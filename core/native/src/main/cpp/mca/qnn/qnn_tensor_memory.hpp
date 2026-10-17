#ifndef MCA_QNN_TENSOR_MEMORY_HPP
#define MCA_QNN_TENSOR_MEMORY_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"

namespace mca {
namespace qnn {

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

bool make_qnn_tensor(
        const NativeTensorPlan& plan,
        bool input,
        std::vector<uint32_t>* dimensions,
        std::vector<uint8_t>* buffer,
        Qnn_Tensor_t* tensor,
        std::string* error);

int tensor_index_by_name(
        const std::vector<QnnTensorBinding>& tensors,
        const std::vector<std::string>& candidates);

int qnn_exact_tensor_index(
        const std::vector<QnnTensorBinding>& tensors,
        const std::string& name);

bool qnn_matches_vector_shape(
        const std::vector<uint32_t>& dimensions,
        size_t expected_elements);

#endif  // MCA_QNN_TENSOR_INSPECT_HAS_SDK

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_TENSOR_MEMORY_HPP
