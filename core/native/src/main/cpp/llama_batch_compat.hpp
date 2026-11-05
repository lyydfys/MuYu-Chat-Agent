#pragma once

#include "llama.h"

#include <vector>

// llama.cpp v0.9.0 removed the free helpers common_batch_clear() and
// common_batch_add() from common/common.h and replaced them with the
// common_batch wrapper around llama_batch_ext.  The plain llama_batch C struct
// and llama_decode() are unchanged, and MCA builds its decode batches directly
// on that struct, so the two helpers are kept here with the exact semantics the
// removed upstream versions had.  Going through common_batch instead would mean
// rewriting every decode site onto llama_batch_ext for no behavioural gain.
namespace mca::llama {

inline void batchClear(llama_batch & batch) noexcept {
    batch.n_tokens = 0;
}

inline void batchAdd(
        llama_batch & batch,
        llama_token id,
        llama_pos pos,
        const std::vector<llama_seq_id> & seqIds,
        bool logits) {
    // Same guard as the removed upstream helper: llama_batch owns fixed-size
    // arrays, so appending past its capacity would corrupt memory.  Callers
    // chunk their token stream by n_batch for exactly this reason.
    GGML_ASSERT(batch.seq_id[batch.n_tokens] != nullptr && "llama_batch size exceeded");

    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = (int32_t) seqIds.size();
    for (size_t i = 0; i < seqIds.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seqIds[i];
    }
    batch.logits  [batch.n_tokens] = logits;

    batch.n_tokens++;
}

} // namespace mca::llama
