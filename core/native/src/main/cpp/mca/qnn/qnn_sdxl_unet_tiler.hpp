#ifndef MCA_QNN_SDXL_UNET_TILER_HPP
#define MCA_QNN_SDXL_UNET_TILER_HPP

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "image_execution_math.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"

namespace mca::qnn {

template <typename GraphT>
inline bool run_sdxl_ultrafix_tiled_unet_branch(
        GraphT& unet,
        int sample_index,
        int timestep_index,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& full_sample,
        double timestep,
        const float* hidden,
        size_t hidden_count,
        const float* time_ids,
        size_t time_ids_count,
        const float* pooled,
        size_t pooled_count,
        std::vector<float>* full_output,
        long long* execute_ms_total,
        size_t* graph_execution_count,
        std::string* error) {
    if (full_output == nullptr || execute_ms_total == nullptr ||
        graph_execution_count == nullptr || error == nullptr || hidden == nullptr ||
        full_sample.size() != plan.full_latent.element_count() ||
        time_ids == nullptr || pooled == nullptr ||
        time_ids_count != mca::image::kSdxlTimeIdCount ||
        plan.target_pixels.width == 0U || plan.target_pixels.height == 0U ||
        plan.tile_size_pixels == 0U || plan.spatial_scale == 0U) {
        if (error != nullptr) {
            *error = "Split-SDXL UltraFix tiled UNet inputs do not match the unified plan.";
        }
        return false;
    }
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(plan.tiles.size());
    for (const auto& tile : plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during split-SDXL UltraFix UNet tiling.";
            return false;
        }
        std::vector<float> tile_sample;
        if (!mca::image::copy_spatial_tile(
                full_sample,
                plan.full_latent,
                tile.latent_x,
                tile.latent_y,
                plan.unet_input,
                &tile_sample,
                error)) {
            return false;
        }
        std::vector<float> tile_output;
        long long execute_ms = 0;
        std::vector<float> tile_time_ids(time_ids, time_ids + time_ids_count);
        const double conditioning_scale = 1024.0 / static_cast<double>(
            std::max(plan.target_pixels.width, plan.target_pixels.height));
        tile_time_ids[0] = static_cast<float>(std::round(
            static_cast<double>(plan.target_pixels.height) * conditioning_scale));
        tile_time_ids[1] = static_cast<float>(std::round(
            static_cast<double>(plan.target_pixels.width) * conditioning_scale));
        tile_time_ids[2] = static_cast<float>(std::round(
            static_cast<double>(tile.latent_y * plan.spatial_scale) * conditioning_scale));
        tile_time_ids[3] = static_cast<float>(std::round(
            static_cast<double>(tile.latent_x * plan.spatial_scale) * conditioning_scale));
        tile_time_ids[4] = static_cast<float>(plan.tile_size_pixels);
        tile_time_ids[5] = static_cast<float>(plan.tile_size_pixels);
        if (!qnn_run_sdxl_unet_once(
                unet,
                sample_index,
                timestep_index,
                hidden_index,
                time_ids_index,
                pooled_index,
                tile_sample,
                timestep,
                hidden,
                hidden_count,
                tile_time_ids.data(),
                time_ids_count,
                pooled,
                pooled_count,
                &tile_output,
                &execute_ms,
                error) ||
            tile_output.size() != plan.unet_output.element_count()) {
            if (error->empty()) {
                *error = "Split-SDXL UltraFix UNet returned an invalid latent tile.";
            }
            return false;
        }
        *execute_ms_total += execute_ms;
        ++(*graph_execution_count);
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during split-SDXL UltraFix UNet tiling.";
            return false;
        }
        tile_outputs.push_back(std::move(tile_output));
    }
    if (qnn_image_generation_cancelled()) {
        *error = "Image generation was cancelled before split-SDXL UltraFix tile blending.";
        return false;
    }
    return mca::image::blend_ultrafix_latent_tiles(
        plan, tile_outputs, full_output, error);
}

} // namespace mca::qnn

using mca::qnn::run_sdxl_ultrafix_tiled_unet_branch;

#endif // MCA_QNN_SDXL_UNET_TILER_HPP
