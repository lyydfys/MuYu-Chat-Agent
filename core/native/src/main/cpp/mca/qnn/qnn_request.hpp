#ifndef MCA_QNN_REQUEST_HPP
#define MCA_QNN_REQUEST_HPP

#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <atomic>
#include "mca/request/native_request_context.hpp"
#include "mca/callback/callback_router.hpp"
#include "qnn_sdxl_contracts.hpp"

namespace mca {
namespace qnn {

class QnnSession;

/**
 * Single-request execution context governing SDXL image generation phases.
 *
 * Invariants:
 * - Inherits NativeRequestContext with immutable RequestIdentity.
 * - Enforces exactly-once terminal delivery.
 * - Cooperative cancellation: CancelRequested does not prematurely detach active hardware handles.
 * - Single-owner contract: does not hold long-lived engine or session allocations.
 */
class QnnRequest : public mca::request::NativeRequestContext {
public:
    QnnRequest(
        contract::RequestIdentity identity,
        std::shared_ptr<QnnSession> session,
        SdxlExecutionContract contract
    );
    ~QnnRequest();

    QnnRequest(const QnnRequest&) = delete;
    QnnRequest& operator=(const QnnRequest&) = delete;

    [[nodiscard]] SdxlPhase get_current_phase() const noexcept;
    bool transition_phase(SdxlPhase next_phase) noexcept;

    [[nodiscard]] const SdxlExecutionContract& get_contract() const noexcept;
    [[nodiscard]] std::vector<SdxlPhaseArtifact> get_artifacts() const;
    void add_artifact(SdxlPhaseArtifact artifact);

    [[nodiscard]] std::shared_ptr<QnnSession> get_session() const noexcept;

    /**
     * Executes the generation pipeline across phases.
     * Checks cancellation points at phase and step boundaries.
     */
    contract::RequestState execute(
        std::shared_ptr<callback::CallbackRouter> router = nullptr
    );

private:
    bool check_cancellation_point() noexcept;
    void emit_progress(
        callback::CallbackRouter* router,
        float current,
        float total,
        const std::string& stage_name,
        const std::string& detail = ""
    );

    std::weak_ptr<QnnSession> session_;
    SdxlExecutionContract contract_;
    std::atomic<SdxlPhase> current_phase_{SdxlPhase::Idle};

    mutable std::mutex request_mutex_;
    std::vector<SdxlPhaseArtifact> artifacts_;
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_REQUEST_HPP
