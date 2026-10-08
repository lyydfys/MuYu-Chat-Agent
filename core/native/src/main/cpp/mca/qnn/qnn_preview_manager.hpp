#ifndef MCA_QNN_PREVIEW_MANAGER_HPP
#define MCA_QNN_PREVIEW_MANAGER_HPP

#include <string>
#include <atomic>
#include <mutex>
#include <cstdint>
#include "qnn_shared_preview.hpp"
#include "qnn_image_stage_trace.hpp"

namespace mca::qnn {

enum QnnImageGenerationPhase {
    kQnnImageIdle = 0,
    kQnnImageLoading = 1,
    kQnnImageContextLock = 2,
    kQnnImageContextBinaryMmap = 3,
    kQnnImageContextCreate = 4,
    kQnnImageGraphExecute = 5,
    kQnnImageSampling = 6,
    kQnnImageDecoding = 7,
    kQnnImagePngWrite = 8,
    kQnnImageContextRelease = 9,
    kQnnImageCancelling = 10
};

struct QnnImagePreviewProgress {
    std::string path;
    std::string mime_type;
    std::string mode;
    int step = 0;
    uint64_t revision = 0U;
    int width = 0;
    int height = 0;
    int frame_count = 0;
    size_t vae_execution_attempt_count = 0U;
    size_t vae_execution_count = 0U;
    long long vae_execution_ms_total = 0;
    size_t publication_count = 0U;
    int last_step = 0;
    uint64_t last_revision = 0U;
    std::string failure_code;
};

extern std::atomic<bool> g_qnn_image_generation_active;
extern std::atomic<bool> g_qnn_image_generation_cancel_requested;
extern std::atomic<int> g_qnn_image_generation_phase;
extern std::atomic<int> g_qnn_image_generation_step;
extern std::atomic<int> g_qnn_image_generation_steps;
extern std::atomic<long long> g_qnn_image_generation_started_ms;
extern std::atomic<long long> g_qnn_image_generation_sequence;
extern std::atomic<uint32_t> g_qnn_image_generation_stage_mask;
extern std::atomic<uint64_t> g_qnn_image_generation_detail_stage_mask;
extern std::mutex g_qnn_image_journal_mutex;
extern std::string g_qnn_image_journal_path;
extern std::mutex g_qnn_image_preview_publish_mutex;
extern std::mutex g_qnn_image_preview_state_mutex;
extern QnnImagePreviewProgress g_qnn_image_preview_progress;

long long monotonic_millis();
long long monotonic_sequence_seed();

const char* qnn_image_generation_phase_name(int phase);
uint32_t qnn_image_generation_stage_bit(QnnImageGenerationPhase phase);

void reset_qnn_image_preview_progress();
QnnImagePreviewProgress snapshot_qnn_image_preview_progress();
void restore_qnn_image_preview_progress(const QnnImagePreviewProgress& progress);
void update_qnn_image_preview_progress(
    const mca::qnn::preview::Audit& audit,
    const std::string& path,
    const std::string& mode,
    int step,
    uint64_t revision,
    int width,
    int height
);

bool persist_qnn_image_generation_journal();
void qnn_image_generation_set_phase(QnnImageGenerationPhase phase);
void qnn_image_generation_record_stage(ImageStage stage, QnnImageGenerationPhase phase);
bool qnn_image_generation_try_begin(int steps, const std::string& journal_path);
void qnn_image_generation_end();
bool qnn_image_generation_cancelled();

class QnnImageGenerationScope {
public:
    QnnImageGenerationScope(int steps, const std::string& journal_path);
    ~QnnImageGenerationScope();

    bool active() const { return active_; }
    bool cancelled() const { return qnn_image_generation_cancelled(); }
    void set_phase(QnnImageGenerationPhase phase) const;
    void record_stage(ImageStage stage, QnnImageGenerationPhase phase) const;
    void set_steps(int steps) const;
    void set_step(int step) const;

private:
    bool active_ = false;
};

std::string qnn_image_generation_progress_json();
std::string qnn_image_generation_cancelled_json();

} // namespace mca::qnn

// Compatibility using declarations and aliases for global scope migration
using mca::qnn::QnnImageGenerationPhase;
using mca::qnn::kQnnImageIdle;
using mca::qnn::kQnnImageLoading;
using mca::qnn::kQnnImageContextLock;
using mca::qnn::kQnnImageContextBinaryMmap;
using mca::qnn::kQnnImageContextCreate;
using mca::qnn::kQnnImageGraphExecute;
using mca::qnn::kQnnImageSampling;
using mca::qnn::kQnnImageDecoding;
using mca::qnn::kQnnImagePngWrite;
using mca::qnn::kQnnImageContextRelease;
using mca::qnn::kQnnImageCancelling;

using mca::qnn::QnnImagePreviewProgress;
using mca::qnn::QnnImageGenerationScope;
using mca::qnn::qnn_image_generation_progress_json;
using mca::qnn::qnn_image_generation_cancelled_json;
using mca::qnn::update_qnn_image_preview_progress;
using mca::qnn::persist_qnn_image_generation_journal;

using mca::qnn::g_qnn_image_generation_active;
using mca::qnn::g_qnn_image_generation_cancel_requested;
using mca::qnn::g_qnn_image_generation_phase;
using mca::qnn::g_qnn_image_generation_step;
using mca::qnn::g_qnn_image_generation_steps;
using mca::qnn::g_qnn_image_generation_started_ms;
using mca::qnn::g_qnn_image_generation_sequence;
using mca::qnn::g_qnn_image_generation_stage_mask;
using mca::qnn::g_qnn_image_generation_detail_stage_mask;
using mca::qnn::g_qnn_image_journal_path;

#endif // MCA_QNN_PREVIEW_MANAGER_HPP
