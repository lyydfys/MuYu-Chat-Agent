#ifndef MCA_MNN_TENSOR_COPY_HPP
#define MCA_MNN_TENSOR_COPY_HPP

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

#include "mca/mnn/mnn_tensor_ops.hpp"
#include "mca/mnn/mnn_numeric_convert.hpp"

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/ErrorCode.hpp>
#include <MNN/Tensor.hpp>
#endif

namespace mca {
namespace mnn {

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
template <typename T>
bool fill_input_tensor(MNN::Tensor* tensor, T value) {
    if (tensor == nullptr) return false;
    auto* direct = tensor->host<T>();
    if (direct != nullptr) {
        std::fill(direct, direct + tensor->elementSize(), value);
        return true;
    }
    void* mapped = tensor->map(MNN::Tensor::MAP_TENSOR_WRITE, tensor->getDimensionType());
    if (mapped != nullptr) {
        auto* ptr = static_cast<T*>(mapped);
        std::fill(ptr, ptr + tensor->elementSize(), value);
        tensor->unmap(MNN::Tensor::MAP_TENSOR_WRITE, tensor->getDimensionType(), mapped);
        return true;
    }
    MNN::Tensor hostTensor(tensor, tensor->getDimensionType());
    auto* ptr = hostTensor.host<T>();
    if (ptr == nullptr) return false;
    std::fill(ptr, ptr + hostTensor.elementSize(), value);
    return tensor->copyFromHostTensor(&hostTensor);
}

template <typename T>
bool tensor_has_host(MNN::Tensor* tensor) {
    return tensor != nullptr && tensor->host<T>() != nullptr;
}

std::string mnn_error_code_name(MNN::ErrorCode code);

bool assign_tensor_buffer_to_float_vector(
        MNN::Tensor* tensor,
        const void* raw,
        size_t count,
        FloatTensorData& output,
        std::string& error);

nlohmann::json tensor_buffer_debug_json(MNN::Tensor* tensor);
nlohmann::json tensor_metadata_json(const MNN::Tensor* tensor);

bool copy_tensor_to_float_vector(
        MNN::Tensor* tensor,
        FloatTensorData& output,
        std::string& error);

MNN::Tensor* find_named_output_by_shape(
        const std::map<std::string, MNN::Tensor*>& outputs,
        const std::vector<int>& expectedShape,
        const std::vector<std::string>& preferredNames);

MNN::Tensor* find_output_by_element_count(
        const std::map<std::string, MNN::Tensor*>& outputs,
        size_t expectedCount,
        const std::vector<std::string>& preferredNames);

MNN::Tensor* find_output_by_shape(
        const std::map<std::string, MNN::Tensor*>& outputs,
        const std::vector<int>& expectedShape,
        const std::vector<std::string>& preferredNames);
#endif

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_TENSOR_COPY_HPP
