# Story: Marketplace/Reviews Concurrency Audit (Fresh Sweep) & ConfigBounds Min/Max Divergence Fix

**Story Key:** `skillars-deferred-130-marketplace-reviews-concurrency-audit-and-config-bounds-fix`
**Epic:** Deferred Work
**Priority:** Medium (one genuine High-severity TOCTOU/lost-update hazard on a production write path,
one genuine Medium double-submit hardening gap, and one small config-correctness fix; plus closing
out the last two modules in this codebase's ~30-story concurrency-hardening series that had never
been swept).
**Status:** ready-for-dev
**Created:** 2026-09-23

---

## Provenance & Scoping (read before starting)

`_bmad-output/implementation-artifacts/deferred-work.md` was swept end-to-end (3173 lines) at story
creation time. Unlike the last several stories in this series (125–129), **the ledger is now
unusually thin** — stories 125–129 each closed their own same-day deferrals, and everything left is
either `[CLOSED by ...]`, `[DECIDED: accepted risk ...]`, or explicitly re-confirmed out of scope
across 2–4 consecutive prior stories. There is no cluster of 3–4 substantial still-open ledger bugs
left to mine the way 125–129 did.

Per an owner decision taken live at story-creation time (`AskUserQuestion`), this story instead
anchors on the **fresh concurrency/TOCTOU audit of `platform.marketplace` and `platform.reviews`**
that stories 126, 127, 128, and 129 each explicitly flagged as the only two modules never swept
under this series' `@Scheduled`/pessimistic-lock/TOCTOU lens (every other module — admin, booking,
config, development, filestorage, messaging, monitoring, notification, outbox, payment, security,
session, video — has at least one closed finding from this series already) — and which the owner
explicitly declined four times running in favor of ledger items that no longer exist in comparable
volume.

The audit (`/txn-and-concurrency-audit`, this story's own creation session) found **zero `@Scheduled`
methods in either module** (confirmed: `grep -rn "@Scheduled" ... platform/marketplace
platform/reviews` returns no hits), so checks #3/#4 (sibling `@SchedulerLock` consistency, lock
duration derivation) do not apply. Both modules already use the codebase's `findByIdForUpdate` +
`entityManager.refresh(..., PESSIMISTIC_WRITE)` pessimistic-lock convention extensively and
correctly in their hottest write paths — `ReviewSubmissionService.updateReview`,
`ReviewModerationService.handleReviewSubmitted`, `AdminReviewService.approveReview`/`blockReview`,
`CoachProfileService.saveStep4` all lock, refresh, and re-check status before mutating, each with
inline comments reasoning explicitly about the exact TOCTOU class this audit checks for. **Found: one
genuine High-severity inconsistency where a sibling write path to the identical field skips all of
that** (AC1 Fix 1), and one genuine Medium double-submit hardening gap (AC1 Fix 2). Checked 0
schedulers / ~20 `@Transactional` methods / 8 entities across both modules; no N+1, no
`MultipleBagFetchException` risk, no missed write-skew beyond the two findings below.

Two smaller, genuinely-open residuals from the immediately-preceding same-day
`deferred-work.md:3108-3172` section (`## Deferred from: code review of
skillars-deferred-129-...`, 2026-09-23, this story's own creation-session ledger sweep) were also
considered:

- **D1** (worst-case `N × seconds` erasure bound) — NOT reopened. This is the same-day recap of
  deferred-129's own owner-decided, honestly-documented non-ceiling; re-litigating it would
  contradict that decision.
- **D2** (`ConfigBounds` min/max re-declared as literals at `getBoundedLong` call sites, can diverge
  from the declared `BoundedKey` bounds) — **closed here, narrowly** (AC2 below). A second owner
  decision (`AskUserQuestion`, this story's own creation session) scoped this to
  `GdprErasureService`'s own call site only, not a codebase-wide `getBoundedLong` signature change
  touching every existing caller (including `RadarCompositeCalculationService`'s own) — the ledger's
  own text already calls the full refactor "its own story."
- **D3** (only 1 of 4 branch × reason catch combinations tested), **D4** (`performance_reports`
  hydrated to read one column, left managed post-delete), **D5** (`lock_timeout` bound re-read once
  per child inside the outer transaction) — NOT reopened. All three are explicitly low-severity,
  pre-existing, test-debt with no production defect claimed; none is individually story-worthy and
  none was selected by the owner for this bundle.

A third candidate — `deferred-work.md:3101-3106`, the GDPR erasure inner transaction's pooled
**connection-acquisition** wait (up to `connection-timeout: 30000`, 3× AC2's own `~10s`
`gdprEraseLockBudget`; `lock_timeout` has no effect on it) — was investigated as a possible fourth AC
and **explicitly declined as a code-change item** (owner decision, `AskUserQuestion`, this story's
own creation session), after discovering the "scoped connection-timeout override" originally proposed
is not the small fix it first appeared to be. See AC3 (ledger closeout) for the full technical
finding and why it remains `[DECIDED: accepted risk]` for a fourth consecutive story.

### Out of scope (explicitly, not re-decided here)

- The full `getBoundedLong` signature refactor (reading `min`/`max` off `BoundedKey` for every
  existing call site, including `RadarCompositeCalculationService`'s own) — considered and declined
  in favor of AC2's narrow, single-call-site fix (see above).
- A second dedicated HikariCP connection pool to bound `deletePlayerDevelopmentData`'s
  connection-acquisition wait — considered and declined (see AC3's Context for the full reasoning:
  no clean per-call-site override exists through the standard `DataSource.getConnection()` path
  Spring's transaction manager uses, and a dedicated pool would need separate wiring for the
  Testcontainers `@ServiceConnection` test path to even be exercisable by `GdprErasureIT`, per this
  project's own IT-only validation convention).
- `deferred-work.md:3146-3172` (D3, D4, D5) — see above, not reopened.
- `main."user"` cleanup-sweep missing index — still blocked on production `EXPLAIN`/row-count
  evidence this project has no deploy history to generate; re-confirmed correctly out of scope for
  a fourth time by this story's own ledger sweep, not reopened.
- `radar_composite_dlq` post-erasure residual, `markFailed`'s generic no-`AdminAlert` gap — both
  already `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.
- `EmailRetryScheduler.retryFailedEmails()` whole-table-poll headline claim
  (`deferred-work.md:2214-2217`) — genuinely open but is the deliberately-left-open half of a bullet
  deferred-129 AC3 already partially closed; re-touching it now would reopen what that story
  deliberately left as a documented residual, not a fresh find from this story's own audit.

All citations below were independently re-verified against `master@1746965d` (this story's own
creation-time `HEAD`, the skillars-deferred-129 merge commit) via direct `Read`/`Grep` against the
actual source files, not assumed from any prior story's own citations. **Re-verify again at actual
implementation time** if this worktree's `HEAD` has moved.

---

## AC1 — Fix two concurrency/data-integrity gaps found by the first-ever `platform.marketplace`/`platform.reviews` audit

### Context

#### Fix 1 (High) — `ReviewFlagService.flag`'s auto-hold write has no lock, refresh, or re-check

`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java:37-93`. The
method reads the target review via a plain, unlocked `reviewRepository.findById(reviewId)` (`:38`),
does its authorization/dedup checks, persists the new `ReviewFlag` row (`:61-72`, correctly guarded
by a caught `DataIntegrityViolationException` against the `review_flags_unique_flagger` unique
index — that part is fine), and then, if the open-flag count has just crossed the configured
threshold, **conditionally mutates the SAME unlocked, possibly-stale `review` instance's
`moderationStatus`** and saves it (`:79-86`):

```java
boolean autoHeld = false;
if (openFlagCount >= threshold && review.getModerationStatus() == ReviewModerationStatus.APPROVED) {
    review.setModerationStatus(ReviewModerationStatus.UNDER_REVIEW);
    review.setHeldReason(HeldReason.FLAG_THRESHOLD);
    review.setLastModifiedAt(Instant.now());
    reviewRepository.save(review);
    coachRatingService.recompute(review.getCoachId());
    autoHeld = true;
}
```

**Every other writer of `CoachReview.moderationStatus` in this codebase goes to real, deliberately-
commented lengths to avoid exactly this class of bug:**

- `ReviewSubmissionService.updateReview` (`:106-126`) — takes `findByIdForUpdate`, then
  `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)`, then **re-runs the moderation-
  status guard on the fresh locked instance**, with an inline comment explicitly warning that
  skipping this "would silently reset [a concurrent decision] to PENDING."
- `ReviewModerationService.handleReviewSubmitted` (`:93-140`, an `AFTER_COMMIT` listener in its own
  `REQUIRES_NEW` transaction) — takes `findByIdForUpdate` as the transaction's first read (so no
  `refresh` is even needed), and its own comment states: "this verdict must lose to any decision
  already recorded against the row... an unlocked read plus an unconditional write would silently
  revert it."
- `AdminReviewService.approveReview`/`blockReview` (`:73-143`) — both take `findByIdForUpdate` as
  their first read, with an inline comment: "a plain check-then-act loses the admin-double-click
  race."

**Concrete failure scenario:** a review is `APPROVED`. Two users have already flagged it (below the
configured threshold, default 3). A third flag arrives — `openFlagCount` now hits the threshold.
Concurrently, an admin calls `AdminReviewService.blockReview` for the same review (e.g., responding
to the same flags via the admin queue) — it takes the row lock, sets `BLOCKED`, resolves all open
flags, and commits. `ReviewFlagService.flag`'s own transaction, still holding its stale `review`
instance read from before the admin's commit, then reaches its threshold check, sees the **stale**
`APPROVED` value in memory (Hibernate does not silently re-read a managed entity), and issues an
unconditional `UPDATE ... SET moderation_status = 'UNDER_REVIEW' WHERE review_id = ?` — **silently
reverting the admin's `BLOCKED` decision back to `UNDER_REVIEW`**, with no error, no alert, and no
signal to the admin that their decision was just undone. `coachRatingService.recompute` then also
runs against a review that should no longer be counted, transiently miscomputing the coach's public
rating until the next recompute trigger.

This is not hypothetical timing-sensitive-only risk requiring a rare race window narrower than what
this project's own `RadarCompositeCalculationService`/`GdprErasureService` findings (stories 115,
121, 124, 126–129) have repeatedly found and fixed in the identical shape elsewhere — the exact
comments quoted above, in the exact same file's sibling methods, describe this exact scenario as the
reason those methods lock.

**Fix (owner decision, this story's own creation session, `AskUserQuestion`):** match the established
sibling pattern exactly — `findByIdForUpdate` + `entityManager.refresh(..., PESSIMISTIC_WRITE)` +
re-check `moderationStatus == APPROVED` on the fresh locked instance, immediately before the
conditional write. (The alternative considered — a conditional `@Modifying UPDATE ... WHERE
moderation_status = 'APPROVED'` checking the affected-row count, avoiding a lock entirely — was
declined in favor of consistency with the other three call sites in this exact file/module, all of
which use the lock-and-recheck shape.)

#### Fix 2 (Medium) — `CoachProfileService.publishProfile` double-submit surfaces as an unhandled 500, not a clean error

`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:299-318`.
Two concurrent `publishProfile(userId)` calls (e.g., a genuine accidental double-click, or a client
retry after a slow/timed-out first response) can both pass the unlocked `profile.getStatus() !=
CoachProfileStatus.DRAFT` guard (`:303-305`) before either has committed. Both then proceed to
`profile.setStatus(ACTIVE); coachProfileRepository.save(profile);` and construct+save a new
`CoachSubscription` row (`:309-315`). `marketplace.coach_subscriptions.coach_id` is the table's own
primary key (`V138__baseline_schema.sql:2852-2853`, confirmed), so the second transaction's
subscription insert throws a primary-key-violation `DataIntegrityViolationException` at flush/commit
time, rolling back that entire transaction — including its own `ACTIVE` status write. **Not a data-
integrity bug** (the PK constraint prevents two subscription rows and the losing transaction's status
flip is correctly undone with it) **but the loser sees an unhandled 500**, not the clean
`marketplace.alreadyPublished` error `publishProfile`'s own first line already throws for the
non-concurrent case (`:304`). `ReviewSubmissionService.submitReview` (`:66-74`) already establishes
the correct pattern for this exact class of race in a sibling module: catch the constraint violation
and translate it to the same business error the pre-check throws.

**Fix (owner decision, this story's own creation session):** wrap the `CoachSubscription` insert in a
`try`/`catch (DataIntegrityViolationException)`, using `saveAndFlush` (not a plain `save`, so the
constraint violation surfaces synchronously inside this method rather than at an unpredictable later
flush point — matching `ReviewFlagService.flag`'s own `saveAndFlush` precedent for the identical
"must catch this specific write's violation, not a later one's" reasoning, rather than
`ReviewSubmissionService.submitReview`'s plain `save()`, which relies on that call being the
transaction's last write), and rethrow `MarketplaceException("marketplace.alreadyPublished", "Profile
is already published")` — the identical error the non-concurrent pre-check already throws, so a
double-submit is indistinguishable to the caller from an already-published profile.

### Tasks

1. [ ] Re-verify all line citations above against current `HEAD` before implementing.
2. [ ] `ReviewFlagService.flag` (`:37-93`): after the existing `openFlagCount`/`threshold`
   computation (`:74-76`), replace the unlocked conditional block (`:78-86`) with: `findByIdForUpdate`
   on `reviewId`, `entityManager.refresh(review, LockModeType.PESSIMISTIC_WRITE)` (inject
   `EntityManager` — not currently a field on this class, unlike `ReviewSubmissionService`), then
   re-check `review.getModerationStatus() == ReviewModerationStatus.APPROVED` on the fresh instance
   before writing `UNDER_REVIEW`/`HeldReason.FLAG_THRESHOLD`/`lastModifiedAt` and calling
   `coachRatingService.recompute`. If the fresh re-check fails (status is no longer `APPROVED` —
   e.g., an admin already resolved it), skip the auto-hold write entirely (do not throw — the flag
   itself was already persisted and committed at `:67`; only the auto-hold escalation is skipped).
   Add a code comment mirroring the sibling methods' own reasoning (cross-reference
   `ReviewSubmissionService.updateReview`/`AdminReviewService.approveReview` by name, matching this
   file's own existing citation convention elsewhere in the module).
3. [ ] `CoachProfileService.publishProfile` (`:299-318`): wrap the `CoachSubscription` construction +
   `coachSubscriptionRepository.save(subscription)` in a `try { coachSubscriptionRepository
   .saveAndFlush(subscription); } catch (DataIntegrityViolationException e) { throw new
   MarketplaceException("marketplace.alreadyPublished", "Profile is already published"); }`. Import
   `org.springframework.dao.DataIntegrityViolationException` (already imported elsewhere in this
   module, e.g. `ReviewFlagService`/`ReviewSubmissionService` — confirm the exact import path matches).
4. [ ] Confirm `ReviewFlagService` does not already have an `EntityManager` field before adding one —
   `grep -n "EntityManager" src/main/java/.../reviews/service/ReviewFlagService.java` should return
   no hits currently; add it as a new constructor-injected field (the class uses Lombok
   `@RequiredArgsConstructor`, same shape as `ReviewSubmissionService`).
5. [ ] Confirm no other caller of `ReviewFlagService.flag` or `CoachProfileService.publishProfile`
   depends on the exact prior (buggy) behavior — `grep -rn "\.flag(\|publishProfile(" src/main/java`
   and check each call site.

### Tests

- A `ReviewFlagIT` (or `ReviewFlagServiceTest`, whichever seam this project's own convention for this
  service favors — check for an existing unit-test class first) concurrency test mirroring
  `RadarCompositeCalculationServiceConcurrencyIT`/`GdprErasureIT`'s own real-Testcontainers-Postgres,
  real-threads-and-latches pattern: seed a review at the auto-hold flag threshold minus one, hold a
  `blockReview`-style write in flight (or directly seed a `BLOCKED` state via a raced concurrent
  transaction) between the flagging request's read and its threshold-crossing write, and assert the
  final `moderation_status` is the admin's `BLOCKED` decision, not a reverted `UNDER_REVIEW` — the
  exact scenario this AC's Fix 1 closes. A second, simpler test should confirm the ordinary
  non-concurrent auto-hold path (no admin race) still transitions `APPROVED` → `UNDER_REVIEW` exactly
  as before — a pure regression check with unchanged externally-observable behavior in the
  non-contended case.
- A `CoachProfileBuilderIT` (or a new focused test) proving Fix 2: two concurrent `publishProfile`
  calls for the same profile result in exactly one `ACTIVE` status, exactly one
  `marketplace.coach_subscriptions` row, and the losing caller receiving a clean
  `marketplace.alreadyPublished` `MarketplaceException`/4xx response, not an unhandled 500. A second
  test should confirm the existing non-concurrent already-published pre-check (`:303-305`) is
  unaffected.

---

## AC2 — Close `ConfigBounds`/`GdprErasureService` min-max divergence at its one concrete call site

### Context

`deferred-work.md:3135-3144` (D2, from skillars-deferred-129's own same-day code review, 2026-09-23):
`GdprErasureService.java:630-631` calls
`configService.getBoundedLong(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L,
120L)`, **re-typing the `2L`/`120L` bounds as literals** instead of reading them from the
`ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` `BoundedKey` (`ConfigBounds.java:280-286`,
confirmed) that already declares them. `ConfigBounds.BoundedKey` is a Java `record(String key, long
min, long max, boolean failFast, String note)` (`ConfigBounds.java:60`) — its `min()`/`max()` accessor
methods are already available for free; nothing needs to be added to the record itself. An operator
who later raises `GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS`'s declared `max` in `ConfigBounds.java`
(the value shown in any future admin-facing bounds documentation) would get a value the
`ConfigService.updateConfig` write path accepts and stores (it validates against the same
`ConfigBounds` registry) but this one read call site would silently clamp back down to the stale
literal `120L` — a correctness trap with no test or compiler signal today.

**Scope (owner decision, this story's own creation session, `AskUserQuestion`): narrow to this one
call site only.** The ledger's own text already frames the fully-correct fix — `getBoundedLong`
reading `min`/`max` from the `BoundedKey` itself for every caller — as "its own story," since it
touches every existing `ConfigBounds` call site codebase-wide, including
`RadarCompositeCalculationService.java:212`'s own identical-shaped literal re-typing (that call site,
and every other pre-existing `getBoundedLong`/`getBoundedInt` call site, is explicitly listed as out
of `ConfigStartupAssertion`'s own scope already, per `ConfigBounds.java:12-18`'s own class-level
Javadoc — this AC does not touch or attempt to close that wider gap). This AC closes only the one
concrete divergence risk the ledger bullet names for `GdprErasureService`'s own new key, added by
deferred-129 in the first place.

### Tasks

1. [ ] Re-verify `GdprErasureService.java:630-631` and `ConfigBounds.java:280-286` against current
   `HEAD` before implementing.
2. [ ] Replace the literal `2L, 120L` in `GdprErasureService.java:631`'s `getBoundedLong(...)` call
   with `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.min()`,
   `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.max()`. Leave the `5L` default-value
   literal as-is (matching `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own established call-site
   convention of keeping the default a literal — `ConfigBounds` has no code-default field on
   `BoundedKey`, a separate, pre-existing, out-of-scope gap already recorded elsewhere in the ledger
   as D2's own sibling note, not reopened here).
3. [ ] Grep for any other `getBoundedLong`/`getBoundedInt` call site that already derives its bounds
   from a `ConfigBounds.BoundedKey` reference (rather than literals) to confirm the exact syntax
   precedent, if one already exists; if none does, this is the first, and should be written plainly
   (`KEY.min()`, `KEY.max()`) with no additional abstraction.
4. [ ] Do not touch any other `getBoundedLong`/`getBoundedInt` call site — this AC is scoped to this
   one line by explicit owner decision.

### Tests

- No behavior change for any in-range value (`2`–`120` before and after). Confirm existing
  `GdprErasureIT` tests that seed `gdpr_erase_statement_lock_timeout_seconds` at the boundary values
  (`2`, matching `RadarCompositeCalculationServiceConcurrencyIT`'s own precedent per
  skillars-deferred-129's own test seam) still pass unchanged — this is a refactor of where the bound
  literals come from, not a behavior change, so no new test is strictly required beyond confirming the
  existing suite stays green.

---

## AC3 — Standard `deferred-work.md` ledger closeout

### Tasks

1. [ ] Close out the "platform.marketplace/platform.reviews never audited" narrative that stories
   126, 127, 128, and 129 each recorded (search for each story's own "Out of scope" mention of these
   two modules, e.g. deferred-129's own Provenance section) — this story is that audit. Record the
   findings summary (2 findings: 1 High closed by AC1 Fix 1, 1 Medium closed by AC1 Fix 2) and that
   both modules are now confirmed swept under this series' concurrency lens, alongside every other
   module.
2. [ ] `deferred-work.md:3108-3172` (the fresh same-day skillars-deferred-129 code-review section) —
   D2 (`:3135-3144`) closed by AC2 above (narrowly — note the codebase-wide `getBoundedLong` gap
   remains, per AC2's own Context). D1, D3, D4, D5 remain untouched, all already correctly
   `[Review][Defer]`-annotated by deferred-129's own review response — re-confirm, do not reword.
3. [ ] `deferred-work.md:3101-3106` (skillars-deferred-128's own review section, Hazard 2 — the
   pooled connection-acquisition wait) — **re-confirm `[DECIDED: accepted risk — skillars-deferred-128]`
   for a fourth consecutive story**, and extend the bullet's own "revisit if" condition with this
   story's own concrete finding: a real fix is not a config tweak — HikariCP has no per-call-site
   connection-acquisition-timeout override through the standard `DataSource.getConnection()` path
   Spring's transaction manager uses (`connection-timeout` is pool-wide only); the only real fix shape
   is a second, dedicated `HikariDataSource` scoped to `deletePlayerDevelopmentData`'s own
   `REQUIRES_NEW` transaction, which would also need separate wiring for the Testcontainers
   `@ServiceConnection` test path (`DataSourceConfig`'s custom `HikariConfig` bean is entirely skipped
   there, per `datasource.container=true`) to be exercisable by `GdprErasureIT` at all, per this
   project's own IT-only validation convention. Declined as a code-change item this story (owner
   decision, `AskUserQuestion`, this story's own creation session) — record this reasoning in the
   bullet itself so a future story does not have to re-derive it from scratch.
4. [ ] Grep-sweep `deferred-work.md` for any other reference to the items above that a targeted
   reword might miss (narrative mentions inside a `## Last audit:` summary section, etc.) — correct
   or annotate any such mention, following this file's own established "narrative sections are
   corrected, not deleted" convention.
5. [ ] Add a new `## Last audit: <implementation date> (skillars-deferred-130 dev-story completion)`
   heading, placed immediately above the freshest section it touches (`:3108`, the skillars-
   deferred-129 same-day section), summarizing the marketplace/reviews audit outcome and the AC2/AC3
   ledger edits in its own body text — mirroring deferred-129's own multi-section, non-adjacent
   cross-referencing precedent.
6. [ ] Re-confirm this story's own "Out of scope" section above remains correctly untouched.

### Tests

- None expected — this AC is a documentation-only ledger edit.

---

## Dev Notes

- This is the first story in this series (100–129) to anchor on a **fresh audit** rather than mining
  an existing ledger deferral — the ledger itself supplied only the two small AC2/AC3-adjacent
  residuals, not the primary scope. Treat AC1's two findings as the story's real substance.
- AC1 Fix 1 touches `ReviewFlagService.java` for the first time in this series' history — read the
  whole file (93 lines) before editing; it is small. AC1 Fix 2 touches `CoachProfileService.java`
  (515 lines) — read the whole `publishProfile` method and its immediate callers
  (`ProfileBuilderResource`, presumably) before editing, per this project's "read files being
  modified" convention.
- AC2 touches `GdprErasureService.java` and `ConfigBounds.java`, both already touched by
  skillars-deferred-127/-128/-129 — read each file's own recent history/Javadoc in full before
  editing, per this project's established convention; do not assume the citations above are still
  accurate without re-checking `HEAD` first.
- No frontend Vue/JS source is touched by this story — no `frontend-tests` PR label needed (confirm
  via `git status --short` before opening the PR, per this project's own established practice).
- No local `mvn verify` — GitHub CI is the sole full-verification gate, per
  `docs/validation-strategy.md`.

### References

- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java` — AC1 Fix 1
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java` — AC1 Fix 1 (locked-write pattern precedent)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java` — AC1 Fix 1 (locked-write pattern precedent)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java` — AC1 Fix 1 (locked-write pattern precedent; also the concurrent actor in the failure scenario)
- `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java` — AC1 Fix 1 (`findByIdForUpdate`)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` — AC1 Fix 2
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSubscriptionRepository.java` — AC1 Fix 2
- `src/main/resources/db/migration/V138__baseline_schema.sql` — AC1 Fix 2 (`coach_subscriptions_pkey` citation)
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` — AC2
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` — AC2
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` — AC3 (Hazard 2 finding, not fixed)
- `src/main/resources/application.yaml` — AC3 (`connection-timeout: 30000` citation)
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC3
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java` — AC1 Fix 1 test seam
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java` — AC1 Fix 2 test seam

## Change Log

- 2026-09-23: Story created via a fresh `/txn-and-concurrency-audit` of `platform.marketplace`/
  `platform.reviews` (the two modules never swept in this series' history), rather than the manual
  ledger-mining process this series otherwise uses — the ledger itself was too thin to mine a
  comparable bundle this time (re-swept end-to-end at creation time; confirmed). Owner decisions
  taken live (`AskUserQuestion`, this story's own creation session): (1) anchor on the fresh audit
  over declining it a fifth time; (2) fix `ReviewFlagService.flag`'s TOCTOU via the lock-and-recheck
  pattern, matching this file's own three sibling call sites exactly, over a conditional-`UPDATE`
  alternative; (3) scope the `ConfigBounds` min/max divergence fix narrowly to `GdprErasureService`'s
  own call site, not a codebase-wide `getBoundedLong` signature refactor; (4) after discovering the
  GDPR connection-acquisition-wait item has no clean scoped-config fix (HikariCP has no per-call-site
  override; a real fix needs a second dedicated connection pool with separate Testcontainers wiring),
  decline it as a code-change item this story and instead extend its ledger documentation with that
  finding.
