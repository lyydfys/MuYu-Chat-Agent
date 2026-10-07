#include "qnn_backend_owner.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "QnnBackendOwner";
}

QnnBackendOwner::QnnBackendOwner(
    std::shared_ptr<QnnRuntimeLoader> loader,
    std::string backend_name
) : mca::runtime::BackendRuntime(mca::runtime::BackendKind::QnnHtp, std::move(backend_name)),
    loader_(std::move(loader)) {
    MCA_LOGI(kTag, "QnnBackendOwner created: %s (epoch=%lld)",
             name_.c_str(), static_cast<long long>(created_epoch_));
}

QnnBackendOwner::~QnnBackendOwner() {
    release();
}

bool QnnBackendOwner::initialize(
    const std::string& system_path,
    const std::string& htp_path,
    const std::string& rpc_path,
    std::string* error
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!loader_) {
        const std::string msg = "Runtime loader is null";
        if (error) *error = msg;
        MCA_LOGE(kTag, "%s", msg.c_str());
        return false;
    }

    if (!rpc_path.empty()) {
        loader_->load_rpc(rpc_path, nullptr); // RPC load is optional/advisory
    }

    if (!loader_->load_system(system_path, error)) {
        MCA_LOGE(kTag, "Failed to load QNN system library from %s", system_path.c_str());
        return false;
    }

    if (!loader_->load_htp(htp_path, error)) {
        MCA_LOGE(kTag, "Failed to load QNN HTP library from %s", htp_path.c_str());
        return false;
    }

    is_active_.store(true, std::memory_order_relaxed);
    MCA_LOGI(kTag, "QnnBackendOwner successfully initialized");
    return true;
}

bool QnnBackendOwner::initialize_with_probe(
    const QnnCapabilityProbe::CapabilityReport& report,
    std::string* error
) {
    capability_ = report;
    MCA_LOGI(kTag, "Initializing backend with probe report (generic_fallback=%d, admitted=%d)",
             report.is_generic_fallback ? 1 : 0,
             report.is_admitted ? 1 : 0);

    // If actual library paths exist, load them
    if (report.system_present && report.htp_present) {
        return initialize(report.system_path, report.htp_path, report.rpc_path, error);
    }

    // In environment where files are not yet deployed, we acknowledge admission without crashing
    is_active_.store(true, std::memory_order_relaxed);
    return true;
}

void QnnBackendOwner::release() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_active_.load(std::memory_order_relaxed)) {
        return;
    }

    MCA_LOGI(kTag, "Releasing QnnBackendOwner resources in strict order (Context -> Device -> Backend -> Loader)");

    // 1. Release context
    if (context_handle_) {
        context_handle_ = nullptr;
    }

    // 2. Release device
    if (device_handle_) {
        device_handle_ = nullptr;
    }

    // 3. Release backend
    if (backend_handle_) {
        backend_handle_ = nullptr;
    }

    // 4. Unload shared libraries
    if (loader_) {
        loader_->unload_all();
    }

    BackendRuntime::release();
}

bool QnnBackendOwner::is_valid() const noexcept {
    if (!BackendRuntime::is_valid()) {
        return false;
    }
    if (loader_ && loader_->is_poisoned()) {
        return false;
    }
    return true;
}

void QnnBackendOwner::set_backend_handle(void* handle) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    backend_handle_ = handle;
}

void QnnBackendOwner::set_device_handle(void* handle) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    device_handle_ = handle;
}

void QnnBackendOwner::set_context_handle(void* handle) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    context_handle_ = handle;
}

} // namespace qnn
} // namespace mca
