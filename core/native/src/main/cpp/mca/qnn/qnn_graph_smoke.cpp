// mca/qnn/qnn_graph_smoke.cpp -- run_typed_qnn_graph_smoke typed graph execution smoke.
// (T2B14 / R7-C3 split).  Relocated byte-exact from mca/qnn/qnn_executable_graph.cpp (lines 1212-1685).

#include "qnn_executable_graph.hpp"

#include <algorithm>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <limits>
#include <utility>

#include "mca/common/logging.hpp"
#include "mca/qnn/qnn_device_support.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include <android/log.h>
#include <dirent.h>
#include "qnn_soc_htp_mapping.hpp"
#include "qnn_htp_runtime_candidates.hpp"

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK


GraphSmokeResult run_typed_qnn_graph_smoke(
        const RuntimeProbe& runtime,
        const SmokeSpecProbe& smoke_spec,
        const std::string& context_binary_path,
        const std::string& smoke_spec_json) {
    GraphSmokeResult result;
    result.attempted = true;
    result.execution_stage = "graph_execution_starting";
    result.execution_runtime = runtime;

    const std::vector<uint8_t> context_binary = read_binary_file(context_binary_path);
    if (context_binary.empty()) {
        result.execution_stage = "context_binary_read_failed";
        result.message = "QNN context binary could not be read or is empty.";
        return result;
    }
    if (smoke_spec.context_identity_required &&
        (context_binary.size() !=
             static_cast<size_t>(smoke_spec.expected_context_size_bytes) ||
         mca::qnn::controlnet::sha256_hex_bytes(context_binary) !=
             smoke_spec.expected_context_sha256)) {
        result.execution_stage = "context_identity_mismatch";
        result.message =
            "The exact QNN context bytes loaded for smoke differ from the pinned package identity.";
        return result;
    }

    RuntimeProbe selected_runtime = runtime;
    uint32_t context_soc_model = 0;
    if (runtime.system_present) {
        const QnnBinaryMetadata metadata =
            inspect_qnn_context_binary_metadata(runtime, context_binary);
        if (metadata.release_failed) {
            result.resource_release_failed = true;
            result.execution_stage = "runtime_resource_release_failed";
            result.message = metadata.message;
            return result;
        }
        context_soc_model = metadata.soc_model;
    }
    if (!select_qnn_runtime_profile_for_context(&selected_runtime, context_soc_model)) {
        result.execution_runtime = selected_runtime;
        result.execution_stage = "runtime_profile_missing";
        result.message = selected_runtime.message;
        return result;
    }
    result.execution_runtime = selected_runtime;
    result.execution_runtime_selected = true;

    auto loaded = load_qnn_runtime_for_graph(selected_runtime);
    const auto finish_loaded_only = [&]() -> GraphSmokeResult {
        std::string release_error;
        if (!loaded.close_checked(&release_error)) {
            result.resource_release_failed = true;
            const std::string primary_error = result.message;
            result.execution_stage = "runtime_resource_release_failed";
            result.message = release_error.empty()
                ? "QNN graph smoke runtime libraries did not release cleanly."
                : release_error;
            if (!primary_error.empty()) {
                result.message = primary_error + " Cleanup also failed: " + result.message;
            }
        }
        return result;
    };
    if (!loaded.ok) {
        result.execution_stage = "runtime_load_failed";
        result.message = loaded.message;
        return finish_loaded_only();
    }

    std::string provider_error;
    const QnnInterface_t* provider = select_qnn_interface(loaded.get_providers, &provider_error);
    if (provider == nullptr) {
        result.execution_stage = "qnn_provider_missing";
        result.message = provider_error;
        return finish_loaded_only();
    }
    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    QnnLogSession log_session = create_qnn_log_session(api);

    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    const QNN_SYSTEM_INTERFACE_VER_TYPE* sys_api_ptr = nullptr;
    QnnSystemContext_Handle_t sys_context = nullptr;
    bool cleanup_done = false;
    const auto cleanup = [&]() -> bool {
        if (cleanup_done) return !result.resource_release_failed;
        cleanup_done = true;
        bool ok = true;
        bool safe_to_unload = true;
        std::string failures;
        const auto record_failure = [&](const std::string& detail) {
            ok = false;
            safe_to_unload = false;
            if (!detail.empty()) {
                if (!failures.empty()) failures += "; ";
                failures += detail;
            }
        };

        if (sys_context != nullptr) {
            if (sys_api_ptr == nullptr || sys_api_ptr->systemContextFree == nullptr) {
                record_failure(
                    "QnnSystemContext_free is unavailable for a live smoke metadata context.");
            } else {
                const Qnn_ErrorHandle_t status = sys_api_ptr->systemContextFree(sys_context);
                if (status != QNN_SUCCESS) {
                    std::ostringstream detail;
                    detail << "QnnSystemContext_free failed: 0x" << std::hex << status;
                    record_failure(detail.str());
                } else {
                    sys_context = nullptr;
                }
            }
        }
        bool qnn_objects_released = true;
        if (context != nullptr) {
            if (api.contextFree == nullptr) {
                record_failure("QNN contextFree is unavailable for a live smoke context handle.");
                qnn_objects_released = false;
            } else {
                const Qnn_ErrorHandle_t status = api.contextFree(context, nullptr);
                if (status != QNN_SUCCESS) {
                    record_failure(
                        "QNN contextFree failed: " + qnn_error_to_string(api, status));
                    qnn_objects_released = false;
                } else {
                    context = nullptr;
                }
            }
        }
        if (qnn_objects_released && device != nullptr) {
            if (api.deviceFree == nullptr) {
                record_failure("QNN deviceFree is unavailable for a live smoke device handle.");
                qnn_objects_released = false;
            } else {
                const Qnn_ErrorHandle_t status = api.deviceFree(device);
                if (status != QNN_SUCCESS) {
                    record_failure(
                        "QNN deviceFree failed: " + qnn_error_to_string(api, status));
                    qnn_objects_released = false;
                } else {
                    device = nullptr;
                }
            }
        }
        if (qnn_objects_released && backend != nullptr) {
            if (api.backendFree == nullptr) {
                record_failure("QNN backendFree is unavailable for a live smoke backend handle.");
                qnn_objects_released = false;
            } else {
                const Qnn_ErrorHandle_t status = api.backendFree(backend);
                if (status != QNN_SUCCESS) {
                    record_failure(
                        "QNN backendFree failed: " + qnn_error_to_string(api, status));
                    qnn_objects_released = false;
                } else {
                    backend = nullptr;
                }
            }
        }
        if (qnn_objects_released) {
            std::string log_error;
            if (!log_session.reset_checked(&log_error)) {
                record_failure(log_error.empty() ? "QNN log release failed." : log_error);
                log_session.abandon_without_release();
            }
        } else {
            log_session.abandon_without_release();
        }
        if (safe_to_unload) {
            std::string unload_error;
            if (!loaded.close_checked(&unload_error)) {
                record_failure(unload_error.empty()
                    ? "QNN graph smoke runtime library unload failed."
                    : unload_error);
            }
        } else {
            loaded.abandon_without_unload();
        }
        if (!ok) {
            g_qnn_runtime_poisoned.store(true);
            result.resource_release_failed = true;
            const std::string primary_error = result.message;
            result.execution_stage = "runtime_resource_release_failed";
            result.message = failures.empty()
                ? "QNN graph smoke resources did not release cleanly."
                : failures;
            if (!primary_error.empty()) {
                result.message = primary_error + " Cleanup also failed: " + result.message;
            }
        }
        return ok;
    };
    const auto finish = [&]() -> GraphSmokeResult {
        cleanup();
        return result;
    };

    const Qnn_ErrorHandle_t backend_status = api.backendCreate(log_session.handle, nullptr, &backend);
    if (backend_status != QNN_SUCCESS || backend == nullptr) {
        result.execution_stage = "backend_create_failed";
        result.message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
        return finish();
    }
    result.backend_created = true;

    const QnnDeviceCreateOutcome device_outcome =
        create_qnn_device_or_default(api, context_soc_model, log_session.handle);
    device = device_outcome.device;
    result.device_created = device_outcome.created;
    result.context_default_device = device_outcome.context_default_device;
    result.device_message = device_outcome.message;
    if (device_outcome.fatal) {
        result.execution_stage = "device_create_failed";
        result.message = device_outcome.message;
        return finish();
    }

    const auto context_start = std::chrono::steady_clock::now();
    const Qnn_ErrorHandle_t context_status = api.contextCreateFromBinary(
        backend,
        device,
        nullptr,
        context_binary.data(),
        static_cast<Qnn_ContextBinarySize_t>(context_binary.size()),
        &context,
        nullptr
    );
    if (context_status != QNN_SUCCESS || context == nullptr) {
        result.execution_stage = "context_load_failed";
        result.message = append_diagnostic_note(
            qnn_context_load_failure_message(api, context_status),
            result.device_message);
        return finish();
    }
    const auto context_end = std::chrono::steady_clock::now();
    result.context_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        context_end - context_start
    ).count();
    result.context_loaded = true;

    Qnn_GraphHandle_t graph = nullptr;
    const Qnn_ErrorHandle_t graph_status = api.graphRetrieve(
        context,
        smoke_spec.graph_name.c_str(),
        &graph
    );
    if (graph_status != QNN_SUCCESS || graph == nullptr) {
        result.execution_stage = "graph_resolve_failed";
        result.message = "QNN graphRetrieve failed for '" + smoke_spec.graph_name +
            "': " + qnn_error_to_string(api, graph_status);
        return finish();
    }
    result.graph_resolved = true;

#if !defined(_WIN32)
    auto get_system_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(loaded.system_handle, "QnnSystemInterface_getProviders")
    );
#else
    auto get_system_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        GetProcAddress(reinterpret_cast<HMODULE>(loaded.system_handle), "QnnSystemInterface_getProviders")
    );
#endif
    if (get_system_providers == nullptr) {
        result.execution_stage = "qnn_system_provider_missing";
        result.message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        return finish();
    }

    std::string system_provider_error;
    const QnnSystemInterface_t* system_provider =
        select_qnn_system_interface(get_system_providers, &system_provider_error);
    if (system_provider == nullptr) {
        result.execution_stage = "qnn_system_provider_missing";
        result.message = system_provider_error;
        return finish();
    }
    sys_api_ptr = &system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
    const auto& sys_api = *sys_api_ptr;
    Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&sys_context);
    if (system_status != QNN_SUCCESS || sys_context == nullptr) {
        std::ostringstream message;
        message << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
        result.execution_stage = "qnn_metadata_context_create_failed";
        result.message = message.str();
        return finish();
    }

    const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
    if (sys_api.systemContextGetMetaData != nullptr) {
        system_status = sys_api.systemContextGetMetaData(
            sys_context,
            context_binary.data(),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info
        );
    } else {
        Qnn_ContextBinarySize_t binary_info_size = 0;
        system_status = sys_api.systemContextGetBinaryInfo(
            sys_context,
            const_cast<uint8_t*>(context_binary.data()),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info,
            &binary_info_size
        );
    }
    if (system_status != QNN_SUCCESS || binary_info == nullptr) {
        std::ostringstream message;
        message << "QnnSystemContext metadata parse failed: 0x" << std::hex << system_status;
        result.execution_stage = "qnn_metadata_parse_failed";
        result.message = message.str();
        return finish();
    }

    QnnSystemContext_GraphInfo_t* graphs = qnn_binary_graphs(*binary_info);
    const uint32_t graph_count = qnn_binary_graph_count(*binary_info);
    QnnGraphMetadata graph_meta;
    bool graph_meta_found = false;
    for (uint32_t i = 0; i < graph_count; ++i) {
        QnnGraphMetadata candidate = qnn_graph_metadata(graphs[i]);
        if (candidate.name != nullptr && smoke_spec.graph_name == candidate.name) {
            graph_meta = candidate;
            graph_meta_found = true;
            break;
        }
    }
    if (!graph_meta_found ||
        graph_meta.inputs == nullptr ||
        graph_meta.outputs == nullptr ||
        graph_meta.input_count == 0 ||
        graph_meta.output_count == 0) {
        result.execution_stage = "qnn_graph_metadata_missing";
        result.message = "QNN context metadata did not expose bindable graph inputs and outputs.";
        return finish();
    }

    const auto input_plans = tensor_plans_from_buffer_plan(
        smoke_spec_json, "inputs", "input");
    const auto output_plans = tensor_plans_from_buffer_plan(
        smoke_spec_json, "outputs", "output");
    std::string tensor_contract_error;
    if (!qnn_smoke_tensor_set_matches_plans(
            graph_meta.inputs,
            graph_meta.input_count,
            input_plans,
            "input",
            &tensor_contract_error) ||
        !qnn_smoke_tensor_set_matches_plans(
            graph_meta.outputs,
            graph_meta.output_count,
            output_plans,
            "output",
            &tensor_contract_error)) {
        result.execution_stage = "tensor_metadata_contract_mismatch";
        result.message = tensor_contract_error;
        return finish();
    }
    result.metadata_contract_matched = true;

    std::vector<Qnn_Tensor_t> inputs;
    std::vector<Qnn_Tensor_t> outputs;
    std::vector<std::vector<uint8_t>> input_buffers;
    std::vector<std::vector<uint8_t>> output_buffers;
    inputs.reserve(graph_meta.input_count);
    outputs.reserve(graph_meta.output_count);
    input_buffers.reserve(graph_meta.input_count);
    output_buffers.reserve(graph_meta.output_count);

    for (uint32_t i = 0; i < graph_meta.input_count; ++i) {
        Qnn_Tensor_t tensor = graph_meta.inputs[i];
        const uint64_t bytes = qnn_tensor_bytes(sys_api, tensor);
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            result.execution_stage = "tensor_bind_failed";
            result.message = "Invalid QNN metadata input tensor size for " +
                std::string(qnn_tensor_name(tensor) != nullptr ? qnn_tensor_name(tensor) : "<unnamed>");
            return finish();
        }
        input_buffers.emplace_back(static_cast<size_t>(bytes), 0);
        qnn_fill_smoke_input_buffer(tensor, &input_buffers.back());
        qnn_set_tensor_raw_buffer(
            &tensor,
            input_buffers.back().data(),
            static_cast<uint32_t>(input_buffers.back().size()));
        inputs.push_back(tensor);
    }
    for (uint32_t i = 0; i < graph_meta.output_count; ++i) {
        Qnn_Tensor_t tensor = graph_meta.outputs[i];
        const uint64_t bytes = qnn_tensor_bytes(sys_api, tensor);
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            result.execution_stage = "tensor_bind_failed";
            result.message = "Invalid QNN metadata output tensor size for " +
                std::string(qnn_tensor_name(tensor) != nullptr ? qnn_tensor_name(tensor) : "<unnamed>");
            return finish();
        }
        output_buffers.emplace_back(static_cast<size_t>(bytes), 0);
        qnn_set_tensor_raw_buffer(
            &tensor,
            output_buffers.back().data(),
            static_cast<uint32_t>(output_buffers.back().size()));
        outputs.push_back(tensor);
    }
    result.tensors_bound = true;

    const auto execute_start = std::chrono::steady_clock::now();
    const Qnn_ErrorHandle_t execute_status = api.graphExecute(
        graph,
        inputs.data(),
        static_cast<uint32_t>(inputs.size()),
        outputs.data(),
        static_cast<uint32_t>(outputs.size()),
        nullptr,
        nullptr
    );
    if (execute_status != QNN_SUCCESS) {
        result.execution_stage = "graph_execute_failed";
        result.message = "QNN graphExecute failed: " + qnn_error_to_string(api, execute_status);
        return finish();
    }
    const auto execute_end = std::chrono::steady_clock::now();
    result.graph_execute_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        execute_end - execute_start
    ).count();

    uint64_t output_checksum = 0;
    for (const auto& buffer : output_buffers) {
        for (uint8_t value : buffer) output_checksum += value;
    }
    result.output_checksum = output_checksum;
    result.graph_executed = true;
    std::string output_validation_error;
    result.output_values_finite = true;
    for (size_t index = 0; index < outputs.size(); ++index) {
        long long output_nonzero_elements = 0;
        if (!qnn_smoke_output_buffer_valid(
                outputs[index],
                output_buffers[index],
                &output_nonzero_elements,
                &output_validation_error)) {
            result.output_values_finite = false;
            break;
        }
        if (output_nonzero_elements == 0) {
            result.output_values_finite = false;
            output_validation_error = "QNN smoke output tensor " +
                std::string(qnn_tensor_name(outputs[index]) != nullptr
                    ? qnn_tensor_name(outputs[index])
                    : "<unnamed>") +
                " contains only zero values.";
            break;
        }
        result.nonzero_output_elements += output_nonzero_elements;
    }
    result.output_validation_passed = result.output_values_finite &&
        result.nonzero_output_elements > 0;
    if (result.output_validation_passed) {
        result.execution_stage = "graph_execute_passed";
        result.message = append_diagnostic_note(
            "QNN/HTP graphExecute completed with strict tensor/output proof. outputChecksum=" +
                std::to_string(output_checksum),
            result.device_message);
    } else {
        result.execution_stage = "graph_output_invalid";
        result.message = output_validation_error.empty()
            ? "QNN graphExecute returned only zero output elements."
            : output_validation_error;
    }
    return finish();
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
