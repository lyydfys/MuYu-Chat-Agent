#pragma once

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <string>
#include <utility>
#include <vector>

namespace mca::mnn {

/**
 * Filters MNN's textual protocol framing before it reaches JNI.  MNN can split
 * special tokens across stream writes, so this state object deliberately holds
 * incomplete suffixes until they can be classified safely.
 */
struct StreamProtocolFilterState {
    std::vector<std::string> terminal_markers;
    std::string pending_marker_prefix;
    std::string pending_role_header;
    bool visible_output_seen = false;
    bool stopped = false;
    std::string stop_reason;

    void reset(std::vector<std::string> markers) {
        terminal_markers = std::move(markers);
        pending_marker_prefix.clear();
        pending_role_header.clear();
        visible_output_seen = false;
        stopped = false;
        stop_reason.clear();
    }

    bool has_pending_protocol() const {
        return !pending_marker_prefix.empty() || !pending_role_header.empty();
    }
};

struct StreamProtocolFilterResult {
    std::string visible;
    bool stopped = false;
};

struct RoleHeaderSpec {
    const char* start;
    const char* end;
    bool ends_at_newline;
};

inline const std::vector<RoleHeaderSpec>& role_header_specs() {
    static const std::vector<RoleHeaderSpec> specs = {
        {"<|im_start|>", "", true},
        {"<|start_header_id|>", "<|end_header_id|>", false},
    };
    return specs;
}

// Some converted MNN packages emit role tokens that are not part of ChatML or
// the Llama header pair. They are never assistant body text in MCA's local
// protocol, so stop rather than expose a prompt/template continuation.
inline const std::vector<std::string>& unsafe_role_tokens() {
    static const std::vector<std::string> tokens = {
        "<|user|>",
        "<|system|>",
        "<|assistant|>",
        "<|developer|>",
        "<|instructions|>",
        "<|tool|>",
    };
    return tokens;
}

inline bool is_role_header_marker(const std::string& marker) {
    for (const auto& spec : role_header_specs()) {
        if (marker == spec.start || (!spec.ends_at_newline && marker == spec.end)) {
            return true;
        }
    }
    return false;
}

namespace detail {

inline bool has_non_whitespace(const std::string& text) {
    return std::any_of(text.begin(), text.end(), [](unsigned char ch) {
        return !std::isspace(ch);
    });
}

inline void mark_visible(StreamProtocolFilterState& state, const std::string& text) {
    if (has_non_whitespace(text)) {
        state.visible_output_seen = true;
    }
}

inline void stop(StreamProtocolFilterState& state, const char* reason) {
    state.stopped = true;
    state.stop_reason = reason;
    state.pending_marker_prefix.clear();
    state.pending_role_header.clear();
}

struct MarkerMatch {
    size_t position = std::string::npos;
    size_t length = 0;
    const RoleHeaderSpec* role_header = nullptr;
};

inline MarkerMatch first_terminal_marker(
        const std::string& text,
        const std::vector<std::string>& markers) {
    MarkerMatch result;
    for (const auto& marker : markers) {
        if (marker.empty()) continue;
        const auto position = text.find(marker);
        if (position != std::string::npos &&
                (result.position == std::string::npos ||
                 position < result.position ||
                 (position == result.position && marker.size() > result.length))) {
            result.position = position;
            result.length = marker.size();
        }
    }
    return result;
}

inline MarkerMatch first_role_header(const std::string& text) {
    MarkerMatch result;
    for (const auto& spec : role_header_specs()) {
        const std::string marker(spec.start);
        const auto position = text.find(marker);
        if (position != std::string::npos &&
                (result.position == std::string::npos || position < result.position)) {
            result.position = position;
            result.length = marker.size();
            result.role_header = &spec;
        }
    }
    return result;
}

inline size_t trailing_protocol_prefix_bytes(
        const std::string& text,
        const std::vector<std::string>& terminal_markers) {
    size_t longest = 0;
    auto inspect = [&text, &longest](const std::string& marker) {
        if (marker.size() < 2 || marker.size() <= longest) return;
        const size_t max_candidate = std::min(marker.size() - 1, text.size());
        for (size_t candidate = max_candidate; candidate > longest; --candidate) {
            if (text.compare(text.size() - candidate, candidate, marker, 0, candidate) == 0) {
                longest = candidate;
                break;
            }
        }
    };
    for (const auto& marker : terminal_markers) inspect(marker);
    for (const auto& spec : role_header_specs()) inspect(spec.start);
    for (const auto& token : unsafe_role_tokens()) inspect(token);
    return longest;
}

inline bool consume_leading_role_header(
        StreamProtocolFilterState& state,
        const RoleHeaderSpec& spec,
        std::string& text,
        std::string& visible,
        bool flush) {
    constexpr size_t kMaxRoleHeaderBytes = 256;
    if (spec.ends_at_newline) {
        const auto newline = text.find('\n');
        if (newline != std::string::npos) {
            text.erase(0, newline + 1);
            return true;
        }
    } else {
        const auto end = text.find(spec.end);
        if (end != std::string::npos) {
            text.erase(0, end + std::string(spec.end).size());
            while (!text.empty() && std::isspace(static_cast<unsigned char>(text.front()))) {
                text.erase(0, 1);
            }
            return true;
        }
    }

    // Keep the start marker as part of the pending state so the next native
    // write can be parsed from a known protocol boundary.
    std::string pending(spec.start);
    pending.append(text);
    if (flush || pending.size() > kMaxRoleHeaderBytes) {
        if (pending.size() > kMaxRoleHeaderBytes) {
            stop(state, "role_header_too_long");
        }
        mark_visible(state, visible);
        return false;
    }
    state.pending_role_header = std::move(pending);
    mark_visible(state, visible);
    return false;
}

} // namespace detail

/**
 * Returns safe assistant text from one native write. True end-of-turn markers
 * always stop output. Role headers are only stripped at the beginning of an
 * answer; once visible body text exists, a role header marks the next turn and
 * terminates output instead of leaking protocol text.
 */
inline StreamProtocolFilterResult filter_stream_protocol(
        StreamProtocolFilterState& state,
        std::string incoming,
        bool flush) {
    if (state.stopped) return {"", true};

    std::string text;
    text.reserve(
            state.pending_role_header.size() +
            state.pending_marker_prefix.size() +
            incoming.size());
    text.append(state.pending_role_header);
    state.pending_role_header.clear();
    text.append(state.pending_marker_prefix);
    state.pending_marker_prefix.clear();
    text.append(incoming);

    std::string visible;
    while (true) {
        const auto terminal = detail::first_terminal_marker(text, state.terminal_markers);
        const auto role_header = detail::first_role_header(text);
        const auto unsafe_role = detail::first_terminal_marker(text, unsafe_role_tokens());
        if (terminal.position != std::string::npos &&
                (role_header.position == std::string::npos || terminal.position <= role_header.position) &&
                (unsafe_role.position == std::string::npos || terminal.position <= unsafe_role.position)) {
            visible.append(text, 0, terminal.position);
            detail::mark_visible(state, visible);
            detail::stop(state, "stop_marker");
            return {std::move(visible), true};
        }
        if (unsafe_role.position != std::string::npos &&
                (role_header.position == std::string::npos || unsafe_role.position <= role_header.position)) {
            visible.append(text, 0, unsafe_role.position);
            detail::mark_visible(state, visible);
            detail::stop(state, "unexpected_role_token");
            return {std::move(visible), true};
        }
        if (role_header.position == std::string::npos) break;

        const std::string before = text.substr(0, role_header.position);
        if (state.visible_output_seen || detail::has_non_whitespace(visible) ||
                detail::has_non_whitespace(before)) {
            visible.append(before);
            detail::mark_visible(state, visible);
            detail::stop(state, "role_header_after_visible_text");
            return {std::move(visible), true};
        }

        // Leading whitespace and the role framing are protocol, not an answer.
        text.erase(0, role_header.position + role_header.length);
        if (!detail::consume_leading_role_header(
                    state,
                    *role_header.role_header,
                    text,
                    visible,
                    flush)) {
            return {std::move(visible), state.stopped};
        }
    }

    const size_t held_bytes = detail::trailing_protocol_prefix_bytes(text, state.terminal_markers);
    if (held_bytes > 0) {
        if (flush) {
            text.erase(text.size() - held_bytes);
        } else {
            state.pending_marker_prefix.assign(text, text.size() - held_bytes, held_bytes);
            text.erase(text.size() - held_bytes);
        }
    }
    visible.append(text);
    detail::mark_visible(state, visible);
    return {std::move(visible), state.stopped};
}

} // namespace mca::mnn
