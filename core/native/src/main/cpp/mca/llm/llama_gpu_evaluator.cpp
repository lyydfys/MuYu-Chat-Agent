#include "mca/llm/llama_gpu_evaluator.hpp"

#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <exception>

namespace mca {
namespace llm {

void LlamaGpuEvaluator::clear_evidence(GpuEvidenceState &state) {
    state.offload_active = false;
    state.allocation_observed = false;
    state.execution_observed = false;
    state.layers_known = false;
    state.offload_bytes = 0;
    state.offload_model_bytes = 0;
    state.offload_context_bytes = 0;
    state.offload_compute_bytes = 0;
    state.offload_layers = 0;
    state.opencl_execution_scope = 0;
    state.opencl_kernel_submissions = 0;
    state.opencl_kernel_completions = 0;
    state.opencl_execution_telemetry_available = false;
    state.gpu_decode_succeeded = false;
}

void LlamaGpuEvaluator::begin_execution_scope(GpuEvidenceState &state) {
    state.offload_active = false;
    state.execution_observed = false;
    state.gpu_decode_succeeded = false;
    state.opencl_kernel_submissions = 0;
    state.opencl_kernel_completions = 0;
    state.opencl_execution_scope = state.begin_scope_fn == nullptr
            ? 0
            : state.begin_scope_fn();
    state.opencl_execution_telemetry_available = state.opencl_execution_scope != 0 &&
            state.snapshot_fn != nullptr;
}

void LlamaGpuEvaluator::read_execution_evidence(GpuEvidenceState &state) {
    state.opencl_execution_telemetry_available = state.opencl_execution_scope != 0 &&
            state.snapshot_fn != nullptr &&
            state.snapshot_fn(state.opencl_execution_scope,
                              &state.opencl_kernel_submissions,
                              &state.opencl_kernel_completions) != 0;
    state.gpu_offload_execution_observed = state.gpu_decode_succeeded &&
            state.opencl_execution_telemetry_available &&
            state.opencl_kernel_completions > 0;
}

void LlamaGpuEvaluator::reconcile_evidence(GpuEvidenceState &state) {
    state.offload_active = state.allocation_observed && state.execution_observed;
}

void LlamaGpuEvaluator::refresh_evidence(GpuEvidenceState &state,
                                         const llama_model *model,
                                         llama_context *context) {
    state.offload_active = false;
    state.allocation_observed = false;
    state.layers_known = false;
    state.offload_bytes = 0;
    state.offload_model_bytes = 0;
    state.offload_context_bytes = 0;
    state.offload_compute_bytes = 0;
    state.offload_layers = 0;
    read_execution_evidence(state);
#if MCA_WITH_LLAMA_CPP
    if (!state.offload_supported || model == nullptr || context == nullptr) {
        return;
    }

    try {
        const llama_memory_breakdown breakdown = llama_get_memory_breakdown(context);
        for (const auto &[buffer_type, memory] : breakdown) {
            const ggml_backend_dev_t device = ggml_backend_buft_get_device(buffer_type);
            if (device == nullptr ||
                ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) {
                continue;
            }
            state.offload_model_bytes += memory.model;
            state.offload_context_bytes += memory.context;
            state.offload_compute_bytes += memory.compute;
        }
    } catch (const std::exception &error) {
#ifdef __ANDROID__
        __android_log_print(
                ANDROID_LOG_WARN,
                "MCA",
                "Unable to read llama.cpp GPU allocation evidence: %s",
                error.what());
#endif
        return;
    } catch (...) {
#ifdef __ANDROID__
        __android_log_print(
                ANDROID_LOG_WARN,
                "MCA",
                "Unable to read llama.cpp GPU allocation evidence.");
#endif
        return;
    }

    state.offload_bytes = state.offload_model_bytes +
                          state.offload_context_bytes +
                          state.offload_compute_bytes;
    state.allocation_observed =
            state.offload_model_bytes > 0 &&
            (state.offload_context_bytes > 0 || state.offload_compute_bytes > 0);
    if (state.allocation_observed) {
        state.offload_layers = -1;
    }
    reconcile_evidence(state);
#else
    (void) model;
    (void) context;
#endif
}

void LlamaGpuEvaluator::record_decode_execution(GpuEvidenceState &state,
                                               const llama_model *model,
                                               llama_context *context) {
    state.gpu_decode_succeeded = true;
    refresh_evidence(state, model, context);
    reconcile_evidence(state);
}

} // namespace llm
} // namespace mca
