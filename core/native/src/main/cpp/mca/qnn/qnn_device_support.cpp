#include "mca/qnn/qnn_device_support.hpp"

#include <android/log.h>
#include <cstdarg>
#include <iomanip>
#include <sstream>
#include <string>
#include <utility>

#include "qnn_soc_htp_mapping.hpp"

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "MCA-QNN";
} // namespace

std::string append_diagnostic_note(std::string message, const std::string& note) {
    if (note.empty()) return message;
    if (!message.empty()) message += " ";
    message += note;
    return message;
}

#if MCA_QNN_DEVICE_SUPPORT_HAS_SDK

void qnn_android_log_callback(const char* fmt, QnnLog_Level_t level, uint64_t /*timestamp*/, va_list args) {
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case QNN_LOG_LEVEL_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        case QNN_LOG_LEVEL_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case QNN_LOG_LEVEL_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        case QNN_LOG_LEVEL_VERBOSE:
            priority = ANDROID_LOG_VERBOSE;
            break;
        default:
            priority = ANDROID_LOG_DEBUG;
            break;
    }
    __android_log_vprint(priority, kTag, fmt, args);
}

QnnLogSession::QnnLogSession(QnnLogSession&& other) noexcept {
    api = other.api;
    handle = other.handle;
    other.api = nullptr;
    other.handle = nullptr;
}

QnnLogSession& QnnLogSession::operator=(QnnLogSession&& other) noexcept {
    if (this != &other) {
        reset();
        api = other.api;
        handle = other.handle;
        other.api = nullptr;
        other.handle = nullptr;
    }
    return *this;
}

QnnLogSession::~QnnLogSession() {
    reset();
}

bool QnnLogSession::reset_checked(std::string* error) {
    if (handle == nullptr) {
        api = nullptr;
        return true;
    }
    if (api == nullptr || api->logFree == nullptr) {
        if (error != nullptr) {
            *error = "QNN logFree is unavailable for a live log handle.";
        }
        return false;
    }
    const Qnn_ErrorHandle_t status = api->logFree(handle);
    if (status != QNN_SUCCESS) {
        if (error != nullptr) {
            std::ostringstream message;
            message << "QNN logFree failed: 0x" << std::hex << status;
            *error = message.str();
        }
        return false;
    }
    api = nullptr;
    handle = nullptr;
    return true;
}

void QnnLogSession::abandon_without_release() {
    api = nullptr;
    handle = nullptr;
}

void QnnLogSession::reset() {
    std::string ignored;
    reset_checked(&ignored);
}

QnnLogSession create_qnn_log_session(const QNN_INTERFACE_VER_TYPE& api) {
    QnnLogSession session;
    session.api = &api;
    if (api.logCreate != nullptr) {
        Qnn_LogHandle_t log = nullptr;
        const Qnn_ErrorHandle_t status =
            api.logCreate(qnn_android_log_callback, QNN_LOG_LEVEL_VERBOSE, &log);
        if (status == QNN_SUCCESS && log != nullptr) {
            session.handle = log;
        } else {
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "QNN logCreate failed: 0x%x",
                static_cast<unsigned int>(status));
        }
    }
    return session;
}

std::string qnn_error_to_string(const QNN_INTERFACE_VER_TYPE& api, Qnn_ErrorHandle_t error) {
    if (error == QNN_SUCCESS) return "QNN_SUCCESS";
    std::ostringstream suffix;
    suffix << "0x" << std::hex << error;
    if (api.errorGetMessage != nullptr) {
        const char* message = nullptr;
        if (api.errorGetMessage(error, &message) == QNN_SUCCESS && message != nullptr) {
            return std::string(message) + " (" + suffix.str() + ")";
        }
    }
    return "QNN error " + suffix.str();
}

// Context binaries carry the QAIRT QNN_SOC_MODEL_* value, not an HTP architecture.
// Never use a newer architecture as a generic fallback: QNN rejects an incompatible
// ARCH custom config before it can create the device on older Snapdragon hardware.
QnnHtpDevice_Arch_t htp_arch_for_soc_model(uint32_t soc_model) {
    switch (mca::qnn::htp_arch_version_for_soc_model(soc_model)) {
        case 68: return QNN_HTP_DEVICE_ARCH_V68;
        case 69: return QNN_HTP_DEVICE_ARCH_V69;
        case 73: return QNN_HTP_DEVICE_ARCH_V73;
        case 75: return QNN_HTP_DEVICE_ARCH_V75;
        case 79: return QNN_HTP_DEVICE_ARCH_V79;
        case 81: return QNN_HTP_DEVICE_ARCH_V81;
        default: return QNN_HTP_DEVICE_ARCH_NONE;
    }
}

const char* htp_arch_name(QnnHtpDevice_Arch_t arch) {
    switch (arch) {
        case QNN_HTP_DEVICE_ARCH_V68: return "V68";
        case QNN_HTP_DEVICE_ARCH_V69: return "V69";
        case QNN_HTP_DEVICE_ARCH_V73: return "V73";
        case QNN_HTP_DEVICE_ARCH_V75: return "V75";
        case QNN_HTP_DEVICE_ARCH_V79: return "V79";
        case QNN_HTP_DEVICE_ARCH_V81: return "V81";
        default: return "unknown";
    }
}

#endif // MCA_QNN_DEVICE_SUPPORT_HAS_SDK

} // namespace qnn
} // namespace mca
