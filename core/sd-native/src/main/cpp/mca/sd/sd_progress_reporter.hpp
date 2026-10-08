#ifndef MCA_SD_PROGRESS_REPORTER_HPP
#define MCA_SD_PROGRESS_REPORTER_HPP

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>

namespace mca {
namespace sd {

/**
 * Generation execution pipeline stages bitmask flags.
 */
constexpr uint32_t kStageContractValidated = 1u << 0u;
constexpr uint32_t kStageContextReady = 1u << 1u;
constexpr uint32_t kStageGenerationInvoked = 1u << 2u;
constexpr uint32_t kStageSamplingObserved = 1u << 3u;
constexpr uint32_t kStageImageReturned = 1u << 4u;
constexpr uint32_t kStageOutputWritten = 1u << 5u;
constexpr uint32_t kStageContextReleased = 1u << 6u;
constexpr uint32_t kStageInputImageDecoded = 1u << 7u;
constexpr uint32_t kStageMaskImageDecoded = 1u << 8u;
constexpr uint32_t kStageControlImageDecoded = 1u << 9u;
constexpr uint32_t kStageLoraValidated = 1u << 10u;
constexpr uint32_t kStageTextualInversionValidated = 1u << 11u;
constexpr uint32_t kStageQwenTextConditionEncoded = 1u << 12u;
constexpr uint32_t kStageQwenTextContextReleased = 1u << 13u;
constexpr uint32_t kStageQwenDiffusionContextReady = 1u << 14u;

/**
 * Snapshot representation of the active or completed progress state.
 */
struct ProgressState {
    int step = 0;
    int steps = 0;
    float seconds_per_step = 0.0f;
    long long started_ms = 0;
    long long updated_ms = 0;
    int width = 0;
    int height = 0;
    int threads = 0;
    std::string phase = "idle";
    std::string message;
    std::string component_selection_json = "{}";
    std::string preview_path;
    std::string preview_mime_type;
    std::string preview_mode;
    int preview_step = 0;
    uint64_t preview_revision = 0;
    int preview_width = 0;
    int preview_height = 0;
    int preview_frame_count = 0;
    bool preview_noisy = false;
};

/**
 * Returns current monotonic clock timestamp in milliseconds.
 */
[[nodiscard]] long long progress_now_ms() noexcept;

/**
 * Thread-safe progress reporter and state machine coordinating generation stages,
 * timing, UI step callbacks, preview publication metadata, and execution evidence.
 */
class SdProgressReporter {
public:
    static SdProgressReporter& instance() noexcept;

    SdProgressReporter();
    ~SdProgressReporter() = default;

    SdProgressReporter(const SdProgressReporter&) = delete;
    SdProgressReporter& operator=(const SdProgressReporter&) = delete;
    SdProgressReporter(SdProgressReporter&&) = delete;
    SdProgressReporter& operator=(SdProgressReporter&&) = delete;

    // Stage machine
    void mark_stage(uint32_t stage) noexcept;
    [[nodiscard]] uint32_t get_stage_mask() const noexcept;
    [[nodiscard]] bool has_stage(uint32_t stage) const noexcept;

    // Evidence and sequence lifecycle
    void reset_evidence(int default_observed_prediction = 6) noexcept;
    void set_active_sequence(uint64_t sequence) noexcept;
    [[nodiscard]] uint64_t get_active_sequence() const noexcept;

    void set_observed_prediction(int prediction) noexcept;
    [[nodiscard]] int get_observed_prediction() const noexcept;

    void record_ui_progress_step(int step, int steps) noexcept;
    [[nodiscard]] int get_ui_progress_callback_count() const noexcept;
    [[nodiscard]] int get_ui_progress_reported_steps() const noexcept;
    [[nodiscard]] int get_ui_progress_max_step() const noexcept;

    // Direct atomic references for zero-overhead bridge interop
    [[nodiscard]] std::atomic<uint64_t>& active_generation_sequence() noexcept {
        return active_sequence_;
    }
    [[nodiscard]] std::atomic<uint32_t>& generation_stage_mask() noexcept {
        return stage_mask_;
    }
    [[nodiscard]] std::atomic<int>& observed_prediction() noexcept {
        return observed_prediction_;
    }
    [[nodiscard]] std::atomic<int>& ui_progress_callback_count() noexcept {
        return ui_callback_count_;
    }
    [[nodiscard]] std::atomic<int>& ui_progress_reported_steps() noexcept {
        return ui_reported_steps_;
    }
    [[nodiscard]] std::atomic<int>& ui_progress_max_step() noexcept {
        return ui_max_step_;
    }

    // Progress updates
    void reset_progress() noexcept;
    void set_progress(
        const std::string& phase,
        const std::string& message,
        int step = 0,
        int steps = 0,
        float seconds_per_step = 0.0f,
        int width = 0,
        int height = 0,
        int threads = 0
    );
    void set_stage(const std::string& phase, const std::string& message);
    void set_component_selection(const std::string& component_selection_json);
    void set_preview(
        const std::string& preview_path,
        const std::string& preview_mime_type,
        const std::string& preview_mode,
        int preview_step,
        uint64_t preview_revision,
        int preview_width,
        int preview_height,
        int preview_frame_count,
        bool preview_noisy
    );
    [[nodiscard]] ProgressState get_snapshot() const;

private:
    mutable std::mutex progress_mutex_;
    ProgressState progress_;

    std::atomic<uint64_t> active_sequence_{0};
    std::atomic<uint32_t> stage_mask_{0};
    std::atomic<int> observed_prediction_{6};
    std::atomic<int> ui_callback_count_{0};
    std::atomic<int> ui_reported_steps_{0};
    std::atomic<int> ui_max_step_{0};
};

// Convenience delegating free functions
void mark_generation_stage(uint32_t stage) noexcept;
void reset_generation_evidence(int default_observed_prediction = 6) noexcept;
void reset_progress_state() noexcept;
void set_progress(
    const std::string& phase,
    const std::string& message,
    int step = 0,
    int steps = 0,
    float seconds_per_step = 0.0f,
    int width = 0,
    int height = 0,
    int threads = 0
);
void set_progress_stage(const std::string& phase, const std::string& message);
void set_progress_component_selection(const std::string& component_selection_json);
void set_progress_preview(
    const std::string& preview_path,
    const std::string& preview_mime_type,
    const std::string& preview_mode,
    int preview_step,
    uint64_t preview_revision,
    int preview_width,
    int preview_height,
    int preview_frame_count,
    bool preview_noisy
);
[[nodiscard]] ProgressState get_progress_snapshot();

[[nodiscard]] std::atomic<uint64_t>& active_generation_sequence() noexcept;
[[nodiscard]] std::atomic<uint32_t>& generation_stage_mask() noexcept;
[[nodiscard]] std::atomic<int>& observed_prediction() noexcept;
[[nodiscard]] std::atomic<int>& ui_progress_callback_count() noexcept;
[[nodiscard]] std::atomic<int>& ui_progress_reported_steps() noexcept;
[[nodiscard]] std::atomic<int>& ui_progress_max_step() noexcept;

} // namespace sd
} // namespace mca

#endif // MCA_SD_PROGRESS_REPORTER_HPP
