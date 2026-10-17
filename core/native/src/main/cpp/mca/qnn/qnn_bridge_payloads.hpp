#ifndef MCA_QNN_BRIDGE_PAYLOADS_HPP
#define MCA_QNN_BRIDGE_PAYLOADS_HPP

// Payload builders relocated byte-exact out of qnn_native_bridge.cpp
// (T2B2-B1).  qnn_native_bridge.cpp keeps the JNI layer and the
// no-SDK stubs; these definitions compile into mca_qnn_runtime.

#include <string>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_bundle_manifest.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"

namespace mca {
namespace qnn {

std::string qnn_pipeline_probe_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& output_path);

std::string smoke_execution_stage(
        const RuntimeProbe& runtime,
        const BundleProbe& bundle,
        const SmokeSpecProbe& smoke_spec,
        bool context_binary_present);

std::string smoke_json(
        const char* kind,
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& smoke_spec_json);

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_BRIDGE_PAYLOADS_HPP
