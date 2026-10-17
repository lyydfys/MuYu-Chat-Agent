// mca/qnn/qnn_graph_lifecycle.cpp -- QnnExecutableGraph close/close_checked lifecycle.
// (T2B14 / R7-C3 split).  Relocated byte-exact from mca/qnn/qnn_executable_graph.cpp (lines 534-694).

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

void QnnExecutableGraph::close() {
    std::string ignored;
    close_checked(&ignored);
}

bool QnnExecutableGraph::close_checked(std::string* error) {
    const auto fail_and_abandon = [&](const std::string& detail) {
        g_qnn_runtime_poisoned.store(true);
        if (shared_session != nullptr) shared_session->mark_poisoned();
        log_session.abandon_without_release();
        loaded.abandon_without_unload();
        context_binary.abandon_without_unmap();
        system_context = nullptr;
        context = nullptr;
        device = nullptr;
        backend = nullptr;
        provider = nullptr;
        system_provider = nullptr;
        shared_session = nullptr;
        if (error != nullptr) *error = detail;
        return false;
    };
    if (shared_session != nullptr && shared_session->poisoned) {
        return fail_and_abandon(
            "QNN coherent runtime session was poisoned by an earlier graph release failure.");
    }
    const bool has_resources = system_context != nullptr || context != nullptr ||
        device != nullptr || backend != nullptr || !context_binary.empty();
    if (has_resources && generation != nullptr) {
        generation->set_phase(kQnnImageContextRelease);
    }
    const bool had_graph = graph != nullptr;
    if (had_graph && generation != nullptr && shared_session != nullptr) {
        generation->record_stage(
            vae_graph
                ? mca::qnn::ImageStage::VaeGraphReleaseBefore
                : mca::qnn::ImageStage::UnetGraphReleaseBefore,
            kQnnImageContextRelease);
    }
    inputs.clear();
    outputs.clear();
    graph = nullptr;
    if (had_graph && generation != nullptr && shared_session != nullptr) {
        generation->record_stage(
            vae_graph
                ? mca::qnn::ImageStage::VaeGraphReleaseAfter
                : mca::qnn::ImageStage::UnetGraphReleaseAfter,
            kQnnImageContextRelease);
    }
    if (system_context != nullptr) {
        if (system_provider == nullptr ||
            system_provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree == nullptr) {
            return fail_and_abandon(
                "QnnSystemContext_free is unavailable for a live system context handle.");
        }
        if (generation != nullptr && shared_session != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeMetadataReleaseBefore
                    : mca::qnn::ImageStage::UnetMetadataReleaseBefore,
                kQnnImageContextRelease);
        }
        const Qnn_ErrorHandle_t status =
            system_provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree(system_context);
        if (status != QNN_SUCCESS) {
            std::ostringstream detail;
            detail << "QnnSystemContext_free failed: 0x" << std::hex << status;
            return fail_and_abandon(detail.str());
        }
        system_context = nullptr;
        if (generation != nullptr && shared_session != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeMetadataReleaseAfter
                    : mca::qnn::ImageStage::UnetMetadataReleaseAfter,
                kQnnImageContextRelease);
        }
    }
    if (context != nullptr) {
        if (provider == nullptr || provider->QNN_INTERFACE_VER_NAME.contextFree == nullptr) {
            return fail_and_abandon(
                "QNN contextFree is unavailable for a live context handle.");
        }
        if (generation != nullptr && shared_session != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeContextReleaseBefore
                    : mca::qnn::ImageStage::UnetContextReleaseBefore,
                kQnnImageContextRelease);
        }
        const Qnn_ErrorHandle_t status =
            provider->QNN_INTERFACE_VER_NAME.contextFree(context, nullptr);
        if (status != QNN_SUCCESS) {
            return fail_and_abandon(
                "QNN contextFree failed: " + qnn_error_to_string(
                    provider->QNN_INTERFACE_VER_NAME, status));
        }
        context = nullptr;
        if (generation != nullptr && shared_session != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeContextReleaseAfter
                    : mca::qnn::ImageStage::UnetContextReleaseAfter,
                kQnnImageContextRelease);
        }
    }
    if (shared_session == nullptr && device != nullptr) {
        if (provider == nullptr || provider->QNN_INTERFACE_VER_NAME.deviceFree == nullptr) {
            return fail_and_abandon(
                "QNN deviceFree is unavailable for a live standalone device handle.");
        }
        const Qnn_ErrorHandle_t status =
            provider->QNN_INTERFACE_VER_NAME.deviceFree(device);
        if (status != QNN_SUCCESS) {
            return fail_and_abandon(
                "QNN deviceFree failed: " + qnn_error_to_string(
                    provider->QNN_INTERFACE_VER_NAME, status));
        }
        device = nullptr;
    }
    if (shared_session == nullptr && backend != nullptr) {
        if (provider == nullptr || provider->QNN_INTERFACE_VER_NAME.backendFree == nullptr) {
            return fail_and_abandon(
                "QNN backendFree is unavailable for a live standalone backend handle.");
        }
        const Qnn_ErrorHandle_t status =
            provider->QNN_INTERFACE_VER_NAME.backendFree(backend);
        if (status != QNN_SUCCESS) {
            return fail_and_abandon(
                "QNN backendFree failed: " + qnn_error_to_string(
                    provider->QNN_INTERFACE_VER_NAME, status));
        }
        backend = nullptr;
    }
    if (shared_session == nullptr) {
        std::string log_error;
        if (!log_session.reset_checked(&log_error)) {
            return fail_and_abandon(log_error.empty()
                ? "QNN log release failed."
                : log_error);
        }
        std::string unload_error;
        if (!loaded.close_checked(&unload_error)) {
            return fail_and_abandon(unload_error.empty()
                ? "QNN runtime library unload failed."
                : unload_error);
        }
    }
    std::string unmap_error;
    if (!context_binary.close_checked(&unmap_error)) {
        return fail_and_abandon(unmap_error.empty()
            ? "QNN context binary unmap failed."
            : unmap_error);
    }
    provider = nullptr;
    system_provider = nullptr;
    shared_session = nullptr;
    if (error != nullptr) error->clear();
    return true;
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
