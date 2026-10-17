// mca/mnn/mnn_diffusion_tokenize.cpp -- tokenizer/conditioning/token-id
// business owner. Relocated from the former 1,028-line JNI TU; UNet smoke
// orchestration lives beside its DirectMnnUnetSession owner. JNI entries are
// in mnn_diffusion_tokenize_entries.cpp.

#include "mca/mnn/mnn_diffusion_jni_prelude.hpp"

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
