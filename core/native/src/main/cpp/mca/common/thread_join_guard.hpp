#ifndef MCA_COMMON_THREAD_JOIN_GUARD_HPP
#define MCA_COMMON_THREAD_JOIN_GUARD_HPP

#include <thread>
#include <utility>

namespace mca {
namespace common {

/**
 * RAII guard ensuring a std::thread is safely joined or explicitly detached on scope exit.
 * Prevents std::terminate from unhandled joinable thread destruction.
 */
class ThreadJoinGuard {
public:
    enum class ActionOnExit {
        Join,
        Detach
    };

    explicit ThreadJoinGuard(std::thread thread, ActionOnExit action = ActionOnExit::Join) noexcept
        : thread_(std::move(thread)), action_(action) {}

    ~ThreadJoinGuard() noexcept {
        if (thread_.joinable()) {
            if (action_ == ActionOnExit::Join) {
                thread_.join();
            } else {
                thread_.detach();
            }
        }
    }

    ThreadJoinGuard(const ThreadJoinGuard&) = delete;
    ThreadJoinGuard& operator=(const ThreadJoinGuard&) = delete;

    ThreadJoinGuard(ThreadJoinGuard&& other) noexcept
        : thread_(std::move(other.thread_)), action_(other.action_) {}

    ThreadJoinGuard& operator=(ThreadJoinGuard&& other) noexcept {
        if (this != &other) {
            if (thread_.joinable()) {
                if (action_ == ActionOnExit::Join) thread_.join();
                else thread_.detach();
            }
            thread_ = std::move(other.thread_);
            action_ = other.action_;
        }
        return *this;
    }

    [[nodiscard]] bool joinable() const noexcept {
        return thread_.joinable();
    }

    std::thread& get() noexcept {
        return thread_;
    }

    const std::thread& get() const noexcept {
        return thread_;
    }

    void join() {
        if (thread_.joinable()) {
            thread_.join();
        }
    }

    void detach() {
        if (thread_.joinable()) {
            thread_.detach();
        }
    }

private:
    std::thread thread_;
    ActionOnExit action_{ActionOnExit::Join};
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_THREAD_JOIN_GUARD_HPP
