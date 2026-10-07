#include "qnn_session.hpp"
#include "qnn_engine.hpp"
#include "qnn_request.hpp"
#include "mca/runtime/process_runtime_host.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "QnnSession";
}

QnnSession::QnnSession(
    std::shared_ptr<QnnEngine> engine,
    int64_t session_id,
    int64_t initial_generation
) : mca::runtime::EngineSession(engine, session_id, initial_generation),
    qnn_engine_(std::move(engine)) {
    MCA_LOGI(kTag, "QnnSession %lld created with generation %lld",
             static_cast<long long>(session_id),
             static_cast<long long>(initial_generation));
}

QnnSession::~QnnSession() {
    close();
}

std::unique_ptr<ExecutionLease> QnnSession::acquire_execution_lease(int64_t timeout_ms) {
    std::unique_lock<std::timed_mutex> lock(
        execution_mutex_,
        std::chrono::milliseconds(timeout_ms)
    );
    if (!lock.owns_lock()) {
        MCA_LOGW(kTag, "Timed out acquiring QNN execution lease on session %lld after %lld ms",
                 static_cast<long long>(get_session_id()),
                 static_cast<long long>(timeout_ms));
        return nullptr;
    }
    return std::make_unique<ExecutionLease>(std::move(lock));
}

std::shared_ptr<QnnRequest> QnnSession::create_qnn_request(
    const std::string& request_id,
    const SdxlExecutionContract& contract,
    const std::string& trace_id
) {
    if (!is_valid()) {
        MCA_LOGE(kTag, "Cannot create QnnRequest on closed/invalid QnnSession %lld",
                 static_cast<long long>(get_session_id()));
        return nullptr;
    }

    contract::RequestIdentity identity;
    identity.request_id = request_id;
    identity.attempt_id = 0;
    identity.session_generation = get_generation();
    identity.runtime_epoch = mca::runtime::ProcessRuntimeHost::instance().get_runtime_epoch();
    identity.worker_incarnation = 0;
    identity.native_sequence = 0;
    identity.trace_id = trace_id;

    auto self = std::static_pointer_cast<QnnSession>(shared_from_this());
    return std::make_shared<QnnRequest>(std::move(identity), self, contract);
}

void QnnSession::clear_cache() noexcept {
    std::lock_guard<std::mutex> lock(cache_mutex_);
    cached_latent_.clear();
    MCA_LOGD(kTag, "Cleared session cache for session %lld",
             static_cast<long long>(get_session_id()));
}

void QnnSession::close() noexcept {
    clear_cache();
    EngineSession::close();
}

bool QnnSession::is_valid() const noexcept {
    return EngineSession::is_valid() && (qnn_engine_ != nullptr && qnn_engine_->is_valid());
}

std::shared_ptr<QnnEngine> QnnSession::get_qnn_engine() const noexcept {
    return qnn_engine_;
}

void QnnSession::set_cached_latent(std::vector<float> latent) {
    std::lock_guard<std::mutex> lock(cache_mutex_);
    cached_latent_ = std::move(latent);
}

std::vector<float> QnnSession::get_cached_latent() const {
    std::lock_guard<std::mutex> lock(cache_mutex_);
    return cached_latent_;
}

} // namespace qnn
} // namespace mca
