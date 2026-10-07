#ifndef MCA_RUNTIME_PROCESS_RUNTIME_HOST_HPP
#define MCA_RUNTIME_PROCESS_RUNTIME_HOST_HPP

#include <cstdint>
#include <atomic>
#include <memory>
#include <mutex>
#include "mca/contract/request_identity.hpp"

namespace mca {
namespace runtime {

/**
 * Process-level runtime host.
 *
 * Exclusively owns process-level initialization, monotonic runtime epoch tracking,
 * runtime health status, and global shutdown sequencing.
 */
class ProcessRuntimeHost {
public:
    static ProcessRuntimeHost& instance() noexcept;

    void initialize();
    void shutdown();

    [[nodiscard]] int64_t get_runtime_epoch() const noexcept;
    int64_t increment_epoch() noexcept;

    [[nodiscard]] bool is_healthy() const noexcept;
    void mark_unhealthy() noexcept;

    [[nodiscard]] bool is_shutting_down() const noexcept;

private:
    ProcessRuntimeHost();
    ~ProcessRuntimeHost() = default;

    std::atomic<int64_t> runtime_epoch_{1};
    std::atomic<bool> is_healthy_{true};
    std::atomic<bool> is_initialized_{false};
    std::atomic<bool> is_shutting_down_{false};
    mutable std::mutex mutex_;
};

} // namespace runtime
} // namespace mca

#endif // MCA_RUNTIME_PROCESS_RUNTIME_HOST_HPP
