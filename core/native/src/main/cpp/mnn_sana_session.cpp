#include "mnn_sana_session.hpp"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <limits.h>
#include <memory>
#include <sys/stat.h>
#include <utility>
#include <vector>

#include <MNN/MNNForwardType.h>
#include "diffusion/diffusion.hpp"
#include "diffusion/sana_llm.hpp"
#include "image_conditioning.hpp"
#include "mnn_sana_conditioning_contract.hpp"

namespace mca {
namespace {

using MNN::DIFFUSION::Diffusion;
using MNN::DIFFUSION::SANA_DIFFUSION;
using MNN::DIFFUSION::SanaLlm;
using MNN::DIFFUSION::SanaLlmExecutionEvidence;
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

bool canonical_regular_file(const std::string& raw_path, std::string& canonical_path) {
    if (raw_path.empty() || raw_path.front() != '/') return false;
    char path_buffer[PATH_MAX] = {};
    struct stat st {};
    if (realpath(raw_path.c_str(), path_buffer) == nullptr ||
        stat(path_buffer, &st) != 0 ||
        !S_ISREG(st.st_mode)) {
        return false;
    }
    canonical_path = path_buffer;
    return canonical_path == raw_path;
}

bool valid_lower_sha256(const std::string& value) {
    return value.size() == 64U &&
           std::all_of(value.begin(), value.end(), [](unsigned char ch) {
               return std::isdigit(ch) != 0 || (ch >= 'a' && ch <= 'f');
           });
}

bool regular_file_sha256(const std::string& canonical_path, std::string& digest) {
    constexpr std::streamoff kMaxInputImageBytes = 32LL * 1024LL * 1024LL;
    struct stat st {};
    if (stat(canonical_path.c_str(), &st) != 0 || !S_ISREG(st.st_mode) ||
        st.st_size <= 0 || st.st_size > kMaxInputImageBytes) {
        return false;
    }
    std::ifstream input(canonical_path, std::ios::binary);
    if (!input.good()) return false;
    std::vector<uint8_t> payload(static_cast<size_t>(st.st_size));
    input.read(
            reinterpret_cast<char*>(payload.data()),
            static_cast<std::streamsize>(payload.size()));
    if (input.gcount() != static_cast<std::streamsize>(payload.size())) return false;
    char trailing = 0;
    if (input.get(trailing)) return false;
    digest = mca::image::sha256_hex_bytes(payload);
    return valid_lower_sha256(digest);
}

void append_u32_little_endian(uint32_t value, std::vector<uint8_t>& payload) {
    for (unsigned shift = 0U; shift < 32U; shift += 8U) {
        payload.push_back(static_cast<uint8_t>((value >> shift) & UINT32_C(0xff)));
    }
}

std::string sana_conditioning_artifact_sha256(
        const SanaLlmExecutionEvidence& evidence) {
    if (!evidence.valid() ||
        evidence.padded_token_ids.size() >
            static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
        return {};
    }
    static const std::string domain = "mca.mnn.sana.conditioning.v1";
    std::vector<uint8_t> payload(domain.begin(), domain.end());
    append_u32_little_endian(static_cast<uint32_t>(evidence.batch_size), payload);
    append_u32_little_endian(
            static_cast<uint32_t>(evidence.tokenizer_sequence_length), payload);
    append_u32_little_endian(
            static_cast<uint32_t>(evidence.non_padding_token_count), payload);
    append_u32_little_endian(static_cast<uint32_t>(evidence.meta_query_count), payload);
    append_u32_little_endian(
            static_cast<uint32_t>(evidence.padded_token_ids.size()), payload);
    for (const int id : evidence.padded_token_ids) {
        if (id < 0) return {};
        append_u32_little_endian(static_cast<uint32_t>(id), payload);
    }
    for (const int mask : evidence.attention_mask) {
        if (mask != 0 && mask != 1) return {};
        payload.push_back(static_cast<uint8_t>(mask));
    }
    return mca::image::sha256_hex_bytes(payload);
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
    const bool image_edit = options_.task_mode == "edit" || options_.task_mode == "img2img";
    if (!image_edit && options_.task_mode != "text_to_image") {
        throw std::runtime_error("MNN Sana supports taskMode=text_to_image or edit.");
    }
    std::string canonical_input_image;
    std::string verified_input_sha256;
    if (image_edit) {
        if (!canonical_regular_file(options_.input_image_path, canonical_input_image)) {
            throw std::runtime_error("MNN Sana edit requires an existing canonical worker input image.");
        }
        if (!valid_lower_sha256(options_.input_image_sha256) ||
            !regular_file_sha256(canonical_input_image, verified_input_sha256) ||
            verified_input_sha256 != options_.input_image_sha256) {
            throw std::runtime_error(
                    "MNN Sana edit input bytes differ from the prepared input SHA-256.");
        }
    } else if (!options_.input_image_path.empty() || !options_.input_image_sha256.empty()) {
        throw std::runtime_error(
                "MNN Sana text_to_image must not carry an unused input image or digest.");
    }
    completed_steps_ = 0;
    graph_invocation_count_ = 0;
    conditioning_sequence_length_ = 0;
    conditioning_batch_size_ = 0;
    conditioning_order_.clear();
    tokenizer_input_sequence_length_ = 0;
    tokenizer_input_batch_size_ = 0;
    tokenizer_non_padding_token_count_ = 0;
    tokenizer_input_order_.clear();
    conditioning_artifact_sha256_.clear();
    input_image_execution_count_ = 0;
    executed_input_image_sha256_.clear();
    report_stage("encoding", "Loading Sana LLM and tokenizer.");

    const std::string llm_path = options_.bundle_root + "/llm";
    std::unique_ptr<SanaLlm> sana_llm(new SanaLlm(llm_path));
    check_cancelled("llm_loaded");

    report_stage("encoding", "Encoding prompt with SanaLlm tokenizer_encode and hidden-state queries.");
    // SanaLlm emits [positive, negative]. SanaDiffusion owns the one required
    // swap to [negative, positive] before its CFG split. Reversing the arguments
    // here would swap twice and drive the guided result toward the negative
    // prompt.
    const auto prompt_invocation = mnn_sana_prompt_invocation(
            options_.prompt,
            options_.negative_prompt,
            options_.use_cfg);
    VARP embeddings = sana_llm->process(
            *prompt_invocation.prompt,
            prompt_invocation.use_cfg,
            *prompt_invocation.negative_prompt);
    if (embeddings.get() == nullptr) {
        throw std::runtime_error("SanaLlm failed to produce prompt embeddings.");
    }
    const auto* embedding_info = embeddings->getInfo();
    const int expected_batch = options_.use_cfg ? 2 : 1;
    if (embedding_info == nullptr || embedding_info->dim.size() < 3U ||
        embedding_info->dim[0] != expected_batch || embedding_info->dim[1] != 256) {
        throw std::runtime_error(
                "SanaLlm did not produce the required 256-query conditioning sequence.");
    }
    const auto& tokenizer_evidence = sana_llm->last_execution_evidence();
    if (!tokenizer_evidence.valid() ||
        tokenizer_evidence.batch_size != expected_batch ||
        tokenizer_evidence.meta_query_count != embedding_info->dim[1]) {
        throw std::runtime_error(
                "SanaLlm did not publish exact tokenizer input evidence for the executed conditioning.");
    }
    conditioning_batch_size_ = embedding_info->dim[0];
    conditioning_sequence_length_ = embedding_info->dim[1];
    conditioning_order_ = prompt_invocation.executed_conditioning_order;
    tokenizer_input_sequence_length_ = tokenizer_evidence.tokenizer_sequence_length;
    tokenizer_input_batch_size_ = tokenizer_evidence.batch_size;
    tokenizer_non_padding_token_count_ = tokenizer_evidence.non_padding_token_count;
    tokenizer_input_order_ = options_.use_cfg
            ? "positive_then_negative"
            : "positive_only";
    conditioning_artifact_sha256_ =
            sana_conditioning_artifact_sha256(tokenizer_evidence);
    if (!valid_lower_sha256(conditioning_artifact_sha256_)) {
        throw std::runtime_error(
                "SanaLlm tokenizer evidence could not be fingerprinted after execution.");
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

    report_stage(
            "generating",
            image_edit
                    ? "Running Sana image editing from the encoded worker input image."
                    : "Running Sana text-to-image diffusion from VARP embeddings.");
    auto checked_progress = [this](int progress) {
        check_cancelled("diffusion_progress");
        ++completed_steps_;
        ++graph_invocation_count_;
        if (progress_callback_) {
            progress_callback_(std::max(0, std::min(100, progress)));
        }
    };
    const bool ok = diffusion->run(
            embeddings,
            image_edit ? "img2img" : "text2img",
            image_edit ? canonical_input_image : "",
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
    if (completed_steps_ != options_.steps || graph_invocation_count_ != options_.steps) {
        throw std::runtime_error("MNN Sana executed a different number of diffusion steps than requested.");
    }
    if (image_edit) {
        std::string post_execution_sha256;
        if (!regular_file_sha256(canonical_input_image, post_execution_sha256) ||
            post_execution_sha256 != verified_input_sha256) {
            throw std::runtime_error("MNN Sana edit input changed during native execution.");
        }
        input_image_execution_count_ = 1;
        executed_input_image_sha256_ = std::move(post_execution_sha256);
    }

    report_stage("saving", "Sana image written to the app-owned output path.");
    if (progress_callback_) {
        progress_callback_(100);
    }
    return true;
}

} // namespace mca
