#include <cstdio>

#include "diffusion/diffusion.hpp"

using MNN::DIFFUSION::resolveRuntimeManagerBackendInfo;

int main() {
    MNNForwardType backend = MNN_FORWARD_CPU;

    // MNN RuntimeManager::getInfo(BACKENDS) can write a valid runtime while
    // returning false. The written value, not that status bit, is authoritative.
    if (!resolveRuntimeManagerBackendInfo(false, MNN_FORWARD_OPENCL, &backend) ||
        backend != MNN_FORWARD_OPENCL) {
        std::fprintf(stderr, "false getInfo status discarded a valid OpenCL backend\n");
        return 1;
    }

    if (!resolveRuntimeManagerBackendInfo(true, MNN_FORWARD_CPU, &backend) ||
        backend != MNN_FORWARD_CPU) {
        std::fprintf(stderr, "valid CPU backend was not accepted\n");
        return 1;
    }

    backend = MNN_FORWARD_OPENCL;
    if (resolveRuntimeManagerBackendInfo(false, -1, &backend) ||
        backend != MNN_FORWARD_OPENCL) {
        std::fprintf(stderr, "unresolved backend was accepted or mutated the output\n");
        return 1;
    }

    if (resolveRuntimeManagerBackendInfo(false, MNN_FORWARD_OPENCL, nullptr)) {
        std::fprintf(stderr, "null backend output pointer was accepted\n");
        return 1;
    }

    std::puts("qwen_mnn_backend_resolution passed");
    return 0;
}
