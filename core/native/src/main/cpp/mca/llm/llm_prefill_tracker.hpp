#ifndef MCA_LLM_LLM_PREFILL_TRACKER_HPP
#define MCA_LLM_LLM_PREFILL_TRACKER_HPP

#include <atomic>
#include <cstddef>
#include <string>

namespace mca {
namespace llm {

struct PrefillProgressSnapshot {
    int completed_tokens{0};
    int total_tokens{0};
};

/**
 * Thread-safe tracker for prompt prefill progress and token reuse.
 *
 * beginCompletion() holds the engine mutex for the entire native prefill,
 * while the UI polls this progress record from another JVM thread.
 * Plain atomic operations ensure lock-free progress inspection without
 * contending with inference execution.
 */
class PrefillProgressTracker {
public:
    static PrefillProgressTracker &instance() noexcept;

    void reset() noexcept;
    void begin(size_t total_tokens, size_t completed_tokens = 0) noexcept;
    void advance(size_t decoded_tokens) noexcept;
    void report_reused(size_t reused_tokens) noexcept;

    [[nodiscard]] int get_completed_tokens() const noexcept;
    [[nodiscard]] int get_total_tokens() const noexcept;
    [[nodiscard]] PrefillProgressSnapshot get_snapshot() const noexcept;
    [[nodiscard]] std::string to_json() const;

private:
    PrefillProgressTracker() = default;
    ~PrefillProgressTracker() = default;
    PrefillProgressTracker(const PrefillProgressTracker &) = delete;
    PrefillProgressTracker &operator=(const PrefillProgressTracker &) = delete;

    std::atomic<int> completed_tokens_{0};
    std::atomic<int> total_tokens_{0};
};

// Functional interfaces delegating to PrefillProgressTracker::instance()
void reset_prefill_progress() noexcept;
void begin_prefill_progress(size_t total_tokens, size_t completed_tokens = 0) noexcept;
void advance_prefill_progress(size_t decoded_tokens) noexcept;
void report_reused_prefill_tokens(size_t reused_tokens) noexcept;
PrefillProgressSnapshot get_prefill_progress_snapshot() noexcept;
std::string get_prefill_progress_json();

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLM_PREFILL_TRACKER_HPP
