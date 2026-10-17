#include "mnn_engine.hpp"
#include "mnn_session.hpp"
#include "mca/common/logging.hpp"
#include "mca/common/safe_path.hpp"
#include <sstream>

namespace mca {
namespace mnn {

/**
 * Marker written into MnnEngine::mnn_model_handle_ while the load is a lifecycle-only transition.
 *
 * \warning This header/source pair is a **skeleton**, not the production MNN engine.  The shipping
 * shared library (mca_mnn_native) does not construct MnnEngine; only MnnEngineStatsSnapshot is read
 * from this module.  Real MNN inference lives in mnn_native_engine.cpp.  Before this class is wired
 * into the production path it must bind a real MNN interpreter/model instead of this sentinel, and
 * MnnEngine::load_model() must only report success once that binding succeeded.
 */
static void* const kSkeletonModelHandle = reinterpret_cast<void*>(0x4D4F444C); // 'MODL'


MnnEngine::MnnEngine(
    std::shared_ptr<MnnBackendOwner> backend,
    std::string model_id,
    std::string model_path,
    MnnModelMetadata metadata
) : Engine(std::move(backend), std::move(model_id), std::move(model_path)),
    metadata_(std::move(metadata)) {
    MCA_LOGI("MnnEngine", "MnnEngine instantiated for model %s", get_model_id().c_str());
}

MnnEngine::~MnnEngine() {
    unload();
}

std::shared_ptr<MnnBackendOwner> MnnEngine::get_mnn_backend() const noexcept {
    return std::dynamic_pointer_cast<MnnBackendOwner>(get_backend());
}

bool MnnEngine::load_model(std::string* error_msg) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    if (is_model_loaded_.load(std::memory_order_relaxed)) {
        return true;
    }

    if (common::SafePath::has_traversal(get_model_path())) {
        if (error_msg) *error_msg = "Security: Path traversal sequence detected in model_path";
        MCA_LOGE("MnnEngine", "Rejected insecure model path: %s", get_model_path().c_str());
        return false;
    }

    auto backend = get_mnn_backend();
    if (!backend || !backend->is_valid()) {
        if (error_msg) *error_msg = "Associated MnnBackendOwner is invalid or released";
        MCA_LOGE("MnnEngine", "Cannot load model without an active backend");
        return false;
    }

    // This module owns the engine/session *lifecycle* state machine.  It deliberately does not bind
    // an MNN graph yet: the real production MNN path is still mnn_native_engine.cpp, and nothing in
    // the shipping shared library constructs an MnnEngine (only MnnEngineStatsSnapshot crosses into
    // it).  The handle below is therefore an explicit marker, not an allocation, and the log line
    // says so -- an earlier revision logged "Successfully loaded MNN model" here, which made a
    // skeleton look like a completed load to anyone reading logcat or wiring this class up.
    mnn_model_handle_ = kSkeletonModelHandle;
    is_model_loaded_.store(true, std::memory_order_relaxed);

    MCA_LOGI("MnnEngine",
             "Lifecycle marked loaded for [%s, ctx=%d, vocab=%d] -- skeleton state only, "
             "no MNN graph is bound (production inference runs in mnn_native_engine.cpp)",
             get_model_id().c_str(), metadata_.context_window, metadata_.vocab_size);
    return true;
}

void MnnEngine::unload() noexcept {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    if (is_model_loaded_.exchange(false, std::memory_order_relaxed)) {
        mnn_model_handle_ = nullptr;
        MCA_LOGI("MnnEngine", "Unloaded model resources for %s", get_model_id().c_str());
    }
    Engine::unload();
}

bool MnnEngine::is_valid() const noexcept {
    return Engine::is_valid() && is_model_loaded_.load(std::memory_order_relaxed);
}

std::shared_ptr<MnnSession> MnnEngine::create_mnn_session(int64_t initial_generation) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    if (!is_valid()) {
        MCA_LOGE("MnnEngine", "Cannot create session on unloaded MnnEngine");
        return nullptr;
    }

    static std::atomic<int64_t> s_session_seq{1};
    const int64_t session_id = s_session_seq.fetch_add(1, std::memory_order_relaxed);
    auto self = std::static_pointer_cast<MnnEngine>(shared_from_this());
    return std::make_shared<MnnSession>(self, session_id, initial_generation);
}

std::vector<int32_t> MnnEngine::tokenize(const std::string& text) const {
    std::vector<int32_t> tokens;
    tokens.reserve(text.size());
    // NOT a tokenizer.  Byte + 10 is a lossless, collision-free stand-in so the lifecycle and session
    // tests can move token counts around; real tokenization is done by the MNN tokenizers in the
    // production path.  Kept because MnnSession counts these tokens.
    for (unsigned char ch : text) {
        tokens.push_back(static_cast<int32_t>(ch) + 10);
    }
    return tokens;
}

std::string MnnEngine::detokenize(const std::vector<int32_t>& tokens) const {
    std::string text;
    text.reserve(tokens.size());
    for (int32_t t : tokens) {
        if (t >= 10 && t <= 265) {
            text.push_back(static_cast<char>(t - 10));
        }
    }
    return text;
}

std::string MnnEngine::format_chat_prompt(const std::vector<MnnChatMessage>& messages) const {
    std::ostringstream ss;
    for (const auto& msg : messages) {
        const char* role_str = "user";
        switch (msg.role) {
            case MnnMessageRole::System: role_str = "system"; break;
            case MnnMessageRole::User: role_str = "user"; break;
            case MnnMessageRole::Assistant: role_str = "assistant"; break;
            case MnnMessageRole::Tool: role_str = "tool"; break;
        }

        ss << "<|im_start|>" << role_str << "\n";
        for (const auto& img : msg.image_paths) {
            ss << "<img>" << img << "</img>";
        }
        ss << msg.content << "<|im_end|>\n";
    }
    ss << "<|im_start|>assistant\n";
    return ss.str();
}

} // namespace mnn
} // namespace mca
