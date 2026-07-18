#include "../../main/cpp/diffusion_scheduler.hpp"
#include "fixtures/diffusion_scheduler_golden.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <string>

namespace {

using mca::diffusion::DiffusionScheduler;
using mca::diffusion::DiffusionSchedulerConfig;
using mca::diffusion::PredictionType;
using mca::diffusion::SchedulerStepResult;
using mca::diffusion::SchedulerTensor;
using mca::diffusion::TimestepSpacing;
using namespace mca::diffusion::test_fixture;

constexpr double kScheduleTolerance = 2.0e-5;
constexpr float kTensorTolerance = 3.0e-5f;

template <typename Expected>
void assert_values_near(const std::vector<double>& actual, const Expected& expected, double tolerance) {
    assert(actual.size() == expected.size());
    for (size_t index = 0; index < actual.size(); ++index) {
        assert(std::abs(actual[index] - expected[index]) <= tolerance);
    }
}

template <typename Expected>
void assert_values_near(const SchedulerTensor& actual, const Expected& expected, float tolerance) {
    assert(actual.size() == expected.size());
    for (size_t index = 0; index < actual.size(); ++index) {
        assert(std::abs(actual[index] - expected[index]) <= tolerance);
    }
}

SchedulerTensor fixture_tensor(const std::array<float, 3>& values) {
    return SchedulerTensor(values.begin(), values.end());
}

void assert_scheduler_ok(bool ok, const std::string& error) {
    if (!ok) std::fprintf(stderr, "scheduler failure: %s\n", error.c_str());
    assert(ok);
}

void test_euler_epsilon() {
    auto config = DiffusionSchedulerConfig::stable_diffusion_euler();
    DiffusionScheduler scheduler(config);
    std::string error;
    assert(scheduler.set_timesteps(4, &error));
    assert_values_near(scheduler.timesteps(), kEulerTimesteps, kScheduleTolerance);
    assert_values_near(scheduler.sigmas(), kEulerSigmas, kScheduleTolerance);
    assert(std::abs(scheduler.init_noise_sigma() - kEulerSigmas.front()) <= kScheduleTolerance);
    assert(scheduler.expected_unet_execution_count() == 4);

    const auto sample = fixture_tensor(kSample);
    const auto model_output = fixture_tensor(kModelOutput);
    SchedulerTensor scaled;
    assert(scheduler.scale_model_input(sample, 0, &scaled, &error));
    assert_values_near(scaled, kEulerScaledSample, kTensorTolerance);

    SchedulerStepResult result;
    assert(scheduler.step(model_output, 0, sample, &result, &error));
    assert_values_near(result.predicted_original_sample, kEulerPredictedOriginal, kTensorTolerance);
    assert_values_near(result.previous_sample, kEulerPreviousSample, kTensorTolerance);
    assert(scheduler.completed_step_count() == 1);

    DiffusionScheduler missing_scale(config);
    assert(missing_scale.set_timesteps(4, &error));
    assert(!missing_scale.step(model_output, 0, sample, &result, &error));
    assert(error.find("scale_model_input") != std::string::npos);
}

void test_ddim_epsilon_and_v_prediction() {
    auto config = DiffusionSchedulerConfig::stable_diffusion_ddim_v_prediction();
    const auto sample = fixture_tensor(kSample);
    const auto model_output = fixture_tensor(kModelOutput);
    std::string error;

    config.prediction_type = PredictionType::Epsilon;
    DiffusionScheduler epsilon(config);
    assert(epsilon.set_timesteps(4, &error));
    assert_values_near(epsilon.timesteps(), kDdimTimesteps, kScheduleTolerance);
    SchedulerStepResult epsilon_result;
    assert(epsilon.step(model_output, 0, sample, &epsilon_result, &error));
    assert_values_near(
            epsilon_result.predicted_original_sample,
            kDdimEpsilonPredictedOriginal,
            kTensorTolerance);
    assert_values_near(epsilon_result.previous_sample, kDdimEpsilonPreviousSample, kTensorTolerance);

    config.prediction_type = PredictionType::VPrediction;
    DiffusionScheduler velocity(config);
    assert(velocity.set_timesteps(4, &error));
    SchedulerStepResult velocity_result;
    assert(velocity.step(model_output, 0, sample, &velocity_result, &error));
    assert_values_near(
            velocity_result.predicted_original_sample,
            kDdimVPredictedOriginal,
            kTensorTolerance);
    assert_values_near(velocity_result.previous_sample, kDdimVPreviousSample, kTensorTolerance);
}

void test_pndm_skip_prk_repeated_timestep_and_cur_sample() {
    auto config = DiffusionSchedulerConfig::stable_diffusion_pndm();
    config.steps_offset = 1;
    config.skip_prk_steps = true;
    DiffusionScheduler scheduler(config);
    std::string error;
    assert(scheduler.set_timesteps(4, &error));
    assert_values_near(scheduler.timesteps(), kPndmSkipPrkTimesteps, kScheduleTolerance);
    assert(scheduler.expected_unet_execution_count() == 5);
    assert(scheduler.timesteps()[1] == scheduler.timesteps()[2]);

    const auto sample = fixture_tensor(kSample);
    const auto first_model_output = fixture_tensor(kModelOutput);
    SchedulerStepResult first;
    assert(scheduler.step(first_model_output, 0, sample, &first, &error));
    assert_values_near(first.previous_sample, kPndmFirstPreviousSample, kTensorTolerance);
    assert(!first.used_saved_sample);

    const SchedulerTensor second_model_output = {-0.05f, 0.4f, 0.2f};
    SchedulerStepResult second;
    assert_scheduler_ok(
            scheduler.step(second_model_output, 1, first.previous_sample, &second, &error),
            error);
    assert(second.used_saved_sample);
    assert(second.timestep == 751.0);
    assert(second.previous_timestep == 501.0);
    assert_values_near(second.previous_sample, kPndmSecondPreviousSample, kTensorTolerance);
}

void test_pndm_prk_and_plms_execution_count() {
    auto config = DiffusionSchedulerConfig::stable_diffusion_pndm();
    config.steps_offset = 1;
    config.skip_prk_steps = false;
    DiffusionScheduler scheduler(config);
    std::string error;
    assert(scheduler.set_timesteps(4, &error));
    assert(scheduler.expected_unet_execution_count() == 13);
    for (size_t index = 0; index < kPndmPrkTimesteps.size(); ++index) {
        assert(scheduler.timesteps()[index] == static_cast<double>(kPndmPrkTimesteps[index]));
    }
    assert(scheduler.timesteps().back() == 1.0);

    SchedulerTensor sample = fixture_tensor(kSample);
    for (size_t index = 0; index < scheduler.timesteps().size(); ++index) {
        const float phase = static_cast<float>(index + 1);
        const SchedulerTensor output = {0.01f * phase, -0.02f * phase, 0.005f * phase};
        SchedulerStepResult result;
        assert(scheduler.step(output, index, sample, &result, &error));
        assert(result.previous_sample.size() == sample.size());
        sample = std::move(result.previous_sample);
    }
    assert(scheduler.completed_step_count() == scheduler.expected_unet_execution_count());
}

void test_twenty_step_schedule_structure() {
    std::string error;

    DiffusionScheduler euler(DiffusionSchedulerConfig::stable_diffusion_euler());
    assert(euler.set_timesteps(20, &error));
    assert(euler.timesteps().size() == 20);
    assert(euler.sigmas().size() == 21);
    assert(euler.timesteps().front() == 999.0);
    assert(euler.timesteps().back() == 0.0);

    DiffusionScheduler ddim(DiffusionSchedulerConfig::stable_diffusion_ddim_v_prediction());
    assert(ddim.set_timesteps(20, &error));
    assert(ddim.timesteps().size() == 20);
    assert(ddim.timesteps().front() == 951.0);
    assert(ddim.timesteps().back() == 1.0);

    DiffusionScheduler pndm(DiffusionSchedulerConfig::stable_diffusion_pndm());
    assert(pndm.set_timesteps(20, &error));
    assert(pndm.timesteps().size() == 21);
    assert(pndm.timesteps().front() == 951.0);
    assert(pndm.timesteps()[1] == 901.0);
    assert(pndm.timesteps()[2] == 901.0);
    assert(pndm.timesteps().back() == 1.0);

    auto sd15_dpmpp_config = DiffusionSchedulerConfig::stable_diffusion_dpmpp_2m();
    sd15_dpmpp_config.timestep_spacing = TimestepSpacing::Leading;
    sd15_dpmpp_config.steps_offset = 0;
    DiffusionScheduler dpmpp(sd15_dpmpp_config);
    assert(dpmpp.set_timesteps(20, &error));
    assert_values_near(
            dpmpp.timesteps(),
            kSd15DpmppLeadingTimesteps20,
            kScheduleTolerance);
    assert_values_near(
            dpmpp.sigmas(),
            kSd15DpmppLeadingSigmas20,
            kScheduleTolerance);
    assert(std::abs(dpmpp.init_noise_sigma() - 1.0) <= kScheduleTolerance);
}

void test_dpmpp_2m_epsilon() {
    auto config = DiffusionSchedulerConfig::stable_diffusion_dpmpp_2m();
    DiffusionScheduler scheduler(config);
    std::string error;
    assert(scheduler.set_timesteps(4, &error));
    assert_values_near(scheduler.timesteps(), kDpmppTimesteps, kScheduleTolerance);
    assert_values_near(scheduler.sigmas(), kDpmppSigmas, kScheduleTolerance);

    const auto sample = fixture_tensor(kSample);
    const auto first_model_output = fixture_tensor(kModelOutput);
    SchedulerStepResult first;
    assert_scheduler_ok(scheduler.step(first_model_output, 0, sample, &first, &error), error);
    assert_values_near(
            first.predicted_original_sample,
            kDpmppFirstPredictedOriginal,
            kTensorTolerance);
    assert_values_near(first.previous_sample, kDpmppFirstPreviousSample, kTensorTolerance);

    const SchedulerTensor second_model_output = {-0.05f, 0.4f, 0.2f};
    SchedulerStepResult second;
    assert_scheduler_ok(
            scheduler.step(second_model_output, 1, first.previous_sample, &second, &error),
            error);
    assert_values_near(
            second.predicted_original_sample,
            kDpmppSecondPredictedOriginal,
            kTensorTolerance);
    assert_values_near(second.previous_sample, kDpmppSecondPreviousSample, kTensorTolerance);
}

void test_dmd2_trailing_four_step_timetable() {
    auto config = DiffusionSchedulerConfig::stable_diffusion_dpmpp_2m();
    config.timestep_spacing = TimestepSpacing::Trailing;
    DiffusionScheduler scheduler(config);
    std::string error;
    assert(scheduler.set_timesteps(4, &error));
    const std::array<double, 4> expected{999.0, 749.0, 499.0, 249.0};
    assert_values_near(scheduler.timesteps(), expected, 0.0);
    assert(scheduler.expected_unet_execution_count() == expected.size());
}

}  // namespace

int main() {
    test_euler_epsilon();
    test_ddim_epsilon_and_v_prediction();
    test_pndm_skip_prk_repeated_timestep_and_cur_sample();
    test_pndm_prk_and_plms_execution_count();
    test_twenty_step_schedule_structure();
    test_dpmpp_2m_epsilon();
    test_dmd2_trailing_four_step_timetable();
    return 0;
}
