// T2B19 / R7-C8 (2026-10-14): m2m100.cpp split - graphs TU (L493-935 moved verbatim).
// Owns alloc_kv_cache / compute_cross_kv / run_encoder / run_decoder_step (all four drop
// `static` and join namespace m2m100_detail, declared hidden in m2m100_internal.hpp) plus
// the three TU-local statics alloc_cross_kv / build_encoder_graph / build_decoder_graph.

#include "m2m100_internal.hpp"

#include <cstdio>
#include <cstring>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"

namespace m2m100_detail {

// ── KV cache allocation ──────────────────────────────────────────

bool alloc_kv_cache(m2m100_context* c, int max_ctx) {
    const auto& hp = c->model.hp;
    const int hd = hp.head_dim();
    const int nh = hp.dec_n_heads;
    const int nl = hp.dec_n_layers;

    size_t n_tensors = 2; // k, v
    size_t ctx_size = ggml_tensor_overhead() * n_tensors + 64;
    ggml_init_params params = {ctx_size, nullptr, true};
    c->kv_ctx = ggml_init(params);

    // Shape: (head_dim, max_ctx, n_heads, n_layers)
    c->kv_k = ggml_new_tensor_4d(c->kv_ctx, GGML_TYPE_F16, hd, max_ctx, nh, nl);
    c->kv_v = ggml_new_tensor_4d(c->kv_ctx, GGML_TYPE_F16, hd, max_ctx, nh, nl);
    ggml_set_name(c->kv_k, "kv_k");
    ggml_set_name(c->kv_v, "kv_v");

    c->kv_buf = ggml_backend_alloc_ctx_tensors(c->kv_ctx, c->backend);
    if (!c->kv_buf)
        return false;

    // Zero-init
    ggml_backend_buffer_clear(c->kv_buf, 0);
    c->kv_max_ctx = max_ctx;
    return true;
}

// ── Cross-attention KV cache ─────────────────────────────────────

static bool alloc_cross_kv(m2m100_context* c, int T_enc) {
    const auto& hp = c->model.hp;
    const int hd = hp.head_dim();
    const int nh = hp.dec_n_heads;
    const int nl = hp.dec_n_layers;

    // Free existing
    if (c->cross_kv_buf) {
        ggml_backend_buffer_free(c->cross_kv_buf);
        c->cross_kv_buf = nullptr;
    }
    if (c->cross_kv_ctx) {
        ggml_free(c->cross_kv_ctx);
        c->cross_kv_ctx = nullptr;
    }

    size_t ctx_size = ggml_tensor_overhead() * nl * 2 + 64;
    ggml_init_params params = {ctx_size, nullptr, true};
    c->cross_kv_ctx = ggml_init(params);

    c->cross_kv_k.resize(nl);
    c->cross_kv_v.resize(nl);
    for (int i = 0; i < nl; i++) {
        c->cross_kv_k[i] = ggml_new_tensor_3d(c->cross_kv_ctx, GGML_TYPE_F16, hd, T_enc, nh);
        c->cross_kv_v[i] = ggml_new_tensor_3d(c->cross_kv_ctx, GGML_TYPE_F16, hd, T_enc, nh);
        char name[64];
        snprintf(name, sizeof(name), "cross_k_%d", i);
        ggml_set_name(c->cross_kv_k[i], name);
        snprintf(name, sizeof(name), "cross_v_%d", i);
        ggml_set_name(c->cross_kv_v[i], name);
    }

    c->cross_kv_buf = ggml_backend_alloc_ctx_tensors(c->cross_kv_ctx, c->backend);
    if (!c->cross_kv_buf)
        return false;

    c->cross_T_enc = T_enc;
    return true;
}

// ── Encoder graph ────────────────────────────────────────────────

static ggml_cgraph* build_encoder_graph(m2m100_context* c, int T) {
    const auto& m = c->model;
    const auto& hp = m.hp;
    const int D = hp.d_model;
    const int nh = hp.enc_n_heads;
    const int hd = hp.head_dim();
    const float scale = 1.0f / sqrtf((float)hd);
    const float emb_scale = hp.scale_embedding ? sqrtf((float)D) : 1.0f;

    ggml_init_params ip = {c->compute_meta.size(), c->compute_meta.data(), true};
    ggml_context* ctx0 = ggml_init(ip);
    ggml_cgraph* gf = ggml_new_graph_custom(ctx0, 8192, false);

    // Input token IDs
    ggml_tensor* inp = ggml_new_tensor_1d(ctx0, GGML_TYPE_I32, T);
    ggml_set_name(inp, "enc_tokens");
    ggml_set_input(inp);

    // Position IDs
    ggml_tensor* pos = ggml_new_tensor_1d(ctx0, GGML_TYPE_I32, T);
    ggml_set_name(pos, "enc_positions");
    ggml_set_input(pos);

    // Embedding: shared_embed[tokens] * scale + pos_emb[positions]
    ggml_tensor* cur = ggml_get_rows(ctx0, m.shared_embed, inp);
    if (emb_scale != 1.0f) {
        cur = ggml_scale(ctx0, cur, emb_scale);
    }
    cur = ggml_add(ctx0, cur, ggml_get_rows(ctx0, m.enc_pos_emb, pos));

    // Encoder layers
    for (int il = 0; il < hp.enc_n_layers; il++) {
        const auto& l = m.enc_layers[il];
        ggml_tensor* residual = cur;

        // Pre-norm for self-attention
        cur = ggml_norm(ctx0, cur, 1e-5f);
        cur = ggml_mul(ctx0, cur, l.attn_ln_w);
        cur = ggml_add(ctx0, cur, l.attn_ln_b);

        // Self-attention Q, K, V
        ggml_tensor* Q = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_q_w, cur), l.attn_q_b);
        ggml_tensor* K = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_k_w, cur), l.attn_k_b);
        ggml_tensor* V = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_v_w, cur), l.attn_v_b);

        // Reshape for multi-head: (D, T) → (hd, nh, T) → permute to (hd, T, nh)
        Q = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, Q, hd, nh, T), 0, 2, 1, 3));
        K = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, K, hd, nh, T), 0, 2, 1, 3));
        V = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, V, hd, nh, T), 0, 2, 1, 3));

        // Flash attention (no causal mask for encoder)
        ggml_tensor* attn = ggml_flash_attn_ext(ctx0, Q, K, V, nullptr, scale, 0.0f, 0.0f);
        attn = ggml_reshape_2d(ctx0, attn, D, T);

        // Output projection + residual
        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_o_w, attn), l.attn_o_b);
        cur = ggml_add(ctx0, cur, residual);

        // FFN
        residual = cur;
        cur = ggml_norm(ctx0, cur, 1e-5f);
        cur = ggml_mul(ctx0, cur, l.ffn_ln_w);
        cur = ggml_add(ctx0, cur, l.ffn_ln_b);

        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.ffn_up_w, cur), l.ffn_up_b);
        cur = ggml_relu(ctx0, cur);
        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.ffn_down_w, cur), l.ffn_down_b);

        cur = ggml_add(ctx0, cur, residual);
    }

    // Final encoder LayerNorm
    cur = ggml_norm(ctx0, cur, 1e-5f);
    cur = ggml_mul(ctx0, cur, m.enc_out_ln_w);
    cur = ggml_add(ctx0, cur, m.enc_out_ln_b);

    ggml_set_name(cur, "enc_out");
    ggml_build_forward_expand(gf, cur);

    ggml_free(ctx0);
    return gf;
}

// ── Compute cross-attention K, V from encoder output ─────────────

bool compute_cross_kv(m2m100_context* c, const float* enc_out, int T_enc) {
    const auto& m = c->model;
    const auto& hp = m.hp;
    const int D = hp.d_model;
    const int nh = hp.dec_n_heads;
    const int hd = hp.head_dim();

    if (!alloc_cross_kv(c, T_enc))
        return false;

    // Build a ggml graph to compute cross-K and cross-V for all layers.
    // This handles F16/quantized weights natively via ggml_mul_mat.
    for (int il = 0; il < hp.dec_n_layers; il++) {
        const auto& l = m.dec_layers[il];

        ggml_init_params ip = {c->compute_meta.size(), c->compute_meta.data(), true};
        ggml_context* ctx0 = ggml_init(ip);
        ggml_cgraph* gf = ggml_new_graph_custom(ctx0, 256, false);

        ggml_tensor* enc_inp = ggml_new_tensor_2d(ctx0, GGML_TYPE_F32, D, T_enc);
        ggml_set_name(enc_inp, "enc_for_cross");
        ggml_set_input(enc_inp);

        // K = W_k @ enc + b_k → (D, T_enc)
        ggml_tensor* K = ggml_mul_mat(ctx0, l.cross_k_w, enc_inp);
        if (l.cross_k_b)
            K = ggml_add(ctx0, K, l.cross_k_b);
        // Reshape to (hd, nh, T_enc) then permute to (hd, T_enc, nh)
        K = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, K, hd, nh, T_enc), 0, 2, 1, 3));
        ggml_set_name(K, "cross_k");

        // V = W_v @ enc + b_v
        ggml_tensor* V = ggml_mul_mat(ctx0, l.cross_v_w, enc_inp);
        if (l.cross_v_b)
            V = ggml_add(ctx0, V, l.cross_v_b);
        V = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, V, hd, nh, T_enc), 0, 2, 1, 3));
        ggml_set_name(V, "cross_v");

        ggml_build_forward_expand(gf, K);
        ggml_build_forward_expand(gf, V);

        ggml_backend_sched_reset(c->sched);
        if (!ggml_backend_sched_alloc_graph(c->sched, gf)) {
            ggml_free(ctx0);
            return false;
        }
        ggml_backend_tensor_set(ggml_graph_get_tensor(gf, "enc_for_cross"), enc_out, 0,
                                (size_t)D * T_enc * sizeof(float));

        if (ggml_backend_sched_graph_compute(c->sched, gf) != GGML_STATUS_SUCCESS) {
            ggml_free(ctx0);
            return false;
        }

        // Copy results to cross_kv tensors
        ggml_tensor* K_out = ggml_graph_get_tensor(gf, "cross_k");
        ggml_tensor* V_out = ggml_graph_get_tensor(gf, "cross_v");
        const size_t n_elem = (size_t)hd * T_enc * nh;
        std::vector<float> buf(n_elem);
        std::vector<ggml_fp16_t> buf16(n_elem);
        ggml_backend_tensor_get(K_out, buf.data(), 0, n_elem * sizeof(float));
        ggml_fp32_to_fp16_row(buf.data(), buf16.data(), (int)n_elem);
        ggml_backend_tensor_set(c->cross_kv_k[il], buf16.data(), 0, n_elem * sizeof(ggml_fp16_t));
        ggml_backend_tensor_get(V_out, buf.data(), 0, n_elem * sizeof(float));
        ggml_fp32_to_fp16_row(buf.data(), buf16.data(), (int)n_elem);
        ggml_backend_tensor_set(c->cross_kv_v[il], buf16.data(), 0, n_elem * sizeof(ggml_fp16_t));

        ggml_free(ctx0);
    }

    return true;
}

// ── Decoder graph ────────────────────────────────────────────────

static ggml_cgraph* build_decoder_graph(m2m100_context* c, int n_tokens, int offset) {
    const auto& m = c->model;
    const auto& hp = m.hp;
    const int D = hp.d_model;
    const int nh = hp.dec_n_heads;
    const int hd = hp.head_dim();
    const float attn_scale = 1.0f / sqrtf((float)hd);
    const float emb_scale = hp.scale_embedding ? sqrtf((float)D) : 1.0f;
    const int Lk = offset + n_tokens;

    ggml_init_params ip = {c->compute_meta.size(), c->compute_meta.data(), true};
    ggml_context* ctx0 = ggml_init(ip);
    ggml_cgraph* gf = ggml_new_graph_custom(ctx0, 8192, false);

    ggml_tensor* inp = ggml_new_tensor_1d(ctx0, GGML_TYPE_I32, n_tokens);
    ggml_set_name(inp, "dec_tokens");
    ggml_set_input(inp);

    ggml_tensor* pos = ggml_new_tensor_1d(ctx0, GGML_TYPE_I32, n_tokens);
    ggml_set_name(pos, "dec_positions");
    ggml_set_input(pos);

    // Causal mask for self-attention (only when n_tokens > 1)
    ggml_tensor* causal_mask = nullptr;
    if (n_tokens > 1) {
        causal_mask = ggml_new_tensor_2d(ctx0, GGML_TYPE_F16, Lk, n_tokens);
        ggml_set_name(causal_mask, "causal_mask");
        ggml_set_input(causal_mask);
    }

    // Embedding
    ggml_tensor* cur = ggml_get_rows(ctx0, m.shared_embed, inp);
    if (emb_scale != 1.0f) {
        cur = ggml_scale(ctx0, cur, emb_scale);
    }
    cur = ggml_add(ctx0, cur, ggml_get_rows(ctx0, m.dec_pos_emb, pos));

    for (int il = 0; il < hp.dec_n_layers; il++) {
        const auto& l = m.dec_layers[il];
        ggml_tensor* residual = cur;

        // ---- Self-attention ----
        cur = ggml_norm(ctx0, cur, 1e-5f);
        cur = ggml_mul(ctx0, cur, l.attn_ln_w);
        cur = ggml_add(ctx0, cur, l.attn_ln_b);

        ggml_tensor* Q = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_q_w, cur), l.attn_q_b);
        ggml_tensor* K = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_k_w, cur), l.attn_k_b);
        ggml_tensor* V = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_v_w, cur), l.attn_v_b);

        // Reshape + permute to (hd, n_tokens, nh)
        Q = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, Q, hd, nh, n_tokens), 0, 2, 1, 3));
        ggml_tensor* K_new =
            ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, K, hd, nh, n_tokens), 0, 2, 1, 3));
        ggml_tensor* V_new =
            ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, V, hd, nh, n_tokens), 0, 2, 1, 3));

        // Write to KV cache
        ggml_tensor* k_view =
            ggml_view_4d(ctx0, c->kv_k, hd, n_tokens, nh, 1, c->kv_k->nb[1], c->kv_k->nb[2], c->kv_k->nb[3],
                         (size_t)il * c->kv_k->nb[3] + (size_t)offset * c->kv_k->nb[1]);
        ggml_tensor* v_view =
            ggml_view_4d(ctx0, c->kv_v, hd, n_tokens, nh, 1, c->kv_v->nb[1], c->kv_v->nb[2], c->kv_v->nb[3],
                         (size_t)il * c->kv_v->nb[3] + (size_t)offset * c->kv_v->nb[1]);
        ggml_build_forward_expand(gf, ggml_cpy(ctx0, K_new, k_view));
        ggml_build_forward_expand(gf, ggml_cpy(ctx0, V_new, v_view));

        // Read full KV history
        ggml_tensor* Kfull =
            ggml_view_3d(ctx0, c->kv_k, hd, Lk, nh, c->kv_k->nb[1], c->kv_k->nb[2], (size_t)il * c->kv_k->nb[3]);
        ggml_tensor* Vfull =
            ggml_view_3d(ctx0, c->kv_v, hd, Lk, nh, c->kv_v->nb[1], c->kv_v->nb[2], (size_t)il * c->kv_v->nb[3]);
        Kfull = ggml_cont(ctx0, Kfull);
        Vfull = ggml_cont(ctx0, Vfull);

        // Flash attention with causal mask
        ggml_tensor* sa_out =
            ggml_flash_attn_ext(ctx0, Q, Kfull, Vfull, (n_tokens == 1) ? nullptr : causal_mask, attn_scale, 0.0f, 0.0f);
        cur = ggml_reshape_2d(ctx0, sa_out, D, n_tokens);
        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.attn_o_w, cur), l.attn_o_b);
        cur = ggml_add(ctx0, cur, residual);

        // ---- Cross-attention ----
        residual = cur;
        cur = ggml_norm(ctx0, cur, 1e-5f);
        cur = ggml_mul(ctx0, cur, l.cross_ln_w);
        cur = ggml_add(ctx0, cur, l.cross_ln_b);

        ggml_tensor* CQ = ggml_add(ctx0, ggml_mul_mat(ctx0, l.cross_q_w, cur), l.cross_q_b);
        CQ = ggml_cont(ctx0, ggml_permute(ctx0, ggml_reshape_3d(ctx0, CQ, hd, nh, n_tokens), 0, 2, 1, 3));

        ggml_tensor* CK = c->cross_kv_k[il];
        ggml_tensor* CV = c->cross_kv_v[il];

        ggml_tensor* ca_out = ggml_flash_attn_ext(ctx0, CQ, CK, CV, nullptr, attn_scale, 0.0f, 0.0f);
        cur = ggml_reshape_2d(ctx0, ca_out, D, n_tokens);
        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.cross_o_w, cur), l.cross_o_b);
        cur = ggml_add(ctx0, cur, residual);

        // ---- FFN ----
        residual = cur;
        cur = ggml_norm(ctx0, cur, 1e-5f);
        cur = ggml_mul(ctx0, cur, l.ffn_ln_w);
        cur = ggml_add(ctx0, cur, l.ffn_ln_b);

        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.ffn_up_w, cur), l.ffn_up_b);
        cur = ggml_relu(ctx0, cur);
        cur = ggml_add(ctx0, ggml_mul_mat(ctx0, l.ffn_down_w, cur), l.ffn_down_b);
        cur = ggml_add(ctx0, cur, residual);
    }

    // Final LayerNorm
    cur = ggml_norm(ctx0, cur, 1e-5f);
    cur = ggml_mul(ctx0, cur, m.dec_out_ln_w);
    cur = ggml_add(ctx0, cur, m.dec_out_ln_b);

    // Take last token
    if (n_tokens > 1) {
        cur = ggml_view_2d(ctx0, cur, D, 1, cur->nb[1], (size_t)(n_tokens - 1) * cur->nb[1]);
    }

    // LM head (tied with shared_embed)
    cur = ggml_mul_mat(ctx0, m.shared_embed, cur);

    ggml_set_name(cur, "logits");
    ggml_build_forward_expand(gf, cur);

    ggml_free(ctx0);
    return gf;
}

// ── Run encoder ──────────────────────────────────────────────────

std::vector<float> run_encoder(m2m100_context* c, const std::vector<int>& token_ids) {
    const int T = (int)token_ids.size();
    const int D = c->model.hp.d_model;

    ggml_cgraph* gf = build_encoder_graph(c, T);
    ggml_backend_sched_reset(c->sched);
    if (!ggml_backend_sched_alloc_graph(c->sched, gf)) {
        fprintf(stderr, "m2m100: failed to alloc encoder graph\n");
        return {};
    }

    // Set input tokens
    ggml_backend_tensor_set(ggml_graph_get_tensor(gf, "enc_tokens"), token_ids.data(), 0, T * sizeof(int32_t));

    // Set position IDs: offset=2 (M2M-100 padding_idx=1, first real pos=2)
    std::vector<int32_t> positions(T);
    for (int i = 0; i < T; i++)
        positions[i] = i + 2;
    ggml_backend_tensor_set(ggml_graph_get_tensor(gf, "enc_positions"), positions.data(), 0, T * sizeof(int32_t));

    if (ggml_backend_sched_graph_compute(c->sched, gf) != GGML_STATUS_SUCCESS) {
        fprintf(stderr, "m2m100: encoder compute failed\n");
        return {};
    }

    ggml_tensor* out = ggml_graph_get_tensor(gf, "enc_out");
    std::vector<float> enc_out(T * D);
    ggml_backend_tensor_get(out, enc_out.data(), 0, enc_out.size() * sizeof(float));
    return enc_out;
}

// ── Run decoder step ─────────────────────────────────────────────

std::vector<float> run_decoder_step(m2m100_context* c, const int* tokens, int n_tokens, int offset) {
    const auto& hp = c->model.hp;
    const int vocab = hp.vocab_size;
    const int Lk = offset + n_tokens;

    ggml_cgraph* gf = build_decoder_graph(c, n_tokens, offset);
    ggml_backend_sched_reset(c->sched);
    if (!ggml_backend_sched_alloc_graph(c->sched, gf)) {
        fprintf(stderr, "m2m100: failed to alloc decoder graph\n");
        return {};
    }

    ggml_backend_tensor_set(ggml_graph_get_tensor(gf, "dec_tokens"), tokens, 0, n_tokens * sizeof(int32_t));

    std::vector<int32_t> positions(n_tokens);
    for (int i = 0; i < n_tokens; i++)
        positions[i] = offset + i + 2; // offset=2
    ggml_backend_tensor_set(ggml_graph_get_tensor(gf, "dec_positions"), positions.data(), 0,
                            n_tokens * sizeof(int32_t));

    // Causal mask
    if (n_tokens > 1) {
        std::vector<ggml_fp16_t> mask((size_t)Lk * n_tokens, ggml_fp32_to_fp16(0.0f));
        const ggml_fp16_t neg_inf = ggml_fp32_to_fp16(-INFINITY);
        for (int q = 0; q < n_tokens; q++) {
            for (int k = offset + q + 1; k < Lk; k++) {
                mask[(size_t)q * Lk + k] = neg_inf;
            }
        }
        ggml_backend_tensor_set(ggml_graph_get_tensor(gf, "causal_mask"), mask.data(), 0,
                                mask.size() * sizeof(ggml_fp16_t));
    }

    if (ggml_backend_sched_graph_compute(c->sched, gf) != GGML_STATUS_SUCCESS) {
        fprintf(stderr, "m2m100: decoder compute failed\n");
        return {};
    }

    ggml_tensor* logits = ggml_graph_get_tensor(gf, "logits");
    std::vector<float> out(vocab);
    ggml_backend_tensor_get(logits, out.data(), 0, vocab * sizeof(float));
    return out;
}

}  // namespace m2m100_detail
