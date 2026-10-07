#include "mnn_token_emitter.hpp"
#include <algorithm>

namespace mca {
namespace mnn {

MnnTokenEmitter::MnnTokenEmitter(std::vector<std::string> stop_sequences) {
    reset(std::move(stop_sequences));
}

MnnTokenEmitter::~MnnTokenEmitter() = default;

void MnnTokenEmitter::reset(std::vector<std::string> stop_sequences) {
    if (stop_sequences.empty()) {
        stop_sequences_ = { "<|im_end|>", "<|endoftext|>", "</s>" };
    } else {
        stop_sequences_ = std::move(stop_sequences);
    }
    incomplete_utf8_tail_.clear();
    pending_stop_prefix_.clear();
    stopped_ = false;
    stop_reason_.clear();
}

size_t MnnTokenEmitter::detect_incomplete_utf8_tail(const std::string& text) const noexcept {
    if (text.empty()) return 0;

    const size_t max_lookback = std::min(static_cast<size_t>(4), text.size());
    size_t lead_idx = std::string::npos;

    for (size_t i = 1; i <= max_lookback; ++i) {
        const auto b = static_cast<uint8_t>(text[text.size() - i]);
        // Continuation bytes have prefix 10xxxxxx (0x80 to 0xBF)
        if ((b & 0xC0) != 0x80) {
            lead_idx = text.size() - i;
            break;
        }
    }

    if (lead_idx == std::string::npos) {
        return 0; // Sequence consists only of continuation bytes or ASCII
    }

    const auto lead = static_cast<uint8_t>(text[lead_idx]);
    if (lead <= 0x7F) {
        return 0; // ASCII is 1-byte, always complete
    }

    size_t expected_len = 0;
    if (lead >= 0xC2 && lead <= 0xDF) {
        expected_len = 2;
    } else if (lead >= 0xE0 && lead <= 0xEF) {
        expected_len = 3;
    } else if (lead >= 0xF0 && lead <= 0xF4) {
        expected_len = 4;
    } else {
        return 0; // Invalid UTF-8 lead byte
    }

    const size_t available = text.size() - lead_idx;
    if (available < expected_len) {
        // Valid partial prefix of multi-byte code point at end of chunk
        return available;
    }

    return 0;
}

size_t MnnTokenEmitter::detect_trailing_stop_prefix(const std::string& text) const noexcept {
    if (text.empty()) return 0;

    size_t longest = 0;
    for (const auto& marker : stop_sequences_) {
        if (marker.size() < 2) continue;
        const size_t max_candidate = std::min(marker.size() - 1, text.size());
        for (size_t cand = max_candidate; cand > longest; --cand) {
            if (text.compare(text.size() - cand, cand, marker, 0, cand) == 0) {
                longest = cand;
                break;
            }
        }
    }
    return longest;
}

TokenEmitResult MnnTokenEmitter::feed_chunk(const std::string& chunk_bytes) {
    if (stopped_) {
        return { "", true, stop_reason_ };
    }

    std::string text;
    text.reserve(pending_stop_prefix_.size() + incomplete_utf8_tail_.size() + chunk_bytes.size());
    text.append(pending_stop_prefix_);
    pending_stop_prefix_.clear();
    text.append(incomplete_utf8_tail_);
    incomplete_utf8_tail_.clear();
    text.append(chunk_bytes);

    // 1. Check for complete stop sequence match
    size_t earliest_stop = std::string::npos;
    std::string matched_stop;
    for (const auto& marker : stop_sequences_) {
        if (marker.empty()) continue;
        const size_t pos = text.find(marker);
        if (pos != std::string::npos) {
            if (earliest_stop == std::string::npos || pos < earliest_stop) {
                earliest_stop = pos;
                matched_stop = marker;
            }
        }
    }

    if (earliest_stop != std::string::npos) {
        std::string visible = text.substr(0, earliest_stop);
        stopped_ = true;
        stop_reason_ = matched_stop;
        return { std::move(visible), true, stop_reason_ };
    }

    // 2. Protect incomplete UTF-8 byte sequences at the boundary
    const size_t tail_bytes = detect_incomplete_utf8_tail(text);
    if (tail_bytes > 0) {
        incomplete_utf8_tail_ = text.substr(text.size() - tail_bytes);
        text.erase(text.size() - tail_bytes);
    }

    // 3. Buffer trailing prefixes of configured stop markers
    const size_t stop_prefix_bytes = detect_trailing_stop_prefix(text);
    if (stop_prefix_bytes > 0) {
        pending_stop_prefix_ = text.substr(text.size() - stop_prefix_bytes);
        text.erase(text.size() - stop_prefix_bytes);
    }

    return { std::move(text), false, "" };
}

TokenEmitResult MnnTokenEmitter::flush() {
    if (stopped_) {
        return { "", true, stop_reason_ };
    }

    std::string text;
    text.reserve(pending_stop_prefix_.size() + incomplete_utf8_tail_.size());
    text.append(pending_stop_prefix_);
    pending_stop_prefix_.clear();
    text.append(incomplete_utf8_tail_);
    incomplete_utf8_tail_.clear();

    return { std::move(text), false, "" };
}

} // namespace mnn
} // namespace mca
