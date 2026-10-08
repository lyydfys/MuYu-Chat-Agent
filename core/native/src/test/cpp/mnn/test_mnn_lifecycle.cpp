#include "mca/mnn/mnn_contracts.hpp"
#include "mca/mnn/mnn_runtime_loader.hpp"
#include "mca/mnn/mnn_backend_owner.hpp"
#include "mca/mnn/mnn_engine.hpp"
#include "mca/mnn/mnn_session.hpp"
#include "mca/mnn/mnn_request.hpp"
#include "mca/mnn/mnn_stream_router.hpp"
#include "mca/mnn/mnn_token_emitter.hpp"
#include "mca/prompt/mnn_qnn_prompt_gateway.hpp"
#include "mca/runtime/process_runtime_host.hpp"

#include <cassert>
#include <iostream>
#include <vector>
#include <string>

using namespace mca::mnn;
using namespace mca::prompt;
using namespace mca::contract;
using namespace mca::runtime;

void test_token_emitter_utf8_split() {
    std::cout << "[Test] test_token_emitter_utf8_split..." << std::endl;
    MnnTokenEmitter emitter({"<|im_end|>"});

    // Case 1: Chinese character "你" is 3 bytes in UTF-8: 0xE4, 0xBD, 0xA0
    const std::string part1 = "\xE4\xBD"; // Incomplete 2 bytes
    const std::string part2 = "\xA0\xE5\xA5\xBD"; // 3rd byte of "你" + "好" (0xE5, 0xA5, 0xBD)

    auto res1 = emitter.feed_chunk(part1);
    // Because part1 ends in an incomplete 3-byte character, it must buffer part1 and emit nothing
    assert(res1.visible_text.empty());
    assert(!res1.is_stopped);

    auto res2 = emitter.feed_chunk(part2);
    // Upon receiving the remainder, both "你" and "好" must be fully assembled and emitted
    assert(res2.visible_text == "你好");
    assert(!res2.is_stopped);

    // Case 2: Emoji "🚀" is 4 bytes in UTF-8: 0xF0, 0x9F, 0x9A, 0x80
    emitter.reset({"<|im_end|>"});
    const std::string e1 = "Hello \xF0"; // 1 byte of 4-byte sequence
    const std::string e2 = "\x9F\x9A";   // next 2 bytes
    const std::string e3 = "\x80 World"; // final byte + ASCII

    auto r1 = emitter.feed_chunk(e1);
    assert(r1.visible_text == "Hello ");

    auto r2 = emitter.feed_chunk(e2);
    assert(r2.visible_text.empty());

    auto r3 = emitter.feed_chunk(e3);
    assert(r3.visible_text == "🚀 World");

    // Case 3: Stop sequence buffering across chunk boundaries: "<|" then "im_end|>"
    emitter.reset({"<|im_end|>"});
    auto s1 = emitter.feed_chunk("Completion text <|");
    // The "<|" prefix is buffered as a potential stop marker
    assert(s1.visible_text == "Completion text ");
    assert(!s1.is_stopped);

    auto s2 = emitter.feed_chunk("im_end|> should not appear");
    assert(s2.visible_text.empty());
    assert(s2.is_stopped);
    assert(s2.stop_reason == "<|im_end|>");

    // Case 4: Flush uncompleted false stop prefix
    emitter.reset({"<|im_end|>"});
    auto f1 = emitter.feed_chunk("Text <|not_a_stop");
    assert(f1.visible_text == "Text <|not_a_stop");
    auto flushed = emitter.flush();
    assert(flushed.visible_text.empty());

    std::cout << "  -> test_token_emitter_utf8_split PASSED" << std::endl;
}

void test_stream_router_order_and_terminal() {
    std::cout << "[Test] test_stream_router_order_and_terminal..." << std::endl;

    auto callback_router = std::make_shared<mca::callback::CallbackRouter>();
    std::vector<CallbackEvent> delivered_events;

    callback_router->set_sink([&delivered_events](const CallbackEvent& ev) {
        delivered_events.push_back(ev);
    });

    RequestIdentity identity;
    identity.request_id = "req_stream_test";
    identity.attempt_id = 0;
    identity.session_generation = 1;
    identity.runtime_epoch = ProcessRuntimeHost::instance().get_runtime_epoch();
    identity.native_sequence = 0;

    MnnChatRequest chat_req;
    chat_req.prompt = "Tell me a joke";

    auto request = std::make_shared<MnnRequest>(identity, chat_req, nullptr);
    MnnStreamRouter router(callback_router, request);

    assert(router.emit_token(0, "Why", false));
    assert(router.emit_token(1, " did", false));
    assert(router.emit_token(2, " the", false));
    assert(router.emit_progress(3.0f, 10.0f, "decoding", "in progress"));

    assert(delivered_events.size() == 4);
    assert(delivered_events[0].sequence == 1);
    assert(delivered_events[1].sequence == 2);
    assert(delivered_events[2].sequence == 3);
    assert(delivered_events[3].sequence == 4);

    // Verify monotonic sequencing
    for (size_t i = 1; i < delivered_events.size(); ++i) {
        assert(delivered_events[i].sequence > delivered_events[i - 1].sequence);
    }

    // Terminal exactly once
    assert(router.emit_terminal(RequestState::Succeeded, 3, 150));
    assert(!router.emit_terminal(RequestState::Failed, 3, 160)); // Duplicate terminal rejected!
    assert(delivered_events.back().is_terminal);

    // No events permitted after terminal
    assert(!router.emit_token(3, " extra", false));

    assert(router.drain(500));
    std::cout << "  -> test_stream_router_order_and_terminal PASSED" << std::endl;
}

void test_session_isolation_and_generation() {
    std::cout << "[Test] test_session_isolation_and_generation..." << std::endl;

    ProcessRuntimeHost::instance().initialize();
    MnnRuntimeConfig config;
    auto backend = std::make_shared<MnnBackendOwner>(config);
    assert(backend->initialize());

    MnnModelMetadata meta;
    meta.model_id = "qwen2.5-test";
    meta.context_window = 2048;
    meta.vocab_size = 32000;

    auto engine = std::make_shared<MnnEngine>(backend, "qwen2.5-test", "models/qwen2.5", meta);
    assert(engine->load_model());

    auto session = engine->create_mnn_session(1);
    assert(session != nullptr);
    assert(session->get_generation() == 1);

    // Append tokens to KV cache in Turn 1
    session->append_cached_tokens({101, 102, 103});
    assert(session->get_cached_token_count() == 3);

    // Advance generation for Turn 2
    const int64_t gen2 = session->advance_generation();
    assert(gen2 == 2);
    // Generation isolation: advancing generation isolates / resets KV cache
    assert(session->get_cached_token_count() == 0);

    // Create request in gen2
    MnnChatRequest req2;
    req2.prompt = "Second turn prompt";
    auto mnn_req = session->create_mnn_request("req_gen_2", req2, "trace_gen2");
    assert(mnn_req != nullptr);
    assert(mnn_req->get_identity().session_generation == 2);

    // Test serialized lease on session
    assert(session->try_acquire_execution_lease());
    assert(!session->try_acquire_execution_lease()); // Concurrent lease blocked!
    session->release_execution_lease();
    assert(session->try_acquire_execution_lease()); // Reacquired after release
    session->release_execution_lease();

    session->close();
    assert(!session->is_valid());

    std::cout << "  -> test_session_isolation_and_generation PASSED" << std::endl;
}

void test_cancel_lifecycle_and_transitions() {
    std::cout << "[Test] test_cancel_lifecycle_and_transitions..." << std::endl;

    RequestIdentity identity;
    identity.request_id = "req_cancel_test";
    identity.attempt_id = 0;
    identity.session_generation = 1;
    identity.runtime_epoch = 1;

    MnnChatRequest chat_req;
    chat_req.prompt = "Test prompt";

    auto req = std::make_shared<MnnRequest>(identity, chat_req, nullptr);
    assert(req->get_state() == RequestState::Accepted);

    // Transition Accepted -> Preparing -> Running
    assert(req->transition_state(RequestState::Preparing));
    assert(req->get_state() == RequestState::Preparing);
    assert(req->transition_state(RequestState::Running));
    assert(req->get_state() == RequestState::Running);

    // Cancel requested
    assert(!req->is_cancel_requested());
    req->request_cancel();
    assert(req->is_cancel_requested());
    assert(req->should_stop());
    assert(req->get_state() == RequestState::CancelRequested);

    // Transition to DrainingCallbacks -> Cancelled
    assert(req->transition_state(RequestState::DrainingCallbacks));
    assert(req->transition_state(RequestState::Cancelled));
    assert(req->is_terminal());

    // Once in terminal state, further transitions are rejected
    assert(!req->transition_state(RequestState::Succeeded));
    assert(!req->transition_state(RequestState::Running));

    std::cout << "  -> test_cancel_lifecycle_and_transitions PASSED" << std::endl;
}

void test_universal_device_admission() {
    std::cout << "[Test] test_universal_device_admission..." << std::endl;

    // Project Invariant:
    // Never use a chipset, device-model, validation-device, certification, profile,
    // allowlist, or whitelist match to hide or block a user-facing download, import, load, or run action.
    auto& loader = MnnRuntimeLoader::instance();
    auto advisory = loader.probe_hardware();

    // Verify advisory has recommended backend
    assert(advisory.recommended_backend == MnnBackendType::Cpu ||
           advisory.recommended_backend == MnnBackendType::OpenCL);
    assert(HardwareProbeAdvisory::universal_device_admission_enforced());

    // Even if configured with Auto on unknown hardware, backend owner succeeds
    // by falling back to the generic compatible CPU path!
    MnnRuntimeConfig config;
    config.backend_type = MnnBackendType::Auto;
    config.device_local_tuning["advisory_key"] = "test_tuning_val";

    auto backend = std::make_shared<MnnBackendOwner>(config);
    assert(backend->initialize());
    assert(backend->is_valid());
    assert(backend->get_tuning_parameter("advisory_key") == "test_tuning_val");

    // Requested OpenCL without GPU driver must fall back gracefully without blocking admission
    MnnRuntimeConfig ocl_config;
    ocl_config.backend_type = MnnBackendType::OpenCL;
    auto ocl_backend = std::make_shared<MnnBackendOwner>(ocl_config);
    assert(ocl_backend->initialize()); // Must NOT throw or reject!
    assert(ocl_backend->is_valid());

    std::cout << "  -> test_universal_device_admission PASSED" << std::endl;
}

void test_prompt_gateway() {
    std::cout << "[Test] test_prompt_gateway..." << std::endl;

    auto& gateway = MnnQnnPromptGateway::instance();
    gateway.clear();

    PromptHandoffRecord record;
    record.tokenizer_path = "models/tokenizer.json";
    record.prompt_pair_sha256 = "sha256_mock_pair_hash_12345";
    record.payload_sha256 = "sha256_mock_payload_hash_67890";

    std::string handle;
    std::string err;
    assert(gateway.issue_handoff(record, handle, err));
    assert(!handle.empty());
    assert(gateway.active_record_count() == 1);

    // Consume with matching record
    PromptHandoffRecord consumed;
    assert(gateway.consume_handoff(handle, record, consumed, err));
    assert(consumed.prompt_pair_sha256 == record.prompt_pair_sha256);
    assert(gateway.active_record_count() == 0); // Single-use lease consumed!

    // Repeat consumption fails
    PromptHandoffRecord second_try;
    assert(!gateway.consume_handoff(handle, record, second_try, err));

    std::cout << "  -> test_prompt_gateway PASSED" << std::endl;
}

int main() {
    std::cout << "==================================================" << std::endl;
    std::cout << "  MCA MNN / llama / Chat Native Unit & Lifecycle  " << std::endl;
    std::cout << "==================================================" << std::endl;

    test_token_emitter_utf8_split();
    test_stream_router_order_and_terminal();
    test_session_isolation_and_generation();
    test_cancel_lifecycle_and_transitions();
    test_universal_device_admission();
    test_prompt_gateway();

    std::cout << "==================================================" << std::endl;
    std::cout << "  ALL MCA MNN LIFECYCLE TESTS PASSED!             " << std::endl;
    std::cout << "==================================================" << std::endl;
    return 0;
}
