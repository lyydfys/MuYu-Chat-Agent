#ifndef MCA_QNN_BACKEND_OWNER_HPP
#define MCA_QNN_BACKEND_OWNER_HPP

#include <string>
#include <memory>
#include <mutex>
#include <atomic>
#include "mca/runtime/backend_runtime.hpp"
#include "qnn_runtime_loader.hpp"
#include "qnn_capability_probe.hpp"

namespace mca {
namespace qnn {

/**
 * Backend runtime owner managing QNN backend, device, context, and allocator handles.
 * Inherits mca::runtime::BackendRuntime with BackendKind::QnnHtp.
 *
 * Enforces strict destruction ordering:
 * Context -> Device -> Backend -> Loader unloads.
 */
class QnnBackendOwner : public mca::runtime::BackendRuntime {
public:
    explicit QnnBackendOwner(
        std::shared_ptr<QnnRuntimeLoader> loader,
        std::string backend_name = "qnn-htp-backend"
    );
    ~QnnBackendOwner() override;

    QnnBackendOwner(const QnnBackendOwner&) = delete;
    QnnBackendOwner& operator=(const QnnBackendOwner&) = delete;

    /**
     * Initializes the backend using given DSO paths.
     */
    bool initialize(
        const std::string& system_path,
        const std::string& htp_path,
        const std::string& rpc_path = "",
        std::string* error = nullptr
    );

    /**
     * Initializes the backend from a probe report, applying generic fallback if needed.
     */
    bool initialize_with_probe(
        const QnnCapabilityProbe::CapabilityReport& report,
        std::string* error = nullptr
    );

    void release() noexcept override;
    [[nodiscard]] bool is_valid() const noexcept;

    [[nodiscard]] std::shared_ptr<QnnRuntimeLoader> get_loader() const noexcept { return loader_; }
    [[nodiscard]] const QnnCapabilityProbe::CapabilityReport& get_capability() const noexcept { return capability_; }

    [[nodiscard]] void* get_backend_handle() const noexcept { return backend_handle_; }
    [[nodiscard]] void* get_device_handle() const noexcept { return device_handle_; }
    [[nodiscard]] void* get_context_handle() const noexcept { return context_handle_; }

    void set_backend_handle(void* handle) noexcept;
    void set_device_handle(void* handle) noexcept;
    void set_context_handle(void* handle) noexcept;

private:
    std::shared_ptr<QnnRuntimeLoader> loader_;
    QnnCapabilityProbe::CapabilityReport capability_;

    void* backend_handle_{nullptr};
    void* device_handle_{nullptr};
    void* context_handle_{nullptr};

    mutable std::mutex mutex_;
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_BACKEND_OWNER_HPP
