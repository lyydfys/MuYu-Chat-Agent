#ifndef MCA_JNI_CONVERTERS_HPP
#define MCA_JNI_CONVERTERS_HPP

#include <jni.h>
#include <string>
#include "mca/contract/request_identity.hpp"
#include "mca/contract/error_envelope.hpp"

namespace mca {
namespace jni {

class JniConverters {
public:
    /**
     * Converts a Java jstring to a safe std::string. Returns empty string if jstr is null.
     */
    static std::string to_native_string(JNIEnv* env, jstring java_str);

    /**
     * Creates a JVM jstring from a UTF-8 native string.
     */
    static jstring to_java_string(JNIEnv* env, const std::string& str);

    /**
     * Serializes an ErrorEnvelope to a JSON string for Java consumption.
     */
    static std::string error_to_json(const contract::ErrorEnvelope& error);
};

} // namespace jni
} // namespace mca

#endif // MCA_JNI_CONVERTERS_HPP
