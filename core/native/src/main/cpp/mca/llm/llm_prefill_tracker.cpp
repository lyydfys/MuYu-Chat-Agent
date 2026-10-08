#include "llm_prefill_tracker.hpp"

#include <algorithm>
#include <limits>
#include <sstream>

namespace mca {
namespace llm {

PrefillProgressTracker &PrefillProgressTracker::instance() noexcept {
    static PrefillProgressTracker instance;
    return instance;
}

void PrefillProgressTracker::reset() noexcept {
    completed_tokens_.store(0, std::memory_order_release);
    total_tokens_.store(0, std::memory_order_release);
}

void PrefillProgressTracker::begin(size_t total_tokens, size_t completed_tokens) noexcept {
    const int total = total_tokens > (size_t) std::numeric_limits<int>::max()
                      ? std::numeric_limits<int>::max()
                      : (int) total_tokens;
    const int completed = std::min(
            total,
            completed_tokens > (size_t) std::numeric_limits<int>::max()
            ? std::numeric_limits<int>::max()
            : (int) completed_tokens);
    total_tokens_.store(total, std::memory_order_release);
    completed_tokens_.store(completed, std::memory_order_release);
}

void PrefillProgressTracker::advance(size_t decoded_tokens) noexcept {
    const int total = total_tokens_.load(std::memory_order_acquire);
    if (total <= 0 || decoded_tokens == 0) return;
    const int increment = decoded_tokens > (size_t) std::numeric_limits<int>::max()
                          ? std::numeric_limits<int>::max()
                          : (int) decoded_tokens;
    int observed = completed_tokens_.load(std::memory_order_acquire);
    while (observed < total) {
        const int next = std::min(total, observed > total - increment ? total : observed + increment);
        if (completed_tokens_.compare_exchange_weak(
                observed,
                next,
                std::memory_order_release,
                std::memory_order_acquire)) {
            return;
        }
    }
}

void PrefillProgressTracker::report_reused(size_t reused_tokens) noexcept {
    const int total = total_tokens_.load(std::memory_order_acquire);
    if (total <= 0) return;
    const int reused = reused_tokens > (size_t) std::numeric_limits<int>::max()
                       ? std::numeric_limits<int>::max()
                       : (int) reused_tokens;
    int observed = completed_tokens_.load(std::memory_order_acquire);
    const int target = std::min(total, reused);
    while (observed < target) {
        if (completed_tokens_.compare_exchange_weak(
                observed,
                target,
                std::memory_order_release,
                std::memory_order_acquire)) {
            return;
        }
    }
}

int PrefillProgressTracker::get_completed_tokens() const noexcept {
    return completed_tokens_.load(std::memory_order_acquire);
}

int PrefillProgressTracker::get_total_tokens() const noexcept {
    return total_tokens_.load(std::memory_order_acquire);
}

PrefillProgressSnapshot PrefillProgressTracker::get_snapshot() const noexcept {
    const int total = std::max(0, total_tokens_.load(std::memory_order_acquire));
    const int completed = std::clamp(
            completed_tokens_.load(std::memory_order_acquire),
            0,
            total);
    return PrefillProgressSnapshot{completed, total};
}

std::string PrefillProgressTracker::to_json() const {
    const auto snapshot = get_snapshot();
    std::ostringstream out;
    out << "{\"completedTokens\":" << snapshot.completed_tokens
        << ",\"totalTokens\":" << snapshot.total_tokens << "}";
    return out.str();
}

void reset_prefill_progress() noexcept {
    PrefillProgressTracker::instance().reset();
}

void begin_prefill_progress(size_t total_tokens, size_t completed_tokens) noexcept {
    PrefillProgressTracker::instance().begin(total_tokens, completed_tokens);
}

void advance_prefill_progress(size_t decoded_tokens) noexcept {
    PrefillProgressTracker::instance().advance(decoded_tokens);
}

void report_reused_prefill_tokens(size_t reused_tokens) noexcept {
    PrefillProgressTracker::instance().report_reused(reused_tokens);
}

PrefillProgressSnapshot get_prefill_progress_snapshot() noexcept {
    return PrefillProgressTracker::instance().get_snapshot();
}

std::string get_prefill_progress_json() {
    return PrefillProgressTracker::instance().to_json();
}

} // namespace llm
} // namespace mca
