#include <cassert>
#include <fstream>
#include <sstream>
#include <string>

namespace {
std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void require_contains(const std::string& source, const std::string& needle) {
    assert(source.find(needle) != std::string::npos);
}
}  // namespace

int main(int argc, char** argv) {
    assert(argc == 4);
    const std::string native = read_text(argv[1]);
    const std::string bridge = read_text(argv[2]);
    const std::string cmake = read_text(argv[3]);

    require_contains(native, "Qwen2Tokenizer qwen;");
    require_contains(native, "T5UniGramTokenizer t5;");
    require_contains(native, "parse_prompt_attention(prompt)");
    require_contains(native, "tokenizer.qwen.tokenize(part.first, nullptr)");
    require_contains(native, "tokenizer.t5.encode(part.first)");
    require_contains(native, "qwen_weights.insert(qwen_weights.end(), qwen_part.size(), 1.0f)");
    require_contains(native, "t5_weights.insert(t5_weights.end(), t5_part.size(), part.second)");
    require_contains(native, "tokenizer.qwen.pad_tokens(");
    require_contains(native, "tokenizer.t5.pad_tokens(");
    require_contains(native, "if (t5_mask[index] == 0.0f) t5_weights[index] = 0.0f");
    require_contains(native, "env->GetStringChars(value, nullptr)");
    require_contains(native, "Anima prompt contains an unmatched UTF-16 surrogate");
    require_contains(native, "\"qwenAttentionMask\"");
    require_contains(native, "\"t5TokenWeights\"");
    require_contains(native, "\"t5AttentionMask\"");
    require_contains(native, "invalid_sequence_length");
    require_contains(native, "qwen_max_tokens),");
    require_contains(native, "t5_max_tokens),");
    require_contains(native, "qwenSequenceLength");
    require_contains(native, "t5SequenceLength");
    require_contains(native, "qwenTruncation");
    require_contains(native, "t5Truncation");
    require_contains(native, "invalid_tokenizer_output");
    require_contains(native, "Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_tokenizeAnimaPrompt");
    require_contains(
            bridge,
            "external fun tokenizeAnimaPrompt(prompt: String, qwenMaxTokens: Int, t5MaxTokens: Int): String");
    require_contains(cmake, "sd_anima_tokenize.cpp");
    return 0;
}
