#include "mca/mnn/mnn_clip_weighting.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

#include "image_conditioning.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_protocol_parser.hpp"
#include "mca/mnn/mnn_tensor_ops.hpp"
#include "native_prompt_language_contract.hpp"

#ifndef MCA_WITH_MNN_DIFFUSION
#define MCA_WITH_MNN_DIFFUSION 0
#endif

#if MCA_WITH_MNN_DIFFUSION
#include "mnn_diffusion_tokenizer.hpp"
#endif

namespace mca {
namespace mnn {

using json = nlohmann::json;

bool has_community_clip_bundle(
        const std::string& root,
        const std::string& graphPath) {
    return file_exists(graphPath.empty() ? root + "/clip_v2.mnn" : graphPath) &&
            file_exists(root + "/tokenizer.json") &&
            file_exists(root + "/token_emb.bin") &&
            file_exists(root + "/pos_emb.bin");
}

bool write_float_file(
        const std::string& path,
        const std::vector<float>& values,
        std::string& error) {
    if (values.empty()) {
        error = "Float output is empty.";
        return false;
    }
    if (!validate_finite_float_vector(values, "Float output", error)) {
        return false;
    }
    if (!validate_output_path_for_write(path, error)) {
        return false;
    }
    if (values.size() > static_cast<size_t>(std::numeric_limits<std::streamsize>::max()) / sizeof(float)) {
        error = "Float output is too large to write safely.";
        return false;
    }
    if (!remove_existing_output_file(path, error)) {
        return false;
    }
    std::ofstream output(path.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) {
        error = "Failed to open float output file: " + path;
        return false;
    }
    output.write(
            reinterpret_cast<const char*>(values.data()),
            static_cast<std::streamsize>(values.size() * sizeof(float)));
    if (!output.good()) {
        error = "Failed to write float output file: " + path;
        return false;
    }
    output.flush();
    if (!output.good()) {
        error = "Failed to flush float output file: " + path;
        return false;
    }
    output.close();
    if (!output.good()) {
        error = "Failed to close float output file: " + path;
        return false;
    }
    if (!nonempty_regular_file_exists(path)) {
        error = "Float output file is missing or empty after writing: " + path;
        return false;
    }
    return true;
}

bool validate_clip_pair_weights_for_token_id_graph(
        const mca::image::ClipTokenPair& tokenPair,
        bool includeNegative,
        std::string& error) {
    std::vector<const mca::image::ClipTokenSequence*> sequences;
    if (includeNegative) sequences.push_back(&tokenPair.negative);
    sequences.push_back(&tokenPair.positive);
    for (const auto* sequence : sequences) {
        if (sequence == nullptr || sequence->ids.size() != sequence->weights.size() ||
                sequence->weights.empty()) {
            error = "Prompt weighting token IDs and weights are inconsistent.";
            return false;
        }
        size_t weightedTokenCount = 0U;
        if (!mca::image::validate_clip_token_id_graph_prompt_weights(
                sequence->weights,
                &weightedTokenCount,
                &error)) {
            return false;
        }
    }
    return true;
}

bool append_clip_prompt_weighting_evidence(
        nlohmann::json& target,
        const mca::image::ClipTokenPair& tokenPair,
        bool supported,
        bool includeNegative,
        std::string& error) {
    const std::string fingerprint = includeNegative
            ? tokenPair.weighting_fingerprint()
            : tokenPair.positive.weighting_fingerprint;
    if (fingerprint.size() != 64U) {
        error = "Prompt weighting fingerprint could not be derived from executed token sequences.";
        return false;
    }
    target["promptWeightingSupported"] = supported;
    target["promptWeightingApplied"] = supported &&
            ((includeNegative && tokenPair.negative.weighting_applied) ||
             tokenPair.positive.weighting_applied);
    target["positiveWeightedTokenCount"] =
            supported ? tokenPair.positive.weighted_token_count : 0U;
    target["negativeWeightedTokenCount"] = supported && includeNegative
            ? tokenPair.negative.weighted_token_count
            : 0U;
    target["promptWeightFingerprint"] = fingerprint;
    return true;
}

nlohmann::json encode_sd15_prompt_embeddings_to_file(
        const std::string& root,
        const std::string& prompt,
        const std::string& negativePrompt,
        const std::string& outputPath,
        const std::string& backendMode,
        int threads,
        bool useCfg,
        bool promptWeightingEnabled,
        const std::string& textualInversionJson,
        CommunityClipEncoderFn communityClipEncoder,
        TextEncoderDirectFn textEncoderDirect) {
    if (is_blank_text(prompt)) {
        return json({{"ok", false}, {"error", "Prompt is empty."}});
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negativePrompt)) {
        return json({
            {"ok", false},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"error", "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."}
        });
    }
    if (has_community_clip_bundle(root) && !file_exists(root + "/text_encoder.mnn")) {
        if (!communityClipEncoder) {
            return json({{"ok", false}, {"error", "Community CLIP encoder runner not provided."}});
        }
        return communityClipEncoder(
                root,
                prompt,
                negativePrompt,
                outputPath,
                backendMode,
                threads,
                useCfg,
                promptWeightingEnabled,
                textualInversionJson);
    }
    if (!textualInversionJson.empty()) {
        return json({
            {"ok", false},
            {"errorCode", "TEXTUAL_INVERSION_GRAPH_INPUT_UNSUPPORTED"},
            {"error", "This text encoder owns its token embedding lookup inside the graph and has no host input_embedding path."}
        });
    }
    std::string error;
    std::vector<int> ids;
    mca::image::ClipTokenPair tokenPair;
    bool hasWeightedTokenPair = false;
    if (file_exists(root + "/tokenizer.json")) {
        mca::image::ClipTokenizerConfig tokenizerConfig;
        tokenizerConfig.bos_id = 49406;
        tokenizerConfig.eos_id = 49407;
        tokenizerConfig.pad_id = 49407;
        tokenizerConfig.max_length = 77;
        tokenizerConfig.enable_prompt_weighting = promptWeightingEnabled;
        if (!mca::image::tokenize_clip_pair_from_json(
                root + "/tokenizer.json",
                prompt,
                negativePrompt,
                tokenizerConfig,
                &tokenPair,
                &error)) {
            return json({{"ok", false}, {"error", error}});
        }
        hasWeightedTokenPair = true;
        const auto standardIds = tokenPair.negative_then_positive();
        ids.assign(standardIds.begin(), standardIds.end());
    } else {
        if (!negativePrompt.empty()) {
            return json({
                    {"ok", false},
                    {"error", "A complete tokenizer.json is required to execute a custom negative prompt exactly."}
            });
        }
#if MCA_WITH_MNN_DIFFUSION
        MNN::DIFFUSION::MtokTokenizer tokenizer(
                MNN::DIFFUSION::MtokTokenizer::Style::kPair,
                49406,
                49407);
        if (!tokenizer.load(root)) {
            if (has_community_clip_bundle(root)) {
                if (!communityClipEncoder) {
                    return json({{"ok", false}, {"error", "Community CLIP encoder runner not provided."}});
                }
                return communityClipEncoder(
                        root,
                        prompt,
                        negativePrompt,
                        outputPath,
                        backendMode,
                        threads,
                        useCfg,
                        promptWeightingEnabled,
                        textualInversionJson);
            }
            return json({{"ok", false}, {"error", "Failed to load MNN-Diffusion tokenizer."}});
        }
        ids = tokenizer.encode(prompt, 77);
#else
        if (has_community_clip_bundle(root)) {
            if (!communityClipEncoder) {
                return json({{"ok", false}, {"error", "Community CLIP encoder runner not provided."}});
            }
            return communityClipEncoder(
                    root,
                    prompt,
                    negativePrompt,
                    outputPath,
                    backendMode,
                    threads,
                    useCfg,
                    promptWeightingEnabled,
                    textualInversionJson);
        }
        return json({{"ok", false}, {"error", "Failed to load MNN-Diffusion tokenizer."}});
#endif
    }
    if (ids.size() != 154) {
        return json({
            {"ok", false},
            {"error", "MNN-Diffusion tokenizer returned invalid id count."},
            {"tokenCount", ids.size()}
        });
    }
    if (hasWeightedTokenPair && !validate_clip_pair_weights_for_token_id_graph(
            tokenPair, useCfg, error)) {
        return json({
                {"ok", false},
                {"errorCode", "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED"},
                {"error", error}
        });
    }
    FloatTensorData embeddings;
    const std::vector<int> activeIds = useCfg
            ? ids
            : std::vector<int>(ids.begin() + 77, ids.end());
    if (!textEncoderDirect) {
        return json({{"ok", false}, {"error", "Text encoder runner not provided."}});
    }
    if (!textEncoderDirect(root, activeIds, backendMode, threads, embeddings, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (!validate_float_tensor_contract(
            embeddings, {useCfg ? 2 : 1, 77, 768}, "Text encoder output", error)) {
        return json({{"ok", false}, {"error", error}});
    }
    if (!write_float_file(outputPath, embeddings.values, error)) {
        return json({{"ok", false}, {"error", error}});
    }
    json result = json({
        {"ok", true},
        {"path", outputPath},
        {"bytes", static_cast<long long>(embeddings.values.size() * sizeof(float))},
        {"elements", static_cast<long long>(embeddings.values.size())},
        {"shape", embeddings.shape},
        {"tokenCount", activeIds.size()},
        {"backendMode", backendMode == "opencl" || backendMode == "gpu" ? "opencl" : "cpu"}
    });
    if (hasWeightedTokenPair) {
        if (!append_clip_prompt_weighting_evidence(
                result, tokenPair, promptWeightingEnabled, useCfg, error)) {
            return json({{"ok", false}, {"error", error}});
        }
        if (!useCfg) {
            result["negativeWeightedTokenCount"] = 0;
            result["promptWeightingApplied"] =
                result.value("positiveWeightedTokenCount", 0) > 0;
        }
    } else {
        result["promptWeightingSupported"] = false;
        result["promptWeightingApplied"] = false;
        result["positiveWeightedTokenCount"] = 0;
        result["negativeWeightedTokenCount"] = 0;
        const std::string fingerprint = mnn_token_id_fingerprint(activeIds);
        if (fingerprint.size() != 64U) {
            return json({
                    {"ok", false},
                    {"error", "Conditioning fingerprint could not be derived from executed MNN token ids."}
            });
        }
        result["promptWeightFingerprint"] = fingerprint;
    }
    return result;
}

}  // namespace mnn
}  // namespace mca
