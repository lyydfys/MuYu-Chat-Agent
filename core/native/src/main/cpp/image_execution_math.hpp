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

constexpr size_t sdxl_conditioning_element_count() {
    return 2U * kSdxlClipTokenCount * kSdxlHiddenWidth +
           2U * kSdxlPooledWidth + kSdxlTimeIdCount;
}

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
