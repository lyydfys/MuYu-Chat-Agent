#ifndef MCA_LLM_LLAMA_COMPLETION_PLANNER_HPP
#define MCA_LLM_LLAMA_COMPLETION_PLANNER_HPP

#include <string>
#include <vector>

#include "mca/llm/llm_runtime_config.hpp"
#include "mca/llm/llm_prompt_formatter.hpp"
#include "mca/request/llama_request_parser.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#else
struct llama_context;
struct common_chat_templates;
using llama_token = int32_t;
using llama_tokens = std::vector<llama_token>;
#endif

namespace mca {
namespace llm {

struct CompletionPlanRequest {
    std::string messages_json;
    std::string params_json;
    RuntimeConfig requested_config;
    RuntimeConfig effective_config;
    bool spec_requested{false};
    int current_threads{0};
    int current_threads_batch{0};
};

struct CompletionPlanResult {
    int rc{0};
    std::string error_message;
    RuntimeConfig new_requested_config;
    RuntimeConfig new_effective_config;
    bool threads_changed{false};
    int n_threads{0};
    int n_threads_batch{0};
    bool cache_reuse_changed{false};
    int n_predict{8192};
    bool is_multimodal{false};
    ChatTemplateOptions chat_options;
    std::vector<mca::request::ParsedMessage> messages;
    std::string formatted_prompt;
    bool prompt_ends_inside_reasoning{false};
    llama_tokens tokens;
};

class LlamaCompletionPlanner {
public:
    static bool should_use_jinja(const std::string &params_json);
    static bool should_enable_thinking(const std::string &params_json);
    static int thinking_budget_for_mode(const std::string &params_json);

    static bool plan_completion(
            llama_context *ctx,
            const common_chat_templates *templates,
            const CompletionPlanRequest &req,
            CompletionPlanResult &res);
};

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLAMA_COMPLETION_PLANNER_HPP
