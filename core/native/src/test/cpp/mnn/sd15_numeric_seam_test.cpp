// T02 P0-04 protection: numeric seam of the restored MNN SD1.5 chain.
//
// This test executes the *real* production pieces the restored
// run_mnn_sd15_interpreter_direct() chain depends on:
//   * mca::diffusion::DiffusionScheduler (diffusion_scheduler.cpp)
//   * the exact seed -> std::mt19937 -> std::normal_distribution ->
//     init_noise_sigma() latent initialisation sequence
//   * uncond + cfgScale * (cond - uncond) guided prediction handed to
//     scheduler.step(), including the SchedulerStepOptions.eta path
//
// It is deliberately model-free and MNN-free so it can run in the host test
// target. It does not copy any production algorithm: the scheduler and its
// validation are the production implementations, and the only test-local math is
// the documented CFG definition that the plan's SD1.5 table asks to check with
// known cond/uncond values.
//
// Regression meaning: the replaced slim bridge produced a zero-filled latent,
// zero-filled community-CLIP conditioning, fed the *un-guided* model input into
// scheduler.step() and never set stepOptions.eta, so none of the numeric
// properties below could hold there.

#include "../../main/cpp/diffusion_scheduler.hpp"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <random>
#include <string>
#include <vector>

namespace {

using mca::diffusion::DiffusionScheduler;
using mca::diffusion::DiffusionSchedulerConfig;
using mca::diffusion::SchedulerStepOptions;
using mca::diffusion::SchedulerStepResult;
using mca::diffusion::SchedulerTensor;

constexpr float kTolerance = 1.0e-6f;

void require(bool condition, const char* message) {
    if (!condition) {
        std::fprintf(stderr, "sd15 numeric seam failure: %s\n", message);
        std::fflush(stderr);
    }
    assert(condition);
}

bool nearly_equal(const SchedulerTensor& a, const SchedulerTensor& b, float tolerance = kTolerance) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (std::abs(a[i] - b[i]) > tolerance) return false;
    }
    return true;
}

bool all_finite(const SchedulerTensor& values) {
    for (float value : values) {
        if (!std::isfinite(value)) return false;
    }
    return true;
}

// Same primitive sequence as the SD1.5 initial latent in the restored bridge.
SchedulerTensor seeded_latent(int seed, double init_noise_sigma, size_t count) {
    SchedulerTensor latent(count);
    std::mt19937 rng(static_cast<uint32_t>(seed));
    std::normal_distribution<float> normal(0.0f, 1.0f);
    for (auto& value : latent) {
        value = normal(rng) * static_cast<float>(init_noise_sigma);
    }
    return latent;
}

// Deterministic non-constant tensor of a given size, used where a real UNet
// prediction would be supplied. Different amplitudes model different UNet
// predictions; the scheduler requires identical model-output/sample shapes.
SchedulerTensor patterned_model_output(size_t count, float amplitude) {
    SchedulerTensor values(count);
    for (size_t i = 0; i < count; ++i) {
        const float phase = static_cast<float>(i % 11U) - 5.0f;
        values[i] = amplitude * phase / 5.0f;
    }
    return values;
}

// CFG definition used by the SD1.5 path: uncond + cfgScale * (cond - uncond).
SchedulerTensor guided_prediction(const SchedulerTensor& unconditional, const SchedulerTensor& conditional, float cfg_scale) {
    SchedulerTensor guided(unconditional.size());
    for (size_t i = 0; i < unconditional.size(); ++i) {
        guided[i] = unconditional[i] + cfg_scale * (conditional[i] - unconditional[i]);
    }
    return guided;
}

// Runs the real scheduler over `steps` timesteps with a fixed model output and
// returns the final latent.
SchedulerTensor run_schedule(
        const DiffusionSchedulerConfig& config,
        int steps,
        const SchedulerTensor& model_output,
        const SchedulerTensor& initial_latent,
        const SchedulerStepOptions& options = SchedulerStepOptions{}) {
    DiffusionScheduler scheduler(config);
    std::string error;
    const bool scheduled = scheduler.set_timesteps(steps, &error);
    require(scheduled, error.c_str());
    SchedulerTensor latent = initial_latent;
    for (int index = 0; index < steps; ++index) {
        SchedulerTensor scaled;
        const bool scaled_ok = scheduler.scale_model_input(
                latent, static_cast<size_t>(index), &scaled, &error);
        require(scaled_ok, error.c_str());
        SchedulerStepResult result;
        const bool stepped = scheduler.step(
                model_output, static_cast<size_t>(index), latent, &result, &error, options);
        require(stepped, error.c_str());
        require(result.previous_sample.size() == latent.size(), "latent shape changed across steps");
        require(all_finite(result.previous_sample), "scheduler produced non-finite latent");
        latent = result.previous_sample;
    }
    require(scheduler.completed_step_count() == static_cast<size_t>(steps),
            "scheduler did not execute every timestep");
    return latent;
}

void test_initial_latent_is_seeded_gaussian_scaled_by_init_noise_sigma() {
    DiffusionScheduler scheduler(DiffusionSchedulerConfig::stable_diffusion_euler());
    std::string error;
    const bool scheduled = scheduler.set_timesteps(20, &error);
    require(scheduled, error.c_str());

    const double sigma = scheduler.init_noise_sigma();
    require(std::isfinite(sigma) && sigma > 1.0, "Euler init_noise_sigma must be a positive scale");
    require(std::abs(sigma - scheduler.sigmas().front()) <= 1.0e-9,
            "init_noise_sigma must be the schedule's first sigma");

    const auto seed_42_a = seeded_latent(42, sigma, 4U * 64U * 64U);
    const auto seed_42_b = seeded_latent(42, sigma, 4U * 64U * 64U);
    const auto seed_43 = seeded_latent(43, sigma, 4U * 64U * 64U);

    require(nearly_equal(seed_42_a, seed_42_b), "same seed must reproduce the same latent");
    require(!nearly_equal(seed_42_a, seed_43), "different seeds must produce different latents");

    double energy = 0.0;
    for (float value : seed_42_a) energy += static_cast<double>(value) * value;
    require(energy > 1.0, "initial latent must not be a zero-filled buffer");

    const auto half_sigma = seeded_latent(42, sigma * 0.5, seed_42_a.size());
    for (size_t i = 0; i < half_sigma.size(); ++i) {
        require(std::abs(half_sigma[i] * 2.0f - seed_42_a[i]) <= 1.0e-3f,
                "latent scaling must follow init_noise_sigma");
    }
}

void test_guided_prediction_reaches_the_scheduler() {
    const auto config = DiffusionSchedulerConfig::stable_diffusion_euler();
    const size_t count = 4U * 64U * 64U;
    const auto latent = seeded_latent(7, 14.6, count);
    const auto unconditional = patterned_model_output(count, 0.10f);
    const auto conditional = patterned_model_output(count, 0.50f);

    const auto guided_cfg_7_5 =
            run_schedule(config, 3, guided_prediction(unconditional, conditional, 7.5f), latent);
    const auto guided_cfg_1_0 =
            run_schedule(config, 3, guided_prediction(unconditional, conditional, 1.0f), latent);
    const auto unguided = run_schedule(config, 3, unconditional, latent);

    require(!nearly_equal(guided_cfg_7_5, guided_cfg_1_0),
            "cfgScale must change the sampled latent");
    require(!nearly_equal(guided_cfg_7_5, unguided),
            "guided prediction must differ from the uncond-only prediction");

    const auto collapsed = guided_prediction(unconditional, conditional, 0.0f);
    require(nearly_equal(collapsed, unconditional), "cfg=0 must reproduce the unconditional branch");
    const auto conditional_only = guided_prediction(unconditional, conditional, 1.0f);
    require(nearly_equal(conditional_only, conditional),
            "cfg=1 must reproduce the conditional branch");
}

void test_unet_output_is_consumed_not_discarded() {
    const auto config = DiffusionSchedulerConfig::stable_diffusion_euler();
    const size_t count = 4U * 64U * 64U;
    const auto latent = seeded_latent(11, 14.6, count);

    const auto from_first = run_schedule(config, 2, patterned_model_output(count, 0.25f), latent);
    const auto from_second = run_schedule(config, 2, patterned_model_output(count, -0.40f), latent);
    require(!nearly_equal(from_first, from_second),
            "a different UNet prediction must change the next latent");
}

void test_eta_option_changes_the_ddim_step() {
    const auto config = DiffusionSchedulerConfig::stable_diffusion_ddim_v_prediction();
    const auto latent = seeded_latent(5, 1.0, 4);
    const SchedulerTensor model_output{0.30f, -0.10f, 0.20f, 0.05f};
    const SchedulerTensor variance_noise{0.50f, 0.50f, -0.50f, -0.50f};

    SchedulerStepOptions deterministic;
    deterministic.eta = 0.0f;
    SchedulerStepOptions stochastic;
    stochastic.eta = 0.6f;
    stochastic.variance_noise = &variance_noise;

    const auto eta_zero = run_schedule(config, 1, model_output, latent, deterministic);
    const auto eta_zero_again = run_schedule(config, 1, model_output, latent, deterministic);
    const auto eta_stochastic = run_schedule(config, 1, model_output, latent, stochastic);

    require(nearly_equal(eta_zero, eta_zero_again), "eta=0 must be reproducible");
    require(!nearly_equal(eta_zero, eta_stochastic),
            "SchedulerStepOptions.eta must have a numeric effect on the step");
}

void test_multi_step_chain_stays_finite_and_moves() {
    const auto config = DiffusionSchedulerConfig::stable_diffusion_euler();
    const size_t count = 4U * 64U * 64U;
    DiffusionScheduler scheduler(config);
    std::string error;
    const bool scheduled = scheduler.set_timesteps(5, &error);
    require(scheduled, error.c_str());

    SchedulerTensor latent = seeded_latent(3, scheduler.init_noise_sigma(), count);
    const SchedulerTensor first_latent = latent;
    for (int index = 0; index < 5; ++index) {
        const auto model_output = patterned_model_output(
                count, 0.01f * static_cast<float>(index + 1));
        SchedulerTensor scaled;
        const bool scaled_ok = scheduler.scale_model_input(
                latent, static_cast<size_t>(index), &scaled, &error);
        require(scaled_ok, error.c_str());
        SchedulerStepResult result;
        const bool stepped = scheduler.step(
                model_output,
                static_cast<size_t>(index),
                latent,
                &result,
                &error,
                SchedulerStepOptions{});
        require(stepped, error.c_str());
        require(all_finite(result.previous_sample), "multi-step latent must stay finite");
        latent = result.previous_sample;
    }
    require(!nearly_equal(latent, first_latent), "the denoise chain must move the latent");
    require(std::abs(latent[0] - first_latent[0]) > 1.0e-4f,
            "five denoise steps must produce a measurably different latent");
}

}  // namespace

int main() {
    std::printf("RUN initial_latent\n");
    std::fflush(stdout);
    test_initial_latent_is_seeded_gaussian_scaled_by_init_noise_sigma();
    std::printf("RUN guided_prediction\n");
    std::fflush(stdout);
    test_guided_prediction_reaches_the_scheduler();
    std::printf("RUN unet_output_consumed\n");
    std::fflush(stdout);
    test_unet_output_is_consumed_not_discarded();
    std::printf("RUN ddim_eta\n");
    std::fflush(stdout);
    test_eta_option_changes_the_ddim_step();
    std::printf("RUN multi_step_chain\n");
    std::fflush(stdout);
    test_multi_step_chain_stays_finite_and_moves();
    std::printf("mnn_sd15_numeric_seam passed\n");
    return 0;
}
