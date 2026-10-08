#ifndef MCA_MNN_TEMPLATE_PARSER_HPP
#define MCA_MNN_TEMPLATE_PARSER_HPP

#include <string>
#include <vector>
#include <utility>
#include <cstdint>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "mnn_legacy_chat_template_policy.hpp"
#include "mnn_runtime_capability_policy.hpp"

namespace mca {
namespace mnn {

using ChatMessage = std::pair<std::string, std::string>;
using ChatMessages = std::vector<ChatMessage>;

struct MnnChatImageInput {
    std::string key;
    std::string path;
};

struct ParsedMnnChatMessages {
    std::vector<ChatMessage> messages;
    std::vector<MnnChatImageInput> images;
    MnnRawMediaTags rawMediaTags;

    bool hasMediaInputs() const {
        return !images.empty() || rawMediaTags.hasAny();
    }
};

// Reasoning opener inspection
bool prompt_ends_inside_reasoning(const std::string& prompt);

// Jinja / legacy template compatibility
bool has_mnn_jinja_chat_template(const nlohmann::json& config);
bool inject_legacy_mnn_chat_template(nlohmann::json& runtimeConfig, const nlohmann::json& modelConfig);

// Content parts & image extraction
std::string image_url_from_content_part(const nlohmann::json& part);
std::string text_from_content(
        const nlohmann::json& content,
        std::vector<MnnChatImageInput>* imageInputs = nullptr);

// Chat transcript prefix & assistant text normalization for prompt-cache
std::string normalize_mnn_chat_message_for_prefix(
        std::string text,
        bool assistantProtocol);

bool mnn_chat_messages_prefix(
        const std::vector<ChatMessage>& prefix,
        const std::vector<ChatMessage>& messages);

void restore_mnn_committed_assistant_text_locked(
        const std::vector<ChatMessage>& committed,
        std::vector<ChatMessage>& messages);

// Full chat message array parsing
void collect_mnn_raw_media_tags_from_content(
        const nlohmann::json& content,
        MnnRawMediaTags& tags);

ParsedMnnChatMessages parse_chat_messages(const std::string& messages_json);

std::string local_mnn_image_path(const std::string& rawPath);

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_TEMPLATE_PARSER_HPP
