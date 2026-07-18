#pragma once

#include <string>

namespace mca {

/**
 * Arguments for MNN SanaLlm::process.
 *
 * SanaLlm emits rows in [prompt, negative_prompt] order. The linked
 * SanaDiffusion implementation then swaps those rows before its CFG split, so
 * the only correct invocation is [positive, negative] here; the graph consumes
 * [negative, positive]. Keeping that distinction in one testable adapter
 * prevents an accidental double swap in the MCA bridge.
 */
struct MnnSanaPromptInvocation {
    const std::string* prompt = nullptr;
    bool use_cfg = false;
    const std::string* negative_prompt = nullptr;
    const char* executed_conditioning_order = "positive_only";
};

inline MnnSanaPromptInvocation mnn_sana_prompt_invocation(
        const std::string& positive_prompt,
        const std::string& negative_prompt,
        bool use_cfg) {
    return MnnSanaPromptInvocation{
            &positive_prompt,
            use_cfg,
            &negative_prompt,
            use_cfg ? "negative_then_positive" : "positive_only"};
}

}  // namespace mca
