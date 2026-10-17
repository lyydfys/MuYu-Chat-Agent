// T2B19 / R7-C8 (2026-10-14): m2m100.cpp split - translate TU (L1067-1199 moved verbatim).
// The bench pair (L43-67) travels with its only user (m2m100_translate L1076) and the
// beam-decode include (L13) moves here with its only user.  No line inside the moved
// function body is edited; the six split seams come in via using-declarations.

#include "m2m100_internal.hpp"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include "core/beam_decode.h"
#include "core/crispasr_env.h"

// ===========================================================================
// Bench instrumentation — `M2M100_BENCH=1` for per-stage timings.
// ===========================================================================

static bool m2m100_bench_enabled() {
    static int v = -1;
    if (v < 0) {
        const char* e = crispasr_env::get("CRISPASR_M2M100_BENCH");
        v = (e && *e && *e != '0') ? 1 : 0;
    }
    return v != 0;
}

struct m2m100_bench_stage {
    const char* name;
    std::chrono::steady_clock::time_point t0;
    explicit m2m100_bench_stage(const char* n) : name(n), t0(std::chrono::steady_clock::now()) {}
    ~m2m100_bench_stage() {
        if (!m2m100_bench_enabled())
            return;
        auto t1 = std::chrono::steady_clock::now();
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        std::fprintf(stderr, "  m2m100_bench: %-22s %.2f ms\n", name, ms);
    }
};

using m2m100_detail::alloc_kv_cache;
using m2m100_detail::compute_cross_kv;
using m2m100_detail::detokenize;
using m2m100_detail::run_decoder_step;
using m2m100_detail::run_encoder;
using m2m100_detail::tokenize;

extern "C" char* m2m100_translate(struct m2m100_context* ctx, const char* text, const char* src_lang,
                                  const char* tgt_lang, int max_new_tokens) {
    if (!ctx || !text || !src_lang || !tgt_lang)
        return nullptr;
    if (max_new_tokens <= 0)
        max_new_tokens = 200;

    const auto& hp = ctx->model.hp;

    m2m100_bench_stage _bs_total("translate_total");

    // 1. Tokenize input
    std::vector<int> enc_ids = tokenize(ctx->tokenizer, text, src_lang);
    if (ctx->params.verbosity >= 2) {
        fprintf(stderr, "m2m100: input %zu tokens:", enc_ids.size());
        for (int id : enc_ids)
            fprintf(stderr, " %d", id);
        fprintf(stderr, "\n");
    }

    // 2. Run encoder
    std::vector<float> enc_out = run_encoder(ctx, enc_ids);
    if (enc_out.empty())
        return nullptr;

    int T_enc = (int)enc_ids.size();
    if (ctx->params.verbosity >= 1) {
        fprintf(stderr, "m2m100: encoder done, T_enc=%d\n", T_enc);
    }

    // 3. Compute cross-attention KV
    if (!compute_cross_kv(ctx, enc_out.data(), T_enc)) {
        fprintf(stderr, "m2m100: failed to compute cross-KV\n");
        return nullptr;
    }

    // 4. Allocate decoder KV cache
    int dec_max = max_new_tokens + 4;
    if (!alloc_kv_cache(ctx, dec_max)) {
        fprintf(stderr, "m2m100: failed to alloc KV cache\n");
        return nullptr;
    }

    // 5. Greedy decode
    // Start with decoder_start_token_id (eos=2) then forced_bos (target lang token)
    auto tgt_it = ctx->tokenizer.lang_to_token_id.find(tgt_lang);
    if (tgt_it == ctx->tokenizer.lang_to_token_id.end()) {
        fprintf(stderr, "m2m100: unknown target language '%s'\n", tgt_lang);
        return nullptr;
    }
    int forced_bos = tgt_it->second;

    std::vector<int> dec_ids;
    dec_ids.push_back(hp.dec_start_token); // </s> = 2
    dec_ids.push_back(forced_bos);         // __de__ etc.

    // First step: prefill with [dec_start, forced_bos]
    std::vector<float> logits = run_decoder_step(ctx, dec_ids.data(), (int)dec_ids.size(), 0);
    if (logits.empty())
        return nullptr;

    const int prompt_len = (int)dec_ids.size();

    if (ctx->beam_size > 1) {
        // Beam search via replay-from-prefix.
        auto replay = [](m2m100_context* c, const int32_t* toks, int n, int pl) -> float* {
            auto lg = run_decoder_step(c, (const int*)toks, n, pl);
            if (lg.empty())
                return nullptr;
            float* out = (float*)std::malloc(lg.size() * sizeof(float));
            std::memcpy(out, lg.data(), lg.size() * sizeof(float));
            return out;
        };
        core_beam_decode::Config bcfg;
        bcfg.max_new_tokens = max_new_tokens;
        bcfg.eos_id = hp.eos_token_id;
        bcfg.vocab_size = hp.vocab_size;
        bcfg.beam_size = ctx->beam_size;
        bcfg.prompt_len = prompt_len;
        auto br = core_beam_decode::run_with_probs(ctx, logits.data(), replay, bcfg);
        for (int32_t t : br.tokens) {
            if (t == hp.eos_token_id)
                break;
            dec_ids.push_back((int)t);
        }
    } else {
        int offset = prompt_len;
        for (int step = 0; step < max_new_tokens; step++) {
            // Greedy: argmax — NaN-robust (see lfm2_audio note). Seed from -inf,
            // skip non-finite, abort if the whole row is non-finite.
            int best_id = -1;
            float best_val = -std::numeric_limits<float>::infinity();
            for (int i = 0; i < hp.vocab_size; i++) {
                if (std::isfinite(logits[i]) && logits[i] > best_val) {
                    best_val = logits[i];
                    best_id = i;
                }
            }
            if (best_id < 0) {
                fprintf(stderr, "m2m100: non-finite logits — aborting decode\n");
                break;
            }

            if (best_id == hp.eos_token_id)
                break;

            dec_ids.push_back(best_id);

            if (ctx->params.verbosity >= 2) {
                fprintf(stderr, "m2m100[dec]: step=%d tok=%d '%s'\n", step, best_id,
                        best_id < (int)ctx->tokenizer.id_to_token.size() ? ctx->tokenizer.id_to_token[best_id].c_str()
                                                                         : "?");
            }

            // Next step: single token
            logits = run_decoder_step(ctx, &best_id, 1, offset);
            if (logits.empty())
                return nullptr;
            offset++;
        }
    }

    // 6. Detokenize
    std::string result = detokenize(ctx->tokenizer, dec_ids);
    if (ctx->params.verbosity >= 1) {
        fprintf(stderr, "m2m100: translated %zu tokens → '%s'\n", dec_ids.size(), result.c_str());
    }

    char* out = (char*)malloc(result.size() + 1);
    std::memcpy(out, result.c_str(), result.size() + 1);
    return out;
}

