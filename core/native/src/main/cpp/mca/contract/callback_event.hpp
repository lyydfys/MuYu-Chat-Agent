#ifndef MCA_CONTRACT_CALLBACK_EVENT_HPP
#define MCA_CONTRACT_CALLBACK_EVENT_HPP

#include <cstdint>
#include <string>
#include <vector>
#include <map>
#include <variant>
#include "request_identity.hpp"
#include "request_state.hpp"
#include "error_envelope.hpp"

namespace mca {
namespace contract {

enum class CallbackEventType : uint8_t {
    Token = 0,
    Progress = 1,
    Preview = 2,
    StateChanged = 3,
    Error = 4,
    Terminal = 5,
    Heartbeat = 6
};

enum class CallbackSource : uint8_t {
    Native = 0,
    Engine = 1,
    Worker = 2,
    System = 3
};

struct TokenPayload {
    std::string text;
    int64_t index{0};
    bool is_special{false};
};

struct ProgressPayload {
    float current{0.0f};
    float total{0.0f};
    std::string stage;
    std::string detail;

    [[nodiscard]] float percentage() const noexcept {
        if (total <= 0.0f) return 0.0f;
        float ratio = current / total;
        if (ratio < 0.0f) ratio = 0.0f;
        if (ratio > 1.0f) ratio = 1.0f;
        return ratio * 100.0f;
    }
};

struct PreviewPayload {
    int32_t frame_index{0};
    int32_t width{0};
    int32_t height{0};
    std::string format;
    std::string data_sha256;
};

struct StatePayload {
    RequestState previous_state{RequestState::Created};
    RequestState new_state{RequestState::Created};
};

struct ErrorPayload {
    ErrorEnvelope error;
};

struct TerminalPayload {
    RequestState final_state{RequestState::Succeeded};
    int64_t total_tokens{0};
    int64_t elapsed_ms{0};
};

struct HeartbeatPayload {
    int64_t timestamp_ms{0};
};

using CallbackPayload = std::variant<
    TokenPayload,
    ProgressPayload,
    PreviewPayload,
    StatePayload,
    ErrorPayload,
    TerminalPayload,
    HeartbeatPayload
>;

/**
 * Unified native callback event across runtime, JNI, and engine layers.
 * Mirrors com.muyuchat.core.engine.contract.CallbackEvent.
 */
struct CallbackEvent {
    RequestIdentity identity;
    CallbackEventType event_type{CallbackEventType::Token};
    int64_t sequence{0};
    int64_t monotonic_time_ms{0};
    CallbackPayload payload;
    bool is_terminal{false};
    CallbackSource source{CallbackSource::Native};
    std::map<std::string, std::string> diagnostic_context;

    [[nodiscard]] bool is_stale(
        int64_t active_epoch,
        int64_t active_generation,
        int64_t active_worker
    ) const noexcept {
        return identity.is_stale(active_epoch, active_generation, active_worker);
    }
};

} // namespace contract
} // namespace mca

#endif // MCA_CONTRACT_CALLBACK_EVENT_HPP
