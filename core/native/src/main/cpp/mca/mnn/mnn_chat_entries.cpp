// mca/mnn/mnn_chat_entries.cpp -- NativeMnnBridge_* JNI entries for the
// MNN chat session (T2B2-C1).  Entry bodies relocated byte-exact from
// mnn_native_engine.cpp; state/helpers come from the owner header.

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
#include "mca/mnn/mnn_chat_jni_guards.hpp"

using namespace mca::mnn::chat_session;
using mca::mnn::mnn_json_entrypoint;
using mca::mnn::mnn_token_ids_entrypoint;

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_initBackends(
        JNIEnv* env,
        jobject,
        jstring nativeLibDir) {
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_native_lib_dir = jstring_to_std(env, nativeLibDir);
#if MCA_WITH_MNN_LLM
    set_error("");
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "MNN LLM runner ready (CPU/OpenCL selected by runtime profile).");
#else
    set_error("MNN runner adapter loaded, but official MNN-LLM runtime is not linked in this build.");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_isRunnerReady(JNIEnv*, jobject) {
    return g_runner_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_loadModel(
        JNIEnv* env,
        jobject,
        jstring configPath,
        jstring paramsJson) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return kMnnInvalidState;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_original_model_path = normalize_mnn_model_path(jstring_to_std(env, configPath));
    g_model_path = resolve_mnn_config_path(g_original_model_path);
    g_mnn_backend_type = "cpu";
    g_vision_ready = false;
    g_visual_model_path.clear();
    reset_active_stop_markers_locked();
    const auto params = jstring_to_std(env, paramsJson);
#if MCA_WITH_MNN_LLM
    try {
        if (g_original_model_path.empty() || (!file_exists(g_original_model_path) && !directory_exists(g_original_model_path))) {
            set_error("MNN model path does not exist: " + g_original_model_path);
            return kMnnLoadFailed;
        }
        g_model_path = prepare_mnn_runtime_config(g_original_model_path);
        if (const auto incompatibility = incompatible_legacy_mnn_visual_graph_message(g_model_path);
                !incompatibility.empty()) {
            set_error(incompatibility);
            return kMnnLoadFailed;
        }
        if (g_model_path.empty() || !file_exists(g_model_path)) {
            set_error("MNN config path does not exist: " + g_model_path);
            return kMnnLoadFailed;
        }
        if (const auto preflightFailure = mnn_chat_bundle_preflight_message(g_model_path);
                !preflightFailure.empty()) {
            set_error("MNN bundle preflight failed: " + preflightFailure);
            return kMnnLoadFailed;
        }
        const auto preparedConfig = read_json_file_or_empty(g_model_path);
        if (preparedConfig.is_object() &&
                preparedConfig.value("is_visual", false) &&
                !g_vision_ready) {
            set_error(
                    "MNN model bundle declares a visual component but is missing the visual model file "
                    "(visual.mnn). Load a complete multimodal package before loading this model.");
            return kMnnLoadFailed;
        }
        g_model_stop_markers = mnn_model_stop_markers(g_model_path);
        g_active_stop_markers = g_model_stop_markers;
        destroy_llm_locked();
        g_llm = MNN::Transformer::Llm::createLLM(g_model_path);
        if (g_llm == nullptr) {
            set_error("MNN createLLM failed for config: " + g_model_path);
            return kMnnLoadFailed;
        }
        const auto config = build_mnn_config(g_model_path, params, true);
        g_mnn_backend_type = config.value("backend_type", std::string("cpu"));
        g_max_all_tokens = std::max(1, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_loaded_config_json = config.dump();
        g_last_config_json = g_loaded_config_json;
        g_llm->set_config(g_last_config_json);
        const bool ok = g_llm->load();
        if (!ok) {
            const std::string mnnLog = mnn_context_log_snapshot(g_llm->getContext());
            set_error(
                    "MNN load() failed for backend '" + g_mnn_backend_type +
                    "'. Check package components, memory headroom, and backend compatibility." +
                    (mnnLog.empty() ? std::string() : " MNN log: " + mnnLog));
            g_vision_ready = false;
            destroy_llm_locked();
            return kMnnLoadFailed;
        }
        const auto mergedModelConfig = json::parse(g_llm->dump_config(), nullptr, false);
        if (g_vision_ready &&
                (!mergedModelConfig.is_object() || !mergedModelConfig.value("is_visual", false))) {
            set_error(
                    "MNN visual component exists, but the loaded runtime did not enable the Omni visual executor.");
            g_vision_ready = false;
            destroy_llm_locked();
            return kMnnLoadFailed;
        }
        g_mnn_model_type = mergedModelConfig.is_object()
                ? mergedModelConfig.value("model_type", std::string())
                : std::string();
        g_loaded = true;
        g_loaded_at_ms = now_ms();
        g_load_generation += 1;
        g_mnn_request_lifecycle.onModelLoaded();
        set_error("");
        return 0;
    } catch (const std::exception& e) {
        set_error(std::string("MNN loadModel exception: ") + e.what());
        g_vision_ready = false;
        destroy_llm_locked();
        return kMnnLoadFailed;
    } catch (...) {
        set_error("MNN loadModel exception: unknown native error");
        g_vision_ready = false;
        destroy_llm_locked();
        return kMnnLoadFailed;
    }
#else
    set_error("MNN model package is valid, but this APK has not linked the official MNN-LLM CPU executor yet.");
    return kMnnRunnerUnavailable;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_unloadModel(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_model_path.clear();
    g_original_model_path.clear();
    g_visual_model_path.clear();
    g_vision_ready = false;
    reset_active_stop_markers_locked();
    g_loaded_config_json = "{}";
    g_last_config_json = "{}";
#if MCA_WITH_MNN_LLM
    destroy_llm_locked();
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_beginCompletion(
        JNIEnv* env,
        jobject,
        jstring messagesJson,
        jstring paramsJson) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return kMnnInvalidState;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    // A new request owns a fresh decoder boundary even if validation or setup
    // fails before MNN starts producing tokens.
    g_pending_chunk.clear();
    g_pending_utf8_tail.clear();
    g_prompt_ends_inside_reasoning = false;
    g_mnn_prefill_completed_tokens.store(0, std::memory_order_release);
    g_mnn_prefill_total_tokens.store(0, std::memory_order_release);
#if MCA_WITH_MNN_LLM
    if (!g_loaded || g_llm == nullptr) {
        set_error("MNN beginCompletion requested before a model is loaded.");
        return kMnnInvalidState;
    }
    bool requestHasMedia = false;
    try {
        // A caller may start a replacement request after receiving the final
        // visible chunk but before asking for the terminal null chunk. Settle
        // that transaction first so its partial KV can never leak forward.
        if (g_mnn_prompt_cache.request_active) {
            if (g_generation_active && !g_stop_requested && context_finished_locked()) {
                g_generation_active = false;
                g_generation_stop_reason = generation_stop_reason_locked();
                settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            } else {
                rollback_mnn_text_prompt_cache_locked("superseded", true);
            }
        }
        const auto params = jstring_to_std(env, paramsJson);
        const auto parsed_params = json::parse(params, nullptr, false);
        configure_mnn_debug_trace_locked(parsed_params);
        auto parsed = parse_chat_messages(jstring_to_std(env, messagesJson));
        requestHasMedia = parsed.hasMediaInputs();
        auto config = build_mnn_config(g_model_path, params, false);
        const auto loaded_config = json::parse(g_loaded_config_json, nullptr, false);
        if (loaded_config.is_discarded() || !loaded_config.is_object()) {
            set_error("MNN loaded configuration snapshot is unavailable; reload the active committed profile.");
            return kMnnLoadSignatureMismatch;
        }
        const auto changed_load_fields = mnn_load_signature_differences(loaded_config, config);
        if (!changed_load_fields.empty()) {
            std::ostringstream detail;
            for (size_t index = 0; index < changed_load_fields.size(); ++index) {
                if (index > 0) detail << ',';
                detail << changed_load_fields[index];
            }
            set_error("MNN completion load signature mismatch; reload required. changed_fields=" + detail.str());
            return kMnnLoadSignatureMismatch;
        }
        // Prompt/KV reuse is a text-only capability. A visual request uses the
        // official multimodal response path and must not inherit stale text KV,
        // even when the loaded package itself is capable of vision.
        const bool textOnlyRequest = !parsed.hasMediaInputs();
        config["reuse_kv"] = textOnlyRequest;
        config["prompt_cache"] = textOnlyRequest;
        mca::mnn::applyProductMnnMultimodalRequestPolicy(
                config,
                parsed.hasMediaInputs(),
                opt_int(config, "chunk", 0));
        configure_mnn_stop_markers_locked(parsed_params);
        g_max_new_tokens = std::max(1, config.value("max_new_tokens", 512));
        g_max_all_tokens = std::max(1, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_last_config_json = config.dump();
        g_generated_steps = 0;
        g_stop_requested = false;
        g_stop_signal.store(false, std::memory_order_release);
        g_generation_active = true;
        g_eop_seen = false;
        g_streamed_bytes = 0;
        g_generation_stop_reason = "running";
        g_generation_started_at_ms = now_ms();
        g_first_chunk_at_ms = 0;
        g_sync_stepping = true;
        const auto visualStream = textOnlyRequest ? std::shared_ptr<VisualStreamState>{}
                : std::make_shared<VisualStreamState>();
        g_stream_buffer = std::make_unique<MnnStreamBuffer>(visualStream);
        g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
        // response()/generate_init() owns context cleanup. The product bridge
        // must not call Llm::reset() here: a controlled MNN 3.5 differential
        // reproduced the Qwen3.5 vision failure by adding only that reset.
        const bool resetBeforeRequest = g_mnn_request_lifecycle.beginRequest();
        if (resetBeforeRequest) {
            g_llm->reset();
        }
        if (textOnlyRequest) {
            prepare_mnn_text_prompt_cache_locked(parsed.messages);
        } else {
            mark_mnn_prompt_cache_disabled_locked(
                    true,
                    "multimodal_request_requires_fresh_visual_prefill");
        }
        g_llm->set_config(g_last_config_json);
        if (!textOnlyRequest) {
            auto visualContent = prepare_mnn_visual_response(parsed);
            set_error("");
            g_sync_stepping = false;
            {
                std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
                g_visual_stats_json = mca::mnn::make_deferred_visual_stats_json(
                        stats_json_locked(),
                        g_generation_sequence + 1);
            }
            visualStream->filter.reset(g_active_stop_markers);
            std::atomic_store(&g_visual_stream, visualStream);
            g_visual_producer_running.store(true, std::memory_order_release);
            try {
                g_visual_producer = std::thread([
                        parsed = std::move(parsed), visualContent = std::move(visualContent), visualStream]() mutable {
                    std::string producerError;
                    {
                        std::lock_guard<std::mutex> model_lock(g_mnn_mutex);
                        try {
                            if (!g_stop_signal.load(std::memory_order_acquire)) {
                                begin_mnn_response(parsed, &visualContent);
                            } else {
                                visualStream->queue.cancel();
                            }
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            g_generation_stop_reason = g_stop_requested
                                    ? "stop_requested"
                                    : visualStream->queue.stoppedAfterMarker()
                                            ? "stop_marker"
                                            : generation_stop_reason_locked();
                            mark_mnn_prompt_cache_disabled_locked(
                                    true, "multimodal_completed_cache_cleared");
                            if (!g_stop_requested && !visualStream->queue.stoppedAfterMarker() &&
                                (g_generation_stop_reason == "mnn_internal_error" ||
                                 g_generation_stop_reason == "mnn_timeout" ||
                                 g_generation_stop_reason == "mnn_user_cancel" ||
                                 g_generation_stop_reason == "mnn_not_loaded")) {
                                producerError = "MNN visual decode failed: " + g_generation_stop_reason;
                            }
                        } catch (const std::exception& e) {
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            if (g_stop_requested || visualStream->queue.stoppedAfterMarker()) {
                                g_generation_stop_reason = g_stop_requested ? "stop_requested" : "stop_marker";
                            } else {
                                producerError = std::string("MNN visual generation failed: ") + e.what();
                                g_generation_stop_reason = "generate_failed";
                            }
                            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
                        } catch (...) {
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            if (g_stop_requested || visualStream->queue.stoppedAfterMarker()) {
                                g_generation_stop_reason = g_stop_requested ? "stop_requested" : "stop_marker";
                            } else {
                                producerError = "MNN visual generation failed: unknown native error";
                                g_generation_stop_reason = "generate_failed";
                            }
                            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
                        }
                        if (!producerError.empty()) set_error(producerError);
                        update_visual_stats_locked();
                    }
                    g_visual_producer_running.store(false, std::memory_order_release);
                    visualStream->queue.finish(std::move(producerError));
                });
            } catch (...) {
                g_visual_producer_running.store(false, std::memory_order_release);
                std::atomic_store(&g_visual_stream, std::shared_ptr<VisualStreamState>{});
                throw;
            }
            ++g_generation_sequence;
            return 0;
        }
        bool completedSynchronously = false;
        try {
            completedSynchronously = begin_mnn_response(parsed);
        } catch (const std::exception& firstError) {
            // A stale MNN prompt/KV transaction can make the first prefill
            // fail with the generic -203 bridge code.  Text requests are
            // retryable because no visual executor state is involved: clear
            // only the cached transaction, reset the decoder, and perform one
            // clean prefill.  Visual requests remain fail-closed and are not
            // retried here because resetting Omni can invalidate image VARPs.
            if (!textOnlyRequest || g_llm == nullptr) {
                throw;
            }
            const std::string firstMessage = firstError.what();
            rollback_mnn_text_prompt_cache_locked("prefill_retry_after_cache_error", true);
            g_llm->reset();
            g_pending_chunk.clear();
            g_pending_utf8_tail.clear();
            g_stream_buffer = std::make_unique<MnnStreamBuffer>();
            g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
            g_llm->set_config(g_last_config_json);
            try {
                completedSynchronously = begin_mnn_response(parsed);
            } catch (const std::exception& retryError) {
                throw std::runtime_error(
                        "MNN text prefill failed after one clean KV retry. first=" +
                        firstMessage + "; retry=" + retryError.what());
            }
        }
        g_sync_stepping = !completedSynchronously;
        if (completedSynchronously) {
            // Leave the buffered native output untouched here. The first
            // generateNextChunk() call owns protocol/EOS filtering and drains
            // it exactly once; pre-filtering would feed the visible text back
            // through an already-stopped filter and drop the whole answer.
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
            if (requestHasMedia) {
                // response(string) has already performed the visual request;
                // invalidate any prior text transcript without calling reset()
                // on the visual executor in this lifecycle boundary.
                mark_mnn_prompt_cache_disabled_locked(
                        true,
                        "multimodal_completed_cache_cleared");
            } else {
                settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            }
        } else {
            if (textOnlyRequest) {
                std::string prefillError;
                if (!capture_mnn_text_prefill_locked(prefillError)) {
                    throw std::runtime_error(prefillError);
                }
            }
            filter_mnn_stop_markers_locked(false);
            restore_stepping_status_if_needed_locked();
        }
        set_error("");
        // No failing begin path remains after this point.
        ++g_generation_sequence;
        return 0;
    } catch (const std::exception& e) {
        g_generation_active = false;
        const std::string detail = e.what();
        if (requestHasMedia) {
            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
        } else {
            rollback_mnn_text_prompt_cache_locked("begin_failed", true);
        }
        g_generation_stop_reason = detail.find("mnn_multimodal_prefill_failed") != std::string::npos
                ? "prefill_failed"
                : "begin_failed";
        const std::string backend = g_mnn_backend_type.empty() ? "cpu" : g_mnn_backend_type;
        const std::string contextLog = mnn_context_log_snapshot(
                g_llm == nullptr ? nullptr : g_llm->getContext());
        set_error(
                "MNN beginCompletion exception (backend=" + backend + "): " + detail +
                (contextLog.empty() ? std::string() : " MNN log: " + contextLog));
        return kMnnBeginFailed;
    } catch (...) {
        g_generation_active = false;
        if (requestHasMedia) {
            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
        } else {
            rollback_mnn_text_prompt_cache_locked("begin_failed", true);
        }
        g_generation_stop_reason = "begin_failed";
        set_error(
                "MNN beginCompletion exception (backend=" +
                (g_mnn_backend_type.empty() ? std::string("cpu") : g_mnn_backend_type) +
                "): unknown native error");
        return kMnnBeginFailed;
    }
#else
    set_error("MNN generation requested before the official MNN-LLM executor is linked.");
    return kMnnRunnerUnavailable;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_generateNextChunk(JNIEnv* env, jobject) {
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) {
        std::lock_guard<std::mutex> stream_lock(stream->consumer_mutex);
        while (true) {
            auto result = stream->queue.read();
            if (result.kind == mca::mnn::VisualStreamQueue::ReadKind::Cancelled) {
                jclass exception_class = env->FindClass("java/lang/IllegalStateException");
                if (exception_class != nullptr) {
                    env->ThrowNew(exception_class, "MNN visual generation was cancelled.");
                    env->DeleteLocalRef(exception_class);
                }
                return nullptr;
            }
            if (result.kind == mca::mnn::VisualStreamQueue::ReadKind::Failed) {
                jclass exception_class = env->FindClass("java/lang/IllegalStateException");
                if (exception_class != nullptr) {
                    env->ThrowNew(exception_class, result.text.c_str());
                    env->DeleteLocalRef(exception_class);
                }
                return nullptr;
            }
            const bool flush = result.kind == mca::mnn::VisualStreamQueue::ReadKind::Finished;
            auto filtered = mca::mnn::filter_stream_protocol(
                    stream->filter, std::move(result.text), flush);
            if (filtered.stopped) {
                stream->queue.stopAfterMarker();
            }
            std::string bytes = std::move(stream->utf8_tail);
            bytes += filtered.visible;
            auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
            stream->utf8_tail = std::move(decoded.incomplete_tail);
            if (!decoded.utf16.empty()) {
                auto* result = utf16_to_jstring(env, decoded.utf16);
                if (result != nullptr || env->ExceptionCheck()) return result;
            }
            if (!flush) continue;
            return nullptr;
        }
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // requestStop() publishes g_stop_signal without taking g_mnn_mutex. Consume
    // it at the next safe native boundary before protocol filtering or another
    // decoder step can commit output/cache state.
    if (g_stop_signal.exchange(false, std::memory_order_acq_rel)) {
        g_stop_requested = true;
        g_generation_active = false;
        g_generation_stop_reason = "stop_requested";
        rollback_mnn_text_prompt_cache_locked("stop_requested", false);
        return nullptr;
    }
    if (!g_pending_chunk.empty() ||
            !g_pending_utf8_tail.empty() ||
            g_stream_protocol_filter.has_pending_protocol()) {
        const bool flush = !g_generation_active || g_stop_requested || g_eop_seen;
        filter_mnn_stop_markers_locked(flush);
        bool emitted = false;
        auto* out = take_pending_utf8_locked(
                env,
                flush,
                &emitted);
        if (emitted || env->ExceptionCheck()) return out;
    }
    if (!g_generation_active || g_llm == nullptr || g_stop_requested) {
        return nullptr;
    }
    restore_stepping_status_if_needed_locked();
    if (g_generated_steps >= g_max_new_tokens || context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
        filter_mnn_stop_markers_locked(true);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, true, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        return nullptr;
    }
    try {
        g_llm->generate(1);
        // A stop may arrive while MNN is inside generate(1). Do not publish the
        // token that was produced after the cancellation request; mark the
        // transaction rolled back on this same owning thread.
        if (g_stop_signal.exchange(false, std::memory_order_acq_rel)) {
            g_stop_requested = true;
            g_generation_active = false;
            g_generation_stop_reason = "stop_requested";
            rollback_mnn_text_prompt_cache_locked("stop_requested", false);
            return nullptr;
        }
        if (g_llm->getContext() != nullptr &&
                g_llm->getContext()->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) {
            g_generation_active = false;
            const auto reason = g_llm->getContext()->logits_error.empty()
                    ? std::string("mnn_internal_error") : g_llm->getContext()->logits_error;
            g_generation_stop_reason = reason;
            rollback_mnn_text_prompt_cache_locked(reason, true);
            set_error("MNN generation aborted: " + reason + ".");
            return nullptr;
        }
        g_generated_steps += 1;
        capture_mnn_debug_generated_token_ids_locked();
        filter_mnn_stop_markers_locked(false);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, g_eop_seen, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        if (context_finished_locked()) {
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
            settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            filter_mnn_stop_markers_locked(true);
            if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
                bool emitted = false;
                auto* out = take_pending_utf8_locked(env, true, &emitted);
                if (emitted || env->ExceptionCheck()) return out;
            }
            return nullptr;
        }
        return utf8_to_jstring(env, "");
    } catch (const std::exception& e) {
        g_generation_active = false;
        rollback_mnn_text_prompt_cache_locked("generate_failed", true);
        set_error(std::string("MNN generateNextChunk exception: ") + e.what());
        return nullptr;
    } catch (...) {
        g_generation_active = false;
        rollback_mnn_text_prompt_cache_locked("generate_failed", true);
        set_error("MNN generateNextChunk exception: unknown native error");
        return nullptr;
    }
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStop(JNIEnv*, jobject) {
    // Deliberately lock-free: generateNextChunk() may own g_mnn_mutex while
    // MNN is executing a long native step. The owning thread consumes this
    // signal and performs the cache/protocol rollback at a safe boundary.
    g_stop_signal.store(true, std::memory_order_release);
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) stream->queue.cancel();
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStopIfActive(JNIEnv*, jobject) {
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) {
        if (!g_visual_producer_running.load(std::memory_order_acquire)) return JNI_FALSE;
        g_stop_signal.store(true, std::memory_order_release);
        stream->queue.cancel();
        return JNI_TRUE;
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // A final visible chunk can be returned before generateNextChunk() reaches
    // its normal terminal bookkeeping. Reconcile the underlying MNN context
    // here so a stale generationActive flag cannot turn natural completion
    // into a successful cancellation proof.
    if (g_generation_active && context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
    }
#endif
    if (!g_generation_active) return JNI_FALSE;
    g_stop_requested = true;
    g_generation_active = false;
    g_generation_stop_reason = "stop_requested";
 #if MCA_WITH_MNN_LLM
    rollback_mnn_text_prompt_cache_locked("stop_requested", false);
 #endif
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_isGenerationRunning(JNIEnv*, jobject) {
#if MCA_WITH_MNN_LLM
    return g_visual_producer_running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
    return mnn_json_entrypoint(
            env,
            "getRuntimeStatsJson",
            "{\"loaded\":false,\"runnerReady\":false,\"error\":\"native exception\"}",
            [env] {
#if MCA_WITH_MNN_LLM
                if (g_visual_producer_running.load(std::memory_order_acquire)) {
                    std::unique_lock<std::mutex> model_lock(g_mnn_mutex, std::try_to_lock);
                    if (!model_lock.owns_lock()) {
                        std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
                        return utf8_to_jstring(env, g_visual_stats_json);
                    }
                    return utf8_to_jstring(env, stats_json_locked());
                }
#endif
                std::lock_guard<std::mutex> lock(g_mnn_mutex);
                const auto stats = stats_json_locked();
                return utf8_to_jstring(env, stats);
            });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getPrefillProgressJson(JNIEnv* env, jobject) {
    // Deliberately lock-free: MNN beginCompletion holds g_mnn_mutex while
    // prefill runs, and the poll getter must observe the atomics independently.
    return mnn_json_entrypoint(
            env,
            "getPrefillProgressJson",
            "{\"completedTokens\":0,\"totalTokens\":0,\"error\":\"native exception\"}",
            [env] {
                const int total = std::max(0, g_mnn_prefill_total_tokens.load(std::memory_order_acquire));
                const int completed = std::clamp(
                        g_mnn_prefill_completed_tokens.load(std::memory_order_acquire),
                        0,
                        total);
                nlohmann::json out = {
                        {"completedTokens", completed},
                        {"totalTokens", total},
                };
                return utf8_to_jstring(env, out.dump());
            });
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_resetPrefillProgress(JNIEnv*, jobject) {
    // Deliberately lock-free; same reasoning as the getter.
    g_mnn_prefill_completed_tokens.store(0, std::memory_order_release);
    g_mnn_prefill_total_tokens.store(0, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_shutdown(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    g_loaded = false;
    configure_mnn_debug_trace_locked(json::object());
    reset_generation_state_locked();
    g_model_path.clear();
    g_original_model_path.clear();
    g_visual_model_path.clear();
    g_vision_ready = false;
    reset_active_stop_markers_locked();
#if MCA_WITH_MNN_LLM
    destroy_llm_locked();
#endif
}
