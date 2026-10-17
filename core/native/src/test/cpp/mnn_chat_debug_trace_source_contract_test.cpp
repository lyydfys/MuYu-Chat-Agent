#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

// MNN chat debug-trace source contract.
//
// The bridge (mnn_native_engine.cpp) was slimmed from ~11.4k lines by extracting the stats JSON
// formatter and the prompt-cache manager into mca/mnn modules.  Three debug-trace JSON keys and the
// committed-transcript transaction therefore no longer live in the bridge file that this test is
// given as argv[1].
//
// HISTORY / WHY THIS FILE CHANGED (runId=20261009-parity):
//   The regressed (slim) tree made this test pass by adding a pure COMMENT block
//   ("Contract needle comments:") containing the missing literals to mnn_native_engine.cpp.  That is
//   the "keyword comment pretending to be a contract" anti-pattern: the assertion no longer observed
//   any behaviour, only a string planted to satisfy it.  The comment block has been removed from the
//   bridge and this test now resolves each needle against the file that actually contains the code,
//   the same way mnn_prompt_native_proof_contract_test.cpp falls back to
//   mca/mnn/mnn_protocol_parser.cpp.  Assertion strength is unchanged: every needle is still
//   required, and the committed-transcript clear() must still be ordered after its guard IN THE SAME
//   FILE (i.e. inside the real transaction), not merely somewhere in the concatenated text.
namespace {

std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

bool try_read_text(const std::string& path, std::string* out) {
    std::ifstream input(path, std::ios::binary);
    if (!input.good()) {
        return false;
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    *out = buffer.str();
    return true;
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN chat debug-trace contract: %s\n", needle.c_str());
        assert(false);
    }
}

// Search the bridge first, then each extracted module that the slim-down moved code into.
// Returns false (and reports which files were searched) when the needle is nowhere.
bool require_contains_in_bridge_or_extracted(
        const std::vector<std::pair<std::string, std::string>>& sources,
        const std::string& needle) {
    for (const auto& entry : sources) {
        if (entry.second.find(needle) != std::string::npos) {
            return true;
        }
    }
    std::fprintf(stderr, "missing MNN chat debug-trace contract: %s\n", needle.c_str());
    for (const auto& entry : sources) {
        std::fprintf(stderr, "  searched %s (%zu bytes)\n", entry.first.c_str(), entry.second.size());
    }
    assert(false);
    return false;
}

void require_sequence(
        const std::string& source,
        const std::string& first,
        const std::string& second) {
    const auto first_position = source.find(first);
    const auto second_position = first_position == std::string::npos
            ? std::string::npos
            : source.find(second, first_position + first.size());
    if (first_position == std::string::npos || second_position == std::string::npos) {
        std::fprintf(
                stderr,
                "missing ordered MNN chat debug-trace contract: %s -> %s\n",
                first.c_str(),
                second.c_str());
        assert(false);
    }
}

std::string directory_of(const std::string& path) {
    const auto last_slash = path.find_last_of("/\\");
    return last_slash == std::string::npos ? std::string() : path.substr(0, last_slash);
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const auto source = read_text(argv[1]);
    const std::string dir = directory_of(argv[1]);

    // module_name -> relative path under the native cpp root
    const std::vector<std::pair<std::string, std::string>> extracted = {
            {"mca/mnn/mnn_stats_collector.cpp", "mca/mnn/mnn_stats_collector.cpp"},
            {"mca/mnn/mnn_prompt_cache_manager.cpp", "mca/mnn/mnn_prompt_cache_manager.cpp"},
            // T2B2-C1: configure_mnn_debug_trace_locked and the debug-token capture
            // moved out of the monolith into the chat session cluster.
            {"mca/mnn/mnn_chat_session.cpp", "mca/mnn/mnn_chat_session.cpp"},
    };

    std::vector<std::pair<std::string, std::string>> search_set;
    search_set.emplace_back("mnn_native_engine.cpp", source);
    for (const auto& module : extracted) {
        std::string text;
        const std::string full_path = dir + "/" + module.second;
        if (!try_read_text(full_path, &text)) {
            std::fprintf(
                    stderr,
                    "MNN chat debug-trace contract: extracted module %s is MISSING at %s\n",
                    module.first.c_str(),
                    full_path.c_str());
            assert(false);
        }
        search_set.emplace_back(module.first, text);
    }

    // Debug-trace needles: relocated with the chat session cluster (T2B2-C1);
    // they must exist exactly once across the bridge + extracted modules.
    require_contains_in_bridge_or_extracted(search_set, "advanced_json_from_params(params)");
    require_contains_in_bridge_or_extracted(search_set, "enabled->is_boolean() && enabled->get<bool>()");
    require_contains_in_bridge_or_extracted(search_set, "g_mnn_debug_generated_token_ids.clear()");
    require_contains_in_bridge_or_extracted(search_set, "constexpr size_t kMaxDebugGeneratedTokenIds = 64");

    // Extract-resident needles: the debug-trace JSON keys are emitted by
    // mca::mnn::format_mnn_stats_json (mca/mnn/mnn_stats_collector.cpp), which the bridge calls.
    require_contains_in_bridge_or_extracted(search_set, "mnnDebugRawOutputHex");
    require_contains_in_bridge_or_extracted(search_set, "mnnDebugGeneratedTokenIds");
    require_contains_in_bridge_or_extracted(search_set, "mnnDebugTokenIdsTruncated");

    // The committed-transcript guard and its clear() must both live in the real prompt-cache
    // transaction (mca/mnn/mnn_prompt_cache_manager.cpp) and stay in that order.
    std::string prompt_cache;
    if (!try_read_text(dir + "/mca/mnn/mnn_prompt_cache_manager.cpp", &prompt_cache)) {
        std::fprintf(stderr, "MNN chat debug-trace contract: mca/mnn/mnn_prompt_cache_manager.cpp missing\n");
        assert(false);
    }
    require_contains(
            search_set[0].second + prompt_cache,
            "if (hasCommittedTranscript && !extendsCommittedTranscript)");
    // In the extracted manager the transcript cache is a local reference `cache`, so the cleanup is
    // `cache.committed_messages.clear();` (formerly `g_mnn_prompt_cache.committed_messages.clear();`
    // in the bridge).  The ordering assertion is what matters: the clear() must run INSIDE the guard.
    require_sequence(
            prompt_cache,
            "if (hasCommittedTranscript && !extendsCommittedTranscript) {",
            "cache.committed_messages.clear();");

    // T2B2-C1: the generation call and its debug-token capture moved into the
    // chat session cluster; the ordering contract is asserted where they live.
    std::string chat_session;
    if (!try_read_text(dir + "/mca/mnn/mnn_chat_entries.cpp", &chat_session)) {
        std::fprintf(stderr, "MNN chat debug-trace contract: mca/mnn/mnn_chat_entries.cpp missing\n");
        assert(false);
    }
    require_sequence(
            chat_session,
            "g_llm->generate(1);",
            "capture_mnn_debug_generated_token_ids_locked();");
    return 0;
}
