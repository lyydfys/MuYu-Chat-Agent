#include "mca/sd/sd_preview_encoder.hpp"

#include <cstdio>
#include <limits>
#include <utility>

#include "stb_image_write.h"
#include "mca/sd/sd_progress_reporter.hpp"

namespace mca {
namespace sd {

namespace {
std::atomic<uint64_t> g_preview_revision{0};
} // namespace

std::atomic<uint64_t>& preview_revision_counter() {
    return g_preview_revision;
}

preview_t preview_mode_from_contract(const std::string &mode) {
    if (mode == "projection") return PREVIEW_PROJ;
    if (mode == "tae") return PREVIEW_TAE;
    if (mode == "vae") return PREVIEW_VAE;
    return PREVIEW_NONE;
}

void sd_preview_callback(int step,
                         int frame_count,
                         sd_image_t *frames,
                         bool is_noisy,
                         void *data) {
    auto *context = static_cast<PreviewPublicationContext *>(data);
    if (context == nullptr || context->interval <= 0 || frame_count <= 0 || frames == nullptr) {
        return;
    }
    const int normalized_step = step < 0 ? -step : step;
    if (normalized_step <= 0 ||
        (normalized_step % context->interval != 0 && normalized_step != context->total_steps)) {
        return;
    }
    const sd_image_t &frame = frames[0];
    if (frame.data == nullptr || frame.width == 0u || frame.height == 0u ||
        frame.channel == 0u || frame.channel > 4u ||
        frame.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        frame.height > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        frame.width > static_cast<uint32_t>(
            std::numeric_limits<int>::max() / static_cast<int>(frame.channel))) {
        return;
    }

    const uint64_t revision =
            g_preview_revision.fetch_add(1u, std::memory_order_relaxed) + 1u;
    const std::string target = context->output_path +
            ".preview-" + std::to_string(revision % 2u) + ".png";
    const std::string partial = target + ".part";
    std::remove(partial.c_str());
    const int stride = static_cast<int>(frame.width * frame.channel);
    if (stbi_write_png(
            partial.c_str(),
            static_cast<int>(frame.width),
            static_cast<int>(frame.height),
            static_cast<int>(frame.channel),
            frame.data,
            stride) == 0) {
        std::remove(partial.c_str());
        return;
    }
    if (std::rename(partial.c_str(), target.c_str()) != 0) {
        std::remove(partial.c_str());
        return;
    }

    context->publication_count += 1;
    context->last_step = normalized_step;
    context->last_revision = revision;
    mca::sd::set_progress_preview(
            target,
            "image/png",
            context->mode,
            normalized_step,
            revision,
            static_cast<int>(frame.width),
            static_cast<int>(frame.height),
            frame_count,
            is_noisy);
}

SdPreviewScope::SdPreviewScope(PreviewPublicationContext *context,
                               preview_t mode,
                               int interval) {
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
    if (mode != PREVIEW_NONE && context != nullptr && interval > 0) {
        sd_set_preview_callback(
                sd_preview_callback,
                mode,
                interval,
                true,
                false,
                context);
        active_ = true;
    }
}

SdPreviewScope::~SdPreviewScope() {
    reset();
}

SdPreviewScope::SdPreviewScope(SdPreviewScope &&other) noexcept
        : active_(other.active_) {
    other.active_ = false;
}

SdPreviewScope &SdPreviewScope::operator=(SdPreviewScope &&other) noexcept {
    if (this != &other) {
        reset();
        active_ = other.active_;
        other.active_ = false;
    }
    return *this;
}

void SdPreviewScope::reset() {
    if (active_) {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
        active_ = false;
    }
}

bool verify_preview_publication(preview_t mode,
                                const PreviewPublicationContext &context,
                                std::string &error_field,
                                std::string &error_message) {
    if (mode != PREVIEW_NONE && context.publication_count <= 0) {
        error_field = "preview";
        error_message = "stable-diffusion.cpp did not publish a real preview frame for the requested mode";
        return false;
    }
    return true;
}

void populate_preview_response_json(nlohmann::json &out,
                                    preview_t native_preview_mode,
                                    const std::string &contract_preview_mode,
                                    int preview_interval,
                                    const PreviewPublicationContext &context) {
    out["previewRequested"] = native_preview_mode != PREVIEW_NONE;
    out["previewMode"] = native_preview_mode == PREVIEW_NONE
            ? "none"
            : contract_preview_mode;
    out["previewInterval"] = preview_interval;
    out["previewPublicationCount"] = context.publication_count;
    out["previewLastStep"] = context.last_step;
    out["previewLastRevision"] = context.last_revision;
}

} // namespace sd
} // namespace mca
