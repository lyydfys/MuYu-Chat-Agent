#include "qnn_bundle_files.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>

#if defined(_WIN32)
#include <windows.h>
#include <sys/stat.h>
#else
#include <dirent.h>
#include <sys/stat.h>
#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include <dlfcn.h>
#endif

namespace mca {
namespace qnn {

bool parse_sdxl_exact_runtime_profile(
        const std::string& json,
        SdxlExactRuntimeProfileRequest* profile,
        std::string* error) {
    if (profile == nullptr || error == nullptr) return false;
    try {
        const nlohmann::json root = nlohmann::json::parse(json);
        if (!root.is_object() || root.size() != 4U) {
            *error = "SDXL runtime profile must be one versioned object with exact host and DSP roles.";
            return false;
        }
        const auto version = root.find("version");
        const auto host = root.find("hostDirectory");
        const auto dsp = root.find("dspDirectory");
        const auto arch = root.find("htpArchVersion");
        if (version == root.end() || !version->is_number_integer() || version->get<int>() != 1 ||
            host == root.end() || !host->is_string() ||
            dsp == root.end() || !dsp->is_string() ||
            arch == root.end() || !arch->is_number_integer()) {
            *error = "SDXL runtime profile version or exact role fields are invalid.";
            return false;
        }
        profile->host_directory = host->get<std::string>();
        profile->dsp_directory = dsp->get<std::string>();
        profile->htp_arch_version = arch->get<int>();
        if (profile->host_directory.empty() || profile->dsp_directory.empty() ||
            profile->htp_arch_version <= 0) {
            *error = "SDXL runtime profile directories and HTP architecture must be explicit.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("SDXL runtime profile JSON is invalid: ") + exception.what();
        return false;
    }
}

// Migrated from the reference qnn_native_bridge.cpp:1297.  The SDXL isolated-phase fragment
// headers call this and it was never carried into a module; this translation unit owns
// parse_sdxl_exact_runtime_profile/join_path/exists_file, and qnn_capability_probe.hpp provides
// RuntimeProbe and append_unique_path.
RuntimeProbe inspect_sdxl_exact_runtime_profile(
        const std::string& runtime_profile_json,
        bool probe) {
    RuntimeProbe result;
    SdxlExactRuntimeProfileRequest profile;
    std::string error;
    if (!parse_sdxl_exact_runtime_profile(runtime_profile_json, &profile, &error)) {
        result.message = error;
        return result;
    }

    result.exact_role_binding = true;
    result.host_runtime_directory = profile.host_directory;
    result.dsp_runtime_directory = profile.dsp_directory;
    result.runtime_directory = profile.host_directory == profile.dsp_directory
        ? profile.host_directory
        : "host=" + profile.host_directory + ";dsp=" + profile.dsp_directory;
    result.htp_arch_version = profile.htp_arch_version;
    append_unique_path(&result.search_directories, profile.host_directory);
    append_unique_path(&result.search_directories, profile.dsp_directory);
    result.system_path = join_path(profile.host_directory, "libQnnSystem.so");
    result.htp_path = join_path(profile.host_directory, "libQnnHtp.so");
    result.rpc_path = join_path(profile.host_directory, "libcdsprpc.so");
    result.skel_path = join_path(
        profile.dsp_directory,
        "libQnnHtpV" + std::to_string(profile.htp_arch_version) + "Skel.so");
    result.stub_path = join_path(
        profile.dsp_directory,
        "libQnnHtpV" + std::to_string(profile.htp_arch_version) + "Stub.so");
    result.system_present = exists_file(result.system_path);
    result.htp_present = exists_file(result.htp_path);
    result.rpc_present = exists_file(result.rpc_path);
    result.skel_present = exists_file(result.skel_path);
    result.stub_present = exists_file(result.stub_path);
    if (!result.rpc_present) result.rpc_path.clear();

    if (!may_pair_qnn_runtime_directories(profile.host_directory, profile.dsp_directory)) {
        result.message = "Exact SDXL QNN host/DSP roles may pair only in one directory or between platform-image directories.";
        return result;
    }
    if (!result.system_present || !result.htp_present ||
        !result.skel_present || !result.stub_present) {
        result.message = "Exact SDXL QNN runtime is missing host System/HTP or the requested DSP Skel/Stub pair.";
        return result;
    }

    std::vector<std::string> adsp_paths;
    append_unique_path(&adsp_paths, profile.dsp_directory);
    append_common_android_adsp_paths(&adsp_paths);
    result.adsp_library_path = join_adsp_paths(adsp_paths);
    if (!result.adsp_library_path.empty()) {
        setenv("ADSP_LIBRARY_PATH", result.adsp_library_path.c_str(), 1);
    }
    result.rpc_message = result.rpc_present
        ? "libcdsprpc.so is present in the exact SDXL host runtime."
        : "libcdsprpc.so is absent from the exact SDXL host runtime; the platform may resolve it.";

    if (!probe) {
        result.message = "Exact SDXL QNN runtime files found; native load probe not requested.";
        return result;
    }
    ScopedDlHandle system_handle(dlopen(result.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
    if (!system_handle) {
        const char* load_error = dlerror();
        result.message = std::string("dlopen exact SDXL libQnnSystem.so failed: ") +
            (load_error ? load_error : "unknown error");
        return result;
    }
    result.qnn_system_interface_present =
        dlsym(system_handle.get(), "QnnSystemInterface_getProviders") != nullptr;
    result.loadable = true;
    result.message = result.qnn_system_interface_present
        ? "Exact SDXL QNN host metadata runtime loaded; host/DSP roles remain bound for graph load."
        : "Exact SDXL QNN System library loaded, but QnnSystemInterface_getProviders was not found.";
    std::string unload_error;
    if (!system_handle.close_checked(&unload_error)) {
        g_qnn_runtime_poisoned.store(true);
        result.loadable = false;
        result.qnn_system_interface_present = false;
        result.message += " Cleanup also failed: " + (unload_error.empty()
            ? "Exact SDXL QNN System preflight library unload failed."
            : unload_error);
        system_handle.abandon_without_unload();
    }
    return result;
}

bool exists_file(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && (st.st_mode & S_IFREG);
}

long long file_size_or_zero(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || !(st.st_mode & S_IFREG)) return 0;
    return static_cast<long long>(st.st_size);
}

bool exists_dir(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && (st.st_mode & S_IFDIR);
}

std::string join_path(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/' || dir.back() == '\\') return dir + name;
    return dir + "/" + name;
}

std::vector<std::string> parse_json_string_array(const std::string& json) {
    std::vector<std::string> values;
    std::string current;
    bool in_string = false;
    bool escape = false;
    for (char c : json) {
        if (!in_string) {
            if (c == '"') {
                in_string = true;
                current.clear();
            }
            continue;
        }
        if (escape) {
            switch (c) {
                case 'n': current.push_back('\n'); break;
                case 'r': current.push_back('\r'); break;
                case 't': current.push_back('\t'); break;
                default: current.push_back(c); break;
            }
            escape = false;
            continue;
        }
        if (c == '\\') {
            escape = true;
            continue;
        }
        if (c == '"') {
            in_string = false;
            if (!current.empty()) values.push_back(current);
            continue;
        }
        current.push_back(c);
    }
    return values;
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

std::string files_json(const std::vector<std::string>& values) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ",";
        out << quote(values[i]);
    }
    out << "]";
    return out.str();
}

std::vector<std::string> list_files_recursive(const std::string& root, int depth) {
    std::vector<std::string> files;
    if (root.empty() || depth > 8) return files;
#if !defined(_WIN32)
    DIR* dir = opendir(root.c_str());
    if (dir == nullptr) return files;
    while (dirent* entry = readdir(dir)) {
        std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        const std::string path = join_path(root, name);
        struct stat st {};
        if (stat(path.c_str(), &st) != 0) continue;
        if (st.st_mode & S_IFREG) {
            files.push_back(path);
        } else if (st.st_mode & S_IFDIR) {
            auto child = list_files_recursive(path, depth + 1);
            files.insert(files.end(), child.begin(), child.end());
        }
    }
    closedir(dir);
#else
    std::string search_path = join_path(root, "*");
    WIN32_FIND_DATAA fd;
    HANDLE hFind = FindFirstFileA(search_path.c_str(), &fd);
    if (hFind != INVALID_HANDLE_VALUE) {
        do {
            std::string name(fd.cFileName);
            if (name == "." || name == "..") continue;
            std::string path = join_path(root, name);
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
                auto child = list_files_recursive(path, depth + 1);
                files.insert(files.end(), child.begin(), child.end());
            } else {
                files.push_back(path);
            }
        } while (FindNextFileA(hFind, &fd));
        FindClose(hFind);
    }
#endif
    return files;
}

bool ends_with(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
        value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool contains_lower(std::string value, const std::string& token) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value.find(token) != std::string::npos;
}

bool is_safe_bundle_relative_path(const std::string& value) {
    if (value.empty()) return false;
    if (value[0] == '/' || value[0] == '\\') return false;
    if (value.size() >= 2 && std::isalpha(static_cast<unsigned char>(value[0])) && value[1] == ':') {
        return false;
    }
    std::string segment;
    for (char c : value) {
        const bool separator = c == '/' || c == '\\';
        if (separator) {
            if (segment.empty() || segment == "." || segment == "..") return false;
            segment.clear();
            continue;
        }
        segment.push_back(c);
    }
    return !segment.empty() && segment != "." && segment != "..";
}

} // namespace qnn
} // namespace mca
