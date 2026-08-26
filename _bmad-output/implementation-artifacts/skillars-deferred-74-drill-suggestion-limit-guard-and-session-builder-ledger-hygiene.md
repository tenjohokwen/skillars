# Story Deferred-74: Drill-Suggestion Limit Guard & Session-Builder Ledger Hygiene

Status: done

## Story

As a platform owner continuing the `deferred-work.md` drawdown, I want (1) a real, now-live gap closed where
`SessionCompletionResource.getDrillSuggestions`'s `limit` query parameter has no upper bound, and (2) ledger
hygiene closing two now-stale, already-fixed-elsewhere `deferred-work.md` items found while re-mining the
Session/Drills/Homework module (Epic 4/5), so that the next re-mining pass starts from an accurate ledger.

### Why this story exists

Escalated to Session/Drills/Homework per the project owner's own explicit choice: the prior two modules
(Booking/Availability/Reschedule, then Marketplace/Coach-profile) were both re-confirmed exhausted by
`skillars-deferred-72`, and a sampled sweep of Auth/Registration (`skillars-deferred-73`) found only
ledger-hygiene closures with no live bugs — the project owner was offered a full ~65-item Auth/Registration
sweep, escalating to the next module, or pausing the series, and chose to escalate.

All ~20 `## Deferred from:` sections for Session/Drills/Homework (`skillars-4-1` through `skillars-4-6`,
`skillars-5-1` through `skillars-5-6`, dated 2026-06-15 through 2026-06-19) were read in full — never
re-mined by any of the 73 prior stories in this series. Most of the roughly 80 bullets in this block carry
their own explicit, still-valid reasoning for staying open (accepted tradeoffs, spec-mandated behavior,
pre-existing patterns mirrored on purpose) — the same shape found in Marketplace/Coach-profile and
Auth/Registration. One item converts from deferred to genuinely live on re-reading; two more are stale.

**AC1 required a project-owner decision, gathered directly.** `SessionCompletionResource
.getDrillSuggestions`'s `limit` parameter (`## Deferred from: code review of
skillars-3-6-session-completion-live-mode-quick-complete (2026-06-16)`, W4) was originally filed as: `` W4:
`getDrillSuggestions` has no `@Max` constraint on `limit` parameter — stub endpoint fully replaced by Epic
4; guard when real implementation lands [`SessionCompletionResource.java`] ``. Verified: the endpoint is no
longer a stub — `getDrillSuggestions` (`:96-104`) calls the real
`DrillSuggestionService.suggest(session.getId(), currentUserId(), limit)` (Epic 4's drill-suggestion
engine), which passes `limit` unbounded into two separate `.stream()....limit(limit)` calls (`:71`, `:124`
of `DrillSuggestionService.java`). This item's own stated precondition for action ("guard when real
implementation lands") has now been met, and the guard still doesn't exist — converting this from a
deferred item into a live one. No design intent for the cap value was found anywhere (no config key, no
comment); the project owner was asked directly and chose **10** — generous headroom over the endpoint's own
default of 2, matching the general shape of the one existing precedent in this codebase
(`CoachMarketplaceResource`'s page-size cap of 50 against a default of 20).

**A second, easy-to-miss gotcha was found during investigation and fixed in the same pass, since shipping
the `@Max` annotation alone would silently do nothing:** `SessionCompletionResource` (verified by direct
read of its full import/class-declaration block) carries no `@Validated` class-level annotation. Spring MVC
silently ignores `@Min`/`@Max` on a bare `@RequestParam` without `@Validated` present on the enclosing
class — confirmed by finding this codebase's own established working precedent,
`CoachMarketplaceResource.java`, which pairs `@Validated` at the class level with
`@RequestParam(defaultValue = "20") @Min(1) @Max(50) int size`. AC1 adds `@Validated` to
`SessionCompletionResource` as well as the `@Min`/`@Max` constraint itself — a spec that added only the
latter would look correct but silently do nothing.

## Acceptance Criteria

### AC1 — Bound `SessionCompletionResource.getDrillSuggestions`'s `limit` parameter

**Current behavior, verified against live source:**

```java
@GetMapping("/session/{bookingId}/drills/suggestions")
@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
public ResponseEntity<List<DrillResponse>> getDrillSuggestions(
        @PathVariable UUID bookingId,
        @RequestParam(defaultValue = "2") int limit) {
    return sessionRepository.findByBookingId(bookingId)
        .map(session -> ResponseEntity.ok(drillSuggestionService.suggest(session.getId(), currentUserId(), limit)))
        .orElseGet(() -> ResponseEntity.ok(List.of()));
}
```

(`src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java:96-104`.) No
upper bound anywhere in the call chain — `DrillSuggestionService.suggest` (`:40`) takes `limit` as a plain
`int` and passes it straight into `.limit(limit)` on two separate streams (`:71`, `:124`), with no clamping.
`SessionCompletionResource`'s class declaration carries no `@Validated` annotation (confirmed by reading
the file's full import list and class header), so adding `@Min`/`@Max` to the parameter alone would be
silently ignored by Spring MVC.

**Fix — one file:**

1. **`src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`** — add
   `jakarta.validation.constraints.Max`, `jakarta.validation.constraints.Min`, and
   `org.springframework.validation.annotation.Validated` to imports; add `@Validated` to the class
   declaration; constrain the `limit` parameter:
   ```java
   @Observed(name = "booking.completion")
   @RestController
   @RequestMapping("/api/bookings")
   @RequiredArgsConstructor
   @Slf4j
   @Validated
   public class SessionCompletionResource {
       ...
       @GetMapping("/session/{bookingId}/drills/suggestions")
       @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
       public ResponseEntity<List<DrillResponse>> getDrillSuggestions(
               @PathVariable UUID bookingId,
               @RequestParam(defaultValue = "2") @Min(1) @Max(10) int limit) {
   ```
   Mirrors `CoachMarketplaceResource`'s identical, already-shipped `@Validated` + `@Min`/`@Max` pattern for
   its own `size` parameter exactly — no new idiom introduced.

2. **Ledger closure** — append to the exact bullet quoted above (`## Deferred from: code review of
   skillars-3-6-session-completion-live-mode-quick-complete (2026-06-16)`, W4):
   `` `[CLOSED by skillars-deferred-74: this item's own stated precondition ("guard when real
   implementation lands") was met — getDrillSuggestions now calls the real
   DrillSuggestionService.suggest(...) — and the guard still didn't exist, converting this from deferred
   to a live gap. Fixed: @Validated added to the class (required for method-parameter @Min/@Max to be
   enforced at all — confirmed absent before this fix), limit constrained to @Min(1) @Max(10), mirroring
   CoachMarketplaceResource's identical size-param pattern. Max value (10) was a project-owner decision,
   generous headroom over the endpoint's own default of 2.]` ``

**Testing:** add to `SessionCompletionResourceIT.java` (already covers this endpoint's happy path via
`getDrillSuggestions_returns200AndEmptyArray`):
- `getDrillSuggestions_limitAboveMax_returns400` — `limit=11` → 400.
- `getDrillSuggestions_limitBelowMin_returns400` — `limit=0` → 400.
- `getDrillSuggestions_limitAtMax_returns200` — `limit=10` → 200 (boundary-inclusive, proves `@Max(10)`
  doesn't accidentally reject the boundary itself).

Run `mvn -o test -Dtest=SessionCompletionResourceIT` — all tests green, including the three new ones and
the pre-existing `getDrillSuggestions_returns200AndEmptyArray` (proving the unconstrained default of 2
still works unchanged).

---

### AC2 — Ledger hygiene: close two stale/already-fixed items

Both individually re-verified against live current source during this story's creation (not assumed from
the ledger's own text). Apply these `deferred-work.md` edits (locate each by its quoted text — line numbers
shift, do not trust them without re-grepping first):

1. **`SessionPlanService`'s `COMPLETED` transition wiring — already shipped, unannotated.** Bullet (`##
   Deferred from: code review of skillars-4-4-session-builder-block-structure-dna (2026-06-18)`), W1: ``
   COMPLETED status transition never wired from booking completion — `session.status` is set to
   `COMPLETED` on `createSession` guard but no code path (booking completion event, scheduler, or explicit
   endpoint) ever transitions a DRAFT/SAVED session to COMPLETED. Cross-story dependency: Story 3.6
   booking completion event flow. [`SessionPlanService.java`] `` . Verified:
   `SessionPlanService.handleBookingCompleted` (`:150-161`), a
   `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) @Async
   @Transactional(propagation = Propagation.REQUIRES_NEW)` listener on `BookingCompletedEvent`, transitions
   any non-`COMPLETED` session to `COMPLETED` on booking completion, exactly closing the gap this item
   named. Shipped by `skillars-deferred-70` AC4, per that story's own Change Log, but the ledger bullet this
   item named was never tagged closed. Append: `` `[CLOSED by skillars-deferred-74 (verified already
   fixed): SessionPlanService.handleBookingCompleted, a @TransactionalEventListener(AFTER_COMMIT) on
   BookingCompletedEvent, transitions any DRAFT/SAVED session to COMPLETED. Shipped by skillars-deferred-70
   AC4, never tagged closed on this bullet.]` ``

2. **`POST /api/session/plans` empty-body stub — replaced by the real implementation.** Bullet (`##
   Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)`), D4: ``
   `POST /api/session/plans` returns 201 empty body — intentional stub per story dev notes; full
   implementation in Story 4.4 [`SessionPlanResource.java`] `` . Verified: the named file
   `SessionPlanResource.java` no longer exists (renamed); the endpoint now lives in
   `SessionBuilderResource.createSession` (`:36-41`), which returns a real, fully-populated
   `SessionPlanResponse` body via `sessionPlanService.createSession(req, currentCoachUserId())` —
   `ResponseEntity.status(HttpStatus.CREATED).body(resp)`, not an empty body. Matches the item's own stated
   closing condition ("full implementation in Story 4.4") exactly. Append: `` `[CLOSED by
   skillars-deferred-74 (verified already fixed): the endpoint (now in SessionBuilderResource.createSession,
   the file having been renamed) returns a real SessionPlanResponse body via
   sessionPlanService.createSession(...), not an empty 201. Story 4.4 delivered the full implementation
   this item's own text anticipated.]` ``

**Testing:** none — this AC is markdown-only ledger editing, no code changes, no test impact.

## Tasks / Subtasks

- [x] AC1: Add `@Validated` to `SessionCompletionResource`. Constrain `getDrillSuggestions`'s `limit`
      parameter with `@Min(1) @Max(10)`. Add three new tests to `SessionCompletionResourceIT.java`. Append
      the AC1 ledger-closure tag to `deferred-work.md`. Run `mvn -o test -Dtest=SessionCompletionResourceIT`
      — all tests green.
- [x] AC2: Apply both `deferred-work.md` closure edits specified above.
- [x] Run the full targeted test sweep for the touched test class; confirm no regressions. Do not run
      `mvn verify` locally — GitHub CI is the sole full-verification gate (`docs/validation-strategy.md`).

## Dev Notes

- This story was implemented directly during its own creation (not left as a spec for a separate
  `dev-story` pass) — AC1 needed the project owner's decision on the cap value before it could be
  specified precisely, and once that decision was made the fix was small and well-scoped enough to
  implement and verify in the same pass, matching the precedent `skillars-deferred-72`'s AC3/AC4 set for
  decision-gated-but-small fixes found during story creation.
- The `@Validated`-is-required gotcha is the single most important thing to preserve if this story is ever
  reverted or partially reapplied: `@Min`/`@Max` on a bare `@RequestParam` does nothing without
  `@Validated` on the enclosing class. Do not "simplify" by removing the class-level annotation.
- Backend: follow `docs/validation-strategy.md` — targeted `mvn -o test -Dtest=X` runs only; never run
  `mvn verify` locally; GitHub CI (triggered on PR) is the sole full-verification gate.
- Frontend: no frontend files touched by this story.
- No new database migrations in this story.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java` — AC1.
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java` — AC1
  (three new tests).
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC1 (1 closure), AC2 (2 closures).

### References

- `src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java` — AC1's
  exact structural precedent (`@Validated` class-level + `@Min`/`@Max` on a `@RequestParam`).
- `src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java` — the
  unbounded `.limit(limit)` call chain AC1's guard protects (`:71`, `:124`).
- `skillars-deferred-72-...md`, `skillars-deferred-73-...md` — immediately preceding stories in this
  series; `-72` set the "small decision-gated fix implemented directly during story creation" precedent
  this story's AC1 follows, and `-73` set the "escalate through the module-priority order, ask the project
  owner when the ledger comes up thin" precedent this story's own creation followed.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code)

### Debug Log References

None — no failures encountered. The targeted test run passed on first execution after implementation.

### Completion Notes List

- Session/Drills/Homework's ~20 sections (~80 bullets) read in full; the overwhelming majority carry their
  own still-valid accepted-tradeoff reasoning, matching the pattern found in the two prior modules.
- AC1: `SessionCompletionResource` gained `@Validated` (required — confirmed absent before this fix, and
  without it `@Min`/`@Max` on a bare `@RequestParam` is silently ignored by Spring MVC) plus
  `@Min(1) @Max(10)` on `getDrillSuggestions`'s `limit` parameter, closing a ledger item that had converted
  from deferred to live once its own stated precondition ("guard when real implementation lands") was met.
  Cap value (10) was a direct project-owner decision, gathered interactively — no design intent existed
  anywhere in config or comments. Three new tests added (`limitAboveMax`/`limitBelowMin` → 400,
  `limitAtMax` → 200, boundary-inclusive). `mvn -o test -Dtest=SessionCompletionResourceIT` 20/20 green
  (17 existing + 3 new, including the pre-existing default-limit-of-2 happy path unchanged).
- AC2: two stale ledger items closed, each independently re-verified against live source —
  `SessionPlanService.handleBookingCompleted` (shipped by `skillars-deferred-70` AC4, never tagged) and
  `SessionBuilderResource.createSession` (real response body, not the empty-201 stub the item described).
- No frontend files touched; no `npx eslint` run needed.

### File List

- `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java`
- `_bmad-output/implementation-artifacts/deferred-work.md`

## Change Log

- 2026-08-26: Story created via story-creation process and implemented directly in the same pass. Escalated
  to Session/Drills/Homework per the project owner's explicit choice after Booking/Availability/Reschedule
  and Marketplace/Coach-profile were both re-confirmed exhausted (`skillars-deferred-72`) and a sampled
  Auth/Registration sweep (`skillars-deferred-73`) found only ledger hygiene. Re-mined all ~20
  Session/Drills/Homework sections (~80 bullets), never previously re-mined. One item converted from
  deferred to genuinely live on re-reading: `SessionCompletionResource.getDrillSuggestions`'s unbounded
  `limit` parameter, whose own filed precondition for action ("guard when real implementation lands") had
  since been met. The project owner was asked directly for the cap value (no existing design intent found
  anywhere) and chose 10; implemented directly in this same pass, including a `@Validated`-is-required
  gotcha found during investigation (confirmed absent, would have silently no-opped a `@Min`/`@Max`-only
  fix). Two further items closed as stale/already-fixed: `SessionPlanService.handleBookingCompleted`
  (shipped unannotated by `skillars-deferred-70` AC4) and the `SessionBuilderResource.createSession`
  empty-201-stub concern (superseded by the real Story 4.4 implementation). `mvn -o test
  -Dtest=SessionCompletionResourceIT` 20/20 green. Status set directly to `done` — fully implemented and
  verified at creation, matching the `skillars-deferred-72` AC3/AC4 precedent for small, decision-gated
  fixes.
