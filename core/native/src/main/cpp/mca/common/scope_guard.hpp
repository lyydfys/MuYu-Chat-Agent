#ifndef MCA_COMMON_SCOPE_GUARD_HPP
#define MCA_COMMON_SCOPE_GUARD_HPP

#include <utility>

namespace mca {
namespace common {

/**
 * Move-only RAII scope guard.
 *
 * Guarantees execution of a cleanup functor upon exiting scope unless explicitly dismissed.
 * Follows C++ Core Guidelines for exception-safe resource management.
 */
template <typename F>
class ScopeGuard {
public:
    explicit ScopeGuard(F&& func) noexcept
        : func_(std::move(func)), active_(true) {}

    explicit ScopeGuard(const F& func)
        : func_(func), active_(true) {}

    ~ScopeGuard() noexcept {
        if (active_) {
            try {
                func_();
            } catch (...) {
                // Destructors must never throw
            }
        }
    }

    // Move-only semantics
    ScopeGuard(const ScopeGuard&) = delete;
    ScopeGuard& operator=(const ScopeGuard&) = delete;

    ScopeGuard(ScopeGuard&& other) noexcept
        : func_(std::move(other.func_)), active_(other.active_) {
        other.active_ = false;
    }

    ScopeGuard& operator=(ScopeGuard&& other) noexcept {
        if (this != &other) {
            if (active_) {
                try {
                    func_();
                } catch (...) {}
            }
            func_ = std::move(other.func_);
            active_ = other.active_;
            other.active_ = false;
        }
        return *this;
    }

    /**
     * Dismisses the guard, preventing the cleanup functor from executing.
     */
    void dismiss() noexcept {
        active_ = false;
    }

    [[nodiscard]] bool is_active() const noexcept {
        return active_;
    }

private:
    F func_;
    bool active_{false};
};

template <typename F>
[[nodiscard]] inline ScopeGuard<typename std::decay<F>::type> make_scope_guard(F&& func) {
    return ScopeGuard<typename std::decay<F>::type>(std::forward<F>(func));
}

} // namespace common
} // namespace mca

#endif // MCA_COMMON_SCOPE_GUARD_HPP
