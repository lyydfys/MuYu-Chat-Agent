// m2m100.cpp — M2M-100 machine translation via ggml
//
// Architecture:
//   Encoder: 12-layer transformer (self-attention + FFN, pre-norm, ReLU)
//   Decoder: 12-layer transformer with cross-attention + KV cache
//   Shared embedding: encoder, decoder, and lm_head share one table
//   Sinusoidal positional embeddings (pre-computed, stored in GGUF)
//
// Supports facebook/m2m100_418M, m2m100_1.2B, and wmt21-dense-24-wide
// (same architecture at different scales).

#include "m2m100.h"
#include "core/gguf_loader.h"
#if !defined(MCA_TRANSLATION_CPU_ONLY)
#include "core/gpu_backend_pref.h" // Optional upstream GPU backend selection.
#endif
#if defined(GGML_USE_METAL)
#include "ggml-metal.h" // ggml_backend_is_metal (§232 CUDA/Vulkan-default gate)
#endif

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml.h"
#include "gguf.h"

#include <algorithm>
#include <cassert>
#include <chrono>
#include "core/sentencepiece.h"
#include "core/crispasr_env.h"

#include <cmath>
#include <limits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>

#include "m2m100_internal.hpp"

// ── Helpers ──────────────────────────────────────────────────────

static ggml_tensor* T(m2m100_context* c, const char* name) {
    auto it = c->tensors.find(name);
    return (it != c->tensors.end()) ? it->second : nullptr;
}

static ggml_tensor* TR(m2m100_context* c, const char* name) {
    auto* t = T(c, name);
    if (!t) {
        fprintf(stderr, "m2m100: required tensor '%s' not found\n", name);
    }
    return t;
}

// ── Load metadata ────────────────────────────────────────────────

static void load_metadata(m2m100_context* c, gguf_context* g) {
    auto& hp = c->model.hp;
    auto get_u32 = [&](const char* key, int def) -> int {
        int idx = gguf_find_key(g, key);
        return (idx >= 0) ? (int)gguf_get_val_u32(g, idx) : def;
    };
    hp.vocab_size = get_u32("m2m100.vocab_size", 128112);
    hp.d_model = get_u32("m2m100.d_model", 1024);
    hp.enc_n_layers = get_u32("m2m100.encoder.n_layers", 12);
    hp.enc_n_heads = get_u32("m2m100.encoder.n_heads", 16);
    hp.enc_ffn_dim = get_u32("m2m100.encoder.ffn_dim", 4096);
    hp.dec_n_layers = get_u32("m2m100.decoder.n_layers", 12);
    hp.dec_n_heads = get_u32("m2m100.decoder.n_heads", 16);
    hp.dec_ffn_dim = get_u32("m2m100.decoder.ffn_dim", 4096);
    hp.max_position_emb = get_u32("m2m100.max_position_embeddings", 1024);
    hp.scale_embedding = get_u32("m2m100.scale_embedding", 1) != 0;
    hp.bos_token_id = get_u32("m2m100.bos_token_id", 0);
    hp.eos_token_id = get_u32("m2m100.eos_token_id", 2);
    hp.pad_token_id = get_u32("m2m100.pad_token_id", 1);
    hp.dec_start_token = get_u32("m2m100.decoder_start_token_id", 2);

    // Load tokenizer
    {
        int tidx = gguf_find_key(g, "tokenizer.ggml.tokens");
        if (tidx >= 0) {
            int n = gguf_get_arr_n(g, tidx);
            c->tokenizer.id_to_token.resize(n);
            c->tokenizer.token_to_id_u.reserve(n * 2);
            for (int i = 0; i < n; i++) {
                c->tokenizer.id_to_token[i] = gguf_get_arr_str(g, tidx, i);
                c->tokenizer.token_to_id[c->tokenizer.id_to_token[i]] = i;
                c->tokenizer.token_to_id_u[c->tokenizer.id_to_token[i]] = i;
            }
        }
        c->tokenizer.embed_vocab_size = hp.vocab_size; // ids >= this are BPE-intermediate only
        // BPE merge scores (= SPM piece scores). M2M-100's SP model is BPE, not
        // Unigram, so tokenization follows merge order — greedy longest-match
        // mis-splits words and degrades translations.
        int sidx = gguf_find_key(g, "tokenizer.ggml.scores");
        if (sidx >= 0) {
            int n = gguf_get_arr_n(g, sidx);
            c->tokenizer.scores.resize(n);
            const float* sp = (const float*)gguf_get_arr_data(g, sidx);
            for (int i = 0; i < n; i++)
                c->tokenizer.scores[i] = sp[i];
        }
    }

    // Load language codes
    {
        int cidx = gguf_find_key(g, "m2m100.lang_codes");
        int iidx = gguf_find_key(g, "m2m100.lang_token_ids");
        if (cidx >= 0 && iidx >= 0) {
            int n = gguf_get_arr_n(g, cidx);
            c->tokenizer.lang_codes.resize(n);
            for (int i = 0; i < n; i++) {
                std::string code = gguf_get_arr_str(g, cidx, i);
                c->tokenizer.lang_codes[i] = code;
                // Read corresponding token ID
                uint32_t tok_id = ((const uint32_t*)gguf_get_arr_data(g, iidx))[i];
                c->tokenizer.lang_to_token_id[code] = (int)tok_id;
            }
        }
    }
}

// ── Bind tensors ─────────────────────────────────────────────────

static bool bind_model(m2m100_context* c) {
    auto& m = c->model;
    const auto& hp = m.hp;

    m.shared_embed = TR(c, "shared.embed.weight");
    m.enc_pos_emb = TR(c, "enc.pos_emb");
    m.dec_pos_emb = TR(c, "dec.pos_emb");
    if (!m.shared_embed || !m.enc_pos_emb || !m.dec_pos_emb)
        return false;

    // Encoder layers
    m.enc_layers.resize(hp.enc_n_layers);
    for (int i = 0; i < hp.enc_n_layers; i++) {
        auto& l = m.enc_layers[i];
        char buf[128];
        auto w = [&](const char* suffix) -> ggml_tensor* {
            snprintf(buf, sizeof(buf), "enc.blk.%d.%s", i, suffix);
            return T(c, buf);
        };
        l.attn_q_w = w("attn_q.weight");
        l.attn_q_b = w("attn_q.bias");
        l.attn_k_w = w("attn_k.weight");
        l.attn_k_b = w("attn_k.bias");
        l.attn_v_w = w("attn_v.weight");
        l.attn_v_b = w("attn_v.bias");
        l.attn_o_w = w("attn_o.weight");
        l.attn_o_b = w("attn_o.bias");
        l.attn_ln_w = w("attn_ln.weight");
        l.attn_ln_b = w("attn_ln.bias");
        l.ffn_up_w = w("ffn_up.weight");
        l.ffn_up_b = w("ffn_up.bias");
        l.ffn_down_w = w("ffn_down.weight");
        l.ffn_down_b = w("ffn_down.bias");
        l.ffn_ln_w = w("ffn_ln.weight");
        l.ffn_ln_b = w("ffn_ln.bias");
    }
    m.enc_out_ln_w = TR(c, "enc.out_ln.weight");
    m.enc_out_ln_b = TR(c, "enc.out_ln.bias");

    // Decoder layers
    m.dec_layers.resize(hp.dec_n_layers);
    for (int i = 0; i < hp.dec_n_layers; i++) {
        auto& l = m.dec_layers[i];
        char buf[128];
        auto w = [&](const char* suffix) -> ggml_tensor* {
            snprintf(buf, sizeof(buf), "dec.blk.%d.%s", i, suffix);
            return T(c, buf);
        };
        l.attn_q_w = w("attn_q.weight");
        l.attn_q_b = w("attn_q.bias");
        l.attn_k_w = w("attn_k.weight");
        l.attn_k_b = w("attn_k.bias");
        l.attn_v_w = w("attn_v.weight");
        l.attn_v_b = w("attn_v.bias");
        l.attn_o_w = w("attn_o.weight");
        l.attn_o_b = w("attn_o.bias");
        l.attn_ln_w = w("attn_ln.weight");
        l.attn_ln_b = w("attn_ln.bias");
        l.cross_q_w = w("cross_q.weight");
        l.cross_q_b = w("cross_q.bias");
        l.cross_k_w = w("cross_k.weight");
        l.cross_k_b = w("cross_k.bias");
        l.cross_v_w = w("cross_v.weight");
        l.cross_v_b = w("cross_v.bias");
        l.cross_o_w = w("cross_o.weight");
        l.cross_o_b = w("cross_o.bias");
        l.cross_ln_w = w("cross_ln.weight");
        l.cross_ln_b = w("cross_ln.bias");
        l.ffn_up_w = w("ffn_up.weight");
        l.ffn_up_b = w("ffn_up.bias");
        l.ffn_down_w = w("ffn_down.weight");
        l.ffn_down_b = w("ffn_down.bias");
        l.ffn_ln_w = w("ffn_ln.weight");
        l.ffn_ln_b = w("ffn_ln.bias");
    }
    m.dec_out_ln_w = TR(c, "dec.out_ln.weight");
    m.dec_out_ln_b = TR(c, "dec.out_ln.bias");

    return true;
}

// ── Public API ───────────────────────────────────────────────────

extern "C" struct m2m100_context_params m2m100_context_default_params(void) {
    m2m100_context_params p{};
    p.n_threads = 4;
    p.verbosity = 1;
    p.use_gpu = true; // §232: GPU allowed by default; the is_metal gate + env decide actual use
    return p;
}

extern "C" struct m2m100_context* m2m100_init_from_file(const char* path_model, struct m2m100_context_params params) {
    auto* c = new m2m100_context();
    c->params = params;

    // Pass 1: metadata
    {
        gguf_context* g = core_gguf::open_metadata(path_model);
        if (!g) {
            delete c;
            return nullptr;
        }
        load_metadata(c, g);
        core_gguf::free_metadata(g);
    }

    const auto& hp = c->model.hp;
    if (params.verbosity >= 1) {
        fprintf(stderr, "m2m100: d=%d enc=%dL dec=%dL heads=%d ffn=%d vocab=%d langs=%d\n", hp.d_model, hp.enc_n_layers,
                hp.dec_n_layers, hp.enc_n_heads, hp.enc_ffn_dim, hp.vocab_size, (int)c->tokenizer.lang_codes.size());
    }

    // Backend selection (§232). m2m100 loads weights + KV onto c->backend via
    // core_gguf::load_weights + ggml_backend_alloc_ctx_tensors, so picking a GPU
    // backend is the whole change.
    //   * CRISPASR_M2M100_GPU=1 forces GPU on ANY backend; =0 forces CPU.
    //   * default: GPU on CUDA/Vulkan, CPU on Metal. Kaggle P100 A/B: identical
    //     en->de output, 1.24x wall (slow OpenBLAS baseline). On M1 (Accelerate)
    //     neutral — small encoder-decoder AR, launch-bound (LEARNING 34) — so
    //     Metal stays CPU unless forced. Mirrors LEARNING 34's is_metal gate.
#if defined(MCA_TRANSLATION_CPU_ONLY)
    // MCA deliberately ships this auxiliary translator as an isolated CPU-only
    // runtime. It must never inherit another model's GPU preference or load a
    // second copy of a platform backend in the app process.
    c->backend_cpu = ggml_backend_cpu_init();
    c->backend = c->backend_cpu;
#else
    c->backend_cpu = ggml_backend_cpu_init();
    const char* gpu_env = std::getenv("CRISPASR_M2M100_GPU");
    const bool force_gpu = gpu_env && std::atoi(gpu_env) != 0;
    const bool force_cpu = gpu_env && std::atoi(gpu_env) == 0;
    c->backend = c->backend_cpu;
    if (!force_cpu && (force_gpu || params.use_gpu)) {
        ggml_backend_t gpu = crispasr_init_gpu_backend();
        if (gpu) {
            bool is_metal = false;
#if defined(GGML_USE_METAL)
            is_metal = ggml_backend_is_metal(gpu);
#endif
            if (!is_metal || force_gpu) {
                c->backend = gpu;
                if (params.verbosity >= 1)
                    fprintf(stderr, "m2m100: GPU backend enabled (%s)\n", ggml_backend_name(c->backend));
            } else {
                ggml_backend_free(gpu);
                if (params.verbosity >= 1)
                    fprintf(stderr, "m2m100: GPU default limited to CUDA/Vulkan (Metal neutral); set "
                                    "CRISPASR_M2M100_GPU=1 to force\n");
            }
        }
    }
#endif

    // Pass 2: weights
    {
        core_gguf::WeightLoad wl;
        if (!core_gguf::load_weights(path_model, c->backend, "m2m100", wl)) {
            delete c;
            return nullptr;
        }
        c->ctx_w = wl.ctx;
        c->buf_w = wl.buf;
        c->tensors = std::move(wl.tensors);
    }

    if (!bind_model(c)) {
        fprintf(stderr, "m2m100: failed to bind tensors\n");
        delete c;
        return nullptr;
    }

    // Compute scheduler
    {
        ggml_backend_t backends[] = {c->backend, c->backend_cpu};
        int n_be = (c->backend != c->backend_cpu) ? 2 : 1;
        c->sched = ggml_backend_sched_new(backends, nullptr, n_be, 8192, false, false);
        c->compute_meta.resize(ggml_tensor_overhead() * 8192 + ggml_graph_overhead_custom(8192, false));
    }

    return c;
}

extern "C" void m2m100_free(struct m2m100_context* ctx) {
    if (!ctx)
        return;
    if (ctx->cross_kv_buf)
        ggml_backend_buffer_free(ctx->cross_kv_buf);
    if (ctx->cross_kv_ctx)
        ggml_free(ctx->cross_kv_ctx);
    if (ctx->kv_buf)
        ggml_backend_buffer_free(ctx->kv_buf);
    if (ctx->kv_ctx)
        ggml_free(ctx->kv_ctx);
    if (ctx->sched)
        ggml_backend_sched_free(ctx->sched);
    if (ctx->buf_w)
        ggml_backend_buffer_free(ctx->buf_w);
    if (ctx->ctx_w)
        ggml_free(ctx->ctx_w);
    if (ctx->backend && ctx->backend != ctx->backend_cpu)
        ggml_backend_free(ctx->backend);
    if (ctx->backend_cpu)
        ggml_backend_free(ctx->backend_cpu);
    delete ctx;
}

extern "C" void m2m100_set_beam_size(struct m2m100_context* ctx, int beam_size) {
    if (!ctx)
        return;
    ctx->beam_size = beam_size > 1 ? beam_size : 1;
}

extern "C" int m2m100_n_languages(struct m2m100_context* ctx) {
    return ctx ? (int)ctx->tokenizer.lang_codes.size() : 0;
}

extern "C" const char* m2m100_language(struct m2m100_context* ctx, int index) {
    if (!ctx || index < 0 || index >= (int)ctx->tokenizer.lang_codes.size())
        return nullptr;
    return ctx->tokenizer.lang_codes[index].c_str();
}
