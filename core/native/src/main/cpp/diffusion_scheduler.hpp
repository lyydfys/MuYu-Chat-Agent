#ifndef MCA_DIFFUSION_SCHEDULER_HPP
#define MCA_DIFFUSION_SCHEDULER_HPP

#include "diffusion_scheduler_config.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mca::diffusion {

using SchedulerTensor = std::vector<float>;

struct SchedulerStepOptions {
    float eta = 0.0f;
    bool use_clipped_model_output = false;
    const SchedulerTensor* variance_noise = nullptr;
};

struct SchedulerStepResult {
    SchedulerTensor previous_sample;
    SchedulerTensor predicted_original_sample;
    double timestep = 0.0;
    double previous_timestep = 0.0;
    bool used_saved_sample = false;
};

struct Img2ImgTailSchedule {
    size_t begin_index = 0;
    size_t effective_step_count = 0;
    float strength = 1.0f;
};

/**
 * Resolves the Local Dream-compatible img2img tail using explicit float32
 * arithmetic and truncation of the retained-image fraction.
 */
bool resolve_img2img_tail_schedule(
        int inference_steps,
        double requested_strength,
        Img2ImgTailSchedule* schedule,
        std::string* error);

class DiffusionScheduler final {
public:
    explicit DiffusionScheduler(DiffusionSchedulerConfig config);

    bool set_timesteps(int num_inference_steps, std::string* error);
    /**
     * Starts an img2img denoise pass at an already-built timetable index.
     * PNDM is intentionally excluded because its repeated PRK/PLMS warm-up
     * state cannot be reconstructed by skipping entries.
     */
    bool set_begin_index(size_t begin_index, std::string* error);
    void reset_state();

    const DiffusionSchedulerConfig& config() const { return config_; }
    const std::vector<double>& timesteps() const { return timesteps_; }
    const std::vector<double>& sigmas() const { return sigmas_; }
    const std::vector<double>& alphas_cumprod() const { return alphas_cumprod_; }
    int num_inference_steps() const { return num_inference_steps_; }
    double init_noise_sigma() const { return init_noise_sigma_; }
    size_t expected_unet_execution_count() const { return timesteps_.size() - begin_index_; }
    size_t completed_step_count() const { return counter_ - begin_index_; }

    bool scale_model_input(
            const SchedulerTensor& sample,
            size_t schedule_index,
            SchedulerTensor* scaled_sample,
            std::string* error);

    /** Adds deterministic caller-supplied noise at one exact timetable entry. */
    bool add_noise(
            const SchedulerTensor& original_sample,
            const SchedulerTensor& noise,
            size_t schedule_index,
            SchedulerTensor* noisy_sample,
            std::string* error) const;

    bool step(
            const SchedulerTensor& model_output,
            size_t schedule_index,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error,
            const SchedulerStepOptions& options = {});

private:
    bool build_training_schedule(std::string* error);
    bool build_euler_schedule(int num_inference_steps, std::string* error);
    bool build_discrete_schedule(int num_inference_steps, std::string* error);
    bool build_pndm_schedule(int num_inference_steps, std::string* error);
    bool build_dpmpp_2m_schedule(int num_inference_steps, std::string* error);

    bool step_euler(
            const SchedulerTensor& model_output,
            size_t schedule_index,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error);
    bool step_ddim(
            const SchedulerTensor& model_output,
            size_t schedule_index,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error,
            const SchedulerStepOptions& options);
    bool step_pndm(
            const SchedulerTensor& model_output,
            size_t schedule_index,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error);
    bool step_dpmpp_2m(
            const SchedulerTensor& model_output,
            size_t schedule_index,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error);
    bool step_pndm_prk(
            const SchedulerTensor& model_output,
            int64_t timestep,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error);
    bool step_pndm_plms(
            const SchedulerTensor& model_output,
            int64_t timestep,
            const SchedulerTensor& sample,
            SchedulerStepResult* result,
            std::string* error);
    bool pndm_previous_sample(
            const SchedulerTensor& sample,
            int64_t timestep,
            int64_t previous_timestep,
            const SchedulerTensor& model_output,
            SchedulerTensor* previous_sample,
            std::string* error) const;

    DiffusionSchedulerConfig config_;
    std::vector<double> betas_;
    std::vector<double> alphas_cumprod_;
    std::vector<double> timesteps_;
    std::vector<double> sigmas_;
    std::vector<int64_t> base_timesteps_;
    std::vector<int64_t> prk_timesteps_;
    std::vector<int64_t> plms_timesteps_;

    int num_inference_steps_ = 0;
    double final_alpha_cumprod_ = 1.0;
    double init_noise_sigma_ = 1.0;
    size_t begin_index_ = 0;
    size_t counter_ = 0;
    size_t last_scaled_step_ = static_cast<size_t>(-1);

    std::vector<SchedulerTensor> model_output_history_;
    SchedulerTensor accumulated_model_output_;
    SchedulerTensor saved_sample_;
    bool has_saved_sample_ = false;
};

}  // namespace mca::diffusion

#endif  // MCA_DIFFUSION_SCHEDULER_HPP
