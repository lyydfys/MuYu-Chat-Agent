#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

namespace {
std::string read_text(const char *path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void require_contains(const std::string &source, const std::string &needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN stop contract: %s\n", needle.c_str());
        assert(false);
    }
}

void require_not_contains(const std::string &source, const std::string &needle) {
    if (source.find(needle) != std::string::npos) {
        std::fprintf(stderr, "unexpected MNN stop contract: %s\n", needle.c_str());
        assert(false);
    }
}

std::string function_body(const std::string &source, const std::string &signature) {
    const auto start = source.find(signature);
    assert(start != std::string::npos);
    const auto open = source.find('{', start + signature.size());
    assert(open != std::string::npos);
    int depth = 0;
    for (size_t i = open; i < source.size(); ++i) {
        if (source[i] == '{') ++depth;
        if (source[i] == '}' && --depth == 0) return source.substr(open, i - open + 1);
    }
    assert(false);
    return {};
}
}  // namespace

int main(int argc, char **argv) {
    assert(argc == 2);
    const auto source = read_text(argv[1]);
    require_contains(source, "std::atomic_bool g_stop_signal{false};");
    const auto reset = function_body(source, "void reset_generation_state_locked()");
    require_contains(reset, "g_stop_signal.store(false, std::memory_order_release)");

    const auto stop = function_body(
            source,
            "Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStop(");
    require_contains(stop, "g_stop_signal.store(true, std::memory_order_release)");
    require_not_contains(stop, "std::lock_guard<std::mutex> lock(g_mnn_mutex)");

    const auto generate = function_body(
            source,
            "Java_com_muyuchat_core_nativebridge_NativeMnnBridge_generateNextChunk(");
    require_contains(generate, "g_stop_signal.exchange(false, std::memory_order_acq_rel)");
    require_contains(generate, "rollback_mnn_text_prompt_cache_locked(\"stop_requested\", false)");
    return 0;
}
