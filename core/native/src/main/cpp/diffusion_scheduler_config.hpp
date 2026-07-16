#ifndef MCA_DIFFUSION_SCHEDULER_CONFIG_HPP
#define MCA_DIFFUSION_SCHEDULER_CONFIG_HPP

#include <string_view>
#include <vector>

namespace mca::diffusion {

enum class SchedulerAlgorithm {
    EulerDiscrete,
    Ddim,
    Pndm,
    Dpmpp2m,
};

enum class PredictionType {
    Epsilon,
    VPrediction,
    Sample,
};

enum class BetaSchedule {
    Linear,
    ScaledLinear,
    SquaredCosineCapV2,
    Trained,
};

enum class TimestepSpacing {
    Linspace,
    Leading,
    Trailing,
};

enum class FinalSigmaType {
    Zero,
    SigmaMin,
};

struct DiffusionSchedulerConfig {
    SchedulerAlgorithm algorithm = SchedulerAlgorithm::Pndm;
    PredictionType prediction_type = PredictionType::Epsilon;
    BetaSchedule beta_schedule = BetaSchedule::ScaledLinear;
    TimestepSpacing timestep_spacing = TimestepSpacing::Leading;
    FinalSigmaType final_sigma_type = FinalSigmaType::Zero;

    int num_train_timesteps = 1000;
    float beta_start = 0.00085f;
    float beta_end = 0.012f;
    std::vector<float> trained_betas;

    int steps_offset = 1;
    bool set_alpha_to_one = false;
    bool skip_prk_steps = true;

    bool clip_sample = false;
    float clip_sample_range = 1.0f;
    bool thresholding = false;
    bool lower_order_final = true;

    static DiffusionSchedulerConfig stable_diffusion_pndm() {
        return DiffusionSchedulerConfig{};
    }

    static DiffusionSchedulerConfig stable_diffusion_euler() {
        DiffusionSchedulerConfig config;
        config.algorithm = SchedulerAlgorithm::EulerDiscrete;
        config.timestep_spacing = TimestepSpacing::Linspace;
        config.steps_offset = 0;
        return config;
    }

    static DiffusionSchedulerConfig stable_diffusion_ddim_v_prediction() {
        DiffusionSchedulerConfig config;
        config.algorithm = SchedulerAlgorithm::Ddim;
        config.prediction_type = PredictionType::VPrediction;
        config.timestep_spacing = TimestepSpacing::Leading;
        config.steps_offset = 1;
        config.set_alpha_to_one = false;
        config.clip_sample = false;
        return config;
    }

    static DiffusionSchedulerConfig stable_diffusion_dpmpp_2m() {
        DiffusionSchedulerConfig config;
        config.algorithm = SchedulerAlgorithm::Dpmpp2m;
        config.prediction_type = PredictionType::Epsilon;
        config.timestep_spacing = TimestepSpacing::Linspace;
        config.steps_offset = 0;
        config.final_sigma_type = FinalSigmaType::Zero;
        config.clip_sample = false;
        config.lower_order_final = true;
        return config;
    }
};

constexpr std::string_view scheduler_algorithm_name(SchedulerAlgorithm algorithm) {
    switch (algorithm) {
        case SchedulerAlgorithm::EulerDiscrete: return "euler_discrete";
        case SchedulerAlgorithm::Ddim: return "ddim";
        case SchedulerAlgorithm::Pndm: return "pndm_plms";
        case SchedulerAlgorithm::Dpmpp2m: return "dpmpp_2m";
    }
    return "unknown";
}

constexpr std::string_view prediction_type_name(PredictionType type) {
    switch (type) {
        case PredictionType::Epsilon: return "epsilon";
        case PredictionType::VPrediction: return "v_prediction";
        case PredictionType::Sample: return "sample";
    }
    return "unknown";
}

constexpr std::string_view beta_schedule_name(BetaSchedule schedule) {
    switch (schedule) {
        case BetaSchedule::Linear: return "linear";
        case BetaSchedule::ScaledLinear: return "scaled_linear";
        case BetaSchedule::SquaredCosineCapV2: return "squaredcos_cap_v2";
        case BetaSchedule::Trained: return "trained";
    }
    return "unknown";
}

constexpr std::string_view timestep_spacing_name(TimestepSpacing spacing) {
    switch (spacing) {
        case TimestepSpacing::Linspace: return "linspace";
        case TimestepSpacing::Leading: return "leading";
        case TimestepSpacing::Trailing: return "trailing";
    }
    return "unknown";
}

}  // namespace mca::diffusion

#endif  // MCA_DIFFUSION_SCHEDULER_CONFIG_HPP
