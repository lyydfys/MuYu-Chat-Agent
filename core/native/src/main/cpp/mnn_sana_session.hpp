#pragma once

#include <functional>
#include <stdexcept>
#include <string>

namespace mca {

class MnnSanaCancelled final : public std::runtime_error {
public:
    MnnSanaCancelled();
};

struct MnnSanaOptions {
    std::string bundle_root;
    std::string prompt;
    std::string negative_prompt;
    std::string output_path;
    std::string task_mode = "text_to_image";
    std::string input_image_path;
    std::string input_image_sha256;
    std::string backend_mode = "cpu";
    int memory_mode = 0;
    int width = 512;
    int height = 512;
    int steps = 5;
    int seed = -1;
    int threads = 4;
    bool use_cfg = false;
    float cfg_scale = 4.5f;
};

class MnnSanaSession final {
public:
    using ProgressCallback = std::function<void(int)>;
    using StageCallback = std::function<void(const std::string&, const std::string&)>;
    using CancellationCheck = std::function<bool()>;

    MnnSanaSession(
            MnnSanaOptions options,
            ProgressCallback progress_callback,
            StageCallback stage_callback,
            CancellationCheck cancellation_check);

    bool run();
    int completed_steps() const { return completed_steps_; }
    int graph_invocation_count() const { return graph_invocation_count_; }
    int conditioning_sequence_length() const { return conditioning_sequence_length_; }
    int conditioning_batch_size() const { return conditioning_batch_size_; }
    const std::string& conditioning_order() const { return conditioning_order_; }
    int tokenizer_input_sequence_length() const {
        return tokenizer_input_sequence_length_;
    }
    int tokenizer_input_batch_size() const { return tokenizer_input_batch_size_; }
    int tokenizer_non_padding_token_count() const {
        return tokenizer_non_padding_token_count_;
    }
    const std::string& tokenizer_input_order() const { return tokenizer_input_order_; }
    const std::string& conditioning_artifact_sha256() const {
        return conditioning_artifact_sha256_;
    }
    int input_image_execution_count() const { return input_image_execution_count_; }
    const std::string& executed_input_image_sha256() const {
        return executed_input_image_sha256_;
    }

private:
    void check_cancelled(const char* stage) const;
    void report_stage(const char* phase, const char* message) const;

    MnnSanaOptions options_;
    ProgressCallback progress_callback_;
    StageCallback stage_callback_;
    CancellationCheck cancellation_check_;
    int completed_steps_ = 0;
    int graph_invocation_count_ = 0;
    int conditioning_sequence_length_ = 0;
    int conditioning_batch_size_ = 0;
    std::string conditioning_order_;
    int tokenizer_input_sequence_length_ = 0;
    int tokenizer_input_batch_size_ = 0;
    int tokenizer_non_padding_token_count_ = 0;
    std::string tokenizer_input_order_;
    std::string conditioning_artifact_sha256_;
    int input_image_execution_count_ = 0;
    std::string executed_input_image_sha256_;
};

} // namespace mca
