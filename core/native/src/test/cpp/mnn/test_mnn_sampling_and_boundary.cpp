#include "mca/mnn/mnn_sampling_strategy.hpp"
#include "mca/mnn/mnn_token_boundary_guard.hpp"
#include "mca/llm/llama_context_manager.hpp"

#include <cassert>
#include <algorithm>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

using namespace mca::mnn;
using namespace mca::llm;
using namespace mca::llama;

void test_sampling_strategy_parsing_and_ranges() {
    std::cout << "[Test] test_sampling_strategy_parsing_and_ranges..." << std::endl;

    // Default params
    nlohmann::json empty_params = nlohmann::json::object();
    auto defaults = parse_sampling_params(empty_params);
    assert(defaults.temperature == 0.6);
    assert(defaults.top_k == 20);
    assert(defaults.top_p == 0.95);
    assert(defaults.min_p == 0.0);
    assert(defaults.seed == -1);
    assert(defaults.repetition_penalty == 1.0);
    assert(defaults.presence_penalty == 0.0);
    assert(defaults.frequency_penalty == 0.0);
    assert(!is_greedy(defaults));
    assert(!has_penalties(defaults));

    // Greedy configuration (temperature = 0)
    nlohmann::json greedy_json = {{"temperature", 0.0}, {"top_k", 50}};
    auto greedy_params = parse_sampling_params(greedy_json);
    assert(is_greedy(greedy_params));
    assert(greedy_params.top_k == 1);
    assert(greedy_params.top_p == 1.0);
    assert(greedy_params.sampler_type == "greedy");

    // Alias resolution: topK, minP, repeat_penalty, presencePenalty, frequencyPenalty
    nlohmann::json alias_json = {
        {"temperature", 0.8},
        {"topK", 40},
        {"topP", 0.9},
        {"minP", 0.05},
        {"repeat_penalty", 1.15},
        {"presencePenalty", 0.2},
        {"frequencyPenalty", 0.1},
        {"seed", 42}
    };
    auto alias_params = parse_sampling_params(alias_json);
    assert(alias_params.temperature == 0.8);
    assert(alias_params.top_k == 40);
    assert(alias_params.top_p == 0.9);
    assert(alias_params.min_p == 0.05);
    assert(alias_params.repetition_penalty == 1.15);
    assert(alias_params.presence_penalty == 0.2);
    assert(alias_params.frequency_penalty == 0.1);
    assert(alias_params.seed == 42);
    assert(has_penalties(alias_params));

    // Stage resolution with penalties
    auto stages = resolve_mixed_samplers(alias_params);
    assert(std::find(stages.begin(), stages.end(), "penalty") != stages.end());

    // Apply onto config JSON
    nlohmann::json config = {{"backend_type", "cpu"}};
    applyCpuCacheSafety(config);
    assert(config["precision"] == "high");
    assert(config["use_cached_mmap"] == false);

    applySamplingConfig(config, alias_json, nlohmann::json::object());
    assert(config["temperature"] == 0.8);
    assert(config["top_k"] == 40);
    assert(config["top_p"] == 0.9);
    assert(config["min_p"] == 0.05);
    assert(config["seed"] == 42);
    assert(config["sampler_type"] == "mixed");

    // Invalid range should throw std::invalid_argument
    bool caught = false;
    try {
        nlohmann::json invalid_json = {{"temperature", 150.0}};
        parse_sampling_params(invalid_json);
    } catch (const std::invalid_argument&) {
        caught = true;
    }
    assert(caught);

    std::cout << "  -> test_sampling_strategy_parsing_and_ranges PASSED" << std::endl;
}

void test_token_boundary_guard_utf8_protection() {
    std::cout << "[Test] test_token_boundary_guard_utf8_protection..." << std::endl;

    MnnTokenBoundaryGuard guard;
    assert(!guard.has_pending());

    // Feed ASCII
    guard.feed_chunk("Hello, ");
    assert(guard.has_pending());
    auto r1 = guard.take_pending_utf16(false);
    assert(r1.has_content);
    assert(guard.pending_tail().empty());

    // Chinese character: "你" is 3 bytes (0xE4, 0xBD, 0xA0)
    // Chunk 1: first 2 bytes
    guard.feed_chunk(std::string("\xE4\xBD"));
    auto r2 = guard.take_pending_utf16(false);
    // Incomplete, must not emit
    assert(!r2.has_content);
    assert(guard.pending_tail() == std::string("\xE4\xBD"));

    // Chunk 2: 3rd byte of "你" + "好" (0xE5, 0xA5, 0xBD)
    guard.feed_chunk(std::string("\xA0\xE5\xA5\xBD"));
    auto r3 = guard.take_pending_utf16(false);
    assert(r3.has_content);
    assert(guard.pending_tail().empty());

    // Emoji: "🎉" is 4 bytes (0xF0, 0x9F, 0x8E, 0x89)
    guard.feed_chunk(std::string("\xF0\x9F"));
    auto r4 = guard.take_pending_utf16(false);
    assert(!r4.has_content);
    assert(guard.pending_tail() == std::string("\xF0\x9F"));

    guard.feed_chunk(std::string("\x8E\x89!"));
    auto r5 = guard.take_pending_utf16(false);
    assert(r5.has_content);
    assert(guard.pending_tail().empty());

    // Transactional rollback test:
    // If staging succeeds but downstream fails, rollback must preserve buffer!
    guard.reset();
    guard.feed_chunk("Important Token");
    std::vector<uint16_t> staged;
    bool has_staged = guard.stage_pending_utf16(false, staged);
    assert(has_staged);
    // Simulate downstream failure -> rollback
    guard.rollback_staged();
    assert(guard.has_pending());
    assert(guard.pending_chunk() == "Important Token");

    // Retry downstream -> commit
    bool retry = guard.stage_pending_utf16(false, staged);
    assert(retry);
    guard.commit_staged();
    assert(!guard.has_pending());

    std::cout << "  -> test_token_boundary_guard_utf8_protection PASSED" << std::endl;
}

void test_llama_context_manager_prefix_reuse() {
    std::cout << "[Test] test_llama_context_manager_prefix_reuse..." << std::endl;

    llama_pos current_pos = 10;
    llama_tokens context_tokens = {101, 202, 303, 404, 505, 606, 707, 808, 909, 1000};
    bool cache_state_valid = true;
    bool cache_reuse_hit = false;
    int cache_reused_tokens = 0;
    long long cache_reuse_hits = 0;
    long long cache_reuse_misses = 0;
    std::string cache_reuse_reason;

    std::string cleared_reason;
    llama_pos trimmed_pos = -1;

    LlamaContextEnvironment env;
    env.current_position = &current_pos;
    env.context_tokens = &context_tokens;
    env.cache_reuse_threshold = 4;
    env.strategy = PrefixCacheStrategy::DirectTrim;
    env.cache_state_valid = &cache_state_valid;
    env.cache_reuse_hit = &cache_reuse_hit;
    env.cache_reused_tokens = &cache_reused_tokens;
    env.cache_reuse_hits = &cache_reuse_hits;
    env.cache_reuse_misses = &cache_reuse_misses;
    env.cache_reuse_reason = &cache_reuse_reason;
    env.clear_target_context = [&](const std::string& r) { cleared_reason = r; };
    env.trim_context = [&](llama_pos pos, const char*) { trimmed_pos = pos; return true; };

    // Case 1: Disabled strategy
    env.strategy = PrefixCacheStrategy::Disabled;
    size_t reused = LlamaContextManager::prepare_text_prefix({101, 202, 303, 404}, env);
    assert(reused == 0);
    assert(cleared_reason == "disabled");

    // Case 2: DirectTrim with matching prefix above threshold
    env.strategy = PrefixCacheStrategy::DirectTrim;
    cleared_reason.clear();
    trimmed_pos = -1;
    // Input shares first 6 tokens {101, 202, 303, 404, 505, 606}, then diverges
    llama_tokens prompt = {101, 202, 303, 404, 505, 606, 9999, 8888};
    reused = LlamaContextManager::prepare_text_prefix(prompt, env);
    assert(reused == 6);
    assert(cache_reuse_hit);
    assert(cache_reused_tokens == 6);
    assert(cache_reuse_reason == "text_prefix_hit");
    assert(trimmed_pos == 6);
    assert(current_pos == 6);
    assert(context_tokens.size() == 6);

    // Case 3: Prompt exactly identical to context: must reserve at least 1 token for new logits
    prompt = {101, 202, 303, 404, 505, 606};
    reused = LlamaContextManager::prepare_text_prefix(prompt, env);
    assert(reused == 5); // 6 - 1 = 5
    assert(current_pos == 5);

    // Case 4: Match below threshold -> misses
    prompt = {101, 202, 111, 222}; // only 2 match, threshold is 4
    reused = LlamaContextManager::prepare_text_prefix(prompt, env);
    assert(reused == 0);
    assert(cache_reuse_misses == 1);
    assert(cleared_reason == "common_prefix_below_threshold");

    std::cout << "  -> test_llama_context_manager_prefix_reuse PASSED" << std::endl;
}

int main() {
    std::cout << "==================================================" << std::endl;
    std::cout << "  MCA MNN Sampling & Boundary Guard Unit Tests    " << std::endl;
    std::cout << "==================================================" << std::endl;

    test_sampling_strategy_parsing_and_ranges();
    test_token_boundary_guard_utf8_protection();
    test_llama_context_manager_prefix_reuse();

    std::cout << "==================================================" << std::endl;
    std::cout << "  ALL SAMPLING & BOUNDARY TESTS PASSED!           " << std::endl;
    std::cout << "==================================================" << std::endl;
    return 0;
}
