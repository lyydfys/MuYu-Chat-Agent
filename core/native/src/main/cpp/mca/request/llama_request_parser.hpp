#ifndef MCA_REQUEST_LLAMA_REQUEST_PARSER_HPP
#define MCA_REQUEST_LLAMA_REQUEST_PARSER_HPP

#include <string>
#include <vector>
#include <cstddef>

namespace mca {
namespace request {

struct ParsedMessage {
    std::string role;
    std::string content;
    std::vector<std::string> image_paths;
};

int parse_int(const std::string &json, const std::string &key, int fallback);
float parse_float(const std::string &json, const std::string &key, float fallback);
float parse_float_any(const std::string &json, const std::vector<std::string> &keys, float fallback);
int parse_int_any(const std::string &json, const std::vector<std::string> &keys, int fallback);
bool parse_bool(const std::string &json, const std::string &key, bool fallback);
std::string parse_json_string_after(const std::string &json, const std::string &key, size_t start);
std::string parse_string(const std::string &json, const std::string &key, const std::string &fallback);

bool starts_with(const std::string &value, const std::string &prefix);
int hex_value(char c);
std::string url_decode(const std::string &value);
std::string local_image_path_from_url(const std::string &url);
std::string canonical_local_image_reference(std::string value);
size_t find_object_end(const std::string &json, size_t start);
size_t value_start_after_key(const std::string &json, const std::string &key, size_t start);
void parse_content_parts(const std::string &segment, ParsedMessage &message);
std::vector<ParsedMessage> parse_messages(const std::string &messages_json);
bool is_valid_utf8(const char *string);
std::string json_escape(const std::string &value);

} // namespace request
} // namespace mca

#endif // MCA_REQUEST_LLAMA_REQUEST_PARSER_HPP
