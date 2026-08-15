# Story Deferred-25: JPA Annotation Hygiene & Stripe Metadata Test Coverage

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want four small, independently-verified deferred items closed — a missing regression test for the
Stripe customer metadata key renamed in `skillars-deferred-24`, three `Booking` entity columns that can
be silently reassigned after a booking is created because they lack `updatable = false`, a coach-media
timestamp column that sets its value via a field initializer instead of this codebase's actual,
established `@PrePersist` convention for creation timestamps, and a drill-tag JPA column whose declared
length silently disagrees with the DB's actual `VARCHAR(50)` constraint — so that each of four unrelated,
previously-deferred defects, spanning the payment, booking, marketplace and session modules, gets fixed
without bundling any of them into a larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit
as `skillars-deferred-11/20/21/22/23/24`. All items below were independently re-verified against
**current** code during this story's creation, not trusted from the ledger's text, which the ledger's
own header warns can be stale. That staleness was not hypothetical: of roughly a dozen ledger candidates
inspected while assembling this story, more than half turned out to already be fixed or no longer
applicable —
- `skillars-1-6`'s V25 hardcoded-ID PK-collision item: the migration already carries an
  `ON CONFLICT (key) DO NOTHING` guard.
- `skillars-3-4`'s "dead `CANCELLED` entry in `BookingStateChip.statusMap`" item: `BookingStateMachine`
  still transitions bookings to bare `CANCELLED` via the `CANCEL_DUE_TO_PAUSE` event — not dead.
- `skillars-deferred-8`'s "`resolveParentName()` can render `\"null null\"`" item: `Customer.firstName`/
  `lastName` are DB `NOT NULL` (`Customer.java:38-43`), so the null-concatenation this item describes is
  unreachable for any persisted `User`; `BookingService`'s own copy of the method already null-guards
  regardless.
- `skillars-7-2`'s "`getParentBookings` doesn't clamp negative `effectiveCredits`" item: `effectiveCredits`
  no longer exists anywhere in `BookingService.java` — superseded by the Story 11 payment-path rewrite.
- `skillars-3-3`'s "no minimum booking duration validated" item: `skillars-uat-2` (2026-08-10) made
  `BookingService.createBooking` require the requested window to exactly equal the coach's configured
  session duration (15–240 min, `V93` `CHECK` constraint) — a 1-second booking is now rejected.
- `skillars-7-2`'s "duplicate expiry query methods, coach-scoped one appears unused" item:
  `SessionPackPurchaseRepository.java:26-28` now carries an explicit comment from `skillars-deferred-15`
  settling this — "The derived query above is a different caller — leave it alone."
- `skillars-deferred-24`'s own review-filed "no inline comment on the `V96` migration" item: that same
  review recorded this as matching the repo's existing convention (no other schema-cleanup migration
  carries such a comment) and deliberately deferred it — not a gap to close, so not picked up here despite
  being the newest item in the ledger.

## Deferred Items Closed

| Source | Item | Current location (re-verified) | AC |
|---|---|---|---|
| code review of `skillars-deferred-24-dead-subscription-column-stripe-metadata-and-backup-guard-fixes` (2026-08-15) | `StripePaymentGateway.createStripeCustomer`'s `parentId`→`userId` metadata rename has no test asserting the metadata map content | `StripePaymentGateway.java:152-160`, `StripePaymentGatewayTest.java` | 1 |
| code review of `skillars-7-2-session-payment-lifecycle-credit-wallet` Group 4 (2026-06-24) D16 | `Booking`'s `parentId`/`playerId`/`coachId` columns lack `updatable = false`, so a bug could silently reassign a booking's parent/player/coach after creation | `Booking.java:31-38` | 2 |
| code review of `skillars-2-3-coach-public-profile-page` (2026-06-13) | `CoachMediaItem.uploadedAt` sets its value via a field initializer (`OffsetDateTime.now()`) evaluated at object-construction time, not the `@PrePersist` callback this codebase actually uses (36 entities) for creation timestamps | `CoachMediaItem.java:30-31` | 3 |
| code review of `skillars-4-2-drill-card-operations` (2026-06-17) W4 | `DrillTagId.tag`'s `@Column` has no `length = 50`, silently disagreeing with the DB's actual `VARCHAR(50)` (`V40__drill_tags.sql:3`) | `DrillTagId.java:17-18` | 4 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- All six stale/superseded items listed under "Why this story exists" above — re-verification found each
  either already fixed by unrelated later work or no longer applicable to current code.
- **`skillars-deferred-24` review's "no inline comment on `V96` migration" item** — that review's own
  `[Review][Defer]` verdict already explains why adding one would break this repo's established
  no-comment convention for schema-cleanup migrations. Not a bundled-fix candidate.
- **`skillars-deferred-24` review's "`GUARD_PATH` duplication across 5 caller scripts" item** — same
  review recorded this as spec-directed (AC4's code block explicitly prescribed the per-caller shape); a
  DRY refactor needs its own sign-off, not a silent deviation. Not touched here.
- The broad body of pre-2026-08 deferred items, all `deploy-*` items (the ledger's own "Last audit" notes
  say these sections were never re-checked against current scripts), and every item flagged in the ledger
  as needing a product decision, its own design pass, or alerting infrastructure that doesn't exist yet —
  none of those are small, independently-safe, mechanical fixes and none were re-verified here.

## Acceptance Criteria

1. **A new unit test proves `StripePaymentGateway.createStripeCustomer` tags the Stripe-side `Customer`
   with the `userId` metadata key `skillars-deferred-24` renamed it to.** `createStripeCustomer(Long
   parentId)` (`StripePaymentGateway.java:151-160`) builds a `CustomerCreateParams` via
   `.putMetadata("userId", parentId.toString())` and passes it to `stripeClient.createCustomer(params)`
   (`StripeClient.java:52`, a thin wrapper this test class already mocks via `@Mock StripeClient
   stripeClient` — `StripePaymentGatewayTest.java:36`). No existing test in
   `StripePaymentGatewayTest.java` calls `createStripeCustomer` at all (confirmed by `grep -n
   "createStripeCustomer" src/test/java/.../StripePaymentGatewayTest.java` returning nothing) — the only
   coverage in that file targets `chargeAndCapture`'s idempotency-key logic. Add a test
   (`createStripeCustomer_tagsMetadataWithUserId` or similar) that: stubs `stripeClient.createCustomer(any())`
   to return a mock/real `com.stripe.model.Customer` whose `getId()` returns a fixed string; calls
   `stripePaymentGateway.createStripeCustomer(parentId)`; captures the `CustomerCreateParams` argument via
   `ArgumentCaptor<CustomerCreateParams>` (mirroring this file's existing `ArgumentCaptor` usage for
   `PaymentIntentCreateParams` in the `chargeAndCapture` tests, e.g. `:70-82`); and asserts the metadata
   contains exactly `{"userId": parentId.toString()}` — not `"parentId"`, which is the literal string a
   silent revert of the rename would reintroduce. Note: `CustomerCreateParams.getMetadata()` is statically
   typed `Object`, not `Map<String, String>`, in stripe-java 33.0.0 (confirmed via `javap`) — cast it
   (`@SuppressWarnings("unchecked") Map<String, String> metadata = (Map<String, String>)
   capturedParams.getMetadata();`) before asserting on it. This is the exact gap the `skillars-deferred-24`
   code review flagged: "a future accidental revert would be silent."

2. **`Booking`'s `parentId`, `playerId` and `coachId` columns are annotated `updatable = false`, so a bug
   can no longer silently reassign a booking's parent, player or coach after creation.** Add `updatable =
   false` to the three `@Column` annotations at `Booking.java:31,34,37` (`parent_id`, `player_id`,
   `coach_id`), matching the existing `created_at` column's shape at the bottom of the same class. This is
   defence-in-depth, not a behavior change: `grep -rn ".setParentId(\|.setPlayerId(\|.setCoachId(" src/main
   src/test` confirms every production call site sets these fields only while constructing a **new**
   `Booking` before its first `save()` — in `BookingService.createBooking` (`:280-282`),
   `BookingBatchService` (`:196-198`), and `BookingDuplicationService.duplicate` (`:60-62`, which builds a
   fresh `Booking` copying fields from the original, never mutates the original) — never on an
   already-persisted row. `updatable = false` only suppresses these columns from Hibernate's `UPDATE`
   statement; it has **zero** effect on `INSERT`, so none of these creation-time `setXxxId(...)` calls (nor
   the many test fixtures that call the same setters on a transient `Booking` before persisting it) are
   affected. Do not add `updatable = false` to any other `Booking` column — `requestedStartTime`,
   `requestedEndTime`, `status`, `canonicalTimezone`, `notes` and `version` are all legitimately mutated
   post-creation (reschedule, state transitions, pause/resume) and must stay mutable.

3. **`CoachMediaItem.uploadedAt` is set via a `@PrePersist` callback, not a field initializer evaluated
   when the Java object is constructed.** Replace
   `@Column(name = "uploaded_at", nullable = false, updatable = false) private OffsetDateTime uploadedAt =
   OffsetDateTime.now();` (`CoachMediaItem.java:30-31`) with `@Column(name = "uploaded_at", nullable =
   false, updatable = false) private OffsetDateTime uploadedAt;` plus a `@PrePersist` method mirroring
   `SessionPackPurchase.java:74-76`'s guarded single-field shape:
   ```java
   @PrePersist
   void onCreate() {
       if (uploadedAt == null) uploadedAt = OffsetDateTime.now();
   }
   ```
   **`@PrePersist` — not `@CreationTimestamp` — is this codebase's actual, established convention for
   creation timestamps.** `@CreationTimestamp` is used by **zero** files anywhere in `src/main/java`
   (confirmed via `grep -rl "@CreationTimestamp" src/main/java` returning nothing); 36 entities instead use
   a `@PrePersist` callback (confirmed via `grep -rl "@PrePersist" src/main/java`), including
   `SessionPackPurchase.java:74-76` (the guarded `if (createdAt == null) createdAt = Instant.now();` shape
   this AC mirrors) and `Video.java:92-96` (an unconditional variant that also sets `updatedAt`, not
   applicable here since `CoachMediaItem` has no update-tracking column). Do **not** introduce
   `@CreationTimestamp` — it would be a novel, unprecedented annotation in this codebase, not an alignment
   with an existing pattern. Note also that `CoachProfile.createdAt` (`CoachProfile.java:74`) uses the
   *exact same* field-initializer pattern this AC is removing from `CoachMediaItem` — it is a second
   instance of the same latent gap, not a convention to preserve, but fixing it is out of this story's scope
   (see Dev Notes: this story touches `CoachMediaItem` only).

   **This AC is defence-in-depth, not an active-bug fix — say so, don't overstate it.**
   `CoachMediaItemRepository` is read-only in production: `grep -rn "new CoachMediaItem()" src/main/java
   src/test/java` returns zero hits, meaning nothing in this codebase currently constructs and persists a
   `CoachMediaItem` row (the coach-media-gallery upload path this entity models is not yet wired up). This
   AC therefore has no currently-reachable behavioral difference and no test can exercise the `@PrePersist`
   method either way today — implement it for entity-hygiene/consistency (a future gallery-upload feature
   inherits correct behavior for free), matching AC2's honest "defence-in-depth, no currently-reachable
   bug" framing, not a claim that this fixes an observable defect.

4. **`DrillTagId.tag`'s JPA column declares the same `length = 50` the database already enforces.** Add
   `length = 50` to `@Column(name = "tag")` at `DrillTagId.java:17` — i.e. `@Column(name = "tag", length =
   50)` — matching `session.drill_tags.tag`'s actual `VARCHAR(50) NOT NULL` definition
   (`V40__drill_tags.sql:3`). This is a documentation/schema-validation-consistency fix, not a behavior
   change: the DB constraint already exists and already enforces the limit at the database layer
   regardless of what the Java annotation says; without this annotation, Hibernate's DDL-validation mode
   (if ever enabled) or any DDL Hibernate might generate from this entity would silently disagree with the
   real schema (JPA's default column length is 255, not 50).

5. **Ledger hygiene in `deferred-work.md`.** Annotate every item this story closes (see **Deferred Items
   Closed** table) with `[CLOSED by skillars-deferred-25 ACn]` at its current ledger location once
   implemented, following this file's established annotation convention (do not delete the original item
   text).

## Tasks / Subtasks

- [x] Task 1 — Add a metadata-content test for `createStripeCustomer` (AC: #1)
  - [x] Add `createStripeCustomer_tagsMetadataWithUserId` (or equivalent name) to
    `StripePaymentGatewayTest.java`, following the file's existing `@Mock`/`@InjectMocks`/`ArgumentCaptor`
    conventions used by the `chargeAndCapture` tests
  - [x] Stub `stripeClient.createCustomer(any())` to return a `Customer` with a fixed `getId()`
  - [x] Capture the `CustomerCreateParams` passed to `stripeClient.createCustomer(...)` and assert its
    metadata map is exactly `{"userId": parentId.toString()}` (not `"parentId"`)
  - [x] `mvn -o test -Dtest=StripePaymentGatewayTest` green

- [x] Task 2 — Harden `Booking`'s identity columns (AC: #2)
  - [x] Add `updatable = false` to `Booking.java`'s `parentId` (`:31`), `playerId` (`:34`) and `coachId`
    (`:37`) `@Column` annotations
  - [x] Re-confirm via `grep -rn ".setParentId(\|.setPlayerId(\|.setCoachId(" src/main/java
    src/test/java` that every call site sets these fields only on a not-yet-persisted `Booking` — do not
    assume this story's own research is still accurate at implementation time
  - [x] `mvn -o test -Dtest=BookingServiceTest,BookingBatchServiceTest,BookingDuplicationServiceTest`
    green (these three unit-test the three call sites identified above)
  - [x] `mvn -o verify -Dit.test=BookingRepositoryIT` green (exercises `Booking` persistence directly)

- [x] Task 3 — Switch `CoachMediaItem.uploadedAt` to a `@PrePersist` callback (AC: #3)
  - [x] Remove the `= OffsetDateTime.now()` field initializer from `uploadedAt`, keeping `nullable = false,
    updatable = false` on the `@Column`
  - [x] Add a `@PrePersist void onCreate() { if (uploadedAt == null) uploadedAt = OffsetDateTime.now(); }`
    method, mirroring `SessionPackPurchase.java:74-76`'s shape
  - [x] Do NOT add `@CreationTimestamp` — confirm via `grep -rl "@CreationTimestamp" src/main/java` that it
    is still unused anywhere in this codebase before assuming otherwise
  - [x] Re-confirm via `grep -rn "new CoachMediaItem()" src/main/java src/test/java` that nothing currently
    constructs/persists a `CoachMediaItem` — if this has changed since story creation, check whether any
    caller depends on `uploadedAt` being set pre-persist (the field initializer's old behavior)
  - [x] `mvn -o test -Dtest=CoachProfileServiceTest` (or whatever test class covers `CoachMediaItem` —
    locate via `grep -rln "CoachMediaItem" src/test/java/`) green
  - [x] `mvn -o verify -Dit.test=CoachProfileResourceIT` green if it seeds/reads `CoachMediaItem` rows

- [x] Task 4 — Add the missing `length = 50` to `DrillTagId.tag` (AC: #4)
  - [x] Add `length = 50` to `DrillTagId.java:17`'s `@Column(name = "tag")`
  - [x] `mvn -o test -Dtest=DrillTagIdTest` if such a test class exists, else confirm no test asserts on
    JPA metadata for this embeddable
  - [x] `mvn -o verify -Dit.test=DrillLibraryResourceIT` (or equivalent IT exercising drill tags) green

- [x] Task 5 — Ledger hygiene (AC: #5)
  - [x] Annotate all 4 closed items per the **Deferred Items Closed** table in `deferred-work.md` with
    `[CLOSED by skillars-deferred-25 ACn]`
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-25-...` entry status as this story progresses
    (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention

### Review Findings

Reviewed via `bmad-code-review` (Blind Hunter + Edge Case Hunter + Acceptance Auditor, 2026-08-15). Acceptance Auditor found zero AC violations — all 5 ACs match the diff exactly. Edge Case Hunter found zero unhandled paths reachable from the changed lines. Blind Hunter raised 12 items; 10 were verified false/already-covered-by-spec-design and dismissed as noise, 2 were real test-coverage gaps deliberately out of this story's scope per its own Dev Notes and are logged below per this story's own "note it as a new `deferred-work.md` item" instruction.

- [x] [Review][Defer] No test proves `CoachMediaItem`'s `@PrePersist onCreate()` actually sets `uploadedAt` on persist [CoachMediaItem.java:34-38] — deferred, pre-existing (currently unreachable: nothing constructs/persists `CoachMediaItem` yet, confirmed zero constructors in `src/main`/`src/test`; add coverage when the coach-media gallery upload feature lands)
- [x] [Review][Defer] No regression test proves `Booking.parentId`/`playerId`/`coachId`'s new `updatable = false` actually causes Hibernate to ignore a post-persist mutation attempt, as opposed to relying on the full existing suite to incidentally catch it [Booking.java:31-38] — deferred, pre-existing (this story's own Dev Notes explicitly accepted relying on Task 2's existing test suite rather than a new dedicated assertion of the ignore-behavior)

## Dev Notes

- **Scope discipline.** Four small, independently-safe items across four different files/modules — a
  missing Stripe-gateway unit test, three JPA `@Column` hardening tweaks on `Booking`, one
  field-initializer→`@PrePersist` swap on `CoachMediaItem`, and one missing `length` attribute on
  `DrillTagId`. Do not use this as a pretext to "clean up while you're in there" on adjacent code — e.g.
  don't also switch other `Booking` columns to `updatable = false`, don't touch `Booking.status`'s raw
  `String` typing (a separate, larger, already-deferred item), don't go hunting for every entity in the
  codebase still using a field-initializer timestamp beyond `CoachMediaItem` (`CoachProfile.createdAt` has
  the identical gap — leave it; it's not this story's job). If something adjacent looks wrong, note it as a
  new `deferred-work.md` item; don't fix it here.

- **This story is unusually verification-heavy relative to its size.** Roughly a dozen ledger candidates
  were inspected while assembling it; more than half were already fixed or no longer applicable (see "Why
  this story exists" above for the full list of rejected stale items). This is not a coincidence — it is
  exactly the failure mode the ledger's own "How to read this file" section warns about ("Several such
  promises turned out to be unkept"). **Do not trust this story's own AC text as gospel either** — re-run
  the greps cited in each AC/task at implementation time before writing code, the same way this story's
  own creation re-ran them against the ledger's original (June-dated) claims.

- **AC1's test must assert the metadata key, not just that `createCustomer` was called.** The
  `skillars-deferred-24` review's whole point was that a silent revert (accidentally reintroducing
  `"parentId"` instead of `"userId"`) would currently pass every existing test unnoticed. An assertion that
  merely verifies `stripeClient.createCustomer(any())` was invoked once would not catch that regression —
  it must inspect the captured `CustomerCreateParams`'s metadata content specifically.

- **AC2 is defence-in-depth with no currently-reachable bug behind it** — this story's own research found
  no code path that mutates these fields on a persisted `Booking`. That is exactly why it is a safe,
  isolated, single-AC fix rather than something requiring a wider regression pass: `updatable = false`
  cannot change behavior for any code that already only sets these fields pre-persist, and if some
  not-yet-discovered call site does mutate a persisted `Booking`'s parent/player/coach, Task 2's full test
  suite run will surface it as a failure rather than silently rejecting it in production. Re-verify the
  grep before trusting this is still true at implementation time.

- **AC3 has no currently-reachable production code path** — `grep -rn "new CoachMediaItem()" src/main/java
  src/test/java` found zero constructors of this entity anywhere, so nothing today persists a
  `CoachMediaItem` row. Implement it as entity hygiene / consistency with the rest of the codebase, not as
  a fix for an observable bug — there isn't one to observe yet. Re-verify the grep at implementation time;
  if a gallery-upload feature has since landed, check whether it depends on `uploadedAt` being set
  pre-persist (the field initializer's old behavior) before removing it.

- **AC4 is the smallest possible fix: one `length = 50` attribute, DB schema unchanged.** Do not add
  `@Size(max = 50)` Bean Validation or any other validation layer — that would be a scope expansion beyond
  what the ledger item asked for, and `DrillTagId` is an `@Embeddable` identifier component, not a
  request/response DTO where Bean Validation would normally apply.

- **This story touches Java only (payment, booking, marketplace, session modules) — no SQL migrations, no
  frontend, no shell scripts.** `mvn -o verify` (unit + the specific ITs cited per-task) is the full
  verification bar.

- **File paths this story touches:**
  - `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java` (AC1)
  - `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java` (AC2)
  - `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItem.java` (AC3)
  - `src/main/java/com/softropic/skillars/platform/session/repo/DrillTagId.java` (AC4)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC5, status line only)

### Project Structure Notes

- All four ACs are same-file, narrow-scope changes to existing files — no new production classes, no new
  migrations, no new test classes (AC1 adds a test *method* to an existing test class).
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses
  in `sprint-status.yaml` (the "DEFERRED WORK" block).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from: code review of
  skillars-deferred-24-dead-subscription-column-stripe-metadata-and-backup-guard-fixes (2026-08-15)"
  (AC1); "## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)"
  Group 4 D16 (AC2); "## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)"
  (AC3); "## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)" W4 (AC4)
- [Source: src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:151-160]
  — confirms AC1's current `createStripeCustomer` metadata shape (`"userId"`, already renamed by
  `skillars-deferred-24`)
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java:1-140]
  — confirms zero existing coverage of `createStripeCustomer`; confirms `@Mock StripeClient
  stripeClient`/`@InjectMocks` wiring and the `ArgumentCaptor<PaymentIntentCreateParams>` pattern to mirror
  for `CustomerCreateParams`
- [Source: src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java:1-60] — confirms AC2's
  current column shape and that only `created_at` currently has `updatable = false`
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:280-282;
  BookingBatchService.java:196-198; BookingDuplicationService.java:60-62] — confirms every
  `setParentId`/`setPlayerId`/`setCoachId` call site is creation-time only, on a not-yet-persisted `Booking`
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItem.java:1-32] —
  confirms AC3's current field-initializer shape
- [Source: src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java:65-77] —
  the `@PrePersist`/guarded-single-field shape AC3 mirrors; confirms `@CreationTimestamp` is used nowhere
  in this codebase (`grep -rl "@CreationTimestamp" src/main/java` returns zero files) and that
  `CoachProfile.createdAt` (`CoachProfile.java:74`) shares `CoachMediaItem`'s pre-fix field-initializer
  gap rather than demonstrating any different convention
- [Source: src/main/java/com/softropic/skillars/platform/session/repo/DrillTagId.java:1-40] — confirms
  AC4's current `@Column(name = "tag")` shape with no `length`
- [Source: src/main/resources/db/migration/V40__drill_tags.sql:1-9] — confirms the DB's actual
  `tag VARCHAR(50) NOT NULL` definition AC4 aligns the JPA annotation with

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -o test -Dtest=StripePaymentGatewayTest` — 5 tests, 0 failures (AC1)
- `mvn -o test -Dtest=BookingServiceTest,BookingBatchServiceTest,BookingDuplicationServiceTest` — 56 tests, 0 failures (AC2)
- `mvn -o verify -Dit.test=BookingRepositoryIT` — 8 tests, 0 failures (AC2)
- `mvn -o compile` — BUILD SUCCESS, no test class covers `CoachMediaItem` construction (AC3)
- `mvn -o verify -Dit.test=DrillLibraryResourceIT` — 11 tests, 0 failures (AC4)
- Full `mvn -o verify` regression suite — see Completion Notes for result

### Completion Notes List

- **AC1**: Added `createStripeCustomer_tagsMetadataWithUserId` to `StripePaymentGatewayTest.java`. Re-verified
  zero prior coverage of `createStripeCustomer` before writing the test. Captures `CustomerCreateParams` via
  `ArgumentCaptor` and asserts the metadata map is exactly `{"userId": "1001"}` — cast required since
  `CustomerCreateParams.getMetadata()` is statically typed `Object` in stripe-java 33.0.0, confirmed as the
  story's Dev Notes predicted.
- **AC2**: Added `updatable = false` to `Booking.parentId`/`playerId`/`coachId`. Re-ran the grep for all
  `.setParentId(/.setPlayerId(/.setCoachId(` call sites across `src/main` and `src/test` before editing —
  confirmed (independently of the story's own research) that all three production call sites
  (`BookingService.createBooking`, `BookingBatchService`, `BookingDuplicationService.duplicate`) only set
  these fields on a `new Booking()` prior to first `save()`. No behavior change; defence-in-depth as specified.
- **AC3**: Replaced `CoachMediaItem.uploadedAt`'s field-initializer with a `@PrePersist onCreate()` callback
  mirroring `SessionPackPurchase`'s guarded shape. Re-confirmed `@CreationTimestamp` is still unused anywhere
  in `src/main/java` and that nothing constructs a `CoachMediaItem` in `src/main` or `src/test` — this AC has
  no currently-reachable behavioral difference, consistent with the story's own framing.
- **AC4**: Added `length = 50` to `DrillTagId.tag`'s `@Column`, matching `V40__drill_tags.sql`'s
  `VARCHAR(50)`. No DB change; JPA-annotation/schema consistency only.
- **AC5**: Annotated all 4 closed ledger items in `deferred-work.md` with `[CLOSED by skillars-deferred-25 ACn]`
  plus a one-line note of what was actually implemented, replacing the `[OWNED BY ...]` story-creation tags
  (following the established convention observed in prior `CLOSED by skillars-deferred-*` entries — the OWNED
  BY tag is replaced, not kept alongside CLOSED BY).
- All four production/test changes are narrow, same-file, no new classes or migrations, matching the story's
  scope-discipline Dev Note. Full `mvn -o verify` regression suite run to confirm no regressions (see below).

### File List

- `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItem.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillTagId.java` (AC4)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC5, status line)

## Change Log

| Date | Change |
|---|---|
| 2026-08-15 | Implementation complete — all 5 tasks / 5 ACs done. AC1: added `createStripeCustomer_tagsMetadataWithUserId` to `StripePaymentGatewayTest.java`, asserting the captured metadata is exactly `{"userId": ...}`; 5/5 tests green. AC2: added `updatable = false` to `Booking.parentId`/`playerId`/`coachId`, re-confirmed all 3 production call sites are creation-time-only; targeted unit tests (56/56) and `BookingRepositoryIT` (8/8) green. AC3: switched `CoachMediaItem.uploadedAt` to a `@PrePersist` callback mirroring `SessionPackPurchase`'s guarded shape, not `@CreationTimestamp`; no test exercises this entity today (confirmed zero constructors in src/main or src/test), so verified via clean compile only. AC4: added `length = 50` to `DrillTagId.tag`; `DrillLibraryResourceIT` (11/11) green. AC5: annotated all 4 closed `deferred-work.md` items with `[CLOSED by skillars-deferred-25 ACn]`, replacing story-creation `[OWNED BY ...]` tags. Full `mvn -o verify` regression suite: BUILD SUCCESS, exit code 0, 9:01 min, 0 failures/errors (2 apparent failures found by summing `target/surefire-reports/*.txt` were confirmed stale — timestamped Aug 13/14, from before this run — a known trap this repo's own `deferred-19` entry documents; failsafe:verify only runs after surefire:test succeeds, and it did, so this run had zero real failures). Status: review. |
