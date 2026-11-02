#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "json.hpp"
#include "tokenizers/qwen2_tokenizer.h"
#include "tokenizers/t5_unigram_tokenizer.h"
#include "util.h"

namespace {

using json = nlohmann::json;

constexpr int kMinAnimaTokenCount = 1;
constexpr int kMaxAnimaTokenCount = 512;
constexpr size_t kMaxPromptBytes = 64 * 1024;

struct AnimaTokenizers {
    Qwen2Tokenizer qwen;
    T5UniGramTokenizer t5;
    std::mutex mutex;
};

AnimaTokenizers& tokenizers() {
    static AnimaTokenizers instance;
    return instance;
}

json tokenize_anima_prompt(const std::string& prompt, int qwen_max_tokens, int t5_max_tokens) {
    if (qwen_max_tokens < kMinAnimaTokenCount || qwen_max_tokens > kMaxAnimaTokenCount ||
        t5_max_tokens < kMinAnimaTokenCount || t5_max_tokens > kMaxAnimaTokenCount) {
        return json({
                {"ok", false},
                {"errorCode", "invalid_sequence_length"},
                {"message", "Anima Qwen and T5 sequence lengths must each be between 1 and 512."},
        });
    }
    if (prompt.size() > kMaxPromptBytes) {
        return json({
                {"ok", false},
                {"errorCode", "prompt_too_large"},
                {"message", "Anima prompt exceeds the 64 KiB tokenizer limit."},
        });
    }

    std::vector<int> qwen_ids;
    std::vector<float> qwen_weights;
    std::vector<float> qwen_mask;
    std::vector<int> t5_ids;
    std::vector<float> t5_weights;
    std::vector<float> t5_mask;

    try {
        auto& tokenizer = tokenizers();
        std::lock_guard<std::mutex> lock(tokenizer.mutex);
        const auto prompt_parts = parse_prompt_attention(prompt);
        for (const auto& part : prompt_parts) {
            const std::vector<int> qwen_part = tokenizer.qwen.tokenize(part.first, nullptr);
            qwen_ids.insert(qwen_ids.end(), qwen_part.begin(), qwen_part.end());
            // Upstream Anima assigns uniform Qwen weights and applies prompt attention only to T5.
            qwen_weights.insert(qwen_weights.end(), qwen_part.size(), 1.0f);

            const std::vector<int> t5_part = tokenizer.t5.encode(part.first);
            t5_ids.insert(t5_ids.end(), t5_part.begin(), t5_part.end());
            t5_weights.insert(t5_weights.end(), t5_part.size(), part.second);
        }

        if (qwen_ids.empty()) {
            qwen_ids.push_back(tokenizer.qwen.PAD_TOKEN_ID);
            qwen_weights.push_back(1.0f);
        }

        // Static LiteRT graphs need a fixed sequence. Keep the prefix, reserve EOS for T5,
        // right-pad, and emit masks so the exported LLM/adapter graphs can ignore pad positions.
        tokenizer.qwen.pad_tokens(
                qwen_ids,
                &qwen_weights,
                &qwen_mask,
                static_cast<size_t>(qwen_max_tokens),
                static_cast<size_t>(qwen_max_tokens),
                false);
        tokenizer.t5.pad_tokens(
                t5_ids,
                &t5_weights,
                &t5_mask,
                static_cast<size_t>(t5_max_tokens),
                static_cast<size_t>(t5_max_tokens),
                false);

        for (size_t index = 0; index < t5_weights.size(); ++index) {
            if (t5_mask[index] == 0.0f) t5_weights[index] = 0.0f;
        }
    } catch (const std::exception& error) {
        return json({
                {"ok", false},
                {"errorCode", "tokenization_failed"},
                {"message", error.what()},
        });
    } catch (...) {
        return json({
                {"ok", false},
                {"errorCode", "tokenization_failed"},
                {"message", "Anima tokenizer failed with an unknown native error."},
        });
    }

    const auto qwen_expected = static_cast<size_t>(qwen_max_tokens);
    const auto t5_expected = static_cast<size_t>(t5_max_tokens);
    if (qwen_ids.size() != qwen_expected || qwen_mask.size() != qwen_expected ||
        t5_ids.size() != t5_expected || t5_weights.size() != t5_expected || t5_mask.size() != t5_expected) {
        return json({
                {"ok", false},
                {"errorCode", "invalid_tokenizer_output"},
                {"message", "Anima tokenizer did not produce fixed-length IDs, weights, and masks."},
        });
    }

    auto mask_to_ints = [](const std::vector<float>& mask) {
        std::vector<int> result;
        result.reserve(mask.size());
        for (float value : mask) {
            if (value != 0.0f && value != 1.0f) {
                throw std::runtime_error("Tokenizer attention mask must contain only zero or one.");
            }
            result.push_back(value == 1.0f ? 1 : 0);
        }
        return result;
    };

    try {
        return json({
                {"ok", true},
                {"qwenTokenIds", qwen_ids},
                {"qwenAttentionMask", mask_to_ints(qwen_mask)},
                {"t5TokenIds", t5_ids},
                {"t5TokenWeights", t5_weights},
                {"t5AttentionMask", mask_to_ints(t5_mask)},
                {"qwenSequenceLength", qwen_max_tokens},
                {"t5SequenceLength", t5_max_tokens},
                {"qwenPadding", "right"},
                {"t5Padding", "right"},
                {"qwenTruncation", "tail"},
                {"t5Truncation", "tail_with_eos"},
        });
    } catch (const std::exception& error) {
        return json({
                {"ok", false},
                {"errorCode", "invalid_tokenizer_output"},
                {"message", error.what()},
        });
    }
}

std::string jstring_to_utf8(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    struct Releaser {
        JNIEnv* env;
        jstring value;
        const jchar* chars;
        ~Releaser() { env->ReleaseStringChars(value, chars); }
    } releaser{env, value, chars};

    const jsize length = env->GetStringLength(value);
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);
    auto append_code_point = [&result](uint32_t code_point) {
        if (code_point <= 0x7f) {
            result.push_back(static_cast<char>(code_point));
        } else if (code_point <= 0x7ff) {
            result.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
        } else if (code_point <= 0xffff) {
            result.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
        } else {
            result.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
        }
    };
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff) {
            if (index + 1 >= length || chars[index + 1] < 0xdc00 || chars[index + 1] > 0xdfff) {
                throw std::invalid_argument("Anima prompt contains an unmatched UTF-16 surrogate.");
            }
            code_point = 0x10000 + ((code_point - 0xd800) << 10) + (chars[++index] - 0xdc00);
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            throw std::invalid_argument("Anima prompt contains an unmatched UTF-16 surrogate.");
        }
        append_code_point(code_point);
    }
    return result;
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& value) {
    return env == nullptr ? nullptr : env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_tokenizeAnimaPrompt(
        JNIEnv* env,
        jobject,
        jstring prompt,
        jint qwen_max_tokens,
        jint t5_max_tokens) {
    if (env == nullptr) return nullptr;
    try {
        return utf8_to_jstring(
                env,
                tokenize_anima_prompt(jstring_to_utf8(env, prompt), qwen_max_tokens, t5_max_tokens).dump());
    } catch (const std::exception& error) {
        return utf8_to_jstring(env, json({
                {"ok", false},
                {"errorCode", "tokenization_failed"},
                {"message", error.what()},
        }).dump());
    }
}
