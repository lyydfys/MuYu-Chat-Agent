#include "qnn_tensor_memory.hpp"

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

#include <algorithm>
#include <limits>

namespace mca {
namespace qnn {

bool make_qnn_tensor(
        const NativeTensorPlan& plan,
        bool input,
        std::vector<uint32_t>* dimensions,
        std::vector<uint8_t>* buffer,
        Qnn_Tensor_t* tensor,
        std::string* error) {
    if (!plan.supported || plan.name.empty() || plan.shape.empty() || plan.byte_size <= 0) {
        if (error) *error = "Tensor plan is not bindable: " + plan.name;
        return false;
    }
    if (plan.byte_size > std::numeric_limits<uint32_t>::max()) {
        if (error) *error = "Tensor buffer exceeds QNN client buffer uint32 size: " + plan.name;
        return false;
    }
    Qnn_DataType_t qnn_type = QNN_DATATYPE_UNDEFINED;
    if (!qnn_data_type_from_string(plan.data_type, &qnn_type)) {
        if (error) *error = "Unsupported QNN tensor dtype in native runner: " + plan.data_type;
        return false;
    }
    *dimensions = plan.shape;
    buffer->assign(static_cast<size_t>(plan.byte_size), 0);

    Qnn_Tensor_t local = QNN_TENSOR_INIT;
    local.version = QNN_TENSOR_VERSION_1;
    local.v1.name = plan.name.c_str();
    local.v1.type = input ? QNN_TENSOR_TYPE_APP_WRITE : QNN_TENSOR_TYPE_APP_READ;
    local.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    local.v1.dataType = qnn_type;
    local.v1.rank = static_cast<uint32_t>(dimensions->size());
    local.v1.dimensions = dimensions->data();
    local.v1.memType = QNN_TENSORMEMTYPE_RAW;
    local.v1.clientBuf.data = buffer->data();
    local.v1.clientBuf.dataSize = static_cast<uint32_t>(buffer->size());
    *tensor = local;
    return true;
}

int tensor_index_by_name(
        const std::vector<QnnTensorBinding>& tensors,
        const std::vector<std::string>& candidates) {
    for (const auto& candidate : candidates) {
        for (size_t i = 0; i < tensors.size(); ++i) {
            if (tensors[i].name.find(candidate) != std::string::npos) return static_cast<int>(i);
        }
    }
    return -1;
}

int qnn_exact_tensor_index(
        const std::vector<QnnTensorBinding>& tensors,
        const std::string& name) {
    if (name.empty()) return -1;
    for (size_t i = 0; i < tensors.size(); ++i) {
        if (tensors[i].name == name) return static_cast<int>(i);
    }
    return -1;
}

bool qnn_matches_vector_shape(
        const std::vector<uint32_t>& dimensions,
        size_t expected_elements) {
    if (dimensions.empty()) return false;
    uint64_t total = 1;
    for (uint32_t dim : dimensions) total *= std::max<uint32_t>(1u, dim);
    return total == expected_elements;
}

}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_TENSOR_INSPECT_HAS_SDK
