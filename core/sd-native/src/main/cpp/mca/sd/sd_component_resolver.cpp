#include "mca/sd/sd_component_resolver.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <dirent.h>
#include <limits.h>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

#include "mca/sd/sd_contract_parser.hpp"

namespace mca {
namespace sd {

namespace {

inline const char *json_bool_str(bool value) {
    return value ? "true" : "false";
}

std::string lower_copy_str(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with_str(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool contains_str(const std::string &value, const std::string &needle) {
    return value.find(needle) != std::string::npos;
}

bool file_exists_local(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists_local(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool is_model_file_local(const std::string &path) {
    const std::string lower = lower_copy_str(path);
    return ends_with_str(lower, ".gguf") ||
           ends_with_str(lower, ".safetensors") ||
           ends_with_str(lower, ".ckpt") ||
           ends_with_str(lower, ".pth") ||
           ends_with_str(lower, ".pt") ||
           ends_with_str(lower, ".sft");
}

void collect_model_files_local(const std::string &root, std::vector<std::string> &out) {
    DIR *dir = opendir(root.c_str());
    if (dir == nullptr) return;
    while (dirent *entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        const std::string path = root + "/" + name;
        struct stat st {};
        if (stat(path.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            collect_model_files_local(path, out);
        } else if (S_ISREG(st.st_mode) && is_model_file_local(path)) {
            out.push_back(path);
        }
    }
    closedir(dir);
}

std::string escape_json_str(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c: value) {
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
                break;
        }
    }
    return out;
}

}  // namespace

std::vector<std::string> textual_inversion_consumer_paths(const ComponentPaths& paths) {
    std::vector<std::string> result;
    const auto append = [&result](const std::string& path) {
        if (!path.empty() && std::find(result.begin(), result.end(), path) == result.end()) {
            result.push_back(path);
        }
    };
    append(paths.primary_path);
    append(paths.high_noise_diffusion);
    append(paths.clip_l);
    append(paths.clip_g);
    append(paths.t5xxl);
    append(paths.llm);
    append(paths.llm_vision);
    append(paths.embeddings_connectors);
    std::sort(result.begin(), result.end());
    return result;
}

bool execution_assets_match_consumers(
        const ComponentPaths& paths,
        const mca::image::execution_assets::Binding& binding) {
    auto expected = textual_inversion_consumer_paths(paths);
    std::vector<std::string> actual;
    actual.reserve(binding.assets.size());
    for (const auto& asset : binding.assets) actual.push_back(asset.path);
    std::sort(actual.begin(), actual.end());
    return actual == expected;
}

bool likely_split_diffusion_family(const std::string &family, const std::string &path) {
    const std::string key = lower_copy_str(family + " " + path);
    // Anima needs the diffusion slot; match the family or the anima-preview
    // filenames, never the "animagine" SDXL checkpoints.
    const bool anima_family = lower_copy_str(family).find("anima") != std::string::npos &&
                              lower_copy_str(family).find("animagine") == std::string::npos;
    return anima_family ||
           contains_str(key, "anima-preview") ||
           contains_str(key, "anima_preview") ||
           contains_str(key, "z_image") ||
           contains_str(key, "z-image") ||
           contains_str(key, "qwen_image") ||
           contains_str(key, "qwen-image") ||
           contains_str(key, "glm_image") ||
           contains_str(key, "glm-image") ||
           contains_str(key, "dreamlite") ||
           contains_str(key, "flux") ||
           contains_str(key, "wan") ||
           contains_str(key, "chroma") ||
           contains_str(key, "ernie") ||
           contains_str(key, "longcat");
}

ComponentPaths infer_components(const std::string &model_path, const std::string &bundle_root, const std::string &family) {
    ComponentPaths paths;
    paths.selection_mode = "compatibility_inference";
    paths.selection_fallback = true;
    paths.bundle_root = bundle_root;
    if (likely_split_diffusion_family(family, model_path)) {
        paths.diffusion = model_path;
        paths.primary_slot = "diffusion";
    } else {
        paths.model = model_path;
        paths.primary_slot = "model";
    }
    paths.primary_path = model_path;

    std::vector<std::string> files;
    if (dir_exists_local(bundle_root)) {
        collect_model_files_local(bundle_root, files);
    }
    std::sort(files.begin(), files.end());
    for (const std::string &path: files) {
        const std::string lower = lower_copy_str(path);
        if (path == model_path) continue;
        if (contains_str(lower, "high") && contains_str(lower, "noise") && contains_str(lower, "diffusion")) {
            paths.high_noise_diffusion = path;
        } else if (contains_str(lower, "clip_vision") || contains_str(lower, "clip-vision") || contains_str(lower, "vision_h")) {
            paths.clip_vision = path;
        } else if (contains_str(lower, "clip_g") || contains_str(lower, "clip-g")) {
            paths.clip_g = path;
        } else if (contains_str(lower, "clip_l") || contains_str(lower, "clip-l")) {
            paths.clip_l = path;
        } else if (contains_str(lower, "t5xxl") || contains_str(lower, "umt5") || contains_str(lower, "t5-xxl")) {
            paths.t5xxl = path;
        } else if (contains_str(lower, "vae") ||
                   ends_with_str(lower, "ae.sft") ||
                   ends_with_str(lower, "ae.safetensors") ||
                   ends_with_str(lower, "_ae.safetensors") ||
                   ends_with_str(lower, "-ae.safetensors") ||
                   ends_with_str(lower, "_ae.gguf") ||
                   ends_with_str(lower, "-ae.gguf") ||
                   contains_str(lower, "/ae.")) {
            paths.vae = path;
        } else if (contains_str(lower, "embeddings") && contains_str(lower, "connector")) {
            paths.embeddings_connectors = path;
        } else if (contains_str(lower, "controlnet") || contains_str(lower, "control_net")) {
            paths.control_net = path;
        } else if (contains_str(lower, "llm_vision") || contains_str(lower, "llm-vision")) {
            paths.llm_vision = path;
        } else if (contains_str(lower, "qwen") || contains_str(lower, "mistral") || contains_str(lower, "gemma") || contains_str(lower, "llm")) {
            paths.llm = path;
        } else if (paths.diffusion.empty() &&
                   (contains_str(lower, "diffusion") || contains_str(lower, "unet") || contains_str(lower, "dit"))) {
            paths.diffusion = path;
        }
    }
    if (paths.diffusion.empty() && paths.model.empty()) {
        paths.model = model_path;
    }
    if (!paths.llm.empty()) {
        paths.text_encoder_path = paths.llm;
        paths.text_encoder_slot = "llm";
    } else if (!paths.t5xxl.empty()) {
        paths.text_encoder_path = paths.t5xxl;
        paths.text_encoder_slot = "t5xxl";
    } else if (!paths.clip_l.empty()) {
        paths.text_encoder_path = paths.clip_l;
        paths.text_encoder_slot = "clip_l";
    }
    return paths;
}

bool canonical_existing_file_within_root(const std::string &raw_path,
                                         const std::string &raw_root,
                                         std::string &canonical_path,
                                         std::string &error) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr || !dir_exists_local(root_buffer)) {
        error = "component bundle root does not exist";
        return false;
    }
    if (raw_path.empty() || realpath(raw_path.c_str(), path_buffer) == nullptr || !file_exists_local(path_buffer)) {
        error = "component file does not exist: " + raw_path;
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path.size() <= root.size() || path.compare(0, root.size(), root) != 0 || path[root.size()] != '/') {
        error = "component path escapes its bundle root: " + raw_path;
        return false;
    }
    canonical_path = path;
    return true;
}

bool validate_upscaler_file(const std::string &raw_root,
                            const std::string &raw_path,
                            uint64_t expected_size,
                            std::string &canonical_path,
                            std::string &error) {
    char root_buffer[PATH_MAX] = {};
    char path_buffer[PATH_MAX] = {};
    if (raw_root.empty() || realpath(raw_root.c_str(), root_buffer) == nullptr ||
        !dir_exists_local(root_buffer)) {
        error = "upscaler root does not exist";
        return false;
    }
    struct stat link_stat {};
    if (raw_path.empty() || lstat(raw_path.c_str(), &link_stat) != 0 ||
        !S_ISREG(link_stat.st_mode)) {
        error = "upscaler model must be a regular non-symlink file";
        return false;
    }
    if (link_stat.st_size < 0 || static_cast<uint64_t>(link_stat.st_size) != expected_size) {
        error = "upscaler model size differs from the worker-verified contract";
        return false;
    }
    if (realpath(raw_path.c_str(), path_buffer) == nullptr || !file_exists_local(path_buffer)) {
        error = "upscaler model does not exist";
        return false;
    }
    const std::string root(root_buffer);
    const std::string path(path_buffer);
    if (path.size() <= root.size() || path.compare(0, root.size(), root) != 0 || path[root.size()] != '/') {
        error = "upscaler model escapes its bundle root: " + raw_path;
        return false;
    }
    canonical_path = path;
    return true;
}

uint32_t mask_bit_count(uint64_t mask) {
    uint32_t count = 0u;
    while (mask != 0u) {
        mask &= mask - 1u;
        ++count;
    }
    return count;
}

bool validate_textual_inversion_execution_evidence(
        const sd_image_execution_evidence_t &evidence,
        size_t selected_count,
        std::string &failure_code,
        std::string &failure_message) {
    const uint64_t expected_mask = selected_count == 0u
            ? 0u
            : (uint64_t{1} << selected_count) - 1u;
    const auto fail = [&](const char *code, const char *message) {
        failure_code = code;
        failure_message = message;
        return false;
    };
    if (evidence.textual_inversion_failure_code !=
        SD_TEXTUAL_INVERSION_FAILURE_NONE) {
        switch (evidence.textual_inversion_failure_code) {
            case SD_TEXTUAL_INVERSION_FAILURE_FILE_LOAD:
                return fail(
                        "TEXTUAL_INVERSION_NATIVE_FILE_LOAD_FAILED",
                        "stable-diffusion.cpp could not open the selected textual inversion file");
            case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_LOAD:
                return fail(
                        "TEXTUAL_INVERSION_NATIVE_TENSOR_LOAD_FAILED",
                        "stable-diffusion.cpp could not load the selected textual inversion tensors");
            case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_SCHEMA:
                return fail(
                        "TEXTUAL_INVERSION_NATIVE_TENSOR_SCHEMA_INVALID",
                        "the selected textual inversion tensor schema is incompatible with the loaded CLIP graph");
            case SD_TEXTUAL_INVERSION_FAILURE_CLIP_PAIR_MISMATCH:
                return fail(
                        "TEXTUAL_INVERSION_NATIVE_CLIP_PAIR_MISMATCH",
                        "the selected SDXL textual inversion does not contain a matching CLIP-L and CLIP-G pair");
            case SD_TEXTUAL_INVERSION_FAILURE_CONDITIONING:
                return fail(
                        "TEXTUAL_INVERSION_NATIVE_CONDITIONING_FAILED",
                        "stable-diffusion.cpp could not consume the selected textual inversion during conditioning");
            default:
                return fail(
                        "TEXTUAL_INVERSION_NATIVE_FAILURE_UNKNOWN",
                        "stable-diffusion.cpp reported an unknown textual inversion failure");
        }
    }
    if (evidence.textual_inversion_requested_mask != expected_mask ||
        evidence.textual_inversion_requested_count != selected_count) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_REQUEST_MISMATCH",
                "stable-diffusion.cpp did not receive the complete textual inversion selection");
    }
    if (evidence.textual_inversion_tokenizer_match_mask != expected_mask ||
        evidence.textual_inversion_tokenizer_match_count != selected_count) {
        return fail(
                "TEXTUAL_INVERSION_TRIGGER_NOT_MATCHED",
                "every selected textual inversion trigger must occur as a complete prompt token");
    }
    if (evidence.textual_inversion_load_attempt_mask != expected_mask ||
        evidence.textual_inversion_load_attempt_count != selected_count) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_LOAD_NOT_ATTEMPTED",
                "not every selected textual inversion reached the native loader");
    }
    if (evidence.textual_inversion_loaded_mask != expected_mask ||
        evidence.textual_inversion_loaded_count != selected_count ||
        evidence.textual_inversion_load_failure_count != 0u) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_LOAD_INCOMPLETE",
                "not every selected textual inversion was loaded by the native conditioner");
    }
    if (evidence.textual_inversion_applied_mask != expected_mask ||
        evidence.textual_inversion_applied_count != selected_count ||
        (selected_count > 0u &&
         evidence.textual_inversion_applied_vector_count < selected_count)) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_APPLY_INCOMPLETE",
                "not every selected textual inversion produced custom token vectors");
    }
    if (evidence.textual_inversion_clip_g_required_mask != 0u &&
        evidence.textual_inversion_clip_g_required_mask != expected_mask) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_CLIP_PAIR_MISMATCH",
                "the native conditioner reported an incomplete SDXL CLIP pair requirement");
    }
    if (evidence.textual_inversion_clip_l_mask != expected_mask ||
        evidence.textual_inversion_clip_l_applied_count != selected_count ||
        evidence.textual_inversion_clip_g_mask !=
                evidence.textual_inversion_clip_g_required_mask ||
        evidence.textual_inversion_clip_g_applied_count !=
                mask_bit_count(evidence.textual_inversion_clip_g_required_mask) ||
        evidence.textual_inversion_consumed_mask != expected_mask ||
        (selected_count > 0u &&
         evidence.textual_inversion_conditioning_consumption_count < selected_count)) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_CONDITIONING_INCOMPLETE",
                "not every selected textual inversion was consumed by each required CLIP conditioning graph");
    }
    if (selected_count == 0u &&
        (evidence.textual_inversion_applied_vector_count != 0u ||
         evidence.textual_inversion_conditioning_consumption_count != 0u)) {
        return fail(
                "TEXTUAL_INVERSION_NATIVE_UNEXPECTED_EVIDENCE",
                "native textual inversion evidence was present for a request without a selection");
    }
    return true;
}

std::string component_selection_json(const ComponentPaths &paths) {
    std::ostringstream out;
    out << "{\"mode\":\"" << escape_json_str(paths.selection_mode) << "\","
        << "\"fallback\":" << json_bool_str(paths.selection_fallback) << ","
        << "\"bundleRoot\":\"" << escape_json_str(paths.bundle_root) << "\","
        << "\"primarySlot\":\"" << escape_json_str(paths.primary_slot) << "\","
        << "\"primaryPath\":\"" << escape_json_str(paths.primary_path) << "\","
        << "\"vaePath\":\"" << escape_json_str(paths.vae) << "\","
        << "\"textEncoderPath\":\"" << escape_json_str(paths.text_encoder_path) << "\","
        << "\"textEncoderSlot\":\"" << escape_json_str(paths.text_encoder_slot) << "\","
        << "\"controlNetPath\":\"" << escape_json_str(paths.control_net) << "\","
        << "\"tokenizerPath\":\"" << escape_json_str(paths.tokenizer_path) << "\","
        << "\"manifestPath\":\"" << escape_json_str(paths.manifest_path) << "\"}";
    return out.str();
}

bool resolve_component_paths(const std::string &model_path,
                             const std::string &bundle_root,
                             const std::string &family,
                             const nlohmann::json &params,
                             ComponentPaths &paths,
                             std::string &error) {
    const std::string mode = optional_string(
            params,
            "componentSelectionMode",
            "compatibility_inference");
    if (mode == "compatibility_inference") {
        paths = infer_components(model_path, bundle_root, family);
        paths.selection_mode = mode;
        paths.selection_fallback = true;
        return true;
    }
    if (mode != "manifest_roles") {
        error = "unsupported component selection mode: " + mode;
        return false;
    }

    paths = ComponentPaths{};
    paths.selection_mode = mode;
    paths.selection_fallback = false;
    const std::string requested_root = optional_string(
            params,
            "componentBundleRoot",
            bundle_root);
    char requested_root_buffer[PATH_MAX] = {};
    char jni_root_buffer[PATH_MAX] = {};
    if (realpath(requested_root.c_str(), requested_root_buffer) == nullptr ||
        !dir_exists_local(requested_root_buffer)) {
        error = "explicit component bundle root does not exist";
        return false;
    }
    if (realpath(bundle_root.c_str(), jni_root_buffer) == nullptr ||
        std::string(requested_root_buffer) != std::string(jni_root_buffer)) {
        error = "explicit component bundle root does not match the JNI bundle root";
        return false;
    }
    paths.bundle_root = requested_root_buffer;
    paths.primary_slot = optional_string(params, "componentPrimarySlot", "");
    const std::string requested_primary = optional_string(params, "componentPrimaryPath", "");
    if (paths.primary_slot != "model" && paths.primary_slot != "diffusion") {
        error = "explicit primary component slot must be model or diffusion";
        return false;
    }
    if (!canonical_existing_file_within_root(
            requested_primary,
            paths.bundle_root,
            paths.primary_path,
            error)) {
        return false;
    }
    char model_path_buffer[PATH_MAX] = {};
    if (realpath(model_path.c_str(), model_path_buffer) == nullptr ||
        paths.primary_path != std::string(model_path_buffer)) {
        error = "explicit primary component does not match the JNI model path";
        return false;
    }
    const bool split_family = likely_split_diffusion_family(family, paths.primary_path);
    if ((split_family && paths.primary_slot != "diffusion") ||
        (!split_family && paths.primary_slot != "model")) {
        error = "explicit primary component slot does not match the model family";
        return false;
    }
    if (paths.primary_slot == "model") {
        paths.model = paths.primary_path;
    } else {
        paths.diffusion = paths.primary_path;
    }

    const bool require_vae = split_family ||
                             optional_boolean(params, "componentRequireVae", false);
    const bool require_text_encoder = split_family || optional_boolean(
            params,
            "componentRequireTextEncoder",
            false);
    const bool require_tokenizer = optional_boolean(
            params,
            "componentRequireTokenizer",
            false);
    const std::string requested_vae = optional_string(params, "componentVaePath", "");
    if (!requested_vae.empty()) {
        if (!canonical_existing_file_within_root(
                requested_vae,
                paths.bundle_root,
                paths.vae,
                error)) {
            return false;
        }
    } else if (require_vae) {
        error = "explicit component selection is missing required VAE";
        return false;
    }

    const std::string requested_text_encoder = optional_string(
            params,
            "componentTextEncoderPath",
            "");
    paths.text_encoder_slot = optional_string(
            params,
            "componentTextEncoderSlot",
            "");
    if (!requested_text_encoder.empty()) {
        if (!canonical_existing_file_within_root(
                requested_text_encoder,
                paths.bundle_root,
                paths.text_encoder_path,
                error)) {
            return false;
        }
        if (paths.text_encoder_slot == "llm") {
            paths.llm = paths.text_encoder_path;
        } else if (paths.text_encoder_slot == "t5xxl") {
            paths.t5xxl = paths.text_encoder_path;
        } else if (paths.text_encoder_slot == "clip_l") {
            paths.clip_l = paths.text_encoder_path;
        } else if (paths.text_encoder_slot == "clip_g") {
            paths.clip_g = paths.text_encoder_path;
        } else {
            error = "unsupported explicit text encoder slot: " + paths.text_encoder_slot;
            return false;
        }
    } else if (require_text_encoder) {
        error = "explicit component selection is missing required text encoder";
        return false;
    } else if (!paths.text_encoder_slot.empty()) {
        error = "explicit text encoder slot was provided without a text encoder path";
        return false;
    }

    const std::string requested_tokenizer = optional_string(
            params,
            "componentTokenizerPath",
            "");
    if (!requested_tokenizer.empty()) {
        if (!canonical_existing_file_within_root(
                requested_tokenizer,
                paths.bundle_root,
                paths.tokenizer_path,
                error)) {
            return false;
        }
    } else if (require_tokenizer) {
        error = "explicit component selection is missing required tokenizer";
        return false;
    }

    const std::string requested_control_net = optional_string(
            params,
            "componentControlNetPath",
            "");
    if (!requested_control_net.empty() && !canonical_existing_file_within_root(
            requested_control_net,
            paths.bundle_root,
            paths.control_net,
            error)) {
        return false;
    }

    const std::string requested_manifest = optional_string(
            params,
            "componentManifestPath",
            "");
    if (requested_manifest.empty() || !canonical_existing_file_within_root(
            requested_manifest,
            paths.bundle_root,
            paths.manifest_path,
            error)) {
        if (requested_manifest.empty()) error = "explicit component selection is missing manifest path";
        return false;
    }
    return true;
}

}  // namespace sd
}  // namespace mca
