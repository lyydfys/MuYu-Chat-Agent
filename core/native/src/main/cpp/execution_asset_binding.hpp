#pragma once

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <iomanip>
#include <limits.h>
#include <limits>
#include <set>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#if __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "json.hpp"
#endif

namespace mca::image::execution_assets {

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif

using json = nlohmann::json;

struct Identity {
    uint64_t device = 0;
    uint64_t inode = 0;
    uint64_t size = 0;
    int64_t modification_time = 0;
    int64_t status_change_time = 0;
};

inline Identity identity_of(const struct stat& value) {
    return Identity{
        static_cast<uint64_t>(value.st_dev),
        static_cast<uint64_t>(value.st_ino),
        value.st_size >= 0 ? static_cast<uint64_t>(value.st_size) : 0U,
        static_cast<int64_t>(value.st_mtime),
        static_cast<int64_t>(value.st_ctime),
    };
}

inline bool same_identity(const Identity& left, const Identity& right) {
    return left.device == right.device && left.inode == right.inode &&
           left.size == right.size && left.modification_time == right.modification_time &&
           left.status_change_time == right.status_change_time;
}

class Sha256 {
public:
    void update(const uint8_t* bytes, size_t size) {
        if (bytes == nullptr || size == 0U) return;
        total_bytes_ += static_cast<uint64_t>(size);
        if (buffer_size_ > 0U) {
            const size_t consumed = std::min(size, buffer_.size() - buffer_size_);
            std::memcpy(buffer_.data() + buffer_size_, bytes, consumed);
            buffer_size_ += consumed;
            bytes += consumed;
            size -= consumed;
            if (buffer_size_ == buffer_.size()) {
                transform(buffer_.data());
                buffer_size_ = 0U;
            }
        }
        while (size >= buffer_.size()) {
            transform(bytes);
            bytes += buffer_.size();
            size -= buffer_.size();
        }
        if (size > 0U) {
            std::memcpy(buffer_.data(), bytes, size);
            buffer_size_ = size;
        }
    }

    std::string finish_hex() {
        const uint64_t bit_count = total_bytes_ * 8U;
        buffer_[buffer_size_++] = 0x80U;
        if (buffer_size_ > 56U) {
            std::fill(buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_), buffer_.end(), 0U);
            transform(buffer_.data());
            buffer_size_ = 0U;
        }
        std::fill(
            buffer_.begin() + static_cast<ptrdiff_t>(buffer_size_),
            buffer_.begin() + 56,
            0U);
        for (size_t index = 0; index < 8U; ++index) {
            buffer_[63U - index] = static_cast<uint8_t>(bit_count >> (index * 8U));
        }
        transform(buffer_.data());
        static constexpr char hex[] = "0123456789abcdef";
        std::string result(64U, '0');
        size_t output = 0U;
        for (const uint32_t word : state_) {
            for (int shift = 28; shift >= 0; shift -= 4) {
                result[output++] = hex[(word >> static_cast<uint32_t>(shift)) & 0x0fU];
            }
        }
        return result;
    }

private:
    static uint32_t rotate_right(uint32_t value, uint32_t amount) {
        return (value >> amount) | (value << (32U - amount));
    }

    void transform(const uint8_t* block) {
        static constexpr std::array<uint32_t, 64> constants = {{
            0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
            0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
            0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
            0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
            0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
            0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
            0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
            0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
            0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
            0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
            0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
            0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
            0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
            0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
            0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
            0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
        }};
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
        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (size_t index = 0; index < words.size(); ++index) {
            const uint32_t s1 = rotate_right(e, 6U) ^ rotate_right(e, 11U) ^ rotate_right(e, 25U);
            const uint32_t choose = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + s1 + choose + constants[index] + words[index];
            const uint32_t s0 = rotate_right(a, 2U) ^ rotate_right(a, 13U) ^ rotate_right(a, 22U);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = s0 + majority;
            h = g; g = f; f = e; e = d + temp1;
            d = c; c = b; b = a; a = temp1 + temp2;
        }
        state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
        state_[4] += e; state_[5] += f; state_[6] += g; state_[7] += h;
    }

    std::array<uint32_t, 8> state_ = {{
        0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
        0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
    }};
    std::array<uint8_t, 64> buffer_{};
    size_t buffer_size_ = 0U;
    uint64_t total_bytes_ = 0U;
};

inline bool lowercase_sha256(const std::string& value) {
    return value.size() == 64U && std::all_of(value.begin(), value.end(), [](char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
    });
}

inline std::string sha256_utf8(const std::string& value) {
    Sha256 digest;
    digest.update(reinterpret_cast<const uint8_t*>(value.data()), value.size());
    return digest.finish_hex();
}

struct Asset {
    std::string label;
    std::string path;
    uint64_t size = 0U;
    std::string sha256;
    Identity identity;
};

struct Binding {
    std::string runtime;
    std::string bundle_root;
    std::string profile_prompt_fingerprint;
    std::string composite_sha256;
    std::vector<Asset> assets;
};

inline std::string composite_sha256(const Binding& binding) {
    std::ostringstream source;
    source << "textual-inversion-execution-assets-v1" << '\x1f'
           << binding.runtime << '\x1f' << binding.profile_prompt_fingerprint;
    for (const auto& asset : binding.assets) {
        source << '\x1f' << asset.label << '\x1f' << asset.size << '\x1f' << asset.sha256;
    }
    return sha256_utf8(source.str());
}

class ScopedFd {
public:
    explicit ScopedFd(int value) : value_(value) {}
    ~ScopedFd() { if (value_ >= 0) close(value_); }
    int get() const { return value_; }
private:
    int value_;
};

inline bool hash_asset(
    Asset& asset,
    Identity& identity,
    std::string& digest,
    const std::function<bool()>& cancelled,
    std::string& error) {
    char canonical[PATH_MAX] = {};
    struct stat path_before{};
    if (asset.path.empty() || realpath(asset.path.c_str(), canonical) == nullptr ||
        asset.path != canonical || lstat(asset.path.c_str(), &path_before) != 0 ||
        !S_ISREG(path_before.st_mode)) {
        error = "execution asset is not a canonical non-symlink regular file: " + asset.label;
        return false;
    }
    ScopedFd fd(open(asset.path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    struct stat descriptor_before{};
    if (fd.get() < 0 || fstat(fd.get(), &descriptor_before) != 0 ||
        !S_ISREG(descriptor_before.st_mode)) {
        error = "execution asset could not be opened through a regular-file descriptor: " + asset.label;
        return false;
    }
    identity = identity_of(descriptor_before);
    if (!same_identity(identity, identity_of(path_before)) || identity.size != asset.size) {
        error = "execution asset identity or size changed before hashing: " + asset.label;
        return false;
    }
    Sha256 accumulator;
    std::array<uint8_t, 64U * 1024U> buffer{};
    uint64_t read_bytes = 0U;
    while (true) {
        if (cancelled && cancelled()) {
            error = "cancelled";
            return false;
        }
        const ssize_t count = read(fd.get(), buffer.data(), buffer.size());
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            error = "execution asset could not be streamed for hashing: " + asset.label;
            return false;
        }
        accumulator.update(buffer.data(), static_cast<size_t>(count));
        read_bytes += static_cast<uint64_t>(count);
        if (read_bytes > asset.size) {
            error = "execution asset grew while hashing: " + asset.label;
            return false;
        }
    }
    struct stat descriptor_after{};
    struct stat path_after{};
    if (fstat(fd.get(), &descriptor_after) != 0 || lstat(asset.path.c_str(), &path_after) != 0 ||
        !same_identity(identity, identity_of(descriptor_after)) ||
        !same_identity(identity, identity_of(path_after)) || read_bytes != asset.size) {
        error = "execution asset changed while hashing: " + asset.label;
        return false;
    }
    digest = accumulator.finish_hex();
    if (digest != asset.sha256) {
        error = "execution asset digest differs from the consumer snapshot: " + asset.label;
        return false;
    }
    return true;
}

inline bool parse(const json& source, const std::string& expected_runtime, Binding& binding,
                  std::string& error) {
    const auto assets_it = source.find("textualInversionExecutionAssets");
    if (assets_it == source.end() || !assets_it->is_array() || assets_it->empty() ||
        assets_it->size() > 64U) {
        error = "textual inversion execution assets must be a non-empty bounded array";
        return false;
    }
    const auto string_field = [&](const char* name, std::string& value) {
        const auto it = source.find(name);
        if (it == source.end() || !it->is_string()) return false;
        value = it->get<std::string>();
        return !value.empty();
    };
    if (!string_field("textualInversionExecutionRuntime", binding.runtime) ||
        binding.runtime != expected_runtime ||
        !string_field("textualInversionExecutionBundleRoot", binding.bundle_root) ||
        !string_field(
            "textualInversionExecutionProfileFingerprint",
            binding.profile_prompt_fingerprint) ||
        !lowercase_sha256(binding.profile_prompt_fingerprint) ||
        !string_field("textualInversionExecutionAssetsSha256", binding.composite_sha256) ||
        !lowercase_sha256(binding.composite_sha256)) {
        error = "textual inversion execution asset runtime or fingerprint is invalid";
        return false;
    }
    char canonical_root[PATH_MAX] = {};
    struct stat root_stat{};
    if (realpath(binding.bundle_root.c_str(), canonical_root) == nullptr ||
        binding.bundle_root != canonical_root || stat(canonical_root, &root_stat) != 0 ||
        !S_ISDIR(root_stat.st_mode)) {
        error = "textual inversion execution bundle root is not a canonical directory";
        return false;
    }
    std::set<std::string> labels;
    std::set<std::string> paths;
    std::string previous_label;
    uint64_t total_size = 0U;
    constexpr uint64_t kMaxExecutionAssetBytes = UINT64_C(64) * 1024U * 1024U * 1024U;
    for (const auto& item : *assets_it) {
        if (!item.is_object()) {
            error = "textual inversion execution asset must be an object";
            return false;
        }
        Asset asset;
        const auto label = item.find("label");
        const auto path = item.find("path");
        const auto size = item.find("sizeBytes");
        const auto sha = item.find("sha256");
        if (label == item.end() || !label->is_string() || path == item.end() || !path->is_string() ||
            size == item.end() || (!size->is_number_unsigned() && !size->is_number_integer()) ||
            sha == item.end() || !sha->is_string()) {
            error = "textual inversion execution asset fields are invalid";
            return false;
        }
        asset.label = label->get<std::string>();
        asset.path = path->get<std::string>();
        if (size->is_number_integer() && size->get<int64_t>() <= 0) {
            error = "textual inversion execution asset size must be positive";
            return false;
        }
        asset.size = size->get<uint64_t>();
        asset.sha256 = sha->get<std::string>();
        const std::string expected_path = binding.bundle_root + "/" + asset.label;
        if (asset.label.empty() || asset.label.size() > 4096U ||
            asset.label.find('\x1f') != std::string::npos || asset.label.front() == '/' ||
            asset.label.find("\\") != std::string::npos ||
            asset.label == ".." || asset.label.find("../") == 0U ||
            asset.label.find("/../") != std::string::npos ||
            (asset.label.size() >= 3U &&
                asset.label.compare(asset.label.size() - 3U, 3U, "/..") == 0) ||
            asset.path.empty() || asset.path != expected_path ||
            asset.path.front() != '/' || asset.size == 0U || !lowercase_sha256(asset.sha256) ||
            (!previous_label.empty() && asset.label <= previous_label) ||
            !labels.insert(asset.label).second || !paths.insert(asset.path).second) {
            error = "textual inversion execution asset descriptor is not canonical";
            return false;
        }
        if (asset.size > kMaxExecutionAssetBytes - total_size) {
            error = "textual inversion execution assets exceed the 64 GiB aggregate bound";
            return false;
        }
        total_size += asset.size;
        previous_label = asset.label;
        binding.assets.push_back(std::move(asset));
    }
    if (composite_sha256(binding) != binding.composite_sha256) {
        error = "textual inversion execution asset composite is invalid";
        return false;
    }
    return true;
}

inline bool verify_initial(Binding& binding, const std::function<bool()>& cancelled,
                           std::string& error) {
    for (auto& asset : binding.assets) {
        Identity identity;
        std::string digest;
        if (!hash_asset(asset, identity, digest, cancelled, error)) return false;
        asset.identity = identity;
    }
    return true;
}

inline bool verify_final(Binding& binding, const std::function<bool()>& cancelled,
                         std::string& error) {
    for (auto& asset : binding.assets) {
        Identity identity;
        std::string digest;
        if (!hash_asset(asset, identity, digest, cancelled, error) ||
            !same_identity(asset.identity, identity)) {
            if (error.empty()) error = "execution asset identity changed during native execution: " + asset.label;
            return false;
        }
    }
    return true;
}

inline void append_evidence(json& target, const Binding& binding) {
    target["textualInversionExecutionAssets"] = json::array();
    for (const auto& asset : binding.assets) {
        target["textualInversionExecutionAssets"].push_back({
            {"label", asset.label},
            {"path", asset.path},
            {"sizeBytes", asset.size},
            {"sha256", asset.sha256},
        });
    }
    target["textualInversionExecutionAssetsSha256"] = binding.composite_sha256;
    target["textualInversionExecutionRuntime"] = binding.runtime;
    target["textualInversionExecutionBundleRoot"] = binding.bundle_root;
    target["textualInversionExecutionProfileFingerprint"] = binding.profile_prompt_fingerprint;
}

}  // namespace mca::image::execution_assets
