#include "qnn_tensor_codec.hpp"

#include <algorithm>
#include <cctype>
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
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<int>(static_cast<unsigned char>(c));
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

std::string files_json(const std::vector<std::string>& values) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ",";
        out << quote(values[i]);
    }
    out << "]";
    return out.str();
}

} // namespace

std::string binary_metadata_json(const QnnBinaryMetadata& metadata) {
    std::ostringstream out;
    out << "{"
        << "\"attempted\":" << (metadata.attempted ? "true" : "false") << ","
        << "\"parsed\":" << (metadata.parsed ? "true" : "false") << ","
        << "\"version\":" << metadata.version << ","
        << "\"backendId\":" << metadata.backend_id << ","
        << "\"buildId\":" << quote(metadata.build_id) << ","
        << "\"coreApiVersion\":" << quote(metadata.core_api_version) << ","
        << "\"backendApiVersion\":" << quote(metadata.backend_api_version) << ","
        << "\"socVersion\":" << quote(metadata.soc_version) << ","
        << "\"socModel\":" << metadata.soc_model << ","
        << "\"contextBlobSize\":" << metadata.context_blob_size << ","
        << "\"graphCount\":" << metadata.graph_count << ","
        << "\"graphNames\":" << files_json(metadata.graph_names) << ","
        << "\"message\":" << quote(metadata.message)
        << "}";
    return out.str();
}

#if MCA_QNN_TENSOR_CODEC_HAS_SDK

bool qnn_data_type_from_string(const std::string& data_type, Qnn_DataType_t* out) {
    if (out == nullptr) return false;
    std::string value = data_type;
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    if (value == "bool") {
        *out = QNN_DATATYPE_BOOL_8;
    } else if (value == "int8") {
        *out = QNN_DATATYPE_INT_8;
    } else if (value == "uint8") {
        *out = QNN_DATATYPE_UINT_8;
    } else if (value == "int16") {
        *out = QNN_DATATYPE_INT_16;
    } else if (value == "uint16") {
        *out = QNN_DATATYPE_UINT_16;
    } else if (value == "int32") {
        *out = QNN_DATATYPE_INT_32;
    } else if (value == "uint32") {
        *out = QNN_DATATYPE_UINT_32;
    } else if (value == "int64") {
        *out = QNN_DATATYPE_INT_64;
    } else if (value == "uint64") {
        *out = QNN_DATATYPE_UINT_64;
    } else if (value == "float16" || value == "fp16") {
        *out = QNN_DATATYPE_FLOAT_16;
    } else if (value == "float32" || value == "fp32") {
        *out = QNN_DATATYPE_FLOAT_32;
    } else if (value == "float64" || value == "fp64") {
        *out = QNN_DATATYPE_FLOAT_64;
    } else {
        return false;
    }
    return true;
}

uint32_t qnn_tensor_id(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.id : tensor.v1.id;
}

const char* qnn_tensor_name(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.name : tensor.v1.name;
}

Qnn_TensorType_t qnn_tensor_type(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.type : tensor.v1.type;
}

Qnn_DataType_t qnn_tensor_data_type(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.dataType : tensor.v1.dataType;
}

Qnn_QuantizeParams_t qnn_tensor_quantize_params(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.quantizeParams : tensor.v1.quantizeParams;
}

uint32_t qnn_tensor_rank(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.rank : tensor.v1.rank;
}

uint32_t* qnn_tensor_dimensions(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.dimensions : tensor.v1.dimensions;
}

void qnn_set_tensor_raw_buffer(Qnn_Tensor_t* tensor, void* buffer, uint32_t bytes) {
    if (tensor == nullptr) return;
    if (tensor->version == QNN_TENSOR_VERSION_2) {
        tensor->v2.memType = QNN_TENSORMEMTYPE_RAW;
        tensor->v2.clientBuf.data = buffer;
        tensor->v2.clientBuf.dataSize = bytes;
    } else {
        tensor->v1.memType = QNN_TENSORMEMTYPE_RAW;
        tensor->v1.clientBuf.data = buffer;
        tensor->v1.clientBuf.dataSize = bytes;
    }
}

const void* qnn_read_tensor_raw_buffer(const Qnn_Tensor_t* tensor, uint32_t* bytes_out) {
    if (tensor == nullptr) {
        if (bytes_out != nullptr) *bytes_out = 0;
        return nullptr;
    }
    if (tensor->version == QNN_TENSOR_VERSION_2) {
        if (bytes_out != nullptr) *bytes_out = tensor->v2.clientBuf.dataSize;
        return tensor->v2.clientBuf.data;
    } else {
        if (bytes_out != nullptr) *bytes_out = tensor->v1.clientBuf.dataSize;
        return tensor->v1.clientBuf.data;
    }
}

const void* qnn_read_tensor_raw_buffer(const Qnn_Tensor_t& tensor, uint32_t* bytes_out) {
    return qnn_read_tensor_raw_buffer(&tensor, bytes_out);
}

uint32_t qnn_element_bytes(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8:
        case QNN_DATATYPE_BOOL_8:
            return 1;
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_BFLOAT_16:
        case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16:
            return 2;
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32:
            return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:
        case QNN_DATATYPE_FLOAT_64:
            return 8;
        default:
            return 0;
    }
}

uint64_t qnn_fallback_tensor_bytes(const Qnn_Tensor_t& tensor) {
    const uint32_t rank = qnn_tensor_rank(tensor);
    uint32_t* dims = qnn_tensor_dimensions(tensor);
    if (rank == 0 || dims == nullptr) return 0;
    uint64_t elements = 1;
    for (uint32_t i = 0; i < rank; ++i) {
        if (dims[i] == 0 || elements > std::numeric_limits<uint64_t>::max() / dims[i]) {
            return 0;
        }
        elements *= dims[i];
    }
    const uint32_t bytes = qnn_element_bytes(qnn_tensor_data_type(tensor));
    if (bytes == 0 || elements > std::numeric_limits<uint64_t>::max() / bytes) return 0;
    return elements * bytes;
}

uint64_t qnn_tensor_bytes(
        const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
        const Qnn_Tensor_t& tensor) {
    if (sys_api.systemTensorGetMemoryFootprint != nullptr) {
        uint64_t bytes = 0;
        const Qnn_ErrorHandle_t status = sys_api.systemTensorGetMemoryFootprint(tensor, &bytes);
        if (status == QNN_SUCCESS && bytes > 0) return bytes;
    }
    return qnn_fallback_tensor_bytes(tensor);
}

uint16_t qnn_quantized_zero_u16(const Qnn_Tensor_t& tensor) {
    const auto quant = qnn_tensor_quantize_params(tensor);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        int64_t value = -static_cast<int64_t>(quant.scaleOffsetEncoding.offset);
        if (value < 0) value = 0;
        if (value > 65535) value = 65535;
        return static_cast<uint16_t>(value);
    }
    return 0;
}

void qnn_fill_smoke_input_buffer(const Qnn_Tensor_t& tensor, std::vector<uint8_t>* buffer) {
    if (buffer == nullptr) return;
    const std::string name = qnn_tensor_name(tensor) != nullptr ? qnn_tensor_name(tensor) : "";
    if ((name.find("timestamp") != std::string::npos ||
         name.find("timestep") != std::string::npos ||
         name.find("time_step") != std::string::npos ||
         name == "time") &&
        qnn_tensor_data_type(tensor) == QNN_DATATYPE_INT_32 &&
        buffer->size() >= sizeof(int32_t)) {
        const int32_t timestep = 1;
        std::memcpy(buffer->data(), &timestep, sizeof(timestep));
        return;
    }
    if (qnn_tensor_data_type(tensor) == QNN_DATATYPE_UFIXED_POINT_16) {
        const uint16_t zero = qnn_quantized_zero_u16(tensor);
        for (size_t cursor = 0; cursor + sizeof(uint16_t) <= buffer->size(); cursor += sizeof(uint16_t)) {
            std::memcpy(buffer->data() + cursor, &zero, sizeof(zero));
        }
    }
}

#endif // MCA_QNN_TENSOR_CODEC_HAS_SDK

} // namespace qnn
} // namespace mca
