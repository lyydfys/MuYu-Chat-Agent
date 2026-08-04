#include "image_conditioning.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <sstream>
#include <utility>

#if MCA_WITH_TOKENIZERS_CPP
#include <tokenizers_cpp.h>
#endif

namespace mca::image {
namespace {

#if MCA_WITH_TOKENIZERS_CPP
constexpr std::streamoff kMaxTokenizerJsonBytes = 64LL * 1024LL * 1024LL;
#endif
constexpr float kRoundBracketMultiplier = 1.1f;
constexpr float kSquareBracketMultiplier = 0.9f;
constexpr float kWeightEqualityTolerance = 1.0e-6f;
constexpr size_t kMaxPromptNesting = 128;
constexpr float kMaxAbsoluteExplicitWeight = 1000.0f;

char matching_close(char open) { return open == '(' ? ')' : ']'; }

bool is_escapable_prompt_character(char value) {
    return value == '\\' || value == '(' || value == ')' || value == '[' || value == ']' ||
           value == ':';
}

bool find_matching_close(const std::string& text, size_t open_index, size_t* close_index) {
    if (close_index == nullptr || open_index >= text.size()) return false;
    const char open = text[open_index];
    if (open != '(' && open != '[') return false;
    std::vector<char> closes;
    closes.push_back(matching_close(open));
    for (size_t index = open_index + 1; index < text.size(); ++index) {
        const char value = text[index];
        if (value == '\\' && index + 1 < text.size()) {
            ++index;
            continue;
        }
        if (value == '(' || value == '[') {
            closes.push_back(matching_close(value));
            continue;
        }
        if (!closes.empty() && value == closes.back()) {
            closes.pop_back();
            if (closes.empty()) {
                *close_index = index;
                return true;
            }
        }
    }
    return false;
}

bool parse_finite_explicit_weight(const std::string& text, float* weight) {
    if (weight == nullptr) return false;
    size_t begin = 0;
    while (begin < text.size() && std::isspace(static_cast<unsigned char>(text[begin])) != 0) {
        ++begin;
    }
    size_t end = text.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(text[end - 1])) != 0) {
        --end;
    }
    if (begin == end) return false;
    const std::string number = text.substr(begin, end - begin);
    char* parsed_end = nullptr;
    errno = 0;
    const float parsed = std::strtof(number.c_str(), &parsed_end);
    if (errno == ERANGE || parsed_end == number.c_str() ||
        parsed_end != number.c_str() + number.size() || !std::isfinite(parsed) ||
        std::abs(parsed) > kMaxAbsoluteExplicitWeight) {
        return false;
    }
    *weight = parsed;
    return true;
}

bool split_explicit_round_weight(const std::string& inner, std::string* body, float* multiplier) {
    if (body == nullptr || multiplier == nullptr) return false;
    std::vector<char> closes;
    size_t candidate_colon = std::string::npos;
    for (size_t index = 0; index < inner.size(); ++index) {
        const char value = inner[index];
        if (value == '\\' && index + 1 < inner.size()) {
            ++index;
            continue;
        }
        if (value == '(' || value == '[') {
            closes.push_back(matching_close(value));
            continue;
        }
        if (!closes.empty() && value == closes.back()) {
            closes.pop_back();
            continue;
        }
        if (value == ':' && closes.empty()) candidate_colon = index;
    }
    if (candidate_colon == std::string::npos) return false;
    float parsed = 1.0f;
    if (!parse_finite_explicit_weight(inner.substr(candidate_colon + 1), &parsed)) {
        return false;
    }
    *body = inner.substr(0, candidate_colon);
    *multiplier = parsed;
    return true;
}

bool append_weighted_fragment(std::string text, float weight,
                              std::vector<WeightedPromptFragment>* fragments, std::string* error) {
    if (fragments == nullptr || error == nullptr) return false;
    if (text.empty()) return true;
    if (!std::isfinite(weight)) {
        *error = "Prompt weighting produced a non-finite effective weight.";
        return false;
    }
    if (!fragments->empty() &&
        std::abs(fragments->back().weight - weight) <= kWeightEqualityTolerance) {
        fragments->back().text += text;
    } else {
        fragments->push_back(WeightedPromptFragment{std::move(text), weight});
    }
    return true;
}

bool parse_weighted_range(const std::string& text, float inherited_weight, size_t depth,
                          std::vector<WeightedPromptFragment>* fragments, std::string* error) {
    if (fragments == nullptr || error == nullptr) return false;
    if (depth > kMaxPromptNesting) {
        *error = "Prompt weighting nesting exceeds 128 levels.";
        return false;
    }
    std::string literal;
    const auto flush_literal = [&]() -> bool {
        if (literal.empty()) return true;
        std::string pending;
        pending.swap(literal);
        return append_weighted_fragment(std::move(pending), inherited_weight, fragments, error);
    };

    for (size_t index = 0; index < text.size();) {
        const char value = text[index];
        if (value == '\\' && index + 1 < text.size() &&
            is_escapable_prompt_character(text[index + 1])) {
            literal.push_back(text[index + 1]);
            index += 2;
            continue;
        }
        if (value == '(' || value == '[') {
            size_t close_index = 0;
            if (!find_matching_close(text, index, &close_index)) {
                literal.push_back(value);
                ++index;
                continue;
            }
            if (!flush_literal()) return false;
            std::string inner = text.substr(index + 1, close_index - index - 1);
            float multiplier = value == '(' ? kRoundBracketMultiplier : kSquareBracketMultiplier;
            if (value == '(') {
                std::string explicit_body;
                float explicit_multiplier = multiplier;
                if (split_explicit_round_weight(inner, &explicit_body, &explicit_multiplier)) {
                    inner = std::move(explicit_body);
                    multiplier = explicit_multiplier;
                }
            }
            const float effective_weight = inherited_weight * multiplier;
            if (!std::isfinite(effective_weight)) {
                *error = "Prompt weighting produced a non-finite nested weight.";
                return false;
            }
            if (!parse_weighted_range(inner, effective_weight, depth + 1, fragments, error)) {
                return false;
            }
            index = close_index + 1;
            continue;
        }
        literal.push_back(value);
        ++index;
    }
    return flush_literal();
}

constexpr std::array<uint32_t, 64> kSha256RoundConstants = {
        UINT32_C(0x428a2f98), UINT32_C(0x71374491), UINT32_C(0xb5c0fbcf), UINT32_C(0xe9b5dba5),
        UINT32_C(0x3956c25b), UINT32_C(0x59f111f1), UINT32_C(0x923f82a4), UINT32_C(0xab1c5ed5),
        UINT32_C(0xd807aa98), UINT32_C(0x12835b01), UINT32_C(0x243185be), UINT32_C(0x550c7dc3),
        UINT32_C(0x72be5d74), UINT32_C(0x80deb1fe), UINT32_C(0x9bdc06a7), UINT32_C(0xc19bf174),
        UINT32_C(0xe49b69c1), UINT32_C(0xefbe4786), UINT32_C(0x0fc19dc6), UINT32_C(0x240ca1cc),
        UINT32_C(0x2de92c6f), UINT32_C(0x4a7484aa), UINT32_C(0x5cb0a9dc), UINT32_C(0x76f988da),
        UINT32_C(0x983e5152), UINT32_C(0xa831c66d), UINT32_C(0xb00327c8), UINT32_C(0xbf597fc7),
        UINT32_C(0xc6e00bf3), UINT32_C(0xd5a79147), UINT32_C(0x06ca6351), UINT32_C(0x14292967),
        UINT32_C(0x27b70a85), UINT32_C(0x2e1b2138), UINT32_C(0x4d2c6dfc), UINT32_C(0x53380d13),
        UINT32_C(0x650a7354), UINT32_C(0x766a0abb), UINT32_C(0x81c2c92e), UINT32_C(0x92722c85),
        UINT32_C(0xa2bfe8a1), UINT32_C(0xa81a664b), UINT32_C(0xc24b8b70), UINT32_C(0xc76c51a3),
        UINT32_C(0xd192e819), UINT32_C(0xd6990624), UINT32_C(0xf40e3585), UINT32_C(0x106aa070),
        UINT32_C(0x19a4c116), UINT32_C(0x1e376c08), UINT32_C(0x2748774c), UINT32_C(0x34b0bcb5),
        UINT32_C(0x391c0cb3), UINT32_C(0x4ed8aa4a), UINT32_C(0x5b9cca4f), UINT32_C(0x682e6ff3),
        UINT32_C(0x748f82ee), UINT32_C(0x78a5636f), UINT32_C(0x84c87814), UINT32_C(0x8cc70208),
        UINT32_C(0x90befffa), UINT32_C(0xa4506ceb), UINT32_C(0xbef9a3f7), UINT32_C(0xc67178f2),
};

uint32_t rotate_right(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

std::array<uint8_t, 32> sha256(const std::vector<uint8_t>& input) {
    std::vector<uint8_t> padded = input;
    const uint64_t bit_length = static_cast<uint64_t>(padded.size()) * UINT64_C(8);
    padded.push_back(UINT8_C(0x80));
    while (padded.size() % 64U != 56U) padded.push_back(0);
    for (int shift = 56; shift >= 0; shift -= 8) {
        padded.push_back(static_cast<uint8_t>(bit_length >> shift));
    }

    std::array<uint32_t, 8> state = {
            UINT32_C(0x6a09e667), UINT32_C(0xbb67ae85), UINT32_C(0x3c6ef372), UINT32_C(0xa54ff53a),
            UINT32_C(0x510e527f), UINT32_C(0x9b05688c), UINT32_C(0x1f83d9ab), UINT32_C(0x5be0cd19),
    };
    for (size_t block = 0; block < padded.size(); block += 64) {
        std::array<uint32_t, 64> words{};
        for (size_t index = 0; index < 16; ++index) {
            const size_t offset = block + index * 4;
            words[index] = (static_cast<uint32_t>(padded[offset]) << 24U) |
                           (static_cast<uint32_t>(padded[offset + 1]) << 16U) |
                           (static_cast<uint32_t>(padded[offset + 2]) << 8U) |
                           static_cast<uint32_t>(padded[offset + 3]);
        }
        for (size_t index = 16; index < words.size(); ++index) {
            const uint32_t s0 = rotate_right(words[index - 15], 7) ^
                                rotate_right(words[index - 15], 18) ^ (words[index - 15] >> 3U);
            const uint32_t s1 = rotate_right(words[index - 2], 17) ^
                                rotate_right(words[index - 2], 19) ^ (words[index - 2] >> 10U);
            words[index] = words[index - 16] + s0 + words[index - 7] + s1;
        }

        uint32_t a = state[0];
        uint32_t b = state[1];
        uint32_t c = state[2];
        uint32_t d = state[3];
        uint32_t e = state[4];
        uint32_t f = state[5];
        uint32_t g = state[6];
        uint32_t h = state[7];
        for (size_t index = 0; index < words.size(); ++index) {
            const uint32_t sum1 = rotate_right(e, 6) ^ rotate_right(e, 11) ^ rotate_right(e, 25);
            const uint32_t choose = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + sum1 + choose + kSha256RoundConstants[index] + words[index];
            const uint32_t sum0 = rotate_right(a, 2) ^ rotate_right(a, 13) ^ rotate_right(a, 22);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
        state[5] += f;
        state[6] += g;
        state[7] += h;
    }

    std::array<uint8_t, 32> digest{};
    for (size_t index = 0; index < state.size(); ++index) {
        digest[index * 4] = static_cast<uint8_t>(state[index] >> 24U);
        digest[index * 4 + 1] = static_cast<uint8_t>(state[index] >> 16U);
        digest[index * 4 + 2] = static_cast<uint8_t>(state[index] >> 8U);
        digest[index * 4 + 3] = static_cast<uint8_t>(state[index]);
    }
    return digest;
}

void append_u32_little_endian(uint32_t value, std::vector<uint8_t>* payload) {
    for (unsigned shift = 0; shift < 32; shift += 8) {
        payload->push_back(static_cast<uint8_t>((value >> shift) & UINT32_C(0xff)));
    }
}

void append_u64_little_endian(uint64_t value, std::vector<uint8_t>* payload) {
    for (unsigned shift = 0; shift < 64; shift += 8) {
        payload->push_back(static_cast<uint8_t>((value >> shift) & UINT64_C(0xff)));
    }
}

bool append_sequence_fingerprint_payload(const std::vector<int32_t>& ids,
                                         const std::vector<float>& weights,
                                         std::vector<uint8_t>* payload) {
    if (payload == nullptr || ids.size() != weights.size()) return false;
    append_u64_little_endian(static_cast<uint64_t>(ids.size()), payload);
    for (size_t index = 0; index < ids.size(); ++index) {
        if (ids[index] < 0 || !std::isfinite(weights[index])) return false;
        const double scaled = static_cast<double>(weights[index]) * 1000000.0;
        if (scaled < static_cast<double>(std::numeric_limits<int64_t>::min()) ||
            scaled > static_cast<double>(std::numeric_limits<int64_t>::max())) {
            return false;
        }
        append_u32_little_endian(static_cast<uint32_t>(ids[index]), payload);
        const int64_t quantized = static_cast<int64_t>(std::llround(scaled));
        append_u64_little_endian(static_cast<uint64_t>(quantized), payload);
    }
    return true;
}

std::string sha256_hex(const std::vector<uint8_t>& payload) {
    const auto digest = sha256(payload);
    std::ostringstream result;
    result << std::hex << std::setfill('0');
    for (const uint8_t value : digest) result << std::setw(2) << static_cast<unsigned>(value);
    return result.str();
}

std::vector<uint8_t> fingerprint_payload_with_domain(const char* domain) {
    std::vector<uint8_t> payload;
    if (domain == nullptr) return payload;
    const size_t length = std::strlen(domain);
    payload.insert(payload.end(), domain, domain + length);
    return payload;
}

std::string clip_weighting_fingerprint(const std::vector<int32_t>& ids,
                                       const std::vector<float>& weights) {
    auto payload = fingerprint_payload_with_domain("mca.clip.weight.sequence.v1");
    if (!append_sequence_fingerprint_payload(ids, weights, &payload)) return {};
    return sha256_hex(payload);
}

bool finalize_clip_sequence(std::vector<int32_t> content_ids, std::vector<float> content_weights,
                            const ClipTokenizerConfig& config, ClipTokenSequence* output,
                            std::string* error) {
    if (output == nullptr || error == nullptr) return false;
    if (content_ids.size() != content_weights.size()) {
        *error = "CLIP token ids and weights have different lengths.";
        return false;
    }
    const size_t special_count = (config.add_bos ? 1U : 0U) + (config.add_eos ? 1U : 0U);
    output->untruncated_token_count = content_ids.size() + special_count;
    output->truncated = output->untruncated_token_count > static_cast<size_t>(config.max_length);
    const size_t content_capacity = static_cast<size_t>(config.max_length) - special_count;
    const size_t retained_content_count = std::min(content_ids.size(), content_capacity);

    output->ids.clear();
    output->weights.clear();
    output->ids.reserve(static_cast<size_t>(config.max_length));
    output->weights.reserve(static_cast<size_t>(config.max_length));
    if (config.add_bos) {
        output->ids.push_back(config.bos_id);
        output->weights.push_back(1.0f);
    }
    output->ids.insert(output->ids.end(), content_ids.begin(),
                       content_ids.begin() + retained_content_count);
    output->weights.insert(output->weights.end(), content_weights.begin(),
                           content_weights.begin() + retained_content_count);
    if (config.add_eos) {
        output->ids.push_back(config.eos_id);
        output->weights.push_back(1.0f);
    }
    output->ids.resize(static_cast<size_t>(config.max_length), config.pad_id);
    output->weights.resize(static_cast<size_t>(config.max_length), 1.0f);

    output->weighting_applied = false;
    output->weighted_token_count = 0;
    output->min_weight = 1.0f;
    output->max_weight = 1.0f;
    output->mean_weight = 1.0f;
    if (retained_content_count > 0) {
        double sum = 0.0;
        output->min_weight = std::numeric_limits<float>::infinity();
        output->max_weight = -std::numeric_limits<float>::infinity();
        for (size_t index = 0; index < retained_content_count; ++index) {
            const float weight = content_weights[index];
            if (!std::isfinite(weight)) {
                *error = "CLIP tokenizer produced a non-finite token weight.";
                return false;
            }
            if (std::abs(weight - 1.0f) > kWeightEqualityTolerance) {
                ++output->weighted_token_count;
            }
            output->min_weight = std::min(output->min_weight, weight);
            output->max_weight = std::max(output->max_weight, weight);
            sum += static_cast<double>(weight);
        }
        output->weighting_applied = output->weighted_token_count > 0;
        output->mean_weight = static_cast<float>(sum / static_cast<double>(retained_content_count));
    }
    output->weighting_fingerprint = clip_weighting_fingerprint(output->ids, output->weights);
    return true;
}

#if MCA_WITH_TOKENIZERS_CPP
bool read_bounded_file(const std::string& path, std::string* contents, std::string* error) {
    if (contents == nullptr || error == nullptr) return false;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input.good()) {
        *error = "Tokenizer JSON is missing or unreadable: " + path;
        return false;
    }
    const std::streamoff size = input.tellg();
    if (size <= 0 || size > kMaxTokenizerJsonBytes) {
        std::ostringstream message;
        message << "Tokenizer JSON size is invalid: " << size << " bytes.";
        *error = message.str();
        return false;
    }
    input.seekg(0, std::ios::beg);
    contents->assign(static_cast<size_t>(size), '\0');
    input.read(contents->data(), size);
    if (!input.good()) {
        contents->clear();
        *error = "Tokenizer JSON could not be read completely: " + path;
        return false;
    }
    return true;
}
#endif

bool validate_config(const ClipTokenizerConfig& config, std::string* error) {
    if (error == nullptr) return false;
    if (config.max_length < 2 || config.max_length > 4096) {
        *error = "CLIP tokenizer max_length must be between 2 and 4096.";
        return false;
    }
    if (config.bos_id < 0 || config.eos_id < 0 || config.pad_id < 0) {
        *error = "CLIP tokenizer BOS, EOS and PAD ids must be non-negative.";
        return false;
    }
    return true;
}

#if MCA_WITH_TOKENIZERS_CPP
bool encode_clip_sequence(tokenizers::Tokenizer* tokenizer, const std::string& text,
                          const ClipTokenizerConfig& config, ClipTokenSequence* output,
                          std::string* error) {
    if (tokenizer == nullptr || output == nullptr || error == nullptr) return false;
    const ClipFragmentEncoder encoder = [tokenizer](const std::string& fragment,
                                                    std::vector<int32_t>* token_ids,
                                                    std::string* encode_error) {
        if (token_ids == nullptr || encode_error == nullptr) return false;
        try {
            // Encode() deliberately disables tokenizer post-processor special
            // tokens. The profile contract owns BOS/EOS/PAD explicitly.
            *token_ids = tokenizer->Encode(fragment);
            return true;
        } catch (const std::exception& exception) {
            *encode_error = std::string("Tokenizer encode failed: ") + exception.what();
            return false;
        } catch (...) {
            *encode_error = "Tokenizer encode failed with an unknown native exception.";
            return false;
        }
    };
    return tokenize_weighted_clip_sequence_with_encoder(text, config, encoder, output, error);
}

bool ascii_trigger_word_character(unsigned char value) {
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
           (value >= '0' && value <= '9') || value == '_';
}

bool ascii_equal_ignore_case(unsigned char left, unsigned char right) {
    const auto lower = [](unsigned char value) {
        return value >= 'A' && value <= 'Z'
                ? static_cast<unsigned char>(value - 'A' + 'a')
                : value;
    };
    return lower(left) == lower(right);
}

bool trigger_matches_at(const std::string& text, size_t offset, const std::string& trigger) {
    if (trigger.empty() || offset > text.size() || trigger.size() > text.size() - offset) {
        return false;
    }
    for (size_t index = 0; index < trigger.size(); ++index) {
        if (!ascii_equal_ignore_case(
                static_cast<unsigned char>(text[offset + index]),
                static_cast<unsigned char>(trigger[index]))) {
            return false;
        }
    }
    if (ascii_trigger_word_character(static_cast<unsigned char>(trigger.front())) &&
        offset > 0U &&
        ascii_trigger_word_character(static_cast<unsigned char>(text[offset - 1U]))) {
        return false;
    }
    const size_t end = offset + trigger.size();
    return !ascii_trigger_word_character(static_cast<unsigned char>(trigger.back())) ||
           end == text.size() ||
           !ascii_trigger_word_character(static_cast<unsigned char>(text[end]));
}

bool validate_textual_inversion_embeddings(
        const std::vector<ClipTextualInversionEmbedding>& embeddings,
        size_t* embedding_width,
        std::string* error) {
    if (embedding_width == nullptr || error == nullptr) return false;
    *embedding_width = 0U;
    if (embeddings.empty() || embeddings.size() > 8U) {
        *error = "Textual inversion selection must contain between one and eight embeddings.";
        return false;
    }
    std::vector<std::string> normalized_triggers;
    std::vector<size_t> artifact_indices;
    for (size_t index = 0; index < embeddings.size(); ++index) {
        const auto& embedding = embeddings[index];
        if (embedding.artifact_index >= 63U || embedding.trigger.empty() ||
            embedding.embedding_width == 0U || embedding.values.empty() ||
            embedding.values.size() % embedding.embedding_width != 0U) {
            *error = "Textual inversion embedding metadata or shape is invalid.";
            return false;
        }
        if (std::find(artifact_indices.begin(), artifact_indices.end(), embedding.artifact_index) !=
            artifact_indices.end()) {
            *error = "Textual inversion artifact indices must be unique.";
            return false;
        }
        artifact_indices.push_back(embedding.artifact_index);
        const size_t rows = embedding.values.size() / embedding.embedding_width;
        if (rows == 0U || rows > 75U ||
            std::any_of(embedding.values.begin(), embedding.values.end(), [](float value) {
                return !std::isfinite(value);
            })) {
            *error = "Textual inversion tensor rows must be finite and fit the CLIP content budget.";
            return false;
        }
        if (*embedding_width == 0U) *embedding_width = embedding.embedding_width;
        if (*embedding_width != embedding.embedding_width) {
            *error = "Textual inversion tensors in one CLIP encoder must share one width.";
            return false;
        }
        std::string normalized = embedding.trigger;
        std::transform(normalized.begin(), normalized.end(), normalized.begin(), [](unsigned char value) {
            return value >= 'A' && value <= 'Z' ? static_cast<char>(value - 'A' + 'a')
                                                 : static_cast<char>(value);
        });
        if (std::find(normalized_triggers.begin(), normalized_triggers.end(), normalized) !=
            normalized_triggers.end()) {
            *error = "Textual inversion triggers must be unique ignoring ASCII case.";
            return false;
        }
        normalized_triggers.push_back(std::move(normalized));
    }
    return true;
}

bool encode_conditioned_clip_sequence(
        tokenizers::Tokenizer* tokenizer,
        const std::string& text,
        const ClipTokenizerConfig& config,
        const std::vector<ClipTextualInversionEmbedding>& embeddings,
        ClipConditionedSequence* output,
        std::string* error) {
    if (tokenizer == nullptr || output == nullptr || error == nullptr) return false;
    *output = ClipConditionedSequence{};
    size_t embedding_width = 0U;
    if (!validate_config(config, error) ||
        !validate_textual_inversion_embeddings(embeddings, &embedding_width, error)) {
        return false;
    }
    const ClipFragmentEncoder encoder = [tokenizer](
            const std::string& fragment,
            std::vector<int32_t>* token_ids,
            std::string* encode_error) {
        if (token_ids == nullptr || encode_error == nullptr) return false;
        try {
            const std::vector<int> encoded = tokenizer->Encode(fragment);
            token_ids->assign(encoded.begin(), encoded.end());
            return true;
        } catch (const std::exception& exception) {
            *encode_error = std::string("Tokenizer execution failed: ") + exception.what();
            return false;
        } catch (...) {
            *encode_error = "Tokenizer execution failed with an unknown native exception.";
            return false;
        }
    };

    std::vector<WeightedPromptFragment> fragments;
    if (config.enable_prompt_weighting) {
        if (!parse_clip_prompt_weighting(text, &fragments, error)) return false;
    } else {
        fragments.push_back(WeightedPromptFragment{text, 1.0f});
    }

    std::vector<int32_t> content_ids;
    std::vector<float> content_weights;
    std::vector<uint8_t> content_override_mask;
    std::vector<float> content_overrides;
    uint64_t tokenizer_match_mask = 0U;
    struct AppliedOccurrence {
        size_t artifact_index = 0U;
        size_t end_content_index = 0U;
        size_t vector_count = 0U;
    };
    std::vector<AppliedOccurrence> occurrences;

    const auto append_literal = [&](const std::string& literal, float weight) -> bool {
        if (literal.empty()) return true;
        std::vector<int32_t> ids;
        if (!encoder(literal, &ids, error)) return false;
        if (std::any_of(ids.begin(), ids.end(), [](int32_t id) { return id < 0; })) {
            *error = "Tokenizer returned a negative token id.";
            return false;
        }
        content_ids.insert(content_ids.end(), ids.begin(), ids.end());
        content_weights.insert(content_weights.end(), ids.size(), weight);
        content_override_mask.insert(content_override_mask.end(), ids.size(), UINT8_C(0));
        content_overrides.resize(content_ids.size() * embedding_width, 0.0f);
        return true;
    };

    for (const auto& fragment : fragments) {
        size_t cursor = 0U;
        while (cursor < fragment.text.size()) {
            size_t match_offset = std::string::npos;
            size_t match_index = 0U;
            size_t match_length = 0U;
            for (size_t offset = cursor; offset < fragment.text.size(); ++offset) {
                for (size_t index = 0; index < embeddings.size(); ++index) {
                    const auto& trigger = embeddings[index].trigger;
                    if (trigger_matches_at(fragment.text, offset, trigger) &&
                        (match_offset == std::string::npos || offset < match_offset ||
                         (offset == match_offset && trigger.size() > match_length))) {
                        match_offset = offset;
                        match_index = index;
                        match_length = trigger.size();
                    }
                }
                if (match_offset != std::string::npos) break;
            }
            if (match_offset == std::string::npos) {
                if (!append_literal(fragment.text.substr(cursor), fragment.weight)) return false;
                cursor = fragment.text.size();
                continue;
            }
            if (!append_literal(
                    fragment.text.substr(cursor, match_offset - cursor),
                    fragment.weight)) {
                return false;
            }
            const auto& embedding = embeddings[match_index];
            const size_t rows = embedding.values.size() / embedding_width;
            const size_t first_row = content_ids.size();
            content_ids.insert(content_ids.end(), rows, config.eos_id);
            content_weights.insert(content_weights.end(), rows, fragment.weight);
            content_override_mask.insert(content_override_mask.end(), rows, UINT8_C(1));
            content_overrides.resize(content_ids.size() * embedding_width, 0.0f);
            std::copy(
                embedding.values.begin(),
                embedding.values.end(),
                content_overrides.begin() + first_row * embedding_width);
            tokenizer_match_mask |= UINT64_C(1) << embedding.artifact_index;
            occurrences.push_back(AppliedOccurrence{
                embedding.artifact_index,
                content_ids.size(),
                rows,
            });
            cursor = match_offset + match_length;
        }
    }

    const size_t special_count = (config.add_bos ? 1U : 0U) + (config.add_eos ? 1U : 0U);
    const size_t content_capacity = static_cast<size_t>(config.max_length) - special_count;
    for (const auto& occurrence : occurrences) {
        if (occurrence.end_content_index > content_capacity) {
            *error = "A selected textual inversion trigger was truncated before all embedding vectors reached CLIP.";
            return false;
        }
    }
    if (!finalize_clip_sequence(
            content_ids,
            content_weights,
            config,
            &output->tokens,
            error)) {
        return false;
    }
    output->embedding_width = embedding_width;
    output->override_mask.assign(static_cast<size_t>(config.max_length), UINT8_C(0));
    output->embedding_overrides.assign(
        static_cast<size_t>(config.max_length) * embedding_width,
        0.0f);
    const size_t retained = std::min(content_ids.size(), content_capacity);
    const size_t output_offset = config.add_bos ? 1U : 0U;
    for (size_t content_index = 0U; content_index < retained; ++content_index) {
        if (content_override_mask[content_index] == 0U) continue;
        const size_t output_index = output_offset + content_index;
        output->override_mask[output_index] = UINT8_C(1);
        std::copy_n(
            content_overrides.begin() + content_index * embedding_width,
            embedding_width,
            output->embedding_overrides.begin() + output_index * embedding_width);
    }
    output->tokenizer_match_mask = tokenizer_match_mask;
    for (const auto& occurrence : occurrences) {
        output->applied_mask |= UINT64_C(1) << occurrence.artifact_index;
        output->applied_vector_count += occurrence.vector_count;
    }
    return true;
}
#endif

}  // namespace

std::string sha256_hex_bytes(const std::vector<uint8_t>& payload) {
    return sha256_hex(payload);
}

std::string image_prompt_execution_sha256(const std::string& prompt,
                                          const std::string& negative_prompt) {
    if (prompt.size() > static_cast<size_t>(std::numeric_limits<uint32_t>::max()) ||
        negative_prompt.size() > static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
        return {};
    }
    std::vector<uint8_t> payload;
    payload.reserve(10U + prompt.size() + negative_prompt.size());
    const auto append_non_null_utf8 = [&payload](const std::string& value) {
        payload.push_back(1U);
        const uint32_t size = static_cast<uint32_t>(value.size());
        payload.push_back(static_cast<uint8_t>((size >> 24U) & UINT32_C(0xff)));
        payload.push_back(static_cast<uint8_t>((size >> 16U) & UINT32_C(0xff)));
        payload.push_back(static_cast<uint8_t>((size >> 8U) & UINT32_C(0xff)));
        payload.push_back(static_cast<uint8_t>(size & UINT32_C(0xff)));
        payload.insert(payload.end(), value.begin(), value.end());
    };
    append_non_null_utf8(prompt);
    append_non_null_utf8(negative_prompt);
    return sha256_hex(payload);
}

std::vector<int32_t> ClipTokenPair::negative_then_positive() const {
    std::vector<int32_t> combined;
    combined.reserve(negative.ids.size() + positive.ids.size());
    combined.insert(combined.end(), negative.ids.begin(), negative.ids.end());
    combined.insert(combined.end(), positive.ids.begin(), positive.ids.end());
    return combined;
}

std::vector<float> ClipTokenPair::negative_then_positive_weights() const {
    std::vector<float> combined;
    combined.reserve(negative.weights.size() + positive.weights.size());
    combined.insert(combined.end(), negative.weights.begin(), negative.weights.end());
    combined.insert(combined.end(), positive.weights.begin(), positive.weights.end());
    return combined;
}

std::string ClipTokenPair::weighting_fingerprint() const {
    auto payload = fingerprint_payload_with_domain("mca.clip.weight.pair.v1");
    if (!append_sequence_fingerprint_payload(negative.ids, negative.weights, &payload) ||
        !append_sequence_fingerprint_payload(positive.ids, positive.weights, &payload)) {
        return {};
    }
    return sha256_hex(payload);
}

uint64_t ClipConditionedPair::tokenizer_match_mask() const {
    return negative.tokenizer_match_mask | positive.tokenizer_match_mask;
}

uint64_t ClipConditionedPair::applied_mask() const {
    return negative.applied_mask | positive.applied_mask;
}

size_t ClipConditionedPair::applied_vector_count() const {
    return negative.applied_vector_count + positive.applied_vector_count;
}

bool parse_clip_prompt_weighting(const std::string& prompt,
                                 std::vector<WeightedPromptFragment>* fragments,
                                 std::string* error) {
    if (fragments == nullptr || error == nullptr) return false;
    fragments->clear();
    error->clear();
    if (!parse_weighted_range(prompt, 1.0f, 0, fragments, error)) {
        fragments->clear();
        return false;
    }
    if (fragments->empty()) fragments->push_back(WeightedPromptFragment{});
    return true;
}

bool tokenize_weighted_clip_sequence_with_encoder(const std::string& prompt,
                                                  const ClipTokenizerConfig& config,
                                                  const ClipFragmentEncoder& encoder,
                                                  ClipTokenSequence* output, std::string* error) {
    if (output == nullptr || error == nullptr) return false;
    *output = ClipTokenSequence{};
    error->clear();
    if (!validate_config(config, error)) return false;
    if (!encoder) {
        *error = "CLIP fragment encoder is not configured.";
        return false;
    }
    std::vector<WeightedPromptFragment> fragments;
    if (config.enable_prompt_weighting) {
        if (!parse_clip_prompt_weighting(prompt, &fragments, error)) return false;
    } else {
        fragments.push_back(WeightedPromptFragment{prompt, 1.0f});
    }

    std::vector<int32_t> content_ids;
    std::vector<float> content_weights;
    for (const auto& fragment : fragments) {
        std::vector<int32_t> fragment_ids;
        if (!encoder(fragment.text, &fragment_ids, error)) return false;
        if (std::any_of(fragment_ids.begin(), fragment_ids.end(),
                        [](int32_t id) { return id < 0; })) {
            *error = "Tokenizer returned a negative token id.";
            return false;
        }
        content_ids.insert(content_ids.end(), fragment_ids.begin(), fragment_ids.end());
        content_weights.insert(content_weights.end(), fragment_ids.size(), fragment.weight);
    }
    return finalize_clip_sequence(std::move(content_ids), std::move(content_weights), config,
                                  output, error);
}

bool apply_clip_token_weights_to_embeddings(const std::vector<float>& embeddings,
                                            size_t token_count, size_t embedding_width,
                                            const std::vector<float>& weights,
                                            bool normalize_mean_amplitude,
                                            std::vector<float>* weighted_embeddings,
                                            ClipEmbeddingWeightStats* stats, std::string* error) {
    if (weighted_embeddings == nullptr || stats == nullptr || error == nullptr) return false;
    std::vector<float> aliased_input;
    const std::vector<float>* input = &embeddings;
    if (&embeddings == weighted_embeddings) {
        aliased_input = embeddings;
        input = &aliased_input;
    }
    weighted_embeddings->clear();
    *stats = ClipEmbeddingWeightStats{};
    error->clear();
    if (token_count == 0 || embedding_width == 0) {
        *error = "CLIP embedding token count and width must be non-zero.";
        return false;
    }
    if (token_count > std::numeric_limits<size_t>::max() / embedding_width ||
            input->size() != token_count * embedding_width) {
        *error = "CLIP embedding tensor shape does not match its data length.";
        return false;
    }
    if (weights.size() != token_count) {
        *error = "CLIP embedding token count does not match its weight count.";
        return false;
    }

    double input_amplitude_sum = 0.0;
    double weighted_amplitude_sum = 0.0;
    weighted_embeddings->resize(input->size());
    for (size_t token = 0; token < token_count; ++token) {
        const float weight = weights[token];
        if (!std::isfinite(weight)) {
            weighted_embeddings->clear();
            *error = "CLIP embedding weights must all be finite.";
            return false;
        }
        if (std::abs(weight - 1.0f) > kWeightEqualityTolerance) {
            ++stats->weighted_token_count;
        }
        for (size_t column = 0; column < embedding_width; ++column) {
            const size_t index = token * embedding_width + column;
            const float input_value = (*input)[index];
            if (!std::isfinite(input_value)) {
                weighted_embeddings->clear();
                *error = "CLIP embeddings must all be finite.";
                return false;
            }
            const float weighted = input_value * weight;
            if (!std::isfinite(weighted)) {
                weighted_embeddings->clear();
                *error = "CLIP embedding weighting overflowed.";
                return false;
            }
            (*weighted_embeddings)[index] = weighted;
            input_amplitude_sum += std::abs(static_cast<double>(input_value));
            weighted_amplitude_sum += std::abs(static_cast<double>(weighted));
        }
    }

    const double element_count = static_cast<double>(input->size());
    stats->weighting_applied = stats->weighted_token_count > 0;
    stats->input_mean_amplitude = static_cast<float>(input_amplitude_sum / element_count);
    stats->weighted_mean_amplitude = static_cast<float>(weighted_amplitude_sum / element_count);
    stats->normalization_scale = 1.0f;
    if (normalize_mean_amplitude && input_amplitude_sum > 0.0 &&
        weighted_amplitude_sum > std::numeric_limits<double>::epsilon()) {
        const double scale = input_amplitude_sum / weighted_amplitude_sum;
        if (!std::isfinite(scale)) {
            weighted_embeddings->clear();
            *error = "CLIP embedding normalization produced a non-finite scale.";
            return false;
        }
        stats->normalization_scale = static_cast<float>(scale);
        for (float& value : *weighted_embeddings) {
            value *= stats->normalization_scale;
        }
    }
    double output_amplitude_sum = 0.0;
    for (const float value : *weighted_embeddings) {
        if (!std::isfinite(value)) {
            weighted_embeddings->clear();
            *error = "CLIP embedding normalization overflowed.";
            return false;
        }
        output_amplitude_sum += std::abs(static_cast<double>(value));
    }
    stats->output_mean_amplitude = static_cast<float>(output_amplitude_sum / element_count);
    return true;
}

bool validate_clip_token_id_graph_prompt_weights(const std::vector<float>& weights,
                                                 size_t* weighted_token_count,
                                                 std::string* error) {
    if (weighted_token_count == nullptr || error == nullptr) return false;
    *weighted_token_count = 0;
    error->clear();
    if (weights.empty()) {
        *error = "CLIP token-id graph weighting evidence is empty.";
        return false;
    }
    for (const float weight : weights) {
        if (!std::isfinite(weight)) {
            *error = "CLIP token-id graph weighting evidence contains a non-finite value.";
            return false;
        }
        if (std::abs(weight - 1.0f) > kWeightEqualityTolerance) {
            ++(*weighted_token_count);
        }
    }
    if (*weighted_token_count != 0U) {
        std::ostringstream message;
        message << "Prompt weighting cannot be executed exactly by an int32 token-id text "
                << "encoder graph: " << *weighted_token_count
                << " token weights require a pre-transformer embedding input and a compatible "
                << "token-embedding artifact. Weighting the graph's final hidden-state output "
                << "is not equivalent. Use an unweighted prompt with this bundle.";
        *error = message.str();
        return false;
    }
    return true;
}

bool tokenize_clip_pair_from_json(const std::string& tokenizer_json_path,
                                  const std::string& positive_prompt,
                                  const std::string& negative_prompt,
                                  const ClipTokenizerConfig& config, ClipTokenPair* output,
                                  std::string* error,
                                  bool include_negative) {
    if (output == nullptr || error == nullptr) return false;
    output->negative = ClipTokenSequence{};
    output->positive = ClipTokenSequence{};
    error->clear();
    if (!validate_config(config, error)) return false;

#if MCA_WITH_TOKENIZERS_CPP
    std::string tokenizer_json;
    if (!read_bounded_file(tokenizer_json_path, &tokenizer_json, error)) return false;
    std::unique_ptr<tokenizers::Tokenizer> tokenizer;
    try {
        tokenizer = tokenizers::Tokenizer::FromBlobJSON(tokenizer_json);
    } catch (const std::exception& exception) {
        *error = std::string("Tokenizer JSON parse failed: ") + exception.what();
        return false;
    } catch (...) {
        *error = "Tokenizer JSON parse failed with an unknown native exception.";
        return false;
    }
    if (!tokenizer) {
        *error = "Tokenizer JSON did not create a tokenizer instance.";
        return false;
    }
    return (!include_negative ||
            encode_clip_sequence(
                tokenizer.get(), negative_prompt, config, &output->negative, error)) &&
           encode_clip_sequence(tokenizer.get(), positive_prompt, config, &output->positive, error);
#else
    (void)tokenizer_json_path;
    (void)positive_prompt;
    (void)negative_prompt;
    (void)include_negative;
    *error = "The standard tokenizer backend is not packaged in this build.";
    return false;
#endif
}

bool measure_clip_prompt_from_json(const std::string& tokenizer_json_path,
                                   const std::string& prompt,
                                   const ClipTokenizerConfig& config,
                                   ClipPromptTokenMeasurement* output,
                                   std::string* error) {
    if (output == nullptr || error == nullptr) return false;
    *output = ClipPromptTokenMeasurement{};
    error->clear();
    if (!validate_config(config, error)) return false;

#if MCA_WITH_TOKENIZERS_CPP
    std::string tokenizer_json;
    if (!read_bounded_file(tokenizer_json_path, &tokenizer_json, error)) return false;
    std::unique_ptr<tokenizers::Tokenizer> tokenizer;
    try {
        tokenizer = tokenizers::Tokenizer::FromBlobJSON(tokenizer_json);
    } catch (const std::exception& exception) {
        *error = std::string("Tokenizer JSON parse failed: ") + exception.what();
        return false;
    } catch (...) {
        *error = "Tokenizer JSON parse failed with an unknown native exception.";
        return false;
    }
    if (!tokenizer) {
        *error = "Tokenizer JSON did not create a tokenizer instance.";
        return false;
    }

    const auto measure = [&](const std::string& candidate, size_t* count) -> bool {
        if (count == nullptr) return false;
        ClipTokenSequence sequence;
        std::string candidate_error;
        if (!encode_clip_sequence(tokenizer.get(), candidate, config, &sequence,
                                  &candidate_error)) {
            return false;
        }
        *count = sequence.untruncated_token_count;
        return true;
    };

    size_t full_count = 0U;
    if (!measure(prompt, &full_count)) {
        *error = "Tokenizer could not measure the complete prompt.";
        return false;
    }
    output->token_count = full_count;
    output->max_length = static_cast<size_t>(config.max_length);
    // tokenizers-cpp does not expose source offsets in its small public API.
    // A prefix-by-prefix retry turns long-prompt typing into O(n^2) tokenizer
    // work, so return the exact count without a source offset. The Kotlin UI
    // already treats this field as optional and can still show overflow.
    return true;
#else
    (void)tokenizer_json_path;
    (void)prompt;
    (void)config;
    *error = "The standard tokenizer backend is not packaged in this build.";
    return false;
#endif
}

bool tokenize_clip_pair_with_textual_inversion_from_json(
        const std::string& tokenizer_json_path,
        const std::string& positive_prompt,
        const std::string& negative_prompt,
        const ClipTokenizerConfig& config,
        const std::vector<ClipTextualInversionEmbedding>& embeddings,
        ClipConditionedPair* output,
        std::string* error,
        bool include_negative) {
    if (output == nullptr || error == nullptr) return false;
    *output = ClipConditionedPair{};
    error->clear();
    if (!validate_config(config, error)) return false;
#if MCA_WITH_TOKENIZERS_CPP
    std::string tokenizer_json;
    if (!read_bounded_file(tokenizer_json_path, &tokenizer_json, error)) return false;
    std::unique_ptr<tokenizers::Tokenizer> tokenizer;
    try {
        tokenizer = tokenizers::Tokenizer::FromBlobJSON(tokenizer_json);
    } catch (const std::exception& exception) {
        *error = std::string("Tokenizer JSON parse failed: ") + exception.what();
        return false;
    } catch (...) {
        *error = "Tokenizer JSON parse failed with an unknown native exception.";
        return false;
    }
    if (!tokenizer) {
        *error = "Tokenizer JSON did not create a tokenizer instance.";
        return false;
    }
    if ((include_negative && !encode_conditioned_clip_sequence(
            tokenizer.get(), negative_prompt, config, embeddings, &output->negative, error)) ||
        !encode_conditioned_clip_sequence(
            tokenizer.get(), positive_prompt, config, embeddings, &output->positive, error)) {
        return false;
    }
    const uint64_t expected_mask = embeddings.size() >= 64U
            ? UINT64_MAX
            : ((UINT64_C(1) << embeddings.size()) - UINT64_C(1));
    const uint64_t tokenizer_match_mask = include_negative
        ? output->tokenizer_match_mask()
        : output->positive.tokenizer_match_mask;
    const uint64_t applied_mask = include_negative
        ? output->applied_mask()
        : output->positive.applied_mask;
    if (tokenizer_match_mask != expected_mask || applied_mask != expected_mask) {
        *error = "Every selected textual inversion trigger must match and reach a CLIP input row.";
        return false;
    }
    return true;
#else
    (void)tokenizer_json_path;
    (void)positive_prompt;
    (void)negative_prompt;
    (void)embeddings;
    (void)include_negative;
    *error = "The standard tokenizer backend is not packaged in this build.";
    return false;
#endif
}

}  // namespace mca::image
