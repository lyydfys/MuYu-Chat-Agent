#include <llama.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr int kSkipNoModel = 77;
constexpr int kGeneratedTokens = 12;

struct ContextDeleter {
    void operator()(llama_context * context) const {
        if (context != nullptr) {
            llama_free(context);
        }
    }
};

struct ModelDeleter {
    void operator()(llama_model * model) const {
        if (model != nullptr) {
            llama_model_free(model);
        }
    }
};

struct SamplerDeleter {
    void operator()(llama_sampler * sampler) const {
        if (sampler != nullptr) {
            llama_sampler_free(sampler);
        }
    }
};

struct BackendGuard {
    BackendGuard() {
        llama_backend_init();
    }

    ~BackendGuard() {
        llama_backend_free();
    }
};

using Context = std::unique_ptr<llama_context, ContextDeleter>;
using Model = std::unique_ptr<llama_model, ModelDeleter>;
using Sampler = std::unique_ptr<llama_sampler, SamplerDeleter>;

bool decode_one(llama_context * context, llama_token token, int position) {
    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.n_tokens = 1;
    batch.token[0] = token;
    batch.pos[0] = position;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = true;
    const int result = llama_decode(context, batch);
    llama_batch_free(batch);
    return result == 0;
}

bool decode_tokens(
        llama_context * context,
        const std::vector<llama_token> & tokens,
        std::size_t first,
        std::size_t last) {
    for (std::size_t index = first; index < last; ++index) {
        if (!decode_one(context, tokens[index], static_cast<int>(index))) {
            std::fprintf(stderr, "llama_decode failed at prompt token %zu\n", index);
            return false;
        }
    }
    return true;
}

std::vector<llama_token> tokenize(const llama_model * model, const std::string & prompt) {
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int32_t count = llama_tokenize(
            vocab,
            prompt.data(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            true,
            true);
    if (count >= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<std::size_t>(-count));
    const int32_t written = llama_tokenize(
            vocab,
            prompt.data(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true);
    if (written != static_cast<int32_t>(tokens.size())) {
        return {};
    }
    return tokens;
}

std::vector<llama_token> generate_greedy(
        llama_context * context,
        const llama_vocab * vocab,
        int first_position) {
    Sampler sampler(llama_sampler_init_greedy());
    if (!sampler) {
        return {};
    }
    std::vector<llama_token> result;
    int position = first_position;
    for (int step = 0; step < kGeneratedTokens; ++step) {
        const llama_token token = llama_sampler_sample(sampler.get(), context, -1);
        result.push_back(token);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        if (!decode_one(context, token, position++)) {
            return {};
        }
    }
    return result;
}

std::string model_argument(int argc, char ** argv) {
    for (int index = 1; index + 1 < argc; ++index) {
        if (std::string(argv[index]) == "--model") {
            return argv[index + 1];
        }
    }
    return {};
}

}  // namespace

int main(int argc, char ** argv) {
    const std::string model_path = model_argument(argc, argv);
    if (model_path.empty() || !std::filesystem::is_regular_file(model_path)) {
        std::fprintf(
                stderr,
                "SKIP: pass --model <real GGUF> to run the native prefix-state equivalence test\n");
        return kSkipNoModel;
    }

    // Declare this before model/context owners so llama.cpp is released last.
    BackendGuard backend;

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    Model model(llama_model_load_from_file(model_path.c_str(), model_params));
    if (!model) {
        std::fprintf(stderr, "Unable to load test model: %s\n", model_path.c_str());
        return 1;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 512;
    context_params.n_batch = 128;
    context_params.n_ubatch = 128;
    context_params.n_seq_max = 1;
    context_params.n_threads = 1;
    context_params.n_threads_batch = 1;

    const std::string prompt =
            "System: You are a concise assistant with a stable persona.\n"
            "User: Continue this deterministic cache verification sentence.";
    const std::vector<llama_token> prompt_tokens = tokenize(model.get(), prompt);
    if (prompt_tokens.size() < 4) {
        std::fprintf(stderr, "Test prompt did not tokenize into a usable prefix.\n");
        return 1;
    }
    const std::size_t prefix_count = std::max<std::size_t>(1, prompt_tokens.size() / 2);

    Context cold_context(llama_init_from_model(model.get(), context_params));
    if (!cold_context || !decode_tokens(cold_context.get(), prompt_tokens, 0, prefix_count)) {
        std::fprintf(stderr, "Unable to prefill the cold fixed prefix.\n");
        return 1;
    }

    const std::filesystem::path state_path = std::filesystem::temp_directory_path() /
            ("mca-prefix-cache-" +
             std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) +
             ".state");
    const std::size_t saved = llama_state_seq_save_file(
            cold_context.get(),
            state_path.string().c_str(),
            0,
            prompt_tokens.data(),
            prefix_count);
    if (saved == 0) {
        std::fprintf(stderr, "llama_state_seq_save_file did not write a prefix state.\n");
        return 1;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model.get());
    if (!decode_tokens(cold_context.get(), prompt_tokens, prefix_count, prompt_tokens.size())) {
        std::filesystem::remove(state_path);
        return 1;
    }
    const std::vector<llama_token> cold_output = generate_greedy(
            cold_context.get(), vocab, static_cast<int>(prompt_tokens.size()));

    Context restored_context(llama_init_from_model(model.get(), context_params));
    if (!restored_context) {
        std::fprintf(stderr, "Unable to create the restored context.\n");
        std::filesystem::remove(state_path);
        return 1;
    }
    std::vector<llama_token> restored_tokens(prefix_count);
    std::size_t restored_count = 0;
    const std::size_t loaded = llama_state_seq_load_file(
            restored_context.get(),
            state_path.string().c_str(),
            0,
            restored_tokens.data(),
            restored_tokens.size(),
            &restored_count);
    std::filesystem::remove(state_path);
    if (loaded == 0 || restored_count != prefix_count ||
        !std::equal(restored_tokens.begin(), restored_tokens.end(), prompt_tokens.begin())) {
        std::fprintf(stderr, "Restored prefix tokens do not match the cold prefix.\n");
        return 1;
    }
    if (!decode_tokens(restored_context.get(), prompt_tokens, prefix_count, prompt_tokens.size())) {
        return 1;
    }
    const std::vector<llama_token> restored_output = generate_greedy(
            restored_context.get(), vocab, static_cast<int>(prompt_tokens.size()));

    const bool equivalent = !cold_output.empty() && cold_output == restored_output;
    std::fprintf(
            stderr,
            "persistent-prefix native equivalence: %s (saved=%zu restored=%zu generated=%zu)\n",
            equivalent ? "PASS" : "FAIL",
            saved,
            restored_count,
            cold_output.size());
    return equivalent ? 0 : 1;
}
