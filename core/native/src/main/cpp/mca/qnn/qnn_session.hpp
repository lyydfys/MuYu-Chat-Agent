#ifndef MCA_QNN_SESSION_HPP
#define MCA_QNN_SESSION_HPP

#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <chrono>
#include "mca/runtime/engine_session.hpp"
#include "qnn_sdxl_contracts.hpp"

namespace mca {
namespace qnn {

class QnnEngine;
class QnnRequest;

/**
 * RAII lease ensuring mutual exclusion for QNN HTP hardware context executions.
 */
class ExecutionLease {
public:
    explicit ExecutionLease(std::unique_lock<std::timed_mutex> lock)
        : lock_(std::move(lock)) {}
    ~ExecutionLease() = default;

    ExecutionLease(const ExecutionLease&) = delete;
    ExecutionLease& operator=(const ExecutionLease&) = delete;
    ExecutionLease(ExecutionLease&&) = default;
    ExecutionLease& operator=(ExecutionLease&&) = default;

    [[nodiscard]] bool is_held() const noexcept { return lock_.owns_lock(); }

private:
    std::unique_lock<std::timed_mutex> lock_;
};

/**
 * Session context governing generation sequence, intermediate latent caches,
 * and hardware concurrency serialization on Qualcomm NPU/HTP.
 */
class QnnSession : public mca::runtime::EngineSession {
public:
    QnnSession(
        std::shared_ptr<QnnEngine> engine,
        int64_t session_id,
        int64_t initial_generation
    );
    ~QnnSession() override;

    QnnSession(const QnnSession&) = delete;
    QnnSession& operator=(const QnnSession&) = delete;

    /**
     * Acquires exclusive hardware execution lease.
     * Returns nullptr if acquisition times out.
     */
    std::unique_ptr<ExecutionLease> acquire_execution_lease(int64_t timeout_ms = 10000);

    /**
     * Creates a typed SDXL execution request bound to this session.
     */
    std::shared_ptr<QnnRequest> create_qnn_request(
        const std::string& request_id,
        const SdxlExecutionContract& contract,
        const std::string& trace_id = ""
    );

    void clear_cache() noexcept;
    void close() noexcept override;
    [[nodiscard]] bool is_valid() const noexcept;

    [[nodiscard]] std::shared_ptr<QnnEngine> get_qnn_engine() const noexcept;

    // Cache accessors
    void set_cached_latent(std::vector<float> latent);
    [[nodiscard]] std::vector<float> get_cached_latent() const;

private:
    std::shared_ptr<QnnEngine> qnn_engine_;
    mutable std::timed_mutex execution_mutex_;

    mutable std::mutex cache_mutex_;
    std::vector<float> cached_latent_;
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_SESSION_HPP
