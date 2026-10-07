#ifndef MCA_COMMON_MONOTONIC_CLOCK_HPP
#define MCA_COMMON_MONOTONIC_CLOCK_HPP

#include <cstdint>
#include <chrono>

namespace mca {
namespace common {

/**
 * High-resolution monotonic clock utilities.
 * Immune to system wall-clock adjustments or NTP jumps.
 */
class MonotonicClock {
public:
    using Clock = std::chrono::steady_clock;

    [[nodiscard]] static int64_t now_ms() noexcept {
        const auto now = Clock::now().time_since_epoch();
        return std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    }

    [[nodiscard]] static int64_t now_ns() noexcept {
        const auto now = Clock::now().time_since_epoch();
        return std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    }
};

/**
 * Deadline utility for timeout enforcement.
 */
class Deadline {
public:
    static Deadline from_timeout_ms(int64_t timeout_ms) noexcept {
        return Deadline(MonotonicClock::now_ms() + (timeout_ms > 0 ? timeout_ms : 0));
    }

    static Deadline infinite() noexcept {
        return Deadline(INT64_MAX);
    }

    [[nodiscard]] bool is_expired() const noexcept {
        return MonotonicClock::now_ms() >= deadline_ms_;
    }

    [[nodiscard]] int64_t remaining_ms() const noexcept {
        const int64_t rem = deadline_ms_ - MonotonicClock::now_ms();
        return rem > 0 ? rem : 0;
    }

    [[nodiscard]] int64_t target_ms() const noexcept {
        return deadline_ms_;
    }

private:
    explicit Deadline(int64_t deadline_ms) noexcept : deadline_ms_(deadline_ms) {}
    int64_t deadline_ms_{0};
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_MONOTONIC_CLOCK_HPP
