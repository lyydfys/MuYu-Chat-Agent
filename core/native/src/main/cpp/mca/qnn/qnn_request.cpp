#include "qnn_request.hpp"
#include "qnn_session.hpp"
#include "qnn_engine.hpp"
#include "mca/common/logging.hpp"
#include "mca/contract/callback_event.hpp"

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "QnnRequest";
}

QnnRequest::QnnRequest(
    contract::RequestIdentity identity,
    std::shared_ptr<QnnSession> session,
    SdxlExecutionContract contract
) : mca::request::NativeRequestContext(std::move(identity)),
    session_(session),
    contract_(std::move(contract)) {
    MCA_LOGI(kTag, "[%s] QnnRequest created for prompt='%s' (%ux%u, steps=%u)",
             get_identity().request_id.c_str(),
             contract_.prompt.c_str(),
             contract_.width,
             contract_.height,
             contract_.steps);
}

QnnRequest::~QnnRequest() {
    MCA_LOGD(kTag, "[%s] QnnRequest destroyed in phase %s",
             get_identity().request_id.c_str(),
             sdxl_phase_to_string(get_current_phase()));
}

SdxlPhase QnnRequest::get_current_phase() const noexcept {
    return current_phase_.load(std::memory_order_relaxed);
}

bool QnnRequest::transition_phase(SdxlPhase next_phase) noexcept {
    SdxlPhase current = current_phase_.load(std::memory_order_relaxed);
    while (true) {
        const bool current_terminal = current == SdxlPhase::Cancelled ||
            current == SdxlPhase::Completed || current == SdxlPhase::Failed;
        const bool normal_forward =
            next_phase > current && next_phase <= SdxlPhase::OutputTransaction;
        const bool terminal_transition =
            (next_phase == SdxlPhase::Failed && !current_terminal) ||
            (next_phase == SdxlPhase::Cancelling && !current_terminal) ||
            (next_phase == SdxlPhase::Cancelled && current == SdxlPhase::Cancelling) ||
            (next_phase == SdxlPhase::Completed && current == SdxlPhase::OutputTransaction);
        if (!normal_forward && !terminal_transition) return false;
        if (current_phase_.compare_exchange_weak(
                current, next_phase, std::memory_order_relaxed)) {
            MCA_LOGD(kTag, "[%s] Phase transition: %s -> %s",
                     get_identity().request_id.c_str(),
                     sdxl_phase_to_string(current),
                     sdxl_phase_to_string(next_phase));
            return true;
        }
    }
}

const SdxlExecutionContract& QnnRequest::get_contract() const noexcept {
    return contract_;
}

std::vector<SdxlPhaseArtifact> QnnRequest::get_artifacts() const {
    std::lock_guard<std::mutex> lock(request_mutex_);
    return artifacts_;
}

void QnnRequest::add_artifact(SdxlPhaseArtifact artifact) {
    std::lock_guard<std::mutex> lock(request_mutex_);
    artifacts_.push_back(std::move(artifact));
}

std::shared_ptr<QnnSession> QnnRequest::get_session() const noexcept {
    return session_.lock();
}

bool QnnRequest::check_cancellation_point() noexcept {
    if (is_cancel_requested()) {
        transition_phase(SdxlPhase::Cancelling);
        mark_terminal(contract::RequestState::Cancelled);
        transition_phase(SdxlPhase::Cancelled);
        MCA_LOGI(kTag, "[%s] Execution gracefully stopped at cancellation point",
                 get_identity().request_id.c_str());
        return true;
    }
    return false;
}

void QnnRequest::emit_progress(
    callback::CallbackRouter* router,
    float current,
    float total,
    const std::string& stage_name,
    const std::string& detail
) {
    if (!router) return;

    contract::CallbackEvent event;
    event.identity = get_identity();
    event.event_type = contract::CallbackEventType::Progress;
    event.sequence = get_last_sequence() + 1;
    update_sequence(event.sequence);

    contract::ProgressPayload progress;
    progress.current = current;
    progress.total = total;
    progress.stage = stage_name;
    progress.detail = detail;

    event.payload = progress;
    router->dispatch(
        event,
        get_identity().runtime_epoch,
        get_identity().session_generation,
        get_identity().worker_incarnation
    );
}

contract::RequestState QnnRequest::execute(
    std::shared_ptr<callback::CallbackRouter> router
) {
    MCA_LOGI(kTag, "[%s] Starting SDXL request", get_identity().request_id.c_str());

    // 1. Validate contract
    std::string validation_error;
    if (!contract_.validate(&validation_error)) {
        MCA_LOGE(kTag, "[%s] Contract validation failed: %s",
                 get_identity().request_id.c_str(), validation_error.c_str());
        transition_phase(SdxlPhase::Failed);
        mark_terminal(contract::RequestState::Failed);
        return contract::RequestState::Failed;
    }

    // 2. Validate session and backend
    auto session = get_session();
    if (!session || !session->is_valid()) {
        MCA_LOGE(kTag, "[%s] Session is null or invalid", get_identity().request_id.c_str());
        transition_phase(SdxlPhase::Failed);
        mark_terminal(contract::RequestState::Failed);
        return contract::RequestState::Failed;
    }

    // 3. Early check before acquiring hardware lease
    if (check_cancellation_point()) {
        return contract::RequestState::Cancelled;
    }

    // This lifecycle scaffold has no graph executor. Never synthesize a successful artifact.
    MCA_LOGE(kTag, "[%s] QNN SDXL graph execution is not connected",
             get_identity().request_id.c_str());
    transition_phase(SdxlPhase::Failed);
    mark_terminal(contract::RequestState::Failed);

    if (router) {
        contract::CallbackEvent term_event;
        term_event.identity = get_identity();
        term_event.event_type = contract::CallbackEventType::Terminal;
        term_event.is_terminal = true;
        term_event.sequence = get_last_sequence() + 1;
        update_sequence(term_event.sequence);

        contract::TerminalPayload payload;
        payload.final_state = contract::RequestState::Failed;
        term_event.payload = payload;

        router->dispatch(
            term_event,
            get_identity().runtime_epoch,
            get_identity().session_generation,
            get_identity().worker_incarnation
        );
    }

    return contract::RequestState::Failed;
}

} // namespace qnn
} // namespace mca
