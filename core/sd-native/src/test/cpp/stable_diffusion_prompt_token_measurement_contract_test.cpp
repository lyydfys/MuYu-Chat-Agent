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

std::string directory_of(const std::string& path) {
    const auto last_slash = path.find_last_of("/\\");
    return last_slash == std::string::npos ? std::string() : path.substr(0, last_slash);
}

// The prompt-token measurement host moved out of MainViewModel when the ViewModel was split (the
// route selection, the bridge call and its `exact = false` argument now live in
// ImagePromptTokenMeasurementHost.kt next to MainViewModel.kt).  The ViewModel read set is composed
// with that file; a missing file fails the test loudly instead of silently shrinking the read set.
std::string read_required_text(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
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
    // T2B17/R7-C6: the CLIP token measurement leaf moved out of the bridge into
    // sd_prompt_token_measure.cpp; the read set composes both so the six bridge-side needles
    // below still fail loudly if either copy disappears.
    const std::string native =
            read_text(argv[1]) +
            read_required_text(directory_of(std::string(argv[1])) + "/sd_prompt_token_measure.cpp");
    const std::string kotlin_bridge = read_text(argv[2]);
    const std::string view_model =
            read_text(argv[3]) +
            read_required_text(directory_of(std::string(argv[3])) + "/ImagePromptTokenMeasurementHost.kt");
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
