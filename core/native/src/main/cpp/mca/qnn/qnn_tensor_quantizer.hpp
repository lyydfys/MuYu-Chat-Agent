#ifndef MCA_QNN_TENSOR_QUANTIZER_HPP
#define MCA_QNN_TENSOR_QUANTIZER_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"

#if defined(MCA_WITH_QNN_SDK_HEADERS) && MCA_WITH_QNN_SDK_HEADERS
#define MCA_QNN_QUANTIZER_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QNN/QnnTensor.h>)
#define MCA_QNN_QUANTIZER_HAS_SDK 1
#elif !defined(MCA_WITH_QNN_SDK_HEADERS) && __has_include(<QnnTensor.h>)
#define MCA_QNN_QUANTIZER_HAS_SDK 1
#else
#define MCA_QNN_QUANTIZER_HAS_SDK 0
#endif

namespace mca {
namespace qnn {

#if MCA_QNN_QUANTIZER_HAS_SDK

/**
 * Checks if the tensor has valid positive scale in scale-offset quantization.
 */
bool qnn_has_supported_scale_offset_quantization(const Qnn_Tensor_t& tensor);

/**
 * Writes floating-point source buffer into a tensor binding, quantizing if the
 * tensor target dtype is uint16/int16 with scale-offset params.
 */
bool qnn_write_float_tensor(
    QnnTensorBinding* binding,
    const float* values,
    size_t count,
    std::string* error);

/**
 * Writes a scalar 32-bit integer into a tensor binding, quantizing if the target
 * is quantized uint16/ufixed16.
 */
bool qnn_write_int32_tensor(
    QnnTensorBinding* binding,
    int32_t value,
    std::string* error);

/**
 * Writes an array of 32-bit integer values into a tensor binding.
 */
bool qnn_write_int32_vector_tensor(
    QnnTensorBinding* binding,
    const int32_t* values,
    size_t count,
    std::string* error);

/**
 * Reads tensor binding buffer as floating-point vector, dequantizing if the tensor
 * is stored in uint16/int16 scale-offset quantization.
 */
bool qnn_read_float_tensor(
    const QnnTensorBinding& binding,
    std::vector<float>* out,
    std::string* error);

/**
 * Writes diffusion timestep into a tensor binding with automatic dtype handling
 * (float32, ufixed16, sfixed16, int32, uint32, int16, uint16, int8, uint8).
 */
bool qnn_write_timestep_tensor(
    QnnTensorBinding* binding,
    double timestep,
    std::string* error);

/**
 * Finds tensor index matching any of the given token substrings (case-insensitive).
 */
int tensor_index_by_name(
    const std::vector<QnnTensorBinding>& tensors,
    const std::vector<std::string>& tokens);

/**
 * Finds exact tensor index matching one of the accepted canonical contract enum names,
 * reporting an error if duplicate matches are encountered.
 */
int qnn_exact_tensor_index(
    const std::vector<QnnTensorBinding>& tensors,
    const std::vector<std::string>& accepted_names,
    std::string* error);

#endif // MCA_QNN_QUANTIZER_HAS_SDK

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_TENSOR_QUANTIZER_HPP
