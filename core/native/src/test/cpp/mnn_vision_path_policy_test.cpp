#include "../../main/cpp/mnn_vision_path_policy.hpp"

#include <cassert>
#include <string>
#include <vector>

int main() {
    assert(mca::mnn::isMnnVisualPathKey("visual_model"));
    assert(mca::mnn::isMnnVisualPathKey("VISION_ENCODER"));
    assert(!mca::mnn::isMnnVisualPathKey("projector_model"));

    const std::vector<std::string> files = {
        "config.json",
        "llm.mnn",
        "vision/encoder.mnn",
    };
    const auto exists = [&files](const std::string& path) {
        for (const auto& file : files) {
            if (file == path) return true;
        }
        return false;
    };

    // A nested graph is selected when the historical root visual.mnn is absent.
    assert(mca::mnn::selectMnnVisualModelPath("", {}, exists) == "vision/encoder.mnn");

    // Explicit metadata always wins over fallback paths.
    assert(mca::mnn::selectMnnVisualModelPath(
               "vision/encoder.mnn", {"visual/visual.mnn"}, exists) == "vision/encoder.mnn");

    // Unsafe declarations never escape the bundle root and are ignored.
    assert(mca::mnn::normalizeMnnVisualRelativePath("../visual.mnn").empty());
    assert(mca::mnn::normalizeMnnVisualRelativePath("/sdcard/visual.mnn").empty());
    assert(mca::mnn::normalizeMnnVisualRelativePath("C:\\visual.mnn").empty());
    assert(mca::mnn::normalizeMnnVisualRelativePath("vision\\encoder.mnn") ==
           "vision/encoder.mnn");

    std::vector<std::string> candidates =
        mca::mnn::mnnVisualPathCandidates("", {"vision/encoder.mnn", "vision/encoder.mnn"});
    assert(candidates.front() == "vision/encoder.mnn");
    size_t duplicateCount = 0;
    for (const auto& candidate : candidates) {
        if (candidate == "vision/encoder.mnn") ++duplicateCount;
    }
    assert(duplicateCount == 1);
    return 0;
}
