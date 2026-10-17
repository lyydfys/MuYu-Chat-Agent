#ifndef MCA_MNN_DIFFUSION_ASSET_CONTRACT_HPP
#define MCA_MNN_DIFFUSION_ASSET_CONTRACT_HPP

#include <sstream>
#include <string>
#include <vector>

#include "nlohmann/json.hpp"

#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_text_encoder_asset.hpp"

namespace mca {
namespace mnn {

using json = nlohmann::json;

// Declared in mca/mnn/mnn_protocol_parser.hpp (whose TU pulls POSIX/Android includes,
// so only the declaration is needed here).
std::string normalize_mnn_diffusion_identifier(std::string value);


// E08c (pure migration, L1): the MNN-Diffusion asset contract helpers were moved verbatim out of
// the anonymous namespace in mnn_native_engine.cpp so family classification, the missing-component
// contract and the failure payloads have a single owner (plan section 5.5 mnn_diffusion_assets).
// Function bodies are byte-identical to the bridge definitions (concatenated source block sha256
// 20b849a00351672853818628ab3e726f4bf21993279c473fb35f672f8279de27); only the `inline` keyword was added.
// Callers keep the same unqualified names via using-declarations in the bridge.

inline json mnn_execution_asset_failure(const std::string& error, const char* format = nullptr) {
    if (error == "cancelled") {
        json cancelled = {{"ok", false}, {"cancelled", true}, {"error", "cancelled"}};
        if (format != nullptr) cancelled["format"] = format;
        return cancelled;
    }
    json failure = {
        {"ok", false},
        {"errorCode", "TEXTUAL_INVERSION_EXECUTION_ASSET_MISMATCH"},
        {"error", error}
    };
    if (format != nullptr) failure["format"] = format;
    return failure;
}

inline bool is_sana_family(const std::string& family) {
    const auto normalized = normalize_mnn_diffusion_identifier(family);
    return normalized == "sana" || normalized == "sana_diffusion";
}

inline bool is_sd15_family(const std::string& family) {
    const auto normalized = normalize_mnn_diffusion_identifier(family);
    return normalized == "sd15" || normalized == "sd1.5" ||
            normalized == "stable_diffusion_1_5" ||
            normalized == "stable_diffusion_1.5" ||
            normalized == "stable-diffusion-1.5";
}

inline json mnn_text_encoder_asset_failure(const std::string& error) {
    if (error == "cancelled") {
        return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}});
    }
    return json({
        {"ok", false},
        {"errorCode", "TEXT_ENCODER_ASSET_MISMATCH"},
        {"error", error}
    });
}

inline std::string mnn_diffusion_missing_component(
        const std::string& root,
        const std::string& family,
        const MnnTextEncoderAssetRequest* declaredTextEncoder = nullptr) {
    if (root.empty() || !directory_exists(root)) {
        return is_sana_family(family)
                ? "MNN Sana requires a complete resource directory."
                : "MNN-Diffusion requires a complete Stable Diffusion 1.5 resource directory.";
    }
    if (is_sana_family(family)) {
        std::vector<std::string> missing;
        if (!file_exists(root + "/connector.mnn")) missing.emplace_back("connector.mnn");
        if (!file_exists(root + "/projector.mnn")) missing.emplace_back("projector.mnn");
        if (!file_exists(root + "/transformer.mnn")) missing.emplace_back("transformer.mnn");
        if (!file_exists(root + "/vae_decoder.mnn")) missing.emplace_back("vae_decoder.mnn");
        if (!file_exists(root + "/llm/config.json")) missing.emplace_back("llm/config.json");
        if (!file_exists(root + "/llm/meta_queries.mnn")) missing.emplace_back("llm/meta_queries.mnn");
        if (missing.empty()) return "";
        std::ostringstream out;
        out << "MNN Sana bundle is incomplete: ";
        for (size_t i = 0; i < missing.size(); ++i) {
            if (i > 0) out << ", ";
            out << missing[i];
        }
        return out.str();
    }
    std::vector<std::string> missing;
    if (declaredTextEncoder != nullptr && declaredTextEncoder->declared) {
        if (!file_exists(declaredTextEncoder->path)) {
            missing.emplace_back("nativeTextEncoderEvidence.path");
        }
        if (declaredTextEncoder->has_weight && !file_exists(declaredTextEncoder->weight_path)) {
            missing.emplace_back("nativeTextEncoderEvidence.auxiliaryAssets[0]");
        }
    } else {
        const bool hasCommunityClip =
                file_exists(root + "/clip_v2.mnn") &&
                file_exists(root + "/tokenizer.json") &&
                file_exists(root + "/token_emb.bin") &&
                file_exists(root + "/pos_emb.bin");
        if (!hasCommunityClip) {
            if (!file_exists(root + "/text_encoder.mnn")) missing.emplace_back("text_encoder.mnn");
            if (!file_exists(root + "/text_encoder.mnn.weight")) {
                missing.emplace_back("text_encoder.mnn.weight");
            }
        }
    }
    if (!file_exists(root + "/unet.mnn")) missing.emplace_back("unet.mnn");
    if (!file_exists(root + "/unet.mnn.weight")) missing.emplace_back("unet.mnn.weight");
    if (!file_exists(root + "/vae_decoder.mnn")) missing.emplace_back("vae_decoder.mnn");
    if (!file_exists(root + "/vae_decoder.mnn.weight")) missing.emplace_back("vae_decoder.mnn.weight");
    if (missing.empty()) return "";
    std::ostringstream out;
    out << "MNN-Diffusion Stable Diffusion 1.5 bundle is incomplete: ";
    for (size_t i = 0; i < missing.size(); ++i) {
        if (i > 0) out << ", ";
        out << missing[i];
    }
    return out.str();
}

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_DIFFUSION_ASSET_CONTRACT_HPP
