# Story: Outbox Claim-Phase Isolation, Radar Stale-Window Margin & Session-Redirect Fixes

**Story Key:** `skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes`
**Epic:** Deferred Work
**Priority:** High (a real outbox/DLQ claim-phase availability gap on both processors, a real
zero-margin scheduler-lock/stale-window relationship on one processor, and a real frontend session-
redirect failure mode) — plus a repo-wide migration-convention hardening and the standard ledger
closeout.
**Status:** ready-for-dev
**Created:** 2026-09-19

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md` per this
project's established "read same-day code-review deferrals before drawing scope" convention, plus one
older but still-genuinely-open item surfaced during the full-file re-audit this story creation ran:

- **`## Deferred from: code review of skillars-deferred-124-strike-lock-contention-outbox-resilience-
  and-schema-fixes (2026-09-19)`** — 4 bullets, all freshly surfaced by that story's own
  `/bmad-code-review` (Edge Case Hunter + Blind Hunter layers) and independently re-verified against
  HEAD (`77036728`, master, post-PR #212) during this story's creation, not merely re-read. All four
  are pre-existing behaviour skillars-deferred-124 neither introduced nor was scoped to fix.
- **`code review (round 2) of 1-7b-session-refresh-rint-contract-fix (2026-09-02)`** — the
  `sessionManager.js`/`App.vue` router-abort re-arm gap, confirmed still open and untouched by every
  subsequent audit since (most recently the 2026-09-10 skillars-deferred-108 post-merge sweep, which
  explicitly left it "item stays open").

**Not in scope**, confirmed by direct re-verification during this story's creation (see the ledger's
own "Explicitly out of scope" section, `skillars-deferred-123, 2026-09-18`, plus this story's own
re-check):

- `main."user"` has no index supporting the cleanup-sweep predicate
  (`activated`/`created_date`/`cleanup_failed_at`). Owner decision (AskUserQuestion, 2026-09-19):
  **keep deferring** — the ledger's own blocker (production row-count/`EXPLAIN` evidence, per
  `docs/deployment/migration-conventions.md`'s guidance on hot-table indexing) still cannot be met
  pre-launch, and guessing an index shape risks shipping the wrong one as technical debt. No AC in
  this story touches it; left exactly as-is in the ledger.

Three owner decisions were taken live with the user (`AskUserQuestion`) before drafting this story —
see AC2, AC4, and the "Not in scope" note above.

**Line numbers below were re-verified against HEAD (`77036728`) at story-creation time, 2026-09-19,
not copied from the ledger's own (already slightly drifted) citations.** Re-verify again immediately
before implementing each AC — this series' own established convention, since every prior story in it
has found at least minor drift between story-creation time and dev-story time.

---

## AC1 — Outbox/DLQ claim-phase isolation: release a stranded claim on abnormal exit

**The finding (re-verified against HEAD, still live).** Both processors run three repository calls
back-to-back with no exception handling around any of them:

- `VideoDeletionOutboxProcessor.process()` (`VideoDeletionOutboxProcessor.java:156-159`):
  `resetStaleClaimed` → `claimPendingBatch` → `findClaimedBatch`.
- `RadarCompositeDlqProcessor.process()` (`RadarCompositeDlqProcessor.java:125-127`): the identical
  three-call sequence.

`claimPendingBatch` is its own auto-committing native `@Modifying` UPDATE (see
`VideoDeletionOutboxRepository.java:90-104`'s own comment on why `process()` is deliberately not
`@Transactional`) — by the time it returns, up to `BATCH_SIZE` (50) rows are durably `CLAIMED` under
this run's `runId`, committed to the database, independent of anything that happens next in the Java
method. If the very next call, `findClaimedBatch`, throws — a connection reset, a statement timeout, a
pool-exhaustion `CannotGetJdbcConnectionException` — those rows are never handed to the loop, the
method-local `runId` variable goes out of scope when the exception propagates out of `process()`, and
nothing in this codebase can ever again address those specific rows by that `runId`. They sit `CLAIMED`
until `resetStaleClaimed`'s own next-tick invocation frees them by elapsed time alone —
`STALE_CLAIM_WINDOW` — 20 minutes for video, 10 (soon 15, see AC2) for radar — even though the very
next scheduled tick, 60 seconds later, could otherwise have retried them immediately.

skillars-deferred-124 AC2's own new per-row guard (the `handleFailure` routing inside the `for` loop)
deliberately covers only the loop body, by design — this is a distinct, earlier phase of `process()`
that guard was never meant to reach.

**The fix is NOT a bare `try`/`finally`.** A `finally` block always runs, including on the successful
path — releasing the batch back to `PENDING` immediately after `claimPendingBatch` committed it, before
a single row is processed, undoing the claim you just took and racing a concurrent invocation into
re-claiming and double-processing the same rows. The correct shape is a `try`/`catch` that releases
**only on the exceptional path**, then rethrows (or logs and returns, matching this method's existing
no-throw-out-of-`process()` convention — check `handleFailure`'s own return behaviour for the
precedent) — never touching the claim on the happy path.

### Tasks

1. Re-verify this premise directly against the current source in both files before implementing —
   confirm the exact call sequence and line numbers, and confirm neither `claimPendingBatch` nor
   `findClaimedBatch` already has any exception handling (they do not, per the citations above, but
   re-check at implementation time per this story's own stated convention).
2. In `VideoDeletionOutboxProcessor.process()`, wrap `claimPendingBatch` + `findClaimedBatch` in a
   `try`/`catch` that, on any exception, calls `outboxRepository.releaseClaimed(runId)` and then
   re-throws (or logs at the same level `process()`'s other unexpected-failure paths use — check
   existing precedent in this class and `handleFailure` before choosing). Do **not** wrap
   `resetStaleClaimed` in the same block — it runs before any claim exists for this `runId`, so there
   is nothing of this run's to release if it throws.
3. Apply the identical fix to `RadarCompositeDlqProcessor.process()`.
4. New test per processor: force an exception from `findClaimedBatch` (a spy/mock, matching this
   codebase's established `@MockitoSpyBean` pattern from skillars-deferred-124 AC2's own new tests)
   and assert `releaseClaimed` was invoked with the run's `runId`, and that the exception path does not
   silently swallow the failure (assert whatever the chosen rethrow/logging behaviour actually is).
   A second test proves the happy path is unaffected: `releaseClaimed` is `never()` invoked when no
   exception occurs.
5. Mutation-check by hand: temporarily revert the `try`/`catch`, confirm the new test fails as
   expected; restore, confirm it passes.
6. Re-run both processors' full test classes plus any repository IT touching `releaseClaimed`/
   `findClaimedBatch` together — zero regressions expected.

---

## AC2 — RadarCompositeDlqProcessor: restore a real stale-window margin above its scheduler lock

**The finding (re-verified against HEAD, still live).**
`RadarCompositeDlqProcessor.STALE_CLAIM_WINDOW` (`RadarCompositeDlqProcessor.java:89`, currently
`Duration.ofMinutes(10)`) is **exactly equal** to its own `@SchedulerLock(lockAtMostFor = "PT10M")`
(`:116`) — zero buffer between the two. Its structural twin, `VideoDeletionOutboxProcessor`, documents
strict inequality between the same two quantities as mandatory (its own `STALE_CLAIM_WINDOW` Javadoc,
`VideoDeletionOutboxProcessor.java:34-58`): `MAX_RUN_DURATION (12m) < LOCK_AT_MOST_FOR (15m) <
STALE_CLAIM_WINDOW (20m)`, a 5-minute buffer between the middle and outer terms.

The equality is not simply stale framing — `RadarCompositeDlqProcessor`'s own class Javadoc
(`:55-77`) already documents, at length, a **prior, deliberate decision** (skillars-deferred-123 code
review, Decision 3) to leave the 10-minute/`PT10M` equality unchanged and instead rely on
`MAX_RUN_DURATION` (`:78`, 8 minutes) self-terminating the run before the lock can expire. That
decision's own reasoning holds *if* `MAX_RUN_DURATION` were a hard, continuously-enforced ceiling — but
skillars-deferred-124's own code review (see the current ledger bullet, `deferred-work.md`) found it is
not: the deadline is sampled only at the top of each loop iteration (`process()`'s `for` loop,
`RadarCompositeDlqProcessor.java:136-143`), never inside `processRow`/`handleFailure` itself. One row
that blocks longer than `lockAtMostFor - MAX_RUN_DURATION` — a 2-minute margin for this class — still
overruns the lock. Once that happens, the zero-margin equality means the *very next* tick's
`resetStaleClaimed` immediately frees and re-claims the still-processing run's rows, causing a real
duplicate `recalculateComposite` — an external side effect `claimed_by` cannot undo after the fact,
only prevent from being written twice.

The guard test, `RadarCompositeDlqProcessorTest.runtimeBudget_staysStrictlyInsideLock`
(currently `RadarCompositeDlqProcessorTest.java:277-290`), asserts `maxRun < lockAtMostFor` and
`maxRun < staleWindow` but **never asserts `lockAtMostFor < staleWindow`** — the one inequality this
class actually violates, and the one `VideoDeletionOutboxProcessor`'s equivalent Javadoc states as the
load-bearing invariant.

**Owner decision (AskUserQuestion, 2026-09-19): widen `STALE_CLAIM_WINDOW` and add the missing test
assertion.** Chosen over leaving the values as-is (the current Javadoc's own accepted-equality framing)
and over engineering a hard per-row timeout around `recalculateComposite` (materially more complex for
a rare edge case). The remaining, narrower per-row-overrun residual from skillars-deferred-124's review
(bullet 2, `MAX_RUN_DURATION` sampled only between rows) is **accepted as documented risk**, not fixed
by this AC — it becomes markedly less consequential once a real buffer exists between lock expiry and
the stale sweep, since a single overrunning row no longer immediately triggers a duplicate reclaim.

### Tasks

1. Re-verify the current values and line numbers directly against HEAD before implementing.
2. Widen `STALE_CLAIM_WINDOW` from `Duration.ofMinutes(10)` to `Duration.ofMinutes(15)` — a 5-minute
   buffer above `lockAtMostFor` (`PT10M`), matching `VideoDeletionOutboxProcessor`'s own buffer
   *amount* between its `LOCK_AT_MOST_FOR` and `STALE_CLAIM_WINDOW` (15m → 20m is also a 5-minute
   gap), not just its ratio — this class's own Javadoc precedent for "mirror the sibling's numbers,
   don't invent new ones" (e.g. `MAX_RUN_DURATION`'s existing 8-minutes-under-`PT10M` derivation from
   `QuotaReservationTimeoutService`).
3. Rewrite `STALE_CLAIM_WINDOW`'s Javadoc (`:80-89`) — it currently states the value is "deliberately
   unchanged at 10 minutes" and that widening it "is no longer what makes the equality with `PT10M`
   safe." That reasoning is being explicitly revised by this AC: record why (skillars-deferred-124's
   own review found the `MAX_RUN_DURATION` bound is not airtight against a single slow row, so a real
   buffer is worth restoring as defense-in-depth even with the bound in place), and cross-reference
   this story.
4. Add the missing assertion to `runtimeBudget_staysStrictlyInsideLock`:
   `assertThat(lockAtMostFor).isLessThan(staleWindow)`, with an `.as(...)` explaining it is the one
   inequality `VideoDeletionOutboxProcessor`'s equivalent test coverage already treats as load-bearing
   and this class's previously did not.
5. Add a short code comment (near `MAX_RUN_DURATION` or in the class header) explicitly naming the
   accepted residual: a single row that individually blocks longer than the lock/window buffer can
   still overrun, and that this is accepted (not fixed) per this story's owner decision — so a future
   reader does not mistake this AC for a complete close-out of skillars-deferred-124's bullet 2.
6. Mutation-check: confirm the new assertion currently fails against the unwidened value (write it
   first, watch it fail, matching this project's own established practice), then widen the value and
   confirm it passes.
7. Re-run `RadarCompositeDlqProcessorTest` in full — zero regressions expected. Confirm no other test
   in this class or `RadarCompositeDlqRepositoryIT` hardcodes the old 10-minute value.

---

## AC3 — Session-expiry redirect: fall back to a hard navigation if `router.push` fails

**The finding (re-verified against HEAD, still live — untouched since the 2026-09-02 review first
raised it, and explicitly reconfirmed open by the 2026-09-10 skillars-deferred-108 post-merge sweep).**
`App.vue`'s `handleSessionExpired` (`src/frontend/src/App.vue:27-40`) clears cookies, calls
`authStore.logout()`, calls `playerStore.resetSelfPlayerId()`, calls `cleanup()` (which stops
`sessionManager.js`'s monitoring interval), and then calls `router.push({...})` (`:36-39`) — **without
`await`ing it or attaching a `.catch()`**. Vue Router 4's `router.push` returns a `Promise` that
**rejects** on a failed navigation (a guard cancels it, a duplicate-navigation, or a concurrently
in-flight navigation superseding it — all real Vue Router 4 outcomes, not hypothetical).

By the time that rejection would fire, every piece of session state has already been irreversibly torn
down: the auth cookie is cleared, in-memory auth/player state is reset, and — critically —
`cleanup()` has already stopped `sessionManager.js`'s monitoring interval (`startSessionMonitoring`'s
early-return-on-already-expired path, `src/frontend/src/plugins/sessionManager.js:231`, is the other
half of this same gap: an already-expired session at mount never arms ongoing monitoring at all,
since `tick()` has already dispatched the event synchronously). If the redirect silently fails, the
user is left on the current route with no active session, no active monitoring, and no mechanism left
to retry the navigation — stuck until their next API call happens to 401.

### Tasks

1. Re-verify `App.vue`'s current `handleSessionExpired` and `sessionManager.js`'s `startSessionMonitoring`
   directly against HEAD before implementing — confirm line numbers and confirm the `router.push` call
   is still unawaited/uncaught.
2. Make `handleSessionExpired`'s `router.push` call handle a rejected navigation: on failure, fall back
   to a hard navigation (`window.location.href = ...`, or `window.location.assign(...)`, bypassing the
   router entirely) to the same `/login?redirect=...&expired=true` target, so the user is guaranteed to
   leave the now-invalid page regardless of why the router-level navigation failed. Decide between
   `.catch()` chaining vs. making the function `async`/`await` with `try`/`catch` — either is
   acceptable, match this file's existing style (currently synchronous, no other `async` function in
   this component).
3. New test: mount `App.vue` (first component-mount test in this frontend suite to touch
   `useRouter()` — no existing spec mocks `vue-router`, so this establishes the pattern; stub
   `GlobalLoadingBar`/`SessionWarningDialog` and the two Pinia stores per this project's existing
   component-test conventions elsewhere in the suite) with a mocked router whose `push` rejects, then
   dispatch a `session:expired` event on `window` and assert the hard-navigation fallback fired with
   the expected URL. A second test proves the happy path (router `push` resolves) does **not** trigger
   the fallback — no double-navigation.
4. Mutation-check by hand: temporarily remove the fallback, confirm the new failure-path test fails as
   expected; restore, confirm both tests pass.
5. Run the full `App.vue`/`sessionManager.js` spec suite together — zero regressions expected. This
   story's PR needs the `frontend-tests` label per this repo's standard CI convention for
   frontend-logic changes.

---

## AC4 — Migration convention: require `SET LOCAL lock_timeout`, not session-scoped `SET`, going forward

**The finding (re-verified against HEAD, still live).** All four of this codebase's `lock_timeout`-
setting migrations — `V144`, `V145`, `V149`, `V150` — use plain `SET lock_timeout = '5s';`
(session-scoped: it persists for the rest of the database session) rather than `SET LOCAL
lock_timeout = '5s';` (transaction-scoped: automatically resets at `COMMIT`/`ROLLBACK`). Flyway runs
every pending migration in a single deploy over **one** JDBC connection/session — a session-scoped
`SET` in an earlier migration silently carries forward into a later migration in the same deploy run
that never declared its own timeout, or that assumed the platform default. `MigrationLint`'s existing
`Rule.MISSING_LOCK_TIMEOUT` regex (`MigrationLint.java:310-311`,
`LOCK_TIMEOUT_DIRECTIVE`) already accepts either spelling — `SET` or `SET LOCAL` — equally, so nothing
today distinguishes or discourages the leakier form.
`docs/deployment/migration-conventions.md` item 7 (`:189-205`) actively **recommends** the leakier
session-scoped form today ("Put `SET lock_timeout = '5s';` near the top of the migration (it is a
session/transaction setting, so one statement covers the whole script)") — the doc's own phrasing does
not distinguish the two scopes either.

**Owner decision (AskUserQuestion, 2026-09-19): require `SET LOCAL` for all new migrations going
forward, via a doc update plus a new, enforced `MigrationLint` rule — the four already-shipped
migrations are not rewritten** (Flyway migrations are immutable once applied; editing a shipped
script's content changes its checksum and breaks every environment that already ran it).

**Design note for implementation**, following this codebase's own established precedent exactly:
`MigrationLint.java` already has a mechanism for exactly this situation — a new rule that must not
retroactively flag already-shipped migrations. `GRANDFATHER_BASELINE` (`:112`) and
`DEFERRED_92_BASELINE` (`:125`) are two existing, distinct version-boundary constants for this purpose,
each gating a different rule set introduced at a different time; the class's own Javadoc (`:29-53`)
documents exactly why a *new* boundary constant is the established pattern when a *new* rule arrives
after migrations already exist above the old boundary(-ies), rather than editing an existing constant.
`V150` is the newest migration at story-creation time (re-confirm the actual tip version at
implementation time — a concurrent story could have added a newer one since). The natural shape is a
third boundary constant at the current tip (e.g. `V150`), a new `Rule` enum member (e.g.
`SESSION_SCOPED_LOCK_TIMEOUT`), and a check that a `SET lock_timeout` (without `LOCAL`) in a migration
**above** that boundary is a violation — while `V144`/`V145`/`V149`/`V150` themselves stay exempt by
virtue of sitting at or below it. Confirm this reasoning against the actual class before implementing;
it is a design direction, not a literal diff to copy.

### Tasks

1. Re-verify all four migrations' current `SET lock_timeout` lines, `MigrationLint`'s current
   `LOCK_TIMEOUT_DIRECTIVE` regex, and the current tip migration version directly against HEAD before
   implementing.
2. Add a new boundary constant to `MigrationLint.java` at the current tip version, following the
   existing `GRANDFATHER_BASELINE`/`DEFERRED_92_BASELINE` pattern (own Javadoc explaining why a new
   constant rather than reusing an existing one, per that class's own documented convention).
3. Add a new `Rule` enum member for a plain (non-`LOCAL`) `SET lock_timeout` in a migration above that
   boundary, with its own Javadoc following this file's existing per-rule documentation style.
4. Implement the check and wire it into whatever aggregation/reporting mechanism the existing rules use
   (read the class fully first — do not assume the shape without checking how `MISSING_LOCK_TIMEOUT`
   and its siblings are invoked and reported).
5. New test(s) in `MigrationConventionLintTest` (or wherever the existing rule tests live — locate the
   actual test class first): a synthetic migration above the new boundary using plain `SET
   lock_timeout` triggers the new violation; the same content at or below the boundary does not; a
   synthetic migration above the boundary using `SET LOCAL lock_timeout` does not trigger it either.
   Confirm none of the four real shipped migrations (`V144`/`V145`/`V149`/`V150`) trip the new rule
   when the full real migration directory is linted.
6. Update `docs/deployment/migration-conventions.md` item 7 (`:189-205`): change the prescribed form to
   `SET LOCAL lock_timeout = '5s';`, add the rationale (Flyway's single-session-per-deploy connection
   reuse, and why `LOCAL` closes the leak `SET` does not), and note the grandfather boundary so a
   future reader understands why the four earlier migrations look inconsistent with the now-current
   rule.
7. Re-run `MigrationConventionLintTest` in full against the real migration directory — zero regressions
   expected, and the four real migrations must not newly fail.

---

## AC5 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story
genuinely fixes; annotate `[DECIDED: accepted risk — skillars-deferred-125]` what it deliberately
does not fix; leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-124-... (2026-09-19)`:
   - **Bullet 1** (outbox/DLQ claim-phase guard) — delete outright, fully fixed by AC1.
   - **Bullet 2** (`MAX_RUN_DURATION` sampled only between rows) — **do not delete.** Annotate
     `[DECIDED: accepted risk — skillars-deferred-125]`, citing AC2's own accepted-residual comment
     (the buffer AC2 restores makes this materially less consequential, but does not eliminate it).
   - **Bullet 3** (`STALE_CLAIM_WINDOW` == `lockAtMostFor` zero margin) — delete outright, fully fixed
     by AC2 (widened window + the previously-missing test assertion now passes).
   - **Bullet 4** (`SET` vs `SET LOCAL`) — delete outright if AC4 ships a genuinely enforced rule (not
     merely a documentation change) as directed; otherwise annotate `[DECIDED: accepted risk —
     skillars-deferred-125]` if implementation finds the enforcement mechanism impractical (record
     which outcome actually happened and why).
3. Confirm the section header itself survives if any bullet remains under it (bullet 2, annotated not
   deleted) — do not delete the header.
4. Do **not** touch the `## Explicitly out of scope (skillars-deferred-123, 2026-09-18)` section's
   `main."user"` index bullet — the owner decision was to keep deferring it; leave it byte-for-byte
   as-is. Add a one-line `## Last audit` narrative note (matching this file's own established style)
   recording that this story re-confirmed it still open and still correctly out of scope, so a future
   audit does not have to re-derive that from scratch.
5. Add a `## Last audit: 2026-09-19 (skillars-deferred-125 dev-story completion)` narrative section, in
   this file's established style, summarizing what closed and what was accepted-and-documented instead,
   matching the level of detail the equivalent skillars-deferred-124 section (`deferred-work.md`,
   currently around line 2495) provides.
6. Grep-sweep every file this story touched (`VideoDeletionOutboxProcessor`, `RadarCompositeDlqProcessor`,
   `App.vue`, `sessionManager.js`, `MigrationLint`, `migration-conventions.md`) against the rest of the
   ledger for any other stale reference this story's changes might affect — re-confirm each hit is
   either unrelated or already correctly annotated, per this series' own standard practice.

---

## Dev Notes

**Cross-AC dependencies:** AC1 and AC2 both touch `RadarCompositeDlqProcessor` but are independent
fixes to independent problems (AC1's `try`/`catch` wraps the claim/fetch phase; AC2 changes
`STALE_CLAIM_WINDOW`'s value and Javadoc, and the test file's assertions) — sequence as separate
commits so either can be reverted without the other, mirroring this series' established convention.
AC3 is entirely independent (frontend, different module, different test suite) and can be implemented
in any order relative to AC1/AC2/AC4. AC4 is independent of everything else in this story (a
`MigrationLint`/doc-only change, no production migration added).

**No new Flyway migration in this story.** Confirm this remains true at implementation time — if any
AC's implementation turns out to need one, re-derive the next free version against the actual
`src/main/resources/db/migration/` directory at that time, not any number cited above.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for
this project. Run each AC's own targeted test class(es) locally during implementation as needed;
`mvn compile`/`mvn test-compile` are fine, `mvn verify` is not. For the frontend (AC3), run
`npm run test:unit` (or the project's equivalent Vitest invocation — confirm the actual script name in
`src/frontend/package.json` at implementation time) locally; this is not gated by the "no local verify"
convention, which is specific to the backend's Maven `verify` phase.

**Frontend test infrastructure gotcha (AC3):** no existing spec in `src/frontend/src/**/__tests__` (or
elsewhere in the frontend suite) mocks `vue-router`'s `useRouter()` or mounts `App.vue` directly — this
will be the first. Locate this project's actual conventions for mounting a component that pulls in
Pinia stores (`useAuthStore`/`usePlayerStore`) and router before writing new test scaffolding from
scratch; check whether a shared test-setup helper already exists elsewhere in the suite for stubbing
Quasar/Pinia in a component mount, since duplicating that setup inline would be wasteful if a
reusable helper already exists.

**CI context-count ceiling (backend ACs only):** `assert-context-count.sh`'s Spring-context ceiling is
currently 44 (raised by skillars-deferred-124 AC2, `pr-build.yml`'s call site) per that story's own
Dev Notes. AC1's new spy-based tests reuse the *same* test classes and mock shapes skillars-deferred-124
AC2 already introduced (`VideoDeletionOutboxProcessorIT`'s existing `@MockitoSpyBean
DrillVideoRefRepository`, `RadarCompositeDlqProcessorTest`'s plain-Mockito unit style) — this AC should
not need a new Spring context fork, but confirm against the CI failure message if it does, and bump the
ceiling with a dated justification comment mirroring the existing history in that script if so.

---

## File List (expected — reconcile against the actual final diff before ledger closeout)

**Production code:**
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC1, AC2)
- `src/frontend/src/App.vue` (AC3)
- `src/frontend/src/plugins/sessionManager.js` (AC3 — only if implementation finds a change needed
  here beyond `App.vue`; re-verify at implementation time whether the `App.vue`-only fix fully closes
  the gap, or whether `startSessionMonitoring`'s early-return path also needs a change)
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` (AC4)
- `docs/deployment/migration-conventions.md` (AC4)

**Tests:**
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java`
  (AC1, new isolation test)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
  (AC1 new isolation test; AC2 widened-window assertion + updated existing test)
- `src/frontend/src/__tests__/App.spec.js` or equivalent (AC3, new — confirm actual naming/location
  convention against an existing component spec before creating)
- Whichever test class covers `MigrationLint`'s existing rules today (AC4 — locate it first; likely
  `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java` or similarly named,
  confirm exact name before citing it in the dev-story implementation)

**Documentation / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes.md`
  (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-09-19: Story created via `/bmad-create-story`. Mined the freshest same-day code-review deferral
  (`code review of skillars-deferred-124`, 4 bullets) plus one older, still-genuinely-open item
  (`code review (round 2) of 1-7b-session-refresh-rint-contract-fix`'s router-abort re-arm gap),
  identified via a full-file re-audit of `deferred-work.md` (a dedicated cataloguing pass covering all
  ~92 section headers, cross-checked against actual HEAD source, not the ledger's own prose). Three
  owner decisions taken live with the user (`AskUserQuestion`) before drafting: (1) widen
  `RadarCompositeDlqProcessor.STALE_CLAIM_WINDOW` and add the missing test assertion, accepting the
  narrower per-row-overrun residual as documented risk, rather than leaving values as-is or engineering
  a full per-row timeout; (2) require `SET LOCAL lock_timeout` for all new migrations going forward via
  a doc update plus a new, properly-grandfathered `MigrationLint` rule, rather than accepting the
  current session-scoped convention as-is; (3) keep deferring the `main."user"` cleanup-sweep index —
  no production deploy has happened yet to generate the row-count/`EXPLAIN` evidence the ledger itself
  says is the actual blocker, so this story does not attempt a speculative shape. All line numbers
  verified against `master@77036728` (skillars-deferred-124's merge, PR #212) on 2026-09-19. 4 ACs
  bundled per this project's "do not create small stories" convention, plus the standard AC5 ledger
  closeout.
