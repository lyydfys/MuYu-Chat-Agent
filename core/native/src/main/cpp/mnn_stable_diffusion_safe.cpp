#include <random>
#include <fstream>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#include <android/log.h>
#include "diffusion/stable_diffusion.hpp"
#include "tokenizer.hpp"
#include "scheduler.hpp"
#define MNN_OPEN_TIME_TRACE
#include <MNN/AutoTime.hpp>
#include <cv/cv.hpp>
#include <fstream>
#include <sstream>
#include <MNN/expr/ExecutorScope.hpp>

#if defined(_MSC_VER)
#include <Windows.h>
#undef min
#undef max
#else
#include <sys/time.h>
#endif

// #define MNN_DUMP_DATA

using namespace CV;

namespace MNN {
namespace DIFFUSION {

namespace {

void mca_sd_log(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, "mca_mnn_sd", "%s", message.c_str());
    MNN_PRINT("[MCA-MNN-SD] %s\n", message.c_str());
}

void require_var(const VARP& value, const char* stage) {
    if (value.get() == nullptr) {
        throw std::runtime_error(std::string("MNN-Diffusion produced a null tensor at ") + stage);
    }
}

std::string var_info_string(const VARP& value) {
    if (value.get() == nullptr) {
        return "<null>";
    }
    auto* info = value->getInfo();
    if (info == nullptr) {
        return "<no-info>";
    }
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < info->dim.size(); ++i) {
        if (i > 0) out << ",";
        out << info->dim[i];
    }
    out << "]"
        << " order=" << static_cast<int>(info->order)
        << " type=" << static_cast<int>(info->type.code)
        << ":" << static_cast<int>(info->type.bits)
        << " size=" << info->size;
    return out.str();
}

VARP first_output_or_throw(const std::vector<VARP>& outputs, const char* stage) {
    if (outputs.empty() || outputs[0].get() == nullptr) {
        throw std::runtime_error(std::string("MNN-Diffusion produced no output at ") + stage);
    }
    return outputs[0];
}

std::vector<VARP> forward_module(Module* module, const std::vector<VARP>& inputs) {
    if (module == nullptr) {
        return {};
    }
    return module->onForward(inputs);
}

template <typename T>
T* write_map_or_throw(const VARP& value, const char* stage) {
    require_var(value, stage);
    auto* ptr = value->writeMap<T>();
    if (ptr == nullptr) {
        throw std::runtime_error(std::string("MNN-Diffusion failed to map tensor at ") + stage);
    }
    return ptr;
}

bool is_supported_stable_model_type(DiffusionModelType model_type) {
    return model_type == STABLE_DIFFUSION_1_5 || model_type == STABLE_DIFFUSION_TAIYI_CHINESE;
}

void require_module(const std::vector<std::shared_ptr<Module>>& modules, size_t index, const char* stage) {
    if (index >= modules.size() || !modules[index]) {
        throw std::runtime_error(std::string("MNN-Diffusion module is unavailable at ") + stage);
    }
}

} // namespace

StableDiffusion::StableDiffusion(std::string modelPath, DiffusionModelType modelType, MNNForwardType backendType, int memoryMode)
    : Diffusion(modelPath, modelType, backendType, memoryMode) {
    if(modelType == STABLE_DIFFUSION_1_5) {
        mMaxTextLen = 77;
    } else if(modelType == STABLE_DIFFUSION_TAIYI_CHINESE) {
        mMaxTextLen = 512;
    }
    // compute timesteps alphas
    std::unique_ptr<Scheduler> scheduler;
    scheduler.reset(new PNDMScheduler);
    mAlphas = scheduler->get_alphas();
}

bool StableDiffusion::load() {
    AUTOTIME;
    mca_sd_log("load: begin");
    auto clear_load_state = [this]() {
        mModules.clear();
        mTokenizer.reset();
        runtime_manager_.reset();
        mEts.clear();
        mSample = nullptr;
        mLatentVar = nullptr;
        mPromptVar = nullptr;
        mTimestepVar = nullptr;
        mSampleVar = nullptr;
        mInitNoise.clear();
        mResizeCachePrepared.clear();
    };
    clear_load_state();

#if !defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    MNN_ERROR("Diffusion models require MNN_BUILD_LLM=ON so diffusion can load tokenizer.mtok\n");
    return false;
#endif
    if (!is_supported_stable_model_type(mModelType)) {
        MNN_ERROR("Unsupported diffusion model type: %d\n", static_cast<int>(mModelType));
        return false;
    }
    if (mBackendType != MNN_FORWARD_CPU && mBackendType != MNN_FORWARD_OPENCL) {
        MNN_ERROR("Unsupported Stable Diffusion backend: %d. Only CPU and OpenCL are supported.\n",
                  static_cast<int>(mBackendType));
        return false;
    }
    if (mMaxTextLen <= 0) {
        MNN_ERROR("Stable Diffusion tokenizer context length is invalid: %d\n", mMaxTextLen);
        return false;
    }

    try {
        ScheduleConfig config;
        BackendConfig backendConfig;
        config.type = mBackendType;
        if (config.type == MNN_FORWARD_CPU) {
            config.numThread = 4;
        } else {
            config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
        }
        backendConfig.memory = BackendConfig::Memory_Low;
        backendConfig.precision = BackendConfig::Precision_Low;
        config.backendConfig = &backendConfig;

        auto exe = ExecutorScope::Current();
        if (!exe) {
            throw std::runtime_error("MNN ExecutorScope::Current returned null.");
        }
        exe->lazyEval = false;
        exe->setGlobalExecutorConfig(config.type, backendConfig, config.numThread);

        Module::Config module_config;
        module_config.shapeMutable = false;
        runtime_manager_.reset(Executor::RuntimeManager::createRuntimeManager(config));
        if (!runtime_manager_) {
            throw std::runtime_error("MNN RuntimeManager creation failed.");
        }

        if (config.type == MNN_FORWARD_OPENCL) {
            runtime_manager_->setCache(".tempcache");
        }
        if (mMemoryMode == 0) {
            runtime_manager_->setHint(Interpreter::WINOGRAD_MEMORY_LEVEL, 0);
        } else if (mMemoryMode == 2) {
            runtime_manager_->setHint(Interpreter::WINOGRAD_MEMORY_LEVEL, 1);
        }
        if (config.type == MNN_FORWARD_CPU) {
            runtime_manager_->setHint(Interpreter::DYNAMIC_QUANT_OPTIONS, 2);
        }

        const size_t prompt_token_count = static_cast<size_t>(mMaxTextLen) * 2;
        mLatentVar = _Input({1, 4, 64, 64}, NCHW, halide_type_of<float>());
        mPromptVar = _Input({2, mMaxTextLen}, NCHW, halide_type_of<int>());
        mTimestepVar = _Input({1}, NCHW, halide_type_of<int>());
        auto* latentWarmup = write_map_or_throw<float>(mLatentVar, "load.latent_input");
        auto* promptWarmup = write_map_or_throw<int>(mPromptVar, "load.prompt_input");
        auto* timestepWarmup = write_map_or_throw<int>(mTimestepVar, "load.timestep_input");
        std::fill(latentWarmup, latentWarmup + 16384, 0.0f);
        std::fill(promptWarmup, promptWarmup + prompt_token_count, 0);
        timestepWarmup[0] = 1;
        mSampleVar = _Concat({mLatentVar, mLatentVar}, 0);
        require_var(mSampleVar, "load.sample_input");

        if (mMemoryMode > 0) {
            MNN_PRINT("First time initializing may cost a few seconds while MNN creates its cache, please wait ...\n");
        }

        VARP text_embeddings;
        mModules.resize(3);
        {
            const std::string model_path = mModelPath + "/text_encoder.mnn";
            mca_sd_log("load: text_encoder");
            mModules[0].reset(Module::load(
                    {"input_ids"}, {"last_hidden_state", "pooler_output"},
                    model_path.c_str(), runtime_manager_, &module_config));
            require_module(mModules, 0, "load.text_encoder");
        }
        {
            const std::string model_path = mModelPath + "/unet.mnn";
            mca_sd_log("load: unet");
            mModules[1].reset(Module::load(
                    {"sample", "timestep", "encoder_hidden_states"}, {"out_sample"},
                    model_path.c_str(), runtime_manager_, &module_config));
            require_module(mModules, 1, "load.unet");
        }
        {
            const std::string model_path = mModelPath + "/vae_decoder.mnn";
            mca_sd_log("load: vae_decoder");
            mModules[2].reset(Module::load(
                    {"latent_sample"}, {"sample"}, model_path.c_str(), runtime_manager_, &module_config));
            require_module(mModules, 2, "load.vae_decoder");
        }

        if (mModelType == STABLE_DIFFUSION_1_5) {
            mTokenizer.reset(new MtokTokenizer(MtokTokenizer::Style::kPair, 49406, 49407));
        } else {
            mTokenizer.reset(new MtokTokenizer(MtokTokenizer::Style::kPair, 101, 102));
        }
        if (!mTokenizer || !mTokenizer->load(mModelPath)) {
            throw std::runtime_error("Failed to load required tokenizer.mtok from " + mModelPath + ".");
        }

        {
            mca_sd_log("load: warmup text_encoder");
            auto outputs = forward_module(mModules[0].get(), {mPromptVar});
            text_embeddings = _Convert(first_output_or_throw(outputs, "load.text_encoder"), NCHW);
            require_var(text_embeddings, "load.text_encoder.convert");
            text_embeddings.fix(VARP::CONSTANT);
        }

        if (mMemoryMode > 0) {
            mca_sd_log("load: warmup unet");
            auto outputs = forward_module(mModules[1].get(), {mSampleVar, mTimestepVar, text_embeddings});
            auto output = _Convert(first_output_or_throw(outputs, "load.unet"), NCHW);
            require_var(output, "load.unet.convert");
            if (output->readMap<float>() == nullptr) {
                throw std::runtime_error("MNN-Diffusion failed to read warmup UNet output.");
            }
        }
        if (mMemoryMode == 1) {
            mca_sd_log("load: warmup vae_decoder");
            auto outputs = forward_module(mModules[2].get(), {mLatentVar});
            auto output = _Convert(first_output_or_throw(outputs, "load.vae_decoder"), NCHW);
            require_var(output, "load.vae_decoder.convert");
            if (output->readMap<float>() == nullptr) {
                throw std::runtime_error("MNN-Diffusion failed to read warmup VAE output.");
            }
        }

        mca_sd_log("load: complete");
        return true;
    } catch (const std::exception& e) {
        clear_load_state();
        const std::string message = std::string("MNN-Diffusion load failed: ") + e.what();
        MNN_ERROR("%s\n", message.c_str());
        mca_sd_log(message);
        return false;
    } catch (...) {
        clear_load_state();
        const std::string message = "MNN-Diffusion load failed: unknown native error.";
        MNN_ERROR("%s\n", message.c_str());
        mca_sd_log(message);
        return false;
    }
}

std::vector<VARP> StableDiffusion::forwardWithResizeCache(int index, const std::vector<VARP>& inputs) {
    if (index < 0 || index >= mModules.size() || !mModules[index]) {
        MNN_ERROR("Invalid diffusion module index: %d\n", index);
        return {};
    }
    mca_sd_log("forward: module " + std::to_string(index) + " begin");
    for (size_t i = 0; i < inputs.size(); ++i) {
        mca_sd_log("forward: module " + std::to_string(index) +
                   " input[" + std::to_string(i) + "]=" + var_info_string(inputs[i]));
    }
    if (mResizeCachePrepared.size() != mModules.size()) {
        mResizeCachePrepared.assign(mModules.size(), false);
    }
    if (!mResizeCachePrepared[index]) {
        mca_sd_log("forward: module " + std::to_string(index) + " resize_check");
        auto code = mModules[index]->traceOrOptimize(MNN::Interpreter::Session_Resize_Check);
        mca_sd_log("forward: module " + std::to_string(index) + " resize_check_code=" + std::to_string(code));
        if (code == 0) {
            auto warmupOutputs = mModules[index]->onForward(inputs);
            mca_sd_log("forward: module " + std::to_string(index) +
                       " resize_warmup_outputs=" + std::to_string(warmupOutputs.size()));
            code = mModules[index]->traceOrOptimize(MNN::Interpreter::Session_Resize_Fix);
            mca_sd_log("forward: module " + std::to_string(index) + " resize_fix_code=" + std::to_string(code));
            if (code != 0) {
                MNN_PRINT("Resize fix is not supported for diffusion module %d, code = %d\n", index, code);
            }
        } else {
            MNN_PRINT("Resize check is not supported for diffusion module %d, code = %d\n", index, code);
        }
        mResizeCachePrepared[index] = true;
    }
    auto outputs = mModules[index]->onForward(inputs);
    mca_sd_log("forward: module " + std::to_string(index) + " outputs=" + std::to_string(outputs.size()));
    for (size_t i = 0; i < outputs.size(); ++i) {
        mca_sd_log("forward: module " + std::to_string(index) +
                   " output[" + std::to_string(i) + "]=" + var_info_string(outputs[i]));
    }
    return outputs;
}

VARP StableDiffusion::text_encoder(const std::vector<int>& ids) {
    AUTOTIME;

    if (mMaxTextLen <= 0) {
        throw std::runtime_error("MNN-Diffusion text encoder has an invalid tokenizer context length.");
    }
    const size_t expected_token_count = static_cast<size_t>(mMaxTextLen) * 2;
    if (ids.size() != expected_token_count) {
        throw std::runtime_error(
                "MNN-Diffusion tokenizer returned " + std::to_string(ids.size()) +
                " ids; expected " + std::to_string(expected_token_count) + ".");
    }
    require_var(mPromptVar, "text_encoder.prompt_input");
    require_module(mModules, 0, "text_encoder");
    std::memcpy(
            write_map_or_throw<int>(mPromptVar, "text_encoder.prompt_ids"),
            ids.data(),
            expected_token_count * sizeof(int));

    mca_sd_log("text_encoder: begin");
    auto outputs = forwardWithResizeCache(0, {mPromptVar});
    auto output = _Convert(first_output_or_throw(outputs, "text_encoder.forward"), NCHW);
    require_var(output, "text_encoder.convert");
    output.fix(VARP::CONSTANT);
    mca_sd_log("text_encoder: complete");
    return output;
}

VARP StableDiffusion::step_plms(VARP sample, VARP model_output, int index) {
    require_var(sample, "scheduler.sample");
    require_var(model_output, "scheduler.model_output");
    if (index < 0 || static_cast<size_t>(index) >= mTimeSteps.size()) {
        throw std::runtime_error("MNN-Diffusion scheduler step index is outside its timestep schedule.");
    }
    if (mAlphas.empty()) {
        throw std::runtime_error("MNN-Diffusion scheduler does not contain alpha coefficients.");
    }

    int timestep = mTimeSteps[index];
    int prev_timestep = 0;
    if (index + 1 < mTimeSteps.size()) {
        prev_timestep = mTimeSteps[index + 1];
    }
    if (index != 1) {
        if (mEts.size() >= 4) {
            mEts.erase(mEts.begin());
        }
        mEts.push_back(model_output);
    } else {
        if (mTimeSteps.size() < 2 || mEts.empty()) {
            throw std::runtime_error("MNN-Diffusion scheduler is missing PLMS history for its second step.");
        }
        timestep = mTimeSteps[0];
        prev_timestep = mTimeSteps[1];
    }
    if (mEts.empty()) {
        throw std::runtime_error("MNN-Diffusion scheduler has no model-output history.");
    }
    const int ets = static_cast<int>(mEts.size()) - 1;
    if (index == 0) {
        mSample = sample;
    } else if (index == 1) {
        require_var(mSample, "scheduler.first_sample");
        require_var(mEts[ets], "scheduler.previous_model_output");
        model_output = (model_output + mEts[ets]) * _Const(0.5);
        sample = mSample;
    } else if (ets == 1) {
        require_var(mEts[ets], "scheduler.model_output_1");
        require_var(mEts[ets - 1], "scheduler.model_output_0");
        model_output = (_Const(3.0) * mEts[ets] - mEts[ets-1]) * _Const(0.5);
    } else if (ets == 2) {
        require_var(mEts[ets], "scheduler.model_output_2");
        require_var(mEts[ets - 1], "scheduler.model_output_1");
        require_var(mEts[ets - 2], "scheduler.model_output_0");
        model_output = (_Const(23.0) * mEts[ets] - _Const(16.0) * mEts[ets-1] + _Const(5.0) * mEts[ets-2]) * _Const(1.0 / 12.0);
    } else if (ets >= 3) {
        require_var(mEts[ets], "scheduler.model_output_3");
        require_var(mEts[ets - 1], "scheduler.model_output_2");
        require_var(mEts[ets - 2], "scheduler.model_output_1");
        require_var(mEts[ets - 3], "scheduler.model_output_0");
        model_output = _Const(1. / 24.) * (_Const(55.0) * mEts[ets] - _Const(59.0) * mEts[ets-1] + _Const(37.0) * mEts[ets-2] - _Const(9.0) * mEts[ets-3]);
    }
    require_var(model_output, "scheduler.adjusted_model_output");
    if (timestep < 0 || prev_timestep < 0 ||
        static_cast<size_t>(timestep) >= mAlphas.size() ||
        static_cast<size_t>(prev_timestep) >= mAlphas.size()) {
        throw std::runtime_error("MNN-Diffusion scheduler timestep is outside the alpha schedule.");
    }
    auto alpha_prod_t = mAlphas[timestep];
    auto alpha_prod_t_prev = mAlphas[prev_timestep];
    if (!std::isfinite(alpha_prod_t) || !std::isfinite(alpha_prod_t_prev) ||
        alpha_prod_t <= 0.0f || alpha_prod_t > 1.0f ||
        alpha_prod_t_prev <= 0.0f || alpha_prod_t_prev > 1.0f) {
        throw std::runtime_error("MNN-Diffusion scheduler received invalid alpha coefficients.");
    }
    auto beta_prod_t = 1 - alpha_prod_t;
    auto beta_prod_t_prev = 1 - alpha_prod_t_prev;
    if (!std::isfinite(beta_prod_t) || !std::isfinite(beta_prod_t_prev) ||
        beta_prod_t < 0.0f || beta_prod_t_prev < 0.0f) {
        throw std::runtime_error("MNN-Diffusion scheduler produced invalid beta coefficients.");
    }
    auto sample_coeff = std::sqrt(alpha_prod_t_prev / alpha_prod_t);
    auto model_output_denom_coeff = alpha_prod_t * std::sqrt(beta_prod_t_prev) + std::sqrt(alpha_prod_t * beta_prod_t * alpha_prod_t_prev);
    if (!std::isfinite(sample_coeff) || !std::isfinite(model_output_denom_coeff) ||
        std::fabs(model_output_denom_coeff) <= std::numeric_limits<float>::epsilon()) {
        throw std::runtime_error("MNN-Diffusion scheduler produced an invalid PLMS denominator.");
    }
    auto prev_sample = _Scalar(sample_coeff) * sample - _Scalar((alpha_prod_t_prev - alpha_prod_t)/model_output_denom_coeff) * model_output;
    require_var(prev_sample, "scheduler.previous_sample");
    return prev_sample;
}

VARP StableDiffusion::unet(VARP text_embeddings, int iterNum, int randomSeed, std::function<void(int)> progressCallback) {
    mca_sd_log("unet: begin");
    require_var(text_embeddings, "unet.text_embeddings");
    if (iterNum <= 0 || mTimeSteps.size() != static_cast<size_t>(iterNum)) {
        throw std::runtime_error("MNN-Diffusion UNet step count does not match its scheduler state.");
    }
    require_module(mModules, 1, "unet");
    require_var(mLatentVar, "unet.latent_input");
    require_var(mTimestepVar, "unet.timestep_input");
    if(mMemoryMode != 1) {
        if (!mModules.empty()) {
            mModules[0].reset();
        }
    }
    if(mInitNoise.size() != 16384) {
        mInitNoise.resize(16384);
    }
#ifdef MNN_DUMP_DATA
    std::ostringstream fileName;
    fileName << "random.txt";
    std::ifstream input(fileName.str().c_str());
    for (int i = 0; i < 16384; ++i) {
        input >> mInitNoise[i];
    }
#else
    int seed = randomSeed < 0 ? std::random_device()() : randomSeed;
    std::mt19937 rng;
    rng.seed(seed);

    std::normal_distribution<float> normal(0, 1);
    for (int i = 0; i < 16384; i++) {
        mInitNoise[i] = normal(rng);
    }
#endif

    std::memcpy(
            write_map_or_throw<float>(mLatentVar, "unet.initial_noise"),
            mInitNoise.data(),
            mInitNoise.size() * sizeof(float));

    VARP scalevar = _Input({1}, NCHW, halide_type_of<float>());
    auto* scaleptr = write_map_or_throw<float>(scalevar, "unet.cfg_scale");
    scaleptr[0] = 7.5f;

    auto floatVar = _Input({1}, NCHW, halide_type_of<float>());
    auto* ptr = write_map_or_throw<float>(floatVar, "unet.timestep_float");
    auto plms = mLatentVar;

    for (int i = 0; i < iterNum; ++i) {
        AUTOTIME;

        if (progressCallback) {
            progressCallback((2 + i) * 100 / (iterNum + 3));
        }

        int timestep = mTimeSteps[i];
        ptr[0] = static_cast<float>(timestep);
        auto temp = _Cast(floatVar, halide_type_of<int>());
        require_var(temp, "unet.timestep_cast");
        mTimestepVar->input(temp);
        mca_sd_log("unet: step " + std::to_string(i + 1) + "/" + std::to_string(mTimeSteps.size()) +
                   " timestep=" + std::to_string(timestep));

        mSampleVar = _Concat({plms, plms}, 0);
        require_var(mSampleVar, "unet.sample");
        mca_sd_log("unet: step " + std::to_string(i + 1) + " forward");
        auto outputs = forwardWithResizeCache(1, {mSampleVar, mTimestepVar, text_embeddings});
        auto output = _Convert(first_output_or_throw(outputs, "unet.forward"), NCHW);
        require_var(output, "unet.convert");

        auto noise_pred = output;
        auto splitvar = _Split(noise_pred, {2}, 0);
        if (splitvar.size() != 2) {
            throw std::runtime_error("MNN-Diffusion UNet output cannot be split into unconditional and conditional batches.");
        }
        auto noise_pred_uncond = splitvar[0];
        auto noise_pred_text = splitvar[1];
        require_var(noise_pred_uncond, "unet.unconditional_noise");
        require_var(noise_pred_text, "unet.conditional_noise");
        noise_pred = scalevar * (noise_pred_text - noise_pred_uncond) + noise_pred_uncond;
        require_var(noise_pred, "unet.guided_noise");

        mca_sd_log("unet: step " + std::to_string(i + 1) + " scheduler");
        plms = step_plms(plms, noise_pred, i);
        require_var(plms, "unet.plms");

        if (progressCallback) {
            progressCallback((3 + i) * 100 / (iterNum + 3));
        }
    }
    require_var(plms, "unet.final_latent");
    plms.fix(VARP::CONSTANT);
    mca_sd_log("unet: complete");
    return plms;
}

VARP StableDiffusion::vae_decoder(VARP latent) {
    mca_sd_log("vae_decoder: begin");
    require_var(latent, "vae_decoder.latent");
    require_module(mModules, 2, "vae_decoder");
    if(mMemoryMode != 1) {
        if (mModules.size() > 1) {
            mModules[1].reset();
        }
    }
    latent = latent * _Const(1 / 0.18215);
    require_var(latent, "vae_decoder.scaled_latent");

    AUTOTIME;
    auto outputs = forwardWithResizeCache(2, {latent});
    auto output = _Convert(first_output_or_throw(outputs, "vae_decoder.forward"), NCHW);
    require_var(output, "vae_decoder.convert");

    auto image = _Relu6(output * _Const(0.5) + _Const(0.5), 0, 1);
    require_var(image, "vae_decoder.normalized_image");
    image = _Squeeze(_Transpose(image, {0, 2, 3, 1}));
    require_var(image, "vae_decoder.transposed_image");
    image = _Cast(_Round(image * _Const(255.0)), halide_type_of<uint8_t>());
    require_var(image, "vae_decoder.uint8_image");
    image = cvtColor(image, COLOR_BGR2RGB);
    require_var(image, "vae_decoder.rgb_image");
    image.fix(VARP::CONSTANT);
    mca_sd_log("vae_decoder: complete");
    return image;
}

bool StableDiffusion::run(const std::string prompt, const std::string imagePath, int iterNum, int randomSeed, std::function<void(int)> progressCallback) {
    AUTOTIME;
    mca_sd_log("run: begin");
    if (progressCallback) {
        progressCallback(0);
    }
    if (imagePath.empty()) {
        throw std::runtime_error("MNN-Diffusion output image path is empty.");
    }
    if (!runtime_manager_ || !mTokenizer) {
        throw std::runtime_error("MNN-Diffusion is not fully loaded. Load the model before generating an image.");
    }
    require_module(mModules, 0, "run.text_encoder");
    require_module(mModules, 1, "run.unet");
    require_module(mModules, 2, "run.vae_decoder");
    require_var(mLatentVar, "run.latent_input");
    require_var(mPromptVar, "run.prompt_input");
    require_var(mTimestepVar, "run.timestep_input");
    if (iterNum < 1 || iterNum > 50) {
        throw std::runtime_error("MNN-Diffusion Stable Diffusion 1.5 supports 1 to 50 inference steps.");
    }
    if (mAlphas.empty()) {
        throw std::runtime_error("MNN-Diffusion scheduler is unavailable.");
    }
    mEts.clear();
    mSample = nullptr;
    mTimeSteps.resize(iterNum);
    int step = 1000 / iterNum;
    for(int i = iterNum - 1; i >= 0; i--) {
        mTimeSteps[i] = 1 + (iterNum - 1 - i) * step;
    }

    auto ids = mTokenizer->encode(prompt, mMaxTextLen);

    auto text_embeddings = text_encoder(ids);

    if (progressCallback) {
        progressCallback(1 * 100 / (iterNum + 3));
    }
    auto latent = unet(text_embeddings, iterNum, randomSeed, progressCallback);

    auto image = vae_decoder(latent);
    mca_sd_log("run: writing image");
    bool res = imwrite(imagePath, image);
    if (res) {
        MNN_PRINT("SUCCESS! write generated image to %s\n", imagePath.c_str());
    } else {
        MNN_ERROR("Failed to write generated image to %s\n", imagePath.c_str());
    }

    if(mMemoryMode != 1) {
        mModules[2].reset();
    }

    if (progressCallback) {
        progressCallback(100);
    }
    mca_sd_log("run: complete");
    return res;
}


// 统一的生成接口实现
// 注意：Stable Diffusion当前实现仅支持text2img模式和512x512分辨率
// input_embeds应该是已经tokenized的文本ids（shape: [2, max_text_len]）
bool StableDiffusion::run(const VARP input_embeds,
                         const std::string& mode,
                         const std::string& inputImagePath,
                         const std::string& outputImagePath,
                         int width,
                         int height,
                         int iterNum,
                         int randomSeed,
                         bool use_cfg,
                         float cfg_scale,
                         std::function<void(int)> progressCallback) {

    MNN_PRINT("Error: stable diffusion model does not support feature vector input.\n");
    return false;

}

} // namespace DIFFUSION
} // namespace MNN
