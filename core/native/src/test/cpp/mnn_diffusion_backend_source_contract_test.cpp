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

std::string function_body(const std::string& source, const std::string& signature) {
    const auto signaturePosition = source.find(signature);
    const auto openingBrace = signaturePosition == std::string::npos
            ? std::string::npos
            : source.find('{', signaturePosition + signature.size());
    assert(signaturePosition != std::string::npos);
    assert(openingBrace != std::string::npos);
    int depth = 0;
    for (size_t position = openingBrace; position < source.size(); ++position) {
        if (source[position] == '{') ++depth;
        if (source[position] == '}' && --depth == 0) {
            return source.substr(openingBrace, position - openingBrace + 1U);
        }
    }
    assert(false);
    return {};
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN backend contract: %s\n", needle.c_str());
        assert(false);
    }
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const auto source = read_text(argv[1]);

    require_contains(source, "bool mnn_sd15_unet_backend_unsupported(");
    const auto predicate = function_body(
            source, "bool mnn_sd15_unet_backend_unsupported(");
    require_contains(predicate, "status 2");
    require_contains(predicate, "NOT_SUPPORT");
    require_contains(predicate, "Create execution error : 304");
    require_contains(predicate, "GroupNorm");

    const auto generate = function_body(
            source, "json run_mnn_sd15_interpreter_direct(");
    require_contains(generate, "contract.backend_mode == \"cpu\"");
    require_contains(generate, "MNN_UNET_BACKEND_UNSUPPORTED");
    require_contains(generate, "fallbackEligible");
    require_contains(generate, "unsupportedOps");
    require_contains(generate, "GroupNorm");
    require_contains(generate, "FmhaV2");
    require_contains(generate, "SplitGeLU");
    require_contains(generate, "backendMode");
    return 0;
}
