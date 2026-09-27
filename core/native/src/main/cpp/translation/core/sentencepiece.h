// src/core/sentencepiece.h — SentencePiece unigram Viterbi tokenizer (header-only).
//
// Hoists the SentencePiece unigram tokenization that multiple backends
// implement inline:
//
//   t5_translate.cpp  — MADLAD-400 T5 translation (tokenize_sp)
//   indextts.cpp      — IndexTTS speech synthesis  (tokenize_bpe, misnamed)
//
// ---------------------------------------------------------------------------
// Per-source adoption verdict (audited 2026-05-31):
//
//   t5_translate.cpp — FAITHFUL-WITH-CONFIG. Set
//                      cfg.merge_consecutive_unk=false and have the caller
//                      append EOS. cfg.oov_score_default=0.0f (default).
//   indextts.cpp     — FAITHFUL-WITH-CONFIG. Set
//                      cfg.oov_score_default=-20.0f (indextts uses -20.0
//                      for out-of-range token ids, not 0.0).
//   fireredpunc.cpp  — DIVERGENT, do NOT adopt. fireredpunc.cpp:56 uses a
//                      DIFFERENT algorithm: greedy longest-prefix WordPiece
//                      (split on whitespace, per-word ▁ prefix, abort the
//                      word on first unknown), NOT Viterbi unigram. This
//                      header does NOT serve it.
//
// Future consumers: SpeechT5, Parler.
//
// Algorithm: Viterbi best-segmentation over byte positions.
//   1. Prepend U+2581 (▁), replace spaces with ▁.
//   2. DP forward: dp[i] = best total log-prob to reach byte position i.
//      For each position, try all piece lengths up to MAX_PIECE_LEN,
//      look up each substring in token_to_id, score with scores[id].
//   3. Single-byte fallback with heavy penalty when no piece covers a byte.
//   4. Backtrack through dp[] to recover the piece sequence.
//
// The tokenizer data (vocabulary, scores) is loaded from GGUF arrays
// `tokenizer.ggml.tokens` and `tokenizer.ggml.scores` by each backend's
// init code. This header only implements the segmentation algorithm,
// not the vocabulary loading.

#pragma once

#include <algorithm>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace core_spm {

// Configuration for the Viterbi tokenizer.
struct Config {
    // Maximum piece length in bytes to consider at each position.
    // 64 covers all known SentencePiece vocabularies.
    int max_piece_len = 64;

    // Score assigned to unknown single-byte fallback pieces.
    // Should be very negative so Viterbi only picks it when nothing
    // else covers the byte.
    float unk_penalty = -100.0f;

    // Token ID to emit for unknown bytes. Typically 0, 1, or 2
    // depending on the vocabulary.
    int32_t unk_id = 0;

    // If true, skip candidates whose end position falls on a UTF-8
    // continuation byte (10xxxxxx). SP pieces are always codepoint-
    // aligned, so such substrings can't be valid vocab entries.
    // Enabled by default for correctness; can be disabled for pure
    // byte-level vocabularies.
    bool utf8_aligned = true;

    // If true, merge consecutive unknown tokens into a single <unk>.
    // Matches the standard SentencePiece behavior.
    bool merge_consecutive_unk = true;

    // Score used when a found piece's token id is out of range of the
    // scores[] array. t5_translate uses 0.0f; indextts uses -20.0f.
    float oov_score_default = 0.0f;

    // Optional SentencePiece byte_fallback table: 256 entries mapping a raw
    // byte value → the token id of its "<0xHH>" piece (-1 if absent). When
    // set, an out-of-vocab byte falls back to its byte token (one per byte,
    // scored by scores[]) instead of a single <unk> — matching HF tokenizers
    // trained with byte_fallback (e.g. llm-jp-3 for Irodori-TTS's emoji
    // control tokens). nullptr → the plain <unk> fallback below.
    const std::vector<int32_t>* byte_fallback = nullptr;
};

// Tokenize a string using unigram Viterbi best-segmentation.
//
// Parameters:
//   text          — input text (UTF-8).
//   token_to_id   — vocabulary map: piece string → token ID.
//   scores        — log-probability scores indexed by token ID.
//                   scores[id] is the unigram log-likelihood for piece id.
//   cfg           — configuration (see Config above).
//   prepend_space — if true, prepend ▁ and replace spaces with ▁.
//                   This is standard SentencePiece behavior. Set to false
//                   for vocabularies that handle spacing differently.
//
// Returns a vector of token IDs (does NOT append EOS — the caller
// handles that since EOS ID varies across vocabularies).
static inline std::vector<int32_t> tokenize(const std::string& text,
                                            const std::unordered_map<std::string, int32_t>& token_to_id,
                                            const std::vector<float>& scores, const Config& cfg = {},
                                            bool prepend_space = true) {
    // Build the SP-style input: leading ▁ + replace spaces/whitespace with ▁.
    std::string s;
    s.reserve(text.size() + 4);
    if (prepend_space) {
        s.append("\xE2\x96\x81"); // ▁ (U+2581)
    }
    for (char ch : text) {
        if (prepend_space && (ch == ' ' || ch == '\t' || ch == '\n')) {
            s.append("\xE2\x96\x81");
        } else {
            s.push_back(ch);
        }
    }

    // A ≥2 GiB input would overflow the int32 length below (n + 1 → INT_MIN,
    // OOB reads in the DP tables / piece_at). No real ASR/TTS text approaches
    // this. Reject at INT_MAX so `n + 1` (and `(size_t)n + 1`) never overflows.
    if (s.size() >= 0x7fffffffu) {
        return {};
    }
    const int n = (int)s.size();
    if (n == 0) {
        return {};
    }

    constexpr float NEG_INF = -1e30f;

    // dp[i] = best total log-prob to reach byte position i.
    // piece_at[i] = token ID of the last piece ending at position i.
    // prev_pos[i] = byte position before the last piece.
    std::vector<float> dp((size_t)n + 1, NEG_INF);
    std::vector<int32_t> piece_at((size_t)n + 1, -1);
    std::vector<int> prev_pos((size_t)n + 1, -1);
    dp[0] = 0.0f;

    for (int i = 0; i < n; i++) {
        if (dp[i] <= NEG_INF + 1.0f) {
            continue;
        }
        const int max_j = std::min(n, i + cfg.max_piece_len);
        for (int j = i + 1; j <= max_j; j++) {
            // Skip positions that fall in the middle of a UTF-8 codepoint.
            if (cfg.utf8_aligned && j < n && (((unsigned char)s[j]) & 0xC0) == 0x80) {
                continue;
            }
            std::string sub = s.substr(i, j - i);
            auto it = token_to_id.find(sub);
            if (it != token_to_id.end()) {
                int32_t tid = it->second;
                float score = (tid >= 0 && tid < (int32_t)scores.size()) ? scores[tid] : cfg.oov_score_default;
                float cand = dp[i] + score;
                if (cand > dp[j]) {
                    dp[j] = cand;
                    piece_at[j] = tid;
                    prev_pos[j] = i;
                }
            }
        }

        // Unknown fallback. Two modes:
        //
        // (a) byte_fallback (SentencePiece byte_fallback): the OOV byte s[i]
        //     becomes its "<0xHH>" token (one per byte, i → i+1, scored by
        //     scores[]). This is what HF Unigram-with-byte_fallback does, and
        //     what the Irodori-TTS model was trained on — its emoji emotion
        //     controls (👂, 😮‍💨, …) are OOV and MUST become byte tokens, not
        //     <unk> noise. Bypasses the utf8-alignment guard on purpose so a
        //     multi-byte lead can still advance one byte at a time.
        //
        // (b) plain <unk>: advance one whole codepoint as a single <unk>
        //     (heavy penalty). Guarantees the DP stays connected across an OOV
        //     multi-byte codepoint — without it, the single-byte edge (i→i+1)
        //     is illegal at a multi-byte lead (i+1 is a continuation byte the
        //     alignment guard skips), no edge leaves i, and the whole string
        //     collapses to just the BOS. In byte mode (utf8_aligned=false)
        //     cp_len is 1, reproducing the original single-byte unk fallback.
        if (cfg.byte_fallback && !cfg.byte_fallback->empty()) {
            unsigned char b = (unsigned char)s[i];
            int32_t bt = (b < cfg.byte_fallback->size()) ? (*cfg.byte_fallback)[b] : -1;
            const int fj = i + 1;
            float score = (bt >= 0 && bt < (int32_t)scores.size()) ? scores[bt] : cfg.unk_penalty;
            float cand = dp[i] + score;
            if (cand > dp[fj]) {
                dp[fj] = cand;
                piece_at[fj] = (bt >= 0) ? bt : cfg.unk_id;
                prev_pos[fj] = i;
            }
        } else {
            int cp_len = 1;
            if (cfg.utf8_aligned) {
                unsigned char lead = (unsigned char)s[i];
                if ((lead & 0xE0) == 0xC0)
                    cp_len = 2;
                else if ((lead & 0xF0) == 0xE0)
                    cp_len = 3;
                else if ((lead & 0xF8) == 0xF0)
                    cp_len = 4;
                cp_len = std::min(cp_len, n - i);
            }
            const int fj = i + cp_len;
            const float unk_cand = dp[i] + cfg.unk_penalty;
            if (unk_cand > dp[fj]) {
                dp[fj] = unk_cand;
                piece_at[fj] = cfg.unk_id;
                prev_pos[fj] = i;
            }
        }
    }

    // Backtrack from position n to 0.
    std::vector<int32_t> ids;
    int pos = n;
    while (pos > 0) {
        if (piece_at[pos] < 0) {
            // Should be unreachable — the single-byte fallback is always
            // available. If it happens, bail out gracefully.
            break;
        }
        ids.push_back(piece_at[pos]);
        pos = prev_pos[pos];
    }
    std::reverse(ids.begin(), ids.end());

    // Optionally merge consecutive unknowns.
    if (cfg.merge_consecutive_unk && cfg.unk_id >= 0) {
        std::vector<int32_t> merged;
        merged.reserve(ids.size());
        bool prev_unk = false;
        for (int32_t id : ids) {
            if (id == cfg.unk_id) {
                if (!prev_unk) {
                    merged.push_back(id);
                }
                prev_unk = true;
            } else {
                merged.push_back(id);
                prev_unk = false;
            }
        }
        return merged;
    }

    return ids;
}

// Detokenize: convert token IDs back to text, replacing ▁ with spaces.
//
// Parameters:
//   ids           — token IDs to decode.
//   id_to_token   — reverse vocabulary map: token ID → piece string.
//   skip_special  — set of token IDs to skip (e.g., <pad>, </s>, <unk>).
//
// Returns the decoded text with leading space stripped.
static inline std::string detokenize(const std::vector<int32_t>& ids, const std::vector<std::string>& id_to_token,
                                     const std::vector<int32_t>& skip_ids = {}) {
    std::string result;
    for (int32_t id : ids) {
        if (id < 0 || id >= (int32_t)id_to_token.size()) {
            continue;
        }
        // Check skip list.
        bool skip = false;
        for (int32_t sid : skip_ids) {
            if (id == sid) {
                skip = true;
                break;
            }
        }
        if (skip) {
            continue;
        }

        std::string decoded = id_to_token[id];
        // Replace ▁ (U+2581, 3-byte sequence) with space.
        size_t pos = 0;
        while ((pos = decoded.find("\xE2\x96\x81", pos)) != std::string::npos) {
            decoded.replace(pos, 3, " ");
            pos += 1;
        }
        result += decoded;
    }

    // Strip leading space (artifact of the prepended ▁).
    if (!result.empty() && result[0] == ' ') {
        result = result.substr(1);
    }
    return result;
}

// ── SentencePiece BPE tokenizer ────────────────────────────────────
static inline std::vector<int32_t> tokenize_bpe(const std::string& text,
                                                const std::unordered_map<std::string, int32_t>& token_to_id,
                                                const std::vector<float>& scores, const Config& cfg = {},
                                                bool prepend_space = true) {
    // A >2 GiB input would overflow the int32 arithmetic below (max_iter =
    // (int)symbols.size() * 2). No real ASR/TTS text approaches this.
    if (text.size() > 0x7fffffffu) {
        return {};
    }
    std::string s;
    s.reserve(text.size() + 4);
    if (prepend_space) {
        s.append("\xE2\x96\x81");
    }
    for (char ch : text) {
        if (prepend_space && (ch == ' ' || ch == '\t' || ch == '\n')) {
            s.append("\xE2\x96\x81");
        } else {
            s.push_back(ch);
        }
    }
    if (s.empty())
        return {};
    std::vector<std::string> symbols;
    {
        size_t i = 0;
        while (i < s.size()) {
            unsigned char c = (unsigned char)s[i];
            size_t len;
            if (c < 0x80)
                len = 1;
            else if ((c & 0xE0) == 0xC0)
                len = 2;
            else if ((c & 0xF0) == 0xE0)
                len = 3;
            else if ((c & 0xF8) == 0xF0)
                len = 4;
            else
                len = 1;
            if (i + len > s.size())
                len = 1;
            symbols.emplace_back(s, i, len);
            i += len;
        }
    }
    if (symbols.empty())
        return {};
    constexpr float NEG_INF = -1e30f;
    const int max_iter = (int)symbols.size() * 2;
    for (int iter = 0; iter < max_iter && symbols.size() >= 2; iter++) {
        int best_i = -1;
        float best_score = NEG_INF;
        for (size_t k = 0; k + 1 < symbols.size(); k++) {
            std::string merged = symbols[k] + symbols[k + 1];
            auto it = token_to_id.find(merged);
            if (it != token_to_id.end()) {
                int32_t tid = it->second;
                float sc = (tid >= 0 && tid < (int32_t)scores.size()) ? scores[tid] : NEG_INF;
                if (sc > best_score) {
                    best_score = sc;
                    best_i = (int)k;
                }
            }
        }
        if (best_i < 0)
            break;
        symbols[best_i] += symbols[best_i + 1];
        symbols.erase(symbols.begin() + best_i + 1);
    }
    std::vector<int32_t> ids;
    ids.reserve(symbols.size());
    for (const auto& sym : symbols) {
        auto it = token_to_id.find(sym);
        if (it != token_to_id.end()) {
            ids.push_back(it->second);
        } else {
            ids.push_back(cfg.unk_id);
        }
    }
    return ids;
}

} // namespace core_spm
