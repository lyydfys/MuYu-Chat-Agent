#include "mca/mnn/mnn_tensor_ops.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "mca/mnn/mnn_protocol_parser.hpp"

namespace mca {
namespace mnn {

size_t tensor_element_count(const std::vector<int>& shape) {
    if (shape.empty()) return 0;
    size_t count = 1;
    for (int dim : shape) {
        if (dim <= 0) return 0;
        const size_t size = static_cast<size_t>(dim);
        if (count > std::numeric_limits<size_t>::max() / size) {
            return 0;
        }
        count *= size;
    }
    return count;
}

std::string tensor_shape_string(const std::vector<int>& shape) {
    std::ostringstream out;
    out << "[";
    for (size_t index = 0; index < shape.size(); ++index) {
        if (index > 0) {
            out << ",";
        }
        out << shape[index];
    }
    out << "]";
    return out.str();
}

bool validate_finite_float_vector(
        const std::vector<float>& values,
        const std::string& label,
        std::string& error) {
    for (size_t index = 0; index < values.size(); ++index) {
        if (!std::isfinite(values[index])) {
            std::ostringstream out;
            out << label << " contains a non-finite value at index " << index << ".";
            error = out.str();
            return false;
        }
    }
    return true;
}

bool validate_float_tensor_contract(
        const FloatTensorData& tensor,
        const std::vector<int>& expected_shape,
        const std::string& label,
        std::string& error) {
    if (tensor.shape != expected_shape) {
        error = label + " shape mismatch: expected " + tensor_shape_string(expected_shape) +
                ", got " + tensor_shape_string(tensor.shape) + ".";
        return false;
    }
    const size_t expected_elements = tensor_element_count(expected_shape);
    if (expected_elements == 0 || tensor.values.size() != expected_elements) {
        std::ostringstream out;
        out << label << " element count mismatch: expected " << expected_elements
            << ", got " << tensor.values.size() << ".";
        error = out.str();
        return false;
    }
    return validate_finite_float_vector(tensor.values, label, error);
}

std::vector<int> mnn_tensor_shape(const MNN::Tensor* tensor) {
    std::vector<int> shape;
    if (tensor == nullptr) return shape;
    for (int i = 0; i < tensor->dimensions(); ++i) {
        shape.push_back(tensor->length(i));
    }
    return shape;
}

std::vector<float> nchw_to_nhwc(
        const std::vector<float>& input,
        int batch,
        int channels,
        int height,
        int width) {
    std::vector<float> output(input.size());
    const size_t expected = tensor_element_count({batch, channels, height, width});
    if (expected == 0 || input.size() != expected) return input;
    for (int n = 0; n < batch; ++n) {
        for (int c = 0; c < channels; ++c) {
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    const size_t src = ((static_cast<size_t>(n) * channels + c) * height + y) * width + x;
                    const size_t dst = ((static_cast<size_t>(n) * height + y) * width + x) * channels + c;
                    output[dst] = input[src];
                }
            }
        }
    }
    return output;
}

std::vector<float> nhwc_to_nchw(
        const std::vector<float>& input,
        int batch,
        int height,
        int width,
        int channels) {
    std::vector<float> output(input.size());
    const size_t expected = tensor_element_count({batch, height, width, channels});
    if (expected == 0 || input.size() != expected) return input;
    for (int n = 0; n < batch; ++n) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                for (int c = 0; c < channels; ++c) {
                    const size_t src = ((static_cast<size_t>(n) * height + y) * width + x) * channels + c;
                    const size_t dst = ((static_cast<size_t>(n) * channels + c) * height + y) * width + x;
                    output[dst] = input[src];
                }
            }
        }
    }
    return output;
}

bool validate_mnn_diffusion_runtime_options(
        const std::string& requested_backend,
        int threads,
        std::string& canonical_backend,
        std::string& error) {
    if (!canonical_mnn_diffusion_backend(requested_backend, canonical_backend, error)) {
        return false;
    }
    if (threads <= 0 || threads > 64) {
        error = "MNN-Diffusion threads must be between 1 and 64.";
        return false;
    }
    return true;
}

bool configure_mnn_session(
        MNN::ScheduleConfig& config,
        MNN::BackendConfig& backendConfig,
        const std::string& backendMode,
        int threads,
        std::string& error,
        bool requireHighPrecision) {
    std::string canonical_backend;
    if (!validate_mnn_diffusion_runtime_options(backendMode, threads, canonical_backend, error)) {
        return false;
    }
    const bool useOpenCl = canonical_backend == "opencl";
    config.type = useOpenCl ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    config.numThread = useOpenCl ? 1 : threads;
    if (useOpenCl) {
        config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    }
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
    // SD1.5's converted text encoder / UNet can emit NaN on the CPU in
    // Precision_Low, and the same low-precision path is the leading suspect
    // for the striped OpenCL module output.  Keep the existing fast default
    // for unrelated probes, but make the direct diffusion pipeline explicit
    // about numerical correctness.
    backendConfig.precision = requireHighPrecision
            ? MNN::BackendConfig::Precision_High
            : MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    return true;
}

void configure_direct_interpreter(
        MNN::Interpreter* interpreter,
        bool resizeDefer,
        bool inputUser,
        bool debugSession) {
    if (interpreter == nullptr) return;
    // Session_Debug is useful for the explicit graph smoke because it exposes
    // tensor metadata, but it adds bookkeeping to every invocation.  Never
    // enable it for the production diffusion/text/VAE path: those sessions are
    // reused across all timesteps and the debug bookkeeping is needlessly
    // expensive on CPU (and can substantially increase the native heap).
    if (debugSession) {
        interpreter->setSessionMode(MNN::Interpreter::Session_Debug);
    }
    interpreter->setSessionMode(inputUser
            ? MNN::Interpreter::Session_Input_User
            : MNN::Interpreter::Session_Input_Inside);
    interpreter->setSessionMode(resizeDefer
            ? MNN::Interpreter::Session_Resize_Defer
            : MNN::Interpreter::Session_Resize_Direct);
}

template bool copy_vector_to_tensor<float>(MNN::Tensor*, const std::vector<float>&, std::string&);
template bool copy_vector_to_tensor<int>(MNN::Tensor*, const std::vector<int>&, std::string&);
template bool copy_vector_to_tensor<uint8_t>(MNN::Tensor*, const std::vector<uint8_t>&, std::string&);

} // namespace mnn
} // namespace mca
