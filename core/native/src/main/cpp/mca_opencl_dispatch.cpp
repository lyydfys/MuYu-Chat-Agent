/*
 * Android OpenCL dispatch for the llama.cpp backend.
 *
 * Android vendor OpenCL implementations are not part of the NDK and are
 * loaded through an application linker namespace. Leaving cl* references as
 * direct imports makes dlopen(libggml-opencl-mca.so) depend on linker
 * visibility details and, on some Qualcomm builds, fails with "cannot locate
 * symbol clGetPlatformIDs" even though the vendor library exports it. These
 * small ABI-preserving trampolines keep the backend self-contained and resolve
 * the real implementation explicitly at runtime.
 * A missing runtime returns the normal OpenCL error value so the caller can
 * reject the accelerator and retain the CPU backend instead of crashing.
 */

#include <CL/cl.h>
#include <CL/cl_ext.h>

#include <dlfcn.h>
#include <cstdint>
#include <limits>
#include <mutex>
#include <cstdio>
#include <unordered_map>
#include "mca_opencl_driver.hpp"

namespace {

class OpenClExecutionEvidence {
public:
    struct Checkpoint {
        std::uint64_t scope = 0;
        std::uint64_t submitted = 0;
        std::uint64_t queue_generation = 0;
    };

    std::uint64_t begin_scope() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (scope_ == std::numeric_limits<std::uint64_t>::max()) return 0;
        ++scope_;
        queues_.clear();
        queue_generation_ = 0;
        submitted_ = completed_ = 0;
        valid_ = true;
        return scope_;
    }

    Checkpoint checkpoint(cl_command_queue queue) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = queues_.find(queue);
        return {scope_, found == queues_.end() ? 0 : found->second.submitted,
                found == queues_.end() ? 0 : found->second.generation};
    }

    void record_submission(cl_command_queue queue, std::uint64_t scope, bool succeeded) {
        if (!succeeded) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (scope == 0 || scope != scope_ || !valid_) return;
        if (submitted_ == std::numeric_limits<std::uint64_t>::max()) {
            valid_ = false;
            return;
        }
        try {
            auto found = queues_.find(queue);
            if (found == queues_.end()) {
                if (queue_generation_ == std::numeric_limits<std::uint64_t>::max()) {
                    valid_ = false;
                    return;
                }
                found = queues_.emplace(queue, QueueProgress{++queue_generation_, 0, 0}).first;
            }
            ++found->second.submitted;
            ++submitted_;
        } catch (...) {
            valid_ = false;
        }
    }

    void record_completion(cl_command_queue queue, Checkpoint checkpoint, bool succeeded) {
        if (!succeeded) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (checkpoint.scope == 0 || checkpoint.scope != scope_ || !valid_) return;
        const auto found = queues_.find(queue);
        if (found == queues_.end() || checkpoint.queue_generation != found->second.generation ||
            checkpoint.submitted > found->second.submitted ||
            checkpoint.submitted <= found->second.completed) return;
        completed_ += checkpoint.submitted - found->second.completed;
        found->second.completed = checkpoint.submitted;
    }

    void forget_queue(cl_command_queue queue) {
        std::lock_guard<std::mutex> lock(mutex_);
        queues_.erase(queue);
    }

    bool snapshot(std::uint64_t scope, std::uint64_t * submitted, std::uint64_t * completed) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (scope == 0 || scope != scope_ || !valid_ || submitted == nullptr || completed == nullptr) return false;
        *submitted = submitted_;
        *completed = completed_;
        return true;
    }

private:
    struct QueueProgress {
        std::uint64_t generation = 0;
        std::uint64_t submitted = 0;
        std::uint64_t completed = 0;
    };
    std::mutex mutex_;
    std::unordered_map<cl_command_queue, QueueProgress> queues_;
    std::uint64_t scope_ = 0;
    std::uint64_t queue_generation_ = 0;
    std::uint64_t submitted_ = 0;
    std::uint64_t completed_ = 0;
    bool valid_ = false;
};

OpenClExecutionEvidence execution_evidence;

void * opencl_handle() {
    static void * handle = nullptr;
    static std::once_flag once;
    std::call_once(once, [] {
        // The bare soname is required for Android's permitted native-library
        // namespace.  Absolute vendor paths cover devices whose vendor
        // linker namespace does not add the soname to the default search.
        for (const char * candidate : mca::opencl::driver_paths) {
            handle = dlopen(candidate, RTLD_NOW | RTLD_LOCAL);
            if (handle != nullptr) {
                const char * missing = mca::opencl::missing_required_symbol(handle);
                if (missing != nullptr) {
                    std::fprintf(stderr, "mca_opencl_dispatch: %s missing driver symbol %s\n", candidate, missing);
                    dlclose(handle);
                    handle = nullptr;
                    continue;
                }
                return;
            }
        }
        const char * error = dlerror();
        std::fprintf(stderr, "mca_opencl_dispatch: unable to load vendor OpenCL: %s\n",
                     error == nullptr ? "unknown error" : error);
    });
    return handle;
}

template<typename Fn>
Fn opencl_symbol(const char * name) {
    void * handle = opencl_handle();
    if (handle == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<Fn>(mca::opencl::driver_symbol(handle, name));
}

} // namespace

extern "C" __attribute__((visibility("default"))) std::uint64_t mca_opencl_begin_execution_scope() {
    return execution_evidence.begin_scope();
}

extern "C" __attribute__((visibility("default"))) int mca_opencl_execution_snapshot(
        std::uint64_t scope, std::uint64_t * submitted, std::uint64_t * completed) {
    return execution_evidence.snapshot(scope, submitted, completed) ? 1 : 0;
}

extern "C" cl_int clGetPlatformIDs(cl_uint n, cl_platform_id * p, cl_uint * np) {
    using Fn = cl_int (*)(cl_uint, cl_platform_id *, cl_uint *);
    static Fn fn = opencl_symbol<Fn>("clGetPlatformIDs");
    return fn == nullptr ? CL_PLATFORM_NOT_FOUND_KHR : fn(n, p, np);
}

extern "C" cl_int clGetPlatformInfo(cl_platform_id p, cl_platform_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_platform_id, cl_platform_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetPlatformInfo");
    return fn == nullptr ? CL_INVALID_PLATFORM : fn(p, i, s, v, r);
}

extern "C" cl_int clGetDeviceIDs(cl_platform_id p, cl_device_type t, cl_uint n, cl_device_id * d, cl_uint * nd) {
    using Fn = cl_int (*)(cl_platform_id, cl_device_type, cl_uint, cl_device_id *, cl_uint *);
    static Fn fn = opencl_symbol<Fn>("clGetDeviceIDs");
    return fn == nullptr ? CL_DEVICE_NOT_FOUND : fn(p, t, n, d, nd);
}

extern "C" cl_int clGetDeviceInfo(cl_device_id d, cl_device_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_device_id, cl_device_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetDeviceInfo");
    return fn == nullptr ? CL_INVALID_DEVICE : fn(d, i, s, v, r);
}

extern "C" cl_int clGetKernelInfo(cl_kernel k, cl_kernel_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_kernel, cl_kernel_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetKernelInfo");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(k, i, s, v, r);
}

extern "C" cl_context clCreateContext(const cl_context_properties * p, cl_uint n, const cl_device_id * d,
                                       void (CL_CALLBACK * cb)(const char *, const void *, size_t, void *),
                                       void * u, cl_int * e) {
    using Fn = cl_context (*)(const cl_context_properties *, cl_uint, const cl_device_id *,
                              void (CL_CALLBACK *)(const char *, const void *, size_t, void *), void *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateContext");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(p, n, d, cb, u, e);
}

extern "C" cl_command_queue clCreateCommandQueue(cl_context c, cl_device_id d, cl_command_queue_properties p, cl_int * e) {
    using Fn = cl_command_queue (*)(cl_context, cl_device_id, cl_command_queue_properties, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateCommandQueue");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(c, d, p, e);
}

extern "C" cl_int clReleaseCommandQueue(cl_command_queue q) {
    using Fn = cl_int (*)(cl_command_queue);
    static Fn fn = opencl_symbol<Fn>("clReleaseCommandQueue");
    if (fn == nullptr) return CL_INVALID_OPERATION;
    const cl_int status = fn(q);
    if (status == CL_SUCCESS) execution_evidence.forget_queue(q);
    return status;
}

extern "C" cl_mem clCreateBuffer(cl_context c, cl_mem_flags f, size_t s, void * h, cl_int * e) {
    using Fn = cl_mem (*)(cl_context, cl_mem_flags, size_t, void *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateBuffer");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(c, f, s, h, e);
}

// clCreateBufferWithProperties is an OpenCL 3.0 entry point, while the
// Android header set used by the normal MCA build targets OpenCL 2.2. Keep the
// ABI-compatible trampoline available for a future 3.0 ggml build without
// making the 2.2 headers (or CPU fallback) depend on the symbol.
extern "C" cl_mem clCreateBufferWithProperties(cl_context c, const intptr_t * props,
                                                 cl_mem_flags f, size_t s, void * h, cl_int * e) {
    using Fn = cl_mem (*)(cl_context, const intptr_t *, cl_mem_flags, size_t, void *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateBufferWithProperties");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(c, props, f, s, h, e);
}

extern "C" cl_mem clCreateSubBuffer(cl_mem b, cl_mem_flags f, cl_buffer_create_type t, const void * i, cl_int * e) {
    using Fn = cl_mem (*)(cl_mem, cl_mem_flags, cl_buffer_create_type, const void *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateSubBuffer");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(b, f, t, i, e);
}

extern "C" cl_mem clCreateImage(cl_context c, cl_mem_flags f, const cl_image_format * fmt,
                                 const cl_image_desc * desc, void * h, cl_int * e) {
    using Fn = cl_mem (*)(cl_context, cl_mem_flags, const cl_image_format *, const cl_image_desc *, void *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateImage");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(c, f, fmt, desc, h, e);
}

extern "C" cl_int clReleaseMemObject(cl_mem m) {
    using Fn = cl_int (*)(cl_mem); static Fn fn = opencl_symbol<Fn>("clReleaseMemObject");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(m);
}

extern "C" cl_program clCreateProgramWithSource(cl_context c, cl_uint n, const char ** s, const size_t * l, cl_int * e) {
    using Fn = cl_program (*)(cl_context, cl_uint, const char **, const size_t *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateProgramWithSource");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(c, n, s, l, e);
}

extern "C" cl_program clCreateProgramWithBinary(cl_context c, cl_uint n, const cl_device_id * d, const size_t * l,
                                                 const unsigned char ** b, cl_int * bs, cl_int * e) {
    using Fn = cl_program (*)(cl_context, cl_uint, const cl_device_id *, const size_t *, const unsigned char **, cl_int *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clCreateProgramWithBinary");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; }
    return fn(c, n, d, l, b, bs, e);
}

extern "C" cl_int clReleaseProgram(cl_program p) {
    using Fn = cl_int (*)(cl_program); static Fn fn = opencl_symbol<Fn>("clReleaseProgram");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(p);
}

extern "C" cl_int clBuildProgram(cl_program p, cl_uint n, const cl_device_id * d, const char * o,
                                  void (CL_CALLBACK * cb)(cl_program, void *), void * u) {
    using Fn = cl_int (*)(cl_program, cl_uint, const cl_device_id *, const char *, void (CL_CALLBACK *)(cl_program, void *), void *);
    static Fn fn = opencl_symbol<Fn>("clBuildProgram");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(p, n, d, o, cb, u);
}

extern "C" cl_int clGetProgramInfo(cl_program p, cl_program_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_program, cl_program_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetProgramInfo");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(p, i, s, v, r);
}

extern "C" cl_int clGetProgramBuildInfo(cl_program p, cl_device_id d, cl_program_build_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_program, cl_device_id, cl_program_build_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetProgramBuildInfo");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(p, d, i, s, v, r);
}

extern "C" cl_kernel clCreateKernel(cl_program p, const char * n, cl_int * e) {
    using Fn = cl_kernel (*)(cl_program, const char *, cl_int *); static Fn fn = opencl_symbol<Fn>("clCreateKernel");
    if (fn == nullptr) { if (e) *e = CL_INVALID_OPERATION; return nullptr; } return fn(p, n, e);
}

extern "C" cl_int clReleaseKernel(cl_kernel k) {
    using Fn = cl_int (*)(cl_kernel); static Fn fn = opencl_symbol<Fn>("clReleaseKernel");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(k);
}

extern "C" cl_int clSetKernelArg(cl_kernel k, cl_uint i, size_t s, const void * v) {
    using Fn = cl_int (*)(cl_kernel, cl_uint, size_t, const void *); static Fn fn = opencl_symbol<Fn>("clSetKernelArg");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(k, i, s, v);
}

extern "C" cl_int clGetKernelWorkGroupInfo(cl_kernel k, cl_device_id d, cl_kernel_work_group_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_kernel, cl_device_id, cl_kernel_work_group_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetKernelWorkGroupInfo"); return fn == nullptr ? CL_INVALID_OPERATION : fn(k, d, i, s, v, r);
}

extern "C" cl_int clGetKernelSubGroupInfo(cl_kernel k, cl_device_id d, cl_kernel_sub_group_info i, size_t s, const void * v, size_t rs, void * r, size_t * rr) {
    using Fn = cl_int (*)(cl_kernel, cl_device_id, cl_kernel_sub_group_info, size_t, const void *, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetKernelSubGroupInfo"); return fn == nullptr ? CL_INVALID_OPERATION : fn(k, d, i, s, v, rs, r, rr);
}

extern "C" cl_int clWaitForEvents(cl_uint n, const cl_event * e) {
    using Fn = cl_int (*)(cl_uint, const cl_event *); static Fn fn = opencl_symbol<Fn>("clWaitForEvents");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(n, e);
}

extern "C" cl_int clReleaseEvent(cl_event e) {
    using Fn = cl_int (*)(cl_event); static Fn fn = opencl_symbol<Fn>("clReleaseEvent");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(e);
}

extern "C" cl_int clGetEventProfilingInfo(cl_event e, cl_profiling_info i, size_t s, void * v, size_t * r) {
    using Fn = cl_int (*)(cl_event, cl_profiling_info, size_t, void *, size_t *);
    static Fn fn = opencl_symbol<Fn>("clGetEventProfilingInfo");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(e, i, s, v, r);
}

extern "C" cl_int clFlush(cl_command_queue q) {
    using Fn = cl_int (*)(cl_command_queue); static Fn fn = opencl_symbol<Fn>("clFlush");
    return fn == nullptr ? CL_INVALID_OPERATION : fn(q);
}

extern "C" cl_int clFinish(cl_command_queue q) {
    using Fn = cl_int (*)(cl_command_queue); static Fn fn = opencl_symbol<Fn>("clFinish");
    if (fn == nullptr) return CL_INVALID_OPERATION;
    // Only kernels already submitted when this synchronization began can be
    // credited. No evidence lock is held while the driver waits for the queue.
    const auto checkpoint = execution_evidence.checkpoint(q);
    const cl_int status = fn(q);
    execution_evidence.record_completion(q, checkpoint, status == CL_SUCCESS);
    return status;
}

extern "C" cl_int clEnqueueBarrierWithWaitList(cl_command_queue q, cl_uint n, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueBarrierWithWaitList"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, n, e, out);
}

extern "C" cl_int clEnqueueMarkerWithWaitList(cl_command_queue q, cl_uint n, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueMarkerWithWaitList"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, n, e, out);
}

extern "C" cl_int clEnqueueCopyBuffer(cl_command_queue q, cl_mem s, cl_mem d, size_t so, size_t doff, size_t n, cl_uint ne, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_mem, cl_mem, size_t, size_t, size_t, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueCopyBuffer"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, s, d, so, doff, n, ne, e, out);
}

extern "C" cl_int clEnqueueFillBuffer(cl_command_queue q, cl_mem b, const void * p, size_t ps, size_t off, size_t n, cl_uint ne, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_mem, const void *, size_t, size_t, size_t, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueFillBuffer"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, b, p, ps, off, n, ne, e, out);
}

extern "C" cl_int clEnqueueWriteBuffer(cl_command_queue q, cl_mem b, cl_bool block, size_t off, size_t n, const void * p, cl_uint ne, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void *, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueWriteBuffer"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, b, block, off, n, p, ne, e, out);
}

extern "C" cl_int clEnqueueReadBuffer(cl_command_queue q, cl_mem b, cl_bool block, size_t off, size_t n, void * p, cl_uint ne, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, void *, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueReadBuffer"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, b, block, off, n, p, ne, e, out);
}

extern "C" cl_int clEnqueueNDRangeKernel(cl_command_queue q, cl_kernel k, cl_uint dim, const size_t * go, const size_t * gs, const size_t * ls, cl_uint ne, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_kernel, cl_uint, const size_t *, const size_t *, const size_t *, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueNDRangeKernel");
    if (fn == nullptr) return CL_INVALID_OPERATION;
    const auto checkpoint = execution_evidence.checkpoint(q);
    const cl_int status = fn(q, k, dim, go, gs, ls, ne, e, out);
    execution_evidence.record_submission(q, checkpoint.scope, status == CL_SUCCESS);
    return status;
}

extern "C" void * clEnqueueMapBuffer(cl_command_queue q, cl_mem b, cl_bool block, cl_map_flags f, size_t off, size_t n, cl_uint ne, const cl_event * e, cl_event * out, cl_int * err) {
    using Fn = void * (*)(cl_command_queue, cl_mem, cl_bool, cl_map_flags, size_t, size_t, cl_uint, const cl_event *, cl_event *, cl_int *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueMapBuffer"); if (fn == nullptr) { if (err) *err = CL_INVALID_OPERATION; return nullptr; }
    return fn(q, b, block, f, off, n, ne, e, out, err);
}

extern "C" cl_int clEnqueueUnmapMemObject(cl_command_queue q, cl_mem b, void * p, cl_uint ne, const cl_event * e, cl_event * out) {
    using Fn = cl_int (*)(cl_command_queue, cl_mem, void *, cl_uint, const cl_event *, cl_event *);
    static Fn fn = opencl_symbol<Fn>("clEnqueueUnmapMemObject"); return fn == nullptr ? CL_INVALID_OPERATION : fn(q, b, p, ne, e, out);
}
