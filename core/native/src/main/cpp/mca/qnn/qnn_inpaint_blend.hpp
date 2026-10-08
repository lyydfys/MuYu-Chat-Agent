#ifndef MCA_QNN_INPAINT_BLEND_HPP
#define MCA_QNN_INPAINT_BLEND_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "qnn_image_pixel_range.hpp"

namespace mca::qnn {

/**
 * Image plane representation for Gaussian and Laplacian pyramid processing.
 */
struct PyramidImage {
    size_t channels = 0U;
    size_t width = 0U;
    size_t height = 0U;
    std::vector<float> values;
};

/**
 * Downsamples an image by 2x using a separable 5-tap binomial Gaussian filter.
 */
PyramidImage pyramid_down(const PyramidImage& input);

/**
 * Upsamples an image by 2x to target dimensions using a separable 5-tap binomial filter.
 */
PyramidImage pyramid_up(
        const PyramidImage& input,
        size_t target_width,
        size_t target_height);

/**
 * Multi-resolution Laplacian pyramid blending of source and generated images in NCHW format
 * guided by a normalized spatial mask.
 */
bool laplacian_pyramid_blend_nchw(
        const std::vector<float>& source,
        const std::vector<float>& generated,
        const std::vector<float>& mask,
        uint32_t width,
        uint32_t height,
        std::vector<float>* output,
        int* level_count,
        std::string* error);

#if MCA_QNN_TENSOR_INSPECT_HAS_SDK

/**
 * Blends inpaint VAE output tensor (NCHW or NHWC) with source RGB pixels using a Laplacian
 * pyramid, handling color range conversions and generating execution checksum.
 */
bool qnn_laplacian_blend_inpaint_vae_output(
        const QnnTensorBinding& tensor,
        const std::vector<float>& generated_values,
        mca::qnn::ImagePixelRange pixel_range,
        const std::vector<float>& source_nchw,
        const std::vector<float>& full_mask,
        std::vector<float>* blended_values,
        int* blend_levels,
        uint64_t* blend_checksum,
        std::string* error);

#endif  // MCA_QNN_TENSOR_INSPECT_HAS_SDK

}  // namespace mca::qnn

#endif  // MCA_QNN_INPAINT_BLEND_HPP
