#include "engine.hpp"
#include "engine_session.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace runtime {

Engine::Engine(std::shared_ptr<BackendRuntime> backend, std::string model_id, std::string model_path)
    : backend_(std::move(backend)),
      model_id_(std::move(model_id)),
      model_path_(std::move(model_path)) {
    MCA_LOGI("Engine", "Engine created for model %s at %s", model_id_.c_str(), model_path_.c_str());
}

Engine::~Engine() {
    unload();
}

bool Engine::is_valid() const noexcept {
    return is_loaded_.load(std::memory_order_relaxed) &&
           (backend_ != nullptr && backend_->is_valid());
}

void Engine::unload() noexcept {
    if (is_loaded_.exchange(false, std::memory_order_relaxed)) {
        MCA_LOGI("Engine", "Engine unloaded for model %s", model_id_.c_str());
    }
}

std::shared_ptr<EngineSession> Engine::create_session(int64_t initial_generation) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_valid()) {
        MCA_LOGE("Engine", "Cannot create session on invalid/unloaded Engine %s", model_id_.c_str());
        return nullptr;
    }
    static std::atomic<int64_t> s_session_counter{1};
    const int64_t session_id = s_session_counter.fetch_add(1, std::memory_order_relaxed);
    return std::make_shared<EngineSession>(shared_from_this(), session_id, initial_generation);
}

} // namespace runtime
} // namespace mca
