#include "mca/llm/llama_completion_planner.hpp"

#include <algorithm>
#include <string>
#include <vector>

namespace mca {
namespace llm {

namespace {

bool string_is_one_of(std::string value, const std::vector<std::string> &options) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    for (const auto &opt : options) {
        std::string lower_opt = opt;
        std::transform(lower_opt.begin(), lower_opt.end(), lower_opt.begin(), [](unsigned char c) {
            return (char) std::tolower(c);
        });
        if (value == lower_opt) return true;
    }
    return false;
}

} // namespace

bool LlamaCompletionPlanner::should_use_jinja(const std::string &params_json) {
    return mca::request::parse_bool(params_json, "use_jinja", true);
}

bool LlamaCompletionPlanner::should_enable_thinking(const std::string &params_json) {
    const std::string mode = mca::request::parse_string(params_json, "thinking_mode", "");
    if (!mode.empty()) {
        return !string_is_one_of(mode, {"off", "disabled", "false", "0"});
    }
    return mca::request::parse_bool(params_json, "enable_thinking", true);
}

int LlamaCompletionPlanner::thinking_budget_for_mode(const std::string &params_json) {
    const std::string mode = mca::request::parse_string(params_json, "thinking_mode", "");
    if (string_is_one_of(mode, {"unlimited", "infinite", "-1"})) {
        return -1;
    }
    if (string_is_one_of(mode, {"off", "disabled", "false", "0"})) {
        return 0;
    }
    const int budget = mca::request::parse_int(params_json, "thinking_budget", 1024);
    if (budget < 0) return -1;
    return budget;
}

bool LlamaCompletionPlanner::plan_completion(
        llama_context *ctx,
        const common_chat_templates *templates,
        const CompletionPlanRequest &req,
        CompletionPlanResult &res) {
    RuntimeConfig request_config;
    std::string config_error;
    if (!parse_runtime_config(req.params_json, req.requested_config, request_config, config_error)) {
        res.rc = -10;
        res.error_message = "Invalid completion runtime config: " + config_error;
        return false;
    }

    if (req.params_json.find("\"n_threads\"") != std::string::npos &&
        req.params_json.find("\"n_threads_batch\"") == std::string::npos) {
        request_config.n_threads_batch = request_config.n_threads;
        if (!validate_runtime_config(request_config, config_error)) {
            res.rc = -10;
            res.error_message = "Invalid completion runtime config: " + config_error;
            return false;
        }
    }

    if (!runtime_config_equal_load_bound(request_config, req.requested_config)) {
        res.rc = -11;
        res.error_message = "Completion config changes load-bound fields; reload the model before generating.";
        return false;
    }

    res.new_requested_config = req.requested_config;
    res.new_effective_config = req.effective_config;

    const int previous_effective_cache_reuse = req.effective_config.cache_reuse;
    res.new_requested_config.n_threads = request_config.n_threads;
    res.new_requested_config.n_threads_batch = request_config.n_threads_batch;
    res.new_requested_config.cache_reuse = request_config.cache_reuse;
    res.new_effective_config.n_threads = request_config.n_threads;
    res.new_effective_config.n_threads_batch = request_config.n_threads_batch;
    res.new_effective_config.cache_reuse = req.spec_requested ? 0 : request_config.cache_reuse;

    res.cache_reuse_changed = (previous_effective_cache_reuse != res.new_effective_config.cache_reuse);
    res.threads_changed = (request_config.n_threads != req.current_threads ||
                           request_config.n_threads_batch != req.current_threads_batch);
    res.n_threads = request_config.n_threads;
    res.n_threads_batch = request_config.n_threads_batch;

    res.n_predict = mca::request::parse_int(req.params_json, "n_predict", 8192);
    if (res.n_predict < 1 || res.n_predict > 1048576) {
        res.rc = -12;
        res.error_message = "n_predict must be in [1, 1048576].";
        return false;
    }

    res.chat_options.use_jinja = should_use_jinja(req.params_json);
    res.chat_options.enable_thinking = should_enable_thinking(req.params_json);
    res.chat_options.thinking_budget = thinking_budget_for_mode(req.params_json);

    res.messages = mca::request::parse_messages(req.messages_json);
    res.is_multimodal = messages_have_images(res.messages);

    if (!res.is_multimodal) {
        res.formatted_prompt = format_messages(templates, res.messages, res.chat_options);
        res.prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(res.formatted_prompt);
#if MCA_WITH_LLAMA_CPP
        res.tokens = common_tokenize(ctx, res.formatted_prompt, true, true);
        if (res.tokens.empty()) {
            res.rc = -13;
            res.error_message = "The rendered text prompt tokenized to an empty sequence.";
            return false;
        }
#endif
    }

    res.rc = 0;
    return true;
}

} // namespace llm
} // namespace mca
