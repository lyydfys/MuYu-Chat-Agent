// T2B18 / R7-C7 (2026-10-14): split out of image_execution_math.cpp (pre-split sha256
// 3ceca72fded2223d85e89566d0909a77c6c75c3a72b9f0df13bfaf175343c17a).  Body lines are byte-exact
// relocations; every delta is registered in b-SPLIT-PLAN.json.

#include "image_execution_math.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <utility>

#include "image_execution_math_internal.hpp"

namespace mca::image {

namespace {
bool valid_ultrafix_noise_level(const UltraFixNoiseLevel& level) {
    return std::isfinite(level.alpha) && level.alpha > 0.0 &&
        std::isfinite(level.sigma) && level.sigma >= 0.0 &&
        std::isfinite(level.scheduler_input_scale) &&
        level.scheduler_input_scale > 0.0;
}

uint64_t splitmix64(uint64_t* state) {
    *state += 0x9e3779b97f4a7c15ULL;
    uint64_t value = *state;
    value = (value ^ (value >> 30U)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27U)) * 0x94d049bb133111ebULL;
    return value ^ (value >> 31U);
}

double deterministic_open_unit(uint64_t* state) {
    constexpr double kUnit53 = 1.0 / 9007199254740992.0;
    const uint64_t bits = splitmix64(state) >> 11U;
    return (static_cast<double>(bits) + 0.5) * kUnit53;
}

bool deterministic_gaussian_tensor(
        size_t count,
        uint64_t request_seed,
        uint32_t refinement_step,
        std::vector<float>* values,
        std::string* error) {
    if (values == nullptr || error == nullptr || count == 0U) {
        return fail(error, "UltraFix deterministic noise requires a non-empty destination.");
    }
    uint64_t state = request_seed ^ 0x554c545241464958ULL ^
        (static_cast<uint64_t>(refinement_step) * 0xd1342543de82ef95ULL);
    values->resize(count);
    constexpr double kTwoPi = 6.283185307179586476925286766559;
    for (size_t index = 0U; index < count; index += 2U) {
        const double first = deterministic_open_unit(&state);
        const double second = deterministic_open_unit(&state);
        const double radius = std::sqrt(-2.0 * std::log(first));
        const double angle = kTwoPi * second;
        (*values)[index] = static_cast<float>(radius * std::cos(angle));
        if (index + 1U < count) {
            (*values)[index + 1U] = static_cast<float>(radius * std::sin(angle));
        }
    }
    if (!finite_values(*values)) {
        values->clear();
        return fail(error, "UltraFix deterministic noise produced a non-finite value.");
    }
    error->clear();
    return true;
}

bool gaussian_blur_nchw(
        const std::vector<float>& source,
        const SpatialTensorShape& shape,
        size_t radius,
        std::vector<float>* blurred,
        std::string* error) {
    if (blurred == nullptr || error == nullptr || shape.batch != 1U ||
        shape.layout != SpatialTensorLayout::Nchw || radius == 0U || radius > 64U ||
        source.size() != shape.element_count() || !finite_values(source)) {
        return fail(error, "UltraFix blur requires one finite NCHW tensor and a bounded radius.");
    }
    const double sigma = std::max(0.5, static_cast<double>(radius) / 2.0);
    std::vector<double> kernel(radius * 2U + 1U, 0.0);
    double kernel_sum = 0.0;
    for (size_t index = 0U; index < kernel.size(); ++index) {
        const double offset = static_cast<double>(index) - static_cast<double>(radius);
        kernel[index] = std::exp(-(offset * offset) / (2.0 * sigma * sigma));
        kernel_sum += kernel[index];
    }
    if (!(kernel_sum > 0.0) || !std::isfinite(kernel_sum)) {
        return fail(error, "UltraFix blur kernel is invalid.");
    }
    for (double& value : kernel) value /= kernel_sum;

    std::vector<float> horizontal(source.size(), 0.0f);
    blurred->assign(source.size(), 0.0f);
    for (size_t channel = 0U; channel < shape.channels; ++channel) {
        for (size_t y = 0U; y < shape.height; ++y) {
            for (size_t x = 0U; x < shape.width; ++x) {
                double sum = 0.0;
                for (size_t kernel_index = 0U; kernel_index < kernel.size(); ++kernel_index) {
                    const long long offset = static_cast<long long>(kernel_index) -
                        static_cast<long long>(radius);
                    const size_t sample_x = static_cast<size_t>(std::clamp<long long>(
                        static_cast<long long>(x) + offset,
                        0LL,
                        static_cast<long long>(shape.width - 1U)));
                    sum += static_cast<double>(source[spatial_index(
                        shape, 0U, channel, y, sample_x)]) * kernel[kernel_index];
                }
                horizontal[spatial_index(shape, 0U, channel, y, x)] =
                    static_cast<float>(sum);
            }
        }
        for (size_t y = 0U; y < shape.height; ++y) {
            for (size_t x = 0U; x < shape.width; ++x) {
                double sum = 0.0;
                for (size_t kernel_index = 0U; kernel_index < kernel.size(); ++kernel_index) {
                    const long long offset = static_cast<long long>(kernel_index) -
                        static_cast<long long>(radius);
                    const size_t sample_y = static_cast<size_t>(std::clamp<long long>(
                        static_cast<long long>(y) + offset,
                        0LL,
                        static_cast<long long>(shape.height - 1U)));
                    sum += static_cast<double>(horizontal[spatial_index(
                        shape, 0U, channel, sample_y, x)]) * kernel[kernel_index];
                }
                (*blurred)[spatial_index(shape, 0U, channel, y, x)] =
                    static_cast<float>(sum);
            }
        }
    }
    if (!finite_values(*blurred)) {
        blurred->clear();
        return fail(error, "UltraFix blur produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

}  // namespace

bool scale_ultrafix_inversion_model_input(
        const std::vector<float>& source,
        const UltraFixNoiseLevel& source_level,
        const UltraFixNoiseLevel& evaluation_level,
        std::vector<float>* model_input,
        std::string* error) {
    if (model_input == nullptr || error == nullptr || source.empty() ||
        !finite_values(source) || !valid_ultrafix_noise_level(source_level) ||
        !valid_ultrafix_noise_level(evaluation_level)) {
        return fail(error, "UltraFix inversion model input requires finite source and noise levels.");
    }
    if (source_level.sigma > 0.0) {
        const double source_ratio = source_level.sigma / source_level.alpha;
        const double evaluation_ratio = evaluation_level.sigma / evaluation_level.alpha;
        if (std::fabs(source_ratio - evaluation_ratio) >
            std::max(1.0e-10, source_ratio * 1.0e-8)) {
            return fail(error, "UltraFix inversion may evaluate a different noise level only from the clean source.");
        }
    }
    const double scale = (evaluation_level.alpha / source_level.alpha) *
        evaluation_level.scheduler_input_scale;
    if (!std::isfinite(scale) || scale <= 0.0) {
        return fail(error, "UltraFix inversion model-input scale is invalid.");
    }
    model_input->resize(source.size());
    for (size_t index = 0U; index < source.size(); ++index) {
        (*model_input)[index] = static_cast<float>(source[index] * scale);
    }
    if (!finite_values(*model_input)) {
        model_input->clear();
        return fail(error, "UltraFix inversion model-input scaling produced non-finite values.");
    }
    error->clear();
    return true;
}

bool ultrafix_epsilon_inversion_step(
        const std::vector<float>& source,
        const std::vector<float>& epsilon,
        const UltraFixNoiseLevel& source_level,
        const UltraFixNoiseLevel& target_level,
        std::vector<float>* target,
        std::string* error) {
    if (target == nullptr || error == nullptr || source.empty() ||
        source.size() != epsilon.size() || !finite_values(source) ||
        !finite_values(epsilon) || !valid_ultrafix_noise_level(source_level) ||
        !valid_ultrafix_noise_level(target_level)) {
        return fail(error, "UltraFix epsilon inversion requires matching finite tensors and levels.");
    }
    const double source_ratio = source_level.sigma / source_level.alpha;
    const double target_ratio = target_level.sigma / target_level.alpha;
    if (!(target_ratio > source_ratio) || !std::isfinite(target_ratio)) {
        return fail(error, "UltraFix inversion target must be strictly noisier than its source.");
    }
    target->resize(source.size());
    for (size_t index = 0U; index < source.size(); ++index) {
        const double predicted_x0 =
            (static_cast<double>(source[index]) - source_level.sigma * epsilon[index]) /
            source_level.alpha;
        (*target)[index] = static_cast<float>(
            target_level.alpha * predicted_x0 + target_level.sigma * epsilon[index]);
    }
    if (!finite_values(*target)) {
        target->clear();
        return fail(error, "UltraFix epsilon inversion produced a non-finite target tensor.");
    }
    error->clear();
    return true;
}

bool resolve_ultrafix_quality_schedule(
        double training_timestep,
        bool has_next_step,
        UltraFixQualitySchedule* schedule,
        std::string* error) {
    if (schedule == nullptr || error == nullptr || !std::isfinite(training_timestep)) {
        return fail(error, "UltraFix quality scheduling requires a finite training timestep.");
    }
    UltraFixQualitySchedule resolved;
    if (has_next_step) {
        constexpr double kPi = 3.1415926535897932384626433832795;
        constexpr double kMaximumInjection = 0.08;
        constexpr double kStructureExponent = 0.3;
        const double timestep = std::clamp(training_timestep, 0.0, 1000.0);
        const double cosine = 0.5 *
            (1.0 + std::cos(kPi * (1000.0 - timestep) / 1000.0));
        const double injection = kMaximumInjection * (1.0 - cosine);
        const double structure = std::pow(std::max(0.0, cosine), kStructureExponent);
        resolved.noise_injection_fraction = injection;
        resolved.structure_guidance_weight = structure;
        resolved.evaluate_noise_injection = injection > 0.005;
        resolved.evaluate_structure_guidance = structure > 0.02;
    }
    *schedule = resolved;
    error->clear();
    return true;
}

bool ultrafix_equivalent_noise(
        const std::vector<float>& clean,
        const std::vector<float>& inverted,
        const UltraFixNoiseLevel& inverted_level,
        std::vector<float>* equivalent_noise,
        std::string* error) {
    if (equivalent_noise == nullptr || error == nullptr || clean.empty() ||
        clean.size() != inverted.size() || !finite_values(clean) ||
        !finite_values(inverted) || !valid_ultrafix_noise_level(inverted_level) ||
        !(inverted_level.sigma > 0.0)) {
        return fail(error, "UltraFix equivalent noise requires matching finite latents at a noisy level.");
    }
    equivalent_noise->resize(clean.size());
    for (size_t index = 0U; index < clean.size(); ++index) {
        (*equivalent_noise)[index] = static_cast<float>(
            (static_cast<double>(inverted[index]) -
                inverted_level.alpha * static_cast<double>(clean[index])) /
            inverted_level.sigma);
    }
    if (!finite_values(*equivalent_noise)) {
        equivalent_noise->clear();
        return fail(error, "UltraFix equivalent noise produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

bool ultrafix_add_noise(
        const std::vector<float>& clean,
        const std::vector<float>& noise,
        const UltraFixNoiseLevel& level,
        std::vector<float>* noisy,
        std::string* error) {
    if (noisy == nullptr || error == nullptr || clean.empty() ||
        clean.size() != noise.size() || !finite_values(clean) ||
        !finite_values(noise) || !valid_ultrafix_noise_level(level)) {
        return fail(error, "UltraFix trajectory reconstruction requires matching finite tensors and a valid noise level.");
    }
    noisy->resize(clean.size());
    for (size_t index = 0U; index < clean.size(); ++index) {
        (*noisy)[index] = static_cast<float>(
            level.alpha * static_cast<double>(clean[index]) +
            level.sigma * static_cast<double>(noise[index]));
    }
    if (!finite_values(*noisy)) {
        noisy->clear();
        return fail(error, "UltraFix trajectory reconstruction produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

uint64_t ultrafix_tensor_checksum(const std::vector<float>& values) {
    if (values.empty() || !finite_values(values)) return 0U;
    uint64_t hash = 1469598103934665603ULL;
    for (float value : values) {
        uint32_t bits = 0U;
        static_assert(sizeof(bits) == sizeof(value), "float checksum width changed");
        std::memcpy(&bits, &value, sizeof(bits));
        for (size_t shift = 0U; shift < 32U; shift += 8U) {
            hash ^= static_cast<uint8_t>((bits >> shift) & 0xffU);
            hash *= 1099511628211ULL;
        }
    }
    return hash == 0U ? 1U : hash;
}

uint64_t ultrafix_accumulate_checksum(
        uint64_t aggregate,
        uint32_t refinement_step,
        uint64_t value_checksum) {
    uint64_t hash = aggregate == 0U ? 1469598103934665603ULL : aggregate;
    constexpr uint64_t kPrime = 1099511628211ULL;
    constexpr std::array<uint8_t, 8> kDomain = {
        'U', 'F', 'Q', 'U', 'A', 'L', '1', 0,
    };
    for (uint8_t byte : kDomain) {
        hash ^= byte;
        hash *= kPrime;
    }
    for (size_t shift = 0U; shift < 32U; shift += 8U) {
        hash ^= static_cast<uint8_t>((refinement_step >> shift) & 0xffU);
        hash *= kPrime;
    }
    for (size_t shift = 0U; shift < 64U; shift += 8U) {
        hash ^= static_cast<uint8_t>((value_checksum >> shift) & 0xffU);
        hash *= kPrime;
    }
    return hash == 0U ? 1U : hash;
}

std::string ultrafix_noise_seed_descriptor(
        uint64_t request_seed,
        size_t denoise_step_count) {
    std::ostringstream out;
    out << "mca-ultrafix-quality-noise-v1|seed=" << request_seed
        << "|steps=" << denoise_step_count;
    return out.str();
}

size_t ultrafix_structure_blur_radius(size_t tile_latent_edge) {
    if (tile_latent_edge == 0U) {
        return 0U;
    }
    return std::max<size_t>(2U, tile_latent_edge / 16U);
}

size_t ultrafix_structure_blur_radius(const SpatialTensorShape& shape) {
    if (shape.batch == 0U || shape.channels == 0U || shape.height == 0U ||
        shape.width == 0U || shape.element_count() == 0U) {
        return 0U;
    }
    return std::clamp<size_t>(std::min(shape.height, shape.width) / 16U, 1U, 8U);
}

bool ultrafix_inject_spherical_noise(
        const std::vector<float>& prediction,
        uint64_t request_seed,
        uint32_t refinement_step,
        double random_fraction,
        std::vector<float>* mixed_prediction,
        uint64_t* gaussian_checksum,
        uint64_t* mixed_checksum,
        std::string* error) {
    if (mixed_prediction == nullptr || gaussian_checksum == nullptr ||
        mixed_checksum == nullptr || error == nullptr || prediction.empty() ||
        !finite_values(prediction) || !std::isfinite(random_fraction) ||
        random_fraction < 0.0 || random_fraction > 0.5) {
        return fail(error, "UltraFix spherical injection requires a finite prediction and bounded fraction.");
    }
    std::vector<float> gaussian;
    if (!deterministic_gaussian_tensor(
            prediction.size(), request_seed, refinement_step, &gaussian, error)) {
        return false;
    }
    const double prediction_weight = 1.0 - random_fraction;
    double dot = 0.0;
    double prediction_norm_squared = 0.0;
    double gaussian_norm_squared = 0.0;
    for (size_t index = 0U; index < prediction.size(); ++index) {
        dot += static_cast<double>(prediction[index]) * gaussian[index];
        prediction_norm_squared += static_cast<double>(prediction[index]) * prediction[index];
        gaussian_norm_squared += static_cast<double>(gaussian[index]) * gaussian[index];
    }
    const double denominator =
        std::sqrt(prediction_norm_squared) * std::sqrt(gaussian_norm_squared);
    if (!(denominator > 0.0) || !std::isfinite(denominator)) {
        return fail(error, "UltraFix spherical injection requires non-zero tensor norms.");
    }
    const double cosine = std::clamp(dot / denominator, -0.999999, 0.999999);
    const double omega = std::acos(cosine);
    const double sine = std::sin(omega);
    if (!(sine > 1.0e-12) || !std::isfinite(sine)) {
        return fail(error, "UltraFix spherical injection angle is numerically unstable.");
    }
    const double prediction_scale = std::sin(prediction_weight * omega) / sine;
    const double gaussian_scale = std::sin(random_fraction * omega) / sine;
    mixed_prediction->resize(prediction.size());
    for (size_t index = 0U; index < prediction.size(); ++index) {
        (*mixed_prediction)[index] = static_cast<float>(
            prediction_scale * prediction[index] + gaussian_scale * gaussian[index]);
    }
    if (!finite_values(*mixed_prediction)) {
        mixed_prediction->clear();
        return fail(error, "UltraFix spherical injection produced a non-finite prediction.");
    }
    *gaussian_checksum = ultrafix_tensor_checksum(gaussian);
    *mixed_checksum = ultrafix_tensor_checksum(*mixed_prediction);
    if (*gaussian_checksum == 0U || *mixed_checksum == 0U) {
        mixed_prediction->clear();
        return fail(error, "UltraFix spherical injection did not produce checksum evidence.");
    }
    error->clear();
    return true;
}

bool ultrafix_apply_structure_guidance(
        const std::vector<float>& current,
        const std::vector<float>& trajectory_reference,
        const SpatialTensorShape& shape,
        size_t blur_radius,
        double weight,
        std::vector<float>* guided,
        uint64_t* guided_checksum,
        std::string* error) {
    if (guided == nullptr || guided_checksum == nullptr || error == nullptr ||
        current.empty() || current.size() != trajectory_reference.size() ||
        current.size() != shape.element_count() || !finite_values(current) ||
        !finite_values(trajectory_reference) || !std::isfinite(weight) ||
        weight <= 0.0 || weight > 1.0) {
        return fail(error, "UltraFix structure guidance requires matching finite latents and a bounded weight.");
    }
    std::vector<float> current_low_frequency;
    std::vector<float> reference_low_frequency;
    if (!gaussian_blur_nchw(
            current, shape, blur_radius, &current_low_frequency, error) ||
        !gaussian_blur_nchw(
            trajectory_reference, shape, blur_radius, &reference_low_frequency, error)) {
        return false;
    }
    guided->resize(current.size());
    for (size_t index = 0U; index < current.size(); ++index) {
        (*guided)[index] = static_cast<float>(
            static_cast<double>(current[index]) + weight *
                (static_cast<double>(reference_low_frequency[index]) -
                    current_low_frequency[index]));
    }
    if (!finite_values(*guided)) {
        guided->clear();
        return fail(error, "UltraFix structure guidance produced a non-finite latent.");
    }
    *guided_checksum = ultrafix_tensor_checksum(*guided);
    if (*guided_checksum == 0U) {
        guided->clear();
        return fail(error, "UltraFix structure guidance did not produce checksum evidence.");
    }
    error->clear();
    return true;
}

bool resolve_ultrafix_execution_counts(
        size_t tile_count,
        size_t inversion_steps,
        size_t denoise_steps,
        bool use_cfg,
        UltraFixExecutionCounts* counts,
        std::string* error) {
    if (counts == nullptr || error == nullptr || tile_count == 0U ||
        inversion_steps == 0U || denoise_steps == 0U) {
        return fail(error, "UltraFix execution counts require non-zero tiles and steps.");
    }
    const auto multiply = [](size_t left, size_t right, size_t* output) {
        return output != nullptr && left <= std::numeric_limits<size_t>::max() / right
            ? ((*output = left * right), true)
            : false;
    };
    UltraFixExecutionCounts resolved;
    resolved.vae_encoder_graph_executions = tile_count;
    resolved.vae_decoder_graph_executions = tile_count;
    if (!multiply(tile_count, inversion_steps,
                  &resolved.inversion_positive_unet_graph_executions) ||
        !multiply(tile_count, denoise_steps,
                  &resolved.refinement_positive_unet_graph_executions)) {
        return fail(error, "UltraFix graph execution counts overflow the host size contract.");
    }
    resolved.refinement_negative_unet_graph_executions = use_cfg
        ? resolved.refinement_positive_unet_graph_executions
        : 0U;
    if (resolved.inversion_positive_unet_graph_executions >
            std::numeric_limits<size_t>::max() -
                resolved.refinement_positive_unet_graph_executions ||
        resolved.inversion_positive_unet_graph_executions +
                resolved.refinement_positive_unet_graph_executions >
            std::numeric_limits<size_t>::max() -
                resolved.refinement_negative_unet_graph_executions) {
        return fail(error, "UltraFix total UNet graph execution count overflowed.");
    }
    resolved.total_unet_graph_executions =
        resolved.inversion_positive_unet_graph_executions +
        resolved.refinement_positive_unet_graph_executions +
        resolved.refinement_negative_unet_graph_executions;
    *counts = resolved;
    error->clear();
    return true;
}

}  // namespace mca::image
