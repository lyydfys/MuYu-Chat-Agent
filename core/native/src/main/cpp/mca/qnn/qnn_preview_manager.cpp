#include "qnn_preview_manager.hpp"

#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <algorithm>
#include <chrono>
#include <limits>
#include <sstream>

namespace mca::qnn {

namespace {

constexpr const char* kTag = "MCA-QNN-Preview";

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

} // namespace

std::atomic<bool> g_qnn_image_generation_active{false};
std::atomic<bool> g_qnn_image_generation_cancel_requested{false};
std::atomic<int> g_qnn_image_generation_phase{kQnnImageIdle};
std::atomic<int> g_qnn_image_generation_step{0};
std::atomic<int> g_qnn_image_generation_steps{0};
std::atomic<long long> g_qnn_image_generation_started_ms{0};
std::atomic<long long> g_qnn_image_generation_sequence{0};
std::atomic<uint32_t> g_qnn_image_generation_stage_mask{0};
std::atomic<uint64_t> g_qnn_image_generation_detail_stage_mask{0};
std::mutex g_qnn_image_journal_mutex;
std::string g_qnn_image_journal_path;
std::mutex g_qnn_image_preview_publish_mutex;
std::mutex g_qnn_image_preview_state_mutex;
QnnImagePreviewProgress g_qnn_image_preview_progress;

// The single definition of the process-wide QNN context-execution mutex.
//
// The pre-refactor bridge defined this in its anonymous namespace; now that the users live in
// several translation units it must be a namespace-scope object with external linkage so that the
// bridge, the semantic pipeline and the SDXL phase headers all lock the *same* mutex.  Do not make
// this `static`/per-TU and do not move it back into a header.
std::timed_mutex g_qnn_context_execution_mutex;

long long monotonic_millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

long long monotonic_sequence_seed() {
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

void qnn_image_generation_set_phase(QnnImageGenerationPhase phase) {
    g_qnn_image_generation_phase.store(phase);
    const uint32_t bit = qnn_image_generation_stage_bit(phase);
    if (bit != 0U) g_qnn_image_generation_stage_mask.fetch_or(bit);
    persist_qnn_image_generation_journal();
}

void qnn_image_generation_record_stage(
        ImageStage stage,
        QnnImageGenerationPhase phase) {
    g_qnn_image_generation_phase.store(phase);
    const uint32_t coarse_bit = qnn_image_generation_stage_bit(phase);
    if (coarse_bit != 0U) g_qnn_image_generation_stage_mask.fetch_or(coarse_bit);
    g_qnn_image_generation_detail_stage_mask.fetch_or(image_stage_bit(stage));
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "QNN image stage: %s",
        image_stage_name(stage));
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

QnnImageGenerationScope::QnnImageGenerationScope(int steps, const std::string& journal_path)
    : active_(qnn_image_generation_try_begin(steps, journal_path)) {}

QnnImageGenerationScope::~QnnImageGenerationScope() {
    if (active_) qnn_image_generation_end();
}

void QnnImageGenerationScope::set_phase(QnnImageGenerationPhase phase) const {
    if (active_) qnn_image_generation_set_phase(phase);
}

void QnnImageGenerationScope::record_stage(ImageStage stage, QnnImageGenerationPhase phase) const {
    if (active_) qnn_image_generation_record_stage(stage, phase);
}

void QnnImageGenerationScope::set_steps(int steps) const {
    if (active_) {
        g_qnn_image_generation_steps.store(std::max(1, steps));
        persist_qnn_image_generation_journal();
    }
}

void QnnImageGenerationScope::set_step(int step) const {
    if (active_) {
        g_qnn_image_generation_step.store(std::max(0, step));
        persist_qnn_image_generation_journal();
    }
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
        for (const auto& name : image_stage_names(detail_stage_mask)) {
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

} // namespace mca::qnn
