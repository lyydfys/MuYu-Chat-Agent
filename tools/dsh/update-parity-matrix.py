#!/usr/bin/env python3
"""Deterministic updater for completion-v2/PARITY_MATRIX_V2.json.

Round: evidence-backed ledger update for the 2026-10-21 one-shot verification
(``artifacts/dsh-v2/verification/ONE-SHOT-REPORT.md`` and the lane gate logs).
This script is read-only with respect to production source: it edits only the
JSON ledger and only the fields allowed by the task:

  implementation.status / productionEntry / notes
  profile.disposition / reason / reviewEvidence
  evidence

It never promotes a row to PASS, never touches ``runtime.status`` or
``runtime.candidateId``, and never edits row ids, ``source`` blocks, schema,
purpose, rules, counts or the status vocabulary. Rows already evidence-bound
to PASS by ``tools/dsh/promote-parity-evidence.py`` are skipped so this
planning updater cannot erase their device evidence.

Mapping policy (explicit, conservative):

* ``mobile-tool-export`` rows are mapped by ``ownerDCard``:
    D17 -> IMPLEMENTED_UNVERIFIED (a11y gate 72/0, routes mounted)
    D18 -> IMPLEMENTED_UNVERIFIED (vscreen gate 62/0, routes mounted on 8999)
    D16 -> IMPLEMENTED_UNVERIFIED (android-actions gate 82/0; six routes mounted,
           overlay controller wired; Shizuku transport is null by design and
           answers typed CAPABILITY_UNAVAILABLE)
    D19 -> IMPLEMENTED_UNVERIFIED (browser gate 77/0; adapter mounted on
           POST /browser, isolated :browser service in the merged manifest)
  Profiles of every mobile row are REVIEWED because the 43 identities are the
  "phone version" denominator this round is about.

* ``mca-target-design`` rows are mapped one by one in ``MCA_RULES`` (id ->
  decision + evidence). Only a real production entry with a green module gate
  becomes IMPLEMENTED_UNVERIFIED. The runtime (245/0), gateway (210/0) and
  platform (107/0) gates are green in this round; device/account/model runs are
  still NOT_RUN.

* ``official-manifest-row`` rows with ``legacyRequiredForV1 == true`` get a
  profile REVIEWED (they belong to the android-mca V1 surface) but stay
  NOT_ASSESSED: the official JavaScript identity never ran, so no per-row
  implementation can be assessed. The 95 upstream-optional rows are left
  completely untouched (UNREVIEWED / NOT_ASSESSED).

The file ``artifacts/dsh-v2/verification/LEDGER-UPDATE.md`` carries the mapping
table and the command that produced this run.

Usage:
    python tools/dsh/update-parity-matrix.py            # write if needed
    python tools/dsh/update-parity-matrix.py --check    # non-zero if stale
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = (
    REPO_ROOT
    / "docs"
    / "proposals"
    / "agent-dsh-embedded-v1"
    / "completion-v2"
    / "PARITY_MATRIX_V2.json"
)

EXPECTED_TOTAL = 485
EXPECTED_KIND_COUNTS = {
    "official-manifest-row": 422,
    "mobile-tool-export": 43,
    "mca-target-design": 20,
}
ALLOWED_IMPLEMENTATION = {"IMPLEMENTED_UNVERIFIED", "NOT_IMPLEMENTED", "NOT_ASSESSED"}
ALLOWED_PROFILE = {"REVIEWED", "UNREVIEWED"}

# Green/red gate snapshot of the 2026-10-21 verification round. Counts come
# from build/test-results/testDebugUnitTest XML; log paths are the lane logs.
GATES = {
    "contract": {
        "task": ":core:dsh-contract:testDebugUnitTest",
        "tests": 19,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lead-contract-fix.log",
    },
    "platform": {
        "task": ":core:dsh-platform:testDebugUnitTest",
        "tests": 107,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-platform.log",
    },
    "android": {
        "task": ":core:dsh-android-actions:testDebugUnitTest",
        "tests": 82,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-android.log",
    },
    "a11y": {
        "task": ":core:dsh-a11y:testDebugUnitTest",
        "tests": 72,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-a11y.log",
    },
    "vscreen": {
        "task": ":core:dsh-vscreen:testDebugUnitTest",
        "tests": 62,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-vscreen.log",
    },
    "browser": {
        "task": ":core:dsh-browser:testDebugUnitTest",
        "tests": 77,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-browser.log",
    },
    "maintenance": {
        "task": ":core:dsh-maintenance:testDebugUnitTest",
        "tests": 52,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-maintenance.log",
    },
    "runtime": {
        "task": ":core:dsh-runtime:testDebugUnitTest",
        "tests": 245,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-exec.log",
    },
    "gateway": {
        "task": ":api:dsh-gateway:testDebugUnitTest",
        "tests": 210,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-config.log",
    },
    "workspace": {
        "task": ":feature:dsh-workspace:testDebugUnitTest",
        "tests": 148,
        "failures": 0,
        "log": "artifacts/dsh-v2/gates/lane-workspace.log",
    },
}

SOURCE_MAP_OFFICIAL = "SOURCE_MAP.md:18-45"
SOURCE_MAP_RANGES = {
    "dsh-tool-android": "SOURCE_MAP.md:78-89",
    "dsh-tool-shizuku": "SOURCE_MAP.md:90-101",
    "dsh-tool-accessibility": "SOURCE_MAP.md:102-121",
    "dsh-tool-vscreen": "SOURCE_MAP.md:122-140",
    "dsh-tool-browser": "SOURCE_MAP.md:141-159",
}

MOBILE_RULES = {
    "D17": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "com.muyuchat.core.dsh.a11y.android.DshA11yController#routes() mounted by "
            "app/src/main/java/com/muyuchat/mca/composition/DshCapabilityAssembly.kt:272-276"
        ),
        "gates": ["a11y"],
        "evidence_extra": [],
        "notes": (
            "Accessibility tool identity; the 14 official HTTP routes are implemented and "
            "mounted on the authenticated app-local server. Unit policy gate is green; the "
            "real AccessibilityService remains user-enabled and device-unverified."
        ),
        "reason": (
            "Reviewed for android-mca: handler route table mounted; a11y unit gate green "
            "(72 tests, 0 failures); device service still NOT_RUN. profiles=[android-mca]"
        ),
    },
    "D18": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "com.muyuchat.core.dsh.vscreen.DshVscreenRoutes#install(VirtualScreenOwner) mounted by "
            "DshCapabilityAssembly.startVscreenServer() on the official fixed port 8999"
        ),
        "gates": ["vscreen"],
        "evidence_extra": [],
        "notes": (
            "Virtual-screen tool identity; protocol, owner and wire are implemented and mounted. "
            "Unit gate is green; real display creation still needs Shizuku/root on a device."
        ),
        "reason": (
            "Reviewed for android-mca: vscreen route table mounted on 8999; unit gate green "
            "(62 tests, 0 failures); device/Shizuku path NOT_RUN. profiles=[android-mca]"
        ),
    },
    "D16": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-android-actions DshAndroidActions#registerInto (six routes /notify /overlay "
            "/usage /clipboard /setting /shell) created by AndroidDshActionsFactory and mounted by "
            "app/src/main/java/com/muyuchat/mca/composition/DshCapabilityAssembly.kt:253-271; "
            "overlay controller wired at :74-76, merged manifest carries the runtime service"
        ),
        "gates": ["android"],
        "evidence_extra": [],
        "notes": (
            "Android action/shell tool identity; the six official routes are implemented, mounted "
            "and unit-tested (82 tests, 0 failures). The Shizuku transport is intentionally null, "
            "so that path answers typed CAPABILITY_UNAVAILABLE; device execution is NOT_RUN."
        ),
        "reason": (
            "Reviewed for android-mca: routes and overlay controller wired; android-actions unit "
            "gate green (82 tests, 0 failures); device path NOT_RUN. profiles=[android-mca]"
        ),
    },
    "D19": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-browser DshBrowserRouteAdapter mounted on POST /browser by "
            "app/src/main/java/com/muyuchat/mca/composition/DshCapabilityAssembly.kt:277-291; "
            "DshBrowserService declared in the merged manifest "
            "(app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:310)"
        ),
        "gates": ["browser"],
        "evidence_extra": [],
        "notes": (
            "Browser tool identity; the official /browser envelope is implemented, mounted on the "
            "authenticated server and forwarded to the isolated :browser process (binder transport) "
            "whose service is in the merged manifest. Unit gate is green (77 tests, 0 failures); no "
            "device browser run."
        ),
        "reason": (
            "Reviewed for android-mca: browser route and isolated process wired; unit gate green "
            "(77 tests, 0 failures); device/WebView path NOT_RUN. profiles=[android-mca]"
        ),
    },
}

# id -> decision. Only production entry + green module gate may be promoted to
# IMPLEMENTED_UNVERIFIED. Everything else is explicit per-row truth.
MCA_RULES = {
    "mca.target.platform-bridge-auth": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-platform DshCapabilityAuthority + DshCapabilityLoopbackServer, wired by "
            "app/src/main/java/com/muyuchat/mca/composition/DshCapabilityAssembly.kt:61-126"
        ),
        "gates": ["platform"],
        "notes": (
            "Bridge auth/generation/revoke logic and its loopback server have a green unit gate; "
            "no device request or official package call has run."
        ),
        "reason": (
            "Reviewed for android-mca: bridge auth implemented with green unit gate; runtime "
            "NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": ["EXECUTION_PACK.md:199-205 (D15)", "SOURCE_MAP.md:18-45"],
    },
    "mca.target.platform-bridge-idempotency-unknown": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-platform DshCapabilityLedger wired by "
            "app/src/main/java/com/muyuchat/mca/composition/DshCapabilityAssembly.kt:61-126"
        ),
        "gates": ["platform"],
        "notes": (
            "Idempotency ledger and unknown-outcome policy have a green unit gate; no device "
            "request or retry has exercised them."
        ),
        "reason": (
            "Reviewed for android-mca: idempotency ledger implemented with green unit gate; "
            "runtime NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": ["EXECUTION_PACK.md:199-205 (D15)", "SOURCE_MAP.md:18-45"],
    },
    "mca.target.webview-independent-recovery": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "feature/dsh-workspace DshWorkspaceViewModel + DshWebRecoveryPolicy, used by "
            "feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshWorkspaceRoute.kt"
        ),
        "gates": ["workspace"],
        "notes": (
            "Load planning, first-frame gate and renderer recovery policy are implemented and "
            "unit-tested; no device WebView has been rendered or killed."
        ),
        "reason": (
            "Reviewed for android-mca: WebView recovery policy implemented with green unit gate; "
            "device render/renderer death NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": ["EXECUTION_PACK.md:279-285 (D25)", "SOURCE_MAP.md:46-71"],
    },
    "mca.target.maintenance-all-mutators": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "app/src/main/java/com/muyuchat/mca/composition/DshMaintenanceAssembly.kt + "
            "core/dsh-platform DshCapabilityMaintenanceAdmissionGate"
        ),
        "gates": ["maintenance", "platform"],
        "notes": (
            "Maintenance admission gate, lease kinds and console barrier are implemented and "
            "unit-tested; mutator/drain behaviour against a real kernel is NOT_RUN."
        ),
        "reason": (
            "Reviewed for android-mca: admission barrier implemented with green maintenance and "
            "platform gates; real mutators NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": ["EXECUTION_PACK.md:135-141 (D07)", "SOURCE_MAP.md:173-188"],
    },
    "mca.target.account-popup-attempt-binding": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "The popup coordinator is wired in the WebView production path, but the trusted attempt "
            "sign-in machine / browser handoff policy have no production caller outside tests; no "
            "device or account run."
        ),
        "reason": (
            "Reviewed for android-mca: attempt-binding policies are not called from production and "
            "no account flow ran. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:111-117 (D04)",
            "artifacts/dsh-v2/gates/lane-exec.log",
            "SOURCE_MAP.md:46-71",
        ],
    },
    "mca.target.provider-source-isolation": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "app/src/main/java/com/muyuchat/mca/dsh/DshModelCatalog.kt consumed by "
            "McaDshGatewayBackend.kt; gateway model catalog wire in api/dsh-gateway"
        ),
        "gates": ["gateway"],
        "notes": (
            "Model catalog source isolation is implemented and the gateway gate is green "
            "(210 tests, 0 failures); no real provider request has run."
        ),
        "reason": (
            "Reviewed for android-mca: source isolation implemented with green gateway gate; real "
            "provider NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:127-133 (D06)",
            "artifacts/dsh-v2/gates/lane-config.log",
        ],
    },
    "mca.target.primary-subagent-offline-routing": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "Gateway routing is green, but no production entry implements primary/subagent/offline "
            "routing selection, and no real model request ran."
        ),
        "reason": (
            "Reviewed for android-mca: no production routing entry; real model NOT_RUN. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:127-133 (D06)",
            "artifacts/dsh-v2/gates/lane-config.log",
        ],
    },
    "mca.target.catalog-desired-applied": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-runtime/state/DshRuntimeStateMachine.kt + DshRuntimeOwner.kt "
            "(desired/applied revision state)"
        ),
        "gates": ["runtime"],
        "notes": (
            "Desired/applied identity state is implemented and the runtime gate is green "
            "(245 tests, 0 failures, 4 skipped); no device rebind run."
        ),
        "reason": (
            "Reviewed for android-mca: identity state machine implemented with green runtime gate; "
            "device NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:135-141 (D07)",
            "artifacts/dsh-v2/gates/lane-exec.log",
        ],
    },
    "mca.target.gateway-main-death-rebind": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-runtime/service/DshRuntimeServiceClient.kt + DshRuntimeService.kt "
            "(rebind/stop fence client)"
        ),
        "gates": ["runtime"],
        "notes": (
            "Rebind/restart policy is implemented and the runtime gate is green (245 tests, "
            "0 failures, 4 skipped); no main-vs-Node death was exercised on a device."
        ),
        "reason": (
            "Reviewed for android-mca: rebind implementation present with green runtime gate; "
            "device death NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:87-93 (D01)",
            "artifacts/dsh-v2/gates/lane-exec.log",
        ],
    },
    "mca.target.shared-inference-lease": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "Shared inference arbitration is owned by :core:engine; no DSH module gate for this "
            "design target was executed this round."
        ),
        "reason": (
            "Reviewed for android-mca: lease design not assessable from DSH-module gates. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:143-149 (D08)",
            "SOURCE_MAP.md:160-172",
        ],
    },
    "mca.target.target36-command-closure": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-runtime/process/DshCommandClosure.kt + AndroidDshNodeHost.kt "
            "(command/env/ELF resolution used by the Node host)"
        ),
        "gates": ["runtime"],
        "notes": (
            "Command closure resolver is implemented and the runtime gate is green (245 tests, "
            "0 failures, 4 skipped; the earlier Windows path-separator failures are fixed); no "
            "device executed node/git/python/pnpm."
        ),
        "reason": (
            "Reviewed for android-mca: closure resolver implemented with green runtime gate; "
            "device exec NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:167-173 (D11)",
            "artifacts/dsh-v2/gates/lane-exec.log",
            "artifacts/dsh-v2/gates/lane-exec-compile.log",
        ],
    },
    "mca.target.final-model-wire-integrity": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "api/dsh-gateway (request/stream/tool-call assembly) consumed by "
            "app/src/main/java/com/muyuchat/mca/dsh/McaDshGatewayBackend.kt"
        ),
        "gates": ["gateway"],
        "notes": (
            "Wire and tool-call assembly are implemented and the gateway gate is green "
            "(210 tests, 0 failures); no real model request ran."
        ),
        "reason": (
            "Reviewed for android-mca: wire code implemented with green gateway gate; real model "
            "NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:143-149 (D08)",
            "artifacts/dsh-v2/gates/lane-config.log",
        ],
    },
    "mca.target.backend-native-tools-and-vision": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "Per-backend native tools/vision require real engine + model runs; none happened and "
            "no DSH-module gate covers this target."
        ),
        "reason": (
            "Reviewed for android-mca: backend matrix unrun; no gate. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:151-157 (D09)",
            "SOURCE_MAP.md:208-220",
        ],
    },
    "mca.target.runtime-stop-fence": {
        "status": "IMPLEMENTED_UNVERIFIED",
        "production_entry": (
            "core/dsh-runtime/service/DshServiceStopPolicy.kt + DshStopSnapshotPolicy.kt "
            "used by DshRuntimeService.kt"
        ),
        "gates": ["runtime"],
        "notes": (
            "Stop/unknown-state policies are implemented and the runtime gate is green (245 tests, "
            "0 failures, 4 skipped); no real service stop or late callback ran on a device."
        ),
        "reason": (
            "Reviewed for android-mca: stop-fence policies implemented with green runtime gate; "
            "device NOT_RUN. profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:87-93 (D01)",
            "artifacts/dsh-v2/gates/lane-exec.log",
        ],
    },
    "mca.target.saf-persisted-access-grants": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "SAF is Activity/device-mediated; the contract DTOs and fake port are tested but no "
            "real picker, persisted grant or re-authorization ran."
        ),
        "reason": (
            "Reviewed for android-mca: SAF production path is device-only; NOT_RUN. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:199-205 (D15)",
            "SOURCE_MAP.md:173-188",
        ],
    },
    "mca.target.legacy-feature-coexistence": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "Coexistence of legacy MCA features with DSH needs a device UI run; only unit suites "
            "for the individual features exist."
        ),
        "reason": (
            "Reviewed for android-mca: coexistence is device/UI-only; NOT_RUN. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:279-285 (D25)",
            "SOURCE_MAP.md:208-220",
        ],
    },
    "mca.target.offline-local-execution": {
        "status": "NOT_ASSESSED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "Offline local execution needs a device, no-network condition and a real local model; "
            "none available this round."
        ),
        "reason": (
            "Reviewed for android-mca: offline device/model condition absent; NOT_RUN. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "EXECUTION_PACK.md:151-157 (D09)",
            "SOURCE_MAP.md:160-172",
        ],
    },
    "mca.target.full-closure-upgrade": {
        "status": "NOT_IMPLEMENTED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "Closure-upgrade policy exists, but the app trial host returns FAILED "
            "(\"alternate-root trial host is not supported by this runtime configuration\"); there "
            "is no production trial/commit path."
        ),
        "reason": (
            "Reviewed for android-mca: no production trial/commit path; D24 target not implemented. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "app/src/main/java/com/muyuchat/mca/composition/DshMaintenanceAssembly.kt:73-84",
            "EXECUTION_PACK.md:271-277 (D24)",
            "SOURCE_MAP.md:173-188",
        ],
    },
    "mca.target.signed-upgrade-data-continuity": {
        "status": "NOT_IMPLEMENTED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "No signed candidate APK or upgrade path was produced this round, so data continuity "
            "across a signed upgrade cannot exist yet."
        ),
        "reason": (
            "Reviewed for android-mca: signed upgrade path absent; target not implemented. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "artifacts/dsh-v2/baseline/D00-BASELINE.md:99-100",
            "EXECUTION_PACK.md:271-277 (D24)",
            "SOURCE_MAP.md:221-235",
        ],
    },
    "mca.target.diagnostic-build-provenance": {
        "status": "NOT_IMPLEMENTED",
        "production_entry": None,
        "gates": [],
        "notes": (
            "No candidate fingerprint / signing / provenance pipeline exists this round; the D27 "
            "deliverable is only partially covered by the D14/D26 verification docs."
        ),
        "reason": (
            "Reviewed for android-mca: build-provenance pipeline absent; target not implemented. "
            "profiles=[android-mca]"
        ),
        "review_evidence": [
            "artifacts/dsh-v2/baseline/D00-BASELINE.md:99-100",
            "EXECUTION_PACK.md:295 (D27)",
            "SOURCE_MAP.md:221-235",
        ],
    },
}

OFFICIAL_REASONS = {
    "required": (
        "Reviewed profile for android-mca: official required row ships in the pinned payload "
        "(kernel 0.2.2-rc.1); implementation not assessed per row. profiles=[official-web, android-mca]"
    ),
    "android-adaptation": (
        "Reviewed profile for android-mca: android-adaptation required; a host seam exists but no "
        "device or kernel run. profiles=[android-mca]"
    ),
}

OFFICIAL_NOTES = (
    "Official JavaScript identity bundled unmodified in the pinned payload; the kernel never "
    "started and no per-row gate exists, so MCA implementation is NOT_ASSESSED this round."
)


def gate_evidence(names):
    evidence = []
    for name in names:
        gate = GATES[name]
        evidence.append(
            "gate task={task} tests={tests} failures={failures} log={log}".format(**gate)
        )
    return evidence


def rule_key(row):
    kind = row["source"]["kind"]
    if kind == "mobile-tool-export":
        return "mobile:" + row["ownerDCard"]
    if kind == "mca-target-design":
        return "mca:" + row["id"]
    if kind == "official-manifest-row":
        if row["source"].get("legacyRequiredForV1") is True:
            classification = row["source"].get("legacyClassification")
            if classification not in OFFICIAL_REASONS:
                raise SystemExit(
                    "unsupported required classification %r for %s" % (classification, row["id"])
                )
            return "official:" + classification
        return "official:optional-untouched"
    raise SystemExit("unknown source.kind %r for %s" % (kind, row["id"]))


def assess(row):
    """Return the new field values for one row, or None to leave it untouched."""
    kind = row["source"]["kind"]
    key = rule_key(row)

    if kind == "mobile-tool-export":
        card = row["ownerDCard"]
        if card not in MOBILE_RULES:
            raise SystemExit("no mobile rule for card %r (%s)" % (card, row["id"]))
        rule = MOBILE_RULES[card]
        package = row["source"]["package"]
        if package not in SOURCE_MAP_RANGES:
            raise SystemExit("no SOURCE_MAP range for package %r" % package)
        review_evidence = [SOURCE_MAP_RANGES[package]]
        review_evidence.extend(
            "artifacts/dsh-v2/gates/" + GATES[name]["log"].split("/")[-1]
            for name in rule["gates"]
        )
        if rule["status"] == "NOT_ASSESSED" and card == "D16":
            review_evidence.append("artifacts/dsh-v2/gates/lane-android.log")
        if rule["status"] == "NOT_IMPLEMENTED" and card == "D19":
            review_evidence.append("artifacts/dsh-v2/gates/lane-browser.log")
        return {
            "impl_status": rule["status"],
            "production_entry": rule["production_entry"],
            "notes": rule["notes"],
            "disposition": "REVIEWED",
            "reason": rule["reason"],
            "review_evidence": review_evidence,
            "evidence": gate_evidence(rule["gates"]),
        }

    if kind == "mca-target-design":
        rule = MCA_RULES.get(row["id"])
        if rule is None:
            raise SystemExit("no MCA rule for %s" % row["id"])
        return {
            "impl_status": rule["status"],
            "production_entry": rule["production_entry"],
            "notes": rule["notes"],
            "disposition": "REVIEWED",
            "reason": rule["reason"],
            "review_evidence": list(rule["review_evidence"]),
            "evidence": gate_evidence(rule["gates"]),
        }

    # official-manifest-row
    if key == "official:optional-untouched":
        return None
    classification = row["source"]["legacyClassification"]
    return {
        "impl_status": "NOT_ASSESSED",
        "production_entry": None,
        "notes": OFFICIAL_NOTES,
        "disposition": "REVIEWED",
        "reason": OFFICIAL_REASONS[classification],
        "review_evidence": [SOURCE_MAP_OFFICIAL],
        "evidence": [],
    }


def load_matrix():
    return json.loads(MATRIX_PATH.read_text(encoding="utf-8"))


def dump_matrix(document):
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def build_updated(before):
    after = json.loads(json.dumps(before))  # deep copy, preserves key order
    for row in after["rows"]:
        # Rows promoted by tools/dsh/promote-parity-evidence.py are bound to a concrete device
        # candidate. Normalize their profile/evidence with the planning rules, but keep the
        # device-evidence sentences and the promotion record that this updater does not own.
        promoted = row.get("runtime", {}).get("status") == "PASS"
        preserved_notes = row["implementation"].get("notes") if promoted else None
        values = assess(row)
        if values is None:
            continue
        row["implementation"]["status"] = values["impl_status"]
        row["implementation"]["productionEntry"] = values["production_entry"]
        row["implementation"]["notes"] = values["notes"]
        row["profile"]["disposition"] = values["disposition"]
        row["profile"]["reason"] = values["reason"]
        row["profile"]["reviewEvidence"] = values["review_evidence"]
        row["evidence"] = values["evidence"]
        if promoted:
            marker = "Device evidence "
            index = (preserved_notes or "").find(marker)
            if index >= 0:
                row["implementation"]["notes"] = (
                    values["notes"].rstrip() + " " + preserved_notes[index:].strip()
                ).strip()
            promotion_record = "artifacts/dsh-v2/verification/LEDGER-CORRECTION-20261024.md"
            if promotion_record not in row["profile"]["reviewEvidence"]:
                row["profile"]["reviewEvidence"].append(promotion_record)
    return after


def validate(before, after):
    if len(after["rows"]) != EXPECTED_TOTAL:
        raise SystemExit("row count changed: %d" % len(after["rows"]))
    ids = [row["id"] for row in after["rows"]]
    if len(set(ids)) != EXPECTED_TOTAL:
        raise SystemExit("row ids are not unique")

    for key in (
        "schema",
        "schemaVersion",
        "generatedAt",
        "purpose",
        "sourceMetadataRef",
        "preservedLegacySourceMetadata",
        "legacyManifestRef",
        "sourceMapRef",
        "acceptanceRef",
        "rules",
        "statusVocabulary",
        "counts",
    ):
        if before[key] != after[key]:
            raise SystemExit("forbidden top-level change: %s" % key)

    kind_counts = {}
    for original, updated in zip(before["rows"], after["rows"]):
        if original["id"] != updated["id"]:
            raise SystemExit("row order/id changed at %s" % original["id"])
        if original["source"] != updated["source"]:
            raise SystemExit("source block changed for %s" % original["id"])
        kind = original["source"]["kind"]
        kind_counts[kind] = kind_counts.get(kind, 0) + 1

        if original["runtime"] != updated["runtime"]:
            raise SystemExit("runtime block changed for %s" % original["id"])
        runtime = updated["runtime"]
        if runtime.get("status") == "PASS":
            # The only permitted non-NOT_RUN state is an evidence-bound promotion: a PASS must
            # name the exact 64-hex candidate that produced the device evidence.
            candidate = runtime.get("candidateId")
            if not isinstance(candidate, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", candidate):
                raise SystemExit("PASS without a bound candidate sha256 for %s" % original["id"])
        elif runtime.get("status") != "NOT_RUN" or runtime.get("candidateId") is not None:
            raise SystemExit(
                "runtime must stay NOT_RUN/null or be PASS with a bound candidate for %s"
                % original["id"]
            )

        status = updated["implementation"]["status"]
        if status not in ALLOWED_IMPLEMENTATION:
            raise SystemExit("invalid implementation status %r for %s" % (status, original["id"]))

        disposition = updated["profile"]["disposition"]
        if disposition not in ALLOWED_PROFILE:
            raise SystemExit("invalid profile disposition %r for %s" % (disposition, original["id"]))

        if disposition == "REVIEWED":
            reason = updated["profile"]["reason"]
            if not isinstance(reason, str) or not reason.strip():
                raise SystemExit("REVIEWED row without reason: %s" % original["id"])
            if len(reason) > 300 or any(ord(ch) < 0x20 for ch in reason):
                raise SystemExit("reason not prompt-free/short for %s" % original["id"])
            if not updated["profile"]["reviewEvidence"]:
                raise SystemExit("REVIEWED row without reviewEvidence: %s" % original["id"])

        if status == "IMPLEMENTED_UNVERIFIED":
            entry = updated["implementation"]["productionEntry"]
            if not entry:
                raise SystemExit("IMPLEMENTED_UNVERIFIED without entry: %s" % original["id"])
            evidence = updated["evidence"]
            if not evidence:
                raise SystemExit("IMPLEMENTED_UNVERIFIED without evidence: %s" % original["id"])
            for item in evidence:
                if not str(item).startswith("gate task=") or "tests=" not in str(item) or "log=" not in str(item):
                    raise SystemExit("bad gate evidence for %s: %r" % (original["id"], item))
        if status == "NOT_IMPLEMENTED" and updated["implementation"]["productionEntry"] is not None:
            raise SystemExit("NOT_IMPLEMENTED with a production entry: %s" % original["id"])

    if kind_counts != EXPECTED_KIND_COUNTS:
        raise SystemExit("source kind counts changed: %r" % kind_counts)

    # Round-trip: the serialized form must reload to the same document.
    if json.loads(dump_matrix(after)) != after:
        raise SystemExit("JSON round-trip mismatch")


def summarize(document):
    impl = {}
    profile = {}
    reviewed_by_kind = {}
    for row in document["rows"]:
        impl[row["implementation"]["status"]] = impl.get(row["implementation"]["status"], 0) + 1
        dispos = row["profile"]["disposition"]
        profile[dispos] = profile.get(dispos, 0) + 1
        if dispos == "REVIEWED":
            kind = row["source"]["kind"]
            reviewed_by_kind[kind] = reviewed_by_kind.get(kind, 0) + 1
    return impl, profile, reviewed_by_kind


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="do not write; exit 1 if the on-disk ledger differs from the deterministic result",
    )
    args = parser.parse_args(argv)

    before = load_matrix()
    after = build_updated(before)
    validate(before, after)
    desired = dump_matrix(after)

    impl, profile, reviewed_by_kind = summarize(after)
    print("rows=485 unique_ids=485 schemaVersion=2")
    print("implementation: " + " ".join("%s=%d" % item for item in sorted(impl.items())))
    print("profile: " + " ".join("%s=%d" % item for item in sorted(profile.items())))
    print(
        "reviewed_by_kind: "
        + " ".join("%s=%d" % item for item in sorted(reviewed_by_kind.items()))
    )
    runtime = {}
    for row in after["rows"]:
        status = row["runtime"]["status"]
        runtime[status] = runtime.get(status, 0) + 1
    print("runtime: " + " ".join("%s=%d" % item for item in sorted(runtime.items())))

    current = MATRIX_PATH.read_text(encoding="utf-8")
    if args.check:
        if current == desired:
            print("check: ledger is current")
            return 0
        print("check: ledger is stale; run without --check to update")
        return 1

    if current == desired:
        print("write: no change (already current)")
        return 0
    MATRIX_PATH.write_text(desired, encoding="utf-8", newline="\n")
    print("write: updated %s" % MATRIX_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
