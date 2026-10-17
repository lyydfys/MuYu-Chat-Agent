#include "mca/mnn/mnn_diffusion_assets.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_diffusion_asset_contract.hpp"
#include "mca/mnn/mnn_tensor_copy.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <fcntl.h>
#include <functional>
#include <memory>
#include <mutex>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#endif

namespace mca {
namespace mnn {

namespace {

std::mutex& mnn_asset_cancel_mutex() {
    static std::mutex mutex;
    return mutex;
}

std::function<bool()>& mnn_asset_cancel_probe_storage() {
    static std::function<bool()> probe;
    return probe;
}

}  // namespace

std::function<bool()> mnn_asset_cancel_callback() {
    std::lock_guard<std::mutex> lock(mnn_asset_cancel_mutex());
    auto& probe = mnn_asset_cancel_probe_storage();
    if (!probe) {
        return [] { return false; };
    }
    return probe;
}

std::function<bool()> mnn_asset_cancel_probe() {
    std::lock_guard<std::mutex> lock(mnn_asset_cancel_mutex());
    return mnn_asset_cancel_probe_storage();
}

void set_mnn_asset_cancel_probe(std::function<bool()> probe) {
    std::lock_guard<std::mutex> lock(mnn_asset_cancel_mutex());
    mnn_asset_cancel_probe_storage() = std::move(probe);
}

MnnOpenedTextEncoderAsset::~MnnOpenedTextEncoderAsset() {
    reset();
}

void MnnOpenedTextEncoderAsset::reset() {
    if (fd >= 0) {
        ::close(fd);
        fd = -1;
    }
    canonical_path.clear();
    sha256.clear();
    size_bytes = 0U;
    identity = {};
}

std::string MnnOpenedTextEncoderAsset::opened_path() const {
    return fd >= 0 ? "/proc/self/fd/" + std::to_string(fd) : std::string();
}


bool prepare_mnn_execution_assets(
        const std::string& textualInversionJson,
        const std::string& runtime,
        mca::image::execution_assets::Binding& binding,
        std::string& error) {
    const auto parsed = nlohmann::json::parse(textualInversionJson, nullptr, false);
    if (!parsed.is_object() ||
        !mca::image::execution_assets::parse(parsed, runtime, binding, error) ||
        !mca::image::execution_assets::verify_initial(
                binding, mnn_asset_cancel_callback(), error)) {
        return false;
    }
    return true;
}

bool finalize_mnn_execution_assets(
        mca::image::execution_assets::Binding& binding,
        std::string& error) {
    return mca::image::execution_assets::verify_final(
            binding, mnn_asset_cancel_callback(), error);
}

bool mnn_selection_matches_execution_assets(
        const mca::image::textual_inversion::Selection& selection,
        const mca::image::execution_assets::Binding& binding,
        std::string& error) {
    const bool matches = std::all_of(
        selection.artifacts.begin(),
        selection.artifacts.end(),
        [&binding](const auto& artifact) {
            return artifact.tokenizer_fingerprint == binding.composite_sha256;
        });
    if (!matches) {
        error = "textual inversion artifact binding does not match the verified consumer assets";
    }
    return matches;
}


bool mnn_text_encoder_path_is_inside_bundle(
        const std::string& bundleRoot,
        const std::string& path) {
    if (bundleRoot == "/") return path.size() > 1U && path.front() == '/';
    return path.size() > bundleRoot.size() &&
            path.compare(0, bundleRoot.size(), bundleRoot) == 0 &&
            path[bundleRoot.size()] == '/';
}

bool hash_opened_mnn_text_encoder_asset(
        MnnOpenedTextEncoderAsset& asset,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* label,
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
        error = std::string(label) + " identity changed before native MNN consumption.";
        return false;
    }
    if (asset.identity.size != expectedSizeBytes ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string(label) + " size or seek position is invalid.";
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
            error = std::string(label) + " could not be streamed for SHA-256 verification.";
            return false;
        }
        readBytes += static_cast<uint64_t>(count);
        if (readBytes > expectedSizeBytes) {
            error = std::string(label) + " grew while native MNN verification was in progress.";
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
        error = std::string(label) + " changed while native MNN verification was in progress.";
        return false;
    }
    const std::string actualSha256 = digest.finish_hex();
    if (actualSha256 != expectedSha256 ||
        ::lseek(asset.fd, 0, SEEK_SET) == static_cast<off_t>(-1)) {
        error = std::string(label) + " bytes do not match the profile-declared SHA-256.";
        return false;
    }
    asset.sha256 = actualSha256;
    asset.size_bytes = readBytes;
    return true;
}

bool open_mnn_text_encoder_asset(
        const std::string& canonicalBundleRoot,
        const std::string& requestedPath,
        const std::string& expectedSha256,
        uint64_t expectedSizeBytes,
        const char* label,
        MnnOpenedTextEncoderAsset& asset,
        std::string& error) {
    asset.reset();
    char canonical[PATH_MAX] = {};
    if (requestedPath.empty() || realpath(requestedPath.c_str(), canonical) == nullptr ||
        requestedPath != canonical ||
        !mnn_text_encoder_path_is_inside_bundle(canonicalBundleRoot, canonical)) {
        error = std::string(label) + " path must be canonical and stay inside the selected bundle.";
        return false;
    }
    struct stat pathBefore {};
    if (::lstat(canonical, &pathBefore) != 0 || !S_ISREG(pathBefore.st_mode)) {
        error = std::string(label) + " is not a regular non-symlink file.";
        return false;
    }
    asset.fd = ::open(canonical, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    struct stat descriptorBefore {};
    if (asset.fd < 0 || ::fstat(asset.fd, &descriptorBefore) != 0 ||
        !S_ISREG(descriptorBefore.st_mode)) {
        asset.reset();
        error = std::string(label) + " could not be opened through a regular-file descriptor.";
        return false;
    }
    asset.canonical_path = canonical;
    asset.identity = mca::image::execution_assets::identity_of(descriptorBefore);
    if (!mca::image::execution_assets::same_identity(
            asset.identity,
            mca::image::execution_assets::identity_of(pathBefore)) ||
        asset.identity.size != expectedSizeBytes ||
        !hash_opened_mnn_text_encoder_asset(
            asset, expectedSha256, expectedSizeBytes, label, error)) {
        asset.reset();
        return false;
    }
    return true;
}

bool open_mnn_text_encoder_binding(
        const std::string& root,
        const MnnTextEncoderAssetRequest& request,
        MnnOpenedTextEncoderBinding& binding,
        MnnTextEncoderAssetEvidence& evidence,
        std::string& error) {
    if (!request.declared) return true;
    char canonicalRoot[PATH_MAX] = {};
    struct stat rootStat {};
    if (root.empty() || realpath(root.c_str(), canonicalRoot) == nullptr ||
        ::stat(canonicalRoot, &rootStat) != 0 || !S_ISDIR(rootStat.st_mode)) {
        error = "MNN text encoder evidence has no canonical bundle root.";
        return false;
    }
    if (!open_mnn_text_encoder_asset(
            canonicalRoot,
            request.path,
            request.sha256,
            request.size_bytes,
            "MNN text encoder graph",
            binding.graph,
            error)) {
        return false;
    }
    if (request.has_weight) {
        if (request.weight_path != request.path + ".weight" ||
            !open_mnn_text_encoder_asset(
                canonicalRoot,
                request.weight_path,
                request.weight_sha256,
                request.weight_size_bytes,
                "MNN text encoder weight",
                binding.weight,
                error)) {
            if (error.empty()) {
                error = "The declared MNN text encoder weight must match the graph's .weight path.";
            }
            return false;
        }
    } else {
        struct stat unexpectedWeight {};
        const std::string defaultWeightPath = binding.graph.canonical_path + ".weight";
        if (::lstat(defaultWeightPath.c_str(), &unexpectedWeight) == 0) {
            error = "The declared MNN text encoder has a .weight sidecar that is not pinned by nativeTextEncoderEvidence.";
            return false;
        }
        if (errno != ENOENT) {
            error = "The declared MNN text encoder .weight sidecar could not be inspected."
                " It must be explicitly pinned before native execution.";
            return false;
        }
    }
    binding.active = true;
    evidence.consumed = true;
    evidence.path = binding.graph.canonical_path;
    evidence.sha256 = binding.graph.sha256;
    evidence.size_bytes = binding.graph.size_bytes;
    evidence.expected_sha256 = request.sha256;
    evidence.expected_size_bytes = request.size_bytes;
    if (request.has_weight) {
        evidence.has_weight = true;
        evidence.weight_path = binding.weight.canonical_path;
        evidence.weight_sha256 = binding.weight.sha256;
        evidence.weight_size_bytes = binding.weight.size_bytes;
        evidence.expected_weight_sha256 = request.weight_sha256;
        evidence.expected_weight_size_bytes = request.weight_size_bytes;
    }
    return true;
}

bool verify_mnn_text_encoder_binding(
        const MnnTextEncoderAssetRequest& request,
        MnnOpenedTextEncoderBinding& binding,
        std::string& error) {
    if (!binding.active) return true;
    if (!hash_opened_mnn_text_encoder_asset(
            binding.graph,
            request.sha256,
            request.size_bytes,
            "MNN text encoder graph",
            error)) {
        return false;
    }
    return !request.has_weight || hash_opened_mnn_text_encoder_asset(
            binding.weight,
            request.weight_sha256,
            request.weight_size_bytes,
            "MNN text encoder weight",
            error);
}



#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION

nlohmann::json inspect_mnn_graph(const std::string& path) {
    if (!file_exists(path)) {
        return nlohmann::json({{"ok", false}, {"error", "Model file does not exist."}});
    }
    std::unique_ptr<MNN::Interpreter> interpreter(MNN::Interpreter::createFromFile(path.c_str()));
    if (!interpreter) {
        return nlohmann::json({{"ok", false}, {"error", "Interpreter::createFromFile failed."}});
    }
    const auto weightPath = path + ".weight";
    if (file_exists(weightPath)) {
        interpreter->setExternalFile(weightPath.c_str());
    }
    MNN::ScheduleConfig config;
    MNN::BackendConfig backendConfig;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 1;
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    auto* session = interpreter->createSession(config);
    if (session == nullptr) {
        return nlohmann::json({{"ok", false}, {"error", "Interpreter::createSession failed."}});
    }
    nlohmann::json inputs = nlohmann::json::array();
    for (const auto& entry : interpreter->getSessionInputAll(session)) {
        inputs.push_back(nlohmann::json({
            {"name", entry.first},
            {"tensor", tensor_metadata_json(entry.second)}
        }));
    }
    nlohmann::json outputs = nlohmann::json::array();
    for (const auto& entry : interpreter->getSessionOutputAll(session)) {
        outputs.push_back(nlohmann::json({
            {"name", entry.first},
            {"tensor", tensor_metadata_json(entry.second)}
        }));
    }
    interpreter->releaseSession(session);
    return nlohmann::json({
        {"ok", true},
        {"path", path},
        {"inputs", inputs},
        {"outputs", outputs}
    });
}

nlohmann::json inspect_mnn_diffusion_bundle(const std::string& root) {
    return nlohmann::json({
        {"textEncoder", inspect_mnn_graph(root + "/text_encoder.mnn")},
        {"unet", inspect_mnn_graph(root + "/unet.mnn")},
        {"vaeDecoder", inspect_mnn_graph(root + "/vae_decoder.mnn")}
    });
}
#endif

}  // namespace mnn
}  // namespace mca
