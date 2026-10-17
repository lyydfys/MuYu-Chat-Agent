#include "mca/mnn/mnn_diffusion_pipeline.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_tensor_copy.hpp"
#include "mca/mnn/mnn_diffusion_asset_contract.hpp"
#include "mca/mnn/mnn_contracts.hpp"
#include "mca/mnn/mnn_bundle_config.hpp"

#include <cmath>
#include <limits>
#include <memory>
#include <cstring>
#include <chrono>
#include <android/log.h>

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <diffusion/diffusion.hpp>
#endif

namespace mca {
namespace mnn {

namespace {

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

}  // namespace




bool resolve_mnn_diffusion_runner(
        bool sana_family,
        const std::string& requested_runner,
        std::string& runner,
        std::string& warning,
        std::string& error) {
    const auto normalized = normalize_mnn_diffusion_identifier(requested_runner);
    warning.clear();
    if (sana_family) {
        if (normalized.empty() || normalized == "sana" || normalized == "sana_varp") {
            runner = "sana_varp";
            return true;
        }
        if (normalized == "module") {
            runner = "sana_varp";
            warning = "runner=module is accepted as a legacy alias for Sana's sana_varp runner.";
            return true;
        }
        error = "Unsupported MNN Sana runner '" + requested_runner +
                "'. Supported values are sana_varp, sana, and the legacy module alias.";
        return false;
    }
    if (normalized.empty() || normalized == "module") {
        runner = "module";
        return true;
    }
    if (normalized == "direct") {
        runner = "direct";
        return true;
    }
    error = "Unsupported MNN Stable Diffusion runner '" + requested_runner +
            "'. Supported values are module and direct.";
    return false;
}

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
bool copy_mnn_timestep_to_tensor(
        MNN::Tensor* tensor,
        double timestep,
        std::string& error) {
    if (tensor == nullptr || tensor->elementSize() != 1) {
        error = "UNet timestep input must contain exactly one element.";
        return false;
    }
    if (!std::isfinite(timestep)) {
        error = "UNet timestep must be finite.";
        return false;
    }
    const auto type = tensor->getType();
    if (type.code == halide_type_float && type.bits == 32 && type.lanes == 1) {
        return copy_vector_to_tensor<float>(
                tensor,
                {static_cast<float>(timestep)},
                error);
    }
    if (type.code == halide_type_int && type.bits == 32 && type.lanes == 1) {
        if (timestep < static_cast<double>(std::numeric_limits<int>::min()) ||
            timestep > static_cast<double>(std::numeric_limits<int>::max())) {
            error = "UNet timestep exceeds int32 range.";
            return false;
        }
        // The official converted MNN graph exposes an int32 timestep. Preserve
        // the scheduler's nearest discrete timestep instead of truncating toward
        // zero, which biases every fractional schedule point downward.
        return copy_vector_to_tensor<int>(
                tensor,
                {static_cast<int>(std::llround(timestep))},
                error);
    }
    error = "UNet timestep input must be scalar float32 or int32.";
    return false;
}

bool run_text_encoder_direct_once(
        const std::string& root,
        const std::vector<int>& ids,
        const std::string& backendMode,
        int threads,
        FloatTensorData& embeddings,
        std::string& error,
        const MnnOpenedTextEncoderBinding* textEncoderBinding) {
    if (ids.size() != 77U && ids.size() != 2U * 77U) {
        error = "text_encoder requires exactly 77 conditional ids or 154 CFG-pair ids.";
        return false;
    }
    const int batch = ids.size() == 77U ? 1 : 2;
    const bool hasDeclaredBinding =
            textEncoderBinding != nullptr && textEncoderBinding->active;
    const std::string path = hasDeclaredBinding
            ? textEncoderBinding->graph.opened_path()
            : root + "/text_encoder.mnn";
    const char* graphLabel = hasDeclaredBinding ? "declared MNN text encoder" : "text_encoder";
    if (path.empty()) {
        error = "The declared MNN text encoder descriptor is no longer open.";
        return false;
    }
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = std::string("Failed to create ") + graphLabel + " interpreter.";
        return false;
    }
    if (hasDeclaredBinding && textEncoderBinding->weight.fd >= 0) {
        const auto weightPath = textEncoderBinding->weight.opened_path();
        if (weightPath.empty()) {
            error = "The declared MNN text encoder weight descriptor is no longer open.";
            return false;
        }
        interpreter->setExternalFile(weightPath.c_str());
    } else if (!hasDeclaredBinding) {
        const auto weightPath = path + ".weight";
        if (file_exists(weightPath)) {
            interpreter->setExternalFile(weightPath.c_str());
        }
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error, true)) {
        error = std::string(graphLabel) + " session configuration failed: " + config_error;
        return false;
    }
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = std::string("Failed to create ") + graphLabel + " session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "input_ids");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input_ids tensor is missing before resize.";
        return false;
    }
    interpreter->resizeTensor(input, {batch, 77});
    interpreter->resizeSession(session, 1);
    int resizeStatus = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    input = interpreter->getSessionInput(session, "input_ids");
    if (resizeStatus != 0 || input == nullptr) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " resizeSession failed with status " +
                std::to_string(resizeStatus) + ".";
        return false;
    }
    std::string copyError;
    if (!copy_vector_to_tensor<int>(input, ids, copyError)) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " input copy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = std::string(graphLabel) + " runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "last_hidden_state");
    const bool ok = copy_tensor_to_float_vector(tensor, embeddings, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = std::string(graphLabel) + " output copy failed: " + error;
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {batch, 77, 768}, std::string(graphLabel) + " output", error)) {
        return false;
    }
    return true;
}

bool run_unet_direct(
        const std::string& root,
        const std::vector<float>& sample,
        double timestep,
        const FloatTensorData& embeddings,
        const std::string& backendMode,
        int threads,
        FloatTensorData& noise,
        std::string& error) {
    constexpr int kCfgBatch = 2;
    constexpr int kLatentElements = 4 * 64 * 64;
    if (!std::isfinite(timestep) || timestep < 0.0 || timestep >= 1000.0) {
        error = "UNet timestep must be in [0, 999].";
        return false;
    }
    if (sample.size() != static_cast<size_t>(kCfgBatch * kLatentElements)) {
        error = "UNet sample must have shape [2,4,64,64].";
        return false;
    }
    if (!validate_finite_float_vector(sample, "UNet sample", error)) {
        return false;
    }
    if (!validate_float_tensor_contract(
            embeddings, {2, 77, 768}, "UNet encoder_hidden_states", error)) {
        return false;
    }
    const auto path = root + "/unet.mnn";
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct create_interpreter_start");
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create UNet interpreter.";
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct create_interpreter_completed");
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error)) {
        error = "UNet session configuration failed: " + config_error;
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet direct create_session_start backend=%s threads=%d",
                        backendMode.c_str(), threads);
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create UNet session.";
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct create_session_completed");
    auto* sampleInput = interpreter->getSessionInput(session, "sample");
    auto* timestepInput = interpreter->getSessionInput(session, "timestep");
    auto* encoderInput = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (sampleInput == nullptr || timestepInput == nullptr || encoderInput == nullptr) {
        interpreter->releaseSession(session);
        error = "UNet input tensor not found before resize.";
        return false;
    }
    interpreter->resizeTensor(sampleInput, {kCfgBatch, 4, 64, 64});
    interpreter->resizeTensor(timestepInput, {1});
    interpreter->resizeTensor(encoderInput, {kCfgBatch, 77, 768});
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct resize_session_start");
    interpreter->resizeSession(session, 1);
    int resizeStatus = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet direct resize_session_completed status=%d", resizeStatus);
    sampleInput = interpreter->getSessionInput(session, "sample");
    timestepInput = interpreter->getSessionInput(session, "timestep");
    encoderInput = interpreter->getSessionInput(session, "encoder_hidden_states");
    if (resizeStatus != 0 || sampleInput == nullptr || timestepInput == nullptr || encoderInput == nullptr) {
        interpreter->releaseSession(session);
        error = "UNet resizeSession failed with status " + std::to_string(resizeStatus) + ".";
        return false;
    }
    std::string copyError;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct copy_inputs_start");
    if (!copy_vector_to_tensor<float>(sampleInput, sample, copyError)) {
        interpreter->releaseSession(session);
        error = "UNet sample input copy failed: " + copyError;
        return false;
    }
    // Exporters differ: some SD1.5 MNN graphs expose an int32 timestep while
    // others retain the scheduler's float32 value.  Always dispatch through the
    // type-aware helper; truncating a fractional DPM++ timestep silently turns
    // the denoiser into a noise generator even though every graph invocation
    // reports success.
    if (!copy_mnn_timestep_to_tensor(timestepInput, static_cast<double>(timestep), copyError)) {
        interpreter->releaseSession(session);
        error = "UNet timestep input copy failed: " + copyError;
        return false;
    }
    if (!copy_vector_to_tensor<float>(encoderInput, embeddings.values, copyError)) {
        interpreter->releaseSession(session);
        error = "UNet encoder_hidden_states input copy failed: " + copyError;
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct copy_inputs_completed");
    const auto runStarted = now_ms();
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native", "UNet direct run_session_start");
    const auto code = interpreter->runSession(session);
    const auto runMs = now_ms() - runStarted;
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_native",
                        "UNet direct run_session_completed code=%d runMs=%lld",
                        static_cast<int>(code), static_cast<long long>(runMs));
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = "UNet runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "out_sample");
    const bool ok = copy_tensor_to_float_vector(tensor, noise, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = "UNet output copy failed: " + error;
        return false;
    }
    if (noise.shape == std::vector<int>({kCfgBatch, 64, 64, 4})) {
        noise.values = nhwc_to_nchw(noise.values, kCfgBatch, 64, 64, 4);
        noise.shape = {kCfgBatch, 4, 64, 64};
    }
    if (!validate_float_tensor_contract(noise, {kCfgBatch, 4, 64, 64}, "UNet output", error)) {
        return false;
    }
    return true;
}

bool run_vae_decoder_direct(
        const std::string& root,
        const std::vector<float>& latent,
        const std::string& backendMode,
        int threads,
        FloatTensorData& image,
        std::string& error) {
    constexpr int kLatentElements = 4 * 64 * 64;
    if (latent.size() != static_cast<size_t>(kLatentElements)) {
        error = "VAE latent input must have shape [1,4,64,64].";
        return false;
    }
    if (!validate_finite_float_vector(latent, "VAE latent input", error)) {
        return false;
    }
    const auto path = root + "/vae_decoder.mnn";
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        error = "Failed to create VAE decoder interpreter.";
        return false;
    }
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    configure_direct_interpreter(interpreter.get(), false, false, false);
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    std::string config_error;
    if (!configure_mnn_session(config, backendConfig, backendMode, threads, config_error, true)) {
        error = "VAE decoder session configuration failed: " + config_error;
        return false;
    }
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        error = "Failed to create VAE decoder session.";
        return false;
    }
    auto* input = interpreter->getSessionInput(session, "latent_sample");
    if (input == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE latent_sample tensor is missing before resize.";
        return false;
    }
    interpreter->resizeTensor(input, {1, 4, 64, 64});
    interpreter->resizeSession(session, 1);
    int resizeStatus = -1;
    interpreter->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resizeStatus);
    input = interpreter->getSessionInput(session, "latent_sample");
    if (resizeStatus != 0 || input == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE resizeSession failed with status " + std::to_string(resizeStatus) + ".";
        return false;
    }
    const auto resizedShape = mnn_tensor_shape(input);
    if (resizedShape != std::vector<int>({1, 4, 64, 64})) {
        interpreter->releaseSession(session);
        error = "VAE latent_sample shape mismatch after resize: expected [1,4,64,64], got " +
                tensor_shape_string(resizedShape) + ".";
        return false;
    }
    std::string copyError;
    if (!copy_vector_to_tensor<float>(input, latent, copyError)) {
        interpreter->releaseSession(session);
        error = "VAE input copy failed after resize: " + copyError;
        return false;
    }
    const auto code = interpreter->runSession(session);
    if (code != MNN::NO_ERROR) {
        interpreter->releaseSession(session);
        error = "VAE runSession failed: " + std::to_string(static_cast<int>(code)) +
                " (" + mnn_error_code_name(code) + ")";
        return false;
    }
    auto* tensor = interpreter->getSessionOutput(session, "sample");
    if (tensor == nullptr) tensor = interpreter->getSessionOutput(session, "output");
    if (tensor == nullptr) tensor = interpreter->getSessionOutput(session, nullptr);
    if (tensor == nullptr) {
        interpreter->releaseSession(session);
        error = "VAE decoder output tensor is missing (expected sample/output).";
        return false;
    }
    const bool ok = copy_tensor_to_float_vector(tensor, image, error);
    interpreter->releaseSession(session);
    if (!ok) {
        error = "VAE output copy failed: " + error;
        return false;
    }
    if (image.shape == std::vector<int>({1, 512, 512, 3})) {
        image.values = nhwc_to_nchw(image.values, 1, 512, 512, 3);
        image.shape = {1, 3, 512, 512};
    }
    if (!validate_float_tensor_contract(image, {1, 3, 512, 512}, "VAE output", error)) {
        return false;
    }
    return true;
}

nlohmann::json run_mnn_sd15_module_runner(
        const std::string& root,
        const std::string& prompt,
        const std::string& outputPath,
        int steps,
        int seed,
        const std::string& backendMode,
        int memoryMode,
        std::function<void(int)> progressCallback) {
    std::string canonicalBackend;
    std::string error;
    if (!canonical_mnn_diffusion_backend(backendMode, canonicalBackend, error)) {
        return nlohmann::json({{"ok", false}, {"error", error}});
    }
    if (memoryMode < 0 || memoryMode > 2) {
        return nlohmann::json({{"ok", false}, {"error", "MNN-Diffusion memoryMode must be 0, 1, or 2."}});
    }
    if (progressCallback) {
        progressCallback(0);
    }
    const auto backend = canonicalBackend == "opencl" ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;
    std::unique_ptr<MNN::DIFFUSION::Diffusion> diffusion(
            MNN::DIFFUSION::Diffusion::createDiffusion(
                    root,
                    MNN::DIFFUSION::STABLE_DIFFUSION_1_5,
                    backend,
                    memoryMode));
    if (!diffusion) {
        return nlohmann::json({{"ok", false}, {"error", "Failed to create MNN-Diffusion module runner."}});
    }
    if (progressCallback) {
        progressCallback(1);
    }
    if (!diffusion->load()) {
        return nlohmann::json({{"ok", false}, {"error", "MNN-Diffusion module runner failed to load."}});
    }
    if (progressCallback) {
        progressCallback(2);
    }
    const bool ok = diffusion->run(prompt, outputPath, steps, seed, progressCallback);
    if (!ok) {
        return nlohmann::json({{"ok", false}, {"error", "MNN-Diffusion module runner failed to generate image."}});
    }
    return nlohmann::json({{"ok", true}, {"path", outputPath}, {"mimeType", "image/png"}});
}
#endif

}  // namespace mnn
}  // namespace mca
