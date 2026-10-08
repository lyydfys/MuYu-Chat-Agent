#ifndef MCA_QNN_ULTRAFIX_HANDLER_HPP
#define MCA_QNN_ULTRAFIX_HANDLER_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "image_execution_math.hpp"

namespace mca::qnn {

/**
 * Structured request for QNN UltraFix patch-based super-resolution / repair.
 */
struct QnnUltraFixRequest {
    bool enabled = false;
    int target_width = 0;
    int target_height = 0;
    double strength = 0.0;
    int inversion_steps = 0;
    int refinement_steps = 0;
    int tile_size = 0;
    double overlap = 0.0;
};

/**
 * Structured configuration for matching VAE tiling controls.
 */
struct QnnVaeTilingConfig {
    int tile_size = 0;
    double overlap = 0.0;
};

/**
 * Parsed execution evidence for an UltraFix image repair pass.
 */
struct QnnUltraFixExecutionEvidence {
    QnnUltraFixRequest request;
    mca::image::UltraFixTilePlan plan;
    std::string tile_plan_sha256;
    size_t source_width = 0U;
    size_t source_height = 0U;
    size_t source_resized_width = 0U;
    size_t source_resized_height = 0U;
    size_t source_crop_left = 0U;
    size_t source_crop_top = 0U;
    size_t encoder_context_load_count = 0U;
    size_t encoder_graph_execution_count = 0U;
    size_t encoder_tile_success_count = 0U;
    size_t inversion_step_count = 0U;
    size_t inversion_graph_execution_count = 0U;
    size_t inversion_tile_success_count = 0U;
    size_t refinement_step_count = 0U;
    size_t refinement_positive_graph_execution_count = 0U;
    size_t refinement_negative_graph_execution_count = 0U;
    size_t refinement_tile_success_count = 0U;
    size_t decoder_graph_execution_count = 0U;
    size_t decoder_tile_success_count = 0U;
    size_t physical_unet_graph_execution_count = 0U;
    size_t quality_step_evaluation_count = 0U;
    size_t noise_injection_step_count = 0U;
    std::string noise_injection_seed_fingerprint;
    uint64_t noise_injection_checksum = 0U;
    size_t structure_guidance_step_count = 0U;
    uint64_t structure_guidance_checksum = 0U;
    uint64_t trajectory_noise_checksum = 0U;
    std::string sample_method;
    std::string native_scheduler;
    std::string output_sha256;
    long long output_bytes = 0;
    bool output_atomic_commit = false;
};

/**
 * Parses the standalone VAE tiling parameters from execution JSON params.
 */
bool parse_qnn_vae_tiling_request(
        const std::string& params_json,
        QnnVaeTilingConfig* config,
        std::string* error);

/**
 * Parses the UltraFix parameters and verifies consistency with VAE tiling controls.
 */
bool parse_qnn_ultrafix_request(
        const std::string& params_json,
        QnnUltraFixRequest* request,
        std::string* error);

/**
 * Serializes UltraFix execution evidence into a standardized JSON string.
 */
std::string qnn_ultrafix_evidence_json(
        const QnnUltraFixExecutionEvidence& evidence);

} // namespace mca::qnn

// Compatibility aliases for global scope
using mca::qnn::QnnUltraFixRequest;
using mca::qnn::QnnVaeTilingConfig;
using mca::qnn::QnnUltraFixExecutionEvidence;
using mca::qnn::parse_qnn_vae_tiling_request;
using mca::qnn::parse_qnn_ultrafix_request;
using mca::qnn::qnn_ultrafix_evidence_json;

#endif // MCA_QNN_ULTRAFIX_HANDLER_HPP
