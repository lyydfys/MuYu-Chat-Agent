#include "backend_runtime.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace runtime {

BackendRuntime::BackendRuntime(BackendKind kind, std::string backend_name)
    : kind_(kind),
      name_(std::move(backend_name)),
      created_epoch_(ProcessRuntimeHost::instance().get_runtime_epoch()) {
    MCA_LOGI("BackendRuntime", "Constructed backend runtime %s at epoch %lld",
             name_.c_str(), static_cast<long long>(created_epoch_));
}

BackendRuntime::~BackendRuntime() {
    release();
}

bool BackendRuntime::is_valid() const noexcept {
    return is_active_.load(std::memory_order_relaxed) &&
           (created_epoch_ == ProcessRuntimeHost::instance().get_runtime_epoch());
}

void BackendRuntime::release() noexcept {
    if (is_active_.exchange(false, std::memory_order_relaxed)) {
        MCA_LOGI("BackendRuntime", "Released backend runtime %s", name_.c_str());
    }
}

} // namespace runtime
} // namespace mca
