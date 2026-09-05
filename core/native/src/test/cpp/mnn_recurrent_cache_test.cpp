#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/Module.hpp>
#include "core/OpCommonUtils.hpp"
#include "core/KVMeta.hpp"
#include "MNN_generated.h"
#include "prompt_cache_utils.hpp"
#include <cmath>
#include <iostream>
#include <numeric>
#include <stdexcept>

using namespace MNN;
using namespace MNN::Express;
static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static std::shared_ptr<Module> model(KVMeta& meta, bool attachMeta = true) {
    auto qkv = _Input(), gate = _Input(), beta = _Input(), weight = _Input();
    OpT op;
    op.type = OpType_LinearAttention;
    op.main.type = OpParameter_LinearAttentionParam;
    op.main.value = new LinearAttentionParamT;
    auto* params = op.main.AsLinearAttentionParam();
    params->attn_type = "gated_delta_rule";
    params->num_k_heads = params->num_v_heads = 2;
    params->head_k_dim = params->head_v_dim = 4;
    params->use_qk_l2norm = true;
    auto output = Variable::create(Expr::create(&op, {qkv, gate, beta, weight}));
    auto bytes = Variable::save({output});
    ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 1;
    BackendConfig backend;
    backend.precision = BackendConfig::Precision_High;
    config.backendConfig = &backend;
    auto runtime = std::shared_ptr<Executor::RuntimeManager>(Executor::RuntimeManager::createRuntimeManager(config));
    if (attachMeta) runtime->setHintPtr(Interpreter::KVCACHE_INFO, &meta);
    return std::shared_ptr<Module>(Module::load({}, {}, reinterpret_cast<uint8_t*>(bytes.data()), bytes.size(), runtime));
}

static std::vector<float> forward(const std::shared_ptr<Module>& module, KVMeta& meta, int length, int offset,
        bool capture = false) {
    auto qkv = _Input({1, 24, length}, NCHW, halide_type_of<float>());
    auto gate = _Input({1, length, 2}, NCHW, halide_type_of<float>());
    auto beta = _Input({1, length, 2}, NCHW, halide_type_of<float>());
    auto weight = _Input({24, 1, 4}, NCHW, halide_type_of<float>());
    auto* q = qkv->writeMap<float>();
    for (int channel = 0; channel < 24; ++channel)
        for (int token = 0; token < length; ++token)
            q[channel * length + token] = std::sin((channel + 1) * 0.13f + (token + offset) * 0.37f);
    auto* g = gate->writeMap<float>();
    auto* b = beta->writeMap<float>();
    for (int i = 0; i < length * 2; ++i) { g[i] = -0.1f; b[i] = 0.6f; }
    auto* w = weight->writeMap<float>();
    for (int i = 0; i < 24 * 4; ++i) w[i] = 0.1f * (1 + i % 4);
    meta.add = length;
    meta.captureRecurrentState = capture;
    if (capture) meta.recurrentSnapshotValid = true;
    auto result = module->onForward({qkv, gate, beta, weight});
    require(!result.empty(), "LinearAttention execution unavailable");
    const auto* data = result[0]->readMap<float>();
    require(data != nullptr, "LinearAttention output unavailable");
    std::vector<float> output(data, data + length * 8);
    meta.sync();
    if (capture) {
        require(meta.recurrentSnapshotValid, "snapshot allocation");
        meta.recurrentCheckpoint = meta.previous;
    }
    meta.captureRecurrentState = false;
    return output;
}

static void equal(const std::vector<float>& actual, const std::vector<float>& expected) {
    require(actual.size() == expected.size(), "output sizes");
    for (size_t i = 0; i < actual.size(); ++i) {
        if (!std::isfinite(actual[i]) || std::abs(actual[i] - expected[i]) > 0.0001f) {
            std::cerr << "index=" << i << " actual=" << actual[i] << " expected=" << expected[i] << '\n';
            throw std::runtime_error("recurrent rollback differs from fresh prefix");
        }
    }
}

int main() {
    try {
        using namespace MNN::Transformer;
        KVMeta live, fresh;
        auto a = model(live), b = model(fresh);
        forward(a, live, 4, 0, true);
        require(live.recurrentState && live.recurrentCheckpoint == 4, "actual CPU op registers checkpoint");
        forward(b, fresh, 4, 0);
        forward(a, live, 3, 4);
        auto decoder = std::shared_ptr<Module>(Module::clone(a.get()));
        forward(decoder, live, 1, 7);
        forward(decoder, live, 1, 8);
        require(recurrentReusablePrefix(7, live.previous, true, live.recurrentCheckpoint) == 4, "align partial rewind");
        live.remove = live.previous - 4;
        const auto resumed = forward(a, live, 3, 20);
        equal(resumed, forward(b, fresh, 3, 20));
        KVMeta noPrefix;
        const auto zeroState = forward(model(noPrefix), noPrefix, 3, 20);
        bool prefixMatters = false;
        for (size_t i = 0; i < resumed.size(); ++i)
            prefixMatters |= std::abs(resumed[i] - zeroState[i]) > 0.0001f;
        require(prefixMatters, "test must detect the old partial-rollback zero-state corruption");
        // Same shape on the next rewind must restore in onExecute, even if onResize is skipped.
        live.remove = live.previous - 4;
        fresh.remove = fresh.previous;
        forward(b, fresh, 4, 0);
        equal(forward(a, live, 3, 30), forward(b, fresh, 3, 30));
        live.remove = live.previous - 4;
        fresh.remove = fresh.previous;
        forward(b, fresh, 4, 0);
        equal(forward(a, live, 1, 40), forward(b, fresh, 1, 40));
        // Full reset must work for a single token as well as multi-token prefill.
        live.remove = live.previous;
        KVMeta cold;
        equal(forward(a, live, 1, 50), forward(model(cold), cold, 1, 50));
        require(recurrentReusablePrefix(3, 9, true, 4) == 0, "checkpoint after LCP forces cold prefill");
        require(recurrentReusablePrefix(7, 9, true, 0) == 0, "missing snapshot forces cold prefill");
        require(recurrentReusablePrefix(7, 9, false, 4) == 7, "nonrecurrent backend unchanged");
        require(recurrentReusablePrefix(9, 9, true, 4) == 9, "append-only keeps live state");
        KVMeta invalid;
        auto invalidModel = model(invalid);
        forward(invalidModel, invalid, 4, 0);
        invalid.remove = 1;
        bool rejected = false;
        try { forward(invalidModel, invalid, 3, 10); }
        catch (const std::runtime_error&) { rejected = true; }
        require(rejected, "partial rewind without an exact snapshot fails closed");
        KVMeta standaloneMeta, standaloneFreshMeta;
        auto standalone = model(standaloneMeta, false);
        forward(standalone, standaloneMeta, 4, 0);
        forward(standalone, standaloneMeta, 1, 4);
        equal(forward(standalone, standaloneMeta, 3, 20),
              forward(model(standaloneFreshMeta, false), standaloneFreshMeta, 3, 20));
        for (const size_t length : {size_t(33), size_t(95), size_t(128), size_t(3597)}) {
            const auto chunks = checkpointPrefillChunks(length, 128);
            require(std::accumulate(chunks.begin(), chunks.end(), size_t(0)) == length, "chunks cover prompt");
            require(chunks.back() == 32, "checkpoint leaves a 32-token replay tail");
        }
        std::cout << "PASS: CPU high recurrent checkpoint/fresh-prefix equivalence, repeated rewind, single-token reset\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
