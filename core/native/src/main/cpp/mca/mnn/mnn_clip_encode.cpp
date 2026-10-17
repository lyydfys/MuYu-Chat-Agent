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
constexpr size_t kCommunityClipFp16TokenEmbeddingBytes = 75'890'688U;
constexpr size_t kCommunityClipFp32TokenEmbeddingBytes = 151'781'376U;
static_assert(
        kCommunityClipFp16TokenEmbeddingBytes == 49'408U * 768U * sizeof(uint16_t),
        "SD1.5 FP16 token embedding size must match the CLIP vocabulary contract.");
static_assert(
        kCommunityClipFp32TokenEmbeddingBytes == 49'408U * 768U * sizeof(float),
        "SD1.5 FP32 token embedding size must match the CLIP vocabulary contract.");
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
        const mca::image::ClipConditionedSequence* conditioned) {
    std::vector<float> input_values;
    if (!build_clip_embedding_input(
            root,
            tokenIds,
            tokenWeights,
            tokenFile,
            positionFile,
            embeddingSize,
            input_values,
            error,
            conditioned)) {
        return false;
    }
    const auto path = root + "/" + modelFile;
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create " + modelFile + " interpreter.";
        return false;
    }
    interpreter->setExternalFile(pinnedExternalWeightPath.c_str());
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = modelFile + " session configuration failed: " + config_error;
        return false;
    }
    backendConfig.precision = MNN::BackendConfig::Precision_High;
    backendConfig.memory = MNN::BackendConfig::Memory_Normal;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create " + modelFile + " session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        input = interpreter->getSessionInput(session, nullptr);
    }
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = modelFile + " input tensor is missing.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 77, embeddingSize});
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = modelFile + " input copy failed: " + copyError;
        return false;
    }
    interpreter->resizeSession(session, 1);
    input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        input = interpreter->getSessionInput(session, nullptr);
    }
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = modelFile + " input recopy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = modelFile + " runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    const auto outputs = interpreter->getSessionOutputAll(session);
    if (debug != nullptr) {
        json outputList = json::array();
        for (const auto& entry : outputs) {
            outputList.push_back(json({
                    {"name", entry.first},
                    {"debug", tensor_buffer_debug_json(entry.second)}
            }));
        }
        (*debug)["outputs"] = outputList;
        (*debug)["inputStats"] = float_vector_stats_json(input_values);
        (*debug)["tokenWeightStats"] = float_vector_stats_json(tokenWeights);
    }
    const std::vector<int> hiddenShape = {1, 77, embeddingSize};
    auto* hiddenTensor = find_output_by_shape(
            outputs, hiddenShape, {"last_hidden_state", "hidden_states", "output"});
    if (!copy_tensor_to_float_vector(hiddenTensor, hidden, error)) {
        interpreter->releaseSession(session);
        error = modelFile + " hidden output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(hidden, hiddenShape, modelFile + " hidden output", error)) {
        interpreter->releaseSession(session);
        return false;
    }
    if (pooled != nullptr) {
        const std::vector<int> pooledShape = {1, embeddingSize};
        const std::vector<std::string> pooledNames = {"text_embeds", "pooled_output"};
        auto* pooledTensor = find_named_output_by_shape(
                outputs, pooledShape, pooledNames);
        if (pooledTensor == nullptr) {
            // Diffusers-style CLIP-bigG exports may apply the shared text
            // projection to every position and publish the projected sequence
            // as [1, seq, emb]; the pooled vector is that sequence's EOS row.
            // An EOS row of an unprojected hidden state stays rejected: the
            // UNet requires the exact projected text_embeds contract.
            auto* pooledSequenceTensor = find_named_output_by_shape(
                    outputs,
                    {1, static_cast<int>(tokenIds.size()), embeddingSize},
                    pooledNames);
            if (pooledSequenceTensor == nullptr) {
                interpreter->releaseSession(session);
                error = modelFile +
                        " must expose a projected pooled output named text_embeds or "
                        "pooled_output with shape [1, " + std::to_string(embeddingSize) +
                        "] or [1, " + std::to_string(tokenIds.size()) + ", " +
                        std::to_string(embeddingSize) +
                        "]; refusing to substitute an EOS hidden-state row.";
                return false;
            }
            constexpr int kClipEosTokenId = 49407;
            const auto eosPosition = std::find(
                    tokenIds.begin(), tokenIds.end(), kClipEosTokenId);
            if (eosPosition == tokenIds.end()) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled sequence requires EOS token id " +
                        std::to_string(kClipEosTokenId) + " to select the pooled row.";
                return false;
            }
            FloatTensorData pooledSequence;
            if (!copy_tensor_to_float_vector(pooledSequenceTensor, pooledSequence, error)) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled sequence copy failed: " + error;
                return false;
            }
            const long eosRow = static_cast<long>(std::distance(tokenIds.begin(), eosPosition));
            const size_t sequenceElements = tensor_element_count({
                    1, static_cast<int>(tokenIds.size()), embeddingSize});
            if (sequenceElements == 0 || pooledSequence.values.size() != sequenceElements ||
                    eosRow < 0 ||
                    static_cast<size_t>(eosRow + 1) * static_cast<size_t>(embeddingSize) >
                            pooledSequence.values.size()) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled sequence has an invalid EOS row.";
                return false;
            }
            pooled->shape = pooledShape;
            pooled->values.assign(
                    pooledSequence.values.begin() + eosRow * embeddingSize,
                    pooledSequence.values.begin() + (eosRow + 1) * embeddingSize);
            if (!validate_float_tensor_contract(
                    *pooled, pooledShape, modelFile + " projected pooled output", error)) {
                interpreter->releaseSession(session);
                return false;
            }
            if (debug != nullptr) {
                (*debug)["pooledSource"] = "projected_sequence_eos_row";
                (*debug)["pooledSequenceEosRow"] = eosRow;
                (*debug)["pooledStats"] = float_vector_stats_json(pooled->values);
            }
        } else {
            if (!copy_tensor_to_float_vector(pooledTensor, *pooled, error)) {
                interpreter->releaseSession(session);
                error = modelFile + " projected pooled output copy failed: " + error;
                return false;
            }
            if (!validate_float_tensor_contract(
                    *pooled, pooledShape, modelFile + " projected pooled output", error)) {
                interpreter->releaseSession(session);
                return false;
            }
            if (debug != nullptr) {
                const auto textEmbeds = outputs.find("text_embeds");
                (*debug)["pooledSource"] =
                        textEmbeds != outputs.end() && textEmbeds->second == pooledTensor
                                ? "text_embeds"
                                : "pooled_output";
                (*debug)["pooledStats"] = float_vector_stats_json(pooled->values);
            }
        }
    }
    interpreter->releaseSession(session);
    return true;
}
#endif

#if MCA_WITH_MNN_DIFFUSION
bool validate_clip_pair_weights_for_token_id_graph(
        const mca::image::ClipTokenPair& tokenPair,
        bool includeNegative,
        std::string& error) {
    return mca::mnn::validate_clip_pair_weights_for_token_id_graph(
            tokenPair, includeNegative, error);
}
#endif

#if MCA_WITH_MNN_DIFFUSION
bool append_clip_prompt_weighting_evidence(
        json& target,
        const mca::image::ClipTokenPair& tokenPair,
        bool supported,
        bool includeNegative,
        std::string& error) {
    return mca::mnn::append_clip_prompt_weighting_evidence(
            target, tokenPair, supported, includeNegative, error);
}
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
        const std::string& textualInversionJson) {
    if (is_blank_text(prompt)) {
        return json({{"ok", false}, {"error", "Prompt is empty."}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!useCfg && !is_blank_text(negativePrompt)) {
        return json({
            {"ok", false},
            {"error", "SDXL no-CFG conditioning must not receive a negative prompt."},
            {"format", "sdxl_qnn_conditioning"}
        });
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negativePrompt)) {
        return json({
            {"ok", false},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"error", "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."},
            {"format", "sdxl_qnn_conditioning"}
        });
    }
    if (width < 256 || width > 2048 || height < 256 || height > 2048 ||
        width % 8 != 0 || height % 8 != 0) {
        return json({{"ok", false}, {"error", "SDXL width and height must be multiples of 8 between 256 and 2048."},
                     {"format", "sdxl_qnn_conditioning"}});
    }
    std::string canonicalBackend;
    std::string runtimeError;
    if (!validate_mnn_diffusion_runtime_options(backendMode, threads, canonicalBackend, runtimeError)) {
        return json({{"ok", false}, {"error", runtimeError}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!has_sdxl_qnn_clip_bundle(root)) {
        return json({
                {"ok", false},
                {"error", "SDXL QNN bundle requires clip.mnn, clip_2.mnn(+weight), tokenizer.json, token_emb*.bin and pos_emb*.bin."},
                {"format", "sdxl_qnn_conditioning"}
        });
    }
    std::string error;
    const bool textualInversionRequested = !textualInversionJson.empty();
    const auto clip1WeightPath = join_path(root, "clip.mnn.weight");
    const bool useClip1Weight = nonempty_regular_file_exists(clip1WeightPath);
    const std::string pinnedClip1WeightPath = useClip1Weight ? clip1WeightPath : "";
    const std::string pinnedClip2WeightPath = join_path(root, "clip_2.mnn.weight");
    mca::image::execution_assets::Binding executionAssets;
    if (textualInversionRequested && !prepare_mnn_execution_assets(
            textualInversionJson, "QNN_HTP", executionAssets, error)) {
        return mnn_execution_asset_failure(error, "sdxl_qnn_conditioning");
    }
    if (textualInversionRequested) {
        std::vector<std::string> requiredAssetPaths = {
            join_path(root, "clip.mnn"),
            join_path(root, "clip_2.mnn"),
            join_path(root, "clip_2.mnn.weight"),
            join_path(root, "tokenizer.json"),
            join_path(root, "token_emb.bin"),
            join_path(root, "token_emb_2.bin"),
            join_path(root, "pos_emb.bin"),
            join_path(root, "pos_emb_2.bin"),
        };
        if (useClip1Weight) {
            requiredAssetPaths.push_back(clip1WeightPath);
        }
        if (!mnn_execution_assets_match(executionAssets, requiredAssetPaths, error)) {
            return mnn_execution_asset_failure(error, "sdxl_qnn_conditioning");
        }
    }
    mca::image::ClipTokenizerConfig clip1TokenizerConfig;
    clip1TokenizerConfig.bos_id = 49406;
    clip1TokenizerConfig.eos_id = 49407;
    clip1TokenizerConfig.pad_id = 49407;
    clip1TokenizerConfig.max_length = 77;
    clip1TokenizerConfig.enable_prompt_weighting = promptWeightingEnabled;
    mca::image::ClipTokenizerConfig clip2TokenizerConfig = clip1TokenizerConfig;
    clip2TokenizerConfig.pad_id = 0;
    mca::image::ClipTokenPair clip1TokenPair;
    mca::image::ClipTokenPair clip2TokenPair;
    if (!mca::image::tokenize_clip_pair_from_json(
            root + "/tokenizer.json",
            prompt,
            negativePrompt,
            clip1TokenizerConfig,
            &clip1TokenPair,
            &error,
            useCfg) ||
        !mca::image::tokenize_clip_pair_from_json(
            root + "/tokenizer.json",
            prompt,
            negativePrompt,
            clip2TokenizerConfig,
            &clip2TokenPair,
            &error,
            useCfg)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    mca::image::textual_inversion::Selection textualInversionSelection;
    mca::image::textual_inversion::Audit textualInversionAudit;
    mca::image::ClipConditionedPair clip1ConditionedPair;
    mca::image::ClipConditionedPair clip2ConditionedPair;
    if (textualInversionRequested) {
        if (!mca::image::textual_inversion::load_selection(
                textualInversionJson,
                "QNN_HTP",
                true,
                &textualInversionSelection,
                &textualInversionAudit,
                &error) ||
            !mnn_selection_matches_execution_assets(
                textualInversionSelection, executionAssets, error) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                clip1TokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, false),
                &clip1ConditionedPair,
                &error,
                useCfg) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                clip2TokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, true),
                &clip2ConditionedPair,
                &error,
                useCfg)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_CONDITIONING_FAILED"},
                {"error", error},
                {"format", "sdxl_qnn_conditioning"}
            });
        }
        mca::image::textual_inversion::record_conditioned_pair(
            clip1ConditionedPair, false, &textualInversionAudit);
        mca::image::textual_inversion::record_conditioned_pair(
            clip2ConditionedPair, true, &textualInversionAudit);
        clip1TokenPair.negative = clip1ConditionedPair.negative.tokens;
        clip1TokenPair.positive = clip1ConditionedPair.positive.tokens;
        clip2TokenPair.negative = clip2ConditionedPair.negative.tokens;
        clip2TokenPair.positive = clip2ConditionedPair.positive.tokens;
    }
    const auto clip1StandardIds = useCfg
        ? clip1TokenPair.negative_then_positive()
        : clip1TokenPair.positive.ids;
    const auto clip2StandardIds = useCfg
        ? clip2TokenPair.negative_then_positive()
        : clip2TokenPair.positive.ids;
    const std::vector<int> clip1Ids(clip1StandardIds.begin(), clip1StandardIds.end());
    const std::vector<int> clip2Ids(clip2StandardIds.begin(), clip2StandardIds.end());
    const size_t expectedTokenIds = useCfg ? 154U : 77U;
    if (clip1Ids.size() != expectedTokenIds || clip2Ids.size() != expectedTokenIds) {
        return json({{"ok", false}, {"error", "CLIP tokenizer returned invalid id count."}});
    }
    std::vector<int> clip1NegIds;
    std::vector<int> clip1PosIds;
    std::vector<int> clip2NegIds;
    std::vector<int> clip2PosIds;
    if (useCfg) {
        clip1NegIds.assign(clip1Ids.begin(), clip1Ids.begin() + 77);
        clip1PosIds.assign(clip1Ids.begin() + 77, clip1Ids.end());
        clip2NegIds.assign(clip2Ids.begin(), clip2Ids.begin() + 77);
        clip2PosIds.assign(clip2Ids.begin() + 77, clip2Ids.end());
    } else {
        clip1PosIds = clip1Ids;
        clip2PosIds = clip2Ids;
    }

    FloatTensorData negClip1;
    FloatTensorData posClip1;
    FloatTensorData negClip2;
    FloatTensorData posClip2;
    FloatTensorData negPooled;
    FloatTensorData posPooled;
    json debug = json::object();
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    if (useCfg && !run_sdxl_clip_encoder_direct(
            root, "clip.mnn", pinnedClip1WeightPath,
            "token_emb.bin", "pos_emb.bin", 768, clip1NegIds,
            clip1TokenPair.negative.weights,
            canonicalBackend, threads, negClip1, nullptr, error,
            includeDebug ? &debug["clip1_negative"] : nullptr,
            textualInversionRequested ? &clip1ConditionedPair.negative : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip.mnn", pinnedClip1WeightPath,
            "token_emb.bin", "pos_emb.bin", 768, clip1PosIds,
            clip1TokenPair.positive.weights,
            canonicalBackend, threads, posClip1, nullptr, error,
            includeDebug ? &debug["clip1_positive"] : nullptr,
            textualInversionRequested ? &clip1ConditionedPair.positive : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (useCfg && !run_sdxl_clip_encoder_direct(
            root, "clip_2.mnn", pinnedClip2WeightPath,
            "token_emb_2.bin", "pos_emb_2.bin", 1280, clip2NegIds,
            clip2TokenPair.negative.weights,
            canonicalBackend, threads, negClip2, &negPooled, error,
            includeDebug ? &debug["clip2_negative"] : nullptr,
            textualInversionRequested ? &clip2ConditionedPair.negative : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    if (!run_sdxl_clip_encoder_direct(
            root, "clip_2.mnn", pinnedClip2WeightPath,
            "token_emb_2.bin", "pos_emb_2.bin", 1280, clip2PosIds,
            clip2TokenPair.positive.weights,
            canonicalBackend, threads, posClip2, &posPooled, error,
            includeDebug ? &debug["clip2_positive"] : nullptr,
            textualInversionRequested ? &clip2ConditionedPair.positive : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    constexpr int kTextLen = 77;
    constexpr int kClip1 = 768;
    constexpr int kClip2 = 1280;
    constexpr int kHidden = kClip1 + kClip2;
    if ((useCfg && (!validate_float_tensor_contract(negClip1, {1, kTextLen, kClip1}, "SDXL negative clip1", error) ||
        !validate_float_tensor_contract(negClip2, {1, kTextLen, kClip2}, "SDXL negative clip2", error) ||
        !validate_float_tensor_contract(negPooled, {1, kClip2}, "SDXL negative pooled output", error))) ||
        !validate_float_tensor_contract(posClip1, {1, kTextLen, kClip1}, "SDXL positive clip1", error) ||
        !validate_float_tensor_contract(posClip2, {1, kTextLen, kClip2}, "SDXL positive clip2", error) ||
        !validate_float_tensor_contract(posPooled, {1, kClip2}, "SDXL positive pooled output", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    std::vector<float> combined;
    const size_t branchCount = useCfg ? 2U : 1U;
    combined.reserve(static_cast<size_t>(branchCount * kTextLen * kHidden +
        branchCount * kClip2 + 6));
    auto appendHidden = [&](const FloatTensorData& clip1, const FloatTensorData& clip2) {
        for (int token = 0; token < kTextLen; ++token) {
            const size_t clip1Base = static_cast<size_t>(token) * kClip1;
            const size_t clip2Base = static_cast<size_t>(token) * kClip2;
            combined.insert(combined.end(), clip1.values.begin() + clip1Base, clip1.values.begin() + clip1Base + kClip1);
            combined.insert(combined.end(), clip2.values.begin() + clip2Base, clip2.values.begin() + clip2Base + kClip2);
        }
    };
    if (useCfg) appendHidden(negClip1, negClip2);
    appendHidden(posClip1, posClip2);
    if (useCfg) {
        combined.insert(combined.end(), negPooled.values.begin(), negPooled.values.end());
    }
    combined.insert(combined.end(), posPooled.values.begin(), posPooled.values.end());
    combined.insert(combined.end(), {
            static_cast<float>(height),
            static_cast<float>(width),
            0.0f,
            0.0f,
            static_cast<float>(height),
            static_cast<float>(width)
    });
    if (!write_float_file(outputPath, combined, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    std::vector<uint8_t> artifactBytes(combined.size() * sizeof(float));
    std::memcpy(artifactBytes.data(), combined.data(), artifactBytes.size());
    const std::string conditioningArtifactSha256 =
            mca::image::sha256_hex_bytes(artifactBytes);
    const std::string nativePromptExecutionSha256 =
            mca::image::image_prompt_execution_sha256(prompt, negativePrompt);
    if (conditioningArtifactSha256.size() != 64U ||
        nativePromptExecutionSha256.size() != 64U) {
        return json({
                {"ok", false},
                {"error", "SDXL conditioning artifact or native prompt SHA-256 could not be derived."},
                {"format", "sdxl_qnn_conditioning"}
        });
    }
    if (textualInversionRequested &&
        !finalize_mnn_execution_assets(executionAssets, error)) {
        return mnn_execution_asset_failure(error, "sdxl_qnn_conditioning");
    }
    json result = json({
            {"ok", true},
            {"path", outputPath},
            {"bytes", static_cast<long long>(combined.size() * sizeof(float))},
            {"elements", static_cast<long long>(combined.size())},
            {"format", "sdxl_qnn_conditioning"},
            {"hiddenShape", {branchCount, 77, 2048}},
            {"pooledShape", {branchCount, 1280}},
            {"timeIdsShape", {1, 6}},
            {"conditioningExecutionMode", "external_mnn_sdxl_embeddings"},
            {"conditioningBackend", "MNN"},
            {"conditioningGraph", "clip.mnn+clip_2.mnn"},
            {"conditioningEncoderExecutionCount", useCfg ? 4 : 2},
            {"conditioningOrder", useCfg ? "negative_then_positive" : "positive_only"},
            {"conditioningArtifactSha256", conditioningArtifactSha256},
            {"nativePromptExecutionSha256", nativePromptExecutionSha256},
            {"nativePromptBindingStage", "conditioning_encoded"},
            {"clip1PadId", clip1TokenizerConfig.pad_id},
            {"clip2PadId", clip2TokenizerConfig.pad_id},
            {"clipPadRules", {
                {"clip1", "EOS"},
                {"clip2", "ZERO"}
            }},
            {"backendMode", canonicalBackend}
    });
    const size_t hiddenElementCount = static_cast<size_t>(kTextLen * kHidden);
    const size_t positiveHiddenOffset = useCfg ? hiddenElementCount : 0U;
    result["positiveHiddenStats"] = float_vector_stats_json(
        std::vector<float>(
            combined.begin() + positiveHiddenOffset,
            combined.begin() + positiveHiddenOffset + hiddenElementCount));
    if (useCfg) {
        result["negativeHiddenStats"] = float_vector_stats_json(
            std::vector<float>(combined.begin(), combined.begin() + hiddenElementCount));
        result["positiveNegativeAbsDiffStats"] = float_vector_abs_diff_stats_json(
            std::vector<float>(
                combined.begin() + hiddenElementCount,
                combined.begin() + 2U * hiddenElementCount),
            std::vector<float>(combined.begin(), combined.begin() + hiddenElementCount));
    }
    if (!append_clip_prompt_weighting_evidence(
            result, clip1TokenPair, promptWeightingEnabled, useCfg, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "sdxl_qnn_conditioning"}});
    }
    // The isolated QNN consumer receives the serialized CLIP float artifact,
    // not either tokenizer sequence independently. Bind the
    // cross-process fingerprint to those exact bytes while retaining the
    // weighted-token counts as separate execution evidence.
    result["promptWeightFingerprint"] = conditioningArtifactSha256;
    if (textualInversionRequested) {
        result["textualInversions"] =
            mca::image::textual_inversion::artifacts_json(textualInversionSelection);
        result["textualInversionEvidence"] =
            mca::image::textual_inversion::evidence_json(
                textualInversionSelection,
                textualInversionAudit,
                false);
        mca::image::execution_assets::append_evidence(result, executionAssets);
    }
    if (includeDebug) {
        result["debug"] = debug;
    }
    return result;
}
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
        bool promptWeightingEnabled,
        const std::string& textualInversionJson) {
    std::string error;
    const bool textualInversionRequested = !textualInversionJson.empty();
    const auto clipWeightPath = join_path(root, "clip_v2.mnn.weight");
    const bool useClipWeight = nonempty_regular_file_exists(clipWeightPath);
    const std::string pinnedClipWeightPath = useClipWeight ? clipWeightPath : "";
    mca::image::execution_assets::Binding executionAssets;
    if (textualInversionRequested && !prepare_mnn_execution_assets(
            textualInversionJson, "QNN_HTP", executionAssets, error)) {
        return mnn_execution_asset_failure(error, "community_clip");
    }
    if (textualInversionRequested) {
        std::vector<std::string> requiredAssetPaths = {
            join_path(root, "clip_v2.mnn"),
            join_path(root, "tokenizer.json"),
            join_path(root, "token_emb.bin"),
            join_path(root, "pos_emb.bin"),
        };
        if (useClipWeight) {
            requiredAssetPaths.push_back(clipWeightPath);
        }
        if (!mnn_execution_assets_match(executionAssets, requiredAssetPaths, error)) {
            return mnn_execution_asset_failure(error, "community_clip");
        }
    }
    mca::image::ClipTokenizerConfig tokenizerConfig;
    tokenizerConfig.bos_id = 49406;
    tokenizerConfig.eos_id = 49407;
    tokenizerConfig.pad_id = 49407;
    tokenizerConfig.max_length = 77;
    tokenizerConfig.enable_prompt_weighting = promptWeightingEnabled;
    mca::image::ClipTokenPair tokenPair;
    if (!mca::image::tokenize_clip_pair_from_json(
            root + "/tokenizer.json",
            prompt,
            negativePrompt,
            tokenizerConfig,
            &tokenPair,
            &error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    mca::image::textual_inversion::Selection textualInversionSelection;
    mca::image::textual_inversion::Audit textualInversionAudit;
    mca::image::ClipConditionedPair conditionedPair;
    if (textualInversionRequested) {
        if (!mca::image::textual_inversion::load_selection(
                textualInversionJson,
                "QNN_HTP",
                false,
                &textualInversionSelection,
                &textualInversionAudit,
                &error) ||
            !mnn_selection_matches_execution_assets(
                textualInversionSelection, executionAssets, error) ||
            !mca::image::tokenize_clip_pair_with_textual_inversion_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                tokenizerConfig,
                mca::image::textual_inversion::clip_embeddings(textualInversionSelection, false),
                &conditionedPair,
                &error)) {
            return json({
                {"ok", false},
                {"errorCode", "TEXTUAL_INVERSION_CONDITIONING_FAILED"},
                {"error", error},
                {"format", "community_clip"}
            });
        }
        mca::image::textual_inversion::record_conditioned_pair(
            conditionedPair, false, &textualInversionAudit);
        tokenPair.negative = conditionedPair.negative.tokens;
        tokenPair.positive = conditionedPair.positive.tokens;
    }
    const auto standardIds = tokenPair.negative_then_positive();
    const std::vector<int> ids(standardIds.begin(), standardIds.end());
    if (ids.size() != 154) {
        return json({
            {"ok", false},
            {"error", "CLIP tokenizer returned invalid id count."},
            {"tokenCount", ids.size()},
            {"format", "community_clip"}
        });
    }
    FloatTensorData negative;
    FloatTensorData positive;
    std::string negativeEmbeddingType;
    std::string positiveEmbeddingType;
    json negativeDebug = json::object();
    json positiveDebug = json::object();
    const bool includeDebug =
            outputPath.find("/image_bench/runs/") != std::string::npos ||
            outputPath.find("\\image_bench\\runs\\") != std::string::npos;
    std::vector<int> neg_ids(ids.begin(), ids.begin() + 77);
    std::vector<int> pos_ids(ids.begin() + 77, ids.end());
    if (useCfg) {
        if (!run_community_clip_encoder_direct(
                root,
                pinnedClipWeightPath,
                neg_ids,
                tokenPair.negative.weights,
                backendMode,
                threads,
                negative,
                error,
                includeDebug ? &negativeDebug : nullptr,
                &negativeEmbeddingType,
                textualInversionRequested ? &conditionedPair.negative : nullptr)) {
            return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
        }
    }
    if (!run_community_clip_encoder_direct(
            root,
            pinnedClipWeightPath,
            pos_ids,
            tokenPair.positive.weights,
            backendMode,
            threads,
            positive,
            error,
            includeDebug ? &positiveDebug : nullptr,
            &positiveEmbeddingType,
            textualInversionRequested ? &conditionedPair.positive : nullptr)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    if (useCfg && negativeEmbeddingType != positiveEmbeddingType) {
        return json({
            {"ok", false},
            {"error", "PACKAGE_FORMAT_INVALID: negative and positive CLIP reads reported different token embedding dtypes."},
            {"format", "community_clip"}
        });
    }
    if ((useCfg && !validate_float_tensor_contract(
            negative, {1, 77, 768}, "Community negative CLIP output", error)) ||
        !validate_float_tensor_contract(
            positive, {1, 77, 768}, "Community positive CLIP output", error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    std::vector<float> combined;
    combined.reserve((useCfg ? negative.values.size() : 0U) + positive.values.size());
    if (useCfg) {
        combined.insert(combined.end(), negative.values.begin(), negative.values.end());
    }
    combined.insert(combined.end(), positive.values.begin(), positive.values.end());
    if (!write_float_file(outputPath, combined, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    std::vector<uint8_t> artifactBytes(combined.size() * sizeof(float));
    std::memcpy(artifactBytes.data(), combined.data(), artifactBytes.size());
    const std::string conditioningArtifactSha256 =
            mca::image::sha256_hex_bytes(artifactBytes);
    const std::string nativePromptExecutionSha256 =
            mca::image::image_prompt_execution_sha256(
                    prompt,
                    useCfg ? negativePrompt : std::string());
    if (conditioningArtifactSha256.size() != 64U ||
        nativePromptExecutionSha256.size() != 64U) {
        return json({
            {"ok", false},
            {"error", "Community CLIP conditioning artifact or native prompt SHA-256 could not be derived."},
            {"format", "community_clip"}
        });
    }
    if (textualInversionRequested &&
        !finalize_mnn_execution_assets(executionAssets, error)) {
        return mnn_execution_asset_failure(error, "community_clip");
    }
    json result = {
        {"ok", true},
        {"path", outputPath},
        {"bytes", static_cast<long long>(combined.size() * sizeof(float))},
        {"elements", static_cast<long long>(combined.size())},
        {"shape", {useCfg ? 2 : 1, 77, 768}},
        {"tokenCount", useCfg ? ids.size() : pos_ids.size()},
        {"positiveShape", positive.shape},
        {"positiveStats", float_vector_stats_json(positive.values)},
        {"format", "community_clip"},
        {"conditioningExecutionMode", "external_mnn_embeddings"},
        {"conditioningBackend", "MNN"},
        {"conditioningGraph", "clip_v2.mnn"},
        {"conditioningEncoderExecutionCount", useCfg ? 2 : 1},
        {"conditioningOrder", useCfg ? "negative_then_positive" : "positive_only"},
        {"conditioningArtifactSha256", conditioningArtifactSha256},
        {"nativePromptExecutionSha256", nativePromptExecutionSha256},
        {"nativePromptBindingStage", "conditioning_encoded"},
        {"embeddingDiskDataType", positiveEmbeddingType},
        {"backendMode", backendMode == "opencl" || backendMode == "gpu" ? "opencl" : "cpu"}
    };
    if (useCfg) {
        result["negativeShape"] = negative.shape;
        result["negativeStats"] = float_vector_stats_json(negative.values);
        result["positiveNegativeAbsDiffStats"] =
            float_vector_abs_diff_stats_json(positive.values, negative.values);
    }
    if (!append_clip_prompt_weighting_evidence(
            result, tokenPair, promptWeightingEnabled, useCfg, error)) {
        return json({{"ok", false}, {"error", error}, {"format", "community_clip"}});
    }
    // The artifact digest is the cross-process identity consumed by QNN. Keep
    // weighting counts as separate evidence, but bind the fingerprint field
    // to the exact serialized float payload.
    result["promptWeightFingerprint"] = conditioningArtifactSha256;
    if (textualInversionRequested) {
        result["textualInversions"] =
            mca::image::textual_inversion::artifacts_json(textualInversionSelection);
        result["textualInversionEvidence"] =
            mca::image::textual_inversion::evidence_json(
                textualInversionSelection,
                textualInversionAudit,
                false);
        mca::image::execution_assets::append_evidence(result, executionAssets);
    }
    if (!useCfg) {
        result["negativeWeightedTokenCount"] = 0;
        result["promptWeightingApplied"] =
            result.value("positiveWeightedTokenCount", 0) > 0;
    }
    if (includeDebug) {
        result["debug"] = {{"positive", positiveDebug}};
        if (useCfg) result["debug"]["negative"] = negativeDebug;
    }
    return result;
}
#endif

}  // namespace diffusion_detail
}  // namespace mnn
}  // namespace mca
