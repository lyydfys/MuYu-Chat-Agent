#ifndef MCA_MNN_JSON_OPTIONS_HPP
#define MCA_MNN_JSON_OPTIONS_HPP

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>

#include "nlohmann/json.hpp"

namespace mca {
namespace mnn {

using json = nlohmann::json;

// E04 (pure migration, L1): these option helpers were moved verbatim out of the
// anonymous namespace in mnn_native_engine.cpp so the parse/merge contract has a
// single owner.  Function bodies are byte-identical to the bridge definitions
// (recorded block sha256 d892cc06d8b600dc8522127c193bf92686ef8c498dd35568db6e6314636b244d);
// only the `inline` keyword was added.  Callers keep the same unqualified names.

inline int opt_int(const json& root, const char* name, int fallback) {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        if (it->is_number_integer()) return it->get<int>();
        if (it->is_number_float()) {
            const double value = it->get<double>();
            if (!std::isfinite(value) || value < std::numeric_limits<int>::min() ||
                value > std::numeric_limits<int>::max()) {
                return fallback;
            }
            return static_cast<int>(value);
        }
        if (it->is_string()) {
            return std::stoi(it->get<std::string>());
        }
    } catch (...) {
        return fallback;
    }
    return fallback;
}

inline bool opt_bool(const json& root, const char* name, bool fallback) {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        if (it->is_boolean()) return it->get<bool>();
        if (it->is_number_integer()) return it->get<int>() != 0;
        if (it->is_string()) {
            auto value = it->get<std::string>();
            std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            if (value == "true" || value == "1" || value == "yes") return true;
            if (value == "false" || value == "0" || value == "no") return false;
        }
    } catch (...) {
        return fallback;
    }
    return fallback;
}

inline double opt_double(const json& root, const char* name, double fallback) {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        double value = fallback;
        if (it->is_number()) {
            value = it->get<double>();
        } else if (it->is_string()) {
            value = std::stod(it->get<std::string>());
        } else {
            return fallback;
        }
        return std::isfinite(value) ? value : fallback;
    } catch (...) {
        return fallback;
    }
    return fallback;
}

inline std::string opt_string(const json& root, const char* name, const std::string& fallback = "") {
    try {
        if (!root.is_object()) return fallback;
        auto it = root.find(name);
        if (it == root.end() || it->is_null()) return fallback;
        if (it->is_string()) return it->get<std::string>();
        if (it->is_number_integer()) return std::to_string(it->get<int64_t>());
        if (it->is_number_float()) return std::to_string(it->get<double>());
        if (it->is_boolean()) return it->get<bool>() ? "true" : "false";
    } catch (...) {
        return fallback;
    }
    return fallback;
}

inline bool optional_json_string(
        const json& root,
        const char *name,
        const std::string& fallback,
        std::string& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (!it->is_string()) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be a string.";
        return false;
    }
    value = it->get<std::string>();
    return true;
}

inline bool optional_json_int(
        const json& root,
        const char *name,
        int fallback,
        int& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (it->is_number_unsigned()) {
        const auto raw = it->get<uint64_t>();
        if (raw > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
            error = std::string("MNN-Diffusion parameter '") + name + "' is outside the supported integer range.";
            return false;
        }
        value = static_cast<int>(raw);
        return true;
    }
    if (it->is_number_integer()) {
        const auto raw = it->get<int64_t>();
        if (raw < static_cast<int64_t>(std::numeric_limits<int>::min()) ||
            raw > static_cast<int64_t>(std::numeric_limits<int>::max())) {
            error = std::string("MNN-Diffusion parameter '") + name + "' is outside the supported integer range.";
            return false;
        }
        value = static_cast<int>(raw);
        return true;
    }
    error = std::string("MNN-Diffusion parameter '") + name + "' must be an integer.";
    return false;
}

inline bool optional_json_double(
        const json& root,
        const char *name,
        double fallback,
        double& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (!it->is_number()) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be a number.";
        return false;
    }
    value = it->get<double>();
    if (!std::isfinite(value)) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be finite.";
        return false;
    }
    return true;
}

inline bool optional_json_bool(
        const json& root,
        const char *name,
        bool fallback,
        bool& value,
        std::string& error) {
    if (!root.is_object()) {
        error = "MNN-Diffusion parameters must be a JSON object.";
        return false;
    }
    const auto it = root.find(name);
    if (it == root.end() || it->is_null()) {
        value = fallback;
        return true;
    }
    if (!it->is_boolean()) {
        error = std::string("MNN-Diffusion parameter '") + name + "' must be a boolean.";
        return false;
    }
    value = it->get<bool>();
    return true;
}

inline void merge_json_object(json& target, const json& override) {
    if (!override.is_object()) return;
    for (auto it = override.begin(); it != override.end(); ++it) {
        if (it.value().is_object() && target.contains(it.key()) && target[it.key()].is_object()) {
            merge_json_object(target[it.key()], it.value());
        } else {
            target[it.key()] = it.value();
        }
    }
}

inline json advanced_json_from_params(const json& params) {
    try {
        if (!params.is_object()) return json::object();
        auto it = params.find("advanced_json");
        if (it == params.end() || it->is_null()) return json::object();
        if (it->is_object()) return *it;
        if (it->is_string()) {
            auto parsed = json::parse(it->get<std::string>(), nullptr, false);
            return parsed.is_object() ? parsed : json::object();
        }
    } catch (...) {
        return json::object();
    }
    return json::object();
}

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_JSON_OPTIONS_HPP
