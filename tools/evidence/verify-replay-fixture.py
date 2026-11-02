"""Independent verification of the controlled-replay fixture expectations.

This is NOT a replacement for the Kotlin driver (ControlledReplayFixtureTest), which runs the real
production ChatStreamAccumulator. It exists so the fixture DATA is provably self-consistent even
while the app module cannot be compiled because another workstream is mid-refactor:

- materialization of `text` / `blocks.repeat` events matches the declared UTF-16 length and SHA-256;
- `finalText` is the concatenation of text events of the newest run only;
- late chunks after a terminal event are excluded;
- the manifest digest matches the fixture bytes and every required case is present.

Exit code: 0 = every check passed, 1 = a check failed.
"""

import hashlib
import json
import sys

FIXTURE_DIR = r"E:\MAC\benchmark\src\main\assets\controlled-replay"


def materialize(event):
    if "text" in event:
        return event["text"]
    out = []
    for block in event.get("blocks", []):
        if block["kind"] == "repeat":
            out.append(block["unit"] * block["count"])
        else:
            raise AssertionError(f"unknown block kind {block['kind']}")
    return "".join(out)


def utf16_len(text):
    return len(text.encode("utf-16-le")) // 2


def run_case(case):
    failures = []
    expect = case["expect"]
    run = None
    fixture_run = case["events"][0].get("run", 1) if case["events"] else 1
    body = []
    terminal_events = 0
    terminals = 0
    cancels = 0
    late = []
    terminal_seen = False
    seen_runs = {fixture_run}

    for event in case["events"]:
        kind = event["type"]
        declared = event.get("run", fixture_run)
        if declared != fixture_run:
            fixture_run = declared
            seen_runs.add(declared)
            body = []
            terminal_seen = False
        if kind == "text":
            if event.get("late", False) or terminal_seen:
                late.append(materialize(event))
            else:
                body.append(materialize(event))
        elif kind == "reasoning":
            pass
        elif kind in ("stats", "tool"):
            pass
        elif kind == "terminal":
            terminal_events += 1
            if not terminal_seen:
                terminals += 1
                terminal_seen = True
        elif kind == "cancel":
            cancels += 1
        else:
            raise AssertionError(f"unknown event type {kind}")

    answer = "".join(body)
    answer_sha = hashlib.sha256(answer.encode("utf-8")).hexdigest()

    if len(case["events"]) != expect["eventCount"]:
        failures.append(f"eventCount {len(case['events'])} != {expect['eventCount']}")
    if terminal_events != expect["terminalCount"]:
        failures.append(f"terminalCount {terminal_events} != {expect['terminalCount']}")
    if cancels != expect["cancelCount"]:
        failures.append(f"cancelCount {cancels} != {expect['cancelCount']}")
    if len(late) != expect["lateTextCount"]:
        failures.append(f"lateTextCount {len(late)} != {expect['lateTextCount']}")
    if terminals != 1:
        failures.append(f"settlements {terminals} != 1")
    if "runs" in expect and len(seen_runs) != expect["runs"]:
        failures.append(f"runs {len(seen_runs)} != {expect['runs']}")
    if expect.get("lateText") and len(late) == 1 and late[0] != expect["lateText"]:
        failures.append("late chunk text differs")
    if utf16_len(answer) != expect["finalTextLength"]:
        failures.append(f"finalTextLength {utf16_len(answer)} != {expect['finalTextLength']}")
    if answer_sha != expect["finalTextSha256"]:
        failures.append(f"finalTextSha256 {answer_sha} != {expect['finalTextSha256']}")
    if "finalText" in expect and answer != expect["finalText"]:
        failures.append("finalText differs")
    if "minChars" in expect and utf16_len(answer) < expect["minChars"]:
        failures.append("stress body shrank")
    return failures


def main():
    with open(f"{FIXTURE_DIR}\\manifest.json", encoding="utf-8") as fh:
        manifest = json.load(fh)
    with open(f"{FIXTURE_DIR}\\{manifest['fixture']}", "rb") as fh:
        fixture_bytes = fh.read()
    fixture = json.loads(fixture_bytes.decode("utf-8"))

    problems = []
    digest = hashlib.sha256(fixture_bytes).hexdigest()
    if digest != manifest["fixtureSha256"]:
        problems.append(f"manifest digest {manifest['fixtureSha256']} != {digest}")
    if manifest["caseCount"] != len(fixture["cases"]):
        problems.append("manifest caseCount mismatch")
    if manifest["chain"]["status"] != "BLOCKED":
        problems.append("chain status must stay BLOCKED while no driver exists")
    ids = [case["id"] for case in fixture["cases"]]
    for required in fixture["requiredCases"]:
        if required not in ids:
            problems.append(f"required case missing: {required}")

    for case in fixture["cases"]:
        failures = run_case(case)
        if failures:
            problems.append(f"{case['id']}: " + "; ".join(failures))
        else:
            print(f"PASS  {case['id']}  len={case['expect']['finalTextLength']}")

    print()
    if problems:
        for problem in problems:
            print(f"FAIL  {problem}")
        return 1
    print(f"OK: {len(fixture['cases'])} cases match their declared length and SHA-256")
    return 0


if __name__ == "__main__":
    sys.exit(main())
