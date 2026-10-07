#ifndef MCA_JNI_EXCEPTION_BOUNDARY_HPP
#define MCA_JNI_EXCEPTION_BOUNDARY_HPP

#include <jni.h>
#include <string>
#include <exception>
#include <new>
#include <stdexcept>
#include "mca/contract/stable_error_code.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace jni {

/**
 * Exception boundary wrapper ensuring zero unhandled native C++ exceptions cross into JNI/JVM.
 */
class JniExceptionBoundary {
public:
    template <typename Fallback, typename F>
    static auto wrap(
        JNIEnv* env,
        const char* method_name,
        Fallback fallback_value,
        F&& func
    ) noexcept -> decltype(func()) {
        try {
            return func();
        } catch (const std::bad_alloc& ex) {
            MCA_LOGE("JNI", "[%s] Native OutOfMemory: %s", method_name, ex.what());
            throw_java_exception(env, "java/lang/OutOfMemoryError", ex.what());
            return fallback_value;
        } catch (const std::invalid_argument& ex) {
            MCA_LOGE("JNI", "[%s] Native InvalidArgument: %s", method_name, ex.what());
            throw_java_exception(env, "java/lang/IllegalArgumentException", ex.what());
            return fallback_value;
        } catch (const std::exception& ex) {
            MCA_LOGE("JNI", "[%s] Native UnexpectedException: %s", method_name, ex.what());
            throw_java_exception(env, "java/lang/RuntimeException", ex.what());
            return fallback_value;
        } catch (...) {
            MCA_LOGE("JNI", "[%s] Native Unknown Exception caught at boundary", method_name);
            throw_java_exception(env, "java/lang/RuntimeException", "Unknown native exception");
            return fallback_value;
        }
    }

    template <typename F>
    static void wrap_void(
        JNIEnv* env,
        const char* method_name,
        F&& func
    ) noexcept {
        try {
            func();
        } catch (const std::bad_alloc& ex) {
            MCA_LOGE("JNI", "[%s] Native OutOfMemory: %s", method_name, ex.what());
            throw_java_exception(env, "java/lang/OutOfMemoryError", ex.what());
        } catch (const std::invalid_argument& ex) {
            MCA_LOGE("JNI", "[%s] Native InvalidArgument: %s", method_name, ex.what());
            throw_java_exception(env, "java/lang/IllegalArgumentException", ex.what());
        } catch (const std::exception& ex) {
            MCA_LOGE("JNI", "[%s] Native UnexpectedException: %s", method_name, ex.what());
            throw_java_exception(env, "java/lang/RuntimeException", ex.what());
        } catch (...) {
            MCA_LOGE("JNI", "[%s] Native Unknown Exception caught at boundary", method_name);
            throw_java_exception(env, "java/lang/RuntimeException", "Unknown native exception");
        }
    }

private:
    static void throw_java_exception(
        JNIEnv* env,
        const char* exception_class,
        const char* message
    ) noexcept {
        if (env == nullptr) return;
        jclass clazz = env->FindClass(exception_class);
        if (clazz != nullptr) {
            env->ThrowNew(clazz, message != nullptr ? message : "Native error");
            env->DeleteLocalRef(clazz);
        }
    }
};

} // namespace jni
} // namespace mca

#endif // MCA_JNI_EXCEPTION_BOUNDARY_HPP
