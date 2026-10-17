// mca/qnn/qnn_conditioning_token_payload.cpp -- CLIP token/weight payload reading and execution-weight evidence.
// (T2B16 / R7-C5 split).  Relocated byte-exact from mca/qnn/qnn_conditioning_pipeline.cpp (lines 48-234).

#include "mca/qnn/qnn_conditioning_pipeline.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "image_conditioning.hpp"
#include "native_prompt_language_contract.hpp"
// image_pixel_range_wire_name() is used by qnn_native_effective_json(); its declaration lives in the
// root QNN contract header, which this TU did not previously need to include.
#include "qnn_image_pixel_range.hpp"
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"

#if defined(_WIN32)
#include <io.h>
#include <direct.h>
#include <stdlib.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define realpath(N, R) _fullpath((R), (N), PATH_MAX)
#define lstat(P, S) stat((P), (S))
#ifndef S_ISREG
#define S_ISREG(m) (((m) & S_IFMT) == S_IFREG)
#endif
#else
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits.h>
#endif

namespace mca {
namespace qnn {

bool parse_qnn_preview_request(
        const std::string& params_json,
        QnnPreviewRequest* request,
        std::string* error) {
    if (request == nullptr || error == nullptr) return false;
    *request = QnnPreviewRequest{};
    try {
        const nlohmann::json root = nlohmann::json::parse(params_json);
        if (!root.is_object()) {
            *error = "QNN execution params must be one structured JSON object.";
            return false;
        }
        if (!root.contains("preview")) {
            error->clear();
            return true;
        }
        const auto& value = root.at("preview");
        static constexpr std::array<const char*, 2> kFields = {
            "mode",
            "interval",
        };
        if (!value.is_object() || value.size() != kFields.size() ||
            std::any_of(kFields.begin(), kFields.end(), [&](const char* field) {
                return !value.contains(field);
            }) ||
            !value.at("mode").is_string() ||
            !value.at("interval").is_number_integer()) {
            *error = "QNN preview must contain exactly string mode and exact integer interval fields.";
            return false;
        }
        request->requested = true;
        request->mode = value.at("mode").get<std::string>();
        request->interval = value.at("interval").get<long long>();
        error->clear();
        return true;
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("QNN preview JSON is invalid: ") + exception.what();
        return false;
    }
}


uint32_t qnn_u32_little_endian(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
        (static_cast<uint32_t>(bytes[1]) << 8U) |
        (static_cast<uint32_t>(bytes[2]) << 16U) |
        (static_cast<uint32_t>(bytes[3]) << 24U);
}

bool derive_qnn_token_execution_weight_evidence(
        const std::vector<int32_t>& token_ids,
        const std::vector<float>& token_weights,
        bool use_cfg,
        QnnConditioningEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr) return false;
    if (token_ids.empty() || token_ids.size() != token_weights.size() ||
        token_ids.size() % 2U != 0U) {
        *error = "CLIP token IDs and weights must contain two equally sized sequences.";
        return false;
    }
    if (std::any_of(token_ids.begin(), token_ids.end(), [](int32_t id) { return id < 0; })) {
        *error = "CLIP token conditioning contains a negative token ID.";
        return false;
    }
    if (std::any_of(token_weights.begin(), token_weights.end(), [](float weight) {
            return !std::isfinite(weight);
        })) {
        *error = "CLIP token conditioning contains a non-finite token weight.";
        return false;
    }

    const size_t sequence_length = token_ids.size() / 2U;
    mca::image::ClipTokenPair pair;
    pair.negative.ids.assign(token_ids.begin(), token_ids.begin() + sequence_length);
    pair.positive.ids.assign(token_ids.begin() + sequence_length, token_ids.end());
    pair.negative.weights.assign(
        token_weights.begin(), token_weights.begin() + sequence_length);
    pair.positive.weights.assign(
        token_weights.begin() + sequence_length, token_weights.end());
    const auto weighted_count = [](const std::vector<float>& weights) {
        return static_cast<size_t>(std::count_if(
            weights.begin(),
            weights.end(),
            [](float value) { return std::fabs(value - 1.0f) > 1.0e-6f; }));
    };
    const size_t payload_negative_weighted_count = weighted_count(pair.negative.weights);
    const size_t payload_positive_weighted_count = weighted_count(pair.positive.weights);
    if (!use_cfg && payload_negative_weighted_count != 0U) {
        *error = "A non-executed negative CLIP branch must not carry prompt weights when useCfg=false.";
        return false;
    }
    evidence->negative_weighted_token_count =
        use_cfg ? payload_negative_weighted_count : 0U;
    evidence->positive_weighted_token_count = payload_positive_weighted_count;
    evidence->prompt_weighting_applied =
        evidence->negative_weighted_token_count > 0U ||
        evidence->positive_weighted_token_count > 0U;
    evidence->prompt_weight_fingerprint = pair.weighting_fingerprint();
    if (evidence->prompt_weight_fingerprint.size() != 64U) {
        *error = "CLIP token conditioning fingerprint could not be derived.";
        return false;
    }
    return true;
}

bool read_qnn_clip_token_weight_payload(
        const std::string& path,
        std::vector<int32_t>* token_ids,
        std::vector<float>* token_weights,
        bool use_cfg,
        QnnConditioningEvidence* evidence,
        std::string* error,
        std::string* consumed_payload_sha256) {
    if (token_ids == nullptr || token_weights == nullptr ||
        evidence == nullptr || error == nullptr) {
        return false;
    }
    constexpr size_t kHeaderBytes = 16U;
    constexpr std::array<uint8_t, 8> kMagic = {
        'M', 'C', 'A', 'Q', 'P', 'W', '0', '1'
    };
    const long long file_bytes = file_size_or_zero(path);
    if (file_bytes < static_cast<long long>(kHeaderBytes) ||
        file_bytes > static_cast<long long>(16U * 1024U * 1024U)) {
        *error = "Weighted CLIP token payload size is invalid: " + path;
        return false;
    }
    std::ifstream input(path.c_str(), std::ios::binary);
    if (!input.good()) {
        *error = "Failed to open weighted CLIP token payload: " + path;
        return false;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(file_bytes));
    input.read(
        reinterpret_cast<char*>(bytes.data()),
        static_cast<std::streamsize>(bytes.size()));
    if (!input.good() || !std::equal(kMagic.begin(), kMagic.end(), bytes.begin())) {
        *error = "Weighted CLIP token payload header is invalid.";
        return false;
    }
    const uint32_t version = qnn_u32_little_endian(bytes.data() + 8U);
    const uint32_t token_count = qnn_u32_little_endian(bytes.data() + 12U);
    if (version != 1U || token_count == 0U || token_count > 8192U) {
        *error = "Weighted CLIP token payload version or token count is unsupported.";
        return false;
    }
    const size_t expected_bytes = kHeaderBytes +
        static_cast<size_t>(token_count) * (sizeof(int32_t) + sizeof(float));
    if (bytes.size() != expected_bytes || token_count % 2U != 0U) {
        *error = "Weighted CLIP token payload length does not match its header.";
        return false;
    }
    token_ids->assign(token_count, 0);
    token_weights->assign(token_count, 1.0f);
    size_t offset = kHeaderBytes;
    for (size_t index = 0; index < token_ids->size(); ++index, offset += 4U) {
        (*token_ids)[index] = static_cast<int32_t>(
            qnn_u32_little_endian(bytes.data() + offset));
        if ((*token_ids)[index] < 0) {
            *error = "Weighted CLIP token payload contains a negative token ID.";
            return false;
        }
    }
    for (size_t index = 0; index < token_weights->size(); ++index, offset += 4U) {
        const uint32_t bits = qnn_u32_little_endian(bytes.data() + offset);
        std::memcpy(&(*token_weights)[index], &bits, sizeof(bits));
        if (!std::isfinite((*token_weights)[index])) {
            *error = "Weighted CLIP token payload contains a non-finite token weight.";
            return false;
        }
    }
    if (!derive_qnn_token_execution_weight_evidence(
            *token_ids,
            *token_weights,
            use_cfg,
            evidence,
            error)) {
        return false;
    }
    return consumed_payload_sha256 == nullptr || qnn_consumed_payload_sha256(
        bytes.data(),
        bytes.size(),
        consumed_payload_sha256,
        error);
}



}  // namespace qnn
}  // namespace mca
