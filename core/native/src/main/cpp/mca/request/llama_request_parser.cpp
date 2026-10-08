#include "llama_request_parser.hpp"

#include <algorithm>
#include <cctype>

namespace mca {
namespace request {

int parse_int(const std::string &json, const std::string &key, int fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    std::string number;
    while (pos < json.size() && (std::isdigit((unsigned char) json[pos]) || json[pos] == '-')) {
        number.push_back(json[pos++]);
    }
    if (number.empty()) return fallback;
    try { return std::stoi(number); } catch (...) { return fallback; }
}

float parse_float(const std::string &json, const std::string &key, float fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    std::string number;
    while (pos < json.size() &&
           (std::isdigit((unsigned char) json[pos]) || json[pos] == '-' || json[pos] == '.')) {
        number.push_back(json[pos++]);
    }
    if (number.empty()) return fallback;
    try { return std::stof(number); } catch (...) { return fallback; }
}

float parse_float_any(const std::string &json, const std::vector<std::string> &keys, float fallback) {
    for (const auto &key: keys) {
        if (json.find("\"" + key + "\"") == std::string::npos) continue;
        return parse_float(json, key, fallback);
    }
    return fallback;
}

int parse_int_any(const std::string &json, const std::vector<std::string> &keys, int fallback) {
    for (const auto &key: keys) {
        if (json.find("\"" + key + "\"") == std::string::npos) continue;
        return parse_int(json, key, fallback);
    }
    return fallback;
}

bool parse_bool(const std::string &json, const std::string &key, bool fallback) {
    const auto key_pos = json.find("\"" + key + "\"");
    if (key_pos == std::string::npos) return fallback;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return fallback;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    if (json.compare(pos, 4, "true") == 0) return true;
    if (json.compare(pos, 5, "false") == 0) return false;
    return fallback;
}

std::string parse_json_string_after(const std::string &json, const std::string &key, size_t start) {
    const auto key_pos = json.find("\"" + key + "\"", start);
    if (key_pos == std::string::npos) return "";
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return "";
    auto pos = json.find('"', colon + 1);
    if (pos == std::string::npos) return "";
    pos++;
    std::string out;
    while (pos < json.size()) {
        const char c = json[pos++];
        if (c == '"') break;
        if (c == '\\' && pos < json.size()) {
            const char escaped = json[pos++];
            switch (escaped) {
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                default: out.push_back(escaped); break;
            }
        } else {
            out.push_back(c);
        }
    }
    return out;
}

std::string parse_string(const std::string &json, const std::string &key, const std::string &fallback) {
    const auto value = parse_json_string_after(json, key, 0);
    return value.empty() ? fallback : value;
}

bool starts_with(const std::string &value, const std::string &prefix) {
    return value.rfind(prefix, 0) == 0;
}

int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::string url_decode(const std::string &value) {
    std::string out;
    out.reserve(value.size());
    for (size_t i = 0; i < value.size(); i++) {
        if (value[i] == '%' && i + 2 < value.size()) {
            const int high = hex_value(value[i + 1]);
            const int low = hex_value(value[i + 2]);
            if (high >= 0 && low >= 0) {
                out.push_back((char) ((high << 4) | low));
                i += 2;
                continue;
            }
        }
        out.push_back(value[i]);
    }
    return out;
}

std::string local_image_path_from_url(const std::string &url) {
    if (starts_with(url, "file://")) {
        return url_decode(url.substr(7));
    }
    if (starts_with(url, "file:")) {
        auto path = url_decode(url.substr(5));
        while (path.rfind("//", 0) == 0) path.erase(path.begin());
        return path;
    }
    return url;
}

std::string canonical_local_image_reference(std::string value) {
    value = local_image_path_from_url(value);
    while (!value.empty() && std::isspace(static_cast<unsigned char>(value.front()))) {
        value.erase(value.begin());
    }
    while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back()))) {
        value.pop_back();
    }
    std::replace(value.begin(), value.end(), '\\', '/');
    while (value.find("/./") != std::string::npos) {
        value.replace(value.find("/./"), 3, "/");
    }
    while (value.size() > 1 && value.back() == '/') value.pop_back();
    return value;
}

size_t find_object_end(const std::string &json, size_t start) {
    int depth = 0;
    bool in_string = false;
    bool escaped = false;
    for (size_t i = start; i < json.size(); i++) {
        const char c = json[i];
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
        } else if (c == '{') {
            depth++;
        } else if (c == '}') {
            depth--;
            if (depth == 0) return i + 1;
        }
    }
    return json.size();
}

size_t value_start_after_key(const std::string &json, const std::string &key, size_t start) {
    const auto key_pos = json.find("\"" + key + "\"", start);
    if (key_pos == std::string::npos) return std::string::npos;
    const auto colon = json.find(':', key_pos);
    if (colon == std::string::npos) return std::string::npos;
    auto pos = colon + 1;
    while (pos < json.size() && std::isspace((unsigned char) json[pos])) pos++;
    return pos;
}

void parse_content_parts(const std::string &segment, ParsedMessage &message) {
    size_t pos = 0;
    while (true) {
        const auto type_pos = segment.find("\"type\"", pos);
        if (type_pos == std::string::npos) break;
        const auto part_start = segment.rfind('{', type_pos);
        if (part_start == std::string::npos) break;
        const auto part_end = find_object_end(segment, part_start);
        const auto part = segment.substr(part_start, part_end - part_start);
        const auto type = parse_json_string_after(part, "type", 0);
        if (type == "text") {
            const auto text = parse_json_string_after(part, "text", 0);
            if (!text.empty()) {
                if (!message.content.empty()) message.content += "\n";
                message.content += text;
            }
        } else if (type == "image_url" || type == "input_image" || type == "image") {
            std::string url;
            for (const auto *key : {"url", "image_url", "image", "input_image"}) {
                url = parse_json_string_after(part, key, 0);
                if (!url.empty()) break;
            }
            if (!url.empty() && !starts_with(url, "data:")) {
                const auto path = local_image_path_from_url(url);
                const auto key = canonical_local_image_reference(path);
                const bool duplicate = !key.empty() && std::any_of(
                        message.image_paths.begin(),
                        message.image_paths.end(),
                        [&](const std::string &previous) {
                            return canonical_local_image_reference(previous) == key;
                        });
                if (!duplicate) message.image_paths.push_back(path);
            }
        }
        pos = part_end;
    }
}

std::vector<ParsedMessage> parse_messages(const std::string &messages_json) {
    std::vector<ParsedMessage> messages;
    size_t pos = 0;
    while (true) {
        const auto role_pos = messages_json.find("\"role\"", pos);
        if (role_pos == std::string::npos) break;
        const auto object_start = messages_json.rfind('{', role_pos);
        const auto object_end = find_object_end(messages_json, object_start == std::string::npos ? role_pos : object_start);
        const auto segment = messages_json.substr(role_pos, object_end - role_pos);
        const auto role = parse_json_string_after(messages_json, "role", role_pos);
        ParsedMessage message{role.empty() ? "user" : role, ""};
        const auto content_start = value_start_after_key(segment, "content", 0);
        if (content_start != std::string::npos) {
            if (content_start < segment.size() && segment[content_start] == '"') {
                message.content = parse_json_string_after(segment, "content", 0);
            } else if (content_start < segment.size() && segment[content_start] == '[') {
                parse_content_parts(segment.substr(content_start), message);
            }
        }
        if (!message.content.empty() || !message.image_paths.empty()) {
            messages.push_back(message);
        }
        pos = object_end;
    }
    if (messages.empty()) messages.push_back({"user", messages_json});
    return messages;
}

bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const auto *bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

std::string json_escape(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c: value) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

} // namespace request
} // namespace mca
