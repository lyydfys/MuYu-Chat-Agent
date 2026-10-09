#ifndef MCA_SD_PREVIEW_ENCODER_HPP
#define MCA_SD_PREVIEW_ENCODER_HPP

#include <atomic>
#include <cstdint>
#include <string>

#include "json.hpp"
#include "stable-diffusion.h"

namespace mca {
namespace sd {

/**
 * Maps contract preview mode string ("projection", "tae", "vae")
 * to the native stable-diffusion.cpp preview_t enumeration.
 */
preview_t preview_mode_from_contract(const std::string &mode);

/**
 * Context payload passed to the native preview callback.
 * Tracks publication counts, steps, and revisions.
 */
struct PreviewPublicationContext {
    std::string output_path;
    std::string mode;
    int interval = 0;
    int total_steps = 0;
    int publication_count = 0;
    int last_step = 0;
    uint64_t last_revision = 0;
};

/**
 * Global preview revision counter accessor.
 */
std::atomic<uint64_t>& preview_revision_counter();

/**
 * Native callback invoked by stable-diffusion.cpp after sampling steps
 * to publish intermediate preview frames.
 *
 * Implements atomic two-slot frame rotation:
 * writes <output_path>.preview-<revision % 2>.png via a temporary .part file.
 */
void sd_preview_callback(int step,
                         int frame_count,
                         sd_image_t *frames,
                         bool is_noisy,
                         void *data);

/**
 * RAII scope guard to ensure the preview callback is safely registered
 * on context and cleaned up on scope exit (success, failure, or cancellation).
 */
class SdPreviewScope {
public:
    SdPreviewScope(PreviewPublicationContext *context,
                   preview_t mode,
                   int interval);
    ~SdPreviewScope();

    SdPreviewScope(const SdPreviewScope &) = delete;
    SdPreviewScope &operator=(const SdPreviewScope &) = delete;
    SdPreviewScope(SdPreviewScope &&) noexcept;
    SdPreviewScope &operator=(SdPreviewScope &&) noexcept;

    void reset();

private:
    bool active_{false};
};

/**
 * Verifies that if preview generation was requested, at least one preview frame
 * was successfully published.
 */
bool verify_preview_publication(preview_t mode,
                                const PreviewPublicationContext &context,
                                std::string &error_field,
                                std::string &error_message);

/**
 * Populates preview-related metadata fields into the generation output JSON.
 */
void populate_preview_response_json(nlohmann::json &out,
                                    preview_t native_preview_mode,
                                    const std::string &contract_preview_mode,
                                    int preview_interval,
                                    const PreviewPublicationContext &context);

} // namespace sd
} // namespace mca

#endif // MCA_SD_PREVIEW_ENCODER_HPP
