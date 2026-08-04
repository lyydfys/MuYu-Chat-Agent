#include "mnn_qnn_prompt_handoff.hpp"

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

namespace mca::image::prompt_handoff {
namespace {

using Clock = std::chrono::steady_clock;

constexpr size_t kRegistryCapacity = 64U;
constexpr size_t kSha256HexCharacters = 64U;
constexpr size_t kRandomHandleBytes = 32U;
constexpr size_t kMaximumCanonicalPathBytes = 4095U;
constexpr auto kRecordLifetime = std::chrono::minutes(2);

struct Entry {
    Record record;
    Clock::time_point expires_at;
};

std::mutex registry_mutex;
std::unordered_map<std::string, Entry> registry;

bool is_lowercase_sha256(const std::string& value) {
    if (value.size() != kSha256HexCharacters) return false;
    for (const char character : value) {
        if (!((character >= '0' && character <= '9') ||
              (character >= 'a' && character <= 'f'))) {
            return false;
        }
    }
    return true;
}

bool is_canonical_absolute_path(const std::string& value) {
    if (value.size() < 2U || value.size() > kMaximumCanonicalPathBytes ||
        value.front() != '/' || value.back() == '/') {
        return false;
    }

    size_t segment_start = 1U;
    for (size_t index = 1U; index <= value.size(); ++index) {
        if (index != value.size() && value[index] != '/') {
            const unsigned char character = static_cast<unsigned char>(value[index]);
            if (character == '\\' || character == 0U || character < 0x20U ||
                character == 0x7fU) {
                return false;
            }
            continue;
        }

        const size_t segment_size = index - segment_start;
        if (segment_size == 0U ||
            (segment_size == 1U && value[segment_start] == '.') ||
            (segment_size == 2U && value[segment_start] == '.' &&
             value[segment_start + 1U] == '.')) {
            return false;
        }
        segment_start = index + 1U;
    }
    return true;
}

bool validate_record(const Record& record, std::string& error) {
    if (!is_canonical_absolute_path(record.tokenizer_canonical_path)) {
        error = "tokenizer canonical path is not a safe canonical absolute path";
        return false;
    }
    // Some valid Android filesystems expose zero-valued device or inode identities. Exact
    // equality, canonical path, size, and SHA-256 still bind the issued and observed snapshots.
    if (record.tokenizer_size_bytes == 0U) {
        error = "tokenizer size must be positive";
        return false;
    }
    if (!is_lowercase_sha256(record.tokenizer_sha256)) {
        error = "tokenizer SHA-256 must be 64 lowercase hexadecimal characters";
        return false;
    }
    if (!is_lowercase_sha256(record.prompt_pair_sha256)) {
        error = "prompt-pair SHA-256 must be 64 lowercase hexadecimal characters";
        return false;
    }
    if (!is_lowercase_sha256(record.payload_sha256)) {
        error = "payload SHA-256 must be 64 lowercase hexadecimal characters";
        return false;
    }
    if (!is_lowercase_sha256(record.prompt_to_encoder_closure_sha256)) {
        error = "prompt-to-encoder closure SHA-256 must be 64 lowercase hexadecimal characters";
        return false;
    }
    return true;
}

bool read_random_handle(std::string& handle, std::string& error) {
    std::FILE* random_source = std::fopen("/dev/urandom", "rb");
    if (random_source == nullptr) {
        error = "unable to open /dev/urandom";
        return false;
    }

    std::array<unsigned char, kRandomHandleBytes> random_bytes{};
    size_t offset = 0U;
    while (offset < random_bytes.size()) {
        const size_t read = std::fread(
            random_bytes.data() + offset,
            1U,
            random_bytes.size() - offset,
            random_source);
        if (read == 0U) {
            error = std::ferror(random_source) != 0
                ? "failed to read /dev/urandom"
                : "unexpected end of /dev/urandom";
            std::fclose(random_source);
            return false;
        }
        offset += read;
    }
    if (std::fclose(random_source) != 0) {
        error = "failed to close /dev/urandom after reading a handoff handle";
        return false;
    }

    static constexpr char kHexDigits[] = "0123456789abcdef";
    handle.resize(kSha256HexCharacters);
    for (size_t index = 0U; index < random_bytes.size(); ++index) {
        handle[index * 2U] = kHexDigits[random_bytes[index] >> 4U];
        handle[index * 2U + 1U] = kHexDigits[random_bytes[index] & 0x0fU];
    }
    return true;
}

void purge_expired(const Clock::time_point now) {
    for (auto iterator = registry.begin(); iterator != registry.end();) {
        if (now >= iterator->second.expires_at) {
            iterator = registry.erase(iterator);
        } else {
            ++iterator;
        }
    }
}

bool records_match(const Record& issued, const Record& observed, std::string& error) {
    if (issued.tokenizer_canonical_path != observed.tokenizer_canonical_path) {
        error = "handoff tokenizer canonical path does not match";
        return false;
    }
    if (issued.tokenizer_device != observed.tokenizer_device) {
        error = "handoff tokenizer device identity does not match";
        return false;
    }
    if (issued.tokenizer_inode != observed.tokenizer_inode) {
        error = "handoff tokenizer inode identity does not match";
        return false;
    }
    if (issued.tokenizer_size_bytes != observed.tokenizer_size_bytes) {
        error = "handoff tokenizer size does not match";
        return false;
    }
    if (issued.tokenizer_sha256 != observed.tokenizer_sha256) {
        error = "handoff tokenizer SHA-256 does not match";
        return false;
    }
    if (issued.prompt_pair_sha256 != observed.prompt_pair_sha256) {
        error = "handoff prompt-pair SHA-256 does not match";
        return false;
    }
    if (issued.payload_sha256 != observed.payload_sha256) {
        error = "handoff payload SHA-256 does not match";
        return false;
    }
    if (issued.prompt_to_encoder_closure_sha256 !=
        observed.prompt_to_encoder_closure_sha256) {
        error = "handoff prompt-to-encoder closure SHA-256 does not match";
        return false;
    }
    return true;
}

}  // namespace

bool issue(const Record& record, std::string& handle, std::string& error) {
    handle.clear();
    error.clear();
    if (!validate_record(record, error)) return false;

    std::string generated_handle;
    if (!read_random_handle(generated_handle, error)) return false;

    const Clock::time_point now = Clock::now();
    std::lock_guard<std::mutex> lock(registry_mutex);
    purge_expired(now);
    if (registry.size() >= kRegistryCapacity) {
        error = "prompt handoff registry is full";
        return false;
    }

    const auto inserted = registry.emplace(
        generated_handle,
        Entry{record, now + kRecordLifetime});
    if (!inserted.second) {
        error = "random prompt handoff handle collided with an existing handle";
        return false;
    }
    handle = std::move(generated_handle);
    return true;
}

bool consume(
    const std::string& handle,
    const Record& observed_record,
    Record& consumed_record,
    std::string& error) {
    consumed_record = Record{};
    error.clear();

    const bool handle_is_valid = is_lowercase_sha256(handle);
    const Clock::time_point now = Clock::now();
    Entry entry{};
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (handle_is_valid) {
            const auto iterator = registry.find(handle);
            if (iterator != registry.end()) {
                entry = std::move(iterator->second);
                registry.erase(iterator);
                found = true;
            }
        }
        purge_expired(now);
    }

    if (!handle_is_valid) {
        error = "prompt handoff handle must be 64 lowercase hexadecimal characters";
        return false;
    }
    if (!found) {
        error = "prompt handoff handle is missing, expired, or already consumed";
        return false;
    }
    if (now >= entry.expires_at) {
        error = "prompt handoff handle has expired";
        return false;
    }
    if (!validate_record(observed_record, error)) return false;
    if (!records_match(entry.record, observed_record, error)) return false;

    consumed_record = std::move(entry.record);
    return true;
}

}  // namespace mca::image::prompt_handoff
