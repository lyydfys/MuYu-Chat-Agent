#include "qnn_bundle_manifest.hpp"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <iomanip>
#include <limits>
#include <sstream>

#include "mca/qnn/qnn_bundle_files.hpp"

namespace mca {
namespace qnn {

BundleProbe inspect_bundle_internal(const std::string& root) {
    BundleProbe probe;
    probe.root_present = exists_dir(root);
    if (!probe.root_present) return probe;

    auto files = list_files_recursive(root);
    probe.file_count = static_cast<int>(files.size());
    for (const auto& path : files) {
        if (probe.sample_files.size() < 12) probe.sample_files.push_back(path);
        const std::string lower_path = [&] {
            std::string lower = path;
            std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            return lower;
        }();
        if (ends_with(lower_path, "/manifest.json")) probe.manifest_present = true;
        if (ends_with(lower_path, ".ctx") || ends_with(lower_path, ".bin") ||
            contains_lower(lower_path, "context") || contains_lower(lower_path, "qnn")) {
            probe.has_graph_artifact = true;
        }
        if (contains_lower(lower_path, "tokenizer") || ends_with(lower_path, "vocab.json") ||
            ends_with(lower_path, "merges.txt")) {
            probe.has_tokenizer = true;
        }
    }
    return probe;
}

std::string bundle_probe_json(const BundleProbe& probe) {
    std::ostringstream out;
    out << "{"
        << "\"rootPresent\":" << (probe.root_present ? "true" : "false") << ","
        << "\"manifestPresent\":" << (probe.manifest_present ? "true" : "false") << ","
        << "\"hasGraphArtifact\":" << (probe.has_graph_artifact ? "true" : "false") << ","
        << "\"hasTokenizer\":" << (probe.has_tokenizer ? "true" : "false") << ","
        << "\"fileCount\":" << probe.file_count << ","
        << "\"sampleFiles\":" << files_json(probe.sample_files)
        << "}";
    return out.str();
}

int count_objects_in_array(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return 0;
    const auto array_start = json.find('[', key_pos);
    if (array_start == std::string::npos) return 0;
    int depth = 0;
    int object_count = 0;
    bool in_string = false;
    bool escape = false;
    for (size_t i = array_start; i < json.size(); ++i) {
        const char c = json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '[') {
            ++depth;
            continue;
        }
        if (c == ']') {
            --depth;
            if (depth == 0) break;
            continue;
        }
        if (c == '{' && depth == 1) {
            ++object_count;
        }
    }
    return object_count;
}

std::string string_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    const auto first_quote = json.find('"', colon + 1);
    if (first_quote == std::string::npos) return "";
    std::string out;
    bool escape = false;
    for (size_t i = first_quote + 1; i < json.size(); ++i) {
        const char c = json[i];
        if (escape) {
            out.push_back(c);
            escape = false;
            continue;
        }
        if (c == '\\') {
            escape = true;
            continue;
        }
        if (c == '"') break;
        out.push_back(c);
    }
    return out;
}

bool bool_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return false;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return false;
    const auto value_start = json.find_first_not_of(" \t\r\n", colon + 1);
    return value_start != std::string::npos && json.compare(value_start, 4, "true") == 0;
}

long long long_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return 0;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return 0;
    const auto value_start = json.find_first_of("-0123456789", colon + 1);
    if (value_start == std::string::npos) return 0;
    size_t value_end = value_start;
    while (value_end < json.size() && (json[value_end] == '-' || std::isdigit(static_cast<unsigned char>(json[value_end])))) {
        ++value_end;
    }
    return std::atoll(json.substr(value_start, value_end - value_start).c_str());
}

double double_field(const std::string& json, const std::string& key, double fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    const auto value_start = json.find_first_of("-0123456789.", colon + 1);
    if (value_start == std::string::npos) return fallback;
    size_t value_end = value_start;
    while (value_end < json.size()) {
        const char c = json[value_end];
        if (!(c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' ||
              std::isdigit(static_cast<unsigned char>(c)))) {
            break;
        }
        ++value_end;
    }
    char* end = nullptr;
    const double value = std::strtod(json.substr(value_start, value_end - value_start).c_str(), &end);
    return end == nullptr ? fallback : value;
}

std::string json_value_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    const auto value_start = json.find_first_not_of(" \t\r\n", colon + 1);
    if (value_start == std::string::npos) return "";

    const char first = json[value_start];
    if (first != '{' && first != '[') return "";

    int depth = 0;
    bool in_string = false;
    bool escape = false;
    for (size_t i = value_start; i < json.size(); ++i) {
        const char c = json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '{' || c == '[') {
            ++depth;
            continue;
        }
        if (c == '}' || c == ']') {
            --depth;
            if (depth == 0) {
                return json.substr(value_start, i - value_start + 1);
            }
        }
    }
    return "";
}

std::string json_array_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    const auto value_start = json.find_first_not_of(" \t\r\n", colon + 1);
    if (value_start == std::string::npos || json[value_start] != '[') return "";

    int depth = 0;
    bool in_string = false;
    bool escape = false;
    for (size_t i = value_start; i < json.size(); ++i) {
        const char c = json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '[') {
            ++depth;
            continue;
        }
        if (c == ']') {
            --depth;
            if (depth == 0) {
                return json.substr(value_start, i - value_start + 1);
            }
        }
    }
    return "";
}

std::vector<std::string> split_top_level_objects(const std::string& array_json) {
    std::vector<std::string> objects;
    int depth = 0;
    bool in_string = false;
    bool escape = false;
    size_t object_start = std::string::npos;
    for (size_t i = 0; i < array_json.size(); ++i) {
        const char c = array_json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '{') {
            if (depth == 0) object_start = i;
            ++depth;
            continue;
        }
        if (c == '}') {
            --depth;
            if (depth == 0 && object_start != std::string::npos) {
                objects.push_back(array_json.substr(object_start, i - object_start + 1));
                object_start = std::string::npos;
            }
        }
    }
    return objects;
}

std::vector<uint32_t> uint_array_field(const std::string& json, const std::string& key) {
    std::vector<uint32_t> values;
    const std::string array = json_array_field(json, key);
    if (array.empty()) return values;
    size_t pos = 0;
    while (pos < array.size()) {
        const auto digit = array.find_first_of("0123456789", pos);
        if (digit == std::string::npos) break;
        size_t end = digit;
        while (end < array.size() && std::isdigit(static_cast<unsigned char>(array[end]))) {
            ++end;
        }
        const auto value = std::strtoull(array.substr(digit, end - digit).c_str(), nullptr, 10);
        if (value > 0 && value <= std::numeric_limits<uint32_t>::max()) {
            values.push_back(static_cast<uint32_t>(value));
        }
        pos = end;
    }
    return values;
}

NativeTensorPlan tensor_plan_from_json(const std::string& json, const std::string& fallback_role) {
    NativeTensorPlan plan;
    plan.name = string_field(json, "name");
    plan.role = string_field(json, "role");
    if (plan.role.empty()) plan.role = fallback_role;
    plan.data_type = string_field(json, "dataType");
    plan.shape = uint_array_field(json, "shape");
    plan.element_count = long_field(json, "elementCount");
    plan.bytes_per_element = static_cast<int>(long_field(json, "bytesPerElement"));
    plan.byte_size = long_field(json, "byteSize");
    plan.supported = bool_field(json, "supported");
    plan.reason = string_field(json, "reason");
    return plan;
}

std::vector<NativeTensorPlan> tensor_plans_from_buffer_plan(
        const std::string& smoke_spec_json,
        const std::string& key,
        const std::string& fallback_role) {
    std::vector<NativeTensorPlan> plans;
    const std::string buffer_plan = json_value_field(smoke_spec_json, "bufferPlan");
    if (buffer_plan.empty()) return plans;
    const std::string array = json_array_field(buffer_plan, key);
    if (array.empty()) return plans;
    for (const auto& object : split_top_level_objects(array)) {
        plans.push_back(tensor_plan_from_json(object, fallback_role));
    }
    return plans;
}

SmokeSpecProbe inspect_smoke_spec(const std::string& smoke_spec_json) {
    SmokeSpecProbe probe;
    probe.present = smoke_spec_json.find('{') != std::string::npos;
    probe.graph_name = string_field(smoke_spec_json, "graphName");
    probe.context_binary = string_field(smoke_spec_json, "contextBinary");
    probe.expected_context_size_bytes = long_field(
        smoke_spec_json, "expectedContextSizeBytes");
    probe.expected_context_sha256 = string_field(
        smoke_spec_json, "expectedContextSha256");
    const bool identity_size_present = probe.expected_context_size_bytes != 0;
    const bool identity_sha_present = !probe.expected_context_sha256.empty();
    const bool identity_sha_valid = probe.expected_context_sha256.size() == 64U &&
        std::all_of(
            probe.expected_context_sha256.begin(),
            probe.expected_context_sha256.end(),
            [](unsigned char value) {
                return (value >= '0' && value <= '9') ||
                    (value >= 'a' && value <= 'f');
            });
    probe.context_identity_required = identity_size_present || identity_sha_present;
    const bool context_identity_valid = !probe.context_identity_required ||
        (probe.expected_context_size_bytes > 0 && identity_sha_present && identity_sha_valid);
    probe.buffer_plan_json = json_value_field(smoke_spec_json, "bufferPlan");
    probe.validation_json = json_value_field(smoke_spec_json, "validation");
    probe.input_count = count_objects_in_array(smoke_spec_json, "inputs");
    probe.output_count = count_objects_in_array(smoke_spec_json, "outputs");
    probe.tensor_buffer_plan_ready = bool_field(smoke_spec_json, "tensorBufferPlanReady");
    probe.validation_ready = !probe.validation_json.empty() &&
        bool_field(probe.validation_json, "readyForNativeSmoke");
    probe.input_buffer_bytes = long_field(smoke_spec_json, "inputBufferBytes");
    probe.output_buffer_bytes = long_field(smoke_spec_json, "outputBufferBytes");
    probe.complete = bool_field(smoke_spec_json, "completeForGraphSmoke") &&
        (probe.validation_json.empty() || probe.validation_ready) &&
        context_identity_valid;
    return probe;
}

std::string smoke_spec_probe_json(const SmokeSpecProbe& probe) {
    std::ostringstream out;
    out << "{"
        << "\"present\":" << (probe.present ? "true" : "false") << ","
        << "\"complete\":" << (probe.complete ? "true" : "false") << ","
        << "\"graphName\":" << quote(probe.graph_name) << ","
        << "\"contextBinary\":" << quote(probe.context_binary) << ","
        << "\"contextBinaryBytes\":" << probe.context_binary_bytes << ","
        << "\"expectedContextSizeBytes\":" << probe.expected_context_size_bytes << ","
        << "\"expectedContextSha256\":" << quote(probe.expected_context_sha256) << ","
        << "\"contextIdentityRequired\":"
        << (probe.context_identity_required ? "true" : "false") << ","
        << "\"contextIdentityMatched\":"
        << (probe.context_identity_matched ? "true" : "false") << ","
        << "\"metadataContractMatched\":"
        << (probe.metadata_contract_matched ? "true" : "false") << ","
        << "\"outputValuesFinite\":"
        << (probe.output_values_finite ? "true" : "false") << ","
        << "\"outputValidationPassed\":"
        << (probe.output_validation_passed ? "true" : "false") << ","
        << "\"nonZeroOutputElements\":" << probe.nonzero_output_elements << ","
        << "\"inputCount\":" << probe.input_count << ","
        << "\"outputCount\":" << probe.output_count << ","
        << "\"tensorBufferPlanReady\":" << (probe.tensor_buffer_plan_ready ? "true" : "false") << ","
        << "\"validationReady\":" << (probe.validation_ready ? "true" : "false") << ","
        << "\"inputBufferBytes\":" << probe.input_buffer_bytes << ","
        << "\"outputBufferBytes\":" << probe.output_buffer_bytes << ","
        << "\"bufferPlan\":" << (probe.buffer_plan_json.empty() ? "{}" : probe.buffer_plan_json) << ","
        << "\"validation\":" << (probe.validation_json.empty() ? "{}" : probe.validation_json)
        << "}";
    return out.str();
}

std::string smoke_stage_json(
        const RuntimeProbe& runtime,
        const BundleProbe& bundle,
        const SmokeSpecProbe& smoke_spec,
        bool context_binary_present,
        bool graph_metadata_ready,
        bool backend_created,
        bool context_loaded,
        bool graph_resolved,
        bool tensors_bound,
        bool graph_executed) {
    std::ostringstream out;
    out << "{"
        << "\"runtimeLoaded\":" << (runtime.loadable ? "true" : "false") << ","
        << "\"qnnInterfaceFound\":" << (runtime.qnn_interface_present ? "true" : "false") << ","
        << "\"bundleRootFound\":" << (bundle.root_present ? "true" : "false") << ","
        << "\"bundleManifestFound\":" << (bundle.manifest_present ? "true" : "false") << ","
        << "\"bundleGraphArtifactFound\":" << (bundle.has_graph_artifact ? "true" : "false") << ","
        << "\"bundleContextBinaryFound\":" << (context_binary_present ? "true" : "false") << ","
        << "\"bundleContextBinaryNonEmpty\":" << (smoke_spec.context_binary_bytes > 0 ? "true" : "false") << ","
        << "\"smokeMetadataComplete\":" << (smoke_spec.complete ? "true" : "false") << ","
        << "\"tensorBufferPlanReady\":" << (smoke_spec.tensor_buffer_plan_ready ? "true" : "false") << ","
        << "\"graphMetadataReady\":" << (graph_metadata_ready ? "true" : "false") << ","
        << "\"sdkHeadersCompiled\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
        << "\"backendCreated\":" << (backend_created ? "true" : "false") << ","
        << "\"contextLoaded\":" << (context_loaded ? "true" : "false") << ","
        << "\"graphResolved\":" << (graph_resolved ? "true" : "false") << ","
        << "\"tensorsBound\":" << (tensors_bound ? "true" : "false") << ","
        << "\"graphExecuted\":" << (graph_executed ? "true" : "false")
        << "}";
    return out.str();
}

} // namespace qnn
} // namespace mca
