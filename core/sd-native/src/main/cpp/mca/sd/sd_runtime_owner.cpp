#include "sd_runtime_owner.hpp"

#include <cstdlib>
#include <new>
#include <sstream>

#include "mca/common/logging.hpp"

namespace mca {
namespace sd {

// ============================================================================
// SdAllocator Implementation
// ============================================================================

SdAllocator::SdAllocator(size_t max_budget_bytes)
    : max_budget_bytes_(max_budget_bytes) {
}

void* SdAllocator::allocate(size_t bytes) {
    if (bytes == 0) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    const size_t current = allocated_bytes_.load(std::memory_order_relaxed);
    if (current + bytes > max_budget_bytes_) {
        MCA_LOGW("SdAllocator",
                 "Allocation of %zu bytes exceeds budget limit %zu (current: %zu)",
                 bytes, max_budget_bytes_, current);
        // Note: Fall back gracefully or allocate if system permits, but log warning
    }

    void* ptr = std::malloc(bytes);
    if (ptr == nullptr) {
        MCA_LOGE("SdAllocator", "Out of memory allocating %zu bytes", bytes);
        throw std::bad_alloc();
    }

    const size_t updated = current + bytes;
    allocated_bytes_.store(updated, std::memory_order_relaxed);

    size_t peak = peak_bytes_.load(std::memory_order_relaxed);
    while (updated > peak &&
           !peak_bytes_.compare_exchange_weak(peak, updated, std::memory_order_relaxed)) {
        // loop until peak updated or updated <= peak
    }

    return ptr;
}

void SdAllocator::deallocate(void* ptr, size_t bytes) noexcept {
    if (ptr == nullptr) {
        return;
    }
    std::free(ptr);
    if (bytes > 0) {
        std::lock_guard<std::mutex> lock(mutex_);
        const size_t current = allocated_bytes_.load(std::memory_order_relaxed);
        if (bytes > current) {
            allocated_bytes_.store(0, std::memory_order_relaxed);
        } else {
            allocated_bytes_.store(current - bytes, std::memory_order_relaxed);
        }
    }
}

size_t SdAllocator::get_allocated_bytes() const noexcept {
    return allocated_bytes_.load(std::memory_order_relaxed);
}

size_t SdAllocator::get_peak_bytes() const noexcept {
    return peak_bytes_.load(std::memory_order_relaxed);
}

void SdAllocator::reset_peak() noexcept {
    peak_bytes_.store(allocated_bytes_.load(std::memory_order_relaxed),
                      std::memory_order_relaxed);
}

// ============================================================================
// SdSamplerOwner Implementation
// ============================================================================

SdSamplerOwner::SdSamplerOwner() = default;

void SdSamplerOwner::configure(SampleMethod method, SchedulerType scheduler) {
    method_ = method;
    scheduler_ = scheduler;
    MCA_LOGI("SdSamplerOwner", "Configured sampler: method=%d, scheduler=%d",
             static_cast<int>(method), static_cast<int>(scheduler));
}

// ============================================================================
// SdRuntimeOwner Implementation
// ============================================================================

SdRuntimeOwner::SdRuntimeOwner(std::string runtime_name)
    : BackendRuntime(mca::runtime::BackendKind::StableDiffusion, std::move(runtime_name)) {
    MCA_LOGI("SdRuntimeOwner",
             "Initialized SdRuntimeOwner '%s' under epoch %lld",
             name_.c_str(),
             static_cast<long long>(created_epoch_));
}

SdRuntimeOwner::~SdRuntimeOwner() {
    unload_model();
    release();
}

bool SdRuntimeOwner::is_valid() const noexcept {
    if (!is_active_.load(std::memory_order_relaxed)) {
        return false;
    }
    const int64_t current_epoch = mca::runtime::ProcessRuntimeHost::instance().get_runtime_epoch();
    if (created_epoch_ != current_epoch) {
        MCA_LOGW("SdRuntimeOwner",
                 "SdRuntimeOwner epoch mismatch: created=%lld, current=%lld",
                 static_cast<long long>(created_epoch_),
                 static_cast<long long>(current_epoch));
        return false;
    }
    return true;
}

void SdRuntimeOwner::release() noexcept {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (is_active_.exchange(false, std::memory_order_relaxed)) {
        MCA_LOGI("SdRuntimeOwner", "Releasing SdRuntimeOwner '%s'", name_.c_str());
        if (model_loaded_) {
            model_loaded_ = false;
            active_model_path_.clear();
            active_bundle_root_.clear();
            model_cache_key_.clear();
        }
    }
}

DeviceAdmissionStatus SdRuntimeOwner::evaluate_device_admission(
    const std::string& chipset,
    const std::string& device_model
) const noexcept {
    // UNIVERSAL DEVICE ADMISSION POLICY (Hard Invariant):
    // Never reject or block based on chipset, device model, or absence of profile.
    // Detection is advisory only; unknown hardware falls back to generic compatible path.
    DeviceAdmissionStatus status;
    status.admitted = true;
    status.detected_chipset = chipset;

    if (chipset.empty() || chipset == "unknown" || chipset == "generic") {
        status.is_generic_fallback = true;
        status.profile_id = "generic_arm64_or_cpu";
        status.admission_reason =
            "Universal device admission: unknown chipset routed to generic compatible path";
        MCA_LOGI("SdRuntimeOwner",
                 "Universal admission granted via generic path for device '%s' (chipset: '%s')",
                 device_model.c_str(), chipset.c_str());
    } else {
        status.is_generic_fallback = false;
        status.profile_id = chipset + "_profile";
        status.admission_reason =
            "Device recognized; advisory tuning available while preserving generic compatibility";
        MCA_LOGI("SdRuntimeOwner",
                 "Universal admission granted with advisory profile '%s' for '%s'",
                 status.profile_id.c_str(), device_model.c_str());
    }

    return status;
}

bool SdRuntimeOwner::load_model(
    const std::string& model_path,
    const std::string& bundle_root,
    const ImageInputContract& contract,
    std::string& error_out
) {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    error_out.clear();

    if (!is_valid()) {
        error_out = "SdRuntimeOwner is invalid or runtime epoch has advanced";
        MCA_LOGE("SdRuntimeOwner", "%s", error_out.c_str());
        return false;
    }

    if (model_path.empty()) {
        error_out = "Model path cannot be empty";
        MCA_LOGE("SdRuntimeOwner", "%s", error_out.c_str());
        return false;
    }

    // Build unique cache key
    std::ostringstream ss;
    ss << model_path << "|" << bundle_root << "|" << contract.clip_skip << "|"
       << contract.loras.size() << "|" << contract.textual_inversions.size();
    const std::string new_cache_key = ss.str();

    if (model_loaded_ && model_cache_key_ == new_cache_key) {
        MCA_LOGI("SdRuntimeOwner", "Reusing cached model: %s", model_path.c_str());
        return true;
    }

    // If another model was loaded, unload it first
    if (model_loaded_) {
        MCA_LOGI("SdRuntimeOwner", "Unloading previous model: %s", active_model_path_.c_str());
        model_loaded_ = false;
        active_model_path_.clear();
        active_bundle_root_.clear();
        model_cache_key_.clear();
    }

    // Configure sampler from contract
    sampler_.configure(contract.sample_method, contract.scheduler);

    // Track active model
    active_model_path_ = model_path;
    active_bundle_root_ = bundle_root;
    model_cache_key_ = new_cache_key;
    model_loaded_ = true;
    total_model_loads_.fetch_add(1, std::memory_order_relaxed);

    MCA_LOGI("SdRuntimeOwner",
             "Successfully loaded model '%s' (total loads: %llu)",
             model_path.c_str(),
             static_cast<unsigned long long>(total_model_loads_.load(std::memory_order_relaxed)));
    return true;
}

void SdRuntimeOwner::unload_model() noexcept {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    if (model_loaded_) {
        MCA_LOGI("SdRuntimeOwner", "Unloading model '%s'", active_model_path_.c_str());
        model_loaded_ = false;
        active_model_path_.clear();
        active_bundle_root_.clear();
        model_cache_key_.clear();
    }
}

bool SdRuntimeOwner::is_model_loaded() const noexcept {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    return model_loaded_ && is_valid();
}

const std::string& SdRuntimeOwner::get_active_model_path() const noexcept {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    return active_model_path_;
}

const std::string& SdRuntimeOwner::get_model_cache_key() const noexcept {
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    return model_cache_key_;
}

uint64_t SdRuntimeOwner::get_peak_memory_bytes() const noexcept {
    return static_cast<uint64_t>(allocator_.get_peak_bytes());
}

std::unique_lock<std::mutex> SdRuntimeOwner::acquire_execution_lock() {
    return std::unique_lock<std::mutex>(execution_mutex_);
}

} // namespace sd
} // namespace mca
