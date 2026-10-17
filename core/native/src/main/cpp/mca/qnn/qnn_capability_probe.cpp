#include "qnn_capability_probe.hpp"
#include "qnn_soc_htp_mapping.hpp"
#include "mca/common/logging.hpp"
#include "mca/common/safe_path.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <sstream>

namespace mca {
namespace qnn {

namespace {

constexpr const char* kTag = "MCA-QNN-PROBE";
constexpr int kGenericFallbackHtpArch = 68;

bool ends_with(const std::string& value, const std::string& suffix) {
    if (suffix.size() > value.size()) return false;
    return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin());
}

bool contains_lower(const std::string& haystack, const std::string& needle) {
    std::string h = haystack;
    std::string n = needle;
    std::transform(h.begin(), h.end(), h.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    std::transform(n.begin(), n.end(), n.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return h.find(n) != std::string::npos;
}

std::string join_path(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

bool exists_file(const std::string& path) {
    return access(path.c_str(), F_OK) == 0;
}

std::string json_escape(const std::string& value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u00";
                    const char* hex = "0123456789abcdef";
                    out << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string quote(const std::string& value) {
    return "\"" + json_escape(value) + "\"";
}

class ScopedDlHandle {
public:
    explicit ScopedDlHandle(void* handle = nullptr) : handle_(handle) {}
    ScopedDlHandle(const ScopedDlHandle&) = delete;
    ScopedDlHandle& operator=(const ScopedDlHandle&) = delete;
    ~ScopedDlHandle() { close(); }

    bool close_checked(std::string* error) {
        if (handle_ == nullptr) return true;
        if (dlclose(handle_) != 0) {
            const char* detail = dlerror();
            if (error != nullptr) {
                *error = "dlclose libQnnSystem failed";
                if (detail != nullptr) *error += std::string(": ") + detail;
            }
            return false;
        }
        handle_ = nullptr;
        return true;
    }

    void close() {
        if (handle_ != nullptr) {
            dlclose(handle_);
            handle_ = nullptr;
        }
    }

    void abandon_without_unload() { handle_ = nullptr; }
    [[nodiscard]] void* get() const { return handle_; }
    explicit operator bool() const { return handle_ != nullptr; }

private:
    void* handle_ = nullptr;
};

constexpr std::array<const char*, 5> kQnnPlatformRuntimeDirectoryPrefixes{{
    "/vendor/",
    "/odm/",
    "/system/",
    "/system_ext/",
    "/product/",
}};

} // namespace

int htp_library_version(const std::string& name) {
    const std::string marker = "libQnnHtpV";
    const auto start = name.find(marker);
    if (start == std::string::npos) return -1;
    size_t cursor = start + marker.size();
    std::string digits;
    while (cursor < name.size() && std::isdigit(static_cast<unsigned char>(name[cursor]))) {
        digits.push_back(name[cursor]);
        ++cursor;
    }
    if (digits.empty()) return -1;
    return std::atoi(digits.c_str());
}

int physical_device_htp_arch_version() {
    char value[PROP_VALUE_MAX] = {};
    const char* properties[] = {"ro.soc.model", "ro.board.platform", "ro.hardware"};
    std::string model;
    for (const char* property : properties) {
        std::memset(value, 0, sizeof(value));
        if (__system_property_get(property, value) > 0 && value[0] != '\0') {
            if (const int arch = mca::qnn::htp_arch_version_for_soc_hint(value); arch > 0) {
                return arch;
            }
            model += " ";
            model += value;
        }
    }
    return mca::qnn::htp_arch_version_for_soc_hint(model);
}

std::string htp_library_for_arch_in_dir(
        const std::string& dir,
        int arch_version,
        const std::string& suffix) {
    if (arch_version <= 0) return "";
    const std::string path = join_path(
        dir,
        "libQnnHtpV" + std::to_string(arch_version) + suffix
    );
    return exists_file(path) ? path : "";
}

bool is_platform_qnn_runtime_directory(const std::string& directory) {
    return std::any_of(
        kQnnPlatformRuntimeDirectoryPrefixes.begin(),
        kQnnPlatformRuntimeDirectoryPrefixes.end(),
        [&](const char* prefix) { return directory.rfind(prefix, 0) == 0; });
}

bool may_pair_qnn_runtime_directories(
        const std::string& host_directory,
        const std::string& dsp_directory) {
    if (host_directory == dsp_directory) return true;
    return is_platform_qnn_runtime_directory(host_directory) &&
        is_platform_qnn_runtime_directory(dsp_directory);
}

std::string best_htp_library_in_dir(const std::string& dir, const std::string& suffix) {
    DIR* handle = opendir(dir.c_str());
    if (handle == nullptr) return "";
    int best_version = -1;
    std::string best_path;
    while (dirent* entry = readdir(handle)) {
        std::string name(entry->d_name);
        if (name.find("libQnnHtpV") != 0 || !ends_with(name, suffix)) continue;
        const int version = htp_library_version(name);
        if (version > best_version) {
            best_version = version;
            best_path = join_path(dir, name);
        }
    }
    closedir(handle);
    return best_path;
}

void append_unique_path(std::vector<std::string>* values, const std::string& value) {
    if (values == nullptr || value.empty()) return;
    if (std::find(values->begin(), values->end(), value) == values->end()) {
        values->push_back(value);
    }
}

std::string join_adsp_paths(const std::vector<std::string>& values) {
    std::ostringstream out;
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ";";
        out << values[i];
    }
    return out.str();
}

std::vector<std::string> prioritize_adsp_paths(const std::vector<std::string>& values) {
    std::vector<std::string> prioritized;
    for (const auto& value : values) {
        append_unique_path(&prioritized, value);
    }
    return prioritized;
}

void append_common_android_adsp_paths(std::vector<std::string>* values) {
    append_unique_path(values, "/system/lib/rfsa/adsp");
    append_unique_path(values, "/system/vendor/lib/rfsa/adsp");
    append_unique_path(values, "/vendor/lib/rfsa/adsp");
    append_unique_path(values, "/vendor/dsp");
    append_unique_path(values, "/vendor/dsp/cdsp");
    append_unique_path(values, "/dsp");
}

RuntimeProbe inspect_runtime_internal(
        const std::vector<std::string>& dirs,
        bool probe,
        bool probe_htp,
        std::atomic<bool>* poisoned_flag) {
    RuntimeProbe result;
    result.search_directories = dirs;
    std::vector<std::string> adsp_paths;
    struct RuntimeDirectoryCandidate {
        std::string directory;
        std::string system;
        std::string htp;
        std::string rpc;
        std::string skel;
        std::string stub;

        bool has_host() const {
            return exists_file(system) && exists_file(htp);
        }

        bool has_dsp() const {
            return !skel.empty();
        }
    };
    std::vector<RuntimeDirectoryCandidate> candidates;
    for (const auto& dir : dirs) {
        if (dir.empty()) continue;
        RuntimeDirectoryCandidate candidate;
        candidate.directory = dir;
        candidate.system = join_path(dir, "libQnnSystem.so");
        candidate.htp = join_path(dir, "libQnnHtp.so");
        candidate.rpc = join_path(dir, "libcdsprpc.so");
        candidate.skel = best_htp_library_in_dir(dir, "Skel.so");
        candidate.stub = best_htp_library_in_dir(dir, "Stub.so");
        if (candidate.has_dsp()) {
            append_unique_path(&adsp_paths, dir);
        }
        if (contains_lower(dir, "rfsa") || contains_lower(dir, "adsp") || contains_lower(dir, "dsp")) {
            append_unique_path(&adsp_paths, dir);
        }
        candidates.push_back(std::move(candidate));
    }

    auto select_runtime = [&](const RuntimeDirectoryCandidate& host, const RuntimeDirectoryCandidate& dsp) {
        result.system_present = true;
        result.htp_present = true;
        result.skel_present = true;
        result.stub_present = !dsp.stub.empty();
        result.rpc_present = exists_file(host.rpc);
        result.system_path = host.system;
        result.htp_path = host.htp;
        result.skel_path = dsp.skel;
        result.stub_path = dsp.stub;
        result.rpc_path = result.rpc_present ? host.rpc : "";
        result.host_runtime_directory = host.directory;
        result.dsp_runtime_directory = dsp.directory;
        result.runtime_directory = host.directory == dsp.directory
            ? host.directory
            : "host=" + host.directory + ";dsp=" + dsp.directory;
    };

    for (const auto& candidate : candidates) {
        if (candidate.has_host() && candidate.has_dsp()) {
            select_runtime(candidate, candidate);
            break;
        }
    }
    if (!result.system_present) {
        for (const auto& host : candidates) {
            if (!host.has_host()) continue;
            for (const auto& dsp : candidates) {
                if (!dsp.has_dsp() || !may_pair_qnn_runtime_directories(host.directory, dsp.directory)) continue;
                select_runtime(host, dsp);
                break;
            }
            if (result.system_present) break;
        }
    }
    std::vector<std::string> prioritized_adsp_paths;
    append_unique_path(&prioritized_adsp_paths, result.dsp_runtime_directory);
    for (const auto& path : adsp_paths) {
        append_unique_path(&prioritized_adsp_paths, path);
    }
    append_common_android_adsp_paths(&prioritized_adsp_paths);

    if (!result.system_present || !result.htp_present || !result.skel_present) {
        result.message = "Missing a coherent QNN runtime profile: libQnnSystem.so/libQnnHtp.so and libQnnHtpVxxSkel.so were not found in one bundle or compatible OEM runtime directories.";
        return result;
    }

    if (!probe) {
        result.message = "QNN runtime files found; native load probe not requested.";
        return result;
    }

    if (!probe_htp) {
        ScopedDlHandle system_handle(dlopen(result.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
        if (!system_handle) {
            const char* error = dlerror();
            result.loadable = false;
            result.message = std::string("dlopen libQnnSystem.so failed: ") +
                (error ? error : "unknown error");
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", result.message.c_str());
            return result;
        }
        result.qnn_system_interface_present =
            dlsym(system_handle.get(), "QnnSystemInterface_getProviders") != nullptr;
        result.loadable = true;
        result.message = result.qnn_system_interface_present
            ? "QNN System metadata preflight loaded; HTP load is deferred until the context selects an exact runtime profile."
            : "QNN System library loaded, but QnnSystemInterface_getProviders was not found.";
        std::string unload_error;
        if (!system_handle.close_checked(&unload_error)) {
            if (poisoned_flag != nullptr) poisoned_flag->store(true);
            result.loadable = false;
            result.qnn_system_interface_present = false;
            result.message += " Cleanup also failed: " + (unload_error.empty()
                ? "QNN System metadata preflight library unload failed."
                : unload_error);
            system_handle.abandon_without_unload();
        }
        return result;
    }

    void* rpc_handle = nullptr;
    std::vector<void*> handles;
    auto close_probe_handles = [&]() -> bool {
        bool closed = true;
        std::string failure;
        for (auto it = handles.rbegin(); it != handles.rend(); ++it) {
            if (*it == nullptr) continue;
            if (dlclose(*it) != 0) {
                const char* detail = dlerror();
                failure = "QNN runtime probe library unload failed";
                if (detail != nullptr) failure += std::string(": ") + detail;
                closed = false;
                break;
            }
            *it = nullptr;
        }
        if (closed && rpc_handle != nullptr) {
            if (dlclose(rpc_handle) != 0) {
                const char* detail = dlerror();
                failure = "QNN RPC runtime probe library unload failed";
                if (detail != nullptr) failure += std::string(": ") + detail;
                closed = false;
            } else {
                rpc_handle = nullptr;
            }
        }
        if (closed) {
            handles.clear();
        } else {
            if (poisoned_flag != nullptr) poisoned_flag->store(true);
            result.loadable = false;
            result.qnn_interface_present = false;
            result.qnn_system_interface_present = false;
            if (result.message.empty()) {
                result.message = failure;
            } else {
                result.message += " Cleanup also failed: " + failure;
            }
            handles.clear();
            rpc_handle = nullptr;
        }
        return closed;
    };

    if (result.rpc_present && !result.rpc_path.empty()) {
        rpc_handle = dlopen(result.rpc_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (rpc_handle != nullptr) {
            result.rpc_loadable = true;
            result.rpc_message = "libcdsprpc.so loaded successfully before QNN HTP.";
        } else {
            const char* error = dlerror();
            result.rpc_loadable = false;
            result.rpc_message = std::string("dlopen libcdsprpc.so failed: ") +
                (error ? error : "unknown error");
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", result.rpc_message.c_str());
        }
    }

    result.adsp_library_path = join_adsp_paths(prioritize_adsp_paths(prioritized_adsp_paths));
    if (!result.adsp_library_path.empty()) {
        setenv("ADSP_LIBRARY_PATH", result.adsp_library_path.c_str(), 1);
        __android_log_print(ANDROID_LOG_INFO, kTag, "ADSP_LIBRARY_PATH=%s", result.adsp_library_path.c_str());
    }

    if (result.rpc_present && result.rpc_message.empty()) {
        result.rpc_message =
            "libcdsprpc.so is present but not dlopen-probed directly; QNN HTP resolves transport dependencies during graph smoke.";
    } else if (!result.rpc_present) {
        result.rpc_message =
            "libcdsprpc.so was not found; this is acceptable when the device or QAIRT HTP backend resolves transport dependencies.";
    }

    std::vector<std::string> load_order;
    load_order.push_back(result.system_path);
    load_order.push_back(result.htp_path);
    for (const auto& path : load_order) {
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char* error = dlerror();
            result.loadable = false;
            result.message = std::string("dlopen failed for ") + path + ": " + (error ? error : "unknown error");
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", result.message.c_str());
            close_probe_handles();
            return result;
        }
        handles.push_back(handle);
    }

    for (void* handle : handles) {
        if (dlsym(handle, "QnnInterface_getProviders") != nullptr) {
            result.qnn_interface_present = true;
        }
        if (dlsym(handle, "QnnSystemInterface_getProviders") != nullptr) {
            result.qnn_system_interface_present = true;
        }
    }
    result.loadable = true;
    result.message = result.qnn_interface_present
        ? "QNN host runtime libraries loaded successfully and QnnInterface_getProviders was found."
        : "QNN host runtime libraries loaded successfully, but QnnInterface_getProviders was not found.";
    close_probe_handles();
    return result;
}

int QnnCapabilityProbe::resolve_htp_arch(
    const std::string& soc_hint,
    uint32_t soc_model,
    bool* out_is_generic_fallback
) {
    int arch = 0;
    if (soc_model > 0) {
        arch = htp_arch_version_for_soc_model(soc_model);
    }
    if (arch == 0 && !soc_hint.empty()) {
        arch = htp_arch_version_for_soc_hint(soc_hint);
    }

    if (arch > 0) {
        if (out_is_generic_fallback) *out_is_generic_fallback = false;
        MCA_LOGI(kTag, "Resolved specific HTP arch V%d for SoC hint='%s' model=%u",
                 arch, soc_hint.c_str(), soc_model);
        return arch;
    }

    if (out_is_generic_fallback) *out_is_generic_fallback = true;
    MCA_LOGI(kTag, "Unrecognized SoC hint='%s' model=%u; falling back to generic compatible HTP V%d path",
             soc_hint.c_str(), soc_model, kGenericFallbackHtpArch);
    return kGenericFallbackHtpArch;
}

std::vector<std::string> QnnCapabilityProbe::get_default_search_directories() {
    return {
        "/data/local/tmp/qnn",
        "/system/lib64",
        "/vendor/lib64",
        "/system/lib/rfsa/adsp",
        "/system/vendor/lib/rfsa/adsp",
        "/vendor/lib/rfsa/adsp",
        "/vendor/dsp",
        "/vendor/dsp/cdsp",
        "/dsp"
    };
}

std::string QnnCapabilityProbe::build_adsp_library_path(const std::vector<std::string>& search_dirs) {
    std::ostringstream oss;
    bool first = true;
    for (const auto& dir : search_dirs) {
        if (dir.empty()) continue;
        if (!first) oss << ":";
        oss << dir;
        first = false;
    }
    return oss.str();
}

QnnCapabilityProbe::CapabilityReport QnnCapabilityProbe::probe(
    const std::string& soc_hint,
    uint32_t soc_model,
    const std::vector<std::string>& search_dirs
) {
    CapabilityReport report;
    report.soc_hint = soc_hint;
    report.soc_model = soc_model;
    report.is_admitted = true;

    bool is_fallback = false;
    report.htp_arch_version = resolve_htp_arch(soc_hint, soc_model, &is_fallback);
    report.is_generic_fallback = is_fallback;

    if (report.is_generic_fallback) {
        report.recommended_transport = "generic_htp";
        report.recommended_precision = "quantized_int8";
    } else {
        report.recommended_transport = "htp";
        report.recommended_precision = (report.htp_arch_version >= 75) ? "fp16" : "quantized_int8";
    }
    report.recommended_batch_size = 1;

    std::vector<std::string> all_dirs = search_dirs;
    if (all_dirs.empty()) {
        all_dirs = get_default_search_directories();
    }
    report.search_directories = all_dirs;
    report.adsp_library_path = build_adsp_library_path(all_dirs);

    for (const auto& dir : all_dirs) {
        const std::string system_candidate = common::SafePath::safe_join(dir, "libQnnSystem.so");
        const std::string htp_candidate = common::SafePath::safe_join(dir, "libQnnHtp.so");
        const std::string rpc_candidate = common::SafePath::safe_join(dir, "libcdsprpc.so");

        if (!report.system_present && common::SafePath::file_exists(system_candidate)) {
            report.system_present = true;
            report.system_path = system_candidate;
        }
        if (!report.htp_present && common::SafePath::file_exists(htp_candidate)) {
            report.htp_present = true;
            report.htp_path = htp_candidate;
        }
        if (!report.rpc_present && common::SafePath::file_exists(rpc_candidate)) {
            report.rpc_present = true;
            report.rpc_path = rpc_candidate;
        }

        const std::string skel_candidate = common::SafePath::safe_join(
            dir, "libQnnHtpV" + std::to_string(report.htp_arch_version) + "Skel.so");
        if (!report.skel_present && common::SafePath::file_exists(skel_candidate)) {
            report.skel_present = true;
            report.skel_path = skel_candidate;
        }
    }

    if (report.is_generic_fallback) {
        report.diagnostic_message = "Advisory: Generic compatible QNN path selected; hardware detection does not block execution.";
    } else {
        report.diagnostic_message = "Specific QNN HTP profile matched (V" + std::to_string(report.htp_arch_version) + ").";
    }

    MCA_LOGI(kTag, "Probe result: arch=V%d, generic_fallback=%d, admitted=%d",
             report.htp_arch_version,
             report.is_generic_fallback ? 1 : 0,
             report.is_admitted ? 1 : 0);

    return report;
}

std::string compile_capability_json() {
    std::ostringstream out;
    out << "{"
        << "\"sdkRootConfigured\":" << (MCA_QNN_SDK_ROOT_CONFIGURED ? "true" : "false") << ","
        << "\"sdkHeadersPresent\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
        << "\"typedGraphBindingsCompiled\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false")
        << "}";
    return out.str();
}

std::string runtime_probe_json(const RuntimeProbe& probe) {
    std::ostringstream out;
    out << "{"
        << "\"ready\":" << (probe.system_present && probe.htp_present && probe.skel_present ? "true" : "false") << ","
        << "\"loadable\":" << (probe.loadable ? "true" : "false") << ","
        << "\"qnnSystemLibraryPresent\":" << (probe.system_present ? "true" : "false") << ","
        << "\"qnnHtpLibraryPresent\":" << (probe.htp_present ? "true" : "false") << ","
        << "\"htpSkelLibraryPresent\":" << (probe.skel_present ? "true" : "false") << ","
        << "\"htpStubLibraryPresent\":" << (probe.stub_present ? "true" : "false") << ","
        << "\"cdspRpcLibraryPresent\":" << (probe.rpc_present ? "true" : "false") << ","
        << "\"cdspRpcLibraryLoadable\":" << (probe.rpc_loadable ? "true" : "false") << ","
        << "\"qnnInterfacePresent\":" << (probe.qnn_interface_present ? "true" : "false") << ","
        << "\"qnnSystemInterfacePresent\":" << (probe.qnn_system_interface_present ? "true" : "false") << ","
        << "\"exactRoleBinding\":" << (probe.exact_role_binding ? "true" : "false") << ","
        << "\"qnnSystemLibraryPath\":" << quote(probe.system_path) << ","
        << "\"qnnHtpLibraryPath\":" << quote(probe.htp_path) << ","
        << "\"htpSkelLibraryPath\":" << quote(probe.skel_path) << ","
        << "\"htpStubLibraryPath\":" << quote(probe.stub_path) << ","
        << "\"cdspRpcLibraryPath\":" << quote(probe.rpc_path) << ","
        << "\"cdspRpcMessage\":" << quote(probe.rpc_message) << ","
        << "\"adspLibraryPath\":" << quote(probe.adsp_library_path) << ","
        << "\"hostRuntimeDirectory\":" << quote(probe.host_runtime_directory) << ","
        << "\"runtimeDirectory\":" << quote(probe.runtime_directory) << ","
        << "\"dspRuntimeDirectory\":" << quote(probe.dsp_runtime_directory) << ","
        << "\"htpArchVersion\":" << probe.htp_arch_version << ","
        << "\"compile\":" << compile_capability_json() << ","
        << "\"message\":" << quote(probe.message)
        << "}";
    return out.str();
}

} // namespace qnn
} // namespace mca