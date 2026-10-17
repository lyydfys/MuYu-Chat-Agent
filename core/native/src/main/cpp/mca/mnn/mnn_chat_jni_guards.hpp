#ifndef MCA_MNN_CHAT_JNI_GUARDS_HPP
#define MCA_MNN_CHAT_JNI_GUARDS_HPP

// Shared JNI exception-guard helpers for the MNN bridges (relocated
// byte-exact from mnn_native_engine.cpp, T2B2-C1).  Used by both the
// chat entries TU and the object-code JNI entries that stay in the
// monolith, so the definitions must be visible in every including TU.

#include <jni.h>
#include <android/log.h>
#include <exception>

#include "mca/mnn/mnn_jni_convert.hpp"

namespace mca {
namespace mnn {

using mca::mnn::mca_jni_exception_pending;

template <typename Body>
jstring mnn_json_entrypoint(
        JNIEnv* env,
        const char* method_name,
        const char* failure_json,
        Body&& body) {
    if (env == nullptr) return nullptr;
    if (mca_jni_exception_pending(env)) return nullptr;
    try {
        return body();
    } catch (const std::exception& ex) {
        __android_log_print(
                ANDROID_LOG_ERROR,
                "mca_mnn_native",
                "[%s] native exception: %s",
                method_name,
                ex.what());
    } catch (...) {
        __android_log_print(
                ANDROID_LOG_ERROR,
                "mca_mnn_native",
                "[%s] unknown native exception",
                method_name);
    }
    if (mca_jni_exception_pending(env)) return nullptr;
    return env->NewStringUTF(failure_json);
}

template <typename Body>
jintArray mnn_token_ids_entrypoint(
        JNIEnv* env,
        const char* method_name,
        Body&& body) {
    if (env == nullptr) return nullptr;
    if (mca_jni_exception_pending(env)) return nullptr;
    try {
        return body();
    } catch (const std::exception& ex) {
        __android_log_print(
                ANDROID_LOG_ERROR,
                "mca_mnn_native",
                "[%s] native exception: %s",
                method_name,
                ex.what());
    } catch (...) {
        __android_log_print(
                ANDROID_LOG_ERROR,
                "mca_mnn_native",
                "[%s] unknown native exception",
                method_name);
    }
    if (mca_jni_exception_pending(env)) return nullptr;
    return env->NewIntArray(0);
}

}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_CHAT_JNI_GUARDS_HPP
