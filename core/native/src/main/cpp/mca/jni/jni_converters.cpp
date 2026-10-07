#include "jni_converters.hpp"
#include "mca/common/jni_support.hpp"

namespace mca {
namespace jni {

std::string JniConverters::to_native_string(JNIEnv* env, jstring java_str) {
    if (env == nullptr || java_str == nullptr) {
        return "";
    }
    common::JniStringGuard guard(env, java_str);
    return guard.to_string();
}

jstring JniConverters::to_java_string(JNIEnv* env, const std::string& str) {
    if (env == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(str.c_str());
}

std::string JniConverters::error_to_json(const contract::ErrorEnvelope& error) {
    std::string json = "{";
    json += "\"code\":\"" + std::string(contract::stable_error_code_to_string(error.code)) + "\",";
    json += "\"userMessageKey\":\"" + error.user_message_key + "\",";
    json += "\"publicMessage\":\"" + error.public_message + "\"";
    if (error.native_code.has_value()) {
        json += ",\"nativeCode\":" + std::to_string(error.native_code.value());
    }
    if (error.http_code.has_value()) {
        json += ",\"httpCode\":" + std::to_string(error.http_code.value());
    }
    json += "}";
    return json;
}

} // namespace jni
} // namespace mca
