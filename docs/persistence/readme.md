# Skillars Persistence Notes

Deep dives into non-obvious persistence-layer behavior — the kind of thing you only learn by hitting
it, or by reading the story that hit it for you.

## The documents

| Document | What it answers |
|---|---|
| [Pessimistic lock retry](pessimistic-lock-retry.md) | Why `findByIdForUpdate` contention now fails fast instead of blocking forever, why a shared `PessimisticLockRetryer` exists, the alternatives that were tried and rejected, and how to wire a new locked call site. **Start here if you're touching `@Lock(PESSIMISTIC_WRITE)` anywhere.** |

## The one-paragraph version

Four repositories (`CoachProfileRepository`, `BookingRescheduleRequestRepository`, `BookingRepository`,
`SessionPackPurchaseRepository`) take a `PESSIMISTIC_WRITE` lock via `findByIdForUpdate` and, until
`skillars-deferred-62`, carried a `jakarta.persistence.lock.timeout` hint that every comment claimed
bounded the wait to ~5 seconds. It didn't — Hibernate's `PostgreSQLDialect` silently ignores any finite
timeout value on Postgres, so contention blocked the requesting connection indefinitely. The fix
switches to `NO_WAIT` (the one value Postgres's dialect actually honors) wrapped in
`PessimisticLockRetryer`, a shared helper that retries a failed lock attempt from a JDBC savepoint
inside the *same* transaction — because a straightforward "catch and call it again" retry doesn't work
here (see the linked doc for why) and Spring's declarative `Propagation.NESTED` isn't available in this
project's JPA setup either. The helper's own code review the same day found and fixed one real
correctness bug in that savepoint design (a persistence-context desync that could silently drop an
unrelated write on a retried attempt — fixed by flushing before each savepoint) plus eight hardening
patches — config validation at startup, backoff jitter, explicit savepoint release, a dedicated unit
test, and tightened concurrency-test assertions among them.

## Where this lives in code

- `com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer` — the helper itself.
- `com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryerTest` — direct unit coverage
  of its retry/backoff/give-up/validation logic.
- `CoachProfileRepository`, `BookingRescheduleRequestRepository`, `BookingRepository`,
  `SessionPackPurchaseRepository` — the four `findByIdForUpdate` methods that carry `NO_WAIT`.
- 16 call sites across 10 services (`BookingService`, `RescheduleService`, `BookingBatchService`,
  `BookingDuplicationService`, `CoachProfileService`, `AdminCoachEnforcementService`,
  `BookingPaymentPersistenceService`, `PaymentPendingSweeper`, `SessionPackPaymentService`,
  `PackSessionService`) call `lockRetryer.withBoundedRetry(...)` around the locked read.
- `ApiAdvice.pessimisticLockExceptionHandler` maps the exhausted-retry case to a `409` — unchanged by
  this story, since it already caught `PessimisticLockingFailureException` unconditionally.

## Related

- [docs/testing/readme.md](../testing/readme.md) — how to write an integration test in this codebase;
  see [pessimistic-lock-retry.md#testing-a-locked-call-site-end-to-end](pessimistic-lock-retry.md#testing-a-locked-call-site-end-to-end)
  for the concurrency-IT pattern specific to lock contention, and
  [pessimistic-lock-retry.md#testing-the-helper-itself-pessimisticlockretryertest](pessimistic-lock-retry.md#testing-the-helper-itself-pessimisticlockretryertest)
  for testing the retry logic itself without a real database.
- [docs/dev-docs/database/index.html](../dev-docs/database/index.html) — general entity/migration
  conventions for this project.
