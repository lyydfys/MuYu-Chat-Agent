// Compatibility symbols for the GenieX 0.3.12 llama.cpp plugin.

#include "chat.h"
#include "sampling.h"

#include "nlohmann/json.hpp"

#include <type_traits>

std::vector<common_chat_tool> common_chat_tools_parse_oaicompat(
        const nlohmann::ordered_json & tools);

namespace {

using geniex_sampler_init_fn = common_sampler * (*)(
        const llama_model *,
        common_params_sampling &);

// b10590 itself supplies the legacy two-argument sampler entry. Keep a
// compile-time ABI check here instead of defining the old three-argument
// forwarding shim again, which would duplicate the upstream symbol.
static_assert(
        std::is_same_v<
                decltype(static_cast<geniex_sampler_init_fn>(&common_sampler_init)),
                geniex_sampler_init_fn>,
        "GenieX requires the two-argument common_sampler_init ABI");

} // namespace

// b10590 moved the public chat helpers from nlohmann::ordered_json to the
// common_json wrapper. GenieX 0.3.12 still links this exact legacy overload.
// Convert at the boundary instead of mixing an older llama-common binary with
// the current llama/ggml ABI set.
std::vector<common_chat_tool> common_chat_tools_parse_oaicompat(
        const nlohmann::ordered_json & tools) {
    return common_chat_tools_parse_oaicompat(common_json::parse(tools.dump()));
}
