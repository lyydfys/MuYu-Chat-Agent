#ifndef MCA_SD_INPAINT_PROCESSOR_HPP
#define MCA_SD_INPAINT_PROCESSOR_HPP

#include <cstdint>
#include <string>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "mca/sd/sd_contracts.hpp"
#include "mca/sd/sd_contract_parser.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_input_contract.hpp"

namespace mca {
namespace sd {

/**
 * Universal device admission policy:
 * Inpaint tasks are validated solely on image parameters, dimensional consistency,
 * and canonical file paths. No device/chipset admission allowlists are permitted.
 */
bool load_and_validate_inpaint_mask(
    const StableDiffusionExecutionContract &contract,
    const LoadedInputImage &input_image,
    LoadedInputImage &mask_image,
    std::string &error_code,
    std::string &error_message);

/**
 * Wires the loaded mask image into the native generator parameters.
 */
void configure_inpaint_params(
    const LoadedInputImage &mask_image,
    sd_img_gen_params_t &gen);

/**
 * Verifies that the native generator parameters correctly reflect the contract's
 * intent to execute inpainting with a denoise mask.
 */
bool verify_inpaint_wiring(
    const StableDiffusionExecutionContract &contract,
    const sd_img_gen_params_t &gen,
    bool &mask_image_wired_out,
    std::string &error_field,
    std::string &error_message);

/**
 * Populates nativeEffective JSON with inpainting metadata.
 */
void populate_inpaint_effective_json(
    nlohmann::json &native_effective,
    bool mask_image_wired,
    const LoadedInputImage &mask_image);

/**
 * Populates top-level generation response JSON with inpainting mask properties.
 */
void populate_inpaint_response_json(
    nlohmann::json &out,
    bool mask_image_wired,
    const LoadedInputImage &mask_image);

/**
 * Morphological dilation of a single-channel 8-bit mask by radius pixels.
 */
void expand_mask_region(std::vector<uint8_t> &mask,
                        uint32_t width,
                        uint32_t height,
                        int radius);

/**
 * Feather/smooth mask boundary edges using box filter approximation.
 */
void feather_mask_edges(std::vector<uint8_t> &mask,
                        uint32_t width,
                        uint32_t height,
                        int radius);

/**
 * Alpha-blends the generated inpainting output with the original input image
 * according to the normalized mask (0 = keep original, 255 = keep inpaint).
 */
void blend_inpaint_composite(uint8_t *target,
                             const uint8_t *original,
                             const uint8_t *inpaint,
                             const uint8_t *mask,
                             uint32_t width,
                             uint32_t height,
                             uint32_t channels);

} // namespace sd
} // namespace mca

#endif // MCA_SD_INPAINT_PROCESSOR_HPP
