#ifndef MCA_JNI_PROVIDER_GATEWAYS_HPP
#define MCA_JNI_PROVIDER_GATEWAYS_HPP

#include <string>
#include <cstdint>
#include <memory>
#include "mca/contract/request_identity.hpp"
#include "mca/contract/request_state.hpp"
#include "mca/contract/error_envelope.hpp"

namespace mca {
namespace jni {

/**
 * Narrow gateway interface for Llama.cpp native execution.
 */
class ILlamaGateway {
public:
    virtual ~ILlamaGateway() = default;
    virtual int32_t load_model(const std::string& model_path, const std::string& params_json) = 0;
    virtual void unload_model() = 0;
    virtual int32_t begin_completion(const std::string& prompt_json) = 0;
    virtual std::string generate_next_chunk() = 0;
    virtual void request_stop() = 0;
    virtual std::string get_runtime_stats_json() = 0;
};

/**
 * Narrow gateway interface for MNN chat engine execution.
 */
class IMnnGateway {
public:
    virtual ~IMnnGateway() = default;
    virtual int32_t load_model(const std::string& model_dir, const std::string& config_json) = 0;
    virtual void unload_model() = 0;
    virtual int32_t begin_completion(const std::string& prompt_json) = 0;
    virtual std::string generate_next_chunk() = 0;
    virtual void request_stop() = 0;
    virtual bool is_runner_ready() = 0;
    virtual std::string get_runtime_stats_json() = 0;
};

/**
 * Narrow gateway interface for Qualcomm QNN execution.
 */
class IQnnGateway {
public:
    virtual ~IQnnGateway() = default;
    virtual bool is_runner_ready() = 0;
    virtual std::string inspect_runtime() = 0;
    virtual std::string inspect_bundle(const std::string& bundle_path) = 0;
    virtual bool run_image_smoke(const std::string& bundle_path) = 0;
    virtual void cancel_image_generation() = 0;
    virtual std::string get_progress_json() = 0;
};

/**
 * Narrow gateway interface for Stable Diffusion execution.
 */
class IStableDiffusionGateway {
public:
    virtual ~IStableDiffusionGateway() = default;
    virtual bool generate(const std::string& request_json) = 0;
    virtual void cancel() = 0;
    virtual std::string get_progress() = 0;
    virtual void shutdown() = 0;
};

} // namespace jni
} // namespace mca

#endif // MCA_JNI_PROVIDER_GATEWAYS_HPP
