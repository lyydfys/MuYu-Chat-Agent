#ifndef MCA_NATIVE_ENGINE_INTERNAL_HPP
#define MCA_NATIVE_ENGINE_INTERNAL_HPP
// Shared declarations for the split native_engine translation units (T2B16 R7-C5).
// State is defined exactly once, in native_engine_runtime_state.cpp; every other
// unit sees the declarations below.  The stub-build macros/typedefs mirror the
// pre-split native_engine.cpp stub block so this header works in both modes.
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama_prefix_cache_policy.hpp"
#include "mca/llm/llama_gpu_evaluator.hpp"
#include "mca/llm/llama_speculative_engine.hpp"
#include "mca/llm/llm_prefix_cache_manager.hpp"
#include "mca/llm/llm_stats_collector.hpp"
#include "mca/llm/llm_turn_cache.hpp"
#include "mca/request/llama_request_parser.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "chat.h"
#include "speculative.h"
#include "mtmd.h"
#else
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
using llama_pos = int32_t;
struct llama_context;
struct llama_model;
struct llama_batch;
struct common_chat_templates;
using common_chat_templates_ptr = std::shared_ptr<common_chat_templates>;
struct common_sampler;
struct common_speculative;
using common_speculative_ptr = std::shared_ptr<common_speculative>;
struct mtmd_context;
struct common_chat_msg;
using llama_seq_id = int32_t;
using ggml_log_level = int;
#define LLAMA_TOKEN_NULL -1
#define LLAMA_STATE_SEQ_FLAGS_NONE 0
#endif

namespace mca::native_engine {

using mca::request::ParsedMessage;
using mca::request::is_valid_utf8;
using mca::llm::ChatTemplateOptions;
using mca::llm::PersistentPrefixCacheNativeRequest;
using PersistentPrefixPreparation = mca::llm::PrefixCacheDecision;

enum class GenerationStopReason : int {
    IDLE = 0, RUNNING, STOP_REQUESTED, STOP_TOKEN, MAX_NEW_TOKENS,
    NORMAL_FINISHED, RUNNER_UNAVAILABLE, BEGIN_FAILED, LOAD_FAILED,
    GENERATION_FAILED, CONTEXT_SHIFT_FAILED, DECODE_FAILED, UNLOADED, SHUTDOWN,
};

inline constexpr int COMPLETION_STOPPED = 3;

inline constexpr int OVERFLOW_HEADROOM = 4;

extern std::mutex g_mutex;
extern std::atomic_bool g_stop_requested;
extern std::atomic<std::uint64_t> g_stop_epoch;
extern std::atomic_bool g_generation_active;
extern std::atomic<GenerationStopReason> g_generation_stop_reason;

extern bool g_loaded;
extern long long g_load_ms, g_prompt_tokens, g_prefill_computed_tokens, g_completion_tokens;
extern long long g_context_shifts, g_prefill_started_ms, g_prefill_finished_ms;
extern long long g_decode_started_ms, g_decode_finished_ms;
extern std::uint64_t g_generation_sequence;
extern std::string g_model_path, g_last_error, g_last_warning, g_load_failure_code, g_native_lib_dir, g_backend_load_diagnostic;
extern std::uint64_t g_model_file_size_bytes;
extern bool g_mmap_fallback_allowed, g_mmap_prefetch_enabled;

extern std::atomic<int> g_persist_stage;
extern std::atomic<std::uint64_t> g_persist_written_bytes, g_persist_total_bytes;

extern void *g_android_opencl_handle, *g_mca_opencl_dispatch_handle;
using mca::llm::OpenClBeginExecutionScope;
using mca::llm::OpenClExecutionSnapshot;
extern OpenClBeginExecutionScope g_opencl_begin_execution_scope;
extern OpenClExecutionSnapshot g_opencl_execution_snapshot;
extern std::uint64_t g_opencl_execution_scope, g_opencl_kernel_submissions, g_opencl_kernel_completions;
extern bool g_opencl_execution_telemetry_available, g_gpu_decode_succeeded, g_backend_initialized;
extern std::size_t g_backend_device_count;
extern int g_n_threads, g_n_threads_batch, g_n_batch, g_n_ubatch, g_max_new_tokens;
extern float g_sampling_temp, g_sampling_top_p, g_sampling_min_p;
extern int g_sampling_top_k, g_sampling_repeat_last_n;
extern float g_sampling_repeat_penalty, g_sampling_presence_penalty, g_sampling_frequency_penalty;
extern bool g_last_use_jinja, g_last_enable_thinking, g_prompt_ends_inside_reasoning, g_vision_ready;
extern std::string g_mmproj_path;

extern mca::llm::RuntimeConfig g_requested_config, g_effective_config;
extern std::size_t g_backend_gpu_device_count;
extern bool g_gpu_offload_supported, g_gpu_offload_active;
extern bool g_gpu_offload_allocation_observed, g_gpu_offload_execution_observed, g_gpu_offload_layers_known;
extern std::size_t g_gpu_offload_bytes, g_gpu_offload_model_bytes, g_gpu_offload_context_bytes, g_gpu_offload_compute_bytes;
extern int g_gpu_offload_layers;
extern bool g_gpu_auto_fallback_applied;
extern std::string g_gpu_auto_fallback_reason;
extern bool g_cache_state_valid, g_cache_reuse_hit;
extern int g_cache_reused_tokens;
extern long long g_cache_reuse_hits, g_cache_reuse_misses;
extern std::string g_cache_reuse_reason, g_cache_reuse_strategy;
extern bool g_persistent_prefix_cache_attempted, g_persistent_prefix_cache_hit, g_persistent_prefix_cache_saved;
extern int g_persistent_prefix_cache_tokens;
extern std::string g_persistent_prefix_cache_reason;
extern bool g_cache_checkpoint_valid;
extern std::size_t g_cache_checkpoint_tokens, g_cache_checkpoint_bytes;
extern std::size_t g_turn_cache_checkpoint_count, g_turn_cache_checkpoint_bytes;
extern long long g_turn_cache_checkpoint_hits, g_turn_cache_checkpoint_misses;
extern bool g_model_hybrid, g_model_recurrent;
extern bool g_spec_requested, g_spec_request_active, g_spec_context_ready, g_spec_done;
extern long long g_spec_drafted_tokens, g_spec_accepted_tokens, g_spec_steps;
extern std::string g_spec_request_reason, g_model_architecture;
extern int g_model_mtp_layers;
extern std::string g_pending_full_session_write_path;
extern thread_local PersistentPrefixCacheNativeRequest g_thread_prefix_cache_request;

#if MCA_WITH_LLAMA_CPP
extern std::atomic_bool g_inference_abort_enabled;
extern llama_model *g_model;
extern llama_context *g_context;
extern llama_context *g_mtp_context;
extern llama_batch g_batch;
extern bool g_batch_ready;
extern common_chat_templates_ptr g_chat_templates;
extern common_sampler *g_sampler;
extern common_speculative_ptr g_speculative;
extern mtmd_context *g_mtmd_context;
extern std::vector<common_chat_msg> g_chat_messages;
extern llama_tokens g_context_tokens;
extern llama_tokens g_cache_checkpoint_prefix;
extern std::vector<std::uint8_t> g_cache_checkpoint_data;
extern std::size_t g_cache_checkpoint_threshold;
extern std::vector<mca::llm::TurnCacheCheckpoint> g_turn_cache_checkpoints;
extern llama_tokens g_spec_prompt_tokens, g_spec_draft_tokens;
extern llama_token g_spec_pending_token;
extern bool g_spec_pending_valid;
extern llama_pos g_current_position;
extern int g_n_ctx, g_n_predict, g_n_keep;
extern std::string g_cached_token_chars;
#endif

std::size_t prepare_text_prefix_locked(const llama_tokens &tokens);
int decode_text_prompt_locked(const llama_tokens &tokens, std::size_t reused);

int decode_tokens(const llama_tokens &tokens, bool logits_last);
long long now_ms();

void set_last_error(const std::string &message);
void clear_load_failure();
void set_load_failure_code(const char *code);
void set_load_failure(const char *code, const std::string &message);
const char *generation_stop_reason_name(GenerationStopReason reason);
void mark_generation_inactive(GenerationStopReason reason);
bool mark_generation_running_unless_stopped();
bool finish_generation_if_active(GenerationStopReason reason);
void reset_persist_progress();
void reset_prefill_progress();
void begin_prefill_progress(std::size_t total_tokens, std::size_t completed_tokens = 0);
void advance_prefill_progress(std::size_t decoded_tokens);
void report_reused_prefill_tokens(std::size_t reused_tokens);

void free_llama_locked();
bool shift_context_locked();
void clear_target_context_locked(const std::string &reason);
bool trim_context_locked(llama_context *ctx, llama_pos position, const char *label);

void refresh_prefix_cache_strategy_locked();
void invalidate_prefix_cache_checkpoint_locked();
void invalidate_turn_cache_checkpoints_locked();
PersistentPrefixPreparation prepare_persistent_prefix_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        const llama_tokens &full_tokens,
        const PersistentPrefixCacheNativeRequest &request);
void save_completed_turn_checkpoint_locked(GenerationStopReason reason);
int prefill_multimodal_locked(
        const std::vector<ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options);
mca::llm::SpeculativeEnvironment make_speculative_environment_locked();
bool prepare_speculative_request_locked();
void invalidate_speculative_contexts_locked(const std::string &reason);
bool stop_speculative_request_if_requested_locked();

void refresh_gpu_offload_evidence_locked();
std::string stats_json(const char *backend);
void clear_gpu_offload_evidence_locked();
void begin_gpu_execution_scope_locked();
void record_gpu_decode_execution_locked();
std::string backend_devices_json_array();
int perform_model_load_locked(const std::string &path, const std::string &params);

#if MCA_WITH_LLAMA_CPP
bool abort_inference_if_stopped(void *user);
void android_llama_log(ggml_log_level level, const char *text, void *user);
#endif

} // namespace mca::native_engine

#endif
