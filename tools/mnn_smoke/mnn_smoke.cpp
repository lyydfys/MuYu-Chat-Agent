#include "llm/llm.hpp"

#include <chrono>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>

using MNN::Transformer::Llm;

static double micros_to_ms(int64_t micros) {
    return static_cast<double>(micros) / 1000.0;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: mnn_smoke <config.json> <prompt> [max_new_tokens]\n";
        return 64;
    }

    const std::string config_path = argv[1];
    const std::string prompt = argv[2];
    const int max_new_tokens = argc >= 4 ? std::atoi(argv[3]) : 64;
    const bool disable_thinking = argc >= 5 && std::string(argv[4]) == "--disable-thinking";

    auto* llm = Llm::createLLM(config_path);
    if (llm == nullptr) {
        std::cerr << "CREATE_LLM_FAILED config=" << config_path << "\n";
        return 2;
    }

    const auto load_begin = std::chrono::steady_clock::now();
    const bool loaded = llm->load();
    const auto load_end = std::chrono::steady_clock::now();
    const auto load_wall_ms = std::chrono::duration_cast<std::chrono::milliseconds>(load_end - load_begin).count();
    if (!loaded) {
        std::cerr << "LOAD_FAILED config=" << config_path << " load_wall_ms=" << load_wall_ms << "\n";
        Llm::destroy(llm);
        return 3;
    }
    if (disable_thinking) {
        llm->set_config("{\"jinja\":{\"context\":{\"enable_thinking\":false}}}");
    }

    std::ostringstream response;
    const auto gen_begin = std::chrono::steady_clock::now();
    llm->response(prompt, &response, nullptr, max_new_tokens);
    const auto gen_end = std::chrono::steady_clock::now();

    const auto* ctx = llm->getContext();
    const auto gen_wall_ms = std::chrono::duration_cast<std::chrono::milliseconds>(gen_end - gen_begin).count();
    const double decode_seconds = ctx->decode_us > 0 ? static_cast<double>(ctx->decode_us) / 1000000.0 : 0.0;
    const double decode_tps = decode_seconds > 0.0 ? static_cast<double>(ctx->gen_seq_len) / decode_seconds : 0.0;
    const double prefill_seconds = ctx->prefill_us > 0 ? static_cast<double>(ctx->prefill_us) / 1000000.0 : 0.0;
    const double prefill_tps = prefill_seconds > 0.0 ? static_cast<double>(ctx->prompt_len) / prefill_seconds : 0.0;

    std::cout << "MNN_SMOKE_OK\n";
    std::cout << "CONFIG=" << config_path << "\n";
    std::cout << "LOAD_WALL_MS=" << load_wall_ms << "\n";
    std::cout << "GEN_WALL_MS=" << gen_wall_ms << "\n";
    std::cout << "PROMPT_TOKENS=" << ctx->prompt_len << "\n";
    std::cout << "DECODE_TOKENS=" << ctx->gen_seq_len << "\n";
    std::cout << "PREFILL_MS=" << micros_to_ms(ctx->prefill_us) << "\n";
    std::cout << "DECODE_MS=" << micros_to_ms(ctx->decode_us) << "\n";
    std::cout << "SAMPLE_MS=" << micros_to_ms(ctx->sample_us) << "\n";
    std::cout << "PREFILL_TPS=" << prefill_tps << "\n";
    std::cout << "DECODE_TPS=" << decode_tps << "\n";
    std::cout << "STATUS=" << static_cast<int>(ctx->status) << "\n";
    std::cout << "DISABLE_THINKING=" << (disable_thinking ? "true" : "false") << "\n";
    std::cout << "RESPONSE_BEGIN\n";
    std::cout << response.str() << "\n";
    std::cout << "RESPONSE_END\n";

    Llm::destroy(llm);
    return 0;
}
