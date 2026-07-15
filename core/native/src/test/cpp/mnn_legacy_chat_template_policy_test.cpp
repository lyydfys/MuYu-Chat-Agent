#include "../../main/cpp/mnn_legacy_chat_template_policy.hpp"

#include <cassert>
#include <string>

int main() {
    assert(mca::mnn::isLegacyQwenChatMlPromptTemplate(
            "<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n"));
    assert(!mca::mnn::isLegacyQwenChatMlPromptTemplate("[INST] %s [/INST]"));

    const auto& jinja = mca::mnn::legacyQwenChatMlJinjaTemplate();
    assert(jinja.find("message['role'] == 'system'") != std::string::npos);
    assert(jinja.find("message['role'] == 'user'") != std::string::npos);
    assert(jinja.find("message['role'] == 'assistant'") != std::string::npos);
    assert(jinja.find("add_generation_prompt") != std::string::npos);
    assert(jinja.find("<|im_start|>assistant\n") != std::string::npos);
    return 0;
}
