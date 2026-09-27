#include <cassert>
#include "../../main/cpp/mca_opencl_dispatch.cpp"

int main() {
    OpenClExecutionEvidence evidence;
    auto queue = reinterpret_cast<cl_command_queue>(1);
    auto other_queue = reinterpret_cast<cl_command_queue>(2);
    std::uint64_t submitted = 0;
    std::uint64_t completed = 0;
    const auto scope = evidence.begin_scope();

    evidence.record_submission(queue, scope, false);
    evidence.record_completion(queue, evidence.checkpoint(queue), true);
    assert(evidence.snapshot(scope, &submitted, &completed));
    assert(submitted == 0 && completed == 0);

    evidence.record_submission(queue, scope, true);
    const auto first_finish = evidence.checkpoint(queue);
    evidence.record_completion(queue, first_finish, false);
    assert(evidence.snapshot(scope, &submitted, &completed));
    assert(submitted == 1 && completed == 0);

    evidence.record_submission(queue, scope, true);
    evidence.record_completion(queue, first_finish, true);
    evidence.record_completion(queue, first_finish, true);
    assert(evidence.snapshot(scope, &submitted, &completed));
    assert(submitted == 2 && completed == 1);
    evidence.record_completion(other_queue, first_finish, true);
    assert(evidence.snapshot(scope, &submitted, &completed));
    assert(completed == 1);
    evidence.record_completion(queue, evidence.checkpoint(queue), true);
    assert(evidence.snapshot(scope, &submitted, &completed));
    assert(completed == 2);

    const auto stale_finish = evidence.checkpoint(queue);
    const auto replacement = evidence.begin_scope();
    evidence.record_submission(queue, scope, true);
    evidence.record_completion(queue, stale_finish, true);
    assert(!evidence.snapshot(scope, &submitted, &completed));
    assert(evidence.snapshot(replacement, &submitted, &completed));
    assert(submitted == 0 && completed == 0);

    evidence.record_submission(queue, replacement, true);
    const auto released_queue_finish = evidence.checkpoint(queue);
    evidence.forget_queue(queue);
    evidence.record_submission(queue, replacement, true);
    evidence.record_completion(queue, released_queue_finish, true);
    assert(evidence.snapshot(replacement, &submitted, &completed));
    assert(submitted == 2 && completed == 0);
    evidence.record_completion(queue, evidence.checkpoint(queue), true);
    assert(evidence.snapshot(replacement, &submitted, &completed));
    assert(submitted == 2 && completed == 1);

    evidence.record_submission(other_queue, replacement, true);
    evidence.record_completion(other_queue, evidence.checkpoint(other_queue), true);
    assert(evidence.snapshot(replacement, &submitted, &completed));
    assert(submitted == 3 && completed == 2);
    assert(!evidence.snapshot(replacement, nullptr, &completed));
    return 0;
}
