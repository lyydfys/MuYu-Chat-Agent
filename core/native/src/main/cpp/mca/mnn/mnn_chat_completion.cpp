// mca/mnn/mnn_chat_completion.cpp -- business-owner body for
// NativeMnnBridge.beginCompletion. The JNI symbol remains in
// mnn_chat_entries.cpp and only delegates across this declared boundary.

#include "mca/mnn/mnn_chat_jni_prelude.hpp"
#include "mca/mnn/mnn_chat_completion.hpp"

namespace mca {
namespace mnn {
namespace chat_session {

jint begin_mnn_completion(
        JNIEnv* env,
        jstring messagesJson,
        jstring paramsJson) {
    std::lock_guard<std::mutex> lifecycle_lock(g_mnn_lifecycle_mutex);
#if MCA_WITH_MNN_LLM
    if (!join_visual_producer(env)) return kMnnInvalidState;
#endif
    std::lock_guard<std::mutex> lock(g_mnn_mutex);
    // A new request owns a fresh decoder boundary even if validation or setup
    // fails before MNN starts producing tokens.
    g_pending_chunk.clear();
    g_pending_utf8_tail.clear();
    g_prompt_ends_inside_reasoning = false;
    g_mnn_prefill_completed_tokens.store(0, std::memory_order_release);
    g_mnn_prefill_total_tokens.store(0, std::memory_order_release);
#if MCA_WITH_MNN_LLM
    if (!g_loaded || g_llm == nullptr) {
        set_error("MNN beginCompletion requested before a model is loaded.");
        return kMnnInvalidState;
    }
    bool requestHasMedia = false;
    try {
        // A caller may start a replacement request after receiving the final
        // visible chunk but before asking for the terminal null chunk. Settle
        // that transaction first so its partial KV can never leak forward.
        if (g_mnn_prompt_cache.request_active) {
            if (g_generation_active && !g_stop_requested && context_finished_locked()) {
                g_generation_active = false;
                g_generation_stop_reason = generation_stop_reason_locked();
                settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            } else {
                rollback_mnn_text_prompt_cache_locked("superseded", true);
            }
        }
        const auto params = jstring_to_std(env, paramsJson);
        const auto parsed_params = json::parse(params, nullptr, false);
        configure_mnn_debug_trace_locked(parsed_params);
        auto parsed = parse_chat_messages(jstring_to_std(env, messagesJson));
        requestHasMedia = parsed.hasMediaInputs();
        auto config = build_mnn_config(g_model_path, params, false);
        const auto loaded_config = json::parse(g_loaded_config_json, nullptr, false);
        if (loaded_config.is_discarded() || !loaded_config.is_object()) {
            set_error("MNN loaded configuration snapshot is unavailable; reload the active committed profile.");
            return kMnnLoadSignatureMismatch;
        }
        const auto changed_load_fields = mnn_load_signature_differences(loaded_config, config);
        if (!changed_load_fields.empty()) {
            std::ostringstream detail;
            for (size_t index = 0; index < changed_load_fields.size(); ++index) {
                if (index > 0) detail << ',';
                detail << changed_load_fields[index];
            }
            set_error("MNN completion load signature mismatch; reload required. changed_fields=" + detail.str());
            return kMnnLoadSignatureMismatch;
        }
        // Prompt/KV reuse is a text-only capability. A visual request uses the
        // official multimodal response path and must not inherit stale text KV,
        // even when the loaded package itself is capable of vision.
        const bool textOnlyRequest = !parsed.hasMediaInputs();
        config["reuse_kv"] = textOnlyRequest;
        config["prompt_cache"] = textOnlyRequest;
        mca::mnn::applyProductMnnMultimodalRequestPolicy(
                config,
                parsed.hasMediaInputs(),
                opt_int(config, "chunk", 0));
        configure_mnn_stop_markers_locked(parsed_params);
        g_max_new_tokens = std::max(1, config.value("max_new_tokens", 512));
        g_max_all_tokens = std::max(1, config.value("max_all_tokens", 8192));
        g_n_threads = std::max(1, config.value("thread_num", 4));
        g_last_config_json = config.dump();
        g_generated_steps = 0;
        g_stop_requested = false;
        g_stop_signal.store(false, std::memory_order_release);
        g_generation_active = true;
        g_eop_seen = false;
        g_streamed_bytes = 0;
        g_generation_stop_reason = "running";
        g_generation_started_at_ms = now_ms();
        g_first_chunk_at_ms = 0;
        g_sync_stepping = true;
        const auto visualStream = textOnlyRequest ? std::shared_ptr<VisualStreamState>{}
                : std::make_shared<VisualStreamState>();
        g_stream_buffer = std::make_unique<MnnStreamBuffer>(visualStream);
        g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
        // response()/generate_init() owns context cleanup. The product bridge
        // must not call Llm::reset() here: a controlled MNN 3.5 differential
        // reproduced the Qwen3.5 vision failure by adding only that reset.
        const bool resetBeforeRequest = g_mnn_request_lifecycle.beginRequest();
        if (resetBeforeRequest) {
            g_llm->reset();
        }
        if (textOnlyRequest) {
            prepare_mnn_text_prompt_cache_locked(parsed.messages);
        } else {
            mark_mnn_prompt_cache_disabled_locked(
                    true,
                    "multimodal_request_requires_fresh_visual_prefill");
        }
        g_llm->set_config(g_last_config_json);
        if (!textOnlyRequest) {
            auto visualContent = prepare_mnn_visual_response(parsed);
            set_error("");
            g_sync_stepping = false;
            {
                std::lock_guard<std::mutex> stats_lock(g_visual_stats_mutex);
                g_visual_stats_json = mca::mnn::make_deferred_visual_stats_json(
                        stats_json_locked(),
                        g_generation_sequence + 1);
            }
            visualStream->filter.reset(g_active_stop_markers);
            std::atomic_store(&g_visual_stream, visualStream);
            g_visual_producer_running.store(true, std::memory_order_release);
            try {
                g_visual_producer = std::thread([
                        parsed = std::move(parsed), visualContent = std::move(visualContent), visualStream]() mutable {
                    std::string producerError;
                    {
                        std::lock_guard<std::mutex> model_lock(g_mnn_mutex);
                        try {
                            if (!g_stop_signal.load(std::memory_order_acquire)) {
                                begin_mnn_response(parsed, &visualContent);
                            } else {
                                visualStream->queue.cancel();
                            }
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            g_generation_stop_reason = g_stop_requested
                                    ? "stop_requested"
                                    : visualStream->queue.stoppedAfterMarker()
                                            ? "stop_marker"
                                            : generation_stop_reason_locked();
                            mark_mnn_prompt_cache_disabled_locked(
                                    true, "multimodal_completed_cache_cleared");
                            if (!g_stop_requested && !visualStream->queue.stoppedAfterMarker() &&
                                (g_generation_stop_reason == "mnn_internal_error" ||
                                 g_generation_stop_reason == "mnn_timeout" ||
                                 g_generation_stop_reason == "mnn_user_cancel" ||
                                 g_generation_stop_reason == "mnn_not_loaded")) {
                                producerError = "MNN visual decode failed: " + g_generation_stop_reason;
                            }
                        } catch (const std::exception& e) {
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            if (g_stop_requested || visualStream->queue.stoppedAfterMarker()) {
                                g_generation_stop_reason = g_stop_requested ? "stop_requested" : "stop_marker";
                            } else {
                                producerError = std::string("MNN visual generation failed: ") + e.what();
                                g_generation_stop_reason = "generate_failed";
                            }
                            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
                        } catch (...) {
                            g_generation_active = false;
                            g_stop_requested = g_stop_signal.load(std::memory_order_acquire);
                            if (g_stop_requested || visualStream->queue.stoppedAfterMarker()) {
                                g_generation_stop_reason = g_stop_requested ? "stop_requested" : "stop_marker";
                            } else {
                                producerError = "MNN visual generation failed: unknown native error";
                                g_generation_stop_reason = "generate_failed";
                            }
                            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
                        }
                        if (!producerError.empty()) set_error(producerError);
                        update_visual_stats_locked();
                    }
                    g_visual_producer_running.store(false, std::memory_order_release);
                    visualStream->queue.finish(std::move(producerError));
                });
            } catch (...) {
                g_visual_producer_running.store(false, std::memory_order_release);
                std::atomic_store(&g_visual_stream, std::shared_ptr<VisualStreamState>{});
                throw;
            }
            ++g_generation_sequence;
            return 0;
        }
        bool completedSynchronously = false;
        try {
            completedSynchronously = begin_mnn_response(parsed);
        } catch (const std::exception& firstError) {
            // A stale MNN prompt/KV transaction can make the first prefill
            // fail with the generic -203 bridge code.  Text requests are
            // retryable because no visual executor state is involved: clear
            // only the cached transaction, reset the decoder, and perform one
            // clean prefill.  Visual requests remain fail-closed and are not
            // retried here because resetting Omni can invalidate image VARPs.
            if (!textOnlyRequest || g_llm == nullptr) {
                throw;
            }
            const std::string firstMessage = firstError.what();
            rollback_mnn_text_prompt_cache_locked("prefill_retry_after_cache_error", true);
            g_llm->reset();
            g_pending_chunk.clear();
            g_pending_utf8_tail.clear();
            g_stream_buffer = std::make_unique<MnnStreamBuffer>();
            g_output_stream = std::make_unique<std::ostream>(g_stream_buffer.get());
            g_llm->set_config(g_last_config_json);
            try {
                completedSynchronously = begin_mnn_response(parsed);
            } catch (const std::exception& retryError) {
                throw std::runtime_error(
                        "MNN text prefill failed after one clean KV retry. first=" +
                        firstMessage + "; retry=" + retryError.what());
            }
        }
        g_sync_stepping = !completedSynchronously;
        if (completedSynchronously) {
            // Leave the buffered native output untouched here. The first
            // generateNextChunk() call owns protocol/EOS filtering and drains
            // it exactly once; pre-filtering would feed the visible text back
            // through an already-stopped filter and drop the whole answer.
            g_generation_active = false;
            g_generation_stop_reason = generation_stop_reason_locked();
            if (requestHasMedia) {
                // response(string) has already performed the visual request;
                // invalidate any prior text transcript without calling reset()
                // on the visual executor in this lifecycle boundary.
                mark_mnn_prompt_cache_disabled_locked(
                        true,
                        "multimodal_completed_cache_cleared");
            } else {
                settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);
            }
        } else {
            if (textOnlyRequest) {
                std::string prefillError;
                if (!capture_mnn_text_prefill_locked(prefillError)) {
                    throw std::runtime_error(prefillError);
                }
            }
            filter_mnn_stop_markers_locked(false);
            restore_stepping_status_if_needed_locked();
        }
        set_error("");
        // No failing begin path remains after this point.
        ++g_generation_sequence;
        return 0;
    } catch (const std::exception& e) {
        g_generation_active = false;
        const std::string detail = e.what();
        if (requestHasMedia) {
            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
        } else {
            rollback_mnn_text_prompt_cache_locked("begin_failed", true);
        }
        g_generation_stop_reason = detail.find("mnn_multimodal_prefill_failed") != std::string::npos
                ? "prefill_failed"
                : "begin_failed";
        const std::string backend = g_mnn_backend_type.empty() ? "cpu" : g_mnn_backend_type;
        const std::string contextLog = mnn_context_log_snapshot(
                g_llm == nullptr ? nullptr : g_llm->getContext());
        set_error(
                "MNN beginCompletion exception (backend=" + backend + "): " + detail +
                (contextLog.empty() ? std::string() : " MNN log: " + contextLog));
        return kMnnBeginFailed;
    } catch (...) {
        g_generation_active = false;
        if (requestHasMedia) {
            mark_mnn_prompt_cache_disabled_locked(true, "multimodal_begin_failed");
        } else {
            rollback_mnn_text_prompt_cache_locked("begin_failed", true);
        }
        g_generation_stop_reason = "begin_failed";
        set_error(
                "MNN beginCompletion exception (backend=" +
                (g_mnn_backend_type.empty() ? std::string("cpu") : g_mnn_backend_type) +
                "): unknown native error");
        return kMnnBeginFailed;
    }
#else
    set_error("MNN generation requested before the official MNN-LLM executor is linked.");
    return kMnnRunnerUnavailable;
#endif
}

}  // namespace chat_session
}  // namespace mnn
}  // namespace mca
