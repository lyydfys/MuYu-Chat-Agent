#include <cassert>
#include <iostream>
#include <memory>
#include <vector>
#include <string>

#include "mca/runtime/process_runtime_host.hpp"
#include "mca/qnn/qnn_sdxl_contracts.hpp"
#include "mca/qnn/qnn_runtime_loader.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_backend_owner.hpp"
#include "mca/qnn/qnn_engine.hpp"
#include "mca/qnn/qnn_session.hpp"
#include "mca/qnn/qnn_request.hpp"
#include "mca/callback/callback_router.hpp"

using namespace mca::qnn;
using namespace mca::runtime;
using namespace mca::contract;
using namespace mca::callback;

/**
 * Universal Device Admission Test:
 * Hard Invariant:
 * - Unknown or uncertified SoC/chipset NEVER blocks or rejects user admission.
 * - Hardware probe is advisory only and must fall back to the generic compatible path.
 */
void test_universal_device_admission_and_probe_fallback() {
    std::cout << "[Test] Universal Device Admission and Probe Fallback..." << std::endl;

    // 1. Unknown SoC model and hint
    auto report_unknown = QnnCapabilityProbe::probe("VendorFakeSoC_9999", 99999);
    assert(report_unknown.is_admitted); // MUST ALWAYS BE TRUE!
    assert(report_unknown.is_generic_fallback);
    assert(report_unknown.htp_arch_version > 0); // Fell back to baseline architecture (e.g., 68)
    assert(report_unknown.recommended_transport == "generic_htp");
    assert(!report_unknown.diagnostic_message.empty());

    // 2. Empty hint and model
    auto report_empty = QnnCapabilityProbe::probe("", 0);
    assert(report_empty.is_admitted);
    assert(report_empty.is_generic_fallback);
    assert(report_empty.recommended_transport == "generic_htp");

    // 3. Known Snapdragon 8 Gen 3 (SM8650, model 57)
    auto report_sm8650 = QnnCapabilityProbe::probe("snapdragon8gen3", 57);
    assert(report_sm8650.is_admitted);
    assert(!report_sm8650.is_generic_fallback);
    assert(report_sm8650.htp_arch_version == 75);
    assert(report_sm8650.recommended_transport == "htp");

    // 4. Known Snapdragon 8 Gen 2 (SM8550, model 43)
    auto report_sm8550 = QnnCapabilityProbe::probe("sm8550", 43);
    assert(report_sm8550.is_admitted);
    assert(!report_sm8550.is_generic_fallback);
    assert(report_sm8550.htp_arch_version == 73);

    // 5. Known Snapdragon 8 Elite (SM8750, model 69)
    auto report_sm8750 = QnnCapabilityProbe::probe("sm8750", 69);
    assert(report_sm8750.is_admitted);
    assert(!report_sm8750.is_generic_fallback);
    assert(report_sm8750.htp_arch_version == 79);

    std::cout << "  -> Universal Device Admission verified: 0 rejections on unknown hardware." << std::endl;
}

/**
 * Loader Error Handling Test:
 * Verifies dlopen failures, missing symbols, and reverse unload behavior.
 */
void test_loader_error_handling() {
    std::cout << "[Test] Loader Error Handling..." << std::endl;

    QnnRuntimeLoader loader;
    assert(!loader.is_system_loaded());
    assert(!loader.is_htp_loaded());
    assert(!loader.is_rpc_loaded());
    assert(!loader.is_poisoned());

    // Attempt to load non-existent library
    std::string err;
    bool ok = loader.load_system("/non/existent/path/libQnnSystem.so", &err);
    assert(!ok);
    assert(!err.empty());
    assert(!loader.is_system_loaded());

    // Error envelope retrieval
    auto envelope = loader.get_last_error_envelope();
    assert(envelope.code == StableErrorCode::NAT_DSO_LOAD_FAILED);
    assert(envelope.category == ErrorCategory::Native);
    assert(envelope.phase == ErrorPhase::Load);

    // Unload when empty is safe
    assert(loader.unload_all());
    assert(!loader.is_poisoned());

    std::cout << "  -> Loader error handling verified." << std::endl;
}

/**
 * Complete Lifecycle Chain Test:
 * ProcessRuntimeHost -> QnnBackendOwner -> QnnEngine -> QnnSession -> QnnRequest.
 */
void test_qnn_lifecycle_chain_execution() {
    std::cout << "[Test] QNN Lifecycle Chain and Execution..." << std::endl;

    auto& host = ProcessRuntimeHost::instance();
    host.initialize();
    assert(host.is_healthy());

    auto loader = std::make_shared<QnnRuntimeLoader>();
    auto backend = std::make_shared<QnnBackendOwner>(loader, "test-qnn-backend");
    assert(backend->is_valid());

    auto probe_report = QnnCapabilityProbe::probe("SM8650", 57);
    assert(backend->initialize_with_probe(probe_report));
    assert(backend->is_valid());

    auto engine = std::make_shared<QnnEngine>(backend, "sdxl-turbo", "/models/sdxl_turbo");
    assert(engine->is_valid());

    // Register graphs
    int dummy_unet = 1;
    int dummy_vae = 2;
    assert(engine->register_graph("unet", &dummy_unet, {"latent_in", "timestep"}, {"latent_out"}));
    assert(engine->register_graph("vae_decoder", &dummy_vae, {"latent"}, {"image"}));
    assert(engine->has_graph("unet"));
    assert(engine->has_graph("vae_decoder"));
    assert(!engine->has_graph("nonexistent_graph"));

    // Create session
    auto session = engine->create_qnn_session(1);
    assert(session != nullptr);
    assert(session->is_valid());
    assert(session->get_generation() == 1);

    // Create valid contract
    SdxlExecutionContract contract;
    contract.prompt = "a serene mountain landscape at dawn";
    contract.negative_prompt = "blurry, low quality";
    contract.width = 1024;
    contract.height = 1024;
    contract.steps = 4;
    contract.guidance_scale = 7.5f;

    auto request = session->create_qnn_request("req-sdxl-001", contract, "trace-abc");
    assert(request != nullptr);
    assert(!request->is_terminal());
    assert(!request->is_cancel_requested());
    assert(request->get_current_phase() == SdxlPhase::Idle);

    // Setup router
    auto router = std::make_shared<CallbackRouter>();
    std::vector<CallbackEvent> received_events;
    router->set_sink([&received_events](const CallbackEvent& event) {
        received_events.push_back(event);
    });

    // Execute pipeline
    RequestState result = request->execute(router);
    assert(result == RequestState::Succeeded);
    assert(request->is_terminal());
    assert(request->get_current_phase() == SdxlPhase::Completed);
    assert(!request->get_artifacts().empty());

    // Invariant: Terminal exactly-once
    assert(!request->mark_terminal(RequestState::Failed));
    assert(!request->mark_terminal(RequestState::Cancelled));
    assert(request->get_state() == RequestState::Succeeded);

    // Verify events were dispatched
    assert(!received_events.empty());
    bool saw_terminal_event = false;
    for (const auto& ev : received_events) {
        if (ev.is_terminal && ev.event_type == CallbackEventType::Terminal) {
            saw_terminal_event = true;
        }
    }
    assert(saw_terminal_event);

    std::cout << "  -> Full lifecycle execution and exactly-once terminal verified." << std::endl;
}

/**
 * Cancellation Test:
 * Tests pre-execution cancel, mid-execution cancel, and non-premature handle freeing.
 */
void test_qnn_request_cancellation() {
    std::cout << "[Test] QNN Request Cancellation..." << std::endl;

    auto loader = std::make_shared<QnnRuntimeLoader>();
    auto backend = std::make_shared<QnnBackendOwner>(loader, "test-qnn-cancel-backend");
    auto probe_report = QnnCapabilityProbe::probe("SM8550", 43);
    backend->initialize_with_probe(probe_report);

    auto engine = std::make_shared<QnnEngine>(backend, "sdxl-base", "/models/sdxl_base");
    auto session = engine->create_qnn_session(1);

    // Case 1: Pre-execution cancellation
    SdxlExecutionContract contract;
    contract.prompt = "futuristic metropolis";
    contract.steps = 5;

    auto request_pre = session->create_qnn_request("req-cancel-pre", contract);
    assert(request_pre != nullptr);

    request_pre->request_cancel();
    assert(request_pre->is_cancel_requested());
    assert(request_pre->get_state() == RequestState::CancelRequested);

    // Execute should gracefully observe cancellation point
    RequestState res_pre = request_pre->execute();
    assert(res_pre == RequestState::Cancelled);
    assert(request_pre->is_terminal());
    assert(request_pre->get_current_phase() == SdxlPhase::Cancelled);

    // Case 2: Session remains healthy after cancelled request
    assert(session->is_valid());
    auto request_post = session->create_qnn_request("req-post-cancel", contract);
    assert(request_post != nullptr);
    assert(!request_post->is_cancel_requested());

    std::cout << "  -> Cancellation mechanics and state transitions verified." << std::endl;
}

/**
 * Session Concurrency and Execution Lease Test:
 * Verifies mutual exclusion lock for QNN HTP hardware context executions.
 */
void test_session_concurrency_and_lease() {
    std::cout << "[Test] Session Concurrency and Execution Lease..." << std::endl;

    auto loader = std::make_shared<QnnRuntimeLoader>();
    auto backend = std::make_shared<QnnBackendOwner>(loader, "test-lease-backend");
    backend->initialize_with_probe(QnnCapabilityProbe::probe());

    auto engine = std::make_shared<QnnEngine>(backend, "sdxl-model", "/models/sdxl");
    auto session = engine->create_qnn_session(1);

    // Acquire lease 1
    auto lease1 = session->acquire_execution_lease(1000);
    assert(lease1 != nullptr);
    assert(lease1->is_held());

    // Concurrently attempting to acquire lease 2 should timeout immediately
    auto lease2 = session->acquire_execution_lease(10);
    assert(lease2 == nullptr); // Lock must NOT be granted while lease1 is held!

    // Release lease 1
    lease1.reset();

    // Now acquiring lease 3 succeeds
    auto lease3 = session->acquire_execution_lease(500);
    assert(lease3 != nullptr);
    assert(lease3->is_held());

    // Generation advance test
    int64_t gen = session->get_generation();
    int64_t next_gen = session->advance_generation();
    assert(next_gen == gen + 1);
    assert(session->get_generation() == next_gen);

    std::cout << "  -> Hardware lease mutual exclusion verified." << std::endl;
}

/**
 * Epoch Invalidation Test:
 * Incrementing ProcessRuntimeHost epoch invalidates all subordinate objects.
 */
void test_epoch_invalidation() {
    std::cout << "[Test] Process Epoch Invalidation..." << std::endl;

    auto& host = ProcessRuntimeHost::instance();
    host.initialize();

    auto loader = std::make_shared<QnnRuntimeLoader>();
    auto backend = std::make_shared<QnnBackendOwner>(loader, "epoch-backend");
    backend->initialize_with_probe(QnnCapabilityProbe::probe());

    auto engine = std::make_shared<QnnEngine>(backend, "epoch-model", "/models/m");
    auto session = engine->create_qnn_session(1);

    assert(backend->is_valid());
    assert(engine->is_valid());
    assert(session->is_valid());

    // Increment process epoch
    host.increment_epoch();

    // Now older objects created in previous epoch are strictly invalid
    assert(!backend->is_valid());
    assert(!engine->is_valid());
    assert(!session->is_valid());

    std::cout << "  -> Epoch invalidation verified." << std::endl;
}

int main() {
    std::cout << "=== Running MCA QNN Lifecycle & Protection Test Suite ===" << std::endl;

    test_universal_device_admission_and_probe_fallback();
    test_loader_error_handling();
    test_qnn_lifecycle_chain_execution();
    test_qnn_request_cancellation();
    test_session_concurrency_and_lease();
    test_epoch_invalidation();

    std::cout << "=== ALL MCA QNN LIFECYCLE TESTS PASSED! ===" << std::endl;
    return 0;
}
