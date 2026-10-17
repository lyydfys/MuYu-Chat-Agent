// mca/qnn/qnn_conditioning_json_fields.cpp -- reusable required-field JSON parsers.
// (T2B16 / R7-C5 split).  Relocated byte-exact from mca/qnn/qnn_conditioning_pipeline.cpp (lines 235-374).

#include "mca/qnn/qnn_conditioning_pipeline.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "image_conditioning.hpp"
#include "native_prompt_language_contract.hpp"
// image_pixel_range_wire_name() is used by qnn_native_effective_json(); its declaration lives in the
// root QNN contract header, which this TU did not previously need to include.
#include "qnn_image_pixel_range.hpp"
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"

#if defined(_WIN32)
#include <io.h>
#include <direct.h>
#include <stdlib.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define realpath(N, R) _fullpath((R), (N), PATH_MAX)
#define lstat(P, S) stat((P), (S))
#ifndef S_ISREG
#define S_ISREG(m) (((m) & S_IFMT) == S_IFREG)
#endif
#else
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits.h>
#endif

namespace mca {
namespace qnn {

std::string normalized_contract_enum(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        if (c == '-' || c == ' ' || c == '.') return '_';
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool qnn_json_value_start(
        const std::string& json,
        const std::string& key,
        size_t* value_start,
        std::string* error) {
    const auto key_position = json.find("\"" + key + "\"");
    if (key_position == std::string::npos) {
        *error = "Missing required native execution field '" + key + "'.";
        return false;
    }
    const auto colon = json.find(':', key_position + key.size() + 2u);
    if (colon == std::string::npos) {
        *error = "Native execution field '" + key + "' has no value.";
        return false;
    }
    const auto start = json.find_first_not_of(" \t\r\n", colon + 1u);
    if (start == std::string::npos) {
        *error = "Native execution field '" + key + "' has no value.";
        return false;
    }
    *value_start = start;
    return true;
}

bool qnn_json_token_ends_at(const std::string& json, size_t end) {
    const auto next = json.find_first_not_of(" \t\r\n", end);
    return next == std::string::npos || json[next] == ',' || json[next] == '}' || json[next] == ']';
}

bool qnn_required_string_field(
        const std::string& json,
        const std::string& key,
        std::string* value,
        std::string* error,
        bool allow_empty) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    if (json[start] != '"') {
        *error = "Native execution field '" + key + "' must be a string.";
        return false;
    }
    value->clear();
    bool escaped = false;
    for (size_t index = start + 1u; index < json.size(); ++index) {
        const char c = json[index];
        if (escaped) {
            switch (c) {
                case 'n': value->push_back('\n'); break;
                case 'r': value->push_back('\r'); break;
                case 't': value->push_back('\t'); break;
                default: value->push_back(c); break;
            }
            escaped = false;
            continue;
        }
        if (c == '\\') {
            escaped = true;
            continue;
        }
        if (c == '"') {
            if (value->empty() && !allow_empty) {
                *error = "Native execution field '" + key + "' must not be blank.";
                return false;
            }
            return true;
        }
        value->push_back(c);
    }
    *error = "Native execution string field '" + key + "' is unterminated.";
    return false;
}

bool qnn_required_bool_field(
        const std::string& json,
        const std::string& key,
        bool* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    if (json.compare(start, 4u, "true") == 0 && qnn_json_token_ends_at(json, start + 4u)) {
        *value = true;
        return true;
    }
    if (json.compare(start, 5u, "false") == 0 && qnn_json_token_ends_at(json, start + 5u)) {
        *value = false;
        return true;
    }
    *error = "Native execution field '" + key + "' must be a boolean.";
    return false;
}

bool qnn_required_integer_field(
        const std::string& json,
        const std::string& key,
        long long* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    errno = 0;
    char* end = nullptr;
    const char* begin = json.c_str() + start;
    const long long parsed = std::strtoll(begin, &end, 10);
    const size_t end_index = static_cast<size_t>(end - json.c_str());
    if (end == begin || errno == ERANGE || !qnn_json_token_ends_at(json, end_index)) {
        *error = "Native execution field '" + key + "' must be an integer.";
        return false;
    }
    *value = parsed;
    return true;
}

bool qnn_required_number_field(
        const std::string& json,
        const std::string& key,
        double* value,
        std::string* error) {
    size_t start = 0;
    if (!qnn_json_value_start(json, key, &start, error)) return false;
    errno = 0;
    char* end = nullptr;
    const char* begin = json.c_str() + start;
    const double parsed = std::strtod(begin, &end);
    const size_t end_index = static_cast<size_t>(end - json.c_str());
    if (end == begin || errno == ERANGE || !std::isfinite(parsed) ||
        !qnn_json_token_ends_at(json, end_index)) {
        *error = "Native execution field '" + key + "' must be a finite number.";
        return false;
    }
    *value = parsed;
    return true;
}



}  // namespace qnn
}  // namespace mca
