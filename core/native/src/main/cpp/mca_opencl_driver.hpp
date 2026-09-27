#pragma once

#include <dlfcn.h>

namespace mca::opencl {

inline constexpr const char * driver_paths[] = {
    "libOpenCL.so", "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so"
};

static inline void * driver_symbol(void * handle, const char * name) {
    if (handle == nullptr) return nullptr;
    void * symbol = dlsym(handle, name);
    Dl_info target{}, local{};
    if (symbol != nullptr && dladdr(symbol, &target) != 0 &&
        dladdr(reinterpret_cast<void *>(&driver_symbol), &local) != 0 &&
        target.dli_fbase == local.dli_fbase) return nullptr;
    return symbol;
}

static inline const char * missing_required_symbol(void * handle) {
    constexpr const char * required[] = {
        "clGetPlatformIDs", "clGetPlatformInfo", "clGetDeviceIDs", "clGetDeviceInfo",
        "clCreateContext", "clCreateCommandQueue", "clCreateBuffer", "clCreateSubBuffer",
        "clCreateProgramWithSource", "clBuildProgram", "clCreateKernel", "clSetKernelArg",
        "clEnqueueNDRangeKernel", "clEnqueueReadBuffer", "clEnqueueWriteBuffer", "clFinish",
        "clReleaseMemObject", "clReleaseKernel", "clReleaseProgram", "clReleaseContext",
        "clReleaseCommandQueue"
    };
    for (const char * name : required) if (driver_symbol(handle, name) == nullptr) return name;
    return nullptr;
}

} // namespace mca::opencl
