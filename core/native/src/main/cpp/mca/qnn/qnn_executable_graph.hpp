#ifndef MCA_QNN_EXECUTABLE_GRAPH_HPP
#define MCA_QNN_EXECUTABLE_GRAPH_HPP

#include <chrono>
#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_htp_controller.hpp"
#include "mca/qnn/qnn_tensor_memory.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_bundle_manifest.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "qnn_image_stage_trace.hpp"
#include "qnn_controlnet_execution.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"

namespace mca {
namespace qnn {

bool qnn_consumed_payload_sha256(
        const void* payload,
        size_t payload_bytes,
        std::string* digest,
        std::string* error);

bool read_float_binary_file(
        const std::string& path,
        std::vector<float>* values,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr);

bool read_int32_binary_file(
        const std::string& path,
        std::vector<int32_t>* values,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr);

bool qnn_file_sha256(
        const std::string& path,
        std::string* digest,
        std::string* error);

struct GraphSmokeResult {
    bool attempted = false;
    bool backend_created = false;
    bool device_created = false;
    bool context_default_device = false;
    bool context_loaded = false;
    bool graph_resolved = false;
    bool tensors_bound = false;
    bool graph_executed = false;
    bool metadata_contract_matched = false;
    bool output_values_finite = false;
    bool output_validation_passed = false;
    bool resource_release_failed = false;
    long long context_load_ms = 0;
    long long graph_execute_ms = 0;
    long long nonzero_output_elements = 0;
    uint64_t output_checksum = 0;
    RuntimeProbe execution_runtime;
    bool execution_runtime_selected = false;
    std::string execution_stage;
    std::string device_message;
    std::string message;
};

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

struct QnnCoherentRuntimeSession {
    QnnLoadedRuntime loaded;
    RuntimeProbe selected_runtime;
    const QnnInterface_t* provider = nullptr;
    const QnnSystemInterface_t* system_provider = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    QnnLogSession log_session;
    bool device_created = false;
    bool context_default_device = false;
    uint32_t context_soc_model = 0;
    const QnnImageGenerationScope* generation = nullptr;
    std::string device_message;
    std::string message;
    bool poisoned = false;

    QnnCoherentRuntimeSession() = default;
    QnnCoherentRuntimeSession(const QnnCoherentRuntimeSession&) = delete;
    QnnCoherentRuntimeSession& operator=(const QnnCoherentRuntimeSession&) = delete;
    ~QnnCoherentRuntimeSession() { close(); }

    bool open(
            const RuntimeProbe& runtime,
            const std::string& first_context_path,
            const QnnImageGenerationScope* generation_scope);

    bool accepts_context(const RuntimeProbe& runtime, const ScopedReadOnlyMmap& binary);
    void mark_poisoned();
    bool close_checked(std::string* error);
    void close();
};

struct QnnExecutableGraph {
    QnnLoadedRuntime loaded;
    const QnnInterface_t* provider = nullptr;
    const QnnSystemInterface_t* system_provider = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    QnnSystemContext_Handle_t system_context = nullptr;
    QnnLogSession log_session;
    bool device_created = false;
    bool context_default_device = false;
    ScopedReadOnlyMmap context_binary;
    const QnnImageGenerationScope* generation = nullptr;
    QnnCoherentRuntimeSession* shared_session = nullptr;
    bool vae_graph = false;
    bool encoder_graph = false;
    RuntimeProbe selected_runtime;
    std::vector<QnnTensorBinding> inputs;
    std::vector<QnnTensorBinding> outputs;
    std::string graph_name;
    std::string context_binary_sha256;
    long long context_load_ms = 0;
    std::string device_message;
    std::string message;

    ~QnnExecutableGraph() { close(); }

    void close();
    bool close_checked(std::string* error);

    bool load_in_session(
            QnnCoherentRuntimeSession* session,
            const RuntimeProbe& runtime,
            const std::string& context_path,
            const std::string& requested_graph_name,
            bool is_vae,
            bool is_encoder = false,
            const std::string& expected_context_sha256 = "",
            long long expected_context_size_bytes = 0,
            dev_t expected_context_device = 0,
            ino_t expected_context_inode = 0);

    bool load(
            const RuntimeProbe& runtime,
            const std::string& context_path,
            const std::string& requested_graph_name,
            const QnnImageGenerationScope* generation_scope = nullptr,
            bool is_vae = false,
            bool is_encoder = false,
            const std::string& expected_context_sha256 = "",
            int preferred_htp_arch = 0);

    bool bind_tensors(
            const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
            Qnn_Tensor_t* source,
            uint32_t count,
            bool input,
            std::vector<QnnTensorBinding>* destination);

    bool execute(
            long long* execute_ms,
            std::string* error,
            bool preview_vae_execution = false);
};

GraphSmokeResult run_typed_qnn_graph_smoke(
        const RuntimeProbe& runtime,
        const SmokeSpecProbe& smoke_spec,
        const std::string& context_binary_path,
        const std::string& smoke_spec_json);

// Migrated from qnn_native_bridge.cpp (HEAD:598); it was never carried into a module.
bool select_qnn_runtime_profile_for_context(
        RuntimeProbe* runtime,
        uint32_t context_soc_model,
        int preferred_htp_arch = 0);

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_EXECUTABLE_GRAPH_HPP