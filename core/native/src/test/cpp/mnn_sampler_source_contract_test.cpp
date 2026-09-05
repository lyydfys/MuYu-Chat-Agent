#include <cassert>
#include <fstream>
#include <string>

int main(int argc, char** argv) {
    assert(argc == 3);
    auto read = [](const char* path) {
        std::ifstream in(path);
        return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    };
    const auto native = read(argv[1]);
    const auto sampler = read(argv[2]);
    // CPU keeps the high-precision default while OpenCL remains low precision.
    assert(native.find("config[\"precision\"] = backendType == \"cpu\" ? \"high\" : \"low\";") != std::string::npos);
    // Wiring contract only; values and behavior run in mnn_sampler_numeric_test.
    assert(native.find("mca::mnn::applySamplingConfig(config, params, advanced);") != std::string::npos);
    assert(native.find("mca::mnn::applyCpuCacheSafety(config);") != std::string::npos);
    // Logits are converted according to their tensor dtype instead of being
    // reinterpreted as float32.
    assert(sampler.find("readMap<void>()") != std::string::npos);
    assert(sampler.find("half_float::half") != std::string::npos);
    assert(sampler.find("halide_type_bfloat") != std::string::npos);
    assert(sampler.find("std::memcpy(&state.logits[i], &bits, sizeof(bits))") != std::string::npos);
    assert(sampler.find("state.logits.clear()") != std::string::npos);
    return 0;
}
