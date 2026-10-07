#include "mca/jni/jni_handle_registry.hpp"
#include "mca/jni/jni_converters.hpp"
#include "mca/jni/jni_exception_boundary.hpp"
#include "mca/jni/provider_gateways.hpp"

#include <cassert>
#include <iostream>
#include <memory>

struct DummyEngineContext {
    int id{42};
};

void test_handle_registry() {
    auto& registry = mca::jni::HandleRegistry<DummyEngineContext>::instance();
    registry.clear();

    assert(registry.resolve(0) == nullptr);
    assert(registry.resolve(9999) == nullptr);

    auto ctx = std::make_shared<DummyEngineContext>();
    ctx->id = 108;

    int64_t handle = registry.allocate(ctx);
    assert(handle > 0);
    assert(registry.count() == 1);

    auto resolved = registry.resolve(handle);
    assert(resolved != nullptr);
    assert(resolved->id == 108);

    // Release
    assert(registry.release(handle));
    assert(!registry.release(handle)); // Double free blocked!
    assert(registry.resolve(handle) == nullptr);
    assert(registry.count() == 0);
}

void test_converters() {
    mca::contract::ErrorEnvelope error;
    error.code = mca::contract::StableErrorCode::MDL_NOT_FOUND;
    error.user_message_key = "error.model.not_found";
    error.public_message = "Model missing";
    error.native_code = 404;

    std::string json = mca::jni::JniConverters::error_to_json(error);
    assert(json.find("\"code\":\"MDL_NOT_FOUND\"") != std::string::npos);
    assert(json.find("\"nativeCode\":404") != std::string::npos);
}

class MockLlamaGateway : public mca::jni::ILlamaGateway {
public:
    int32_t load_model(const std::string&, const std::string&) override { return 0; }
    void unload_model() override { unloaded_ = true; }
    int32_t begin_completion(const std::string&) override { return 1; }
    std::string generate_next_chunk() override { return "token"; }
    void request_stop() override { stopped_ = true; }
    std::string get_runtime_stats_json() override { return "{}"; }

    bool unloaded_{false};
    bool stopped_{false};
};

void test_gateway_interface() {
    std::unique_ptr<mca::jni::ILlamaGateway> gateway = std::make_unique<MockLlamaGateway>();
    assert(gateway->load_model("path", "{}") == 0);
    assert(gateway->generate_next_chunk() == "token");
    gateway->request_stop();
    gateway->unload_model();
}

int main() {
    std::cout << "Running MCA JNI Boundary Unit Tests..." << std::endl;
    test_handle_registry();
    test_converters();
    test_gateway_interface();
    std::cout << "ALL MCA JNI BOUNDARY TESTS PASSED!" << std::endl;
    return 0;
}
