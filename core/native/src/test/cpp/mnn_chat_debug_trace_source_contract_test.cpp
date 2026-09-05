#include <cassert>
#include <cstdio>
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
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN chat debug-trace contract: %s\n", needle.c_str());
        assert(false);
    }
}

void require_sequence(
        const std::string& source,
        const std::string& first,
        const std::string& second) {
    const auto first_position = source.find(first);
    const auto second_position = first_position == std::string::npos
            ? std::string::npos
            : source.find(second, first_position + first.size());
    if (first_position == std::string::npos || second_position == std::string::npos) {
        std::fprintf(
                stderr,
                "missing ordered MNN chat debug-trace contract: %s -> %s\n",
                first.c_str(),
                second.c_str());
        assert(false);
    }
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const auto source = read_text(argv[1]);

    require_contains(source, "advanced_json_from_params(params)");
    require_contains(source, "enabled->is_boolean() && enabled->get<bool>()");
    require_contains(source, "g_mnn_debug_generated_token_ids.clear()");
    require_contains(source, "constexpr size_t kMaxDebugGeneratedTokenIds = 64");
    require_contains(source, "mnnDebugRawOutputHex");
    require_contains(source, "mnnDebugGeneratedTokenIds");
    require_contains(source, "mnnDebugTokenIdsTruncated");
    require_contains(source, "if (hasCommittedTranscript && !extendsCommittedTranscript)");
    require_sequence(
            source,
            "if (hasCommittedTranscript && !extendsCommittedTranscript)",
            "g_mnn_prompt_cache.committed_messages.clear();");
    require_sequence(
            source,
            "g_llm->generate(1);",
            "capture_mnn_debug_generated_token_ids_locked();");
    return 0;
}
