#pragma once

// The shared live-preview publisher.
//
// This type lived inline in qnn_native_bridge.cpp.  When the bridge was slimmed from ~11.9k lines to
// ~1k the graph/semantic/conditioning logic moved into mca/qnn modules -- but this enum and class were
// not carried over with it, so qnn_semantic_pipeline.cpp ended up constructing a type that no header
// defined (QnnSharedPreviewPublisher appeared exactly once in the tree: at its own construction site).
//
// Restored verbatim from the bridge (HEAD 3767..3967) and placed in mca::qnn, which is the namespace
// the call sites already live in.  The helper free functions that followed the class in the bridge
// (qnn_consumed_payload_sha256, read_float_binary_file, ...) were already extracted into
// qnn_executable_graph.hpp/.cpp, so they are deliberately NOT duplicated here.

#include <cstdint>
#include <fstream>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "qnn_shared_preview.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_image_writer.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"

namespace mca {
namespace qnn {

// g_qnn_context_execution_mutex is deliberately NOT defined in this header.
//
// The pre-refactor bridge kept it in its own translation unit (76348a5b
// core/native/src/main/cpp/qnn_native_bridge.cpp:99, inside an anonymous namespace), so every user
// was in that one TU.  The graph / semantic / SDXL-phase code now spans several translation units
// (`qnn_native_bridge.cpp` in mca_qnn_native, plus qnn_semantic_pipeline.cpp and the SDXL phase
// headers reached from it), so the object is declared extern in
// mca/qnn/qnn_preview_manager.hpp and defined exactly once in mca/qnn/qnn_preview_manager.cpp.
//
// Defining it here would (a) give every including TU its own non-inline definition -- ODR /
// "multiple definition" at link time -- and (b) leave the global-scope `extern` that used to sit in
// qnn_native_bridge.cpp referring to a different, never-defined `::g_qnn_context_execution_mutex`,
// i.e. callers would lock two unrelated mutexes and real QNN context execution would not be
// serialised at all.

enum class QnnPreviewPublishOutcome {
    Skipped,
    Published,
    Failed,
    Cancelled,
};

class QnnSharedPreviewPublisher {
public:
    QnnSharedPreviewPublisher(
            mca::qnn::preview::Contract contract,
            std::string journal_path)
        : contract_(std::move(contract)),
          directory_(contract_.enabled
              ? mca::qnn::preview::request_directory_for_journal(journal_path)
              : "") {}

    QnnSharedPreviewPublisher(const QnnSharedPreviewPublisher&) = delete;
    QnnSharedPreviewPublisher& operator=(const QnnSharedPreviewPublisher&) = delete;

    ~QnnSharedPreviewPublisher() {
        // A committed frame and its journal remain available until the Android
        // provider performs its terminal drain and request-scoped cleanup.
        // Only an unpublished temporary file belongs to this native scope.
        if (!temporary_path_.empty()) ::unlink(temporary_path_.c_str());
    }

    bool initialize(std::string* error) {
        if (!contract_.enabled) return true;
        if (directory_.empty()) {
            *error = "Shared QNN preview requires a safe request-scoped stage journal path.";
            fail("PREVIEW_STORAGE_INVALID");
            return false;
        }
        if (::mkdir(directory_.c_str(), 0700) != 0) {
            *error = errno == EEXIST
                ? "Shared QNN preview request directory already exists."
                : "Unable to create the shared QNN preview request directory.";
            fail("PREVIEW_STORAGE_INVALID");
            return false;
        }
        update_qnn_image_preview_progress(audit_, "", "vae", 0, 0U, 0, 0);
        if (!persist_qnn_image_generation_journal()) {
            *error = "Unable to persist the shared QNN preview journal.";
            fail("PREVIEW_JOURNAL_COMMIT_FAILED");
            return false;
        }
        return true;
    }

    const mca::qnn::preview::Contract& contract() const { return contract_; }
    const mca::qnn::preview::Audit& audit() const { return audit_; }

    QnnPreviewPublishOutcome publish_if_due(
            int completed_step,
            int total_steps,
            const std::vector<float>& host_latents,
            double effective_vae_host_scale,
            QnnExecutableGraph& vae,
            mca::qnn::ImagePixelRange pixel_range) {
        if (!mca::qnn::preview::should_publish(
                contract_,
                completed_step,
                total_steps,
                audit_.stopped_after_failure())) {
            return QnnPreviewPublishOutcome::Skipped;
        }
        if (qnn_image_generation_cancelled()) return QnnPreviewPublishOutcome::Cancelled;

        ++audit_.vae_execution_attempt_count;
        if (!sync_audit()) return fail("PREVIEW_JOURNAL_COMMIT_FAILED");
        std::vector<float> preview_latents = host_latents;
        if (effective_vae_host_scale != 1.0) {
            for (float& value : preview_latents) {
                value = static_cast<float>(value * effective_vae_host_scale);
            }
        }
        std::string error;
        if (!qnn_write_float_tensor(
                &vae.inputs[0],
                preview_latents.data(),
                preview_latents.size(),
                &error)) {
            return fail("PREVIEW_VAE_INPUT_BIND_FAILED");
        }
        if (qnn_image_generation_cancelled()) return QnnPreviewPublishOutcome::Cancelled;
        long long execute_ms = 0;
        const bool executed = vae.execute(&execute_ms, &error, true);
        audit_.vae_execution_ms_total += std::max(0LL, execute_ms);
        if (!executed) return fail("PREVIEW_VAE_EXECUTE_FAILED");
        ++audit_.vae_execution_count;
        if (!sync_audit()) return fail("PREVIEW_JOURNAL_COMMIT_FAILED");
        if (qnn_image_generation_cancelled()) return QnnPreviewPublishOutcome::Cancelled;

        std::vector<float> pixels;
        if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
            return fail("PREVIEW_VAE_OUTPUT_READ_FAILED");
        }
        const uint64_t candidate_revision = audit_.last_revision + 1U;
        const std::string file_name =
            mca::qnn::preview::immutable_revision_file_name(candidate_revision);
        if (file_name.empty()) return fail("PREVIEW_REVISION_INVALID");
        const std::string target_path = join_path(directory_, file_name);
        temporary_path_ = target_path + ".tmp";
        ::unlink(temporary_path_.c_str());
        int width = 0;
        int height = 0;
        mca::qnn::ImagePixelRangeEvidence ignored_pixel_range_evidence;
        if (!write_vae_tensor_png(
                vae.outputs[0],
                pixels,
                temporary_path_,
                pixel_range,
                &ignored_pixel_range_evidence,
                &width,
                &height,
                &error)) {
            return fail("PREVIEW_PNG_WRITE_FAILED");
        }
        if (!validate_preview_png(temporary_path_, width, height, &error)) {
            return fail("PREVIEW_PNG_INVALID");
        }

        std::string previous_path;
        {
            std::lock_guard<std::mutex> publish_lock(g_qnn_image_preview_publish_mutex);
            if (qnn_image_generation_cancelled()) {
                ::unlink(temporary_path_.c_str());
                temporary_path_.clear();
                return QnnPreviewPublishOutcome::Cancelled;
            }
            struct stat existing {};
            if (::lstat(target_path.c_str(), &existing) == 0 ||
                ::rename(temporary_path_.c_str(), target_path.c_str()) != 0) {
                return fail("PREVIEW_ATOMIC_RENAME_FAILED");
            }
            temporary_path_.clear();
            if (!fsync_directory(directory_, &error)) {
                ::unlink(target_path.c_str());
                return fail("PREVIEW_DIRECTORY_FSYNC_FAILED");
            }
            previous_path = current_path_;
            const int previous_width = current_width_;
            const int previous_height = current_height_;
            const mca::qnn::preview::Audit previous_audit = audit_;
            const QnnImagePreviewProgress previous_progress =
                snapshot_qnn_image_preview_progress();
            current_path_ = target_path;
            current_width_ = width;
            current_height_ = height;
            ++audit_.publication_count;
            audit_.last_step = completed_step;
            audit_.last_revision = candidate_revision;
            update_qnn_image_preview_progress(
                audit_,
                current_path_,
                "vae",
                completed_step,
                candidate_revision,
                width,
                height);
            if (!sync_audit()) {
                current_path_ = previous_path;
                current_width_ = previous_width;
                current_height_ = previous_height;
                audit_ = previous_audit;
                restore_qnn_image_preview_progress(previous_progress);
                ::unlink(target_path.c_str());
                return fail("PREVIEW_JOURNAL_COMMIT_FAILED");
            }
        }
        if (!previous_path.empty()) ::unlink(previous_path.c_str());
        return QnnPreviewPublishOutcome::Published;
    }

private:
    QnnPreviewPublishOutcome fail(const std::string& code) {
        if (!temporary_path_.empty()) {
            ::unlink(temporary_path_.c_str());
            temporary_path_.clear();
        }
        if (audit_.failure_code.empty()) audit_.failure_code = code;
        sync_audit();
        return QnnPreviewPublishOutcome::Failed;
    }

    bool sync_audit() {
        update_qnn_image_preview_progress(
            audit_, current_path_, contract_.requested ? "vae" : "",
            audit_.last_step, audit_.last_revision, current_width_, current_height_);
        return persist_qnn_image_generation_journal();
    }

    mca::qnn::preview::Contract contract_;
    std::string directory_;
    std::string temporary_path_;
    std::string current_path_;
    int current_width_ = 0;
    int current_height_ = 0;
    mca::qnn::preview::Audit audit_;
};

}  // namespace qnn
}  // namespace mca
