#include "textual_inversion_conditioning.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <iomanip>
#include <limits>
#include <numeric>
#include <sstream>
#include <string_view>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace mca::image::textual_inversion {
namespace {

constexpr uint64_t kMinArtifactBytes = 16U;
constexpr uint64_t kMaxArtifactBytes = 100U * 1024U * 1024U;
constexpr uint64_t kMaxHeaderBytes = 1024U * 1024U;
constexpr size_t kClipLWidth = 768U;
constexpr size_t kClipGWidth = 1280U;
constexpr size_t kMaxEmbeddingRows = 75U;

constexpr std::array<uint32_t, 64> kSha256RoundConstants = {
    UINT32_C(0x428a2f98), UINT32_C(0x71374491), UINT32_C(0xb5c0fbcf), UINT32_C(0xe9b5dba5),
    UINT32_C(0x3956c25b), UINT32_C(0x59f111f1), UINT32_C(0x923f82a4), UINT32_C(0xab1c5ed5),
    UINT32_C(0xd807aa98), UINT32_C(0x12835b01), UINT32_C(0x243185be), UINT32_C(0x550c7dc3),
    UINT32_C(0x72be5d74), UINT32_C(0x80deb1fe), UINT32_C(0x9bdc06a7), UINT32_C(0xc19bf174),
    UINT32_C(0xe49b69c1), UINT32_C(0xefbe4786), UINT32_C(0x0fc19dc6), UINT32_C(0x240ca1cc),
    UINT32_C(0x2de92c6f), UINT32_C(0x4a7484aa), UINT32_C(0x5cb0a9dc), UINT32_C(0x76f988da),
    UINT32_C(0x983e5152), UINT32_C(0xa831c66d), UINT32_C(0xb00327c8), UINT32_C(0xbf597fc7),
    UINT32_C(0xc6e00bf3), UINT32_C(0xd5a79147), UINT32_C(0x06ca6351), UINT32_C(0x14292967),
    UINT32_C(0x27b70a85), UINT32_C(0x2e1b2138), UINT32_C(0x4d2c6dfc), UINT32_C(0x53380d13),
    UINT32_C(0x650a7354), UINT32_C(0x766a0abb), UINT32_C(0x81c2c92e), UINT32_C(0x92722c85),
    UINT32_C(0xa2bfe8a1), UINT32_C(0xa81a664b), UINT32_C(0xc24b8b70), UINT32_C(0xc76c51a3),
    UINT32_C(0xd192e819), UINT32_C(0xd6990624), UINT32_C(0xf40e3585), UINT32_C(0x106aa070),
    UINT32_C(0x19a4c116), UINT32_C(0x1e376c08), UINT32_C(0x2748774c), UINT32_C(0x34b0bcb5),
    UINT32_C(0x391c0cb3), UINT32_C(0x4ed8aa4a), UINT32_C(0x5b9cca4f), UINT32_C(0x682e6ff3),
    UINT32_C(0x748f82ee), UINT32_C(0x78a5636f), UINT32_C(0x84c87814), UINT32_C(0x8cc70208),
    UINT32_C(0x90befffa), UINT32_C(0xa4506ceb), UINT32_C(0xbef9a3f7), UINT32_C(0xc67178f2),
};

uint32_t rotate_right(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

class Sha256State {
public:
    bool update(const uint8_t* input, size_t input_size) {
        if ((input == nullptr && input_size != 0U) ||
            input_size > std::numeric_limits<uint64_t>::max() - total_bytes_) {
            return false;
        }
        total_bytes_ += input_size;
        while (input_size > 0U) {
            const size_t copied = std::min(input_size, buffer_.size() - buffer_size_);
            std::memcpy(buffer_.data() + buffer_size_, input, copied);
            buffer_size_ += copied;
            input += copied;
            input_size -= copied;
            if (buffer_size_ == buffer_.size()) {
                transform(buffer_.data());
                buffer_size_ = 0U;
            }
        }
        return true;
    }

    std::array<uint8_t, 32> finish() {
        const uint64_t bit_length = total_bytes_ * UINT64_C(8);
        buffer_[buffer_size_++] = UINT8_C(0x80);
        if (buffer_size_ > 56U) {
            std::fill(buffer_.begin() + buffer_size_, buffer_.end(), UINT8_C(0));
            transform(buffer_.data());
            buffer_size_ = 0U;
        }
        std::fill(buffer_.begin() + buffer_size_, buffer_.begin() + 56U, UINT8_C(0));
        for (size_t index = 0; index < 8U; ++index) {
            buffer_[56U + index] = static_cast<uint8_t>(bit_length >> (56U - index * 8U));
        }
        transform(buffer_.data());
        std::array<uint8_t, 32> digest{};
        for (size_t index = 0; index < state_.size(); ++index) {
            digest[index * 4U] = static_cast<uint8_t>(state_[index] >> 24U);
            digest[index * 4U + 1U] = static_cast<uint8_t>(state_[index] >> 16U);
            digest[index * 4U + 2U] = static_cast<uint8_t>(state_[index] >> 8U);
            digest[index * 4U + 3U] = static_cast<uint8_t>(state_[index]);
        }
        return digest;
    }

private:
    void transform(const uint8_t* block) {
        std::array<uint32_t, 64> words{};
        for (size_t index = 0; index < 16U; ++index) {
            const size_t offset = index * 4U;
            words[index] = (static_cast<uint32_t>(block[offset]) << 24U) |
                           (static_cast<uint32_t>(block[offset + 1U]) << 16U) |
                           (static_cast<uint32_t>(block[offset + 2U]) << 8U) |
                           static_cast<uint32_t>(block[offset + 3U]);
        }
        for (size_t index = 16U; index < words.size(); ++index) {
            const uint32_t s0 = rotate_right(words[index - 15U], 7U) ^
                                rotate_right(words[index - 15U], 18U) ^
                                (words[index - 15U] >> 3U);
            const uint32_t s1 = rotate_right(words[index - 2U], 17U) ^
                                rotate_right(words[index - 2U], 19U) ^
                                (words[index - 2U] >> 10U);
            words[index] = words[index - 16U] + s0 + words[index - 7U] + s1;
        }
        uint32_t a = state_[0];
        uint32_t b = state_[1];
        uint32_t c = state_[2];
        uint32_t d = state_[3];
        uint32_t e = state_[4];
        uint32_t f = state_[5];
        uint32_t g = state_[6];
        uint32_t h = state_[7];
        for (size_t index = 0; index < words.size(); ++index) {
            const uint32_t sum1 = rotate_right(e, 6U) ^ rotate_right(e, 11U) ^ rotate_right(e, 25U);
            const uint32_t choose = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + sum1 + choose + kSha256RoundConstants[index] + words[index];
            const uint32_t sum0 = rotate_right(a, 2U) ^ rotate_right(a, 13U) ^ rotate_right(a, 22U);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
    }

    std::array<uint32_t, 8> state_ = {
        UINT32_C(0x6a09e667), UINT32_C(0xbb67ae85), UINT32_C(0x3c6ef372), UINT32_C(0xa54ff53a),
        UINT32_C(0x510e527f), UINT32_C(0x9b05688c), UINT32_C(0x1f83d9ab), UINT32_C(0x5be0cd19),
    };
    std::array<uint8_t, 64> buffer_{};
    size_t buffer_size_ = 0U;
    uint64_t total_bytes_ = 0U;
};

std::string sha256_hex(const uint8_t* bytes, size_t size) {
    Sha256State state;
    if (!state.update(bytes, size)) return {};
    const auto digest = state.finish();
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (const uint8_t value : digest) {
        out << std::setw(2) << static_cast<unsigned>(value);
    }
    return out.str();
}

std::string sha256_text(const std::string& value) {
    return sha256_hex(reinterpret_cast<const uint8_t*>(value.data()), value.size());
}

bool lower_hex_sha256(const std::string& value) {
    return value.size() == 64U &&
           std::all_of(value.begin(), value.end(), [](unsigned char ch) {
               return std::isdigit(ch) != 0 || (ch >= 'a' && ch <= 'f');
           });
}

bool valid_uuid(const std::string& value) {
    if (value.size() != 36U) return false;
    for (size_t index = 0U; index < value.size(); ++index) {
        if (index == 8U || index == 13U || index == 18U || index == 23U) {
            if (value[index] != '-') return false;
            continue;
        }
        const unsigned char ch = static_cast<unsigned char>(value[index]);
        if (!((ch >= '0' && ch <= '9') ||
              (ch >= 'a' && ch <= 'f') ||
              (ch >= 'A' && ch <= 'F'))) {
            return false;
        }
    }
    return true;
}

bool valid_trigger(const std::string& value) {
    return !value.empty() && value.size() <= 64U &&
           std::all_of(value.begin(), value.end(), [](unsigned char ch) {
               return std::isalnum(ch) != 0 || ch == '_' || ch == ':' || ch == '#' ||
                      ch == '<' || ch == '>' || ch == '|' || ch == '.' || ch == '-';
           });
}

std::string ascii_lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return ch >= 'A' && ch <= 'Z' ? static_cast<char>(ch - 'A' + 'a')
                                      : static_cast<char>(ch);
    });
    return value;
}

bool required_string(const nlohmann::json& object,
                     const char* key,
                     std::string* value,
                     std::string* error,
                     bool allow_empty = false) {
    const auto field = object.find(key);
    if (field == object.end() || !field->is_string()) {
        *error = std::string("Textual inversion field '") + key + "' must be a string.";
        return false;
    }
    *value = field->get<std::string>();
    if (!allow_empty && value->empty()) {
        *error = std::string("Textual inversion field '") + key + "' must not be empty.";
        return false;
    }
    return true;
}

uint64_t little_u64(const uint8_t* bytes) {
    uint64_t value = 0U;
    for (size_t index = 0; index < 8U; ++index) {
        value |= static_cast<uint64_t>(bytes[index]) << (index * 8U);
    }
    return value;
}

uint32_t little_u32(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
           (static_cast<uint32_t>(bytes[1]) << 8U) |
           (static_cast<uint32_t>(bytes[2]) << 16U) |
           (static_cast<uint32_t>(bytes[3]) << 24U);
}

uint16_t little_u16(const uint8_t* bytes) {
    return static_cast<uint16_t>(bytes[0]) |
           static_cast<uint16_t>(static_cast<uint16_t>(bytes[1]) << 8U);
}

float fp16_to_float(uint16_t value) {
    const uint32_t sign = (static_cast<uint32_t>(value) & UINT32_C(0x8000)) << 16U;
    uint32_t exponent = (static_cast<uint32_t>(value) >> 10U) & UINT32_C(0x1f);
    uint32_t mantissa = static_cast<uint32_t>(value) & UINT32_C(0x03ff);
    uint32_t bits = 0U;
    if (exponent == 0U) {
        if (mantissa == 0U) {
            bits = sign;
        } else {
            int shift = 0;
            while ((mantissa & UINT32_C(0x0400)) == 0U) {
                mantissa <<= 1U;
                ++shift;
            }
            mantissa &= UINT32_C(0x03ff);
            bits = sign | (static_cast<uint32_t>(127 - 15 - shift) << 23U) | (mantissa << 13U);
        }
    } else if (exponent == UINT32_C(0x1f)) {
        bits = sign | UINT32_C(0x7f800000) | (mantissa << 13U);
    } else {
        bits = sign | ((exponent + UINT32_C(112)) << 23U) | (mantissa << 13U);
    }
    float result = 0.0f;
    std::memcpy(&result, &bits, sizeof(result));
    return result;
}

struct TensorInfo {
    std::string name;
    std::string dtype;
    std::vector<size_t> shape;
    uint64_t start = 0U;
    uint64_t end = 0U;
};

bool parse_tensor_info(const std::string& name,
                       const nlohmann::json& value,
                       TensorInfo* output,
                       std::string* error) {
    if (output == nullptr || !value.is_object()) {
        *error = "Safetensors tensor metadata must be an object.";
        return false;
    }
    if (value.size() != 3U || !value.contains("dtype") ||
        !value.contains("shape") || !value.contains("data_offsets")) {
        *error = "Safetensors tensor metadata has unknown or missing fields.";
        return false;
    }
    const auto dtype = value.find("dtype");
    const auto shape = value.find("shape");
    const auto offsets = value.find("data_offsets");
    if (dtype == value.end() || !dtype->is_string() ||
        shape == value.end() || !shape->is_array() || shape->empty() ||
        offsets == value.end() || !offsets->is_array() || offsets->size() != 2U) {
        *error = "Safetensors tensor metadata is incomplete.";
        return false;
    }
    output->name = name;
    output->dtype = dtype->get<std::string>();
    output->shape.clear();
    size_t element_count = 1U;
    for (const auto& dimension : *shape) {
        uint64_t dimension_value = 0U;
        if (dimension.is_number_unsigned()) {
            dimension_value = dimension.get<uint64_t>();
        } else if (dimension.is_number_integer()) {
            const int64_t signed_dimension = dimension.get<int64_t>();
            if (signed_dimension > 0) {
                dimension_value = static_cast<uint64_t>(signed_dimension);
            }
        } else {
            *error = "Safetensors shape dimensions must be integers.";
            return false;
        }
        if (dimension_value == 0U ||
            dimension_value > std::numeric_limits<size_t>::max() ||
            element_count > std::numeric_limits<size_t>::max() /
                static_cast<size_t>(dimension_value)) {
            *error = "Safetensors tensor shape is empty or overflows native size_t.";
            return false;
        }
        output->shape.push_back(static_cast<size_t>(dimension_value));
        element_count *= static_cast<size_t>(dimension_value);
    }
    const auto read_nonnegative_u64 = [](const nlohmann::json& number, uint64_t* result) {
        if (number.is_number_unsigned()) {
            *result = number.get<uint64_t>();
            return true;
        }
        if (!number.is_number_integer()) return false;
        const int64_t signed_value = number.get<int64_t>();
        if (signed_value < 0) return false;
        *result = static_cast<uint64_t>(signed_value);
        return true;
    };
    uint64_t start = 0U;
    uint64_t end = 0U;
    if (!read_nonnegative_u64((*offsets)[0], &start) ||
        !read_nonnegative_u64((*offsets)[1], &end)) {
        *error = "Safetensors data offsets must be integers.";
        return false;
    }
    if (end <= start) {
        *error = "Safetensors data offsets are invalid.";
        return false;
    }
    output->start = start;
    output->end = end;
    size_t element_bytes = 0U;
    if (output->dtype == "F16" || output->dtype == "BF16") element_bytes = 2U;
    if (output->dtype == "F32") element_bytes = 4U;
    if (output->dtype == "F64") element_bytes = 8U;
    if (element_bytes == 0U ||
        element_count > std::numeric_limits<uint64_t>::max() / element_bytes ||
        output->end - output->start != static_cast<uint64_t>(element_count * element_bytes)) {
        *error = "Safetensors tensor dtype or byte span is unsupported.";
        return false;
    }
    return true;
}

const TensorInfo* select_tensor(const std::vector<TensorInfo>& tensors,
                                size_t width,
                                bool clip_g,
                                std::string* error) {
    std::vector<const TensorInfo*> matching;
    std::vector<const TensorInfo*> preferred;
    for (const auto& tensor : tensors) {
        if (tensor.shape.back() != width) continue;
        matching.push_back(&tensor);
        const std::string name = ascii_lower(tensor.name);
        const bool preferred_name = clip_g
            ? name.find("clip_g") != std::string::npos
            : name.find("clip_l") != std::string::npos ||
              name.find("emb_params") != std::string::npos ||
              name.find("string_to_param") != std::string::npos;
        if (preferred_name) preferred.push_back(&tensor);
    }
    if (preferred.size() == 1U) return preferred.front();
    if (preferred.size() > 1U || matching.size() > 1U) {
        *error = "Safetensors contains multiple ambiguous tensors for one CLIP embedding width.";
        return nullptr;
    }
    if (matching.empty()) {
        *error = "Safetensors does not contain the required CLIP embedding width.";
        return nullptr;
    }
    return matching.front();
}

bool decode_tensor(const uint8_t* file_bytes,
                   size_t file_size,
                   uint64_t data_start,
                   const TensorInfo& tensor,
                   std::vector<float>* values,
                   std::string* error) {
    if (values == nullptr || data_start > file_size || tensor.end > file_size - data_start) {
        *error = "Safetensors tensor points outside the mapped artifact.";
        return false;
    }
    size_t count = 1U;
    for (const size_t dimension : tensor.shape) count *= dimension;
    const uint8_t* source = file_bytes + data_start + tensor.start;
    values->assign(count, 0.0f);
    for (size_t index = 0U; index < count; ++index) {
        float decoded = 0.0f;
        if (tensor.dtype == "F16") {
            decoded = fp16_to_float(little_u16(source + index * 2U));
        } else if (tensor.dtype == "BF16") {
            const uint32_t bits = static_cast<uint32_t>(little_u16(source + index * 2U)) << 16U;
            std::memcpy(&decoded, &bits, sizeof(decoded));
        } else if (tensor.dtype == "F32") {
            const uint32_t bits = little_u32(source + index * 4U);
            std::memcpy(&decoded, &bits, sizeof(decoded));
        } else {
            const uint64_t bits = little_u64(source + index * 8U);
            double value = 0.0;
            std::memcpy(&value, &bits, sizeof(value));
            decoded = static_cast<float>(value);
        }
        if (!std::isfinite(decoded)) {
            values->clear();
            *error = "Safetensors textual inversion contains a non-finite value.";
            return false;
        }
        (*values)[index] = decoded;
    }
    return true;
}

bool canonical_direct_child(const std::string& root,
                            const std::string& path,
                            std::string* canonical_path,
                            std::string* error) {
    std::array<char, PATH_MAX> root_buffer{};
    std::array<char, PATH_MAX> path_buffer{};
    if (realpath(root.c_str(), root_buffer.data()) == nullptr ||
        realpath(path.c_str(), path_buffer.data()) == nullptr) {
        *error = "Textual inversion root or artifact path cannot be canonicalized.";
        return false;
    }
    const std::string canonical_root(root_buffer.data());
    *canonical_path = std::string(path_buffer.data());
    const auto separator = canonical_path->find_last_of('/');
    if (separator == std::string::npos || canonical_path->substr(0U, separator) != canonical_root) {
        *error = "Textual inversion artifact must be a direct child of the leased app-private root.";
        return false;
    }
    return true;
}

bool load_artifact_tensors(const std::string& root,
                           bool require_clip_g,
                           Artifact* artifact,
                           std::string* error) {
    std::string canonical_path;
    if (!canonical_direct_child(root, artifact->path, &canonical_path, error)) return false;
    const int descriptor = open(canonical_path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        *error = "Textual inversion artifact could not be opened without following links.";
        return false;
    }
    struct stat status {};
    if (fstat(descriptor, &status) != 0 || !S_ISREG(status.st_mode) || status.st_size < 0 ||
        static_cast<uint64_t>(status.st_size) != artifact->size_bytes) {
        close(descriptor);
        *error = "Textual inversion artifact size or file type changed after leasing.";
        return false;
    }
    const size_t file_size = static_cast<size_t>(status.st_size);
    void* mapping = mmap(nullptr, file_size, PROT_READ, MAP_PRIVATE, descriptor, 0);
    close(descriptor);
    if (mapping == MAP_FAILED) {
        *error = "Textual inversion artifact could not be mapped read-only.";
        return false;
    }
    const auto* bytes = static_cast<const uint8_t*>(mapping);
    const auto cleanup = [&]() { munmap(mapping, file_size); };
    if (sha256_hex(bytes, file_size) != artifact->sha256) {
        cleanup();
        *error = "Textual inversion artifact SHA-256 changed after leasing.";
        return false;
    }
    if (file_size < 9U) {
        cleanup();
        *error = "Safetensors artifact is too small for its header.";
        return false;
    }
    const uint64_t header_size = little_u64(bytes);
    if (header_size == 0U || header_size > kMaxHeaderBytes ||
        header_size > static_cast<uint64_t>(file_size - 8U)) {
        cleanup();
        *error = "Safetensors header length is invalid or exceeds 1 MiB.";
        return false;
    }
    nlohmann::json header;
    try {
        header = nlohmann::json::parse(
            reinterpret_cast<const char*>(bytes + 8U),
            reinterpret_cast<const char*>(bytes + 8U + header_size));
    } catch (const nlohmann::json::exception& exception) {
        cleanup();
        *error = std::string("Safetensors header JSON is invalid: ") + exception.what();
        return false;
    }
    if (!header.is_object()) {
        cleanup();
        *error = "Safetensors header must be an object.";
        return false;
    }
    std::vector<TensorInfo> tensors;
    for (const auto& entry : header.items()) {
        if (entry.key() == "__metadata__") {
            if (!entry.value().is_object() ||
                !std::all_of(
                    entry.value().begin(),
                    entry.value().end(),
                    [](const nlohmann::json& value) { return value.is_string(); })) {
                cleanup();
                *error = "Safetensors metadata must be an object with string values.";
                return false;
            }
            continue;
        }
        TensorInfo tensor;
        if (!parse_tensor_info(entry.key(), entry.value(), &tensor, error)) {
            cleanup();
            return false;
        }
        if (tensor.end > static_cast<uint64_t>(file_size) - 8U - header_size) {
            cleanup();
            *error = "Safetensors tensor byte span exceeds the artifact.";
            return false;
        }
        tensors.push_back(std::move(tensor));
    }
    if (tensors.empty()) {
        cleanup();
        *error = "Safetensors does not contain any tensor entries.";
        return false;
    }
    std::vector<const TensorInfo*> ordered_tensors;
    ordered_tensors.reserve(tensors.size());
    for (const auto& tensor : tensors) ordered_tensors.push_back(&tensor);
    std::sort(
        ordered_tensors.begin(),
        ordered_tensors.end(),
        [](const TensorInfo* left, const TensorInfo* right) {
            return left->start < right->start;
        });
    uint64_t expected_start = 0U;
    for (const TensorInfo* tensor : ordered_tensors) {
        if (tensor->start != expected_start) {
            cleanup();
            *error = "Safetensors tensor spans overlap or leave holes.";
            return false;
        }
        expected_start = tensor->end;
    }
    const uint64_t data_size = static_cast<uint64_t>(file_size) - 8U - header_size;
    if (expected_start != data_size) {
        cleanup();
        *error = "Safetensors data contains unreferenced trailing bytes.";
        return false;
    }
    const TensorInfo* clip_l = select_tensor(tensors, kClipLWidth, false, error);
    if (clip_l == nullptr) {
        cleanup();
        return false;
    }
    const size_t clip_l_rows = clip_l->shape.empty()
        ? 0U
        : std::accumulate(
            clip_l->shape.begin(), clip_l->shape.end() - 1U, size_t{1},
            std::multiplies<size_t>());
    if (clip_l_rows == 0U || clip_l_rows > kMaxEmbeddingRows) {
        cleanup();
        *error = "CLIP-L textual inversion has an invalid vector count.";
        return false;
    }
    if (
        !decode_tensor(bytes, file_size, 8U + header_size, *clip_l, &artifact->clip_l, error)) {
        cleanup();
        return false;
    }
    if (require_clip_g) {
        const TensorInfo* clip_g = select_tensor(tensors, kClipGWidth, true, error);
        if (clip_g == nullptr) {
            cleanup();
            return false;
        }
        const size_t clip_g_rows = clip_g->shape.empty()
            ? 0U
            : std::accumulate(
                clip_g->shape.begin(), clip_g->shape.end() - 1U, size_t{1},
                std::multiplies<size_t>());
        if (clip_g_rows != clip_l_rows) {
            cleanup();
            *error = "SDXL textual inversion CLIP-L and CLIP-G vector counts must match.";
            return false;
        }
        if (
            !decode_tensor(bytes, file_size, 8U + header_size, *clip_g, &artifact->clip_g, error)) {
            cleanup();
            return false;
        }
    }
    cleanup();
    return true;
}

bool validate_binding_fingerprint(const Artifact& artifact) {
    const std::array<std::string, 9> parts = {
        "textual-inversion-binding-v1",
        artifact.id,
        artifact.sha256,
        ascii_lower(artifact.trigger),
        ascii_lower(artifact.model_fingerprint),
        ascii_lower(artifact.tokenizer_fingerprint),
        artifact.profile_id,
        std::to_string(artifact.profile_revision),
        artifact.runtime,
    };
    std::string payload;
    for (size_t index = 0; index < parts.size(); ++index) {
        if (index > 0U) payload.push_back('\x1f');
        payload += parts[index];
    }
    return sha256_text(payload) == artifact.binding_fingerprint;
}

size_t bit_count(uint64_t value) {
    size_t count = 0U;
    while (value != 0U) {
        count += static_cast<size_t>(value & UINT64_C(1));
        value >>= 1U;
    }
    return count;
}

}  // namespace

bool load_selection(const std::string& selection_json,
                    const std::string& expected_runtime,
                    bool require_clip_g,
                    Selection* selection,
                    Audit* audit,
                    std::string* error) {
    if (selection == nullptr || audit == nullptr || error == nullptr) return false;
    *selection = Selection{};
    *audit = Audit{};
    error->clear();
    nlohmann::json root;
    try {
        root = nlohmann::json::parse(selection_json);
    } catch (const nlohmann::json::exception& exception) {
        *error = std::string("Textual inversion selection JSON is invalid: ") + exception.what();
        return false;
    }
    if (!root.is_object()) {
        *error = "Textual inversion selection must be a JSON object.";
        return false;
    }
    const auto artifacts = root.find("textualInversions");
    const auto count = root.find("textualInversionCount");
    const auto supported = root.find("textualInversionSupported");
    std::string root_path;
    if (artifacts == root.end() || !artifacts->is_array() || artifacts->empty() ||
        artifacts->size() > 8U || count == root.end() || !count->is_number_integer() ||
        count->get<int64_t>() != static_cast<int64_t>(artifacts->size()) ||
        supported == root.end() || !supported->is_boolean() || !supported->get<bool>() ||
        !required_string(root, "textualInversionBindingFingerprint", &selection->binding_fingerprint, error) ||
        !required_string(root, "textualInversionNativeMode", &selection->native_mode, error) ||
        !required_string(root, "textualInversionRootPath", &root_path, error)) {
        if (error->empty()) *error = "Textual inversion selection metadata is incomplete.";
        return false;
    }
    if (selection->native_mode != "MNN_CLIP_INPUT_EMBEDDING" ||
        !lower_hex_sha256(selection->binding_fingerprint)) {
        *error = "Textual inversion native mode or selection fingerprint is invalid.";
        return false;
    }
    selection->clip_g_required = require_clip_g;
    audit->requested_count = artifacts->size();
    audit->requested_mask = (UINT64_C(1) << artifacts->size()) - UINT64_C(1);
    std::vector<std::string> ids;
    std::vector<std::string> canonical_paths;
    std::vector<std::string> triggers;
    uint64_t active_bytes = 0U;
    constexpr uint64_t kMaxActiveBytes = UINT64_C(256) * 1024U * 1024U;
    for (size_t index = 0U; index < artifacts->size(); ++index) {
        const auto& value = (*artifacts)[index];
        if (!value.is_object()) {
            *error = "Textual inversion binding must be an object.";
            return false;
        }
        Artifact artifact;
        artifact.index = index;
        int64_t size_bytes = 0;
        int64_t profile_revision = 0;
        const auto size_field = value.find("sizeBytes");
        const auto revision_field = value.find("profileRevision");
        if (!required_string(value, "id", &artifact.id, error) ||
            !required_string(value, "name", &artifact.name, error) ||
            !required_string(value, "trigger", &artifact.trigger, error) ||
            !required_string(value, "path", &artifact.path, error) ||
            !required_string(value, "sha256", &artifact.sha256, error) ||
            !required_string(value, "format", &artifact.format, error) ||
            !required_string(value, "modelFingerprint", &artifact.model_fingerprint, error) ||
            !required_string(value, "tokenizerFingerprint", &artifact.tokenizer_fingerprint, error) ||
            !required_string(value, "profileId", &artifact.profile_id, error) ||
            !required_string(value, "runtime", &artifact.runtime, error) ||
            !required_string(value, "bindingFingerprint", &artifact.binding_fingerprint, error) ||
            size_field == value.end() || !size_field->is_number_integer() ||
            revision_field == value.end() || !revision_field->is_number_integer()) {
            if (error->empty()) *error = "Textual inversion binding metadata is incomplete.";
            return false;
        }
        size_bytes = size_field->get<int64_t>();
        profile_revision = revision_field->get<int64_t>();
        if (size_bytes < static_cast<int64_t>(kMinArtifactBytes) ||
            size_bytes > static_cast<int64_t>(kMaxArtifactBytes) ||
            profile_revision <= 0 || profile_revision > std::numeric_limits<int>::max() ||
            !valid_uuid(artifact.id) || artifact.format != "safetensors" ||
            artifact.runtime != expected_runtime ||
            !valid_trigger(artifact.trigger) || !lower_hex_sha256(artifact.sha256) ||
            !lower_hex_sha256(artifact.model_fingerprint) ||
            !lower_hex_sha256(artifact.tokenizer_fingerprint) ||
            !lower_hex_sha256(artifact.binding_fingerprint)) {
            *error = "Textual inversion binding id, format, runtime, digest, trigger, or bounds are invalid.";
            return false;
        }
        artifact.size_bytes = static_cast<uint64_t>(size_bytes);
        if (artifact.size_bytes > kMaxActiveBytes - active_bytes) {
            *error = "Selected textual inversion artifacts exceed the 256 MiB active-request quota.";
            return false;
        }
        active_bytes += artifact.size_bytes;
        artifact.profile_revision = static_cast<int>(profile_revision);
        const std::string normalized_id = ascii_lower(artifact.id);
        const std::string normalized_trigger = ascii_lower(artifact.trigger);
        if (std::find(ids.begin(), ids.end(), normalized_id) != ids.end() ||
            std::find(triggers.begin(), triggers.end(), normalized_trigger) != triggers.end()) {
            *error = "Textual inversion ids and triggers must be unique ignoring ASCII case.";
            return false;
        }
        std::string canonical_path;
        if (!canonical_direct_child(root_path, artifact.path, &canonical_path, error)) return false;
        if (std::find(canonical_paths.begin(), canonical_paths.end(), canonical_path) !=
            canonical_paths.end()) {
            *error = "Textual inversion canonical artifact paths must be unique.";
            return false;
        }
        artifact.path = std::move(canonical_path);
        ids.push_back(normalized_id);
        triggers.push_back(normalized_trigger);
        canonical_paths.push_back(artifact.path);
        if (!validate_binding_fingerprint(artifact)) {
            *error = "Textual inversion binding fingerprint does not match its immutable fields.";
            return false;
        }
        ++audit->validated_count;
        ++audit->load_attempt_count;
        if (!load_artifact_tensors(root_path, require_clip_g, &artifact, error)) return false;
        ++audit->loaded_count;
        audit->loaded_mask |= UINT64_C(1) << index;
        selection->artifacts.push_back(std::move(artifact));
    }
    std::vector<std::string> binding_fingerprints;
    binding_fingerprints.reserve(selection->artifacts.size());
    for (const auto& artifact : selection->artifacts) {
        binding_fingerprints.push_back(artifact.binding_fingerprint);
    }
    std::sort(binding_fingerprints.begin(), binding_fingerprints.end(), [&](const auto& left, const auto& right) {
        const auto find_trigger = [&](const std::string& fingerprint) {
            const auto found = std::find_if(selection->artifacts.begin(), selection->artifacts.end(),
                [&](const Artifact& artifact) { return artifact.binding_fingerprint == fingerprint; });
            return found == selection->artifacts.end() ? std::string() : ascii_lower(found->trigger);
        };
        return find_trigger(left) < find_trigger(right);
    });
    std::string selection_payload = "textual-inversion-selection-v1";
    for (const auto& fingerprint : binding_fingerprints) {
        selection_payload.push_back('\x1f');
        selection_payload += fingerprint;
    }
    if (sha256_text(selection_payload) != selection->binding_fingerprint ||
        audit->loaded_mask != audit->requested_mask) {
        *error = "Textual inversion selection fingerprint or loaded mask is invalid.";
        return false;
    }
    return true;
}

std::vector<ClipTextualInversionEmbedding> clip_embeddings(const Selection& selection,
                                                           bool clip_g) {
    std::vector<ClipTextualInversionEmbedding> result;
    result.reserve(selection.artifacts.size());
    for (const auto& artifact : selection.artifacts) {
        result.push_back(ClipTextualInversionEmbedding{
            artifact.index,
            artifact.trigger,
            clip_g ? kClipGWidth : kClipLWidth,
            clip_g ? artifact.clip_g : artifact.clip_l,
        });
    }
    return result;
}

void record_conditioned_pair(const ClipConditionedPair& pair,
                             bool clip_g,
                             Audit* audit) {
    if (audit == nullptr) return;
    if (clip_g) {
        audit->clip_g_match_mask = pair.tokenizer_match_mask();
        audit->clip_g_mask = pair.applied_mask();
    } else {
        audit->clip_l_match_mask = pair.tokenizer_match_mask();
        audit->clip_l_mask = pair.applied_mask();
    }
    audit->applied_vector_count += pair.applied_vector_count();
}

nlohmann::json artifacts_json(const Selection& selection) {
    nlohmann::json result = nlohmann::json::array();
    for (const auto& artifact : selection.artifacts) {
        result.push_back({
            {"id", artifact.id},
            {"name", artifact.name},
            {"trigger", artifact.trigger},
            {"sha256", artifact.sha256},
            {"sizeBytes", artifact.size_bytes},
            {"format", artifact.format},
            {"modelFingerprint", artifact.model_fingerprint},
            {"tokenizerFingerprint", artifact.tokenizer_fingerprint},
            {"profileId", artifact.profile_id},
            {"profileRevision", artifact.profile_revision},
            {"runtime", artifact.runtime},
            {"bindingFingerprint", artifact.binding_fingerprint},
        });
    }
    return result;
}

nlohmann::json evidence_json(const Selection& selection,
                             const Audit& audit,
                             bool conditioning_consumed) {
    const uint64_t tokenizer_match_mask = selection.clip_g_required
        ? audit.clip_l_match_mask & audit.clip_g_match_mask
        : audit.clip_l_match_mask;
    const uint64_t applied_mask = selection.clip_g_required
        ? audit.clip_l_mask & audit.clip_g_mask
        : audit.clip_l_mask;
    const uint64_t consumed_mask = conditioning_consumed ? applied_mask : 0U;
    return {
        {"requestedCount", audit.requested_count},
        {"validatedCount", audit.validated_count},
        {"loadAttemptCount", audit.load_attempt_count},
        {"loadedCount", audit.loaded_count},
        {"tokenizerMatchCount", bit_count(tokenizer_match_mask)},
        {"appliedCount", bit_count(applied_mask)},
        {"appliedVectorCount", audit.applied_vector_count},
        {"conditioningConsumptionCount", bit_count(consumed_mask)},
        {"clipLAppliedCount", bit_count(audit.clip_l_mask)},
        {"clipGAppliedCount", bit_count(audit.clip_g_mask)},
        {"requestedMask", audit.requested_mask},
        {"loadedMask", audit.loaded_mask},
        {"tokenizerMatchMask", tokenizer_match_mask},
        {"appliedMask", applied_mask},
        {"consumedMask", consumed_mask},
        {"clipLMask", audit.clip_l_mask},
        {"clipGMask", audit.clip_g_mask},
        {"clipGRequiredMask", selection.clip_g_required ? audit.requested_mask : 0U},
        {"bindingFingerprint", selection.binding_fingerprint},
        {"nativeMode", selection.native_mode},
        {"bindingStage", conditioning_consumed ? "conditioning_consumed" : "conditioning_encoded"},
        {"failureCode", "none"},
    };
}

}  // namespace mca::image::textual_inversion
