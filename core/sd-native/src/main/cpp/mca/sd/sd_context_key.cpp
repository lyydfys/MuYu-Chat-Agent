#include "mca/sd/sd_context_key.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>

#include "mca/sd/sd_runtime_diagnostics.hpp"

namespace mca {
namespace sd {

namespace {

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

}  // namespace

std::string make_context_key(
        const ComponentPaths &paths,
        const std::vector<ContractTextualInversion> &textual_inversions,
        int threads,
        const std::string &execution_asset_composite,
        sd_qwen_image_21_phase_t qwen_phase) {
    std::ostringstream key;
    key << paths.model << "|"
        << paths.diffusion << "|"
        << paths.high_noise_diffusion << "|"
        << paths.vae << "|"
        << paths.clip_l << "|"
        << paths.clip_g << "|"
        << paths.clip_vision << "|"
        << paths.t5xxl << "|"
        << paths.llm << "|"
        << paths.llm_vision << "|"
        << paths.embeddings_connectors << "|"
        << paths.control_net << "|"
        << paths.selection_mode << "|"
        << paths.tokenizer_path << "|"
        << "qwenPhase:" << static_cast<int>(qwen_phase) << "|"
        << threads << "|"
        << sd_runtime_backend_label() << "|assets:"
        << execution_asset_composite;
    for (const auto &artifact: textual_inversions) {
        key << "|ti:" << lower_copy(artifact.trigger)
            << ":" << artifact.path
            << ":" << artifact.sha256
            << ":" << artifact.tokenizer_fingerprint;
    }
    return key.str();
}

std::string observed_prediction_mode(int prediction) {
    if (prediction < 0 || prediction >= PREDICTION_COUNT) return "unknown";
    return sd_prediction_name(static_cast<prediction_t>(prediction));
}

}  // namespace sd
}  // namespace mca
