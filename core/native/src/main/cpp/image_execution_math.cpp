// T2B18 / R7-C7 (2026-10-14): split out of image_execution_math.cpp (pre-split sha256
// 3ceca72fded2223d85e89566d0909a77c6c75c3a72b9f0df13bfaf175343c17a).  Body lines are byte-exact
// relocations; every delta is registered in b-SPLIT-PLAN.json.

#include "image_execution_math.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <utility>

#include "image_execution_math_internal.hpp"

namespace mca::image {

bool fail(std::string* error, const std::string& message) {
    if (error != nullptr) *error = message;
    return false;
}

namespace {
bool checked_product(size_t left, size_t right, size_t* product) {
    if (product == nullptr || left == 0U || right == 0U ||
        left > std::numeric_limits<size_t>::max() / right) {
        return false;
    }
    *product = left * right;
    return true;
}
}  // namespace

bool finite_values(const std::vector<float>& values) {
    return std::all_of(values.begin(), values.end(), [](float value) {
        return std::isfinite(value);
    });
}

size_t SpatialTensorShape::element_count() const {
    size_t batch_channels = 0;
    size_t spatial = 0;
    size_t total = 0;
    if (!checked_product(batch, channels, &batch_channels) ||
        !checked_product(height, width, &spatial) ||
        !checked_product(batch_channels, spatial, &total)) {
        return 0U;
    }
    return total;
}

bool resolve_spatial_tensor_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_channels,
        SpatialTensorShape* shape,
        std::string* error) {
    if (shape == nullptr || error == nullptr) return false;
    if (dimensions.size() != 4U || expected_channels == 0U) {
        return fail(error, "Spatial tensor must be rank 4 with a concrete channel count.");
    }
    if (dimensions[0] != 1U) {
        return fail(error, "Image execution currently requires spatial tensor batch size 1.");
    }
    const bool nchw = dimensions[1] == expected_channels;
    const bool nhwc = dimensions[3] == expected_channels;
    if (nchw == nhwc) {
        return fail(error, nchw
                ? "Spatial tensor channel layout is ambiguous."
                : "Spatial tensor does not expose the required channel dimension.");
    }
    shape->batch = dimensions[0];
    shape->channels = expected_channels;
    shape->layout = nchw ? SpatialTensorLayout::Nchw : SpatialTensorLayout::Nhwc;
    shape->height = nchw ? dimensions[2] : dimensions[1];
    shape->width = nchw ? dimensions[3] : dimensions[2];
    if (shape->height == 0U || shape->width == 0U || shape->element_count() == 0U) {
        return fail(error, "Spatial tensor dimensions are empty or overflow the host size contract.");
    }
    error->clear();
    return true;
}

bool resolve_sequence_feature_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_sequence,
        uint32_t expected_features,
        SequenceFeatureShape* shape,
        std::string* error) {
    if (shape == nullptr || error == nullptr) return false;
    if (dimensions.size() != 3U || dimensions[0] != 1U ||
        expected_sequence == 0U || expected_features == 0U) {
        return fail(error, "Conditioning tensor must be rank 3 with batch size 1.");
    }
    const bool sequence_major =
            dimensions[1] == expected_sequence && dimensions[2] == expected_features;
    const bool feature_major =
            dimensions[1] == expected_features && dimensions[2] == expected_sequence;
    if (sequence_major == feature_major) {
        return fail(error, "Conditioning tensor does not have one unambiguous sequence/feature layout.");
    }
    shape->batch = 1U;
    shape->sequence = expected_sequence;
    shape->features = expected_features;
    shape->layout = sequence_major
            ? SequenceFeatureLayout::SequenceMajor
            : SequenceFeatureLayout::FeatureMajor;
    error->clear();
    return true;
}

bool reorder_sequence_feature_tensor(
        const float* canonical_sequence_major,
        size_t value_count,
        const SequenceFeatureShape& destination_shape,
        std::vector<float>* destination,
        std::string* error) {
    if (canonical_sequence_major == nullptr || destination == nullptr || error == nullptr) {
        return false;
    }
    size_t expected = 0U;
    if (destination_shape.batch != 1U ||
        !checked_product(destination_shape.sequence, destination_shape.features, &expected) ||
        value_count != expected) {
        return fail(error, "Conditioning values do not match the resolved sequence/feature shape.");
    }
    destination->assign(expected, 0.0f);
    if (destination_shape.layout == SequenceFeatureLayout::SequenceMajor) {
        std::copy(
                canonical_sequence_major,
                canonical_sequence_major + expected,
                destination->begin());
    } else {
        for (size_t token = 0U; token < destination_shape.sequence; ++token) {
            for (size_t feature = 0U; feature < destination_shape.features; ++feature) {
                (*destination)[feature * destination_shape.sequence + token] =
                        canonical_sequence_major[token * destination_shape.features + feature];
            }
        }
    }
    if (!finite_values(*destination)) {
        return fail(error, "Conditioning tensor contains a non-finite value.");
    }
    error->clear();
    return true;
}

bool validate_sdxl_conditioning_payload(
        size_t value_count,
        size_t branch_count,
        std::string* error) {
    if (error == nullptr) return false;
    const size_t expected = sdxl_conditioning_element_count(branch_count);
    if (expected == 0U) {
        return fail(error, "SDXL conditioning payload must contain one or two CLIP branches.");
    }
    if (value_count != expected) {
        std::ostringstream message;
        message << "SDXL conditioning payload with " << branch_count
                << " branch(es) must contain exactly " << expected
                << " float32 values, got " << value_count << ".";
        return fail(error, message.str());
    }
    error->clear();
    return true;
}

bool validate_sdxl_conditioning_payload(size_t value_count, std::string* error) {
    return validate_sdxl_conditioning_payload(value_count, 2U, error);
}

bool apply_classifier_free_guidance(
        const std::vector<float>& conditional,
        const std::vector<float>& unconditional,
        double cfg_scale,
        bool use_cfg,
        std::vector<float>* guided,
        std::string* error) {
    if (guided == nullptr || error == nullptr) return false;
    if (conditional.empty() || !finite_values(conditional) ||
        !std::isfinite(cfg_scale) || cfg_scale < 0.0 || cfg_scale > 30.0) {
        return fail(error, "CFG inputs must be finite and use a scale in [0, 30].");
    }
    if (!use_cfg) {
        if (std::fabs(cfg_scale - 1.0) > 1.0e-12) {
            return fail(error, "Conditional-only execution requires cfgScale=1.");
        }
        *guided = conditional;
        error->clear();
        return true;
    }
    if (unconditional.size() != conditional.size() || !finite_values(unconditional)) {
        return fail(error, "CFG conditional and unconditional tensors must have identical finite shapes.");
    }
    guided->resize(conditional.size());
    for (size_t index = 0U; index < conditional.size(); ++index) {
        (*guided)[index] = static_cast<float>(
                unconditional[index] +
                cfg_scale * (conditional[index] - unconditional[index]));
    }
    if (!finite_values(*guided)) {
        return fail(error, "CFG produced a non-finite tensor.");
    }
    error->clear();
    return true;
}

bool scale_vae_latents_in_place(
        std::vector<float>* latents,
        double scaling_factor,
        bool host_before_graph,
        std::string* error) {
    if (latents == nullptr || error == nullptr || latents->empty() ||
        !std::isfinite(scaling_factor) || scaling_factor <= 0.0 ||
        !finite_values(*latents)) {
        return fail(error, "VAE latent scaling requires finite values and a positive factor.");
    }
    if (host_before_graph) {
        const double multiplier = 1.0 / scaling_factor;
        for (float& value : *latents) {
            value = static_cast<float>(value * multiplier);
        }
        if (!finite_values(*latents)) {
            return fail(error, "VAE latent scaling produced a non-finite value.");
        }
    }
    error->clear();
    return true;
}

}  // namespace mca::image
