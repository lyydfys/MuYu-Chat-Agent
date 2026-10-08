#include "mca/llm/llm_prompt_formatter.hpp"

#include <cctype>
#include <string>
#include <utility>
#include <vector>

namespace mca {
namespace llm {

bool PromptFormatter::prompt_ends_inside_reasoning(const std::string &prompt) {
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
    for (const auto &opener: reasoning_openers) {
        const size_t n = opener.size();
        if (end >= n && prompt.compare(end - n, n, opener) == 0) {
            return true;
        }
    }
    return false;
}

std::string PromptFormatter::format_messages(
        const common_chat_templates *templates,
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &options) {
#if MCA_WITH_LLAMA_CPP
    const bool has_template = templates != nullptr;
    if (has_template) {
        common_chat_templates_inputs inputs;
        inputs.use_jinja = options.use_jinja;
        inputs.enable_thinking = options.enable_thinking;
        inputs.add_generation_prompt = true;
        // MCA only needs the rendered prompt. For Android in-app streaming we parse
        // reasoning ourselves, so avoid llama.cpp's PEG/autoparser path here; some
        // recent Jinja templates raise while the parser is being generated even
        // though direct prompt rendering is valid.
        inputs.force_pure_content = true;
        if (options.use_jinja && options.thinking_budget > 0) {
            inputs.chat_template_kwargs["thinking_budget"] = std::to_string(options.thinking_budget);
        }

        for (const auto &message: messages) {
            common_chat_msg msg;
            msg.role = message.role.empty() ? "user" : message.role;
            msg.content = message.content;
            inputs.messages.push_back(msg);
        }

        const std::string prompt = common_chat_templates_apply(templates, inputs).prompt;
        return prompt;
    }
#endif

    std::string formatted;
    for (const auto &message: messages) {
        const std::string role = message.role.empty() ? "user" : message.role;
        formatted += role + ": " + message.content + "\n";
    }
    formatted += "assistant: ";
    return formatted;
}

bool PromptFormatter::messages_have_images(const std::vector<mca::request::ParsedMessage> &messages) {
    for (const auto &message: messages) {
        if (!message.image_paths.empty()) return true;
    }
    return false;
}

std::vector<mca::request::ParsedMessage> PromptFormatter::with_media_markers(
        const std::vector<mca::request::ParsedMessage> &messages,
        const char *custom_marker) {
    std::vector<mca::request::ParsedMessage> out;
    out.reserve(messages.size());
    const char *marker = custom_marker;
#if MCA_WITH_LLAMA_CPP
    if (marker == nullptr) {
        marker = mtmd_default_marker();
    }
#endif
    if (marker == nullptr) {
        marker = "<__media__>";
    }
    for (auto message: messages) {
        if (!message.image_paths.empty()) {
            std::string prefix;
            for (size_t i = 0; i < message.image_paths.size(); i++) {
                prefix += marker;
            }
            message.content = prefix + (message.content.empty() ? "请描述这张图片。" : message.content);
        }
        out.push_back(std::move(message));
    }
    return out;
}

int PromptFormatter::prefill_multimodal_locked(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        MultimodalPrefillEnvironment &env) {
#if MCA_WITH_LLAMA_CPP
    if (env.mtmd_ctx == nullptr || !env.vision_ready) {
        if (env.last_error != nullptr) {
            *env.last_error = "Local vision is not ready. Attach a matching mmproj projector and reload the model.";
        }
        return -4;
    }

    std::vector<mtmd_bitmap *> owned_bitmaps;
    std::vector<mtmd_helper_video *> owned_videos;
    const auto free_media = [&]() {
        for (auto *owned: owned_bitmaps) mtmd_bitmap_free(owned);
        for (auto *owned: owned_videos) mtmd_helper_video_free(owned);
    };
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    for (const auto &message: messages) {
        for (const auto &path: message.image_paths) {
            if (env.is_stop_requested && env.is_stop_requested()) {
                free_media();
                return env.completion_stopped_code;
            }
            const auto loaded = mtmd_helper_bitmap_init_from_file(
                    env.mtmd_ctx,
                    path.c_str(),
                    false,
                    mtmd_helper_init_opt_default());
            mtmd_bitmap *bitmap = loaded.bitmap;
            if (bitmap == nullptr) {
                if (loaded.video_ctx != nullptr) mtmd_helper_video_free(loaded.video_ctx);
                free_media();
                if (env.last_error != nullptr) {
                    *env.last_error = "Failed to load local vision image: " + path;
                }
                return -5;
            }
            owned_bitmaps.push_back(bitmap);
            if (loaded.video_ctx != nullptr) owned_videos.push_back(loaded.video_ctx);
            bitmap_ptrs.push_back(bitmap);
        }
    }

    if (env.is_stop_requested && env.is_stop_requested()) {
        free_media();
        return env.completion_stopped_code;
    }
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (chunks == nullptr) {
        free_media();
        if (env.last_error != nullptr) {
            *env.last_error = "mtmd_input_chunks_init returned null.";
        }
        return -6;
    }

    const auto marked_messages = with_media_markers(messages);
    const auto formatted = format_messages(env.chat_templates, marked_messages, chat_options);
    if (env.prompt_ends_inside_reasoning != nullptr) {
        *env.prompt_ends_inside_reasoning = prompt_ends_inside_reasoning(formatted);
    }
    mtmd_input_text text{};
    text.text = formatted.c_str();
    text.text_len = formatted.size();
    text.add_special = true;
    text.parse_special = true;

    int tokenize_rc = mtmd_tokenize(
            env.mtmd_ctx,
            chunks,
            &text,
            bitmap_ptrs.empty() ? nullptr : bitmap_ptrs.data(),
            bitmap_ptrs.size());
    free_media();
    if (tokenize_rc != 0) {
        mtmd_input_chunks_free(chunks);
        if (env.is_stop_requested && env.is_stop_requested()) return env.completion_stopped_code;
        if (env.last_error != nullptr) {
            *env.last_error = "mtmd_tokenize failed: " + std::to_string(tokenize_rc);
        }
        return -7;
    }
    if (env.is_stop_requested && env.is_stop_requested()) {
        mtmd_input_chunks_free(chunks);
        return env.completion_stopped_code;
    }

    const size_t multimodal_prompt_tokens = mtmd_helper_get_n_tokens(chunks);
    if (multimodal_prompt_tokens == 0 ||
        multimodal_prompt_tokens >= static_cast<size_t>(env.n_ctx - env.overflow_headroom)) {
        mtmd_input_chunks_free(chunks);
        if (env.last_error != nullptr) {
            *env.last_error = "Vision prompt needs " + std::to_string(multimodal_prompt_tokens) +
                           " tokens, but n_ctx=" + std::to_string(env.n_ctx) +
                           " must also leave room for generation. Reduce image size or history, "
                           "or reload with a larger context.";
        }
        return -9;
    }
    if (env.begin_prefill_progress) {
        env.begin_prefill_progress(multimodal_prompt_tokens);
    }

    llama_pos new_position = 0;
    const int eval_rc = mtmd_helper_eval_chunks(
            env.mtmd_ctx,
            env.llama_ctx,
            chunks,
            0,
            0,
            env.n_batch,
            true,
            &new_position);
    if (env.prompt_tokens != nullptr) {
        *env.prompt_tokens = static_cast<long long>(multimodal_prompt_tokens);
    }
    mtmd_input_chunks_free(chunks);
    if (eval_rc != 0) {
        if (env.is_stop_requested && env.is_stop_requested()) return env.completion_stopped_code;
        if (env.last_error != nullptr) {
            *env.last_error = "mtmd_helper_eval_chunks failed: " + std::to_string(eval_rc);
        }
        return -8;
    }
    if (env.is_stop_requested && env.is_stop_requested()) return env.completion_stopped_code;
    if (env.current_position != nullptr) {
        *env.current_position = new_position;
    }
    if (env.prefill_computed_tokens != nullptr) {
        *env.prefill_computed_tokens = static_cast<long long>(multimodal_prompt_tokens);
    }
    if (env.report_reused_prefill_tokens) {
        env.report_reused_prefill_tokens(multimodal_prompt_tokens);
    }
    return 0;
#else
    (void) messages;
    (void) chat_options;
    (void) env;
    return -4;
#endif
}

bool prompt_ends_inside_reasoning(const std::string &prompt) {
    return PromptFormatter::prompt_ends_inside_reasoning(prompt);
}

std::string format_messages(
        const common_chat_templates *templates,
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &options) {
    return PromptFormatter::format_messages(templates, messages, options);
}

bool messages_have_images(const std::vector<mca::request::ParsedMessage> &messages) {
    return PromptFormatter::messages_have_images(messages);
}

std::vector<mca::request::ParsedMessage> with_media_markers(
        const std::vector<mca::request::ParsedMessage> &messages,
        const char *custom_marker) {
    return PromptFormatter::with_media_markers(messages, custom_marker);
}

int prefill_multimodal_locked(
        const std::vector<mca::request::ParsedMessage> &messages,
        const ChatTemplateOptions &chat_options,
        MultimodalPrefillEnvironment &env) {
    return PromptFormatter::prefill_multimodal_locked(messages, chat_options, env);
}

} // namespace llm
} // namespace mca
