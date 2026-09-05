#include "../../main/cpp/mnn_diffusion_batch_policy.hpp"

#include <cassert>

int main() {
    using mca::mnn::diffusionBatchCandidates;
    using mca::mnn::graphInvocationsPerTimestep;
    using mca::mnn::shouldRetryFirstBatchedCfgExecution;
    using mca::mnn::usesSequentialCfg;

    const auto cfg = diffusionBatchCandidates(true);
    assert(cfg.count == 2U);
    assert(cfg.values[0] == 2);
    assert(cfg.values[1] == 1);
    assert(!usesSequentialCfg(true, cfg.values[0]));
    assert(usesSequentialCfg(true, cfg.values[1]));
    assert(graphInvocationsPerTimestep(true, 2) == 1U);
    assert(graphInvocationsPerTimestep(true, 1) == 2U);

    const auto conditional = diffusionBatchCandidates(false);
    assert(conditional.count == 1U);
    assert(conditional.values[0] == 1);
    assert(!usesSequentialCfg(false, conditional.values[0]));
    assert(graphInvocationsPerTimestep(false, 1) == 1U);

    assert(graphInvocationsPerTimestep(false, 2) == 0U);
    assert(graphInvocationsPerTimestep(true, 0) == 0U);

    assert(shouldRetryFirstBatchedCfgExecution(true, 2, 0U, false));
    assert(!shouldRetryFirstBatchedCfgExecution(false, 2, 0U, false));
    assert(!shouldRetryFirstBatchedCfgExecution(true, 1, 0U, false));
    assert(!shouldRetryFirstBatchedCfgExecution(true, 2, 1U, false));
    assert(!shouldRetryFirstBatchedCfgExecution(true, 2, 0U, true));
    return 0;
}
