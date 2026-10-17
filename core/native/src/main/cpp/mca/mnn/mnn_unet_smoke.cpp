#include "mca/mnn/mnn_unet_smoke.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_diffusion_assets.hpp"
#include "mca/mnn/mnn_tensor_copy.hpp"
#include "mca/mnn/mnn_tensor_ops.hpp"

#include <chrono>
#include <memory>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#else
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, fmt, ...) ((void)0)
#endif

namespace mca {
namespace mnn {

namespace {

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

}  // namespace

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
void configure_interpreter_profile(MNN::Interpreter* interpreter, const UnetSmokeProfile& profile) {
    if (interpreter == nullptr) return;
    interpreter->setSessionMode(MNN::Interpreter::Session_Debug);
    interpreter->setSessionMode(profile.inputUser
            ? MNN::Interpreter::Session_Input_User
            : MNN::Interpreter::Session_Input_Inside);
    interpreter->setSessionMode(profile.resizeDefer
            ? MNN::Interpreter::Session_Resize_Defer
            : MNN::Interpreter::Session_Resize_Direct);
}


nlohmann::json run_unet_interpreter_smoke_once(
        const std::string& root,
        const std::string& backendMode,
        const UnetSmokeProfile& profile) {
    const auto path = root + "/unet.mnn";
    const auto weightPath = path + ".weight";
    if (!nonempty_regular_file_exists(path)) {
        return nlohmann::json({
            {"ok", false},
            {"errorCode", "MNN_DIFFUSION_BUNDLE_INCOMPLETE"},
            {"error", "unet.mnn is missing, empty, or not a regular file."}
        });
    }
    if (!nonempty_regular_file_exists(weightPath)) {
        return nlohmann::json({
            {"ok", false},
            {"errorCode", "MNN_DIFFUSION_BUNDLE_INCOMPLETE"},
            {"error", "unet.mnn.weight is missing, empty, or not a regular file."}
        });
    }
    const auto started = now_ms();
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_interpreter_start", profile.name.c_str());
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        return nlohmann::json({{"ok", false}, {"error", "Interpreter::createFromFile failed."}});
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_interpreter_completed", profile.name.c_str());
    interpreter->setExternalFile(weightPath.c_str());
    configure_interpreter_profile(interpreter.get(), profile);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    const bool useOpenCl = backendMode == "opencl" || backendMode == "gpu";
    config.type = useOpenCl ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    config.numThread = useOpenCl ? 1 : 4;
    if (useOpenCl) {
        config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    }
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_session_start backend=%s",
                        profile.name.c_str(), useOpenCl ? "opencl" : "cpu");
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        return nlohmann::json({{"ok", false}, {"error", "Interpreter::createSession failed."}});
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] create_session_completed", profile.name.c_str());
    auto* sample = interpreter->getSessionInput(session, "sample");
    auto* timestep = interpreter->getSessionInput(session, "timestep");
    auto* encoder = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (sample == nullptr || timestep == nullptr || encoder == nullptr) {
        interpreter->releaseSession(session);
        return nlohmann::json({
            {"ok", false},
            {"error", "UNet input tensor not found."},
            {"hasSample", sample != nullptr},
            {"hasTimestep", timestep != nullptr},
            {"hasEncoderHiddenStates", encoder != nullptr}
        });
    }
    // Prefer a batched CFG graph, while also supporting exporters that freeze
    // the leading dimension at one. Production executes the latter twice per
    // scheduler timestep, so one real batch-1 graph run is the matching smoke.
    interpreter->resizeTensor(sample, {profile.batch, 4, 64, 64});
    interpreter->resizeTensor(timestep, {1});
    interpreter->resizeTensor(encoder, {profile.batch, 77, 768});
    int resizeStatusBefore = -1;
    int resizeStatusAfter = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatusBefore);
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] resize_session_start status=%d",
                        profile.name.c_str(), resizeStatusBefore);
    interpreter->resizeSession(session, 1);
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatusAfter);
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] resize_session_completed status=%d",
                        profile.name.c_str(), resizeStatusAfter);
    sample = interpreter->getSessionInput(session, "sample");
    timestep = interpreter->getSessionInput(session, "timestep");
    encoder = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (resizeStatusAfter != 0 || sample == nullptr || timestep == nullptr || encoder == nullptr) {
        const auto inputs = nlohmann::json({
            {"sample", tensor_metadata_json(sample)},
            {"encoderHiddenStates", tensor_metadata_json(encoder)},
            {"timestep", tensor_metadata_json(timestep)}
        });
        interpreter->releaseSession(session);
        return nlohmann::json({
            {"ok", false},
            {"errorCode", "MNN_UNET_RESIZE_FAILED"},
            {"error", "UNet resizeSession failed or invalidated an input tensor."},
            {"backendMode", useOpenCl ? "opencl" : "cpu"},
            {"profile", profile.name},
            {"sessionBatch", profile.batch},
            {"cfgExecutionMode", profile.batch == 2 ? "batched" : "sequential"},
            {"resizeStatusBefore", resizeStatusBefore},
            {"resizeStatusAfter", resizeStatusAfter},
            {"inputs", inputs}
        });
    }

    std::vector<float> sampleBuffer(
            static_cast<size_t>(profile.batch * 4 * 64 * 64), 0.0f);
    std::vector<float> encoderBuffer(
            static_cast<size_t>(profile.batch * 77 * 768), 0.0f);
    std::vector<int> timestepBuffer = {1};
    std::string copyError;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] copy_inputs_start", profile.name.c_str());
    const bool sampleCopied = copy_vector_to_tensor<float>(sample, sampleBuffer, copyError);
    const std::string sampleCopyError = sampleCopied ? "" : copyError;
    copyError.clear();
    const bool encoderCopied = copy_vector_to_tensor<float>(encoder, encoderBuffer, copyError);
    const std::string encoderCopyError = encoderCopied ? "" : copyError;
    copyError.clear();
    const bool timestepCopied = copy_vector_to_tensor<int>(timestep, timestepBuffer, copyError);
    const std::string timestepCopyError = timestepCopied ? "" : copyError;
    nlohmann::json inputJson = {
        {"sample", tensor_metadata_json(sample)},
        {"encoderHiddenStates", tensor_metadata_json(encoder)},
        {"timestep", tensor_metadata_json(timestep)},
        {"sampleHasHost", tensor_has_host<float>(sample)},
        {"encoderHasHost", tensor_has_host<float>(encoder)},
        {"timestepHasHost", tensor_has_host<int>(timestep)},
        {"sampleCopied", sampleCopied},
        {"encoderCopied", encoderCopied},
        {"timestepCopied", timestepCopied},
        {"sampleCopyError", sampleCopyError},
        {"encoderCopyError", encoderCopyError},
        {"timestepCopyError", timestepCopyError},
        {"layout", "NCHW"},
        {"sessionBatch", profile.batch},
        {"logicalCfgBranches", 2},
        {"cfgExecutionMode", profile.batch == 2 ? "batched" : "sequential"}
    };
    if (!sampleCopied || !encoderCopied || !timestepCopied) {
        __android_log_print(ANDROID_LOG_ERROR, "mca_mnn_native",
                            "UNet smoke[%s] copy_inputs_failed sample=%d encoder=%d timestep=%d",
                            profile.name.c_str(), sampleCopied, encoderCopied, timestepCopied);
        interpreter->releaseSession(session);
        return nlohmann::json({
            {"ok", false},
            {"error", "UNet input copy failed."},
            {"backendMode", useOpenCl ? "opencl" : "cpu"},
            {"profile", profile.name},
            {"resizeStatusBefore", resizeStatusBefore},
            {"resizeStatusAfter", resizeStatusAfter},
            {"inputs", inputJson}
        });
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] copy_inputs_completed", profile.name.c_str());
    const auto runStarted = now_ms();
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] run_session_start", profile.name.c_str());
    const auto runCode = interpreter->runSession(session);
    const auto runMs = now_ms() - runStarted;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet smoke[%s] run_session_completed code=%d runMs=%lld",
                        profile.name.c_str(), static_cast<int>(runCode),
                        static_cast<long long>(runMs));
    auto* output = interpreter->getSessionOutput(session, "out_sample");
    nlohmann::json outputJson = tensor_metadata_json(output);
    bool outputCopied = false;
    double firstValue = 0.0;
    if (output != nullptr) {
        if (output->host<float>() != nullptr) {
            outputCopied = true;
            firstValue = output->host<float>()[0];
        } else {
            std::unique_ptr<MNN::Tensor> outputHost(MNN::Tensor::createHostTensorFromDevice(output, false));
            outputCopied = outputHost != nullptr && output->copyToHostTensor(outputHost.get());
            if (outputCopied && outputHost->elementSize() > 0 && outputHost->host<float>() != nullptr) {
                firstValue = outputHost->host<float>()[0];
            }
        }
    }
    interpreter->releaseSession(session);
    const bool ok = runCode == MNN::NO_ERROR && output != nullptr && outputCopied;
    return nlohmann::json({
        {"ok", ok},
        {"error", ok ? "" : ("UNet smoke failed: runCode=" + std::to_string(static_cast<int>(runCode)) +
                " (" + mnn_error_code_name(runCode) + "), resizeStatusAfter=" + std::to_string(resizeStatusAfter))},
        {"backendMode", useOpenCl ? "opencl" : "cpu"},
        {"profile", profile.name},
        {"resizeMode", profile.resizeDefer ? "defer" : "direct"},
        {"inputMode", profile.inputUser ? "user" : "inside"},
        {"path", path},
        {"externalWeight", file_exists(weightPath)},
        {"resizeCalled", true},
        {"resizeStatusBefore", resizeStatusBefore},
        {"resizeStatusAfter", resizeStatusAfter},
        {"runCode", static_cast<int>(runCode)},
        {"runCodeName", mnn_error_code_name(runCode)},
        {"elapsedMs", now_ms() - started},
        {"runMs", runMs},
        {"inputs", inputJson},
        {"sampleCopied", sampleCopied},
        {"encoderCopied", encoderCopied},
        {"timestepCopied", timestepCopied},
        {"execution", "runSession"},
        {"output", outputJson},
        {"outputCopied", outputCopied},
        {"firstValue", firstValue}
    });
}
#endif

}  // namespace mnn
}  // namespace mca
