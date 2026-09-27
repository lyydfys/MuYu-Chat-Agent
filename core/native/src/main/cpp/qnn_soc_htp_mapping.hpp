#pragma once

#include <charconv>
#include <cctype>
#include <cstdint>
#include <string>

// Keep SoC-to-HTP mapping independent of Android and QNN headers so the
// production bridge and host contract tests exercise the same table.
namespace mca::qnn {

inline int htp_arch_version_for_soc_model(uint32_t soc_model) noexcept {
    switch (soc_model) {
        case 30: return 68;  // SM8350 / Snapdragon 888
        case 36:             // SM8450 / Snapdragon 8 Gen 1
        case 42: return 69;  // SM8475 / Snapdragon 8+ Gen 1
        case 43:             // SM8550 / Snapdragon 8 Gen 2
        case 66: return 73;  // QCS/QCM8550
        case 57:             // SM8650 / Snapdragon 8 Gen 3
        case 68: return 75;  // SM8635
        case 69:             // SM8750 / Snapdragon 8 Elite
        case 85: return 79;  // SM8735 / Snapdragon 8s Gen 4
        case 87: return 81;  // SM8850 / Snapdragon 8 Elite Gen 5
        default: return 0;
    }
}

inline int htp_arch_version_for_soc_hint(const std::string& raw_hint) {
    std::string hint;
    hint.reserve(raw_hint.size());
    for (const unsigned char character : raw_hint) {
        if (std::isspace(character)) continue;
        hint.push_back(static_cast<char>(std::tolower(character)));
    }
    if (hint.empty()) return 0;

    uint32_t numeric_soc_model = 0;
    const auto parsed = std::from_chars(
        hint.data(), hint.data() + hint.size(), numeric_soc_model);
    if (parsed.ec == std::errc{} && parsed.ptr == hint.data() + hint.size()) {
        return htp_arch_version_for_soc_model(numeric_soc_model);
    }

    if (hint.find("sm8850") != std::string::npos) return 81;
    if (hint.find("sm8750") != std::string::npos ||
        hint.find("sm8735") != std::string::npos) return 79;
    if (hint.find("sm8650") != std::string::npos ||
        hint.find("sm8635") != std::string::npos) return 75;
    if (hint.find("sm8550") != std::string::npos ||
        hint.find("qcs8550") != std::string::npos ||
        hint.find("qcm8550") != std::string::npos) return 73;
    if (hint.find("sm8475") != std::string::npos ||
        hint.find("sm8450") != std::string::npos) return 69;
    if (hint.find("sm8350") != std::string::npos) return 68;
    // Some OEM builds expose the marketing name instead of the Qualcomm
    // internal part number. Keep these aliases exact enough to avoid treating
    // an unrelated "Gen 2" string as a Snapdragon SoC.
    if (hint.find("snapdragon8elitegen5") != std::string::npos ||
        hint.find("snapdragon8gen5") != std::string::npos ||
        hint.find("8elitegen5") != std::string::npos) return 81;
    if (hint.find("snapdragon8elite") != std::string::npos ||
        hint.find("8elite") != std::string::npos) return 79;
    if (hint.find("snapdragon8sgen4") != std::string::npos ||
        hint.find("8sgen4") != std::string::npos) return 79;
    if (hint.find("snapdragon8gen3") != std::string::npos ||
        hint.find("8gen3") != std::string::npos) return 75;
    if (hint.find("snapdragon8gen2") != std::string::npos ||
        hint.find("8gen2") != std::string::npos) return 73;
    return 0;
}

}  // namespace mca::qnn
