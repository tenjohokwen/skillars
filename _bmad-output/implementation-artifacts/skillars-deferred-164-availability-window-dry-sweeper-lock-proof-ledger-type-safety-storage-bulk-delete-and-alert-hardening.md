# Story skillars-deferred-164: Availability-window DRY, sweeper lock proof, ledger type-safety, storage bulk-delete and alert hardening

Status: done

<!-- CREATED 2026-10-27 (/bmad-create-story). User-requested bundle mined from deferred-work.md — 8
     user-selected buckets, re-verified against HEAD b5680880 before drafting (see "Verified state at
     drafting"). Every item was re-read in the live ledger text AND the live source; several no longer
     match the ledger's own framing — see "Owner decisions" and "Closed by verification, no code"
     below. Ledger line numbers shift constantly; locate bullets by the quoted lead text, not by number. -->

## Story

As a maintainer of the Skillars platform,
I want the oldest still-open ledger residuals around availability-window duplication, the
sweeper-lock reasoning-not-test gap, stringly-typed money/status fields, storage bulk-delete and
alert-listener hardening closed or explicitly re-decided,
so that a genuine fourth/fifth duplicated validation block stops drifting, the payment-sweeper lock
has a real regression guard instead of a documented argument, ledger-entry types stop being
free-text, and the ledger stops re-offering items that are either already decided elsewhere or not
real defects.

## Owner decisions (2026-10-27)

Resolved with the user before drafting (each re-verified against HEAD first — see "Verified state
at drafting"):

- **D164-A — `DrillMetadata.repDensity` (AC13): leave documented, no code.** Re-confirmed
  unreachable for a third time (deferred-63 audit, deferred-76 AC10's own code comment, now this
  story) — no live endpoint accepts coach-supplied `DrillMetadata`, so the "unset" case cannot occur
  in production today.
- **D164-B — `VideoModerationScan.scannedAt` (AC14): leave documented, no code.** The real fix
  (append-only per-attempt rows) is architectural scope beyond one story, per the ledger's own
  framing; re-confirmed, re-annotated.
- **D164-C — `AlertNotificationListener` hardening (AC12): implement now**, despite being moot while
  `AlertEvaluationService.computeMetricValue` still returns `-1.0` (no `AlertFiredEvent` is ever
  published). Same rationale already applied to AC1 of skillars-deferred-161.
- **D164-D — `SessionPackPurchase.expiresAt` (AC5): encapsulate, do NOT add `updatable=false`.**
  Verified `PackSessionService.java:310` (`purchase.setExpiresAt(purchase.getExpiresAt().plus(...))`)
  actively extends expiry during pause/resume — `updatable=false` would silently stop that column
  from persisting and break the pause feature. Narrow the entity's public surface instead.

Two further items turned out to contradict the ledger's own paraphrase once checked against the
live source — resolved here rather than re-asked, since each matches an already-established,
documented project convention (see each item's AC for the evidence):

- **D164-E — `BookingPayment.status` / `Booking.status` stay plain `String`, no Java enum.**
  `BookingPaymentStatus.java`'s own class Javadoc already documents a deliberate "Not an enum"
  decision (VARCHAR compared as string in JPQL/native queries/fixture SQL). Converting it now would
  reverse that recorded decision. Only `ParentCreditLedger.type` (no such prior decision exists for
  it) gets the type-safety treatment — see AC3.
- **D164-F — `PackSessionService.CONFLICT_STATUSES`: derive from `BookingStatus` enum names, not a
  new enum.** Same VARCHAR-string convention as D164-E; `Booking.status` is a plain `String` column.
  The fix removes the duplicated literal spelling without introducing a second status-typing scheme.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets below live in `_bmad-output/implementation-artifacts/deferred-work.md`. On completion the
dev **deletes** closed bullets and re-annotates anything closed by decision/verification rather than
code (file convention, same as skillars-deferred-161/163).

| AC | Ledger bullet (lead text) | Section |
|----|---------------------------|---------|
| 1 | "D11 … `PaymentPendingSweeper.sweepOne`'s row lock is justified by reasoning, not by a test" | code review of skillars-uat-3 (2026-08-11) |
| 2 | "Validation logic … is duplicated near-verbatim across three call sites" | code review of skillars-deferred-49 (2026-08-21) |
| 3 | "D4: Raw `String` fields for `type` (ParentCreditLedger) and `status` (BookingPayment)" (ParentCreditLedger half only — see D164-E) | code review of skillars-7-2 (2026-06-24) |
| 4 | "D9: Stringly-typed computed `status` field and hardcoded `CONFLICT_STATUSES` list" (CONFLICT_STATUSES half only) | code review of skillars-11-1 (2026-08-03) |
| 5 | "D3: `SessionPackPurchase.expiresAt` mutable with no `updatable=false`" | adversarial code review of skillars-7-2 Group 1 (2026-06-24) |
| 6 | "Phone-collision detection via `msg.contains(\"phone\")`" | code review of skillars-1-4-parent-registration-player-profiles-shadow-accounts Group 1 (2026-06-12) |
| 7 | "W3: `parentName` null in `getParentBookings()`" | code review of skillars-3-9 (2026-06-16) |
| 8 | "Scan-equivalence counts (1571 literal / … ) are only ever checked manually" | code review of skillars-deferred-158 (2026-10-22) |
| 9 | "No status enum ↔ CHECK parity guard" | code review of ses-1-5 (2026-10-24) |
| 10 | "Frontend root `happy-dom` is pinned `^15.11.6`" | code review of skillars-deferred-161 (2026-10-25) |
| 11 | "No bulk-delete capability in the storage stack" (R1) | skillars-deferred-90 story creation (2026-09-02) |
| 12 | "`AlertNotificationListener` hardening" (three gaps) | code review of skillars-deferred-161 (2026-10-25) |
| 13 | "D1 — `DrillMetadata.repDensity` cannot represent 'coach never set this'" | skillars-deferred-26 story creation (2026-08-15), re-audited skillars-deferred-63 and deferred-76 AC10 |
| 14 | "RW2: scanned_at misleading on upsert retry path" | post-implementation review of skillars-6-3 (2026-06-22) |

## Verified state at drafting (HEAD b5680880)

- **AC1**: `PaymentPendingSweeper.sweepOne` (`:141-188`) already takes the fix the ledger bullet
  describes — `lockRetryer.withBoundedRetry(..., () -> bookingRepository.findByIdForUpdate(bookingId))`
  — and `BookingPaymentPersistenceService.reserveCaptureTransactional` (`:126`) takes the identical
  `PESSIMISTIC_WRITE` lock on the same row (`REQUIRES_NEW`, per the "DEADLOCK CONSTRAINT" comments in
  `PaymentLifecycleService.java:210,296`). What's missing is only the **test** proving the lock
  serialises the two — not a code fix.
- **AC2**: counted the *live* call sites of `BookingService.isSlotWithinAvailabilityWindow` — there
  are now **five**, not three: `BookingService.createBookingRequest` (`:290`, the original),
  `RescheduleService.validateRescheduleProposal` (`:223`, shared by `requestReschedule`/
  `requestRescheduleAsCoach`), `RescheduleService.acceptRescheduleShared` (`:409`, shared by
  `acceptReschedule`/`acceptRescheduleAsParent`), `BookingDuplicationService.duplicateNextWeek`
  (`:92`), and `BookingBatchService.validateSlotDurationAndAvailability` (`:285`, new since the
  ledger bullet was written — its own doc comment at `:270` explains it calls the method directly
  rather than copying the *window-check logic*, but it still duplicates the surrounding
  fetch→call→throw `SLOT_OUTSIDE_AVAILABILITY` idiom). The deferred-49 decision to leave this alone
  was explicitly conditioned on "revisit if a fourth caller ever appears" — counted the way the
  original bullet counted (3, excluding `createBookingRequest`), it is now 4; counted including the
  original call site, it is 5. Either way the stated revisit threshold is past, so this AC proceeds.
  The five sites' messages are **not** identical — see AC2 and H1-equivalent note below — so the
  extraction must preserve each site's own wording, not normalize it.
- **AC3**: `ParentCreditLedger.type` (`ParentCreditLedger.java:39`, plain `String`) is written from 6
  bare string literals across 5 files: `CASH_OUT_DEBIT`/`STRIPE_FEE_DEBIT`/`CASH_OUT_REVERSAL`
  (`CreditWalletService.java:32-33`, `CashOutService.java`), `BOOKING_REFUND`
  (`CreditWalletRefundOutboxHandler.java:47`), `BOOKING_DEDUCTION`/`BOOKING_DEDUCTION_REVERSAL`
  (`BookingPaymentPersistenceService.java:254,303`, `PaymentLifecycleService.java:339,351`).
  **Correction**: a DB `CHECK` constraint DOES exist — `chk_ledger_type`
  (`V138__baseline_schema.sql:1823`), enumerating exactly the same 6 values as a stale literal
  SQL array (plus `chk_ledger_amount_sign` at `:1818-1822`, unrelated). No migration is needed to
  *introduce* the Java constants (the constraint already matches), but the new constants-holder class
  must carry a Javadoc note that adding a 7th value requires widening `chk_ledger_type` in a
  migration — the same warning `BookingPaymentStatus` would need if it ever grew. Additional write/read
  sites missed at first pass, also needing the constant: `admin/service/DisputeService.java:231,244,254`
  (3× `"BOOKING_REFUND"` — this module is `platform.admin`, so the constant class must be `public`,
  which it already is per the plan below), the read at `CreditWalletRefundOutboxHandler.java:42`
  (`existsByReferenceIdAndType(..., "BOOKING_REFUND")`), and the JPQL literals in
  `ParentCreditLedgerRepository.java:20,41,44` (constant string concatenation into `@Query` is legal in
  a Java annotation attribute — use it there too). `CreditStatementPage.vue:54` (frontend) is a
  separate, Vue-side literal that stays out of scope for this Java-side AC — note this rather than
  silently leaving it unmentioned. `BookingPayment.status` is a different story — see D164-E:
  `BookingPaymentStatus.java` already documents, in its own class Javadoc, a deliberate decision to
  stay a plain-`String` constants holder (not a Java enum) because the column is compared as a string
  in JPQL/native/fixture SQL. Converting it now would reverse that recorded decision.
- **AC4**: `PackSessionService.CONFLICT_STATUSES` (`:51`) is
  `List.of("REQUESTED", "ACCEPTED", "CONFIRMED", "UPCOMING")` — these are 4 of
  `BookingStatus`'s (an existing Java enum, `booking/contract/BookingStatus.java`) 18 constants, used
  only to build a `List<String>` argument to `BookingRepository.findConflictingBookingsForPause`.
  `Booking.status` itself is a plain `String` column (`Booking.java:48`, no `@Enumerated`) — same
  VARCHAR-as-string convention as `BookingPaymentStatus`, so the fix is deriving the literal list from
  the enum's names, not introducing `@Enumerated` on the entity.
- **AC5**: `SessionPackPurchase.expiresAt` (`SessionPackPurchase.java:49-50`,
  `@Column(name = "expires_at", nullable = false)`, mutable) is written from **three** sites, not
  one — re-grepped and corrected after drafting: `PackSessionService.java:310` (pause, extends by the
  pause duration), `SessionPackPaymentService.java:173` (`extendPack`, +30 days), and
  `SessionPackPaymentService.java:280` (`createPurchase`, now+60 days). The Lombok `@Setter` on the
  entity is class-level (`:22`), and the entity lives in `payment.repo` while all three callers live
  in `payment.service` — so a package-private entity method would not even be visible to them as
  written; it would need to move the setter's access or add a method the callers *can* see. This
  still confirms D164-D's conclusion (no `updatable=false`), since there are now three live write
  paths needing the column mutable, not just one — but the "exactly once" / "package-private"
  framing below (AC5) is corrected accordingly.
- **AC6**: `ParentRegistrationService.java:100-108` catches `DataIntegrityViolationException` and does
  `ex.getMessage().toLowerCase().contains("phone")` to decide between `security.phoneInUse` and
  `security.emailInUse`. The real unique constraint is named `user_phone_key`
  (`V138__baseline_schema.sql:2727`, `UNIQUE (phone)`). The established project pattern for this exact
  problem already exists three times: `SluPersistenceRetrier.isSessionSkillUniqueViolation`,
  `ReviewFlagService`, `ReviewSubmissionService` — all do
  `ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve && CONSTANT.equals(cve.getConstraintName())`.
  **Correction, found on re-check**: that constraint-name check is **dead for a real DB collision**
  as written in all three precedents' spirit, unless the save is flushed inside the `try`.
  `ParentRegistrationService` is class-level `@Transactional`; `User`'s id is an app-assigned
  `@Tsid` (not DB-generated), so `userRepository.save(user)` alone does not force an immediate
  `INSERT` — the real constraint violation would only surface at commit, after the `catch` block has
  already exited. All three precedents this story mirrors call `saveAndFlush(...)` inside their
  `try`, not a bare `save(...)`, for exactly this reason. **AC6 must use `saveAndFlush`**, not plain
  `save`, or the fix changes nothing at runtime. Also note: `ApiAdvice`'s known-constraint-name list
  does not currently include `user_phone_key`, so today a real phone collision surfaces as a generic
  data error rather than any `phoneInUse`/`emailInUse` response — worth confirming this still works
  end-to-end once the flush is added. No existing test covers `phoneInUse` (0 hits grepping
  `src/test` for it) — AC6 is adding coverage, not just fixing a gap in existing coverage.
  **New finding, out of this story's scope**: `CoachRegistrationService.java:101-105` doesn't attempt
  phone/email discrimination at all — every `DataIntegrityViolationException` there reports
  `security.emailInUse`, so a phone collision during coach registration is misreported as an email
  collision, and it has the identical no-flush gap described above. Not touched here (ledger bullet
  only names the parent-side heuristic); worth its own ledger bullet for a future pass — see AC15.
- **AC7**: `BookingService.getParentBookings` (`:508-549`) passes a literal `null` for `parentName` at
  its `toResponse(...)` call (`:546`) — on inspection this is **intentional**: it's the parent viewing
  their own bookings, so their own name is never needed in the response. The *other* method,
  `getCoachBookingRequests` (`:551` onward), resolves real `parentName` values via
  `resolveParentNames`/`Unknown Parent` fallback because the coach genuinely needs to see them. No
  AC requires a parent's own name on their own bookings view — closing by verification, no code.
- **AC8**: `i18nTemplateKeyResolutionSpec.js:168` pins `MIN_LITERAL_CALL_SITES = 500`. The ledger
  bullet's figure (1571, as of 2026-10-22) is already stale — independently re-run via the spec's
  own `scanCallSites` logic, the live count at this drafting is **1641**. See AC8 for the corrected
  floor (~1550, not 1500).
- **AC9**: `EnvelopeEntitySchemaIT.emailTemplateCheck_allowsExactlyTheEmailTemplateEnumValues` (`:76+`)
  already does the `(constraint, enum)` parity comparison for `envelope_entity_email_template_check`
  ↔ `EmailTemplate`; `envelope_entity_status_check` (`V138__baseline_schema.sql:614`) is only asserted
  to exist (`envelopeEntity_hasExactlyOneCheckConstraintPerEnumColumn`), never compared against
  `EmailDeliveryStatus.values()` (`SENT, FAILED, SENDING, DELETE, DEADLINE_EXPIRED,
  ATTEMPTS_EXHAUSTED`).
- **AC10**: `src/frontend/package.json:49` — `"happy-dom": "^15.11.6"`.
- **AC11**: `StorageService` interface (`infrastructure/blobstore/service/StorageService.java`) has
  `put`/`get`/`delete(String)`/`exists`/`stat`/`copy` — no batch variant. Three implementations:
  `S3StorageService` (real AWS SDK, `delete(String)` at `:114-117` with `@Retryable`/`@Recover`),
  `LocalFileSystemStorageService` (`delete(String)` at `:59+`, `Files.deleteIfExists`),
  `ReplicatedStorageService` (`platform.filestorage`, delegates to `primaryStorageService.delete`).
  `platform.filestorage.service.FileStorageService.deleteRawBytes` (`:324`) is a **separate**,
  direct-S3-client single-key delete, outside the `StorageService` abstraction entirely — not in
  scope to merge here. **No current caller needs a bulk call**: the only multi-object-deletion path,
  `BlobDeletionOutboxHandler.handle` (`platform.filestorage.service`), processes exactly one storage
  key per generic-outbox row by that outbox's own one-row-at-a-time contract — wiring it to batch
  would mean redesigning the outbox's own drain/batching model, which is its own, unrelated change.
  This AC adds the capability only, per the ledger's own "still open deliberately" framing.
- **AC12**: `AlertNotificationListener.java` (`:56-95`) already resolves
  `platform.admin_alert_email` via `ConfigService.find(...)` inside the try (skillars-deferred-161
  AC1). The three gaps the ledger still lists are all live in the current code: (a) the blank-email
  branch (`:63-68`, status `EMAIL_NO_RECIPIENT`) logs ERROR on every fired alert with no throttle;
  (b) `adminEmail` is used as-is from `configService.find(...).orElse("")` with no `.trim()` and no
  format validation; (c) the outer `catch (Exception e)` (`:87-93`) logs **every** failure —
  including a `configService.find()` lookup failure — under the same `EMAIL_ERROR` status as a
  genuine `mailManager.sendEmailSync` transport failure, conflating "our config service is down"
  with "SES rejected the send." **Correction, found on re-check**: the ledger's gap-2 bullet names
  both `AlertNotificationListener` and its sibling `VideoModerationEmailListener` ("doesn't either")
  as sharing the no-`.trim()`/no-validation gap — AC12 as scoped below only touches
  `AlertNotificationListener`. `VideoModerationEmailListener` is deliberately left out of this AC
  (would roughly double Task 12's size for a second, unrelated listener) — see AC15 for how the
  ledger bullet gets split rather than fully closed.
- **AC13/AC14**: see Owner decisions D164-A/D164-B.

## Acceptance Criteria

1. **`PaymentPendingSweeper.sweepOne`'s row lock gets a real concurrency regression test.**
   **Corrected per story review (2026-10-27) — the original hook placement and assertion below were
   both wrong; this replaces them:**
   - **Hook placement**: `sweepOne` is `private`, so proving this needs a small, deliberate
     production change (not "no production code changes" — correct that framing). Add a
     package-private test-only seam — a settable `Runnable` field, default no-op, reset in a
     `finally`/test `@AfterEach` so a stuck hook never survives past one test — invoked **after** the
     `existing.isPresent()` early-return check (`:158-170`) and **before** `new BookingPayment()` /
     `bookingPaymentRepository.save(bp)` (`:177-182`). The actual race `sweepOne`'s comment describes
     is between that absence check and that save — not between the lock acquisition and the
     `bookingPaymentRepository.findById` read (placing the hook there lets B's `reserveCapture`
     commit before A's `findById` even runs, so A takes the safe `reportUnrecoverable` branch and the
     test stays green against the unlocked code too — verify this is exactly why an earlier IT for
     this was deleted, per the ledger bullet's own history and `PaymentPendingSweeperIT:302-308`).
   - **Assertion**: do **not** assert that the concurrent call "blocks". `findByIdForUpdate` uses
     `jakarta.persistence.lock.timeout=0` (NOWAIT, `BookingRepository.java:248-249`), and
     `PessimisticLockRetryer`'s bounded retry (defaults: max 8 attempts, 100ms initial backoff,
     1.6× multiplier, 800ms cap) means a concurrent lock attempt polls for roughly 2-3 seconds and
     then fails outright — it never blocks. With the hook holding `sweepOne`'s transaction open,
     assert instead: B's `reserveCaptureTransactional` call returns/throws reflecting a lock-acquire
     failure (e.g. surfaces as `BOOKING_NOT_PENDING` or whatever the retry-exhausted path maps to —
     confirm the exact outcome by reading `PessimisticLockRetryer`'s caller contract first); the
     `persistence.lock_retry.retries{...}` metric for that lock increments above 0; and once the hook
     releases and `sweepOne` commits, the final state is booking `DECLINED` and `booking_payments`
     row `CHARGE_FAILED` — not silently overwritten by whichever thread's Java statements happened to
     run first (this is the actual bug class: `BookingPayment` has an assigned `@Id`/no `@Version`,
     so an unlocked `save` would merge over a concurrently-committed row).
   - **Test hygiene**: use a `CountDownLatch`/timeout (a couple of seconds, not an unbounded wait) so
     a wrong implementation fails fast instead of hanging the suite; always reset the hook field in
     `finally` — a stuck hook holds the booking row lock and a connection, which can hang
     `DatabaseResetTestExecutionListener`'s truncate between tests.
   - **Mutation-test it manually** before committing: temporarily replace `findByIdForUpdate` with a
     plain `findById` in `sweepOne`, confirm the new test goes **red**, then restore and confirm
     green. This is the real regression guard — the lock mechanism itself already exists (see
     "Verified state at drafting"); only the test and the small seam enabling it are new.
   - While in this method, correct the stale `"5 s lock.timeout hint"`-style comments at
     `BookingPaymentPersistenceService.java:100` and `PaymentLifecycleService.java:89` — the real
     value is NOWAIT (`0`), not 5 seconds.
2. **Extract a shared `requireSlotWithinAvailabilityWindow(...)` helper covering all five call
   sites.** `BookingService.createBookingRequest`, `RescheduleService.validateRescheduleProposal`,
   `RescheduleService.acceptRescheduleShared`, `BookingDuplicationService.duplicateNextWeek`, and
   `BookingBatchService.validateSlotDurationAndAvailability` each independently call
   `isSlotWithinAvailabilityWindow(...)` and then throw `OperationNotAllowedException(...,
   BookingError.SLOT_OUTSIDE_AVAILABILITY)` on failure. **Correction per story review**: the five
   sites' detail payloads are not "byte-for-byte" identical modulo key names — the exception
   *messages* themselves differ ("Requested slot…" at `BookingService`/`BookingBatchService` vs
   "Proposed slot…" at both `RescheduleService` sites/`BookingDuplicationService`), and
   `RescheduleService.acceptRescheduleShared` (`:413`) has a **third** detail-map key
   (`"submitted coach id"`) the other four don't have. Design the helper's signature around this:
   take the start/end/windows/coachId/timezone plus a wording selector (an enum or small record
   covering the message text + the detail-map keys to use, including an optional extra key/value for
   the `RescheduleService` case) rather than two bare label strings. All five call sites are in
   `platform.booking.service`, so a package-private helper on `BookingService` is visible to all of
   them without widening access. Preserve each site's **exact** existing message text and map keys —
   this is not a chance to also normalize the wording (an unrequested, unreviewed message-contract
   change for every client surfacing these errors). `BookingBatchService`'s site is prefetched by
   `createBatch` before the call, unlike the other four — keep that prefetch where it is; only the
   validate→throw idiom moves into the shared helper, not the fetch.
   - **Mock-based unit test risk (must handle, not skip)**: `RescheduleServiceTest`,
     `BookingDuplicationServiceTest`, and `BookingBatchServiceTest` each declare `@Mock
     BookingService`, stub `isSlotWithinAvailabilityWindow(...)` to return `false`, and assert/verify
     the resulting `SLOT_OUTSIDE_AVAILABILITY` throw (e.g. `BookingDuplicationServiceTest:222`,
     `BookingBatchServiceTest:198,262`, with `verify(...)` at `RescheduleServiceTest:120`,
     `BookingDuplicationServiceTest:122`, `BookingBatchServiceTest:228` `times(2)`). A mocked
     `void requireSlotWithinAvailabilityWindow(...)` on the mocked `BookingService` never throws by
     default, so these tests would silently stop testing what they claim to unless addressed. Resolve
     by either (a) keeping `isSlotWithinAvailabilityWindow(...)` as the one method these callers
     invoke on their `BookingService` collaborator, with the new package-private helper doing the
     `throw` *inside* `BookingService` itself and the four external callers calling a thin wrapper
     that still appears on the mock as a stubbable boolean-returning call, or (b) explicitly rewriting
     all three test classes' stubs/verifies to the new method shape. Pick one approach and apply it
     consistently; list the exact test changes in Task 2, don't discover them mid-implementation.
   - `BookingServiceTest:709-840` already calls `isSlotWithinAvailabilityWindow(...)` directly and
     the new helper itself will call it — so it is **not** true that it ends up "used standalone
     nowhere" after this change; keep the method as the shared boolean primitive both the helper and
     those direct tests use, don't delete or hide it.
3. **`ParentCreditLedger.type` gets a type-safety treatment; `BookingPayment.status` stays as-is (D164-E).**
   Add a `public final class CreditLedgerEntryType` (mirroring `BookingPaymentStatus`'s own style —
   `private` constructor, `public static final String` constants, a short class-level Javadoc
   explaining why it is a constants holder and not a Java enum, same VARCHAR-as-string reasoning) in
   `payment/contract/`, with constants `CASH_OUT_DEBIT`, `STRIPE_FEE_DEBIT`, `CASH_OUT_REVERSAL`,
   `BOOKING_REFUND`, `BOOKING_DEDUCTION`, `BOOKING_DEDUCTION_REVERSAL` (the 6 literals found at
   drafting — grep again at dev time for any missed, including `admin/service/DisputeService.java`
   (3 `"BOOKING_REFUND"` write sites, cross-module — the class must stay `public`),
   `CreditWalletRefundOutboxHandler.java:42` (a read, `existsByReferenceIdAndType`), and the JPQL
   literals in `ParentCreditLedgerRepository.java:20,41,44`). Replace every bare-string call site with
   the constant. No DB migration needed to introduce the constants — a `chk_ledger_type` CHECK
   constraint already exists (`V138__baseline_schema.sql:1823`) enumerating exactly these 6 values —
   but the new class's Javadoc must state that adding a 7th constant requires a migration widening
   `chk_ledger_type` first (mirror the equivalent caution `BookingPaymentStatus` would need).
   Re-annotate the `BookingPayment.status` half of the ledger bullet `[DECIDED]`, citing
   `BookingPaymentStatus.java`'s own documented rationale — do not touch `BookingPayment.status` or
   `Booking.status`.
4. **`PackSessionService.CONFLICT_STATUSES` derived from `BookingStatus`, not re-typed literals.**
   Replace `List.of("REQUESTED", "ACCEPTED", "CONFIRMED", "UPCOMING")` with
   `List.of(BookingStatus.REQUESTED.name(), BookingStatus.ACCEPTED.name(), BookingStatus.CONFIRMED.name(), BookingStatus.UPCOMING.name())`
   (or equivalent `Arrays.stream(...).map(Enum::name)`), so a future rename of a `BookingStatus`
   constant fails to compile here instead of silently drifting. `Booking.status` remains a plain
   `String` column (D164-F) — no entity/migration change. Note only, out of scope: the resulting
   list still excludes `PAYMENT_PENDING` — that's the existing behavior, unchanged by this AC, not a
   defect this story is fixing.
   **Correction per story review**: this AC closes only the `CONFLICT_STATUSES` half of ledger bullet
   D9. The bullet's *other* half — a separately stringly-typed **computed** status
   (`"EXHAUSTED"`/`"PAUSED"`/`"EXPIRED"`/`"ACTIVE"` in `SessionPackPaymentService.computeStatus`,
   surfaced via `SessionPackPurchaseResponse`) — is a different piece of code from `Booking.status`
   and is **not** addressed by this story (D164-E only covers `BookingPayment.status`/`Booking.status`,
   which was never actually the D9 bullet's other half). AC15 below re-annotates D9 to close only the
   `CONFLICT_STATUSES` half and leave the computed-status half open under its own, separate bullet.
5. **`SessionPackPurchase.expiresAt` encapsulated, no `updatable=false` (D164-D).** Keep the column
   mutable — **three** live write paths need it, not one: `PackSessionService.java:310` (pause),
   `SessionPackPaymentService.java:173` (`extendPack`, +30d), `SessionPackPaymentService.java:280`
   (`createPurchase`, now+60d; this is initial-set, not really a "mutation", but uses the same
   setter). Both services are in `payment.service`, the entity in `payment.repo`, so a
   package-private entity method is **not** a smaller surface than today's `@Setter` from either
   caller's point of view — drop that option. Pick one: (a) add a Javadoc note on the entity's
   `expiresAt` field/setter naming all three call sites and stating it's a service-layer invariant,
   not a free-for-all setter, and leave the public Lombok `@Setter` as-is (cheapest, no test churn);
   or (b) add a **public** `extendExpiryBy(Duration)` method on the entity that the two "extend" call
   sites use (pause, `extendPack`) while `createPurchase`'s initial set can keep using the bare
   setter or a small `initializeExpiry(Instant)` method, and only remove the general-purpose
   `@Setter` if a check first shows how many test call sites reference it directly (roughly 30, per a
   `grep -rn setExpiresAt src/test` at drafting time — removing it is a bigger, separate change; don't
   do it as a side effect of this AC unless that count turns out much smaller at dev time). Do not
   add `@Column(updatable = false)`. Drop any "resume" framing — `PackSessionService` has no resume
   path today, only pause.
6. **`ParentRegistrationService` phone-collision detection uses the real constraint name, not message
   sniffing.** Replace
   `ex.getMessage() != null ? ex.getMessage().toLowerCase() : ""` / `.contains("phone")` with the
   established pattern: catch `DataIntegrityViolationException`, check
   `ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve &&
   "user_phone_key".equals(cve.getConstraintName())` → `security.phoneInUse`, else →
   `security.emailInUse`. Mirror `SluPersistenceRetrier.isSessionSkillUniqueViolation`'s exact shape
   (private static helper, same unwrap depth). **Required, not optional**: change
   `userRepository.save(user)` to `userRepository.saveAndFlush(user)` inside the `try` — without the
   flush, the constraint violation surfaces at commit time, after the `catch` has already exited, so
   the new constraint-name check would never actually run against a real collision (all three
   mirrored precedents use `saveAndFlush` for this exact reason). Add **both**: (a) a unit test
   asserting a non-phone `DataIntegrityViolationException` (e.g. email unique violation, or a
   constraint-name-less generic one) still falls through to `security.emailInUse`, and a
   `user_phone_key`-named violation maps to `security.phoneInUse`; and (b) an integration test that
   actually registers two parents with different emails and the same phone number end-to-end and
   asserts the real `security.phoneInUse` response — a mocked-exception unit test alone would
   certify a code path that, pre-flush-fix, production never reached. While here, confirm whether
   `ApiAdvice`'s known-constraint-name list needs `user_phone_key` added for the end-to-end response
   to actually come back as `phoneInUse` rather than a generic data error. Do **not** touch
   `CoachRegistrationService` (new, separate finding, same no-flush gap — leave for a future ledger
   pick-up per "Verified state at drafting" AC6 note and AC15).
7. **Closed by verification, no code.** `getParentBookings`'s `null` `parentName` is intentional — a
   parent does not need their own name in their own bookings view (`getCoachBookingRequests`, the
   method that genuinely needs parent names, already resolves them correctly). Re-annotate the ledger
   bullet `[DECIDED — verified, no defect]` with this story's id and the one-line reasoning above. No
   code.
8. **Raise `MIN_LITERAL_CALL_SITES` in `i18nTemplateKeyResolutionSpec.js` from 500 to roughly 1550.**
   **Correction per story review**: the live count at drafting, re-run via the spec's own
   `scanCallSites` logic from a scratch copy, is **1641** — not the ledger bullet's 1571 (the ledger
   number has drifted since 2026-10-22). 95% of 1641 ≈ 1559, so a round **1550** is the right floor
   given the assertion is a strict `toBeGreaterThan` — not the story's originally proposed 1500, which
   is looser than the project's own 95% rule once the real count is accounted for. Re-count the live
   literal call sites at dev time (the test's own counting logic, not a manual grep) immediately
   before picking the exact floor, since the count can keep moving — pick ~95% of whatever that
   number is, not a number copied from this story. This still catches the kind of regression the
   ledger bullet warns about (a drop to 600 passing unnoticed) with headroom for ordinary future
   additions. If the live count has moved meaningfully since this drafting, set the floor to roughly
   95% of the current count instead of hardcoding 1550 — record
   whichever number is used and why in Completion Notes.
9. **Generalize `EnvelopeEntitySchemaIT`'s parity test to cover `envelope_entity_status_check` ↔
   `EmailDeliveryStatus`.** Extend (or parameterize) the existing
   `emailTemplateCheck_allowsExactlyTheEmailTemplateEnumValues` pattern so it also compares
   `envelope_entity_status_check`'s live constraint definition against `EmailDeliveryStatus.values()`
   in both directions (missing-from-constraint and missing-from-enum), exactly as the email-template
   case already does. Keep `envelopeEntity_hasExactlyOneCheckConstraintPerEnumColumn`'s existence
   check as-is (still useful as the "exactly these two constraints exist" guard) — add the new
   parity assertion alongside it, do not replace it.
10. **Bump frontend `happy-dom` from `^15.11.6` to `^20.11.6` in `src/frontend/package.json`.**
    Use `^20.11.6` specifically — the Quasar App Extension's own nested `happy-dom` pin, so the two
    don't diverge — rather than bare `^20`. **Correction per story review**: the real baseline is
    **0 unhandled errors**, not "+1 pre-existing" — re-read skillars-deferred-161's own completion
    notes (567/567 passing on vitest 4.1.12 with **no** unhandled error after that story's bump); the
    "+1" figure was vitest 3's pre-bump number, already fixed by deferred-161. Run `npm install` (or
    the project's lockfile-update command) and confirm `vitest run` is green against the *0-unhandled-
    error* baseline, not merely "all green" — happy-dom 20 defaults `enableJavaScriptEvaluation` to
    `false` (a behavior change from 15.x), so compare both pass count and unhandled-error count
    explicitly; note in Completion Notes which figure was observed. Node 22.16.0 (the project's
    pinned Node) satisfies happy-dom 20's `Node >= 20` requirement. The live suite may have grown
    past 567 tests by dev time (treat 567 as a reference point, not a fixed target) — if
    `routerGuardSpec.js` or any other spec times out under full-suite load after the bump, investigate
    before assuming it's unrelated flake. If the bump needs a version floor compatible with any other
    `@vitest/*` peer range already pinned, note the chosen exact range and why.
11. **Add a bulk-delete capability to `StorageService` and its three implementations — no caller
    wiring.** Add `void deleteAll(List<String> keys)` (or equivalent) to the `StorageService`
    interface. Specify and document the contract up front: empty/null `keys` is a no-op (don't throw);
    decide and document whether a non-empty `DeleteObjectsResponse.errors()` from S3 throws or is
    swallowed (S3's `DeleteObjects` reports per-key failures in `errors()` **without** throwing
    `SdkException`, so `@Retryable` on the method call itself will not fire on a per-key failure the
    way it does for a transport-level exception — decide explicitly whether `deleteAll` inspects
    `errors()` and converts it to an exception, or logs and returns, matching `delete(String)`'s own
    idempotent-on-missing-key behavior). `S3StorageService`: use the real AWS SDK v2 `DeleteObjects`
    request (batched ≤1000 keys per call — chunk if given more), same `@Retryable`/`@Recover` pattern
    already used by `delete(String)` for transport-level failures.
    `LocalFileSystemStorageService`: loop calling the existing `delete(key)` method per key — **not**
    a raw `Files.deleteIfExists` — `delete(String)` already has a path-traversal guard
    (`normalize()` + `startsWith(baseDir)`, `:58-70`) that a new bulk path must not bypass.
    `ReplicatedStorageService`: delegate to `primaryStorageService.deleteAll(keys)` only (it already
    only delegates `delete` to the primary, not a replica — same pattern). Add unit/integration test
    coverage matching each existing single-delete test's shape (success, partial-failure/missing-key
    behavior, retry-exhausted recovery for S3) — note `S3StorageServiceTest` mocks `S3Client`, so it
    won't catch a real checksum/Content-MD5 incompatibility; an IT against the project's SeaweedFS
    test container is worth adding if one isn't already in place for `delete(String)`. Do not touch
    `BlobDeletionOutboxHandler`/`FileStorageService.deleteRawBytes` — no current caller needs the
    bulk path (see "Verified state at drafting" AC11 note); re-annotate the ledger bullet noting the
    capability now exists but is unwired, rather than deleting it outright.
12. **`AlertNotificationListener` hardening — implement despite being moot today (D164-C).**
    (a) Throttle the blank/invalid-recipient `EMAIL_NO_RECIPIENT` ERROR log so it fires at most once
    per some bounded interval (e.g. a simple in-memory last-logged `Instant` with a configurable
    cooldown, or reuse any existing throttling utility in `platform.notification` if one exists —
    check first) instead of once per 30 s tick per breaching rule. (b) Trim (`.strip()`) and validate
    the `platform.admin_alert_email` value (basic shape check, e.g. contains `@`; do not pull in a
    new validation library for this) before using it — blank-after-trim and invalid-shape both take
    the no-send ERROR-logged path. (c) Distinguish a `configService.find()` lookup failure from a
    genuine `mailManager.sendEmailSync` failure: wrap the config lookup in its own narrower
    try/catch (or check) and log it under a distinct status (e.g. `EMAIL_CONFIG_ERROR`), leaving
    `EMAIL_ERROR` to mean "mail transport failed" only. Add/extend
    `AlertNotificationListenerTest` for: throttled-repeat-no-second-log, invalid-email-shape,
    config-lookup-failure-logs-distinct-status. Update the class Javadoc to describe the new
    semantics (still note the listener is unreachable in production until
    `AlertEvaluationService.computeMetricValue` is implemented — do not imply this fixes a live bug).
13. **Closed by decision (D164-A), no code.** `DrillMetadata.repDensity` stays a non-nullable
    `int`. Re-annotate the ledger bullet with this story's id, repeating the "no live path constructs
    a coach-supplied `DrillMetadata` today" finding (now confirmed a third time via
    `DrillMetadata.java:9-14`'s own code comment, itself written by deferred-76 AC10). Re-open only
    if a future story adds a real coach-facing metadata-submitting endpoint.
14. **Closed by decision (D164-B), no code.** `VideoModerationScan.scannedAt` stays
    `updatable = false`, stamped once at `@PrePersist`. Re-annotate the ledger bullet, repeating that
    the real fix (append-only per-attempt rows) is its own story.
15. **Ledger hygiene.** Outcomes: (a) closed by code → delete the bullet (AC1 is a code change but the
    underlying lock already existed — delete the "justified by reasoning, not a test" framing since a
    test now exists); (b) closed by decision → re-annotate `[DECIDED …]` with date and this story's id
    (AC3's `BookingPayment.status` half, AC4's `CONFLICT_STATUSES` half — **not** "`Booking.status`
    half", see the AC4 correction above — AC5, AC13, AC14); (c) closed by verification →
    **re-annotate** `[DECIDED — verified, no defect]` citing the evidence, **don't delete** (AC7 —
    matching AC7's own wording, this entry previously said "delete", corrected for consistency);
    (d) capability added but deliberately unwired → re-annotate, do not delete (AC11). **Leave open,
    re-scoped**: D9's other half — the computed status in `SessionPackPaymentService.computeStatus`
    (`EXHAUSTED`/`PAUSED`/`EXPIRED`/`ACTIVE`, surfaced via `SessionPackPurchaseResponse`) is not the
    same thing as `Booking.status` and is not touched by this story; rewrite that bullet to describe
    the actual remaining code instead of leaving it attributed to the wrong field. Add **two** new
    bullets: the `CoachRegistrationService` always-reports-`emailInUse` finding (AC6's "Verified
    state at drafting" note, including its own no-flush gap), and `VideoModerationEmailListener`'s
    matching no-`.trim()`/no-validation gap (AC12's "Verified state at drafting" note) — both so they
    are not lost.

## Tasks / Subtasks

- [x] Task 1 — Sweeper lock regression test (AC: 1)
  - [x] Read `PaymentPendingSweeper.sweepOne` + `BookingPaymentPersistenceService.reserveCaptureTransactional` fully first
  - [x] Add the package-private test-only hook **after the `existing.isPresent()` early return, before `new BookingPayment()`/`save`** (not before the `findById` read)
  - [x] Write the two-thread harness; assert lock-retry-exhaustion outcome + metric, not "blocks" (NOWAIT + bounded retry)
  - [x] Mutation-test manually: revert `findByIdForUpdate` to `findById`, confirm the new test goes red, then restore and confirm green
  - [x] Fix stale "5 s lock.timeout" comments at `BookingPaymentPersistenceService.java:100` / `PaymentLifecycleService.java:89`
- [x] Task 2 — Availability-window DRY extraction (AC: 2)
  - [x] Read all five call sites' exact current exception messages/map keys before touching anything (note the third `"submitted coach id"` key at `RescheduleService:413`)
  - [x] Decide the mock-compatibility approach (thin stubbable wrapper vs. rewriting the 3 mock-based test classes) before writing the helper
  - [x] Add `requireSlotWithinAvailabilityWindow(...)` to `BookingService`; migrate all five sites; update `RescheduleServiceTest`/`BookingDuplicationServiceTest`/`BookingBatchServiceTest` per the chosen approach
  - [x] Final grep for `isSlotWithinAvailabilityWindow(` — confirm `BookingServiceTest:709-840`'s direct calls and the new helper's internal call are the remaining users, not zero
- [x] Task 3 — `CreditLedgerEntryType` constants class (AC: 3)
  - [x] Grep fresh for every `writeLedgerEntry(` call site's literal `type` argument, plus `DisputeService`, `CreditWalletRefundOutboxHandler.java:42`, `ParentCreditLedgerRepository.java:20,41,44` (do not trust the drafting-time list alone)
  - [x] Add the class (public, with the `chk_ledger_type` migration-caution Javadoc); replace literals; re-annotate the `BookingPayment.status` ledger half `[DECIDED]`
- [x] Task 4 — `CONFLICT_STATUSES` from enum names (AC: 4); re-annotate only the `CONFLICT_STATUSES` half of D9, leave the computed-status half open and re-scoped (AC15)
- [x] Task 5 — `SessionPackPurchase.expiresAt` encapsulation (AC: 5); account for all three write sites, pick Javadoc-only vs. `extendExpiryBy` per the ~30-test-call-site check
- [x] Task 6 — Phone-collision constraint-name fix (AC: 6)
  - [x] Confirm `user_phone_key` is still the live constraint name before hardcoding it
  - [x] Change `save` to `saveAndFlush`; add the unit test AND the end-to-end IT described in AC6; check `ApiAdvice`'s constraint list
- [x] Task 7 — `getParentBookings` ledger closure by verification (AC: 7, 15c — re-annotate, do not delete)
- [x] Task 8 — i18n floor raise (AC: 8)
  - [x] Re-run the spec's own counting logic to get the live count before picking the exact floor (expect ≈1641 at drafting, use ~95% of whatever the live number is)
- [x] Task 9 — Envelope status parity guard (AC: 9)
- [x] Task 10 — happy-dom bump to `^20.11.6` (AC: 10); verify against the 0-unhandled-error baseline, not "+1"
- [x] Task 11 — Storage bulk-delete capability (AC: 11); loop over `delete(key)` for local (not raw `Files.deleteIfExists`); decide + document the `errors()`/empty-list contract
- [x] Task 12 — Alert listener hardening (AC: 12); `AlertNotificationListener` only — `VideoModerationEmailListener` is a separate new ledger bullet (AC15), not in scope here
- [x] Task 13 — repDensity ledger closure by decision (AC: 13, 15b)
- [x] Task 14 — scanned_at ledger closure by decision (AC: 14, 15b)
- [x] Task 15 — Ledger hygiene incl. new `CoachRegistrationService` and `VideoModerationEmailListener` bullets, and the D9 computed-status re-scope (AC: 15); sprint-status entry to `review` on dev completion (→ `done` after code review)

### Review Findings

_All 8 patches applied 2026-10-27 and verified: targeted unit tests (8 classes incl. new `S3StorageServiceProxyRetryTest`) and `PaymentPendingSweeperIT` (10/10) green; the S3 `@Recover` pass-through was mutation-checked (removing it turns the proxy test red with `ExhaustedRetryException: Cannot locate recovery method`)._

_/bmad-code-review 2026-10-27, reviewing uncommitted changes against HEAD `b5680880` (code diff, 35 files). Layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor. Each surviving finding was re-read in the working tree._

- [x] [Review][Patch] (from Decision 1 → option a) `LocalFileSystemStorageService.deleteAll` best-effort + aggregated failure, matching S3 — try every key, collect failures, throw one `StorageProviderException` naming every failed key; document "best-effort; one exception naming every failed key" on `StorageService.deleteAll` Javadoc; add a mid-list traversal-rejected-key test asserting later keys are still deleted [src/main/java/com/softropic/skillars/infrastructure/blobstore/service/LocalFileSystemStorageService.java:79-84]
- [x] [Review][Decision] AC2 helper takes raw message + details `Map` rather than a wording selector — RESOLVED (option a, owner 2026-10-27): accepted as-is; wording exact at all 5 sites; deviation to be noted in Completion Notes (folded into the Completion Notes patch below)
- [x] [Review][Patch] `S3StorageService.deleteAll` per-key failure surfaces as `ExhaustedRetryException("Cannot locate recovery method")` through the retry proxy, not as `StorageProviderException` — Spring Retry routes non-retryable exceptions to recovery, and the only matching `@Recover` takes `SdkException`. Add `@Recover public void recoverDeleteAllPerKey(StorageProviderException ex, List<String> keys) { throw ex; }`, mirroring `recoverGetNotFound` at :97-100, and add a per-key-error case that goes through the proxied bean (IT or retry-enabled slice) [src/main/java/com/softropic/skillars/infrastructure/blobstore/service/S3StorageService.java:144-170]
- [x] [Review][Patch] AC1 IT hook swallows `InterruptedException` and the executor is never awaited — On any early failure, `finally { executor.shutdownNow(); }` interrupts the paused sweep thread. The hook then returns normally, so `sweepOne` goes on to write `CHARGE_FAILED` and commit while the next test's DB reset runs. Rethrow from the hook's catch (`throw new IllegalStateException("hook interrupted", e)`) and add `executor.awaitTermination(10, SECONDS)` after `shutdownNow()` [src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java:399-465]
- [x] [Review][Patch] AC12 tests don't assert the distinct status or the invalid-shape ERROR log — `emailChannel_configLookupThrows_logsDistinctConfigErrorStatus` only checks the formatted-message text, so reverting `kv("status","EMAIL_CONFIG_ERROR")` to `EMAIL_ERROR` still passes. `emailChannel_invalidEmailShape_sendsNothing` verifies that nothing is sent but not the required ERROR log. Assert on the `StructuredArgument` in `getArgumentArray()` and on the no-recipient ERROR event [src/test/java/com/softropic/skillars/platform/notification/service/AlertNotificationListenerTest.java:117-157]
- [x] [Review][Patch] Ledger hygiene (AC15) — (1) an orphan `[DECIDED …]` line remains where the deleted phone-collision bullet was; delete it. (2) The closed three-gap `AlertNotificationListener` bullet was annotated instead of deleted (AC15a); delete it. (3) The open `VideoModerationEmailListener` bullet carries a `[DECIDED …]` sub-line pointing at (2); remove it. (4) The still-open D9 computed-status half sits under a `[DECIDED …]` tag; reword it as an open note so ledger scans don't filter it out [_bmad-output/implementation-artifacts/deferred-work.md]
- [x] [Review][Patch] AC2 batch-site wording isn't pinned, and the helper has no direct test — `BookingBatchServiceTest` stubs `doThrow(new OperationNotAllowedException("Requested slot is not within coach availability", …))` and then asserts `hasMessageContaining` on its own stub, which is circular. Verify the message and details arguments passed to `requireSlotWithinAvailabilityWindow` (as `RescheduleServiceTest` and `BookingDuplicationServiceTest` already do), and add `BookingServiceTest` cases for the helper's throw and no-throw branches [src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java:204-243,285]
- [x] [Review][Patch] S3 chunking test can't detect a wrong chunk size — With 1,500 keys, `verify(s3Client, times(2))` passes for both 750+750 and 1000+500. Capture the requests and assert sizes 1000 and 500 (and optionally a 1,000-key boundary case that makes one call) [src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceTest.java:140-150]
- [x] [Review][Patch] Completion Notes / File List accuracy — AC3 says "13 call sites", but the diff has 15 Java sites plus 3 JPQL. AC1 says "three stale 5s comments", but four were corrected (BPPS:100, PLS:89/:213/:300). AC3/AC5 present DisputeService, the CHECK constraint and the three `expiresAt` writers as dev-time discoveries, but the corrected story already listed them. The File List omits `story-review.md`. Fix the counts and wording; the same "13" also appears in `sprint-status.yaml` [this story file, Completion Notes / File List]
- [x] [Review][Defer] AC10 full-suite timeouts (7 `Test timed out in 5000ms` across `routerGuardSpec.js`, `CoachPlayerDetailPageSpec.js`, `CoachSessionPackTiersPageSpec.js`) — deferred, likely pre-existing. The pre-dev story review saw `routerGuardSpec.js` time out under full-suite load on happy-dom 15, but the other two specs were not confirmed on 15.x [src/frontend/src]
- [x] [Review][Defer] `AlertNotificationListener` EMAIL_CONFIG_ERROR path is not throttled — it logs ERROR with a stack trace on every tick per breaching rule while the config service is down. That is outside AC12(a)'s scope (no-recipient only) and moot until `computeMetricValue` is real [src/main/java/com/softropic/skillars/platform/notification/service/AlertNotificationListener.java:101-110] — deferred, out of scope
- [x] [Review][Defer] `S3StorageService.deleteAll` retry granularity — a transient `SdkException` on chunk N re-sends all chunks from 0 (harmless: idempotent), and per-key throttling codes in `errors()` (`SlowDown`/`InternalError`) are treated as permanent and never retried. There is no caller today (capability deliberately unwired) [src/main/java/com/softropic/skillars/infrastructure/blobstore/service/S3StorageService.java:144-163] — deferred, no caller

## Dev Notes

- **This story was audited by /mto-story-review on 2026-10-27 against HEAD `b5680880`** (see
  `_bmad-output/implementation-artifacts/story-review.md`). 4 Critical and 5 High findings were
  confirmed by re-reading the actual source and applied directly into the ACs above (AC1's hook
  placement/assertion, AC2's mock-test risk and wording, AC3's CHECK-constraint correction and
  missed sites, AC5's three-writer correction, AC6's `saveAndFlush` requirement, AC10's baseline).
  If anything below still looks inconsistent with that report, re-read the report directly rather
  than trusting a paraphrase.
- **Do not run `mvn verify` locally** (project rule) — GitHub CI is the gate. Targeted single
  ITs/unit tests are fine.
- Re-verify every cited line/constant against HEAD again at dev time — ledger items and even this
  story's own "Verified state at drafting" section drift; ~1 in 3 ledger items has historically
  turned out already stale by the time it's picked up (precedent: AC2's duplication count itself
  grew from 3 to 5 call sites between the original ledger bullet and this story's drafting).
  **Note, not an AC**: AC12 touches `AlertNotificationListener`'s existing tests/Javadoc only —
  no new `@MockitoBean`/`@TestPropertySource`/Spring context, so it should not move the
  context-count gate ceiling (`.github/scripts/assert-context-count.sh`). AC1's new concurrency
  test, if added to the existing `PaymentPendingSweeperIT`, reuses that IT's context — do not spin
  up a second one.
- Bare `jdbcTemplate` writes in an IT method body do not commit (Hikari auto-commit=false) — wrap in
  `transactionTemplate` if AC1's test needs any direct seeding.
- AC2's five call sites currently throw with **different** Map key wording per site ("requested
  start time" vs "proposed start time" vs "submitted coach id") — the extracted helper must preserve
  each site's exact existing wording; this is not a chance to also normalize the wording (that would
  be an unrequested, unreviewed message-contract change for every client surfacing these errors).
- AC3/AC4 both deliberately stop short of introducing `@Enumerated`/true Java enums on
  `BookingPayment.status` or `Booking.status` — see Owner decisions D164-E/D164-F. If dev-time
  re-verification finds either column now has a documented reason to actually convert, stop and
  raise it rather than silently doing the bigger migration.
- AC11: confirm at dev time whether the AWS SDK v2 `DeleteObjects` request surfaces partial failures
  (`DeleteObjectsResponse.errors()`) — the interface method's contract should probably not throw on a
  partial failure for already-missing keys (mirrors `delete(String)`'s own idempotent-on-missing-key
  behavior), but should surface genuine per-key errors. Decide and document the exact contract in the
  interface Javadoc.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (bullets listed in the mapping table)
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperTest.java`, `PaymentPendingSweeperIT.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/{BookingService,RescheduleService,BookingDuplicationService,BookingBatchService}.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedger.java`, `BookingPayment.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/BookingPaymentStatus.java` (existing non-enum style to mirror)
- `src/main/java/com/softropic/skillars/platform/payment/service/{CreditWalletService,CashOutService,CreditWalletRefundOutboxHandler,PaymentLifecycleService}.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java` (3 more `"BOOKING_REFUND"` literal sites, AC3)
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedgerRepository.java` (JPQL literals, AC3)
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` (2 more `setExpiresAt` sites, AC5)
- `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java` (AC6 note — new ledger bullet only)
- `src/main/java/com/softropic/skillars/platform/notification/service/VideoModerationEmailListener.java` (AC12 note — new ledger bullet only)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStatus.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java`
- `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrier.java` (constraint-name pattern to mirror)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (`getParentBookings`/`getCoachBookingRequests`)
- `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`
- `src/test/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntitySchemaIT.java`
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailDeliveryStatus.java`
- `src/frontend/package.json`
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/service/{StorageService,S3StorageService,LocalFileSystemStorageService}.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/ReplicatedStorageService.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/AlertNotificationListener.java`, `AlertNotificationListenerTest.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoModerationScan.java`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `PaymentPendingSweeperIT.concurrentReserveCapture_isBlockedBySweepOnesLock_andLoses` initially timed out waiting for the test hook (`sweepPausedHoldingLock` never counted down within 10s). Root cause: `PaymentPendingSweeper` is an AOP (CGLIB) proxy because of `@SchedulerLock` — the `@Autowired PaymentPendingSweeper sweeper` field in the test is the *proxy*, and `sweepOne()`'s real method body runs on a separate *target* instance with its own copy of the `sweepOneBeforeChargeFailedWriteHook` field. Setting the field through the proxy reference was invisible to the real method. Fixed using `org.springframework.test.util.AopTestUtils.getUltimateTargetObject(sweeper)` to resolve the real target and set/reset the hook on it instead — mirrors `MinorSafetyGateIT`'s identical pre-existing workaround for an `@Async` proxy. Documented in both the test and the production field's Javadoc for future maintainers.
- Mutation-tested AC1 manually: temporarily replaced `findByIdForUpdate` with `findById` in `sweepOne` → the new concurrency test failed red (`reserveCapture` succeeded unopposed instead of being blocked) → restored → confirmed green again.

### Completion Notes List

- **AC1**: Added a package-private test-only hook (`sweepOneBeforeChargeFailedWriteHook`) in `PaymentPendingSweeper`, invoked after the existing-payment-row check and before the `CHARGE_FAILED` write — the exact contested window. New `PaymentPendingSweeperIT.concurrentReserveCapture_isBlockedBySweepOnesLock_andLoses` pauses `sweepOne` there while a concurrent `reserveCapture` call is forced to contend for the same row lock; asserts the NOWAIT+bounded-retry contract (lock-retry-exhaustion, not blocking), the `persistence.lock_retry` metrics, and that the sweeper's write — not the loser's — survives. Mutation-tested red/green manually (see Debug Log). Also corrected four stale "5s lock.timeout" comments to the real NOWAIT value (`BookingPaymentPersistenceService:100`, `PaymentLifecycleService:89/:213/:300`).
- **AC2**: Added `BookingService.requireSlotWithinAvailabilityWindow(...)`, a package-private check-and-throw helper sharing the repeated "if (!check) throw SLOT_OUTSIDE_AVAILABILITY" boilerplate across all five call sites (the original `createBookingRequest` plus `RescheduleService` ×2, `BookingDuplicationService`, `BookingBatchService` — up from the ledger's original 3). Each site keeps building its own message/detail-map (wording genuinely differs per site, including `RescheduleService.acceptRescheduleShared`'s extra `"submitted coach id"` key) — only the check+throw idiom is shared. **Deviation from AC2's wording-selector suggestion, accepted by the owner at code review (2026-10-27, Decision 2 option a):** the helper takes the raw message string + details `Map` rather than an enum/record selector — wording stays exact per site and the helper stays trivial; the cost is that each site still spells out its own message/keys. `isSlotWithinAvailabilityWindow` itself is untouched and still used directly by `BookingServiceTest` and the new helper. Rewrote the mock-based stubs/verifies in `RescheduleServiceTest`, `BookingDuplicationServiceTest`, `BookingBatchServiceTest` (a mocked void method is a no-op by default, so happy-path stubs of the old boolean method became unnecessary, and failure-path stubs now use `doThrow`/`doAnswer`/`doNothing().doThrow(...)` sequencing instead). Verified end-to-end against a real DB via `RescheduleResourceIT`, `BookingBatchResourceIT`, `BookingServiceConcurrencyIT`, `RescheduleServiceConcurrencyIT`, `BookingBatchServiceConcurrencyIT` — all unaffected, since the thrown exception's content is unchanged.
- **AC3**: Added `payment/contract/CreditLedgerEntryType.java` (mirrors `BookingPaymentStatus`'s non-enum style, with a migration-caution Javadoc) and replaced all 15 Java `writeLedgerEntry`/`existsByReferenceIdAndType` bare-string call sites across `CreditWalletService`, `CashOutService`, `CreditWalletRefundOutboxHandler`, `BookingPaymentPersistenceService`, `PaymentLifecycleService`, `DisputeService`, and the JPQL literals in `ParentCreditLedgerRepository` (3 sites, via compile-time constant string concatenation in `@Query`). Includes the 3 `DisputeService` write sites and the existing `chk_ledger_type` CHECK constraint the corrected story (post-/mto-story-review) already called out — no migration needed since the CHECK already matches the 6 constants.
- **AC4**: `PackSessionService.CONFLICT_STATUSES` now derives from `BookingStatus.REQUESTED/ACCEPTED/CONFIRMED/UPCOMING.name()` instead of re-typed literals.
- **AC5**: Added a Javadoc note on `SessionPackPurchase.expiresAt` naming all three live writers, as listed by the corrected story (`PackSessionService` pause, `SessionPackPaymentService.extendPack`, `SessionPackPaymentService.createPurchase`). Kept the class-level Lombok `@Setter` — 52 existing test call sites made a narrower replacement its own, separate change. No `updatable=false`.
- **AC6**: `ParentRegistrationService.registerParent` now checks `ex.getCause() instanceof ConstraintViolationException && "user_phone_key".equals(cve.getConstraintName())` instead of message-sniffing, mirroring `SluPersistenceRetrier`'s established pattern, and uses `saveAndFlush` (not `save`) — required, since `User.id` is an app-assigned `@Tsid` and a bare `save()` would defer the INSERT past the catch block, making the constraint check dead code. Added unit tests (phone/non-phone/unrelated-constraint) and a real end-to-end `ParentRegistrationResourceIT` test registering two parents with the same phone, different emails. Confirmed `ApiAdvice` needs no change (`ParentRegistrationException` has its own dedicated `@ExceptionHandler`, never reaching the generic `DataIntegrityViolationException` handler). Did not touch `CoachRegistrationService` (new, separate finding, recorded in the ledger).
- **AC7**: No code — verified `getParentBookings`'s `null` `parentName` is intentional (the method genuinely never needs it).
- **AC8**: Re-ran the spec's own `scanCallSites()` logic directly (temporarily set the floor absurdly high to force a failure message revealing the true count, then reverted) — live count is 1641, not the ledger's stale 1571. Raised `MIN_LITERAL_CALL_SITES` to 1550 (~95% of 1641, matching the project's own stated rule). Verified full `i18nTemplateKeyResolutionSpec.js` green (11/11).
- **AC9**: Extracted `EnvelopeEntitySchemaIT`'s `(constraint, enum)` comparison into a shared private helper and added `envelopeEntityStatusCheck_allowsExactlyTheEmailDeliveryStatusEnumValues`, parameterized the same way as the existing `EmailTemplate` test. Both pass today (constraint and enum already agree) — the guard is for future drift.
- **AC10**: Bumped `happy-dom` to `^20.11.6` (matching the Quasar AE's own nested pin) and ran `npm install` (deduped to a single `happy-dom@20.14.6`). Full suite: 570/577 passed, **0 unhandled errors** (the corrected baseline — the story's original "+1 pre-existing" claim was stale, already fixed by deferred-161). The 7 failures (3 files: `routerGuardSpec.js`, `CoachPlayerDetailPageSpec.js`, `CoachSessionPackTiersPageSpec.js`) are all `Test timed out in 5000ms` under full-parallel-suite CPU load — re-ran `routerGuardSpec.js` in isolation 3× (14/14 passing each time) and the 3-file subset together (70/71 passing, a *different* single test timing out each run) to confirm this is pre-existing resource-contention flake, not a logic regression introduced by the version bump. Node 22.16.0 satisfies happy-dom 20's `>=20` requirement.
- **AC11**: Added `StorageService.deleteAll(List<String>)` to the interface and all three implementations. `S3StorageService`: real AWS SDK v2 `DeleteObjects`, chunked at 1000 keys/call, aggregates and throws on genuine `errors()` entries (a missing key is never reported as an error by S3, so idempotent-on-missing is automatic). `LocalFileSystemStorageService`: loops the existing path-traversal-guarded `delete(key)` (not a raw `Files.deleteIfExists`). `ReplicatedStorageService`: delegates to the primary only, matching `delete(String)`'s own behavior. Added unit tests for all three plus a real `S3StorageServiceIT` test against the SeaweedFS test container. No caller wiring (none needed — `BlobDeletionOutboxHandler` is one-key-per-row by contract).
- **AC12**: `AlertNotificationListener` now (a) throttles the no-recipient ERROR log to at most once per 300s (injected `Clock`, testable without real sleeps), (b) `.strip()`s and shape-checks (`contains("@")`) the admin email before use, (c) logs a config-lookup failure under the distinct `EMAIL_CONFIG_ERROR` status instead of conflating it with `EMAIL_ERROR` (mail-transport failures). Added throttle/shape/distinct-status tests using a Logback `ListAppender` (mirrors `RadarCompositeCalculatorTest`'s established log-capture pattern). `VideoModerationEmailListener`'s identical gap was deliberately left untouched (new ledger bullet).
- **AC13/AC14**: No code — both re-confirmed by decision, re-annotated in the ledger.
- **AC15**: Ledger hygiene applied across 14 bullets (deletions, `[DECIDED]` re-annotations, and the 2 new findings: `CoachRegistrationService`'s phone/email collision gap, `VideoModerationEmailListener`'s no-trim/validation gap) — see `deferred-work.md` diff.
- **Validation**: 228 unit tests (9 classes, 0 failures) + 96 integration tests across 9 real-database/Testcontainers classes (0 failures) + the frontend i18n spec (11/11) + the full frontend suite (570/577, 7 confirmed-flaky timeouts under load, 0 unhandled errors). No `mvn verify` run (project rule — GitHub CI is the gate); all validation was targeted per `docs/validation-strategy.md`.

### File List

**New files:**
- `src/main/java/com/softropic/skillars/platform/payment/contract/CreditLedgerEntryType.java`

**Modified — production:**
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CreditWalletService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CashOutService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CreditWalletRefundOutboxHandler.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedgerRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java`
- `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java`
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/service/StorageService.java`
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/service/S3StorageService.java`
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/service/LocalFileSystemStorageService.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/ReplicatedStorageService.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/AlertNotificationListener.java`

**Modified — tests:**
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/ParentRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/api/ParentRegistrationResourceIT.java`
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/LocalFileSystemStorageServiceTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceIT.java`
- `src/test/java/com/softropic/skillars/platform/filestorage/service/ReplicatedStorageServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/service/AlertNotificationListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntitySchemaIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` (code-review patch: direct helper tests)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceProxyRetryTest.java` (new, code-review patch: retry-proxy wiring)

**Modified — frontend:**
- `src/frontend/package.json`
- `src/frontend/package-lock.json`
- `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`

**Modified — ledger/process:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/story-review.md` (pre-dev /mto-story-review report, overwritten)
