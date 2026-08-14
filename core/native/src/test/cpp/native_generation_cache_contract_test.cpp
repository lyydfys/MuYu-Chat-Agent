#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

namespace {

std::string read_text(const char *path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void require_contains(const std::string &source, const std::string &needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing native generation/cache contract: %s\n", needle.c_str());
        assert(false);
    }
}

void require_not_contains(const std::string &source, const std::string &needle) {
    if (source.find(needle) != std::string::npos) {
        std::fprintf(stderr, "unexpected native generation/cache contract: %s\n", needle.c_str());
        assert(false);
    }
}

void require_before(
        const std::string &source,
        const std::string &first,
        const std::string &second) {
    const size_t first_position = source.find(first);
    const size_t second_position = source.find(second);
    if (first_position == std::string::npos || second_position == std::string::npos ||
        first_position >= second_position) {
        std::fprintf(
                stderr,
                "native generation/cache ordering mismatch: first=%s second=%s\n",
                first.c_str(),
                second.c_str());
        assert(false);
    }
}

void require_sequence(
        const std::string &source,
        const std::string &first,
        const std::string &second,
        const std::string &third,
        const std::string &fourth = {}) {
    const size_t first_position = source.find(first);
    const size_t second_position = first_position == std::string::npos
            ? std::string::npos
            : source.find(second, first_position + first.size());
    const size_t third_position = second_position == std::string::npos
            ? std::string::npos
            : source.find(third, second_position + second.size());
    const size_t fourth_position = fourth.empty() || third_position == std::string::npos
            ? third_position
            : source.find(fourth, third_position + third.size());
    if (first_position == std::string::npos || second_position == std::string::npos ||
        third_position == std::string::npos || fourth_position == std::string::npos) {
        std::fprintf(
                stderr,
                "native generation/cache sequence missing: %s -> %s -> %s -> %s\n",
                first.c_str(),
                second.c_str(),
                third.c_str(),
                fourth.c_str());
        assert(false);
    }
}

std::string function_body(const std::string &source, const std::string &signature) {
    const size_t signature_position = source.find(signature);
    const size_t opening_brace = signature_position == std::string::npos
            ? std::string::npos
            : source.find('{', signature_position + signature.size());
    assert(signature_position != std::string::npos);
    assert(opening_brace != std::string::npos);
    int depth = 0;
    for (size_t position = opening_brace; position < source.size(); ++position) {
        if (source[position] == '{') {
            ++depth;
        } else if (source[position] == '}' && --depth == 0) {
            return source.substr(opening_brace, position - opening_brace + 1);
        }
    }
    assert(false);
    return {};
}

}  // namespace

int main(int argc, char **argv) {
    assert(argc == 2);
    const std::string source = read_text(argv[1]);

    const std::string from_java = function_body(source, "std::string jstring_to_string(");
    require_contains(from_java, "GetStringChars(value, nullptr)");
    require_contains(from_java, "mca::utf8::encode_from_utf16");
    require_not_contains(from_java, "GetStringUTFChars");

    const std::string to_java = function_body(source, "jstring string_to_jstring(");
    require_contains(to_java, "mca::utf8::decode_to_utf16(value, false)");
    require_contains(to_java, "env->NewString(data");
    require_not_contains(to_java, "NewStringUTF");

    const std::string prefill = function_body(source, "int decode_tokens(");
    require_sequence(
            prefill,
            "g_stop_requested.load(std::memory_order_acquire)",
            "llama_decode(g_context, g_batch)",
            "g_current_position += batch_size;",
            "return COMPLETION_STOPPED;");

    const std::string begin = function_body(
            source,
            "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion(");
    require_contains(begin, "g_stop_epoch.load(std::memory_order_acquire)");
    require_sequence(
            begin,
            "g_stop_requested.store(false, std::memory_order_release);",
            "g_stop_epoch.load(std::memory_order_acquire) != stop_epoch_at_entry",
            "g_stop_requested.store(true, std::memory_order_release);",
            "return COMPLETION_STOPPED;");
    require_contains(begin, "if (!mark_generation_running_unless_stopped())");
    require_contains(begin, "return COMPLETION_STOPPED;");
    require_not_contains(begin, "\n            mark_generation_running();");

    const std::string stop = function_body(
            source,
            "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_requestStop(");
    require_before(
            stop,
            "g_stop_requested.store(true, std::memory_order_release);",
            "g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);");
    require_before(
            stop,
            "g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);",
            "std::lock_guard<std::mutex> lock(g_mutex);");

    const std::string invalidate = function_body(
            source,
            "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_invalidateTextContext(");
    require_before(
            invalidate,
            "g_stop_requested.store(true, std::memory_order_release);",
            "g_stop_epoch.fetch_add(1, std::memory_order_acq_rel);");

    const std::string generate = function_body(
            source,
            "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_generateNextChunk(");
    const size_t ordinary_start = generate.find(
            "const llama_token token = common_sampler_sample(g_sampler, g_context, -1);");
    assert(ordinary_start != std::string::npos);
    const std::string ordinary = generate.substr(ordinary_start);
    require_sequence(
            ordinary,
            "common_sampler_sample(g_sampler, g_context, -1)",
            "g_stop_requested.load(std::memory_order_acquire)",
            "common_sampler_accept(g_sampler, token, true);",
            "g_stop_requested.load(std::memory_order_acquire)");
    require_sequence(
            ordinary,
            "const int decode_rc = llama_decode(g_context, g_batch);",
            "g_cached_token_chars += common_token_to_piece(g_context, token);",
            "g_stop_requested.load(std::memory_order_acquire)",
            "return nullptr;");

    const std::string save = function_body(source, "bool save_turn_cache_checkpoint_locked(");
    require_contains(
            save,
            "turn_cache_checkpoint_bytes(\n            checkpoint_size,\n            g_context_tokens.size())");
    require_contains(save, "MAX_TURN_CACHE_CHECKPOINT_BYTES - incoming_bytes");
    require_before(save, "g_turn_cache_checkpoints.erase", "std::vector<uint8_t> checkpoint_data");
    require_before(save, "g_turn_cache_checkpoints.erase", "llama_tokens checkpoint_prefix");
    require_contains(save, "llama_tokens checkpoint_prefix = g_context_tokens;");
    require_contains(save, "std::move(checkpoint_prefix)");
    require_not_contains(save, "const llama_tokens checkpoint_prefix = g_context_tokens;");
    require_sequence(
            save,
            "g_turn_cache_checkpoints.push_back",
            "g_stop_requested.load(std::memory_order_acquire)",
            "g_turn_cache_checkpoints.pop_back()");

    const std::string restore = function_body(
            source,
            "bool restore_turn_cache_checkpoint_locked(");
    require_contains(restore, "clear_target_context_locked(\"turn_checkpoint_restore_failed\")");
    require_contains(restore, "clear_target_context_locked(\"turn_checkpoint_suffix_trim_failed\")");
    require_contains(
            restore,
            "g_current_position != (llama_pos) g_context_tokens.size()");

    const std::string terminal = function_body(
            source,
            "void save_completed_turn_checkpoint_locked(");
    require_contains(terminal, "!g_cached_token_chars.empty()");
    require_contains(terminal, "g_stop_requested.load(std::memory_order_acquire)");
    require_sequence(
            ordinary,
            "const bool output_boundary_valid = is_valid_utf8(g_cached_token_chars.c_str());",
            "ready_output = g_cached_token_chars;",
            "save_completed_turn_checkpoint_locked(GenerationStopReason::MAX_NEW_TOKENS);",
            "return string_to_jstring(env, ready_output);");
    return 0;
}
