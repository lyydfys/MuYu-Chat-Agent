#include "mca/qnn/qnn_semantic_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

std::string QnnSemanticRun::conditioning_phase(QnnImageGenerationScope& generation) {
    text_encoder_context_load_ms = 0;
    text_encoder_execute_ms_total = 0;
    text_encoder_execute_count = 0;
    text_encoder_embedding_width = 0;
    text_encoder_input_elements = 0;
    text_encoder_inputs_debug = "[]";
    text_encoder_outputs_debug = "[]";
    conditioning_graph_sha256 =
        conditioning_evidence.conditioning_graph_sha256;
    prompt_weighting_execution_mode = qnn_token_conditioning
        ? "token_ids_unweighted"
        : "external_float_conditioning";
    conditioning_artifact_consumed = !qnn_token_conditioning && !embeddings.empty();
    if (qnn_token_conditioning) {
        QnnExecutableGraph text_encoder;
        if (!text_encoder.load_in_session(
                &runtime_session,
                runtime,
                text_encoder_path,
                text_encoder_graph_name,
                false,
                false,
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.sha256
                    : "",
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.size_bytes
                    : 0,
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.device
                    : 0,
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.inode
                    : 0)) {
            const std::string primary_error = text_encoder_asset_evidence.required
                ? "QNN text encoder graph could not be loaded from the verified bundle asset."
                : text_encoder.message;
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_load_failed\",\"message\":") +
                quote(primary_error) + "}";
        }
        text_encoder_context_load_ms = text_encoder.context_load_ms;
        loaded_text_encoder_graph = text_encoder.graph_name;
        if (text_encoder_asset_evidence.multilingual_evidence_required &&
            loaded_text_encoder_graph != text_encoder_asset_evidence.graph_name) {
            return qnn_semantic_failure_json(
                "text_encoder_graph_identity_invalid",
                "TEXT_ENCODER_GRAPH_IDENTITY_MISMATCH",
                "The QNN text encoder graph loaded from textEncoderContextBinary differs from textEncoderGraphName.");
        }
        if (text_encoder_asset_evidence.required) {
            conditioning_graph_sha256 = text_encoder_asset_evidence.sha256;
        } else if (!qnn_file_sha256(
                       text_encoder_path,
                       &conditioning_graph_sha256,
                       &error)) {
            return qnn_semantic_failure_json(
                "text_encoder_sha256_failed",
                "EXECUTION_EVIDENCE_INVALID",
                "QNN text encoder graph SHA-256 could not be derived: " + error);
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();

        int token_index = tensor_index_by_name(
            text_encoder.inputs,
            {"tokens", "token", "input_ids", "input"});
        if (token_index < 0 && text_encoder.inputs.size() == 1) token_index = 0;
        int embedding_index = tensor_index_by_name(
            text_encoder.outputs,
            {"text_embedding", "text_emb", "embedding", "hidden"});
        if (embedding_index < 0 && text_encoder.outputs.size() == 1) embedding_index = 0;
        if (token_index < 0 || embedding_index < 0) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_tensor_layout_unsupported\",\"message\":") +
                quote("QNN text encoder requires one int32 token input and one embedding output. inputs=" +
                    qnn_tensor_list_debug_json(text_encoder.inputs) + ", outputs=" +
                    qnn_tensor_list_debug_json(text_encoder.outputs)) + "}";
        }
        text_encoder_inputs_debug = qnn_tensor_list_debug_json(text_encoder.inputs);
        text_encoder_outputs_debug = qnn_tensor_list_debug_json(text_encoder.outputs);

        const uint64_t token_elements = qnn_tensor_element_count(
            text_encoder.inputs[static_cast<size_t>(token_index)].tensor);
        const Qnn_DataType_t token_data_type = qnn_tensor_data_type(
            text_encoder.inputs[static_cast<size_t>(token_index)].tensor);
        const uint64_t embedding_elements = qnn_tensor_element_count(
            text_encoder.outputs[static_cast<size_t>(embedding_index)].tensor);
        text_encoder_input_elements = token_elements;
        text_encoder_input_tensor =
            text_encoder.inputs[static_cast<size_t>(token_index)].name;
        text_encoder_input_data_type = qnn_data_type_name(token_data_type);
        if (token_data_type != QNN_DATATYPE_INT_32) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_tensor_layout_unsupported\",\"message\":") +
                quote("QNN CLIP token conditioning requires an actual int32 token-id graph input. inputs=" +
                    text_encoder_inputs_debug) + "}";
        }
        if (token_elements != 77 || token_ids.size() != static_cast<size_t>(token_elements * 2u)) {
            std::ostringstream message;
            message << "QNN CLIP token conditioning requires exactly two 77-token int32 sequences. "
                    << "Graph token elements=" << token_elements
                    << ", file token elements=" << token_ids.size() << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_token_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        if (embedding_elements == 0 || embedding_elements % token_elements != 0) {
            std::ostringstream message;
            message << "QNN text encoder output shape is incompatible with its token sequence. "
                    << "Token elements=" << token_elements
                    << ", embedding elements=" << embedding_elements << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_output_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        text_encoder_embedding_width = embedding_elements / token_elements;

        if (qnn_weighted_token_conditioning) {
            size_t unsupported_weighted_token_count = 0;
            if (!mca::image::validate_clip_token_id_graph_prompt_weights(
                    token_weights,
                    &unsupported_weighted_token_count,
                    &error)) {
                std::ostringstream message;
                message << error << " Actual graph input=" << text_encoder_input_tensor
                        << ", dtype=" << text_encoder_input_data_type
                        << ", elements=" << text_encoder_input_elements << ".";
                return qnn_semantic_failure_json(
                    "prompt_weighting_execution_unsupported",
                    "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED",
                    message.str());
            }
            prompt_weighting_execution_mode = "token_ids_unity_weight_payload";
        }

        std::vector<float> negative_embeddings;
        std::vector<float> positive_embeddings;
        long long execute_ms = 0;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (text_encoder_asset_evidence.multilingual_evidence_required &&
            !revalidate_qnn_tokenizer_asset_evidence(
                bundle_root,
                tokenizer_path,
                text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "tokenizer_asset_handoff_revalidation_failed",
                "TOKENIZER_ASSET_EVIDENCE_INVALID",
                error);
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!consume_qnn_mnn_prompt_handoff(
                execution_contract,
                conditioning_evidence,
                &text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "mnn_prompt_handoff_invalid",
                "MNN_PROMPT_HANDOFF_INVALID",
                error);
        }
        if (text_encoder_asset_evidence.multilingual_evidence_required) {
            conditioning_evidence.tokenizer_binding_stage = "tokenizer_consumed";
        }
        if (execution_contract.use_cfg) {
            if (!qnn_run_text_encoder_once(
                    text_encoder,
                    token_index,
                    embedding_index,
                    token_ids.data(),
                    static_cast<size_t>(token_elements),
                    &negative_embeddings,
                    &execute_ms,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_uncond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            text_encoder_execute_ms_total += execute_ms;
            ++text_encoder_execute_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
        if (!qnn_run_text_encoder_once(
                text_encoder,
                token_index,
                embedding_index,
                token_ids.data() + static_cast<size_t>(token_elements),
                static_cast<size_t>(token_elements),
                &positive_embeddings,
                &execute_ms,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        text_encoder_execute_ms_total += execute_ms;
        ++text_encoder_execute_count;
        if ((execution_contract.use_cfg &&
             negative_embeddings.size() != static_cast<size_t>(embedding_elements)) ||
            positive_embeddings.size() != static_cast<size_t>(embedding_elements)) {
            std::ostringstream message;
            message << "QNN text encoder returned an unexpected output size. Expected "
                    << embedding_elements << " per prompt, got "
                    << negative_embeddings.size() << " and "
                    << positive_embeddings.size() << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_output_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        if (text_encoder_asset_evidence.required &&
            !revalidate_qnn_text_encoder_asset_evidence(
                text_encoder_path,
                text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "text_encoder_asset_revalidation_failed",
                "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
                error);
        }
        if (text_encoder_asset_evidence.multilingual_evidence_required &&
            !revalidate_qnn_tokenizer_asset_evidence(
                bundle_root,
                tokenizer_path,
                text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "tokenizer_asset_revalidation_failed",
                "TOKENIZER_ASSET_EVIDENCE_INVALID",
                error);
        }
        embeddings.reserve(
            (execution_contract.use_cfg ? negative_embeddings.size() : 0u) +
            positive_embeddings.size());
        if (execution_contract.use_cfg) {
            embeddings.insert(
                embeddings.end(),
                negative_embeddings.begin(),
                negative_embeddings.end());
        }
        embeddings.insert(
            embeddings.end(),
            positive_embeddings.begin(),
            positive_embeddings.end());
        conditioning_artifact_consumed =
            text_encoder_execute_count == (execution_contract.use_cfg ? 2U : 1U);
        std::string text_encoder_release_error;
        if (!text_encoder.close_checked(&text_encoder_release_error)) {
            return qnn_semantic_failure_json(
                "text_encoder_release_failed",
                "NATIVE_RESOURCE_RELEASE_FAILED",
                text_encoder_release_error.empty()
                    ? "QNN text encoder resources did not release cleanly."
                    : text_encoder_release_error);
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }

    return std::string();
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
