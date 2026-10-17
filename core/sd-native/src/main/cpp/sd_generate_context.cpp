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

} // namespace

namespace mca_sd_bridge_internal {

std::string sd_generate_context(SdGenerateContext &state) {
    auto &model_path = state.model_path;
    auto &bundle_root = state.bundle_root;
    auto &params = state.params;
    auto &contract = state.contract;
    auto &canonical_lora_paths = state.canonical_lora_paths;
    auto &validated_textual_inversions = state.validated_textual_inversions;
    auto &paths = state.paths;
    auto &execution_asset_binding = state.execution_asset_binding;
    auto &has_execution_asset_binding = state.has_execution_asset_binding;
    auto &selected_components_json = state.selected_components_json;
    auto &split_qwen_image_21 = state.split_qwen_image_21;
    auto &ctx = state.ctx;
    auto &actual_qwen_image_21 = state.actual_qwen_image_21;
    auto &actual_model_version = state.actual_model_version;
    auto &native_loras = state.native_loras;
    auto &qwen_condition = state.qwen_condition;
    auto &gen = state.gen;
        set_progress("initializing", "checking model bundle", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        std::string component_error;
        if (!resolve_component_paths(
                model_path,
                bundle_root,
                contract.family,
                params,
                paths,
                component_error)) {
            set_progress("failed", component_error, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return json({
                    {"ok", false},
                    {"errorCode", "COMPONENT_SELECTION_INVALID"},
                    {"error", component_error},
                    {"componentSelection", {
                            {"mode", optional_string(
                                    params,
                                    "componentSelectionMode",
                                    "compatibility_inference")},
                            {"fallback", false}
                    }}
            }).dump();
        }
        std::string cn_code, cn_msg;
        if (!validate_controlnet_component(task_uses_control_image(contract), paths.control_net, cn_code, cn_msg)) {
            return runtime_failure(cn_code, cn_msg).dump();
        }
        has_execution_asset_binding = !validated_textual_inversions.empty();
        if (has_execution_asset_binding) {
            std::string asset_error;
            if (!mca::image::execution_assets::parse(
                    params,
                    "STABLE_DIFFUSION_CPP",
                    execution_asset_binding,
                    asset_error) ||
                !execution_assets_match_consumers(paths, execution_asset_binding) ||
                !std::all_of(
                    validated_textual_inversions.begin(),
                    validated_textual_inversions.end(),
                    [&execution_asset_binding](const auto& artifact) {
                        return artifact.tokenizer_fingerprint ==
                            execution_asset_binding.composite_sha256;
                    }) ||
                !mca::image::execution_assets::verify_initial(
                    execution_asset_binding,
                    [] { return g_cancel_requested.load(std::memory_order_relaxed); },
                    asset_error)) {
                if (g_cancel_requested.load(std::memory_order_relaxed) || asset_error == "cancelled") {
                    return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
                }
                if (asset_error.empty()) {
                    asset_error = "execution asset descriptors do not match the resolved native consumers";
                }
                return runtime_failure(
                    "TEXTUAL_INVERSION_EXECUTION_ASSET_MISMATCH",
                    asset_error).dump();
            }
        }
        selected_components_json = component_selection_json(paths);
        set_progress_component_selection(selected_components_json);
        split_qwen_image_21 = contract.variant == "QWEN_IMAGE_21";
        ComponentPaths text_encoder_paths = paths;
        ComponentPaths diffusion_vae_paths = paths;
        if (split_qwen_image_21) {
            // The first context owns only the Qwen3-VL text encoder. The second
            // context owns the Qwen Image DiT/VAE graph, so their parameter
            // buffers never overlap during initialization.
            text_encoder_paths.model.clear(); text_encoder_paths.diffusion.clear();
            text_encoder_paths.high_noise_diffusion.clear(); text_encoder_paths.vae.clear();
            text_encoder_paths.clip_l.clear(); text_encoder_paths.clip_g.clear();
            text_encoder_paths.clip_vision.clear(); text_encoder_paths.t5xxl.clear();
            text_encoder_paths.llm_vision.clear(); text_encoder_paths.embeddings_connectors.clear();
            text_encoder_paths.control_net.clear();

            diffusion_vae_paths.model.clear(); diffusion_vae_paths.high_noise_diffusion.clear();
            diffusion_vae_paths.clip_l.clear(); diffusion_vae_paths.clip_g.clear();
            diffusion_vae_paths.clip_vision.clear(); diffusion_vae_paths.t5xxl.clear();
            diffusion_vae_paths.llm.clear(); diffusion_vae_paths.llm_vision.clear();
            diffusion_vae_paths.embeddings_connectors.clear(); diffusion_vae_paths.control_net.clear();
        }
        std::string ctx_key = make_context_key(
                split_qwen_image_21 ? text_encoder_paths : paths,
                validated_textual_inversions,
                contract.threads,
                has_execution_asset_binding
                    ? execution_asset_binding.composite_sha256
                    : std::string(),
                split_qwen_image_21
                    ? SD_QWEN_IMAGE_21_PHASE_TEXT_ENCODER
                    : SD_QWEN_IMAGE_21_PHASE_FULL);
        set_progress("loading", "loading stable-diffusion.cpp context", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
        ctx = ensure_context(
                split_qwen_image_21 ? text_encoder_paths : paths,
                validated_textual_inversions,
                ctx_key,
                contract.threads,
                split_qwen_image_21,
                split_qwen_image_21
                    ? SD_QWEN_IMAGE_21_PHASE_TEXT_ENCODER
                    : SD_QWEN_IMAGE_21_PHASE_FULL);
        if (ctx == nullptr) {
            set_progress("failed", g_last_error, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return runtime_failure("NATIVE_CONTEXT_LOAD_FAILED", g_last_error).dump();
        }
        // Stage-unloaded contexts cannot be reused for another request. Release on
        // every exit, including cancellation/contract failures, after evidence is read.
        state.stage_context_release_enabled = (contract.variant == "QWEN_IMAGE_21");
        actual_qwen_image_21 = sd_ctx_is_qwen_image_2_1(ctx);
        actual_model_version = sd_ctx_model_version_name(ctx);
        if (contract.variant == "QWEN_IMAGE_21" && !actual_qwen_image_21) {
            execution_mismatch("variant", "QWEN_IMAGE_21 requested but checkpoint tensor schema is " + actual_model_version);
        }
        if (actual_qwen_image_21 && contract.task_mode != "text_to_image") {
            unsupported_contract("taskMode", "this Qwen Image 2.1 runtime provides text-to-image; reference conditioning requires the deepstack vision implementation");
        }
        if (actual_qwen_image_21 && (contract.width % 32 != 0 || contract.height % 32 != 0)) {
            invalid_contract("width,height", "Qwen Image 2.1 tensor geometry requires multiples of 32");
        }

        native_loras =
                mca::sd::build_native_loras(contract.loras, canonical_lora_paths);
        // contract: gen.loras = native_loras.empty() ? nullptr : native_loras.data();
        // contract verification: LORA_NATIVE_APPLY_INCOMPLETE, lora_applied_tensor_count == 0u

        if (split_qwen_image_21) {
            set_progress("encoding", "encoding prompt with Qwen3-VL", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            sd_img_gen_params_t prompt_generation;
            sd_img_gen_params_init(&prompt_generation);
            prompt_generation.prompt = contract.prompt.c_str();
            prompt_generation.negative_prompt = contract.use_cfg
                    ? contract.negative_prompt.c_str() : nullptr;
            prompt_generation.width = contract.width;
            prompt_generation.height = contract.height;
            prompt_generation.seed = contract.seed;
            prompt_generation.clip_skip = contract.clip_skip;
            prompt_generation.loras = native_loras.empty() ? nullptr : native_loras.data();
            prompt_generation.lora_count = static_cast<uint32_t>(native_loras.size());
            prompt_generation.sample_params.guidance.txt_cfg =
                    static_cast<float>(contract.cfg_scale);
            prompt_generation.sample_params.guidance.distilled_guidance =
                    static_cast<float>(contract.distilled_guidance);
            qwen_condition = sd_qwen_image_21_encode_prompt(ctx, &prompt_generation);
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            if (qwen_condition == nullptr) {
                return runtime_failure(
                        "QWEN_TEXT_ENCODER_FAILED",
                        "Qwen3-VL could not encode the positive and negative prompt").dump();
            }
            mark_generation_stage(kStageQwenTextConditionEncoded);

            // Release the text encoder before loading the multi-gigabyte DiT/VAE
            // context. The opaque condition owns host tensors and remains valid
            // after this context is destroyed.
            if (g_ctx != nullptr) {
                free_sd_ctx(g_ctx);
                g_ctx = nullptr;
                g_ctx_key.clear();
            }
            mark_generation_stage(kStageQwenTextContextReleased);
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }

            ctx_key = make_context_key(
                    diffusion_vae_paths,
                    validated_textual_inversions,
                    contract.threads,
                    has_execution_asset_binding
                        ? execution_asset_binding.composite_sha256
                        : std::string(),
                    SD_QWEN_IMAGE_21_PHASE_DIFFUSION_VAE);
            set_progress("loading", "loading Qwen Image DiT and VAE context", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            ctx = ensure_context(
                    diffusion_vae_paths,
                    validated_textual_inversions,
                    ctx_key,
                    contract.threads,
                    true,
                    SD_QWEN_IMAGE_21_PHASE_DIFFUSION_VAE);
            if (ctx == nullptr) {
                return runtime_failure("NATIVE_CONTEXT_LOAD_FAILED", g_last_error).dump();
            }
            actual_qwen_image_21 = sd_ctx_is_qwen_image_2_1(ctx);
            actual_model_version = sd_ctx_model_version_name(ctx);
            if (!actual_qwen_image_21) {
                execution_mismatch(
                        "variant",
                        "QWEN_IMAGE_21 diffusion/VAE phase resolved checkpoint schema as " +
                        actual_model_version);
            }
            mark_generation_stage(kStageQwenDiffusionContextReady);
        }

    return {};
}

} // namespace mca_sd_bridge_internal
