#ifndef MCA_IMAGE_EXECUTION_QUALITY_GOLDEN_HPP
#define MCA_IMAGE_EXECUTION_QUALITY_GOLDEN_HPP

#include <array>

namespace mca::image::test_fixture {

inline constexpr const char* kPromptWeightPairFingerprint =
        "4c4cbea57ffe74af9b92e153893eafd8725267ee7478332e883277aee2022575";
inline constexpr float kEmbeddingNormalizationScale = 0.965853691f;
inline constexpr std::array<float, 3> kConditioningSignal = {
        0.443551838f,
        0.450686008f,
        0.457820177f,
};
inline constexpr std::array<float, 4> kLatentAfterStepOne = {
        1.85013843f,
        -8.04030418f,
        8.38827133f,
        -14.5992479f,
};
inline constexpr std::array<float, 4> kLatentAfterStepTwo = {
        1.84157348f,
        -8.02007484f,
        8.36056137f,
        -14.5598545f,
};
inline constexpr std::array<float, 4> kVaeHostScaledLatent = {
        10.1102028f,
        -44.030056f,
        45.8993187f,
        -79.9333191f,
};
inline constexpr const char* kPipelineFingerprint = "dc330db4cced0a8c";

}  // namespace mca::image::test_fixture

#endif  // MCA_IMAGE_EXECUTION_QUALITY_GOLDEN_HPP
