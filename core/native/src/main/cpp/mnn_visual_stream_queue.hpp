#pragma once

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>

namespace mca::mnn {

class VisualStreamQueue {
public:
    enum class ReadKind { Chunk, Finished, Cancelled, Failed };

    struct ReadResult {
        ReadKind kind;
        std::string text;
    };

    explicit VisualStreamQueue(size_t capacity = 64 * 1024, size_t writeSize = 4096)
        : capacity_(capacity), write_size_(std::min(capacity, writeSize)) {
        if (capacity_ == 0 || write_size_ == 0) {
            throw std::invalid_argument("Visual stream queue capacity must be positive.");
        }
    }

    bool write(const char* data, size_t size) {
        size_t offset = 0;
        while (offset < size) {
            const size_t count = std::min(write_size_, size - offset);
            std::unique_lock<std::mutex> lock(mutex_);
            changed_.wait(lock, [&] {
                return producer_stopped_ || terminal_ || queued_bytes_ + count <= capacity_;
            });
            if (producer_stopped_ || terminal_) return false;
            chunks_.emplace_back(data + offset, count);
            queued_bytes_ += count;
            offset += count;
            lock.unlock();
            changed_.notify_all();
        }
        return true;
    }

    ReadResult read() {
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait(lock, [&] { return cancelled_ || terminal_ || !chunks_.empty(); });
        if (cancelled_) return {ReadKind::Cancelled, {}};
        if (!error_.empty()) return {ReadKind::Failed, error_};
        if (chunks_.empty()) return {ReadKind::Finished, {}};
        auto chunk = std::move(chunks_.front());
        chunks_.pop_front();
        queued_bytes_ -= chunk.size();
        lock.unlock();
        changed_.notify_all();
        return {ReadKind::Chunk, std::move(chunk)};
    }

    void cancel() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cancelled_ = true;
            stop_producer_locked();
        }
        changed_.notify_all();
    }

    void stopAfterMarker() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            protocol_stopped_ = true;
            stop_producer_locked();
        }
        changed_.notify_all();
    }

    // Terminal state has no queue slot, so a full buffer cannot block it.
    void finish(std::string error = {}) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (terminal_) return;
            if (!error.empty()) {
                error_ = std::move(error);
                chunks_.clear();
                queued_bytes_ = 0;
            }
            terminal_ = true;
        }
        changed_.notify_all();
    }

    bool waitFinished(std::chrono::milliseconds timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        return changed_.wait_for(lock, timeout, [&] { return terminal_; });
    }

    bool finished() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return terminal_;
    }

    bool cancelled() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return cancelled_;
    }

    bool stoppedAfterMarker() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return protocol_stopped_;
    }

private:
    void stop_producer_locked() {
        producer_stopped_ = true;
        chunks_.clear();
        queued_bytes_ = 0;
    }

    const size_t capacity_;
    const size_t write_size_;
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    std::deque<std::string> chunks_;
    size_t queued_bytes_ = 0;
    bool cancelled_ = false;
    bool producer_stopped_ = false;
    bool protocol_stopped_ = false;
    bool terminal_ = false;
    std::string error_;
};

}  // namespace mca::mnn
