#include "qnn_runtime_loader.hpp"
#include "mca/common/logging.hpp"

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "QnnRuntimeLoader";
}

QnnRuntimeLoader::QnnRuntimeLoader() {
    MCA_LOGD(kTag, "QnnRuntimeLoader initialized");
}

QnnRuntimeLoader::~QnnRuntimeLoader() {
    unload_all();
}

void* QnnRuntimeLoader::native_dlopen(const std::string& path, std::string* error_msg) {
    if (path.empty()) {
        if (error_msg) *error_msg = "Dynamic library path is empty";
        return nullptr;
    }
#if defined(_WIN32)
    HMODULE handle = LoadLibraryA(path.c_str());
    if (!handle) {
        if (error_msg) {
            DWORD err = GetLastError();
            *error_msg = "LoadLibrary failed for " + path + " (err=" + std::to_string(err) + ")";
        }
        return nullptr;
    }
    return reinterpret_cast<void*>(handle);
#else
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        if (error_msg) {
            const char* err = dlerror();
            *error_msg = std::string("dlopen failed for ") + path + ": " + (err ? err : "unknown error");
        }
        return nullptr;
    }
    return handle;
#endif
}

void* QnnRuntimeLoader::native_dlsym(void* handle, const char* symbol, std::string* error_msg) {
    if (!handle || !symbol) {
        if (error_msg) *error_msg = "Invalid handle or symbol name";
        return nullptr;
    }
#if defined(_WIN32)
    FARPROC proc = GetProcAddress(reinterpret_cast<HMODULE>(handle), symbol);
    if (!proc && error_msg) {
        DWORD err = GetLastError();
        *error_msg = std::string("GetProcAddress failed for symbol ") + symbol + " (err=" + std::to_string(err) + ")";
    }
    return reinterpret_cast<void*>(proc);
#else
    void* sym = dlsym(handle, symbol);
    if (!sym && error_msg) {
        const char* err = dlerror();
        *error_msg = std::string("dlsym failed for symbol ") + symbol + ": " + (err ? err : "unknown error");
    }
    return sym;
#endif
}

bool QnnRuntimeLoader::native_dlclose(void* handle, std::string* error_msg) {
    if (!handle) return true;
#if defined(_WIN32)
    BOOL ok = FreeLibrary(reinterpret_cast<HMODULE>(handle));
    if (!ok && error_msg) {
        DWORD err = GetLastError();
        *error_msg = "FreeLibrary failed (err=" + std::to_string(err) + ")";
    }
    return ok != FALSE;
#else
    int rc = dlclose(handle);
    if (rc != 0 && error_msg) {
        const char* err = dlerror();
        *error_msg = std::string("dlclose failed: ") + (err ? err : "unknown error");
    }
    return rc == 0;
#endif
}

bool QnnRuntimeLoader::load_system(const std::string& path, std::string* error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (is_poisoned_.load(std::memory_order_relaxed)) {
        last_error_ = "Cannot load QNN system library on poisoned loader";
        if (error) *error = last_error_;
        MCA_LOGE(kTag, "%s", last_error_.c_str());
        return false;
    }
    if (system_handle_) {
        MCA_LOGD(kTag, "QNN System already loaded from %s", system_path_.c_str());
        return true;
    }

    std::string err_msg;
    system_handle_ = native_dlopen(path, &err_msg);
    if (!system_handle_) {
        last_error_ = err_msg;
        if (error) *error = last_error_;
        MCA_LOGW(kTag, "%s", last_error_.c_str());
        return false;
    }

    system_path_ = path;
    is_system_loaded_.store(true, std::memory_order_relaxed);

    fn_qnn_system_interface_ = native_dlsym(system_handle_, "QnnSystemInterface_getProviders", nullptr);
    if (!fn_qnn_system_interface_) {
        MCA_LOGW(kTag, "QnnSystemInterface_getProviders not found in %s", path.c_str());
    } else {
        MCA_LOGI(kTag, "QNN System library loaded successfully from %s", path.c_str());
    }
    return true;
}

bool QnnRuntimeLoader::load_htp(const std::string& path, std::string* error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (is_poisoned_.load(std::memory_order_relaxed)) {
        last_error_ = "Cannot load QNN HTP library on poisoned loader";
        if (error) *error = last_error_;
        MCA_LOGE(kTag, "%s", last_error_.c_str());
        return false;
    }
    if (htp_handle_) {
        MCA_LOGD(kTag, "QNN HTP already loaded from %s", htp_path_.c_str());
        return true;
    }

    std::string err_msg;
    htp_handle_ = native_dlopen(path, &err_msg);
    if (!htp_handle_) {
        last_error_ = err_msg;
        if (error) *error = last_error_;
        MCA_LOGW(kTag, "%s", last_error_.c_str());
        return false;
    }

    htp_path_ = path;
    is_htp_loaded_.store(true, std::memory_order_relaxed);

    fn_qnn_interface_ = native_dlsym(htp_handle_, "QnnInterface_getProviders", nullptr);
    if (!fn_qnn_interface_) {
        MCA_LOGW(kTag, "QnnInterface_getProviders not found in %s", path.c_str());
    } else {
        MCA_LOGI(kTag, "QNN HTP library loaded successfully from %s", path.c_str());
    }
    return true;
}

bool QnnRuntimeLoader::load_rpc(const std::string& path, std::string* error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (is_poisoned_.load(std::memory_order_relaxed)) {
        last_error_ = "Cannot load FastRPC library on poisoned loader";
        if (error) *error = last_error_;
        MCA_LOGE(kTag, "%s", last_error_.c_str());
        return false;
    }
    if (rpc_handle_) {
        return true;
    }

    std::string err_msg;
    rpc_handle_ = native_dlopen(path, &err_msg);
    if (!rpc_handle_) {
        last_error_ = err_msg;
        if (error) *error = last_error_;
        MCA_LOGW(kTag, "FastRPC optional library load failed: %s", last_error_.c_str());
        return false;
    }

    rpc_path_ = path;
    is_rpc_loaded_.store(true, std::memory_order_relaxed);
    MCA_LOGI(kTag, "FastRPC library loaded successfully from %s", path.c_str());
    return true;
}

bool QnnRuntimeLoader::unload_all() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    bool all_ok = true;

    // Strict LIFO unload order: HTP -> System -> RPC
    if (htp_handle_) {
        std::string err;
        if (!native_dlclose(htp_handle_, &err)) {
            MCA_LOGE(kTag, "Failed to unload QNN HTP library: %s", err.c_str());
            is_poisoned_.store(true, std::memory_order_relaxed);
            all_ok = false;
        }
        htp_handle_ = nullptr;
        fn_qnn_interface_ = nullptr;
        is_htp_loaded_.store(false, std::memory_order_relaxed);
    }

    if (system_handle_) {
        std::string err;
        if (!native_dlclose(system_handle_, &err)) {
            MCA_LOGE(kTag, "Failed to unload QNN System library: %s", err.c_str());
            is_poisoned_.store(true, std::memory_order_relaxed);
            all_ok = false;
        }
        system_handle_ = nullptr;
        fn_qnn_system_interface_ = nullptr;
        is_system_loaded_.store(false, std::memory_order_relaxed);
    }

    if (rpc_handle_) {
        std::string err;
        if (!native_dlclose(rpc_handle_, &err)) {
            MCA_LOGE(kTag, "Failed to unload FastRPC library: %s", err.c_str());
            is_poisoned_.store(true, std::memory_order_relaxed);
            all_ok = false;
        }
        rpc_handle_ = nullptr;
        is_rpc_loaded_.store(false, std::memory_order_relaxed);
    }

    return all_ok;
}

bool QnnRuntimeLoader::is_system_loaded() const noexcept {
    return is_system_loaded_.load(std::memory_order_relaxed);
}

bool QnnRuntimeLoader::is_htp_loaded() const noexcept {
    return is_htp_loaded_.load(std::memory_order_relaxed);
}

bool QnnRuntimeLoader::is_rpc_loaded() const noexcept {
    return is_rpc_loaded_.load(std::memory_order_relaxed);
}

bool QnnRuntimeLoader::is_poisoned() const noexcept {
    return is_poisoned_.load(std::memory_order_relaxed);
}

bool QnnRuntimeLoader::has_qnn_interface() const noexcept {
    return fn_qnn_interface_ != nullptr;
}

bool QnnRuntimeLoader::has_qnn_system_interface() const noexcept {
    return fn_qnn_system_interface_ != nullptr;
}

void* QnnRuntimeLoader::get_qnn_interface_fn() const noexcept {
    return fn_qnn_interface_;
}

void* QnnRuntimeLoader::get_qnn_system_interface_fn() const noexcept {
    return fn_qnn_system_interface_;
}

std::string QnnRuntimeLoader::get_last_error() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

contract::ErrorEnvelope QnnRuntimeLoader::get_last_error_envelope() const {
    std::lock_guard<std::mutex> lock(mutex_);
    contract::ErrorEnvelope env;
    env.code = contract::StableErrorCode::NAT_DSO_LOAD_FAILED;
    env.category = contract::ErrorCategory::Native;
    env.phase = contract::ErrorPhase::Load;
    env.retryability = contract::Retryability::RetryAfterRecovery;
    env.public_message = last_error_.empty() ? "QNN dynamic library load failed" : last_error_;
    return env;
}

} // namespace qnn
} // namespace mca
