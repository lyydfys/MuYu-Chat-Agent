#ifndef MCA_MNN_DIFFUSION_PIPELINE_HPP
#define MCA_MNN_DIFFUSION_PIPELINE_HPP

#include <string>
#include <vector>
#include <memory>
#include <functional>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "mca/mnn/mnn_tensor_ops.hpp"
#include "mca/mnn/mnn_diffusion_assets.hpp"

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#endif

namespace mca {
namespace mnn {

bool is_sana_family(const std::string& family);
bool is_sd15_family(const std::string& family);

bool validate_mnn_diffusion_runtime_options(
        const std::string& requested_backend,
        int threads,
        std::string& canonical_backend,
        std::string& error);

bool resolve_mnn_diffusion_runner(
        bool sana_family,
        const std::string& requested_runner,
        std::string& runner,
        std::string& warning,
        std::string& error);

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
bool copy_mnn_timestep_to_tensor(
        MNN::Tensor* tensor,
        double timestep,
        std::string& error);

bool run_text_encoder_direct_once(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        const MnnOpenedTextEncoderBinding* textEncoderBinding = nullptr);

bool run_unet_direct(
        const std::string& root,
        const std::vector<float>& sample,
        double timestep,
        const FloatTensorData& embeddings,
        const std::string& backendMode,
        int threads,
        FloatTensorData& noise,
        std::string& error);

bool run_vae_decoder_direct(
        const std::string& root,
        const std::vector<float>& latent,
        const std::string& backendMode,
        int threads,
        FloatTensorData& image,
        std::string& error);

nlohmann::json run_mnn_sd15_module_runner(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        int steps,
        int seed,
        const std::string& backendMode,
        int memoryMode,
        std::function<void(int)> progressCallback = nullptr);
#endif

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_DIFFUSION_PIPELINE_HPP
