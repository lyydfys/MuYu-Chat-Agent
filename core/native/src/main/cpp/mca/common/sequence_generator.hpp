#ifndef MCA_COMMON_SEQUENCE_GENERATOR_HPP
#define MCA_COMMON_SEQUENCE_GENERATOR_HPP

#include <atomic>
#include <cstdint>

namespace mca {
namespace common {

/**
 * Thread-safe monotonic sequence generator.
 * Used for intra-attempt event ordering and callback routing.
 */
class SequenceGenerator {
public:
    explicit SequenceGenerator(int64_t initial_sequence = 0) noexcept
        : sequence_(initial_sequence) {}

    /**
     * Atomically increments and returns the next sequence number.
     */
    int64_t next() noexcept {
        return sequence_.fetch_add(1, std::memory_order_relaxed);
    }

    /**
     * Reads the current sequence value without incrementing.
     */
    [[nodiscard]] int64_t current() const noexcept {
        return sequence_.load(std::memory_order_relaxed);
    }

    /**
     * Resets the sequence counter to a given base value.
     */
    void reset(int64_t new_base = 0) noexcept {
        sequence_.store(new_base, std::memory_order_relaxed);
    }

private:
    std::atomic<int64_t> sequence_{0};
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_SEQUENCE_GENERATOR_HPP
