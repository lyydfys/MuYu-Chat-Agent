#ifndef MCA_MNN_TENSOR_OPS_HPP
#define MCA_MNN_TENSOR_OPS_HPP

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include <MNN/ImageProcess.hpp>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

namespace mca {
namespace mnn {

struct FloatTensorData {
    std::vector<float> values;
    std::vector<int> shape;
};

size_t tensor_element_count(const std::vector<int>& shape);

std::string tensor_shape_string(const std::vector<int>& shape);

bool validate_finite_float_vector(
        const std::vector<float>& values,
        const std::string& label,
        std::string& error);

bool validate_float_tensor_contract(
        const FloatTensorData& tensor,
        const std::vector<int>& expected_shape,
        const std::string& label,
        std::string& error);

std::vector<int> mnn_tensor_shape(const MNN::Tensor* tensor);

std::vector<float> nchw_to_nhwc(
        const std::vector<float>& input,
        int batch,
        int channels,
        int height,
        int width);

std::vector<float> nhwc_to_nchw(
        const std::vector<float>& input,
        int batch,
        int height,
        int width,
        int channels);

bool validate_mnn_diffusion_runtime_options(
        const std::string& requested_backend,
        int threads,
        std::string& canonical_backend,
        std::string& error);

bool configure_mnn_session(
        MNN::ScheduleConfig& config,
        MNN::BackendConfig& backendConfig,
        const std::string& backendMode,
        int threads,
        std::string& error,
        bool requireHighPrecision = false);

void configure_direct_interpreter(
        MNN::Interpreter* interpreter,
        bool resizeDefer = false,
        bool inputUser = false,
        bool debugSession = true);

template <typename T>
bool copy_vector_to_tensor(MNN::Tensor* tensor, const std::vector<T>& values, std::string& error) {
    if (tensor == nullptr) {
        error = "Input tensor is missing.";
        return false;
    }
    if (tensor->elementSize() != static_cast<int>(values.size())) {
        std::ostringstream out;
        out << "Input tensor element mismatch: expected " << tensor->elementSize()
            << ", got " << values.size() << ".";
        error = out.str();
        return false;
    }
    auto* direct = tensor->host<T>();
    if (direct != nullptr) {
        std::copy(values.begin(), values.end(), direct);
        return true;
    }
    std::unique_ptr<MNN::Tensor> hostTensor(MNN::Tensor::createHostTensorFromDevice(tensor, false));
    if (!hostTensor) {
        error = "Failed to allocate host input tensor.";
        return false;
    }
    if (hostTensor->elementSize() != static_cast<int>(values.size())) {
        std::ostringstream out;
        out << "Host input tensor element mismatch: expected " << hostTensor->elementSize()
            << ", got " << values.size() << ".";
        error = out.str();
        return false;
    }
    auto* ptr = hostTensor->host<T>();
    if (ptr == nullptr) {
        error = "Failed to map host input tensor.";
        return false;
    }
    std::copy(values.begin(), values.end(), ptr);
    if (!tensor->copyFromHostTensor(hostTensor.get())) {
        error = "copyFromHostTensor failed.";
        return false;
    }
    return true;
}

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_TENSOR_OPS_HPP
