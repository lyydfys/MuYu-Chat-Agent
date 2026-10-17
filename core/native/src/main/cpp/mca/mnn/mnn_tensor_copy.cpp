#include "mca/mnn/mnn_tensor_copy.hpp"
#include "mca/mnn/mnn_stats_collector.hpp"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <memory>
#include <sstream>

namespace mca {
namespace mnn {

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION

nlohmann::json tensor_metadata_json(const MNN::Tensor* tensor) {
    if (tensor == nullptr) {
        return nlohmann::json({{"available", false}});
    }
    nlohmann::json shape = nlohmann::json::array();
    for (int i = 0; i < tensor->dimensions(); ++i) {
        shape.push_back(tensor->length(i));
    }
    const auto type = tensor->getType();
    return nlohmann::json({
        {"available", true},
        {"shape", shape},
        {"dimensionType", static_cast<int>(tensor->getDimensionType())},
        {"typeCode", type.code},
        {"typeBits", type.bits},
        {"typeLanes", type.lanes}
    });
}

std::string mnn_error_code_name(MNN::ErrorCode code) {
    switch (code) {
        case MNN::NO_ERROR:
            return "NO_ERROR";
        case MNN::OUT_OF_MEMORY:
            return "OUT_OF_MEMORY";
        case MNN::NOT_SUPPORT:
            return "NOT_SUPPORT";
        case MNN::COMPUTE_SIZE_ERROR:
            return "COMPUTE_SIZE_ERROR";
        case MNN::NO_EXECUTION:
            return "NO_EXECUTION";
        case MNN::INVALID_VALUE:
            return "INVALID_VALUE";
        case MNN::INPUT_DATA_ERROR:
            return "INPUT_DATA_ERROR";
        case MNN::CALL_BACK_STOP:
            return "CALL_BACK_STOP";
        default:
            return "MNN_ERROR_" + std::to_string(static_cast<int>(code));
    }
}

bool assign_tensor_buffer_to_float_vector(
        MNN::Tensor* tensor,
        const void* raw,
        size_t count,
        FloatTensorData& output,
        std::string& error) {
    if (raw == nullptr) {
        error = "Tensor output buffer is null.";
        return false;
    }
    const auto type = tensor->getType();
    output.values.assign(count, 0.0f);
    if (type.code == halide_type_float && type.bits == 32) {
        const auto* ptr = static_cast<const float*>(raw);
        output.values.assign(ptr, ptr + count);
        return true;
    }
    if (type.code == halide_type_float && type.bits == 16) {
        const auto* ptr = static_cast<const uint16_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = fp16_to_float(ptr[i]);
        return true;
    }
    if (type.code == halide_type_uint && type.bits == 16) {
        const auto* ptr = static_cast<const uint16_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = static_cast<float>(ptr[i]);
        return true;
    }
    if (type.code == halide_type_int && type.bits == 32) {
        const auto* ptr = static_cast<const int32_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = static_cast<float>(ptr[i]);
        return true;
    }
    if (type.code == halide_type_uint && type.bits == 32) {
        const auto* ptr = static_cast<const uint32_t*>(raw);
        for (size_t i = 0; i < count; ++i) output.values[i] = static_cast<float>(ptr[i]);
        return true;
    }
    std::ostringstream out;
    out << "Unsupported output tensor type code=" << static_cast<int>(type.code)
        << " bits=" << static_cast<int>(type.bits)
        << " lanes=" << static_cast<int>(type.lanes) << ".";
    error = out.str();
    return false;
}

nlohmann::json tensor_buffer_debug_json(MNN::Tensor* tensor) {
    nlohmann::json out = {
            {"tensor", tensor_metadata_json(tensor)}
    };
    if (tensor == nullptr) {
        return out;
    }
    const size_t expectedCount = tensor_element_count(mnn_tensor_shape(tensor));
    auto* direct = tensor->host<uint8_t>();
    if (direct != nullptr) {
        nlohmann::json directBytes = nlohmann::json::array();
        const int bytesToShow = std::min(tensor->size(), 16);
        for (int i = 0; i < bytesToShow; ++i) {
            directBytes.push_back(static_cast<int>(direct[i]));
        }
        out["directAvailable"] = true;
        out["directBytes"] = directBytes;
        FloatTensorData decodedDirect;
        std::string directError;
        if (assign_tensor_buffer_to_float_vector(tensor, direct, expectedCount, decodedDirect, directError)) {
            out["directDeclaredStats"] = float_vector_stats_json(decodedDirect.values);
        } else {
            out["directDeclaredError"] = directError;
        }
    } else {
        out["directAvailable"] = false;
    }
    std::unique_ptr<MNN::Tensor> hostTensor(MNN::Tensor::createHostTensorFromDevice(tensor, true));
    if (!hostTensor) {
        out["hostCopyOk"] = false;
        out["hostCopyError"] = "createHostTensorFromDevice returned null.";
        return out;
    }
    out["hostCopyOk"] = true;
    out["hostTensor"] = tensor_metadata_json(hostTensor.get());
    out["hostBytes"] = hostTensor->size();
    const auto* raw = hostTensor->host<uint8_t>();
    if (raw == nullptr) {
        out["hostCopyOk"] = false;
        out["hostCopyError"] = "Host tensor buffer is null.";
        return out;
    }
    nlohmann::json firstBytes = nlohmann::json::array();
    const int bytesToShow = std::min(hostTensor->size(), 16);
    for (int i = 0; i < bytesToShow; ++i) {
        firstBytes.push_back(static_cast<int>(raw[i]));
    }
    out["firstBytes"] = firstBytes;
    const size_t hostExpectedCount = tensor_element_count(mnn_tensor_shape(hostTensor.get()));
    FloatTensorData decoded;
    std::string decodeError;
    if (assign_tensor_buffer_to_float_vector(hostTensor.get(), raw, hostExpectedCount, decoded, decodeError)) {
        out["declaredStats"] = float_vector_stats_json(decoded.values);
    } else {
        out["declaredError"] = decodeError;
    }
    const size_t bytes = static_cast<size_t>(std::max(hostTensor->size(), 0));
    out["asF32"] = raw_tensor_stats_json(bytes / sizeof(float), hostExpectedCount, [&](size_t index) {
        float value = 0.0f;
        std::memcpy(&value, raw + index * sizeof(float), sizeof(float));
        return value;
    });
    out["asF16"] = raw_tensor_stats_json(bytes / sizeof(uint16_t), hostExpectedCount, [&](size_t index) {
        uint16_t value = 0;
        std::memcpy(&value, raw + index * sizeof(uint16_t), sizeof(uint16_t));
        return fp16_to_float(value);
    });
    out["asBF16"] = raw_tensor_stats_json(bytes / sizeof(uint16_t), hostExpectedCount, [&](size_t index) {
        uint16_t value = 0;
        std::memcpy(&value, raw + index * sizeof(uint16_t), sizeof(uint16_t));
        return bf16_to_float(value);
    });
    return out;
}

bool copy_tensor_to_float_vector(MNN::Tensor* tensor, FloatTensorData& output, std::string& error) {
    if (tensor == nullptr) {
        error = "Output tensor is missing.";
        return false;
    }
    output.shape = mnn_tensor_shape(tensor);
    const size_t count = tensor_element_count(output.shape);
    if (count == 0) {
        error = "Output tensor shape is empty.";
        return false;
    }
    // OpenCL map() can expose a packed device buffer rather than logical NCHW
    // elements. Always use MNN's host-copy path so layout conversion is handled
    // by the backend before the scheduler or PNG writer reads the values.
    std::unique_ptr<MNN::Tensor> hostTensor(
            MNN::Tensor::createHostTensorFromDevice(tensor, false));
    if (!hostTensor || hostTensor->host<uint8_t>() == nullptr) {
        error = "Failed to allocate host output tensor.";
        return false;
    }
    if (!tensor->copyToHostTensor(hostTensor.get())) {
        error = "copyToHostTensor failed.";
        return false;
    }
    output.shape = mnn_tensor_shape(hostTensor.get());
    return assign_tensor_buffer_to_float_vector(
            hostTensor.get(),
            hostTensor->host<uint8_t>(),
            count,
            output,
            error);
}

MNN::Tensor* find_named_output_by_shape(
        const std::map<std::string, MNN::Tensor*>& outputs,
        const std::vector<int>& expectedShape,
        const std::vector<std::string>& preferredNames) {
    for (const auto& name : preferredNames) {
        auto it = outputs.find(name);
        if (it != outputs.end() && mnn_tensor_shape(it->second) == expectedShape) {
            return it->second;
        }
    }
    return nullptr;
}

MNN::Tensor* find_output_by_element_count(
        const std::map<std::string, MNN::Tensor*>& outputs,
        size_t expectedCount,
        const std::vector<std::string>& preferredNames) {
    for (const auto& name : preferredNames) {
        auto it = outputs.find(name);
        if (it != outputs.end() &&
            tensor_element_count(mnn_tensor_shape(it->second)) == expectedCount) {
            return it->second;
        }
    }
    for (const auto& entry : outputs) {
        if (tensor_element_count(mnn_tensor_shape(entry.second)) == expectedCount) {
            return entry.second;
        }
    }
    return nullptr;
}

MNN::Tensor* find_output_by_shape(
        const std::map<std::string, MNN::Tensor*>& outputs,
        const std::vector<int>& expectedShape,
        const std::vector<std::string>& preferredNames) {
    for (const auto& name : preferredNames) {
        auto it = outputs.find(name);
        if (it != outputs.end() && mnn_tensor_shape(it->second) == expectedShape) {
            return it->second;
        }
    }
    for (const auto& entry : outputs) {
        if (mnn_tensor_shape(entry.second) == expectedShape) {
            return entry.second;
        }
    }
    return nullptr;
}
#endif

}  // namespace mnn
}  // namespace mca
