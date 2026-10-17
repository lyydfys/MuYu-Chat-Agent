#include "mca/qnn/qnn_graph_executor_helper.hpp"

#include <cassert>
#include <iostream>
#include <vector>

using namespace mca::qnn;

void test_checksum_float_vector() {
    std::vector<float> values = {1.0f, 2.0f, 3.0f};
    uint64_t c1 = checksum_float_vector(values);
    uint64_t c2 = checksum_float_vector(values);
    assert(c1 == c2);
    assert(c1 != 0);

    std::vector<float> empty_values;
    assert(checksum_float_vector(empty_values) == 0);
}

void test_fixed_width_lower_hex() {
    std::string hex_str = fixed_width_lower_hex_u64(0x1a2b3c4d);
    assert(hex_str.size() == 16);
    assert(hex_str == "000000001a2b3c4d");
}

void test_float_vector_stats_json() {
    std::vector<float> values = {2.0f, 4.0f, 6.0f};
    std::string stats = float_vector_stats_json(values);
    assert(stats.find("\"count\":3") != std::string::npos);
    assert(stats.find("\"finite\":3") != std::string::npos);
    assert(stats.find("\"min\":2") != std::string::npos);
    assert(stats.find("\"max\":6") != std::string::npos);
    assert(stats.find("\"mean\":4") != std::string::npos);
}

#if MCA_QNN_EXECUTOR_HELPER_HAS_SDK

void test_graph_execution_metrics_json() {
    QnnGraphExecutionMetrics metrics;
    metrics.graph_name = "unet_eval";
    metrics.execute_ms = 42;
    metrics.input_count = 3;
    metrics.output_count = 1;
    metrics.nonzero_output_elements = 1000;
    metrics.output_checksum = 123456789;
    metrics.finite_outputs = true;
    metrics.execution_success = true;
    metrics.stage = "graph_execute_passed";

    std::string json = graph_execution_metrics_json(metrics);
    assert(json.find("\"graphName\":\"unet_eval\"") != std::string::npos);
    assert(json.find("\"executeMs\":42") != std::string::npos);
    assert(json.find("\"executionSuccess\":true") != std::string::npos);
    assert(json.find("\"stage\":\"graph_execute_passed\"") != std::string::npos);
}

void test_bind_tensors_null_destination() {
    std::string error;
    bool ok = qnn_bind_graph_tensors(
        {},
        nullptr,
        0,
        true,
        nullptr,
        &error
    );
    assert(!ok);
    assert(!error.empty());
}

#endif // MCA_QNN_EXECUTOR_HELPER_HAS_SDK

int main() {
    test_checksum_float_vector();
    test_fixed_width_lower_hex();
    test_float_vector_stats_json();
#if MCA_QNN_EXECUTOR_HELPER_HAS_SDK
    test_graph_execution_metrics_json();
    test_bind_tensors_null_destination();
#endif
    std::cout << "QNN graph executor helper unit tests passed." << std::endl;
    return 0;
}
