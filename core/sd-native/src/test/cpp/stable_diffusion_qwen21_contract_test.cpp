#include <cassert>
#include <cmath>
#include <cstdio>
#include "qwen_image_2_1.hpp"
#include "denoiser.hpp"
#include "name_conversion.h"

int main() {
    auto layout = Qwen::QwenImage21Layout::build(3, {}, {{2, 2}});
    assert(layout.prefix_length == 3 && layout.positions.size() == 7);
    assert(layout.segments.size() == 2);
    assert(layout.segments[0].image_index == -1);
    assert(layout.segments[1].start == 3 && layout.segments[1].end == 7);
    assert((layout.positions[3] == std::vector<float>{3.f, -1.f, -1.f}));
    bool rejected = false;
    try { Qwen::QwenImage21Layout::build(3, {}, {{2, 2}, {2, 2}}); }
    catch (const std::runtime_error&) { rejected = true; }
    assert(rejected);
    FluxScheduler low(256), high(4096);
    assert(std::fabs(low.compute_mu() - 0.5f) < 1e-6f);
    assert(std::fabs(high.compute_mu() - 1.15f) < 1e-6f);
    auto a = low.get_sigmas(20, 0.f, 1.f, {});
    auto b = high.get_sigmas(20, 0.f, 1.f, {});
    assert(a.size() == 21 && a.front() == 1.f && a.back() == 0.f);
    assert(std::fabs(a[10] - std::exp(0.5f) / (std::exp(0.5f) + 1.f)) < 1e-6f);
    for (size_t i = 1; i < a.size(); ++i) {
        assert(std::isfinite(a[i]) && a[i] < a[i-1]);
        if (i < 20) assert(b[i] > a[i]);
    }
    const auto v = VERSION_QWEN_IMAGE_2_1;
    assert(convert_tensor_name("text_encoders.llm.blk.0.attn_q_norm.weight", v) == "text_encoders.llm.model.layers.0.self_attn.q_norm.weight");
    assert(convert_tensor_name("first_stage_model.decoder.up_blocks.4.resnets.2.conv2.weight", v) == "first_stage_model.decoder.upsamples.4.upsamples.2.residual.6.weight");
    assert(convert_tensor_name("first_stage_model.encoder.down_blocks.1.resnets.0.conv_shortcut.weight", v) == "first_stage_model.encoder.downsamples.1.downsamples.0.shortcut.weight");
    assert(convert_tensor_name("first_stage_model.decoder.conv_in.weight", v) == "first_stage_model.decoder.conv1.weight");
    assert(convert_tensor_name("first_stage_model.post_quant_conv.weight", v) == "first_stage_model.conv2.weight");
    assert(sd_version_is_qwen_image(v) && sd_version_is_dit(v));
    std::puts("Qwen2.1 layout, resolution schedule and tensor mappings passed");
}