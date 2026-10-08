#ifndef MCA_QNN_TENSOR_INSPECT_HPP
#define MCA_QNN_TENSOR_INSPECT_HPP

#include <cstdint>
#include <string>
#include <vector>

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_TENSOR_INSPECT_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnTensor.h>)
#define MCA_QNN_TENSOR_INSPECT_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QnnTensor.h>)
#define MCA_QNN_TENSOR_INSPECT_HAS_SDK 1
#else
#define MCA_QNN_TENSOR_INSPECT_HAS_SDK 0
#endif

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK
#if __has_include(<QNN/QnnCommon.h>)
#include <QNN/QnnCommon.h>
#include <QNN/QnnTypes.h>
#include <QNN/QnnTensor.h>
#else
#include <QnnCommon.h>
#include <QnnTypes.h>
#include <QnnTensor.h>
#endif
#endif

namespace mca {
namespace qnn {

/**
 * Plan describing tensor shape, data type, element contract and support status.
 */
struct NativeTensorPlan {
    std::string name;
    std::string role;
    std::string data_type;
    std::vector<uint32_t> shape;
    long long element_count = 0;
    int bytes_per_element = 0;
    long long byte_size = 0;
    bool supported = false;
    std::string reason;
};

/**
 * Normalizes tensor data type name (e.g. fp16 -> float16).
 */
std::string normalized_smoke_data_type(std::string value);

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

/**
 * Wrapper binding for a QNN tensor with owned dimensions and backing storage buffer.
 */
struct QnnTensorBinding {
    Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
    std::vector<uint32_t> dimensions;
    std::vector<uint8_t> buffer;
    std::string name;
};

/**
 * Returns human-readable string name for a QNN data type enum.
 */
std::string qnn_data_type_name(Qnn_DataType_t type);

/**
 * Returns human-readable string name for a QNN quantization encoding enum.
 */
std::string qnn_quant_encoding_name(Qnn_QuantizationEncoding_t encoding);

/**
 * Maps QNN data type enum to canonical smoke storage data type string.
 */
std::string qnn_smoke_storage_data_type(Qnn_DataType_t type);

/**
 * Computes total number of elements in a tensor from its rank and dimensions.
 */
uint64_t qnn_tensor_element_count(const Qnn_Tensor_t& tensor);

/**
 * Copies tensor dimensions into an owned vector and updates the tensor's dimensions pointer.
 */
void qnn_copy_tensor_dimensions(Qnn_Tensor_t* tensor, std::vector<uint32_t>* owned_dimensions);

/**
 * Dequantizes an integer value to float using scale-offset encoding params if defined.
 */
float qnn_dequantize_value(const Qnn_Tensor_t& tensor, int64_t quantized);

/**
 * Quantizes a float value to integer using scale-offset encoding params if defined.
 */
int64_t qnn_quantize_value(const Qnn_Tensor_t& tensor, float value);

/**
 * Validates that a QNN tensor matches expected plan metadata (name, type, shape, elements, bytes).
 */
bool qnn_smoke_tensor_matches_plan(
        const Qnn_Tensor_t& tensor,
        const NativeTensorPlan& plan,
        std::string* error = nullptr);

/**
 * Validates that a collection of QNN tensors matches a set of expected plans.
 */
bool qnn_smoke_tensor_set_matches_plans(
        const Qnn_Tensor_t* tensors,
        uint32_t tensor_count,
        const std::vector<NativeTensorPlan>& plans,
        const char* role,
        std::string* error = nullptr);

/**
 * Validates smoke output buffer elements and finite numeric bounds.
 */
bool qnn_smoke_output_buffer_valid(
        const Qnn_Tensor_t& tensor,
        const std::vector<uint8_t>& buffer,
        long long* nonzero_elements,
        std::string* error = nullptr);

/**
 * Checks if a binding's buffer has sufficient capacity for the required elements.
 */
bool qnn_tensor_buffer_has_capacity(
        const QnnTensorBinding& binding,
        uint64_t element_count,
        size_t bytes_per_element,
        const char* value_kind,
        std::string* error = nullptr);

#endif // MCA_QNN_TENSOR_INSPECT_HAS_SDK

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_TENSOR_INSPECT_HPP
