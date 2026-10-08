#include "mca/mnn/mnn_vision_adapter.hpp"

#include <algorithm>
#include <sys/stat.h>

#if !defined(S_ISREG) && defined(_S_IFREG)
#define S_ISREG(m) (((m) & _S_IFMT) == _S_IFREG)
#endif

#if (defined(MCA_WITH_MNN_LLM) && MCA_WITH_MNN_LLM) || __has_include(<llm/llm.hpp>) || __has_include("llm/llm.hpp")
#define MCA_MNN_VISION_HAS_LLM 1
#if __has_include(<llm/llm.hpp>)
#include <llm/llm.hpp>
#elif __has_include("llm/llm.hpp")
#include "llm/llm.hpp"
#endif
#else
#define MCA_MNN_VISION_HAS_LLM 0
#endif

namespace mca {
namespace mnn {

namespace {

bool file_exists(const std::string& path) {
    if (path.empty()) return false;
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

} // namespace

std::string validateMnnImagePath(const std::string& rawPath) {
    const auto imagePath = local_mnn_image_path(rawPath);
    if (imagePath.rfind("content://", 0) == 0 ||
            imagePath.rfind("http://", 0) == 0 ||
            imagePath.rfind("https://", 0) == 0 ||
            imagePath.rfind("data:", 0) == 0) {
        throw std::runtime_error(
                "MNN local vision received an unresolved image URI. "
                "Prepare the attachment as a local readable image file first.");
    }
    if (!file_exists(imagePath)) {
        throw std::runtime_error(
                "MNN local vision image file is missing or unreadable: " + imagePath);
    }
    return imagePath;
}

bool replaceMediaTagInMessages(
        MNN::Transformer::ChatMessages& messages,
        const std::string& originalTag,
        const std::string& replacementTag) {
    for (auto& message : messages) {
        const auto offset = message.second.find(originalTag);
        if (offset == std::string::npos) continue;
        message.second.replace(offset, originalTag.size(), replacementTag);
        return true;
    }
    return false;
}

void normalizeMnnImageTagsInMessages(
        MNN::Transformer::ChatMessages& messages,
        const ParsedMnnChatMessages& parsed) {
    for (const auto& input : parsed.images) {
        const auto imagePath = validateMnnImagePath(input.path);
        const auto placeholder = "<img>" + input.key + "</img>";
        if (!replaceMediaTagInMessages(
                    messages,
                    placeholder,
                    "<img>" + imagePath + "</img>")) {
            throw std::runtime_error(
                    "MNN local vision could not match an image placeholder in the message content.");
        }
    }
    for (const auto& rawImagePath : parsed.rawMediaTags.images) {
        const auto imagePath = validateMnnImagePath(rawImagePath);
        if (imagePath == rawImagePath) continue;
        if (!replaceMediaTagInMessages(
                    messages,
                    "<img>" + rawImagePath + "</img>",
                    "<img>" + imagePath + "</img>")) {
            throw std::runtime_error(
                    "MNN local vision could not preserve a normalized raw image tag in the message content.");
        }
    }
    const auto latestUserMessage = std::find_if(
            messages.rbegin(),
            messages.rend(),
            [](const MNN::Transformer::ChatMessage& message) {
                return message.first == "user";
            });
    if (latestUserMessage == messages.rend()) {
        throw std::runtime_error(
                "MNN local multimodal input has no user message after normalization.");
    }
}

MNN::Transformer::ChatMessages prepareMnnVisualResponse(
        const ParsedMnnChatMessages& parsed,
        const MnnVisionContext& context) {
    if (parsed.rawMediaTags.malformed) {
        throw std::runtime_error(
                "MNN local multimodal input contains an unterminated raw media tag.");
    }
    if (!parsed.rawMediaTags.audio.empty() || !parsed.rawMediaTags.video.empty()) {
        throw std::runtime_error(
                "MNN local chat currently supports image attachments only; raw audio/video tags are unavailable.");
    }
    if (!context.vision_ready || context.visual_model_path.empty()) {
        throw std::runtime_error(
                "This MNN package does not include a readable visual.mnn component. "
                "Load a complete local multimodal package before sending an image.");
    }
#if !defined(MNN_IMGCODECS)
    throw std::runtime_error(
            "This MCA build does not include MNN image codecs, so local image input is unavailable.");
#else
    // Let Omni decode each attachment inside its own ExecutorScope. Creating image VARPs here
    // binds them to the default runtime, which can make the later vision/text concat cross runtimes.
    // Keep media decoding inside Omni's official response path after validating
    // and normalizing paths. Reimplementing template -> tokenize -> embedding
    // here diverges from MNNChat and can lose the visual effect before decode.
    auto promptMessages = parsed.messages;
    // Preserve caller roles. The continuous ChatMessages response owns visual
    // prefill and decode for historical requests, just as the single-turn path does.
    if (context.multimodal_system_prompt_suppressed != nullptr) {
        *context.multimodal_system_prompt_suppressed = false;
    }
    if (promptMessages.empty()) {
        throw std::runtime_error(
                "MNN local multimodal input has no messages.");
    }

    normalizeMnnImageTagsInMessages(promptMessages, parsed);

    if (context.multimodal_history_suppressed != nullptr) {
        *context.multimodal_history_suppressed = false;
    }

    const bool singleUserTurn = useMnnSingleUserVisualResponse(promptMessages);
    std::string formatted_prompt;
    if (context.custom_single_turn_formatter && singleUserTurn) {
        formatted_prompt = context.custom_single_turn_formatter(promptMessages.front().second);
    } else if (context.custom_messages_formatter && !singleUserTurn) {
        formatted_prompt = context.custom_messages_formatter(promptMessages);
#if MCA_MNN_VISION_HAS_LLM
    } else if (context.llm != nullptr) {
        formatted_prompt = singleUserTurn
                ? context.llm->apply_chat_template(promptMessages.front().second)
                : context.llm->apply_chat_template(promptMessages);
#endif
    }

    if (context.debug_prompt_sink) {
        context.debug_prompt_sink(formatted_prompt);
    }
    if (context.prompt_ends_inside_reasoning != nullptr) {
        *context.prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(formatted_prompt);
    }
    return promptMessages;
#endif
}

} // namespace mnn
} // namespace mca
