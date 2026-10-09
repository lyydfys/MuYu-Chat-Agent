#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

#include "mca/sd/sd_controlnet_adapter.hpp"
#include "mca/sd/sd_inpaint_processor.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_contract_parser.hpp"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_image_io.hpp"

namespace mca {
namespace sd {
namespace test {

// ============================================================================
// 1. ControlNet Tests
// ============================================================================

void test_controlnet_component_validation() {
    std::cout << "[RUN] test_controlnet_component_validation" << std::endl;
    std::string err_code, err_msg;

    // No control image requested -> always valid (universal admission)
    bool ok = validate_controlnet_component(false, "", err_code, err_msg);
    assert(ok);
    assert(err_code.empty());

    // Control image requested, but empty model path -> rejected with descriptive error
    ok = validate_controlnet_component(true, "", err_code, err_msg);
    assert(!ok);
    assert(err_code == "SD_CONTROLNET_PATH_EMPTY");

    // Control image requested, non-existent file -> rejected
    ok = validate_controlnet_component(true, "/path/that/does/not/exist/controlnet.gguf", err_code, err_msg);
    assert(!ok);
    assert(err_code == "SD_CONTROLNET_FILE_NOT_FOUND");

    std::cout << "[PASS] test_controlnet_component_validation" << std::endl;
}

void test_controlnet_branch_classification() {
    std::cout << "[RUN] test_controlnet_branch_classification" << std::endl;
    sd_image_execution_evidence_t ev{};
    ev.control_net_compute_attempt_count = 20;
    ev.positive_control_net_compute_attempt_count = 10;
    ev.negative_control_net_compute_attempt_count = 10;

    ev.control_net_compute_success_count = 20;
    ev.positive_control_net_compute_success_count = 10;
    ev.negative_control_net_compute_success_count = 10;

    ev.control_net_residual_consumption_count = 20;
    ev.positive_control_net_residual_consumption_count = 10;
    ev.negative_control_net_residual_consumption_count = 10;
    ev.auxiliary_control_net_residual_consumption_count = 0;

    std::string field, error;
    bool ok = verify_controlnet_branch_classification(ev, field, error);
    assert(ok);
    assert(field.empty());

    // Incomplete attempt classification
    ev.control_net_compute_attempt_count = 21;
    ok = verify_controlnet_branch_classification(ev, field, error);
    assert(!ok);
    assert(field == "controlNetEvidence");

    // Restore attempt, break success classification
    ev.control_net_compute_attempt_count = 20;
    ev.control_net_compute_success_count = 19;
    ok = verify_controlnet_branch_classification(ev, field, error);
    assert(!ok);
    assert(field == "controlNetEvidence");

    // Restore success, break residual classification
    ev.control_net_compute_success_count = 20;
    ev.control_net_residual_consumption_count = 22;
    ok = verify_controlnet_branch_classification(ev, field, error);
    assert(!ok);
    assert(field == "controlNetEvidence");

    std::cout << "[PASS] test_controlnet_branch_classification" << std::endl;
}

void test_controlnet_evidence_matching() {
    std::cout << "[RUN] test_controlnet_evidence_matching" << std::endl;
    ControlNetExecutionEvidence ev;
    ev.compute_attempt_count = 20;
    ev.compute_success_count = 20;
    ev.positive_compute_attempt_count = 10;
    ev.positive_compute_success_count = 10;
    ev.negative_compute_attempt_count = 10;
    ev.negative_compute_success_count = 10;
    ev.residual_consumption_count = 20;
    ev.positive_residual_consumption_count = 10;
    ev.negative_residual_consumption_count = 10;
    ev.auxiliary_residual_consumption_count = 0;

    std::string field, error;

    // Wired case: matches unet=20, pos=10, neg=10
    bool ok = verify_controlnet_execution_evidence(ev, true, 20, 10, 10, field, error);
    assert(ok);

    // Wired case: compute attempts don't match unet execution count
    ok = verify_controlnet_execution_evidence(ev, true, 22, 10, 10, field, error);
    assert(!ok);
    assert(field == "controlNetEvidence");

    // Wired case: unexpected auxiliary residual consumption
    ev.auxiliary_residual_consumption_count = 1;
    ok = verify_controlnet_execution_evidence(ev, true, 20, 10, 10, field, error);
    assert(!ok);
    assert(field == "controlNetEvidence");

    // Unwired case: all must be zero
    ControlNetExecutionEvidence zero_ev{};
    ok = verify_controlnet_execution_evidence(zero_ev, false, 20, 10, 10, field, error);
    assert(ok);

    // Unwired case: unexpected non-zero compute reported
    zero_ev.compute_attempt_count = 1;
    ok = verify_controlnet_execution_evidence(zero_ev, false, 20, 10, 10, field, error);
    assert(!ok);
    assert(field == "controlNetEvidence");

    std::cout << "[PASS] test_controlnet_evidence_matching" << std::endl;
}

void test_controlnet_json_serialization() {
    std::cout << "[RUN] test_controlnet_json_serialization" << std::endl;
    ControlNetExecutionEvidence ev;
    ev.compute_attempt_count = 4;
    ev.compute_success_count = 4;
    ev.positive_compute_attempt_count = 2;
    ev.positive_compute_success_count = 2;
    ev.negative_compute_attempt_count = 2;
    ev.negative_compute_success_count = 2;
    ev.residual_consumption_count = 4;
    ev.positive_residual_consumption_count = 2;
    ev.negative_residual_consumption_count = 2;
    ev.auxiliary_residual_consumption_count = 0;

    nlohmann::json j = format_controlnet_evidence_json(ev);
    assert(j["computeAttemptCount"] == 4);
    assert(j["computeSuccessCount"] == 4);
    assert(j["positiveComputeAttemptCount"] == 2);
    assert(j["negativeComputeAttemptCount"] == 2);
    assert(j["residualConsumptionCount"] == 4);
    assert(j["auxiliaryResidualConsumptionCount"] == 0);

    nlohmann::json effective;
    LoadedInputImage control_image;
    control_image.canonical_path = "/path/to/control.png";
    control_image.sha256 = "c0ffee";
    populate_controlnet_effective_json(effective, ev, true, true, 0.8, 0.8f, control_image);
    assert(effective["controlImagePath"] == "/path/to/control.png");
    assert(effective["controlImageExecutionCount"] == 1);
    assert(effective["controlImageSha256"] == "c0ffee");
    assert(effective["controlStrengthApplied"] == true);

    nlohmann::json out;
    populate_controlnet_response_json(out, effective, true, true, control_image);
    assert(out["controlImagePath"] == "/path/to/control.png");
    assert(out["controlImageExecutionCount"] == 1);
    assert(out["controlNetEvidence"]["computeAttemptCount"] == 4);

    std::cout << "[PASS] test_controlnet_json_serialization" << std::endl;
}

// ============================================================================
// 2. Inpaint Tests
// ============================================================================

void test_inpaint_mask_validation() {
    std::cout << "[RUN] test_inpaint_mask_validation" << std::endl;
    StableDiffusionExecutionContract contract;
    contract.task_mode = "txt2img";

    LoadedInputImage input_image;
    LoadedInputImage mask_image;
    std::string err_code, err_msg;

    // Non-inpaint task -> no mask needed
    bool ok = load_and_validate_inpaint_mask(contract, input_image, mask_image, err_code, err_msg);
    assert(ok);

    // Inpaint task, empty mask path -> error
    contract.task_mode = "inpaint";
    contract.mask_image_path = "";
    ok = load_and_validate_inpaint_mask(contract, input_image, mask_image, err_code, err_msg);
    assert(!ok);
    assert(err_code == "SD_INPAINT_MASK_PATH_EMPTY");

    // Inpaint task, non-existent mask path -> error
    contract.mask_image_path = "/missing/mask.png";
    ok = load_and_validate_inpaint_mask(contract, input_image, mask_image, err_code, err_msg);
    assert(!ok);
    assert(err_code == "SD_INPAINT_MASK_FILE_NOT_FOUND");

    std::cout << "[PASS] test_inpaint_mask_validation" << std::endl;
}

void test_inpaint_mask_dilation() {
    std::cout << "[RUN] test_inpaint_mask_dilation" << std::endl;
    const uint32_t w = 5;
    const uint32_t h = 5;
    std::vector<uint8_t> mask(w * h, 0);
    // Center pixel at (2, 2) set to 255
    mask[2 * w + 2] = 255;

    // Dilate with radius 1
    expand_mask_region(mask, w, h, 1);

    // 3x3 region centered at (2,2) should be 255
    for (int y = 1; y <= 3; ++y) {
        for (int x = 1; x <= 3; ++x) {
            assert(mask[y * w + x] == 255);
        }
    }
    // Corners should still be 0
    assert(mask[0] == 0);
    assert(mask[w - 1] == 0);
    assert(mask[(h - 1) * w] == 0);
    assert(mask[h * w - 1] == 0);

    // Negative or zero radius is a no-op
    expand_mask_region(mask, w, h, 0);
    expand_mask_region(mask, w, h, -1);

    std::cout << "[PASS] test_inpaint_mask_dilation" << std::endl;
}

void test_inpaint_mask_feathering() {
    std::cout << "[RUN] test_inpaint_mask_feathering" << std::endl;
    const uint32_t w = 8;
    const uint32_t h = 8;
    std::vector<uint8_t> mask(w * h, 0);
    // Left half 0, right half 255
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 4; x < w; ++x) {
            mask[y * w + x] = 255;
        }
    }

    feather_mask_edges(mask, w, h, 2);

    // Border near x=3 and x=4 should have intermediate values
    for (uint32_t y = 0; y < h; ++y) {
        uint8_t boundary_left = mask[y * w + 3];
        uint8_t boundary_right = mask[y * w + 4];
        assert(boundary_left > 0 && boundary_left < 255);
        assert(boundary_right > 0 && boundary_right < 255);
        assert(boundary_right >= boundary_left);
    }

    std::cout << "[PASS] test_inpaint_mask_feathering" << std::endl;
}

void test_inpaint_composite_blending() {
    std::cout << "[RUN] test_inpaint_composite_blending" << std::endl;
    const uint32_t w = 3;
    const uint32_t h = 1;
    const uint32_t channels = 3;
    const size_t total_bytes = w * h * channels;

    std::vector<uint8_t> original(total_bytes, 100);
    std::vector<uint8_t> inpaint(total_bytes, 200);
    std::vector<uint8_t> mask = {0, 128, 255}; // 3 pixels: keep orig, half-blend, keep inpaint
    std::vector<uint8_t> target(total_bytes, 0);

    blend_inpaint_composite(target.data(), original.data(), inpaint.data(), mask.data(), w, h, channels);

    // Pixel 0 (mask = 0) -> original (100)
    for (uint32_t c = 0; c < channels; ++c) {
        assert(target[0 * channels + c] == 100);
    }
    // Pixel 1 (mask = 128) -> blend ~ 100 + (200 - 100)*128/255 = 150
    for (uint32_t c = 0; c < channels; ++c) {
        uint8_t val = target[1 * channels + c];
        assert(val >= 149 && val <= 151);
    }
    // Pixel 2 (mask = 255) -> inpaint (200)
    for (uint32_t c = 0; c < channels; ++c) {
        assert(target[2 * channels + c] == 200);
    }

    std::cout << "[PASS] test_inpaint_composite_blending" << std::endl;
}

// ============================================================================
// 3. Preview Tests
// ============================================================================

void test_preview_mode_parsing() {
    std::cout << "[RUN] test_preview_mode_parsing" << std::endl;
    assert(preview_mode_from_contract("projection") == PREVIEW_PROJ);
    assert(preview_mode_from_contract("tae") == PREVIEW_TAE);
    assert(preview_mode_from_contract("vae") == PREVIEW_VAE);
    assert(preview_mode_from_contract("none") == PREVIEW_NONE);
    assert(preview_mode_from_contract("") == PREVIEW_NONE);
    assert(preview_mode_from_contract("unknown_future_mode") == PREVIEW_NONE);

    std::cout << "[PASS] test_preview_mode_parsing" << std::endl;
}

void test_preview_publication_verification() {
    std::cout << "[RUN] test_preview_publication_verification" << std::endl;
    PreviewPublicationContext ctx;
    ctx.publication_count = 0;

    std::string field, error;

    // PREVIEW_NONE -> valid even with 0 publications
    bool ok = verify_preview_publication(PREVIEW_NONE, ctx, field, error);
    assert(ok);

    // PREVIEW_PROJ with 0 publications -> failure
    ok = verify_preview_publication(PREVIEW_PROJ, ctx, field, error);
    assert(!ok);
    assert(field == "previewPublicationCount");

    // PREVIEW_PROJ with >= 1 publication -> success
    ctx.publication_count = 2;
    ok = verify_preview_publication(PREVIEW_PROJ, ctx, field, error);
    assert(ok);

    std::cout << "[PASS] test_preview_publication_verification" << std::endl;
}

void test_preview_revision_counter() {
    std::cout << "[RUN] test_preview_revision_counter" << std::endl;
    uint64_t before = preview_revision_counter().load();
    uint64_t next = preview_revision_counter().fetch_add(1) + 1;
    assert(next == before + 1);
    assert(preview_revision_counter().load() == next);

    std::cout << "[PASS] test_preview_revision_counter" << std::endl;
}

void test_preview_scope_guard() {
    std::cout << "[RUN] test_preview_scope_guard" << std::endl;
    PreviewPublicationContext ctx;
    ctx.mode = "projection";
    ctx.interval = 1;

    {
        SdPreviewScope scope(&ctx, PREVIEW_PROJ, 1);
        // Explicit reset inside scope
        scope.reset();
        // Redundant reset is idempotent
        scope.reset();
    }

    {
        // Normal RAII scope exit
        SdPreviewScope scope(&ctx, PREVIEW_NONE, 1);
    }

    std::cout << "[PASS] test_preview_scope_guard" << std::endl;
}

void test_preview_response_serialization() {
    std::cout << "[RUN] test_preview_response_serialization" << std::endl;
    PreviewPublicationContext ctx;
    ctx.publication_count = 3;
    ctx.last_step = 6;
    ctx.last_revision = 42;

    nlohmann::json out;
    populate_preview_response_json(out, PREVIEW_PROJ, "projection", 2, ctx);

    assert(out["previewRequested"] == true);
    assert(out["previewMode"] == "projection");
    assert(out["previewInterval"] == 2);
    assert(out["previewPublicationCount"] == 3);
    assert(out["previewLastStep"] == 6);
    assert(out["previewLastRevision"] == 42);

    nlohmann::json out_none;
    populate_preview_response_json(out_none, PREVIEW_NONE, "none", 0, ctx);
    assert(out_none["previewRequested"] == false);
    assert(out_none["previewMode"] == "none");

    std::cout << "[PASS] test_preview_response_serialization" << std::endl;
}

} // namespace test
} // namespace sd
} // namespace mca

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << "Starting MCA SD Component Unit Tests" << std::endl;
    std::cout << "========================================" << std::endl;

    mca::sd::test::test_controlnet_component_validation();
    mca::sd::test::test_controlnet_branch_classification();
    mca::sd::test::test_controlnet_evidence_matching();
    mca::sd::test::test_controlnet_json_serialization();

    mca::sd::test::test_inpaint_mask_validation();
    mca::sd::test::test_inpaint_mask_dilation();
    mca::sd::test::test_inpaint_mask_feathering();
    mca::sd::test::test_inpaint_composite_blending();

    mca::sd::test::test_preview_mode_parsing();
    mca::sd::test::test_preview_publication_verification();
    mca::sd::test::test_preview_revision_counter();
    mca::sd::test::test_preview_scope_guard();
    mca::sd::test::test_preview_response_serialization();

    std::cout << "========================================" << std::endl;
    std::cout << "All MCA SD Component Tests PASSED! (13/13)" << std::endl;
    std::cout << "========================================" << std::endl;
    return 0;
}
