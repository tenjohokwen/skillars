# Senior-Dev Audit — `skillars-deferred-130-marketplace-reviews-concurrency-audit-and-config-bounds-fix`

**Reviewed at:** `HEAD = 7ed6d30b` (story-creation commit; story's own citation baseline `master@1746965d`)
**Method:** every file, line citation, constraint, precedent and test seam named in the story was opened
and read against actual source. Schema constraints were read out of `V138__baseline_schema.sql`.
No finding below is inferred from the story's own text.

**Verdict: do not implement as written.** AC1 Fix 1 is a real defect fixed for the wrong reason, with a
repro that cannot happen and a prescribed fix that introduces a new deadlock. AC2 reverses a documented,
code-review-decided convention that the story never mentions — and that the very ledger bullet it quotes
does mention. AC1 Fix 2's headline justification is false. The strongest genuine concurrency hole in the
two audited modules was classified as "not a data-integrity bug" and left open.

---

## Citation-accuracy pass (what checks out)

Verified accurate against source, no correction needed:

| Claim | Verified |
|---|---|
| `ReviewFlagService.java:37-93`, `:38`, `:61-72`, `:67`, `:74-76`, `:78-86` | ✅ exact |
| `ReviewSubmissionService.updateReview:106-126`, `submitReview:66-74` | ✅ exact; quoted comments verbatim at `:115-119` |
| `ReviewModerationService.handleReviewSubmitted:93-140` + quoted comment `:94-97` | ✅ exact |
| `AdminReviewService.approveReview`/`blockReview:73-143` + quoted comment `:75-80` | ✅ exact |
| `CoachProfileService.publishProfile:299-318`, `:303-305`, `:309-315`; file is 515 lines | ✅ exact |
| `coach_subscriptions.coach_id` is the PK — `V138:2852-2853` | ✅ exact (`coach_subscriptions_pkey PRIMARY KEY (coach_id)`) |
| `review_flags_unique_flagger` unique index | ✅ `V138:3934` |
| `GdprErasureService.java:630-631` | ✅ exact |
| `ConfigBounds.BoundedKey` record at `:60`; class-Javadoc scope note at `:12-18` | ✅ exact |
| `RadarCompositeCalculationService.java:212` literal re-typing | ✅ exact |
| Zero `@Scheduled` in `platform.marketplace` / `platform.reviews` | ✅ grep returns nothing |
| `deferred-work.md` is 3173 lines; `:3101-3106`, `:3108`, `:3135-3144` | ✅ exact |
| `ReviewFlagService` has no `EntityManager` field (Task 4's prediction) | ✅ confirmed |
| Only callers: `ReviewResource.java:113`, `ProfileBuilderResource.java:99` (Task 5) | ✅ confirmed |
| Test seams `ReviewFlagIT.java`, `CoachProfileBuilderIT.java` exist | ✅ 369 / 957 lines |

Minor citation slack (not worth a task, but fix while editing):

- `ConfigBounds.java:280-286` — the `BoundedKey` declaration is `:280-284`. `:286` starts an unrelated
  section comment ("Templated per-enum key segments").
- `deferred-work.md:3146-3172` (D3/D4/D5) — D5 runs to `:3173`, not `:3172`.
- Task 3: *"Import `DataIntegrityViolationException` (already imported elsewhere in this module, e.g.
  `ReviewFlagService`/`ReviewSubmissionService`)"* — both of those are `platform.reviews`, not
  `platform.marketplace`. It is **not** currently imported anywhere in `CoachProfileService`. The path
  (`org.springframework.dao.DataIntegrityViolationException`) is right; the "already in this module"
  reassurance is not.

---

## BLOCKING

### B1 — AC1 Fix 1's concrete failure scenario cannot happen as narrated (FK row lock serialises it)

The story's entire High-severity case is: admin `blockReview` commits *between* `ReviewFlagService.flag`'s
unlocked read (`:38`) and its threshold write (`:83`). Postgres prevents that interleaving.

`V138:4531-4532` declares `review_flags_review_id_fkey FOREIGN KEY (review_id) REFERENCES
reviews.coach_reviews(review_id)`, and the schema contains **zero** `DEFERRABLE` constraints
(`grep -c DEFERRABLE V138__baseline_schema.sql` → `0`). So the `saveAndFlush(flag)` INSERT at `:67`
fires the RI check immediately, which takes **`FOR KEY SHARE`** on the parent `coach_reviews` row and
holds it until commit. `FOR KEY SHARE` conflicts with `FOR UPDATE`, which is exactly what
`CoachReviewRepository.findByIdForUpdate` (`:23-25`) issues.

Enumerating the windows:

1. **Admin commits before `:67`** → `:74`'s `countByReviewIdAndResolvedAtIsNull` reads a *fresh* snapshot
   and sees the flags `blockReview` just resolved (`AdminReviewService:126` →
   `ReviewFlagRepository.resolveAllOpenFlags`). Count is `1` (our new flag only). At the default
   threshold `3`, `openFlagCount >= threshold` is **false** — no write. Safe.
2. **Admin tries to lock after `:67`** → its `findByIdForUpdate` blocks on our `KEY SHARE` until we
   commit. Our `UNDER_REVIEW` lands first, admin's `BLOCKED` lands second. Admin wins. Safe.
3. **Admin holds `FOR UPDATE` before `:67`** → *our* INSERT at `:67` blocks. On release we fall into
   case 1. Safe.

There is no fourth window. The narrated scenario ("two users have already flagged it… a third flag
arrives… concurrently an admin blocks") is not reachable at the default threshold. Every other
status-writer that could substitute for the admin (`ReviewModerationService.handleReviewSubmitted:102`,
`ReviewSubmissionService.updateReview:106`, `.submitCoachResponse:141`) also enters via
`findByIdForUpdate`, so the same serialisation applies to all of them.

**Confidence:** high, but it rests on two Postgres behaviours worth confirming with a two-session `psql`
test before rewriting the AC — (a) RI check takes `FOR KEY SHARE` on the parent, (b) `FOR KEY SHARE`
conflicts with `FOR UPDATE`. Both are documented (PG *Explicit Locking* §13.3.2); a 5-minute empirical
check makes the rewritten AC defensible.

**Consequence for the story:** the "High" severity, the "Concrete failure scenario" paragraph, and the
Change Log's framing all need rewriting around B4/M2's *actually reachable* trigger. The fix itself is
still worth making — see M2/M3 — but not for the stated reason.

### B2 — The prescribed fix introduces a new deadlock between two concurrent flaggers

Task 2 says: keep the flag INSERT at `:67`, then take `findByIdForUpdate` + `refresh` afterwards. That is
a **`KEY SHARE` → `FOR UPDATE` lock upgrade on a row two transactions can both hold in shared mode**,
which is the textbook Postgres FK-upgrade deadlock:

```
T1: INSERT review_flags(review R, user A)  -- holds KEY SHARE on R
T2: INSERT review_flags(review R, user B)  -- holds KEY SHARE on R  (compatible)
T1: SELECT R FOR UPDATE                    -- waits on T2's KEY SHARE
T2: SELECT R FOR UPDATE                    -- waits on T1's KEY SHARE  → deadlock (40P01)
```

Two users flagging the same review at once is the single most likely concurrent event on this path —
far likelier than the admin race the story is trying to fix. `CoachReviewRepository.findByIdForUpdate`
carries **no** `NO_WAIT` hint (contrast `CoachProfileRepository.findByIdForUpdate`, `:35-38`), so it
blocks until Postgres's deadlock detector fires (~`deadlock_timeout`, default 1s) and aborts one side.
`CannotAcquireLockException` extends `PessimisticLockingFailureException`, so `ApiAdvice:652-658` turns
it into a 409 "This resource is busy" — the flagging user loses their flag to a spurious conflict.

The current code does **not** have this problem: `save(review)` at `:83` emits a plain `UPDATE` on a
non-key column, which takes `FOR NO KEY UPDATE` — compatible with the other transaction's `KEY SHARE`.

**Required change to Task 2:** take the `coach_reviews` lock **before** the `review_flags` INSERT, i.e.
move `findByIdForUpdate` + `refresh` to the top of `flag()` (replacing the plain `findById` at `:38`
entirely, so no `refresh` is even needed — matching `AdminReviewService.approveReview`'s own documented
"first read of the row in this method, so the locked query returns fresh state" rationale at `:78-80`).
This yields one consistent lock order (parent before child) everywhere, removes the upgrade, and fixes
M3 for free.

### B3 — AC2 reverses a documented, code-review-decided convention the story never mentions

AC2 replaces the `2L, 120L` literals at `GdprErasureService:631` with
`GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.min()/.max()`. Three independent pieces of the codebase say
not to:

1. **`ConfigBounds.java:33-35`** (class Javadoc, the file AC2 reads bounds from):
   > *"Each call site still passes its `[min, max]` **literally** (Mockito `verify(...)` in the per-site
   > unit tests pins the exact numbers), but the numbers here and at the call site are kept identical and
   > cross-referenced. If you change a bound, change it in both places."*

2. **`RadarCompositeCalculationService.java:203-210`** — the exact sibling call site AC2 cites as its
   precedent carries a dated reversal of this very change:
   > *"`/bmad-code-review` fix (2026-09-21): min/max passed as the same LITERAL numbers
   > `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS` declares (2L, 120L), **not `.min()`/`.max()`
   > accessor calls** — matching this codebase's own documented ConfigBounds convention… Reading the
   > bound through its own accessors **defeats** that convention: a unit test asserting via
   > `verify(...).getBoundedLong(key, default, 2L, 120L)` would still pass if ConfigBounds's stored
   > min/max silently drifted."*

   The drift guard is live: `RadarCompositeCalculatorTest.java:353-354` asserts
   `verify(configService).getBoundedLong(eq(KEY.key()), eq(5L), eq(2L), eq(120L))` — key via accessor,
   bounds as literals. That is the shape `GdprErasureService:630-631` already matches exactly.

3. **The ledger bullet AC2 quotes** — `deferred-work.md:3141-3144` says:
   > *"**Pre-existing, codebase-wide:** this mirrors `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own call
   > site (`RadarCompositeCalculationService.java:212`) exactly — **the convention the story was directed
   > to follow**."*

   AC2's Context quotes this bullet's "its own story" clause but omits this sentence.

AC2 as written makes `GdprErasureService` the **only** `getBoundedLong` call site in the codebase not
following the convention — strictly worse than either endpoint (all-literals today, or the signature
refactor the ledger names). And AC2's framing — *"a correctness trap with no test or compiler signal
today"* — inverts the documented rationale: under the convention, the literals **are** the signal.

**Recommendation:** drop AC2 or invert it. If the owner still wants D2 closed narrowly, the change that
*is* consistent with the convention is to add a unit test pinning
`verify(configService).getBoundedLong(eq(KEY.key()), eq(5L), eq(2L), eq(120L))` for
`deletePlayerDevelopmentData` — there is no such test today (only `GdprErasureIT`), which is the real
gap. That closes the drift risk D2 names *using* the convention instead of against it. Either way this
needs to go back to the owner: the decision recorded in the Change Log was made without either
`ConfigBounds:33-35` or the 2026-09-21 review reversal on the table.

### B4 — AC2's stated failure mode is factually wrong

AC2 Context: *"…but this one read call site would silently **clamp back down to the stale literal
`120L`**."*

`ConfigService.getBoundedLong(String, long defaultValue, long min, long max)` (`:110-118`) does not
clamp — it **falls back to `defaultValue`**:

```java
public long getBoundedLong(String key, long defaultValue, long min, long max) {
    long value = getLong(key, defaultValue);
    if (value < min || value > max) {
        log.warn("... out-of-range value {} (expected [{}, {}]) — using default {}", ...);
        return defaultValue;          // ← not min/max
    }
    return value;
}
```

Clamping is the *3-arg* overload (`:128-141`), which this call site does not use — deliberately, per
`ConfigBounds:276-277` ("Read via the 4-arg `getBoundedLong` route"). So an operator who raises `max` to
`300` and sets the stored value to `200` does not get `120`; they get **`5`** — the code default, a
25× *reduction*, with a WARN. The real divergence consequence is materially worse and differently
shaped than the story describes, which matters because the description is the justification.

---

## MAJOR

### M1 — AC1 Fix 2's headline justification ("the loser sees an unhandled 500") is false

`ApiAdvice.java:174-200` already has `@ExceptionHandler(DataIntegrityViolationException.class)`. A
commit-time PK violation on `coach_subscriptions_pkey` is translated by `JpaTransactionManager` and
lands there. `coach_subscriptions_pkey` appears in neither `CONSTRAINT_MAPPINGS` (`:135-150`) nor
`CONFLICT_CONSTRAINTS` (`:152-166`), so the loser today gets:

> **HTTP 400**, `messageKey = "generic.dataError"`, message `"Data integrity error"` — logged as
> `"Database constraint violation" constraint=coach_subscriptions_pkey`.

Not a 500, not unhandled, not silent. The genuine defect is only that the error code is generic rather
than `marketplace.alreadyPublished` (422 via `ApiAdvice:395-400`). That is a **Low**-severity UX/error-code
issue, not the Medium the story assigns, and the AC's Context needs rewriting.

There is also a one-line alternative the story never considers, which is what this codebase already does
for this exact class of race: add `"coach_subscriptions_pkey" → "marketplace.alreadyPublished"` to
`CONSTRAINT_MAPPINGS` and the name to `CONFLICT_CONSTRAINTS`. That is precisely the documented precedent
of `uq_pot_one_active_per_user` (`ApiAdvice:139-142`: *"two concurrent OTP-issue calls for one user hit
the partial unique index; the retry is itself a resend"*) and `idx_spt_one_active_per_coach`
(`:147-149`: *"a concurrent tier creation race resolved to a 409 retryable conflict, not a 500"*).
Weigh it against the try/catch before implementing — it needs no `saveAndFlush`, no new import, and
survives a future second inserter.

### M2 — The bug's actual blast radius is misdescribed, and so is its reachable trigger

The story says the write is:

> `UPDATE ... SET moderation_status = 'UNDER_REVIEW' WHERE review_id = ?`

`CoachReview` (`repo/CoachReview.java:21-25`) carries no `@DynamicUpdate` and no `@Version`. `review` is
a **managed** entity from `findById` at `:38`, so Hibernate's dirty check emits an UPDATE of **every
mapped column** from the stale in-memory snapshot — `rating`, `body`, `coach_response_body`,
`coach_response_at`, `moderation_epoch`, `author_role`, all of it.

That changes which concurrent writer actually matters. Of the four writers of `CoachReview` state:

- `AdminReviewService.approveReview` (`:94`) and `.blockReview` (`:126`) both call `resolveAllOpenFlags` —
  which is why B1's window closes: `:74` re-counts and sees `1`.
- `ReviewModerationService.handleReviewSubmitted` only acts when `current == PENDING` (`:128`).
- **`ReviewSubmissionService.updateReview` is the only status-writer that does *not* resolve open flags.**

So the genuinely reachable scenario is:

> Review is `APPROVED` with 2 open flags, threshold `3`. `flag()` reads the row at `:38`. The author's
> `updateReview` commits in the window before `:67` — it sets `PENDING`, bumps `moderationEpoch`, rewrites
> `rating`/`body`, clears `coachResponse*`, and leaves the 2 flags open. `flag()` reaches `:74`, counts
> `3`, sees stale `APPROVED` in memory, and flushes a **full-row** UPDATE that reverts the author's edit
> — rating, body, coach response, and epoch — while setting `UNDER_REVIEW`. The `AFTER_COMMIT` moderation
> listener for that edit then finds `UNDER_REVIEW != PENDING` (`:128`) and discards its verdict, so the
> reverted content is never re-moderated. `coachRatingService.recompute` runs on top.

That is a real lost update on user content, it survives the FK serialisation (because `updateReview`
commits *before* `:67`, not during), and it is strictly more severe than the status-only revert the story
describes. It deserves to be the AC's stated scenario. Note the irony: `updateReview:115-119`'s own
comment already names `ReviewFlagService.flag` as a writer it must not lose to — the reverse direction
was never checked.

### M3 — Fix 1 is incomplete: the flag count stays a pre-lock read

Task 2 re-checks only `moderationStatus` under the lock. The auto-hold guard at `:79` is a **conjunction**:
`openFlagCount >= threshold && status == APPROVED`. `openFlagCount` comes from `:74`, which the task
leaves in place before the lock.

`ReviewSubmissionService.updateReview:115-126` — the precedent the story says it is matching "exactly" —
re-runs its *whole* guard on the refreshed instance. Fix 1 as written re-runs half of one.

Reachable consequence: admin `blockReview` then `approveReview` (both resolving all flags) commit in the
window; the fresh re-check sees `APPROVED` and passes, while the stale count of `3` justifies an auto-hold
that the actual open-flag count (`1`) does not. The review is pushed back to `UNDER_REVIEW` below threshold.

B2's fix resolves this at no extra cost: take the lock first, then read the count under it.

### M4 — Task 2's rationale is wrong: the flag is flushed at `:67`, not committed

> *"(do not throw — the flag itself was already persisted and **committed** at `:67`; only the auto-hold
> escalation is skipped)"*

`saveAndFlush` flushes; it does not commit. `ReviewFlagService` is annotated `@Transactional` at class
level (`:27`), so the `review_flags` INSERT commits only when `flag()` returns normally. Throwing from
the re-check would roll the flag row back.

The **conclusion** (don't throw) is correct and the code is unaffected — but the stated reason is the
opposite of why it matters, and a future reader relying on "already committed" will reason wrongly about
this method. Reword to: *"do not throw — the flag insert is part of this same transaction and must still
commit; only the escalation is skipped."*

### M5 — Task 3's `saveAndFlush` precision argument does not hold

> *"using `saveAndFlush` (not a plain `save`, so the constraint violation surfaces synchronously inside
> this method…), matching `ReviewFlagService.flag`'s own `saveAndFlush` precedent for the identical
> 'must catch **this specific write's** violation, not a later one's' reasoning"*

`saveAndFlush` flushes the **entire** persistence context, not just the passed entity. At `:315` that
context already contains the `coach_profiles` UPDATE queued by `:310`. So the `catch` is *not* scoped to
the subscription insert — any integrity violation from the profile UPDATE would be mistranslated into
`marketplace.alreadyPublished`.

The `ReviewFlagService` precedent works only because its `saveAndFlush(flag)` at `:67` is the
transaction's **first** write, so the flush contains exactly that INSERT. That property does not transfer.
Either inspect the constraint name in the catch, or prefer M1's `ApiAdvice` mapping, which is name-scoped
by construction.

(Separately, and worth a line in the story: `CoachSubscription` has an **assigned** `@Id` with no
`@GeneratedValue` and does not implement `Persistable`, so `isNew()` is `id == null` → `false`, and
`save`/`saveAndFlush` route through `em.merge()` — a SELECT-then-INSERT, not `persist()`. The concurrent
outcome is unchanged (T2's snapshot cannot see T1's uncommitted row, so it still INSERTs and still
violates the PK), so Fix 2 works. But it means the only thing preventing a silent *UPDATE* of an existing
subscription — resetting `activeSince` — is the `status != DRAFT` pre-check at `:303-305`. I verified no
path sets an `ACTIVE` profile back to `DRAFT` (`setStatus(CoachProfileStatus.DRAFT)` occurs only at
`:147`/`:157`, both on newly-constructed entities), so this is not live today. Record it as the invariant
it is.)

### M6 — The prescribed test seam cannot express the prescribed test

AC1's Tests ask for a concurrency test in `ReviewFlagIT` that holds *"a `blockReview`-style write in
flight… between the flagging request's read and its threshold-crossing write."*

`ReviewFlagIT` is an **HTTP-level** IT: it drives `POST /api/reviews/…/flag` through `HttpTestClient`
against `@LocalServerPort`. There is no seam to pause inside `flag()` between `:38` and `:83`, so the
interleaving cannot be constructed from that class.

The cited precedent does not have this shape either. `RadarCompositeCalculationServiceConcurrencyIT` is a
**service-level** IT that autowires the service directly and uses a raw `SELECT … FOR UPDATE` locker
thread — and its own Javadoc (`:29-33`) says so explicitly:

> *"mirroring `BookingServiceConcurrencyIT`'s raw-SELECT-FOR-UPDATE locker-thread shape rather than
> trying to race two real recalculations against each other (**whose completion order is not
> independently observable without invasive instrumentation**)."*

Retarget the test to a new `ReviewFlagServiceConcurrencyIT` under
`src/test/java/.../platform/reviews/service/`, autowiring `ReviewFlagService` and using a
`TransactionTemplate` + latch to commit the racing `updateReview` (per M2 — that is the trigger a test
can actually stage) before invoking `flag()`.

Also: the *"second, simpler test [confirming] the ordinary non-concurrent auto-hold path"* **already
exists** — `ReviewFlagIT.flagThresholdReached_reviewSetToUnderReview` (`:201`). The task should say
"confirm it still passes", not "add it".

---

## MODERATE

### D1 — The exact defect AC2 fixes sits one line above the block AC1 Fix 1 rewrites, unmentioned

`ReviewFlagService.java:76`:

```java
int threshold = configService.getBoundedInt("reviews.autoHoldFlagThreshold", 3, 1, 1000);
```

`ConfigBounds:169-171` declares `REVIEWS_AUTO_HOLD_FLAG_THRESHOLD = new BoundedKey("reviews.autoHoldFlagThreshold", 1L, 1000L, false, …)`.
So this call site re-types **both** the bounds *and* the key string — strictly more divergence-prone than
`GdprErasureService:631`, which at least uses `KEY.key()`. `ReviewSubmissionService.java:165`
(`"reviews.submissionWindowDays", 14, 1, 365` vs `ConfigBounds:164-166`) is the same.

AC2 Task 4's *"Do not touch any other call site"* is a defensible scope line. The problem is that the
owner drew it without knowing the nearest instance is inside the file AC1 is already editing. Surface it
in the story (even just as a noted-and-deliberately-skipped line) so the scoping decision is informed.
Note this cuts *against* AC2 as written: under B3's convention the literals are correct everywhere, and
`ReviewFlagService:76` is then only guilty of the key string.

### D2 — AC3 Task 2 marks ledger D2 "closed" when at most half of it is addressed

`deferred-work.md:3135-3144` states two distinct problems:
1. `min`/`max` re-typed as literals, and
2. *"the code default `5` appears nowhere in `ConfigBounds` despite the key being registered in
   `HAS_CODE_DEFAULT`"* (`:3138-3139` — verified: `HAS_CODE_DEFAULT` at `ConfigBounds:330-348` contains
   `GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key()`, and `BoundedKey` `:60` has no default field).

AC2 addresses (1) only — and per B3, addresses it in the wrong direction. AC2 Task 2 calls (2) *"a
separate, pre-existing, out-of-scope gap already recorded elsewhere in the ledger as D2's own sibling
note"* — it is not a sibling note, it is the second sentence of D2 itself. Mark D2 **partially closed**
with both residuals named, or (per B3) leave it open entirely.

### D3 — The audit's own coverage numbers do not match the source

> *"Checked 0 schedulers / ~20 `@Transactional` methods / **8 entities** across both modules"*

- `@Entity` classes in `platform.marketplace` + `platform.reviews`: **11**
  (`CoachAgeGroup`, `CoachAvailabilityWindow`, `CoachMediaItem`, `CoachPricing`, `CoachProfile`,
  `CoachReliabilityStrike`, `CoachSpecialty`, `CoachSubscription`, `SessionPack`, `CoachReview`,
  `ReviewFlag`).
- `@Transactional` annotations across the two modules: **26** (class- and method-level combined), spread
  over 10 service classes. "~20 methods" is loose but arguable; "8 entities" is not.

Small in itself, but this is the number that backs *"both modules are now confirmed swept"* in AC3
Task 1. Three unexamined entities is three too many for that claim. Recount before recording the closeout.

### D4 — `threshold = 1` is the one config under which the story's narrated race is live

`ConfigBounds:169-171` sets `min = 1`, and `ReviewFlagService:75`'s own comment documents the intent
(*"0 → the first flag on any review auto-holds it"*). At `threshold = 1`, B1's case-1 count of `1` does
satisfy `openFlagCount >= threshold`, so the stale-status write fires and the admin's `BLOCKED` is
reverted. If the story wants to keep an admin-race narrative, this is the honest version of it — and it
is worth a sentence, because it means the fix is not purely defensive.

### D5 — `publishProfile`'s real concurrency hole is left open, and the story calls it a non-bug

AC1 Fix 2 declares the `publishProfile` race **"Not a data-integrity bug"**. That is true for the double-
*publish* case it examines. It is not true for the method.

`publishProfile` takes **no lock at all**. `requireProfile(userId)` returns a managed `CoachProfile`;
`:309-310` mutate and `save` it; the flush happens at `:315`. Between `:301` and that flush sits
`validateAllStepsComplete(profile)` (`:307`) — several queries' worth of window. `CoachProfile` has no
`@DynamicUpdate` and no `@Version` (`repo/CoachProfile.java:24-28`), so the flush writes **every column**
from the stale instance.

`AdminCoachEnforcementService.suspendCoach` (`:129-144`) has no status guard beyond "already SUSPENDED →
return" — it will suspend a `DRAFT` profile. It takes the row lock properly (`:135`, `findByIdForUpdate`
+ `lockRetryer`) and sets `SUSPENDED` + `statusChangedAt`. If it commits inside `publishProfile`'s window,
`publishProfile`'s unlocked full-row UPDATE **reverts the suspension to `ACTIVE`** and restores the old
`statusChangedAt` — no error, no conflict, coach bookable again. `suspendCoach:131-134`'s own comment
names this exact failure mode ("*two writers serialise only when BOTH take it… making that lock
decorative*"); `publishProfile` is the writer that doesn't take it.

Reachability is narrower than M2's (it needs an admin suspending a `DRAFT` profile), so **Medium**, not
High — but it is a genuine data-integrity defect in the method AC1 Fix 2 is already opening, in a module
the story declares swept, and the fix is the same three lines `saveStep4:249-255` already uses
(`lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(...))` +
`entityManager.refresh(profile, PESSIMISTIC_WRITE)` + re-check `status == DRAFT`). Leaving it out while
touching the method for a 400-vs-422 error code is the wrong trade.

---

## Notes for the implementer (verified, no action needed — recorded so they are not re-derived)

- **Two different lock conventions across the two modules.** `CoachProfileRepository.findByIdForUpdate`
  (`:35-38`) is `NO_WAIT` and its Javadoc (`:28-34`) states *"Every one of this method's 7 call sites
  wraps it in `PessimisticLockRetryer`."* `CoachReviewRepository.findByIdForUpdate` (`:23-25`) is a plain
  blocking lock and none of its call sites use the retryer. AC1 Fix 1 is in the reviews module, so **no
  retryer** is correct. Do not reach for `lockRetryer` by analogy with `saveStep4` — and if you do,
  `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` (`:93`) is pinned at **31** and will
  fail the build until bumped. D5's `publishProfile` fix *is* marketplace-side and therefore *does*
  need both the retryer and the count bump to 32.
- **`CoachRatingService.recompute` is safe where it sits, but must stay last.**
  `CoachProfileRepository.updateRatingAggregate` (`:57-61`) is `@Modifying(clearAutomatically = true)`
  with **no** `flushAutomatically` — the classic lost-update footgun. It is safe here only because
  `recompute:24` runs `computeAggregates`, a JPQL query over `CoachReview`, which `FlushMode.AUTO`
  flushes the pending status UPDATE ahead of. Keep `recompute(...)` after every entity mutation in
  `flag()`, and do not put `entityManager.refresh(...)` after it — the clear detaches `review`.
- **`payment.coach_subscriptions` is a different table** (`PaymentCoachSubscription:29`,
  `@Table(schema = "payment", …)`) from `marketplace.coach_subscriptions`. `SubscriptionService` is not a
  second writer of the PK the story cites. Checked; not a finding.
- **`publishProfile` never sets `statusChangedAt`** on `DRAFT → ACTIVE` (`:309`), unlike every
  `AdminCoachEnforcementService` transition. `CoachProfileRepository.findByStatusInOrderByStatusChangedAtAsc`
  (`:53-55`) orders on that column. Pre-existing, out of scope, worth a ledger line if you are editing
  `deferred-work.md` anyway.
- **`ReviewSubmissionService.submitReview`'s catch at `:68-74` is very likely dead code.** `CoachReview`
  uses `@GeneratedValue(strategy = GenerationType.UUID)` (`:28-30`), so `save()` generates the id
  in-memory and does **not** flush; the `uq_coach_reviews_author_coach` violation (`V138:3084`) surfaces at
  commit-time flush, thrown by `TransactionInterceptor` — outside the `try`. The story's Fix-2 rationale
  cites this as *"already establishes the correct pattern"* and explains its `save()` as *"relies on that
  call being the transaction's last write"*; being last does not bring the commit-time flush inside the
  `try`. Don't build on this precedent. (It is pre-existing and out of this story's scope, but it is a
  real latent bug — the caller currently gets `ApiAdvice:174`'s generic 400 instead of
  `ALREADY_SUBMITTED`. Worth its own ledger bullet.)

---

## Suggested disposition

| AC | Disposition |
|---|---|
| **AC1 Fix 1** | Keep the fix, rewrite the justification. Replace the scenario with M2's `updateReview` full-row clobber; take the lock **before** the `review_flags` INSERT (B2) and move the flag count under it (M3); correct the commit claim (M4); retarget the test to a service-level concurrency IT (M6); note `threshold = 1` (D4). |
| **AC1 Fix 2** | Downgrade to Low and re-decide. Today's behaviour is a handled 400, not a 500 (M1). Evaluate the `ApiAdvice` `CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` one-liner against the try/catch; if the try/catch wins, drop the incorrect `saveAndFlush` scoping rationale (M5). |
| **AC1 (new)** | Add D5 — lock + refresh + re-check `DRAFT` in `publishProfile`, mirroring `saveStep4:249-263`. This is the module's real finding. |
| **AC2** | **Return to owner.** It reverses `ConfigBounds:33-35` and a dated `/bmad-code-review` reversal at the sibling call site, neither of which was on the table when the decision was taken (B3), and its stated failure mode is wrong (B4). Recommended substitute: the missing `verify(...)` unit test on `deletePlayerDevelopmentData`'s bounds. |
| **AC3** | Proceed, with corrections: D2 is partially closed at best (D2 above), the coverage numbers need recounting before the "both modules confirmed swept" closeout (D3), and B1/B2/M2/D5 should be recorded as what the audit actually found. |
