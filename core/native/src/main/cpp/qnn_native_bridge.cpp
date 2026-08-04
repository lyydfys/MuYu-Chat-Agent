#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <cstdarg>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iomanip>
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
#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "image_execution_math.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "qnn_controlnet_execution.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_image_stage_trace.hpp"
#include "qnn_inpaint_contract.hpp"
#include "qnn_shared_preview.hpp"
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"

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
std::atomic<bool> g_qnn_runtime_poisoned{false};
std::timed_mutex g_qnn_context_execution_mutex;
std::mutex g_qnn_image_journal_mutex;
std::string g_qnn_image_journal_path;
std::mutex g_qnn_image_preview_publish_mutex;
std::mutex g_qnn_image_preview_state_mutex;

struct QnnImagePreviewProgress {
    std::string path;
    std::string mime_type;
    std::string mode;
    int step = 0;
    uint64_t revision = 0U;
    int width = 0;
    int height = 0;
    int frame_count = 0;
    size_t vae_execution_attempt_count = 0U;
    size_t vae_execution_count = 0U;
    long long vae_execution_ms_total = 0;
    size_t publication_count = 0U;
    int last_step = 0;
    uint64_t last_revision = 0U;
    std::string failure_code;
};

QnnImagePreviewProgress g_qnn_image_preview_progress;

std::string qnn_image_generation_progress_json();

void reset_qnn_image_preview_progress() {
    std::lock_guard<std::mutex> lock(g_qnn_image_preview_state_mutex);
    g_qnn_image_preview_progress = QnnImagePreviewProgress{};
}

QnnImagePreviewProgress snapshot_qnn_image_preview_progress() {
    std::lock_guard<std::mutex> lock(g_qnn_image_preview_state_mutex);
    return g_qnn_image_preview_progress;
}

void restore_qnn_image_preview_progress(const QnnImagePreviewProgress& progress) {
    std::lock_guard<std::mutex> lock(g_qnn_image_preview_state_mutex);
    g_qnn_image_preview_progress = progress;
}

void update_qnn_image_preview_progress(
        const mca::qnn::preview::Audit& audit,
        const std::string& path,
        const std::string& mode,
        int step,
        uint64_t revision,
        int width,
        int height) {
    std::lock_guard<std::mutex> lock(g_qnn_image_preview_state_mutex);
    g_qnn_image_preview_progress.path = path;
    g_qnn_image_preview_progress.mime_type = path.empty() ? "" : "image/png";
    g_qnn_image_preview_progress.mode = mode;
    g_qnn_image_preview_progress.step = path.empty() ? 0 : step;
    g_qnn_image_preview_progress.revision = path.empty() ? 0U : revision;
    g_qnn_image_preview_progress.width = path.empty() ? 0 : width;
    g_qnn_image_preview_progress.height = path.empty() ? 0 : height;
    g_qnn_image_preview_progress.frame_count = static_cast<int>(audit.publication_count);
    g_qnn_image_preview_progress.vae_execution_attempt_count =
        audit.vae_execution_attempt_count;
    g_qnn_image_preview_progress.vae_execution_count = audit.vae_execution_count;
    g_qnn_image_preview_progress.vae_execution_ms_total = audit.vae_execution_ms_total;
    g_qnn_image_preview_progress.publication_count = audit.publication_count;
    g_qnn_image_preview_progress.last_step = audit.last_step;
    g_qnn_image_preview_progress.last_revision = audit.last_revision;
    g_qnn_image_preview_progress.failure_code = audit.failure_code;
}

bool persist_qnn_image_generation_journal() {
    std::lock_guard<std::mutex> lock(g_qnn_image_journal_mutex);
    if (g_qnn_image_journal_path.empty()) return false;
    const std::string temporary = g_qnn_image_journal_path + ".tmp";
    const std::string payload = qnn_image_generation_progress_json();
    const int fd = ::open(
        temporary.c_str(),
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
        0600
    );
    if (fd < 0) return false;
    const char* cursor = payload.data();
    size_t remaining = payload.size();
    bool complete = true;
    while (remaining > 0) {
        const ssize_t written = ::write(fd, cursor, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) {
            complete = false;
            break;
        }
        cursor += written;
        remaining -= static_cast<size_t>(written);
    }
    const bool synced = complete && ::fsync(fd) == 0;
    const bool closed = ::close(fd) == 0;
    if (!complete || !synced || !closed) {
        ::unlink(temporary.c_str());
        return false;
    }
    if (::rename(temporary.c_str(), g_qnn_image_journal_path.c_str()) != 0) {
        ::unlink(temporary.c_str());
        return false;
    }
    return true;
}

long long monotonic_millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

long long monotonic_sequence_seed() {
    // Split SDXL phases run in disposable processes. Millisecond timestamps plus
    // PIDs can collide when Android reuses a PID quickly, so use the native
    // steady-clock tick value and retain a positive 63-bit wire representation.
    timespec clock_value{};
    uint64_t ticks = 0U;
    if (::clock_gettime(CLOCK_MONOTONIC, &clock_value) == 0 &&
        clock_value.tv_sec >= 0 && clock_value.tv_nsec >= 0) {
        ticks = static_cast<uint64_t>(clock_value.tv_sec) * UINT64_C(1000000000) +
            static_cast<uint64_t>(clock_value.tv_nsec);
    } else {
        ticks = static_cast<uint64_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
    }
    uint64_t mixed = ticks ^
        (static_cast<uint64_t>(static_cast<uint32_t>(::getpid())) << 32U);
    mixed &= static_cast<uint64_t>(std::numeric_limits<long long>::max());
    return mixed == 0U ? 1LL : static_cast<long long>(mixed);
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
    reset_qnn_image_preview_progress();
    {
        std::lock_guard<std::mutex> lock(g_qnn_image_journal_mutex);
        g_qnn_image_journal_path = journal_path;
    }
    g_qnn_image_generation_phase.store(kQnnImageLoading);
    g_qnn_image_generation_stage_mask.fetch_or(qnn_image_generation_stage_bit(kQnnImageLoading));
    g_qnn_image_generation_step.store(0);
    g_qnn_image_generation_steps.store(std::max(1, steps));
    const long long started_ms = monotonic_millis();
    g_qnn_image_generation_started_ms.store(started_ms);
    // Isolated SDXL workers are short-lived, so a process-local 1,2,3 counter
    // would restart at one for every UI/API request. Bind the sequence to a
    // high-resolution monotonic clock and worker identity, while preserving
    // monotonicity for repeated generations in a long-lived process.
    const long long process_unique_sequence = monotonic_sequence_seed();
    long long previous_sequence = g_qnn_image_generation_sequence.load();
    const long long incremented_sequence = previous_sequence ==
            std::numeric_limits<long long>::max()
        ? previous_sequence
        : previous_sequence + 1LL;
    const long long next_sequence = std::max(process_unique_sequence, incremented_sequence);
    g_qnn_image_generation_sequence.store(next_sequence);
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
        if (active_) {
            g_qnn_image_generation_steps.store(std::max(1, steps));
            persist_qnn_image_generation_journal();
        }
    }
    void set_step(int step) const {
        if (active_) {
            g_qnn_image_generation_step.store(std::max(0, step));
            persist_qnn_image_generation_journal();
        }
    }

private:
    bool active_ = false;
};

class ScopedDlHandle {
public:
    explicit ScopedDlHandle(void* handle = nullptr) : handle_(handle) {}
    ScopedDlHandle(const ScopedDlHandle&) = delete;
    ScopedDlHandle& operator=(const ScopedDlHandle&) = delete;
    ~ScopedDlHandle() { close(); }

    bool close_checked(std::string* error) {
        if (handle_ == nullptr) return true;
        if (dlclose(handle_) != 0) {
            const char* detail = dlerror();
            if (error != nullptr) {
                *error = "dlclose libQnnSystem failed";
                if (detail != nullptr) *error += std::string(": ") + detail;
            }
            return false;
        }
        handle_ = nullptr;
        if (error != nullptr) error->clear();
        return true;
    }

    void abandon_without_unload() { handle_ = nullptr; }

    void close() {
        std::string ignored;
        close_checked(&ignored);
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
        device_ = st.st_dev;
        inode_ = st.st_ino;
        return true;
    }

    bool close_checked(std::string* error) {
        bool ok = true;
        if (data_ != MAP_FAILED) {
            if (munmap(data_, size_) != 0) {
                ok = false;
                if (error != nullptr) *error = "Unable to unmap QNN context binary.";
            }
            data_ = MAP_FAILED;
            size_ = 0;
            device_ = 0;
            inode_ = 0;
        }
        return ok;
    }

    void abandon_without_unmap() {
        data_ = MAP_FAILED;
        size_ = 0;
        device_ = 0;
        inode_ = 0;
    }

    void close() {
        std::string ignored;
        close_checked(&ignored);
    }

    const uint8_t* data() const {
        return data_ == MAP_FAILED ? nullptr : static_cast<const uint8_t*>(data_);
    }
    size_t size() const { return size_; }
    dev_t device() const { return device_; }
    ino_t inode() const { return inode_; }
    bool empty() const { return data() == nullptr || size_ == 0; }

private:
    void* data_ = MAP_FAILED;
    size_t size_ = 0;
    dev_t device_ = 0;
    ino_t inode_ = 0;
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
    QnnImagePreviewProgress preview;
    {
        std::lock_guard<std::mutex> lock(g_qnn_image_preview_state_mutex);
        preview = g_qnn_image_preview_progress;
    }
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
    out << "],"
        << "\"previewPath\":" << quote(preview.path) << ","
        << "\"previewMimeType\":" << quote(preview.mime_type) << ","
        << "\"previewMode\":" << quote(preview.mode) << ","
        << "\"previewStep\":" << preview.step << ","
        << "\"previewRevision\":" << preview.revision << ","
        << "\"previewWidth\":" << preview.width << ","
        << "\"previewHeight\":" << preview.height << ","
        << "\"previewFrameCount\":" << preview.frame_count << ","
        << "\"previewNoisy\":false,"
        << "\"previewVaeExecutionAttemptCount\":"
        << preview.vae_execution_attempt_count << ","
        << "\"previewVaeExecutionCount\":" << preview.vae_execution_count << ","
        << "\"previewVaeExecutionMsTotal\":" << preview.vae_execution_ms_total << ","
        << "\"previewPublicationCount\":" << preview.publication_count << ","
        << "\"previewLastStep\":" << preview.last_step << ","
        << "\"previewLastRevision\":" << preview.last_revision << ","
        << "\"previewFailureCode\":" << quote(preview.failure_code)
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
    bool exact_role_binding = false;
    std::string system_path;
    std::string htp_path;
    std::string skel_path;
    std::string stub_path;
    std::string rpc_path;
    std::string rpc_message;
    std::string adsp_library_path;
    std::string host_runtime_directory;
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

constexpr std::array<const char*, 5> kQnnPlatformRuntimeDirectoryPrefixes{{
    "/vendor/",
    "/odm/",
    "/system/",
    "/system_ext/",
    "/product/",
}};

bool is_platform_qnn_runtime_directory(const std::string& directory) {
    return std::any_of(
        kQnnPlatformRuntimeDirectoryPrefixes.begin(),
        kQnnPlatformRuntimeDirectoryPrefixes.end(),
        [&](const char* prefix) { return directory.rfind(prefix, 0) == 0; });
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
        result.host_runtime_directory = host.directory;
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
        std::string unload_error;
        if (!system_handle.close_checked(&unload_error)) {
            g_qnn_runtime_poisoned.store(true);
            result.loadable = false;
            result.qnn_system_interface_present = false;
            result.message += " Cleanup also failed: " + (unload_error.empty()
                ? "QNN System metadata preflight library unload failed."
                : unload_error);
            system_handle.abandon_without_unload();
        }
        return result;
    }

    void* rpc_handle = nullptr;
    std::vector<void*> handles;
    auto close_probe_handles = [&]() -> bool {
        bool closed = true;
        std::string failure;
        for (auto it = handles.rbegin(); it != handles.rend(); ++it) {
            if (*it == nullptr) continue;
            if (dlclose(*it) != 0) {
                const char* detail = dlerror();
                failure = "QNN runtime probe library unload failed";
                if (detail != nullptr) failure += std::string(": ") + detail;
                closed = false;
                break;
            }
            *it = nullptr;
        }
        if (closed && rpc_handle != nullptr) {
            if (dlclose(rpc_handle) != 0) {
                const char* detail = dlerror();
                failure = "QNN RPC runtime probe library unload failed";
                if (detail != nullptr) failure += std::string(": ") + detail;
                closed = false;
            } else {
                rpc_handle = nullptr;
            }
        }
        if (closed) {
            handles.clear();
        } else {
            g_qnn_runtime_poisoned.store(true);
            result.loadable = false;
            result.qnn_interface_present = false;
            result.qnn_system_interface_present = false;
            if (result.message.empty()) {
                result.message = failure;
            } else {
                result.message += " Cleanup also failed: " + failure;
            }
            // Preserve all lower-level dependencies beneath the first failed
            // unload. This process is poisoned and will not reuse them.
            handles.clear();
            rpc_handle = nullptr;
        }
        return closed;
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

struct SdxlExactRuntimeProfileRequest {
    std::string host_directory;
    std::string dsp_directory;
    int htp_arch_version = 0;
};

bool parse_sdxl_exact_runtime_profile(
        const std::string& json,
        SdxlExactRuntimeProfileRequest* profile,
        std::string* error) {
    if (profile == nullptr || error == nullptr) return false;
    try {
        const nlohmann::json root = nlohmann::json::parse(json);
        if (!root.is_object() || root.size() != 4U) {
            *error = "SDXL runtime profile must be one versioned object with exact host and DSP roles.";
            return false;
        }
        const auto version = root.find("version");
        const auto host = root.find("hostDirectory");
        const auto dsp = root.find("dspDirectory");
        const auto arch = root.find("htpArchVersion");
        if (version == root.end() || !version->is_number_integer() || version->get<int>() != 1 ||
            host == root.end() || !host->is_string() ||
            dsp == root.end() || !dsp->is_string() ||
            arch == root.end() || !arch->is_number_integer()) {
            *error = "SDXL runtime profile version or exact role fields are invalid.";
            return false;
        }
        profile->host_directory = host->get<std::string>();
        profile->dsp_directory = dsp->get<std::string>();
        profile->htp_arch_version = arch->get<int>();
        if (profile->host_directory.empty() || profile->dsp_directory.empty() ||
            profile->htp_arch_version <= 0) {
            *error = "SDXL runtime profile directories and HTP architecture must be explicit.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("SDXL runtime profile JSON is invalid: ") + exception.what();
        return false;
    }
}

RuntimeProbe inspect_sdxl_exact_runtime_profile(
        const std::string& runtime_profile_json,
        bool probe) {
    RuntimeProbe result;
    SdxlExactRuntimeProfileRequest profile;
    std::string error;
    if (!parse_sdxl_exact_runtime_profile(runtime_profile_json, &profile, &error)) {
        result.message = error;
        return result;
    }

    result.exact_role_binding = true;
    result.host_runtime_directory = profile.host_directory;
    result.dsp_runtime_directory = profile.dsp_directory;
    result.runtime_directory = profile.host_directory == profile.dsp_directory
        ? profile.host_directory
        : "host=" + profile.host_directory + ";dsp=" + profile.dsp_directory;
    result.htp_arch_version = profile.htp_arch_version;
    append_unique_path(&result.search_directories, profile.host_directory);
    append_unique_path(&result.search_directories, profile.dsp_directory);
    result.system_path = join_path(profile.host_directory, "libQnnSystem.so");
    result.htp_path = join_path(profile.host_directory, "libQnnHtp.so");
    result.rpc_path = join_path(profile.host_directory, "libcdsprpc.so");
    result.skel_path = join_path(
        profile.dsp_directory,
        "libQnnHtpV" + std::to_string(profile.htp_arch_version) + "Skel.so");
    result.stub_path = join_path(
        profile.dsp_directory,
        "libQnnHtpV" + std::to_string(profile.htp_arch_version) + "Stub.so");
    result.system_present = exists_file(result.system_path);
    result.htp_present = exists_file(result.htp_path);
    result.rpc_present = exists_file(result.rpc_path);
    result.skel_present = exists_file(result.skel_path);
    result.stub_present = exists_file(result.stub_path);
    if (!result.rpc_present) result.rpc_path.clear();

    if (!may_pair_qnn_runtime_directories(profile.host_directory, profile.dsp_directory)) {
        result.message = "Exact SDXL QNN host/DSP roles may pair only in one directory or between platform-image directories.";
        return result;
    }
    if (!result.system_present || !result.htp_present ||
        !result.skel_present || !result.stub_present) {
        result.message = "Exact SDXL QNN runtime is missing host System/HTP or the requested DSP Skel/Stub pair.";
        return result;
    }

    std::vector<std::string> adsp_paths;
    append_unique_path(&adsp_paths, profile.dsp_directory);
    append_common_android_adsp_paths(&adsp_paths);
    result.adsp_library_path = join_adsp_paths(adsp_paths);
    if (!result.adsp_library_path.empty()) {
        setenv("ADSP_LIBRARY_PATH", result.adsp_library_path.c_str(), 1);
    }
    result.rpc_message = result.rpc_present
        ? "libcdsprpc.so is present in the exact SDXL host runtime."
        : "libcdsprpc.so is absent from the exact SDXL host runtime; the platform may resolve it.";

    if (!probe) {
        result.message = "Exact SDXL QNN runtime files found; native load probe not requested.";
        return result;
    }
    ScopedDlHandle system_handle(dlopen(result.system_path.c_str(), RTLD_NOW | RTLD_LOCAL));
    if (!system_handle) {
        const char* load_error = dlerror();
        result.message = std::string("dlopen exact SDXL libQnnSystem.so failed: ") +
            (load_error ? load_error : "unknown error");
        return result;
    }
    result.qnn_system_interface_present =
        dlsym(system_handle.get(), "QnnSystemInterface_getProviders") != nullptr;
    result.loadable = true;
    result.message = result.qnn_system_interface_present
        ? "Exact SDXL QNN host metadata runtime loaded; host/DSP roles remain bound for graph load."
        : "Exact SDXL QNN System library loaded, but QnnSystemInterface_getProviders was not found.";
    std::string unload_error;
    if (!system_handle.close_checked(&unload_error)) {
        g_qnn_runtime_poisoned.store(true);
        result.loadable = false;
        result.qnn_system_interface_present = false;
        result.message += " Cleanup also failed: " + (unload_error.empty()
            ? "Exact SDXL QNN System preflight library unload failed."
            : unload_error);
        system_handle.abandon_without_unload();
    }
    return result;
}

bool select_qnn_runtime_profile_for_context(
        RuntimeProbe* runtime,
        uint32_t context_soc_model,
        int preferred_htp_arch = 0) {
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

    // An explicit phase preference and device detection only rank transports;
    // neither is an admission list. Try the phase preference first, then the
    // physical transport, the context's declared transport, the coherent
    // profile selected by the generic runtime probe, and finally every complete
    // packaged profile. A missing preferred/device profile therefore still
    // reaches real context/graph load through the remaining compatible paths.
    std::vector<int> candidate_arches;
    auto append_arch = [&](int arch) {
        if (arch <= 0) return;
        if (std::find(candidate_arches.begin(), candidate_arches.end(), arch) == candidate_arches.end()) {
            candidate_arches.push_back(arch);
        }
    };
    append_arch(preferred_htp_arch);
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
        runtime->host_runtime_directory = host.directory;
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
            "Selected coherent QNN runtime profile: preferred HTP V%d, context socModel=%u (HTP V%d), device HTP V%d, selected HTP V%d, system=%s, htp=%s, skel=%s, stub=%s",
            preferred_htp_arch,
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
    message << "No coherent QNN runtime profile for preferred HTP V" << preferred_htp_arch
            << ", context socModel=" << context_soc_model
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
        << "\"exactRoleBinding\":" << (probe.exact_role_binding ? "true" : "false") << ","
        << "\"qnnSystemLibraryPath\":" << quote(probe.system_path) << ","
        << "\"qnnHtpLibraryPath\":" << quote(probe.htp_path) << ","
        << "\"htpSkelLibraryPath\":" << quote(probe.skel_path) << ","
        << "\"htpStubLibraryPath\":" << quote(probe.stub_path) << ","
        << "\"cdspRpcLibraryPath\":" << quote(probe.rpc_path) << ","
        << "\"cdspRpcMessage\":" << quote(probe.rpc_message) << ","
        << "\"adspLibraryPath\":" << quote(probe.adsp_library_path) << ","
        << "\"hostRuntimeDirectory\":" << quote(probe.host_runtime_directory) << ","
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
    bool context_identity_required = false;
    bool context_identity_matched = false;
    bool metadata_contract_matched = false;
    bool output_values_finite = false;
    bool output_validation_passed = false;
    std::string graph_name;
    std::string context_binary;
    std::string expected_context_sha256;
    std::string buffer_plan_json;
    std::string validation_json;
    long long context_binary_bytes = 0;
    long long expected_context_size_bytes = 0;
    long long nonzero_output_elements = 0;
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
    probe.expected_context_size_bytes = long_field(
        smoke_spec_json, "expectedContextSizeBytes");
    probe.expected_context_sha256 = string_field(
        smoke_spec_json, "expectedContextSha256");
    const bool identity_size_present = probe.expected_context_size_bytes != 0;
    const bool identity_sha_present = !probe.expected_context_sha256.empty();
    const bool identity_sha_valid = probe.expected_context_sha256.size() == 64U &&
        std::all_of(
            probe.expected_context_sha256.begin(),
            probe.expected_context_sha256.end(),
            [](unsigned char value) {
                return (value >= '0' && value <= '9') ||
                    (value >= 'a' && value <= 'f');
            });
    probe.context_identity_required = identity_size_present || identity_sha_present;
    const bool context_identity_valid = !probe.context_identity_required ||
        (probe.expected_context_size_bytes > 0 && identity_sha_present && identity_sha_valid);
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
        (probe.validation_json.empty() || probe.validation_ready) &&
        context_identity_valid;
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
        << "\"expectedContextSizeBytes\":" << probe.expected_context_size_bytes << ","
        << "\"expectedContextSha256\":" << quote(probe.expected_context_sha256) << ","
        << "\"contextIdentityRequired\":"
        << (probe.context_identity_required ? "true" : "false") << ","
        << "\"contextIdentityMatched\":"
        << (probe.context_identity_matched ? "true" : "false") << ","
        << "\"metadataContractMatched\":"
        << (probe.metadata_contract_matched ? "true" : "false") << ","
        << "\"outputValuesFinite\":"
        << (probe.output_values_finite ? "true" : "false") << ","
        << "\"outputValidationPassed\":"
        << (probe.output_validation_passed ? "true" : "false") << ","
        << "\"nonZeroOutputElements\":" << probe.nonzero_output_elements << ","
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
    bool metadata_contract_matched = false;
    bool output_values_finite = false;
    bool output_validation_passed = false;
    bool resource_release_failed = false;
    long long context_load_ms = 0;
    long long graph_execute_ms = 0;
    long long nonzero_output_elements = 0;
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

    bool reset_checked(std::string* error) {
        if (handle == nullptr) {
            api = nullptr;
            return true;
        }
        if (api == nullptr || api->logFree == nullptr) {
            if (error != nullptr) {
                *error = "QNN logFree is unavailable for a live log handle.";
            }
            return false;
        }
        const Qnn_ErrorHandle_t status = api->logFree(handle);
        if (status != QNN_SUCCESS) {
            if (error != nullptr) {
                std::ostringstream message;
                message << "QNN logFree failed: 0x" << std::hex << status;
                *error = message.str();
            }
            return false;
        }
        api = nullptr;
        handle = nullptr;
        return true;
    }

    void abandon_without_release() {
        api = nullptr;
        handle = nullptr;
    }

    void reset() {
        std::string ignored;
        reset_checked(&ignored);
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
    bool release_failed = false;
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

    const auto fail_release = [&](const std::string& detail) {
        metadata.parsed = false;
        metadata.release_failed = true;
        g_qnn_runtime_poisoned.store(true);
        if (metadata.message.empty()) {
            metadata.message = detail;
        } else if (!detail.empty()) {
            metadata.message += " Cleanup also failed: " + detail;
        }
    };
    const auto close_system_handle = [&]() -> bool {
        std::string unload_error;
        if (system_handle.close_checked(&unload_error)) return true;
        fail_release(unload_error.empty()
            ? "QNN system metadata library unload failed."
            : unload_error);
        // A failed dlclose leaves the handle's lifetime undefined from the
        // caller's perspective. Keep the dependency pinned and reject future
        // QNN work in this process rather than attempting to reuse it.
        system_handle.abandon_without_unload();
        return false;
    };

    auto get_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(system_handle.get(), "QnnSystemInterface_getProviders")
    );
    if (get_providers == nullptr) {
        metadata.message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        close_system_handle();
        return metadata;
    }

    std::string provider_error;
    const QnnSystemInterface_t* provider = select_qnn_system_interface(get_providers, &provider_error);
    if (provider == nullptr) {
        metadata.message = provider_error;
        close_system_handle();
        return metadata;
    }
    const auto& api = provider->QNN_SYSTEM_INTERFACE_VER_NAME;

    QnnSystemContext_Handle_t sys_context = nullptr;
    const auto release_resources = [&]() -> bool {
        if (sys_context != nullptr) {
            const Qnn_ErrorHandle_t free_status = api.systemContextFree(sys_context);
            if (free_status != QNN_SUCCESS) {
                std::ostringstream detail;
                detail << "QnnSystemContext_free failed: 0x" << std::hex << free_status;
                fail_release(detail.str());
                // A live system context still depends on its provider library. This process is
                // poisoned and disposable, so retain that dependency until process death.
                system_handle.abandon_without_unload();
                sys_context = nullptr;
                return false;
            }
            sys_context = nullptr;
        }
        return close_system_handle();
    };

    Qnn_ErrorHandle_t status = api.systemContextCreate(&sys_context);
    if (status != QNN_SUCCESS || sys_context == nullptr) {
        std::ostringstream out;
        out << "QnnSystemContext_create failed: 0x" << std::hex << status;
        metadata.message = out.str();
        release_resources();
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
        release_resources();
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
            release_resources();
            return metadata;
    }

    std::ostringstream message;
    message << "QNN context binary metadata parsed";
    if (!metadata.build_id.empty()) message << "; buildId=" << metadata.build_id;
    if (!metadata.soc_version.empty()) message << "; socVersion=" << metadata.soc_version;
    if (metadata.soc_model != 0) message << "; socModel=" << metadata.soc_model;
    metadata.message = message.str();
    if (!release_resources()) return metadata;
    metadata.parsed = true;
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

    bool close_checked(std::string* error) {
        bool ok = true;
        std::string failures;
        const auto close_handle = [&](void*& handle, const char* role) -> bool {
            if (handle == nullptr) return true;
            if (dlclose(handle) != 0) {
                ok = false;
                const char* detail = dlerror();
                if (!failures.empty()) failures += "; ";
                failures += std::string("dlclose ") + role + " failed";
                if (detail != nullptr) failures += std::string(": ") + detail;
                return false;
            }
            handle = nullptr;
            return true;
        };
        // QNN objects and log sessions must be released before this method is called.
        // Unload in dependency order and stop at the first failed dependency:
        // if HTP remains loaded, System/RPC must remain pinned beneath it; if
        // System remains loaded, RPC must remain pinned beneath it.
        const bool htp_closed = close_handle(htp_handle, "libQnnHtp");
        const bool system_closed = htp_closed
            ? close_handle(system_handle, "libQnnSystem")
            : false;
        if (system_closed) close_handle(rpc_handle, "libcdsprpc");
        if (!ok) {
            g_qnn_runtime_poisoned.store(true);
            this->ok = false;
            get_providers = nullptr;
            if (error != nullptr) *error = failures;
            return false;
        }
        get_providers = nullptr;
        this->ok = false;
        return ok;
    }

    void abandon_without_unload() {
        ok = false;
        rpc_handle = nullptr;
        system_handle = nullptr;
        htp_handle = nullptr;
        get_providers = nullptr;
    }

    void close() {
        std::string ignored;
        close_checked(&ignored);
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

std::string qnn_data_type_name(Qnn_DataType_t type);
uint64_t qnn_tensor_element_count(const Qnn_Tensor_t& tensor);

std::string normalized_smoke_data_type(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    if (value == "fp16") return "float16";
    if (value == "fp32") return "float32";
    if (value == "fp64") return "float64";
    return value;
}

std::string qnn_smoke_storage_data_type(Qnn_DataType_t type) {
    switch (type) {
        case QNN_DATATYPE_SFIXED_POINT_8: return "int8";
        case QNN_DATATYPE_UFIXED_POINT_8: return "uint8";
        case QNN_DATATYPE_SFIXED_POINT_16: return "int16";
        case QNN_DATATYPE_UFIXED_POINT_16: return "uint16";
        case QNN_DATATYPE_SFIXED_POINT_32: return "int32";
        case QNN_DATATYPE_UFIXED_POINT_32: return "uint32";
        case QNN_DATATYPE_BOOL_8: return "bool";
        default: return normalized_smoke_data_type(qnn_data_type_name(type));
    }
}

bool qnn_smoke_tensor_matches_plan(
        const Qnn_Tensor_t& tensor,
        const NativeTensorPlan& plan,
        std::string* error) {
    const std::string actual_name = qnn_tensor_name(tensor) != nullptr
        ? qnn_tensor_name(tensor)
        : "";
    if (actual_name != plan.name) {
        *error = "QNN smoke tensor name mismatch: expected " + plan.name +
            ", got " + actual_name + ".";
        return false;
    }
    const std::string actual_type = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(tensor));
    const std::string expected_type = normalized_smoke_data_type(plan.data_type);
    if (actual_type != expected_type) {
        *error = "QNN smoke tensor dtype mismatch for " + plan.name +
            ": expected " + expected_type + ", got " + actual_type + ".";
        return false;
    }
    const uint32_t rank = qnn_tensor_rank(tensor);
    uint32_t* dimensions = qnn_tensor_dimensions(tensor);
    if (dimensions == nullptr || rank != plan.shape.size() ||
        !std::equal(plan.shape.begin(), plan.shape.end(), dimensions)) {
        *error = "QNN smoke tensor shape mismatch for " + plan.name + ".";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(tensor);
    const uint64_t logical_bytes = qnn_fallback_tensor_bytes(tensor);
    if (element_count == 0U ||
        element_count != static_cast<uint64_t>(plan.element_count) ||
        logical_bytes != static_cast<uint64_t>(plan.byte_size)) {
        *error = "QNN smoke tensor element/byte contract mismatch for " + plan.name + ".";
        return false;
    }
    return true;
}

bool qnn_smoke_tensor_set_matches_plans(
        const Qnn_Tensor_t* tensors,
        uint32_t tensor_count,
        const std::vector<NativeTensorPlan>& plans,
        const char* role,
        std::string* error) {
    if (tensors == nullptr || tensor_count != plans.size()) {
        std::ostringstream message;
        message << "QNN smoke " << role << " tensor count mismatch: expected "
                << plans.size() << ", got " << tensor_count << ".";
        *error = message.str();
        return false;
    }
    std::vector<bool> consumed(tensor_count, false);
    for (const auto& plan : plans) {
        size_t selected = tensor_count;
        for (size_t index = 0; index < tensor_count; ++index) {
            const char* name = qnn_tensor_name(tensors[index]);
            if (!consumed[index] && name != nullptr && plan.name == name) {
                selected = index;
                break;
            }
        }
        if (selected == tensor_count) {
            *error = "QNN smoke metadata is missing declared " +
                std::string(role) + " tensor " + plan.name + ".";
            return false;
        }
        if (!qnn_smoke_tensor_matches_plan(tensors[selected], plan, error)) return false;
        consumed[selected] = true;
    }
    return true;
}

bool qnn_smoke_output_buffer_valid(
        const Qnn_Tensor_t& tensor,
        const std::vector<uint8_t>& buffer,
        long long* nonzero_elements,
        std::string* error) {
    const uint64_t element_count = qnn_tensor_element_count(tensor);
    const uint32_t element_bytes = qnn_element_bytes(qnn_tensor_data_type(tensor));
    if (element_count == 0U || element_bytes == 0U ||
        element_count > buffer.size() / element_bytes) {
        *error = "QNN smoke output buffer has an invalid element contract.";
        return false;
    }
    long long local_nonzero = 0;
    for (uint64_t index = 0; index < element_count; ++index) {
        const size_t offset = static_cast<size_t>(index) * element_bytes;
        bool raw_nonzero = false;
        for (uint32_t byte = 0; byte < element_bytes; ++byte) {
            raw_nonzero = raw_nonzero || buffer[offset + byte] != 0U;
        }
        if (raw_nonzero) ++local_nonzero;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        for (uint64_t index = 0; index < element_count; ++index) {
            float value = 0.0f;
            std::memcpy(&value, buffer.data() + index * sizeof(float), sizeof(float));
            if (!std::isfinite(value)) {
                *error = "QNN smoke output contains non-finite float32 values.";
                return false;
            }
        }
    } else if (type == QNN_DATATYPE_FLOAT_64) {
        for (uint64_t index = 0; index < element_count; ++index) {
            double value = 0.0;
            std::memcpy(&value, buffer.data() + index * sizeof(double), sizeof(double));
            if (!std::isfinite(value)) {
                *error = "QNN smoke output contains non-finite float64 values.";
                return false;
            }
        }
    } else if (type == QNN_DATATYPE_FLOAT_16 || type == QNN_DATATYPE_BFLOAT_16) {
        const uint16_t exponent_mask = type == QNN_DATATYPE_FLOAT_16 ? 0x7c00U : 0x7f80U;
        for (uint64_t index = 0; index < element_count; ++index) {
            uint16_t bits = 0U;
            std::memcpy(&bits, buffer.data() + index * sizeof(uint16_t), sizeof(uint16_t));
            if ((bits & exponent_mask) == exponent_mask) {
                *error = "QNN smoke output contains non-finite 16-bit float values.";
                return false;
            }
        }
    } else if (type == QNN_DATATYPE_SFIXED_POINT_8 ||
               type == QNN_DATATYPE_UFIXED_POINT_8 ||
               type == QNN_DATATYPE_SFIXED_POINT_16 ||
               type == QNN_DATATYPE_UFIXED_POINT_16 ||
               type == QNN_DATATYPE_SFIXED_POINT_32 ||
               type == QNN_DATATYPE_UFIXED_POINT_32) {
        const auto quant = qnn_tensor_quantize_params(tensor);
        if (quant.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET &&
            (!std::isfinite(quant.scaleOffsetEncoding.scale) ||
             quant.scaleOffsetEncoding.scale <= 0.0f)) {
            *error = "QNN smoke output has an invalid dequantization scale.";
            return false;
        }
    }
    *nonzero_elements += local_nonzero;
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
    if (smoke_spec.context_identity_required &&
        (context_binary.size() !=
             static_cast<size_t>(smoke_spec.expected_context_size_bytes) ||
         mca::qnn::controlnet::sha256_hex_bytes(context_binary) !=
             smoke_spec.expected_context_sha256)) {
        result.execution_stage = "context_identity_mismatch";
        result.message =
            "The exact QNN context bytes loaded for smoke differ from the pinned package identity.";
        return result;
    }

    RuntimeProbe selected_runtime = runtime;
    uint32_t context_soc_model = 0;
    if (runtime.system_present) {
        const QnnBinaryMetadata metadata =
            inspect_qnn_context_binary_metadata(runtime, context_binary);
        if (metadata.release_failed) {
            result.resource_release_failed = true;
            result.execution_stage = "runtime_resource_release_failed";
            result.message = metadata.message;
            return result;
        }
        context_soc_model = metadata.soc_model;
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
    const auto finish_loaded_only = [&]() -> GraphSmokeResult {
        std::string release_error;
        if (!loaded.close_checked(&release_error)) {
            result.resource_release_failed = true;
            const std::string primary_error = result.message;
            result.execution_stage = "runtime_resource_release_failed";
            result.message = release_error.empty()
                ? "QNN graph smoke runtime libraries did not release cleanly."
                : release_error;
            if (!primary_error.empty()) {
                result.message = primary_error + " Cleanup also failed: " + result.message;
            }
        }
        return result;
    };
    if (!loaded.ok) {
        result.execution_stage = "runtime_load_failed";
        result.message = loaded.message;
        return finish_loaded_only();
    }

    std::string provider_error;
    const QnnInterface_t* provider = select_qnn_interface(loaded.get_providers, &provider_error);
    if (provider == nullptr) {
        result.execution_stage = "qnn_provider_missing";
        result.message = provider_error;
        return finish_loaded_only();
    }
    const auto& api = provider->QNN_INTERFACE_VER_NAME;
    QnnLogSession log_session = create_qnn_log_session(api);

    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    const QNN_SYSTEM_INTERFACE_VER_TYPE* sys_api_ptr = nullptr;
    QnnSystemContext_Handle_t sys_context = nullptr;
    bool cleanup_done = false;
    const auto cleanup = [&]() -> bool {
        if (cleanup_done) return !result.resource_release_failed;
        cleanup_done = true;
        bool ok = true;
        bool safe_to_unload = true;
        std::string failures;
        const auto record_failure = [&](const std::string& detail) {
            ok = false;
            safe_to_unload = false;
            if (!detail.empty()) {
                if (!failures.empty()) failures += "; ";
                failures += detail;
            }
        };

        if (sys_context != nullptr) {
            if (sys_api_ptr == nullptr || sys_api_ptr->systemContextFree == nullptr) {
                record_failure(
                    "QnnSystemContext_free is unavailable for a live smoke metadata context.");
            } else {
                const Qnn_ErrorHandle_t status = sys_api_ptr->systemContextFree(sys_context);
                if (status != QNN_SUCCESS) {
                    std::ostringstream detail;
                    detail << "QnnSystemContext_free failed: 0x" << std::hex << status;
                    record_failure(detail.str());
                } else {
                    sys_context = nullptr;
                }
            }
        }
        bool qnn_objects_released = true;
        if (context != nullptr) {
            if (api.contextFree == nullptr) {
                record_failure("QNN contextFree is unavailable for a live smoke context handle.");
                qnn_objects_released = false;
            } else {
                const Qnn_ErrorHandle_t status = api.contextFree(context, nullptr);
                if (status != QNN_SUCCESS) {
                    record_failure(
                        "QNN contextFree failed: " + qnn_error_to_string(api, status));
                    qnn_objects_released = false;
                } else {
                    context = nullptr;
                }
            }
        }
        if (qnn_objects_released && device != nullptr) {
            if (api.deviceFree == nullptr) {
                record_failure("QNN deviceFree is unavailable for a live smoke device handle.");
                qnn_objects_released = false;
            } else {
                const Qnn_ErrorHandle_t status = api.deviceFree(device);
                if (status != QNN_SUCCESS) {
                    record_failure(
                        "QNN deviceFree failed: " + qnn_error_to_string(api, status));
                    qnn_objects_released = false;
                } else {
                    device = nullptr;
                }
            }
        }
        if (qnn_objects_released && backend != nullptr) {
            if (api.backendFree == nullptr) {
                record_failure("QNN backendFree is unavailable for a live smoke backend handle.");
                qnn_objects_released = false;
            } else {
                const Qnn_ErrorHandle_t status = api.backendFree(backend);
                if (status != QNN_SUCCESS) {
                    record_failure(
                        "QNN backendFree failed: " + qnn_error_to_string(api, status));
                    qnn_objects_released = false;
                } else {
                    backend = nullptr;
                }
            }
        }
        if (qnn_objects_released) {
            std::string log_error;
            if (!log_session.reset_checked(&log_error)) {
                record_failure(log_error.empty() ? "QNN log release failed." : log_error);
                log_session.abandon_without_release();
            }
        } else {
            // A child QNN object remains live. Retain its log dependency and
            // all provider libraries until this poisoned worker exits.
            log_session.abandon_without_release();
        }
        if (safe_to_unload) {
            std::string unload_error;
            if (!loaded.close_checked(&unload_error)) {
                record_failure(unload_error.empty()
                    ? "QNN graph smoke runtime library unload failed."
                    : unload_error);
            }
        } else {
            // A live QNN object or log handle still depends on the provider
            // libraries. Retain those mappings for the poisoned disposable
            // worker instead of unloading underneath them.
            loaded.abandon_without_unload();
        }
        if (!ok) {
            g_qnn_runtime_poisoned.store(true);
            result.resource_release_failed = true;
            const std::string primary_error = result.message;
            result.execution_stage = "runtime_resource_release_failed";
            result.message = failures.empty()
                ? "QNN graph smoke resources did not release cleanly."
                : failures;
            if (!primary_error.empty()) {
                result.message = primary_error + " Cleanup also failed: " + result.message;
            }
        }
        return ok;
    };
    const auto finish = [&]() -> GraphSmokeResult {
        cleanup();
        return result;
    };

    const Qnn_ErrorHandle_t backend_status = api.backendCreate(log_session.handle, nullptr, &backend);
    if (backend_status != QNN_SUCCESS || backend == nullptr) {
        result.execution_stage = "backend_create_failed";
        result.message = "QNN backendCreate failed: " + qnn_error_to_string(api, backend_status);
        return finish();
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
        return finish();
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
        return finish();
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
        return finish();
    }
    result.graph_resolved = true;

    auto get_system_providers = reinterpret_cast<QnnSystemInterfaceGetProvidersFn>(
        dlsym(loaded.system_handle, "QnnSystemInterface_getProviders")
    );
    if (get_system_providers == nullptr) {
        result.execution_stage = "qnn_system_provider_missing";
        result.message = "QnnSystemInterface_getProviders was not found in libQnnSystem.so.";
        return finish();
    }

    std::string system_provider_error;
    const QnnSystemInterface_t* system_provider =
        select_qnn_system_interface(get_system_providers, &system_provider_error);
    if (system_provider == nullptr) {
        result.execution_stage = "qnn_system_provider_missing";
        result.message = system_provider_error;
        return finish();
    }
    sys_api_ptr = &system_provider->QNN_SYSTEM_INTERFACE_VER_NAME;
    const auto& sys_api = *sys_api_ptr;
    Qnn_ErrorHandle_t system_status = sys_api.systemContextCreate(&sys_context);
    if (system_status != QNN_SUCCESS || sys_context == nullptr) {
        std::ostringstream message;
        message << "QnnSystemContext_create failed: 0x" << std::hex << system_status;
        result.execution_stage = "qnn_metadata_context_create_failed";
        result.message = message.str();
        return finish();
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
        return finish();
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
    if (!graph_meta_found ||
        graph_meta.inputs == nullptr ||
        graph_meta.outputs == nullptr ||
        graph_meta.input_count == 0 ||
        graph_meta.output_count == 0) {
        result.execution_stage = "qnn_graph_metadata_missing";
        result.message = "QNN context metadata did not expose bindable graph inputs and outputs.";
        return finish();
    }

    const auto input_plans = tensor_plans_from_buffer_plan(
        smoke_spec_json, "inputs", "input");
    const auto output_plans = tensor_plans_from_buffer_plan(
        smoke_spec_json, "outputs", "output");
    std::string tensor_contract_error;
    if (!qnn_smoke_tensor_set_matches_plans(
            graph_meta.inputs,
            graph_meta.input_count,
            input_plans,
            "input",
            &tensor_contract_error) ||
        !qnn_smoke_tensor_set_matches_plans(
            graph_meta.outputs,
            graph_meta.output_count,
            output_plans,
            "output",
            &tensor_contract_error)) {
        result.execution_stage = "tensor_metadata_contract_mismatch";
        result.message = tensor_contract_error;
        return finish();
    }
    result.metadata_contract_matched = true;

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
            return finish();
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
            return finish();
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
        return finish();
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
    std::string output_validation_error;
    result.output_values_finite = true;
    for (size_t index = 0; index < outputs.size(); ++index) {
        long long output_nonzero_elements = 0;
        if (!qnn_smoke_output_buffer_valid(
                outputs[index],
                output_buffers[index],
                &output_nonzero_elements,
                &output_validation_error)) {
            result.output_values_finite = false;
            break;
        }
        if (output_nonzero_elements == 0) {
            result.output_values_finite = false;
            output_validation_error = "QNN smoke output tensor " +
                std::string(qnn_tensor_name(outputs[index]) != nullptr
                    ? qnn_tensor_name(outputs[index])
                    : "<unnamed>") +
                " contains only zero values.";
            break;
        }
        result.nonzero_output_elements += output_nonzero_elements;
    }
    result.output_validation_passed = result.output_values_finite &&
        result.nonzero_output_elements > 0;
    if (result.output_validation_passed) {
        result.execution_stage = "graph_execute_passed";
        result.message = append_diagnostic_note(
            "QNN/HTP graphExecute completed with strict tensor/output proof. outputChecksum=" +
                std::to_string(output_checksum),
            result.device_message);
    } else {
        result.execution_stage = "graph_output_invalid";
        result.message = output_validation_error.empty()
            ? "QNN graphExecute returned only zero output elements."
            : output_validation_error;
    }
    return finish();
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
        const double transformed =
            static_cast<double>(value) /
                static_cast<double>(quant.scaleOffsetEncoding.scale) -
            static_cast<double>(quant.scaleOffsetEncoding.offset);
        if (!std::isfinite(transformed)) {
            return std::signbit(transformed)
                ? std::numeric_limits<int64_t>::min()
                : std::numeric_limits<int64_t>::max();
        }
        if (transformed >= static_cast<double>(std::numeric_limits<int64_t>::max())) {
            return std::numeric_limits<int64_t>::max();
        }
        if (transformed <= static_cast<double>(std::numeric_limits<int64_t>::min())) {
            return std::numeric_limits<int64_t>::min();
        }
        return static_cast<int64_t>(std::llround(transformed));
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
        case QNN_DATATYPE_INT_64: return "int64";
        case QNN_DATATYPE_UINT_64: return "uint64";
        case QNN_DATATYPE_FLOAT_16: return "float16";
        case QNN_DATATYPE_BFLOAT_16: return "bfloat16";
        case QNN_DATATYPE_FLOAT_32: return "float32";
        case QNN_DATATYPE_FLOAT_64: return "float64";
        case QNN_DATATYPE_BOOL_8: return "bool";
        case QNN_DATATYPE_UFIXED_POINT_8: return "ufixed8";
        case QNN_DATATYPE_SFIXED_POINT_8: return "sfixed8";
        case QNN_DATATYPE_UFIXED_POINT_16: return "ufixed16";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sfixed16";
        case QNN_DATATYPE_UFIXED_POINT_32: return "ufixed32";
        case QNN_DATATYPE_SFIXED_POINT_32: return "sfixed32";
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

bool qnn_tensor_buffer_has_capacity(
        const QnnTensorBinding& binding,
        uint64_t element_count,
        size_t bytes_per_element,
        const char* value_kind,
        std::string* error) {
    if (bytes_per_element == 0U ||
        element_count > std::numeric_limits<size_t>::max() / bytes_per_element) {
        if (error != nullptr) {
            *error = std::string(value_kind) + " tensor byte count overflows for " + binding.name;
        }
        return false;
    }
    const size_t required_bytes = static_cast<size_t>(element_count) * bytes_per_element;
    if (binding.buffer.size() < required_bytes) {
        if (error != nullptr) {
            *error = std::string(value_kind) + " tensor buffer is too small for " + binding.name;
        }
        return false;
    }
    return true;
}

bool qnn_has_supported_scale_offset_quantization(const Qnn_Tensor_t& tensor) {
    const auto quantization = qnn_tensor_quantize_params(tensor);
    return quantization.encodingDefinition == QNN_DEFINITION_DEFINED &&
        quantization.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET &&
        std::isfinite(quantization.scaleOffsetEncoding.scale) &&
        quantization.scaleOffsetEncoding.scale > 0.0f;
}

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
        if (!qnn_tensor_buffer_has_capacity(
                *binding, element_count, sizeof(float), "Float", error)) return false;
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(float);
        std::memcpy(binding->buffer.data(), values, bytes);
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                *binding, element_count, sizeof(uint16_t), "Uint16", error)) return false;
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
        if (!qnn_tensor_buffer_has_capacity(
                *binding, element_count, sizeof(int16_t), "Int16", error)) return false;
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
    if (out == nullptr) {
        if (error != nullptr) *error = "Null float tensor destination.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding.tensor);
    if (element_count == 0) {
        *error = "Output tensor has no elements: " + binding.name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding.tensor);
    if (type == QNN_DATATYPE_FLOAT_32) {
        if (!qnn_tensor_buffer_has_capacity(
                binding, element_count, sizeof(float), "Float output", error)) return false;
        out->assign(static_cast<size_t>(element_count), 0.0f);
        const size_t bytes = static_cast<size_t>(element_count) * sizeof(float);
        std::memcpy(out->data(), binding.buffer.data(), bytes);
        return true;
    }
    if (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UINT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                binding, element_count, sizeof(uint16_t), "Uint16 output", error)) return false;
        out->assign(static_cast<size_t>(element_count), 0.0f);
        const auto* values = reinterpret_cast<const uint16_t*>(binding.buffer.data());
        for (uint64_t i = 0; i < element_count; ++i) {
            (*out)[static_cast<size_t>(i)] = qnn_dequantize_value(binding.tensor, values[i]);
        }
        return true;
    }
    if (type == QNN_DATATYPE_SFIXED_POINT_16 || type == QNN_DATATYPE_INT_16) {
        if (!qnn_tensor_buffer_has_capacity(
                binding, element_count, sizeof(int16_t), "Int16 output", error)) return false;
        out->assign(static_cast<size_t>(element_count), 0.0f);
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
        if (runtime.system_present) {
            const QnnBinaryMetadata metadata = inspect_qnn_context_binary_metadata(
                runtime,
                context_probe.data(),
                context_probe.size());
            if (metadata.release_failed) {
                message = metadata.message;
                return false;
            }
            context_soc_model = metadata.soc_model;
        } else {
            context_soc_model = 0;
        }
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
        uint32_t soc_model = 0;
        if (runtime.system_present) {
            const QnnBinaryMetadata metadata = inspect_qnn_context_binary_metadata(
                runtime, binary.data(), binary.size());
            if (metadata.release_failed) {
                message = metadata.message;
                return false;
            }
            soc_model = metadata.soc_model;
        }
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

    bool poisoned = false;

    void mark_poisoned() {
        poisoned = true;
        g_qnn_runtime_poisoned.store(true);
    }

    bool close_checked(std::string* error) {
        const auto fail_and_abandon = [&](const std::string& detail) {
            mark_poisoned();
            log_session.abandon_without_release();
            loaded.abandon_without_unload();
            provider = nullptr;
            system_provider = nullptr;
            device = nullptr;
            backend = nullptr;
            if (error != nullptr) *error = detail;
            return false;
        };
        if (poisoned) {
            return fail_and_abandon(
                "QNN coherent runtime session was poisoned by an earlier resource release failure.");
        }
        if (device != nullptr) {
            if (provider == nullptr ||
                provider->QNN_INTERFACE_VER_NAME.deviceFree == nullptr) {
                return fail_and_abandon(
                    "QNN deviceFree is unavailable for a live coherent-session device handle.");
            }
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::DeviceReleaseBefore,
                    kQnnImageContextRelease);
            }
            const Qnn_ErrorHandle_t status =
                provider->QNN_INTERFACE_VER_NAME.deviceFree(device);
            if (status != QNN_SUCCESS) {
                return fail_and_abandon(
                    "QNN deviceFree failed: " + qnn_error_to_string(
                        provider->QNN_INTERFACE_VER_NAME, status));
            }
            device = nullptr;
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::DeviceReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (backend != nullptr) {
            if (provider == nullptr ||
                provider->QNN_INTERFACE_VER_NAME.backendFree == nullptr) {
                return fail_and_abandon(
                    "QNN backendFree is unavailable for a live coherent-session backend handle.");
            }
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::BackendReleaseBefore,
                    kQnnImageContextRelease);
            }
            const Qnn_ErrorHandle_t status =
                provider->QNN_INTERFACE_VER_NAME.backendFree(backend);
            if (status != QNN_SUCCESS) {
                return fail_and_abandon(
                    "QNN backendFree failed: " + qnn_error_to_string(
                        provider->QNN_INTERFACE_VER_NAME, status));
            }
            backend = nullptr;
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::BackendReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        const bool had_log = log_session.handle != nullptr;
        if (had_log && generation != nullptr) {
            generation->record_stage(
                mca::qnn::ImageStage::LogReleaseBefore,
                kQnnImageContextRelease);
        }
        std::string log_error;
        if (!log_session.reset_checked(&log_error)) {
            return fail_and_abandon(log_error.empty()
                ? "QNN log release failed."
                : log_error);
        }
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
            std::string unload_error;
            if (!loaded.close_checked(&unload_error)) {
                return fail_and_abandon(unload_error.empty()
                    ? "QNN coherent runtime library unload failed."
                    : unload_error);
            }
            if (generation != nullptr) {
                generation->record_stage(
                    mca::qnn::ImageStage::RuntimeUnloadAfter,
                    kQnnImageContextRelease);
            }
        }
        provider = nullptr;
        system_provider = nullptr;
        if (error != nullptr) error->clear();
        return true;
    }

    void close() {
        std::string ignored;
        close_checked(&ignored);
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
    bool encoder_graph = false;
    RuntimeProbe selected_runtime;
    std::vector<QnnTensorBinding> inputs;
    std::vector<QnnTensorBinding> outputs;
    std::string graph_name;
    std::string context_binary_sha256;
    long long context_load_ms = 0;
    std::string device_message;
    std::string message;

    ~QnnExecutableGraph() { close(); }

    void close() {
        std::string ignored;
        close_checked(&ignored);
    }

    bool close_checked(std::string* error) {
        const auto fail_and_abandon = [&](const std::string& detail) {
            g_qnn_runtime_poisoned.store(true);
            if (shared_session != nullptr) shared_session->mark_poisoned();
            log_session.abandon_without_release();
            loaded.abandon_without_unload();
            context_binary.abandon_without_unmap();
            system_context = nullptr;
            context = nullptr;
            device = nullptr;
            backend = nullptr;
            provider = nullptr;
            system_provider = nullptr;
            shared_session = nullptr;
            if (error != nullptr) *error = detail;
            return false;
        };
        if (shared_session != nullptr && shared_session->poisoned) {
            return fail_and_abandon(
                "QNN coherent runtime session was poisoned by an earlier graph release failure.");
        }
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
        if (system_context != nullptr) {
            if (system_provider == nullptr ||
                system_provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree == nullptr) {
                return fail_and_abandon(
                    "QnnSystemContext_free is unavailable for a live system context handle.");
            }
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeMetadataReleaseBefore
                        : mca::qnn::ImageStage::UnetMetadataReleaseBefore,
                    kQnnImageContextRelease);
            }
            const Qnn_ErrorHandle_t status =
                system_provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree(system_context);
            if (status != QNN_SUCCESS) {
                std::ostringstream detail;
                detail << "QnnSystemContext_free failed: 0x" << std::hex << status;
                return fail_and_abandon(detail.str());
            }
            system_context = nullptr;
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeMetadataReleaseAfter
                        : mca::qnn::ImageStage::UnetMetadataReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (context != nullptr) {
            if (provider == nullptr || provider->QNN_INTERFACE_VER_NAME.contextFree == nullptr) {
                return fail_and_abandon(
                    "QNN contextFree is unavailable for a live context handle.");
            }
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeContextReleaseBefore
                        : mca::qnn::ImageStage::UnetContextReleaseBefore,
                    kQnnImageContextRelease);
            }
            const Qnn_ErrorHandle_t status =
                provider->QNN_INTERFACE_VER_NAME.contextFree(context, nullptr);
            if (status != QNN_SUCCESS) {
                return fail_and_abandon(
                    "QNN contextFree failed: " + qnn_error_to_string(
                        provider->QNN_INTERFACE_VER_NAME, status));
            }
            context = nullptr;
            if (generation != nullptr && shared_session != nullptr) {
                generation->record_stage(
                    vae_graph
                        ? mca::qnn::ImageStage::VaeContextReleaseAfter
                        : mca::qnn::ImageStage::UnetContextReleaseAfter,
                    kQnnImageContextRelease);
            }
        }
        if (shared_session == nullptr && device != nullptr) {
            if (provider == nullptr || provider->QNN_INTERFACE_VER_NAME.deviceFree == nullptr) {
                return fail_and_abandon(
                    "QNN deviceFree is unavailable for a live standalone device handle.");
            }
            const Qnn_ErrorHandle_t status =
                provider->QNN_INTERFACE_VER_NAME.deviceFree(device);
            if (status != QNN_SUCCESS) {
                return fail_and_abandon(
                    "QNN deviceFree failed: " + qnn_error_to_string(
                        provider->QNN_INTERFACE_VER_NAME, status));
            }
            device = nullptr;
        }
        if (shared_session == nullptr && backend != nullptr) {
            if (provider == nullptr || provider->QNN_INTERFACE_VER_NAME.backendFree == nullptr) {
                return fail_and_abandon(
                    "QNN backendFree is unavailable for a live standalone backend handle.");
            }
            const Qnn_ErrorHandle_t status =
                provider->QNN_INTERFACE_VER_NAME.backendFree(backend);
            if (status != QNN_SUCCESS) {
                return fail_and_abandon(
                    "QNN backendFree failed: " + qnn_error_to_string(
                        provider->QNN_INTERFACE_VER_NAME, status));
            }
            backend = nullptr;
        }
        if (shared_session == nullptr) {
            std::string log_error;
            if (!log_session.reset_checked(&log_error)) {
                return fail_and_abandon(log_error.empty()
                    ? "QNN log release failed."
                    : log_error);
            }
            std::string unload_error;
            if (!loaded.close_checked(&unload_error)) {
                return fail_and_abandon(unload_error.empty()
                    ? "QNN runtime library unload failed."
                    : unload_error);
            }
        }
        std::string unmap_error;
        if (!context_binary.close_checked(&unmap_error)) {
            return fail_and_abandon(unmap_error.empty()
                ? "QNN context binary unmap failed."
                : unmap_error);
        }
        provider = nullptr;
        system_provider = nullptr;
        shared_session = nullptr;
        if (error != nullptr) error->clear();
        return true;
    }

    bool load_in_session(
            QnnCoherentRuntimeSession* session,
            const RuntimeProbe& runtime,
            const std::string& context_path,
            const std::string& requested_graph_name,
            bool is_vae,
            bool is_encoder = false,
            const std::string& expected_context_sha256 = "",
            long long expected_context_size_bytes = 0,
            dev_t expected_context_device = 0,
            ino_t expected_context_inode = 0) {
        shared_session = session;
        generation = session != nullptr ? session->generation : nullptr;
        vae_graph = is_vae;
        encoder_graph = is_encoder;
        if (session == nullptr || session->provider == nullptr || session->backend == nullptr) {
            message = "QNN coherent runtime session is not ready.";
            return false;
        }
        provider = session->provider;
        system_provider = session->system_provider;
        if (generation != nullptr) {
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderBinaryMmap
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeBinaryMmap
                        : mca::qnn::ImageStage::UnetBinaryMmap),
                kQnnImageContextBinaryMmap);
        }
        if (!context_binary.open_file(context_path, &message)) return false;
        if (!expected_context_sha256.empty()) {
            const bool expected_identity = expected_context_size_bytes > 0 &&
                static_cast<uint64_t>(context_binary.size()) ==
                    static_cast<uint64_t>(expected_context_size_bytes) &&
                context_binary.device() == expected_context_device &&
                context_binary.inode() == expected_context_inode;
            context_binary_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                context_binary.data(), context_binary.size());
            if (!expected_identity || context_binary_sha256.empty() ||
                context_binary_sha256 != expected_context_sha256) {
                message = "Mapped QNN context identity differs from the verified asset.";
                return false;
            }
        }
        if (!session->accepts_context(runtime, context_binary)) {
            message = session->message;
            return false;
        }

        const auto& api = provider->QNN_INTERFACE_VER_NAME;
        if (generation != nullptr) {
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderContextCreateBefore
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeContextCreateBefore
                        : mca::qnn::ImageStage::UnetContextCreateBefore),
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderContextCreateAfter
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeContextCreateAfter
                        : mca::qnn::ImageStage::UnetContextCreateAfter),
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
        if (!graph_meta_found && graph_count > 0 && !is_encoder) {
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderGraphRetrieveBefore
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeGraphRetrieveBefore
                        : mca::qnn::ImageStage::UnetGraphRetrieveBefore),
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderGraphRetrieveAfter
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeGraphRetrieveAfter
                        : mca::qnn::ImageStage::UnetGraphRetrieveAfter),
                kQnnImageContextCreate);
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderTensorBindBefore
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeTensorBindBefore
                        : mca::qnn::ImageStage::UnetTensorBindBefore),
                kQnnImageContextCreate);
        }
        if (!bind_tensors(sys_api, graph_meta.inputs, graph_meta.input_count, true, &inputs) ||
            !bind_tensors(sys_api, graph_meta.outputs, graph_meta.output_count, false, &outputs)) {
            return false;
        }
        if (generation != nullptr) {
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderTensorBindAfter
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeTensorBindAfter
                        : mca::qnn::ImageStage::UnetTensorBindAfter),
                kQnnImageContextCreate);
        }
        return true;
    }

    bool load(
            const RuntimeProbe& runtime,
            const std::string& context_path,
            const std::string& requested_graph_name,
            const QnnImageGenerationScope* generation_scope = nullptr,
            bool is_vae = false,
            bool is_encoder = false,
            const std::string& expected_context_sha256 = "",
            int preferred_htp_arch = 0) {
        generation = generation_scope;
        vae_graph = is_vae;
        encoder_graph = is_encoder;
        if (generation != nullptr) {
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderBinaryMmap
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeBinaryMmap
                        : mca::qnn::ImageStage::UnetBinaryMmap),
                kQnnImageContextBinaryMmap);
        }
        if (!context_binary.open_file(context_path, &message)) {
            return false;
        }
        if (!expected_context_sha256.empty()) {
            context_binary_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                context_binary.data(), context_binary.size());
            if (context_binary_sha256.empty() ||
                context_binary_sha256 != expected_context_sha256) {
                message = "Mapped QNN context bytes differ from the Provider-bound SHA-256.";
                return false;
            }
        }
        selected_runtime = runtime;
        uint32_t context_soc_model = 0;
        if (runtime.system_present) {
            const QnnBinaryMetadata metadata = inspect_qnn_context_binary_metadata(
                runtime,
                context_binary.data(),
                context_binary.size()
            );
            if (metadata.release_failed) {
                message = metadata.message;
                return false;
            }
            context_soc_model = metadata.soc_model;
        }
        if (selected_runtime.exact_role_binding) {
            if (preferred_htp_arch <= 0 ||
                selected_runtime.htp_arch_version != preferred_htp_arch) {
                message = "Exact SDXL QNN runtime HTP architecture differs from the phase request.";
                return false;
            }
        } else {
            if (!select_qnn_runtime_profile_for_context(
                    &selected_runtime,
                    context_soc_model,
                    preferred_htp_arch)) {
                message = selected_runtime.message;
                return false;
            }
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderContextCreateBefore
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeContextCreateBefore
                        : mca::qnn::ImageStage::UnetContextCreateBefore),
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderContextCreateAfter
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeContextCreateAfter
                        : mca::qnn::ImageStage::UnetContextCreateAfter),
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
        if (!graph_meta_found && graph_count > 0 && !is_encoder) {
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderGraphRetrieveBefore
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeGraphRetrieveBefore
                        : mca::qnn::ImageStage::UnetGraphRetrieveBefore),
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
                is_encoder
                    ? mca::qnn::ImageStage::EncoderGraphRetrieveAfter
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeGraphRetrieveAfter
                        : mca::qnn::ImageStage::UnetGraphRetrieveAfter),
                kQnnImageContextCreate);
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderTensorBindBefore
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeTensorBindBefore
                        : mca::qnn::ImageStage::UnetTensorBindBefore),
                kQnnImageContextCreate);
        }

        if (!bind_tensors(sys_api, graph_meta.inputs, graph_meta.input_count, true, &inputs)) return false;
        if (!bind_tensors(sys_api, graph_meta.outputs, graph_meta.output_count, false, &outputs)) return false;
        if (generation != nullptr) {
            generation->record_stage(
                is_encoder
                    ? mca::qnn::ImageStage::EncoderTensorBindAfter
                    : (is_vae
                        ? mca::qnn::ImageStage::VaeTensorBindAfter
                        : mca::qnn::ImageStage::UnetTensorBindAfter),
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

    bool execute(
            long long* execute_ms,
            std::string* error,
            bool preview_vae_execution = false) {
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
                encoder_graph
                    ? mca::qnn::ImageStage::EncoderGraphExecute
                    : (vae_graph && preview_vae_execution
                    ? mca::qnn::ImageStage::PreviewVaeGraphExecute
                    : (vae_graph
                        ? mca::qnn::ImageStage::VaeGraphExecute
                        : mca::qnn::ImageStage::UnetGraphExecute)),
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

std::string fixed_width_lower_hex_u64(uint64_t value) {
    std::ostringstream out;
    out << std::hex << std::nouppercase << std::setfill('0') << std::setw(16) << value;
    return out.str();
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

bool qnn_laplacian_blend_inpaint_vae_output(
        const QnnTensorBinding& tensor,
        const std::vector<float>& generated_values,
        mca::qnn::ImagePixelRange pixel_range,
        const std::vector<float>& source_nchw,
        const std::vector<float>& full_mask,
        std::vector<float>* blended_values,
        int* blend_levels,
        uint64_t* blend_checksum,
        std::string* error) {
    if (blended_values == nullptr || blend_levels == nullptr ||
        blend_checksum == nullptr || error == nullptr || tensor.dimensions.size() != 4U) {
        if (error != nullptr) *error = "Inpaint VAE pixel blend arguments are invalid.";
        return false;
    }
    bool nchw = false;
    size_t width = 0U;
    size_t height = 0U;
    if (tensor.dimensions[0] == 1U && tensor.dimensions[1] == 3U) {
        nchw = true;
        height = tensor.dimensions[2];
        width = tensor.dimensions[3];
    } else if (tensor.dimensions[0] == 1U && tensor.dimensions[3] == 3U) {
        height = tensor.dimensions[1];
        width = tensor.dimensions[2];
    } else {
        *error = "Inpaint VAE pixel blend requires one RGB NCHW or NHWC output.";
        return false;
    }
    const uint64_t plane_u64 = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (width == 0U || height == 0U ||
        plane_u64 > std::numeric_limits<size_t>::max() / 3U) {
        *error = "Inpaint VAE pixel blend dimensions overflow the host buffer.";
        return false;
    }
    const size_t plane = static_cast<size_t>(plane_u64);
    const size_t expected = plane * 3U;
    if (generated_values.size() != expected || source_nchw.size() != expected ||
        full_mask.size() != plane) {
        *error = "Inpaint VAE pixel blend tensors do not share one full-resolution shape.";
        return false;
    }
    const auto to_canonical = [pixel_range](float value) -> float {
        switch (pixel_range) {
            case mca::qnn::ImagePixelRange::NegativeOneToOne: return value;
            case mca::qnn::ImagePixelRange::ZeroToOne: return value * 2.0f - 1.0f;
            case mca::qnn::ImagePixelRange::ZeroTo255: return value * (2.0f / 255.0f) - 1.0f;
        }
        return std::numeric_limits<float>::quiet_NaN();
    };
    const auto from_canonical = [pixel_range](float value) -> float {
        switch (pixel_range) {
            case mca::qnn::ImagePixelRange::NegativeOneToOne: return value;
            case mca::qnn::ImagePixelRange::ZeroToOne: return (value + 1.0f) * 0.5f;
            case mca::qnn::ImagePixelRange::ZeroTo255: return (value + 1.0f) * 127.5f;
        }
        return std::numeric_limits<float>::quiet_NaN();
    };
    std::vector<float> generated_nchw(expected, 0.0f);
    for (size_t channel = 0U; channel < 3U; ++channel) {
        for (size_t pixel = 0U; pixel < plane; ++pixel) {
            const size_t source_index = nchw ? channel * plane + pixel : pixel * 3U + channel;
            generated_nchw[channel * plane + pixel] = to_canonical(generated_values[source_index]);
        }
    }
    std::vector<float> blended_nchw;
    if (!mca::qnn::inpaint::laplacian_pyramid_blend_nchw(
            source_nchw,
            generated_nchw,
            full_mask,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            &blended_nchw,
            blend_levels,
            error)) {
        return false;
    }
    *blend_checksum = checksum_float_vector(blended_nchw);
    if (*blend_checksum == 0U) {
        *error = "Inpaint Laplacian blend produced no checksum evidence.";
        return false;
    }
    blended_values->assign(expected, 0.0f);
    for (size_t channel = 0U; channel < 3U; ++channel) {
        for (size_t pixel = 0U; pixel < plane; ++pixel) {
            const size_t target_index = nchw ? channel * plane + pixel : pixel * 3U + channel;
            (*blended_values)[target_index] = from_canonical(blended_nchw[channel * plane + pixel]);
        }
    }
    if (!std::all_of(blended_values->begin(), blended_values->end(), [](float value) {
            return std::isfinite(value);
        })) {
        blended_values->clear();
        *error = "Inpaint Laplacian blend could not be represented in the VAE output range.";
        return false;
    }
    error->clear();
    return true;
}

bool write_vae_tensor_png(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        mca::qnn::ImagePixelRange pixel_range,
        mca::qnn::ImagePixelRangeEvidence* pixel_range_evidence,
        int* width,
        int* height,
        std::string* error) {
    if (pixel_range_evidence == nullptr || width == nullptr || height == nullptr || error == nullptr) {
        return false;
    }
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

    std::vector<uint8_t> rgb(expected, 0);
    *pixel_range_evidence = mca::qnn::ImagePixelRangeEvidence{};
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
                const float value = sample(c, y, x);
                uint8_t byte_value = 0;
                bool clamped = false;
                if (!mca::qnn::image_pixel_to_u8(
                        value,
                        pixel_range,
                        &byte_value,
                        &clamped,
                        error)) {
                    return false;
                }
                pixel_range_evidence->value_count += 1;
                if (clamped) pixel_range_evidence->clamped_value_count += 1;
                pixel_range_evidence->observed_min = std::min(
                    pixel_range_evidence->observed_min,
                    value);
                pixel_range_evidence->observed_max = std::max(
                    pixel_range_evidence->observed_max,
                    value);
                rgb[(static_cast<size_t>(y) * (*width) + x) * 3 + c] = byte_value;
            }
        }
    }
    if (stbi_write_png(output_path.c_str(), *width, *height, 3, rgb.data(), (*width) * 3) == 0) {
        *error = "Failed to write PNG output: " + output_path;
        return false;
    }
    return true;
}

bool fsync_regular_file(const std::string& path, std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Unable to open preview PNG for fsync.";
        return false;
    }
    struct stat st {};
    const bool valid = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        st.st_size > 0 && static_cast<uint64_t>(st.st_size) <=
            mca::qnn::preview::kMaximumPngBytes;
    const bool synced = valid && ::fsync(fd) == 0;
    ::close(fd);
    if (!valid) {
        *error = "Preview PNG is empty, non-regular, or exceeds 32 MiB.";
        return false;
    }
    if (!synced) {
        *error = "Unable to fsync preview PNG.";
        return false;
    }
    return true;
}

bool fsync_directory(const std::string& path, std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Unable to open preview directory for fsync.";
        return false;
    }
    const bool synced = ::fsync(fd) == 0;
    ::close(fd);
    if (!synced) {
        *error = "Unable to fsync preview directory.";
        return false;
    }
    return true;
}

uint32_t read_png_u32(const std::array<uint8_t, 29>& header, size_t offset) {
    return (static_cast<uint32_t>(header[offset]) << 24U) |
        (static_cast<uint32_t>(header[offset + 1U]) << 16U) |
        (static_cast<uint32_t>(header[offset + 2U]) << 8U) |
        static_cast<uint32_t>(header[offset + 3U]);
}

bool validate_preview_png(
        const std::string& path,
        int expected_width,
        int expected_height,
        std::string* error) {
    if (!fsync_regular_file(path, error)) return false;
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Unable to reopen preview PNG for validation.";
        return false;
    }
    std::array<uint8_t, 29> header{};
    size_t consumed = 0U;
    while (consumed < header.size()) {
        const ssize_t read_count = ::read(
            fd,
            header.data() + consumed,
            header.size() - consumed);
        if (read_count <= 0) break;
        consumed += static_cast<size_t>(read_count);
    }
    ::close(fd);
    static constexpr std::array<uint8_t, 8> kPngSignature = {
        0x89U, 0x50U, 0x4eU, 0x47U, 0x0dU, 0x0aU, 0x1aU, 0x0aU,
    };
    const bool signature_matches = consumed == header.size() &&
        std::equal(kPngSignature.begin(), kPngSignature.end(), header.begin());
    const bool ihdr_matches = read_png_u32(header, 8U) == 13U &&
        header[12] == 'I' && header[13] == 'H' &&
        header[14] == 'D' && header[15] == 'R';
    const bool dimensions_match =
        read_png_u32(header, 16U) == static_cast<uint32_t>(expected_width) &&
        read_png_u32(header, 20U) == static_cast<uint32_t>(expected_height);
    const bool format_matches = header[24] == 8U && header[25] == 2U &&
        header[26] == 0U && header[27] == 0U && header[28] == 0U;
    if (!signature_matches || !ihdr_matches || !dimensions_match || !format_matches) {
        *error = "Preview output is not the expected non-interlaced 8-bit RGB PNG.";
        return false;
    }
    return true;
}

bool write_ultrafix_png_atomic(
        const QnnTensorBinding& tensor,
        const std::vector<float>& values,
        const std::string& output_path,
        mca::qnn::ImagePixelRange pixel_range,
        mca::qnn::ImagePixelRangeEvidence* pixel_range_evidence,
        int* width,
        int* height,
        std::string* error) {
    if (output_path.empty() || error == nullptr) return false;
    const size_t separator = output_path.find_last_of('/');
    if (separator == std::string::npos || separator == 0U) {
        *error = "UltraFix output must be inside an app-owned directory.";
        return false;
    }
    const std::string directory = output_path.substr(0U, separator);
    const std::string temporary = output_path + ".ultrafix.part";
    ::unlink(temporary.c_str());
    if (!write_vae_tensor_png(
            tensor,
            values,
            temporary,
            pixel_range,
            pixel_range_evidence,
            width,
            height,
            error) ||
        !validate_preview_png(temporary, *width, *height, error)) {
        ::unlink(temporary.c_str());
        return false;
    }
    if (qnn_image_generation_cancelled()) {
        ::unlink(temporary.c_str());
        *error = "Image generation was cancelled before UltraFix output publication.";
        return false;
    }
    struct stat existing {};
    if (::lstat(output_path.c_str(), &existing) == 0 ||
        ::rename(temporary.c_str(), output_path.c_str()) != 0) {
        ::unlink(temporary.c_str());
        *error = "UltraFix output atomic rename failed or would replace an existing file.";
        return false;
    }
    if (!fsync_directory(directory, error)) {
        ::unlink(output_path.c_str());
        return false;
    }
    error->clear();
    return true;
}

enum class QnnPreviewPublishOutcome {
    Skipped,
    Published,
    Failed,
    Cancelled,
};

class QnnSharedPreviewPublisher {
public:
    QnnSharedPreviewPublisher(
            mca::qnn::preview::Contract contract,
            std::string journal_path)
        : contract_(std::move(contract)),
          directory_(contract_.enabled
              ? mca::qnn::preview::request_directory_for_journal(journal_path)
              : "") {}

    QnnSharedPreviewPublisher(const QnnSharedPreviewPublisher&) = delete;
    QnnSharedPreviewPublisher& operator=(const QnnSharedPreviewPublisher&) = delete;

    ~QnnSharedPreviewPublisher() {
        // A committed frame and its journal remain available until the Android
        // provider performs its terminal drain and request-scoped cleanup.
        // Only an unpublished temporary file belongs to this native scope.
        if (!temporary_path_.empty()) ::unlink(temporary_path_.c_str());
    }

    bool initialize(std::string* error) {
        if (!contract_.enabled) return true;
        if (directory_.empty()) {
            *error = "Shared QNN preview requires a safe request-scoped stage journal path.";
            fail("PREVIEW_STORAGE_INVALID");
            return false;
        }
        if (::mkdir(directory_.c_str(), 0700) != 0) {
            *error = errno == EEXIST
                ? "Shared QNN preview request directory already exists."
                : "Unable to create the shared QNN preview request directory.";
            fail("PREVIEW_STORAGE_INVALID");
            return false;
        }
        update_qnn_image_preview_progress(audit_, "", "vae", 0, 0U, 0, 0);
        if (!persist_qnn_image_generation_journal()) {
            *error = "Unable to persist the shared QNN preview journal.";
            fail("PREVIEW_JOURNAL_COMMIT_FAILED");
            return false;
        }
        return true;
    }

    const mca::qnn::preview::Contract& contract() const { return contract_; }
    const mca::qnn::preview::Audit& audit() const { return audit_; }

    QnnPreviewPublishOutcome publish_if_due(
            int completed_step,
            int total_steps,
            const std::vector<float>& host_latents,
            double effective_vae_host_scale,
            QnnExecutableGraph& vae,
            mca::qnn::ImagePixelRange pixel_range) {
        if (!mca::qnn::preview::should_publish(
                contract_,
                completed_step,
                total_steps,
                audit_.stopped_after_failure())) {
            return QnnPreviewPublishOutcome::Skipped;
        }
        if (qnn_image_generation_cancelled()) return QnnPreviewPublishOutcome::Cancelled;

        ++audit_.vae_execution_attempt_count;
        if (!sync_audit()) return fail("PREVIEW_JOURNAL_COMMIT_FAILED");
        std::vector<float> preview_latents = host_latents;
        if (effective_vae_host_scale != 1.0) {
            for (float& value : preview_latents) {
                value = static_cast<float>(value * effective_vae_host_scale);
            }
        }
        std::string error;
        if (!qnn_write_float_tensor(
                &vae.inputs[0],
                preview_latents.data(),
                preview_latents.size(),
                &error)) {
            return fail("PREVIEW_VAE_INPUT_BIND_FAILED");
        }
        if (qnn_image_generation_cancelled()) return QnnPreviewPublishOutcome::Cancelled;
        long long execute_ms = 0;
        const bool executed = vae.execute(&execute_ms, &error, true);
        audit_.vae_execution_ms_total += std::max(0LL, execute_ms);
        if (!executed) return fail("PREVIEW_VAE_EXECUTE_FAILED");
        ++audit_.vae_execution_count;
        if (!sync_audit()) return fail("PREVIEW_JOURNAL_COMMIT_FAILED");
        if (qnn_image_generation_cancelled()) return QnnPreviewPublishOutcome::Cancelled;

        std::vector<float> pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
            return fail("PREVIEW_VAE_OUTPUT_READ_FAILED");
        }
        const uint64_t candidate_revision = audit_.last_revision + 1U;
        const std::string file_name =
            mca::qnn::preview::immutable_revision_file_name(candidate_revision);
        if (file_name.empty()) return fail("PREVIEW_REVISION_INVALID");
        const std::string target_path = join_path(directory_, file_name);
        temporary_path_ = target_path + ".tmp";
        ::unlink(temporary_path_.c_str());
        int width = 0;
        int height = 0;
        mca::qnn::ImagePixelRangeEvidence ignored_pixel_range_evidence;
        if (!write_vae_tensor_png(
                vae.outputs[0],
                pixels,
                temporary_path_,
                pixel_range,
                &ignored_pixel_range_evidence,
                &width,
                &height,
                &error)) {
            return fail("PREVIEW_PNG_WRITE_FAILED");
        }
        if (!validate_preview_png(temporary_path_, width, height, &error)) {
            return fail("PREVIEW_PNG_INVALID");
        }

        std::string previous_path;
        {
            std::lock_guard<std::mutex> publish_lock(g_qnn_image_preview_publish_mutex);
            if (qnn_image_generation_cancelled()) {
                ::unlink(temporary_path_.c_str());
                temporary_path_.clear();
                return QnnPreviewPublishOutcome::Cancelled;
            }
            struct stat existing {};
            if (::lstat(target_path.c_str(), &existing) == 0 ||
                ::rename(temporary_path_.c_str(), target_path.c_str()) != 0) {
                return fail("PREVIEW_ATOMIC_RENAME_FAILED");
            }
            temporary_path_.clear();
            if (!fsync_directory(directory_, &error)) {
                ::unlink(target_path.c_str());
                return fail("PREVIEW_DIRECTORY_FSYNC_FAILED");
            }
            previous_path = current_path_;
            const int previous_width = current_width_;
            const int previous_height = current_height_;
            const mca::qnn::preview::Audit previous_audit = audit_;
            const QnnImagePreviewProgress previous_progress =
                snapshot_qnn_image_preview_progress();
            current_path_ = target_path;
            current_width_ = width;
            current_height_ = height;
            ++audit_.publication_count;
            audit_.last_step = completed_step;
            audit_.last_revision = candidate_revision;
            update_qnn_image_preview_progress(
                audit_,
                current_path_,
                "vae",
                completed_step,
                candidate_revision,
                width,
                height);
            if (!sync_audit()) {
                current_path_ = previous_path;
                current_width_ = previous_width;
                current_height_ = previous_height;
                audit_ = previous_audit;
                restore_qnn_image_preview_progress(previous_progress);
                ::unlink(target_path.c_str());
                return fail("PREVIEW_JOURNAL_COMMIT_FAILED");
            }
        }
        if (!previous_path.empty()) ::unlink(previous_path.c_str());
        return QnnPreviewPublishOutcome::Published;
    }

private:
    QnnPreviewPublishOutcome fail(const std::string& code) {
        if (!temporary_path_.empty()) {
            ::unlink(temporary_path_.c_str());
            temporary_path_.clear();
        }
        if (audit_.failure_code.empty()) audit_.failure_code = code;
        sync_audit();
        return QnnPreviewPublishOutcome::Failed;
    }

    bool sync_audit() {
        update_qnn_image_preview_progress(
            audit_, current_path_, contract_.requested ? "vae" : "",
            audit_.last_step, audit_.last_revision, current_width_, current_height_);
        return persist_qnn_image_generation_journal();
    }

    mca::qnn::preview::Contract contract_;
    std::string directory_;
    std::string temporary_path_;
    std::string current_path_;
    int current_width_ = 0;
    int current_height_ = 0;
    mca::qnn::preview::Audit audit_;
};

bool qnn_consumed_payload_sha256(
        const void* payload,
        size_t payload_bytes,
        std::string* digest,
        std::string* error) {
    constexpr size_t kMaxAuditedArtifactBytes = 64U * 1024U * 1024U;
    if (payload == nullptr || payload_bytes == 0U ||
        payload_bytes > kMaxAuditedArtifactBytes || digest == nullptr || error == nullptr) {
        if (error != nullptr) {
            *error = "Consumed artifact bytes are empty or exceed the native SHA-256 audit limit.";
        }
        return false;
    }
    const auto* begin = static_cast<const uint8_t*>(payload);
    const std::vector<uint8_t> consumed_bytes(begin, begin + payload_bytes);
    *digest = mca::qnn::controlnet::sha256_hex_bytes(consumed_bytes);
    if (digest->size() != 64U) {
        *error = "Consumed artifact SHA-256 could not be derived.";
        return false;
    }
    return true;
}

bool read_float_binary_file(
        const std::string& path,
        std::vector<float>* values,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr) {
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
    if (consumed_payload_sha256 != nullptr &&
        !qnn_consumed_payload_sha256(
            values->data(),
            static_cast<size_t>(bytes),
            consumed_payload_sha256,
            error)) {
        return false;
    }
    return true;
}

bool read_int32_binary_file(
        const std::string& path,
        std::vector<int32_t>* values,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr) {
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
    if (consumed_payload_sha256 != nullptr &&
        !qnn_consumed_payload_sha256(
            values->data(),
            static_cast<size_t>(bytes),
            consumed_payload_sha256,
            error)) {
        return false;
    }
    return true;
}

bool qnn_file_sha256(
        const std::string& path,
        std::string* digest,
        std::string* error) {
    if (digest == nullptr || error == nullptr) return false;
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0) {
        *error = "Artifact is missing or empty.";
        return false;
    }
    return mca::qnn::controlnet::sha256_hex_file(path, digest, error);
}

enum class QnnVaeScalingLocation {
    HostBeforeGraph,
    GraphInternal,
    None,
};

struct QnnSchedulerContract {
    mca::diffusion::DiffusionSchedulerConfig config;
    int steps = 0;
    size_t expected_timetable_count = 0;
    size_t expected_unet_execution_count = 0;
    bool scale_model_input = false;
    float eta = 0.0f;
};

struct QnnUltraFixRequest {
    bool enabled = false;
    int target_width = 0;
    int target_height = 0;
    double strength = 0.0;
    int inversion_steps = 0;
    int refinement_steps = 0;
    int tile_size = 0;
    double overlap = 0.0;
};

struct QnnPreviewRequest {
    bool requested = false;
    std::string mode;
    long long interval = 0;
};

bool parse_qnn_preview_request(
        const std::string& params_json,
        QnnPreviewRequest* request,
        std::string* error) {
    if (request == nullptr || error == nullptr) return false;
    *request = QnnPreviewRequest{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.is_object()) {
            *error = "QNN execution params must be one structured JSON object.";
            return false;
        }
        if (!root.contains("preview")) {
            error->clear();
            return true;
        }
        const auto& value = root.at("preview");
        static constexpr std::array<const char*, 2> kFields = {
            "mode",
            "interval",
        };
        if (!value.is_object() || value.size() != kFields.size() ||
            std::any_of(kFields.begin(), kFields.end(), [&](const char* field) {
                return !value.contains(field);
            }) ||
            !value.at("mode").is_string() ||
            !value.at("interval").is_number_integer()) {
            *error = "QNN preview must contain exactly string mode and exact integer interval fields.";
            return false;
        }
        request->requested = true;
        request->mode = value.at("mode").get<std::string>();
        request->interval = value.at("interval").get<long long>();
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN preview JSON is invalid: ") + exception.what();
        return false;
    }
}

bool parse_qnn_ultrafix_request(
        const std::string& params_json,
        QnnUltraFixRequest* request,
        std::string* error) {
    if (request == nullptr || error == nullptr) return false;
    *request = QnnUltraFixRequest{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.contains("ultraFix")) {
            error->clear();
            return true;
        }
        const auto& value = root.at("ultraFix");
        static constexpr std::array<const char*, 7> kFields = {
            "targetWidth",
            "targetHeight",
            "strength",
            "inversionSteps",
            "refinementSteps",
            "tileSize",
            "overlap",
        };
        if (!value.is_object() || value.size() != kFields.size() ||
            std::any_of(kFields.begin(), kFields.end(), [&](const char* field) {
                return !value.contains(field);
            })) {
            *error = "QNN UltraFix must contain exactly the seven structured request fields.";
            return false;
        }
        const auto exact_int = [&](const char* field, int* output) {
            const auto& item = value.at(field);
            if (!item.is_number_integer()) return false;
            const long long parsed = item.get<long long>();
            if (parsed < std::numeric_limits<int>::min() ||
                parsed > std::numeric_limits<int>::max()) return false;
            *output = static_cast<int>(parsed);
            return true;
        };
        if (!exact_int("targetWidth", &request->target_width) ||
            !exact_int("targetHeight", &request->target_height) ||
            !exact_int("inversionSteps", &request->inversion_steps) ||
            !exact_int("refinementSteps", &request->refinement_steps) ||
            !exact_int("tileSize", &request->tile_size) ||
            !value.at("strength").is_number() ||
            !value.at("overlap").is_number()) {
            *error = "QNN UltraFix dimensions and step counts must be exact integers, with finite numeric strength and overlap.";
            return false;
        }
        request->strength = value.at("strength").get<double>();
        request->overlap = value.at("overlap").get<double>();
        request->enabled = true;
        if (request->target_width < 512 || request->target_width > 2048 ||
            request->target_height < 512 || request->target_height > 2048 ||
            request->target_width % 8 != 0 || request->target_height % 8 != 0 ||
            request->tile_size < 128 || request->tile_size > 2048 ||
            request->tile_size % 8 != 0 ||
            request->tile_size > std::min(
                request->target_width, request->target_height) ||
            !std::isfinite(request->strength) || request->strength <= 0.0 ||
            request->strength > 1.0 || request->inversion_steps < 1 ||
            request->inversion_steps > 100 || request->refinement_steps < 1 ||
            request->refinement_steps > 100 ||
            request->inversion_steps > request->refinement_steps ||
            !std::isfinite(request->overlap) || request->overlap < 0.0 ||
            request->overlap > 0.5) {
            *error = "QNN UltraFix request exceeds the bounded target, tile, strength, step, or overlap contract.";
            return false;
        }
        if (!root.contains("vaeTiling") || !root.at("vaeTiling").is_object()) {
            *error = "QNN UltraFix requires its matching request-local VAE tile controls.";
            return false;
        }
        const auto& vae_tiling = root.at("vaeTiling");
        if (vae_tiling.size() != 2U || !vae_tiling.contains("tileSize") ||
            !vae_tiling.contains("overlap") ||
            !vae_tiling.at("tileSize").is_number_integer() ||
            !vae_tiling.at("overlap").is_number() ||
            vae_tiling.at("tileSize").get<long long>() != request->tile_size ||
            !std::isfinite(vae_tiling.at("overlap").get<double>()) ||
            std::fabs(vae_tiling.at("overlap").get<double>() - request->overlap) >
                1.0e-12) {
            *error = "QNN UltraFix VAE tile controls conflict with its structured request.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN UltraFix JSON is invalid: ") + exception.what();
        return false;
    }
}

struct QnnUltraFixExecutionEvidence {
    QnnUltraFixRequest request;
    mca::image::UltraFixTilePlan plan;
    std::string tile_plan_sha256;
    size_t source_width = 0U;
    size_t source_height = 0U;
    size_t source_resized_width = 0U;
    size_t source_resized_height = 0U;
    size_t source_crop_left = 0U;
    size_t source_crop_top = 0U;
    size_t encoder_context_load_count = 0U;
    size_t encoder_graph_execution_count = 0U;
    size_t encoder_tile_success_count = 0U;
    size_t inversion_step_count = 0U;
    size_t inversion_graph_execution_count = 0U;
    size_t inversion_tile_success_count = 0U;
    size_t refinement_step_count = 0U;
    size_t refinement_positive_graph_execution_count = 0U;
    size_t refinement_negative_graph_execution_count = 0U;
    size_t refinement_tile_success_count = 0U;
    size_t decoder_graph_execution_count = 0U;
    size_t decoder_tile_success_count = 0U;
    size_t physical_unet_graph_execution_count = 0U;
    size_t quality_step_evaluation_count = 0U;
    size_t noise_injection_step_count = 0U;
    std::string noise_injection_seed_fingerprint;
    uint64_t noise_injection_checksum = 0U;
    size_t structure_guidance_step_count = 0U;
    uint64_t structure_guidance_checksum = 0U;
    uint64_t trajectory_noise_checksum = 0U;
    std::string sample_method;
    std::string native_scheduler;
    std::string output_sha256;
    long long output_bytes = 0;
    bool output_atomic_commit = false;
};

std::string qnn_ultrafix_evidence_json(
        const QnnUltraFixExecutionEvidence& evidence) {
    const auto stage = [](size_t invocations,
                          size_t successes,
                          size_t tile_invocations,
                          size_t tile_successes,
                          size_t steps) {
        return nlohmann::json{
            {"invocationCount", invocations},
            {"successCount", successes},
            {"tileInvocationCount", tile_invocations},
            {"tileSuccessCount", tile_successes},
            {"stepCount", steps},
        };
    };
    const size_t tile_count = evidence.plan.tiles.size();
    return nlohmann::json{
        {"version", 2},
        {"generationCompleted", true},
        {"cancelled", false},
        {"previewPublished", false},
        {"sourceWidth", evidence.source_width},
        {"sourceHeight", evidence.source_height},
        {"targetWidth", evidence.request.target_width},
        {"targetHeight", evidence.request.target_height},
        {"sourceFit", "cover_center"},
        {"sourceResizedWidth", evidence.source_resized_width},
        {"sourceResizedHeight", evidence.source_resized_height},
        {"sourceCropLeft", evidence.source_crop_left},
        {"sourceCropTop", evidence.source_crop_top},
        {"tileSize", evidence.request.tile_size},
        {"overlap", evidence.request.overlap},
        {"tileCount", tile_count},
        {"tilePlanSha256", evidence.tile_plan_sha256},
        {"inversionSteps", evidence.request.inversion_steps},
        {"refinementSteps", evidence.request.refinement_steps},
        {"denoiseStepCount", evidence.request.inversion_steps},
        {"sampleMethod", evidence.sample_method},
        {"nativeScheduler", evidence.native_scheduler},
        {"vaeEncode", stage(
            evidence.encoder_context_load_count,
            evidence.encoder_context_load_count,
            evidence.encoder_graph_execution_count,
            evidence.encoder_tile_success_count,
            evidence.encoder_context_load_count)},
        {"ddimInversion", stage(
            evidence.inversion_step_count,
            evidence.inversion_step_count,
            evidence.inversion_graph_execution_count,
            evidence.inversion_tile_success_count,
            evidence.inversion_step_count)},
        {"tiledUnetRefinement", stage(
            evidence.refinement_step_count,
            evidence.refinement_step_count,
            evidence.refinement_positive_graph_execution_count +
                evidence.refinement_negative_graph_execution_count,
            evidence.refinement_tile_success_count,
            evidence.refinement_step_count)},
        {"tiledVaeDecode", stage(
            1U,
            1U,
            evidence.decoder_graph_execution_count,
            evidence.decoder_tile_success_count,
            1U)},
        {"encoderGraphExecutionCount", evidence.encoder_graph_execution_count},
        {"inversionPositiveGraphExecutionCount",
            evidence.inversion_graph_execution_count},
        {"refinementPositiveGraphExecutionCount",
            evidence.refinement_positive_graph_execution_count},
        {"refinementNegativeGraphExecutionCount",
            evidence.refinement_negative_graph_execution_count},
        {"decoderGraphExecutionCount", evidence.decoder_graph_execution_count},
        {"physicalDiffusionModelComputeCount",
            evidence.physical_unet_graph_execution_count},
        {"qualityStepEvaluationCount", evidence.quality_step_evaluation_count},
        {"noiseInjectionStepCount", evidence.noise_injection_step_count},
        {"noiseInjectionSeedFingerprint",
            evidence.noise_injection_seed_fingerprint},
        {"noiseInjectionChecksum",
            fixed_width_lower_hex_u64(evidence.noise_injection_checksum)},
        {"structureGuidanceStepCount", evidence.structure_guidance_step_count},
        {"structureGuidanceChecksum",
            fixed_width_lower_hex_u64(evidence.structure_guidance_checksum)},
        {"trajectoryNoiseChecksum",
            fixed_width_lower_hex_u64(evidence.trajectory_noise_checksum)},
        {"outputSha256", evidence.output_sha256},
        {"outputBytes", evidence.output_bytes},
        {"outputAtomicCommit", evidence.output_atomic_commit},
    }.dump();
}

struct QnnSemanticExecutionContract {
    std::string profile_id;
    int profile_revision = 0;
    std::string model_fingerprint;
    QnnSchedulerContract scheduler;
    bool use_cfg = false;
    float cfg_scale = 0.0f;
    std::string prompt;
    std::string negative_prompt;
    std::string native_prompt_execution_sha256;
    std::string native_prompt_binding_stage;
    uint32_t seed = 0;
    std::string tokenizer_backend;
    size_t token_count = 0;
    int tokenizer_max_length = 0;
    bool prompt_weighting_supported = false;
    std::string embedding_disk_data_type;
    QnnVaeScalingLocation vae_scaling_location = QnnVaeScalingLocation::None;
    double vae_scaling_factor = 1.0;
    mca::qnn::ImagePixelRange pixel_range =
        mca::qnn::ImagePixelRange::NegativeOneToOne;
    int width = 0;
    int height = 0;
    std::string graph_name;
};

struct QnnNativeEffectiveEvidence {
    size_t timetable_count = 0;
    size_t unet_execution_count = 0;
    std::string tokenizer_backend;
    size_t token_count = 0;
    std::string embedding_disk_data_type;
    int width = 0;
    int height = 0;
    std::string graph_name;
    bool prompt_weighting_applied = false;
    size_t positive_weighted_token_count = 0;
    size_t negative_weighted_token_count = 0;
    std::string prompt_weight_fingerprint;
    std::string native_prompt_execution_sha256;
    std::string native_prompt_binding_stage;
    std::string conditioning_artifact_sha256;
    std::string conditioning_execution_mode;
    std::string conditioning_backend;
    std::string conditioning_graph;
    std::string conditioning_graph_sha256;
    std::string conditioning_order;
    size_t conditioning_encoder_execution_count = 0;
    size_t text_encoder_execution_count = 0;
    std::string consumed_text_encoder_path;
    std::string consumed_text_encoder_sha256;
    long long consumed_text_encoder_size_bytes = 0;
    bool consumed_text_encoder_asset_verified = false;
    std::string language_proof_sha256;
    bool conditioning_artifact_consumed = false;
    std::string runtime_session_mode;
    std::string task_mode = "text_to_image";
    std::string input_image_path;
    std::string input_image_sha256;
    std::string input_image_tensor_path;
    std::string input_image_tensor_sha256;
    long long input_image_tensor_bytes = 0;
    std::string input_image_tensor_dtype;
    std::string input_image_tensor_layout;
    std::string input_image_tensor_range;
    std::string encoder_latent_sha256;
    std::string encoder_context_sha256;
    std::string encoder_graph_name;
    std::string encoder_input_name;
    std::string encoder_mean_output_name;
    std::string encoder_std_output_name;
    std::string encoder_input_dtype;
    std::string encoder_mean_dtype;
    std::string encoder_std_dtype;
    std::string encoder_input_buffer_sha256;
    std::string encoder_mean_buffer_sha256;
    std::string encoder_std_buffer_sha256;
    std::string encoder_runtime_mode;
    size_t encoder_context_load_count = 0;
    size_t encoder_execution_count = 0;
    size_t encoder_posterior_sample_count = 0;
    double encoder_latent_scaling_factor = 0.0;
    bool encoder_context_released_before_shared_session = false;
    std::string input_image_preprocess = "none";
    double strength = 1.0;
    size_t input_image_execution_count = 0;
    size_t input_image_source_width = 0;
    size_t input_image_source_height = 0;
    size_t input_image_oriented_width = 0;
    size_t input_image_oriented_height = 0;
    int input_image_exif_orientation = 0;
    size_t input_image_tensor_width = 0;
    size_t input_image_tensor_height = 0;
    size_t input_image_tensor_channels = 0;
    size_t encoder_graph_input_width = 0;
    size_t encoder_graph_input_height = 0;
    size_t encoder_graph_output_width = 0;
    size_t encoder_graph_output_height = 0;
    size_t img2img_begin_index = 0;
    size_t full_timetable_count = 0;
    size_t effective_denoise_steps = 0;
    bool img2img_add_noise_applied = false;
    size_t img2img_add_noise_begin_index = 0;
    double img2img_add_noise_timestep = 0.0;
    std::string img2img_noise_checksum = "0000000000000000";
    std::string control_image_path;
    std::string control_image_sha256;
    std::string control_image_preprocessed_sha256;
    std::string control_image_preprocess = "none";
    double control_strength = 0.0;
    size_t control_image_execution_count = 0;
    size_t controlnet_execution_count = 0;
    size_t controlnet_residual_tensor_count = 0;
    size_t controlnet_residual_write_count = 0;
    size_t controlnet_residual_unet_reuse_count = 0;
    bool controlnet_input_consumed = false;
    std::string controlnet_conditioning_branch = "none";
    std::string controlnet_graph_name;
    uint32_t control_image_source_width = 0;
    uint32_t control_image_source_height = 0;
    uint32_t control_image_source_channels = 0;
    uint32_t control_image_tensor_width = 0;
    uint32_t control_image_tensor_height = 0;
    uint32_t control_image_tensor_channels = 0;
    int control_image_exif_orientation = 0;
    size_t control_image_edge_pixel_count = 0;
    uint64_t control_image_tensor_checksum = 0;
    uint64_t controlnet_scaled_residual_checksum = 0;
    std::string controlnet_input_buffer_sha256;
    // Mask-conditioned execution evidence is kept separate from the legacy
    // ControlNet fields so a plain four-channel UNet cannot accidentally
    // appear to have consumed a mask.
    std::string inpaint_topology = "none";
    std::string mask_image_path;
    std::string mask_image_sha256;
    long long mask_image_source_bytes = 0;
    std::string mask_image_tensor_path;
    std::string mask_image_tensor_sha256;
    long long mask_image_tensor_bytes = 0;
    std::string mask_image_tensor_dtype;
    std::string mask_image_tensor_layout;
    std::string mask_image_tensor_range;
    std::string mask_image_tensor_preprocess;
    std::string mask_image_full_tensor_path;
    std::string mask_image_full_tensor_sha256;
    long long mask_image_full_tensor_bytes = 0;
    std::string mask_image_full_tensor_dtype;
    std::string mask_image_full_tensor_layout;
    std::string mask_image_full_tensor_range;
    std::string mask_image_full_tensor_preprocess;
    size_t mask_image_source_width = 0U;
    size_t mask_image_source_height = 0U;
    size_t mask_image_oriented_width = 0U;
    size_t mask_image_oriented_height = 0U;
    int mask_image_exif_orientation = 0;
    size_t mask_image_repaint_pixel_count = 0U;
    size_t mask_image_latent_repaint_pixel_count = 0U;
    std::string masked_input_image_tensor_path;
    std::string masked_input_image_tensor_sha256;
    long long masked_input_image_tensor_bytes = 0;
    std::string masked_input_image_tensor_dtype;
    std::string masked_input_image_tensor_layout;
    std::string masked_input_image_tensor_range;
    std::string masked_input_image_tensor_preprocess;
    std::string masked_input_buffer_sha256;
    std::string masked_input_mean_buffer_sha256;
    std::string masked_input_std_buffer_sha256;
    std::string masked_input_latent_sha256;
    size_t masked_input_encoder_execution_count = 0U;
    size_t masked_input_posterior_sample_count = 0U;
    long long masked_input_encoder_execute_ms = 0;
    size_t mask_image_execution_count = 0;
    size_t inpaint_mask_unet_bind_count = 0U;
    size_t inpaint_preserve_step_count = 0U;
    size_t inpaint_latent_blend_count = 0U;
    size_t inpaint_source_encoder_execution_count = 0U;
    std::string inpaint_preserved_latent_checksum = "0000000000000000";
    std::string inpaint_source_noise_sha256;
    size_t inpaint_source_noise_use_count = 0U;
    std::string inpaint_final_mode = "none";
    size_t inpaint_pixel_blend_levels = 0U;
    std::string inpaint_pixel_blend_checksum = "0000000000000000";
    bool inpaint_pixel_blend_applied = false;
    bool mask_image_consumed = false;
    bool inpaint_unmasked_preservation_applied = false;
    std::string ultra_fix_json;
    std::string ultra_fix_output_sha256;
    long long ultra_fix_output_bytes = 0;
    bool ultra_fix_output_atomic_commit = false;
    size_t ultra_fix_positive_diffusion_model_compute_count = 0U;
    size_t ultra_fix_negative_diffusion_model_compute_count = 0U;
    size_t ultra_fix_auxiliary_diffusion_model_compute_count = 0U;
    size_t ultra_fix_sampling_pass_count = 0U;
    size_t ultra_fix_total_unet_execution_count = 0U;
    // Keep closure fields at the tail: shared and split QNN paths use C++17
    // positional aggregate initialization for the stable execution prefix.
    std::string text_encoder_graph_name;
    std::string loaded_text_encoder_graph_name;
    std::string consumed_tokenizer_path;
    std::string consumed_tokenizer_sha256;
    long long consumed_tokenizer_size_bytes = 0;
    bool consumed_tokenizer_asset_verified = false;
    std::string tokenizer_binding_stage;
    std::string consumed_prompt_to_encoder_closure_sha256;
    std::string consumed_prompt_to_encoder_binding_stage;
    bool mnn_prompt_handoff_verified = false;
    std::string tokenizer_receipt_canonical_path;
    std::string tokenizer_receipt_sha256;
    long long tokenizer_receipt_size_bytes = 0;
    std::string tokenizer_receipt_binding_stage;
};

struct QnnTextEncoderAssetEvidence {
    bool required = false;
    bool multilingual_evidence_required = false;
    std::string relative_path;
    std::string sha256;
    long long size_bytes = 0;
    std::string graph_name;
    std::string language_proof_sha256;
    std::string prompt_to_encoder_closure_sha256;
    dev_t device = 0;
    ino_t inode = 0;
    std::string tokenizer_relative_path;
    std::string tokenizer_sha256;
    long long tokenizer_size_bytes = 0;
    dev_t tokenizer_device = 0;
    ino_t tokenizer_inode = 0;
    std::string tokenizer_canonical_path;
    bool tokenizer_asset_verified = false;
    std::string mnn_prompt_handoff;
    bool mnn_prompt_handoff_verified = false;
    mca::image::prompt_handoff::Record consumed_prompt_handoff;
};

struct QnnConditioningEvidence {
    std::string tokenizer_backend;
    std::string tokenizer_binding_stage;
    size_t token_count = 0;
    bool prompt_weighting_applied = false;
    size_t positive_weighted_token_count = 0;
    size_t negative_weighted_token_count = 0;
    std::string prompt_weight_fingerprint;
    std::string native_prompt_execution_sha256;
    std::string native_prompt_binding_stage;
    std::string conditioning_artifact_sha256;
    std::string embedding_disk_data_type;
    std::string conditioning_execution_mode;
    std::string conditioning_backend;
    std::string conditioning_graph;
    std::string conditioning_graph_sha256;
    std::string conditioning_order;
    size_t conditioning_encoder_execution_count = 0;
};

uint32_t qnn_u32_little_endian(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
        (static_cast<uint32_t>(bytes[1]) << 8U) |
        (static_cast<uint32_t>(bytes[2]) << 16U) |
        (static_cast<uint32_t>(bytes[3]) << 24U);
}

bool derive_qnn_token_execution_weight_evidence(
        const std::vector<int32_t>& token_ids,
        const std::vector<float>& token_weights,
        bool use_cfg,
        QnnConditioningEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr) return false;
    if (token_ids.empty() || token_ids.size() != token_weights.size() ||
        token_ids.size() % 2U != 0U) {
        *error = "CLIP token IDs and weights must contain two equally sized sequences.";
        return false;
    }
    if (std::any_of(token_ids.begin(), token_ids.end(), [](int32_t id) { return id < 0; })) {
        *error = "CLIP token conditioning contains a negative token ID.";
        return false;
    }
    if (std::any_of(token_weights.begin(), token_weights.end(), [](float weight) {
            return !std::isfinite(weight);
        })) {
        *error = "CLIP token conditioning contains a non-finite token weight.";
        return false;
    }

    const size_t sequence_length = token_ids.size() / 2U;
    mca::image::ClipTokenPair pair;
    pair.negative.ids.assign(token_ids.begin(), token_ids.begin() + sequence_length);
    pair.positive.ids.assign(token_ids.begin() + sequence_length, token_ids.end());
    pair.negative.weights.assign(
        token_weights.begin(), token_weights.begin() + sequence_length);
    pair.positive.weights.assign(
        token_weights.begin() + sequence_length, token_weights.end());
    const auto weighted_count = [](const std::vector<float>& weights) {
        return static_cast<size_t>(std::count_if(
            weights.begin(),
            weights.end(),
            [](float value) { return std::fabs(value - 1.0f) > 1.0e-6f; }));
    };
    const size_t payload_negative_weighted_count = weighted_count(pair.negative.weights);
    const size_t payload_positive_weighted_count = weighted_count(pair.positive.weights);
    if (!use_cfg && payload_negative_weighted_count != 0U) {
        *error = "A non-executed negative CLIP branch must not carry prompt weights when useCfg=false.";
        return false;
    }
    evidence->negative_weighted_token_count =
        use_cfg ? payload_negative_weighted_count : 0U;
    evidence->positive_weighted_token_count = payload_positive_weighted_count;
    evidence->prompt_weighting_applied =
        evidence->negative_weighted_token_count > 0U ||
        evidence->positive_weighted_token_count > 0U;
    evidence->prompt_weight_fingerprint = pair.weighting_fingerprint();
    if (evidence->prompt_weight_fingerprint.size() != 64U) {
        *error = "CLIP token conditioning fingerprint could not be derived.";
        return false;
    }
    return true;
}

bool read_qnn_clip_token_weight_payload(
        const std::string& path,
        std::vector<int32_t>* token_ids,
        std::vector<float>* token_weights,
        bool use_cfg,
        QnnConditioningEvidence* evidence,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr) {
    if (token_ids == nullptr || token_weights == nullptr ||
        evidence == nullptr || error == nullptr) {
        return false;
    }
    constexpr size_t kHeaderBytes = 16U;
    constexpr std::array<uint8_t, 8> kMagic = {
        'M', 'C', 'A', 'Q', 'P', 'W', '0', '1'
    };
    const long long file_bytes = file_size_or_zero(path);
    if (file_bytes < static_cast<long long>(kHeaderBytes) ||
        file_bytes > static_cast<long long>(16U * 1024U * 1024U)) {
        *error = "Weighted CLIP token payload size is invalid: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open weighted CLIP token payload: " + path;
        return false;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(file_bytes));
    input.read(
        reinterpret_cast<char*>(bytes.data()),
        static_cast<std::streamsize>(bytes.size()));
    if (!input.good() || !std::equal(kMagic.begin(), kMagic.end(), bytes.begin())) {
        *error = "Weighted CLIP token payload header is invalid.";
        return false;
    }
    const uint32_t version = qnn_u32_little_endian(bytes.data() + 8U);
    const uint32_t token_count = qnn_u32_little_endian(bytes.data() + 12U);
    if (version != 1U || token_count == 0U || token_count > 8192U) {
        *error = "Weighted CLIP token payload version or token count is unsupported.";
        return false;
    }
    const size_t expected_bytes = kHeaderBytes +
        static_cast<size_t>(token_count) * (sizeof(int32_t) + sizeof(float));
    if (bytes.size() != expected_bytes || token_count % 2U != 0U) {
        *error = "Weighted CLIP token payload length does not match its header.";
        return false;
    }
    token_ids->assign(token_count, 0);
    token_weights->assign(token_count, 1.0f);
    size_t offset = kHeaderBytes;
    for (size_t index = 0; index < token_ids->size(); ++index, offset += 4U) {
        (*token_ids)[index] = static_cast<int32_t>(
            qnn_u32_little_endian(bytes.data() + offset));
        if ((*token_ids)[index] < 0) {
            *error = "Weighted CLIP token payload contains a negative token ID.";
            return false;
        }
    }
    for (size_t index = 0; index < token_weights->size(); ++index, offset += 4U) {
        const uint32_t bits = qnn_u32_little_endian(bytes.data() + offset);
        std::memcpy(&(*token_weights)[index], &bits, sizeof(bits));
        if (!std::isfinite((*token_weights)[index])) {
            *error = "Weighted CLIP token payload contains a non-finite token weight.";
            return false;
        }
    }
    if (!derive_qnn_token_execution_weight_evidence(
            *token_ids,
            *token_weights,
            use_cfg,
            evidence,
            error)) {
        return false;
    }
    return consumed_payload_sha256 == nullptr || qnn_consumed_payload_sha256(
        bytes.data(),
        bytes.size(),
        consumed_payload_sha256,
        error);
}

std::string normalized_contract_enum(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        if (c == '-' || c == ' ' || c == '.') return '_';
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool qnn_json_value_start(
        const std::string& json,
        const std::string& key,
        size_t* value_start,
        std::string* error) {
    const auto key_position = json.find("\"" + key + "\"");
    if (key_position == std::string::npos) {
        *error = "Missing required native execution field '" + key + "'.";
        return false;
    }
    const auto colon = json.find(':', key_position + key.size() + 2u);
    if (colon == std::string::npos) {
        *error = "Native execution field '" + key + "' has no value.";
        return false;
    }
    const auto start = json.find_first_not_of(" \t\r\n", colon + 1u);
    if (start == std::string::npos) {
        *error = "Native execution field '" + key + "' has no value.";
        return false;
    }
    *value_start = start;
    return true;
}

bool qnn_json_token_ends_at(const std::string& json, size_t end) {
    const auto next = json.find_first_not_of(" \t\r\n", end);
    return next == std::string::npos || json[next] == ',' || json[next] == '}' || json[next] == ']';
}

bool qnn_required_string_field(
        const std::string& json,
        const std::string& key,
        std::string* value,
        std::string* error,
        bool allow_empty = false) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    if (json[start] != '"') {
        *error = "Native execution field '" + key + "' must be a string.";
        return false;
    }
    value->clear();
    bool escaped = false;
    for (size_t index = start + 1u; index < json.size(); ++index) {
        const char c = json[index];
        if (escaped) {
            switch (c) {
                case 'n': value->push_back('\n'); break;
                case 'r': value->push_back('\r'); break;
                case 't': value->push_back('\t'); break;
                default: value->push_back(c); break;
            }
            escaped = false;
            continue;
        }
        if (c == '\\') {
            escaped = true;
            continue;
        }
        if (c == '"') {
            if (value->empty() && !allow_empty) {
                *error = "Native execution field '" + key + "' must not be blank.";
                return false;
            }
            return true;
        }
        value->push_back(c);
    }
    *error = "Native execution string field '" + key + "' is unterminated.";
    return false;
}

bool qnn_required_bool_field(
        const std::string& json,
        const std::string& key,
        bool* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    if (json.compare(start, 4u, "true") == 0 && qnn_json_token_ends_at(json, start + 4u)) {
        *value = true;
        return true;
    }
    if (json.compare(start, 5u, "false") == 0 && qnn_json_token_ends_at(json, start + 5u)) {
        *value = false;
        return true;
    }
    *error = "Native execution field '" + key + "' must be a boolean.";
    return false;
}

bool qnn_required_integer_field(
        const std::string& json,
        const std::string& key,
        long long* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    errno = 0;
    char* end = nullptr;
    const char* begin = json.c_str() + start;
    const long long parsed = std::strtoll(begin, &end, 10);
    const size_t end_index = static_cast<size_t>(end - json.c_str());
    if (end == begin || errno == ERANGE || !qnn_json_token_ends_at(json, end_index)) {
        *error = "Native execution field '" + key + "' must be an integer.";
        return false;
    }
    *value = parsed;
    return true;
}

bool qnn_required_number_field(
        const std::string& json,
        const std::string& key,
        double* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    errno = 0;
    char* end = nullptr;
    const char* begin = json.c_str() + start;
    const double parsed = std::strtod(begin, &end);
    const size_t end_index = static_cast<size_t>(end - json.c_str());
    if (end == begin || errno == ERANGE || !std::isfinite(parsed) ||
        !qnn_json_token_ends_at(json, end_index)) {
        *error = "Native execution field '" + key + "' must be a finite number.";
        return false;
    }
    *value = parsed;
    return true;
}

bool parse_qnn_text_encoder_asset_evidence(
        const std::string& params_json,
        const std::string& text_encoder_binary,
        const std::string& requested_text_encoder_graph_name,
        bool prompt_requires_multilingual_evidence,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr) return false;
    *evidence = QnnTextEncoderAssetEvidence{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.is_object()) {
            *error = "QNN execution params must be one structured JSON object.";
            return false;
        }
        const auto path = root.find("textEncoderPath");
        const auto sha256 = root.find("textEncoderSha256");
        const auto size_bytes = root.find("textEncoderSizeBytes");
        const auto graph_name = root.find("textEncoderGraphName");
        const auto context_binary = root.find("textEncoderContextBinary");
        const auto tokenizer_path = root.find("tokenizerJsonPath");
        const auto tokenizer_sha256 = root.find("tokenizerJsonSha256");
        const auto tokenizer_size_bytes = root.find("tokenizerJsonSizeBytes");
        const auto mnn_prompt_handoff = root.find("mnnPromptHandoff");
        const auto tokenizer_receipt_canonical_path = root.find(
            "tokenizerReceiptCanonicalPath");
        const auto tokenizer_receipt_sha256 = root.find("tokenizerReceiptSha256");
        const auto tokenizer_receipt_size_bytes = root.find("tokenizerReceiptSizeBytes");
        const auto tokenizer_receipt_binding_stage = root.find(
            "tokenizerReceiptBindingStage");
        const auto tokenizer_asset_path = root.find("tokenizerAssetPath");
        const auto tokenizer_asset_sha256 = root.find("tokenizerAssetSha256");
        const auto tokenizer_asset_size_bytes = root.find("tokenizerAssetSizeBytes");
        const auto tokenizer_asset_binding_stage = root.find(
            "tokenizerAssetBindingStage");
        const auto prompt_to_encoder_closure_sha256 = root.find(
            "promptToEncoderClosureSha256");
        const auto language_proof_sha256 = root.find("languageProofSha256");
        const bool any_declared = path != root.end() || sha256 != root.end() ||
            size_bytes != root.end();
        const bool all_declared = path != root.end() && sha256 != root.end() &&
            size_bytes != root.end();
        const bool any_tokenizer_declared = tokenizer_path != root.end() ||
            tokenizer_sha256 != root.end() || tokenizer_size_bytes != root.end();
        const bool all_tokenizer_declared = tokenizer_path != root.end() &&
            tokenizer_sha256 != root.end() && tokenizer_size_bytes != root.end();
        const bool any_tokenizer_receipt_declared =
            tokenizer_receipt_canonical_path != root.end() ||
            tokenizer_receipt_sha256 != root.end() ||
            tokenizer_receipt_size_bytes != root.end() ||
            tokenizer_receipt_binding_stage != root.end();
        const bool any_legacy_tokenizer_asset_declared =
            tokenizer_asset_path != root.end() ||
            tokenizer_asset_sha256 != root.end() ||
            tokenizer_asset_size_bytes != root.end() ||
            tokenizer_asset_binding_stage != root.end();
        const bool signed_multilingual_claim = language_proof_sha256 != root.end();
        const bool multilingual_evidence_required =
            prompt_requires_multilingual_evidence || signed_multilingual_claim;
        evidence->multilingual_evidence_required = multilingual_evidence_required;

        const auto parse_size_bytes = [&](
                const nlohmann::json& value,
                const char* field_name,
                long long* output) {
            if (!value.is_number_integer() && !value.is_number_unsigned()) {
                *error = std::string(field_name) + " must be an exact integer.";
                return false;
            }
            if (value.is_number_unsigned()) {
                const uint64_t unsigned_size_bytes = value.get<uint64_t>();
                if (unsigned_size_bytes >
                    static_cast<uint64_t>(std::numeric_limits<long long>::max())) {
                    *error = std::string(field_name) +
                        " exceeds the native signed size range.";
                    return false;
                }
                *output = static_cast<long long>(unsigned_size_bytes);
            } else {
                *output = value.get<long long>();
            }
            if (*output <= 0) {
                *error = std::string(field_name) + " must be positive.";
                return false;
            }
            return true;
        };
        const auto normalize_sha256 = [](std::string* value) {
            std::transform(
                value->begin(),
                value->end(),
                value->begin(),
                [](unsigned char character) {
                    return static_cast<char>(std::tolower(character));
                });
            return value->size() == 64U && std::all_of(
                value->begin(),
                value->end(),
                [](unsigned char character) {
                    return (character >= '0' && character <= '9') ||
                        (character >= 'a' && character <= 'f');
                });
        };
        if (multilingual_evidence_required &&
            (any_tokenizer_receipt_declared ||
             any_legacy_tokenizer_asset_declared)) {
            *error = "Signed multilingual QNN evidence rejects request-side tokenizerReceipt* and tokenizerAsset* authorization fields.";
            return false;
        }
        if (any_declared && !all_declared) {
            *error = "textEncoderPath, textEncoderSha256, and textEncoderSizeBytes must be supplied together.";
            return false;
        }
        if (!all_declared) {
            if (multilingual_evidence_required) {
                *error = "Signed multilingual QNN prompts require complete text encoder asset evidence.";
                return false;
            }
            error->clear();
            return true;
        }
        if (!path->is_string() || !sha256->is_string()) {
            *error = "textEncoderPath and textEncoderSha256 must be strings.";
            return false;
        }
        long long requested_size_bytes = 0;
        if (!parse_size_bytes(*size_bytes, "textEncoderSizeBytes", &requested_size_bytes)) {
            return false;
        }
        std::string requested_path = path->get<std::string>();
        std::string requested_sha256 = sha256->get<std::string>();
        if (requested_path.find('\0') != std::string::npos ||
            !is_safe_bundle_relative_path(requested_path) ||
            requested_path != text_encoder_binary) {
            *error = "textEncoderPath must be a safe bundle-relative path matching textEncoderContextBinary.";
            return false;
        }
        if (!normalize_sha256(&requested_sha256)) {
            *error = "textEncoderSha256 must be a 64-character SHA-256 value.";
            return false;
        }
        std::string requested_language_proof_sha256;
        if (language_proof_sha256 != root.end()) {
            if (!language_proof_sha256->is_string()) {
                *error = "languageProofSha256 must be a 64-character SHA-256 string when supplied.";
                return false;
            }
            requested_language_proof_sha256 = language_proof_sha256->get<std::string>();
            if (!normalize_sha256(&requested_language_proof_sha256)) {
                *error = "languageProofSha256 must be a 64-character SHA-256 value.";
                return false;
            }
        }

        if (multilingual_evidence_required) {
            if (context_binary == root.end() || !context_binary->is_string()) {
                *error = "Signed multilingual QNN evidence requires textEncoderContextBinary.";
                return false;
            }
            const std::string requested_context_binary = context_binary->get<std::string>();
            if (requested_context_binary.find('\0') != std::string::npos ||
                !is_safe_bundle_relative_path(requested_context_binary) ||
                !is_safe_bundle_relative_path(text_encoder_binary) ||
                requested_context_binary != text_encoder_binary ||
                requested_path != requested_context_binary) {
                *error = "Signed multilingual QNN evidence must bind textEncoderPath to the exact textEncoderContextBinary asset.";
                return false;
            }
            if (graph_name == root.end() || !graph_name->is_string()) {
                *error = "Signed multilingual QNN evidence requires textEncoderGraphName.";
                return false;
            }
            std::string requested_graph_name = graph_name->get<std::string>();
            if (requested_graph_name.empty() ||
                requested_graph_name.find('\0') != std::string::npos ||
                requested_graph_name != requested_text_encoder_graph_name) {
                *error = "textEncoderGraphName must identify the exact graph selected from textEncoderContextBinary.";
                return false;
            }
            if (!all_tokenizer_declared || !tokenizer_path->is_string() ||
                !tokenizer_sha256->is_string()) {
                *error = "Signed multilingual QNN evidence requires tokenizerJsonPath, tokenizerJsonSha256, and tokenizerJsonSizeBytes.";
                return false;
            }
            long long requested_tokenizer_size_bytes = 0;
            if (!parse_size_bytes(
                    *tokenizer_size_bytes,
                    "tokenizerJsonSizeBytes",
                    &requested_tokenizer_size_bytes)) {
                return false;
            }
            std::string requested_tokenizer_path = tokenizer_path->get<std::string>();
            std::string requested_tokenizer_sha256 = tokenizer_sha256->get<std::string>();
            if (requested_tokenizer_path.find('\0') != std::string::npos ||
                !is_safe_bundle_relative_path(requested_tokenizer_path) ||
                requested_tokenizer_path == requested_path ||
                !ends_with(normalized_contract_enum(requested_tokenizer_path), ".json")) {
                *error = "tokenizerJsonPath must be a distinct safe bundle-relative JSON tokenizer asset.";
                return false;
            }
            if (!normalize_sha256(&requested_tokenizer_sha256)) {
                *error = "tokenizerJsonSha256 must be a 64-character SHA-256 value.";
                return false;
            }
            if (mnn_prompt_handoff == root.end() ||
                !mnn_prompt_handoff->is_string()) {
                *error = "Signed multilingual QNN evidence requires mnnPromptHandoff.";
                return false;
            }
            std::string requested_mnn_prompt_handoff =
                mnn_prompt_handoff->get<std::string>();
            if (requested_mnn_prompt_handoff.size() != 64U ||
                !std::all_of(
                    requested_mnn_prompt_handoff.begin(),
                    requested_mnn_prompt_handoff.end(),
                    [](unsigned char character) {
                        return (character >= '0' && character <= '9') ||
                            (character >= 'a' && character <= 'f');
                    })) {
                *error = "mnnPromptHandoff must be a 256-bit lowercase opaque handle.";
                return false;
            }
            if (prompt_to_encoder_closure_sha256 == root.end() ||
                !prompt_to_encoder_closure_sha256->is_string()) {
                *error = "Signed multilingual QNN evidence requires promptToEncoderClosureSha256.";
                return false;
            }
            std::string requested_prompt_to_encoder_closure_sha256 =
                prompt_to_encoder_closure_sha256->get<std::string>();
            if (!normalize_sha256(&requested_prompt_to_encoder_closure_sha256)) {
                *error = "promptToEncoderClosureSha256 must be a 64-character SHA-256 value.";
                return false;
            }
            if (requested_language_proof_sha256.empty()) {
                *error = "Signed multilingual QNN evidence requires languageProofSha256.";
                return false;
            }
            evidence->graph_name = std::move(requested_graph_name);
            evidence->tokenizer_relative_path = std::move(requested_tokenizer_path);
            evidence->tokenizer_sha256 = std::move(requested_tokenizer_sha256);
            evidence->tokenizer_size_bytes = requested_tokenizer_size_bytes;
            evidence->mnn_prompt_handoff =
                std::move(requested_mnn_prompt_handoff);
            evidence->prompt_to_encoder_closure_sha256 =
                std::move(requested_prompt_to_encoder_closure_sha256);
        } else if (any_tokenizer_declared || any_tokenizer_receipt_declared ||
                   any_legacy_tokenizer_asset_declared ||
                   mnn_prompt_handoff != root.end() ||
                   prompt_to_encoder_closure_sha256 != root.end()) {
            // Legacy English execution neither consumes nor receipts a closure. Keep it
            // compatible even when a newer caller includes optional closure fields.
        }
        evidence->required = true;
        evidence->relative_path = std::move(requested_path);
        evidence->sha256 = std::move(requested_sha256);
        evidence->size_bytes = requested_size_bytes;
        evidence->language_proof_sha256 = std::move(requested_language_proof_sha256);
        error->clear();
        return true;
    } catch (const nlohmann::json::exception&) {
        *error = "QNN text encoder asset evidence JSON is invalid.";
        return false;
    }
}

// English-dominant product paths pass only safe ASCII tags.  A native caller that supplies any
// UTF-8 bytes outside ASCII must therefore present the same strict text-encoder asset proof used
// by the evidence-bound multilingual Android path.
bool qnn_prompt_contains_non_ascii(const std::string& value) {
    return std::any_of(value.begin(), value.end(), [](unsigned char byte) {
        return byte >= 0x80U;
    });
}

bool qnn_is_safe_ascii_diffusion_prompt(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char byte) {
        return mca::image::prompt_language::is_safe_ascii_diffusion_prompt_code_point(byte);
    });
}

bool qnn_is_safe_ascii_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt) {
    return qnn_is_safe_ascii_diffusion_prompt(prompt) &&
        qnn_is_safe_ascii_diffusion_prompt(negative_prompt);
}

bool qnn_text_encoder_asset_file_status(
        const std::string& bundle_asset_path,
        struct stat* status,
        std::string* error) {
    if (status == nullptr || error == nullptr) return false;
    struct stat file_status {};
    // A strict bundle-relative proof must not resolve through a symlink.
    if (::lstat(bundle_asset_path.c_str(), &file_status) != 0 ||
        !S_ISREG(file_status.st_mode) || file_status.st_size <= 0 ||
        static_cast<uint64_t>(file_status.st_size) >
            static_cast<uint64_t>(std::numeric_limits<long long>::max())) {
        *error = "The strict QNN text encoder asset is not a readable nonempty regular file.";
        return false;
    }
    *status = file_status;
    error->clear();
    return true;
}

bool qnn_text_encoder_asset_status_matches(
        const struct stat& status,
        const QnnTextEncoderAssetEvidence& evidence) {
    return S_ISREG(status.st_mode) && status.st_size > 0 &&
        static_cast<uint64_t>(status.st_size) ==
            static_cast<uint64_t>(evidence.size_bytes) &&
        status.st_dev == evidence.device && status.st_ino == evidence.inode;
}

bool capture_qnn_text_encoder_asset_evidence(
        const std::string& bundle_asset_path,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr || !evidence->required) return false;
    struct stat before_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &before_hash, error)) {
        return false;
    }
    if (static_cast<long long>(before_hash.st_size) != evidence->size_bytes) {
        *error = "The strict QNN text encoder asset size differs from textEncoderSizeBytes.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(bundle_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN text encoder asset SHA-256 could not be verified.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &after_hash, error)) {
        return false;
    }
    if (before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size) {
        *error = "The strict QNN text encoder asset changed while its SHA-256 was verified.";
        return false;
    }
    if (actual_sha256 != evidence->sha256) {
        *error = "The strict QNN text encoder asset SHA-256 differs from textEncoderSha256.";
        return false;
    }
    evidence->device = after_hash.st_dev;
    evidence->inode = after_hash.st_ino;
    error->clear();
    return true;
}

bool revalidate_qnn_text_encoder_asset_evidence(
        const std::string& bundle_asset_path,
        const QnnTextEncoderAssetEvidence& evidence,
        std::string* error) {
    if (error == nullptr || !evidence.required) return false;
    struct stat before_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &before_hash, error)) {
        return false;
    }
    if (!qnn_text_encoder_asset_status_matches(before_hash, evidence)) {
        *error = "The strict QNN text encoder asset inode or size changed after graph execution.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(bundle_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN text encoder asset could not be re-verified after graph execution.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &after_hash, error)) {
        return false;
    }
    if (!qnn_text_encoder_asset_status_matches(after_hash, evidence) ||
        before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size || actual_sha256 != evidence.sha256) {
        *error = "The strict QNN text encoder asset changed after graph execution.";
        return false;
    }
    error->clear();
    return true;
}

bool qnn_tokenizer_asset_file_status(
        const std::string& bundle_asset_path,
        struct stat* status,
        std::string* error) {
    if (status == nullptr || error == nullptr) return false;
    struct stat file_status {};
    if (::lstat(bundle_asset_path.c_str(), &file_status) != 0 ||
        !S_ISREG(file_status.st_mode) || file_status.st_size <= 0 ||
        static_cast<uint64_t>(file_status.st_size) >
            static_cast<uint64_t>(std::numeric_limits<long long>::max())) {
        *error = "The strict QNN tokenizer asset is not a readable nonempty regular file.";
        return false;
    }
    *status = file_status;
    error->clear();
    return true;
}

bool qnn_tokenizer_asset_status_matches(
        const struct stat& status,
        const QnnTextEncoderAssetEvidence& evidence) {
    return S_ISREG(status.st_mode) && status.st_size > 0 &&
        static_cast<uint64_t>(status.st_size) ==
            static_cast<uint64_t>(evidence.tokenizer_size_bytes) &&
        status.st_dev == evidence.tokenizer_device &&
        status.st_ino == evidence.tokenizer_inode;
}

bool qnn_canonical_bundle_tokenizer_path(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        std::string* canonical_asset_path,
        std::string* error) {
    if (canonical_asset_path == nullptr || error == nullptr) return false;
    if (bundle_root.empty() || bundle_asset_path.empty() ||
        bundle_root.find('\0') != std::string::npos ||
        bundle_asset_path.find('\0') != std::string::npos) {
        *error = "The strict QNN tokenizer asset or bundle root is invalid.";
        return false;
    }
    char canonical_root[PATH_MAX] = {};
    char canonical_asset[PATH_MAX] = {};
    if (::realpath(bundle_root.c_str(), canonical_root) == nullptr ||
        ::realpath(bundle_asset_path.c_str(), canonical_asset) == nullptr) {
        *error = "The strict QNN tokenizer asset or bundle root cannot be canonicalized.";
        return false;
    }
    const std::string canonical_root_path(canonical_root);
    const std::string resolved_asset_path(canonical_asset);
    const std::string root_prefix = canonical_root_path == "/"
        ? canonical_root_path
        : canonical_root_path + "/";
    if (resolved_asset_path == canonical_root_path ||
        resolved_asset_path.compare(0, root_prefix.size(), root_prefix) != 0) {
        *error = "The strict QNN tokenizer asset resolves outside the installed bundle.";
        return false;
    }
    *canonical_asset_path = resolved_asset_path;
    error->clear();
    return true;
}

bool capture_qnn_tokenizer_asset_evidence(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr ||
        !evidence->multilingual_evidence_required) {
        return false;
    }
    std::string canonical_asset_path;
    if (!qnn_canonical_bundle_tokenizer_path(
            bundle_root,
            bundle_asset_path,
            &canonical_asset_path,
            error)) {
        return false;
    }
    struct stat before_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &before_hash, error)) {
        return false;
    }
    if (static_cast<long long>(before_hash.st_size) != evidence->tokenizer_size_bytes) {
        *error = "The strict QNN tokenizer asset size differs from tokenizerJsonSizeBytes.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(canonical_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN tokenizer asset SHA-256 could not be verified.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &after_hash, error)) {
        return false;
    }
    if (before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size) {
        *error = "The strict QNN tokenizer asset changed while its SHA-256 was verified.";
        return false;
    }
    if (actual_sha256 != evidence->tokenizer_sha256) {
        *error = "The strict QNN tokenizer asset SHA-256 differs from tokenizerJsonSha256.";
        return false;
    }
    evidence->tokenizer_device = after_hash.st_dev;
    evidence->tokenizer_inode = after_hash.st_ino;
    evidence->tokenizer_canonical_path = std::move(canonical_asset_path);
    evidence->tokenizer_asset_verified = true;
    error->clear();
    return true;
}

bool revalidate_qnn_tokenizer_asset_evidence(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        const QnnTextEncoderAssetEvidence& evidence,
        std::string* error) {
    if (error == nullptr || !evidence.multilingual_evidence_required) return false;
    std::string canonical_asset_path;
    if (!evidence.tokenizer_asset_verified) {
        *error = "The strict QNN tokenizer asset has no captured identity.";
        return false;
    }
    if (!qnn_canonical_bundle_tokenizer_path(
            bundle_root,
            bundle_asset_path,
            &canonical_asset_path,
            error)) {
        return false;
    }
    if (canonical_asset_path != evidence.tokenizer_canonical_path) {
        *error = "The strict QNN tokenizer asset canonical path changed after capture.";
        return false;
    }
    struct stat before_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &before_hash, error)) {
        return false;
    }
    if (!qnn_tokenizer_asset_status_matches(before_hash, evidence)) {
        *error = "The strict QNN tokenizer asset inode or size changed after capture.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(canonical_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN tokenizer asset could not be re-verified after capture.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &after_hash, error)) {
        return false;
    }
    if (!qnn_tokenizer_asset_status_matches(after_hash, evidence) ||
        before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size || actual_sha256 != evidence.tokenizer_sha256) {
        *error = "The strict QNN tokenizer asset changed after capture.";
        return false;
    }
    error->clear();
    return true;
}

bool parse_qnn_scheduler_contract(
        const std::string& json,
        QnnSchedulerContract* contract,
        std::string* error) {
    std::string scheduler;
    std::string sample_method;
    std::string prediction;
    std::string noise_schedule;
    std::string timestep_spacing;
    std::string final_sigma_type;
    long long integer_value = 0;
    double number_value = 0.0;
    if (!qnn_required_string_field(json, "scheduler", &scheduler, error) ||
        !qnn_required_string_field(json, "sampleMethod", &sample_method, error) ||
        !qnn_required_string_field(json, "predictionType", &prediction, error) ||
        !qnn_required_integer_field(json, "numTrainTimesteps", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0 || integer_value > std::numeric_limits<int>::max()) {
        *error = "numTrainTimesteps must be a positive int32 value.";
        return false;
    }
    contract->config.num_train_timesteps = static_cast<int>(integer_value);
    if (!qnn_required_string_field(json, "noiseSchedule", &noise_schedule, error) ||
        !qnn_required_number_field(json, "betaStart", &number_value, error)) {
        return false;
    }
    contract->config.beta_start = static_cast<float>(number_value);
    if (!qnn_required_number_field(json, "betaEnd", &number_value, error) ||
        !qnn_required_string_field(json, "timestepSpacing", &timestep_spacing, error) ||
        !qnn_required_integer_field(json, "stepsOffset", &integer_value, error)) {
        return false;
    }
    if (integer_value < std::numeric_limits<int>::min() ||
        integer_value > std::numeric_limits<int>::max()) {
        *error = "stepsOffset must be an int32 value.";
        return false;
    }
    contract->config.beta_end = static_cast<float>(number_value);
    contract->config.steps_offset = static_cast<int>(integer_value);
    if (!qnn_required_bool_field(json, "setAlphaToOne", &contract->config.set_alpha_to_one, error) ||
        !qnn_required_bool_field(json, "skipPrkSteps", &contract->config.skip_prk_steps, error) ||
        !qnn_required_string_field(json, "finalSigmaType", &final_sigma_type, error) ||
        !qnn_required_bool_field(json, "scaleModelInput", &contract->scale_model_input, error) ||
        !qnn_required_bool_field(json, "clipSample", &contract->config.clip_sample, error) ||
        !qnn_required_number_field(json, "clipSampleRange", &number_value, error)) {
        return false;
    }
    contract->config.clip_sample_range = static_cast<float>(number_value);
    if (!qnn_required_bool_field(json, "thresholding", &contract->config.thresholding, error) ||
        !qnn_required_number_field(json, "eta", &number_value, error)) {
        return false;
    }
    if (number_value != 0.0) {
        *error = "QNN scheduler execution currently requires eta=0 because no variance-noise tensor is supplied.";
        return false;
    }
    contract->eta = static_cast<float>(number_value);
    if (!qnn_required_bool_field(json, "lowerOrderFinal", &contract->config.lower_order_final, error) ||
        !qnn_required_integer_field(json, "steps", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0 || integer_value > contract->config.num_train_timesteps) {
        *error = "steps must be in [1, numTrainTimesteps]; native does not clamp it.";
        return false;
    }
    contract->steps = static_cast<int>(integer_value);
    if (!qnn_required_integer_field(json, "expectedTimetableCount", &integer_value, error) ||
        integer_value <= 0) {
        if (error->empty()) *error = "expectedTimetableCount must be positive.";
        return false;
    }
    contract->expected_timetable_count = static_cast<size_t>(integer_value);
    if (!qnn_required_integer_field(json, "timetableCount", &integer_value, error) ||
        integer_value <= 0 ||
        static_cast<size_t>(integer_value) != contract->expected_timetable_count) {
        if (error->empty()) {
            *error = "timetableCount must exactly match expectedTimetableCount.";
        }
        return false;
    }
    if (!qnn_required_integer_field(json, "expectedUnetExecutionCount", &integer_value, error) ||
        integer_value <= 0) {
        if (error->empty()) *error = "expectedUnetExecutionCount must be positive.";
        return false;
    }
    contract->expected_unet_execution_count = static_cast<size_t>(integer_value);
    if (!qnn_required_integer_field(json, "unetExecutionCount", &integer_value, error) ||
        integer_value <= 0 ||
        static_cast<size_t>(integer_value) != contract->expected_unet_execution_count) {
        if (error->empty()) {
            *error = "unetExecutionCount must exactly match expectedUnetExecutionCount.";
        }
        return false;
    }

    scheduler = normalized_contract_enum(scheduler);
    if (scheduler == "euler" || scheduler == "euler_discrete") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
    } else if (scheduler == "ddim") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::Ddim;
    } else if (scheduler == "pndm" || scheduler == "pndm_plms") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::Pndm;
    } else if (scheduler == "dpmpp_2m" || scheduler == "dpm++_2m") {
        contract->config.algorithm = mca::diffusion::SchedulerAlgorithm::Dpmpp2m;
    } else {
        *error = "Unsupported QNN scheduler contract value: " + scheduler;
        return false;
    }
    sample_method = normalized_contract_enum(sample_method);
    const bool sample_method_matches =
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete &&
         (sample_method == "euler" || sample_method == "euler_discrete")) ||
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::Ddim &&
         sample_method == "ddim") ||
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::Pndm &&
         (sample_method == "pndm" || sample_method == "pndm_plms")) ||
        (contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::Dpmpp2m &&
         (sample_method == "dpmpp_2m" || sample_method == "dpm++2m" ||
          sample_method == "dpm_plus_plus_2m"));
    if (!sample_method_matches) {
        *error = "sampleMethod conflicts with scheduler: sampleMethod=" + sample_method +
            ", scheduler=" + scheduler + ".";
        return false;
    }

    prediction = normalized_contract_enum(prediction);
    if (prediction == "epsilon") {
        contract->config.prediction_type = mca::diffusion::PredictionType::Epsilon;
    } else if (prediction == "v_prediction") {
        contract->config.prediction_type = mca::diffusion::PredictionType::VPrediction;
    } else if (prediction == "sample") {
        contract->config.prediction_type = mca::diffusion::PredictionType::Sample;
    } else {
        *error = "Unsupported QNN predictionType contract value: " + prediction;
        return false;
    }

    noise_schedule = normalized_contract_enum(noise_schedule);
    if (noise_schedule == "linear") {
        contract->config.beta_schedule = mca::diffusion::BetaSchedule::Linear;
    } else if (noise_schedule == "scaled_linear") {
        contract->config.beta_schedule = mca::diffusion::BetaSchedule::ScaledLinear;
    } else if (noise_schedule == "squaredcos_cap_v2" ||
               noise_schedule == "squared_cosine_cap_v2") {
        contract->config.beta_schedule = mca::diffusion::BetaSchedule::SquaredCosineCapV2;
    } else {
        *error = "Unsupported explicit QNN noiseSchedule contract value: " + noise_schedule;
        return false;
    }

    timestep_spacing = normalized_contract_enum(timestep_spacing);
    if (timestep_spacing == "linspace") {
        contract->config.timestep_spacing = mca::diffusion::TimestepSpacing::Linspace;
    } else if (timestep_spacing == "leading") {
        contract->config.timestep_spacing = mca::diffusion::TimestepSpacing::Leading;
    } else if (timestep_spacing == "trailing") {
        contract->config.timestep_spacing = mca::diffusion::TimestepSpacing::Trailing;
    } else {
        *error = "Unsupported QNN timestepSpacing contract value: " + timestep_spacing;
        return false;
    }

    final_sigma_type = normalized_contract_enum(final_sigma_type);
    if (final_sigma_type == "zero") {
        contract->config.final_sigma_type = mca::diffusion::FinalSigmaType::Zero;
    } else if (final_sigma_type == "sigma_min") {
        contract->config.final_sigma_type = mca::diffusion::FinalSigmaType::SigmaMin;
    } else {
        *error = "Unsupported QNN finalSigmaType contract value: " + final_sigma_type;
        return false;
    }

    const bool scheduler_scales_input =
        contract->config.algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete;
    if (contract->scale_model_input != scheduler_scales_input) {
        *error = "scaleModelInput conflicts with the selected scheduler's actual native behavior.";
        return false;
    }
    return true;
}

bool parse_qnn_pixel_range_contract(
        const std::string& json,
        mca::qnn::ImagePixelRange* pixel_range,
        std::string* error) {
    std::string wire_value;
    if (pixel_range == nullptr ||
        !qnn_required_string_field(json, "pixelRange", &wire_value, error)) {
        return false;
    }
    wire_value = normalized_contract_enum(wire_value);
    if (wire_value == "negative_one_to_one") {
        *pixel_range = mca::qnn::ImagePixelRange::NegativeOneToOne;
    } else if (wire_value == "zero_to_one") {
        *pixel_range = mca::qnn::ImagePixelRange::ZeroToOne;
    } else if (wire_value == "zero_to_255") {
        *pixel_range = mca::qnn::ImagePixelRange::ZeroTo255;
    } else {
        *error = "Unsupported explicit QNN pixelRange contract value: " + wire_value;
        return false;
    }
    return true;
}

bool parse_qnn_prompt_execution_fields(
        const std::string& json,
        std::string* prompt,
        std::string* negative_prompt,
        std::string* native_prompt_execution_sha256,
        std::string* native_prompt_binding_stage,
        std::string* error) {
    if (prompt == nullptr || negative_prompt == nullptr ||
        native_prompt_execution_sha256 == nullptr ||
        native_prompt_binding_stage == nullptr || error == nullptr) {
        return false;
    }
    try {
        const nlohmann::json root = nlohmann::json::parse(json);
        if (!root.is_object()) {
            *error = "QNN execution params must be one structured JSON object.";
            return false;
        }
        const auto prompt_value = root.find("prompt");
        const auto negative_prompt_value = root.find("negativePrompt");
        const auto prompt_sha256_value = root.find("nativePromptExecutionSha256");
        const auto prompt_stage_value = root.find("nativePromptBindingStage");
        if (prompt_value == root.end() || !prompt_value->is_string()) {
            *error = "Native execution field 'prompt' must be a string.";
            return false;
        }
        if (negative_prompt_value == root.end() || !negative_prompt_value->is_string()) {
            *error = "Native execution field 'negativePrompt' must be a string.";
            return false;
        }
        if (prompt_sha256_value == root.end() || !prompt_sha256_value->is_string()) {
            *error = "Native execution field 'nativePromptExecutionSha256' must be a string.";
            return false;
        }
        if (prompt_stage_value == root.end() || !prompt_stage_value->is_string()) {
            *error = "Native execution field 'nativePromptBindingStage' must be a string.";
            return false;
        }
        *prompt = prompt_value->get<std::string>();
        *negative_prompt = negative_prompt_value->get<std::string>();
        *native_prompt_execution_sha256 = prompt_sha256_value->get<std::string>();
        *native_prompt_binding_stage = prompt_stage_value->get<std::string>();
        if (prompt->empty()) {
            *error = "Native execution field 'prompt' must not be blank.";
            return false;
        }
        if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
                *prompt,
                *negative_prompt)) {
            *error = "Native multilingual image prompts must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax.";
            return false;
        }
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN execution params JSON is invalid: ") + exception.what();
        return false;
    }
}

bool parse_qnn_semantic_execution_contract(
        const std::string& json,
        QnnSemanticExecutionContract* contract,
        std::string* error) {
    long long integer_value = 0;
    double number_value = 0.0;
    std::string runtime;
    std::string vae_scaling_location;
    bool unconditional_branch = false;
    if (!parse_qnn_prompt_execution_fields(
            json,
            &contract->prompt,
            &contract->negative_prompt,
            &contract->native_prompt_execution_sha256,
            &contract->native_prompt_binding_stage,
            error)) {
        return false;
    }
    if (!qnn_required_string_field(json, "profileId", &contract->profile_id, error) ||
        !qnn_required_integer_field(json, "profileRevision", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0 || integer_value > std::numeric_limits<int>::max()) {
        *error = "profileRevision must be a positive int32 value.";
        return false;
    }
    contract->profile_revision = static_cast<int>(integer_value);
    if (!qnn_required_string_field(json, "modelFingerprint", &contract->model_fingerprint, error) ||
        !qnn_required_string_field(json, "runtime", &runtime, error)) {
        return false;
    }
    if (normalized_contract_enum(runtime) != "qnn_htp") {
        *error = "QNN semantic generation requires runtime=QNN_HTP.";
        return false;
    }
    if (!parse_qnn_scheduler_contract(json, &contract->scheduler, error) ||
        !qnn_required_number_field(json, "cfgScale", &number_value, error)) {
        return false;
    }
    if (number_value < 0.0 || number_value > 30.0) {
        *error = "cfgScale must be in [0, 30].";
        return false;
    }
    contract->cfg_scale = static_cast<float>(number_value);
    if (!qnn_required_bool_field(json, "useCfg", &contract->use_cfg, error) ||
        !qnn_required_bool_field(json, "unconditionalBranch", &unconditional_branch, error)) {
        return false;
    }
    if (unconditional_branch != contract->use_cfg) {
        *error = "unconditionalBranch must exactly match useCfg.";
        return false;
    }
    if (!contract->use_cfg && std::fabs(number_value - 1.0) > 1.0e-12) {
        *error = "Conditional-only execution requires cfgScale=1 when useCfg=false.";
        return false;
    }
    if (!contract->use_cfg && !contract->negative_prompt.empty()) {
        *error = "A negativePrompt cannot affect pixels when useCfg=false.";
        return false;
    }
    if (!qnn_required_integer_field(json, "seed", &integer_value, error) ||
        integer_value < 0 ||
        static_cast<unsigned long long>(integer_value) > std::numeric_limits<uint32_t>::max()) {
        if (error->empty()) *error = "seed must fit the native uint32 RNG contract.";
        return false;
    }
    contract->seed = static_cast<uint32_t>(integer_value);
    if (!qnn_required_string_field(json, "tokenizerBackend", &contract->tokenizer_backend, error) ||
        !qnn_required_integer_field(json, "tokenCount", &integer_value, error)) {
        return false;
    }
    if (integer_value <= 0) {
        *error = "tokenCount must be positive.";
        return false;
    }
    contract->token_count = static_cast<size_t>(integer_value);
    if (!qnn_required_integer_field(json, "tokenizerMaxLength", &integer_value, error) ||
        integer_value <= 0 || integer_value > std::numeric_limits<int>::max()) {
        if (error->empty()) *error = "tokenizerMaxLength must be a positive int32 value.";
        return false;
    }
    contract->tokenizer_max_length = static_cast<int>(integer_value);
    contract->tokenizer_backend = normalized_contract_enum(contract->tokenizer_backend);
    if (contract->tokenizer_backend == "tokenizers_cpp") {
        contract->tokenizer_backend = "TOKENIZERS_CPP";
    } else if (contract->tokenizer_backend == "mnn_mtok") {
        contract->tokenizer_backend = "MNN_MTOK";
    } else {
        *error = "Unsupported tokenizerBackend contract value: " + contract->tokenizer_backend;
        return false;
    }
    if (!qnn_required_bool_field(
            json,
            "promptWeightingSupported",
            &contract->prompt_weighting_supported,
            error)) {
        return false;
    }
    if (contract->prompt_weighting_supported &&
        contract->tokenizer_backend != "TOKENIZERS_CPP") {
        *error = "Prompt weighting requires the complete TOKENIZERS_CPP contract.";
        return false;
    }
    if (!qnn_required_string_field(
            json,
            "embeddingDiskDataType",
            &contract->embedding_disk_data_type,
            error)) {
        return false;
    }
    contract->embedding_disk_data_type = normalized_contract_enum(
        contract->embedding_disk_data_type);
    if (contract->embedding_disk_data_type == "graph_internal") {
        contract->embedding_disk_data_type = "GRAPH_INTERNAL";
    } else if (contract->embedding_disk_data_type == "fp16") {
        contract->embedding_disk_data_type = "FP16";
    } else if (contract->embedding_disk_data_type == "fp32") {
        contract->embedding_disk_data_type = "FP32";
    } else {
        *error = "Unsupported embeddingDiskDataType contract value: " +
            contract->embedding_disk_data_type;
        return false;
    }
    if (!qnn_required_string_field(json, "vaeScalingLocation", &vae_scaling_location, error) ||
        !qnn_required_number_field(json, "vaeScalingFactor", &number_value, error)) {
        return false;
    }
    if (number_value <= 0.0) {
        *error = "vaeScalingFactor must be finite and positive.";
        return false;
    }
    contract->vae_scaling_factor = number_value;
    vae_scaling_location = normalized_contract_enum(vae_scaling_location);
    if (vae_scaling_location == "host_before_graph") {
        contract->vae_scaling_location = QnnVaeScalingLocation::HostBeforeGraph;
    } else if (vae_scaling_location == "graph_internal") {
        contract->vae_scaling_location = QnnVaeScalingLocation::GraphInternal;
    } else if (vae_scaling_location == "none") {
        contract->vae_scaling_location = QnnVaeScalingLocation::None;
    } else {
        *error = "Unsupported explicit QNN vaeScalingLocation contract value: " +
            vae_scaling_location;
        return false;
    }
    if (contract->vae_scaling_location == QnnVaeScalingLocation::None &&
        std::fabs(contract->vae_scaling_factor - 1.0) > 1e-12) {
        *error = "vaeScalingLocation=NONE requires vaeScalingFactor=1.";
        return false;
    }
    if (!parse_qnn_pixel_range_contract(json, &contract->pixel_range, error)) {
        return false;
    }
    if (!qnn_required_integer_field(json, "width", &integer_value, error) ||
        integer_value <= 0 || integer_value > 16384 || integer_value % 8 != 0) {
        if (error->empty()) *error = "width must be a positive multiple of 8 no larger than 16384.";
        return false;
    }
    contract->width = static_cast<int>(integer_value);
    if (!qnn_required_integer_field(json, "height", &integer_value, error) ||
        integer_value <= 0 || integer_value > 16384 || integer_value % 8 != 0) {
        if (error->empty()) *error = "height must be a positive multiple of 8 no larger than 16384.";
        return false;
    }
    contract->height = static_cast<int>(integer_value);
    bool fallback = true;
    if (!qnn_required_string_field(json, "graphName", &contract->graph_name, error) ||
        !qnn_required_bool_field(json, "fallback", &fallback, error)) {
        return false;
    }
    if (fallback) {
        *error = "QNN native execution requires fallback=false.";
        return false;
    }
    if (json.find("\"vaeLatentScale\"") != std::string::npos) {
        *error = "Deprecated vaeLatentScale conflicts with the explicit VAE scaling contract.";
        return false;
    }
    return true;
}

const char* qnn_scheduler_wire_name(mca::diffusion::SchedulerAlgorithm algorithm) {
    switch (algorithm) {
        case mca::diffusion::SchedulerAlgorithm::EulerDiscrete: return "EULER";
        case mca::diffusion::SchedulerAlgorithm::Ddim: return "DDIM";
        case mca::diffusion::SchedulerAlgorithm::Pndm: return "PNDM_PLMS";
        case mca::diffusion::SchedulerAlgorithm::Dpmpp2m: return "DPMPP_2M";
    }
    return "UNKNOWN";
}

const char* qnn_prediction_wire_name(mca::diffusion::PredictionType prediction) {
    switch (prediction) {
        case mca::diffusion::PredictionType::Epsilon: return "EPSILON";
        case mca::diffusion::PredictionType::VPrediction: return "V_PREDICTION";
        case mca::diffusion::PredictionType::Sample: return "SAMPLE";
    }
    return "UNKNOWN";
}

const char* qnn_vae_scaling_wire_name(QnnVaeScalingLocation location) {
    switch (location) {
        case QnnVaeScalingLocation::HostBeforeGraph: return "HOST_BEFORE_GRAPH";
        case QnnVaeScalingLocation::GraphInternal: return "GRAPH_INTERNAL";
        case QnnVaeScalingLocation::None: return "NONE";
    }
    return "NONE";
}

double qnn_effective_vae_host_scale(const QnnSemanticExecutionContract& contract) {
    return contract.vae_scaling_location == QnnVaeScalingLocation::HostBeforeGraph
        ? 1.0 / contract.vae_scaling_factor
        : 1.0;
}

std::string qnn_pixel_range_evidence_json(
        mca::qnn::ImagePixelRange range,
        const mca::qnn::ImagePixelRangeEvidence& evidence) {
    std::ostringstream out;
    out << std::setprecision(9)
        << "\"pixelRange\":" << quote(mca::qnn::image_pixel_range_wire_name(range)) << ","
        << "\"pixelRangeConversion\":"
        << quote(mca::qnn::image_pixel_range_conversion_name(range)) << ","
        << "\"pixelRangeValueCount\":" << evidence.value_count << ","
        << "\"pixelRangeClampedValueCount\":" << evidence.clamped_value_count << ","
        << "\"pixelRangeObservedMin\":" << evidence.observed_min << ","
        << "\"pixelRangeObservedMax\":" << evidence.observed_max;
    return out.str();
}

std::string qnn_nonempty_bundle_file_named(
        const std::vector<std::string>& files,
        const std::string& expected_name) {
    const std::string expected = normalized_contract_enum(expected_name);
    for (const auto& path : files) {
        const auto separator = path.find_last_of("/\\");
        const std::string basename = separator == std::string::npos
            ? path
            : path.substr(separator + 1u);
        if (normalized_contract_enum(basename) == expected && file_size_or_zero(path) > 0) {
            return path;
        }
    }
    return "";
}

bool qnn_sdxl_conditioning_graph_sha256(
        const std::vector<std::string>& files,
        std::string* digest,
        std::string* error) {
    if (digest == nullptr || error == nullptr) return false;
    std::vector<std::string> execution_asset_names = {"clip.mnn"};
    if (!qnn_nonempty_bundle_file_named(files, "clip.mnn.weight").empty()) {
        execution_asset_names.emplace_back("clip.mnn.weight");
    }
    execution_asset_names.emplace_back("clip_2.mnn");
    execution_asset_names.emplace_back("clip_2.mnn.weight");
    std::ostringstream payload;
    for (const auto& asset_name : execution_asset_names) {
        const std::string asset_path = qnn_nonempty_bundle_file_named(files, asset_name);
        if (asset_path.empty()) {
            *error = "MNN SDXL conditioning execution asset is missing or empty: " +
                asset_name;
            return false;
        }
        std::string asset_sha256;
        if (!qnn_file_sha256(asset_path, &asset_sha256, error)) {
            *error = "MNN SDXL conditioning execution asset SHA-256 failed for " +
                asset_name + ": " + *error;
            return false;
        }
        payload << asset_name << "=" << asset_sha256 << "\n";
    }
    const std::string canonical_payload = payload.str();
    const std::vector<uint8_t> payload_bytes(
        canonical_payload.begin(),
        canonical_payload.end());
    *digest = mca::qnn::controlnet::sha256_hex_bytes(payload_bytes);
    if (digest->size() != 64U) {
        *error = "MNN SDXL conditioning execution-closure fingerprint could not be derived.";
        return false;
    }
    return true;
}

bool resolve_qnn_conditioning_evidence(
        const std::string& bundle_root,
        const std::string& consumed_artifact_sha256,
        const std::string& conditioning_format,
        size_t observed_token_ids,
        const std::string& params_json,
        const QnnSemanticExecutionContract& contract,
        const QnnTextEncoderAssetEvidence* text_encoder_asset_evidence,
        QnnConditioningEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr) {
        *error = "Conditioning evidence output is null.";
        return false;
    }
    std::string requested_artifact_sha256;
    if (!qnn_required_string_field(
            params_json,
            "conditioningArtifactSha256",
            &requested_artifact_sha256,
            error)) {
        return false;
    }
    requested_artifact_sha256 = normalized_contract_enum(requested_artifact_sha256);
    if (requested_artifact_sha256.size() != 64U ||
        !std::all_of(
            requested_artifact_sha256.begin(),
            requested_artifact_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "conditioningArtifactSha256 must be a 64-character SHA-256 value.";
        return false;
    }
    evidence->conditioning_artifact_sha256 = normalized_contract_enum(
        consumed_artifact_sha256);
    if (evidence->conditioning_artifact_sha256.size() != 64U ||
        !std::all_of(
            evidence->conditioning_artifact_sha256.begin(),
            evidence->conditioning_artifact_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "The consumed conditioning artifact bytes have no valid SHA-256 evidence.";
        return false;
    }
    if (evidence->conditioning_artifact_sha256 != requested_artifact_sha256) {
        *error = "The conditioning artifact bytes differ from conditioningArtifactSha256.";
        return false;
    }
    std::string native_prompt_execution_sha256 =
        contract.native_prompt_execution_sha256;
    const std::string& native_prompt_binding_stage =
        contract.native_prompt_binding_stage;
    native_prompt_execution_sha256 = normalized_contract_enum(
        native_prompt_execution_sha256);
    if (native_prompt_execution_sha256.size() != 64U ||
        !std::all_of(
            native_prompt_execution_sha256.begin(),
            native_prompt_execution_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "nativePromptExecutionSha256 must be a 64-character SHA-256 value.";
        return false;
    }
    if (native_prompt_binding_stage != "conditioning_encoded") {
        *error = "nativePromptBindingStage must be conditioning_encoded at the QNN conditioning handoff.";
        return false;
    }
    const std::string qnn_prompt_execution_sha256 =
        mca::image::image_prompt_execution_sha256(
            contract.prompt,
            contract.negative_prompt);
    if (qnn_prompt_execution_sha256.size() != 64U) {
        *error = "QNN could not derive native prompt execution SHA-256 from the structured prompt contract.";
        return false;
    }
    if (qnn_prompt_execution_sha256 != native_prompt_execution_sha256) {
        *error = "nativePromptExecutionSha256 differs from the structured prompt and negativePrompt consumed by QNN.";
        return false;
    }
    evidence->native_prompt_execution_sha256 = native_prompt_execution_sha256;
    evidence->native_prompt_binding_stage = native_prompt_binding_stage;
    const auto files = list_files_recursive(bundle_root);
    const bool signed_multilingual_tokenizer = text_encoder_asset_evidence != nullptr &&
        text_encoder_asset_evidence->multilingual_evidence_required;
    const std::string signed_tokenizer_path = signed_multilingual_tokenizer
        ? join_path(bundle_root, text_encoder_asset_evidence->tokenizer_relative_path)
        : "";
    const bool has_standard_tokenizer = signed_multilingual_tokenizer
        ? file_size_or_zero(signed_tokenizer_path) > 0
        : !qnn_nonempty_bundle_file_named(files, "tokenizer.json").empty();
    const bool has_mtok_tokenizer = !signed_multilingual_tokenizer &&
        !qnn_nonempty_bundle_file_named(files, "tokenizer.mtok").empty();
    if (has_standard_tokenizer) {
        evidence->tokenizer_backend = "TOKENIZERS_CPP";
    } else if (has_mtok_tokenizer) {
        evidence->tokenizer_backend = "MNN_MTOK";
    } else {
        *error = "The QNN bundle has no tokenizer.json or tokenizer.mtok proving the tokenizer backend.";
        return false;
    }
    if (signed_multilingual_tokenizer) {
        if (!text_encoder_asset_evidence->required ||
            text_encoder_asset_evidence->tokenizer_relative_path.empty() ||
            text_encoder_asset_evidence->tokenizer_sha256.empty() ||
            text_encoder_asset_evidence->tokenizer_size_bytes <= 0 ||
            text_encoder_asset_evidence->tokenizer_canonical_path.empty() ||
            !text_encoder_asset_evidence->tokenizer_asset_verified ||
            text_encoder_asset_evidence->mnn_prompt_handoff.empty() ||
            evidence->tokenizer_backend != "TOKENIZERS_CPP") {
            *error = "Signed multilingual QNN conditioning requires a captured tokenizerJsonPath asset and an opaque MNN prompt handoff.";
            return false;
        }
    }

    const std::string format = normalized_contract_enum(conditioning_format);
    const bool sdxl_conditioning = format == "sdxl_qnn_conditioning";
    const bool split_sdxl_conditioning = sdxl_conditioning &&
        normalized_contract_enum(string_field(params_json, "workerStrategy")) ==
            "split_unet_vae";
    // The split SDXL worker serializes only what its UNet executes. Existing
    // shared SDXL callers retain the fixed dual-branch artifact, even when a
    // distilled UNet later skips CFG, so their on-disk layout stays compatible.
    const bool conditioning_has_negative_branch = !sdxl_conditioning ||
        !split_sdxl_conditioning || contract.use_cfg;
    const size_t expected_conditioning_token_count = sdxl_conditioning &&
        !conditioning_has_negative_branch
        ? mca::image::kSdxlClipTokenCount
        : 2U * mca::image::kSdxlClipTokenCount;
    const bool strict_external_conditioning_contract =
        normalized_contract_enum(
            string_field(params_json, "conditioningContractMode")) ==
        "shared_unet_vae";
    const bool weighted_token_payload =
        format == "qnn_clip_token_ids_weights_v1";
    if (format == "qnn_clip_token_ids_i32" || weighted_token_payload) {
        if (observed_token_ids == 0) {
            *error = "qnn_clip_token_ids_i32 conditioning has no observed int32 token IDs.";
            return false;
        }
        evidence->token_count = observed_token_ids;
        evidence->embedding_disk_data_type = "GRAPH_INTERNAL";
    } else {
        if (observed_token_ids != 0) {
            *error = "Float embedding conditioning unexpectedly carried int32 token IDs.";
            return false;
        }
        constexpr uint64_t kClipVocabSize = 49408u;
        constexpr uint64_t kSd15EmbeddingWidth = 768u;
        constexpr uint64_t kSdxlSecondEmbeddingWidth = 1280u;
        const bool sd15 =
            format == "community_clip" ||
            format == "sd15_qnn_conditioning" ||
            format == "sd15_qnn_embeddings_f32";
        const bool graph_internal = format == "mnn_text_encoder_embeddings_f32";
        if (!sdxl_conditioning && !sd15 && !graph_internal) {
            *error = "Unsupported explicit float conditioningFormat: " + conditioning_format;
            return false;
        }
        evidence->token_count = expected_conditioning_token_count;
        if (graph_internal) {
            if (qnn_nonempty_bundle_file_named(files, "text_encoder.mnn").empty()) {
                *error = "mnn_text_encoder_embeddings_f32 requires a concrete text_encoder.mnn graph.";
                return false;
            }
            evidence->embedding_disk_data_type = "GRAPH_INTERNAL";
        } else {
            const std::string token_embedding = qnn_nonempty_bundle_file_named(
                files,
                "token_emb.bin");
            if (token_embedding.empty()) {
                *error = "Float CLIP conditioning lacks token_emb.bin dtype evidence.";
                return false;
            }
            const uint64_t expected_fp16 =
                kClipVocabSize * kSd15EmbeddingWidth * sizeof(uint16_t);
            const uint64_t token_embedding_bytes = static_cast<uint64_t>(
                file_size_or_zero(token_embedding));
            if (token_embedding_bytes == expected_fp16) {
                evidence->embedding_disk_data_type = "FP16";
            } else if (token_embedding_bytes == expected_fp16 * 2u) {
                // Headerless tables of this exact size use the explicit FP32 package contract;
                // no alternate byte interpretation is accepted without independent metadata.
                evidence->embedding_disk_data_type = "FP32";
            } else {
                std::ostringstream message;
                message << "token_emb.bin has " << token_embedding_bytes
                        << " bytes; expected " << expected_fp16
                        << " for FP16 or " << (expected_fp16 * 2u) << " for FP32.";
                *error = message.str();
                return false;
            }
            if (sdxl_conditioning) {
                const std::string second_token_embedding = qnn_nonempty_bundle_file_named(
                    files,
                    "token_emb_2.bin");
                if (second_token_embedding.empty()) {
                    *error = "SDXL conditioning lacks token_emb_2.bin dtype evidence.";
                    return false;
                }
                const uint64_t expected_second_fp16 =
                    kClipVocabSize * kSdxlSecondEmbeddingWidth * sizeof(uint16_t);
                const uint64_t second_bytes = static_cast<uint64_t>(
                    file_size_or_zero(second_token_embedding));
                const uint64_t expected_second = evidence->embedding_disk_data_type == "FP16"
                    ? expected_second_fp16
                    : expected_second_fp16 * 2u;
                if (second_bytes != expected_second) {
                    std::ostringstream message;
                    message << "SDXL token embedding dtypes conflict: token_emb_2.bin has "
                            << second_bytes << " bytes, expected " << expected_second << ".";
                    *error = message.str();
                    return false;
                }
            }
        }
    }

    if (contract.tokenizer_max_length != 77 ||
        evidence->token_count != expected_conditioning_token_count) {
        std::ostringstream message;
        message << "QNN CLIP conditioning requires "
                << (expected_conditioning_token_count / mca::image::kSdxlClipTokenCount)
                << " 77-token sequence(s); maxLength="
                << contract.tokenizer_max_length << ", observed tokenCount="
                << evidence->token_count << ".";
        *error = message.str();
        return false;
    }
    if (contract.tokenizer_backend != evidence->tokenizer_backend) {
        *error = "tokenizerBackend mismatch: resolved=" + contract.tokenizer_backend +
            ", conditioning evidence=" + evidence->tokenizer_backend + ".";
        return false;
    }
    if (contract.token_count != evidence->token_count) {
        std::ostringstream message;
        message << "tokenCount mismatch: resolved=" << contract.token_count
                << ", conditioning evidence=" << evidence->token_count << ".";
        *error = message.str();
        return false;
    }
    if (contract.embedding_disk_data_type != evidence->embedding_disk_data_type) {
        *error = "embeddingDiskDataType mismatch: resolved=" +
            contract.embedding_disk_data_type + ", conditioning evidence=" +
            evidence->embedding_disk_data_type + ".";
        return false;
    }
    if (weighted_token_payload && !contract.prompt_weighting_supported) {
        *error = "Weighted token payload requires promptWeightingSupported=true.";
        return false;
    }
    if (format == "qnn_clip_token_ids_i32" && contract.prompt_weighting_supported) {
        *error = "Prompt weighting requires the versioned qnn_clip_token_ids_weights_v1 payload.";
        return false;
    }

    const bool token_conditioning =
        format == "qnn_clip_token_ids_i32" || weighted_token_payload;
    bool reported_applied = false;
    long long reported_positive = 0;
    long long reported_negative = 0;
    std::string reported_fingerprint;
    if (!qnn_required_bool_field(
            params_json,
            "promptWeightingApplied",
            &reported_applied,
            error) ||
        !qnn_required_integer_field(
            params_json,
            "positiveWeightedTokenCount",
            &reported_positive,
            error) ||
        !qnn_required_integer_field(
            params_json,
            "negativeWeightedTokenCount",
            &reported_negative,
            error) ||
        !qnn_required_string_field(
            params_json,
            "promptWeightFingerprint",
            &reported_fingerprint,
            error)) {
        return false;
    }
    if (reported_positive < 0 || reported_positive > 4096 ||
        reported_negative < 0 || reported_negative > 4096) {
        *error = "Prompt weighting token counts must be in [0, 4096].";
        return false;
    }
    std::transform(
        reported_fingerprint.begin(),
        reported_fingerprint.end(),
        reported_fingerprint.begin(),
        [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
    if (reported_fingerprint.size() != 64U ||
        !std::all_of(
            reported_fingerprint.begin(),
            reported_fingerprint.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; })) {
        *error = "Prompt weighting fingerprint must be a 64-character SHA-256 value.";
        return false;
    }
    if (reported_applied != (reported_positive + reported_negative > 0)) {
        *error = "Prompt weighting applied flag conflicts with weighted token counts.";
        return false;
    }
    if (!contract.prompt_weighting_supported &&
        (reported_applied || reported_positive != 0 || reported_negative != 0)) {
        *error = "A profile without prompt weighting reported applied conditioning weights.";
        return false;
    }
    if (!contract.use_cfg && reported_negative != 0) {
        *error = "Conditioning reported weights for a negative branch that was not executed.";
        return false;
    }
    if (token_conditioning) {
        evidence->conditioning_execution_mode = "qnn_text_encoder";
        evidence->conditioning_backend = "QNN";
        evidence->conditioning_graph = "text_encoder.bin";
        if (evidence->prompt_weight_fingerprint.size() != 64U ||
            !std::all_of(
                evidence->prompt_weight_fingerprint.begin(),
                evidence->prompt_weight_fingerprint.end(),
                [](unsigned char value) { return std::isxdigit(value) != 0; })) {
            *error = "Token conditioning did not produce a valid native SHA-256 fingerprint.";
            return false;
        }
        if (evidence->prompt_weighting_applied !=
            (evidence->positive_weighted_token_count +
                evidence->negative_weighted_token_count > 0U)) {
            *error = "Native token weighting evidence conflicts with the executed branch counts.";
            return false;
        }
        if (reported_applied != evidence->prompt_weighting_applied ||
            reported_positive != static_cast<long long>(
                evidence->positive_weighted_token_count) ||
            reported_negative != static_cast<long long>(
                evidence->negative_weighted_token_count) ||
            reported_fingerprint != evidence->prompt_weight_fingerprint) {
            *error = "Producer prompt weighting evidence differs from the consumed QNN token payload.";
            return false;
        }
    } else {
        // Legacy float conditioning cannot reconstruct token weights inside the
        // QNN process. Accept only complete evidence emitted by the native
        // encoder; a versioned, artifact-bound float container remains the
        // follow-up that will remove this handoff entirely.
        if ((strict_external_conditioning_contract || format == "sdxl_qnn_conditioning") &&
            reported_fingerprint != evidence->conditioning_artifact_sha256) {
            *error = "External MNN promptWeightFingerprint must identify the exact consumed conditioning artifact.";
            return false;
        }
        evidence->prompt_weighting_applied = reported_applied;
        evidence->positive_weighted_token_count =
            static_cast<size_t>(reported_positive);
        evidence->negative_weighted_token_count =
            static_cast<size_t>(reported_negative);
        evidence->prompt_weight_fingerprint = reported_fingerprint;
    }
    if (!token_conditioning && format == "sdxl_qnn_conditioning") {
        std::string conditioning_execution_mode;
        std::string conditioning_backend;
        std::string conditioning_graph;
        std::string conditioning_graph_sha256;
        std::string conditioning_order;
        long long conditioning_encoder_execution_count = 0;
        if (!qnn_required_string_field(
                params_json,
                "conditioningExecutionMode",
                &conditioning_execution_mode,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningBackend",
                &conditioning_backend,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraph",
                &conditioning_graph,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraphSha256",
                &conditioning_graph_sha256,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningOrder",
                &conditioning_order,
                error) ||
            !qnn_required_integer_field(
                params_json,
                "conditioningEncoderExecutionCount",
                &conditioning_encoder_execution_count,
                error)) {
            return false;
        }
        const size_t expected_encoder_execution_count =
            conditioning_has_negative_branch ? 4U : 2U;
        const std::string expected_conditioning_order = conditioning_has_negative_branch
            ? "negative_then_positive"
            : "positive_only";
        if (conditioning_execution_mode != "external_mnn_sdxl_embeddings" ||
            conditioning_backend != "MNN" ||
            conditioning_graph != "clip.mnn+clip_2.mnn" ||
            conditioning_order != expected_conditioning_order ||
            conditioning_encoder_execution_count !=
                static_cast<long long>(expected_encoder_execution_count)) {
            *error = "SDXL conditioning evidence does not match the resolved MNN CLIP branch contract.";
            return false;
        }
        conditioning_graph_sha256 = normalized_contract_enum(conditioning_graph_sha256);
        std::string executed_graph_sha256;
        if (!qnn_sdxl_conditioning_graph_sha256(
                files,
                &executed_graph_sha256,
                error)) {
            return false;
        }
        if (conditioning_graph_sha256 != executed_graph_sha256) {
            *error = "The installed MNN SDXL conditioning execution assets differ from conditioningGraphSha256.";
            return false;
        }
        evidence->conditioning_execution_mode = conditioning_execution_mode;
        evidence->conditioning_backend = conditioning_backend;
        evidence->conditioning_graph = conditioning_graph;
        evidence->conditioning_graph_sha256 = executed_graph_sha256;
        evidence->conditioning_order = conditioning_order;
        evidence->conditioning_encoder_execution_count = expected_encoder_execution_count;
    } else if (!token_conditioning && strict_external_conditioning_contract &&
        (format == "community_clip" || format == "sd15_qnn_conditioning" ||
         format == "sd15_qnn_embeddings_f32")) {
        std::string conditioning_execution_mode;
        std::string conditioning_backend;
        std::string conditioning_graph;
        std::string conditioning_graph_sha256;
        std::string conditioning_order;
        long long conditioning_encoder_execution_count = 0;
        if (!qnn_required_string_field(
                params_json,
                "conditioningExecutionMode",
                &conditioning_execution_mode,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningBackend",
                &conditioning_backend,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraph",
                &conditioning_graph,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningGraphSha256",
                &conditioning_graph_sha256,
                error) ||
            !qnn_required_string_field(
                params_json,
                "conditioningOrder",
                &conditioning_order,
                error) ||
            !qnn_required_integer_field(
                params_json,
                "conditioningEncoderExecutionCount",
                &conditioning_encoder_execution_count,
                error)) {
            return false;
        }
        const size_t expected_execution_count = contract.use_cfg ? 2U : 1U;
        const std::string expected_order = contract.use_cfg
            ? "negative_then_positive"
            : "positive_only";
        if (conditioning_execution_mode != "external_mnn_embeddings" ||
            conditioning_backend != "MNN" || conditioning_graph != "clip_v2.mnn" ||
            conditioning_graph_sha256.size() != 64U ||
            !std::all_of(
                conditioning_graph_sha256.begin(),
                conditioning_graph_sha256.end(),
                [](unsigned char value) { return std::isxdigit(value) != 0; }) ||
            conditioning_order != expected_order ||
            conditioning_encoder_execution_count !=
                static_cast<long long>(expected_execution_count)) {
            *error = "External SD1.5 conditioning evidence does not match the MNN clip_v2.mnn execution contract.";
            return false;
        }
        conditioning_graph_sha256 = normalized_contract_enum(conditioning_graph_sha256);
        const std::string conditioning_graph_path = qnn_nonempty_bundle_file_named(
            files,
            conditioning_graph);
        if (conditioning_graph_path.empty()) {
            *error = "The declared external MNN conditioning graph is missing or empty in the bundle.";
            return false;
        }
        std::string executed_graph_sha256;
        if (!qnn_file_sha256(conditioning_graph_path, &executed_graph_sha256, error)) {
            *error = "External MNN conditioning graph SHA-256 could not be derived: " + *error;
            return false;
        }
        if (conditioning_graph_sha256 != executed_graph_sha256) {
            *error = "The installed external MNN conditioning graph differs from conditioningGraphSha256.";
            return false;
        }
        evidence->conditioning_execution_mode = conditioning_execution_mode;
        evidence->conditioning_backend = conditioning_backend;
        evidence->conditioning_graph = conditioning_graph;
        evidence->conditioning_graph_sha256 = executed_graph_sha256;
        evidence->conditioning_order = conditioning_order;
        evidence->conditioning_encoder_execution_count = expected_execution_count;
    } else if (!token_conditioning &&
        (format == "community_clip" || format == "sd15_qnn_conditioning" ||
         format == "sd15_qnn_embeddings_f32")) {
        // Old community packages did not publish the new cross-process fields.
        // Preserve their real execution path, while deriving graph identity
        // independently from installed bytes instead of trusting request echo.
        std::string conditioning_graph_path = qnn_nonempty_bundle_file_named(
            files,
            "clip_v2.mnn");
        std::string conditioning_graph = "clip_v2.mnn";
        if (conditioning_graph_path.empty()) {
            conditioning_graph_path = qnn_nonempty_bundle_file_named(
                files,
                "text_encoder.mnn");
            conditioning_graph = "text_encoder.mnn";
        }
        if (conditioning_graph_path.empty()) {
            *error = "Float conditioning requires a concrete MNN text encoder graph in the bundle.";
            return false;
        }
        std::string executed_graph_sha256;
        if (!qnn_file_sha256(conditioning_graph_path, &executed_graph_sha256, error)) {
            *error = "Legacy external MNN conditioning graph SHA-256 could not be derived: " + *error;
            return false;
        }
        evidence->conditioning_execution_mode = "external_mnn_embeddings";
        evidence->conditioning_backend = "MNN";
        evidence->conditioning_graph = conditioning_graph;
        evidence->conditioning_graph_sha256 = executed_graph_sha256;
        evidence->conditioning_order = contract.use_cfg
            ? "negative_then_positive"
            : "positive_only";
        evidence->conditioning_encoder_execution_count = contract.use_cfg ? 2U : 1U;
    }
    return true;
}

bool bind_qnn_consumed_prompt_evidence(
        const QnnConditioningEvidence& conditioning,
        bool conditioning_artifact_consumed,
        size_t unet_execution_count,
        QnnNativeEffectiveEvidence* native_evidence,
        std::string* error) {
    if (native_evidence == nullptr || error == nullptr) return false;
    if (!conditioning_artifact_consumed || unet_execution_count == 0U) {
        *error = "Native prompt evidence requires a consumed conditioning artifact and at least one successful UNet execution.";
        return false;
    }
    if (conditioning.native_prompt_execution_sha256.size() != 64U ||
        !std::all_of(
            conditioning.native_prompt_execution_sha256.begin(),
            conditioning.native_prompt_execution_sha256.end(),
            [](unsigned char value) { return std::isxdigit(value) != 0; }) ||
        conditioning.native_prompt_binding_stage != "conditioning_encoded") {
        *error = "Encoded native prompt evidence is incomplete at the QNN consumption boundary.";
        return false;
    }
    native_evidence->native_prompt_execution_sha256 =
        conditioning.native_prompt_execution_sha256;
    native_evidence->native_prompt_binding_stage = "conditioning_consumed";
    return true;
}

std::string qnn_prompt_to_encoder_closure_sha256(
        const QnnTextEncoderAssetEvidence& asset_evidence) {
    const auto canonical_path = [](std::string value) {
        std::replace(value.begin(), value.end(), '\\', '/');
        const size_t first = value.find_first_not_of(" \t\r\n");
        const size_t last = value.find_last_not_of(" \t\r\n");
        return first == std::string::npos ? "" : value.substr(first, last - first + 1U);
    };
    constexpr char kFieldSeparator = '\x1f';
    std::ostringstream payload;
    payload << "mca.image.prompt-to-encoder-assets.v1" << kFieldSeparator
        << "TEXT_ENCODER_GRAPH" << kFieldSeparator
        << canonical_path(asset_evidence.relative_path) << kFieldSeparator
        << asset_evidence.size_bytes << kFieldSeparator
        << asset_evidence.sha256 << kFieldSeparator
        << "TOKENIZER_JSON" << kFieldSeparator
        << canonical_path(asset_evidence.tokenizer_relative_path) << kFieldSeparator
        << asset_evidence.tokenizer_size_bytes << kFieldSeparator
        << asset_evidence.tokenizer_sha256;
    const std::string canonical_payload = payload.str();
    const std::vector<uint8_t> payload_bytes(
        canonical_payload.begin(), canonical_payload.end());
    return mca::qnn::controlnet::sha256_hex_bytes(payload_bytes);
}

bool consume_qnn_mnn_prompt_handoff(
        const QnnSemanticExecutionContract& contract,
        const QnnConditioningEvidence& conditioning,
        QnnTextEncoderAssetEvidence* asset_evidence,
        std::string* error) {
    if (asset_evidence == nullptr || error == nullptr) return false;
    if (!asset_evidence->multilingual_evidence_required) {
        error->clear();
        return true;
    }
    if (!asset_evidence->required ||
        !asset_evidence->tokenizer_asset_verified ||
        asset_evidence->tokenizer_canonical_path.empty() ||
        asset_evidence->tokenizer_size_bytes <= 0 ||
        asset_evidence->mnn_prompt_handoff.empty()) {
        *error = "The MNN prompt handoff has no captured QNN tokenizer identity.";
        return false;
    }

    mca::image::prompt_handoff::Record observed;
    observed.tokenizer_canonical_path = asset_evidence->tokenizer_canonical_path;
    observed.tokenizer_device = static_cast<uint64_t>(
        asset_evidence->tokenizer_device);
    observed.tokenizer_inode = static_cast<uint64_t>(
        asset_evidence->tokenizer_inode);
    observed.tokenizer_size_bytes = static_cast<uint64_t>(
        asset_evidence->tokenizer_size_bytes);
    observed.tokenizer_sha256 = asset_evidence->tokenizer_sha256;
    observed.prompt_pair_sha256 = mca::image::image_prompt_execution_sha256(
        contract.prompt,
        contract.negative_prompt);
    observed.payload_sha256 = conditioning.conditioning_artifact_sha256;
    observed.prompt_to_encoder_closure_sha256 =
        qnn_prompt_to_encoder_closure_sha256(*asset_evidence);

    const std::string handle = std::move(asset_evidence->mnn_prompt_handoff);
    asset_evidence->mnn_prompt_handoff.clear();
    mca::image::prompt_handoff::Record consumed;
    if (!mca::image::prompt_handoff::consume(
            handle,
            observed,
            consumed,
            *error)) {
        return false;
    }
    if (asset_evidence->prompt_to_encoder_closure_sha256 !=
        consumed.prompt_to_encoder_closure_sha256) {
        *error = "The signed prompt-to-encoder closure differs from the consumed MNN prompt handoff.";
        return false;
    }
    asset_evidence->consumed_prompt_handoff = std::move(consumed);
    asset_evidence->mnn_prompt_handoff_verified = true;
    error->clear();
    return true;
}

bool bind_qnn_consumed_prompt_to_encoder_evidence(
        const QnnTextEncoderAssetEvidence& asset_evidence,
        const std::string& loaded_text_encoder_graph_name,
        const QnnConditioningEvidence& conditioning,
        QnnNativeEffectiveEvidence* native_evidence,
        std::string* error) {
    if (native_evidence == nullptr || error == nullptr) return false;
    if (!asset_evidence.multilingual_evidence_required) {
        error->clear();
        return true;
    }
    const auto valid_lower_sha256 = [](const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](unsigned char character) {
                return (character >= '0' && character <= '9') ||
                    (character >= 'a' && character <= 'f');
            });
    };
    const std::string consumed_prompt_to_encoder_closure_sha256 =
        qnn_prompt_to_encoder_closure_sha256(asset_evidence);
    const auto& prompt_handoff = asset_evidence.consumed_prompt_handoff;
    if (!asset_evidence.required || asset_evidence.relative_path.empty() ||
        !valid_lower_sha256(asset_evidence.sha256) || asset_evidence.size_bytes <= 0 ||
        asset_evidence.graph_name.empty() ||
        loaded_text_encoder_graph_name != asset_evidence.graph_name ||
        asset_evidence.tokenizer_relative_path.empty() ||
        !valid_lower_sha256(asset_evidence.tokenizer_sha256) ||
        asset_evidence.tokenizer_size_bytes <= 0 ||
        !asset_evidence.tokenizer_asset_verified ||
        !asset_evidence.mnn_prompt_handoff_verified ||
        prompt_handoff.tokenizer_canonical_path !=
            asset_evidence.tokenizer_canonical_path ||
        prompt_handoff.tokenizer_device !=
            static_cast<uint64_t>(asset_evidence.tokenizer_device) ||
        prompt_handoff.tokenizer_inode !=
            static_cast<uint64_t>(asset_evidence.tokenizer_inode) ||
        prompt_handoff.tokenizer_size_bytes !=
            static_cast<uint64_t>(asset_evidence.tokenizer_size_bytes) ||
        prompt_handoff.tokenizer_sha256 != asset_evidence.tokenizer_sha256 ||
        !valid_lower_sha256(asset_evidence.prompt_to_encoder_closure_sha256) ||
        consumed_prompt_to_encoder_closure_sha256 !=
            asset_evidence.prompt_to_encoder_closure_sha256 ||
        prompt_handoff.prompt_to_encoder_closure_sha256 !=
            consumed_prompt_to_encoder_closure_sha256 ||
        prompt_handoff.prompt_pair_sha256 !=
            conditioning.native_prompt_execution_sha256 ||
        prompt_handoff.payload_sha256 !=
            conditioning.conditioning_artifact_sha256 ||
        !valid_lower_sha256(asset_evidence.language_proof_sha256) ||
        conditioning.tokenizer_backend != "TOKENIZERS_CPP" ||
        conditioning.tokenizer_binding_stage != "tokenizer_consumed" ||
        !native_evidence->conditioning_artifact_consumed ||
        !valid_lower_sha256(native_evidence->conditioning_artifact_sha256) ||
        native_evidence->conditioning_artifact_sha256 !=
            conditioning.conditioning_artifact_sha256 ||
        native_evidence->native_prompt_binding_stage != "conditioning_consumed" ||
        native_evidence->text_encoder_execution_count == 0U) {
        *error = "Signed multilingual QNN evidence did not bind the exact context graph, loaded graph, tokenizer asset, consumed conditioning artifact, and prompt-to-encoder closure.";
        return false;
    }
    native_evidence->text_encoder_graph_name = asset_evidence.graph_name;
    native_evidence->loaded_text_encoder_graph_name = loaded_text_encoder_graph_name;
    native_evidence->consumed_text_encoder_path = asset_evidence.relative_path;
    native_evidence->consumed_text_encoder_sha256 = asset_evidence.sha256;
    native_evidence->consumed_text_encoder_size_bytes = asset_evidence.size_bytes;
    native_evidence->consumed_text_encoder_asset_verified = true;
    native_evidence->consumed_tokenizer_path = asset_evidence.tokenizer_relative_path;
    native_evidence->consumed_tokenizer_sha256 = prompt_handoff.tokenizer_sha256;
    native_evidence->consumed_tokenizer_size_bytes =
        static_cast<long long>(prompt_handoff.tokenizer_size_bytes);
    native_evidence->consumed_tokenizer_asset_verified = true;
    native_evidence->tokenizer_binding_stage = conditioning.tokenizer_binding_stage;
    native_evidence->tokenizer_receipt_canonical_path =
        prompt_handoff.tokenizer_canonical_path;
    native_evidence->tokenizer_receipt_sha256 =
        prompt_handoff.tokenizer_sha256;
    native_evidence->tokenizer_receipt_size_bytes =
        static_cast<long long>(prompt_handoff.tokenizer_size_bytes);
    native_evidence->tokenizer_receipt_binding_stage = "tokenizer_consumed";
    native_evidence->mnn_prompt_handoff_verified = true;
    native_evidence->language_proof_sha256 = asset_evidence.language_proof_sha256;
    native_evidence->consumed_prompt_to_encoder_closure_sha256 =
        prompt_handoff.prompt_to_encoder_closure_sha256;
    native_evidence->consumed_prompt_to_encoder_binding_stage =
        "conditioning_consumed";
    error->clear();
    return true;
}

std::string qnn_double_array_json(const std::vector<double>& values) {
    std::ostringstream out;
    out << std::setprecision(17) << "[";
    for (size_t index = 0; index < values.size(); ++index) {
        if (index > 0) out << ",";
        out << values[index];
    }
    out << "]";
    return out.str();
}

std::string qnn_consumed_prompt_to_encoder_assets_json(
        const QnnNativeEffectiveEvidence& evidence) {
    if (evidence.consumed_prompt_to_encoder_binding_stage != "conditioning_consumed") {
        return "[]";
    }
    std::ostringstream out;
    out << "["
        << "{\"role\":\"TEXT_ENCODER_GRAPH\",\"path\":"
        << quote(evidence.consumed_text_encoder_path)
        << ",\"sha256\":" << quote(evidence.consumed_text_encoder_sha256)
        << ",\"sizeBytes\":" << evidence.consumed_text_encoder_size_bytes << "},"
        << "{\"role\":\"TOKENIZER_JSON\",\"path\":"
        << quote(evidence.consumed_tokenizer_path)
        << ",\"sha256\":" << quote(evidence.consumed_tokenizer_sha256)
        << ",\"sizeBytes\":" << evidence.consumed_tokenizer_size_bytes << "}"
        << "]";
    return out.str();
}

// Keep the flat receipt and nativeEffective receipt byte-for-byte aligned for the
// role-aware prompt-to-encoder proof. Empty values are intentional for legacy English
// requests, which do not opt into the multilingual closure contract.
std::string qnn_prompt_to_encoder_receipt_json_fields(
        const QnnNativeEffectiveEvidence& evidence) {
    std::ostringstream out;
    out << "\"textEncoderGraphName\":" << quote(evidence.text_encoder_graph_name) << ","
        << "\"loadedTextEncoderGraphName\":"
        << quote(evidence.loaded_text_encoder_graph_name) << ","
        << "\"consumedTextEncoderPath\":"
        << quote(evidence.consumed_text_encoder_path) << ","
        << "\"consumedTextEncoderSha256\":"
        << quote(evidence.consumed_text_encoder_sha256) << ","
        << "\"consumedTextEncoderSizeBytes\":"
        << evidence.consumed_text_encoder_size_bytes << ","
        << "\"consumedTextEncoderAssetVerified\":"
        << (evidence.consumed_text_encoder_asset_verified ? "true" : "false") << ","
        << "\"consumedTokenizerPath\":"
        << quote(evidence.consumed_tokenizer_path) << ","
        << "\"consumedTokenizerSha256\":"
        << quote(evidence.consumed_tokenizer_sha256) << ","
        << "\"consumedTokenizerSizeBytes\":"
        << evidence.consumed_tokenizer_size_bytes << ","
        << "\"consumedTokenizerAssetVerified\":"
        << (evidence.consumed_tokenizer_asset_verified ? "true" : "false") << ","
        << "\"tokenizerBindingStage\":"
        << quote(evidence.tokenizer_binding_stage) << ","
        << "\"mnnPromptHandoffVerified\":"
        << (evidence.mnn_prompt_handoff_verified ? "true" : "false") << ","
        << "\"tokenizerReceiptCanonicalPath\":"
        << quote(evidence.tokenizer_receipt_canonical_path) << ","
        << "\"tokenizerReceiptSha256\":"
        << quote(evidence.tokenizer_receipt_sha256) << ","
        << "\"tokenizerReceiptSizeBytes\":"
        << evidence.tokenizer_receipt_size_bytes << ","
        << "\"tokenizerReceiptBindingStage\":"
        << quote(evidence.tokenizer_receipt_binding_stage) << ","
        << "\"languageProofSha256\":"
        << quote(evidence.language_proof_sha256) << ","
        << "\"promptToEncoderClosureSha256\":"
        << quote(evidence.consumed_prompt_to_encoder_closure_sha256) << ","
        << "\"consumedPromptToEncoderClosureSha256\":"
        << quote(evidence.consumed_prompt_to_encoder_closure_sha256) << ","
        << "\"consumedPromptToEncoderBindingStage\":"
        << quote(evidence.consumed_prompt_to_encoder_binding_stage) << ","
        << "\"consumedPromptToEncoderAssets\":"
        << qnn_consumed_prompt_to_encoder_assets_json(evidence);
    return out.str();
}

std::string qnn_native_effective_json(
        const QnnSemanticExecutionContract& contract,
        const QnnNativeEffectiveEvidence& evidence) {
    std::ostringstream out;
    out << std::setprecision(17)
        << "{"
        << "\"profileId\":" << quote(contract.profile_id) << ","
        << "\"profileRevision\":" << contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\","
        << "\"scheduler\":" << quote(qnn_scheduler_wire_name(contract.scheduler.config.algorithm)) << ","
        << "\"predictionType\":" << quote(qnn_prediction_wire_name(contract.scheduler.config.prediction_type)) << ","
        << "\"steps\":" << contract.scheduler.steps << ","
        << "\"timetableCount\":" << evidence.timetable_count << ","
        << "\"unetExecutionCount\":" << evidence.unet_execution_count << ","
        << "\"cfgScale\":" << contract.cfg_scale << ","
        << "\"useCfg\":" << (contract.use_cfg ? "true" : "false") << ","
        << "\"unconditionalBranch\":" << (contract.use_cfg ? "true" : "false") << ","
        << "\"tokenizerBackend\":" << quote(evidence.tokenizer_backend) << ","
        << "\"tokenCount\":" << evidence.token_count << ","
        << "\"promptWeightingSupported\":" << (contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingApplied\":" << (evidence.prompt_weighting_applied ? "true" : "false") << ","
        << "\"positiveWeightedTokenCount\":" << evidence.positive_weighted_token_count << ","
        << "\"negativeWeightedTokenCount\":" << evidence.negative_weighted_token_count << ","
        << "\"promptWeightFingerprint\":" << quote(evidence.prompt_weight_fingerprint) << ","
        << "\"nativePromptExecutionSha256\":"
        << quote(evidence.native_prompt_execution_sha256) << ","
        << "\"nativePromptBindingStage\":"
        << quote(evidence.native_prompt_binding_stage) << ","
        << "\"conditioningArtifactSha256\":"
        << quote(evidence.conditioning_artifact_sha256) << ","
        << "\"conditioningExecutionMode\":" << quote(evidence.conditioning_execution_mode) << ","
        << "\"conditioningBackend\":" << quote(evidence.conditioning_backend) << ","
        << "\"conditioningGraph\":" << quote(evidence.conditioning_graph) << ","
        << "\"conditioningGraphSha256\":"
        << quote(evidence.conditioning_graph_sha256) << ","
        << "\"conditioningOrder\":" << quote(evidence.conditioning_order) << ","
        << "\"conditioningEncoderExecutionCount\":"
        << evidence.conditioning_encoder_execution_count << ","
        << "\"textEncoderExecutionCount\":" << evidence.text_encoder_execution_count << ","
        << qnn_prompt_to_encoder_receipt_json_fields(evidence) << ","
        << "\"conditioningArtifactConsumed\":"
        << (evidence.conditioning_artifact_consumed ? "true" : "false") << ","
        << "\"runtimeSessionMode\":" << quote(evidence.runtime_session_mode) << ","
        << "\"embeddingDiskDataType\":" << quote(evidence.embedding_disk_data_type) << ","
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << contract.vae_scaling_factor << ","
        << "\"pixelRange\":" << quote(mca::qnn::image_pixel_range_wire_name(contract.pixel_range)) << ","
        << "\"width\":" << evidence.width << ","
        << "\"height\":" << evidence.height << ","
        << "\"seed\":" << contract.seed << ","
        << "\"graphName\":" << quote(evidence.graph_name) << ","
        << "\"taskMode\":" << quote(evidence.task_mode) << ","
        << "\"batchCount\":1,"
        << "\"inputImagePath\":" << quote(evidence.input_image_path) << ","
        << "\"maskImagePath\":" << quote(evidence.mask_image_path) << ","
        << "\"controlImagePath\":" << quote(evidence.control_image_path) << ","
        << "\"inputImageExecutionCount\":" << evidence.input_image_execution_count << ","
        << "\"maskImageExecutionCount\":" << evidence.mask_image_execution_count << ","
        << "\"maskImageSha256\":" << quote(evidence.mask_image_sha256) << ","
        << "\"maskImageSizeBytes\":" << evidence.mask_image_source_bytes << ","
        << "\"maskImageSourceReadByNative\":false,"
        << "\"maskImageSourceValidation\":"
        << quote(evidence.mask_image_execution_count == 1U
            ? "android_preprocess_provenance"
            : "none") << ","
        << "\"maskImageTensorPath\":" << quote(evidence.mask_image_tensor_path) << ","
        << "\"maskImageTensorSha256\":" << quote(evidence.mask_image_tensor_sha256) << ","
        << "\"maskImageTensorBytes\":" << evidence.mask_image_tensor_bytes << ","
        << "\"maskImageTensorShape\":[1,1,"
        << (evidence.mask_image_execution_count == 1U ? contract.height / 8 : 0)
        << ","
        << (evidence.mask_image_execution_count == 1U ? contract.width / 8 : 0)
        << "],"
        << "\"maskImageTensorDtype\":" << quote(evidence.mask_image_tensor_dtype) << ","
        << "\"maskImageTensorLayout\":" << quote(evidence.mask_image_tensor_layout) << ","
        << "\"maskImageTensorRange\":" << quote(evidence.mask_image_tensor_range) << ","
        << "\"maskImageTensorPreprocess\":" << quote(evidence.mask_image_tensor_preprocess) << ","
        << "\"maskImageFullTensorPath\":" << quote(evidence.mask_image_full_tensor_path) << ","
        << "\"maskImageFullTensorSha256\":" << quote(evidence.mask_image_full_tensor_sha256) << ","
        << "\"maskImageFullTensorBytes\":" << evidence.mask_image_full_tensor_bytes << ","
        << "\"maskImageFullTensorShape\":[1,1,"
        << (evidence.mask_image_execution_count == 1U ? contract.height : 0) << ","
        << (evidence.mask_image_execution_count == 1U ? contract.width : 0) << "],"
        << "\"maskImageFullTensorDtype\":" << quote(evidence.mask_image_full_tensor_dtype) << ","
        << "\"maskImageFullTensorLayout\":" << quote(evidence.mask_image_full_tensor_layout) << ","
        << "\"maskImageFullTensorRange\":" << quote(evidence.mask_image_full_tensor_range) << ","
        << "\"maskImageFullTensorPreprocess\":"
        << quote(evidence.mask_image_full_tensor_preprocess) << ","
        << "\"maskImageSourceWidth\":" << evidence.mask_image_source_width << ","
        << "\"maskImageSourceHeight\":" << evidence.mask_image_source_height << ","
        << "\"maskImageOrientedWidth\":" << evidence.mask_image_oriented_width << ","
        << "\"maskImageOrientedHeight\":" << evidence.mask_image_oriented_height << ","
        << "\"maskImageExifOrientation\":" << evidence.mask_image_exif_orientation << ","
        << "\"maskImageRepaintPixelCount\":" << evidence.mask_image_repaint_pixel_count << ","
        << "\"maskImageLatentRepaintPixelCount\":"
        << evidence.mask_image_latent_repaint_pixel_count << ","
        << "\"maskedInputImageTensorPath\":"
        << quote(evidence.masked_input_image_tensor_path) << ","
        << "\"maskedInputImageTensorSha256\":"
        << quote(evidence.masked_input_image_tensor_sha256) << ","
        << "\"maskedInputImageTensorBytes\":"
        << evidence.masked_input_image_tensor_bytes << ","
        << "\"maskedInputImageTensorShape\":[1,3,"
        << (evidence.masked_input_image_tensor_bytes > 0 ? contract.height : 0)
        << ","
        << (evidence.masked_input_image_tensor_bytes > 0 ? contract.width : 0)
        << "],"
        << "\"maskedInputImageTensorDtype\":"
        << quote(evidence.masked_input_image_tensor_dtype) << ","
        << "\"maskedInputImageTensorLayout\":"
        << quote(evidence.masked_input_image_tensor_layout) << ","
        << "\"maskedInputImageTensorRange\":"
        << quote(evidence.masked_input_image_tensor_range) << ","
        << "\"maskedInputImageTensorPreprocess\":"
        << quote(evidence.masked_input_image_tensor_preprocess) << ","
        << "\"maskedInputBufferSha256\":" << quote(evidence.masked_input_buffer_sha256) << ","
        << "\"maskedInputMeanBufferSha256\":"
        << quote(evidence.masked_input_mean_buffer_sha256) << ","
        << "\"maskedInputStdBufferSha256\":"
        << quote(evidence.masked_input_std_buffer_sha256) << ","
        << "\"maskedInputLatentSha256\":" << quote(evidence.masked_input_latent_sha256) << ","
        << "\"maskedInputLatentShape\":[1,4,"
        << (evidence.masked_input_encoder_execution_count == 1U ? contract.height / 8 : 0)
        << ","
        << (evidence.masked_input_encoder_execution_count == 1U ? contract.width / 8 : 0)
        << "],"
        << "\"maskedInputEncoderExecutionCount\":"
        << evidence.masked_input_encoder_execution_count << ","
        << "\"maskedInputPosteriorSampleCount\":"
        << evidence.masked_input_posterior_sample_count << ","
        << "\"maskedInputEncoderExecuteMs\":" << evidence.masked_input_encoder_execute_ms << ","
        << "\"inpaintTopology\":" << quote(evidence.inpaint_topology) << ","
        << "\"inpaintMaskUnetBindCount\":" << evidence.inpaint_mask_unet_bind_count << ","
        << "\"inpaintPreserveStepCount\":" << evidence.inpaint_preserve_step_count << ","
        << "\"inpaintLatentBlendCount\":" << evidence.inpaint_latent_blend_count << ","
        << "\"inpaintSourceEncoderExecutionCount\":"
        << evidence.inpaint_source_encoder_execution_count << ","
        << "\"inpaintPreservedLatentChecksum\":"
        << quote(evidence.inpaint_preserved_latent_checksum) << ","
        << "\"inpaintSourceNoiseSha256\":"
        << quote(evidence.inpaint_source_noise_sha256) << ","
        << "\"inpaintSourceNoiseUseCount\":"
        << evidence.inpaint_source_noise_use_count << ","
        << "\"inpaintFinalMode\":" << quote(evidence.inpaint_final_mode) << ","
        << "\"inpaintPixelBlendLevels\":" << evidence.inpaint_pixel_blend_levels << ","
        << "\"inpaintPixelBlendChecksum\":"
        << quote(evidence.inpaint_pixel_blend_checksum) << ","
        << "\"inpaintPixelBlendApplied\":"
        << (evidence.inpaint_pixel_blend_applied ? "true" : "false") << ","
        << "\"inpaintUnmaskedPreservationApplied\":"
        << (evidence.inpaint_unmasked_preservation_applied ? "true" : "false") << ","
        << "\"inpaintMaskConsumed\":"
        << (evidence.mask_image_consumed ? "true" : "false") << ","
        << "\"controlImageExecutionCount\":" << evidence.control_image_execution_count << ","
        << "\"controlImageSha256\":" << quote(evidence.control_image_sha256) << ","
        << "\"controlImagePreprocessedSha256\":"
        << quote(evidence.control_image_preprocessed_sha256) << ","
        << "\"controlImagePreprocess\":" << quote(evidence.control_image_preprocess) << ","
        << "\"inputImageSha256\":" << quote(evidence.input_image_sha256) << ","
        << "\"inputImageTensorPath\":" << quote(evidence.input_image_tensor_path) << ","
        << "\"inputImageTensorSha256\":" << quote(evidence.input_image_tensor_sha256) << ","
        << "\"inputImageTensorBytes\":" << evidence.input_image_tensor_bytes << ","
        << "\"inputImageTensorShape\":[1,3," << evidence.input_image_tensor_height
        << "," << evidence.input_image_tensor_width << "],"
        << "\"inputImageTensorDtype\":" << quote(evidence.input_image_tensor_dtype) << ","
        << "\"inputImageTensorLayout\":" << quote(evidence.input_image_tensor_layout) << ","
        << "\"inputImageTensorRange\":" << quote(evidence.input_image_tensor_range) << ","
        << "\"inputImageSourceReadByNative\":false,"
        << "\"inputImageSourceValidation\":"
        << quote(evidence.input_image_execution_count == 1U
            ? "android_preprocess_provenance"
            : "none") << ","
        << "\"encoderLatentSha256\":" << quote(evidence.encoder_latent_sha256) << ","
        << "\"encoderContextSha256\":" << quote(evidence.encoder_context_sha256) << ","
        << "\"encoderGraphName\":" << quote(evidence.encoder_graph_name) << ","
        << "\"encoderContextLoadCount\":" << evidence.encoder_context_load_count << ","
        << "\"encoderExecutionCount\":" << evidence.encoder_execution_count << ","
        << "\"encoderInputName\":" << quote(evidence.encoder_input_name) << ","
        << "\"encoderMeanOutputName\":" << quote(evidence.encoder_mean_output_name) << ","
        << "\"encoderStdOutputName\":" << quote(evidence.encoder_std_output_name) << ","
        << "\"encoderInputDtype\":" << quote(evidence.encoder_input_dtype) << ","
        << "\"encoderMeanDtype\":" << quote(evidence.encoder_mean_dtype) << ","
        << "\"encoderStdDtype\":" << quote(evidence.encoder_std_dtype) << ","
        << "\"encoderInputShape\":[1,3," << evidence.encoder_graph_input_height
        << "," << evidence.encoder_graph_input_width << "],"
        << "\"encoderMeanShape\":[1,4," << evidence.encoder_graph_output_height
        << "," << evidence.encoder_graph_output_width << "],"
        << "\"encoderStdShape\":[1,4," << evidence.encoder_graph_output_height
        << "," << evidence.encoder_graph_output_width << "],"
        << "\"encoderInputBufferSha256\":"
        << quote(evidence.encoder_input_buffer_sha256) << ","
        << "\"encoderMeanBufferSha256\":"
        << quote(evidence.encoder_mean_buffer_sha256) << ","
        << "\"encoderStdBufferSha256\":"
        << quote(evidence.encoder_std_buffer_sha256) << ","
        << "\"posteriorSampling\":"
        << quote(evidence.encoder_execution_count > 0U
            ? "mean_plus_std_times_normal_mt19937_domain_v1"
            : "none") << ","
        << "\"posteriorSampleCount\":" << evidence.encoder_posterior_sample_count << ","
        << "\"encoderLatentScalingFactor\":"
        << evidence.encoder_latent_scaling_factor << ","
        << "\"encoderContextReleasedBeforeSharedSession\":"
        << (evidence.encoder_context_released_before_shared_session ? "true" : "false") << ","
        << "\"encoderRuntimeMode\":" << quote(evidence.encoder_runtime_mode) << ","
        << "\"inputImagePreprocess\":" << quote(evidence.input_image_preprocess) << ","
        << "\"inputImageSourceWidth\":" << evidence.input_image_source_width << ","
        << "\"inputImageSourceHeight\":" << evidence.input_image_source_height << ","
        << "\"inputImageOrientedWidth\":" << evidence.input_image_oriented_width << ","
        << "\"inputImageOrientedHeight\":" << evidence.input_image_oriented_height << ","
        << "\"inputImageExifOrientation\":" << evidence.input_image_exif_orientation << ","
        << "\"inputImageTensorWidth\":" << evidence.input_image_tensor_width << ","
        << "\"inputImageTensorHeight\":" << evidence.input_image_tensor_height << ","
        << "\"inputImageTensorChannels\":" << evidence.input_image_tensor_channels << ","
        << "\"img2imgBeginIndex\":" << evidence.img2img_begin_index << ","
        << "\"fullTimetableCount\":" << evidence.full_timetable_count << ","
        << "\"effectiveDenoiseSteps\":" << evidence.effective_denoise_steps << ","
        << "\"img2imgAddNoiseApplied\":"
        << (evidence.img2img_add_noise_applied ? "true" : "false") << ","
        << "\"img2imgAddNoiseBeginIndex\":"
        << evidence.img2img_add_noise_begin_index << ","
        << "\"img2imgAddNoiseTimestep\":" << evidence.img2img_add_noise_timestep << ","
        << "\"img2imgNoiseChecksum\":" << quote(evidence.img2img_noise_checksum) << ","
        << "\"strength\":" << evidence.strength << ","
        << "\"controlStrength\":" << evidence.control_strength << ","
        << "\"controlStrengthApplied\":"
        << (evidence.control_image_execution_count == 1U ? "true" : "false") << ","
        << "\"clipSkip\":-1,"
        << "\"controlNetExecutionCount\":" << evidence.controlnet_execution_count << ","
        << "\"controlNetResidualTensorCount\":" << evidence.controlnet_residual_tensor_count << ","
        << "\"controlNetResidualWriteCount\":" << evidence.controlnet_residual_write_count << ","
        << "\"controlNetResidualUnetReuseCount\":"
        << evidence.controlnet_residual_unet_reuse_count << ","
        << "\"controlNetConditioningBranch\":"
        << quote(evidence.controlnet_conditioning_branch) << ","
        << "\"controlNetInputConsumed\":"
        << (evidence.controlnet_input_consumed ? "true" : "false") << ","
        << "\"controlNetGraph\":" << quote(evidence.controlnet_graph_name) << ","
        << "\"controlImageSourceWidth\":" << evidence.control_image_source_width << ","
        << "\"controlImageSourceHeight\":" << evidence.control_image_source_height << ","
        << "\"controlImageSourceChannels\":" << evidence.control_image_source_channels << ","
        << "\"controlImageTensorWidth\":" << evidence.control_image_tensor_width << ","
        << "\"controlImageTensorHeight\":" << evidence.control_image_tensor_height << ","
        << "\"controlImageTensorChannels\":" << evidence.control_image_tensor_channels << ","
        << "\"controlImageExifOrientation\":" << evidence.control_image_exif_orientation << ","
        << "\"controlImageEdgePixelCount\":" << evidence.control_image_edge_pixel_count << ","
        << "\"controlImageTensorChecksum\":" << evidence.control_image_tensor_checksum << ","
        << "\"controlNetInputBufferSha256\":"
        << quote(evidence.controlnet_input_buffer_sha256) << ","
        << "\"controlNetScaledResidualChecksum\":"
        << evidence.controlnet_scaled_residual_checksum << ","
        << "\"fallback\":false";
    if (!evidence.ultra_fix_json.empty()) {
        out << ",\"ultraFix\":" << evidence.ultra_fix_json
            << ",\"strengthMechanism\":\"ddim_inversion\""
            << ",\"outputSha256\":" << quote(evidence.ultra_fix_output_sha256)
            << ",\"outputSizeBytes\":" << evidence.ultra_fix_output_bytes
            << ",\"outputAtomicCommit\":"
            << (evidence.ultra_fix_output_atomic_commit ? "true" : "false")
            << ",\"positiveDiffusionModelComputeCount\":"
            << evidence.ultra_fix_positive_diffusion_model_compute_count
            << ",\"negativeDiffusionModelComputeCount\":"
            << evidence.ultra_fix_negative_diffusion_model_compute_count
            << ",\"auxiliaryDiffusionModelComputeCount\":"
            << evidence.ultra_fix_auxiliary_diffusion_model_compute_count
            << ",\"samplingPassCount\":" << evidence.ultra_fix_sampling_pass_count
            << ",\"totalUnetExecutionCount\":"
            << evidence.ultra_fix_total_unet_execution_count;
    }
    out << "}";
    return out.str();
}

std::string qnn_semantic_failure_json(
        const std::string& execution_stage,
        const std::string& error_code,
        const std::string& message) {
    std::ostringstream out;
    out << "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,"
        << "\"executionStage\":" << quote(execution_stage) << ","
        << "\"errorCode\":" << quote(error_code) << ","
        << "\"message\":" << quote(message) << "}";
    return out.str();
}

std::string qnn_execution_contract_mismatch_json(
        const std::string& field,
        size_t expected,
        size_t actual) {
    std::ostringstream message;
    message << "Native " << field << " mismatch: expected " << expected
            << ", actual " << actual << ".";
    return qnn_semantic_failure_json(
        "execution_contract_mismatch",
        "EXECUTION_CONTRACT_MISMATCH",
        message.str());
}

bool qnn_write_timestep_tensor(
        QnnTensorBinding* binding,
        double timestep,
        std::string* error) {
    if (binding == nullptr || !std::isfinite(timestep)) {
        *error = "Timestep tensor binding and value must be valid and finite.";
        return false;
    }
    const uint64_t element_count = qnn_tensor_element_count(binding->tensor);
    if (element_count == 0) {
        *error = "Timestep tensor has no elements: " + binding->name;
        return false;
    }
    const Qnn_DataType_t type = qnn_tensor_data_type(binding->tensor);
    const auto quantization = qnn_tensor_quantize_params(binding->tensor);
    const bool quantized =
        quantization.quantizationEncoding != QNN_QUANTIZATION_ENCODING_UNDEFINED;
    if (type == QNN_DATATYPE_FLOAT_32 || type == QNN_DATATYPE_UFIXED_POINT_16 ||
        type == QNN_DATATYPE_SFIXED_POINT_16 ||
        ((type == QNN_DATATYPE_UINT_16 || type == QNN_DATATYPE_INT_16) && quantized)) {
        std::vector<float> values(static_cast<size_t>(element_count), static_cast<float>(timestep));
        return qnn_write_float_tensor(binding, values.data(), values.size(), error);
    }

    const double rounded = std::round(timestep);
    if (std::fabs(timestep - rounded) > 1e-6) {
        std::ostringstream message;
        message << std::setprecision(17) << "Timestep " << timestep
                << " is fractional, but graph tensor " << binding->name
                << " has integer dtype " << qnn_data_type_name(type) << ".";
        *error = message.str();
        return false;
    }
    const auto ensure_buffer = [&](size_t bytes) {
        if (binding->buffer.size() >= bytes) return true;
        *error = "Timestep tensor buffer is too small for " + binding->name;
        return false;
    };
    if (type == QNN_DATATYPE_INT_32) {
        if (rounded < std::numeric_limits<int32_t>::min() ||
            rounded > std::numeric_limits<int32_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(int32_t))) return false;
        auto* output = reinterpret_cast<int32_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<int32_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_UINT_32) {
        if (rounded < 0.0 || rounded > std::numeric_limits<uint32_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(uint32_t))) return false;
        auto* output = reinterpret_cast<uint32_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<uint32_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_INT_16) {
        if (rounded < std::numeric_limits<int16_t>::min() ||
            rounded > std::numeric_limits<int16_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(int16_t))) return false;
        auto* output = reinterpret_cast<int16_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<int16_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_UINT_16) {
        if (rounded < 0.0 || rounded > std::numeric_limits<uint16_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count) * sizeof(uint16_t))) return false;
        auto* output = reinterpret_cast<uint16_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<uint16_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_INT_8) {
        if (rounded < std::numeric_limits<int8_t>::min() ||
            rounded > std::numeric_limits<int8_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count))) return false;
        auto* output = reinterpret_cast<int8_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<int8_t>(rounded));
        return true;
    }
    if (type == QNN_DATATYPE_UINT_8) {
        if (rounded < 0.0 || rounded > std::numeric_limits<uint8_t>::max() ||
            !ensure_buffer(static_cast<size_t>(element_count))) return false;
        auto* output = reinterpret_cast<uint8_t*>(binding->buffer.data());
        std::fill(output, output + element_count, static_cast<uint8_t>(rounded));
        return true;
    }
    *error = "Unsupported QNN timestep tensor dtype for " + binding->name +
        ": " + qnn_data_type_name(type);
    return false;
}

struct QnnControlNetLayout {
    int latent_index = -1;
    int timestep_index = -1;
    int text_index = -1;
    int image_index = -1;
    mca::image::SpatialTensorShape image_shape;
    std::vector<mca::qnn::controlnet::ResidualBinding> residual_bindings;
};

int qnn_exact_tensor_index(
        const std::vector<QnnTensorBinding>& tensors,
        const std::vector<std::string>& accepted_names,
        std::string* error) {
    int resolved = -1;
    for (size_t index = 0; index < tensors.size(); ++index) {
        const std::string normalized = normalized_contract_enum(tensors[index].name);
        if (std::find(accepted_names.begin(), accepted_names.end(), normalized) ==
            accepted_names.end()) {
            continue;
        }
        if (resolved >= 0) {
            *error = "QNN graph exposes duplicate semantic tensor inputs for " +
                tensors[static_cast<size_t>(resolved)].name + " and " + tensors[index].name + ".";
            return -2;
        }
        resolved = static_cast<int>(index);
    }
    return resolved;
}

bool qnn_resolve_controlnet_layout(
        const QnnExecutableGraph& controlnet,
        const QnnExecutableGraph& unet,
        int unet_sample_index,
        int unet_timestep_index,
        int unet_text_index,
        int expected_width,
        int expected_height,
        QnnControlNetLayout* layout,
        std::string* error) {
    if (layout == nullptr || error == nullptr || unet_sample_index < 0 ||
        unet_timestep_index < 0 || unet_text_index < 0) {
        return false;
    }
    if (controlnet.inputs.size() != 4U) {
        *error = "ControlNet graph must expose exactly latent, timestep, text_emb, and image_cond inputs. inputs=" +
            qnn_tensor_list_debug_json(controlnet.inputs);
        return false;
    }
    layout->latent_index = qnn_exact_tensor_index(
        controlnet.inputs, {"latent", "sample"}, error);
    layout->timestep_index = qnn_exact_tensor_index(
        controlnet.inputs, {"timestep", "timestamp"}, error);
    layout->text_index = qnn_exact_tensor_index(
        controlnet.inputs, {"text_emb", "text_embedding", "encoder_hidden_states"}, error);
    layout->image_index = qnn_exact_tensor_index(
        controlnet.inputs, {"image_cond", "control_image", "controlnet_cond"}, error);
    if (layout->latent_index < 0 || layout->timestep_index < 0 ||
        layout->text_index < 0 || layout->image_index < 0) {
        if (error->empty()) {
            *error = "ControlNet graph tensor names do not match the required four-input contract. inputs=" +
                qnn_tensor_list_debug_json(controlnet.inputs);
        }
        return false;
    }
    if (controlnet.inputs[static_cast<size_t>(layout->latent_index)].dimensions !=
            unet.inputs[static_cast<size_t>(unet_sample_index)].dimensions ||
        controlnet.inputs[static_cast<size_t>(layout->timestep_index)].dimensions !=
            unet.inputs[static_cast<size_t>(unet_timestep_index)].dimensions ||
        controlnet.inputs[static_cast<size_t>(layout->text_index)].dimensions !=
            unet.inputs[static_cast<size_t>(unet_text_index)].dimensions) {
        *error = "ControlNet latent, timestep, and text tensor shapes must exactly match the Control-UNet base inputs.";
        return false;
    }
    if (!mca::image::resolve_spatial_tensor_shape(
            controlnet.inputs[static_cast<size_t>(layout->image_index)].dimensions,
            3U,
            &layout->image_shape,
            error)) {
        return false;
    }
    if (layout->image_shape.batch != 1U ||
        layout->image_shape.width != static_cast<size_t>(expected_width) ||
        layout->image_shape.height != static_cast<size_t>(expected_height)) {
        *error = "ControlNet image_cond tensor does not match the resolved execution width and height.";
        return false;
    }

    std::vector<mca::qnn::controlnet::TensorDescriptor> control_outputs;
    std::vector<mca::qnn::controlnet::TensorDescriptor> unet_inputs;
    control_outputs.reserve(controlnet.outputs.size());
    unet_inputs.reserve(unet.inputs.size());
    for (const auto& tensor : controlnet.outputs) {
        control_outputs.push_back({tensor.name, tensor.dimensions});
    }
    for (const auto& tensor : unet.inputs) {
        unet_inputs.push_back({tensor.name, tensor.dimensions});
    }
    return mca::qnn::controlnet::build_residual_binding_plan(
        control_outputs,
        unet_inputs,
        &layout->residual_bindings,
        error);
}

bool qnn_control_image_tensor_for_layout(
        const mca::qnn::controlnet::PreparedControlImage& image,
        const mca::image::SpatialTensorShape& shape,
        std::vector<float>* tensor,
        std::string* error) {
    if (tensor == nullptr || error == nullptr || shape.batch != 1U || shape.channels != 3U ||
        shape.width != image.tensor_width || shape.height != image.tensor_height ||
        image.tensor_hwc.size() != shape.element_count()) {
        if (error != nullptr) {
            *error = "Prepared control image does not exactly match the QNN image_cond tensor.";
        }
        return false;
    }
    if (shape.layout == mca::image::SpatialTensorLayout::Nhwc) {
        *tensor = image.tensor_hwc;
        error->clear();
        return true;
    }
    tensor->assign(shape.element_count(), 0.0F);
    for (size_t y = 0U; y < shape.height; ++y) {
        for (size_t x = 0U; x < shape.width; ++x) {
            for (size_t channel = 0U; channel < 3U; ++channel) {
                const size_t source = (y * shape.width + x) * 3U + channel;
                const size_t target = (channel * shape.height + y) * shape.width + x;
                (*tensor)[target] = image.tensor_hwc[source];
            }
        }
    }
    error->clear();
    return true;
}

bool qnn_run_controlnet_once(
        QnnExecutableGraph& controlnet,
        const QnnControlNetLayout& layout,
        QnnExecutableGraph& unet,
        const std::vector<float>& latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        const std::vector<float>& control_image_tensor,
        double control_strength,
        size_t* residual_write_count,
        uint64_t* scaled_residual_checksum,
        std::string* input_buffer_sha256,
        long long* execute_ms,
        std::string* error) {
    if (embedding == nullptr || residual_write_count == nullptr ||
        scaled_residual_checksum == nullptr || input_buffer_sha256 == nullptr ||
        execute_ms == nullptr || error == nullptr) {
        return false;
    }
    if (!qnn_write_float_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.latent_index)],
            latent.data(), latent.size(), error) ||
        !qnn_write_timestep_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.timestep_index)],
            timestep, error) ||
        !qnn_write_float_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.text_index)],
            embedding, embedding_elements, error) ||
        !qnn_write_float_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.image_index)],
            control_image_tensor.data(), control_image_tensor.size(), error)) {
        return false;
    }
    const std::string current_input_buffer_sha256 =
        mca::qnn::controlnet::sha256_hex_bytes(
            controlnet.inputs[static_cast<size_t>(layout.image_index)].buffer);
    if (current_input_buffer_sha256.size() != 64U) {
        *error = "ControlNet image_cond graph buffer SHA-256 could not be derived.";
        return false;
    }
    if (input_buffer_sha256->empty()) {
        *input_buffer_sha256 = current_input_buffer_sha256;
    } else if (*input_buffer_sha256 != current_input_buffer_sha256) {
        *error = "ControlNet image_cond graph buffer changed between scheduler steps.";
        return false;
    }
    if (!controlnet.execute(execute_ms, error)) return false;

    for (const auto& binding : layout.residual_bindings) {
        std::vector<float> residual;
        if (!qnn_read_float_tensor(
                controlnet.outputs[binding.control_output_index],
                &residual,
                error) ||
            !mca::qnn::controlnet::scale_residual_in_place(
                &residual,
                control_strength,
                error) ||
            !qnn_write_float_tensor(
                &unet.inputs[binding.unet_input_index],
                residual.data(),
                residual.size(),
                error)) {
            return false;
        }
        *scaled_residual_checksum += checksum_float_vector(residual);
        ++(*residual_write_count);
    }
    return true;
}

bool qnn_run_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const std::vector<float>& latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_float_tensor(&unet.inputs[sample_index], latent.data(), latent.size(), error) ||
        !qnn_write_timestep_tensor(&unet.inputs[timestep_index], timestep, error) ||
        !qnn_write_float_tensor(&unet.inputs[text_index], embedding, embedding_elements, error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) {
        return false;
    }
    return qnn_read_float_tensor(unet.outputs[0], output, error);
}

bool qnn_ultrafix_noise_level(
        const mca::diffusion::DiffusionScheduler& scheduler,
        size_t schedule_index,
        mca::image::UltraFixNoiseLevel* level,
        std::string* error) {
    if (level == nullptr || error == nullptr ||
        schedule_index >= scheduler.timesteps().size()) {
        if (error != nullptr) *error = "UltraFix noise-level index is outside the scheduler timetable.";
        return false;
    }
    mca::image::UltraFixNoiseLevel resolved;
    const auto algorithm = scheduler.config().algorithm;
    if (algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete ||
        algorithm == mca::diffusion::SchedulerAlgorithm::Dpmpp2m) {
        if (schedule_index >= scheduler.sigmas().size() - 1U) {
            *error = "UltraFix scheduler sigma index is unavailable.";
            return false;
        }
        const double raw_sigma = scheduler.sigmas()[schedule_index];
        if (!(raw_sigma > 0.0) || !std::isfinite(raw_sigma)) {
            *error = "UltraFix scheduler sigma must be finite and positive.";
            return false;
        }
        const double normalization = 1.0 / std::sqrt(raw_sigma * raw_sigma + 1.0);
        if (algorithm == mca::diffusion::SchedulerAlgorithm::EulerDiscrete) {
            resolved.alpha = 1.0;
            resolved.sigma = raw_sigma;
            resolved.scheduler_input_scale = normalization;
        } else {
            resolved.alpha = normalization;
            resolved.sigma = raw_sigma * normalization;
            resolved.scheduler_input_scale = 1.0;
        }
    } else if (algorithm == mca::diffusion::SchedulerAlgorithm::Ddim) {
        const long long timestep = static_cast<long long>(
            std::llround(scheduler.timesteps()[schedule_index]));
        if (timestep < 0 ||
            static_cast<size_t>(timestep) >= scheduler.alphas_cumprod().size()) {
            *error = "UltraFix DDIM timestep is outside the training alpha schedule.";
            return false;
        }
        const double alpha_product =
            scheduler.alphas_cumprod()[static_cast<size_t>(timestep)];
        if (!(alpha_product > 0.0) || alpha_product > 1.0 ||
            !std::isfinite(alpha_product)) {
            *error = "UltraFix DDIM alpha product is invalid.";
            return false;
        }
        resolved.alpha = std::sqrt(alpha_product);
        resolved.sigma = std::sqrt(std::max(0.0, 1.0 - alpha_product));
        resolved.scheduler_input_scale = 1.0;
    } else {
        *error = "UltraFix inversion does not support PNDM scheduler state.";
        return false;
    }
    *level = resolved;
    error->clear();
    return true;
}

bool qnn_run_ultrafix_tiled_unet_branch(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& full_sample,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* full_output,
        long long* execute_ms_total,
        size_t* graph_execution_count,
        std::string* error) {
    if (full_output == nullptr || execute_ms_total == nullptr ||
        graph_execution_count == nullptr || error == nullptr || embedding == nullptr ||
        full_sample.size() != plan.full_latent.element_count()) {
        if (error != nullptr) *error = "UltraFix tiled UNet inputs do not match the plan.";
        return false;
    }
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(plan.tiles.size());
    for (const auto& tile : plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during an UltraFix UNet tile pass.";
            return false;
        }
        std::vector<float> tile_sample;
        if (!mca::image::copy_spatial_tile(
                full_sample,
                plan.full_latent,
                tile.latent_x,
                tile.latent_y,
                plan.unet_input,
                &tile_sample,
                error)) {
            return false;
        }
        std::vector<float> tile_output;
        long long execute_ms = 0;
        if (!qnn_run_unet_once(
                unet,
                sample_index,
                timestep_index,
                text_index,
                tile_sample,
                timestep,
                embedding,
                embedding_elements,
                &tile_output,
                &execute_ms,
                error) ||
            tile_output.size() != plan.unet_output.element_count()) {
            if (error->empty()) *error = "UltraFix UNet tile output shape is invalid.";
            return false;
        }
        *execute_ms_total += execute_ms;
        ++(*graph_execution_count);
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled after an UltraFix UNet tile execution.";
            return false;
        }
        tile_outputs.push_back(std::move(tile_output));
    }
    if (qnn_image_generation_cancelled()) {
        *error = "Image generation was cancelled before UltraFix UNet tile blending.";
        return false;
    }
    return mca::image::blend_ultrafix_latent_tiles(
        plan, tile_outputs, full_output, error);
}

// Generic inpaint UNet binder. A regular four-channel graph keeps its normal
// sample binding and receives the mask only through the post-step latent blend.
// Explicit mask-conditioned graphs bind the additional tensors declared by
// qnn::inpaint::inspect().
bool qnn_run_inpaint_unet_once(
        QnnExecutableGraph& unet,
        const mca::qnn::inpaint::Contract& inpaint_contract,
        int timestep_index,
        int text_index,
        const std::vector<float>& latent,
        const std::vector<float>& mask,
        const std::vector<float>& masked_latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!inpaint_contract.supported() || inpaint_contract.sample_index < 0 ||
        static_cast<size_t>(inpaint_contract.sample_index) >= unet.inputs.size() ||
        timestep_index < 0 || static_cast<size_t>(timestep_index) >= unet.inputs.size() ||
        text_index < 0 || static_cast<size_t>(text_index) >= unet.inputs.size()) {
        if (error != nullptr) *error = "Mask-conditioned UNet contract is not bound to the loaded graph inputs.";
        return false;
    }
    std::vector<float> concatenated;
    if (inpaint_contract.topology == mca::qnn::inpaint::MaskTopology::LatentBlend4) {
        if (!qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.sample_index)],
                latent.data(),
                latent.size(),
                error)) {
            return false;
        }
    } else if (inpaint_contract.topology == mca::qnn::inpaint::MaskTopology::ConcatenatedLatent9) {
        if (!mca::qnn::inpaint::build_concatenated_sample(
                latent,
                mask,
                masked_latent,
                inpaint_contract.width,
                inpaint_contract.height,
                inpaint_contract.layout,
                &concatenated,
                error)) {
            return false;
        }
        if (!qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.sample_index)],
                concatenated.data(),
                concatenated.size(),
                error)) {
            return false;
        }
    } else if (inpaint_contract.topology == mca::qnn::inpaint::MaskTopology::SeparateMaskInput) {
        if (inpaint_contract.mask_index < 0 ||
            static_cast<size_t>(inpaint_contract.mask_index) >= unet.inputs.size() ||
            !qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.sample_index)],
                latent.data(),
                latent.size(),
                error) ||
            !mca::qnn::inpaint::validate_mask_values(
                mask,
                inpaint_contract.width,
                inpaint_contract.height,
                error) ||
            !qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.mask_index)],
                mask.data(),
                mask.size(),
                error)) {
            return false;
        }
    } else {
        if (error != nullptr) *error = "QNN inpaint topology is not supported by the native binder.";
        return false;
    }
    if (!qnn_write_timestep_tensor(
            &unet.inputs[static_cast<size_t>(timestep_index)],
            timestep,
            error) ||
        !qnn_write_float_tensor(
            &unet.inputs[static_cast<size_t>(text_index)],
            embedding,
            embedding_elements,
            error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) return false;
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
        double timestep,
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
        !qnn_write_timestep_tensor(&unet.inputs[timestep_index], timestep, error) ||
        !qnn_write_float_tensor(&unet.inputs[hidden_index], hidden, hidden_elements, error) ||
        !qnn_write_float_tensor(&unet.inputs[time_ids_index], time_ids, time_id_elements, error) ||
        !qnn_write_float_tensor(&unet.inputs[pooled_index], pooled, pooled_elements, error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) {
        return false;
    }
    std::vector<float> raw_output;
    if (!qnn_read_float_tensor(unet.outputs[0], &raw_output, error)) {
        return false;
    }
    mca::image::SpatialTensorShape input_shape;
    mca::image::SpatialTensorShape output_shape;
    if (!mca::image::resolve_spatial_tensor_shape(
                unet.inputs[sample_index].dimensions,
                4U,
                &input_shape,
                error) ||
        !mca::image::resolve_spatial_tensor_shape(
                unet.outputs[0].dimensions,
                4U,
                &output_shape,
                error)) {
        return false;
    }
    if (input_shape.height != output_shape.height ||
        input_shape.width != output_shape.width) {
        *error = "SDXL UNet output spatial shape does not match its latent input.";
        return false;
    }
    return mca::image::copy_spatial_tile(
        raw_output,
        output_shape,
        0U,
        0U,
        input_shape,
        output,
        error);
}

bool qnn_matches_vector_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_elements) {
    return dimensions.size() == 2U && dimensions[0] == 1U &&
        dimensions[1] == expected_elements;
}

struct QnnSdxlConditioningBuffers {
    std::vector<float> negative_hidden;
    std::vector<float> positive_hidden;
    const float* negative_pooled = nullptr;
    const float* positive_pooled = nullptr;
    const float* time_ids = nullptr;
};

bool qnn_prepare_sdxl_conditioning(
        const QnnExecutableGraph& unet,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const std::vector<float>& payload,
        bool use_cfg,
        QnnSdxlConditioningBuffers* conditioning,
        std::string* error) {
    if (conditioning == nullptr || error == nullptr || hidden_index < 0 ||
        time_ids_index < 0 || pooled_index < 0) {
        return false;
    }
    mca::image::SequenceFeatureShape hidden_shape;
    if (!mca::image::resolve_sequence_feature_shape(
            unet.inputs[static_cast<size_t>(hidden_index)].dimensions,
            static_cast<uint32_t>(mca::image::kSdxlClipTokenCount),
            static_cast<uint32_t>(mca::image::kSdxlHiddenWidth),
            &hidden_shape,
            error)) {
        return false;
    }
    if (!qnn_matches_vector_shape(
            unet.inputs[static_cast<size_t>(pooled_index)].dimensions,
            static_cast<uint32_t>(mca::image::kSdxlPooledWidth))) {
        *error = "SDXL pooled conditioning tensor must be exactly [1,1280].";
        return false;
    }
    if (!qnn_matches_vector_shape(
            unet.inputs[static_cast<size_t>(time_ids_index)].dimensions,
            static_cast<uint32_t>(mca::image::kSdxlTimeIdCount))) {
        *error = "SDXL micro-conditioning tensor must be exactly [1,6].";
        return false;
    }
    const size_t branch_count = use_cfg ? 2U : 1U;
    if (!mca::image::validate_sdxl_conditioning_payload(
            payload.size(), branch_count, error)) {
        return false;
    }
    constexpr size_t kHiddenElements =
            mca::image::kSdxlClipTokenCount * mca::image::kSdxlHiddenWidth;
    conditioning->negative_hidden.clear();
    conditioning->negative_pooled = nullptr;
    const size_t positive_hidden_offset = use_cfg ? kHiddenElements : 0U;
    if (use_cfg && !mca::image::reorder_sequence_feature_tensor(
            payload.data(),
            kHiddenElements,
            hidden_shape,
            &conditioning->negative_hidden,
            error)) {
        return false;
    }
    if (!mca::image::reorder_sequence_feature_tensor(
            payload.data() + positive_hidden_offset,
            kHiddenElements,
            hidden_shape,
            &conditioning->positive_hidden,
            error)) {
        return false;
    }
    const size_t pooled_offset = branch_count * kHiddenElements;
    if (use_cfg) {
        conditioning->negative_pooled = payload.data() + pooled_offset;
    }
    conditioning->positive_pooled = payload.data() + pooled_offset +
        (use_cfg ? mca::image::kSdxlPooledWidth : 0U);
    conditioning->time_ids =
            conditioning->positive_pooled + mca::image::kSdxlPooledWidth;
    error->clear();
    return true;
}

struct QnnVaeDecodeResult {
    mca::image::VaeDecodePlan plan;
    std::vector<float> pixels_nchw;
    size_t execution_count = 0U;
    long long execute_ms_total = 0;
};

bool qnn_decode_vae_latents(
        QnnExecutableGraph& vae,
        const std::vector<uint32_t>& source_latent_dimensions,
        const std::vector<float>& source_latents,
        const QnnSemanticExecutionContract& execution_contract,
        QnnVaeDecodeResult* result,
        std::string* error) {
    if (result == nullptr || error == nullptr || vae.inputs.empty() || vae.outputs.empty()) {
        return false;
    }
    QnnVaeDecodeResult decoded;
    if (!mca::image::build_vae_decode_plan(
            source_latent_dimensions,
            vae.inputs[0].dimensions,
            vae.outputs[0].dimensions,
            static_cast<size_t>(execution_contract.width),
            static_cast<size_t>(execution_contract.height),
            &decoded.plan,
            error)) {
        return false;
    }
    if (source_latents.size() != decoded.plan.source_latent.element_count()) {
        *error = "Published latent values do not match the resolved source latent shape.";
        return false;
    }
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(decoded.plan.tiles.size());
    for (const auto& tile : decoded.plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during VAE tile decode.";
            return false;
        }
        std::vector<float> vae_latents;
        if (!mca::image::copy_spatial_tile(
                source_latents,
                decoded.plan.source_latent,
                tile.latent_x,
                tile.latent_y,
                decoded.plan.vae_input,
                &vae_latents,
                error) ||
            !mca::image::scale_vae_latents_in_place(
                &vae_latents,
                execution_contract.vae_scaling_factor,
                execution_contract.vae_scaling_location ==
                        QnnVaeScalingLocation::HostBeforeGraph,
                error) ||
            !qnn_write_float_tensor(
                &vae.inputs[0],
                vae_latents.data(),
                vae_latents.size(),
                error)) {
            return false;
        }
        long long execute_ms = 0;
        if (!vae.execute(&execute_ms, error)) {
            return false;
        }
        decoded.execute_ms_total += execute_ms;
        ++decoded.execution_count;
        std::vector<float> tile_pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &tile_pixels, error)) {
            return false;
        }
        tile_outputs.push_back(std::move(tile_pixels));
    }
    if (!mca::image::blend_vae_decode_tiles(
            decoded.plan,
            tile_outputs,
            &decoded.pixels_nchw,
            error)) {
        return false;
    }
    *result = std::move(decoded);
    error->clear();
    return true;
}

struct QnnUltraFixVaeDecodeResult {
    std::vector<float> pixels_nchw;
    size_t execution_count = 0U;
    long long execute_ms_total = 0;
};

bool qnn_decode_ultrafix_vae_latents(
        QnnExecutableGraph& vae,
        const mca::image::UltraFixTilePlan& plan,
        const std::vector<float>& source_latents,
        const QnnSemanticExecutionContract& execution_contract,
        QnnUltraFixVaeDecodeResult* result,
        std::string* error) {
    if (result == nullptr || error == nullptr || vae.inputs.empty() ||
        vae.outputs.empty() || source_latents.size() != plan.full_latent.element_count()) {
        if (error != nullptr) *error = "UltraFix VAE decode inputs do not match the tile plan.";
        return false;
    }
    QnnUltraFixVaeDecodeResult decoded;
    std::vector<std::vector<float>> tile_outputs;
    tile_outputs.reserve(plan.tiles.size());
    for (const auto& tile : plan.tiles) {
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled during UltraFix VAE tile decode.";
            return false;
        }
        std::vector<float> tile_latents;
        if (!mca::image::copy_spatial_tile(
                source_latents,
                plan.full_latent,
                tile.latent_x,
                tile.latent_y,
                plan.vae_input,
                &tile_latents,
                error) ||
            !mca::image::scale_vae_latents_in_place(
                &tile_latents,
                execution_contract.vae_scaling_factor,
                execution_contract.vae_scaling_location ==
                    QnnVaeScalingLocation::HostBeforeGraph,
                error) ||
            !qnn_write_float_tensor(
                &vae.inputs[0], tile_latents.data(), tile_latents.size(), error)) {
            return false;
        }
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled before an UltraFix VAE tile execution.";
            return false;
        }
        long long execute_ms = 0;
        if (!vae.execute(&execute_ms, error)) return false;
        decoded.execute_ms_total += execute_ms;
        ++decoded.execution_count;
        if (qnn_image_generation_cancelled()) {
            *error = "Image generation was cancelled after an UltraFix VAE tile execution.";
            return false;
        }
        std::vector<float> tile_pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &tile_pixels, error) ||
            tile_pixels.size() != plan.vae_output.element_count()) {
            if (error->empty()) *error = "UltraFix VAE tile output shape is invalid.";
            return false;
        }
        tile_outputs.push_back(std::move(tile_pixels));
    }
    if (qnn_image_generation_cancelled()) {
        *error = "Image generation was cancelled before UltraFix VAE tile blending.";
        return false;
    }
    if (!mca::image::blend_ultrafix_pixel_tiles(
            plan, tile_outputs, &decoded.pixels_nchw, error)) {
        return false;
    }
    *result = std::move(decoded);
    error->clear();
    return true;
}

#include "qnn_sdxl_isolated_phases.hpp"

struct QnnSharedImg2ImgEncoderEvidence {
    std::vector<float> latents;
    std::vector<float> masked_latents;
    std::vector<float> source_pixels_nchw;
    std::string context_sha256;
    std::string graph_name;
    std::string input_name;
    std::string mean_name;
    std::string std_name;
    std::string input_dtype;
    std::string mean_dtype;
    std::string std_dtype;
    std::string input_buffer_sha256;
    std::string mean_buffer_sha256;
    std::string std_buffer_sha256;
    std::string latent_sha256;
    std::string masked_input_tensor_sha256;
    std::string masked_input_buffer_sha256;
    std::string masked_mean_buffer_sha256;
    std::string masked_std_buffer_sha256;
    std::string masked_latent_sha256;
    long long context_load_ms = 0;
    long long execute_ms = 0;
    long long masked_execute_ms = 0;
    size_t execution_count = 0U;
    size_t masked_execution_count = 0U;
    bool context_released_before_shared_session = false;
    bool ultra_fix = false;
    mca::image::UltraFixTilePlan ultra_fix_plan;
    std::vector<uint32_t> encoder_input_dimensions;
    std::vector<uint32_t> encoder_output_dimensions;
};

bool qnn_run_shared_img2img_encoder(
        const RuntimeProbe& runtime,
        const std::string& bundle_root,
        const std::string& params_json,
        const QnnSemanticExecutionContract& execution_contract,
        const QnnUltraFixRequest* ultra_fix,
        const QnnImageGenerationScope* generation,
        QnnSharedImg2ImgEncoderEvidence* evidence,
        std::string* failure_code,
        std::string* error) {
    if (evidence == nullptr || failure_code == nullptr || error == nullptr) return false;
    *evidence = QnnSharedImg2ImgEncoderEvidence{};
    *failure_code = "ENCODER_EXECUTION_FAILED";
    error->clear();

    const int width = execution_contract.width;
    const int height = execution_contract.height;
    const bool request_ultra_fix = ultra_fix != nullptr && ultra_fix->enabled;
    const bool request_inpaint = normalized_contract_enum(
        string_field(params_json, "taskMode")) == "inpaint";
    const std::string requested_inpaint_topology = normalized_contract_enum(
        string_field(params_json, "inpaintRequestedTopology"));
    const bool request_masked_image_latent = request_inpaint &&
        requested_inpaint_topology == "concatenated_latent_9";
    const std::string input_image_path = string_field(params_json, "inputImagePath");
    const std::string input_image_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageSha256"));
    const std::string input_tensor_path = string_field(params_json, "inputImageTensorPath");
    const std::string expected_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageTensorSha256"));
    const std::vector<uint32_t> declared_tensor_shape = uint_array_field(
        params_json, "inputImageTensorShape");
    const std::string masked_input_tensor_path = string_field(
        params_json, "maskedInputImageTensorPath");
    const std::string expected_masked_input_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "maskedInputImageTensorSha256"));
    const std::vector<uint32_t> declared_masked_input_shape = uint_array_field(
        params_json, "maskedInputImageTensorShape");
    const std::vector<uint32_t> expected_full_input_shape{
        1U,
        3U,
        static_cast<uint32_t>(height),
        static_cast<uint32_t>(width),
    };
    const std::vector<uint32_t> expected_full_output_shape{
        1U,
        4U,
        static_cast<uint32_t>(height / 8),
        static_cast<uint32_t>(width / 8),
    };
    const std::vector<uint32_t> expected_graph_input_shape = request_ultra_fix
        ? std::vector<uint32_t>{1U, 3U, 512U, 512U}
        : expected_full_input_shape;
    const std::vector<uint32_t> expected_graph_output_shape = request_ultra_fix
        ? std::vector<uint32_t>{1U, 4U, 64U, 64U}
        : expected_full_output_shape;
    const uint64_t expected_input_elements =
        static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 3U;
    const uint64_t expected_input_bytes = expected_input_elements * sizeof(float);
    const auto valid_sha256 = [](const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](unsigned char digit) {
                return (digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f');
            });
    };
    if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0 ||
        input_image_path.empty() || input_tensor_path.empty() ||
        !valid_sha256(input_image_sha256) || !valid_sha256(expected_tensor_sha256) ||
        declared_tensor_shape != expected_full_input_shape ||
        long_field(params_json, "inputImageSizeBytes") <= 0LL ||
        long_field(params_json, "inputImageTensorBytes") !=
            static_cast<long long>(expected_input_bytes) ||
        string_field(params_json, "inputImageTensorDtype") != "float32-le" ||
        string_field(params_json, "inputImageTensorLayout") != "NCHW" ||
        string_field(params_json, "inputImageTensorRange") != "NEGATIVE_ONE_TO_ONE" ||
        string_field(params_json, "inputImagePreprocess") !=
            "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1") {
        *failure_code = "INPUT_TENSOR_CONTRACT_INVALID";
        *error = "Shared QNN img2img requires the exact bounded RGB NCHW float32 preprocessing contract.";
        return false;
    }
    if (request_inpaint &&
        (requested_inpaint_topology != "latent_blend_4" &&
         requested_inpaint_topology != "concatenated_latent_9" &&
         requested_inpaint_topology != "separate_mask_input")) {
        *failure_code = "INPAINT_TOPOLOGY_CONTRACT_INVALID";
        *error = "Shared QNN inpaint requires one explicit topology derived from the loaded profile graph.";
        return false;
    }
    if (request_inpaint &&
        (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
         string_field(params_json, "inpaintMaskConvention") !=
            "white_repaint_black_preserve")) {
        *failure_code = "INPAINT_ARTIFACT_CONTRACT_INVALID";
        *error = "Shared QNN inpaint requires the versioned grayscale-mask preprocessing contract.";
        return false;
    }
    if (request_masked_image_latent &&
        (masked_input_tensor_path.empty() ||
         !valid_sha256(expected_masked_input_tensor_sha256) ||
         declared_masked_input_shape != expected_full_input_shape ||
         long_field(params_json, "maskedInputImageTensorBytes") !=
            static_cast<long long>(expected_input_bytes) ||
         string_field(params_json, "maskedInputImageTensorDtype") != "float32-le" ||
         string_field(params_json, "maskedInputImageTensorLayout") != "NCHW" ||
         string_field(params_json, "maskedInputImageTensorRange") != "NEGATIVE_ONE_TO_ONE" ||
         string_field(params_json, "maskedInputImageTensorPreprocess") !=
            "source_aligned_grayscale_masked_rgb_nchw_negative_one_to_one_v2")) {
        *failure_code = "INPAINT_MASKED_INPUT_CONTRACT_INVALID";
        *error = "Shared QNN inpaint requires the exact aligned masked RGB NCHW float32 preprocessing contract.";
        return false;
    }
    if (request_inpaint && !request_masked_image_latent &&
        (!masked_input_tensor_path.empty() ||
         !expected_masked_input_tensor_sha256.empty() ||
         !declared_masked_input_shape.empty() ||
         long_field(params_json, "maskedInputImageTensorBytes") != 0LL)) {
        *failure_code = "INPAINT_MASKED_INPUT_CONTRACT_INVALID";
        *error = "Only a concatenated nine-channel inpaint UNet may request a masked-image VAE latent.";
        return false;
    }

    std::vector<float> input_values;
    std::string actual_tensor_sha256;
    if (!read_float_binary_file(
            input_tensor_path,
            &input_values,
            error,
            &actual_tensor_sha256) ||
        actual_tensor_sha256 != expected_tensor_sha256 ||
        input_values.size() != static_cast<size_t>(expected_input_elements) ||
        !std::all_of(input_values.begin(), input_values.end(), [](float value) {
            return std::isfinite(value) && value >= -1.000001f && value <= 1.000001f;
        })) {
        *failure_code = "INPUT_TENSOR_IDENTITY_MISMATCH";
        if (error->empty()) {
            *error = "Prepared shared QNN encoder tensor changed or violates its declared range.";
        }
        return false;
    }
    std::vector<float> masked_input_values;
    if (request_masked_image_latent) {
        std::string actual_masked_tensor_sha256;
        if (!read_float_binary_file(
                masked_input_tensor_path,
                &masked_input_values,
                error,
                &actual_masked_tensor_sha256) ||
            actual_masked_tensor_sha256 != expected_masked_input_tensor_sha256 ||
            masked_input_values.size() != static_cast<size_t>(expected_input_elements) ||
            !std::all_of(masked_input_values.begin(), masked_input_values.end(), [](float value) {
                return std::isfinite(value) && value >= -1.000001f && value <= 1.000001f;
            })) {
            *failure_code = "INPAINT_MASKED_INPUT_IDENTITY_MISMATCH";
            if (error->empty()) {
                *error = "Prepared shared QNN masked RGB tensor changed or violates its declared range.";
            }
            return false;
        }
        evidence->masked_input_tensor_sha256 = actual_masked_tensor_sha256;
    }

    const std::string encoder_binary = string_field(params_json, "vaeEncoderContextBinary");
    const std::string encoder_graph_name = string_field(params_json, "vaeEncoderGraphName").empty()
        ? "model"
        : string_field(params_json, "vaeEncoderGraphName");
    const std::string expected_context_sha256 = normalized_contract_enum(
        string_field(params_json, "vaeEncoderContextSha256"));
    if (!is_safe_bundle_relative_path(encoder_binary) || encoder_graph_name.empty() ||
        !valid_sha256(expected_context_sha256)) {
        *failure_code = "ENCODER_CONTEXT_IDENTITY_INVALID";
        *error = "Shared QNN VAE encoder path, graph name, or context SHA-256 is invalid.";
        return false;
    }
    const std::string encoder_path = join_path(bundle_root, encoder_binary);
    if (!exists_file(encoder_path)) {
        *failure_code = "REQUIRED_GRAPH_MISSING";
        *error = "Shared QNN VAE encoder context is missing.";
        return false;
    }
    if (generation != nullptr && generation->cancelled()) {
        *failure_code = "CANCELLED";
        *error = "Image generation was cancelled before VAE encoding.";
        return false;
    }

    QnnExecutableGraph encoder;
    if (!encoder.load(
            runtime,
            encoder_path,
            encoder_graph_name,
            generation,
            false,
            true,
            expected_context_sha256,
            0)) {
        *failure_code = encoder.message.find("Provider-bound SHA-256") != std::string::npos
            ? "ENCODER_CONTEXT_IDENTITY_MISMATCH"
            : "ENCODER_LOAD_FAILED";
        *error = encoder.message;
        std::string release_error;
        if (!encoder.close_checked(&release_error)) {
            const std::string primary_error = *error;
            *failure_code = "ENCODER_RELEASE_FAILED";
            *error = release_error.empty()
                ? "Shared QNN VAE encoder resources did not release cleanly after load failure."
                : release_error;
            if (!primary_error.empty()) {
                *error = primary_error + " Cleanup also failed: " + *error;
            }
        }
        return false;
    }
    const auto fail_after_encoder_load = [&]() -> bool {
        std::string release_error;
        if (!encoder.close_checked(&release_error)) {
            const std::string primary_error = *error;
            *failure_code = "ENCODER_RELEASE_FAILED";
            *error = release_error.empty()
                ? "Shared QNN VAE encoder resources did not release cleanly."
                : release_error;
            if (!primary_error.empty()) {
                *error = primary_error + " Cleanup also failed: " + *error;
            }
        }
        return false;
    };
    if (encoder.graph_name != encoder_graph_name ||
        encoder.context_binary_sha256 != expected_context_sha256) {
        *failure_code = "ENCODER_CONTEXT_IDENTITY_MISMATCH";
        *error = "Loaded shared QNN encoder graph or mapped context identity differs from the request.";
        return fail_after_encoder_load();
    }

    auto exact_tensor_index = [](const std::vector<QnnTensorBinding>& tensors,
                                 const std::string& name) -> int {
        for (size_t index = 0; index < tensors.size(); ++index) {
            if (tensors[index].name == name) return static_cast<int>(index);
        }
        return -1;
    };
    const int input_index = exact_tensor_index(encoder.inputs, "input");
    const int mean_index = exact_tensor_index(encoder.outputs, "mean");
    const int std_index = exact_tensor_index(encoder.outputs, "std");
    if (encoder.inputs.size() != 1U || encoder.outputs.size() != 2U ||
        input_index != 0 || mean_index < 0 || std_index < 0 || mean_index == std_index ||
        encoder.inputs[0].dimensions != expected_graph_input_shape ||
        encoder.outputs[static_cast<size_t>(mean_index)].dimensions != expected_graph_output_shape ||
        encoder.outputs[static_cast<size_t>(std_index)].dimensions != expected_graph_output_shape ||
        qnn_smoke_storage_data_type(qnn_tensor_data_type(encoder.inputs[0].tensor)) != "uint16" ||
        qnn_smoke_storage_data_type(qnn_tensor_data_type(
            encoder.outputs[static_cast<size_t>(mean_index)].tensor)) != "uint16" ||
        qnn_smoke_storage_data_type(qnn_tensor_data_type(
            encoder.outputs[static_cast<size_t>(std_index)].tensor)) != "uint16" ||
        !qnn_has_supported_scale_offset_quantization(encoder.inputs[0].tensor) ||
        !qnn_has_supported_scale_offset_quantization(
            encoder.outputs[static_cast<size_t>(mean_index)].tensor) ||
        !qnn_has_supported_scale_offset_quantization(
            encoder.outputs[static_cast<size_t>(std_index)].tensor)) {
        *failure_code = "ENCODER_GRAPH_CONTRACT_INVALID";
        *error = "Shared QNN VAE encoder must expose exact uint16 input/mean/std tensors with finite positive per-tensor scale-offset quantization for the requested image size.";
        return fail_after_encoder_load();
    }
    const auto& mean_binding = encoder.outputs[static_cast<size_t>(mean_index)];
    const auto& std_binding = encoder.outputs[static_cast<size_t>(std_index)];
    evidence->encoder_input_dimensions = encoder.inputs[0].dimensions;
    evidence->encoder_output_dimensions = mean_binding.dimensions;
    if (request_ultra_fix) {
        if (request_inpaint || ultra_fix->target_width != width ||
            ultra_fix->target_height != height ||
            !mca::image::build_ultrafix_tile_plan(
                encoder.inputs[0].dimensions,
                mean_binding.dimensions,
                {1U, 4U, 64U, 64U},
                {1U, 4U, 64U, 64U},
                {1U, 4U, 64U, 64U},
                {1U, 3U, 512U, 512U},
                static_cast<size_t>(width),
                static_cast<size_t>(height),
                static_cast<size_t>(ultra_fix->tile_size),
                ultra_fix->overlap,
                8U,
                &evidence->ultra_fix_plan,
                error)) {
            *failure_code = "ULTRAFIX_TILE_PLAN_INVALID";
            if (error->empty()) {
                *error = "Shared QNN UltraFix encoder topology does not match its target tile plan.";
            }
            return fail_after_encoder_load();
        }
        evidence->ultra_fix = true;
    }
    const size_t expected_latent_elements = static_cast<size_t>(
        static_cast<uint64_t>(width / 8) * static_cast<uint64_t>(height / 8) * 4U);
    constexpr double kSd15EncoderLatentScalingFactor = 0.18215;
    if (execution_contract.vae_scaling_location != QnnVaeScalingLocation::HostBeforeGraph ||
        !std::isfinite(execution_contract.vae_scaling_factor) ||
        std::fabs(execution_contract.vae_scaling_factor -
            kSd15EncoderLatentScalingFactor) > 1.0e-9) {
        *failure_code = "ENCODER_SCALING_CONTRACT_INVALID";
        *error = "Shared QNN SD1.x img2img requires host VAE scaling factor 0.18215.";
        return fail_after_encoder_load();
    }
    std::seed_seq posterior_seed{
        execution_contract.seed,
        0x53443135U,
        0x454e4331U,
    };
    std::mt19937 posterior_rng(posterior_seed);
    std::normal_distribution<float> posterior_normal(0.0f, 1.0f);
    const auto encode_once = [&](const std::vector<float>& values,
                                 bool masked_input,
                                 std::vector<float>* latents,
                                 std::string* input_buffer_sha256,
                                 std::string* mean_buffer_sha256,
                                 std::string* std_buffer_sha256,
                                 std::string* latent_sha256,
                                 long long* execute_ms) -> bool {
        const std::string label = masked_input ? "masked-image" : "source-image";
        const auto set_failure = [&](const char* source_code, const char* masked_code) {
            *failure_code = masked_input ? masked_code : source_code;
        };
        if (generation != nullptr && generation->cancelled()) {
            *failure_code = "CANCELLED";
            *error = "Image generation was cancelled before the " + label + " VAE encode.";
            return false;
        }
        std::vector<float> mean;
        std::vector<float> stddev;
        if (request_ultra_fix && !masked_input) {
            const mca::image::SpatialTensorShape source_shape{
                1U,
                3U,
                static_cast<size_t>(height),
                static_cast<size_t>(width),
                mca::image::SpatialTensorLayout::Nchw,
            };
            std::vector<std::vector<float>> mean_tiles;
            std::vector<std::vector<float>> std_tiles;
            mean_tiles.reserve(evidence->ultra_fix_plan.tiles.size());
            std_tiles.reserve(evidence->ultra_fix_plan.tiles.size());
            std::string input_proofs;
            std::string mean_proofs;
            std::string std_proofs;
            *execute_ms = 0;
            for (const auto& tile : evidence->ultra_fix_plan.tiles) {
                if (generation != nullptr && generation->cancelled()) {
                    *failure_code = "CANCELLED";
                    *error = "Image generation was cancelled during tiled UltraFix VAE encoding.";
                    return false;
                }
                std::vector<float> tile_input;
                if (!mca::image::copy_spatial_tile(
                        values,
                        source_shape,
                        tile.pixel_x,
                        tile.pixel_y,
                        evidence->ultra_fix_plan.encoder_input,
                        &tile_input,
                        error) ||
                    !qnn_write_float_tensor(
                        &encoder.inputs[0], tile_input.data(), tile_input.size(), error)) {
                    *failure_code = "ULTRAFIX_ENCODER_INPUT_BIND_FAILED";
                    return false;
                }
                const std::string input_proof =
                    mca::qnn::controlnet::sha256_hex_bytes(
                        encoder.inputs[0].buffer.data(), encoder.inputs[0].buffer.size());
                if (!valid_sha256(input_proof)) {
                    *failure_code = "ULTRAFIX_ENCODER_INPUT_EVIDENCE_INVALID";
                    *error = "An UltraFix encoder tile lacks a SHA-256 input proof.";
                    return false;
                }
                long long tile_execute_ms = 0;
                if (generation != nullptr && generation->cancelled()) {
                    *failure_code = "CANCELLED";
                    *error = "Image generation was cancelled before an UltraFix encoder tile execution.";
                    return false;
                }
                if (!encoder.execute(&tile_execute_ms, error)) {
                    *failure_code = "ULTRAFIX_ENCODER_EXECUTION_FAILED";
                    return false;
                }
                *execute_ms += tile_execute_ms;
                if (generation != nullptr && generation->cancelled()) {
                    *failure_code = "CANCELLED";
                    *error = "Image generation was cancelled after an UltraFix encoder tile execution.";
                    return false;
                }
                const std::string mean_proof =
                    mca::qnn::controlnet::sha256_hex_bytes(
                        mean_binding.buffer.data(), mean_binding.buffer.size());
                const std::string std_proof =
                    mca::qnn::controlnet::sha256_hex_bytes(
                        std_binding.buffer.data(), std_binding.buffer.size());
                std::vector<float> tile_mean;
                std::vector<float> tile_std;
                if (!valid_sha256(mean_proof) || !valid_sha256(std_proof) ||
                    !qnn_read_float_tensor(mean_binding, &tile_mean, error) ||
                    !qnn_read_float_tensor(std_binding, &tile_std, error) ||
                    tile_mean.size() != evidence->ultra_fix_plan.encoder_output.element_count() ||
                    tile_std.size() != evidence->ultra_fix_plan.encoder_output.element_count()) {
                    *failure_code = "ULTRAFIX_ENCODER_OUTPUT_INVALID";
                    if (error->empty()) {
                        *error = "An UltraFix encoder tile returned invalid mean/std tensors.";
                    }
                    return false;
                }
                input_proofs += input_proof;
                input_proofs.push_back('|');
                mean_proofs += mean_proof;
                mean_proofs.push_back('|');
                std_proofs += std_proof;
                std_proofs.push_back('|');
                mean_tiles.push_back(std::move(tile_mean));
                std_tiles.push_back(std::move(tile_std));
            }
            if (generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled before UltraFix encoder tile blending.";
                return false;
            }
            if (!mca::image::blend_ultrafix_encoder_tiles(
                    evidence->ultra_fix_plan, mean_tiles, &mean, error) ||
                !mca::image::blend_ultrafix_encoder_tiles(
                    evidence->ultra_fix_plan, std_tiles, &stddev, error)) {
                *failure_code = "ULTRAFIX_ENCODER_TILE_BLEND_FAILED";
                return false;
            }
            const auto digest_text = [](const std::string& proofs) {
                return mca::qnn::controlnet::sha256_hex_bytes(
                    reinterpret_cast<const uint8_t*>(proofs.data()), proofs.size());
            };
            *input_buffer_sha256 = digest_text(input_proofs);
            *mean_buffer_sha256 = digest_text(mean_proofs);
            *std_buffer_sha256 = digest_text(std_proofs);
        } else {
            if (!qnn_write_float_tensor(
                    &encoder.inputs[0], values.data(), values.size(), error)) {
                set_failure("ENCODER_INPUT_BIND_FAILED", "INPAINT_MASKED_ENCODER_INPUT_BIND_FAILED");
                return false;
            }
            *input_buffer_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                encoder.inputs[0].buffer.data(), encoder.inputs[0].buffer.size());
            if (!valid_sha256(*input_buffer_sha256)) {
                set_failure("ENCODER_INPUT_EVIDENCE_INVALID", "INPAINT_MASKED_ENCODER_INPUT_EVIDENCE_INVALID");
                *error = "Shared QNN " + label + " encoder input lacks a SHA-256 execution proof.";
                return false;
            }
            if (generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled before the " + label + " VAE graph execution.";
                return false;
            }
            if (!encoder.execute(execute_ms, error)) {
                set_failure("ENCODER_EXECUTION_FAILED", "INPAINT_MASKED_ENCODER_EXECUTION_FAILED");
                return false;
            }
            if (generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled after the " + label + " VAE graph execution.";
                return false;
            }
            *mean_buffer_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                mean_binding.buffer.data(), mean_binding.buffer.size());
            *std_buffer_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                std_binding.buffer.data(), std_binding.buffer.size());
            if (!valid_sha256(*mean_buffer_sha256) || !valid_sha256(*std_buffer_sha256) ||
                !qnn_read_float_tensor(mean_binding, &mean, error) ||
                !qnn_read_float_tensor(std_binding, &stddev, error)) {
                set_failure("ENCODER_OUTPUT_INVALID", "INPAINT_MASKED_ENCODER_OUTPUT_INVALID");
                if (error->empty()) {
                    *error = "Shared QNN " + label + " encoder mean/std outputs are invalid.";
                }
                return false;
            }
        }
        if (!valid_sha256(*input_buffer_sha256) ||
            !valid_sha256(*mean_buffer_sha256) || !valid_sha256(*std_buffer_sha256) ||
            mean.size() != expected_latent_elements || stddev.size() != expected_latent_elements ||
            !std::all_of(mean.begin(), mean.end(), [](float value) { return std::isfinite(value); }) ||
            !std::all_of(stddev.begin(), stddev.end(), [](float value) { return std::isfinite(value); })) {
            set_failure("ENCODER_OUTPUT_INVALID", "INPAINT_MASKED_ENCODER_OUTPUT_INVALID");
            if (error->empty()) *error = "Shared QNN " + label + " encoder mean/std outputs are invalid.";
            return false;
        }
        latents->assign(expected_latent_elements, 0.0f);
        for (size_t index = 0; index < latents->size(); ++index) {
            if ((index % 4096U) == 0U && generation != nullptr && generation->cancelled()) {
                *failure_code = "CANCELLED";
                *error = "Image generation was cancelled during " + label + " posterior sampling.";
                return false;
            }
            (*latents)[index] = static_cast<float>(
                (mean[index] + stddev[index] * posterior_normal(posterior_rng)) *
                kSd15EncoderLatentScalingFactor);
        }
        if (!std::all_of(latents->begin(), latents->end(), [](float value) {
                return std::isfinite(value);
            })) {
            set_failure("ENCODER_POSTERIOR_INVALID", "INPAINT_MASKED_ENCODER_POSTERIOR_INVALID");
            *error = "Shared QNN " + label + " encoder posterior contains non-finite values.";
            return false;
        }
        *latent_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
            reinterpret_cast<const uint8_t*>(latents->data()),
            latents->size() * sizeof(float));
        if (!valid_sha256(*latent_sha256)) {
            set_failure("ENCODER_POSTERIOR_INVALID", "INPAINT_MASKED_ENCODER_POSTERIOR_INVALID");
            *error = "Shared QNN " + label + " encoder posterior lacks a SHA-256 proof.";
            return false;
        }
        return true;
    };
    if (!encode_once(
            input_values,
            false,
            &evidence->latents,
            &evidence->input_buffer_sha256,
            &evidence->mean_buffer_sha256,
            &evidence->std_buffer_sha256,
            &evidence->latent_sha256,
            &evidence->execute_ms)) {
        return fail_after_encoder_load();
    }
    if (request_inpaint) evidence->source_pixels_nchw = input_values;
    evidence->execution_count = request_ultra_fix
        ? evidence->ultra_fix_plan.tiles.size()
        : 1U;
    if (request_masked_image_latent) {
        if (!encode_once(
                masked_input_values,
                true,
                &evidence->masked_latents,
                &evidence->masked_input_buffer_sha256,
                &evidence->masked_mean_buffer_sha256,
                &evidence->masked_std_buffer_sha256,
                &evidence->masked_latent_sha256,
                &evidence->masked_execute_ms)) {
            return fail_after_encoder_load();
        }
        evidence->execution_count = 2U;
        evidence->masked_execution_count = 1U;
    }

    evidence->context_sha256 = encoder.context_binary_sha256;
    evidence->graph_name = encoder.graph_name;
    evidence->input_name = encoder.inputs[0].name;
    evidence->mean_name = mean_binding.name;
    evidence->std_name = std_binding.name;
    evidence->input_dtype = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(encoder.inputs[0].tensor));
    evidence->mean_dtype = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(mean_binding.tensor));
    evidence->std_dtype = qnn_smoke_storage_data_type(
        qnn_tensor_data_type(std_binding.tensor));
    evidence->context_load_ms = encoder.context_load_ms;
    std::string release_error;
    if (!encoder.close_checked(&release_error)) {
        *failure_code = "ENCODER_RELEASE_FAILED";
        *error = release_error.empty()
            ? "Shared QNN VAE encoder resources did not release cleanly."
            : release_error;
        return false;
    }
    evidence->context_released_before_shared_session = true;
    if (generation != nullptr && generation->cancelled()) {
        *failure_code = "CANCELLED";
        *error = "Image generation was cancelled after releasing the VAE encoder context.";
        return false;
    }
    failure_code->clear();
    error->clear();
    return true;
}

std::string qnn_semantic_generate_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& output_path) {
    // A failed native release intentionally poisons this disposable worker.
    // Check before probing or dlopening any QNN runtime so a later request
    // cannot touch a library that may still own a live context.
    if (g_qnn_runtime_poisoned.load()) {
        return qnn_semantic_failure_json(
            "runtime_resource_release_failed",
            "NATIVE_RESOURCE_RELEASE_FAILED",
            "This disposable QNN image worker rejected reuse after a native resource release failure.");
    }
    const auto started = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false);
    if (g_qnn_runtime_poisoned.load()) {
        return qnn_semantic_failure_json(
            "runtime_resource_release_failed",
            "NATIVE_RESOURCE_RELEASE_FAILED",
            runtime.message.empty()
                ? "QNN runtime preflight resources did not release cleanly."
                : runtime.message);
    }
    const auto bundle = inspect_bundle_internal(bundle_root);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    if (!bundle.root_present) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"bundle_missing\",\"message\":\"QNN bundle root is missing.\"}";
    }

    const std::string conditioning_format = string_field(params_json, "conditioningFormat");
    if (conditioning_format.empty()) {
        return qnn_semantic_failure_json(
            "execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            "conditioningFormat must be explicit for QNN semantic generation.");
    }
    const std::string normalized_conditioning_format =
        normalized_contract_enum(conditioning_format);
    const bool qnn_weighted_token_conditioning =
        normalized_conditioning_format == "qnn_clip_token_ids_weights_v1";
    const bool qnn_token_conditioning =
        normalized_conditioning_format == "qnn_clip_token_ids_i32" ||
        qnn_weighted_token_conditioning;
    std::string error;
    QnnSemanticExecutionContract execution_contract;
    if (!parse_qnn_semantic_execution_contract(
            params_json,
            &execution_contract,
            &error)) {
        return qnn_semantic_failure_json(
            "execution_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    QnnUltraFixRequest ultra_fix_request;
    if (!parse_qnn_ultrafix_request(params_json, &ultra_fix_request, &error)) {
        return qnn_semantic_failure_json(
            "ultrafix_contract_invalid",
            "QNN_ULTRAFIX_CONTRACT_INVALID",
            error);
    }
    QnnPreviewRequest preview_request;
    if (!parse_qnn_preview_request(params_json, &preview_request, &error)) {
        return qnn_semantic_failure_json(
            "preview_contract_invalid",
            "INVALID_PREVIEW_CONTRACT",
            error);
    }
    std::string task_mode = normalized_contract_enum(string_field(params_json, "taskMode"));
    if (task_mode.empty()) task_mode = "text_to_image";
    const bool request_controlnet = task_mode == "control";
    const bool request_img2img = task_mode == "img2img";
    const bool request_inpaint = task_mode == "inpaint";
    const std::string requested_inpaint_topology = normalized_contract_enum(
        string_field(params_json, "inpaintRequestedTopology"));
    if (task_mode != "text_to_image" && !request_controlnet && !request_img2img &&
        !request_inpaint) {
        return qnn_semantic_failure_json(
            "task_mode_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            "QNN semantic generation supports text_to_image, control, encoder-backed img2img, or topology-checked inpaint for this execution path.");
    }
    const std::string worker_strategy = normalized_contract_enum(
        string_field(params_json, "workerStrategy"));
    if (ultra_fix_request.enabled &&
        (!request_img2img ||
         (worker_strategy != "shared_unet_vae" &&
          worker_strategy != "shared_text_unet_vae") ||
         execution_contract.width != ultra_fix_request.target_width ||
         execution_contract.height != ultra_fix_request.target_height ||
         execution_contract.scheduler.steps != ultra_fix_request.refinement_steps ||
         execution_contract.scheduler.config.prediction_type !=
            mca::diffusion::PredictionType::Epsilon ||
         execution_contract.scheduler.config.algorithm ==
            mca::diffusion::SchedulerAlgorithm::Pndm ||
         preview_request.requested ||
         long_field(params_json, "batchCount") != 1LL)) {
        return qnn_semantic_failure_json(
            "ultrafix_topology_unsupported",
            "QNN_ULTRAFIX_TOPOLOGY_UNSUPPORTED",
            "QNN UltraFix requires one shared_unet_vae img2img output, epsilon prediction, a reversible scheduler, exact target controls, and no live preview.");
    }
    if ((request_img2img || request_inpaint) && worker_strategy != "shared_unet_vae" &&
        worker_strategy != "shared_text_unet_vae") {
        return qnn_semantic_failure_json(
            request_inpaint ? "inpaint_topology_unsupported" : "img2img_topology_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            request_inpaint
                ? "QNN inpaint requires a concrete shared coherent UNet/VAE topology with a VAE encoder; a regular four-channel UNet uses per-step latent blending."
                : "QNN img2img requires a concrete shared coherent UNet/VAE topology with a VAE encoder.");
    }
    const std::string input_image_path = string_field(params_json, "inputImagePath");
    const std::string mask_image_path = string_field(params_json, "maskImagePath");
    const std::string mask_image_sha256 = normalized_contract_enum(
        string_field(params_json, "maskImageSha256"));
    const auto valid_inpaint_sha256 = [](const std::string& value) {
        return value.size() == 64U && std::all_of(
            value.begin(), value.end(), [](unsigned char digit) {
                return (digit >= '0' && digit <= '9') ||
                    (digit >= 'a' && digit <= 'f');
            });
    };
    const std::string control_image_path = string_field(params_json, "controlImagePath");
    const std::string control_image_sha256 = string_field(params_json, "controlImageSha256");
    const bool control_strength_specified =
        params_json.find("\"controlStrength\"") != std::string::npos;
    const bool control_preprocess_specified =
        params_json.find("\"controlImagePreprocess\"") != std::string::npos;
    const double control_strength = double_field(params_json, "controlStrength", 1.0);
    const std::string control_image_preprocess_value =
        string_field(params_json, "controlImagePreprocess");
    mca::qnn::controlnet::ControlImagePreprocessMode control_image_preprocess =
        mca::qnn::controlnet::ControlImagePreprocessMode::Canny;
    if (!mca::qnn::controlnet::parse_control_image_preprocess_mode(
            control_image_preprocess_value,
            &control_image_preprocess,
            &error)) {
        return qnn_semantic_failure_json(
            "control_image_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    if (!std::isfinite(control_strength) || control_strength < 0.0 || control_strength > 2.0) {
        return qnn_semantic_failure_json(
            "control_strength_invalid",
            "EXECUTION_CONTRACT_INVALID",
            "controlStrength must be finite and in [0, 2].");
    }
    const long long batch_count = long_field(params_json, "batchCount");
    if (batch_count != 0 && batch_count != 1) {
        return qnn_semantic_failure_json(
            "batch_count_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            "QNN semantic generation currently executes exactly one output per native request.");
    }
    if (request_controlnet) {
        if (!input_image_path.empty() || !mask_image_path.empty() ||
            control_image_path.empty() || control_image_sha256.empty()) {
            return qnn_semantic_failure_json(
                "control_image_contract_invalid",
                "EXECUTION_CONTRACT_INVALID",
                "taskMode=control requires only controlImagePath and controlImageSha256.");
        }
    } else if (request_img2img) {
        if (input_image_path.empty() || !mask_image_path.empty() ||
            !control_image_path.empty() || !control_image_sha256.empty() ||
            control_strength_specified || control_preprocess_specified) {
            return qnn_semantic_failure_json(
                "img2img_input_contract_invalid",
                "EXECUTION_CONTRACT_INVALID",
                "taskMode=img2img requires exactly one input image and no mask or ControlNet input.");
        }
    } else if (request_inpaint) {
        if (input_image_path.empty() || mask_image_path.empty() ||
            !valid_inpaint_sha256(mask_image_sha256) ||
            !control_image_path.empty() || !control_image_sha256.empty() ||
            control_strength_specified || control_preprocess_specified) {
            return qnn_semantic_failure_json(
                "inpaint_input_contract_invalid",
                "EXECUTION_CONTRACT_INVALID",
                "taskMode=inpaint requires inputImagePath, maskImagePath, and maskImageSha256, with no ControlNet input.");
        }
    } else if (!input_image_path.empty() || !mask_image_path.empty() ||
               !control_image_path.empty() || !control_image_sha256.empty() ||
               control_strength_specified || control_preprocess_specified) {
        return qnn_semantic_failure_json(
            "text_image_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            "taskMode=text_to_image cannot carry image input paths or hashes.");
    }
    const std::string family = normalized_contract_enum(string_field(params_json, "family"));
    const bool request_sdxl =
        family == "sdxl" || contains_lower(conditioning_format, "sdxl");
    if ((request_img2img || request_inpaint) && request_sdxl) {
        return qnn_semantic_failure_json(
            request_inpaint ? "shared_sdxl_inpaint_unsupported" : "shared_sdxl_img2img_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            request_inpaint
                ? "SDXL inpaint requires an isolated split-worker graph with an inspected mask-conditioned UNet; this shared path does not provide it."
                : "SDXL img2img requires the isolated split-worker encoder/UNet/VAE chain.");
    }
    const mca::qnn::preview::Contract preview_contract =
        mca::qnn::preview::resolve_contract(
            preview_request.requested,
            normalized_contract_enum(string_field(params_json, "workerStrategy")),
            normalized_contract_enum(preview_request.mode),
            preview_request.interval);
    if (!preview_contract.error_code.empty()) {
        return qnn_semantic_failure_json(
            "preview_contract_invalid",
            preview_contract.error_code,
            preview_contract.message);
    }
    if (request_controlnet && request_sdxl) {
        return qnn_semantic_failure_json(
            "controlnet_family_unsupported",
            "TASK_MODE_EXECUTION_UNSUPPORTED",
            "This QNN ControlNet execution path requires the SD1.x four-graph contract.");
    }
    mca::diffusion::DiffusionScheduler scheduler(execution_contract.scheduler.config);
    if (!scheduler.set_timesteps(execution_contract.scheduler.steps, &error)) {
        return qnn_semantic_failure_json(
            "scheduler_contract_invalid",
            "EXECUTION_CONTRACT_INVALID",
            error);
    }
    const size_t full_timetable_count = scheduler.timesteps().size();
    const bool request_encoder_conditioned = request_img2img || request_inpaint;
    const double img2img_strength = request_encoder_conditioned
        ? double_field(params_json, "strength", 1.0)
        : 1.0;
    mca::diffusion::Img2ImgTailSchedule img2img_schedule;
    if (request_encoder_conditioned && !mca::diffusion::resolve_img2img_tail_schedule(
            execution_contract.scheduler.steps,
            img2img_strength,
            &img2img_schedule,
            &error)) {
        return qnn_semantic_failure_json(
            "img2img_strength_invalid",
            "IMG2IMG_STRENGTH_INVALID",
            error);
    }
    const size_t img2img_begin_index = request_encoder_conditioned ? img2img_schedule.begin_index : 0U;
    const size_t effective_timetable_count = request_encoder_conditioned
        ? img2img_schedule.effective_step_count
        : full_timetable_count;
    if (ultra_fix_request.enabled &&
        (std::fabs(img2img_strength - ultra_fix_request.strength) > 1.0e-12 ||
         full_timetable_count != static_cast<size_t>(ultra_fix_request.refinement_steps) ||
         effective_timetable_count !=
            static_cast<size_t>(ultra_fix_request.inversion_steps))) {
        return qnn_semantic_failure_json(
            "ultrafix_schedule_mismatch",
            "QNN_ULTRAFIX_SCHEDULE_MISMATCH",
            "QNN UltraFix strength, inversion steps, and refinement timetable do not describe one exact denoising tail.");
    }
    if (request_encoder_conditioned && !scheduler.set_begin_index(img2img_begin_index, &error)) {
        return qnn_semantic_failure_json(
            "img2img_scheduler_unsupported",
            "IMG2IMG_SCHEDULER_UNSUPPORTED",
            error);
    }
    if ((request_encoder_conditioned &&
            full_timetable_count != static_cast<size_t>(execution_contract.scheduler.steps)) ||
        effective_timetable_count != execution_contract.scheduler.expected_timetable_count ||
        (request_encoder_conditioned &&
            (long_field(params_json, "fullTimetableCount") !=
                    static_cast<long long>(full_timetable_count) ||
             long_field(params_json, "effectiveDenoiseSteps") !=
                    static_cast<long long>(effective_timetable_count) ||
             long_field(params_json, "img2imgBeginIndex") !=
                    static_cast<long long>(img2img_begin_index)))) {
        return qnn_execution_contract_mismatch_json(
            "timetableCount",
            execution_contract.scheduler.expected_timetable_count,
            effective_timetable_count);
    }
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
    const std::string controlnet_binary =
        string_field(params_json, "controlNetContextBinary").empty()
            ? "controlnet.bin"
            : string_field(params_json, "controlNetContextBinary");
    const std::string encoder_binary = string_field(params_json, "vaeEncoderContextBinary");
    const std::string& graph_name = execution_contract.graph_name;
    const std::string requested_text_encoder_graph_name =
        string_field(params_json, "textEncoderGraphName");
    const std::string text_encoder_graph_name =
        requested_text_encoder_graph_name.empty()
            ? graph_name
            : requested_text_encoder_graph_name;
    const std::string controlnet_graph_name =
        string_field(params_json, "controlNetGraphName").empty()
            ? graph_name
            : string_field(params_json, "controlNetGraphName");
    const std::string unet_path = join_path(bundle_root, unet_binary);
    const std::string vae_path = join_path(bundle_root, vae_binary);
    const std::string text_encoder_path = join_path(bundle_root, text_encoder_binary);
    const std::string controlnet_path = join_path(bundle_root, controlnet_binary);
    const bool prompt_requires_verified_multilingual_encoder =
        qnn_prompt_contains_non_ascii(execution_contract.prompt) ||
        qnn_prompt_contains_non_ascii(execution_contract.negative_prompt);
    QnnTextEncoderAssetEvidence text_encoder_asset_evidence;
    if (!parse_qnn_text_encoder_asset_evidence(
            params_json,
            text_encoder_binary,
            requested_text_encoder_graph_name,
            prompt_requires_verified_multilingual_encoder,
            &text_encoder_asset_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "text_encoder_asset_evidence_invalid",
            "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
            error);
    }
    const std::string tokenizer_path =
        text_encoder_asset_evidence.multilingual_evidence_required
            ? join_path(bundle_root, text_encoder_asset_evidence.tokenizer_relative_path)
            : "";
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            execution_contract.prompt,
            execution_contract.negative_prompt)) {
        return qnn_semantic_failure_json(
            "unsupported_native_prompt_language",
            "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE",
            "Native multilingual image prompts must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax.");
    }
    if (prompt_requires_verified_multilingual_encoder &&
        (!text_encoder_asset_evidence.required ||
            !text_encoder_asset_evidence.multilingual_evidence_required ||
            text_encoder_asset_evidence.language_proof_sha256.empty() ||
            text_encoder_asset_evidence.graph_name.empty() ||
            text_encoder_asset_evidence.tokenizer_relative_path.empty() ||
            text_encoder_asset_evidence.prompt_to_encoder_closure_sha256.empty())) {
        return qnn_semantic_failure_json(
            "text_encoder_language_proof_required",
            "TEXT_ENCODER_LANGUAGE_PROOF_REQUIRED",
            "Non-ASCII image prompts require complete graph and tokenizer asset evidence plus a signed prompt-to-encoder closure.");
    }
    if (text_encoder_asset_evidence.required && !qnn_token_conditioning) {
        return qnn_semantic_failure_json(
            "text_encoder_asset_evidence_invalid",
            "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
            "Strict text encoder asset evidence requires QNN token conditioning.");
    }
    if (text_encoder_asset_evidence.required &&
        !capture_qnn_text_encoder_asset_evidence(
            text_encoder_path,
            &text_encoder_asset_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "text_encoder_asset_evidence_invalid",
            "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
            error);
    }
    if (text_encoder_asset_evidence.multilingual_evidence_required &&
        !capture_qnn_tokenizer_asset_evidence(
            bundle_root,
            tokenizer_path,
            &text_encoder_asset_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "tokenizer_asset_evidence_invalid",
            "TOKENIZER_ASSET_EVIDENCE_INVALID",
            error);
    }
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
    if (request_controlnet && !exists_file(controlnet_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"controlnet_context_missing\",\"errorCode\":\"REQUIRED_GRAPH_MISSING\",\"message\":") +
            quote("QNN control generation requires " + controlnet_binary +
                " in the bundle root.") + "}";
    }
    if ((request_img2img || request_inpaint) &&
        (!is_safe_bundle_relative_path(encoder_binary) ||
         !exists_file(join_path(bundle_root, encoder_binary)))) {
        return qnn_semantic_failure_json(
            "encoder_context_missing",
            "REQUIRED_GRAPH_MISSING",
            request_inpaint
                ? "QNN inpaint requires a concrete VAE encoder context inside the installed bundle."
                : "QNN img2img requires a concrete VAE encoder context inside the installed bundle.");
    }

    std::vector<float> embeddings;
    std::vector<int32_t> token_ids;
    std::vector<float> token_weights;
    std::string consumed_conditioning_artifact_sha256;
    QnnConditioningEvidence conditioning_evidence;
    if (qnn_token_conditioning) {
        const bool read_ok = qnn_weighted_token_conditioning
            ? read_qnn_clip_token_weight_payload(
                embeddings_path,
                &token_ids,
                &token_weights,
                execution_contract.use_cfg,
                &conditioning_evidence,
                &error,
                &consumed_conditioning_artifact_sha256)
            : read_int32_binary_file(
                embeddings_path,
                &token_ids,
                &error,
                &consumed_conditioning_artifact_sha256);
        if (!read_ok) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"token_read_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (!qnn_weighted_token_conditioning) {
            const std::vector<float> unity_weights(token_ids.size(), 1.0f);
            if (!derive_qnn_token_execution_weight_evidence(
                    token_ids,
                    unity_weights,
                    execution_contract.use_cfg,
                    &conditioning_evidence,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"token_evidence_invalid\",\"message\":") +
                    quote(error) + "}";
            }
        }
    } else {
        if (!read_float_binary_file(
                embeddings_path,
                &embeddings,
                &error,
                &consumed_conditioning_artifact_sha256)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"embedding_read_failed\",\"message\":") +
            quote(error) + "}";
        }
    }
    if (!resolve_qnn_conditioning_evidence(
            bundle_root,
            consumed_conditioning_artifact_sha256,
            conditioning_format,
            token_ids.size(),
            params_json,
            execution_contract,
            &text_encoder_asset_evidence,
            &conditioning_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "conditioning_contract_invalid",
            "CONDITIONING_EVIDENCE_INVALID",
            error);
    }

    const std::string progress_journal_path = string_field(params_json, "progressJournalPath");
    QnnImageGenerationScope generation(
        static_cast<int>(effective_timetable_count),
        progress_journal_path);
    if (!generation.active()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"generation_busy\",\"message\":\"Another QNN image generation is still finishing.\"}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    QnnSharedPreviewPublisher preview_publisher(preview_contract, progress_journal_path);
    // Preview storage is auxiliary. A request-scoped directory failure is
    // audited by the publisher and disables later preview attempts, but it
    // must not substitute for or prevent the independent final VAE decode.
    if (!preview_publisher.initialize(&error)) error.clear();

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

    QnnSharedImg2ImgEncoderEvidence img2img_encoder;
    if (request_img2img || request_inpaint) {
        std::string encoder_failure_code;
        if (!qnn_run_shared_img2img_encoder(
                runtime,
                bundle_root,
                params_json,
                execution_contract,
                ultra_fix_request.enabled ? &ultra_fix_request : nullptr,
                &generation,
                &img2img_encoder,
                &encoder_failure_code,
                &error)) {
            if (encoder_failure_code == "CANCELLED" || generation.cancelled()) {
                return qnn_image_generation_cancelled_json();
            }
            return qnn_semantic_failure_json(
                "shared_img2img_encoder_failed",
                encoder_failure_code.empty()
                    ? "ENCODER_EXECUTION_FAILED"
                    : encoder_failure_code,
                error);
        }
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

    mca::qnn::inpaint::Contract inpaint_contract;
    if (request_inpaint) {
        std::vector<mca::qnn::inpaint::TensorDescriptor> inpaint_inputs;
        inpaint_inputs.reserve(unet.inputs.size());
        for (const auto& tensor : unet.inputs) {
            inpaint_inputs.push_back({tensor.name, tensor.dimensions});
        }
        inpaint_contract = mca::qnn::inpaint::inspect(inpaint_inputs);
        if (!inpaint_contract.supported()) {
            return qnn_semantic_failure_json(
                "inpaint_topology_unsupported",
                "QNN_INPAINT_GRAPH_TOPOLOGY_UNSUPPORTED",
                "QNN inpaint was rejected because the loaded UNet does not expose an executable four-channel or explicit mask-conditioned topology: " +
                    inpaint_contract.reason + " Graph inputs=" + qnn_tensor_list_debug_json(unet.inputs));
        }
        if (requested_inpaint_topology != inpaint_contract.topology_name()) {
            return qnn_semantic_failure_json(
                "inpaint_topology_identity_mismatch",
                "QNN_INPAINT_TOPOLOGY_IDENTITY_MISMATCH",
                "The prepared inpaint topology differs from the UNet graph loaded by native execution.");
        }
    }

    long long text_encoder_context_load_ms = 0;
    long long text_encoder_execute_ms_total = 0;
    size_t text_encoder_execute_count = 0;
    uint64_t text_encoder_embedding_width = 0;
    uint64_t text_encoder_input_elements = 0;
    std::string loaded_text_encoder_graph;
    std::string text_encoder_input_tensor;
    std::string text_encoder_input_data_type;
    std::string text_encoder_inputs_debug = "[]";
    std::string text_encoder_outputs_debug = "[]";
    std::string conditioning_graph_sha256 =
        conditioning_evidence.conditioning_graph_sha256;
    std::string prompt_weighting_execution_mode = qnn_token_conditioning
        ? "token_ids_unweighted"
        : "external_float_conditioning";
    bool conditioning_artifact_consumed = !qnn_token_conditioning && !embeddings.empty();
    if (qnn_token_conditioning) {
        QnnExecutableGraph text_encoder;
        if (!text_encoder.load_in_session(
                &runtime_session,
                runtime,
                text_encoder_path,
                text_encoder_graph_name,
                false,
                false,
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.sha256
                    : "",
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.size_bytes
                    : 0,
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.device
                    : 0,
                text_encoder_asset_evidence.required
                    ? text_encoder_asset_evidence.inode
                    : 0)) {
            const std::string primary_error = text_encoder_asset_evidence.required
                ? "QNN text encoder graph could not be loaded from the verified bundle asset."
                : text_encoder.message;
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_load_failed\",\"message\":") +
                quote(primary_error) + "}";
        }
        text_encoder_context_load_ms = text_encoder.context_load_ms;
        loaded_text_encoder_graph = text_encoder.graph_name;
        if (text_encoder_asset_evidence.multilingual_evidence_required &&
            loaded_text_encoder_graph != text_encoder_asset_evidence.graph_name) {
            return qnn_semantic_failure_json(
                "text_encoder_graph_identity_invalid",
                "TEXT_ENCODER_GRAPH_IDENTITY_MISMATCH",
                "The QNN text encoder graph loaded from textEncoderContextBinary differs from textEncoderGraphName.");
        }
        if (text_encoder_asset_evidence.required) {
            conditioning_graph_sha256 = text_encoder_asset_evidence.sha256;
        } else if (!qnn_file_sha256(
                       text_encoder_path,
                       &conditioning_graph_sha256,
                       &error)) {
            return qnn_semantic_failure_json(
                "text_encoder_sha256_failed",
                "EXECUTION_EVIDENCE_INVALID",
                "QNN text encoder graph SHA-256 could not be derived: " + error);
        }
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
        const Qnn_DataType_t token_data_type = qnn_tensor_data_type(
            text_encoder.inputs[static_cast<size_t>(token_index)].tensor);
        const uint64_t embedding_elements = qnn_tensor_element_count(
            text_encoder.outputs[static_cast<size_t>(embedding_index)].tensor);
        text_encoder_input_elements = token_elements;
        text_encoder_input_tensor =
            text_encoder.inputs[static_cast<size_t>(token_index)].name;
        text_encoder_input_data_type = qnn_data_type_name(token_data_type);
        if (token_data_type != QNN_DATATYPE_INT_32) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_tensor_layout_unsupported\",\"message\":") +
                quote("QNN CLIP token conditioning requires an actual int32 token-id graph input. inputs=" +
                    text_encoder_inputs_debug) + "}";
        }
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

        if (qnn_weighted_token_conditioning) {
            size_t unsupported_weighted_token_count = 0;
            if (!mca::image::validate_clip_token_id_graph_prompt_weights(
                    token_weights,
                    &unsupported_weighted_token_count,
                    &error)) {
                std::ostringstream message;
                message << error << " Actual graph input=" << text_encoder_input_tensor
                        << ", dtype=" << text_encoder_input_data_type
                        << ", elements=" << text_encoder_input_elements << ".";
                return qnn_semantic_failure_json(
                    "prompt_weighting_execution_unsupported",
                    "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED",
                    message.str());
            }
            prompt_weighting_execution_mode = "token_ids_unity_weight_payload";
        }

        std::vector<float> negative_embeddings;
        std::vector<float> positive_embeddings;
        long long execute_ms = 0;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (text_encoder_asset_evidence.multilingual_evidence_required &&
            !revalidate_qnn_tokenizer_asset_evidence(
                bundle_root,
                tokenizer_path,
                text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "tokenizer_asset_handoff_revalidation_failed",
                "TOKENIZER_ASSET_EVIDENCE_INVALID",
                error);
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!consume_qnn_mnn_prompt_handoff(
                execution_contract,
                conditioning_evidence,
                &text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "mnn_prompt_handoff_invalid",
                "MNN_PROMPT_HANDOFF_INVALID",
                error);
        }
        if (text_encoder_asset_evidence.multilingual_evidence_required) {
            conditioning_evidence.tokenizer_binding_stage = "tokenizer_consumed";
        }
        if (execution_contract.use_cfg) {
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
            ++text_encoder_execute_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
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
        ++text_encoder_execute_count;
        if ((execution_contract.use_cfg &&
             negative_embeddings.size() != static_cast<size_t>(embedding_elements)) ||
            positive_embeddings.size() != static_cast<size_t>(embedding_elements)) {
            std::ostringstream message;
            message << "QNN text encoder returned an unexpected output size. Expected "
                    << embedding_elements << " per prompt, got "
                    << negative_embeddings.size() << " and "
                    << positive_embeddings.size() << ".";
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_output_shape_unsupported\",\"message\":") +
                quote(message.str()) + "}";
        }
        if (text_encoder_asset_evidence.required &&
            !revalidate_qnn_text_encoder_asset_evidence(
                text_encoder_path,
                text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "text_encoder_asset_revalidation_failed",
                "TEXT_ENCODER_ASSET_EVIDENCE_INVALID",
                error);
        }
        if (text_encoder_asset_evidence.multilingual_evidence_required &&
            !revalidate_qnn_tokenizer_asset_evidence(
                bundle_root,
                tokenizer_path,
                text_encoder_asset_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "tokenizer_asset_revalidation_failed",
                "TOKENIZER_ASSET_EVIDENCE_INVALID",
                error);
        }
        embeddings.reserve(
            (execution_contract.use_cfg ? negative_embeddings.size() : 0u) +
            positive_embeddings.size());
        if (execution_contract.use_cfg) {
            embeddings.insert(
                embeddings.end(),
                negative_embeddings.begin(),
                negative_embeddings.end());
        }
        embeddings.insert(
            embeddings.end(),
            positive_embeddings.begin(),
            positive_embeddings.end());
        conditioning_artifact_consumed =
            text_encoder_execute_count == (execution_contract.use_cfg ? 2U : 1U);
        std::string text_encoder_release_error;
        if (!text_encoder.close_checked(&text_encoder_release_error)) {
            return qnn_semantic_failure_json(
                "text_encoder_release_failed",
                "NATIVE_RESOURCE_RELEASE_FAILED",
                text_encoder_release_error.empty()
                    ? "QNN text encoder resources did not release cleanly."
                    : text_encoder_release_error);
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }

    QnnExecutableGraph controlnet;
    if (request_controlnet && !controlnet.load_in_session(
            &runtime_session,
            runtime,
            controlnet_path,
            controlnet_graph_name,
            false)) {
        const std::string primary_error = controlnet.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"controlnet_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

    QnnExecutableGraph vae;
    if (!vae.load_in_session(&runtime_session, runtime, vae_path, graph_name, true)) {
        const std::string primary_error = vae.message;
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(primary_error) + "}";
    }
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();

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

        mca::image::SpatialTensorShape latent_shape;
        mca::image::SpatialTensorShape unet_output_shape;
        if (!mca::image::resolve_spatial_tensor_shape(
                    unet.inputs[sample_index].dimensions,
                    4U,
                    &latent_shape,
                    &error) ||
            !mca::image::resolve_spatial_tensor_shape(
                    unet.outputs[0].dimensions,
                    4U,
                    &unet_output_shape,
                    &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"latent_layout_unsupported\",\"message\":") +
                quote(error) + "}";
        }
        if (latent_shape.height != unet_output_shape.height ||
            latent_shape.width != unet_output_shape.width ||
            latent_shape.width * 8U != static_cast<size_t>(execution_contract.width) ||
            latent_shape.height * 8U != static_cast<size_t>(execution_contract.height)) {
            return qnn_semantic_failure_json(
                "latent_resolution_mismatch",
                "EXECUTION_CONTRACT_MISMATCH",
                "Resolved SDXL width/height do not match the native UNet latent tensor at the required 8x VAE scale.");
        }
        const uint64_t latent_elements = latent_shape.element_count();
        QnnSdxlConditioningBuffers conditioning;
        const bool sdxl_conditioning_has_negative_branch =
            worker_strategy != "split_unet_vae" || execution_contract.use_cfg;
        if (!qnn_prepare_sdxl_conditioning(
                    unet,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    embeddings,
                    sdxl_conditioning_has_negative_branch,
                    &conditioning,
                    &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"conditioning_shape_unsupported\",\"message\":") +
                quote(error) + "}";
        }
        constexpr size_t hidden_count =
            mca::image::kSdxlClipTokenCount * mca::image::kSdxlHiddenWidth;
        constexpr size_t pooled_count = mca::image::kSdxlPooledWidth;
        constexpr size_t time_ids_count = mca::image::kSdxlTimeIdCount;

        const int steps = execution_contract.scheduler.steps;
        const auto& timesteps = scheduler.timesteps();
        const double effective_vae_host_scale =
            qnn_effective_vae_host_scale(execution_contract);
        generation.set_steps(static_cast<int>(timesteps.size()));
        generation.set_phase(kQnnImageSampling);
        std::mt19937 rng(execution_contract.seed);
        std::normal_distribution<float> normal(0.0f, 1.0f);
        std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
        const float initial_noise_scale = static_cast<float>(scheduler.init_noise_sigma());
        for (float& value : latents) value = normal(rng) * initial_noise_scale;

        std::vector<float> noise_uncond;
        std::vector<float> noise_cond;
        long long unet_execute_ms_total = 0;
        size_t unet_execution_count = 0;
        for (size_t step = 0; step < timesteps.size(); ++step) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            generation.set_step(static_cast<int>(step));
            std::vector<float> model_input;
            if (!scheduler.scale_model_input(latents, step, &model_input, &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_scheduler_scale_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    error);
            }
            long long execute_ms = 0;
            if (execution_contract.use_cfg) {
                if (!qnn_run_sdxl_unet_once(
                        unet,
                        sample_index,
                        timestep_index,
                        hidden_index,
                        time_ids_index,
                        pooled_index,
                        model_input,
                        timesteps[step],
                        conditioning.negative_hidden.data(),
                        hidden_count,
                        conditioning.time_ids,
                        time_ids_count,
                        conditioning.negative_pooled,
                        pooled_count,
                        &noise_uncond,
                        &execute_ms,
                        &error)) {
                    return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_uncond_failed\",\"message\":") +
                        quote(error) + "}";
                }
                unet_execute_ms_total += execute_ms;
                ++unet_execution_count;
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            }
            execute_ms = 0;
            if (!qnn_run_sdxl_unet_once(
                    unet,
                    sample_index,
                    timestep_index,
                    hidden_index,
                    time_ids_index,
                    pooled_index,
                    model_input,
                    timesteps[step],
                    conditioning.positive_hidden.data(),
                    hidden_count,
                    conditioning.time_ids,
                    time_ids_count,
                    conditioning.positive_pooled,
                    pooled_count,
                    &noise_cond,
                    &execute_ms,
                    &error)) {
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_cond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            unet_execute_ms_total += execute_ms;
            ++unet_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            if ((execution_contract.use_cfg && noise_uncond.size() != latents.size()) ||
                noise_cond.size() != latents.size()) {
                return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_unet_output_shape_unsupported\",\"message\":\"SDXL UNet output does not exactly match the latent tensor.\"}";
            }
            std::vector<float> guided;
            if (!mca::image::apply_classifier_free_guidance(
                    noise_cond,
                    noise_uncond,
                    execution_contract.cfg_scale,
                    execution_contract.use_cfg,
                    &guided,
                    &error)) {
                return qnn_semantic_failure_json(
                    "sdxl_cfg_failed",
                    "EXECUTION_CONTRACT_INVALID",
                    error);
            }
            mca::diffusion::SchedulerStepResult step_result;
            mca::diffusion::SchedulerStepOptions step_options;
            step_options.eta = execution_contract.scheduler.eta;
            if (!scheduler.step(guided, step, latents, &step_result, &error, step_options)) {
                return qnn_semantic_failure_json(
                    "sdxl_scheduler_step_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    error);
            }
            latents = std::move(step_result.previous_sample);
            const int completed_step = static_cast<int>(step + 1U);
            generation.set_step(completed_step);
            const QnnPreviewPublishOutcome preview_outcome =
                preview_publisher.publish_if_due(
                    completed_step,
                    static_cast<int>(timesteps.size()),
                    latents,
                    effective_vae_host_scale,
                    vae,
                    execution_contract.pixel_range);
            if (preview_outcome == QnnPreviewPublishOutcome::Cancelled) {
                return qnn_image_generation_cancelled_json();
            }
            generation.set_phase(kQnnImageSampling);
        }
        if (unet_execution_count !=
            execution_contract.scheduler.expected_unet_execution_count) {
            return qnn_execution_contract_mismatch_json(
                "unetExecutionCount",
                execution_contract.scheduler.expected_unet_execution_count,
                unet_execution_count);
        }

        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        generation.set_phase(kQnnImageDecoding);
        QnnVaeDecodeResult vae_decode;
        if (!qnn_decode_vae_latents(
                vae,
                unet.inputs[sample_index].dimensions,
                latents,
                execution_contract,
                &vae_decode,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_vae_decode_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        int width = 0;
        int height = 0;
        mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
        QnnTensorBinding final_output_binding;
        final_output_binding.name = "sdxl_tiled_vae_output_nchw";
        final_output_binding.dimensions = {
            1U,
            3U,
            static_cast<uint32_t>(vae_decode.plan.final_output.height),
            static_cast<uint32_t>(vae_decode.plan.final_output.width),
        };
        generation.record_stage(
            mca::qnn::ImageStage::PngWrite,
            kQnnImagePngWrite);
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!write_vae_tensor_png(
                final_output_binding,
                vae_decode.pixels_nchw,
                output_path,
                execution_contract.pixel_range,
                &pixel_range_evidence,
                &width,
                &height,
                &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdxl_png_write_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (generation.cancelled()) {
            ::unlink(output_path.c_str());
            return qnn_image_generation_cancelled_json();
        }
        if (width != execution_contract.width || height != execution_contract.height) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "sdxl_output_resolution_mismatch",
                "EXECUTION_CONTRACT_MISMATCH",
                "Decoded SDXL image dimensions do not match the requested execution contract.");
        }

        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started
        ).count();
        const long long output_bytes = file_size_or_zero(output_path);
        std::string output_sha256;
        if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "sdxl_png_sha256_failed",
                "OUTPUT_INTEGRITY_FAILED",
                error);
        }
        if (generation.cancelled()) {
            ::unlink(output_path.c_str());
            return qnn_image_generation_cancelled_json();
        }
        QnnNativeEffectiveEvidence native_evidence{
            timesteps.size(),
            unet_execution_count,
            conditioning_evidence.tokenizer_backend,
            conditioning_evidence.token_count,
            conditioning_evidence.embedding_disk_data_type,
            width,
            height,
            unet.graph_name,
        };
        native_evidence.prompt_weighting_applied =
            conditioning_evidence.prompt_weighting_applied;
        native_evidence.positive_weighted_token_count =
            conditioning_evidence.positive_weighted_token_count;
        native_evidence.negative_weighted_token_count =
            conditioning_evidence.negative_weighted_token_count;
        native_evidence.prompt_weight_fingerprint =
            conditioning_evidence.prompt_weight_fingerprint;
        native_evidence.conditioning_artifact_sha256 =
            conditioning_evidence.conditioning_artifact_sha256;
        native_evidence.conditioning_execution_mode =
            conditioning_evidence.conditioning_execution_mode;
        native_evidence.conditioning_backend = conditioning_evidence.conditioning_backend;
        native_evidence.conditioning_graph = conditioning_evidence.conditioning_graph;
        native_evidence.conditioning_graph_sha256 = conditioning_graph_sha256;
        native_evidence.conditioning_order = conditioning_evidence.conditioning_order;
        native_evidence.conditioning_encoder_execution_count =
            conditioning_evidence.conditioning_encoder_execution_count;
        native_evidence.text_encoder_execution_count = text_encoder_execute_count;
        if (text_encoder_asset_evidence.required) {
            native_evidence.consumed_text_encoder_path =
                text_encoder_asset_evidence.relative_path;
            native_evidence.consumed_text_encoder_sha256 =
                text_encoder_asset_evidence.sha256;
            native_evidence.consumed_text_encoder_size_bytes =
                text_encoder_asset_evidence.size_bytes;
            native_evidence.consumed_text_encoder_asset_verified = true;
            native_evidence.language_proof_sha256 =
                text_encoder_asset_evidence.language_proof_sha256;
        }
        native_evidence.conditioning_artifact_consumed =
            conditioning_artifact_consumed && unet_execution_count > 0U;
        if (!bind_qnn_consumed_prompt_evidence(
                conditioning_evidence,
                native_evidence.conditioning_artifact_consumed,
                unet_execution_count,
                &native_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "native_prompt_evidence_invalid",
                "EXECUTION_EVIDENCE_INVALID",
                error);
        }
        if (!bind_qnn_consumed_prompt_to_encoder_evidence(
                text_encoder_asset_evidence,
                loaded_text_encoder_graph,
                conditioning_evidence,
                &native_evidence,
                &error)) {
            return qnn_semantic_failure_json(
                "prompt_to_encoder_evidence_invalid",
                "PROMPT_TO_ENCODER_EVIDENCE_INVALID",
                error);
        }
        native_evidence.runtime_session_mode = "shared_unet_vae";
        const std::string native_effective = qnn_native_effective_json(
            execution_contract,
            native_evidence);
        std::ostringstream out;
        out << std::setprecision(17) << "{"
            << "\"ok\":true,"
            << "\"backend\":\"qnn_htp\","
            << "\"pipelineProbe\":false,"
            << "\"semanticReady\":true,"
            << "\"npuActive\":true,"
            << "\"qnnGraphExecution\":true,"
            << "\"nativeExecution\":true,"
            << "\"fallback\":false,"
            << "\"executionStage\":\"sdxl_semantic_generation_passed\","
            << "\"profileId\":" << quote(execution_contract.profile_id) << ","
            << "\"profileRevision\":" << execution_contract.profile_revision << ","
            << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
            << "\"runtime\":\"QNN_HTP\","
            << "\"scheduler\":" << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ","
            << "\"predictionType\":" << quote(qnn_prediction_wire_name(execution_contract.scheduler.config.prediction_type)) << ","
            << "\"steps\":" << steps << ","
            << "\"timetableCount\":" << timesteps.size() << ","
            << "\"unetExecutionCount\":" << unet_execution_count << ","
            << "\"cfgScale\":" << execution_contract.cfg_scale << ","
            << "\"useCfg\":" << (execution_contract.use_cfg ? "true" : "false") << ","
            << "\"unconditionalBranch\":" << (execution_contract.use_cfg ? "true" : "false") << ","
            << "\"tokenizerBackend\":" << quote(conditioning_evidence.tokenizer_backend) << ","
            << "\"tokenCount\":" << conditioning_evidence.token_count << ","
            << "\"promptWeightingSupported\":" << (execution_contract.prompt_weighting_supported ? "true" : "false") << ","
            << "\"promptWeightingApplied\":" << (conditioning_evidence.prompt_weighting_applied ? "true" : "false") << ","
            << "\"positiveWeightedTokenCount\":" << conditioning_evidence.positive_weighted_token_count << ","
            << "\"negativeWeightedTokenCount\":" << conditioning_evidence.negative_weighted_token_count << ","
            << "\"promptWeightFingerprint\":" << quote(conditioning_evidence.prompt_weight_fingerprint) << ","
            << "\"nativePromptExecutionSha256\":"
            << quote(native_evidence.native_prompt_execution_sha256) << ","
            << "\"nativePromptBindingStage\":"
            << quote(native_evidence.native_prompt_binding_stage) << ","
            << "\"conditioningArtifactSha256\":"
            << quote(conditioning_evidence.conditioning_artifact_sha256) << ","
            << "\"conditioningExecutionMode\":"
            << quote(native_evidence.conditioning_execution_mode) << ","
            << "\"conditioningBackend\":" << quote(native_evidence.conditioning_backend) << ","
            << "\"conditioningGraph\":" << quote(native_evidence.conditioning_graph) << ","
            << "\"conditioningGraphSha256\":"
            << quote(native_evidence.conditioning_graph_sha256) << ","
            << "\"conditioningOrder\":" << quote(native_evidence.conditioning_order) << ","
            << "\"conditioningEncoderExecutionCount\":"
            << native_evidence.conditioning_encoder_execution_count << ","
            << qnn_prompt_to_encoder_receipt_json_fields(native_evidence) << ","
            << "\"embeddingDiskDataType\":" << quote(conditioning_evidence.embedding_disk_data_type) << ","
            << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(execution_contract.vae_scaling_location)) << ","
            << "\"vaeScalingFactor\":" << execution_contract.vae_scaling_factor << ","
            << qnn_pixel_range_evidence_json(
                execution_contract.pixel_range,
                pixel_range_evidence) << ","
            << "\"width\":" << width << ","
            << "\"height\":" << height << ","
            << "\"seed\":" << execution_contract.seed << ","
            << "\"graphName\":" << quote(unet.graph_name) << ","
            << "\"nativeEffective\":" << native_effective << ","
            << "\"timesteps\":" << qnn_double_array_json(timesteps) << ","
            << "\"sigmas\":" << qnn_double_array_json(scheduler.sigmas()) << ","
            << "\"initNoiseSigma\":" << scheduler.init_noise_sigma() << ","
            << "\"scaleModelInput\":" << (execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
            << "\"textEncoderExecutionCount\":" << text_encoder_execute_count << ","
            << "\"conditioningArtifactConsumed\":"
            << (native_evidence.conditioning_artifact_consumed ? "true" : "false") << ","
            << "\"vaeExecutionCount\":" << vae_decode.execution_count << ","
            << "\"finalVaeExecutionCount\":1,"
            << "\"finalVaeGraphExecutionCount\":" << vae_decode.execution_count << ","
            << "\"vaeTileCount\":" << vae_decode.plan.tiles.size() << ","
            << "\"vaeTiled\":" << (vae_decode.plan.tiled() ? "true" : "false") << ","
            << "\"previewRequested\":" << (preview_contract.requested ? "true" : "false") << ","
            << "\"previewMode\":" << quote(preview_contract.enabled ? "vae" : "none") << ","
            << "\"previewInterval\":" << preview_contract.interval << ","
            << "\"previewVaeExecutionAttemptCount\":"
            << preview_publisher.audit().vae_execution_attempt_count << ","
            << "\"previewVaeExecutionCount\":"
            << preview_publisher.audit().vae_execution_count << ","
            << "\"previewVaeExecutionMsTotal\":"
            << preview_publisher.audit().vae_execution_ms_total << ","
            << "\"previewPublicationCount\":"
            << preview_publisher.audit().publication_count << ","
            << "\"previewLastStep\":" << preview_publisher.audit().last_step << ","
            << "\"previewLastRevision\":" << preview_publisher.audit().last_revision << ","
            << "\"previewFailureCode\":"
            << quote(preview_publisher.audit().failure_code) << ","
            << "\"previewDegraded\":"
            << (preview_publisher.audit().stopped_after_failure() ? "true" : "false") << ","
            << "\"effectiveVaeHostScale\":" << effective_vae_host_scale << ","
            << "\"nativeGenerationSequence\":" << g_qnn_image_generation_sequence.load() << ","
            << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
            << "\"nativeStageMask\":" << g_qnn_image_generation_stage_mask.load() << ","
            << "\"nativeDetailStageMaskHex\":\""
            << mca::qnn::image_stage_mask_hex(g_qnn_image_generation_detail_stage_mask.load())
            << "\","
            << "\"runtimeSessionMode\":\"shared_unet_vae\","
            << "\"message\":\"QNN SDXL semantic generation completed with the resolved shared scheduler, QNN UNet, and QNN VAE decoder.\","
            << "\"conditioningFormat\":\"sdxl_qnn_conditioning\","
            << "\"elapsedMs\":" << elapsed << ","
            << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
            << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
            << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
            << "\"unetExecuteMsAvg\":" << (unet_execution_count > 0 ? (unet_execute_ms_total / static_cast<long long>(unet_execution_count)) : 0) << ","
            << "\"vaeExecuteMs\":" << vae_decode.execute_ms_total << ","
            << "\"conditioningElements\":" << embeddings.size() << ","
            << "\"hiddenElements\":" << hidden_count << ","
            << "\"pooledElements\":" << pooled_count << ","
            << "\"timeIdElements\":" << time_ids_count << ","
            << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
            << "\"pixelChecksum\":" << checksum_float_vector(vae_decode.pixels_nchw) << ","
            << "\"outputPath\":" << quote(output_path) << ","
            << "\"outputBytes\":" << output_bytes << ","
            << "\"outputSha256\":" << quote(output_sha256) << ","
            << "\"unetGraph\":" << quote(unet.graph_name) << ","
            << "\"vaeGraph\":" << quote(vae.graph_name) << ","
            << "\"debug\":{"
            << "\"timestepFirst\":" << (timesteps.empty() ? 0.0 : timesteps.front()) << ","
            << "\"timestepLast\":" << (timesteps.empty() ? 0.0 : timesteps.back()) << ","
            << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
            << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
            << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
            << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
            << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
            << "\"noiseUncondStats\":" << float_vector_stats_json(noise_uncond) << ","
            << "\"noiseCondStats\":" << float_vector_stats_json(noise_cond) << ","
            << "\"pixelStats\":" << float_vector_stats_json(vae_decode.pixels_nchw) << "},"
            << "\"executionRuntime\":" << runtime_probe_json(runtime_session.selected_runtime) << ","
            << "\"htpArchVersion\":" << runtime_session.selected_runtime.htp_arch_version << ","
            << "\"bundle\":" << bundle_probe_json(bundle)
            << "}";
        const std::string success_json = out.str();
        const auto release_failure_json = [&](const std::string& component,
                                              const std::string& detail) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                component + "_release_failed",
                "NATIVE_RESOURCE_RELEASE_FAILED",
                detail.empty()
                    ? "QNN SDXL resources did not release cleanly after final image generation."
                    : detail);
        };
        std::string release_error;
        if (!vae.close_checked(&release_error)) {
            return release_failure_json("vae", release_error);
        }
        if (!controlnet.close_checked(&release_error)) {
            return release_failure_json("controlnet", release_error);
        }
        if (!unet.close_checked(&release_error)) {
            return release_failure_json("unet", release_error);
        }
        if (!runtime_session.close_checked(&release_error)) {
            return release_failure_json("runtime", release_error);
        }
        return success_json;
    }

    int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
    if (request_inpaint) sample_index = inpaint_contract.sample_index;
    int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep", "time"});
    if (timestep_index < 0 && unet.inputs.size() > 1) timestep_index = 1;
    int text_index = tensor_index_by_name(unet.inputs, {"text_embedding", "encoder_hidden", "hidden"});
    if (text_index < 0 && unet.inputs.size() > 2) text_index = 2;
    if (unet.inputs.size() < 3 || sample_index >= static_cast<int>(unet.inputs.size()) ||
        timestep_index < 0 || text_index < 0 || unet.outputs.empty() ||
        vae.inputs.empty() || vae.outputs.empty()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SD1.5 semantic generation expects UNet(sample,timestep,text) and VAE(latent) tensors.\"}";
    }

    mca::image::SpatialTensorShape graph_sample_shape;
    mca::image::SpatialTensorShape unet_output_shape;
    mca::image::SpatialTensorShape vae_input_shape;
    mca::image::SpatialTensorShape vae_output_shape;
    const uint32_t expected_sample_channels = request_inpaint
        ? inpaint_contract.sample_channels
        : 4U;
    if (!mca::image::resolve_spatial_tensor_shape(
                unet.inputs[sample_index].dimensions,
                expected_sample_channels,
                &graph_sample_shape,
                &error) ||
        !mca::image::resolve_spatial_tensor_shape(
                unet.outputs[0].dimensions,
                4U,
                &unet_output_shape,
                &error) ||
        !mca::image::resolve_spatial_tensor_shape(
                vae.inputs[0].dimensions,
                4U,
                &vae_input_shape,
                &error) ||
        !mca::image::resolve_spatial_tensor_shape(
                vae.outputs[0].dimensions,
                3U,
                &vae_output_shape,
                &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"latent_layout_unsupported\",\"message\":") +
            quote(error) + "}";
    }
    mca::image::UltraFixTilePlan ultra_fix_plan;
    if (ultra_fix_request.enabled) {
        if (!img2img_encoder.ultra_fix || request_inpaint || request_controlnet ||
            !mca::image::build_ultrafix_tile_plan(
                img2img_encoder.encoder_input_dimensions,
                img2img_encoder.encoder_output_dimensions,
                unet.inputs[sample_index].dimensions,
                unet.outputs[0].dimensions,
                vae.inputs[0].dimensions,
                vae.outputs[0].dimensions,
                static_cast<size_t>(execution_contract.width),
                static_cast<size_t>(execution_contract.height),
                static_cast<size_t>(ultra_fix_request.tile_size),
                ultra_fix_request.overlap,
                8U,
                &ultra_fix_plan,
                &error) ||
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan) !=
                mca::image::ultrafix_tile_plan_descriptor(
                    img2img_encoder.ultra_fix_plan)) {
            return qnn_semantic_failure_json(
                "ultrafix_graph_topology_mismatch",
                "QNN_ULTRAFIX_GRAPH_TOPOLOGY_MISMATCH",
                error.empty()
                    ? "Loaded shared QNN encoder, UNet, and VAE graphs do not agree on one UltraFix tile plan."
                    : error);
        }
    } else if (graph_sample_shape.batch != 1U || unet_output_shape.batch != 1U ||
        vae_input_shape.batch != 1U || vae_output_shape.batch != 1U ||
        graph_sample_shape.height != unet_output_shape.height ||
        graph_sample_shape.width != unet_output_shape.width ||
        unet_output_shape.height != vae_input_shape.height ||
        unet_output_shape.width != vae_input_shape.width ||
        unet_output_shape.width * 8U != static_cast<size_t>(execution_contract.width) ||
        unet_output_shape.height * 8U != static_cast<size_t>(execution_contract.height) ||
        vae_output_shape.width != static_cast<size_t>(execution_contract.width) ||
        vae_output_shape.height != static_cast<size_t>(execution_contract.height)) {
            return qnn_semantic_failure_json(
                "latent_resolution_mismatch",
                "EXECUTION_CONTRACT_MISMATCH",
                "Resolved width and height must both match the native UNet latent and VAE tensors at the required 8x scale.");
    }
    // Diffusion state and VAE input are always four-channel NCHW. A dedicated
    // inpaint UNet may expose a nine-channel graph sample, assembled only at
    // the graph binding boundary from latent + mask + masked-image latent.
    const uint64_t latent_elements = ultra_fix_request.enabled
        ? ultra_fix_plan.full_latent.element_count()
        : unet_output_shape.element_count();
    const uint64_t text_elements = qnn_tensor_element_count(unet.inputs[text_index].tensor);
    if (latent_elements == 0 || text_elements == 0 ||
        (!ultra_fix_request.enabled && vae_input_shape.element_count() != latent_elements)) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN UNet and VAE latent tensor shapes are not compatible.\"}";
    }
    QnnControlNetLayout controlnet_layout;
    mca::qnn::controlnet::PreparedControlImage prepared_control_image;
    std::vector<float> control_image_tensor;
    if (request_controlnet) {
        if (!qnn_resolve_controlnet_layout(
                controlnet,
                unet,
                sample_index,
                timestep_index,
                text_index,
                execution_contract.width,
                execution_contract.height,
                &controlnet_layout,
                &error)) {
            return qnn_semantic_failure_json(
                "controlnet_tensor_layout_unsupported",
                "CONTROLNET_GRAPH_CONTRACT_INVALID",
                error);
        }
        if (!mca::qnn::controlnet::load_prepared_control_image(
                control_image_path,
                control_image_sha256,
                static_cast<uint32_t>(controlnet_layout.image_shape.width),
                static_cast<uint32_t>(controlnet_layout.image_shape.height),
                control_image_preprocess,
                &prepared_control_image,
                &error)) {
            return qnn_semantic_failure_json(
                "control_image_decode_failed",
                "CONTROL_IMAGE_INVALID",
                error);
        }
        if (!qnn_control_image_tensor_for_layout(
                prepared_control_image,
                controlnet_layout.image_shape,
                &control_image_tensor,
                &error)) {
            return qnn_semantic_failure_json(
                "control_image_tensor_bind_failed",
                "CONTROLNET_GRAPH_CONTRACT_INVALID",
                error);
        }
    }
    std::vector<float> inpaint_mask;
    std::vector<float> inpaint_full_mask;
    std::vector<float> inpaint_masked_latent;
    if (request_inpaint) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        const uint64_t mask_elements =
            static_cast<uint64_t>(inpaint_contract.width) *
            static_cast<uint64_t>(inpaint_contract.height);
        const std::vector<uint32_t> expected_mask_shape{
            1U, 1U, inpaint_contract.height, inpaint_contract.width};
        const std::vector<uint32_t> expected_full_mask_shape{
            1U,
            1U,
            static_cast<uint32_t>(execution_contract.height),
            static_cast<uint32_t>(execution_contract.width),
        };
        const std::string mask_tensor_path = string_field(params_json, "maskImageTensorPath");
        const std::string expected_mask_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageTensorSha256"));
        std::string actual_mask_tensor_sha256;
        const std::string full_mask_tensor_path = string_field(
            params_json, "maskImageFullTensorPath");
        const std::string expected_full_mask_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageFullTensorSha256"));
        std::string actual_full_mask_tensor_sha256;
        const long long mask_source_width = long_field(params_json, "maskImageSourceWidth");
        const long long mask_source_height = long_field(params_json, "maskImageSourceHeight");
        const long long mask_oriented_width = long_field(params_json, "maskImageOrientedWidth");
        const long long mask_oriented_height = long_field(params_json, "maskImageOrientedHeight");
        const long long input_oriented_width = long_field(params_json, "inputImageOrientedWidth");
        const long long input_oriented_height = long_field(params_json, "inputImageOrientedHeight");
        const long long full_repaint_pixel_count = long_field(
            params_json, "maskImageRepaintPixelCount");
        const long long mask_exif_orientation = long_field(
            params_json, "maskImageExifOrientation");
        if (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
            string_field(params_json, "inpaintMaskConvention") !=
                "white_repaint_black_preserve" ||
            mask_image_path.empty() || !valid_inpaint_sha256(mask_image_sha256) ||
            long_field(params_json, "maskImageSizeBytes") <= 0LL ||
            mask_source_width <= 0LL || mask_source_height <= 0LL ||
            mask_oriented_width <= 0LL || mask_oriented_height <= 0LL ||
            mask_exif_orientation < 1LL || mask_exif_orientation > 8LL ||
            mask_oriented_width != input_oriented_width ||
            mask_oriented_height != input_oriented_height ||
            full_repaint_pixel_count < 0LL ||
            full_repaint_pixel_count >
                static_cast<long long>(execution_contract.width) * execution_contract.height ||
            mask_tensor_path.empty() || !valid_inpaint_sha256(expected_mask_tensor_sha256) ||
            uint_array_field(params_json, "maskImageTensorShape") != expected_mask_shape ||
            string_field(params_json, "maskImageTensorDtype") != "float32-le" ||
            string_field(params_json, "maskImageTensorLayout") != "NCHW" ||
            string_field(params_json, "maskImageTensorRange") != "ZERO_TO_ONE" ||
            string_field(params_json, "maskImageTensorPreprocess") !=
                "source_aligned_center_crop_linear_grayscale_area_latent_nchw_v2" ||
            long_field(params_json, "maskImageTensorBytes") !=
                static_cast<long long>(mask_elements * sizeof(float)) ||
            !read_float_binary_file(
                mask_tensor_path,
                &inpaint_mask,
                &error,
                &actual_mask_tensor_sha256) ||
            normalized_contract_enum(actual_mask_tensor_sha256) != expected_mask_tensor_sha256 ||
            inpaint_mask.size() != static_cast<size_t>(mask_elements) ||
            long_field(params_json, "maskImageLatentRepaintPixelCount") !=
                static_cast<long long>(std::count_if(
                    inpaint_mask.begin(), inpaint_mask.end(), [](float value) {
                        return value > 0.0f;
                    })) ||
            full_mask_tensor_path.empty() ||
            !valid_inpaint_sha256(expected_full_mask_tensor_sha256) ||
            uint_array_field(params_json, "maskImageFullTensorShape") !=
                expected_full_mask_shape ||
            string_field(params_json, "maskImageFullTensorDtype") != "float32-le" ||
            string_field(params_json, "maskImageFullTensorLayout") != "NCHW" ||
            string_field(params_json, "maskImageFullTensorRange") != "ZERO_TO_ONE" ||
            string_field(params_json, "maskImageFullTensorPreprocess") !=
                "source_aligned_center_crop_linear_grayscale_full_nchw_v1" ||
            long_field(params_json, "maskImageFullTensorBytes") !=
                static_cast<long long>(
                    static_cast<uint64_t>(execution_contract.width) *
                    static_cast<uint64_t>(execution_contract.height) * sizeof(float)) ||
            !read_float_binary_file(
                full_mask_tensor_path,
                &inpaint_full_mask,
                &error,
                &actual_full_mask_tensor_sha256) ||
            normalized_contract_enum(actual_full_mask_tensor_sha256) !=
                expected_full_mask_tensor_sha256 ||
            inpaint_full_mask.size() != static_cast<size_t>(
                static_cast<uint64_t>(execution_contract.width) *
                static_cast<uint64_t>(execution_contract.height)) ||
            !mca::qnn::inpaint::validate_mask_values(
                inpaint_full_mask,
                static_cast<uint32_t>(execution_contract.width),
                static_cast<uint32_t>(execution_contract.height),
                &error) ||
            full_repaint_pixel_count != static_cast<long long>(std::count_if(
                inpaint_full_mask.begin(), inpaint_full_mask.end(), [](float value) {
                    return value > 0.0f;
                })) ||
            !mca::qnn::inpaint::validate_mask_values(
                inpaint_mask,
                inpaint_contract.width,
                inpaint_contract.height,
                &error)) {
            if (error.empty()) {
                error = "Prepared QNN inpaint mask identity, geometry, or normalized tensor contract is invalid.";
            }
            return qnn_semantic_failure_json(
                "inpaint_mask_tensor_invalid",
                "QNN_INPAINT_MASK_TENSOR_INVALID",
                error);
        }
        for (uint32_t latent_y = 0U; latent_y < inpaint_contract.height; ++latent_y) {
            for (uint32_t latent_x = 0U; latent_x < inpaint_contract.width; ++latent_x) {
                double sum = 0.0;
                for (uint32_t offset_y = 0U; offset_y < 8U; ++offset_y) {
                    const size_t row = static_cast<size_t>(latent_y * 8U + offset_y) *
                        static_cast<size_t>(execution_contract.width) + latent_x * 8U;
                    for (uint32_t offset_x = 0U; offset_x < 8U; ++offset_x) {
                        sum += inpaint_full_mask[row + offset_x];
                    }
                }
                const float expected_value = static_cast<float>(sum / 64.0);
                const float actual_value = inpaint_mask[
                    static_cast<size_t>(latent_y) * inpaint_contract.width + latent_x];
                if (std::fabs(actual_value - expected_value) > 1.0e-6f) {
                    return qnn_semantic_failure_json(
                        "inpaint_mask_scale_mismatch",
                        "QNN_INPAINT_MASK_TENSOR_INVALID",
                        "The latent inpaint mask is not the deterministic area reduction of the committed full-resolution mask.");
                }
            }
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (inpaint_contract.requires_masked_latent()) {
            inpaint_masked_latent = img2img_encoder.masked_latents;
            if (img2img_encoder.masked_execution_count != 1U ||
                inpaint_masked_latent.size() != static_cast<size_t>(latent_elements) ||
                !valid_inpaint_sha256(img2img_encoder.masked_latent_sha256)) {
                return qnn_semantic_failure_json(
                    "inpaint_masked_latent_invalid",
                    "QNN_INPAINT_MASKED_LATENT_INVALID",
                    "The concatenated QNN inpaint graph did not obtain a real masked-image latent from the loaded VAE encoder.");
            }
        } else if (img2img_encoder.masked_execution_count != 0U ||
                   !img2img_encoder.masked_latents.empty()) {
            return qnn_semantic_failure_json(
                "inpaint_masked_latent_unexpected",
                "QNN_INPAINT_EXECUTION_EVIDENCE_INVALID",
                "A four-channel QNN inpaint graph performed an unnecessary masked-image VAE encode.");
        }
    }
    const size_t conditioning_branch_count = execution_contract.use_cfg ? 2u : 1u;
    const size_t required_embedding_elements =
        static_cast<size_t>(text_elements) * conditioning_branch_count;
    if (qnn_token_conditioning && embeddings.size() != required_embedding_elements) {
        std::ostringstream message;
        message << "QNN text encoder output does not match the UNet conditioning input. "
                << "Encoder produced " << embeddings.size()
                << " total elements across " << conditioning_branch_count << " branch(es)"
                << " (width=" << text_encoder_embedding_width
                << "), UNet expects " << text_elements << ".";
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"text_encoder_unet_shape_mismatch\",\"message\":") +
            quote(message.str()) + "}";
    }
    if (embeddings.size() < required_embedding_elements) {
        std::ostringstream message;
        message << "Prompt embeddings are too small. Need at least "
                << required_embedding_elements << " f32 elements, got " << embeddings.size() << ".";
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"embedding_shape_unsupported\",\"message\":") +
            quote(message.str()) + "}";
    }

    const int steps = execution_contract.scheduler.steps;
    const auto& timesteps = scheduler.timesteps();
    const double effective_vae_host_scale = qnn_effective_vae_host_scale(execution_contract);
    generation.set_steps(static_cast<int>(
        ultra_fix_request.enabled
            ? effective_timetable_count * 2U
            : effective_timetable_count));
    generation.set_phase(kQnnImageSampling);
    std::mt19937 rng(execution_contract.seed);
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    const float initial_noise_scale = static_cast<float>(scheduler.init_noise_sigma());
    bool img2img_add_noise_applied = false;
    double img2img_add_noise_timestep = 0.0;
    uint64_t img2img_noise_checksum = 0U;
    std::vector<float> diffusion_noise;
    std::vector<float> ultra_fix_clean_latents;
    std::vector<float> ultra_fix_equivalent_noise;
    std::string inpaint_source_noise_sha256;
    size_t inpaint_source_noise_use_count = 0U;
    if (request_encoder_conditioned) {
        if (img2img_encoder.latents.size() != latents.size()) {
            return qnn_semantic_failure_json(
                "encoder_latent_shape_invalid",
                "ENCODER_LATENT_LAYOUT_INVALID",
                "Shared QNN encoder latent shape does not match the loaded UNet input tensor.");
        }
        if (ultra_fix_request.enabled) {
            latents = img2img_encoder.latents;
            ultra_fix_clean_latents = latents;
        } else {
            diffusion_noise.assign(latents.size(), 0.0f);
            for (float& value : diffusion_noise) value = normal(rng);
            img2img_noise_checksum = checksum_float_vector(diffusion_noise);
            if (request_inpaint) {
                inpaint_source_noise_sha256 = mca::qnn::controlnet::sha256_hex_bytes(
                    reinterpret_cast<const uint8_t*>(diffusion_noise.data()),
                    diffusion_noise.size() * sizeof(float));
            }
            if (img2img_noise_checksum == 0U ||
                (request_inpaint && !valid_inpaint_sha256(inpaint_source_noise_sha256)) ||
                !scheduler.add_noise(
                    img2img_encoder.latents,
                    diffusion_noise,
                    img2img_begin_index,
                    &latents,
                    &error)) {
                return qnn_semantic_failure_json(
                    "img2img_add_noise_failed",
                    "SCHEDULER_EXECUTION_FAILED",
                    error.empty()
                        ? "Shared QNN img2img diffusion noise lacks execution evidence."
                        : error);
            }
            img2img_add_noise_applied = true;
            img2img_add_noise_timestep = timesteps[img2img_begin_index];
            if (request_inpaint) ++inpaint_source_noise_use_count;
        }
    } else {
        for (float& value : latents) value = normal(rng) * initial_noise_scale;
    }

    const float* negative_embedding = embeddings.data();
    const bool has_two_embedding_branches =
        embeddings.size() >= static_cast<size_t>(text_elements * 2u);
    const float* positive_embedding = embeddings.data() +
        ((execution_contract.use_cfg || has_two_embedding_branches)
            ? static_cast<size_t>(text_elements)
            : 0u);
    std::vector<float> noise_uncond;
    std::vector<float> noise_cond;
    long long unet_execute_ms_total = 0;
    size_t unet_execution_count = 0;
    long long controlnet_execute_ms_total = 0;
    size_t controlnet_execution_count = 0;
    size_t controlnet_residual_write_count = 0;
    uint64_t controlnet_scaled_residual_checksum = 0U;
    std::string controlnet_input_buffer_sha256;
    size_t inpaint_mask_bind_count = 0U;
    size_t inpaint_preserve_step_count = 0U;
    uint64_t inpaint_preserved_latent_checksum = 0U;
    size_t ultra_fix_inversion_step_count = 0U;
    size_t ultra_fix_inversion_graph_execution_count = 0U;
    size_t ultra_fix_refinement_step_count = 0U;
    size_t ultra_fix_refinement_positive_execution_count = 0U;
    size_t ultra_fix_refinement_negative_execution_count = 0U;
    size_t ultra_fix_physical_unet_execution_count = 0U;
    size_t ultra_fix_quality_step_evaluation_count = 0U;
    size_t ultra_fix_noise_injection_step_count = 0U;
    std::string ultra_fix_noise_injection_seed_fingerprint;
    uint64_t ultra_fix_noise_injection_checksum = 0U;
    size_t ultra_fix_structure_guidance_step_count = 0U;
    uint64_t ultra_fix_structure_guidance_checksum = 0U;
    uint64_t ultra_fix_trajectory_noise_checksum = 0U;
    const auto run_task_unet_once = [&](const std::vector<float>& sample,
                                        double timestep,
                                        const float* embedding,
                                        std::vector<float>* output,
                                        long long* execute_ms) -> bool {
        if (request_inpaint) {
            const bool executed = qnn_run_inpaint_unet_once(
                unet,
                inpaint_contract,
                timestep_index,
                text_index,
                sample,
                inpaint_mask,
                inpaint_masked_latent,
                timestep,
                embedding,
                static_cast<size_t>(text_elements),
                output,
                execute_ms,
                &error);
            if (executed && inpaint_contract.requires_mask_binding()) {
                ++inpaint_mask_bind_count;
            }
            return executed;
        }
        return qnn_run_unet_once(
            unet,
            sample_index,
            timestep_index,
            text_index,
            sample,
            timestep,
            embedding,
            static_cast<size_t>(text_elements),
            output,
            execute_ms,
            &error);
    };
    if (ultra_fix_request.enabled) {
        mca::image::UltraFixNoiseLevel clean_level;
        for (size_t reverse = timesteps.size(); reverse > img2img_begin_index; --reverse) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            const size_t target_index = reverse - 1U;
            const bool first_hop = target_index + 1U == timesteps.size();
            const size_t evaluation_index = first_hop ? target_index : target_index + 1U;
            mca::image::UltraFixNoiseLevel target_level;
            mca::image::UltraFixNoiseLevel source_level;
            if (!qnn_ultrafix_noise_level(
                    scheduler, target_index, &target_level, &error) ||
                (!first_hop && !qnn_ultrafix_noise_level(
                    scheduler, evaluation_index, &source_level, &error))) {
                return qnn_semantic_failure_json(
                    "ultrafix_inversion_schedule_invalid",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            if (first_hop) source_level = clean_level;
            std::vector<float> model_input;
            if (!mca::image::scale_ultrafix_inversion_model_input(
                    latents,
                    source_level,
                    first_hop ? target_level : source_level,
                    &model_input,
                    &error)) {
                return qnn_semantic_failure_json(
                    "ultrafix_inversion_scale_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            std::vector<float> epsilon;
            if (!qnn_run_ultrafix_tiled_unet_branch(
                    unet,
                    sample_index,
                    timestep_index,
                    text_index,
                    ultra_fix_plan,
                    model_input,
                    timesteps[evaluation_index],
                    positive_embedding,
                    static_cast<size_t>(text_elements),
                    &epsilon,
                    &unet_execute_ms_total,
                    &ultra_fix_inversion_graph_execution_count,
                    &error) ||
                !mca::image::ultrafix_epsilon_inversion_step(
                    latents,
                    epsilon,
                    source_level,
                    target_level,
                    &model_input,
                    &error)) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                return qnn_semantic_failure_json(
                    "ultrafix_inversion_execute_failed",
                    "QNN_ULTRAFIX_INVERSION_FAILED",
                    error);
            }
            latents = std::move(model_input);
            ++ultra_fix_inversion_step_count;
            generation.set_step(static_cast<int>(ultra_fix_inversion_step_count));
        }
        if (ultra_fix_inversion_step_count != effective_timetable_count) {
            return qnn_semantic_failure_json(
                "ultrafix_inversion_incomplete",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix did not complete every DDIM inversion hop.");
        }
        const std::string seed_descriptor =
            mca::image::ultrafix_noise_seed_descriptor(
                execution_contract.seed,
                effective_timetable_count);
        ultra_fix_noise_injection_seed_fingerprint =
            mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(seed_descriptor.data()),
                seed_descriptor.size());
        if (ultra_fix_noise_injection_seed_fingerprint.size() != 64U) {
            return qnn_semantic_failure_json(
                "ultrafix_quality_seed_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix could not bind its deterministic quality-noise seed domain.");
        }
        if (effective_timetable_count > 1U) {
            mca::image::UltraFixNoiseLevel inverted_level;
            if (!qnn_ultrafix_noise_level(
                    scheduler,
                    img2img_begin_index,
                    &inverted_level,
                    &error) ||
                !mca::image::ultrafix_equivalent_noise(
                    ultra_fix_clean_latents,
                    latents,
                    inverted_level,
                    &ultra_fix_equivalent_noise,
                    &error)) {
                return qnn_semantic_failure_json(
                    "ultrafix_trajectory_noise_failed",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    error);
            }
            ultra_fix_trajectory_noise_checksum =
                mca::image::ultrafix_tensor_checksum(ultra_fix_equivalent_noise);
            if (ultra_fix_trajectory_noise_checksum == 0U) {
                return qnn_semantic_failure_json(
                    "ultrafix_trajectory_noise_evidence_invalid",
                    "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                    "QNN UltraFix trajectory noise lacks deterministic tensor evidence.");
            }
        }
    }
    for (size_t step = img2img_begin_index; step < timesteps.size(); ++step) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        const size_t physical_step = step - img2img_begin_index;
        generation.set_step(static_cast<int>(
            (ultra_fix_request.enabled ? effective_timetable_count : 0U) +
            physical_step));
        std::vector<float> model_input;
        if (!scheduler.scale_model_input(latents, step, &model_input, &error)) {
            return qnn_semantic_failure_json(
                "scheduler_scale_failed",
                "SCHEDULER_EXECUTION_FAILED",
                error);
        }
        long long execute_ms = 0;
        if (request_controlnet) {
            // The published ControlNet pipeline conditions the graph with the
            // positive embedding once per scheduler step, then supplies the
            // same residual set to both UNet branches. Running ControlNet again
            // for the unconditional branch changes the calibrated execution
            // and doubles the dominant graph cost.
            if (!qnn_run_controlnet_once(
                    controlnet,
                    controlnet_layout,
                    unet,
                    model_input,
                    timesteps[step],
                    positive_embedding,
                    static_cast<size_t>(text_elements),
                    control_image_tensor,
                    control_strength,
                    &controlnet_residual_write_count,
                    &controlnet_scaled_residual_checksum,
                    &controlnet_input_buffer_sha256,
                    &execute_ms,
                    &error)) {
                return qnn_semantic_failure_json(
                    "controlnet_execute_failed",
                    "CONTROLNET_EXECUTION_FAILED",
                    error);
            }
            controlnet_execute_ms_total += execute_ms;
            ++controlnet_execution_count;
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        if (execution_contract.use_cfg) {
            execute_ms = 0;
            const bool unconditioned_ok = ultra_fix_request.enabled
                ? qnn_run_ultrafix_tiled_unet_branch(
                    unet,
                    sample_index,
                    timestep_index,
                    text_index,
                    ultra_fix_plan,
                    model_input,
                    timesteps[step],
                    negative_embedding,
                    static_cast<size_t>(text_elements),
                    &noise_uncond,
                    &unet_execute_ms_total,
                    &ultra_fix_refinement_negative_execution_count,
                    &error)
                : run_task_unet_once(
                    model_input,
                    timesteps[step],
                    negative_embedding,
                    &noise_uncond,
                    &execute_ms);
            if (!unconditioned_ok) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_uncond_failed\",\"message\":") +
                    quote(error) + "}";
            }
            if (!ultra_fix_request.enabled) {
                unet_execute_ms_total += execute_ms;
                ++unet_execution_count;
            }
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        execute_ms = 0;
        const bool conditioned_ok = ultra_fix_request.enabled
            ? qnn_run_ultrafix_tiled_unet_branch(
                unet,
                sample_index,
                timestep_index,
                text_index,
                ultra_fix_plan,
                model_input,
                timesteps[step],
                positive_embedding,
                static_cast<size_t>(text_elements),
                &noise_cond,
                &unet_execute_ms_total,
                &ultra_fix_refinement_positive_execution_count,
                &error)
            : run_task_unet_once(
                model_input,
                timesteps[step],
                positive_embedding,
                &noise_cond,
                &execute_ms);
        if (!conditioned_ok) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_cond_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (!ultra_fix_request.enabled) {
            unet_execute_ms_total += execute_ms;
            ++unet_execution_count;
        }
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if ((execution_contract.use_cfg && noise_uncond.size() != latents.size()) ||
            noise_cond.size() != latents.size()) {
            return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"unet_output_shape_unsupported\",\"message\":\"UNet output does not exactly match the latent tensor.\"}";
        }
        std::vector<float> guided;
        if (!mca::image::apply_classifier_free_guidance(
                noise_cond,
                noise_uncond,
                execution_contract.cfg_scale,
                execution_contract.use_cfg,
                &guided,
                &error)) {
            return qnn_semantic_failure_json(
                "cfg_failed",
                "EXECUTION_CONTRACT_INVALID",
                error);
        }
        mca::image::UltraFixQualitySchedule ultra_fix_quality_schedule;
        const bool ultra_fix_has_next_step = ultra_fix_request.enabled &&
            step + 1U < timesteps.size();
        if (ultra_fix_request.enabled) {
            if (!mca::image::resolve_ultrafix_quality_schedule(
                    timesteps[step],
                    ultra_fix_has_next_step,
                    &ultra_fix_quality_schedule,
                    &error)) {
                return qnn_semantic_failure_json(
                    "ultrafix_quality_schedule_invalid",
                    "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                    error);
            }
            if (ultra_fix_has_next_step) {
                ++ultra_fix_quality_step_evaluation_count;
            }
            if (ultra_fix_quality_schedule.evaluate_noise_injection) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> mixed_prediction;
                uint64_t gaussian_checksum = 0U;
                uint64_t mixed_checksum = 0U;
                if (!mca::image::ultrafix_inject_spherical_noise(
                        guided,
                        execution_contract.seed,
                        static_cast<uint32_t>(physical_step),
                        ultra_fix_quality_schedule.noise_injection_fraction,
                        &mixed_prediction,
                        &gaussian_checksum,
                        &mixed_checksum,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "ultrafix_noise_injection_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error);
                }
                ultra_fix_noise_injection_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_noise_injection_checksum,
                        static_cast<uint32_t>(physical_step),
                        gaussian_checksum);
                ultra_fix_noise_injection_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_noise_injection_checksum,
                        static_cast<uint32_t>(physical_step),
                        mixed_checksum);
                guided = std::move(mixed_prediction);
                ++ultra_fix_noise_injection_step_count;
            }
        }
        mca::diffusion::SchedulerStepResult step_result;
        mca::diffusion::SchedulerStepOptions step_options;
        step_options.eta = execution_contract.scheduler.eta;
        if (!scheduler.step(guided, step, latents, &step_result, &error, step_options)) {
            return qnn_semantic_failure_json(
                "scheduler_step_failed",
                "SCHEDULER_EXECUTION_FAILED",
                error);
        }
        latents = std::move(step_result.previous_sample);
        if (ultra_fix_request.enabled) {
            ++ultra_fix_refinement_step_count;
            if (ultra_fix_quality_schedule.evaluate_structure_guidance) {
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> trajectory_reference;
                if (ultra_fix_equivalent_noise.empty() ||
                    !scheduler.add_noise(
                        ultra_fix_clean_latents,
                        ultra_fix_equivalent_noise,
                        step + 1U,
                        &trajectory_reference,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "ultrafix_structure_trajectory_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error.empty()
                            ? "QNN UltraFix structure guidance lacks its inverted trajectory noise."
                            : error);
                }
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
                std::vector<float> structured_latents;
                uint64_t structured_checksum = 0U;
                const size_t blur_radius =
                    mca::image::ultrafix_structure_blur_radius(
                        ultra_fix_plan.unet_input.width);
                if (blur_radius == 0U ||
                    !mca::image::ultrafix_apply_structure_guidance(
                        latents,
                        trajectory_reference,
                        ultra_fix_plan.full_latent,
                        blur_radius,
                        ultra_fix_quality_schedule.structure_guidance_weight,
                        &structured_latents,
                        &structured_checksum,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "ultrafix_structure_guidance_failed",
                        "QNN_ULTRAFIX_QUALITY_EXECUTION_FAILED",
                        error);
                }
                ultra_fix_structure_guidance_checksum =
                    mca::image::ultrafix_accumulate_checksum(
                        ultra_fix_structure_guidance_checksum,
                        static_cast<uint32_t>(physical_step),
                        structured_checksum);
                latents = std::move(structured_latents);
                ++ultra_fix_structure_guidance_step_count;
                if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            }
        }
        if (request_inpaint) {
            std::vector<float> preserved_source;
            if (step + 1U < timesteps.size()) {
                if (!scheduler.add_noise(
                        img2img_encoder.latents,
                        diffusion_noise,
                        step + 1U,
                        &preserved_source,
                        &error)) {
                    return qnn_semantic_failure_json(
                        "inpaint_source_noise_failed",
                        "QNN_INPAINT_PRESERVATION_FAILED",
                        error);
                }
                ++inpaint_source_noise_use_count;
            } else {
                preserved_source = img2img_encoder.latents;
            }
            std::vector<float> preserved_latents;
            if (!mca::qnn::inpaint::preserve_unmasked_latent(
                    latents,
                    preserved_source,
                    inpaint_mask,
                    inpaint_contract.width,
                    inpaint_contract.height,
                    &preserved_latents,
                    &error)) {
                return qnn_semantic_failure_json(
                    "inpaint_preservation_failed",
                    "QNN_INPAINT_PRESERVATION_FAILED",
                    error);
            }
            latents = std::move(preserved_latents);
            ++inpaint_preserve_step_count;
            inpaint_preserved_latent_checksum = checksum_float_vector(latents);
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        }
        const int completed_step = static_cast<int>(physical_step + 1U);
        generation.set_step(static_cast<int>(
            (ultra_fix_request.enabled ? effective_timetable_count : 0U) +
            static_cast<size_t>(completed_step)));
        if (!ultra_fix_request.enabled) {
            const QnnPreviewPublishOutcome preview_outcome =
                preview_publisher.publish_if_due(
                    completed_step,
                    static_cast<int>(effective_timetable_count),
                    latents,
                    effective_vae_host_scale,
                    vae,
                    execution_contract.pixel_range);
            if (preview_outcome == QnnPreviewPublishOutcome::Cancelled) {
                return qnn_image_generation_cancelled_json();
            }
        }
        generation.set_phase(kQnnImageSampling);
    }
    if (ultra_fix_request.enabled) {
        mca::image::UltraFixExecutionCounts expected_counts;
        const size_t quality_step_count = ultra_fix_quality_step_evaluation_count;
        const bool quality_noise_evidence_consistent =
            ultra_fix_noise_injection_step_count <= quality_step_count &&
            ((ultra_fix_noise_injection_step_count == 0U) ==
             (ultra_fix_noise_injection_checksum == 0U));
        const bool quality_structure_evidence_consistent =
            ultra_fix_structure_guidance_step_count <= quality_step_count &&
            ((ultra_fix_structure_guidance_step_count == 0U) ==
             (ultra_fix_structure_guidance_checksum == 0U));
        const bool quality_coverage_complete = quality_step_count == 0U
            ? ultra_fix_trajectory_noise_checksum == 0U
            : ultra_fix_trajectory_noise_checksum != 0U &&
                  ultra_fix_noise_injection_step_count +
                          ultra_fix_structure_guidance_step_count >=
                      quality_step_count;
        if (!mca::image::resolve_ultrafix_execution_counts(
                ultra_fix_plan.tiles.size(),
                effective_timetable_count,
                effective_timetable_count,
                execution_contract.use_cfg,
                &expected_counts,
                &error) ||
            ultra_fix_inversion_step_count != effective_timetable_count ||
            ultra_fix_refinement_step_count != effective_timetable_count ||
            ultra_fix_inversion_graph_execution_count !=
                expected_counts.inversion_positive_unet_graph_executions ||
            ultra_fix_refinement_positive_execution_count !=
                expected_counts.refinement_positive_unet_graph_executions ||
            ultra_fix_refinement_negative_execution_count !=
                expected_counts.refinement_negative_unet_graph_executions ||
            img2img_encoder.execution_count !=
                expected_counts.vae_encoder_graph_executions ||
            execution_contract.scheduler.expected_unet_execution_count !=
                effective_timetable_count * (execution_contract.use_cfg ? 2U : 1U) ||
            ultra_fix_quality_step_evaluation_count !=
                (effective_timetable_count > 0U ? effective_timetable_count - 1U : 0U) ||
            !quality_noise_evidence_consistent ||
            !quality_structure_evidence_consistent ||
            !quality_coverage_complete) {
            return qnn_semantic_failure_json(
                "ultrafix_execution_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                error.empty()
                    ? "QNN UltraFix physical graph counts do not match its tile plan and global scheduler trajectory."
                    : error);
        }
        ultra_fix_physical_unet_execution_count =
            expected_counts.total_unet_graph_executions;
        unet_execution_count =
            execution_contract.scheduler.expected_unet_execution_count;
    } else if (unet_execution_count !=
        execution_contract.scheduler.expected_unet_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "unetExecutionCount",
            execution_contract.scheduler.expected_unet_execution_count,
            unet_execution_count);
    }
    const size_t expected_inpaint_mask_bind_count = request_inpaint &&
        inpaint_contract.requires_mask_binding() ? unet_execution_count : 0U;
    const size_t expected_masked_encoder_count = request_inpaint &&
        inpaint_contract.requires_masked_latent() ? 1U : 0U;
    if (request_inpaint &&
        (inpaint_mask_bind_count != expected_inpaint_mask_bind_count ||
         inpaint_preserve_step_count != effective_timetable_count ||
         inpaint_source_noise_use_count != effective_timetable_count ||
         !valid_inpaint_sha256(inpaint_source_noise_sha256) ||
         img2img_encoder.execution_count != 1U + expected_masked_encoder_count ||
         img2img_encoder.masked_execution_count != expected_masked_encoder_count ||
         inpaint_mask.empty() ||
         (inpaint_contract.requires_masked_latent() && inpaint_masked_latent.empty()))) {
        return qnn_semantic_failure_json(
            "inpaint_execution_evidence_invalid",
            "QNN_INPAINT_EXECUTION_EVIDENCE_INVALID",
            "QNN inpaint did not consume its mask, reuse one diffusion-noise tensor, and preserve the unmasked source on every scheduler step.");
    }
    conditioning_artifact_consumed = conditioning_artifact_consumed &&
        unet_execution_count > 0U;
    const size_t expected_text_encoder_execution_count = qnn_token_conditioning
        ? (execution_contract.use_cfg ? 2U : 1U)
        : 0U;
    if (text_encoder_execute_count != expected_text_encoder_execution_count) {
        return qnn_execution_contract_mismatch_json(
            "textEncoderExecutionCount",
            expected_text_encoder_execution_count,
            text_encoder_execute_count);
    }
    if (conditioning_graph_sha256.size() != 64U) {
        return qnn_semantic_failure_json(
            "conditioning_graph_sha256_missing",
            "EXECUTION_EVIDENCE_INVALID",
            "The executed conditioning graph lacks a SHA-256 identity proof.");
    }
    if (!conditioning_artifact_consumed) {
        return qnn_semantic_failure_json(
            "conditioning_artifact_not_consumed",
            "EXECUTION_EVIDENCE_INVALID",
            "The prepared conditioning artifact was not consumed by a successful QNN UNet execution.");
    }
    if (request_controlnet) {
        const size_t expected_controlnet_executions = timesteps.size();
        const size_t expected_residual_writes =
            expected_controlnet_executions * controlnet_layout.residual_bindings.size();
        if (controlnet_execution_count != expected_controlnet_executions) {
            return qnn_execution_contract_mismatch_json(
                "controlNetExecutionCount",
                expected_controlnet_executions,
                controlnet_execution_count);
        }
        if (controlnet_residual_write_count != expected_residual_writes) {
            return qnn_execution_contract_mismatch_json(
                "controlNetResidualWriteCount",
                expected_residual_writes,
                controlnet_residual_write_count);
        }
        if (prepared_control_image.encoded_sha256 !=
                normalized_contract_enum(control_image_sha256) ||
            prepared_control_image.preprocessed_sha256.size() != 64U ||
            controlnet_input_buffer_sha256.size() != 64U ||
            control_image_tensor.empty()) {
            return qnn_semantic_failure_json(
                "control_input_evidence_invalid",
                "CONTROLNET_EXECUTION_EVIDENCE_INVALID",
                "ControlNet did not retain verifiable consumed image evidence.");
        }
    }

    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    generation.set_phase(kQnnImageDecoding);
    long long vae_execute_ms = 0;
    size_t final_vae_graph_execution_count = 0U;
    std::vector<float> pixels;
    if (ultra_fix_request.enabled) {
        QnnUltraFixVaeDecodeResult ultra_fix_decode;
        if (!qnn_decode_ultrafix_vae_latents(
                vae,
                ultra_fix_plan,
                latents,
                execution_contract,
                &ultra_fix_decode,
                &error)) {
            if (generation.cancelled()) return qnn_image_generation_cancelled_json();
            return qnn_semantic_failure_json(
                "ultrafix_vae_decode_failed",
                "QNN_ULTRAFIX_VAE_DECODE_FAILED",
                error);
        }
        pixels = std::move(ultra_fix_decode.pixels_nchw);
        vae_execute_ms = ultra_fix_decode.execute_ms_total;
        final_vae_graph_execution_count = ultra_fix_decode.execution_count;
    } else {
        std::vector<float> vae_latents = latents;
        if (effective_vae_host_scale != 1.0) {
            for (float& value : vae_latents) {
                value = static_cast<float>(value * effective_vae_host_scale);
            }
        }
        if (!qnn_write_float_tensor(&vae.inputs[0], vae_latents.data(), vae_latents.size(), &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_input_bind_failed\",\"message\":") +
                quote(error) + "}";
        }
        if (!vae.execute(&vae_execute_ms, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_execute_failed\",\"message\":") +
                quote(error) + "}";
        }
        final_vae_graph_execution_count = 1U;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"vae_output_read_failed\",\"message\":") +
                quote(error) + "}";
        }
    }
    int inpaint_pixel_blend_levels = 0;
    uint64_t inpaint_pixel_blend_checksum = 0U;
    bool inpaint_pixel_blend_applied = false;
    if (request_inpaint) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        std::vector<float> blended_pixels;
        if (!qnn_laplacian_blend_inpaint_vae_output(
                vae.outputs[0],
                pixels,
                execution_contract.pixel_range,
                img2img_encoder.source_pixels_nchw,
                inpaint_full_mask,
                &blended_pixels,
                &inpaint_pixel_blend_levels,
                &inpaint_pixel_blend_checksum,
                &error)) {
            return qnn_semantic_failure_json(
                "inpaint_pixel_blend_failed",
                "QNN_INPAINT_PIXEL_BLEND_FAILED",
                error);
        }
        pixels = std::move(blended_pixels);
        inpaint_pixel_blend_applied = true;
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    }
    int width = 0;
    int height = 0;
    mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
    generation.record_stage(
        mca::qnn::ImageStage::PngWrite,
        kQnnImagePngWrite);
    if (generation.cancelled()) return qnn_image_generation_cancelled_json();
    QnnTensorBinding final_output_binding;
    final_output_binding.dimensions = ultra_fix_request.enabled
        ? std::vector<uint32_t>{
            1U,
            3U,
            static_cast<uint32_t>(ultra_fix_plan.target_pixels.height),
            static_cast<uint32_t>(ultra_fix_plan.target_pixels.width),
        }
        : vae.outputs[0].dimensions;
    const bool png_written = ultra_fix_request.enabled
        ? write_ultrafix_png_atomic(
            final_output_binding,
            pixels,
            output_path,
            execution_contract.pixel_range,
            &pixel_range_evidence,
            &width,
            &height,
            &error)
        : write_vae_tensor_png(
            final_output_binding,
            pixels,
            output_path,
            execution_contract.pixel_range,
            &pixel_range_evidence,
            &width,
            &height,
            &error);
    if (!png_written) {
        if (generation.cancelled()) return qnn_image_generation_cancelled_json();
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(output_path.c_str());
        return qnn_image_generation_cancelled_json();
    }

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    const long long output_bytes = file_size_or_zero(output_path);
    std::string output_sha256;
    if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"png_sha256_failed\",\"message\":") +
            quote(error) + "}";
    }
    if (generation.cancelled()) {
        ::unlink(output_path.c_str());
        return qnn_image_generation_cancelled_json();
    }
    const std::vector<double> executed_timesteps(
        timesteps.begin() + static_cast<std::ptrdiff_t>(img2img_begin_index),
        timesteps.end());
    const std::vector<double> executed_sigmas = scheduler.sigmas().empty()
        ? std::vector<double>{}
        : std::vector<double>(
            scheduler.sigmas().begin() + static_cast<std::ptrdiff_t>(img2img_begin_index),
            scheduler.sigmas().end());
    const size_t semantic_unet_execution_count = ultra_fix_request.enabled
        ? execution_contract.scheduler.expected_unet_execution_count
        : unet_execution_count;
    QnnNativeEffectiveEvidence native_evidence{
        effective_timetable_count,
        semantic_unet_execution_count,
        conditioning_evidence.tokenizer_backend,
        conditioning_evidence.token_count,
        conditioning_evidence.embedding_disk_data_type,
        width,
        height,
        unet.graph_name,
    };
    native_evidence.prompt_weighting_applied =
        conditioning_evidence.prompt_weighting_applied;
    native_evidence.positive_weighted_token_count =
        conditioning_evidence.positive_weighted_token_count;
    native_evidence.negative_weighted_token_count =
        conditioning_evidence.negative_weighted_token_count;
    native_evidence.prompt_weight_fingerprint =
        conditioning_evidence.prompt_weight_fingerprint;
    native_evidence.conditioning_artifact_sha256 =
        conditioning_evidence.conditioning_artifact_sha256;
    native_evidence.conditioning_execution_mode = qnn_token_conditioning
        ? "qnn_text_encoder"
        : conditioning_evidence.conditioning_execution_mode;
    native_evidence.conditioning_backend = qnn_token_conditioning
        ? "QNN"
        : conditioning_evidence.conditioning_backend;
    native_evidence.conditioning_graph = qnn_token_conditioning
        ? loaded_text_encoder_graph
        : conditioning_evidence.conditioning_graph;
    native_evidence.conditioning_graph_sha256 = conditioning_graph_sha256;
    // External MNN conditioning reports the branch layout serialized in the
    // artifact. Shared SDXL retains that dual layout for ABI compatibility
    // even when its UNet later executes only the positive branch.
    native_evidence.conditioning_order = qnn_token_conditioning
        ? (execution_contract.use_cfg ? "negative_then_positive" : "positive_only")
        : conditioning_evidence.conditioning_order;
    native_evidence.conditioning_encoder_execution_count = qnn_token_conditioning
        ? text_encoder_execute_count
        : conditioning_evidence.conditioning_encoder_execution_count;
    native_evidence.text_encoder_execution_count = text_encoder_execute_count;
    if (text_encoder_asset_evidence.required) {
        native_evidence.consumed_text_encoder_path = text_encoder_asset_evidence.relative_path;
        native_evidence.consumed_text_encoder_sha256 = text_encoder_asset_evidence.sha256;
        native_evidence.consumed_text_encoder_size_bytes = text_encoder_asset_evidence.size_bytes;
        native_evidence.consumed_text_encoder_asset_verified = true;
        native_evidence.language_proof_sha256 = text_encoder_asset_evidence.language_proof_sha256;
    }
    native_evidence.conditioning_artifact_consumed = conditioning_artifact_consumed;
    if (!bind_qnn_consumed_prompt_evidence(
            conditioning_evidence,
            native_evidence.conditioning_artifact_consumed,
            unet_execution_count,
            &native_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "native_prompt_evidence_invalid",
            "EXECUTION_EVIDENCE_INVALID",
            error);
    }
    if (!bind_qnn_consumed_prompt_to_encoder_evidence(
            text_encoder_asset_evidence,
            loaded_text_encoder_graph,
            conditioning_evidence,
            &native_evidence,
            &error)) {
        return qnn_semantic_failure_json(
            "prompt_to_encoder_evidence_invalid",
            "PROMPT_TO_ENCODER_EVIDENCE_INVALID",
            error);
    }
    native_evidence.runtime_session_mode = request_controlnet
        ? (qnn_token_conditioning
            ? "shared_text_unet_controlnet_vae"
            : "shared_unet_controlnet_vae")
        : (qnn_token_conditioning ? "shared_text_unet_vae" : "shared_unet_vae");
    native_evidence.task_mode = task_mode;
    native_evidence.full_timetable_count = full_timetable_count;
    native_evidence.effective_denoise_steps = effective_timetable_count;
    native_evidence.control_strength = request_controlnet ? control_strength : 0.0;
    if (request_encoder_conditioned) {
        native_evidence.input_image_path = input_image_path;
        native_evidence.input_image_sha256 = normalized_contract_enum(
            string_field(params_json, "inputImageSha256"));
        native_evidence.input_image_tensor_path = string_field(
            params_json, "inputImageTensorPath");
        native_evidence.input_image_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "inputImageTensorSha256"));
        native_evidence.input_image_tensor_bytes = long_field(
            params_json, "inputImageTensorBytes");
        native_evidence.input_image_tensor_dtype = string_field(
            params_json, "inputImageTensorDtype");
        native_evidence.input_image_tensor_layout = string_field(
            params_json, "inputImageTensorLayout");
        native_evidence.input_image_tensor_range = string_field(
            params_json, "inputImageTensorRange");
        native_evidence.input_image_preprocess = string_field(
            params_json, "inputImagePreprocess");
        native_evidence.input_image_execution_count = 1U;
        native_evidence.input_image_source_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageSourceWidth")));
        native_evidence.input_image_source_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageSourceHeight")));
        native_evidence.input_image_oriented_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageOrientedWidth")));
        native_evidence.input_image_oriented_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "inputImageOrientedHeight")));
        native_evidence.input_image_exif_orientation = static_cast<int>(
            long_field(params_json, "inputImageExifOrientation"));
        native_evidence.input_image_tensor_width = static_cast<size_t>(width);
        native_evidence.input_image_tensor_height = static_cast<size_t>(height);
        native_evidence.input_image_tensor_channels = 3U;
        native_evidence.encoder_graph_input_height =
            img2img_encoder.encoder_input_dimensions.size() == 4U
            ? img2img_encoder.encoder_input_dimensions[2]
            : 0U;
        native_evidence.encoder_graph_input_width =
            img2img_encoder.encoder_input_dimensions.size() == 4U
            ? img2img_encoder.encoder_input_dimensions[3]
            : 0U;
        native_evidence.encoder_graph_output_height =
            img2img_encoder.encoder_output_dimensions.size() == 4U
            ? img2img_encoder.encoder_output_dimensions[2]
            : 0U;
        native_evidence.encoder_graph_output_width =
            img2img_encoder.encoder_output_dimensions.size() == 4U
            ? img2img_encoder.encoder_output_dimensions[3]
            : 0U;
        native_evidence.encoder_latent_sha256 = img2img_encoder.latent_sha256;
        native_evidence.encoder_context_sha256 = img2img_encoder.context_sha256;
        native_evidence.encoder_graph_name = img2img_encoder.graph_name;
        native_evidence.encoder_input_name = img2img_encoder.input_name;
        native_evidence.encoder_mean_output_name = img2img_encoder.mean_name;
        native_evidence.encoder_std_output_name = img2img_encoder.std_name;
        native_evidence.encoder_input_dtype = img2img_encoder.input_dtype;
        native_evidence.encoder_mean_dtype = img2img_encoder.mean_dtype;
        native_evidence.encoder_std_dtype = img2img_encoder.std_dtype;
        native_evidence.encoder_input_buffer_sha256 =
            img2img_encoder.input_buffer_sha256;
        native_evidence.encoder_mean_buffer_sha256 =
            img2img_encoder.mean_buffer_sha256;
        native_evidence.encoder_std_buffer_sha256 =
            img2img_encoder.std_buffer_sha256;
        native_evidence.encoder_runtime_mode = ultra_fix_request.enabled
            ? "standalone_tiled_encoder_then_shared_tiled_unet_vae"
            : request_inpaint && inpaint_contract.requires_masked_latent()
            ? "standalone_dual_encode_then_shared_unet_vae"
            : "standalone_encoder_then_shared_unet_vae";
        native_evidence.encoder_context_load_count = 1U;
        native_evidence.encoder_execution_count = img2img_encoder.execution_count;
        native_evidence.encoder_posterior_sample_count = img2img_encoder.latents.size();
        native_evidence.encoder_latent_scaling_factor = 0.18215;
        native_evidence.encoder_context_released_before_shared_session =
            img2img_encoder.context_released_before_shared_session;
        native_evidence.strength = img2img_strength;
        native_evidence.img2img_begin_index = img2img_begin_index;
        native_evidence.full_timetable_count = full_timetable_count;
        native_evidence.effective_denoise_steps = effective_timetable_count;
        native_evidence.img2img_add_noise_applied = img2img_add_noise_applied;
        native_evidence.img2img_add_noise_begin_index = img2img_begin_index;
        native_evidence.img2img_add_noise_timestep = img2img_add_noise_timestep;
        native_evidence.img2img_noise_checksum = fixed_width_lower_hex_u64(
            img2img_noise_checksum);
    }
    if (request_inpaint) {
        native_evidence.mask_image_path = mask_image_path;
        native_evidence.mask_image_sha256 = mask_image_sha256;
        native_evidence.mask_image_source_bytes = long_field(
            params_json, "maskImageSizeBytes");
        native_evidence.mask_image_tensor_path = string_field(
            params_json, "maskImageTensorPath");
        native_evidence.mask_image_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageTensorSha256"));
        native_evidence.mask_image_tensor_bytes = long_field(
            params_json, "maskImageTensorBytes");
        native_evidence.mask_image_tensor_dtype = string_field(
            params_json, "maskImageTensorDtype");
        native_evidence.mask_image_tensor_layout = string_field(
            params_json, "maskImageTensorLayout");
        native_evidence.mask_image_tensor_range = string_field(
            params_json, "maskImageTensorRange");
        native_evidence.mask_image_tensor_preprocess = string_field(
            params_json, "maskImageTensorPreprocess");
        native_evidence.mask_image_full_tensor_path = string_field(
            params_json, "maskImageFullTensorPath");
        native_evidence.mask_image_full_tensor_sha256 = normalized_contract_enum(
            string_field(params_json, "maskImageFullTensorSha256"));
        native_evidence.mask_image_full_tensor_bytes = long_field(
            params_json, "maskImageFullTensorBytes");
        native_evidence.mask_image_full_tensor_dtype = string_field(
            params_json, "maskImageFullTensorDtype");
        native_evidence.mask_image_full_tensor_layout = string_field(
            params_json, "maskImageFullTensorLayout");
        native_evidence.mask_image_full_tensor_range = string_field(
            params_json, "maskImageFullTensorRange");
        native_evidence.mask_image_full_tensor_preprocess = string_field(
            params_json, "maskImageFullTensorPreprocess");
        native_evidence.mask_image_source_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageSourceWidth")));
        native_evidence.mask_image_source_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageSourceHeight")));
        native_evidence.mask_image_oriented_width = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageOrientedWidth")));
        native_evidence.mask_image_oriented_height = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageOrientedHeight")));
        native_evidence.mask_image_exif_orientation = static_cast<int>(
            long_field(params_json, "maskImageExifOrientation"));
        native_evidence.mask_image_repaint_pixel_count = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageRepaintPixelCount")));
        native_evidence.mask_image_latent_repaint_pixel_count = static_cast<size_t>(
            std::max(0LL, long_field(params_json, "maskImageLatentRepaintPixelCount")));
        native_evidence.masked_input_image_tensor_path = string_field(
            params_json, "maskedInputImageTensorPath");
        native_evidence.masked_input_image_tensor_sha256 =
            img2img_encoder.masked_input_tensor_sha256;
        native_evidence.masked_input_image_tensor_bytes = long_field(
            params_json, "maskedInputImageTensorBytes");
        native_evidence.masked_input_image_tensor_dtype = string_field(
            params_json, "maskedInputImageTensorDtype");
        native_evidence.masked_input_image_tensor_layout = string_field(
            params_json, "maskedInputImageTensorLayout");
        native_evidence.masked_input_image_tensor_range = string_field(
            params_json, "maskedInputImageTensorRange");
        native_evidence.masked_input_image_tensor_preprocess = string_field(
            params_json, "maskedInputImageTensorPreprocess");
        native_evidence.masked_input_buffer_sha256 =
            img2img_encoder.masked_input_buffer_sha256;
        native_evidence.masked_input_mean_buffer_sha256 =
            img2img_encoder.masked_mean_buffer_sha256;
        native_evidence.masked_input_std_buffer_sha256 =
            img2img_encoder.masked_std_buffer_sha256;
        native_evidence.masked_input_latent_sha256 =
            img2img_encoder.masked_latent_sha256;
        native_evidence.masked_input_encoder_execution_count =
            img2img_encoder.masked_execution_count;
        native_evidence.masked_input_posterior_sample_count =
            img2img_encoder.masked_latents.size();
        native_evidence.masked_input_encoder_execute_ms =
            img2img_encoder.masked_execute_ms;
        native_evidence.mask_image_execution_count = 1U;
        native_evidence.inpaint_topology = inpaint_contract.topology_name();
        native_evidence.inpaint_mask_unet_bind_count = inpaint_mask_bind_count;
        native_evidence.inpaint_preserve_step_count = inpaint_preserve_step_count;
        native_evidence.inpaint_latent_blend_count = inpaint_preserve_step_count;
        native_evidence.inpaint_source_encoder_execution_count = 1U;
        native_evidence.inpaint_preserved_latent_checksum = fixed_width_lower_hex_u64(
            inpaint_preserved_latent_checksum);
        native_evidence.inpaint_source_noise_sha256 = inpaint_source_noise_sha256;
        native_evidence.inpaint_source_noise_use_count = inpaint_source_noise_use_count;
        native_evidence.inpaint_final_mode =
            "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend";
        native_evidence.inpaint_pixel_blend_levels = static_cast<size_t>(
            std::max(0, inpaint_pixel_blend_levels));
        native_evidence.inpaint_pixel_blend_checksum = fixed_width_lower_hex_u64(
            inpaint_pixel_blend_checksum);
        native_evidence.inpaint_pixel_blend_applied = inpaint_pixel_blend_applied;
        const bool native_mask_binding_complete = inpaint_contract.requires_mask_binding()
            ? inpaint_mask_bind_count == unet_execution_count && unet_execution_count > 0U
            : inpaint_mask_bind_count == 0U;
        native_evidence.mask_image_consumed = native_mask_binding_complete &&
            inpaint_preserve_step_count == effective_timetable_count &&
            !inpaint_mask.empty() && !inpaint_full_mask.empty() &&
            inpaint_pixel_blend_applied;
        native_evidence.inpaint_unmasked_preservation_applied =
            inpaint_preserve_step_count == effective_timetable_count &&
            effective_timetable_count > 0U && inpaint_pixel_blend_applied;
    }
    if (request_controlnet) {
        native_evidence.control_image_path = prepared_control_image.canonical_path;
        native_evidence.control_image_sha256 = prepared_control_image.encoded_sha256;
        native_evidence.control_image_preprocessed_sha256 =
            prepared_control_image.preprocessed_sha256;
        native_evidence.control_image_preprocess =
            mca::qnn::controlnet::control_image_preprocess_wire_name(
                prepared_control_image.preprocess_mode);
        native_evidence.control_image_execution_count = 1U;
        native_evidence.controlnet_execution_count = controlnet_execution_count;
        native_evidence.controlnet_residual_tensor_count =
            controlnet_layout.residual_bindings.size();
        native_evidence.controlnet_residual_write_count =
            controlnet_residual_write_count;
        native_evidence.controlnet_residual_unet_reuse_count =
            execution_contract.use_cfg ? timesteps.size() : 0U;
        native_evidence.controlnet_conditioning_branch = "positive";
        const size_t expected_controlnet_executions = timesteps.size();
        native_evidence.controlnet_input_consumed =
            controlnet_execution_count == expected_controlnet_executions &&
            controlnet_residual_write_count ==
                expected_controlnet_executions * controlnet_layout.residual_bindings.size() &&
            controlnet_input_buffer_sha256.size() == 64U;
        native_evidence.controlnet_graph_name = controlnet.graph_name;
        native_evidence.control_image_source_width = prepared_control_image.source_width;
        native_evidence.control_image_source_height = prepared_control_image.source_height;
        native_evidence.control_image_source_channels = prepared_control_image.source_channels;
        native_evidence.control_image_tensor_width = prepared_control_image.tensor_width;
        native_evidence.control_image_tensor_height = prepared_control_image.tensor_height;
        native_evidence.control_image_tensor_channels = prepared_control_image.tensor_channels;
        native_evidence.control_image_exif_orientation = prepared_control_image.exif_orientation;
        native_evidence.control_image_edge_pixel_count = prepared_control_image.edge_pixel_count;
        native_evidence.control_image_tensor_checksum = checksum_float_vector(control_image_tensor);
        native_evidence.controlnet_scaled_residual_checksum =
            controlnet_scaled_residual_checksum;
        native_evidence.controlnet_input_buffer_sha256 =
            controlnet_input_buffer_sha256;
    }
    if (ultra_fix_request.enabled) {
        const size_t source_width = native_evidence.input_image_oriented_width;
        const size_t source_height = native_evidence.input_image_oriented_height;
        const size_t target_width = static_cast<size_t>(ultra_fix_request.target_width);
        const size_t target_height = static_cast<size_t>(ultra_fix_request.target_height);
        if (source_width == 0U || source_height == 0U ||
            final_vae_graph_execution_count != ultra_fix_plan.tiles.size() ||
            output_bytes <= 0LL || output_sha256.size() != 64U) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "ultrafix_final_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix source geometry, tiled decoder counts, or committed output evidence is invalid.");
        }
        const bool fit_by_width =
            static_cast<uint64_t>(target_width) * source_height >=
            static_cast<uint64_t>(target_height) * source_width;
        size_t resized_width = target_width;
        size_t resized_height = target_height;
        if (fit_by_width) {
            resized_height = static_cast<size_t>(
                static_cast<uint64_t>(source_height) * target_width / source_width);
        } else {
            resized_width = static_cast<size_t>(
                static_cast<uint64_t>(source_width) * target_height / source_height);
        }
        const std::string plan_descriptor =
            mca::image::ultrafix_tile_plan_descriptor(ultra_fix_plan);
        QnnUltraFixExecutionEvidence ultra_fix_evidence;
        ultra_fix_evidence.request = ultra_fix_request;
        ultra_fix_evidence.plan = ultra_fix_plan;
        ultra_fix_evidence.tile_plan_sha256 =
            mca::qnn::controlnet::sha256_hex_bytes(
                reinterpret_cast<const uint8_t*>(plan_descriptor.data()),
                plan_descriptor.size());
        ultra_fix_evidence.source_width = source_width;
        ultra_fix_evidence.source_height = source_height;
        ultra_fix_evidence.source_resized_width = resized_width;
        ultra_fix_evidence.source_resized_height = resized_height;
        ultra_fix_evidence.source_crop_left = (resized_width - target_width) / 2U;
        ultra_fix_evidence.source_crop_top = (resized_height - target_height) / 2U;
        ultra_fix_evidence.encoder_context_load_count = 1U;
        ultra_fix_evidence.encoder_graph_execution_count =
            img2img_encoder.execution_count;
        ultra_fix_evidence.encoder_tile_success_count =
            img2img_encoder.execution_count;
        ultra_fix_evidence.inversion_step_count = ultra_fix_inversion_step_count;
        ultra_fix_evidence.inversion_graph_execution_count =
            ultra_fix_inversion_graph_execution_count;
        ultra_fix_evidence.inversion_tile_success_count =
            ultra_fix_inversion_graph_execution_count;
        ultra_fix_evidence.refinement_step_count = ultra_fix_refinement_step_count;
        ultra_fix_evidence.refinement_positive_graph_execution_count =
            ultra_fix_refinement_positive_execution_count;
        ultra_fix_evidence.refinement_negative_graph_execution_count =
            ultra_fix_refinement_negative_execution_count;
        ultra_fix_evidence.refinement_tile_success_count =
            ultra_fix_refinement_positive_execution_count +
            ultra_fix_refinement_negative_execution_count;
        ultra_fix_evidence.decoder_graph_execution_count =
            final_vae_graph_execution_count;
        ultra_fix_evidence.decoder_tile_success_count =
            final_vae_graph_execution_count;
        ultra_fix_evidence.physical_unet_graph_execution_count =
            ultra_fix_physical_unet_execution_count;
        ultra_fix_evidence.quality_step_evaluation_count =
            ultra_fix_quality_step_evaluation_count;
        ultra_fix_evidence.noise_injection_step_count =
            ultra_fix_noise_injection_step_count;
        ultra_fix_evidence.noise_injection_seed_fingerprint =
            ultra_fix_noise_injection_seed_fingerprint;
        ultra_fix_evidence.noise_injection_checksum =
            ultra_fix_noise_injection_checksum;
        ultra_fix_evidence.structure_guidance_step_count =
            ultra_fix_structure_guidance_step_count;
        ultra_fix_evidence.structure_guidance_checksum =
            ultra_fix_structure_guidance_checksum;
        ultra_fix_evidence.trajectory_noise_checksum =
            ultra_fix_trajectory_noise_checksum;
        ultra_fix_evidence.sample_method = qnn_scheduler_wire_name(
            execution_contract.scheduler.config.algorithm);
        ultra_fix_evidence.native_scheduler = ultra_fix_evidence.sample_method;
        ultra_fix_evidence.output_sha256 = output_sha256;
        ultra_fix_evidence.output_bytes = output_bytes;
        ultra_fix_evidence.output_atomic_commit = true;
        if (ultra_fix_evidence.tile_plan_sha256.size() != 64U ||
            resized_width < target_width || resized_height < target_height) {
            ::unlink(output_path.c_str());
            return qnn_semantic_failure_json(
                "ultrafix_tile_plan_evidence_invalid",
                "QNN_ULTRAFIX_EXECUTION_EVIDENCE_INVALID",
                "QNN UltraFix tile plan or center-cover geometry lacks deterministic evidence.");
        }
        native_evidence.ultra_fix_json =
            qnn_ultrafix_evidence_json(ultra_fix_evidence);
        native_evidence.ultra_fix_output_sha256 = output_sha256;
        native_evidence.ultra_fix_output_bytes = output_bytes;
        native_evidence.ultra_fix_output_atomic_commit = true;
        native_evidence.ultra_fix_positive_diffusion_model_compute_count =
            ultra_fix_inversion_graph_execution_count +
            ultra_fix_refinement_positive_execution_count;
        native_evidence.ultra_fix_negative_diffusion_model_compute_count =
            ultra_fix_refinement_negative_execution_count;
        native_evidence.ultra_fix_auxiliary_diffusion_model_compute_count = 0U;
        native_evidence.ultra_fix_sampling_pass_count = 1U;
        native_evidence.ultra_fix_total_unet_execution_count =
            ultra_fix_physical_unet_execution_count;
    }
    const std::string native_effective = qnn_native_effective_json(
        execution_contract,
        native_evidence);
    const size_t unet_execute_average_count = ultra_fix_request.enabled
        ? ultra_fix_physical_unet_execution_count
        : unet_execution_count;
    std::ostringstream out;
    out << std::setprecision(17) << "{"
        << "\"ok\":true,"
        << "\"backend\":\"qnn_htp\","
        << "\"pipelineProbe\":false,"
        << "\"semanticReady\":true,"
        << "\"npuActive\":true,"
        << "\"qnnGraphExecution\":true,"
        << "\"nativeExecution\":true,"
        << "\"fallback\":false,"
        << "\"executionStage\":\"semantic_generation_passed\","
        << "\"profileId\":" << quote(execution_contract.profile_id) << ","
        << "\"profileRevision\":" << execution_contract.profile_revision << ","
        << "\"modelFingerprint\":" << quote(execution_contract.model_fingerprint) << ","
        << "\"runtime\":\"QNN_HTP\","
        << "\"scheduler\":" << quote(qnn_scheduler_wire_name(execution_contract.scheduler.config.algorithm)) << ","
        << "\"predictionType\":" << quote(qnn_prediction_wire_name(execution_contract.scheduler.config.prediction_type)) << ","
        << "\"steps\":" << steps << ","
        << "\"timetableCount\":" << effective_timetable_count << ","
        << "\"unetExecutionCount\":" << unet_execution_count << ","
        << "\"cfgScale\":" << execution_contract.cfg_scale << ","
        << "\"useCfg\":" << (execution_contract.use_cfg ? "true" : "false") << ","
        << "\"unconditionalBranch\":" << (execution_contract.use_cfg ? "true" : "false") << ","
        << "\"tokenizerBackend\":" << quote(conditioning_evidence.tokenizer_backend) << ","
        << "\"tokenCount\":" << conditioning_evidence.token_count << ","
        << "\"promptWeightingSupported\":" << (execution_contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingApplied\":" << (conditioning_evidence.prompt_weighting_applied ? "true" : "false") << ","
        << "\"promptWeightingExecutionSupported\":" << (!qnn_token_conditioning && execution_contract.prompt_weighting_supported ? "true" : "false") << ","
        << "\"promptWeightingExecutionMode\":" << quote(prompt_weighting_execution_mode) << ","
        << "\"positiveWeightedTokenCount\":" << conditioning_evidence.positive_weighted_token_count << ","
        << "\"negativeWeightedTokenCount\":" << conditioning_evidence.negative_weighted_token_count << ","
        << "\"promptWeightFingerprint\":" << quote(conditioning_evidence.prompt_weight_fingerprint) << ","
        << "\"nativePromptExecutionSha256\":"
        << quote(native_evidence.native_prompt_execution_sha256) << ","
        << "\"nativePromptBindingStage\":"
        << quote(native_evidence.native_prompt_binding_stage) << ","
        << "\"conditioningArtifactSha256\":"
        << quote(conditioning_evidence.conditioning_artifact_sha256) << ","
        << "\"conditioningExecutionMode\":"
        << quote(native_evidence.conditioning_execution_mode) << ","
        << "\"conditioningBackend\":" << quote(native_evidence.conditioning_backend) << ","
        << "\"conditioningGraph\":" << quote(native_evidence.conditioning_graph) << ","
        << "\"conditioningGraphSha256\":"
        << quote(native_evidence.conditioning_graph_sha256) << ","
        << "\"conditioningOrder\":" << quote(native_evidence.conditioning_order) << ","
        << "\"conditioningEncoderExecutionCount\":"
        << native_evidence.conditioning_encoder_execution_count << ","
        << qnn_prompt_to_encoder_receipt_json_fields(native_evidence) << ","
        << "\"embeddingDiskDataType\":" << quote(conditioning_evidence.embedding_disk_data_type) << ","
        << "\"vaeScalingLocation\":" << quote(qnn_vae_scaling_wire_name(execution_contract.vae_scaling_location)) << ","
        << "\"vaeScalingFactor\":" << execution_contract.vae_scaling_factor << ","
        << qnn_pixel_range_evidence_json(
            execution_contract.pixel_range,
            pixel_range_evidence) << ","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << "\"seed\":" << execution_contract.seed << ","
        << "\"graphName\":" << quote(unet.graph_name) << ","
        << "\"taskMode\":" << quote(task_mode) << ","
        << "\"batchCount\":1,"
        << "\"inputImagePath\":" << quote(native_evidence.input_image_path) << ","
        << "\"maskImagePath\":" << quote(native_evidence.mask_image_path) << ","
        << "\"controlImagePath\":" << quote(native_evidence.control_image_path) << ","
        << "\"inputImageExecutionCount\":"
        << native_evidence.input_image_execution_count << ","
        << "\"maskImageExecutionCount\":"
        << native_evidence.mask_image_execution_count << ","
        << "\"maskImageSha256\":" << quote(native_evidence.mask_image_sha256) << ","
        << "\"maskImageSizeBytes\":"
        << native_evidence.mask_image_source_bytes << ","
        << "\"maskImageSourceWidth\":" << native_evidence.mask_image_source_width << ","
        << "\"maskImageSourceHeight\":" << native_evidence.mask_image_source_height << ","
        << "\"maskImageOrientedWidth\":" << native_evidence.mask_image_oriented_width << ","
        << "\"maskImageOrientedHeight\":" << native_evidence.mask_image_oriented_height << ","
        << "\"maskImageExifOrientation\":" << native_evidence.mask_image_exif_orientation << ","
        << "\"maskImageSourceReadByNative\":false,"
        << "\"maskImageSourceValidation\":"
        << quote(request_inpaint ? "android_preprocess_provenance" : "none") << ","
        << "\"maskImageTensorPath\":" << quote(native_evidence.mask_image_tensor_path) << ","
        << "\"maskImageTensorSha256\":" << quote(native_evidence.mask_image_tensor_sha256) << ","
        << "\"maskImageTensorBytes\":" << native_evidence.mask_image_tensor_bytes << ","
        << "\"maskImageTensorShape\":[1,1,"
        << (request_inpaint ? inpaint_contract.height : 0U) << ","
        << (request_inpaint ? inpaint_contract.width : 0U) << "],"
        << "\"maskImageTensorDtype\":" << quote(native_evidence.mask_image_tensor_dtype) << ","
        << "\"maskImageTensorLayout\":" << quote(native_evidence.mask_image_tensor_layout) << ","
        << "\"maskImageTensorRange\":" << quote(native_evidence.mask_image_tensor_range) << ","
        << "\"maskImageTensorPreprocess\":" << quote(native_evidence.mask_image_tensor_preprocess) << ","
        << "\"maskImageFullTensorPath\":" << quote(native_evidence.mask_image_full_tensor_path) << ","
        << "\"maskImageFullTensorSha256\":" << quote(native_evidence.mask_image_full_tensor_sha256) << ","
        << "\"maskImageFullTensorBytes\":" << native_evidence.mask_image_full_tensor_bytes << ","
        << "\"maskImageFullTensorShape\":[1,1,"
        << (request_inpaint ? height : 0) << "," << (request_inpaint ? width : 0) << "],"
        << "\"maskImageFullTensorDtype\":" << quote(native_evidence.mask_image_full_tensor_dtype) << ","
        << "\"maskImageFullTensorLayout\":" << quote(native_evidence.mask_image_full_tensor_layout) << ","
        << "\"maskImageFullTensorRange\":" << quote(native_evidence.mask_image_full_tensor_range) << ","
        << "\"maskImageFullTensorPreprocess\":"
        << quote(native_evidence.mask_image_full_tensor_preprocess) << ","
        << "\"maskImageRepaintPixelCount\":" << native_evidence.mask_image_repaint_pixel_count << ","
        << "\"maskImageLatentRepaintPixelCount\":"
        << native_evidence.mask_image_latent_repaint_pixel_count << ","
        << "\"maskedInputImageTensorPath\":"
        << quote(native_evidence.masked_input_image_tensor_path) << ","
        << "\"maskedInputImageTensorSha256\":"
        << quote(native_evidence.masked_input_image_tensor_sha256) << ","
        << "\"maskedInputImageTensorBytes\":"
        << native_evidence.masked_input_image_tensor_bytes << ","
        << "\"maskedInputImageTensorShape\":[1,3,"
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? height : 0)
        << ","
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? width : 0)
        << "],"
        << "\"maskedInputImageTensorDtype\":"
        << quote(native_evidence.masked_input_image_tensor_dtype) << ","
        << "\"maskedInputImageTensorLayout\":"
        << quote(native_evidence.masked_input_image_tensor_layout) << ","
        << "\"maskedInputImageTensorRange\":"
        << quote(native_evidence.masked_input_image_tensor_range) << ","
        << "\"maskedInputImageTensorPreprocess\":"
        << quote(native_evidence.masked_input_image_tensor_preprocess) << ","
        << "\"maskedInputBufferSha256\":" << quote(native_evidence.masked_input_buffer_sha256) << ","
        << "\"maskedInputMeanBufferSha256\":"
        << quote(native_evidence.masked_input_mean_buffer_sha256) << ","
        << "\"maskedInputStdBufferSha256\":"
        << quote(native_evidence.masked_input_std_buffer_sha256) << ","
        << "\"maskedInputLatentSha256\":" << quote(native_evidence.masked_input_latent_sha256) << ","
        << "\"maskedInputLatentShape\":[1,4,"
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? height / 8 : 0)
        << ","
        << (request_inpaint && inpaint_contract.requires_masked_latent() ? width / 8 : 0)
        << "],"
        << "\"maskedInputEncoderExecutionCount\":"
        << native_evidence.masked_input_encoder_execution_count << ","
        << "\"maskedInputPosteriorSampleCount\":"
        << native_evidence.masked_input_posterior_sample_count << ","
        << "\"maskedInputEncoderExecuteMs\":" << native_evidence.masked_input_encoder_execute_ms << ","
        << "\"inpaintTopology\":" << quote(native_evidence.inpaint_topology) << ","
        << "\"inpaintMaskUnetBindCount\":" << native_evidence.inpaint_mask_unet_bind_count << ","
        << "\"inpaintPreserveStepCount\":" << native_evidence.inpaint_preserve_step_count << ","
        << "\"inpaintLatentBlendCount\":" << native_evidence.inpaint_latent_blend_count << ","
        << "\"inpaintSourceEncoderExecutionCount\":"
        << native_evidence.inpaint_source_encoder_execution_count << ","
        << "\"inpaintPreservedLatentChecksum\":"
        << quote(native_evidence.inpaint_preserved_latent_checksum) << ","
        << "\"inpaintSourceNoiseSha256\":"
        << quote(native_evidence.inpaint_source_noise_sha256) << ","
        << "\"inpaintSourceNoiseUseCount\":"
        << native_evidence.inpaint_source_noise_use_count << ","
        << "\"inpaintFinalMode\":" << quote(native_evidence.inpaint_final_mode) << ","
        << "\"inpaintPixelBlendLevels\":" << native_evidence.inpaint_pixel_blend_levels << ","
        << "\"inpaintPixelBlendChecksum\":"
        << quote(native_evidence.inpaint_pixel_blend_checksum) << ","
        << "\"inpaintPixelBlendApplied\":"
        << (native_evidence.inpaint_pixel_blend_applied ? "true" : "false") << ","
        << "\"inpaintMaskConsumed\":" << (native_evidence.mask_image_consumed ? "true" : "false") << ","
        << "\"inpaintUnmaskedPreservationApplied\":"
        << (native_evidence.inpaint_unmasked_preservation_applied ? "true" : "false") << ","
        << "\"controlImageExecutionCount\":"
        << native_evidence.control_image_execution_count << ","
        << "\"controlImageSha256\":" << quote(native_evidence.control_image_sha256) << ","
        << "\"controlImagePreprocessedSha256\":"
        << quote(native_evidence.control_image_preprocessed_sha256) << ","
        << "\"controlImagePreprocess\":" << quote(native_evidence.control_image_preprocess) << ","
        << "\"inputImageSha256\":" << quote(native_evidence.input_image_sha256) << ","
        << "\"inputImageSizeBytes\":" << (request_encoder_conditioned
            ? long_field(params_json, "inputImageSizeBytes")
            : 0LL) << ","
        << "\"inputImageSourceWidth\":" << native_evidence.input_image_source_width << ","
        << "\"inputImageSourceHeight\":" << native_evidence.input_image_source_height << ","
        << "\"inputImageOrientedWidth\":" << native_evidence.input_image_oriented_width << ","
        << "\"inputImageOrientedHeight\":" << native_evidence.input_image_oriented_height << ","
        << "\"inputImageExifOrientation\":" << native_evidence.input_image_exif_orientation << ","
        << "\"inputImageSourceReadByNative\":false,"
        << "\"inputImageSourceValidation\":"
        << quote(request_encoder_conditioned ? "android_preprocess_provenance" : "none") << ","
        << "\"inputImageTensorPath\":" << quote(native_evidence.input_image_tensor_path) << ","
        << "\"inputImageTensorSha256\":"
        << quote(native_evidence.input_image_tensor_sha256) << ","
        << "\"inputImageTensorBytes\":" << native_evidence.input_image_tensor_bytes << ","
        << "\"inputImageTensorShape\":[1,3," << native_evidence.input_image_tensor_height
        << "," << native_evidence.input_image_tensor_width << "],"
        << "\"inputImageTensorWidth\":" << native_evidence.input_image_tensor_width << ","
        << "\"inputImageTensorHeight\":" << native_evidence.input_image_tensor_height << ","
        << "\"inputImageTensorChannels\":" << native_evidence.input_image_tensor_channels << ","
        << "\"inputImageTensorDtype\":" << quote(native_evidence.input_image_tensor_dtype) << ","
        << "\"inputImageTensorLayout\":" << quote(native_evidence.input_image_tensor_layout) << ","
        << "\"inputImageTensorRange\":" << quote(native_evidence.input_image_tensor_range) << ","
        << "\"inputImagePreprocess\":" << quote(native_evidence.input_image_preprocess) << ","
        << "\"encoderContextLoadCount\":" << native_evidence.encoder_context_load_count << ","
        << "\"encoderExecutionCount\":" << native_evidence.encoder_execution_count << ","
        << "\"encoderGraphName\":" << quote(native_evidence.encoder_graph_name) << ","
        << "\"encoderContextSha256\":" << quote(native_evidence.encoder_context_sha256) << ","
        << "\"encoderInputName\":" << quote(native_evidence.encoder_input_name) << ","
        << "\"encoderMeanOutputName\":"
        << quote(native_evidence.encoder_mean_output_name) << ","
        << "\"encoderStdOutputName\":"
        << quote(native_evidence.encoder_std_output_name) << ","
        << "\"encoderInputDtype\":" << quote(native_evidence.encoder_input_dtype) << ","
        << "\"encoderMeanDtype\":" << quote(native_evidence.encoder_mean_dtype) << ","
        << "\"encoderStdDtype\":" << quote(native_evidence.encoder_std_dtype) << ","
        << "\"encoderInputShape\":[1,3," << native_evidence.encoder_graph_input_height
        << "," << native_evidence.encoder_graph_input_width << "],"
        << "\"encoderMeanShape\":[1,4," << native_evidence.encoder_graph_output_height
        << "," << native_evidence.encoder_graph_output_width << "],"
        << "\"encoderStdShape\":[1,4," << native_evidence.encoder_graph_output_height
        << "," << native_evidence.encoder_graph_output_width << "],"
        << "\"encoderInputBufferSha256\":"
        << quote(native_evidence.encoder_input_buffer_sha256) << ","
        << "\"encoderMeanBufferSha256\":"
        << quote(native_evidence.encoder_mean_buffer_sha256) << ","
        << "\"encoderStdBufferSha256\":"
        << quote(native_evidence.encoder_std_buffer_sha256) << ","
        << "\"encoderLatentSha256\":" << quote(native_evidence.encoder_latent_sha256) << ","
        << "\"posteriorSampling\":"
        << quote(request_encoder_conditioned
            ? "mean_plus_std_times_normal_mt19937_domain_v1"
            : "none") << ","
        << "\"posteriorSampleCount\":"
        << native_evidence.encoder_posterior_sample_count << ","
        << "\"encoderLatentScalingFactor\":"
        << native_evidence.encoder_latent_scaling_factor << ","
        << "\"encoderContextReleasedBeforeSharedSession\":"
        << (native_evidence.encoder_context_released_before_shared_session ? "true" : "false") << ","
        << "\"encoderRuntimeMode\":" << quote(native_evidence.encoder_runtime_mode) << ","
        << "\"controlImageSourceWidth\":" << prepared_control_image.source_width << ","
        << "\"controlImageSourceHeight\":" << prepared_control_image.source_height << ","
        << "\"controlImageSourceChannels\":" << prepared_control_image.source_channels << ","
        << "\"controlImageOrientedWidth\":" << prepared_control_image.oriented_width << ","
        << "\"controlImageOrientedHeight\":" << prepared_control_image.oriented_height << ","
        << "\"controlImageExifOrientation\":"
        << (request_controlnet ? prepared_control_image.exif_orientation : 0) << ","
        << "\"controlImageTensorWidth\":" << prepared_control_image.tensor_width << ","
        << "\"controlImageTensorHeight\":" << prepared_control_image.tensor_height << ","
        << "\"controlImageTensorChannels\":"
        << (request_controlnet ? prepared_control_image.tensor_channels : 0U) << ","
        << "\"controlImageTensorLayout\":"
        << quote(request_controlnet &&
                 controlnet_layout.image_shape.layout == mca::image::SpatialTensorLayout::Nchw
                     ? "NCHW"
                     : (request_controlnet ? "NHWC" : "NONE")) << ","
        << "\"controlImageEdgePixelCount\":" << prepared_control_image.edge_pixel_count << ","
        << "\"controlImageTensorChecksum\":" << checksum_float_vector(control_image_tensor) << ","
        << "\"strength\":" << native_evidence.strength << ","
        << "\"fullTimetableCount\":" << native_evidence.full_timetable_count << ","
        << "\"effectiveDenoiseSteps\":" << native_evidence.effective_denoise_steps << ","
        << "\"img2imgBeginIndex\":" << native_evidence.img2img_begin_index << ","
        << "\"img2imgAddNoiseApplied\":"
        << (native_evidence.img2img_add_noise_applied ? "true" : "false") << ","
        << "\"img2imgAddNoiseBeginIndex\":"
        << native_evidence.img2img_add_noise_begin_index << ","
        << "\"img2imgAddNoiseTimestep\":"
        << native_evidence.img2img_add_noise_timestep << ","
        << "\"img2imgNoiseChecksum\":" << quote(native_evidence.img2img_noise_checksum) << ","
        << "\"controlStrength\":" << native_evidence.control_strength << ","
        << "\"controlStrengthApplied\":" << (request_controlnet ? "true" : "false") << ","
        << "\"clipSkip\":-1,"
        << "\"controlNetExecutionCount\":" << controlnet_execution_count << ","
        << "\"controlNetResidualTensorCount\":"
        << native_evidence.controlnet_residual_tensor_count << ","
        << "\"controlNetResidualWriteCount\":" << controlnet_residual_write_count << ","
        << "\"controlNetResidualUnetReuseCount\":"
        << native_evidence.controlnet_residual_unet_reuse_count << ","
        << "\"controlNetConditioningBranch\":"
        << quote(native_evidence.controlnet_conditioning_branch) << ","
        << "\"controlNetInputConsumed\":"
        << (native_evidence.controlnet_input_consumed ? "true" : "false") << ","
        << "\"controlNetInputBufferSha256\":"
        << quote(native_evidence.controlnet_input_buffer_sha256) << ","
        << "\"controlNetScaledResidualChecksum\":"
        << controlnet_scaled_residual_checksum << ","
        << "\"controlNetGraph\":" << quote(native_evidence.controlnet_graph_name) << ",";
    if (ultra_fix_request.enabled) {
        out << "\"ultraFix\":" << native_evidence.ultra_fix_json << ","
            << "\"strengthMechanism\":\"ddim_inversion\","
            << "\"outputSizeBytes\":" << native_evidence.ultra_fix_output_bytes << ","
            << "\"outputAtomicCommit\":"
            << (native_evidence.ultra_fix_output_atomic_commit ? "true" : "false") << ","
            << "\"actualDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_total_unet_execution_count << ","
            << "\"actualPositiveDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_positive_diffusion_model_compute_count << ","
            << "\"actualNegativeDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_negative_diffusion_model_compute_count << ","
            << "\"actualAuxiliaryDiffusionModelComputeCount\":"
            << native_evidence.ultra_fix_auxiliary_diffusion_model_compute_count << ",";
    }
    out << "\"nativeEffective\":" << native_effective << ","
        << "\"timesteps\":" << qnn_double_array_json(executed_timesteps) << ","
        << "\"sigmas\":" << qnn_double_array_json(executed_sigmas) << ","
        << "\"initNoiseSigma\":" << scheduler.init_noise_sigma() << ","
        << "\"scaleModelInput\":" << (execution_contract.scheduler.scale_model_input ? "true" : "false") << ","
        << "\"textEncoderExecutionCount\":" << text_encoder_execute_count << ","
        << "\"vaeExecutionCount\":" << final_vae_graph_execution_count << ","
        << "\"finalVaeExecutionCount\":1,"
        << "\"finalVaeGraphExecutionCount\":" << final_vae_graph_execution_count << ","
        << "\"vaeTileCount\":" << final_vae_graph_execution_count << ","
        << "\"vaeTiled\":" << (final_vae_graph_execution_count > 1U ? "true" : "false") << ","
        << "\"previewRequested\":" << (preview_contract.requested ? "true" : "false") << ","
        << "\"previewMode\":" << quote(preview_contract.enabled ? "vae" : "none") << ","
        << "\"previewInterval\":" << preview_contract.interval << ","
        << "\"previewVaeExecutionAttemptCount\":"
        << preview_publisher.audit().vae_execution_attempt_count << ","
        << "\"previewVaeExecutionCount\":"
        << preview_publisher.audit().vae_execution_count << ","
        << "\"previewVaeExecutionMsTotal\":"
        << preview_publisher.audit().vae_execution_ms_total << ","
        << "\"previewPublicationCount\":"
        << preview_publisher.audit().publication_count << ","
        << "\"previewLastStep\":" << preview_publisher.audit().last_step << ","
        << "\"previewLastRevision\":" << preview_publisher.audit().last_revision << ","
        << "\"previewFailureCode\":"
        << quote(preview_publisher.audit().failure_code) << ","
        << "\"previewDegraded\":"
        << (preview_publisher.audit().stopped_after_failure() ? "true" : "false") << ","
        << "\"effectiveVaeHostScale\":" << effective_vae_host_scale << ","
        << "\"nativeGenerationSequence\":" << g_qnn_image_generation_sequence.load() << ","
        << "\"nativeStartedAtMonotonicMs\":" << g_qnn_image_generation_started_ms.load() << ","
        << "\"nativeStageMask\":" << g_qnn_image_generation_stage_mask.load() << ","
        << "\"nativeDetailStageMaskHex\":\""
        << mca::qnn::image_stage_mask_hex(g_qnn_image_generation_detail_stage_mask.load())
        << "\","
        << "\"runtimeSessionMode\":" << quote(native_evidence.runtime_session_mode) << ","
        << "\"message\":" << quote(request_inpaint
            ? (inpaint_contract.requires_masked_latent()
                ? "QNN inpaint completed with source and masked-image VAE encodes, an explicit nine-channel UNet contract, strength-derived denoising, per-step source preservation, final QNN VAE decode, and a full-resolution Laplacian mask blend."
                : "QNN inpaint completed with one source VAE encode, a topology-checked four-channel UNet, strength-derived denoising, per-step source-latent blending with one shared noise tensor, final QNN VAE decode, and a full-resolution Laplacian mask blend.")
            : (request_img2img
            ? "QNN img2img completed with a strict standalone VAE encoder, released encoder context, strength-derived scheduler tail, add-noise, shared QNN UNet, and QNN VAE decoder."
            : (request_controlnet
            ? "QNN control generation completed with verified image preprocessing, one positive-conditioned ControlNet execution per scheduler step, residual reuse across CFG UNet branches, the resolved scheduler, Control-UNet, and QNN VAE decode."
            : (qnn_token_conditioning
                ? "QNN semantic generation completed with QNN CLIP text encoding, the resolved shared scheduler, QNN UNet, and QNN VAE decoder."
                : "QNN semantic generation completed with MNN text embeddings, the resolved shared scheduler, QNN UNet, and QNN VAE decoder.")))) << ","
        << "\"conditioningFormat\":" << quote(conditioning_format) << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
        << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
        << "\"textEncoderContextLoadMs\":" << text_encoder_context_load_ms << ","
        << "\"textEncoderExecuteMsTotal\":" << text_encoder_execute_ms_total << ","
        << "\"textEncoderEmbeddingWidth\":" << text_encoder_embedding_width << ","
        << "\"textEncoderInputTensor\":" << quote(text_encoder_input_tensor) << ","
        << "\"textEncoderInputDataType\":" << quote(text_encoder_input_data_type) << ","
        << "\"textEncoderInputElements\":" << text_encoder_input_elements << ","
        << "\"encoderContextLoadMs\":" << img2img_encoder.context_load_ms << ","
        << "\"encoderExecuteMs\":" << img2img_encoder.execute_ms << ","
        << "\"encoderExecuteMsTotal\":"
        << (img2img_encoder.execute_ms + img2img_encoder.masked_execute_ms) << ","
        << "\"conditioningArtifactConsumed\":" << (conditioning_artifact_consumed ? "true" : "false") << ","
        << "\"controlNetContextLoadMs\":" << (request_controlnet ? controlnet.context_load_ms : 0) << ","
        << "\"controlNetExecuteMsTotal\":" << controlnet_execute_ms_total << ","
        << "\"controlNetExecuteMsAvg\":"
        << (controlnet_execution_count > 0
            ? controlnet_execute_ms_total / static_cast<long long>(controlnet_execution_count)
            : 0) << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
        << "\"unetExecuteMsAvg\":"
        << (unet_execute_average_count > 0U
            ? unet_execute_ms_total /
                static_cast<long long>(unet_execute_average_count)
            : 0) << ","
        << "\"vaeExecuteMs\":" << vae_execute_ms << ","
        << "\"embeddingElements\":" << embeddings.size() << ","
        << "\"textElements\":" << text_elements << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"outputPath\":" << quote(output_path) << ","
        << "\"outputBytes\":" << output_bytes << ","
        << "\"outputSha256\":" << quote(output_sha256) << ","
        << "\"textEncoderGraph\":" << quote(loaded_text_encoder_graph) << ","
        << "\"controlNetLoadedGraph\":" << quote(native_evidence.controlnet_graph_name) << ","
        << "\"unetGraph\":" << quote(unet.graph_name) << ","
        << "\"vaeGraph\":" << quote(vae.graph_name) << ","
        << "\"debug\":{"
        << "\"timestepFirst\":"
        << (executed_timesteps.empty() ? 0.0 : executed_timesteps.front()) << ","
        << "\"timestepLast\":"
        << (executed_timesteps.empty() ? 0.0 : executed_timesteps.back()) << ","
        << "\"textEncoderInputs\":" << text_encoder_inputs_debug << ","
        << "\"textEncoderOutputs\":" << text_encoder_outputs_debug << ","
        << "\"controlNetInputs\":"
        << (request_controlnet ? qnn_tensor_list_debug_json(controlnet.inputs) : "[]") << ","
        << "\"controlNetOutputs\":"
        << (request_controlnet ? qnn_tensor_list_debug_json(controlnet.outputs) : "[]") << ","
        << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
        << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
        << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
        << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
        << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
        << "\"noiseUncondStats\":" << float_vector_stats_json(noise_uncond) << ","
        << "\"noiseCondStats\":" << float_vector_stats_json(noise_cond) << ","
        << "\"pixelStats\":" << float_vector_stats_json(pixels) << "},"
        << "\"executionRuntime\":" << runtime_probe_json(runtime_session.selected_runtime) << ","
        << "\"htpArchVersion\":" << runtime_session.selected_runtime.htp_arch_version << ","
        << "\"bundle\":" << bundle_probe_json(bundle)
        << "}";
    const std::string success_json = out.str();
    const auto release_failure_json = [&](const std::string& component,
                                          const std::string& detail) {
        ::unlink(output_path.c_str());
        return qnn_semantic_failure_json(
            component + "_release_failed",
            "NATIVE_RESOURCE_RELEASE_FAILED",
            detail.empty()
                ? "QNN resources did not release cleanly after final image generation."
                : detail);
    };
    std::string release_error;
    if (!vae.close_checked(&release_error)) {
        return release_failure_json("vae", release_error);
    }
    if (!controlnet.close_checked(&release_error)) {
        return release_failure_json("controlnet", release_error);
    }
    if (!unet.close_checked(&release_error)) {
        return release_failure_json("unet", release_error);
    }
    if (!runtime_session.close_checked(&release_error)) {
        return release_failure_json("runtime", release_error);
    }
    return success_json;
}

std::string qnn_pipeline_probe_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& output_path) {
    if (g_qnn_runtime_poisoned.load()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_resource_release_failed\",\"errorCode\":\"NATIVE_RESOURCE_RELEASE_FAILED\",\"message\":\"This disposable QNN image worker rejected reuse after a native resource release failure.\"}";
    }
    const auto started = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false);
    if (g_qnn_runtime_poisoned.load()) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_resource_release_failed\",\"errorCode\":\"NATIVE_RESOURCE_RELEASE_FAILED\",\"message\":") +
            quote(runtime.message.empty()
                ? "QNN runtime preflight resources did not release cleanly."
                : runtime.message) + "}";
    }
    const auto bundle = inspect_bundle_internal(bundle_root);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    if (!bundle.root_present) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"bundle_missing\",\"message\":\"QNN bundle root is missing.\"}";
    }
    std::string error;
    mca::qnn::ImagePixelRange pixel_range =
        mca::qnn::ImagePixelRange::NegativeOneToOne;
    if (!parse_qnn_pixel_range_contract(params_json, &pixel_range, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"execution_contract_invalid\",\"errorCode\":\"EXECUTION_CONTRACT_INVALID\",\"message\":") +
            quote(error) + "}";
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
    mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
    if (!write_vae_tensor_png(
            vae.outputs[0],
            pixels,
            output_path,
            pixel_range,
            &pixel_range_evidence,
            &width,
            &height,
            &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    const long long output_bytes = file_size_or_zero(output_path);
    std::string output_sha256;
    if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"png_sha256_failed\",\"message\":") +
            quote(error) + "}";
    }
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
        << qnn_pixel_range_evidence_json(pixel_range, pixel_range_evidence) << ","
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
        << "\"outputSha256\":" << quote(output_sha256) << ","
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
    const std::string success_json = out.str();
    const auto release_failure_json = [&](const std::string& component,
                                          const std::string& detail) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"") +
            component + "_release_failed\",\"errorCode\":\"NATIVE_RESOURCE_RELEASE_FAILED\",\"message\":" +
            quote(detail.empty()
                ? "QNN pipeline probe resources did not release cleanly."
                : detail) + "}";
    };
    std::string release_error;
    if (!vae.close_checked(&release_error)) {
        return release_failure_json("vae", release_error);
    }
    if (!unet.close_checked(&release_error)) {
        return release_failure_json("unet", release_error);
    }
    return success_json;
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
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"unet\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_sdxl_encoder_phase_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
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

bool smoke_context_identity_matches(
        const SmokeSpecProbe& smoke_spec,
        const std::string& context_binary_path,
        std::string* error) {
    if (!smoke_spec.context_identity_required) return true;
    const long long actual_size = file_size_or_zero(context_binary_path);
    if (actual_size != smoke_spec.expected_context_size_bytes) {
        std::ostringstream message;
        message << "QNN smoke context size mismatch: expected "
                << smoke_spec.expected_context_size_bytes << ", got " << actual_size << ".";
        *error = message.str();
        return false;
    }
    std::string actual_sha256;
    if (!mca::qnn::controlnet::sha256_hex_file(
            context_binary_path, &actual_sha256, error)) {
        return false;
    }
    if (actual_sha256 != smoke_spec.expected_context_sha256) {
        *error = "QNN smoke context SHA-256 differs from the pinned package identity.";
        return false;
    }
    return true;
}

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
    if (smoke_spec.context_identity_required && !smoke_spec.context_identity_matched) {
        return "context_identity_mismatch";
    }
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
    if (g_qnn_runtime_poisoned.load()) {
        std::ostringstream poisoned;
        poisoned << "{"
            << "\"kind\":" << quote(kind) << ","
            << "\"backend\":\"qnn_htp\","
            << "\"ok\":false,"
            << "\"runnerReady\":false,"
            << "\"graphMetadataReady\":false,"
            << "\"graphRunnerReady\":false,"
            << "\"graphExecute\":false,"
            << "\"npuActive\":false,"
            << "\"smokePassed\":false,"
            << "\"executionStage\":\"runtime_resource_release_failed\","
            << "\"message\":\"This QNN process rejected reuse after a native resource release failure.\""
            << "}";
        return poisoned.str();
    }
    const auto start = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false);
    if (g_qnn_runtime_poisoned.load()) {
        std::ostringstream poisoned;
        poisoned << "{"
            << "\"kind\":" << quote(kind) << ","
            << "\"backend\":\"qnn_htp\","
            << "\"ok\":false,"
            << "\"runnerReady\":false,"
            << "\"graphMetadataReady\":false,"
            << "\"graphRunnerReady\":false,"
            << "\"graphExecute\":false,"
            << "\"npuActive\":false,"
            << "\"smokePassed\":false,"
            << "\"executionStage\":\"runtime_resource_release_failed\","
            << "\"message\":" << quote(runtime.message.empty()
                ? "QNN runtime preflight resources did not release cleanly."
                : runtime.message)
            << "}";
        return poisoned.str();
    }
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
    std::string context_identity_error;
    smoke_spec_with_file.context_identity_matched = context_binary_present &&
        smoke_context_identity_matches(
            smoke_spec_with_file,
            context_binary_path,
            &context_identity_error);
    const bool graph_metadata_ready = runtime.loadable &&
        runtime.qnn_system_interface_present &&
        bundle.root_present &&
        bundle.manifest_present &&
        bundle.has_graph_artifact &&
        context_binary_present &&
        smoke_spec_with_file.context_identity_matched &&
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
    smoke_spec_with_file.metadata_contract_matched = graph_smoke.metadata_contract_matched;
    smoke_spec_with_file.output_values_finite = graph_smoke.output_values_finite;
    smoke_spec_with_file.output_validation_passed = graph_smoke.output_validation_passed;
    smoke_spec_with_file.nonzero_output_elements = graph_smoke.nonzero_output_elements;
    std::string binary_metadata_json_value = "{}";
    bool binary_metadata_release_failed = false;
    std::string binary_metadata_release_message;
#if MCA_WITH_QNN_SDK_HEADERS
    if (context_binary_present && runtime.system_present &&
        !graph_smoke.resource_release_failed) {
        const QnnBinaryMetadata binary_metadata = inspect_qnn_context_binary_metadata(
            runtime, read_binary_file(context_binary_path));
        binary_metadata_release_failed = binary_metadata.release_failed;
        binary_metadata_release_message = binary_metadata.message;
        binary_metadata_json_value = binary_metadata_json(binary_metadata);
    }
#endif
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();

    std::string message;
    const bool graph_runner_ready = graph_smoke.graph_resolved && graph_smoke.tensors_bound;
    const bool graph_execute = graph_smoke.graph_executed;
    const bool smoke_passed = !binary_metadata_release_failed &&
        !graph_smoke.resource_release_failed && graph_smoke.graph_executed &&
        graph_smoke.metadata_contract_matched &&
        graph_smoke.output_validation_passed;
    const bool npu_active = graph_smoke.graph_executed;
    const std::string execution_stage = binary_metadata_release_failed
        ? "runtime_resource_release_failed"
        : (graph_smoke.resource_release_failed
            ? "runtime_resource_release_failed"
        : (graph_smoke.attempted
            ? graph_smoke.execution_stage
            : smoke_execution_stage(runtime, bundle, smoke_spec_with_file, context_binary_present)));
    if (binary_metadata_release_failed) {
        message = binary_metadata_release_message;
    } else if (graph_smoke.resource_release_failed) {
        message = graph_smoke.message.empty()
            ? "QNN graph smoke resources did not release cleanly."
            : graph_smoke.message;
    } else if (!runtime.loadable) {
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
    } else if (smoke_spec_with_file.context_identity_required &&
               !smoke_spec_with_file.context_identity_matched) {
        message = context_identity_error.empty()
            ? "QNN smoke context differs from the pinned package identity."
            : context_identity_error;
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
        << "\"resourceReleaseFailed\":"
        << (graph_smoke.resource_release_failed ? "true" : "false") << ","
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
        if (g_qnn_runtime_poisoned.load()) {
            RuntimeProbe poisoned;
            poisoned.message =
                "This QNN process rejected runtime probing after a native resource release failure.";
            return runtime_probe_json(poisoned);
        }
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
        jstring runtimeProfileJson,
        jstring paramsJson,
        jstring embeddingsPath,
        jstring initialLatentPath,
        jstring latentPath) noexcept {
    return qnn_jni_json_guard(env, "runSdxlUnetPhase", [&]() {
        return qnn_sdxl_unet_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeProfileJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, embeddingsPath),
            jstring_to_std(env, initialLatentPath),
            jstring_to_std(env, latentPath));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlEncoderPhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeProfileJson,
        jstring paramsJson,
        jstring inputTensorPath,
        jstring latentPath,
        jstring expectedVaeEncoderContextSha256) noexcept {
    return qnn_jni_json_guard(env, "runSdxlEncoderPhase", [&]() {
        return qnn_sdxl_encoder_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeProfileJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, inputTensorPath),
            jstring_to_std(env, latentPath),
            jstring_to_std(env, expectedVaeEncoderContextSha256));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlVaePhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeProfileJson,
        jstring paramsJson,
        jstring latentPath,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runSdxlVaePhase", [&]() {
        return qnn_sdxl_vae_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeProfileJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, latentPath),
            jstring_to_std(env, outputPath));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_cancelImageGeneration(JNIEnv*, jobject) noexcept {
    return qnn_jni_boolean_guard("cancelImageGeneration", []() {
        if (!g_qnn_image_generation_active.load()) return false;
        {
            std::lock_guard<std::mutex> publish_lock(g_qnn_image_preview_publish_mutex);
            g_qnn_image_generation_cancel_requested.store(true);
            g_qnn_image_generation_phase.store(kQnnImageCancelling);
            persist_qnn_image_generation_journal();
        }
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
