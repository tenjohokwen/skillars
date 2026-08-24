# Story Review: Deferred-58 — Coach-Availability Write-Lock Consistency & Pack-Deduction Failure-Record Transactional Safety

Senior-dev audit of `skillars-deferred-58-coach-availability-write-lock-consistency-and-pack-deduction-failure-record-transactional-safety.md`
(status `ready-for-dev`) against live code, before implementation. Every claim below was independently
re-verified against the actual production/test files this story cites — nothing here is taken on the
story's word alone. The story's core diagnosis for both AC1 and AC2 is correct and its proposed
production-code changes are sound; every substantive finding below is in the **test-design guidance**,
not the fix itself.

---

## Finding 1 (High) — AC1's suggested test technique ("hold a conflicting lock") cannot produce the failure it's meant to prove; this exact codebase already documents why, and a much simpler proven alternative sits one file away

**What's wrong:** AC1's Test Coverage section instructs: *"have the test's transaction hold a conflicting
lock... to force a real, non-mocked persistence failure from `deductSession`'s `.save(purchase)`
(`PackSessionService.java:59`)"* and points at `BookingServiceConcurrencyIT`'s lock-contention pattern as
the model to reuse.

`SessionPackPurchaseLockContentionIT` — the class that already investigated exactly this lock
(`SessionPackPurchaseRepository.findByIdForUpdate`, the same `@Lock(PESSIMISTIC_WRITE)` +
`jakarta.persistence.lock.timeout` shape `deductSession` uses) — documents in its own class Javadoc that
this technique cannot work at all:

> *"Investigation during this story found that Hibernate's `PostgreSQLDialect` only special-cases the
> `NO_WAIT`/`SKIP_LOCKED` sentinels in `withTimeout(...)` — any finite `jakarta.persistence.lock.timeout`
> value, including this repository's and its three siblings', falls through unchanged and has no effect
> on Postgres. Confirmed empirically: a contended `findByIdForUpdate` call blocked for the full duration a
> competing lock was held (tested up to 12s) and then completed normally, with no
> `PessimisticLockingFailureException` ever raised."*

So "hold a conflicting lock" against `deductSession`'s `findByIdForUpdate` doesn't throw a
`PessimisticLockingFailureException` (or any exception) at all — the calling thread just **blocks until
the competing transaction releases the lock**, then proceeds normally with no persistence failure to
observe. Even setting that aside, the technique's own stated target — a failure "from `.save(purchase)`
at line 59" — was never reachable this way in the first place: a lock held on the row would block *before*
`.save()`, at the earlier locked `SELECT` (line 53), not at the save call the AC names.

**A working alternative already exists in this exact test suite.** `CaptureReservationIT` — the class this
story itself half-suggests reusing ("check for an existing IT class covering
`handlePackBasedBooking`'s... path first") — already uses `@MockitoSpyBean PaymentGateway paymentGateway`
to spy on a real Spring bean inside a real transactional flow and force a genuine exception with
`doThrow(...)`. Applying the identical, already-proven pattern to
`SessionPackPurchaseRepository`/`PackSessionService` (`@MockitoSpyBean SessionPackPurchaseRepository
sessionPackPurchaseRepository; ... doThrow(new DataIntegrityViolationException("simulated")).when(spy)
.save(any());`) would deterministically force a real `DataAccessException` at exactly the `.save(purchase)`
call the AC names, inside the real `AFTER_COMMIT`/`REQUIRES_NEW` flow, with zero threads, zero timing
races, and zero dependency on a lock-timeout mechanism this codebase has already proven does not exist on
Postgres.

**Confirmed while checking this: no existing IT class currently exercises `onBookingAccepted`'s pack-based
branch at all** — `CaptureReservationIT.acceptedEvent(bookingId)` builds every `BookingAcceptedEvent` with
`sessionPackPurchaseId = null` (verified against the constructor's actual parameter order in
`BookingAcceptedEvent.java:21-22`), so every existing test in that class routes through
`handleCreditBasedBooking`, never `handlePackBasedBooking`. The story's own "`PaymentLifecycleServiceIT`?
— check first" hedge was warranted: no such class exists (confirmed by search). `CaptureReservationIT` is
still the right home for the new test — it already carries the necessary Spring context and the
`@MockitoSpyBean` pattern to copy, and reusing it avoids standing up a second context (this project has an
enforced CI context-count ceiling — `CaptureReservationIT`'s own comment at `:391-393` notes a prior
context-ceiling trip from exactly this kind of avoidable duplication, deferred-19 AC3).

**Why it matters:** as written, a dev following AC1's Test Coverage guidance literally would either try to
force a lock-timeout that this codebase has already proven doesn't fire on Postgres (producing a hung/very
slow test, not a failing one), or spend real time rediscovering that fact from scratch before landing on
the spy-based approach — which was sitting in the very file the AC points at as a candidate host.

**Recommendation:** rewrite AC1's Test Coverage guidance to specify `@MockitoSpyBean` +
`doThrow(new DataIntegrityViolationException(...))` on `SessionPackPurchaseRepository.save(...)` (or on
`PackSessionService.deductSession` itself), added to `CaptureReservationIT`, mirroring its existing
`paymentGateway` spy exactly. Drop the lock-contention suggestion entirely — it is not a slower path to the
same proof, it is a path that does not reach the proof at all.

---

## Finding 2 (Medium) — AC2's suggested concurrency-test design has the lock-holder and lock-waiter roles backwards relative to how `awaitAnotherSessionBlockedOnCoachProfileLock` and its two existing usages actually work

**What's wrong:** Task 2.4 / AC2's Test Coverage section says: *"start a `saveStep4` call in one thread,
hold its transaction open past the lock acquisition (same latch-based staging those two existing tests
use)... reusing `awaitAnotherSessionBlockedOnCoachProfileLock`."* This describes `saveStep4` (the
production method under test) as the thread that **holds** the lock while pausing mid-transaction.

Read literally, this is not achievable. `awaitAnotherSessionBlockedOnCoachProfileLock` (`:407`) is a
**private test method** that polls `pg_locks` via `jdbcTemplate` against the *calling thread's own current
transaction* (`pg_current_xact_id()`). In both existing usages (`:245-278`, `:330-361`), it is called from
*inside a `transactionTemplate.execute(...)` block that the test itself opened* — a raw-SQL thread the
test fully controls, which takes a `SELECT ... FOR UPDATE` on `coach_profiles`, flips a column, then calls
this helper before committing. There is no way to call this private test-class method from inside
`CoachProfileService.saveStep4`'s own `@Transactional` execution — that would require adding a test-only
hook into production code, which nothing in the story proposes.

In both existing tests, the roles are the **opposite** of what AC2 describes: a raw-SQL thread is the lock
**holder** (and the one that calls the helper, from its own transaction), while the **real service method**
under test (`createBookingRequest`/`acceptBooking`) is the thread that blocks on the lock and is then
asserted to observe fresh, not stale, state once released.

**Why it matters:** a dev following this instruction literally has no way to implement it — `saveStep4`
cannot be made to pause mid-transaction and call a private test helper without modifying production code
to add a test seam that doesn't exist today. This would cost real implementation time before the dev
independently arrives at the actual working shape.

**Recommendation:** mirror the existing tests' actual structure, not the story's description of it: a
raw-SQL thread takes `SELECT ... FOR UPDATE` on the target coach's row (as both existing tests already do),
signals a latch, waits on `awaitAnotherSessionBlockedOnCoachProfileLock`, then commits; a second thread
calls the real `saveStep4(...)` (or `duplicateNextWeek(...)`) and is asserted to block until the first
thread's commit, then to observe/write correctly. Proving `saveStep4`'s new lock blocks against a raw
`SELECT ... FOR UPDATE` is sufficient evidence that it shares the same row lock the two existing readers
already prove they block against — Postgres row locks are symmetric regardless of which query path
acquires them, so this doesn't need saveStep4 and duplicateNextWeek to literally contend against each
other in the same test to establish that they now would.

---

## Finding 3 (Low) — Task 2.3 never states that `BookingDuplicationServiceTest` needs a new `@Mock EntityManager` field and a corresponding constructor-argument update, without which the file will not compile once AC2 lands

**What's wrong:** AC2 adds a new `private final EntityManager entityManager` field to
`BookingDuplicationService`, which (via this class's `@RequiredArgsConstructor`) adds a new 8th
constructor parameter. `BookingDuplicationServiceTest.setUp()` currently constructs the service with the
existing 7-argument constructor (confirmed: `new BookingDuplicationService(bookingService,
bookingRepository, coachProfileRepository, userRepository, packSessionService, eventPublisher,
coachAvailabilityWindowRepository)`, no `entityManager`). Task 2.3 only says to "stub
`coachProfileRepository.findByIdForUpdate(coach.getId())`" — it never says to add a `@Mock EntityManager
entityManager` field to the test class or to pass it into the constructor call. Without both, the test
file will not compile at all once AC2's field lands, regardless of any stubbing.

The one place this is even implied is an aside deep in AC2's Dev Notes, describing `RescheduleServiceTest`'s
*pre-existing* setup for context ("note `entityManager.refresh(...)` needs no stubbing... `RescheduleServiceTest`'s
own `@Mock EntityManager entityManager` field, injected into the constructor the same way, already relies
on Mockito's default no-op") — this explains an existing pattern rather than instructing the dev to
replicate it in the file actually being changed.

**Why it matters:** this is a compile error, not a subtle logic gap, so it will be caught immediately — but
the story's own Task list and Project Structure Notes (which do enumerate "gains a new `EntityManager`
field" for the two production files) omit the one test-file change that is strictly required, not optional,
for those production changes to compile against their existing unit test.

**Recommendation:** add an explicit Task 2.3 sub-step: add `@Mock private EntityManager entityManager;` to
`BookingDuplicationServiceTest` and add it as the 8th argument to the `new BookingDuplicationService(...)`
call in `setUp()`, mirroring `RescheduleServiceTest`'s identical field exactly.

---

## Minor wording nit, non-blocking

AC1's rationale says *"both catch branches in `PaymentLifecycleService.handlePackBasedBooking` (`:162-177`)
call this same method"* — `handlePackBasedBooking` has exactly **one** `catch (RuntimeException e)` clause,
not two; that single clause now absorbs both the pre-existing `PaymentGatewayException` case and the
newly-widened general-`RuntimeException` case (per `deferred-56` AC2's widening), rather than being two
separate branches. Doesn't affect the correctness of AC1's actual proposed fix (`REQUIRES_NEW` on
`persistPaymentFailure`), which is unconditional on how many catch clauses call it.

---

## Everything else independently re-verified as accurate, no changes needed

**AC1 (transactional safety):**
- `persistPaymentFailure`'s current `@Transactional` (default `REQUIRED`) at `BookingPaymentPersistenceService.java:206-207`
  confirmed exactly as cited, as are its two sibling `REQUIRES_NEW` methods at `:72` (`reserveCapture`) and
  `:279` (`declineBatchBooking`) — `Propagation` is already imported for this file, no new import needed.
- `PackSessionService.deductSession` (`:51-61`) confirmed plain `@Transactional` (`REQUIRED`), and
  `PaymentLifecycleService.onBookingAccepted` (`:138-139`) confirmed
  `@Transactional(propagation = Propagation.REQUIRES_NEW)` + `@TransactionalEventListener(AFTER_COMMIT)` —
  `deductSession` genuinely joins `onBookingAccepted`'s physical transaction rather than starting its own,
  exactly as claimed. Spring's rollback-only-on-participating-transaction mechanism, as described, is
  accurate.
- `handlePackBasedBooking` (`:162-181`) confirmed to call `persistenceService.persistPaymentFailure(...)`
  from its one catch clause; `handleCreditBasedBooking`'s separate `PaymentGatewayException` catch (`:213-218`)
  is the other of the two call sites named in the story's References section — both confirmed to call the
  same method AC1 fixes.
- `CreditRoutingTest`'s existing AC2 test (`packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal`)
  confirmed to use `@Mock BookingPaymentPersistenceService persistenceService` — correctly cannot observe
  real commit/rollback behavior, exactly as the story states.

**AC2 (write-lock consistency):**
- `RescheduleService.acceptReschedule`'s existing lock pattern confirmed exactly as cited:
  `findByIdForUpdate` at `:208`, `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` at `:215`, and the
  unlocked `coachAvailabilityWindowRepository.findByCoachId(coach.getId())` read at `:229`, one line after
  the lock is taken and released to program flow.
- `BookingDuplicationService.duplicateNextWeek` (`:42-88`) confirmed to never lock the coach row today —
  `findByUserId` at `:45` is a plain read, and both the availability check (`:67-68`) and overlap check
  (`:80-83`) run unlocked.
- `CoachProfileService.saveStep4` (`:225-245`) confirmed to have no lock at all before its
  `deleteByCoachId`+`saveAll` rewrite, and the class has no `EntityManager` field or import today — the
  story's proposed field addition is correctly scoped.
- `CoachProfileRepository.findByIdForUpdate` (`:28-34`) confirmed to exist exactly as cited:
  `@Lock(PESSIMISTIC_WRITE)` + a `jakarta.persistence.lock.timeout` query hint + the JPQL query — the same
  shape `SessionPackPurchaseRepository`'s sibling method uses (relevant to Finding 1 above).
- Lock-ordering checked across all three methods that will hold the coach-profile lock post-fix
  (`acceptReschedule`, `duplicateNextWeek`, `saveStep4`): `acceptReschedule` is the only one that takes a
  second lock (reschedule-request, always before coach — documented at `:181-184` and never taken in the
  reverse order anywhere), while `duplicateNextWeek` and `saveStep4` each take only the single coach-profile
  lock. No new deadlock cycle is introduced by this change.
- `BookingDuplicationServiceTest`'s 7 existing tests confirmed: exactly 5 of them
  (`..._createsNewRequestedBookingAdvancedBy7DaysAndCarriesOverPack`, `..._noCreditsAvailable_throws`,
  `..._slotOutsideAvailabilityWindow_throwsSlotOutsideAvailability`, `..._overlapsAnotherBooking_throwsSlotUnavailable`,
  `..._proposedTimePast_throws`) reach past the `"COMPLETED"` check and the story's proposed lock insertion
  point, and would need the new `findByIdForUpdate` stub; the other 2
  (`..._wrongCoach_throws403`, `..._notCompletedStatus_throws`) fail before reaching it and need no change —
  matching the story's "every existing test fixture that reaches past" framing exactly, including for the
  proposed-time-past test, whose fixture status remains `COMPLETED` even though its ultimate assertion is
  about the past-time check further down.
- `RescheduleServiceTest`'s `@Mock EntityManager entityManager` field (`:48`) and its
  `coachProfileRepository.findByIdForUpdate(coach.getId())` stubbing pattern confirmed present at exactly
  `:288`, `:324`, `:365` as cited.
- `CoachProfileBuilderIT` confirmed to exist (API-level IT, real Spring beans) — adding the new
  `EntityManager` constructor dependency to `CoachProfileService` needs no test-file change there, since
  Spring autowires the real bean; the story's claim of "no existing unit test to update" for this class is
  accurate.
- All three `deferred-work.md` `[PICKED UP by skillars-deferred-58 ACn]` tags confirmed present with wording
  matching the story's own description: the `story review of skillars-deferred-56` item (AC1), and the
  `code review of skillars-deferred-49` and `code review of skillars-deferred-50` items (both AC2).

**Considered-and-excluded items, spot-checked:** `DisputeService`'s dormant `FROZEN`-filter gap confirmed
still dormant (`BookingPaymentStatus.FROZEN` genuinely unwritten anywhere in `src/main/java`, per a fresh
grep); the video-bandwidth and DRY-duplication exclusions match their own cited source text.
