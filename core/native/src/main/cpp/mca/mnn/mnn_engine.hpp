#ifndef MCA_MNN_ENGINE_HPP
#define MCA_MNN_ENGINE_HPP

#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include "mca/runtime/engine.hpp"
#include "mnn_contracts.hpp"
#include "mnn_backend_owner.hpp"

namespace mca {
namespace mnn {

class MnnSession;

/**
 * Long-lived model resource owner for MNN inference.
 *
 * Inherits from mca::runtime::Engine.
 * Owns loaded model weights, tokenizer metadata, and chat formatting templates.
 * Does NOT hold UI state or single-request callbacks.
 */
class MnnEngine : public mca::runtime::Engine {
public:
    MnnEngine(
        std::shared_ptr<MnnBackendOwner> backend,
        std::string model_id,
        std::string model_path,
        MnnModelMetadata metadata
    );
    ~MnnEngine() override;

    [[nodiscard]] const MnnModelMetadata& get_metadata() const noexcept { return metadata_; }
    [[nodiscard]] std::shared_ptr<MnnBackendOwner> get_mnn_backend() const noexcept;

    /**
     * Verifies model package integrity and initializes graph/tokenizer resources.
     */
    bool load_model(std::string* error_msg = nullptr);

    /**
     * Releases loaded model weights, graphs, and tokenizers.
     */
    void unload() noexcept override;

    [[nodiscard]] bool is_valid() const noexcept;

    /**
     * Creates an active conversation session.
     */
    std::shared_ptr<MnnSession> create_mnn_session(int64_t initial_generation = 1);

    /**
     * Tokenization helpers using model vocabulary.
     */
    [[nodiscard]] std::vector<int32_t> tokenize(const std::string& text) const;
    [[nodiscard]] std::string detokenize(const std::vector<int32_t>& tokens) const;

    /**
     * Formats chat turns into model-compatible prompt text.
     */
    [[nodiscard]] std::string format_chat_prompt(const std::vector<MnnChatMessage>& messages) const;

private:
    MnnModelMetadata metadata_;
    mutable std::mutex engine_mutex_;
    std::atomic<bool> is_model_loaded_{false};
    void* mnn_model_handle_{nullptr};
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_ENGINE_HPP
