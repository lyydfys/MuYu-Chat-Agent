#include "mca/mnn/mnn_token_boundary_guard.hpp"
#include "jni_utf8_codec.hpp"

#include <utility>

namespace mca {
namespace mnn {

void MnnTokenBoundaryGuard::feed_chunk(const std::string& chunk) {
    pending_chunk_.append(chunk);
}

void MnnTokenBoundaryGuard::feed_chunk(std::string&& chunk) {
    if (pending_chunk_.empty()) {
        pending_chunk_ = std::move(chunk);
    } else {
        pending_chunk_.append(chunk);
    }
}

void MnnTokenBoundaryGuard::append_tail_bytes(const std::string& bytes) {
    incomplete_tail_.append(bytes);
}

bool MnnTokenBoundaryGuard::stage_pending_utf16(bool flush, std::vector<uint16_t>& out_utf16) {
    std::string bytes;
    bytes.reserve(incomplete_tail_.size() + pending_chunk_.size());
    bytes.append(incomplete_tail_);
    bytes.append(pending_chunk_);

    auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
    const bool has_content = !decoded.utf16.empty();
    out_utf16 = std::move(decoded.utf16);
    staged_incomplete_tail_ = std::move(decoded.incomplete_tail);
    has_staged_ = true;
    return has_content;
}

void MnnTokenBoundaryGuard::commit_staged() {
    if (has_staged_) {
        incomplete_tail_ = std::move(staged_incomplete_tail_);
        staged_incomplete_tail_.clear();
        pending_chunk_.clear();
        has_staged_ = false;
    }
}

void MnnTokenBoundaryGuard::rollback_staged() noexcept {
    staged_incomplete_tail_.clear();
    has_staged_ = false;
}

TokenBoundaryTakeResult MnnTokenBoundaryGuard::take_pending_utf16(bool flush) {
    TokenBoundaryTakeResult result;
    result.has_content = stage_pending_utf16(flush, result.utf16);
    commit_staged();
    return result;
}

std::string MnnTokenBoundaryGuard::take_pending_utf8(bool flush) {
    std::string bytes;
    bytes.reserve(incomplete_tail_.size() + pending_chunk_.size());
    bytes.append(incomplete_tail_);
    bytes.append(pending_chunk_);

    auto decoded = mca::utf8::decode_to_utf16(bytes, !flush);
    incomplete_tail_ = std::move(decoded.incomplete_tail);
    pending_chunk_.clear();
    has_staged_ = false;

    if (decoded.utf16.empty()) {
        return {};
    }
    return mca::utf8::encode_from_utf16(decoded.utf16.data(), decoded.utf16.size());
}

void MnnTokenBoundaryGuard::reset() noexcept {
    incomplete_tail_.clear();
    pending_chunk_.clear();
    staged_incomplete_tail_.clear();
    has_staged_ = false;
}

void MnnTokenBoundaryGuard::clear_pending_chunk() noexcept {
    pending_chunk_.clear();
    staged_incomplete_tail_.clear();
    has_staged_ = false;
}

void MnnTokenBoundaryGuard::set_pending_chunk(std::string chunk) {
    pending_chunk_ = std::move(chunk);
    staged_incomplete_tail_.clear();
    has_staged_ = false;
}

bool MnnTokenBoundaryGuard::has_pending() const noexcept {
    return !incomplete_tail_.empty() || !pending_chunk_.empty();
}

size_t MnnTokenBoundaryGuard::total_pending_bytes() const noexcept {
    return incomplete_tail_.size() + pending_chunk_.size();
}

size_t MnnTokenBoundaryGuard::detect_incomplete_multibyte_suffix(const std::string& text) noexcept {
    if (text.empty()) return 0;
    const size_t len = text.size();
    const size_t max_inspect = std::min<size_t>(len, 4);

    for (size_t i = 1; i <= max_inspect; ++i) {
        const auto byte = static_cast<uint8_t>(text[len - i]);
        // Leading byte in UTF-8 multi-byte sequence
        if ((byte & 0xC0) == 0xC0) {
            size_t expected_len = 0;
            if ((byte & 0xE0) == 0xC0) expected_len = 2;
            else if ((byte & 0xF0) == 0xE0) expected_len = 3;
            else if ((byte & 0xF8) == 0xF0) expected_len = 4;

            if (expected_len > i) {
                return i; // i trailing bytes are incomplete
            }
            return 0; // Sequence is complete
        }
        // If not continuation byte, cannot be incomplete sequence start
        if ((byte & 0xC0) != 0x80) {
            return 0;
        }
    }
    return 0;
}

} // namespace mnn
} // namespace mca
