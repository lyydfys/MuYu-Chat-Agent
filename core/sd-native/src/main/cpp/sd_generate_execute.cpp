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

auto &g_active_generation_sequence = mca::sd::active_generation_sequence();
auto &g_cancel_requested = mca::sd::cancel_requested();
auto &g_observed_prediction = mca::sd::observed_prediction();

} // namespace

namespace mca_sd_bridge_internal {

std::string sd_generate_execute(SdGenerateContext &state) {
    auto &output_path = state.output_path;
    auto &contract = state.contract;
    auto &output_paths = state.output_paths;
    auto &input_image = state.input_image;
    auto &mask_image = state.mask_image;
    auto &control_image = state.control_image;
    auto &validated_textual_inversions = state.validated_textual_inversions;
    auto &paths = state.paths;
    auto &split_qwen_image_21 = state.split_qwen_image_21;
    auto &ctx = state.ctx;
    auto &actual_qwen_image_21 = state.actual_qwen_image_21;
    auto &native_loras = state.native_loras;
    auto &qwen_condition = state.qwen_condition;
    auto &observed_prediction = state.observed_prediction;
    auto &prediction_wire = state.prediction_wire;
    auto &flow_shift_capable = state.flow_shift_capable;
    auto &dynamic_flow_shift = state.dynamic_flow_shift;
    auto &distilled_guidance_capable = state.distilled_guidance_capable;
    auto &gen = state.gen;
    auto &qwen_custom_sigmas = state.qwen_custom_sigmas;
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
    auto &g_generation_sequence = state.generation_sequence;
        mark_generation_stage(kStageContextReady);

        observed_prediction = g_observed_prediction.load(std::memory_order_relaxed);
        prediction_wire = observed_prediction_wire(observed_prediction);
        if (prediction_wire != contract.prediction_wire) {
            execution_mismatch(
                    "predictionType",
                    "resolved predictionType=" + contract.prediction_wire +
                    ", observed stable-diffusion.cpp prediction mode=" + prediction_wire);
        }
        flow_shift_capable = sd_ctx_uses_flow_shift(ctx);
        dynamic_flow_shift = sd_ctx_uses_dynamic_flow_shift(ctx);
        distilled_guidance_capable = sd_ctx_uses_distilled_guidance(ctx);
        if (!flow_shift_capable && contract.flow_shift >= 0.0) {
            unsupported_contract(
                    "flowShift",
                    "the loaded checkpoint has no flow denoiser; a numeric flow shift would be inert");
        }
        if (dynamic_flow_shift && contract.flow_shift >= 0.0) {
            unsupported_contract(
                    "flowShift",
                    "the loaded checkpoint derives flow shift from its sampling schedule and image sequence length");
        }
        if (contract.distilled_guidance_specified && !distilled_guidance_capable) {
            unsupported_contract(
                    "distilledGuidance",
                    "the loaded checkpoint has no distilled-guidance graph input");
        }
        if (contract.ultrafix_enabled &&
            (!sd_ctx_supports_ultrafix(ctx) || contract.prediction_wire == "FLOW")) {
            unsupported_contract(
                    "ultraFix",
                    "the loaded graph does not expose the complete standard-UNet UltraFix topology");
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            set_progress("cancelled", "cancelled before sampling", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        sd_img_gen_params_init(&gen);
        gen.loras = native_loras.empty() ? nullptr : native_loras.data();
        gen.lora_count = static_cast<uint32_t>(native_loras.size());
        gen.prompt = contract.prompt.c_str();
        gen.negative_prompt = contract.use_cfg ? contract.negative_prompt.c_str() : nullptr;
        gen.width = contract.width;
        gen.height = contract.height;
        gen.seed = contract.seed;
        gen.batch_count = contract.batch_count;
        gen.clip_skip = contract.clip_skip;
        gen.strength = backend_denoising_strength(contract);
        if (!input_image.pixels.empty()) gen.init_image = input_image.view();
        configure_inpaint_params(mask_image, gen);
        configure_controlnet_params(contract, control_image, gen);
        gen.vae_tiling_params.enabled = contract.vae_tiling_enabled;
        gen.vae_tiling_params.tile_size_x = 0;
        gen.vae_tiling_params.tile_size_y = 0;
        gen.vae_tiling_params.target_overlap = contract.vae_tiling_enabled
                ? static_cast<float>(contract.vae_tile_overlap)
                : 0.5f;
        gen.vae_tiling_params.rel_size_x = contract.vae_tiling_enabled
                ? std::min(1.0f,
                           static_cast<float>(contract.vae_tile_size) /
                               static_cast<float>(contract.width))
                : 0.0f;
        gen.vae_tiling_params.rel_size_y = contract.vae_tiling_enabled
                ? std::min(1.0f,
                           static_cast<float>(contract.vae_tile_size) /
                               static_cast<float>(contract.height))
                : 0.0f;
        gen.sample_params.sample_steps = contract.steps;
        gen.sample_params.guidance.txt_cfg = static_cast<float>(contract.cfg_scale);
        gen.sample_params.guidance.distilled_guidance =
                static_cast<float>(contract.distilled_guidance);
        if (contract.flow_shift >= 0.0) {
            gen.sample_params.flow_shift = static_cast<float>(contract.flow_shift);
        }
        qwen_custom_sigmas = contract.qwen_custom_sigmas;
        if (!contract.qwen_custom_sigmas.empty()) {
            gen.sample_params.custom_sigmas = qwen_custom_sigmas.data();
            gen.sample_params.custom_sigmas_count =
                    static_cast<int>(qwen_custom_sigmas.size());
            // The public API accepts the denoiser's multiplicative shift.  The
            // Viggle schedule publishes the corresponding logit mu, so pass
            // exp(mu) to keep native flow-shift evidence aligned with it.
            gen.sample_params.flow_shift = static_cast<float>(std::exp(contract.qwen_sigma_mu));
        }
        gen.sample_params.sample_method = contract.sample_method;
        gen.sample_params.scheduler = actual_qwen_image_21 ? FLUX_SCHEDULER : contract.native_scheduler;
        input_image_wired = task_uses_init_image(contract) &&
                gen.init_image.data != nullptr && gen.init_image.width > 0u && gen.init_image.height > 0u;
        if (task_uses_init_image(contract) != input_image_wired) {
            execution_mismatch(
                    "inputImagePath",
                    "the prepared input image was not wired into native init-latent generation");
        }
        mask_image_wired = false;
        std::string inpaint_field, inpaint_error;
        if (!verify_inpaint_wiring(contract, gen, mask_image_wired, inpaint_field, inpaint_error)) {
            execution_mismatch(inpaint_field, inpaint_error);
        }
        control_image_wired = false;
        std::string cn_field, cn_error;
        if (!verify_controlnet_wiring(contract, paths, gen, control_image_wired, cn_field, cn_error)) {
            execution_mismatch(cn_field, cn_error);
        }

        sequence =
                g_generation_sequence.fetch_add(1, std::memory_order_relaxed) + 1u;
        g_active_generation_sequence.store(sequence, std::memory_order_relaxed);
        mark_generation_stage(kStageGenerationInvoked);
        set_progress("preparing", "preparing image generation", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
        preview_context = PreviewPublicationContext{
                output_path,
                contract.preview_mode,
                contract.preview_interval,
                contract.steps};
        native_preview_mode = preview_mode_from_contract(contract.preview_mode);
        SdPreviewScope preview_scope(&preview_context, native_preview_mode, contract.preview_interval);
        if (contract.ultrafix_enabled) {
            sd_ultrafix_params_t ultrafix{};
            ultrafix.loras = gen.loras;
            ultrafix.lora_count = gen.lora_count;
            ultrafix.prompt = gen.prompt;
            ultrafix.negative_prompt = gen.negative_prompt;
            ultrafix.clip_skip = gen.clip_skip;
            ultrafix.init_image = gen.init_image;
            ultrafix.width = contract.ultrafix_target_width;
            ultrafix.height = contract.ultrafix_target_height;
            ultrafix.inversion_steps = contract.ultrafix_inversion_steps;
            ultrafix.refinement_steps = contract.ultrafix_refinement_steps;
            ultrafix.tile_size = contract.ultrafix_tile_size;
            ultrafix.tile_overlap = static_cast<float>(contract.ultrafix_overlap);
            ultrafix.strength = static_cast<float>(contract.ultrafix_strength);
            ultrafix.cfg_scale = gen.sample_params.guidance.txt_cfg;
            ultrafix.seed = gen.seed;
            ultrafix.sample_method = gen.sample_params.sample_method;
            ultrafix.scheduler = gen.sample_params.scheduler;
            ultrafix.eta = gen.sample_params.sample_method == EULER_A_SAMPLE_METHOD
                    ? 1.0f : 0.0f;
            ultrafix.extra_sample_args = gen.sample_params.extra_sample_args;
            ultrafix.vae_tiling_params = gen.vae_tiling_params;
            ultrafix.source_fit = SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER;
            images = sd_generate_ultrafix(ctx, &ultrafix, &ultrafix_evidence);
        } else if (split_qwen_image_21) {
            images = sd_generate_image_with_qwen_image_21_condition(
                    ctx,
                    &gen,
                    qwen_condition);
        } else {
            images = generate_image(ctx, &gen);
        }
        preview_scope.reset();
        if (qwen_condition != nullptr) {
            sd_free_qwen_image_21_condition(qwen_condition);
            qwen_condition = nullptr;
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            free_generated_images(images, contract.batch_count);
            set_progress("cancelled", "cancelled", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }
        native_evidence_available =
                sd_get_last_image_execution_evidence(ctx, &execution_evidence);
        textual_inversion_evidence_valid = native_evidence_available &&
                validate_textual_inversion_execution_evidence(
                        execution_evidence,
                        validated_textual_inversions.size(),
                        textual_inversion_failure_code,
                        textual_inversion_failure_message);
        bool complete_batch = images != nullptr;
        if (complete_batch) {
            for (int index = 0; index < contract.batch_count; ++index) {
                if (images[index].data == nullptr) {
                    complete_batch = false;
                    break;
                }
            }
        }
        if (!complete_batch) {
            free_generated_images(images, contract.batch_count);
            remove_output_files(output_paths);
            if (!validated_textual_inversions.empty() && native_evidence_available &&
                !textual_inversion_evidence_valid) {
                set_progress("failed", textual_inversion_failure_message, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
                return runtime_failure(
                        textual_inversion_failure_code,
                        textual_inversion_failure_message).dump();
            }
            set_progress("failed", "stable-diffusion.cpp returned no image", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return runtime_failure(
                    "NATIVE_GENERATION_FAILED",
                    "stable-diffusion.cpp returned an incomplete image batch").dump();
        }
        std::string preview_field, preview_error;
        if (!verify_preview_publication(native_preview_mode, preview_context, preview_field, preview_error)) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(preview_field, preview_error);
        }

    return {};
}

} // namespace mca_sd_bridge_internal
