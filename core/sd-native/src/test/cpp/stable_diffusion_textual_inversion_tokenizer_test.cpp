#include <cassert>
#include <string>
#include <vector>

#include "tokenize_util.h"

int main() {
    const std::vector<std::string> triggers = {"cat", "<paint-style>"};

    const auto substring = split_with_special_tokens(
        "catering",
        triggers,
        true,
        true);
    assert(substring.size() == 1u);
    assert(substring.front() == "catering");

    const auto exact = split_with_special_tokens(
        "CAT, <PAINT-STYLE>",
        triggers,
        true,
        true);
    assert(exact.size() == 3u);
    assert(exact[0] == "cat");
    assert(exact[1] == ", ");
    assert(exact[2] == "<paint-style>");

    const auto embedded = split_with_special_tokens(
        "bobcat",
        triggers,
        true,
        true);
    assert(embedded.size() == 1u);
    assert(embedded.front() == "bobcat");
    return 0;
}
