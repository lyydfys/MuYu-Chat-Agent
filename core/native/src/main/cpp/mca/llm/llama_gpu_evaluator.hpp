#ifndef MCA_LLM_LLAMA_GPU_EVALUATOR_HPP
#define MCA_LLM_LLAMA_GPU_EVALUATOR_HPP

#include <cstddef>
#include <cstdint>
#include <string>

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "ggml-backend.h"
#else
struct llama_model;
struct llama_context;
#endif

namespace mca {
namespace llm {

using OpenClBeginExecutionScope = std::uint64_t (*)();
using OpenClExecutionSnapshot = int (*)(std::uint64_t, std::uint64_t *, std::uint64_t *);

struct GpuEvidenceState {
    bool offload_supported{false};
    bool offload_active{false};
    bool allocation_observed{false};
    bool execution_observed{false};
    bool layers_known{false};
    size_t offload_bytes{0};
    size_t offload_model_bytes{0};
    size_t offload_context_bytes{0};
    size_t offload_compute_bytes{0};
    int offload_layers{0};

    std::uint64_t opencl_execution_scope{0};
    std::uint64_t opencl_kernel_submissions{0};
    std::uint64_t opencl_kernel_completions{0};
    bool opencl_execution_telemetry_available{false};
    bool gpu_decode_succeeded{false};

    OpenClBeginExecutionScope begin_scope_fn{nullptr};
    OpenClExecutionSnapshot snapshot_fn{nullptr};
};

class LlamaGpuEvaluator {
public:
    static void clear_evidence(GpuEvidenceState &state);
    static void begin_execution_scope(GpuEvidenceState &state);
    static void read_execution_evidence(GpuEvidenceState &state);
    static void reconcile_evidence(GpuEvidenceState &state);
    static void refresh_evidence(GpuEvidenceState &state,
                                 const llama_model *model,
                                 llama_context *context);
    static void record_decode_execution(GpuEvidenceState &state,
                                        const llama_model *model,
                                        llama_context *context);
};

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLAMA_GPU_EVALUATOR_HPP
