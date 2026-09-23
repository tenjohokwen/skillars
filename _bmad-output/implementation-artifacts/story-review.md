# Senior-Dev Story Review — `skillars-deferred-131-marketplace-reviews-hardening-and-ci-reset-deadlock-fix`

**Reviewed:** 2026-09-23
**Reviewed against:** working tree at `HEAD = 30694d58` (story-creation commit; `git diff --stat e2d0233b HEAD`
touches only `_bmad-output/**`, so every source line below is identical to the story's stated base `e2d0233b`).
**Method:** every cited file opened in full or in the cited neighbourhood; every line number counted;
every named constraint checked against `V138__baseline_schema.sql`; every named test class resolved
on disk; every "confirm at implementation time" caveat answered where the source could answer it.
**Verdict:** **Do not hand to `dev-story` as written.** The fixes target real defects, but four of the
prescribed mechanisms do not do what the story says they do (B1–B4), one scope decision rests on a
premise the codebase explicitly contradicts (M1), and the story's headline claim — "all line citations
re-verified, several drifted, corrected inline" — is false for three of the five files it re-cited
(C1–C3), in two cases replacing a *correct* ledger citation with a wrong one.

Nothing below is a style objection. Every item is either a mechanism that will not work, a factual
claim contradicted by source, or a flow the story missed.

---

## Summary table

| # | Severity | Item |
|---|---|---|
| B1 | **Blocking** | Fix 5's two-read restructure reintroduces the exact stale-read bug deferred-130 AC1 Fix 1 closed |
| B2 | **Blocking** | AC4's `pg_advisory_xact_lock` cannot prevent the deadlock it is written to prevent |
| B3 | **Blocking** | AC3's `pg_locks NOT granted` poll cannot observe contention at a NOWAIT lock site |
| B4 | **Blocking** | Fix 4 as literally written breaks `PessimisticLockRetryerCallSiteAuditTest` and violates `withBoundedRetry`'s contract |
| M1 | Major | Fix 3's scope narrowing is justified by a premise `CoachProfileService.java:355-362` contradicts |
| M2 | Major | Fix 3 misses the identical gap in `deleteStrike` (`AdminCoachEnforcementService.java:460-462`) |
| M3 | Major | Fix 3 patches one entry point, not the failure surface (`getCoachSubscriptionTier`, 10 call sites) |
| M4 | Major | Fix 4 does not remove the Stripe-orphan risk — it changes which exception causes it |
| M5 | Major | Two ledger items inside the code this story restructures are neither closed nor triaged |
| M6 | Major | The story mis-identifies which `deferred-work.md` sections it sourced from; one section never triaged |
| C1–C3 | Moderate | Three citation "corrections" are wrong; two of them broke a correct ledger citation |
| F1–F15 | Minor | Factual errors, wrong names, answered "confirm at implementation time" questions |
| T1–T2 | Minor | Test-design traps that will burn implementation time |

---

## BLOCKING

### B1 — Fix 5's restructure reintroduces the bug `skillars-deferred-130` AC1 Fix 1 closed

The story asserts the two-read restructure is *"the only shape that doesn't reintroduce the TOCTOU
`skillars-deferred-130` AC1 Fix 1 closed"* and instructs: preserve the existing comment's reasoning,
*"that guarantee is unchanged, it now just starts one guard-check later."*

**That is false, and this codebase documents why in three places.**

`CoachReviewRepository.findByIdForUpdate` is a JPQL query
(`CoachReviewRepository.java:23-25`: `@Lock(PESSIMISTIC_WRITE)` + `@Query("SELECT r FROM CoachReview r WHERE r.reviewId = :reviewId")`).
Once step 1's unlocked `reviewRepository.findById(reviewId)` has put the `CoachReview` in the
persistence context, step 3's locked query takes the Postgres `FOR UPDATE` lock but **returns the
already-managed instance without refreshing its fields** — the Hibernate identity-map gotcha. The
auto-hold decision at `ReviewFlagService.java:97` (`review.getModerationStatus()`) and the writes at
`:98-101` would then run on the *pre-lock snapshot* — precisely the failure mode deferred-130 fixed.

Evidence, all in-repo:

- `ReviewSubmissionService.java:108-113` — *"findByIdForUpdate is a JPQL query and the row is already
  managed from the unlocked load above, so Hibernate takes the DB lock but returns the existing
  instance without refreshing its fields"* → followed by `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)`.
- `AdminReviewService.java:75-81` — *"This is the first read of the row in this method, so the locked
  query returns fresh state (contrast `BookingService.createBookingRequest`, where an earlier findById
  makes the entity managed and the later locked read returns stale in-memory state)."*
- `ReviewFlagService.java:44-46` — the comment the story says to preserve: *"No `entityManager.refresh`
  is needed — **this is the first read of the row**, not a re-lock of an already-loaded instance."*
  The restructure makes that sentence false.

**Also missing:** `ReviewFlagService` does not inject `EntityManager` (fields at `:30-35`), so the
`refresh` route requires a new dependency the story never mentions.

**Recommended correction — pick one and state it in the story:**

1. *Refresh route:* inject `EntityManager`, and after acquiring the lock call
   `entityManager.refresh(review, LockModeType.PESSIMISTIC_WRITE)`. Rewrite the `:38-46` comment (its
   "first read" justification no longer holds). Mirrors `ReviewSubmissionService.updateReview`.
2. *Projection route (cleaner):* never load the entity for the guards. Add scalar/projection queries
   for the two facts the guards need (`authorId`, `coachId`) so nothing enters the persistence context
   before the locked read, and the existing "first read of the row" guarantee survives verbatim.

Option 2 is the better fit here — it keeps the `:38-46` comment true and adds no `EntityManager`
dependency — but either must be chosen explicitly, not left to `dev-story`.

---

### B2 — AC4's `pg_advisory_xact_lock` cannot prevent the deadlock it targets

The story says: wrap `beforeTestMethod`'s transactional block (`DatabaseResetTestExecutionListener.java:118-124`,
citation **correct**) in `pg_advisory_xact_lock` *"so the reset can never deadlock against any other
concurrent transaction touching the same tables — it will simply wait its turn instead."*

An advisory lock serializes only transactions that **also take that advisory lock**. The hypothesised
counterparty is an application thread (`RadarCompositeCalculationService`, `@TransactionalEventListener(AFTER_COMMIT)`
at `:78` + `@Async("reportExecutor")` at `:82` — both **confirmed**) running ordinary JPA writes. It
will never call `pg_advisory_xact_lock`, so it is entirely unaffected and the `ShareLock` cycle on
`main.player_profiles` remains exactly as reachable as before. The only thing the advisory lock
serializes is *two concurrent resets* — which cannot occur: one Failsafe fork (`pom.xml:660`,
**confirmed**), no `junit-platform.properties` (**confirmed absent**), test methods run sequentially.

The story contradicts a principle it cites in the same paragraph. Its stated precedent is
*"every `findByIdForUpdate` call site in the marketplace/reviews modules"* — and those work precisely
because both writers take the lock. The codebase says so in three places the story itself quotes
elsewhere: `AdminCoachEnforcementService.java:131-134` (*"two writers serialise only when BOTH take
it, making a one-sided lock decorative"*), echoed at `CoachProfileService.java:305-310`.

**Second, internal contradiction:** Task 1 says *"This is a hypothesis, not a confirmed root cause —
do not implement a fix that assumes it without verifying first."* Task 2 then prescribes a fix billed
as *"structural, independent of the exact root cause."* It is not independent — whether an advisory
lock helps depends entirely on whether the counterparty takes it, which is a root-cause question.

**Third, the rejection of retry is unjustified here.** The story rejects retry-on-deadlock by analogy
to production business logic. The reset is *idempotent test infrastructure* — `truncateApplicationTables`
+ `restoreReferenceData` produce the same end state on a second attempt. `40P01` retry is the textbook
handling for a victim of a Postgres deadlock, and here it carries none of the "retry-and-hope"
downside that argument is about.

**Recommended correction:** re-specify Task 2 as one (or both) of:

- **Quiesce, not lock** — drain/await the async executors (`reportExecutor` and any sibling
  `@Async` + `AFTER_COMMIT` writers) before the reset transaction opens. This actually removes the
  second concurrent actor, which is what the story wants. Note there is currently **no** async-drain
  step anywhere in the listener (`:118-128`: tx → `flushRedis` → `evictInProcessCaches` →
  `resetStatefulStubBeans` → `recordCost`).
- **Retry the reset transaction on `40P01`**, bounded, with a WARN naming the conflicting statement.

If the advisory lock is kept for any reason, the story must state plainly that it does *not* close the
mechanism — only a second reset's contention — so the Dev Agent Record doesn't record a false close.

---

### B3 — AC3's `pg_locks` poll cannot observe contention at a NOWAIT lock site

The proposed signal is `SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON l.pid = a.pid
WHERE NOT l.granted AND ...`, applied to **all three** concurrency ITs.

It works for `ReviewFlagServiceConcurrencyIT` — `CoachReviewRepository.findByIdForUpdate` (`:23-25`)
carries **no** `@QueryHints`, so it is a blocking `FOR UPDATE` and the waiter is genuinely visible as
`granted = false`.

It **cannot** work for `CoachProfileServiceConcurrencyIT`. `CoachProfileRepository.findByIdForUpdate`
(`:35-38`) carries `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`
— NOWAIT. The contending backend never enters a wait state: it fails instantly with `55P03`,
`PessimisticLockRetryer.withBoundedRetry` rolls back to its JDBC savepoint and **sleeps in Java**
(75–100 ms, growing ×1.6 to 800 ms, 8 attempts). For essentially the whole window the backend is idle,
not waiting on a lock. The poll would spin until its `atMost` and fail — and would fail on a *correct*
system, which is worse than the `Thread.sleep(300)` it replaces.

The same applies to the new `SubscriptionServiceConcurrencyIT` if Fix 4 uses `CoachProfileRepository.findByIdForUpdate`
(which it must, to serialize against `publishProfile`) — so **two of the three ITs** are affected.

Note this is also why the ledger's own suggestion (`wait_event_type = 'Lock'`, deferred-work.md) has
the same defect; the story inherited it without re-checking the lock mode.

**Recommended correction:** specify two signals, one per lock discipline.

- *Blocking sites* (`ReviewFlagServiceConcurrencyIT`): the `pg_locks`/`pg_stat_activity` poll as drafted.
- *NOWAIT + retryer sites* (`CoachProfileServiceConcurrencyIT`, `SubscriptionServiceConcurrencyIT`):
  poll the retryer's own instrumentation instead — `PessimisticLockRetryer` already increments a
  `persistence.lock_retry.retries` counter on the `MeterRegistry` (`:165-169`). `Awaitility.until(retriesCounter > 0)`
  is a direct, deterministic proof that the contender actually hit the lock and is retrying. That is a
  *stronger* assertion than "a backend is waiting", and needs no `pg_stat_activity` privileges.

Also verify the Testcontainers DB role can read other backends' `pg_stat_activity.query` (needs
superuser or `pg_read_all_stats`) before committing to the SQL route.

---

### B4 — Fix 4 as written breaks `PessimisticLockRetryerCallSiteAuditTest` and violates the retryer's contract

The instruction: *"wrap the existing body of `syncMarketplaceTier` in a
`lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)...)` call before
the `ifPresentOrElse`"*. That sentence is self-contradictory — "wrap the body" and "before the
`ifPresentOrElse`" are different code — and the first reading is actively harmful.

`syncMarketplaceTier`'s body (`SubscriptionService.java:682-695`) contains two
`coachSubscriptionRepository.save(cs)` calls. Putting them inside the retried lambda:

1. **Fails `PessimisticLockRetryerCallSiteAuditTest`** — its `DENYLIST` includes `Pattern.compile("\\.save\\(")`
   and it scans the balanced-paren argument of every `.withBoundedRetry(` call site under `src/main/java`.
2. **Violates the documented contract** — `withBoundedRetry`'s javadoc: the supplier *"can legitimately
   execute more than once for a single logical call"*, so it must be side-effect-free. That test exists
   because this exact mistake was already made once in `DrillUploadService` (see the test's javadoc).

**Recommended correction — state the shape explicitly:**

```java
private void syncMarketplaceTier(UUID coachId, String tier) {
    lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)
        .orElseThrow(...));            // lock only — read-only, retry-safe
    coachSubscriptionRepository.findByCoachId(coachId).ifPresentOrElse(...);  // writes AFTER it returns
}
```

The Postgres row lock is held for the whole transaction, not just the lambda, so this preserves the
guarantee exactly — the same argument the audit test's javadoc makes for `DrillUploadService`.

**And bump the count:** `EXPECTED_CALL_SITE_COUNT` is currently `32`; Fix 4 adds the 33rd. Fix 3 adds
**none** (it reuses `reinstateCoach`'s existing lock), so the story's *"count bump required if so
[Fixes 3/4]"* should read: 32 → 33, from Fix 4 only.

**Open question the story must answer, not defer:** the story asks whether `findByIdForUpdate` finding
no profile is reachable across the five call sites. It is — `payment.coach_subscriptions` and
`marketplace.coach_profiles` are separate tables with separate lifecycles, and the webhook path
(`:606`) resolves the coach from a Stripe subscription id, not from a profile lookup. Decide now:
throw, or skip the lock and proceed as today. Leaving it to implementation time in a method reached
from a webhook is how a webhook starts 500-ing.

---

## MAJOR

### M1 — Fix 3's scope narrowing rests on a premise the codebase explicitly contradicts

The story drops `validateAllStepsComplete` with this reasoning:

> *"every reachable source status here was ACTIVE at some point, meaning it already passed that
> validation once, and nothing in this module clears builder-step data on suspension. Re-running it
> here would be scope creep with no defect behind it."*

`CoachProfileService.java:355-362` — a javadoc added by **deferred-130's own code review**, i.e. the
review this story is closing out — says the opposite:

> *"`AdminCoachEnforcementService.suspendCoach` has no `ACTIVE` guard and `ReliabilityStrikeService`'s
> `PENDING_REVIEW`/`REDUCED` transitions only check the target status, so a profile that is
> `SUSPENDED`/`REDUCED`/`PENDING_REVIEW`/`DEACTIVATED` can be reached **directly from `DRAFT`, without
> ever having been published**"*

Verified against source: `suspendCoach` (`:138-140`) early-returns only on `SUSPENDED` — a `DRAFT`
profile is suspendable. `reinstateCoach` accepts `SUSPENDED`/`PENDING_REVIEW`/`REDUCED` (`:223-228`)
and writes `ACTIVE` unconditionally (`:230`). So `DRAFT → SUSPENDED → reinstate → ACTIVE` puts a
never-validated, never-published profile live on the marketplace. That is exactly the ledger's own
framing of the item (*"mints a marketplace-`ACTIVE` profile that never published … and never runs
`validateAllStepsComplete`"*), and the half the story drops is the half that defect is named after.

This also **answers the story's own open question** — *"confirm at implementation time whether this is
reachable given the accepted source statuses, or is purely defensive"*. It is reachable, and the
codebase already documented it.

**Recommended correction:** either (a) run the validation — `validateAllStepsComplete` is `private`
at `CoachProfileService.java:513`, so this needs a public/package-private seam or a
`CoachProfileService` method call, which is real scope and should be an owner decision; or (b) keep
the narrow find-or-create and record the residual honestly in `deferred-work.md` as *"reinstate can
still activate an unvalidated profile — [DECIDED: accepted]"*. What must not ship is the current
justification, which tells the next reader the case doesn't exist.

### M2 — Fix 3 misses the identical gap in `deleteStrike`

`AdminCoachEnforcementService.deleteStrike`'s tier-3 branch (`:460-462`) sets `ACTIVE` from
`PENDING_REVIEW`/`REDUCED` under the same `findByIdForUpdate` lock (`:409-410`) and likewise never
touches `coach_subscriptions`. Same file, same defect, same one-line remedy — and it is reachable by
exactly the `DRAFT → PENDING_REVIEW` path M1 describes. Fixing only `reinstateCoach` leaves a second
door to the identical broken state. Add it to Fix 3's scope (or state why not).

### M3 — Fix 3 patches one entry point, not the failure surface

The throwing read is `CoachProfileService.getCoachSubscriptionTier` (`:493-497`):
`findByCoachId(coachId).map(...).orElseThrow(new ResourceNotFoundException(...))`. Every coach lacking
a row breaks **10 call sites**, not just the marketplace: `RadarAssessmentService:52` and `:98`,
`ReportGenerationService:140/:262/:275`, `DevelopmentCorrelationService:51`, `QuotaConfigService:63`,
`DrillLibraryService:187`, `CoachMarketplaceResource:88`.

Find-or-create at the reinstate site repairs *future* coaches taking that one path. It does nothing
for any row already in this state, nor for the `deleteStrike` path (M2), nor for any path added later.
Worth an owner decision: default `getCoachSubscriptionTier` to `SCOUT` on a missing row (the same
default `publishProfile` and `syncMarketplaceTier` already write), with the find-or-create as belt-and-braces.

*(Naming: the story attributes the read to "`CoachSubscriptionRepository.getCoachSubscriptionTier`-style
reads (confirm exact method name)". `CoachSubscriptionRepository` has exactly one method,
`findByCoachId`. The method is on `CoachProfileService`.)*

### M4 — Fix 4 does not remove the Stripe-orphan risk; it changes which exception causes it

Fix 4's stated payoff is that a `coach_subscriptions` PK violation no longer rolls back
`persistCoachSubscription` while the Stripe subscription (created outside the transaction at
`SubscriptionService.java:141`, called from the non-transactional `subscribeCoach` at `:104`, handed
to the `@Transactional persistCoachSubscription` at `:148`) survives orphaned.

Adding the lock swaps the failure, it doesn't remove it. `CoachProfileRepository.findByIdForUpdate` is
NOWAIT; `PessimisticLockRetryer` gives it ~3.2 s across 8 attempts and then **rethrows**
`PessimisticLockingFailureException` (`:155-161`). That propagates out of `persistCoachSubscription`,
rolls the transaction back, and leaves the identical orphaned Stripe subscription — just rarer, and
now also reachable from a *slow* `publishProfile` rather than only a colliding one. Fix 4 is still
worth doing (it closes the common case), but the story should say what it does and does not close, so
the ledger closeout is accurate. The durable fix is a compensating action or a reconciliation sweep
for Stripe subscriptions with no local row — that is a separate story, and naming it is the honest move.

*(Positive verification, worth stating in the story so the dev doesn't worry: the three sweeper/webhook
call sites `:467`, `:534`, `:606` each run **one coach per transaction** via
`transactionTemplate.executeWithoutResult`, so adding a row lock introduces no batch lock-ordering
deadlock risk. All five cited call sites — `:163`, `:216`, `:467`, `:534`, `:606` — are **correct**.)*

### M5 — Two ledger items inside the code this story restructures are neither closed nor triaged

AC5 says *"mark every item this story resolves … as closed"*. Two items in the same method the story
restructures three times are not even mentioned:

1. **Lock-order inversion `ReviewFlagService.flag` ↔ `GdprErasureService.erase`.** `flag()` takes
   `coach_reviews` then `coach_profiles` (via `CoachRatingService.recompute` → `updateRatingAggregate`,
   `CoachRatingService.java:29`); `erase()` takes them in the opposite order. Fix 5 changes when
   `flag()` first touches each row (the `coachProfileRepository.findById` guard moves *before* the
   `coach_reviews` lock). That read takes no row lock, so the recorded cycle is unchanged — but the
   story must say so, not be silent, because the reviewer of this change will ask.
2. **`CoachReviewRepository.findByIdForUpdate` blocking-vs-NOWAIT.** Recorded as
   *"[DECIDED 2026-09-23 … accepted for now. … Revisit if flag-endpoint latency or admin-moderation
   contention is observed in production, **or when the reviews module is next opened for a locking
   change**]"*. Fix 5 **is** a locking change to the reviews module. The decision's own revisit trigger
   fires. Either revisit it or record explicitly why the trigger doesn't apply; AC5 cannot close out
   honestly while it sits untouched.

Note item 1's premise is also what Fix 5 partly acts on (the ledger's lock-scope item says the guards
were *"deliberately not patched"* because moving them *"conflicts with that AC's explicit 'locked as
this transaction's first read' shape"*) — which is the same objection as B1, already on the record in
the ledger the story was drawn from.

### M6 — The story mis-identifies which ledger sections it sourced from

Context claims the two newest `deferred-work.md` sections are
`## Last audit: 2026-09-23 (skillars-deferred-130 dev-story completion)` and
`## Deferred from: story review of skillars-deferred-130-…`. Actual section order at the tail of the file:

| Line | Section |
|---|---|
| 3120 | `## Last audit: 2026-09-23 (skillars-deferred-130 dev-story completion)` |
| 3163 | `## Deferred from: story review of skillars-deferred-130-…` |
| 3194 | `## Deferred from: code review of skillars-deferred-129-… (2026-09-23)` |
| **3274** | **`## Deferred from: code review of skillars-deferred-130-… (2026-09-23)`** ← newest, and where Fixes 3–8 actually come from |

Low risk to the fixes themselves (the story clearly read 3274 — its Fixes 3–8 map one-to-one onto that
section's bullets). But the Change Log's claim that *"a fresh audit … confirmed every older section is
already `[CLOSED by …]` or `[DECIDED: accepted risk …]`"* is unverified for the deferred-129 code-review
section at **3194**, which the story never names and which is newer than one of the two it does name.
Triage it before AC5 closeout.

---

## MODERATE — citation accuracy

The story's Context states: *"All line citations below were re-verified against `HEAD = e2d0233b` …
Several drifted by 1-3 lines from the ledger's own citations, corrected inline."* Verified file by
file: **`ReviewFlagService` and `CoachProfileService` citations are correct and the corrections there
are genuine improvements. `ReviewSubmissionService`, `AdminCoachEnforcementService` and
`AdminReviewService` were not re-verified**, and two of the three "corrections" replaced a correct
ledger citation with a wrong one.

### C1 — Fix 1 broke a correct citation

Story: *"`ReviewSubmissionService.java:66-73` (ledger cited `:68-74`, drifted 2 lines … )"*, and
instructs the change at *"`:66-67`"*.

Actual source: `try {` at **68**, `review = coachReviewRepository.save(review);` at **69**,
`} catch (DataIntegrityViolationException e) {` at **70**, closing `}` at **74**. The ledger's
`:68-74` was **exactly right**. `:66-67` is `review.setModerationStatus(...)` / `review.setLastModifiedAt(...)`
— the two lines *before* the `try`. Revert to `:68-74`, `saveAndFlush` at `:69`.

*(The mechanism claim is sound and independently confirmed: `CoachReview.reviewId` is
`@GeneratedValue(strategy = GenerationType.UUID)` (`CoachReview.java:28-31`), so `save()` → `persist()`
with a client-side UUID and no flush; `ReviewResource.submitReview` (`:74-84`) is not `@Transactional`,
so `ReviewSubmissionService`'s class-level `@Transactional` is the outermost boundary and the mapped
`ALREADY_SUBMITTED` will propagate cleanly. Fix 1 is correct — only its coordinates are wrong.)*

### C2 — Fix 3 broke a correct citation, twice, by ~10 lines

Story: *"`AdminCoachEnforcementService.java:219-221` (the `ACTIVE` write; ledger cited `:217-232`,
which is the right neighborhood but the write itself is at `:219-221`)"* and *"runs under
`reinstateCoach`'s own `findByIdForUpdate` lock (`:207-208`)"*.

Actual source:

| What | Real line |
|---|---|
| `lockRetryer.withBoundedRetry(… findByIdForUpdate …)` | **217-218** (story said 207-208) |
| `if (coach.getStatus() == ACTIVE) { return; }` | 220-222 ← *this is what `:219-221` points at* |
| source-status guard | 223-228 |
| `coach.setStatus(ACTIVE); coach.setStatusChangedAt(…); save(coach);` | **230-232** (story said 219-221) |

The ledger's `:217-232` spans lock → write exactly. Both of the story's "corrections" are wrong, and
`:219-221` aims the dev's insertion point at the early-return guard — i.e. at code that returns before
the write, where inserting the find-or-create would be dead.

### C3 — Fix 8 repeated a stale citation and added a new wrong one

Story: *"`AdminReviewService.java:94` (`reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now());`)"*
and *"`AdminReviewService` already has a `log` field (used at `:104`)"*.

Actual: `resolveAllOpenFlags` is at **:95** (the ledger's `:94`, uncorrected); `log.info("Review
approved: …")` is at **:106**, not `:104`. (`blockReview`'s own `resolveAllOpenFlags` is at **:125**;
the ledger said `:126`.)

### C4 — Citations that ARE correct (verified, so the dev need not re-check these)

`ReviewFlagService.java` — lock `:47-49` ✓, self-flag guard `:51-54` ✓, coach-profile guards `:56-63` ✓,
`ALREADY_FLAGGED` `:65-68` ✓, insert+catch `:70-81` / `:75-81` ✓, auto-hold `:92-104` ✓,
`flaggedBy.equals(...)` `:59` ✓ (all three of the story's corrections against the ledger here are right).
`CoachProfileService.java:329` (`setStatus(ACTIVE)`) ✓, `:513` (`private validateAllStepsComplete`) ✓,
`:335-343` find-or-create pattern ✓, `Instant` **not** imported ✓ (`:45-53` has `OffsetDateTime`/`ZoneId` only),
`CoachProfile.statusChangedAt` is `Instant` ✓ (`CoachProfile.java:70-71`).
`AdminCoachEnforcementService` — `suspendCoach:143` ✓, `reinstateCoach:231` ✓, `:450`/`:461` ✓,
`findByStatusInOrderByStatusChangedAtAsc` ✓ (call site `:498`).
`SubscriptionService.java:682-694` ✓ and all five `syncMarketplaceTier` call sites ✓.
`V138__baseline_schema.sql:4441` (`coach_subscriptions_coach_id_fkey` → `marketplace.coach_profiles(id)`) ✓.
`DatabaseResetTestExecutionListener.java:118-124` ✓, `:311-314` (the ~99.7 ms CI mean) ✓
(`:356` is the `DO $$` string literal; the `jdbc.execute(` call is `:355` — harmless).
`pom.xml` single-fork comment ✓ (~`:659-661`), `junit-platform.properties` absent ✓,
`RadarCompositeCalculationService` `@TransactionalEventListener(AFTER_COMMIT)` `:78` + `@Async("reportExecutor")` `:82` + `PlayerProfileRepository` ✓,
`RadarAssessmentResourceIT.getMyEntries_returnsOwnEntriesOnly` ✓ (`:222`),
`main.player_profiles` is `PlayerProfile`'s table ✓.

---

## MINOR — factual corrections and answers to the story's open questions

**F1.** The `coach_reviews` unique constraint is **`uq_coach_reviews_author_coach`** (`V138:3084`),
not *"`review_author_coach_unique`-style"*.

**F2.** Fix 6's rationale lists *"a `flagged_by` FK failure"* as a constraint that could mis-map. There
is **no FK on `review_flags.flagged_by`** — `review_flags_review_id_fkey` (`V138:4531-4532`) is the
table's only FK. The real candidates are: `reason` NOT NULL, `details` > 500 chars, the `review_id` FK,
and `review_flags_pkey`.

**F3.** `review_flags_unique_flagger` is a **UNIQUE INDEX** (`V138:3934`), not a table constraint.
Postgres does report the index name in `23505`'s `constraint` field, so name-matching works — but pin
it with a real IT rather than only a Mockito fixture (see T2).

**F4.** Fix 3's second test case says *"e.g. `PRO` from a Stripe purchase made while suspended"*.
`CoachSubscriptionTier` is `{SCOUT, INSTRUCTOR, ACADEMY}` (`CoachSubscriptionTier.java:6`, with a
load-bearing ordinal assertion). `PRO` is a **player** tier (`SubscriptionService.java:59`
`PLAYER_TIERS`). The scenario is real (`subscribeCoach` has no `DRAFT`/`ACTIVE` guard — confirmed);
use `INSTRUCTOR`/`ACADEMY`. *(The ledger's own "PRO/ELITE" wording at deferred-work.md:3140 and the
comment at `CoachProfileService.java:341` are wrong the same way — worth correcting while in there.)*

**F5.** Fix 2's impact statement is weaker than the ledger's and slightly wrong. `status_changed_at`
is nullable with no DB default (`V138:1585`), no entity default (`CoachProfile.java:70-71`), and is
**not** set at `DRAFT` creation (`CoachProfileService.java:147`, `:157`). So a freshly published
profile carries **NULL**, which Postgres sorts **NULLS LAST** under `ORDER BY … ASC` — not *"as if it
had been in its current status since whenever the row was first created"*. The ledger had this right
(*"or `NULL`, ordered last via `NULLS LAST`"*); the story dropped it. Also,
`getCoachesUnderEnforcement` defaults to `[PENDING_REVIEW, SUSPENDED]` (`AdminCoachEnforcementService.java:488`),
so a freshly-published `ACTIVE` profile reaches that query only under an explicit `status=ACTIVE`
filter — its more direct effect is a `null` `statusChangedAt` in `CoachEnforcementListItemDto` (`:511`).
The fix is right; the justification should be too.

**F6.** AC3: *"`Awaitility` itself is already used elsewhere, e.g. `BookingServiceConcurrencyIT`"* —
**`BookingServiceConcurrencyIT` does not use Awaitility.** It is a real dependency (`pom.xml:447-448`)
used in `RefundOutboxIT`, `SluSnapshotOutboxIT`, `VideoPurgedEventIT`, `SecurityFilterChainIT`. Cite
one of those.

**F7.** AC3 says the ITs use `Thread.sleep(300)` as *"their sole"* signal.
`ReviewFlagServiceConcurrencyIT` has **two** (`:191`, `:300`); `CoachProfileServiceConcurrencyIT` has
one (`:192`). Three call sites to convert in the existing files, not two.

**F8.** Fix 8 prescribes a second query (`countByReviewIdAndResolvedAtIsNull`) before the resolve.
`ReviewFlagRepository.resolveAllOpenFlags` (`:22-24`) is a `@Modifying` JPQL `UPDATE` returning `void`
— change it to `int` and log the row count it already computes. One statement instead of two, and no
window between count and update. *(Also note `clearAutomatically = true` detaches `review` after the
call; harmless today — only getters are used at `:96` and `:105` — but worth knowing.)*

**F9.** Fix 8 covers `approveReview` only. The ledger item explicitly also names
*"`BLOCKED` reviews likewise still accept flags that can never act (`blockReview:126` already resolved
them)"* — real line `:125`. Record the decision (block is arguably less surprising than approve) rather
than silently dropping half the item, or AC5 will close an item that was only half addressed.

**F10.** Named test classes, resolved on disk:

| Story says | Reality |
|---|---|
| `ReviewSubmissionServiceIT` (*"check … first"*) | Does not exist. The real one is **`src/test/java/com/softropic/skillars/platform/reviews/api/ReviewSubmissionIT.java`** (364 lines; already asserts `reviews.alreadySubmitted` at `:193`) |
| *"unit test on `ReviewFlagService` (Mockito)"* (Fixes 6, 7) | No `ReviewFlagServiceTest` exists — this is a **new class**, not an addition |
| `AdminCoachEnforcementServiceIT` | Does not exist. Existing: `AdminCoachEnforcementConcurrencyIT`, `AdminCoachEnforcementIsolationRuntimeIT`, `AdminCoachEnforcementServiceIsolationTest` |
| `CoachProfileBuilderIT` | Exists, at `marketplace/**api**/`, not `marketplace/service/` |
| `SubscriptionServiceConcurrencyIT` | New. Nearest shapes: `SubscriptionLifecycleIT`, `SessionPackPurchaseLockContentionIT` |

**F11.** Fix 8 asks to *"check for precedent"* on log capture. **Yes** — `ListAppender` /
`OutputCaptureExtension` / `CapturedOutput` are used in ≥10 classes (`BookingServiceTest`,
`SluCalculationServiceIT`, `OutboxServiceTest`, the three registration-email listener tests, …). No
new dependency needed.

**F12.** Fix 7 worries a new `ReviewErrorCode` may need more than the enum. It does not — the
`src/main/resources/i18n/messages*.properties` bundles carry no `reviews.*` error keys at all, and no
test enumerates `ErrorCode` values. Adding one constant is the whole change.

**F13.** Dev Notes' constructor-injection concern: **confirmed safe**. `grep -rn "new AdminCoachEnforcementService(\|new SubscriptionService(\|new ReviewFlagService(\|new CoachProfileService(" src/test`
returns nothing. All four are Spring-injected or mocked.

**F14.** AC5 asks to correct *"a running narrative of 'N items remain' in places"*. There is exactly
one such sentence, at `deferred-work.md:3237`, scoped to the deferred-129 section — not a global
counter. Don't send the dev hunting for something that isn't there.

**F15.** Fix 4's mechanism narrative is directionally right but slightly over-specific: the PK
violation arrives via the `coach_subscriptions_pkey` unique-index wait at least as directly as via the
`coach_profiles` FK `FOR KEY SHARE` chain. Either path ends in the same `DataIntegrityViolationException`;
don't let the story's FK framing make the dev think the FK is the only route.

---

## Test-design traps

**T1 (Fix 1's IT).** `CoachProfileServiceConcurrencyIT`'s shape — the one the story says to mirror —
wraps the lock-*holder* in `transactionTemplate.execute` and calls the contender directly
(`:154-189`). Fix 1's IT must do the same. If the **loser's** `submitReview` is invoked from inside an
outer `transactionTemplate.execute`, the flush-time `DataIntegrityViolationException` marks that outer
transaction rollback-only, and the test observes `UnexpectedRollbackException` at the outer boundary
instead of `ALREADY_SUBMITTED` — a false failure that will read like the fix didn't work. Only the
winner gets wrapped; the loser must be called directly so `ReviewSubmissionService`'s own
`@Transactional` is outermost (as it is in production — `ReviewResource` opens no transaction).

**T2 (Fix 6's test).** A Mockito test that feeds a hand-built `DataIntegrityViolationException` into
the catch proves only that your parser matches your own fixture. Since F3 shows the name comes from a
unique *index* (not a constraint), pin the real message shape with at least one Testcontainers IT that
provokes a genuine duplicate flag, then keep the Mockito test for the negative branch.

---

## Recommended pre-implementation actions

1. **Rewrite Fix 5** with an explicit stale-read remedy — refresh-under-lock or projection guards (B1).
2. **Rewrite AC4 Task 2** — quiesce the async executors and/or retry the reset transaction on `40P01`;
   drop the advisory lock or state plainly that it closes nothing (B2). Resolve the Task 1 / Task 2
   contradiction while in there.
3. **Split AC3's wait signal in two** — `pg_locks` for the blocking site, the retryer's
   `persistence.lock_retry.retries` meter for the two NOWAIT sites (B3).
4. **Rewrite Fix 4's code shape** — lock inside `withBoundedRetry`, writes after it returns; state
   `EXPECTED_CALL_SITE_COUNT` 32 → 33; decide the missing-profile behaviour now (B4).
5. **Re-open the Fix 3 scope decision** with `CoachProfileService.java:355-362` in front of the owner
   (M1), and extend it to `deleteStrike:460-462` (M2).
6. **Re-verify Fixes 1, 3 and 8's line citations** against source — use `:68-74`, `:217-218` / `:230-232`,
   `:95` / `:106` (C1–C3).
7. **Triage the two untouched ledger items** in `ReviewFlagService`/`CoachReviewRepository` (M5) and
   the deferred-129 code-review section at `deferred-work.md:3194` (M6) before AC5 closeout.
8. **Fold in F1–F15** — most are one-word corrections that each save the dev a lookup, and F10 alone
   redirects five test-class references that don't resolve.
