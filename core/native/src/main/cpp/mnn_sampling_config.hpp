#pragma once

#include <cmath>
#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>
#include "nlohmann/json.hpp"

namespace mca::mnn {

// Resolve aliases within each source before applying advanced overrides.
// Otherwise an existing top_k masks a later advanced topK (and likewise minP).
inline void applySamplingConfig(nlohmann::json& config, const nlohmann::json& params,
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
    const double temperature = number("temperature", "temperature", 0.6, 0, 100);
    const double topK = number("top_k", "topK", 20, 0, 2147483647);
    const double seed = number("seed", "seed", -1, -1, 2147483647);
    if (std::floor(topK) != topK || std::floor(seed) != seed)
        throw std::invalid_argument("MNN top_k and seed must be integers");
    const double topP = number("top_p", "topP", 0.95, 0, 1);
    const double minP = number("min_p", "minP", 0.0, 0, 1);
    const double repetitionPenalty = number("repeat_penalty", "repetition_penalty", 1.0, 0.0, 100.0);
    const double presencePenalty = number("presence_penalty", "presencePenalty", 0.0, -100.0, 100.0);
    const double frequencyPenalty = number("frequency_penalty", "frequencyPenalty", 0.0, -100.0, 100.0);
    config["temperature"] = temperature;
    config["top_k"] = config["topK"] = temperature == 0 ? 1 : static_cast<int>(topK);
    config["top_p"] = config["topP"] = temperature == 0 ? 1.0 : topP;
    config["min_p"] = config["minP"] = minP;
    config["seed"] = static_cast<int>(seed);
    config["repetition_penalty"] = config["repeat_penalty"] = repetitionPenalty;
    config["presence_penalty"] = config["presencePenalty"] = presencePenalty;
    config["frequency_penalty"] = config["frequencyPenalty"] = frequencyPenalty;
    // The pinned MNN sampler only applies penalty fields when the penalty stage
    // is present in the mixed pipeline. Preserve explicit stages, and use the
    // pinned runtime's default stages when none were supplied.
    const bool hasPenalty = repetitionPenalty != 1.0 || presencePenalty != 0.0 || frequencyPenalty != 0.0;
    config["sampler_type"] = temperature == 0 ? "greedy" : "mixed";
    if (hasPenalty) {
        auto stages = temperature == 0
                ? std::vector<std::string>{"greedy"}
                : config.value("mixed_samplers", std::vector<std::string>{
                        "topK", "tfs", "typical", "topP", "min_p", "temperature"});
        if (std::find(stages.begin(), stages.end(), "penalty") == stages.end()) {
            stages.insert(stages.begin(), "penalty");
        }
        config["mixed_samplers"] = stages;
        config["sampler_type"] = "mixed";
    }
}

inline void applyCpuCacheSafety(nlohmann::json& config) {
    if (config.value("backend_type", std::string("cpu")) == "cpu") {
        config["precision"] = "high";
        // Keep mmap and live KV, but repack CPU weights from the model at load.
        // A sync.static marker alone cannot validate the contents of the cache.
        config["use_cached_mmap"] = false;
    }
}

} // namespace mca::mnn
