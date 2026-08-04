#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace mca::qnn::preview {

constexpr int kMinimumInterval = 1;
constexpr int kMaximumInterval = 10;
constexpr uint64_t kMaximumPngBytes = 32U * 1024U * 1024U;

struct Contract {
    bool requested = false;
    bool enabled = false;
    int interval = 0;
    std::string error_code;
    std::string message;
};

inline Contract resolve_contract(
        bool requested,
        const std::string& worker_strategy,
        const std::string& mode,
        long long interval) {
    Contract contract;
    contract.requested = requested;
    if (!requested) return contract;
    if (worker_strategy != "shared_unet_vae" &&
        worker_strategy != "shared_text_unet_vae") {
        contract.error_code = "UNSUPPORTED_PREVIEW_TRANSPORT";
        contract.message =
            "QNN VAE preview requires one shared UNet/VAE runtime session; split workers are unsupported.";
        return contract;
    }
    if (mode != "vae") {
        contract.error_code = "UNSUPPORTED_PREVIEW_MODE";
        contract.message = "Shared QNN preview supports only mode=vae.";
        return contract;
    }
    if (interval < kMinimumInterval || interval > kMaximumInterval) {
        contract.error_code = "INVALID_PREVIEW_INTERVAL";
        contract.message = "Shared QNN preview interval must be in [1, 10].";
        return contract;
    }
    contract.enabled = true;
    contract.interval = static_cast<int>(interval);
    return contract;
}

inline bool should_publish(
        const Contract& contract,
        int completed_step,
        int total_steps,
        bool stopped_after_failure) {
    return contract.enabled &&
        !stopped_after_failure &&
        completed_step > 0 &&
        total_steps > 1 &&
        completed_step < total_steps &&
        completed_step % contract.interval == 0;
}

inline bool contains_unsafe_path_component(const std::string& path) {
    return path.empty() || path.front() != '/' || path.find('\0') != std::string::npos ||
        path.find('\\') != std::string::npos || path.find("/../") != std::string::npos ||
        path.find("/./") != std::string::npos ||
        (path.size() >= 3U && path.compare(path.size() - 3U, 3U, "/..") == 0) ||
        (path.size() >= 2U && path.compare(path.size() - 2U, 2U, "/.") == 0);
}

inline std::string request_directory_for_journal(const std::string& journal_path) {
    static constexpr const char* kJournalSuffix = ".qnn-stage.json";
    const size_t suffix_size = std::char_traits<char>::length(kJournalSuffix);
    if (contains_unsafe_path_component(journal_path) || journal_path.size() > 4096U ||
        journal_path.size() < suffix_size ||
        journal_path.compare(journal_path.size() - suffix_size, suffix_size, kJournalSuffix) != 0) {
        return "";
    }
    return journal_path + ".previews";
}

inline std::string immutable_revision_file_name(uint64_t revision) {
    return revision == 0U ? "" : "preview-" + std::to_string(revision) + ".png";
}

struct Audit {
    size_t vae_execution_attempt_count = 0U;
    size_t vae_execution_count = 0U;
    long long vae_execution_ms_total = 0;
    size_t publication_count = 0U;
    int last_step = 0;
    uint64_t last_revision = 0U;
    std::string failure_code;

    bool stopped_after_failure() const { return !failure_code.empty(); }
};

}  // namespace mca::qnn::preview
