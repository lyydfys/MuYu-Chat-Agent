// Compatibility symbols for the GenieX 0.3.x llama.cpp plugin.
// The vendored plugin was built before common_sampler_init gained n_ctx.

#include "sampling.h"

// Keep this overload local to the compatibility translation unit so current
// callers continue to use the three-argument declaration from sampling.h.
common_sampler * common_sampler_init(
        const llama_model * model,
        common_params_sampling & params) {
    return common_sampler_init(model, params, 0);
}
