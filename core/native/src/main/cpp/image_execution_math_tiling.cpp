// T2B18 / R7-C7 (2026-10-14): split out of image_execution_math.cpp (pre-split sha256
// 3ceca72fded2223d85e89566d0909a77c6c75c3a72b9f0df13bfaf175343c17a).  Body lines are byte-exact
// relocations; every delta is registered in b-SPLIT-PLAN.json.

#include "image_execution_math.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <utility>

#include "image_execution_math_internal.hpp"

namespace mca::image {

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

namespace {
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

}  // namespace mca::image
