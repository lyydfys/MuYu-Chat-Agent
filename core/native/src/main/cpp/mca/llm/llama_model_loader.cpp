#include "mca/llm/llama_model_loader.hpp"

#include <algorithm>
#include <fstream>
#include <list>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#if MCA_WITH_LLAMA_CPP
#include <dlfcn.h>
#include "ggml-backend.h"
#include "llama.h"
#include "common.h"
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"
#include "src/llama-ext.h"
#include "llama_load_failure_policy.hpp"
#include "llama_model_device_policy.hpp"
#include "llama_model_memory_policy.hpp"
#endif

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "mca/llm/llm_backend_loader.hpp"
#include "mca/request/llama_request_parser.hpp"

#if MCA_WITH_LLAMA_CPP
extern "C" void mca_llama_set_model_mmap_prefetch_enabled(bool enabled) noexcept;
#endif

namespace mca {
namespace llm {

std::uint64_t regular_file_size_bytes(const std::string &path) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        return 0;
    }
    return static_cast<std::uint64_t>(st.st_size);
}

GgufHeaderProbe probe_gguf_header(const std::string &path) {
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) return GgufHeaderProbe::UNREADABLE;

    char header[24] = {};
    input.read(header, static_cast<std::streamsize>(sizeof(header)));
    const std::streamsize bytes_read = input.gcount();
    if (bytes_read < 4) return GgufHeaderProbe::TRUNCATED;
    if (header[0] != 'G' || header[1] != 'G' || header[2] != 'U' || header[3] != 'F') {
        return GgufHeaderProbe::INVALID_MAGIC;
    }
    return bytes_read == static_cast<std::streamsize>(sizeof(header))
           ? GgufHeaderProbe::VALID
           : GgufHeaderProbe::TRUNCATED;
}

bool LlamaModelLoader::ensure_backends_loaded(
        const std::string &native_lib_dir,
        void *&android_opencl_handle,
        void *&mca_opencl_dispatch_handle,
        bool &backend_initialized,
        size_t &backend_device_count,
        size_t &backend_gpu_device_count,
        bool &gpu_offload_supported,
        std::string &backend_load_diagnostic,
        std::string &last_error) {
    backend_load_diagnostic.clear();
    const bool android_opencl_available =
            load_android_opencl_runtime_if_available_locked(android_opencl_handle, backend_load_diagnostic);
    load_baseline_cpu_backend_if_needed(native_lib_dir, last_error);

    if (!native_lib_dir.empty()) {
        const std::string mca_opencl = join_path(native_lib_dir, "libggml-opencl-mca.so");
        if (file_exists(mca_opencl) && android_opencl_available) {
#if MCA_WITH_LLAMA_CPP
            if (ggml_backend_load(mca_opencl.c_str()) == nullptr) {
                backend_load_diagnostic += " MCA OpenCL backend failed to register: " + mca_opencl + ".";
            } else {
                backend_load_diagnostic += " MCA OpenCL backend registered: " + mca_opencl + ".";
#if defined(__ANDROID__) && defined(__aarch64__)
                if (mca_opencl_dispatch_handle == nullptr) {
                    mca_opencl_dispatch_handle = dlopen(mca_opencl.c_str(), RTLD_NOW | RTLD_LOCAL);
                }
#endif
            }
#endif
        }
    }
#if MCA_WITH_LLAMA_CPP
    ggml_backend_load_all();
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }
    backend_device_count = ggml_backend_dev_count();
    backend_gpu_device_count = 0;
    for (size_t i = 0; i < backend_device_count; ++i) {
        if (ggml_backend_dev_type(ggml_backend_dev_get(i)) != GGML_BACKEND_DEVICE_TYPE_CPU) {
            backend_gpu_device_count++;
        }
    }
    gpu_offload_supported = llama_supports_gpu_offload() && backend_gpu_device_count > 0;
    if (backend_device_count > 0) return true;
#endif

    last_error += "No llama.cpp ggml backend devices are registered. ";
    last_error += "nativeLibDir=" + native_lib_dir + ". ";
    last_error += "Backend files: " + list_ggml_backend_files(native_lib_dir) + ". ";
    last_error += "On Android, dynamic GGML backends require extracted native libraries; keep android:extractNativeLibs=\"true\" / jniLibs.useLegacyPackaging=true.";
    return false;
}

bool LlamaModelLoader::resolve_backend_config(
        const RuntimeConfig &requested,
        RuntimeConfig &effective,
        bool allow_cpu_fallback,
        bool gpu_offload_supported,
        size_t backend_gpu_device_count,
        const std::string &backend_load_diagnostic,
        std::string &error) {
    effective = requested;
    const bool force_gpu = requested.n_gpu_layers == -2 || requested.n_gpu_layers > 0;
    if (force_gpu && !gpu_offload_supported && !allow_cpu_fallback) {
        error = "n_gpu_layers requests GPU offload, but this APK has no usable non-CPU llama.cpp backend.";
        if (!backend_load_diagnostic.empty()) {
            error += " Backend diagnostics: " + backend_load_diagnostic;
        }
        return false;
    }

    if (requested.n_cpu_moe > 0 && !gpu_offload_supported) {
        effective.n_cpu_moe = 0;
    } else if (requested.n_cpu_moe > 0 && requested.n_gpu_layers == 0) {
        error = "n_cpu_moe requires GPU offload; n_gpu_layers is 0.";
        return false;
    }
    if (backend_gpu_device_count > 0 && requested.main_gpu >= (int) backend_gpu_device_count &&
        requested.n_gpu_layers != 0) {
        error = "main_gpu exceeds the registered non-CPU backend device count.";
        return false;
    }
    if (!gpu_offload_supported) {
        if (requested.main_gpu != 0 && force_gpu && !allow_cpu_fallback) {
            error = "main_gpu must be 0 when no GPU backend is registered.";
            return false;
        }
        if (requested.n_gpu_layers == -1 || allow_cpu_fallback) {
            effective.n_gpu_layers = 0;
        }
        effective.main_gpu = 0;
        effective.split_mode = "none";
    } else if (requested.n_gpu_layers == 0) {
        effective.main_gpu = 0;
        effective.split_mode = "none";
    }
    if (requested.spec_type == "draft-mtp" && requested.cache_reuse > 0) {
        effective.cache_reuse = 0;
    }
    return true;
}

bool LlamaModelLoader::configure_sampler(
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
        std::string &last_error) {
#if MCA_WITH_LLAMA_CPP
    if (sampler != nullptr) {
        common_sampler_free(sampler);
        sampler = nullptr;
    }
    common_params_sampling params;
    params.no_perf = !effective_config.perf;
    params.temp = mca::request::parse_float(params_json, "temperature", params.temp);
    params.top_k = mca::request::parse_int(params_json, "top_k", params.top_k);
    params.top_p = mca::request::parse_float(params_json, "top_p", params.top_p);
    params.min_p = mca::request::parse_float(params_json, "min_p", params.min_p);
    params.penalty_repeat = mca::request::parse_float_any(params_json, {"repeat_penalty", "repetition_penalty"}, params.penalty_repeat);
    params.penalty_present = mca::request::parse_float(params_json, "presence_penalty", params.penalty_present);
    params.penalty_freq = mca::request::parse_float(params_json, "frequency_penalty", params.penalty_freq);
    params.penalty_last_n = mca::request::parse_int_any(params_json, {"repeat_last_n", "penalty_last_n"}, params.penalty_last_n);
    const int seed = mca::request::parse_int(params_json, "seed", -1);
    if (seed >= 0) {
        params.seed = (uint32_t) seed;
    }

    sampling_temp = params.temp;
    sampling_top_k = params.top_k;
    sampling_top_p = params.top_p;
    sampling_min_p = params.min_p;
    sampling_repeat_penalty = params.penalty_repeat;
    sampling_presence_penalty = params.penalty_present;
    sampling_frequency_penalty = params.penalty_freq;
    sampling_repeat_last_n = params.penalty_last_n;

    sampler = common_sampler_init(model, params);
    if (sampler == nullptr) {
        last_error += "\ncommon_sampler_init returned null.";
        return false;
    }
    return true;
#else
    (void) model; (void) params_json; (void) effective_config; (void) sampler;
    (void) sampling_temp; (void) sampling_top_k; (void) sampling_top_p; (void) sampling_min_p;
    (void) sampling_repeat_penalty; (void) sampling_presence_penalty; (void) sampling_frequency_penalty;
    (void) sampling_repeat_last_n; (void) last_error;
    return true;
#endif
}

void LlamaModelLoader::free_llama_resources(
        llama_model *&model,
        llama_context *&context,
        llama_context *&mtp_context,
        llama_batch &batch,
        bool &batch_ready,
        common_chat_templates_ptr &chat_templates,
        common_sampler *&sampler,
        mtmd_context *&mtmd_context) {
#if MCA_WITH_LLAMA_CPP
    if (mtp_context != nullptr) {
        llama_free(mtp_context);
        mtp_context = nullptr;
    }
    if (mtmd_context != nullptr) {
        mtmd_free(mtmd_context);
        mtmd_context = nullptr;
    }
    if (sampler != nullptr) {
        common_sampler_free(sampler);
        sampler = nullptr;
    }
    chat_templates.reset();
    if (batch_ready) {
        llama_batch_free(batch);
        batch_ready = false;
    }
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
#else
    (void) model; (void) context; (void) mtp_context; (void) batch;
    (void) batch_ready; (void) chat_templates; (void) sampler; (void) mtmd_context;
#endif
}

int LlamaModelLoader::load_model(
        const ModelLoadRequest &request,
        ModelLoadResult &result) {
#if MCA_WITH_LLAMA_CPP
    RuntimeConfig requested;
    std::string config_error;
    if (!parse_runtime_config(request.params_json, default_runtime_config(), requested, config_error)) {
        result.error_message = "Invalid llama runtime config: " + config_error;
        result.failure_code = "MCA_LOAD_RUNTIME_CONFIG_INVALID";
        return 12;
    }
    if (request.params_json.find("\"n_threads\"") != std::string::npos &&
        request.params_json.find("\"n_threads_batch\"") == std::string::npos) {
        requested.n_threads_batch = requested.n_threads;
        if (!validate_runtime_config(requested, config_error)) {
            result.error_message = "Invalid llama runtime config: " + config_error;
            result.failure_code = "MCA_LOAD_RUNTIME_CONFIG_INVALID";
            return 12;
        }
    }
    result.requested_config = requested;

    const std::string fallback_policy = mca::request::parse_string(
            request.params_json, "fallback_policy",
            requested.n_gpu_layers == -1 ? "allow_cpu" : "require_requested_backend");
    if (fallback_policy != "allow_cpu" && fallback_policy != "require_requested_backend") {
        result.failure_code = "MCA_BACKEND_FALLBACK_POLICY_INVALID";
        result.error_message = "Unsupported fallback_policy: " + fallback_policy;
        return 11;
    }

    RuntimeConfig effective;
    if (!resolve_backend_config(requested, effective, fallback_policy == "allow_cpu",
                               request.gpu_offload_supported,
                               request.backend_gpu_device_count,
                               request.backend_load_diagnostic,
                               config_error)) {
        result.error_message = "Unsupported llama runtime config: " + config_error;
        result.failure_code = "MCA_LOAD_RUNTIME_CONFIG_UNSUPPORTED";
        return 13;
    }
    result.effective_config = effective;
    result.spec_requested = effective.spec_type == "draft-mtp";

    {
        std::ifstream input(request.model_path, std::ios::binary);
        if (!input.good()) {
            result.error_message = "Model file is not readable: " + request.model_path;
            result.failure_code = "MCA_LOAD_FILE_UNREADABLE";
            return 10;
        }
    }

    switch (probe_gguf_header(request.model_path)) {
        case GgufHeaderProbe::VALID:
            break;
        case GgufHeaderProbe::UNREADABLE:
            result.failure_code = "MCA_LOAD_FILE_UNREADABLE";
            result.error_message = "Model file became unreadable before GGUF header validation: " + request.model_path;
            return 10;
        case GgufHeaderProbe::INVALID_MAGIC:
            result.failure_code = "MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED";
            result.error_message = "Invalid GGUF magic in model file: " + request.model_path;
            return 1;
        case GgufHeaderProbe::TRUNCATED:
            result.failure_code = "MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED";
            result.error_message = "GGUF header is truncated: " + request.model_path;
            return 1;
    }

    if (fallback_policy == "require_requested_backend" && requested.n_gpu_layers != 0 &&
        (effective.n_gpu_layers == 0 || request.backend_gpu_device_count == 0)) {
        result.failure_code = "MCA_REQUESTED_BACKEND_UNAVAILABLE";
        result.error_message = "Requested GPU backend has no registered usable device. " + request.backend_load_diagnostic;
        return 11;
    }

    const bool auto_gpu_cpu_fallback_allowed =
            fallback_policy == "allow_cpu" &&
            effective.n_gpu_layers != 0 &&
            effective.n_cpu_moe == 0;
    result.gpu_auto_fallback_applied = fallback_policy == "allow_cpu" &&
            requested.n_gpu_layers != 0 && effective.n_gpu_layers == 0;
    result.gpu_auto_fallback_reason = result.gpu_auto_fallback_applied
            ? "gpu_backend_unavailable: " + request.backend_load_diagnostic
            : (auto_gpu_cpu_fallback_allowed ? "pending" : "not_requested");

    result.model_file_size_bytes = regular_file_size_bytes(request.model_path);

    auto restore_load_state_for_attempt = [&]() {
        result.spec_requested = effective.spec_type == "draft-mtp";
        result.spec_context_ready = false;
        result.model_architecture.clear();
        result.model_mtp_layers = 0;
        result.model_hybrid = false;
        result.model_recurrent = false;
    };

    auto load_model_for_effective_config = [&]() -> bool {
        llama_model_params model_params = llama_model_default_params();
        model_params.load_mode = load_mode_from_flags(effective.mmap, effective.mlock);
        model_params.load_mtp = result.spec_requested;
        model_params.n_gpu_layers = effective.n_gpu_layers;
        model_params.main_gpu = mca::llama::modelMainGpuForLoad(
                request.gpu_offload_supported,
                effective.n_gpu_layers,
                effective.main_gpu);
        model_params.split_mode = split_mode_from_name(effective.split_mode);

        std::list<std::string> moe_patterns;
        std::vector<llama_model_tensor_buft_override> tensor_overrides;
        for (int i = 0; i < effective.n_cpu_moe; ++i) {
            moe_patterns.push_back(llm_ffn_block_regex(i, LLM_FFN_EXPS_REGEX));
            tensor_overrides.push_back({moe_patterns.back().c_str(), ggml_backend_cpu_buffer_type()});
        }
        if (!tensor_overrides.empty()) {
            tensor_overrides.push_back({nullptr, nullptr});
            model_params.tensor_buft_overrides = tensor_overrides.data();
        }

        result.mmap_fallback_allowed = mca::llama::shouldRetryModelLoadWithoutMmap(
                load_mode_uses_mmap(model_params.load_mode),
                result.model_file_size_bytes);
        result.mmap_prefetch_enabled = load_mode_uses_mmap(model_params.load_mode) &&
                mca::llama::shouldPrefetchModelMmap(result.model_file_size_bytes);
        mca_llama_set_model_mmap_prefetch_enabled(result.mmap_prefetch_enabled);
        result.model = llama_model_load_from_file(request.model_path.c_str(), model_params);
        if (result.model == nullptr && result.mmap_fallback_allowed) {
            result.error_message += "\nRetrying model load with mmap=false.";
            model_params.load_mode = load_mode_from_flags(false, effective.mlock);
            effective.mmap = false;
            result.effective_config = effective;
            result.mmap_prefetch_enabled = false;
            mca_llama_set_model_mmap_prefetch_enabled(false);
            result.model = llama_model_load_from_file(request.model_path.c_str(), model_params);
        } else if (result.model == nullptr && load_mode_uses_mmap(model_params.load_mode)) {
            result.error_message += "\nLarge-model mmap load failed; unsafe mmap=false retry is disabled.";
        }
        return result.model != nullptr;
    };

    auto validate_loaded_model_for_effective_config = [&]() -> int {
        result.model_architecture = model_meta_string(result.model, "general.architecture");
        if (!model_supports_autoregressive_chat(result.model, result.model_architecture)) {
            result.error_message = "The selected GGUF is not an autoregressive chat model supported by the current generation path";
            if (!result.model_architecture.empty()) {
                result.error_message += ": architecture=" + result.model_architecture;
            }
            result.error_message += ".";
            result.failure_code = "MCA_LOAD_GGUF_NOT_AUTOREGRESSIVE_CHAT";
            free_llama_resources(result.model, result.context, result.mtp_context,
                                 result.batch, result.batch_ready, result.chat_templates,
                                 result.sampler, result.mtmd_context);
            return 18;
        }
        result.model_mtp_layers = model_mtp_layer_count(result.model);
        result.model_hybrid = llama_model_is_hybrid(result.model);
        result.model_recurrent = llama_model_is_recurrent(result.model);
        if (result.spec_requested && !model_supports_requested_mtp(
                result.model_architecture,
                result.model_mtp_layers)) {
            result.error_message = result.model_mtp_layers <= 0
                    ? "draft-mtp was requested, but the GGUF has no nextn_predict_layers metadata/MTP head."
                    : "draft-mtp supports one MTP block for this architecture; multi-head chaining is currently supported only by Step35. "
                      "Model architecture=" + result.model_architecture +
                      ", MTP blocks=" + std::to_string(result.model_mtp_layers) + ".";
            result.failure_code = "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION";
            free_llama_resources(result.model, result.context, result.mtp_context,
                                 result.batch, result.batch_ready, result.chat_templates,
                                 result.sampler, result.mtmd_context);
            return 17;
        }
        if (effective.n_cpu_moe > llama_model_n_layer(result.model)) {
            result.error_message = "n_cpu_moe exceeds the model layer count (" +
                    std::to_string(llama_model_n_layer(result.model)) + ").";
            result.failure_code = "MCA_LOAD_RUNTIME_CONFIG_UNSUPPORTED";
            free_llama_resources(result.model, result.context, result.mtp_context,
                                 result.batch, result.batch_ready, result.chat_templates,
                                 result.sampler, result.mtmd_context);
            return 14;
        }
        return 0;
    };

    llama_context_params ctx_params{};
    uint32_t speculative_rollback_window = 0;
    int n_threads = 0;
    auto create_contexts_for_effective_config = [&]() -> int {
        result.n_ctx = effective.n_ctx;
        n_threads = effective.n_threads;
        ctx_params = llama_context_default_params();
        ctx_params.n_ctx = result.n_ctx;
        ctx_params.n_batch = effective.n_batch;
        ctx_params.n_ubatch = effective.n_ubatch;
        ctx_params.n_seq_max = 1;
        speculative_rollback_window = result.spec_requested
                                      ? (uint32_t) effective.spec_draft_n_max + 1U
                                      : 0U;
        ctx_params.n_rs_seq = speculative_rollback_window;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = effective.n_threads_batch;
        ctx_params.type_k = cache_type_from_name(effective.cache_type_k);
        ctx_params.type_v = cache_type_from_name(effective.cache_type_v);
        ctx_params.flash_attn_type = flash_attn_from_name(effective.flash_attn);
        ctx_params.no_perf = !effective.perf;
        ctx_params.abort_callback = request.abort_callback;
        result.n_threads = n_threads;
        result.n_threads_batch = effective.n_threads_batch;
        result.n_batch = effective.n_batch;
        result.n_ubatch = effective.n_ubatch;
        result.context = llama_init_from_model(result.model, ctx_params);
        if (result.context == nullptr) {
            result.error_message += "\nllama_init_from_model returned null for the requested context/KV/flash configuration.";
            return 2;
        }

        result.n_ctx = (int) llama_n_ctx(result.context);
        result.n_batch = (int) llama_n_batch(result.context);
        result.n_ubatch = (int) llama_n_ubatch(result.context);
        result.effective_config.n_ctx = result.n_ctx;
        result.effective_config.n_batch = result.n_batch;
        result.effective_config.n_ubatch = result.n_ubatch;

        if (!result.spec_requested) return 0;
        const auto seq_rm_type = common_context_can_seq_rm(result.context);
        if (seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_NO || seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL ||
            (seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_RS &&
             llama_n_rs_seq(result.context) < speculative_rollback_window)) {
            result.error_message = "draft-mtp requires bounded partial target-context rollback; this model/context does not provide it.";
            return 15;
        }

        llama_context_params mtp_params = ctx_params;
        mtp_params.ctx_type = LLAMA_CONTEXT_TYPE_MTP;
        mtp_params.n_rs_seq = 0;
        mtp_params.ctx_other = result.context;
        result.mtp_context = llama_init_from_model(result.model, mtp_params);
        if (result.mtp_context == nullptr) {
            result.error_message = "draft-mtp was requested, but the model has no usable MTP head/context.";
            return 16;
        }
        result.spec_context_ready = true;
        return 0;
    };

    auto fallback_to_cpu = [&](const char *stage) {
        const std::string gpu_error = result.error_message;
        free_llama_resources(result.model, result.context, result.mtp_context,
                             result.batch, result.batch_ready, result.chat_templates,
                             result.sampler, result.mtmd_context);
        effective.n_gpu_layers = 0;
        effective.main_gpu = 0;
        effective.split_mode = "none";
        effective.n_cpu_moe = 0;
        result.effective_config = effective;
        result.gpu_auto_fallback_applied = true;
        result.gpu_auto_fallback_reason = stage;
        result.error_message = std::string("GPU auto initialization failed at ") + stage +
                               "; retrying with the CPU backend.";
        if (!gpu_error.empty()) {
            result.error_message += "\nGPU attempt: " + gpu_error;
        }
        restore_load_state_for_attempt();
    };

    bool retried_auto_gpu_on_cpu = false;
    while (true) {
        if (!load_model_for_effective_config()) {
            if (auto_gpu_cpu_fallback_allowed && !retried_auto_gpu_on_cpu) {
                fallback_to_cpu("model_load");
                retried_auto_gpu_on_cpu = true;
                continue;
            }
            if (result.error_message.empty()) {
                result.error_message = "llama_model_load_from_file returned null. The file may be incomplete, unsupported, or not a chat model GGUF.";
            }
            result.failure_code = mca::llama::loadFailureCodeFromLlamaError(
                    result.error_message + result.warning_message);
            return 1;
        }

        const int model_validation_rc = validate_loaded_model_for_effective_config();
        if (model_validation_rc != 0) return model_validation_rc;

        const int context_rc = create_contexts_for_effective_config();
        if (context_rc == 0) break;
        if ((context_rc == 2 || context_rc == 16) &&
            auto_gpu_cpu_fallback_allowed && !retried_auto_gpu_on_cpu) {
            fallback_to_cpu(context_rc == 2 ? "context_create" : "mtp_context_create");
            retried_auto_gpu_on_cpu = true;
            continue;
        }
        const std::string failure_code = mca::llama::loadFailureCodeFromLlamaError(
                result.error_message + result.warning_message);
        result.failure_code = failure_code == "MCA_LOAD_OUT_OF_MEMORY"
                ? "MCA_LOAD_OUT_OF_MEMORY"
                : (context_rc == 15
                   ? "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION"
                   : "MCA_LOAD_CONTEXT_CREATION_FAILED");
        free_llama_resources(result.model, result.context, result.mtp_context,
                             result.batch, result.batch_ready, result.chat_templates,
                             result.sampler, result.mtmd_context);
        return context_rc;
    }

    result.batch = llama_batch_init(result.n_batch, 0, 1);
    result.batch_ready = true;
    try {
        result.chat_templates = common_chat_templates_init(result.model, "");
    } catch (const std::bad_alloc &) {
        result.failure_code = "MCA_LOAD_OUT_OF_MEMORY";
        result.error_message = "common_chat_templates_init failed: native out of memory.";
        free_llama_resources(result.model, result.context, result.mtp_context,
                             result.batch, result.batch_ready, result.chat_templates,
                             result.sampler, result.mtmd_context);
        return 4;
    } catch (const std::exception &e) {
        result.error_message = std::string("common_chat_templates_init exception: ") + e.what();
        result.failure_code = "MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID";
        free_llama_resources(result.model, result.context, result.mtp_context,
                             result.batch, result.batch_ready, result.chat_templates,
                             result.sampler, result.mtmd_context);
        return 4;
    } catch (...) {
        result.error_message = "common_chat_templates_init exception: unknown native error";
        result.failure_code = "MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID";
        free_llama_resources(result.model, result.context, result.mtp_context,
                             result.batch, result.batch_ready, result.chat_templates,
                             result.sampler, result.mtmd_context);
        return 4;
    }

    if (!configure_sampler(
            result.model,
            request.params_json,
            result.effective_config,
            result.sampler,
            result.sampling_temp,
            result.sampling_top_k,
            result.sampling_top_p,
            result.sampling_min_p,
            result.sampling_repeat_penalty,
            result.sampling_presence_penalty,
            result.sampling_frequency_penalty,
            result.sampling_repeat_last_n,
            result.error_message)) {
        result.failure_code = "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION";
        free_llama_resources(result.model, result.context, result.mtp_context,
                             result.batch, result.batch_ready, result.chat_templates,
                             result.sampler, result.mtmd_context);
        return 3;
    }

    const std::string mmproj_path = mca::request::parse_string(request.params_json, "mmproj_path", "");
    if (!mmproj_path.empty()) {
        std::ifstream projector_input(mmproj_path, std::ios::binary);
        if (!projector_input.good()) {
            result.error_message = "Vision projector file is not readable: " + mmproj_path;
            result.failure_code = "MCA_LOAD_FILE_UNREADABLE";
            free_llama_resources(result.model, result.context, result.mtp_context,
                                 result.batch, result.batch_ready, result.chat_templates,
                                 result.sampler, result.mtmd_context);
            return 5;
        }
        mtmd_context_params vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.print_timings = false;
        const long online_cpus = sysconf(_SC_NPROCESSORS_ONLN);
        const int available_threads = online_cpus > 0 ? static_cast<int>(online_cpus) : 1;
        const int vision_threads = std::min(4, std::max(1, available_threads));
        vision_params.n_threads = vision_threads;
        vision_params.flash_attn_type = ctx_params.flash_attn_type;
        vision_params.warmup = false;
        result.mtmd_context = mtmd_init_from_file(mmproj_path.c_str(), result.model, vision_params);
        if (result.mtmd_context == nullptr) {
            if (result.error_message.empty()) {
                result.error_message = "mtmd_init_from_file returned null. The mmproj may not match this main model.";
            }
            result.failure_code = "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION";
            free_llama_resources(result.model, result.context, result.mtp_context,
                                 result.batch, result.batch_ready, result.chat_templates,
                                 result.sampler, result.mtmd_context);
            return 6;
        }
        if (!mtmd_support_vision(result.mtmd_context)) {
            result.error_message = "The attached mtmd projector does not report vision support.";
            result.failure_code = "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION";
            free_llama_resources(result.model, result.context, result.mtp_context,
                                 result.batch, result.batch_ready, result.chat_templates,
                                 result.sampler, result.mtmd_context);
            return 7;
        }
        result.vision_ready = true;
        result.mmproj_path = mmproj_path;
    }

    result.rc = 0;
    return 0;
#else
    (void) request;
    result.rc = 0;
    return 0;
#endif
}

} // namespace llm
} // namespace mca
