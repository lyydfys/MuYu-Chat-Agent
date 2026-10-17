// NOT COMPILED -- see the comment in core/native/src/main/cpp/CMakeLists.txt.
// This translation unit duplicates symbols that qnn_system_symbol_loader.{hpp,cpp} also define;
// compiling both made mca_qnn_runtime contain several strong definitions of the same symbol, so
// behaviour depended on link order.  Kept on disk for reference until its unique content is
// folded into qnn_system_symbol_loader or removed.
#include "mca/qnn/qnn_context_binary_cache.hpp"
#include "mca/qnn/qnn_device_support.hpp"

#include <fcntl.h>
#include <sys/stat.h>

#include <fstream>
#include <limits>
#include <sstream>
#include <utility>

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#endif

namespace mca {
namespace qnn {

void* const ScopedReadOnlyMmap::kMapFailed = reinterpret_cast<void*>(-1);

ScopedDlHandle::ScopedDlHandle(void* handle) : handle_(handle) {}

ScopedDlHandle::ScopedDlHandle(ScopedDlHandle&& other) noexcept : handle_(other.handle_) {
    other.handle_ = nullptr;
}

ScopedDlHandle& ScopedDlHandle::operator=(ScopedDlHandle&& other) noexcept {
    if (this != &other) {
        close();
        handle_ = other.handle_;
        other.handle_ = nullptr;
    }
    return *this;
}

ScopedDlHandle::~ScopedDlHandle() {
    close();
}

bool ScopedDlHandle::close_checked(std::string* error) {
    if (handle_ == nullptr) return true;
#if defined(_WIN32)
    HMODULE h = reinterpret_cast<HMODULE>(handle_);
    if (!FreeLibrary(h)) {
        if (error != nullptr) *error = "FreeLibrary failed";
        return false;
    }
#else
    if (dlclose(handle_) != 0) {
        const char* detail = dlerror();
        if (error != nullptr) {
            *error = "dlclose libQnnSystem failed";
            if (detail != nullptr) *error += std::string(": ") + detail;
        }
        return false;
    }
#endif
    handle_ = nullptr;
    if (error != nullptr) error->clear();
    return true;
}

void ScopedDlHandle::abandon_without_unload() {
    handle_ = nullptr;
}

void ScopedDlHandle::close() {
    std::string ignored;
    close_checked(&ignored);
}

void ScopedDlHandle::reset(void* handle) {
    close();
    handle_ = handle;
}

ScopedReadOnlyMmap::ScopedReadOnlyMmap() : data_(kMapFailed), size_(0), device_(0), inode_(0) {}

ScopedReadOnlyMmap::ScopedReadOnlyMmap(ScopedReadOnlyMmap&& other) noexcept {
    move_from(std::move(other));
}

ScopedReadOnlyMmap& ScopedReadOnlyMmap::operator=(ScopedReadOnlyMmap&& other) noexcept {
    if (this != &other) {
        close();
        move_from(std::move(other));
    }
    return *this;
}

ScopedReadOnlyMmap::~ScopedReadOnlyMmap() {
    close();
}

void ScopedReadOnlyMmap::move_from(ScopedReadOnlyMmap&& other) {
    data_ = other.data_;
    size_ = other.size_;
    device_ = other.device_;
    inode_ = other.inode_;
    other.data_ = kMapFailed;
    other.size_ = 0;
    other.device_ = 0;
    other.inode_ = 0;
}

bool ScopedReadOnlyMmap::open_file(const std::string& path, std::string* error) {
    close();
#if defined(_WIN32)
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) {
        if (error != nullptr) *error = "Unable to open QNN context binary: " + path;
        return false;
    }
    const auto file_size = file.tellg();
    if (file_size <= 0) {
        if (error != nullptr) *error = "QNN context binary is missing or empty: " + path;
        return false;
    }
    file.seekg(0, std::ios::beg);
    size_ = static_cast<size_t>(file_size);
    char* buf = new (std::nothrow) char[size_];
    if (buf == nullptr || !file.read(buf, size_)) {
        delete[] buf;
        if (error != nullptr) *error = "Unable to read QNN context binary: " + path;
        return false;
    }
    data_ = buf;
    device_ = 0;
    inode_ = 0;
    if (error != nullptr) error->clear();
    return true;
#else
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (error != nullptr) *error = "Unable to open QNN context binary for mmap: " + path;
        return false;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        ::close(fd);
        if (error != nullptr) *error = "QNN context binary is missing or empty: " + path;
        return false;
    }
    if (static_cast<uint64_t>(st.st_size) >
        static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        ::close(fd);
        if (error != nullptr) *error = "QNN context binary is too large to map: " + path;
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
        if (error != nullptr) *error = "Unable to mmap QNN context binary: " + path;
        return false;
    }
    data_ = mapped;
    size_ = static_cast<size_t>(st.st_size);
    device_ = st.st_dev;
    inode_ = st.st_ino;
    if (error != nullptr) error->clear();
    return true;
#endif
}

bool ScopedReadOnlyMmap::close_checked(std::string* error) {
    bool ok = true;
    if (data_ != nullptr && data_ != kMapFailed) {
#if defined(_WIN32)
        delete[] static_cast<char*>(data_);
#else
        if (munmap(data_, size_) != 0) {
            ok = false;
            if (error != nullptr) *error = "Unable to unmap QNN context binary.";
        }
#endif
        data_ = kMapFailed;
        size_ = 0;
        device_ = 0;
        inode_ = 0;
    }
    return ok;
}

void ScopedReadOnlyMmap::abandon_without_unmap() {
    data_ = kMapFailed;
    size_ = 0;
    device_ = 0;
    inode_ = 0;
}

void ScopedReadOnlyMmap::close() {
    std::string ignored;
    close_checked(&ignored);
}

const uint8_t* ScopedReadOnlyMmap::data() const {
    return (data_ == nullptr || data_ == kMapFailed)
        ? nullptr
        : static_cast<const uint8_t*>(data_);
}

std::vector<uint8_t> read_binary_file(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return {};
    return std::vector<uint8_t>(
        std::istreambuf_iterator<char>(file),
        std::istreambuf_iterator<char>()
    );
}

#if MCA_QNN_CONTEXT_CACHE_HAS_SDK

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
    if (get_providers == nullptr) {
        if (error != nullptr) *error = "Null get_providers function pointer.";
        return nullptr;
    }
    const QnnSystemInterface_t** providers = nullptr;
    uint32_t provider_count = 0;
    const Qnn_ErrorHandle_t status = get_providers(&providers, &provider_count);
    if (status != QNN_SUCCESS || providers == nullptr || provider_count == 0) {
        std::ostringstream out;
        out << "QnnSystemInterface_getProviders failed or returned no providers: 0x"
            << std::hex << status;
        if (error != nullptr) *error = out.str();
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
    if (error != nullptr) {
        *error = "No QNN system provider with SystemContext binary metadata APIs was found.";
    }
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

std::string version_to_string(const Qnn_Version_t& version) {
    std::ostringstream out;
    out << version.major << "." << version.minor << "." << version.patch;
    return out.str();
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
        size_t context_binary_size,
        std::atomic<bool>* poisoned_flag) {
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

#if defined(_WIN32)
    ScopedDlHandle system_handle(reinterpret_cast<void*>(LoadLibraryA(runtime.system_path.c_str())));
#else
    ScopedDlHandle system_handle(dlopen(runtime.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
#endif
    if (!system_handle) {
#if defined(_WIN32)
        metadata.message = "LoadLibraryA libQnnSystem failed.";
#else
        const char* error = dlerror();
        metadata.message = std::string("dlopen libQnnSystem.so failed: ") +
            (error ? error : "unknown error");
#endif
        return metadata;
    }

    const auto fail_release = [&](const std::string& detail) {
        metadata.parsed = false;
        metadata.release_failed = true;
        if (poisoned_flag != nullptr) {
            poisoned_flag->store(true);
        }
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

#if defined(_WIN32)
    auto get_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        GetProcAddress(reinterpret_cast<HMODULE>(system_handle.get()), "QnnSystemInterface_getProviders"));
#else
    auto get_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(system_handle.get(), "QnnSystemInterface_getProviders"));
#endif
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
    return metadata;
}

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const std::vector<uint8_t>& context_binary,
        std::atomic<bool>* poisoned_flag) {
    return inspect_qnn_context_binary_metadata(
        runtime,
        context_binary.empty() ? nullptr : context_binary.data(),
        context_binary.size(),
        poisoned_flag
    );
}

#endif // MCA_QNN_CONTEXT_CACHE_HAS_SDK

} // namespace qnn
} // namespace mca
