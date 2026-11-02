// Minimal Neuron compiled-network adapter for one ditc1c chunk.
//
// The vendor runtime is resolved with dlopen at execution time; this library
// links no vendor binary. Function signatures follow the public Neuron API
// reference captured in artifacts/.../neuron-api-signatures.txt. Handles are
// opaque; no struct layout is guessed. All execution is synchronous.

#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "McaNeuron"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
struct NeuronModel;
struct NeuronCompilation;
struct NeuronExecution;
struct NeuronOperandType;
struct NeuronDevice;
struct NeuronRuntimeVersion {
  uint32_t major;
  uint32_t minor;
  uint32_t patch;
};
}

namespace {

using RestoreFn = int (*)(NeuronModel**, NeuronCompilation**, const void*, size_t);
using PaddedSizeFn = int (*)(NeuronCompilation*, int32_t, size_t*);
using PaddedDimsFn = int (*)(NeuronCompilation*, int32_t, uint32_t*);
using CreateExecutionFn = int (*)(NeuronCompilation*, NeuronExecution**);
using SetInputFn = int (*)(NeuronExecution*, int32_t, const NeuronOperandType*, const void*, size_t);
using SetOutputFn = int (*)(NeuronExecution*, int32_t, const NeuronOperandType*, void*, size_t);
using ComputeFn = int (*)(NeuronExecution*);
using FreeExecutionFn = void (*)(NeuronExecution*);
using FreeCompilationFn = void (*)(NeuronCompilation*);
using FreeModelFn = void (*)(NeuronModel*);
using GetVersionFn = int (*)(NeuronRuntimeVersion*);
using GetDeviceCountFn = int (*)(uint32_t*);
using GetDeviceFn = int (*)(uint32_t, NeuronDevice**);
using GetDeviceNameFn = int (*)(const NeuronDevice*, const char**);

struct RuntimeApi {
  void* handle = nullptr;
  std::string libraryPath;
  RestoreFn restore = nullptr;
  PaddedSizeFn inputSize = nullptr;
  PaddedSizeFn outputSize = nullptr;
  PaddedDimsFn inputDims = nullptr;
  PaddedDimsFn outputDims = nullptr;
  CreateExecutionFn createExecution = nullptr;
  SetInputFn setInput = nullptr;
  SetOutputFn setOutput = nullptr;
  ComputeFn compute = nullptr;
  FreeExecutionFn freeExecution = nullptr;
  FreeCompilationFn freeCompilation = nullptr;
  FreeModelFn freeModel = nullptr;
  GetVersionFn getVersion = nullptr;
  GetDeviceCountFn getDeviceCount = nullptr;
  GetDeviceFn getDevice = nullptr;
  GetDeviceNameFn getDeviceName = nullptr;
};

struct State {
  std::mutex mutex;
  RuntimeApi api;
  NeuronModel* model = nullptr;
  NeuronCompilation* compilation = nullptr;
  NeuronExecution* execution = nullptr;
  int inputCount = 0;
  int outputCount = 0;
  std::vector<size_t> inputSizes;
  std::vector<size_t> outputSizes;
  std::string version;
  std::string libraryPath;
  void* dlaMap = MAP_FAILED;
  size_t dlaSize = 0;
  int dlaFd = -1;
  bool open = false;
  std::string lastError;
};

State g_state;

template <typename T>
bool loadSymbol(void* handle, const char* name, T& target, std::string& missing) {
  target = reinterpret_cast<T>(dlsym(handle, name));
  if (target == nullptr) {
    if (!missing.empty()) missing += ", ";
    missing += name;
    return false;
  }
  return true;
}

namespace {

void* openNeuronRuntime(const std::string& candidate) {
  return dlopen(candidate.c_str(), RTLD_NOW | RTLD_LOCAL);
}

std::vector<std::string> discoverNeuronRuntimePaths() {
  static const char* kDirs[] = {
      "/vendor/lib64", "/system/lib64", "/odm/lib64", "/product/lib64",
      "/vendor/lib", "/system/lib", "/odm/lib", "/product/lib",
  };
  std::vector<std::string> paths;
  for (const char* dir : kDirs) {
    DIR* directory = opendir(dir);
    if (directory == nullptr) continue;
    while (dirent* entry = readdir(directory)) {
      std::string name = entry->d_name;
      std::string lower = name;
      std::transform(lower.begin(), lower.end(), lower.begin(),
                     [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
      if (lower.find("neuron") == std::string::npos) continue;
      if (lower.size() < 3 || lower.compare(lower.size() - 3, 3, ".so") != 0) continue;
      paths.push_back(std::string(dir) + "/" + name);
    }
    closedir(directory);
  }
  return paths;
}

}  // namespace

bool loadRuntimeLocked() {
  if (g_state.api.restore != nullptr) return true;
  std::vector<std::string> candidates = {
      "libneuronusdk_adapter.9.mtk.so",
      "/vendor/lib64/libneuronusdk_adapter.9.mtk.so",
      "/system/lib64/libneuronusdk_adapter.9.mtk.so",
      "libneuronusdk_adapter.so",
      "libneuronusdk.so",
      "/vendor/lib64/libneuronusdk.so",
      "/vendor/lib/libneuronusdk.so",
      "libNeuronAdapter.so",
      "/vendor/lib64/libNeuronAdapter.so",
      "/vendor/lib/libNeuronAdapter.so",
      "/system/lib64/libneuronusdk.so",
      "/system/lib/libneuronusdk.so",
  };
  const std::vector<std::string> discoveredPaths = discoverNeuronRuntimePaths();
  for (const std::string& discovered : discoveredPaths) {
    if (std::find(candidates.begin(), candidates.end(), discovered) == candidates.end()) {
      candidates.push_back(discovered);
    }
  }
  std::string lastError = "no Neuron runtime candidate was loadable";
  for (const std::string& candidate : candidates) {
    void* handle = openNeuronRuntime(candidate);
    if (handle == nullptr) {
      const char* error = dlerror();
      if (error != nullptr) lastError = std::string(candidate) + ": " + error;
      continue;
    }
    RuntimeApi api;
    std::string missing;
    bool ok = true;
    ok &= loadSymbol(handle, "NeuronModel_restoreFromCompiledNetwork", api.restore, missing);
    ok &= loadSymbol(handle, "NeuronCompilation_getInputPaddedSize", api.inputSize, missing);
    ok &= loadSymbol(handle, "NeuronCompilation_getOutputPaddedSize", api.outputSize, missing);
    ok &= loadSymbol(handle, "NeuronExecution_create", api.createExecution, missing);
    ok &= loadSymbol(handle, "NeuronExecution_setInput", api.setInput, missing);
    ok &= loadSymbol(handle, "NeuronExecution_setOutput", api.setOutput, missing);
    ok &= loadSymbol(handle, "NeuronExecution_compute", api.compute, missing);
    ok &= loadSymbol(handle, "NeuronExecution_free", api.freeExecution, missing);
    ok &= loadSymbol(handle, "NeuronCompilation_free", api.freeCompilation, missing);
    ok &= loadSymbol(handle, "NeuronModel_free", api.freeModel, missing);
    if (!ok) {
      dlclose(handle);
      lastError = std::string(candidate) + ": missing " + missing;
      continue;
    }
    loadSymbol(handle, "NeuronCompilation_getInputPaddedDimensions", api.inputDims, missing);
    loadSymbol(handle, "NeuronCompilation_getOutputPaddedDimensions", api.outputDims, missing);
    loadSymbol(handle, "Neuron_getVersion", api.getVersion, missing);
    loadSymbol(handle, "Neuron_getDeviceCount", api.getDeviceCount, missing);
    loadSymbol(handle, "Neuron_getDevice", api.getDevice, missing);
    loadSymbol(handle, "NeuronDevice_getName", api.getDeviceName, missing);
    api.handle = handle;
    api.libraryPath = candidate;
    g_state.api = api;
    g_state.libraryPath = candidate;
    NeuronRuntimeVersion version{};
    if (api.getVersion != nullptr && api.getVersion(&version) == 0) {
      g_state.version = std::to_string(version.major) + "." + std::to_string(version.minor) +
          "." + std::to_string(version.patch);
    } else {
      g_state.version.clear();
    }
    LOGI("Neuron runtime loaded from %s (%s)", candidate.c_str(),
         g_state.version.empty() ? "version unavailable" : g_state.version.c_str());
    return true;
  }
  if (!discoveredPaths.empty()) {
    lastError += "; discovered=";
    for (size_t index = 0; index < discoveredPaths.size(); ++index) {
      if (index != 0) lastError += ",";
      lastError += discoveredPaths[index];
    }
  }
  g_state.lastError = lastError;
  LOGE("Neuron runtime load failed: %s", lastError.c_str());
  return false;
}

std::vector<std::string> deviceNamesLocked() {
  std::vector<std::string> names;
  const RuntimeApi& api = g_state.api;
  if (api.getDeviceCount == nullptr || api.getDevice == nullptr || api.getDeviceName == nullptr) {
    return names;
  }
  uint32_t count = 0;
  if (api.getDeviceCount(&count) != 0) return names;
  for (uint32_t index = 0; index < count; ++index) {
    NeuronDevice* device = nullptr;
    if (api.getDevice(index, &device) != 0 || device == nullptr) continue;
    const char* name = nullptr;
    if (api.getDeviceName(device, &name) == 0 && name != nullptr) names.emplace_back(name);
  }
  return names;
}

std::string jsonEscape(const std::string& value) {
  std::string result;
  result.reserve(value.size() + 8);
  for (char c : value) {
    switch (c) {
      case '"': result += "\\\""; break;
      case '\\': result += "\\\\"; break;
      case '\n': result += "\\n"; break;
      case '\r': result += "\\r"; break;
      case '\t': result += "\\t"; break;
      default: result += c; break;
    }
  }
  return result;
}

std::string jsonString(const std::string& value) { return "\"" + jsonEscape(value) + "\""; }

std::string jsonStringArray(const std::vector<std::string>& values) {
  std::string result = "[";
  for (size_t index = 0; index < values.size(); ++index) {
    if (index != 0) result += ",";
    result += jsonString(values[index]);
  }
  result += "]";
  return result;
}

std::string jsonNumberArray(const std::vector<size_t>& values) {
  std::string result = "[";
  for (size_t index = 0; index < values.size(); ++index) {
    if (index != 0) result += ",";
    result += std::to_string(values[index]);
  }
  result += "]";
  return result;
}

std::string jsonDimsArray(const std::vector<std::vector<uint32_t>>& values) {
  std::string result = "[";
  for (size_t index = 0; index < values.size(); ++index) {
    if (index != 0) result += ",";
    result += "[";
    for (size_t dim = 0; dim < values[index].size(); ++dim) {
      if (dim != 0) result += ",";
      result += std::to_string(values[index][dim]);
    }
    result += "]";
  }
  result += "]";
  return result;
}

std::string failureJson(const char* stage, int code, const std::string& message) {
  std::ostringstream out;
  out << "{\"status\":\"FAILED\",\"stage\":" << (stage == nullptr ? "\"\"" : jsonString(stage))
      << ",\"code\":" << code << ",\"message\":" << jsonString(message) << "}";
  return out.str();
}

void releaseOpenLocked() {
  if (g_state.execution != nullptr && g_state.api.freeExecution != nullptr) {
    g_state.api.freeExecution(g_state.execution);
  }
  if (g_state.compilation != nullptr && g_state.api.freeCompilation != nullptr) {
    g_state.api.freeCompilation(g_state.compilation);
  }
  if (g_state.model != nullptr && g_state.api.freeModel != nullptr) {
    g_state.api.freeModel(g_state.model);
  }
  if (g_state.dlaMap != MAP_FAILED) {
    munmap(g_state.dlaMap, g_state.dlaSize);
  }
  if (g_state.dlaFd >= 0) {
    close(g_state.dlaFd);
  }
  g_state.execution = nullptr;
  g_state.compilation = nullptr;
  g_state.model = nullptr;
  g_state.dlaMap = MAP_FAILED;
  g_state.dlaSize = 0;
  g_state.dlaFd = -1;
  g_state.inputCount = 0;
  g_state.outputCount = 0;
  g_state.inputSizes.clear();
  g_state.outputSizes.clear();
  g_state.open = false;
}

std::string toStdString(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars == nullptr ? "" : chars;
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return result;
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
  jclass clazz = env->FindClass("java/lang/IllegalStateException");
  if (clazz != nullptr) env->ThrowNew(clazz, message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_mediatek_MediaTekNeuronJniBridge_nativeProbe(JNIEnv* env, jobject) {
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!loadRuntimeLocked()) {
    std::ostringstream out;
    out << "{\"status\":\"BLOCKED\",\"libraryPath\":null,\"version\":null,\"devices\":[],"
        << "\"message\":" << jsonString(g_state.lastError) << "}";
    return env->NewStringUTF(out.str().c_str());
  }
  std::ostringstream out;
  out << "{\"status\":\"READY\",\"libraryPath\":" << jsonString(g_state.libraryPath)
      << ",\"version\":" << (g_state.version.empty() ? "null" : jsonString(g_state.version))
      << ",\"devices\":" << jsonStringArray(deviceNamesLocked())
      << ",\"message\":\"\"}";
  return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_mediatek_MediaTekNeuronJniBridge_nativeRestore(
    JNIEnv* env, jobject, jstring dlaPath, jint inputCount, jint outputCount, jintArray inputRanks) {
  const std::string path = toStdString(env, dlaPath);
  if (path.empty()) return env->NewStringUTF(failureJson("arguments", 0, "DLA path is empty").c_str());
  if (inputCount <= 0 || outputCount <= 0) {
    return env->NewStringUTF(failureJson("arguments", 0, "input/output count must be positive").c_str());
  }
  std::vector<int> ranks(static_cast<size_t>(inputCount), 0);
  if (inputRanks != nullptr) {
    const jsize length = env->GetArrayLength(inputRanks);
    if (length != inputCount) {
      return env->NewStringUTF(
          failureJson("arguments", 0, "inputRanks length does not match inputCount").c_str());
    }
    jint* values = env->GetIntArrayElements(inputRanks, nullptr);
    if (values != nullptr) {
      for (jsize index = 0; index < length; ++index) ranks[static_cast<size_t>(index)] = values[index];
      env->ReleaseIntArrayElements(inputRanks, values, JNI_ABORT);
    }
  }

  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (g_state.open) return env->NewStringUTF(
      failureJson("restore", 0, "an adapter instance is already open").c_str());
  if (!loadRuntimeLocked()) {
    return env->NewStringUTF(failureJson("load", 0, g_state.lastError).c_str());
  }
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    return env->NewStringUTF(
        failureJson("open", errno, "cannot open the DLA file: " + path).c_str());
  }
  struct stat statBuffer {};
  if (fstat(fd, &statBuffer) != 0 || statBuffer.st_size <= 0) {
    close(fd);
    return env->NewStringUTF(failureJson("stat", errno, "DLA file is empty or unreadable").c_str());
  }
  const size_t size = static_cast<size_t>(statBuffer.st_size);
  void* mapped = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
  if (mapped == MAP_FAILED) {
    close(fd);
    return env->NewStringUTF(failureJson("mmap", errno, "cannot map the DLA file").c_str());
  }

  NeuronModel* model = nullptr;
  NeuronCompilation* compilation = nullptr;
  const auto started = std::chrono::steady_clock::now();
  const int restoreCode = g_state.api.restore(&model, &compilation, mapped, size);
  const auto restored = std::chrono::steady_clock::now();
  if (restoreCode != 0 || compilation == nullptr) {
    if (compilation != nullptr) g_state.api.freeCompilation(compilation);
    if (model != nullptr) g_state.api.freeModel(model);
    munmap(mapped, size);
    close(fd);
    return env->NewStringUTF(
        failureJson("restore", restoreCode, "NeuronModel_restoreFromCompiledNetwork failed").c_str());
  }

  std::vector<size_t> inputSizes(static_cast<size_t>(inputCount), 0);
  std::vector<size_t> outputSizes(static_cast<size_t>(outputCount), 0);
  std::vector<std::vector<uint32_t>> inputDims(static_cast<size_t>(inputCount));
  std::vector<std::vector<uint32_t>> outputDims(static_cast<size_t>(outputCount));
  for (int index = 0; index < inputCount; ++index) {
    const int code = g_state.api.inputSize(compilation, index, &inputSizes[static_cast<size_t>(index)]);
    if (code != 0) {
      g_state.api.freeCompilation(compilation);
      g_state.api.freeModel(model);
      munmap(mapped, size);
      close(fd);
      return env->NewStringUTF(
          failureJson("inputPaddedSize", code, "cannot query input " + std::to_string(index)).c_str());
    }
    const int rank = ranks[static_cast<size_t>(index)];
    if (g_state.api.inputDims != nullptr && rank > 0) {
      inputDims[static_cast<size_t>(index)].assign(static_cast<size_t>(rank), 0);
      g_state.api.inputDims(compilation, index, inputDims[static_cast<size_t>(index)].data());
    }
  }
  for (int index = 0; index < outputCount; ++index) {
    const int code = g_state.api.outputSize(compilation, index, &outputSizes[static_cast<size_t>(index)]);
    if (code != 0) {
      g_state.api.freeCompilation(compilation);
      g_state.api.freeModel(model);
      munmap(mapped, size);
      close(fd);
      return env->NewStringUTF(
          failureJson("outputPaddedSize", code, "cannot query output " + std::to_string(index)).c_str());
    }
    if (g_state.api.outputDims != nullptr) {
      outputDims[static_cast<size_t>(index)].assign(8, 0);
      g_state.api.outputDims(compilation, index, outputDims[static_cast<size_t>(index)].data());
    }
  }

  NeuronExecution* execution = nullptr;
  const int executionCode = g_state.api.createExecution(compilation, &execution);
  if (executionCode != 0 || execution == nullptr) {
    g_state.api.freeCompilation(compilation);
    g_state.api.freeModel(model);
    munmap(mapped, size);
    close(fd);
    return env->NewStringUTF(
        failureJson("execution", executionCode, "NeuronExecution_create failed").c_str());
  }

  g_state.model = model;
  g_state.compilation = compilation;
  g_state.execution = execution;
  g_state.inputCount = inputCount;
  g_state.outputCount = outputCount;
  g_state.inputSizes = std::move(inputSizes);
  g_state.outputSizes = std::move(outputSizes);
  g_state.dlaMap = mapped;
  g_state.dlaSize = size;
  g_state.dlaFd = fd;
  g_state.open = true;
  g_state.lastError.clear();

  const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(restored - started).count();
  std::ostringstream out;
  out << "{\"status\":\"OPEN\",\"libraryPath\":" << jsonString(g_state.libraryPath)
      << ",\"version\":" << (g_state.version.empty() ? "null" : jsonString(g_state.version))
      << ",\"inputCount\":" << inputCount << ",\"outputCount\":" << outputCount
      << ",\"inputPaddedBytes\":" << jsonNumberArray(g_state.inputSizes)
      << ",\"outputPaddedBytes\":" << jsonNumberArray(g_state.outputSizes)
      << ",\"inputDims\":" << jsonDimsArray(inputDims)
      << ",\"outputDims\":" << jsonDimsArray(outputDims)
      << ",\"restoreElapsedMs\":" << elapsedMs << ",\"message\":\"\"}";
  LOGI("restored %s: %d inputs, %d outputs, %lld ms", path.c_str(), inputCount, outputCount,
       static_cast<long long>(elapsedMs));
  return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_muyuchat_core_mediatek_MediaTekNeuronJniBridge_nativeRun(
    JNIEnv* env, jobject, jobjectArray inputs) {
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open || g_state.execution == nullptr) {
    throwIllegalState(env, "Neuron adapter is not open");
    return nullptr;
  }
  if (inputs == nullptr || env->GetArrayLength(inputs) != g_state.inputCount) {
    throwIllegalState(env, "input count does not match the restored network");
    return nullptr;
  }
  const int count = g_state.inputCount;
  std::vector<jbyteArray> arrays(static_cast<size_t>(count), nullptr);
  for (int index = 0; index < count; ++index) {
    arrays[static_cast<size_t>(index)] =
        static_cast<jbyteArray>(env->GetObjectArrayElement(inputs, index));
    if (arrays[static_cast<size_t>(index)] == nullptr) {
      throwIllegalState(env, "input " + std::to_string(index) + " is null");
      return nullptr;
    }
    const jsize length = env->GetArrayLength(arrays[static_cast<size_t>(index)]);
    if (static_cast<size_t>(length) != g_state.inputSizes[static_cast<size_t>(index)]) {
      const std::string message = "input " + std::to_string(index) + " has " +
          std::to_string(length) + " bytes; runtime expects " +
          std::to_string(g_state.inputSizes[static_cast<size_t>(index)]);
      throwIllegalState(env, message);
      return nullptr;
    }
  }
  std::vector<jbyte*> pointers(static_cast<size_t>(count), nullptr);
  for (int index = 0; index < count; ++index) {
    pointers[static_cast<size_t>(index)] =
        env->GetByteArrayElements(arrays[static_cast<size_t>(index)], nullptr);
    if (pointers[static_cast<size_t>(index)] == nullptr) {
      for (int previous = 0; previous < index; ++previous) {
        env->ReleaseByteArrayElements(arrays[static_cast<size_t>(previous)],
                                      pointers[static_cast<size_t>(previous)], JNI_ABORT);
      }
      throwIllegalState(env, "cannot access input " + std::to_string(index));
      return nullptr;
    }
  }
  auto releaseInputs = [&]() {
    for (int index = 0; index < count; ++index) {
      if (arrays[static_cast<size_t>(index)] != nullptr &&
          pointers[static_cast<size_t>(index)] != nullptr) {
        env->ReleaseByteArrayElements(arrays[static_cast<size_t>(index)],
                                      pointers[static_cast<size_t>(index)], JNI_ABORT);
        pointers[static_cast<size_t>(index)] = nullptr;
      }
    }
  };
  std::vector<std::vector<uint8_t>> outputs(static_cast<size_t>(g_state.outputCount));
  for (int index = 0; index < g_state.outputCount; ++index) {
    outputs[static_cast<size_t>(index)].assign(g_state.outputSizes[static_cast<size_t>(index)], 0);
  }
  for (int index = 0; index < count; ++index) {
    const int code = g_state.api.setInput(g_state.execution, index, nullptr,
                                          pointers[static_cast<size_t>(index)],
                                          g_state.inputSizes[static_cast<size_t>(index)]);
    if (code != 0) {
      releaseInputs();
      throwIllegalState(env, "NeuronExecution_setInput failed for input " +
          std::to_string(index) + " (code " + std::to_string(code) + ")");
      return nullptr;
    }
  }
  for (int index = 0; index < g_state.outputCount; ++index) {
    const int code = g_state.api.setOutput(g_state.execution, index, nullptr,
                                           outputs[static_cast<size_t>(index)].data(),
                                           outputs[static_cast<size_t>(index)].size());
    if (code != 0) {
      releaseInputs();
      throwIllegalState(env, "NeuronExecution_setOutput failed for output " +
          std::to_string(index) + " (code " + std::to_string(code) + ")");
      return nullptr;
    }
  }
  const auto started = std::chrono::steady_clock::now();
  const int computeCode = g_state.api.compute(g_state.execution);
  const auto finished = std::chrono::steady_clock::now();
  releaseInputs();
  if (computeCode != 0) {
    throwIllegalState(env, "NeuronExecution_compute failed (code " + std::to_string(computeCode) + ")");
    return nullptr;
  }
  LOGI("Neuron compute finished in %lld ms",
       static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(finished - started).count()));

  jclass byteArrayClass = env->FindClass("[B");
  if (byteArrayClass == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(g_state.outputCount, byteArrayClass, nullptr);
  if (result == nullptr) return nullptr;
  for (int index = 0; index < g_state.outputCount; ++index) {
    const auto& output = outputs[static_cast<size_t>(index)];
    jbyteArray array = env->NewByteArray(static_cast<jsize>(output.size()));
    if (array == nullptr) return nullptr;
    if (!output.empty()) {
      env->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()),
                              reinterpret_cast<const jbyte*>(output.data()));
    }
    env->SetObjectArrayElement(result, index, array);
    env->DeleteLocalRef(array);
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_mediatek_MediaTekNeuronJniBridge_nativeClose(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_state.mutex);
  releaseOpenLocked();
  LOGI("Neuron adapter closed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_mediatek_MediaTekNeuronJniBridge_nativeLastError(JNIEnv* env, jobject) {
  std::lock_guard<std::mutex> lock(g_state.mutex);
  return env->NewStringUTF(g_state.lastError.c_str());
}
