// MCA repair runId=20261009-parity / T07 test-trust
// REAL BEHAVIOUR protection for the MNN chat debug-trace contract.
//
// Why this file exists (PLAN 9.1):
//   mnn_chat_debug_trace_source_contract_test.cpp asserts that three debug-trace JSON keys exist in
//   the source.  That is a text locator, and in the regressed tree it was satisfied by a planted
//   comment block in mnn_native_engine.cpp rather than by any code.  This test replaces that with an
//   actual call into the production function that emits the keys,
//   mca::mnn::format_mnn_stats_json (mca/mnn/mnn_stats_collector.cpp), and asserts the emitted JSON
//   VALUES.  Both callers go through the same function: qnn/mnn_native_engine.cpp returns
//   mca::mnn::format_mnn_stats_json(snapshot) for the stats query.
//
// It is a narrow seam test in the sense of PLAN 9.1: the production TU is compiled and linked as-is,
// only the snapshot struct is constructed by the test.

#include <cstdio>
#include <string>
#include <vector>

#include "mca/mnn/mnn_stats_collector.hpp"
#include "nlohmann/json.hpp"

namespace {

int failures = 0;

void expect(bool condition, const std::string& what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
        ++failures;
    }
}

nlohmann::json stats_json(const mca::mnn::MnnEngineStatsSnapshot& snapshot) {
    const std::string text = mca::mnn::format_mnn_stats_json(snapshot);
    nlohmann::json parsed = nlohmann::json::parse(text, nullptr, false);
    if (parsed.is_discarded()) {
        std::fprintf(stderr, "FAIL: format_mnn_stats_json produced invalid JSON: %s\n", text.c_str());
        ++failures;
        return nlohmann::json::object();
    }
    return parsed;
}

}  // namespace

int main() {
    // 1. The keys are always emitted, even for a default snapshot, and carry truthful defaults.
    {
        mca::mnn::MnnEngineStatsSnapshot snapshot;
        const auto parsed = stats_json(snapshot);
        expect(parsed.contains("mnnDebugRawOutputHex"), "default snapshot must emit mnnDebugRawOutputHex");
        expect(parsed.contains("mnnDebugGeneratedTokenIds"), "default snapshot must emit mnnDebugGeneratedTokenIds");
        expect(parsed.contains("mnnDebugTokenIdsTruncated"), "default snapshot must emit mnnDebugTokenIdsTruncated");
        expect(parsed.value("mnnDebugRawOutputHex", std::string("sentinel")).empty(),
               "default snapshot must report an empty hex payload");
        expect(parsed["mnnDebugGeneratedTokenIds"].is_array() && parsed["mnnDebugGeneratedTokenIds"].empty(),
               "default snapshot must report an empty generated-token-id array");
        expect(parsed.value("mnnDebugTokenIdsTruncated", true) == false,
               "default snapshot must not claim truncation");
    }

    // 2. The hex field is derived from the bytes actually captured, not echoed from the input.
    {
        mca::mnn::MnnEngineStatsSnapshot snapshot;
        snapshot.mnn_debug_trace_enabled = true;
        snapshot.mnn_debug_raw_output = std::string("AB\x01", 3);
        const auto parsed = stats_json(snapshot);
        expect(parsed.value("mnnDebugRawOutputHex", std::string()) == "414201",
               "mnnDebugRawOutputHex must be the lowercase hex of the captured bytes");
        expect(parsed.value("mnnDebugTraceEnabled", false) == true,
               "mnnDebugTraceEnabled must reflect the snapshot flag");
    }

    // 3. An explicit hex payload wins over the derived one (the collector uses the pre-encoded field).
    {
        mca::mnn::MnnEngineStatsSnapshot snapshot;
        snapshot.mnn_debug_raw_output = "ignored";
        snapshot.mnn_debug_raw_output_hex = "00ff";
        const auto parsed = stats_json(snapshot);
        expect(parsed.value("mnnDebugRawOutputHex", std::string()) == "00ff",
               "an explicitly encoded hex payload must be emitted verbatim");
    }

    // 4. Generated token ids are serialised as the real array, and the explicit JSON override wins.
    {
        mca::mnn::MnnEngineStatsSnapshot snapshot;
        snapshot.mnn_debug_generated_token_ids = {1, 2, 3};
        snapshot.mnn_debug_token_ids_truncated = true;
        const auto parsed = stats_json(snapshot);
        const auto expected = nlohmann::json::array({1, 2, 3});
        expect(parsed["mnnDebugGeneratedTokenIds"] == expected,
               "mnnDebugGeneratedTokenIds must serialise the captured token ids");
        expect(parsed.value("mnnDebugTokenIdsTruncated", false) == true,
               "mnnDebugTokenIdsTruncated must reflect the snapshot flag");

        mca::mnn::MnnEngineStatsSnapshot overridden;
        overridden.mnn_debug_generated_token_ids = {1, 2, 3};
        overridden.mnn_debug_generated_token_ids_json = "[9,8]";
        const auto overridden_parsed = stats_json(overridden);
        expect(overridden_parsed["mnnDebugGeneratedTokenIds"] == nlohmann::json::array({9, 8}),
               "an explicitly encoded token-id JSON payload must be emitted verbatim");
    }

    // 5. Prompt-truncation is a separate flag and must not be conflated with token-id truncation.
    {
        mca::mnn::MnnEngineStatsSnapshot snapshot;
        snapshot.mnn_debug_prompt_truncated = true;
        const auto parsed = stats_json(snapshot);
        expect(parsed.value("mnnDebugPromptTruncated", false) == true,
               "mnnDebugPromptTruncated must reflect the snapshot flag");
        expect(parsed.value("mnnDebugTokenIdsTruncated", true) == false,
               "prompt truncation must not be reported as token-id truncation");
    }

    if (failures != 0) {
        std::fprintf(stderr, "mnn_debug_trace_stats_json_behavior_test: %d assertion(s) failed\n", failures);
        return 1;
    }
    std::printf("mnn_debug_trace_stats_json_behavior_test: OK\n");
    return 0;
}
