#include "mca/runtime/process_runtime_host.hpp"
#include "mca/runtime/backend_runtime.hpp"
#include "mca/runtime/engine.hpp"
#include "mca/runtime/engine_session.hpp"
#include "mca/request/native_request_context.hpp"
#include "mca/callback/callback_router.hpp"

#include <cassert>
#include <iostream>
#include <stdexcept>
#include <vector>

using namespace mca::runtime;
using namespace mca::request;
using namespace mca::callback;
using namespace mca::contract;

void test_runtime_host_and_epoch() {
    auto& host = ProcessRuntimeHost::instance();
    host.initialize();
    assert(host.is_healthy());

    const int64_t ep1 = host.get_runtime_epoch();
    assert(ep1 >= 1);

    const int64_t ep2 = host.increment_epoch();
    assert(ep2 == ep1 + 1);
    assert(host.get_runtime_epoch() == ep2);

    host.mark_unhealthy();
    assert(!host.is_healthy());
}

void test_lifecycle_chain() {
    auto& host = ProcessRuntimeHost::instance();
    host.initialize();
    host.increment_epoch();

    auto backend = std::make_shared<BackendRuntime>(BackendKind::MnnLlm, "mnn-test-backend");
    assert(backend->is_valid());

    auto engine = std::make_shared<Engine>(backend, "qwen-2.5", "/models/qwen");
    assert(engine->is_valid());

    auto session = engine->create_session(1);
    assert(session != nullptr);
    assert(session->is_valid());
    assert(session->get_generation() == 1);

    auto request = session->create_request("req-test-1", "trace-xyz");
    assert(request != nullptr);
    assert(!request->is_terminal());
    assert(!request->is_cancel_requested());

    assert(!request->mark_terminal(RequestState::Succeeded));
    assert(request->transition_to(RequestState::Preparing));
    assert(request->transition_to(RequestState::Running));

    // Invariant: Terminal exactly-once
    assert(request->mark_terminal(RequestState::Succeeded));
    assert(!request->mark_terminal(RequestState::Failed)); // Repeated terminal ignored!
    assert(request->is_terminal());

    // Invalidation by epoch
    assert(backend->is_valid());
    host.increment_epoch();
    // Older backend is now invalid relative to new epoch
    assert(!backend->is_valid());
    assert(!engine->is_valid());
    assert(!session->is_valid());
}

void test_callback_router() {
    CallbackRouter router;
    int received_count = 0;
    router.set_sink([&received_count](const CallbackEvent&) {
        received_count++;
    });

    RequestIdentity identity;
    identity.request_id = "req-cb";
    identity.runtime_epoch = 5;
    identity.session_generation = 2;

    CallbackEvent event;
    event.identity = identity;
    event.sequence = 1;
    event.is_terminal = false;

    // Normal dispatch
    auto res1 = router.dispatch(event, 5, 2);
    assert(res1 == DispatchResult::Delivered);
    assert(received_count == 1);

    // Stale epoch event
    auto resStale = router.dispatch(event, 6, 2);
    assert(resStale == DispatchResult::StaleRejected);
    assert(received_count == 1); // Not delivered

    // Terminal event
    event.sequence = 2;
    event.is_terminal = true;
    auto resTerm1 = router.dispatch(event, 5, 2);
    assert(resTerm1 == DispatchResult::Delivered);
    assert(received_count == 2);

    // Duplicate terminal event
    auto resTerm2 = router.dispatch(event, 5, 2);
    assert(resTerm2 == DispatchResult::DuplicateTerminalIgnored);
    assert(received_count == 2);

    event.identity.attempt_id = 1;
    event.sequence = 3;
    assert(router.dispatch(event, 5, 2) == DispatchResult::StaleRejected);

    CallbackRouter throwing_router;
    throwing_router.set_sink([](const CallbackEvent&) {
        throw std::runtime_error("sink failure");
    });
    event.identity = identity;
    event.is_terminal = false;
    event.sequence = 1;
    assert(throwing_router.dispatch(event, 5, 2) == DispatchResult::SinkFailed);
    assert(throwing_router.pending_count() == 0);
    assert(throwing_router.drain(10));

    // Drain
    assert(router.drain(500));
}

int main() {
    std::cout << "Running MCA Native Lifecycle Chain Unit Tests..." << std::endl;
    test_runtime_host_and_epoch();
    test_lifecycle_chain();
    test_callback_router();
    std::cout << "ALL MCA NATIVE LIFECYCLE CHAIN TESTS PASSED!" << std::endl;
    return 0;
}
