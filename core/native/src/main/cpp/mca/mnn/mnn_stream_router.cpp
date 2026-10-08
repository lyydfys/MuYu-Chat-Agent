#include "mnn_stream_router.hpp"
#include "mca/common/monotonic_clock.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace mnn {

MnnStreamRouter::MnnStreamRouter(
    std::shared_ptr<mca::callback::CallbackRouter> router,
    std::shared_ptr<MnnRequest> request
) : router_(std::move(router)),
    request_(std::move(request)) {
}

MnnStreamRouter::~MnnStreamRouter() = default;

bool MnnStreamRouter::emit_token(int64_t token_index, const std::string& text, bool is_special) {
    if (terminal_emitted_.load(std::memory_order_relaxed) || !router_ || !request_) {
        return false;
    }

    const int64_t seq = request_->next_sequence_number();
    contract::CallbackEvent event;
    event.identity = request_->get_identity();
    event.identity.native_sequence = seq;
    event.event_type = contract::CallbackEventType::Token;
    event.sequence = seq;
    event.monotonic_time_ms = common::MonotonicClock::now_ms();
    event.is_terminal = false;
    event.source = contract::CallbackSource::Native;

    contract::TokenPayload token_payload;
    token_payload.text = text;
    token_payload.index = token_index;
    token_payload.is_special = is_special;
    event.payload = token_payload;

    auto result = router_->dispatch(
        event,
        event.identity.runtime_epoch,
        event.identity.session_generation,
        event.identity.worker_incarnation
    );

    if (result == callback::DispatchResult::Delivered) {
        emitted_token_count_.fetch_add(1, std::memory_order_relaxed);
        return true;
    }

    if (result == callback::DispatchResult::SinkFailed) {
        MCA_LOGW("MnnStreamRouter", "[%s] Callback sink failed at seq %lld",
                 event.identity.request_id.c_str(), static_cast<long long>(seq));
    }
    return false;
}

bool MnnStreamRouter::emit_progress(float current, float total, const std::string& stage, const std::string& detail) {
    if (terminal_emitted_.load(std::memory_order_relaxed) || !router_ || !request_) {
        return false;
    }

    const int64_t seq = request_->next_sequence_number();
    contract::CallbackEvent event;
    event.identity = request_->get_identity();
    event.identity.native_sequence = seq;
    event.event_type = contract::CallbackEventType::Progress;
    event.sequence = seq;
    event.monotonic_time_ms = common::MonotonicClock::now_ms();
    event.is_terminal = false;
    event.source = contract::CallbackSource::Native;

    contract::ProgressPayload prog;
    prog.current = current;
    prog.total = total;
    prog.stage = stage;
    prog.detail = detail;
    event.payload = prog;

    auto result = router_->dispatch(
        event,
        event.identity.runtime_epoch,
        event.identity.session_generation,
        event.identity.worker_incarnation
    );
    return result == callback::DispatchResult::Delivered;
}

bool MnnStreamRouter::emit_error(const contract::ErrorEnvelope& error) {
    if (terminal_emitted_.load(std::memory_order_relaxed) || !router_ || !request_) {
        return false;
    }

    const int64_t seq = request_->next_sequence_number();
    contract::CallbackEvent event;
    event.identity = request_->get_identity();
    event.identity.native_sequence = seq;
    event.event_type = contract::CallbackEventType::Error;
    event.sequence = seq;
    event.monotonic_time_ms = common::MonotonicClock::now_ms();
    event.is_terminal = false;
    event.source = contract::CallbackSource::Native;

    contract::ErrorPayload err_payload;
    err_payload.error = error;
    event.payload = err_payload;

    auto result = router_->dispatch(
        event,
        event.identity.runtime_epoch,
        event.identity.session_generation,
        event.identity.worker_incarnation
    );
    return result == callback::DispatchResult::Delivered;
}

bool MnnStreamRouter::emit_terminal(contract::RequestState final_state, int64_t total_tokens, int64_t elapsed_ms) {
    std::lock_guard<std::mutex> lock(router_mutex_);
    if (terminal_emitted_.exchange(true, std::memory_order_relaxed)) {
        MCA_LOGW("MnnStreamRouter", "[%s] Duplicate terminal emission ignored",
                 request_ ? request_->get_identity().request_id.c_str() : "unknown");
        return false; // Terminal exactly-once invariant
    }

    if (!router_ || !request_) return false;

    // Transition request context to terminal state
    request_->mark_terminal(final_state);

    const int64_t seq = request_->next_sequence_number();
    contract::CallbackEvent event;
    event.identity = request_->get_identity();
    event.identity.native_sequence = seq;
    event.event_type = contract::CallbackEventType::Terminal;
    event.sequence = seq;
    event.monotonic_time_ms = common::MonotonicClock::now_ms();
    event.is_terminal = true;
    event.source = contract::CallbackSource::Native;

    contract::TerminalPayload term;
    term.final_state = final_state;
    term.total_tokens = total_tokens;
    term.elapsed_ms = elapsed_ms;
    event.payload = term;

    auto result = router_->dispatch(
        event,
        event.identity.runtime_epoch,
        event.identity.session_generation,
        event.identity.worker_incarnation
    );
    return result == callback::DispatchResult::Delivered;
}

bool MnnStreamRouter::drain(int64_t timeout_ms) {
    if (!router_) return true;
    return router_->drain(timeout_ms);
}

} // namespace mnn
} // namespace mca
