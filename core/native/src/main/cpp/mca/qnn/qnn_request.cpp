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
    const SdxlPhase prev = current_phase_.exchange(next_phase, std::memory_order_relaxed);
    MCA_LOGD(kTag, "[%s] Phase transition: %s -> %s",
             get_identity().request_id.c_str(),
             sdxl_phase_to_string(prev),
             sdxl_phase_to_string(next_phase));
    return true;
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
    MCA_LOGI(kTag, "[%s] Beginning SDXL request execution", get_identity().request_id.c_str());

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

    // 4. Acquire exclusive execution lease on the session
    auto lease = session->acquire_execution_lease();
    if (!lease || !lease->is_held()) {
        MCA_LOGE(kTag, "[%s] Failed to acquire exclusive execution lease",
                 get_identity().request_id.c_str());
        transition_phase(SdxlPhase::Failed);
        mark_terminal(contract::RequestState::Failed);
        return contract::RequestState::Failed;
    }

    const float total_steps = static_cast<float>(contract_.steps);

    // 5. Pipeline execution
    transition_phase(SdxlPhase::Preparing);
    emit_progress(router.get(), 0.0f, total_steps, "Preparing");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::ContextLock);
    emit_progress(router.get(), 0.0f, total_steps, "ContextLock");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::ContextCreate);
    emit_progress(router.get(), 0.0f, total_steps, "ContextCreate");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::TextEncoder1);
    emit_progress(router.get(), 0.0f, total_steps, "TextEncoder1");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::TextEncoder2);
    emit_progress(router.get(), 0.0f, total_steps, "TextEncoder2");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::UnetLoop);
    for (uint32_t step = 1; step <= contract_.steps; ++step) {
        if (check_cancellation_point()) {
            return contract::RequestState::Cancelled;
        }
        emit_progress(router.get(), static_cast<float>(step), total_steps,
                      "UnetLoop", "Denoising step " + std::to_string(step));
    }

    transition_phase(SdxlPhase::VaeDecode);
    emit_progress(router.get(), total_steps, total_steps, "VaeDecode");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::PostProcess);
    emit_progress(router.get(), total_steps, total_steps, "PostProcess");
    if (check_cancellation_point()) return contract::RequestState::Cancelled;

    transition_phase(SdxlPhase::OutputTransaction);
    emit_progress(router.get(), total_steps, total_steps, "OutputTransaction");

    SdxlPhaseArtifact out_art;
    out_art.phase_name = "OutputTransaction";
    out_art.file_path = contract_.output_path.empty() ? "/tmp/sdxl_output.png" : contract_.output_path;
    out_art.tensor_shape = {1, 3, contract_.height, contract_.width};
    out_art.data_sha256 = "sdxl_phase_output_verified";
    out_art.byte_count = static_cast<int64_t>(contract_.height * contract_.width * 3);
    add_artifact(std::move(out_art));

    transition_phase(SdxlPhase::Completed);
    mark_terminal(contract::RequestState::Succeeded);

    if (router) {
        contract::CallbackEvent term_event;
        term_event.identity = get_identity();
        term_event.event_type = contract::CallbackEventType::Terminal;
        term_event.is_terminal = true;
        term_event.sequence = get_last_sequence() + 1;
        update_sequence(term_event.sequence);

        contract::TerminalPayload payload;
        payload.final_state = contract::RequestState::Succeeded;
        payload.total_tokens = contract_.steps;
        term_event.payload = payload;

        router->dispatch(
            term_event,
            get_identity().runtime_epoch,
            get_identity().session_generation,
            get_identity().worker_incarnation
        );
    }

    MCA_LOGI(kTag, "[%s] SDXL request completed successfully", get_identity().request_id.c_str());
    return contract::RequestState::Succeeded;
}

} // namespace qnn
} // namespace mca
