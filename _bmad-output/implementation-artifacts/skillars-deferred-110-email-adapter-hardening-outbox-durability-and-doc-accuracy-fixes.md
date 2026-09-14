# skillars-deferred-110: Email Adapter Hardening, Outbox Durability & Doc Accuracy Fixes

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high
**Story ID:** deferred-110
**Branch:** `story/deferred-110-email-adapter-hardening`
**Created:** 2026-09-14

---

## Story Overview

A cross-cutting cleanup story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`, mined after
`ses-1-7-documentation` shipped (PR #186). All 11 items below were independently re-verified against `HEAD`
(`master@f6a093e0`) at story creation — every cited file:line was re-read directly, not trusted from the
ledger's (sometimes drifted) line numbers. `ses-1-5-ses-cutover` and `ses-1-6-delete-smtp` remain `backlog`,
gated on the still-open D-1 sending-domain decision — nothing in this story touches the transport cutover
itself; every item here is a bug fix or gap in the already-shipped `ses-1.1`–`ses-1.4`/`ses-1.7` code, and one
frontend fix + one CI hardening item carried over from `skillars-deferred-109`'s code review.

Themes:

1. **SMTP adapter hardening** — the classifier misses a real JavaMail failure shape; the provider bean has no
   startup validation and can divide by zero or wrap to a negative array index.
2. **Outbox/retry correctness** — a video-moderation alert can silently drop on a persistence-visibility race; a
   renamed/removed email template creates an unrecoverable poison row; `envelope_entity` has no Flyway DDL
   despite two stories now depending on its unique constraint.
3. **Frontend defect + CI gap** — a batch-accept failure path can wipe real results instead of just clearing a
   stale seed; the frontend Vitest job never runs under a non-UTC timezone, so DST-class regressions pass
   silently.
4. **Doc accuracy** — a config-property name collides with the transactional outbox; two dev-docs statements are
   over-broad or incomplete, both found by `ses-1-7`'s own code review and explicitly left for a follow-up story.
5. **Decision-needed:** one item (AC4) widens an existing retry-exclusion list — flagged for explicit owner
   sign-off during dev-story/code-review, per this project's established pattern (see `ses-1.3`/`ses-1.4`'s own
   decision-needed ACs).

**Source:** `deferred-work.md`, re-verified 2026-09-14 against `master@f6a093e0`. Ledger citations below name
the exact `## Deferred from: ...` heading each item closes.

---

## User Story

**As a** platform engineer responsible for Skillars' email reliability and the outbox pipeline it depends on,
**I want** the SMTP adapter's missing validation/classification gaps closed, the outbox's two unrecoverable
failure modes fixed, one real frontend batch-accept defect patched, a CI blind spot on timezone-dependent tests
closed, and the doc-accuracy gaps `ses-1-7`'s own code review surfaced but deferred fixed,
**so that** a misconfigured SMTP provider fails fast instead of throwing an unclassified exception from a
production send path, a poisoned or racing outbox row can no longer wedge forever, a coach's batch-accept UI
never silently loses real results, DST-class frontend regressions can't ship unnoticed, and the dev-docs this
project relies on for onboarding stay accurate.

---

## Acceptance Criteria

> Legend: each AC ends with **Ledger** (the `deferred-work.md` bullet it closes) and **Test** (verification).
> Frontend ACs must pass `npx eslint src/` + `npx prettier --check src/` and `quasar build`. Backend ACs:
> GitHub CI is the full-verification gate (do **not** run `mvn verify` locally); `mvn -o test-compile` + targeted
> `mvn -o test -Dtest=...` for touched classes is the local sanity bar.

### AC1: `NoHardcodedSenderTest` must catch a second hardcoded `from-address` in the same file

- **Task:** Change `noRealDomainLiteral_andNoNonEmptyDefaultOutsideDev` to assert its invariant against
  **every** `from-address:` match in each file, not just the first.
- **Verified at HEAD:** `src/test/java/com/softropic/skillars/infrastructure/ses/NoHardcodedSenderTest.java:31`
  — `Matcher matcher = FROM_ADDRESS_LINE.matcher(content); assertThat(matcher.find())...` calls `find()` exactly
  once per file inside the `for (Case c : cases)` loop. A second, regression `from-address: noreply@skillars.com`
  line added later in the same YAML is never examined — the guard holds only for today's single-occurrence
  files.
- **Fix approach:** Replace the single `matcher.find()` assertion with a loop over `matcher.results()` (or
  `while (matcher.find())`), asserting the same default-value invariant on every match, and additionally assert
  at least one match was found (preserve the current "must set via placeholder" failure mode for zero matches).
- **Files:** `NoHardcodedSenderTest.java`.
- **Test:** this AC's own rewritten test IS the test — add a temporary local mutation (a second `from-address`
  line with a real domain) during development to confirm it now fails, then revert.
- **Ledger:** `## Deferred from: code review of ses-1-1-introduce-outbound-email-port (2026-09-11)` — "
  `NoHardcodedSenderTest` only inspects the first `from-address` match per file."

### AC2: `SmtpErrorClassifier` must inspect `MailSendException.getFailedMessages()`

- **Task:** When classifying a `MailSendException`, also scan `getFailedMessages().values()` (and each value's
  own cause chain) against `NON_REPAIRABLE_ERRORS`, in addition to the existing direct/cause/cause-of-cause walk.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:35-48`
  — `classify(Exception ex)` builds `direct`/`cause`/`causeOfCause` from `ex` itself and never touches
  `MailSendException`'s own `getFailedMessages()` map. `JavaMailSenderImpl.doSend` throws
  `new MailSendException(failedMessages)` for a per-recipient failure (e.g. a 550 rejection) with **no cause
  set on the outer exception** — the real `SendFailedException`/`AddressException` lives only inside that map.
  Today this misclassifies a permanent recipient rejection as transient, burning all 6
  `EmailRetryScheduler` attempts plus circuit-breaker failure-rate pressure (`slidingWindowSize=5,
  failureRateThreshold=50%`) that a single bad address can use to degrade delivery for unrelated mail.
- **Fix approach:** In `classify`, if `ex instanceof MailSendException mse`, additionally stream
  `mse.getFailedMessages().values()` — each a `Throwable` — through the same `NON_REPAIRABLE_ERRORS` check
  (walking each value's own `getCause()`/`getCause().getCause()` too, mirroring the existing depth), before or
  alongside the existing direct-exception walk. Keep the existing walk for every other exception type unchanged.
- **Files:** `SmtpErrorClassifier.java`, `SmtpErrorClassifierTest`.
- **Test:** new case — build a `MailSendException` with an empty/null cause whose `failedMessages` map contains
  one `AddressException` → assert `EmailTransportPermanentException`. Existing direct-throw and
  one-level-wrapped cases must stay green.
- **Ledger:** `## Deferred from: code review of ses-1-2-smtp-behind-port-and-containment (2026-09-11)` —
  "`SmtpErrorClassifier` never inspects `MailSendException.getFailedMessages()`."

### AC3: `SmtpPropertiesValidator` (new) + `MailSenderProvider.nextSender()` overflow/divide-by-zero fix

- **Task:** Add a `SmtpPropertiesValidator` mirroring `SesPropertiesValidator`'s shape, active only when
  `app.email.transport=smtp`, that fails fast on a malformed provider list; separately fix
  `MailSenderProvider.nextSender()`'s modulo so it can never divide by zero or return a negative index.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProvider.java`
  — the constructor (`:26-29`) calls `toMailSender` unconditionally for every configured provider, with no
  `@ConditionalOnProperty` gate on the bean itself (so `Integer.parseInt(providerConfig.getPort())` at `:53`
  runs in **every** profile, including prod where `transport=ses` and SMTP is unused) and no validation of
  `host`/`username`. `nextSender()` (`:66-70`) computes `currentCounterValue % providers.size()`: an empty
  `app.email.smtp.provider-configs` list under `transport=smtp` throws `ArithmeticException: / by zero`, and
  `AtomicInteger` overflow after 2^31 calls wraps to `Integer.MIN_VALUE`, which `% N` yields negative for any
  provider count other than a power of 2 sharing `MIN_VALUE`'s parity — an `IndexOutOfBoundsException` neither
  `SmtpEmailSender`'s `catch (MessagingException | MailException)` nor anything upstream catches. No
  `SmtpPropertiesValidator` file exists anywhere under `src/main` (confirmed by search) — `SesPropertiesValidator`
  (`infrastructure/ses/`) is the direct sibling model, added in `ses-1.1` for exactly this class of gap on the
  SES side.
- **Fix approach:**
  - New `SmtpPropertiesValidator`: `@Component`, `@ConditionalOnProperty(name = "app.email.transport",
    havingValue = "smtp")`, `@PostConstruct validate()`. Reject (throw `AppSetupException`, naming the offending
    property, matching this codebase's existing convention) a null/empty provider list; for each
    `ProviderConfig`, reject a blank `host`, a `port` that fails `Integer.parseInt` (catch
    `NumberFormatException`, re-throw naming the property and provider index/host), and a blank `username`.
  - `MailSenderProvider.nextSender()`: replace `currentCounterValue % providers.size()` with
    `Math.floorMod(counter.getAndIncrement(), providers.size())` — removes the negative-index case. The
    divide-by-zero case for an empty list is closed by the new validator running first at boot (a
    `@PostConstruct` in a sibling bean does not guarantee ordering relative to `MailSenderProvider`'s own
    constructor — if ordering can't be guaranteed cheaply, also guard `nextSender()`/the constructor directly
    against an empty list with a clear `AppSetupException`, not just rely on the validator).
- **Files:** new `SmtpPropertiesValidator.java` + test; `MailSenderProvider.java`; `MailSenderProviderTest`
  (new or extended).
- **Test:** validator — empty provider list, blank host, non-numeric port, blank username each throw
  `AppSetupException` naming the property; a valid config passes. `MailSenderProvider` — `nextSender()` called
  with `counter` pre-set near `Integer.MAX_VALUE` (via reflection or a small counter-seam) never returns a
  negative index across the rollover.
- **Ledger:** `## Deferred from: code review of ses-1-2-smtp-behind-port-and-containment (2026-09-11)` —
  "`MailSenderProvider` round-robin counter overflow indexes a negative position", "`SmtpEmailSender`/
  `MailSenderProvider.nextSender()` misclassifies an empty provider list as retryable", "No
  `SmtpPropertiesValidator` counterpart to `SesPropertiesValidator`."

### AC4: `EmailTransportPermanentException` should stop being retried — **decision needed**

- **Task:** Add `.notRetryOn(EmailTransportPermanentException.class)` to `ComponentConfig.retryTemplate()`,
  alongside the existing `EmailTransportRateLimitedException` exclusion.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java:61-66`
  — `retryTemplate()` calls only `.notRetryOn(EmailTransportRateLimitedException.class)`. Every other exception
  type, including `EmailTransportPermanentException` (already listed in `MailManager.NON_REPAIRABLE_ERRORS`),
  keeps the builder's `true`-retry default — burning 3 attempts with 1s fixed backoff inside an open
  `REQUIRES_NEW` transaction and against the circuit breaker's 10s `TimeLimiter`, for a failure the codebase
  already knows cannot be fixed by retrying.
- **Decision needed (surface to owner before/at dev-story):** `ses-1.3`'s own AC4 deliberately scoped itself to
  "exactly one new exception type, nothing else" when it added the rate-limited exclusion, and its code review
  explicitly deferred this exact fix as "a behaviour change for every permanent failure [that] deserves its own
  decision" rather than folding it in silently. The fix itself is a one-line, low-risk change — the decision is
  purely whether widening the exclusion now (vs. leaving it deferred again) is wanted. Record the decision and
  rationale in the Dev Agent Record either way; if declined, leave this AC unimplemented and re-annotate the
  ledger bullet with the renewed deferral rather than silently dropping it.
- **Fix approach (if approved):** the one-line addition above. No other change — `EmailTransportPermanentException`
  is already excluded from `MailManager.isRetryable`'s own re-drive logic; this only stops `ComponentConfig`'s
  separate `RetryTemplate` (used for the synchronous send attempt) from burning 3 in-process attempts on a
  failure already known to be permanent.
- **Files:** `ComponentConfig.java`.
- **Test:** a `RetryTemplate`-level unit/slice test asserting a callable throwing `EmailTransportPermanentException`
  executes exactly once (no retry), while one throwing a plain transient exception still retries 3x.
- **Ledger:** `## Deferred from: code review of ses-1-3-health-monitoring-rate-limiting (2026-09-12)` —
  "`EmailTransportPermanentException` is still retried 3x by `ComponentConfig.retryTemplate()`."

### AC5: `VideoModerationEmailListener` must not silently release the outbox row on an unresolved read-back

- **Task:** When the post-send `EnvelopeEntity` read-back returns `null` (a persistence-visibility race, not a
  confirmed outcome), perform a short bounded re-read before falling back to today's WARN-and-return, which the
  outbox handler treats identically to a confirmed successful send.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java:94-106`
  (`sendAdminAlertSync`) — `envelopeEntityRepository.findBySendId(envelope.sendId())` returning `null` logs a
  WARN and returns normally. `MailManager.sendEmailSync` always persists an `EnvelopeEntity` (SENT or FAILED),
  so a `null` read-back here is specifically a race between that `REQUIRES_NEW` commit becoming visible and this
  read — not a real "unknown" state that should be treated as done. `ModerationAdminAlertOutboxHandler` deletes
  the durable outbox row on any normal return from this method, so an unresolved race silently drops the
  admin-alert row exactly as if it had succeeded.
- **Fix approach:** On `persisted == null`, retry the read (2–3 bounded attempts with a brief fixed delay,
  matching this codebase's existing bounded-retry idiom — see `PessimisticLockRetryer` for the shape/timeout
  philosophy). If still unresolved after the bounded window, throw (rather than return normally) so the outbox
  handler retains the row instead of deleting it on an unproven outcome.
- **Files:** `VideoModerationEmailListener.java`, `VideoModerationEmailListenerTest` /
  `VideoModerationAdminAlertEnvelopeIT`.
- **Test:** a case that mocks the repository to return `null` on the first N reads and a real row after — asserts
  the method proceeds past the bounded retries and completes normally. A case where it never resolves within the
  bounded window asserts the method throws (row retained, not silently dropped).
- **Ledger:** `## Deferred from: code review of skillars-deferred-109 (2026-09-11)` — the
  `VideoModerationEmailListener` `persisted == null` bullet.

### AC6: `booking.store.js` `handleAcceptAllBatch` must not wipe real accept results on a post-success refresh failure

- **Task:** In the `catch` block, only delete the `batchId` entry from `batchAcceptResultsByBatch` if it is
  still the `null` seed written at the top of the function — not when it already holds real results from a
  completed `acceptAllBatch` call followed by a failing `loadCoachBookingRequests()` refresh.
- **Verified at HEAD:** `src/frontend/src/stores/booking.store.js:635-666` — `setBatchAcceptResult(batchId, null)`
  seeds the entry (`:638`); if `acceptAllBatch` succeeds, `setBatchAcceptResult(batchId, results)` overwrites it
  with the real per-booking results (`:646`); if the subsequent `await loadCoachBookingRequests()` (`:653`)
  throws, control reaches `catch` (`:655`), which calls `deleteBatchAcceptResult(batchId)` (`:661`)
  **unconditionally** — deleting the real results, not just the stale `null` seed the surrounding comment
  describes clearing.
- **Fix approach:** Guard the delete: `if (batchAcceptResultsByBatch.value[batchId] === null) { deleteBatchAcceptResult(batchId) }`
  (or equivalent), so a real-results entry survives a refresh failure and remains readable by the caller per
  this function's own documented contract ("callers read results from here").
- **Files:** `booking.store.js`.
- **Test:** unit — `acceptAllBatch` succeeds, `loadCoachBookingRequests` rejects → `batchAcceptResultsByBatch[batchId]`
  still holds the real results after the catch (not deleted); `acceptAllBatch` itself rejects → the `null` seed
  is deleted as today.
- **Ledger:** `## Deferred from: code review of skillars-deferred-109 (2026-09-11)` — the
  `handleAcceptAllBatch`/`deleteBatchAcceptResult` bullet.

### AC7: Frontend Vitest CI job must also run under a non-UTC timezone

- **Task:** Add a second run of the same Vitest suite under a non-UTC timezone (e.g. `America/New_York`), so a
  DST-boundary regression fails CI instead of passing silently.
- **Verified at HEAD:** `.github/workflows/frontend-unit-tests.yml` — one job (`frontend-unit`), no matrix, no
  `TZ` env var; `happy-dom`'s `Intl`/date handling in this job is UTC-only. `skillars-deferred-109`'s own AC7.2
  spec is cited by the ledger as reproducibly failing under `TZ=America/New_York` — this job would never have
  caught that on its own.
- **Fix approach:** Add a `TZ: America/New_York` env var to a second leg — either a `strategy.matrix` on the
  existing job (`tz: [UTC, America/New_York]`, passed through as `env.TZ`) or a duplicated job — running the
  same `npm run test:unit:ci` step. Keep the job opt-in/non-gating exactly as today (this workflow is
  deliberately decoupled from `ci.yml`/`pr-build.yml` per its own header comment) — do not add it to any
  required check.
- **Files:** `.github/workflows/frontend-unit-tests.yml`.
- **Test:** this is itself a CI-config AC — verify by triggering the workflow manually (`workflow_dispatch`)
  after the change and confirming both legs run and both pass (or, if a currently-passing spec is timezone-
  sensitive and was masked by UTC-only CI, confirming it now surfaces — fix or explicitly note any such spec
  found, as a deviation).
- **Ledger:** `## Deferred from: code review of skillars-deferred-109 (2026-09-11)` — the CI-timezone bullet.

### AC8: `NotificationEmailOutboxHandler` must not create an unrecoverable poison row for a renamed/removed template

- **Task:** Catch `IllegalArgumentException` from `EmailTemplate.valueOf(p.template())` and let the row
  complete (log + release) rather than let it propagate into the generic outbox retry-forever path.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/notification/service/NotificationEmailOutboxHandler.java:76`
  — `EmailTemplate.valueOf(p.template())` is called directly, no surrounding try/catch for this specific
  conversion. Payloads carry the template as a raw `String` and can sit in `outbox_messages` up to the 24h
  default deadline; if a rolling deploy renames/removes the enum constant a still-live row was queued under,
  `valueOf` throws `IllegalArgumentException`, which `OutboxRowProcessor.claimAndHandle` wraps, backs off, and
  retries **forever** — unlike the structurally similar "no handler registered" case this same class's own
  javadoc (`:21-42`) already documents a `retry == false` / log-and-complete pattern for, a missing enum
  constant has no equivalent guard and, unlike a missing handler, can never self-resolve.
- **Fix approach:** Wrap the `EmailTemplate.valueOf(...)` call in `try/catch (IllegalArgumentException)`; on
  catch, log an ERROR (mirroring this class's own `[...]`-tagged undeliverable-row logging convention already
  in use for the `retry == false` case) and complete/release the row rather than let the exception propagate
  into the generic retry-forever path.
- **Files:** `NotificationEmailOutboxHandler.java`, its test class.
- **Test:** a case with a payload `template` string that does not match any `EmailTemplate` constant → asserts
  the row completes (no retry-forever loop) and the ERROR log fires, rather than an unhandled exception
  reaching `OutboxRowProcessor`.
- **Ledger:** `## Deferred from: code review of ses-1-4-registration-email-durability (2026-09-12)` —
  "`EmailTemplate.valueOf(p.template())` creates an immortal poison outbox row for a removed or renamed
  constant."

### AC9: `envelope_entity` needs a real Flyway migration

- **Task:** Add an additive, `IF NOT EXISTS`-guarded migration creating `envelope_entity` (and its
  `@ElementCollection` recipients table) matching `EnvelopeEntity`'s actual Hibernate-mapped shape, plus a
  guarded unique index on `send_id`.
- **Verified at HEAD:** No `CREATE TABLE envelope_entity` exists in any of the 129 files under
  `src/main/resources/db/migration/` (confirmed by full-directory grep), nor in
  `src/test/resources/sql/createSchema.sql`. `spring.jpa.hibernate.ddl-auto: none` with
  `flyway.baseline-on-migrate: true` (`application.yaml`) means the table and its unique constraint exist in
  every real environment only by out-of-band provenance. `EnvelopeEntity`
  (`src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntity.java`) has `@Id UUID id`,
  `@Version long version`, `@ElementCollection List<RecipientEntity> recipients`, `emailTemplate` (enum
  string), `deadline` (Instant), `data` (jsonb), `attempts` (long), `status` (enum string), `error` (text),
  `retry` (boolean — note its `columnDefinition = "text"` annotation looks like a latent, separate bug worth
  flagging in the Dev Agent Record but is out of this AC's scope to fix), `@Column(unique = true) sendId`.
  `ses-1.4`'s `MailManager.saveAndFlush` and `MailManagerDuplicateSendIdIT` now actively depend on the
  `send_id` unique constraint firing — raising the stakes on this already-known gap, which the project's own
  `docs/dev-docs/notification/index.html:373-380` callout already documents as still open.
- **Fix approach:** Follow `docs/deployment/migration-conventions.md` (additive, `IF NOT EXISTS`-guarded, no
  lock-heavy DDL, next free `V###`). Verify the exact column types/shape against a real Hibernate-generated DDL
  (e.g. a throwaway local run with `ddl-auto=validate` against the new migration) rather than hand-guessing —
  a mismatch would fail Hibernate's own schema validation at boot. `MigrationLint`'s online-safety rules apply
  to this migration like any other.
- **Files:** new `V###__create_envelope_entity.sql` (or equivalent), possibly
  `src/test/resources/sql/createSchema.sql` if that fixture needs the table too (check whether tests currently
  rely on Hibernate `ddl-auto` in the test context, or on this same script).
- **Test:** the new `MigrationConventionLintTest` run against the added migration (existing suite); confirm
  `MailManagerDuplicateSendIdIT` (already exists) still passes with the table now Flyway-managed rather than
  out-of-band.
- **Ledger:** `## Deferred from: code review of ses-1-4-registration-email-durability (2026-09-12)` —
  "`envelope_entity` has no Flyway DDL and its `send_id` unique index is unversioned."

### AC10: Rename `app.email.log.outbox-dir` to stop colliding with the transactional outbox

- **Task:** Rename the log-transport dump-directory property (and every reference to it) to something that
  doesn't share a name with `platform.outbox`'s real transactional outbox.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportProperties.java:33-40` (`outboxDir`
  field + getter/setter), consumed by
  `src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java` (multiple sites),
  configured at `src/main/resources/application-dev.yaml:18` (`outbox-dir: target/mails`), and documented at
  `docs/dev-docs/infrastructure/index.html:182`. Anyone grepping "outbox" while investigating a stuck
  transactional outbox row gets a false lead into this unrelated, dev-only-transport setting.
- **Fix approach:** Rename to `app.email.log.dump-dir` (field `dumpDir`), updating: `EmailTransportProperties`
  (field, getter/setter, javadoc), every `LoggingEmailSender` reference, `application-dev.yaml`'s key, any test
  fixture referencing the old key, and `infrastructure/index.html:182`. Grep for `outbox-dir`/`outboxDir`
  across `src/` and `docs/` after the change to confirm no reference survives.
- **Files:** `EmailTransportProperties.java`, `LoggingEmailSender.java`, `application-dev.yaml`,
  `docs/dev-docs/infrastructure/index.html`, affected tests.
- **Test:** existing `LoggingEmailSender`-related tests still pass under the renamed property; a quick grep
  confirms zero remaining `outbox-dir`/`outboxDir` occurrences.
- **Ledger:** `## Deferred from: code review of ses-1-7-documentation (2026-09-14)` — "`app.email.log.outbox-dir`
  collides in name with the transactional outbox."

### AC11: Close the two remaining `ses-1-7` doc-accuracy gaps (doc-only, sequenced after AC10)

- **Task:** (a) Tighten the "no boolean enable/disable flag anywhere in this area any more" claim on the
  infrastructure dev-doc to name its actual scope; (b) document `email.providerConfigs` and the round-robin
  behaviour it drives, which is currently named with zero description.
- **Verified at HEAD:**
  - `docs/dev-docs/infrastructure/index.html:173-174` — "there is no boolean enable/disable flag anywhere in
    this area any more" is unbounded, but
    `src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java:32` still has
    `@ConditionalOnProperty(name = "enable.test.mail", havingValue = "false", matchIfMissing = true)` gating a
    different-package notification bean — a single surviving toggle in an adjacent area falsifies the absolute
    phrasing as written.
  - `docs/dev-docs/infrastructure/index.html:193` names `MailSenderProvider` under the `infrastructure.email.smtp`
    bullet with no description; neither this page nor `notification/index.html` documents the
    `email.providerConfigs` config key or the gmx/gmail round-robin behaviour `MailSenderProvider` implements —
    `ses-1.7` correctly deleted the old `MailSenderProvider` row from `notification/index.html` and
    cross-referenced this page instead, which surfaced (did not create) this pre-existing documentation gap.
- **Fix approach:** (a) Reword the sentence to scope it explicitly: "...no boolean flag anywhere in
  `infrastructure.email`/`.ses`/`.email.smtp` — `enable.test.mail` (`platform.notification`) is a
  different-area, pre-existing exception, not a stray survivor in this one." (b) Add a short paragraph under
  the `infrastructure.email.smtp` bullet naming `app.email.smtp.provider-configs` (shape: list of `{host, port,
  username, password}`, referencing the renamed-by-AC10 dump-dir property where relevant) and the round-robin
  selection `MailSenderProvider.nextSender()` implements. Do this **after** AC10 lands, so nothing new here
  references the old `outbox-dir` name.
- **Files:** `docs/dev-docs/infrastructure/index.html`. Run the AC6-style tag-balance check (see
  `ses-1-7-documentation.md`'s own AC6 for the exact script) against this file after editing — same
  hand-written-HTML-with-no-linter risk applies here.
- **Test:** the tag-balance script (adapt from `ses-1-7-documentation.md` AC6) run clean against the edited
  file.
- **Ledger:** `## Deferred from: code review of ses-1-7-documentation (2026-09-14)` — the "unbounded ... no
  boolean flag" bullet and the "SMTP round-robin provider config documented nowhere" bullet. (The third item
  in that same ledger section — the `envelope_entity` callout's severity framing and its unlinked
  `deferred-work.md` citation — is intentionally **not** picked up here: `ses-1.7`'s AC1 explicitly scoped that
  specific callout to "leave as-is beyond an optional cross-reference," and re-opening it needs its own framing
  decision, not a mechanical fix. Left on the ledger.)

---

## Dev Notes

- **Validator pattern for AC3:** `src/main/java/com/softropic/skillars/infrastructure/ses/SesPropertiesValidator.java`
  is the exact template to mirror (component shape, `@ConditionalOnProperty`, `@PostConstruct`, `AppSetupException`
  message convention).
- **Outbox terminal-failure pattern for AC8:** `NotificationEmailOutboxHandler`'s own class javadoc (`:21-42`)
  already documents the `retry == false` → log `[...]` + let-row-complete shape for a *post-send* permanent
  failure; AC8 needs the same shape applied to a *pre-send* `IllegalArgumentException` from the enum
  conversion.
- **Bounded-retry idiom for AC5:** `PessimisticLockRetryer`
  (`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`) is this
  codebase's established "small bounded retry, then give up loudly" shape — reuse its philosophy rather than
  inventing a new one.
- **Migration conventions for AC9:** `docs/deployment/migration-conventions.md` governs the new migration;
  `MigrationLint`'s online-safety rules bind from `V128+` (per its own comments) — confirm the new migration's
  number lands above that baseline and passes lint. Do not hand-guess the Hibernate-generated column shape.
- **No shared files across items:** each of the 11 items touches a distinct file/file-set — they are
  independently testable and revertible, which is why this bundle groups them despite being topically varied
  (matches this project's established small/unrelated/mechanical bundling convention).
- **AC4 is the one decision point** — everything else in this story is a mechanical, low-ambiguity fix with an
  already-verified current code shape. Surface AC4's decision early in dev-story rather than deferring it to
  code review, since (unlike the other ACs) the fix is trivial but the *decision* isn't.
- **Off-limits reminder:** none of these 11 items touch the SES transport cutover switch or SMTP removal — all
  are bug fixes / gaps in already-shipped `ses-1.1`–`ses-1.4`/`ses-1.7` code, independent of the still-open D-1
  sending-domain decision blocking `ses-1-5`/`ses-1-6`.
- **Items considered and explicitly NOT included** (re-verified, then dropped — do not re-pick without new
  information): `QuotaConfigService.resolveTierKey` semiPro/pro mapping (needs a product decision first);
  `MailManager.isRetryable` misclassifying every permanently-undeliverable email as retryable against the
  circuit breaker (genuine and serious, but the fix is architectural/uncertain — deserves its own
  investigation-led story, not a bundle slot); SES/SMTP health-indicator DOWN-caching TTL (the review that
  raised it explicitly wants it revisited jointly across both indicators, not fixed on one side); `LoggingEmailSender`
  collision-exhaustion throttling and its silent-text-part-discard (both dev-only-transport nuisances, no
  current caller reachable); `MailManager`'s mid-loop rate-limit-rejection recipient-duplication bug (confirmed
  open but explicitly latent — no current multi-recipient caller); `SesSendRateLimiter`'s one-WARN-per-rejection
  log level (explicitly coupled to a broader burst-handling decision from the same review). All other ledger
  bullets are `[DECIDED]`/`[DISMISSED]`/`[CLOSED by ...]` or otherwise settled.

### Git intelligence (last 3 relevant commits)

`f6a093e0` (deferred-work.md prune after ses-1-7) → `1c40d6bf` (ses-1-7-documentation, PR #186 — source of
AC10/AC11) → `0bf1fc96` (ses-1-4, registration email durability — introduces the `envelope_entity`/
`EmailTemplate` dependencies AC8/AC9 build on). `git log --oneline -- src/main/java/com/softropic/skillars/platform/notification`
and `-- src/main/java/com/softropic/skillars/infrastructure/email` are good starting points for context on the
current shape of this code before touching AC2/AC3/AC8/AC9.

---

## Project Context Reference

See `_bmad-output/project-context.md` for platform-wide rules (no `mvn verify` locally; GitHub CI is the
verification gate; migration conventions; secrets handling).

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-09-14: Story created via `/bmad-create-story`, mining `deferred-work.md` after `ses-1-7-documentation`
  (PR #186) merged. `ses-1-5-ses-cutover`/`ses-1-6-delete-smtp` confirmed still blocked on the open D-1
  sending-domain decision — explicitly excluded from consideration (owner-confirmed). 11 items selected from
  a broader candidate set (full list of considered-and-dropped items recorded in Dev Notes); every included
  item's cited file:line was re-read directly against `master@f6a093e0` before being folded into an AC, not
  trusted from the ledger's own (in several cases drifted) line numbers. AC4 flagged as needing an explicit
  owner decision before implementation, per this project's established pattern for low-risk-but-scope-widening
  fixes.
