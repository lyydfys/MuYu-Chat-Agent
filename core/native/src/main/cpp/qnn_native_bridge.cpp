#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdarg>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iterator>
#include <limits>
#include <mutex>
#include <new>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#include "jni_utf8_codec.hpp"
#include "qnn_image_stage_trace.hpp"

#if MCA_WITH_QNN_SDK_HEADERS
#include <QNN/QnnBackend.h>
#include <QNN/QnnContext.h>
#include <QNN/QnnDevice.h>
#include <QNN/QnnGraph.h>
#include <QNN/HTP/QnnHtpDevice.h>
#include <QNN/QnnInterface.h>
#include <QNN/QnnTensor.h>
#include <QNN/System/QnnSystemInterface.h>
#endif

namespace {

constexpr const char* kTag = "MCA-QNN";

enum QnnImageGenerationPhase {
    kQnnImageIdle = 0,
    kQnnImageLoading = 1,
    kQnnImageContextLock = 2,
    kQnnImageContextBinaryMmap = 3,
    kQnnImageContextCreate = 4,
    kQnnImageGraphExecute = 5,
    kQnnImageSampling = 6,
    kQnnImageDecoding = 7,
    kQnnImagePngWrite = 8,
    kQnnImageContextRelease = 9,
    kQnnImageCancelling = 10
};

std::atomic<bool> g_qnn_image_generation_active{false};
std::atomic<bool> g_qnn_image_generation_cancel_requested{false};
std::atomic<int> g_qnn_image_generation_phase{kQnnImageIdle};
std::atomic<int> g_qnn_image_generation_step{0};
std::atomic<int> g_qnn_image_generation_steps{0};
std::atomic<long long> g_qnn_image_generation_started_ms{0};
std::atomic<long long> g_qnn_image_generation_sequence{0};
std::atomic<uint32_t> g_qnn_image_generation_stage_mask{0};
std::atomic<uint64_t> g_qnn_image_generation_detail_stage_mask{0};
std::timed_mutex g_qnn_context_execution_mutex;
std::mutex g_qnn_image_journal_mutex;
std::string g_qnn_image_journal_path;

std::string qnn_image_generation_progress_json();

void persist_qnn_image_generation_journal() {
    std::lock_guard<std::mutex> lock(g_qnn_image_journal_mutex);
    if (g_qnn_image_journal_path.empty()) return;
    const std::string temporary = g_qnn_image_journal_path + ".tmp";
    const std::string payload = qnn_image_generation_progress_json();
    const int fd = ::open(
        temporary.c_str(),
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
        0600
    );
    if (fd < 0) return;
    const char* cursor = payload.data();
    size_t remaining = payload.size();
    bool complete = true;
    while (remaining > 0) {
        const ssize_t written = ::write(fd, cursor, remaining);
        if (written <= 0) {
            complete = false;
            break;
        }
        cursor += written;
        remaining -= static_cast<size_t>(written);
    }
    if (complete) ::fsync(fd);
    ::close(fd);
    if (complete) {
        ::rename(temporary.c_str(), g_qnn_image_journal_path.c_str());
    } else {
        ::unlink(temporary.c_str());
    }
}

long long monotonic_millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

const char* qnn_image_generation_phase_name(int phase) {
    switch (phase) {
        case kQnnImageLoading: return "loading";
        case kQnnImageContextLock: return "context_lock";
        case kQnnImageContextBinaryMmap: return "context_binary_mmap";
        case kQnnImageContextCreate: return "context_create";
        case kQnnImageGraphExecute: return "graph_execute";
        case kQnnImageSampling: return "sampling";
        case kQnnImageDecoding: return "decoding";
        case kQnnImagePngWrite: return "png_write";
        case kQnnImageContextRelease: return "context_release";
        case kQnnImageCancelling: return "cancelling";
        default: return "idle";
    }
}

uint32_t qnn_image_generation_stage_bit(QnnImageGenerationPhase phase) {
    return phase <= kQnnImageIdle
        ? 0U
        : (1U << static_cast<uint32_t>(phase));
}

void qnn_image_generation_set_phase(QnnImageGenerationPhase phase) {
    g_qnn_image_generation_phase.store(phase);
    const uint32_t bit = qnn_image_generation_stage_bit(phase);
    if (bit != 0U) g_qnn_image_generation_stage_mask.fetch_or(bit);
    persist_qnn_image_generation_journal();
}

void qnn_image_generation_record_stage(
        mca::qnn::ImageStage stage,
        QnnImageGenerationPhase phase) {
    g_qnn_image_generation_phase.store(phase);
    const uint32_t coarse_bit = qnn_image_generation_stage_bit(phase);
    if (coarse_bit != 0U) g_qnn_image_generation_stage_mask.fetch_or(coarse_bit);
    g_qnn_image_generation_detail_stage_mask.fetch_or(mca::qnn::image_stage_bit(stage));
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "QNN image stage: %s",
        mca::qnn::image_stage_name(stage));
    persist_qnn_image_generation_journal();
}

bool qnn_image_generation_try_begin(int steps, const std::string& journal_path) {
    bool expected = false;
    if (!g_qnn_image_generation_active.compare_exchange_strong(expected, true)) {
        return false;
    }
    g_qnn_image_generation_cancel_requested.store(false);
    g_qnn_image_generation_stage_mask.store(0U);
    g_qnn_image_generation_detail_stage_mask.store(0U);
    {
        std::lock_guard<std::mutex> lock(g_qnn_image_journal_mutex);
        g_qnn_image_journal_path = journal_path;
    }
    g_qnn_image_generation_phase.store(kQnnImageLoading);
    g_qnn_image_generation_stage_mask.fetch_or(qnn_image_generation_stage_bit(kQnnImageLoading));
    g_qnn_image_generation_step.store(0);
    g_qnn_image_generation_steps.store(std::max(1, steps));
    g_qnn_image_generation_started_ms.store(monotonic_millis());
    g_qnn_image_generation_sequence.fetch_add(1);
    persist_qnn_image_generation_journal();
    return true;
}

void qnn_image_generation_end() {
    g_qnn_image_generation_active.store(false);
    g_qnn_image_generation_cancel_requested.store(false);
    persist_qnn_image_generation_journal();
    g_qnn_image_generation_phase.store(kQnnImageIdle);
    g_qnn_image_generation_step.store(0);
    g_qnn_image_generation_steps.store(0);
    g_qnn_image_generation_started_ms.store(0);
}

bool qnn_image_generation_cancelled() {
    return g_qnn_image_generation_cancel_requested.load();
}

class QnnImageGenerationScope {
public:
    QnnImageGenerationScope(int steps, const std::string& journal_path)
        : active_(qnn_image_generation_try_begin(steps, journal_path)) {}
    ~QnnImageGenerationScope() {
        if (active_) qnn_image_generation_end();
    }

    bool active() const { return active_; }
    bool cancelled() const { return qnn_image_generation_cancelled(); }
    void set_phase(QnnImageGenerationPhase phase) const {
        if (active_) qnn_image_generation_set_phase(phase);
    }
    void record_stage(mca::qnn::ImageStage stage, QnnImageGenerationPhase phase) const {
        if (active_) qnn_image_generation_record_stage(stage, phase);
    }
    void set_steps(int steps) const {
        if (active_) g_qnn_image_generation_steps.store(std::max(1, steps));
    }
    void set_step(int step) const {
        if (active_) g_qnn_image_generation_step.store(std::max(0, step));
    }

private:
    bool active_ = false;
};

class ScopedDlHandle {
public:
    explicit ScopedDlHandle(void* handle = nullptr) : handle_(handle) {}
    ScopedDlHandle(const ScopedDlHandle&) = delete;
    ScopedDlHandle& operator=(const ScopedDlHandle&) = delete;
    ~ScopedDlHandle() {
        if (handle_ != nullptr) dlclose(handle_);
    }

    void* get() const { return handle_; }
    explicit operator bool() const { return handle_ != nullptr; }

private:
    void* handle_ = nullptr;
};

class ScopedReadOnlyMmap {
public:
    ScopedReadOnlyMmap() = default;
    ScopedReadOnlyMmap(const ScopedReadOnlyMmap&) = delete;
    ScopedReadOnlyMmap& operator=(const ScopedReadOnlyMmap&) = delete;
    ~ScopedReadOnlyMmap() { close(); }

    bool open_file(const std::string& path, std::string* error) {
        close();
        const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            *error = "Unable to open QNN context binary for mmap: " + path;
            return false;
        }
        struct stat st {};
        if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
            ::close(fd);
            *error = "QNN context binary is missing or empty: " + path;
            return false;
        }
        if (static_cast<uint64_t>(st.st_size) >
            static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
            ::close(fd);
            *error = "QNN context binary is too large to map: " + path;
            return false;
        }
        // QNN's legacy binary-info entry point accepts a mutable pointer even
        // though the context blob is logically input-only.  A private writable
        // mapping preserves compatibility without modifying the bundle file.
        void* mapped = mmap(
            nullptr,
            static_cast<size_t>(st.st_size),
            PROT_READ | PROT_WRITE,
            MAP_PRIVATE,
            fd,
            0
        );
        ::close(fd);
        if (mapped == MAP_FAILED) {
            *error = "Unable to mmap QNN context binary: " + path;
            return false;
        }
        data_ = mapped;
        size_ = static_cast<size_t>(st.st_size);
        return true;
    }

    void close() {
        if (data_ != MAP_FAILED) {
            munmap(data_, size_);
            data_ = MAP_FAILED;
            size_ = 0;
        }
    }

    const uint8_t* data() const {
        return data_ == MAP_FAILED ? nullptr : static_cast<const uint8_t*>(data_);
    }
    size_t size() const { return size_; }
    bool empty() const { return data() == nullptr || size_ == 0; }

private:
    void* data_ = MAP_FAILED;
    size_t size_ = 0;
};

std::string compile_capability_json() {
    std::ostringstream out;
    out << "{"
        << "\"sdkRootConfigured\":" << (MCA_QNN_SDK_ROOT_CONFIGURED ? "true" : "false") << ","
        << "\"sdkHeadersPresent\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
        << "\"typedGraphBindingsCompiled\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false")
        << "}";
    return out.str();
}

std::string jstring_to_std(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    std::string out;
    try {
        out = mca::utf8::encode_from_utf16(
            reinterpret_cast<const uint16_t*>(chars),
            static_cast<size_t>(length)
        );
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

jstring std_to_jstring(JNIEnv* env, const std::string& value) {
    const auto decoded = mca::utf8::decode_to_utf16(value, false);
    if (decoded.utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("QNN native UTF-8 output is too large for a Java string.");
    }
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    static constexpr jchar kEmptyStringData = 0;
    const auto* data = decoded.utf16.empty()
        ? &kEmptyStringData
        : reinterpret_cast<const jchar*>(decoded.utf16.data());
    return env->NewString(data, static_cast<jsize>(decoded.utf16.size()));
}

std::string json_escape(const std::string& value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u00";
                    const char* hex = "0123456789abcdef";
                    out << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string quote(const std::string& value) {
    return "\"" + json_escape(value) + "\"";
}

jstring qnn_jni_error_json(
        JNIEnv* env,
        const char* operation,
        const char* execution_stage,
        const char* message) noexcept {
    if (env == nullptr) return nullptr;
    try {
        std::ostringstream out;
        out << "{"
            << "\"ok\":false,"
            << "\"backend\":\"qnn_htp\","
            << "\"executionStage\":" << quote(execution_stage == nullptr ? "native_exception" : execution_stage) << ","
            << "\"operation\":" << quote(operation == nullptr ? "unknown" : operation) << ","
            << "\"message\":" << quote(message == nullptr ? "QNN native bridge failed." : message)
            << "}";
        return std_to_jstring(env, out.str());
    } catch (...) {
        static constexpr char16_t kFallback[] =
            u"{\"ok\":false,\"backend\":\"qnn_htp\",\"executionStage\":\"native_exception\","
            u"\"message\":\"QNN native bridge failed before returning a result.\"}";
        static_assert(sizeof(char16_t) == sizeof(jchar), "JNI jchar must be a UTF-16 code unit");
        return env->NewString(
            reinterpret_cast<const jchar*>(kFallback),
            static_cast<jsize>((sizeof(kFallback) / sizeof(kFallback[0])) - 1)
        );
    }
}

template <typename Callback>
jstring qnn_jni_json_guard(JNIEnv* env, const char* operation, Callback&& callback) noexcept {
    try {
        return std_to_jstring(env, callback());
    } catch (const std::bad_alloc&) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: native out of memory", operation);
        return qnn_jni_error_json(
            env,
            operation,
            "native_out_of_memory",
            "QNN native execution ran out of memory."
        );
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: %s", operation, error.what());
        return qnn_jni_error_json(env, operation, "native_exception", error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: unknown native exception", operation);
        return qnn_jni_error_json(
            env,
            operation,
            "native_exception",
            "QNN native execution failed with an unknown exception."
        );
    }
}

template <typename Callback>
jboolean qnn_jni_boolean_guard(const char* operation, Callback&& callback) noexcept {
    try {
        return callback() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc&) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: native out of memory", operation);
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: %s", operation, error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s failed: unknown native exception", operation);
    }
    return JNI_FALSE;
}

std::string qnn_image_generation_progress_json() {
    const bool active = g_qnn_image_generation_active.load();
    const bool cancel_requested = g_qnn_image_generation_cancel_requested.load();
    const int phase = g_qnn_image_generation_phase.load();
    const int step = g_qnn_image_generation_step.load();
    const int steps = g_qnn_image_generation_steps.load();
    const long long started_ms = g_qnn_image_generation_started_ms.load();
    const uint32_t stage_mask = g_qnn_image_generation_stage_mask.load();
    const uint64_t detail_stage_mask = g_qnn_image_generation_detail_stage_mask.load();
    const long long elapsed_ms = active && started_ms > 0
        ? std::max(0LL, monotonic_millis() - started_ms)
        : 0LL;
    std::string message;
    if (cancel_requested) {
        message = "Stopping QNN image generation after the current graph execution.";
    } else {
        switch (phase) {
            case kQnnImageLoading:
                message = "Loading QNN image graphs on Snapdragon NPU.";
                break;
            case kQnnImageContextLock:
                message = "Waiting for the QNN context execution lock.";
                break;
            case kQnnImageContextBinaryMmap:
                message = "Memory-mapping the QNN context binary.";
                break;
            case kQnnImageContextCreate:
                message = "Creating the QNN context from the mapped binary.";
                break;
            case kQnnImageGraphExecute:
                message = "Executing a QNN graph on Snapdragon NPU.";
                break;
            case kQnnImageSampling:
                message = "Running QNN diffusion sampling on Snapdragon NPU.";
                break;
            case kQnnImageDecoding:
                message = "Decoding the QNN VAE output.";
                break;
            case kQnnImagePngWrite:
                message = "Writing the generated image.";
                break;
            case kQnnImageContextRelease:
                message = "Releasing QNN contexts, mappings, and runtime handles.";
                break;
            default:
                message = "QNN image generation is idle.";
                break;
        }
    }
    std::ostringstream out;
    out << "{"
        << "\"active\":" << (active ? "true" : "false") << ","
        << "\"phase\":" << quote(qnn_image_generation_phase_name(phase)) << ","
        << "\"message\":" << quote(message) << ","
        << "\"step\":" << step << ","
        << "\"steps\":" << steps << ","
        << "\"elapsedMs\":" << elapsed_ms << ","
        << "\"cancelRequested\":" << (cancel_requested ? "true" : "false") << ","
        << "\"stageTrace\":[";
    bool first_stage = true;
    if (detail_stage_mask != 0U) {
        for (const auto& name : mca::qnn::image_stage_names(detail_stage_mask)) {
            if (!first_stage) out << ",";
            first_stage = false;
            out << quote(name);
        }
    } else {
        const QnnImageGenerationPhase ordered_stages[] = {
            kQnnImageContextLock,
            kQnnImageContextBinaryMmap,
            kQnnImageContextCreate,
            kQnnImageGraphExecute,
            kQnnImageSampling,
            kQnnImageDecoding,
            kQnnImagePngWrite,
            kQnnImageContextRelease
        };
        for (QnnImageGenerationPhase stage : ordered_stages) {
            if ((stage_mask & qnn_image_generation_stage_bit(stage)) == 0U) continue;
            if (!first_stage) out << ",";
            first_stage = false;
            out << quote(qnn_image_generation_phase_name(stage));
        }
    }
    out << "]"
        << "}";
    return out.str();
}

std::string qnn_image_generation_cancelled_json() {
    return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"cancelled\":true,\"executionStage\":\"cancelled\",\"message\":\"QNN image generation was cancelled.\"}";
}

bool exists_file(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

long long file_size_or_zero(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return 0;
    return static_cast<long long>(st.st_size);
}

bool exists_dir(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::string join_path(const std::string& dir, const std::string& name) {
    if (dir.empty()) return name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

std::vector<std::string> parse_json_string_array(const std::string& json) {
    std::vector<std::string> values;
    std::string current;
    bool in_string = false;
    bool escape = false;
    for (char c : json) {
        if (!in_string) {
            if (c == '"') {
                in_string = true;
                current.clear();
            }
            continue;
        }
        if (escape) {
            switch (c) {
                case 'n': current.push_back('\n'); break;
                case 'r': current.push_back('\r'); break;
                case 't': current.push_back('\t'); break;
                default: current.push_back(c); break;
            }
            escape = false;
            continue;
        }
        if (c == '\\') {
            escape = true;
            continue;
        }
        if (c == '"') {
            in_string = false;
            if (!current.empty()) values.push_back(current);
            continue;
        }
        current.push_back(c);
    }
    return values;
}

std::string files_json(const std::vector<std::string>& values) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ",";
        out << quote(values[i]);
    }
    out << "]";
    return out.str();
}

std::vector<std::string> list_files_recursive(const std::string& root, int depth = 0) {
    std::vector<std::string> files;
    if (root.empty() || depth > 8) return files;
    DIR* dir = opendir(root.c_str());
    if (dir == nullptr) return files;
    while (dirent* entry = readdir(dir)) {
        std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        const std::string path = join_path(root, name);
        struct stat st {};
        if (stat(path.c_str(), &st) != 0) continue;
        if (S_ISREG(st.st_mode)) {
            files.push_back(path);
        } else if (S_ISDIR(st.st_mode)) {
            auto child = list_files_recursive(path, depth + 1);
            files.insert(files.end(), child.begin(), child.end());
        }
    }
    closedir(dir);
    return files;
}

bool ends_with(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
        value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool contains_lower(std::string value, const std::string& token) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value.find(token) != std::string::npos;
}

bool is_safe_bundle_relative_path(const std::string& value) {
    if (value.empty()) return false;
    if (value[0] == '/' || value[0] == '\\') return false;
    if (value.size() >= 2 && std::isalpha(static_cast<unsigned char>(value[0])) && value[1] == ':') {
        return false;
    }
    std::string segment;
    for (char c : value) {
        const bool separator = c == '/' || c == '\\';
        if (separator) {
            if (segment.empty() || segment == "." || segment == "..") return false;
            segment.clear();
            continue;
        }
        segment.push_back(c);
    }
    return !segment.empty() && segment != "." && segment != "..";
}

struct RuntimeProbe {
    bool system_present = false;
    bool htp_present = false;
    bool skel_present = false;
    bool stub_present = false;
    bool rpc_present = false;
    bool rpc_loadable = false;
    bool loadable = false;
    bool qnn_interface_present = false;
    bool qnn_system_interface_present = false;
    std::string system_path;
    std::string htp_path;
    std::string skel_path;
    std::string stub_path;
    std::string rpc_path;
    std::string rpc_message;
    std::string adsp_library_path;
    std::string runtime_directory;
    std::string dsp_runtime_directory;
    int htp_arch_version = 0;
    std::vector<std::string> search_directories;
    std::string message;
};

int htp_library_version(const std::string& name) {
    const std::string marker = "libQnnHtpV";
    const auto start = name.find(marker);
    if (start == std::string::npos) return -1;
    size_t cursor = start + marker.size();
    std::string digits;
    while (cursor < name.size() && std::isdigit(static_cast<unsigned char>(name[cursor]))) {
        digits.push_back(name[cursor]);
        ++cursor;
    }
    if (digits.empty()) return -1;
    return std::atoi(digits.c_str());
}

// Values are QAIRT QNN_SOC_MODEL_* identifiers carried by context metadata.
// Keep this numeric helper outside the optional QNN-header build block so the
// runtime inspector can report a missing exact profile before graph execution.
int htp_arch_version_for_soc_model(uint32_t soc_model) {
    switch (soc_model) {
        case 30: return 68;  // SM8350 / Snapdragon 888
        case 36:             // SM8450 / Snapdragon 8 Gen 1
        case 42: return 69;  // SM8475 / Snapdragon 8+ Gen 1
        case 43: return 73;  // SM8550 / Snapdragon 8 Gen 2
        case 57: return 75;  // SM8650 / Snapdragon 8 Gen 3
        case 69: return 79;  // SM8750 / Snapdragon 8 Elite
        case 87: return 81;  // SM8850 / Snapdragon 8 Elite Gen 5
        default: return 0;
    }
}

int physical_device_htp_arch_version() {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("ro.soc.model", value) <= 0) return 0;
    const std::string model(value);
    if (contains_lower(model, "sm8850")) return 81;
    if (contains_lower(model, "sm8750")) return 79;
    if (contains_lower(model, "sm8650")) return 75;
    if (contains_lower(model, "sm8550")) return 73;
    if (contains_lower(model, "sm8475") || contains_lower(model, "sm8450")) return 69;
    if (contains_lower(model, "sm8350")) return 68;
    return 0;
}

std::string htp_library_for_arch_in_dir(
        const std::string& dir,
        int arch_version,
        const std::string& suffix) {
    if (arch_version <= 0) return "";
    const std::string path = join_path(
        dir,
        "libQnnHtpV" + std::to_string(arch_version) + suffix
    );
    return exists_file(path) ? path : "";
}

bool is_platform_qnn_runtime_directory(const std::string& directory) {
    return directory.rfind("/vendor/", 0) == 0 ||
        directory.rfind("/odm/", 0) == 0 ||
        directory.rfind("/system/", 0) == 0 ||
        directory.rfind("/system_ext/", 0) == 0 ||
        directory.rfind("/product/", 0) == 0;
}

bool may_pair_qnn_runtime_directories(
        const std::string& host_directory,
        const std::string& dsp_directory) {
    if (host_directory == dsp_directory) return true;
    // OEM Android images commonly keep libQnnSystem/libQnnHtp under /vendor
    // and the matching Skel/Stub under RFSA. Permit that *only* within the
    // platform image; never mix a side-loaded/app QAIRT host with OEM DSP libs.
    return is_platform_qnn_runtime_directory(host_directory) &&
        is_platform_qnn_runtime_directory(dsp_directory);
}

std::string best_htp_library_in_dir(const std::string& dir, const std::string& suffix) {
    DIR* handle = opendir(dir.c_str());
    if (handle == nullptr) return "";
    int best_version = -1;
    std::string best_path;
    while (dirent* entry = readdir(handle)) {
        std::string name(entry->d_name);
        if (name.find("libQnnHtpV") != 0 || !ends_with(name, suffix)) continue;
        const int version = htp_library_version(name);
        if (version > best_version) {
            best_version = version;
            best_path = join_path(dir, name);
        }
    }
    closedir(handle);
    return best_path;
}

void append_unique_path(std::vector<std::string>* values, const std::string& value) {
    if (value.empty()) return;
    if (std::find(values->begin(), values->end(), value) == values->end()) {
        values->push_back(value);
    }
}

std::string join_adsp_paths(const std::vector<std::string>& values) {
    std::ostringstream out;
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ";";
        out << values[i];
    }
    return out.str();
}

std::vector<std::string> prioritize_adsp_paths(const std::vector<std::string>& values) {
    std::vector<std::string> prioritized;
    for (const auto& value : values) {
        append_unique_path(&prioritized, value);
    }
    return prioritized;
}

void append_common_android_adsp_paths(std::vector<std::string>* values) {
    // Qualcomm Android samples include the app's extracted native lib directory
    // and common RFSA/DSP directories so HTP skel libraries can be discovered by
    // the device-side loader. Keep these as fallbacks; missing directories are
    // harmless and still useful in diagnostics.
    append_unique_path(values, "/system/lib/rfsa/adsp");
    append_unique_path(values, "/system/vendor/lib/rfsa/adsp");
    append_unique_path(values, "/vendor/lib/rfsa/adsp");
    append_unique_path(values, "/vendor/dsp");
    append_unique_path(values, "/vendor/dsp/cdsp");
    append_unique_path(values, "/dsp");
}

RuntimeProbe inspect_runtime_internal(
        const std::vector<std::string>& dirs,
        bool probe,
        bool probe_htp = true) {
    RuntimeProbe result;
    result.search_directories = dirs;
    std::vector<std::string> adsp_paths;
    struct RuntimeDirectoryCandidate {
        std::string directory;
        std::string system;
        std::string htp;
        std::string rpc;
        std::string skel;
        std::string stub;

        bool has_host() const {
            return exists_file(system) && exists_file(htp);
        }

        bool has_dsp() const {
            return !skel.empty();
        }
    };
    std::vector<RuntimeDirectoryCandidate> candidates;
    for (const auto& dir : dirs) {
        if (dir.empty()) continue;
        RuntimeDirectoryCandidate candidate;
        candidate.directory = dir;
        candidate.system = join_path(dir, "libQnnSystem.so");
        candidate.htp = join_path(dir, "libQnnHtp.so");
        candidate.rpc = join_path(dir, "libcdsprpc.so");
        candidate.skel = best_htp_library_in_dir(dir, "Skel.so");
        candidate.stub = best_htp_library_in_dir(dir, "Stub.so");
        if (candidate.has_dsp()) {
            append_unique_path(&adsp_paths, dir);
        }
        if (contains_lower(dir, "rfsa") || contains_lower(dir, "adsp") || contains_lower(dir, "dsp")) {
            append_unique_path(&adsp_paths, dir);
        }
        candidates.push_back(std::move(candidate));
    }

    auto select_runtime = [&](const RuntimeDirectoryCandidate& host, const RuntimeDirectoryCandidate& dsp) {
        result.system_present = true;
        result.htp_present = true;
        result.skel_present = true;
        result.stub_present = !dsp.stub.empty();
        result.rpc_present = exists_file(host.rpc);
        result.system_path = host.system;
        result.htp_path = host.htp;
        result.skel_path = dsp.skel;
        result.stub_path = dsp.stub;
        result.rpc_path = result.rpc_present ? host.rpc : "";
        result.dsp_runtime_directory = dsp.directory;
        result.runtime_directory = host.directory == dsp.directory
            ? host.directory
            : "host=" + host.directory + ";dsp=" + dsp.directory;
    };

    // Prefer a self-contained side-loaded/profile bundle. If the OEM runtime
    // is split between host and RFSA directories, pair only two platform-image
    // directories and retain both origins in diagnostics.
    for (const auto& candidate : candidates) {
        if (candidate.has_host() && candidate.has_dsp()) {
            select_runtime(candidate, candidate);
            break;
        }
    }
    if (!result.system_present) {
        for (const auto& host : candidates) {
            if (!host.has_host()) continue;
            for (const auto& dsp : candidates) {
                if (!dsp.has_dsp() || !may_pair_qnn_runtime_directories(host.directory, dsp.directory)) continue;
                select_runtime(host, dsp);
                break;
            }
            if (result.system_present) break;
        }
    }
    std::vector<std::string> prioritized_adsp_paths;
    append_unique_path(&prioritized_adsp_paths, result.dsp_runtime_directory);
    for (const auto& path : adsp_paths) {
        append_unique_path(&prioritized_adsp_paths, path);
    }
    append_common_android_adsp_paths(&prioritized_adsp_paths);

    if (!result.system_present || !result.htp_present || !result.skel_present) {
        result.message = "Missing a coherent QNN runtime profile: libQnnSystem.so/libQnnHtp.so and libQnnHtpVxxSkel.so were not found in one bundle or compatible OEM runtime directories.";
        return result;
    }

    if (!probe) {
        result.message = "QNN runtime files found; native load probe not requested.";
        return result;
    }

    // A process can carry several QNN HTP profiles (for example V73 for a
    // Snapdragon 8 Gen 2 context and V79/V81 for newer QAIRT contexts). Do
    // not initialise the generic highest HTP profile before the context
    // metadata chooses its exact profile: QNN retains that selection in the
    // process and later loads can then report incompatible binaries. Loading
    // libQnnSystem alone is sufficient for safe context metadata inspection.
    if (!probe_htp) {
        ScopedDlHandle system_handle(dlopen(result.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
        if (!system_handle) {
            const char* error = dlerror();
            result.loadable = false;
            result.message = std::string("dlopen libQnnSystem.so failed: ") +
                (error ? error : "unknown error");
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", result.message.c_str());
            return result;
        }
        result.qnn_system_interface_present =
            dlsym(system_handle.get(), "QnnSystemInterface_getProviders") != nullptr;
        result.loadable = true;
        result.message = result.qnn_system_interface_present
            ? "QNN System metadata preflight loaded; HTP load is deferred until the context selects an exact runtime profile."
            : "QNN System library loaded, but QnnSystemInterface_getProviders was not found.";
        return result;
    }

    void* rpc_handle = nullptr;
    std::vector<void*> handles;
    auto close_probe_handles = [&]() {
        for (auto it = handles.rbegin(); it != handles.rend(); ++it) {
            if (*it != nullptr) dlclose(*it);
        }
        handles.clear();
        if (rpc_handle != nullptr) {
            dlclose(rpc_handle);
            rpc_handle = nullptr;
        }
    };

    if (result.rpc_present && !result.rpc_path.empty()) {
        rpc_handle = dlopen(result.rpc_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (rpc_handle != nullptr) {
            result.rpc_loadable = true;
            result.rpc_message = "libcdsprpc.so loaded successfully before QNN HTP.";
        } else {
            const char* error = dlerror();
            result.rpc_loadable = false;
            result.rpc_message = std::string("dlopen libcdsprpc.so failed: ") +
                (error ? error : "unknown error");
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", result.rpc_message.c_str());
        }
    }

    result.adsp_library_path = join_adsp_paths(prioritize_adsp_paths(prioritized_adsp_paths));
    if (!result.adsp_library_path.empty()) {
        setenv("ADSP_LIBRARY_PATH", result.adsp_library_path.c_str(), 1);
        __android_log_print(ANDROID_LOG_INFO, kTag, "ADSP_LIBRARY_PATH=%s", result.adsp_library_path.c_str());
    }

    if (result.rpc_present && result.rpc_message.empty()) {
        result.rpc_message =
            "libcdsprpc.so is present but not dlopen-probed directly; QNN HTP resolves transport dependencies during graph smoke.";
    } else if (!result.rpc_present) {
        result.rpc_message =
            "libcdsprpc.so was not found; this is acceptable when the device or QAIRT HTP backend resolves transport dependencies.";
    }

    std::vector<std::string> load_order;
    load_order.push_back(result.system_path);
    load_order.push_back(result.htp_path);
    for (const auto& path : load_order) {
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char* error = dlerror();
            result.loadable = false;
            result.message = std::string("dlopen failed for ") + path + ": " + (error ? error : "unknown error");
            __android_log_print(ANDROID_LOG_WARN, kTag, "%s", result.message.c_str());
            close_probe_handles();
            return result;
        }
        handles.push_back(handle);
    }

    for (void* handle : handles) {
        if (dlsym(handle, "QnnInterface_getProviders") != nullptr) {
            result.qnn_interface_present = true;
        }
        if (dlsym(handle, "QnnSystemInterface_getProviders") != nullptr) {
            result.qnn_system_interface_present = true;
        }
    }
    result.loadable = true;
    result.message = result.qnn_interface_present
        ? "QNN host runtime libraries loaded successfully and QnnInterface_getProviders was found."
        : "QNN host runtime libraries loaded successfully, but QnnInterface_getProviders was not found.";
    close_probe_handles();
    return result;
}

bool select_qnn_runtime_profile_for_context(
        RuntimeProbe* runtime,
        uint32_t context_soc_model) {
    if (runtime == nullptr) return false;
    const int context_htp_arch = htp_arch_version_for_soc_model(context_soc_model);
    const int device_htp_arch = physical_device_htp_arch_version();

    struct HostCandidate {
        std::string directory;
        std::string system;
        std::string htp;
        std::string rpc;
    };
    struct DspCandidate {
        std::string directory;
        std::string skel;
        std::string stub;
    };
    std::vector<HostCandidate> hosts;
    for (const auto& dir : runtime->search_directories) {
        if (dir.empty()) continue;
        const std::string system = join_path(dir, "libQnnSystem.so");
        const std::string htp = join_path(dir, "libQnnHtp.so");
        if (exists_file(system) && exists_file(htp)) {
            hosts.push_back({dir, system, htp, join_path(dir, "libcdsprpc.so")});
        }
    }

    // Device detection ranks the preferred transport but is never an admission
    // list. Try the physical transport first, then the context's declared
    // transport, then the coherent profile selected by the generic runtime
    // probe, and finally every complete packaged profile. A newer device with
    // only an older complete package therefore reaches real context/graph load
    // instead of failing at a static chipset table.
    std::vector<int> candidate_arches;
    auto append_arch = [&](int arch) {
        if (arch <= 0) return;
        if (std::find(candidate_arches.begin(), candidate_arches.end(), arch) == candidate_arches.end()) {
            candidate_arches.push_back(arch);
        }
    };
    append_arch(device_htp_arch);
    append_arch(context_htp_arch);
    append_arch(runtime->htp_arch_version);

    std::vector<int> packaged_arches;
    for (const auto& dir : runtime->search_directories) {
        DIR* handle = opendir(dir.c_str());
        if (handle == nullptr) continue;
        while (dirent* entry = readdir(handle)) {
            const std::string name(entry->d_name);
            if (name.find("libQnnHtpV") != 0 ||
                (!ends_with(name, "Skel.so") && !ends_with(name, "Stub.so"))) {
                continue;
            }
            const int version = htp_library_version(name);
            if (version > 0 && std::find(packaged_arches.begin(), packaged_arches.end(), version) == packaged_arches.end()) {
                packaged_arches.push_back(version);
            }
        }
        closedir(handle);
    }
    std::sort(packaged_arches.begin(), packaged_arches.end(), [](int left, int right) {
        return left > right;
    });
    for (const int arch : packaged_arches) append_arch(arch);

    auto select_profile = [&](const HostCandidate& host, const DspCandidate& dsp, int htp_arch) {
        runtime->system_present = true;
        runtime->htp_present = true;
        runtime->skel_present = true;
        runtime->stub_present = true;
        runtime->rpc_present = exists_file(host.rpc);
        runtime->system_path = host.system;
        runtime->htp_path = host.htp;
        runtime->skel_path = dsp.skel;
        runtime->stub_path = dsp.stub;
        runtime->rpc_path = runtime->rpc_present ? host.rpc : "";
        runtime->dsp_runtime_directory = dsp.directory;
        runtime->runtime_directory = host.directory == dsp.directory
            ? host.directory
            : "host=" + host.directory + ";dsp=" + dsp.directory;
        runtime->htp_arch_version = htp_arch;

        // The exact Skel/Stub directory is the only non-platform entry in the
        // DSP lookup path. Platform fallback directories are safe for OEM
        // split runtimes but cannot override a side-loaded exact profile.
        std::vector<std::string> adsp_paths;
        append_unique_path(&adsp_paths, dsp.directory);
        append_common_android_adsp_paths(&adsp_paths);
        runtime->adsp_library_path = join_adsp_paths(adsp_paths);
        if (!runtime->adsp_library_path.empty()) {
            setenv("ADSP_LIBRARY_PATH", runtime->adsp_library_path.c_str(), 1);
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "Selected coherent QNN runtime profile: context socModel=%u (HTP V%d), device HTP V%d, selected HTP V%d, system=%s, htp=%s, skel=%s, stub=%s",
            context_soc_model,
            context_htp_arch,
            device_htp_arch,
            htp_arch,
            runtime->system_path.c_str(),
            runtime->htp_path.c_str(),
            runtime->skel_path.c_str(),
            runtime->stub_path.c_str());
    };

    for (const int htp_arch : candidate_arches) {
        std::vector<DspCandidate> dsps;
        for (const auto& dir : runtime->search_directories) {
            if (dir.empty()) continue;
            const std::string skel = htp_library_for_arch_in_dir(dir, htp_arch, "Skel.so");
            const std::string stub = htp_library_for_arch_in_dir(dir, htp_arch, "Stub.so");
            if (!skel.empty() && !stub.empty()) {
                dsps.push_back({dir, skel, stub});
            }
        }
        for (const auto& host : hosts) {
            for (const auto& dsp : dsps) {
                if (!may_pair_qnn_runtime_directories(host.directory, dsp.directory)) continue;
                select_profile(host, dsp, htp_arch);
                return true;
            }
        }
    }

    std::ostringstream message;
    message << "No coherent QNN runtime profile for context socModel=" << context_soc_model
            << " (context HTP V" << context_htp_arch
            << ", device HTP V" << device_htp_arch << ", attempted=";
    if (candidate_arches.empty()) {
        message << "none";
    } else {
        for (size_t i = 0; i < candidate_arches.size(); ++i) {
            if (i > 0) message << ",";
            message << "V" << candidate_arches[i];
        }
    }
    message << "): a coherent libQnnSystem.so/libQnnHtp.so plus matching Skel/Stub profile "
            << "must be installed as one side-loaded bundle or compatible OEM host/RFSA directories.";
    runtime->message = message.str();
    return false;
}

std::string runtime_probe_json(const RuntimeProbe& probe) {
    std::ostringstream out;
    out << "{"
        << "\"ready\":" << (probe.system_present && probe.htp_present && probe.skel_present ? "true" : "false") << ","
        << "\"loadable\":" << (probe.loadable ? "true" : "false") << ","
        << "\"qnnSystemLibraryPresent\":" << (probe.system_present ? "true" : "false") << ","
        << "\"qnnHtpLibraryPresent\":" << (probe.htp_present ? "true" : "false") << ","
        << "\"htpSkelLibraryPresent\":" << (probe.skel_present ? "true" : "false") << ","
        << "\"htpStubLibraryPresent\":" << (probe.stub_present ? "true" : "false") << ","
        << "\"cdspRpcLibraryPresent\":" << (probe.rpc_present ? "true" : "false") << ","
        << "\"cdspRpcLibraryLoadable\":" << (probe.rpc_loadable ? "true" : "false") << ","
        << "\"qnnInterfacePresent\":" << (probe.qnn_interface_present ? "true" : "false") << ","
        << "\"qnnSystemInterfacePresent\":" << (probe.qnn_system_interface_present ? "true" : "false") << ","
        << "\"qnnSystemLibraryPath\":" << quote(probe.system_path) << ","
        << "\"qnnHtpLibraryPath\":" << quote(probe.htp_path) << ","
        << "\"htpSkelLibraryPath\":" << quote(probe.skel_path) << ","
        << "\"htpStubLibraryPath\":" << quote(probe.stub_path) << ","
        << "\"cdspRpcLibraryPath\":" << quote(probe.rpc_path) << ","
        << "\"cdspRpcMessage\":" << quote(probe.rpc_message) << ","
        << "\"adspLibraryPath\":" << quote(probe.adsp_library_path) << ","
        << "\"runtimeDirectory\":" << quote(probe.runtime_directory) << ","
        << "\"dspRuntimeDirectory\":" << quote(probe.dsp_runtime_directory) << ","
        << "\"htpArchVersion\":" << probe.htp_arch_version << ","
        << "\"compile\":" << compile_capability_json() << ","
        << "\"message\":" << quote(probe.message)
        << "}";
    return out.str();
}

struct BundleProbe {
    bool root_present = false;
    bool manifest_present = false;
    bool has_graph_artifact = false;
    bool has_tokenizer = false;
    int file_count = 0;
    std::vector<std::string> sample_files;
};

BundleProbe inspect_bundle_internal(const std::string& root) {
    BundleProbe probe;
    probe.root_present = exists_dir(root);
    if (!probe.root_present) return probe;

    auto files = list_files_recursive(root);
    probe.file_count = static_cast<int>(files.size());
    for (const auto& path : files) {
        if (probe.sample_files.size() < 12) probe.sample_files.push_back(path);
        const std::string lower_path = [&] {
            std::string lower = path;
            std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            return lower;
        }();
        if (ends_with(lower_path, "/manifest.json")) probe.manifest_present = true;
        if (ends_with(lower_path, ".ctx") || ends_with(lower_path, ".bin") ||
            contains_lower(lower_path, "context") || contains_lower(lower_path, "qnn")) {
            probe.has_graph_artifact = true;
        }
        if (contains_lower(lower_path, "tokenizer") || ends_with(lower_path, "vocab.json") ||
            ends_with(lower_path, "merges.txt")) {
            probe.has_tokenizer = true;
        }
    }
    return probe;
}

std::string bundle_probe_json(const BundleProbe& probe) {
    std::ostringstream out;
    out << "{"
        << "\"rootPresent\":" << (probe.root_present ? "true" : "false") << ","
        << "\"manifestPresent\":" << (probe.manifest_present ? "true" : "false") << ","
        << "\"hasGraphArtifact\":" << (probe.has_graph_artifact ? "true" : "false") << ","
        << "\"hasTokenizer\":" << (probe.has_tokenizer ? "true" : "false") << ","
        << "\"fileCount\":" << probe.file_count << ","
        << "\"sampleFiles\":" << files_json(probe.sample_files)
        << "}";
    return out.str();
}

int count_objects_in_array(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return 0;
    const auto array_start = json.find('[', key_pos);
    if (array_start == std::string::npos) return 0;
    int depth = 0;
    int object_count = 0;
    bool in_string = false;
    bool escape = false;
    for (size_t i = array_start; i < json.size(); ++i) {
        const char c = json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '[') {
            ++depth;
            continue;
        }
        if (c == ']') {
            --depth;
            if (depth == 0) break;
            continue;
        }
        if (c == '{' && depth == 1) {
            ++object_count;
        }
    }
    return object_count;
}

std::string string_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    const auto first_quote = json.find('"', colon + 1);
    if (first_quote == std::string::npos) return "";
    std::string out;
    bool escape = false;
    for (size_t i = first_quote + 1; i < json.size(); ++i) {
        const char c = json[i];
        if (escape) {
            out.push_back(c);
            escape = false;
            continue;
        }
        if (c == '\\') {
            escape = true;
            continue;
        }
        if (c == '"') break;
        out.push_back(c);
    }
    return out;
}

bool bool_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return false;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return false;
    const auto value_start = json.find_first_not_of(" \t\r\n", colon + 1);
    return value_start != std::string::npos && json.compare(value_start, 4, "true") == 0;
}

long long long_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return 0;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return 0;
    const auto value_start = json.find_first_of("-0123456789", colon + 1);
    if (value_start == std::string::npos) return 0;
    size_t value_end = value_start;
    while (value_end < json.size() && (json[value_end] == '-' || std::isdigit(static_cast<unsigned char>(json[value_end])))) {
        ++value_end;
    }
    return std::atoll(json.substr(value_start, value_end - value_start).c_str());
}

double double_field(const std::string& json, const std::string& key, double fallback = 0.0) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    const auto value_start = json.find_first_of("-0123456789.", colon + 1);
    if (value_start == std::string::npos) return fallback;
    size_t value_end = value_start;
    while (value_end < json.size()) {
        const char c = json[value_end];
        if (!(c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' ||
              std::isdigit(static_cast<unsigned char>(c)))) {
            break;
        }
        ++value_end;
    }
    char* end = nullptr;
    const double value = std::strtod(json.substr(value_start, value_end - value_start).c_str(), &end);
    return end == nullptr ? fallback : value;
}

std::string json_value_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    const auto value_start = json.find_first_not_of(" \t\r\n", colon + 1);
    if (value_start == std::string::npos) return "";

    const char first = json[value_start];
    if (first != '{' && first != '[') return "";

    int depth = 0;
    bool in_string = false;
    bool escape = false;
    for (size_t i = value_start; i < json.size(); ++i) {
        const char c = json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '{' || c == '[') {
            ++depth;
            continue;
        }
        if (c == '}' || c == ']') {
            --depth;
            if (depth == 0) {
                return json.substr(value_start, i - value_start + 1);
            }
        }
    }
    return "";
}

std::string json_array_field(const std::string& json, const std::string& key) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    const auto value_start = json.find_first_not_of(" \t\r\n", colon + 1);
    if (value_start == std::string::npos || json[value_start] != '[') return "";

    int depth = 0;
    bool in_string = false;
    bool escape = false;
    for (size_t i = value_start; i < json.size(); ++i) {
        const char c = json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '[') {
            ++depth;
            continue;
        }
        if (c == ']') {
            --depth;
            if (depth == 0) {
                return json.substr(value_start, i - value_start + 1);
            }
        }
    }
    return "";
}

std::vector<std::string> split_top_level_objects(const std::string& array_json) {
    std::vector<std::string> objects;
    int depth = 0;
    bool in_string = false;
    bool escape = false;
    size_t object_start = std::string::npos;
    for (size_t i = 0; i < array_json.size(); ++i) {
        const char c = array_json[i];
        if (in_string) {
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
            continue;
        }
        if (c == '{') {
            if (depth == 0) object_start = i;
            ++depth;
            continue;
        }
        if (c == '}') {
            --depth;
            if (depth == 0 && object_start != std::string::npos) {
                objects.push_back(array_json.substr(object_start, i - object_start + 1));
                object_start = std::string::npos;
            }
        }
    }
    return objects;
}

std::vector<uint32_t> uint_array_field(const std::string& json, const std::string& key) {
    std::vector<uint32_t> values;
    const std::string array = json_array_field(json, key);
    if (array.empty()) return values;
    size_t pos = 0;
    while (pos < array.size()) {
        const auto digit = array.find_first_of("0123456789", pos);
        if (digit == std::string::npos) break;
        size_t end = digit;
        while (end < array.size() && std::isdigit(static_cast<unsigned char>(array[end]))) {
            ++end;
        }
        const auto value = std::strtoull(array.substr(digit, end - digit).c_str(), nullptr, 10);
        if (value > 0 && value <= std::numeric_limits<uint32_t>::max()) {
            values.push_back(static_cast<uint32_t>(value));
        }
        pos = end;
    }
    return values;
}

struct NativeTensorPlan {
    std::string name;
    std::string role;
    std::string data_type;
    std::vector<uint32_t> shape;
    long long element_count = 0;
    int bytes_per_element = 0;
    long long byte_size = 0;
    bool supported = false;
    std::string reason;
};

NativeTensorPlan tensor_plan_from_json(const std::string& json, const std::string& fallback_role) {
    NativeTensorPlan plan;
    plan.name = string_field(json, "name");
    plan.role = string_field(json, "role");
    if (plan.role.empty()) plan.role = fallback_role;
    plan.data_type = string_field(json, "dataType");
    plan.shape = uint_array_field(json, "shape");
    plan.element_count = long_field(json, "elementCount");
    plan.bytes_per_element = static_cast<int>(long_field(json, "bytesPerElement"));
    plan.byte_size = long_field(json, "byteSize");
    plan.supported = bool_field(json, "supported");
    plan.reason = string_field(json, "reason");
    return plan;
}

std::vector<NativeTensorPlan> tensor_plans_from_buffer_plan(
        const std::string& smoke_spec_json,
        const std::string& key,
        const std::string& fallback_role) {
    std::vector<NativeTensorPlan> plans;
    const std::string buffer_plan = json_value_field(smoke_spec_json, "bufferPlan");
    if (buffer_plan.empty()) return plans;
    const std::string array = json_array_field(buffer_plan, key);
    if (array.empty()) return plans;
    for (const auto& object : split_top_level_objects(array)) {
        plans.push_back(tensor_plan_from_json(object, fallback_role));
    }
    return plans;
}

struct SmokeSpecProbe {
    bool present = false;
    bool complete = false;
    bool tensor_buffer_plan_ready = false;
    bool validation_ready = false;
    std::string graph_name;
    std::string context_binary;
    std::string buffer_plan_json;
    std::string validation_json;
    long long context_binary_bytes = 0;
    int input_count = 0;
    int output_count = 0;
    long long input_buffer_bytes = 0;
    long long output_buffer_bytes = 0;
};

SmokeSpecProbe inspect_smoke_spec(const std::string& smoke_spec_json) {
    SmokeSpecProbe probe;
    probe.present = smoke_spec_json.find('{') != std::string::npos;
    probe.graph_name = string_field(smoke_spec_json, "graphName");
    probe.context_binary = string_field(smoke_spec_json, "contextBinary");
    probe.buffer_plan_json = json_value_field(smoke_spec_json, "bufferPlan");
    probe.validation_json = json_value_field(smoke_spec_json, "validation");
    probe.input_count = count_objects_in_array(smoke_spec_json, "inputs");
    probe.output_count = count_objects_in_array(smoke_spec_json, "outputs");
    probe.tensor_buffer_plan_ready = bool_field(smoke_spec_json, "tensorBufferPlanReady");
    probe.validation_ready = !probe.validation_json.empty() &&
        bool_field(probe.validation_json, "readyForNativeSmoke");
    probe.input_buffer_bytes = long_field(smoke_spec_json, "inputBufferBytes");
    probe.output_buffer_bytes = long_field(smoke_spec_json, "outputBufferBytes");
    probe.complete = bool_field(smoke_spec_json, "completeForGraphSmoke") &&
        (probe.validation_json.empty() || probe.validation_ready);
    return probe;
}

std::string smoke_spec_probe_json(const SmokeSpecProbe& probe) {
    std::ostringstream out;
    out << "{"
        << "\"present\":" << (probe.present ? "true" : "false") << ","
        << "\"complete\":" << (probe.complete ? "true" : "false") << ","
        << "\"graphName\":" << quote(probe.graph_name) << ","
        << "\"contextBinary\":" << quote(probe.context_binary) << ","
        << "\"contextBinaryBytes\":" << probe.context_binary_bytes << ","
        << "\"inputCount\":" << probe.input_count << ","
        << "\"outputCount\":" << probe.output_count << ","
        << "\"tensorBufferPlanReady\":" << (probe.tensor_buffer_plan_ready ? "true" : "false") << ","
        << "\"validationReady\":" << (probe.validation_ready ? "true" : "false") << ","
        << "\"inputBufferBytes\":" << probe.input_buffer_bytes << ","
        << "\"outputBufferBytes\":" << probe.output_buffer_bytes << ","
        << "\"bufferPlan\":" << (probe.buffer_plan_json.empty() ? "{}" : probe.buffer_plan_json) << ","
        << "\"validation\":" << (probe.validation_json.empty() ? "{}" : probe.validation_json)
        << "}";
    return out.str();
}

std::string smoke_stage_json(
        const RuntimeProbe& runtime,
        const BundleProbe& bundle,
        const SmokeSpecProbe& smoke_spec,
        bool context_binary_present,
        bool graph_metadata_ready,
        bool backend_created,
        bool context_loaded,
        bool graph_resolved,
        bool tensors_bound,
        bool graph_executed) {
    std::ostringstream out;
    out << "{"
        << "\"runtimeLoaded\":" << (runtime.loadable ? "true" : "false") << ","
        << "\"qnnInterfaceFound\":" << (runtime.qnn_interface_present ? "true" : "false") << ","
        << "\"bundleRootFound\":" << (bundle.root_present ? "true" : "false") << ","
        << "\"bundleManifestFound\":" << (bundle.manifest_present ? "true" : "false") << ","
        << "\"bundleGraphArtifactFound\":" << (bundle.has_graph_artifact ? "true" : "false") << ","
        << "\"bundleContextBinaryFound\":" << (context_binary_present ? "true" : "false") << ","
        << "\"bundleContextBinaryNonEmpty\":" << (smoke_spec.context_binary_bytes > 0 ? "true" : "false") << ","
        << "\"smokeMetadataComplete\":" << (smoke_spec.complete ? "true" : "false") << ","
        << "\"tensorBufferPlanReady\":" << (smoke_spec.tensor_buffer_plan_ready ? "true" : "false") << ","
        << "\"graphMetadataReady\":" << (graph_metadata_ready ? "true" : "false") << ","
        << "\"sdkHeadersCompiled\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
        << "\"backendCreated\":" << (backend_created ? "true" : "false") << ","
        << "\"contextLoaded\":" << (context_loaded ? "true" : "false") << ","
        << "\"graphResolved\":" << (graph_resolved ? "true" : "false") << ","
        << "\"tensorsBound\":" << (tensors_bound ? "true" : "false") << ","
        << "\"graphExecuted\":" << (graph_executed ? "true" : "false")
        << "}";
    return out.str();
}

struct GraphSmokeResult {
    bool attempted = false;
    bool backend_created = false;
    bool device_created = false;
    bool context_default_device = false;
    bool context_loaded = false;
    bool graph_resolved = false;
    bool tensors_bound = false;
    bool graph_executed = false;
    long long context_load_ms = 0;
    long long graph_execute_ms = 0;
    uint64_t output_checksum = 0;
    // Preflight can only load QnnSystem. Keep the actual context-selected
    // runtime separately so smoke evidence cannot falsely report a generic
    // V81 probe profile while graph execution used V73.
    RuntimeProbe execution_runtime;
    bool execution_runtime_selected = false;
    std::string execution_stage;
    std::string device_message;
    std::string message;
};

#if MCA_WITH_QNN_SDK_HEADERS

using QnnInterfaceGetProvidersFn =
    Qnn_ErrorHandle_t (*)(const QnnInterface_t*** providerList, uint32_t* numProviders);
using QnnSystemInterfaceGetProvidersFn =
    Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t*** providerList, uint32_t* numProviders);

void qnn_android_log_callback(const char* fmt, QnnLog_Level_t level, uint64_t, va_list args) {
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case QNN_LOG_LEVEL_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        case QNN_LOG_LEVEL_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case QNN_LOG_LEVEL_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        case QNN_LOG_LEVEL_VERBOSE:
            priority = ANDROID_LOG_VERBOSE;
            break;
        default:
            priority = ANDROID_LOG_DEBUG;
            break;
    }
    __android_log_vprint(priority, kTag, fmt, args);
}

struct QnnLogSession {
    const QNN_INTERFACE_VER_TYPE* api = nullptr;
    Qnn_LogHandle_t handle = nullptr;

    QnnLogSession() = default;
    QnnLogSession(const QnnLogSession&) = delete;
    QnnLogSession& operator=(const QnnLogSession&) = delete;

    QnnLogSession(QnnLogSession&& other) noexcept {
        api = other.api;
        handle = other.handle;
        other.api = nullptr;
        other.handle = nullptr;
    }

    QnnLogSession& operator=(QnnLogSession&& other) noexcept {
        if (this != &other) {
            reset();
            api = other.api;
            handle = other.handle;
            other.api = nullptr;
            other.handle = nullptr;
        }
        return *this;
    }

    ~QnnLogSession() { reset(); }

    void reset() {
        if (api != nullptr && handle != nullptr && api->logFree != nullptr) {
            api->logFree(handle);
        }
        api = nullptr;
        handle = nullptr;
    }
};

QnnLogSession create_qnn_log_session(const QNN_INTERFACE_VER_TYPE& api) {
    QnnLogSession session;
    session.api = &api;
    if (api.logCreate != nullptr) {
        Qnn_LogHandle_t log = nullptr;
        const Qnn_ErrorHandle_t status =
            api.logCreate(qnn_android_log_callback, QNN_LOG_LEVEL_VERBOSE, &log);
        if (status == QNN_SUCCESS && log != nullptr) {
            session.handle = log;
        } else {
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "QNN logCreate failed: 0x%x",
                static_cast<unsigned int>(status));
        }
    }
    return session;
}

std::string qnn_error_to_string(const QNN_INTERFACE_VER_TYPE& api, Qnn_ErrorHandle_t error) {
    if (error == QNN_SUCCESS) return "QNN_SUCCESS";
    std::ostringstream suffix;
    suffix << "0x" << std::hex << error;
    if (api.errorGetMessage != nullptr) {
        const char* message = nullptr;
        if (api.errorGetMessage(error, &message) == QNN_SUCCESS && message != nullptr) {
            return std::string(message) + " (" + suffix.str() + ")";
        }
    }
    return "QNN error " + suffix.str();
}

std::string append_diagnostic_note(std::string message, const std::string& note) {
    if (note.empty()) return message;
    if (!message.empty()) message += " ";
    message += note;
    return message;
}

// Context binaries carry the QAIRT QNN_SOC_MODEL_* value, not an HTP architecture.
// Never use a newer architecture as a generic fallback: QNN rejects an incompatible
// ARCH custom config before it can create the device on older Snapdragon hardware.
QnnHtpDevice_Arch_t htp_arch_for_soc_model(uint32_t soc_model) {
    switch (soc_model) {
        // Snapdragon 888 / 8 Gen 1 / 8+ Gen 1.
        case 30: return QNN_HTP_DEVICE_ARCH_V68;  // SM8350
        case 36:                                  // SM8450
        case 42: return QNN_HTP_DEVICE_ARCH_V69;  // SM8475
        // Snapdragon 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5.
        case 43: return QNN_HTP_DEVICE_ARCH_V73;  // SM8550
        case 57: return QNN_HTP_DEVICE_ARCH_V75;  // SM8650
        case 69: return QNN_HTP_DEVICE_ARCH_V79;  // SM8750
        case 87: return QNN_HTP_DEVICE_ARCH_V81;  // SM8850
        default: return QNN_HTP_DEVICE_ARCH_NONE;
    }
}

const char* htp_arch_name(QnnHtpDevice_Arch_t arch) {
    switch (arch) {
        case QNN_HTP_DEVICE_ARCH_V68: return "V68";
        case QNN_HTP_DEVICE_ARCH_V69: return "V69";
        case QNN_HTP_DEVICE_ARCH_V73: return "V73";
        case QNN_HTP_DEVICE_ARCH_V75: return "V75";
        case QNN_HTP_DEVICE_ARCH_V79: return "V79";
        case QNN_HTP_DEVICE_ARCH_V81: return "V81";
        default: return "unknown";
    }
}

struct QnnDeviceCreateOutcome {
    Qnn_DeviceHandle_t device = nullptr;
    bool created = false;
    bool context_default_device = false;
    bool fatal = false;
    std::string message;
};

QnnDeviceCreateOutcome create_qnn_device_or_default(
        const QNN_INTERFACE_VER_TYPE& api,
        uint32_t soc_model = 0,
        Qnn_LogHandle_t log_handle = nullptr) {
    QnnDeviceCreateOutcome outcome;
    if (api.deviceCreate == nullptr) {
        outcome.context_default_device = true;
        outcome.message =
            "QNN deviceCreate API is unavailable; using NULL device for context default affinity.";
        return outcome;
    }

    std::vector<std::string> attempts;
    auto try_create = [&](const char* label, const QnnDevice_Config_t** configs) -> bool {
        Qnn_DeviceHandle_t candidate = nullptr;
        const Qnn_ErrorHandle_t status = api.deviceCreate(log_handle, configs, &candidate);
        if (status == QNN_SUCCESS && candidate != nullptr) {
            outcome.device = candidate;
            outcome.created = true;
            outcome.context_default_device = false;
            outcome.message = std::string("QNN deviceCreate succeeded with ") + label + ".";
            return true;
        }
        std::ostringstream attempt;
        attempt << label << ": " << qnn_error_to_string(api, status);
        if (status == QNN_SUCCESS && candidate == nullptr) {
            attempt << " but returned null device";
        }
        attempts.push_back(attempt.str());
        return false;
    };

    if (try_create("default config", nullptr)) return outcome;
    if (!attempts.empty() &&
        attempts.back().find("QNN_SUCCESS") != std::string::npos &&
        attempts.back().find("null device") != std::string::npos) {
        outcome.context_default_device = true;
        outcome.message =
            "QNN deviceCreate returned success without a device handle; using NULL device for context default affinity.";
        return outcome;
    }

    auto make_custom_config = [](QnnHtpDevice_CustomConfig_t* custom) {
        QnnDevice_Config_t config = QNN_DEVICE_CONFIG_INIT;
        config.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
        config.customConfig = custom;
        return config;
    };

    if (soc_model != 0) {
        QnnHtpDevice_CustomConfig_t soc = {};
        soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
        soc.socModel = soc_model;
        QnnDevice_Config_t soc_config = make_custom_config(&soc);
        const QnnDevice_Config_t* configs[] = {&soc_config, nullptr};
        std::ostringstream label;
        label << "HTP SOC=" << soc_model;
        if (try_create(label.str().c_str(), configs)) return outcome;
    }

    const QnnHtpDevice_Arch_t preferred_arch = htp_arch_for_soc_model(soc_model);
    const uint32_t device_ids[] = {QNN_DEVICE_DEFAULT_DEVICE_ID, 0u};
    if (preferred_arch != QNN_HTP_DEVICE_ARCH_NONE) {
        for (uint32_t device_id : device_ids) {
            QnnHtpDevice_CustomConfig_t arch = {};
            arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
            arch.arch.deviceId = device_id;
            arch.arch.arch = preferred_arch;
            QnnDevice_Config_t arch_config = make_custom_config(&arch);
            const QnnDevice_Config_t* configs[] = {&arch_config, nullptr};
            std::ostringstream label;
            label << "HTP ARCH=" << htp_arch_name(preferred_arch) << " deviceId=" << device_id;
            if (try_create(label.str().c_str(), configs)) return outcome;
        }

        if (soc_model != 0) {
            for (uint32_t device_id : device_ids) {
                QnnHtpDevice_CustomConfig_t soc = {};
                soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
                soc.socModel = soc_model;
                QnnHtpDevice_CustomConfig_t arch = {};
                arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
                arch.arch.deviceId = device_id;
                arch.arch.arch = preferred_arch;
                QnnDevice_Config_t soc_config = make_custom_config(&soc);
                QnnDevice_Config_t arch_config = make_custom_config(&arch);
                const QnnDevice_Config_t* configs[] = {&soc_config, &arch_config, nullptr};
                std::ostringstream label;
                label << "HTP SOC=" << soc_model << " ARCH=" << htp_arch_name(preferred_arch)
                      << " deviceId=" << device_id;
                if (try_create(label.str().c_str(), configs)) return outcome;
            }
        }
    }

    for (uint32_t device_id : device_ids) {
        for (bool signed_pd : {false, true}) {
            QnnHtpDevice_CustomConfig_t signed_pd_config_value = {};
            signed_pd_config_value.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
            signed_pd_config_value.useSignedProcessDomain.deviceId = device_id;
            signed_pd_config_value.useSignedProcessDomain.useSignedProcessDomain = signed_pd;
            QnnDevice_Config_t signed_config = make_custom_config(&signed_pd_config_value);
            const QnnDevice_Config_t* configs[] = {&signed_config, nullptr};
            std::ostringstream label;
            label << "HTP SIGNEDPD=" << (signed_pd ? "true" : "false")
                  << " deviceId=" << device_id;
            if (try_create(label.str().c_str(), configs)) return outcome;
        }
    }

    if (soc_model != 0 && preferred_arch != QNN_HTP_DEVICE_ARCH_NONE) {
        for (uint32_t device_id : device_ids) {
            for (bool signed_pd : {false, true}) {
                QnnHtpDevice_CustomConfig_t soc = {};
                soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
                soc.socModel = soc_model;
                QnnHtpDevice_CustomConfig_t arch = {};
                arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
                arch.arch.deviceId = device_id;
                arch.arch.arch = preferred_arch;
                QnnHtpDevice_CustomConfig_t signed_pd_config_value = {};
                signed_pd_config_value.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
                signed_pd_config_value.useSignedProcessDomain.deviceId = device_id;
                signed_pd_config_value.useSignedProcessDomain.useSignedProcessDomain = signed_pd;
                QnnDevice_Config_t soc_config = make_custom_config(&soc);
                QnnDevice_Config_t arch_config = make_custom_config(&arch);
                QnnDevice_Config_t signed_config = make_custom_config(&signed_pd_config_value);
                const QnnDevice_Config_t* configs[] = {
                    &soc_config,
                    &arch_config,
                    &signed_config,
                    nullptr
                };
                std::ostringstream label;
                label << "HTP SOC=" << soc_model << " ARCH=" << htp_arch_name(preferred_arch)
                      << " SIGNEDPD="
                      << (signed_pd ? "true" : "false") << " deviceId=" << device_id;
                if (try_create(label.str().c_str(), configs)) return outcome;
            }
        }
    }

    outcome.context_default_device = true;
    std::ostringstream message;
    message << "QNN deviceCreate failed for all tested configs";
    if (soc_model != 0) message << " (context socModel=" << soc_model << ")";
    message << "; continuing with NULL device for context default affinity. Attempts: ";
    for (size_t i = 0; i < attempts.size(); ++i) {
        if (i > 0) message << " | ";
        message << attempts[i];
    }
    outcome.message = message.str();
    outcome.device = nullptr;
    return outcome;
}

std::string qnn_context_load_failure_message(
        const QNN_INTERFACE_VER_TYPE& api,
        Qnn_ErrorHandle_t error) {
    std::string message = "QNN contextCreateFromBinary failed: " +
        qnn_error_to_string(api, error);
    if (error == QNN_CONTEXT_ERROR_INVALID_ARGUMENT ||
        error == QNN_CONTEXT_ERROR_BINARY_VERSION ||
        error == QNN_CONTEXT_ERROR_CREATE_FROM_BINARY ||
        error == QNN_CONTEXT_ERROR_BINARY_CONFIGURATION ||
        error == QNN_CONTEXT_ERROR_BINARY_SUBOPTIMAL) {
        message +=
            ". The context binary may not match this device's QNN runtime, "
            "target SoC, or SDK version. Rebuild or download a QNN bundle "
            "compiled for the device runtime before enabling NPU image generation.";
    }
    return message;
}

std::string version_to_string(const Qnn_Version_t& version) {
    std::ostringstream out;
    out << version.major << "." << version.minor << "." << version.patch;
    return out.str();
}

std::vector<uint8_t> read_binary_file(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return {};
    return std::vector<uint8_t>(
        std::istreambuf_iterator<char>(file),
        std::istreambuf_iterator<char>()
    );
}

bool qnn_data_type_from_string(const std::string& data_type, Qnn_DataType_t* out) {
    std::string value = data_type;
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    if (value == "bool") {
        *out = QNN_DATATYPE_BOOL_8;
    } else if (value == "int8") {
        *out = QNN_DATATYPE_INT_8;
    } else if (value == "uint8") {
        *out = QNN_DATATYPE_UINT_8;
    } else if (value == "int16") {
        *out = QNN_DATATYPE_INT_16;
    } else if (value == "uint16") {
        *out = QNN_DATATYPE_UINT_16;
    } else if (value == "int32") {
        *out = QNN_DATATYPE_INT_32;
    } else if (value == "uint32") {
        *out = QNN_DATATYPE_UINT_32;
    } else if (value == "int64") {
        *out = QNN_DATATYPE_INT_64;
    } else if (value == "uint64") {
        *out = QNN_DATATYPE_UINT_64;
    } else if (value == "float16" || value == "fp16") {
        *out = QNN_DATATYPE_FLOAT_16;
    } else if (value == "float32" || value == "fp32") {
        *out = QNN_DATATYPE_FLOAT_32;
    } else if (value == "float64" || value == "fp64") {
        *out = QNN_DATATYPE_FLOAT_64;
    } else {
        return false;
    }
    return true;
}

struct QnnBinaryMetadata {
    bool attempted = false;
    bool parsed = false;
    uint32_t version = 0;
    uint32_t backend_id = 0;
    uint32_t graph_count = 0;
    uint32_t soc_model = 0;
    uint64_t context_blob_size = 0;
    std::string build_id;
    std::string core_api_version;
    std::string backend_api_version;
    std::string soc_version;
    std::string message;
    std::vector<std::string> graph_names;
};

std::string binary_metadata_json(const QnnBinaryMetadata& metadata) {
    std::ostringstream out;
    out << "{"
        << "\"attempted\":" << (metadata.attempted ? "true" : "false") << ","
        << "\"parsed\":" << (metadata.parsed ? "true" : "false") << ","
        << "\"version\":" << metadata.version << ","
        << "\"backendId\":" << metadata.backend_id << ","
        << "\"buildId\":" << quote(metadata.build_id) << ","
        << "\"coreApiVersion\":" << quote(metadata.core_api_version) << ","
        << "\"backendApiVersion\":" << quote(metadata.backend_api_version) << ","
        << "\"socVersion\":" << quote(metadata.soc_version) << ","
        << "\"socModel\":" << metadata.soc_model << ","
        << "\"contextBlobSize\":" << metadata.context_blob_size << ","
        << "\"graphCount\":" << metadata.graph_count << ","
        << "\"graphNames\":" << files_json(metadata.graph_names) << ","
        << "\"message\":" << quote(metadata.message)
        << "}";
    return out.str();
}

uint32_t qnn_tensor_id(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.id : tensor.v1.id;
}

const char* qnn_tensor_name(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.name : tensor.v1.name;
}

Qnn_TensorType_t qnn_tensor_type(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.type : tensor.v1.type;
}

Qnn_DataType_t qnn_tensor_data_type(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.dataType : tensor.v1.dataType;
}

Qnn_QuantizeParams_t qnn_tensor_quantize_params(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.quantizeParams : tensor.v1.quantizeParams;
}

uint32_t qnn_tensor_rank(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.rank : tensor.v1.rank;
}

uint32_t* qnn_tensor_dimensions(const Qnn_Tensor_t& tensor) {
    return tensor.version == QNN_TENSOR_VERSION_2 ? tensor.v2.dimensions : tensor.v1.dimensions;
}

void qnn_set_tensor_raw_buffer(Qnn_Tensor_t* tensor, void* buffer, uint32_t bytes) {
    if (tensor->version == QNN_TENSOR_VERSION_2) {
        tensor->v2.memType = QNN_TENSORMEMTYPE_RAW;
        tensor->v2.clientBuf.data = buffer;
        tensor->v2.clientBuf.dataSize = bytes;
    } else {
        tensor->v1.memType = QNN_TENSORMEMTYPE_RAW;
        tensor->v1.clientBuf.data = buffer;
        tensor->v1.clientBuf.dataSize = bytes;
    }
}

uint32_t qnn_element_bytes(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8:
        case QNN_DATATYPE_BOOL_8:
            return 1;
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_FLOAT_16:
        case QNN_DATATYPE_BFLOAT_16:
        case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16:
            return 2;
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32:
            return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:
        case QNN_DATATYPE_FLOAT_64:
            return 8;
        default:
            return 0;
    }
}

uint64_t qnn_fallback_tensor_bytes(const Qnn_Tensor_t& tensor) {
    const uint32_t rank = qnn_tensor_rank(tensor);
    uint32_t* dims = qnn_tensor_dimensions(tensor);
    if (rank == 0 || dims == nullptr) return 0;
    uint64_t elements = 1;
    for (uint32_t i = 0; i < rank; ++i) {
        if (dims[i] == 0 || elements > std::numeric_limits<uint64_t>::max() / dims[i]) {
            return 0;
        }
        elements *= dims[i];
    }
    const uint32_t bytes = qnn_element_bytes(qnn_tensor_data_type(tensor));
    if (bytes == 0 || elements > std::numeric_limits<uint64_t>::max() / bytes) return 0;
    return elements * bytes;
}

uint64_t qnn_tensor_bytes(
        const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
        const Qnn_Tensor_t& tensor) {
    if (sys_api.systemTensorGetMemoryFootprint != nullptr) {
        uint64_t bytes = 0;
        const Qnn_ErrorHandle_t status = sys_api.systemTensorGetMemoryFootprint(tensor, &bytes);
        if (status == QNN_SUCCESS && bytes > 0) return bytes;
    }
    return qnn_fallback_tensor_bytes(tensor);
}

uint16_t qnn_quantized_zero_u16(const Qnn_Tensor_t& tensor) {
    const auto quant = qnn_tensor_quantize_params(tensor);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        int64_t value = -static_cast<int64_t>(quant.scaleOffsetEncoding.offset);
        if (value < 0) value = 0;
        if (value > 65535) value = 65535;
        return static_cast<uint16_t>(value);
    }
    return 0;
}

void qnn_fill_smoke_input_buffer(const Qnn_Tensor_t& tensor, std::vector<uint8_t>* buffer) {
    const std::string name = qnn_tensor_name(tensor) != nullptr ? qnn_tensor_name(tensor) : "";
    if ((name.find("timestamp") != std::string::npos ||
         name.find("timestep") != std::string::npos ||
         name.find("time_step") != std::string::npos ||
         name == "time") &&
        qnn_tensor_data_type(tensor) == QNN_DATATYPE_INT_32 &&
        buffer->size() >= sizeof(int32_t)) {
        const int32_t timestep = 1;
        std::memcpy(buffer->data(), &timestep, sizeof(timestep));
        return;
    }
    if (qnn_tensor_data_type(tensor) == QNN_DATATYPE_UFIXED_POINT_16) {
        const uint16_t zero = qnn_quantized_zero_u16(tensor);
        for (size_t cursor = 0; cursor + sizeof(uint16_t) <= buffer->size(); cursor += sizeof(uint16_t)) {
            std::memcpy(buffer->data() + cursor, &zero, sizeof(zero));
        }
    }
}

struct QnnGraphMetadata {
    const char* name = nullptr;
    uint32_t input_count = 0;
    Qnn_Tensor_t* inputs = nullptr;
    uint32_t output_count = 0;
    Qnn_Tensor_t* outputs = nullptr;
};

QnnGraphMetadata qnn_graph_metadata(const QnnSystemContext_GraphInfo_t& graph) {
    QnnGraphMetadata out;
    switch (graph.version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            out.name = graph.graphInfoV1.graphName;
            out.input_count = graph.graphInfoV1.numGraphInputs;
            out.inputs = graph.graphInfoV1.graphInputs;
            out.output_count = graph.graphInfoV1.numGraphOutputs;
            out.outputs = graph.graphInfoV1.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            out.name = graph.graphInfoV2.graphName;
            out.input_count = graph.graphInfoV2.numGraphInputs;
            out.inputs = graph.graphInfoV2.graphInputs;
            out.output_count = graph.graphInfoV2.numGraphOutputs;
            out.outputs = graph.graphInfoV2.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            out.name = graph.graphInfoV3.graphName;
            out.input_count = graph.graphInfoV3.numGraphInputs;
            out.inputs = graph.graphInfoV3.graphInputs;
            out.output_count = graph.graphInfoV3.numGraphOutputs;
            out.outputs = graph.graphInfoV3.graphOutputs;
            break;
        default:
            break;
    }
    return out;
}

uint32_t qnn_binary_graph_count(const QnnSystemContext_BinaryInfo_t& info) {
    switch (info.version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            return info.contextBinaryInfoV1.numGraphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            return info.contextBinaryInfoV2.numGraphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            return info.contextBinaryInfoV3.numGraphs;
        default:
            return 0;
    }
}

QnnSystemContext_GraphInfo_t* qnn_binary_graphs(const QnnSystemContext_BinaryInfo_t& info) {
    switch (info.version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            return info.contextBinaryInfoV1.graphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            return info.contextBinaryInfoV2.graphs;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            return info.contextBinaryInfoV3.graphs;
        default:
            return nullptr;
    }
}

const QnnSystemInterface_t* select_qnn_system_interface(
        QnnSystemInterfaceGetProvidersFn get_providers,
        std::string* error) {
    const QnnSystemInterface_t** providers = nullptr;
    uint32_t provider_count = 0;
    const Qnn_ErrorHandle_t status = get_providers(&providers, &provider_count);
    if (status != QNN_SUCCESS || providers == nullptr || provider_count == 0) {
        std::ostringstream out;
        out << "QnnSystemInterface_getProviders failed or returned no providers: 0x"
            << std::hex << status;
        *error = out.str();
        return nullptr;
    }
    for (uint32_t i = 0; i < provider_count; ++i) {
        const QnnSystemInterface_t* provider = providers[i];
        if (provider != nullptr &&
            provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextCreate != nullptr &&
            (provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetBinaryInfo != nullptr ||
             provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetMetaData != nullptr) &&
            provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree != nullptr) {
            return provider;
        }
    }
    *error = "No QNN system provider with SystemContext binary metadata APIs was found.";
    return nullptr;
}

void append_graph_names(
        QnnSystemContext_GraphInfo_t* graphs,
        uint32_t graph_count,
        std::vector<std::string>* out) {
    if (graphs == nullptr || out == nullptr) return;
    for (uint32_t i = 0; i < graph_count && i < 16; ++i) {
        const auto& graph = graphs[i];
        const char* name = nullptr;
        switch (graph.version) {
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
                name = graph.graphInfoV1.graphName;
                break;
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
                name = graph.graphInfoV2.graphName;
                break;
            case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
                name = graph.graphInfoV3.graphName;
                break;
            default:
                break;
        }
        if (name != nullptr && name[0] != '\0') out->push_back(name);
    }
}

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const uint8_t* context_binary,
        size_t context_binary_size) {
    QnnBinaryMetadata metadata;
    metadata.attempted = true;
    if (context_binary == nullptr || context_binary_size == 0) {
        metadata.message = "Context binary is empty.";
        return metadata;
    }
    if (!runtime.system_present) {
        metadata.message = "libQnnSystem.so is missing.";
        return metadata;
    }

    ScopedDlHandle system_handle(dlopen(runtime.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
    if (!system_handle) {
        const char* error = dlerror();
        metadata.message = std::string("dlopen libQnnSystem.so failed: ") +
            (error ? error : "unknown error");
        return metadata;
    }

    auto get_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(system_handle.get(), "QnnSystemInterface_getProviders")
    );
    if (get_providers == nullptr) {
        metadata.message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        return metadata;
    }

    std::string provider_error;
    const QnnSystemInterface_t* provider = select_qnn_system_interface(get_providers, &provider_error);
    if (provider == nullptr) {
        metadata.message = provider_error;
        return metadata;
    }
    const auto& api = provider->QNN_SYSTEM_INTERFACE_VER_NAME;

    QnnSystemContext_Handle_t sys_context = nullptr;
    Qnn_ErrorHandle_t status = api.systemContextCreate(&sys_context);
    if (status != QNN_SUCCESS || sys_context == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_create failed: 0x" << std::hex << status;
        metadata.message = out.str();
        return metadata;
    }

    const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
    Qnn_ContextBinarySize_t binary_info_size = 0;
    if (api.systemContextGetBinaryInfo != nullptr) {
        status = api.systemContextGetBinaryInfo(
            sys_context,
            const_cast<uint8_t*>(context_binary),
            static_cast<uint64_t>(context_binary_size),
            &binary_info,
            &binary_info_size
        );
    } else {
        status = api.systemContextGetMetaData(
            sys_context,
            context_binary,
            static_cast<uint64_t>(context_binary_size),
            &binary_info
        );
    }
    if (status != QNN_SUCCESS || binary_info == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_getBinaryInfo failed: 0x" << std::hex << status;
        metadata.message = out.str();
        api.systemContextFree(sys_context);
        return metadata;
    }

    metadata.version = static_cast<uint32_t>(binary_info->version);
    switch (binary_info->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: {
            const auto& info = binary_info->contextBinaryInfoV1;
            metadata.backend_id = info.backendId;
            metadata.build_id = info.buildId != nullptr ? info.buildId : "";
            metadata.core_api_version = version_to_string(info.coreApiVersion);
            metadata.backend_api_version = version_to_string(info.backendApiVersion);
            metadata.soc_version = info.socVersion != nullptr ? info.socVersion : "";
            metadata.context_blob_size = info.contextBlobSize;
            metadata.graph_count = info.numGraphs;
            append_graph_names(info.graphs, info.numGraphs, &metadata.graph_names);
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: {
            const auto& info = binary_info->contextBinaryInfoV2;
            metadata.backend_id = info.backendId;
            metadata.build_id = info.buildId != nullptr ? info.buildId : "";
            metadata.core_api_version = version_to_string(info.coreApiVersion);
            metadata.backend_api_version = version_to_string(info.backendApiVersion);
            metadata.soc_version = info.socVersion != nullptr ? info.socVersion : "";
            metadata.context_blob_size = info.contextBlobSize;
            metadata.graph_count = info.numGraphs;
            append_graph_names(info.graphs, info.numGraphs, &metadata.graph_names);
            break;
        }
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: {
            const auto& info = binary_info->contextBinaryInfoV3;
            metadata.backend_id = info.backendId;
            metadata.build_id = info.buildId != nullptr ? info.buildId : "";
            metadata.core_api_version = version_to_string(info.coreApiVersion);
            metadata.backend_api_version = version_to_string(info.backendApiVersion);
            metadata.soc_version = info.socVersion != nullptr ? info.socVersion : "";
            metadata.context_blob_size = info.contextBlobSize;
            metadata.graph_count = info.numGraphs;
            metadata.soc_model = info.socModel;
            append_graph_names(info.graphs, info.numGraphs, &metadata.graph_names);
            break;
        }
        default:
            metadata.message = "QNN context binary metadata uses an unsupported version.";
            api.systemContextFree(sys_context);
            return metadata;
    }

    metadata.parsed = true;
    std::ostringstream message;
    message << "QNN context binary metadata parsed";
    if (!metadata.build_id.empty()) message << "; buildId=" << metadata.build_id;
    if (!metadata.soc_version.empty()) message << "; socVersion=" << metadata.soc_version;
    if (metadata.soc_model != 0) message << "; socModel=" << metadata.soc_model;
    metadata.message = message.str();
    api.systemContextFree(sys_context);
    return metadata;
}

QnnBinaryMetadata inspect_qnn_context_binary_metadata(
        const RuntimeProbe& runtime,
        const std::vector<uint8_t>& context_binary) {
    return inspect_qnn_context_binary_metadata(
        runtime,
        context_binary.empty() ? nullptr : context_binary.data(),
        context_binary.size()
    );
}

struct QnnLoadedRuntime {
    bool ok = false;
    void* rpc_handle = nullptr;
    void* system_handle = nullptr;
    void* htp_handle = nullptr;
    QnnInterfaceGetProvidersFn get_providers = nullptr;
    std::string message;

    QnnLoadedRuntime() = default;
    QnnLoadedRuntime(const QnnLoadedRuntime&) = delete;
    QnnLoadedRuntime& operator=(const QnnLoadedRuntime&) = delete;

    QnnLoadedRuntime(QnnLoadedRuntime&& other) noexcept {
        move_from(std::move(other));
    }

    QnnLoadedRuntime& operator=(QnnLoadedRuntime&& other) noexcept {
        if (this != &other) {
            close();
            move_from(std::move(other));
        }
        return *this;
    }

    ~QnnLoadedRuntime() { close(); }

    void close() {
        // QNN objects and log sessions must be released before this method is called.
        if (htp_handle != nullptr) {
            dlclose(htp_handle);
            htp_handle = nullptr;
        }
        if (system_handle != nullptr) {
            dlclose(system_handle);
            system_handle = nullptr;
        }
        if (rpc_handle != nullptr) {
            dlclose(rpc_handle);
            rpc_handle = nullptr;
        }
        get_providers = nullptr;
        ok = false;
    }

private:
    void move_from(QnnLoadedRuntime&& other) {
        ok = other.ok;
        rpc_handle = other.rpc_handle;
        system_handle = other.system_handle;
        htp_handle = other.htp_handle;
        get_providers = other.get_providers;
        message = std::move(other.message);
        other.ok = false;
        other.rpc_handle = nullptr;
        other.system_handle = nullptr;
        other.htp_handle = nullptr;
        other.get_providers = nullptr;
    }
};

QnnLoadedRuntime load_qnn_runtime_for_graph(const RuntimeProbe& runtime) {
    QnnLoadedRuntime loaded;
    if (!runtime.rpc_path.empty()) {
        loaded.rpc_handle = dlopen(runtime.rpc_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (loaded.rpc_handle == nullptr && runtime.rpc_present) {
            const char* error = dlerror();
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "dlopen libcdsprpc.so before graph load failed: %s",
                error ? error : "unknown error");
        }
    }
    loaded.system_handle = dlopen(runtime.system_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (loaded.system_handle == nullptr) {
        const char* error = dlerror();
        loaded.message = std::string("dlopen libQnnSystem.so failed: ") +
            (error ? error : "unknown error");
        return loaded;
    }
    loaded.htp_handle = dlopen(runtime.htp_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (loaded.htp_handle == nullptr) {
        const char* error = dlerror();
        loaded.message = std::string("dlopen libQnnHtp.so failed: ") +
            (error ? error : "unknown error");
        return loaded;
    }
    for (void* handle : {loaded.htp_handle, loaded.system_handle}) {
        if (handle == nullptr) continue;
        loaded.get_providers = reinterpret_cast<QnnInterfaceGetProvidersFn>(
            dlsym(handle, "QnnInterface_getProviders")
        );
        if (loaded.get_providers != nullptr) break;
    }
    if (loaded.get_providers == nullptr) {
        loaded.message = "QnnInterface_getProviders was not found in loaded QNN libraries.";
        return loaded;
    }
    loaded.ok = true;
    loaded.message = "QNN runtime loaded for graph execution.";
    return loaded;
}

const QnnInterface_t* select_qnn_interface(QnnInterfaceGetProvidersFn get_providers, std::string* error) {
    const QnnInterface_t** providers = nullptr;
    uint32_t provider_count = 0;
    const Qnn_ErrorHandle_t status = get_providers(&providers, &provider_count);
    if (status != QNN_SUCCESS || providers == nullptr || provider_count == 0) {
        std::ostringstream out;
        out << "QnnInterface_getProviders failed or returned no providers: 0x"
            << std::hex << status;
        *error = out.str();
        return nullptr;
    }
    for (uint32_t i = 0; i < provider_count; ++i) {
        const QnnInterface_t* provider = providers[i];
        if (provider == nullptr) continue;
        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        if (api.backendCreate != nullptr &&
            api.backendFree != nullptr &&
            api.contextCreateFromBinary != nullptr &&
            api.contextFree != nullptr &&
            api.graphRetrieve != nullptr &&
            api.graphExecute != nullptr) {
            return provider;
        }
    }
    *error = "No QNN provider exposes backend/context/graph execute functions.";
    return nullptr;
}

bool make_qnn_tensor(
        const NativeTensorPlan& plan,
        bool input,
        std::vector<uint32_t>* dimensions,
        std::vector<uint8_t>* buffer,
        Qnn_Tensor_t* tensor,
        std::string* error) {
    if (!plan.supported || plan.name.empty() || plan.shape.empty() || plan.byte_size <= 0) {
        *error = "Tensor plan is not bindable: " + plan.name;
        return false;
    }
    if (plan.byte_size > std::numeric_limits<uint32_t>::max()) {
        *error = "Tensor buffer exceeds QNN client buffer uint32 size: " + plan.name;
        return false;
    }
    Qnn_DataType_t qnn_type = QNN_DATATYPE_UNDEFINED;
    if (!qnn_data_type_from_string(plan.data_type, &qnn_type)) {
        *error = "Unsupported QNN tensor dtype in native runner: " + plan.data_type;
        return false;
    }
    *dimensions = plan.shape;
    buffer->assign(static_cast<size_t>(plan.byte_size), 0);

    Qnn_Tensor_t local = QNN_TENSOR_INIT;
    local.version = QNN_TENSOR_VERSION_1;
    local.v1.name = plan.name.c_str();
    local.v1.type = input ? QNN_TENSOR_TYPE_APP_WRITE : QNN_TENSOR_TYPE_APP_READ;
    local.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    local.v1.dataType = qnn_type;
    local.v1.rank = static_cast<uint32_t>(dimensions->size());
    local.v1.dimensions = dimensions->data();
    local.v1.memType = QNN_TENSORMEMTYPE_RAW;
    local.v1.clientBuf.data = buffer->data();
    local.v1.clientBuf.dataSize = static_cast<uint32_t>(buffer->size());
    *tensor = local;
    return true;
}

GraphSmokeResult run_typed_qnn_graph_smoke(
        const RuntimeProbe& runtime,
        const SmokeSpecProbe& smoke_spec,
        const std::string& context_binary_path,
        const std::string& smoke_spec_json) {
    GraphSmokeResult result;
    result.attempted = true;
    result.execution_stage = "graph_execution_starting";
    result.execution_runtime = runtime;

    const std::vector<uint8_t> context_binary = read_binary_file(context_binary_path);
    if (context_binary.empty()) {
        result.execution_stage = "context_binary_read_failed";
        result.message = "QNN context binary could not be read or is empty.";
        return result;
    }

    RuntimeProbe selected_runtime = runtime;
    uint32_t context_soc_model = 0;
    if (runtime.system_present) {
        context_soc_model = inspect_qnn_context_binary_metadata(runtime, context_binary).soc_model;
    }
    if (!select_qnn_runtime_profile_for_context(&selected_runtime, context_soc_model)) {
        result.execution_runtime = selected_runtime;
        result.execution_stage = "runtime_profile_missing";
        result.message = selected_runtime.message;
        return result;
    }
    result.execution_runtime = selected_runtime;
    result.execution_runtime_selected = true;

    auto loaded = load_qnn_runtime_for_graph(selected_runtime);
    if (!loaded.ok) {
        result.execution_stage = "runtime_load_failed";
        result.message = loaded.message;
        return result;
    }

    std::string provider_error;
    const QnnInterface_t* provider = select_qnn_interface(loaded.get_providers, &provider_error);
    if (provider == nullptr) {
        result.execution_stage = "qnn_provider_missing";
        result.message = provider_error;
        return result;
    }
    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    QnnLogSession log_session = create_qnn_log_session(api);

    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;

    const Qnn_ErrorHandle_t backend_status = api.backendCreate(log_session.handle, nullptr, &backend);
    if (backend_status != QNN_SUCCESS || backend == nullptr) {
        result.execution_stage = "backend_create_failed";
        result.message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
        return result;
    }
    result.backend_created = true;

    const QnnDeviceCreateOutcome device_outcome =
        create_qnn_device_or_default(api, context_soc_model, log_session.handle);
    device = device_outcome.device;
    result.device_created = device_outcome.created;
    result.context_default_device = device_outcome.context_default_device;
    result.device_message = device_outcome.message;
    if (device_outcome.fatal) {
        result.execution_stage = "device_create_failed";
        result.message = device_outcome.message;
        api.backendFree(backend);
        return result;
    }

    const auto context_start = std::chrono::steady_clock::now();
    const Qnn_ErrorHandle_t context_status = api.contextCreateFromBinary(
        backend,
        device,
        nullptr,
        context_binary.data(),
        static_cast<Qnn_ContextBinarySize_t>(context_binary.size()),
        &context,
        nullptr
    );
    if (context_status != QNN_SUCCESS || context == nullptr) {
        result.execution_stage = "context_load_failed";
        result.message = append_diagnostic_note(
            qnn_context_load_failure_message(api, context_status),
            result.device_message);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }
    const auto context_end = std::chrono::steady_clock::now();
    result.context_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        context_end - context_start
    ).count();
    result.context_loaded = true;

    Qnn_GraphHandle_t graph = nullptr;
    const Qnn_ErrorHandle_t graph_status = api.graphRetrieve(
        context,
        smoke_spec.graph_name.c_str(),
        &graph
    );
    if (graph_status != QNN_SUCCESS || graph == nullptr) {
        result.execution_stage = "graph_resolve_failed";
        result.message = "QNN graphRetrieve failed for '" + smoke_spec.graph_name +
            "': " + qnn_error_to_string(api, graph_status);
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }
    result.graph_resolved = true;

    auto get_system_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(loaded.system_handle, "QnnSystemInterface_getProviders")
    );
    if (get_system_providers == nullptr) {
        result.execution_stage = "qnn_system_provider_missing";
        result.message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }

    std::string system_provider_error;
    const QnnSystemInterface_t* system_provider =
        select_qnn_system_interface(get_system_providers, &system_provider_error);
    if (system_provider == nullptr) {
        result.execution_stage = "qnn_system_provider_missing";
        result.message = system_provider_error;
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }
    const auto& sys_api = system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;

    QnnSystemContext_Handle_t sys_context = nullptr;
    Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&sys_context);
    if (system_status != QNN_SUCCESS || sys_context == nullptr) {
        std::ostringstream message;
        message << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
        result.execution_stage = "qnn_metadata_context_create_failed";
        result.message = message.str();
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }

    const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
    if (sys_api.systemContextGetMetaData != nullptr) {
        system_status = sys_api.systemContextGetMetaData(
            sys_context,
            context_binary.data(),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info
        );
    } else {
        Qnn_ContextBinarySize_t binary_info_size = 0;
        system_status = sys_api.systemContextGetBinaryInfo(
            sys_context,
            const_cast<uint8_t*>(context_binary.data()),
            static_cast<uint64_t>(context_binary.size()),
            &binary_info,
            &binary_info_size
        );
    }
    if (system_status != QNN_SUCCESS || binary_info == nullptr) {
        std::ostringstream message;
        message << "QnnSystemContext metadata parse failed: 0x" << std::hex << system_status;
        result.execution_stage = "qnn_metadata_parse_failed";
        result.message = message.str();
        sys_api.systemContextFree(sys_context);
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }

    QnnSystemContext_GraphInfo_t* graphs = qnn_binary_graphs(*binary_info);
    const uint32_t graph_count = qnn_binary_graph_count(*binary_info);
    QnnGraphMetadata graph_meta;
    bool graph_meta_found = false;
    for (uint32_t i = 0; i < graph_count; ++i) {
        QnnGraphMetadata candidate = qnn_graph_metadata(graphs[i]);
        if (candidate.name != nullptr && smoke_spec.graph_name == candidate.name) {
            graph_meta = candidate;
            graph_meta_found = true;
            break;
        }
    }
    if (!graph_meta_found && graph_count > 0) {
        graph_meta = qnn_graph_metadata(graphs[0]);
        graph_meta_found = graph_meta.input_count > 0 && graph_meta.output_count > 0;
    }
    if (!graph_meta_found ||
        graph_meta.inputs == nullptr ||
        graph_meta.outputs == nullptr ||
        graph_meta.input_count == 0 ||
        graph_meta.output_count == 0) {
        result.execution_stage = "qnn_graph_metadata_missing";
        result.message = "QNN context metadata did not expose bindable graph inputs and outputs.";
        sys_api.systemContextFree(sys_context);
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }

    std::vector<Qnn_Tensor_t> inputs;
    std::vector<Qnn_Tensor_t> outputs;
    std::vector<std::vector<uint8_t>> input_buffers;
    std::vector<std::vector<uint8_t>> output_buffers;
    inputs.reserve(graph_meta.input_count);
    outputs.reserve(graph_meta.output_count);
    input_buffers.reserve(graph_meta.input_count);
    output_buffers.reserve(graph_meta.output_count);

    for (uint32_t i = 0; i < graph_meta.input_count; ++i) {
        Qnn_Tensor_t tensor = graph_meta.inputs[i];
        const uint64_t bytes = qnn_tensor_bytes(sys_api, tensor);
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            result.execution_stage = "tensor_bind_failed";
            result.message = "Invalid QNN metadata input tensor size for " +
                std::string(qnn_tensor_name(tensor) != nullptr ? qnn_tensor_name(tensor) : "<unnamed>");
            sys_api.systemContextFree(sys_context);
            api.contextFree(context, nullptr);
            if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
            api.backendFree(backend);
            return result;
        }
        input_buffers.emplace_back(static_cast<size_t>(bytes), 0);
        qnn_fill_smoke_input_buffer(tensor, &input_buffers.back());
        qnn_set_tensor_raw_buffer(
            &tensor,
            input_buffers.back().data(),
            static_cast<uint32_t>(input_buffers.back().size()));
        inputs.push_back(tensor);
    }
    for (uint32_t i = 0; i < graph_meta.output_count; ++i) {
        Qnn_Tensor_t tensor = graph_meta.outputs[i];
        const uint64_t bytes = qnn_tensor_bytes(sys_api, tensor);
        if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
            result.execution_stage = "tensor_bind_failed";
            result.message = "Invalid QNN metadata output tensor size for " +
                std::string(qnn_tensor_name(tensor) != nullptr ? qnn_tensor_name(tensor) : "<unnamed>");
            sys_api.systemContextFree(sys_context);
            api.contextFree(context, nullptr);
            if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
            api.backendFree(backend);
            return result;
        }
        output_buffers.emplace_back(static_cast<size_t>(bytes), 0);
        qnn_set_tensor_raw_buffer(
            &tensor,
            output_buffers.back().data(),
            static_cast<uint32_t>(output_buffers.back().size()));
        outputs.push_back(tensor);
    }
    result.tensors_bound = true;

    const auto execute_start = std::chrono::steady_clock::now();
    const Qnn_ErrorHandle_t execute_status = api.graphExecute(
        graph,
        inputs.data(),
        static_cast<uint32_t>(inputs.size()),
        outputs.data(),
        static_cast<uint32_t>(outputs.size()),
        nullptr,
        nullptr
    );
    if (execute_status != QNN_SUCCESS) {
        result.execution_stage = "graph_execute_failed";
        result.message = "QNN graphExecute failed: " + qnn_error_to_string(api, execute_status);
        sys_api.systemContextFree(sys_context);
        api.contextFree(context, nullptr);
        if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
        api.backendFree(backend);
        return result;
    }
    const auto execute_end = std::chrono::steady_clock::now();
    result.graph_execute_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        execute_end - execute_start
    ).count();

    uint64_t output_checksum = 0;
    for (const auto& buffer : output_buffers) {
        for (uint8_t value : buffer) output_checksum += value;
    }
    result.output_checksum = output_checksum;
    result.graph_executed = true;
    result.execution_stage = "graph_execute_passed";
    result.message = append_diagnostic_note(
        "QNN/HTP graphExecute completed successfully. outputChecksum=" +
            std::to_string(output_checksum),
        result.device_message);
    sys_api.systemContextFree(sys_context);
    api.contextFree(context, nullptr);
    if (device != nullptr && api.deviceFree != nullptr) api.deviceFree(device);
    api.backendFree(backend);
    return result;
}

uint64_t qnn_tensor_element_count(const Qnn_Tensor_t& tensor) {
    const uint32_t rank = qnn_tensor_rank(tensor);
    uint32_t* dims = qnn_tensor_dimensions(tensor);
    if (rank == 0 || dims == nullptr) return 0;
    uint64_t elements = 1;
    for (uint32_t i = 0; i < rank; ++i) {
        if (dims[i] == 0 || elements > std::numeric_limits<uint64_t>::max() / dims[i]) {
            return 0;
        }
        elements *= dims[i];
    }
    return elements;
}

void qnn_copy_tensor_dimensions(Qnn_Tensor_t* tensor, std::vector<uint32_t>* owned_dimensions) {
    owned_dimensions->clear();
    const uint32_t rank = qnn_tensor_rank(*tensor);
    uint32_t* source = qnn_tensor_dimensions(*tensor);
    if (rank == 0 || source == nullptr) return;
    owned_dimensions->assign(source, source + rank);
    if (tensor->version == QNN_TENSOR_VERSION_2) {
        tensor->v2.dimensions = owned_dimensions->data();
    } else {
        tensor->v1.dimensions = owned_dimensions->data();
    }
}

float qnn_dequantize_value(const Qnn_Tensor_t& tensor, int64_t quantized) {
    const auto quant = qnn_tensor_quantize_params(tensor);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        return (static_cast<float>(quantized) +
                static_cast<float>(quant.scaleOffsetEncoding.offset)) *
            quant.scaleOffsetEncoding.scale;
    }
    return static_cast<float>(quantized);
}

int64_t qnn_quantize_value(const Qnn_Tensor_t& tensor, float value) {
    const auto quant = qnn_tensor_quantize_params(tensor);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET &&
        quant.scaleOffsetEncoding.scale != 0.0f) {
        return static_cast<int64_t>(
            std::llround(value / quant.scaleOffsetEncoding.scale) -
            quant.scaleOffsetEncoding.offset
        );
    }
    return static_cast<int64_t>(std::llround(value));
}

std::string qnn_data_type_name(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_INT_8: return "int8";
        case QNN_DATATYPE_UINT_8: return "uint8";
        case QNN_DATATYPE_INT_16: return "int16";
        case QNN_DATATYPE_UINT_16: return "uint16";
        case QNN_DATATYPE_INT_32: return "int32";
        case QNN_DATATYPE_UINT_32: return "uint32";
        case QNN_DATATYPE_FLOAT_16: return "float16";
        case QNN_DATATYPE_BFLOAT_16: return "bfloat16";
        case QNN_DATATYPE_FLOAT_32: return "float32";
        case QNN_DATATYPE_UFIXED_POINT_16: return "ufixed16";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sfixed16";
        default: return "unknown";
    }
}

std::string qnn_quant_encoding_name(Qnn_QuantizationEncoding_t encoding) {
    switch (encoding) {
        case QNN_QUANTIZATION_ENCODING_SCALE_OFFSET: return "scale_offset";
        case QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET: return "axis_scale_offset";
        case QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET: return "bw_scale_offset";
        case QNN_QUANTIZATION_ENCODING_BW_AXIS_SCALE_OFFSET: return "bw_axis_scale_offset";
        case QNN_QUANTIZATION_ENCODING_BLOCK: return "block";
        case QNN_QUANTIZATION_ENCODING_BLOCKWISE_EXPANSION: return "blockwise_expansion";
        case QNN_QUANTIZATION_ENCODING_VECTOR: return "vector";
        case QNN_QUANTIZATION_ENCODING_FLOAT_BLOCK: return "float_block";
        case QNN_QUANTIZATION_ENCODING_MICROSCALING: return "microscaling";
        case QNN_QUANTIZATION_ENCODING_UNDEFINED: return "undefined";
        default: return "other";
    }
}

struct QnnTensorBinding {
    Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
    std::vector<uint32_t> dimensions;
    std::vector<uint8_t> buffer;
    std::string name;
};

bool qnn_write_float_tensor(QnnTensorBinding* binding, const float* values, size_t count, std::string* error) {
    if (binding == nullptr || values == nullptr) {
        *error = "Null tensor binding or float source.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0 || count < element_count) {
        *error = "Not enough float values for tensor " + binding->name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(float);
        if (binding->buffer.size() < bytes) {
            *error = "Float tensor buffer is too small for " + binding->name;
            return false;
        }
        std::memcpy(binding->buffer.data(), values, bytes);
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        auto* out = reinterpret_cast<uint16_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            int64_t q = qnn_quantize_value(binding->tensor, values[i]);
            if (q < 0) q = 0;
            if (q > 65535) q = 65535;
            out[i] = static_cast<uint16_t>(q);
        }
        return true;
    }
    if (type == QNN_DATATYPE_SFIXED_POINT_16 || type == QNN_DATATYPE_INT_16) {
        auto* out = reinterpret_cast<int16_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            int64_t q = qnn_quantize_value(binding->tensor, values[i]);
            if (q < std::numeric_limits<int16_t>::min()) q = std::numeric_limits<int16_t>::min();
            if (q > std::numeric_limits<int16_t>::max()) q = std::numeric_limits<int16_t>::max();
            out[i] = static_cast<int16_t>(q);
        }
        return true;
    }
    *error = "Unsupported float input tensor dtype for " + binding->name;
    return false;
}

bool qnn_write_int32_tensor(QnnTensorBinding* binding, int32_t value, std::string* error) {
    if (binding == nullptr) {
        *error = "Null int32 tensor binding.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0) {
        *error = "Invalid int32 tensor buffer for " + binding->name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    if (type == QNN_DATATYPE_INT_32) {
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(int32_t);
        if (binding->buffer.size() < bytes) {
            *error = "Int32 tensor buffer is too small for " + binding->name;
            return false;
        }
        auto* out = reinterpret_cast<int32_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) out[i] = value;
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(uint16_t);
        if (binding->buffer.size() < bytes) {
            *error = "Quantized timestep tensor buffer is too small for " + binding->name;
            return false;
        }
        int64_t quantized = qnn_quantize_value(binding->tensor, static_cast<float>(value));
        quantized = std::max<int64_t>(0, std::min<int64_t>(65535, quantized));
        auto* out = reinterpret_cast<uint16_t*>(binding->buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            out[i] = static_cast<uint16_t>(quantized);
        }
        return true;
    }
    *error = "Unsupported scalar integer tensor dtype for " + binding->name +
        ": " + qnn_data_type_name(type);
    return false;
}

bool qnn_write_int32_vector_tensor(
        QnnTensorBinding* binding,
        const int32_t* values,
        size_t count,
        std::string* error) {
    if (binding == nullptr || values == nullptr) {
        *error = "Null int32 tensor binding or source.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0 || count < element_count) {
        std::ostringstream message;
        message << "Not enough int32 values for tensor " << binding->name
                << ". Need " << element_count << ", got " << count << ".";
        *error = message.str();
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    if (type != QNN_DATATYPE_INT_32) {
        *error = "Token tensor is not int32: " + binding->name +
            " (dtype=" + qnn_data_type_name(type) + ")";
        return false;
    }
    const size_t bytes = static_cast<size_t>(element_count) * sizeof(int32_t);
    if (binding->buffer.size() < bytes) {
        *error = "Int32 vector tensor buffer is too small for " + binding->name;
        return false;
    }
    std::memcpy(binding->buffer.data(), values, bytes);
    return true;
}

bool qnn_read_float_tensor(const QnnTensorBinding& binding, std::vector<float>* out, std::string* error) {
    const uint64_t element_count = qnn_tensor_element_count(binding.tensor);
    if (element_count == 0) {
        *error = "Output tensor has no elements: " + binding.name;
        return false;
    }
    out->assign(static_cast<size_t>(element_count), 0.0f);
    const Qnn_DataType_t type = qnn_tensor_data_type(binding.tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(float);
        if (binding.buffer.size() < bytes) {
            *error = "Float output tensor buffer is too small for " + binding.name;
            return false;
        }
        std::memcpy(out->data(), binding.buffer.data(), bytes);
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        const auto* values = reinterpret_cast<const uint16_t*>(binding.buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            (*out)[static_cast<size_t>(i)] = qnn_dequantize_value(binding.tensor, values[i]);
        }
        return true;
    }
    if (type == QNN_DATATYPE_SFIXED_POINT_16 || type == QNN_DATATYPE_INT_16) {
        const auto* values = reinterpret_cast<const int16_t*>(binding.buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            (*out)[static_cast<size_t>(i)] = qnn_dequantize_value(binding.tensor, values[i]);
        }
        return true;
    }
    *error = "Unsupported output tensor dtype for " + binding.name;
    return false;
}

int tensor_index_by_name(const std::vector<QnnTensorBinding>& tensors, const std::vector<std::string>& tokens) {
    for (size_t i = 0; i < tensors.size(); ++i) {
        std::string lower = tensors[i].name;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        for (const auto& token : tokens) {
            if (lower.find(token) != std::string::npos) return static_cast<int>(i);
        }
    }
    return -1;
}

struct QnnCoherentRuntimeSession {
    QnnLoadedRuntime loaded;
    RuntimeProbe selected_runtime;
    const QnnInterface_t* provider = nullptr;
    const QnnSystemInterface_t* system_provider = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    QnnLogSession log_session;
    bool device_created = false;
    bool context_default_device = false;
    uint32_t context_soc_model = 0;
    const QnnImageGenerationScope* generation = nullptr;
    std::string device_message;
    std::string message;

    QnnCoherentRuntimeSession() = default;
    QnnCoherentRuntimeSession(const QnnCoherentRuntimeSession&) = delete;
    QnnCoherentRuntimeSession& operator=(const QnnCoherentRuntimeSession&) = delete;
    ~QnnCoherentRuntimeSession() { close(); }

    bool open(
            const RuntimeProbe& runtime,
            const std::string& first_context_path,
            const QnnImageGenerationScope* generation_scope) {
        generation = generation_scope;
        ScopedReadOnlyMmap context_probe;
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::UnetBinaryMmap,
                kQnnImageContextBinaryMmap);
        }
        if (!context_probe.open_file(first_context_path, &message)) return false;
        context_soc_model = runtime.system_present
            ? inspect_qnn_context_binary_metadata(
                runtime,
                context_probe.data(),
                context_probe.size()).soc_model
            : 0;
        selected_runtime = runtime;
        if (!select_qnn_runtime_profile_for_context(&selected_runtime, context_soc_model)) {
            message = selected_runtime.message;
            return false;
        }

        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::RuntimeLoadBefore,
                kQnnImageContextCreate);
        }
        loaded = load_qnn_runtime_for_graph(selected_runtime);
        if (!loaded.ok) {
            message = loaded.message;
            return false;
        }
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::RuntimeLoadAfter,
                kQnnImageContextCreate);
        }

        std::string provider_error;
        provider = select_qnn_interface(loaded.get_providers, &provider_error);
        if (provider == nullptr) {
            message = provider_error;
            return false;
        }
        auto get_system_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
            dlsym(loaded.system_handle, "QnnSystemInterface_getProviders")
        );
        if (get_system_providers == nullptr) {
            message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
            return false;
        }
        std::string system_provider_error;
        system_provider = select_qnn_system_interface(
            get_system_providers,
            &system_provider_error);
        if (system_provider == nullptr) {
            message = system_provider_error;
            return false;
        }

        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        log_session = create_qnn_log_session(api);
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::BackendCreateBefore,
                kQnnImageContextCreate);
        }
        const Qnn_ErrorHandle_t backend_status = api.backendCreate(
            log_session.handle,
            nullptr,
            &backend);
        if (backend_status != QNN_SUCCESS || backend == nullptr) {
            message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
            return false;
        }
        if (generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::BackendCreateAfter,
                kQnnImageContextCreate);
        }

        const QnnDeviceCreateOutcome device_outcome =
            create_qnn_device_or_default(api, context_soc_model, log_session.handle);
        device = device_outcome.device;
        device_created = device_outcome.created;
        context_default_device = device_outcome.context_default_device;
        device_message = device_outcome.message;
        if (device_outcome.fatal) {
            message = device_outcome.message;
            return false;
        }
        return true;
    }

    bool accepts_context(const RuntimeProbe& runtime, const ScopedReadOnlyMmap& binary) {
        const uint32_t soc_model = runtime.system_present
            ? inspect_qnn_context_binary_metadata(runtime, binary.data(), binary.size()).soc_model
            : 0;
        RuntimeProbe candidate = runtime;
        if (!select_qnn_runtime_profile_for_context(&candidate, soc_model)) {
            message = candidate.message;
            return false;
        }
        const bool coherent =
            candidate.system_path == selected_runtime.system_path &&
            candidate.htp_path == selected_runtime.htp_path &&
            candidate.rpc_path == selected_runtime.rpc_path &&
            candidate.skel_path == selected_runtime.skel_path &&
            candidate.stub_path == selected_runtime.stub_path &&
            candidate.adsp_library_path == selected_runtime.adsp_library_path &&
            candidate.htp_arch_version == selected_runtime.htp_arch_version;
        if (!coherent) {
            message = "UNet and VAE context binaries resolve to different QNN runtime profiles.";
        }
        return coherent;
    }

    void close() {
        if (device != nullptr && provider != nullptr &&
            provider->QNN_INTERFACE_VER_NAME.deviceFree != nullptr) {
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::DeviceReleaseBefore,
                    kQnnImageContextRelease);
            }
            provider->QNN_INTERFACE_VER_NAME.deviceFree(device);
            device = nullptr;
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::DeviceReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (backend != nullptr && provider != nullptr) {
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::BackendReleaseBefore,
                    kQnnImageContextRelease);
            }
            provider->QNN_INTERFACE_VER_NAME.backendFree(backend);
            backend = nullptr;
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::BackendReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (log_session.handle != nullptr && generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::LogReleaseBefore,
                kQnnImageContextRelease);
        }
        const bool had_log = log_session.handle != nullptr;
        log_session.reset();
        if (had_log && generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::LogReleaseAfter,
                kQnnImageContextRelease);
        }
        if (loaded.ok || loaded.htp_handle != nullptr || loaded.system_handle != nullptr ||
            loaded.rpc_handle != nullptr) {
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::RuntimeUnloadBefore,
                    kQnnImageContextRelease);
            }
            loaded.close();
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::RuntimeUnloadAfter,
                    kQnnImageContextRelease);
            }
        }
        provider = nullptr;
        system_provider = nullptr;
    }
};

struct QnnExecutableGraph {
    QnnLoadedRuntime loaded;
    const QnnInterface_t* provider = nullptr;
    const QnnSystemInterface_t* system_provider = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    QnnSystemContext_Handle_t system_context = nullptr;
    QnnLogSession log_session;
    bool device_created = false;
    bool context_default_device = false;
    ScopedReadOnlyMmap context_binary;
    const QnnImageGenerationScope* generation = nullptr;
    QnnCoherentRuntimeSession* shared_session = nullptr;
    bool vae_graph = false;
    RuntimeProbe selected_runtime;
    std::vector<QnnTensorBinding> inputs;
    std::vector<QnnTensorBinding> outputs;
    std::string graph_name;
    long long context_load_ms = 0;
    std::string device_message;
    std::string message;

    ~QnnExecutableGraph() { close(); }

    void close() {
        const bool has_resources = system_context != nullptr || context != nullptr ||
            device != nullptr || backend != nullptr || !context_binary.empty();
        if (has_resources && generation != nullptr) {
            generation->set_phase(kQnnImageContextRelease);
        }
        const bool had_graph = graph != nullptr;
        if (had_graph && generation != nullptr && shared_session != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeGraphReleaseBefore
                    : mca::qnn::ImageStage::UnetGraphReleaseBefore,
                kQnnImageContextRelease);
        }
        inputs.clear();
        outputs.clear();
        graph = nullptr;
        if (had_graph && generation != nullptr && shared_session != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeGraphReleaseAfter
                    : mca::qnn::ImageStage::UnetGraphReleaseAfter,
                kQnnImageContextRelease);
        }
        if (system_context != nullptr && system_provider != nullptr) {
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeMetadataReleaseBefore
                        : mca::qnn::ImageStage::UnetMetadataReleaseBefore,
                    kQnnImageContextRelease);
            }
            system_provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree(system_context);
            system_context = nullptr;
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeMetadataReleaseAfter
                        : mca::qnn::ImageStage::UnetMetadataReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (context != nullptr && provider != nullptr) {
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeContextReleaseBefore
                        : mca::qnn::ImageStage::UnetContextReleaseBefore,
                    kQnnImageContextRelease);
            }
            provider->QNN_INTERFACE_VER_NAME.contextFree(context, nullptr);
            context = nullptr;
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeContextReleaseAfter
                        : mca::qnn::ImageStage::UnetContextReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (shared_session == nullptr && device != nullptr && provider != nullptr && provider->QNN_INTERFACE_VER_NAME.deviceFree != nullptr) {
            provider->QNN_INTERFACE_VER_NAME.deviceFree(device);
            device = nullptr;
        }
        if (shared_session == nullptr && backend != nullptr && provider != nullptr) {
            provider->QNN_INTERFACE_VER_NAME.backendFree(backend);
            backend = nullptr;
        }
        if (shared_session == nullptr) {
            log_session.reset();
            loaded.close();
        }
        context_binary.close();
        provider = nullptr;
        system_provider = nullptr;
        shared_session = nullptr;
    }

    bool load_in_session(
            QnnCoherentRuntimeSession* session,
            const RuntimeProbe& runtime,
            const std::string& context_path,
            const std::string& requested_graph_name,
            bool is_vae) {
        shared_session = session;
        generation = session != nullptr ? session->generation : nullptr;
        vae_graph = is_vae;
        if (session == nullptr || session->provider == nullptr || session->backend == nullptr) {
            message = "QNN coherent runtime session is not ready.";
            return false;
        }
        provider = session->provider;
        system_provider = session->system_provider;
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeBinaryMmap
                    : mca::qnn::ImageStage::UnetBinaryMmap,
                kQnnImageContextBinaryMmap);
        }
        if (!context_binary.open_file(context_path, &message)) return false;
        if (!session->accepts_context(runtime, context_binary)) {
            message = session->message;
            return false;
        }

        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateBefore
                    : mca::qnn::ImageStage::UnetContextCreateBefore,
                kQnnImageContextCreate);
        }
        const auto context_start = std::chrono::steady_clock::now();
        const Qnn_ErrorHandle_t context_status = api.contextCreateFromBinary(
            session->backend,
            session->device,
            nullptr,
            context_binary.data(),
            static_cast<Qnn_ContextBinarySize_t>(context_binary.size()),
            &context,
            nullptr);
        if (context_status != QNN_SUCCESS || context == nullptr) {
            message = append_diagnostic_note(
                qnn_context_load_failure_message(api, context_status),
                session->device_message);
            return false;
        }
        context_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - context_start).count();
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateAfter
                    : mca::qnn::ImageStage::UnetContextCreateAfter,
                kQnnImageContextCreate);
        }

        const auto& sys_api = system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
        Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&system_context);
        if (system_status != QNN_SUCCESS || system_context == nullptr) {
            std::ostringstream out;
            out << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
            message = out.str();
            return false;
        }
        const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
        if (sys_api.systemContextGetMetaData != nullptr) {
            system_status = sys_api.systemContextGetMetaData(
                system_context,
                context_binary.data(),
                static_cast<uint64_t>(context_binary.size()),
                &binary_info);
        } else {
            Qnn_ContextBinarySize_t binary_info_size = 0;
            system_status = sys_api.systemContextGetBinaryInfo(
                system_context,
                const_cast<uint8_t*>(context_binary.data()),
                static_cast<uint64_t>(context_binary.size()),
                &binary_info,
                &binary_info_size);
        }
        if (system_status != QNN_SUCCESS || binary_info == nullptr) {
            std::ostringstream out;
            out << "QNN metadata parse failed: 0x" << std::hex << system_status;
            message = out.str();
            return false;
        }

        QnnSystemContext_GraphInfo_t* graphs = qnn_binary_graphs(*binary_info);
        const uint32_t graph_count = qnn_binary_graph_count(*binary_info);
        QnnGraphMetadata graph_meta;
        bool graph_meta_found = false;
        for (uint32_t i = 0; i < graph_count; ++i) {
            QnnGraphMetadata candidate = qnn_graph_metadata(graphs[i]);
            if (candidate.name != nullptr &&
                (requested_graph_name.empty() || requested_graph_name == candidate.name)) {
                graph_meta = candidate;
                graph_meta_found = true;
                break;
            }
        }
        if (!graph_meta_found && graph_count > 0) {
            graph_meta = qnn_graph_metadata(graphs[0]);
            graph_meta_found = graph_meta.name != nullptr;
        }
        if (!graph_meta_found || graph_meta.inputs == nullptr || graph_meta.outputs == nullptr) {
            message = "QNN graph metadata missing.";
            return false;
        }
        graph_name = graph_meta.name != nullptr ? graph_meta.name : requested_graph_name;
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveBefore
                    : mca::qnn::ImageStage::UnetGraphRetrieveBefore,
                kQnnImageContextCreate);
        }
        const Qnn_ErrorHandle_t graph_status = api.graphRetrieve(context, graph_name.c_str(), &graph);
        if (graph_status != QNN_SUCCESS || graph == nullptr) {
            message = "QNN graphRetrieve failed for '" + graph_name + "': " +
                qnn_error_to_string(api, graph_status);
            return false;
        }
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveAfter
                    : mca::qnn::ImageStage::UnetGraphRetrieveAfter,
                kQnnImageContextCreate);
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindBefore
                    : mca::qnn::ImageStage::UnetTensorBindBefore,
                kQnnImageContextCreate);
        }
        if (!bind_tensors(sys_api, graph_meta.inputs, graph_meta.input_count, true, &inputs) ||
            !bind_tensors(sys_api, graph_meta.outputs, graph_meta.output_count, false, &outputs)) {
            return false;
        }
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindAfter
                    : mca::qnn::ImageStage::UnetTensorBindAfter,
                kQnnImageContextCreate);
        }
        return true;
    }

    bool load(
            const RuntimeProbe& runtime,
            const std::string& context_path,
            const std::string& requested_graph_name,
            const QnnImageGenerationScope* generation_scope = nullptr,
            bool is_vae = false) {
        generation = generation_scope;
        vae_graph = is_vae;
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeBinaryMmap
                    : mca::qnn::ImageStage::UnetBinaryMmap,
                kQnnImageContextBinaryMmap);
        }
        if (!context_binary.open_file(context_path, &message)) {
            return false;
        }
        selected_runtime = runtime;
        uint32_t context_soc_model = 0;
        if (runtime.system_present) {
            context_soc_model = inspect_qnn_context_binary_metadata(
                runtime,
                context_binary.data(),
                context_binary.size()
            ).soc_model;
        }
        if (!select_qnn_runtime_profile_for_context(&selected_runtime, context_soc_model)) {
            message = selected_runtime.message;
            return false;
        }

        loaded = load_qnn_runtime_for_graph(selected_runtime);
        if (!loaded.ok) {
            message = loaded.message;
            return false;
        }
        std::string provider_error;
        provider = select_qnn_interface(loaded.get_providers, &provider_error);
        if (provider == nullptr) {
            message = provider_error;
            return false;
        }
        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        log_session = create_qnn_log_session(api);
        const Qnn_ErrorHandle_t backend_status = api.backendCreate(log_session.handle, nullptr, &backend);
        if (backend_status != QNN_SUCCESS || backend == nullptr) {
            message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
            return false;
        }
        const QnnDeviceCreateOutcome device_outcome =
            create_qnn_device_or_default(api, context_soc_model, log_session.handle);
        device = device_outcome.device;
        device_created = device_outcome.created;
        context_default_device = device_outcome.context_default_device;
        device_message = device_outcome.message;
        if (device_outcome.fatal) {
            message = device_outcome.message;
            return false;
        }
        const auto context_start = std::chrono::steady_clock::now();
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateBefore
                    : mca::qnn::ImageStage::UnetContextCreateBefore,
                kQnnImageContextCreate);
        }
        const Qnn_ErrorHandle_t context_status = api.contextCreateFromBinary(
            backend,
            device,
            nullptr,
            context_binary.data(),
            static_cast<Qnn_ContextBinarySize_t>(context_binary.size()),
            &context,
            nullptr
        );
        if (context_status != QNN_SUCCESS || context == nullptr) {
            message = append_diagnostic_note(
                qnn_context_load_failure_message(api, context_status),
                device_message);
            return false;
        }
        context_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - context_start
        ).count();
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeContextCreateAfter
                    : mca::qnn::ImageStage::UnetContextCreateAfter,
                kQnnImageContextCreate);
        }

        auto get_system_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
            dlsym(loaded.system_handle, "QnnSystemInterface_getProviders")
        );
        if (get_system_providers == nullptr) {
            message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
            return false;
        }
        std::string system_provider_error;
        system_provider = select_qnn_system_interface(get_system_providers, &system_provider_error);
        if (system_provider == nullptr) {
            message = system_provider_error;
            return false;
        }
        const auto& sys_api = system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
        Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&system_context);
        if (system_status != QNN_SUCCESS || system_context == nullptr) {
            std::ostringstream out;
            out << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
            message = out.str();
            return false;
        }
        const QnnSystemContext_BinaryInfo_t* binary_info = nullptr;
        if (sys_api.systemContextGetMetaData != nullptr) {
            system_status = sys_api.systemContextGetMetaData(
                system_context,
                context_binary.data(),
                static_cast<uint64_t>(context_binary.size()),
                &binary_info
            );
        } else {
            Qnn_ContextBinarySize_t binary_info_size = 0;
            system_status = sys_api.systemContextGetBinaryInfo(
                system_context,
                const_cast<uint8_t*>(context_binary.data()),
                static_cast<uint64_t>(context_binary.size()),
                &binary_info,
                &binary_info_size
            );
        }
        if (system_status != QNN_SUCCESS || binary_info == nullptr) {
            std::ostringstream out;
            out << "QNN metadata parse failed: 0x" << std::hex << system_status;
            message = out.str();
            return false;
        }

        QnnSystemContext_GraphInfo_t* graphs = qnn_binary_graphs(*binary_info);
        const uint32_t graph_count = qnn_binary_graph_count(*binary_info);
        QnnGraphMetadata graph_meta;
        bool graph_meta_found = false;
        for (uint32_t i = 0; i < graph_count; ++i) {
            QnnGraphMetadata candidate = qnn_graph_metadata(graphs[i]);
            if (candidate.name != nullptr &&
                (requested_graph_name.empty() || requested_graph_name == candidate.name)) {
                graph_meta = candidate;
                graph_meta_found = true;
                break;
            }
        }
        if (!graph_meta_found && graph_count > 0) {
            graph_meta = qnn_graph_metadata(graphs[0]);
            graph_meta_found = graph_meta.name != nullptr;
        }
        if (!graph_meta_found || graph_meta.inputs == nullptr || graph_meta.outputs == nullptr) {
            message = "QNN graph metadata missing.";
            return false;
        }
        graph_name = graph_meta.name != nullptr ? graph_meta.name : requested_graph_name;
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveBefore
                    : mca::qnn::ImageStage::UnetGraphRetrieveBefore,
                kQnnImageContextCreate);
        }
        const Qnn_ErrorHandle_t graph_status = api.graphRetrieve(context, graph_name.c_str(), &graph);
        if (graph_status != QNN_SUCCESS || graph == nullptr) {
            message = "QNN graphRetrieve failed for '" + graph_name + "': " +
                qnn_error_to_string(api, graph_status);
            return false;
        }
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeGraphRetrieveAfter
                    : mca::qnn::ImageStage::UnetGraphRetrieveAfter,
                kQnnImageContextCreate);
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindBefore
                    : mca::qnn::ImageStage::UnetTensorBindBefore,
                kQnnImageContextCreate);
        }

        if (!bind_tensors(sys_api, graph_meta.inputs, graph_meta.input_count, true, &inputs)) return false;
        if (!bind_tensors(sys_api, graph_meta.outputs, graph_meta.output_count, false, &outputs)) return false;
        if (generation != nullptr) {
            generation->record_stage(
                is_vae
                    ? mca::qnn::ImageStage::VaeTensorBindAfter
                    : mca::qnn::ImageStage::UnetTensorBindAfter,
                kQnnImageContextCreate);
        }
        return true;
    }

    bool bind_tensors(
            const QNN_SYSTEM_INTERFACE_VER_TYPE& sys_api,
            Qnn_Tensor_t* source,
            uint32_t count,
            bool input,
            std::vector<QnnTensorBinding>* destination) {
        destination->clear();
        destination->reserve(count);
        for (uint32_t i = 0; i < count; ++i) {
            QnnTensorBinding binding;
            binding.tensor = source[i];
            binding.name = qnn_tensor_name(binding.tensor) != nullptr
                ? qnn_tensor_name(binding.tensor)
                : "";
            qnn_copy_tensor_dimensions(&binding.tensor, &binding.dimensions);
            const uint64_t bytes = qnn_tensor_bytes(sys_api, binding.tensor);
            if (bytes == 0 || bytes > std::numeric_limits<uint32_t>::max()) {
                message = std::string("Invalid tensor buffer size for ") +
                    (input ? "input " : "output ") + binding.name;
                return false;
            }
            binding.buffer.assign(static_cast<size_t>(bytes), 0);
            qnn_set_tensor_raw_buffer(
                &binding.tensor,
                binding.buffer.data(),
                static_cast<uint32_t>(binding.buffer.size()));
            destination->push_back(std::move(binding));
        }
        return true;
    }

    bool execute(long long* execute_ms, std::string* error) {
        if (provider == nullptr || graph == nullptr) {
            *error = "QNN executable graph is not loaded.";
            return false;
        }
        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        std::vector<Qnn_Tensor_t> input_tensors;
        std::vector<Qnn_Tensor_t> output_tensors;
        input_tensors.reserve(inputs.size());
        output_tensors.reserve(outputs.size());
        for (const auto& binding : inputs) input_tensors.push_back(binding.tensor);
        for (const auto& binding : outputs) output_tensors.push_back(binding.tensor);
        if (generation != nullptr) {
            generation->record_stage(
                vae_graph
                    ? mca::qnn::ImageStage::VaeGraphExecute
                    : mca::qnn::ImageStage::UnetGraphExecute,
                kQnnImageGraphExecute);
        }
        const auto start = std::chrono::steady_clock::now();
        const Qnn_ErrorHandle_t status = api.graphExecute(
            graph,
            input_tensors.data(),
            static_cast<uint32_t>(input_tensors.size()),
            output_tensors.data(),
            static_cast<uint32_t>(output_tensors.size()),
            nullptr,
            nullptr
        );
        *execute_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start
        ).count();
        if (status != QNN_SUCCESS) {
            *error = "QNN graphExecute failed: " + qnn_error_to_string(api, status);
            return false;
        }
        return true;
    }
};

uint64_t checksum_float_vector(const std::vector<float>& values) {
    uint64_t checksum = 0;
    for (float value : values) {
        uint32_t bits = 0;
        std::memcpy(&bits, &value, sizeof(bits));
        checksum += bits;
    }
    return checksum;
}

std::string float_vector_stats_json(const std::vector<float>& values) {
    float min_value = std::numeric_limits<float>::infinity();
    float max_value = -std::numeric_limits<float>::infinity();
    long double sum = 0.0;
    uint64_t finite = 0;
    for (float value : values) {
        if (!std::isfinite(value)) continue;
        min_value = std::min(min_value, value);
        max_value = std::max(max_value, value);
        sum += value;
        ++finite;
    }
    std::ostringstream out;
    out << "{"
        << "\"count\":" << values.size() << ","
        << "\"finite\":" << finite << ",";
    if (finite == 0) {
        out << "\"min\":0,\"max\":0,\"mean\":0";
    } else {
        out << "\"min\":" << static_cast<double>(min_value) << ","
            << "\"max\":" << static_cast<double>(max_value) << ","
            << "\"mean\":" << static_cast<double>(sum / static_cast<long double>(finite));
    }
    out << "}";
    return out.str();
}

std::string qnn_tensor_shape_json(const QnnTensorBinding& binding) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < binding.dimensions.size(); ++i) {
        if (i > 0) out << ",";
        out << binding.dimensions[i];
    }
    out << "]";
    return out.str();
}

std::string qnn_tensor_debug_json(const QnnTensorBinding& binding) {
    const Qnn_DataType_t type = qnn_tensor_data_type(binding.tensor);
    const auto quant = qnn_tensor_quantize_params(binding.tensor);
    std::ostringstream out;
    out << "{"
        << "\"name\":" << quote(binding.name) << ","
        << "\"dataType\":" << quote(qnn_data_type_name(type)) << ","
        << "\"dataTypeValue\":" << static_cast<int>(type) << ","
        << "\"shape\":" << qnn_tensor_shape_json(binding) << ","
        << "\"bytes\":" << binding.buffer.size() << ","
        << "\"quantEncoding\":" << quote(qnn_quant_encoding_name(quant.quantizationEncoding)) << ","
        << "\"quantEncodingValue\":" << static_cast<int>(quant.quantizationEncoding);
    if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        out << ",\"scale\":" << quant.scaleOffsetEncoding.scale
            << ",\"offset\":" << quant.scaleOffsetEncoding.offset;
    }
    out << "}";
    return out.str();
}

std::string qnn_tensor_list_debug_json(const std::vector<QnnTensorBinding>& tensors) {
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < tensors.size(); ++i) {
        if (i > 0) out << ",";
        out << qnn_tensor_debug_json(tensors[i]);
    }
    out << "]";
    return out.str();
}

bool write_vae_tensor_png(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        int* width,
        int* height,
        std::string* error) {
    if (tensor.dimensions.size() != 4) {
        *error = "VAE output tensor is expected to be rank 4.";
        return false;
    }
    const int n = static_cast<int>(tensor.dimensions[0]);
    (void)n;
    int channels = 0;
    bool nchw = false;
    if (tensor.dimensions[1] == 3) {
        nchw = true;
        channels = 3;
        *height = static_cast<int>(tensor.dimensions[2]);
        *width = static_cast<int>(tensor.dimensions[3]);
    } else if (tensor.dimensions[3] == 3) {
        nchw = false;
        channels = 3;
        *height = static_cast<int>(tensor.dimensions[1]);
        *width = static_cast<int>(tensor.dimensions[2]);
    } else {
        *error = "VAE output tensor does not expose 3 RGB channels.";
        return false;
    }
    if (*width <= 0 || *height <= 0 || channels != 3) {
        *error = "Invalid VAE output dimensions.";
        return false;
    }
    const size_t expected = static_cast<size_t>(*width) * static_cast<size_t>(*height) * 3;
    if (values.size() < expected) {
        *error = "VAE output float buffer is smaller than expected RGB size.";
        return false;
    }

    float min_value = std::numeric_limits<float>::infinity();
    float max_value = -std::numeric_limits<float>::infinity();
    for (float value : values) {
        if (!std::isfinite(value)) continue;
        min_value = std::min(min_value, value);
        max_value = std::max(max_value, value);
    }
    const bool looks_zero_to_one = min_value >= -0.05f && max_value <= 1.05f;
    std::vector<uint8_t> rgb(expected, 0);
    auto sample = [&](int c, int y, int x) -> float {
        if (nchw) {
            return values[static_cast<size_t>(c) * (*height) * (*width) +
                          static_cast<size_t>(y) * (*width) + x];
        }
        return values[(static_cast<size_t>(y) * (*width) + x) * 3 + c];
    };
    for (int y = 0; y < *height; ++y) {
        for (int x = 0; x < *width; ++x) {
            for (int c = 0; c < 3; ++c) {
                float value = sample(c, y, x);
                if (!std::isfinite(value)) value = 0.0f;
                const float normalized = looks_zero_to_one
                    ? value
                    : value * 0.5f + 0.5f;
                const int byte_value = static_cast<int>(std::lround(
                    std::max(0.0f, std::min(1.0f, normalized)) * 255.0f));
                rgb[(static_cast<size_t>(y) * (*width) + x) * 3 + c] =
                    static_cast<uint8_t>(byte_value);
            }
        }
    }
    if (stbi_write_png(output_path.c_str(), *width, *height, 3, rgb.data(), (*width) * 3) == 0) {
        *error = "Failed to write PNG output: " + output_path;
        return false;
    }
    return true;
}

bool read_float_binary_file(
        const std::string& path,
        std::vector<float>* values,
        std::string* error) {
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0 || bytes % static_cast<long long>(sizeof(float)) != 0) {
        *error = "Embedding file is missing, empty, or not f32 aligned: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open embedding file: " + path;
        return false;
    }
    values->assign(static_cast<size_t>(bytes / static_cast<long long>(sizeof(float))), 0.0f);
    input.read(
        reinterpret_cast<char*>(values->data()),
        static_cast<std::streamsize>(bytes));
    if (!input.good()) {
        *error = "Failed to read embedding file: " + path;
        return false;
    }
    return true;
}

bool read_int32_binary_file(
        const std::string& path,
        std::vector<int32_t>* values,
        std::string* error) {
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0 || bytes % static_cast<long long>(sizeof(int32_t)) != 0) {
        *error = "Token file is missing, empty, or not int32 aligned: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open token file: " + path;
        return false;
    }
    values->assign(
        static_cast<size_t>(bytes / static_cast<long long>(sizeof(int32_t))),
        0);
    input.read(
        reinterpret_cast<char*>(values->data()),
        static_cast<std::streamsize>(bytes));
    if (!input.good()) {
        *error = "Failed to read token file: " + path;
        return false;
    }
    return true;
}

std::vector<float> sd15_pndm_alphas() {
    constexpr int train_timesteps = 1000;
    constexpr float beta_start = 0.00085f;
    constexpr float beta_end = 0.012f;
    std::vector<float> alphas(train_timesteps, 1.0f);
    float product = 1.0f;
    const float sqrt_start = std::sqrt(beta_start);
    const float sqrt_end = std::sqrt(beta_end);
    for (int i = 0; i < train_timesteps; ++i) {
        const float t = train_timesteps <= 1
            ? 0.0f
            : static_cast<float>(i) / static_cast<float>(train_timesteps - 1);
        const float beta_root = sqrt_start + (sqrt_end - sqrt_start) * t;
        const float alpha = 1.0f - beta_root * beta_root;
        product *= alpha;
        alphas[static_cast<size_t>(i)] = product;
    }
    return alphas;
}

std::vector<float> qnn_pndm_step(
        const std::vector<float>& sample,
        std::vector<std::vector<float>>& ets,
        const std::vector<float>& model_output,
        int index,
        const std::vector<int>& timesteps,
        const std::vector<float>& alphas) {
    int timestep = timesteps[static_cast<size_t>(index)];
    int prev_timestep = 0;
    if (index + 1 < static_cast<int>(timesteps.size())) {
        prev_timestep = timesteps[static_cast<size_t>(index + 1)];
    }
    std::vector<float> adjusted = model_output;
    if (index != 1) {
        if (ets.size() >= 4) {
            ets.erase(ets.begin());
        }
        ets.push_back(model_output);
    } else {
        timestep = timesteps[0];
        prev_timestep = timesteps[1];
    }
    const int ets_index = static_cast<int>(ets.size()) - 1;
    if (index == 1) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] = (adjusted[i] + ets[static_cast<size_t>(ets_index)][i]) * 0.5f;
        }
    } else if (ets_index == 1) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] =
                (3.0f * ets[static_cast<size_t>(ets_index)][i] -
                 ets[static_cast<size_t>(ets_index - 1)][i]) * 0.5f;
        }
    } else if (ets_index == 2) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] =
                (23.0f * ets[static_cast<size_t>(ets_index)][i] -
                 16.0f * ets[static_cast<size_t>(ets_index - 1)][i] +
                 5.0f * ets[static_cast<size_t>(ets_index - 2)][i]) / 12.0f;
        }
    } else if (ets_index >= 3) {
        for (size_t i = 0; i < adjusted.size(); ++i) {
            adjusted[i] =
                (55.0f * ets[static_cast<size_t>(ets_index)][i] -
                 59.0f * ets[static_cast<size_t>(ets_index - 1)][i] +
                 37.0f * ets[static_cast<size_t>(ets_index - 2)][i] -
                 9.0f * ets[static_cast<size_t>(ets_index - 3)][i]) / 24.0f;
        }
    }
    const int last_alpha = static_cast<int>(alphas.size()) - 1;
    const float alpha_prod_t = alphas[static_cast<size_t>(std::max(0, std::min(last_alpha, timestep)))];
    const float alpha_prod_prev = alphas[static_cast<size_t>(std::max(0, std::min(last_alpha, prev_timestep)))];
    const float beta_prod_t = 1.0f - alpha_prod_t;
    const float beta_prod_prev = 1.0f - alpha_prod_prev;
    const float sample_coeff = std::sqrt(alpha_prod_prev / alpha_prod_t);
    const float denom =
        alpha_prod_t * std::sqrt(beta_prod_prev) +
        std::sqrt(alpha_prod_t * beta_prod_t * alpha_prod_prev);
    const float model_coeff = (alpha_prod_prev - alpha_prod_t) / denom;
    std::vector<float> previous(sample.size(), 0.0f);
    for (size_t i = 0; i < sample.size(); ++i) {
        previous[i] = sample_coeff * sample[i] - model_coeff * adjusted[i];
    }
    return previous;
}

bool qnn_run_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const std::vector<float>& latent,
        int timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_float_tensor(&unet.inputs[sample_index], latent.data(), latent.size(), error) ||
        !qnn_write_int32_tensor(&unet.inputs[timestep_index], timestep, error) ||
        !qnn_write_float_tensor(&unet.inputs[text_index], embedding, embedding_elements, error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) {
        return false;
    }
    return qnn_read_float_tensor(unet.outputs[0], output, error);
}

bool qnn_run_text_encoder_once(
        QnnExecutableGraph& text_encoder,
        int token_index,
        int embedding_index,
        const int32_t* tokens,
        size_t token_count,
        std::vector<float>* embeddings,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_int32_vector_tensor(
            &text_encoder.inputs[static_cast<size_t>(token_index)],
            tokens,
            token_count,
            error)) {
        return false;
    }
    if (!text_encoder.execute(execute_ms, error)) {
        return false;
    }
    return qnn_read_float_tensor(
        text_encoder.outputs[static_cast<size_t>(embedding_index)],
        embeddings,
        error);
}

bool qnn_run_sdxl_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const std::vector<float>& latent,
        int timestep,
        const float* hidden,
        size_t hidden_elements,
        const float* time_ids,
        size_t time_id_elements,
        const float* pooled,
        size_t pooled_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_float_tensor(&unet.inputs[sample_index], latent.data(), latent.size(), error) ||
        !qnn_write_int32_tensor(&unet.inputs[timestep_index], timestep, error) ||
        !qnn_write_float_tensor(&unet.inputs[hidden_index], hidden, hidden_elements, error) ||
        !qnn_write_float_tensor(&unet.inputs[time_ids_index], time_ids, time_id_elements, error) ||
        !qnn_write_float_tensor(&unet.inputs[pooled_index], pooled, pooled_elements, error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) {
        return false;
    }
    return qnn_read_float_tensor(unet.outputs[0], output, error);
}

#include "qnn_sdxl_isolated_phases.hpp"

std::string qnn_semantic_generate_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& output_path) {
    const auto started = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false);
    const auto bundle = inspect_bundle_internal(bundle_root);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    if (!bundle.root_present) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"bundle_missing\",\"message\":\"QNN bundle root is missing.\"}";
    }

    const std::string conditioning_format = string_field(params_json, "conditioningFormat");
    const bool qnn_token_conditioning =
        contains_lower(conditioning_format, "qnn_clip_token_ids_i32");
    const std::string unet_binary = string_field(params_json, "unetContextBinary").empty()
        ? "unet.bin"
        : string_field(params_json, "unetContextBinary");
    const std::string vae_binary = string_field(params_json, "vaeDecoderContextBinary").empty()
        ? "vae_decoder.bin"
        : string_field(params_json, "vaeDecoderContextBinary");
    const std::string text_encoder_binary =
        string_field(params_json, "textEncoderContextBinary").empty()
            ? "text_encoder.bin"
            : string_field(params_json, "textEncoderContextBinary");
    const std::string graph_name = string_field(params_json, "graphName").empty()
        ? "model"
        : string_field(params_json, "graphName");
    const std::string text_encoder_graph_name =
        string_field(params_json, "textEncoderGraphName").empty()
            ? graph_name
            : string_field(params_json, "textEncoderGraphName");
    const std::string unet_path = join_path(bundle_root, unet_binary);
    const std::string vae_path = join_path(bundle_root, vae_binary);
    const std::string text_encoder_path = join_path(bundle_root, text_encoder_binary);
    if (!exists_file(unet_path) || !exists_file(vae_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"context_missing\",\"message\":") +
            quote("QNN semantic generation context is missing. Expected " +
                unet_binary + " and " + vae_binary + " in the bundle root.") + "}";
    }
    if (qnn_token_conditioning && !exists_file(text_encoder_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_context_missing\",\"message\":") +
            quote("QNN token conditioning requires " + text_encoder_binary +
                " in the bundle root.") + "}";
    }

    std::vector<float> embeddings;
    std::vector<int32_t> token_ids;
    std::string error;
    if (qnn_token_conditioning) {
        if (!read_int32_binary_file(embeddings_path, &token_ids, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"token_read_failed\",\"message\":") +
                quote(error) + "}";
        }
    } else {
        if (!read_float_binary_file(embeddings_path, &embeddings, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"embedding_read_failed\",\"message\":") +
                quote(error) + "}";
        }
    }

    const int requested_steps_hint = static_cast<int>(std::max<long long>(
        1,
        std::min<long long>(long_field(params_json, "steps"), 20)
    ));
    const std::string progress_journal_path = string_field(params_json, "progressJournalPath");
    QnnImageGenerationScope generation(requested_steps_hint, progress_journal_path);
    if (!generation.active()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image generation is still finishing.\"}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    generation.record_stage(
        mca::qnn::ImageStage::ContextLock,
        kQnnImageContextLock);
    std::unique_lock<std::timed_mutex> context_execution_lock(
        g_qnn_context_execution_mutex,
        std::defer_lock
    );
    while (!context_execution_lock.try_lock_for(std::chrono::milliseconds(50))) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    QnnCoherentRuntimeSession runtime_session;
    if (!runtime_session.open(runtime, unet_path, &generation)) {
        const std::string primary_error = runtime_session.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"runtime_session_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    QnnExecutableGraph unet;
    if (!unet.load_in_session(&runtime_session, runtime, unet_path, graph_name, false)) {
        const std::string primary_error = unet.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    long long text_encoder_context_load_ms = 0;
    long long text_encoder_execute_ms_total = 0;
    uint64_t text_encoder_embedding_width = 0;
    std::string loaded_text_encoder_graph;
    std::string text_encoder_inputs_debug = "[]";
    std::string text_encoder_outputs_debug = "[]";
    if (qnn_token_conditioning) {
        QnnExecutableGraph text_encoder;
        if (!text_encoder.load_in_session(
                &runtime_session,
                runtime,
                text_encoder_path,
                text_encoder_graph_name,
                false)) {
            const std::string primary_error = text_encoder.message;
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_load_failed\",\"message\":") +
                quote(primary_error) + "}";
        }
        text_encoder_context_load_ms = text_encoder.context_load_ms;
        loaded_text_encoder_graph = text_encoder.graph_name;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();

        int token_index = tensor_index_by_name(
            text_encoder.inputs,
            {"tokens", "token", "input_ids", "input"});
        if (token_index < 0 && text_encoder.inputs.size() == 1) token_index = 0;
        int embedding_index = tensor_index_by_name(
            text_encoder.outputs,
            {"text_embedding", "text_emb", "embedding", "hidden"});
        if (embedding_index < 0 && text_encoder.outputs.size() == 1) embedding_index = 0;
        if (token_index < 0 || embedding_index < 0) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_tensor_layout_unsupported\",\"message\":") +
                quote("QNN text encoder requires one int32 token input and one embedding output. inputs=" +
                    qnn_tensor_list_debug_json(text_encoder.inputs) + ", outputs=" +
                    qnn_tensor_list_debug_json(text_encoder.outputs)) + "}";
        }
        text_encoder_inputs_debug = qnn_tensor_list_debug_json(text_encoder.inputs);
        text_encoder_outputs_debug = qnn_tensor_list_debug_json(text_encoder.outputs);

        const uint64_t token_elements = qnn_tensor_element_count(
            text_encoder.inputs[static_cast<size_t>(token_index)].tensor);
        const uint64_t embedding_elements = qnn_tensor_element_count(
            text_encoder.outputs[static_cast<size_t>(embedding_index)].tensor);
        if (token_elements != 77 || token_ids.size() != static_cast<size_t>(token_elements * 2u)) {
            std::ostringstream message;
            message << "QNN CLIP token conditioning requires exactly two 77-token int32 sequences. "
                    << "Graph token elements=" << token_elements
                    << ", file token elements=" << token_ids.size() << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_token_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        if (embedding_elements == 0 || embedding_elements % token_elements != 0) {
            std::ostringstream message;
            message << "QNN text encoder output shape is incompatible with its token sequence. "
                    << "Token elements=" << token_elements
                    << ", embedding elements=" << embedding_elements << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_output_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        text_encoder_embedding_width = embedding_elements / token_elements;

        std::vector<float> negative_embeddings;
        std::vector<float> positive_embeddings;
        long long execute_ms = 0;
        if (!qnn_run_text_encoder_once(
                text_encoder,
                token_index,
                embedding_index,
                token_ids.data(),
                static_cast<size_t>(token_elements),
                &negative_embeddings,
                &execute_ms,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_uncond_failed\",\"message\":") +
                quote(error) + "}";
        }
        text_encoder_execute_ms_total += execute_ms;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_run_text_encoder_once(
                text_encoder,
                token_index,
                embedding_index,
                token_ids.data() + static_cast<size_t>(token_elements),
                static_cast<size_t>(token_elements),
                &positive_embeddings,
                &execute_ms,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        text_encoder_execute_ms_total += execute_ms;
        if (negative_embeddings.size() != static_cast<size_t>(embedding_elements) ||
            positive_embeddings.size() != static_cast<size_t>(embedding_elements)) {
            std::ostringstream message;
            message << "QNN text encoder returned an unexpected output size. Expected "
                    << embedding_elements << " per prompt, got "
                    << negative_embeddings.size() << " and "
                    << positive_embeddings.size() << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_output_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        embeddings.reserve(negative_embeddings.size() + positive_embeddings.size());
        embeddings.insert(
            embeddings.end(),
            negative_embeddings.begin(),
            negative_embeddings.end());
        embeddings.insert(
            embeddings.end(),
            positive_embeddings.begin(),
            positive_embeddings.end());
        text_encoder.close();
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }

    QnnExecutableGraph vae;
    if (!vae.load_in_session(&runtime_session, runtime, vae_path, graph_name, true)) {
        const std::string primary_error = vae.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    const std::string family = string_field(params_json, "family");
    const bool request_sdxl =
        contains_lower(conditioning_format, "sdxl") ||
        contains_lower(family, "sdxl") ||
        contains_lower(bundle_root, "sdxl");
    if (request_sdxl) {
        const int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
        int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep"});
        if (timestep_index < 0 && unet.inputs.size() > 2) timestep_index = 2;
        int hidden_index = tensor_index_by_name(unet.inputs, {"encoder_hidden_states", "encoder_hidden", "hidden_states", "hidden"});
        int time_ids_index = tensor_index_by_name(unet.inputs, {"time_ids", "timeids"});
        int pooled_index = tensor_index_by_name(unet.inputs, {"text_embeds", "text_embed", "pooled"});
        if (unet.inputs.size() < 5 || sample_index >= static_cast<int>(unet.inputs.size()) ||
            timestep_index < 0 || hidden_index < 0 || time_ids_index < 0 || pooled_index < 0 ||
            unet.outputs.empty() || vae.inputs.empty() || vae.outputs.empty()) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SDXL semantic generation expects UNet(sample,encoder_hidden_states,timestamp,time_ids,text_embeds) and VAE(latent) tensors.\"}";
        }

        const uint64_t latent_elements = qnn_tensor_element_count(unet.inputs[sample_index].tensor);
        const uint64_t hidden_elements = qnn_tensor_element_count(unet.inputs[hidden_index].tensor);
        const uint64_t pooled_elements = qnn_tensor_element_count(unet.inputs[pooled_index].tensor);
        const uint64_t time_id_elements = qnn_tensor_element_count(unet.inputs[time_ids_index].tensor);
        const uint64_t vae_input_elements = qnn_tensor_element_count(vae.inputs[0].tensor);
        if (latent_elements == 0 || hidden_elements == 0 || pooled_elements == 0 ||
            time_id_elements == 0 || vae_input_elements != latent_elements) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN SDXL UNet and VAE tensor shapes are not compatible.\"}";
        }
        const size_t hidden_count = static_cast<size_t>(hidden_elements);
        const size_t pooled_count = static_cast<size_t>(pooled_elements);
        const size_t time_ids_count = static_cast<size_t>(time_id_elements);
        const size_t needed = hidden_count * 2u + pooled_count * 2u + time_ids_count;
        if (embeddings.size() < needed) {
            std::ostringstream message;
            message << "SDXL conditioning file is too small. Need at least "
                    << needed << " f32 elements, got " << embeddings.size() << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"conditioning_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }

        const long long requested_steps = std::max<long long>(1, long_field(params_json, "steps"));
        if (requested_steps > 1) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"npuActive\":false,\"executionStage\":\"sdxl_multistep_disabled\",\"message\":\"QNN SDXL currently allows only 1-step proof-of-life generation. Multi-step sampling is disabled until repeated HTP graph execution is stabilized.\"}";
        }
        const int steps = 1;
        generation.set_steps(steps);
        generation.set_phase(kQnnImageSampling);
        const long long seed_value = long_field(params_json, "seed");
        const int seed = static_cast<int>(seed_value == 0 ? 42 : seed_value);
        const float cfg_scale = static_cast<float>(double_field(params_json, "cfgScale", 7.0));
        const float vae_decode_scale = static_cast<float>(double_field(params_json, "vaeLatentScale", 1.0 / 0.13025));
        std::vector<int> timesteps(static_cast<size_t>(steps), 1);
        const int step_size = 1000 / std::max(1, steps);
        for (int i = steps - 1; i >= 0; --i) {
            timesteps[static_cast<size_t>(i)] = 1 + (steps - 1 - i) * step_size;
        }
        const auto alphas = sd15_pndm_alphas();
        std::mt19937 rng(static_cast<uint32_t>(seed));
        std::normal_distribution<float> normal(0.0f, 1.0f);
        std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
        for (float& value : latents) value = normal(rng);

        const float* negative_hidden = embeddings.data();
        const float* positive_hidden = embeddings.data() + hidden_count;
        const float* negative_pooled = embeddings.data() + hidden_count * 2u;
        const float* positive_pooled = negative_pooled + pooled_count;
        const float* time_ids = positive_pooled + pooled_count;
        std::vector<float> noise_uncond;
        std::vector<float> noise_cond;
        std::vector<std::vector<float>> ets;
        long long unet_execute_ms_total = 0;
        for (int step = 0; step < steps; ++step) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            generation.set_step(step);
            long long execute_ms = 0;
            if (!qnn_run_sdxl_unet_once(
                    unet,
                    sample_index,
                    timestep_index,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    latents,
                    timesteps[static_cast<size_t>(step)],
                    negative_hidden,
                    hidden_count,
                    time_ids,
                    time_ids_count,
                    negative_pooled,
                    pooled_count,
                    &noise_uncond,
                    &execute_ms,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            unet_execute_ms_total += execute_ms;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            if (!qnn_run_sdxl_unet_once(
                    unet,
                    sample_index,
                    timestep_index,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    latents,
                    timesteps[static_cast<size_t>(step)],
                    positive_hidden,
                    hidden_count,
                    time_ids,
                    time_ids_count,
                    positive_pooled,
                    pooled_count,
                    &noise_cond,
                    &execute_ms,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            unet_execute_ms_total += execute_ms;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            if (noise_uncond.size() < latents.size() || noise_cond.size() < latents.size()) {
                return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_output_shape_unsupported\",\"message\":\"SDXL UNet output is smaller than the latent tensor.\"}";
            }
            std::vector<float> guided(latents.size(), 0.0f);
            for (size_t i = 0; i < latents.size(); ++i) {
                guided[i] = cfg_scale * (noise_cond[i] - noise_uncond[i]) + noise_uncond[i];
            }
            latents = qnn_pndm_step(latents, ets, guided, step, timesteps, alphas);
            generation.set_step(step + 1);
        }

        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        generation.set_phase(kQnnImageDecoding);
        std::vector<float> vae_latents(latents.size(), 0.0f);
        for (size_t i = 0; i < latents.size(); ++i) vae_latents[i] = latents[i] * vae_decode_scale;
        if (!qnn_write_float_tensor(&vae.inputs[0], vae_latents.data(), vae_latents.size(), &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_vae_input_bind_failed\",\"message\":") +
                quote(error) + "}";
        }
        long long vae_execute_ms = 0;
        if (!vae.execute(&vae_execute_ms, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_vae_execute_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        std::vector<float> pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_vae_output_read_failed\",\"message\":") +
                quote(error) + "}";
        }
        int width = 0;
        int height = 0;
        generation.record_stage(
            mca::qnn::ImageStage::PngWrite,
            kQnnImagePngWrite);
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!write_vae_tensor_png(vae.outputs[0], pixels, output_path, &width, &height, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_png_write_failed\",\"message\":") +
                quote(error) + "}";
        }

        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started
        ).count();
        const long long output_bytes = file_size_or_zero(output_path);
        std::ostringstream out;
        out << "{"
            << "\"ok\":true,"
            << "\"backend\":\"qnn_htp\","
            << "\"pipelineProbe\":false,"
            << "\"semanticReady\":true,"
            << "\"npuActive\":true,"
            << "\"qnnGraphExecution\":true,"
            << "\"nativeExecution\":true,"
            << "\"fallback\":false,"
            << "\"executionStage\":\"sdxl_semantic_generation_passed\","
            << "\"nativeGenerationSequence\":" << g_qnn_image_generation_sequence.load() << ","
            << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
            << "\"nativeStageMask\":" << g_qnn_image_generation_stage_mask.load() << ","
            << "\"nativeDetailStageMask\":" << g_qnn_image_generation_detail_stage_mask.load() << ","
            << "\"runtimeSessionMode\":\"shared_unet_vae\","
            << "\"message\":\"QNN SDXL semantic generation completed with MNN CLIP conditioning, CFG sampling, QNN UNet, and QNN VAE decoder.\","
            << "\"conditioningFormat\":\"sdxl_qnn_conditioning\","
            << "\"steps\":" << steps << ","
            << "\"seed\":" << seed << ","
            << "\"cfgScale\":" << cfg_scale << ","
            << "\"width\":" << width << ","
            << "\"height\":" << height << ","
            << "\"elapsedMs\":" << elapsed << ","
            << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
            << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
            << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
            << "\"unetExecuteMsAvg\":" << (steps > 0 ? (unet_execute_ms_total / (steps * 2)) : 0) << ","
            << "\"vaeExecuteMs\":" << vae_execute_ms << ","
            << "\"conditioningElements\":" << embeddings.size() << ","
            << "\"hiddenElements\":" << hidden_elements << ","
            << "\"pooledElements\":" << pooled_elements << ","
            << "\"timeIdElements\":" << time_id_elements << ","
            << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
            << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
            << "\"outputPath\":" << quote(output_path) << ","
            << "\"outputBytes\":" << output_bytes << ","
            << "\"unetGraph\":" << quote(unet.graph_name) << ","
            << "\"vaeGraph\":" << quote(vae.graph_name) << ","
            << "\"debug\":{"
            << "\"timestepFirst\":" << (timesteps.empty() ? 0 : timesteps.front()) << ","
            << "\"timestepLast\":" << (timesteps.empty() ? 0 : timesteps.back()) << ","
            << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
            << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
            << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
            << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
            << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
            << "\"noiseUncondStats\":" << float_vector_stats_json(noise_uncond) << ","
            << "\"noiseCondStats\":" << float_vector_stats_json(noise_cond) << ","
            << "\"pixelStats\":" << float_vector_stats_json(pixels) << "},"
            << "\"runtime\":" << runtime_probe_json(runtime) << ","
            << "\"bundle\":" << bundle_probe_json(bundle)
            << "}";
        return out.str();
    }

    const int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
    int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep", "time"});
    if (timestep_index < 0 && unet.inputs.size() > 1) timestep_index = 1;
    int text_index = tensor_index_by_name(unet.inputs, {"text_embedding", "encoder_hidden", "hidden"});
    if (text_index < 0 && unet.inputs.size() > 2) text_index = 2;
    if (unet.inputs.size() < 3 || sample_index >= static_cast<int>(unet.inputs.size()) ||
        timestep_index < 0 || text_index < 0 || unet.outputs.empty() ||
        vae.inputs.empty() || vae.outputs.empty()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SD1.5 semantic generation expects UNet(sample,timestep,text) and VAE(latent) tensors.\"}";
    }

    const uint64_t latent_elements = qnn_tensor_element_count(unet.inputs[sample_index].tensor);
    const uint64_t text_elements = qnn_tensor_element_count(unet.inputs[text_index].tensor);
    const uint64_t vae_input_elements = qnn_tensor_element_count(vae.inputs[0].tensor);
    if (latent_elements == 0 || text_elements == 0 || vae_input_elements != latent_elements) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN UNet and VAE latent tensor shapes are not compatible.\"}";
    }
    if (qnn_token_conditioning &&
        embeddings.size() != static_cast<size_t>(text_elements * 2)) {
        std::ostringstream message;
        message << "QNN text encoder output does not match the UNet conditioning input. "
                << "Encoder produced " << (embeddings.size() / 2u)
                << " elements per prompt (width=" << text_encoder_embedding_width
                << "), UNet expects " << text_elements << ".";
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_unet_shape_mismatch\",\"message\":") +
            quote(message.str()) + "}";
    }
    if (embeddings.size() < static_cast<size_t>(text_elements * 2)) {
        std::ostringstream message;
        message << "Prompt embeddings are too small. Need at least "
                << (text_elements * 2) << " f32 elements, got " << embeddings.size() << ".";
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"embedding_shape_unsupported\",\"message\":") +
            quote(message.str()) + "}";
    }

    const int steps = static_cast<int>(std::max<long long>(1, std::min<long long>(long_field(params_json, "steps"), 20)));
    generation.set_steps(steps);
    generation.set_phase(kQnnImageSampling);
    const long long seed_value = long_field(params_json, "seed");
    const int seed = static_cast<int>(seed_value == 0 ? 42 : seed_value);
    const float cfg_scale = static_cast<float>(double_field(params_json, "cfgScale", 7.5));
    std::vector<int> timesteps(static_cast<size_t>(steps), 1);
    const int step_size = 1000 / std::max(1, steps);
    for (int i = steps - 1; i >= 0; --i) {
        timesteps[static_cast<size_t>(i)] = 1 + (steps - 1 - i) * step_size;
    }
    const auto alphas = sd15_pndm_alphas();
    std::mt19937 rng(static_cast<uint32_t>(seed));
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    for (float& value : latents) value = normal(rng);

    const float* negative_embedding = embeddings.data();
    const float* positive_embedding = embeddings.data() + static_cast<size_t>(text_elements);
    std::vector<float> noise_uncond;
    std::vector<float> noise_cond;
    std::vector<std::vector<float>> ets;
    long long unet_execute_ms_total = 0;
    for (int step = 0; step < steps; ++step) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        generation.set_step(step);
        long long execute_ms = 0;
        if (!qnn_run_unet_once(
                unet,
                sample_index,
                timestep_index,
                text_index,
                latents,
                timesteps[static_cast<size_t>(step)],
                negative_embedding,
                static_cast<size_t>(text_elements),
                &noise_uncond,
                &execute_ms,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_uncond_failed\",\"message\":") +
                quote(error) + "}";
        }
        unet_execute_ms_total += execute_ms;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_run_unet_once(
                unet,
                sample_index,
                timestep_index,
                text_index,
                latents,
                timesteps[static_cast<size_t>(step)],
                positive_embedding,
                static_cast<size_t>(text_elements),
                &noise_cond,
                &execute_ms,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        unet_execute_ms_total += execute_ms;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (noise_uncond.size() < latents.size() || noise_cond.size() < latents.size()) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_output_shape_unsupported\",\"message\":\"UNet output is smaller than the latent tensor.\"}";
        }
        std::vector<float> guided(latents.size(), 0.0f);
        for (size_t i = 0; i < latents.size(); ++i) {
            guided[i] = cfg_scale * (noise_cond[i] - noise_uncond[i]) + noise_uncond[i];
        }
        latents = qnn_pndm_step(latents, ets, guided, step, timesteps, alphas);
        generation.set_step(step + 1);
    }

    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    generation.set_phase(kQnnImageDecoding);
    std::vector<float> vae_latents(latents.size(), 0.0f);
    for (size_t i = 0; i < latents.size(); ++i) vae_latents[i] = latents[i] * (1.0f / 0.18215f);
    if (!qnn_write_float_tensor(&vae.inputs[0], vae_latents.data(), vae_latents.size(), &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_input_bind_failed\",\"message\":") +
            quote(error) + "}";
    }
    long long vae_execute_ms = 0;
    if (!vae.execute(&vae_execute_ms, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_execute_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    std::vector<float> pixels;
    if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_output_read_failed\",\"message\":") +
            quote(error) + "}";
    }
    int width = 0;
    int height = 0;
    generation.record_stage(
        mca::qnn::ImageStage::PngWrite,
        kQnnImagePngWrite);
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    if (!write_vae_tensor_png(vae.outputs[0], pixels, output_path, &width, &height, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"png_write_failed\",\"message\":") +
            quote(error) + "}";
    }

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    const long long output_bytes = file_size_or_zero(output_path);
    std::ostringstream out;
    out << "{"
        << "\"ok\":true,"
        << "\"backend\":\"qnn_htp\","
        << "\"pipelineProbe\":false,"
        << "\"semanticReady\":true,"
        << "\"npuActive\":true,"
        << "\"qnnGraphExecution\":true,"
        << "\"nativeExecution\":true,"
        << "\"fallback\":false,"
        << "\"executionStage\":\"semantic_generation_passed\","
        << "\"nativeGenerationSequence\":" << g_qnn_image_generation_sequence.load() << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << g_qnn_image_generation_stage_mask.load() << ","
        << "\"nativeDetailStageMask\":" << g_qnn_image_generation_detail_stage_mask.load() << ","
        << "\"runtimeSessionMode\":"
        << quote(qnn_token_conditioning ? "shared_text_unet_vae" : "shared_unet_vae") << ","
        << "\"message\":" << quote(qnn_token_conditioning
            ? "QNN semantic generation completed with QNN CLIP text encoding, PNDM scheduler, QNN UNet, and QNN VAE decoder."
            : "QNN SD1.5 semantic generation completed with MNN text embeddings, PNDM scheduler, QNN UNet, and QNN VAE decoder.") << ","
        << "\"conditioningFormat\":" << quote(qnn_token_conditioning
            ? "qnn_clip_token_ids_i32"
            : conditioning_format) << ","
        << "\"steps\":" << steps << ","
        << "\"seed\":" << seed << ","
        << "\"cfgScale\":" << cfg_scale << ","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
        << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
        << "\"textEncoderContextLoadMs\":" << text_encoder_context_load_ms << ","
        << "\"textEncoderExecuteMsTotal\":" << text_encoder_execute_ms_total << ","
        << "\"textEncoderEmbeddingWidth\":" << text_encoder_embedding_width << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
        << "\"unetExecuteMsAvg\":" << (steps > 0 ? (unet_execute_ms_total / (steps * 2)) : 0) << ","
        << "\"vaeExecuteMs\":" << vae_execute_ms << ","
        << "\"embeddingElements\":" << embeddings.size() << ","
        << "\"textElements\":" << text_elements << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"outputPath\":" << quote(output_path) << ","
        << "\"outputBytes\":" << output_bytes << ","
        << "\"textEncoderGraph\":" << quote(loaded_text_encoder_graph) << ","
        << "\"unetGraph\":" << quote(unet.graph_name) << ","
        << "\"vaeGraph\":" << quote(vae.graph_name) << ","
        << "\"debug\":{"
        << "\"timestepFirst\":" << (timesteps.empty() ? 0 : timesteps.front()) << ","
        << "\"timestepLast\":" << (timesteps.empty() ? 0 : timesteps.back()) << ","
        << "\"textEncoderInputs\":" << text_encoder_inputs_debug << ","
        << "\"textEncoderOutputs\":" << text_encoder_outputs_debug << ","
        << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
        << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
        << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
        << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
        << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
        << "\"noiseUncondStats\":" << float_vector_stats_json(noise_uncond) << ","
        << "\"noiseCondStats\":" << float_vector_stats_json(noise_cond) << ","
        << "\"pixelStats\":" << float_vector_stats_json(pixels) << "},"
        << "\"runtime\":" << runtime_probe_json(runtime) << ","
        << "\"bundle\":" << bundle_probe_json(bundle)
        << "}";
    return out.str();
}

std::string qnn_pipeline_probe_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& output_path) {
    const auto started = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false);
    const auto bundle = inspect_bundle_internal(bundle_root);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    if (!bundle.root_present) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"bundle_missing\",\"message\":\"QNN bundle root is missing.\"}";
    }

    const std::string unet_binary = string_field(params_json, "unetContextBinary").empty()
        ? "unet.bin"
        : string_field(params_json, "unetContextBinary");
    const std::string vae_binary = string_field(params_json, "vaeDecoderContextBinary").empty()
        ? "vae_decoder.bin"
        : string_field(params_json, "vaeDecoderContextBinary");
    const std::string graph_name = string_field(params_json, "graphName").empty()
        ? "model"
        : string_field(params_json, "graphName");
    const std::string unet_path = join_path(bundle_root, unet_binary);
    const std::string vae_path = join_path(bundle_root, vae_binary);
    if (!exists_file(unet_path) || !exists_file(vae_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"context_missing\",\"message\":") +
            quote("QNN pipeline requires unet.bin and vae_decoder.bin in the bundle root.") + "}";
    }

    QnnExecutableGraph unet;
    if (!unet.load(runtime, unet_path, graph_name)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_load_failed\",\"message\":") +
            quote(unet.message) + "}";
    }
    QnnExecutableGraph vae;
    if (!vae.load(runtime, vae_path, graph_name)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(vae.message) + "}";
    }

    const int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
    int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep", "time"});
    if (timestep_index < 0 && unet.inputs.size() > 1) timestep_index = 1;
    int text_index = tensor_index_by_name(unet.inputs, {"text_embedding", "encoder_hidden", "hidden"});
    if (text_index < 0 && unet.inputs.size() > 2) text_index = 2;
    if (unet.inputs.size() < 3 || sample_index >= static_cast<int>(unet.inputs.size()) ||
        timestep_index < 0 || text_index < 0 || unet.outputs.empty() ||
        vae.inputs.empty() || vae.outputs.empty()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SD1.5 probe expects UNet(sample,timestep,text) and VAE(latent) tensors.\"}";
    }

    const uint64_t latent_elements = qnn_tensor_element_count(unet.inputs[sample_index].tensor);
    const uint64_t text_elements = qnn_tensor_element_count(unet.inputs[text_index].tensor);
    const uint64_t vae_input_elements = qnn_tensor_element_count(vae.inputs[0].tensor);
    if (latent_elements == 0 || text_elements == 0 || vae_input_elements != latent_elements) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN UNet and VAE latent tensor shapes are not compatible.\"}";
    }

    const int steps = static_cast<int>(std::max<long long>(1, std::min<long long>(long_field(params_json, "steps"), 8)));
    const int seed = static_cast<int>(long_field(params_json, "seed") == 0 ? 42 : long_field(params_json, "seed"));
    const float update_scale = static_cast<float>(double_field(params_json, "probeUpdateScale", 0.08));
    const float latent_scale = static_cast<float>(double_field(params_json, "vaeLatentScale", 1.0));
    std::mt19937 rng(static_cast<uint32_t>(seed));
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    for (float& value : latents) value = normal(rng);
    std::vector<float> text(static_cast<size_t>(text_elements), 0.0f);
    std::vector<float> unet_output;
    long long unet_execute_ms_total = 0;
    std::string error;
    for (int step = 0; step < steps; ++step) {
        if (!qnn_write_float_tensor(&unet.inputs[sample_index], latents.data(), latents.size(), &error) ||
            !qnn_write_int32_tensor(&unet.inputs[timestep_index], steps - step, &error) ||
            !qnn_write_float_tensor(&unet.inputs[text_index], text.data(), text.size(), &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_input_bind_failed\",\"message\":") +
                quote(error) + "}";
        }
        long long execute_ms = 0;
        if (!unet.execute(&execute_ms, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_execute_failed\",\"message\":") +
                quote(error) + "}";
        }
        unet_execute_ms_total += execute_ms;
        if (!qnn_read_float_tensor(unet.outputs[0], &unet_output, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_output_read_failed\",\"message\":") +
                quote(error) + "}";
        }
        const size_t count = std::min(latents.size(), unet_output.size());
        for (size_t i = 0; i < count; ++i) {
            latents[i] -= update_scale * unet_output[i];
        }
    }

    std::vector<float> vae_latents(latents.size());
    for (size_t i = 0; i < latents.size(); ++i) vae_latents[i] = latents[i] * latent_scale;
    if (!qnn_write_float_tensor(&vae.inputs[0], vae_latents.data(), vae_latents.size(), &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_input_bind_failed\",\"message\":") +
            quote(error) + "}";
    }
    long long vae_execute_ms = 0;
    if (!vae.execute(&vae_execute_ms, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_execute_failed\",\"message\":") +
            quote(error) + "}";
    }
    std::vector<float> pixels;
    if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_output_read_failed\",\"message\":") +
            quote(error) + "}";
    }
    int width = 0;
    int height = 0;
    if (!write_vae_tensor_png(vae.outputs[0], pixels, output_path, &width, &height, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    const long long output_bytes = file_size_or_zero(output_path);
    std::ostringstream out;
    out << "{"
        << "\"ok\":true,"
        << "\"backend\":\"qnn_htp\","
        << "\"pipelineProbe\":true,"
        << "\"semanticReady\":false,"
        << "\"npuActive\":true,"
        << "\"executionStage\":\"pipeline_probe_passed\","
        << "\"message\":\"QNN UNet and VAE decoder executed sequentially on Snapdragon NPU; prompt text encoder and production scheduler are still pending.\","
        << "\"steps\":" << steps << ","
        << "\"seed\":" << seed << ","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
        << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
        << "\"unetExecuteMsAvg\":" << (steps > 0 ? (unet_execute_ms_total / steps) : 0) << ","
        << "\"vaeExecuteMs\":" << vae_execute_ms << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"outputPath\":" << quote(output_path) << ","
        << "\"outputBytes\":" << output_bytes << ","
        << "\"unetGraph\":" << quote(unet.graph_name) << ","
        << "\"vaeGraph\":" << quote(vae.graph_name) << ","
        << "\"debug\":{"
        << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
        << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
        << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
        << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
        << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
        << "\"unetOutputStats\":" << float_vector_stats_json(unet_output) << ","
        << "\"pixelStats\":" << float_vector_stats_json(pixels) << "},"
        << "\"runtime\":" << runtime_probe_json(runtime) << ","
        << "\"bundle\":" << bundle_probe_json(bundle)
        << "}";
    return out.str();
}

#else

GraphSmokeResult run_typed_qnn_graph_smoke(
        const RuntimeProbe&,
        const SmokeSpecProbe&,
        const std::string&,
        const std::string&) {
    GraphSmokeResult result;
    result.attempted = false;
    result.execution_stage = "sdk_headers_missing";
    result.message = "QNN SDK headers were not available at build time.";
    return result;
}

std::string qnn_pipeline_probe_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_semantic_generate_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_sdxl_unet_phase_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"unet\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_sdxl_vae_phase_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"vae\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

#endif

std::string smoke_execution_stage(
        const RuntimeProbe& runtime,
        const BundleProbe& bundle,
        const SmokeSpecProbe& smoke_spec,
        bool context_binary_present) {
    if (!runtime.loadable) return "runtime_load_failed";
    if (!runtime.qnn_system_interface_present) return "qnn_system_interface_missing";
    if (!bundle.root_present || !bundle.manifest_present || !bundle.has_graph_artifact) {
        return "bundle_incomplete";
    }
    if (!smoke_spec.complete) return "smoke_metadata_incomplete";
    if (!smoke_spec.tensor_buffer_plan_ready) return "tensor_buffer_plan_incomplete";
    if (!context_binary_present) return "context_binary_missing";
#if !MCA_WITH_QNN_SDK_HEADERS
    return "sdk_headers_missing";
#else
    return "graph_execution_ready";
#endif
}

std::string smoke_json(
        const char* kind,
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& smoke_spec_json) {
    const auto start = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false);
    const auto bundle = inspect_bundle_internal(bundle_root);
    const auto smoke_spec = inspect_smoke_spec(smoke_spec_json);
    const std::string context_binary_path = join_path(bundle_root, smoke_spec.context_binary);
    const bool context_binary_present = !smoke_spec.context_binary.empty() &&
        is_safe_bundle_relative_path(smoke_spec.context_binary) &&
        exists_file(context_binary_path) &&
        file_size_or_zero(context_binary_path) > 0;
    auto smoke_spec_with_file = smoke_spec;
    smoke_spec_with_file.context_binary_bytes = context_binary_present
        ? file_size_or_zero(context_binary_path)
        : 0;
    const bool graph_metadata_ready = runtime.loadable &&
        runtime.qnn_system_interface_present &&
        bundle.root_present &&
        bundle.manifest_present &&
        bundle.has_graph_artifact &&
        context_binary_present &&
        smoke_spec_with_file.complete &&
        smoke_spec_with_file.tensor_buffer_plan_ready;
    GraphSmokeResult graph_smoke;
    if (graph_metadata_ready) {
        graph_smoke = run_typed_qnn_graph_smoke(
            runtime,
            smoke_spec_with_file,
            context_binary_path,
            smoke_spec_json
        );
    }
    std::string binary_metadata_json_value = "{}";
#if MCA_WITH_QNN_SDK_HEADERS
    if (context_binary_present && runtime.system_present) {
        binary_metadata_json_value = binary_metadata_json(
            inspect_qnn_context_binary_metadata(runtime, read_binary_file(context_binary_path))
        );
    }
#endif
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();

    std::string message;
    const bool graph_runner_ready = graph_smoke.graph_resolved && graph_smoke.tensors_bound;
    const bool graph_execute = graph_smoke.graph_executed;
    const bool smoke_passed = graph_smoke.graph_executed;
    const bool npu_active = graph_smoke.graph_executed;
    const std::string execution_stage = graph_smoke.attempted
        ? graph_smoke.execution_stage
        : smoke_execution_stage(runtime, bundle, smoke_spec_with_file, context_binary_present);
    if (!runtime.loadable) {
        message = runtime.message;
    } else if (!runtime.qnn_system_interface_present) {
        message = "QNN System runtime loaded, but QnnSystemInterface_getProviders was not found.";
    } else if (!bundle.root_present || !bundle.manifest_present || !bundle.has_graph_artifact) {
        message = "QNN bundle is missing manifest or graph artifacts.";
    } else if (!smoke_spec_with_file.complete) {
        message = "QNN smoke metadata is incomplete; contextBinary, inputs, and outputs are required before graph execution.";
    } else if (!smoke_spec_with_file.tensor_buffer_plan_ready) {
        message = "QNN tensor buffer plan is incomplete; supported data types, shapes, and byte sizes are required before graph execution.";
    } else if (!context_binary_present) {
        message = "QNN smoke contextBinary is missing or empty inside this engine bundle.";
#if !MCA_WITH_QNN_SDK_HEADERS
    } else {
        message = "QNN runtime, bundle, and smoke metadata are ready, but QNN SDK headers were not available at build time.";
#else
    } else {
        message = graph_smoke.message.empty()
            ? "QNN graph smoke execution returned no message."
            : graph_smoke.message;
#endif
    }

    std::ostringstream out;
    out << "{"
        << "\"kind\":" << quote(kind) << ","
        << "\"backend\":\"qnn_htp\","
        << "\"ok\":" << (smoke_passed ? "true" : "false") << ","
        << "\"runnerReady\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
        << "\"graphMetadataReady\":" << (graph_metadata_ready ? "true" : "false") << ","
        << "\"graphRunnerReady\":" << (graph_runner_ready ? "true" : "false") << ","
        << "\"compile\":" << compile_capability_json() << ","
        << "\"deviceCreated\":" << (graph_smoke.device_created ? "true" : "false") << ","
        << "\"contextDefaultDevice\":" << (graph_smoke.context_default_device ? "true" : "false") << ","
        << "\"deviceMessage\":" << quote(graph_smoke.device_message) << ","
        << "\"graphExecute\":" << (graph_execute ? "true" : "false") << ","
        << "\"npuActive\":" << (npu_active ? "true" : "false") << ","
        << "\"smokePassed\":" << (smoke_passed ? "true" : "false") << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"contextLoadMs\":" << graph_smoke.context_load_ms << ","
        << "\"graphExecuteMs\":" << graph_smoke.graph_execute_ms << ","
        << "\"outputChecksum\":" << graph_smoke.output_checksum << ","
        << "\"executionStage\":" << quote(execution_stage) << ","
        << "\"message\":" << quote(message) << ","
        << "\"runtime\":" << runtime_probe_json(runtime) << ","
        << "\"executionRuntime\":" << runtime_probe_json(graph_smoke.execution_runtime) << ","
        << "\"executionRuntimeSelected\":"
        << (graph_smoke.execution_runtime_selected ? "true" : "false") << ","
        << "\"bundle\":" << bundle_probe_json(bundle) << ","
        << "\"binaryMetadata\":" << binary_metadata_json_value << ","
        << "\"smokeSpec\":" << smoke_spec_probe_json(smoke_spec_with_file) << ","
        << "\"stages\":" << smoke_stage_json(
            runtime,
            bundle,
            smoke_spec_with_file,
            context_binary_present,
            graph_metadata_ready,
            graph_smoke.backend_created,
            graph_smoke.context_loaded,
            graph_smoke.graph_resolved,
            graph_smoke.tensors_bound,
            graph_smoke.graph_executed)
        << "}";
    return out.str();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_isRunnerReady(JNIEnv*, jobject) noexcept {
    return qnn_jni_boolean_guard("isRunnerReady", []() {
#if MCA_WITH_QNN_SDK_HEADERS
        return true;
#else
        return false;
#endif
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_inspectRuntime(
        JNIEnv* env,
        jobject,
        jstring runtimeDirsJson) noexcept {
    return qnn_jni_json_guard(env, "inspectRuntime", [&]() {
        const auto dirs = parse_json_string_array(jstring_to_std(env, runtimeDirsJson));
        return runtime_probe_json(inspect_runtime_internal(dirs, true, false));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_inspectBundle(
        JNIEnv* env,
        jobject,
        jstring bundleRoot) noexcept {
    return qnn_jni_json_guard(env, "inspectBundle", [&]() {
        return bundle_probe_json(inspect_bundle_internal(jstring_to_std(env, bundleRoot)));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runImageSmoke(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring smokeSpecJson) noexcept {
    return qnn_jni_json_guard(env, "runImageSmoke", [&]() {
        const std::string bundle_root = jstring_to_std(env, bundleRoot);
        const std::string runtime_dirs_json = jstring_to_std(env, runtimeDirsJson);
        const std::string smoke_spec_json = jstring_to_std(env, smokeSpecJson);
        std::lock_guard<std::timed_mutex> context_execution_lock(g_qnn_context_execution_mutex);
        return smoke_json(
            "image",
            bundle_root,
            runtime_dirs_json,
            smoke_spec_json
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runImagePipelineProbe(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring paramsJson,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runImagePipelineProbe", [&]() {
        const std::string bundle_root = jstring_to_std(env, bundleRoot);
        const std::string runtime_dirs_json = jstring_to_std(env, runtimeDirsJson);
        const std::string params_json = jstring_to_std(env, paramsJson);
        const std::string output_path = jstring_to_std(env, outputPath);
        std::lock_guard<std::timed_mutex> context_execution_lock(g_qnn_context_execution_mutex);
        return qnn_pipeline_probe_json(
            bundle_root,
            runtime_dirs_json,
            params_json,
            output_path
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runImageSemanticGenerate(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring paramsJson,
        jstring embeddingsPath,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runImageSemanticGenerate", [&]() {
        return qnn_semantic_generate_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeDirsJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, embeddingsPath),
            jstring_to_std(env, outputPath)
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlUnetPhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring paramsJson,
        jstring embeddingsPath,
        jstring latentPath) noexcept {
    return qnn_jni_json_guard(env, "runSdxlUnetPhase", [&]() {
        return qnn_sdxl_unet_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeDirsJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, embeddingsPath),
            jstring_to_std(env, latentPath));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlVaePhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring paramsJson,
        jstring latentPath,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runSdxlVaePhase", [&]() {
        return qnn_sdxl_vae_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeDirsJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, latentPath),
            jstring_to_std(env, outputPath));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_cancelImageGeneration(JNIEnv*, jobject) noexcept {
    return qnn_jni_boolean_guard("cancelImageGeneration", []() {
        if (!g_qnn_image_generation_active.load()) return false;
        g_qnn_image_generation_cancel_requested.store(true);
        g_qnn_image_generation_phase.store(kQnnImageCancelling);
        return true;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_getImageGenerationProgressJson(
        JNIEnv* env,
        jobject) noexcept {
    return qnn_jni_json_guard(env, "getImageGenerationProgressJson", []() {
        return qnn_image_generation_progress_json();
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runVisionSmoke(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring smokeSpecJson) noexcept {
    return qnn_jni_json_guard(env, "runVisionSmoke", [&]() {
        const std::string bundle_root = jstring_to_std(env, bundleRoot);
        const std::string runtime_dirs_json = jstring_to_std(env, runtimeDirsJson);
        const std::string smoke_spec_json = jstring_to_std(env, smokeSpecJson);
        std::lock_guard<std::timed_mutex> context_execution_lock(g_qnn_context_execution_mutex);
        return smoke_json(
            "vision",
            bundle_root,
            runtime_dirs_json,
            smoke_spec_json
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_getRuntimeStatsJson(
        JNIEnv* env,
        jobject) noexcept {
    return qnn_jni_json_guard(env, "getRuntimeStatsJson", []() {
        return std::string("{\"backend\":\"qnn_htp\",\"bridgeReady\":true,\"graphRunnerReady\":true,\"pipelineProbeReady\":true,\"npuActive\":false,\"compile\":") +
            compile_capability_json() + "}";
    });
}
