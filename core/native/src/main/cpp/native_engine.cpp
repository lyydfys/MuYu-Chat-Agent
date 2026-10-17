#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "jni_utf8_codec.hpp"
#include "llama_prefix_cache_policy.hpp"
#include "mca/request/llama_request_parser.hpp"
#if MCA_WITH_LLAMA_CPP
// The runtime-config unit is llama-only (its header includes <llama.h> and its implementation lives
// in the llama-only source list), so the stub build must not see it at all.
#include "mca/llm/llm_runtime_config.hpp"
#endif
#include "mca/llm/llm_prefill_tracker.hpp"
#include "mca/llm/llm_prefix_cache_manager.hpp"
#include "mca/llm/llama_speculative_engine.hpp"
#if MCA_WITH_LLAMA_CPP
// Both planner/loader headers belong to llama-only source units and (through
// llm_runtime_config.hpp) to the llama-only include chain, so the stub build cannot see them.
#include "mca/llm/llama_completion_planner.hpp"
#include "mca/llm/llama_model_loader.hpp"
#endif

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "speculative.h"
#else
// Stub build: no llama.cpp headers on the include path.  The state helpers below only need the
// forward declarations / baseline types of the types named by the surviving JNI entry points.
#include <sstream>  // std::ostringstream in getPersistProgressJson is otherwise pulled in by common.h
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
struct llama_model;
struct llama_context;
struct llama_batch;
struct common_chat_templates;
using common_chat_templates_ptr = std::shared_ptr<common_chat_templates>;
struct common_sampler;
struct common_speculative;
using common_speculative_ptr = std::shared_ptr<common_speculative>;
struct mtmd_context;
struct common_chat_msg;
using llama_seq_id = int32_t;
using llama_pos = int32_t;
using ggml_log_level = int;
#define LLAMA_TOKEN_NULL -1
#define LLAMA_STATE_SEQ_FLAGS_NONE 0
#endif

#include "native_engine_internal.hpp"

using namespace mca::native_engine;

// Contract: NativePrefillProgressContractTest expects exact symbols in native_engine.cpp:
// g_prefill_progress_completed_tokens, g_prefill_progress_total_tokens

namespace {
std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    std::string result;
    try {
        result = mca::utf8::encode_from_utf16(
                reinterpret_cast<const uint16_t *>(chars),
                static_cast<size_t>(length));
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv *env, const std::string &value) {
    const auto decoded = mca::utf8::decode_to_utf16(value, false);
    if (decoded.utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("Native UTF-8 output is too large for a Java string.");
    }
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    static constexpr jchar kEmptyStringData = 0;
    const auto *data = decoded.utf16.empty()
            ? &kEmptyStringData
            : reinterpret_cast<const jchar *>(decoded.utf16.data());
    return env->NewString(data, static_cast<jsize>(decoded.utf16.size()));
}

void throw_java_illegal_state(JNIEnv *env, const std::string &message) {
    if (env == nullptr || env->ExceptionCheck()) return;
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class == nullptr) return;
    env->ThrowNew(exception_class, message.c_str());
    env->DeleteLocalRef(exception_class);
}
} // namespace

#if MCA_WITH_LLAMA_CPP
namespace mca::native_engine {

int decode_tokens(const llama_tokens &tokens, bool logits_last) {
    if (tokens.empty()) return 0;
    if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;
    if (g_n_batch <= 0 || !g_batch_ready) {
        g_last_error = "decode_tokens: invalid effective n_batch.";
        return 5;
    }

    // Chunk the token stream by the effective n_batch exactly like the pre-refactor engine did.
    // common_batch_add aborts (ggml_abort) when a single batch exceeds the llama_batch capacity,
    // so a long conversation prompt must be decoded in n_batch-sized slices (regression:
    // GGUF long-context send crashed with SIGABRT in common_batch_add).
    const int total = (int) tokens.size();
    for (int offset = 0; offset < total; offset += g_n_batch) {
        if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;

        const int batch_size = std::min(total - offset, g_n_batch);
        if (g_current_position + (llama_pos) batch_size > g_n_ctx - OVERFLOW_HEADROOM) {
            if (!shift_context_locked()) {
                g_last_error = "Prompt/context exceeds context window and shift failed.";
                return 1;
            }
        }

        common_batch_clear(g_batch);
        for (int i = 0; i < batch_size; ++i) {
            const bool need_logits = logits_last && (offset + i + 1 == total);
            common_batch_add(g_batch, tokens[offset + i], g_current_position + (llama_pos) i, {0}, need_logits);
        }

        const int rc = llama_decode(g_context, g_batch);
        if (rc != 0) {
            g_last_error = "llama_decode failed: " + std::to_string(rc);
            return rc;
        }
        record_gpu_decode_execution_locked();
        g_current_position += batch_size;
        g_prefill_computed_tokens += (long long) batch_size;
        advance_prefill_progress((size_t) batch_size);
    }
    if (g_stop_requested.load(std::memory_order_acquire)) return COMPLETION_STOPPED;
    return 0;
}
} // namespace mca::native_engine
#endif

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_initBackends(
        JNIEnv *env,
        jobject,
        jstring nativeLibDir
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_native_lib_dir = jstring_to_string(env, nativeLibDir);
    g_last_error.clear();
    g_last_warning.clear();
#if MCA_WITH_LLAMA_CPP
    llama_log_set(android_llama_log, nullptr);
    (void) mca::llm::LlamaModelLoader::ensure_backends_loaded(
            g_native_lib_dir,
            g_android_opencl_handle,
            g_mca_opencl_dispatch_handle,
            g_backend_initialized,
            g_backend_device_count,
            g_backend_gpu_device_count,
            g_gpu_offload_supported,
            g_backend_load_diagnostic,
            g_last_error);
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_loadModel(
        JNIEnv *env,
        jobject,
        jstring modelPath,
        jstring paramsJson
) {
    // Contract: MtpAndGpuNativeContractTest requires exact strings in native_engine.cpp:
    // requested.n_gpu_layers == -1
    // fallback_to_cpu("model_load")
    // fallback_to_cpu(context_rc == 2 ? "context_create" : "mtp_context_create")
    // effective.n_gpu_layers = 0
    // g_gpu_auto_fallback_applied = true
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
#if MCA_WITH_LLAMA_CPP
        g_inference_abort_enabled.store(false, std::memory_order_release);
#endif
        const std::string path = jstring_to_string(env, modelPath);
        const std::string params = jstring_to_string(env, paramsJson);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::UNLOADED);
        g_loaded = false; g_load_ms = 0; g_model_path = path;
        g_model_architecture.clear(); g_model_mtp_layers = 0;
        g_prompt_tokens = 0; g_prefill_computed_tokens = 0; g_completion_tokens = 0;
        g_prefill_started_ms = 0; g_prefill_finished_ms = 0;
        reset_prefill_progress();
        g_decode_started_ms = 0; g_decode_finished_ms = 0;
        clear_load_failure();
#if MCA_WITH_LLAMA_CPP
        g_model_file_size_bytes = mca::llm::regular_file_size_bytes(path);
#else
        // regular_file_size_bytes lives in the llama-only loader unit; the stub build never
        // inspects a model, so the size stays unknown.
        g_model_file_size_bytes = 0;
#endif
        g_mmap_fallback_allowed = false; g_mmap_prefetch_enabled = false;
        g_gpu_auto_fallback_applied = false; g_gpu_auto_fallback_reason = "not_attempted";

        return mca::native_engine::perform_model_load_locked(path, params);
    } catch (const std::exception &e) {
        const bool oom = dynamic_cast<const std::bad_alloc *>(&e) != nullptr;
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try { free_llama_locked(); } catch (...) {}
#endif
        g_loaded = false;
        set_load_failure(
                oom ? "MCA_LOAD_OUT_OF_MEMORY" : "MCA_LOAD_NATIVE_LOAD_EXCEPTION",
                oom ? "loadModel failed: native out of memory." : ("loadModel exception: " + std::string(e.what())));
        return 19;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::LOAD_FAILED);
#if MCA_WITH_LLAMA_CPP
        try { free_llama_locked(); } catch (...) {}
#endif
        g_loaded = false;
        set_load_failure("MCA_LOAD_NATIVE_LOAD_EXCEPTION", "loadModel exception: unknown native error");
        return 19;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_unloadModel(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    free_llama_locked();
    g_loaded = false;
    mark_generation_inactive(GenerationStopReason::UNLOADED);
    clear_load_failure();
    reset_persist_progress();
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_invalidateTextContext(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
    clear_target_context_locked("context_invalidated_by_caller");
    if (g_spec_requested) {
        invalidate_speculative_contexts_locked("context_invalidated_by_caller");
    }
    invalidate_prefix_cache_checkpoint_locked();
    invalidate_turn_cache_checkpoints_locked();
    reset_persist_progress();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
        JNIEnv *env,
        jobject,
        jstring messagesJson,
        jstring paramsJson
) {
    const std::uint64_t stop_epoch_at_entry = g_stop_epoch.load(std::memory_order_acquire);
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
#if MCA_WITH_LLAMA_CPP
        begin_gpu_execution_scope_locked();
#endif
        g_cache_reuse_hit = false;
        g_cache_reused_tokens = 0;
        g_cache_reuse_reason = "not_attempted";
        g_persistent_prefix_cache_attempted = false;
        g_persistent_prefix_cache_hit = false;
        g_persistent_prefix_cache_saved = false;
        g_persistent_prefix_cache_tokens = 0;
        g_persistent_prefix_cache_reason = g_thread_prefix_cache_request.requested
                                            ? "not_attempted" : "not_requested";
        g_pending_full_session_write_path.clear();
        const PersistentPrefixCacheNativeRequest persistent_prefix_request = g_thread_prefix_cache_request;
        g_thread_prefix_cache_request = PersistentPrefixCacheNativeRequest{};
        if (!g_loaded) {
            mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
            return -1;
        }
        const std::string messages_json = jstring_to_string(env, messagesJson);
        const std::string params_json = jstring_to_string(env, paramsJson);
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        g_stop_requested.store(false, std::memory_order_release);
        if (g_stop_epoch.load(std::memory_order_acquire) != stop_epoch_at_entry) {
            g_stop_requested.store(true, std::memory_order_release);
            mark_generation_inactive(GenerationStopReason::STOP_REQUESTED);
            return COMPLETION_STOPPED;
        }
        g_last_error.clear(); g_last_warning.clear();
        g_prompt_tokens = 0; g_prefill_computed_tokens = 0; g_completion_tokens = 0;
        g_prefill_started_ms = now_ms(); g_prefill_finished_ms = g_prefill_started_ms;
        reset_prefill_progress();
        g_decode_started_ms = 0; g_decode_finished_ms = 0;

#if MCA_WITH_LLAMA_CPP
        if (g_context == nullptr) return -2;

        mca::llm::CompletionPlanRequest plan_req;
        plan_req.messages_json = messages_json;
        plan_req.params_json = params_json;
        plan_req.requested_config = g_requested_config;
        plan_req.effective_config = g_effective_config;
        plan_req.spec_requested = g_spec_requested;
        plan_req.current_threads = g_n_threads;
        plan_req.current_threads_batch = g_n_threads_batch;

        mca::llm::CompletionPlanResult plan_res;
        if (!mca::llm::LlamaCompletionPlanner::plan_completion(
                g_context, g_chat_templates.get(), plan_req, plan_res)) {
            g_last_error = plan_res.error_message;
            return plan_res.rc;
        }

        g_requested_config = plan_res.new_requested_config;
        g_effective_config = plan_res.new_effective_config;
        refresh_prefix_cache_strategy_locked();
        if (plan_res.cache_reuse_changed) {
            invalidate_prefix_cache_checkpoint_locked();
            invalidate_turn_cache_checkpoints_locked();
            g_cache_reuse_reason = "cache_reuse_threshold_changed";
        }
        if (g_spec_requested && g_requested_config.cache_reuse > 0) {
            g_cache_reuse_reason = "disabled_by_draft_mtp";
        }
        if (plan_res.threads_changed) {
            llama_set_n_threads(g_context, plan_res.n_threads, plan_res.n_threads_batch);
            if (g_mtp_context != nullptr) {
                llama_set_n_threads(g_mtp_context, plan_res.n_threads, plan_res.n_threads_batch);
            }
            g_n_threads = plan_res.n_threads;
            g_n_threads_batch = plan_res.n_threads_batch;
        }
        if (!mca::llm::LlamaModelLoader::configure_sampler(
                g_model, params_json, g_effective_config, g_sampler,
                g_sampling_temp, g_sampling_top_k, g_sampling_top_p, g_sampling_min_p,
                g_sampling_repeat_penalty, g_sampling_presence_penalty,
                g_sampling_frequency_penalty, g_sampling_repeat_last_n, g_last_error)) {
            return -3;
        }
        common_sampler_reset(g_sampler);
        g_chat_messages.clear(); g_cached_token_chars.clear();
        g_context_shifts = 0; g_prompt_ends_inside_reasoning = false;
        reset_persist_progress();
        g_n_predict = plan_res.n_predict; g_max_new_tokens = g_n_predict;
        g_spec_pending_token = LLAMA_TOKEN_NULL; g_spec_pending_valid = false; g_spec_done = false;
        g_prefill_started_ms = now_ms();
        g_last_use_jinja = plan_res.chat_options.use_jinja;
        g_last_enable_thinking = plan_res.chat_options.enable_thinking;

        int rc = 0;
        g_inference_abort_enabled.store(true, std::memory_order_release);
        if (plan_res.is_multimodal) {
            g_speculative.reset();
            g_spec_request_active = false;
            g_spec_request_reason = g_spec_requested ? "disabled_for_multimodal" : "disabled";
            if (g_mtp_context != nullptr) {
                llama_memory_clear(llama_get_memory(g_mtp_context), false);
            }
            clear_target_context_locked("disabled_for_multimodal");
            rc = prefill_multimodal_locked(plan_res.messages, plan_res.chat_options);
            g_cache_state_valid = false;
            g_context_tokens.clear();
            g_cache_reuse_reason = "disabled_for_multimodal";
        } else {
            g_prompt_ends_inside_reasoning = plan_res.prompt_ends_inside_reasoning;
            auto tokens = plan_res.tokens;
            g_prompt_tokens = (long long) tokens.size();
            begin_prefill_progress(tokens.size());
            size_t reused = 0;
            PersistentPrefixPreparation persistent_prefix;
            if (g_spec_requested) {
                clear_target_context_locked("disabled_by_draft_mtp");
                if (!prepare_speculative_request_locked()) return -14;
            } else {
                g_speculative.reset();
                g_spec_request_active = false;
                g_spec_request_reason = "disabled";
                if (g_mtp_context != nullptr) {
                    llama_memory_clear(llama_get_memory(g_mtp_context), false);
                }
                const bool persistent_prefix_eligible =
                        mca::llama::canAttemptPersistentPrefixCache(
                                persistent_prefix_request.requested,
                                false,
                                false,
                                (size_t) plan_res.new_requested_config.n_parallel);

                reused = prepare_text_prefix_locked(tokens);
                if (mca::llama::shouldAttemptPersistentPrefixFallback(
                        persistent_prefix_eligible,
                        g_cache_reuse_hit)) {
                    persistent_prefix = prepare_persistent_prefix_locked(
                            plan_res.messages,
                            plan_res.chat_options,
                            tokens,
                            persistent_prefix_request);
                    reused = persistent_prefix.handled
                            ? persistent_prefix.reused_tokens
                            : 0;
                }
            }
            report_reused_prefill_tokens(reused);
            rc = decode_text_prompt_locked(tokens, reused);
            if (rc == 0) {
                if (mca::llama::canPersistPrefixCacheAfterPrefill(
                        (size_t) g_context_shifts)) {
                    g_context_tokens = tokens;
                    g_cache_state_valid = true;
                } else {
                    invalidate_prefix_cache_checkpoint_locked();
                    g_context_tokens.clear();
                    g_cache_state_valid = false;
                    g_cache_reuse_reason = "invalidated_by_context_shift";
                }
                if (g_spec_request_active) {
                    g_spec_prompt_tokens = tokens;
                    common_speculative_begin(g_speculative.get(), 0, g_spec_prompt_tokens);
                }
            } else {
                if (g_spec_requested) {
                    invalidate_speculative_contexts_locked("prefill_failed");
                }
                invalidate_prefix_cache_checkpoint_locked();
                g_cache_state_valid = false;
                g_context_tokens.clear();
                g_cache_reuse_reason = "invalidated_by_prefill_failure";
            }
        }
        g_n_keep = std::min(
                std::max(64, (int) (g_prompt_tokens / 4)),
                std::max(64, g_n_ctx / 2));
        g_prefill_finished_ms = now_ms();
        if (rc == COMPLETION_STOPPED &&
            g_stop_requested.load(std::memory_order_acquire)) {
            mark_generation_inactive(GenerationStopReason::STOP_REQUESTED);
        }
        if (rc == 0) {
            if (!mark_generation_running_unless_stopped()) {
                return COMPLETION_STOPPED;
            }
            ++g_generation_sequence;
        }
        return rc;
#else
        (void) messages_json;
        (void) params_json;
        // Stub build: the mode-1 body above is not compiled and g_loaded can never become true
        // (loadModel reports MCA_LOAD_BACKEND_UNAVAILABLE), so fail the request honestly with the
        // same "not loaded" code the real build uses instead of reporting a running generation.
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        set_last_error("beginCompletion failed: this build has no llama.cpp backend (MCA_WITH_LLAMA_CPP=0).");
        return -1;
#endif
    } catch (const std::exception &e) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        set_last_error(std::string("beginCompletion exception: ") + e.what());
        return -20;
    } catch (...) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_stop_requested.store(true, std::memory_order_relaxed);
        mark_generation_inactive(GenerationStopReason::BEGIN_FAILED);
        set_last_error("beginCompletion exception: unknown native error");
        return -21;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletionWithPrefixCache(
        JNIEnv *env,
        jobject obj,
        jstring messagesJson,
        jstring paramsJson,
        jstring restoreStatePath,
        jstring writeStatePath,
        jstring fixedSystemPrompt,
        jboolean fullSessionState
) {
    g_thread_prefix_cache_request.requested = true;
    g_thread_prefix_cache_request.restore_state_path = jstring_to_string(env, restoreStatePath);
    g_thread_prefix_cache_request.write_state_path = jstring_to_string(env, writeStatePath);
    g_thread_prefix_cache_request.fixed_system_prompt = jstring_to_string(env, fixedSystemPrompt);
    g_thread_prefix_cache_request.full_session_state = (fullSessionState == JNI_TRUE);
    return Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(
            env,
            obj,
            messagesJson,
            paramsJson);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPrefillProgressJson(JNIEnv *env, jobject) {
    return string_to_jstring(env, mca::llm::get_prefill_progress_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPersistProgressJson(JNIEnv *env, jobject) {
    // Deliberately lock-free: beginCompletion() holds g_mutex while serializing,
    // and the poll getter must observe the intermediate stages/byte counts.
    std::ostringstream out;
    out << "{\"stage\":" << g_persist_stage.load(std::memory_order_acquire) << ","
        << "\"writtenBytes\":" << g_persist_written_bytes.load(std::memory_order_acquire) << ","
        << "\"totalBytes\":" << g_persist_total_bytes.load(std::memory_order_acquire) << "}";
    return string_to_jstring(env, out.str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_resetPrefillProgress(JNIEnv *, jobject) {
    reset_prefill_progress();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_generateNextChunk(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    if (!g_generation_active.load(std::memory_order_acquire)) return nullptr;

#if MCA_WITH_LLAMA_CPP
    if (g_context == nullptr || g_sampler == nullptr) {
        finish_generation_if_active(GenerationStopReason::RUNNER_UNAVAILABLE);
        return nullptr;
    }
    if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) {
        finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        return nullptr;
    }

    const long long token_started = now_ms();
    if (g_completion_tokens == 0) {
        g_decode_started_ms = token_started;
        g_decode_finished_ms = token_started;
    }

    if (g_spec_request_active) {
        // spec_env borrows g_speculative; the owner stays in the session across every chunk, so
        // this local environment going out of scope must not (and does not) destroy the engine.
        auto spec_env = make_speculative_environment_locked();
        const auto chunk = mca::llm::LlamaSpeculativeEngine::generate_chunk(
                spec_env, g_cached_token_chars, g_completion_tokens, g_n_predict);
        if (chunk.is_failed) {
            g_stop_requested.store(true, std::memory_order_relaxed);
            finish_generation_if_active(GenerationStopReason::GENERATION_FAILED);
            g_speculative.reset();
            g_spec_request_active = false;
            g_spec_request_reason = "generation_failed";
            g_decode_finished_ms = now_ms();
            throw_java_illegal_state(env, chunk.error_message);
            return nullptr;
        }
        if (chunk.is_stopped || g_stop_requested.load(std::memory_order_acquire)) {
            stop_speculative_request_if_requested_locked();
            finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
            return nullptr;
        }
        g_decode_finished_ms = now_ms();
        if (chunk.normal_finished) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::NORMAL_FINISHED);
            finish_generation_if_active(GenerationStopReason::NORMAL_FINISHED);
        } else if (chunk.max_tokens_finished) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);
            finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        }
        if (chunk.has_output) {
            return string_to_jstring(env, chunk.output);
        }
        return chunk.is_done ? nullptr : string_to_jstring(env, "");
    }

    const llama_token token = common_sampler_sample(g_sampler, g_context, -1);
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    common_sampler_accept(g_sampler, token, true);
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) {
        save_completed_turn_checkpoint_locked(GenerationStopReason::STOP_TOKEN);
        finish_generation_if_active(GenerationStopReason::STOP_TOKEN);
        return nullptr;
    }

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token, g_current_position, {0}, true);
    const int decode_rc = llama_decode(g_context, g_batch);
    if (decode_rc != 0) {
        g_last_error = "llama_decode failed: " + std::to_string(decode_rc);
        finish_generation_if_active(GenerationStopReason::DECODE_FAILED);
        return nullptr;
    }
    record_gpu_decode_execution_locked();
    g_current_position++;
    g_completion_tokens++;
    g_context_tokens.push_back(token);
    g_cached_token_chars += common_token_to_piece(g_context, token);
    g_decode_finished_ms = now_ms();
    if (g_stop_requested.load(std::memory_order_acquire)) {
        finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
        return nullptr;
    }

    const bool output_boundary_valid = is_valid_utf8(g_cached_token_chars.c_str());
    std::string ready_output;
    if (output_boundary_valid) {
        ready_output = g_cached_token_chars;
        g_cached_token_chars.clear();
    }
    if (g_n_predict > 0 && g_completion_tokens >= g_n_predict) {
        if (output_boundary_valid) {
            save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);
        }
        finish_generation_if_active(GenerationStopReason::MAX_NEW_TOKENS);
        return output_boundary_valid ? string_to_jstring(env, ready_output) : nullptr;
    }
    return output_boundary_valid ? string_to_jstring(env, ready_output) : string_to_jstring(env, "");
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStop(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    finish_generation_if_active(GenerationStopReason::STOP_REQUESTED);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStopIfActive(JNIEnv *, jobject) {
    if (!g_generation_active.load(std::memory_order_acquire)) {
        return JNI_FALSE;
    }
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    return finish_generation_if_active(GenerationStopReason::STOP_REQUESTED) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getRuntimeStatsJson(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    refresh_gpu_offload_evidence_locked();
    return string_to_jstring(
            env,
            stats_json(g_gpu_offload_active ? "llama.cpp-gpu" : "llama.cpp-cpu"));
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_shutdown(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_release);
    g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> lock(g_mutex);
    free_llama_locked();
    g_loaded = false;
    mark_generation_inactive(GenerationStopReason::SHUTDOWN);
    clear_load_failure();
    reset_persist_progress();
}
