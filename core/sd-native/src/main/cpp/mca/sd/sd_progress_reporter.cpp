#include "sd_progress_reporter.hpp"

#include <chrono>

namespace mca {
namespace sd {

long long progress_now_ms() noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

SdProgressReporter& SdProgressReporter::instance() noexcept {
    static SdProgressReporter s_instance;
    return s_instance;
}

SdProgressReporter::SdProgressReporter()
    : active_sequence_(0),
      stage_mask_(0),
      observed_prediction_(6),
      ui_callback_count_(0),
      ui_reported_steps_(0),
      ui_max_step_(0) {}

void SdProgressReporter::mark_stage(uint32_t stage) noexcept {
    stage_mask_.fetch_or(stage, std::memory_order_relaxed);
}

uint32_t SdProgressReporter::get_stage_mask() const noexcept {
    return stage_mask_.load(std::memory_order_relaxed);
}

bool SdProgressReporter::has_stage(uint32_t stage) const noexcept {
    return (stage_mask_.load(std::memory_order_relaxed) & stage) != 0u;
}

void SdProgressReporter::reset_evidence(int default_observed_prediction) noexcept {
    active_sequence_.store(0, std::memory_order_relaxed);
    stage_mask_.store(0, std::memory_order_relaxed);
    observed_prediction_.store(default_observed_prediction, std::memory_order_relaxed);
    ui_callback_count_.store(0, std::memory_order_relaxed);
    ui_reported_steps_.store(0, std::memory_order_relaxed);
    ui_max_step_.store(0, std::memory_order_relaxed);
}

void SdProgressReporter::set_active_sequence(uint64_t sequence) noexcept {
    active_sequence_.store(sequence, std::memory_order_relaxed);
}

uint64_t SdProgressReporter::get_active_sequence() const noexcept {
    return active_sequence_.load(std::memory_order_relaxed);
}

void SdProgressReporter::set_observed_prediction(int prediction) noexcept {
    observed_prediction_.store(prediction, std::memory_order_relaxed);
}

int SdProgressReporter::get_observed_prediction() const noexcept {
    return observed_prediction_.load(std::memory_order_relaxed);
}

void SdProgressReporter::record_ui_progress_step(int step, int steps) noexcept {
    ui_callback_count_.fetch_add(1, std::memory_order_relaxed);
    ui_reported_steps_.store(steps, std::memory_order_relaxed);
    int observed = ui_max_step_.load(std::memory_order_relaxed);
    while (step > observed &&
           !ui_max_step_.compare_exchange_weak(
                   observed,
                   step,
                   std::memory_order_relaxed)) {
    }
}

int SdProgressReporter::get_ui_progress_callback_count() const noexcept {
    return ui_callback_count_.load(std::memory_order_relaxed);
}

int SdProgressReporter::get_ui_progress_reported_steps() const noexcept {
    return ui_reported_steps_.load(std::memory_order_relaxed);
}

int SdProgressReporter::get_ui_progress_max_step() const noexcept {
    return ui_max_step_.load(std::memory_order_relaxed);
}

void SdProgressReporter::reset_progress() noexcept {
    std::lock_guard<std::mutex> lock(progress_mutex_);
    progress_ = ProgressState{};
}

void SdProgressReporter::set_progress(
    const std::string& phase,
    const std::string& message,
    int step,
    int steps,
    float seconds_per_step,
    int width,
    int height,
    int threads
) {
    std::lock_guard<std::mutex> lock(progress_mutex_);
    progress_.phase = phase;
    progress_.message = message;
    progress_.step = step;
    progress_.steps = steps;
    progress_.seconds_per_step = seconds_per_step;
    progress_.updated_ms = progress_now_ms();
    if (width > 0) progress_.width = width;
    if (height > 0) progress_.height = height;
    if (threads > 0) progress_.threads = threads;
    if (progress_.started_ms == 0 || phase == "initializing") {
        progress_.started_ms = progress_.updated_ms;
    }
}

void SdProgressReporter::set_stage(const std::string& phase, const std::string& message) {
    std::lock_guard<std::mutex> lock(progress_mutex_);
    progress_.phase = phase;
    progress_.message = message;
    progress_.updated_ms = progress_now_ms();
}

void SdProgressReporter::set_component_selection(const std::string& component_selection_json) {
    std::lock_guard<std::mutex> lock(progress_mutex_);
    progress_.component_selection_json = component_selection_json.empty()
                                          ? "{}"
                                          : component_selection_json;
    progress_.updated_ms = progress_now_ms();
}

void SdProgressReporter::set_preview(
    const std::string& preview_path,
    const std::string& preview_mime_type,
    const std::string& preview_mode,
    int preview_step,
    uint64_t preview_revision,
    int preview_width,
    int preview_height,
    int preview_frame_count,
    bool preview_noisy
) {
    std::lock_guard<std::mutex> lock(progress_mutex_);
    progress_.preview_path = preview_path;
    progress_.preview_mime_type = preview_mime_type;
    progress_.preview_mode = preview_mode;
    progress_.preview_step = preview_step;
    progress_.preview_revision = preview_revision;
    progress_.preview_width = preview_width;
    progress_.preview_height = preview_height;
    progress_.preview_frame_count = preview_frame_count;
    progress_.preview_noisy = preview_noisy;
    progress_.updated_ms = progress_now_ms();
}

ProgressState SdProgressReporter::get_snapshot() const {
    std::lock_guard<std::mutex> lock(progress_mutex_);
    return progress_;
}

void mark_generation_stage(uint32_t stage) noexcept {
    SdProgressReporter::instance().mark_stage(stage);
}

void reset_generation_evidence(int default_observed_prediction) noexcept {
    SdProgressReporter::instance().reset_evidence(default_observed_prediction);
}

void reset_progress_state() noexcept {
    SdProgressReporter::instance().reset_progress();
}

void set_progress(
    const std::string& phase,
    const std::string& message,
    int step,
    int steps,
    float seconds_per_step,
    int width,
    int height,
    int threads
) {
    SdProgressReporter::instance().set_progress(
        phase, message, step, steps, seconds_per_step, width, height, threads);
}

void set_progress_stage(const std::string& phase, const std::string& message) {
    SdProgressReporter::instance().set_stage(phase, message);
}

void set_progress_component_selection(const std::string& component_selection_json) {
    SdProgressReporter::instance().set_component_selection(component_selection_json);
}

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
) {
    SdProgressReporter::instance().set_preview(
        preview_path,
        preview_mime_type,
        preview_mode,
        preview_step,
        preview_revision,
        preview_width,
        preview_height,
        preview_frame_count,
        preview_noisy
    );
}

ProgressState get_progress_snapshot() {
    return SdProgressReporter::instance().get_snapshot();
}

std::atomic<uint64_t>& active_generation_sequence() noexcept {
    return SdProgressReporter::instance().active_generation_sequence();
}

std::atomic<uint32_t>& generation_stage_mask() noexcept {
    return SdProgressReporter::instance().generation_stage_mask();
}

std::atomic<int>& observed_prediction() noexcept {
    return SdProgressReporter::instance().observed_prediction();
}

std::atomic<int>& ui_progress_callback_count() noexcept {
    return SdProgressReporter::instance().ui_progress_callback_count();
}

std::atomic<int>& ui_progress_reported_steps() noexcept {
    return SdProgressReporter::instance().ui_progress_reported_steps();
}

std::atomic<int>& ui_progress_max_step() noexcept {
    return SdProgressReporter::instance().ui_progress_max_step();
}

} // namespace sd
} // namespace mca
