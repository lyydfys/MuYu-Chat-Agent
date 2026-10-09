#include "mca/sd/sd_inpaint_processor.hpp"

#include <algorithm>
#include <cmath>

namespace mca {
namespace sd {

bool load_and_validate_inpaint_mask(
    const StableDiffusionExecutionContract &contract,
    const LoadedInputImage &input_image,
    LoadedInputImage &mask_image,
    std::string &error_code,
    std::string &error_message) {
    if (contract.mask_image_path.empty()) {
        return true;
    }
    std::string input_error;
    if (!load_canonical_input_image(
            contract.mask_image_path,
            contract.mask_image_sha256,
            1,
            mask_image,
            input_error)) {
        error_code = "MASK_IMAGE_INVALID";
        error_message = input_error;
        return false;
    }
    if (!input_image.pixels.empty() && !mask_image.pixels.empty() &&
        (input_image.width != mask_image.width || input_image.height != mask_image.height)) {
        error_code = "MASK_IMAGE_SHAPE_MISMATCH";
        error_message = "inpaint mask dimensions must match the input image dimensions";
        return false;
    }
    return true;
}

void configure_inpaint_params(
    const LoadedInputImage &mask_image,
    sd_img_gen_params_t &gen) {
    if (!mask_image.pixels.empty()) {
        gen.mask_image = mask_image.view();
    }
}

bool verify_inpaint_wiring(
    const StableDiffusionExecutionContract &contract,
    const sd_img_gen_params_t &gen,
    bool &mask_image_wired_out,
    std::string &error_field,
    std::string &error_message) {
    const bool uses_mask = task_uses_mask_image(contract);
    mask_image_wired_out = uses_mask &&
            gen.mask_image.data != nullptr &&
            gen.mask_image.width > 0u &&
            gen.mask_image.height > 0u;
    if (uses_mask != mask_image_wired_out) {
        error_field = "maskImagePath";
        error_message = "the prepared mask image was not wired into native denoise-mask generation";
        return false;
    }
    return true;
}

void populate_inpaint_effective_json(
    nlohmann::json &native_effective,
    bool mask_image_wired,
    const LoadedInputImage &mask_image) {
    native_effective["maskImagePath"] = mask_image_wired ? mask_image.canonical_path : "";
    native_effective["maskImageExecutionCount"] = mask_image_wired ? 1 : 0;
    native_effective["maskImageSha256"] = mask_image.sha256;
}

void populate_inpaint_response_json(
    nlohmann::json &out,
    bool mask_image_wired,
    const LoadedInputImage &mask_image) {
    out["maskImagePath"] = mask_image_wired ? mask_image.canonical_path : "";
    out["maskImageExecutionCount"] = mask_image_wired ? 1 : 0;
    out["maskImageSha256"] = mask_image.sha256;
    out["maskImageSourceWidth"] = mask_image.source_width;
    out["maskImageSourceHeight"] = mask_image.source_height;
    out["maskImageSourceChannels"] = mask_image.source_channels;
    out["maskImageWidth"] = mask_image.width;
    out["maskImageHeight"] = mask_image.height;
    out["maskImageChannels"] = mask_image.channels;
    out["maskImageExifOrientation"] = mask_image.exif_orientation;
}

void expand_mask_region(std::vector<uint8_t> &mask,
                        uint32_t width,
                        uint32_t height,
                        int radius) {
    if (mask.empty() || radius <= 0 || width == 0u || height == 0u) {
        return;
    }
    const std::vector<uint8_t> original = mask;
    const int w = static_cast<int>(width);
    const int h = static_cast<int>(height);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (original[static_cast<size_t>(y * w + x)] > 128u) {
                const int y_min = std::max(0, y - radius);
                const int y_max = std::min(h - 1, y + radius);
                const int x_min = std::max(0, x - radius);
                const int x_max = std::min(w - 1, x + radius);
                for (int ny = y_min; ny <= y_max; ++ny) {
                    for (int nx = x_min; nx <= x_max; ++nx) {
                        mask[static_cast<size_t>(ny * w + nx)] = 255u;
                    }
                }
            }
        }
    }
}

void feather_mask_edges(std::vector<uint8_t> &mask,
                        uint32_t width,
                        uint32_t height,
                        int radius) {
    if (mask.empty() || radius <= 0 || width == 0u || height == 0u) {
        return;
    }
    const int w = static_cast<int>(width);
    const int h = static_cast<int>(height);
    std::vector<uint8_t> temp = mask;

    // Horizontal box pass
    for (int y = 0; y < h; ++y) {
        int sum = 0;
        int count = 0;
        for (int x = 0; x <= std::min(radius, w - 1); ++x) {
            sum += mask[static_cast<size_t>(y * w + x)];
            count++;
        }
        for (int x = 0; x < w; ++x) {
            temp[static_cast<size_t>(y * w + x)] = static_cast<uint8_t>(sum / count);
            const int next_add = x + radius + 1;
            if (next_add < w) {
                sum += mask[static_cast<size_t>(y * w + next_add)];
                count++;
            }
            const int prev_sub = x - radius;
            if (prev_sub >= 0) {
                sum -= mask[static_cast<size_t>(y * w + prev_sub)];
                count--;
            }
        }
    }

    // Vertical box pass
    for (int x = 0; x < w; ++x) {
        int sum = 0;
        int count = 0;
        for (int y = 0; y <= std::min(radius, h - 1); ++y) {
            sum += temp[static_cast<size_t>(y * w + x)];
            count++;
        }
        for (int y = 0; y < h; ++y) {
            mask[static_cast<size_t>(y * w + x)] = static_cast<uint8_t>(sum / count);
            const int next_add = y + radius + 1;
            if (next_add < h) {
                sum += temp[static_cast<size_t>(next_add * w + x)];
                count++;
            }
            const int prev_sub = y - radius;
            if (prev_sub >= 0) {
                sum -= temp[static_cast<size_t>(prev_sub * w + x)];
                count--;
            }
        }
    }
}

void blend_inpaint_composite(uint8_t *target,
                             const uint8_t *original,
                             const uint8_t *inpaint,
                             const uint8_t *mask,
                             uint32_t width,
                             uint32_t height,
                             uint32_t channels) {
    if (target == nullptr || original == nullptr || inpaint == nullptr || mask == nullptr) {
        return;
    }
    const size_t total_pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    for (size_t i = 0; i < total_pixels; ++i) {
        const float alpha = static_cast<float>(mask[i]) / 255.0f;
        const float inv_alpha = 1.0f - alpha;
        const size_t pixel_offset = i * channels;
        for (uint32_t c = 0; c < channels; ++c) {
            const float blended = inv_alpha * static_cast<float>(original[pixel_offset + c]) +
                                  alpha * static_cast<float>(inpaint[pixel_offset + c]);
            target[pixel_offset + c] = static_cast<uint8_t>(std::clamp(blended, 0.0f, 255.0f));
        }
    }
}

} // namespace sd
} // namespace mca
