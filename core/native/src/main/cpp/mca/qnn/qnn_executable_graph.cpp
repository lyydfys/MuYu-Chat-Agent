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

bool QnnExecutableGraph::load_in_session(
        QnnCoherentRuntimeSession* session,
        const RuntimeProbe& runtime,
        const std::string& context_path,
        const std::string& requested_graph_name,
        bool is_vae,
        bool is_encoder,
        const std::string& expected_context_sha256,
        long long expected_context_size_bytes,
        dev_t expected_context_device,
        ino_t expected_context_inode) {
    shared_session = session;
    generation = session != nullptr ? session->generation : nullptr;
    vae_graph = is_vae;
    encoder_graph = is_encoder;
    if (session == nullptr || session->provider == nullptr || session->backend == nullptr) {
        message = "QNN coherent runtime session is not ready.";
        return false;
    }
    provider = session->provider;
    system_provider = session->system_provider;
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderBinaryMmap
                : (is_vae
                    ? mca::qnn::ImageStage::VaeBinaryMmap
                    : mca::qnn::ImageStage::UnetBinaryMmap),
            kQnnImageContextBinaryMmap);
    }
    if (!context_binary.open_file(context_path, &message)) return false;
    if (!expected_context_sha256.empty()) {
        const bool expected_identity = expected_context_size_bytes > 0 &&
            static_cast<uint64_t>(context_binary.size()) ==
                static_cast<uint64_t>(expected_context_size_bytes);
#if !defined(_WIN32)
        const bool dev_ino_match =
            context_binary.device() == expected_context_device &&
            context_binary.inode() == expected_context_inode;
#else
        const bool dev_ino_match = true;
#endif
        context_binary_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            context_binary.data(), context_binary.size());
        if (!expected_identity || !dev_ino_match || context_binary_sha256.empty() ||
            context_binary_sha256 != expected_context_sha256) {
            message = "Mapped QNN context identity differs from the verified asset.";
            return false;
        }
    }
    if (!session->accepts_context(runtime, context_binary)) {
        message = session->message;
        return false;
    }

    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderContextCreateBefore
                : (is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateBefore
                    : mca::qnn::ImageStage::UnetContextCreateBefore),
            kQnnImageContextCreate);
    }
    const auto context_start = std::chrono::steady_clock::now();
    const Qnn_ErrorHandle_t context_status = api.contextCreateFromBinary(
        session->backend,
        session->device,
        nullptr,
        context_binary.data(),
        static_cast<Qnn_ContextBinarySize_t>(context_binary.size()),
        &context,
        nullptr);
    if (context_status != QNN_SUCCESS || context == nullptr) {
        message = append_diagnostic_note(
            qnn_context_load_failure_message(api, context_status),
            session->device_message);
        return false;
    }
    context_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - context_start).count();
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderContextCreateAfter
                : (is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateAfter
                    : mca::qnn::ImageStage::UnetContextCreateAfter),
            kQnnImageContextCreate);
    }

    const auto& sys_api = system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
    Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&system_context);
    if (system_status != QNN_SUCCESS || system_context == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
        message = out.str();
        return false;
    }
    const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
    if (sys_api.systemContextGetMetaData != nullptr) {
        system_status = sys_api.systemContextGetMetaData(
            system_context,
            context_binary.data(),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info);
    } else {
        Qnn_ContextBinarySize_t binary_info_size = 0;
        system_status = sys_api.systemContextGetBinaryInfo(
            system_context,
            const_cast<uint8_t*>(context_binary.data()),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info,
            &binary_info_size);
    }
    if (system_status != QNN_SUCCESS || binary_info == nullptr) {
        std::ostringstream out;
        out << "QNN metadata parse failed: 0x" << std::hex << system_status;
        message = out.str();
        return false;
    }

    QnnSystemContext_GraphInfo_t* graphs = qnn_binary_graphs(*binary_info);
    const uint32_t graph_count = qnn_binary_graph_count(*binary_info);
    QnnGraphMetadata graph_meta;
    bool graph_meta_found = false;
    for (uint32_t i = 0; i < graph_count; ++i) {
        QnnGraphMetadata candidate = qnn_graph_metadata(graphs[i]);
        if (candidate.name != nullptr &&
            (requested_graph_name.empty() || requested_graph_name == candidate.name)) {
            graph_meta = candidate;
            graph_meta_found = true;
            break;
        }
    }
    if (!graph_meta_found && graph_count > 0 && !is_encoder) {
        graph_meta = qnn_graph_metadata(graphs[0]);
        graph_meta_found = graph_meta.name != nullptr;
    }
    if (!graph_meta_found || graph_meta.inputs == nullptr || graph_meta.outputs == nullptr) {
        message = "QNN graph metadata missing.";
        return false;
    }
    graph_name = graph_meta.name != nullptr ? graph_meta.name : requested_graph_name;
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderGraphRetrieveBefore
                : (is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveBefore
                    : mca::qnn::ImageStage::UnetGraphRetrieveBefore),
            kQnnImageContextCreate);
    }
    const Qnn_ErrorHandle_t graph_status = api.graphRetrieve(context, graph_name.c_str(), &graph);
    if (graph_status != QNN_SUCCESS || graph == nullptr) {
        message = "QNN graphRetrieve failed for '" + graph_name + "': " +
            qnn_error_to_string(api, graph_status);
        return false;
    }
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderGraphRetrieveAfter
                : (is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveAfter
                    : mca::qnn::ImageStage::UnetGraphRetrieveAfter),
            kQnnImageContextCreate);
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderTensorBindBefore
                : (is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindBefore
                    : mca::qnn::ImageStage::UnetTensorBindBefore),
            kQnnImageContextCreate);
    }
    if (!bind_tensors(sys_api, graph_meta.inputs, graph_meta.input_count, true, &inputs) ||
        !bind_tensors(sys_api, graph_meta.outputs, graph_meta.output_count, false, &outputs)) {
        return false;
    }
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderTensorBindAfter
                : (is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindAfter
                    : mca::qnn::ImageStage::UnetTensorBindAfter),
            kQnnImageContextCreate);
    }
    return true;
}

bool QnnExecutableGraph::load(
        const RuntimeProbe& runtime,
        const std::string& context_path,
        const std::string& requested_graph_name,
        const QnnImageGenerationScope* generation_scope,
        bool is_vae,
        bool is_encoder,
        const std::string& expected_context_sha256,
        int preferred_htp_arch) {
    generation = generation_scope;
    vae_graph = is_vae;
    encoder_graph = is_encoder;
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderBinaryMmap
                : (is_vae
                    ? mca::qnn::ImageStage::VaeBinaryMmap
                    : mca::qnn::ImageStage::UnetBinaryMmap),
            kQnnImageContextBinaryMmap);
    }
    if (!context_binary.open_file(context_path, &message)) {
        return false;
    }
    if (!expected_context_sha256.empty()) {
        context_binary_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            context_binary.data(), context_binary.size());
        if (context_binary_sha256.empty() ||
            context_binary_sha256 != expected_context_sha256) {
            message = "Mapped QNN context bytes differ from the Provider-bound SHA-256.";
            return false;
        }
    }
    selected_runtime = runtime;
    uint32_t context_soc_model = 0;
    if (runtime.system_present) {
        const QnnBinaryMetadata metadata = inspect_qnn_context_binary_metadata(
            runtime,
            context_binary.data(),
            context_binary.size()
        );
        if (metadata.release_failed) {
            message = metadata.message;
            return false;
        }
        context_soc_model = metadata.soc_model;
    }
    if (selected_runtime.exact_role_binding) {
        if (preferred_htp_arch <= 0 ||
            selected_runtime.htp_arch_version != preferred_htp_arch) {
            message = "Exact SDXL QNN runtime HTP architecture differs from the phase request.";
            return false;
        }
    } else {
        if (!select_qnn_runtime_profile_for_context(
                &selected_runtime,
                context_soc_model,
                preferred_htp_arch)) {
            message = selected_runtime.message;
            return false;
        }
    }

    loaded = load_qnn_runtime_for_graph(selected_runtime);
    if (!loaded.ok) {
        message = loaded.message;
        return false;
    }
    std::string provider_error;
    provider = select_qnn_interface(loaded.get_providers, &provider_error);
    if (provider == nullptr) {
        message = provider_error;
        return false;
    }
    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    log_session = create_qnn_log_session(api);
    const Qnn_ErrorHandle_t backend_status = api.backendCreate(log_session.handle, nullptr, &backend);
    if (backend_status != QNN_SUCCESS || backend == nullptr) {
        message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
        return false;
    }
    const QnnDeviceCreateOutcome device_outcome =
        create_qnn_device_or_default(api, context_soc_model, log_session.handle);
    device = device_outcome.device;
    device_created = device_outcome.created;
    context_default_device = device_outcome.context_default_device;
    device_message = device_outcome.message;
    if (device_outcome.fatal) {
        message = device_outcome.message;
        return false;
    }
    const auto context_start = std::chrono::steady_clock::now();
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderContextCreateBefore
                : (is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateBefore
                    : mca::qnn::ImageStage::UnetContextCreateBefore),
            kQnnImageContextCreate);
    }
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
        message = append_diagnostic_note(
            qnn_context_load_failure_message(api, context_status),
            device_message);
        return false;
    }
    context_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - context_start
    ).count();
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderContextCreateAfter
                : (is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateAfter
                    : mca::qnn::ImageStage::UnetContextCreateAfter),
            kQnnImageContextCreate);
    }

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
        message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        return false;
    }
    std::string system_provider_error;
    system_provider = select_qnn_system_interface(get_system_providers, &system_provider_error);
    if (system_provider == nullptr) {
        message = system_provider_error;
        return false;
    }
    const auto& sys_api = system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
    Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&system_context);
    if (system_status != QNN_SUCCESS || system_context == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
        message = out.str();
        return false;
    }
    const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
    if (sys_api.systemContextGetMetaData != nullptr) {
        system_status = sys_api.systemContextGetMetaData(
            system_context,
            context_binary.data(),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info
        );
    } else {
        Qnn_ContextBinarySize_t binary_info_size = 0;
        system_status = sys_api.systemContextGetBinaryInfo(
            system_context,
            const_cast<uint8_t*>(context_binary.data()),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info,
            &binary_info_size
        );
    }
    if (system_status != QNN_SUCCESS || binary_info == nullptr) {
        std::ostringstream out;
        out << "QNN metadata parse failed: 0x" << std::hex << system_status;
        message = out.str();
        return false;
    }

    QnnSystemContext_GraphInfo_t* graphs = qnn_binary_graphs(*binary_info);
    const uint32_t graph_count = qnn_binary_graph_count(*binary_info);
    QnnGraphMetadata graph_meta;
    bool graph_meta_found = false;
    for (uint32_t i = 0; i < graph_count; ++i) {
        QnnGraphMetadata candidate = qnn_graph_metadata(graphs[i]);
        if (candidate.name != nullptr &&
            (requested_graph_name.empty() || requested_graph_name == candidate.name)) {
            graph_meta = candidate;
            graph_meta_found = true;
            break;
        }
    }
    if (!graph_meta_found && graph_count > 0 && !is_encoder) {
        graph_meta = qnn_graph_metadata(graphs[0]);
        graph_meta_found = graph_meta.name != nullptr;
    }
    if (!graph_meta_found || graph_meta.inputs == nullptr || graph_meta.outputs == nullptr) {
        message = "QNN graph metadata missing.";
        return false;
    }
    graph_name = graph_meta.name != nullptr ? graph_meta.name : requested_graph_name;
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderGraphRetrieveBefore
                : (is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveBefore
                    : mca::qnn::ImageStage::UnetGraphRetrieveBefore),
            kQnnImageContextCreate);
    }
    const Qnn_ErrorHandle_t graph_status = api.graphRetrieve(context, graph_name.c_str(), &graph);
    if (graph_status != QNN_SUCCESS || graph == nullptr) {
        message = "QNN graphRetrieve failed for '" + graph_name + "': " +
            qnn_error_to_string(api, graph_status);
        return false;
    }
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderGraphRetrieveAfter
                : (is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveAfter
                    : mca::qnn::ImageStage::UnetGraphRetrieveAfter),
            kQnnImageContextCreate);
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderTensorBindBefore
                : (is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindBefore
                    : mca::qnn::ImageStage::UnetTensorBindBefore),
            kQnnImageContextCreate);
    }

    if (!bind_tensors(sys_api, graph_meta.inputs, graph_meta.input_count, true, &inputs)) return false;
    if (!bind_tensors(sys_api, graph_meta.outputs, graph_meta.output_count, false, &outputs)) return false;
    if (generation != nullptr) {
        generation->record_stage(
            is_encoder
                ? mca::qnn::ImageStage::EncoderTensorBindAfter
                : (is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindAfter
                    : mca::qnn::ImageStage::UnetTensorBindAfter),
            kQnnImageContextCreate);
    }
    return true;
}

bool QnnExecutableGraph::bind_tensors(
        const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
        Qnn_Tensor_t* source,
        uint32_t count,
        bool input,
        std::vector<QnnTensorBinding>* destination) {
    destination->clear();
    destination->reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        QnnTensorBinding binding;
        binding.tensor = source[i];
        binding.name = qnn_tensor_name(binding.tensor) != nullptr
            ? qnn_tensor_name(binding.tensor)
            : "";
        qnn_copy_tensor_dimensions(&binding.tensor, &binding.dimensions);
        const uint64_t bytes = qnn_tensor_bytes(sys_api, binding.tensor);
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            message = std::string("Invalid tensor buffer size for ") +
                (input ? "input " : "output ") + binding.name;
            return false;
        }
        binding.buffer.assign(static_cast<size_t>(bytes), 0);
        qnn_set_tensor_raw_buffer(
            &binding.tensor,
            binding.buffer.data(),
            static_cast<uint32_t>(binding.buffer.size()));
        destination->push_back(std::move(binding));
    }
    return true;
}

bool QnnExecutableGraph::execute(
        long long* execute_ms,
        std::string* error,
        bool preview_vae_execution) {
    if (provider == nullptr || graph == nullptr) {
        *error = "QNN executable graph is not loaded.";
        return false;
    }
    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    std::vector<Qnn_Tensor_t> input_tensors;
    std::vector<Qnn_Tensor_t> output_tensors;
    input_tensors.reserve(inputs.size());
    output_tensors.reserve(outputs.size());
    for (const auto& binding : inputs) input_tensors.push_back(binding.tensor);
    for (const auto& binding : outputs) output_tensors.push_back(binding.tensor);
    if (generation != nullptr) {
        generation->record_stage(
            encoder_graph
                ? mca::qnn::ImageStage::EncoderGraphExecute
                : (vae_graph && preview_vae_execution
                ? mca::qnn::ImageStage::PreviewVaeGraphExecute
                : (vae_graph
                    ? mca::qnn::ImageStage::VaeGraphExecute
                    : mca::qnn::ImageStage::UnetGraphExecute)),
            kQnnImageGraphExecute);
    }
    const auto start = std::chrono::steady_clock::now();
    const Qnn_ErrorHandle_t status = api.graphExecute(
        graph,
        input_tensors.data(),
        static_cast<uint32_t>(input_tensors.size()),
        output_tensors.data(),
        static_cast<uint32_t>(output_tensors.size()),
        nullptr,
        nullptr
    );
    *execute_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
    if (status != QNN_SUCCESS) {
        *error = "QNN graphExecute failed: " + qnn_error_to_string(api, status);
        return false;
    }
    return true;
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
