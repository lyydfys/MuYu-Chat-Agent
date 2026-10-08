#ifndef MCA_QNN_DEVICE_SUPPORT_HPP
#define MCA_QNN_DEVICE_SUPPORT_HPP

#include <cstdarg>
#include <cstdint>
#include <string>

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_DEVICE_SUPPORT_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnInterface.h>)
#define MCA_QNN_DEVICE_SUPPORT_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QnnInterface.h>)
#define MCA_QNN_DEVICE_SUPPORT_HAS_SDK 1
#else
#define MCA_QNN_DEVICE_SUPPORT_HAS_SDK 0
#endif

#if MCA_QNN_DEVICE_SUPPORT_HAS_SDK
#if __has_include(<QNN/QnnCommon.h>)
#include <QNN/QnnCommon.h>
#include <QNN/QnnTypes.h>
#include <QNN/QnnLog.h>
#include <QNN/QnnInterface.h>
#include <QNN/HTP/QnnHtpDevice.h>
#else
#include <QnnCommon.h>
#include <QnnTypes.h>
#include <QnnLog.h>
#include <QnnInterface.h>
#include <HTP/QnnHtpDevice.h>
#endif
#endif

namespace mca {
namespace qnn {

/**
 * Appends a diagnostic note to an existing message if the note is non-empty.
 */
std::string append_diagnostic_note(std::string message, const std::string& note);

#if MCA_QNN_DEVICE_SUPPORT_HAS_SDK

/**
 * QNN Android log callback forwarding QNN engine logs to Android logcat.
 */
void qnn_android_log_callback(const char* fmt, QnnLog_Level_t level, uint64_t timestamp, va_list args);

/**
 * RAII wrapper managing QNN log handle lifecycle.
 */
struct QnnLogSession {
    const QNN_INTERFACE_VER_TYPE* api = nullptr;
    Qnn_LogHandle_t handle = nullptr;

    QnnLogSession() = default;
    QnnLogSession(const QnnLogSession&) = delete;
    QnnLogSession& operator=(const QnnLogSession&) = delete;

    QnnLogSession(QnnLogSession&& other) noexcept;
    QnnLogSession& operator=(QnnLogSession&& other) noexcept;
    ~QnnLogSession();

    bool reset_checked(std::string* error);
    void abandon_without_release();
    void reset();
};

/**
 * Creates a QnnLogSession initialized with the provided QNN interface API.
 */
QnnLogSession create_qnn_log_session(const QNN_INTERFACE_VER_TYPE& api);

/**
 * Formats a QNN error handle into a human-readable diagnostic string.
 */
std::string qnn_error_to_string(const QNN_INTERFACE_VER_TYPE& api, Qnn_ErrorHandle_t error);

/**
 * Maps Qualcomm SoC model ID to the corresponding HTP architecture.
 */
QnnHtpDevice_Arch_t htp_arch_for_soc_model(uint32_t soc_model);

/**
 * Returns human-readable name of an HTP architecture enum.
 */
const char* htp_arch_name(QnnHtpDevice_Arch_t arch);

#endif // MCA_QNN_DEVICE_SUPPORT_HAS_SDK

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_DEVICE_SUPPORT_HPP
