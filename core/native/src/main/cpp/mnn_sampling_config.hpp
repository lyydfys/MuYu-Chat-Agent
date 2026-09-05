#pragma once

#include <cmath>
#include <stdexcept>
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
    config["temperature"] = temperature;
    config["top_k"] = config["topK"] = temperature == 0 ? 1 : static_cast<int>(topK);
    config["top_p"] = config["topP"] = temperature == 0 ? 1.0 : topP;
    config["min_p"] = config["minP"] = minP;
    config["seed"] = static_cast<int>(seed);
    config["sampler_type"] = temperature == 0 ? "greedy" : "mixed";
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
