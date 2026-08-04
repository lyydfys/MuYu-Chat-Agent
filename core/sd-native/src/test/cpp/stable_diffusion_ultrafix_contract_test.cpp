#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>

namespace {

struct SourceFit {
    unsigned long long resized_width;
    unsigned long long resized_height;
    unsigned long long crop_left;
    unsigned long long crop_top;
};

bool strength_matches_steps(float strength, int total_steps, int inversion_steps) {
    const int denoise_begin = total_steps - inversion_steps;
    const double strength_begin =
        static_cast<double>(total_steps) * (1.0 - static_cast<double>(strength));
    const double tolerance = std::max(
        1.0e-6,
        static_cast<double>(total_steps) *
            static_cast<double>(std::numeric_limits<float>::epsilon()) * 4.0);
    return strength_begin + tolerance >= static_cast<double>(denoise_begin) &&
           strength_begin < static_cast<double>(denoise_begin + 1) + tolerance;
}

SourceFit cover_center(unsigned long long source_width,
                       unsigned long long source_height,
                       unsigned long long target_width,
                       unsigned long long target_height) {
    assert(source_width > 0 && source_height > 0);
    assert(source_width <= target_width && source_height <= target_height);
    unsigned long long resized_width = target_width;
    unsigned long long resized_height = target_height;
    if (target_width * source_height >= target_height * source_width) {
        resized_height = source_height * target_width / source_width;
    } else {
        resized_width = source_width * target_height / source_height;
    }
    assert(resized_width >= target_width && resized_height >= target_height);
    return {
        resized_width,
        resized_height,
        (resized_width - target_width) / 2u,
        (resized_height - target_height) / 2u,
    };
}

std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing UltraFix contract needle: %s\n", needle.c_str());
    }
    assert(source.find(needle) != std::string::npos);
}

void require_absent(const std::string& source, const std::string& needle) {
    if (source.find(needle) != std::string::npos) {
        std::fprintf(stderr, "forbidden UltraFix contract needle: %s\n", needle.c_str());
    }
    assert(source.find(needle) == std::string::npos);
}

bool quality_evidence_valid(uint64_t quality_step_count,
                            uint64_t noise_step_count,
                            uint64_t noise_checksum,
                            uint64_t structure_step_count,
                            uint64_t structure_checksum,
                            uint64_t trajectory_checksum) {
    const bool noise_consistent =
        noise_step_count <= quality_step_count &&
        ((noise_step_count == 0u) == (noise_checksum == 0u));
    const bool structure_consistent =
        structure_step_count <= quality_step_count &&
        ((structure_step_count == 0u) == (structure_checksum == 0u));
    const bool coverage_complete = quality_step_count == 0u
        ? trajectory_checksum == 0u
        : trajectory_checksum != 0u &&
              noise_step_count + structure_step_count >= quality_step_count;
    return noise_consistent && structure_consistent && coverage_complete;
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 6);
    const std::string public_header = read_text(argv[1]);
    const std::string engine = read_text(argv[2]);
    const std::string bridge = read_text(argv[3]);
    const std::string qnn_shared = read_text(argv[4]);
    const std::string qnn_split = read_text(argv[5]);

    // Source and target are distinct contracts. The source may be enlarged but
    // never shrunk; fitting is deterministic aspect-preserving center-cover.
    require_contains(public_header, "SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER");
    require_contains(public_header, "SD_ULTRAFIX_EXECUTION_EVIDENCE_VERSION 5u");
    require_contains(public_header, "sizeof(sd_ultrafix_execution_evidence_t) == 280u");
    require_contains(public_header, "quality_step_evaluation_count");
    require_contains(public_header, "trajectory_noise_checksum");
    require_contains(public_header, "source_resized_width");
    require_contains(public_header, "source_crop_left");
    require_contains(engine, "UltraFix does not shrink either source image axis");
    require_contains(engine, "target_width * source_height >= target_height * source_width");
    require_contains(engine, "source_height * target_width / source_width");
    require_contains(engine, "source_width * target_height / source_height");
    require_contains(engine, "sd::ops::interpolate(");
    require_contains(engine, "sd::ops::InterpolateMode::Bilinear");
    require_contains(engine, "const int denoise_begin = params->refinement_steps - params->inversion_steps");
    require_contains(engine, "strength_boundary_tolerance");
    require_absent(engine, "static_cast<float>(params->refinement_steps) *");
    require_contains(engine, "x + evidence->source_crop_left");
    require_contains(engine, "y + evidence->source_crop_top");
    require_contains(engine, "(y & 31u) == 0u && sd_should_cancel()");
    require_contains(engine, "ultrafix_inject_spherical_noise");
    require_contains(engine, "ultrafix_apply_structure_guidance");
    require_contains(engine, "ultrafix_add_noise");
    require_contains(engine, "quality_step_evaluation_count");
    require_contains(engine, "float model_timestep = std::numeric_limits<float>::quiet_NaN()");
    require_contains(engine, "static_cast<double>(prediction.model_timestep)");
    require_contains(engine, "params->tile_size / vae_scale");
    require_absent(engine, "normalized_noise * 1000.0");
    require_absent(
        engine,
        "params->init_image.width != static_cast<uint32_t>(params->width)");

    // The bridge pins and verifies the fit policy and leaves ordinary image
    // generation's once-per-step positive-branch assertion unchanged.
    require_contains(
        bridge,
        "ultrafix.source_fit = SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER");
    require_contains(bridge, "ULTRAFIX_SOURCE_SHRINK_UNSUPPORTED");
    require_contains(bridge, "bool source_fit_matches =");
    require_contains(bridge, "dimensions_match && source_fit_matches");
    require_contains(
        bridge,
        "if (!contract.ultrafix_enabled &&\n            actual_positive_execution_count != actual_timetable_count)");
    require_contains(bridge, "ultrafix_evidence.tiled_unet_tile_count");
    require_contains(bridge, "{\"sourceFit\", \"cover_center\"}");
    require_contains(bridge, "noiseInjectionSeedFingerprint");
    require_contains(bridge, "structureGuidanceChecksum");

    // Either thresholded quality action may have zero steps, but every evaluated
    // step must be covered and each checksum must agree with its physical count.
    for (const std::string* source : {&engine, &bridge, &qnn_shared, &qnn_split}) {
        require_contains(*source, "quality_noise_evidence_consistent");
        require_contains(*source, "quality_structure_evidence_consistent");
        require_contains(*source, "quality_coverage_complete");
        require_contains(*source, "quality_step_count");
    }
    require_contains(engine, "evidence->noise_injection_step_count <= quality_step_count");
    require_contains(bridge, "ultrafix_evidence.noise_injection_step_count <= quality_step_count");
    require_contains(qnn_shared, "ultra_fix_noise_injection_step_count <= quality_step_count");
    require_contains(qnn_split, "ultra_fix_noise_injection_step_count <= quality_step_count");

    assert(quality_evidence_valid(0u, 0u, 0u, 0u, 0u, 0u));
    assert(quality_evidence_valid(3u, 0u, 0u, 3u, 0x11u, 0x21u));
    assert(quality_evidence_valid(3u, 3u, 0x12u, 0u, 0u, 0x22u));
    assert(quality_evidence_valid(3u, 1u, 0x13u, 2u, 0x23u, 0x33u));
    assert(quality_evidence_valid(3u, 3u, 0x14u, 3u, 0x24u, 0x34u));
    assert(!quality_evidence_valid(3u, 0u, 0u, 0u, 0u, 0x31u));
    assert(!quality_evidence_valid(3u, 0u, 0x15u, 3u, 0x25u, 0x35u));
    assert(!quality_evidence_valid(3u, 1u, 0u, 2u, 0x26u, 0x36u));
    assert(!quality_evidence_valid(3u, 4u, 0x17u, 0u, 0u, 0x37u));
    assert(!quality_evidence_valid(3u, 1u, 0x18u, 2u, 0x28u, 0u));
    assert(!quality_evidence_valid(0u, 0u, 0u, 0u, 0u, 0x38u));
    assert(!quality_evidence_valid(0u, 1u, 0x19u, 0u, 0u, 0u));

    const SourceFit square = cover_center(512, 512, 1024, 1024);
    assert(square.resized_width == 1024 && square.resized_height == 1024 &&
           square.crop_left == 0 && square.crop_top == 0);
    const SourceFit landscape = cover_center(640, 480, 1024, 1024);
    assert(landscape.resized_width == 1365 && landscape.resized_height == 1024 &&
           landscape.crop_left == 170 && landscape.crop_top == 0);
    const SourceFit portrait = cover_center(480, 640, 1024, 1024);
    assert(portrait.resized_width == 1024 && portrait.resized_height == 1365 &&
           portrait.crop_left == 0 && portrait.crop_top == 170);
    const SourceFit wide_target = cover_center(512, 512, 1024, 768);
    assert(wide_target.resized_width == 1024 && wide_target.resized_height == 1024 &&
           wide_target.crop_left == 0 && wide_target.crop_top == 128);
    assert(strength_matches_steps(0.4f, 10, 4));
    assert(strength_matches_steps(0.2f, 5, 1));
    assert(strength_matches_steps(0.9f, 10, 10));
    assert(!strength_matches_steps(0.4f, 10, 3));
    return 0;
}
