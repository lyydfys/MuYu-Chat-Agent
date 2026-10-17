// mca/mnn/mnn_chat_entries.cpp -- NativeMnnBridge generation, cancellation,
// progress, and runtime-stats JNI entries. Lifecycle entries live in
// mnn_chat_lifecycle_entries.cpp; beginCompletion business logic lives in
// mnn_chat_completion.{hpp,cpp}. JNI names and behavior are unchanged.

#include "mca/mnn/mnn_chat_jni_prelude.hpp"
#include "mca/mnn/mnn_chat_completion.hpp"

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_beginCompletion(
        JNIEnv* env,
        jobject,
        jstring messagesJson,
        jstring paramsJson) {
    return mca::mnn::chat_session::begin_mnn_completion(env, messagesJson, paramsJson);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_generateNextChunk(JNIEnv* env, jobject) {
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) {
        std::lock_guard<std::mutex> stream_lock(stream->consumer_mutex);
        while (true) {
            auto result = stream->queue.read();
            if (result.kind == mca::mnn::VisualStreamQueue::ReadKind::Cancelled) {
                jclass exception_class = env->FindClass("java/lang/IllegalStateException");
                if (exception_class != nullptr) {
                    env->ThrowNew(exception_class, "MNN visual generation was cancelled.");
                    env->DeleteLocalRef(exception_class);
                }
                return nullptr;
            }
            if (result.kind == mca::mnn::VisualStreamQueue::ReadKind::Failed) {
                jclass exception_class = env->FindClass("java/lang/IllegalStateException");
                if (exception_class != nullptr) {
                    env->ThrowNew(exception_class, result.text.c_str());
                    env->DeleteLocalRef(exception_class);
                }
                return nullptr;
            }
            const bool flush = result.kind == mca::mnn::VisualStreamQueue::ReadKind::Finished;
            auto filtered = mca::mnn::filter_stream_protocol(
                    stream->filter, std::move(result.text), flush);
            if (filtered.stopped) {
                stream->queue.stopAfterMarker();
            }
            std::string bytes = std::move(stream->utf8_tail);
            bytes += filtered.visible;
            auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
            stream->utf8_tail = std::move(decoded.incomplete_tail);
            if (!decoded.utf16.empty()) {
                auto* result = utf16_to_jstring(env, decoded.utf16);
                if (result != nullptr || env->ExceptionCheck()) return result;
            }
            if (!flush) continue;
            return nullptr;
        }
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // requestStop() publishes g_stop_signal without taking g_mnn_mutex. Consume
    // it at the next safe native boundary before protocol filtering or another
    // decoder step can commit output/cache state.
    if (g_stop_signal.exchange(false, std::memory_order_acq_rel)) {
        g_stop_requested = true;
        g_generation_active = false;
        g_generation_stop_reason = "stop_requested";
        rollback_mnn_text_prompt_cache_locked("stop_requested", false);
        return nullptr;
    }
    if (!g_pending_chunk.empty() ||
            !g_pending_utf8_tail.empty() ||
            g_stream_protocol_filter.has_pending_protocol()) {
        const bool flush = !g_generation_active || g_stop_requested || g_eop_seen;
        filter_mnn_stop_markers_locked(flush);
        bool emitted = false;
        auto* out = take_pending_utf8_locked(
                env,
                flush,
                &emitted);
        if (emitted || env->ExceptionCheck()) return out;
    }
    if (!g_generation_active || g_llm == nullptr || g_stop_requested) {
        return nullptr;
    }
    restore_stepping_status_if_needed_locked();
    if (g_generated_steps >= g_max_new_tokens || context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
        filter_mnn_stop_markers_locked(true);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, true, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        return nullptr;
    }
    try {
        g_llm->generate(1);
        // A stop may arrive while MNN is inside generate(1). Do not publish the
        // token that was produced after the cancellation request; mark the
        // transaction rolled back on this same owning thread.
        if (g_stop_signal.exchange(false, std::memory_order_acq_rel)) {
            g_stop_requested = true;
            g_generation_active = false;
            g_generation_stop_reason = "stop_requested";
            rollback_mnn_text_prompt_cache_locked("stop_requested", false);
            return nullptr;
        }
        if (g_llm->getContext() != nullptr &&
                g_llm->getContext()->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR) {
            g_generation_active = false;
            const auto reason = g_llm->getContext()->logits_error.empty()
                    ? std::string("mnn_internal_error") : g_llm->getContext()->logits_error;
            g_generation_stop_reason = reason;
            rollback_mnn_text_prompt_cache_locked(reason, true);
            set_error("MNN generation aborted: " + reason + ".");
            return nullptr;
        }
        g_generated_steps += 1;
        capture_mnn_debug_generated_token_ids_locked();
        filter_mnn_stop_markers_locked(false);
        if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
            bool emitted = false;
            auto* out = take_pending_utf8_locked(env, g_eop_seen, &emitted);
            if (emitted || env->ExceptionCheck()) return out;
        }
        if (context_finished_locked()) {
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
            settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            filter_mnn_stop_markers_locked(true);
            if (!g_pending_chunk.empty() || !g_pending_utf8_tail.empty()) {
                bool emitted = false;
                auto* out = take_pending_utf8_locked(env, true, &emitted);
                if (emitted || env->ExceptionCheck()) return out;
            }
            return nullptr;
        }
        return utf8_to_jstring(env, "");
    } catch (const std::exception& e) {
        g_generation_active = false;
        rollback_mnn_text_prompt_cache_locked("generate_failed", true);
        set_error(std::string("MNN generateNextChunk exception: ") + e.what());
        return nullptr;
    } catch (...) {
        g_generation_active = false;
        rollback_mnn_text_prompt_cache_locked("generate_failed", true);
        set_error("MNN generateNextChunk exception: unknown native error");
        return nullptr;
    }
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStop(JNIEnv*, jobject) {
    // Deliberately lock-free: generateNextChunk() may own g_mnn_mutex while
    // MNN is executing a long native step. The owning thread consumes this
    // signal and performs the cache/protocol rollback at a safe boundary.
    g_stop_signal.store(true, std::memory_order_release);
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) stream->queue.cancel();
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_requestStopIfActive(JNIEnv*, jobject) {
#if MCA_WITH_MNN_LLM
    if (const auto stream = std::atomic_load(&g_visual_stream)) {
        if (!g_visual_producer_running.load(std::memory_order_acquire)) return JNI_FALSE;
        g_stop_signal.store(true, std::memory_order_release);
        stream->queue.cancel();
        return JNI_TRUE;
    }
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
#if MCA_WITH_MNN_LLM
    // A final visible chunk can be returned before generateNextChunk() reaches
    // its normal terminal bookkeeping. Reconcile the underlying MNN context
    // here so a stale generationActive flag cannot turn natural completion
    // into a successful cancellation proof.
    if (g_generation_active && context_finished_locked()) {
        g_generation_active = false;
        g_generation_stop_reason = generation_stop_reason_locked();
        settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
    }
#endif
    if (!g_generation_active) return JNI_FALSE;
    g_stop_requested = true;
    g_generation_active = false;
    g_generation_stop_reason = "stop_requested";
 #if MCA_WITH_MNN_LLM
    rollback_mnn_text_prompt_cache_locked("stop_requested", false);
 #endif
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_isGenerationRunning(JNIEnv*, jobject) {
#if MCA_WITH_MNN_LLM
    return g_visual_producer_running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
    return mnn_json_entrypoint(
            env,
            "getRuntimeStatsJson",
            "{\"loaded\":false,\"runnerReady\":false,\"error\":\"native exception\"}",
            [env] {
#if MCA_WITH_MNN_LLM
                if (g_visual_producer_running.load(std::memory_order_acquire)) {
                    std::unique_lock<std::mutex> model_lock(g_mnn_mutex, std::try_to_lock);
                    if (!model_lock.owns_lock()) {
                        std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
                        return utf8_to_jstring(env, g_visual_stats_json);
                    }
                    return utf8_to_jstring(env, stats_json_locked());
                }
#endif
                std::lock_guard<std::mutex> lock(g_mnn_mutex);
                const auto stats = stats_json_locked();
                return utf8_to_jstring(env, stats);
            });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_getPrefillProgressJson(JNIEnv* env, jobject) {
    // Deliberately lock-free: MNN beginCompletion holds g_mnn_mutex while
    // prefill runs, and the poll getter must observe the atomics independently.
    return mnn_json_entrypoint(
            env,
            "getPrefillProgressJson",
            "{\"completedTokens\":0,\"totalTokens\":0,\"error\":\"native exception\"}",
            [env] {
                const int total = std::max(0, g_mnn_prefill_total_tokens.load(std::memory_order_acquire));
                const int completed = std::clamp(
                        g_mnn_prefill_completed_tokens.load(std::memory_order_acquire),
                        0,
                        total);
                nlohmann::json out = {
                        {"completedTokens", completed},
                        {"totalTokens", total},
                };
                return utf8_to_jstring(env, out.dump());
            });
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnBridge_resetPrefillProgress(JNIEnv*, jobject) {
    // Deliberately lock-free; same reasoning as the getter.
    g_mnn_prefill_completed_tokens.store(0, std::memory_order_release);
    g_mnn_prefill_total_tokens.store(0, std::memory_order_release);
}
