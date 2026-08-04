#include "image_execution_math.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iomanip>
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

std::vector<size_t> aligned_axis_positions(
        size_t extent,
        size_t tile,
        double overlap,
        size_t alignment) {
    std::vector<size_t> positions;
    if (extent == 0U || tile == 0U || tile > extent || alignment == 0U ||
        extent % alignment != 0U || tile % alignment != 0U ||
        !std::isfinite(overlap) || overlap < 0.0 || overlap > 0.5) {
        return positions;
    }
    if (extent == tile) {
        positions.push_back(0U);
        return positions;
    }
    // Match Local Dream's grid: first determine the minimum number of tiles
    // from the requested overlap, then spread the edge-to-edge distance evenly.
    // Alignment is applied after spreading so every graph origin remains in the
    // same UNet feature-grid phase; the final tile is always clamped to the edge.
    const double raw_overlap = std::floor(static_cast<double>(tile) * overlap);
    if (!(raw_overlap >= 0.0) ||
        raw_overlap >= static_cast<double>(tile)) {
        return positions;
    }
    const size_t minimum_overlap = static_cast<size_t>(raw_overlap);
    const size_t maximum_stride = tile - minimum_overlap;
    const size_t final_start = extent - tile;
    const size_t interval_count = final_start / maximum_stride +
            (final_start % maximum_stride == 0U ? 0U : 1U);
    if (interval_count == 0U) return positions;
    const size_t base_stride = final_start / interval_count;
    const size_t remainder = final_start % interval_count;
    std::vector<size_t> unaligned;
    unaligned.reserve(interval_count + 1U);
    unaligned.push_back(0U);
    size_t cursor = 0U;
    for (size_t index = 0U; index < interval_count; ++index) {
        const size_t step = base_stride + (index < remainder ? 1U : 0U);
        if (step == 0U || cursor > std::numeric_limits<size_t>::max() - step) {
            return positions;
        }
        cursor += step;
        unaligned.push_back(cursor);
    }
    unaligned.back() = final_start;
    positions.reserve(unaligned.size());
    for (size_t index = 0U; index < unaligned.size(); ++index) {
        const size_t candidate = index + 1U == unaligned.size()
                ? final_start
                : (unaligned[index] / alignment) * alignment;
        if (positions.empty() || candidate > positions.back()) {
            positions.push_back(candidate);
        }
    }
    if (positions.empty() || positions.front() != 0U || positions.back() != final_start) {
        positions.clear();
    }
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

float ultrafix_axis_blend_weight(
        size_t coordinate,
        size_t extent,
        size_t overlap_before,
        size_t overlap_after) {
    float weight = 1.0f;
    // Local Dream fades only across the inner half of each overlap and uses a
    // linear ramp. Keeping the outer half at full weight avoids the broad
    // smootherstep blur that the old MCA implementation introduced.
    const size_t fade_before = overlap_before / 2U;
    const size_t fade_after = overlap_after / 2U;
    if (fade_before > 0U && coordinate < fade_before) {
        weight = std::min(
                weight,
                static_cast<float>(coordinate + 1U) /
                    static_cast<float>(fade_before));
    }
    if (fade_after > 0U && coordinate >= extent - fade_after) {
        weight = std::min(
                weight,
                static_cast<float>(extent - coordinate) /
                    static_cast<float>(fade_after));
    }
    return weight;
}

bool finite_values(const std::vector<float>& values) {
    return std::all_of(values.begin(), values.end(), [](float value) {
        return std::isfinite(value);
    });
}

bool valid_ultrafix_noise_level(const UltraFixNoiseLevel& level) {
    return std::isfinite(level.alpha) && level.alpha > 0.0 &&
        std::isfinite(level.sigma) && level.sigma >= 0.0 &&
        std::isfinite(level.scheduler_input_scale) &&
        level.scheduler_input_scale > 0.0;
}

uint64_t splitmix64(uint64_t* state) {
    *state += 0x9e3779b97f4a7c15ULL;
    uint64_t value = *state;
    value = (value ^ (value >> 30U)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27U)) * 0x94d049bb133111ebULL;
    return value ^ (value >> 31U);
}

double deterministic_open_unit(uint64_t* state) {
    constexpr double kUnit53 = 1.0 / 9007199254740992.0;
    const uint64_t bits = splitmix64(state) >> 11U;
    return (static_cast<double>(bits) + 0.5) * kUnit53;
}

bool deterministic_gaussian_tensor(
        size_t count,
        uint64_t request_seed,
        uint32_t refinement_step,
        std::vector<float>* values,
        std::string* error) {
    if (values == nullptr || error == nullptr || count == 0U) {
        return fail(error, "UltraFix deterministic noise requires a non-empty destination.");
    }
    uint64_t state = request_seed ^ 0x554c545241464958ULL ^
        (static_cast<uint64_t>(refinement_step) * 0xd1342543de82ef95ULL);
    values->resize(count);
    constexpr double kTwoPi = 6.283185307179586476925286766559;
    for (size_t index = 0U; index < count; index += 2U) {
        const double first = deterministic_open_unit(&state);
        const double second = deterministic_open_unit(&state);
        const double radius = std::sqrt(-2.0 * std::log(first));
        const double angle = kTwoPi * second;
        (*values)[index] = static_cast<float>(radius * std::cos(angle));
        if (index + 1U < count) {
            (*values)[index + 1U] = static_cast<float>(radius * std::sin(angle));
        }
    }
    if (!finite_values(*values)) {
        values->clear();
        return fail(error, "UltraFix deterministic noise produced a non-finite value.");
    }
    error->clear();
    return true;
}

bool gaussian_blur_nchw(
        const std::vector<float>& source,
        const SpatialTensorShape& shape,
        size_t radius,
        std::vector<float>* blurred,
        std::string* error) {
    if (blurred == nullptr || error == nullptr || shape.batch != 1U ||
        shape.layout != SpatialTensorLayout::Nchw || radius == 0U || radius > 64U ||
        source.size() != shape.element_count() || !finite_values(source)) {
        return fail(error, "UltraFix blur requires one finite NCHW tensor and a bounded radius.");
    }
    const double sigma = std::max(0.5, static_cast<double>(radius) / 2.0);
    std::vector<double> kernel(radius * 2U + 1U, 0.0);
    double kernel_sum = 0.0;
    for (size_t index = 0U; index < kernel.size(); ++index) {
        const double offset = static_cast<double>(index) - static_cast<double>(radius);
        kernel[index] = std::exp(-(offset * offset) / (2.0 * sigma * sigma));
        kernel_sum += kernel[index];
    }
    if (!(kernel_sum > 0.0) || !std::isfinite(kernel_sum)) {
        return fail(error, "UltraFix blur kernel is invalid.");
    }
    for (double& value : kernel) value /= kernel_sum;

    std::vector<float> horizontal(source.size(), 0.0f);
    blurred->assign(source.size(), 0.0f);
    for (size_t channel = 0U; channel < shape.channels; ++channel) {
        for (size_t y = 0U; y < shape.height; ++y) {
            for (size_t x = 0U; x < shape.width; ++x) {
                double sum = 0.0;
                for (size_t kernel_index = 0U; kernel_index < kernel.size(); ++kernel_index) {
                    const long long offset = static_cast<long long>(kernel_index) -
                        static_cast<long long>(radius);
                    const size_t sample_x = static_cast<size_t>(std::clamp<long long>(
                        static_cast<long long>(x) + offset,
                        0LL,
                        static_cast<long long>(shape.width - 1U)));
                    sum += static_cast<double>(source[spatial_index(
                        shape, 0U, channel, y, sample_x)]) * kernel[kernel_index];
                }
                horizontal[spatial_index(shape, 0U, channel, y, x)] =
                    static_cast<float>(sum);
            }
        }
        for (size_t y = 0U; y < shape.height; ++y) {
            for (size_t x = 0U; x < shape.width; ++x) {
                double sum = 0.0;
                for (size_t kernel_index = 0U; kernel_index < kernel.size(); ++kernel_index) {
                    const long long offset = static_cast<long long>(kernel_index) -
                        static_cast<long long>(radius);
                    const size_t sample_y = static_cast<size_t>(std::clamp<long long>(
                        static_cast<long long>(y) + offset,
                        0LL,
                        static_cast<long long>(shape.height - 1U)));
                    sum += static_cast<double>(horizontal[spatial_index(
                        shape, 0U, channel, sample_y, x)]) * kernel[kernel_index];
                }
                (*blurred)[spatial_index(shape, 0U, channel, y, x)] =
                    static_cast<float>(sum);
            }
        }
    }
    if (!finite_values(*blurred)) {
        blurred->clear();
        return fail(error, "UltraFix blur produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

bool blend_ultrafix_tiles(
        const UltraFixTilePlan& plan,
        const SpatialTensorShape& tile_shape,
        const SpatialTensorShape& output_shape,
        bool pixel_space,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error) {
    if (output_nchw == nullptr || error == nullptr || plan.tiles.empty() ||
        tile_outputs.size() != plan.tiles.size() || tile_shape.batch != 1U ||
        output_shape.batch != 1U || tile_shape.channels != output_shape.channels ||
        output_shape.layout != SpatialTensorLayout::Nchw ||
        tile_shape.element_count() == 0U || output_shape.element_count() == 0U) {
        return fail(error, "UltraFix tile outputs do not match the resolved plan.");
    }
    std::vector<double> accumulated(output_shape.element_count(), 0.0);
    std::vector<double> weights(output_shape.height * output_shape.width, 0.0);
    for (size_t tile_index = 0U; tile_index < plan.tiles.size(); ++tile_index) {
        const auto& tile = plan.tiles[tile_index];
        const auto& values = tile_outputs[tile_index];
        const size_t target_x = pixel_space ? tile.pixel_x : tile.latent_x;
        const size_t target_y = pixel_space ? tile.pixel_y : tile.latent_y;
        const size_t overlap_left = pixel_space
                ? tile.pixel_overlap_left : tile.latent_overlap_left;
        const size_t overlap_right = pixel_space
                ? tile.pixel_overlap_right : tile.latent_overlap_right;
        const size_t overlap_top = pixel_space
                ? tile.pixel_overlap_top : tile.latent_overlap_top;
        const size_t overlap_bottom = pixel_space
                ? tile.pixel_overlap_bottom : tile.latent_overlap_bottom;
        if (values.size() != tile_shape.element_count() || !finite_values(values) ||
            target_x + tile_shape.width > output_shape.width ||
            target_y + tile_shape.height > output_shape.height) {
            return fail(error, "An UltraFix tile violates the output tensor contract.");
        }
        for (size_t y = 0U; y < tile_shape.height; ++y) {
            const float y_weight = ultrafix_axis_blend_weight(
                    y, tile_shape.height, overlap_top, overlap_bottom);
            for (size_t x = 0U; x < tile_shape.width; ++x) {
                const float x_weight = ultrafix_axis_blend_weight(
                        x, tile_shape.width, overlap_left, overlap_right);
                const double weight = static_cast<double>(x_weight) * y_weight;
                const size_t output_y = target_y + y;
                const size_t output_x = target_x + x;
                weights[output_y * output_shape.width + output_x] += weight;
                for (size_t channel = 0U; channel < output_shape.channels; ++channel) {
                    accumulated[spatial_index(
                            output_shape, 0U, channel, output_y, output_x)] +=
                        static_cast<double>(values[spatial_index(
                                tile_shape, 0U, channel, y, x)]) * weight;
                }
            }
        }
    }
    output_nchw->assign(output_shape.element_count(), 0.0f);
    for (size_t y = 0U; y < output_shape.height; ++y) {
        for (size_t x = 0U; x < output_shape.width; ++x) {
            const double weight = weights[y * output_shape.width + x];
            if (!(weight > 0.0) || !std::isfinite(weight)) {
                output_nchw->clear();
                return fail(error, "UltraFix blending left an uncovered output coordinate.");
            }
            for (size_t channel = 0U; channel < output_shape.channels; ++channel) {
                const size_t index = spatial_index(output_shape, 0U, channel, y, x);
                (*output_nchw)[index] = static_cast<float>(accumulated[index] / weight);
            }
        }
    }
    if (!finite_values(*output_nchw)) {
        output_nchw->clear();
        return fail(error, "UltraFix blending produced a non-finite tensor.");
    }
    error->clear();
    return true;
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

bool validate_sdxl_conditioning_payload(
        size_t value_count,
        size_t branch_count,
        std::string* error) {
    if (error == nullptr) return false;
    const size_t expected = sdxl_conditioning_element_count(branch_count);
    if (expected == 0U) {
        return fail(error, "SDXL conditioning payload must contain one or two CLIP branches.");
    }
    if (value_count != expected) {
        std::ostringstream message;
        message << "SDXL conditioning payload with " << branch_count
                << " branch(es) must contain exactly " << expected
                << " float32 values, got " << value_count << ".";
        return fail(error, message.str());
    }
    error->clear();
    return true;
}

bool validate_sdxl_conditioning_payload(size_t value_count, std::string* error) {
    return validate_sdxl_conditioning_payload(value_count, 2U, error);
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
        std::string* error) {
    if (plan == nullptr || error == nullptr) return false;
    UltraFixTilePlan resolved;
    if (!resolve_spatial_tensor_shape(
                encoder_input_dimensions, 3U, &resolved.encoder_input, error) ||
        !resolve_spatial_tensor_shape(
                encoder_output_dimensions, 4U, &resolved.encoder_output, error) ||
        !resolve_spatial_tensor_shape(
                unet_input_dimensions, 4U, &resolved.unet_input, error) ||
        !resolve_spatial_tensor_shape(
                unet_output_dimensions, 4U, &resolved.unet_output, error) ||
        !resolve_spatial_tensor_shape(
                vae_input_dimensions, 4U, &resolved.vae_input, error) ||
        !resolve_spatial_tensor_shape(
                vae_output_dimensions, 3U, &resolved.vae_output, error)) {
        return false;
    }
    if (target_width == 0U || target_height == 0U || tile_size_pixels == 0U ||
        latent_origin_alignment == 0U || !std::isfinite(overlap) ||
        overlap < 0.0 || overlap > 0.5) {
        return fail(error, "UltraFix dimensions, overlap, and alignment must be bounded and finite.");
    }
    if (resolved.encoder_input.width != tile_size_pixels ||
        resolved.encoder_input.height != tile_size_pixels ||
        resolved.vae_output.width != tile_size_pixels ||
        resolved.vae_output.height != tile_size_pixels ||
        resolved.encoder_input.width % resolved.encoder_output.width != 0U ||
        resolved.encoder_input.height % resolved.encoder_output.height != 0U) {
        return fail(error, "UltraFix tile size must exactly match the fixed VAE encoder and decoder canvas.");
    }
    const size_t width_scale =
        resolved.encoder_input.width / resolved.encoder_output.width;
    const size_t height_scale =
        resolved.encoder_input.height / resolved.encoder_output.height;
    if (width_scale == 0U || width_scale != height_scale ||
        resolved.vae_output.width % resolved.vae_input.width != 0U ||
        resolved.vae_output.height % resolved.vae_input.height != 0U ||
        resolved.vae_output.width / resolved.vae_input.width != width_scale ||
        resolved.vae_output.height / resolved.vae_input.height != height_scale) {
        return fail(error, "UltraFix VAE encode/decode graphs do not expose one common spatial scale.");
    }
    resolved.spatial_scale = width_scale;
    const auto same_spatial = [](const SpatialTensorShape& left,
                                 const SpatialTensorShape& right) {
        return left.batch == right.batch && left.channels == right.channels &&
            left.height == right.height && left.width == right.width;
    };
    if (!same_spatial(resolved.encoder_output, resolved.unet_input) ||
        !same_spatial(resolved.encoder_output, resolved.unet_output) ||
        !same_spatial(resolved.encoder_output, resolved.vae_input)) {
        return fail(error, "UltraFix encoder, UNet, and decoder latent tiles do not match.");
    }
    const size_t pixel_alignment = latent_origin_alignment * resolved.spatial_scale;
    if (pixel_alignment / resolved.spatial_scale != latent_origin_alignment ||
        target_width < tile_size_pixels || target_height < tile_size_pixels ||
        target_width % pixel_alignment != 0U ||
        target_height % pixel_alignment != 0U ||
        tile_size_pixels % pixel_alignment != 0U) {
        return fail(error, "UltraFix target and tile dimensions do not align to the UNet origin grid.");
    }
    resolved.tile_size_pixels = tile_size_pixels;
    resolved.latent_origin_alignment = latent_origin_alignment;
    resolved.overlap = overlap;
    resolved.target_pixels = SpatialTensorShape{
        1U, 3U, target_height, target_width, SpatialTensorLayout::Nchw};
    resolved.full_latent = SpatialTensorShape{
        1U,
        4U,
        target_height / resolved.spatial_scale,
        target_width / resolved.spatial_scale,
        SpatialTensorLayout::Nchw,
    };
    if (resolved.target_pixels.element_count() == 0U ||
        resolved.full_latent.element_count() == 0U) {
        return fail(error, "UltraFix target tensor dimensions overflow the host size contract.");
    }
    const auto x_positions = aligned_axis_positions(
        resolved.full_latent.width,
        resolved.unet_input.width,
        overlap,
        latent_origin_alignment);
    const auto y_positions = aligned_axis_positions(
        resolved.full_latent.height,
        resolved.unet_input.height,
        overlap,
        latent_origin_alignment);
    if (x_positions.empty() || y_positions.empty() ||
        x_positions.size() > std::numeric_limits<size_t>::max() / y_positions.size()) {
        return fail(error, "UltraFix could not build a complete aligned tile grid.");
    }
    resolved.tiles.reserve(x_positions.size() * y_positions.size());
    for (size_t y_index = 0U; y_index < y_positions.size(); ++y_index) {
        for (size_t x_index = 0U; x_index < x_positions.size(); ++x_index) {
            const size_t latent_x = x_positions[x_index];
            const size_t latent_y = y_positions[y_index];
            const size_t left = x_index == 0U ? 0U :
                x_positions[x_index - 1U] + resolved.unet_input.width - latent_x;
            const size_t right = x_index + 1U == x_positions.size() ? 0U :
                latent_x + resolved.unet_input.width - x_positions[x_index + 1U];
            const size_t top = y_index == 0U ? 0U :
                y_positions[y_index - 1U] + resolved.unet_input.height - latent_y;
            const size_t bottom = y_index + 1U == y_positions.size() ? 0U :
                latent_y + resolved.unet_input.height - y_positions[y_index + 1U];
            if (left >= resolved.unet_input.width || right >= resolved.unet_input.width ||
                top >= resolved.unet_input.height || bottom >= resolved.unet_input.height) {
                return fail(error, "UltraFix tile overlap consumed an entire latent tile.");
            }
            resolved.tiles.push_back(UltraFixTile{
                latent_x * resolved.spatial_scale,
                latent_y * resolved.spatial_scale,
                latent_x,
                latent_y,
                left * resolved.spatial_scale,
                right * resolved.spatial_scale,
                top * resolved.spatial_scale,
                bottom * resolved.spatial_scale,
                left,
                right,
                top,
                bottom,
            });
        }
    }
    *plan = std::move(resolved);
    error->clear();
    return true;
}

bool blend_ultrafix_latent_tiles(
        const UltraFixTilePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error) {
    return blend_ultrafix_tiles(
        plan, plan.unet_output, plan.full_latent, false, tile_outputs, output_nchw, error);
}

bool blend_ultrafix_encoder_tiles(
        const UltraFixTilePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error) {
    return blend_ultrafix_tiles(
        plan, plan.encoder_output, plan.full_latent, false, tile_outputs, output_nchw, error);
}

bool blend_ultrafix_pixel_tiles(
        const UltraFixTilePlan& plan,
        const std::vector<std::vector<float>>& tile_outputs,
        std::vector<float>* output_nchw,
        std::string* error) {
    return blend_ultrafix_tiles(
        plan, plan.vae_output, plan.target_pixels, true, tile_outputs, output_nchw, error);
}

std::string ultrafix_tile_plan_descriptor(const UltraFixTilePlan& plan) {
    uint64_t overlap_bits = 0U;
    static_assert(sizeof(overlap_bits) == sizeof(plan.overlap));
    std::memcpy(&overlap_bits, &plan.overlap, sizeof(overlap_bits));
    std::ostringstream overlap;
    overlap << std::hex << std::nouppercase << std::setfill('0')
            << std::setw(16) << overlap_bits;
    std::ostringstream out;
    out << "ultrafix-tile-plan-v2|target=" << plan.target_pixels.width << "x"
        << plan.target_pixels.height << "|tile=" << plan.tile_size_pixels
        << "|scale=" << plan.spatial_scale << "|alignment="
        << plan.latent_origin_alignment << "|overlapBits=" << overlap.str()
        << "|tiles=" << plan.tiles.size();
    for (const auto& tile : plan.tiles) {
        out << "|" << tile.pixel_x << "," << tile.pixel_y << ","
            << tile.latent_x << "," << tile.latent_y << ","
            << tile.pixel_overlap_left << "," << tile.pixel_overlap_right << ","
            << tile.pixel_overlap_top << "," << tile.pixel_overlap_bottom << ","
            << tile.latent_overlap_left << "," << tile.latent_overlap_right << ","
            << tile.latent_overlap_top << "," << tile.latent_overlap_bottom;
    }
    return out.str();
}

bool scale_ultrafix_inversion_model_input(
        const std::vector<float>& source,
        const UltraFixNoiseLevel& source_level,
        const UltraFixNoiseLevel& evaluation_level,
        std::vector<float>* model_input,
        std::string* error) {
    if (model_input == nullptr || error == nullptr || source.empty() ||
        !finite_values(source) || !valid_ultrafix_noise_level(source_level) ||
        !valid_ultrafix_noise_level(evaluation_level)) {
        return fail(error, "UltraFix inversion model input requires finite source and noise levels.");
    }
    if (source_level.sigma > 0.0) {
        const double source_ratio = source_level.sigma / source_level.alpha;
        const double evaluation_ratio = evaluation_level.sigma / evaluation_level.alpha;
        if (std::fabs(source_ratio - evaluation_ratio) >
            std::max(1.0e-10, source_ratio * 1.0e-8)) {
            return fail(error, "UltraFix inversion may evaluate a different noise level only from the clean source.");
        }
    }
    const double scale = (evaluation_level.alpha / source_level.alpha) *
        evaluation_level.scheduler_input_scale;
    if (!std::isfinite(scale) || scale <= 0.0) {
        return fail(error, "UltraFix inversion model-input scale is invalid.");
    }
    model_input->resize(source.size());
    for (size_t index = 0U; index < source.size(); ++index) {
        (*model_input)[index] = static_cast<float>(source[index] * scale);
    }
    if (!finite_values(*model_input)) {
        model_input->clear();
        return fail(error, "UltraFix inversion model-input scaling produced non-finite values.");
    }
    error->clear();
    return true;
}

bool ultrafix_epsilon_inversion_step(
        const std::vector<float>& source,
        const std::vector<float>& epsilon,
        const UltraFixNoiseLevel& source_level,
        const UltraFixNoiseLevel& target_level,
        std::vector<float>* target,
        std::string* error) {
    if (target == nullptr || error == nullptr || source.empty() ||
        source.size() != epsilon.size() || !finite_values(source) ||
        !finite_values(epsilon) || !valid_ultrafix_noise_level(source_level) ||
        !valid_ultrafix_noise_level(target_level)) {
        return fail(error, "UltraFix epsilon inversion requires matching finite tensors and levels.");
    }
    const double source_ratio = source_level.sigma / source_level.alpha;
    const double target_ratio = target_level.sigma / target_level.alpha;
    if (!(target_ratio > source_ratio) || !std::isfinite(target_ratio)) {
        return fail(error, "UltraFix inversion target must be strictly noisier than its source.");
    }
    target->resize(source.size());
    for (size_t index = 0U; index < source.size(); ++index) {
        const double predicted_x0 =
            (static_cast<double>(source[index]) - source_level.sigma * epsilon[index]) /
            source_level.alpha;
        (*target)[index] = static_cast<float>(
            target_level.alpha * predicted_x0 + target_level.sigma * epsilon[index]);
    }
    if (!finite_values(*target)) {
        target->clear();
        return fail(error, "UltraFix epsilon inversion produced a non-finite target tensor.");
    }
    error->clear();
    return true;
}

bool resolve_ultrafix_quality_schedule(
        double training_timestep,
        bool has_next_step,
        UltraFixQualitySchedule* schedule,
        std::string* error) {
    if (schedule == nullptr || error == nullptr || !std::isfinite(training_timestep)) {
        return fail(error, "UltraFix quality scheduling requires a finite training timestep.");
    }
    UltraFixQualitySchedule resolved;
    if (has_next_step) {
        constexpr double kPi = 3.1415926535897932384626433832795;
        constexpr double kMaximumInjection = 0.08;
        constexpr double kStructureExponent = 0.3;
        const double timestep = std::clamp(training_timestep, 0.0, 1000.0);
        const double cosine = 0.5 *
            (1.0 + std::cos(kPi * (1000.0 - timestep) / 1000.0));
        const double injection = kMaximumInjection * (1.0 - cosine);
        const double structure = std::pow(std::max(0.0, cosine), kStructureExponent);
        resolved.noise_injection_fraction = injection;
        resolved.structure_guidance_weight = structure;
        resolved.evaluate_noise_injection = injection > 0.005;
        resolved.evaluate_structure_guidance = structure > 0.02;
    }
    *schedule = resolved;
    error->clear();
    return true;
}

bool ultrafix_equivalent_noise(
        const std::vector<float>& clean,
        const std::vector<float>& inverted,
        const UltraFixNoiseLevel& inverted_level,
        std::vector<float>* equivalent_noise,
        std::string* error) {
    if (equivalent_noise == nullptr || error == nullptr || clean.empty() ||
        clean.size() != inverted.size() || !finite_values(clean) ||
        !finite_values(inverted) || !valid_ultrafix_noise_level(inverted_level) ||
        !(inverted_level.sigma > 0.0)) {
        return fail(error, "UltraFix equivalent noise requires matching finite latents at a noisy level.");
    }
    equivalent_noise->resize(clean.size());
    for (size_t index = 0U; index < clean.size(); ++index) {
        (*equivalent_noise)[index] = static_cast<float>(
            (static_cast<double>(inverted[index]) -
                inverted_level.alpha * static_cast<double>(clean[index])) /
            inverted_level.sigma);
    }
    if (!finite_values(*equivalent_noise)) {
        equivalent_noise->clear();
        return fail(error, "UltraFix equivalent noise produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

bool ultrafix_add_noise(
        const std::vector<float>& clean,
        const std::vector<float>& noise,
        const UltraFixNoiseLevel& level,
        std::vector<float>* noisy,
        std::string* error) {
    if (noisy == nullptr || error == nullptr || clean.empty() ||
        clean.size() != noise.size() || !finite_values(clean) ||
        !finite_values(noise) || !valid_ultrafix_noise_level(level)) {
        return fail(error, "UltraFix trajectory reconstruction requires matching finite tensors and a valid noise level.");
    }
    noisy->resize(clean.size());
    for (size_t index = 0U; index < clean.size(); ++index) {
        (*noisy)[index] = static_cast<float>(
            level.alpha * static_cast<double>(clean[index]) +
            level.sigma * static_cast<double>(noise[index]));
    }
    if (!finite_values(*noisy)) {
        noisy->clear();
        return fail(error, "UltraFix trajectory reconstruction produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

uint64_t ultrafix_tensor_checksum(const std::vector<float>& values) {
    if (values.empty() || !finite_values(values)) return 0U;
    uint64_t hash = 1469598103934665603ULL;
    for (float value : values) {
        uint32_t bits = 0U;
        static_assert(sizeof(bits) == sizeof(value), "float checksum width changed");
        std::memcpy(&bits, &value, sizeof(bits));
        for (size_t shift = 0U; shift < 32U; shift += 8U) {
            hash ^= static_cast<uint8_t>((bits >> shift) & 0xffU);
            hash *= 1099511628211ULL;
        }
    }
    return hash == 0U ? 1U : hash;
}

uint64_t ultrafix_accumulate_checksum(
        uint64_t aggregate,
        uint32_t refinement_step,
        uint64_t value_checksum) {
    uint64_t hash = aggregate == 0U ? 1469598103934665603ULL : aggregate;
    constexpr uint64_t kPrime = 1099511628211ULL;
    constexpr std::array<uint8_t, 8> kDomain = {
        'U', 'F', 'Q', 'U', 'A', 'L', '1', 0,
    };
    for (uint8_t byte : kDomain) {
        hash ^= byte;
        hash *= kPrime;
    }
    for (size_t shift = 0U; shift < 32U; shift += 8U) {
        hash ^= static_cast<uint8_t>((refinement_step >> shift) & 0xffU);
        hash *= kPrime;
    }
    for (size_t shift = 0U; shift < 64U; shift += 8U) {
        hash ^= static_cast<uint8_t>((value_checksum >> shift) & 0xffU);
        hash *= kPrime;
    }
    return hash == 0U ? 1U : hash;
}

std::string ultrafix_noise_seed_descriptor(
        uint64_t request_seed,
        size_t denoise_step_count) {
    std::ostringstream out;
    out << "mca-ultrafix-quality-noise-v1|seed=" << request_seed
        << "|steps=" << denoise_step_count;
    return out.str();
}

size_t ultrafix_structure_blur_radius(size_t tile_latent_edge) {
    if (tile_latent_edge == 0U) {
        return 0U;
    }
    return std::max<size_t>(2U, tile_latent_edge / 16U);
}

size_t ultrafix_structure_blur_radius(const SpatialTensorShape& shape) {
    if (shape.batch == 0U || shape.channels == 0U || shape.height == 0U ||
        shape.width == 0U || shape.element_count() == 0U) {
        return 0U;
    }
    return std::clamp<size_t>(std::min(shape.height, shape.width) / 16U, 1U, 8U);
}

bool ultrafix_inject_spherical_noise(
        const std::vector<float>& prediction,
        uint64_t request_seed,
        uint32_t refinement_step,
        double random_fraction,
        std::vector<float>* mixed_prediction,
        uint64_t* gaussian_checksum,
        uint64_t* mixed_checksum,
        std::string* error) {
    if (mixed_prediction == nullptr || gaussian_checksum == nullptr ||
        mixed_checksum == nullptr || error == nullptr || prediction.empty() ||
        !finite_values(prediction) || !std::isfinite(random_fraction) ||
        random_fraction < 0.0 || random_fraction > 0.5) {
        return fail(error, "UltraFix spherical injection requires a finite prediction and bounded fraction.");
    }
    std::vector<float> gaussian;
    if (!deterministic_gaussian_tensor(
            prediction.size(), request_seed, refinement_step, &gaussian, error)) {
        return false;
    }
    const double prediction_weight = 1.0 - random_fraction;
    double dot = 0.0;
    double prediction_norm_squared = 0.0;
    double gaussian_norm_squared = 0.0;
    for (size_t index = 0U; index < prediction.size(); ++index) {
        dot += static_cast<double>(prediction[index]) * gaussian[index];
        prediction_norm_squared += static_cast<double>(prediction[index]) * prediction[index];
        gaussian_norm_squared += static_cast<double>(gaussian[index]) * gaussian[index];
    }
    const double denominator =
        std::sqrt(prediction_norm_squared) * std::sqrt(gaussian_norm_squared);
    if (!(denominator > 0.0) || !std::isfinite(denominator)) {
        return fail(error, "UltraFix spherical injection requires non-zero tensor norms.");
    }
    const double cosine = std::clamp(dot / denominator, -0.999999, 0.999999);
    const double omega = std::acos(cosine);
    const double sine = std::sin(omega);
    if (!(sine > 1.0e-12) || !std::isfinite(sine)) {
        return fail(error, "UltraFix spherical injection angle is numerically unstable.");
    }
    const double prediction_scale = std::sin(prediction_weight * omega) / sine;
    const double gaussian_scale = std::sin(random_fraction * omega) / sine;
    mixed_prediction->resize(prediction.size());
    for (size_t index = 0U; index < prediction.size(); ++index) {
        (*mixed_prediction)[index] = static_cast<float>(
            prediction_scale * prediction[index] + gaussian_scale * gaussian[index]);
    }
    if (!finite_values(*mixed_prediction)) {
        mixed_prediction->clear();
        return fail(error, "UltraFix spherical injection produced a non-finite prediction.");
    }
    *gaussian_checksum = ultrafix_tensor_checksum(gaussian);
    *mixed_checksum = ultrafix_tensor_checksum(*mixed_prediction);
    if (*gaussian_checksum == 0U || *mixed_checksum == 0U) {
        mixed_prediction->clear();
        return fail(error, "UltraFix spherical injection did not produce checksum evidence.");
    }
    error->clear();
    return true;
}

bool ultrafix_apply_structure_guidance(
        const std::vector<float>& current,
        const std::vector<float>& trajectory_reference,
        const SpatialTensorShape& shape,
        size_t blur_radius,
        double weight,
        std::vector<float>* guided,
        uint64_t* guided_checksum,
        std::string* error) {
    if (guided == nullptr || guided_checksum == nullptr || error == nullptr ||
        current.empty() || current.size() != trajectory_reference.size() ||
        current.size() != shape.element_count() || !finite_values(current) ||
        !finite_values(trajectory_reference) || !std::isfinite(weight) ||
        weight <= 0.0 || weight > 1.0) {
        return fail(error, "UltraFix structure guidance requires matching finite latents and a bounded weight.");
    }
    std::vector<float> current_low_frequency;
    std::vector<float> reference_low_frequency;
    if (!gaussian_blur_nchw(
            current, shape, blur_radius, &current_low_frequency, error) ||
        !gaussian_blur_nchw(
            trajectory_reference, shape, blur_radius, &reference_low_frequency, error)) {
        return false;
    }
    guided->resize(current.size());
    for (size_t index = 0U; index < current.size(); ++index) {
        (*guided)[index] = static_cast<float>(
            static_cast<double>(current[index]) + weight *
                (static_cast<double>(reference_low_frequency[index]) -
                    current_low_frequency[index]));
    }
    if (!finite_values(*guided)) {
        guided->clear();
        return fail(error, "UltraFix structure guidance produced a non-finite latent.");
    }
    *guided_checksum = ultrafix_tensor_checksum(*guided);
    if (*guided_checksum == 0U) {
        guided->clear();
        return fail(error, "UltraFix structure guidance did not produce checksum evidence.");
    }
    error->clear();
    return true;
}

bool resolve_ultrafix_execution_counts(
        size_t tile_count,
        size_t inversion_steps,
        size_t denoise_steps,
        bool use_cfg,
        UltraFixExecutionCounts* counts,
        std::string* error) {
    if (counts == nullptr || error == nullptr || tile_count == 0U ||
        inversion_steps == 0U || denoise_steps == 0U) {
        return fail(error, "UltraFix execution counts require non-zero tiles and steps.");
    }
    const auto multiply = [](size_t left, size_t right, size_t* output) {
        return output != nullptr && left <= std::numeric_limits<size_t>::max() / right
            ? ((*output = left * right), true)
            : false;
    };
    UltraFixExecutionCounts resolved;
    resolved.vae_encoder_graph_executions = tile_count;
    resolved.vae_decoder_graph_executions = tile_count;
    if (!multiply(tile_count, inversion_steps,
                  &resolved.inversion_positive_unet_graph_executions) ||
        !multiply(tile_count, denoise_steps,
                  &resolved.refinement_positive_unet_graph_executions)) {
        return fail(error, "UltraFix graph execution counts overflow the host size contract.");
    }
    resolved.refinement_negative_unet_graph_executions = use_cfg
        ? resolved.refinement_positive_unet_graph_executions
        : 0U;
    if (resolved.inversion_positive_unet_graph_executions >
            std::numeric_limits<size_t>::max() -
                resolved.refinement_positive_unet_graph_executions ||
        resolved.inversion_positive_unet_graph_executions +
                resolved.refinement_positive_unet_graph_executions >
            std::numeric_limits<size_t>::max() -
                resolved.refinement_negative_unet_graph_executions) {
        return fail(error, "UltraFix total UNet graph execution count overflowed.");
    }
    resolved.total_unet_graph_executions =
        resolved.inversion_positive_unet_graph_executions +
        resolved.refinement_positive_unet_graph_executions +
        resolved.refinement_negative_unet_graph_executions;
    *counts = resolved;
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
