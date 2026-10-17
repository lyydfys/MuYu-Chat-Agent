// mca/qnn/qnn_sdxl_unet_phase_internal.hpp
// T2B18 / R7-C7: phase-handoff context + relocation prototypes for the split SDXL UNet
// phase implementation.  Declarations only; hidden visibility keeps every new/temporary
// symbol out of the shared object's dynamic table (the explicit template instantiation
// and the three body functions).
#ifndef MCA_QNN_SDXL_UNET_PHASE_INTERNAL_HPP
#define MCA_QNN_SDXL_UNET_PHASE_INTERNAL_HPP

#include <chrono>
#include <cstdint>
#include <random>
#include <string>
#include <vector>

#include "qnn_sdxl_unet_phase.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_conditioning_pipeline.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_graph_executor_helper.hpp"
#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "qnn_controlnet_execution.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK
#include "mca/qnn/qnn_semantic_pipeline.hpp"

#pragma GCC visibility push(hidden)
using ::mca::qnn::SdxlInpaintPixelArtifacts;
using ::mca::qnn::write_sdxl_latent_atomic;
using ::mca::qnn::fsync_sdxl_artifact;
using ::mca::qnn::valid_sdxl_lower_sha256;
using ::mca::qnn::is_safe_ascii_diffusion_prompt_pair;
using ::mca::qnn::validate_sdxl_phase_execution_contract;
using ::mca::qnn::load_sdxl_inpaint_pixel_artifacts;
using ::mca::qnn::validate_sdxl_no_preview_transport;
using ::mca::qnn::sdxl_parent_directory;
using ::mca::qnn::sdxl_disabled_preview_native_json_fields;
using ::mca::qnn::run_sdxl_ultrafix_tiled_unet_branch;
using ::mca::qnn::RuntimeProbe;
using ::mca::qnn::inspect_sdxl_exact_runtime_profile;
using ::mca::qnn::quote;
using ::mca::qnn::string_field;
using ::mca::qnn::long_field;
using ::mca::qnn::double_field;
using ::mca::qnn::uint_array_field;
using ::mca::qnn::normalized_contract_enum;
using ::mca::qnn::join_path;
using ::mca::qnn::exists_file;
using ::mca::qnn::file_size_or_zero;
using ::mca::qnn::read_float_binary_file;
using ::mca::qnn::tensor_index_by_name;
using ::mca::qnn::checksum_float_vector;
using ::mca::qnn::fixed_width_lower_hex_u64;
using ::mca::qnn::qnn_run_sdxl_unet_once;
using ::mca::qnn::qnn_prepare_sdxl_conditioning;
using ::mca::qnn::qnn_ultrafix_noise_level;
using ::mca::qnn::parse_qnn_semantic_execution_contract;
using ::mca::qnn::parse_qnn_ultrafix_request;
using ::mca::qnn::resolve_qnn_conditioning_evidence;
using ::mca::qnn::QnnSemanticExecutionContract;
using ::mca::qnn::QnnConditioningEvidence;
using ::mca::qnn::QnnUltraFixRequest;
using ::mca::qnn::QnnSdxlConditioningBuffers;
using ::mca::qnn::QnnNativeEffectiveEvidence;
using ::mca::qnn::bind_qnn_consumed_prompt_evidence;
using ::mca::qnn::qnn_native_effective_json;
using ::mca::qnn::qnn_prompt_to_encoder_receipt_json_fields;
using ::mca::qnn::qnn_semantic_failure_json;
using ::mca::qnn::qnn_execution_contract_mismatch_json;
using ::mca::qnn::qnn_double_array_json;
using ::mca::qnn::qnn_scheduler_wire_name;
using ::mca::qnn::qnn_prediction_wire_name;
using ::mca::qnn::qnn_vae_scaling_wire_name;
using ::mca::qnn::image_pixel_range_wire_name;
using ::mca::qnn::image_stage_mask_hex;
using ::mca::qnn::runtime_probe_json;
using ::mca::qnn::QnnImageGenerationScope;
using ::mca::qnn::g_qnn_context_execution_mutex;
using ::mca::qnn::kQnnImageSampling;
using ::mca::qnn::kQnnImageContextLock;
using ::mca::qnn::g_qnn_image_generation_sequence;
using ::mca::qnn::g_qnn_image_generation_stage_mask;
using ::mca::qnn::g_qnn_image_generation_detail_stage_mask;
using ::mca::qnn::g_qnn_image_generation_started_ms;
using ::mca::qnn::qnn_image_generation_cancelled_json;
namespace mca {
namespace qnn {

// Split-SDXL UNet phase hand-off.  Every field was a function-local of the original
// single-body template; the T2B18 split threads them between the three body functions.
struct SdxlUnetPhaseContext {
    // The scheduler is owned by the caller (T1) and stays alive across the phase calls.
    template <typename SchedulerT>
    explicit SdxlUnetPhaseContext(SchedulerT& scheduler_ref) : scheduler(scheduler_ref) {}

    SdxlUnetPhaseContext(const SdxlUnetPhaseContext&) = delete;
    SdxlUnetPhaseContext& operator=(const SdxlUnetPhaseContext&) = delete;

    std::string bundle_root;
    RuntimeProbe runtime;
    std::string error;
    std::chrono::steady_clock::time_point started;
    int steps = 0;
    std::string task_mode;
    size_t full_timetable_count = 0U;
    bool encoder_conditioned = false;
    bool inpaint = false;
    size_t begin_index = 0U;
    double strength = 1.0;
    const QnnImageGenerationScope* generation = nullptr;
    int sample_index = -1;
    int timestep_index = -1;
    int hidden_index = -1;
    int time_ids_index = -1;
    int pooled_index = -1;
    mca::qnn::inpaint::Contract inpaint_contract;
    mca::image::SpatialTensorShape latent_shape;
    QnnUltraFixRequest ultra_fix;
    mca::image::UltraFixTilePlan ultra_fix_plan;
    std::string ultra_fix_tile_plan_sha256;
    uint64_t latent_elements = 0U;
    std::reference_wrapper<mca::diffusion::DiffusionScheduler> scheduler;
    QnnSdxlConditioningBuffers conditioning;
    size_t hidden_count = 0U;
    size_t pooled_count = 0U;
    size_t time_ids_count = 0U;
    std::vector<float> inpaint_mask;
    std::string actual_mask_tensor_sha256;
    QnnConditioningEvidence conditioning_evidence;
    size_t effective_denoise_steps = 0U;
    QnnSemanticExecutionContract execution_contract;
    std::string embeddings_path;
    std::string initial_latent_path;
    std::string latent_path;
    std::string params_json;
    QnnExecutableGraph* unet = nullptr;
    std::mt19937 rng;
    std::normal_distribution<float> normal;
    float initial_noise_scale = 0.0f;
    std::vector<float> latents;
    std::string encoder_latent_sha256;
    std::vector<float> source_latents;
    std::vector<float> diffusion_noise;
    std::string inpaint_source_noise_sha256;
    size_t inpaint_source_noise_use_count = 0U;
    size_t inpaint_preserve_step_count = 0U;
    uint64_t inpaint_preserved_latent_checksum = 0U;
    uint64_t img2img_noise_checksum = 0U;
    std::vector<float> ultra_fix_clean_latents;
    std::vector<float> ultra_fix_equivalent_noise;
    std::vector<float> noise_uncond;
    std::vector<float> noise_cond;
    long long unet_execute_ms_total = 0;
    size_t unet_execution_count = 0U;
    size_t ultra_fix_inversion_step_count = 0U;
    size_t ultra_fix_inversion_graph_execution_count = 0U;
    size_t ultra_fix_refinement_step_count = 0U;
    size_t ultra_fix_refinement_positive_execution_count = 0U;
    size_t ultra_fix_refinement_negative_execution_count = 0U;
    size_t ultra_fix_quality_step_evaluation_count = 0U;
    size_t ultra_fix_noise_injection_step_count = 0U;
    std::string ultra_fix_noise_injection_seed_fingerprint;
    uint64_t ultra_fix_noise_injection_checksum = 0U;
    size_t ultra_fix_structure_guidance_step_count = 0U;
    uint64_t ultra_fix_structure_guidance_checksum = 0U;
    uint64_t ultra_fix_trajectory_noise_checksum = 0U;
};

std::string qnn_sdxl_unet_phase_body(SdxlUnetPhaseContext& ctx);
std::string qnn_sdxl_unet_tiling_body(SdxlUnetPhaseContext& ctx);
std::string qnn_sdxl_unet_execution_body(SdxlUnetPhaseContext& ctx);

}  // namespace qnn
}  // namespace mca

#pragma GCC visibility pop

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

#endif  // MCA_QNN_SDXL_UNET_PHASE_INTERNAL_HPP
