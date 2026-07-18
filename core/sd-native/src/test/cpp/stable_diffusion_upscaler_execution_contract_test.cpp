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

void require_absent(const std::string& source, const std::string& needle) {
    assert(source.find(needle) == std::string::npos);
}

void require_before(const std::string& source,
                    const std::string& first,
                    const std::string& second) {
    const size_t first_position = source.find(first);
    const size_t second_position = source.find(second);
    assert(first_position != std::string::npos);
    assert(second_position != std::string::npos);
    assert(first_position < second_position);
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 4);
    const std::string public_header = read_text(argv[1]);
    const std::string bridge = read_text(argv[2]);
    const std::string upscaler = read_text(argv[3]);

    // The evidence ABI has one public definition shared by the bridge and
    // implementation, with version, width and offset contracts fixed in C types.
    require_contains(public_header, "SD_UPSCALER_EXECUTION_EVIDENCE_VERSION 1u");
    require_contains(public_header, "typedef struct sd_upscaler_execution_evidence_t");
    require_contains(public_header, "uint64_t compute_invocation_count;");
    require_contains(public_header, "uint64_t tile_compute_success_count;");
    require_contains(public_header, "sizeof(sd_upscaler_execution_evidence_t) == 72u");
    require_contains(public_header, "compute_invocation_count) == 40u");
    require_contains(public_header, "tile_compute_success_count) == 64u");
    require_contains(public_header, "SD_API bool sd_get_upscaler_execution_evidence(");
    require_absent(bridge, "struct sd_upscaler_execution_evidence_t {");
    require_absent(upscaler, "struct sd_upscaler_execution_evidence_t {");

    // Every physical model compute is counted at its call site. Tile counts
    // are separate and a context-owned completion record survives until read.
    require_before(upscaler,
                   "record_compute_start(false);",
                   "esrgan_upscaler->compute(n_threads, input_tensor)");
    require_before(upscaler,
                   "record_compute_start(true);",
                   "esrgan_upscaler->compute(n_threads, input_tile)");
    require_contains(upscaler, "compute_invocation_count");
    require_contains(upscaler, "tile_compute_invocation_count");
    require_contains(upscaler, "sd_get_upscaler_execution_evidence(");
    require_contains(upscaler, "upscale_factor != static_cast<uint32_t>(native_scale)");
    require_contains(upscaler, "new (std::nothrow) upscaler_ctx_t()");
    const size_t loader_failure = upscaler.find("if (!model_loader.init_from_file_and_convert_name");
    const size_t loader_return = upscaler.find("return false;", loader_failure);
    const size_t loader_use = upscaler.find("model_loader.set_wtype_override", loader_failure);
    assert(loader_failure != std::string::npos);
    assert(loader_return != std::string::npos);
    assert(loader_use != std::string::npos);
    assert(loader_failure < loader_return && loader_return < loader_use);

    // The bridge hashes the actual bounded file, detects identity changes,
    // consumes evidence before release, and never echoes requested SHA as actual.
    require_contains(bridge, "sha256_regular_file(");
    require_contains(bridge, "actual_upscaler_sha256 != requested_upscaler_sha256");
    require_contains(bridge, "same_regular_file_identity(");
    require_before(bridge,
                   "const bool has_execution_evidence = sd_get_upscaler_execution_evidence(",
                   "upscaler_ctx.reset();");
    require_contains(bridge, "UPSCALER_EXECUTION_EVIDENCE_INVALID");
    require_contains(bridge, "physicalComputeCount");
    require_contains(bridge, "physicalTileComputeCount");
    require_contains(bridge, "modelHashVerified");
    require_contains(bridge, "modelFileIdentityStable");
    require_contains(bridge, "upscalerFileName");
    require_contains(bridge, "kMaxUpscaleInputPixels = 4000000ull");
    require_contains(bridge, "kMaxUpscaleProductOutputSide = 4096u");
    require_contains(bridge, "kMaxUpscaleProductOutputPixels = 16000000ull");
    require_contains(bridge, "kMaxUpscaleNativeOutputSide = 8192u");
    require_contains(bridge, "kMaxUpscaleNativeOutputPixels = 64000000ull");
    require_before(bridge,
                   "pixel_count > maximum_pixels",
                   "stbi_load_from_memory(");
    require_before(bridge,
                   "requested_pixels > kMaxUpscaleProductOutputPixels",
                   "sd_image_t upscaled = upscale(");
    require_absent(bridge, "{\"upscalerSha256\", requested_upscaler_sha256}");
    require_absent(bridge, "{\"physicalComputeCount\", 1}");
    return 0;
}
