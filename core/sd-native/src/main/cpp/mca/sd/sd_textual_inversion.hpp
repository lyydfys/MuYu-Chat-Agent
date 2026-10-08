#ifndef MCA_SD_TEXTUAL_INVERSION_HPP
#define MCA_SD_TEXTUAL_INVERSION_HPP

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>
#include <sys/stat.h>

namespace mca {
namespace sd {

/**
 * Textual Inversion artifact contract matching worker-verified runtime inputs.
 */
struct ContractTextualInversion {
    std::string id;
    std::string name;
    std::string trigger;
    std::string path;
    std::string sha256;
    std::string format;
    std::string model_fingerprint;
    std::string tokenizer_fingerprint;
    std::string profile_id;
    std::string runtime;
    std::string native_mode;
    std::string binding_fingerprint;
    int profile_revision = 0;
    uint64_t size_bytes = 0u;
};

/**
 * Cached regular file identity metadata for anti-tamper / change detection.
 */
struct RegularFileIdentity {
    uint64_t device = 0;
    uint64_t inode = 0;
    uint64_t size = 0;
    int64_t modification_time = 0;
    int64_t status_change_time = 0;
};

/**
 * Extracts RegularFileIdentity from a stat structure.
 */
RegularFileIdentity regular_file_identity(const struct stat &value);

/**
 * Compares two RegularFileIdentity structs for exact match.
 */
bool same_regular_file_identity(const RegularFileIdentity &left,
                                const RegularFileIdentity &right);

/**
 * Reads and verifies the identity of an existing regular non-symlink file.
 */
bool read_regular_file_identity(const std::string &path,
                                uint64_t expected_size,
                                RegularFileIdentity &identity,
                                std::string &error);

/**
 * Incremental SHA-256 accumulator.
 */
class Sha256Accumulator {
public:
    Sha256Accumulator();
    void update(const uint8_t *bytes, size_t size);
    std::string finish_hex();

private:
    void transform(const uint8_t *block);

    std::array<uint32_t, 8> state_;
    std::array<uint8_t, 64> buffer_;
    size_t buffer_size_ = 0u;
    uint64_t total_bytes_ = 0u;
};

/**
 * Computes SHA-256 hex digest for in-memory bytes.
 */
std::string sha256_hex(const std::vector<uint8_t> &bytes);

/**
 * Computes SHA-256 hex digest for a UTF-8 string.
 */
std::string sha256_utf8(const std::string &value);

/**
 * Formats a 64-bit unsigned integer as a 16-character lowercase hexadecimal string.
 */
std::string fixed_width_lower_hex_u64(uint64_t value);

/**
 * Computes canonical single-artifact binding fingerprint for Textual Inversion.
 */
std::string textual_inversion_binding_fingerprint(
        const ContractTextualInversion &artifact);

/**
 * Computes selection fingerprint across an ordered collection of Textual Inversions.
 */
std::string textual_inversion_selection_fingerprint(
        const std::vector<ContractTextualInversion> &artifacts);

/**
 * Computes deterministic SHA-256 digest for prompt + negative prompt combination.
 */
std::string image_prompt_execution_sha256(const std::string &prompt,
                                          const std::string &negative_prompt);

/**
 * Self-test asserting internal SHA-256 accumulator correctness against known vectors.
 */
bool sha256_implementation_ready();

/**
 * Streams and hashes a regular file using SHA-256 with TOCTOU anti-tamper verification.
 */
bool sha256_regular_file(const std::string &path,
                         uint64_t expected_size,
                         std::string &digest,
                         RegularFileIdentity &identity,
                         std::string &error,
                         const std::function<bool()> &is_cancelled = nullptr);

/**
 * Validates existence, path containment, format, size, and SHA-256 of a Textual Inversion file.
 */
bool validate_textual_inversion_file(const std::string &raw_root,
                                     const ContractTextualInversion &artifact,
                                     std::string &canonical_path,
                                     std::string &actual_sha256,
                                     std::string &error,
                                     const std::function<bool()> &is_cancelled = nullptr);

} // namespace sd
} // namespace mca

#endif // MCA_SD_TEXTUAL_INVERSION_HPP
