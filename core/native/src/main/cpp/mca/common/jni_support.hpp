#ifndef MCA_COMMON_JNI_SUPPORT_HPP
#define MCA_COMMON_JNI_SUPPORT_HPP

#include <jni.h>
#include <string>
#include <utility>
#include <cstdint>

namespace mca {
namespace common {

/**
 * RAII guard for JNI string UTF characters.
 */
class JniStringGuard {
public:
    JniStringGuard(JNIEnv* env, jstring java_str) noexcept
        : env_(env), java_str_(java_str) {
        if (env_ != nullptr && java_str_ != nullptr) {
            chars_ = env_->GetStringUTFChars(java_str_, nullptr);
        }
    }

    ~JniStringGuard() noexcept {
        reset();
    }

    JniStringGuard(const JniStringGuard&) = delete;
    JniStringGuard& operator=(const JniStringGuard&) = delete;

    JniStringGuard(JniStringGuard&& other) noexcept
        : env_(other.env_), java_str_(other.java_str_), chars_(other.chars_) {
        other.chars_ = nullptr;
    }

    JniStringGuard& operator=(JniStringGuard&& other) noexcept {
        if (this != &other) {
            reset();
            env_ = other.env_;
            java_str_ = other.java_str_;
            chars_ = other.chars_;
            other.chars_ = nullptr;
        }
        return *this;
    }

    [[nodiscard]] const char* c_str() const noexcept {
        return chars_ != nullptr ? chars_ : "";
    }

    [[nodiscard]] bool is_valid() const noexcept {
        return chars_ != nullptr;
    }

    [[nodiscard]] std::string to_string() const {
        return chars_ != nullptr ? std::string(chars_) : std::string();
    }

    void reset() noexcept {
        if (env_ != nullptr && java_str_ != nullptr && chars_ != nullptr) {
            env_->ReleaseStringUTFChars(java_str_, chars_);
            chars_ = nullptr;
        }
    }

private:
    JNIEnv* env_{nullptr};
    jstring java_str_{nullptr};
    const char* chars_{nullptr};
};

/**
 * RAII exception barrier checking and clearing JNI pending exceptions.
 */
class JniExceptionGuard {
public:
    explicit JniExceptionGuard(JNIEnv* env) noexcept : env_(env) {}

    ~JniExceptionGuard() noexcept {
        check_and_clear();
    }

    [[nodiscard]] bool has_exception() const noexcept {
        return env_ != nullptr && env_->ExceptionCheck() == JNI_TRUE;
    }

    bool check_and_clear() noexcept {
        if (has_exception()) {
            env_->ExceptionClear();
            return true;
        }
        return false;
    }

private:
    JNIEnv* env_{nullptr};
};

/**
 * Validated wrapper around JNI Direct Byte Buffers.
 */
class JniDirectByteBuffer {
public:
    JniDirectByteBuffer(JNIEnv* env, jobject byte_buffer) noexcept {
        if (env != nullptr && byte_buffer != nullptr) {
            data_ = env->GetDirectBufferAddress(byte_buffer);
            capacity_ = env->GetDirectBufferCapacity(byte_buffer);
        }
    }

    [[nodiscard]] void* data() noexcept { return data_; }
    [[nodiscard]] const void* data() const noexcept { return data_; }
    [[nodiscard]] jlong capacity() const noexcept { return capacity_; }
    [[nodiscard]] bool is_valid() const noexcept { return data_ != nullptr && capacity_ > 0; }

private:
    void* data_{nullptr};
    jlong capacity_{0};
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_JNI_SUPPORT_HPP
