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

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_generate(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring paramsJson,
        jstring outputPath) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto params_raw = jstring_to_std(env, paramsJson);
    const auto output_path = jstring_to_std(env, outputPath);
    json params = json::parse(params_raw, nullptr, false);
    if (params.is_discarded() || !params.is_object()) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", "MNN image generation params must be a valid JSON object."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    std::string prompt;
    std::string family;
    std::string contractError;
    if (!mnn_contract_string(params, "prompt", prompt, contractError) ||
        !mnn_contract_string(params, "family", family, contractError)) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", contractError}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    const bool sana_family = is_sana_family(family);
    MnnSemanticExecutionContract executionContract;
    if (!sana_family &&
        !parse_mnn_semantic_execution_contract(params, executionContract, contractError)) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", contractError}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (!sana_family && executionContract.runner != "direct") {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
            {"field", "runner"},
            {"error", "runner=module cannot faithfully execute the resolved scheduler, tokenizer, CFG, and VAE contract; use runner=direct."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    auto backend_mode = sana_family
            ? opt_string(params, "backendMode", "cpu")
            : executionContract.backend_mode;
    const int requested_steps = opt_int(params, "steps", 8);
    const int steps = sana_family
            ? requested_steps
            : executionContract.scheduler.steps;
    const int seed = sana_family
            ? opt_int(params, "seed", opt_int(params, "randomSeed", -1))
            : static_cast<int>(executionContract.seed);
    const int width = sana_family
            ? opt_int(params, "width", 512)
            : executionContract.width;
    const int height = sana_family
            ? opt_int(params, "height", 512)
            : executionContract.height;
    const int threads = sana_family
            ? opt_int(params, "threads", 4)
            : executionContract.threads;
    const int memory_mode = sana_family
            ? opt_int(params, "memoryMode", 0)
            : executionContract.memory_mode;
    const bool use_cfg = sana_family
            ? opt_bool(params, "useCfg", false)
            : executionContract.use_cfg;
    const float cfg_scale = sana_family
            ? static_cast<float>(opt_double(params, "cfgScale", 4.5))
            : executionContract.cfg_scale;
    std::string negative_prompt = executionContract.negative_prompt;
    std::string task_mode;
    std::string input_image_path;
    std::string input_image_sha256;
    std::string mask_image_path;
    std::string control_image_path;
    int64_t batch_count_value = 0;
    if (!mnn_contract_string(params, "taskMode", task_mode, contractError) ||
        !optional_json_string(params, "inputImagePath", "", input_image_path, contractError) ||
        !optional_json_string(params, "inputImageSha256", "", input_image_sha256, contractError) ||
        !optional_json_string(params, "maskImagePath", "", mask_image_path, contractError) ||
        !optional_json_string(params, "controlImagePath", "", control_image_path, contractError) ||
        !mnn_contract_integer(params, "batchCount", batch_count_value, contractError) ||
        (sana_family &&
         !mnn_contract_string(params, "negativePrompt", negative_prompt, contractError, true))) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_INVALID"},
            {"error", contractError}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (batch_count_value != 1) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
            {"field", "batchCount"},
            {"error", "MNN-Diffusion currently produces exactly one image per native request."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    const int batch_count = static_cast<int>(batch_count_value);
    if (sana_family) {
        std::string canonicalBackend;
        if (!canonical_mnn_diffusion_backend(backend_mode, canonicalBackend, contractError)) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"field", "backendMode"},
                {"error", contractError}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        backend_mode = canonicalBackend;
        if (steps < 2 || steps > 50 ||
            width < 256 || width > 2048 || height < 256 || height > 2048 ||
            width % 32 != 0 || height % 32 != 0 ||
            threads < 1 || threads > 64 ||
            memory_mode < 0 || memory_mode > 2 ||
            seed < 0 || !std::isfinite(cfg_scale) || cfg_scale < 0.0f || cfg_scale > 30.0f) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"error", "MNN Sana generation fields are outside their exact supported ranges."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (task_mode != "text_to_image" && task_mode != "edit") {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
                {"field", "taskMode"},
                {"error", "MNN Sana supports text_to_image or edit only."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        const bool imageEdit = task_mode == "edit";
        if (imageEdit != (!input_image_path.empty() && !input_image_sha256.empty()) ||
            (!imageEdit && (!input_image_path.empty() || !input_image_sha256.empty())) ||
            !mask_image_path.empty() || !control_image_path.empty()) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"field", "inputImagePath,inputImageSha256,maskImagePath,controlImagePath"},
                {"error", "MNN Sana edit requires one input image with its SHA-256 and does not accept mask/control images."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (!use_cfg && !negative_prompt.empty()) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
                {"field", "negativePrompt"},
                {"error", "MNN Sana cannot apply a negative prompt when useCfg=false."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        bool positiveWeighted = false;
        bool negativeWeighted = false;
        if (!prompt_has_non_unity_attention_weight(
                prompt, positiveWeighted, contractError) ||
            (use_cfg && !prompt_has_non_unity_attention_weight(
                negative_prompt, negativeWeighted, contractError))) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"field", "prompt,negativePrompt"},
                {"error", contractError}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (positiveWeighted || negativeWeighted) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED"},
                {"field", "prompt,negativePrompt"},
                {"error", "MNN Sana cannot apply non-unity prompt weights before its Transformer."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        if (params.contains("strength") ||
            params.contains("vaeTiling") || params.contains("preview") ||
            params.contains("clipSkip")) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
                {"field", "strength,vaeTiling,preview,clipSkip"},
                {"error", "MNN Sana edit does not expose strength, tiling, preview, or clip-skip controls through its native API."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        json preflightEffective;
        if (!mnn_sana_native_effective_json(
                params,
                steps,
                steps,
                256,
                use_cfg ? 2 : 1,
                use_cfg ? "negative_then_positive" : "positive_only",
                0,
                0,
                0,
                "",
                "",
                false,
                preflightEffective,
                contractError)) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "EXECUTION_CONTRACT_INVALID"},
                {"error", contractError}
            }).dump();
            return utf8_to_jstring(env, out);
        }
    } else if (task_mode != "text_to_image" ||
               !input_image_path.empty() || !input_image_sha256.empty() || !mask_image_path.empty() ||
               !control_image_path.empty()) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "EXECUTION_CONTRACT_UNSUPPORTED"},
            {"field", "taskMode"},
            {"error", "The current MNN SD1.5 direct graph package has no VAE encoder; only text_to_image is executable."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negative_prompt)) {
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"field", "prompt,negativePrompt"},
            {"error", "Native multilingual image prompts must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."}
        }).dump();
        return utf8_to_jstring(env, out);
    }
    if (mnn_prompt_contains_non_ascii(prompt) ||
        mnn_prompt_contains_non_ascii(negative_prompt)) {
        const bool has_verified_text_encoder =
            !sana_family && executionContract.text_encoder_asset.declared &&
                !executionContract.language_proof_sha256.empty();
        if (!has_verified_text_encoder) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"errorCode", "TEXT_ENCODER_LANGUAGE_PROOF_REQUIRED"},
                {"field", "nativeTextEncoderEvidence,languageProofSha256"},
                {"error", "Non-ASCII image prompts require complete text-encoder asset evidence and a signed semantic proof."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        if (g_mnn_diffusion_generating) {
            const auto out = json({
                {"ok", false},
                {"backend", "mnn_diffusion"},
                {"error", "Another MNN-Diffusion generation is already running."}
            }).dump();
            return utf8_to_jstring(env, out);
        }
        g_mnn_diffusion_generating = true;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_started_at_ms = now_ms();
        const int64_t process_unique_sequence =
                (g_mnn_diffusion_started_at_ms << 16) |
                (static_cast<int64_t>(::getpid()) & 0xffffLL);
        g_mnn_diffusion_generation_sequence = std::max<int64_t>(
                process_unique_sequence,
                g_mnn_diffusion_generation_sequence + static_cast<int64_t>(1));
        g_mnn_diffusion_finished_at_ms = 0;
        g_mnn_diffusion_seconds_per_step = 0.0;
        g_mnn_diffusion_bundle_root = root;
        g_mnn_diffusion_output_path = output_path;
        g_mnn_diffusion_backend = backend_mode == "opencl" || backend_mode == "gpu" ? "opencl" : "cpu";
        g_mnn_diffusion_last_error.clear();
        set_mnn_diffusion_progress_locked(
                "loading",
                sana_family
                        ? "Loading MNN Sana text encoder and diffusion engine."
                        : "Loading MNN-Diffusion Stable Diffusion 1.5 engine.",
                0,
                steps,
                width,
                height,
                threads);
    }

    auto fail = [&](const std::string& message) {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        g_mnn_diffusion_generating = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_finished_at_ms = now_ms();
        g_mnn_diffusion_last_error = message;
        set_mnn_diffusion_progress_locked("failed", message, g_mnn_diffusion_step, steps, width, height, threads);
        return json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"error", message}
        }).dump();
    };

    if (prompt.empty()) {
        const auto out = fail("Prompt is empty.");
        return utf8_to_jstring(env, out);
    }
    if (output_path.empty()) {
        const auto out = fail("Output path is empty.");
        return utf8_to_jstring(env, out);
    }
    const auto missing = mnn_diffusion_missing_component(
            root,
            family,
            sana_family ? nullptr : &executionContract.text_encoder_asset);
    if (!missing.empty()) {
        const auto out = fail(missing);
        return utf8_to_jstring(env, out);
    }
    if (sana_family && task_mode == "edit" &&
        (!file_exists(root + "/vae_encoder.mnn") ||
         !file_exists(root + "/vae_encoder.mnn.weight"))) {
        const auto out = fail("MNN Sana edit bundle is missing vae_encoder.mnn or its weight file.");
        return utf8_to_jstring(env, out);
    }

    try {
        {
            std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
            g_mnn_diffusion_loaded = true;
            set_mnn_diffusion_progress_locked(
                    "generating",
                    "Generating image with MNN-Diffusion.",
                    0,
                    steps,
                    width,
                    height,
                    threads);
        }
        auto progress_callback = [steps, width, height, threads](int progress) {
                    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                    if (g_mnn_diffusion_cancel_requested) {
                        set_mnn_diffusion_progress_locked(
                                "cancelled",
                                "Cancelling MNN-Diffusion generation.",
                                g_mnn_diffusion_step,
                                steps,
                                width,
                                height,
                                threads);
                        throw MnnDiffusionCancelled();
                    }
                    const int pct = std::max(0, std::min(100, progress));
                    const int step = std::max(0, std::min(steps, static_cast<int>(std::round(pct * steps / 100.0))));
                    set_mnn_diffusion_progress_locked(
                            pct >= 100 ? "saving" : "generating",
                            pct >= 100 ? "Saving generated image." : "Generating image with MNN-Diffusion.",
                            step,
                            steps,
                            width,
                            height,
                            threads);
                };
        json result;
        MnnNativeExecutionEvidence executionEvidence;
        int sanaCompletedSteps = 0;
        int sanaGraphInvocationCount = 0;
        int sanaConditioningSequenceLength = 0;
        int sanaConditioningBatchSize = 0;
        int sanaTokenizerInputSequenceLength = 0;
        int sanaTokenizerInputBatchSize = 0;
        int sanaTokenizerNonPaddingTokenCount = 0;
        int sanaInputImageExecutionCount = 0;
        std::string sanaConditioningOrder;
        std::string sanaTokenizerInputOrder;
        std::string sanaConditioningArtifactSha256;
        std::string sanaExecutedInputImageSha256;
        if (sana_family) {
            mca::MnnSanaOptions options;
            options.bundle_root = root;
            options.prompt = prompt;
            options.negative_prompt = negative_prompt;
            options.output_path = output_path;
            options.task_mode = task_mode;
            options.input_image_path = input_image_path;
            options.input_image_sha256 = input_image_sha256;
            options.backend_mode = backend_mode;
            options.memory_mode = memory_mode;
            options.width = width;
            options.height = height;
            options.steps = steps;
            options.seed = seed;
            options.threads = threads;
            options.use_cfg = use_cfg;
            options.cfg_scale = cfg_scale;
            mca::MnnSanaSession session(
                    std::move(options),
                    progress_callback,
                    [steps, width, height, threads](const std::string& phase, const std::string& message) {
                        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                        set_mnn_diffusion_progress_locked(
                                phase,
                                message,
                                g_mnn_diffusion_step,
                                steps,
                                width,
                                height,
                                threads);
                    },
                    []() {
                        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                        return g_mnn_diffusion_cancel_requested;
                    });
            const bool sana_ok = session.run();
            sanaCompletedSteps = session.completed_steps();
            sanaGraphInvocationCount = session.graph_invocation_count();
            sanaConditioningSequenceLength = session.conditioning_sequence_length();
            sanaConditioningBatchSize = session.conditioning_batch_size();
            sanaConditioningOrder = session.conditioning_order();
            sanaTokenizerInputSequenceLength =
                    session.tokenizer_input_sequence_length();
            sanaTokenizerInputBatchSize = session.tokenizer_input_batch_size();
            sanaTokenizerNonPaddingTokenCount =
                    session.tokenizer_non_padding_token_count();
            sanaTokenizerInputOrder = session.tokenizer_input_order();
            sanaConditioningArtifactSha256 =
                    session.conditioning_artifact_sha256();
            sanaInputImageExecutionCount = session.input_image_execution_count();
            sanaExecutedInputImageSha256 = session.executed_input_image_sha256();
            result = json({
                {"ok", sana_ok},
                {"path", output_path},
                {"mimeType", "image/png"}
            });
        } else {
            result = run_mnn_sd15_interpreter_direct(
                    root,
                    prompt,
                    output_path,
                    executionContract,
                    executionEvidence,
                    progress_callback);
        }
        const bool ok = result.value("ok", false);
        if (!ok) {
            if (result.value("cancelled", false) ||
                result.value("error", std::string()) == "cancelled") {
                result["ok"] = false;
                result["cancelled"] = true;
                result["error"] = "cancelled";
                result["backend"] = "mnn_diffusion";
                return utf8_to_jstring(env, result.dump());
            }
            auto failed = json::parse(
                    fail(result.value("error", "MNN-Diffusion direct interpreter failed.")),
                    nullptr,
                    false);
            if (!failed.is_object()) failed = json::object();
            failed["ok"] = false;
            failed["backend"] = "mnn_diffusion";
            if (result.contains("errorCode")) failed["errorCode"] = result["errorCode"];
            if (result.contains("field")) failed["field"] = result["field"];
            return utf8_to_jstring(env, failed.dump());
        }
        if (!ok || !file_exists(output_path)) {
            const auto out = fail("MNN-Diffusion did not produce a valid output image.");
            return utf8_to_jstring(env, out);
        }
        MnnPublishedImageEvidence output_evidence;
        std::string output_evidence_error;
        if (!collect_mnn_published_image_evidence(
                output_path,
                output_evidence,
                output_evidence_error)) {
            ::unlink(output_path.c_str());
            const auto out = fail(output_evidence_error);
            return utf8_to_jstring(env, out);
        }
        {
            std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
            g_mnn_diffusion_generating = false;
            g_mnn_diffusion_loaded = false;
            g_mnn_diffusion_finished_at_ms = now_ms();
            set_mnn_diffusion_progress_locked("completed", "Image generation completed.", steps, steps, width, height, threads);
        }
        if (sana_family) {
            json nativeEffective;
            std::string evidenceError;
            if (!mnn_sana_native_effective_json(
                    params,
                    sanaCompletedSteps,
                    sanaGraphInvocationCount,
                    sanaConditioningSequenceLength,
                    sanaConditioningBatchSize,
                    sanaConditioningOrder,
                    sanaTokenizerInputSequenceLength,
                    sanaTokenizerInputBatchSize,
                    sanaTokenizerNonPaddingTokenCount,
                    sanaTokenizerInputOrder,
                    sanaConditioningArtifactSha256,
                    true,
                    nativeEffective,
                    evidenceError)) {
                const auto out = fail(evidenceError);
                return utf8_to_jstring(env, out);
            }
            const std::string nativePromptExecutionSha256 =
                    mca::image::image_prompt_execution_sha256(prompt, negative_prompt);
            if (nativePromptExecutionSha256.size() != 64U) {
                const auto out = fail(
                        "The prompts consumed by native MNN Sana conditioning could not be framed and hashed.");
                return utf8_to_jstring(env, out);
            }
            nativeEffective["nativePromptExecutionSha256"] =
                    nativePromptExecutionSha256;
            nativeEffective["nativePromptBindingStage"] =
                    "conditioning_consumed";
            nativeEffective["taskMode"] = task_mode;
            nativeEffective["inputImagePath"] = input_image_path;
            nativeEffective["maskImagePath"] = "";
            nativeEffective["controlImagePath"] = "";
            nativeEffective["inputImageExecutionCount"] =
                    sanaInputImageExecutionCount;
            nativeEffective["inputImageSha256"] = sanaExecutedInputImageSha256;
            nativeEffective["maskImageExecutionCount"] = 0;
            nativeEffective["controlImageExecutionCount"] = 0;
            nativeEffective["outputPath"] = output_path;
            nativeEffective["outputBytes"] = output_evidence.bytes;
            nativeEffective["outputSha256"] = output_evidence.sha256;
            nativeEffective["batchCount"] = batch_count;
            json out = json({
                {"ok", true},
                {"nativeExecution", true},
                {"executionStage", "semantic_generation_passed"},
                {"backend", "mnn_diffusion"},
                {"family", "SANA"},
                {"backendMode", backend_mode},
                {"runner", "sana_varp"},
                {"path", output_path},
                {"outputPath", output_path},
                {"outputBytes", output_evidence.bytes},
                {"outputSha256", output_evidence.sha256},
                {"mimeType", "image/png"},
                {"steps", steps},
                {"width", width},
                {"height", height},
                {"seed", seed},
                {"cfgScale", cfg_scale},
                {"useCfg", use_cfg},
                {"negativePromptSpecified", params.contains("negativePrompt")},
                {"sampleMethod", "flow_match"},
                {"memoryMode", memory_mode},
                {"batchCount", batch_count},
                {"taskMode", task_mode},
                {"inputImagePath", input_image_path},
                {"maskImagePath", ""},
                {"controlImagePath", ""},
                {"inputImageExecutionCount", sanaInputImageExecutionCount},
                {"inputImageSha256", sanaExecutedInputImageSha256},
                {"maskImageExecutionCount", 0},
                {"controlImageExecutionCount", 0},
                {"graphInvocationCount", sanaGraphInvocationCount},
                {"logicalBranchExecutionCount", nativeEffective["unetExecutionCount"]},
                {"conditioningSequenceLength", sanaConditioningSequenceLength},
                {"conditioningBatchSize", sanaConditioningBatchSize},
                {"conditioningOrder", sanaConditioningOrder},
                {"tokenizerInputSequenceLength", sanaTokenizerInputSequenceLength},
                {"tokenizerInputBatchSize", sanaTokenizerInputBatchSize},
                {"tokenizerNonPaddingTokenCount", sanaTokenizerNonPaddingTokenCount},
                {"tokenizerInputOrder", sanaTokenizerInputOrder},
                {"conditioningArtifactSha256", sanaConditioningArtifactSha256},
                {"nativePromptExecutionSha256", nativePromptExecutionSha256},
                {"nativePromptBindingStage", "conditioning_consumed"},
                {"nativeEffective", nativeEffective},
                {"outputs", json::array({json({
                    {"index", 0},
                    {"path", output_path},
                    {"outputBytes", output_evidence.bytes},
                    {"outputSha256", output_evidence.sha256},
                    {"mimeType", "image/png"},
                    {"seed", seed}
                })})},
                {"nativeGenerationSequence", g_mnn_diffusion_generation_sequence},
                {"nativeStartedAtMs", g_mnn_diffusion_started_at_ms}
            });
            return utf8_to_jstring(env, out.dump());
        }
        auto nativeEffective = mnn_native_effective_json(
                executionContract,
                executionEvidence);
        nativeEffective["outputPath"] = output_path;
        nativeEffective["outputBytes"] = output_evidence.bytes;
        nativeEffective["outputSha256"] = output_evidence.sha256;
        nativeEffective["batchCount"] = batch_count;
        json out = nativeEffective;
        out["ok"] = true;
        out["nativeExecution"] = true;
        out["executionStage"] = "semantic_generation_passed";
        out["backend"] = "mnn_diffusion";
        out["family"] = "SD15";
        out["backendMode"] = executionContract.backend_mode;
        out["runner"] = "direct";
        out["path"] = output_path;
        out["outputPath"] = output_path;
        out["outputBytes"] = output_evidence.bytes;
        out["outputSha256"] = output_evidence.sha256;
        out["mimeType"] = "image/png";
        out["taskMode"] = "text_to_image";
        out["inputImagePath"] = "";
        out["maskImagePath"] = "";
        out["controlImagePath"] = "";
        out["inputImageExecutionCount"] = 0;
        out["maskImageExecutionCount"] = 0;
        out["controlImageExecutionCount"] = 0;
        out["outputs"] = json::array({json({
            {"index", 0},
            {"path", output_path},
            {"outputBytes", output_evidence.bytes},
            {"outputSha256", output_evidence.sha256},
            {"mimeType", "image/png"},
            {"seed", executionContract.seed}
        })});
        out["nativeEffective"] = nativeEffective;
        out["timesteps"] = executionEvidence.timesteps;
        out["sigmas"] = executionEvidence.sigmas;
        out["initNoiseSigma"] = executionEvidence.init_noise_sigma;
        out["graphInvocationCount"] = executionEvidence.graph_invocation_count;
        out["logicalBranchExecutionCount"] = executionEvidence.unet_execution_count;
        out["negativePromptSpecified"] = !executionContract.negative_prompt.empty();
        out["nativeGenerationSequence"] = g_mnn_diffusion_generation_sequence;
        out["nativeStartedAtMs"] = g_mnn_diffusion_started_at_ms;
        out["sampleMethod"] = mnn_scheduler_product_name(
                executionContract.scheduler.config.algorithm);
        out["memoryMode"] = executionContract.memory_mode;
        if (result.contains("debug")) out["debug"] = result["debug"];
        const auto serialized = out.dump();
        return utf8_to_jstring(env, serialized);
    } catch (const mca::MnnSanaCancelled& e) {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        g_mnn_diffusion_generating = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_finished_at_ms = now_ms();
        g_mnn_diffusion_last_error = e.what();
        set_mnn_diffusion_progress_locked("cancelled", e.what(), g_mnn_diffusion_step, steps, width, height, threads);
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"family", "SANA"},
            {"cancelled", true},
            {"error", e.what()}
        }).dump();
        return utf8_to_jstring(env, out);
    } catch (const MnnDiffusionCancelled& e) {
        std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
        g_mnn_diffusion_generating = false;
        g_mnn_diffusion_loaded = false;
        g_mnn_diffusion_finished_at_ms = now_ms();
        g_mnn_diffusion_last_error = e.what();
        set_mnn_diffusion_progress_locked("cancelled", e.what(), g_mnn_diffusion_step, steps, width, height, threads);
        const auto out = json({
            {"ok", false},
            {"backend", "mnn_diffusion"},
            {"cancelled", true},
            {"error", e.what()}
        }).dump();
        return utf8_to_jstring(env, out);
    } catch (const std::exception& e) {
        const auto out = fail(std::string("MNN-Diffusion exception: ") + e.what());
        return utf8_to_jstring(env, out);
    } catch (...) {
        const auto out = fail("MNN-Diffusion exception: unknown native error.");
        return utf8_to_jstring(env, out);
    }
#else
    const auto out = json({
        {"ok", false},
        {"backend", "mnn_diffusion"},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
    return utf8_to_jstring(env, out);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_getProgress(JNIEnv* env, jobject) {
    return mnn_json_entrypoint(
            env,
            "getProgress",
            "{\"phase\":\"error\",\"message\":\"native exception\",\"step\":0,\"steps\":0,"
            "\"elapsedMs\":0,\"secondsPerStep\":0.0,\"cancelRequested\":false}",
            [env] {
#if MCA_WITH_MNN_DIFFUSION
                std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                const auto out = mnn_diffusion_progress_json_locked().dump();
#else
                const auto out = json({
                    {"phase", "unavailable"},
                    {"message", "MNN-Diffusion native runner is not linked in this APK."},
                    {"step", 0},
                    {"steps", 0},
                    {"elapsedMs", 0},
                    {"secondsPerStep", 0.0},
                    {"cancelRequested", false}
                }).dump();
#endif
                return utf8_to_jstring(env, out);
            });
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_cancel(JNIEnv*, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    g_mnn_diffusion_cancel_requested = true;
    if (g_mnn_diffusion_generating) {
        g_mnn_diffusion_phase = "cancelling";
        g_mnn_diffusion_message = "Cancelling MNN-Diffusion generation.";
    }
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
    return mnn_json_entrypoint(
            env,
            "getRuntimeStatsJson",
            "{\"backend\":\"mnn_diffusion\",\"runnerReady\":false,\"loaded\":false,"
            "\"generating\":false,\"lastError\":\"native exception\"}",
            [env] {
#if MCA_WITH_MNN_DIFFUSION
                std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                const auto out = json({
                    {"backend", "mnn_diffusion"},
                    {"runnerReady", true},
                    {"loaded", g_mnn_diffusion_loaded},
                    {"generating", g_mnn_diffusion_generating},
                    {"bundleRoot", g_mnn_diffusion_bundle_root},
                    {"outputPath", g_mnn_diffusion_output_path},
                    {"backendMode", g_mnn_diffusion_backend},
                    {"backendDevices", json::array({"cpu", "opencl-experimental"})},
                    {"progress", mnn_diffusion_progress_json_locked()},
                    {"lastError", g_mnn_diffusion_last_error}
                }).dump();
#else
                const auto out = json({
                    {"backend", "mnn_diffusion"},
                    {"runnerReady", false},
                    {"loaded", false},
                    {"lastError", "MNN-Diffusion native runner is not linked in this APK."}
                }).dump();
#endif
                return utf8_to_jstring(env, out);
            });
}

#if MCA_WITH_MNN_DIFFUSION
namespace MNN {
namespace DIFFUSION {

Diffusion::Diffusion(std::string modelPath, DiffusionModelType modelType, MNNForwardType backendType, int memoryMode)
        : mModelPath(std::move(modelPath)),
          mModelType(modelType),
          mMemoryMode(memoryMode),
          mBackendType(backendType) {
}

Diffusion::~Diffusion() {
    mModules.clear();
    runtime_manager_.reset();
}

bool Diffusion::runVideo(
        const std::string& prompt,
        const std::string& outputDir,
        int width,
        int height,
        int frames,
        int steps,
        int seed,
        float cfgScale,
        std::function<void(int)> progressCallback) {
    (void)prompt;
    (void)outputDir;
    (void)width;
    (void)height;
    (void)frames;
    (void)steps;
    (void)seed;
    (void)cfgScale;
    (void)progressCallback;
    return false;
}

Diffusion* Diffusion::createDiffusion(
        std::string modelPath,
        DiffusionModelType modelType,
        MNNForwardType backendType,
        int memoryMode) {
    if (modelType == SANA_DIFFUSION) {
        return new SanaDiffusion(std::move(modelPath), modelType, backendType, memoryMode);
    }
    return new StableDiffusion(std::move(modelPath), modelType, backendType, memoryMode);
}

}  // namespace DIFFUSION
}  // namespace MNN
#endif

