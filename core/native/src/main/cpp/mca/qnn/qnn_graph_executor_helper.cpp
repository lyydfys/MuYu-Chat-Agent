#include "mca/qnn/qnn_graph_executor_helper.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>

namespace mca {
namespace qnn {

namespace {

std::string json_escape(const std::string& value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u00";
                    const char* hex = "0123456789abcdef";
                    out << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string quote(const std::string& value) {
    return "\"" + json_escape(value) + "\"";
}

} // namespace

uint64_t checksum_float_vector(const std::vector<float>& values) {
    uint64_t checksum = 0;
    for (float value : values) {
        uint32_t bits = 0;
        std::memcpy(&bits, &value, sizeof(bits));
        checksum += bits;
    }
    return checksum;
}

std::string fixed_width_lower_hex_u64(uint64_t value) {
    std::ostringstream out;
    out << std::hex << std::nouppercase << std::setfill('0') << std::setw(16) << value;
    return out.str();
}

std::string float_vector_stats_json(const std::vector<float>& values) {
    float min_value = std::numeric_limits<float>::infinity();
    float max_value = -std::numeric_limits<float>::infinity();
    long double sum = 0.0;
    uint64_t finite = 0;
    for (float value : values) {
        if (!std::isfinite(value)) continue;
        min_value = std::min(min_value, value);
        max_value = std::max(max_value, value);
        sum += value;
        ++finite;
    }
    std::ostringstream out;
    out << "{"
        << "\"count\":" << values.size() << ","
        << "\"finite\":" << finite << ",";
    if (finite == 0) {
        out << "\"min\":0,\"max\":0,\"mean\":0";
    } else {
        out << "\"min\":" << static_cast<double>(min_value) << ","
            << "\"max\":" << static_cast<double>(max_value) << ","
            << "\"mean\":" << static_cast<double>(sum / static_cast<long double>(finite));
    }
    out << "}";
    return out.str();
}

#if MCA_QNN_EXECUTOR_HELPER_HAS_SDK

std::string qnn_tensor_shape_json(const QnnTensorBinding& binding) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < binding.dimensions.size(); ++i) {
        if (i > 0) out << ",";
        out << binding.dimensions[i];
    }
    out << "]";
    return out.str();
}

std::string qnn_tensor_debug_json(const QnnTensorBinding& binding) {
    const Qnn_DataType_t type = qnn_tensor_data_type(binding.tensor);
    const auto quant = qnn_tensor_quantize_params(binding.tensor);
    std::ostringstream out;
    out << "{"
        << "\"name\":" << quote(binding.name) << ","
        << "\"dataType\":" << quote(qnn_data_type_name(type)) << ","
        << "\"dataTypeValue\":" << static_cast<int>(type) << ","
        << "\"shape\":" << qnn_tensor_shape_json(binding) << ","
        << "\"bytes\":" << binding.buffer.size() << ","
        << "\"quantEncoding\":" << quote(qnn_quant_encoding_name(quant.quantizationEncoding)) << ","
        << "\"quantEncodingValue\":" << static_cast<int>(quant.quantizationEncoding);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        out << ",\"scale\":" << quant.scaleOffsetEncoding.scale
            << ",\"offset\":" << quant.scaleOffsetEncoding.offset;
    }
    out << "}";
    return out.str();
}

std::string qnn_tensor_list_debug_json(const std::vector<QnnTensorBinding>& tensors) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < tensors.size(); ++i) {
        if (i > 0) out << ",";
        out << qnn_tensor_debug_json(tensors[i]);
    }
    out << "]";
    return out.str();
}

bool qnn_bind_graph_tensors(
        const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
        Qnn_Tensor_t* source,
        uint32_t count,
        bool input,
        std::vector<QnnTensorBinding>* destination,
        std::string* error) {
    if (destination == nullptr) {
        if (error != nullptr) *error = "Null destination vector for tensor bindings.";
        return false;
    }
    destination->clear();
    destination->reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        QnnTensorBinding binding;
        binding.tensor = source[i];
        binding.name = qnn_tensor_name(binding.tensor) != nullptr
            ? qnn_tensor_name(binding.tensor)
            : "";
        qnn_copy_tensor_dimensions(&binding.tensor, &binding.dimensions);
        const uint64_t bytes = qnn_tensor_bytes(sys_api, binding.tensor);
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            if (error != nullptr) {
                *error = std::string("Invalid tensor buffer size for ") +
                    (input ? "input " : "output ") + binding.name;
            }
            return false;
        }
        binding.buffer.assign(static_cast<size_t>(bytes), 0);
        qnn_set_tensor_raw_buffer(
            &binding.tensor,
            binding.buffer.data(),
            static_cast<uint32_t>(binding.buffer.size()));
        destination->push_back(std::move(binding));
    }
    return true;
}

std::string graph_execution_metrics_json(const QnnGraphExecutionMetrics& metrics) {
    std::ostringstream out;
    out << "{"
        << "\"graphName\":" << quote(metrics.graph_name) << ","
        << "\"executeMs\":" << metrics.execute_ms << ","
        << "\"inputCount\":" << metrics.input_count << ","
        << "\"outputCount\":" << metrics.output_count << ","
        << "\"nonzeroOutputElements\":" << metrics.nonzero_output_elements << ","
        << "\"outputChecksum\":" << metrics.output_checksum << ","
        << "\"outputChecksumHex\":" << quote(fixed_width_lower_hex_u64(metrics.output_checksum)) << ","
        << "\"finiteOutputs\":" << (metrics.finite_outputs ? "true" : "false") << ","
        << "\"executionSuccess\":" << (metrics.execution_success ? "true" : "false") << ","
        << "\"stage\":" << quote(metrics.stage) << ","
        << "\"errorMessage\":" << quote(metrics.error_message)
        << "}";
    return out.str();
}

#endif // MCA_QNN_EXECUTOR_HELPER_HAS_SDK

} // namespace qnn
} // namespace mca
