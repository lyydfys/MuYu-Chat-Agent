#ifndef MCA_MNN_CHAT_SESSION_INTERNAL_HPP
#define MCA_MNN_CHAT_SESSION_INTERNAL_HPP

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <fcntl.h>
#include <fstream>
#include <functional>
#include <limits.h>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <ostream>
#include <random>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#ifndef MCA_WITH_MNN_LLM
#define MCA_WITH_MNN_LLM 0
#endif

#ifndef MCA_WITH_MNN_DIFFUSION
#define MCA_WITH_MNN_DIFFUSION 0
#endif

#include "nlohmann/json.hpp"
#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_diffusion_batch_policy.hpp"
#include "mnn_legacy_chat_template_policy.hpp"
#include "mnn_embedding_bundle_policy.hpp"
#include "mnn_bundle_path_policy.hpp"
#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_template_parser.hpp"
#include "mca/mnn/mnn_vision_adapter.hpp"
#include "mca/mnn/mnn_stats_collector.hpp"
#include "mca/mnn/mnn_prompt_cache_manager.hpp"
#include "mca/mnn/mnn_protocol_parser.hpp"
#include "mca/mnn/mnn_diffusion_assets.hpp"
#include "mca/mnn/mnn_qnn_prompt_payload.hpp"
#include "mca/mnn/mnn_clip_tokenizer.hpp"
#include "mca/mnn/mnn_unet_timestep.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_json_options.hpp"
#include "mca/mnn/mnn_prompt_weighting.hpp"
#include "mca/mnn/mnn_numeric_convert.hpp"
#include "mca/mnn/mnn_backend_predicates.hpp"
#include "mca/mnn/mnn_jni_convert.hpp"
#include "mca/mnn/mnn_diffusion_asset_contract.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "mnn_runtime_capability_policy.hpp"
#include "mnn_sampling_config.hpp"
#include "mnn_stream_protocol_filter.hpp"
#include "mnn_visual_stream_queue.hpp"
#include "textual_inversion_conditioning.hpp"
#include "mnn_vision_path_policy.hpp"
#include "execution_asset_binding.hpp"

#if MCA_WITH_MNN_LLM
#include "llm/llm.hpp"
#endif

#if MCA_WITH_MNN_DIFFUSION
#include "mca/mnn/mnn_tensor_ops.hpp"
#include "mca/mnn/mnn_vae_image_writer.hpp"
#include "mca/mnn/mnn_clip_weighting.hpp"
#include "mca/mnn/mnn_pndm_scheduler.hpp"
#include "mca/mnn/mnn_tensor_copy.hpp"
#include "mca/mnn/mnn_diffusion_pipeline.hpp"
#include "mca/mnn/mnn_unet_smoke.hpp"
#include "mca/mnn/mnn_sdxl_pipeline.hpp"
#endif

#ifndef MCA_WITH_TOKENIZERS_CPP
#define MCA_WITH_TOKENIZERS_CPP 0
#endif

#ifndef MCA_MNN_RUNTIME_CACHE_NAMESPACE
#if MCA_WITH_MNN_LLM
#error "MNN runtime cache namespace must come from the verified vendor patch"
#else
#define MCA_MNN_RUNTIME_CACHE_NAMESPACE "unlinked"
#endif
#endif

#if (MCA_WITH_MNN_LLM || MCA_WITH_MNN_DIFFUSION) && defined(MNN_IMGCODECS)
#include <cv/cv.hpp>
#endif

#if MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/expr/Module.hpp>
#include "mnn_diffusion_tokenizer.hpp"
#include "scheduler.hpp"
#include "diffusion/stable_diffusion.hpp"
#include "diffusion/sana_diffusion.hpp"
#include "mnn_sana_session.hpp"
#endif

// Owner header for the MNN chat session cluster (T2B2-C1).
// State is defined in mnn_chat_session.cpp; the JNI entries TU and the
// session TU share this surface.  Guard structure mirrors the monolith.

namespace mca {
namespace mnn {
namespace chat_session {

using json = nlohmann::json;

// ---- session state (definitions live in mnn_chat_session.cpp) ----
extern std::mutex g_mnn_mutex;
extern std::mutex g_mnn_lifecycle_mutex;
extern bool g_loaded;
extern bool g_generation_active;
extern bool g_stop_requested;
extern std::atomic_bool g_stop_signal;
extern bool g_runner_ready;
extern std::string g_model_path;
extern std::string g_original_model_path;
extern std::string g_visual_model_path;
extern std::string g_mnn_model_type;
extern std::string g_mnn_backend_type;
extern std::string g_native_lib_dir;
extern std::string g_pending_chunk;
extern std::string g_pending_utf8_tail;
extern std::vector<std::string> g_model_stop_markers;
extern std::vector<std::string> g_active_stop_markers;
extern mca::mnn::StreamProtocolFilterState g_stream_protocol_filter;
extern mca::mnn::MnnRequestLifecyclePolicy g_mnn_request_lifecycle;
extern bool g_mnn_debug_trace_enabled;
extern bool g_mnn_debug_trace_truncated;
extern std::string g_mnn_debug_raw_output;
extern bool g_mnn_debug_token_ids_truncated;
extern std::vector<int> g_mnn_debug_generated_token_ids;
extern bool g_mnn_debug_prompt_truncated;
extern std::string g_mnn_debug_prompt;
extern std::string g_last_config_json;
extern std::string g_loaded_config_json;
extern std::string g_last_error;
extern int g_max_new_tokens;
extern int g_max_all_tokens;
extern int g_n_threads;
extern int g_generated_steps;
extern uint64_t g_load_generation;
extern uint64_t g_generation_sequence;
extern int64_t g_loaded_at_ms;
extern int64_t g_generation_started_at_ms;
extern int64_t g_first_chunk_at_ms;
extern bool g_eop_seen;
extern bool g_vision_ready;
extern bool g_multimodal_system_prompt_suppressed;
extern bool g_multimodal_history_suppressed;
extern bool g_sync_stepping;
extern size_t g_streamed_bytes;
extern std::string g_generation_stop_reason;
extern bool g_prompt_ends_inside_reasoning;
extern std::atomic<int> g_mnn_prefill_completed_tokens;
extern std::atomic<int> g_mnn_prefill_total_tokens;

#if MCA_WITH_MNN_LLM
struct VisualStreamState {
    mca::mnn::VisualStreamQueue queue;
    std::mutex consumer_mutex;
    mca::mnn::StreamProtocolFilterState filter;
    std::string utf8_tail;
};

extern std::shared_ptr<VisualStreamState> g_visual_stream;
extern std::thread g_visual_producer;
extern std::atomic_bool g_visual_producer_running;
extern std::mutex g_visual_stats_mutex;
extern std::string g_visual_stats_json;
extern MNN::Transformer::Llm* g_llm;
extern MnnPromptCacheRuntimeState g_mnn_prompt_cache;
extern long long g_mnn_prompt_cache_hits;
extern long long g_mnn_prompt_cache_misses;

using mca::mnn::MnnPromptCacheRuntimeState;

// ---- early helpers used by the stream buffer ----
void append_mnn_debug_raw_output(const char* data, size_t size);
void update_visual_stats_locked();
int64_t now_ms();

class MnnStreamBuffer : public std::streambuf {
public:
    explicit MnnStreamBuffer(std::shared_ptr<VisualStreamState> visual = {}) : visual_(std::move(visual)) {}

    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (s != nullptr && n > 0) {
            if (visual_) {
                if (!visual_->queue.write(s, static_cast<size_t>(n))) {
                    // The SDK owns a mutable context but exposes a const view.
                    // As in the text stepping adapter, mutate it only on the
                    // same thread that is executing MNN's generation loop.
                    if (g_llm != nullptr && g_llm->getContext() != nullptr) {
                        auto* context = const_cast<MNN::Transformer::LlmContext*>(g_llm->getContext());
                        context->status = MNN::Transformer::LlmStatus::USER_CANCEL;
                    }
                    return n;
                }
            } else {
                g_pending_chunk.append(s, static_cast<size_t>(n));
            }
            append_mnn_debug_raw_output(s, static_cast<size_t>(n));
            g_streamed_bytes += static_cast<size_t>(n);
            if (g_first_chunk_at_ms == 0) {
                g_first_chunk_at_ms = now_ms();
            }
            if (visual_) update_visual_stats_locked();
        }
        return n;
    }

    int overflow(int ch) override {
        if (ch != EOF) {
            char c = static_cast<char>(ch);
            if (xsputn(&c, 1) != 1) return traits_type::eof();
        }
        return ch;
    }

private:
    std::shared_ptr<VisualStreamState> visual_;

    static int64_t now_ms() {
        using namespace std::chrono;
        return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
    }
};

// ---- stream handles (need the class above) ----
extern std::unique_ptr<MnnStreamBuffer> g_stream_buffer;
extern std::unique_ptr<std::ostream> g_output_stream;

// ---- relocated helpers / entries support ----
bool join_visual_producer(JNIEnv* env);
void capture_mnn_debug_generated_token_ids_locked();
void capture_mnn_debug_prompt(const std::string& prompt);
inline mca::mnn::MnnPromptCacheContext mnn_prompt_cache_context_locked();
void clear_mnn_prompt_cache_tracking_locked(const std::string& reason);
void reset_mnn_native_prompt_cache_locked();
size_t mnn_effective_kv_history_locked();
std::string mnn_prompt_cache_assistant_text_locked();
void trim_mnn_protocol_suffix_from_live_history_locked();
void mark_mnn_prompt_cache_disabled_locked(bool multimodal, const std::string& reason);
void prepare_mnn_text_prompt_cache_locked(
        std::vector<MNN::Transformer::ChatMessage>& messages);
bool capture_mnn_text_prefill_locked(std::string& error);
void commit_mnn_text_prompt_cache_locked(const std::string& reason);
void rollback_mnn_text_prompt_cache_locked(
        const std::string& reason,
        bool forceClear);
void settle_mnn_text_prompt_cache_locked(const std::string& reason);
MNN::Transformer::ChatMessages prepare_mnn_visual_response(const ParsedMnnChatMessages& parsed);
bool begin_mnn_response(
        const ParsedMnnChatMessages& parsed,
        const MNN::Transformer::ChatMessages* preparedVisualContent = nullptr);
json build_mnn_config(const std::string& config_path, const std::string& params_json, bool for_load);
void destroy_llm_locked();
bool context_finished_locked();
std::string generation_stop_reason_locked();
bool android_stepping_boundary_locked();
void restore_stepping_status_if_needed_locked();
void configure_mnn_stop_markers_locked(const json& params);
void filter_mnn_stop_markers_locked(bool flush);

#endif

void reset_active_stop_markers_locked();
jstring take_pending_utf8_locked(JNIEnv* env, bool flush, bool* emitted);
std::string incompatible_legacy_mnn_visual_graph_message(const std::string& requestedPath);
std::string prepare_mnn_runtime_config(const std::string& requestedPath);
void set_error(const std::string& message);
void configure_mnn_debug_trace_locked(const json& params);
std::string mnn_debug_raw_output_hex();
std::string mnn_debug_generated_token_ids_json();
void reset_generation_state_locked();
std::string stats_json_locked();

// ---- request constants ----
constexpr jint kMnnRunnerUnavailable = -200;
constexpr jint kMnnInvalidState = -201;
constexpr jint kMnnLoadFailed = -202;
constexpr jint kMnnBeginFailed = -203;
constexpr jint kMnnLoadSignatureMismatch = -11;

}  // namespace chat_session
}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_CHAT_SESSION_INTERNAL_HPP
