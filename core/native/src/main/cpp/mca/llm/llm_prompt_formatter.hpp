#ifndef MCA_LLM_LLM_PROMPT_FORMATTER_HPP
#define MCA_LLM_LLM_PROMPT_FORMATTER_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "mca/llm/llm_prefix_cache_manager.hpp"
#include "mca/request/llama_request_parser.hpp"

#if MCA_WITH_LLAMA_CPP
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#else
struct common_chat_templates;
struct mtmd_context;
struct llama_context;
using llama_pos = int32_t;
#endif

namespace mca {
namespace llm {

/**
 * Host environment and execution hooks required for multimodal prefill.
 */
struct MultimodalPrefillEnvironment {
    mtmd_context *mtmd_ctx{nullptr};
    bool vision_ready{false};
    llama_context *llama_ctx{nullptr};
    int n_ctx{0};
    int n_batch{0};
    int overflow_headroom{4};
    int completion_stopped_code{-2};

    const common_chat_templates *chat_templates{nullptr};

    std::function<bool()> is_stop_requested;
    std::string *last_error{nullptr};
    llama_pos *current_position{nullptr};
    long long *prompt_tokens{nullptr};
    long long *prefill_computed_tokens{nullptr};
    bool *prompt_ends_inside_reasoning{nullptr};

    std::function<void(size_t total_tokens)> begin_prefill_progress;
    std::function<void(size_t reused_tokens)> report_reused_prefill_tokens;
};

/**
 * Encapsulates prompt template rendering, reasoning delimiter detection,
 * multimodal media tag injection, and visual encoder prefill execution.
 */
class PromptFormatter {
public:
    static bool prompt_ends_inside_reasoning(const std::string &prompt);

    static std::string format_messages(
            const common_chat_templates *templates,
            const std::vector<mca::request::ParsedMessage> &messages,
            const ChatTemplateOptions &options);

    static bool messages_have_images(const std::vector<mca::request::ParsedMessage> &messages);

    static std::vector<mca::request::ParsedMessage> with_media_markers(
            const std::vector<mca::request::ParsedMessage> &messages,
            const char *custom_marker = nullptr);

    static int prefill_multimodal_locked(
            const std::vector<mca::request::ParsedMessage> &messages,
            const ChatTemplateOptions &chat_options,
            MultimodalPrefillEnvironment &env);
};

// Functional interfaces
bool prompt_ends_inside_reasoning(const std::string &prompt);

std::string format_messages(
        const common_chat_templates *templates,
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &options);

bool messages_have_images(const std::vector<mca::request::ParsedMessage> &messages);

std::vector<mca::request::ParsedMessage> with_media_markers(
        const std::vector<mca::request::ParsedMessage> &messages,
        const char *custom_marker = nullptr);

int prefill_multimodal_locked(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        MultimodalPrefillEnvironment &env);

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLM_PROMPT_FORMATTER_HPP
