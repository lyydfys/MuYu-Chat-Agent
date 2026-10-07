#ifndef MCA_MNN_REQUEST_HPP
#define MCA_MNN_REQUEST_HPP

#include <string>
#include <memory>
#include <mutex>
#include <optional>
#include "mca/request/native_request_context.hpp"
#include "mnn_contracts.hpp"

namespace mca {
namespace mnn {

class MnnSession;

/**
 * Execution context for a single chat or multimodal generation request.
 *
 * Inherits from mca::request::NativeRequestContext.
 * Manages request inputs, progress statistics, cancellation points, and error envelopes.
 * Guarantees exactly-once terminal state transition.
 */
class MnnRequest : public mca::request::NativeRequestContext {
public:
    MnnRequest(
        contract::RequestIdentity identity,
        MnnChatRequest chat_request,
        std::shared_ptr<MnnSession> session
    );
    ~MnnRequest();

    [[nodiscard]] const MnnChatRequest& get_chat_request() const noexcept { return chat_request_; }
    [[nodiscard]] const MnnGenerationStats& get_stats() const noexcept { return stats_; }
    [[nodiscard]] MnnGenerationStats& get_stats() noexcept { return stats_; }
    [[nodiscard]] std::shared_ptr<MnnSession> get_session() const noexcept { return session_; }

    /**
     * Attempts to transition request state following contract transition rules.
     */
    bool transition_state(contract::RequestState target_state);

    /**
     * Attaches an error envelope when failure occurs.
     */
    void set_error(contract::ErrorEnvelope error);
    [[nodiscard]] std::optional<contract::ErrorEnvelope> get_error() const;

    /**
     * Checks if generation loop should stop (either cancelled or already terminal).
     */
    [[nodiscard]] bool should_stop() const noexcept;

    /**
     * Prepares and validates input assets (e.g. image attachments for multimodal).
     */
    bool prepare_and_validate(std::string* error_msg = nullptr);

    /**
     * Increments and returns the next monotonic sequence number for events.
     */
    int64_t next_sequence_number() noexcept;

private:
    MnnChatRequest chat_request_;
    std::shared_ptr<MnnSession> session_;
    MnnGenerationStats stats_;
    mutable std::mutex request_mutex_;
    std::optional<contract::ErrorEnvelope> error_;
    std::atomic<int64_t> sequence_counter_{0};
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_REQUEST_HPP
