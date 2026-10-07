#ifndef MCA_JNI_HANDLE_REGISTRY_HPP
#define MCA_JNI_HANDLE_REGISTRY_HPP

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <atomic>

namespace mca {
namespace jni {

/**
 * Type-safe handle registry replacing bare reinterpret_cast pointers across JNI.
 *
 * Prevents:
 * 1. Invalid handle use-after-free
 * 2. Handle type confusion
 * 3. Stale handle re-dereferencing
 */
template <typename T>
class HandleRegistry {
public:
    static HandleRegistry<T>& instance() noexcept {
        static HandleRegistry<T> s_registry;
        return s_registry;
    }

    /**
     * Registers an object into the registry and returns a non-zero opaque handle ID.
     */
    int64_t allocate(std::shared_ptr<T> object) {
        if (!object) return 0;
        std::lock_guard<std::mutex> lock(mutex_);
        const int64_t handle = next_handle_.fetch_add(1, std::memory_order_relaxed);
        table_[handle] = std::move(object);
        return handle;
    }

    /**
     * Looks up an object by handle. Returns nullptr if the handle is invalid or expired.
     */
    std::shared_ptr<T> resolve(int64_t handle) {
        if (handle <= 0) return nullptr;
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = table_.find(handle);
        return (it != table_.end()) ? it->second : nullptr;
    }

    /**
     * Releases an object handle. Returns true if the handle was present and removed.
     */
    bool release(int64_t handle) {
        if (handle <= 0) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return table_.erase(handle) > 0;
    }

    /**
     * Returns the count of actively registered handles.
     */
    size_t count() {
        std::lock_guard<std::mutex> lock(mutex_);
        return table_.size();
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        table_.clear();
    }

private:
    HandleRegistry() : next_handle_(1000) {}
    std::mutex mutex_;
    std::atomic<int64_t> next_handle_{1000};
    std::unordered_map<int64_t, std::shared_ptr<T>> table_;
};

} // namespace jni
} // namespace mca

#endif // MCA_JNI_HANDLE_REGISTRY_HPP
