# Story skillars-deferred-91: A generic transactional outbox for AFTER_COMMIT reliability (money + notifications + SLU), a completion-gated coach-payout / CAPTURE_PENDING design pass, rolling-deploy migration-safety hardening, a full de-DE formal-register + fr-FR accent pass, N+1 batching for `getPublicProfile`, and the genuine one-off bugs still open in `deferred-work.md`

Status: ready-for-dev

<!-- v0.1 — story-creation pass (2026-09-03), immediately after skillars-deferred-90 merged (PR #145,
     master CI green) and 4 Dependabot PRs (#140/#142/#143/#144) merged. Project-owner-selected large
     cross-cutting bundle. Four multi-round decisions taken during creation (see § "Project-owner
     decisions folded in"): (1) FULL generalization of deferred-90's blob-deletion outbox to the money
     + notification + SLU AFTER_COMMIT paths; (2) FULL escrow / completion-gated-payout design pass,
     not just a CAPTURE_PENDING timeout; (3) FULL de-DE `Sie` consistency rewrite, not just the one
     new string; (4) ALL THREE rolling-deploy items — enum-gate + lint-widen + retroactive audit of the
     6 grandfathered ACCESS EXCLUSIVE migrations. Anchor one-off bugs AC12–AC19. This story is
     deliberately very large per the project owner's standing "no small stories" instruction. -->

## Story

As the **Skillars engineering team**,
I want a generic durable-outbox mechanism replacing the fire-and-forget `@TransactionalEventListener(AFTER_COMMIT)` pattern on every path that can silently lose money or a notification, a real design pass on completion-gated coach payout and the `CAPTURE_PENDING` dead-end, the rolling-deploy migration hazards that `skillars-deferred-90` AC10 only *catalogued* actually closed, one consistent formal register across the whole de-DE bundle plus accented fr-FR, and the genuine one-off bugs still sitting in `deferred-work.md`,
so that a post-commit Stripe refund or a cancellation email can no longer vanish with no retry, an unrecoverable `CAPTURE_PENDING` row stops holding a coach's slot forever, a rolling deploy stops carrying the enum-widen / `ACCESS EXCLUSIVE` hazards the convention doc names but does not fix, DE users stop reading a `du`/`Sie` mix, and the `ErrorLog` `%`-crash-in-the-exception-handler / the transaction-less GDPR video cascade / the same-segment `permitAll()` path leak are all fixed.

## Story creation context

Created 2026-09-03 via the story-creation process, immediately after `skillars-deferred-90` merged (PR #145, master CI green) and its four green Dependabot follow-ups. Project-owner instruction (unchanged from `skillars-deferred-89`/`-90`): *"Go through `deferred-work.md` and put together a story that touches Genuine one-off bugs & gaps. If they are exhausted, get the ones that touch the following buckets in the order listed: Rolling-deploy column-drop ordering; i18n / hardcoded-English / de-DE native review as well as French locale; N+1 / query batching. If there is an item that needs a decision from me, let's go through together. Do not create small stories."*

Genuine one-off bugs are **not** exhausted — AC12–AC19 anchor the story. The i18n and rolling-deploy buckets each have real residue that `skillars-deferred-90` deliberately deferred (deferred-90 AC10 wrote the migration-safety *convention + lint* but grandfathered every pre-`V121` migration; deferred-90 AC12 did a native pass on only the 55 marked strings and left the rest of the de-DE bundle informal). The N+1 bucket is nearly exhausted by deferred-90 AC13 — only `getPublicProfile` remains, and the ledger itself flags it as unmeasured, so AC11 measures before batching.

### Project-owner decisions folded in (2026-09-03)

Four decisions taken during a multi-round story-creation discussion. Each was offered as three options (narrow / medium / full); the project owner chose **full** on all four.

1. **AFTER_COMMIT-listener reliability — FULL generalization.** deferred-90 built a durable outbox for exactly one case (`PendingBlobDeletionService` / `pending_blob_deletions`). The pattern (`skillars-10-2` D1, `skillars-3-6` W3, `skillars-3-10` D2, `skillars-deferred-15` D1, `skillars-6-3` W3, `skillars-deferred-89` SLU under-report) recurs 6× and silently drops a refund, a cancellation/expiry email, or an SLU snapshot delta. This story extracts a **reusable** outbox and wires it onto **all three**: refunds (AC2), notification emails (AC3), and the SLU snapshot under-report path (AC4). Large blast radius accepted.
2. **Escrow / `CAPTURE_PENDING` — FULL design pass.** Not just an automated `CAPTURE_PENDING` timeout (`skillars-uat-3` D3, deferred-90 residual): also a design pass on **completion-gated coach payout** (`skillars-deferred-63` story creation) — fund-hold duration, interaction with `QuickCompleteTimeoutService`'s auto-completion, interaction with the dispute system (a coach still has no rebuttal *before* an automatic no-show refund fires). AC5 produces a design sub-doc first, then implements.
3. **de-DE register — FULL `Sie` consistency rewrite.** deferred-90 AC12 rewrote only the 55 `// TODO: native review` strings + backend bundles. Large untouched sections of `de-DE/index.js` (onboarding, dashboard, reviews, video) are informal `du`. AC9 rewrites every informal form to formal `Sie` so the whole bundle has one voice. AC10 additionally fixes pre-existing missing-accent fr-FR strings.
4. **Rolling-deploy bucket — ALL THREE items.** AC6 (gate `AdminAlertType.MODERATION_UNRESOLVED` / make `AdminQueueService` tolerate an unknown alert type per-row — `skillars-deferred-16` D4), AC7 (widen `MigrationConventionLintTest` to the 3 documented blind spots), **and** AC8 (retroactively make the 6 grandfathered `ACCESS EXCLUSIVE` migrations V60/V89/V94/V97/V98/V117 online-safe with follow-up `NOT VALID` + `VALIDATE CONSTRAINT` / `CREATE INDEX CONCURRENTLY` / batched-backfill migrations, or document why each is safe at any scale and leave it).

### Standing decisions recorded (not ACs)

- **`sessionManager.js` `startSessionMonitoring()` early-return-with-no-timer path** (`deferred-work.md` line 1318/1324) — project owner previously decided "leave as documented." Not revisited in this story.
- **No frontend test framework.** ~6 recorded coverage gaps (`5-4` W9, `deferred-17`/`-18`/`-30`/`-37`/`-38`/`-43` D6) depend on standing up Vitest/Vue Test Utils. Out of scope — its own initiative. Every `.vue` / `.js` change in this story is verified by ESLint + `quasar build` + code reading, per this project's established path for frontend-only changes.
- **`BookingService` hardcoded `"Unknown Player/Coach/Parent"` fallbacks** (`skillars-deferred-81`, DECIDED 2026-08-29) — stay as-is (unreachable orphaned-profile shape). AC19 only addresses the `MessagingService` sibling nit that deferred-90 explicitly deferred.
- **Two-sided disputes**, **`saveStep4` per-window timezone write**, **overnight availability windows**, **shared `stripe_customers` row across roles**, **`DrillMetadata.repDensity` primitive**, **`RadarDisplayService` skill-deactivation drop**, **40k-char ledger-line hygiene** — all previously DECIDED wont-fix / DISMISSED; not touched.

---

## Acceptance Criteria

### AC1 — A reusable transactional outbox, extracted from `PendingBlobDeletionService`.

A generic durable outbox that a producer writes **inside its own transaction** and a post-commit + scheduled drainer processes off the request path, with per-row `attempts` / `last_error` and a stuck-row ERROR alert. Modelled column-for-column and mechanism-for-mechanism on `skillars-deferred-90`'s `PendingBlobDeletionService` + `PendingBlobDeletionChunkProcessor` + `PendingBlobDeletionRepository`:

- **Table** (`outbox_messages` in `main`, or a small set of per-domain tables — dev's call, one migration): additive `CREATE TABLE` only, no FK to domain tables, no secondary index at creation. Passes `MigrationConventionLintTest` (it is a `V123+` migration, above the `V121` grandfather baseline). Columns at least: `id bigint identity PK`, `aggregate_type text`, `payload jsonb` (or typed columns per domain), `attempts int NOT NULL DEFAULT 0`, `last_error text`, `created_at timestamptz NOT NULL DEFAULT now()`.
- **Producer API:** `enqueue(...)` (called inside the business transaction) + `requestDrainAfterCommit()` (publishes a marker event inside the same transaction, so exactly one drain fires `AFTER_COMMIT`). Same two-method shape as `PendingBlobDeletionService`.
- **Drain:** a `@TransactionalEventListener(AFTER_COMMIT)` **non-`@Transactional`** listener that loops `chunkProcessor.processChunk()` until a chunk yields nothing (with a `MAX_CHUNKS_PER_DRAIN` safety stop), where `processChunk()` is a **separate bean** with `@Transactional(REQUIRES_NEW)` claiming ≤ `CHUNK_SIZE` rows via `SELECT … FOR UPDATE SKIP LOCKED` (JPA `@Lock(PESSIMISTIC_WRITE)` + `jakarta.persistence.lock.timeout = -2`), so the DB connection is released between chunks and never held across the external call. **This is the exact shape deferred-90's 3-layer code review forced onto `PendingBlobDeletionService`; do not regress to a self-invoked `@Transactional(REQUIRES_NEW) drain()`.**
- **Sweeper:** `@Scheduled(fixedDelayString = "${app.outbox.sweep-ms:300000}")` + `@SchedulerLock`; disabled under the test profile with every other job via `app.scheduling.enabled` (`infrastructure.config.SchedulingConfig`); tests call `drain()` directly. Crossing a `STUCK_ATTEMPTS_THRESHOLD` (10) logs `[OUTBOX_STUCK]` at ERROR — a row is **never dropped** (it may be money or a compliance-relevant delete).
- **Handler dispatch:** each outbox row names the operation it re-drives; the drainer dispatches to a handler that is **idempotent** (a repeat call on an already-completed operation is a documented no-op) — mirroring `QuotaService.release()` and the Stripe-refund idempotency key.
- Unit tests for chunking / SKIP LOCKED / loop-until-empty / stuck-row alert; one `@SpringBootTest @Testcontainers` IT proving a producer-enqueue → commit → drain round-trip and a forced-failure → `attempts++` → re-drive.
- **Reference:** `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionService.java` (+ `…ChunkProcessor`, `…repo/PendingBlobDeletionRepository.java`), `src/main/resources/db/migration/V122__pending_blob_deletions.sql`, `docs/deployment/migration-conventions.md`.

### AC2 — The refund AFTER_COMMIT listeners go through the outbox.

Every `@TransactionalEventListener(AFTER_COMMIT)` that issues a Stripe refund now enqueues an outbox row **inside the producing transaction** and drains post-commit; a refund whose gateway call fails stays in the outbox with `attempts++` / `last_error` for the next drain instead of being lost with only a log line.

- Call sites: `CancellationRefundService.onBookingCancelledByAdmin` (`skillars-10-2` D1), `onBookingCancelledByCoach`, `onCoachNoShow`, and any sibling listener in `platform.payment` following the same `REQUIRES_NEW` refund shape (grep `@TransactionalEventListener` in `platform.payment` — enumerate them in Dev Notes).
- The refund handler must be idempotent against a re-drive: reuse the Stripe idempotency key already keyed on the booking/payment id; a second attempt on an already-refunded charge is a no-op returning success.
- Regression IT: a listener whose refund call throws once, then succeeds on the next drain — the refund lands exactly once, the booking's terminal state is unchanged.
- **Do not** change the synchronous cancellation/refund paths (only the post-commit listeners).

### AC3 — The notification AFTER_COMMIT listeners go through the outbox.

`BookingEmailListener` (`skillars-3-6` W3) and `SessionPackEmailListener` (`skillars-3-10` D2, `skillars-deferred-15` D1) — the cancellation and pack-expiry-warning emails currently discarded on an `AFTER_COMMIT` listener failure (or on `fallbackExecution = false` when the publish happens outside a transaction) now go through the outbox.

- Keep `SessionPackExpiryNotifier.expiryWarnedAt` / the forfeiture-scheduler dedupe stamp — the "up to 14 warning emails per pack" behaviour (`skillars-deferred-15` implementation note) is what appears the moment delivery becomes reliable; the dedupe column must stay load-bearing.
- Email send is idempotent-enough: a duplicate send on a re-drive is acceptable (better than a lost notification), but the dedupe stamp should prevent the common case.
- Regression IT per listener: send fails once → re-driven on next drain → exactly one email (or an acceptable duplicate, asserted explicitly) → the `AFTER_COMMIT`-drop no longer occurs.

### AC4 — SLU weekly-snapshot under-report is reconciled.

`skillars-deferred-89` residual (`deferred-work.md` line 1288): detail rows saved, `SnapshotPersistenceRetrier.writeAllWithRetry` then exhausts its retries and its `@Recover` logs "rows lost" and returns — `player_slu_weekly_snapshot` **under-reports** for that session with no reconciliation.

- Wire the failed snapshot write onto the outbox (re-drive `writeAllWithRetry` for that `(session, isoYear, isoWeek)` bucket), **or** add a reconciliation sweep that, for a session with `player_skill_stats` rows but **no** matching `player_slu_weekly_snapshot_applied` marker for its bucket, recomputes and applies the missing `total_slu` delta (the `V119` marker table makes this idempotent).
- Must not re-introduce the *over*-report direction `skillars-deferred-89` AC2 closed (the snapshot write is skipped on `ALREADY_PERSISTED`; a reconciliation must respect the marker).
- IT: force `writeAllWithRetry` to `@Recover`, assert the snapshot is `0` for the bucket, run the outbox drain / reconciliation, assert `total_slu` now equals `SUM(player_skill_stats.slu_value)` for that session and the marker is present exactly once.

### AC5 — Completion-gated coach payout + `CAPTURE_PENDING` automated exit (escrow design pass).

**Task 0 of this AC is a design sub-doc** (`docs/architecture/payout-and-capture-pending.md` or an ADR) reviewed with the project owner before implementation, covering: fund-hold duration; the `BookingCompletedEvent` vs parent-confirmation vs `QuickCompleteTimeoutService` auto-completion signal that releases payout; interaction with the dispute system (whether a coach gets a rebuttal window *before* an automatic `NO_SHOW_COACH` refund fires); Stripe Connect settlement timing vs in-app completion. Then:

- **(a) `CAPTURE_PENDING` automated exit.** A `CAPTURE_PENDING` `booking_payments` row older than `${app.payment.capture-pending.max-hours}` transitions to a terminal state (`CHARGE_FAILED` or a new `CAPTURE_ABANDONED`) + emits `booking.payment_pending.unrecoverable{reason="CAPTURE_TIMEOUT"}`, **releasing the coach's slot** and unblocking the parent's cancel (`skillars-uat-3` D3, deferred-90 line 1325). No automatic charge/confirm — only a human can read the Stripe side, per uat-3's own reasoning; this AC bounds the *slot-hold* harm, it does not resolve the payment.
- **(b) Completion-gated payout.** Coach payout release is gated on a completion signal (per Task 0's decision) rather than settling independently at booking confirmation (`skillars-deferred-63` line 1189). Interacts with `QuickCompleteTimeoutService` (`src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java:36-61`) and `BookingService.recordNoShowCoach` (`:739-758`, which the project owner previously kept `UPCOMING`-only).
- Scope the ACs that fall out of Task 0's design; anything Task 0 identifies as needing its own story is filed to `deferred-work.md` under this story's residuals section (AC20), not implemented here.

### AC6 — Rolling-deploy: `AdminQueueService` tolerates an unknown `AdminAlertType` per-row.

`skillars-deferred-16` D4: an older instance reading a row carrying the newer `AdminAlertType.MODERATION_UNRESOLVED` value hits `Enum.valueOf` and 500s the **whole** `GET /api/admin/queue` and `/queue/summary` page, not just the affected row.

- `AdminQueueService.buildSummary` (`AdminQueueService.java:50-58,128-133`) — an unrecognised `admin_alerts.alert_type` value **skips that row with a WARN**, not `Enum.valueOf` on the request thread. The page renders every alert it *can* map.
- This is the general "enum widening one release ahead of the first write" rule from `docs/deployment/migration-conventions.md` applied to the one place a widened enum is *read*; audit `platform.admin` for any other `Enum.valueOf` on a DB string and apply the same tolerance.
- IT: seed an `admin_alerts` row with a bogus `alert_type`, assert `/api/admin/queue` returns 200 with the other rows and a WARN was logged.

### AC7 — Rolling-deploy: `MigrationConventionLintTest` covers its 3 documented blind spots.

`docs/deployment/migration-conventions.md` § "What the guard cannot catch" (deferred-90 AC10, line 1329) names three:

- **A backported migration below the `V121` grandfather baseline.** Flag any `V<n>__*.sql` where `n <= GRANDFATHER_BASELINE` that is **new in this commit** (not in `git show HEAD:…` / not otherwise present) — a backport past the baseline is how a `DROP` evades the guard. (`MigrationLint` already grew a `LOOKS_VERSIONED` / decimal-version rule in deferred-90's 3-layer review — extend the same class.)
- **An `R__` repeatable migration** containing `DROP` / a validating constraint / a blocking `CREATE INDEX`.
- **An inline `ALTER TABLE … ADD COLUMN … REFERENCES x(y)` FK** (no literal `ADD CONSTRAINT` text) added without `NOT VALID`.
- Fixtures under `src/test/resources/migration-lint/{valid,invalid}/`; update the conventions doc's blind-spot list to reflect what is now covered.

### AC8 — Rolling-deploy: the 6 grandfathered `ACCESS EXCLUSIVE` migrations made online-safe (or documented safe).

For each of **V60, V89, V94, V97, V98, V117** (`deferred-work.md`: `skillars-6-6` W3, `skillars-11-3` D1/D2, `skillars-uat-3` D13, `skillars-deferred-33`, `skillars-deferred-40`, `skillars-deferred-84`):

- Read the migration. If its target table can grow large in production and it takes a validating `ACCESS EXCLUSIVE` lock (`DROP CONSTRAINT`/`ADD CONSTRAINT`, a non-`CONCURRENTLY` `CREATE INDEX`, an unbatched full-table `UPDATE` backfill), add a **new** `V123+` migration that re-does the operation online-safely: `ADD CONSTRAINT … NOT VALID` now + `VALIDATE CONSTRAINT` in a later migration; `CREATE INDEX CONCURRENTLY` outside a transaction; a batched backfill. The already-applied migration is immutable — the fix is additive.
- If the table is genuinely small at any realistic scale (`platform_config`, a config-widen CHECK on a tiny table), **document why in the grandfather list** in `docs/deployment/migration-conventions.md` and leave it. Per-migration disposition table in this story's Dev Notes.
- No behaviour change — the constraints/indexes end up identical, just installed without a long lock.

### AC9 — i18n: full de-DE formal-`Sie` consistency pass.

`src/frontend/src/i18n/de-DE/index.js` (`deferred-work.md` line 1353): rewrite **every** informal `du` / `dein` / `deine` / `dich` / `dir` / imperative `-e`/`-` verb form to the formal `Sie` / `Ihr` / `Ihre` / `Ihnen` / `-en Sie` across the whole bundle (onboarding, dashboard, reviews, video — the sections deferred-90 AC12 did not touch).

- One consistent register end-to-end. Grep `\bdu\b|\bdein|\bdich\b|\bdir\b|\bDeine?\b|Lass |schau ` for the sweep surface; record the changed-string count.
- `MessageBundleParityTest` (deferred-90 AC12) already enforces key-set + `{placeholder}` / `|`-arity parity for DE and FR — it must stay green (this AC changes values, not keys).
- Do **not** touch the 4 `AC11`-excluded machine-format sites from deferred-90 (they carry no user-facing German).
- Residual (filed to AC20): a native-German-speaker review of the resulting wording is still owed — this AC delivers a consistent register, not a certified translation.

### AC10 — i18n: fr-FR native-quality accent pass.

`src/frontend/src/i18n/fr-FR/index.js` + `src/main/resources/i18n/messages_fr.properties`: fix pre-existing un-accented strings — `'Session actualisee avec succes'` → `'Session actualisée avec succès'`, `'Votre session a expire'` → `'Votre session a expiré'`, and a full sweep for missing `é`/`è`/`à`/`ê`/`ç` on French words. `MessageBundleParityTest` stays green.

### AC11 — N+1: measure, then batch, `getPublicProfile`.

`CoachProfileService.getPublicProfile` (`skillars-2-3`, `deferred-work.md` line 734/1326) fires ~8 sequential single-row round-trips. The ledger re-evaluated this as "not a classic N+1 — once per single-coach page view, wait for real latency evidence."

- **Task 1: measure.** Add a query-count assertion (Hibernate statistics via an IT, or datasource-proxy) over `getPublicProfile` on a realistic fixture. Record the actual round-trip count.
- If it is `> 4` independent round-trips per call, collapse via `@EntityGraph` / batched fetches / a projection so the count is bounded and independent of how much the profile contains. If it is genuinely ~2–3 cheap indexed reads, **do not** batch speculatively — record the measurement in Dev Notes and file "leave as-is, measured" to AC20.
- Whatever is done, the endpoint's response body is byte-for-byte unchanged (a parity IT).

### AC12 — Bug: `ErrorLog.logError` crashes inside the exception handler on a `%` in the message.

`src/main/java/com/softropic/skillars/infrastructure/message/ErrorLog.java:44` (`deferred-work.md` line 1347): `String.format(msgTemplate + " SUPPORT_ID: %s", helpCode)` where `msgTemplate` is frequently `ex.getMessage()` (passed at ~20 `ApiAdvice` call sites). An exception message containing a `%` (user input echoed by a validation message, a driver error) throws `UnknownFormatConversionException` / `MissingFormatArgumentException` **inside the handler** → a bare 500 with no `ErrorDto` body. Same failure class as `skillars-deferred-90` AC1's NPE.

- Fix: log the template as a parameterised argument, not a format string — `log.error("{} SUPPORT_ID: {}", msgTemplate, helpCode, entries(ctx), throwable)` (and the WARN variant). Removes the `%` hazard entirely.
- Unit test: an exception whose `getMessage()` contains `"%s"` / `"100% failed"` still produces a clean `ErrorDto` with a `helpCode`, no exception escapes.
- Check both `ErrorLog.logError` and `ErrorLog.logExpected`.

### AC13 — Bug: the GDPR video-cascade delete runs with no transaction.

`deferred-work.md` line 1355: `VideoDeletionService.cascadeDeleteForAccount:186` throws `jakarta.persistence.TransactionRequiredException: Executing an update/delete query` on the account-deletion cascade path (`AccountDeletionCascadeListener.onAccountDeleted:46`, reached from `GdprErasureService.erase()` via `AccountDeletionRequestedEvent`). Caught + logged upstream so `GdprErasureIT` stays green — a silently-failing GDPR cascade delete is a **compliance issue**, not just log noise.

- **Task 1: reproduce and diagnose.** Run a clean `GdprErasureIT`; capture the exception. Determine whether the `@Modifying` cascade is *silently not deleting the video rows* or just noisy. Grep every caller of `cascadeDeleteForAccount` and every `@Modifying` query it issues.
- Fix: `@Transactional` on `AccountDeletionCascadeListener.onAccountDeleted` (or on `cascadeDeleteForAccount`), matching how the `@TransactionalEventListener` needs a surrounding tx for `@Modifying`. Confirm no propagation conflict with `GdprErasureService.erase()`'s own `@Transactional`.
- IT: run an erasure for an account with videos; assert the `videos` (and `video_approval_requests`, `video_quotas`, whatever the cascade covers) rows are **actually gone** afterwards, and no `TransactionRequiredException` is logged.

### AC14 — Bug: cross-tab logout is invisible when the backend `/logout` stalls.

`deferred-work.md` line 1351 (deferred-90 3-layer review): the AC3 fast-teardown branch in `sessionManager.js` `computeTimeUntilExpiry()` needs `rint` absent, but nothing on the client clears `rint` — it is removed only by the backend logout `Set-Cookie`, which is `Promise.race`d against `LOGOUT_BACKEND_WAIT_MS`. If that request stalls / errors, sibling tabs keep `rint` and keep rendering an authenticated UI until the stale `rint` deadline fires.

- Fix: `handleLogout` (`src/frontend/src/composables/useSession.js:71-90`) clears the `rint` cookie client-side (same `document.cookie` expiry write it already does for `user` / `skp`), so a sibling tab's tick enters the fast-teardown branch regardless of the backend call's fate.
- ESLint + build only (no frontend test framework). Verify by code-reading the tick gate: `checkIntervalId !== null && hasSeenRintThisTab() && !hasUserSession()` still holds once `rint` is gone.

### AC15 — Gap: `permitAll()` path patterns match same-segment siblings.

`src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java` (`deferred-work.md` line 1298): `resend-otp**`, `register**`, `verify-email**` have no `/` before `**`, so `/api/security/coach/resend-otp**` also matches `…/resend-otp-admin` — a future controller under that prefix is silently `permitAll()` with no review step.

- Audit **every** `PUBLIC_ENDPOINTS` / `permitAll()` pattern in `AppEndpoints`. Anchor each so it cannot match a same-segment sibling: `resend-otp/**` (+ an exact `resend-otp` entry if the bare path is a real endpoint), or an exact pattern. `PathPatternRequestMatcher` semantics: a trailing `/**` does not cross `/`, an in-segment `**` does.
- Add a convention test asserting no `permitAll()` pattern in `AppEndpoints` ends in a non-`/`-preceded `**`.
- Verify no currently-public endpoint's path becomes non-matched by the tighter pattern (list them in Dev Notes).

### AC16 — Gap: de-triplicate `resendPhoneOtp`.

`ParentRegistrationService.java:226-252`, `CoachRegistrationService.java:230-248`, `PlayerRegistrationService.java:256-274` (`deferred-work.md` line 1297) are byte-identical bar the `@RateLimited` key and the OTP-email event type. Extract to one shared collaborator (`RegistrationOtpResendSupport` in `platform.security.service`, injected into all three) parameterized by the role rate-limit key + an OTP-email-event factory. Behaviour byte-identical; the existing per-service ITs (`CoachRegistrationResourceIT`, `PlayerRegistrationResourceIT`, parent's) stay green. Remove the "mirrors parent exactly" comments the extraction makes true by construction.

### AC17 — Bug: `IllegalStateException` → 500, not 409, for a missing config key.

`ApiAdvice`'s `illegalStateExceptionHandler` (`deferred-work.md` line 812, `skillars-1-1`): a missing / non-numeric `platform_config` key surfaces as `IllegalStateException` → currently `409 CONFLICT`, which is a server misconfiguration masquerading as a client conflict and hides it from 5xx alerting. Map it to `500` (with an ERROR log carrying a `helpCode`, via `ErrorLog`). Audit every `throw new IllegalStateException` reachable by a request to confirm none legitimately means "409" (grep `platform` — `ConfigService.getBoundedLong` / `getLong` / `getString` are the expected sources).

### AC18 — Gap: `app.bootstrap.jwt-secret.enabled` gets an enforced guard.

`JwtSecretBootstrapRunner` (`deferred-work.md` line 1252, `skillars-deferred-83`): the flag "MUST stay unset in every real environment" is enforced only by a javadoc comment. Add a fail-fast at startup — e.g. require a co-located `app.bootstrap.jwt-secret.i-understand-dev-only=true`, or refuse to run when `spring.datasource.url` does not target `localhost`/`127.0.0.1` (dev's call, record the signal chosen). A misconfigured non-dev deploy that sets the flag `true` must **fail to boot**, not silently seed a known secret. `application-dev.yaml` updated so dev still works.

### AC19 — Nit: `MessagingService.buildSummaryContext` player-name dedup.

deferred-90's deferred nit: `buildSummaryContext` inlines `playerProfileRepository.findAllById(...).forEach(...)` where `PlayerProfileService.getPlayerNamesByPlayerIds` already does exactly that. Do the single deliberate pass over all six `MessagingService → PlayerProfileRepository` direct call sites — route the ones that want "id → name" through the shared method; for each that stays direct, add a one-line comment saying why (cross-module dep, different projection, etc.). Keep the null-tolerant collector deferred-90 added.

### AC20 — `deferred-work.md` ledger hygiene + `sprint-status.yaml`.

Delete every item this story closes (per the file's own "delete outright, don't tag" convention). Add `## Deferred from: skillars-deferred-91 story creation (2026-09-03)` with the residuals: SLU non-gating perf-trend signal (`deferred-89` line 1290, still not built); the standing frontend-test-framework gap; the de-DE native-speaker verification still owed after AC9; anything AC5 Task 0 spins out as its own story; `getPublicProfile` "leave as-is, measured" if AC11's measurement says so; the `AFTER_COMMIT` reliability catalogue entries not covered (if any listener is deliberately left un-migrated, say which and why). Flip `skillars-deferred-91-*` to `done` in `sprint-status.yaml` on completion (dev sets it to `review` first, per the workflow).

---

## Tasks / Subtasks

- [ ] **Task 0 — Pre-implementation re-verification.** For each AC, open the named files on the current branch and confirm the defect is still present as described (line numbers will have drifted from deferred-90). Explicitly: confirm `ErrorLog.java:44` still `String.format`s the template (AC12); reproduce the `GdprErasureIT` `TransactionRequiredException` (AC13); grep every `@TransactionalEventListener(AFTER_COMMIT)` in `platform.payment` and list them (AC2); confirm the 6 grandfathered migration files and read each (AC8); grep the de-DE informal-form surface and count strings (AC9); confirm the `AppEndpoints` patterns (AC15).
- [ ] **Task 1 (AC1)** — reusable outbox: table migration (`V123+`, additive, lint-clean) + `OutboxService` (`enqueue` / `requestDrainAfterCommit` / `drain`) + `OutboxChunkProcessor` (`@Transactional(REQUIRES_NEW)`, `FOR UPDATE SKIP LOCKED`, `CHUNK_SIZE=25`) + `@Scheduled @SchedulerLock` sweeper + a handler-dispatch SPI. Unit + one Testcontainers IT. **Do not** regress to a self-invoked `@Transactional drain()`.
- [ ] **Task 2 (AC2)** — wire `CancellationRefundService`'s refund `AFTER_COMMIT` listeners (+ siblings) onto the outbox; idempotent refund handler keyed on the existing Stripe idempotency key; forced-failure re-drive IT.
- [ ] **Task 3 (AC3)** — wire `BookingEmailListener` / `SessionPackEmailListener` onto the outbox; keep the `expiryWarnedAt` dedupe stamp; per-listener re-drive IT.
- [ ] **Task 4 (AC4)** — SLU snapshot under-report: outbox re-drive of `writeAllWithRetry`, or a marker-gap reconciliation sweep; respect the `V119` marker (no over-report regression); IT forcing `@Recover` then reconciling.
- [ ] **Task 5 (AC5) — design first.** `docs/architecture/payout-and-capture-pending.md` (fund-hold, completion signal, dispute-rebuttal window, Stripe Connect timing) → review with owner → then (a) `CAPTURE_PENDING` timeout → terminal state + slot release + `unrecoverable` alert; (b) completion-gated payout per the design. File anything larger to AC20.
- [ ] **Task 6 (AC6)** — `AdminQueueService.buildSummary` skips an unknown `alert_type` with a WARN; audit `platform.admin` for sibling `Enum.valueOf`-on-DB-string; IT with a bogus `alert_type` row.
- [ ] **Task 7 (AC7)** — 3 new `MigrationLint` rules (backport-below-baseline, `R__` DROP/validate, inline `ADD COLUMN … REFERENCES`) + fixtures; update `migration-conventions.md` blind-spot list.
- [ ] **Task 8 (AC8)** — per-migration disposition of V60/V89/V94/V97/V98/V117: online-safe follow-up migration where the table can grow; documented-safe otherwise. Disposition table in Dev Notes.
- [ ] **Task 9 (AC9)** — de-DE `du` → `Sie` bundle-wide rewrite; changed-string count; `MessageBundleParityTest` green.
- [ ] **Task 10 (AC10)** — fr-FR accent sweep (frontend + `messages_fr.properties`); parity test green.
- [ ] **Task 11 (AC11)** — measure `getPublicProfile` round-trips (Hibernate statistics IT); batch via `@EntityGraph`/projection only if `> 4`; response-parity IT; record the measurement.
- [ ] **Task 12 (AC12)** — `ErrorLog` `logError` + `logExpected` → parameterised logging; unit test with a `%`-bearing message.
- [ ] **Task 13 (AC13)** — reproduce + diagnose the GDPR cascade; `@Transactional` on the listener/method; IT asserting the video rows are actually deleted.
- [ ] **Task 14 (AC14)** — `handleLogout` clears `rint` client-side; ESLint + build.
- [ ] **Task 15 (AC15)** — anchor every `AppEndpoints` `permitAll()` pattern; convention test; list affected paths.
- [ ] **Task 16 (AC16)** — extract `RegistrationOtpResendSupport`; three services delegate; existing ITs green.
- [ ] **Task 17 (AC17)** — `IllegalStateException` → 500 in `ApiAdvice`; audit reachable `throw new IllegalStateException` sites.
- [ ] **Task 18 (AC18)** — enforced boot guard on `app.bootstrap.jwt-secret.enabled`; `application-dev.yaml` still boots.
- [ ] **Task 19 (AC19)** — `MessagingService` six-call-site dedup pass.
- [ ] **Task 20 (AC20)** — `deferred-work.md` deletions + `skillars-deferred-91` residuals section; `sprint-status.yaml`.
- [ ] **Task 21 — Verification gate.** Backend: targeted `-Dtest` green per touched module + the outbox/refund/notification/SLU ITs. Frontend: `eslint` + `quasar build`; `prettier --check` on touched files (note pre-existing failures, do not widen). No local `mvn verify` ([[feedback_no_local_mvn_verify]]) — GitHub CI (`mvn -B verify` + Trivy + Docker + context-count gate) is the full-suite gate. **Context-count gate:** any new `@SpringBootTest` IT must extend `AbstractIntegrationTest` with **no** class-level `@TestPropertySource` / `@ActiveProfiles` / extra `@MockitoBean` — `IntegrationTestConventionTest` pins the context count at 37 (see deferred-90's `MessagingConversationSummaryBatchingIT`).

## Dev Notes

### Scope discipline

This story is large by explicit project-owner direction. It is **not** open-ended: the 20 ACs are the scope. AC5 Task 0 is the one place new scope can appear — anything it identifies beyond "CAPTURE_PENDING timeout + a completion-gated payout hook" is filed to `deferred-work.md`, not built here.

### The outbox is a generalization, not a rewrite

`skillars-deferred-90`'s `PendingBlobDeletionService` is the reference implementation and it already survived a 3-layer adversarial review that forced the final shape (separate `ChunkProcessor` bean so `@Transactional(REQUIRES_NEW)` goes through the proxy; `FOR UPDATE SKIP LOCKED`; `drain()` loops to empty; sweeper; `app.scheduling.enabled` test gate). **Copy that shape.** The generic version adds only: a discriminator column + a handler-dispatch SPI so more than one domain can share the table. Consider whether `PendingBlobDeletionService` itself should be re-expressed on top of the generic outbox (nice-to-have, not required — do not destabilise a just-shipped, just-reviewed component without a reason).

### AFTER_COMMIT call-site inventory (fill in during Task 0)

Grep `@TransactionalEventListener` across `src/main`. Known from the ledger: `CancellationRefundService.onBookingCancelledByAdmin` / `onBookingCancelledByCoach` / `onCoachNoShow` (refund — AC2); `BookingEmailListener` (cancellation email — AC3); `SessionPackEmailListener.onExpiryWarning` (AC3); `SessionPackForfeitureScheduler` publishes inside a tx and works (leave); `SluPersistenceDispatcher` / `SnapshotPersistenceRetrier` (AC4). `AccountDeletionCascadeListener.onAccountDeleted` is `@EventListener` not `@TransactionalEventListener` and is a **different** bug (AC13).

### Migration conventions

Every new migration in this story is `V123` or higher and MUST pass `MigrationConventionLintTest` and follow `docs/deployment/migration-conventions.md` (expand/contract; additive first; guarded `DROP` last; FK/CHECK `NOT VALID` then `VALIDATE` later; `CREATE INDEX CONCURRENTLY` on hot tables; batched backfills). The outbox table (AC1) is a plain additive `CREATE TABLE`. AC8's follow-ups are the online-safe re-do migrations — they are the convention's own worked examples.

### i18n

`MessageBundleParityTest` (deferred-90 AC12) is the guard: key-set parity + `{placeholder}` / `|` pluralization-arity parity for DE and FR against EN. AC9/AC10 change **values only** — if the parity test breaks, a key was accidentally touched. The 4 machine-format exclusion sites from deferred-90 AC11 (`WeeklyCalendar.vue` `'en-CA'`/`'en'` geometry, `AvailabilityManagerPage.vue` / `BookingRequestPage.vue` `'en-CA'` offsets, `CoachCommandCenterPage.getDayIndex`) carry no user-facing text — do not touch.

### Testing traps carried from prior stories

- `@SchedulerLock`-annotated methods invoked from a test go through the proxy → ShedLock applies → with `lockAtLeastFor` a second invocation in the same test class is silently skipped. Use `BasePaymentIT.releaseSchedulerLock(name)` / the equivalent before each invocation. (`skillars-deferred-15` lesson.)
- A concurrency IT that "proves" a lock frequently passes without it because the two threads' pre-lock work isn't symmetric (`skillars-deferred-13` / `-15` / `uat-3` D11, and deferred-90's SLU IT). If AC2/AC4 need a serialisation proof, scope the block probe to a known backend PID via `pg_blocking_pids()` (deferred-90's reworked `SluCalculationServiceIT` is the pattern) rather than a fuzzy `pg_stat_activity` query-text match.
- New IT context-forking: `IntegrationTestConventionTest` pins the `@TestPropertySource`-carrying-IT count. `hibernate.generate_statistics` for AC11 needs a property — either fold the measurement into an existing statistics-enabled context, or accept the pinned-count bump with a justification comment (deferred-90 chose to keep O(1) proof at the unit level for this reason).

### Project Structure Notes

- Backend module hierarchy `com.softropic.skillars.platform.{module}.{api|service|repo|contract|config}`. A generic outbox is infrastructure-ish but it carries domain payloads and a domain-lifecycle scheduler → per `project-context.md` § "Schedulers belong to platform", it lives in `platform.{module}` (or a new `platform.outbox`), **not** `infrastructure`. The `PendingBlobDeletionService` precedent is `platform.filestorage.service` — follow it.
- All request/response DTOs are Java `record`s; MapStruct for entity/DTO; `@PreAuthorize` + `SecurityConstants` on every resource method; Jakarta Validation on request records. Frontend: `<script setup>`, `async/await`, centralized `*.api.js`, all user-facing text via `vue-i18n`.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — lines 612 (`10-2` D1), 716/862 (`3-6` W3 / `3-10` D2), 941 (`deferred-15` D1), 886 (`6-3` W3), 1288/1290 (`deferred-89` SLU + perf-trend), 990/1010/1012 (`uat-3` D3/D13/D14), 1189 (`deferred-63` escrow), 947 (`deferred-16` D4), 1329 (deferred-90 lint blind spots), 894 (`6-6` W3 V60), 927 (`11-3` V89), 1000 (`uat-3` V94), 1065 (`deferred-33` V97), 1077 (`deferred-40` V98), 1258 (`deferred-84` V117), 1353 (deferred-90 de-DE register), 734/1326 (`2-3` `getPublicProfile`), 1347 (`ErrorLog` `%`), 1355 (GDPR cascade), 1351 (cross-tab `rint`), 1298 (`resend-otp**`), 1297 (`resendPhoneOtp` triplication), 812 (`1-1` 409), 1252 (`deferred-83` jwt-secret).
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-90-…-n-plus-1-query-batching.md`] — `PendingBlobDeletionService` outbox shape, `MigrationConventionLintTest`, `MessageBundleParityTest`, `MessagingConversationSummaryBatchingIT` (no-fork IT pattern), reworked `SluCalculationServiceIT` (PID-scoped lock probe).
- [Source: `docs/deployment/migration-conventions.md`] — expand/contract standard, grandfather list, "What the guard cannot catch".
- [Source: `_bmad-output/project-context.md`] — module structure, "Schedulers belong to platform", `@TransactionalEventListener` gotchas, rolling-deploy migration rules for `V > 121`.
- [Source: `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionService.java`, `PendingBlobDeletionChunkProcessor.java`, `repo/PendingBlobDeletionRepository.java`, `src/main/resources/db/migration/V122__pending_blob_deletions.sql`]

## Dev Agent Record

### Agent Model Used

_TBD_

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Version | Change |
| --- | --- | --- |
| 2026-09-03 | v0.1 | Story created via story-creation process. 20 ACs. Four project-owner "full-scope" decisions folded in (outbox generalization, escrow design pass, de-DE `Sie` rewrite, all 3 rolling-deploy items). Anchor one-off bugs AC12–AC19. Standing decisions recorded (startSessionMonitoring, frontend test framework). Status → ready-for-dev. |
