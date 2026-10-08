#include "mca/qnn/qnn_backend_owner.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_engine.hpp"
#include "mca/qnn/qnn_request.hpp"
#include "mca/qnn/qnn_runtime_loader.hpp"
#include "mca/runtime/process_runtime_host.hpp"

#include <cassert>
#include <iostream>

using namespace mca::qnn;
using namespace mca::contract;

void test_unknown_device_remains_admitted() {
    const auto report = QnnCapabilityProbe::probe("unknown-chipset", 0);
    assert(report.is_admitted);
    assert(report.is_generic_fallback);
    assert(report.recommended_transport == "generic_htp");
}

void test_missing_runtime_never_creates_a_ready_engine() {
    mca::runtime::ProcessRuntimeHost::instance().initialize();

    auto backend = std::make_shared<QnnBackendOwner>(
        std::make_shared<QnnRuntimeLoader>(), "missing-runtime-test");
    const auto report = QnnCapabilityProbe::probe("unknown-chipset", 0);
    std::string error;
    backend->initialize_with_probe(report, &error);

    auto engine = std::make_shared<QnnEngine>(backend, "model", "/models/model");
    assert(!backend->is_valid());
    assert(!engine->is_valid());
    assert(engine->create_qnn_session() == nullptr);

    SdxlExecutionContract contract;
    contract.prompt = "A real graph executor is required";
    QnnRequest request(RequestIdentity{"request-no-graph"}, nullptr, contract);
    assert(request.execute() == RequestState::Failed);
    assert(request.is_terminal());
    assert(request.get_state() == RequestState::Failed);
    assert(request.get_current_phase() == SdxlPhase::Failed);
    assert(request.get_artifacts().empty());
}

void test_cancellation_is_terminal_without_fabricating_output() {
    SdxlExecutionContract contract;
    contract.prompt = "Cancellation test";
    QnnRequest request(RequestIdentity{"request-cancel"}, nullptr, contract);

    request.request_cancel();
    assert(request.get_state() == RequestState::CancelRequested);
    assert(request.execute() == RequestState::Cancelled);
    assert(request.get_state() == RequestState::Cancelled);
    assert(request.get_current_phase() == SdxlPhase::Cancelled);
    assert(request.get_artifacts().empty());
}

void test_loader_reports_missing_library() {
    QnnRuntimeLoader loader;
    std::string error;
    assert(!loader.load_system("/definitely/missing/libQnnSystem.so", &error));
    assert(!error.empty());
    assert(!loader.is_system_loaded());
}

int main() {
    test_unknown_device_remains_admitted();
    test_missing_runtime_never_creates_a_ready_engine();
    test_cancellation_is_terminal_without_fabricating_output();
    test_loader_reports_missing_library();
    std::cout << "QNN lifecycle fail-closed tests passed." << std::endl;
    return 0;
}
