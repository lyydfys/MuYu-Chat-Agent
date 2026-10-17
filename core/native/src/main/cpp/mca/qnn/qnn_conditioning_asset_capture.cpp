// mca/qnn/qnn_conditioning_asset_capture.cpp -- TOCTOU asset capture/revalidation (file identity + sha256).
// (T2B16 / R7-C5 split).  Relocated byte-exact from mca/qnn/qnn_conditioning_pipeline.cpp (lines 662-924).

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

bool qnn_text_encoder_asset_file_status(
        const std::string& bundle_asset_path,
        struct stat* status,
        std::string* error) {
    if (status == nullptr || error == nullptr) return false;
    struct stat file_status {};
    // A strict bundle-relative proof must not resolve through a symlink.
    if (::lstat(bundle_asset_path.c_str(), &file_status) != 0 ||
        !S_ISREG(file_status.st_mode) || file_status.st_size <= 0 ||
        static_cast<uint64_t>(file_status.st_size) >
            static_cast<uint64_t>(std::numeric_limits<long long>::max())) {
        *error = "The strict QNN text encoder asset is not a readable nonempty regular file.";
        return false;
    }
    *status = file_status;
    error->clear();
    return true;
}

bool qnn_text_encoder_asset_status_matches(
        const struct stat& status,
        const QnnTextEncoderAssetEvidence& evidence) {
    return S_ISREG(status.st_mode) && status.st_size > 0 &&
        static_cast<uint64_t>(status.st_size) ==
            static_cast<uint64_t>(evidence.size_bytes) &&
        status.st_dev == evidence.device && status.st_ino == evidence.inode;
}

bool capture_qnn_text_encoder_asset_evidence(
        const std::string& bundle_asset_path,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr || !evidence->required) return false;
    struct stat before_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &before_hash, error)) {
        return false;
    }
    if (static_cast<long long>(before_hash.st_size) != evidence->size_bytes) {
        *error = "The strict QNN text encoder asset size differs from textEncoderSizeBytes.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(bundle_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN text encoder asset SHA-256 could not be verified.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &after_hash, error)) {
        return false;
    }
    if (before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size) {
        *error = "The strict QNN text encoder asset changed while its SHA-256 was verified.";
        return false;
    }
    if (actual_sha256 != evidence->sha256) {
        *error = "The strict QNN text encoder asset SHA-256 differs from textEncoderSha256.";
        return false;
    }
    evidence->device = after_hash.st_dev;
    evidence->inode = after_hash.st_ino;
    error->clear();
    return true;
}

bool revalidate_qnn_text_encoder_asset_evidence(
        const std::string& bundle_asset_path,
        const QnnTextEncoderAssetEvidence& evidence,
        std::string* error) {
    if (error == nullptr || !evidence.required) return false;
    struct stat before_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &before_hash, error)) {
        return false;
    }
    if (!qnn_text_encoder_asset_status_matches(before_hash, evidence)) {
        *error = "The strict QNN text encoder asset inode or size changed after graph execution.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(bundle_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN text encoder asset could not be re-verified after graph execution.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_text_encoder_asset_file_status(bundle_asset_path, &after_hash, error)) {
        return false;
    }
    if (!qnn_text_encoder_asset_status_matches(after_hash, evidence) ||
        before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size || actual_sha256 != evidence.sha256) {
        *error = "The strict QNN text encoder asset changed after graph execution.";
        return false;
    }
    error->clear();
    return true;
}

bool qnn_tokenizer_asset_file_status(
        const std::string& bundle_asset_path,
        struct stat* status,
        std::string* error) {
    if (status == nullptr || error == nullptr) return false;
    struct stat file_status {};
    if (::lstat(bundle_asset_path.c_str(), &file_status) != 0 ||
        !S_ISREG(file_status.st_mode) || file_status.st_size <= 0 ||
        static_cast<uint64_t>(file_status.st_size) >
            static_cast<uint64_t>(std::numeric_limits<long long>::max())) {
        *error = "The strict QNN tokenizer asset is not a readable nonempty regular file.";
        return false;
    }
    *status = file_status;
    error->clear();
    return true;
}

bool qnn_tokenizer_asset_status_matches(
        const struct stat& status,
        const QnnTextEncoderAssetEvidence& evidence) {
    return S_ISREG(status.st_mode) && status.st_size > 0 &&
        static_cast<uint64_t>(status.st_size) ==
            static_cast<uint64_t>(evidence.tokenizer_size_bytes) &&
        status.st_dev == evidence.tokenizer_device &&
        status.st_ino == evidence.tokenizer_inode;
}

bool qnn_canonical_bundle_tokenizer_path(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        std::string* canonical_asset_path,
        std::string* error) {
    if (canonical_asset_path == nullptr || error == nullptr) return false;
    if (bundle_root.empty() || bundle_asset_path.empty() ||
        bundle_root.find('\0') != std::string::npos ||
        bundle_asset_path.find('\0') != std::string::npos) {
        *error = "The strict QNN tokenizer asset or bundle root is invalid.";
        return false;
    }
    char canonical_root[PATH_MAX] = {};
    char canonical_asset[PATH_MAX] = {};
    if (::realpath(bundle_root.c_str(), canonical_root) == nullptr ||
        ::realpath(bundle_asset_path.c_str(), canonical_asset) == nullptr) {
        *error = "The strict QNN tokenizer asset or bundle root cannot be canonicalized.";
        return false;
    }
    const std::string canonical_root_path(canonical_root);
    const std::string resolved_asset_path(canonical_asset);
    const std::string root_prefix = canonical_root_path == "/"
        ? canonical_root_path
        : canonical_root_path + "/";
    if (resolved_asset_path == canonical_root_path ||
        resolved_asset_path.compare(0, root_prefix.size(), root_prefix) != 0) {
        *error = "The strict QNN tokenizer asset resolves outside the installed bundle.";
        return false;
    }
    *canonical_asset_path = resolved_asset_path;
    error->clear();
    return true;
}

bool capture_qnn_tokenizer_asset_evidence(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        QnnTextEncoderAssetEvidence* evidence,
        std::string* error) {
    if (evidence == nullptr || error == nullptr ||
        !evidence->multilingual_evidence_required) {
        return false;
    }
    std::string canonical_asset_path;
    if (!qnn_canonical_bundle_tokenizer_path(
            bundle_root,
            bundle_asset_path,
            &canonical_asset_path,
            error)) {
        return false;
    }
    struct stat before_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &before_hash, error)) {
        return false;
    }
    if (static_cast<long long>(before_hash.st_size) != evidence->tokenizer_size_bytes) {
        *error = "The strict QNN tokenizer asset size differs from tokenizerJsonSizeBytes.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(canonical_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN tokenizer asset SHA-256 could not be verified.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &after_hash, error)) {
        return false;
    }
    if (before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size) {
        *error = "The strict QNN tokenizer asset changed while its SHA-256 was verified.";
        return false;
    }
    if (actual_sha256 != evidence->tokenizer_sha256) {
        *error = "The strict QNN tokenizer asset SHA-256 differs from tokenizerJsonSha256.";
        return false;
    }
    evidence->tokenizer_device = after_hash.st_dev;
    evidence->tokenizer_inode = after_hash.st_ino;
    evidence->tokenizer_canonical_path = std::move(canonical_asset_path);
    evidence->tokenizer_asset_verified = true;
    error->clear();
    return true;
}

bool revalidate_qnn_tokenizer_asset_evidence(
        const std::string& bundle_root,
        const std::string& bundle_asset_path,
        const QnnTextEncoderAssetEvidence& evidence,
        std::string* error) {
    if (error == nullptr || !evidence.multilingual_evidence_required) return false;
    std::string canonical_asset_path;
    if (!evidence.tokenizer_asset_verified) {
        *error = "The strict QNN tokenizer asset has no captured identity.";
        return false;
    }
    if (!qnn_canonical_bundle_tokenizer_path(
            bundle_root,
            bundle_asset_path,
            &canonical_asset_path,
            error)) {
        return false;
    }
    if (canonical_asset_path != evidence.tokenizer_canonical_path) {
        *error = "The strict QNN tokenizer asset canonical path changed after capture.";
        return false;
    }
    struct stat before_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &before_hash, error)) {
        return false;
    }
    if (!qnn_tokenizer_asset_status_matches(before_hash, evidence)) {
        *error = "The strict QNN tokenizer asset inode or size changed after capture.";
        return false;
    }
    std::string actual_sha256;
    std::string hash_error;
    if (!qnn_file_sha256(canonical_asset_path, &actual_sha256, &hash_error)) {
        *error = "The strict QNN tokenizer asset could not be re-verified after capture.";
        return false;
    }
    struct stat after_hash {};
    if (!qnn_tokenizer_asset_file_status(canonical_asset_path, &after_hash, error)) {
        return false;
    }
    if (!qnn_tokenizer_asset_status_matches(after_hash, evidence) ||
        before_hash.st_dev != after_hash.st_dev || before_hash.st_ino != after_hash.st_ino ||
        before_hash.st_size != after_hash.st_size || actual_sha256 != evidence.tokenizer_sha256) {
        *error = "The strict QNN tokenizer asset changed after capture.";
        return false;
    }
    error->clear();
    return true;
}



}  // namespace qnn
}  // namespace mca
