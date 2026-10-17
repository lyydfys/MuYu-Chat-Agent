#ifndef MCA_QNN_MODEL_EXECUTION_PIPELINE_HPP
#define MCA_QNN_MODEL_EXECUTION_PIPELINE_HPP

#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include "mca/qnn/qnn_ultrafix_handler.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "qnn_controlnet_execution.hpp"
#include "qnn_inpaint_contract.hpp"
#include "image_execution_math.hpp"
#include "mca/qnn/qnn_conditioning_pipeline.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

// qnn_write_timestep_tensor is defined file-local in qnn_model_execution_pipeline.cpp
// (anonymous namespace) because every call site lives in that translation unit. Declaring it
// here as well would make each in-TU call ambiguous with the file-local definition. The public
// declaration of the quantizer implementation is in mca/qnn/qnn_tensor_quantizer.hpp.

struct QnnControlNetLayout {
    int latent_index = -1;
    int timestep_index = -1;
    int text_index = -1;
    int image_index = -1;
    mca::image::SpatialTensorShape image_shape;
    std::vector<mca::qnn::controlnet::ResidualBinding> residual_bindings;
};

bool qnn_resolve_controlnet_layout(
        const QnnExecutableGraph& controlnet,
        const QnnExecutableGraph& unet,
        int unet_sample_index,
        int unet_timestep_index,
        int unet_text_index,
        int expected_width,
        int expected_height,
        QnnControlNetLayout* layout,
        std::string* error);

bool qnn_control_image_tensor_for_layout(
        const mca::qnn::controlnet::PreparedControlImage& image,
        const mca::image::SpatialTensorShape& shape,
        std::vector<float>* tensor,
        std::string* error);

bool qnn_run_controlnet_once(
        QnnExecutableGraph& controlnet,
        const QnnControlNetLayout& layout,
        QnnExecutableGraph& unet,
        const std::vector<float>& latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        const std::vector<float>& control_image_tensor,
        double control_strength,
        size_t* residual_write_count,
        uint64_t* scaled_residual_checksum,
        std::string* input_buffer_sha256,
        long long* execute_ms,
        std::string* error);

bool qnn_run_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const std::vector<float>& sample,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error);

bool qnn_run_inpaint_unet_once(
        QnnExecutableGraph& unet,
        const mca::qnn::inpaint::Contract& inpaint_contract,
        int timestep_index,
        int text_index,
        const std::vector<float>& latent,
        const std::vector<float>& mask,
        const std::vector<float>& masked_latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error);

bool qnn_run_text_encoder_once(
        QnnExecutableGraph& text_encoder,
        int token_index,
        int embedding_index,
        const int32_t* tokens,
        size_t token_count,
        std::vector<float>* embeddings,
        long long* execute_ms,
        std::string* error);

// Aligned with the definition in qnn_model_execution_pipeline.cpp (and with the call sites in
// qnn_semantic_pipeline.cpp), which are the bridge's original members.  Only this declaration had been
// rewritten to a different shape that neither the definition nor any caller used.
struct QnnVaeDecodeResult {
    mca::image::VaeDecodePlan plan;
    std::vector<float> pixels_nchw;
    size_t execution_count = 0U;
    long long execute_ms_total = 0;
};

bool qnn_prepare_sdxl_conditioning(
        const QnnExecutableGraph& unet,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const std::vector<float>& payload,
        bool use_cfg,
        QnnSdxlConditioningBuffers* conditioning,
        std::string* error);

bool qnn_run_sdxl_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const std::vector<float>& latent,
        double timestep,
        const float* hidden,
        size_t hidden_elements,
        const float* time_ids,
        size_t time_id_elements,
        const float* pooled,
        size_t pooled_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error);

bool qnn_decode_vae_latents(
        QnnExecutableGraph& vae,
        const std::vector<uint32_t>& source_latent_dimensions,
        const std::vector<float>& source_latents,
        const QnnSemanticExecutionContract& execution_contract,
        QnnVaeDecodeResult* result,
        std::string* error);

// Aligned with the definition in qnn_model_execution_pipeline.cpp (bridge's original members).
struct QnnUltraFixVaeDecodeResult {
    std::vector<float> pixels_nchw;
    size_t execution_count = 0U;
    long long execute_ms_total = 0;
};

bool qnn_decode_ultrafix_vae_latents(
        QnnExecutableGraph& vae,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& source_latents,
        const QnnSemanticExecutionContract& execution_contract,
        QnnUltraFixVaeDecodeResult* result,
        std::string* error);

bool qnn_ultrafix_noise_level(
        const mca::diffusion::DiffusionScheduler& scheduler,
        size_t schedule_index,
        mca::image::UltraFixNoiseLevel* level,
        std::string* error);

bool qnn_run_ultrafix_tiled_unet_branch(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& full_sample,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* full_output,
        long long* execute_ms_total,
        size_t* graph_execution_count,
        std::string* error);

struct QnnSharedImg2ImgEncoderEvidence {
    std::vector<float> latents;
    std::vector<float> masked_latents;
    std::vector<float> source_pixels_nchw;
    std::string context_sha256;
    std::string graph_name;
    std::string input_name;
    std::string mean_name;
    std::string std_name;
    std::string input_dtype;
    std::string mean_dtype;
    std::string std_dtype;
    std::string input_buffer_sha256;
    std::string mean_buffer_sha256;
    std::string std_buffer_sha256;
    std::string latent_sha256;
    std::string masked_input_tensor_sha256;
    std::string masked_input_buffer_sha256;
    std::string masked_mean_buffer_sha256;
    std::string masked_std_buffer_sha256;
    std::string masked_latent_sha256;
    long long context_load_ms = 0;
    long long execute_ms = 0;
    long long masked_execute_ms = 0;
    size_t execution_count = 0U;
    size_t masked_execution_count = 0U;
    bool context_released_before_shared_session = false;
    bool ultra_fix = false;
    mca::image::UltraFixTilePlan ultra_fix_plan;
    std::vector<uint32_t> encoder_input_dimensions;
    std::vector<uint32_t> encoder_output_dimensions;
};

bool qnn_run_shared_img2img_encoder(
        const RuntimeProbe& runtime,
        const std::string& bundle_root,
        const std::string& params_json,
        const QnnSemanticExecutionContract& execution_contract,
        const QnnUltraFixRequest* ultra_fix,
        const QnnImageGenerationScope* generation,
        QnnSharedImg2ImgEncoderEvidence* evidence,
        std::string* failure_code,
        std::string* error);

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

#endif  // MCA_QNN_MODEL_EXECUTION_PIPELINE_HPP
