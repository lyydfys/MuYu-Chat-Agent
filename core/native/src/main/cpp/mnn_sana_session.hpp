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
    std::string output_path;
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

private:
    void check_cancelled(const char* stage) const;
    void report_stage(const char* phase, const char* message) const;

    MnnSanaOptions options_;
    ProgressCallback progress_callback_;
    StageCallback stage_callback_;
    CancellationCheck cancellation_check_;
};

} // namespace mca
