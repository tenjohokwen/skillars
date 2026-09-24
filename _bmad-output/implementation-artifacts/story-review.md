# Story Review: `skillars-deferred-132-lock-contention-transactional-safety-and-config-bounds-hardening`

**Reviewed:** 2026-09-24
**Reviewer:** senior-dev audit (adversarial, source-verified)
**Story HEAD:** `68fafa7d` (story creation commit); story's own citations were drafted against
`2842df52`
**Method:** every file, line citation, constraint, precedent and test file named in the story was
opened and checked against the actual source. Nothing below is inferred from the ledger or from the
story's own prose — each finding names the file and line that contradicts it. Items I could not
confirm as defects are **not** listed.

---

## Verdict

**Do not implement as written.** The story is unusually well-researched — its line citations are
almost all accurate (see the citation audit at the end), several of its self-corrections against
`deferred-work.md` are genuinely right, and it correctly refuses to re-litigate closed items. But
three of its twelve fixes rest on a **false premise about the code they touch**, and two of those
would ship a user-visible regression or an always-firing scheduler if implemented literally. A
fourth (Fix 4's recommended mechanism) is unsound as specified.

| | Count | Fixes |
|---|---|---|
| **Blocker** | 3 | Fix 2, Fix 3, Fix 11 |
| **High** | 4 | Fix 4, Fix 7, Fix 2 (tests), Fix 3 (missed flow) |
| **Medium** | 5 | Fix 6, Fix 1, Fix 12, Fix 10, Fix 9 |
| **Low** | 7 | Fixes 2, 3, 5, 8, 11 |

---

## Story items checked against source

| # | Fix (story name) | Premise holds? | Severity |
|---|---|---|---|
| 1 | `ReviewFlagService.flag()` ↔ `GdprErasureService.erase()` lock-order inversion (document only) | Conclusion right, **stated reason wrong** | Medium |
| 2 | `CoachReviewRepository.findByIdForUpdate` → NOWAIT + `PessimisticLockRetryer` | **No — "sole call site" is false (6 sites)** | **Blocker** |
| 3 | Marketplace-tier reconciliation sweep | **No — prescribed comparison never matches** | **Blocker** |
| 4 | Bound GDPR erasure's `REQUIRES_NEW` connection wait | Risk real; **recommended mechanism (b) unsound** | High |
| 5 | `PessimisticLockRetryer` per-call-site retry attribution | Yes — accurate | Low (nits) |
| 6 | Empirically verify CI reset-deadlock root cause | **Protocol cannot reproduce anything** | Medium |
| 7 | `submitReview` → `REQUIRES_NEW` | Trap real; **fix applied to 1 of 2 identical sites** | High |
| 8 | `PerformanceReport` storage-key projection | Yes — accurate | Low |
| 9 | Single `lock_timeout` config read per `erase()` | Partly — **mitigates frequency, not the hazard**; misses a call site | Medium |
| 10 | Point review config call sites at `ConfigBounds` constants | Change right; **fail-fast + test claims wrong** | Medium |
| 11 | `PessimisticLockRetryerCallSiteAuditTest` lock-site coverage | **No — "all sites wrapped today" is false** | **Blocker** |
| 12 | Two missing GDPR branch×reason tests | Gap real; **existing-coverage premise wrong** | Medium |

---

## BLOCKERS

### B1 — Fix 2: `CoachReviewRepository.findByIdForUpdate` has **six** call sites, not one. Adding NOWAIT silently converts five unretried blocking locks to fail-fast.

The story states, twice, that `ReviewFlagService.java:92` is the *sole* call site and that the fix
is therefore contained. It is not:

| Call site | Method | Wrapped in `withBoundedRetry`? |
|---|---|---|
| `ReviewFlagService.java:92` | `flag()` | no (Fix 2 adds it) |
| `ReviewSubmissionService.java:113` | `updateReview()` | **no** |
| `ReviewSubmissionService.java:148` | `submitCoachResponse()` | **no** |
| `AdminReviewService.java:81` | `approveReview()` | **no** |
| `AdminReviewService.java:121` | `blockReview()` | **no** |
| `ReviewModerationService.java:102` | AFTER_COMMIT moderation verdict | **no** |

All six resolve to the same interface (`CoachReviewRepository`, confirmed at
`ReviewSubmissionService.java:34`, `AdminReviewService.java:35`, `ReviewModerationService.java:28`).
`@QueryHints` sits on the **repository method**, so Fix 2 step 1 changes the lock discipline for
every one of them, while step 3 adds a retry wrapper to exactly one.

**The worst consequence is a silently-lost moderation verdict.**
`ReviewModerationService.handleReviewSubmitted` is a non-async
`@TransactionalEventListener(AFTER_COMMIT)` that re-reads the review under
`findByIdForUpdate` inside its own `REQUIRES_NEW` template (`:102`), and its outer catch
(`:154-163`) *deliberately swallows everything*:

> `// Note this also swallows a lock-acquisition failure on the read above, which leaves the`
> `// review PENDING rather than mis-resolved — the safe direction.`

That comment is written for a world where the lock **blocks**, making the failure essentially
unreachable. Under NOWAIT-without-retry it becomes reachable on any brief overlap (an admin
approving, an author editing, a concurrent `flag()`), and the review is then left `PENDING`
**permanently** — the event is not re-published and there is no sweeper for it. A submitted review
that is never moderated is never visible.

Secondary: `approveReview`/`blockReview` return a 409 to an admin instead of waiting ~milliseconds;
`updateReview`/`submitCoachResponse` do the same to the author.

**Required change.** Either (a) wrap all six sites in `PessimisticLockRetryer` and bump
`EXPECTED_CALL_SITE_COUNT` 33 → **39**, not 34 — noting that `ReviewModerationService`'s site is
inside a manually-built `TransactionTemplate` and `PessimisticLockRetryer` requires an active
transaction and a savepoint-capable connection, so that one needs checking specifically; or (b)
leave `findByIdForUpdate` blocking and add a **second** repository method
(`findByIdForUpdateNoWait`) used only by `flag()`. (b) is far smaller and keeps the blast radius
matched to the item the ledger actually raised.

---

### B2 — Fix 3: the prescribed reconciliation comparison can never be equal, so the sweep rewrites and alerts on **every** active coach, **every** run.

The story's snippet:

```java
if (marketplace.isEmpty() || !marketplace.get().getTier().equals(sub.getTier())) {
```

- `marketplace.get()` is `com.softropic.skillars.platform.marketplace.repo.CoachSubscription`;
  `getTier()` returns **`CoachSubscriptionTier` (an enum)** — `CoachSubscription.java:30`.
- `sub` is `PaymentCoachSubscription`; `getTier()` returns **`String`** —
  `PaymentCoachSubscription.java:45`.

`Enum.equals(String)` is always `false`. Therefore the `if` is always taken. Every ACTIVE/TRIALLING
coach, on every scheduled run, would:

1. take the `coach_profiles` **FOR UPDATE NOWAIT** lock (via `syncMarketplaceTier`'s
   `lockRetryer.withBoundedRetry`, `SubscriptionService.java:719`) — real contention against
   `publishProfile`, `reinstateCoach`, `deleteStrike` and the two sibling schedulers;
2. rewrite the `marketplace.coach_subscriptions` row;
3. emit `[COACH_TIER_RECONCILED …]` at **WARN**, turning a designed-to-be-rare alert into per-coach
   noise on a daily cadence.

It also makes the story's own acceptance test impossible: *"a coach whose tiers already match causes
no write (assert no `COACH_TIER_RECONCILED` log line for that coach)"* can never pass.

**Required change.** Compare on one side of the boundary, e.g.
`marketplace.get().getTier() != CoachSubscriptionTier.valueOf(sub.getTier())` — and guard the
`valueOf` (a payment row whose `tier` is outside `COACH_TIERS`, `SubscriptionService.java:63`, would
throw `IllegalArgumentException` *per row* rather than being skipped cleanly).

---

### B3 — Fix 11: "All current `findByIdForUpdate` sites are correctly wrapped today" is false. The proposed assertion fails on ~12 legitimate call sites on its first run.

The story's premise is an over-generalisation of the ledger's own, narrower claim. `deferred-work.md`
says *"All **15 `coachProfileRepository`** `findByIdForUpdate` sites are correctly wrapped today
(hand-checked during this review)"*. The story drops the qualifier.

Four repositories declare `findByIdForUpdate` **without** `@QueryHints(lock.timeout = 0)` — i.e.
genuinely blocking locks that neither need nor should have a retry wrapper:

| Repository | Blocking? | Unwrapped call sites |
|---|---|---|
| `VideoQuotaRepository:17` | yes | `QuotaService:81` |
| `CoachPayoutRepository:31` | yes | `CoachPayoutTransferHandler:66`, `DisputeService:315` |
| `MessageRepository:21` | yes | `AdminMessageService:101,140`, `MessagingService:332`, `ModerationResultApplier:53`, `MessageModerationSweeper:115` |
| `CoachReviewRepository:25` | yes | the six from B1 |

(The NOWAIT repositories — `VideoRepository:32`, `PlayerProfileRepository:63`,
`BookingBatchRepository:28`, `BookingRescheduleRequestRepository:34`, `BookingRepository:194`,
`SessionPackPurchaseRepository:22`, `CoachProfileRepository:38`, `DrillRepository:27` — *are* all
covered, several inside multi-line lambdas that a method-scoped token check would catch correctly,
e.g. `RescheduleService:195-200`, `CoachProfileService:252-258`, `DrillUploadService:99-128`.)

This also falsifies Fix 2's own Context claim that `CoachReviewRepository` is *"the reviews module's
only remaining blocking lock (every other locked read in this codebase … already carries
`@QueryHints`)"*. Three other repositories still block.

**Required change.** Scope the new assertion to repositories whose `findByIdForUpdate` declares
`jakarta.persistence.lock.timeout = "0"`, and state the blocking-lock repositories as an explicit,
justified exemption list (that list is itself the useful artefact — it is the set that would need
revisiting if the NOWAIT convention is ever made universal).

---

## HIGH

### H1 — Fix 4: the recommended mechanism (b) does not do what it claims, and can create the deadlock it was meant to avoid.

Option (b) is *"run `requiresNewTemplate.execute(...)`'s opening on a dedicated single-thread
executor and `future.get(5, SECONDS)`"*. Four problems, in descending order:

1. **`future.get` timing out does not cancel the acquisition.** The submitted task keeps running.
   When the pool frees up it acquires a connection and **commits the child's deletes + tombstone
   after `erase()` has already alerted and moved on** — concurrently with the still-open outer
   transaction that holds `main."user"` and `player_profiles` locks for the same account. That is a
   genuine two-transaction deadlock, not merely a leaked thread. Today's behaviour (a 30s wait) is
   worse for latency but is at least serialised.
2. **Thread affinity.** `TransactionSynchronizationManager` is `ThreadLocal`, and both
   `GdprErasureService.entityManager` and `PessimisticLockRetryer.entityManager` are
   `@PersistenceContext` proxies resolved per thread. The lambda's `entityManager.refresh(...)`,
   `createNativeQuery("SELECT set_config('lock_timeout', …)")` and `flush()` would bind to a
   different context than the caller's.
3. **Exception-type erasure.** `future.get()` wraps in `ExecutionException`, so the two call sites'
   carefully-typed `catch (DeleteStatementLockTimeoutException | PessimisticLockingFailureException)`
   (`GdprErasureService.java:237` and `:427`) stop matching. The whole
   `CHILD_CONTENDED`/`CHILD_DELETE_LOCK_TIMEOUT` discrimination — built and defended across
   deferred-128/129 — silently collapses unless every path unwraps.
4. **Option (a) is costed too low.** The story says the project *"does not currently"* split
   repositories by `entityManagerFactoryRef`. In fact there is **no `@EnableJpaRepositories`
   anywhere in `src/main/java`** — Boot auto-configures. Defining a second `EntityManagerFactory`
   bean backs `HibernateJpaAutoConfiguration` off entirely, forcing the *primary* EMF to be
   hand-wired too. That is a much larger change than "duplicate ~11 repository interfaces".

**Context the story omits that actually strengthens the case for the fix:** `erase()` runs on the
**HTTP request thread**, from a non-`@Async` `@TransactionalEventListener(AFTER_COMMIT)`
(`GdprErasureService.java:348-352`, `GdprEventListener.java:35`). Spring invokes `afterCommit`
*before* `cleanupAfterCompletion` releases the outer connection — so `erase()`'s `REQUIRES_NEW`
genuinely needs a **second** pooled connection while the first is still held. That is why the wait
exists at all, and it belongs in the Context.

**Suggested direction.** Prefer a mechanism that fails fast *on the caller's own thread*: e.g. keep
everything single-threaded and bound the aggregate through the existing `gdprEraseLockBudget`
deadline (already sampled per child at `:383-398`), extending it to also cover the PLAYER branch;
or set a short `connectionTimeout` on a genuinely separate `DataSource` used through a plain
`DataSourceTransactionManager` (no second EMF) if a dedicated pool is wanted. Whatever is chosen,
the story should explicitly rule out "run the transaction on another thread".

---

### H2 — Fix 7 fixes one of two byte-for-byte identical traps, and does not weigh `REQUIRES_NEW`'s own costs.

`ReviewFlagService.flag()` has the **same** shape Fix 7 exists to fix:

```java
// ReviewFlagService.java:101-114
try {
    reviewFlagRepository.saveAndFlush(flag);
} catch (DataIntegrityViolationException e) {
    if (isUniqueFlaggerViolation(e)) {
        throw new OperationNotAllowedException(..., ReviewErrorCode.ALREADY_FLAGGED);
    }
    throw e;
}
```

`saveAndFlush` → catch DIVE → translate to a clean 4xx, safe only because `ReviewResource` opens no
transaction. Fix 7's entire rationale applies verbatim. The story never mentions it, so AC2 would
ship an inconsistent half-fix on two sibling methods in the same module that were *deliberately*
made to mirror each other (`ReviewSubmissionService.java:71-74` says so explicitly: *"mirrors
`ReviewFlagService.flag`'s identical Fix 6"*).

Unweighed consequences of `REQUIRES_NEW` on `submitReview`:

- **Semantic change.** The `coach_reviews` row now survives an outer rollback, and its AFTER_COMMIT
  moderation listener fires on the *inner* commit. Today an outer rollback discards both. This is
  arguably desirable but it is a behaviour change, not pure isolation.
- **Self-deadlock risk in exactly the scenario the story's test constructs.** If the hypothetical
  outer transaction has itself touched the conflicting `uq_coach_reviews_author_coach` key, the
  suspended outer transaction holds the uncommitted index entry while the inner one blocks on it —
  on the same thread, forever. The proposed test ("wrap a call to `submitReview` in an outer
  `@Transactional`/`TransactionTemplate` block") walks straight toward this; it must be written to
  avoid it, and the story should say so.
- **A second pooled connection per submit**, in the same story that argues (Fix 4) the 25-connection
  pool is the constrained resource.

---

### H3 — Fix 2 breaks **two** existing tests, not one, and orphans the helper it was built on.

`ConcurrencyLockWaitSupport.awaitBlockingLockWaiter` is called twice, in two different tests:

- `ReviewFlagServiceConcurrencyIT:201` — `concurrentUpdateReview_doesNotRevertEditOrWronglyAutoHold`
- `ReviewFlagServiceConcurrencyIT:312` — `flagUnderGenuineLockContention_stillAppliesAutoHoldOnceThresholdReached`

The story's test plan names one ("the existing blocking-`FOR UPDATE` contention test", singular).
Both break, and both break *twice over*: under NOWAIT the contender never enters a Postgres wait
state, so the helper's 10s Awaitility poll can never be satisfied — and the retryer's ~3.2s budget
exhausts long before that, so `flag()` throws `PessimisticLockingFailureException` before the holder
is ever released. Each test fails on a different assertion than the one it was written for.

A third, `concurrentDuplicateFlagFromSameFlagger_loserGetsAlreadyFlaggedViaRealConstraintViolation`
(`:466`), now exercises the retry path for the first time and must be re-verified even though it
does not call the helper.

Also consequential and unmentioned:

- After Fix 2, `awaitBlockingLockWaiter` has **zero** callers — dead code, along with its `pg_locks`
  rationale comment (`ConcurrencyLockWaitSupport:80-98`).
- `ConcurrencyLockWaitSupport`'s class javadoc (`:20-23`) states as fact that
  *"`CoachReviewRepository.findByIdForUpdate` carries no `@QueryHints` — a genuine blocking
  `FOR UPDATE`"*. Fix 2 makes that false; the javadoc is the canonical explanation of *why two
  signals exist* and must be rewritten, not just the call sites.

---

### H4 — Fix 3 misses the live billing-divergence path, and the sweep as designed would permanently mask it.

The story's Context traces the stale-tier gap only through `subscribeCoach` →
`persistCoachSubscription`'s Decision-3 catch (`SubscriptionService.java:178-182`). It never looks
at the **other** upgrade path:

```java
// changeCoachTier, SubscriptionService.java:216 — outside any transaction
stripeClient.updateSubscriptionTier(sub.getStripeSubscriptionId(), newPriceId);
...
self.persistCoachTierUpgrade(coachId, sub, newTier);

// persistCoachTierUpgrade, :230-237 — NO catch
sub.setTier(newTier);
paymentCoachSubscriptionRepository.save(sub);
syncMarketplaceTier(coachId, newTier);   // ← PessimisticLockingFailureException propagates
```

Lock exhaustion here rolls back the **whole** method, leaving **Stripe billing at the new tier while
`payment.coach_subscriptions.tier` stays at the old one**. This is strictly worse than a stale
projection: it is a payment/entitlement divergence, and it is the surviving instance of exactly the
hazard Decision 3 was written to prevent on the sibling path.

Fix 3's sweep treats `payment.coach_subscriptions` as the source of truth. After such a rollback it
would "reconcile" `marketplace` down to the **old** tier and log `COACH_TIER_RECONCILED` as though
it had corrected something — permanently hiding the divergence behind a green signal.

**Also:** the ledger's still-open residual (`deferred-work.md:3328-3345`, restated at `:3502`)
explicitly calls for *"a compensating action or a reconciliation sweep for **Stripe subscriptions
with no local row**"* — a **Stripe → payment** reconciliation. Fix 3 is a **payment → marketplace**
reconciliation. They are different sweeps. AC5's blanket *"Mark every item this story resolves
(Fixes 2-12) `[CLOSED by …]`"* risks falsely closing a real billing-integrity item. That residual
must be named as still-open alongside Fix 1 and the `BoundedKey` default.

---

## MEDIUM

### M1 — Fix 6's reproduction protocol cannot reproduce anything, because the fix it is trying to validate is already installed.

`quiesceAsyncExecutors` is already wired at `DatabaseResetTestExecutionListener:113` and runs before
**every** `beforeTestMethod` reset (impl `:210-226`). The story's steps 1-2 say to add diagnostic
logging and run `platform.development.**` 20-30 times "watching for any overlap between a logged
async task still running and the next test class's reset firing". That overlap is precisely what the
shipped code now drains. Step 3 then says *"If reproduced: confirm `quiesceAsyncExecutors` actually
closes the observed window"* — which is circular. The near-certain outcome is a "not reproduced"
result for a methodological reason, recorded in AC5 as if it were an empirical one.

**Required change.** Step 1 must be "temporarily disable/short-circuit `quiesceAsyncExecutors`",
with reinstatement as an explicit step.

Two related points:

- The shipped quiesce catches `ConditionTimeoutException` and **proceeds with the reset anyway**
  (`:218-224`, with its own javadoc arguing this is the lesser evil). So the window is *not* closed
  for an in-flight task lasting >10s. The story's *"it should, since it drains before the reset
  transaction opens"* overstates what was shipped.
- 20-30 consecutive local runs of a 231-test Testcontainers package is in direct tension with Task
  15's own *"No `mvn verify` run locally per this project's standing convention
  (`docs/validation-strategy.md`) — GitHub CI is the sole full-verification gate."* If the
  investigation is to run locally, say so and justify the exception explicitly.

### M2 — Fix 1's reachability argument cites a guard that does not exist.

The story says: *"A coach cannot review their own profile (**existing guard elsewhere in the reviews
module**) … Confirmed still true at HEAD"*, and Task 2 instructs the dev to *"confirm the self-review
guard this fix's reachability argument depends on is still in place"*.

There is no such guard. `ReviewErrorCode` (full enum read) has no self-review code, and
`ReviewSubmissionService.submitReview` (`:41-86`) never compares `authorId` against the target
coach's `userId`. What actually prevents it is incidental and lives in two other layers:

- `AuthorRole` (`platform/reviews/contract/AuthorRole.java`) contains only `PARENT, PLAYER`;
- `ReviewResource.resolveRole` (`:142-146`) returns `"COACH"` **first** for anyone holding
  `ROLE_COACH`, so `AuthorRole.valueOf(...)` throws `AUTHOR_ROLE_NOT_ALLOWED` (`:52-59`).

The dev will hunt for something that isn't there. Worse, the real protection is a contract-enum
membership plus a role-precedence ordering in an API class — a future story adding `COACH` to
`AuthorRole` would make the deadlock reachable with nothing to flag it.

**The conclusion (unreachable) is still correct, and for a stronger reason the story misses.** The
two lock sets are disjoint by construction for *both* account shapes, not merely "not the same row
pair":

- `erase(u)` takes the `coach_profiles` lock only inside `ifPresent` (`:168`), so a non-coach `u`
  takes **no** `coach_profiles` lock at all — no cycle possible regardless of what they authored.
- If `u` *is* a coach, `u` can author **zero** reviews (the role guard above), so
  `deleteNonApprovedByAuthorId`/`anonymiseApprovedReviews` (`:182-183`) match zero rows and take no
  row locks — no cycle possible either.

Rewriting Fix 1 around this is both more accurate and more durable than the current wording.

### M3 — Fix 12's statement about existing coverage is wrong, and points the dev at the wrong test class.

The story says *"Existing tests (`GdprErasureServiceTest.java`, `GdprErasureIT.java`) cover only
two"*.

`GdprErasureServiceTest.java` is 153 lines and contains **exactly one test** —
`deletePlayerDevelopmentData_readsLockTimeoutConfigWithTheDocumentedBoundsLiterally` — a
`verify(configService).getBoundedLong(...)` bounds-literal pin. It covers **zero** branch×reason
combinations and has nothing for the dev to "mirror".

Both covered combinations live in `GdprErasureIT`:

| Branch | Reason | Test | Assertion |
|---|---|---|---|
| PARENT (`eraseParentChildren`) | `CHILD_CONTENDED` | `erase_parentUser_contendedChild_skipsOthersProcessed_marksRequestFailed` | `:1146` |
| PLAYER (`erase`) | `CHILD_DELETE_LOCK_TIMEOUT` | `erase_selfRegisteredPlayer_downstreamDeleteStatementLockTimeout_boundedNotHanging_marksFailedWithDistinguishedAlert` | `:1275` |

Missing: **PARENT × `CHILD_DELETE_LOCK_TIMEOUT`** and **PLAYER × `CHILD_CONTENDED`**. Both belong in
`GdprErasureIT`. Note the cost: each of the two existing tests holds a raw lock for >4s with 20s
futures, so two more roughly double that file's contribution to suite time — worth acknowledging.

(The four combinations themselves are real and correctly identified: constants at `:129-130`,
PARENT catch at `:427-437`, PLAYER catch at `:237-260`.)

### M4 — Fix 10's fail-fast claim and its "no new test needed" claim are both wrong.

- **Fail-fast.** The story says the raw literal makes the key *"invisible to `ConfigStartupAssertion`'s
  `failFast` handling for `REVIEWS_SUBMISSION_WINDOW_DAYS`"*. `ConfigStartupAssertion` iterates
  `ConfigBounds.ALL` and looks each key up **by string** (`:83-85`), then applies `failFast` on
  out-of-range / non-numeric / absent-and-not-in-`HAS_CODE_DEFAULT`. The call site's literal-vs-constant
  form is irrelevant to it — `reviews.submissionWindowDays` **is** already boot-protected today. The
  genuine, narrower value of Fix 10 is future typo-drift protection, which the story does also state
  correctly one sentence later; the stronger framing should go.
- **Tests.** *"`ConfigBoundsEnumCoverageTest` and `ConfigStartupAssertionTest` … already exercise
  these keys once they're referenced by constant instead of literal"* is false.
  `ConfigBoundsEnumCoverageTest` only validates the registry's internal consistency (enum coverage,
  `min ≤ max`, non-blank note, no duplicate keys, `HAS_CODE_DEFAULT` membership). It never scans call
  sites, for any key. Referencing the constant changes nothing it asserts. The only new guard is the
  parenthetical per-service assertion, and the story should specify its shape:
  `verify(configService).getBoundedInt(eq(ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key()), eq(3), eq(1), eq(1000))`,
  mirroring `GdprErasureServiceTest`'s existing pattern.
- **Missing precedent, in the story's favour.** Fix 10's decision to fix only the key strings and
  leave the `1, 365` / `1, 1000` bounds as literals is *correct*, and there is an explicit precedent
  for it that the story doesn't cite: `GdprErasureServiceTest`'s javadoc (`:53-65`) documents that
  re-typing bounds as literals is the intentional convention, so a `Mockito verify(...)` can pin the
  numbers as a drift detector. Citing that would pre-empt a reviewer asking why the bounds weren't
  also switched.

### M5 — Fix 9 reduces the frequency of the hazard it describes but does not address the hazard; and it misses a call site.

The Context builds a real concern: a `ConfigService.refreshCache()` (`private synchronized`, doing
`configRepository.findAll()`) can fire while this thread holds the `player_profiles` lock and a
pooled connection, stalling every other `ConfigService` caller in the JVM. Hoisting the read makes
that happen **once instead of N times** — the hazard itself is unchanged in kind, only in
probability. The fix is fine; the Context should not imply the risk is closed.

Two concrete gaps:

- `deletePlayerDevelopmentData` has **two** call sites: `:415` (PARENT loop) and `:215` (PLAYER
  branch inside `erase`). The signature change touches both; the story mentions only the loop. For
  the PLAYER branch — *"the most common account shape"* per the method's own javadoc (`:622-625`) —
  the fix is a **no-op**, since that path already calls it exactly once. Worth saying, so the benefit
  isn't overstated.
- The proposed test — *"assert `configService.getBoundedLong` is invoked **exactly once** per
  `erase()` call **regardless of child count** (… with 0, 1, and 3+ children)"* — is only satisfiable
  if the read is hoisted **unconditionally**, which changes the empty-children case from 0 reads to
  1. If the read is instead placed after an emptiness check, the "regardless of child count"
  assertion is wrong. Pick one and state it. (Feasibility is fine: `configService.getBoundedLong` is
  called at exactly one place in this file, `:630`, so the invocation count is unambiguous.)

---

## LOW

- **L1 — Fix 2, step 3 line reference.** *"the insert at `:70-81`-equivalent post-Fix-5-restructure
  lines"* — the flag insert is at `ReviewFlagService.java:96-102`. `:70-81` is the
  self-flag/own-profile guard block. Confusing for a step whose whole point is "don't move the
  writes".
- **L2 — Fix 5 / Task 3 call-site arithmetic.** *"35 if Fix 5's overload approach also adds a site"*
  is incoherent: an overload adds no `.withBoundedRetry(` **occurrence**; only a new *call site*
  moves the count. Verified today: 34 raw occurrences in `src/main/java`, minus one inside a comment
  (`PlayerProfileRepository.java:59`) that the scanner blanks = **33**, matching
  `EXPECTED_CALL_SITE_COUNT` at `:103`. So Fix 2's 33 → 34 is right and Fix 5 never changes it —
  unless B1's resolution wraps the other five sites, in which case it is 39.
- **L3 — Fix 5 metric consistency.** The story tags only `persistence.lock_retry.retries` (`:184`).
  `persistence.lock_retry.exhausted` (`:159`) and the `persistence.lock_retry` timer (`:189`) stay
  untagged — state that as deliberate or tag them too. Note the hard constraint this codebase already
  documents (`ConfigStartupAssertion:310-315`): `PrometheusMeterRegistry` **rejects** a second
  registration of the same meter name with a different tag-key set, so every registration path must
  carry the `lock` tag. Also, `ConcurrencyLockWaitSupport.currentLockRetryCount` uses
  `meterRegistry.find(name).counter()`, which returns an arbitrary match once several tagged counters
  exist — the helper's new `lockName` parameter is load-bearing, not cosmetic.
- **L4 — Fix 8 leaves dead code.** After the projection replaces the only caller,
  `PerformanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc` (`:15`) has **zero** callers
  (verified across `src/main` and `src/test`), and its explanatory comment at `:13-14` ("Used by GDPR
  erasure… callers there must null-check `getStorageKey()`") becomes stale. Say whether to delete it.
  Otherwise Fix 8 is accurate — `storageKey` and `playerId` both exist (`PerformanceReport.java:32,
  :40`) and the `ORDER BY` genuinely is not load-bearing.
- **L5 — Fix 3 misses a sibling convention and an existing precedent.** `SubscriptionSchedulerLockTest`
  carries one `@SchedulerLock` reflection test **per scheduler**; a third scheduler needs a third
  (the story names only `SubscriptionSchedulerIsolationTest`). Separately,
  `PaymentCoachSubscriptionRepository.countActiveByTier` (`:19`) already encodes the exact
  `('ACTIVE', 'TRIALLING')` set the new query needs — cite it so the two can't drift. (The spelling
  `TRIALLING` in the story **is** correct — `SubscriptionService.normalizeStripeStatus:798`.)
- **L6 — Fix 3's missing-profile note under-weights repetition.** A coach with a payment subscription
  but no `coach_profiles` row is skipped per-row — but logs an ERROR on **every** run, forever, with
  no path to resolution. A one-shot alert or a distinguishing log level beats a permanent daily
  `log.error`.
- **L7 — Fix 11 understates implementation cost.** The existing test has **no method-boundary
  parsing** — it works on file offsets plus balanced-paren matching from `.withBoundedRetry(`
  (`:142-198`). "Does this method's source contain both tokens" requires adding exactly the machinery
  the story says not to build. A file-scoped check is the genuinely cheap option and should be named
  as such, with its false-negative rate acknowledged.

---

## What the story got right (verified, worth preserving)

- **Fix 5's premise is exactly correct.** `recordRetries` (`PessimisticLockRetryer:182-186`)
  increments a single untagged counter at `:184`, and `ConcurrencyLockWaitSupport
  .assertGenuineLockRetryOccurred` (`:119-124`) polls that same counter for growth past a baseline —
  which any unrelated in-flight retry in the JVM satisfies. Both concurrency ITs that use it
  (`CoachProfileServiceConcurrencyIT:153,215`, `SubscriptionServiceConcurrencyIT:111,171`) already
  `@Autowired MeterRegistry`, so the parameter change is mechanical.
- **Fix 10's scope correction is right and cheaper than the ledger implied.**
  `REVIEWS_SUBMISSION_WINDOW_DAYS` (`ConfigBounds.java:163-166`) and
  `REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` (`:168-171`) do already exist, are registered in
  `HAS_CODE_DEFAULT` (`:343-344`) and `ALL` (`:373-374`), and their bounds `[1,365]` / `[1,1000]`
  and defaults `14` / `3` match both call sites exactly. The ledger's *"add `BoundedKey`
  constants"* framing was indeed stale.
- **Fix 8 is sound**, modulo L4.
- **Fix 12's four-combination analysis is correct** (constants `:129-130`; PLAYER catch `:237-260`;
  PARENT catch `:427-437`), modulo M3's coverage bookkeeping.
- **Fix 6's structural citations are all accurate**: `quiesceAsyncExecutors` at `:210`,
  `RadarCompositeCalculationService:82`, `ReportGenerationService:201`, both on the `reportExecutor`
  bean (`DevelopmentConfig:105-106`).
- Refusing to re-litigate the deferred-126/127 accepted-risk items and the deploy-blocked
  deferred-123/125 items is correct — spot-checked against the ledger's own markers.
- Leaving the Instancio convention sweep out is the right call.

---

## Citation audit

Every line citation in the story was opened. Accurate (exact or ±2 lines):

`ReviewFlagService.java:92, :127, :135` · `CoachReviewRepository.java:23-25` ·
`ReviewSubmissionService.java:31, :69-70, :180` · `SubscriptionService.java:168-182, :473-497,
:536-560, :718-736` · `GdprErasureService.java:129-130, :135-138, :168, :182-183, :415, :628,
:630-631, :664, :665-668, :671` · `PessimisticLockRetryer.java:182-186, :184` ·
`ConfigBounds.java:343-344, :373-374` · `PessimisticLockRetryerCallSiteAuditTest` count `33` at
`:103` · `DatabaseResetTestExecutionListener:210` · `RadarCompositeCalculationService:82` ·
`ReportGenerationService:201` · `application.yaml` pool `25` / timeout `30000` (actual `:178, :181`;
story cites `:172-191` — range correct, values correct).

Minor drift, harmless: `ConfigBounds.java:164-171` (actual `:163-166` + `:168-171`);
`PessimisticLockRetryerCallSiteAuditTest.java:20-59` (actual javadoc `:19-65`);
`ReviewSubmissionService` `submitReview` `:41-85` (actual `:41-86`).

Naming nit: the story writes *"`SubscriptionGracePeriodChecker`'s `checkPastDueGracePeriod`"* — the
scheduler method is `checkGracePeriods()`; `checkPastDueGracePeriod` is the `SubscriptionService`
method it delegates to (which is what the `:536-560` citation points at, so the citation is right
and only the prose is loose).

**Overall:** citation quality is high. The failures are not citation failures — they are failures to
ask *"who else calls this?"* (B1, B3, H2, M5) and *"what type is on the other side of this
comparison?"* (B2).

---

## Minimum changes to make this story implementable

1. **Fix 2** — re-scope to a dedicated `findByIdForUpdateNoWait` method used only by `flag()`, or
   wrap all six sites and set `EXPECTED_CALL_SITE_COUNT = 39`. Name both broken ITs and the
   `ConcurrencyLockWaitSupport` javadoc/dead-helper cleanup in the test plan.
2. **Fix 3** — correct the enum/String comparison; guard `CoachSubscriptionTier.valueOf`; add a
   `SubscriptionSchedulerLockTest` case; add `persistCoachTierUpgrade`'s missing catch (or carve it
   out explicitly as still-open); reuse `countActiveByTier`'s status set.
3. **Fix 11** — restrict the assertion to NOWAIT repositories, with an explicit exemption list for
   the four blocking ones.
4. **Fix 4** — drop the cross-thread `Future` mechanism; re-cost option (a) against the absence of
   `@EnableJpaRepositories`; add the AFTER_COMMIT-on-request-thread fact to the Context.
5. **Fix 7** — extend to `ReviewFlagService.flag()`; state the outer-rollback semantic change and
   the same-thread self-deadlock constraint on the new test.
6. **Fix 6** — add "temporarily disable `quiesceAsyncExecutors`" as step 1 and reinstatement as the
   last step; reconcile the 20-30 local runs against the no-local-verify convention.
7. **Fix 1** — replace the non-existent self-review guard with the disjoint-lock-sets argument
   (`:168` is `ifPresent`; a coach can author zero reviews); fix Task 2 accordingly.
8. **Fixes 9, 10, 12** — correct the coverage/fail-fast/call-site statements per M3-M5.
9. **AC5** — add the ledger's Stripe→payment reconciliation residual (`deferred-work.md:3328-3345`)
   to the explicitly-still-open list, alongside Fix 1 and the `BoundedKey` default.
