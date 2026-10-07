#ifndef MCA_RUNTIME_ENGINE_HPP
#define MCA_RUNTIME_ENGINE_HPP

#include <string>
#include <memory>
#include <atomic>
#include <vector>
#include <mutex>
#include "backend_runtime.hpp"

namespace mca {
namespace runtime {

class EngineSession;

/**
 * Long-lived model and graph resource owner.
 * Owns loaded model weights, graphs, and tokenizers.
 * Does NOT hold UI state or single-request callbacks.
 */
class Engine : public std::enable_shared_from_this<Engine> {
public:
    Engine(std::shared_ptr<BackendRuntime> backend, std::string model_id, std::string model_path);
    virtual ~Engine();

    [[nodiscard]] const std::string& get_model_id() const noexcept { return model_id_; }
    [[nodiscard]] const std::string& get_model_path() const noexcept { return model_path_; }
    [[nodiscard]] std::shared_ptr<BackendRuntime> get_backend() const noexcept { return backend_; }

    [[nodiscard]] bool is_valid() const noexcept;
    virtual void unload() noexcept;

    std::shared_ptr<EngineSession> create_session(int64_t initial_generation = 1);

private:
    std::shared_ptr<BackendRuntime> backend_;
    std::string model_id_;
    std::string model_path_;
    std::atomic<bool> is_loaded_{true};
    mutable std::mutex mutex_;
};

} // namespace runtime
} // namespace mca

#endif // MCA_RUNTIME_ENGINE_HPP
