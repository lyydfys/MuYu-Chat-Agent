#ifndef MCA_COMMON_TEST_DOUBLE_INJECTION_HPP
#define MCA_COMMON_TEST_DOUBLE_INJECTION_HPP

/**
 * NON_PRODUCTION TEST DOUBLE INJECTION POINT
 *
 * CAUTION: This header is strictly for host testing, fault injection, and mock execution.
 * It MUST NEVER be compiled or linked into release targets.
 */

#if defined(MCA_RELEASE_BUILD)
#error "test_double_injection.hpp must NOT be included in production release builds!"
#endif

#include <cstdint>
#include <functional>
#include <atomic>

namespace mca {
namespace common {
namespace test_double {

/**
 * NON_PRODUCTION mock hook for testing fault injection and clock overrides.
 */
class NonProductionFaultInjector {
public:
    static void set_should_fail_next_allocation(bool fail) noexcept {
        should_fail_alloc_.store(fail, std::memory_order_relaxed);
    }

    [[nodiscard]] static bool should_fail_next_allocation() noexcept {
        return should_fail_alloc_.exchange(false, std::memory_order_relaxed);
    }

    static void set_clock_offset_ms(int64_t offset_ms) noexcept {
        clock_offset_ms_.store(offset_ms, std::memory_order_relaxed);
    }

    [[nodiscard]] static int64_t get_clock_offset_ms() noexcept {
        return clock_offset_ms_.load(std::memory_order_relaxed);
    }

    static void reset() noexcept {
        should_fail_alloc_.store(false, std::memory_order_relaxed);
        clock_offset_ms_.store(0, std::memory_order_relaxed);
    }

private:
    static inline std::atomic<bool> should_fail_alloc_{false};
    static inline std::atomic<int64_t> clock_offset_ms_{0};
};

} // namespace test_double
} // namespace common
} // namespace mca

#endif // MCA_COMMON_TEST_DOUBLE_INJECTION_HPP
