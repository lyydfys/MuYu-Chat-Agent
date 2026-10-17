#ifndef MCA_QNN_CONDITIONING_PIPELINE_HPP
#define MCA_QNN_CONDITIONING_PIPELINE_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include "mnn_qnn_prompt_handoff.hpp"

#include <sys/types.h>
#include <sys/stat.h>

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
typedef unsigned short mode_t;
#endif

namespace mca {
namespace qnn {

struct QnnPreviewRequest {
    bool requested = false;
    std::string mode;
    long long interval = 0;
};

bool parse_qnn_preview_request(
        const std::string& params_json,
        QnnPreviewRequest* request,
        std::string* error);

struct QnnTextEncoderAssetEvidence {
    bool required = false;
    bool multilingual_evidence_required = false;
    std::string relative_path;
    std::string sha256;
    long long size_bytes = 0;
    std::string graph_name;
    std::string language_proof_sha256;
    std::string prompt_to_encoder_closure_sha256;
    dev_t device = 0;
    ino_t inode = 0;
    std::string tokenizer_relative_path;
    std::string tokenizer_sha256;
    long long tokenizer_size_bytes = 0;
    dev_t tokenizer_device = 0;
    ino_t tokenizer_inode = 0;
    std::string tokenizer_canonical_path;
    bool tokenizer_asset_verified = false;
    std::string mnn_prompt_handoff;
    bool mnn_prompt_handoff_verified = false;
    mca::image::prompt_handoff::Record consumed_prompt_handoff;
};

struct QnnSdxlConditioningBuffers {
    std::vector<float> negative_hidden;
    std::vector<float> positive_hidden;
    const float* negative_pooled = nullptr;
    const float* positive_pooled = nullptr;
    const float* time_ids = nullptr;
};

struct QnnConditioningEvidence {
    std::string tokenizer_backend;
    std::string tokenizer_binding_stage;
    size_t token_count = 0;
    bool prompt_weighting_applied = false;
    size_t positive_weighted_token_count = 0;
    size_t negative_weighted_token_count = 0;
    std::string prompt_weight_fingerprint;
    std::string native_prompt_execution_sha256;
    std::string native_prompt_binding_stage;
    std::string conditioning_artifact_sha256;
    std::string embedding_disk_data_type;
    // The six members below were dropped when this struct was moved out of qnn_native_bridge.cpp,
    // while the call sites in qnn_semantic_pipeline.cpp (copied verbatim from the bridge) still set
    // and read them.  Restored exactly as the bridge declared them.
    std::string conditioning_execution_mode;
    std::string conditioning_backend;
    std::string conditioning_graph;
    std::string conditioning_graph_sha256;
    std::string conditioning_order;
    size_t conditioning_encoder_execution_count = 0;
};

struct QnnNativeEffectiveEvidence {
    size_t timetable_count = 0;
    size_t unet_execution_count = 0;
    std::string tokenizer_backend;
    size_t token_count = 0;
    std::string embedding_disk_data_type;
    int width = 0;
    int height = 0;
    std::string graph_name;
    bool prompt_weighting_applied = false;
    size_t positive_weighted_token_count = 0;
    size_t negative_weighted_token_count = 0;
    std::string prompt_weight_fingerprint;
    std::string native_prompt_execution_sha256;
    std::string native_prompt_binding_stage;
    std::string conditioning_artifact_sha256;
    std::string conditioning_execution_mode;
    std::string conditioning_backend;
    std::string conditioning_graph;
    std::string conditioning_graph_sha256;
    std::string conditioning_order;
    size_t conditioning_encoder_execution_count = 0;
    size_t text_encoder_execution_count = 0;
    std::string consumed_text_encoder_path;
    std::string consumed_text_encoder_sha256;
    long long consumed_text_encoder_size_bytes = 0;
    bool consumed_text_encoder_asset_verified = false;
    std::string language_proof_sha256;
    bool conditioning_artifact_consumed = false;
    std::string runtime_session_mode;
    std::string task_mode = "text_to_image";
    std::string input_image_path;
    std::string input_image_sha256;
    std::string input_image_tensor_path;
    std::string input_image_tensor_sha256;
    long long input_image_tensor_bytes = 0;
    std::string input_image_tensor_dtype;
    std::string input_image_tensor_layout;
    std::string input_image_tensor_range;
    std::string encoder_latent_sha256;
    std::string encoder_context_sha256;
    std::string encoder_graph_name;
    std::string encoder_input_name;
    std::string encoder_mean_output_name;
    std::string encoder_std_output_name;
    std::string encoder_input_dtype;
    std::string encoder_mean_dtype;
    std::string encoder_std_dtype;
    std::string encoder_input_buffer_sha256;
    std::string encoder_mean_buffer_sha256;
    std::string encoder_std_buffer_sha256;
    std::string encoder_runtime_mode;
    size_t encoder_context_load_count = 0;
    size_t encoder_execution_count = 0;
    size_t encoder_posterior_sample_count = 0;
    double encoder_latent_scaling_factor = 0.0;
    bool encoder_context_released_before_shared_session = false;
    std::string input_image_preprocess = "none";
    double strength = 1.0;
    size_t input_image_execution_count = 0;
    size_t input_image_source_width = 0;
    size_t input_image_source_height = 0;
    size_t input_image_oriented_width = 0;
    size_t input_image_oriented_height = 0;
    int input_image_exif_orientation = 0;
    size_t input_image_tensor_width = 0;
    size_t input_image_tensor_height = 0;
    size_t input_image_tensor_channels = 0;
    size_t encoder_graph_input_width = 0;
    size_t encoder_graph_input_height = 0;
    size_t encoder_graph_output_width = 0;
    size_t encoder_graph_output_height = 0;
    size_t img2img_begin_index = 0;
    size_t full_timetable_count = 0;
    size_t effective_denoise_steps = 0;
    bool img2img_add_noise_applied = false;
    size_t img2img_add_noise_begin_index = 0;
    double img2img_add_noise_timestep = 0.0;
    std::string img2img_noise_checksum = "0000000000000000";
    std::string control_image_path;
    std::string control_image_sha256;
    std::string control_image_preprocessed_sha256;
    std::string control_image_preprocess = "none";
    double control_strength = 0.0;
    size_t control_image_execution_count = 0;
    size_t controlnet_execution_count = 0;
    size_t controlnet_residual_tensor_count = 0;
    size_t controlnet_residual_write_count = 0;
    size_t controlnet_residual_unet_reuse_count = 0;
    bool controlnet_input_consumed = false;
    std::string controlnet_conditioning_branch = "none";
    std::string controlnet_graph_name;
    uint32_t control_image_source_width = 0;
    uint32_t control_image_source_height = 0;
    uint32_t control_image_source_channels = 0;
    uint32_t control_image_tensor_width = 0;
    uint32_t control_image_tensor_height = 0;
    uint32_t control_image_tensor_channels = 0;
    int control_image_exif_orientation = 0;
    size_t control_image_edge_pixel_count = 0;
    uint64_t control_image_tensor_checksum = 0;
    uint64_t controlnet_scaled_residual_checksum = 0;
    std::string controlnet_input_buffer_sha256;
    std::string inpaint_topology = "none";
    std::string mask_image_path;
    std::string mask_image_sha256;
    long long mask_image_source_bytes = 0;
    std::string mask_image_tensor_path;
    std::string mask_image_tensor_sha256;
    long long mask_image_tensor_bytes = 0;
    std::string mask_image_tensor_dtype;
    std::string mask_image_tensor_layout;
    std::string mask_image_tensor_range;
    std::string mask_image_tensor_preprocess;
    std::string mask_image_full_tensor_path;
    std::string mask_image_full_tensor_sha256;
    long long mask_image_full_tensor_bytes = 0;
    std::string mask_image_full_tensor_dtype;
    std::string mask_image_full_tensor_layout;
    std::string mask_image_full_tensor_range;
    std::string mask_image_full_tensor_preprocess;
    size_t mask_image_source_width = 0U;
    size_t mask_image_source_height = 0U;
    size_t mask_image_oriented_width = 0U;
    size_t mask_image_oriented_height = 0U;
    int mask_image_exif_orientation = 0;
    size_t mask_image_repaint_pixel_count = 0U;
    size_t mask_image_latent_repaint_pixel_count = 0U;
    std::string masked_input_image_tensor_path;
    std::string masked_input_image_tensor_sha256;
    long long masked_input_image_tensor_bytes = 0;
    std::string masked_input_image_tensor_dtype;
    std::string masked_input_image_tensor_layout;
    std::string masked_input_image_tensor_range;
    std::string masked_input_image_tensor_preprocess;
    std::string masked_input_buffer_sha256;
    std::string masked_input_mean_buffer_sha256;
    std::string masked_input_std_buffer_sha256;
    std::string masked_input_latent_sha256;
    size_t masked_input_encoder_execution_count = 0U;
    size_t masked_input_posterior_sample_count = 0U;
    long long masked_input_encoder_execute_ms = 0;
    size_t mask_image_execution_count = 0;
    size_t inpaint_mask_unet_bind_count = 0U;
    size_t inpaint_preserve_step_count = 0U;
    size_t inpaint_latent_blend_count = 0U;
    size_t inpaint_source_encoder_execution_count = 0U;
    std::string inpaint_preserved_latent_checksum = "0000000000000000";
    std::string inpaint_source_noise_sha256;
    size_t inpaint_source_noise_use_count = 0U;
    std::string inpaint_final_mode = "none";
    size_t inpaint_pixel_blend_levels = 0U;
    std::string inpaint_pixel_blend_checksum = "0000000000000000";
    bool inpaint_pixel_blend_applied = false;
    bool mask_image_consumed = false;
    bool inpaint_unmasked_preservation_applied = false;
    std::string ultra_fix_json;
    std::string ultra_fix_output_sha256;
    long long ultra_fix_output_bytes = 0;
    bool ultra_fix_output_atomic_commit = false;
    size_t ultra_fix_positive_diffusion_model_compute_count = 0U;
    size_t ultra_fix_negative_diffusion_model_compute_count = 0U;
    size_t ultra_fix_auxiliary_diffusion_model_compute_count = 0U;
    size_t ultra_fix_sampling_pass_count = 0U;
    size_t ultra_fix_total_unet_execution_count = 0U;
    std::string text_encoder_graph_name;
    std::string loaded_text_encoder_graph_name;
    std::string consumed_tokenizer_path;
    std::string consumed_tokenizer_sha256;
    long long consumed_tokenizer_size_bytes = 0;
    bool consumed_tokenizer_asset_verified = false;
    std::string tokenizer_binding_stage;
    std::string consumed_prompt_to_encoder_closure_sha256;
    std::string consumed_prompt_to_encoder_binding_stage;
    bool mnn_prompt_handoff_verified = false;
    std::string tokenizer_receipt_canonical_path;
    std::string tokenizer_receipt_sha256;
    long long tokenizer_receipt_size_bytes = 0;
    std::string tokenizer_receipt_binding_stage;
};

uint32_t qnn_u32_little_endian(const uint8_t* bytes);

bool derive_qnn_token_execution_weight_evidence(
        const std::vector<int32_t>& token_ids,
        const std::vector<float>& token_weights,
        bool use_cfg,
        QnnConditioningEvidence* evidence,
        std::string* error);

bool read_qnn_clip_token_weight_payload(
        const std::string& path,
        std::vector<int32_t>* token_ids,
        std::vector<float>* token_weights,
        bool use_cfg,
        QnnConditioningEvidence* evidence,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr);

std::string normalized_contract_enum(std::string value);

bool qnn_json_value_start(
        const std::string& json,
        const std::string& key,
        size_t* value_start,
        std::string* error);

bool qnn_json_token_ends_at(const std::string& json, size_t end);

bool qnn_required_string_field(
        const std::string& json,
        const std::string& key,
        std::string* value,
        std::string* error,
        bool allow_empty = false);

bool qnn_required_bool_field(
        const std::string& json,
        const std::string& key,
        bool* value,
        std::string* error);

bool qnn_required_integer_field(
        const std::string& json,
        const std::string& key,
        long long* value,
        std::string* error);

bool qnn_required_number_field(
        const std::string& json,
        const std::string& key,
        double* value,
        std::string* error);

bool parse_qnn_text_encoder_asset_evidence(
        const std::string& params_json,
        const std::string& text_encoder_binary,
        const std::string& requested_text_encoder_graph_name,
        bool prompt_requires_multilingual_evidence,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error);

bool qnn_prompt_contains_non_ascii(const std::string& value);
bool qnn_is_safe_ascii_diffusion_prompt(const std::string& value);
bool qnn_is_safe_ascii_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt);

bool qnn_text_encoder_asset_file_status(
        const std::string& path,
        dev_t* device,
        ino_t* inode,
        long long* size_bytes,
        std::string* error);

bool qnn_text_encoder_asset_status_matches(
        const QnnTextEncoderAssetEvidence& expected,
        dev_t device,
        ino_t inode,
        long long size_bytes);

bool capture_qnn_text_encoder_asset_evidence(
        const std::string& bundle_asset_path,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error);

bool revalidate_qnn_text_encoder_asset_evidence(
        const std::string& bundle_asset_path,
        const QnnTextEncoderAssetEvidence& expected,
        std::string* error);

bool qnn_tokenizer_asset_file_status(
        const std::string& path,
        dev_t* device,
        ino_t* inode,
        long long* size_bytes,
        std::string* error);

bool qnn_tokenizer_asset_status_matches(
        const QnnTextEncoderAssetEvidence& expected,
        dev_t device,
        ino_t inode,
        long long size_bytes);

bool qnn_canonical_bundle_tokenizer_path(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        std::string* canonical_path,
        std::string* error);

bool capture_qnn_tokenizer_asset_evidence(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error);

bool revalidate_qnn_tokenizer_asset_evidence(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        const QnnTextEncoderAssetEvidence& expected,
        std::string* error);

bool qnn_sdxl_conditioning_graph_sha256(
        const std::vector<std::string>& files,
        std::string* digest,
        std::string* error);

std::string qnn_prompt_to_encoder_closure_sha256(
        const QnnTextEncoderAssetEvidence& evidence);

bool consume_qnn_mnn_prompt_handoff(
        const QnnSemanticExecutionContract& contract,
        const QnnConditioningEvidence& conditioning,
        QnnTextEncoderAssetEvidence* asset_evidence,
        std::string* error);

// Migrated from qnn_native_bridge.cpp (HEAD:5788).  Its sibling below has a different signature and
// a different name; the two must not be conflated.
bool bind_qnn_consumed_prompt_evidence(
        const QnnConditioningEvidence& conditioning,
        bool conditioning_artifact_consumed,
        size_t unet_execution_count,
        QnnNativeEffectiveEvidence* native_evidence,
        std::string* error);

bool bind_qnn_consumed_prompt_to_encoder_evidence(
        const QnnTextEncoderAssetEvidence& asset_evidence,
        const std::string& loaded_text_encoder_graph_name,
        const QnnConditioningEvidence& conditioning,
        QnnNativeEffectiveEvidence* native_evidence,
        std::string* error);

std::string qnn_double_array_json(const std::vector<double>& values);
std::string qnn_consumed_prompt_to_encoder_assets_json(
        const QnnNativeEffectiveEvidence& evidence);
std::string qnn_prompt_to_encoder_receipt_json_fields(
        const QnnNativeEffectiveEvidence& evidence);

std::string qnn_native_effective_json(
        const QnnSemanticExecutionContract& contract,
        const QnnNativeEffectiveEvidence& evidence);

std::string qnn_semantic_failure_json(
        const std::string& execution_stage,
        const std::string& error_code,
        const std::string& message);

std::string qnn_execution_contract_mismatch_json(
        const std::string& field,
        size_t expected,
        size_t actual);

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_CONDITIONING_PIPELINE_HPP
