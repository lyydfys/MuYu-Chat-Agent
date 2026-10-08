#include "mca/qnn/qnn_ultrafix_handler.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <limits>
#include <sstream>
#include <utility>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca::qnn {

namespace {

std::string fixed_width_lower_hex_u64(uint64_t value) {
    std::ostringstream out;
    out << std::hex << std::nouppercase << std::setfill('0') << std::setw(16) << value;
    return out.str();
}

} // namespace

bool parse_qnn_vae_tiling_request(
        const std::string& params_json,
        QnnVaeTilingConfig* config,
        std::string* error) {
    if (config == nullptr || error == nullptr) return false;
    *config = QnnVaeTilingConfig{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.contains("vaeTiling")) {
            error->clear();
            return true;
        }
        const auto& vae_tiling = root.at("vaeTiling");
        if (!vae_tiling.is_object() || vae_tiling.size() != 2U ||
            !vae_tiling.contains("tileSize") || !vae_tiling.contains("overlap") ||
            !vae_tiling.at("tileSize").is_number_integer() ||
            !vae_tiling.at("overlap").is_number()) {
            *error = "QNN VAE tile controls must contain exactly integer tileSize and numeric overlap.";
            return false;
        }
        const long long tile_size = vae_tiling.at("tileSize").get<long long>();
        const double overlap = vae_tiling.at("overlap").get<double>();
        if (tile_size < std::numeric_limits<int>::min() ||
            tile_size > std::numeric_limits<int>::max() ||
            !std::isfinite(overlap)) {
            *error = "QNN VAE tile controls contain out-of-range dimensions or non-finite overlap.";
            return false;
        }
        config->tile_size = static_cast<int>(tile_size);
        config->overlap = overlap;
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN VAE tiling JSON is invalid: ") + exception.what();
        return false;
    }
}

bool parse_qnn_ultrafix_request(
        const std::string& params_json,
        QnnUltraFixRequest* request,
        std::string* error) {
    if (request == nullptr || error == nullptr) return false;
    *request = QnnUltraFixRequest{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.contains("ultraFix")) {
            error->clear();
            return true;
        }
        const auto& value = root.at("ultraFix");
        static constexpr std::array<const char*, 7> kFields = {
            "targetWidth",
            "targetHeight",
            "strength",
            "inversionSteps",
            "refinementSteps",
            "tileSize",
            "overlap",
        };
        if (!value.is_object() || value.size() != kFields.size() ||
            std::any_of(kFields.begin(), kFields.end(), [&](const char* field) {
                return !value.contains(field);
            })) {
            *error = "QNN UltraFix must contain exactly the seven structured request fields.";
            return false;
        }
        const auto exact_int = [&](const char* field, int* output) {
            const auto& item = value.at(field);
            if (!item.is_number_integer()) return false;
            const long long parsed = item.get<long long>();
            if (parsed < std::numeric_limits<int>::min() ||
                parsed > std::numeric_limits<int>::max()) return false;
            *output = static_cast<int>(parsed);
            return true;
        };
        if (!exact_int("targetWidth", &request->target_width) ||
            !exact_int("targetHeight", &request->target_height) ||
            !exact_int("inversionSteps", &request->inversion_steps) ||
            !exact_int("refinementSteps", &request->refinement_steps) ||
            !exact_int("tileSize", &request->tile_size) ||
            !value.at("strength").is_number() ||
            !value.at("overlap").is_number()) {
            *error = "QNN UltraFix dimensions and step counts must be exact integers, with finite numeric strength and overlap.";
            return false;
        }
        request->strength = value.at("strength").get<double>();
        request->overlap = value.at("overlap").get<double>();
        request->enabled = true;
        if (request->target_width < 512 || request->target_width > 2048 ||
            request->target_height < 512 || request->target_height > 2048 ||
            request->target_width % 8 != 0 || request->target_height % 8 != 0 ||
            request->tile_size < 128 || request->tile_size > 2048 ||
            request->tile_size % 8 != 0 ||
            request->tile_size > std::min(
                request->target_width, request->target_height) ||
            !std::isfinite(request->strength) || request->strength <= 0.0 ||
            request->strength > 1.0 || request->inversion_steps < 1 ||
            request->inversion_steps > 100 || request->refinement_steps < 1 ||
            request->refinement_steps > 100 ||
            request->inversion_steps > request->refinement_steps ||
            !std::isfinite(request->overlap) || request->overlap < 0.0 ||
            request->overlap > 0.5) {
            *error = "QNN UltraFix request exceeds the bounded target, tile, strength, step, or overlap contract.";
            return false;
        }
        if (!root.contains("vaeTiling") || !root.at("vaeTiling").is_object()) {
            *error = "QNN UltraFix requires its matching request-local VAE tile controls.";
            return false;
        }
        const auto& vae_tiling = root.at("vaeTiling");
        if (vae_tiling.size() != 2U || !vae_tiling.contains("tileSize") ||
            !vae_tiling.contains("overlap") ||
            !vae_tiling.at("tileSize").is_number_integer() ||
            !vae_tiling.at("overlap").is_number() ||
            vae_tiling.at("tileSize").get<long long>() != request->tile_size ||
            !std::isfinite(vae_tiling.at("overlap").get<double>()) ||
            std::fabs(vae_tiling.at("overlap").get<double>() - request->overlap) >
                1.0e-12) {
            *error = "QNN UltraFix VAE tile controls conflict with its structured request.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN UltraFix JSON is invalid: ") + exception.what();
        return false;
    }
}

std::string qnn_ultrafix_evidence_json(
        const QnnUltraFixExecutionEvidence& evidence) {
    const auto stage = [](size_t invocations,
                          size_t successes,
                          size_t tile_invocations,
                          size_t tile_successes,
                          size_t steps) {
        return nlohmann::json{
            {"invocationCount", invocations},
            {"successCount", successes},
            {"tileInvocationCount", tile_invocations},
            {"tileSuccessCount", tile_successes},
            {"stepCount", steps},
        };
    };
    const size_t tile_count = evidence.plan.tiles.size();
    return nlohmann::json{
        {"version", 2},
        {"generationCompleted", true},
        {"cancelled", false},
        {"previewPublished", false},
        {"sourceWidth", evidence.source_width},
        {"sourceHeight", evidence.source_height},
        {"targetWidth", evidence.request.target_width},
        {"targetHeight", evidence.request.target_height},
        {"sourceFit", "cover_center"},
        {"sourceResizedWidth", evidence.source_resized_width},
        {"sourceResizedHeight", evidence.source_resized_height},
        {"sourceCropLeft", evidence.source_crop_left},
        {"sourceCropTop", evidence.source_crop_top},
        {"tileSize", evidence.request.tile_size},
        {"overlap", evidence.request.overlap},
        {"tileCount", tile_count},
        {"tilePlanSha256", evidence.tile_plan_sha256},
        {"inversionSteps", evidence.request.inversion_steps},
        {"refinementSteps", evidence.request.refinement_steps},
        {"denoiseStepCount", evidence.request.inversion_steps},
        {"sampleMethod", evidence.sample_method},
        {"nativeScheduler", evidence.native_scheduler},
        {"vaeEncode", stage(
            evidence.encoder_context_load_count,
            evidence.encoder_context_load_count,
            evidence.encoder_graph_execution_count,
            evidence.encoder_tile_success_count,
            evidence.encoder_context_load_count)},
        {"ddimInversion", stage(
            evidence.inversion_step_count,
            evidence.inversion_step_count,
            evidence.inversion_graph_execution_count,
            evidence.inversion_tile_success_count,
            evidence.inversion_step_count)},
        {"tiledUnetRefinement", stage(
            evidence.refinement_step_count,
            evidence.refinement_step_count,
            evidence.refinement_positive_graph_execution_count +
                evidence.refinement_negative_graph_execution_count,
            evidence.refinement_tile_success_count,
            evidence.refinement_step_count)},
        {"tiledVaeDecode", stage(
            1U,
            1U,
            evidence.decoder_graph_execution_count,
            evidence.decoder_tile_success_count,
            1U)},
        {"encoderGraphExecutionCount", evidence.encoder_graph_execution_count},
        {"inversionPositiveGraphExecutionCount",
            evidence.inversion_graph_execution_count},
        {"refinementPositiveGraphExecutionCount",
            evidence.refinement_positive_graph_execution_count},
        {"refinementNegativeGraphExecutionCount",
            evidence.refinement_negative_graph_execution_count},
        {"decoderGraphExecutionCount", evidence.decoder_graph_execution_count},
        {"physicalDiffusionModelComputeCount",
            evidence.physical_unet_graph_execution_count},
        {"qualityStepEvaluationCount", evidence.quality_step_evaluation_count},
        {"noiseInjectionStepCount", evidence.noise_injection_step_count},
        {"noiseInjectionSeedFingerprint",
            evidence.noise_injection_seed_fingerprint},
        {"noiseInjectionChecksum",
            fixed_width_lower_hex_u64(evidence.noise_injection_checksum)},
        {"structureGuidanceStepCount", evidence.structure_guidance_step_count},
        {"structureGuidanceChecksum",
            fixed_width_lower_hex_u64(evidence.structure_guidance_checksum)},
        {"trajectoryNoiseChecksum",
            fixed_width_lower_hex_u64(evidence.trajectory_noise_checksum)},
        {"outputSha256", evidence.output_sha256},
        {"outputBytes", evidence.output_bytes},
        {"outputAtomicCommit", evidence.output_atomic_commit},
    }.dump();
}

} // namespace mca::qnn
