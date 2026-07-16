#include "diffusion_scheduler.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <utility>

namespace mca::diffusion {
namespace {

constexpr double kPi = 3.14159265358979323846264338327950288;
constexpr size_t kNoStep = static_cast<size_t>(-1);

bool fail(std::string* error, const char* message) {
    if (error != nullptr) *error = message;
    return false;
}

bool finite_tensor(const SchedulerTensor& tensor) {
    return std::all_of(tensor.begin(), tensor.end(), [](float value) {
        return std::isfinite(value);
    });
}

double rounded(double value) {
    return std::nearbyint(value);
}

std::vector<int64_t> discrete_timesteps_descending(
        int num_train_timesteps,
        int num_inference_steps,
        TimestepSpacing spacing,
        int steps_offset) {
    std::vector<int64_t> values;
    values.reserve(static_cast<size_t>(num_inference_steps));
    if (spacing == TimestepSpacing::Linspace) {
        const double denominator = std::max(1, num_inference_steps - 1);
        for (int index = num_inference_steps - 1; index >= 0; --index) {
            const double timestep = num_inference_steps == 1
                    ? 0.0
                    : static_cast<double>(index) * (num_train_timesteps - 1) / denominator;
            values.push_back(static_cast<int64_t>(rounded(timestep)));
        }
    } else if (spacing == TimestepSpacing::Leading) {
        const int step_ratio = num_train_timesteps / num_inference_steps;
        for (int index = num_inference_steps - 1; index >= 0; --index) {
            values.push_back(static_cast<int64_t>(index * step_ratio + steps_offset));
        }
    } else {
        const double step_ratio = static_cast<double>(num_train_timesteps) / num_inference_steps;
        for (int index = 0; index < num_inference_steps; ++index) {
            values.push_back(static_cast<int64_t>(rounded(num_train_timesteps - index * step_ratio)) - 1);
        }
    }
    return values;
}

std::vector<int64_t> discrete_timesteps_ascending(
        int num_train_timesteps,
        int num_inference_steps,
        TimestepSpacing spacing,
        int steps_offset) {
    auto values = discrete_timesteps_descending(
            num_train_timesteps,
            num_inference_steps,
            spacing,
            steps_offset);
    std::reverse(values.begin(), values.end());
    return values;
}

SchedulerTensor weighted_sum(
        const SchedulerTensor& first,
        float first_weight,
        const SchedulerTensor& second,
        float second_weight) {
    SchedulerTensor output(first.size());
    for (size_t index = 0; index < first.size(); ++index) {
        output[index] = first[index] * first_weight + second[index] * second_weight;
    }
    return output;
}

void add_scaled_in_place(SchedulerTensor* destination, const SchedulerTensor& source, float scale) {
    for (size_t index = 0; index < destination->size(); ++index) {
        (*destination)[index] += source[index] * scale;
    }
}

bool validate_tensor_pair(
        const SchedulerTensor& model_output,
        const SchedulerTensor& sample,
        std::string* error) {
    if (sample.empty() || sample.size() != model_output.size()) {
        return fail(error, "Scheduler sample and model output must be non-empty and have identical sizes.");
    }
    if (!finite_tensor(sample) || !finite_tensor(model_output)) {
        return fail(error, "Scheduler sample and model output must contain only finite values.");
    }
    return true;
}

}  // namespace

DiffusionScheduler::DiffusionScheduler(DiffusionSchedulerConfig config)
    : config_(std::move(config)) {}

bool DiffusionScheduler::build_training_schedule(std::string* error) {
    if (config_.num_train_timesteps <= 0) {
        return fail(error, "Scheduler num_train_timesteps must be positive.");
    }
    if (config_.thresholding) {
        return fail(error, "Dynamic thresholding requires a shaped batch contract and is not supported by the flat scheduler tensor API.");
    }
    if (config_.clip_sample &&
        (!std::isfinite(config_.clip_sample_range) || config_.clip_sample_range <= 0.0f)) {
        return fail(error, "Scheduler clip_sample_range must be finite and positive when clipping is enabled.");
    }

    const size_t count = static_cast<size_t>(config_.num_train_timesteps);
    betas_.clear();
    betas_.reserve(count);
    if (config_.beta_schedule == BetaSchedule::Trained) {
        if (config_.trained_betas.size() != count) {
            return fail(error, "Trained beta schedule length must equal num_train_timesteps.");
        }
        for (float beta : config_.trained_betas) betas_.push_back(static_cast<double>(beta));
    } else {
        if (!(config_.beta_start > 0.0f) || !(config_.beta_end > 0.0f) ||
            config_.beta_start >= 1.0f || config_.beta_end >= 1.0f) {
            return fail(error, "Scheduler beta_start and beta_end must be finite values in (0, 1).");
        }
        for (size_t index = 0; index < count; ++index) {
            const float fraction = count <= 1
                    ? 0.0f
                    : static_cast<float>(index) / static_cast<float>(count - 1);
            float beta = 0.0f;
            if (config_.beta_schedule == BetaSchedule::Linear) {
                beta = config_.beta_start + (config_.beta_end - config_.beta_start) * fraction;
            } else if (config_.beta_schedule == BetaSchedule::ScaledLinear) {
                const float start = std::sqrt(config_.beta_start);
                const float end = std::sqrt(config_.beta_end);
                const float value = start + (end - start) * fraction;
                beta = value * value;
            } else if (config_.beta_schedule == BetaSchedule::SquaredCosineCapV2) {
                const double t1 = static_cast<double>(index) / count;
                const double t2 = static_cast<double>(index + 1) / count;
                const auto alpha_bar = [](double t) {
                    const double angle = ((t + 0.008) / 1.008) * kPi * 0.5;
                    const double cosine = std::cos(angle);
                    return cosine * cosine;
                };
                beta = static_cast<float>(std::min(1.0 - alpha_bar(t2) / alpha_bar(t1), 0.999));
            } else {
                return fail(error, "Unsupported beta schedule.");
            }
            betas_.push_back(static_cast<double>(beta));
        }
    }

    float cumulative = 1.0f;
    alphas_cumprod_.clear();
    alphas_cumprod_.reserve(count);
    for (double beta_value : betas_) {
        const float beta = static_cast<float>(beta_value);
        if (!std::isfinite(beta) || beta <= 0.0f || beta >= 1.0f) {
            return fail(error, "Scheduler beta schedule contains a value outside (0, 1).");
        }
        cumulative = static_cast<float>(cumulative * (1.0f - beta));
        if (!std::isfinite(cumulative) || cumulative <= 0.0f || cumulative > 1.0f) {
            return fail(error, "Scheduler alpha cumulative product is invalid.");
        }
        alphas_cumprod_.push_back(static_cast<double>(cumulative));
    }
    final_alpha_cumprod_ = config_.set_alpha_to_one ? 1.0 : alphas_cumprod_.front();
    return true;
}

bool DiffusionScheduler::set_timesteps(int num_inference_steps, std::string* error) {
    if (num_inference_steps <= 0 || num_inference_steps > config_.num_train_timesteps) {
        return fail(error, "Scheduler inference steps must be in [1, num_train_timesteps].");
    }
    if (!build_training_schedule(error)) return false;

    num_inference_steps_ = num_inference_steps;
    timesteps_.clear();
    sigmas_.clear();
    base_timesteps_.clear();
    prk_timesteps_.clear();
    plms_timesteps_.clear();
    reset_state();

    switch (config_.algorithm) {
        case SchedulerAlgorithm::EulerDiscrete:
            return build_euler_schedule(num_inference_steps, error);
        case SchedulerAlgorithm::Ddim:
            return build_discrete_schedule(num_inference_steps, error);
        case SchedulerAlgorithm::Pndm:
            return build_pndm_schedule(num_inference_steps, error);
        case SchedulerAlgorithm::Dpmpp2m:
            return build_dpmpp_2m_schedule(num_inference_steps, error);
    }
    return fail(error, "Unsupported scheduler algorithm.");
}

void DiffusionScheduler::reset_state() {
    counter_ = 0;
    last_scaled_step_ = kNoStep;
    model_output_history_.clear();
    accumulated_model_output_.clear();
    saved_sample_.clear();
    has_saved_sample_ = false;
}

bool DiffusionScheduler::build_euler_schedule(int num_inference_steps, std::string* error) {
    std::vector<double> schedule;
    schedule.reserve(static_cast<size_t>(num_inference_steps));
    if (config_.timestep_spacing == TimestepSpacing::Linspace) {
        const double denominator = std::max(1, num_inference_steps - 1);
        for (int index = num_inference_steps - 1; index >= 0; --index) {
            const float timestep = num_inference_steps == 1
                    ? 0.0f
                    : static_cast<float>(index * (config_.num_train_timesteps - 1) / denominator);
            schedule.push_back(static_cast<double>(timestep));
        }
    } else if (config_.timestep_spacing == TimestepSpacing::Leading) {
        const int step_ratio = config_.num_train_timesteps / num_inference_steps;
        for (int index = num_inference_steps - 1; index >= 0; --index) {
            schedule.push_back(static_cast<double>(static_cast<float>(index * step_ratio + config_.steps_offset)));
        }
    } else {
        const double step_ratio = static_cast<double>(config_.num_train_timesteps) / num_inference_steps;
        for (int index = 0; index < num_inference_steps; ++index) {
            const float timestep = static_cast<float>(rounded(config_.num_train_timesteps - index * step_ratio) - 1.0);
            schedule.push_back(static_cast<double>(timestep));
        }
    }

    std::vector<float> training_sigmas(alphas_cumprod_.size());
    for (size_t index = 0; index < alphas_cumprod_.size(); ++index) {
        const float alpha = static_cast<float>(alphas_cumprod_[index]);
        training_sigmas[index] = std::sqrt((1.0f - alpha) / alpha);
    }

    timesteps_ = std::move(schedule);
    sigmas_.reserve(timesteps_.size() + 1);
    for (double timestep : timesteps_) {
        if (!std::isfinite(timestep) || timestep < 0.0 || timestep > config_.num_train_timesteps - 1) {
            return fail(error, "Euler timestep is outside the training schedule.");
        }
        const size_t low = static_cast<size_t>(std::floor(timestep));
        const size_t high = std::min(low + 1, training_sigmas.size() - 1);
        const double weight = timestep - static_cast<double>(low);
        const float sigma = static_cast<float>(
                training_sigmas[low] + (training_sigmas[high] - training_sigmas[low]) * weight);
        sigmas_.push_back(static_cast<double>(sigma));
    }
    const double final_sigma = config_.final_sigma_type == FinalSigmaType::SigmaMin
            ? training_sigmas.front()
            : 0.0;
    sigmas_.push_back(final_sigma);

    const double max_sigma = *std::max_element(sigmas_.begin(), sigmas_.end());
    init_noise_sigma_ = config_.timestep_spacing == TimestepSpacing::Leading
            ? std::sqrt(max_sigma * max_sigma + 1.0)
            : max_sigma;
    return true;
}

bool DiffusionScheduler::build_discrete_schedule(int num_inference_steps, std::string* error) {
    const auto discrete = discrete_timesteps_descending(
            config_.num_train_timesteps,
            num_inference_steps,
            config_.timestep_spacing,
            config_.steps_offset);
    timesteps_.reserve(discrete.size());
    for (int64_t timestep : discrete) {
        if (timestep < 0 || timestep >= config_.num_train_timesteps) {
            return fail(error, "DDIM timestep is outside the training schedule.");
        }
        timesteps_.push_back(static_cast<double>(timestep));
    }
    init_noise_sigma_ = 1.0;
    return true;
}

bool DiffusionScheduler::build_pndm_schedule(int num_inference_steps, std::string* error) {
    if (config_.prediction_type == PredictionType::Sample) {
        return fail(error, "PNDM supports epsilon and v_prediction outputs only.");
    }
    if (config_.skip_prk_steps && num_inference_steps < 2) {
        return fail(error, "PNDM skip-PRK requires at least two inference steps.");
    }
    if (!config_.skip_prk_steps && num_inference_steps < 4) {
        return fail(error, "PNDM PRK warmup requires at least four inference steps.");
    }

    base_timesteps_ = discrete_timesteps_ascending(
            config_.num_train_timesteps,
            num_inference_steps,
            config_.timestep_spacing,
            config_.steps_offset);
    for (int64_t timestep : base_timesteps_) {
        if (timestep < 0 || timestep >= config_.num_train_timesteps) {
            return fail(error, "PNDM timestep is outside the training schedule.");
        }
    }

    if (config_.skip_prk_steps) {
        plms_timesteps_.insert(
                plms_timesteps_.end(),
                base_timesteps_.begin(),
                base_timesteps_.end() - 1);
        plms_timesteps_.push_back(base_timesteps_[base_timesteps_.size() - 2]);
        plms_timesteps_.push_back(base_timesteps_.back());
        std::reverse(plms_timesteps_.begin(), plms_timesteps_.end());
    } else {
        const int64_t half_step = config_.num_train_timesteps / num_inference_steps / 2;
        std::vector<int64_t> expanded;
        expanded.reserve(8);
        const size_t start = base_timesteps_.size() - 4;
        for (size_t index = 0; index < 4; ++index) {
            const int64_t timestep = base_timesteps_[start + index];
            expanded.push_back(timestep);
            expanded.push_back(timestep + half_step);
        }
        expanded.pop_back();
        std::vector<int64_t> repeated;
        repeated.reserve(expanded.size() * 2);
        for (int64_t value : expanded) {
            repeated.push_back(value);
            repeated.push_back(value);
        }
        prk_timesteps_.assign(repeated.begin() + 1, repeated.end() - 1);
        std::reverse(prk_timesteps_.begin(), prk_timesteps_.end());

        plms_timesteps_.assign(base_timesteps_.begin(), base_timesteps_.end() - 3);
        std::reverse(plms_timesteps_.begin(), plms_timesteps_.end());
    }

    timesteps_.reserve(prk_timesteps_.size() + plms_timesteps_.size());
    for (int64_t value : prk_timesteps_) timesteps_.push_back(static_cast<double>(value));
    for (int64_t value : plms_timesteps_) timesteps_.push_back(static_cast<double>(value));
    init_noise_sigma_ = 1.0;
    return true;
}

bool DiffusionScheduler::build_dpmpp_2m_schedule(int num_inference_steps, std::string* error) {
    std::vector<int64_t> discrete;
    discrete.reserve(static_cast<size_t>(num_inference_steps));
    if (config_.timestep_spacing == TimestepSpacing::Linspace) {
        for (int index = num_inference_steps; index >= 1; --index) {
            const double timestep = static_cast<double>(index) *
                    (config_.num_train_timesteps - 1) / num_inference_steps;
            discrete.push_back(static_cast<int64_t>(rounded(timestep)));
        }
    } else if (config_.timestep_spacing == TimestepSpacing::Leading) {
        const int step_ratio = config_.num_train_timesteps / (num_inference_steps + 1);
        for (int index = num_inference_steps; index >= 1; --index) {
            discrete.push_back(static_cast<int64_t>(index * step_ratio + config_.steps_offset));
        }
    } else {
        const double step_ratio = static_cast<double>(config_.num_train_timesteps) / num_inference_steps;
        for (int index = 0; index < num_inference_steps; ++index) {
            discrete.push_back(static_cast<int64_t>(rounded(
                    config_.num_train_timesteps - index * step_ratio)) - 1);
        }
    }

    std::vector<float> training_sigmas(alphas_cumprod_.size());
    for (size_t index = 0; index < alphas_cumprod_.size(); ++index) {
        const float alpha = static_cast<float>(alphas_cumprod_[index]);
        training_sigmas[index] = std::sqrt((1.0f - alpha) / alpha);
    }
    timesteps_.reserve(discrete.size());
    sigmas_.reserve(discrete.size() + 1);
    for (int64_t timestep : discrete) {
        if (timestep < 0 || timestep >= config_.num_train_timesteps) {
            return fail(error, "DPM++ 2M timestep is outside the training schedule.");
        }
        timesteps_.push_back(static_cast<double>(timestep));
        sigmas_.push_back(static_cast<double>(training_sigmas[static_cast<size_t>(timestep)]));
    }
    sigmas_.push_back(config_.final_sigma_type == FinalSigmaType::SigmaMin
            ? static_cast<double>(training_sigmas.front())
            : 0.0);
    init_noise_sigma_ = 1.0;
    return true;
}

bool DiffusionScheduler::scale_model_input(
        const SchedulerTensor& sample,
        size_t schedule_index,
        SchedulerTensor* scaled_sample,
        std::string* error) {
    if (scaled_sample == nullptr) return fail(error, "Scheduler scaled sample output is null.");
    if (schedule_index >= timesteps_.size() || schedule_index != counter_) {
        return fail(error, "Scheduler model-input scale index does not match the next execution step.");
    }
    if (sample.empty() || !finite_tensor(sample)) {
        return fail(error, "Scheduler model input must be non-empty and finite.");
    }
    *scaled_sample = sample;
    if (config_.algorithm == SchedulerAlgorithm::EulerDiscrete) {
        if (schedule_index >= sigmas_.size() - 1) {
            return fail(error, "Euler sigma index is outside the inference schedule.");
        }
        const double sigma = sigmas_[schedule_index];
        const float scale = static_cast<float>(1.0 / std::sqrt(sigma * sigma + 1.0));
        for (float& value : *scaled_sample) value *= scale;
    }
    last_scaled_step_ = schedule_index;
    return true;
}

bool DiffusionScheduler::step(
        const SchedulerTensor& model_output,
        size_t schedule_index,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error,
        const SchedulerStepOptions& options) {
    if (result == nullptr) return fail(error, "Scheduler step result is null.");
    if (schedule_index >= timesteps_.size() || schedule_index != counter_) {
        return fail(error, "Scheduler step index does not match the next timestep.");
    }
    if (!validate_tensor_pair(model_output, sample, error)) return false;
    if (config_.algorithm == SchedulerAlgorithm::EulerDiscrete && last_scaled_step_ != schedule_index) {
        return fail(error, "Euler scheduler requires scale_model_input before every model execution.");
    }
    if (options.eta < 0.0f || !std::isfinite(options.eta)) {
        return fail(error, "Scheduler eta must be finite and non-negative.");
    }

    bool ok = false;
    switch (config_.algorithm) {
        case SchedulerAlgorithm::EulerDiscrete:
            ok = step_euler(model_output, schedule_index, sample, result, error);
            break;
        case SchedulerAlgorithm::Ddim:
            ok = step_ddim(model_output, schedule_index, sample, result, error, options);
            break;
        case SchedulerAlgorithm::Pndm:
            ok = step_pndm(model_output, schedule_index, sample, result, error);
            break;
        case SchedulerAlgorithm::Dpmpp2m:
            ok = step_dpmpp_2m(model_output, schedule_index, sample, result, error);
            break;
    }
    if (ok) last_scaled_step_ = kNoStep;
    return ok;
}

bool DiffusionScheduler::step_euler(
        const SchedulerTensor& model_output,
        size_t schedule_index,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error) {
    const double sigma = sigmas_[schedule_index];
    const double next_sigma = sigmas_[schedule_index + 1];
    if (!(sigma > 0.0) || !std::isfinite(sigma) || !std::isfinite(next_sigma)) {
        return fail(error, "Euler scheduler encountered an invalid sigma.");
    }

    SchedulerTensor predicted(sample.size());
    for (size_t index = 0; index < sample.size(); ++index) {
        if (config_.prediction_type == PredictionType::Epsilon) {
            predicted[index] = static_cast<float>(sample[index] - sigma * model_output[index]);
        } else if (config_.prediction_type == PredictionType::VPrediction) {
            const double normalizer = std::sqrt(sigma * sigma + 1.0);
            predicted[index] = static_cast<float>(
                    model_output[index] * (-sigma / normalizer) + sample[index] / (sigma * sigma + 1.0));
        } else {
            predicted[index] = model_output[index];
        }
    }

    SchedulerTensor previous(sample.size());
    const double delta = next_sigma - sigma;
    for (size_t index = 0; index < sample.size(); ++index) {
        const double derivative = (sample[index] - predicted[index]) / sigma;
        previous[index] = static_cast<float>(sample[index] + derivative * delta);
    }
    if (!finite_tensor(previous) || !finite_tensor(predicted)) {
        return fail(error, "Euler scheduler produced a non-finite sample.");
    }

    result->previous_sample = std::move(previous);
    result->predicted_original_sample = std::move(predicted);
    result->timestep = timesteps_[schedule_index];
    result->previous_timestep = schedule_index + 1 < timesteps_.size()
            ? timesteps_[schedule_index + 1]
            : -1.0;
    result->used_saved_sample = false;
    ++counter_;
    return true;
}

bool DiffusionScheduler::step_ddim(
        const SchedulerTensor& model_output,
        size_t schedule_index,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error,
        const SchedulerStepOptions& options) {
    const int64_t timestep = static_cast<int64_t>(timesteps_[schedule_index]);
    const int64_t previous_timestep = timestep - config_.num_train_timesteps / num_inference_steps_;
    const double alpha = alphas_cumprod_[static_cast<size_t>(timestep)];
    const double alpha_previous = previous_timestep >= 0
            ? alphas_cumprod_[static_cast<size_t>(previous_timestep)]
            : final_alpha_cumprod_;
    const double beta = 1.0 - alpha;
    const double beta_previous = 1.0 - alpha_previous;
    if (!(alpha > 0.0) || !(alpha_previous > 0.0) || beta < 0.0 || beta_previous < 0.0) {
        return fail(error, "DDIM scheduler encountered invalid alpha coefficients.");
    }

    const double sqrt_alpha = std::sqrt(alpha);
    const double sqrt_beta = std::sqrt(beta);
    SchedulerTensor predicted(sample.size());
    SchedulerTensor epsilon(sample.size());
    for (size_t index = 0; index < sample.size(); ++index) {
        if (config_.prediction_type == PredictionType::Epsilon) {
            predicted[index] = static_cast<float>((sample[index] - sqrt_beta * model_output[index]) / sqrt_alpha);
            epsilon[index] = model_output[index];
        } else if (config_.prediction_type == PredictionType::VPrediction) {
            predicted[index] = static_cast<float>(sqrt_alpha * sample[index] - sqrt_beta * model_output[index]);
            epsilon[index] = static_cast<float>(sqrt_alpha * model_output[index] + sqrt_beta * sample[index]);
        } else {
            predicted[index] = model_output[index];
            epsilon[index] = beta > 0.0
                    ? static_cast<float>((sample[index] - sqrt_alpha * predicted[index]) / sqrt_beta)
                    : 0.0f;
        }
        if (config_.clip_sample) {
            predicted[index] = std::clamp(
                    predicted[index],
                    -config_.clip_sample_range,
                    config_.clip_sample_range);
        }
    }

    const double variance = beta > 0.0
            ? std::max(0.0, (beta_previous / beta) * (1.0 - alpha / alpha_previous))
            : 0.0;
    const double standard_deviation = options.eta * std::sqrt(variance);
    if (options.use_clipped_model_output) {
        if (!(beta > 0.0)) return fail(error, "DDIM cannot rederive epsilon when beta is zero.");
        for (size_t index = 0; index < sample.size(); ++index) {
            epsilon[index] = static_cast<float>((sample[index] - sqrt_alpha * predicted[index]) / sqrt_beta);
        }
    }
    if (standard_deviation > 0.0 &&
        (options.variance_noise == nullptr || options.variance_noise->size() != sample.size() ||
         !finite_tensor(*options.variance_noise))) {
        return fail(error, "DDIM eta > 0 requires a finite variance_noise tensor matching the sample size.");
    }

    const double direction_scale = std::sqrt(std::max(0.0, 1.0 - alpha_previous - standard_deviation * standard_deviation));
    SchedulerTensor previous(sample.size());
    for (size_t index = 0; index < sample.size(); ++index) {
        double value = std::sqrt(alpha_previous) * predicted[index] + direction_scale * epsilon[index];
        if (standard_deviation > 0.0) {
            value += standard_deviation * (*options.variance_noise)[index];
        }
        previous[index] = static_cast<float>(value);
    }
    if (!finite_tensor(previous) || !finite_tensor(predicted)) {
        return fail(error, "DDIM scheduler produced a non-finite sample.");
    }

    result->previous_sample = std::move(previous);
    result->predicted_original_sample = std::move(predicted);
    result->timestep = static_cast<double>(timestep);
    result->previous_timestep = static_cast<double>(previous_timestep);
    result->used_saved_sample = false;
    ++counter_;
    return true;
}

bool DiffusionScheduler::step_pndm(
        const SchedulerTensor& model_output,
        size_t,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error) {
    const int64_t timestep = static_cast<int64_t>(timesteps_[counter_]);
    if (!config_.skip_prk_steps && counter_ < prk_timesteps_.size()) {
        return step_pndm_prk(model_output, timestep, sample, result, error);
    }
    return step_pndm_plms(model_output, timestep, sample, result, error);
}

bool DiffusionScheduler::step_dpmpp_2m(
        const SchedulerTensor& model_output,
        size_t schedule_index,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error) {
    const double sigma_current = sigmas_[schedule_index];
    const double sigma_next = sigmas_[schedule_index + 1];
    if (!(sigma_current > 0.0) || sigma_next < 0.0 ||
        !std::isfinite(sigma_current) || !std::isfinite(sigma_next)) {
        return fail(error, "DPM++ 2M scheduler encountered an invalid sigma.");
    }
    const auto alpha_sigma = [](double sigma) {
        const double alpha = 1.0 / std::sqrt(sigma * sigma + 1.0);
        return std::pair<double, double>(alpha, sigma * alpha);
    };
    const auto [alpha_current, sigma_current_normalized] = alpha_sigma(sigma_current);

    SchedulerTensor converted(model_output.size());
    for (size_t index = 0; index < converted.size(); ++index) {
        if (config_.prediction_type == PredictionType::Epsilon) {
            converted[index] = static_cast<float>(
                    (sample[index] - sigma_current_normalized * model_output[index]) / alpha_current);
        } else if (config_.prediction_type == PredictionType::VPrediction) {
            converted[index] = static_cast<float>(
                    alpha_current * sample[index] - sigma_current_normalized * model_output[index]);
        } else {
            converted[index] = model_output[index];
        }
    }
    if (!finite_tensor(converted)) {
        return fail(error, "DPM++ 2M model-output conversion produced non-finite values.");
    }
    if (model_output_history_.size() >= 2) {
        model_output_history_.erase(model_output_history_.begin(), model_output_history_.end() - 1);
    }
    model_output_history_.push_back(converted);

    const auto [alpha_next, sigma_next_normalized] = alpha_sigma(sigma_next);
    const double lambda_current = std::log(alpha_current) - std::log(sigma_current_normalized);
    const double lambda_next = sigma_next_normalized == 0.0
            ? std::numeric_limits<double>::infinity()
            : std::log(alpha_next) - std::log(sigma_next_normalized);
    const double h = lambda_next - lambda_current;
    const double exponential_delta = std::expm1(-h);
    if (!std::isfinite(exponential_delta)) {
        return fail(error, "DPM++ 2M exponential integration coefficient is invalid.");
    }

    const bool final_step = schedule_index + 1 == timesteps_.size();
    const bool lower_order_final = final_step &&
            ((config_.lower_order_final && timesteps_.size() < 15) ||
             config_.final_sigma_type == FinalSigmaType::Zero);
    SchedulerTensor previous(sample.size());
    if (counter_ == 0 || lower_order_final) {
        const double sample_scale = sigma_current_normalized > 0.0
                ? sigma_next_normalized / sigma_current_normalized
                : 0.0;
        const double model_scale = -alpha_next * exponential_delta;
        for (size_t index = 0; index < previous.size(); ++index) {
            previous[index] = static_cast<float>(
                    sample_scale * sample[index] + model_scale * converted[index]);
        }
    } else {
        const double sigma_previous = sigmas_[schedule_index - 1];
        const auto [alpha_previous, sigma_previous_normalized] = alpha_sigma(sigma_previous);
        const double lambda_previous =
                std::log(alpha_previous) - std::log(sigma_previous_normalized);
        const double h_previous = lambda_current - lambda_previous;
        const double ratio = h_previous / h;
        if (!std::isfinite(ratio) || std::abs(ratio) < std::numeric_limits<double>::epsilon()) {
            return fail(error, "DPM++ 2M history ratio is invalid.");
        }
        const auto& current_output = model_output_history_.back();
        const auto& previous_output = model_output_history_[model_output_history_.size() - 2];
        const double sample_scale = sigma_next_normalized / sigma_current_normalized;
        const double model_scale = -alpha_next * exponential_delta;
        for (size_t index = 0; index < previous.size(); ++index) {
            const double first_derivative =
                    (current_output[index] - previous_output[index]) / ratio;
            previous[index] = static_cast<float>(
                    sample_scale * sample[index] +
                    model_scale * current_output[index] +
                    0.5 * model_scale * first_derivative);
        }
    }
    if (!finite_tensor(previous)) {
        return fail(error, "DPM++ 2M scheduler produced a non-finite sample.");
    }

    result->previous_sample = std::move(previous);
    result->predicted_original_sample = std::move(converted);
    result->timestep = timesteps_[schedule_index];
    result->previous_timestep = schedule_index + 1 < timesteps_.size()
            ? timesteps_[schedule_index + 1]
            : -1.0;
    result->used_saved_sample = false;
    ++counter_;
    return true;
}

bool DiffusionScheduler::step_pndm_prk(
        const SchedulerTensor& model_output,
        int64_t timestep,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error) {
    const int64_t difference = counter_ % 2 == 0
            ? config_.num_train_timesteps / num_inference_steps_ / 2
            : 0;
    const int64_t previous_timestep = timestep - difference;
    const int64_t base_timestep = prk_timesteps_[(counter_ / 4) * 4];
    SchedulerTensor adjusted = model_output;

    if (counter_ % 4 == 0) {
        accumulated_model_output_.assign(model_output.size(), 0.0f);
        add_scaled_in_place(&accumulated_model_output_, model_output, 1.0f / 6.0f);
        model_output_history_.push_back(model_output);
        saved_sample_ = sample;
        has_saved_sample_ = true;
    } else if ((counter_ - 1) % 4 == 0) {
        add_scaled_in_place(&accumulated_model_output_, model_output, 1.0f / 3.0f);
    } else if ((counter_ - 2) % 4 == 0) {
        add_scaled_in_place(&accumulated_model_output_, model_output, 1.0f / 3.0f);
    } else {
        adjusted = accumulated_model_output_;
        add_scaled_in_place(&adjusted, model_output, 1.0f / 6.0f);
        accumulated_model_output_.clear();
    }

    const SchedulerTensor& base_sample = has_saved_sample_ ? saved_sample_ : sample;
    SchedulerTensor previous;
    if (!pndm_previous_sample(
            base_sample,
            base_timestep,
            previous_timestep,
            adjusted,
            &previous,
            error)) {
        return false;
    }
    result->previous_sample = std::move(previous);
    result->predicted_original_sample.clear();
    result->timestep = static_cast<double>(base_timestep);
    result->previous_timestep = static_cast<double>(previous_timestep);
    result->used_saved_sample = has_saved_sample_;
    ++counter_;
    return true;
}

bool DiffusionScheduler::step_pndm_plms(
        const SchedulerTensor& model_output,
        int64_t timestep,
        const SchedulerTensor& sample,
        SchedulerStepResult* result,
        std::string* error) {
    if (!config_.skip_prk_steps && model_output_history_.size() < 3) {
        return fail(error, "PNDM PLMS requires the complete PRK warmup history.");
    }
    const int64_t step_ratio = config_.num_train_timesteps / num_inference_steps_;
    int64_t previous_timestep = timestep - step_ratio;
    int64_t effective_timestep = timestep;

    if (counter_ != 1) {
        if (model_output_history_.size() > 3) {
            model_output_history_.erase(
                    model_output_history_.begin(),
                    model_output_history_.end() - 3);
        }
        model_output_history_.push_back(model_output);
    } else {
        previous_timestep = timestep;
        effective_timestep = timestep + step_ratio;
    }

    SchedulerTensor adjusted;
    const SchedulerTensor* base_sample = &sample;
    bool used_saved_sample = false;
    bool release_saved_sample = false;
    const size_t history_size = model_output_history_.size();
    if (history_size == 1 && counter_ == 0) {
        adjusted = model_output;
        saved_sample_ = sample;
        has_saved_sample_ = true;
    } else if (history_size == 1 && counter_ == 1) {
        adjusted = weighted_sum(model_output, 0.5f, model_output_history_.back(), 0.5f);
        if (!has_saved_sample_ || saved_sample_.size() != sample.size()) {
            return fail(error, "PNDM second PLMS estimate is missing the first curSample.");
        }
        base_sample = &saved_sample_;
        used_saved_sample = true;
        release_saved_sample = true;
    } else if (history_size == 2) {
        adjusted = weighted_sum(
                model_output_history_[1],
                1.5f,
                model_output_history_[0],
                -0.5f);
    } else if (history_size == 3) {
        adjusted.resize(model_output.size());
        for (size_t index = 0; index < adjusted.size(); ++index) {
            adjusted[index] = (
                    23.0f * model_output_history_[2][index] -
                    16.0f * model_output_history_[1][index] +
                    5.0f * model_output_history_[0][index]) / 12.0f;
        }
    } else if (history_size >= 4) {
        adjusted.resize(model_output.size());
        const size_t last = history_size - 1;
        for (size_t index = 0; index < adjusted.size(); ++index) {
            adjusted[index] = (
                    55.0f * model_output_history_[last][index] -
                    59.0f * model_output_history_[last - 1][index] +
                    37.0f * model_output_history_[last - 2][index] -
                    9.0f * model_output_history_[last - 3][index]) / 24.0f;
        }
    } else {
        return fail(error, "PNDM PLMS has no model-output history.");
    }

    SchedulerTensor previous;
    if (!pndm_previous_sample(
            *base_sample,
            effective_timestep,
            previous_timestep,
            adjusted,
            &previous,
            error)) {
        return false;
    }
    if (release_saved_sample) {
        saved_sample_.clear();
        has_saved_sample_ = false;
    }
    result->previous_sample = std::move(previous);
    result->predicted_original_sample.clear();
    result->timestep = static_cast<double>(effective_timestep);
    result->previous_timestep = static_cast<double>(previous_timestep);
    result->used_saved_sample = used_saved_sample;
    ++counter_;
    return true;
}

bool DiffusionScheduler::pndm_previous_sample(
        const SchedulerTensor& sample,
        int64_t timestep,
        int64_t previous_timestep,
        const SchedulerTensor& model_output,
        SchedulerTensor* previous_sample,
        std::string* error) const {
    if (previous_sample == nullptr || sample.size() != model_output.size()) {
        return fail(error, "PNDM previous-sample tensors are invalid.");
    }
    if (timestep < 0 || timestep >= config_.num_train_timesteps) {
        return fail(error, "PNDM effective timestep is outside the training schedule.");
    }
    const double alpha = alphas_cumprod_[static_cast<size_t>(timestep)];
    const double alpha_previous = previous_timestep >= 0
            ? (previous_timestep < config_.num_train_timesteps
                ? alphas_cumprod_[static_cast<size_t>(previous_timestep)]
                : -1.0)
            : final_alpha_cumprod_;
    if (!(alpha > 0.0) || !(alpha_previous > 0.0)) {
        return fail(error, "PNDM alpha lookup is invalid.");
    }
    const double beta = 1.0 - alpha;
    const double beta_previous = 1.0 - alpha_previous;
    const double sample_coefficient = std::sqrt(alpha_previous / alpha);
    const double output_denominator =
            alpha * std::sqrt(beta_previous) + std::sqrt(alpha * beta * alpha_previous);
    if (!(output_denominator > 0.0) || !std::isfinite(output_denominator)) {
        return fail(error, "PNDM integration denominator is invalid.");
    }

    previous_sample->resize(sample.size());
    for (size_t index = 0; index < sample.size(); ++index) {
        double output = model_output[index];
        if (config_.prediction_type == PredictionType::VPrediction) {
            output = std::sqrt(alpha) * output + std::sqrt(beta) * sample[index];
        }
        (*previous_sample)[index] = static_cast<float>(
                sample_coefficient * sample[index] -
                (alpha_previous - alpha) * output / output_denominator);
    }
    if (!finite_tensor(*previous_sample)) {
        return fail(error, "PNDM scheduler produced a non-finite sample.");
    }
    return true;
}

}  // namespace mca::diffusion
