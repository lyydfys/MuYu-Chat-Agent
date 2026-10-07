#include "process_runtime_host.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace runtime {

ProcessRuntimeHost& ProcessRuntimeHost::instance() noexcept {
    static ProcessRuntimeHost host;
    return host;
}

ProcessRuntimeHost::ProcessRuntimeHost() = default;

void ProcessRuntimeHost::initialize() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_initialized_.load(std::memory_order_relaxed)) {
        is_initialized_.store(true, std::memory_order_relaxed);
        is_healthy_.store(true, std::memory_order_relaxed);
        is_shutting_down_.store(false, std::memory_order_relaxed);
        MCA_LOGI("RuntimeHost", "ProcessRuntimeHost initialized at epoch %lld",
                 static_cast<long long>(runtime_epoch_.load(std::memory_order_relaxed)));
    }
}

void ProcessRuntimeHost::shutdown() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_shutting_down_.load(std::memory_order_relaxed)) {
        is_shutting_down_.store(true, std::memory_order_relaxed);
        MCA_LOGI("RuntimeHost", "ProcessRuntimeHost initiated shutdown sequencing");
    }
}

int64_t ProcessRuntimeHost::get_runtime_epoch() const noexcept {
    return runtime_epoch_.load(std::memory_order_relaxed);
}

int64_t ProcessRuntimeHost::increment_epoch() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t next_epoch = runtime_epoch_.fetch_add(1, std::memory_order_relaxed) + 1;
    is_healthy_.store(true, std::memory_order_relaxed);
    MCA_LOGW("RuntimeHost", "Runtime epoch incremented to %lld (older callbacks invalidated)",
             static_cast<long long>(next_epoch));
    return next_epoch;
}

bool ProcessRuntimeHost::is_healthy() const noexcept {
    return is_healthy_.load(std::memory_order_relaxed) && !is_shutting_down_.load(std::memory_order_relaxed);
}

void ProcessRuntimeHost::mark_unhealthy() noexcept {
    is_healthy_.store(false, std::memory_order_relaxed);
    MCA_LOGE("RuntimeHost", "ProcessRuntimeHost marked UNHEALTHY. Isolation/recovery required.");
}

bool ProcessRuntimeHost::is_shutting_down() const noexcept {
    return is_shutting_down_.load(std::memory_order_relaxed);
}

} // namespace runtime
} // namespace mca
