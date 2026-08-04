#pragma once

// Topology-only admission for QNN inpaint graphs.
//
// A regular Stable Diffusion UNet exposes a four-channel latent sample and can
// execute Local Dream-compatible inpaint by blending the noised source latent
// back after every scheduler step. Explicit concatenated nine-channel and
// separate-mask graphs retain their stricter native binding contracts. This
// helper does not use model ids, chipsets, device profiles, or validation
// history as admission criteria.

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace mca::qnn::inpaint {

enum class MaskTopology {
    None,
    LatentBlend4,
    ConcatenatedLatent9,
    SeparateMaskInput,
};

enum class TensorLayout {
    Unknown,
    Nchw,
};

struct TensorDescriptor {
    std::string name;
    std::vector<uint32_t> dimensions;
};

struct Contract {
    MaskTopology topology = MaskTopology::None;
    TensorLayout layout = TensorLayout::Unknown;
    int sample_index = -1;
    int mask_index = -1;
    uint32_t width = 0U;
    uint32_t height = 0U;
    uint32_t sample_channels = 0U;
    std::string reason;

    bool supported() const {
        return topology != MaskTopology::None &&
            layout == TensorLayout::Nchw && sample_index >= 0 &&
            width > 0U && height > 0U;
    }

    const char* topology_name() const {
        switch (topology) {
            case MaskTopology::LatentBlend4: return "latent_blend_4";
            case MaskTopology::ConcatenatedLatent9: return "concatenated_latent_9";
            case MaskTopology::SeparateMaskInput: return "separate_mask_input";
            case MaskTopology::None: return "none";
        }
        return "none";
    }

    bool requires_mask_binding() const {
        return topology == MaskTopology::ConcatenatedLatent9 ||
            topology == MaskTopology::SeparateMaskInput;
    }

    bool requires_masked_latent() const {
        return topology == MaskTopology::ConcatenatedLatent9;
    }

    const char* layout_name() const {
        switch (layout) {
            case TensorLayout::Nchw: return "NCHW";
            case TensorLayout::Unknown: return "UNKNOWN";
        }
        return "UNKNOWN";
    }
};

inline std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

inline bool contains_mask_token(const std::string& name) {
    const std::string lower = lower_ascii(name);
    // Require a token boundary for the short "mask" form.  This avoids
    // treating unrelated names such as "masker" as a mask input while still
    // accepting common exported names (inpaint_mask, denoise_mask, mask_image).
    if (lower == "mask" || lower == "inpaint_mask" || lower == "inpaintmask" ||
        lower == "denoise_mask" || lower == "denoisemask" || lower == "mask_image" ||
        lower == "masked_image" || lower == "maskinput" || lower == "mask_input" ||
        lower == "masktensor" || lower == "mask_tensor" ||
        lower == "conditioning_mask") {
        return true;
    }
    for (size_t i = 0U; i + 4U <= lower.size(); ++i) {
        if (lower.compare(i, 4U, "mask") != 0) continue;
        const bool left_boundary = i == 0U || !std::isalnum(static_cast<unsigned char>(lower[i - 1U]));
        const size_t end = i + 4U;
        const bool right_boundary = end == lower.size() || !std::isalnum(static_cast<unsigned char>(lower[end]));
        if (left_boundary && right_boundary) return true;
    }
    return false;
}

struct SpatialShape {
    TensorLayout layout = TensorLayout::Unknown;
    uint32_t width = 0U;
    uint32_t height = 0U;
    uint32_t channels = 0U;
};

inline bool resolve_spatial_shape(
        const std::vector<uint32_t>& dimensions,
        SpatialShape* shape) {
    if (shape == nullptr || dimensions.size() != 4U || dimensions[0] != 1U) return false;
    if (dimensions[1] > 0U && dimensions[2] > 0U && dimensions[3] > 0U &&
        (dimensions[1] == 1U || dimensions[1] == 4U || dimensions[1] == 9U)) {
        shape->layout = TensorLayout::Nchw;
        shape->channels = dimensions[1];
        shape->height = dimensions[2];
        shape->width = dimensions[3];
        return true;
    }
    return false;
}

inline int likely_sample_index(const std::vector<TensorDescriptor>& inputs) {
    for (size_t index = 0U; index < inputs.size(); ++index) {
        const std::string name = lower_ascii(inputs[index].name);
        if (name == "sample" || name == "latent" || name == "noisy_sample" ||
            name == "model_input" || name == "x") {
            return static_cast<int>(index);
        }
    }
    // Some context exporters do not preserve semantic names.  A latent-shaped
    // four/nine-channel tensor is still a candidate, but ambiguity must be
    // rejected below rather than silently selecting a text/control input.
    int candidate = -1;
    for (size_t index = 0U; index < inputs.size(); ++index) {
        SpatialShape shape;
        if (!resolve_spatial_shape(inputs[index].dimensions, &shape) ||
            (shape.channels != 4U && shape.channels != 9U)) {
            continue;
        }
        if (candidate >= 0) return -2;
        candidate = static_cast<int>(index);
    }
    return candidate;
}

inline Contract inspect(const std::vector<TensorDescriptor>& inputs) {
    Contract result;
    if (inputs.empty()) {
        result.reason = "QNN UNet exposes no inputs.";
        return result;
    }
    const int sample_index = likely_sample_index(inputs);
    if (sample_index == -2) {
        result.reason = "QNN inpaint sample input is ambiguous; semantic sample/latent name is required.";
        return result;
    }
    if (sample_index < 0 || static_cast<size_t>(sample_index) >= inputs.size()) {
        result.reason = "QNN UNet has no four- or nine-channel latent sample input.";
        return result;
    }
    SpatialShape sample_shape;
    if (!resolve_spatial_shape(inputs[static_cast<size_t>(sample_index)].dimensions, &sample_shape)) {
        result.reason = "QNN inpaint sample tensor must be a rank-4 NCHW image tensor.";
        return result;
    }
    result.sample_index = sample_index;
    result.layout = sample_shape.layout;
    result.width = sample_shape.width;
    result.height = sample_shape.height;
    result.sample_channels = sample_shape.channels;
    std::vector<size_t> semantic_mask_indices;
    for (size_t index = 0U; index < inputs.size(); ++index) {
        if (static_cast<int>(index) != sample_index && contains_mask_token(inputs[index].name)) {
            semantic_mask_indices.push_back(index);
        }
    }
    if (semantic_mask_indices.size() > 1U) {
        result.reason = "QNN UNet exposes multiple semantic mask inputs; the contract is ambiguous.";
        return result;
    }
    if (sample_shape.channels == 9U) {
        if (!semantic_mask_indices.empty()) {
            result.reason = "QNN UNet exposes both a concatenated nine-channel sample and a separate semantic mask input.";
            return result;
        }
        result.topology = MaskTopology::ConcatenatedLatent9;
        result.reason = "UNet sample exposes the conventional four-latent + one-mask + four-masked-latent contract.";
        return result;
    }
    if (sample_shape.channels != 4U) {
        result.reason = "QNN UNet latent sample must expose four channels for a separate-mask contract or nine concatenated channels.";
        return result;
    }

    if (semantic_mask_indices.empty()) {
        result.topology = MaskTopology::LatentBlend4;
        result.reason = "UNet exposes a regular four-channel latent; inpaint is applied by per-step source-latent blending.";
        return result;
    }
    const int mask_index = static_cast<int>(semantic_mask_indices.front());
    SpatialShape mask_shape;
    if (!resolve_spatial_shape(inputs[semantic_mask_indices.front()].dimensions, &mask_shape) ||
        mask_shape.channels != 1U || mask_shape.width != sample_shape.width ||
        mask_shape.height != sample_shape.height || mask_shape.layout != sample_shape.layout) {
        result.reason = "QNN mask input must be one channel with the same spatial shape and layout as the latent sample.";
        return result;
    }
    result.topology = MaskTopology::SeparateMaskInput;
    result.mask_index = mask_index;
    result.reason = "UNet exposes a separate one-channel mask tensor.";
    return result;
}

inline bool validate_mask_values(
        const std::vector<float>& values,
        uint32_t width,
        uint32_t height,
        std::string* error) {
    if (width == 0U || height == 0U) {
        if (error != nullptr) *error = "Mask dimensions must be positive.";
        return false;
    }
    const uint64_t expected = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (expected > std::numeric_limits<size_t>::max() || values.size() != static_cast<size_t>(expected)) {
        if (error != nullptr) *error = "Mask tensor element count does not match the graph spatial shape.";
        return false;
    }
    for (float value : values) {
        if (!std::isfinite(value) || value < 0.0f || value > 1.0f) {
            if (error != nullptr) *error = "Mask tensor values must be finite and normalized to [0,1].";
            return false;
        }
    }
    if (error != nullptr) error->clear();
    return true;
}

inline bool build_concatenated_sample(
        const std::vector<float>& latent,
        const std::vector<float>& mask,
        const std::vector<float>& masked_latent,
        uint32_t width,
        uint32_t height,
        TensorLayout layout,
        std::vector<float>* output,
        std::string* error) {
    if (output == nullptr) {
        if (error != nullptr) *error = "Inpaint concatenation output is null.";
        return false;
    }
    const uint64_t spatial = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (spatial == 0U || latent.size() != spatial * 4U ||
        mask.size() != spatial || masked_latent.size() != spatial * 4U) {
        if (error != nullptr) *error = "Inpaint concatenation requires latent[4], mask[1], and masked-latent[4] with one shared spatial shape.";
        return false;
    }
    if (!validate_mask_values(mask, width, height, error)) return false;
    output->assign(static_cast<size_t>(spatial * 9U), 0.0f);
    if (layout != TensorLayout::Nchw) {
        if (error != nullptr) *error = "Inpaint concatenation requires NCHW tensor layout.";
        return false;
    }
    std::copy(latent.begin(), latent.end(), output->begin());
    std::copy(mask.begin(), mask.end(), output->begin() + static_cast<size_t>(spatial * 4U));
    std::copy(masked_latent.begin(), masked_latent.end(), output->begin() + static_cast<size_t>(spatial * 5U));
    if (error != nullptr) error->clear();
    return true;
}

// White mask values repaint; black values preserve the source latent. The
// source tensor passed here must already carry the same deterministic noise at
// the timestep represented by generated_latent.
inline bool preserve_unmasked_latent(
        const std::vector<float>& generated_latent,
        const std::vector<float>& source_latent_at_timestep,
        const std::vector<float>& mask,
        uint32_t width,
        uint32_t height,
        std::vector<float>* output,
        std::string* error) {
    if (output == nullptr) {
        if (error != nullptr) *error = "Inpaint preserved latent output is null.";
        return false;
    }
    const uint64_t spatial = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (spatial == 0U || generated_latent.size() != spatial * 4U ||
        source_latent_at_timestep.size() != generated_latent.size() ||
        !validate_mask_values(mask, width, height, error)) {
        if (error != nullptr && error->empty()) {
            *error = "Inpaint preservation requires matching four-channel latents and one mask.";
        }
        return false;
    }
    output->resize(generated_latent.size());
    for (size_t channel = 0U; channel < 4U; ++channel) {
        const size_t channel_offset = channel * static_cast<size_t>(spatial);
        for (size_t pixel = 0U; pixel < static_cast<size_t>(spatial); ++pixel) {
            const float repaint = mask[pixel];
            const size_t index = channel_offset + pixel;
            (*output)[index] = generated_latent[index] * repaint +
                source_latent_at_timestep[index] * (1.0f - repaint);
            if (!std::isfinite((*output)[index])) {
                if (error != nullptr) *error = "Inpaint preservation produced a non-finite latent.";
                return false;
            }
        }
    }
    if (error != nullptr) error->clear();
    return true;
}

struct PyramidImage {
    size_t channels = 0U;
    size_t width = 0U;
    size_t height = 0U;
    std::vector<float> values;
};

inline PyramidImage pyramid_down(const PyramidImage& input) {
    static constexpr float kernel[5] = {
        1.0f / 16.0f, 4.0f / 16.0f, 6.0f / 16.0f, 4.0f / 16.0f, 1.0f / 16.0f};
    PyramidImage output;
    output.channels = input.channels;
    output.width = input.width / 2U;
    output.height = input.height / 2U;
    output.values.assign(output.channels * output.width * output.height, 0.0f);
    const size_t input_plane = input.width * input.height;
    const size_t output_plane = output.width * output.height;
    for (size_t channel = 0U; channel < output.channels; ++channel) {
        for (size_t y = 0U; y < output.height; ++y) {
            for (size_t x = 0U; x < output.width; ++x) {
                float value = 0.0f;
                for (int ky = -2; ky <= 2; ++ky) {
                    for (int kx = -2; kx <= 2; ++kx) {
                        const size_t source_y = static_cast<size_t>(std::clamp(
                            static_cast<int>(y * 2U) + ky,
                            0,
                            static_cast<int>(input.height) - 1));
                        const size_t source_x = static_cast<size_t>(std::clamp(
                            static_cast<int>(x * 2U) + kx,
                            0,
                            static_cast<int>(input.width) - 1));
                        value += input.values[channel * input_plane + source_y * input.width + source_x] *
                            kernel[ky + 2] * kernel[kx + 2];
                    }
                }
                output.values[channel * output_plane + y * output.width + x] = value;
            }
        }
    }
    return output;
}

inline PyramidImage pyramid_up(
        const PyramidImage& input,
        size_t target_width,
        size_t target_height) {
    static constexpr float kernel[5] = {
        1.0f / 16.0f, 4.0f / 16.0f, 6.0f / 16.0f, 4.0f / 16.0f, 1.0f / 16.0f};
    PyramidImage output;
    output.channels = input.channels;
    output.width = target_width;
    output.height = target_height;
    output.values.assign(output.channels * output.width * output.height, 0.0f);
    const size_t input_plane = input.width * input.height;
    const size_t output_plane = output.width * output.height;
    for (size_t channel = 0U; channel < output.channels; ++channel) {
        for (size_t y = 0U; y < output.height; ++y) {
            for (size_t x = 0U; x < output.width; ++x) {
                float value = 0.0f;
                for (int ky = -2; ky <= 2; ++ky) {
                    for (int kx = -2; kx <= 2; ++kx) {
                        const int source_y_numerator = static_cast<int>(y) - ky;
                        const int source_x_numerator = static_cast<int>(x) - kx;
                        if (source_y_numerator % 2 != 0 || source_x_numerator % 2 != 0) continue;
                        const int source_y = source_y_numerator / 2;
                        const int source_x = source_x_numerator / 2;
                        if (source_y < 0 || source_x < 0 ||
                            source_y >= static_cast<int>(input.height) ||
                            source_x >= static_cast<int>(input.width)) {
                            continue;
                        }
                        value += input.values[
                            channel * input_plane + static_cast<size_t>(source_y) * input.width +
                            static_cast<size_t>(source_x)] * kernel[ky + 2] * kernel[kx + 2] * 4.0f;
                    }
                }
                output.values[channel * output_plane + y * output.width + x] = value;
            }
        }
    }
    return output;
}

/** Local Dream-compatible full-resolution Laplacian mask blend. */
inline bool laplacian_pyramid_blend_nchw(
        const std::vector<float>& source,
        const std::vector<float>& generated,
        const std::vector<float>& mask,
        uint32_t width,
        uint32_t height,
        std::vector<float>* output,
        int* level_count,
        std::string* error) {
    if (output == nullptr || level_count == nullptr) {
        if (error != nullptr) *error = "Inpaint Laplacian blend output is null.";
        return false;
    }
    const uint64_t plane_u64 = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (width == 0U || height == 0U ||
        plane_u64 > std::numeric_limits<size_t>::max() / 3U ||
        source.size() != static_cast<size_t>(plane_u64 * 3U) ||
        generated.size() != source.size() || mask.size() != static_cast<size_t>(plane_u64) ||
        !validate_mask_values(mask, width, height, error) ||
        !std::all_of(source.begin(), source.end(), [](float value) { return std::isfinite(value); }) ||
        !std::all_of(generated.begin(), generated.end(), [](float value) { return std::isfinite(value); })) {
        if (error != nullptr && error->empty()) {
            *error = "Inpaint Laplacian blend requires finite source/generated RGB and one normalized mask.";
        }
        return false;
    }

    const int minimum_size = static_cast<int>(std::min(width, height));
    int levels = static_cast<int>(std::floor(std::log2(static_cast<double>(minimum_size)))) - 3;
    levels = std::max(levels, 2);
    while (levels > 0 && (minimum_size >> levels) < 4) --levels;
    levels = std::max(levels, 1);

    std::vector<PyramidImage> source_gaussian;
    std::vector<PyramidImage> generated_gaussian;
    std::vector<PyramidImage> mask_gaussian;
    source_gaussian.push_back({3U, width, height, source});
    generated_gaussian.push_back({3U, width, height, generated});
    mask_gaussian.push_back({1U, width, height, mask});
    for (int level = 1; level < levels; ++level) {
        source_gaussian.push_back(pyramid_down(source_gaussian.back()));
        generated_gaussian.push_back(pyramid_down(generated_gaussian.back()));
        mask_gaussian.push_back(pyramid_down(mask_gaussian.back()));
    }

    std::vector<PyramidImage> source_laplacian;
    std::vector<PyramidImage> generated_laplacian;
    for (int level = 0; level < levels - 1; ++level) {
        PyramidImage source_up = pyramid_up(
            source_gaussian[level + 1], source_gaussian[level].width, source_gaussian[level].height);
        PyramidImage generated_up = pyramid_up(
            generated_gaussian[level + 1], generated_gaussian[level].width,
            generated_gaussian[level].height);
        for (size_t index = 0U; index < source_up.values.size(); ++index) {
            source_up.values[index] = source_gaussian[level].values[index] - source_up.values[index];
            generated_up.values[index] = generated_gaussian[level].values[index] - generated_up.values[index];
        }
        source_laplacian.push_back(std::move(source_up));
        generated_laplacian.push_back(std::move(generated_up));
    }
    source_laplacian.push_back(source_gaussian.back());
    generated_laplacian.push_back(generated_gaussian.back());

    std::vector<PyramidImage> blended;
    for (int level = 0; level < levels; ++level) {
        PyramidImage value = source_laplacian[level];
        const size_t plane = value.width * value.height;
        for (size_t channel = 0U; channel < value.channels; ++channel) {
            for (size_t pixel = 0U; pixel < plane; ++pixel) {
                const float repaint = mask_gaussian[level].values[pixel];
                const size_t index = channel * plane + pixel;
                value.values[index] = source_laplacian[level].values[index] * (1.0f - repaint) +
                    generated_laplacian[level].values[index] * repaint;
            }
        }
        blended.push_back(std::move(value));
    }

    PyramidImage result = blended.back();
    for (int level = levels - 2; level >= 0; --level) {
        PyramidImage expanded = pyramid_up(result, blended[level].width, blended[level].height);
        for (size_t index = 0U; index < expanded.values.size(); ++index) {
            expanded.values[index] += blended[level].values[index];
        }
        result = std::move(expanded);
    }
    if (!std::all_of(result.values.begin(), result.values.end(), [](float value) {
            return std::isfinite(value);
        })) {
        if (error != nullptr) *error = "Inpaint Laplacian blend produced a non-finite pixel.";
        return false;
    }
    *output = std::move(result.values);
    *level_count = levels;
    if (error != nullptr) error->clear();
    return true;
}

}  // namespace mca::qnn::inpaint
