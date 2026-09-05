#include "../../main/cpp/mnn_stream_protocol_filter.hpp"

#include <cassert>
#include <string>
#include <vector>

static void expectXml(const std::string& raw, const std::string& expected, bool stopped) {
    // Every two-chunk split plus byte-wise streaming must agree with one write.
    for (size_t split = 0; split <= raw.size(); ++split) {
        mca::mnn::StreamProtocolFilterState state;
        state.reset({"<|im_end|>"});
        std::string visible = mca::mnn::filter_stream_protocol(state, raw.substr(0, split), false).visible;
        visible += mca::mnn::filter_stream_protocol(state, raw.substr(split), true).visible;
        assert(visible == expected);
        assert(state.stopped == stopped);
    }
    mca::mnn::StreamProtocolFilterState state;
    state.reset({"<|im_end|>"});
    std::string visible;
    for (const char ch : raw) visible += mca::mnn::filter_stream_protocol(state, std::string(1, ch), false).visible;
    visible += mca::mnn::filter_stream_protocol(state, "", true).visible;
    assert(visible == expected);
    assert(state.stopped == stopped);
}

int main() {
    using mca::mnn::StreamProtocolFilterState;
    using mca::mnn::filter_stream_protocol;

    const std::vector<std::string> terminal_markers = {
        "<eop>",
        "<|im_end|>",
        "<|endoftext|>",
    };

    // A ChatML assistant header is valid at the beginning of a turn and must
    // be removed without dropping the assistant body.
    StreamProtocolFilterState chatml;
    chatml.reset(terminal_markers);
    const auto chatml_result = filter_stream_protocol(
        chatml,
        "<|im_start|>assistant\n\xE6\xAD\xA3\xE6\x96\x87",
        false);
    assert(chatml_result.visible == "\xE6\xAD\xA3\xE6\x96\x87");
    assert(!chatml_result.stopped);
    assert(chatml.visible_output_seen);

    // A role header may be split byte-for-byte across MNN stream writes.
    StreamProtocolFilterState split_header;
    split_header.reset(terminal_markers);
    const auto split_first = filter_stream_protocol(split_header, "<|im_sta", false);
    assert(split_first.visible.empty());
    assert(split_header.has_pending_protocol());
    const auto split_second = filter_stream_protocol(
        split_header,
        "rt|>assistant\nanswer",
        false);
    assert(split_second.visible == "answer");
    assert(!split_second.stopped);

    // Llama-style start/end header framing is also stripped only at the start.
    StreamProtocolFilterState llama;
    llama.reset(terminal_markers);
    const auto llama_result = filter_stream_protocol(
        llama,
        "<|start_header_id|>assistant<|end_header_id|>\n\nbody",
        false);
    assert(llama_result.visible == "body");
    assert(!llama_result.stopped);

    // After assistant text has been emitted, a role marker starts the next
    // template turn and must terminate output rather than leak it.
    StreamProtocolFilterState next_turn;
    next_turn.reset(terminal_markers);
    assert(filter_stream_protocol(next_turn, "body", false).visible == "body");
    const auto next_turn_result = filter_stream_protocol(
        next_turn,
        "<|im_start|>user\nshould not leak",
        false);
    assert(next_turn_result.visible.empty());
    assert(next_turn_result.stopped);
    assert(next_turn.stop_reason == "role_header_after_visible_text");

    // A split role header following visible output must remain hidden in both
    // pieces, while retaining the text before it.
    StreamProtocolFilterState split_next_turn;
    split_next_turn.reset(terminal_markers);
    const auto split_body = filter_stream_protocol(split_next_turn, "body<|im_s", false);
    assert(split_body.visible == "body");
    const auto split_role = filter_stream_protocol(
        split_next_turn,
        "tart|>assistant\nshould not leak",
        false);
    assert(split_role.visible.empty());
    assert(split_role.stopped);

    // True EOS markers remain terminal, including when split across writes.
    StreamProtocolFilterState split_eos;
    split_eos.reset(terminal_markers);
    const auto eos_body = filter_stream_protocol(split_eos, "body<eo", false);
    assert(eos_body.visible == "body");
    const auto eos_tail = filter_stream_protocol(split_eos, "p>should not leak", false);
    assert(eos_tail.visible.empty());
    assert(eos_tail.stopped);
    assert(split_eos.stop_reason == "stop_marker");

    // Converted packages can emit non-ChatML role tokens. They are prompt
    // protocol, never assistant text, and must not reach Java even when split
    // across stream writes.
    StreamProtocolFilterState unsafe_role;
    unsafe_role.reset(terminal_markers);
    const auto unsafe_prefix = filter_stream_protocol(unsafe_role, "answer<|us", false);
    assert(unsafe_prefix.visible == "answer");
    assert(!unsafe_prefix.stopped);
    const auto unsafe_tail = filter_stream_protocol(unsafe_role, "er|>prompt leak", false);
    assert(unsafe_tail.visible.empty());
    assert(unsafe_tail.stopped);
    assert(unsafe_role.stop_reason == "unexpected_role_token");

    // Some MNN tokenizers detokenize the next role as a plain `assistant`
    // line.  It is protocol framing only when it occupies a whole line; the
    // same word inside normal prose must remain untouched.
    StreamProtocolFilterState plain_role;
    plain_role.reset(terminal_markers);
    assert(filter_stream_protocol(plain_role, "answer\nassistant\nleak", false).visible == "answer\n");
    assert(plain_role.stopped);

    StreamProtocolFilterState plain_user;
    plain_user.reset(terminal_markers);
    const auto plain_user_result = filter_stream_protocol(
        plain_user,
        "answer\nuser\nnext prompt",
        false);
    assert(plain_user_result.visible == "answer\n");
    assert(plain_user_result.stopped);

    StreamProtocolFilterState prose_assistant;
    prose_assistant.reset(terminal_markers);
    const auto prose = filter_stream_protocol(
        prose_assistant,
        "The assistant answered clearly.",
        false);
    assert(prose.visible == "The assistant answered clearly.");
    assert(!prose.stopped);

    for (const std::string literal : {
            "The <response> tag contains a reply; </response> closes it.",
            "\"<answer>\" and \"</answer>\" are literal XML tags.",
            "`<response>value</response>` is an example.",
            "```xml\n<response>value</response>\n```",
            "</response> is a closing tag.",
            "Partial tag: <res", "Partial closing tag: </resp"}) {
        expectXml(literal, literal, false);
    }
    expectXml("<answer>4</answer>\nuser\nleak", "4", true);
    expectXml("<response>Yes</response><|im_end|>", "Yes", true);
    expectXml("<answer>Use <response>x</response> here.</answer>",
              "Use <response>x</response> here.", true);
    expectXml("<response>Use <response>x</response> here.</response>",
              "Use <response>x</response> here.", true);
    expectXml("<response>The tag `</response>` closes it.</response>",
              "The tag `</response>` closes it.", true);
    expectXml("<response>\"</response>\" closes it.</response>",
              "\"</response>\" closes it.", true);
    expectXml("<response>```xml\n<response>x</response>\n```\nDone.</response>",
              "```xml\n<response>x</response>\n```\nDone.", true);
    expectXml("<answer>\n\n</answer>", "\n\n", true);
    StreamProtocolFilterState empty;
    empty.reset({});
    const auto empty_result = filter_stream_protocol(empty, "<answer>\n\n</answer>", true);
    assert(!mca::mnn::detail::has_non_whitespace(empty_result.visible));

    StreamProtocolFilterState offset;
    offset.reset({});
    const std::string with_literal = "<answer>Use <response>x</response> here.</answer>";
    filter_stream_protocol(offset, with_literal, true);
    assert(offset.stop_marker_offset == with_literal.rfind("</answer>"));

    return 0;
}
