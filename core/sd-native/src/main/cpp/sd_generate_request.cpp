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

#include <new>
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

std::string sd_generate_request(SdGenerateContext &state) {
    auto &model_path = state.model_path;
    auto &params_json = state.params_json;
    auto &output_path = state.output_path;
    auto &params = state.params;
    auto &contract = state.contract;
    auto &output_paths = state.output_paths;
    auto &input_image = state.input_image;
    auto &mask_image = state.mask_image;
    auto &control_image = state.control_image;
    auto &canonical_lora_paths = state.canonical_lora_paths;
    auto &validated_textual_inversions = state.validated_textual_inversions;
        if (!file_exists(model_path)) {
            return runtime_failure("MODEL_FILE_MISSING", "model file does not exist").dump();
        }
        try {
            params = json::parse(params_json);
        } catch (const json::exception &error) {
            return contract_failure_json(ContractError(
                    "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                    "params",
                    std::string("native params are not valid JSON: ") + error.what())).dump();
        }
        // R7-C6: reconstruct in place; a move-assignment would add new weak exports to the
        // shared object (the baseline only ever constructed this value once).
        state.contract.~StableDiffusionExecutionContract();
        new (&state.contract) StableDiffusionExecutionContract(parse_execution_contract(params));
        std::string output_error;
        if (!build_output_paths(
                output_path, contract.batch_count, output_paths, output_error)) {
            return runtime_failure("OUTPUT_PATH_INVALID", output_error).dump();
        }
        mark_generation_stage(kStageContractValidated);

        std::string input_error;
        if (!contract.input_image_path.empty() && !load_canonical_input_image(
                contract.input_image_path,
                contract.input_image_sha256,
                3,
                input_image,
                input_error)) {
            return runtime_failure("INPUT_IMAGE_INVALID", input_error).dump();
        }
        if (contract.ultrafix_enabled &&
            (input_image.width > static_cast<uint32_t>(contract.ultrafix_target_width) ||
             input_image.height > static_cast<uint32_t>(contract.ultrafix_target_height))) {
            return runtime_failure(
                    "ULTRAFIX_SOURCE_SHRINK_UNSUPPORTED",
                    "UltraFix target width and height must each be at least the oriented source size").dump();
        }
        if (!input_image.pixels.empty()) mark_generation_stage(kStageInputImageDecoded);
        std::string mask_code, mask_msg;
        if (!load_and_validate_inpaint_mask(
                contract,
                input_image,
                mask_image,
                mask_code,
                mask_msg)) {
            return runtime_failure(mask_code, mask_msg).dump();
        }
        if (!mask_image.pixels.empty()) mark_generation_stage(kStageMaskImageDecoded);
        if (!contract.control_image_path.empty() && !load_canonical_input_image(
                contract.control_image_path,
                contract.control_image_sha256,
                3,
                control_image,
                input_error)) {
            return runtime_failure("CONTROL_IMAGE_INVALID", input_error).dump();
        }
        if (!control_image.pixels.empty()) mark_generation_stage(kStageControlImageDecoded);
        std::string lora_error;
        if (!mca::sd::validate_lora_files(
                contract.lora_root_path,
                contract.loras,
                canonical_lora_paths,
                lora_error)) {
            return runtime_failure("LORA_FILE_INVALID", lora_error).dump();
        }
        if (!canonical_lora_paths.empty()) mark_generation_stage(kStageLoraValidated);

        // R7-C6: copy-construct in place so the baseline weak copy-ctor exports stay identical.
        state.validated_textual_inversions.~vector();
        new (&state.validated_textual_inversions) std::vector<ContractTextualInversion>(
                contract.textual_inversions);
        if (!validated_textual_inversions.empty() && !sha256_implementation_ready()) {
            return runtime_failure(
                    "TEXTUAL_INVERSION_HASH_UNAVAILABLE",
                    "native SHA-256 self-test failed before textual inversion binding").dump();
        }
        for (const auto &artifact: validated_textual_inversions) {
            if (textual_inversion_binding_fingerprint(artifact) !=
                artifact.binding_fingerprint) {
                execution_mismatch(
                        "textualInversions.bindingFingerprint",
                        "artifact binding fingerprint does not match its canonical model/tokenizer binding");
            }
        }
        if (!validated_textual_inversions.empty() &&
            textual_inversion_selection_fingerprint(validated_textual_inversions) !=
                    contract.textual_inversion_binding_fingerprint) {
            execution_mismatch(
                    "textualInversionBindingFingerprint",
                    "selection binding fingerprint does not match the canonical artifact set");
        }
        for (auto &artifact: validated_textual_inversions) {
            std::string canonical_path;
            std::string actual_sha256;
            std::string textual_error;
            if (!validate_textual_inversion_file(
                    contract.textual_inversion_root_path,
                    artifact,
                    canonical_path,
                    actual_sha256,
                    textual_error,
                    [&]() { return g_cancel_requested.load(std::memory_order_relaxed); })) {
                if (g_cancel_requested.load(std::memory_order_relaxed)) {
                    return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
                }
                return runtime_failure("TEXTUAL_INVERSION_FILE_INVALID", textual_error).dump();
            }
            artifact.path = std::move(canonical_path);
            artifact.sha256 = std::move(actual_sha256);
        }
        if (!validated_textual_inversions.empty()) {
            mark_generation_stage(kStageTextualInversionValidated);
        }

    return {};
}

} // namespace mca_sd_bridge_internal
