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
    assert(argc == 5);
    const std::string native = read_text(argv[1]);
    const std::string kotlin_bridge = read_text(argv[2]);
    const std::string view_model = read_text(argv[3]);
    const std::string cmake = read_text(argv[4]);

    require_contains(native, "#include \"tokenizers/clip_tokenizer.h\"");
    require_contains(native, "parse_prompt_attention(prompt)");
    require_contains(native, "static CLIPTokenizer tokenizer");
    require_contains(native, "part.first == \"BREAK\"");
    require_contains(native, "content_token_count + 2u");
    require_contains(native, "{\"exact\", true}");
    require_contains(
            native,
            "Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_measurePromptTokens");

    require_contains(kotlin_bridge, "external fun measurePromptTokens(");
    require_contains(view_model, "ImagePromptTokenMeasurementRoute.SDCPP_CLIP");
    require_contains(view_model, "NativeStableDiffusionBridge().measurePromptTokens(");
    require_contains(view_model, "exact = false");
    require_contains(cmake, "${SDCPP_SRC}/src");
    return 0;
}
