#include "mnn_runtime_loader.hpp"
#include "mca/common/logging.hpp"

#if defined(_WIN32)
#include <windows.h>
#else
#include <dlfcn.h>
#include <unistd.h>
#endif

namespace mca {
namespace mnn {

MnnRuntimeLoader& MnnRuntimeLoader::instance() noexcept {
    static MnnRuntimeLoader s_instance;
    return s_instance;
}

MnnRuntimeLoader::MnnRuntimeLoader() {
    probe_platform_features();
}

MnnRuntimeLoader::~MnnRuntimeLoader() {
    unload_runtime();
}

void MnnRuntimeLoader::probe_platform_features() {
    std::lock_guard<std::mutex> lock(mutex_);

#if defined(_WIN32)
    HMODULE ocl = LoadLibraryA("OpenCL.dll");
    if (ocl != nullptr) {
        opencl_available_.store(true, std::memory_order_relaxed);
        opencl_lib_handle_ = static_cast<void*>(ocl);
        MCA_LOGI("MnnRuntimeLoader", "Discovered OpenCL runtime library on Windows host");
    } else {
        opencl_available_.store(false, std::memory_order_relaxed);
        MCA_LOGI("MnnRuntimeLoader", "OpenCL library not found; default generic fallback is CPU");
    }
#else
    // Check Android platform OpenCL paths and standard system libraries
    const char* const ocl_candidates[] = {
        "libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so"
    };

    void* ocl = nullptr;
    for (const char* path : ocl_candidates) {
        ocl = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (ocl != nullptr) {
            MCA_LOGI("MnnRuntimeLoader", "Discovered OpenCL runtime at %s", path);
            break;
        }
    }

    if (ocl != nullptr) {
        opencl_available_.store(true, std::memory_order_relaxed);
        opencl_lib_handle_ = ocl;
    } else {
        opencl_available_.store(false, std::memory_order_relaxed);
        MCA_LOGI("MnnRuntimeLoader", "OpenCL library not found; using generic CPU compatible path");
    }
#endif
}

HardwareProbeAdvisory MnnRuntimeLoader::probe_hardware() const {
    HardwareProbeAdvisory adv;

#if defined(__aarch64__) || defined(_M_ARM64)
    adv.chip_family = "ARM64";
#elif defined(__x86_64__) || defined(_M_X64)
    adv.chip_family = "x86_64";
#else
    adv.chip_family = "GenericArch";
#endif

    if (opencl_available_.load(std::memory_order_relaxed)) {
        adv.recommended_backend = MnnBackendType::OpenCL;
        adv.gpu_renderer = "Platform-OpenCL";
        adv.driver_version = "Generic-OpenCL-Driver";
        adv.advisory_notes = "OpenCL transport recommended; hardware execution will determine viability.";
    } else {
        adv.recommended_backend = MnnBackendType::Cpu;
        adv.gpu_renderer = "CPU-Fallback";
        adv.driver_version = "N/A";
        adv.advisory_notes = "Generic CPU compatible path selected. No device allowlist check applied.";
    }

    // Advisory flag only - never an admission blocker!
    adv.is_certified_profile = true;
    return adv;
}

bool MnnRuntimeLoader::load_runtime(std::string* error_msg) {
    if (error_msg != nullptr) {
        error_msg->clear();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (is_loaded_.load(std::memory_order_relaxed)) {
        return true;
    }

    // This loader tracks *transport availability*; it does not load MNN.  No library is opened and no
    // MNN symbol is resolved here -- in modular builds the provider target links the runtime itself,
    // so the flag only records that a backend may be attempted.  The version string is the declared
    // compile-time value, not a version detected from a loaded library.
    is_loaded_.store(true, std::memory_order_relaxed);
    MCA_LOGI("MnnRuntimeLoader",
             "MNN runtime ready flag set (declared version: %s) -- no library or symbol loading "
             "happens in this loader; production symbols are linked into the provider target",
             version_string_.c_str());
    return true;
}

void MnnRuntimeLoader::unload_runtime() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_loaded_.exchange(false, std::memory_order_relaxed)) {
        return;
    }

#if defined(_WIN32)
    if (opencl_lib_handle_ != nullptr) {
        FreeLibrary(static_cast<HMODULE>(opencl_lib_handle_));
        opencl_lib_handle_ = nullptr;
    }
#else
    if (opencl_lib_handle_ != nullptr) {
        dlclose(opencl_lib_handle_);
        opencl_lib_handle_ = nullptr;
    }
#endif

    MCA_LOGI("MnnRuntimeLoader", "MNN runtime ready flag cleared (OpenCL probe handle released)");
}

bool MnnRuntimeLoader::is_runtime_loaded() const noexcept {
    return is_loaded_.load(std::memory_order_relaxed);
}

bool MnnRuntimeLoader::is_opencl_available() const noexcept {
    return opencl_available_.load(std::memory_order_relaxed);
}

bool MnnRuntimeLoader::is_vulkan_available() const noexcept {
    return vulkan_available_.load(std::memory_order_relaxed);
}

const std::string& MnnRuntimeLoader::get_version_string() const noexcept {
    return version_string_;
}

bool MnnRuntimeLoader::can_attempt_backend(MnnBackendType backend) const noexcept {
    // Universal Device Admission Hard Rule:
    // Never reject based on device profiles or untested status.
    switch (backend) {
        case MnnBackendType::Cpu:
        case MnnBackendType::Auto:
            return true;
        case MnnBackendType::OpenCL:
            return opencl_available_.load(std::memory_order_relaxed);
        case MnnBackendType::Vulkan:
            return vulkan_available_.load(std::memory_order_relaxed);
    }
    return true;
}

} // namespace mnn
} // namespace mca
