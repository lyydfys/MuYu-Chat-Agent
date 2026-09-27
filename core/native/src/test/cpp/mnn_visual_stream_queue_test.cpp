#include "../../main/cpp/mnn_visual_stream_queue.hpp"

#include <cassert>
#include <chrono>
#include <future>
#include <string>

using mca::mnn::VisualStreamQueue;
using namespace std::chrono_literals;

int main() {
    {
        VisualStreamQueue queue(4, 4);
        assert(queue.write(" \n", 2));
        const auto first = queue.read();
        assert(first.kind == VisualStreamQueue::ReadKind::Chunk);
        assert(first.text == " \n");
        assert(!queue.finished());
        queue.finish();
        assert(queue.read().kind == VisualStreamQueue::ReadKind::Finished);
    }
    {
        VisualStreamQueue queue(4, 4);
        auto reader = std::async(std::launch::async, [&] { return queue.read(); });
        assert(reader.wait_for(10ms) == std::future_status::timeout);
        queue.cancel();
        assert(reader.wait_for(1s) == std::future_status::ready);
        assert(reader.get().kind == VisualStreamQueue::ReadKind::Cancelled);
        assert(!queue.write("late", 4));
    }
    {
        VisualStreamQueue queue(4, 4);
        assert(queue.write("full", 4));
        auto writer = std::async(std::launch::async, [&] { return queue.write("late", 4); });
        assert(writer.wait_for(10ms) == std::future_status::timeout);
        queue.cancel();
        assert(writer.wait_for(1s) == std::future_status::ready);
        assert(!writer.get());
        assert(queue.read().kind == VisualStreamQueue::ReadKind::Cancelled);
    }
    {
        VisualStreamQueue queue(4, 4);
        assert(queue.write("full", 4));
        auto writer = std::async(std::launch::async, [&] { return queue.write("late", 4); });
        assert(writer.wait_for(10ms) == std::future_status::timeout);
        queue.finish("native decode failed");
        assert(writer.wait_for(1s) == std::future_status::ready);
        assert(!writer.get());
        const auto terminal = queue.read();
        assert(terminal.kind == VisualStreamQueue::ReadKind::Failed);
        assert(terminal.text == "native decode failed");
        queue.finish("replacement terminal");
        assert(queue.read().text == "native decode failed");
    }
    {
        VisualStreamQueue queue(4, 4);
        auto writer = std::async(std::launch::async, [&] { return queue.write("abcdefgh", 8); });
        assert(queue.read().text == "abcd");
        assert(queue.read().text == "efgh");
        assert(writer.wait_for(1s) == std::future_status::ready);
        assert(writer.get());
        queue.stopAfterMarker();
        assert(queue.stoppedAfterMarker());
        assert(!queue.cancelled());
        assert(!queue.write("late", 4));
        assert(!queue.waitFinished(1ms));
        queue.finish();
        assert(queue.waitFinished(1s));
        assert(queue.read().kind == VisualStreamQueue::ReadKind::Finished);
    }
}
