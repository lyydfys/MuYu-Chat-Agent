#ifndef MCA_MNN_TOKEN_BOUNDARY_GUARD_HPP
#define MCA_MNN_TOKEN_BOUNDARY_GUARD_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mca {
namespace mnn {

/**
 * Result of taking safe pending text units from the boundary guard.
 */
struct TokenBoundaryTakeResult {
    std::vector<uint16_t> utf16;
    bool has_content{false};
};

/**
 * UTF-8 multi-byte boundary guard and streaming token assembly safeguard.
 *
 * Prevents corrupted replacement characters (U+FFFD) and partial surrogate splits
 * during incremental token decoding by identifying UTF-8 multibyte character sequences
 * and holding incomplete tails until the next streaming chunk arrives.
 *
 * Supports transactional take operations: if downstream JNI string allocation fails
 * (e.g. OutOfMemory), the internal buffers are NOT consumed and can be retried without
 * token loss.
 */
class MnnTokenBoundaryGuard {
public:
    MnnTokenBoundaryGuard() = default;
    ~MnnTokenBoundaryGuard() = default;

    MnnTokenBoundaryGuard(const MnnTokenBoundaryGuard&) = delete;
    MnnTokenBoundaryGuard& operator=(const MnnTokenBoundaryGuard&) = delete;
    MnnTokenBoundaryGuard(MnnTokenBoundaryGuard&&) noexcept = default;
    MnnTokenBoundaryGuard& operator=(MnnTokenBoundaryGuard&&) noexcept = default;

    /**
     * Appends newly produced token bytes/text to the guard.
     */
    void feed_chunk(const std::string& chunk);
    void feed_chunk(std::string&& chunk);

    /**
     * Appends raw bytes to the incomplete tail buffer.
     */
    void append_tail_bytes(const std::string& bytes);

    /**
     * Inspects the combined tail and pending chunks, decoding any complete UTF-8 sequences
     * into UTF-16 code units.
     *
     * @param flush If true, flushes any trailing bytes (even if incomplete).
     * @param out_utf16 Destination vector receiving decoded UTF-16 code units.
     * @return true if complete characters were decoded and are available to emit.
     */
    bool stage_pending_utf16(bool flush, std::vector<uint16_t>& out_utf16);

    /**
     * Commits the most recent stage_pending operation, discarding the consumed input
     * and saving the incomplete tail for the next cycle.
     */
    void commit_staged();

    /**
     * Discards staged results without consuming the pending chunk or tail (for error recovery).
     */
    void rollback_staged() noexcept;

    /**
     * Convenience method combining decode, output generation, and immediate commit if successful.
     */
    TokenBoundaryTakeResult take_pending_utf16(bool flush);

    /**
     * Returns valid UTF-8 text assembled up to the latest safe character boundary,
     * retaining any incomplete multibyte trailing bytes.
     */
    std::string take_pending_utf8(bool flush);

    /**
     * Resets both the incomplete tail and the pending chunk buffer.
     */
    void reset() noexcept;

    /**
     * Clears only the pending chunk buffer.
     */
    void clear_pending_chunk() noexcept;

    /**
     * Sets or replaces the pending chunk buffer directly.
     */
    void set_pending_chunk(std::string chunk);

    /**
     * Checks whether any unprocessed text or incomplete tail bytes are currently buffered.
     */
    [[nodiscard]] bool has_pending() const noexcept;

    [[nodiscard]] const std::string& pending_tail() const noexcept { return incomplete_tail_; }
    [[nodiscard]] const std::string& pending_chunk() const noexcept { return pending_chunk_; }
    [[nodiscard]] size_t total_pending_bytes() const noexcept;

    /**
     * Inspects arbitrary UTF-8 text and computes how many trailing bytes belong to
     * an incomplete multi-byte sequence.
     */
    static size_t detect_incomplete_multibyte_suffix(const std::string& text) noexcept;

private:
    std::string incomplete_tail_;
    std::string pending_chunk_;
    std::string staged_incomplete_tail_;
    bool has_staged_{false};
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_TOKEN_BOUNDARY_GUARD_HPP
