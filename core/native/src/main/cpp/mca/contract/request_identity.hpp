#ifndef MCA_CONTRACT_REQUEST_IDENTITY_HPP
#define MCA_CONTRACT_REQUEST_IDENTITY_HPP

#include <cstdint>
#include <string>

namespace mca {
namespace contract {

/**
 * Immutable identifier and epoch context for an MCA request across native boundaries.
 * Mirrors com.muyuchat.core.engine.contract.RequestIdentity.
 */
struct RequestIdentity {
    std::string request_id;
    int64_t attempt_id{0};
    int64_t session_generation{0};
    int64_t runtime_epoch{0};
    int64_t worker_incarnation{0};
    int64_t native_sequence{0};
    std::string trace_id;

    [[nodiscard]] bool is_valid() const noexcept {
        return !request_id.empty() &&
               attempt_id >= 0 &&
               session_generation >= 0 &&
               runtime_epoch >= 0 &&
               worker_incarnation >= 0 &&
               native_sequence >= 0;
    }

    [[nodiscard]] bool is_stale(
        int64_t active_epoch,
        int64_t active_generation,
        int64_t active_worker
    ) const noexcept {
        if (runtime_epoch < active_epoch) return true;
        if (session_generation < active_generation) return true;
        if (worker_incarnation < active_worker) return true;
        return false;
    }

    [[nodiscard]] RequestIdentity next_sequence() const {
        RequestIdentity copy = *this;
        copy.native_sequence += 1;
        return copy;
    }
};

} // namespace contract
} // namespace mca

#endif // MCA_CONTRACT_REQUEST_IDENTITY_HPP
