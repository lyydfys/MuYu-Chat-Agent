# Dependency verification — agent-v1 (baseline 5e912ff9)

- **Baseline:** `5e912ff9ac7bf78f1ed1f452657da642cfbdfead` (`git rev-parse HEAD`, 2026-10-19T02:05+08:00). Dirty submodule `third_party/tokenizers-cpp` and unrelated working-tree edits pre-existed and are out of scope.
- **Scope:** read-only verification (no Gradle/build executed). The only file written by this task is this document.
- **Verification window:** 2026-10-19T02:05–02:15 +08:00 (Asia/Shanghai). The fetch tool does not return per-response clocks; every URL below was fetched inside that window. Quoted timestamps such as `2026-10-17 23:11 UTC` are **server-side values from the payload**, not local clock reads.
- **Observed working tree (not edited by this agent):** at 02:09:09 +08:00 the concurrent implementer had modified `gradle/libs.versions.toml`, `settings.gradle.kts`, `app/build.gradle.kts` and added untracked `core/agent-contract/`, `core/agent-runtime/`, `feature/agent-workspace/`. Catalog/line citations below are from that observed state; the pinned versions are unchanged from baseline (`coroutines = "1.9.0"`).

## 1. com.fasterxml.jackson.core:jackson-core:2.22.4 — **VERIFIED**

| Fact | Value | Source (accessed 2026-10-19) |
|---|---|---|
| Exists on Maven Central | Yes; metadata `<release>2.22.4</release>`, `<latest>2.22.4</latest>`, `lastUpdated=20261017231257` (= 2026-10-17T23:12:57Z) | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/maven-metadata.xml |
| Publication timestamp | All artifacts listed at **2026-10-17 23:11 UTC**; `project.build.outputTimestamp=2026-10-17T23:03:42Z` in POM | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/ , https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.pom |
| Exact POM dependencies | **None.** The POM has no `<dependencies>` element. Its only Gradle-level edges are the `jackson-bom:2.22.4` platform in both `apiElements`/`runtimeElements` (`endorseStrictVersions=true`); `fastdoubleparser` is shaded into the jar and removed from Gradle metadata (`gradle-module-metadata-maven-plugin` `removedDependencies`). No transitive library dependency. | POM above; https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.module ; README "Package has no external dependencies, except for testing" https://raw.githubusercontent.com/FasterXML/jackson-core/2.22/README.md |
| License | Apache License 2.0 (`The Apache Software License, Version 2.0`) | POM; README "licensed under Apache License 2.0" |
| maven.compiler / minimum Java | Leaf POM declares no compiler props; parent `jackson-parent:2.22` sets `maven.compiler.source=1.8`, `maven.compiler.target=1.8`. Upstream README: "Versions 2.14 and above require JDK 8". | https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-parent/2.22/jackson-parent-2.22.pom ; README §Compatibility/JDK |
| Android baseline | Upstream README §Compatibility/Android: "2.14 and above: Android SDK 26+". MCA `minSdk = 26` (catalog line 6) satisfies this. POM also runs animal-sniffer against `com.toasttab.android:gummy-bears-api-26`. | README; https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.pom |
| jar sha256 | `2d30c375cddea9657a1e1ef97547a2694b663b355facb4de53a097782b312d1a` (599,548 bytes; sha1 `d753ae59bae7dcb183f856da541c5b27272088ce`; sha512 also published). Matches the `.sha256` file, the directory listing size, and the Gradle `.module` file record. | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.jar.sha256 ; `.module` |
| Release content | `2.22.4 (17-Oct-2026)` in upstream release notes: 7 fixes (#1683, #1730, #1742, #1744, #1746, #1750, #1756), no API breaks listed. No GitHub Release object exists for this tag (`api.github.com` → 404); release notes file is authoritative. | https://raw.githubusercontent.com/FasterXML/jackson-core/2.22/release-notes/VERSION-2.x |
| Android/R8 caveats stated upstream | (a) API 26+ baseline above. (b) Fixed Android packaging defect #1446 (`Invalid package reference to "java.lang.foreign"` from FastDoubleParser) shipped in 2.18.5 / 2.19.1, therefore contained in 2.22.4. (c) Jar is `Multi-Release: true` with `META-INF/versions/11,17,21,23` classes. Upstream states **no R8/ProGuard keep rule** for jackson-core; no reflection is used by the streaming API. Whether D8/R8 correctly strips the MR-JAR version dirs in this project is **UNVERIFIED** (build forbidden by task). | POM; release notes entries 2.18.5/2.19.1; README §Maven/Overview |

### Known CVEs affecting exactly 2.22.4

**NONE.** 14 NVD keyword hits for `jackson-core` were enumerated (NVD API snapshot `2026-10-18T18:08:02Z`, fetched 2026-10-19); none has an affected range covering 2.22.4. OSV/GHSA check on 2026-10-19 agrees.

| CVE / GHSA | Affected `com.fasterxml.jackson.core:jackson-core` range | 2.22.4 affected? |
|---|---|---|
| CVE-2025-49128 / GHSA-wf8f-6423-gfxg (memory disclosure, JsonLocation) | `< 2.13.0` | No |
| CVE-2025-52999 / GHSA-h46c-h94j-95f3 (nested-depth StackOverflow) | `< 2.15.0` | No |
| CVE-2026-18401 / GHSA-72hv-8253-57qq (async maxNumberLength bypass) | `2.15.0–2.18.5`, `2.19.0–<2.21.1` | No |
| CVE-2026-68494 (incomplete fix of 18401) | `2.15.0–<2.18.8`, `2.19.0–<2.21.4` | No |
| CVE-2026-68498 (maxNameLength on char input) | `2.16.0–<2.18.10`, `2.19.0–<2.21.6`, `2.22.0–<2.22.2` | No (fixed 2.22.2) |
| CVE-2026-89407 (regex backtracking, NumberInput) | `2.17.0–2.18.10`, `2.19.0–2.21.6`, `2.22.0–2.22.2` | No (fixed 2.22.3) |
| CVE-2026-89425 / GHSA-7hhh-6rmp-j9qf (DataInput error-token DoS) | `2.8.0–2.18.10`, `2.19.0–2.21.6`, `2.22.0–2.22.2` | No (fixed 2.22.3) |
| CVE-2026-29062 (maxNestingDepth, DataInput) | Jackson 3.x only: `3.0.0–<3.1.0` | No (different artifact) |

Other NVD hits are not this artifact: CVE-2026-68495/68496 (`jackson-dataformat-cbor/smile`), CVE-2026-68497 (`jackson-databind`), CVE-2026-86321/86511/86513 (`java-json-tools:jackson-coreutils`). **Operational consequence: do not pin 2.22.0–2.22.2** — that range is exposed to three of the CVEs above; 2.22.4 is the fixed patch line. Residual risk: an advisory published after 2026-10-18T18:08Z cannot be covered by this snapshot; re-check NVD/OSV before merge.

## 2. org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0 — **VERIFIED**

- Catalog version is already pinned: `coroutines = "1.9.0"` (`gradle/libs.versions.toml:13`; read 02:05+08:00). Working tree now adds `kotlinx-coroutines-core` and `kotlinx-coroutines-test` aliases using the same `version.ref = "coroutines"` (lines 68–69), and `jackson-core` (line 66) with `jackson = "2.22.4"` (line 22).
- Maven Central: `kotlinx-coroutines-core` metadata lists `1.9.0` (latest is 1.11.0) — https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml, accessed 2026-10-19.
- Local Gradle cache (read-only, `C:\Users\sy427\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlinx\`): root module `kotlinx-coroutines-core\1.9.0\` has `.pom` (2,057 B) and `.module` (45,570 B); the actual JVM artifact is `kotlinx-coroutines-core-jvm\1.9.0\...jar` (1,463,587 B, plus pom/module). This split is expected for a KMP root module and does **not** mean coroutines-core is missing.
- `kotlinx-coroutines-android:1.9.0` **is already used and cached**: catalog entry (baseline line 62 / working-tree line 67) and `implementation(libs.kotlinx.coroutines.android)` in `app`, `api:local`, `core:engine`, `core:download`, `core:benchmark`, `feature:chat`, `feature:settings`, `feature:agent-workspace`; cache has the 1.9.0 jar (19,247 B) + pom/module.
- Conclusion: the new explicit `kotlinx-coroutines-core:1.9.0` coordinate resolves the same version already used by the project; no coroutines upgrade is implied or needed.

## 3. MCP 2025-06-18 Streamable HTTP transport — **VERIFIED (with revision-drift warning)**

- Canonical URL resolves HTTP 200 and renders "**Version 2025-06-18**": https://modelcontextprotocol.io/specification/2025-06-18/basic/transports (accessed 2026-10-19). Revision index: https://modelcontextprotocol.io/specification/2025-06-18 ; authoritative schema: https://github.com/modelcontextprotocol/specification/blob/main/schema/2025-06-18/schema.ts.
- **Revision status:** 2025-06-18 is NOT the latest revision. `https://modelcontextprotocol.io/specification` redirects to **2026-07-28 ("latest")**; an intermediate **2025-11-25** exists. The plan's line 780 ("protocol candidate fixed to MCP 2025-06-18") is therefore an intentional pin, not "current". Note for interop: 2025-11-25 renames the session header to **`MCP-Session-Id`**, while 2025-06-18 uses **`Mcp-Session-Id`** (HTTP field names are case-insensitive per RFC 9110 §5.1, but exact-string implementations may differ — locally UNVERIFIED).
- Plan's frozen subset (architecture doc lines 784–792) maps to spec sections as follows:

| Plan requirement | Spec anchor (2025-06-18) | Notes |
|---|---|---|
| Streamable HTTP; replaces 2024-11-05 HTTP+SSE; stdio not claimed | `#streamable-http` | Spec makes it the replacement transport; plan's stdio exclusion is a plan choice, not a spec prohibition |
| POST per JSON-RPC message; `Accept: application/json, text/event-stream` | `#sending-messages-to-the-server` items 1–2 | Both are literal MUSTs. Plan's `Content-Type: application/json` is standard practice but is not a literal MUST in this section |
| Notification/response accepted → HTTP **202** with empty body | `#sending-messages-to-the-server` item 4 | Matches plan line 786 "202 is not a tool result" |
| Request → server replies `application/json` one object **or** `text/event-stream`; client supports both | `#sending-messages-to-the-server` item 5 | Matches plan line 789 |
| `Mcp-Session-Id` returned on InitializeResult; echoed on all later requests; 404 ⇒ re-initialize; DELETE optional | `#session-management` items 1–5 | Matches plan line 788 (server MAY terminate → MUST 404; client MUST new initialize) |
| `MCP-Protocol-Version: 2025-06-18` on all subsequent HTTP requests; invalid ⇒ 400; absent ⇒ assume 2025-03-26 | `#protocol-version-header` | Matches plan line 788 |
| `capabilities.tools` required for tool servers; `listChanged` gates change notifications; `notifications/tools/list_changed` | `/server/tools#capabilities`, `/server/tools#list-changed-notification` | Matches plan line 787/791; notice is SHOULD, discovery is plan policy |
| SSE messages carry JSON-RPC; only complete events consumed; response matched by JSON-RPC `id` | `#listening-for-messages-from-the-server`, `#resumability-and-redelivery` | The `data:` field framing is defined by the referenced WHATWG SSE standard; 2025-06-18 does not restate it as an MCP MUST |
| JSON-RPC batching | Changelog major change 1: batching removed in 2025-06-18 | Plan is compatible; must not send batches |

## 4. Jackson Core parser flags relevant to the plan — **VERIFIED (one design gap)**

All line references are source at tag `jackson-core-2.22.4`; javadoc 2.22.4 was not yet uploaded to javadoc.io (only 2.22.3), so API text is cross-checked between tag source, 2.22.3 javadoc and the upstream README.

- **Duplicate keys:** `StreamReadFeature.STRICT_DUPLICATE_DETECTION` (maps to legacy `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`) — **disabled by default**, throws `JsonParseException` on duplicate field names, javadoc states ~20–30% parsing overhead; enable on the shared `JsonFactory` builder. Source: https://raw.githubusercontent.com/FasterXML/jackson-core/jackson-core-2.22.4/src/main/java/com/fasterxml/jackson/core/StreamReadFeature.java
- **Lenient constructs are rejected by default** (no action needed beyond not enabling them): `JsonReadFeature.ALLOW_JAVA_COMMENTS(false)`, `ALLOW_YAML_COMMENTS(false)`, `ALLOW_SINGLE_QUOTES(false)`, `ALLOW_UNQUOTED_FIELD_NAMES(false)`, `ALLOW_UNESCAPED_CONTROL_CHARS(false)`, `ALLOW_TRAILING_COMMA(false)`, `ALLOW_MISSING_VALUES(false)`. Source: https://raw.githubusercontent.com/FasterXML/jackson-core/jackson-core-2.22.4/src/main/java/com/fasterxml/jackson/core/json/JsonReadFeature.java
- **Trailing tokens — NO core flag exists.** `StreamReadFeature` contains no equivalent of databind's `FAIL_ON_TRAILING_TOKENS`; jackson-core is token-oriented and returns `null` from `JsonParser.nextToken()` at EOF. The strict-decode wrapper MUST therefore read the single root value and then require `nextToken() == null`, rejecting any remainder. Because the plan depends on core only (no databind), this must be implemented in `:core:agent-runtime` and covered by a fixture; it cannot be delegated to a Jackson flag. **Design gap to record, not a library defect.**
- **Depth/size limits:** `StreamReadConstraints` (since 2.15), configured per factory via `JsonFactory.builder().streamReadConstraints(...)`. Javadoc defaults: `maxNumberLength=1000`, `maxStringLength=20_000_000`, `maxNameLength=50_000`, `maxNestingDepth=1000`, `maxDocumentLength=-1 (unlimited)`, `maxTokenCount=-1 (unlimited, 2.18+)`; violations throw `StreamConstraintsException`. `maxDocumentLength` must be set explicitly by the plan (default is unlimited). Source: https://javadoc.io/static/com.fasterxml.jackson.core/jackson-core/2.22.3/com/fasterxml/jackson/core/StreamReadConstraints.html ; defaults also documented in README §Processing limits.

## 5. Recommendation — exact coordinates and declaration style

Catalog (`gradle/libs.versions.toml`) — current working-tree form is correct; keep only these three additions:

```toml
[versions]
jackson = "2.22.4"          # jackson-core only; no databind/BOM import
coroutines = "1.9.0"        # unchanged pin

[libraries]
jackson-core = { module = "com.fasterxml.jackson.core:jackson-core", version.ref = "jackson" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
```

- `:core:agent-runtime`: `implementation(libs.jackson.core)` (verified in working tree) — keeps `com.fasterxml.jackson.core` types out of exported signatures. Resolve `jackson-bom:2.22.4` implicitly (platform edge from Gradle metadata; POM verified present on Central) — do not add a catalog BOM entry.
- `:core:agent-contract` / `:core:agent-runtime`: `implementation(libs.kotlinx.coroutines.core)`; **review point:** if `Flow` appears in `:core:agent-contract` public signatures, change that module to `api(...)`, otherwise consumers must not rely on transitive exposure.
- **Network prerequisite:** local Gradle cache contains **no** `com.fasterxml.jackson*` artifacts. First build of the new modules requires Maven Central access for `jackson-core` 2.22.4 **and** `jackson-bom` 2.22.4. Mirrors/offline approvals must carry both; sha256 above is the integrity anchor.

**Fallback procedure if a check fails**

1. Version/artifact missing at resolution time → **BLOCKED**, do not substitute a lower version. 2.22.0–2.22.2 are CVE-affected and 2.21.x is below the fixed 2.22 line. Either restore Central access for the exact coordinates or escalate.
2. Checksum mismatch → discard the artifact, re-fetch; if it persists, **BLOCKED** and report the observed hash.
3. An advisory is published after 2026-10-18 covering 2.22.4 → pin the smallest fixed 2.22.x patch that exists, re-run items 1/4 for that exact version, and record the delta before merging.
4. If adding a third-party JSON dependency is rejected outright, the fallback is a hand-written strict JSON tokenizer in `:core:agent-runtime` (no new dependency, larger review surface, same strictness tests) — not `org.json`, not databind, not kotlinx-serialization.
5. MCP: treat 2025-06-18 as an explicit pinned revision. If a fixture server negotiates 2025-11-25/2026-07-28, accept the header spelling used by the negotiated revision and record the exact negotiated `protocolVersion`; a mismatch is a compatibility finding, not a silent fallback.

## Unresolved / UNVERIFIED (honest list)

- **R8/D8 behavior** on the multi-release jar and `META-INF/versions/23` stripping in this AGP 8.13.2/compileSdk 36 project — no build allowed; verify in the first real assemble.
- **Runtime parse behavior** (duplicate keys, trailing tokens, depth limits) — not exercised here; requires fixtures in `:core:agent-runtime` unit tests.
- **javadoc 2.22.4** not on javadoc.io at check time; 2.22.3 javadoc + 2.22.4 tag source were used, so API text is not version-exact for 2.22.3-only diffs.
- **search.maven.org Solr** returned `numFound: 0` for 2.22.4 at check time (index lag); this is not evidence of absence — repo1 metadata, POM, module metadata and `.sha256` are authoritative.
- **CVE completeness** is bounded by the NVD snapshot timestamp (2026-10-18T18:08Z) and OSV query on 2026-10-19.
- **MCP header-name casing** across revisions was not interoperably tested.

## Access log

| # | URL | HTTP | Accessed (2026-10-19, +08:00) |
|---|---|---|---|
| 1 | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/maven-metadata.xml | 200 | 02:05–02:15 window |
| 2 | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/ | 200 | 02:05–02:15 window |
| 3 | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.pom | 200 | 02:05–02:15 window |
| 4 | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.module | 200 | 02:05–02:15 window |
| 5 | https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.jar.sha256 | 200 | 02:05–02:15 window |
| 6 | https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-base/2.22.4/jackson-base-2.22.4.pom | 200 | 02:05–02:15 window |
| 7 | https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-bom/2.22.4/jackson-bom-2.22.4.pom | 200 | 02:05–02:15 window |
| 8 | https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-parent/2.22/jackson-parent-2.22.pom | 200 | 02:05–02:15 window |
| 9 | https://raw.githubusercontent.com/FasterXML/jackson-core/2.22/README.md | 200 | 02:05–02:15 window |
| 10 | https://raw.githubusercontent.com/FasterXML/jackson-core/2.22/release-notes/VERSION-2.x | 200 | 02:05–02:15 window |
| 11 | https://raw.githubusercontent.com/FasterXML/jackson-core/jackson-core-2.22.4/.../StreamReadFeature.java , .../json/JsonReadFeature.java | 200 | 02:05–02:15 window |
| 12 | https://javadoc.io/static/com.fasterxml.jackson.core/jackson-core/2.22.3/com/fasterxml/jackson/core/StreamReadConstraints.html | 200 | 02:05–02:15 window |
| 13 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml | 200 | 02:05–02:15 window |
| 14 | https://modelcontextprotocol.io/specification/2025-06-18/basic/transports (.md raw) | 200 | 02:05–02:15 window |
| 15 | https://modelcontextprotocol.io/specification/2025-06-18/server/tools (.md raw) | 200 | 02:05–02:15 window |
| 16 | https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle , .../2025-06-18/changelog | 200 | 02:05–02:15 window |
| 17 | https://modelcontextprotocol.io/specification (redirects to 2026-07-28 "latest") ; .../2025-11-25/basic/transports.md | 200 | 02:05–02:15 window |
| 18 | https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=jackson-core&resultsPerPage=50 | 200 | 02:05–02:15 window (NVD payload timestamp 2026-10-18T18:08:02Z) |
| 19 | https://api.osv.dev/v1/vulns/GHSA-72hv-8253-57qq , GHSA-7hhh-6rmp-j9qf , GHSA-h46c-h94j-95f3 , CVE-2025-49128 | 200 | 02:05–02:15 window |
