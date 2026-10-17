// T2B18 / R7-C7 (2026-10-14): internal split scaffold for image_execution_math.cpp.
// Declarations only.  Every name declared here is hidden via the visibility pragma below, so the
// public mca::image API in image_execution_math.hpp keeps its default visibility and both
// libmca_qnn_native.so and libmca_sd_native.so keep their pre-split export surface.
#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "image_execution_math.hpp"

#pragma GCC visibility push(hidden)
namespace mca::image {

bool fail(std::string* error, const std::string& message);

bool finite_values(const std::vector<float>& values);

size_t spatial_index(
        const SpatialTensorShape& shape,
        size_t batch,
        size_t channel,
        size_t y,
        size_t x);

}  // namespace mca::image
#pragma GCC visibility pop
