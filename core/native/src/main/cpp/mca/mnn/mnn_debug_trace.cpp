#include "mca/mnn/mnn_debug_trace.hpp"
#include <algorithm>
#include <iomanip>
#include <sstream>

namespace mca {
namespace mnn {

std::string token_ids_to_json_string(const std::vector<int>& ids) {
    nlohmann::json array = nlohmann::json::array();
    for (int id : ids) {
        array.push_back(id);
    }
    return array.dump();
}

void configure_debug_trace(
        MnnDebugTraceState& state,
        const nlohmann::json& params) {
    state.enabled = false;
    try {
        if (params.is_object()) {
            auto it = params.find("advanced_json");
            nlohmann::json advanced = nlohmann::json::object();
            if (it != params.end() && !it->is_null()) {
                if (it->is_object()) {
                    advanced = *it;
                } else if (it->is_string()) {
                    auto parsed = nlohmann::json::parse(it->get<std::string>(), nullptr, false);
                    if (parsed.is_object()) advanced = parsed;
                }
            }
            auto enabled_it = advanced.find("mca_debug_trace");
            state.enabled = enabled_it != advanced.end() &&
                            enabled_it->is_boolean() && enabled_it->get<bool>();
        }
    } catch (...) {
        state.enabled = false;
    }
    state.trace_truncated = false;
    state.raw_output.clear();
    state.token_ids_truncated = false;
    state.generated_token_ids.clear();
    state.prompt_truncated = false;
    state.prompt.clear();
}

void append_debug_raw_output(
        MnnDebugTraceState& state,
        const char* data,
        size_t size) {
    if (!state.enabled || data == nullptr || size == 0) return;
    if (state.raw_output.size() >= kMaxDebugRawOutputBytes) {
        state.trace_truncated = true;
        return;
    }
    const size_t available = kMaxDebugRawOutputBytes - state.raw_output.size();
    const size_t copy_size = std::min(size, available);
    state.raw_output.append(data, copy_size);
    if (copy_size < size) {
        state.trace_truncated = true;
    }
}

void capture_debug_prompt(
        MnnDebugTraceState& state,
        const std::string& prompt) {
    if (!state.enabled) return;
    if (prompt.size() > kMaxDebugPromptBytes) {
        state.prompt = prompt.substr(0, kMaxDebugPromptBytes);
        state.prompt_truncated = true;
    } else {
        state.prompt = prompt;
        state.prompt_truncated = false;
    }
}

void capture_debug_generated_token_ids(
        MnnDebugTraceState& state,
        const std::vector<int>& history,
        size_t start) {
    if (!state.enabled) return;
    const size_t clamped_start = std::min(start, history.size());
    state.generated_token_ids.clear();
    for (size_t index = clamped_start; index < history.size(); ++index) {
        if (state.generated_token_ids.size() >= kMaxDebugGeneratedTokenIds) {
            state.token_ids_truncated = true;
            break;
        }
        state.generated_token_ids.push_back(history[index]);
    }
}

}  // namespace mnn
}  // namespace mca
