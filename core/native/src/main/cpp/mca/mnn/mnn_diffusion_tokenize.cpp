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

using json = nlohmann::json;
using mca::mnn::jstring_to_std;
using mca::mnn::mca_jni_exception_pending;
using mca::mnn::utf16_to_jstring;
using mca::mnn::utf8_to_jstring;
using mca::mnn::mnn_json_entrypoint;
using mca::mnn::mnn_token_ids_entrypoint;
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
#if MCA_WITH_MNN_DIFFUSION
#if defined(MCA_WITH_TOKENIZERS_CPP) && MCA_WITH_TOKENIZERS_CPP
using mca::mnn::encode_prompt_token_ids_with_weights_from_json;
#endif
#endif

#include "mca/mnn/mnn_diffusion_internal.hpp"

using namespace mca::mnn::diffusion_detail;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_isRunnerReady(JNIEnv*, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_inspectBundle(
        JNIEnv* env,
        jobject,
        jstring bundleRoot) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto out = inspect_mnn_diffusion_bundle(root).dump();
#else
    const auto out = json({
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_runUnetSmoke(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring backendMode) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto mode = opt_string(json({{"backendMode", jstring_to_std(env, backendMode)}}), "backendMode", "cpu");
    const auto out = run_unet_interpreter_smoke(root, mode).dump();
#else
    const auto out = json({
        {"ok", false},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_resetImageCancellation(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    g_mnn_diffusion_cancel_requested = false;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_tokenizePromptTokenIds(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt) {
#if MCA_WITH_MNN_DIFFUSION
    return tokenize_prompt_token_ids(
            env,
            normalize_mnn_model_path(jstring_to_std(env, bundleRoot)),
            jstring_to_std(env, prompt),
            "",
            49406,
            49407,
            77);
#else
    return env == nullptr ? nullptr : env->NewIntArray(0);
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_tokenizePromptTokenIdsWithConfig(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring tokenizerRoot,
        jint bosId,
        jint eosId,
        jint maxTokens) {
#if MCA_WITH_MNN_DIFFUSION
    return tokenize_prompt_token_ids(
            env,
            normalize_mnn_model_path(jstring_to_std(env, bundleRoot)),
            jstring_to_std(env, prompt),
            normalize_mnn_model_path(jstring_to_std(env, tokenizerRoot)),
            bosId,
            eosId,
            maxTokens);
#else
    return env == nullptr ? nullptr : env->NewIntArray(0);
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_tokenizePromptTokenIdsFromJson(
        JNIEnv* env,
        jobject,
        jstring tokenizerJsonPath,
        jstring prompt,
        jstring negativePrompt,
        jint bosId,
        jint eosId,
        jint padId,
        jint maxTokens) {
    return mnn_token_ids_entrypoint(
            env,
            "tokenizePromptTokenIdsFromJson",
            [&]() -> jintArray {
                if (env == nullptr || maxTokens <= 0 || maxTokens > 4096) {
                    return env == nullptr ? nullptr : env->NewIntArray(0);
                }
                const std::string promptText = jstring_to_std(env, prompt);
                const std::string negativePromptText = jstring_to_std(env, negativePrompt);
                if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
                        promptText,
                        negativePromptText)) {
                    return env->NewIntArray(0);
                }
                mca::image::ClipTokenizerConfig config;
                config.bos_id = static_cast<int32_t>(bosId);
                config.eos_id = static_cast<int32_t>(eosId);
                config.pad_id = static_cast<int32_t>(padId);
                config.max_length = static_cast<int>(maxTokens);
                mca::image::ClipTokenPair pair;
                std::string error;
                if (!mca::image::tokenize_clip_pair_from_json(
                        normalize_mnn_model_path(jstring_to_std(env, tokenizerJsonPath)),
                        promptText,
                        negativePromptText,
                        config,
                        &pair,
                        &error)) {
                    __android_log_print(
                            ANDROID_LOG_ERROR,
                            "MCA-MNN",
                            "Standard image tokenizer failed: %s",
                            error.c_str());
                    return env->NewIntArray(0);
                }
                const auto ids = pair.negative_then_positive();
                const size_t expectedCount = static_cast<size_t>(maxTokens) * 2U;
                if (ids.size() != expectedCount || expectedCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
                    return env->NewIntArray(0);
                }
                const auto result = env->NewIntArray(static_cast<jsize>(ids.size()));
                if (result == nullptr) return nullptr;
                std::vector<jint> values(ids.begin(), ids.end());
                env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(result);
                    return nullptr;
                }
                return result;
            });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_measurePromptTokens(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring tokenizerBackend,
        jstring tokenizerJsonPath,
        jstring prompt,
        jint bosId,
        jint eosId,
        jint padId,
        jint maxTokens,
        jboolean promptWeightingEnabled) {
    if (env == nullptr || maxTokens < 2 || maxTokens > 4096) {
        return env == nullptr ? nullptr : utf8_to_jstring(
                env,
                json({{"ok", false}, {"error", "Prompt tokenizer arguments are invalid."}}).dump());
    }
    const std::string backend = jstring_to_std(env, tokenizerBackend);
    const std::string promptText = jstring_to_std(env, prompt);
    json result = {
        {"ok", false},
        {"backend", backend},
    };
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt(
            promptText)) {
        result["errorCode"] = "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE";
        result["error"] =
            "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax.";
        return utf8_to_jstring(env, result.dump());
    }
    if (backend == "TOKENIZERS_CPP") {
        mca::image::ClipTokenizerConfig config;
        config.bos_id = static_cast<int32_t>(bosId);
        config.eos_id = static_cast<int32_t>(eosId);
        config.pad_id = static_cast<int32_t>(padId);
        config.max_length = static_cast<int>(maxTokens);
        config.enable_prompt_weighting = promptWeightingEnabled == JNI_TRUE;
        mca::image::ClipPromptTokenMeasurement measurement;
        std::string error;
        if (!mca::image::measure_clip_prompt_from_json(
                normalize_mnn_model_path(jstring_to_std(env, tokenizerJsonPath)),
                promptText,
                config,
                &measurement,
                &error)) {
            result["error"] = error;
        } else {
            result = {
                {"ok", true},
                {"backend", backend},
                {"count", measurement.token_count},
                {"maxTokens", measurement.max_length},
            };
            if (measurement.overflow_byte_offset.has_value()) {
                result["overflowByteOffset"] = *measurement.overflow_byte_offset;
            }
        }
    } else if (backend == "MNN_MTOK") {
#if MCA_WITH_MNN_DIFFUSION
        if (promptWeightingEnabled == JNI_TRUE) {
            // The production MNN_MTOK contract rejects weighted prompts because
            // this graph consumes token ids only. Do not present a literal
            // punctuation count as an exact executable count.
            result["error"] = "MNN_MTOK does not support exact prompt-weight measurement.";
            return utf8_to_jstring(env, result.dump());
        }
        try {
            const std::string root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
            // Production MNN_MTOK execution requires this artifact before it loads
            // the same pair tokenizer used below. Do not measure a root that the
            // generation contract would reject before tokenizer initialization.
            if (root.empty() || !file_exists(root + "/tokenizer.mtok")) {
                result["error"] = "MNN prompt tokenizer is unavailable.";
            } else {
                MNN::DIFFUSION::McaMtokPromptMeasurementTokenizer tokenizer(
                        static_cast<int>(bosId),
                        static_cast<int>(eosId));
                if (!tokenizer.load(root)) {
                    result["error"] = "MNN prompt tokenizer is unavailable.";
                } else {
                    std::vector<int> ids;
                    if (!tokenizer.encodeConditionalPromptForMeasurement(promptText, &ids)) {
                        result["error"] =
                                "MNN prompt tokenizer has no exact single-prompt measurement adapter.";
                    } else {
                        result = {
                            {"ok", true},
                            {"backend", backend},
                            {"count", ids.size()},
                            {"maxTokens", maxTokens},
                        };
                    }
                }
            }
        } catch (const std::exception&) {
            result["error"] = "MNN prompt tokenizer is unavailable.";
        } catch (...) {
            // Never permit a malformed local tokenizer asset to escape through JNI.
            result["error"] = "MNN prompt tokenizer is unavailable.";
        }
#else
        result["error"] = "MNN prompt tokenizer is not packaged in this build.";
#endif
    } else {
        result["error"] = "The selected image tokenizer has no exact measurement adapter.";
    }
    return utf8_to_jstring(env, result.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodePromptTokenIdsWithWeightsFromJson(
        JNIEnv* env,
        jobject,
        jstring tokenizerJsonPath,
        jstring prompt,
        jstring negativePrompt,
        jint bosId,
        jint eosId,
        jint padId,
        jint maxTokens,
        jstring promptToEncoderClosureSha256,
        jstring outputPath) {
#if MCA_WITH_MNN_DIFFUSION && MCA_WITH_TOKENIZERS_CPP
    const auto result = encode_prompt_token_ids_with_weights_from_json(
            normalize_mnn_model_path(jstring_to_std(env, tokenizerJsonPath)),
            jstring_to_std(env, prompt),
            jstring_to_std(env, negativePrompt),
            static_cast<int32_t>(bosId),
            static_cast<int32_t>(eosId),
            static_cast<int32_t>(padId),
            static_cast<int>(maxTokens),
            jstring_to_std(env, promptToEncoderClosureSha256),
            normalize_mnn_model_path(jstring_to_std(env, outputPath)));
#else
    (void)tokenizerJsonPath;
    (void)prompt;
    (void)negativePrompt;
    (void)bosId;
    (void)eosId;
    (void)padId;
    (void)maxTokens;
    (void)promptToEncoderClosureSha256;
    (void)outputPath;
    const auto result = json({
        {"ok", false},
        {"error", "MNN-Diffusion tokenizer support is not linked in this APK."}
    });
#endif
    return utf8_to_jstring(env, result.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodeSd15PromptEmbeddings(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring negativePrompt,
        jstring outputPath,
        jstring backendMode,
        jint threads,
        jstring conditioningOrder,
        jboolean promptWeightingEnabled,
        jstring textualInversionJson) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    // The JNI slot is unchanged; its local name now reflects the active branch
    // contract. Dtype remains derived from token_emb.bin's exact size.
    const auto activeConditioningOrder = jstring_to_std(env, conditioningOrder);
    const bool useCfg = activeConditioningOrder != "positive_only";
    const auto out = encode_sd15_prompt_embeddings_to_file(
            root,
            jstring_to_std(env, prompt),
            jstring_to_std(env, negativePrompt),
            jstring_to_std(env, outputPath),
            jstring_to_std(env, backendMode),
            std::max(1, static_cast<int>(threads)),
            useCfg,
            promptWeightingEnabled == JNI_TRUE,
            jstring_to_std(env, textualInversionJson)).dump();
#else
    (void)bundleRoot;
    (void)prompt;
    (void)negativePrompt;
    (void)outputPath;
    (void)backendMode;
    (void)threads;
    (void)conditioningOrder;
    (void)promptWeightingEnabled;
    (void)textualInversionJson;
    const auto out = json({
        {"ok", false},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodeSdxlPromptConditioning(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring prompt,
        jstring negativePrompt,
        jstring outputPath,
        jint width,
        jint height,
        jstring backendMode,
        jint threads,
        jboolean useCfg,
        jboolean promptWeightingEnabled,
        jstring textualInversionJson) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto out = encode_sdxl_prompt_conditioning_to_file(
            root,
            jstring_to_std(env, prompt),
            jstring_to_std(env, negativePrompt),
            jstring_to_std(env, outputPath),
            std::max(256, static_cast<int>(width)),
            std::max(256, static_cast<int>(height)),
            jstring_to_std(env, backendMode),
            std::max(1, static_cast<int>(threads)),
            useCfg == JNI_TRUE,
            promptWeightingEnabled == JNI_TRUE,
            jstring_to_std(env, textualInversionJson)).dump();
#else
    (void)bundleRoot;
    (void)prompt;
    (void)negativePrompt;
    (void)outputPath;
    (void)width;
    (void)height;
    (void)backendMode;
    (void)threads;
    (void)useCfg;
    (void)promptWeightingEnabled;
    (void)textualInversionJson;
    const auto out = json({
        {"ok", false},
        {"error", "MNN-Diffusion native runner is not linked in this APK."},
        {"format", "sdxl_qnn_conditioning"}
    }).dump();
#endif
    return utf8_to_jstring(env, out);
}

namespace mca {
namespace mnn {
namespace diffusion_detail {

#if MCA_WITH_MNN_DIFFUSION
bool has_community_clip_bundle(
        const std::string& root,
        const std::string& graphPath) {
    return mca::mnn::has_community_clip_bundle(root, graphPath);
}
#endif

#if MCA_WITH_MNN_DIFFUSION
std::string resolve_mnn_tokenizer_json_path(const std::string& root) {
    const std::array<std::string, 2> candidates = {
        root + "/tokenizer/tokenizer.json",
        root + "/tokenizer.json"
    };
    for (const auto& candidate : candidates) {
        if (file_exists(candidate)) return candidate;
    }
    return "";
}
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
        std::string& error) {
    ids.clear();
    executedTokenPair = mca::image::ClipTokenPair{};
    hasExecutedTokenPair = false;
    if (contract.tokenizer_backend == "TOKENIZERS_CPP") {
        const auto tokenizerPath = resolve_mnn_tokenizer_json_path(root);
        if (tokenizerPath.empty()) {
            error = "tokenizerBackend=TOKENIZERS_CPP requires tokenizer/tokenizer.json or tokenizer.json.";
            return false;
        }
        mca::image::ClipTokenizerConfig tokenizerConfig;
        tokenizerConfig.bos_id = contract.tokenizer_bos_id;
        tokenizerConfig.eos_id = contract.tokenizer_eos_id;
        tokenizerConfig.pad_id = contract.tokenizer_pad_id;
        tokenizerConfig.max_length = contract.tokenizer_max_length;
        // Parse attention syntax even when this graph cannot execute it. That keeps ordinary
        // prompts on the normal path while allowing a non-unity request to fail explicitly
        // instead of becoming literal punctuation in the token-id graph.
        tokenizerConfig.enable_prompt_weighting = true;
        mca::image::ClipTokenPair pair;
        if (!mca::image::tokenize_clip_pair_from_json(
                tokenizerPath,
                prompt,
                contract.negative_prompt,
                tokenizerConfig,
                &pair,
                &error)) {
            return false;
        }
        if (!contract.prompt_weighting_supported &&
            (pair.negative.weighting_applied || pair.positive.weighting_applied)) {
            error = "This MNN text_encoder.mnn accepts int32 token IDs and cannot apply "
                    "non-unity prompt weights before the Transformer.";
            return false;
        }
        const auto standardIds = contract.use_cfg
                ? pair.negative_then_positive()
                : pair.positive.ids;
        ids.assign(standardIds.begin(), standardIds.end());
        executedTokenPair = pair;
        hasExecutedTokenPair = true;
        evidence.tokenizer_backend = "TOKENIZERS_CPP";
    } else if (contract.tokenizer_backend == "MNN_MTOK") {
        if (!file_exists(root + "/tokenizer.mtok")) {
            error = "tokenizerBackend=MNN_MTOK requires tokenizer.mtok in the MNN bundle root.";
            return false;
        }
        MNN::DIFFUSION::MtokTokenizer tokenizer(
                MNN::DIFFUSION::MtokTokenizer::Style::kSingle,
                contract.tokenizer_bos_id,
                contract.tokenizer_eos_id);
        if (!tokenizer.load(root)) {
            error = "Failed to load the explicitly selected MNN_MTOK tokenizer.";
            return false;
        }
        const auto positiveIds = tokenizer.encode(prompt, contract.tokenizer_max_length);
        if (positiveIds.size() != static_cast<size_t>(contract.tokenizer_max_length)) {
            error = "MNN_MTOK positive prompt did not produce tokenizerMaxLength token ids.";
            return false;
        }
        if (contract.use_cfg) {
            const auto negativeIds = tokenizer.encode(
                    contract.negative_prompt,
                    contract.tokenizer_max_length);
            if (negativeIds.size() != static_cast<size_t>(contract.tokenizer_max_length)) {
                error = "MNN_MTOK negative prompt did not produce tokenizerMaxLength token ids.";
                return false;
            }
            ids.reserve(negativeIds.size() + positiveIds.size());
            ids.insert(ids.end(), negativeIds.begin(), negativeIds.end());
        }
        ids.insert(ids.end(), positiveIds.begin(), positiveIds.end());
        evidence.tokenizer_backend = "MNN_MTOK";
    } else {
        error = "Unsupported MNN tokenizer backend after strict contract parsing.";
        return false;
    }
    if (ids.size() != contract.token_count) {
        error = "MNN tokenizer produced " + std::to_string(ids.size()) +
                " token ids; the resolved contract requires " +
                std::to_string(contract.token_count) + ".";
        return false;
    }
    evidence.token_count = ids.size();
    const std::vector<int> activeTokenIds = contract.use_cfg
            ? ids
            : std::vector<int>(
                    ids.end() - contract.tokenizer_max_length,
                    ids.end());
    evidence.prompt_weight_fingerprint = hasExecutedTokenPair
            ? (contract.use_cfg
                    ? executedTokenPair.weighting_fingerprint()
                    : executedTokenPair.positive.weighting_fingerprint)
            : mnn_token_id_fingerprint(activeTokenIds);
    if (evidence.prompt_weight_fingerprint.size() != 64U) {
        error = "Conditioning fingerprint could not be derived from executed token sequences.";
        return false;
    }
    if (contract.prompt_weighting_supported) {
        if (!hasExecutedTokenPair) {
            error = "Prompt weighting was enabled without executed TOKENIZERS_CPP sequences.";
            return false;
        }
        evidence.prompt_weighting_applied =
                (contract.use_cfg && executedTokenPair.negative.weighting_applied) ||
                executedTokenPair.positive.weighting_applied;
        evidence.positive_weighted_token_count =
                executedTokenPair.positive.weighted_token_count;
        evidence.negative_weighted_token_count = contract.use_cfg
                ? executedTokenPair.negative.weighted_token_count
                : 0U;
    }
    return true;
}
#endif

std::function<bool()> mnn_asset_cancel_callback() {
    return [] {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        return g_mnn_diffusion_cancel_requested;
    };
}

#if MCA_WITH_MNN_DIFFUSION
json run_unet_interpreter_smoke(const std::string& root, const std::string& backendMode) {
    std::vector<UnetSmokeProfile> profiles;
    const auto batches = mca::mnn::diffusionBatchCandidates(true);
    profiles.reserve(batches.count);
    for (size_t index = 0; index < batches.count; ++index) {
        const int batch = batches.values[index];
        profiles.push_back({
                batch == 2 ? "defer-inside-batch2" : "defer-inside-batch1",
                true,
                false,
                batch
        });
    }
    json attempts = json::array();
    json best = json::object();
    for (const auto& profile : profiles) {
        auto attempt = run_unet_interpreter_smoke_once(root, backendMode, profile);
        attempts.push_back(attempt);
        if (attempt.value("ok", false)) {
            attempt["attempts"] = attempts;
            return attempt;
        }
        // If every candidate fails, surface the final batch-1 compatibility
        // attempt while retaining all earlier attempts for diagnosis.
        best = attempt;
    }
    if (best.empty()) {
        best = json({{"ok", false}, {"error", "UNet smoke did not run any profile."}});
    }
    best["attempts"] = attempts;
    return best;
}
#endif

#if MCA_WITH_MNN_DIFFUSION
// Tokenizer-only bridge used by QNN Stable Diffusion bundles. QNN's
// text_encoder.bin consumes CLIP ids, while the MNN text encoder path can
// remain unchanged. The pair layout is [negative(77), positive(77)].
jintArray tokenize_prompt_token_ids(
        JNIEnv* env,
        const std::string& bundleRoot,
        const std::string& prompt,
        const std::string& tokenizerRoot,
        jint bosId,
        jint eosId,
        jint maxTokens) {
    return mnn_token_ids_entrypoint(
            env,
            "tokenizePromptTokenIds",
            [&]() -> jintArray {
                if (env == nullptr || maxTokens <= 0 || maxTokens > 4096) {
                    return env == nullptr ? nullptr : env->NewIntArray(0);
                }
                if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt(prompt)) {
                    return env->NewIntArray(0);
                }
                auto root = tokenizerRoot.empty() ? bundleRoot : tokenizerRoot;
                if (!file_exists(root + "/tokenizer.mtok") && file_exists(bundleRoot + "/tokenizer.mtok")) {
                    root = bundleRoot;
                }
                if (root.empty()) {
                    return env->NewIntArray(0);
                }
                MNN::DIFFUSION::MtokTokenizer tokenizer(
                        MNN::DIFFUSION::MtokTokenizer::Style::kPair,
                        static_cast<int>(bosId),
                        static_cast<int>(eosId));
                if (!tokenizer.load(root)) {
                    return env->NewIntArray(0);
                }
                const auto ids = tokenizer.encode(prompt, static_cast<int>(maxTokens));
                const size_t expectedCount = static_cast<size_t>(maxTokens) * 2U;
                if (ids.size() != expectedCount || expectedCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
                    return env->NewIntArray(0);
                }
                const auto result = env->NewIntArray(static_cast<jsize>(expectedCount));
                if (result == nullptr) {
                    return nullptr;
                }
                std::vector<jint> values;
                values.reserve(expectedCount);
                for (const int id : ids) {
                    values.push_back(static_cast<jint>(id));
                }
                env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(result);
                    return nullptr;
                }
                return result;
            });
}

using mca::mnn::write_u32_little_endian;

// Keep tokenizer.json on the same descriptor-pinned lifecycle as verified MNN graphs.
using MnnOpenedTokenizerJsonAsset = MnnOpenedTextEncoderAsset;

using mca::mnn::hash_opened_qnn_tokenizer_json_asset;

using mca::mnn::open_qnn_tokenizer_json_asset;

using mca::mnn::qnn_tokenizer_asset_failure;

using mca::mnn::hash_published_qnn_prompt_payload;

#if defined(MCA_WITH_TOKENIZERS_CPP) && MCA_WITH_TOKENIZERS_CPP
using mca::mnn::encode_prompt_token_ids_with_weights_from_json;
#endif
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
        std::string& error) {
    return mca::mnn::pndm_step(
            sample,
            ets,
            firstSample,
            modelOutput,
            index,
            timesteps,
            alphas,
            previous,
            error);
}
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
        const std::string& textualInversionJson) {
    return mca::mnn::encode_sd15_prompt_embeddings_to_file(
            root,
            prompt,
            negativePrompt,
            outputPath,
            backendMode,
            threads,
            useCfg,
            promptWeightingEnabled,
            textualInversionJson,
            &encode_community_clip_embeddings_to_file,
            [](const std::string& r, const std::vector<int>& ids,
               const std::string& bm, int th, FloatTensorData& emb, std::string& err) {
                return run_text_encoder_direct(r, ids, bm, th, emb, err);
            });
}
#endif

}  // namespace diffusion_detail
}  // namespace mnn
}  // namespace mca

