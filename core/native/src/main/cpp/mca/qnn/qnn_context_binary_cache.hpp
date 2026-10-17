#ifndef MCA_QNN_CONTEXT_BINARY_CACHE_HPP
#define MCA_QNN_CONTEXT_BINARY_CACHE_HPP

#include <cstdint>
#include <string>
#include <vector>
#include <atomic>
#include <sys/types.h>

#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_CONTEXT_CACHE_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnSystemInterface.h>)
#define MCA_QNN_CONTEXT_CACHE_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QnnSystemInterface.h>)
#define MCA_QNN_CONTEXT_CACHE_HAS_SDK 1
#else
#define MCA_QNN_CONTEXT_CACHE_HAS_SDK 0
#endif

#if MCA_QNN_CONTEXT_CACHE_HAS_SDK
#if __has_include(<QNN/QnnCommon.h>)
#include <QNN/QnnCommon.h>
#include <QNN/QnnTypes.h>
#include <QNN/QnnContext.h>
#include <QNN/QnnInterface.h>
#include <QNN/System/QnnSystemInterface.h>
#else
#include <QnnCommon.h>
#include <QnnTypes.h>
#include <QnnContext.h>
#include <QnnInterface.h>
#include <QnnSystemInterface.h>
#endif
#endif

namespace mca {
namespace qnn {

/**
 * Scoped RAII wrapper for dynamic library handles with failure diagnostics.
 */
class ScopedDlHandle {
public:
    explicit ScopedDlHandle(void* handle = nullptr);
    ScopedDlHandle(const ScopedDlHandle&) = delete;
    ScopedDlHandle& operator=(const ScopedDlHandle&) = delete;
    ScopedDlHandle(ScopedDlHandle&& other) noexcept;
    ScopedDlHandle& operator=(ScopedDlHandle&& other) noexcept;
    ~ScopedDlHandle();

    bool close_checked(std::string* error = nullptr);
    void abandon_without_unload();
    void close();

    void* get() const { return handle_; }
    void reset(void* handle = nullptr);
    explicit operator bool() const { return handle_ != nullptr; }

private:
    void* handle_ = nullptr;
};

/**
 * Read-only memory-mapped file guard specifically suited for QNN context binaries.
 * Uses MAP_PRIVATE writable mapping on POSIX to satisfy QNN legacy binary-info
 * mutability requirements without modifying the underlying bundle file on disk.
 */
class ScopedReadOnlyMmap {
public:
    ScopedReadOnlyMmap();
    ScopedReadOnlyMmap(const ScopedReadOnlyMmap&) = delete;
    ScopedReadOnlyMmap& operator=(const ScopedReadOnlyMmap&) = delete;
    ScopedReadOnlyMmap(ScopedReadOnlyMmap&& other) noexcept;
    ScopedReadOnlyMmap& operator=(ScopedReadOnlyMmap&& other) noexcept;
    ~ScopedReadOnlyMmap();

    bool open_file(const std::string& path, std::string* error);
    bool close_checked(std::string* error = nullptr);
    void abandon_without_unmap();
    void close();

    const uint8_t* data() const;
    size_t size() const { return size_; }
    dev_t device() const { return device_; }
    ino_t inode() const { return inode_; }
    bool empty() const { return data() == nullptr || size_ == 0; }

private:
    static void* const kMapFailed;
    void move_from(ScopedReadOnlyMmap&& other);

    void* data_ = nullptr;
    size_t size_ = 0;
    dev_t device_ = 0;
    ino_t inode_ = 0;
};

/**
 * Reads binary file contents into an owned byte vector.
 */
std::vector<uint8_t> read_binary_file(const std::string& path);

#if MCA_QNN_CONTEXT_CACHE_HAS_SDK

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

QnnGraphMetadata qnn_graph_metadata(const QnnSystemContext_GraphInfo_t& graph);
uint32_t qnn_binary_graph_count(const QnnSystemContext_BinaryInfo_t& info);
QnnSystemContext_GraphInfo_t* qnn_binary_graphs(const QnnSystemContext_BinaryInfo_t& info);

const QnnSystemInterface_t* select_qnn_system_interface(
    QnnSystemInterfaceGetProvidersFn get_providers,
    std::string* error);

void append_graph_names(
    QnnSystemContext_GraphInfo_t* graphs,
    uint32_t graph_count,
    std::vector<std::string>* out);

std::string version_to_string(const Qnn_Version_t& version);

std::string qnn_context_load_failure_message(
    const QNN_INTERFACE_VER_TYPE& api,
    Qnn_ErrorHandle_t error);

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
    const RuntimeProbe& runtime,
    const uint8_t* context_binary,
    size_t context_binary_size,
    std::atomic<bool>* poisoned_flag = nullptr);

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
    const RuntimeProbe& runtime,
    const std::vector<uint8_t>& context_binary,
    std::atomic<bool>* poisoned_flag = nullptr);

#endif // MCA_QNN_CONTEXT_CACHE_HAS_SDK

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_CONTEXT_BINARY_CACHE_HPP
