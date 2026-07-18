#include "image_execution_math.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <sstream>
#include <utility>

namespace mca::image {
namespace {

bool fail(std::string* error, const std::string& message) {
    if (error != nullptr) *error = message;
    return false;
}

bool checked_product(size_t left, size_t right, size_t* product) {
    if (product == nullptr || left == 0U || right == 0U ||
        left > std::numeric_limits<size_t>::max() / right) {
        return false;
    }
    *product = left * right;
    return true;
}

size_t spatial_index(
        const SpatialTensorShape& shape,
        size_t batch,
        size_t channel,
        size_t y,
        size_t x) {
    if (shape.layout == SpatialTensorLayout::Nchw) {
        return ((batch * shape.channels + channel) * shape.height + y) * shape.width + x;
    }
    return ((batch * shape.height + y) * shape.width + x) * shape.channels + channel;
}

std::vector<size_t> axis_positions(size_t extent, size_t tile) {
    if (extent <= tile) return {0U};
    const size_t minimum_overlap = std::max<size_t>(1U, tile / 4U);
    const size_t maximum_stride = tile - minimum_overlap;
    const size_t remaining = extent - tile;
    const size_t interval_count =
            (remaining + maximum_stride - 1U) / maximum_stride;
    std::vector<size_t> positions(interval_count + 1U, 0U);
    const size_t base_stride = remaining / interval_count;
    const size_t remainder = remaining % interval_count;
    for (size_t index = 0; index < interval_count; ++index) {
        positions[index + 1U] = positions[index] + base_stride + (index < remainder ? 1U : 0U);
    }
    positions.back() = remaining;
    return positions;
}

float axis_blend_weight(
        size_t coordinate,
        size_t extent,
        size_t overlap_before,
        size_t overlap_after) {
    float weight = 1.0f;
    if (overlap_before > 0U && coordinate < overlap_before) {
        weight = std::min(
                weight,
                static_cast<float>(coordinate + 1U) /
                        static_cast<float>(overlap_before + 1U));
    }
    if (overlap_after > 0U && coordinate >= extent - overlap_after) {
        weight = std::min(
                weight,
                static_cast<float>(extent - coordinate) /
                        static_cast<float>(overlap_after + 1U));
    }
    return weight;
}

bool finite_values(const std::vector<float>& values) {
    return std::all_of(values.begin(), values.end(), [](float value) {
        return std::isfinite(value);
    });
}

}  // namespace

size_t SpatialTensorShape::element_count() const {
    size_t batch_channels = 0;
    size_t spatial = 0;
    size_t total = 0;
    if (!checked_product(batch, channels, &batch_channels) ||
        !checked_product(height, width, &spatial) ||
        !checked_product(batch_channels, spatial, &total)) {
        return 0U;
    }
    return total;
}

bool resolve_spatial_tensor_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_channels,
        SpatialTensorShape* shape,
        std::string* error) {
    if (shape == nullptr || error == nullptr) return false;
    if (dimensions.size() != 4U || expected_channels == 0U) {
        return fail(error, "Spatial tensor must be rank 4 with a concrete channel count.");
    }
    if (dimensions[0] != 1U) {
        return fail(error, "Image execution currently requires spatial tensor batch size 1.");
    }
    const bool nchw = dimensions[1] == expected_channels;
    const bool nhwc = dimensions[3] == expected_channels;
    if (nchw == nhwc) {
        return fail(error, nchw
                ? "Spatial tensor channel layout is ambiguous."
                : "Spatial tensor does not expose the required channel dimension.");
    }
    shape->batch = dimensions[0];
    shape->channels = expected_channels;
    shape->layout = nchw ? SpatialTensorLayout::Nchw : SpatialTensorLayout::Nhwc;
    shape->height = nchw ? dimensions[2] : dimensions[1];
    shape->width = nchw ? dimensions[3] : dimensions[2];
    if (shape->height == 0U || shape->width == 0U || shape->element_count() == 0U) {
        return fail(error, "Spatial tensor dimensions are empty or overflow the host size contract.");
    }
    error->clear();
    return true;
}

bool resolve_sequence_feature_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_sequence,
        uint32_t expected_features,
        SequenceFeatureShape* shape,
        std::string* error) {
    if (shape == nullptr || error == nullptr) return false;
    if (dimensions.size() != 3U || dimensions[0] != 1U ||
        expected_sequence == 0U || expected_features == 0U) {
        return fail(error, "Conditioning tensor must be rank 3 with batch size 1.");
    }
    const bool sequence_major =
            dimensions[1] == expected_sequence && dimensions[2] == expected_features;
    const bool feature_major =
            dimensions[1] == expected_features && dimensions[2] == expected_sequence;
    if (sequence_major == feature_major) {
        return fail(error, "Conditioning tensor does not have one unambiguous sequence/feature layout.");
    }
    shape->batch = 1U;
    shape->sequence = expected_sequence;
    shape->features = expected_features;
    shape->layout = sequence_major
            ? SequenceFeatureLayout::SequenceMajor
            : SequenceFeatureLayout::FeatureMajor;
    error->clear();
    return true;
}

bool reorder_sequence_feature_tensor(
        const float* canonical_sequence_major,
        size_t value_count,
        const SequenceFeatureShape& destination_shape,
        std::vector<float>* destination,
        std::string* error) {
    if (canonical_sequence_major == nullptr || destination == nullptr || error == nullptr) {
        return false;
    }
    size_t expected = 0U;
    if (destination_shape.batch != 1U ||
        !checked_product(destination_shape.sequence, destination_shape.features, &expected) ||
        value_count != expected) {
        return fail(error, "Conditioning values do not match the resolved sequence/feature shape.");
    }
    destination->assign(expected, 0.0f);
    if (destination_shape.layout == SequenceFeatureLayout::SequenceMajor) {
        std::copy(
                canonical_sequence_major,
                canonical_sequence_major + expected,
                destination->begin());
    } else {
        for (size_t token = 0U; token < destination_shape.sequence; ++token) {
            for (size_t feature = 0U; feature < destination_shape.features; ++feature) {
                (*destination)[feature * destination_shape.sequence + token] =
                        canonical_sequence_major[token * destination_shape.features + feature];
            }
        }
    }
    if (!finite_values(*destination)) {
        return fail(error, "Conditioning tensor contains a non-finite value.");
    }
    error->clear();
    return true;
}

bool validate_sdxl_conditioning_payload(size_t value_count, std::string* error) {
    if (error == nullptr) return false;
    const size_t expected = sdxl_conditioning_element_count();
    if (value_count != expected) {
        std::ostringstream message;
        message << "SDXL conditioning payload must contain exactly " << expected
                << " float32 values, got " << value_count << ".";
        return fail(error, message.str());
    }
    error->clear();
    return true;
}

bool build_vae_decode_plan(
        const std::vector<uint32_t>& source_latent_dimensions,
        const std::vector<uint32_t>& vae_input_dimensions,
        const std::vector<uint32_t>& vae_output_dimensions,
        size_t requested_width,
        size_t requested_height,
        VaeDecodePlan* plan,
        std::string* error) {
    if (plan == nullptr || error == nullptr) return false;
    VaeDecodePlan resolved;
    if (!resolve_spatial_tensor_shape(
                source_latent_dimensions, 4U, &resolved.source_latent, error) ||
        !resolve_spatial_tensor_shape(
                vae_input_dimensions, 4U, &resolved.vae_input, error) ||
        !resolve_spatial_tensor_shape(
                vae_output_dimensions, 3U, &resolved.vae_output, error)) {
        return false;
    }
    if (requested_width == 0U || requested_height == 0U ||
        resolved.vae_output.width % resolved.vae_input.width != 0U ||
        resolved.vae_output.height % resolved.vae_input.height != 0U) {
        return fail(error, "VAE input/output spatial shapes do not define an integer decode scale.");
    }
    const size_t width_scale = resolved.vae_output.width / resolved.vae_input.width;
    const size_t height_scale = resolved.vae_output.height / resolved.vae_input.height;
    if (width_scale == 0U || width_scale != height_scale) {
        return fail(error, "VAE decoder must use one non-zero spatial scale on both axes.");
    }
    resolved.spatial_scale = width_scale;
    if (resolved.source_latent.width < resolved.vae_input.width ||
        resolved.source_latent.height < resolved.vae_input.height) {
        return fail(error, "VAE fixed latent tile is larger than the denoised latent.");
    }
    if (resolved.source_latent.width > std::numeric_limits<size_t>::max() / width_scale ||
        resolved.source_latent.height > std::numeric_limits<size_t>::max() / height_scale ||
        resolved.source_latent.width * width_scale != requested_width ||
        resolved.source_latent.height * height_scale != requested_height) {
        return fail(error, "Resolved output size does not match the denoised latent and VAE scale.");
    }

    resolved.final_output = SpatialTensorShape{
            1U,
            3U,
            requested_height,
            requested_width,
            SpatialTensorLayout::Nchw,
    };
    const auto x_positions = axis_positions(resolved.source_latent.width, resolved.vae_input.width);
    const auto y_positions = axis_positions(resolved.source_latent.height, resolved.vae_input.height);
    resolved.tiles.reserve(x_positions.size() * y_positions.size());
    for (size_t y_index = 0U; y_index < y_positions.size(); ++y_index) {
        for (size_t x_index = 0U; x_index < x_positions.size(); ++x_index) {
            const size_t latent_x = x_positions[x_index];
            const size_t latent_y = y_positions[y_index];
            const size_t left = x_index == 0U
                    ? 0U
                    : x_positions[x_index - 1U] + resolved.vae_input.width - latent_x;
            const size_t right = x_index + 1U == x_positions.size()
                    ? 0U
                    : latent_x + resolved.vae_input.width - x_positions[x_index + 1U];
            const size_t top = y_index == 0U
                    ? 0U
                    : y_positions[y_index - 1U] + resolved.vae_input.height - latent_y;
            const size_t bottom = y_index + 1U == y_positions.size()
                    ? 0U
                    : latent_y + resolved.vae_input.height - y_positions[y_index + 1U];
            resolved.tiles.push_back(VaeDecodeTile{
                    latent_x,
                    latent_y,
                    latent_x * width_scale,
                    latent_y * height_scale,
                    left * width_scale,
                    right * width_scale,
                    top * height_scale,
                    bottom * height_scale,
            });
        }
    }
    if (resolved.tiles.empty()) {
        return fail(error, "VAE decode plan did not produce any tiles.");
    }
    *plan = std::move(resolved);
    error->clear();
    return true;
}

bool copy_spatial_tile(
        const std::vector<float>& source,
        const SpatialTensorShape& source_shape,
        size_t source_x,
        size_t source_y,
        const SpatialTensorShape& destination_shape,
        std::vector<float>* destination,
        std::string* error) {
    if (destination == nullptr || error == nullptr) return false;
    if (source_shape.batch != 1U || destination_shape.batch != 1U ||
        source_shape.channels != destination_shape.channels ||
        source.size() != source_shape.element_count() ||
        destination_shape.element_count() == 0U ||
        source_x + destination_shape.width > source_shape.width ||
        source_y + destination_shape.height > source_shape.height) {
        return fail(error, "Spatial tile copy does not fit the source and destination tensor contracts.");
    }
    destination->assign(destination_shape.element_count(), 0.0f);
    for (size_t channel = 0U; channel < source_shape.channels; ++channel) {
        for (size_t y = 0U; y < destination_shape.height; ++y) {
            for (size_t x = 0U; x < destination_shape.width; ++x) {
                (*destination)[spatial_index(destination_shape, 0U, channel, y, x)] =
                        source[spatial_index(
                                source_shape,
                                0U,
                                channel,
                                source_y + y,
                                source_x + x)];
            }
        }
    }
    if (!finite_values(*destination)) {
        return fail(error, "Spatial tile contains a non-finite value.");
    }
    error->clear();
    return true;
}

bool blend_vae_decode_tiles(
        const VaeDecodePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error) {
    if (output_nchw == nullptr || error == nullptr) return false;
    if (tile_outputs.size() != plan.tiles.size() || plan.final_output.element_count() == 0U ||
        plan.final_output.layout != SpatialTensorLayout::Nchw ||
        plan.vae_output.channels != plan.final_output.channels) {
        return fail(error, "Decoded VAE tiles do not match the decode plan.");
    }
    std::vector<double> accumulated(plan.final_output.element_count(), 0.0);
    std::vector<double> weights(plan.final_output.height * plan.final_output.width, 0.0);
    for (size_t tile_index = 0U; tile_index < plan.tiles.size(); ++tile_index) {
        const auto& tile = plan.tiles[tile_index];
        const auto& values = tile_outputs[tile_index];
        if (values.size() != plan.vae_output.element_count() || !finite_values(values) ||
            tile.pixel_x + plan.vae_output.width > plan.final_output.width ||
            tile.pixel_y + plan.vae_output.height > plan.final_output.height) {
            return fail(error, "A decoded VAE tile violates the output tensor contract.");
        }
        for (size_t y = 0U; y < plan.vae_output.height; ++y) {
            const float y_weight = axis_blend_weight(
                    y,
                    plan.vae_output.height,
                    tile.overlap_top,
                    tile.overlap_bottom);
            for (size_t x = 0U; x < plan.vae_output.width; ++x) {
                const float x_weight = axis_blend_weight(
                        x,
                        plan.vae_output.width,
                        tile.overlap_left,
                        tile.overlap_right);
                const double weight = static_cast<double>(x_weight) * y_weight;
                const size_t destination_y = tile.pixel_y + y;
                const size_t destination_x = tile.pixel_x + x;
                const size_t weight_index = destination_y * plan.final_output.width + destination_x;
                weights[weight_index] += weight;
                for (size_t channel = 0U; channel < plan.final_output.channels; ++channel) {
                    accumulated[spatial_index(
                            plan.final_output,
                            0U,
                            channel,
                            destination_y,
                            destination_x)] +=
                            static_cast<double>(values[spatial_index(
                                    plan.vae_output,
                                    0U,
                                    channel,
                                    y,
                                    x)]) * weight;
                }
            }
        }
    }
    output_nchw->assign(plan.final_output.element_count(), 0.0f);
    for (size_t y = 0U; y < plan.final_output.height; ++y) {
        for (size_t x = 0U; x < plan.final_output.width; ++x) {
            const double weight = weights[y * plan.final_output.width + x];
            if (!(weight > 0.0) || !std::isfinite(weight)) {
                return fail(error, "VAE tile blending left an uncovered output pixel.");
            }
            for (size_t channel = 0U; channel < plan.final_output.channels; ++channel) {
                const size_t index = spatial_index(plan.final_output, 0U, channel, y, x);
                (*output_nchw)[index] = static_cast<float>(accumulated[index] / weight);
            }
        }
    }
    if (!finite_values(*output_nchw)) {
        return fail(error, "VAE tile blending produced a non-finite output.");
    }
    error->clear();
    return true;
}

bool apply_classifier_free_guidance(
        const std::vector<float>& conditional,
        const std::vector<float>& unconditional,
        double cfg_scale,
        bool use_cfg,
        std::vector<float>* guided,
        std::string* error) {
    if (guided == nullptr || error == nullptr) return false;
    if (conditional.empty() || !finite_values(conditional) ||
        !std::isfinite(cfg_scale) || cfg_scale < 0.0 || cfg_scale > 30.0) {
        return fail(error, "CFG inputs must be finite and use a scale in [0, 30].");
    }
    if (!use_cfg) {
        if (std::fabs(cfg_scale - 1.0) > 1.0e-12) {
            return fail(error, "Conditional-only execution requires cfgScale=1.");
        }
        *guided = conditional;
        error->clear();
        return true;
    }
    if (unconditional.size() != conditional.size() || !finite_values(unconditional)) {
        return fail(error, "CFG conditional and unconditional tensors must have identical finite shapes.");
    }
    guided->resize(conditional.size());
    for (size_t index = 0U; index < conditional.size(); ++index) {
        (*guided)[index] = static_cast<float>(
                unconditional[index] +
                cfg_scale * (conditional[index] - unconditional[index]));
    }
    if (!finite_values(*guided)) {
        return fail(error, "CFG produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

bool scale_vae_latents_in_place(
        std::vector<float>* latents,
        double scaling_factor,
        bool host_before_graph,
        std::string* error) {
    if (latents == nullptr || error == nullptr || latents->empty() ||
        !std::isfinite(scaling_factor) || scaling_factor <= 0.0 ||
        !finite_values(*latents)) {
        return fail(error, "VAE latent scaling requires finite values and a positive factor.");
    }
    if (host_before_graph) {
        const double multiplier = 1.0 / scaling_factor;
        for (float& value : *latents) {
            value = static_cast<float>(value * multiplier);
        }
        if (!finite_values(*latents)) {
            return fail(error, "VAE latent scaling produced a non-finite value.");
        }
    }
    error->clear();
    return true;
}

}  // namespace mca::image
