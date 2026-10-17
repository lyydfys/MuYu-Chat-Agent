// mca/qnn/qnn_coherent_runtime_session.cpp -- QnnCoherentRuntimeSession lifecycle (open/accepts/close).
// (T2B14 / R7-C3 split).  Relocated byte-exact from mca/qnn/qnn_executable_graph.cpp (lines 277-533).

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

bool QnnCoherentRuntimeSession::open(
        const RuntimeProbe& runtime,
        const std::string& first_context_path,
        const QnnImageGenerationScope* generation_scope) {
    generation = generation_scope;
    ScopedReadOnlyMmap context_probe;
    if (generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::UnetBinaryMmap,
            kQnnImageContextBinaryMmap);
    }
    if (!context_probe.open_file(first_context_path, &message)) return false;
    if (runtime.system_present) {
        const QnnBinaryMetadata metadata = inspect_qnn_context_binary_metadata(
            runtime,
            context_probe.data(),
            context_probe.size());
        if (metadata.release_failed) {
            message = metadata.message;
            return false;
        }
        context_soc_model = metadata.soc_model;
    } else {
        context_soc_model = 0;
    }
    selected_runtime = runtime;
    if (!select_qnn_runtime_profile_for_context(&selected_runtime, context_soc_model)) {
        message = selected_runtime.message;
        return false;
    }

    if (generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::RuntimeLoadBefore,
            kQnnImageContextCreate);
    }
    loaded = load_qnn_runtime_for_graph(selected_runtime);
    if (!loaded.ok) {
        message = loaded.message;
        return false;
    }
    if (generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::RuntimeLoadAfter,
            kQnnImageContextCreate);
    }

    std::string provider_error;
    provider = select_qnn_interface(loaded.get_providers, &provider_error);
    if (provider == nullptr) {
        message = provider_error;
        return false;
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
    system_provider = select_qnn_system_interface(
        get_system_providers,
        &system_provider_error);
    if (system_provider == nullptr) {
        message = system_provider_error;
        return false;
    }

    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    log_session = create_qnn_log_session(api);
    if (generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::BackendCreateBefore,
            kQnnImageContextCreate);
    }
    const Qnn_ErrorHandle_t backend_status = api.backendCreate(
        log_session.handle,
        nullptr,
        &backend);
    if (backend_status != QNN_SUCCESS || backend == nullptr) {
        message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
        return false;
    }
    if (generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::BackendCreateAfter,
            kQnnImageContextCreate);
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
    return true;
}

bool QnnCoherentRuntimeSession::accepts_context(const RuntimeProbe& runtime, const ScopedReadOnlyMmap& binary) {
    uint32_t soc_model = 0;
    if (runtime.system_present) {
        const QnnBinaryMetadata metadata = inspect_qnn_context_binary_metadata(
            runtime, binary.data(), binary.size());
        if (metadata.release_failed) {
            message = metadata.message;
            return false;
        }
        soc_model = metadata.soc_model;
    }
    RuntimeProbe candidate = runtime;
    if (!select_qnn_runtime_profile_for_context(&candidate, soc_model)) {
        message = candidate.message;
        return false;
    }
    const bool coherent =
        candidate.system_path == selected_runtime.system_path &&
        candidate.htp_path == selected_runtime.htp_path &&
        candidate.rpc_path == selected_runtime.rpc_path &&
        candidate.skel_path == selected_runtime.skel_path &&
        candidate.stub_path == selected_runtime.stub_path &&
        candidate.adsp_library_path == selected_runtime.adsp_library_path &&
        candidate.htp_arch_version == selected_runtime.htp_arch_version;
    if (!coherent) {
        message = "UNet and VAE context binaries resolve to different QNN runtime profiles.";
    }
    return coherent;
}

void QnnCoherentRuntimeSession::mark_poisoned() {
    poisoned = true;
    g_qnn_runtime_poisoned.store(true);
}

bool QnnCoherentRuntimeSession::close_checked(std::string* error) {
    const auto fail_and_abandon = [&](const std::string& detail) {
        mark_poisoned();
        log_session.abandon_without_release();
        loaded.abandon_without_unload();
        provider = nullptr;
        system_provider = nullptr;
        device = nullptr;
        backend = nullptr;
        if (error != nullptr) *error = detail;
        return false;
    };
    if (poisoned) {
        return fail_and_abandon(
            "QNN coherent runtime session was poisoned by an earlier resource release failure.");
    }
    if (device != nullptr) {
        if (provider == nullptr ||
            provider->QNN_INTERFACE_VER_NAME.deviceFree == nullptr) {
            return fail_and_abandon(
                "QNN deviceFree is unavailable for a live coherent-session device handle.");
        }
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::DeviceReleaseBefore,
                kQnnImageContextRelease);
        }
        const Qnn_ErrorHandle_t status =
            provider->QNN_INTERFACE_VER_NAME.deviceFree(device);
        if (status != QNN_SUCCESS) {
            return fail_and_abandon(
                "QNN deviceFree failed: " + qnn_error_to_string(
                    provider->QNN_INTERFACE_VER_NAME, status));
        }
        device = nullptr;
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::DeviceReleaseAfter,
                kQnnImageContextRelease);
        }
    }
    if (backend != nullptr) {
        if (provider == nullptr ||
            provider->QNN_INTERFACE_VER_NAME.backendFree == nullptr) {
            return fail_and_abandon(
                "QNN backendFree is unavailable for a live coherent-session backend handle.");
        }
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::BackendReleaseBefore,
                kQnnImageContextRelease);
        }
        const Qnn_ErrorHandle_t status =
            provider->QNN_INTERFACE_VER_NAME.backendFree(backend);
        if (status != QNN_SUCCESS) {
            return fail_and_abandon(
                "QNN backendFree failed: " + qnn_error_to_string(
                    provider->QNN_INTERFACE_VER_NAME, status));
        }
        backend = nullptr;
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::BackendReleaseAfter,
                kQnnImageContextRelease);
        }
    }
    const bool had_log = log_session.handle != nullptr;
    if (had_log && generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::LogReleaseBefore,
            kQnnImageContextRelease);
    }
    std::string log_error;
    if (!log_session.reset_checked(&log_error)) {
        return fail_and_abandon(log_error.empty()
            ? "QNN log release failed."
            : log_error);
    }
    if (had_log && generation != nullptr) {
        generation->record_stage(
            mca::qnn::ImageStage::LogReleaseAfter,
            kQnnImageContextRelease);
    }
    if (loaded.ok || loaded.htp_handle != nullptr || loaded.system_handle != nullptr ||
        loaded.rpc_handle != nullptr) {
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::RuntimeUnloadBefore,
                kQnnImageContextRelease);
        }
        std::string unload_error;
        if (!loaded.close_checked(&unload_error)) {
            return fail_and_abandon(unload_error.empty()
                ? "QNN coherent runtime library unload failed."
                : unload_error);
        }
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::RuntimeUnloadAfter,
                kQnnImageContextRelease);
        }
    }
    provider = nullptr;
    system_provider = nullptr;
    if (error != nullptr) error->clear();
    return true;
}

void QnnCoherentRuntimeSession::close() {
    std::string ignored;
    close_checked(&ignored);
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
