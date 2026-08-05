# Story Deferred-15: PAYMENT_PENDING Recovery Sweeper & Accept-Path Concurrency Integrity

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a platform operator,
I want bookings stranded in `PAYMENT_PENDING` to be swept to a terminal state by a scheduler where that is provably safe — and loudly reported where it is not — instead of holding a coach's slot forever in silence, the coach-accept and reschedule-accept paths to actually honour the coach lock they already take, a batch's final status to be computed from every booking in the batch rather than a stale subset, and the session-pack expiry-warning email to be delivered exactly once per pack instead of never,
so that a crashed settlement listener stops silently consuming coach availability without ever risking a parent being charged for a booking the platform then declined, a suspended coach cannot accept work through three unguarded paths, a batch cannot read `FULLY_ACCEPTED` when part of it was declined, and parents actually receive the expiry warning the product promises without being mailed fourteen times.

### Why this story exists

Five items from `deferred-work.md`, backend-only, **zero unshipped dependencies** — every repository method, transaction pattern, scheduler idiom and config mechanism this story needs already exists. Every item was re-verified against the current source on 2026-08-05 during story creation; file/line references come from direct reads of the tree at commit `cc8d2a8`, not from trusting the ledger.

| # | Source item | Verified current state (2026-08-05) |
|---|---|---|
| AC1–AC2 | `skillars-deferred-14 story creation` D1 (2026-08-05) | **CONFIRMED, and it is the headline.** Grepped all 27 `@Scheduled` methods in `src/main`: **none reads `PAYMENT_PENDING`**. `BookingExpiryScheduler.expireStaleRequests` (`:44-47`) queries `findRequestedBookingsOlderThan`, whose JPQL is `WHERE b.status = 'REQUESTED'` (`BookingRepository.java:61-64`) — `REQUESTED` only. `BookingStateMachine` (`:34-38`) offers `PAYMENT_PENDING → {PAYMENT_CAPTURED, PAYMENT_FAILED, CANCEL_PARENT}`; the only non-system escape is the parent's. `PAYMENT_PENDING` is in `ACTIVE_SLOT_STATUSES` (`BookingService.java:117-118`) and in `V87`'s `WHERE` clause, so a stranded row blocks that coach's slot indefinitely. |
| AC3 | `skillars-deferred-14` review D1 (2026-08-05) | **CONFIRMED.** `RescheduleService.acceptReschedule` reads `req.getStatus()` at `:108`, then blocks on `coachProfileRepository.findByIdForUpdate` at `:128`, then writes `req.setStatus("ACCEPTED")` at `:144` — never re-reading the row. `declineReschedule` (`:157-186`) takes **no lock at all**, neither on the coach nor on the reschedule row. Nothing serialises them. |
| AC4 | `skillars-deferred-14` review D2 (2026-08-05) | **CONFIRMED, and worse than filed.** The item says the three accept paths never *re-check* `SUSPENDED` under the lock. They never check it **at all**: `BookingService.acceptBooking` (`:269-296`), `BookingBatchService.acceptOneBooking` (`:263-283`) and `RescheduleService.acceptReschedule` (`:93-155`) each load the coach and verify **ownership only**. Additionally `AdminCoachEnforcementService.suspendCoach` (`:99-110`) writes the suspension with a plain `findById` — **it takes no lock**, so the lock the accept paths already hold serialises against nothing. |
| AC5 | `skillars-deferred-14` review D4 (2026-08-05) | **CONFIRMED.** `BookingBatchService.acceptAll` computes `acceptedIds.size() == requestedBookings.size()` (`:215-216`) over the `REQUESTED` subset captured at loop start, while `updateBatchStatusFromBooking` (`:286-317`) recomputes over **all** rows. Since Deferred-14's per-booking commits, `BookingBatchStatusListener` fires mid-loop, so the two writers now race and the naive formula wins last. |
| AC6 | `skillars-7-2` Group 2 D2 (2026-06-24) | **CONFIRMED — and story creation found the item understates it in one direction and overstates it in the other.** `SessionPackExpiryNotifier.notifyExpiringPacks` (`:32-65`) has **no dedupe guard**, so it re-selects the same pack every morning for up to 14 days. But it is also **not `@Transactional` and does not wrap its publish in a `TransactionTemplate`**, while `SessionPackEmailListener.onExpiryWarning` (`:37-38`) is a `@TransactionalEventListener(AFTER_COMMIT)` with default `fallbackExecution = false` — **so the event is discarded and the warning email is sent zero times today, not fourteen.** Contrast `SessionPackForfeitureScheduler.forfeitExpiredPacks` (`:34-61`), which publishes *inside* `transactionTemplate.execute` and therefore works. The two halves must ship together: fixing delivery alone converts silence into 14 emails per pack. |

### Corrections to `deferred-work.md` this story must record

- **`skillars-7-2` Group 2 D2's premise is incomplete.** It reads as "the notifier sends up to 14 emails" and prescribes a `last_warned_at` column. The column is right, but the current observable behaviour is **zero emails**, because the event never reaches its listener (no transaction bound). Whoever implements this must reproduce the non-delivery first (Task 8) — otherwise the "fix" ships a regression from silent to spammy.
- **`skillars-deferred-14` review D2's framing is narrower than the defect.** It describes a missing *re-check* after the lock; there is no check at any point on any of the three paths, and the suspension writer holds no matching lock. AC4 covers the whole shape.
- **The sweeper item's implied scope is not achievable and the ledger must say so.** `deferred-14` story-creation D1 reads as though a grace period is the only design question ("a sweeper would need a grace period well clear of Stripe's capture latency"). It is not. For credit-funded bookings, `chargeAndCapture` runs **before** the DB writes that record it, and nothing durable survives a crash in between — so no grace period, however long, makes an automated decline safe on that path. AC1 covers pack-funded bookings and reports the rest; the residual and the change that would close it are recorded as a new ledger item (AC7).
- **The V88 precedent that D2 (7-2) predates.** The item asks for a "`last_warned_at` column (V63+ migration)". Since then `V88__session_pack_purchases_parity.sql` added `expired_notified_at` plus a partial index `idx_session_pack_purchases_expiry_notify … WHERE expired_notified_at IS NULL` for exactly this purpose on the *expired* path. Mirror that shape rather than inventing one.

### Items examined and deliberately NOT included

Recording these so the next audit does not re-litigate them:

- **`skillars-deferred-14` review D3** (moderation `PENDING` guard cannot distinguish a stale in-flight Gemini verdict) — its own inline `[AUDIT 2026-08-05]` annotation already establishes the scenario is **unreachable today**: `ReviewSubmissionService.updateReview:82-86` rejects any edit within 365 days of the last one, so two `ReviewSubmittedEvent` deliveries cannot be in flight together. A design limitation, not a live defect. **Leave in the file.**
- **`skillars-10-2` D1** (`AFTER_COMMIT` listener failure silently drops refunds) — the same platform-wide event-reliability concern `deferred-13` and `deferred-14` both left alone, for the same reason. AC1 deliberately does **not** generalise into an outbox/retry mechanism; it recovers one specific stuck state. **Leave in the file.**
- **`skillars-8-2` D1/D2** (deleted player crashes `getConversations()` via `UserNotFoundException`). Re-verified during story creation and **downgraded**: `AgePolicyService.getMessagingPolicy` (`:52-54`) does throw `UserNotFoundException` when the profile row is absent, and `MessagingService.getConversations`'s PARENT filter (`:102-105`) has no guard — but **no code path in `src/main` deletes a `player_profiles` row.** `GdprErasureService` anonymises the `User` and deletes development data (`:83-84,180-190`) while leaving the profile; `UserAdminService.deleteUserInTransaction` (`:136-138`) deletes only never-activated `User` rows. The crash therefore needs a data-integrity failure, not an ordinary deletion. Still worth fixing, still messaging-module scope. **Leave in the file, but the reachability note above should be added to it** (AC7).
- **A generic settlement outbox / dead-letter queue.** AC1 is a targeted sweeper with a money-safety guard, not an event-delivery framework. Anything broader belongs with `skillars-10-2` D1.

## Acceptance Criteria

1. **A stranded `PAYMENT_PENDING` booking is swept to a terminal state only when a Stripe capture is provably impossible; everything else is reported, not swept.** A new `PaymentPendingSweeper` in `com.softropic.skillars.platform.payment.service` runs on `@Scheduled` + `@SchedulerLock` (mirroring `SessionPackForfeitureScheduler:31-33`) and, for each booking in `PAYMENT_PENDING` whose `updated_at` is older than a configurable grace period, decides between two outcomes **in its own transaction per booking**. Grace period comes from `booking.payment_pending_sweep_grace_minutes` via `configService.getBoundedLong(key, 120, 15, 10080)`, seeded by migration.

   **Auto-decline** — issue `BookingEvent.PAYMENT_FAILED` (→ `DECLINED`, already in the state machine at `BookingStateMachine.java:36`), write a `BookingPayment` row with `status = "CHARGE_FAILED"`, publish `BookingDeclinedEvent` — the same triple `BookingPaymentPersistenceService.persistPaymentFailure` (`:68-86`) performs. **Permitted only when both hold:**
   - `booking.sessionPackPurchaseId != null`. Pack-funded settlement never calls Stripe: `handlePackBasedBooking` (`:77-90`) is `deductSession` + `persistPaymentSuccess`, both plain `@Transactional` joining the listener's transaction, and `onBatchBookingAccepted` splits pack bookings out at `:133-141` so they are excluded from `chargeAndCaptureForBatch`'s subtotal entirely. A stranded pack booking therefore provably moved no money — the deduction rolled back with everything else.
   - no `payment.booking_payments` row exists for that booking (`bookingPaymentRepository.existsById`). All five writers of that table write the row and the status transition in one transaction, so `PAYMENT_PENDING` + an existing payment row is a data-integrity failure, not a stranded booking.

   **Report-only** — every other stranded booking is left in `PAYMENT_PENDING` and logged at **ERROR** with `bookingId`, `batchId`, `parentId`, `sessionPackPurchaseId`, `updatedAt` and which condition disqualified it. This is not a lesser outcome chosen for convenience; it is forced. A credit-funded booking that reaches `chargeAndCapture`/`chargeAndCaptureForBatch` and then strands has had **real money captured with no durable record of it** — verified: the gateway is not transactional, `PaymentGateway` exposes no capture-lookup method, `StripeWebhookService.handleEventAtomically` handles no `payment_intent.*`/`charge.*` event, and the only durable write the charge makes (`stripeCustomer.lastPaymentIntentId`, `StripePaymentGateway:67-70`) joins the caller's transaction *and* is per-parent, overwritten on every charge. There is no signal the sweeper can read. Declining such a booking would take a coach's slot back at the cost of charging a parent for nothing.

   The sweeper must **not** touch `ACCEPTED` (a transient in-transaction state, never committed as a resting state on either accept path), and must **not** invent new state-machine transitions. **Do not add a credit-ledger guard** — see Dev Notes for why the obvious one does not work.

2. **The sweeper is proven by integration test across all four cases, and the negative cases are what matter.** An IT drives a seeded booking through each: (a) pack-funded (`session_pack_purchase_id` set), `updated_at` older than the grace period, no payment row → swept to `DECLINED` with a `CHARGE_FAILED` payment row and one `BookingDeclinedEvent`; (b) pack-funded but *inside* the grace period → untouched, still `PAYMENT_PENDING`, no payment row; (c) pack-funded, past grace, **with** a `CAPTURED` payment row → untouched, ERROR logged; (d) **credit-funded** (`session_pack_purchase_id` null), past grace, no payment row → untouched, ERROR logged. Cases (c) and (d) are the reason this AC exists — a sweeper that declines a paid booking is worse than no sweeper, and (d) is the case where the money may already be at Stripe. Assert the *absence* of the transition and of any `booking_payments` write, not just the presence of a log line.

3. **Reschedule accept and decline are mutually exclusive.** `BookingRescheduleRequestRepository` gains `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)` + JPQL, copying `CoachProfileRepository:31-34`). **Both** `acceptReschedule` and `declineReschedule` load the reschedule row through it, and each re-verifies `"PENDING".equals(status)` **after** the lock is granted, throwing the existing `OperationNotAllowedException(..., SecurityError.MISSING_RIGHTS)` otherwise. In `acceptReschedule` the locked read must be taken **before** the coach lock at `:128` so the two methods cannot deadlock by acquiring the same pair in opposite orders — `declineReschedule` takes only the reschedule lock, so ordering is decided entirely by `acceptReschedule`. The existing pre-lock `PENDING` check at `:108` stays as a cheap early-out. Outcome: a decline committing while an accept waits on the lock makes the accept fail cleanly instead of silently overwriting it.

4. **A suspended coach cannot accept work, and the lock that guarantees it is actually shared.** After acquiring the coach lock, all three accept paths — `BookingService.acceptBooking` (`:283`), `BookingBatchService.acceptOneBooking` (`:264`) and `RescheduleService.acceptReschedule` (`:128`) — re-read the coach's status under that lock and throw `OperationNotAllowedException(..., BookingError.COACH_UNAVAILABLE)` when it is `SUSPENDED`, using the **exact** locked-re-read idiom already proven in `BookingService.createBookingRequest` (`:201-215`): `findByIdForUpdate(...).orElseThrow(...)` followed by `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)` before reading `getStatus()`. The `refresh` is not optional where the same row is already managed from an earlier `findByUserId`/`findById` in the same persistence context — without it the locked read returns the stale in-memory instance and the check can never fire (the reason that line exists in `createBookingRequest`; see Dev Notes for which of the three sites this applies to).

   **`AdminCoachEnforcementService.suspendCoach` (`:101-110`) is changed to acquire the same lock** — `findByIdForUpdate` in place of `findById`, keeping its existing already-`SUSPENDED` early return. Without this the accept-side lock serialises against nothing and AC4 is decorative. Only `SUSPENDED` is rejected; `DEACTIVATED`/`DRAFT` are **out of scope** — no path sets them on an active coach today and widening the check risks breaking fixtures that leave coaches in `PENDING_REVIEW`.

5. **A batch's final status counts every booking in the batch.** `BookingBatchService` gains a private `computeBatchStatus(List<Booking> allInBatch)` holding the single formula (`FULLY_ACCEPTED` when every booking is in `POST_ACCEPTANCE_STATUSES`, `DECLINED` when none is, else `PARTIALLY_ACCEPTED`). `updateBatchStatusFromBooking` (`:286`) keeps its `requestedCount > 0 → return` early-out and delegates to it; `acceptAll`'s trailing transaction (`:235-244`) **re-reads all bookings for the batch inside that transaction** and calls the same method, replacing `acceptedIds.size() == requestedBookings.size()`. A batch of three where one booking was declined individually before `acceptAll` ran, and the remaining two are accepted, must end `PARTIALLY_ACCEPTED`; today it ends `FULLY_ACCEPTED`. **Do not remove the early-out from `updateBatchStatusFromBooking`** and do not make it the sole writer — on a partial batch the failed bookings stay `REQUESTED`, so it early-returns every time and the batch would never leave `PENDING` (Deferred-14 Dev Notes rejected exactly this).

6. **The pack expiry-warning email is delivered, once per pack.** `SessionPackExpiryNotifier.notifyExpiringPacks` publishes each `SessionPackExpiryWarningEvent` **inside a `TransactionTemplate.execute`** that also stamps a new `expiry_warned_at` column, mirroring `SessionPackForfeitureScheduler:42-56` in both respects. `SessionPackPurchase` gains `expiryWarnedAt`; migration `V90` adds the nullable `TIMESTAMPTZ` and a partial index mirroring `V88`'s; `findExpiringWithinWindowAndSessionsRemaining` gains `AND p.expiryWarnedAt IS NULL`. Result: exactly one warning email per pack per expiry window, where today there are none. Failures are caught per pack and logged, exactly as the forfeiture scheduler does, so one bad pack cannot abort the run. A pack whose expiry is extended (`extendedAt`) is already excluded by the existing query predicate — do not add re-warning logic for extensions; that is new product behaviour, not a bug fix.

7. **`deferred-work.md` reflects reality.** **Story creation already did part of this — read the file before editing it.** A `## Last audit: 2026-08-05 (skillars-deferred-15 story creation)` block **already exists**, the five items this story owns are **already annotated inline** with `OWNED BY skillars-deferred-15` naming their AC, and the reachability correction to `skillars-8-2` D1/D2 is **already written**. Do not duplicate any of that.

   A new item is also **already added** by story creation under this story's audit block, recording the residual AC1 knowingly leaves open: credit-funded stranded bookings cannot be swept because `chargeAndCapture` has no durable pre-capture record, and closing it means writing that record before the Stripe call and making the four `existsById` idempotency checks status-aware. Do not delete that item — this story does not close it.

   What this story still has to do: delete the five items it closes — D1 under `## Deferred from: skillars-deferred-14 story creation (2026-08-05)` (which empties that heading → **remove the heading too**); D1, D2 and D4 under `## Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity (2026-08-05)` (**D3 stays** — see "Items examined and NOT included" — so that heading remains); and D2 under `## Deferred from: adversarial code review of skillars-7-2 Group 2 Service Layer (2026-06-24)` (D1 and D3 stay, heading remains). Then amend the existing story-creation audit block to record those five as closed by shipped code, plus whatever the implementation found that story creation got wrong — in particular the actual observed pre-fix behaviour from Tasks 1 and 8.

   Match on heading **plus** item id: the file's own preamble warns ids repeat across sections, and three headings now begin with `skillars-deferred-14`. There are already three `## Last audit: 2026-08-05` headings disambiguated by parenthesised suffix; that is the file's convention — **do not add a fourth**, amend the story-creation one.

## Tasks / Subtasks

- [x] **Task 1 — Reproduce the stranded booking before building anything (AC: 1, 2)**
  - [x] Write the IT case first: seed a booking in `PAYMENT_PENDING` with `updated_at` well in the past and no `payment.booking_payments` row, run the (not yet existing) sweep, and assert it is still `PAYMENT_PENDING` — i.e. pin the current "nothing recovers this" behaviour.
  - [x] Independently confirm the claim rather than trusting it: grep `src/main` for `@Scheduled` and check each hit for a `PAYMENT_PENDING` read. Story creation counted 27 schedulers and zero readers; record what you find in Debug Log References. If a reader exists, stop and re-scope AC1 rather than adding a second one.
  - [x] Confirm the booking genuinely blocks the slot: attempt a `createBookingRequest` for the same coach and overlapping window and observe the `403 booking.slotUnavailable` from `BookingService.java:217-225`. This is the user-visible harm; capture it.

- [x] **Task 2 — `PaymentPendingSweeper` (AC: 1)**
  - [x] New class `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`. **Payment module, not booking** — it needs `BookingPaymentRepository` and `ParentCreditLedgerRepository`, and it is recovering `PaymentLifecycleService`'s own failure mode. The payment module already depends on `booking.service`/`booking.repo` (`PaymentLifecycleService:5-8`, `BookingPaymentPersistenceService:3-8`), so no new dependency direction is introduced.
  - [x] `@Scheduled(fixedDelay = 15, timeUnit = TimeUnit.MINUTES)` + `@SchedulerLock(name = "PaymentPendingSweeper_sweep", lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")`. ShedLock is already wired (`V80__shedlock_table.sql`, `ShedLockConfigIT`); follow `SessionPackForfeitureScheduler:31-33` for the annotation shape.
  - [x] Add to `BookingRepository`: `@Query("SELECT b FROM Booking b WHERE b.status = 'PAYMENT_PENDING' AND b.updatedAt < :threshold") List<Booking> findPaymentPendingOlderThan(@Param("threshold") Instant threshold)`. `Booking.updatedAt` exists and is maintained by `@PreUpdate` (`Booking.java:62-63`); `createdAt` would be wrong — it predates the accept by however long the request sat in the coach's inbox.
  - [x] **No `ParentCreditLedgerRepository` change.** An earlier draft of this AC specified an `existsByTypeAndReferenceId("BOOKING_DEDUCTION", batchId)` guard; it was removed after tracing the transaction boundaries (Dev Notes → "the credit-ledger guard that does not work"). Do not re-add it.
  - [x] Structure the run exactly like `SessionPackForfeitureScheduler.forfeitExpiredPacks`: select outside, then a `TransactionTemplate.execute` **per booking** wrapped in `try/catch (Exception e) { log.error(...) }`, so one failure cannot abort the sweep or poison a sibling. Do **not** annotate the scheduled method `@Transactional` — a single long transaction is what made `BookingExpiryScheduler` fragile, and the per-booking template is the established remedy (`PaymentLifecycleService:43-55`).
  - [x] Inside each per-booking transaction: re-read the booking and re-assert `PAYMENT_PENDING` (the row may have settled between select and sweep), then evaluate the two auto-decline preconditions, then write the `BookingPayment(CHARGE_FAILED)` row, then `bookingService.transition(id, PAYMENT_FAILED, new TransitionContext(ActorRole.SYSTEM, null))`, then publish `BookingDeclinedEvent`. Order matters: mirror `persistPaymentFailure:76-85`.
  - [x] Every report-only path logs at **ERROR** with `bookingId`, `batchId`, `parentId`, `sessionPackPurchaseId`, `updatedAt` and the disqualifying condition — these are states an operator must reconcile against Stripe by hand, not routine noise. Emit a counter alongside the log so the condition is alertable; follow whatever metric idiom the module already uses rather than inventing one.
  - [x] Add a class-level comment stating plainly that the credit-funded case is **knowingly** left stranded, and why (no durable pre-capture record exists). A later reader must not "complete" the sweeper by removing the pack-funded precondition — that would convert a stuck slot into a silent overcharge. The follow-up that would make full coverage safe is tracked in `deferred-work.md` under this story's audit block.
  - [x] Resolve `parentEmail` and `coachDisplayName` for the event the way `BookingExpiryScheduler:52-59,70-75` does (null-tolerant, `""`/`"Coach"` fallbacks, WARN on miss). A missing email must not abort the sweep.
  - [x] Seed the config key in `V90`: `INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES (601, 'booking.payment_pending_sweep_grace_minutes', '120', 'LONG', '...') ON CONFLICT (key) DO NOTHING;`. Highest existing id is 600 (`V85`); the `ON CONFLICT (key)` idiom is the project pattern. 120 minutes is deliberately well clear of Stripe capture latency — settlement is an in-process `AFTER_COMMIT` listener, so anything still pending after two hours is not in flight.

- [x] **Task 3 — Sweeper tests (AC: 2)**
  - [x] Unit test `PaymentPendingSweeperTest` (`@ExtendWith(MockitoExtension.class)`, strict stubbing) covering the four cases, following `BookingExpirySchedulerTest`'s constructor-injection style (`:44-50`) — it builds the scheduler by hand rather than `@InjectMocks`, which also sidesteps the `@PostConstruct`/`@InjectMocks` trap Deferred-14 hit.
  - [x] Integration test in `src/test/java/com/softropic/skillars/platform/payment/service/` (the `BasePaymentIT` fixtures live there) driving cases (a)–(d) against a real database. Seed with raw `jdbcTemplate` inside `transactionTemplate.execute`, tear down in `@AfterEach` — the project-wide IT pattern.
  - [x] Setting `updated_at` into the past needs a direct `jdbcTemplate` UPDATE — a JPA save would re-stamp it via `@PreUpdate`. Do this **after** the entity write, and assert the value actually landed before running the sweep; a silently re-stamped timestamp makes case (a) pass for the wrong reason.
  - [x] Case (c) seeds `booking_payments` with `status = 'CAPTURED'` and a `captured_at`; case (d) seeds a booking with `session_pack_purchase_id` **null**, past grace, no payment row. Both must assert the booking is **still** `PAYMENT_PENDING` afterwards **and** that no `booking_payments` row was written — a sweeper that logs ERROR but still transitions would pass a log-only assertion.
  - [x] Case (d) is the one that protects real money. Give it a name that says so (e.g. `creditFundedStrandedBooking_isReportedNotDeclined`) so a future editor cannot mistake it for a redundant variant of (a) and delete it.

- [x] **Task 4 — Reschedule accept/decline mutual exclusion (AC: 3)**
  - [x] Add `findByIdForUpdate` to `BookingRescheduleRequestRepository`, copying the annotation stack from `CoachProfileRepository:31-34` verbatim (`@Lock(LockModeType.PESSIMISTIC_WRITE)` + explicit `@Query`).
  - [x] `acceptReschedule`: keep the cheap `findById` + `PENDING` check at `:103-111`, then take `findByIdForUpdate(rescheduleId)` **before** the coach lock at `:128`, and re-assert `"PENDING".equals(locked.getStatus())` immediately. Use the locked instance for the rest of the method — do not keep writing through the earlier reference.
  - [x] `declineReschedule`: replace `findById` at `:167` with `findByIdForUpdate` and keep the existing `PENDING` check, which now runs under the lock.
  - [x] Write a comment recording the lock **ordering** rule (reschedule row, then coach) and why it is safe: `declineReschedule` takes only the first, so no path acquires them in the opposite order. A future editor adding a coach lock to `declineReschedule` must take it second.
  - [x] IT in `RescheduleResourceIT`: a decline committing on a second thread while the accept is parked mid-method must leave the request `DECLINED` and the booking's times **unchanged**, with the accept failing. Follow `BookingServiceConcurrencyIT` for the barrier/executor idiom.
  - [x] **Mutation-verify it.** Revert the post-lock `PENDING` re-check and confirm the test fails. Per `deferred-13`'s finding, a test that only proves "the second caller waits" proves nothing — the discriminator is the **final stored status**, so assert on that. Record the result in Completion Notes.
  - [x] `RescheduleServiceTest` will break: `acceptReschedule_coachOwnsBooking_updatesTimesAndStatus` and the decline tests stub `rescheduleRepo.findById`. Repoint to `findByIdForUpdate`; strict stubbing turns a leftover stub into a build failure.

- [x] **Task 5 — Suspension check under the coach lock at all three accept paths (AC: 4)**
  - [x] `BookingService.acceptBooking` (`:283-284`): the coach row is **already managed** from `findByUserId` at `:272`, so add `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)` before reading the status — same reasoning as the comment at `:203-207`. `EntityManager` is already injected (`:115`).
  - [x] `RescheduleService.acceptReschedule` (`:128-129`): same situation — `findByUserId` at `:96` leaves the row managed. `RescheduleService` has **no** `EntityManager`; inject one (`@PersistenceContext` or constructor via `@RequiredArgsConstructor`, matching how `BookingService` does it).
  - [x] `BookingBatchService.acceptOneBooking` (`:264-265`): this runs inside a `REQUIRES_NEW` transaction with its own persistence context, so the row is **not** pre-managed and `findByIdForUpdate` returns fresh state. Add the refresh anyway for uniformity **only if** you can state why in a comment; otherwise read the locked instance directly and comment that the fresh persistence context is what makes it safe. Do not leave the reader guessing which of the two situations applies.
  - [x] Throw `OperationNotAllowedException("Coach is suspended", metaData, BookingError.COACH_UNAVAILABLE)` — the identical shape `createBookingRequest:208-210` uses. `ApiAdvice` maps `OperationNotAllowedException` → **403** with `errorKey` from the `ErrorCode`, so `BookingError.COACH_UNAVAILABLE` → `booking.coachUnavailable`. Confirm against `SuspendedCoachBookingBlockIT`'s existing expectations before asserting a status code; do not invent a new one.
  - [x] `AdminCoachEnforcementService.suspendCoach:101`: `findById` → `findByIdForUpdate`. Keep the already-`SUSPENDED` early return and everything after it unchanged.
  - [x] Extend `SuspendedCoachBookingBlockIT` (it already owns this scenario for the *create* path) with three cases: a suspended coach attempting a single accept, a batch `acceptAll`, and a reschedule accept — each `403` / `booking.coachUnavailable`, with the booking's status unchanged.
  - [x] Add one concurrency IT: suspension committing while an accept is parked on the coach lock must make the accept fail. Mutation-verify by removing the status re-read.
  - [x] Check whether `AdminCoachEnforcementService`'s existing tests stub `findById`; repoint them if so.

- [x] **Task 6 — Unify the batch-status formula (AC: 5)**
  - [x] Extract `private String computeBatchStatus(List<Booking> allInBatch)` from `updateBatchStatusFromBooking:298-310` and have that method delegate to it, keeping its `requestedCount > 0 → return` guard where it is.
  - [x] In `acceptAll`'s trailing transaction (`:235-244`), re-read `bookingRepository.findByBatchId(batchId)` **inside** the transaction and call `computeBatchStatus`, replacing the `acceptedIds.size() == requestedBookings.size()` expression at `:215-216`. The re-read is required for the same reason the batch itself is re-read there: `requestedBookings` belongs to the enclosing transaction's persistence context and predates the per-booking commits.
  - [x] Leave `acceptedIds` in place — it still feeds `BatchBookingAcceptedEvent` and the log line, and `acceptedIds.isEmpty() → return` at `:210-213` stays as-is.
  - [x] Update the comment at `:227-234` to say the trailing transaction now writes the **same** value the listener would, so the two writers can no longer disagree.
  - [x] IT in `BookingBatchResourceIT`: a batch of three with one booking individually declined **before** `acceptAll`, then `acceptAll` accepting the other two, must end `PARTIALLY_ACCEPTED`. Assert the pre-fix value (`FULLY_ACCEPTED`) first so the test is known to discriminate, then flip it.
  - [x] `BookingBatchServiceTest` mocks `bookingRepository`; the new `findByBatchId` call inside the trailing transaction will return `null`/empty unless stubbed. Fix in the same commit.

- [x] **Task 7 — Reproduce the missing expiry-warning email (AC: 6)**
  - [x] Before changing anything, prove the current behaviour: invoke `notifyExpiringPacks` with a pack inside the 14-day window in a Spring context IT and assert `SessionPackEmailListener.onExpiryWarning` is **not** invoked (or that no `Envelope` is published). Static analysis says `@TransactionalEventListener` with default `fallbackExecution = false` discards an event published with no transaction bound; Spring also logs "No transaction is active" at DEBUG — capture whichever signal the test can see.
  - [x] Record the observed result in Debug Log References. **If the event does fire** (e.g. some enclosing transaction exists in practice), say so plainly and re-scope AC6 to the dedupe half only — do not force the prediction.

- [x] **Task 8 — One expiry warning per pack, actually delivered (AC: 6)**
  - [x] `V90__payment_pending_sweep_and_pack_warning.sql`: `ALTER TABLE payment.session_pack_purchases ADD COLUMN expiry_warned_at TIMESTAMPTZ;` plus `CREATE INDEX idx_session_pack_purchases_expiry_warn ON payment.session_pack_purchases (expires_at) WHERE expiry_warned_at IS NULL;` — mirroring `V88`'s `expired_notified_at` pair exactly. Add the AC1 config-key insert to the same migration.
  - [x] `SessionPackPurchase`: add `@Column(name = "expiry_warned_at") private Instant expiryWarnedAt;` next to `expiredNotifiedAt` (`:58-59`).
  - [x] `findExpiringWithinWindowAndSessionsRemaining` (`SessionPackPurchaseRepository:25-26`): add `AND p.expiryWarnedAt IS NULL`. Check for other callers before editing — `findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan` (`:22-23`) is a separate derived query and must **not** change.
  - [x] `SessionPackExpiryNotifier`: inject `TransactionTemplate` (constructor, as `SessionPackForfeitureScheduler:29` does), and per pack run `transactionTemplate.execute` that sets `expiryWarnedAt`, saves, and publishes — in that order, so the `AFTER_COMMIT` listener fires on a commit that already carries the stamp. Wrap each pack in `try/catch (Exception e) { log.error(...) }`.
  - [x] IT: a pack in the window produces exactly one `Envelope` on the first run and **zero** on an immediate second run. Both halves must be asserted — the second run is what pins the dedupe.

- [x] **Task 9 — `deferred-work.md` ledger maintenance (AC: 7)**
  - [x] **Read the file first.** Story creation already added the audit block, annotated the five owned items, and wrote the `skillars-8-2` reachability correction. Re-doing any of it recreates the duplicate-heading mess `deferred-13`'s review had to clean up.
  - [x] Delete the five closed items and the one heading that empties (`## Deferred from: skillars-deferred-14 story creation (2026-08-05)`). Leave `deferred-14` review D3, `7-2` Group 2 D1/D3, `10-2` D1 and `8-2` D1/D2 in place.
  - [x] Amend the existing story-creation audit block with what implementation actually found — especially Task 1's scheduler count and Task 7's observed delivery behaviour. Do not add a fourth `## Last audit: 2026-08-05` heading.

- [x] **Task 10 — Full verification**
  - [x] `mvn -o verify` green (unit + IT). Baseline from `deferred-14`: **812 unit + 850 IT, 0 failures** (~18 min for the IT phase — budget for it).
  - [x] Re-run the suites most exposed by this diff, and say so explicitly in the Change Log rather than only quoting totals:
    - `BookingServiceConcurrencyIT`, `SuspendedCoachBookingBlockIT` — AC4 adds a throw inside `acceptBooking`'s lock section.
    - `BatchAcceptPaymentIT`, `BookingBatchResourceIT` — AC5 changes what the trailing transaction writes.
    - `RescheduleResourceIT`, `RescheduleServiceTest` — AC3 changes the lock shape.
    - `SessionPackPurchaseIT`, `SessionPackForfeitureSchedulerTest`, `PackExtensionIT` — AC6 changes a shared query and adds a column.
    - `ShedLockConfigIT` — AC1 adds a new lock name.
  - [x] Confirm `V90` applies cleanly on a fresh Testcontainers database **and** that `idx_session_pack_purchases_expiry_warn` does not collide with `V88`'s index name.
  - [x] Every concurrency test added by AC3 and AC4 must be mutation-verified in both directions and the result recorded. `deferred-13`'s review found that barrier-based ITs routinely pass against unfixed code; a test that is not mutation-verified is not evidence.

### Review Findings

- [x] [Review][Patch] `BookingServiceConcurrencyIT`'s suspend-race test and `RescheduleResourceIT`'s decline-race test simulate the contending write with raw JDBC (`UPDATE ... SET status = 'SUSPENDED'`, raw decline SQL) instead of calling the real `AdminCoachEnforcementService.suspendCoach()` / `RescheduleService.declineReschedule()` under test — AC4's claim that "the lock the accept paths already hold serialises against nothing without suspendCoach also locking" is proven only against a lock-shaped stand-in, not the wired service method [src/test/java/.../BookingServiceConcurrencyIT.java, src/test/java/.../RescheduleResourceIT.java] — **ACCEPTED, and it was the sharpest finding.** Confirmed by mutation: reverting `suspendCoach` to a plain `findById` passed the entire suite, so that lock — the one AC4's whole argument rests on — was untested and could have been deleted unnoticed. Same for `declineReschedule`. Kept the existing raw-SQL staging (the stand-in is the *contending* side there, which is correct), and added the two missing tests with the real service as the method under test: `CoachSuspensionIT.suspendCoach_concurrentSuspensionCommitsFirst_isNotRedoneOnAStaleRead` and `RescheduleResourceIT.declineReschedule_acceptCommitsWhileDeclineWaitsOnTheLock_declineFailsAndAcceptStands`. Both mutation-verified: the stale-read suspension redoes the whole suspension and leaves a duplicate `COACH_SUSPEND` audit row (`expected: 0 but was: 1`), and the stale-read decline overwrites the committed accept. Discriminator is what the call observes once unblocked, not that it waited — per deferred-13.
- [x] [Review][Patch] `SessionPackExpiryNotifier.notifyExpiringPacks`'s `if (coach == null) return null;` branch has no logging, no counter, and never stamps `expiryWarnedAt` — a pack with a dangling `coach_id` retries silently every day forever, with no operator signal, unlike the analogous case in this same diff's `PaymentPendingSweeper.reportUnrecoverable` [src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java:64-65] — **ACCEPTED.** Added an ERROR log carrying purchaseId/coachId/parentId/expiresAt. Deliberately still does not stamp `expiryWarnedAt`: `coach_id` carries an FK (`fk_spp_coach`), so reaching this branch means a data-integrity failure, and stamping would silence the only signal it exists. The repetition is the alert.
- [x] [Review][Patch] `PaymentPendingSweeper`'s `@Scheduled(fixedDelay = 15min)` has zero margin against its own `@SchedulerLock(lockAtMostFor = "PT15M")` — sibling schedulers this class patterns itself after keep 3x+ margin (`SessionPackForfeitureScheduler`: 60min delay / 15min lock; `BookingExpiryScheduler`: 5min delay / 15min lock) — a slow run risks the lock expiring mid-execution and a second instance starting an overlapping sweep [src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java:82-83] — **ACCEPTED.** `lockAtMostFor` raised to `PT30M`, giving 2x margin over the 15-minute cadence, with the reasoning recorded at the annotation. Note the harm was bounded: `sweepOne` re-reads and re-asserts `PAYMENT_PENDING` inside its own transaction, so an overlapping run could not double-decline — but it would double-log and waste work.
- [x] [Review][Patch] `PaymentPendingSweeper.sweepStrandedPayments`'s per-booking `catch (Exception e)` logs every failure at ERROR uniformly, including a benign optimistic-lock conflict from a booking that legitimately settled mid-sweep — undermining the class's own documented ERROR/WARN distinction ("ERROR, not WARN: these are the bookings an operator has to reconcile by hand") [src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java:99-103] — **ACCEPTED.** `OptimisticLockingFailureException` is now caught separately and logged at INFO as "settled concurrently". Narrowed deliberately to that exception; widening it to `DataIntegrityViolationException` would swallow real defects.
- [x] [Review][Patch] No fast unit test covers `BookingService.acceptBooking`'s new suspended-coach check — it's exercised only by the slow thread-based `BookingServiceConcurrencyIT` and the end-to-end `SuspendedCoachBookingBlockIT`, inconsistent with the equivalent checks in `BookingBatchService` and `RescheduleService`, both of which also got direct mock-based unit tests [src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java] — **ACCEPTED.** Added `BookingServiceTest.acceptBooking_suspendedCoach_throwsCoachUnavailable`. Its javadoc states what it does not prove: `entityManager` is a mock, so `refresh()` is a no-op and the locked instance is whatever the stub returns — that the refresh is load-bearing is proven only by `BookingServiceConcurrencyIT`.
- [x] [Review][Patch] `BookingBatchService.computeBatchStatus` returns `FULLY_ACCEPTED` for an empty booking list (`0 == 0` is vacuously true) — pre-existing formula, but this diff consolidated both callers onto it specifically to eliminate disagreement, making this the natural place to guard the degenerate case; neither the new unit test nor the new IT exercises the boundary [src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:341] — **ACCEPTED.** Guarded with an explicit `isEmpty() -> "PENDING"` and a unit test on the boundary. Unreachable today (both callers exclude it), but the finding's reasoning holds: this diff made the method the single shared formula, so it is where the degenerate case belongs.
- [x] [Review][Rejected] `PaymentPendingSweeper.reportUnrecoverable` has no dedupe/escalation ceiling — the same stranded credit-funded booking re-logs an identical ERROR and re-increments the same counter every 15-minute cycle indefinitely, with no coded remediation path or backoff [src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java:154-160] — **REJECTED, deliberately.** The repetition is the feature. A credit-funded stranded booking may have real money captured at Stripe with no record; the ERROR and its counter must keep firing until a human resolves it, because that is what the alert is keyed on. Backing off or stamping would make an unresolved, money-at-risk booking look resolved — the precise failure this sweeper's report-only design exists to avoid, and strictly worse than noise. Dedupe state would also be new schema for a case the story deliberately leaves open. The right closure is not suppression but `deferred-work.md` -> `skillars-deferred-15 story creation` D1 (the durable pre-capture record), which would let these bookings be swept rather than merely reported.
- [x] [Review][Defer] `SessionPackExpiryNotifier` stamps `expiryWarnedAt` in the same transaction that publishes the warning event, before the `AFTER_COMMIT` listener actually attempts delivery — a mail-send failure in the listener permanently loses the warning with no retry [src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java:72-87] — deferred, pre-existing: mirrors the identical accepted tradeoff already in `SessionPackForfeitureScheduler`, and is the same platform-wide `AFTER_COMMIT`-listener-reliability gap the spec's own Dev Notes explicitly scope out to `skillars-10-2` D1
- [x] [Review][Defer] `BookingService.acceptBooking` / `RescheduleService.acceptReschedule` call `coachProfileRepository.findByIdForUpdate` and then immediately `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` — two separate `SELECT ... FOR UPDATE` round trips on the same row for the same lock [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:279-289] — deferred, pre-existing: verified this is the exact idiom already established in `BookingService.createBookingRequest:201-215` (Deferred-12 AC3), which this diff was explicitly directed to mirror byte-for-byte, not a pattern newly introduced here
- [x] [Review][Defer] `BookingRepository.findPaymentPendingOlderThan`'s correctness assumes `updatedAt` reflects only the transition into `PAYMENT_PENDING`; `@PreUpdate` stamps `updatedAt` on any field change, so an unrelated write to a `PAYMENT_PENDING` booking would silently reset the stranded-booking clock [src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java] — deferred, pre-existing: no such write path exists anywhere in `src/main` today (verified), speculative risk against a future path, not a live defect
- [x] [Review][Defer] The batch-status trailing transaction in `acceptAll` and the `AFTER_COMMIT` listener's `updateBatchStatusFromBooking` both read-then-write `computeBatchStatus` unlocked — a last-writer-wins race remains between them, though both now compute from the same correct formula (unlike the pre-AC5 disagreement) [src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:249-258,308-321] — deferred, pre-existing: this residual non-atomicity was already explicitly documented and accepted in `deferred-14`'s own commit message ("acceptAll is explicitly documented as no longer atomic")

## Dev Notes

### AC1 — why a sweeper, and why this narrow one

The stranding window is concrete. `BookingService.acceptAndInitiatePayment` (`:332-336`) commits the booking into `PAYMENT_PENDING`. Settlement happens in `PaymentLifecycleService.onBookingAccepted` / `onBatchBookingAccepted` — both `@TransactionalEventListener(AFTER_COMMIT)` with `REQUIRES_NEW`, **no retry and no dead-letter queue**. If the JVM dies between the accept commit and the listener, or the listener's own transaction fails outright, the booking rests in `PAYMENT_PENDING` forever.

What makes it more than an accounting wart:

- `PAYMENT_PENDING` ∈ `ACTIVE_SLOT_STATUSES` (`BookingService.java:117-118`) **and** ∈ `V87`'s exclusion-constraint `WHERE` clause, so the row **holds the coach's slot** and blocks every other booking for that window.
- The state machine's only exits are `PAYMENT_CAPTURED` (the listener that already failed), `PAYMENT_FAILED` (nothing issues it outside the payment listener), and `CANCEL_PARENT` — added by `deferred-12` AC4 for exactly this crash window (`BookingStateMachine.java:30-38` even names it), but requiring **the parent** to notice. No coach path, no admin path, no system path.

`BookingPaymentPersistenceService.declineBatchBooking` (`:147-158`) covers only the adjacent case where a settle attempt *ran and threw*. This is the case where it never ran.

`PAYMENT_FAILED → DECLINED` is the right terminal transition because it already exists and because `applyRefundLogic` (`BookingService`) has **no branch for `PAYMENT_FAILED`** — so `refundEligibility` stays null and no bogus refund is implied. Verify that by reading the switch before relying on it.

### AC1 — why the sweep is restricted to pack-funded bookings

This restriction is the whole safety argument. Trace both settlement paths and it falls out.

**Single booking — `onBookingAccepted` (`:59-75`), `@Transactional(REQUIRES_NEW)`, so the listener body runs in one transaction (call it TX_L).**

- Pack-funded → `handlePackBasedBooking` (`:77-90`): `packSessionService.deductSession` then `persistPaymentSuccess`. Both are plain `@Transactional` (REQUIRED) and join TX_L. **No Stripe call on this path at all.** TX_L fails or the JVM dies → everything rolls back together → booking stranded in `PAYMENT_PENDING` with provably zero money movement. Safe to decline.
- Credit-funded → `handleCreditBasedBooking` (`:92-117`): `paymentGateway.chargeAndCapture(...)` **first** (`:102`), then `persistPaymentSuccess` (`:114`). The capture is an external, irreversible side effect; the DB writes that record it join TX_L and commit afterwards. A crash between the two leaves **money captured, booking `PAYMENT_PENDING`, no `booking_payments` row**. Unsafe.

**Batch — `onBatchBookingAccepted` (`:127-234`), same `REQUIRES_NEW` TX_L.**

- Pack bookings are partitioned out at `:133-141` and settled in the `packIds` loop, each in its own `perBookingTx` (`REQUIRES_NEW`) doing deduct + confirm atomically (`:152-157`). They never contribute to `creditSubtotal` and never reach `chargeAndCaptureForBatch`. Safe.
- Credit bookings: `chargeAndCaptureForBatch` charges the **whole batch upfront** (`:190-191`) and only then does the per-booking `confirmCreditBatchPayment` loop (`:209-233`) record it against individual bookings, each in its own `REQUIRES_NEW`. A crash mid-loop leaves the batch fully charged with some siblings confirmed and the rest holding **no payment row at all**. Unsafe, and this is the case the naive `existsById` guard misses most badly.

**There is no signal that closes the credit gap.** Verified during this audit: `StripePaymentGateway` is not `@Transactional` and its only durable write is `stripeCustomer.setLastPaymentIntentId(...)` (`:67-70`) — which joins the caller's transaction (so it rolls back in exactly the crash window that matters) *and* is keyed per parent and overwritten on every charge, so it cannot identify a booking or batch. `PaymentGateway` exposes no lookup/query method. `StripeWebhookService.handleEventAtomically` (`:71-80`) handles `account.updated` and the `customer.subscription.*` / invoice families only — **no `payment_intent.*` or `charge.*` handler**, so `payment.stripe_webhook_events` holds no capture record either. Reconciling a credit-funded stranded booking therefore requires querying Stripe, which nothing in this codebase can do today.

Hence: decline what is provably free of money movement, report the rest loudly, and do not pretend the second half is solved.

### AC1 — the credit-ledger guard that does not work

An earlier draft of AC1 required a second guard: skip a batched booking when a `BOOKING_DEDUCTION` credit-ledger entry exists for its `batchId`, on the reasoning that `onBatchBookingAccepted` writes that entry *before* the per-booking confirms, so a crash in between would leave committed credit debited with zero payment rows.

**That reasoning is backwards and the guard is near-useless. Do not add it.** `CreditWalletService.writeLedgerEntry` (`:36-37`) is plain `@Transactional` (REQUIRED), so the batch-level deduction at `:182-185` **joins TX_L and commits at the end of the listener method** — *after* the per-booking `REQUIRES_NEW` confirms have already committed independently. Durability order is the opposite of what the draft assumed. In the crash window the guard was meant to cover, the ledger entry has rolled back along with everything else, so the guard finds nothing. The only way the entry is durable is if TX_L committed in full — in which case the per-booking confirms committed too and the existing-payment-row guard already fires. It adds a repository method and a query for no reachable case.

The pack-funded precondition supersedes it and is strictly stronger: it excludes every path that can reach Stripe, rather than trying to detect after the fact that one did.

### AC1 — the follow-up this story deliberately does not do

Full coverage needs a **durable pre-capture record**: write a `booking_payments` row (or an intent-outbox row) in its own `REQUIRES_NEW` transaction *before* calling `chargeAndCapture`, then update it to `CAPTURED` after. The sweeper's existing-payment-row guard would then be sound for every funding path — no row means the code provably never reached Stripe.

It is out of scope here for two reasons, both worth stating so the next author does not treat this as an oversight: it modifies the live charging path rather than adding a reader alongside it, and it changes the meaning of `bookingPaymentRepository.existsById`, which is the **idempotency guard** at four call sites (`PaymentLifecycleService:62,145,199,210`). A pre-capture row left behind by a crashed attempt would make a duplicate event delivery skip settlement entirely — trading a visible stuck booking for an invisible one. Those call sites would have to become status-aware in the same change. Tracked as a new item in `deferred-work.md` under this story's audit block.

### AC3 — the race, precisely

`acceptReschedule` today: read status (`:108`) → … → block on coach lock (`:128`) → write `ACCEPTED` (`:144`). `declineReschedule`: read status (`:172`) → write `DECLINED` (`:177`), no locks anywhere.

Interleaving: accept reads `PENDING`, parks on the coach lock; decline runs start-to-finish and commits `DECLINED`; accept is granted the lock, resumes holding a **stale in-memory `req`** still reading `PENDING`, passes the overlap check, and overwrites the decline with `ACCEPTED`. The coach's decline is silently reversed and the booking's times are rewritten.

Deferred-14's AC4 did not create this class of bug — status was already read-then-written-later — but it inserted a **blocking DB call** into the middle of the window, which is why its own code review flagged it. Locking the reschedule row in both methods closes it at the source; re-checking after the coach lock alone would not, because `declineReschedule` never takes the coach lock.

### AC4 — which sites need `entityManager.refresh`, and why it is not cosmetic

`findByIdForUpdate` is a **JPQL** query. When the same row is already managed in the current persistence context, Hibernate takes the DB lock but returns the **existing instance without overwriting its in-memory state** — so reading `getStatus()` off it re-reads the stale value the check was supposed to catch. `BookingService.createBookingRequest:203-207` carries this exact comment, and `AdminReviewService.approveReview:74-80` cross-references it.

Applying that rule:

| Site | Row pre-managed? | Refresh needed? |
|---|---|---|
| `BookingService.acceptBooking:283` | **Yes** — `findByUserId` at `:272` | **Yes** |
| `RescheduleService.acceptReschedule:128` | **Yes** — `findByUserId` at `:96` | **Yes** (inject an `EntityManager`) |
| `BookingBatchService.acceptOneBooking:264` | **No** — runs in a `REQUIRES_NEW` transaction with a fresh persistence context | No, but say so in a comment |

Confirm the third row empirically rather than trusting the table: if `acceptOneBooking`'s suspension test passes without a refresh and fails with a deliberately stale coach, the reasoning holds.

**Why `suspendCoach` must take the lock too.** `AdminCoachEnforcementService.suspendCoach:101` uses a plain `findById`. Two writers touching the same row with only *one* of them holding `SELECT … FOR UPDATE` do not serialise: the suspension can read, write and commit entirely inside the window in which the accept path holds its lock. The accept-side lock is only meaningful once both sides take it. This is the same lesson `deferred-13`'s review recorded about locks that look protective and are not.

Note the second-order effect, which is why AC4 is worth doing beyond the abstract race: `suspendCoach` cancels the coach's `REQUESTED` bookings (`:112-119`). A booking accepted concurrently moves to `PAYMENT_PENDING` and is therefore **invisible to that sweep**, surviving with a suspended coach — and, until AC1 ships, potentially stranded there.

### AC5 — what changed under Deferred-14, and what did not

The formula bug is pre-existing: `acceptedIds.size() == requestedBookings.size()` compares against the `REQUESTED` subset at loop start, so a batch containing an already-`DECLINED` booking yields `FULLY_ACCEPTED`.

What Deferred-14 changed is **which writer wins**. Before: `acceptAll` wrote first, then the `AFTER_COMMIT` `BookingBatchStatusListener` recomputed over *all* rows and corrected it. After: per-booking commits make the listener fire mid-loop, and the trailing transaction overwrites its (correct) value with the naive one. The winner flipped toward the wrong value.

It is **self-correcting in practice** — settlement transitions each booking and republishes `BookingStatusChangedEvent`, so the listener runs again after the trailing commit — which is why Deferred-14 left it. The exposure is a transient wrong read. Unifying the formula removes both the transient wrong read and the dependence on settlement to clean up after the accept path.

Do **not** "simplify" by deleting `acceptAll`'s own write. Deferred-14's Dev Notes rejected that explicitly: failed bookings stay `REQUESTED`, so `updateBatchStatusFromBooking` early-returns forever and the batch never leaves `PENDING`.

### AC6 — the two independent defects

1. **Delivery.** `notifyExpiringPacks` (`:32-65`) is not `@Transactional` and does not open a `TransactionTemplate`. `SessionPackEmailListener.onExpiryWarning` is `@TransactionalEventListener(phase = AFTER_COMMIT)`; with the default `fallbackExecution = false`, an event published with no transaction bound is **discarded**. `SessionPackForfeitureScheduler` gets this right by publishing inside `transactionTemplate.execute` (`:42-56`) — the difference between the two schedulers is the whole bug. `SessionPackEmailListenerTest` constructs the listener directly and calls the method, so it cannot catch this.
2. **Dedupe.** Once delivery is fixed, the daily cron (`0 0 8 * * *`) plus a 14-day window means one pack is selected on up to 14 consecutive mornings.

Ship both or neither. The `expired_notified_at` column and its partial index from `V88` are the template — same nullable `TIMESTAMPTZ`, same `WHERE … IS NULL` index, same "stamp inside the transaction that publishes" ordering.

### Files to touch

**New (main):**
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java` — AC1
- `src/main/resources/db/migration/V90__payment_pending_sweep_and_pack_warning.sql` — AC1, AC6

**Modify (main):**
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java` — AC1 (`findPaymentPendingOlderThan`)
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRescheduleRequestRepository.java` — AC3 (`findByIdForUpdate`)
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java` — AC3, AC4
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` — AC4
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java` — AC4, AC5
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java` — AC4 (locked read)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java` — AC6
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` — AC6
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java` — AC6

**New (test):**
- `PaymentPendingSweeperTest.java` + a sweeper IT under `platform/payment/service/` — AC2

**Modify (test):**
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` — AC3
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java` — AC3 (stubs break)
- `src/test/java/com/softropic/skillars/platform/admin/api/SuspendedCoachBookingBlockIT.java` — AC4
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java` — AC5
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java` — AC5 (new repo call needs a stub)
- A pack-expiry IT under `platform/payment/service/` — AC6

**Modify (docs):**
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC7
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

**No frontend change. No i18n change** — every new error path reuses an existing key (`booking.coachUnavailable`, already surfaced by `createBookingRequest`).

### Testing standards

- Integration tests are `*IT.java`, Testcontainers-backed, seeded with raw `jdbcTemplate` inside `transactionTemplate.execute` and torn down in `@AfterEach`. FK-constrained seeding needs fixed ids, which is why Instancio is not used in ITs (see `skillars-5-6` AD4).
- Unit tests use `@ExtendWith(MockitoExtension.class)` with **strict** stubbing — an unused stub fails the build, which is what Tasks 4 and 6 have to repair.
- Concurrency ITs use `CyclicBarrier`/`CountDownLatch` + an executor; `BookingServiceConcurrencyIT` is the reference implementation.
- **Every concurrency test here must be mutation-verified in both directions.** `deferred-13`'s review found both of its barrier-based ITs passed unchanged against unfixed code, and that even "hold `SELECT FOR UPDATE`, assert timeout" passed the mutation — waiting is not the discriminator. Assert on **what the caller observes after unblocking**, and on final stored state.
- Scheduler unit tests in this repo construct the bean by hand rather than `@InjectMocks` (`BookingExpirySchedulerTest:44-50`) — follow that; `@InjectMocks` does not run `@PostConstruct`, a trap Deferred-14 hit with `BookingBatchServiceTest`.
- Use **Awaitility** for asynchronous assertions; `Thread.sleep` in tests is an accepted-but-discouraged legacy pattern (`skillars-5-1` D4).

### Project Structure Notes

- `BookingService`, `BookingBatchService` and `RescheduleService` share the package `com.softropic.skillars.platform.booking.service`, which is why `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` is package-private rather than promoted to `booking.contract`. Nothing in this story needs to widen it further.
- The sweeper belongs in `platform.payment.service`, not `platform.booking.service`: it reads `payment.booking_payments` and `payment.parent_credit_ledger`, and it recovers `PaymentLifecycleService`'s failure. Both dependency directions between the two modules already exist (`PaymentLifecycleService` imports `booking.service`; `BookingService:113` imports `payment.repo.SessionPackPurchaseRepository`), so this adds no new coupling.
- Config keys are seeded by Flyway into `main.platform_config` with `ON CONFLICT (key) DO NOTHING` and explicit ids. Highest id in use is **600** (`V85`); `booking.request_expiry_hours` is 38 (`V31`) and `booking.quick_complete_timeout_hours` is 39 (`V33`).
- ShedLock is wired platform-wide (`V80__shedlock_table.sql`, `ShedLockConfigIT`); a new scheduler needs only the `@SchedulerLock` annotation with a unique `name`.
- Per project-context: request/response DTOs must be `record`s, every REST endpoint needs `@PreAuthorize` — **this story adds no endpoints and no DTOs**, so neither rule is triggered. All schema change goes through Flyway; no `ddl-auto` reliance.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` §`Deferred from: skillars-deferred-14 story creation (2026-08-05)` D1 — the sweeper]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` §`Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity (2026-08-05)` D1, D2, D4]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` §`Deferred from: adversarial code review of skillars-7-2 Group 2 Service Layer (2026-06-24)` D2]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-14-moderation-listener-batch-overlap-integrity.md` — AC3a's residual-window analysis, the per-booking `REQUIRES_NEW` pattern, and the rejected "listener as sole writer" mitigation]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-13-admin-moderation-action-integrity.md` — the mutation-verification standard for concurrency tests]
- [Source: `_bmad-output/implementation-artifacts/sprint-status.yaml` — `skillars-deferred-12` note on `REQUIRES_NEW` per-item settlement]
- [Source: `src/main/resources/db/migration/V87__booking_overlap_exclusion_constraint.sql` — why `PAYMENT_PENDING` holds a slot]
- [Source: `src/main/resources/db/migration/V88__session_pack_purchases_parity.sql` — the `expired_notified_at` column + partial-index pattern AC6 mirrors]

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code, `bmad-dev-story` workflow)

### Debug Log References

**Task 1 — scheduler audit (result differs slightly from the story's count).** `grep -rn "@Scheduled" src/main/java` returns **29 occurrences across 27 files**; the story says "27 schedulers", which was counting files. Every one of those 27 files was checked for a `PAYMENT_PENDING` read: **zero hits**. The only `PAYMENT_PENDING` references anywhere in `src/main` are `BookingStatus`, the two `ACTIVE_SLOT_STATUSES`/`POST_ACCEPTANCE_STATUSES` lists, `BookingStateMachine`'s transition map, and comments. AC1 stands as scoped; no re-scope needed.

**Task 1 — pre-fix reproduction (`StrandedPaymentPendingProbeIT`, run against unfixed code, both cases passed).**
- A booking seeded in `PAYMENT_PENDING` with `updated_at` 30 days old and no `payment.booking_payments` row survived `BookingExpiryScheduler.expireStaleRequests()` unchanged — still `PAYMENT_PENDING`, still no payment row. Nothing recovers it.
- `createBookingRequest` for the same coach and the same window was rejected with `BookingError.SLOT_UNAVAILABLE` — the user-visible harm, observed rather than argued.
- Bonus confirmation the story did not predict: Postgres itself rejected a *second* seed at that slot with `ERROR: conflicting key value violates exclusion constraint "excl_bkg_coach_slot_overlap"`. The slot is held at DB level, not only by the app-layer check.
- The probe file was deleted once `PaymentPendingSweeperIT` covered the same ground; its "no other scheduler recovers this" case survives there as `noOtherSchedulerRecoversAStrandedBooking`.

**Task 1 — testing trap found while running the probe, and it would have invalidated later assertions.** The probe's log carried `ShedLockConfig - Scheduler lock 'BookingExpiryScheduler_expire' is held by another instance — skipping this run`. Invoking a `@SchedulerLock` method from a test goes through the Spring proxy, so ShedLock applies; with `lockAtLeastFor = "PT2M"` (universal here) **a second invocation in the same test class is silently skipped**. `SessionPackExpiryWarningIT`'s dedupe test calls the notifier twice and would have "passed" without the dedupe fix existing at all. Added `BasePaymentIT.releaseSchedulerLock(name)` and routed every scheduler invocation in both new ITs through a helper that calls it first.

**Task 7 — pre-fix expiry-warning behaviour (run against unfixed code).** With a pack seven days from expiry and four sessions remaining, `notifyExpiringPacks()` produced **zero** `SESSION_PACK_EXPIRY_WARNING` envelopes: the probe asserted one and failed with `expected: 1L but was: 0L`. Story creation's prediction is confirmed by execution; `skillars-7-2` Group 2 D2's "up to 14 emails" was wrong about the current behaviour. AC6 shipped both halves as specified.

### Completion Notes List

All 7 ACs implemented. Two corrections to the spec, one correction to a Task instruction, and one testing defect found in my own first attempt — all below.

**Mutation verification (required by AC3, AC4 and Task 10).** Four fixes were reverted simultaneously and the affected ITs re-run; each test failed in the expected way, then passed again once restored:

| Fix reverted | Test | Result under mutation |
|---|---|---|
| `acceptReschedule`'s locked re-read of the reschedule row | `RescheduleResourceIT.acceptReschedule_declineCommitsWhileAcceptWaitsOnTheLock_acceptFailsAndDeclineStands` | **FAILED** — "the accept must fail once its locked re-read sees the committed decline" |
| `acceptBooking`'s `refresh` + SUSPENDED check | `BookingServiceConcurrencyIT.acceptBooking_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable` | **FAILED** — accept succeeded against a suspended coach |
| same | `SuspendedCoachBookingBlockIT.suspendedCoachAcceptingSingleBooking_returns403WithCoachUnavailableCode` | **FAILED** — no 403 thrown |
| sweeper's pack-funded precondition | `PaymentPendingSweeperIT.creditFundedStrandedBooking_isReportedNotDeclined` | **FAILED** — the sweeper declined a booking whose money may already be at Stripe |

**AC5's IT does not discriminate, and no IT at that level can — this corrects Task 6.** Task 6 says to "assert the pre-fix value (`FULLY_ACCEPTED`) first so the test is known to discriminate". It would not have shown `FULLY_ACCEPTED`: with the naive formula restored, `BookingBatchResourceIT.acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted` **still passed**. The reason is structural, and it is the same self-correction Deferred-14's Dev Notes described: the naive formula is only wrong when every `REQUESTED` booking was accepted, which is exactly the condition under which `updateBatchStatusFromBooking` stops early-returning — settlement republishes `BookingStatusChangedEvent`, the listener recomputes over all rows after the trailing commit, and repairs the value before the response is observable. The real exposure is the transient wrong read in between. AC5's discriminating evidence is therefore the unit test `BookingBatchServiceTest.acceptAll_batchAlreadyContainsADeclinedBooking_endsPartiallyAccepted`, where no listener exists to clean up: under the same mutation it fails with `expected "PARTIALLY_ACCEPTED" but was "FULLY_ACCEPTED"` — the exact pre-fix value. Both tests are kept and the IT's javadoc records that it is an end-state guard only, so no future reader mistakes it for proof.

**AC4 needed more than the spec asked for, on the batch path.** AC4 (and `deferred-14` review D2 before it) specifies a locked re-check inside `acceptOneBooking`. That is necessary but not sufficient: `acceptAll` catches per-booking exceptions so one bad slot cannot fail the batch, so the locked throw is swallowed and a suspended coach's `acceptAll` returns a silent no-op instead of the 403 the AC requires. Shipped fix carries both — an **unlocked** status check in `acceptAll` for the clean `booking.coachUnavailable`, plus the locked per-booking check for the race. The `acceptAll` check deliberately does **not** take the coach lock: doing so would make every per-booking `REQUIRES_NEW` transaction block on a lock its own caller holds, on a different connection, until the 5s timeout.

**AC3 needed an `entityManager.refresh` the spec did not mention.** AC3 asks only for `findByIdForUpdate` plus a re-check. But `acceptReschedule` already loads the reschedule row via `findById` earlier in the method, and `findByIdForUpdate` is JPQL — Hibernate takes the DB lock and returns the *same managed instance* with its stale in-memory status. Re-checking without a refresh would read `PENDING` off the stale object and could never fire, the same trap `BookingService.createBookingRequest:203-207` documents for the coach row. Both the reschedule row and the coach row now refresh in `acceptReschedule`; `BookingBatchService.acceptOneBooking` deliberately does not, because its `REQUIRES_NEW` transaction has a fresh persistence context — commented at the call site rather than left for the reader to infer.

**A testing defect I introduced and then had to fix — worth reading before writing the next scheduler test.** Calling a `@SchedulerLock` method from a test goes through the Spring proxy, so ShedLock applies; with `lockAtLeastFor = "PT2M"` every invocation after the first in a test class is silently skipped. My first fix deleted the `main.shedlock` row, which is **worse than doing nothing**: `JdbcTemplateLockProvider` caches the lock names it has inserted and thereafter issues only `UPDATE … WHERE lock_until <= now()`, so with the row gone that UPDATE matches nothing and *every* later run is skipped. The first run of `PaymentPendingSweeperIT` had 5 of 6 sweeps skipped — and the three negative cases (b), (c), (d) **passed vacuously** while the two positive cases failed, which is exactly the shape that would have shipped a sweeper nobody had actually exercised. `BasePaymentIT.releaseSchedulerLock` now backdates `lock_until` instead, which works on both the insert and update paths. `ShedLockConfigIT` failed during the DELETE-based run and passes both alone and alongside these ITs with the UPDATE-based version.

**Verified alongside, no regressions:** `BatchAcceptPaymentIT`, `BatchPaymentIT`, `SessionPackPurchaseIT`, `PackExtensionIT`, `SessionPackForfeitureSchedulerTest`, `BookingExpirySchedulerTest`, `ShedLockConfigIT`. `V90` applies cleanly on a fresh Testcontainers database and `idx_session_pack_purchases_expiry_warn` does not collide with `V88`'s `idx_session_pack_purchases_expiry_notify`; config id 601 is unused elsewhere (600 in `V85` was the previous maximum).

**Deliberately not done:** the credit-funded half of the sweep. It stays open as `deferred-work.md` → `skillars-deferred-15 story creation` D1 and is the reason `PaymentPendingSweeper` carries a class-level comment telling the next author not to "complete" it by dropping the pack-funded precondition.

### File List

**New (main)**
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
- `src/main/resources/db/migration/V90__payment_pending_sweep_and_pack_warning.sql`

**Modified (main)**
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRescheduleRequestRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java`

**New (test)**
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryWarningIT.java`

**Modified (test)**
- `src/test/java/com/softropic/skillars/platform/payment/BasePaymentIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/SuspendedCoachBookingBlockIT.java`

**Modified (docs)**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-deferred-15-payment-pending-sweeper-accept-path-integrity.md`

**Created then deleted:** `src/test/java/com/softropic/skillars/platform/payment/service/StrandedPaymentPendingProbeIT.java` — Task 1's pre-fix probe; its coverage lives on in `PaymentPendingSweeperIT`.

## Change Log

- 2026-08-05 — **Full `mvn -o verify` green after the review patches: 805 unit + 866 IT, 0 failures, 20:39 min.** One correction to the baseline this story was told to compare against: `deferred-14`'s recorded "812 unit" is inflated. `target/surefire-reports/` also holds report files for two integration tests (`SessionPackPaymentResourceIT` 6 + `SubscriptionLifecycleIT` 13 = **19**), so any naive sum over that directory double-counts them. Summing only the 94 classes surefire actually ran gives exactly 805, and 805 − 12 new tests = **793**, the true pre-story unit baseline (793 + 19 = the 812 that was being quoted). The IT baseline of 850 was correct: 850 + 16 new = 866. Whoever records the next story's totals should sum the classes that ran, not the directory.
- 2026-08-05 — **Code review addressed: 6 patches applied, 1 rejected, 4 deferred.** Zero AC violations were found, but the review caught a real hole in my own evidence and it is the finding worth remembering: **both locks I added for AC4/AC3 on the *writer* side — `AdminCoachEnforcementService.suspendCoach` and `RescheduleService.declineReschedule` — were completely untested.** Every existing test drove them sequentially, and my accept-side race tests staged their contending write with raw `SELECT … FOR UPDATE` SQL, a lock-shaped stand-in that passes just as well against a plain `findById`. Verified by mutation: reverting both to `findById` passed the entire suite. This is exactly the "decorative lock" failure deferred-13's review recorded, reproduced inside the story that cites it. Closed with two new tests using the real service as the method under test, both mutation-verified — the stale-read suspension redoes the suspension and leaves a duplicate `COACH_SUSPEND` audit row (`expected: 0 but was: 1`), the stale-read decline overwrites a committed accept. Also applied: ERROR log on the notifier's missing-coach branch, `lockAtMostFor` raised to `PT30M` for margin over the 15-minute cadence, benign optimistic-lock conflicts demoted from ERROR to INFO so the sweeper's ERROR channel keeps meaning "reconcile this by hand", a fast unit test for `acceptBooking`'s suspension check, and an explicit empty-list guard in `computeBatchStatus`. Rejected the dedupe/backoff proposal for `reportUnrecoverable`: repetition is the alert, and silencing an unresolved booking with money possibly captured at Stripe would be strictly worse than the noise.
- 2026-08-05 — **Implemented; all 7 ACs done.** Both pre-fix behaviours were reproduced before any code was written, as the story required, and both held: a booking stranded in `PAYMENT_PENDING` survives every scheduler *and* blocks the coach's slot (`booking.slotUnavailable`, with Postgres' `excl_bkg_coach_slot_overlap` rejecting a second seed independently), and the pack expiry warning is delivered **zero** times, not fourteen. Three things the spec did not have right: AC4's locked re-check alone leaves `acceptAll` returning a silent no-op for a suspended coach because the loop swallows the throw (an unlocked pre-check in `acceptAll` was added, deliberately not a locked one — see Completion Notes); AC3's re-check is inert without an `entityManager.refresh`, because `findByIdForUpdate` returns the already-managed stale instance; and Task 6's expectation that the AC5 IT would discriminate is wrong — the `AFTER_COMMIT` listener repairs the naive value before the response is observable, so AC5's proof is its unit test instead. Every concurrency test added by AC3 and AC4 was mutation-verified in both directions, plus the sweeper's credit-funded case; results tabulated in Completion Notes. Suites re-run for this diff beyond the totals: `BookingServiceConcurrencyIT`, `SuspendedCoachBookingBlockIT`, `BatchAcceptPaymentIT`, `BatchPaymentIT`, `BookingBatchResourceIT`, `RescheduleResourceIT`, `RescheduleServiceTest`, `SessionPackPurchaseIT`, `SessionPackForfeitureSchedulerTest`, `PackExtensionIT`, `ShedLockConfigIT`, `BookingExpirySchedulerTest`.
- 2026-08-05 — **AC1/AC2 corrected after review of the draft.** The draft specified two money-safety guards and claimed they made auto-decline safe for every stranded booking. Tracing the transaction boundaries showed both claims were wrong. (1) **Stripe-funded bookings were entirely unguarded**: `chargeAndCapture` (single, `PaymentLifecycleService:102`) and `chargeAndCaptureForBatch` (batch, `:190`) both run *before* the DB writes that record them, so a crash in between leaves money captured with no `booking_payments` row and no ledger entry — the draft's sweeper would have declined already-charged bookings. (2) **The credit-ledger guard's justification was backwards**: `CreditWalletService.writeLedgerEntry` is plain `@Transactional`, so the batch-level `BOOKING_DEDUCTION` joins the listener transaction and commits *after* the per-booking `REQUIRES_NEW` confirms — in the crash window it is meant to detect, it has rolled back too. Confirmed there is no substitute signal: no capture-lookup on `PaymentGateway`, no `payment_intent.*`/`charge.*` webhook handler, and the gateway's only durable write (`stripeCustomer.lastPaymentIntentId`) is transactional and per-parent. AC1 now auto-declines **only pack-funded** bookings (that path never calls Stripe) and report-only-logs everything else; the credit-ledger guard and its repository method are removed; AC2 case (d) changed from a ledger fixture to a credit-funded booking that must survive the sweep. The residual and the pre-capture-record change that would close it are recorded as a new `deferred-work.md` item.
- 2026-08-05 — Story created. Five deferred items grouped: the `PAYMENT_PENDING` sweeper (deferred-14 story creation D1), three items from deferred-14's code review (reschedule accept/decline TOCTOU, missing suspension check at three accept paths, batch-status formula divergence), and the session-pack expiry-warning notifier (7-2 Group 2 D2). All re-verified against `src/main` at commit `cc8d2a8`. Two ledger claims corrected during creation: the suspension item is broader than filed (no check exists at all, and the suspension writer holds no lock), and the expiry-warning item's observable behaviour is **zero** emails rather than fourteen, because the event is published with no transaction bound and its `AFTER_COMMIT` listener discards it.
