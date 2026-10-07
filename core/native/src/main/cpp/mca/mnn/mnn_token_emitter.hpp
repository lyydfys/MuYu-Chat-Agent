#ifndef MCA_MNN_TOKEN_EMITTER_HPP
#define MCA_MNN_TOKEN_EMITTER_HPP

#include <string>
#include <vector>
#include <cstdint>
#include <cstddef>

namespace mca {
namespace mnn {

/**
 * Result returned by token emitter processing.
 */
struct TokenEmitResult {
    std::string visible_text;
    bool is_stopped{false};
    std::string stop_reason;
};

/**
 * Incremental token stream processor providing UTF-8 byte split protection
 * and stop sequence buffering.
 *
 * Prevents character corruption by buffering incomplete UTF-8 multi-byte sequences
 * and stops output cleanly when matching configured stop markers.
 */
class MnnTokenEmitter {
public:
    explicit MnnTokenEmitter(std::vector<std::string> stop_sequences = {});
    ~MnnTokenEmitter();

    /**
     * Consumes incoming token chunk bytes and produces clean visible text.
     */
    TokenEmitResult feed_chunk(const std::string& chunk_bytes);

    /**
     * Flushes any remaining non-stop buffer at end of generation.
     */
    TokenEmitResult flush();

    /**
     * Resets internal buffers for a new generation turn.
     */
    void reset(std::vector<std::string> stop_sequences = {});

    [[nodiscard]] bool is_stopped() const noexcept { return stopped_; }
    [[nodiscard]] const std::string& get_stop_reason() const noexcept { return stop_reason_; }

private:
    [[nodiscard]] size_t detect_incomplete_utf8_tail(const std::string& text) const noexcept;
    [[nodiscard]] size_t detect_trailing_stop_prefix(const std::string& text) const noexcept;

    std::vector<std::string> stop_sequences_;
    std::string incomplete_utf8_tail_;
    std::string pending_stop_prefix_;
    bool stopped_{false};
    std::string stop_reason_;
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_TOKEN_EMITTER_HPP
