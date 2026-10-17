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

std::string directory_of(const std::string& path) {
    const auto last_slash = path.find_last_of("/\\");
    return last_slash == std::string::npos ? std::string() : path.substr(0, last_slash);
}

// The asserted text was extracted out of the bridge into mca/sd modules; the read set is composed so
// the checks observe the real implementations.  A missing module fails the test loudly instead of
// silently shrinking the read set (which is how a wrongly-wired test would appear to pass).
std::string read_required_text(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string dir = directory_of(argv[1]);
    const std::string source = read_text(argv[1]);
    // requires the real implementations the extracted code now lives in (T2B17/R7-C6: also
    // the generate execute phase that owns the init-image wiring and VAE tiling needles).
    const std::string contract = source
            + read_required_text(dir + "/sd_generate_execute.cpp")
            + read_required_text(dir + "/mca/sd/sd_response_builder.cpp")
            + read_required_text(dir + "/mca/sd/sd_image_io.cpp")
            + read_required_text(dir + "/mca/sd/sd_controlnet_adapter.cpp")
            + read_required_text(dir + "/mca/sd/sd_contract_parser.cpp")
            + read_required_text(dir + "/mca/sd/sd_evidence_verifier.cpp")
            + read_required_text(dir + "/mca/sd/sd_inpaint_processor.cpp")
            + read_required_text(dir + "/mca/sd/sd_schedule_resolver.cpp");

    // Decode is bounded before allocation and the worker-prepared content hash
    // is verified against the exact bytes passed to STB.
    require_contains(contract, "kMaxInputImageBytes = 32u * 1024u * 1024u");
    require_contains(contract, "kMaxInputImagePixels = 64u * 1024u * 1024u");
    require_contains(contract, "lstat(raw_path.c_str(), &link_stat)");
    require_contains(contract, "contract.input_image_sha256 = lower_copy(optional_string(params,");
    require_contains(contract, "image.sha256 != lower_copy(expected_sha256)");
    require_contains(contract, "sha256_implementation_ready()");
    require_contains(contract, "e3b0c44298fc1c149afbf4c8996fb924");
    require_contains(contract, "ba7816bf8f01cfea414140de5dae2223");
    require_before(contract, "stbi_info_from_memory(", "stbi_load_from_memory(");

    // JPEG orientation is applied to decoded pixels, not only reported.
    require_contains(contract, "jpeg_exif_orientation(encoded)");
    require_contains(contract, "return orient_pixels(");

    // Every product image role must be wired into the public native request.
    require_contains(contract, "gen.init_image = input_image.view()");
    require_contains(contract, "gen.mask_image = mask_image.view()");
    require_contains(contract, "gen.control_image = control_image.view()");
    require_contains(contract, "!paths.control_net.empty()");
    require_contains(contract, "controlnet_residual");
    require_contains(contract, "execution_evidence.control_net_compute_attempt_count");
    require_contains(contract, "controlNetResidualConsumptionCount");
    require_contains(contract, "evidence.residual_consumption_count");
    require_contains(contract, "evidence.positive_compute_attempt_count != actual_positive_execution_count");
    require_contains(contract, "evidence.negative_compute_attempt_count != actual_negative_execution_count");
    require_contains(contract, "controlNetEvidence");
    require_contains(contract, "refusing to claim pixel consumption");
    require_absent(source, "native_effective[\"controlImageExecutionCount\"] = control_image_wired ? 1 : 0");

    // Strength changes the actual native denoising timetable and must be
    // validated independently from configured sample steps.
    require_contains(contract, "expected_denoising_step_count(contract)");
    require_contains(contract, "backend_denoising_strength(contract)");
    require_contains(contract, "static_cast<float>(steps) * (1.0f - str)");
    require_contains(contract, "native_effective[\"backendStrength\"]");
    require_contains(contract, "actualDenoisingStepCount");
    require_contains(contract, "configuredSampleSteps");

    // Batch generation owns and publishes every returned image.
    require_contains(contract, "kMaxBatchCount = 8");
    require_contains(contract, "free_generated_images(images, contract.batch_count)");
    require_contains(contract, "out[\"outputs\"].push_back");
    require_contains(contract, "actualDiffusionModelComputeCount");
    require_absent(source, "batch_count != 1");
    require_absent(source, "free(images[0].data)");

    // VAE overlap is the public API's ratio, never an integer pixel count.
    require_contains(contract, "gen.vae_tiling_params.target_overlap = contract.vae_tiling_enabled");
    require_contains(contract, "gen.vae_tiling_params.rel_size_x = contract.vae_tiling_enabled");
    require_contains(contract, "actual_vae_decode_tile_compute_success_count");
    require_contains(contract, "requestedTileSize");
    require_contains(contract, "plannedTileCount");
    require_contains(contract, "actual_vae_decode_tiling_invocation_count != contract.batch_count");
    require_contains(contract, "input_image_wired && summary.actual_vae_encode_tiling_invocation_count <= 0");
    require_absent(source, "vae_tile_overlap_pixels");
    require_absent(source, "{\"tileSize\", gen.vae_tiling_params.tile_size_x}");
    return 0;
}
