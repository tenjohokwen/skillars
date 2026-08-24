# Story Review: skillars-deferred-62

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-62-postgres-lock-timeout-bounded-wait-fix.md`

Status: `ready-for-dev`, no branch or implementation exists yet — this is a pre-implementation audit of the
story spec itself, not a diff review.

Method: every factual/technical claim was independently re-verified against the actual source tree and, for
the core Hibernate/Postgres claim, against the actual `hibernate-core-6.6.53.Final.jar` bytecode (not just
trusted from the story's prose). This included: decompiling `PostgreSQLDialect.withTimeout(String, int)` and
`supportsWait()` via `javap`, reading all four repositories' current annotations/comments in full, grepping
every `findByIdForUpdate` call site in the codebase to cross-check the AC2 enumeration for completeness, and
locating the exact `deferred-work.md` section the story cites as its origin.

## Findings

### 1. "14 call sites across 9 services" undercounts the story's own enumerated list — actual count is 16 across 10 (Medium)

Both the "Why this story exists" narrative ("this fix touches 14 call sites across 9 services") and AC2's
preamble ("wraps every one of the following 14 call sites") give this count — but AC2's own bullet list
enumerates **16** distinct `file:line` sites, not 14:

- `CoachProfileRepository` callers (7): `BookingBatchService.java:378`, `BookingDuplicationService.java:63`,
  `BookingService.java:233`, `BookingService.java:323`, `CoachProfileService.java:239`,
  `AdminCoachEnforcementService.java:105`, `RescheduleService.java:208`
- `BookingRescheduleRequestRepository` callers (2): `RescheduleService.java:192`, `RescheduleService.java:278`
- `BookingRepository` callers (3): `BookingService.java:637`, `BookingPaymentPersistenceService.java:75`,
  `PaymentPendingSweeper.java:139`
- `SessionPackPurchaseRepository` callers (4): `SessionPackPaymentService.java:104`,
  `PackSessionService.java:53`, `PackSessionService.java:73`, `PackSessionService.java:112`

7+2+3+4 = 16, spanning 10 distinct service classes (`BookingBatchService`, `BookingDuplicationService`,
`BookingService`, `CoachProfileService`, `AdminCoachEnforcementService`, `RescheduleService`,
`BookingPaymentPersistenceService`, `PaymentPendingSweeper`, `SessionPackPaymentService`,
`PackSessionService`), not 9. A repo-wide grep for `findByIdForUpdate` confirms every other call site in the
codebase belongs to `VideoQuotaRepository`, `MessageRepository`, or `CoachReviewRepository` — all three
explicitly and correctly named as out-of-scope by the story's own "Scope discipline" Dev Note — so the
enumerated 16 is itself complete and correct; only the summary count ("14"/"9") that describes it is wrong.
Low functional risk since every site is explicitly named (a dev wrapping "every listed site" doesn't need
the count to be right), but worth fixing before implementation, since Task 4.2 asks for a completeness grep
and a dev sanity-checking "do I have 14" against a correct 16-site sweep would get a confusing mismatch.

### 2. "Why this story exists" and AC5 cite the wrong `deferred-work.md` section name (Low-Medium)

Both cite `## Deferred from: code review of skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes
(2026-08-14)` as where the `jakarta.persistence.lock.timeout` diagnosis lives. That section exists
(`deferred-work.md:1300`) but contains two unrelated bullets (a CI `concurrency`-group ordering gap and a
`PlaybackServiceIT` latency-assertion pattern) — no mention of lock timeouts. The actual diagnosis is one
section earlier, under the near-identically-named but distinct header `## Deferred from:
skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)` (no "code review of"
prefix, `deferred-work.md:1296`) — the story-creation-pass section for the same story, not its code-review
pass. AC5's assumption that "it is the section's only bullet" is actually true, just of the un-cited section
(the correct section has exactly one bullet; the cited one has two, neither of which is this item). This
doesn't block AC1-AC4 — the full diagnosis text is quoted inline in the story itself, so implementation
doesn't depend on the citation — but AC5's literal instruction ("flip the ... section's ... item") would send
a dev searching by section name to the wrong, unrelated section, and the References section's invitation to
"confirm context" has the same problem.

### 3. AC4 overclaims "every one of the four repositories" has a comment to correct (Low)

Direct read of all four repositories: `CoachProfileRepository.java:27-29` and
`BookingRescheduleRequestRepository.java:26-27`/`BookingRepository.java:188-189` (the two AC4 explicitly
cites) all carry a "bounded lock wait" claim in their comments. `SessionPackPurchaseRepository.java`, however,
has **no comment at all** above its `findByIdForUpdate` — just the bare annotations. So only 3 of the 4
repositories actually have a comment making the false claim, not 4, and AC4's explicit line citations miss
one of those 3 (`CoachProfileRepository`'s own comment, which makes the identical claim and needs the same
correction as its two cited siblings). Low risk — a dev fixing "every repository's misleading comment"
would very likely find `CoachProfileRepository`'s copy on their own since it's right next to the code AC1
already has them editing — but the AC's citation list and repository count are both slightly off.

## Verified as accurate (no finding)

- **Core technical claim, bytecode-verified**: decompiled `PostgreSQLDialect.withTimeout(String, int)` from
  the actual `hibernate-core-6.6.53.Final.jar` in this project's local `.m2` — its `lookupswitch` only
  special-cases `-2` (`SKIP_LOCKED`) and `0` (`NO_WAIT`); any other value (including `"5000"`) falls to
  `default`, which returns the lock string unchanged. `supportsWait()` compiles to an unconditional
  `iconst_0`/`return false`. The story's diagnosis is exactly right, not just plausible.
- **All four repositories**: `@Lock(PESSIMISTIC_WRITE)` + the ineffective `@QueryHint` at the exact line
  ranges cited (`CoachProfileRepository.java:28-34`, `BookingRescheduleRequestRepository.java:23-31`,
  `BookingRepository.java:188-193`, `SessionPackPurchaseRepository.java:18-21`).
- **`ApiAdvice.pessimisticLockExceptionHandler`** (`:578-583`) matches exactly, including the doc comment
  AC4 flags as needing correction (`:575-577`).
- **`RescheduleService.acceptReschedule`'s coach lock** is exactly at `:208-215` as Task 1.2 cites
  (`findByIdForUpdate` at `:208`, `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` at `:215`),
  confirming Task 1.2's chosen representative call site is accurate.
- **The `entityManager.refresh(...)` trap** is real and consistently commented at every cited location —
  `RescheduleService.java:190-192` and `:210-214`, `BookingService.java:235`, `MessagingService.java:320` —
  all describe the same "JPQL query returns a stale managed instance without the refresh" mechanism.
- **Test file existence claims (Task 4.2)**, all confirmed: `BookingServiceTest`, `RescheduleServiceTest`,
  `BookingDuplicationServiceTest`, `BookingBatchServiceTest`, `SessionPackPaymentServiceTest`,
  `PaymentPendingSweeperTest`, `CaptureReservationTest`/`CaptureReservationIT`,
  `PackSessionServiceParityTest`/`PackSessionServicePauseTest`, `CoachProfileBuilderIT` all exist;
  `PackSessionServiceTest`, `CoachProfileServiceTest`, `BookingPaymentPersistenceServiceTest` do not
  (correctly predicted as split/absent). `AdminCoachEnforcementService` has no dedicated `*ServiceTest` —
  the story correctly hedges this as "confirm during implementation" rather than asserting untested; it
  actually does have IT coverage via `CoachSuspensionIT.java`, which the story didn't claim either way.
- **Concurrency IT precedents**: `BookingServiceConcurrencyIT`, `SessionPackPurchaseLockContentionIT`, and
  `PackExtensionIT#extendPack_concurrentRequests_noDuplicateExtension` all exist exactly as named;
  `RescheduleResourceIT` has a decline-race test exercising `declineReschedule`'s own lock, matching the
  "reschedule-decline race" description.
- **No premature implementation**: no branch, no commit, and no code changes exist anywhere in git history
  for this story — confirmed via branch and log search — consistent with its `ready-for-dev` status.

## Summary

The story's central technical premise — that `jakarta.persistence.lock.timeout` is silently ineffective on
Postgres under this Hibernate version — is not just plausible but bytecode-confirmed exactly as described,
and the proposed fix shape (`NO_WAIT` + a shared retry/backoff helper, verified by a real concurrency IT
before trusting the fix) is sound and appropriately cautious about the behavior-change risk (the Dev Notes
correctly flag that `NO_WAIT` without retry would make the system more fragile, not less). All four
repositories, the `ApiAdvice` handler, the representative call sites, the refresh-trap mechanism, and the
test-file landscape all check out exactly as claimed. Three findings, none blocking: two are counting/citation
errors (14-vs-16 call sites and 9-vs-10 services; a `deferred-work.md` section citation pointing to the wrong
of two similarly-named sections) that don't threaten correctness since the underlying lists/content are
themselves accurate, and one is an AC4 scope-count overclaim (comment exists on 3 of 4 repos, not 4, and one
of the 3 isn't explicitly cited). Recommend fixing all three before or during implementation — cheap, and
Finding 2 in particular would otherwise cost a dev real time hunting the wrong section for AC5.
