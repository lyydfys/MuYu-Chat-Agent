#ifndef MCA_DIFFUSION_SCHEDULER_GOLDEN_HPP
#define MCA_DIFFUSION_SCHEDULER_GOLDEN_HPP

#include <array>
#include <cstdint>

namespace mca::diffusion::test_fixture {

inline constexpr std::array<float, 3> kSample = {1.0f, -0.5f, 0.25f};
inline constexpr std::array<float, 3> kModelOutput = {0.1f, -0.2f, 0.3f};

inline constexpr std::array<double, 4> kEulerTimesteps = {999.0, 666.0, 333.0, 0.0};
inline constexpr std::array<double, 5> kEulerSigmas = {
    14.6146555,
    2.9183085,
    0.93235797,
    0.029167533,
    0.0,
};
inline constexpr std::array<float, 3> kEulerScaledSample = {
    0.06826485f,
    -0.034132425f,
    0.017066212f,
};
inline constexpr std::array<float, 3> kEulerPredictedOriginal = {
    -0.4614656f,
    2.4229312f,
    -4.134397f,
};
inline constexpr std::array<float, 3> kEulerPreviousSample = {
    -0.1696347f,
    1.8392694f,
    -3.2589042f,
};

inline constexpr std::array<double, 4> kDdimTimesteps = {751.0, 501.0, 251.0, 1.0};
inline constexpr std::array<float, 3> kDdimEpsilonPredictedOriginal = {
    3.8247457f,
    -1.2948678f,
    -0.17590608f,
};
inline constexpr std::array<float, 3> kDdimEpsilonPreviousSample = {
    2.0908566f,
    -0.8493270f,
    0.16319524f,
};
inline constexpr std::array<float, 3> kDdimVPredictedOriginal = {
    0.13887447f,
    0.076323956f,
    -0.23251024f,
};
inline constexpr std::array<float, 3> kDdimVPreviousSample = {
    0.92033356f,
    -0.41387734f,
    0.14521945f,
};

inline constexpr std::array<double, 5> kPndmSkipPrkTimesteps = {
    751.0,
    501.0,
    501.0,
    251.0,
    1.0,
};
inline constexpr std::array<float, 3> kPndmFirstPreviousSample = {
    2.0908566f,
    -0.8493271f,
    0.16319525f,
};
inline constexpr std::array<float, 3> kPndmSecondPreviousSample = {
    2.1889071f,
    -1.2415295f,
    0.22856233f,
};
inline constexpr std::array<int64_t, 12> kPndmPrkTimesteps = {
    751,
    626,
    626,
    501,
    501,
    376,
    376,
    251,
    251,
    126,
    126,
    1,
};

inline constexpr std::array<double, 20> kSd15DpmppLeadingTimesteps20 = {
    940.0,
    893.0,
    846.0,
    799.0,
    752.0,
    705.0,
    658.0,
    611.0,
    564.0,
    517.0,
    470.0,
    423.0,
    376.0,
    329.0,
    282.0,
    235.0,
    188.0,
    141.0,
    94.0,
    47.0,
};
inline constexpr std::array<double, 21> kSd15DpmppLeadingSigmas20 = {
    10.367029190063477,
    8.046112060546875,
    6.3492865562438965,
    5.0877671241760254,
    4.1343307495117188,
    3.401972770690918,
    2.8303585052490234,
    2.3770802021026611,
    2.0119497776031494,
    1.7132071256637573,
    1.4649727344512939,
    1.2555116415023804,
    1.0760315656661987,
    0.919833242893219,
    0.7816984057426453,
    0.6574068069458008,
    0.5433112978935242,
    0.43581581115722656,
    0.33035483956336975,
    0.21780137717723846,
    0.0,
};

inline constexpr std::array<double, 4> kDpmppTimesteps = {999.0, 749.0, 500.0, 250.0};
inline constexpr std::array<double, 5> kDpmppSigmas = {
    14.6146555,
    4.0817323,
    1.6182793,
    0.69579905,
    0.0,
};
inline constexpr std::array<float, 3> kDpmppFirstPredictedOriginal = {
    13.187362f,
    -4.4014826f,
    -0.72219f,
};
inline constexpr std::array<float, 3> kDpmppFirstPreviousSample = {
    3.2351494f,
    -1.2416177f,
    0.11953278f,
};
inline constexpr std::array<float, 3> kDpmppSecondPredictedOriginal = {
    13.799621f,
    -6.850522f,
    -0.3140166f,
};
inline constexpr std::array<float, 3> kDpmppSecondPreviousSample = {
    7.2820063f,
    -3.5426466f,
    0.052031077f,
};

}  // namespace mca::diffusion::test_fixture

#endif  // MCA_DIFFUSION_SCHEDULER_GOLDEN_HPP
