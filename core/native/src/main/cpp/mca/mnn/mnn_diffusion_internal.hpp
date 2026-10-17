#ifndef MCA_MNN_DIFFUSION_INTERNAL_HPP
#define MCA_MNN_DIFFUSION_INTERNAL_HPP

// Owner header for the MNN diffusion cluster (T2B3).  State is defined
// in mnn_diffusion_state.cpp; the JNI layer and pipeline TUs share this
// surface.  Guard structure mirrors the monolith.

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
#include "mca/mnn/mnn_chat_jni_guards.hpp"
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

namespace mca {
namespace mnn {
namespace diffusion_detail {

using json = nlohmann::json;

// ---- diffusion state (definitions in mnn_diffusion_state.cpp) ----
extern std::mutex g_mnn_diffusion_mutex;
extern std::mutex g_mnn_diffusion_runtime_mutex;
extern bool g_mnn_diffusion_generating;
extern bool g_mnn_diffusion_cancel_requested;
extern bool g_mnn_diffusion_loaded;
extern int g_mnn_diffusion_step;
extern int g_mnn_diffusion_steps;
extern int g_mnn_diffusion_width;
extern int g_mnn_diffusion_height;
extern int g_mnn_diffusion_threads;
extern int64_t g_mnn_diffusion_started_at_ms;
extern int64_t g_mnn_diffusion_finished_at_ms;
extern int64_t g_mnn_diffusion_generation_sequence;
extern double g_mnn_diffusion_seconds_per_step;
extern std::string g_mnn_diffusion_bundle_root;
extern std::string g_mnn_diffusion_output_path;
extern std::string g_mnn_diffusion_phase;
extern std::string g_mnn_diffusion_message;
extern std::string g_mnn_diffusion_backend;
extern std::string g_mnn_diffusion_runner;
extern std::string g_mnn_diffusion_last_error;

class MnnDiffusionCancelled final : public std::exception {
public:
    const char *what() const noexcept override {
        return "MNN-Diffusion generation was cancelled.";
    }
};

// ---- cross-TU helpers ----
bool prompt_has_non_unity_attention_weight(
        const std::string& prompt,
        bool& weighted,
        std::string& error);
int64_t now_ms();
void set_mnn_diffusion_progress_locked(
        const std::string& phase,
        const std::string& message,
        int step,
        int steps,
        int width,
        int height,
        int threads);
json mnn_diffusion_progress_json_locked();
bool mnn_execution_assets_match(
        const mca::image::execution_assets::Binding& binding,
        const std::vector<std::string>& requiredPaths,
        std::string& error);
#if MCA_WITH_MNN_DIFFUSION
json run_unet_interpreter_smoke(const std::string& root, const std::string& backendMode);
#endif
#if MCA_WITH_MNN_DIFFUSION
bool has_community_clip_bundle(
        const std::string& root,
        const std::string& graphPath = "");
#endif
#if MCA_WITH_MNN_DIFFUSION
bool run_sdxl_clip_encoder_direct(
        const std::string& root,
        const std::string& modelFile,
        const std::string& pinnedExternalWeightPath,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        const std::vector<int>& tokenIds,
        const std::vector<float>& tokenWeights,
        const std::string& backendMode,
        int threads,
        FloatTensorData& hidden,
        FloatTensorData* pooled,
        std::string& error,
        json* debug,
        const mca::image::ClipConditionedSequence* conditioned = nullptr);
#endif
#if MCA_WITH_MNN_DIFFUSION
bool write_float_file(const std::string& path, const std::vector<float>& values, std::string& error);
#endif
#if MCA_WITH_MNN_DIFFUSION
bool validate_clip_pair_weights_for_token_id_graph(
        const mca::image::ClipTokenPair& tokenPair,
        bool includeNegative,
        std::string& error);
#endif
#if MCA_WITH_MNN_DIFFUSION
bool append_clip_prompt_weighting_evidence(
        json& target,
        const mca::image::ClipTokenPair& tokenPair,
        bool supported,
        bool includeNegative,
        std::string& error);
#endif
#if MCA_WITH_MNN_DIFFUSION
json encode_sdxl_prompt_conditioning_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        int width,
        int height,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson);
#endif
#if MCA_WITH_MNN_DIFFUSION
json encode_community_clip_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled = true,
        const std::string& textualInversionJson = "");
#endif
#if MCA_WITH_MNN_DIFFUSION
std::string resolve_mnn_tokenizer_json_path(const std::string& root);
#endif
#if MCA_WITH_MNN_DIFFUSION
bool tokenize_mnn_sd15_prompt(
        const std::string& root,
        const std::string& prompt,
        const MnnSemanticExecutionContract& contract,
        std::vector<int>& ids,
        mca::image::ClipTokenPair& executedTokenPair,
        bool& hasExecutedTokenPair,
        MnnNativeExecutionEvidence& evidence,
        std::string& error);
#endif
#if MCA_WITH_MNN_DIFFUSION
bool run_text_encoder_direct(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        const MnnOpenedTextEncoderBinding* textEncoderBinding = nullptr);
#endif
#if MCA_WITH_MNN_DIFFUSION
#if 0
bool pndm_step(
        const std::vector<float>& sample,
        std::vector<std::vector<float>>& ets,
        std::vector<float>& firstSample,
        const std::vector<float>& modelOutput,
        int index,
        const std::vector<int>& timesteps,
        const std::vector<float>& alphas,
        std::vector<float>& previous,
        std::string& error);
#endif
#endif
#if MCA_WITH_MNN_DIFFUSION
json encode_sd15_prompt_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson);
#endif
#if MCA_WITH_MNN_DIFFUSION
json run_mnn_sd15_interpreter_direct(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        const MnnSemanticExecutionContract& contract,
        MnnNativeExecutionEvidence& evidence,
        std::function<void(int)> progressCallback);
#endif
#if MCA_WITH_MNN_DIFFUSION
jintArray tokenize_prompt_token_ids(
        JNIEnv* env,
        const std::string& bundleRoot,
        const std::string& prompt,
        const std::string& tokenizerRoot,
        jint bosId,
        jint eosId,
        jint maxTokens);
#endif

}  // namespace diffusion_detail
}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_DIFFUSION_INTERNAL_HPP
