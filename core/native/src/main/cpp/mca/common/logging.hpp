#ifndef MCA_COMMON_LOGGING_HPP
#define MCA_COMMON_LOGGING_HPP

#include <cstdint>
#include <string>

namespace mca {
namespace common {

enum class LogLevel : uint8_t {
    Verbose = 0,
    Debug = 1,
    Info = 2,
    Warn = 3,
    Error = 4,
    Fatal = 5
};

class Logger {
public:
    static void set_minimum_level(LogLevel level) noexcept;
    static LogLevel get_minimum_level() noexcept;

    static void log(
        LogLevel level,
        const char* tag,
        const char* file,
        int line,
        const char* format,
        ...
    );

    /**
     * Redacts absolute filesystem paths and credentials from diagnostic messages.
     */
    [[nodiscard]] static std::string redact(const std::string& message);
};

} // namespace common
} // namespace mca

#define MCA_LOGV(tag, fmt, ...) \
    ::mca::common::Logger::log(::mca::common::LogLevel::Verbose, tag, __FILE__, __LINE__, fmt, ##__VA_ARGS__)

#define MCA_LOGD(tag, fmt, ...) \
    ::mca::common::Logger::log(::mca::common::LogLevel::Debug, tag, __FILE__, __LINE__, fmt, ##__VA_ARGS__)

#define MCA_LOGI(tag, fmt, ...) \
    ::mca::common::Logger::log(::mca::common::LogLevel::Info, tag, __FILE__, __LINE__, fmt, ##__VA_ARGS__)

#define MCA_LOGW(tag, fmt, ...) \
    ::mca::common::Logger::log(::mca::common::LogLevel::Warn, tag, __FILE__, __LINE__, fmt, ##__VA_ARGS__)

#define MCA_LOGE(tag, fmt, ...) \
    ::mca::common::Logger::log(::mca::common::LogLevel::Error, tag, __FILE__, __LINE__, fmt, ##__VA_ARGS__)

#define MCA_LOGF(tag, fmt, ...) \
    ::mca::common::Logger::log(::mca::common::LogLevel::Fatal, tag, __FILE__, __LINE__, fmt, ##__VA_ARGS__)

#endif // MCA_COMMON_LOGGING_HPP
