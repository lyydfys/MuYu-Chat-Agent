#ifndef MCA_QNN_CONTRACT_PARSER_HPP
#define MCA_QNN_CONTRACT_PARSER_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "diffusion_scheduler.hpp"
#include "qnn_image_pixel_range.hpp"

#if __has_include("qnn_execution_contract.hpp")
#include "qnn_execution_contract.hpp"
#endif

namespace mca {
namespace qnn {

enum class QnnVaeScalingLocation {
    HostBeforeGraph,
    GraphInternal,
    None,
};

struct QnnSchedulerContract {
    mca::diffusion::DiffusionSchedulerConfig config;
    int steps = 0;
    size_t expected_timetable_count = 0;
    size_t expected_unet_execution_count = 0;
    bool scale_model_input = false;
    float eta = 0.0f;
};

struct QnnSemanticExecutionContract {
    std::string profile_id;
    int profile_revision = 0;
    std::string model_fingerprint;
    QnnSchedulerContract scheduler;
    bool use_cfg = false;
    float cfg_scale = 0.0f;
    std::string prompt;
    std::string negative_prompt;
    std::string native_prompt_execution_sha256;
    std::string native_prompt_binding_stage;
    uint32_t seed = 0;
    std::string tokenizer_backend;
    size_t token_count = 0;
    int tokenizer_max_length = 0;
    bool prompt_weighting_supported = false;
    std::string embedding_disk_data_type;
    QnnVaeScalingLocation vae_scaling_location = QnnVaeScalingLocation::None;
    double vae_scaling_factor = 1.0;
    mca::qnn::ImagePixelRange pixel_range =
        mca::qnn::ImagePixelRange::NegativeOneToOne;
    int width = 0;
    int height = 0;
    std::string graph_name;
};

bool parse_qnn_scheduler_contract(
        const std::string& json,
        QnnSchedulerContract* contract,
        std::string* error);

bool parse_qnn_pixel_range_contract(
        const std::string& json,
        mca::qnn::ImagePixelRange* pixel_range,
        std::string* error);

bool parse_qnn_prompt_execution_fields(
        const std::string& json,
        std::string* prompt,
        std::string* negative_prompt,
        std::string* native_prompt_execution_sha256,
        std::string* native_prompt_binding_stage,
        std::string* error);

bool parse_qnn_semantic_execution_contract(
        const std::string& json,
        QnnSemanticExecutionContract* contract,
        std::string* error);

const char* qnn_scheduler_wire_name(mca::diffusion::SchedulerAlgorithm algorithm);

const char* qnn_prediction_wire_name(mca::diffusion::PredictionType prediction);

const char* qnn_vae_scaling_wire_name(QnnVaeScalingLocation location);

double qnn_effective_vae_host_scale(const QnnSemanticExecutionContract& contract);

std::string qnn_pixel_range_evidence_json(
        mca::qnn::ImagePixelRange range,
        const mca::qnn::ImagePixelRangeEvidence& evidence);

std::string qnn_nonempty_bundle_file_named(
        const std::vector<std::string>& files,
        const std::string& expected_name);

} // namespace qnn
} // namespace mca

// Compatibility aliases for global scope
using mca::qnn::QnnVaeScalingLocation;
using mca::qnn::QnnSchedulerContract;
using mca::qnn::QnnSemanticExecutionContract;
using mca::qnn::parse_qnn_scheduler_contract;
using mca::qnn::parse_qnn_pixel_range_contract;
using mca::qnn::parse_qnn_prompt_execution_fields;
using mca::qnn::parse_qnn_semantic_execution_contract;
using mca::qnn::qnn_scheduler_wire_name;
using mca::qnn::qnn_prediction_wire_name;
using mca::qnn::qnn_vae_scaling_wire_name;
using mca::qnn::qnn_effective_vae_host_scale;
using mca::qnn::qnn_pixel_range_evidence_json;
using mca::qnn::qnn_nonempty_bundle_file_named;

#endif // MCA_QNN_CONTRACT_PARSER_HPP
