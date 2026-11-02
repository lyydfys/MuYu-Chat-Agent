# ADR 0009: Field-limited application-state writes

- Date: 2026-10-24
- Scope: app state authority, coordinator composition and typed dependency injection
- Status: implemented; verification results belong to the accompanying task evidence

## Context

The former coordinator host exposed unrelated application capabilities through a single 71-member
interface. Business constructors also received a store able to replace all of `MainUiState`, and
several injected dependencies used `Any?` followed by a safe cast and a production fallback.
Splitting source files alone did not remove those authorities.

## Decision

Keep the existing `MainUiState` schema, defaults and one private `MutableStateFlow`. Existing
read-only snapshots and flows continue to represent the same atomic application state. This
migration does not split persistence, introduce parallel state sources or change UI projections.

Composition creates `UiStateWriter<ConcreteFields>` capabilities. Each reducer reads the exact
snapshot used by its CAS attempt and returns only its declared fields. The store merges a fixed
whitelist into that same snapshot. A field object's `snapshot` is read-only input; substituting it
cannot replace unrelated root fields. Root-returning reducers and mutable-flow exposure are removed.

The reviewed authority manifest is `tools/architecture/state_write_authorities.json`.
`tools/architecture/generate_state_fields.py` regenerates the field types, explicit merges and
composition factories. Generated sources remain visible and reviewable in the app source tree.
The boundary gate checks fields and merges against the manifest; changing a whitelist is a change
of write authority and requires review of actual production callers and atomic invariants.

`updateWithResult` returns only the successful CAS attempt's result. `updateAndGet` returns the
committed projection. Callers must not use a mutable variable captured outside a retryable reducer
for persistence decisions. Time, candidate identities, native statistics and I/O results are frozen
outside reducers. Side effects execute after a successful transaction under the existing owner.
`restrict` can derive a smaller capability by merging only into its parent's fields; it cannot
obtain a root store or a wider parent merge.

Composition owns writer issuance. Business code receives explicit fields, a read-only port or a
named status command, and cannot select another writer. Related atomically coupled transitions
retain explicit wider field sets: runtime release/load/profile settlement, conversation activation
and rollback, and image-result attachment to conversation records. They are not universal writers.

The old coordinator host and adapter are removed. Domain factories receive explicit dependencies;
`MainCoordinatorAssembly` is only the collection of already constructed results and is never a
service locator for business code. Actual DSH screen-capture authorization lifecycle belongs to
`DshScreenCaptureConsentOwner`. Injection is concrete and nullable only to express an omitted
optional test override; a wrong dependency type is rejected rather than replaced by a real service.

Business coordinators no longer retain `McaAppContainer`. They receive the actual repositories,
ports, resource owners and named callbacks they use. Image workflow port construction belongs to
composition and preserves construction order and deferred providers. The bootstrap projection
receives its four specific stores instead of the application container. A source gate rejects
container references outside the explicitly enumerated composition entry points.

Image terminal settlement also revalidates the current job binding inside each CAS attempt.
An old job cannot overwrite a newer retry of the same request, and a discarded attempt never
persists its tentative sessions. Ordinary request replacement retains its existing rebind behavior;
requests with an empty legacy job binding keep their previous terminal compatibility.

## Tradeoffs and review points

The application snapshot retains its existing 142 fields for compatibility and atomic observation.
This ADR closes unrestricted write access; it does not claim the entire application is fully
modular or that every domain has an independent persisted schema.

`MainViewModel` remains a composition and lifecycle entry with existing public intent signatures.
Its physical size remains close to the hard ceiling, so future work must not add business rules
there. The separate conversation replay operation owns regeneration and reset transitions.
Existing image workflow coordinators still have many direct collaborators: supplying explicit
writers increases visible constructor parameters while removing the previously implicit ability
to mutate unrelated fields. These coordinators must not acquire new unrelated capabilities or a
replacement dependency bag. Further decomposition follows an independently verifiable feature
boundary, not an arbitrary file-size target.

Generated field files are repetitive by design and fall under the generated-code budget exception.
The manifest, generator, store and boundary checks are the review surface for that repetition.
No existing feature, state default, data format, route, device admission rule, native backend or
frozen visual behavior is authorized to change through this ADR.

## Verification and rollback

Required verification includes Kotlin production/test compilation, existing app regression tests,
CAS contention and successful-result tests, UI/API shared-entry contracts, terminal and rollback
behavior, boundary gate negative fixtures, and the existing architecture gate. Device/native and
release acceptance must be reported separately and are never inferred from JVM success.

Rollback applies the task patch in reverse after reviewing pre-existing local edits. No database
migration or persisted-format change is introduced; data remains in the existing schema. Do not
reset whole files or native worktrees to undo this task. No commit or publication is implied.
