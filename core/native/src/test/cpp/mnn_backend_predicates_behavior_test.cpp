// Standalone host behaviour test for mca::mnn::mnn_sd15_unet_backend_unsupported
// (T2 batch-1, lane A).  It compiles the production TU
// (mca/mnn/mnn_backend_predicates.cpp, -DMCA_WITH_MNN_DIFFUSION=1) so the
// assertions run against the real predicate, not a re-implementation.
#include <cstdio>
#include <string>
#include <vector>

#include "mca/mnn/mnn_backend_predicates.hpp"

namespace {

int g_failures = 0;

void expect_true(bool value, const std::string& what) {
    if (!value) { std::printf("FAIL: expected true: %s\n", what.c_str()); ++g_failures; }
}

void expect_false(bool value, const std::string& what) {
    if (value) { std::printf("FAIL: expected false: %s\n", what.c_str()); ++g_failures; }
}

}  // namespace

int main() {
    using mca::mnn::mnn_sd15_unet_backend_unsupported;

    // Every capability signature observed in real SD1.5 resize failures.
    const char* capabilityErrors[] = {
            "backend resize failed, status 2",
            "Resize error: NOT_SUPPORT",
            "Create execution error : 304",
            "reshape error: op GroupNorm not supported",
    };
    for (const char* message : capabilityErrors) {
        const std::string text(message);
        expect_true(mnn_sd15_unet_backend_unsupported({text}), text);
        expect_true(
                mnn_sd15_unet_backend_unsupported({"file truncated", text}),
                "match among more errors: " + text);
    }
    // Substring shape matters: the marker may sit mid-string.
    expect_true(
            mnn_sd15_unet_backend_unsupported({"prefix status 2 trailer"}),
            "mid-string marker");

    // Negative controls: unrelated or near-miss text must not select the
    // backend-capability fallback.
    expect_false(mnn_sd15_unet_backend_unsupported({}), "empty list");
    expect_false(
            mnn_sd15_unet_backend_unsupported({"file truncated at 4096 bytes"}),
            "unrelated error");
    expect_false(mnn_sd15_unet_backend_unsupported({"status 1"}), "near miss status 1");
    expect_false(
            mnn_sd15_unet_backend_unsupported({"Create execution error : 305"}),
            "near miss 305");
    expect_false(
            mnn_sd15_unet_backend_unsupported({"groupnorm (lowercase)"}),
            "case-sensitive GroupNorm");

    if (g_failures == 0) {
        std::printf("mnn_backend_predicates_behavior_test: PASS\n");
        return 0;
    }
    std::printf("mnn_backend_predicates_behavior_test: %d FAILURE(S)\n", g_failures);
    return 1;
}
