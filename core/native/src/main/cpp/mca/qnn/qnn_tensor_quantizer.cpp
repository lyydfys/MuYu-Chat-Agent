#include "mca/qnn/qnn_tensor_quantizer.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>

namespace mca {
namespace qnn {

#if MCA_QNN_QUANTIZER_HAS_SDK

namespace {

std::string normalize_contract_token(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    value.erase(std::remove(value.begin(), value.end(), '_'), value.end());
    value.erase(std::remove(value.begin(), value.end(), '-'), value.end());
    return value;
}

} // namespace

bool qnn_has_supported_scale_offset_quantization(const Qnn_Tensor_t& tensor) {
    const auto quantization = qnn_tensor_quantize_params(tensor);
    return quantization.encodingDefinition == QNN_DEFINITION_DEFINED &&
        quantization.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET &&
        std::isfinite(quantization.scaleOffsetEncoding.scale) &&
        quantization.scaleOffsetEncoding.scale > 0.0f;
}

bool qnn_write_float_tensor(QnnTensorBinding* binding, const float* values, size_t count, std::string* error) {
    if (binding == nullptr || values == nullptr) {
        if (error != nullptr) *error = "Null tensor binding or float source.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0 || count < element_count) {
        if (error != nullptr) *error = "Not enough float values for tensor " + binding->name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        if (!qnn_tensor_buffer_has_capacity(
                *binding, element_count, sizeof(float), "Float", error)) return false;
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(float);
        std::memcpy(binding->buffer.data(), values, bytes);
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                *binding, element_count, sizeof(uint16_t), "Uint16", error)) return false;
        auto* out = reinterpret_cast<uint16_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            int64_t q = qnn_quantize_value(binding->tensor, values[i]);
            if (q < 0) q = 0;
            if (q > 65535) q = 65535;
            out[i] = static_cast<uint16_t>(q);
        }
        return true;
    }
    if (type == QNN_DATATYPE_SFIXED_POINT_16 || type == QNN_DATATYPE_INT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                *binding, element_count, sizeof(int16_t), "Int16", error)) return false;
        auto* out = reinterpret_cast<int16_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            int64_t q = qnn_quantize_value(binding->tensor, values[i]);
            if (q < std::numeric_limits<int16_t>::min()) q = std::numeric_limits<int16_t>::min();
            if (q > std::numeric_limits<int16_t>::max()) q = std::numeric_limits<int16_t>::max();
            out[i] = static_cast<int16_t>(q);
        }
        return true;
    }
    if (error != nullptr) {
        *error = "Unsupported float input tensor dtype for " + binding->name;
    }
    return false;
}

bool qnn_write_int32_tensor(QnnTensorBinding* binding, int32_t value, std::string* error) {
    if (binding == nullptr) {
        if (error != nullptr) *error = "Null int32 tensor binding.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0) {
        if (error != nullptr) *error = "Invalid int32 tensor buffer for " + binding->name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    if (type == QNN_DATATYPE_INT_32) {
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(int32_t);
        if (binding->buffer.size() < bytes) {
            if (error != nullptr) *error = "Int32 tensor buffer is too small for " + binding->name;
            return false;
        }
        auto* out = reinterpret_cast<int32_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) out[i] = value;
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(uint16_t);
        if (binding->buffer.size() < bytes) {
            if (error != nullptr) *error = "Quantized timestep tensor buffer is too small for " + binding->name;
            return false;
        }
        int64_t quantized = qnn_quantize_value(binding->tensor, static_cast<float>(value));
        quantized = std::max<int64_t>(0, std::min<int64_t>(65535, quantized));
        auto* out = reinterpret_cast<uint16_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            out[i] = static_cast<uint16_t>(quantized);
        }
        return true;
    }
    if (error != nullptr) {
        *error = "Unsupported scalar integer tensor dtype for " + binding->name +
            ": " + qnn_data_type_name(type);
    }
    return false;
}

bool qnn_write_int32_vector_tensor(
        QnnTensorBinding* binding,
        const int32_t* values,
        size_t count,
        std::string* error) {
    if (binding == nullptr || values == nullptr) {
        if (error != nullptr) *error = "Null int32 tensor binding or source.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0 || count < element_count) {
        if (error != nullptr) {
            std::ostringstream message;
            message << "Not enough int32 values for tensor " << binding->name
                    << ". Need " << element_count << ", got " << count << ".";
            *error = message.str();
        }
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    if (type != QNN_DATATYPE_INT_32) {
        if (error != nullptr) {
            *error = "Token tensor is not int32: " + binding->name +
                " (dtype=" + qnn_data_type_name(type) + ")";
        }
        return false;
    }
    const size_t bytes = static_cast<size_t>(element_count) * sizeof(int32_t);
    if (binding->buffer.size() < bytes) {
        if (error != nullptr) *error = "Int32 vector tensor buffer is too small for " + binding->name;
        return false;
    }
    std::memcpy(binding->buffer.data(), values, bytes);
    return true;
}

bool qnn_read_float_tensor(const QnnTensorBinding& binding, std::vector<float>* out, std::string* error) {
    if (out == nullptr) {
        if (error != nullptr) *error = "Null float tensor destination.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding.tensor);
    if (element_count == 0) {
        if (error != nullptr) *error = "Output tensor has no elements: " + binding.name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding.tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        if (!qnn_tensor_buffer_has_capacity(
                binding, element_count, sizeof(float), "Float output", error)) return false;
        out->assign(static_cast<size_t>(element_count), 0.0f);
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(float);
        std::memcpy(out->data(), binding.buffer.data(), bytes);
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                binding, element_count, sizeof(uint16_t), "Uint16 output", error)) return false;
        out->assign(static_cast<size_t>(element_count), 0.0f);
        const auto* values = reinterpret_cast<const uint16_t*>(binding.buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            (*out)[static_cast<size_t>(i)] = qnn_dequantize_value(binding.tensor, values[i]);
        }
        return true;
    }
    if (type == QNN_DATATYPE_SFIXED_POINT_16 || type == QNN_DATATYPE_INT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                binding, element_count, sizeof(int16_t), "Int16 output", error)) return false;
        out->assign(static_cast<size_t>(element_count), 0.0f);
        const auto* values = reinterpret_cast<const int16_t*>(binding.buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            (*out)[static_cast<size_t>(i)] = qnn_dequantize_value(binding.tensor, values[i]);
        }
        return true;
    }
    if (error != nullptr) {
        *error = "Unsupported output tensor dtype for " + binding.name;
    }
    return false;
}

bool qnn_write_timestep_tensor(
        QnnTensorBinding* binding,
        double timestep,
        std::string* error) {
    if (binding == nullptr || !std::isfinite(timestep)) {
        if (error != nullptr) *error = "Timestep tensor binding and value must be valid and finite.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0) {
        if (error != nullptr) *error = "Timestep tensor has no elements: " + binding->name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    const auto quantization = qnn_tensor_quantize_params(binding->tensor);
    const bool quantized =
        quantization.quantizationEncoding != QNN_QUANTIZATION_ENCODING_UNDEFINED;
    if (type == QNN_DATATYPE_FLOAT_32 || type == QNN_DATATYPE_UFIXED_POINT_16 ||
        type == QNN_DATATYPE_SFIXED_POINT_16 ||
        ((type == QNN_DATATYPE_UINT_16 || type == QNN_DATATYPE_INT_16) && quantized)) {
        std::vector<float> values(static_cast<size_t>(element_count), static_cast<float>(timestep));
        return qnn_write_float_tensor(binding, values.data(), values.size(), error);
    }

    const double rounded = std::round(timestep);
    if (std::fabs(timestep - rounded) > 1e-6) {
        if (error != nullptr) {
            std::ostringstream message;
            message << std::setprecision(17) << "Timestep " << timestep
                    << " is fractional, but graph tensor " << binding->name
                    << " has integer dtype " << qnn_data_type_name(type) << ".";
            *error = message.str();
        }
        return false;
    }
    const auto ensure_buffer = [&](size_t bytes) {
        if (binding->buffer.size() >= bytes) return true;
        if (error != nullptr) *error = "Timestep tensor buffer is too small for " + binding->name;
        return false;
    };
    if (type == QNN_DATATYPE_INT_32) {
        if (rounded < std::numeric_limits<int32_t>::min() ||
            rounded > std::numeric_limits<int32_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(int32_t))) return false;
        auto* output = reinterpret_cast<int32_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<int32_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_UINT_32) {
        if (rounded < 0.0 || rounded > std::numeric_limits<uint32_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(uint32_t))) return false;
        auto* output = reinterpret_cast<uint32_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<uint32_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_INT_16) {
        if (rounded < std::numeric_limits<int16_t>::min() ||
            rounded > std::numeric_limits<int16_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(int16_t))) return false;
        auto* output = reinterpret_cast<int16_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<int16_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_UINT_16) {
        if (rounded < 0.0 || rounded > std::numeric_limits<uint16_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(uint16_t))) return false;
        auto* output = reinterpret_cast<uint16_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<uint16_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_INT_8) {
        if (rounded < std::numeric_limits<int8_t>::min() ||
            rounded > std::numeric_limits<int8_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count))) return false;
        auto* output = reinterpret_cast<int8_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<int8_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_UINT_8) {
        if (rounded < 0.0 || rounded > std::numeric_limits<uint8_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count))) return false;
        auto* output = reinterpret_cast<uint8_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<uint8_t>(rounded));
        return true;
    }
    if (error != nullptr) {
        *error = "Unsupported QNN timestep tensor dtype for " + binding->name +
            ": " + qnn_data_type_name(type);
    }
    return false;
}

int tensor_index_by_name(const std::vector<QnnTensorBinding>& tensors, const std::vector<std::string>& tokens) {
    for (size_t i = 0; i < tensors.size(); ++i) {
        std::string lower = tensors[i].name;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        for (const auto& token : tokens) {
            std::string token_lower = token;
            std::transform(token_lower.begin(), token_lower.end(), token_lower.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            if (lower.find(token_lower) != std::string::npos) {
                return static_cast<int>(i);
            }
        }
    }
    return -1;
}

int qnn_exact_tensor_index(
        const std::vector<QnnTensorBinding>& tensors,
        const std::vector<std::string>& accepted_names,
        std::string* error) {
    int resolved = -1;
    for (size_t index = 0; index < tensors.size(); ++index) {
        const std::string normalized = normalize_contract_token(tensors[index].name);
        if (std::find(accepted_names.begin(), accepted_names.end(), normalized) ==
            accepted_names.end()) {
            continue;
        }
        if (resolved >= 0) {
            if (error != nullptr) {
                *error = "QNN graph exposes duplicate semantic tensor inputs for " +
                    tensors[static_cast<size_t>(resolved)].name + " and " + tensors[index].name + ".";
            }
            return -2;
        }
        resolved = static_cast<int>(index);
    }
    return resolved;
}

#endif // MCA_QNN_QUANTIZER_HAS_SDK

} // namespace qnn
} // namespace mca
