#ifndef MCA_QNN_BUNDLE_MANIFEST_HPP
#define MCA_QNN_BUNDLE_MANIFEST_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"

namespace mca {
namespace qnn {

/**
 * Inspection probe of candidate QNN bundle directory structure.
 */
struct BundleProbe {
    bool root_present = false;
    bool manifest_present = false;
    bool has_graph_artifact = false;
    bool has_tokenizer = false;
    int file_count = 0;
    std::vector<std::string> sample_files;
};

BundleProbe inspect_bundle_internal(const std::string& root);
std::string bundle_probe_json(const BundleProbe& probe);

// JSON inspection helpers
int count_objects_in_array(const std::string& json, const std::string& key);
std::string string_field(const std::string& json, const std::string& key);
bool bool_field(const std::string& json, const std::string& key);
long long long_field(const std::string& json, const std::string& key);
double double_field(const std::string& json, const std::string& key, double fallback = 0.0);
std::string json_value_field(const std::string& json, const std::string& key);
std::string json_array_field(const std::string& json, const std::string& key);
std::vector<std::string> split_top_level_objects(const std::string& array_json);
std::vector<uint32_t> uint_array_field(const std::string& json, const std::string& key);

/**
 * Inspection probe of smoke testing specification for a bundle graph.
 */
struct SmokeSpecProbe {
    bool present = false;
    bool complete = false;
    bool tensor_buffer_plan_ready = false;
    bool validation_ready = false;
    bool context_identity_required = false;
    bool context_identity_matched = false;
    bool metadata_contract_matched = false;
    bool output_values_finite = false;
    bool output_validation_passed = false;
    std::string graph_name;
    std::string context_binary;
    std::string expected_context_sha256;
    std::string buffer_plan_json;
    std::string validation_json;
    long long context_binary_bytes = 0;
    long long expected_context_size_bytes = 0;
    long long nonzero_output_elements = 0;
    int input_count = 0;
    int output_count = 0;
    long long input_buffer_bytes = 0;
    long long output_buffer_bytes = 0;
};

SmokeSpecProbe inspect_smoke_spec(const std::string& smoke_spec_json);
std::string smoke_spec_probe_json(const SmokeSpecProbe& probe);

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
    bool graph_executed);

NativeTensorPlan tensor_plan_from_json(
    const std::string& json,
    const std::string& fallback_role = "");

std::vector<NativeTensorPlan> tensor_plans_from_buffer_plan(
    const std::string& smoke_spec_json,
    const std::string& key,
    const std::string& fallback_role = "");

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_BUNDLE_MANIFEST_HPP
