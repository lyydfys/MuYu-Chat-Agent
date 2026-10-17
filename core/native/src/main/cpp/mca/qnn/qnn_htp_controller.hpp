#ifndef MCA_QNN_HTP_CONTROLLER_HPP
#define MCA_QNN_HTP_CONTROLLER_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_device_support.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

#if __has_include(<QNN/HTP/QnnHtpDevice.h>)
#include <QNN/HTP/QnnHtpDevice.h>
#elif __has_include(<QnnHtpDevice.h>)
#include <QnnHtpDevice.h>
#endif

namespace mca {
namespace qnn {

struct QnnDeviceCreateOutcome {
    Qnn_DeviceHandle_t device = nullptr;
    bool created = false;
    bool context_default_device = false;
    bool fatal = false;
    std::string message;
};

QnnDeviceCreateOutcome create_qnn_device_or_default(
        const QNN_INTERFACE_VER_TYPE& api,
        uint32_t soc_model = 0,
        Qnn_LogHandle_t log_handle = nullptr);

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

#endif  // MCA_QNN_HTP_CONTROLLER_HPP
