#ifndef MCA_SD_RUNTIME_OWNER_HPP
#define MCA_SD_RUNTIME_OWNER_HPP

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "mca/runtime/backend_runtime.hpp"
#include "mca/runtime/process_runtime_host.hpp"
#include "sd_contracts.hpp"

namespace mca {
namespace sd {

/**
 * Memory allocator tracking for Stable Diffusion tensor and image buffers.
 */
class SdAllocator {
public:
    explicit SdAllocator(size_t max_budget_bytes = 2048ULL * 1024ULL * 1024ULL);
    ~SdAllocator() = default;

    [[nodiscard]] void* allocate(size_t bytes);
    void deallocate(void* ptr, size_t bytes) noexcept;

    [[nodiscard]] size_t get_allocated_bytes() const noexcept;
    [[nodiscard]] size_t get_peak_bytes() const noexcept;
    void reset_peak() noexcept;

private:
    size_t max_budget_bytes_;
    std::atomic<size_t> allocated_bytes_{0};
    std::atomic<size_t> peak_bytes_{0};
    mutable std::mutex mutex_;
};

/**
 * Sampler configuration and state owner.
 */
class SdSamplerOwner {
public:
    SdSamplerOwner();
    ~SdSamplerOwner() = default;

    void configure(SampleMethod method, SchedulerType scheduler);
    [[nodiscard]] SampleMethod get_sample_method() const noexcept { return method_; }
    [[nodiscard]] SchedulerType get_scheduler_type() const noexcept { return scheduler_; }

private:
    SampleMethod method_{SampleMethod::EulerA};
    SchedulerType scheduler_{SchedulerType::Default};
};

/**
 * Device admission descriptor documenting universal admission invariants.
 */
struct DeviceAdmissionStatus {
    bool admitted{true};
    bool is_generic_fallback{false};
    std::string detected_chipset;
    std::string profile_id;
    std::string admission_reason;
};

/**
 * Exclusive native owner for Stable Diffusion backend, models, samplers, and allocators.
 * Directly inherits mca::runtime::BackendRuntime to maintain strict epoch alignment with A3.
 */
class SdRuntimeOwner : public mca::runtime::BackendRuntime {
public:
    explicit SdRuntimeOwner(std::string runtime_name = "SdNativeRuntime");
    ~SdRuntimeOwner() override;

    // Epoch and Validity
    [[nodiscard]] bool is_valid() const noexcept;
    void release() noexcept override;

    // Universal Device Admission (MCA Hard Invariant)
    [[nodiscard]] DeviceAdmissionStatus evaluate_device_admission(
        const std::string& chipset,
        const std::string& device_model
    ) const noexcept;

    // Model Lifecycle Management
    bool load_model(
        const std::string& model_path,
        const std::string& bundle_root,
        const ImageInputContract& contract,
        std::string& error_out
    );
    void unload_model() noexcept;
    [[nodiscard]] bool is_model_loaded() const noexcept;
    [[nodiscard]] const std::string& get_active_model_path() const noexcept;
    [[nodiscard]] const std::string& get_model_cache_key() const noexcept;

    // Sampler & Allocator Access
    [[nodiscard]] SdAllocator& get_allocator() noexcept { return allocator_; }
    [[nodiscard]] const SdAllocator& get_allocator() const noexcept { return allocator_; }
    [[nodiscard]] SdSamplerOwner& get_sampler() noexcept { return sampler_; }
    [[nodiscard]] const SdSamplerOwner& get_sampler() const noexcept { return sampler_; }

    // Memory Tracking
    [[nodiscard]] uint64_t get_peak_memory_bytes() const noexcept;

    // Concurrency Guard
    [[nodiscard]] std::unique_lock<std::mutex> acquire_execution_lock();

private:
    mutable std::mutex lifecycle_mutex_;
    mutable std::mutex execution_mutex_;

    bool model_loaded_{false};
    std::string active_model_path_;
    std::string active_bundle_root_;
    std::string model_cache_key_;

    SdAllocator allocator_;
    SdSamplerOwner sampler_;
    std::atomic<uint64_t> total_model_loads_{0};
};

} // namespace sd
} // namespace mca

#endif // MCA_SD_RUNTIME_OWNER_HPP
