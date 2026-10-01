// JNI bridge for com.scsonic.qwenimage21.QwenImage21 -> MNN QwenImage21Diffusion.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <sstream>
#include <vector>
#if !defined(_WIN32)
#include <dlfcn.h>
#include <sys/stat.h>
#endif
#include "diffusion/qwen_image21_diffusion.hpp"

using namespace MNN::DIFFUSION;

#define LOG_TAG "QwenImage21"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr size_t kMaxNativeErrorBytes = 512;
thread_local std::string g_lastBoundaryError;
std::mutex g_qnnRuntimeMutex;
std::string g_qnnRuntimePath;
#if !defined(_WIN32)
std::vector<void*> g_qnnRuntimeHandles;
#endif

struct Handle {
    std::unique_ptr<Diffusion> model;
    QwenImage21Diffusion* qwen = nullptr;  // same object, typed
    std::mutex mutex;
    int errorCode = QwenImage21Diffusion::kOk;
    std::string error;
    MNNForwardType requestedBackend = MNN_FORWARD_CPU;
    MNNForwardType effectiveBackend = MNN_FORWARD_CPU;
    bool backendResolved = false;
    bool nativeRunCompleted = false;
    bool backendExecutionConfirmed = false;
    bool qnnRequested = false;
    int generationCount = 0;
};

const char* backendName(MNNForwardType backend) noexcept {
    switch (backend) {
        case MNN_FORWARD_CPU: return "MNN_CPU";
        case MNN_FORWARD_OPENCL: return "MNN_OPENCL";
        case MNN_FORWARD_OPENGL: return "MNN_OPENGL";
        case MNN_FORWARD_VULKAN: return "MNN_VULKAN";
        case MNN_FORWARD_NN: return "MNN_NN";
        case MNN_FORWARD_HEXAGON: return "MNN_HEXAGON";
        default: return "MNN_UNKNOWN";
    }
}

const char* effectiveBackendName(const Handle& handle) noexcept {
    if (handle.qnnRequested && handle.effectiveBackend == MNN_FORWARD_NN) return "MNN_QNN";
    return backendName(handle.effectiveBackend);
}

std::string executionAuditJson(const Handle& handle) {
    // MNN's RuntimeManager reports the effective primary runtime selected for
    // the graph; the pinned RuntimeManager::getInfo(BACKENDS) may return false
    // even when it populated that value. A successful native run plus a
    // matching non-negative resolved runtime is the strongest evidence exposed
    // by this API. This is not a claim that every operator avoided MNN's
    // internal CPU backup path.
    const bool backendMatch = handle.nativeRunCompleted && handle.backendResolved &&
                              handle.requestedBackend == handle.effectiveBackend;
    const bool adapterFallback = handle.nativeRunCompleted && !backendMatch;
    std::ostringstream json;
    json << "{"
         << "\"nativeExecution\":" << (handle.nativeRunCompleted ? "true" : "false") << ","
         << "\"fallback\":" << (adapterFallback ? "true" : "false") << ","
         << "\"fallbackScope\":\"adapter_backend_resolution\","
         << "\"nativeGenerationSequence\":" << handle.generationCount << ","
         << "\"nativeRunCompleted\":" << (handle.nativeRunCompleted ? "true" : "false") << ","
         << "\"backendExecutionConfirmedByNative\":"
         << (handle.backendExecutionConfirmed ? "true" : "false") << ","
         << "\"backendExecutionProof\":\"mnn_runtime_resolution_after_native_run\","
         << "\"backendExecutionScope\":\"primary_runtime\","
         << "\"requestedBackend\":\"" << (handle.qnnRequested ? "MNN_QNN" : backendName(handle.requestedBackend)) << "\","
         << "\"effectiveBackend\":\"" << effectiveBackendName(handle) << "\","
         << "\"backendResolved\":" << (handle.backendResolved ? "true" : "false") << ","
         << "\"nativeGenerationCount\":" << handle.generationCount << ","
         << "\"backendMatch\":" << (backendMatch ? "true" : "false")
         << "}";
    return json.str();
}

void setBoundedError(std::string& destination, const char* message) noexcept;
const char* errorOrFallback(const std::string& error, const char* fallback) noexcept;
std::string toString(JNIEnv* env, jstring s);

bool isDirectory(const std::string& path) noexcept {
#if defined(_WIN32)
    (void)path;
    return false;
#else
    struct stat info{};
    return !path.empty() && ::stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
#endif
}

bool isReadableFile(const std::string& path) noexcept {
#if defined(_WIN32)
    (void)path;
    return false;
#else
    struct stat info{};
    return !path.empty() && ::stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
#endif
}

bool preloadQnnRuntime(const std::string& path, std::string& error) {
    std::lock_guard<std::mutex> lock(g_qnnRuntimeMutex);
    if (path.empty()) return true;
    if (!isDirectory(path)) {
        error = "QNN runtime path is not a readable directory";
        return false;
    }
    if (!g_qnnRuntimePath.empty() && g_qnnRuntimePath != path) {
        error = "QNN runtime path cannot change while the isolated worker is alive";
        return false;
    }
    if (!g_qnnRuntimeHandles.empty()) return true;
#if defined(_WIN32)
    error = "QNN runtime preload is unavailable on Windows";
    return false;
#else
    const std::string systemPath = path + "/libQnnSystem.so";
    const std::string htpPath = path + "/libQnnHtp.so";
    // Keep the host handles resident. MNN_QNN resolves these SONAMEs through
    // its normal dlopen("libQnnHtp.so") path after this preload.
    void* system = ::dlopen(systemPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!system) {
        const char* detail = ::dlerror();
        error = std::string("cannot load ") + systemPath + ": " + (detail ? detail : "unknown error");
        return false;
    }
    void* htp = ::dlopen(htpPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!htp) {
        const char* detail = ::dlerror();
        error = std::string("cannot load ") + htpPath + ": " + (detail ? detail : "unknown error");
        ::dlclose(system);
        return false;
    }
    g_qnnRuntimeHandles.push_back(system);
    g_qnnRuntimeHandles.push_back(htp);

    // QAIRT's Android HTP host is split across the prepare plugin and the
    // SoC-specific transport stubs. They are not DT_NEEDED by libQnnHtp.so;
    // QNN loads them lazily while finalizing the graph. Preload every matching
    // library present in the supplied directory so an app-private runtime is
    // self-contained, while still accepting platform runtimes that expose only
    // the host pair. A missing optional transport is reported by QNN at the
    // real graph boundary and is never treated as a device admission failure.
    const char* optionalNames[] = {
        "libQnnHtpPrepare.so",
        "libQnnHtpV68Stub.so", "libQnnHtpV68CalculatorStub.so",
        "libQnnHtpV69Stub.so", "libQnnHtpV69CalculatorStub.so",
        "libQnnHtpV73Stub.so", "libQnnHtpV73CalculatorStub.so",
        "libQnnHtpV75Stub.so", "libQnnHtpV75CalculatorStub.so",
        "libQnnHtpV79Stub.so", "libQnnHtpV79CalculatorStub.so",
        "libQnnHtpV81Stub.so", "libQnnHtpV81CalculatorStub.so"
    };
    for (const char* name : optionalNames) {
        const std::string candidate = path + "/" + name;
        if (!isReadableFile(candidate)) continue;
        void* handle = ::dlopen(candidate.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle != nullptr) {
            g_qnnRuntimeHandles.push_back(handle);
        } else {
            const char* detail = ::dlerror();
            LOGI("QNN optional runtime %s not preloaded: %s", name,
                 detail ? detail : "unknown error");
        }
    }
    g_qnnRuntimePath = path;
    return true;
#endif
}

jlong createRuntime(JNIEnv* env, jstring modelDir, jint backendCode,
                    jboolean textEncoderOnCpu, jboolean vaeOnCpu,
                    jint memoryMode, jint threads) {
    const std::string dir = toString(env, modelDir);
    const bool qnn = backendCode == 2;
    const MNNForwardType backend = backendCode == 0
        ? MNN_FORWARD_CPU
        : (qnn ? MNN_FORWARD_NN : MNN_FORWARD_OPENCL);
    auto handle = std::make_unique<Handle>();
    handle->model.reset(Diffusion::createDiffusion(dir, QWEN_IMAGE_21, backend, memoryMode, 512,
                                                   512, textEncoderOnCpu, vaeOnCpu, GPU_MEMORY_BUFFER,
                                                   PRECISION_LOW, CFG_MODE_AUTO, std::max(1, (int)threads)));
    handle->qwen = static_cast<QwenImage21Diffusion*>(handle->model.get());
    if (!handle->model || !handle->model->load()) {
        const char* detail = handle->qwen ? handle->qwen->lastError().c_str() : nullptr;
        setBoundedError(g_lastBoundaryError,
                        detail && *detail ? detail : "Failed to load Qwen-Image-2.1 runtime");
        LOGE("create failed: %s", errorOrFallback(g_lastBoundaryError, "Failed to load Qwen-Image-2.1 runtime"));
        return 0;
    }
    handle->requestedBackend = backend;
    handle->qnnRequested = qnn;
    handle->backendResolved = handle->qwen->resolveEffectiveBackendType(&handle->effectiveBackend);
    if (!handle->backendResolved) handle->effectiveBackend = static_cast<MNNForwardType>(-1);
    handle->nativeRunCompleted = false;
    handle->backendExecutionConfirmed = false;
    return reinterpret_cast<jlong>(handle.release());
}

void setBoundedError(std::string& destination, const char* message) noexcept {
    try {
        if (message == nullptr) {
            destination.clear();
            return;
        }
        destination.assign(message, std::min(std::strlen(message), kMaxNativeErrorBytes));
    } catch (...) {
        destination.clear();
    }
}

void setHandleError(Handle& handle, int code, const char* message) noexcept {
    handle.errorCode = code;
    setBoundedError(handle.error, message);
}

const char* errorOrFallback(const std::string& error, const char* fallback) noexcept {
    return error.empty() ? fallback : error.c_str();
}

std::string toString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) throw std::runtime_error("JNI could not read a string argument");
    std::string result;
    try {
        result.assign(chars);
    } catch (...) {
        env->ReleaseStringUTFChars(s, chars);
        throw;
    }
    env->ReleaseStringUTFChars(s, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeCreate(JNIEnv* env, jclass, jstring modelDir, jboolean useGpu,
                                                      jboolean textEncoderOnCpu, jboolean vaeOnCpu,
                                                      jint memoryMode, jint threads) {
    g_lastBoundaryError.clear();
    try {
        return createRuntime(env, modelDir, useGpu ? 1 : 0, textEncoderOnCpu, vaeOnCpu, memoryMode, threads);
    } catch (const std::bad_alloc&) {
        setBoundedError(g_lastBoundaryError, "Out of memory while creating Qwen-Image-2.1 runtime");
        LOGE("create failed: %s",
             errorOrFallback(g_lastBoundaryError, "Out of memory while creating Qwen-Image-2.1 runtime"));
        return 0;
    } catch (const std::exception& e) {
        setBoundedError(g_lastBoundaryError, e.what());
        LOGE("create failed: %s",
             errorOrFallback(g_lastBoundaryError, "Native exception while creating Qwen-Image-2.1 runtime"));
        return 0;
    } catch (...) {
        setBoundedError(g_lastBoundaryError, "Unknown native exception while creating Qwen-Image-2.1 runtime");
        LOGE("create failed: %s",
             errorOrFallback(g_lastBoundaryError, "Unknown native exception while creating Qwen-Image-2.1 runtime"));
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeCreateWithBackend(JNIEnv* env, jclass, jstring modelDir,
                                                                  jint backendCode, jboolean textEncoderOnCpu,
                                                                  jboolean vaeOnCpu, jint memoryMode, jint threads) {
    g_lastBoundaryError.clear();
    try {
        if (backendCode < 0 || backendCode > 2) {
            setBoundedError(g_lastBoundaryError, "Unsupported Qwen-Image-2.1 backend code");
            return 0;
        }
        return createRuntime(env, modelDir, backendCode, textEncoderOnCpu, vaeOnCpu, memoryMode, threads);
    } catch (const std::bad_alloc&) {
        setBoundedError(g_lastBoundaryError, "Out of memory while creating Qwen-Image-2.1 runtime");
        LOGE("create failed: %s", errorOrFallback(g_lastBoundaryError, "Out of memory while creating Qwen-Image-2.1 runtime"));
        return 0;
    } catch (const std::exception& e) {
        setBoundedError(g_lastBoundaryError, e.what());
        LOGE("create failed: %s", errorOrFallback(g_lastBoundaryError, "Native exception while creating Qwen-Image-2.1 runtime"));
        return 0;
    } catch (...) {
        setBoundedError(g_lastBoundaryError, "Unknown native exception while creating Qwen-Image-2.1 runtime");
        LOGE("create failed: %s", errorOrFallback(g_lastBoundaryError, "Unknown native exception while creating Qwen-Image-2.1 runtime"));
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeSetQnnRuntimePath(JNIEnv* env, jclass, jstring path) {
    g_lastBoundaryError.clear();
    try {
        const std::string value = toString(env, path);
        std::string error;
        if (!preloadQnnRuntime(value, error)) {
            setBoundedError(g_lastBoundaryError, error.c_str());
            LOGE("QNN runtime preload failed: %s", errorOrFallback(g_lastBoundaryError, "QNN runtime preload failed"));
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        setBoundedError(g_lastBoundaryError, e.what());
        return JNI_FALSE;
    } catch (...) {
        setBoundedError(g_lastBoundaryError, "Unknown exception while preloading QNN runtime");
        return JNI_FALSE;
    }
}

// Returns QwenImage21Diffusion::ErrorCode (0 = ok); the message is available from nativeLastError.
extern "C" JNIEXPORT jint JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeGenerate(JNIEnv* env, jclass, jlong ptr, jstring prompt,
                                                        jstring inputImage, jstring outputPng, jint steps, jint seed,
                                                        jint width, jint height, jobject listener) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    if (!handle) {
        setBoundedError(g_lastBoundaryError, "Qwen-Image-2.1 runtime handle is null");
        return QwenImage21Diffusion::kRuntimeError;
    }
    g_lastBoundaryError.clear();
    bool ok = false;
    try {
        std::lock_guard<std::mutex> lock(handle->mutex);
        try {
            if (!handle->model || !handle->qwen) {
                throw std::runtime_error("Qwen-Image-2.1 runtime is not initialized");
            }
            jmethodID onProgress = nullptr;
            if (listener != nullptr) {
                jclass listenerClass = env->GetObjectClass(listener);
                if (listenerClass != nullptr) {
                    onProgress = env->GetMethodID(listenerClass, "onProgress", "(I)V");
                    env->DeleteLocalRef(listenerClass);
                }
            }
            auto callback = [env, listener, onProgress](int progress) {
                if (onProgress != nullptr) env->CallVoidMethod(listener, onProgress, (jint)progress);
            };
            if (width > 0 && height > 0) handle->qwen->setImageSize(width, height);
            ok = handle->model->run(toString(env, prompt), toString(env, outputPng), steps, seed, 1.0f, callback,
                                    toString(env, inputImage));
            // Re-read MNN's resolved primary runtime after the native graph
            // completed. This catches an unavailable OpenCL runtime that was
            // transparently replaced by CPU before the result is published.
            // resolveEffectiveBackendType validates the populated output value
            // rather than getInfo's bool, which is false for BACKENDS in this
            // pinned RuntimeManager implementation.
            handle->backendResolved = handle->qwen->resolveEffectiveBackendType(&handle->effectiveBackend);
            if (!handle->backendResolved) handle->effectiveBackend = static_cast<MNNForwardType>(-1);
            handle->nativeRunCompleted = ok;
            handle->backendExecutionConfirmed = ok && handle->backendResolved &&
                handle->requestedBackend == handle->effectiveBackend;
            if (ok) ++handle->generationCount;
            handle->errorCode = ok ? QwenImage21Diffusion::kOk : handle->qwen->lastErrorCode();
            setBoundedError(handle->error, ok ? "" : handle->qwen->lastError().c_str());
            if (!ok) {
                if (handle->errorCode == QwenImage21Diffusion::kOk) {
                    handle->errorCode = QwenImage21Diffusion::kRuntimeError;
                }
                if (handle->error.empty()) setBoundedError(handle->error, "Generation failed");
            }
        } catch (const std::bad_alloc&) {
            setHandleError(*handle, QwenImage21Diffusion::kOutOfMemory,
                           "Out of memory during Qwen-Image-2.1 generation");
        } catch (const std::exception& e) {
            setHandleError(*handle, QwenImage21Diffusion::kRuntimeError, e.what());
        } catch (...) {
            setHandleError(*handle, QwenImage21Diffusion::kRuntimeError,
                           "Unknown native exception during Qwen-Image-2.1 generation");
        }
        LOGI("generate %s %s", ok ? "ok" : "failed",
             errorOrFallback(handle->error, ok ? "" : "Generation failed"));
        return handle->errorCode;
    } catch (const std::exception& e) {
        setBoundedError(g_lastBoundaryError, e.what());
        LOGE("generate boundary failed: %s",
             errorOrFallback(g_lastBoundaryError, "Native exception at JNI boundary"));
        return QwenImage21Diffusion::kRuntimeError;
    } catch (...) {
        setBoundedError(g_lastBoundaryError, "Unknown native exception at Qwen-Image-2.1 JNI boundary");
        LOGE("generate boundary failed: %s",
             errorOrFallback(g_lastBoundaryError, "Unknown native exception at JNI boundary"));
        return QwenImage21Diffusion::kRuntimeError;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeLastError(JNIEnv* env, jclass, jlong ptr) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    const char* error = !g_lastBoundaryError.empty()
                            ? g_lastBoundaryError.c_str()
                            : (handle ? handle->error.c_str() : "");
    return env->NewStringUTF(error ? error : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeExecutionAudit(JNIEnv* env, jclass, jlong ptr) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    if (!handle) {
        return env->NewStringUTF("{\"nativeRunCompleted\":false,\"backendExecutionConfirmedByNative\":false,\"error\":\"null_handle\"}");
    }
    try {
        std::lock_guard<std::mutex> lock(handle->mutex);
        const std::string audit = executionAuditJson(*handle);
        return env->NewStringUTF(audit.c_str());
    } catch (...) {
        return env->NewStringUTF("{\"nativeRunCompleted\":false,\"backendExecutionConfirmedByNative\":false,\"error\":\"audit_failed\"}");
    }
}

extern "C" JNIEXPORT jint JNICALL Java_com_scsonic_qwenimage21_QwenImage21_nativeAvailableMemoryMB(JNIEnv*, jclass) {
    g_lastBoundaryError.clear();
    try {
        return QwenImage21Diffusion::availableMemoryMB();
    } catch (const std::exception& e) {
        setBoundedError(g_lastBoundaryError, e.what());
    } catch (...) {
        setBoundedError(g_lastBoundaryError, "Unknown native exception while reading available memory");
    }
    return -1;
}

extern "C" JNIEXPORT void JNICALL Java_com_scsonic_qwenimage21_QwenImage21_nativeRelease(JNIEnv*, jclass, jlong ptr) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    if (!handle) return;
    g_lastBoundaryError.clear();
    try {
        {
            std::lock_guard<std::mutex> lock(handle->mutex);
            handle->model.reset();
        }
        delete handle;
    } catch (const std::exception& e) {
        setBoundedError(g_lastBoundaryError, e.what());
        LOGE("release failed: %s", errorOrFallback(g_lastBoundaryError, "Native exception while releasing runtime"));
    } catch (...) {
        setBoundedError(g_lastBoundaryError, "Unknown native exception while releasing runtime");
        LOGE("release failed: %s",
             errorOrFallback(g_lastBoundaryError, "Unknown native exception while releasing runtime"));
    }
}
