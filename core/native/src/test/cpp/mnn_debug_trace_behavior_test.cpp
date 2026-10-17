// E08k host behaviour test for the MNN debug-trace module (state machine contract).
// Compiles the real production header (mca/mnn/mnn_debug_trace.hpp) and exercises the five
// state-based functions that previously lived only in the uncompiled refactor-era module.
#include "mca/mnn/mnn_debug_trace.hpp"

#include <cstdio>
#include <string>
#include <vector>

using mca::mnn::MnnDebugTraceState;
using mca::mnn::append_debug_raw_output;
using mca::mnn::capture_debug_generated_token_ids;
using mca::mnn::capture_debug_prompt;
using mca::mnn::configure_debug_trace;
using mca::mnn::kMaxDebugGeneratedTokenIds;
using mca::mnn::kMaxDebugPromptBytes;
using mca::mnn::kMaxDebugRawOutputBytes;
using mca::mnn::token_ids_to_json_string;

static int failures = 0;

#define CHECK(cond)                                                                     \
    do {                                                                                 \
        if (!(cond)) {                                                                   \
            ++failures;                                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                  \
        }                                                                                \
    } while (0)

static nlohmann::json params_with_advanced(const nlohmann::json& advanced) {
    return nlohmann::json{{"advanced_json", advanced}};
}

int main() {
    // --- configure_debug_trace ---
    {
        MnnDebugTraceState state;
        configure_debug_trace(state, nlohmann::json::object());
        CHECK(state.enabled == false);
    }
    {
        MnnDebugTraceState state;
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", true}}));
        CHECK(state.enabled == true);
        CHECK(state.trace_truncated == false);
        CHECK(state.raw_output.empty());
        CHECK(state.generated_token_ids.empty());
        CHECK(state.prompt.empty());
    }
    {
        // advanced_json may arrive as a serialized string (LoadParams path).
        MnnDebugTraceState state;
        const nlohmann::json advanced = nlohmann::json{{"mca_debug_trace", true}};
        configure_debug_trace(state, params_with_advanced(advanced.dump()));
        CHECK(state.enabled == true);
    }
    {
        // non-boolean flag stays disabled; malformed strings do not throw.
        MnnDebugTraceState state;
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", 1}}));
        CHECK(state.enabled == false);
        configure_debug_trace(state, params_with_advanced("{not json"));
        CHECK(state.enabled == false);
    }
    {
        // reconfigure resets all capture surfaces.
        MnnDebugTraceState state;
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", true}}));
        append_debug_raw_output(state, "abc", 3);
        capture_debug_prompt(state, "hello");
        capture_debug_generated_token_ids(state, {1, 2, 3}, 0);
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", true}}));
        CHECK(state.raw_output.empty() && state.prompt.empty() && state.generated_token_ids.empty());
        CHECK(!state.trace_truncated && !state.prompt_truncated && !state.token_ids_truncated);
    }

    // --- append_debug_raw_output ---
    {
        MnnDebugTraceState state;  // disabled -> no-op
        append_debug_raw_output(state, "abc", 3);
        CHECK(state.raw_output.empty() && !state.trace_truncated);
    }
    {
        MnnDebugTraceState state;
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", true}}));
        append_debug_raw_output(state, nullptr, 5);
        append_debug_raw_output(state, "abc", 0);
        CHECK(state.raw_output.empty() && !state.trace_truncated);
        append_debug_raw_output(state, "hello", 5);
        CHECK(state.raw_output == "hello");
        // exact cap boundary
        std::string big(kMaxDebugRawOutputBytes, 'x');
        state.raw_output.clear();
        state.trace_truncated = false;
        append_debug_raw_output(state, big.data(), big.size());
        CHECK(state.raw_output.size() == kMaxDebugRawOutputBytes);
        CHECK(state.trace_truncated == false);
        append_debug_raw_output(state, "y", 1);
        CHECK(state.raw_output.size() == kMaxDebugRawOutputBytes);
        CHECK(state.trace_truncated == true);
    }

    // --- capture_debug_prompt ---
    {
        MnnDebugTraceState state;
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", true}}));
        capture_debug_prompt(state, "short prompt");
        CHECK(state.prompt == "short prompt" && !state.prompt_truncated);
        const std::string long_prompt(kMaxDebugPromptBytes + 10, 'p');
        capture_debug_prompt(state, long_prompt);
        CHECK(state.prompt.size() == kMaxDebugPromptBytes);
        CHECK(state.prompt_truncated == true);
        CHECK(state.prompt == long_prompt.substr(0, kMaxDebugPromptBytes));
    }

    // --- capture_debug_generated_token_ids ---
    {
        MnnDebugTraceState state;
        configure_debug_trace(state, params_with_advanced(nlohmann::json{{"mca_debug_trace", true}}));
        capture_debug_generated_token_ids(state, {10, 11, 12, 13}, 2);
        CHECK(state.generated_token_ids == std::vector<int>({12, 13}));
        CHECK(state.token_ids_truncated == false);
        capture_debug_generated_token_ids(state, {10, 11}, 99);  // start clamps to size -> empty
        CHECK(state.generated_token_ids.empty());
        std::vector<int> history;
        for (size_t i = 0; i < kMaxDebugGeneratedTokenIds + 5; ++i) history.push_back(static_cast<int>(i));
        capture_debug_generated_token_ids(state, history, 0);
        CHECK(state.generated_token_ids.size() == kMaxDebugGeneratedTokenIds);
        CHECK(state.token_ids_truncated == true);
        CHECK(state.generated_token_ids.front() == 0);
        CHECK(state.generated_token_ids.back() == static_cast<int>(kMaxDebugGeneratedTokenIds - 1));
    }

    // --- token_ids_to_json_string ---
    CHECK(token_ids_to_json_string({}) == "[]");
    CHECK(token_ids_to_json_string({1, 2, 3}) == "[1,2,3]");
    CHECK(token_ids_to_json_string({-1, 0, 7}) == "[-1,0,7]");

    if (failures == 0) {
        std::printf("ALL PASS mnn_debug_trace_behavior_test\n");
        return 0;
    }
    std::printf("FAILURES=%d\n", failures);
    return 1;
}