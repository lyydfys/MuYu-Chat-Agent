#pragma once

#include <string>

namespace mca::mnn {

/**
 * MNN 2.x text bundles commonly expose only the printf-style Qwen ChatML
 * `prompt_template`. MNN 3.x's ChatMessages API consumes `jinja.chat_template`
 * instead; without this compatibility adapter it concatenates system and user
 * text without role markers and produces semantically corrupted answers.
 */
inline bool isLegacyQwenChatMlPromptTemplate(const std::string& value) {
    return value.find("<|im_start|>user") != std::string::npos &&
           value.find("<|im_end|>") != std::string::npos &&
           value.find("<|im_start|>assistant") != std::string::npos &&
           value.find("%s") != std::string::npos;
}

inline const std::string& legacyQwenChatMlJinjaTemplate() {
    static const std::string value =
            "{% for message in messages %}"
            "{% if message['role'] == 'system' %}"
            "<|im_start|>system\n{{ message['content'] }}<|im_end|>\n"
            "{% elif message['role'] == 'user' %}"
            "<|im_start|>user\n{{ message['content'] }}<|im_end|>\n"
            "{% elif message['role'] == 'assistant' %}"
            "<|im_start|>assistant\n{{ message['content'] }}<|im_end|>\n"
            "{% endif %}"
            "{% endfor %}"
            "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}";
    return value;
}

}  // namespace mca::mnn
