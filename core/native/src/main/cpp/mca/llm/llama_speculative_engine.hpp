#ifndef MCA_LLM_LLAMA_SPECULATIVE_ENGINE_HPP
#define MCA_LLM_LLAMA_SPECULATIVE_ENGINE_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "speculative.h"
#else
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
struct llama_model;
struct llama_context;
struct llama_batch;
struct common_sampler;
struct common_speculative;
using common_speculative_ptr = std::shared_ptr<common_speculative>;
using llama_pos = int32_t;
#define LLAMA_TOKEN_NULL -1
#endif

namespace mca {
namespace llm {

struct SpecStepResult {
    llama_tokens output;
    bool ok{true};
    bool done{false};
};

struct SpeculativeEnvironment {
    llama_model *model{nullptr};
    llama_context *context{nullptr};
    llama_context *mtp_context{nullptr};
    llama_batch *batch{nullptr};
    common_sampler *sampler{nullptr};
    common_speculative_ptr speculative;

    bool spec_requested{false};
    bool *spec_request_active{nullptr};
    std::string *spec_request_reason{nullptr};
    bool *spec_done{nullptr};
    llama_token *spec_pending_token{nullptr};
    bool *spec_pending_valid{nullptr};
    llama_tokens *spec_prompt_tokens{nullptr};
    llama_tokens *spec_draft_tokens{nullptr};
    long long *spec_drafted_tokens{nullptr};
    long long *spec_accepted_tokens{nullptr};
    long long *spec_steps{nullptr};

    llama_pos *current_position{nullptr};
    llama_tokens *context_tokens{nullptr};

    int n_ctx{4096};
    int n_batch{512};
    int n_predict{8192};
    int spec_draft_n_max{0};
    int overflow_headroom{4};
    long long completion_tokens{0};

    std::string *last_error{nullptr};

    std::function<bool()> is_stopped;
    std::function<bool(llama_pos pos, const char *label)> trim_context;
    std::function<bool(llama_pos pos, const char *label)> trim_speculative_contexts;
    std::function<void(const std::string &reason)> invalidate_speculative_contexts;
    std::function<void()> record_gpu_execution;
};

struct SpecChunkResult {
    std::string output;
    bool has_output{false};
    bool is_done{false};
    bool is_failed{false};
    bool is_stopped{false};
    std::string error_message;
    bool normal_finished{false};
    bool max_tokens_finished{false};
};

class LlamaSpeculativeEngine {
public:
    static bool prepare_speculative_request(SpeculativeEnvironment &env);
    static SpecStepResult speculative_step(SpeculativeEnvironment &env);
    static SpecChunkResult generate_chunk(
            SpeculativeEnvironment &env,
            std::string &cached_token_chars,
            long long &completion_tokens,
            int max_tokens);
};

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLAMA_SPECULATIVE_ENGINE_HPP
