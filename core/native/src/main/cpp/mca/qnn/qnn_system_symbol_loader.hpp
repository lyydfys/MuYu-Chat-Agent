#ifndef MCA_QNN_SYSTEM_SYMBOL_LOADER_HPP
#define MCA_QNN_SYSTEM_SYMBOL_LOADER_HPP

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_SYSTEM_LOADER_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnSystemInterface.h>)
#define MCA_QNN_SYSTEM_LOADER_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QnnSystemInterface.h>)
#define MCA_QNN_SYSTEM_LOADER_HAS_SDK 1
#else
#define MCA_QNN_SYSTEM_LOADER_HAS_SDK 0
#endif

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK
#if __has_include(<QNN/QnnInterface.h>)
#include <QNN/QnnInterface.h>
#include <QNN/System/QnnSystemInterface.h>
#else
#include <QnnInterface.h>
#include <QnnSystemInterface.h>
#endif
#endif

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace mca {
namespace qnn {

extern std::atomic<bool> g_qnn_runtime_poisoned;

class ScopedDlHandle {
public:
    explicit ScopedDlHandle(void* handle = nullptr) : handle_(handle) {}
    ScopedDlHandle(const ScopedDlHandle&) = delete;
    ScopedDlHandle& operator=(const ScopedDlHandle&) = delete;
    ~ScopedDlHandle() { close(); }

    bool close_checked(std::string* error) {
        if (handle_ == nullptr) return true;
#if !defined(_WIN32)
        if (dlclose(handle_) != 0) {
            const char* detail = dlerror();
            if (error != nullptr) {
                *error = "dlclose libQnnSystem failed";
                if (detail != nullptr) *error += std::string(": ") + detail;
            }
            return false;
        }
#else
        FreeLibrary(reinterpret_cast<HMODULE>(handle_));
#endif
        handle_ = nullptr;
        if (error != nullptr) error->clear();
        return true;
    }

    void abandon_without_unload() { handle_ = nullptr; }

    void close() {
        std::string ignored;
        close_checked(&ignored);
    }

    void* get() const { return handle_; }
    explicit operator bool() const { return handle_ != nullptr; }

private:
    void* handle_ = nullptr;
};

class ScopedReadOnlyMmap {
public:
    ScopedReadOnlyMmap() = default;
    ScopedReadOnlyMmap(const ScopedReadOnlyMmap&) = delete;
    ScopedReadOnlyMmap& operator=(const ScopedReadOnlyMmap&) = delete;
    ~ScopedReadOnlyMmap() { close(); }

    bool open_file(const std::string& path, std::string* error);
    bool close_checked(std::string* error);
    void abandon_without_unmap();
    void close();

    const uint8_t* data() const;
    size_t size() const { return size_; }
    // Same accessors the bridge's ScopedReadOnlyMap exposed (HEAD:217-218).  Callers such as
    // QnnExecutableGraph::load_in_session compare the mapped file's identity before accepting it,
    // but this header had dropped them while the callers were carried over verbatim.
#if !defined(_WIN32)
    dev_t device() const { return device_; }
    ino_t inode() const { return inode_; }
#endif
    bool empty() const { return data() == nullptr || size_ == 0; }

private:
    void* data_ = nullptr;
    size_t size_ = 0;
#if !defined(_WIN32)
    dev_t device_ = 0;
    ino_t inode_ = 0;
#endif
};

std::vector<uint8_t> read_binary_file(const std::string& path);

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

using QnnInterfaceGetProvidersFn =
    Qnn_ErrorHandle_t (*)(const QnnInterface_t*** providerList, uint32_t* numProviders);
using QnnSystemInterfaceGetProvidersFn =
    Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t*** providerList, uint32_t* numProviders);

struct QnnGraphMetadata {
    const char* name = nullptr;
    uint32_t input_count = 0;
    Qnn_Tensor_t* inputs = nullptr;
    uint32_t output_count = 0;
    Qnn_Tensor_t* outputs = nullptr;
};

std::string version_to_string(const Qnn_Version_t& version);
QnnGraphMetadata qnn_graph_metadata(const QnnSystemContext_GraphInfo_t& graph);
uint32_t qnn_binary_graph_count(const QnnSystemContext_BinaryInfo_t& info);
QnnSystemContext_GraphInfo_t* qnn_binary_graphs(const QnnSystemContext_BinaryInfo_t& info);
const QnnSystemInterface_t* select_qnn_system_interface(
        QnnSystemInterfaceGetProvidersFn get_providers,
        std::string* error);
const QnnInterface_t* select_qnn_interface(
        QnnInterfaceGetProvidersFn get_providers,
        std::string* error);
void append_graph_names(
        QnnSystemContext_GraphInfo_t* graphs,
        uint32_t graph_count,
        std::vector<std::string>* out);

std::string qnn_context_load_failure_message(
        const QNN_INTERFACE_VER_TYPE& api,
        Qnn_ErrorHandle_t error);

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const uint8_t* context_binary,
        size_t context_binary_size);

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const std::vector<uint8_t>& context_binary);

struct QnnLoadedRuntime {
    bool ok = false;
    void* rpc_handle = nullptr;
    void* system_handle = nullptr;
    void* htp_handle = nullptr;
    QnnInterfaceGetProvidersFn get_providers = nullptr;
    std::string message;

    QnnLoadedRuntime() = default;
    QnnLoadedRuntime(const QnnLoadedRuntime&) = delete;
    QnnLoadedRuntime& operator=(const QnnLoadedRuntime&) = delete;

    QnnLoadedRuntime(QnnLoadedRuntime&& other) noexcept {
        move_from(std::move(other));
    }

    QnnLoadedRuntime& operator=(QnnLoadedRuntime&& other) noexcept {
        if (this != &other) {
            close();
            move_from(std::move(other));
        }
        return *this;
    }

    ~QnnLoadedRuntime() { close(); }

    bool close_checked(std::string* error);
    void abandon_without_unload();
    void close();

private:
    void move_from(QnnLoadedRuntime&& other);
};

QnnLoadedRuntime load_qnn_runtime_for_graph(const RuntimeProbe& runtime);

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_SYMBOL_LOADER_HPP
