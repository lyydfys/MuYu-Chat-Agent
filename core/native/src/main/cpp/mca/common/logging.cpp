#include "logging.hpp"

#include <cstdio>
#include <cstdarg>
#include <regex>
#include <atomic>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace mca {
namespace common {

static std::atomic<LogLevel> g_min_level{LogLevel::Info};

void Logger::set_minimum_level(LogLevel level) noexcept {
    g_min_level.store(level, std::memory_order_relaxed);
}

LogLevel Logger::get_minimum_level() noexcept {
    return g_min_level.load(std::memory_order_relaxed);
}

std::string Logger::redact(const std::string& message) {
    // Redact absolute local storage / data paths
    static const std::regex kPathRegex(R"((?:[a-zA-Z]:\\[^\s,]+)|(?:\/(?:data|storage|emulated)[^\s,]+))");
    return std::regex_replace(message, kPathRegex, "<redacted-path>");
}

void Logger::log(
    LogLevel level,
    const char* tag,
    const char* file,
    int line,
    const char* format,
    ...
) {
    if (level < g_min_level.load(std::memory_order_relaxed)) {
        return;
    }

    char buffer[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);

    const std::string redacted = redact(buffer);

#if defined(__ANDROID__)
    int android_prio = ANDROID_LOG_INFO;
    switch (level) {
        case LogLevel::Verbose: android_prio = ANDROID_LOG_VERBOSE; break;
        case LogLevel::Debug:   android_prio = ANDROID_LOG_DEBUG;   break;
        case LogLevel::Info:    android_prio = ANDROID_LOG_INFO;    break;
        case LogLevel::Warn:    android_prio = ANDROID_LOG_WARN;    break;
        case LogLevel::Error:   android_prio = ANDROID_LOG_ERROR;   break;
        case LogLevel::Fatal:   android_prio = ANDROID_LOG_FATAL;   break;
    }
    __android_log_print(android_prio, tag, "%s", redacted.c_str());
#else
    const char* level_str = "INFO";
    switch (level) {
        case LogLevel::Verbose: level_str = "VERBOSE"; break;
        case LogLevel::Debug:   level_str = "DEBUG";   break;
        case LogLevel::Info:    level_str = "INFO";    break;
        case LogLevel::Warn:    level_str = "WARN";    break;
        case LogLevel::Error:   level_str = "ERROR";   break;
        case LogLevel::Fatal:   level_str = "FATAL";   break;
    }
    fprintf(stderr, "[%s][%s] %s\n", level_str, tag, redacted.c_str());
    fflush(stderr);
#endif
}

} // namespace common
} // namespace mca
