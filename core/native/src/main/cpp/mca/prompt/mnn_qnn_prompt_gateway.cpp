#include "mnn_qnn_prompt_gateway.hpp"
#include "mca/common/monotonic_clock.hpp"
#include "mca/common/safe_path.hpp"
#include "mca/common/logging.hpp"
#include <sstream>

namespace mca {
namespace prompt {

MnnQnnPromptGateway& MnnQnnPromptGateway::instance() noexcept {
    static MnnQnnPromptGateway s_gateway;
    return s_gateway;
}

bool MnnQnnPromptGateway::issue_handoff(
    const PromptHandoffRecord& record,
    std::string& out_handle,
    std::string& error_msg
) {
    if (common::SafePath::has_traversal(record.tokenizer_path)) {
        error_msg = "Security: Insecure path traversal detected in tokenizer_path";
        MCA_LOGE("MnnQnnPromptGateway", "%s", error_msg.c_str());
        return false;
    }

    if (record.prompt_pair_sha256.empty() && record.payload_sha256.empty()) {
        error_msg = "Validation: Both prompt_pair_sha256 and payload_sha256 are empty";
        MCA_LOGE("MnnQnnPromptGateway", "%s", error_msg.c_str());
        return false;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t now_ms = common::MonotonicClock::now_ms();
    const uint64_t seq = ++handle_counter_;

    std::ostringstream ss;
    ss << "hnd_" << now_ms << "_" << seq;
    out_handle = ss.str();

    PromptHandoffRecord stored = record;
    stored.created_timestamp_ms = now_ms;
    active_records_[out_handle] = std::move(stored);

    MCA_LOGI("MnnQnnPromptGateway", "Issued prompt handoff lease [%s]", out_handle.c_str());
    return true;
}

bool MnnQnnPromptGateway::consume_handoff(
    const std::string& handle,
    const PromptHandoffRecord& observed_record,
    PromptHandoffRecord& consumed_record,
    std::string& error_msg
) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = active_records_.find(handle);
    if (it == active_records_.end()) {
        error_msg = "Lease error: Handle not found or already consumed";
        MCA_LOGW("MnnQnnPromptGateway", "[%s] %s", handle.c_str(), error_msg.c_str());
        return false;
    }

    // Verify SHA-256 digests match expected record
    if (!observed_record.prompt_pair_sha256.empty() &&
        observed_record.prompt_pair_sha256 != it->second.prompt_pair_sha256) {
        error_msg = "Integrity error: prompt_pair_sha256 mismatch";
        MCA_LOGE("MnnQnnPromptGateway", "[%s] %s", handle.c_str(), error_msg.c_str());
        return false;
    }

    if (!observed_record.payload_sha256.empty() &&
        observed_record.payload_sha256 != it->second.payload_sha256) {
        error_msg = "Integrity error: payload_sha256 mismatch";
        MCA_LOGE("MnnQnnPromptGateway", "[%s] %s", handle.c_str(), error_msg.c_str());
        return false;
    }

    // Atomically transfer and erase (enforces exactly-once lease consumption)
    consumed_record = std::move(it->second);
    active_records_.erase(it);

    MCA_LOGI("MnnQnnPromptGateway", "Consumed prompt handoff lease [%s]", handle.c_str());
    return true;
}

void MnnQnnPromptGateway::purge_expired_records(int64_t max_age_ms) {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t now_ms = common::MonotonicClock::now_ms();

    for (auto it = active_records_.begin(); it != active_records_.end();) {
        if (now_ms - it->second.created_timestamp_ms > max_age_ms) {
            MCA_LOGI("MnnQnnPromptGateway", "Purging expired handoff lease [%s]", it->first.c_str());
            it = active_records_.erase(it);
        } else {
            ++it;
        }
    }
}

void MnnQnnPromptGateway::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    active_records_.clear();
}

size_t MnnQnnPromptGateway::active_record_count() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_records_.size();
}

} // namespace prompt
} // namespace mca
