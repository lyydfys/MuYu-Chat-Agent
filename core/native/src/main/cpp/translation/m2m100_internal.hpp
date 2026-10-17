// T2B19 / R7-C8 (2026-10-14): internal split scaffold for m2m100.cpp (station B).
// Type block L68-220 of the pre-split m2m100.cpp moved verbatim; the six cross-TU
// entry points below are the split seams.  Declarations only - definitions live in
// m2m100_tokenizer.cpp / m2m100_graphs.cpp.  The visibility pragma keeps them out
// of the dynamic surface of a future libmca_translation_native.so.
#pragma once

#include "m2m100.h"

#include "ggml-backend.h"
#include "ggml.h"

#include <cstdint>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>

// ── Hyperparameters ──────────────────────────────────────────────

struct m2m100_hparams {
    int vocab_size = 128112;
    int d_model = 1024;
    int enc_n_layers = 12;
    int enc_n_heads = 16;
    int enc_ffn_dim = 4096;
    int dec_n_layers = 12;
    int dec_n_heads = 16;
    int dec_ffn_dim = 4096;
    int max_position_emb = 1024;
    bool scale_embedding = true;
    int bos_token_id = 0;
    int eos_token_id = 2;
    int pad_token_id = 1;
    int dec_start_token = 2;
    int head_dim() const { return d_model / enc_n_heads; }
};

// ── Encoder layer tensors ────────────────────────────────────────

struct m2m100_enc_layer {
    ggml_tensor* attn_q_w = nullptr;
    ggml_tensor* attn_q_b = nullptr;
    ggml_tensor* attn_k_w = nullptr;
    ggml_tensor* attn_k_b = nullptr;
    ggml_tensor* attn_v_w = nullptr;
    ggml_tensor* attn_v_b = nullptr;
    ggml_tensor* attn_o_w = nullptr;
    ggml_tensor* attn_o_b = nullptr;
    ggml_tensor* attn_ln_w = nullptr;
    ggml_tensor* attn_ln_b = nullptr;
    ggml_tensor* ffn_up_w = nullptr;
    ggml_tensor* ffn_up_b = nullptr;
    ggml_tensor* ffn_down_w = nullptr;
    ggml_tensor* ffn_down_b = nullptr;
    ggml_tensor* ffn_ln_w = nullptr;
    ggml_tensor* ffn_ln_b = nullptr;
};

// ── Decoder layer tensors ────────────────────────────────────────

struct m2m100_dec_layer {
    // Self-attention
    ggml_tensor* attn_q_w = nullptr;
    ggml_tensor* attn_q_b = nullptr;
    ggml_tensor* attn_k_w = nullptr;
    ggml_tensor* attn_k_b = nullptr;
    ggml_tensor* attn_v_w = nullptr;
    ggml_tensor* attn_v_b = nullptr;
    ggml_tensor* attn_o_w = nullptr;
    ggml_tensor* attn_o_b = nullptr;
    ggml_tensor* attn_ln_w = nullptr;
    ggml_tensor* attn_ln_b = nullptr;
    // Cross-attention
    ggml_tensor* cross_q_w = nullptr;
    ggml_tensor* cross_q_b = nullptr;
    ggml_tensor* cross_k_w = nullptr;
    ggml_tensor* cross_k_b = nullptr;
    ggml_tensor* cross_v_w = nullptr;
    ggml_tensor* cross_v_b = nullptr;
    ggml_tensor* cross_o_w = nullptr;
    ggml_tensor* cross_o_b = nullptr;
    ggml_tensor* cross_ln_w = nullptr;
    ggml_tensor* cross_ln_b = nullptr;
    // FFN
    ggml_tensor* ffn_up_w = nullptr;
    ggml_tensor* ffn_up_b = nullptr;
    ggml_tensor* ffn_down_w = nullptr;
    ggml_tensor* ffn_down_b = nullptr;
    ggml_tensor* ffn_ln_w = nullptr;
    ggml_tensor* ffn_ln_b = nullptr;
};

// ── Model ────────────────────────────────────────────────────────

struct m2m100_model {
    m2m100_hparams hp;

    // Shared embedding (encoder + decoder + lm_head)
    ggml_tensor* shared_embed = nullptr;

    // Positional embeddings (pre-computed sinusoidal)
    ggml_tensor* enc_pos_emb = nullptr;
    ggml_tensor* dec_pos_emb = nullptr;

    // Encoder
    std::vector<m2m100_enc_layer> enc_layers;
    ggml_tensor* enc_out_ln_w = nullptr;
    ggml_tensor* enc_out_ln_b = nullptr;

    // Decoder
    std::vector<m2m100_dec_layer> dec_layers;
    ggml_tensor* dec_out_ln_w = nullptr;
    ggml_tensor* dec_out_ln_b = nullptr;
    // lm_head shares shared_embed (tied weights)
};

// ── Tokenizer ────────────────────────────────────────────────────

struct m2m100_tokenizer {
    std::vector<std::string> id_to_token;
    std::map<std::string, int> token_to_id;
    // BPE merge scores (= SPM piece scores) + hash map, for core_spm::tokenize_bpe.
    // token_to_id_u / id_to_token include ~390 SPM "intermediate" pieces (ids
    // >= embed_vocab_size) that are absent from the embedding vocab but needed as
    // intermediate BPE merges; a final token with id >= embed_vocab_size maps to
    // <unk> (matches HF's convert_token_to_id).
    std::vector<float> scores;
    std::unordered_map<std::string, int32_t> token_to_id_u;
    int embed_vocab_size = 0; // ids < this have embedding rows
    // Language code → token ID
    std::vector<std::string> lang_codes;
    std::map<std::string, int> lang_to_token_id;
};

// ── Context ──────────────────────────────────────────────────────

struct m2m100_context {
    m2m100_context_params params;
    m2m100_model model;
    m2m100_tokenizer tokenizer;

    // Weight storage
    ggml_context* ctx_w = nullptr;
    ggml_backend_buffer_t buf_w = nullptr;
    std::map<std::string, ggml_tensor*> tensors;

    // Backend
    ggml_backend_t backend = nullptr;
    ggml_backend_t backend_cpu = nullptr;

    // Compute
    ggml_backend_sched_t sched = nullptr;
    std::vector<uint8_t> compute_meta;

    // KV cache for decoder self-attention
    ggml_tensor* kv_k = nullptr;
    ggml_tensor* kv_v = nullptr;
    ggml_context* kv_ctx = nullptr;
    ggml_backend_buffer_t kv_buf = nullptr;
    int kv_max_ctx = 0;

    // Cross-attention KV cache (computed once per encoder run)
    std::vector<ggml_tensor*> cross_kv_k;
    std::vector<ggml_tensor*> cross_kv_v;
    ggml_context* cross_kv_ctx = nullptr;
    ggml_backend_buffer_t cross_kv_buf = nullptr;
    int cross_T_enc = 0;
    int beam_size = 1;
};

#pragma GCC visibility push(hidden)
namespace m2m100_detail {

std::vector<int> tokenize(const m2m100_tokenizer& tok, const std::string& text, const std::string& src_lang);

std::string detokenize(const m2m100_tokenizer& tok, const std::vector<int>& ids);

bool alloc_kv_cache(m2m100_context* c, int max_ctx);

bool compute_cross_kv(m2m100_context* c, const float* enc_out, int T_enc);

std::vector<float> run_encoder(m2m100_context* c, const std::vector<int>& token_ids);

std::vector<float> run_decoder_step(m2m100_context* c, const int* tokens, int n_tokens, int offset);

}  // namespace m2m100_detail
#pragma GCC visibility pop
