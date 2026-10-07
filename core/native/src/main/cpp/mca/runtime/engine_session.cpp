#include "engine_session.hpp"
#include "engine.hpp"
#include "mca/request/native_request_context.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace runtime {

EngineSession::EngineSession(std::shared_ptr<Engine> engine, int64_t session_id, int64_t initial_generation)
    : engine_(std::move(engine)),
      session_id_(session_id),
      generation_(initial_generation) {
    MCA_LOGI("EngineSession", "EngineSession %lld initialized with generation %lld",
             static_cast<long long>(session_id_),
             static_cast<long long>(generation_.load(std::memory_order_relaxed)));
}

EngineSession::~EngineSession() {
    close();
}

int64_t EngineSession::advance_generation() noexcept {
    const int64_t next_gen = generation_.fetch_add(1, std::memory_order_relaxed) + 1;
    MCA_LOGI("EngineSession", "EngineSession %lld advanced to generation %lld",
             static_cast<long long>(session_id_),
             static_cast<long long>(next_gen));
    return next_gen;
}

bool EngineSession::is_valid() const noexcept {
    return is_active_.load(std::memory_order_relaxed) &&
           (engine_ != nullptr && engine_->is_valid());
}

void EngineSession::close() noexcept {
    if (is_active_.exchange(false, std::memory_order_relaxed)) {
        MCA_LOGI("EngineSession", "EngineSession %lld closed", static_cast<long long>(session_id_));
    }
}

std::shared_ptr<request::NativeRequestContext> EngineSession::create_request(
    const std::string& request_id,
    const std::string& trace_id
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_valid()) {
        MCA_LOGE("EngineSession", "Cannot create request on closed/invalid EngineSession %lld",
                 static_cast<long long>(session_id_));
        return nullptr;
    }

    contract::RequestIdentity identity;
    identity.request_id = request_id;
    identity.attempt_id = 0;
    identity.session_generation = generation_.load(std::memory_order_relaxed);
    identity.runtime_epoch = ProcessRuntimeHost::instance().get_runtime_epoch();
    identity.worker_incarnation = 0;
    identity.native_sequence = 0;
    identity.trace_id = trace_id;

    return std::make_shared<request::NativeRequestContext>(std::move(identity));
}

} // namespace runtime
} // namespace mca
