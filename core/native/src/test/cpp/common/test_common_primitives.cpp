#include "mca/common/scope_guard.hpp"
#include "mca/common/file_guard.hpp"
#include "mca/common/thread_join_guard.hpp"
#include "mca/common/monotonic_clock.hpp"
#include "mca/common/sequence_generator.hpp"
#include "mca/common/safe_path.hpp"
#include "mca/common/observability.hpp"
#include "mca/common/test_double_injection.hpp"

#include <cassert>
#include <iostream>
#include <vector>
#include <thread>

using namespace mca::common;

void test_scope_guard() {
    int counter = 0;
    {
        auto guard = make_scope_guard([&counter]() { counter += 1; });
        assert(guard.is_active());
        assert(counter == 0);
    }
    assert(counter == 1);

    // Dismiss test
    {
        auto guard = make_scope_guard([&counter]() { counter += 10; });
        guard.dismiss();
        assert(!guard.is_active());
    }
    assert(counter == 1); // Not changed

    // Move-only test
    {
        auto g1 = make_scope_guard([&counter]() { counter += 5; });
        auto g2 = std::move(g1);
        assert(!g1.is_active());
        assert(g2.is_active());
    }
    assert(counter == 6);
}

void test_file_guard() {
    FileDescriptorGuard guard(-1);
    assert(!guard.is_valid());
    assert(guard.get() == -1);

    FileDescriptorGuard valid_dummy(100);
    assert(valid_dummy.is_valid());
    int released = valid_dummy.release();
    assert(released == 100);
    assert(!valid_dummy.is_valid());
}

void test_thread_join_guard() {
    bool executed = false;
    {
        std::thread t([&executed]() {
            executed = true;
        });
        ThreadJoinGuard guard(std::move(t));
        assert(guard.joinable());
    } // Joined on exit
    assert(executed);
}

void test_monotonic_clock_and_deadline() {
    int64_t t1 = MonotonicClock::now_ms();
    assert(t1 > 0);

    Deadline d = Deadline::from_timeout_ms(100);
    assert(!d.is_expired());
    assert(d.remaining_ms() <= 100);

    Deadline expired = Deadline::from_timeout_ms(0);
    assert(expired.is_expired());
    assert(expired.remaining_ms() == 0);
}

void test_sequence_generator() {
    SequenceGenerator gen(10);
    assert(gen.current() == 10);
    assert(gen.next() == 10);
    assert(gen.next() == 11);
    assert(gen.current() == 12);

    gen.reset(0);
    assert(gen.current() == 0);
}

void test_safe_path() {
    assert(SafePath::has_traversal("../secret"));
    assert(SafePath::has_traversal("models/../../root"));
    assert(SafePath::has_traversal("models/.."));
    assert(!SafePath::has_traversal("models/qwen/model.bin"));
    assert(!SafePath::has_traversal("models/sd/vae.onnx"));

    std::string joined = SafePath::safe_join("/data/local/models", "qwen/config.json");
    assert(joined == "/data/local/models/qwen/config.json");

    std::string escaped = SafePath::safe_join("/data/local/models", "../../etc/passwd");
    assert(escaped.empty()); // Blocked!
}

void test_observability() {
    auto& sink = MetricsSink::instance();
    sink.reset();

    sink.record_counter("test.requests", 1);
    sink.record_counter("test.requests", 2);
    assert(sink.get_counter("test.requests") == 3);

    sink.record_latency_ms("inference", 45);
    sink.record_latency_ms("inference", 55);

    auto counters = sink.snapshot_counters();
    assert(counters["test.requests"] == 3);
}

void test_fault_injection() {
    using namespace mca::common::test_double;
    NonProductionFaultInjector::reset();

    assert(!NonProductionFaultInjector::should_fail_next_allocation());
    NonProductionFaultInjector::set_should_fail_next_allocation(true);
    assert(NonProductionFaultInjector::should_fail_next_allocation());
    assert(!NonProductionFaultInjector::should_fail_next_allocation()); // Cleared

    NonProductionFaultInjector::set_clock_offset_ms(5000);
    assert(NonProductionFaultInjector::get_clock_offset_ms() == 5000);
    NonProductionFaultInjector::reset();
}

int main() {
    std::cout << "Running MCA Native Common Host Unit Tests..." << std::endl;
    test_scope_guard();
    test_file_guard();
    test_thread_join_guard();
    test_monotonic_clock_and_deadline();
    test_sequence_generator();
    test_safe_path();
    test_observability();
    test_fault_injection();
    std::cout << "ALL MCA NATIVE COMMON HOST TESTS PASSED!" << std::endl;
    return 0;
}
