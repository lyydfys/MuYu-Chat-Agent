// Host regression: Euler + trailing publishes the Local Dream sentinel -1 as the
// final timetable entry, which the UNet graph rejects ("UNet timestep must be
// finite and in [0, 999]."). The device run this comes from executed 19 of 20
// steps (~876 s) and then aborted on the last one.
//
// The scheduler must keep producing -1 (diffusion_scheduler.cpp:288-297 builds it
// and :309-313 accepts it for trailing spacing, where it selects the terminal
// sigma). The runner must clamp at the graph boundary
// (mca::mnn::unet_input_timestep, mnn_native_engine.cpp call sites).
//
// Fixture: the exact params the device ran are archived at
// artifacts/repair/20261009-parity/device/mnn-realmodel/device-params-probe.json
// (scheduler/sampleMethod=euler, timestepSpacing=trailing, steps=20,
//  numTrainTimesteps=1000, useCfg=true, expectedUnetExecutionCount=40).
#include "diffusion_scheduler.hpp"
#include "mca/mnn/mnn_unet_timestep.hpp"

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace {

int failures = 0;

void expect(bool condition, const std::string& name, const std::string& detail) {
    if (!condition) ++failures;
    std::printf("%-58s %s  (%s)\n", name.c_str(), condition ? "PASS" : "FAIL", detail.c_str());
}

// Mirrors diffusion_scheduler.cpp:288-297 for the device's declared values.
std::vector<double> euler_trailing_timetable(int num_train, int steps) {
    const double ratio = static_cast<double>(num_train) / steps;
    std::vector<double> values;
    for (int index = 0; index < steps; ++index) {
        const double rounded = std::floor(num_train - (index + 1) * ratio + 0.5);
        values.push_back(rounded - 1.0);
    }
    return values;
}

// The UNetSession guard (mnn_native_engine.cpp UNetSession::run).
bool unet_accepts(double timestep) {
    return std::isfinite(timestep) && timestep >= 0.0 && timestep < 1000.0;
}

}  // namespace

int main() {
    constexpr int kNumTrain = 1000;
    constexpr int kSteps = 20;          // device fixture: steps = 20
    constexpr int kExpectedUnetCalls = 40;  // device fixture: 20 timesteps x CFG(2)

    mca::diffusion::DiffusionSchedulerConfig config;
    config.algorithm = mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
    config.prediction_type = mca::diffusion::PredictionType::Epsilon;
    config.num_train_timesteps = kNumTrain;
    config.beta_schedule = mca::diffusion::BetaSchedule::ScaledLinear;
    config.beta_start = 0.00085f;
    config.beta_end = 0.012f;
    config.timestep_spacing = mca::diffusion::TimestepSpacing::Trailing;
    config.steps_offset = 0;
    config.set_alpha_to_one = false;

    mca::diffusion::DiffusionScheduler scheduler(config);
    std::string error;
    expect(scheduler.set_timesteps(kSteps, &error), "scheduler builds the Euler+trailing timetable", error);

    const std::vector<double>& timesteps = scheduler.timesteps();
    expect(timesteps.size() == static_cast<size_t>(kSteps),
           "timetable length equals the contracted step count",
           "size=" + std::to_string(timesteps.size()));
    expect(timesteps.size() * 2U == static_cast<size_t>(kExpectedUnetCalls),
           "CFG doubles the UNet execution count (20 x 2 = 40)",
           "timetable=" + std::to_string(timesteps.size()) +
                   " expected_unet_calls=" + std::to_string(kExpectedUnetCalls));

    // The sentinel is intentional scheduling state, not a bug to remove here.
    expect(timesteps.front() == 949.0 && timesteps.back() == -1.0,
           "trailing timetable ends on the -1 sentinel (kept by design)",
           "first=" + std::to_string(timesteps.front()) +
                   " last=" + std::to_string(timesteps.back()));

    // Pre-fix behaviour: the runner published the raw value.
    const double raw_last = timesteps.back();
    expect(!unet_accepts(raw_last),
           "publishing the raw sentinel fails the UNet guard (the old defect)",
           "raw=" + std::to_string(raw_last));

    // Post-fix behaviour: the graph boundary clamps, the scheduler keeps -1.
    const double published_last = mca::mnn::unet_input_timestep(raw_last);
    expect(unet_accepts(published_last),
           "clamped value is accepted by the UNet guard",
           "published=" + std::to_string(published_last));

    bool all_accepted = true;
    for (double timestep : timesteps) {
        if (!unet_accepts(mca::mnn::unet_input_timestep(timestep))) all_accepted = false;
    }
    expect(all_accepted, "every step publishes a value the UNet accepts", "checked all steps");

    // The clamp must not rewrite real timesteps.
    expect(mca::mnn::unet_input_timestep(949.0) == 949.0 &&
                   mca::mnn::unet_input_timestep(0.0) == 0.0 &&
                   mca::mnn::unet_input_timestep(-1.0) == 0.0,
           "clamp only maps negative sentinels to zero", "949->949, 0->0, -1->0");

    // The model-side mirror of the scheduler formula must agree with the real one.
    const std::vector<double> mirror = euler_trailing_timetable(kNumTrain, kSteps);
    bool mirror_matches = mirror.size() == timesteps.size();
    for (size_t index = 0; mirror_matches && index < mirror.size(); ++index) {
        mirror_matches = mirror[index] == timesteps[index];
    }
    expect(mirror_matches, "the reference formula in this test matches the scheduler",
           "compared " + std::to_string(mirror.size()) + " entries");

    std::printf("SUMMARY|failures=%d\n", failures);
    return failures == 0 ? 0 : 1;
}
