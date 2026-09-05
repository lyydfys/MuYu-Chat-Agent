#include <cstring>
#include <iostream>
#include <limits>
#include <set>
#include <stdexcept>
#include <MNN/expr/ExprCreator.hpp>
#include "half.hpp"
#include "sampler.hpp"
#include "../../main/cpp/mnn_sampling_config.hpp"

using namespace MNN;
using namespace MNN::Express;
using namespace MNN::Transformer;
using nlohmann::json;

static void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
static std::shared_ptr<LlmConfig> config(const std::string& source = R"({"sampler_type":"greedy","seed":7})") {
    auto result = std::make_shared<LlmConfig>();
    result->config_ = ujson::json::parse(source);
    return result;
}
static VARP floats(std::initializer_list<float> values) {
    return _Const(values.begin(), {1, static_cast<int>(values.size())}, NCHW);
}
static json stats(const std::shared_ptr<LlmContext>& context) {
    return json::parse(context->logits_diagnostics_json);
}

int main() {
    try {
        auto context = std::make_shared<LlmContext>();
        Sampler greedy(context, config());
        require(greedy.sample(floats({-2, 1, 7, 0})) == 2, "FP32 argmax");
        std::cout << "FP32 diagnostics: " << context->logits_diagnostics_json << '\n';
        require(stats(context)["min"] == -2 && stats(context)["max"] == 7, "real FP32 min/max");
        const auto first = context->first_logits_diagnostics_json;
        require(greedy.sample(floats({-2, 8, 7, 0})) == 1, "varying token stream");
        require(context->first_logits_diagnostics_json == first, "first logits stay fixed within request");
        context->first_logits_diagnostics_json.clear();
        require(greedy.sample(floats({9, 1, 7, 0})) == 0, "valid individual token zero remains allowed");
        require(json::parse(context->first_logits_diagnostics_json)["selectedToken"] == 0, "first logits reset");

        const half_float::half halfs[] = {half_float::half(-2.f), half_float::half(1.f), half_float::half(7.f), half_float::half(0.f)};
        require(greedy.sample(_Const(halfs, {1,4}, NCHW, halide_type_t(halide_type_float,16))) == 2, "FP16 argmax");
        require(stats(context)["min"] == -2 && stats(context)["max"] == 7, "real FP16 min/max");
        const uint16_t bfloats[] = {0xc000, 0x3f80, 0x40e0, 0x0000};
        require(greedy.sample(_Const(bfloats, {1,4}, NCHW, halide_type_t(halide_type_bfloat,16))) == 2, "BF16 argmax");
        require(stats(context)["min"] == -2 && stats(context)["max"] == 7, "real BF16 min/max");
        auto rows = floats({-1, 5, 2, 10, 3, 1});
        require(greedy.sample(rows, 0, 3) == 1, "offset zero with explicit size");
        require(greedy.sample(rows, 3, 3) == 0, "nonzero offset");
        require(greedy.sample(rows, 4, 3) == -1 && context->logits_error == "invalid_logits_range", "bounds fail closed");
        require(stats(context)["min"].is_null() && stats(context)["max"].is_null(), "unavailable stats are null");
        const int integers[] = {1, 5, 2};
        require(greedy.sample(_Const(integers, {1,3}, NCHW, halide_type_of<int>())) == -1 && context->logits_error == "unsupported_logits_dtype", "unknown dtype fail closed");
        require(greedy.sample(nullptr) == -1, "null tensor fail closed");
        require(greedy.sample(floats({0, -0.f, 0, 0})) == -1 && context->logits_error == "zero_logits", "all-zero logits reject token-zero collapse");
        require(stats(context)["zeroCount"] == 4 && stats(context)["min"] == 0 && stats(context)["max"] == 0, "real zeros remain zero");
        require(greedy.sample(floats({1, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()})) == -1, "NaN/Inf fail closed");
        require(context->logits_finite_count == 1 && context->logits_non_finite_count == 2 && !context->logits_all_finite, "NaN/Inf counts");

        auto stochasticConfig = config(R"({"sampler_type":"mixed","mixed_samplers":["topK","topP","min_p","temperature"],"temperature":0.7,"top_k":0,"top_p":1.0,"min_p":0.0,"seed":7})");
        auto c1 = std::make_shared<LlmContext>(), c2 = std::make_shared<LlmContext>();
        Sampler s1(c1, stochasticConfig), s2(c2, stochasticConfig);
        std::set<int> selected;
        for (int i = 0; i < 64; ++i) {
            int token = s1.sample(floats({1,1,1,1}));
            require(token >= 0 && token == s2.sample(floats({1,1,1,1})), "same seed repeats stochastic stream");
            selected.insert(token);
        }
        require(selected.size() > 1, "non-greedy stream is not fixed repetition");
        auto effective = json::parse(c1->sampler_config_json);
        require(effective["top_k"] == 0 && effective["min_p"] == 0 && effective["seed"] == 7, "effective sampler snapshot");
        Sampler emptyMixed(context, config(R"({"sampler_type":"mixed","mixed_samplers":[],"temperature":1,"seed":7})"));
        require(emptyMixed.sample(floats({1,2,3})) >= 0, "empty mixed pipeline");
        Sampler topK(context, config(R"({"sampler_type":"topK","top_k":1,"temperature":0.25,"seed":7})"));
        require(topK.sample(floats({1,2,3})) == 2 && json::parse(context->sampler_config_json)["temperature"] == 0.25, "topK honors temperature");
        Sampler invalidTemperature(context, config(R"({"sampler_type":"temperature","temperature":0,"seed":7})"));
        require(invalidTemperature.sample(floats({1,2,3})) == -1 && context->logits_error == "invalid_sampler_distribution", "invalid distribution cannot select token zero");
        Sampler allBanned(context, config(R"({"sampler_type":"mixed","mixed_samplers":["temperature"],"banned_tokens":[0,1,2],"temperature":1,"seed":7})"));
        require(allBanned.sample(floats({1,2,3})) == -1, "all banned tokens fail closed");

        json params = {{"temperature",0.7},{"top_k",40},{"top_p",0.9},{"min_p",0.05},{"seed",7}};
        json cpu = {{"backend_type","cpu"},{"precision","low"},{"use_cached_mmap",true},{"use_mmap",true},{"reuse_kv",true}};
        mca::mnn::applyCpuCacheSafety(cpu);
        mca::mnn::applySamplingConfig(cpu, params, json::object());
        require(cpu["precision"] == "high" && cpu["use_cached_mmap"] == false && cpu["reuse_kv"] == true && cpu["use_mmap"] == true, "CPU precision/cache safety");
        for (auto field : {"temperature","top_k","top_p","min_p","seed"}) require(cpu[field] == params[field], "all CPU sampler params transmitted");
        json opencl = {{"backend_type","opencl"},{"precision","low"},{"use_cached_mmap",true}};
        auto original = opencl;
        mca::mnn::applyCpuCacheSafety(opencl);
        require(opencl == original, "OpenCL policy unchanged");
        mca::mnn::applySamplingConfig(opencl, params, json::object());
        for (auto field : {"temperature","top_k","top_p","min_p","seed","sampler_type"}) require(cpu[field] == opencl[field], "CPU/OpenCL sampler parity");
        mca::mnn::applySamplingConfig(cpu, params, {{"topK",8},{"topP",0.8},{"minP",0.2},{"seed",11}});
        require(cpu["top_k"] == 8 && cpu["topK"] == 8 && cpu["top_p"] == 0.8 && cpu["min_p"] == 0.2 && cpu["seed"] == 11, "advanced aliases win");
        mca::mnn::applySamplingConfig(cpu, params, {{"temperature",0}});
        require(cpu["sampler_type"] == "greedy" && cpu["top_k"] == 1 && cpu["top_p"] == 1, "greedy config");
        for (const auto& bad : {json{{"top_k",1.5}}, json{{"min_p",2}}, json{{"temperature",-1}}, json{{"seed","7"}}}) {
            bool rejected = false;
            try { mca::mnn::applySamplingConfig(cpu, params, bad); } catch (const std::invalid_argument&) { rejected = true; }
            require(rejected, "invalid config fails closed");
        }
        std::cout << "PASS: real MNN tensor sampling, dtype/range/stats, seeded streams, CPU high, parameter mapping, OpenCL parity\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
