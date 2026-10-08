#include "mca/mnn/mnn_clip_tokenizer.hpp"

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <fstream>
#include <limits>
#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#endif
#include <sstream>

using json = nlohmann::ordered_json;

namespace mca {
namespace mnn {

void append_utf8_codepoint(std::string& out, int codepoint) {
    if (codepoint <= 0x7f) {
        out.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | ((codepoint >> 6) & 0x1f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | ((codepoint >> 12) & 0x0f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | ((codepoint >> 18) & 0x07)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

std::vector<std::string> build_clip_byte_encoder() {
    std::vector<int> bytes;
    for (int i = 33; i <= 126; ++i) bytes.push_back(i);
    for (int i = 0xa1; i <= 0xac; ++i) bytes.push_back(i);
    for (int i = 0xae; i <= 0xff; ++i) bytes.push_back(i);
    std::vector<bool> present(256, false);
    for (int b : bytes) present[static_cast<size_t>(b)] = true;
    std::vector<int> codepoints = bytes;
    int n = 0;
    for (int b = 0; b < 256; ++b) {
        if (!present[static_cast<size_t>(b)]) {
            bytes.push_back(b);
            codepoints.push_back(256 + n);
            ++n;
        }
    }
    std::vector<std::string> out(256);
    for (size_t i = 0; i < bytes.size(); ++i) {
        append_utf8_codepoint(out[static_cast<size_t>(bytes[i])], codepoints[i]);
    }
    return out;
}

std::vector<std::string> clip_pretokenize(std::string text) {
    std::vector<std::string> tokens;
    std::string cleaned;
    bool last_space = true;
    for (unsigned char c : text) {
        if (std::isspace(c)) {
            if (!last_space) {
                cleaned.push_back(' ');
                last_space = true;
            }
        } else {
            cleaned.push_back(static_cast<char>(std::tolower(c)));
            last_space = false;
        }
    }
    auto flush = [&](std::string& current) {
        if (!current.empty()) {
            tokens.push_back(current);
            current.clear();
        }
    };
    std::string current;
    enum class Kind { None, Letter, Number, Other, Utf8 };
    Kind kind = Kind::None;
    for (size_t i = 0; i < cleaned.size();) {
        const unsigned char c = static_cast<unsigned char>(cleaned[i]);
        if (std::isspace(c)) {
            flush(current);
            kind = Kind::None;
            ++i;
            continue;
        }
        Kind next = Kind::Other;
        size_t count = 1;
        if (c < 0x80) {
            if (std::isalpha(c)) {
                next = Kind::Letter;
            } else if (std::isdigit(c)) {
                next = Kind::Number;
            } else {
                next = Kind::Other;
            }
        } else {
            next = Kind::Utf8;
            if ((c & 0xe0u) == 0xc0u) count = 2;
            else if ((c & 0xf0u) == 0xe0u) count = 3;
            else if ((c & 0xf8u) == 0xf0u) count = 4;
            count = std::min(count, cleaned.size() - i);
        }
        if (kind != Kind::None && next != kind) {
            flush(current);
        }
        kind = next;
        current.append(cleaned, i, count);
        i += count;
        if (next == Kind::Number) {
            flush(current);
            kind = Kind::None;
        }
    }
    flush(current);
    return tokens;
}

std::string ClipBpeTokenizer::pair_key(const std::string& a, const std::string& b) {
    return a + "\x1f" + b;
}

bool ClipBpeTokenizer::load(const std::string& path, std::string& error) {
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        error = "Failed to open tokenizer.json.";
        return false;
    }
    json parsed = json::parse(input, nullptr, false);
    if (!parsed.is_object() || !parsed.contains("model") || !parsed["model"].is_object()) {
        error = "tokenizer.json is not a Hugging Face BPE tokenizer.";
        return false;
    }
    const auto& model = parsed["model"];
    if (model.value("type", "") != "BPE" || !model.contains("vocab") || !model["vocab"].is_object()) {
        error = "tokenizer.json model is not BPE.";
        return false;
    }
    end_suffix = model.value("end_of_word_suffix", "</w>");
    for (auto it = model["vocab"].begin(); it != model["vocab"].end(); ++it) {
        if (it.value().is_number_integer()) {
            vocab[it.key()] = it.value().get<int>();
        }
    }
    if (model.contains("merges") && model["merges"].is_array()) {
        int rank = 0;
        for (const auto& merge : model["merges"]) {
            std::string first;
            std::string second;
            if (merge.is_array() && merge.size() >= 2) {
                first = merge[0].get<std::string>();
                second = merge[1].get<std::string>();
            } else if (merge.is_string()) {
                std::istringstream parts(merge.get<std::string>());
                parts >> first >> second;
            }
            if (!first.empty() && !second.empty()) {
                ranks[pair_key(first, second)] = rank++;
            }
        }
    }
    auto bos = vocab.find("<|startoftext|>");
    auto eos = vocab.find("<|endoftext|>");
    if (bos != vocab.end()) bos_id = bos->second;
    if (eos != vocab.end()) eos_id = eos->second;
    byte_encoder = build_clip_byte_encoder();
    if (vocab.empty() || ranks.empty() || byte_encoder.size() != 256) {
        error = "tokenizer.json BPE vocab or merges are empty.";
        return false;
    }
    return true;
}

std::vector<int> ClipBpeTokenizer::encode_single(const std::string& text, int max_len) const {
    std::vector<int> ids;
    ids.push_back(bos_id);
    for (const auto& token : clip_pretokenize(text)) {
        std::vector<std::string> word;
        for (unsigned char c : token) {
            word.push_back(byte_encoder[static_cast<size_t>(c)]);
        }
        if (word.empty()) continue;
        word.back() += end_suffix;
        while (word.size() > 1) {
            int best_rank = std::numeric_limits<int>::max();
            size_t best_index = 0;
            for (size_t i = 0; i + 1 < word.size(); ++i) {
                auto it = ranks.find(pair_key(word[i], word[i + 1]));
                if (it != ranks.end() && it->second < best_rank) {
                    best_rank = it->second;
                    best_index = i;
                }
            }
            if (best_rank == std::numeric_limits<int>::max()) break;
            word[best_index] += word[best_index + 1];
            word.erase(word.begin() + static_cast<std::ptrdiff_t>(best_index + 1));
        }
        for (const auto& piece : word) {
            auto it = vocab.find(piece);
            ids.push_back(it == vocab.end() ? eos_id : it->second);
        }
    }
    ids.push_back(eos_id);
    if (max_len > 0) {
        if (static_cast<int>(ids.size()) > max_len) {
            ids.resize(static_cast<size_t>(max_len));
            ids[static_cast<size_t>(max_len - 1)] = eos_id;
        } else {
            while (static_cast<int>(ids.size()) < max_len) {
                ids.push_back(eos_id);
            }
        }
    }
    return ids;
}

std::vector<int> ClipBpeTokenizer::encode_pair(const std::string& text, int max_len) const {
    std::vector<int> out(static_cast<size_t>(max_len * 2), eos_id);
    auto neg = encode_single("", max_len);
    auto pos = encode_single(text, max_len);
    std::copy(neg.begin(), neg.end(), out.begin());
    std::copy(pos.begin(), pos.end(), out.begin() + max_len);
    return out;
}

} // namespace mnn
} // namespace mca
