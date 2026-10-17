#ifndef MCA_QNN_SEMANTIC_PIPELINE_HPP
#define MCA_QNN_SEMANTIC_PIPELINE_HPP

#include <cstddef>
#include <string>

#include "mca/qnn/qnn_conditioning_pipeline.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
#include <unistd.h>
#include <vector>

#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "image_execution_math.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "qnn_controlnet_execution.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_image_stage_trace.hpp"
#include "qnn_inpaint_contract.hpp"
#include "qnn_soc_htp_mapping.hpp"
#include "qnn_shared_preview.hpp"

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_shared_preview_publisher.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_bundle_manifest.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_device_support.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_ultrafix_handler.hpp"
#include "mca/qnn/qnn_image_writer.hpp"
#include "mca/qnn/qnn_inpaint_blend.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_graph_executor_helper.hpp"

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace qnn {

std::string qnn_semantic_generate_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& output_path);


// Migrated from qnn_native_bridge.cpp (HEAD:5245); it was never carried into a module.
bool resolve_qnn_conditioning_evidence(
        const std::string& bundle_root,
        const std::string& consumed_artifact_sha256,
        const std::string& conditioning_format,
        size_t observed_token_ids,
        const std::string& params_json,
        const QnnSemanticExecutionContract& contract,
        const QnnTextEncoderAssetEvidence* text_encoder_asset_evidence,
        QnnConditioningEvidence* evidence,
        std::string* error);
#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

// One native QNN semantic request.  The former single ~3.2k-line function body now
// lives in the phase translation units (qnn_semantic_{request,conditioning,sdxl,
// stage_runner,output,evidence}.cpp); this struct carries the request-scoped state
// they share, so the phase methods keep the original expression text unchanged.
struct QnnSemanticRun {
    QnnSemanticRun(
            const std::string& bundle_root,
            const std::string& runtime_dirs_json,
            const std::string& params_json,
            const std::string& embeddings_path,
            const std::string& output_path);

    std::string run();
    std::string request_phase(mca::diffusion::DiffusionScheduler& scheduler);
    std::string conditioning_phase(QnnImageGenerationScope& generation);
    std::string sdxl_phase(
            mca::diffusion::DiffusionScheduler& scheduler,
            QnnImageGenerationScope& generation,
            QnnSharedPreviewPublisher& preview_publisher);
    std::string stage_runner_phase(
            mca::diffusion::DiffusionScheduler& scheduler,
            QnnImageGenerationScope& generation,
            QnnSharedPreviewPublisher& preview_publisher);
    std::string output_phase(
            mca::diffusion::DiffusionScheduler& scheduler,
            QnnImageGenerationScope& generation);
    std::string evidence_phase(mca::diffusion::DiffusionScheduler& scheduler, QnnSharedPreviewPublisher& preview_publisher);

    static bool valid_inpaint_sha256(const std::string& value);

    std::string bundle_root;
    std::string runtime_dirs_json;
    std::string params_json;
    std::string embeddings_path;
    std::string output_path;
    std::unique_lock<std::timed_mutex> context_execution_lock;
    QnnSharedImg2ImgEncoderEvidence img2img_encoder;
    QnnCoherentRuntimeSession runtime_session;
    QnnExecutableGraph unet;
    mca::qnn::inpaint::Contract inpaint_contract;
    QnnExecutableGraph controlnet;
    QnnExecutableGraph vae;
    std::chrono::steady_clock::time_point started;
    RuntimeProbe runtime;
    BundleProbe bundle;
    std::string conditioning_format;
    bool qnn_weighted_token_conditioning;
    bool qnn_token_conditioning;
    std::string error;
    QnnSemanticExecutionContract execution_contract;
    QnnUltraFixRequest ultra_fix_request;
    QnnPreviewRequest preview_request;
    std::string task_mode;
    bool request_controlnet;
    bool request_img2img;
    bool request_inpaint;
    std::string requested_inpaint_topology;
    std::string worker_strategy;
    std::string input_image_path;
    std::string mask_image_path;
    std::string mask_image_sha256;
    std::string control_image_path;
    std::string control_image_sha256;
    double control_strength;
    mca::qnn::controlnet::ControlImagePreprocessMode control_image_preprocess;
    bool request_sdxl;
    mca::qnn::preview::Contract preview_contract;
    size_t full_timetable_count;
    bool request_encoder_conditioned;
    double img2img_strength;
    size_t img2img_begin_index;
    size_t effective_timetable_count;
    std::string graph_name;
    std::string text_encoder_graph_name;
    std::string controlnet_graph_name;
    std::string unet_path;
    std::string vae_path;
    std::string text_encoder_path;
    std::string controlnet_path;
    QnnTextEncoderAssetEvidence text_encoder_asset_evidence;
    std::string tokenizer_path;
    std::vector<float> embeddings;
    std::vector<int32_t> token_ids;
    std::vector<float> token_weights;
    QnnConditioningEvidence conditioning_evidence;
    long long text_encoder_context_load_ms;
    long long text_encoder_execute_ms_total;
    size_t text_encoder_execute_count;
    uint64_t text_encoder_embedding_width;
    uint64_t text_encoder_input_elements;
    std::string loaded_text_encoder_graph;
    std::string text_encoder_input_tensor;
    std::string text_encoder_input_data_type;
    std::string text_encoder_inputs_debug;
    std::string text_encoder_outputs_debug;
    std::string conditioning_graph_sha256;
    std::string prompt_weighting_execution_mode;
    bool conditioning_artifact_consumed;
    mca::image::UltraFixTilePlan ultra_fix_plan;
    uint64_t text_elements;
    QnnControlNetLayout controlnet_layout;
    mca::qnn::controlnet::PreparedControlImage prepared_control_image;
    std::vector<float> control_image_tensor;
    std::vector<float> inpaint_mask;
    std::vector<float> inpaint_full_mask;
    std::vector<float> inpaint_masked_latent;
    int steps;
    std::vector<double> timesteps;
    double effective_vae_host_scale;
    std::vector<float> latents;
    bool img2img_add_noise_applied;
    double img2img_add_noise_timestep;
    uint64_t img2img_noise_checksum;
    std::string inpaint_source_noise_sha256;
    size_t inpaint_source_noise_use_count;
    std::vector<float> noise_uncond;
    std::vector<float> noise_cond;
    long long unet_execute_ms_total;
    size_t unet_execution_count;
    long long controlnet_execute_ms_total;
    size_t controlnet_execution_count;
    size_t controlnet_residual_write_count;
    uint64_t controlnet_scaled_residual_checksum;
    std::string controlnet_input_buffer_sha256;
    size_t inpaint_mask_bind_count;
    size_t inpaint_preserve_step_count;
    uint64_t inpaint_preserved_latent_checksum;
    size_t ultra_fix_inversion_step_count;
    size_t ultra_fix_inversion_graph_execution_count;
    size_t ultra_fix_refinement_step_count;
    size_t ultra_fix_refinement_positive_execution_count;
    size_t ultra_fix_refinement_negative_execution_count;
    size_t ultra_fix_physical_unet_execution_count;
    size_t ultra_fix_quality_step_evaluation_count;
    size_t ultra_fix_noise_injection_step_count;
    std::string ultra_fix_noise_injection_seed_fingerprint;
    uint64_t ultra_fix_noise_injection_checksum;
    size_t ultra_fix_structure_guidance_step_count;
    uint64_t ultra_fix_structure_guidance_checksum;
    uint64_t ultra_fix_trajectory_noise_checksum;
    long long vae_execute_ms;
    size_t final_vae_graph_execution_count;
    std::vector<float> pixels;
    int inpaint_pixel_blend_levels;
    uint64_t inpaint_pixel_blend_checksum;
    bool inpaint_pixel_blend_applied;
    int width;
    int height;
    mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
    long long elapsed;
    long long output_bytes;
    std::string output_sha256;
};

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
#endif  // MCA_QNN_SEMANTIC_PIPELINE_HPP
