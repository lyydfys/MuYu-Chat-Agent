#include "mnn_bundle_config.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#include "image_conditioning.hpp"
#include "mnn_bundle_path_policy.hpp"
#include "mnn_embedding_bundle_policy.hpp"
#include "mnn_stream_protocol_filter.hpp"
#include "mnn_vision_path_policy.hpp"
#include "mnn_template_parser.hpp"

namespace mca {
namespace mnn {

std::string parent_dir(const std::string& path) {
    const auto pos = path.find_last_of("/\\");
    if (pos == std::string::npos) return ".";
    if (pos == 0) return path.substr(0, 1);
    if (pos == 2 && path.size() > 2 && path[1] == ':' &&
        (path[2] == '/' || path[2] == '\\')) {
        return path.substr(0, 3);
    }
    return path.substr(0, pos);
}

bool nonempty_regular_file_exists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0;
}

bool directory_exists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::string normalize_mnn_model_path(const std::string& path) {
    if (path.empty()) return path;
    if (directory_exists(path) && path.back() != '/' && path.back() != '\\') {
        return path + "/";
    }
    return path;
}

std::string join_path(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/' || dir.back() == '\\') return dir + name;
    return dir + "/" + name;
}

std::string resolve_mnn_config_path(const std::string& path) {
    const auto normalized = normalize_mnn_model_path(path);
    if (directory_exists(normalized)) {
        return join_path(normalized, "config.json");
    }
    return normalized;
}

nlohmann::json read_json_file_or_empty(const std::string& path) {
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) return nlohmann::json::object();
    nlohmann::json parsed = nlohmann::json::parse(input, nullptr, false);
    if (!parsed.is_object()) return nlohmann::json::object();
    return parsed;
}

bool read_mnn_config_object(
        const std::string& path,
        const std::string& label,
        nlohmann::json& output,
        std::string& error) {
    struct stat info {};
    if (stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0) {
        error = label + " is missing, empty, or not a regular file: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        error = label + " cannot be opened: " + path;
        return false;
    }
    output = nlohmann::json::parse(input, nullptr, false);
    if (!output.is_object()) {
        error = label + " must contain a JSON object: " + path;
        return false;
    }
    return true;
}

void merge_mnn_config(nlohmann::json& target, const nlohmann::json& source) {
    if (!target.is_object() || !source.is_object()) return;
    for (auto it = source.begin(); it != source.end(); ++it) {
        auto existing = target.find(it.key());
        if (existing != target.end() && existing->is_object() && it.value().is_object()) {
            merge_mnn_config(*existing, it.value());
        } else {
            target[it.key()] = it.value();
        }
    }
}

void relocate_mnn_component_declarations(
        nlohmann::json& config, const std::string& root, const std::string& prefix, size_t depth) {
    if (depth > 32U) throw std::invalid_argument("MNN configuration nesting exceeds the safe path inspection limit.");
    if (config.is_array()) {
        for (auto& child : config) relocate_mnn_component_declarations(child, root, prefix, depth + 1);
        return;
    }
    if (!config.is_object()) return;
    for (const auto* key : {
             "llm_config", "llm_model", "llm_weight", "block_model", "lm_model",
             "embedding_model", "embedding_file", "tokenizer_file", "visual_model",
             "vision_model", "visual_encoder", "vision_encoder", "audio_model",
             "visual_weight", "audio_weight", "projector_model", "projector_weight",
             "context_file", "ple_model", "ple_weight", "ple_embed_file", "talker_model", "talker_weight",
             "talker_embedding_file", "predit_model", "dit_model", "bigvgan_model",
             "spk_dict", "lookup_file", "draft_model", "mtp_model", "eagle_model",
             "eagle_fc", "eagle_d2t", "dflash_model", "dflash_fc", "dflash_lmhead"}) {
        const auto it = config.find(key);
        if (it == config.end()) continue;
        if (!it->is_string()) {
            throw std::invalid_argument(std::string("MNN config field '") + key + "' must be a relative path.");
        }
        if (it->get<std::string>().empty()) continue; // Optional upstream paths may be disabled with "".
        const auto relative = mca::mnn::mnnBundleRelativeComponent(prefix, it->get<std::string>());
        mca::mnn::confinedMnnBundlePath(root, relative);
        *it = relative;
    }
    const auto npuDir = config.find("npu_model_dir");
    if (npuDir != config.end()) {
        if (!npuDir->is_string()) throw std::invalid_argument("MNN npu_model_dir must be a relative path.");
        const auto relative = mca::mnn::normalizeMnnBundleRelativePath(npuDir->get<std::string>(), true);
        *npuDir = prefix.empty() ? relative : prefix + (relative.empty() ? "" : "/" + relative);
        mca::mnn::confinedMnnBundlePath(root, npuDir->get<std::string>(), true);
    }
    config.erase("base_dir");
    for (auto& child : config) {
        if (child.is_object() || child.is_array()) {
            relocate_mnn_component_declarations(child, root, prefix, depth + 1);
        }
    }
}

void read_relocated_mnn_configs(const std::string& configPath, nlohmann::json& config, nlohmann::json& modelConfig) {
    const auto root = parent_dir(configPath);
    std::string error;
    if (!read_mnn_config_object(configPath, "MNN config.json", config, error)) {
        throw std::invalid_argument(error);
    }
    const auto base = config.find("base_dir");
    if (base != config.end() && !base->is_null() && !base->is_string()) {
        throw std::invalid_argument("MNN base_dir must be a directory path.");
    }
    const auto prefix = mca::mnn::relocatedMnnBasePrefix(
            base == config.end() || base->is_null() ? std::string() : base->get<std::string>());
    mca::mnn::confinedMnnBundlePath(root, prefix, true);
    if (!config.contains("llm_config")) config["llm_config"] = "llm_config.json";
    relocate_mnn_component_declarations(config, root, prefix);
    const auto llmPath = mca::mnn::confinedMnnBundlePath(root, config["llm_config"].get<std::string>());
    if (!read_mnn_config_object(llmPath.string(), "MNN llm_config", modelConfig, error)) {
        throw std::invalid_argument(error);
    }
    // Upstream fixes base_dir_ from the root before merging llm_config. Use
    // that same prefix for both documents; do not let a model-side base_dir
    // revive the export machine's directory when creating the runtime copy.
    relocate_mnn_component_declarations(modelConfig, root, prefix);
    for (const auto& entry : {
             std::pair<const char*, const char*>{"llm_model", "llm.mnn"},
             {"llm_weight", "llm.mnn.weight"}, {"embedding_file", "embeddings_bf16.bin"},
             {"block_model", "block_"}, {"lm_model", "lm.mnn"}, {"embedding_model", "embedding.mnn"},
             {"context_file", "context.json"}, {"talker_model", "talker.mnn"},
             {"talker_weight", "talker.mnn.weight"}, {"talker_embedding_file", "talker_embeddings_bf16.bin"},
             {"predit_model", "predit.mnn"}, {"dit_model", "dit.mnn"}, {"bigvgan_model", "bigvgan.mnn"},
             {"spk_dict", "spk_dict.mnn"}, {"lookup_file", "lookup_file.txt"}, {"mtp_model", "mtp.mnn"},
             {"eagle_model", "eagle.mnn"}, {"eagle_fc", "eagle_fc.mnn"}, {"eagle_d2t", "eagle_d2t.mnn"},
             {"dflash_model", "dflash.mnn"}, {"dflash_fc", "dflash_fc.mnn"}}) {
        if (!config.contains(entry.first) && !modelConfig.contains(entry.first)) {
            config[entry.first] = mca::mnn::mnnBundleRelativeComponent(prefix, entry.second);
        }
    }
    if (!prefix.empty() && !config.contains("npu_model_dir") && !modelConfig.contains("npu_model_dir")) {
        config["npu_model_dir"] = prefix;
    }
    if (!config.contains("tokenizer_file") && !modelConfig.contains("tokenizer_file")) {
        const auto txt = mca::mnn::mnnBundleRelativeComponent(prefix, "tokenizer.txt");
        const auto mtok = mca::mnn::mnnBundleRelativeComponent(prefix, "tokenizer.mtok");
        config["tokenizer_file"] = !nonempty_regular_file_exists(
                mca::mnn::confinedMnnBundlePath(root, txt).string()) && nonempty_regular_file_exists(
                mca::mnn::confinedMnnBundlePath(root, mtok).string()) ? mtok : txt;
    }
    for (const auto& entry : {
             std::pair<const char*, const char*>{"visual_model", "is_visual"}, {"audio_model", "is_audio"}}) {
        const bool enabled = modelConfig.contains(entry.second)
                ? modelConfig.value(entry.second, false) : config.value(entry.second, false);
        if (enabled && !config.contains(entry.first) && !modelConfig.contains(entry.first)) {
            config[entry.first] = mca::mnn::mnnBundleRelativeComponent(
                    prefix, std::string(entry.first) == "visual_model" ? "visual.mnn" : "audio.mnn");
        }
    }
}

bool mnn_configured_component_path(
        const nlohmann::json& config,
        const std::string& root,
        const char* key,
        const char* fallback,
        std::string& relative,
        std::string& absolute,
        std::string& error) {
    std::string raw = fallback;
    const auto value = config.find(key);
    if (value != config.end()) {
        if (!value->is_string() || value->get<std::string>().empty()) {
            error = std::string("MNN config field '") + key + "' must be a non-empty relative path.";
            return false;
        }
        raw = value->get<std::string>();
    }
    try {
        relative = mca::mnn::normalizeMnnBundleRelativePath(raw);
        absolute = mca::mnn::confinedMnnBundlePath(root, relative).string();
    } catch (const std::exception& e) {
        error = std::string("MNN config field '") + key +
                "' contains an unsafe component path: " + raw + ". " + e.what();
        return false;
    }
    return true;
}

bool require_mnn_component(
        const nlohmann::json& config,
        const std::string& root,
        const char* key,
        const char* fallback,
        std::string& relative,
        std::string& absolute,
        std::string& error) {
    if (!mnn_configured_component_path(
            config, root, key, fallback, relative, absolute, error)) {
        return false;
    }
    if (!nonempty_regular_file_exists(absolute)) {
        error = std::string("Required MNN component '") + relative +
                "' is missing, empty, or not a regular file.";
        return false;
    }
    return true;
}

bool mnn_json_exact_int64(const nlohmann::json& value, int64_t& output) {
    if (value.is_number_unsigned()) {
        const auto raw = value.get<uint64_t>();
        if (raw > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) return false;
        output = static_cast<int64_t>(raw);
        return true;
    }
    if (value.is_number_integer()) {
        output = value.get<int64_t>();
        return true;
    }
    return false;
}

bool parse_mnn_tie_embedding_descriptor(
        const nlohmann::json& value,
        TieEmbeddingDescriptor& descriptor,
        std::string& error) {
    const auto read = [&error](const nlohmann::json& raw, const char* name, int64_t& target) {
        if (!mnn_json_exact_int64(raw, target)) {
            error = std::string("tie_embeddings field '") + name + "' must be an exact integer.";
            return false;
        }
        return true;
    };
    if (value.is_array()) {
        if (value.size() < 5U ||
            !read(value[0], "weight_offset", descriptor.weight_offset) ||
            !read(value[1], "alpha_offset", descriptor.alpha_offset) ||
            !read(value[2], "alpha_size", descriptor.alpha_size) ||
            !read(value[3], "quant_bit", descriptor.quant_bit) ||
            !read(value[4], "quant_block", descriptor.quant_block)) {
            if (error.empty()) {
                error = "legacy tie_embeddings must contain at least five exact integers.";
            }
            return false;
        }
        if (value.size() >= 6U) {
            if (value[5].is_boolean()) {
                descriptor.alpha_fp16 = value[5].get<bool>();
            } else {
                int64_t alphaFp16 = 0;
                if (!read(value[5], "alpha_fp16", alphaFp16)) return false;
                descriptor.alpha_fp16 = alphaFp16 != 0;
            }
        }
        return true;
    }
    if (!value.is_object()) {
        error = "tie_embeddings must be an array or object.";
        return false;
    }
    for (const auto* name : {
             "weight_offset", "alpha_offset", "alpha_size", "quant_bit", "quant_block"}) {
        const auto field = value.find(name);
        if (field == value.end()) {
            error = std::string("tie_embeddings is missing field '") + name + "'.";
            return false;
        }
        int64_t* target = nullptr;
        if (std::strcmp(name, "weight_offset") == 0) target = &descriptor.weight_offset;
        else if (std::strcmp(name, "alpha_offset") == 0) target = &descriptor.alpha_offset;
        else if (std::strcmp(name, "alpha_size") == 0) target = &descriptor.alpha_size;
        else if (std::strcmp(name, "quant_bit") == 0) target = &descriptor.quant_bit;
        else target = &descriptor.quant_block;
        if (!read(*field, name, *target)) return false;
    }
    const auto alphaDtypeIt = value.find("alpha_dtype");
    if (alphaDtypeIt != value.end() && !alphaDtypeIt->is_string()) {
        error = "tie_embeddings alpha_dtype must be a string.";
        return false;
    }
    const auto alphaDtype = alphaDtypeIt == value.end()
            ? std::string("fp32")
            : alphaDtypeIt->get<std::string>();
    if (alphaDtype != "fp16" && alphaDtype != "fp32") {
        error = "tie_embeddings alpha_dtype must be fp16 or fp32.";
        return false;
    }
    descriptor.alpha_fp16 = alphaDtype == "fp16";
    return true;
}

std::string mnn_chat_bundle_preflight_message(const std::string& configPath) {
    const auto root = parent_dir(configPath);
    nlohmann::json rootConfig;
    std::string error;
    if (!read_mnn_config_object(configPath, "MNN config.json", rootConfig, error)) return error;

    std::string llmConfigRelative;
    std::string llmConfigPath;
    if (!require_mnn_component(
            rootConfig,
            root,
            "llm_config",
            "llm_config.json",
            llmConfigRelative,
            llmConfigPath,
            error)) {
        return error;
    }
    nlohmann::json modelConfig;
    if (!read_mnn_config_object(llmConfigPath, "MNN llm_config", modelConfig, error)) return error;

    nlohmann::json effective = rootConfig;
    merge_mnn_config(effective, modelConfig);
    std::string modelRelative;
    std::string modelPath;
    if (!require_mnn_component(
            effective, root, "llm_model", "llm.mnn", modelRelative, modelPath, error)) {
        return error;
    }
    std::string weightRelative;
    std::string weightPath;
    if (!require_mnn_component(
            effective, root, "llm_weight", "llm.mnn.weight", weightRelative, weightPath, error)) {
        return error;
    }
    std::string tokenizerRelative;
    std::string tokenizerPath;
    if (!require_mnn_component(
            effective, root, "tokenizer_file", "tokenizer.txt", tokenizerRelative, tokenizerPath, error)) {
        return error;
    }

    if (effective.contains("ple_embed_file")) {
        std::string pleRelative;
        std::string plePath;
        if (!require_mnn_component(
                effective, root, "ple_embed_file", "", pleRelative, plePath, error)) {
            return error;
        }
    }
    if (effective.value("is_visual", false)) {
        std::string visualRelative;
        std::string visualPath;
        if (!require_mnn_component(
                effective, root, "visual_model", "visual.mnn", visualRelative, visualPath, error)) {
            return error;
        }
    }
    if (effective.value("is_audio", false)) {
        std::string audioRelative;
        std::string audioPath;
        if (!require_mnn_component(
                effective, root, "audio_model", "audio.mnn", audioRelative, audioPath, error)) {
            return error;
        }
    }

    const auto tie = effective.find("tie_embeddings");
    if (tie == effective.end()) {
        std::string embeddingRelative;
        std::string embeddingPath;
        if (!require_mnn_component(
                effective,
                root,
                "embedding_file",
                "embeddings_bf16.bin",
                embeddingRelative,
                embeddingPath,
                error)) {
            return error;
        }
        return "";
    }

    TieEmbeddingDescriptor descriptor;
    if (!parse_mnn_tie_embedding_descriptor(*tie, descriptor, error)) {
        return "Invalid MNN tie_embeddings metadata: " + error;
    }
    const bool tiedToLlmWeight = descriptor.weight_offset > 0;
    std::string storageRelative = weightRelative;
    std::string storagePath = weightPath;
    if (!tiedToLlmWeight &&
        !require_mnn_component(
                effective,
                root,
                "embedding_file",
                "embeddings_bf16.bin",
                storageRelative,
                storagePath,
                error)) {
        return error;
    }
    struct stat storageInfo {};
    if (stat(storagePath.c_str(), &storageInfo) != 0 || !S_ISREG(storageInfo.st_mode) ||
        storageInfo.st_size <= 0) {
        return "MNN tie_embeddings backing file is missing or empty: " + storageRelative;
    }
    int64_t hiddenSize = 0;
    const auto hidden = effective.find("hidden_size");
    if (hidden == effective.end() || !mnn_json_exact_int64(*hidden, hiddenSize)) {
        return "MNN tie_embeddings requires an exact integer hidden_size.";
    }
    const auto validation = mca::mnn::validateTieEmbedding(
            descriptor,
            hiddenSize,
            static_cast<uint64_t>(storageInfo.st_size));
    if (!validation.valid) {
        return "Invalid MNN tie_embeddings metadata for '" + storageRelative + "': " +
                validation.error;
    }
    return "";
}

bool collect_mnn_published_image_evidence(
        const std::string& path,
        MnnPublishedImageEvidence& evidence,
        std::string& error) {
    constexpr long long kMinPngBytes = 57LL;
    constexpr long long kMaxPngBytes = 64LL * 1024LL * 1024LL;
    struct stat info {};
    if (path.empty() || ::lstat(path.c_str(), &info) != 0 ||
        !S_ISREG(info.st_mode) || info.st_size < kMinPngBytes ||
        info.st_size > kMaxPngBytes) {
        error = "MNN-Diffusion output is not a bounded regular PNG file.";
        return false;
    }
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        error = "MNN-Diffusion output could not be opened for publication proof.";
        return false;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(info.st_size));
    input.read(reinterpret_cast<char*>(bytes.data()), info.st_size);
    if (input.gcount() != info.st_size || input.peek() != std::char_traits<char>::eof()) {
        error = "MNN-Diffusion output changed while publication proof was collected.";
        return false;
    }
    struct stat after {};
    if (::lstat(path.c_str(), &after) != 0 || !S_ISREG(after.st_mode) ||
        after.st_dev != info.st_dev || after.st_ino != info.st_ino ||
        after.st_size != info.st_size) {
        error = "MNN-Diffusion output identity changed before publication.";
        return false;
    }
    static constexpr std::array<uint8_t, 8> kPngSignature = {
        UINT8_C(0x89), UINT8_C(0x50), UINT8_C(0x4e), UINT8_C(0x47),
        UINT8_C(0x0d), UINT8_C(0x0a), UINT8_C(0x1a), UINT8_C(0x0a),
    };
    if (bytes.size() < kPngSignature.size() ||
        !std::equal(kPngSignature.begin(), kPngSignature.end(), bytes.begin())) {
        error = "MNN-Diffusion output does not have a PNG signature.";
        return false;
    }
    evidence.bytes = static_cast<long long>(bytes.size());
    evidence.sha256 = mca::image::sha256_hex_bytes(bytes);
    if (evidence.sha256.size() != 64U) {
        error = "MNN-Diffusion output SHA-256 proof could not be produced.";
        return false;
    }
    error.clear();
    return true;
}

void collect_mnn_visual_path_declarations(
        const nlohmann::json& value,
        std::vector<std::string>& paths,
        int depth,
        bool allowGenericModelPath) {
    if (depth > 8) return;
    if (value.is_array()) {
        for (const auto& item : value) {
            collect_mnn_visual_path_declarations(item, paths, depth + 1, allowGenericModelPath);
        }
        return;
    }
    if (!value.is_object()) return;
    for (auto it = value.begin(); it != value.end(); ++it) {
        const auto key = mca::mnn::lowerAscii(it.key());
        if ((mca::mnn::isMnnVisualPathKey(it.key()) ||
             (allowGenericModelPath && (key == "model" || key == "path"))) &&
            it.value().is_string()) {
            mca::mnn::appendUniqueMnnVisualPath(it.value().get<std::string>(), paths);
        }
        if (it.value().is_object() || it.value().is_array()) {
            const bool childAllowsGeneric = key == "vision" || key == "visual" ||
                    key == "image_encoder" ||
                    key == "vision_encoder" || key == "visual_encoder";
            collect_mnn_visual_path_declarations(
                    it.value(), paths, depth + 1, childAllowsGeneric || allowGenericModelPath);
        }
    }
}

std::vector<std::string> mnn_visual_path_declarations(
        const std::string& root,
        const nlohmann::json& config) {
    std::vector<std::string> paths;
    collect_mnn_visual_path_declarations(config, paths);

    std::string llmConfigPath = "llm_config.json";
    const auto llmConfigIt = config.find("llm_config");
    if (llmConfigIt != config.end() && llmConfigIt->is_string()) {
        const auto normalized = mca::mnn::normalizeMnnVisualRelativePath(
                llmConfigIt->get<std::string>());
        if (!normalized.empty()) llmConfigPath = normalized;
    }
    const auto llmConfig = read_json_file_or_empty(join_path(root, llmConfigPath));
    collect_mnn_visual_path_declarations(llmConfig, paths);
    return paths;
}

std::string select_mnn_visual_model_path(
        const std::string& root,
        const nlohmann::json& config,
        const std::string& configuredVisual) {
    const auto declared = mnn_visual_path_declarations(root, config);
    return mca::mnn::selectMnnVisualModelPath(
            configuredVisual,
            declared,
            [&root](const std::string& relativePath) {
                return nonempty_regular_file_exists(join_path(root, relativePath));
            });
}

bool is_mnn_stop_marker_key(const std::string& rawKey) {
    std::string key;
    key.reserve(rawKey.size());
    for (const unsigned char ch : rawKey) {
        key.push_back(static_cast<char>(std::tolower(ch)));
    }
    return key == "eos_token" ||
           key == "eod_token" ||
           key == "end_token" ||
           key == "end_of_turn_token" ||
           key == "stop" ||
           key == "stops" ||
           key == "stop_word" ||
           key == "stop_words" ||
           key == "stop_token" ||
           key == "stop_tokens";
}

const std::vector<std::string>& default_mnn_stop_markers() {
    static const std::vector<std::string> markers = {
            "<eop>",
            "<|endoftext|>",
            "<|im_end|>",
            "<|end_of_turn|>",
            "<end_of_turn>",
            "<|eot_id|>"
    };
    return markers;
}

void append_stop_marker(std::vector<std::string>& target, const std::string& marker) {
    constexpr size_t kMaxStopMarkerBytes = 256;
    if (marker.empty() || marker.size() > kMaxStopMarkerBytes ||
            mca::mnn::is_role_header_marker(marker)) return;
    if (std::find(target.begin(), target.end(), marker) == target.end()) {
        target.push_back(marker);
    }
}

void append_mnn_stop_markers_from_json(const nlohmann::json& value, std::vector<std::string>& target, int depth) {
    if (depth > 4) return;
    if (value.is_string()) {
        append_stop_marker(target, value.get<std::string>());
        return;
    }
    if (value.is_array()) {
        for (const auto& item : value) {
            append_mnn_stop_markers_from_json(item, target, depth + 1);
        }
        return;
    }
    if (!value.is_object()) return;
    for (auto it = value.begin(); it != value.end(); ++it) {
        if (is_mnn_stop_marker_key(it.key())) {
            append_mnn_stop_markers_from_json(it.value(), target, depth + 1);
        } else if (it.value().is_object()) {
            append_mnn_stop_markers_from_json(it.value(), target, depth + 1);
        }
    }
}

std::vector<std::string> mnn_model_stop_markers(const std::string& configPath) {
    auto markers = default_mnn_stop_markers();
    append_mnn_stop_markers_from_json(read_json_file_or_empty(configPath), markers);
    return markers;
}

std::vector<std::string> mnn_load_signature_differences(
        const nlohmann::json& loaded,
        const nlohmann::json& requested) {
    static const std::array<const char*, 11> kLoadBoundFields = {
            "backend_type",
            "precision",
            "memory",
            "power",
            "use_mmap",
            "use_cached_mmap",
            "tmp_path",
            "kvcache_mmap",
            "max_all_tokens",
            "n_ctx",
            "visual_model"};
    std::vector<std::string> changed;
    for (const char* field : kLoadBoundFields) {
        const auto loaded_it = loaded.find(field);
        const auto requested_it = requested.find(field);
        if (loaded_it == loaded.end() && requested_it == requested.end()) continue;
        if (loaded_it == loaded.end() || requested_it == requested.end() || *loaded_it != *requested_it) {
            changed.emplace_back(field);
        }
    }
    return changed;
}

bool write_json_file(const std::string& path, const nlohmann::json& value) {
    std::ofstream output(path.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) return false;
    output << value.dump(4);
    return output.good();
}

// The app links one coherent MNN 3.6 runtime.  Gemma 4's public MNN 3.5
// visual graph crashes in MNN::Transformer::Omni::load before the Java layer
// can recover, so keep a native preflight in addition to the repository gate.
// Text-only isolation packages are unaffected because they do not enable or
// contain a visual model.
std::string incompatible_legacy_mnn_visual_graph_message(const std::string& requestedPath) {
    constexpr const char* kLegacyGraphVersion = "3.5.0";
    constexpr const char* kProductRuntimeVersion = "3.6";
    const auto configPath = resolve_mnn_config_path(requestedPath);
    const auto root = parent_dir(configPath);
    const auto config = read_json_file_or_empty(configPath);
    if (!config.is_object()) return "";
    if (config.value("mca_import_mode", std::string()) == "text_only") return "";

    std::string configuredVisual;
    bool configuredVisualDeclared = false;
    const auto visualIt = config.find("visual_model");
    if (visualIt != config.end() && visualIt->is_string()) {
        configuredVisualDeclared = true;
        configuredVisual = mca::mnn::normalizeMnnVisualRelativePath(visualIt->get<std::string>());
    }
    const auto selectedVisual = select_mnn_visual_model_path(root, config, configuredVisual);
    const bool visualEnabled = config.value("is_visual", false) ||
            configuredVisualDeclared || !selectedVisual.empty();
    if (!visualEnabled) return "";
    const auto visualName = selectedVisual.empty()
            ? (configuredVisual.empty() ? std::string("visual.mnn") : configuredVisual)
            : selectedVisual;

    // llm.mnn.json is MNN's exporter metadata sidecar. A model may use a
    // differently named JSON embedding sidecar or a renamed llm_model, so
    // inspect each safe relative metadata name before the standard file. This
    // must mirror the repository gate: raw/debug native loads bypass Kotlin.
    std::vector<std::string> metadataPaths;
    const auto is_safe_relative = [](const std::string& name) {
        if (name.empty() || name.front() == '/' || name.find('\\') != std::string::npos) return false;
        size_t segmentStart = 0;
        while (segmentStart <= name.size()) {
            const size_t segmentEnd = name.find('/', segmentStart);
            const auto segment = name.substr(
                    segmentStart,
                    segmentEnd == std::string::npos ? std::string::npos : segmentEnd - segmentStart);
            if (segment.empty() || segment == "." || segment == "..") return false;
            if (segmentEnd == std::string::npos) break;
            segmentStart = segmentEnd + 1;
        }
        return true;
    };
    const auto add_metadata_path = [&metadataPaths, &root, &is_safe_relative](const std::string& name) {
        const bool isJson = name.size() >= 5 && name.compare(name.size() - 5, 5, ".json") == 0;
        if (isJson && is_safe_relative(name)) {
            metadataPaths.push_back(join_path(root, name));
        }
    };
    const auto embeddingIt = config.find("embedding_file");
    if (embeddingIt != config.end() && embeddingIt->is_string()) {
        add_metadata_path(embeddingIt->get<std::string>());
    }
    const auto llmModelIt = config.find("llm_model");
    if (llmModelIt != config.end() && llmModelIt->is_string()) {
        const auto modelName = llmModelIt->get<std::string>();
        const bool isMnn = modelName.size() >= 4 &&
                modelName.compare(modelName.size() - 4, 4, ".mnn") == 0;
        if (isMnn && is_safe_relative(modelName)) {
            add_metadata_path(modelName + ".json");
        }
    }
    const bool visualNameIsMnn = visualName.size() >= 4 &&
            visualName.compare(visualName.size() - 4, 4, ".mnn") == 0;
    if (visualNameIsMnn && is_safe_relative(visualName)) {
        add_metadata_path(visualName + ".json");
    }
    metadataPaths.push_back(join_path(root, "llm.mnn.json"));
    std::sort(metadataPaths.begin(), metadataPaths.end());
    metadataPaths.erase(std::unique(metadataPaths.begin(), metadataPaths.end()), metadataPaths.end());
    for (const auto& metadataPath : metadataPaths) {
        const auto metadata = read_json_file_or_empty(metadataPath);
        if (!metadata.is_object()) continue;
        const auto extraInfo = metadata.find("extraInfo");
        if (extraInfo == metadata.end() || !extraInfo->is_object()) continue;
        const auto version = extraInfo->value("version", std::string());
        if (version == kLegacyGraphVersion) {
            return std::string("MNN 多模态图版本 ") + kLegacyGraphVersion +
                    " 与当前 MNN " + kProductRuntimeVersion +
                    " runtime 不兼容，已安全阻止加载。请使用 MNN " +
                    kProductRuntimeVersion +
                    " 重新导出的多模态包，或移除视觉组件后仅作为文本模型使用。";
        }
    }
    return "";
}

std::string prepare_mnn_runtime_config(
        const std::string& requestedPath,
        std::string* visualModelPath,
        bool* visionReady) {
    const auto configPath = resolve_mnn_config_path(requestedPath);
    const auto root = parent_dir(configPath);
    nlohmann::json config;
    nlohmann::json modelConfig;
    if (visualModelPath != nullptr) {
        visualModelPath->clear();
    }
    if (visionReady != nullptr) {
        *visionReady = false;
    }
    read_relocated_mnn_configs(configPath, config, modelConfig);
    inject_legacy_mnn_chat_template(config, modelConfig);
    nlohmann::json effective = config;
    merge_mnn_config(effective, modelConfig);
    const bool textOnlyImport = effective.value("mca_import_mode", std::string()) == "text_only";

    std::string configuredVisual;
    bool configuredVisualDeclared = false;
    auto visualIt = effective.find("visual_model");
    if (visualIt != effective.end() && visualIt->is_string()) {
        configuredVisualDeclared = true;
        configuredVisual = mca::mnn::normalizeMnnVisualRelativePath(visualIt->get<std::string>());
    }
    std::vector<std::string> declaredVisualPaths;
    collect_mnn_visual_path_declarations(config, declaredVisualPaths);
    collect_mnn_visual_path_declarations(modelConfig, declaredVisualPaths);
    const auto selectedVisual = textOnlyImport ? std::string() : mca::mnn::selectMnnVisualModelPath(
            configuredVisual, declaredVisualPaths, [&root](const std::string& relative) {
                return nonempty_regular_file_exists(mca::mnn::confinedMnnBundlePath(root, relative).string());
            });
    const bool visualExists = !selectedVisual.empty();
    const bool shouldEnableVision = !textOnlyImport && (visualExists || effective.value("is_visual", false) ||
            configuredVisualDeclared);
    if (shouldEnableVision) {
        config["is_visual"] = true;
        modelConfig["is_visual"] = true;
        std::string resolvedVisualPath;
        if (visualExists) {
            // Always persist the resolved relative path.  This makes nested
            // exporter layouts visible to MNN's Omni loader and keeps the
            // g_vision_ready flag truthful for the Java/UI/API layers.
            config["visual_model"] = selectedVisual;
            modelConfig["visual_model"] = selectedVisual;
            resolvedVisualPath = join_path(root, selectedVisual);
        } else if (configuredVisual.empty()) {
            config["visual_model"] = "visual.mnn";
            resolvedVisualPath = join_path(root, "visual.mnn");
        } else {
            resolvedVisualPath = configuredVisual;
        }
        if (visualModelPath != nullptr) {
            *visualModelPath = resolvedVisualPath;
        }
        if (visionReady != nullptr) {
            *visionReady = visualExists;
        }
    }
    if (textOnlyImport) {
        for (auto* document : {&config, &modelConfig}) {
            (*document)["mca_import_mode"] = "text_only";
            (*document)["is_visual"] = false;
            (*document)["is_audio"] = false;
            (*document)["is_talker"] = false;
        }
    }

    // LlmConfig reads and merges llm_config inside createLLM(), before any
    // set_config call. Generate both documents so that second merge cannot
    // reintroduce export-machine base_dir or unnormalised component paths.
    const auto runtimeLlmName = "mca_runtime_llm_config.json";
    const auto runtimeLlmPath = mca::mnn::confinedMnnBundlePath(root, runtimeLlmName).string();
    modelConfig.erase("llm_config");
    if (!write_json_file(runtimeLlmPath, modelConfig)) {
        throw std::runtime_error("MNN relocated model config could not be written: " + runtimeLlmPath);
    }
    config["llm_config"] = runtimeLlmName;
    const auto runtimeConfigPath = mca::mnn::confinedMnnBundlePath(root, "mca_runtime_config.json").string();
    if (!write_json_file(runtimeConfigPath, config)) {
        throw std::runtime_error(
                "MNN compatibility runtime config could not be written: " + runtimeConfigPath);
    }
    return runtimeConfigPath;
}

} // namespace mnn
} // namespace mca
