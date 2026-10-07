#ifndef MCA_PROMPT_MNN_QNN_PROMPT_GATEWAY_HPP
#define MCA_PROMPT_MNN_QNN_PROMPT_GATEWAY_HPP

#include <string>
#include <cstdint>
#include <map>
#include <mutex>
#include <vector>

namespace mca {
namespace prompt {

/**
 * Metadata record for prompt handoff exchange between MNN and QNN engines.
 */
struct PromptHandoffRecord {
    std::string tokenizer_path;
    uint64_t tokenizer_device{0};
    uint64_t tokenizer_inode{0};
    uint64_t tokenizer_size_bytes{0};
    std::string tokenizer_sha256;
    std::string prompt_pair_sha256;
    std::string payload_sha256;
    std::string prompt_closure_sha256;
    int64_t created_timestamp_ms{0};
};

/**
 * Narrow versioned gateway interface for cross-engine prompt handoff.
 *
 * Implements atomic, single-use lease semantics for tokenizer and prompt artifacts.
 */
class MnnQnnPromptGateway {
public:
    static MnnQnnPromptGateway& instance() noexcept;

    MnnQnnPromptGateway(const MnnQnnPromptGateway&) = delete;
    MnnQnnPromptGateway& operator=(const MnnQnnPromptGateway&) = delete;

    /**
     * Issues a new prompt handoff lease and generates a unique token handle.
     */
    bool issue_handoff(
        const PromptHandoffRecord& record,
        std::string& out_handle,
        std::string& error_msg
    );

    /**
     * Consumes an existing prompt handoff lease atomically.
     * Enforces exactly-once consumption and checksum validation.
     */
    bool consume_handoff(
        const std::string& handle,
        const PromptHandoffRecord& observed_record,
        PromptHandoffRecord& consumed_record,
        std::string& error_msg
    );

    /**
     * Purges expired records older than max_age_ms.
     */
    void purge_expired_records(int64_t max_age_ms = 60000);

    /**
     * Clears all active handoff records (testing / shutdown).
     */
    void clear();

    [[nodiscard]] size_t active_record_count() const noexcept;

private:
    MnnQnnPromptGateway() = default;
    ~MnnQnnPromptGateway() = default;

    mutable std::mutex mutex_;
    std::map<std::string, PromptHandoffRecord> active_records_;
    uint64_t handle_counter_{0};
};

} // namespace prompt
} // namespace mca

#endif // MCA_PROMPT_MNN_QNN_PROMPT_GATEWAY_HPP
