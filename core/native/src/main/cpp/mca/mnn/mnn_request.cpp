#include "mnn_request.hpp"
#include "mnn_session.hpp"
#include "mca/common/logging.hpp"
#include "mca/common/safe_path.hpp"

namespace mca {
namespace mnn {

MnnRequest::MnnRequest(
    contract::RequestIdentity identity,
    MnnChatRequest chat_request,
    std::shared_ptr<MnnSession> session
) : NativeRequestContext(std::move(identity)),
    chat_request_(std::move(chat_request)),
    session_(std::move(session)) {
    MCA_LOGI("MnnRequest", "[%s] MnnRequest created for session %lld",
             get_identity().request_id.c_str(),
             session_ ? static_cast<long long>(session_->get_session_id()) : -1LL);
}

MnnRequest::~MnnRequest() {
    MCA_LOGD("MnnRequest", "[%s] MnnRequest destroyed", get_identity().request_id.c_str());
}

bool MnnRequest::transition_state(contract::RequestState target_state) {
    if (contract::is_terminal_state(target_state)) {
        return mark_terminal(target_state);
    }

    const contract::RequestState current_state = get_state();
    if (!transition_to(target_state)) {
        MCA_LOGW("MnnRequest", "[%s] Invalid state transition: %s -> %s rejected",
                 get_identity().request_id.c_str(),
                 contract::request_state_to_string(current_state),
                 contract::request_state_to_string(target_state));
        return false;
    }

    MCA_LOGI("MnnRequest", "[%s] State transitioned: %s -> %s",
             get_identity().request_id.c_str(),
             contract::request_state_to_string(current_state),
             contract::request_state_to_string(target_state));
    return true;
}

void MnnRequest::set_error(contract::ErrorEnvelope error) {
    std::lock_guard<std::mutex> lock(request_mutex_);
    error_ = std::move(error);
}

std::optional<contract::ErrorEnvelope> MnnRequest::get_error() const {
    std::lock_guard<std::mutex> lock(request_mutex_);
    return error_;
}

bool MnnRequest::should_stop() const noexcept {
    return is_cancel_requested() || is_terminal();
}

bool MnnRequest::prepare_and_validate(std::string* error_msg) {
    std::lock_guard<std::mutex> lock(request_mutex_);

    if (!chat_request_.sampling.validate(error_msg)) {
        MCA_LOGE("MnnRequest", "[%s] Sampling config validation failed: %s",
                 get_identity().request_id.c_str(),
                 error_msg ? error_msg->c_str() : "unknown");
        return false;
    }

    if (chat_request_.is_multimodal()) {
        // MNN language chunker cannot preserve atomic visual embeddings across chunks
        chat_request_.chunk_size = 0;

        for (const auto& img_path : chat_request_.image_paths) {
            if (common::SafePath::has_traversal(img_path)) {
                if (error_msg) *error_msg = "Security: Path traversal in image attachment";
                MCA_LOGE("MnnRequest", "[%s] Insecure image path: %s",
                         get_identity().request_id.c_str(), img_path.c_str());
                return false;
            }
        }
        for (const auto& msg : chat_request_.messages) {
            for (const auto& img_path : msg.image_paths) {
                if (common::SafePath::has_traversal(img_path)) {
                    if (error_msg) *error_msg = "Security: Path traversal in message image attachment";
                    MCA_LOGE("MnnRequest", "[%s] Insecure image path in turn: %s",
                             get_identity().request_id.c_str(), img_path.c_str());
                    return false;
                }
            }
        }
    }

    return true;
}

int64_t MnnRequest::next_sequence_number() noexcept {
    const int64_t seq = sequence_counter_.fetch_add(1, std::memory_order_relaxed) + 1;
    update_sequence(seq);
    return seq;
}

} // namespace mnn
} // namespace mca
