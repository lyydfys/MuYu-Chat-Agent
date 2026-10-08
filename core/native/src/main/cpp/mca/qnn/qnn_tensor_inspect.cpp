#include "mca/qnn/qnn_tensor_inspect.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <limits>
#include <sstream>

#include "mca/qnn/qnn_tensor_codec.hpp"

namespace mca {
namespace qnn {

std::string normalized_smoke_data_type(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    if (value == "fp16") return "float16";
    if (value == "fp32") return "float32";
    if (value == "fp64") return "float64";
    return value;
}

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

std::string qnn_data_type_name(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_INT_8: return "int8";
        case QNN_DATATYPE_UINT_8: return "uint8";
        case QNN_DATATYPE_INT_16: return "int16";
        case QNN_DATATYPE_UINT_16: return "uint16";
        case QNN_DATATYPE_INT_32: return "int32";
        case QNN_DATATYPE_UINT_32: return "uint32";
        case QNN_DATATYPE_INT_64: return "int64";
        case QNN_DATATYPE_UINT_64: return "uint64";
        case QNN_DATATYPE_FLOAT_16: return "float16";
        case QNN_DATATYPE_BFLOAT_16: return "bfloat16";
        case QNN_DATATYPE_FLOAT_32: return "float32";
        case QNN_DATATYPE_FLOAT_64: return "float64";
        case QNN_DATATYPE_BOOL_8: return "bool";
        case QNN_DATATYPE_UFIXED_POINT_8: return "ufixed8";
        case QNN_DATATYPE_SFIXED_POINT_8: return "sfixed8";
        case QNN_DATATYPE_UFIXED_POINT_16: return "ufixed16";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sfixed16";
        case QNN_DATATYPE_UFIXED_POINT_32: return "ufixed32";
        case QNN_DATATYPE_SFIXED_POINT_32: return "sfixed32";
        default: return "unknown";
    }
}

std::string qnn_quant_encoding_name(Qnn_QuantizationEncoding_t encoding) {
    switch (encoding) {
        case QNN_QUANTIZATION_ENCODING_SCALE_OFFSET: return "scale_offset";
        case QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET: return "axis_scale_offset";
        case QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET: return "bw_scale_offset";
        case QNN_QUANTIZATION_ENCODING_BW_AXIS_SCALE_OFFSET: return "bw_axis_scale_offset";
        case QNN_QUANTIZATION_ENCODING_BLOCK: return "block";
        case QNN_QUANTIZATION_ENCODING_BLOCKWISE_EXPANSION: return "blockwise_expansion";
        case QNN_QUANTIZATION_ENCODING_VECTOR: return "vector";
        case QNN_QUANTIZATION_ENCODING_FLOAT_BLOCK: return "float_block";
        case QNN_QUANTIZATION_ENCODING_MICROSCALING: return "microscaling";
        case QNN_QUANTIZATION_ENCODING_UNDEFINED: return "undefined";
        default: return "other";
    }
}

std::string qnn_smoke_storage_data_type(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_SFIXED_POINT_8: return "int8";
        case QNN_DATATYPE_UFIXED_POINT_8: return "uint8";
        case QNN_DATATYPE_SFIXED_POINT_16: return "int16";
        case QNN_DATATYPE_UFIXED_POINT_16: return "uint16";
        case QNN_DATATYPE_SFIXED_POINT_32: return "int32";
        case QNN_DATATYPE_UFIXED_POINT_32: return "uint32";
        case QNN_DATATYPE_BOOL_8: return "bool";
        default: return normalized_smoke_data_type(qnn_data_type_name(type));
    }
}

uint64_t qnn_tensor_element_count(const Qnn_Tensor_t& tensor) {
    const uint32_t rank = qnn_tensor_rank(tensor);
    const uint32_t* dims = qnn_tensor_dimensions(tensor);
    if (rank == 0 || dims == nullptr) return 0;
    uint64_t elements = 1;
    for (uint32_t i = 0; i < rank; ++i) {
        if (dims[i] == 0 || elements > std::numeric_limits<uint64_t>::max() / dims[i]) {
            return 0;
        }
        elements *= dims[i];
    }
    return elements;
}

void qnn_copy_tensor_dimensions(Qnn_Tensor_t* tensor, std::vector<uint32_t>* owned_dimensions) {
    if (tensor == nullptr || owned_dimensions == nullptr) return;
    owned_dimensions->clear();
    const uint32_t rank = qnn_tensor_rank(*tensor);
    uint32_t* source = qnn_tensor_dimensions(*tensor);
    if (rank == 0 || source == nullptr) return;
    owned_dimensions->assign(source, source + rank);
    if (tensor->version == QNN_TENSOR_VERSION_2) {
        tensor->v2.dimensions = owned_dimensions->data();
    } else {
        tensor->v1.dimensions = owned_dimensions->data();
    }
}

float qnn_dequantize_value(const Qnn_Tensor_t& tensor, int64_t quantized) {
    const auto quant = qnn_tensor_quantize_params(tensor);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        return (static_cast<float>(quantized) +
                static_cast<float>(quant.scaleOffsetEncoding.offset)) *
            quant.scaleOffsetEncoding.scale;
    }
    return static_cast<float>(quantized);
}

int64_t qnn_quantize_value(const Qnn_Tensor_t& tensor, float value) {
    const auto quant = qnn_tensor_quantize_params(tensor);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET &&
        quant.scaleOffsetEncoding.scale != 0.0f) {
        const double transformed =
            static_cast<double>(value) /
                static_cast<double>(quant.scaleOffsetEncoding.scale) -
            static_cast<double>(quant.scaleOffsetEncoding.offset);
        if (!std::isfinite(transformed)) {
            return std::signbit(transformed)
                ? std::numeric_limits<int64_t>::min()
                : std::numeric_limits<int64_t>::max();
        }
        if (transformed >= static_cast<double>(std::numeric_limits<int64_t>::max())) {
            return std::numeric_limits<int64_t>::max();
        }
        if (transformed <= static_cast<double>(std::numeric_limits<int64_t>::min())) {
            return std::numeric_limits<int64_t>::min();
        }
        return static_cast<int64_t>(std::llround(transformed));
    }
    return static_cast<int64_t>(std::llround(value));
}

bool qnn_smoke_tensor_matches_plan(
        const Qnn_Tensor_t& tensor,
        const NativeTensorPlan& plan,
        std::string* error) {
    const std::string actual_name = qnn_tensor_name(tensor) != nullptr
        ? qnn_tensor_name(tensor)
        : "";
    if (actual_name != plan.name) {
        if (error != nullptr) {
            *error = "QNN smoke tensor name mismatch: expected " + plan.name +
                ", got " + actual_name + ".";
        }
        return false;
    }
    const std::string actual_type = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(tensor));
    const std::string expected_type = normalized_smoke_data_type(plan.data_type);
    if (actual_type != expected_type) {
        if (error != nullptr) {
            *error = "QNN smoke tensor dtype mismatch for " + plan.name +
                ": expected " + expected_type + ", got " + actual_type + ".";
        }
        return false;
    }
    const uint32_t rank = qnn_tensor_rank(tensor);
    uint32_t* dimensions = qnn_tensor_dimensions(tensor);
    if (dimensions == nullptr || rank != plan.shape.size() ||
        !std::equal(plan.shape.begin(), plan.shape.end(), dimensions)) {
        if (error != nullptr) {
            *error = "QNN smoke tensor shape mismatch for " + plan.name + ".";
        }
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(tensor);
    const uint64_t logical_bytes = qnn_fallback_tensor_bytes(tensor);
    if (element_count == 0U ||
        element_count != static_cast<uint64_t>(plan.element_count) ||
        logical_bytes != static_cast<uint64_t>(plan.byte_size)) {
        if (error != nullptr) {
            *error = "QNN smoke tensor element/byte contract mismatch for " + plan.name + ".";
        }
        return false;
    }
    return true;
}

bool qnn_smoke_tensor_set_matches_plans(
        const Qnn_Tensor_t* tensors,
        uint32_t tensor_count,
        const std::vector<NativeTensorPlan>& plans,
        const char* role,
        std::string* error) {
    if (tensors == nullptr || tensor_count != plans.size()) {
        std::ostringstream message;
        message << "QNN smoke " << role << " tensor count mismatch: expected "
                << plans.size() << ", got " << tensor_count << ".";
        if (error != nullptr) {
            *error = message.str();
        }
        return false;
    }
    std::vector<bool> consumed(tensor_count, false);
    for (const auto& plan : plans) {
        size_t selected = tensor_count;
        for (size_t index = 0; index < tensor_count; ++index) {
            const char* name = qnn_tensor_name(tensors[index]);
            if (!consumed[index] && name != nullptr && plan.name == name) {
                selected = index;
                break;
            }
        }
        if (selected == tensor_count) {
            if (error != nullptr) {
                *error = "QNN smoke metadata is missing declared " +
                    std::string(role) + " tensor " + plan.name + ".";
            }
            return false;
        }
        if (!qnn_smoke_tensor_matches_plan(tensors[selected], plan, error)) return false;
        consumed[selected] = true;
    }
    return true;
}

bool qnn_smoke_output_buffer_valid(
        const Qnn_Tensor_t& tensor,
        const std::vector<uint8_t>& buffer,
        long long* nonzero_elements,
        std::string* error) {
    const uint64_t element_count = qnn_tensor_element_count(tensor);
    const uint32_t element_bytes = qnn_element_bytes(qnn_tensor_data_type(tensor));
    if (element_count == 0U || element_bytes == 0U ||
        element_count > buffer.size() / element_bytes) {
        if (error != nullptr) {
            *error = "QNN smoke output buffer has an invalid element contract.";
        }
        return false;
    }
    long long local_nonzero = 0;
    for (uint64_t index = 0; index < element_count; ++index) {
        const size_t offset = static_cast<size_t>(index) * element_bytes;
        bool raw_nonzero = false;
        for (uint32_t byte = 0; byte < element_bytes; ++byte) {
            raw_nonzero = raw_nonzero || buffer[offset + byte] != 0U;
        }
        if (raw_nonzero) ++local_nonzero;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        for (uint64_t index = 0; index < element_count; ++index) {
            float value = 0.0f;
            std::memcpy(&value, buffer.data() + index * sizeof(float), sizeof(float));
            if (!std::isfinite(value)) {
                if (error != nullptr) {
                    *error = "QNN smoke output contains non-finite float32 values.";
                }
                return false;
            }
        }
    } else if (type == QNN_DATATYPE_FLOAT_64) {
        for (uint64_t index = 0; index < element_count; ++index) {
            double value = 0.0;
            std::memcpy(&value, buffer.data() + index * sizeof(double), sizeof(double));
            if (!std::isfinite(value)) {
                if (error != nullptr) {
                    *error = "QNN smoke output contains non-finite float64 values.";
                }
                return false;
            }
        }
    } else if (type == QNN_DATATYPE_FLOAT_16 || type == QNN_DATATYPE_BFLOAT_16) {
        const uint16_t exponent_mask = type == QNN_DATATYPE_FLOAT_16 ? 0x7c00U : 0x7f80U;
        for (uint64_t index = 0; index < element_count; ++index) {
            uint16_t bits = 0U;
            std::memcpy(&bits, buffer.data() + index * sizeof(uint16_t), sizeof(uint16_t));
            if ((bits & exponent_mask) == exponent_mask) {
                if (error != nullptr) {
                    *error = "QNN smoke output contains non-finite 16-bit float values.";
                }
                return false;
            }
        }
    } else if (type == QNN_DATATYPE_SFIXED_POINT_8 ||
               type == QNN_DATATYPE_UFIXED_POINT_8 ||
               type == QNN_DATATYPE_SFIXED_POINT_16 ||
               type == QNN_DATATYPE_UFIXED_POINT_16 ||
               type == QNN_DATATYPE_SFIXED_POINT_32 ||
               type == QNN_DATATYPE_UFIXED_POINT_32) {
        const auto quant = qnn_tensor_quantize_params(tensor);
        if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET &&
            (!std::isfinite(quant.scaleOffsetEncoding.scale) ||
             quant.scaleOffsetEncoding.scale <= 0.0f)) {
            if (error != nullptr) {
                *error = "QNN smoke output has an invalid dequantization scale.";
            }
            return false;
        }
    }
    if (nonzero_elements != nullptr) {
        *nonzero_elements += local_nonzero;
    }
    return true;
}

bool qnn_tensor_buffer_has_capacity(
        const QnnTensorBinding& binding,
        uint64_t element_count,
        size_t bytes_per_element,
        const char* value_kind,
        std::string* error) {
    if (bytes_per_element == 0U ||
        element_count > std::numeric_limits<size_t>::max() / bytes_per_element) {
        if (error != nullptr) {
            *error = std::string(value_kind) + " tensor byte count overflows for " + binding.name;
        }
        return false;
    }
    const size_t required_bytes = static_cast<size_t>(element_count) * bytes_per_element;
    if (binding.buffer.size() < required_bytes) {
        if (error != nullptr) {
            *error = std::string(value_kind) + " tensor buffer is too small for " + binding.name;
        }
        return false;
    }
    return true;
}

#endif // MCA_QNN_TENSOR_INSPECT_HAS_SDK

} // namespace qnn
} // namespace mca
