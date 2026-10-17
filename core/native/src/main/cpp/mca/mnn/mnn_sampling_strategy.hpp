#ifndef MCA_MNN_SAMPLING_STRATEGY_HPP
#define MCA_MNN_SAMPLING_STRATEGY_HPP

#include <cmath>
#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>
#include "nlohmann/json.hpp"

namespace mca {
namespace mnn {

/**
 * Structured sampling parameters for MNN LLM inference.
 * Encapsulates temperature, top-k, top-p, min-p, random seed, and penalty coefficients.
 */
struct MnnSamplingParams {
    double temperature{0.6};
    int top_k{20};
    double top_p{0.95};
    double min_p{0.0};
    int seed{-1};
    double repetition_penalty{1.0};
    double presence_penalty{0.0};
    double frequency_penalty{0.0};
    std::string sampler_type{"mixed"};
    std::vector<std::string> mixed_samplers{
        "topK", "tfs", "typical", "topP", "min_p", "temperature"
    };
};

/**
 * Checks whether the sampling configuration dictates greedy (deterministic) token selection.
 */
bool is_greedy(const MnnSamplingParams& params) noexcept;

/**
 * Checks whether any repetition, presence, or frequency penalties are active.
 */
bool has_penalties(const MnnSamplingParams& params) noexcept;

/**
 * Resolves the ordered pipeline stages for MNN's mixed sampler, ensuring the penalty stage
 * is appropriately positioned when penalty coefficients are configured.
 */
std::vector<std::string> resolve_mixed_samplers(
    const MnnSamplingParams& params,
    const std::vector<std::string>& existing_stages = {});

/**
 * Parses and validates sampling parameters from incoming JSON dictionaries,
 * safely resolving aliases between top_k/topK, min_p/minP, etc., with bounds checking.
 */
MnnSamplingParams parse_sampling_params(
    const nlohmann::json& params,
    const nlohmann::json& advanced = nlohmann::json::object());

/**
 * Applies structured sampling parameters onto an MNN llm_config JSON object.
 */
void apply_sampling_strategy(
    nlohmann::json& config,
    const MnnSamplingParams& params);

/**
 * Main entrypoint for applying sampling parameters and overrides onto MNN runtime configuration.
 * Resolves aliases across params and advanced sources, validates all numeric ranges,
 * and configures the mixed sampler pipeline stages.
 */
void applySamplingConfig(
    nlohmann::json& config,
    const nlohmann::json& params,
    const nlohmann::json& advanced);

/**
 * Enforces high precision and safe mmap settings when CPU backend is targeted.
 */
void applyCpuCacheSafety(nlohmann::json& config);

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_SAMPLING_STRATEGY_HPP
