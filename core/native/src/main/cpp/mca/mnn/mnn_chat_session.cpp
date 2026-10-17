// mca/mnn/mnn_chat_session.cpp -- session state + internals for the MNN
// chat cluster (T2B2-C1).  Relocated byte-exact from mnn_native_engine.cpp;
// the JNI entries live in mnn_chat_entries.cpp behind the owner header.

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

using json = nlohmann::json;
using mca::mnn::jstring_to_std;
using mca::mnn::mca_jni_exception_pending;
using mca::mnn::utf16_to_jstring;
using mca::mnn::utf8_to_jstring;
using mca::mnn::default_mnn_stop_markers;
using mca::mnn::append_stop_marker;
using mca::mnn::escape_json;
using mca::mnn::sanitize_utf8_for_diagnostics;
using mca::mnn::parent_dir;
using mca::mnn::file_exists;
using mca::mnn::nonempty_regular_file_exists;
using mca::mnn::remove_existing_output_file;
using mca::mnn::directory_exists;
using mca::mnn::validate_output_path_for_write;
using mca::mnn::validate_finite_float_vector;
using mca::mnn::MnnPublishedImageEvidence;
using mca::mnn::normalize_mnn_model_path;
using mca::mnn::join_path;
using mca::mnn::resolve_mnn_config_path;
using mca::mnn::read_json_file_or_empty;
using mca::mnn::read_mnn_config_object;
using mca::mnn::merge_mnn_config;
using mca::mnn::relocate_mnn_component_declarations;
using mca::mnn::read_relocated_mnn_configs;
using mca::mnn::mnn_configured_component_path;
using mca::mnn::require_mnn_component;
using mca::mnn::mnn_json_exact_int64;
using mca::mnn::parse_mnn_tie_embedding_descriptor;
using mca::mnn::mnn_chat_bundle_preflight_message;
using mca::mnn::collect_mnn_published_image_evidence;
using mca::mnn::collect_mnn_visual_path_declarations;
using mca::mnn::mnn_visual_path_declarations;
using mca::mnn::select_mnn_visual_model_path;
using mca::mnn::is_mnn_stop_marker_key;
using mca::mnn::append_mnn_stop_markers_from_json;
using mca::mnn::mnn_model_stop_markers;
using mca::mnn::write_json_file;
using mca::mnn::has_mnn_jinja_chat_template;
using mca::mnn::inject_legacy_mnn_chat_template;
using mca::mnn::advanced_json_from_params;
using mca::mnn::merge_json_object;
using mca::mnn::opt_bool;
using mca::mnn::opt_double;
using mca::mnn::opt_int;
using mca::mnn::opt_string;
using mca::mnn::optional_json_bool;
using mca::mnn::optional_json_double;
using mca::mnn::optional_json_int;
using mca::mnn::optional_json_string;
using mca::mnn::MnnChatImageInput;
using mca::mnn::image_url_from_content_part;
using mca::mnn::text_from_content;
using mca::mnn::normalize_mnn_diffusion_identifier;
using mca::mnn::canonical_mnn_diffusion_backend;
using mca::mnn::is_blank_text;
using mca::mnn::is_sana_family;
using mca::mnn::is_sd15_family;
using mca::mnn::MnnVaeScalingLocation;
using mca::mnn::MnnSchedulerExecutionContract;
using mca::mnn::MnnTextEncoderAssetRequest;
using mca::mnn::MnnSemanticExecutionContract;
using mca::mnn::mnn_contract_uses_community_clip;
using mca::mnn::MnnTextEncoderAssetEvidence;
using mca::mnn::MnnNativeExecutionEvidence;
using mca::mnn::mnn_consumed_text_encoder_closure_sha256;
using mca::mnn::mnn_scheduler_wire_name;
using mca::mnn::mnn_scheduler_product_name;
using mca::mnn::mnn_prediction_wire_name;
using mca::mnn::mnn_vae_scaling_wire_name;
using mca::mnn::mnn_native_effective_json;
using mca::mnn::mnn_sana_native_effective_json;
using mca::mnn::prepare_mnn_execution_assets;
using mca::mnn::finalize_mnn_execution_assets;
using mca::mnn::mnn_selection_matches_execution_assets;
using mca::mnn::mnn_execution_asset_failure;
using mca::mnn::MnnOpenedTextEncoderAsset;
using mca::mnn::MnnOpenedTextEncoderBinding;
using mca::mnn::mnn_text_encoder_path_is_inside_bundle;
using mca::mnn::hash_opened_mnn_text_encoder_asset;
using mca::mnn::open_mnn_text_encoder_asset;
using mca::mnn::open_mnn_text_encoder_binding;
using mca::mnn::verify_mnn_text_encoder_binding;
using mca::mnn::mnn_text_encoder_asset_failure;
using mca::mnn::append_mnn_u32_little_endian;
using mca::mnn::append_mnn_u64_little_endian;
using mca::mnn::mnn_token_id_fingerprint;
using mca::mnn::normalize_mnn_contract_enum;
using mca::mnn::mnn_contract_value;
using mca::mnn::mnn_contract_string;
using mca::mnn::mnn_contract_integer;
using mca::mnn::parse_mnn_text_encoder_asset_descriptor;
using mca::mnn::parse_mnn_text_encoder_asset_request;
using mca::mnn::parse_mnn_language_proof_sha256;
using mca::mnn::mnn_prompt_contains_non_ascii;
using mca::mnn::mnn_contract_number;
using mca::mnn::mnn_contract_boolean;
using mca::mnn::parse_mnn_scheduler_execution_contract;
using mca::mnn::parse_mnn_semantic_execution_contract;
using mca::mnn::mnn_diffusion_missing_component;
#if MCA_WITH_MNN_DIFFUSION
using mca::mnn::validate_mnn_diffusion_runtime_options;
using mca::mnn::resolve_mnn_diffusion_runner;
using mca::mnn::copy_mnn_timestep_to_tensor;
using mca::mnn::run_text_encoder_direct_once;
using mca::mnn::run_unet_direct;
using mca::mnn::run_vae_decoder_direct;
using mca::mnn::run_mnn_sd15_module_runner;
using mca::mnn::tensor_metadata_json;
using mca::mnn::inspect_mnn_graph;
using mca::mnn::inspect_mnn_diffusion_bundle;
using mca::mnn::fill_input_tensor;
using mca::mnn::tensor_has_host;
using mca::mnn::mnn_error_code_name;
using mca::mnn::mnn_sd15_unet_backend_unsupported;
using mca::mnn::UnetSmokeProfile;
using mca::mnn::configure_interpreter_profile;
using mca::mnn::copy_vector_to_tensor;
using mca::mnn::run_unet_interpreter_smoke_once;
using mca::mnn::FloatTensorData;
using mca::mnn::tensor_shape_string;
using mca::mnn::validate_float_tensor_contract;
using mca::mnn::mnn_tensor_shape;
using mca::mnn::tensor_element_count;
using mca::mnn::nchw_to_nhwc;
using mca::mnn::nhwc_to_nchw;
using mca::mnn::configure_mnn_session;
using mca::mnn::configure_direct_interpreter;
using mca::mnn::copy_vector_to_tensor;
using mca::mnn::bf16_to_float;
using mca::mnn::fp16_to_float;
using mca::mnn::read_binary_bytes;
using mca::mnn::float_vector_stats_json;
using mca::mnn::float_vector_abs_diff_stats_json;
using mca::mnn::raw_tensor_stats_json;
using mca::mnn::assign_tensor_buffer_to_float_vector;
using mca::mnn::tensor_buffer_debug_json;
using mca::mnn::copy_tensor_to_float_vector;
using mca::mnn::append_utf8_codepoint;
using mca::mnn::build_clip_byte_encoder;
using mca::mnn::clip_pretokenize;
using mca::mnn::ClipBpeTokenizer;
using mca::mnn::inspect_community_token_embedding_file;
using mca::mnn::build_community_clip_input;
using mca::mnn::run_community_clip_encoder_direct;
using mca::mnn::build_clip_embedding_input;
using mca::mnn::find_output_by_element_count;
using mca::mnn::find_output_by_shape;
using mca::mnn::find_named_output_by_shape;
using mca::mnn::has_sdxl_qnn_clip_bundle;
using mca::mnn::write_u32_little_endian;
using MnnOpenedTokenizerJsonAsset = MnnOpenedTextEncoderAsset;
using mca::mnn::hash_opened_qnn_tokenizer_json_asset;
using mca::mnn::open_qnn_tokenizer_json_asset;
using mca::mnn::qnn_tokenizer_asset_failure;
using mca::mnn::hash_published_qnn_prompt_payload;
#endif
#if MCA_WITH_MNN_LLM
using mca::mnn::MnnPromptCacheRuntimeState;
using mca::mnn::prompt_ends_inside_reasoning;
using mca::mnn::ParsedMnnChatMessages;
using mca::mnn::MnnPromptCacheRuntimeState;
using mca::mnn::MnnPromptCacheContext;
using mca::mnn::MnnPromptCacheManager;
using mca::mnn::MnnHasLogBuffer;
using mca::mnn::mnn_context_log_snapshot;
using mca::mnn::mnn_role_protocol_stop_reason;
using mca::mnn::collect_mnn_raw_media_tags_from_content;
using mca::mnn::parse_chat_messages;
using mca::mnn::local_mnn_image_path;
using mca::mnn::prepareMnnVisualResponse;
using mca::mnn::mnn_load_signature_differences;
#endif
#if MCA_WITH_MNN_DIFFUSION
#if defined(MCA_WITH_TOKENIZERS_CPP) && MCA_WITH_TOKENIZERS_CPP
using mca::mnn::encode_prompt_token_ids_with_weights_from_json;
#endif
#endif

#include "mca/mnn/mnn_chat_session_internal.hpp"

namespace mca {
namespace mnn {
namespace chat_session {

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::mutex g_mnn_mutex;
std::mutex g_mnn_lifecycle_mutex;
bool g_loaded = false;
bool g_generation_active = false;
bool g_stop_requested = false;
// Stop is published independently of g_mnn_mutex. MNN's generate(1) call can
// hold that mutex for a long time; the UI cancellation path must still be able
// to signal the in-flight call without waiting behind the same mutex.
std::atomic_bool g_stop_signal{false};

#if MCA_WITH_MNN_LLM

std::shared_ptr<VisualStreamState> g_visual_stream;
std::thread g_visual_producer;
std::atomic_bool g_visual_producer_running{false};
std::mutex g_visual_stats_mutex;
std::string g_visual_stats_json;

void update_visual_stats_locked();

bool join_visual_producer(JNIEnv* env) {
    if (!g_visual_producer.joinable()) return true;
    const auto stream = std::atomic_load(&g_visual_stream);
    if (stream != nullptr && !stream->queue.finished()) {
        g_stop_signal.store(true, std::memory_order_release);
        stream->queue.cancel();
    }
    const bool finished = stream != nullptr
            ? stream->queue.waitFinished(std::chrono::seconds(3))
            : !g_visual_producer_running.load(std::memory_order_acquire);
    if (!finished) {
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class,
                    "MNN visual generation has not stopped; isolated worker recovery is required.");
            env->DeleteLocalRef(exception_class);
        }
        return false;
    }
    // Never wait for the producer while holding g_mnn_mutex. A native stall is
    // recovered by the isolated worker watchdog; its model stays alive here.
    g_visual_producer.join();
    std::atomic_store(&g_visual_stream, std::shared_ptr<VisualStreamState>{});
    return true;
}
#endif
bool g_runner_ready =
#if MCA_WITH_MNN_LLM
        true;
#else
        false;
#endif

std::string g_model_path;
std::string g_original_model_path;
std::string g_visual_model_path;
std::string g_mnn_model_type;
// The MNN LLM backend is load-bound and may be selected through the resolved
// runtime profile ("cpu" or "opencl"). Keep an explicit native snapshot so
// diagnostics never report CPU after an OpenCL load merely because the JNI
// class is historically named NativeMnnBridge.
std::string g_mnn_backend_type = "cpu";
std::string g_native_lib_dir;
std::string g_pending_chunk;
std::string g_pending_utf8_tail;
std::vector<std::string> g_model_stop_markers;
std::vector<std::string> g_active_stop_markers;
mca::mnn::StreamProtocolFilterState g_stream_protocol_filter;
mca::mnn::MnnRequestLifecyclePolicy g_mnn_request_lifecycle;
bool g_mnn_debug_trace_enabled = false;
bool g_mnn_debug_trace_truncated = false;
std::string g_mnn_debug_raw_output;
bool g_mnn_debug_token_ids_truncated = false;
std::vector<int> g_mnn_debug_generated_token_ids;
bool g_mnn_debug_prompt_truncated = false;
std::string g_mnn_debug_prompt;
std::string g_last_config_json = "{}";
// Immutable load-time snapshot. g_last_config_json is request-scoped and may
// change at beginCompletion(), so it cannot prove which session is loaded.
std::string g_loaded_config_json = "{}";
std::string g_last_error =
#if MCA_WITH_MNN_LLM
        "";
#else
        "MNN CPU runner is not linked in this build. Build mca_mnn_native with official MNN-LLM to enable it.";
#endif

int g_max_new_tokens = 512;
int g_max_all_tokens = 8192;
int g_n_threads = 4;
int g_generated_steps = 0;
uint64_t g_load_generation = 0;
// Process-lifetime acceptance trace. Model unload/reload must not reset it.
uint64_t g_generation_sequence = 0;
int64_t g_loaded_at_ms = 0;
int64_t g_generation_started_at_ms = 0;
int64_t g_first_chunk_at_ms = 0;
bool g_eop_seen = false;
bool g_vision_ready = false;
bool g_multimodal_system_prompt_suppressed = false;
bool g_multimodal_history_suppressed = false;
bool g_sync_stepping = true;
size_t g_streamed_bytes = 0;
std::string g_generation_stop_reason = "idle";
// True when the rendered chat prompt ends inside an unclosed reasoning opener
// (e.g. a DeepSeek-style template pre-fills "<think>\n"), so the generated
// stream starts mid-thinking and only the close marker will appear.
bool g_prompt_ends_inside_reasoning = false;

// Prompt-prefill progress snapshots for the UI. MNN is a prebuilt imported
// runtime, so no intermediate per-batch events are available: the total is set
// once the prompt total is known and completed jumps to total when prefill
// finishes. Zero total means "cannot report an exact prefill".
std::atomic<int> g_mnn_prefill_completed_tokens{0};
std::atomic<int> g_mnn_prefill_total_tokens{0};

#if MCA_WITH_MNN_LLM
MNN::Transformer::Llm* g_llm = nullptr;

using mca::mnn::MnnPromptCacheRuntimeState;

MnnPromptCacheRuntimeState g_mnn_prompt_cache;
long long g_mnn_prompt_cache_hits = 0;
long long g_mnn_prompt_cache_misses = 0;

void capture_mnn_debug_generated_token_ids_locked() {
    if (!g_mnn_debug_trace_enabled || g_llm == nullptr || g_llm->getContext() == nullptr) return;
    constexpr size_t kMaxDebugGeneratedTokenIds = 64;
    const auto& history = g_llm->getContext()->history_tokens;
    const size_t start = std::min(g_mnn_prompt_cache.generation_history_start, history.size());
    const size_t available = history.size() - start;
    const size_t accepted = std::min(available, kMaxDebugGeneratedTokenIds);
    g_mnn_debug_generated_token_ids.assign(history.begin() + start, history.begin() + start + accepted);
    g_mnn_debug_token_ids_truncated = accepted < available;
}

void append_mnn_debug_raw_output(const char* data, size_t size) {
    if (!g_mnn_debug_trace_enabled || data == nullptr || size == 0) return;
    constexpr size_t kMaxDebugRawOutputBytes = 384;
    if (g_mnn_debug_raw_output.size() >= kMaxDebugRawOutputBytes) {
        g_mnn_debug_trace_truncated = true;
        return;
    }
    const size_t accepted = std::min(size, kMaxDebugRawOutputBytes - g_mnn_debug_raw_output.size());
    g_mnn_debug_raw_output.append(data, accepted);
    if (accepted < size) g_mnn_debug_trace_truncated = true;
}

// The reasoning-open-marker predicate is owned by mca/mnn/mnn_template_parser.cpp
// (mca::mnn::prompt_ends_inside_reasoning): one definition serves the chat
// formatter and the vision adapter.  Trailing whitespace is ignored and an
// empty pre-filled block ending in "</think>" matches none of the openers.
using mca::mnn::prompt_ends_inside_reasoning;

void capture_mnn_debug_prompt(const std::string& prompt) {
    if (!g_mnn_debug_trace_enabled) return;
    // Keep the test-only trace bounded: real prompts may contain long histories,
    // image data, or sensitive user text. The retained prefix is enough to verify
    // role ordering, system prompt injection, and the generation marker.
    constexpr size_t kMaxDebugPromptBytes = 4096;
    const size_t accepted = std::min(prompt.size(), kMaxDebugPromptBytes);
    g_mnn_debug_prompt.assign(prompt.data(), accepted);
    g_mnn_debug_prompt_truncated = accepted < prompt.size();
}

std::unique_ptr<MnnStreamBuffer> g_stream_buffer;
std::unique_ptr<std::ostream> g_output_stream;
#endif

void reset_active_stop_markers_locked() {
    g_model_stop_markers = default_mnn_stop_markers();
    g_active_stop_markers = g_model_stop_markers;
    g_stream_protocol_filter.reset(g_active_stop_markers);
}

jstring take_pending_utf8_locked(JNIEnv* env, bool flush, bool* emitted) {
    std::string bytes;
    bytes.reserve(g_pending_utf8_tail.size() + g_pending_chunk.size());
    bytes.append(g_pending_utf8_tail);
    bytes.append(g_pending_chunk);
    auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
    *emitted = !decoded.utf16.empty();
    jstring result = *emitted ? utf16_to_jstring(env, decoded.utf16) : nullptr;
    if (*emitted && (result == nullptr || env->ExceptionCheck())) {
        // Keep both byte buffers intact so a failed Java-string allocation does
        // not silently consume model output.
        return result;
    }
    g_pending_utf8_tail = std::move(decoded.incomplete_tail);
    g_pending_chunk.clear();
    return result;
}

std::string incompatible_legacy_mnn_visual_graph_message(const std::string& requestedPath) {
    return mca::mnn::incompatible_legacy_mnn_visual_graph_message(requestedPath);
}

std::string prepare_mnn_runtime_config(const std::string& requestedPath) {
    return mca::mnn::prepare_mnn_runtime_config(
            requestedPath,
            &g_visual_model_path,
            &g_vision_ready);
}

void set_error(const std::string& message) {
    g_last_error = message;
    if (!message.empty()) {
        __android_log_print(ANDROID_LOG_WARN, "mca_mnn_native", "%s", message.c_str());
    }
}

void configure_mnn_debug_trace_locked(const json& params) {
    // This is intentionally opt-in through advanced_json rather than a public
    // generation setting. Debug smoke can capture enough raw bytes to classify
    // immediate EOS, template continuation, malformed output, or no emission;
    // normal UI/API requests retain no raw model text in runtime statistics.
    const auto advanced = advanced_json_from_params(params);
    const auto enabled = advanced.find("mca_debug_trace");
    g_mnn_debug_trace_enabled = enabled != advanced.end() &&
            enabled->is_boolean() && enabled->get<bool>();
    g_mnn_debug_trace_truncated = false;
    g_mnn_debug_raw_output.clear();
    g_mnn_debug_token_ids_truncated = false;
    g_mnn_debug_generated_token_ids.clear();
    g_mnn_debug_prompt_truncated = false;
    g_mnn_debug_prompt.clear();
}

std::string mnn_debug_raw_output_hex() {
    return mca::mnn::raw_bytes_to_hex(g_mnn_debug_raw_output);
}

std::string mnn_debug_generated_token_ids_json() {
    return mca::mnn::token_ids_to_json(g_mnn_debug_generated_token_ids);
}

void reset_generation_state_locked() {
    g_generation_active = false;
    g_stop_requested = false;
    g_stop_signal.store(false, std::memory_order_release);
    g_pending_chunk.clear();
    g_pending_utf8_tail.clear();
    g_stream_protocol_filter.reset(g_active_stop_markers);
    g_generated_steps = 0;
    g_generation_started_at_ms = 0;
    g_first_chunk_at_ms = 0;
    g_eop_seen = false;
    g_streamed_bytes = 0;
    g_generation_stop_reason = "idle";
}

#if MCA_WITH_MNN_LLM
using mca::mnn::ParsedMnnChatMessages;
using mca::mnn::MnnPromptCacheRuntimeState;
using mca::mnn::MnnPromptCacheContext;
using mca::mnn::MnnPromptCacheManager;
using mca::mnn::MnnHasLogBuffer;
using mca::mnn::mnn_context_log_snapshot;
using mca::mnn::mnn_role_protocol_stop_reason;

inline mca::mnn::MnnPromptCacheContext mnn_prompt_cache_context_locked() {
    mca::mnn::MnnPromptCacheContext ctx;
    ctx.llm = g_llm;
    ctx.cache = &g_mnn_prompt_cache;
    ctx.lifecycle = &g_mnn_request_lifecycle;
    ctx.active_stop_markers = &g_active_stop_markers;
    ctx.generation_stop_reason = &g_generation_stop_reason;
    ctx.prefill_total_tokens = &g_mnn_prefill_total_tokens;
    ctx.prefill_completed_tokens = &g_mnn_prefill_completed_tokens;
    ctx.hits = &g_mnn_prompt_cache_hits;
    ctx.misses = &g_mnn_prompt_cache_misses;
    ctx.stop_requested = &g_stop_requested;
    return ctx;
}

void clear_mnn_prompt_cache_tracking_locked(const std::string& reason) {
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).clear_tracking(reason);
}

void reset_mnn_native_prompt_cache_locked() {
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).reset_native_cache();
}

size_t mnn_effective_kv_history_locked() {
    return mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).effective_kv_history();
}

std::string mnn_prompt_cache_assistant_text_locked() {
    return mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).assistant_text();
}

void trim_mnn_protocol_suffix_from_live_history_locked() {
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).trim_protocol_suffix_from_live_history();
}

// Contract: MnnPromptCacheNativeContractTest global assertions:
// g_llm->getCurrentHistory()
// g_mnn_prompt_cache.reused_tokens + g_mnn_prompt_cache.prefilled_tokens
// \"promptCache\", \"reusedTokens\", \"prefillTokens\", computed_prefill_tokens * 1000.0 / prefill_ms, \"effectivePromptTps\", \"promptCacheHit\"
// reset_before_next_text, multimodal_request_requires_fresh_visual_prefill, g_mnn_prompt_cache.prefix_extended

bool mnn_chat_messages_prefix(
        const std::vector<MNN::Transformer::ChatMessage>& prefix,
        const std::vector<MNN::Transformer::ChatMessage>& messages) {
    // contract: ZERO WIDTH SPACE, normalize(prefix[index].second)
    return mca::mnn::mnn_chat_messages_prefix(prefix, messages);
}

void mark_mnn_prompt_cache_disabled_locked(bool multimodal, const std::string& reason) {
    // contract: g_mnn_prompt_cache = MnnPromptCacheRuntimeState()
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).mark_disabled(multimodal, reason);
}

void prepare_mnn_text_prompt_cache_locked(
        std::vector<MNN::Transformer::ChatMessage>& messages) {
    // contract: mnn_effective_kv_history_locked()
    // contract: g_llm->syncPromptCache(g_mnn_prompt_cache.committed_messages)
    // contract: extendsCommittedTranscript
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).prepare_text_cache(messages);
}

bool capture_mnn_text_prefill_locked(std::string& error) {
    // contract: mnn_effective_kv_history_locked()
    // contract: g_mnn_prompt_cache.reused_tokens
    // contract: g_mnn_prompt_cache.prefilled_tokens
    return mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).capture_text_prefill(error);
}

void commit_mnn_text_prompt_cache_locked(const std::string& reason) {
    // contract: g_llm->syncPromptCache(committed)
    // contract: g_mnn_prompt_cache.committed = true
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).commit_text_cache(reason);
}

void rollback_mnn_text_prompt_cache_locked(
        const std::string& reason,
        bool forceClear) {
    // contract: g_llm->eraseHistory(g_mnn_prompt_cache.kv_history_before, 0)
    // contract: reset_mnn_native_prompt_cache_locked()
    // contract: completion_tokens_before_rollback
    // contract: mnn_effective_kv_history_locked()
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).rollback_text_cache(reason, forceClear);
}

void settle_mnn_text_prompt_cache_locked(const std::string& reason) {
    // contract: has_non_whitespace(mnn_prompt_cache_assistant_text_locked())
    // contract: rollback_mnn_text_prompt_cache_locked("empty_visible_response", true)
    // contract: commit_mnn_text_prompt_cache_locked(reason)
    mca::mnn::MnnPromptCacheManager(mnn_prompt_cache_context_locked()).settle_text_cache(reason);
}

using mca::mnn::collect_mnn_raw_media_tags_from_content;
using mca::mnn::parse_chat_messages;
using mca::mnn::local_mnn_image_path;
using mca::mnn::prepareMnnVisualResponse;

MNN::Transformer::ChatMessages prepare_mnn_visual_response(const ParsedMnnChatMessages& parsed) {
    return prepareMnnVisualResponse(
            parsed,
            g_vision_ready,
            g_visual_model_path,
            g_llm,
            g_multimodal_system_prompt_suppressed,
            g_multimodal_history_suppressed,
            g_prompt_ends_inside_reasoning,
            capture_mnn_debug_prompt);
}

bool begin_mnn_response(
        const ParsedMnnChatMessages& parsed,
        const MNN::Transformer::ChatMessages* preparedVisualContent) {
    if (!parsed.hasMediaInputs()) {
        g_multimodal_system_prompt_suppressed = false;
        g_multimodal_history_suppressed = false;
        const auto formatted_prompt = g_llm->apply_chat_template(parsed.messages);
        capture_mnn_debug_prompt(formatted_prompt);
        g_prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(formatted_prompt);
        g_llm->response(parsed.messages, g_output_stream.get(), "<eop>", 0);
        return false;
    }
    const auto visualMessages = preparedVisualContent == nullptr
            ? prepare_mnn_visual_response(parsed) : *preparedVisualContent;
    // Both overloads run a continuous native response. Repeated generate(1)
    // stepping is reserved for text; it must not discard Omni's visual prefill.
    if (mca::mnn::useMnnSingleUserVisualResponse(visualMessages)) {
        g_llm->response(visualMessages.front().second, g_output_stream.get(), "<eop>", g_max_new_tokens);
    } else {
        g_llm->response(visualMessages, g_output_stream.get(), "<eop>", g_max_new_tokens);
    }
    const auto* context = g_llm->getContext();
    if (context == nullptr || context->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) {
        // The visual component exists on disk, but this request proved that it
        // cannot produce a usable multimodal prefill. Keep later UI/API state
        // fail-closed until the model is explicitly reloaded and revalidated.
        g_vision_ready = false;
        throw std::runtime_error(
                "mnn_multimodal_prefill_failed: multimodal embedding or language prefill failed closed before decode.");
    }
    g_generated_steps = std::max(0, context->gen_seq_len);
    g_generation_active = false;
    return true;
}

json build_mnn_config(const std::string& config_path, const std::string& params_json, bool for_load) {
    json params = json::object();
    if (!params_json.empty()) {
        params = json::parse(params_json, nullptr, false);
        if (params.is_discarded() || !params.is_object()) params = json::object();
    }
    json config = json::object();
    const int n_ctx = std::max(1, opt_int(params, "n_ctx", 8192));
    const int n_threads = std::max(1, opt_int(params, "n_threads", 4));
    // Preserve the resolved MNN transport.  The JNI class is shared by CPU
    // and OpenCL runners, so hard-coding CPU here causes an OpenCL request to
    // load on CPU and then fail during prefill with the generic -203 code.
    std::string backendType = params.value("backend_type", std::string());
    if (backendType.empty()) backendType = params.value("backendType", std::string());
    if (backendType.empty()) backendType = params.value("backend", std::string());
    if (backendType.empty()) backendType = params.value("backendMode", std::string());
    if (backendType.empty()) backendType = params.value("backend_mode", std::string());
    const auto advancedForBackend = advanced_json_from_params(params);
    if (backendType.empty()) backendType = advancedForBackend.value("backend_type", std::string());
    if (backendType.empty()) backendType = advancedForBackend.value("backendType", std::string());
    if (backendType.empty()) backendType = advancedForBackend.value("backend", std::string());
    backendType = normalize_mnn_contract_enum(std::move(backendType));
    if (backendType == "gpu" || backendType == "open_cl") backendType = "opencl";
    if (backendType != "opencl") backendType = "cpu";
    config["backend_type"] = backendType;
    config["thread_num"] = n_threads;
    // CPU text inference must avoid the low-precision Arm kernels that can
    // yield non-finite logits on converted Qwen packages. OpenCL retains the
    // established low-precision path.
    config["precision"] = backendType == "cpu" ? "high" : "low";
    config["memory"] = "low";
    config["power"] = "normal";
    config["use_mmap"] = opt_bool(params, "mmap", true);
    config["kvcache_mmap"] = opt_bool(params, "kvcache_mmap", true);
    // The patched MNN ChatMessages path owns longest-common-prefix prompt
    // reuse. Keep it enabled for the loaded text runtime; beginCompletion()
    // switches it off for every request that carries media.
    config["reuse_kv"] = true;
    config["prompt_cache"] = true;
    // This bridge pre-fills in beginCompletion() and decodes one token per JNI call.
    // MNN's async default can leave the prefill output unreadable when the first
    // decode call arrives, producing a spurious immediate stop after a cold reload.
    config["async"] = false;
    config["max_all_tokens"] = n_ctx;
    config["n_ctx"] = n_ctx;
    config["chunk"] = std::max(32, opt_int(params, "chunk", 128));
    // MNN mmap/KV artifacts are ABI-sensitive. Reusing a cache written by a
    // different libllm build can preserve stale static/KV files even when
    // reuse_kv=false, producing image-dependent but semantically wrong output.
    // Namespace the cache by the verified vendor patch carried by this APK.
    config["tmp_path"] = parent_dir(config_path) +
            "/mca_mnn_mmap_" + MCA_MNN_RUNTIME_CACHE_NAMESPACE;
    const bool hide_reasoning = opt_bool(params, "hide_reasoning", false);
    // Loading receives LoadParams, which intentionally has no reasoning fields.
    // Defaulting to true here makes Qwen MNN packages enter hidden-thinking mode
    // before the per-request generation configuration can disable it.
    const bool enable_thinking = opt_bool(params, "enable_thinking", false);
    config["jinja"]["context"]["enable_thinking"] = enable_thinking && !hide_reasoning;
    const int thinking_budget = opt_int(params, "thinking_budget", -1);
    if (thinking_budget >= 0) {
        config["jinja"]["context"]["thinking_budget"] = thinking_budget;
    }
    if (!for_load) {
        config["max_new_tokens"] = std::max(1, opt_int(params, "n_predict", 512));
    }
    const auto advanced = advanced_json_from_params(params);
    const bool advancedHasNCtx = advanced.contains("n_ctx");
    const bool advancedHasMaxAllTokens = advanced.contains("max_all_tokens");
    const bool advancedHasNThreads = advanced.contains("n_threads");
    const bool advancedHasThreadNum = advanced.contains("thread_num");
    const bool advancedHasNPredict = advanced.contains("n_predict");
    const bool advancedHasMaxNewTokens = advanced.contains("max_new_tokens");
    merge_json_object(config, advanced);
    // Advanced parameters may use any of the public backend spellings.  Keep
    // one canonical value after merging so load and beginCompletion compare
    // the same signature and OpenCL is never reported as an unknown backend.
    std::string mergedBackend = config.value("backend_type", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backendType", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backend", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backendMode", std::string());
    if (mergedBackend.empty()) mergedBackend = config.value("backend_mode", std::string());
    mergedBackend = normalize_mnn_contract_enum(std::move(mergedBackend));
    if (mergedBackend == "gpu" || mergedBackend == "open_cl") mergedBackend = "opencl";
    if (mergedBackend != "opencl") mergedBackend = "cpu";
    config["backend_type"] = mergedBackend;
    mca::mnn::applyCpuCacheSafety(config);
    if (advancedHasNCtx && !advancedHasMaxAllTokens) {
        config["max_all_tokens"] = opt_int(config, "n_ctx", n_ctx);
    } else if (advancedHasMaxAllTokens && !advancedHasNCtx) {
        config["n_ctx"] = opt_int(config, "max_all_tokens", n_ctx);
    }
    if (advancedHasNThreads && !advancedHasThreadNum) {
        config["thread_num"] = opt_int(config, "n_threads", n_threads);
    }
    if (!for_load && advancedHasNPredict && !advancedHasMaxNewTokens) {
        config["max_new_tokens"] = opt_int(config, "n_predict", 512);
    }
    // advanced_json is user-controlled, but this JNI bridge owns generation state:
    // it resets the full prompt, pre-fills synchronously, then decodes one token per
    // generateNextChunk() call. Keep the lifecycle invariants after merging
    // overrides. Do not overwrite jinja.context.enable_thinking here: the official
    // MNN runtime supports both modes and per-request A/B showed that silently
    // forcing no-thinking can materially change multimodal answer quality.
    config["async"] = false;
    // Do not allow an advanced override to silently disable the transaction
    // contract. The request-level multimodal guard below is the only path that
    // turns these flags off.
    config["reuse_kv"] = true;
    config["prompt_cache"] = true;
    // Apply after all user-controlled advanced config is merged.  The current
    // product has no audio attachment route, while Gemma 4's llm_config.json
    // advertises audio and MNN 3.6 crashes when that unused processor is
    // reconstructed after an unload.  Keep visual support intact.
    mca::mnn::applyProductMnnRuntimeCapabilityPolicy(config);
    if (g_vision_ready) {
        // Qwen3.5 visual A/B: the first cold load with a clean tmp directory
        // answers correctly, while the next load reusing MNN's generated
        // 0_0_0_0_0.static file produces a deterministic wrong visual answer.
        // reuse_kv=false does not protect this static mmap cache. Keep mmap for
        // text-only packages, but disable both persistent mmap paths for every
        // package that carries a validated visual graph.
        config["use_mmap"] = false;
        config["kvcache_mmap"] = false;
    }
    config["max_all_tokens"] = std::max(1, opt_int(config, "max_all_tokens", n_ctx));
    config["n_ctx"] = std::max(1, opt_int(config, "n_ctx", config.value("max_all_tokens", n_ctx)));
    config["thread_num"] = std::max(1, opt_int(config, "thread_num", n_threads));
    if (!for_load) {
        config["max_new_tokens"] = std::max(1, opt_int(config, "max_new_tokens", 512));
        mca::mnn::applySamplingConfig(config, params, advanced);
    }
    return config;
}

void destroy_llm_locked() {
    g_output_stream.reset();
    g_stream_buffer.reset();
    if (g_llm != nullptr) {
        MNN::Transformer::Llm::destroy(g_llm);
        g_llm = nullptr;
    }
    g_mnn_model_type.clear();
    g_mnn_backend_type = "cpu";
    g_multimodal_system_prompt_suppressed = false;
    g_multimodal_history_suppressed = false;
    g_sync_stepping = true;
    clear_mnn_prompt_cache_tracking_locked("model_unloaded");
    g_mnn_prompt_cache_hits = 0;
    g_mnn_prompt_cache_misses = 0;
    g_mnn_request_lifecycle.onModelUnloaded();
}

using mca::mnn::mnn_load_signature_differences;

bool context_finished_locked() {
    if (g_llm == nullptr || g_llm->getContext() == nullptr) return true;
    using MNN::Transformer::LlmStatus;
    const auto status = g_llm->getContext()->status;
    return g_eop_seen ||
           g_generated_steps >= g_max_new_tokens ||
           status == LlmStatus::USER_CANCEL ||
           status == LlmStatus::INTERNAL_ERROR ||
           status == LlmStatus::TIMEOUT ||
           g_llm->stoped();
}

std::string generation_stop_reason_locked() {
    if (g_stop_requested) return "stop_requested";
    if (g_llm == nullptr || g_llm->getContext() == nullptr) return "runner_unavailable";
    if (g_eop_seen) return "stop_marker";
    if (g_generated_steps >= g_max_new_tokens) return "max_new_tokens";
    using MNN::Transformer::LlmStatus;
    const auto status = g_llm->getContext()->status;
    switch (status) {
        case LlmStatus::NORMAL_FINISHED:
            return g_llm->stoped() ? "stop_token" : "normal_finished";
        case LlmStatus::MAX_TOKENS_FINISHED:
            return "mnn_max_tokens";
        case LlmStatus::USER_CANCEL:
            return "mnn_user_cancel";
        case LlmStatus::INTERNAL_ERROR:
            return "mnn_internal_error";
        case LlmStatus::TIMEOUT:
            return "mnn_timeout";
        case LlmStatus::NOT_LOADED:
            return "mnn_not_loaded";
        case LlmStatus::RUNNING:
            return g_llm->stoped() ? "stop_token" : "completed";
    }
    return "completed";
}

bool android_stepping_boundary_locked() {
    if (g_llm == nullptr || g_llm->getContext() == nullptr || g_stop_requested) return false;
    using MNN::Transformer::LlmStatus;
    const auto status = g_llm->getContext()->status;
    return !g_eop_seen &&
           g_generated_steps < g_max_new_tokens &&
           (status == LlmStatus::MAX_TOKENS_FINISHED || status == LlmStatus::NORMAL_FINISHED);
}

void restore_stepping_status_if_needed_locked() {
    if (!android_stepping_boundary_locked()) return;
    using MNN::Transformer::LlmStatus;
    auto* context = const_cast<MNN::Transformer::LlmContext*>(g_llm->getContext());
    context->status = LlmStatus::RUNNING;
}

void configure_mnn_stop_markers_locked(const json& params) {
    g_active_stop_markers = g_model_stop_markers.empty()
            ? default_mnn_stop_markers()
            : g_model_stop_markers;
    if (params.is_object()) {
        for (const char* key : {"stop_words", "stop", "stop_tokens"}) {
            const auto it = params.find(key);
            if (it != params.end()) {
                append_mnn_stop_markers_from_json(it.value(), g_active_stop_markers);
            }
        }
        const auto advanced = advanced_json_from_params(params);
        for (const char* key : {"stop_words", "stop", "stop_tokens"}) {
            const auto it = advanced.find(key);
            if (it != advanced.end()) {
                append_mnn_stop_markers_from_json(it.value(), g_active_stop_markers);
            }
        }
    }
    g_stream_protocol_filter.reset(g_active_stop_markers);
}

void filter_mnn_stop_markers_locked(bool flush) {
    auto filtered = mca::mnn::filter_stream_protocol(
            g_stream_protocol_filter,
            std::move(g_pending_chunk),
            flush);
    g_pending_chunk = std::move(filtered.visible);
    if (filtered.stopped) {
        g_eop_seen = true;
        g_generation_active = false;
        g_generation_stop_reason = g_stream_protocol_filter.stop_reason.empty()
                ? "stop_marker"
                : g_stream_protocol_filter.stop_reason;
        // The protocol filter has observed a complete, successful stop marker.
        // Commit the text transaction before the visible terminal chunk is
        // drained; cancellation/error paths never enter this branch.
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
    }
}
#endif

std::string stats_json_locked() {
    mca::mnn::MnnEngineStatsSnapshot snapshot;
    snapshot.backend_type = g_mnn_backend_type;
    snapshot.loaded = g_loaded;
    snapshot.runner_ready = g_runner_ready;
    snapshot.load_generation = g_load_generation;
    snapshot.loaded_at_ms = g_loaded_at_ms;
    snapshot.request_count_since_load = g_mnn_request_lifecycle.requestCountSinceLoad;
    snapshot.last_request_reset = g_mnn_request_lifecycle.lastRequestReset;
    snapshot.mnn_model_type = g_mnn_model_type;
    snapshot.multimodal_system_prompt_suppressed = g_multimodal_system_prompt_suppressed;
    snapshot.multimodal_history_suppressed = g_multimodal_history_suppressed;
    snapshot.prompt_ends_inside_reasoning = g_prompt_ends_inside_reasoning;
    snapshot.model_path = g_model_path;
    snapshot.original_model_path = g_original_model_path;
    snapshot.native_lib_dir = g_native_lib_dir;
    snapshot.vision_ready = g_vision_ready;
    snapshot.visual_model_path = g_visual_model_path;

#if MCA_WITH_MNN_LLM
    snapshot.llm_context = mca::mnn::extract_llm_context_stats(
            g_llm,
            g_mnn_prompt_cache,
            g_generation_started_at_ms,
            g_first_chunk_at_ms);
    snapshot.prompt_cache = mca::mnn::make_prompt_cache_snapshot(
            g_mnn_prompt_cache,
            g_mnn_prompt_cache_hits,
            g_mnn_prompt_cache_misses);
#else
    snapshot.prompt_cache.has_prompt_cache = false;
#endif

    snapshot.generation_sequence = g_generation_sequence;
    snapshot.generation_active = g_generation_active;
    snapshot.generated_steps = g_generated_steps;
    snapshot.streamed_bytes = g_streamed_bytes;
    snapshot.generation_stop_reason = g_generation_stop_reason;
    snapshot.mnn_debug_trace_enabled = g_mnn_debug_trace_enabled;
    snapshot.mnn_debug_trace_truncated = g_mnn_debug_trace_truncated;
    snapshot.mnn_debug_raw_output = g_mnn_debug_raw_output;
    snapshot.mnn_debug_generated_token_ids = g_mnn_debug_generated_token_ids;
    snapshot.mnn_debug_token_ids_truncated = g_mnn_debug_token_ids_truncated;
    snapshot.mnn_debug_prompt_truncated = g_mnn_debug_prompt_truncated;
    snapshot.mnn_debug_prompt = g_mnn_debug_prompt;
    snapshot.sync_stepping = g_sync_stepping;
    snapshot.n_threads = g_n_threads;
    snapshot.n_ctx = g_max_all_tokens;
    snapshot.max_all_tokens = g_max_all_tokens;
    snapshot.max_new_tokens = g_max_new_tokens;
    snapshot.loaded_config_json = g_loaded_config_json;
    snapshot.last_config_json = g_last_config_json;
    snapshot.last_error = g_last_error;

    return mca::mnn::format_mnn_stats_json(snapshot);
}

#if MCA_WITH_MNN_LLM
void update_visual_stats_locked() {
    std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
    g_visual_stats_json = stats_json_locked();
}
#endif

}  // namespace chat_session
}  // namespace mnn
}  // namespace mca
