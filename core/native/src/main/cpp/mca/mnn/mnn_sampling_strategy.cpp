#include "mca/mnn/mnn_sampling_strategy.hpp"

namespace mca {
namespace mnn {

bool is_greedy(const MnnSamplingParams& params) noexcept {
    return params.temperature == 0.0;
}

bool has_penalties(const MnnSamplingParams& params) noexcept {
    return params.repetition_penalty != 1.0 ||
           params.presence_penalty != 0.0 ||
           params.frequency_penalty != 0.0;
}

std::vector<std::string> resolve_mixed_samplers(
    const MnnSamplingParams& params,
    const std::vector<std::string>& existing_stages) {
    // The pinned sampler reads mixed_samplers, moves "penalty" to the front when
    // present, and only then treats a trailing "greedy" as the selection type
    // (third_party/MNN/transformers/llm/engine/src/sampler.cpp:148-168).
    // Returning {"greedy"} early therefore dropped repetition/presence/frequency
    // penalties whenever temperature == 0. Greedy keeps its own base stage, but a
    // requested penalty stage is still prepended, restoring the original
    // mnn_sampling_config.hpp behaviour (["penalty", "greedy"]).
    std::vector<std::string> stages;
    if (is_greedy(params)) {
        stages = {"greedy"};
    } else if (existing_stages.empty()) {
        stages = {"topK", "tfs", "typical", "topP", "min_p", "temperature"};
    } else {
        stages = existing_stages;
    }
    if (has_penalties(params) &&
        std::find(stages.begin(), stages.end(), "penalty") == stages.end()) {
        stages.insert(stages.begin(), "penalty");
    }
    return stages;
}

MnnSamplingParams parse_sampling_params(
    const nlohmann::json& params,
    const nlohmann::json& advanced) {
    const auto value = [&](const char* canonical, const char* alias, nlohmann::json fallback) {
        for (const auto* source : {&advanced, &params}) {
            for (const auto* key : {canonical, alias}) {
                if (source->contains(key) && !source->at(key).is_null()) return source->at(key);
            }
        }
        return fallback;
    };

    const auto number = [&](const char* key, const char* alias, double fallback, double low, double high) {
        const auto raw = value(key, alias, fallback);
        if (!raw.is_number()) throw std::invalid_argument(std::string("MNN invalid sampler field: ") + key);
        const double result = raw.get<double>();
        if (!std::isfinite(result) || result < low || result > high)
            throw std::invalid_argument(std::string("MNN sampler field out of range: ") + key);
        return result;
    };

    MnnSamplingParams p;
    p.temperature = number("temperature", "temperature", 0.6, 0.0, 100.0);
    const double raw_top_k = number("top_k", "topK", 20.0, 0.0, 2147483647.0);
    const double raw_seed = number("seed", "seed", -1.0, -1.0, 2147483647.0);

    if (std::floor(raw_top_k) != raw_top_k || std::floor(raw_seed) != raw_seed) {
        throw std::invalid_argument("MNN top_k and seed must be integers");
    }

    p.top_k = is_greedy(p) ? 1 : static_cast<int>(raw_top_k);
    p.top_p = is_greedy(p) ? 1.0 : number("top_p", "topP", 0.95, 0.0, 1.0);
    p.min_p = number("min_p", "minP", 0.0, 0.0, 1.0);
    p.seed = static_cast<int>(raw_seed);
    p.repetition_penalty = number("repeat_penalty", "repetition_penalty", 1.0, 0.0, 100.0);
    p.presence_penalty = number("presence_penalty", "presencePenalty", 0.0, -100.0, 100.0);
    p.frequency_penalty = number("frequency_penalty", "frequencyPenalty", 0.0, -100.0, 100.0);

    // A penalty request promotes the sampler pipeline to "mixed" even when the
    // temperature selects greedy decoding; only greedy without any penalty is
    // reported as plain "greedy" (original mnn_sampling_config.hpp semantics).
    p.sampler_type = (is_greedy(p) && !has_penalties(p)) ? "greedy" : "mixed";
    p.mixed_samplers = resolve_mixed_samplers(p);
    return p;
}

void apply_sampling_strategy(
    nlohmann::json& config,
    const MnnSamplingParams& params) {
    config["temperature"] = params.temperature;
    config["top_k"] = config["topK"] = params.top_k;
    config["top_p"] = config["topP"] = params.top_p;
    config["min_p"] = config["minP"] = params.min_p;
    config["seed"] = params.seed;
    config["repetition_penalty"] = config["repeat_penalty"] = params.repetition_penalty;
    config["presence_penalty"] = config["presencePenalty"] = params.presence_penalty;
    config["frequency_penalty"] = config["frequencyPenalty"] = params.frequency_penalty;

    config["sampler_type"] = params.sampler_type;

    std::vector<std::string> existing_stages;
    if (config.contains("mixed_samplers") && config["mixed_samplers"].is_array()) {
        existing_stages = config["mixed_samplers"].get<std::vector<std::string>>();
    }
    config["mixed_samplers"] = resolve_mixed_samplers(params, existing_stages);
    if (has_penalties(params)) {
        config["sampler_type"] = "mixed";
    }
}

void applySamplingConfig(
    nlohmann::json& config,
    const nlohmann::json& params,
    const nlohmann::json& advanced) {
    const MnnSamplingParams parsed = parse_sampling_params(params, advanced);
    apply_sampling_strategy(config, parsed);
}

void applyCpuCacheSafety(nlohmann::json& config) {
    if (config.value("backend_type", std::string("cpu")) == "cpu") {
        config["precision"] = "high";
        // Keep mmap and live KV, but repack CPU weights from the model at load.
        // A sync.static marker alone cannot validate the contents of the cache.
        config["use_cached_mmap"] = false;
    }
}

} // namespace mnn
} // namespace mca
