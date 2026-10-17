#include "qnn_htp_controller.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

#include <sstream>

namespace mca {
namespace qnn {

QnnDeviceCreateOutcome create_qnn_device_or_default(
        const QNN_INTERFACE_VER_TYPE& api,
        uint32_t soc_model,
        Qnn_LogHandle_t log_handle) {
    QnnDeviceCreateOutcome outcome;
    if (api.deviceCreate == nullptr) {
        outcome.context_default_device = true;
        outcome.message =
            "QNN deviceCreate API is unavailable; using NULL device for context default affinity.";
        return outcome;
    }

    std::vector<std::string> attempts;
    auto try_create = [&](const char* label, const QnnDevice_Config_t** configs) -> bool {
        Qnn_DeviceHandle_t candidate = nullptr;
        const Qnn_ErrorHandle_t status = api.deviceCreate(log_handle, configs, &candidate);
        if (status == QNN_SUCCESS && candidate != nullptr) {
            outcome.device = candidate;
            outcome.created = true;
            outcome.context_default_device = false;
            outcome.message = std::string("QNN deviceCreate succeeded with ") + label + ".";
            return true;
        }
        std::ostringstream attempt;
        attempt << label << ": " << qnn_error_to_string(api, status);
        if (status == QNN_SUCCESS && candidate == nullptr) {
            attempt << " but returned null device";
        }
        attempts.push_back(attempt.str());
        return false;
    };

    if (try_create("default config", nullptr)) return outcome;
    if (!attempts.empty() &&
        attempts.back().find("QNN_SUCCESS") != std::string::npos &&
        attempts.back().find("null device") != std::string::npos) {
        outcome.context_default_device = true;
        outcome.message =
            "QNN deviceCreate returned success without a device handle; using NULL device for context default affinity.";
        return outcome;
    }

    auto make_custom_config = [](QnnHtpDevice_CustomConfig_t* custom) {
        QnnDevice_Config_t config = QNN_DEVICE_CONFIG_INIT;
        config.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
        config.customConfig = custom;
        return config;
    };

    if (soc_model != 0) {
        QnnHtpDevice_CustomConfig_t soc = {};
        soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
        soc.socModel = soc_model;
        QnnDevice_Config_t soc_config = make_custom_config(&soc);
        const QnnDevice_Config_t* configs[] = {&soc_config, nullptr};
        std::ostringstream label;
        label << "HTP SOC=" << soc_model;
        if (try_create(label.str().c_str(), configs)) return outcome;
    }

    const QnnHtpDevice_Arch_t preferred_arch = htp_arch_for_soc_model(soc_model);
    const uint32_t device_ids[] = {QNN_DEVICE_DEFAULT_DEVICE_ID, 0u};
    if (preferred_arch != QNN_HTP_DEVICE_ARCH_NONE) {
        for (uint32_t device_id : device_ids) {
            QnnHtpDevice_CustomConfig_t arch = {};
            arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
            arch.arch.deviceId = device_id;
            arch.arch.arch = preferred_arch;
            QnnDevice_Config_t arch_config = make_custom_config(&arch);
            const QnnDevice_Config_t* configs[] = {&arch_config, nullptr};
            std::ostringstream label;
            label << "HTP ARCH=" << htp_arch_name(preferred_arch) << " deviceId=" << device_id;
            if (try_create(label.str().c_str(), configs)) return outcome;
        }

        if (soc_model != 0) {
            for (uint32_t device_id : device_ids) {
                QnnHtpDevice_CustomConfig_t soc = {};
                soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
                soc.socModel = soc_model;
                QnnHtpDevice_CustomConfig_t arch = {};
                arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
                arch.arch.deviceId = device_id;
                arch.arch.arch = preferred_arch;
                QnnDevice_Config_t soc_config = make_custom_config(&soc);
                QnnDevice_Config_t arch_config = make_custom_config(&arch);
                const QnnDevice_Config_t* configs[] = {&soc_config, &arch_config, nullptr};
                std::ostringstream label;
                label << "HTP SOC=" << soc_model << " ARCH=" << htp_arch_name(preferred_arch)
                      << " deviceId=" << device_id;
                if (try_create(label.str().c_str(), configs)) return outcome;
            }
        }
    }

    for (uint32_t device_id : device_ids) {
        for (bool signed_pd : {false, true}) {
            QnnHtpDevice_CustomConfig_t signed_pd_config_value = {};
            signed_pd_config_value.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
            signed_pd_config_value.useSignedProcessDomain.deviceId = device_id;
            signed_pd_config_value.useSignedProcessDomain.useSignedProcessDomain = signed_pd;
            QnnDevice_Config_t signed_config = make_custom_config(&signed_pd_config_value);
            const QnnDevice_Config_t* configs[] = {&signed_config, nullptr};
            std::ostringstream label;
            label << "HTP SIGNEDPD=" << (signed_pd ? "true" : "false")
                  << " deviceId=" << device_id;
            if (try_create(label.str().c_str(), configs)) return outcome;
        }
    }

    if (soc_model != 0 && preferred_arch != QNN_HTP_DEVICE_ARCH_NONE) {
        for (uint32_t device_id : device_ids) {
            for (bool signed_pd : {false, true}) {
                QnnHtpDevice_CustomConfig_t soc = {};
                soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
                soc.socModel = soc_model;
                QnnHtpDevice_CustomConfig_t arch = {};
                arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
                arch.arch.deviceId = device_id;
                arch.arch.arch = preferred_arch;
                QnnHtpDevice_CustomConfig_t signed_pd_config_value = {};
                signed_pd_config_value.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
                signed_pd_config_value.useSignedProcessDomain.deviceId = device_id;
                signed_pd_config_value.useSignedProcessDomain.useSignedProcessDomain = signed_pd;
                QnnDevice_Config_t soc_config = make_custom_config(&soc);
                QnnDevice_Config_t arch_config = make_custom_config(&arch);
                QnnDevice_Config_t signed_config = make_custom_config(&signed_pd_config_value);
                const QnnDevice_Config_t* configs[] = {
                    &soc_config,
                    &arch_config,
                    &signed_config,
                    nullptr
                };
                std::ostringstream label;
                label << "HTP SOC=" << soc_model << " ARCH=" << htp_arch_name(preferred_arch)
                      << " SIGNEDPD="
                      << (signed_pd ? "true" : "false") << " deviceId=" << device_id;
                if (try_create(label.str().c_str(), configs)) return outcome;
            }
        }
    }

    outcome.context_default_device = true;
    std::ostringstream message;
    message << "QNN deviceCreate failed for all tested configs";
    if (soc_model != 0) message << " (context socModel=" << soc_model << ")";
    message << "; continuing with NULL device for context default affinity. Attempts: ";
    for (size_t i = 0; i < attempts.size(); ++i) {
        if (i > 0) message << " | ";
        message << attempts[i];
    }
    outcome.message = message.str();
    outcome.device = nullptr;
    return outcome;
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
