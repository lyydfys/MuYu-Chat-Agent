#include "../../../../../third_party/MNN/transformers/llm/engine/src/omni_embedding_contract.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <iterator>
#include <limits>
#include <string>
#include <vector>

int main() {
    using namespace MNN::Transformer::OmniEmbeddingContract;

    ConcatPlan plan;
    std::string error;
    assert(buildConcatPlan({{2, 1, 1024}, {64, 1, 1024}, {3, 1, 1024}}, 0, 1024, plan, error));
    assert(plan.outputShape == std::vector<int>({69, 1, 1024}));
    assert(plan.outerCount == 1);
    assert(plan.innerCount == 1024);
    assert(plan.outputElements == 69u * 1024u);

    assert(buildConcatPlan({{3, 2, 1024}, {3, 64, 1024}, {3, 1, 1024}}, 1, 1024, plan, error));
    assert(plan.outputShape == std::vector<int>({3, 67, 1024}));
    assert(plan.outerCount == 3);
    assert(plan.innerCount == 1024);

    assert(!buildConcatPlan({{2, 1, 1024}, {64, 1, 768}}, 0, 1024, plan, error));
    assert(error == "hidden_size_mismatch_at_segment_1");
    assert(!buildConcatPlan({{2, 1, 1024}, {64, 2, 1024}}, 0, 1024, plan, error));
    assert(error == "non_concat_dimension_mismatch_at_segment_1");
    assert(!buildConcatPlan({{2, 1, 1024}, {0, 1, 1024}}, 0, 1024, plan, error));
    assert(error == "non_positive_dimension_at_segment_1");

    const std::vector<int> ids = {10, 248053, 248056, 248056, 248054, 11, 248056, 248056, 248056, 12};
    assert(validateVisionPadRuns(ids, 248056, {2, 3}, error));
    assert(!validateVisionPadRuns(ids, 248056, {2}, error));
    assert(error == "vision_run_count_mismatch_expected_1_actual_2");
    assert(!validateVisionPadRuns(ids, 248056, {2, 2}, error));
    assert(error == "vision_run_length_mismatch_at_1_expected_2_actual_3");

    // Reproduce the device evidence: 48 text tokens followed by one 169-token
    // image run. A configured chunk of 128 leaves only 80 image pads in the
    // first chunk, while the complete visual embedding remains length 169.
    std::vector<int> qwen35Ids(48, 10);
    qwen35Ids.insert(qwen35Ids.end(), 169, 248056);
    qwen35Ids.insert(qwen35Ids.end(), 3, 11);
    const std::vector<int> firstChunk(qwen35Ids.begin(), qwen35Ids.begin() + 128);
    assert(!validateVisionPadRuns(firstChunk, 248056, {169}, error));
    assert(error == "vision_run_length_mismatch_at_0_expected_169_actual_80");
    assert(validateVisionPadRuns(qwen35Ids, 248056, {169}, error));

    MultimodalPrefillPlan prefillPlan;
    assert(buildMultimodalPrefillPlan(
        qwen35Ids, 248056, 248057, 1, 0, 128, prefillPlan, error));
    assert(prefillPlan.atomic);
    assert(prefillPlan.configuredChunkWouldSplitMediaRun);
    assert(prefillPlan.effectiveChunkSize == 0);
    assert(prefillPlan.tokenCount == qwen35Ids.size());
    assert(prefillPlan.visionRunCount == 1);

    // Pure text keeps the normal chunked path. Pending media without a matching
    // complete pad run fails before an embedding or generation call is made.
    assert(buildMultimodalPrefillPlan(
        std::vector<int>{1, 2, 3}, 248056, 248057, 0, 0, 128, prefillPlan, error));
    assert(!prefillPlan.atomic);
    assert(prefillPlan.effectiveChunkSize == 128);
    assert(!buildMultimodalPrefillPlan(
        std::vector<int>{1, 2, 3}, 248056, 248057, 1, 0, 128, prefillPlan, error));
    assert(error == "vision_run_count_mismatch_expected_1_actual_0");

    assert(isQwen35VisionInputContract({
        "patches", "position_ids", "attention_mask", "idx_tensor", "weight_tensor"
    }));
    assert(!isQwen35VisionInputContract({
        "patches", "position_ids", "attention_mask", "weight_tensor", "idx_tensor"
    }));

    // Shipping MNNChat is the hard runtime baseline: linear resize and its
    // legacy repeated fourth interpolation corner. Corrected/bicubic modes
    // remain explicit A/B probes rather than silent defaults.
    assert(qwenVisionResizeFilter("") == VisionResizeFilter::Linear);
    assert(qwenVisionResizeFilter("linear") == VisionResizeFilter::Linear);
    assert(qwenVisionResizeFilter("bicubic") == VisionResizeFilter::Bicubic);
    assert(std::string(visionResizeFilterName(VisionResizeFilter::Bicubic)) == "bicubic");
    assert(qwenVisionInterpolationCornerMode("") == VisionInterpolationCornerMode::MnnChatLegacy);
    assert(qwenVisionInterpolationCornerMode("mnnchat_legacy") == VisionInterpolationCornerMode::MnnChatLegacy);
    assert(qwenVisionInterpolationCornerMode("corrected") == VisionInterpolationCornerMode::Corrected);
    assert(std::string(visionInterpolationCornerModeName(VisionInterpolationCornerMode::MnnChatLegacy)) ==
           "mnnchat_legacy");
    assert(qwen35LegacyDeepstackPlaceholderShape() == std::vector<int>({3, 1, 1}));

    FloatTensorStats tensorStats;
    const float diagnosticValues[] = {
        -2.0f, 0.0f, 2.0f,
        std::numeric_limits<float>::quiet_NaN(),
        std::numeric_limits<float>::infinity()
    };
    assert(summarizeFloatTensor(diagnosticValues, 5, tensorStats, error));
    assert(tensorStats.elementCount == 5);
    assert(tensorStats.finiteCount == 3);
    assert(tensorStats.nanCount == 1);
    assert(tensorStats.infinityCount == 1);
    assert(tensorStats.zeroCount == 1);
    assert(tensorStats.minimum == -2.0f);
    assert(tensorStats.maximum == 2.0f);
    assert(std::fabs(tensorStats.mean) < 1.0e-12);
    assert(std::fabs(tensorStats.rootMeanSquare - std::sqrt(8.0 / 3.0)) < 1.0e-12);
    assert(!validateFloatTensorSignal(tensorStats, error));
    assert(error == "tensor_contains_non_finite_values");
    assert(!summarizeFloatTensor(nullptr, 5, tensorStats, error));
    assert(error == "null_tensor_data");

    const float validSignal[] = {-2.0f, 0.0f, 2.0f};
    const float sameValidSignal[] = {-2.0f, 0.0f, 2.0f};
    const float changedValidSignal[] = {-2.0f, 0.0f, 3.0f};
    const float allZeroSignal[] = {0.0f, 0.0f, 0.0f};
    FloatTensorStats validStats;
    FloatTensorStats sameValidStats;
    FloatTensorStats changedValidStats;
    FloatTensorStats allZeroStats;
    assert(summarizeFloatTensor(validSignal, 3, validStats, error));
    assert(summarizeFloatTensor(sameValidSignal, 3, sameValidStats, error));
    assert(summarizeFloatTensor(changedValidSignal, 3, changedValidStats, error));
    assert(summarizeFloatTensor(allZeroSignal, 3, allZeroStats, error));
    assert(validateFloatTensorSignal(validStats, error));
    assert(validateMaterializedFloatTensorMatch(validStats, sameValidStats, error));
    assert(validStats.fingerprint64 == sameValidStats.fingerprint64);
    assert(validStats.fingerprint64 != changedValidStats.fingerprint64);
    assert(!validateMaterializedFloatTensorMatch(validStats, changedValidStats, error));
    assert(error == "materialized_tensor_fingerprint_mismatch");
    assert(!validateFloatTensorSignal(allZeroStats, error));
    assert(error == "tensor_all_zero");

    // Four bilinear corners are stored corner-major, matching the visual MNN
    // input contract [4, patch_count]. Each patch must address the fixed
    // positional table and its four weights must sum to one.
    const int interpolationIndices[] = {
        0, 1,
        1, 2,
        4, 5,
        5, 6
    };
    const float interpolationWeights[] = {
        1.0f, 0.25f,
        0.0f, 0.25f,
        0.0f, 0.25f,
        0.0f, 0.25f
    };
    InterpolationContractStats interpolationStats;
    assert(validateInterpolationContract(
        interpolationIndices, interpolationWeights, 2, 16, interpolationStats, error));
    assert(interpolationStats.minimumIndex == 0);
    assert(interpolationStats.maximumIndex == 6);
    assert(interpolationStats.minimumWeight == 0.0f);
    assert(interpolationStats.maximumWeight == 1.0f);
    assert(interpolationStats.maximumWeightSumError == 0.0f);

    int invalidIndices[8];
    std::copy(std::begin(interpolationIndices), std::end(interpolationIndices), invalidIndices);
    invalidIndices[7] = 16;
    assert(!validateInterpolationContract(
        invalidIndices, interpolationWeights, 2, 16, interpolationStats, error));
    assert(error == "interpolation_index_out_of_range_at_7");
    return 0;
}
