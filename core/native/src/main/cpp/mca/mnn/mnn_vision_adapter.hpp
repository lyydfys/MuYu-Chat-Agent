#ifndef MCA_MNN_VISION_ADAPTER_HPP
#define MCA_MNN_VISION_ADAPTER_HPP

#include <string>
#include <vector>
#include <functional>
#include <stdexcept>
#include <utility>

#include "mca/mnn/mnn_template_parser.hpp"
#include "mnn_runtime_capability_policy.hpp"

#if defined(MCA_WITH_MNN_LLM) && MCA_WITH_MNN_LLM
#if __has_include(<llm/llm.hpp>)
#include <llm/llm.hpp>
#elif __has_include("llm/llm.hpp")
#include "llm/llm.hpp"
#endif
#endif

namespace MNN {
namespace Transformer {
class Llm;
using ChatMessage = std::pair<std::string, std::string>;
using ChatMessages = std::vector<ChatMessage>;
} // namespace Transformer
} // namespace MNN

namespace mca {
namespace mnn {

/**
 * Context for preparing MNN multimodal visual responses.
 */
struct MnnVisionContext {
    bool vision_ready{false};
    std::string visual_model_path;
    MNN::Transformer::Llm* llm{nullptr};
    bool* multimodal_system_prompt_suppressed{nullptr};
    bool* multimodal_history_suppressed{nullptr};
    bool* prompt_ends_inside_reasoning{nullptr};
    std::function<void(const std::string&)> debug_prompt_sink{nullptr};
    std::function<std::string(const std::string&)> custom_single_turn_formatter{nullptr};
    std::function<std::string(const MNN::Transformer::ChatMessages&)> custom_messages_formatter{nullptr};
};

/**
 * Validates and resolves a raw image path or URI for MNN local vision.
 * Throws std::runtime_error on unresolved URIs or missing files.
 */
std::string validateMnnImagePath(const std::string& rawPath);

/**
 * Replaces the first occurrence of an original tag in messages with a replacement tag.
 * Returns true if found and replaced, false otherwise.
 */
bool replaceMediaTagInMessages(
        MNN::Transformer::ChatMessages& messages,
        const std::string& originalTag,
        const std::string& replacementTag);

/**
 * Normalizes image placeholders and raw media tags in chat messages.
 * Verifies presence of a user message after normalization.
 */
void normalizeMnnImageTagsInMessages(
        MNN::Transformer::ChatMessages& messages,
        const ParsedMnnChatMessages& parsed);

/**
 * Prepares and validates a multimodal visual response for MNN.
 * Validates media tags, checks image codecs and components, normalizes image
 * paths/placeholders, applies chat template formatting, and captures debug prompt traces.
 */
MNN::Transformer::ChatMessages prepareMnnVisualResponse(
        const ParsedMnnChatMessages& parsed,
        const MnnVisionContext& context);

/**
 * Convenience overload accepting direct state references.
 */
inline MNN::Transformer::ChatMessages prepareMnnVisualResponse(
        const ParsedMnnChatMessages& parsed,
        bool vision_ready,
        const std::string& visual_model_path,
        MNN::Transformer::Llm* llm,
        bool& multimodal_system_prompt_suppressed,
        bool& multimodal_history_suppressed,
        bool& prompt_ends_inside_reasoning,
        const std::function<void(const std::string&)>& debug_prompt_sink = nullptr) {
    MnnVisionContext ctx;
    ctx.vision_ready = vision_ready;
    ctx.visual_model_path = visual_model_path;
    ctx.llm = llm;
    ctx.multimodal_system_prompt_suppressed = &multimodal_system_prompt_suppressed;
    ctx.multimodal_history_suppressed = &multimodal_history_suppressed;
    ctx.prompt_ends_inside_reasoning = &prompt_ends_inside_reasoning;
    ctx.debug_prompt_sink = debug_prompt_sink;
    return prepareMnnVisualResponse(parsed, ctx);
}

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_VISION_ADAPTER_HPP
