#include "qnn_system_symbol_loader.hpp"

#include <fstream>
#include <sstream>
#include <iomanip>
#include <limits>
#include <utility>

#include "mca/common/logging.hpp"
#include "mca/qnn/qnn_device_support.hpp"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "MCA-QNN";
}

std::atomic<bool> g_qnn_runtime_poisoned{false};

bool ScopedReadOnlyMmap::open_file(const std::string& path, std::string* error) {
    close();
#if !defined(_WIN32)
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (error) *error = "Unable to open QNN context binary for mmap: " + path;
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        ::close(fd);
        if (error) *error = "QNN context binary is missing or empty: " + path;
        return false;
    }
    if (static_cast<uint64_t>(st.st_size) >
        static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        ::close(fd);
        if (error) *error = "QNN context binary is too large to map: " + path;
        return false;
    }
    void* mapped = mmap(
        nullptr,
        static_cast<size_t>(st.st_size),
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE,
        fd,
        0
    );
    ::close(fd);
    if (mapped == MAP_FAILED) {
        if (error) *error = "Unable to mmap QNN context binary: " + path;
        return false;
    }
    data_ = mapped;
    size_ = static_cast<size_t>(st.st_size);
    device_ = st.st_dev;
    inode_ = st.st_ino;
    return true;
#else
    if (error) *error = "mmap is not supported on Windows";
    return false;
#endif
}

bool ScopedReadOnlyMmap::close_checked(std::string* error) {
    bool ok = true;
#if !defined(_WIN32)
    if (data_ != nullptr && data_ != MAP_FAILED) {
        if (munmap(data_, size_) != 0) {
            ok = false;
            if (error != nullptr) *error = "Unable to unmap QNN context binary.";
        }
        data_ = nullptr;
        size_ = 0;
        device_ = 0;
        inode_ = 0;
    }
#else
    data_ = nullptr;
    size_ = 0;
#endif
    return ok;
}

void ScopedReadOnlyMmap::abandon_without_unmap() {
    data_ = nullptr;
    size_ = 0;
#if !defined(_WIN32)
    device_ = 0;
    inode_ = 0;
#endif
}

void ScopedReadOnlyMmap::close() {
    std::string ignored;
    close_checked(&ignored);
}

const uint8_t* ScopedReadOnlyMmap::data() const {
#if !defined(_WIN32)
    return (data_ == nullptr || data_ == MAP_FAILED) ? nullptr : static_cast<const uint8_t*>(data_);
#else
    return static_cast<const uint8_t*>(data_);
#endif
}

std::vector<uint8_t> read_binary_file(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return {};
    return std::vector<uint8_t>(
        std::istreambuf_iterator<char>(file),
        std::istreambuf_iterator<char>()
    );
}

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

std::string version_to_string(const Qnn_Version_t& version) {
    std::ostringstream out;
    out << version.major << "." << version.minor << "." << version.patch;
    return out.str();
}

QnnGraphMetadata qnn_graph_metadata(const QnnSystemContext_GraphInfo_t& graph) {
    QnnGraphMetadata out;
    switch (graph.version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            out.name = graph.graphInfoV1.graphName;
            out.input_count = graph.graphInfoV1.numGraphInputs;
            out.inputs = graph.graphInfoV1.graphInputs;
            out.output_count = graph.graphInfoV1.numGraphOutputs;
            out.outputs = graph.graphInfoV1.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            out.name = graph.graphInfoV2.graphName;
            out.input_count = graph.graphInfoV2.numGraphInputs;
            out.inputs = graph.graphInfoV2.graphInputs;
            out.output_count = graph.graphInfoV2.numGraphOutputs;
            out.outputs = graph.graphInfoV2.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            out.name = graph.graphInfoV3.graphName;
            out.input_count = graph.graphInfoV3.numGraphInputs;
            out.inputs = graph.graphInfoV3.graphInputs;
            out.output_count = graph.graphInfoV3.numGraphOutputs;
            out.outputs = graph.graphInfoV3.graphOutputs;
            break;
        default:
            break;
    }
    return out;
}

uint32_t qnn_binary_graph_count(const QnnSystemContext_BinaryInfo_t& info) {
    switch (info.version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            return info.contextBinaryInfoV1.numGraphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            return info.contextBinaryInfoV2.numGraphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            return info.contextBinaryInfoV3.numGraphs;
        default:
            return 0;
    }
}

QnnSystemContext_GraphInfo_t* qnn_binary_graphs(const QnnSystemContext_BinaryInfo_t& info) {
    switch (info.version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            return info.contextBinaryInfoV1.graphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            return info.contextBinaryInfoV2.graphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            return info.contextBinaryInfoV3.graphs;
        default:
            return nullptr;
    }
}

const QnnSystemInterface_t* select_qnn_system_interface(
        QnnSystemInterfaceGetProvidersFn get_providers,
        std::string* error) {
    const QnnSystemInterface_t** providers = nullptr;
    uint32_t provider_count = 0;
    const Qnn_ErrorHandle_t status = get_providers(&providers, &provider_count);
    if (status != QNN_SUCCESS || providers == nullptr || provider_count == 0) {
        std::ostringstream out;
        out << "QnnSystemInterface_getProviders failed or returned no providers: 0x"
            << std::hex << status;
        if (error) *error = out.str();
        return nullptr;
    }
    for (uint32_t i = 0; i < provider_count; ++i) {
        const QnnSystemInterface_t* provider = providers[i];
        if (provider != nullptr &&
            provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextCreate != nullptr &&
            (provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetBinaryInfo != nullptr ||
             provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetMetaData != nullptr) &&
            provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree != nullptr) {
            return provider;
        }
    }
    if (error) *error = "No QNN system provider with SystemContext binary metadata APIs was found.";
    return nullptr;
}

const QnnInterface_t* select_qnn_interface(
        QnnInterfaceGetProvidersFn get_providers,
        std::string* error) {
    const QnnInterface_t** providers = nullptr;
    uint32_t provider_count = 0;
    const Qnn_ErrorHandle_t status = get_providers(&providers, &provider_count);
    if (status != QNN_SUCCESS || providers == nullptr || provider_count == 0) {
        std::ostringstream out;
        out << "QnnInterface_getProviders failed or returned no providers: 0x"
            << std::hex << status;
        if (error) *error = out.str();
        return nullptr;
    }
    for (uint32_t i = 0; i < provider_count; ++i) {
        const QnnInterface_t* provider = providers[i];
        if (provider == nullptr) continue;
        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        if (api.backendCreate != nullptr &&
            api.backendFree != nullptr &&
            api.contextCreateFromBinary != nullptr &&
            api.contextFree != nullptr &&
            api.graphRetrieve != nullptr &&
            api.graphExecute != nullptr) {
            return provider;
        }
    }
    if (error) *error = "No QNN provider exposes backend/context/graph execute functions.";
    return nullptr;
}

void append_graph_names(
        QnnSystemContext_GraphInfo_t* graphs,
        uint32_t graph_count,
        std::vector<std::string>* out) {
    if (graphs == nullptr || out == nullptr) return;
    for (uint32_t i = 0; i < graph_count && i < 16; ++i) {
        const auto& graph = graphs[i];
        const char* name = nullptr;
        switch (graph.version) {
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
                name = graph.graphInfoV1.graphName;
                break;
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
                name = graph.graphInfoV2.graphName;
                break;
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
                name = graph.graphInfoV3.graphName;
                break;
            default:
                break;
        }
        if (name != nullptr && name[0] != '\0') out->push_back(name);
    }
}

std::string qnn_context_load_failure_message(
        const QNN_INTERFACE_VER_TYPE& api,
        Qnn_ErrorHandle_t error) {
    std::string message = "QNN contextCreateFromBinary failed: " +
        qnn_error_to_string(api, error);
    if (error == QNN_CONTEXT_ERROR_INVALID_ARGUMENT ||
        error == QNN_CONTEXT_ERROR_BINARY_VERSION ||
        error == QNN_CONTEXT_ERROR_CREATE_FROM_BINARY ||
        error == QNN_CONTEXT_ERROR_BINARY_CONFIGURATION ||
        error == QNN_CONTEXT_ERROR_BINARY_SUBOPTIMAL) {
        message +=
            ". The context binary may not match this device's QNN runtime, "
            "target SoC, or SDK version. Rebuild or download a QNN bundle "
            "compiled for the device runtime before enabling NPU image generation.";
    }
    return message;
}

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const uint8_t* context_binary,
        size_t context_binary_size) {
    QnnBinaryMetadata metadata;
    metadata.attempted = true;
    if (context_binary == nullptr || context_binary_size == 0) {
        metadata.message = "Context binary is empty.";
        return metadata;
    }
    if (!runtime.system_present) {
        metadata.message = "libQnnSystem.so is missing.";
        return metadata;
    }

#if !defined(_WIN32)
    ScopedDlHandle system_handle(dlopen(runtime.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
    if (!system_handle) {
        const char* error = dlerror();
        metadata.message = std::string("dlopen libQnnSystem.so failed: ") +
            (error ? error : "unknown error");
        return metadata;
    }

    const auto fail_release = [&](const std::string& detail) {
        metadata.parsed = false;
        metadata.release_failed = true;
        g_qnn_runtime_poisoned.store(true);
        if (metadata.message.empty()) {
            metadata.message = detail;
        } else if (!detail.empty()) {
            metadata.message += " Cleanup also failed: " + detail;
        }
    };
    const auto close_system_handle = [&]() -> bool {
        std::string unload_error;
        if (system_handle.close_checked(&unload_error)) return true;
        fail_release(unload_error.empty()
            ? "QNN system metadata library unload failed."
            : unload_error);
        system_handle.abandon_without_unload();
        return false;
    };

    auto get_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(system_handle.get(), "QnnSystemInterface_getProviders")
    );
    if (get_providers == nullptr) {
        metadata.message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        close_system_handle();
        return metadata;
    }

    std::string provider_error;
    const QnnSystemInterface_t* provider = select_qnn_system_interface(get_providers, &provider_error);
    if (provider == nullptr) {
        metadata.message = provider_error;
        close_system_handle();
        return metadata;
    }
    const auto& api = provider->QNN_SYSTEM_INTERFACE_VER_NAME;

    QnnSystemContext_Handle_t sys_context = nullptr;
    const auto release_resources = [&]() -> bool {
        if (sys_context != nullptr) {
            const Qnn_ErrorHandle_t free_status = api.systemContextFree(sys_context);
            if (free_status != QNN_SUCCESS) {
                std::ostringstream detail;
                detail << "QnnSystemContext_free failed: 0x" << std::hex << free_status;
                fail_release(detail.str());
                system_handle.abandon_without_unload();
                sys_context = nullptr;
                return false;
            }
            sys_context = nullptr;
        }
        return close_system_handle();
    };

    Qnn_ErrorHandle_t status = api.systemContextCreate(&sys_context);
    if (status != QNN_SUCCESS || sys_context == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_create failed: 0x" << std::hex << status;
        metadata.message = out.str();
        release_resources();
        return metadata;
    }

    const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
    Qnn_ContextBinarySize_t binary_info_size = 0;
    if (api.systemContextGetBinaryInfo != nullptr) {
        status = api.systemContextGetBinaryInfo(
            sys_context,
            const_cast<uint8_t*>(context_binary),
            static_cast<uint64_t>(context_binary_size),
            &binary_info,
            &binary_info_size
        );
    } else {
        status = api.systemContextGetMetaData(
            sys_context,
            context_binary,
            static_cast<uint64_t>(context_binary_size),
            &binary_info
        );
    }
    if (status != QNN_SUCCESS || binary_info == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_getBinaryInfo failed: 0x" << std::hex << status;
        metadata.message = out.str();
        release_resources();
        return metadata;
    }

    metadata.version = static_cast<uint32_t>(binary_info->version);
    switch (binary_info->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: {
            const auto& info = binary_info->contextBinaryInfoV1;
            metadata.backend_id = info.backendId;
            metadata.build_id = info.buildId != nullptr ? info.buildId : "";
            metadata.core_api_version = version_to_string(info.coreApiVersion);
            metadata.backend_api_version = version_to_string(info.backendApiVersion);
            metadata.soc_version = info.socVersion != nullptr ? info.socVersion : "";
            metadata.context_blob_size = info.contextBlobSize;
            metadata.graph_count = info.numGraphs;
            append_graph_names(info.graphs, info.numGraphs, &metadata.graph_names);
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: {
            const auto& info = binary_info->contextBinaryInfoV2;
            metadata.backend_id = info.backendId;
            metadata.build_id = info.buildId != nullptr ? info.buildId : "";
            metadata.core_api_version = version_to_string(info.coreApiVersion);
            metadata.backend_api_version = version_to_string(info.backendApiVersion);
            metadata.soc_version = info.socVersion != nullptr ? info.socVersion : "";
            metadata.context_blob_size = info.contextBlobSize;
            metadata.graph_count = info.numGraphs;
            append_graph_names(info.graphs, info.numGraphs, &metadata.graph_names);
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: {
            const auto& info = binary_info->contextBinaryInfoV3;
            metadata.backend_id = info.backendId;
            metadata.build_id = info.buildId != nullptr ? info.buildId : "";
            metadata.core_api_version = version_to_string(info.coreApiVersion);
            metadata.backend_api_version = version_to_string(info.backendApiVersion);
            metadata.soc_version = info.socVersion != nullptr ? info.socVersion : "";
            metadata.context_blob_size = info.contextBlobSize;
            metadata.graph_count = info.numGraphs;
            metadata.soc_model = info.socModel;
            append_graph_names(info.graphs, info.numGraphs, &metadata.graph_names);
            break;
        }
        default:
            metadata.message = "QNN context binary metadata uses an unsupported version.";
            release_resources();
            return metadata;
    }

    std::ostringstream message;
    message << "QNN context binary metadata parsed";
    if (!metadata.build_id.empty()) message << "; buildId=" << metadata.build_id;
    if (!metadata.soc_version.empty()) message << "; socVersion=" << metadata.soc_version;
    if (metadata.soc_model != 0) message << "; socModel=" << metadata.soc_model;
    metadata.message = message.str();
    if (!release_resources()) return metadata;
    metadata.parsed = true;
#else
    metadata.message = "System context binary metadata inspection not supported on Windows.";
#endif
    return metadata;
}

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const std::vector<uint8_t>& context_binary) {
    return inspect_qnn_context_binary_metadata(
        runtime,
        context_binary.empty() ? nullptr : context_binary.data(),
        context_binary.size()
    );
}

bool QnnLoadedRuntime::close_checked(std::string* error) {
    bool all_ok = true;
    std::string failures;
#if !defined(_WIN32)
    const auto close_handle = [&](void*& handle, const char* role) -> bool {
        if (handle == nullptr) return true;
        if (dlclose(handle) != 0) {
            all_ok = false;
            const char* detail = dlerror();
            if (!failures.empty()) failures += "; ";
            failures += std::string("dlclose ") + role + " failed";
            if (detail != nullptr) failures += std::string(": ") + detail;
            return false;
        }
        handle = nullptr;
        return true;
    };
    const bool htp_closed = close_handle(htp_handle, "libQnnHtp");
    const bool system_closed = htp_closed
        ? close_handle(system_handle, "libQnnSystem")
        : false;
    if (system_closed) close_handle(rpc_handle, "libcdsprpc");
#else
    if (htp_handle) { FreeLibrary(reinterpret_cast<HMODULE>(htp_handle)); htp_handle = nullptr; }
    if (system_handle) { FreeLibrary(reinterpret_cast<HMODULE>(system_handle)); system_handle = nullptr; }
    if (rpc_handle) { FreeLibrary(reinterpret_cast<HMODULE>(rpc_handle)); rpc_handle = nullptr; }
#endif
    if (!all_ok) {
        g_qnn_runtime_poisoned.store(true);
        this->ok = false;
        get_providers = nullptr;
        if (error != nullptr) *error = failures;
        return false;
    }
    get_providers = nullptr;
    this->ok = false;
    return all_ok;
}

void QnnLoadedRuntime::abandon_without_unload() {
    ok = false;
    rpc_handle = nullptr;
    system_handle = nullptr;
    htp_handle = nullptr;
    get_providers = nullptr;
}

void QnnLoadedRuntime::close() {
    std::string ignored;
    close_checked(&ignored);
}

void QnnLoadedRuntime::move_from(QnnLoadedRuntime&& other) {
    ok = other.ok;
    rpc_handle = other.rpc_handle;
    system_handle = other.system_handle;
    htp_handle = other.htp_handle;
    get_providers = other.get_providers;
    message = std::move(other.message);
    other.ok = false;
    other.rpc_handle = nullptr;
    other.system_handle = nullptr;
    other.htp_handle = nullptr;
    other.get_providers = nullptr;
}

QnnLoadedRuntime load_qnn_runtime_for_graph(const RuntimeProbe& runtime) {
    QnnLoadedRuntime loaded;
#if !defined(_WIN32)
    if (!runtime.rpc_path.empty()) {
        loaded.rpc_handle = dlopen(runtime.rpc_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (loaded.rpc_handle == nullptr && runtime.rpc_present) {
            const char* error = dlerror();
#if defined(__ANDROID__)
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "dlopen libcdsprpc.so before graph load failed: %s",
                error ? error : "unknown error");
#else
            MCA_LOGW(kTag, "dlopen libcdsprpc.so before graph load failed: %s", error ? error : "unknown error");
#endif
        }
    }
    loaded.system_handle = dlopen(runtime.system_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (loaded.system_handle == nullptr) {
        const char* error = dlerror();
        loaded.message = std::string("dlopen libQnnSystem.so failed: ") +
            (error ? error : "unknown error");
        return loaded;
    }
    loaded.htp_handle = dlopen(runtime.htp_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (loaded.htp_handle == nullptr) {
        const char* error = dlerror();
        loaded.message = std::string("dlopen libQnnHtp.so failed: ") +
            (error ? error : "unknown error");
        return loaded;
    }
    for (void* handle : {loaded.htp_handle, loaded.system_handle}) {
        if (handle == nullptr) continue;
        loaded.get_providers = reinterpret_cast<QnnInterfaceGetProvidersFn>(
            dlsym(handle, "QnnInterface_getProviders")
        );
        if (loaded.get_providers != nullptr) break;
    }
    if (loaded.get_providers == nullptr) {
        loaded.message = "QnnInterface_getProviders was not found in loaded QNN libraries.";
        return loaded;
    }
    loaded.ok = true;
    loaded.message = "QNN runtime loaded for graph execution.";
#else
    loaded.message = "QNN runtime loading not supported on Windows.";
#endif
    return loaded;
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
