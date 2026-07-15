#include "mnn_sana_session.hpp"

#include <android/log.h>

#include <algorithm>
#include <memory>
#include <utility>

#include <MNN/MNNForwardType.h>
#include "diffusion/diffusion.hpp"
#include "diffusion/sana_llm.hpp"

namespace mca {
namespace {

using MNN::DIFFUSION::Diffusion;
using MNN::DIFFUSION::SANA_DIFFUSION;
using MNN::DIFFUSION::SanaLlm;
using MNN::Express::VARP;

void sana_log(const char* phase, const std::string& message) {
    __android_log_print(
            ANDROID_LOG_INFO,
            "mca_mnn_sana",
            "[%s] %s",
            phase,
            message.c_str());
}

MNNForwardType backend_type(const std::string& backend_mode) {
    return backend_mode == "opencl"
            ? MNN_FORWARD_OPENCL
            : MNN_FORWARD_CPU;
}

} // namespace

MnnSanaCancelled::MnnSanaCancelled()
    : std::runtime_error("MNN Sana generation was cancelled.") {
}

MnnSanaSession::MnnSanaSession(
        MnnSanaOptions options,
        ProgressCallback progress_callback,
        StageCallback stage_callback,
        CancellationCheck cancellation_check)
    : options_(std::move(options)),
       progress_callback_(std::move(progress_callback)),
       stage_callback_(std::move(stage_callback)),
       cancellation_check_(std::move(cancellation_check)) {
}

void MnnSanaSession::check_cancelled(const char* stage) const {
    if (cancellation_check_ && cancellation_check_()) {
        sana_log(stage, "Cancellation requested.");
        throw MnnSanaCancelled();
    }
}

void MnnSanaSession::report_stage(const char* phase, const char* message) const {
    sana_log(phase, message);
    if (stage_callback_) {
        stage_callback_(phase, message);
    }
}

bool MnnSanaSession::run() {
    check_cancelled("start");
    if (options_.bundle_root.empty()) {
        throw std::runtime_error("MNN Sana bundle root is empty.");
    }
    if (options_.output_path.empty()) {
        throw std::runtime_error("MNN Sana output path is empty.");
    }
    if (options_.backend_mode != "cpu" && options_.backend_mode != "opencl") {
        throw std::runtime_error("MNN Sana backend must be cpu or opencl.");
    }
    if (options_.steps < 2 || options_.steps > 50) {
        throw std::runtime_error("MNN Sana steps must be between 2 and 50.");
    }
    if (options_.width < 256 || options_.width > 2048 || options_.height < 256 ||
        options_.height > 2048 || options_.width % 32 != 0 || options_.height % 32 != 0) {
        throw std::runtime_error("MNN Sana width and height must be multiples of 32 between 256 and 2048.");
    }
    report_stage("encoding", "Loading Sana LLM and tokenizer.");

    const std::string llm_path = options_.bundle_root + "/llm";
    std::unique_ptr<SanaLlm> sana_llm(new SanaLlm(llm_path));
    check_cancelled("llm_loaded");

    report_stage("encoding", "Encoding prompt with SanaLlm tokenizer_encode and hidden-state queries.");
    VARP embeddings = options_.use_cfg
            ? sana_llm->process(options_.prompt, true, "")
            : sana_llm->process(options_.prompt, false);
    if (embeddings.get() == nullptr) {
        throw std::runtime_error("SanaLlm failed to produce prompt embeddings.");
    }
    embeddings.fix(VARP::CONSTANT);
    check_cancelled("prompt_encoded");

    report_stage("loading", "Loading Sana connector, projector, transformer, and VAE decoder.");
    report_stage("cache", "MNN/OpenCL cache placement is managed by the linked MNN runtime.");
    std::unique_ptr<Diffusion> diffusion(Diffusion::createDiffusion(
            options_.bundle_root,
            SANA_DIFFUSION,
            backend_type(options_.backend_mode),
            options_.memory_mode));
    if (!diffusion) {
        throw std::runtime_error("Failed to create MNN Sana diffusion runtime.");
    }
    if (!diffusion->load()) {
        throw std::runtime_error("MNN Sana diffusion runtime failed to load.");
    }
    check_cancelled("diffusion_loaded");

    report_stage("generating", "Running Sana text-to-image diffusion from VARP embeddings.");
    auto checked_progress = [this](int progress) {
        check_cancelled("diffusion_progress");
        if (progress_callback_) {
            progress_callback_(std::max(0, std::min(100, progress)));
        }
    };
    const bool ok = diffusion->run(
            embeddings,
            "text2img",
            "",
            options_.output_path,
            options_.width,
            options_.height,
            options_.steps,
            options_.seed,
            options_.use_cfg,
            options_.cfg_scale,
            std::move(checked_progress));
    check_cancelled("diffusion_finished");
    if (!ok) {
        throw std::runtime_error("MNN Sana diffusion failed to generate an image.");
    }

    report_stage("saving", "Sana image written to the app-owned output path.");
    if (progress_callback_) {
        progress_callback_(100);
    }
    return true;
}

} // namespace mca
