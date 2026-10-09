#ifndef MCA_LLM_LLAMA_MODEL_LOADER_HPP
#define MCA_LLM_LLAMA_MODEL_LOADER_HPP

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "mca/llm/llm_runtime_config.hpp"
#include "mca/llm/llm_stats_collector.hpp"

#if MCA_WITH_LLAMA_CPP
#include "llama.h"
#include "common.h"
#include "chat.h"
#include "mtmd.h"
#else
struct llama_model;
struct llama_context;
struct llama_batch;
struct common_sampler;
struct common_chat_templates;
using common_chat_templates_ptr = std::shared_ptr<common_chat_templates>;
struct mtmd_context;
using ggml_abort_callback = bool (*)(void *);
#endif

namespace mca {
namespace llm {

enum class GgufHeaderProbe {
    VALID,
    UNREADABLE,
    INVALID_MAGIC,
    TRUNCATED,
};

GgufHeaderProbe probe_gguf_header(const std::string &path);
std::uint64_t regular_file_size_bytes(const std::string &path);

struct ModelLoadRequest {
    std::string model_path;
    std::string params_json;
    std::string native_lib_dir;
    std::string fallback_policy;
    bool gpu_offload_supported{false};
    size_t backend_gpu_device_count{0};
    std::string backend_load_diagnostic;
#if MCA_WITH_LLAMA_CPP
    ggml_abort_callback abort_callback{nullptr};
#endif
};

struct ModelLoadResult {
    int rc{0};
    std::string error_message;
    std::string failure_code;
    std::string warning_message;

    llama_model *model{nullptr};
    llama_context *context{nullptr};
    llama_context *mtp_context{nullptr};
    llama_batch batch{};
    bool batch_ready{false};
    common_chat_templates_ptr chat_templates;
    common_sampler *sampler{nullptr};
    mtmd_context *mtmd_context{nullptr};

    bool vision_ready{false};
    std::string mmproj_path;
    std::string model_architecture;
    int model_mtp_layers{0};
    bool model_hybrid{false};
    bool model_recurrent{false};

    uint64_t model_file_size_bytes{0};
    bool mmap_fallback_allowed{false};
    bool mmap_prefetch_enabled{false};
    bool gpu_auto_fallback_applied{false};
    std::string gpu_auto_fallback_reason;

    RuntimeConfig requested_config;
    RuntimeConfig effective_config;
    bool spec_requested{false};
    bool spec_context_ready{false};

    int n_ctx{0};
    int n_batch{0};
    int n_ubatch{0};
    int n_threads{0};
    int n_threads_batch{0};

    float sampling_temp{0.0f};
    int sampling_top_k{0};
    float sampling_top_p{0.0f};
    float sampling_min_p{0.0f};
    float sampling_repeat_penalty{1.08f};
    float sampling_presence_penalty{0.0f};
    float sampling_frequency_penalty{0.2f};
    int sampling_repeat_last_n{0};
};

class LlamaModelLoader {
public:
    static bool ensure_backends_loaded(
            const std::string &native_lib_dir,
            void *&android_opencl_handle,
            void *&mca_opencl_dispatch_handle,
            bool &backend_initialized,
            size_t &backend_device_count,
            size_t &backend_gpu_device_count,
            bool &gpu_offload_supported,
            std::string &backend_load_diagnostic,
            std::string &last_error);

    static bool resolve_backend_config(
            const RuntimeConfig &requested,
            RuntimeConfig &effective,
            bool allow_cpu_fallback,
            bool gpu_offload_supported,
            size_t backend_gpu_device_count,
            const std::string &backend_load_diagnostic,
            std::string &error);

    static bool configure_sampler(
            llama_model *model,
            const std::string &params_json,
            const RuntimeConfig &effective_config,
            common_sampler *&sampler,
            float &sampling_temp,
            int &sampling_top_k,
            float &sampling_top_p,
            float &sampling_min_p,
            float &sampling_repeat_penalty,
            float &sampling_presence_penalty,
            float &sampling_frequency_penalty,
            int &sampling_repeat_last_n,
            std::string &last_error);

    static void free_llama_resources(
            llama_model *&model,
            llama_context *&context,
            llama_context *&mtp_context,
            llama_batch &batch,
            bool &batch_ready,
            common_chat_templates_ptr &chat_templates,
            common_sampler *&sampler,
            mtmd_context *&mtmd_context);

    static int load_model(
            const ModelLoadRequest &request,
            ModelLoadResult &result);
};

} // namespace llm
} // namespace mca

#endif // MCA_LLM_LLAMA_MODEL_LOADER_HPP
