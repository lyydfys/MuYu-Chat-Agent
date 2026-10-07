#ifndef MCA_MNN_RUNTIME_LOADER_HPP
#define MCA_MNN_RUNTIME_LOADER_HPP

#include <string>
#include <memory>
#include <atomic>
#include <mutex>
#include "mnn_contracts.hpp"

namespace mca {
namespace mnn {

/**
 * Manages MNN and OpenCL runtime dynamic library loading and symbol resolution.
 *
 * Invariant: Never uses chipset or device profiles as admission blockers.
 * Unknown or uncertified devices automatically fall back to the generic compatible
 * path (e.g. CPU or standard OpenCL) and let native execution determine viability.
 */
class MnnRuntimeLoader {
public:
    static MnnRuntimeLoader& instance() noexcept;

    MnnRuntimeLoader(const MnnRuntimeLoader&) = delete;
    MnnRuntimeLoader& operator=(const MnnRuntimeLoader&) = delete;

    /**
     * Inspects device hardware capabilities to produce transport and tuning advice.
     * Note: Non-certified profiles are strictly advisory.
     */
    [[nodiscard]] HardwareProbeAdvisory probe_hardware() const;

    /**
     * Resolves and loads the MNN runtime libraries.
     */
    bool load_runtime(std::string* error_msg = nullptr);

    /**
     * Unloads runtime symbols and clears loaded state.
     */
    void unload_runtime() noexcept;

    [[nodiscard]] bool is_runtime_loaded() const noexcept;
    [[nodiscard]] bool is_opencl_available() const noexcept;
    [[nodiscard]] bool is_vulkan_available() const noexcept;
    [[nodiscard]] const std::string& get_version_string() const noexcept;

    /**
     * Checks if a backend can be attempted.
     * Universal Admission: Always true for compatible paths regardless of device profile.
     */
    [[nodiscard]] bool can_attempt_backend(MnnBackendType backend) const noexcept;

private:
    MnnRuntimeLoader();
    ~MnnRuntimeLoader();

    void probe_platform_features();

    mutable std::mutex mutex_;
    std::atomic<bool> is_loaded_{false};
    std::atomic<bool> opencl_available_{false};
    std::atomic<bool> vulkan_available_{false};
    std::string version_string_{"MNN-3.x-MCA"};
    void* opencl_lib_handle_{nullptr};
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_RUNTIME_LOADER_HPP
