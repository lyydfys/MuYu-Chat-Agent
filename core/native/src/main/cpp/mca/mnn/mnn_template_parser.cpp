#include "mnn_template_parser.hpp"

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <stdexcept>
#include <utility>

#include "mnn_vision_path_policy.hpp"

namespace mca {
namespace mnn {

bool prompt_ends_inside_reasoning(const std::string& prompt) {
    static const std::vector<std::string> reasoning_openers = {
            "<think>",
            "<|think|>",
            "<|channel>thought",
            "<|channel|>thought",
            "<|channel>analysis",
            "<|channel|>analysis",
    };
    size_t end = prompt.size();
    while (end > 0 && std::isspace(static_cast<unsigned char>(prompt[end - 1]))) end--;
    for (const auto& opener: reasoning_openers) {
        const size_t n = opener.size();
        if (end >= n && prompt.compare(end - n, n, opener) == 0) {
            return true;
        }
    }
    return false;
}

bool has_mnn_jinja_chat_template(const nlohmann::json& config) {
    if (!config.is_object()) return false;
    const auto jinjaIt = config.find("jinja");
    if (jinjaIt == config.end() || !jinjaIt->is_object()) return false;
    const auto templateIt = jinjaIt->find("chat_template");
    return templateIt != jinjaIt->end() && templateIt->is_string() &&
            !templateIt->get<std::string>().empty();
}

bool inject_legacy_mnn_chat_template(nlohmann::json& runtimeConfig, const nlohmann::json& modelConfig) {
    if (has_mnn_jinja_chat_template(runtimeConfig) || has_mnn_jinja_chat_template(modelConfig)) {
        return false;
    }
    std::string legacyPrompt;
    const auto readPrompt = [&legacyPrompt](const nlohmann::json& source) {
        if (!legacyPrompt.empty() || !source.is_object()) return;
        const auto promptIt = source.find("prompt_template");
        if (promptIt != source.end() && promptIt->is_string()) {
            legacyPrompt = promptIt->get<std::string>();
        }
    };
    readPrompt(modelConfig);
    readPrompt(runtimeConfig);
    if (!mca::mnn::isLegacyQwenChatMlPromptTemplate(legacyPrompt)) return false;

    runtimeConfig["jinja"]["chat_template"] =
            mca::mnn::legacyQwenChatMlJinjaTemplate();
    runtimeConfig["jinja"]["eos"] = "<|im_end|>";
    return true;
}

std::string image_url_from_content_part(const nlohmann::json& part) {
    for (const auto* key : {"image_url", "image", "input_image", "url"}) {
        const auto value = part.find(key);
        if (value == part.end()) continue;
        if (value->is_string()) {
            const auto text = value->get<std::string>();
            if (!text.empty()) return text;
            continue;
        }
        if (!value->is_object()) continue;
        const auto url = value->find("url");
        if (url != value->end() && url->is_string()) {
            const auto text = url->get<std::string>();
            if (!text.empty()) return text;
        }
    }
    return {};
}

std::string text_from_content(
        const nlohmann::json& content,
        std::vector<MnnChatImageInput>* imageInputs) {
    if (content.is_string()) return content.get<std::string>();
    if (!content.is_array()) return content.dump();
    std::vector<std::string> textParts;
    std::vector<std::string> imageTags;
    for (const auto& part : content) {
        if (!part.is_object()) continue;
        const auto type = part.value("type", "");
        if (type == "text") {
            textParts.push_back(part.value("text", ""));
        } else if (type == "image_url" || type == "input_image" || type == "image") {
            const auto url = image_url_from_content_part(part);
            if (!url.empty()) {
                if (imageInputs == nullptr) {
                    imageTags.push_back("<img>" + url + "</img>");
                    continue;
                }
                const auto imageKey = mca::mnn::canonicalMnnImageReference(url);
                const bool duplicate = !imageKey.empty() && std::any_of(
                    imageInputs->begin(), imageInputs->end(), [&](const MnnChatImageInput& previous) {
                        return mca::mnn::canonicalMnnImageReference(previous.path) == imageKey;
                    });
                if (duplicate) continue;
                const auto key = "mca_image_" + std::to_string(imageInputs->size());
                imageInputs->push_back({key, url});
                imageTags.push_back("<img>" + key + "</img>");
            }
        }
    }
    return mca::mnn::composeMnnImageFirstPromptContent(imageTags, textParts);
}

std::string normalize_mnn_chat_message_for_prefix(
        std::string text,
        bool assistantProtocol) {
    std::string lineNormalized;
    lineNormalized.reserve(text.size());
    for (size_t index = 0; index < text.size(); ++index) {
        const char value = text[index];
        if (value == '\r') {
            if (index + 1 < text.size() && text[index + 1] == '\n') ++index;
            lineNormalized.push_back('\n');
        } else {
            lineNormalized.push_back(value);
        }
    }
    text = std::move(lineNormalized);

    static const std::vector<std::string> invisible = {
            "\xE2\x80\x8B", // U+200B ZERO WIDTH SPACE
            "\xE2\x80\x8C", // U+200C ZERO WIDTH NON-JOINER
            "\xE2\x80\x8D", // U+200D ZERO WIDTH JOINER
            "\xEF\xBB\xBF"  // U+FEFF ZERO WIDTH NO-BREAK SPACE / BOM
    };
    for (const auto& marker : invisible) {
        size_t position = 0;
        while ((position = text.find(marker, position)) != std::string::npos) {
            text.erase(position, marker.size());
        }
    }

    if (assistantProtocol) {
        static const std::vector<std::string> protocolLabels = {
                "<answer>",
                "<final>",
                "<response>",
                "<|channel|>final",
                "<|channel>final",
                "<|channel|>response",
                "<|channel>response"
        };
        size_t labelStart = 0;
        while (labelStart < text.size() &&
                std::isspace(static_cast<unsigned char>(text[labelStart]))) {
            ++labelStart;
        }
        for (const auto& label : protocolLabels) {
            if (text.compare(labelStart, label.size(), label) != 0) continue;
            size_t content = labelStart + label.size();
            while (content < text.size() &&
                    (text[content] == ' ' || text[content] == '\t' ||
                     text[content] == '\n')) {
                ++content;
            }
            if (content > labelStart + label.size()) {
                text.erase(labelStart + label.size(), content - (labelStart + label.size()));
            }
            break;
        }
    }

    size_t first = 0;
    if (assistantProtocol) {
        while (first < text.size() &&
                std::isspace(static_cast<unsigned char>(text[first]))) {
            ++first;
        }
    }
    size_t last = text.size();
    while (last > first &&
            std::isspace(static_cast<unsigned char>(text[last - 1]))) {
        --last;
    }
    if (first != 0 || last != text.size()) {
        text = text.substr(first, last - first);
    }
    return text;
}

bool mnn_chat_messages_prefix(
        const std::vector<ChatMessage>& prefix,
        const std::vector<ChatMessage>& messages) {
    if (prefix.size() > messages.size()) return false;
    const auto normalize = [](const std::string& text) {
        return normalize_mnn_chat_message_for_prefix(text, false);
    };
    for (size_t index = 0; index < prefix.size(); ++index) {
        if (prefix[index].first != messages[index].first) return false;
        const auto prefixText = prefix[index].first == "assistant"
                ? normalize_mnn_chat_message_for_prefix(prefix[index].second, true)
                : normalize(prefix[index].second);
        const auto messageText = messages[index].first == "assistant"
                ? normalize_mnn_chat_message_for_prefix(messages[index].second, true)
                : normalize(messages[index].second);
        if (prefixText != messageText) {
            return false;
        }
    }
    return true;
}

void restore_mnn_committed_assistant_text_locked(
        const std::vector<ChatMessage>& committed,
        std::vector<ChatMessage>& messages) {
    const size_t count = std::min(committed.size(), messages.size());
    for (size_t index = 0; index < count; ++index) {
        if (committed[index].first != "assistant" ||
                messages[index].first != committed[index].first) {
            continue;
        }
        if (committed[index].second == messages[index].second) continue;
        if (normalize_mnn_chat_message_for_prefix(committed[index].second, true) ==
                normalize_mnn_chat_message_for_prefix(messages[index].second, true)) {
            messages[index].second = committed[index].second;
        }
    }
}

void collect_mnn_raw_media_tags_from_content(
        const nlohmann::json& content,
        MnnRawMediaTags& tags) {
    if (content.is_string()) {
        mca::mnn::collectMnnRawMediaTags(content.get<std::string>(), tags);
        return;
    }
    if (!content.is_array()) return;
    for (const auto& part : content) {
        if (!part.is_object() || part.value("type", "") != "text") continue;
        const auto textIt = part.find("text");
        if (textIt != part.end() && textIt->is_string()) {
            mca::mnn::collectMnnRawMediaTags(textIt->get<std::string>(), tags);
        }
    }
}

ParsedMnnChatMessages parse_chat_messages(const std::string& messages_json) {
    const auto root = nlohmann::json::parse(messages_json);
    if (!root.is_array()) {
        throw std::runtime_error("messagesJson must be an array.");
    }
    ParsedMnnChatMessages parsed;
    for (const auto& item : root) {
        if (!item.is_object()) continue;
        const auto role = item.value("role", "user");
        const auto content_it = item.find("content");
        if (content_it != item.end()) {
            collect_mnn_raw_media_tags_from_content(*content_it, parsed.rawMediaTags);
        }
        const auto content = content_it == item.end()
                ? std::string()
                : text_from_content(*content_it, &parsed.images);
        if (!content.empty() || role == "assistant" || role == "system") {
            parsed.messages.emplace_back(role, content);
        }
    }
    if (!parsed.images.empty() && !parsed.rawMediaTags.images.empty()) {
        parsed.rawMediaTags.images.erase(
                std::remove_if(
                        parsed.rawMediaTags.images.begin(),
                        parsed.rawMediaTags.images.end(),
                        [&](const std::string& rawPath) {
                            const auto key = mca::mnn::canonicalMnnImageReference(rawPath);
                            return !key.empty() && std::any_of(
                                    parsed.images.begin(),
                                    parsed.images.end(),
                                    [&](const MnnChatImageInput& input) {
                                        return mca::mnn::canonicalMnnImageReference(input.path) == key;
                                    });
                        }),
                parsed.rawMediaTags.images.end());

        for (auto& message : parsed.messages) {
            std::string cleaned;
            cleaned.reserve(message.second.size());
            size_t cursor = 0;
            while (cursor < message.second.size()) {
                const size_t start = message.second.find("<img>", cursor);
                if (start == std::string::npos) {
                    cleaned.append(message.second, cursor, std::string::npos);
                    break;
                }
                cleaned.append(message.second, cursor, start - cursor);
                const size_t valueStart = start + 5;
                const size_t end = message.second.find("</img>", valueStart);
                if (end == std::string::npos) {
                    cleaned.append(message.second, start, std::string::npos);
                    break;
                }
                const auto rawPath = message.second.substr(valueStart, end - valueStart);
                const auto rawKey = mca::mnn::canonicalMnnImageReference(rawPath);
                const bool matchesStructured = !rawKey.empty() && std::any_of(
                    parsed.images.begin(),
                    parsed.images.end(),
                    [&](const MnnChatImageInput& input) {
                        return mca::mnn::canonicalMnnImageReference(input.path) == rawKey;
                    });
                if (!matchesStructured) {
                    cleaned.append(message.second, start, end + 6 - start);
                }
                cursor = end + 6;
            }
            message.second = std::move(cleaned);
        }
    }
    if (parsed.messages.empty()) {
        throw std::runtime_error("No valid chat messages for MNN generation.");
    }
    return parsed;
}

std::string local_mnn_image_path(const std::string& rawPath) {
    if (rawPath.rfind("file://", 0) == 0) return rawPath.substr(7);
    if (rawPath.rfind("file:", 0) == 0) return rawPath.substr(5);
    return rawPath;
}

} // namespace mnn
} // namespace mca
