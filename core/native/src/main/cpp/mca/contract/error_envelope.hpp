#ifndef MCA_CONTRACT_ERROR_ENVELOPE_HPP
#define MCA_CONTRACT_ERROR_ENVELOPE_HPP

#include <cstdint>
#include <string>
#include <vector>
#include <optional>
#include "request_identity.hpp"
#include "stable_error_code.hpp"

namespace mca {
namespace contract {

enum class ErrorCategory : uint8_t {
    Validation = 0,
    Capability = 1,
    Model = 2,
    Runtime = 3,
    Native = 4,
    Request = 5,
    Cancel = 6,
    Timeout = 7,
    Storage = 8,
    Network = 9,
    Auth = 10,
    Internal = 11
};

enum class ErrorPhase : uint8_t {
    Validate = 0,
    Load = 1,
    Prepare = 2,
    Execute = 3,
    Callback = 4,
    Persist = 5,
    Shutdown = 6
};

enum class Retryability : uint8_t {
    Never = 0,
    SafeRetry = 1,
    RetryAfterRecovery = 2,
    UserAction = 3
};

/**
 * Native representation of the standardized ErrorEnvelope.
 * Mirrors com.muyuchat.core.engine.contract.ErrorEnvelope.
 */
struct ErrorEnvelope {
    StableErrorCode code{StableErrorCode::INT_UNEXPECTED_EXCEPTION};
    ErrorCategory category{ErrorCategory::Internal};
    ErrorPhase phase{ErrorPhase::Execute};
    Retryability retryability{Retryability::Never};
    std::string user_message_key;
    std::string public_message;
    std::vector<std::string> cause_chain;
    std::optional<RequestIdentity> identity;
    std::optional<int32_t> native_code;
    std::optional<int32_t> http_code;
    std::optional<int32_t> os_code;
    std::string trace_id;
    int64_t timestamp_ms{0};

    [[nodiscard]] ErrorEnvelope sanitize_for_public(
        bool allow_native_code = false,
        bool allow_http_code = true,
        bool allow_os_code = false
    ) const {
        ErrorEnvelope out = *this;
        if (!allow_native_code) out.native_code.reset();
        if (!allow_http_code) out.http_code.reset();
        if (!allow_os_code) out.os_code.reset();
        return out;
    }
};

} // namespace contract
} // namespace mca

#endif // MCA_CONTRACT_ERROR_ENVELOPE_HPP
