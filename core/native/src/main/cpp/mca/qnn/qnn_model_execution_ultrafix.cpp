// mca/qnn/qnn_model_execution_ultrafix.cpp -- UltraFix noise-level resolution and the tiled UltraFix UNet branch.
// (T2B17 / R7-C6 split).  Relocated byte-exact from mca/qnn/qnn_model_execution_pipeline.cpp (lines 250-381).

#include "mca/qnn/qnn_model_execution_pipeline.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK


bool qnn_ultrafix_noise_level(
        const mca::diffusion::DiffusionScheduler& scheduler,
        size_t schedule_index,
        mca::image::UltraFixNoiseLevel* level,
        std::string* error) {
    if (level == nullptr || error == nullptr ||
        schedule_index >= scheduler.timesteps().size()) {
        if (error != nullptr) *error = "UltraFix noise-level index is outside the scheduler timetable.";
        return false;
    }
    mca::image::UltraFixNoiseLevel resolved;
    const auto algorithm = scheduler.config().algorithm;
    if (algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete ||
        algorithm == mca::diffusion::SchedulerAlgorithm::Dpmpp2m) {
        if (schedule_index >= scheduler.sigmas().size() - 1U) {
            *error = "UltraFix scheduler sigma index is unavailable.";
            return false;
        }
        const double raw_sigma = scheduler.sigmas()[schedule_index];
        if (!(raw_sigma > 0.0) || !std::isfinite(raw_sigma)) {
            *error = "UltraFix scheduler sigma must be finite and positive.";
            return false;
        }
        const double normalization = 1.0 / std::sqrt(raw_sigma * raw_sigma + 1.0);
        if (algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete) {
            resolved.alpha = 1.0;
            resolved.sigma = raw_sigma;
            resolved.scheduler_input_scale = normalization;
        } else {
            resolved.alpha = normalization;
            resolved.sigma = raw_sigma * normalization;
            resolved.scheduler_input_scale = 1.0;
        }
    } else if (algorithm == mca::diffusion::SchedulerAlgorithm::Ddim) {
        const long long timestep = static_cast<long long>(
            std::llround(scheduler.timesteps()[schedule_index]));
        if (timestep < 0 ||
            static_cast<size_t>(timestep) >= scheduler.alphas_cumprod().size()) {
            *error = "UltraFix DDIM timestep is outside the training alpha schedule.";
            return false;
        }
        const double alpha_product =
            scheduler.alphas_cumprod()[static_cast<size_t>(timestep)];
        if (!(alpha_product > 0.0) || alpha_product > 1.0 ||
            !std::isfinite(alpha_product)) {
            *error = "UltraFix DDIM alpha product is invalid.";
            return false;
        }
        resolved.alpha = std::sqrt(alpha_product);
        resolved.sigma = std::sqrt(std::max(0.0, 1.0 - alpha_product));
        resolved.scheduler_input_scale = 1.0;
    } else {
        *error = "UltraFix inversion does not support PNDM scheduler state.";
        return false;
    }
    *level = resolved;
    error->clear();
    return true;
}

bool qnn_run_ultrafix_tiled_unet_branch(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& full_sample,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* full_output,
        long long* execute_ms_total,
        size_t* graph_execution_count,
        std::string* error) {
    if (full_output == nullptr || execute_ms_total == nullptr ||
        graph_execution_count == nullptr || error == nullptr || embedding == nullptr ||
        full_sample.size() != plan.full_latent.element_count()) {
        if (error != nullptr) *error = "UltraFix tiled UNet inputs do not match the plan.";
        return false;
    }
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(plan.tiles.size());
    for (const auto& tile : plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during an UltraFix UNet tile pass.";
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
        if (!qnn_run_unet_once(
                unet,
                sample_index,
                timestep_index,
                text_index,
                tile_sample,
                timestep,
                embedding,
                embedding_elements,
                &tile_output,
                &execute_ms,
                error) ||
            tile_output.size() != plan.unet_output.element_count()) {
            if (error->empty()) *error = "UltraFix UNet tile output shape is invalid.";
            return false;
        }
        *execute_ms_total += execute_ms;
        ++(*graph_execution_count);
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled after an UltraFix UNet tile execution.";
            return false;
        }
        tile_outputs.push_back(std::move(tile_output));
    }
    if (qnn_image_generation_cancelled()) {
        *error = "Image generation was cancelled before UltraFix UNet tile blending.";
        return false;
    }
    return mca::image::blend_ultrafix_latent_tiles(
        plan, tile_outputs, full_output, error);
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
