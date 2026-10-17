#ifndef MCA_MNN_DEBUG_TRACE_HPP
#define MCA_MNN_DEBUG_TRACE_HPP

#include <cstddef>
#include <string>
#include <vector>

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace mnn {

constexpr size_t kMaxDebugGeneratedTokenIds = 64;
constexpr size_t kMaxDebugRawOutputBytes = 4096;
constexpr size_t kMaxDebugPromptBytes = 4096;

struct MnnDebugTraceState {
    bool enabled{false};
    bool trace_truncated{false};
    std::string raw_output;
    bool token_ids_truncated{false};
    std::vector<int> generated_token_ids;
    bool prompt_truncated{false};
    std::string prompt;

    void clear() {
        enabled = false;
        trace_truncated = false;
        raw_output.clear();
        token_ids_truncated = false;
        generated_token_ids.clear();
        prompt_truncated = false;
        prompt.clear();
    }
};

std::string raw_bytes_to_hex(const std::string& bytes);
std::string token_ids_to_json_string(const std::vector<int>& ids);

void configure_debug_trace(
        MnnDebugTraceState& state,
        const nlohmann::json& params);

void append_debug_raw_output(
        MnnDebugTraceState& state,
        const char* data,
        size_t size);

void capture_debug_prompt(
        MnnDebugTraceState& state,
        const std::string& prompt);

void capture_debug_generated_token_ids(
        MnnDebugTraceState& state,
        const std::vector<int>& history,
        size_t start);

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_DEBUG_TRACE_HPP
