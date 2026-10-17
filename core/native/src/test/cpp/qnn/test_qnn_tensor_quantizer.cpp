#include "mca/qnn/qnn_tensor_quantizer.hpp"

#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>

using namespace mca::qnn;

#if MCA_QNN_QUANTIZER_HAS_SDK

void test_tensor_index_by_name() {
    std::vector<QnnTensorBinding> bindings;
    QnnTensorBinding b1;
    b1.name = "sample_input";
    bindings.push_back(b1);

    QnnTensorBinding b2;
    b2.name = "timestep";
    bindings.push_back(b2);

    assert(tensor_index_by_name(bindings, {"time", "step"}) == 1);
    assert(tensor_index_by_name(bindings, {"sample"}) == 0);
    assert(tensor_index_by_name(bindings, {"nonexistent"}) == -1);
}

void test_exact_tensor_index() {
    std::vector<QnnTensorBinding> bindings;
    QnnTensorBinding b1;
    b1.name = "timestep";
    bindings.push_back(b1);

    QnnTensorBinding b2;
    b2.name = "prompt_embedding";
    bindings.push_back(b2);

    std::string error;
    int idx = qnn_exact_tensor_index(bindings, {"timestep", "time"}, &error);
    assert(idx == 0);
    assert(error.empty());

    int missing = qnn_exact_tensor_index(bindings, {"nonexistent_name"}, &error);
    assert(missing == -1);

    QnnTensorBinding b3;
    b3.name = "time";
    bindings.push_back(b3);
    int duplicate = qnn_exact_tensor_index(bindings, {"timestep", "time"}, &error);
    assert(duplicate == -2);
    assert(!error.empty());
}

void test_null_tensor_guards() {
    std::string error;
    float vals[4] = {1.0f, 2.0f, 3.0f, 4.0f};
    assert(!qnn_write_float_tensor(nullptr, vals, 4, &error));
    assert(!error.empty());

    error.clear();
    assert(!qnn_write_int32_tensor(nullptr, 42, &error));
    assert(!error.empty());

    error.clear();
    assert(!qnn_write_int32_vector_tensor(nullptr, nullptr, 0, &error));
    assert(!error.empty());

    error.clear();
    QnnTensorBinding dummy;
    assert(!qnn_read_float_tensor(dummy, nullptr, &error));
    assert(!error.empty());

    error.clear();
    assert(!qnn_write_timestep_tensor(nullptr, 1.0, &error));
    assert(!error.empty());

    error.clear();
    assert(!qnn_write_timestep_tensor(&dummy, std::numeric_limits<double>::quiet_NaN(), &error));
    assert(!error.empty());
}

#endif // MCA_QNN_QUANTIZER_HAS_SDK

int main() {
#if MCA_QNN_QUANTIZER_HAS_SDK
    test_tensor_index_by_name();
    test_exact_tensor_index();
    test_null_tensor_guards();
#else
    std::cout << "QNN SDK headers unavailable; typed quantizer tests skipped." << std::endl;
    return 77;
#endif
    std::cout << "QNN tensor quantizer unit tests passed." << std::endl;
    return 0;
}
