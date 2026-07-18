#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mca::qnn::controlnet {

enum class ControlImagePreprocessMode {
    Canny,
    PreprocessedCanny,
};

struct PreparedControlImage {
    std::string canonical_path;
    std::string encoded_sha256;
    std::string preprocessed_sha256;
    uint32_t source_width = 0;
    uint32_t source_height = 0;
    uint32_t source_channels = 0;
    uint32_t oriented_width = 0;
    uint32_t oriented_height = 0;
    uint32_t tensor_width = 0;
    uint32_t tensor_height = 0;
    uint32_t tensor_channels = 3;
    int exif_orientation = 1;
    size_t edge_pixel_count = 0;
    ControlImagePreprocessMode preprocess_mode = ControlImagePreprocessMode::Canny;
    // Canonical model input in NHWC/HWC order and the graph-declared [0, 1] range.
    std::vector<float> tensor_hwc;
};

struct TensorDescriptor {
    std::string name;
    std::vector<uint32_t> dimensions;
};

struct ResidualBinding {
    size_t control_output_index = 0;
    size_t unet_input_index = 0;
    std::string role;
};

std::string control_image_preprocess_wire_name(ControlImagePreprocessMode mode);

/** SHA-256 used for encoded, preprocessed, and actual graph-buffer evidence. */
std::string sha256_hex_bytes(const std::vector<uint8_t>& payload);

/**
 * Streams an artifact from disk into SHA-256 without imposing a model-size
 * admission limit or allocating a buffer proportional to the file size.
 */
bool sha256_hex_file(const std::string& path,
                     std::string* digest,
                     std::string* error);

bool parse_control_image_preprocess_mode(const std::string& value,
                                         ControlImagePreprocessMode* mode,
                                         std::string* error);

/**
 * Loads only an already canonical, regular worker-owned image, verifies its
 * prepared SHA-256, applies EXIF orientation, and produces the exact RGB
 * [0, 1] tensor consumed by the QNN graph.
 */
bool load_prepared_control_image(const std::string& raw_path,
                                 const std::string& expected_sha256,
                                 uint32_t target_width,
                                 uint32_t target_height,
                                 ControlImagePreprocessMode mode,
                                 PreparedControlImage* result,
                                 std::string* error);

/** Pure pixel entry point used by native tests and by the verified file path. */
bool prepare_control_image_pixels(const std::vector<uint8_t>& rgb,
                                  uint32_t width,
                                  uint32_t height,
                                  uint32_t target_width,
                                  uint32_t target_height,
                                  ControlImagePreprocessMode mode,
                                  std::vector<float>* tensor_hwc,
                                  size_t* edge_pixel_count,
                                  std::string* preprocessed_sha256,
                                  std::string* error);

/**
 * Resolves the official 12 down-block plus one mid-block contract by semantic
 * tensor name and exact shape. Numeric suffixes are parsed, never substring
 * matched, so down block 1 cannot accidentally bind block 10 or 11.
 */
bool build_residual_binding_plan(const std::vector<TensorDescriptor>& control_outputs,
                                 const std::vector<TensorDescriptor>& unet_inputs,
                                 std::vector<ResidualBinding>* bindings,
                                 std::string* error);

/** Applies the user control strength to actual dequantized graph residuals. */
bool scale_residual_in_place(std::vector<float>* residual,
                             double control_strength,
                             std::string* error);

}  // namespace mca::qnn::controlnet
