#include "mnn_backend_owner.hpp"
#include "mca/common/logging.hpp"
#include <algorithm>

namespace mca {
namespace mnn {

MnnBackendOwner::MnnBackendOwner(
    MnnRuntimeConfig config,
    std::shared_ptr<MnnRuntimeLoader> loader
) : BackendRuntime(runtime::BackendKind::MnnLlm, "mnn_backend"),
    config_(std::move(config)),
    loader_(std::move(loader)) {
    if (!loader_) {
        // Shared reference to process loader
        loader_ = std::shared_ptr<MnnRuntimeLoader>(&MnnRuntimeLoader::instance(), [](MnnRuntimeLoader*){});
    }
    thread_count_ = std::max(1, std::min(64, config_.threads));
    tuning_map_ = config_.device_local_tuning;
}

MnnBackendOwner::~MnnBackendOwner() {
    release();
}

bool MnnBackendOwner::initialize(std::string* error_msg) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!loader_->load_runtime(error_msg)) {
        MCA_LOGE("MnnBackendOwner", "Failed to load MNN runtime dependencies");
        return false;
    }

    // Universal Device Admission Hard Invariant:
    // Fall back to generic CPU path if OpenCL is requested but unavailable on this device.
    if (config_.backend_type == MnnBackendType::Auto) {
        if (loader_->is_opencl_available()) {
            active_backend_type_ = MnnBackendType::OpenCL;
        } else {
            active_backend_type_ = MnnBackendType::Cpu;
        }
    } else if (config_.backend_type == MnnBackendType::OpenCL) {
        if (loader_->is_opencl_available()) {
            active_backend_type_ = MnnBackendType::OpenCL;
        } else {
            MCA_LOGW("MnnBackendOwner",
                     "OpenCL backend requested but driver unavailable; generic CPU fallback engaged");
            active_backend_type_ = MnnBackendType::Cpu;
        }
    } else {
        active_backend_type_ = config_.backend_type;
    }

    // Allocate dummy / native handle marker for backend
    backend_handle_ = reinterpret_cast<void*>(0x4D4E4E01); // 'MNN\x01'

    MCA_LOGI("MnnBackendOwner",
             "Initialized MNN backend [type=%d, threads=%d, precision=%d, epoch=%lld]",
             static_cast<int>(active_backend_type_),
             thread_count_,
             static_cast<int>(config_.precision),
             static_cast<long long>(created_epoch_));
    return true;
}

void MnnBackendOwner::release() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (backend_handle_ != nullptr) {
        backend_handle_ = nullptr;
        MCA_LOGI("MnnBackendOwner", "Destroyed MNN backend execution context");
    }
    BackendRuntime::release();
}

bool MnnBackendOwner::is_valid() const noexcept {
    return BackendRuntime::is_valid() && (backend_handle_ != nullptr);
}

void MnnBackendOwner::set_tuning_parameter(const std::string& key, const std::string& value) {
    std::lock_guard<std::mutex> lock(mutex_);
    tuning_map_[key] = value;
    MCA_LOGD("MnnBackendOwner", "Updated device-local tuning: %s=%s", key.c_str(), value.c_str());
}

std::string MnnBackendOwner::get_tuning_parameter(const std::string& key) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = tuning_map_.find(key);
    if (it != tuning_map_.end()) {
        return it->second;
    }
    return {};
}

} // namespace mnn
} // namespace mca
