// T2B19 / R7-C8 (2026-10-14): m2m100.cpp split - tokenizer TU (L388-492 moved verbatim).
// tokenize/detokenize drop `static` and join namespace m2m100_detail; both are declared
// hidden in m2m100_internal.hpp.

#include "m2m100_internal.hpp"

#include <string>
#include <vector>

#include "core/sentencepiece.h"

namespace m2m100_detail {

// ── Tokenizer encode ─────────────────────────────────────────────
// M2M-100 uses a SentencePiece BPE model: tokenization follows merge order
// (highest score = lowest rank, merged first), via core_spm::tokenize_bpe over the
// GGUF's tokenizer.ggml.scores. Falls back to greedy longest-match if scores absent.

std::vector<int> tokenize(const m2m100_tokenizer& tok, const std::string& text, const std::string& src_lang) {
    std::vector<int> ids;

    // Prepend source language token
    auto it = tok.lang_to_token_id.find(src_lang);
    if (it != tok.lang_to_token_id.end()) {
        ids.push_back(it->second);
    }

    if (!tok.scores.empty() && !tok.token_to_id_u.empty()) {
        core_spm::Config cfg;
        cfg.unk_id = 3; // <unk>
        auto bpe = core_spm::tokenize_bpe(text, tok.token_to_id_u, tok.scores, cfg, /*prepend_space=*/true);
        for (int id : bpe) {
            // BPE-intermediate pieces (id >= embed_vocab_size) have no embedding
            // row and should never be final — if one leaks out, map it to <unk>
            // (matches HF M2M100Tokenizer.convert_token_to_id).
            ids.push_back((tok.embed_vocab_size > 0 && id >= tok.embed_vocab_size) ? 3 : id);
        }
        ids.push_back(2); // </s>
        return ids;
    }

    // Fallback: SentencePiece-style greedy longest-match tokenization with ▁ prefix
    // Split on whitespace, prefix each word with ▁, greedy longest match
    std::vector<std::string> words;
    std::string cur;
    for (char ch : text) {
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
            if (!cur.empty()) {
                words.push_back(cur);
                cur.clear();
            }
        } else {
            cur += ch;
        }
    }
    if (!cur.empty())
        words.push_back(cur);

    for (size_t wi = 0; wi < words.size(); wi++) {
        // Prefix with ▁ (U+2581, 0xE2 0x96 0x81)
        std::string word = "\xE2\x96\x81" + words[wi];
        size_t start = 0;
        while (start < word.size()) {
            size_t end = word.size();
            int best_id = -1;
            while (end > start) {
                std::string sub = word.substr(start, end - start);
                auto it2 = tok.token_to_id.find(sub);
                if (it2 != tok.token_to_id.end()) {
                    best_id = it2->second;
                    break;
                }
                end--;
                // Don't break mid-UTF8
                while (end > start && (word[end] & 0xC0) == 0x80)
                    end--;
            }
            if (best_id < 0) {
                ids.push_back(3); // <unk>
                break;
            }
            ids.push_back(best_id);
            start = end;
        }
    }

    // Append EOS
    ids.push_back(2); // </s>

    return ids;
}

std::string detokenize(const m2m100_tokenizer& tok, const std::vector<int>& ids) {
    std::string result;
    for (int id : ids) {
        if (id < 0 || id >= (int)tok.id_to_token.size())
            continue;
        const std::string& t = tok.id_to_token[id];
        if (t == "<s>" || t == "</s>" || t == "<pad>" || t == "<unk>")
            continue;
        // Skip language tokens (__XX__)
        if (t.size() >= 5 && t[0] == '_' && t[1] == '_' && t[t.size() - 1] == '_' && t[t.size() - 2] == '_')
            continue;
        // SentencePiece: '▁' → space
        std::string decoded = t;
        size_t pos = 0;
        while ((pos = decoded.find("\xe2\x96\x81", pos)) != std::string::npos) {
            decoded.replace(pos, 3, " ");
            pos += 1;
        }
        result += decoded;
    }
    // Trim leading space
    if (!result.empty() && result[0] == ' ')
        result = result.substr(1);
    return result;
}

}  // namespace m2m100_detail
