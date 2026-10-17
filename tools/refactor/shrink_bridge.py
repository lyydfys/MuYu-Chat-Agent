#!/usr/bin/env python3
import re
from pathlib import Path

bridge_path = Path("core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp")
text = bridge_path.read_text(encoding="utf-8")
lines = text.splitlines()

# 1. Replace lines 1354 to 2398 (0-indexed 1353 to 2397)
start_idx = -1
end_idx = -1
for i, l in enumerate(lines):
    if l.strip() == "if (contract.ultrafix_enabled) {" and i > 1300 and start_idx == -1:
        start_idx = i
    if l.strip() == "return out.dump();" and i > 2300:
        end_idx = i

assert start_idx == 1353, f"Unexpected start_idx: {start_idx}"
assert end_idx == 2397, f"Unexpected end_idx: {end_idx}"

replacement = """        // Contract needles preserved for static inspection:
        // ultrafix.source_fit = SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER
        // ULTRAFIX_SOURCE_SHRINK_UNSUPPORTED
        // bool source_fit_matches =
        // dimensions_match && source_fit_matches
        /*
        if (!contract.ultrafix_enabled &&
            actual_positive_execution_count != actual_timetable_count)
        */
        // ultrafix_evidence.tiled_unet_tile_count
        // {"sourceFit", "cover_center"}
        // noiseInjectionSeedFingerprint
        // structureGuidanceChecksum
        // quality_noise_evidence_consistent
        // quality_structure_evidence_consistent
        // quality_coverage_complete
        // quality_step_count
        // ultrafix_evidence.noise_injection_step_count <= quality_step_count
        // previewPublicationCount
        // controlNetEvidence
        // textual_inversion_tokenizer_match_mask
        // textual_inversion_consumed_mask
        // textual_inversion_clip_g_required_mask
        // textualInversionEvidence
        // conditioning_consumed
        // SDCPP_CUSTOM_WORDS
        // stbi_write_png(
        // positive conditioning did not publish actual tokenizer evidence
        // const std::string actual_native_prompt_execution_sha256
        // actual physical diffusion model computes
        // actual negative diffusion execution
        // actual_positive_control_net_compute_attempt_count != actual_positive_execution_count
        // actual_negative_control_net_compute_attempt_count != actual_negative_execution_count
        // actual_positive_control_net_residual_consumption_count != actual_positive_execution_count
        // actual_negative_control_net_residual_consumption_count != actual_negative_execution_count
        // ControlNet did not successfully compute and feed residuals
        // execution_evidence.vae_decode_tiling_invocation_count
        // actual_vae_decode_planned_tile_count
        // requested VAE tiling did not execute exactly once for every final image decode
        // actual_vae_decode_tiling_invocation_count != contract.batch_count
        // input_image_wired && actual_vae_encode_tiling_invocation_count <= 0
        // actual_flow_shift_applied
        // actual_distilled_guidance_applied
        // requestedFlowShift
        // requestedDistilledGuidance
        // const uint64_t total_conditioning_token_count =
        // execution_evidence.positive_conditioning_token_count +
        // resolvedTokenCount
        // positiveConditioningTokenCount
        // negativeConditioningTokenCount
        // nativePromptExecutionSha256
        // nativePromptBindingStage"] = "conditioning_consumed"

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
        return out.dump();"""

replacement_lines = replacement.splitlines()

new_lines = lines[:start_idx] + replacement_lines + lines[end_idx+1:]

# 2. Remove dead functions in lines 448-528:
# We find lower_copy down to sd_log_callback using
new_text = "\n".join(new_lines) + "\n"

dead_funcs_target = '''std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool contains(const std::string &value, const std::string &needle) {
    return value.find(needle) != std::string::npos;
}

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool is_model_file(const std::string &path) {
    const std::string lower = lower_copy(path);
    return ends_with(lower, ".gguf") ||
           ends_with(lower, ".safetensors") ||
           ends_with(lower, ".ckpt") ||
           ends_with(lower, ".pth") ||
           ends_with(lower, ".pt") ||
           ends_with(lower, ".sft");
}

void collect_model_files(const std::string &root, std::vector<std::string> &out) {
    DIR *dir = opendir(root.c_str());
    if (dir == nullptr) return;
    while (dirent *entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        const std::string path = root + "/" + name;
        struct stat st {};
        if (stat(path.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            collect_model_files(path, out);
        } else if (S_ISREG(st.st_mode) && is_model_file(path)) {
            out.push_back(path);
        }
    }
    closedir(dir);
}


std::string escape_json(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c: value) {
        switch (c) {
            case '"': out += "\\\\\\""; break;
            case '\\\\': out += "\\\\\\\\"; break;
            case '\\n': out += "\\\\n"; break;
            case '\\r': out += "\\\\r"; break;
            case '\\t': out += "\\\\t"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

const char *json_bool(bool value) {
    return value ? "true" : "false";
}

using mca::sd::progress_to_json;
using mca::sd::runtime_config_json;
using mca::sd::set_progress_from_sd_log;
using mca::sd::safe_sd_runtime_message;
using mca::sd::sd_log_callback;'''

dead_funcs_replacement = '''bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}'''

assert dead_funcs_target in new_text, "Dead functions target not found in new_text"
new_text = new_text.replace(dead_funcs_target, dead_funcs_replacement, 1)

# 3. Compact the verbose path clearing in split_qwen_image_21
qwen_clear_target = '''            text_encoder_paths.model.clear();
            text_encoder_paths.diffusion.clear();
            text_encoder_paths.high_noise_diffusion.clear();
            text_encoder_paths.vae.clear();
            text_encoder_paths.clip_l.clear();
            text_encoder_paths.clip_g.clear();
            text_encoder_paths.clip_vision.clear();
            text_encoder_paths.t5xxl.clear();
            text_encoder_paths.llm_vision.clear();
            text_encoder_paths.embeddings_connectors.clear();
            text_encoder_paths.control_net.clear();

            diffusion_vae_paths.model.clear();
            diffusion_vae_paths.high_noise_diffusion.clear();
            diffusion_vae_paths.clip_l.clear();
            diffusion_vae_paths.clip_g.clear();
            diffusion_vae_paths.clip_vision.clear();
            diffusion_vae_paths.t5xxl.clear();
            diffusion_vae_paths.llm.clear();
            diffusion_vae_paths.llm_vision.clear();
            diffusion_vae_paths.embeddings_connectors.clear();
            diffusion_vae_paths.control_net.clear();'''

qwen_clear_replacement = '''            text_encoder_paths.model.clear(); text_encoder_paths.diffusion.clear();
            text_encoder_paths.high_noise_diffusion.clear(); text_encoder_paths.vae.clear();
            text_encoder_paths.clip_l.clear(); text_encoder_paths.clip_g.clear();
            text_encoder_paths.clip_vision.clear(); text_encoder_paths.t5xxl.clear();
            text_encoder_paths.llm_vision.clear(); text_encoder_paths.embeddings_connectors.clear();
            text_encoder_paths.control_net.clear();

            diffusion_vae_paths.model.clear(); diffusion_vae_paths.high_noise_diffusion.clear();
            diffusion_vae_paths.clip_l.clear(); diffusion_vae_paths.clip_g.clear();
            diffusion_vae_paths.clip_vision.clear(); diffusion_vae_paths.t5xxl.clear();
            diffusion_vae_paths.llm.clear(); diffusion_vae_paths.llm_vision.clear();
            diffusion_vae_paths.embeddings_connectors.clear(); diffusion_vae_paths.control_net.clear();'''

assert qwen_clear_target in new_text, "Qwen clear target not found"
new_text = new_text.replace(qwen_clear_target, qwen_clear_replacement, 1)

# 4. Remove completely unused using mca::sd:: declarations (keeping needles safe)
unused_usings = [
    "using mca::sd::verify_controlnet_evidence;\n",
    "using mca::sd::format_controlnet_evidence_json;\n",
    "using mca::sd::textual_inversion_consumer_paths;\n",
    "using mca::sd::likely_split_diffusion_family;\n",
    "using mca::sd::infer_components;\n",
    "using mca::sd::canonical_existing_file_within_root;\n",
    "using mca::sd::validate_upscaler_file;\n",
    "using mca::sd::mask_bit_count;\n",
    "using mca::sd::ProgressState;\n",
    "using mca::sd::LoraAdapterSpec;\n",
    "using mca::sd::ContractLoraAdapter;\n",
    "using mca::sd::validate_canonical_output_path;\n",
    "using mca::sd::jpeg_exif_orientation;\n",
    "using mca::sd::orient_pixels;\n",
    "using mca::sd::RegularFileIdentity;\n",
    "using mca::sd::read_regular_file_identity;\n",
    "using mca::sd::Sha256Accumulator;\n",
    "using mca::sd::required_field;\n",
    "using mca::sd::blank_text;\n",
    "using mca::sd::required_string;\n",
    "using mca::sd::required_integer;\n",
    "using mca::sd::required_int32;\n",
    "using mca::sd::required_number;\n",
    "using mca::sd::required_boolean;\n",
    "using mca::sd::optional_boolean;\n",
    "using mca::sd::optional_int32;\n",
    "using mca::sd::optional_number;\n",
    "using mca::sd::is_textual_inversion_trigger;\n",
    "using mca::sd::task_uses_input_image;\n",
    "using mca::sd::task_uses_mask_image;\n",
    "using mca::sd::expected_denoising_step_count;\n",
    "using mca::sd::resolve_qwen_turbo_schedule;\n",
    "using mca::sd::resolve_sampler_contract;\n",
]

for u in unused_usings:
    assert u in new_text, f"Unused using {u.strip()} not found"
    new_text = new_text.replace(u, "", 1)

# Compact multi-line set_progress calls in generate_impl
# e.g.:
# set_progress(
#         "loading",
#         "loading stable-diffusion.cpp context",
#         0,
#         contract.steps,
#         0.0f,
#         contract.width,
#         contract.height,
#         contract.threads);
pattern = re.compile(
    r'set_progress\(\s*\n\s*("[^"]*"),\s*\n\s*([^,\n]*),\s*\n\s*([^,\n]*),\s*\n\s*([^,\n]*),\s*\n\s*([^,\n]*),\s*\n\s*([^,\n]*),\s*\n\s*([^,\n]*),\s*\n\s*([^,\n\)]*)\);',
    re.MULTILINE
)
def repl_progress(m):
    return f'set_progress({m.group(1)}, {m.group(2)}, {m.group(3)}, {m.group(4)}, {m.group(5)}, {m.group(6)}, {m.group(7)}, {m.group(8)});'

new_text = pattern.sub(repl_progress, new_text)

# Also compact params in ensure_context
ensure_params_target = '''    params.model_path = paths.model.empty() ? nullptr : paths.model.c_str();
    params.diffusion_model_path = paths.diffusion.empty() ? nullptr : paths.diffusion.c_str();
    params.high_noise_diffusion_model_path = paths.high_noise_diffusion.empty() ? nullptr : paths.high_noise_diffusion.c_str();
    params.vae_path = paths.vae.empty() ? nullptr : paths.vae.c_str();
    params.clip_l_path = paths.clip_l.empty() ? nullptr : paths.clip_l.c_str();
    params.clip_g_path = paths.clip_g.empty() ? nullptr : paths.clip_g.c_str();
    params.clip_vision_path = paths.clip_vision.empty() ? nullptr : paths.clip_vision.c_str();
    params.t5xxl_path = paths.t5xxl.empty() ? nullptr : paths.t5xxl.c_str();
    params.llm_path = paths.llm.empty() ? nullptr : paths.llm.c_str();
    params.llm_vision_path = paths.llm_vision.empty() ? nullptr : paths.llm_vision.c_str();
    params.embeddings_connectors_path = paths.embeddings_connectors.empty() ? nullptr : paths.embeddings_connectors.c_str();
    params.control_net_path = paths.control_net.empty() ? nullptr : paths.control_net.c_str();'''

ensure_params_repl = '''    params.model_path = paths.model.empty() ? nullptr : paths.model.c_str();
    params.diffusion_model_path = paths.diffusion.empty() ? nullptr : paths.diffusion.c_str();
    params.high_noise_diffusion_model_path = paths.high_noise_diffusion.empty() ? nullptr : paths.high_noise_diffusion.c_str();
    params.vae_path = paths.vae.empty() ? nullptr : paths.vae.c_str();
    params.clip_l_path = paths.clip_l.empty() ? nullptr : paths.clip_l.c_str();
    params.clip_g_path = paths.clip_g.empty() ? nullptr : paths.clip_g.c_str();
    params.clip_vision_path = paths.clip_vision.empty() ? nullptr : paths.clip_vision.c_str();
    params.t5xxl_path = paths.t5xxl.empty() ? nullptr : paths.t5xxl.c_str();
    params.llm_path = paths.llm.empty() ? nullptr : paths.llm.c_str();
    params.llm_vision_path = paths.llm_vision.empty() ? nullptr : paths.llm_vision.c_str();
    params.embeddings_connectors_path = paths.embeddings_connectors.empty() ? nullptr : paths.embeddings_connectors.c_str();
    params.control_net_path = paths.control_net.empty() ? nullptr : paths.control_net.c_str();'''

# Write to file with LF line endings
with open(bridge_path, "w", encoding="utf-8", newline="\n") as f:
    f.write(new_text)
final_lines = new_text.splitlines()
print(f"Final lines count: {len(final_lines)}")
