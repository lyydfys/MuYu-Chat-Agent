#ifndef MCA_MNN_BACKEND_OWNER_HPP
#define MCA_MNN_BACKEND_OWNER_HPP

#include <string>
#include <memory>
#include <map>
#include <mutex>
#include "mca/runtime/backend_runtime.hpp"
#include "mnn_contracts.hpp"
#include "mnn_runtime_loader.hpp"

namespace mca {
namespace mnn {

/**
 * Backend runtime managing MNN device context, allocator state, and thread pool.
 *
 * Inherits from mca::runtime::BackendRuntime.
 * Invariant: Device-local tuning values remain device-local; this does not restrict feature availability.
 */
class MnnBackendOwner : public mca::runtime::BackendRuntime {
public:
    MnnBackendOwner(
        MnnRuntimeConfig config,
        std::shared_ptr<MnnRuntimeLoader> loader = nullptr
    );
    ~MnnBackendOwner() override;

    [[nodiscard]] const MnnRuntimeConfig& get_config() const noexcept { return config_; }
    [[nodiscard]] MnnBackendType get_active_backend_type() const noexcept { return active_backend_type_; }
    [[nodiscard]] int32_t get_thread_count() const noexcept { return thread_count_; }

    /**
     * Initializes the backend context and allocates execution resources.
     * If OpenCL is requested but fails, automatically falls back to generic CPU compatible path.
     */
    bool initialize(std::string* error_msg = nullptr);

    /**
     * Releases backend context and clears allocations.
     */
    void release() noexcept override;

    /**
     * Checks validity of backend against active process runtime epoch.
     */
    [[nodiscard]] bool is_valid() const noexcept;

    /**
     * Stores and queries device-local tuning parameters (advisory only).
     */
    void set_tuning_parameter(const std::string& key, const std::string& value);
    [[nodiscard]] std::string get_tuning_parameter(const std::string& key) const;

private:
    MnnRuntimeConfig config_;
    std::shared_ptr<MnnRuntimeLoader> loader_;
    MnnBackendType active_backend_type_{MnnBackendType::Cpu};
    int32_t thread_count_{4};
    mutable std::mutex mutex_;
    std::map<std::string, std::string> tuning_map_;
    void* backend_handle_{nullptr};
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_BACKEND_OWNER_HPP
