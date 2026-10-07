#include "sd_input_contract.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <cstring>
#include <fstream>
#include <sstream>

#include "mca/common/logging.hpp"

namespace mca {
namespace sd {

namespace {

inline uint32_t rotate_right(uint32_t value, uint32_t amount) noexcept {
    return (value >> amount) | (value << (32u - amount));
}

class Sha256Accumulator {
public:
    void update(const uint8_t* bytes, size_t size) {
        if (size == 0u) {
            return;
        }
        total_bytes_ += static_cast<uint64_t>(size);
        if (buffer_size_ > 0u) {
            const size_t consumed = std::min(size, buffer_.size() - buffer_size_);
            std::memcpy(buffer_.data() + buffer_size_, bytes, consumed);
            buffer_size_ += consumed;
            bytes += consumed;
            size -= consumed;
            if (buffer_size_ == buffer_.size()) {
                transform(buffer_.data());
                buffer_size_ = 0u;
            }
        }
        while (size >= buffer_.size()) {
            transform(bytes);
            bytes += buffer_.size();
            size -= buffer_.size();
        }
        if (size > 0u) {
            std::memcpy(buffer_.data(), bytes, size);
            buffer_size_ = size;
        }
    }

    std::string finish_hex() {
        const uint64_t bit_count = total_bytes_ * 8u;
        buffer_[buffer_size_++] = 0x80u;
        if (buffer_size_ > 56u) {
            std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_), buffer_.end(), 0u);
            transform(buffer_.data());
            buffer_size_ = 0u;
        }
        std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_),
                  buffer_.begin() + 56, 0u);
        for (size_t index = 0; index < 8u; ++index) {
            buffer_[63u - index] = static_cast<uint8_t>(bit_count >> (index * 8u));
        }
        transform(buffer_.data());

        static constexpr char hex[] = "0123456789abcdef";
        std::string result(64, '0');
        size_t output = 0;
        for (uint32_t word : state_) {
            for (int shift = 28; shift >= 0; shift -= 4) {
                result[output++] = hex[(word >> static_cast<uint32_t>(shift)) & 0x0fu];
            }
        }
        return result;
    }

private:
    void transform(const uint8_t* block) {
        static constexpr std::array<uint32_t, 64> constants = {{
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
            0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
            0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
            0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
            0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
            0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
            0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
            0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
            0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
        }};
        std::array<uint32_t, 64> words = {};
        for (size_t i = 0; i < 16; ++i) {
            const size_t offset = i * 4;
            words[i] = (static_cast<uint32_t>(block[offset]) << 24u) |
                       (static_cast<uint32_t>(block[offset + 1]) << 16u) |
                       (static_cast<uint32_t>(block[offset + 2]) << 8u) |
                       static_cast<uint32_t>(block[offset + 3]);
        }
        for (size_t i = 16; i < words.size(); ++i) {
            const uint32_t s0 = rotate_right(words[i - 15], 7u) ^
                                rotate_right(words[i - 15], 18u) ^
                                (words[i - 15] >> 3u);
            const uint32_t s1 = rotate_right(words[i - 2], 17u) ^
                                rotate_right(words[i - 2], 19u) ^
                                (words[i - 2] >> 10u);
            words[i] = words[i - 16] + s0 + words[i - 7] + s1;
        }
        uint32_t a = state_[0];
        uint32_t b = state_[1];
        uint32_t c = state_[2];
        uint32_t d = state_[3];
        uint32_t e = state_[4];
        uint32_t f = state_[5];
        uint32_t g = state_[6];
        uint32_t h = state_[7];
        for (size_t i = 0; i < words.size(); ++i) {
            const uint32_t sigma1 = rotate_right(e, 6u) ^
                                    rotate_right(e, 11u) ^
                                    rotate_right(e, 25u);
            const uint32_t choose = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + sigma1 + choose + constants[i] + words[i];
            const uint32_t sigma0 = rotate_right(a, 2u) ^
                                    rotate_right(a, 13u) ^
                                    rotate_right(a, 22u);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = sigma0 + majority;
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

    std::array<uint32_t, 8> state_ = {{
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    }};
    std::array<uint8_t, 64> buffer_ = {};
    size_t buffer_size_ = 0u;
    uint64_t total_bytes_ = 0u;
};

std::string lower_copy(std::string str) {
    std::transform(str.begin(), str.end(), str.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return str;
}

mca::contract::ErrorEnvelope make_validation_error(
    mca::contract::StableErrorCode code,
    const std::string& message,
    const mca::contract::RequestIdentity& identity,
    mca::contract::ErrorCategory category = mca::contract::ErrorCategory::Validation
) {
    mca::contract::ErrorEnvelope env;
    env.code = code;
    env.category = category;
    env.phase = mca::contract::ErrorPhase::Validate;
    env.retryability = mca::contract::Retryability::UserAction;
    env.public_message = message;
    env.user_message_key = "error.validation";
    env.cause_chain.push_back(message);
    env.identity = identity;
    const auto now = std::chrono::system_clock::now();
    env.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();
    return env;
}

} // namespace

std::string SdInputValidator::compute_sha256(const std::vector<uint8_t>& bytes) {
    Sha256Accumulator acc;
    if (!bytes.empty()) {
        acc.update(bytes.data(), bytes.size());
    }
    return acc.finish_hex();
}

std::string SdInputValidator::compute_file_sha256(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) {
        return "";
    }
    Sha256Accumulator acc;
    std::vector<uint8_t> buffer(64 * 1024);
    while (file.good()) {
        file.read(reinterpret_cast<char*>(buffer.data()), buffer.size());
        const auto count = file.gcount();
        if (count > 0) {
            acc.update(buffer.data(), static_cast<size_t>(count));
        }
    }
    return acc.finish_hex();
}

std::optional<mca::contract::ErrorEnvelope> SdInputValidator::validate_prompt(
    const std::string& prompt,
    const mca::contract::RequestIdentity& identity
) const {
    if (prompt.size() > 32768) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Prompt exceeds maximum supported length (32KB)",
            identity
        );
    }
    return std::nullopt;
}

std::optional<mca::contract::ErrorEnvelope> SdInputValidator::validate_dimensions(
    int32_t width,
    int32_t height,
    const mca::contract::RequestIdentity& identity
) const {
    if (width <= 0 || height <= 0) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Image dimensions must be positive integers",
            identity
        );
    }
    if (width > kMaxDimension || height > kMaxDimension) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Image dimensions exceed maximum supported size of 4096px",
            identity
        );
    }
    if (width % 8 != 0 || height % 8 != 0) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Image width and height must be multiples of 8",
            identity
        );
    }
    const uint64_t pixels = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (pixels > kMaxInputImagePixels) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Image pixel count exceeds memory safety boundary",
            identity
        );
    }
    return std::nullopt;
}

std::optional<mca::contract::ErrorEnvelope> SdInputValidator::validate_image_file(
    const std::string& path,
    const std::string& expected_sha256,
    const std::string& role_name,
    const mca::contract::RequestIdentity& identity
) const {
    if (path.empty()) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_MISSING_REQUIRED_FIELD,
            role_name + " image path is required",
            identity
        );
    }

    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            role_name + " image file could not be opened: " + path,
            identity
        );
    }

    const auto file_size = file.tellg();
    if (file_size <= 0) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_FORMAT,
            role_name + " image file is empty",
            identity
        );
    }
    if (static_cast<uint64_t>(file_size) > kMaxInputImageBytes) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            role_name + " image file exceeds 32MB limit",
            identity
        );
    }

    if (!expected_sha256.empty()) {
        const std::string computed = compute_file_sha256(path);
        if (lower_copy(computed) != lower_copy(expected_sha256)) {
            return make_validation_error(
                mca::contract::StableErrorCode::MDL_CHECKSUM_MISMATCH,
                role_name + " image SHA-256 mismatch (expected: " + expected_sha256 +
                    ", got: " + computed + ")",
                identity,
                mca::contract::ErrorCategory::Model
            );
        }
    }

    return std::nullopt;
}

std::optional<mca::contract::ErrorEnvelope> SdInputValidator::validate(
    const ImageInputContract& contract,
    const mca::contract::RequestIdentity& identity
) const {
    // Invariant: UNIVERSAL DEVICE ADMISSION
    // We never inspect device models, chipsets, or allowlists here.
    // Untested, uncertified, or unknown profiles must be admitted to the generic path.

    // 1. Prompt bounds
    if (auto err = validate_prompt(contract.prompt, identity)) {
        return err;
    }

    // 2. Dimension constraints
    if (auto err = validate_dimensions(contract.width, contract.height, identity)) {
        return err;
    }

    // 3. Generation parameters
    if (contract.steps < 1 || contract.steps > kMaxSteps) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Sampling steps must be between 1 and 150",
            identity
        );
    }
    if (contract.cfg_scale < 0.0 || contract.cfg_scale > kMaxCfgScale) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "CFG scale must be between 0.0 and 30.0",
            identity
        );
    }
    if (contract.strength < 0.0 || contract.strength > 1.0) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Denoising strength must be in range [0.0, 1.0]",
            identity
        );
    }
    if (contract.batch_count < 1 || contract.batch_count > kMaxBatchCount) {
        return make_validation_error(
            mca::contract::StableErrorCode::VAL_INVALID_INPUT,
            "Batch count must be between 1 and 8",
            identity
        );
    }

    // 4. Role-specific input files
    if (task_uses_init_image(contract.task_mode)) {
        if (auto err = validate_image_file(contract.input_image_path,
                                           contract.input_image_sha256,
                                           "Initial",
                                           identity)) {
            return err;
        }
    }
    if (task_uses_mask_image(contract.task_mode)) {
        if (auto err = validate_image_file(contract.mask_image_path,
                                           contract.mask_image_sha256,
                                           "Mask",
                                           identity)) {
            return err;
        }
    }
    if (task_uses_control_image(contract.task_mode) || contract.control_net.enabled) {
        if (auto err = validate_image_file(contract.control_net.control_image_path,
                                           contract.control_net.control_image_sha256,
                                           "ControlNet",
                                           identity)) {
            return err;
        }
    }

    return std::nullopt;
}

} // namespace sd
} // namespace mca
