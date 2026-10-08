#ifndef MCA_SD_RUNTIME_DIAGNOSTICS_HPP
#define MCA_SD_RUNTIME_DIAGNOSTICS_HPP

#include <atomic>
#include <string>

#include "mca/sd/sd_progress_reporter.hpp"
#include "stable-diffusion.h"

namespace mca {
namespace sd {

/**
 * References to active generation and cancellation atomic flags.
 */
[[nodiscard]] std::atomic<bool>& generation_active() noexcept;
[[nodiscard]] std::atomic<bool>& cancel_requested() noexcept;

/**
 * Stable diffusion runtime error tracking accessors.
 */
[[nodiscard]] std::string get_last_sd_error();
[[nodiscard]] std::string& last_sd_error_storage() noexcept;
void set_last_sd_error(const std::string& error);
void clear_last_sd_error();

/**
 * Returns canonical execution backend label ("cpu").
 */
[[nodiscard]] const char* sd_runtime_backend_label() noexcept;

/**
 * Formats generation progress snapshot as a JSON payload.
 */
[[nodiscard]] std::string progress_to_json();
[[nodiscard]] std::string progress_to_json(bool active, bool cancelled);

/**
 * Formats native runtime platform capabilities and hardware configuration as JSON.
 */
[[nodiscard]] std::string runtime_config_json();

/**
 * Analyzes log text emitted by stable-diffusion.cpp and updates prediction
 * mode and progress stages accordingly.
 */
void set_progress_from_sd_log(const char* text);

/**
 * Maps raw stable-diffusion errors and warnings to sanitized safe messages.
 */
[[nodiscard]] std::string safe_sd_runtime_message(sd_log_level_t level, const char* text);

/**
 * Native log callback compatible with stable-diffusion.cpp sd_log_cb_t.
 */
void sd_log_callback(sd_log_level_t level, const char* text, void* data = nullptr);

} // namespace sd
} // namespace mca

#endif // MCA_SD_RUNTIME_DIAGNOSTICS_HPP
