#ifndef MCA_RUNTIME_BACKEND_RUNTIME_HPP
#define MCA_RUNTIME_BACKEND_RUNTIME_HPP

#include <string>
#include <cstdint>
#include <memory>
#include <atomic>
#include "process_runtime_host.hpp"

namespace mca {
namespace runtime {

enum class BackendKind : uint8_t {
    GenericCpu = 0,
    LlamaOpenCl = 1,
    MnnLlm = 2,
    QnnHtp = 3,
    StableDiffusion = 4
};

/**
 * Backend runtime managing device handles, library version, and allocator state.
 *
 * Epoch comparison is scoped to this DSO's ProcessRuntimeHost. A BackendRuntime
 * object, its owner, and its epoch source must stay within one loaded shared
 * object; no process-wide uniqueness is implied across JNI DSOs.
 */
class BackendRuntime {
public:
    explicit BackendRuntime(BackendKind kind, std::string backend_name);
    virtual ~BackendRuntime();

    [[nodiscard]] BackendKind get_kind() const noexcept { return kind_; }
    [[nodiscard]] const std::string& get_name() const noexcept { return name_; }
    [[nodiscard]] int64_t get_created_epoch() const noexcept { return created_epoch_; }

    [[nodiscard]] bool is_valid() const noexcept;
    virtual void release() noexcept;

protected:
    BackendKind kind_;
    std::string name_;
    int64_t created_epoch_{1};
    std::atomic<bool> is_active_{true};
};

} // namespace runtime
} // namespace mca

#endif // MCA_RUNTIME_BACKEND_RUNTIME_HPP
