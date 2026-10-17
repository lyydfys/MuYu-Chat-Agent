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

namespace mca {
namespace mnn {
namespace diffusion_detail {

#if MCA_WITH_MNN_DIFFUSION
bool run_text_encoder_direct(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        const MnnOpenedTextEncoderBinding* textEncoderBinding) {
    if (ids.size() != 2U * 77U) {
        return run_text_encoder_direct_once(
                root,
                ids,
                backendMode,
                threads,
                embeddings,
                error,
                textEncoderBinding);
    }

    std::string batchedError;
    if (run_text_encoder_direct_once(
            root,
            ids,
            backendMode,
            threads,
            embeddings,
            batchedError,
            textEncoderBinding)) {
        return true;
    }

    const std::vector<int> negativeIds(ids.begin(), ids.begin() + 77);
    const std::vector<int> positiveIds(ids.begin() + 77, ids.end());
    FloatTensorData negative;
    FloatTensorData positive;
    std::string negativeError;
    if (!run_text_encoder_direct_once(
            root,
            negativeIds,
            backendMode,
            threads,
            negative,
            negativeError,
            textEncoderBinding)) {
        error = "text_encoder batch-2 execution failed: " + batchedError +
                " Sequential negative branch also failed: " + negativeError;
        return false;
    }
    std::string positiveError;
    if (!run_text_encoder_direct_once(
            root,
            positiveIds,
            backendMode,
            threads,
            positive,
            positiveError,
            textEncoderBinding)) {
        error = "text_encoder batch-2 execution failed: " + batchedError +
                " Sequential positive branch failed: " + positiveError;
        return false;
    }

    embeddings.shape = {2, 77, 768};
    embeddings.values.clear();
    embeddings.values.reserve(negative.values.size() + positive.values.size());
    embeddings.values.insert(
            embeddings.values.end(), negative.values.begin(), negative.values.end());
    embeddings.values.insert(
            embeddings.values.end(), positive.values.begin(), positive.values.end());
    error.clear();
    return true;
}
#endif

#if MCA_WITH_MNN_DIFFUSION
class DirectMnnUnetSession {
public:
    ~DirectMnnUnetSession() {
        close();
    }

    int batch() const {
        return batch_;
    }

    bool initialize(
            const std::string& root,
            const std::string& backendMode,
            int threads,
            int batch,
            std::string& error) {
        close();
        if (batch != 1 && batch != 2) {
            error = "UNet direct batch must be 1 (conditional) or 2 (CFG pair).";
            return false;
        }
        batch_ = batch;
        const auto path = root + "/unet.mnn";
        interpreter_.reset(MNN::Interpreter::createFromFile(path.c_str()));
        if (!interpreter_) {
            error = "Failed to create UNet interpreter.";
            return false;
        }
        weight_path_ = path + ".weight";
        if (file_exists(weight_path_)) {
            interpreter_->setExternalFile(weight_path_.c_str());
        }
        configure_direct_interpreter(interpreter_.get(), false, false, false);
        MNN::ScheduleConfig config;
        MNN::BackendConfig backendConfig;
        std::string configError;
        if (!configure_mnn_session(config, backendConfig, backendMode, threads, configError, true)) {
            error = "UNet session configuration failed: " + configError;
            close();
            return false;
        }
        session_ = interpreter_->createSession(config);
        if (session_ == nullptr) {
            error = "Failed to create UNet session.";
            close();
            return false;
        }
        sample_input_ = interpreter_->getSessionInput(session_, "sample");
        timestep_input_ = interpreter_->getSessionInput(session_, "timestep");
        encoder_input_ = interpreter_->getSessionInput(session_, "encoder_hidden_states");
        if (sample_input_ == nullptr || timestep_input_ == nullptr || encoder_input_ == nullptr) {
            error = "UNet input tensor not found before resize.";
            close();
            return false;
        }
        interpreter_->resizeTensor(sample_input_, {batch_, 4, 64, 64});
        interpreter_->resizeTensor(timestep_input_, {1});
        interpreter_->resizeTensor(encoder_input_, {batch_, 77, 768});
        interpreter_->resizeSession(session_, 1);
        int resizeStatus = -1;
        interpreter_->getSessionInfo(session_, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
        sample_input_ = interpreter_->getSessionInput(session_, "sample");
        timestep_input_ = interpreter_->getSessionInput(session_, "timestep");
        encoder_input_ = interpreter_->getSessionInput(session_, "encoder_hidden_states");
        if (resizeStatus != 0 || sample_input_ == nullptr || timestep_input_ == nullptr || encoder_input_ == nullptr) {
            error = "UNet resizeSession failed with status " + std::to_string(resizeStatus) + ".";
            close();
            return false;
        }
        const std::vector<int> expectedSampleShape = {batch_, 4, 64, 64};
        const std::vector<int> expectedEncoderShape = {batch_, 77, 768};
        const std::vector<int> expectedTimestepShape = {1};
        const auto sampleShape = mnn_tensor_shape(sample_input_);
        const auto encoderShape = mnn_tensor_shape(encoder_input_);
        const auto timestepShape = mnn_tensor_shape(timestep_input_);
        if (sampleShape != expectedSampleShape ||
            encoderShape != expectedEncoderShape ||
            timestepShape != expectedTimestepShape) {
            error = "UNet input shape mismatch after resize: sample expected " +
                    tensor_shape_string(expectedSampleShape) + ", got " +
                    tensor_shape_string(sampleShape) + "; encoder_hidden_states expected " +
                    tensor_shape_string(expectedEncoderShape) + ", got " +
                    tensor_shape_string(encoderShape) + "; timestep expected " +
                    tensor_shape_string(expectedTimestepShape) + ", got " +
                    tensor_shape_string(timestepShape) + ".";
            close();
            return false;
        }
        return true;
    }

    bool run(
            const std::vector<float>& sample,
            double timestep,
            const FloatTensorData& embeddings,
            FloatTensorData& noise,
            std::string& error) {
        constexpr int kLatentElements = 4 * 64 * 64;
        if (session_ == nullptr || interpreter_ == nullptr) {
            error = "UNet session is not initialized.";
            return false;
        }
        if (!std::isfinite(timestep) || timestep < 0.0 || timestep >= 1000.0) {
            error = "UNet timestep must be finite and in [0, 999].";
            return false;
        }
        if (sample.size() != static_cast<size_t>(batch_ * kLatentElements)) {
            error = "UNet sample does not match the initialized conditional/CFG batch.";
            return false;
        }
        if (!validate_finite_float_vector(sample, "UNet sample", error) ||
            !validate_float_tensor_contract(
                embeddings,
                {batch_, 77, 768},
                "UNet encoder_hidden_states",
                error)) {
            return false;
        }
        if (!copy_vector_to_tensor<float>(sample_input_, sample, error) ||
            !copy_mnn_timestep_to_tensor(timestep_input_, timestep, error) ||
            !copy_vector_to_tensor<float>(encoder_input_, embeddings.values, error)) {
            return false;
        }
        const auto code = interpreter_->runSession(session_);
        if (code != MNN::NO_ERROR) {
            error = "UNet runSession failed: " + std::to_string(static_cast<int>(code)) +
                    " (" + mnn_error_code_name(code) + ")";
            return false;
        }
        auto* tensor = interpreter_->getSessionOutput(session_, "out_sample");
        if (!copy_tensor_to_float_vector(tensor, noise, error)) {
            error = "UNet output copy failed: " + error;
            return false;
        }
        if (noise.shape == std::vector<int>({batch_, 64, 64, 4})) {
            noise.values = nhwc_to_nchw(noise.values, batch_, 64, 64, 4);
            noise.shape = {batch_, 4, 64, 64};
        }
        return validate_float_tensor_contract(
                noise,
                {batch_, 4, 64, 64},
                "UNet output",
                error);
    }

private:
    void close() {
        if (interpreter_ && session_ != nullptr) {
            interpreter_->releaseSession(session_);
        }
        session_ = nullptr;
        sample_input_ = nullptr;
        timestep_input_ = nullptr;
        encoder_input_ = nullptr;
        interpreter_.reset();
        weight_path_.clear();
        batch_ = 0;
    }

    std::unique_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* sample_input_ = nullptr;
    MNN::Tensor* timestep_input_ = nullptr;
    MNN::Tensor* encoder_input_ = nullptr;
    std::string weight_path_;
    int batch_ = 0;
};
#endif

#if MCA_WITH_MNN_DIFFUSION
json run_mnn_sd15_interpreter_direct(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        const MnnSemanticExecutionContract& contract,
        MnnNativeExecutionEvidence& evidence,
        std::function<void(int)> progressCallback) {
    std::string error;
    if (progressCallback) {
        progressCallback(0);
    }
    MnnOpenedTextEncoderBinding textEncoderBinding;
    if (!open_mnn_text_encoder_binding(
            root,
            contract.text_encoder_asset,
            textEncoderBinding,
            evidence.text_encoder_asset,
            error)) {
        return mnn_text_encoder_asset_failure(error);
    }
    std::vector<int> ids;
    mca::image::ClipTokenPair executedTokenPair;
    bool hasExecutedTokenPair = false;
    if (!tokenize_mnn_sd15_prompt(
            root,
            prompt,
            contract,
            ids,
            executedTokenPair,
            hasExecutedTokenPair,
            evidence,
            error)) {
        json failure = {{"ok", false}, {"error", error}};
        if (error.find("cannot apply non-unity prompt weights before the Transformer") !=
            std::string::npos) {
            failure["errorCode"] = "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED";
        }
        return failure;
    }
    mca::image::textual_inversion::Selection textualInversionSelection;
    mca::image::textual_inversion::Audit textualInversionAudit;
    mca::image::ClipConditionedPair conditionedPair;
    const bool textualInversionRequested = !contract.textual_inversion_json.empty();
    const bool useCommunityClip = mnn_contract_uses_community_clip(contract);
    const std::string selectedTextEncoderPath = textEncoderBinding.active
            ? textEncoderBinding.graph.canonical_path
            : join_path(root, "clip_v2.mnn");
    const std::string clipWeightPath = textEncoderBinding.active
            ? textEncoderBinding.weight.canonical_path
            : join_path(root, "clip_v2.mnn.weight");
    const bool useClipWeight = textEncoderBinding.active
            ? textEncoderBinding.weight.fd >= 0
            : nonempty_regular_file_exists(clipWeightPath);
    const std::string pinnedClipWeightPath =
            !textEncoderBinding.active && useClipWeight ? clipWeightPath : "";
    mca::image::execution_assets::Binding executionAssets;
    if (textualInversionRequested && !prepare_mnn_execution_assets(
            contract.textual_inversion_json, "MNN_DIFFUSION", executionAssets, error)) {
        return mnn_execution_asset_failure(error);
    }
    if (textualInversionRequested) {
        std::vector<std::string> requiredAssetPaths = {
            selectedTextEncoderPath,
            join_path(root, "tokenizer.json"),
            join_path(root, "token_emb.bin"),
            join_path(root, "pos_emb.bin"),
        };
        if (useClipWeight) {
            requiredAssetPaths.push_back(clipWeightPath);
        }
        if (!mnn_execution_assets_match(executionAssets, requiredAssetPaths, error)) {
            return mnn_execution_asset_failure(error);
        }
    }
    if (textualInversionRequested) {
        if (!useCommunityClip || !has_community_clip_bundle(root, selectedTextEncoderPath)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_GRAPH_INPUT_UNSUPPORTED"},
                {"error", "MNN textual inversion requires the declared host-writable input_embedding text encoder."}
            });
        }
        mca::image::ClipTokenizerConfig tokenizerConfig;
        tokenizerConfig.bos_id = contract.tokenizer_bos_id;
        tokenizerConfig.eos_id = contract.tokenizer_eos_id;
        tokenizerConfig.pad_id = contract.tokenizer_pad_id;
        tokenizerConfig.max_length = contract.tokenizer_max_length;
        tokenizerConfig.enable_prompt_weighting = contract.prompt_weighting_supported;
        if (!mca::image::textual_inversion::load_selection(
                contract.textual_inversion_json,
                "MNN_DIFFUSION",
                false,
                &textualInversionSelection,
                &textualInversionAudit,
                &error) ||
            !mnn_selection_matches_execution_assets(
                textualInversionSelection, executionAssets, error) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                contract.negative_prompt,
                tokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, false),
                &conditionedPair,
                &error)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_CONDITIONING_FAILED"},
                {"error", error}
            });
        }
        mca::image::textual_inversion::record_conditioned_pair(
            conditionedPair, false, &textualInversionAudit);
        executedTokenPair.negative = conditionedPair.negative.tokens;
        executedTokenPair.positive = conditionedPair.positive.tokens;
        hasExecutedTokenPair = true;
        const auto conditionedIds = executedTokenPair.negative_then_positive();
        ids.assign(conditionedIds.begin(), conditionedIds.end());
    }
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    json debug = json::object();
    FloatTensorData embeddings;
    const int conditioningBatch = contract.use_cfg ? 2 : 1;
    std::vector<int> positiveIds(ids.begin() + 77, ids.end());
    const std::vector<float> unitTokenWeights(77U, 1.0f);
    const auto& positiveTokenWeights = hasExecutedTokenPair
            ? executedTokenPair.positive.weights
            : unitTokenWeights;
    const auto& negativeTokenWeights = hasExecutedTokenPair
            ? executedTokenPair.negative.weights
            : unitTokenWeights;
    if (progressCallback) {
        progressCallback(1);
    }
    if (useCommunityClip) {
        size_t tokenEmbeddingBytes = 0;
        std::string detectedEmbeddingType;
        if (!inspect_community_token_embedding_file(
                root,
                tokenEmbeddingBytes,
                detectedEmbeddingType,
                error)) {
            return json({
                {"ok", false},
                {"errorCode", "PACKAGE_FORMAT_INVALID"},
                {"field", "token_emb.bin"},
                {"error", error}
            });
        }
        if (detectedEmbeddingType != contract.embedding_disk_data_type) {
            return json({
                {"ok", false},
                {"errorCode", "EXECUTION_CONTRACT_MISMATCH"},
                {"field", "embeddingDiskDataType"},
                {"error", "embeddingDiskDataType mismatch: resolved=" +
                        contract.embedding_disk_data_type + ", native=" +
                        detectedEmbeddingType + "."}
            });
        }
        if (includeDebug) debug["tokenEmbeddingBytes"] = tokenEmbeddingBytes;
        FloatTensorData positiveEmbeddings;
        std::string positiveType;
        json positiveDebug;
        if (!run_community_clip_encoder_direct(
                root,
                pinnedClipWeightPath,
                positiveIds,
                positiveTokenWeights,
                contract.backend_mode,
                contract.threads,
                positiveEmbeddings,
                error,
                includeDebug ? &positiveDebug : nullptr,
                &positiveType,
                textualInversionRequested ? &conditionedPair.positive : nullptr,
                &textEncoderBinding)) {
            return json({{"ok", false}, {"error", error}});
        }
        if (positiveType != detectedEmbeddingType) {
            return json({
                {"ok", false},
                {"errorCode", "PACKAGE_FORMAT_INVALID"},
                {"field", "token_emb.bin"},
                {"error", "PACKAGE_FORMAT_INVALID: community CLIP token embedding dtype changed during windowed reading."}
            });
        }
        if (contract.use_cfg) {
            std::vector<int> negativeIds(ids.begin(), ids.begin() + 77);
            FloatTensorData negativeEmbeddings;
            std::string negativeType;
            json negativeDebug;
            if (!run_community_clip_encoder_direct(
                    root,
                    pinnedClipWeightPath,
                    negativeIds,
                    negativeTokenWeights,
                    contract.backend_mode,
                    contract.threads,
                    negativeEmbeddings,
                    error,
                    includeDebug ? &negativeDebug : nullptr,
                    &negativeType,
                    textualInversionRequested ? &conditionedPair.negative : nullptr,
                    &textEncoderBinding)) {
                return json({{"ok", false}, {"error", error}});
            }
            if (negativeType != positiveType) {
                return json({
                    {"ok", false},
                    {"errorCode", "PACKAGE_FORMAT_INVALID"},
                    {"field", "token_emb.bin"},
                    {"error", "PACKAGE_FORMAT_INVALID: negative and positive CLIP reads reported different token embedding dtypes."}
                });
            }
            embeddings.shape = {2, 77, 768};
            embeddings.values.reserve(
                    negativeEmbeddings.values.size() + positiveEmbeddings.values.size());
            embeddings.values.insert(
                    embeddings.values.end(),
                    negativeEmbeddings.values.begin(),
                    negativeEmbeddings.values.end());
            embeddings.values.insert(
                    embeddings.values.end(),
                    positiveEmbeddings.values.begin(),
                    positiveEmbeddings.values.end());
            if (includeDebug) {
                debug["communityClip"] = {
                    {"negative", negativeDebug},
                    {"positive", positiveDebug}
                };
            }
        } else {
            embeddings = std::move(positiveEmbeddings);
            if (includeDebug) debug["communityClip"] = {{"positive", positiveDebug}};
        }
        evidence.embedding_disk_data_type = positiveType;
    } else {
        if (hasExecutedTokenPair && !validate_clip_pair_weights_for_token_id_graph(
                executedTokenPair, contract.use_cfg, error)) {
            return json({
                    {"ok", false},
                    {"errorCode", "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED"},
                    {"error", error}
            });
        }
        if (!run_text_encoder_direct(
                root,
                contract.use_cfg ? ids : positiveIds,
                contract.backend_mode,
                contract.threads,
                embeddings,
                error,
                &textEncoderBinding)) {
            return json({{"ok", false}, {"error", error}});
        }
        evidence.embedding_disk_data_type = "GRAPH_INTERNAL";
    }
    if (!validate_float_tensor_contract(
            embeddings,
            {conditioningBatch, 77, 768},
            "Text encoder output",
            error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (textEncoderBinding.active && !verify_mnn_text_encoder_binding(
            contract.text_encoder_asset, textEncoderBinding, error)) {
        return mnn_text_encoder_asset_failure(error);
    }
    if (evidence.embedding_disk_data_type != contract.embedding_disk_data_type) {
        return json({
            {"ok", false},
            {"errorCode", "EXECUTION_CONTRACT_MISMATCH"},
            {"field", "embeddingDiskDataType"},
            {"error", "embeddingDiskDataType mismatch: resolved=" +
                    contract.embedding_disk_data_type + ", native=" +
                    evidence.embedding_disk_data_type + "."}
        });
    }
    evidence.native_prompt_execution_sha256 =
            mca::image::image_prompt_execution_sha256(prompt, contract.negative_prompt);
    if (evidence.native_prompt_execution_sha256.size() != 64U) {
        return json({
            {"ok", false},
            {"errorCode", "EXECUTION_CONTRACT_MISMATCH"},
            {"field", "nativePromptExecutionSha256"},
            {"error", "The prompts consumed by native MNN conditioning could not be framed and hashed."}
        });
    }
    if (includeDebug) {
        const size_t rowSize = 77U * 768U;
        debug["tokenIds"] = contract.use_cfg ? ids : positiveIds;
        if (contract.use_cfg) {
            std::vector<float> negative(embeddings.values.begin(), embeddings.values.begin() + rowSize);
            std::vector<float> positive(embeddings.values.begin() + rowSize, embeddings.values.end());
            debug["negativeEmbeddingStats"] = float_vector_stats_json(negative);
            debug["positiveEmbeddingStats"] = float_vector_stats_json(positive);
            debug["positiveNegativeAbsDiffStats"] = float_vector_abs_diff_stats_json(positive, negative);
        } else {
            debug["positiveEmbeddingStats"] = float_vector_stats_json(embeddings.values);
        }
    }
    if (progressCallback) progressCallback(5);

    DirectMnnUnetSession unetSession;
    const auto unetBatchCandidates =
            mca::mnn::diffusionBatchCandidates(contract.use_cfg);
    std::vector<std::string> unetBatchErrors;
    bool unetInitialized = false;
    for (size_t index = 0; index < unetBatchCandidates.count; ++index) {
        const int candidate = unetBatchCandidates.values[index];
        std::string candidateError;
        if (unetSession.initialize(
                root,
                contract.backend_mode,
                contract.threads,
                candidate,
                candidateError)) {
            unetInitialized = true;
            break;
        }
        unetBatchErrors.push_back(
                "batch=" + std::to_string(candidate) + ": " + candidateError);
    }
    if (!unetInitialized) {
        std::ostringstream message;
        message << "UNet could not initialize a compatible CFG batch";
        for (size_t index = 0; index < unetBatchErrors.size(); ++index) {
            message << (index == 0 ? ": " : "; ") << unetBatchErrors[index];
        }
        const auto messageText = message.str();
        json failure = {
                {"ok", false},
                {"error", messageText},
                {"backendMode", contract.backend_mode}
        };
        // This is intentionally emitted only for a CPU SD1.5 graph whose
        // real resize/encode attempt returned NOT_SUPPORT.  Callers may then
        // choose an explicitly permitted OpenCL retry while preserving the
        // original CPU failure in execution evidence.
        if (contract.backend_mode == "cpu" &&
            mnn_sd15_unet_backend_unsupported(unetBatchErrors)) {
            failure["errorCode"] = "MNN_UNET_BACKEND_UNSUPPORTED";
            failure["nativeStatus"] = static_cast<int>(MNN::NOT_SUPPORT);
            failure["fallbackEligible"] = true;
            failure["unsupportedOps"] = {"GroupNorm", "FmhaV2", "SplitGeLU"};
            failure["error"] =
                    "MNN SD1.5 UNet cannot execute on the CPU backend: the graph contains "
                    "fused operators not registered by this MNN CPU build. " + messageText;
        }
        return failure;
    }
    bool sequentialCfg =
            mca::mnn::usesSequentialCfg(contract.use_cfg, unetSession.batch());
    size_t graphInvocationsPerTimestep =
            mca::mnn::graphInvocationsPerTimestep(contract.use_cfg, unetSession.batch());
    bool runtimeBatchFallbackAttempted = false;
    if (graphInvocationsPerTimestep == 0U) {
        return json({
                {"ok", false},
                {"error", "UNet batch policy selected an invalid execution plan."}
        });
    }
    if (includeDebug) {
        debug["unetSessionBatch"] = unetSession.batch();
        debug["cfgExecutionMode"] = sequentialCfg ? "sequential" : "batched";
        debug["graphInvocationsPerTimestep"] = graphInvocationsPerTimestep;
        debug["unetBatchFallbacks"] = unetBatchErrors;
    }

    mca::diffusion::DiffusionScheduler scheduler(contract.scheduler.config);
    if (!scheduler.set_timesteps(contract.scheduler.steps, &error)) {
        return json({{"ok", false}, {"error", "MNN direct scheduler setup failed: " + error}});
    }
    if (scheduler.timesteps().size() != contract.scheduler.expected_timetable_count) {
        return json({
            {"ok", false},
            {"error", "MNN direct scheduler timetableCount mismatch: resolved=" +
                    std::to_string(contract.scheduler.expected_timetable_count) +
                    ", native=" + std::to_string(scheduler.timesteps().size()) + "."}
        });
    }
    const size_t branchCount = contract.use_cfg ? 2U : 1U;
    if (scheduler.expected_unet_execution_count() >
        std::numeric_limits<size_t>::max() / branchCount ||
        scheduler.expected_unet_execution_count() * branchCount !=
            contract.scheduler.expected_unet_execution_count) {
        return json({
            {"ok", false},
            {"error", "MNN direct scheduler unetExecutionCount conflicts with the resolved CFG contract."}
        });
    }
    evidence.timetable_count = scheduler.timesteps().size();
    evidence.timesteps = scheduler.timesteps();
    evidence.sigmas = scheduler.sigmas();
    evidence.init_noise_sigma = scheduler.init_noise_sigma();

    std::vector<float> latent(4 * 64 * 64);
    std::mt19937 rng(contract.seed);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    for (auto& value : latent) {
        value = normal(rng) * static_cast<float>(scheduler.init_noise_sigma());
    }
    if (!validate_finite_float_vector(latent, "Initial scheduler latent", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    const size_t timetableCount = scheduler.timesteps().size();
    for (size_t i = 0; i < timetableCount; ++i) {
        if (progressCallback) {
            progressCallback(5 + static_cast<int>(i * 80U / timetableCount));
        }
        std::vector<float> modelInput;
        if (!scheduler.scale_model_input(latent, i, &modelInput, &error)) {
            return json({{"ok", false}, {"error", "MNN direct scheduler input scaling failed: " + error}});
        }
        FloatTensorData batchedNoise;
        bool executeSequentialCfg = sequentialCfg;
        if (!executeSequentialCfg) {
            std::vector<float> sampleBatch;
            sampleBatch.reserve(
                    modelInput.size() * static_cast<size_t>(conditioningBatch));
            sampleBatch.insert(sampleBatch.end(), modelInput.begin(), modelInput.end());
            if (contract.use_cfg) {
                sampleBatch.insert(sampleBatch.end(), modelInput.begin(), modelInput.end());
            }
            if (!unetSession.run(
                    sampleBatch,
                    mca::mnn::unet_input_timestep(scheduler.timesteps()[i]),
                    embeddings,
                    batchedNoise,
                    error)) {
                if (!mca::mnn::shouldRetryFirstBatchedCfgExecution(
                        contract.use_cfg,
                        unetSession.batch(),
                        i,
                        runtimeBatchFallbackAttempted)) {
                    return json({{"ok", false}, {"error", error}});
                }
                runtimeBatchFallbackAttempted = true;
                const std::string batch2ExecutionError = error;
                std::string batch1InitializationError;
                if (!unetSession.initialize(
                        root,
                        contract.backend_mode,
                        contract.threads,
                        1,
                        batch1InitializationError)) {
                    return json({
                            {"ok", false},
                            {"error", "UNet batch-2 first execution failed: " +
                                    batch2ExecutionError +
                                    " Batch-1 compatibility reinitialization failed: " +
                                    batch1InitializationError}
                    });
                }
                sequentialCfg =
                        mca::mnn::usesSequentialCfg(contract.use_cfg, unetSession.batch());
                graphInvocationsPerTimestep =
                        mca::mnn::graphInvocationsPerTimestep(
                                contract.use_cfg, unetSession.batch());
                if (!sequentialCfg || graphInvocationsPerTimestep != 2U) {
                    return json({
                            {"ok", false},
                            {"error", "UNet batch-1 compatibility reinitialization selected an invalid execution plan."}
                    });
                }
                const std::string runtimeFallbackReason =
                        "batch=2 first execution: " + batch2ExecutionError;
                unetBatchErrors.push_back(runtimeFallbackReason);
                if (includeDebug) {
                    debug["unetSessionBatch"] = unetSession.batch();
                    debug["cfgExecutionMode"] = "sequential";
                    debug["graphInvocationsPerTimestep"] = graphInvocationsPerTimestep;
                    debug["unetBatchFallbacks"] = unetBatchErrors;
                    debug["unetRuntimeFallbackReason"] = batch2ExecutionError;
                    debug["unetRuntimeFallbackTimestepIndex"] = i;
                }
                error.clear();
                executeSequentialCfg = true;
            } else {
                ++evidence.graph_invocation_count;
            }
        }
        if (executeSequentialCfg) {
            constexpr size_t kEmbeddingBranchElements = 77U * 768U;
            batchedNoise.shape = {2, 4, 64, 64};
            batchedNoise.values.clear();
            batchedNoise.values.reserve(modelInput.size() * 2U);
            for (size_t branch = 0; branch < 2U; ++branch) {
                FloatTensorData branchEmbeddings;
                branchEmbeddings.shape = {1, 77, 768};
                const auto begin = embeddings.values.begin() +
                        static_cast<std::ptrdiff_t>(branch * kEmbeddingBranchElements);
                const auto end = begin + static_cast<std::ptrdiff_t>(kEmbeddingBranchElements);
                branchEmbeddings.values.assign(begin, end);
                FloatTensorData branchNoise;
                if (!unetSession.run(
                        modelInput,
                        mca::mnn::unet_input_timestep(scheduler.timesteps()[i]),
                        branchEmbeddings,
                        branchNoise,
                        error)) {
                    return json({
                            {"ok", false},
                            {"error", "UNet sequential CFG branch " +
                                    std::to_string(branch) + " failed: " + error}
                    });
                }
                batchedNoise.values.insert(
                        batchedNoise.values.end(),
                        branchNoise.values.begin(),
                        branchNoise.values.end());
                ++evidence.graph_invocation_count;
                if (branch == 0U) {
                    const auto cancelled = mnn_asset_cancel_callback();
                    if (cancelled()) {
                        return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}});
                    }
                }
            }
        }
        evidence.unet_execution_count += branchCount;
        if (!validate_float_tensor_contract(
                batchedNoise,
                {conditioningBatch, 4, 64, 64},
                "UNet output",
                error)) {
            return json({{"ok", false}, {"error", error}});
        }
        std::vector<float> guided(latent.size());
        for (size_t j = 0; j < latent.size(); ++j) {
            if (contract.use_cfg) {
                const float uncond = batchedNoise.values[j];
                const float cond = batchedNoise.values[latent.size() + j];
                guided[j] = contract.cfg_scale * (cond - uncond) + uncond;
            } else {
                guided[j] = batchedNoise.values[j];
            }
        }
        if (!validate_finite_float_vector(guided, "Guided UNet noise", error)) {
            return json({{"ok", false}, {"error", error}});
        }
        mca::diffusion::SchedulerStepResult stepResult;
        mca::diffusion::SchedulerStepOptions stepOptions;
        stepOptions.eta = contract.scheduler.eta;
        if (!scheduler.step(guided, i, latent, &stepResult, &error, stepOptions)) {
            return json({{"ok", false}, {"error", "MNN direct scheduler step failed: " + error}});
        }
        latent = std::move(stepResult.previous_sample);
        if (includeDebug && i + 1U == timetableCount) {
            debug["finalLatentStats"] = float_vector_stats_json(latent);
        }
        if (progressCallback) {
            progressCallback(5 + static_cast<int>((i + 1U) * 80U / timetableCount));
        }
    }
    const size_t expectedGraphInvocationCount =
            timetableCount * graphInvocationsPerTimestep;
    if (scheduler.completed_step_count() != timetableCount ||
        evidence.unet_execution_count != contract.scheduler.expected_unet_execution_count ||
        evidence.graph_invocation_count != expectedGraphInvocationCount) {
        return json({
            {"ok", false},
            {"error", "MNN direct execution counts differ from the resolved scheduler contract."}
        });
    }
    std::vector<float> vaeInput = latent;
    if (contract.vae_scaling_location == MnnVaeScalingLocation::HostBeforeGraph) {
        const float inverseScale = static_cast<float>(1.0 / contract.vae_scaling_factor);
        for (float& value : vaeInput) value *= inverseScale;
    }
    if (!validate_finite_float_vector(vaeInput, "VAE decoder input", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    FloatTensorData image;
    if (progressCallback) progressCallback(90);
    if (!run_vae_decoder_direct(
            root,
            vaeInput,
            contract.backend_mode,
            contract.threads,
            image,
            error)) {
            return json({{"ok", false}, {"error", error}});
    }
    if (includeDebug) {
        debug["vaeOutputStats"] = float_vector_stats_json(image.values);
        debug["vaeOutputShape"] = image.shape;
    }
    if (progressCallback) progressCallback(95);
    if (!mca::mnn::write_vae_image_to_file(image, outputPath, contract.vae_output_range, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (textualInversionRequested &&
        !finalize_mnn_execution_assets(executionAssets, error)) {
        return mnn_execution_asset_failure(error);
    }
    if (textualInversionRequested) {
        evidence.textual_inversions =
            mca::image::textual_inversion::artifacts_json(textualInversionSelection);
        evidence.textual_inversion_evidence =
            mca::image::textual_inversion::evidence_json(
                textualInversionSelection,
                textualInversionAudit,
                true);
        evidence.execution_assets = executionAssets;
        evidence.has_execution_assets = true;
    }
    if (progressCallback) progressCallback(100);
    json result = {
        {"ok", true},
        {"path", outputPath},
        {"mimeType", "image/png"},
        {"graphInvocationCount", evidence.graph_invocation_count},
        {"initNoiseSigma", evidence.init_noise_sigma}
    };
    if (includeDebug) result["debug"] = debug;
    return result;
}
#endif

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

}  // namespace diffusion_detail
}  // namespace mnn
}  // namespace mca
