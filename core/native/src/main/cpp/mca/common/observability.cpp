#include "observability.hpp"

namespace mca {
namespace common {

MetricsSink& MetricsSink::instance() noexcept {
    static MetricsSink sink;
    return sink;
}

void MetricsSink::record_counter(const std::string& name, int64_t delta) {
    std::lock_guard<std::mutex> lock(mutex_);
    counters_[name] += delta;
}

void MetricsSink::record_latency_ms(const std::string& operation, int64_t duration_ms) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto& list = latencies_[operation];
    if (list.size() < 1000) { // Limit max samples
        list.push_back(duration_ms);
    }
}

int64_t MetricsSink::get_counter(const std::string& name) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = counters_.find(name);
    return it != counters_.end() ? it->second : 0;
}

std::map<std::string, int64_t> MetricsSink::snapshot_counters() {
    std::lock_guard<std::mutex> lock(mutex_);
    return counters_;
}

void MetricsSink::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    counters_.clear();
    latencies_.clear();
}

} // namespace common
} // namespace mca
