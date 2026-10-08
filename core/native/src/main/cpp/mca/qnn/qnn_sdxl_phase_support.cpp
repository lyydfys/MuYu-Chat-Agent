#include "qnn_sdxl_phase_support.hpp"

#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <algorithm>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <fstream>
#include <cctype>

#include "native_prompt_language_contract.hpp"
#include "qnn_inpaint_contract.hpp"
#include "qnn_controlnet_execution.hpp"

namespace {

long long file_size_or_zero(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return 0;
    return static_cast<long long>(st.st_size);
}

std::string string_field(const std::string& json_str, const std::string& key) {
    auto parsed = nlohmann::json::parse(json_str, nullptr, false);
    if (parsed.is_discarded() || !parsed.contains(key) || !parsed[key].is_string()) return "";
    return parsed[key].get<std::string>();
}

long long long_field(const std::string& json_str, const std::string& key) {
    auto parsed = nlohmann::json::parse(json_str, nullptr, false);
    if (parsed.is_discarded() || !parsed.contains(key) || !parsed[key].is_number_integer()) return 0;
    return parsed[key].get<long long>();
}

std::vector<uint32_t> uint_array_field(const std::string& json_str, const std::string& key) {
    auto parsed = nlohmann::json::parse(json_str, nullptr, false);
    if (parsed.is_discarded() || !parsed.contains(key) || !parsed[key].is_array()) return {};
    std::vector<uint32_t> result;
    for (const auto& item : parsed[key]) {
        if (item.is_number_unsigned()) result.push_back(item.get<uint32_t>());
    }
    return result;
}

std::string normalized_contract_enum(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return std::tolower(c); });
    return value;
}

bool read_float_binary_file(
        const std::string& path,
        std::vector<float>* values,
        std::string* error,
        std::string* consumed_payload_sha256 = nullptr) {
    const long long bytes = file_size_or_zero(path);
    if (bytes <= 0 || bytes % static_cast<long long>(sizeof(float)) != 0) {
        if (error != nullptr) *error = "File is missing, empty, or not f32 aligned: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        if (error != nullptr) *error = "Failed to open file: " + path;
        return false;
    }
    values->assign(static_cast<size_t>(bytes / static_cast<long long>(sizeof(float))), 0.0f);
    input.read(reinterpret_cast<char*>(values->data()), static_cast<std::streamsize>(bytes));
    if (!input.good()) {
        if (error != nullptr) *error = "Failed to read file: " + path;
        return false;
    }
    if (consumed_payload_sha256 != nullptr) {
        const auto* begin = reinterpret_cast<const uint8_t*>(values->data());
        const std::vector<uint8_t> consumed_bytes(begin, begin + static_cast<size_t>(bytes));
        *consumed_payload_sha256 = mca::qnn::controlnet::sha256_hex_bytes(consumed_bytes);
    }
    return true;
}

} // namespace

namespace mca {
namespace qnn {

namespace {

bool is_safe_ascii_diffusion_prompt(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char byte) {
        return mca::image::prompt_language::is_safe_ascii_diffusion_prompt_code_point(byte);
    });
}

} // namespace

bool write_sdxl_latent_atomic(
        const std::string& path,
        const std::vector<float>& values,
        std::string* error) {
    const std::string temporary = path + ".part";
    ::unlink(temporary.c_str());
    const int fd = ::open(
        temporary.c_str(),
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
        0600);
    if (fd < 0) {
        *error = "Failed to open latent temporary file: " + temporary;
        return false;
    }
    const uint8_t* cursor = reinterpret_cast<const uint8_t*>(values.data());
    size_t remaining = values.size() * sizeof(float);
    bool written = true;
    while (remaining > 0u) {
        const ssize_t count = ::write(fd, cursor, remaining);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            written = false;
            break;
        }
        cursor += count;
        remaining -= static_cast<size_t>(count);
    }
    if (written && ::fsync(fd) != 0) written = false;
    if (::close(fd) != 0) written = false;
    if (!written) {
        ::unlink(temporary.c_str());
        *error = "Failed to durably write latent temporary file: " + temporary;
        return false;
    }
    if (::rename(temporary.c_str(), path.c_str()) != 0) {
        ::unlink(temporary.c_str());
        *error = "Failed to atomically publish latent file: " + path;
        return false;
    }
    return true;
}

bool fsync_sdxl_artifact(const std::string& path, std::string* error) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "Failed to open generated artifact for fsync: " + path;
        return false;
    }
    const bool synced = ::fsync(fd) == 0;
    const bool closed = ::close(fd) == 0;
    if (!synced || !closed) {
        *error = "Failed to durably sync generated artifact: " + path;
        return false;
    }
    return true;
}

bool valid_sdxl_lower_sha256(const std::string& value) {
    return value.size() == 64U && std::all_of(
        value.begin(), value.end(), [](unsigned char character) {
            return (character >= '0' && character <= '9') ||
                (character >= 'a' && character <= 'f');
        });
}

bool is_safe_ascii_diffusion_prompt_pair(
        const std::string& prompt,
        const std::string& negative_prompt) {
    return is_safe_ascii_diffusion_prompt(prompt) &&
        is_safe_ascii_diffusion_prompt(negative_prompt);
}

bool validate_sdxl_phase_execution_contract(
        const std::string& prompt,
        const std::string& negative_prompt,
        int width,
        int height,
        bool is_host_before_graph_scaling,
        double vae_scaling_factor,
        std::string* error) {
    constexpr double kSdxlVaeScalingFactor = 0.13025;
    if (error == nullptr) return false;
    if (!is_safe_ascii_diffusion_prompt_pair(prompt, negative_prompt)) {
        *error = "Split-worker QNN SDXL accepts only safe ASCII diffusion prompt syntax because it lacks descriptor- and receipt-backed direct-Chinese tokenizer topology.";
        return false;
    }
    if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0) {
        *error = "SDXL dimensions must define an exact 8x latent shape.";
        return false;
    }
    if (!is_host_before_graph_scaling ||
        !std::isfinite(vae_scaling_factor) ||
        std::fabs(vae_scaling_factor - kSdxlVaeScalingFactor) > 1.0e-9) {
        *error = "SDXL VAE execution requires host-before-graph scaling with factor 0.13025.";
        return false;
    }
    error->clear();
    return true;
}

bool load_sdxl_inpaint_pixel_artifacts(
        const std::string& params_json,
        int contract_width,
        int contract_height,
        SdxlInpaintPixelArtifacts* artifacts,
        std::string* error) {
    if (artifacts == nullptr || error == nullptr ||
        contract_width != 1024 || contract_height != 1024) {
        if (error != nullptr) {
            *error = "Split-SDXL inpaint pixel artifacts require an exact 1024x1024 contract.";
        }
        return false;
    }
    const std::string source_tensor_path = string_field(
        params_json, "inputImageTensorPath");
    const std::string expected_source_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "inputImageTensorSha256"));
    const std::string full_mask_tensor_path = string_field(
        params_json, "maskImageFullTensorPath");
    const std::string expected_full_mask_tensor_sha256 = normalized_contract_enum(
        string_field(params_json, "maskImageFullTensorSha256"));
    const std::vector<uint32_t> expected_source_shape{1U, 3U, 1024U, 1024U};
    const std::vector<uint32_t> expected_full_mask_shape{1U, 1U, 1024U, 1024U};
    constexpr long long kSourceBytes =
        static_cast<long long>(1U * 3U * 1024U * 1024U * sizeof(float));
    constexpr long long kFullMaskBytes =
        static_cast<long long>(1U * 1024U * 1024U * sizeof(float));
    const long long repaint_count = long_field(
        params_json, "maskImageRepaintPixelCount");
    if (long_field(params_json, "inpaintArtifactVersion") != 2LL ||
        string_field(params_json, "inpaintRequestedTopology") != "latent_blend_4" ||
        string_field(params_json, "inpaintMaskConvention") !=
            "white_repaint_black_preserve" ||
        source_tensor_path.empty() ||
        !valid_sdxl_lower_sha256(expected_source_tensor_sha256) ||
        uint_array_field(params_json, "inputImageTensorShape") != expected_source_shape ||
        string_field(params_json, "inputImageTensorDtype") != "float32-le" ||
        string_field(params_json, "inputImageTensorLayout") != "NCHW" ||
        string_field(params_json, "inputImageTensorRange") != "NEGATIVE_ONE_TO_ONE" ||
        string_field(params_json, "inputImagePreprocess") !=
            "exif_orient_center_crop_bilinear_rgb_nchw_negative_one_to_one_v1" ||
        long_field(params_json, "inputImageTensorBytes") != kSourceBytes ||
        file_size_or_zero(source_tensor_path) != kSourceBytes ||
        !read_float_binary_file(
            source_tensor_path,
            &artifacts->source_nchw,
            error,
            &artifacts->source_tensor_sha256) ||
        normalized_contract_enum(artifacts->source_tensor_sha256) !=
            expected_source_tensor_sha256 ||
        artifacts->source_nchw.size() != 1U * 3U * 1024U * 1024U ||
        !std::all_of(
            artifacts->source_nchw.begin(),
            artifacts->source_nchw.end(),
            [](float value) {
                return std::isfinite(value) &&
                    value >= -1.000001f && value <= 1.000001f;
            }) ||
        full_mask_tensor_path.empty() ||
        !valid_sdxl_lower_sha256(expected_full_mask_tensor_sha256) ||
        uint_array_field(params_json, "maskImageFullTensorShape") !=
            expected_full_mask_shape ||
        string_field(params_json, "maskImageFullTensorDtype") != "float32-le" ||
        string_field(params_json, "maskImageFullTensorLayout") != "NCHW" ||
        string_field(params_json, "maskImageFullTensorRange") != "ZERO_TO_ONE" ||
        string_field(params_json, "maskImageFullTensorPreprocess") !=
            "source_aligned_center_crop_linear_grayscale_full_nchw_v1" ||
        long_field(params_json, "maskImageFullTensorBytes") != kFullMaskBytes ||
        file_size_or_zero(full_mask_tensor_path) != kFullMaskBytes ||
        !read_float_binary_file(
            full_mask_tensor_path,
            &artifacts->full_mask,
            error,
            &artifacts->full_mask_tensor_sha256) ||
        normalized_contract_enum(artifacts->full_mask_tensor_sha256) !=
            expected_full_mask_tensor_sha256 ||
        !mca::qnn::inpaint::validate_mask_values(
            artifacts->full_mask, 1024U, 1024U, error) ||
        repaint_count != static_cast<long long>(std::count_if(
            artifacts->full_mask.begin(),
            artifacts->full_mask.end(),
            [](float value) { return value > 0.0f; }))) {
        if (error->empty()) {
            *error = "Split-SDXL source RGB or full mask tensor failed its exact identity and shape contract.";
        }
        return false;
    }
    error->clear();
    return true;
}

bool validate_sdxl_no_preview_transport(
        const std::string& params_json,
        std::string* error) {
    if (error == nullptr) return false;
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.is_object()) {
            *error = "Split-SDXL params must be a JSON object.";
            return false;
        }
        const auto preview = root.find("preview");
        if (preview != root.end()) {
            *error = "Split-worker QNN SDXL does not support preview transport.";
            return false;
        }
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("Split-SDXL preview transport JSON is invalid: ") +
            exception.what();
        return false;
    }
}

std::string sdxl_parent_directory(const std::string& path) {
    const size_t separator = path.find_last_of('/');
    if (separator == std::string::npos) return "";
    return separator == 0U ? "/" : path.substr(0U, separator);
}

std::string sdxl_disabled_preview_native_json_fields() {
    return "\"previewRequested\":false,"
        "\"previewMode\":\"none\","
        "\"previewInterval\":0,"
        "\"previewVaeExecutionAttemptCount\":0,"
        "\"previewVaeExecutionCount\":0,"
        "\"previewVaeExecutionMsTotal\":0,"
        "\"previewPublicationCount\":0,"
        "\"previewLastStep\":0,"
        "\"previewLastRevision\":0,"
        "\"previewFailureCode\":\"\","
        "\"projectionPreviewAttemptCount\":0,"
        "\"projectionPreviewPublicationCount\":0,"
        "\"projectionPreviewProjectionMsTotal\":0,"
        "\"projectionPreviewLastStep\":0,"
        "\"projectionPreviewLastRevision\":0,"
        "\"projectionPreviewFailureCode\":\"\","
        "\"previewDegraded\":false";
}

} // namespace qnn
} // namespace mca
