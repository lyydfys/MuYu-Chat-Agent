#ifndef MCA_QNN_TENSOR_CODEC_HPP
#define MCA_QNN_TENSOR_CODEC_HPP

#include <cstdint>
#include <string>
#include <vector>

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_TENSOR_CODEC_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnTensor.h>)
#define MCA_QNN_TENSOR_CODEC_HAS_SDK 1
#else
#define MCA_QNN_TENSOR_CODEC_HAS_SDK 0
#endif

#if MCA_QNN_TENSOR_CODEC_HAS_SDK
#include <QNN/QnnCommon.h>
#include <QNN/QnnTypes.h>
#include <QNN/QnnTensor.h>
#include <QNN/System/QnnSystemInterface.h>
#endif

namespace mca {
namespace qnn {

/**
 * Metadata deserialized from QNN context binary header.
 */
struct QnnBinaryMetadata {
    bool attempted = false;
    bool parsed = false;
    bool release_failed = false;
    uint32_t version = 0;
    uint32_t backend_id = 0;
    uint32_t graph_count = 0;
    uint32_t soc_model = 0;
    uint64_t context_blob_size = 0;
    std::string build_id;
    std::string core_api_version;
    std::string backend_api_version;
    std::string soc_version;
    std::string message;
    std::vector<std::string> graph_names;
};

/**
 * Formats QnnBinaryMetadata into standard JSON response format.
 */
std::string binary_metadata_json(const QnnBinaryMetadata& metadata);

#if MCA_QNN_TENSOR_CODEC_HAS_SDK

/**
 * Parses QNN data type enum from canonical string name.
 * Returns true if recognized, false otherwise.
 */
bool qnn_data_type_from_string(const std::string& data_type, Qnn_DataType_t* out);

/**
 * Version-independent accessor for QNN tensor ID.
 */
uint32_t qnn_tensor_id(const Qnn_Tensor_t& tensor);

/**
 * Version-independent accessor for QNN tensor name.
 */
const char* qnn_tensor_name(const Qnn_Tensor_t& tensor);

/**
 * Version-independent accessor for QNN tensor type (APP_READ / APP_WRITE).
 */
Qnn_TensorType_t qnn_tensor_type(const Qnn_Tensor_t& tensor);

/**
 * Version-independent accessor for QNN tensor data type.
 */
Qnn_DataType_t qnn_tensor_data_type(const Qnn_Tensor_t& tensor);

/**
 * Version-independent accessor for QNN tensor quantization params.
 */
Qnn_QuantizeParams_t qnn_tensor_quantize_params(const Qnn_Tensor_t& tensor);

/**
 * Version-independent accessor for QNN tensor rank.
 */
uint32_t qnn_tensor_rank(const Qnn_Tensor_t& tensor);

/**
 * Version-independent accessor for QNN tensor dimensions pointer.
 */
uint32_t* qnn_tensor_dimensions(const Qnn_Tensor_t& tensor);

/**
 * Configures raw client buffer pointer and size on a QNN tensor (v1 or v2).
 */
void qnn_set_tensor_raw_buffer(Qnn_Tensor_t* tensor, void* buffer, uint32_t bytes);

/**
 * Reads raw client buffer pointer and size from a QNN tensor (v1 or v2).
 */
const void* qnn_read_tensor_raw_buffer(const Qnn_Tensor_t* tensor, uint32_t* bytes_out = nullptr);
const void* qnn_read_tensor_raw_buffer(const Qnn_Tensor_t& tensor, uint32_t* bytes_out = nullptr);

/**
 * Returns byte footprint per element for a QNN data type.
 */
uint32_t qnn_element_bytes(Qnn_DataType_t type);

/**
 * Calculates tensor buffer size in bytes from rank, dimensions, and element type.
 */
uint64_t qnn_fallback_tensor_bytes(const Qnn_Tensor_t& tensor);

/**
 * Determines tensor buffer byte size via System API footprint query, falling back
 * to rank/dimension calculation if unavailable.
 */
uint64_t qnn_tensor_bytes(
        const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
        const Qnn_Tensor_t& tensor);

/**
 * Resolves quantized zero point for uint16 scale-offset encoding.
 */
uint16_t qnn_quantized_zero_u16(const Qnn_Tensor_t& tensor);

/**
 * Fills smoke test input buffer with appropriate initial values
 * (timestep = 1 for time inputs, quantized zero for ufixed16, etc.).
 */
void qnn_fill_smoke_input_buffer(const Qnn_Tensor_t& tensor, std::vector<uint8_t>* buffer);

#endif // MCA_QNN_TENSOR_CODEC_HAS_SDK

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_TENSOR_CODEC_HPP
