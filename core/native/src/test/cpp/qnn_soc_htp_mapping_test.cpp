#include "qnn_soc_htp_mapping.hpp"

#include <array>
#include <iostream>

namespace {

struct SocModelCase {
    uint32_t soc_model;
    int expected_htp;
};

struct SocHintCase {
    const char* hint;
    int expected_htp;
};

}  // namespace

int main() {
    constexpr std::array<SocModelCase, 10> soc_models{{
        {30, 68},
        {36, 69},
        {42, 69},
        {43, 73},
        {66, 73},
        {57, 75},
        {68, 75},
        {69, 79},
        {85, 79},
        {87, 81},
    }};
    for (const auto& test_case : soc_models) {
        const int actual = mca::qnn::htp_arch_version_for_soc_model(test_case.soc_model);
        if (actual != test_case.expected_htp) {
            std::cerr << "QNN SoC model " << test_case.soc_model
                      << " mapped to HTP V" << actual << ", expected V"
                      << test_case.expected_htp << '\n';
            return 1;
        }
    }

    constexpr std::array<SocHintCase, 23> soc_hints{{
        {"SM8350", 68},
        {"SM8350P", 68},
        {"SM8450", 69},
        {"SM8475", 69},
        {"SM8550P", 73},
        {"QCS8550", 73},
        {"QCM8550", 73},
        {"SM8650", 75},
        {"SM8635", 75},
        {"SM8750P", 79},
        {"SM8735", 79},
        {"SM8850P", 81},
        {"Snapdragon 8 Elite Gen 5", 81},
        {"Snapdragon 8 Elite", 79},
        {"Snapdragon 8s Gen 4", 79},
        {"Snapdragon 8 Gen 3", 75},
        {"Snapdragon 8 Gen 2", 73},
        {"43", 73},
        {" 85 ", 79},
        {"87", 81},
        {"SM9999", 0},
        {"not-a-soc", 0},
        {"", 0},
    }};
    for (const auto& test_case : soc_hints) {
        const int actual = mca::qnn::htp_arch_version_for_soc_hint(test_case.hint);
        if (actual != test_case.expected_htp) {
            std::cerr << "SoC hint '" << test_case.hint << "' mapped to HTP V"
                      << actual << ", expected V" << test_case.expected_htp << '\n';
            return 1;
        }
    }

    if (mca::qnn::htp_arch_version_for_soc_model(9999) != 0) {
        std::cerr << "Unknown SoC identifiers must remain unknown.\n";
        return 1;
    }
    return 0;
}
