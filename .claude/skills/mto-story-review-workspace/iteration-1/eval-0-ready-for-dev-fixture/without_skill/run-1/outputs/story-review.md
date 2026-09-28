# Story Review: skillars-deferred-137-gdpr-erase-ceiling-payment-pool-fix

**Reviewer:** senior-dev audit (manual, no review skill/workflow invoked)
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-137-gdpr-erase-ceiling-payment-pool-fix.md`
**Story status as reviewed:** `ready-for-dev`
**Repo state reviewed:** worktree HEAD `0d5897a8` ("skillars-deferred-137: GDPR erasure lock-wait
ceiling + payment REQUIRES_NEW pool isolation (#233)"), plus an uncommitted working-tree diff that
touches only the story `.md` file and one `sprint-status.yaml` line.
**Method:** every file:line citation, ledger reference, and technical claim in the story was re-opened
and checked directly against the real source in this worktree (`git log`, `git show --stat`, `grep -n`,
and full file reads) — nothing below is taken on the story's own word.

---

## Verdict

**Do not start `/bmad-dev-story` on this story as written.** Both AC1 and AC2 are **already fully
implemented, tested, and ledger-closed in the current codebase** — the story's own "Current state
(re-verified against `HEAD`)" sections describe a pre-fix world that no longer exists. This is not a
matter of interpretation; it is directly checkable and confirmed below (Finding 1). On top of that, one
piece of technical guidance in AC1's "The fix" section is factually wrong about the precedent it tells
the implementer to copy (Finding 2), and two ledger citations point at the wrong location/date
(Findings 3–4).

---

## Finding 1 (Critical) — The work this story describes is already shipped

**Claim in story:** Status `ready-for-dev`; AC1's "Current state" section describes
`GdprErasureService.deletePlayerDevelopmentDataInDedicatedPool` as having no cumulative ceiling ("Real
worst case: `(12 + M) ×` the configured seconds — not a method-level ceiling"); AC2's "Current state"
describes `BookingPaymentPersistenceService`'s three `REQUIRES_NEW` methods as having "No pool-sizing
analysis has ever been written." Both sections are prefixed "re-verified against `HEAD`, exact current
line numbers."

**What re-verification against the actual `HEAD` found:**

- `git log --oneline -1` on this worktree is `0d5897a8 skillars-deferred-137: GDPR erasure lock-wait
  ceiling + payment REQUIRES_NEW pool isolation (#233)`. `git show --stat 0d5897a8` shows this commit
  already modified exactly the files AC1/AC2 target: `GdprErasureService.java` (+250/-…),
  `BookingPaymentPersistenceService.java` (+70/-…), `DataSourceConfig.java` (+67/-…),
  `TestConfig.java` (+35/-…), plus new/extended `GdprErasureIT`, `GdprErasureServiceTest`, a new
  `BookingPaymentDataSourceRoutingIT`, and `deferred-work.md`/`sprint-status.yaml` ledger updates.
- `grep -n "cumulativeLockWaitBudget\|CumulativeLockBudgetExhaustedException\|spendDownLockBudget"
  src/main/java/.../GdprErasureService.java` returns 15+ hits — the field (line 171), the exception
  class (line 1302), the `spendDownLockBudget` method (line 1275), the four-group batching
  (`deletePlayerDevelopmentDataInDedicatedPool`, lines ~1130–1200) — all present and wired in.
- The method's own Javadoc, at the exact lines the story cites (`:1040-1112`), reads: *"before
  **skillars-deferred-137 AC1** this method's real worst case was `(12 + M) ×` the configured
  per-statement seconds … **skillars-deferred-137 AC1 closes that gap:** the 12+M statements are
  batched into four logical groups …"* — i.e. the cited lines describe the fix as already done, in
  the past tense, naming this exact story.
- `BookingPaymentPersistenceService.java` already has the self-invocation split AC2 asks for:
  `reserveCapture`/`reserveCaptureTransactional`, `persistPaymentFailure`/
  `persistPaymentFailureTransactional`, `declineBatchBooking`/`declineBatchBookingTransactional`, each
  setting `RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY)` before
  calling `self.*Transactional(...)`. `reserveCapture`'s own Javadoc explicitly says: *"skillars-deferred-137
  AC2: thin, non-transactional wrapper around `reserveCaptureTransactional` … routing this method's
  `REQUIRES_NEW` acquisition to the dedicated payment pool instead of the primary one."*
- `DataSourceConfig.java` already declares `PAYMENT_REQUIRES_NEW_DATASOURCE_KEY = "payment-requires-new"`
  and a `paymentRequiresNewHikariConfig` bean (`maximumPoolSize=10`, `minimumIdle=1`,
  `connectionTimeout=5_000`), added as a second entry to the existing `RoutingDataSource`'s
  `namedTargets` map alongside `GDPR_ERASURE_DATASOURCE_KEY` — exactly the "reuse, don't build a second
  mechanism" shape AC2 asks for.
- `src/test/java/.../config/TestConfig.java` already has the mirrored test-side
  `paymentRequiresNewHikariConfig` bean and a `Map.of(...)` with both named-target keys.
- `src/test/java/.../BookingPaymentDataSourceRoutingIT.java` already exists (new file, per `git show
  --stat`). `GdprErasureServiceTest` currently has 14 `@Test` methods, 9 of which directly reference
  `CumulativeLockBudgetExhausted`/`cumulativeLockWaitBudget`/`spendDownLockBudget`.
- `deferred-work.md`'s own D1 bullet ("GDPR erasure has no method-level wait ceiling…", line 2963) is
  already annotated `**[CLOSED by skillars-deferred-137 AC1 — ported `RadarCompositeCalculationService`'s
  own cumulative spend-down mechanism …]**` (line 2982), with the same closeout detail (four groups,
  `cumulativeLockWaitBudget`, `CumulativeLockBudgetExhaustedException`) the story asks Task 4 to add.
  Both `BookingPaymentPersistenceService` pool-pressure bullets (2026-08-11 D14, 2026-08-24) are
  likewise already annotated `[CLOSED by skillars-deferred-137 AC2 — …]`.
- `sprint-status.yaml`'s committed entry for this story key (before the uncommitted diff) reads `done`
  with a full completion note describing both ACs as implemented, tested, and reviewed (two review
  passes, findings triaged, all green).

**Root cause, as far as it's checkable from this worktree:** the only uncommitted change in this
worktree is to the story `.md` (status `done → ready-for-dev`, Dev Agent Record / Completion Status /
second Review Findings section stripped out, tasks unchecked) and one `sprint-status.yaml` line (same
status flip). None of the actual `src/main`/`src/test` files were touched by that uncommitted diff —
they are exactly what shipped in `0d5897a8`. So the artifact under review was reset to look
pre-implementation while the codebase it describes was not.

**Impact if not caught:** a developer picking this up under `/bmad-dev-story` would either burn a
session confused that the "gap" AC1/AC2 describe isn't reproducible, or — worse — not notice, and
re-implement a second, competing mechanism (a second `cumulativeLockWaitBudget`-shaped field, a second
self-invocation split, a duplicate `RoutingDataSource` named-target key) alongside the one already
shipped, which would not compile cleanly or would silently shadow the real fix.

---

## Finding 2 (High) — AC1's "mirror the precedent" guidance is factually wrong about the precedent

**Claim in story (AC1, "The fix" section):** *"Radar's own spend-down bookkeeping is
`System.nanoTime()`-based monotonic elapsed-time tracking (see the field declarations immediately above
the loop), which is why it is immune to NTP clock steps — mirror that same monotonic-clock choice here
rather than `Instant.now()`, since this method already holds a Postgres advisory-adjacent lock for the
duration and a backward clock step must not be allowed to inflate the remaining budget."*

**Verification:** read `RadarCompositeCalculationService.java` in full (the cited `:260-324` region and
the field declarations above it). `grep -n "nanoTime" RadarCompositeCalculationService.java` returns
**zero matches** anywhere in the file. The actual mechanism, at line 291 and line 321:

```java
Instant skillStartedAt = Instant.now();
...
Duration elapsed = Duration.between(skillStartedAt, Instant.now());
remainingLockBudget = elapsed.compareTo(remainingLockBudget) >= 0
    ? Duration.ZERO
    : remainingLockBudget.minus(elapsed);
```

This is wall-clock `Instant.now()`/`Duration.between`, not `System.nanoTime()` — the story's citation is
fabricated, not merely stale (there is nothing in this file resembling a nanoTime-based field). The
"field declarations immediately above the loop" the story points to are `WEIGHT_OBJECTIVE`/
`WEIGHT_MATCH_OBS`/`WEIGHT_COACH_EVAL` (`BigDecimal`s) and `CUMULATIVE_LOCK_WAIT_BUDGET` (a `Duration`
constant) — none of them a clock field.

The claim that this makes Radar "immune to NTP clock steps" is also verifiably false, and doubly so:
the code above has **no guard against a negative `elapsed`** — if the wall clock steps backward between
`skillStartedAt` and the second `Instant.now()` call, `elapsed` is negative, `elapsed.compareTo(remainingLockBudget)`
is `< 0`, and `remainingLockBudget.minus(elapsed)` **inflates** the budget (subtracting a negative). This
is precisely the "Clock regression" defect already found and fixed in `GdprErasureService`'s own ported
copy (`spendDownLockBudget` floors elapsed at `Duration.ZERO`, per the already-shipped
`GdprErasureService.java` and its `spendDownLockBudget_negativeElapsedFromBackwardClockStep_…` test) —
and Radar's own identical, still-unguarded copy of the same bug is recorded in the ledger/sprint-status
notes as a **known, deliberately-unfixed** follow-up, not something "immune" by design.

**Impact:** this guidance, read at face value, would send an implementer toward `System.nanoTime()` —
which cannot be compared against a `Duration` budget the way this code needs (no wall-clock semantics,
not directly convertible to the `set_config` timeout arithmetic) — for a false safety property, while
simultaneously misrepresenting the actual precedent's real (and already-diagnosed) weakness as
non-existent. Anyone reviewing an eventual implementation against "did you mirror the precedent
correctly?" would be checking against a precedent that doesn't exist in the code.

---

## Finding 3 (Medium) — Wrong `deferred-work.md` line citation for the D1 bullet

**Claim in story (Context + Task 4):** the `skillars-deferred-129` D1 bullet ("aggregate lock-wait has
no method-level ceiling") lives at `deferred-work.md:3259-3277`.

**Verification:** `grep -n` for the bullet's own heading text ("D1 — GDPR erasure has no method-level
wait ceiling") locates it at **line 2963** (section header "Deferred from: code review of
skillars-deferred-129-gdpr-lock-timeout-ci-frontend-auto-detect-and-envelope-test-fixes (2026-09-23)"),
with its `[CLOSED by skillars-deferred-137 AC1 …]` annotation starting at line 2982. Lines 3259-3277
contain unrelated content from a different section entirely — the AC5 ledger-closeout paragraph of a
different (skillars-deferred-133-era) story, discussing "Fix 1's lock-order inversion," the `BoundedKey`
missing-`default`-field residual, and the Stripe→payment reconciliation sweep. There is no overlap
between that text and the D1 bullet the story means to cite.

---

## Finding 4 (Medium) — Wrong date in AC2's ledger citation ("2026-06-25")

**Claim in story (AC2 "Current state" and Dev Notes):** the payment-pool pressure gap was "Flagged twice
in old reviews (2026-06-25 `D14`, 2026-08-24) and never fixed or dismissed."

**Verification:** there is a `## Deferred from: code review of skillars-7-3-…` section genuinely dated
`2026-06-25` (line 972), but its contents are unrelated (`CoachSearchService.buildSort`,
`ReliabilityStrikeService`, `CoachCancellationHistory`) — nothing about `BookingPaymentPersistenceService`
or connection-pool sizing. The actual matching bullets, located by searching for
`BookingPaymentPersistenceService` pool-sizing text, are:
- line ~1018/1028, dated **2026-08-11** (`## Deferred from: code review of
  skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)`), including the one
  explicitly labeled **D14**;
- line ~1157, dated **2026-08-24** (`## Deferred from: code review of skillars-deferred-58-…
  (2026-08-24)`).

So "2026-08-24" in the story is correct, but "2026-06-25" should be "2026-08-11" (and there are two
bullets on that date, not one). Note this same wrong date has also been carried, uncorrected, into the
already-shipped `DataSourceConfig.paymentRequiresNewHikariConfig` Javadoc comment in the current
codebase ("flagged twice, 2026-06-25 and 2026-08-24, never fixed") — so this specific date error is not
unique to the story; it appears to predate it. The story's own "re-verified against `HEAD`" framing
implies this was independently checked, and the checkable ledger text does not support the date it
gives.

---

## Finding 5 (Low) — "Exact current line numbers" claim is stale by construction

Given Finding 1, every specific `:NNN-NNN` citation in AC1's and AC2's "Files" sections no longer points
at what the story says it does, simply because the files already contain the AC1/AC2 implementation and
have grown:
- `BookingPaymentPersistenceService.java` is now 400 lines; `reserveCapture` (with its
  `@Transactional(REQUIRES_NEW)` annotation) is at line 125, not `:91-92`; `persistPaymentFailure`'s
  annotation is at line 297, not `:246-247`; `declineBatchBooking`'s is at line 390, not `:326-327`.
- `GdprErasureService.java` is now 1336 lines; the Javadoc/method the story cites at `:980-1024`/
  `:1040-1112` is present but already describes the fix as shipped (see Finding 1), not the pre-fix
  behavior the "Current state" prose around it claims.

This is a direct, mechanical symptom of Finding 1 rather than an independent defect — flagged
separately only because the story explicitly asserts these are "exact current line numbers" checked
against `HEAD`, which they are not.

---

## Checked and found accurate (no false positives claimed)

To keep this review honest about what *isn't* wrong:

- `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` and `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`
  both exist as described (bounded keys, `[2, 120]`-shaped).
- `RadarCompositeCalculationService.recalculateComposite`'s cumulative spend-down loop genuinely exists
  at (approximately) the cited `:260-324` region, with the per-skill `/2` halving, the `< 2L` floor
  check throwing `IllegalStateException`, and DLQ-retry re-drivability — the story's *structural*
  description of the mechanism to port is accurate; only the clock-source claim (Finding 2) is wrong.
- The "Considered and explicitly excluded" items were spot-checked and hold up: `AdminVideoService
  .deleteVideo`'s quota release is genuinely Phase 2, outside the `TransactionTemplate` (confirmed by
  reading the method); the `ses-1-4` and `deploy-1-5`/`deploy-1-3` ledger citations land within a few
  lines of the right sections (minor drift, not a substantive error, not flagged as a separate finding).
- AC2's underlying risk characterization (three `REQUIRES_NEW` methods on live request/batch-listener
  traffic, not admin-only) is an accurate description of the class, independent of the "is it already
  fixed" question in Finding 1.
- The story's stated precedent that `RoutingDataSource`/`RoutingDataSourceContext` were built
  "business-agnostic, reusable by any future module needing a second dedicated pool" in
  skillars-deferred-136 is accurate — confirmed by that exact phrase already appearing in
  `DataSourceConfig.java`'s own Javadoc for `gdprErasureHikariConfig`/`paymentRequiresNewHikariConfig`.

---

## Recommendation

1. Before any dev work starts, resolve the status/reality mismatch: either this story should be marked
   `done` (it already is, in every way that's checkable in this worktree) or, if there is a genuine
   reason to redo/re-scope this work, the story needs to be rewritten from scratch against what actually
   exists today, not silently rerun against a "Current state" section describing code that was true
   roughly one story-cycle ago.
2. If a future story does port Radar's cumulative-budget pattern elsewhere, fix the clock-source
   citation before reusing this story's text as a template — direct a reader to `Instant.now()`/
   `Duration.between`, not `System.nanoTime()`, and be explicit that Radar's own copy has a known,
   currently-unfixed negative-elapsed gap rather than claiming immunity.
3. Correct the two ledger citations (D1 at `:2963`, not `:3259-3277`; pool-pressure bullets dated
   2026-08-11 (×2)/2026-08-24, not 2026-06-25/2026-08-24) wherever this story's text gets reused,
   including the already-shipped `DataSourceConfig.java` Javadoc, which repeats the same wrong date.
