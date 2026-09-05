#pragma once

#include <array>
#include <cstddef>

namespace mca::mnn {

struct DiffusionBatchCandidates {
    std::array<int, 2> values{};
    std::size_t count = 0;
};

// CFG-capable converted graphs are found in both batch-2 and fixed batch-1
// forms. Prefer the batched path, then fall back to two real batch-1 graph
// invocations when the model rejects a resized leading dimension.
inline DiffusionBatchCandidates diffusionBatchCandidates(bool use_cfg) {
    return use_cfg
        ? DiffusionBatchCandidates{{2, 1}, 2}
        : DiffusionBatchCandidates{{1, 0}, 1};
}

inline bool usesSequentialCfg(bool use_cfg, int session_batch) {
    return use_cfg && session_batch == 1;
}

inline std::size_t graphInvocationsPerTimestep(bool use_cfg, int session_batch) {
    if (!use_cfg) return session_batch == 1 ? 1U : 0U;
    if (session_batch == 2) return 1U;
    if (session_batch == 1) return 2U;
    return 0U;
}

inline bool shouldRetryFirstBatchedCfgExecution(
        bool use_cfg,
        int session_batch,
        std::size_t timestep_index,
        bool fallback_already_attempted) {
    return use_cfg && session_batch == 2 && timestep_index == 0U &&
            !fallback_already_attempted;
}

}  // namespace mca::mnn
