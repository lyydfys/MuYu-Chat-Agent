#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mca::image {

enum class SpatialTensorLayout {
    Nchw,
    Nhwc,
};

struct SpatialTensorShape {
    size_t batch = 0;
    size_t channels = 0;
    size_t height = 0;
    size_t width = 0;
    SpatialTensorLayout layout = SpatialTensorLayout::Nchw;

    size_t element_count() const;
};

bool resolve_spatial_tensor_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_channels,
        SpatialTensorShape* shape,
        std::string* error);

enum class SequenceFeatureLayout {
    SequenceMajor,
    FeatureMajor,
};

struct SequenceFeatureShape {
    size_t batch = 0;
    size_t sequence = 0;
    size_t features = 0;
    SequenceFeatureLayout layout = SequenceFeatureLayout::SequenceMajor;
};

bool resolve_sequence_feature_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_sequence,
        uint32_t expected_features,
        SequenceFeatureShape* shape,
        std::string* error);

bool reorder_sequence_feature_tensor(
        const float* canonical_sequence_major,
        size_t value_count,
        const SequenceFeatureShape& destination_shape,
        std::vector<float>* destination,
        std::string* error);

constexpr size_t kSdxlClipTokenCount = 77;
constexpr size_t kSdxlHiddenWidth = 2048;
constexpr size_t kSdxlPooledWidth = 1280;
constexpr size_t kSdxlTimeIdCount = 6;

constexpr size_t sdxl_conditioning_element_count(size_t branch_count = 2U) {
    return (branch_count == 1U || branch_count == 2U)
        ? branch_count * kSdxlClipTokenCount * kSdxlHiddenWidth +
            branch_count * kSdxlPooledWidth + kSdxlTimeIdCount
        : 0U;
}

bool validate_sdxl_conditioning_payload(
        size_t value_count,
        size_t branch_count,
        std::string* error);

bool validate_sdxl_conditioning_payload(size_t value_count, std::string* error);

struct VaeDecodeTile {
    size_t latent_x = 0;
    size_t latent_y = 0;
    size_t pixel_x = 0;
    size_t pixel_y = 0;
    size_t overlap_left = 0;
    size_t overlap_right = 0;
    size_t overlap_top = 0;
    size_t overlap_bottom = 0;
};

struct VaeDecodePlan {
    SpatialTensorShape source_latent;
    SpatialTensorShape vae_input;
    SpatialTensorShape vae_output;
    SpatialTensorShape final_output;
    size_t spatial_scale = 0;
    std::vector<VaeDecodeTile> tiles;

    bool tiled() const { return tiles.size() > 1U; }
};

struct UltraFixTile {
    size_t pixel_x = 0;
    size_t pixel_y = 0;
    size_t latent_x = 0;
    size_t latent_y = 0;
    size_t pixel_overlap_left = 0;
    size_t pixel_overlap_right = 0;
    size_t pixel_overlap_top = 0;
    size_t pixel_overlap_bottom = 0;
    size_t latent_overlap_left = 0;
    size_t latent_overlap_right = 0;
    size_t latent_overlap_top = 0;
    size_t latent_overlap_bottom = 0;
};

/** One topology-checked plan shared by VAE encode, UNet, and VAE decode. */
struct UltraFixTilePlan {
    SpatialTensorShape target_pixels;
    SpatialTensorShape encoder_input;
    SpatialTensorShape encoder_output;
    SpatialTensorShape full_latent;
    SpatialTensorShape unet_input;
    SpatialTensorShape unet_output;
    SpatialTensorShape vae_input;
    SpatialTensorShape vae_output;
    size_t spatial_scale = 0;
    size_t tile_size_pixels = 0;
    size_t latent_origin_alignment = 0;
    double overlap = 0.0;
    std::vector<UltraFixTile> tiles;
};

bool build_ultrafix_tile_plan(
        const std::vector<uint32_t>& encoder_input_dimensions,
        const std::vector<uint32_t>& encoder_output_dimensions,
        const std::vector<uint32_t>& unet_input_dimensions,
        const std::vector<uint32_t>& unet_output_dimensions,
        const std::vector<uint32_t>& vae_input_dimensions,
        const std::vector<uint32_t>& vae_output_dimensions,
        size_t target_width,
        size_t target_height,
        size_t tile_size_pixels,
        double overlap,
        size_t latent_origin_alignment,
        UltraFixTilePlan* plan,
        std::string* error);

bool blend_ultrafix_latent_tiles(
        const UltraFixTilePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error);

bool blend_ultrafix_encoder_tiles(
        const UltraFixTilePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error);

bool blend_ultrafix_pixel_tiles(
        const UltraFixTilePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error);

std::string ultrafix_tile_plan_descriptor(const UltraFixTilePlan& plan);

struct UltraFixNoiseLevel {
    double alpha = 1.0;
    double sigma = 0.0;
    double scheduler_input_scale = 1.0;
};

bool scale_ultrafix_inversion_model_input(
        const std::vector<float>& source,
        const UltraFixNoiseLevel& source_level,
        const UltraFixNoiseLevel& evaluation_level,
        std::vector<float>* model_input,
        std::string* error);

bool ultrafix_epsilon_inversion_step(
        const std::vector<float>& source,
        const std::vector<float>& epsilon,
        const UltraFixNoiseLevel& source_level,
        const UltraFixNoiseLevel& target_level,
        std::vector<float>* target,
        std::string* error);

struct UltraFixQualitySchedule {
    double noise_injection_fraction = 0.0;
    double structure_guidance_weight = 0.0;
    bool evaluate_noise_injection = false;
    bool evaluate_structure_guidance = false;
};

/**
 * Resolves the bounded detail/structure schedule for one global refinement step.
 * The training timestep is clamped to [0, 1000]; the final step is always inert.
 */
bool resolve_ultrafix_quality_schedule(
        double training_timestep,
        bool has_next_step,
        UltraFixQualitySchedule* schedule,
        std::string* error);

/** Reconstructs the one noise tensor that maps a clean latent to an inverted state. */
bool ultrafix_equivalent_noise(
        const std::vector<float>& clean,
        const std::vector<float>& inverted,
        const UltraFixNoiseLevel& inverted_level,
        std::vector<float>* equivalent_noise,
        std::string* error);

/** Rebuilds one point on the clean/noise trajectory for the supplied level. */
bool ultrafix_add_noise(
        const std::vector<float>& clean,
        const std::vector<float>& noise,
        const UltraFixNoiseLevel& level,
        std::vector<float>* noisy,
        std::string* error);

/**
 * Mixes a domain-separated deterministic Gaussian tensor into a model prediction by slerp.
 * The random fraction must be in [0, 0.5], keeping the model prediction dominant.
 */
bool ultrafix_inject_spherical_noise(
        const std::vector<float>& prediction,
        uint64_t request_seed,
        uint32_t refinement_step,
        double random_fraction,
        std::vector<float>* mixed_prediction,
        uint64_t* gaussian_checksum,
        uint64_t* mixed_checksum,
        std::string* error);

/** Applies a separable Gaussian low-frequency residual in canonical NCHW storage order. */
bool ultrafix_apply_structure_guidance(
        const std::vector<float>& current,
        const std::vector<float>& trajectory_reference,
        const SpatialTensorShape& shape,
        size_t blur_radius,
        double weight,
        std::vector<float>* guided,
        uint64_t* guided_checksum,
        std::string* error);

/** Stable non-cryptographic tensor evidence used only after finite-value validation. */
uint64_t ultrafix_tensor_checksum(const std::vector<float>& values);

/** Stable domain-separated folding for per-step UltraFix tensor evidence. */
uint64_t ultrafix_accumulate_checksum(
        uint64_t aggregate,
        uint32_t refinement_step,
        uint64_t value_checksum);

/** Canonical seed descriptor; callers hash this UTF-8 value with SHA-256. */
std::string ultrafix_noise_seed_descriptor(
        uint64_t request_seed,
        size_t denoise_step_count);

/** Resolves the Local Dream radius from one UNet tile edge in latent space. */
size_t ultrafix_structure_blur_radius(size_t tile_latent_edge);

/** Compatibility overload for callers that have not yet surfaced their tile edge. */
size_t ultrafix_structure_blur_radius(const SpatialTensorShape& shape);

struct UltraFixExecutionCounts {
    size_t vae_encoder_graph_executions = 0;
    size_t inversion_positive_unet_graph_executions = 0;
    size_t refinement_positive_unet_graph_executions = 0;
    size_t refinement_negative_unet_graph_executions = 0;
    size_t total_unet_graph_executions = 0;
    size_t vae_decoder_graph_executions = 0;
};

bool resolve_ultrafix_execution_counts(
        size_t tile_count,
        size_t inversion_steps,
        size_t denoise_steps,
        bool use_cfg,
        UltraFixExecutionCounts* counts,
        std::string* error);

bool build_vae_decode_plan(
        const std::vector<uint32_t>& source_latent_dimensions,
        const std::vector<uint32_t>& vae_input_dimensions,
        const std::vector<uint32_t>& vae_output_dimensions,
        size_t requested_width,
        size_t requested_height,
        VaeDecodePlan* plan,
        std::string* error);

bool copy_spatial_tile(
        const std::vector<float>& source,
        const SpatialTensorShape& source_shape,
        size_t source_x,
        size_t source_y,
        const SpatialTensorShape& destination_shape,
        std::vector<float>* destination,
        std::string* error);

bool blend_vae_decode_tiles(
        const VaeDecodePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error);

bool apply_classifier_free_guidance(
        const std::vector<float>& conditional,
        const std::vector<float>& unconditional,
        double cfg_scale,
        bool use_cfg,
        std::vector<float>* guided,
        std::string* error);

bool scale_vae_latents_in_place(
        std::vector<float>* latents,
        double scaling_factor,
        bool host_before_graph,
        std::string* error);

}  // namespace mca::image
