// mca/mnn/mnn_diffusion_tokenize_entries.cpp -- NativeMnnDiffusionBridge
// tokenizer, measurement, bundle-inspection, and encoding JNI entries.
// Business helpers live in mnn_diffusion_tokenize.cpp.

#include "mca/mnn/mnn_diffusion_jni_prelude.hpp"

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
