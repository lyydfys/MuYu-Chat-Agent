#ifndef MCA_QNN_GRAPH_EXECUTOR_HELPER_HPP
#define MCA_QNN_GRAPH_EXECUTOR_HELPER_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_tensor_inspect.hpp"

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_EXECUTOR_HELPER_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnSystemInterface.h>)
#define MCA_QNN_EXECUTOR_HELPER_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QnnSystemInterface.h>)
#define MCA_QNN_EXECUTOR_HELPER_HAS_SDK 1
#else
#define MCA_QNN_EXECUTOR_HELPER_HAS_SDK 0
#endif

#if MCA_QNN_EXECUTOR_HELPER_HAS_SDK
#if __has_include(<QNN/QnnCommon.h>)
#include <QNN/QnnCommon.h>
#include <QNN/QnnTypes.h>
#include <QNN/QnnGraph.h>
#include <QNN/System/QnnSystemInterface.h>
#else
#include <QnnCommon.h>
#include <QnnTypes.h>
#include <QnnGraph.h>
#include <QnnSystemInterface.h>
#endif
#endif

namespace mca {
namespace qnn {

/**
 * Performance and execution telemetry metrics for a QNN graph execution run.
 */
struct QnnGraphExecutionMetrics {
    std::string graph_name;
    long long execute_ms = 0;
    size_t input_count = 0;
    size_t output_count = 0;
    long long nonzero_output_elements = 0;
    uint64_t output_checksum = 0;
    bool finite_outputs = true;
    bool execution_success = false;
    std::string stage;
    std::string error_message;
};

/**
 * Computes simple bit-cast checksum of a float vector.
 */
uint64_t checksum_float_vector(const std::vector<float>& values);

/**
 * Formats 64-bit integer into 16-character fixed-width lower-case hexadecimal string.
 */
std::string fixed_width_lower_hex_u64(uint64_t value);

/**
 * Serializes float vector distribution statistics (count, finite, min, max, mean) to JSON.
 */
std::string float_vector_stats_json(const std::vector<float>& values);

#if MCA_QNN_EXECUTOR_HELPER_HAS_SDK

/**
 * Serializes tensor dimensions to JSON array.
 */
std::string qnn_tensor_shape_json(const QnnTensorBinding& binding);

/**
 * Serializes tensor binding debug metadata (name, dtype, shape, bytes, quant) to JSON object.
 */
std::string qnn_tensor_debug_json(const QnnTensorBinding& binding);

/**
 * Serializes a list of tensor bindings to JSON array of debug objects.
 */
std::string qnn_tensor_list_debug_json(const std::vector<QnnTensorBinding>& tensors);

/**
 * Allocates and binds buffers for a list of QNN tensors using system interface footprint.
 */
bool qnn_bind_graph_tensors(
    const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
    Qnn_Tensor_t* source,
    uint32_t count,
    bool input,
    std::vector<QnnTensorBinding>* destination,
    std::string* error = nullptr);

/**
 * Formats QnnGraphExecutionMetrics into JSON string.
 */
std::string graph_execution_metrics_json(const QnnGraphExecutionMetrics& metrics);

#endif // MCA_QNN_EXECUTOR_HELPER_HAS_SDK

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_GRAPH_EXECUTOR_HELPER_HPP
