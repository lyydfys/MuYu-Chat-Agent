#ifndef MCA_QNN_RUNTIME_LOADER_HPP
#define MCA_QNN_RUNTIME_LOADER_HPP

#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include "mca/contract/stable_error_code.hpp"
#include "mca/contract/error_envelope.hpp"

namespace mca {
namespace qnn {

/**
 * Dynamic symbol and library loader for QNN host, HTP backend, and RPC transports.
 * Enforces strict LIFO unload ordering, failure isolation, and poisoning detection.
 * Invariant: Never checks device allowlists/whitelists; loads any valid candidate requested.
 */
class QnnRuntimeLoader {
public:
    QnnRuntimeLoader();
    ~QnnRuntimeLoader();

    QnnRuntimeLoader(const QnnRuntimeLoader&) = delete;
    QnnRuntimeLoader& operator=(const QnnRuntimeLoader&) = delete;

    /**
     * Loads the QNN System metadata library (e.g., libQnnSystem.so).
     */
    bool load_system(const std::string& path, std::string* error = nullptr);

    /**
     * Loads the QNN HTP backend library (e.g., libQnnHtp.so).
     */
    bool load_htp(const std::string& path, std::string* error = nullptr);

    /**
     * Loads the FastRPC transport library (e.g., libcdsprpc.so) if present.
     */
    bool load_rpc(const std::string& path, std::string* error = nullptr);

    /**
     * Unloads all loaded libraries in strict reverse order (HTP -> System -> RPC).
     * If any unload fails, the loader marks itself poisoned.
     */
    bool unload_all() noexcept;

    [[nodiscard]] bool is_system_loaded() const noexcept;
    [[nodiscard]] bool is_htp_loaded() const noexcept;
    [[nodiscard]] bool is_rpc_loaded() const noexcept;
    [[nodiscard]] bool is_poisoned() const noexcept;

    [[nodiscard]] bool has_qnn_interface() const noexcept;
    [[nodiscard]] bool has_qnn_system_interface() const noexcept;

    [[nodiscard]] void* get_qnn_interface_fn() const noexcept;
    [[nodiscard]] void* get_qnn_system_interface_fn() const noexcept;

    [[nodiscard]] std::string get_last_error() const;
    [[nodiscard]] contract::ErrorEnvelope get_last_error_envelope() const;

private:
    static void* native_dlopen(const std::string& path, std::string* error_msg);
    static void* native_dlsym(void* handle, const char* symbol, std::string* error_msg);
    static bool native_dlclose(void* handle, std::string* error_msg);

    void* rpc_handle_{nullptr};
    void* system_handle_{nullptr};
    void* htp_handle_{nullptr};

    void* fn_qnn_interface_{nullptr};
    void* fn_qnn_system_interface_{nullptr};

    std::atomic<bool> is_rpc_loaded_{false};
    std::atomic<bool> is_system_loaded_{false};
    std::atomic<bool> is_htp_loaded_{false};
    std::atomic<bool> is_poisoned_{false};

    mutable std::mutex mutex_;
    std::string last_error_;
    std::string system_path_;
    std::string htp_path_;
    std::string rpc_path_;
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_RUNTIME_LOADER_HPP
