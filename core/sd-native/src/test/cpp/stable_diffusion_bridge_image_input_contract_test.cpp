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
    assert(argc == 2);
    const std::string source = read_text(argv[1]);

    // Decode is bounded before allocation and the worker-prepared content hash
    // is verified against the exact bytes passed to STB.
    require_contains(source, "kMaxInputImageBytes = 32u * 1024u * 1024u");
    require_contains(source, "kMaxInputImagePixels = 64u * 1024u * 1024u");
    require_contains(source, "lstat(raw_path.c_str(), &link_stat)");
    require_contains(source, "image.sha256 = sha256_hex(encoded)");
    require_contains(source, "image.sha256 != lower_copy(expected_sha256)");
    require_contains(source, "sha256_implementation_ready()");
    require_contains(source, "e3b0c44298fc1c149afbf4c8996fb924");
    require_contains(source, "ba7816bf8f01cfea414140de5dae2223");
    require_before(source, "stbi_info_from_memory(", "stbi_load_from_memory(");

    // JPEG orientation is applied to decoded pixels, not only reported.
    require_contains(source, "jpeg_exif_orientation(encoded)");
    require_contains(source, "return orient_pixels(");

    // Every product image role must be wired into the public native request.
    require_contains(source, "gen.init_image = input_image.view()");
    require_contains(source, "gen.mask_image = mask_image.view()");
    require_contains(source, "gen.control_image = control_image.view()");
    require_contains(source, "!paths.control_net.empty()");
    require_contains(source, "controlnet_residual");
    require_contains(source, "execution_evidence.control_net_compute_attempt_count");
    require_contains(source, "actual_control_net_residual_consumption_count");
    require_contains(source, "actual_positive_control_net_compute_attempt_count != actual_positive_execution_count");
    require_contains(source, "actual_negative_control_net_compute_attempt_count != actual_negative_execution_count");
    require_contains(source, "controlNetEvidence");
    require_contains(source, "refusing to claim pixel consumption");
    require_absent(source, "native_effective[\"controlImageExecutionCount\"] = control_image_wired ? 1 : 0");

    // Strength changes the actual native denoising timetable and must be
    // validated independently from configured sample steps.
    require_contains(source, "expected_denoising_step_count(contract)");
    require_contains(source, "actualDenoisingStepCount");
    require_contains(source, "configuredSampleSteps");

    // Batch generation owns and publishes every returned image.
    require_contains(source, "kMaxBatchCount = 8");
    require_contains(source, "free_generated_images(images, contract.batch_count)");
    require_contains(source, "out[\"outputs\"].push_back");
    require_contains(source, "actualDiffusionModelComputeCount");
    require_absent(source, "batch_count != 1");
    require_absent(source, "free(images[0].data)");

    // VAE overlap is the public API's ratio, never an integer pixel count.
    require_contains(source, "gen.vae_tiling_params.target_overlap = contract.vae_tiling_enabled");
    require_contains(source, "gen.vae_tiling_params.rel_size_x = contract.vae_tiling_enabled");
    require_contains(source, "actual_vae_decode_tile_compute_success_count");
    require_contains(source, "requestedTileSize");
    require_contains(source, "plannedTileCount");
    require_contains(source, "actual_vae_decode_tiling_invocation_count != contract.batch_count");
    require_contains(source, "input_image_wired && actual_vae_encode_tiling_invocation_count <= 0");
    require_absent(source, "vae_tile_overlap_pixels");
    require_absent(source, "{\"tileSize\", gen.vae_tiling_params.tile_size_x}");
    return 0;
}
