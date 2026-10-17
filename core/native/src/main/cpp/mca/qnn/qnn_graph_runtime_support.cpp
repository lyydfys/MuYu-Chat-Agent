// mca/qnn/qnn_graph_runtime_support.cpp -- runtime profile selection and binary/hash helpers.
// (T2B14 / R7-C3 split).  Relocated byte-exact from mca/qnn/qnn_executable_graph.cpp (lines 21-274).

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

namespace {

constexpr const char* kTag = "MCA-QNN";   // as in qnn_native_bridge.cpp (HEAD:96)

}  // namespace

bool select_qnn_runtime_profile_for_context(
        RuntimeProbe* runtime,
        uint32_t context_soc_model,
        int preferred_htp_arch) {
    if (runtime == nullptr) return false;
    const int context_htp_arch = htp_arch_version_for_soc_model(context_soc_model);
    const int device_htp_arch = physical_device_htp_arch_version();

    struct HostCandidate {
        std::string directory;
        std::string system;
        std::string htp;
        std::string rpc;
    };
    struct DspCandidate {
        std::string directory;
        std::string skel;
        std::string stub;
    };
    std::vector<HostCandidate> hosts;
    for (const auto& dir : runtime->search_directories) {
        if (dir.empty()) continue;
        const std::string system = join_path(dir, "libQnnSystem.so");
        const std::string htp = join_path(dir, "libQnnHtp.so");
        if (exists_file(system) && exists_file(htp)) {
            hosts.push_back({dir, system, htp, join_path(dir, "libcdsprpc.so")});
        }
    }

    std::vector<int> packaged_arches;
    for (const auto& dir : runtime->search_directories) {
        DIR* handle = opendir(dir.c_str());
        if (handle == nullptr) continue;
        while (dirent* entry = readdir(handle)) {
            const std::string name(entry->d_name);
            if (name.find("libQnnHtpV") != 0 ||
                (!ends_with(name, "Skel.so") && !ends_with(name, "Stub.so"))) {
                continue;
            }
            const int version = htp_library_version(name);
            if (version > 0 && std::find(packaged_arches.begin(), packaged_arches.end(), version) == packaged_arches.end()) {
                packaged_arches.push_back(version);
            }
        }
        closedir(handle);
    }
    const std::vector<int> candidate_arches = qnn_htp_runtime_candidates(
        context_htp_arch,
        device_htp_arch,
        preferred_htp_arch,
        runtime->htp_arch_version,
        std::move(packaged_arches));

    if (context_htp_arch > 0 && device_htp_arch > 0 &&
        context_htp_arch != device_htp_arch) {
        __android_log_print(
            ANDROID_LOG_WARN, kTag,
            "Context HTP V%d differs from device HTP V%d; selecting an available coherent transport and deferring compatibility to QNN contextCreateFromBinary.",
            context_htp_arch, device_htp_arch);
    }

    auto select_profile = [&](const HostCandidate& host, const DspCandidate& dsp, int htp_arch) {
        runtime->system_present = true;
        runtime->htp_present = true;
        runtime->skel_present = true;
        runtime->stub_present = true;
        runtime->rpc_present = exists_file(host.rpc);
        runtime->system_path = host.system;
        runtime->htp_path = host.htp;
        runtime->skel_path = dsp.skel;
        runtime->stub_path = dsp.stub;
        runtime->rpc_path = runtime->rpc_present ? host.rpc : "";
        runtime->host_runtime_directory = host.directory;
        runtime->dsp_runtime_directory = dsp.directory;
        runtime->runtime_directory = host.directory == dsp.directory
            ? host.directory
            : "host=" + host.directory + ";dsp=" + dsp.directory;
        runtime->htp_arch_version = htp_arch;

        // The selected Skel/Stub directory is the only non-platform entry in the
        // DSP lookup path. Platform fallback directories are safe for OEM
        // split runtimes but cannot override a side-loaded exact profile.
        std::vector<std::string> adsp_paths;
        append_unique_path(&adsp_paths, dsp.directory);
        append_common_android_adsp_paths(&adsp_paths);
        runtime->adsp_library_path = join_adsp_paths(adsp_paths);
        if (!runtime->adsp_library_path.empty()) {
            setenv("ADSP_LIBRARY_PATH", runtime->adsp_library_path.c_str(), 1);
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "Selected coherent QNN runtime profile: preferred HTP V%d, context socModel=%u (HTP V%d), device HTP V%d, selected HTP V%d, system=%s, htp=%s, skel=%s, stub=%s",
            preferred_htp_arch,
            context_soc_model,
            context_htp_arch,
            device_htp_arch,
            htp_arch,
            runtime->system_path.c_str(),
            runtime->htp_path.c_str(),
            runtime->skel_path.c_str(),
            runtime->stub_path.c_str());
    };

    for (const int htp_arch : candidate_arches) {
        std::vector<DspCandidate> dsps;
        for (const auto& dir : runtime->search_directories) {
            if (dir.empty()) continue;
            const std::string skel = htp_library_for_arch_in_dir(dir, htp_arch, "Skel.so");
            const std::string stub = htp_library_for_arch_in_dir(dir, htp_arch, "Stub.so");
            if (!skel.empty() && !stub.empty()) {
                dsps.push_back({dir, skel, stub});
            }
        }
        for (const auto& host : hosts) {
            for (const auto& dsp : dsps) {
                if (!may_pair_qnn_runtime_directories(host.directory, dsp.directory)) continue;
                select_profile(host, dsp, htp_arch);
                return true;
            }
        }
    }

    std::ostringstream message;
    message << "No coherent QNN runtime profile for preferred HTP V" << preferred_htp_arch
            << ", context socModel=" << context_soc_model
            << " (context HTP V" << context_htp_arch
            << ", device HTP V" << device_htp_arch << ", attempted=";
    if (candidate_arches.empty()) {
        message << "none";
    } else {
        for (size_t i = 0; i < candidate_arches.size(); ++i) {
            if (i > 0) message << ",";
            message << "V" << candidate_arches[i];
        }
    }
    message << "): a coherent libQnnSystem.so/libQnnHtp.so plus matching Skel/Stub profile "
            << "must be installed as one side-loaded bundle or compatible OEM host/RFSA directories.";
    runtime->message = message.str();
    return false;
}

bool qnn_consumed_payload_sha256(
        const void* payload,
        size_t payload_bytes,
        std::string* digest,
        std::string* error) {
    constexpr size_t kMaxAuditedArtifactBytes = 64U * 1024U * 1024U;
    if (payload == nullptr || payload_bytes == 0U ||
        payload_bytes > kMaxAuditedArtifactBytes || digest == nullptr || error == nullptr) {
        if (error != nullptr) {
            *error = "Consumed artifact bytes are empty or exceed the native SHA-256 audit limit.";
        }
        return false;
    }
    const auto* begin = static_cast<const uint8_t*>(payload);
    const std::vector<uint8_t> consumed_bytes(begin, begin + payload_bytes);
    *digest = mca::qnn::controlnet::sha256_hex_bytes(consumed_bytes);
    if (digest->size() != 64U) {
        *error = "Consumed artifact SHA-256 could not be derived.";
        return false;
    }
    return true;
}

bool read_float_binary_file(
        const std::string& path,
        std::vector<float>* values,
        std::string* error,
        std::string* consumed_payload_sha256) {
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0 || bytes % static_cast<long long>(sizeof(float)) != 0) {
        *error = "Embedding file is missing, empty, or not f32 aligned: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open embedding file: " + path;
        return false;
    }
    values->assign(static_cast<size_t>(bytes / static_cast<long long>(sizeof(float))), 0.0f);
    input.read(
        reinterpret_cast<char*>(values->data()),
        static_cast<std::streamsize>(bytes));
    if (!input.good()) {
        *error = "Failed to read embedding file: " + path;
        return false;
    }
    if (consumed_payload_sha256 != nullptr &&
        !qnn_consumed_payload_sha256(
            values->data(),
            static_cast<size_t>(bytes),
            consumed_payload_sha256,
            error)) {
        return false;
    }
    return true;
}

bool read_int32_binary_file(
        const std::string& path,
        std::vector<int32_t>* values,
        std::string* error,
        std::string* consumed_payload_sha256) {
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0 || bytes % static_cast<long long>(sizeof(int32_t)) != 0) {
        *error = "Token file is missing, empty, or not int32 aligned: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open token file: " + path;
        return false;
    }
    values->assign(
        static_cast<size_t>(bytes / static_cast<long long>(sizeof(int32_t))),
        0);
    input.read(
        reinterpret_cast<char*>(values->data()),
        static_cast<std::streamsize>(bytes));
    if (!input.good()) {
        *error = "Failed to read token file: " + path;
        return false;
    }
    if (consumed_payload_sha256 != nullptr &&
        !qnn_consumed_payload_sha256(
            values->data(),
            static_cast<size_t>(bytes),
            consumed_payload_sha256,
            error)) {
        return false;
    }
    return true;
}

bool qnn_file_sha256(
        const std::string& path,
        std::string* digest,
        std::string* error) {
    if (digest == nullptr || error == nullptr) return false;
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0) {
        *error = "Artifact is missing or empty.";
        return false;
    }
    return mca::qnn::controlnet::sha256_hex_file(path, digest, error);
}

}  // namespace qnn
}  // namespace mca
