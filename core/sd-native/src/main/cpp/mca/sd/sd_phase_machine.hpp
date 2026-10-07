#ifndef MCA_SD_PHASE_MACHINE_HPP
#define MCA_SD_PHASE_MACHINE_HPP

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "mca/contract/callback_event.hpp"
#include "mca/contract/error_envelope.hpp"
#include "sd_contracts.hpp"
#include "sd_output_transaction.hpp"
#include "sd_request.hpp"
#include "sd_runtime_owner.hpp"

namespace mca {
namespace sd {

/**
 * Execution phases for the Stable Diffusion generation pipeline.
 */
enum class SdPhase : uint8_t {
    Validate = 0,
    TokenizeConditioning = 1,
    LoadGraph = 2,
    PrepareLatents = 3,
    DenoiseLoop = 4,
    DecodeLatents = 5,
    PostProcess = 6,
    CommitOutputs = 7,
    Completed = 8,
    Failed = 9,
    Cancelled = 10
};

[[nodiscard]] const char* sd_phase_to_string(SdPhase phase) noexcept;

/**
 * Backend interface abstracting compute steps (CLIP, Latents, UNet/DiT, VAE)
 * allows production runtime integration and clean deterministic testing.
 */
class ISdEngineBackend {
public:
    virtual ~ISdEngineBackend() = default;

    virtual bool tokenize_and_condition(
        const ImageInputContract& contract,
        std::string& error_out
    ) = 0;

    virtual bool prepare_latents(
        const ImageInputContract& contract,
        std::vector<float>& latents_out,
        std::string& error_out
    ) = 0;

    virtual bool execute_denoise_step(
        int32_t step,
        int32_t total_steps,
        std::vector<float>& latents,
        std::string& error_out
    ) = 0;

    virtual bool generate_preview_frame(
        int32_t step,
        const std::vector<float>& latents,
        GeneratedImageResult& preview_out,
        std::string& error_out
    ) = 0;

    virtual bool decode_latents_to_pixels(
        const ImageInputContract& contract,
        const std::vector<float>& latents,
        GeneratedImageResult& final_image_out,
        std::string& error_out
    ) = 0;
};

using SdEventCallback = std::function<void(const mca::contract::CallbackEvent&)>;

/**
 * Coordinates and orchestrates the multi-phase execution of txt2img, img2img, and inpaint.
 * Strictly guarantees cancellation responsiveness, error phase attribution,
 * and atomic output transactions.
 */
class SdPhaseMachine {
public:
    explicit SdPhaseMachine(
        SdRuntimeOwner& runtime_owner,
        std::shared_ptr<ISdEngineBackend> backend
    );
    ~SdPhaseMachine() = default;

    /**
     * Executes the complete generation pipeline for the given request.
     * Guarantees terminal state transition on SdRequest exactly once.
     */
    bool execute(
        SdRequest& request,
        SdEventCallback event_callback = nullptr
    );

    [[nodiscard]] SdPhase get_current_phase() const noexcept { return current_phase_; }

private:
    void transition_phase(SdPhase new_phase, SdRequest& request, const SdEventCallback& callback);
    void emit_event(const mca::contract::CallbackEvent& event, const SdEventCallback& callback);

    bool run_validation_phase(SdRequest& request, const SdEventCallback& callback);
    bool run_tokenize_phase(SdRequest& request, const SdEventCallback& callback);
    bool run_load_graph_phase(SdRequest& request, const SdEventCallback& callback);
    bool run_prepare_latents_phase(
        SdRequest& request,
        std::vector<float>& latents,
        const SdEventCallback& callback
    );
    bool run_denoise_loop_phase(
        SdRequest& request,
        std::vector<float>& latents,
        const SdEventCallback& callback
    );
    bool run_decode_phase(
        SdRequest& request,
        const std::vector<float>& latents,
        GeneratedImageResult& image_result,
        const SdEventCallback& callback
    );
    bool run_commit_phase(
        SdRequest& request,
        const GeneratedImageResult& image_result,
        const SdEventCallback& callback
    );

    SdRuntimeOwner& runtime_owner_;
    std::shared_ptr<ISdEngineBackend> backend_;
    SdPhase current_phase_{SdPhase::Validate};
};

} // namespace sd
} // namespace mca

#endif // MCA_SD_PHASE_MACHINE_HPP
