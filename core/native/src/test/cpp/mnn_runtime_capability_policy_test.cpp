#include "../../main/cpp/mnn_runtime_capability_policy.hpp"

#include <cassert>
#include <map>
#include <string>

int main() {
    // Gemma 4 exports both visual and audio metadata.  MCA keeps the visual
    // route available while disabling only the unsupported audio processor.
    std::map<std::string, bool> firstLoadConfig = {
        {"is_visual", true},
        {"is_audio", true},
    };
    mca::mnn::applyProductMnnRuntimeCapabilityPolicy(firstLoadConfig);
    assert(firstLoadConfig.at("is_visual"));
    assert(!firstLoadConfig.at("is_audio"));

    // A later request/load may merge package or advanced config again.  The
    // policy must be applied on that second path as well, otherwise Gemma's
    // model-side is_audio=true recreates the crashing MNN audio module.
    firstLoadConfig["is_audio"] = true;
    mca::mnn::applyProductMnnRuntimeCapabilityPolicy(firstLoadConfig);
    assert(firstLoadConfig.at("is_visual"));
    assert(!firstLoadConfig.at("is_audio"));

    std::map<std::string, int> requestConfig = {{"chunk", 128}};
    mca::mnn::applyProductMnnMultimodalRequestPolicy(requestConfig, false, 128);
    assert(requestConfig.at("chunk") == 128);

    // Visual prefill is always atomic. A caller-provided or model-provided
    // chunk override must not survive the final native request policy.
    mca::mnn::applyProductMnnMultimodalRequestPolicy(requestConfig, true, 128);
    assert(requestConfig.at("chunk") == 0);
    assert(mca::mnn::effectiveMnnRequestChunkSize(true, 32) == 0);
    mca::mnn::MnnRawMediaTags rawTags;
    mca::mnn::collectMnnRawMediaTags(
        "before<img>/tmp/a.png</img>middle<img>file:///tmp/b.png</img>"
        "<audio>/tmp/c.wav</audio>after",
        rawTags);
    assert(rawTags.hasAny());
    assert(!rawTags.malformed);
    assert(rawTags.images.size() == 2);
    assert(rawTags.images[0] == "/tmp/a.png");
    assert(rawTags.images[1] == "file:///tmp/b.png");
    assert(rawTags.audio.size() == 1);
    assert(rawTags.video.empty());

    mca::mnn::MnnRawMediaTags malformedTags;
    mca::mnn::collectMnnRawMediaTags("<img>/tmp/missing-close.png", malformedTags);
    assert(malformedTags.hasAny());
    assert(malformedTags.malformed);

    assert(mca::mnn::composeMnnImageFirstPromptContent(
               {"<img>first.png</img>", "<img>second.png</img>"},
               {"Describe both images.", "Answer briefly."}) ==
           "<img>first.png</img><img>second.png</img>Describe both images.\nAnswer briefly.");
    assert(mca::mnn::shouldSuppressMnnMultimodalSystemPrompt("qwen3_5"));
    assert(mca::mnn::shouldSuppressMnnMultimodalSystemPrompt("qwen3_5_moe"));
    assert(!mca::mnn::shouldSuppressMnnMultimodalSystemPrompt("gemma4"));
    assert(!mca::mnn::shouldSuppressMnnMultimodalSystemPrompt("qwen2_5_vl"));

    mca::mnn::MnnRequestLifecyclePolicy lifecycle;
    lifecycle.onModelLoaded();
    assert(!lifecycle.beginRequest());
    assert(lifecycle.requestCountSinceLoad == 1);
    assert(!lifecycle.lastRequestReset);
    assert(!lifecycle.beginRequest());
    assert(lifecycle.requestCountSinceLoad == 2);
    assert(!lifecycle.lastRequestReset);
    lifecycle.onModelLoaded();
    assert(!lifecycle.beginRequest());
    lifecycle.onModelUnloaded();
    assert(lifecycle.requestCountSinceLoad == 0);
    return 0;
}
