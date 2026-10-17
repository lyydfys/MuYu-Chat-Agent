#include "mca/mnn/mnn_sdxl_pipeline.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_tensor_copy.hpp"
#include "mca/mnn/mnn_tensor_ops.hpp"
#include "mca/mnn/mnn_stats_collector.hpp"
#include "mca/mnn/mnn_prompt_weighting.hpp"
#include "mca/mnn/mnn_contracts.hpp"

#include <sys/stat.h>
#include <fstream>
#include <sstream>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <memory>

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#endif

namespace mca {
namespace mnn {

bool inspect_community_token_embedding_file(
        const std::string& root,
        size_t& tokenBytes,
        std::string& dataType,
        std::string& error) {
    const auto tokenPath = root + "/token_emb.bin";
    struct stat tokenStat {};
    if (stat(tokenPath.c_str(), &tokenStat) != 0 ||
        !S_ISREG(tokenStat.st_mode) || tokenStat.st_size < 0) {
        error = "token_emb.bin is missing or is not a regular file.";
        return false;
    }
    tokenBytes = static_cast<size_t>(tokenStat.st_size);
    if (tokenBytes == kCommunityClipFp16TokenEmbeddingBytes) {
        dataType = "FP16";
        return true;
    }
    if (tokenBytes == kCommunityClipFp32TokenEmbeddingBytes) {
        dataType = "FP32";
        return true;
    }
    std::ostringstream out;
    out << "PACKAGE_FORMAT_INVALID: token_emb.bin has " << tokenBytes
        << " bytes; expected exactly " << kCommunityClipFp16TokenEmbeddingBytes
        << " for FP16 or " << kCommunityClipFp32TokenEmbeddingBytes << " for FP32.";
    error = out.str();
    return false;
}

bool build_community_clip_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        std::vector<float>& input,
        std::string& error,
        size_t* token_embedding_bytes,
        std::string* token_embedding_data_type,
        const mca::image::ClipConditionedSequence* conditioned) {
    constexpr int kMaxTextLen = 77;
    constexpr int kEmbeddingSize = 768;
    constexpr int kVocabSize = 49408;
    if (token_ids.size() != kMaxTextLen) {
        error = "CLIP token id count must be 77.";
        return false;
    }
    if (token_weights.size() != kMaxTextLen) {
        error = "CLIP token weight count must be 77.";
        return false;
    }
    if (conditioned != nullptr &&
        (conditioned->embedding_width != static_cast<size_t>(kEmbeddingSize) ||
         conditioned->tokens.ids.size() != token_ids.size() ||
         conditioned->tokens.weights.size() != token_weights.size() ||
         conditioned->override_mask.size() != token_ids.size() ||
         conditioned->embedding_overrides.size() !=
            token_ids.size() * static_cast<size_t>(kEmbeddingSize) ||
         !std::equal(conditioned->tokens.ids.begin(), conditioned->tokens.ids.end(), token_ids.begin()) ||
         !std::equal(conditioned->tokens.weights.begin(), conditioned->tokens.weights.end(), token_weights.begin()))) {
        error = "Textual inversion CLIP-L override contract does not match the executed token sequence.";
        return false;
    }
    const auto tokenPath = root + "/token_emb.bin";
    size_t tokenBytes = 0;
    std::string detectedDataType;
    if (!inspect_community_token_embedding_file(
            root,
            tokenBytes,
            detectedDataType,
            error)) {
        return false;
    }
    const bool tokenFp16 = detectedDataType == "FP16";
    const auto pos_bytes = read_binary_bytes(root + "/pos_emb.bin");
    const size_t expected_pos_bytes = static_cast<size_t>(kMaxTextLen) * kEmbeddingSize * sizeof(float);
    if (pos_bytes.size() != expected_pos_bytes) {
        std::ostringstream out;
        out << "pos_emb.bin has unexpected size: " << pos_bytes.size()
            << ", expected " << expected_pos_bytes << ".";
        error = out.str();
        return false;
    }
    if (token_embedding_bytes != nullptr) {
        *token_embedding_bytes = tokenBytes;
    }
    if (token_embedding_data_type != nullptr) {
        *token_embedding_data_type = detectedDataType;
    }
    const auto* pos_emb = reinterpret_cast<const float*>(pos_bytes.data());
    std::ifstream tokenInput(tokenPath.c_str(), std::ios::binary);
    if (!tokenInput.good()) {
        error = "Failed to open token_emb.bin for windowed reading.";
        return false;
    }
    std::vector<uint16_t> fp16Window(static_cast<size_t>(kEmbeddingSize));
    std::vector<float> fp32Window(static_cast<size_t>(kEmbeddingSize));
    input.assign(static_cast<size_t>(kMaxTextLen) * kEmbeddingSize, 0.0f);
    for (int pos = 0; pos < kMaxTextLen; ++pos) {
        const int id = token_ids[static_cast<size_t>(pos)];
        const float tokenWeight = token_weights[static_cast<size_t>(pos)];
        const bool overridden = conditioned != nullptr &&
                conditioned->override_mask[static_cast<size_t>(pos)] != 0U;
        if (id < 0 || id >= kVocabSize) {
            error = "CLIP token id at position " + std::to_string(pos) + " is outside the vocabulary range.";
            return false;
        }
        if (!std::isfinite(tokenWeight)) {
            error = "CLIP token weight at position " + std::to_string(pos) + " is not finite.";
            return false;
        }
        if (!overridden) {
            const size_t elementBytes = tokenFp16 ? sizeof(uint16_t) : sizeof(float);
            const size_t byteOffset = static_cast<size_t>(id) * kEmbeddingSize * elementBytes;
            tokenInput.clear();
            tokenInput.seekg(static_cast<std::streamoff>(byteOffset), std::ios::beg);
            if (!tokenInput.good()) {
                error = "Failed to seek token_emb.bin to vocabulary row " + std::to_string(id) + ".";
                return false;
            }
            if (tokenFp16) {
                tokenInput.read(
                        reinterpret_cast<char*>(fp16Window.data()),
                        static_cast<std::streamsize>(fp16Window.size() * sizeof(uint16_t)));
            } else {
                tokenInput.read(
                        reinterpret_cast<char*>(fp32Window.data()),
                        static_cast<std::streamsize>(fp32Window.size() * sizeof(float)));
            }
            if (!tokenInput.good()) {
                error = "Failed to read a complete token_emb.bin vocabulary row.";
                return false;
            }
        }
        for (int dim = 0; dim < kEmbeddingSize; ++dim) {
            const size_t out_index = static_cast<size_t>(pos) * kEmbeddingSize + dim;
            const float tokenValue = overridden
                    ? conditioned->embedding_overrides[out_index]
                    : (tokenFp16
                        ? fp16_to_float(fp16Window[static_cast<size_t>(dim)])
                        : fp32Window[static_cast<size_t>(dim)]);
            const float value = tokenValue * tokenWeight + pos_emb[out_index];
            if (!std::isfinite(value)) {
                error = "token_emb.bin or pos_emb.bin produced a non-finite CLIP input value.";
                return false;
            }
            input[out_index] = value;
        }
    }
    return true;
}

bool build_clip_embedding_input(
        const std::string& root,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        const std::string& tokenFile,
        const std::string& positionFile,
        int embeddingSize,
        std::vector<float>& input,
        std::string& error,
        const mca::image::ClipConditionedSequence* conditioned) {
    constexpr int kMaxTextLen = 77;
    constexpr int kVocabSize = 49408;
    if (token_ids.size() != kMaxTextLen) {
        error = "CLIP token id count must be 77.";
        return false;
    }
    if (token_weights.size() != kMaxTextLen) {
        error = "CLIP token weight count must be 77.";
        return false;
    }
    if (embeddingSize <= 0) {
        error = "CLIP embedding size must be positive.";
        return false;
    }
    if (conditioned != nullptr &&
        (conditioned->embedding_width != static_cast<size_t>(embeddingSize) ||
         conditioned->tokens.ids.size() != token_ids.size() ||
         conditioned->tokens.weights.size() != token_weights.size() ||
         conditioned->override_mask.size() != token_ids.size() ||
         conditioned->embedding_overrides.size() !=
            token_ids.size() * static_cast<size_t>(embeddingSize) ||
         !std::equal(conditioned->tokens.ids.begin(), conditioned->tokens.ids.end(), token_ids.begin()) ||
         !std::equal(conditioned->tokens.weights.begin(), conditioned->tokens.weights.end(), token_weights.begin()))) {
        error = "Textual inversion CLIP override contract does not match the executed token sequence.";
        return false;
    }
    const auto token_bytes = read_binary_bytes(root + "/" + tokenFile);
    const auto pos_bytes = read_binary_bytes(root + "/" + positionFile);
    const size_t expected_token_bytes =
            static_cast<size_t>(kVocabSize) * static_cast<size_t>(embeddingSize) * sizeof(uint16_t);
    const size_t expected_pos_bytes =
            static_cast<size_t>(kMaxTextLen) * static_cast<size_t>(embeddingSize) * sizeof(float);
    if (token_bytes.size() != expected_token_bytes) {
        std::ostringstream out;
        out << tokenFile << " has unexpected size: " << token_bytes.size()
            << ", expected " << expected_token_bytes << ".";
        error = out.str();
        return false;
    }
    if (pos_bytes.size() != expected_pos_bytes) {
        std::ostringstream out;
        out << positionFile << " has unexpected size: " << pos_bytes.size()
            << ", expected " << expected_pos_bytes << ".";
        error = out.str();
        return false;
    }
    const auto* token_emb = reinterpret_cast<const uint16_t*>(token_bytes.data());
    const auto* pos_emb = reinterpret_cast<const float*>(pos_bytes.data());
    input.assign(static_cast<size_t>(kMaxTextLen) * static_cast<size_t>(embeddingSize), 0.0f);
    for (int pos = 0; pos < kMaxTextLen; ++pos) {
        const int id = token_ids[static_cast<size_t>(pos)];
        const float tokenWeight = token_weights[static_cast<size_t>(pos)];
        if (id < 0 || id >= kVocabSize) {
            error = "CLIP token id at position " + std::to_string(pos) + " is outside the vocabulary range.";
            return false;
        }
        if (!std::isfinite(tokenWeight)) {
            error = "CLIP token weight at position " + std::to_string(pos) + " is not finite.";
            return false;
        }
        for (int dim = 0; dim < embeddingSize; ++dim) {
            const size_t out_index = static_cast<size_t>(pos) * static_cast<size_t>(embeddingSize) +
                    static_cast<size_t>(dim);
            const size_t token_index = static_cast<size_t>(id) * static_cast<size_t>(embeddingSize) +
                    static_cast<size_t>(dim);
            const bool overridden = conditioned != nullptr &&
                    conditioned->override_mask[static_cast<size_t>(pos)] != 0U;
            const float tokenValue = overridden
                    ? conditioned->embedding_overrides[out_index]
                    : fp16_to_float(token_emb[token_index]);
            const float value = tokenValue * tokenWeight + pos_emb[out_index];
            if (!std::isfinite(value)) {
                error = tokenFile + " or " + positionFile +
                        " produced a non-finite CLIP input value.";
                return false;
            }
            input[out_index] = value;
        }
    }
    return true;
}

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
bool run_community_clip_encoder_direct(
        const std::string& root,
        const std::string& pinnedExternalWeightPath,
        const std::vector<int>& token_ids,
        const std::vector<float>& token_weights,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        nlohmann::json* debug,
        std::string* embedding_disk_data_type,
        const mca::image::ClipConditionedSequence* conditioned,
        const MnnOpenedTextEncoderBinding* textEncoderBinding) {
    std::vector<float> input_values;
    size_t token_embedding_bytes = 0;
    std::string token_embedding_type;
    if (!build_community_clip_input(
            root, token_ids, token_weights, input_values, error,
            &token_embedding_bytes, &token_embedding_type, conditioned)) {
        return false;
    }
    if (embedding_disk_data_type != nullptr) {
        *embedding_disk_data_type = token_embedding_type;
    }
    if (debug != nullptr) {
        *debug = nlohmann::json::object();
        (*debug)["inputValueStats"] = float_vector_stats_json(input_values);
        (*debug)["tokenEmbeddingBytes"] = token_embedding_bytes;
        (*debug)["embeddingDiskDataType"] = token_embedding_type;
        (*debug)["tokenWeightStats"] = float_vector_stats_json(token_weights);
        nlohmann::json tokenSamples = nlohmann::json::array();
        for (size_t i = 0; i < token_ids.size() && tokenSamples.size() < 16; ++i) {
            tokenSamples.push_back(token_ids[i]);
        }
        (*debug)["tokenIdSamples"] = tokenSamples;
    }
    const bool hasDeclaredBinding =
            textEncoderBinding != nullptr && textEncoderBinding->active;
    const std::string path = hasDeclaredBinding
            ? textEncoderBinding->graph.opened_path()
            : root + "/clip_v2.mnn";
    const char* graphLabel = hasDeclaredBinding ? "declared MNN text encoder" : "clip_v2.mnn";
    if (path.empty()) {
        error = "The declared MNN text encoder descriptor is no longer open.";
        return false;
    }
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = std::string("Failed to create ") + graphLabel + " interpreter.";
        return false;
    }
    if (hasDeclaredBinding && textEncoderBinding->weight.fd >= 0) {
        const auto weightPath = textEncoderBinding->weight.opened_path();
        if (weightPath.empty()) {
            error = "The declared MNN text encoder weight descriptor is no longer open.";
            return false;
        }
        interpreter->setExternalFile(weightPath.c_str());
    } else if (!hasDeclaredBinding && !pinnedExternalWeightPath.empty()) {
        interpreter->setExternalFile(pinnedExternalWeightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = std::string(graphLabel) + " session configuration failed: " + config_error;
        return false;
    }
    // The community CLIP transformer is numerically fragile with low precision CPU
    // kernels: bad runs produce 1e37-scale hidden states, which collapse CFG.
    backendConfig.precision = MNN::BackendConfig::Precision_High;
    backendConfig.memory = MNN::BackendConfig::Memory_Normal;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = std::string("Failed to create ") + graphLabel + " session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_embedding");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input_embedding tensor is missing.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 77, 768});
    if (debug != nullptr) {
        (*debug)["inputBeforeResizeSession"] = tensor_metadata_json(input);
    }
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input copy failed: " + copyError;
        return false;
    }
    interpreter->resizeSession(session, 1);
    input = interpreter->getSessionInput(session, "input_embedding");
    if (debug != nullptr) {
        (*debug)["inputAfterResizeSession"] = tensor_metadata_json(input);
    }
    if (!copy_vector_to_tensor<float>(input, input_values, copyError)) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input recopy failed after resize: " + copyError;
        return false;
    }
    if (debug != nullptr) {
        (*debug)["inputAfterCopy"] = tensor_buffer_debug_json(input);
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    if (debug != nullptr) {
        nlohmann::json outputList = nlohmann::json::array();
        for (const auto& entry : interpreter->getSessionOutputAll(session)) {
            outputList.push_back(nlohmann::json({
                    {"name", entry.first},
                    {"debug", tensor_buffer_debug_json(entry.second)}
            }));
        }
        (*debug)["outputs"] = outputList;
    }
    auto* tensor = interpreter->getSessionOutput(session, "last_hidden_state");
    std::string selectedOutputName = "last_hidden_state";
    if (tensor == nullptr) {
        tensor = interpreter->getSessionOutput(session, nullptr);
        selectedOutputName = "<default>";
    }
    if (debug != nullptr) {
        (*debug)["selectedOutputName"] = selectedOutputName;
        (*debug)["selectedOutput"] = tensor_buffer_debug_json(tensor);
    }
    const bool ok = copy_tensor_to_float_vector(tensor, embeddings, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = std::string(graphLabel) + " output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {1, 77, 768}, std::string(graphLabel) + " output", error)) {
        return false;
    }
    if (debug != nullptr) {
        (*debug)["decodedEmbeddingStats"] = float_vector_stats_json(embeddings.values);
        (*debug)["decodedEmbeddingShape"] = embeddings.shape;
    }
    return true;
}
#endif

bool has_sdxl_qnn_clip_bundle(const std::string& root) {
    return file_exists(root + "/clip.mnn") &&
            file_exists(root + "/clip_2.mnn") &&
            file_exists(root + "/clip_2.mnn.weight") &&
            file_exists(root + "/tokenizer.json") &&
            file_exists(root + "/token_emb.bin") &&
            file_exists(root + "/token_emb_2.bin") &&
            file_exists(root + "/pos_emb.bin") &&
            file_exists(root + "/pos_emb_2.bin");
}


}  // namespace mnn
}  // namespace mca
