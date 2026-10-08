#include "sd_lora_adapter.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_set>

namespace mca {
namespace sd {

namespace {

const nlohmann::json& required_field(const nlohmann::json& object, const std::string& key) {
    const auto found = object.find(key);
    if (found == object.end() || found->is_null()) {
        throw_invalid_lora(key, "required field is missing");
    }
    return *found;
}

bool blank_text(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isspace(c) != 0;
    });
}

std::string required_string(const nlohmann::json& object,
                            const std::string& key,
                            bool allow_empty = false) {
    const nlohmann::json& value = required_field(object, key);
    if (!value.is_string()) throw_invalid_lora(key, "field must be a string");
    const std::string result = value.get<std::string>();
    if (!allow_empty && (result.empty() || blank_text(result))) {
        throw_invalid_lora(key, "field must be a non-blank string");
    }
    return result;
}

int64_t required_integer(const nlohmann::json& object, const std::string& key) {
    const nlohmann::json& value = required_field(object, key);
    try {
        if (value.is_number_unsigned()) {
            const uint64_t result = value.get<uint64_t>();
            if (result <= static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
                return static_cast<int64_t>(result);
            }
        } else if (value.is_number_integer()) {
            return value.get<int64_t>();
        }
    } catch (const std::exception&) {
    }
    throw_invalid_lora(key, "field must be an exact signed 64-bit integer");
}

int required_int32(const nlohmann::json& object, const std::string& key) {
    const int64_t value = required_integer(object, key);
    if (value < std::numeric_limits<int>::min() ||
        value > std::numeric_limits<int>::max()) {
        throw_invalid_lora(key, "field must fit a signed 32-bit integer");
    }
    return static_cast<int>(value);
}

double required_number(const nlohmann::json& object, const std::string& key) {
    const nlohmann::json& value = required_field(object, key);
    if (!value.is_number()) throw_invalid_lora(key, "field must be numeric");
    const double result = value.get<double>();
    if (!std::isfinite(result)) throw_invalid_lora(key, "field must be finite");
    return result;
}

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool is_valid_sha256(const std::string& value) {
    return value.size() == 64 && std::all_of(value.begin(), value.end(), [](unsigned char c) {
        return std::isxdigit(c) != 0;
    });
}

bool file_exists(const std::string& path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists(const std::string& path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

} // namespace

LoraAdapterSpec parse_lora_specification(const nlohmann::json& item) {
    if (!item.is_object()) {
        throw_invalid_lora("loras", "every LoRA item must be an object");
    }
    LoraAdapterSpec adapter;
    adapter.id = required_string(item, "id");
    adapter.name = required_string(item, "name");
    adapter.path = required_string(item, "path");
    adapter.sha256 = lower_copy(required_string(item, "sha256"));
    const int64_t size_bytes = required_integer(item, "sizeBytes");
    adapter.multiplier = required_number(item, "multiplier");
    if (!is_valid_sha256(adapter.sha256)) {
        throw_invalid_lora("loras.sha256", "must be a 64-character SHA-256 value");
    }
    if (size_bytes < static_cast<int64_t>(kMinLoraBytes) ||
        static_cast<uint64_t>(size_bytes) > kMaxLoraBytes) {
        throw_invalid_lora("loras.sizeBytes", "must describe a bounded non-empty LoRA file");
    }
    if (adapter.multiplier < -kMaxLoraMultiplierAbs || adapter.multiplier > kMaxLoraMultiplierAbs ||
        std::fabs(adapter.multiplier) < kMinLoraMultiplierAbs) {
        throw_invalid_lora("loras.multiplier", "must be in [-4, -0.01] or [0.01, 4]");
    }
    adapter.size_bytes = static_cast<uint64_t>(size_bytes);
    return adapter;
}

LoraContractSpec parse_lora_specifications(const nlohmann::json& params) {
    const int lora_count = required_int32(params, "loraCount");
    if (lora_count < 0 || static_cast<size_t>(lora_count) > kMaxLoraCount) {
        throw_invalid_lora("loraCount", "must be in [0, 8]");
    }
    LoraContractSpec spec;
    spec.lora_root_path = required_string(params, "loraRootPath", true);
    const auto& loras = required_field(params, "loras");
    if (!loras.is_array() || loras.size() != static_cast<size_t>(lora_count)) {
        throw_invalid_lora("loras", "must be an array whose length exactly matches loraCount");
    }
    if ((lora_count == 0) != spec.lora_root_path.empty()) {
        throw_invalid_lora(
                "loraRootPath",
                lora_count == 0
                ? "must be empty when no LoRA is requested"
                : "is required when LoRA adapters are requested");
    }
    std::unordered_set<std::string> lora_ids;
    std::unordered_set<std::string> lora_paths;
    spec.loras.reserve(static_cast<size_t>(lora_count));
    for (size_t index = 0; index < loras.size(); ++index) {
        LoraAdapterSpec adapter = parse_lora_specification(loras[index]);
        if (!lora_ids.insert(adapter.id).second || !lora_paths.insert(adapter.path).second) {
            throw_invalid_lora("loras", "LoRA ids and paths must be unique per request");
        }
        spec.loras.push_back(std::move(adapter));
    }
    return spec;
}

void parse_lora_specifications(
    const nlohmann::json& params,
    std::string& lora_root_path,
    std::vector<LoraAdapterSpec>& loras) {
    LoraContractSpec spec = parse_lora_specifications(params);
    lora_root_path = std::move(spec.lora_root_path);
    loras = std::move(spec.loras);
}

bool validate_lora_file(const std::string& raw_root,
                        const LoraAdapterSpec& adapter,
                        std::string& canonical_path,
                        std::string& error) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr ||
        !dir_exists(root_buffer)) {
        error = "LoRA root does not exist";
        return false;
    }
    struct stat link_stat {};
    if (adapter.path.empty() || lstat(adapter.path.c_str(), &link_stat) != 0 ||
        !S_ISREG(link_stat.st_mode)) {
        error = "LoRA must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size < 0 ||
        static_cast<uint64_t>(link_stat.st_size) != adapter.size_bytes) {
        error = "LoRA size differs from the worker-verified contract";
        return false;
    }
    if (realpath(adapter.path.c_str(), path_buffer) == nullptr || !file_exists(path_buffer)) {
        error = "LoRA file does not exist";
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path != adapter.path) {
        error = "LoRA path must already be canonical";
        return false;
    }
    const size_t separator = path.find_last_of('/');
    if (separator == std::string::npos || path.substr(0, separator) != root) {
        error = "LoRA must be a direct child of the app-owned LoRA root";
        return false;
    }
    const std::string lower = lower_copy(path);
    if (!ends_with(lower, ".safetensors") && !ends_with(lower, ".ckpt")) {
        error = "LoRA file extension is unsupported";
        return false;
    }
    canonical_path = path;
    return true;
}

bool validate_lora_files(const std::string& raw_root,
                         const std::vector<LoraAdapterSpec>& loras,
                         std::vector<std::string>& canonical_paths,
                         std::string& error) {
    canonical_paths.clear();
    canonical_paths.reserve(loras.size());
    for (const auto& adapter : loras) {
        std::string canonical_path;
        if (!validate_lora_file(raw_root, adapter, canonical_path, error)) {
            canonical_paths.clear();
            return false;
        }
        canonical_paths.push_back(std::move(canonical_path));
    }
    return true;
}

std::vector<sd_lora_t> build_native_loras(
    const std::vector<LoraAdapterSpec>& loras,
    const std::vector<std::string>& canonical_paths) {
    if (loras.size() != canonical_paths.size()) {
        throw std::invalid_argument("loras and canonical_paths size mismatch");
    }
    std::vector<sd_lora_t> native_loras;
    native_loras.reserve(loras.size());
    for (size_t index = 0; index < loras.size(); ++index) {
        sd_lora_t native_lora{};
        native_lora.is_high_noise = false;
        native_lora.multiplier = static_cast<float>(loras[index].multiplier);
        native_lora.path = canonical_paths[index].c_str();
        native_loras.push_back(native_lora);
    }
    return native_loras;
}

bool verify_lora_execution_evidence(
    size_t expected_lora_count,
    const sd_image_execution_evidence_t& evidence,
    std::string& error_code,
    std::string& error_message) {
    const uint32_t expected = static_cast<uint32_t>(expected_lora_count);
    if (evidence.lora_requested_count != expected) {
        error_code = "LORA_NATIVE_REQUEST_MISMATCH";
        error_message = "stable-diffusion.cpp did not receive the complete requested LoRA set";
        return false;
    }
    if (evidence.lora_loaded_count != expected) {
        error_code = "LORA_NATIVE_LOAD_INCOMPLETE";
        error_message = "stable-diffusion.cpp could not load every requested LoRA adapter";
        return false;
    }
    if (evidence.lora_applied_count != expected) {
        error_code = "LORA_NATIVE_APPLY_INCOMPLETE";
        error_message = "stable-diffusion.cpp did not apply every requested LoRA adapter to the executed graph";
        return false;
    }
    if (expected > 0u && evidence.lora_applied_tensor_count == 0u) {
        error_code = "LORA_NATIVE_TENSOR_EVIDENCE_MISSING";
        error_message = "stable-diffusion.cpp did not execute any tensor adapted by the requested LoRA set";
        return false;
    }
    if (expected == 0u && evidence.lora_applied_tensor_count != 0u) {
        error_code = "LORA_NATIVE_UNEXPECTED_EVIDENCE";
        error_message = "stable-diffusion.cpp reported applied LoRA tensors for a request without LoRA adapters";
        return false;
    }
    return true;
}

bool verify_lora_execution_evidence(
    size_t expected_lora_count,
    const LoraExecutionEvidence& evidence,
    std::string& error_code,
    std::string& error_message) {
    sd_image_execution_evidence_t native_ev{};
    native_ev.lora_requested_count = evidence.requested_count;
    native_ev.lora_loaded_count = evidence.loaded_count;
    native_ev.lora_applied_count = evidence.applied_count;
    native_ev.lora_applied_tensor_count = evidence.applied_tensor_count;
    return verify_lora_execution_evidence(expected_lora_count, native_ev, error_code, error_message);
}

nlohmann::json format_lora_effective_json(
    const std::vector<LoraAdapterSpec>& loras,
    const std::vector<sd_lora_t>& native_loras) {
    nlohmann::json array = nlohmann::json::array();
    const size_t count = std::min(loras.size(), native_loras.size());
    for (size_t index = 0; index < count; ++index) {
        array.push_back({
            {"id", loras[index].id},
            {"sha256", loras[index].sha256},
            {"multiplier", static_cast<double>(native_loras[index].multiplier)}
        });
    }
    return array;
}

nlohmann::json format_lora_evidence_json(
    const sd_image_execution_evidence_t& evidence) {
    return {
        {"requestedCount", evidence.lora_requested_count},
        {"loadedCount", evidence.lora_loaded_count},
        {"appliedCount", evidence.lora_applied_count},
        {"appliedTensorCount", evidence.lora_applied_tensor_count}
    };
}

nlohmann::json format_lora_evidence_json(
    const LoraExecutionEvidence& evidence) {
    return {
        {"requestedCount", evidence.requested_count},
        {"loadedCount", evidence.loaded_count},
        {"appliedCount", evidence.applied_count},
        {"appliedTensorCount", evidence.applied_tensor_count}
    };
}

} // namespace sd
} // namespace mca
