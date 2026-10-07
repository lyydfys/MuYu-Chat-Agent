#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif

#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "mca/common/logging.hpp"
#include "mca/contract/callback_event.hpp"
#include "mca/contract/error_envelope.hpp"
#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"
#include "mca/contract/stable_error_code.hpp"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_input_contract.hpp"
#include "mca/sd/sd_output_transaction.hpp"
#include "mca/sd/sd_phase_machine.hpp"
#include "mca/sd/sd_request.hpp"
#include "mca/sd/sd_runtime_owner.hpp"

namespace mca {
namespace sd {
namespace test {

// ============================================================================
// NON_PRODUCTION Test Double Backend
// ============================================================================

class TestSdEngineBackend_NON_PRODUCTION : public ISdEngineBackend {
public:
    bool fail_tokenize{false};
    bool fail_prepare_latents{false};
    int32_t fail_at_step{-1};
    bool fail_decode{false};
    int32_t steps_executed{0};

    bool tokenize_and_condition(
        const ImageInputContract& /*contract*/,
        std::string& error_out
    ) override {
        if (fail_tokenize) {
            error_out = "Injected tokenize failure";
            return false;
        }
        return true;
    }

    bool prepare_latents(
        const ImageInputContract& contract,
        std::vector<float>& latents_out,
        std::string& error_out
    ) override {
        if (fail_prepare_latents) {
            error_out = "Injected prepare latents failure";
            return false;
        }
        latents_out.resize(static_cast<size_t>(contract.width * contract.height / 64 * 4), 0.5f);
        return true;
    }

    bool execute_denoise_step(
        int32_t step,
        int32_t /*total_steps*/,
        std::vector<float>& /*latents*/,
        std::string& error_out
    ) override {
        if (fail_at_step >= 0 && step == fail_at_step) {
            error_out = "Injected denoise failure at step " + std::to_string(step);
            return false;
        }
        steps_executed++;
        return true;
    }

    bool generate_preview_frame(
        int32_t step,
        const std::vector<float>& /*latents*/,
        GeneratedImageResult& preview_out,
        std::string& /*error_out*/
    ) override {
        preview_out.batch_index = 0;
        preview_out.width = 128;
        preview_out.height = 128;
        preview_out.channels = 4;
        preview_out.pixel_data.resize(128 * 128 * 4, 128);
        preview_out.sha256_hex = "preview_sha256_step_" + std::to_string(step);
        return true;
    }

    bool decode_latents_to_pixels(
        const ImageInputContract& contract,
        const std::vector<float>& /*latents*/,
        GeneratedImageResult& final_image_out,
        std::string& error_out
    ) override {
        if (fail_decode) {
            error_out = "Injected decode latents failure";
            return false;
        }
        final_image_out.batch_index = 0;
        final_image_out.width = contract.width;
        final_image_out.height = contract.height;
        final_image_out.channels = 3;
        final_image_out.pixel_data.resize(
            static_cast<size_t>(contract.width * contract.height * 3), 200
        );
        return true;
    }
};

// ============================================================================
// Test Utilities
// ============================================================================

std::string get_temp_test_dir() {
#if defined(_WIN32)
    const char* tmp = std::getenv("TEMP");
    if (tmp != nullptr) return std::string(tmp);
    return "C:\\Windows\\Temp";
#else
    const char* tmp = std::getenv("TMPDIR");
    if (tmp != nullptr) return std::string(tmp);
    return "/tmp";
#endif
}

std::string create_test_file(const std::string& name, const std::string& content) {
    std::string path = get_temp_test_dir() + "/" + name;
    std::ofstream file(path, std::ios::binary);
    file << content;
    file.close();
    return path;
}

void cleanup_test_file(const std::string& path) {
    std::remove(path.c_str());
    std::remove((path + ".part").c_str());
}

mca::contract::RequestIdentity make_test_identity(const std::string& id) {
    mca::contract::RequestIdentity ident;
    ident.request_id = id;
    ident.attempt_id = 1;
    ident.session_generation = 1;
    ident.runtime_epoch = 1;
    ident.worker_incarnation = 1;
    ident.native_sequence = 100;
    ident.trace_id = "trace-" + id;
    return ident;
}

// ============================================================================
// Test 1: Universal Device Admission (MCA Project Invariant)
// ============================================================================

void test_universal_device_admission() {
    std::cout << "[RUN] test_universal_device_admission" << std::endl;
    SdRuntimeOwner owner("TestRuntime");

    // Case A: Completely unknown chipset and device
    auto status1 = owner.evaluate_device_admission("UnknownVendor-SoC-9999", "VendorX-Phone");
    assert(status1.admitted == true);

    // Case B: Blank or generic chipset
    auto status2 = owner.evaluate_device_admission("unknown", "GenericDevice");
    assert(status2.admitted == true);
    assert(status2.is_generic_fallback == true);

    // Case C: Empty string fallback
    auto status3 = owner.evaluate_device_admission("", "GenericDevice");
    assert(status3.admitted == true);
    assert(status3.is_generic_fallback == true);

    std::cout << "[PASS] test_universal_device_admission" << std::endl;
}

// ============================================================================
// Test 2: Input Contract Validation
// ============================================================================

void test_input_contract_validation() {
    std::cout << "[RUN] test_input_contract_validation" << std::endl;
    SdInputValidator validator;
    auto identity = make_test_identity("req-input-test");

    // Case A: Valid contract passes
    ImageInputContract valid_contract;
    valid_contract.prompt = "A majestic cat sitting on a throne";
    valid_contract.width = 512;
    valid_contract.height = 512;
    valid_contract.steps = 20;
    valid_contract.cfg_scale = 7.5;
    valid_contract.strength = 1.0;
    valid_contract.batch_count = 1;
    valid_contract.task_mode = ImageTaskMode::TextToImage;

    auto err1 = validator.validate(valid_contract, identity);
    assert(!err1.has_value());

    // Case B: Non-multiple of 8 width fails
    ImageInputContract bad_dim = valid_contract;
    bad_dim.width = 515;
    auto err2 = validator.validate(bad_dim, identity);
    assert(err2.has_value());
    assert(err2->code == mca::contract::StableErrorCode::VAL_INVALID_INPUT);

    // Case C: Out of bounds steps (> 150) fails
    ImageInputContract bad_steps = valid_contract;
    bad_steps.steps = 200;
    auto err3 = validator.validate(bad_steps, identity);
    assert(err3.has_value());
    assert(err3->code == mca::contract::StableErrorCode::VAL_INVALID_INPUT);

    // Case D: Batch count > 8 fails
    ImageInputContract bad_batch = valid_contract;
    bad_batch.batch_count = 12;
    auto err4 = validator.validate(bad_batch, identity);
    assert(err4.has_value());
    assert(err4->code == mca::contract::StableErrorCode::VAL_INVALID_INPUT);

    // Case E: Inpaint task requires mask file
    ImageInputContract inpaint_no_mask = valid_contract;
    inpaint_no_mask.task_mode = ImageTaskMode::Inpaint;
    inpaint_no_mask.input_image_path = create_test_file("inpaint_in.tmp", "dummy_image_data");
    inpaint_no_mask.mask_image_path = ""; // Missing
    auto err5 = validator.validate(inpaint_no_mask, identity);
    assert(err5.has_value());
    assert(err5->code == mca::contract::StableErrorCode::VAL_MISSING_REQUIRED_FIELD);
    cleanup_test_file(inpaint_no_mask.input_image_path);

    // Case F: SHA256 checksum mismatch
    std::string tmp_file = create_test_file("sha_test.tmp", "test_content_for_sha");
    ImageInputContract sha_contract = valid_contract;
    sha_contract.task_mode = ImageTaskMode::ImageToImage;
    sha_contract.input_image_path = tmp_file;
    sha_contract.input_image_sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
    auto err6 = validator.validate(sha_contract, identity);
    assert(err6.has_value());
    assert(err6->code == mca::contract::StableErrorCode::MDL_CHECKSUM_MISMATCH);
    cleanup_test_file(tmp_file);

    std::cout << "[PASS] test_input_contract_validation" << std::endl;
}

// ============================================================================
// Test 3: Request Lifecycle and Exactly-Once Terminal Invariant
// ============================================================================

void test_request_lifecycle_and_terminal_invariants() {
    std::cout << "[RUN] test_request_lifecycle_and_terminal_invariants" << std::endl;
    auto identity = make_test_identity("req-lifecycle");
    ImageInputContract contract;
    contract.steps = 25;
    SdRequest req(identity, contract);

    assert(req.get_state() == mca::contract::RequestState::Created);
    assert(!req.is_terminal());

    // Valid state transitions
    assert(req.transition_to(mca::contract::RequestState::Accepted));
    assert(req.get_state() == mca::contract::RequestState::Accepted);

    assert(req.transition_to(mca::contract::RequestState::Preparing));
    assert(req.get_state() == mca::contract::RequestState::Preparing);

    assert(req.transition_to(mca::contract::RequestState::Running));
    assert(req.get_state() == mca::contract::RequestState::Running);

    // Illegal backwards transition
    assert(!req.transition_to(mca::contract::RequestState::Created));

    // Terminal transition (first time succeeds)
    assert(req.mark_terminal(mca::contract::RequestState::Succeeded));
    assert(req.is_terminal());
    assert(req.get_state() == mca::contract::RequestState::Succeeded);

    // Exactly-once invariant: second terminal transition must be rejected
    assert(!req.mark_terminal(mca::contract::RequestState::Failed));
    assert(req.get_state() == mca::contract::RequestState::Succeeded);

    // Cancellation check
    SdRequest req_cancel(make_test_identity("req-cancel"), contract);
    req_cancel.transition_to(mca::contract::RequestState::Accepted);
    req_cancel.transition_to(mca::contract::RequestState::Preparing);
    req_cancel.transition_to(mca::contract::RequestState::Running);

    req_cancel.request_cancel();
    assert(req_cancel.is_cancel_requested());
    assert(req_cancel.get_state() == mca::contract::RequestState::CancelRequested);

    std::cout << "[PASS] test_request_lifecycle_and_terminal_invariants" << std::endl;
}

// ============================================================================
// Test 4: Output Transaction Atomicity, Durability, and Rollback
// ============================================================================

void test_output_transaction() {
    std::cout << "[RUN] test_output_transaction" << std::endl;
    auto identity = make_test_identity("req-tx");
    std::string target = get_temp_test_dir() + "/sd_test_output_atomic.png";
    cleanup_test_file(target);

    // Subtest A: Successful Commit
    {
        SdOutputTransaction tx(target, identity);
        assert(tx.begin());
        std::vector<uint8_t> dummy_pixels(64 * 64 * 3, 100);
        assert(tx.write_pixels_as_png(64, 64, 3, dummy_pixels.data()));

        auto result = tx.commit();
        assert(result.success);
        assert(!result.sha256_hex.empty());
        assert(result.bytes_written > 0);
        assert(tx.is_committed());

        // Target file exists, partial does not
        std::ifstream target_in(target, std::ios::binary);
        assert(target_in.is_open());
        target_in.close();

        std::ifstream part_in(tx.get_partial_path(), std::ios::binary);
        assert(!part_in.is_open());
    }
    cleanup_test_file(target);

    // Subtest B: Rollback on Failure / Destructor
    {
        SdOutputTransaction tx(target, identity);
        assert(tx.begin());
        std::vector<uint8_t> data = {1, 2, 3, 4};
        assert(tx.write_data(data.data(), data.size()));

        // Explicit rollback
        tx.rollback();
        assert(!tx.is_committed());

        std::ifstream part_in(tx.get_partial_path(), std::ios::binary);
        assert(!part_in.is_open());

        std::ifstream target_in(target, std::ios::binary);
        assert(!target_in.is_open());
    }

    // Subtest C: Rollback on Cancellation during Commit
    {
        SdOutputTransaction tx(target, identity);
        assert(tx.begin());
        std::vector<uint8_t> data = {5, 6, 7, 8};
        assert(tx.write_data(data.data(), data.size()));

        // Commit with cancellation predicate returning true
        auto result = tx.commit([]() { return true; });
        assert(!result.success);
        assert(result.error.has_value());
        assert(result.error->code == mca::contract::StableErrorCode::CNC_USER_CANCELLED);

        // Files cleaned up
        std::ifstream part_in(tx.get_partial_path(), std::ios::binary);
        assert(!part_in.is_open());

        std::ifstream target_in(target, std::ios::binary);
        assert(!target_in.is_open());
    }

    // Subtest D: Existing Target is Protected on Abort
    {
        // Create an existing target
        std::ofstream preexisting(target, std::ios::binary);
        preexisting << "ORIGINAL_CONTENT";
        preexisting.close();

        {
            SdOutputTransaction tx(target, identity);
            assert(tx.begin());
            std::vector<uint8_t> new_data = {'N', 'E', 'W'};
            assert(tx.write_data(new_data.data(), new_data.size()));
            tx.rollback();
        }

        // Verify original content untouched
        std::ifstream check(target, std::ios::binary);
        std::string content;
        check >> content;
        assert(content == "ORIGINAL_CONTENT");
        check.close();
        cleanup_test_file(target);
    }

    std::cout << "[PASS] test_output_transaction" << std::endl;
}

// ============================================================================
// Test 5: Phase Machine Pipeline Execution (txt2img & img2img)
// ============================================================================

void test_phase_machine_pipeline() {
    std::cout << "[RUN] test_phase_machine_pipeline" << std::endl;
    SdRuntimeOwner owner("TestRuntimeOwner");
    auto backend = std::make_shared<TestSdEngineBackend_NON_PRODUCTION>();
    SdPhaseMachine machine(owner, backend);

    std::string output_path = get_temp_test_dir() + "/pipeline_txt2img_result.png";
    cleanup_test_file(output_path);

    ImageInputContract contract;
    contract.model_path = create_test_file("dummy_model.gguf", "dummy_model_binary");
    contract.output_path = output_path;
    contract.prompt = "A glowing cyberpunk skyscraper";
    contract.width = 256;
    contract.height = 256;
    contract.steps = 5;
    contract.preview_interval = 2;
    contract.task_mode = ImageTaskMode::TextToImage;

    SdRequest request(make_test_identity("req-pipeline"), contract);

    std::vector<mca::contract::CallbackEvent> captured_events;
    bool success = machine.execute(request, [&](const mca::contract::CallbackEvent& event) {
        captured_events.push_back(event);
    });

    assert(success);
    assert(request.get_state() == mca::contract::RequestState::Succeeded);
    assert(machine.get_current_phase() == SdPhase::Completed);
    assert(backend->steps_executed == 5);
    assert(request.get_execution_evidence().actual_denoising_step_count == 5);
    assert(request.get_execution_evidence().device_admitted_without_allowlist == true);

    // Verify events: progress events and terminal event emitted
    bool had_terminal = false;
    int progress_count = 0;
    int preview_count = 0;
    for (const auto& ev : captured_events) {
        if (ev.event_type == mca::contract::CallbackEventType::Terminal) {
            had_terminal = true;
        } else if (ev.event_type == mca::contract::CallbackEventType::Progress) {
            progress_count++;
        } else if (ev.event_type == mca::contract::CallbackEventType::Preview) {
            preview_count++;
        }
    }
    assert(had_terminal);
    assert(progress_count >= 5);
    assert(preview_count > 0);

    // Verify committed output file
    std::ifstream out_file(output_path, std::ios::binary);
    assert(out_file.is_open());
    out_file.close();

    cleanup_test_file(output_path);
    cleanup_test_file(contract.model_path);

    std::cout << "[PASS] test_phase_machine_pipeline" << std::endl;
}

// ============================================================================
// Test 6: Phase Machine Cancellation Responsiveness
// ============================================================================

void test_phase_machine_cancellation() {
    std::cout << "[RUN] test_phase_machine_cancellation" << std::endl;
    SdRuntimeOwner owner("TestRuntimeCancel");
    auto backend = std::make_shared<TestSdEngineBackend_NON_PRODUCTION>();
    SdPhaseMachine machine(owner, backend);

    std::string output_path = get_temp_test_dir() + "/pipeline_cancelled.png";
    cleanup_test_file(output_path);

    ImageInputContract contract;
    contract.model_path = create_test_file("dummy_model_cancel.gguf", "dummy_binary");
    contract.output_path = output_path;
    contract.prompt = "Scenic landscape";
    contract.width = 256;
    contract.height = 256;
    contract.steps = 10;
    contract.task_mode = ImageTaskMode::TextToImage;

    SdRequest request(make_test_identity("req-pipeline-cancel"), contract);

    int progress_steps_seen = 0;
    bool success = machine.execute(request, [&](const mca::contract::CallbackEvent& event) {
        if (event.event_type == mca::contract::CallbackEventType::Progress) {
            const auto* payload = std::get_if<mca::contract::ProgressPayload>(&event.payload);
            if (payload != nullptr && payload->stage == "DenoiseLoop") {
                progress_steps_seen++;
                // Cancel at step 3 of denoise loop
                if (progress_steps_seen == 3) {
                    request.request_cancel();
                }
            }
        }
    });

    assert(!success);
    assert(request.get_state() == mca::contract::RequestState::Cancelled);
    assert(machine.get_current_phase() == SdPhase::Cancelled);
    assert(request.get_last_error().has_value());
    assert(request.get_last_error()->code == mca::contract::StableErrorCode::CNC_USER_CANCELLED);

    // Intermediate file rolled back, target file must NOT exist
    std::ifstream out_file(output_path, std::ios::binary);
    assert(!out_file.is_open());

    cleanup_test_file(contract.model_path);
    std::cout << "[PASS] test_phase_machine_cancellation" << std::endl;
}

// ============================================================================
// Test 7: Phase Machine Execution Fault Handling and Attribution
// ============================================================================

void test_phase_machine_execution_fault() {
    std::cout << "[RUN] test_phase_machine_execution_fault" << std::endl;
    SdRuntimeOwner owner("TestRuntimeFault");
    auto backend = std::make_shared<TestSdEngineBackend_NON_PRODUCTION>();
    backend->fail_at_step = 2; // Inject fault at denoise step 2

    SdPhaseMachine machine(owner, backend);

    std::string output_path = get_temp_test_dir() + "/pipeline_fault.png";
    cleanup_test_file(output_path);

    ImageInputContract contract;
    contract.model_path = create_test_file("dummy_model_fault.gguf", "dummy_binary");
    contract.output_path = output_path;
    contract.prompt = "Futuristic city";
    contract.width = 256;
    contract.height = 256;
    contract.steps = 6;

    SdRequest request(make_test_identity("req-pipeline-fault"), contract);

    bool success = machine.execute(request);
    assert(!success);
    assert(request.get_state() == mca::contract::RequestState::Failed);
    assert(machine.get_current_phase() == SdPhase::Failed);
    assert(request.get_last_error().has_value());
    assert(request.get_last_error()->code == mca::contract::StableErrorCode::NAT_EXECUTION_FAULT);
    assert(request.get_last_error()->phase == mca::contract::ErrorPhase::Execute);

    cleanup_test_file(contract.model_path);
    std::cout << "[PASS] test_phase_machine_execution_fault" << std::endl;
}

} // namespace test
} // namespace sd
} // namespace mca

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << "Starting MCA SD Pipeline Unit Tests" << std::endl;
    std::cout << "========================================" << std::endl;

    mca::sd::test::test_universal_device_admission();
    mca::sd::test::test_input_contract_validation();
    mca::sd::test::test_request_lifecycle_and_terminal_invariants();
    mca::sd::test::test_output_transaction();
    mca::sd::test::test_phase_machine_pipeline();
    mca::sd::test::test_phase_machine_cancellation();
    mca::sd::test::test_phase_machine_execution_fault();

    std::cout << "========================================" << std::endl;
    std::cout << "All MCA SD Pipeline Tests PASSED! (7/7)" << std::endl;
    std::cout << "========================================" << std::endl;
    return 0;
}
