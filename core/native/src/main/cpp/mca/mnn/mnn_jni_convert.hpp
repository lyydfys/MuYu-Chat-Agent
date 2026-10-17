#ifndef MCA_MNN_JNI_CONVERT_HPP
#define MCA_MNN_JNI_CONVERT_HPP

#include <jni.h>

#include <limits>
#include <string>
#include <vector>

#include "jni_utf8_codec.hpp"

namespace mca {
namespace mnn {

// E08b (pure migration, L1): the JNI string/exception helpers were moved verbatim out of the
// anonymous namespace in mnn_native_engine.cpp so the JNI conversion boundary has a single owner
// (jni_boundary direction, plan sections 5.2/14.2). Function bodies are byte-identical to the
// bridge definitions (source block sha256 ab31283401ac8b34ddc7e4ce9006e82158fd2b02895a00316d07915a8c616e98); only the `inline` keyword was
// added. Callers keep the same unqualified names via using-declarations in the bridge.

inline std::string jstring_to_std(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    std::string out;
    try {
        out = mca::utf8::encode_from_utf16(
                reinterpret_cast<const uint16_t*>(chars),
                static_cast<size_t>(length));
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

inline jstring utf16_to_jstring(JNIEnv* env, const std::vector<uint16_t>& value) {
    if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class, "Native UTF-8 output is too large for a Java string.");
            env->DeleteLocalRef(exception_class);
        }
        return nullptr;
    }
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    static constexpr jchar kEmptyStringData = 0;
    const auto* data = value.empty()
            ? &kEmptyStringData
            : reinterpret_cast<const jchar*>(value.data());
    return env->NewString(
            data,
            static_cast<jsize>(value.size()));
}

inline jstring utf8_to_jstring(JNIEnv* env, const std::string& value) {
    return utf16_to_jstring(env, mca::utf8::decode_to_utf16(value, false).utf16);
}

// Contract: an unexpected C++ exception raised inside a JSON-returning JNI
// entrypoint must become a locatable failure value. Letting it unwind into the
// JVM aborts the process, while returning nullptr would look like "no data" to
// the Kotlin callers that pass these strings straight into JSONObject. On
// failure the entrypoint therefore keeps its JSON contract and reports the
// method plus "native exception" in the payload.
//
// A Java exception that is already pending when the entrypoint returns is
// preserved: this helper never calls FindClass/ThrowNew, so it cannot replace a
// pending exception, and it emits the fallback JSON only when none is pending.
inline bool mca_jni_exception_pending(JNIEnv* env) {
    return env != nullptr && env->ExceptionCheck() == JNI_TRUE;
}

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_JNI_CONVERT_HPP
