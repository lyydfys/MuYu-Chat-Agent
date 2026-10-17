// mca/qnn/qnn_model_execution_vae_decode.cpp -- VAE latent decode and UltraFix VAE latent decode.
// (T2B17 / R7-C6 split).  Relocated byte-exact from mca/qnn/qnn_model_execution_pipeline.cpp (lines 646-795).

#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"

#include <utility>

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK


bool qnn_decode_vae_latents(
        QnnExecutableGraph& vae,
        const std::vector<uint32_t>& source_latent_dimensions,
        const std::vector<float>& source_latents,
        const QnnSemanticExecutionContract& execution_contract,
        QnnVaeDecodeResult* result,
        std::string* error) {
    if (result == nullptr || error == nullptr || vae.inputs.empty() || vae.outputs.empty()) {
        return false;
    }
    QnnVaeDecodeResult decoded;
    if (!mca::image::build_vae_decode_plan(
            source_latent_dimensions,
            vae.inputs[0].dimensions,
            vae.outputs[0].dimensions,
            static_cast<size_t>(execution_contract.width),
            static_cast<size_t>(execution_contract.height),
            &decoded.plan,
            error)) {
        return false;
    }
    if (source_latents.size() != decoded.plan.source_latent.element_count()) {
        *error = "Published latent values do not match the resolved source latent shape.";
        return false;
    }
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(decoded.plan.tiles.size());
    for (const auto& tile : decoded.plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during VAE tile decode.";
            return false;
        }
        std::vector<float> vae_latents;
        if (!mca::image::copy_spatial_tile(
                source_latents,
                decoded.plan.source_latent,
                tile.latent_x,
                tile.latent_y,
                decoded.plan.vae_input,
                &vae_latents,
                error) ||
            !mca::image::scale_vae_latents_in_place(
                &vae_latents,
                execution_contract.vae_scaling_factor,
                execution_contract.vae_scaling_location ==
                        QnnVaeScalingLocation::HostBeforeGraph,
                error) ||
            !qnn_write_float_tensor(
                &vae.inputs[0],
                vae_latents.data(),
                vae_latents.size(),
                error)) {
            return false;
        }
        long long execute_ms = 0;
        if (!vae.execute(&execute_ms, error)) {
            return false;
        }
        decoded.execute_ms_total += execute_ms;
        ++decoded.execution_count;
        std::vector<float> tile_pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &tile_pixels, error)) {
            return false;
        }
        tile_outputs.push_back(std::move(tile_pixels));
    }
    if (!mca::image::blend_vae_decode_tiles(
            decoded.plan,
            tile_outputs,
            &decoded.pixels_nchw,
            error)) {
        return false;
    }
    *result = std::move(decoded);
    error->clear();
    return true;
}

bool qnn_decode_ultrafix_vae_latents(
        QnnExecutableGraph& vae,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& source_latents,
        const QnnSemanticExecutionContract& execution_contract,
        QnnUltraFixVaeDecodeResult* result,
        std::string* error) {
    if (result == nullptr || error == nullptr || vae.inputs.empty() ||
        vae.outputs.empty() || source_latents.size() != plan.full_latent.element_count()) {
        if (error != nullptr) *error = "UltraFix VAE decode inputs do not match the tile plan.";
        return false;
    }
    QnnUltraFixVaeDecodeResult decoded;
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(plan.tiles.size());
    for (const auto& tile : plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during UltraFix VAE tile decode.";
            return false;
        }
        std::vector<float> tile_latents;
        if (!mca::image::copy_spatial_tile(
                source_latents,
                plan.full_latent,
                tile.latent_x,
                tile.latent_y,
                plan.vae_input,
                &tile_latents,
                error) ||
            !mca::image::scale_vae_latents_in_place(
                &tile_latents,
                execution_contract.vae_scaling_factor,
                execution_contract.vae_scaling_location ==
                    QnnVaeScalingLocation::HostBeforeGraph,
                error) ||
            !qnn_write_float_tensor(
                &vae.inputs[0], tile_latents.data(), tile_latents.size(), error)) {
            return false;
        }
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled before an UltraFix VAE tile execution.";
            return false;
        }
        long long execute_ms = 0;
        if (!vae.execute(&execute_ms, error)) return false;
        decoded.execute_ms_total += execute_ms;
        ++decoded.execution_count;
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled after an UltraFix VAE tile execution.";
            return false;
        }
        std::vector<float> tile_pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &tile_pixels, error) ||
            tile_pixels.size() != plan.vae_output.element_count()) {
            if (error->empty()) *error = "UltraFix VAE tile output shape is invalid.";
            return false;
        }
        tile_outputs.push_back(std::move(tile_pixels));
    }
    if (qnn_image_generation_cancelled()) {
        *error = "Image generation was cancelled before UltraFix VAE tile blending.";
        return false;
    }
    if (!mca::image::blend_ultrafix_pixel_tiles(
            plan, tile_outputs, &decoded.pixels_nchw, error)) {
        return false;
    }
    *result = std::move(decoded);
    error->clear();
    return true;
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
