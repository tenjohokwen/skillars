# Story Deferred-70: Self-Booking Query-Param Guard, Marketplace Hygiene & Session-Plan Completion Wiring

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a platform owner continuing the deferred-work.md drawdown, I want (1) a self-booking (adult) player's
booking-request page to stop trusting a `?playerId=` query-string override, (2) two small, mechanical
Marketplace hygiene fixes — a dead sort branch and a constraint-locking migration split — (3) a real gap
closed where a session plan never transitions to `COMPLETED` when its booking actually completes, and
(4) ledger hygiene closing/re-annotating three now-stale or superseded `deferred-work.md` items,
so that the next `deferred-work.md` re-mining pass starts from an accurate, thinner ledger.

### Why this story exists

Re-mined `deferred-work.md` (1635 lines) module-by-module in the priority order the project owner set:
Booking/Availability/Reschedule first, then Marketplace/Coach-profile, then Auth/Registration, then
Session/Drills/Homework. Booking/Availability/Reschedule is now genuinely thin — `skillars-deferred-69`
(the immediately preceding story) closed 7 items in this module, and its own code-review follow-up
recorded 4 more as intentionally deferred/accepted (pre-existing patterns or design decisions, not
actionable). Only one fresh, real, mechanical item turned up in Booking on this pass (AC1). Per project
owner instruction, the story was bundled forward into Marketplace/Coach-profile (AC2, AC3) rather than
shipped as a single-item story.

A fourth candidate — `CoachProfileService.getPublicProfile` firing 8 sequential DB round-trips for a
single coach's public profile page — was investigated but **not** picked up as an AC. On close reading,
the original ledger framing ("N+1 queries... before Epic 3 traffic ramp") overstates what's actually
happening: `getPublicProfile(coachId)` is called once per page view for exactly one `coachId`, so there
is no `N` that grows — it is one profile view issuing ~8 small, indexed, single-row queries, not a
classic N+1 scaling problem. Forcing a batch-loading/`@EntityGraph` rewrite here today would be
optimizing a page load that has no reported latency problem, against this project's own established
anti-over-engineering convention. Re-filed in AC5 with this corrected framing rather than picked up.

A fifth candidate — `CoachCommandCenterPage.vue`'s `getDayIndex` hardcoding `Intl.DateTimeFormat('en', ...)`
— was checked directly against current source and found **already correct**: the function carries an
explicit code comment (`:327-329`) explaining that the hardcoded `'en'` locale is deliberate, matched
against the hardcoded English weekday array immediately below it via `.indexOf`, and that localizing the
formatter alone without also rewriting that array would silently misbucket every non-English user's
schedule into the wrong day column. Not a bug; not picked up; not re-filed (it was never an open ledger
item to begin with — found via direct source reading, not the ledger).

**AC4 (session-plan completion wiring) required a product decision, gathered from the project owner
directly during this story's creation**: `SessionPlanService`'s `status` field is only ever set to
`DRAFT` (at `createSession`) or to whatever `UpdateSessionPlanRequest.status()` the frontend explicitly
sends (at `updateSession`) — no code path anywhere (booking-completion event, scheduler, dedicated
endpoint) ever transitions a plan to `COMPLETED` when its underlying booking actually completes. The
project owner confirmed (2026-08-26): **wire it up** — a session plan should auto-transition to
`COMPLETED` when its booking completes. This is scoped as a real, moderate-effort feature addition
(AC4), not ledger hygiene.

## Acceptance Criteria

### AC1 — Self-booking player: query-param `playerId` must not override the player's own resolved id

**Current behavior, verified against live source
(`src/frontend/src/pages/parent/BookingRequestPage.vue:245-249`):**

```js
const playerId = computed(() => {
  if (route.query.playerId) return Number(route.query.playerId)
  if (authStore.isPlayer) return selfPlayerId.value
  return playerStore.activePlayerId
})
```

A self-booking (adult) player (`authStore.isPlayer === true`) who lands on this page with **any**
`?playerId=...` query string present — a stray bookmark, a copy-pasted link originally built for a
parent's linked child, a malformed/leftover param from browser history — has that query param take
priority over their own correctly-resolved `selfPlayerId.value`. The backend's ownership check in
`BookingService.createBookingRequest` (the established `player.getParentId() == null` → check
`player.getUserId()` branch, already used everywhere self-booking is handled in this codebase) will
correctly reject a mismatched `playerId` with a 403/404 at submit time — this is **not** a security
hole, the backend never trusts the frontend's resolved id — but it produces a confusing, generic
rejection instead of the page simply ignoring the stray param and using the player's own id, the way a
self-booking player should always expect this page to behave for themselves.

**Fix:** gate the query-param branch behind `!authStore.isPlayer` — a self-booking player's own id
always wins, regardless of what is in the URL:

```js
const playerId = computed(() => {
  if (route.query.playerId && !authStore.isPlayer) return Number(route.query.playerId)
  if (authStore.isPlayer) return selfPlayerId.value
  return playerStore.activePlayerId
})
```

Do not change the two other branches — the parent-viewing-a-linked-child path (`route.query.playerId`
for a parent) and the parent-default-active-player fallback (`playerStore.activePlayerId`) are both
correct today and out of scope.

**Testing:** No frontend test infrastructure exists in this repo (standing, repeatedly-documented gap —
do not introduce one as part of this story). Verify by direct code reading post-fix and `npx eslint`
clean on the touched file; no automated regression test is expected or required for this file, matching
this repo's established convention for `.vue`-only changes.

---

### AC2 — Collapse `CoachSearchService.buildSort`'s dead-identical `"price"`/`default` branches

**Current behavior, verified against live source
(`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchService.java:112-121`):**

```java
private Sort buildSort(String sortBy) {
    // price sort is applied in Java after enrichment (perSessionPrice lives in a separate table)
    // ACTIVE coaches sort before REDUCED within any other sort criteria
    Sort statusSort = Sort.by(Sort.Order.asc("status"));
    return switch (StringUtils.hasText(sortBy) ? sortBy : "displayName") {
        case "price"  -> statusSort.and(Sort.by(Sort.Direction.ASC,  "displayName"));
        case "rating" -> statusSort.and(Sort.by(Sort.Order.desc("averageRating").nullsLast()));
        default       -> statusSort.and(Sort.by(Sort.Direction.ASC,  "displayName"));
    };
}
```

The `"price"` case and the `default` case produce byte-for-byte identical `Sort` objects. This is
harmless today (both compute the same value) but is dead, confusing duplication — a future editor
changing one arm without noticing the other exists could silently reintroduce a real bug. The existing
code comment ("price sort is applied in Java after enrichment...") already explains *why* the DB-level
sort for `"price"` is deliberately `displayName`, not `perSessionPrice` — that reasoning belongs on one
arm, not two copies.

**Fix:** collapse the two identical arms into one, keeping the existing explanatory comment attached to
the surviving arm (do not delete the comment — it documents genuinely non-obvious behavior: the actual
price ordering happens later, in `sortPage`, not here):

```java
private Sort buildSort(String sortBy) {
    // price sort is applied in Java after enrichment (perSessionPrice lives in a separate table),
    // so "price" and the default both order by displayName at the DB level — ACTIVE coaches sort
    // before REDUCED within either.
    Sort statusSort = Sort.by(Sort.Order.asc("status"));
    return switch (StringUtils.hasText(sortBy) ? sortBy : "displayName") {
        case "rating" -> statusSort.and(Sort.by(Sort.Order.desc("averageRating").nullsLast()));
        default       -> statusSort.and(Sort.by(Sort.Direction.ASC,  "displayName"));
    };
}
```

**Testing:** Check `CoachSearchServiceTest.java` (or the nearest existing test covering `buildSort`/sort
behavior for `sortBy="price"` and no-`sortBy`) for any test asserting on the specific `switch` arm taken
— none should need behavioral changes since the produced `Sort` is unchanged, only the source
structure. Run `mvn -o test -Dtest=CoachSearchServiceTest` (or the correct existing test class name,
confirm via a repo search — do not assume the name) to confirm no regression.

---

### AC3 — Split `chk_coach_pricing_session_duration`'s single-migration CHECK into `NOT VALID` + `VALIDATE CONSTRAINT`

**Current behavior, verified against live source (`src/main/resources/db/migration/V93__session_duration.sql`):**

```sql
ALTER TABLE marketplace.coach_pricing
    ADD COLUMN session_duration_minutes INT NULL,
    ADD CONSTRAINT chk_coach_pricing_session_duration
        CHECK (session_duration_minutes IS NULL
               OR (session_duration_minutes BETWEEN 15 AND 240));
```

This combined `ADD COLUMN` + `ADD CONSTRAINT` takes an `ACCESS EXCLUSIVE` lock on
`marketplace.coach_pricing` for the full duration of the constraint's initial full-table `CHECK`
validation — the exact lock-duration pattern this project has since established a fix for
(`V100`/`V101`, and this story's own sibling precedent `V105`/`V106` for
`chk_payment_currency_format`): split into a `NOT VALID` add (brief lock, no table scan) followed by a
separate `VALIDATE CONSTRAINT` migration (`SHARE UPDATE EXCLUSIVE` lock, does not block concurrent
reads/writes). `marketplace.coach_pricing` is small today (one row per coach), so the practical impact
is minor, but the fix costs nothing and keeps this migration's locking behavior consistent with every
CHECK constraint this project has added since `V100`.

The constraint already exists in every environment that has run `V93` — this is **not** a fresh
constraint add, so the fix cannot simply add `NOT VALID` to `V93` (already-applied Flyway migrations are
immutable). It must `DROP` the existing constraint and re-`ADD` it as `NOT VALID` in a new migration,
then validate it in a following one.

**Fix — two new migrations** (confirm the next free `V` number via `ls src/main/resources/db/migration`
at implementation time; `V107`/`V108` were the next free numbers as of this story's creation, immediately
after `V106__payment_currency_format_guard_validate.sql`):

`V107__coach_pricing_session_duration_not_valid.sql`:
```sql
-- Story Deferred-70 AC3: chk_coach_pricing_session_duration (V93) was added via a single ALTER TABLE
-- ADD COLUMN + ADD CONSTRAINT, taking an ACCESS EXCLUSIVE lock for the full duration of the
-- constraint's initial full-table CHECK validation — the same pattern V100/V101 and V105/V106
-- established a NOT VALID + VALIDATE CONSTRAINT split to avoid. Table is small today (one row per
-- coach); the split costs nothing and keeps this migration's locking behavior consistent with every
-- CHECK constraint added since V100. The constraint already exists in every environment that has run
-- V93 (an already-applied migration cannot be edited), so it is dropped and re-added here rather than
-- added fresh.
ALTER TABLE marketplace.coach_pricing
    DROP CONSTRAINT chk_coach_pricing_session_duration;

ALTER TABLE marketplace.coach_pricing
    ADD CONSTRAINT chk_coach_pricing_session_duration
    CHECK (session_duration_minutes IS NULL
           OR (session_duration_minutes BETWEEN 15 AND 240)) NOT VALID;
```

`V108__coach_pricing_session_duration_validate.sql`:
```sql
-- Validates the NOT VALID constraint added in V107 against every existing row, under a SHARE UPDATE
-- EXCLUSIVE lock that does not block concurrent reads/writes — a separate migration (separate
-- transaction) from V107, mirroring V101's and V106's own reasoning exactly.
ALTER TABLE marketplace.coach_pricing
    VALIDATE CONSTRAINT chk_coach_pricing_session_duration;
```

**Testing:** Flyway migration tests (if any exist covering migration application — check for a
`FlywayMigrationIT`-style test) plus any existing `CoachPricing`/session-duration integration test
(e.g. covering the boundary values 15/240 and NULL) must still pass unchanged, since the effective
constraint semantics are identical before and after — only the locking/validation timing changes.

---

### AC4 — Wire up `SessionPlanService`: a session plan auto-transitions to `COMPLETED` when its booking completes

**Decision (project owner, 2026-08-26): wire it up as a real feature**, not leave `COMPLETED` as a
currently-unreachable status value.

**Current behavior, verified against live source
(`src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`):**
- `createSession` (`:93`) always sets a new plan's status to the literal `"DRAFT"`.
- `updateSession` (`:136`) sets status to `req.status() != null ? req.status() : session.getStatus()` —
  i.e. only ever changes on an explicit frontend-driven `PATCH`/update call.
- No code path — no event listener, no scheduler, no dedicated endpoint — ever transitions a plan to
  `"COMPLETED"` in response to its underlying booking actually completing.

**Established pattern to mirror** (same module, same trigger event, verified live):
`src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java:53-88`
already listens for the exact same `BookingCompletedEvent`
(`src/main/java/com/softropic/skillars/platform/booking/contract/BookingCompletedEvent.java`, which
carries `bookingId`) via:
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void handleBookingCompleted(BookingCompletedEvent event) {
    ...
    UUID sessionId = sessionRepository.findByBookingId(event.getBookingId())
        .map(s -> s.getId()).orElse(null);
    if (sessionId == null) {
        log.debug("No session found for booking {} — assigning homework with null sessionId (expected for QUICK-mode bookings)", event.getBookingId());
    }
    ...
}
```
`BookingCompletedEvent` is published from **two** sites in
`src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java` (`:162`
and `:197`) — both the LIVE-mode confirm-completion path and the QUICK-mode confirm path. A booking
completed via either path may or may not have an associated `Session` plan (QUICK-mode bookings
routinely have none, as `HomeworkAssignmentService`'s own comment above documents) — the new listener
must handle "no session plan exists for this booking" as a normal no-op, not an error, exactly like
`HomeworkAssignmentService` already does.

**Fix:** add a new listener method to `SessionPlanService` itself (it already has `sessionRepository`
wired) — do **not** create a separate listener class; `SessionPlanService` is the natural, minimal home
for a method that only reads/writes `Session.status`, and `HomeworkAssignmentService`'s own listener
lives directly in its owning service, not a dedicated `*Listener` class, for the same reason.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void handleBookingCompleted(BookingCompletedEvent event) {
    sessionRepository.findByBookingId(event.getBookingId()).ifPresentOrElse(session -> {
        if (!"COMPLETED".equals(session.getStatus())) {
            session.setStatus("COMPLETED");
            sessionRepository.save(session);
        }
    }, () -> log.debug("No session plan found for completed booking {} — nothing to transition", event.getBookingId()));
}
```

Required additions to `SessionPlanService.java`'s imports:
`com.softropic.skillars.platform.booking.contract.BookingCompletedEvent`,
`org.springframework.scheduling.annotation.Async`,
`org.springframework.transaction.event.TransactionPhase`,
`org.springframework.transaction.event.TransactionalEventListener`.

**Idempotency note:** the `!"COMPLETED".equals(...)` guard exists because `BookingCompletedEvent` could
in principle be re-delivered (event-replay, retry) — re-setting an already-`COMPLETED` plan to
`COMPLETED` again is harmless either way, but the guard avoids an unnecessary write, matching
`HomeworkAssignmentService`'s own idempotency-by-existence-check convention for the same event.

**Interaction with `updateSession`'s existing lock:** `updateSession` (`:119-123`) already throws
`SessionErrorCode.SESSION_PLAN_LOCKED` if `"COMPLETED".equals(session.getStatus())` — once this AC
ships, that guard becomes reachable via the new automatic path too (not just a hypothetical manual
`COMPLETED` set), which is the correct, intended interaction: a coach can no longer edit a session plan
after its booking has completed. No change needed to `updateSession` itself.

**Testing:**
- Add `handleBookingCompleted` tests to `SessionPlanServiceTest.java` (existing file — confirm current
  test setup/mocking conventions there before writing new tests, mirror them):
  - a `DRAFT` session plan found for the event's `bookingId` transitions to `COMPLETED` and is saved.
  - no session plan exists for the `bookingId` (QUICK-mode case) — no-op, no exception, no save call.
  - a session plan already `COMPLETED` — no redundant save call (idempotency).
- Consider one integration-level test (in whichever IT already exercises the full
  confirm-completion → `BookingCompletedEvent` → listener chain, if one exists — e.g. check
  `BookingCompletionResourceIT`/`SessionCompletionResourceIT` for whether they already assert on
  `HomeworkAssignmentService`'s side effects, and mirror that pattern for the session-plan-status side
  effect too) if a suitable end-to-end seam already exists; if none does, unit coverage above is
  sufficient — do not build new end-to-end infrastructure for this alone.

---

### AC5 — Ledger hygiene

Apply these `deferred-work.md` updates:

1. **Close the stale gallery-reorder item** (`## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)`, the `V28__marketplace_coach_media.sql` `UNIQUE (coach_id, display_order)` bullet). Verified via grep across `src/main/java/.../marketplace/` and every frontend page/store/api file: `displayOrder` is **read-only** everywhere in current code (`CoachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc`, mapped into `CoachMediaItemDto`) — no reorder endpoint, service method, or frontend UI exists anywhere in the codebase today. The original concern ("naive gallery reorder impossible without a temp value") is currently unreachable because there is no reorder feature at all, built or in progress. Close with:
   `[CLOSED by skillars-deferred-70 (verified moot): no gallery-reorder feature exists anywhere in current code — displayOrder is read-only. Re-open if/when a reorder feature is actually built, and apply the DEFERRABLE INITIALLY DEFERRED fix (or redesign the reorder API) at that time, not before.]`

2. **Cross-reference-close the superseded half of `skillars-deferred-17`'s D8** (`## Deferred from: code review of skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)`, the `canonical_timezone` reconciliation bullet, currently tagged `[PARTIALLY ADDRESSED by skillars-deferred-63 AC6, still open]`). This is fully superseded by a later, more specific entry: `## Deferred from: story-review and implementation of skillars-deferred-63-product-directed-fairness-and-consistency-fixes (2026-08-24)`'s `CoachProfileService.saveStep4` bullet, itself tagged `[DECIDED 2026-08-25: per-window coach timezone is a deliberate feature, not a bug; saveStep4's write behavior stays as-is; no further action planned beyond skillars-deferred-63's one-time backfill]`. D8's "still open" framing is stale — the decision was already made one day after D8's own `PARTIALLY ADDRESSED` annotation, just recorded under a different heading. Append to D8's own bullet (do not delete either bullet — both retain their own historical detail):
   `[CLOSED by cross-reference: the "still open" write-path half of this item was decided one day later under the skillars-deferred-63 story-review heading below — see that section's own CoachProfileService.saveStep4 bullet, tagged DECIDED 2026-08-25. Per-window coach timezone is a deliberate feature; saveStep4 keeps trusting the request payload; no further action planned.]`

3. **Re-file `CoachProfileService.getPublicProfile`'s round-trip count with corrected framing** (`## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)`, the N+1-queries bullet). Do not close it — it remains factually accurate (8 sequential round-trips, confirmed live at `CoachProfileService.java:331-386`) — but append a note correcting the original "before Epic 3 traffic ramp" framing, which this story's own investigation (see "Why this story exists" above) found to be a misapplied N+1 label: there is no `N` here, `getPublicProfile` is called once per single-`coachId` page view, not in a loop over many coaches. Append:
   `[RE-EVALUATED by skillars-deferred-70: not a classic N+1 — getPublicProfile(coachId) is called once per single-coach page view, not once per row in a larger collection. 8 small, indexed, single-row queries for one profile view is unlikely to be the bottleneck it was originally framed as. Left open and unpicked rather than closed — a real fix (EntityGraph/batch-loading) is still reasonable if this page's latency is ever actually measured and found wanting, but should wait for that evidence rather than being done speculatively.]`

**Testing:** none — this AC is markdown-only ledger editing, no code changes, no test impact.

## Tasks / Subtasks

- [ ] AC1: Gate `BookingRequestPage.vue`'s `playerId` computed property's query-param branch behind
      `!authStore.isPlayer`. Verify via direct code reading (no frontend test infra exists); `npx eslint`
      clean on the touched file.
- [ ] AC2: Collapse `CoachSearchService.buildSort`'s dead-identical `"price"`/`default` switch arms into
      one, preserving the existing explanatory comment. Confirm the correct existing test class name
      covering `buildSort`/search-sort behavior (do not assume `CoachSearchServiceTest` without
      checking) and run it to confirm no regression.
- [ ] AC3: Add `V107__coach_pricing_session_duration_not_valid.sql` and
      `V108__coach_pricing_session_duration_validate.sql` (confirm next free `V` numbers at
      implementation time), splitting `chk_coach_pricing_session_duration` into `DROP` + re-`ADD ...
      NOT VALID` then `VALIDATE CONSTRAINT`. Confirm no existing test asserts on constraint-add timing;
      run whichever existing test(s) cover coach-pricing session-duration validation.
- [ ] AC4: Add `SessionPlanService.handleBookingCompleted(BookingCompletedEvent)` —
      `@TransactionalEventListener(phase = AFTER_COMMIT) @Async`, transitions a found, non-`COMPLETED`
      session plan to `COMPLETED` and saves it; no-ops (with a debug log) if no session plan exists for
      the booking. Add tests to `SessionPlanServiceTest.java`: found-and-transitions,
      not-found-no-op, already-COMPLETED-no-redundant-save.
- [ ] AC5: Apply the three `deferred-work.md` ledger updates specified above (close gallery-reorder item;
      cross-reference-close D8's superseded half; re-file the `getPublicProfile` round-trip item with
      corrected framing, left open).
- [ ] Run the full targeted test sweep for every touched test class (backend) plus `npx eslint` on every
      touched frontend file; confirm no regressions. Do not run `mvn verify` locally — GitHub CI is the
      sole full-verification gate (`docs/validation-strategy.md`).

## Dev Notes

- This story bundles across three modules (Booking/Availability/Reschedule, Marketplace/Coach-profile,
  Session/Drills/Homework) per explicit project-owner instruction not to create a small, single-item
  story, after re-mining `deferred-work.md` found Booking genuinely thin post-`skillars-deferred-69`.
- AC1, AC2, AC3, AC5 are small and mechanical — no design decisions needed, every fix is fully specified
  above with exact current source and exact replacement code/SQL.
- AC4 is the one substantive feature addition in this story — a new domain-event listener, not a bug
  fix. Follow `HomeworkAssignmentService.handleBookingCompleted`'s exact idiom (same file's sibling
  method in spirit, same event, same module) rather than inventing a different shape.
- No new dependencies, no new frontend routes, no new REST endpoints in this story.
- Frontend: this repo has no automated frontend test infrastructure (standing, repeatedly-documented
  gap across dozens of prior stories) — do not introduce one as part of this story; `npx eslint` plus
  direct code reading is the established bar for `.vue`-only changes.
- Backend: follow `docs/validation-strategy.md` — targeted `mvn -o test -Dtest=X` runs only; never run
  `mvn verify` locally; GitHub CI (triggered on PR) is the sole full-verification gate.

### Project Structure Notes

- `src/frontend/src/pages/parent/BookingRequestPage.vue` — AC1.
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchService.java` — AC2.
- `src/main/resources/db/migration/V107__coach_pricing_session_duration_not_valid.sql` (new),
  `V108__coach_pricing_session_duration_validate.sql` (new) — AC3.
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java` — AC4.
- `src/test/java/com/softropic/skillars/platform/session/service/SessionPlanServiceTest.java` — AC4
  tests (existing file).
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC5.

### References

- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingCompletedEvent.java` — event
  shape (`bookingId`, `coachId`, `playerId`, `parentId`, etc.), confirmed unchanged, no new fields
  needed for AC4.
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java:53-88` —
  the exact listener pattern AC4 mirrors.
- `src/main/resources/db/migration/V105__payment_currency_format_guard.sql`,
  `V106__payment_currency_format_guard_validate.sql` — the exact `NOT VALID`/`VALIDATE CONSTRAINT` split
  pattern AC3 mirrors.
- `_bmad-output/implementation-artifacts/skillars-deferred-69-....md` — immediately preceding story,
  confirms Booking/Availability/Reschedule's current state and its own 4 intentionally-deferred review
  findings (not re-litigated by this story).

## Dev Agent Record

### Agent Model Used

_To be filled in by the dev agent._

### Debug Log References

_To be filled in by the dev agent._

### Completion Notes List

_To be filled in by the dev agent._

### File List

_To be filled in by the dev agent._

## Change Log

- 2026-08-26: Story created via story-creation process. Re-mined `deferred-work.md` module-by-module in
  the project owner's specified priority order (Booking/Availability/Reschedule → Marketplace/Coach-
  profile → Auth/Registration → Session/Drills/Homework). Booking/Availability/Reschedule confirmed
  genuinely thin after `skillars-deferred-69`'s own 7 closures plus 4 intentionally-deferred review
  findings — only one fresh mechanical item found (AC1). Bundled forward into Marketplace (AC2, AC3) per
  explicit instruction not to create a small story. A `CoachProfileService.getPublicProfile` "N+1"
  candidate was investigated and found to be a misapplied label (no `N` — one query per single-coach
  page view, not per row in a collection) — re-filed with corrected framing rather than picked up (AC5).
  A `CoachCommandCenterPage.vue` hardcoded-locale candidate was checked and found already correct,
  already documented via code comment — not picked up, not re-filed (never was an open ledger item).
  One item required a product decision, gathered interactively from the project owner during this
  story's creation: whether `SessionPlanService`'s `COMPLETED` status should be wired up to actually
  fire when a booking completes, or left as a currently-dead status value — decided **wire it up**
  (2026-08-26), scoped as AC4, mirroring `HomeworkAssignmentService`'s existing `BookingCompletedEvent`
  listener pattern in the same module. AC5 closes one stale ledger item (gallery-reorder, verified moot
  — no reorder feature exists anywhere in current code) and cross-reference-closes one item whose "still
  open" tag was stale (superseded by a later DECIDED note recorded under a different heading one day
  later).
