#include "sd_phase_machine.hpp"

#include <chrono>
#include <sstream>

#include "mca/common/logging.hpp"
#include "sd_input_contract.hpp"

namespace mca {
namespace sd {

const char* sd_phase_to_string(SdPhase phase) noexcept {
    switch (phase) {
        case SdPhase::Validate: return "Validate";
        case SdPhase::TokenizeConditioning: return "TokenizeConditioning";
        case SdPhase::LoadGraph: return "LoadGraph";
        case SdPhase::PrepareLatents: return "PrepareLatents";
        case SdPhase::DenoiseLoop: return "DenoiseLoop";
        case SdPhase::DecodeLatents: return "DecodeLatents";
        case SdPhase::PostProcess: return "PostProcess";
        case SdPhase::CommitOutputs: return "CommitOutputs";
        case SdPhase::Completed: return "Completed";
        case SdPhase::Failed: return "Failed";
        case SdPhase::Cancelled: return "Cancelled";
    }
    return "Unknown";
}

namespace {

mca::contract::ErrorEnvelope make_phase_error(
    mca::contract::StableErrorCode code,
    mca::contract::ErrorCategory category,
    mca::contract::ErrorPhase phase,
    const std::string& message,
    const mca::contract::RequestIdentity& identity
) {
    mca::contract::ErrorEnvelope env;
    env.code = code;
    env.category = category;
    env.phase = phase;
    env.retryability = mca::contract::Retryability::Never;
    env.public_message = message;
    env.user_message_key = "error.sd_execution";
    env.cause_chain.push_back(message);
    env.identity = identity;
    const auto now = std::chrono::system_clock::now();
    env.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();
    return env;
}

} // namespace

SdPhaseMachine::SdPhaseMachine(
    SdRuntimeOwner& runtime_owner,
    std::shared_ptr<ISdEngineBackend> backend
)
    : runtime_owner_(runtime_owner),
      backend_(std::move(backend)) {
}

void SdPhaseMachine::transition_phase(
    SdPhase new_phase,
    SdRequest& request,
    const SdEventCallback& callback
) {
    const auto old_phase = current_phase_;
    current_phase_ = new_phase;
    MCA_LOGI("SdPhaseMachine",
             "Request '%s' phase transition: %s -> %s",
             request.get_identity().request_id.c_str(),
             sd_phase_to_string(old_phase),
             sd_phase_to_string(new_phase));

    if (callback) {
        const auto progress_event = request.create_progress_event(
            static_cast<float>(new_phase),
            static_cast<float>(SdPhase::Completed),
            sd_phase_to_string(new_phase),
            std::string("Entering phase ") + sd_phase_to_string(new_phase)
        );
        callback(progress_event);
    }
}

void SdPhaseMachine::emit_event(
    const mca::contract::CallbackEvent& event,
    const SdEventCallback& callback
) {
    if (callback) {
        callback(event);
    }
}

bool SdPhaseMachine::run_validation_phase(
    SdRequest& request,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::Validate, request, callback);
    SdInputValidator validator;
    auto validation_err = validator.validate(request.get_input_contract(), request.get_identity());
    if (validation_err.has_value()) {
        MCA_LOGE("SdPhaseMachine",
                 "Validation failed for '%s': %s",
                 request.get_identity().request_id.c_str(),
                 validation_err->public_message.c_str());
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, validation_err);
        emit_event(request.create_error_event(*validation_err), callback);
        return false;
    }
    return true;
}

bool SdPhaseMachine::run_tokenize_phase(
    SdRequest& request,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::TokenizeConditioning, request, callback);
    if (request.is_cancel_requested()) {
        return false;
    }

    std::string error;
    if (!backend_->tokenize_and_condition(request.get_input_contract(), error)) {
        auto err = make_phase_error(
            mca::contract::StableErrorCode::INT_UNEXPECTED_EXCEPTION,
            mca::contract::ErrorCategory::Internal,
            mca::contract::ErrorPhase::Prepare,
            "Prompt tokenization failed: " + error,
            request.get_identity()
        );
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), callback);
        return false;
    }
    return true;
}

bool SdPhaseMachine::run_load_graph_phase(
    SdRequest& request,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::LoadGraph, request, callback);
    if (request.is_cancel_requested()) {
        return false;
    }

    const auto& contract = request.get_input_contract();
    std::string load_error;
    if (!runtime_owner_.load_model(contract.model_path, contract.bundle_root, contract, load_error)) {
        auto err = make_phase_error(
            mca::contract::StableErrorCode::MDL_LOAD_FAILED,
            mca::contract::ErrorCategory::Model,
            mca::contract::ErrorPhase::Load,
            "Failed to load diffusion model: " + load_error,
            request.get_identity()
        );
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), callback);
        return false;
    }
    return true;
}

bool SdPhaseMachine::run_prepare_latents_phase(
    SdRequest& request,
    std::vector<float>& latents,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::PrepareLatents, request, callback);
    if (request.is_cancel_requested()) {
        return false;
    }

    std::string error;
    if (!backend_->prepare_latents(request.get_input_contract(), latents, error)) {
        auto err = make_phase_error(
            mca::contract::StableErrorCode::RT_INITIALIZATION_FAILED,
            mca::contract::ErrorCategory::Runtime,
            mca::contract::ErrorPhase::Prepare,
            "Latent preparation failed: " + error,
            request.get_identity()
        );
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), callback);
        return false;
    }

    if (task_uses_init_image(request.get_input_contract().task_mode)) {
        request.get_execution_evidence().actual_vae_encode_tiling_count++;
    }
    if (task_uses_control_image(request.get_input_contract().task_mode)) {
        request.get_execution_evidence().actual_control_net_compute_attempt_count++;
    }

    return true;
}

bool SdPhaseMachine::run_denoise_loop_phase(
    SdRequest& request,
    std::vector<float>& latents,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::DenoiseLoop, request, callback);
    const auto& contract = request.get_input_contract();
    const int32_t total_steps = expected_denoising_step_count(
        contract.steps, contract.strength, contract.task_mode
    );

    MCA_LOGI("SdPhaseMachine",
             "Starting denoising loop for '%s': total_steps=%d (configured=%d, strength=%.2f)",
             request.get_identity().request_id.c_str(),
             total_steps, contract.steps, contract.strength);

    for (int32_t step = 0; step < total_steps; ++step) {
        // Strict Cancellation Check at loop boundary
        if (request.is_cancel_requested()) {
            MCA_LOGW("SdPhaseMachine",
                     "Cancellation observed at denoise step %d/%d for '%s'",
                     step, total_steps, request.get_identity().request_id.c_str());
            current_phase_ = SdPhase::Cancelled;
            auto err = make_phase_error(
                mca::contract::StableErrorCode::CNC_USER_CANCELLED,
                mca::contract::ErrorCategory::Cancel,
                mca::contract::ErrorPhase::Execute,
                "Generation cancelled during denoising loop",
                request.get_identity()
            );
            request.mark_terminal(mca::contract::RequestState::Cancelled, err);
            emit_event(request.create_error_event(err), callback);
            return false;
        }

        std::string step_error;
        if (!backend_->execute_denoise_step(step, total_steps, latents, step_error)) {
            MCA_LOGE("SdPhaseMachine",
                     "Denoise step %d failed for '%s': %s",
                     step, request.get_identity().request_id.c_str(), step_error.c_str());
            current_phase_ = SdPhase::Failed;
            auto err = make_phase_error(
                mca::contract::StableErrorCode::NAT_EXECUTION_FAULT,
                mca::contract::ErrorCategory::Native,
                mca::contract::ErrorPhase::Execute,
                "Denoise step failed: " + step_error,
                request.get_identity()
            );
            request.mark_terminal(mca::contract::RequestState::Failed, err);
            emit_event(request.create_error_event(err), callback);
            return false;
        }

        request.get_execution_evidence().actual_denoising_step_count++;

        // Step Progress Event
        emit_event(request.create_progress_event(
            static_cast<float>(step + 1),
            static_cast<float>(total_steps),
            "DenoiseLoop",
            "Step " + std::to_string(step + 1) + "/" + std::to_string(total_steps)
        ), callback);

        // Preview Event Checkpoint
        if (contract.preview_interval > 0 &&
            ((step + 1) % contract.preview_interval == 0 || step + 1 == total_steps)) {
            GeneratedImageResult preview_frame;
            std::string preview_error;
            if (backend_->generate_preview_frame(step + 1, latents, preview_frame, preview_error)) {
                emit_event(request.create_preview_event(
                    step + 1,
                    preview_frame.width,
                    preview_frame.height,
                    "rgba",
                    preview_frame.sha256_hex
                ), callback);
            }
        }
    }

    return true;
}

bool SdPhaseMachine::run_decode_phase(
    SdRequest& request,
    const std::vector<float>& latents,
    GeneratedImageResult& image_result,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::DecodeLatents, request, callback);
    if (request.is_cancel_requested()) {
        return false;
    }

    std::string decode_error;
    if (!backend_->decode_latents_to_pixels(request.get_input_contract(), latents, image_result, decode_error)) {
        auto err = make_phase_error(
            mca::contract::StableErrorCode::RT_CRASHED,
            mca::contract::ErrorCategory::Runtime,
            mca::contract::ErrorPhase::Execute,
            "VAE latent decoding failed: " + decode_error,
            request.get_identity()
        );
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), callback);
        return false;
    }

    request.get_execution_evidence().actual_vae_decode_tiling_count++;
    return true;
}

bool SdPhaseMachine::run_commit_phase(
    SdRequest& request,
    const GeneratedImageResult& image_result,
    const SdEventCallback& callback
) {
    transition_phase(SdPhase::CommitOutputs, request, callback);
    const auto& contract = request.get_input_contract();

    // Atomic Output Transaction
    SdOutputTransaction transaction(contract.output_path, request.get_identity());
    if (!transaction.begin()) {
        auto err = make_phase_error(
            mca::contract::StableErrorCode::STR_WRITE_ERROR,
            mca::contract::ErrorCategory::Storage,
            mca::contract::ErrorPhase::Persist,
            "Failed to begin output transaction",
            request.get_identity()
        );
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), callback);
        return false;
    }

    if (!transaction.write_pixels_as_png(
            image_result.width,
            image_result.height,
            image_result.channels,
            image_result.pixel_data.data())) {
        transaction.rollback();
        auto err = make_phase_error(
            mca::contract::StableErrorCode::STR_WRITE_ERROR,
            mca::contract::ErrorCategory::Storage,
            mca::contract::ErrorPhase::Persist,
            "Failed to write image data to partial output file",
            request.get_identity()
        );
        current_phase_ = SdPhase::Failed;
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), callback);
        return false;
    }

    auto commit_result = transaction.commit([&request]() {
        return request.is_cancel_requested();
    });

    if (!commit_result.success) {
        current_phase_ = commit_result.error &&
                         commit_result.error->code == mca::contract::StableErrorCode::CNC_USER_CANCELLED
                         ? SdPhase::Cancelled
                         : SdPhase::Failed;
        const auto req_state = (current_phase_ == SdPhase::Cancelled)
                               ? mca::contract::RequestState::Cancelled
                               : mca::contract::RequestState::Failed;
        request.mark_terminal(req_state, commit_result.error);
        if (commit_result.error.has_value()) {
            emit_event(request.create_error_event(*commit_result.error), callback);
        }
        return false;
    }

    return true;
}

bool SdPhaseMachine::execute(
    SdRequest& request,
    SdEventCallback event_callback
) {
    if (!backend_) {
        auto err = make_phase_error(
            mca::contract::StableErrorCode::CAP_BACKEND_UNAVAILABLE,
            mca::contract::ErrorCategory::Capability,
            mca::contract::ErrorPhase::Validate,
            "No backend implementation bound to phase machine",
            request.get_identity()
        );
        request.mark_terminal(mca::contract::RequestState::Failed, err);
        emit_event(request.create_error_event(err), event_callback);
        return false;
    }

    // 1. Accepted
    if (!request.transition_to(mca::contract::RequestState::Accepted)) {
        return false;
    }
    emit_event(request.create_state_event(
        mca::contract::RequestState::Created,
        mca::contract::RequestState::Accepted
    ), event_callback);

    // 2. Validation
    if (!run_validation_phase(request, event_callback)) {
        return false;
    }

    // 3. Preparing
    auto exec_lock = runtime_owner_.acquire_execution_lock();
    if (!request.transition_to(mca::contract::RequestState::Preparing)) {
        return false;
    }
    emit_event(request.create_state_event(
        mca::contract::RequestState::Accepted,
        mca::contract::RequestState::Preparing
    ), event_callback);

    auto handle_cancelled = [&](mca::contract::ErrorPhase phase, const char* msg) {
        current_phase_ = SdPhase::Cancelled;
        auto err = make_phase_error(
            mca::contract::StableErrorCode::CNC_USER_CANCELLED,
            mca::contract::ErrorCategory::Cancel,
            phase,
            msg,
            request.get_identity()
        );
        request.mark_terminal(mca::contract::RequestState::Cancelled, err);
        emit_event(request.create_error_event(err), event_callback);
        return false;
    };

    if (request.is_cancel_requested()) {
        return handle_cancelled(mca::contract::ErrorPhase::Prepare, "Generation cancelled while preparing");
    }

    // 4. Tokenize & Condition
    if (!run_tokenize_phase(request, event_callback)) {
        if (request.is_cancel_requested()) {
            return handle_cancelled(mca::contract::ErrorPhase::Prepare, "Generation cancelled during tokenization");
        }
        return false;
    }
    if (request.is_cancel_requested()) {
        return handle_cancelled(mca::contract::ErrorPhase::Prepare, "Generation cancelled after tokenization");
    }

    // 5. Load Graph
    if (!run_load_graph_phase(request, event_callback)) {
        if (request.is_cancel_requested()) {
            return handle_cancelled(mca::contract::ErrorPhase::Load, "Generation cancelled during graph load");
        }
        return false;
    }
    if (request.is_cancel_requested()) {
        return handle_cancelled(mca::contract::ErrorPhase::Load, "Generation cancelled after graph load");
    }

    // 6. Running
    if (!request.transition_to(mca::contract::RequestState::Running)) {
        if (request.is_cancel_requested()) {
            return handle_cancelled(mca::contract::ErrorPhase::Execute, "Generation cancelled before running");
        }
        return false;
    }
    emit_event(request.create_state_event(
        mca::contract::RequestState::Preparing,
        mca::contract::RequestState::Running
    ), event_callback);

    // 7. Prepare Latents
    if (request.is_cancel_requested()) {
        return handle_cancelled(mca::contract::ErrorPhase::Prepare, "Generation cancelled before preparing latents");
    }
    std::vector<float> latents;
    if (!run_prepare_latents_phase(request, latents, event_callback)) {
        if (request.is_cancel_requested()) {
            return handle_cancelled(mca::contract::ErrorPhase::Prepare, "Generation cancelled during latent preparation");
        }
        return false;
    }
    if (request.is_cancel_requested()) {
        return handle_cancelled(mca::contract::ErrorPhase::Prepare, "Generation cancelled after latent preparation");
    }

    // 8. Denoise Loop
    if (!run_denoise_loop_phase(request, latents, event_callback)) {
        return false;
    }

    // 9. Decode Latents
    GeneratedImageResult image_result;
    if (!run_decode_phase(request, latents, image_result, event_callback)) {
        return false;
    }

    // 10. Commit Outputs
    if (!run_commit_phase(request, image_result, event_callback)) {
        return false;
    }

    // 11. Completed & Exactly-Once Terminal
    transition_phase(SdPhase::Completed, request, event_callback);
    request.get_execution_evidence().peak_memory_bytes = runtime_owner_.get_peak_memory_bytes();
    request.get_execution_evidence().device_admitted_without_allowlist = true;

    request.mark_terminal(mca::contract::RequestState::Succeeded);
    emit_event(request.create_state_event(
        mca::contract::RequestState::Running,
        mca::contract::RequestState::Succeeded
    ), event_callback);
    emit_event(request.create_terminal_event(request.get_elapsed_ms()), event_callback);

    MCA_LOGI("SdPhaseMachine",
             "Request '%s' finished successfully in %lld ms (actual steps: %d)",
             request.get_identity().request_id.c_str(),
             static_cast<long long>(request.get_elapsed_ms()),
             request.get_execution_evidence().actual_denoising_step_count);

    return true;
}

} // namespace sd
} // namespace mca
