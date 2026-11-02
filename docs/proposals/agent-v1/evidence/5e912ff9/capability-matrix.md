# Capability Matrix — Agent V1 K00 evidence

- Baseline HEAD: `5e912ff9ac7bf78f1ed1f452657da642cfbdfead` (working dir `E:\MAC`).
- Method: read-only static source inspection on 2026-10-19. No Gradle, adb, native build, or git write was run; no production file was modified. Only file written: this one (`risk-register.md` beside it was not touched).
- Source-line evidence is pinned to this HEAD. Paths are repo-relative; `:N` = line at this HEAD.
- Status legend: `VERIFIED(source)` = proven by the cited line(s) at this HEAD; `NOT_RUN` = needs build/device/runtime and was not executed here; `UNKNOWN` = source is inconclusive or depends on external platform/provider behavior.
- Lead-provided context used only as context, not re-proven here: device `f9d98aa3` connected; JDK 21 required for full verification ([MCA_PROJECT_HARD_LOCK.md §11](../../../../architecture/MCA_PROJECT_HARD_LOCK.md), "当前完整验证需 JDK 21", :252).

## 1. Room: database, schema, migrations, migration test

| Claim | Status | Evidence |
| --- | --- | --- |
| Single chat DB class `McaRoomDatabase`; file name `mca.db` | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt:38, :52 |
| `@Database` version = 27 | VERIFIED(source) | ChatSessionStoreRoomDatabase.kt:33; app/schemas/com.muyuchat.mca.McaRoomDatabase/27.json:4 |
| 11 entities: chat_sessions, chat_messages, image_assets, file_assets, memories, assistant_memory_turns, assistants, knowledge_bases, knowledge_documents, knowledge_chunks, chat_knowledge_base_bindings | VERIFIED(source) | ChatSessionStoreRoomDatabase.kt:20-32; 27.json `"tableName"` at :8, :92, :215, :343, :448, :525, :593, :711, :775, :874, :972 |
| `exportSchema = true`; export dir configured for KSP | VERIFIED(source) | ChatSessionStoreRoomDatabase.kt:36; app/build.gradle.kts:515 (`room.schemaLocation = file("schemas")`) |
| Exported schema artifact is 27.json only (plus unrelated `ModelRuntimeProfileDatabase/1.json`); identityHash `8f79b14f8fd663f5c85301a53e254d5f` | VERIFIED(source) | app/schemas/com.muyuchat.mca.McaRoomDatabase/27.json:1-6; directory contains no 28.json |
| Migration registration has one site: `.addMigrations(...)` chain in the `Room.databaseBuilder` | VERIFIED(source) | ChatSessionStoreRoomDatabase.kt:49-73 (chains :54-55, :56-72, build :73) |
| 26 `Migration` objects cover 1→27, including multi-hop 2/3/4/5→6; 23→27 are `internal` (test-visible) | VERIFIED(source) | ChatSessionStoreRoomDatabase.kt:95-294; internal at :248 (23→24), :256 (24→25), :263 (25→26), :288 (26→27) |
| Migration column/table helper functions live in a separate file that explicitly registers nothing | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/ChatSessionStoreColumnMigrations.kt:11-16 |
| `fallbackToDestructiveMigration` (and any fallback variant) absent repo-wide; builder ends at `.build()` | VERIFIED(source) | no match for `fallbackToDestructiveMigration` in `*.kt`; ChatSessionStoreRoomDatabase.kt:73; second DB also plain `.build()` at ModelRuntimeProfileDatabase.kt:134-141 |
| Pre-Room legacy repair runs before the builder: opens `mca.db` with framework `SQLiteDatabase`, mutates tables when `db.version < 14` | VERIFIED(source) | ChatSessionStoreRoomDatabase.kt:48, :77-93 |
| Migration test is a single instrumentation test; `FrameworkSQLiteOpenHelperFactory` with callback version 23, minimal v23 `chat_sessions`/`chat_messages`, `onUpgrade` = error | VERIFIED(source) | app/src/androidTest/java/com/muyuchat/mca/ChatHistoryMigrationTest.kt:14-38 |
| The test invokes only `MIGRATION_23_24` and `MIGRATION_24_25` manually, then asserts content preserved, backfilled `messageId = "session:0:123"`, defaults | VERIFIED(source) | ChatHistoryMigrationTest.kt:45-46, :48-68 |
| Test coverage gap: 25→26 and 26→27 untested; Room never opened; exported 27.json never compared; no `MigrationTestHelper`; `androidx.room:room-testing` absent | VERIFIED(source) | ChatHistoryMigrationTest.kt:19-46; no `MigrationTestHelper`/`room-testing` match in repo; app/build.gradle.kts:495-499 lists only compose/ui-test/runner/ext-junit/uiautomator |
| In-memory Room test exercises the current schema (not migrations) | VERIFIED(source) | app/src/androidTest/java/com/muyuchat/mca/AssistantMemoryRoomTest.kt:20 |
| Running the migration test | NOT_RUN | androidTest is not part of `scripts/verify-code.ps1` (only `testDebugUnitTest` tasks, e.g. :177-179, :210-273); needs a connected device |
| Second DB `mca-runtime.db` is version 1, no migrations, excluded from backup | VERIFIED(source) | ModelRuntimeProfileDatabase.kt:109-141; app/src/main/res/xml/backup_rules.xml:4-7 |

## 2. Backup and device transfer

| Claim | Status | Evidence |
| --- | --- | --- |
| `android:allowBackup="true"`, wired to both `fullBackupContent` and `dataExtractionRules` | VERIFIED(source) | app/src/main/AndroidManifest.xml:18, :19, :21 |
| `backup_rules.xml` excludes `mca.db` + `-wal/-shm/-journal` and `mca-runtime.db*` | VERIFIED(source) | backup_rules.xml:4-12 |
| Excludes file domains: image_assets, image_execution_journal, image_generation_inputs, image_loras, image_upscalers, image_models (file+external), chat_sessions.json | VERIFIED(source) | backup_rules.xml:14-21 |
| Excludes sharedprefs incl. `mca_api.xml` and `mca_cloud_api.xml` (credentials) and web-search/local-image preference files | VERIFIED(source) | backup_rules.xml:22-32 |
| `data_extraction_rules.xml` repeats the same 26 exclusions under both `<cloud-backup>` and `<device-transfer>` | VERIFIED(source) | app/src/main/res/xml/data_extraction_rules.xml:3-30, :31-58 |
| No Agent workspace path (`files/agent/...`) is excluded yet; rules enumerate existing paths only | VERIFIED(source) | backup_rules.xml:1-33; data_extraction_rules.xml:1-59 (no agent entry) |
| Whether a future `files/agent` tree would actually be backed up/transferred, and whether both rule files stay in sync | UNKNOWN / NOT_RUN | Requires editing both files + on-device backup test; no source evidence yet |

## 3. Cloud credentials and cloud model configuration

| Claim | Status | Evidence |
| --- | --- | --- |
| Cloud config + credential prefs file is `mca_cloud_api` (private); instance created in app container | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/CloudChatProvider.kt:215-216; app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt:176 |
| API key at rest: AES/GCM/NoPadding, AndroidKeyStore alias `mca_cloud_api_key`, 128-bit GCM tag, randomized encryption required | VERIFIED(source) | CloudChatProvider.kt:491-497, :499-507, :509-524, constants :547-549 |
| Stored as base64 ciphertext + IV; plaintext read only for legacy migration; key write is verify-then-retire (cipher/IV written, read back must equal input, then legacy plaintext removed) | VERIFIED(source) | CloudChatProvider.kt:435-449, :460-489; CredentialStoragePolicy.kt:11-14 |
| Typed save outcomes: Stored / Cleared / EncryptionUnavailable / VerificationFailed; encryption failure never falls back to plaintext | VERIFIED(source) | CredentialStoragePolicy.kt:16-30, :40-51, :62-81; CloudChatProvider.kt:274, :460-489 |
| Entire cloud model list persists as JSON in prefs key `cloud_models_json`; each record stores `apiKeyCipher`/`apiKeyIv`, protocol, baseUrl, model, supportsVision/Tools, reasoning flag | VERIFIED(source) | CloudChatProvider.kt:277-332, :371-422, :540 |
| Selected backend and selected cloud chat/image model IDs persist in same prefs | VERIFIED(source) | CloudChatProvider.kt:339-359, :541-543 |
| `supportsTools` default false at `CloudApiConfig`, `CloudModelRecord`, prefs load and JSON parse; coerced false for non-CHAT kind | VERIFIED(source) | CloudChatProvider.kt:120, :160, :189, :207, :236, :392, :415 |
| Room holds no credential column/table: chat_sessions stores only `modelMode`/`modelId`; the 11-entity list has no key/config entity | VERIFIED(source) | ChatSessionStoreEntities.kt:17-32; ChatSessionStoreRoomDatabase.kt:20-32 |
| Local API key is different: generated plaintext `mca-` + 24 chars in `mca_api` SharedPreferences (not Keystore AES-GCM); file excluded from backup | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/GenerationPreferenceStore.kt:35, :55-63; backup_rules.xml:23 |
| Generic Keystore-backed "credential reference" for MCP/Agent secrets (plan §10.1/§12.2) | UNKNOWN | Only the cloud/web-search `CredentialStoragePolicy` helpers exist; no generic credential-vault type was found in this recon |

## 4. Cloud protocol: tool config, tool decode, terminal, cancellation, bounds

| Claim | Status | Evidence |
| --- | --- | --- |
| Cloud chat call site calls provider directly; does not pass through `ChatExecutionOwner` | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt:268-285 |
| UI can only store `supportsTools=true` when `apiFormat == OPENAI_RESPONSES` | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/CloudModelConfigurationOperations.kt:139-148 |
| Tools are only serialized in the Responses request builder, and only if `request.tools` is non-empty, format is Responses, and `supportsTools` is true | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/OpenAiResponsesProtocol.kt:62-84 (requires :63-68) |
| OpenAI-compatible and Anthropic payload builders never attach `tools` | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/CloudChatPayloadSupport.kt:108-126, :128-149 |
| OpenAI-compatible provider has no tool-call decoding at all (only `[DONE]` string handling; chunk parser reads content/reasoning only) | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/OpenAiCompatibleChatProvider.kt:90 (only tool-related match), :351-362 |
| Existing chat UI passes tools only for Responses + supportsTools + image-tool binding and no pending exchanges | VERIFIED(source) | ChatExecutionOperations.kt:274-282 |
| Responses tool calls are accumulated from `output_item.added/done` + `function_call_arguments.delta/done` + terminal `output` array; non-JSON arguments rejected | VERIFIED(source) | OpenAiResponsesProtocol.kt:155-181, :222-248, :278-332 |
| `toolCalls` are exposed only when terminal AND terminalSuccessful AND no error | VERIFIED(source) | OpenAiResponsesProtocol.kt:118-134, :235-248 |
| Responses terminal is strict: loop exits only on terminal/stop; `[DONE]` without `response.completed` is an error; EOF before terminal throws "流意外中断" | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/OpenAiResponsesChat.kt:118-140; OpenAiResponsesProtocol.kt:136-140 |
| Responses terminal-event status mismatch, failed/incomplete/cancelled and unknown status are errors; Done only emitted after accepted terminal | VERIFIED(source) | OpenAiResponsesProtocol.kt:182-191, :209-221, :247; OpenAiResponsesChat.kt:151-157 |
| Responses rejects tool calls not present in `request.tools` | VERIFIED(source) | OpenAiResponsesChat.kt:153-154 |
| EOF-after-Done risk (OpenAI-compatible + Anthropic share one loop): EOF/`[DONE]`/`message_stop` all just break; `finish_reason` is never inspected; `Done` is emitted unconditionally afterwards | VERIFIED(source) | OpenAiCompatibleChatProvider.kt:77-80 (EOF), :82-90 (`[DONE]`), :97 (`done`), :178-191 (unconditional Done); parse paths :344-362, :364-383 |
| Non-stream fallback also emits `Done` without any completion-reason check (unless unparseable → Error) | VERIFIED(source) | OpenAiCompatibleChatProvider.kt:124-177 (Done :161-176) |
| Typed `STRICT_AGENT_TERMINAL` option for legacy providers | VERIFIED(source, absence) | no `STRICT|strict` match in `OpenAi*.kt`; only strictness is inside Responses decoder |
| Cancellation is per-Call: `withCancellableCloudCall` launches a canceller that calls `call.cancel()` and closes response on coroutine cancellation; no `dispatcher.cancelAll()` anywhere | VERIFIED(source) | OpenAiResponsesChat.kt:39-56; no match for `cancelAll()` in `app/src/main` |
| Provider constructor accepts injected `OkHttpClient` (stream client: connect 30s, read 0=no timeout, write 60s; quick client callTimeout 16s) | VERIFIED(source) | OpenAiCompatibleChatProvider.kt:34-47; OpenAiResponsesChat.kt:58 |
| Responses response bounds exist but are 4 MiB (line, event, JSON, accumulated text/tool args), not the plan's 64 KiB/1 MiB | VERIFIED(source) | OpenAiResponsesProtocol.kt:349 (`RESPONSES_MAX_BODY_CHARS`), enforced :329-331, :342-344; OpenAiResponsesChat.kt:128, :133, :192-211 |
| OpenAI-compatible/Anthropic streaming has NO size bound (unbounded `readLine()`); only accumulated non-stream fallback body capped at 1,048,576 chars; error body peek 64 KiB | VERIFIED(source) | OpenAiCompatibleChatProvider.kt:77-80, :83-85, :269, :494 |
| No per-attempt wall-clock cap on cloud streaming (read timeout disabled) | VERIFIED(source) | OpenAiCompatibleChatProvider.kt:35-39 |
| Real Responses endpoint/model native tool-call behavior | NOT_RUN | Requires live endpoint + device; plan §6.1's "已验证" is not proven by source |

## 5. Foreground service and notifications

| Claim | Status | Evidence |
| --- | --- | --- |
| `McaGenerationForegroundService` declared `exported=false`, `foregroundServiceType="specialUse"`, `stopWithTask=false`, subtype `mca_user_generation` | VERIFIED(source) | AndroidManifest.xml:173-181 |
| Manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` | VERIFIED(source) | AndroidManifest.xml:6-9 |
| Service promotes to foreground in `onCreate` with a bootstrap notification (comment: five-second foreground deadline), then refreshes from `onStartCommand` | VERIFIED(source) | McaGenerationForegroundService.kt:26-42, :45-70 |
| Promotion failure is contained: log + `stopSelf`; `START_NOT_STICKY` | VERIFIED(source) | McaGenerationForegroundService.kt:39-42, :71-77 |
| Channel `mca_generation`, `IMPORTANCE_LOW`, badge off | VERIFIED(source) | McaGenerationForegroundService.kt:133-145, :149 |
| Notification content is generic (title "MCA 正在生成", kind text chat/image/API, owner count); no prompt, filename, model output or key; PendingIntent only opens MainActivity | VERIFIED(source) | McaGenerationForegroundService.kt:89-110, :112-123, :125-131 |
| Notification ID 11438 reserved to avoid collision with workers/API services | VERIFIED(source) | McaGenerationForegroundService.kt:150-152 |
| Per-request lease registry: acquire starts service, release stops it only when owner count reaches zero; snapshot exposes kind/ownerCount | VERIFIED(source) | app/src/main/java/com/muyuchat/mca/GenerationForegroundLeaseRegistry.kt:11-40 |
| FGS acquire returns null on synchronous start rejection (caller must handle); service re-promotes and stops on stale/null intent | VERIFIED(source) | McaGenerationForegroundService.kt:56-69, :158-171 |
| targetSdk-36-specific constraint documented in code comments | VERIFIED(source, absence) | no `targetSdk`/`Android 16` comment in the service or manifest; only locale-independent "five-second deadline" note at :27-32; `Android 16` appears only in an unrelated QNN comment (QnnImageRuntimeStager.kt:13) |
| `POST_NOTIFICATIONS` runtime gating / behavior when notification permission is denied | UNKNOWN | Not handled in this service file; no source evidence found in this recon |
| Actual FGS start from background, OEM recreation and notification-vs-model behavior on device | NOT_RUN | Requires device execution |

## 6. Plans that source does NOT prove (Agent V1 assumptions to re-check at K00/K05)

| Assumption | Status | Finding |
| --- | --- | --- |
| "Room N→N+1" (plan §10.1) | VERIFIED(source) for N=27; N+1 NOT_RUN | N=27 proven (ChatSessionStoreRoomDatabase.kt:33). No 28 schema/migration exists; the +1 and its test are future work. |
| "Finish migration test before production code" is a usable gate today | UNKNOWN | Current migration test only manually runs 23→25, never opens Room, never validates 27.json, no `MigrationTestHelper`/room-testing, and is not in `verify-code.ps1`. |
| Agent files under `files/agent/...` are backup/device-transfer excluded | VERIFIED(source, absence) | Plan §10.2 requires it; both XMLs currently exclude only pre-existing paths. Two files must be edited consistently (backup_rules.xml + data_extraction_rules.xml cloud-backup & device-transfer). |
| "Responses supportsTools is explicit default false" | VERIFIED(source) | Defaults false everywhere (CloudChatProvider.kt:120, :160, :236, :392); enabling is Responses-only (CloudModelConfigurationOperations.kt:144). |
| "Only verified Responses path gets Agent tools" | UNKNOWN | Responses decode is terminal-gated in source, but no live endpoint/model evidence exists; OpenAI-compatible/Anthropic `supportsTools` is inert because those builders never send tools. |
| "EOF-after-Done must be defended against" | VERIFIED(source) | Real risk exists for OpenAI-compatible + Anthropic: `Done` after EOF with no finish_reason check (OpenAiCompatibleChatProvider.kt:77-80, :178-191). |
| "Agent cloud client needs 64 KiB/SSE and 1 MiB/attempt bounds" | VERIFIED(source, absence) | Not implemented for the OpenAI-compatible/Anthropic path (unbounded `readLine()`); Responses has a 4 MiB cap instead (OpenAiResponsesProtocol.kt:349). |
| "Cancel the attempt's Call, never `dispatcher.cancelAll()`" | VERIFIED(source) | Per-Call cancel exists (OpenAiResponsesChat.kt:40-56); no `cancelAll` in app main. No attempt handle is exposed beyond flow-collection cancellation. |
| "New FGS subtype/notification needs targetSdk36 review" | UNKNOWN / NOT_RUN | Existing `specialUse` + subtype pattern is proven in manifest; no targetSdk-36-specific code path or comment, and runtime behavior was not exercised. |
| JDK 21 needed for full verification despite Java 17 source/target | VERIFIED(source) | Hard lock :252; app compiles `JavaVersion.VERSION_17` (app/build.gradle.kts:318-319); no build was run. |

## 7. Open questions

1. K05 migration harness: adopt `androidx.room:room-testing` + `MigrationTestHelper` against 26/27 fixtures, or keep the manual `Migration(...).migrate()` pattern? Is adding the room-testing dependency authorized?
2. Will the 27→28 migration bump have a device-run upgrade test on a real pre-existing `mca.db`, and where is that evidence recorded? (Currently `ChatHistoryMigrationTest` is instrumentation-only and not wired into `verify-code.ps1`.)
3. Should Agent workspace files be excluded from BOTH system backup and device transfer, and who owns keeping `backup_rules.xml` and `data_extraction_rules.xml` in sync? (Plan §10.2 implies both; today neither mentions agent paths.)
4. Do the 26 migrations' `private` visibility need to change for a new migration test, or should tests use the exported schema instead? Only 23→27 are `internal`.
5. Is the real G0 Responses endpoint/model (with native tool calling) available for K04, and which supportsTools legacy records exist in the field? The flag is inert for non-Responses formats and no migration/cleanup for such records was found.
6. Where will `STRICT_AGENT_TERMINAL` live: a typed constructor/request option on the shared `OpenAiCompatibleChatProvider` (default-off for legacy chat) or a separate Agent-only decoder? No such option exists today.
7. Which bounds/timeouts must the Agent egress client own (64 KiB message, 1 MiB attempt, 180 s wall time) given the current streaming client has no read timeout and no SSE line bound for OpenAI-compatible/Anthropic?
8. Is per-attempt cancellation sufficient with only coroutine-job cancellation of the provider flow, or does the Agent adapter need a request-scoped `Call` handle? No call handle is currently returned by `streamChat`.
9. Should Agent reuse `McaGenerationForegroundService` with a new `KIND_AGENT`, or add a separate service/subtype? Either path changes the manifest; targetSdk-36 special-use review and POST_NOTIFICATIONS gating are still open.
10. Is a generic Keystore credential reference needed for MCP/Agent secrets, or is reusing the cloud-only `CloudApiStore`/`CredentialStoragePolicy` acceptable? No generic vault type exists at this HEAD.
11. Should Agent use `mca.db` (excluded from backup) or a separate database? Plan §10.1 says reuse `mca.db`; note that this DB is currently excluded from both backup and device transfer, so Agent data would be non-migratable by system backup by construction.
12. What is the intended handling of `repairLegacySchemaIfNeeded` (pre-Room repair at version <14) when new tables/migrations are added — is it frozen, and can a future test cover it?
