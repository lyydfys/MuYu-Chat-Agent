// T2B17 / R7-C6 (2026-10-14): split out of stable_diffusion_bridge.cpp (pre-split sha256
// c11dcdd8a564ad59573e40d4554db435c1251d7946c5b99c52a9230eee8865cd).  Body lines are byte-exact
// relocations; every delta is registered in b-SPLIT-PLAN.json.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <exception>
#include <fcntl.h>
#include <iomanip>
#include <limits.h>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <unordered_set>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "tokenizers/clip_tokenizer.h"
#include "util.h"
#include "image_execution_math.hpp"
#include "execution_asset_binding.hpp"
#include "native_prompt_language_contract.hpp"
#include "mca/sd/sd_output_transaction.hpp"
#include "mca/sd/sd_input_contract.hpp"
#include "mca/sd/sd_progress_reporter.hpp"
#include "mca/sd/sd_schedule_resolver.hpp"
#include "mca/sd/sd_lora_adapter.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_textual_inversion.hpp"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_runtime_diagnostics.hpp"
#include "mca/sd/sd_upscaler.hpp"
#include "mca/sd/sd_context_key.hpp"
#include "mca/sd/sd_effective_config.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"
#include "mca/sd/sd_inpaint_processor.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_evidence_verifier.hpp"
#include "mca/sd/sd_response_builder.hpp"
using mca::sd::preview_mode_from_contract;
using mca::sd::PreviewPublicationContext;
using mca::sd::SdPreviewScope;
using mca::sd::verify_preview_publication;
using mca::sd::populate_preview_response_json;
using mca::sd::validate_controlnet_component;
using mca::sd::configure_controlnet_params;
using mca::sd::verify_controlnet_wiring;
using mca::sd::extract_controlnet_evidence;
using mca::sd::verify_controlnet_branch_classification;
using mca::sd::populate_controlnet_effective_json;
using mca::sd::populate_controlnet_response_json;
using mca::sd::load_and_validate_inpaint_mask;
using mca::sd::configure_inpaint_params;
using mca::sd::verify_inpaint_wiring;
using mca::sd::populate_inpaint_effective_json;
using mca::sd::populate_inpaint_response_json;
using mca::sd::progress_to_json;
using mca::sd::runtime_config_json;
using mca::sd::set_progress_from_sd_log;
using mca::sd::safe_sd_runtime_message;
using mca::sd::sd_log_callback;
using mca::sd::make_context_key;
using mca::sd::observed_prediction_mode;
using mca::sd::ComponentPaths;
using mca::sd::execution_assets_match_consumers;
using mca::sd::validate_lora_file;
using mca::sd::validate_textual_inversion_execution_evidence;
using mca::sd::component_selection_json;
using mca::sd::resolve_component_paths;
using mca::sd::is_sha256;
using mca::sd::LoadedInputImage;
using mca::sd::load_canonical_input_image;
using mca::sd::build_output_paths;
using mca::sd::free_generated_images;
using mca::sd::remove_output_files;
using mca::sd::ContractTextualInversion;
using mca::sd::regular_file_identity;
using mca::sd::same_regular_file_identity;
using mca::sd::sha256_hex;
using mca::sd::sha256_utf8;
using mca::sd::fixed_width_lower_hex_u64;
using mca::sd::textual_inversion_binding_fingerprint;
using mca::sd::textual_inversion_selection_fingerprint;
using mca::sd::image_prompt_execution_sha256;
using mca::sd::sha256_implementation_ready;
using mca::sd::sha256_regular_file;
using mca::sd::validate_textual_inversion_file;


#include "mca/sd/sd_contract_parser.hpp"
using mca::sd::ContractError;
using mca::sd::invalid_contract;
using mca::sd::unsupported_contract;
using mca::sd::execution_mismatch;
using mca::sd::contract_failure_json;
using mca::sd::optional_string;
using mca::sd::StableDiffusionExecutionContract;
using mca::sd::task_uses_init_image;
using mca::sd::task_uses_control_image;
using mca::sd::backend_denoising_strength;
using mca::sd::parse_execution_contract;
using mca::sd::observed_prediction_wire;
using mca::sd::runtime_failure;
using mca::sd::native_effective_json;

#include "stable_diffusion_bridge_internal.hpp"

using json = nlohmann::json;

using mca::sd::kStageContractValidated;
using mca::sd::kStageContextReady;
using mca::sd::kStageGenerationInvoked;
using mca::sd::kStageSamplingObserved;
using mca::sd::kStageImageReturned;
using mca::sd::kStageOutputWritten;
using mca::sd::kStageContextReleased;
using mca::sd::kStageInputImageDecoded;
using mca::sd::kStageMaskImageDecoded;
using mca::sd::kStageControlImageDecoded;
using mca::sd::kStageLoraValidated;
using mca::sd::kStageTextualInversionValidated;
using mca::sd::kStageQwenTextConditionEncoded;
using mca::sd::kStageQwenTextContextReleased;
using mca::sd::kStageQwenDiffusionContextReady;

namespace {

auto &g_cancel_requested = mca::sd::cancel_requested();
auto &g_generation_stage_mask = mca::sd::generation_stage_mask();
auto &g_ui_progress_callback_count = mca::sd::ui_progress_callback_count();
auto &g_ui_progress_max_step = mca::sd::ui_progress_max_step();
auto &g_ui_progress_reported_steps = mca::sd::ui_progress_reported_steps();

} // namespace

namespace mca_sd_bridge_internal {

std::string sd_generate_publish(SdGenerateContext &state) {
    auto &contract = state.contract;
    auto &output_paths = state.output_paths;
    auto &input_image = state.input_image;
    auto &mask_image = state.mask_image;
    auto &control_image = state.control_image;
    auto &validated_textual_inversions = state.validated_textual_inversions;
    auto &execution_asset_binding = state.execution_asset_binding;
    auto &has_execution_asset_binding = state.has_execution_asset_binding;
    auto &selected_components_json = state.selected_components_json;
    auto &split_qwen_image_21 = state.split_qwen_image_21;
    auto &ctx = state.ctx;
    auto &actual_qwen_image_21 = state.actual_qwen_image_21;
    auto &actual_model_version = state.actual_model_version;
    auto &native_loras = state.native_loras;
    auto &observed_prediction = state.observed_prediction;
    auto &prediction_wire = state.prediction_wire;
    auto &flow_shift_capable = state.flow_shift_capable;
    auto &dynamic_flow_shift = state.dynamic_flow_shift;
    auto &distilled_guidance_capable = state.distilled_guidance_capable;
    auto &gen = state.gen;
    auto &sequence = state.sequence;
    auto &preview_context = state.preview_context;
    auto &native_preview_mode = state.native_preview_mode;
    auto &images = state.images;
    auto &ultrafix_evidence = state.ultrafix_evidence;
    auto &execution_evidence = state.execution_evidence;
    auto &native_evidence_available = state.native_evidence_available;
    auto &textual_inversion_failure_code = state.textual_inversion_failure_code;
    auto &textual_inversion_failure_message = state.textual_inversion_failure_message;
    auto &textual_inversion_evidence_valid = state.textual_inversion_evidence_valid;
    auto &input_image_wired = state.input_image_wired;
    auto &mask_image_wired = state.mask_image_wired;
    auto &control_image_wired = state.control_image_wired;
        mark_generation_stage(kStageImageReturned);

        const sd_image_t &first = images[0];
        const int actual_width = static_cast<int>(first.width);
        const int actual_height = static_cast<int>(first.height);
        const int actual_channels = static_cast<int>(first.channel);
        if (actual_width <= 0 || actual_height <= 0 ||
            actual_channels < 1 || actual_channels > 4) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(
                    "output",
                    "stable-diffusion.cpp returned an invalid output image shape");
        }
        for (int index = 1; index < contract.batch_count; ++index) {
            if (images[index].width != first.width ||
                images[index].height != first.height ||
                images[index].channel != first.channel) {
                free_generated_images(images, contract.batch_count);
                execution_mismatch(
                        "outputs",
                        "stable-diffusion.cpp returned inconsistent dimensions within the image batch");
            }
        }
        auto release_images_and_mismatch = [&](const std::string &field,
                                               const std::string &message) -> void {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(field, message);
        };
        auto release_images_and_lora_failure = [&](const std::string &code,
                                                   const std::string &message) -> std::string {
            free_generated_images(images, contract.batch_count);
            remove_output_files(output_paths);
            set_progress("failed", message, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return runtime_failure(code, message).dump();
        };

        if (!native_evidence_available) {
            release_images_and_mismatch(
                    "nativeExecutionEvidence",
                    "native image execution did not publish context-local completion evidence");
        }
        if (execution_evidence.version != SD_IMAGE_EXECUTION_EVIDENCE_VERSION ||
            execution_evidence.generation_completed == 0u) {
            release_images_and_mismatch(
                    "nativeExecutionEvidence",
                    "native image execution evidence is incomplete or has an unsupported version");
        }
        if (!textual_inversion_evidence_valid) {
            return release_images_and_lora_failure(
                    textual_inversion_failure_code,
                    textual_inversion_failure_message);
        }
        /*
        if (!contract.ultrafix_enabled &&
            actual_positive_execution_count != actual_timetable_count)
        */

        std::string lora_evidence_code, lora_evidence_message;
        if (!mca::sd::verify_lora_execution_evidence(
                contract.loras.size(), execution_evidence, lora_evidence_code, lora_evidence_message)) {
            return release_images_and_lora_failure(lora_evidence_code, lora_evidence_message);
        }

        auto evidence_per_image = [&](uint64_t val, const std::string &field) -> int {
            if (val % static_cast<uint64_t>(contract.batch_count) != 0u) {
                release_images_and_mismatch(field, "native execution evidence is not divisible by the completed batch count");
            }
            if (val / static_cast<uint64_t>(contract.batch_count) > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
                release_images_and_mismatch(field, "native execution evidence exceeds int32 range");
            }
            return static_cast<int>(val / static_cast<uint64_t>(contract.batch_count));
        };
        const auto controlnet_evidence = extract_controlnet_evidence(
                execution_evidence, contract.batch_count, evidence_per_image);

        mca::sd::GenerationEvidenceSummary summary;
        std::string mismatch_field, mismatch_message;
        if (!mca::sd::verify_generation_evidence(
                contract, ctx, gen, actual_width, actual_height,
                input_image_wired, input_image, control_image_wired, controlnet_evidence,
                ultrafix_evidence, execution_evidence, flow_shift_capable, dynamic_flow_shift,
                distilled_guidance_capable, summary, mismatch_field, mismatch_message)) {
            release_images_and_mismatch(mismatch_field, mismatch_message);
        }

        if (has_execution_asset_binding) {
            std::string asset_error;
            if (!mca::image::execution_assets::verify_final(
                    execution_asset_binding,
                    [] { return g_cancel_requested.load(std::memory_order_relaxed); },
                    asset_error)) {
                if (g_cancel_requested.load(std::memory_order_relaxed) || asset_error == "cancelled") {
                    free_generated_images(images, contract.batch_count);
                    return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
                }
                release_images_and_mismatch(
                    "textualInversionExecutionAssets",
                    asset_error.empty() ? "execution assets changed during native generation" : asset_error);
            }
        }

        const uint32_t stage_mask = g_generation_stage_mask.load(std::memory_order_relaxed);
        json native_effective = mca::sd::build_generation_effective_json(
                contract, prediction_wire, gen, summary, actual_model_version, actual_qwen_image_21,
                split_qwen_image_21, stage_mask, input_image_wired, input_image, mask_image_wired,
                mask_image, controlnet_evidence, control_image_wired, control_image, ultrafix_evidence,
                native_loras, execution_evidence, validated_textual_inversions, has_execution_asset_binding,
                execution_asset_binding);

        set_progress("writing", "writing png", summary.actual_timetable_count, summary.actual_timetable_count,
                     0.0f, actual_width, actual_height, contract.threads);

        if (first.width > static_cast<uint32_t>(std::numeric_limits<int>::max() / actual_channels)) {
            free_generated_images(images, contract.batch_count);
            return runtime_failure("OUTPUT_WRITE_FAILED", "output row stride exceeds the native PNG writer range").dump();
        }

        std::vector<std::string> output_sha256s;
        std::vector<uint64_t> output_byte_counts;
        std::string output_write_error;
        const bool write_ok = mca::sd::write_generation_png_batch(
                contract, g_ctx_key, output_paths, images, actual_width, actual_height, actual_channels,
                g_cancel_requested, output_sha256s, output_byte_counts, output_write_error);
        free_generated_images(images, contract.batch_count);

        if (!write_ok) {
            remove_output_files(output_paths);
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                set_progress("cancelled", "cancelled before output publication", summary.actual_timetable_count,
                             summary.actual_timetable_count, 0.0f, actual_width, actual_height, contract.threads);
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            set_progress("failed", output_write_error.empty() ? "failed to write png" : output_write_error,
                         summary.actual_timetable_count, summary.actual_timetable_count, 0.0f, actual_width, actual_height, contract.threads);
            return runtime_failure("OUTPUT_WRITE_FAILED", output_write_error.empty() ? "failed to write the complete PNG batch" : output_write_error).dump();
        }

        mark_generation_stage(kStageOutputWritten);
        set_progress("completed", "image saved", summary.actual_timetable_count, summary.actual_timetable_count,
                     0.0f, actual_width, actual_height, contract.threads);

        const int ui_progress_callback_count = g_ui_progress_callback_count.load(std::memory_order_relaxed);
        const int ui_progress_reported_steps = g_ui_progress_reported_steps.load(std::memory_order_relaxed);
        const int ui_progress_max_step = g_ui_progress_max_step.load(std::memory_order_relaxed);

        json out = mca::sd::build_generation_success_response(
                std::move(native_effective), contract, output_paths, output_sha256s, output_byte_counts, gen,
                summary, execution_evidence, actual_width, actual_height, actual_channels, observed_prediction,
                ui_progress_callback_count, ui_progress_reported_steps, ui_progress_max_step,
                native_preview_mode, preview_context, input_image_wired, input_image, mask_image_wired,
                mask_image, control_image_wired, control_image, selected_components_json, sequence);
        return out.dump();
}

} // namespace mca_sd_bridge_internal
