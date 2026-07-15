#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace mca::mnn {

// MCA currently accepts text and image attachments only. Do not let a model
// package activate an MNN audio processor that the product cannot feed or
// validate. Gemma 4 declares audio support in llm_config.json; constructing
// that unused processor on MNN 3.6 crashes a later unload/reload cycle.
inline constexpr bool kProductSupportsMnnAudioAttachments = false;

struct MnnRawMediaTags {
    std::vector<std::string> images;
    std::vector<std::string> audio;
    std::vector<std::string> video;
    bool malformed = false;

    bool hasAny() const {
        return malformed || !images.empty() || !audio.empty() || !video.empty();
    }
};

// Llm::response() enters generate_init(), which already clears prompt/KV state
// when reuse_kv=false; the ChatMessages path also owns its reuse/cache cleanup.
// An extra bridge-level Llm::reset() is therefore redundant and, on Qwen3.5,
// corrupts multimodal results even though text generation still looks healthy.
// Keep the "never explicitly reset before response" contract host-testable.
struct MnnRequestLifecyclePolicy {
    bool lastRequestReset = false;
    size_t requestCountSinceLoad = 0;

    void onModelLoaded() {
        lastRequestReset = false;
        requestCountSinceLoad = 0;
    }

    void onModelUnloaded() {
        onModelLoaded();
    }

    bool beginRequest() {
        lastRequestReset = false;
        ++requestCountSinceLoad;
        return false;
    }
};

inline void appendMnnRawMediaTagValues(
        const std::string& text,
        const std::string& tag,
        std::vector<std::string>& target,
        bool& malformed) {
    const std::string opening = "<" + tag + ">";
    const std::string closing = "</" + tag + ">";
    size_t cursor = 0;
    while (cursor < text.size()) {
        const size_t start = text.find(opening, cursor);
        if (start == std::string::npos) return;
        const size_t valueStart = start + opening.size();
        const size_t end = text.find(closing, valueStart);
        if (end == std::string::npos) {
            malformed = true;
            return;
        }
        target.push_back(text.substr(valueStart, end - valueStart));
        cursor = end + closing.size();
    }
}

inline void collectMnnRawMediaTags(const std::string& text, MnnRawMediaTags& tags) {
    appendMnnRawMediaTagValues(text, "img", tags.images, tags.malformed);
    appendMnnRawMediaTagValues(text, "audio", tags.audio, tags.malformed);
    appendMnnRawMediaTagValues(text, "video", tags.video, tags.malformed);
}

// MNNChat's Android PromptUtils emits every selected image before the user's
// text (`<img>...</img><img>...</img>question`). Qwen-family MNN exports are
// measurably less reliable when an OpenAI-style content array is flattened in
// its transport order (usually text first, image second), so normalize only
// structured image parts to the official MNNChat image-first contract. Raw
// string prompts keep their caller-provided order.
inline std::string composeMnnImageFirstPromptContent(
        const std::vector<std::string>& imageTags,
        const std::vector<std::string>& textParts) {
    std::string content;
    for (const auto& imageTag : imageTags) {
        content.append(imageTag);
    }
    bool hasText = false;
    for (const auto& textPart : textParts) {
        if (textPart.empty()) continue;
        if (hasText) content.push_back('\n');
        content.append(textPart);
        hasText = true;
    }
    return content;
}

// MNN 3.5's Qwen3.5 ChatMessages visual path is not system-turn safe.  A
// controlled same-runtime A/B on the same bundle produces the correct red
// circle / blue square answer with a user-only image prompt, while adding
// either MCA's smoke system prompt or the generic "helpful assistant" system
// turn makes the answer image-independent or semantically wrong.  Text-only
// requests and non-Qwen3.5 multimodal models keep their system messages.
inline bool shouldSuppressMnnMultimodalSystemPrompt(const std::string& modelType) {
    return modelType == "qwen3_5" || modelType == "qwen3_5_moe";
}

// MNN's language chunker cannot preserve one atomic visual embedding across a
// split prompt. A visual request must therefore prefill with chunk=0 even if
// the model or caller selected a non-zero text chunk size. Pure-text requests
// retain their configured chunk size.
inline constexpr int effectiveMnnRequestChunkSize(
        bool hasVisualInputs,
        int configuredChunkSize) {
    return hasVisualInputs ? 0 : configuredChunkSize;
}

template <typename JsonLike>
inline void applyProductMnnRuntimeCapabilityPolicy(JsonLike& config) {
    // This assignment deliberately happens after all model/request advanced
    // config is merged, so neither a package's llm_config.json nor a caller
    // override can reactivate an unsupported audio path between load cycles.
    config["is_audio"] = kProductSupportsMnnAudioAttachments;
}

template <typename JsonLike>
inline void applyProductMnnMultimodalRequestPolicy(
        JsonLike& config,
        bool hasVisualInputs,
        int configuredChunkSize) {
    config["chunk"] = effectiveMnnRequestChunkSize(hasVisualInputs, configuredChunkSize);
}

}  // namespace mca::mnn
