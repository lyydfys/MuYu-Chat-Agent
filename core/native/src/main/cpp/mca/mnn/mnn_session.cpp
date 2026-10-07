#include "mnn_session.hpp"
#include "mnn_engine.hpp"
#include "mnn_request.hpp"
#include "mca/runtime/process_runtime_host.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace mnn {

MnnSession::MnnSession(
    std::shared_ptr<MnnEngine> engine,
    int64_t session_id,
    int64_t initial_generation
) : EngineSession(std::move(engine), session_id, initial_generation) {
    MCA_LOGI("MnnSession", "MnnSession %lld created (gen=%lld)",
             static_cast<long long>(session_id),
             static_cast<long long>(initial_generation));
}

MnnSession::~MnnSession() {
    close();
}

std::shared_ptr<MnnEngine> MnnSession::get_mnn_engine() const noexcept {
    return std::dynamic_pointer_cast<MnnEngine>(get_engine());
}

std::shared_ptr<MnnRequest> MnnSession::create_mnn_request(
    const std::string& request_id,
    const MnnChatRequest& chat_request,
    const std::string& trace_id
) {
    std::lock_guard<std::mutex> lock(session_mutex_);
    if (!is_valid()) {
        MCA_LOGE("MnnSession", "Cannot create request on closed/invalid MnnSession %lld",
                 static_cast<long long>(get_session_id()));
        return nullptr;
    }

    contract::RequestIdentity identity;
    identity.request_id = request_id;
    identity.attempt_id = 0;
    identity.session_generation = get_generation();
    identity.runtime_epoch = runtime::ProcessRuntimeHost::instance().get_runtime_epoch();
    identity.worker_incarnation = 0;
    identity.native_sequence = 0;
    identity.trace_id = trace_id;

    auto self = std::static_pointer_cast<MnnSession>(shared_from_this());
    return std::make_shared<MnnRequest>(std::move(identity), chat_request, self);
}

void MnnSession::clear_kv_cache() noexcept {
    std::lock_guard<std::mutex> lock(session_mutex_);
    kv_prefix_cache_.clear();
    MCA_LOGD("MnnSession", "[%lld] KV cache cleared", static_cast<long long>(get_session_id()));
}

size_t MnnSession::get_cached_token_count() const noexcept {
    std::lock_guard<std::mutex> lock(session_mutex_);
    return kv_prefix_cache_.size();
}

void MnnSession::append_cached_tokens(const std::vector<int32_t>& tokens) {
    std::lock_guard<std::mutex> lock(session_mutex_);
    kv_prefix_cache_.insert(kv_prefix_cache_.end(), tokens.begin(), tokens.end());
}

bool MnnSession::fits_in_context_window(size_t prompt_tokens, size_t max_new_tokens) const noexcept {
    auto engine = get_mnn_engine();
    if (!engine) return false;
    const int32_t ctx_window = engine->get_metadata().context_window;
    std::lock_guard<std::mutex> lock(session_mutex_);
    const size_t total = kv_prefix_cache_.size() + prompt_tokens + max_new_tokens;
    return total <= static_cast<size_t>(ctx_window);
}

int64_t MnnSession::advance_generation() noexcept {
    std::lock_guard<std::mutex> lock(session_mutex_);
    kv_prefix_cache_.clear(); // Generation isolation invariant
    const int64_t next_gen = EngineSession::advance_generation();
    MCA_LOGI("MnnSession", "[%lld] Advanced generation to %lld; KV cache isolated",
             static_cast<long long>(get_session_id()),
             static_cast<long long>(next_gen));
    return next_gen;
}

void MnnSession::close() noexcept {
    std::lock_guard<std::mutex> lock(session_mutex_);
    kv_prefix_cache_.clear();
    is_executing_.store(false, std::memory_order_relaxed);
    EngineSession::close();
}

bool MnnSession::is_valid() const noexcept {
    return EngineSession::is_valid();
}

bool MnnSession::try_acquire_execution_lease() noexcept {
    return !is_executing_.exchange(true, std::memory_order_acq_rel);
}

void MnnSession::release_execution_lease() noexcept {
    is_executing_.store(false, std::memory_order_release);
}

} // namespace mnn
} // namespace mca
