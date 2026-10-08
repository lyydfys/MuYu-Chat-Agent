#include "mca/sd/sd_upscaler.hpp"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <memory>
#include <string>
#include <utility>

#include "json.hpp"
#include "stable-diffusion.h"
#include "stb_image_write.h"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_contract_parser.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_input_contract.hpp"
#include "mca/sd/sd_progress_reporter.hpp"
#include "mca/sd/sd_runtime_diagnostics.hpp"
#include "mca/sd/sd_textual_inversion.hpp"

namespace mca {
namespace sd {

namespace {

using json = nlohmann::json;

json runtime_failure(const std::string &code, const std::string &message) {
    return json({
            {"ok", false},
            {"errorCode", code},
            {"error", message}
    });
}

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

} // namespace

std::string SdUpscaler::execute(
        const std::string &raw_upscaler_path,
        const std::string &raw_upscaler_root,
        const std::string &raw_input_path,
        const std::string &raw_params,
        const std::string &raw_output_path,
        std::atomic<uint64_t> *sequence_counter) {
    try {
        const json params = json::parse(raw_params);
        const std::string upscaler_id = required_string(params, "upscalerId");
        const std::string requested_upscaler_sha256 =
                lower_copy(required_string(params, "upscalerSha256"));
        const int64_t upscaler_size_signed = required_integer(params, "upscalerSizeBytes");
        const std::string input_sha256 =
                lower_copy(required_string(params, "inputImageSha256"));
        const int target_scale = required_int32(params, "targetScale");
        const int tile_size = required_int32(params, "tileSize");
        const int threads = required_int32(params, "threads");
        if (!is_sha256(requested_upscaler_sha256)) {
            invalid_contract("upscalerSha256", "must be a 64-character SHA-256 value");
        }
        if (!is_sha256(input_sha256)) {
            invalid_contract("inputImageSha256", "must be a 64-character SHA-256 value");
        }
        if (upscaler_size_signed < 16 ||
            static_cast<uint64_t>(upscaler_size_signed) > kMaxUpscalerBytes) {
            invalid_contract("upscalerSizeBytes", "must describe a bounded non-empty model file");
        }
        if (target_scale != 2 && target_scale != 3 && target_scale != 4) {
            invalid_contract("targetScale", "must be 2, 3, or 4");
        }
        if (tile_size < 32 || tile_size > 1024 || tile_size % 8 != 0) {
            invalid_contract("tileSize", "must be a multiple of 8 in [32, 1024]");
        }
        if (threads < 1 || threads > 64) {
            invalid_contract("threads", "must be in [1, 64]");
        }

        std::string upscaler_path;
        std::string validation_error;
        if (!validate_upscaler_file(
                raw_upscaler_root,
                raw_upscaler_path,
                static_cast<uint64_t>(upscaler_size_signed),
                upscaler_path,
                validation_error)) {
            return runtime_failure("UPSCALER_FILE_INVALID", validation_error).dump();
        }
        const size_t upscaler_leaf_separator = upscaler_path.find_last_of('/');
        const std::string upscaler_file_name = upscaler_leaf_separator == std::string::npos
                ? upscaler_path
                : upscaler_path.substr(upscaler_leaf_separator + 1u);
        const std::string expected_upscaler_name_prefix = "upscaler-" + upscaler_id + ".";
        if (upscaler_file_name.rfind(expected_upscaler_name_prefix, 0u) != 0u) {
            return runtime_failure(
                    "UPSCALER_IDENTITY_MISMATCH",
                    "upscaler id does not match the app-owned model file identity").dump();
        }
        std::string output_path;
        if (!validate_canonical_output_path(raw_output_path, output_path, validation_error)) {
            return runtime_failure("OUTPUT_PATH_INVALID", validation_error).dump();
        }
        mark_generation_stage(kStageContractValidated);

        set_progress(
                "verifying_upscaler",
                "verifying ESRGAN model bytes",
                0,
                1,
                0.0f,
                0,
                0,
                threads);
        const uint64_t upscaler_size = static_cast<uint64_t>(upscaler_size_signed);
        std::string actual_upscaler_sha256;
        RegularFileIdentity hashed_upscaler_identity;
        if (!sha256_regular_file(
                upscaler_path,
                upscaler_size,
                actual_upscaler_sha256,
                hashed_upscaler_identity,
                validation_error,
                [&]() { return cancel_requested().load(std::memory_order_relaxed); })) {
            if (cancel_requested().load(std::memory_order_relaxed)) {
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            return runtime_failure("UPSCALER_NATIVE_HASH_FAILED", validation_error).dump();
        }
        if (actual_upscaler_sha256 != requested_upscaler_sha256) {
            return runtime_failure(
                    "UPSCALER_SHA256_MISMATCH",
                    "native SHA-256 does not match the worker-verified upscaler contract").dump();
        }

        LoadedInputImage input;
        if (!load_canonical_input_image(
                raw_input_path,
                input_sha256,
                3,
                input,
                validation_error,
                kMaxUpscaleInputSide,
                kMaxUpscaleInputPixels,
                "upscale input exceeds the 2048-pixel side or 4-megapixel execution limit")) {
            return runtime_failure(
                    validation_error ==
                            "upscale input exceeds the 2048-pixel side or 4-megapixel execution limit"
                            ? "UPSCALE_INPUT_TOO_LARGE"
                            : "UPSCALE_INPUT_INVALID",
                    validation_error).dump();
        }
        mark_generation_stage(kStageInputImageDecoded);
        const uint64_t requested_width =
                static_cast<uint64_t>(input.width) * static_cast<uint64_t>(target_scale);
        const uint64_t requested_height =
                static_cast<uint64_t>(input.height) * static_cast<uint64_t>(target_scale);
        const uint64_t requested_pixels = requested_width * requested_height;
        if (requested_width > kMaxUpscaleProductOutputSide ||
            requested_height > kMaxUpscaleProductOutputSide ||
            requested_pixels > kMaxUpscaleProductOutputPixels) {
            return runtime_failure(
                    "UPSCALE_OUTPUT_TOO_LARGE",
                    "requested upscale output exceeds the bounded 4096-pixel side or 16-megapixel product limit").dump();
        }
        set_progress(
                "loading_upscaler",
                "loading ESRGAN upscaler",
                0,
                1,
                0.0f,
                static_cast<int>(input.width),
                static_cast<int>(input.height),
                threads);

        std::unique_ptr<upscaler_ctx_t, decltype(&free_upscaler_ctx)> upscaler_ctx(
                new_upscaler_ctx(
                        upscaler_path.c_str(),
                        false,
                        false,
                        threads,
                        tile_size,
                        "cpu",
                        "cpu"),
                &free_upscaler_ctx);
        if (!upscaler_ctx) {
            return runtime_failure(
                    "UPSCALER_NATIVE_LOAD_FAILED",
                    "stable-diffusion.cpp could not load the selected ESRGAN model").dump();
        }
        mark_generation_stage(kStageContextReady);
        RegularFileIdentity loaded_upscaler_identity;
        if (!read_regular_file_identity(
                upscaler_path,
                upscaler_size,
                loaded_upscaler_identity,
                validation_error) ||
            !same_regular_file_identity(hashed_upscaler_identity, loaded_upscaler_identity)) {
            return runtime_failure(
                    "UPSCALER_FILE_CHANGED_DURING_LOAD",
                    validation_error.empty()
                            ? "upscaler model identity changed between hashing and native load"
                            : validation_error).dump();
        }
        const int native_scale = get_upscale_factor(upscaler_ctx.get());
        if (native_scale < target_scale || native_scale < 2 || native_scale > 8) {
            return runtime_failure(
                    "UPSCALER_SCALE_UNSUPPORTED",
                    "the selected ESRGAN model cannot produce the requested target scale").dump();
        }
        const uint64_t expected_width =
                static_cast<uint64_t>(input.width) * static_cast<uint64_t>(native_scale);
        const uint64_t expected_height =
                static_cast<uint64_t>(input.height) * static_cast<uint64_t>(native_scale);
        const uint64_t expected_pixels = expected_width * expected_height;
        if (expected_width > kMaxUpscaleNativeOutputSide ||
            expected_height > kMaxUpscaleNativeOutputSide ||
            expected_pixels > kMaxUpscaleNativeOutputPixels) {
            return runtime_failure(
                    "UPSCALE_OUTPUT_TOO_LARGE",
                    "native ESRGAN intermediate exceeds the bounded 8192-pixel side or 64-megapixel limit").dump();
        }
        if (cancel_requested().load(std::memory_order_relaxed)) {
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        static std::atomic<uint64_t> s_default_generation_sequence{0};
        const uint64_t sequence = sequence_counter
                ? sequence_counter->fetch_add(1u, std::memory_order_relaxed) + 1u
                : s_default_generation_sequence.fetch_add(1u, std::memory_order_relaxed) + 1u;
        active_generation_sequence().store(sequence, std::memory_order_relaxed);
        mark_generation_stage(kStageGenerationInvoked);
        set_progress(
                "upscaling",
                "running ESRGAN upscale",
                0,
                1,
                0.0f,
                static_cast<int>(expected_width),
                static_cast<int>(expected_height),
                threads);
        sd_image_t upscaled = upscale(
                upscaler_ctx.get(),
                input.view(),
                static_cast<uint32_t>(native_scale));
        sd_upscaler_execution_evidence_t execution_evidence{};
        const bool has_execution_evidence = sd_get_upscaler_execution_evidence(
                upscaler_ctx.get(),
                &execution_evidence);
        upscaler_ctx.reset();
        mark_generation_stage(kStageContextReleased);
        if (cancel_requested().load(std::memory_order_relaxed)) {
            if (upscaled.data != nullptr) std::free(upscaled.data);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }
        const bool expected_tiled = input.width > static_cast<uint32_t>(tile_size) ||
                                    input.height > static_cast<uint32_t>(tile_size);
        const bool compute_counts_valid =
                execution_evidence.compute_invocation_count > 0u &&
                execution_evidence.compute_success_count ==
                        execution_evidence.compute_invocation_count;
        const bool tile_counts_valid = expected_tiled
                ? execution_evidence.tiled == 1u &&
                  execution_evidence.tile_compute_invocation_count ==
                          execution_evidence.compute_invocation_count &&
                  execution_evidence.tile_compute_success_count ==
                          execution_evidence.compute_success_count
                : execution_evidence.tiled == 0u &&
                  execution_evidence.compute_invocation_count == 1u &&
                  execution_evidence.tile_compute_invocation_count == 0u &&
                  execution_evidence.tile_compute_success_count == 0u;
        if (!has_execution_evidence ||
            execution_evidence.version != SD_UPSCALER_EXECUTION_EVIDENCE_VERSION ||
            execution_evidence.completed != 1u || execution_evidence.cancelled != 0u ||
            execution_evidence.requested_scale != static_cast<uint32_t>(native_scale) ||
            execution_evidence.native_scale != static_cast<uint32_t>(native_scale) ||
            execution_evidence.source_width != input.width ||
            execution_evidence.source_height != input.height ||
            execution_evidence.output_width != static_cast<uint32_t>(expected_width) ||
            execution_evidence.output_height != static_cast<uint32_t>(expected_height) ||
            !compute_counts_valid || !tile_counts_valid) {
            if (upscaled.data != nullptr) std::free(upscaled.data);
            return runtime_failure(
                    "UPSCALER_EXECUTION_EVIDENCE_INVALID",
                    "ESRGAN physical compute evidence is missing or inconsistent").dump();
        }
        if (upscaled.data == nullptr ||
            upscaled.width != static_cast<uint32_t>(expected_width) ||
            upscaled.height != static_cast<uint32_t>(expected_height) ||
            upscaled.channel != 3u) {
            if (upscaled.data != nullptr) std::free(upscaled.data);
            return runtime_failure(
                    "UPSCALER_NATIVE_OUTPUT_INVALID",
                    "stable-diffusion.cpp returned an invalid ESRGAN output tensor").dump();
        }
        mark_generation_stage(kStageSamplingObserved);
        mark_generation_stage(kStageImageReturned);
        if (upscaled.width > static_cast<uint32_t>(
                std::numeric_limits<int>::max() / static_cast<int>(upscaled.channel))) {
            std::free(upscaled.data);
            return runtime_failure("OUTPUT_WRITE_FAILED", "upscale output row stride is too large").dump();
        }
        set_progress(
                "writing",
                "writing upscaled PNG",
                1,
                1,
                0.0f,
                static_cast<int>(upscaled.width),
                static_cast<int>(upscaled.height),
                threads);
        const int write_ok = stbi_write_png(
                output_path.c_str(),
                static_cast<int>(upscaled.width),
                static_cast<int>(upscaled.height),
                static_cast<int>(upscaled.channel),
                upscaled.data,
                static_cast<int>(upscaled.width * upscaled.channel));
        std::free(upscaled.data);
        if (write_ok == 0) {
            std::remove(output_path.c_str());
            return runtime_failure("OUTPUT_WRITE_FAILED", "failed to write the upscaled PNG").dump();
        }
        mark_generation_stage(kStageOutputWritten);
        set_progress(
                "completed",
                "upscale completed",
                1,
                1,
                0.0f,
                static_cast<int>(expected_width),
                static_cast<int>(expected_height),
                threads);

        json native_effective = {
                {"operation", "ESRGAN_UPSCALE"},
                {"runtime", "STABLE_DIFFUSION_CPP"},
                {"backendMode", "cpu"},
                {"fallback", false},
                {"upscalerId", upscaler_id},
                {"upscalerFileName", upscaler_file_name},
                {"upscalerSha256", actual_upscaler_sha256},
                {"upscalerSizeBytes", upscaler_size},
                {"modelHashVerified", true},
                {"modelFileIdentityStable", true},
                {"inputImageSha256", input.sha256},
                {"sourceWidth", input.width},
                {"sourceHeight", input.height},
                {"nativeScale", native_scale},
                {"requestedTargetScale", target_scale},
                {"width", expected_width},
                {"height", expected_height},
                {"channels", 3},
                {"tileSize", tile_size},
                {"threads", threads},
                {"physicalComputeCount", execution_evidence.compute_invocation_count},
                {"physicalComputeSuccessCount", execution_evidence.compute_success_count},
                {"physicalTileComputeCount", execution_evidence.tile_compute_invocation_count},
                {"physicalTileComputeSuccessCount", execution_evidence.tile_compute_success_count},
                {"tiledExecution", execution_evidence.tiled == 1u},
                {"executionCompleted", execution_evidence.completed == 1u},
                {"nativeGenerationSequence", sequence}
        };
        json result = native_effective;
        result["ok"] = true;
        result["nativeExecution"] = true;
        result["path"] = output_path;
        result["mimeType"] = "image/png";
        result["nativeEffective"] = native_effective;
        result["nativeGenerationSequence"] = sequence;
        return result.dump();
    } catch (const ContractError &error) {
        set_progress("failed", error.what());
        return contract_failure_json(error).dump();
    } catch (const json::exception &error) {
        set_progress("failed", error.what());
        return contract_failure_json(ContractError(
                "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                "upscaleParams",
                error.what())).dump();
    } catch (const std::exception &error) {
        set_progress("failed", error.what());
        return runtime_failure("UPSCALER_NATIVE_FAILURE", error.what()).dump();
    }
}

std::string execute_sd_upscale(
        const std::string &raw_upscaler_path,
        const std::string &raw_upscaler_root,
        const std::string &raw_input_path,
        const std::string &raw_params,
        const std::string &raw_output_path,
        std::atomic<uint64_t> *sequence_counter) {
    return SdUpscaler::execute(
            raw_upscaler_path,
            raw_upscaler_root,
            raw_input_path,
            raw_params,
            raw_output_path,
            sequence_counter);
}

} // namespace sd
} // namespace mca
