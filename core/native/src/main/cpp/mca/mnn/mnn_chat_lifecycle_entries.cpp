// mca/mnn/mnn_chat_lifecycle_entries.cpp -- NativeMnnBridge lifecycle JNI
// entries. Split from mnn_chat_entries.cpp so every JNI TU stays below 700
// physical lines while JNI names and behavior remain unchanged.

#include "mca/mnn/mnn_chat_jni_prelude.hpp"

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
