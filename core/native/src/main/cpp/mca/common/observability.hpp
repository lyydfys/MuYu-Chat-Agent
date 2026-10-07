#ifndef MCA_COMMON_OBSERVABILITY_HPP
#define MCA_COMMON_OBSERVABILITY_HPP

#include <string>
#include <cstdint>
#include <map>
#include <mutex>
#include <vector>

namespace mca {
namespace common {

/**
 * Distributed tracing context.
 */
struct TraceContext {
    std::string trace_id;
    std::string span_id;
    std::string parent_span_id;

    [[nodiscard]] bool is_valid() const noexcept {
        return !trace_id.empty() && !span_id.empty();
    }
};

/**
 * Lightweight metrics recording sink for native inference.
 */
class MetricsSink {
public:
    static MetricsSink& instance() noexcept;

    void record_counter(const std::string& name, int64_t delta = 1);
    void record_latency_ms(const std::string& operation, int64_t duration_ms);

    [[nodiscard]] int64_t get_counter(const std::string& name);
    [[nodiscard]] std::map<std::string, int64_t> snapshot_counters();

    void reset();

private:
    MetricsSink() = default;
    std::mutex mutex_;
    std::map<std::string, int64_t> counters_;
    std::map<std::string, std::vector<int64_t>> latencies_;
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_OBSERVABILITY_HPP
