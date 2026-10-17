#include "mca/mnn/mnn_qnn_prompt_payload.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_prompt_weighting.hpp"
#include "mca/mnn/mnn_clip_weighting.hpp"
#include "execution_asset_binding.hpp"
#include "image_conditioning.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#if defined(_WIN32)
#include <io.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#else
#include <unistd.h>
#endif
#include <array>
#include <fstream>
#include <limits>
#include <cstring>

namespace mca {
namespace mnn {

void write_u32_little_endian(std::ostream& output, uint32_t value) {
    const std::array<uint8_t, 4> bytes = {
        static_cast<uint8_t>(value & 0xffU),
        static_cast<uint8_t>((value >> 8U) & 0xffU),
        static_cast<uint8_t>((value >> 16U) & 0xffU),
        static_cast<uint8_t>((value >> 24U) & 0xffU),
    };
    output.write(
            reinterpret_cast<const char*>(bytes.data()),
            static_cast<std::streamsize>(bytes.size()));
}

bool hash_opened_qnn_tokenizer_json_asset(
        MnnOpenedTokenizerJsonAsset& asset,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* phase,
        std::string& error) {
    struct stat descriptorBefore {};
    struct stat pathBefore {};
    if (asset.fd < 0 || asset.canonical_path.empty() ||
        ::fstat(asset.fd, &descriptorBefore) != 0 ||
        ::lstat(asset.canonical_path.c_str(), &pathBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode) || !S_ISREG(pathBefore.st_mode) ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(descriptorBefore)) ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(pathBefore))) {
        error = std::string("QNN tokenizer JSON identity changed ") + phase + ".";
        return false;
    }
    if (expectedSizeBytes == 0U || asset.identity.size != expectedSizeBytes ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string("QNN tokenizer JSON size or seek position is invalid ") + phase + ".";
        return false;
    }

    mca::image::execution_assets::Sha256 digest;
    std::array<uint8_t, 64U * 1024U> bytes{};
    uint64_t readBytes = 0U;
    const auto cancelled = mnn_asset_cancel_callback();
    while (true) {
        if (cancelled()) {
            error = "cancelled";
            return false;
        }
        const ssize_t count = ::read(asset.fd, bytes.data(), bytes.size());
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            error = std::string("QNN tokenizer JSON could not be streamed ") + phase + ".";
            return false;
        }
        readBytes += static_cast<uint64_t>(count);
        if (readBytes > expectedSizeBytes) {
            error = std::string("QNN tokenizer JSON grew ") + phase + ".";
            return false;
        }
        digest.update(bytes.data(), static_cast<size_t>(count));
    }

    struct stat descriptorAfter {};
    struct stat pathAfter {};
    if (::fstat(asset.fd, &descriptorAfter) != 0 ||
        ::lstat(asset.canonical_path.c_str(), &pathAfter) != 0 ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(descriptorAfter)) ||
        !mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(pathAfter)) ||
        readBytes != expectedSizeBytes) {
        error = std::string("QNN tokenizer JSON changed ") + phase + ".";
        return false;
    }

    const std::string actualSha256 = digest.finish_hex();
    if ((!expectedSha256.empty() && actualSha256 != expectedSha256) ||
        !mca::image::execution_assets::lowercase_sha256(actualSha256) ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string("QNN tokenizer JSON bytes do not match the consumed snapshot ") +
                phase + ".";
        return false;
    }
    asset.sha256 = actualSha256;
    asset.size_bytes = readBytes;
    return true;
}

bool open_qnn_tokenizer_json_asset(
        const std::string& requestedPath,
        MnnOpenedTokenizerJsonAsset& asset,
        std::string& error) {
    asset.reset();
    char canonical[PATH_MAX] = {};
    if (requestedPath.empty() || realpath(requestedPath.c_str(), canonical) == nullptr ||
        requestedPath != canonical) {
        error = "QNN tokenizer JSON path must be a canonical absolute path.";
        return false;
    }

    struct stat pathBefore {};
    if (::lstat(canonical, &pathBefore) != 0 || !S_ISREG(pathBefore.st_mode)) {
        error = "QNN tokenizer JSON must be a regular non-symlink file.";
        return false;
    }

    asset.fd = ::open(canonical, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    struct stat descriptorBefore {};
    if (asset.fd < 0 || ::fstat(asset.fd, &descriptorBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode)) {
        asset.reset();
        error = "QNN tokenizer JSON could not be opened through a regular-file descriptor.";
        return false;
    }

    asset.canonical_path = canonical;
    asset.identity = mca::image::execution_assets::identity_of(descriptorBefore);
    if (!mca::image::execution_assets::same_identity(
                asset.identity,
                mca::image::execution_assets::identity_of(pathBefore)) ||
        asset.identity.size == 0U ||
        !hash_opened_qnn_tokenizer_json_asset(
                asset,
                "",
                asset.identity.size,
                "before native tokenizer consumption",
                error)) {
        asset.reset();
        return false;
    }
    return true;
}

nlohmann::json qnn_tokenizer_asset_failure(const std::string& error) {
    if (error == "cancelled") {
        return nlohmann::json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}});
    }
    return nlohmann::json({
        {"ok", false},
        {"errorCode", "TOKENIZER_ASSET_MISMATCH"},
        {"error", error}
    });
}

bool hash_published_qnn_prompt_payload(
        const std::string& requestedPath,
        std::string& sha256,
        std::string& error) {
    char canonical[PATH_MAX] = {};
    struct stat pathBefore {};
    if (requestedPath.empty() ||
        ::realpath(requestedPath.c_str(), canonical) == nullptr ||
        requestedPath != canonical ||
        ::lstat(canonical, &pathBefore) != 0 ||
        !S_ISREG(pathBefore.st_mode) || pathBefore.st_size <= 0) {
        error = "Published QNN prompt payload is not a canonical nonempty regular file.";
        return false;
    }
    const int fd = ::open(canonical, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        error = "Published QNN prompt payload cannot be opened through a pinned descriptor.";
        return false;
    }
    const auto closeDescriptor = [&]() { ::close(fd); };
    struct stat descriptorBefore {};
    if (::fstat(fd, &descriptorBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode) ||
        !mca::image::execution_assets::same_identity(
            mca::image::execution_assets::identity_of(pathBefore),
            mca::image::execution_assets::identity_of(descriptorBefore))) {
        closeDescriptor();
        error = "Published QNN prompt payload identity changed before hashing.";
        return false;
    }

    mca::image::execution_assets::Sha256 digest;
    std::array<uint8_t, 64U * 1024U> bytes{};
    uint64_t readBytes = 0U;
    const auto cancelled = mnn_asset_cancel_callback();
    while (true) {
        if (cancelled()) {
            closeDescriptor();
            error = "cancelled";
            return false;
        }
        const ssize_t count = ::read(fd, bytes.data(), bytes.size());
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            closeDescriptor();
            error = "Published QNN prompt payload could not be streamed for hashing.";
            return false;
        }
        readBytes += static_cast<uint64_t>(count);
        if (readBytes > static_cast<uint64_t>(descriptorBefore.st_size)) {
            closeDescriptor();
            error = "Published QNN prompt payload grew while being hashed.";
            return false;
        }
        digest.update(bytes.data(), static_cast<size_t>(count));
    }

    struct stat descriptorAfter {};
    struct stat pathAfter {};
    const bool stable = ::fstat(fd, &descriptorAfter) == 0 &&
        ::lstat(canonical, &pathAfter) == 0 &&
        mca::image::execution_assets::same_identity(
            mca::image::execution_assets::identity_of(descriptorBefore),
            mca::image::execution_assets::identity_of(descriptorAfter)) &&
        mca::image::execution_assets::same_identity(
            mca::image::execution_assets::identity_of(descriptorBefore),
            mca::image::execution_assets::identity_of(pathAfter)) &&
        readBytes == static_cast<uint64_t>(descriptorBefore.st_size);
    closeDescriptor();
    if (!stable) {
        error = "Published QNN prompt payload changed while being hashed.";
        return false;
    }
    sha256 = digest.finish_hex();
    if (!mca::image::execution_assets::lowercase_sha256(sha256)) {
        error = "Published QNN prompt payload SHA-256 is invalid.";
        return false;
    }
    return true;
}

#if defined(MCA_WITH_TOKENIZERS_CPP) && MCA_WITH_TOKENIZERS_CPP
nlohmann::json encode_prompt_token_ids_with_weights_from_json(
        const std::string& tokenizerJsonPath,
        const std::string& prompt,
        const std::string& negativePrompt,
        int32_t bosId,
        int32_t eosId,
        int32_t padId,
        int maxTokens,
        const std::string& promptToEncoderClosureSha256,
        const std::string& outputPath) {
    if (outputPath.empty()) {
        return nlohmann::json({{"ok", false}, {"error", "Weighted CLIP token payload arguments are invalid."}});
    }
    if (!promptToEncoderClosureSha256.empty() &&
        !mca::image::execution_assets::lowercase_sha256(
            promptToEncoderClosureSha256)) {
        return nlohmann::json({
            {"ok", false},
            {"error", "Prompt-to-encoder closure SHA-256 is invalid."}
        });
    }
    const std::string temporaryPath = outputPath + ".part";
    if (tokenizerJsonPath == outputPath || tokenizerJsonPath == temporaryPath) {
        return nlohmann::json({
            {"ok", false},
            {"error", "Weighted CLIP token payload output must not alias tokenizer.json."}
        });
    }
    const auto discardOutput = [&]() {
        ::unlink(temporaryPath.c_str());
        ::unlink(outputPath.c_str());
    };
    if (maxTokens <= 0 || maxTokens > 4096) {
        discardOutput();
        return nlohmann::json({{"ok", false}, {"error", "Weighted CLIP token payload arguments are invalid."}});
    }
    if (!mca::image::prompt_language::is_supported_chinese_han_diffusion_prompt_pair(
            prompt,
            negativePrompt)) {
        discardOutput();
        return nlohmann::json({
            {"ok", false},
            {"errorCode", "UNSUPPORTED_NATIVE_PROMPT_LANGUAGE"},
            {"error", "Image prompt syntax must use Chinese Han text, supported CJK punctuation, and safe ASCII diffusion prompt syntax."}
        });
    }

    MnnOpenedTokenizerJsonAsset tokenizerAsset;
    std::string error;
    if (!open_qnn_tokenizer_json_asset(tokenizerJsonPath, tokenizerAsset, error)) {
        discardOutput();
        return qnn_tokenizer_asset_failure(error);
    }
    char outputCanonical[PATH_MAX] = {};
    char temporaryCanonical[PATH_MAX] = {};
    if ((realpath(outputPath.c_str(), outputCanonical) != nullptr &&
            tokenizerAsset.canonical_path == outputCanonical) ||
        (realpath(temporaryPath.c_str(), temporaryCanonical) != nullptr &&
            tokenizerAsset.canonical_path == temporaryCanonical)) {
        return nlohmann::json({
            {"ok", false},
            {"error", "Weighted CLIP token payload output must not alias tokenizer.json."}
        });
    }
    discardOutput();

    mca::image::ClipTokenizerConfig config;
    config.bos_id = bosId;
    config.eos_id = eosId;
    config.pad_id = padId;
    config.max_length = maxTokens;
    config.enable_prompt_weighting = true;
    mca::image::ClipTokenPair pair;
    const bool tokenized = mca::image::tokenize_clip_pair_from_json(
            tokenizerAsset.opened_path(),
            prompt,
            negativePrompt,
            config,
            &pair,
            &error);
    const std::string tokenizationError = error;
    const std::string tokenizerSha256 = tokenizerAsset.sha256;
    const uint64_t tokenizerSizeBytes = tokenizerAsset.size_bytes;
    if (!hash_opened_qnn_tokenizer_json_asset(
            tokenizerAsset,
            tokenizerSha256,
            tokenizerSizeBytes,
            "after native tokenizer consumption",
            error)) {
        discardOutput();
        return qnn_tokenizer_asset_failure(error);
    }
    if (!tokenized) {
        discardOutput();
        return nlohmann::json({{"ok", false}, {"error", tokenizationError}});
    }
    const auto cancelled = mnn_asset_cancel_callback();
    if (cancelled()) {
        discardOutput();
        return qnn_tokenizer_asset_failure("cancelled");
    }
    const auto ids = pair.negative_then_positive();
    const auto weights = pair.negative_then_positive_weights();
    const size_t expectedCount = static_cast<size_t>(maxTokens) * 2U;
    const std::string fingerprint = pair.weighting_fingerprint();
    if (ids.size() != expectedCount || weights.size() != expectedCount ||
            fingerprint.size() != 64U ||
            expectedCount > static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
        discardOutput();
        return nlohmann::json({
            {"ok", false},
            {"error", "Weighted CLIP token payload is internally inconsistent."}
        });
    }
    const std::string nativePromptExecutionSha256 =
            mca::image::image_prompt_execution_sha256(prompt, negativePrompt);
    if (nativePromptExecutionSha256.size() != 64U) {
        discardOutput();
        return nlohmann::json({
            {"ok", false},
            {"error", "Weighted CLIP token payload prompt SHA-256 could not be derived."}
        });
    }

    nlohmann::json result = {
        {"ok", true},
        {"conditioningFormat", "qnn_clip_token_ids_weights_v1"},
        {"tokenizerBackend", "tokenizers_cpp"},
        {"tokenCount", ids.size()},
        {"nativePromptExecutionSha256", nativePromptExecutionSha256},
        {"nativePromptBindingStage", "conditioning_encoded"},
        {"outputPath", outputPath},
        {"tokenizerAssetPath", tokenizerAsset.canonical_path},
        {"tokenizerAssetSha256", tokenizerAsset.sha256},
        {"tokenizerAssetSizeBytes", tokenizerAsset.size_bytes},
        {"tokenizerAssetBindingStage", "tokenizer_consumed"}
    };
    if (!append_clip_prompt_weighting_evidence(result, pair, true, true, error)) {
        discardOutput();
        return nlohmann::json({{"ok", false}, {"error", error}});
    }
    if (cancelled()) {
        discardOutput();
        return qnn_tokenizer_asset_failure("cancelled");
    }

    std::ofstream output(temporaryPath.c_str(), std::ios::binary | std::ios::trunc);
    if (!output.good()) {
        output.close();
        discardOutput();
        return nlohmann::json({{"ok", false}, {"error", "Failed to open weighted CLIP token payload."}});
    }
    static constexpr std::array<char, 8> kMagic = {
        'M', 'C', 'A', 'Q', 'P', 'W', '0', '1'
    };
    output.write(kMagic.data(), static_cast<std::streamsize>(kMagic.size()));
    write_u32_little_endian(output, 1U);
    write_u32_little_endian(output, static_cast<uint32_t>(expectedCount));
    for (const int32_t id : ids) {
        write_u32_little_endian(output, static_cast<uint32_t>(id));
    }
    for (const float weight : weights) {
        uint32_t bits = 0;
        static_assert(sizeof(bits) == sizeof(weight), "float32 payload requires 32-bit float");
        std::memcpy(&bits, &weight, sizeof(bits));
        write_u32_little_endian(output, bits);
    }
    output.flush();
    const bool writeOk = output.good();
    output.close();
    if (!writeOk || !output.good() || cancelled() ||
            ::rename(temporaryPath.c_str(), outputPath.c_str()) != 0 ||
            !nonempty_regular_file_exists(outputPath)) {
        discardOutput();
        if (cancelled()) return qnn_tokenizer_asset_failure("cancelled");
        return nlohmann::json({{"ok", false}, {"error", "Failed to publish weighted CLIP token payload."}});
    }
    if (cancelled()) {
        discardOutput();
        return qnn_tokenizer_asset_failure("cancelled");
    }
    if (!promptToEncoderClosureSha256.empty()) {
        std::string payloadSha256;
        if (!hash_published_qnn_prompt_payload(outputPath, payloadSha256, error)) {
            discardOutput();
            return qnn_tokenizer_asset_failure(error);
        }
        mca::image::prompt_handoff::Record handoffRecord;
        handoffRecord.tokenizer_canonical_path = tokenizerAsset.canonical_path;
        handoffRecord.tokenizer_device = tokenizerAsset.identity.device;
        handoffRecord.tokenizer_inode = tokenizerAsset.identity.inode;
        handoffRecord.tokenizer_size_bytes = tokenizerAsset.size_bytes;
        handoffRecord.tokenizer_sha256 = tokenizerAsset.sha256;
        handoffRecord.prompt_pair_sha256 = nativePromptExecutionSha256;
        handoffRecord.payload_sha256 = payloadSha256;
        handoffRecord.prompt_to_encoder_closure_sha256 =
            promptToEncoderClosureSha256;
        if (cancelled()) {
            discardOutput();
            return qnn_tokenizer_asset_failure("cancelled");
        }
        std::string handoff;
        if (!mca::image::prompt_handoff::issue(handoffRecord, handoff, error)) {
            discardOutput();
            return nlohmann::json({
                {"ok", false},
                {"errorCode", "PROMPT_HANDOFF_ISSUE_FAILED"},
                {"error", error.empty()
                    ? "Failed to issue the native MNN-to-QNN prompt handoff."
                    : error}
            });
        }
        result["mnnPromptHandoff"] = handoff;
    }
    return result;
}
#endif

}  // namespace mnn
}  // namespace mca
