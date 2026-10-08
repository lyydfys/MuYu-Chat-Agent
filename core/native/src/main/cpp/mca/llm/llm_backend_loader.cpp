#include "mca/llm/llm_backend_loader.hpp"

#include <dirent.h>
#include <dlfcn.h>
#include <sys/stat.h>

#include <cstdint>
#include <string>

#include "ggml-backend.h"
#include "mca_opencl_driver.hpp"

namespace mca {
namespace llm {

bool file_exists(const std::string &path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string join_path(const std::string &dir, const std::string &name) {
    if (dir.empty()) return name;
    return dir.back() == '/' ? dir + name : dir + "/" + name;
}

std::string list_ggml_backend_files(const std::string &dir) {
    if (dir.empty()) return "nativeLibDir is empty";
    DIR *handle = opendir(dir.c_str());
    if (handle == nullptr) {
        return "nativeLibDir is not readable or native libs are not extracted: " + dir;
    }

    std::string result;
    int count = 0;
    while (dirent *entry = readdir(handle)) {
        const std::string name = entry->d_name;
        if (name.find("libggml") == 0 && name.size() >= 3 && name.rfind(".so") == name.size() - 3) {
            if (!result.empty()) result += ", ";
            result += name;
            count++;
            if (count >= 24) {
                result += ", ...";
                break;
            }
        }
    }
    closedir(handle);
    return result.empty() ? "no libggml*.so files found in " + dir : result;
}

void load_baseline_cpu_backend_if_needed(
        const std::string &native_lib_dir,
        std::string &last_error) {
    if (ggml_backend_dev_count() > 0 || native_lib_dir.empty()) return;

    const char *candidates[] = {
            "libggml-cpu-android_armv8.0_1.so",
            "libggml-cpu-x64.so",
            "libggml-cpu-sse42.so",
            "libggml-cpu.so",
    };

    for (const char *candidate: candidates) {
        const std::string path = join_path(native_lib_dir, candidate);
        if (!file_exists(path)) continue;
        if (ggml_backend_load(path.c_str()) != nullptr) {
            last_error += "\nLoaded fallback backend: " + path;
            return;
        }
    }
}

bool load_android_opencl_runtime_if_available_locked(
        void *&android_opencl_handle,
        std::string &backend_load_diagnostic) {
#if defined(__ANDROID__) && defined(__aarch64__)
    if (android_opencl_handle != nullptr) {
        using get_platform_ids_fn = int (*)(std::uint32_t, void **, std::uint32_t *);
        auto get_platform_ids = reinterpret_cast<get_platform_ids_fn>(
                mca::opencl::driver_symbol(android_opencl_handle, "clGetPlatformIDs"));
        if (get_platform_ids == nullptr) {
            backend_load_diagnostic += "Android OpenCL runtime loaded but clGetPlatformIDs is not exported.";
            return false;
        }
        std::uint32_t count = 0;
        const int status = get_platform_ids(0, nullptr, &count);
        if (status != 0 || count == 0) {
            backend_load_diagnostic += "Android OpenCL runtime loaded but reports no usable platform (clGetPlatformIDs status=";
            backend_load_diagnostic += std::to_string(status) + ", count=" + std::to_string(count) + ").";
            return false;
        }
        return true;
    }
    // libOpenCL.so is an optional platform native library on supported
    // devices. Keep CPU-only devices installable by treating dlopen failure as
    // a normal accelerator miss. RTLD_LOCAL avoids leaking vendor symbols into
    // unrelated JNI libraries; mca_opencl_dispatch resolves its own symbols.
    for (const char * candidate : mca::opencl::driver_paths) {
        void * driver = dlopen(candidate, RTLD_NOW | RTLD_LOCAL);
        if (driver == nullptr) continue;
        const char * missing = mca::opencl::missing_required_symbol(driver);
        if (missing != nullptr) {
            backend_load_diagnostic += std::string(candidate) + " missing driver symbol " + missing + ". ";
            dlclose(driver);
            continue;
        }
        android_opencl_handle = driver;
        backend_load_diagnostic += std::string("OpenCL driver loaded: ") + candidate + ". ";
        break;
    }
    if (android_opencl_handle == nullptr) {
        const char *diagnostic = dlerror();
        backend_load_diagnostic += "Android OpenCL runtime unavailable: ";
        backend_load_diagnostic += diagnostic == nullptr ? "dlopen(libOpenCL.so) failed." : diagnostic;
        return false;
    }
    using get_platform_ids_fn = int (*)(std::uint32_t, void **, std::uint32_t *);
    auto get_platform_ids = reinterpret_cast<get_platform_ids_fn>(
            mca::opencl::driver_symbol(android_opencl_handle, "clGetPlatformIDs"));
    if (get_platform_ids == nullptr) {
        backend_load_diagnostic += "Android OpenCL runtime loaded from libOpenCL.so but clGetPlatformIDs is not exported.";
        return false;
    }
    std::uint32_t count = 0;
    const int status = get_platform_ids(0, nullptr, &count);
    if (status != 0 || count == 0) {
        backend_load_diagnostic += "Android OpenCL runtime loaded but reports no usable platform (clGetPlatformIDs status=";
        backend_load_diagnostic += std::to_string(status) + ", count=" + std::to_string(count) + ").";
        return false;
    }
    backend_load_diagnostic += "Android OpenCL runtime loaded from libOpenCL.so; platform count=" + std::to_string(count) + ".";
    return true;
#else
    return true;
#endif
}

}  // namespace llm
}  // namespace mca
