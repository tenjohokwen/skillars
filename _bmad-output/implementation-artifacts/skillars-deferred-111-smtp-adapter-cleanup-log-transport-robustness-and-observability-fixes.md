# skillars-deferred-111: SMTP Adapter Cleanup, Log-Transport Robustness & Observability Fixes

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-111
**Branch:** `story/deferred-111-smtp-adapter-cleanup`
**Created:** 2026-09-14

---

## Story Overview

A cross-cutting cleanup story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`, mined
after `skillars-deferred-110-email-adapter-hardening-outbox-durability-and-doc-accuracy-fixes` merged
(PR #188) and its own post-merge ledger prune (PR #189) closed. `ses-1-5-ses-cutover` and
`ses-1-6-delete-smtp` remain `backlog`, gated on the still-open D-1 sending-domain decision — nothing in
this story touches the transport cutover itself.

Every candidate item was re-verified by direct read against the current codebase (`master@ab193011`)
before being included — several ledger bullets are years-old-feeling but genuinely still open; none were
taken on the ledger's word alone.

Themes:

1. **Log-transport robustness** — `LoggingEmailSender`'s dev/uat-facing dump-file writer has two
   dev-experience gaps: an unthrottled WARN loop on collision exhaustion, and a silent text-part drop
   when a caller sends both an HTML and a text body.
2. **SMTP adapter cleanup** — `MailSenderProvider` ignores the `implicitTls` field `SmtpHealthIndicator`
   already reads, so a port-465 provider would report healthy while actually failing to send; the
   classifier still retries a wrong/expired password forever; and the adapter's cause-chain "wrap depth"
   contract has no test driving a real adapter, only mocks.
3. **Observability tuning (2 ACs, decision-needed)** — the SES/SMTP health indicators share one TTL for
   both UP and DOWN results, delaying recovery visibility after a blip; `SesSendRateLimiter` logs one WARN
   per rejected send even during an expected, sustained throttle period.
4. **Outbox/PII decision needed (1 AC)** — SMTP failure messages and the persisted `envelope_entity.error`
   stacktrace carry raw recipient addresses (and, on a serialization failure, could echo an OTP), in
   tension with the recipient masking `skillars-deferred-110` landed for the log transport. Fixing this
   properly needs a logging-layer sanitizer, not a call-site patch — surfaced as a decision, not a
   silent fix.
5. **Schema decision needed (1 AC)** — `envelope_entity_recipients` has no primary key and no index on its
   foreign key, a faithful pin of what Hibernate's auto-DDL produced (`skillars-deferred-110` AC9), but now
   promoted from an accident to a version-controlled shape that deserves an explicit decision.
6. **Test/doc hygiene** — `RegistrationEmailDurabilityIT`'s scheduler assertions scan the whole
   `envelope_entity` table instead of scoping to their own seeded row; a dev-doc callout about the
   `envelope_entity` migration is now factually stale since `V136` shipped.

**Source:** `deferred-work.md`, re-verified 2026-09-14 against `master@ab193011` (post `skillars-deferred-110`
merge and its own ledger prune, PR #189). Ledger citations below name the exact `## Deferred from: ...`
heading each item closes.

---

## User Story

**As a** platform engineer responsible for Skillars' email reliability,
**I want** the log transport's remaining dev-experience gaps closed, the SMTP adapter's `implicitTls` and
wrong-password gaps fixed, its adapter-wrap-depth contract actually tested against real adapters, the
health-indicator DOWN-caching and rate-limiter log-noise questions decided and implemented, the recipient/
PII exposure in SMTP failure messages decided, the `envelope_entity_recipients` schema gap decided, and the
two remaining test/doc hygiene items fixed,
**so that** the SMTP path and its outbox observability are as hardened as the rest of the email adapter
layer `skillars-deferred-110` closed out.

---

## Acceptance Criteria

> Legend: each AC ends with **Ledger** (the `deferred-work.md` bullet it closes) and **Test** (verification).

### AC1: `LoggingEmailSender` collision-exhaustion WARN is unthrottled

- **Task:** Bring the collision-exhausted WARN (the log line emitted after `MAX_COLLISION_ATTEMPTS` failed
  `Files.writeString` attempts) under the same throttle discipline as the `IOException`/invalid-path
  branches, so a caller reusing a constant `correlationId` against an outbox already holding 100 matching
  files does not perform 100 syscalls and emit one WARN per send indefinitely.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java:147-148`
  — the loop-exhausted `log.warn(...)` sits *after* the `for` loop, outside the `directoryWritable`
  `AtomicBoolean` transition-throttle the `IOException` branch (`:139-143`) already uses, and the
  `FileAlreadyExistsException` branch (`:136-138`) never touches that flag either. Confirmed by direct
  read: nothing gates the final `log.warn` call.
- **Fix approach:** Reuse the existing `directoryWritable` `AtomicBoolean` (or a second one, if collision-
  exhaustion is judged a distinct condition from directory-unwritable — recommend reusing the same flag,
  since both describe "this correlation id's send degraded to log-only") via the same
  `compareAndSet(true, false)` transition-guard pattern already used twice in this class. A later
  successful write (a different, non-colliding `correlationId`) should still clear the flag via the
  existing `directoryWritable.set(true)` on the happy path.
- **Files:** `LoggingEmailSender.java`, `LoggingEmailSenderTest.java`.
- **Test:** seed `MAX_COLLISION_ATTEMPTS` files for one `correlationId`, send twice with that same id, and
  assert exactly one `log.warn` call across both sends (not two) — mirroring the existing
  `LoggingEmailSenderTest` throttle-on-repeated-`IOException` case. `// Mutation:` comment naming the
  reverted throttle-guard removal.
- **Ledger:** `## Deferred from: code review of ses-1-1-introduce-outbound-email-port (2026-09-11)` —
  "`LoggingEmailSender` collision-exhaustion is unthrottled and costs 100 syscalls per send."

### AC2: `LoggingEmailSender` silently discards the text part when both bodies are present

- **Task:** When an `OutboundEmailRequest` carries both an HTML and a text body, write both dump-file
  companions (`.html` and `.txt`), not just the HTML one.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java:106-108`
  — `writeToDumpDir` picks `htmlBody` when present (`isHtml = isPresent(request.htmlBody())`) and never
  writes a `.txt` companion when `textBody` is also present. Confirmed no caller sends both today (all six
  registration emails are html-only — `OutboundEmailRequestValidationTest.bothBodiesPresent_isAccepted`
  is the only place both-present is exercised, and it's a validator test, not a `LoggingEmailSender` one),
  so this is a dormant gap, not a live incident.
- **Fix approach:** In `writeToDumpDir`, when both bodies are present, write two files sharing the same
  `baseName`/collision-suffix logic — one `.html`, one `.txt` — instead of the current single-file,
  single-extension branch. Keep the existing "both blank cannot happen (AC1 of ses-1.1)" guard for the
  genuinely-empty case.
- **Files:** `LoggingEmailSender.java`, `LoggingEmailSenderTest.java`.
- **Test:** a request with both `htmlBody` and `textBody` set → assert both a `.html` and a `.txt` file
  exist in the dump dir with the expected content, and that the collision-suffix logic still applies
  independently to each extension (two `.html` files with the same correlation id still suffix
  `-2`/`-3`/…, and likewise for `.txt`, without cross-extension collisions). **Include the asymmetric case**
  explicitly, not just the symmetric one: pre-seed only `<id>.html` (not `<id>.txt`) for a given correlation
  id, then send a both-bodies request under that same id, and assert the result is `<id>-2.html` +
  `<id>.txt` — i.e. each extension's counter advances independently, so a collision on one extension must
  not skip a suffix on the other.
- **Ledger:** `## Deferred from: code review of ses-1-1-introduce-outbound-email-port (2026-09-11)` —
  "`LoggingEmailSender` silently discards the text part when both bodies are present."

### AC3: Adapter-wrap-depth guard (§7.2 item 13) has no test driving a real adapter

- **Task:** Add a test that drives at least one case through the real `SesEmailSender` + `SesErrorClassifier`
  and one through the real `SmtpEmailSender` + `SmtpErrorClassifier`, so the suite fails if either adapter's
  cause-chain wrap depth drifts from what `MailManager.isRetryable`'s fixed two-level walk expects.
- **Verified at HEAD:** `requirements/ses-email-consolidation.md:1078-1082` (§7.2 item 13) states this
  requirement explicitly. `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerResilienceTest.java`
  is the only test exercising `MailManager.isRetryable`'s cause-chain walk, and it is `@Mock`-only
  (`:42-48`) — it hand-constructs exceptions at whatever depth the test author chooses, so a real adapter
  adding one extra wrapper "for context" would silently push the real exception type out of the walk's
  two-level range and every permanent failure would start retrying six times, without this suite noticing.
- **Fix approach:** Add a slice/integration-style test (or extend an existing `*IT` that already boots a
  real `SmtpEmailSender`/`SesEmailSender`, e.g. `SmtpTransportBootIT`) that: (a) drives a real permanent
  SMTP failure (e.g. a `SendFailedException` from a fake/mock `JavaMailSender` at the `Transport` level, or
  reuse `SmtpErrorClassifierTest`'s real-exception-type fixtures) through the real `SmtpEmailSender` →
  `SmtpErrorClassifier` → `MailManager.isRetryable` chain end-to-end, and (b) does the same for
  `SesEmailSender` → `SesErrorClassifier` → `MailManager.isRetryable`. Assert `isRetryable` returns `false`
  for the permanent case in both — this is what breaks if either adapter's wrap depth drifts.
- **Files:** new or extended IT under `src/test/java/com/softropic/skillars/infrastructure/email/` or
  `.../platform/notification/infrastructure/`.
- **Test:** this AC's own new test IS the test — during development, add one extra wrapping level to
  either adapter's thrown exception (e.g. wrap `SendFailedException` in an extra `RuntimeException`) and
  confirm the new test goes red, then revert.
- **Ledger:** `## Deferred from: code review of ses-1-2-smtp-behind-port-and-containment (2026-09-11)` —
  "§7.2 item 13's adapter-wrap-depth guard is absent."

### AC4: SES/SMTP health indicator DOWN-result caching — decision needed

- **Task:** Decide whether a `DOWN` health result should be cached for a shorter TTL than an `UP` one, and
  if so, by how much, then implement it identically in both `SesHealthIndicator` and `SmtpHealthIndicator`
  (the review that raised this explicitly wants it revisited jointly, not fixed on one side only).
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/ses/SesHealthIndicator.java:84`
  — `CACHE_TTL = Duration.ofSeconds(60)`, a single hardcoded constant used for both UP and DOWN results.
  `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpHealthIndicator.java:40-41` — a
  single, *configurable* `app.email.smtp.health.ttl` (default 60s), also shared between UP and DOWN. In
  both cases: a transient failure caches `DOWN` for up to the full TTL, and Docker's healthcheck at a 30s
  interval with `retries: 3` means a single blip yields exactly two consecutive failures against a 60s
  TTL — one short of tripping the threshold, but recovery visibility is still delayed up to a minute.
- **Decision needed:** (1) Is an asymmetric TTL (shorter for DOWN) wanted at all, or is the current
  symmetric-TTL behaviour acceptable given it doesn't actually trip the healthcheck threshold today? (2)
  If yes, what's the new DOWN TTL — a fixed constant (e.g. 15s), or a second configurable property
  mirroring `SmtpHealthIndicator`'s existing `app.email.smtp.health.ttl` pattern (and adding the SES side's
  currently-hardcoded TTL to config for symmetry)? Record the decision and rationale in the Dev Agent
  Record; if declined, leave this AC unimplemented and re-annotate the ledger bullet with the renewed
  deferral.
- **Fix approach (if approved):** add a second TTL constant/property for the DOWN case in both indicators,
  applied at the same cache-set call site each already has (`SesHealthIndicator.java:111`,
  `SmtpHealthIndicator.java:144`) — pick the TTL to apply based on the freshly-computed result's status.
- **Files:** `SesHealthIndicator.java`, `SmtpHealthIndicator.java`, and their property classes if a new
  config key is chosen.
- **Test:** compute a DOWN result, assert it re-probes after the new (shorter) TTL rather than the old 60s
  one; compute an UP result, assert it still honours the original TTL.
- **Ledger:** `## Deferred from: code review of ses-1-3-health-monitoring-rate-limiting (2026-09-12)` —
  "A DOWN health result is cached for the full 60s TTL."

### AC5: `SesSendRateLimiter` logs one WARN per rejected send

- **Task:** Change the per-rejection WARN to log only on the transition into a throttled state (mirroring
  `LoggingEmailSender`'s own `AtomicBoolean` transition-throttle pattern), or drop to DEBUG, so a sustained
  burst doesn't emit one WARN line per rejected send.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/ses/SesSendRateLimiter.java:52-53`
  — `log.warn("SES send rate limit exceeded (limitForPeriod={}/s); rejecting send", ...)` fires
  unconditionally on every rejection, no throttle. Confirmed the coupling concern this bullet originally
  noted ("coupled to the burst/attempt-exhaustion decision from the same review") is resolved:
  `ses-1-3` AC (owner decision D3) already shipped — a rate-limit rejection no longer consumes one of the
  six `EmailRetryScheduler` attempts — so this WARN-log-level tweak is no longer blocked on anything else.
- **Fix approach:** Add an `AtomicBoolean throttled` (or reuse the rate limiter's own state if it already
  tracks "was the last acquire a rejection") and only `log.warn` on a `false → true` transition; log at
  DEBUG on every rejection so the detail is still available with logging turned up. Clear the flag on the
  next successful `acquireOrThrow()`. The existing `mail.ses.rate_limiter.rejected` metric (unaffected by
  this change) remains the signal to alert on.
- **Files:** `SesSendRateLimiter.java`, its test class.
- **Test:** drive two consecutive rejections and assert exactly one WARN (the transition), then a
  successful acquire, then another rejection, and assert a second WARN (a new transition).
- **Ledger:** `## Deferred from: code review of ses-1-3-health-monitoring-rate-limiting (2026-09-12)` —
  "One WARN line per rejected send."

### AC6: Registration listeners' `HashMap` accepts a null OTP/verify-URL with no fail-fast guard

- **Task:** Add an explicit null-check on the token value before it goes into the listener's `data` map, in
  `CoachRegistrationEmailListener`, `PlayerRegistrationEmailListener` and `ParentRegistrationEmailListener`.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListener.java:61-62,84-85`
  — `data.put("verifyUrl", event.verifyUrl())` / `data.put("otpCode", event.otp())` into a plain
  `HashMap<>()`, which (unlike the `Map.of(...)` these listeners used before `ses-1.4`) accepts a null
  value silently. Confirmed the other two listeners follow the identical shape. Not reachable today —
  `generateOtp()` returns a non-null `String` and `verifyUrl` is built by concatenation — so this is a
  latent loss of a guard, not a live defect. All three listeners' handler methods are `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)`
  with no `@Async` — confirmed by direct read, all six methods across the three classes. They run
  synchronously on the publishing thread inside the still-open transaction, so a `NullPointerException`
  thrown here propagates and rolls back the transaction rather than being swallowed by an async
  executor's own exception handling.
- **Fix approach:** `Objects.requireNonNull(event.otp(), "otp must not be null")` /
  `Objects.requireNonNull(event.verifyUrl(), "verifyUrl must not be null")` immediately before the
  `data.put(...)` call in all three listeners (six call sites total), restoring the fail-fast behaviour
  `Map.of(...)` used to provide for free — a null token would now throw loudly at the listener instead of
  serialising as `{"otpCode":null}`, surviving the outbox round trip, and being **delivered** with
  `EnvelopeEntity.status = SENT`.
- **Files:** `CoachRegistrationEmailListener.java`, `PlayerRegistrationEmailListener.java`,
  `ParentRegistrationEmailListener.java`, their test classes.
- **Test:** for each listener/token pair, a case constructing the triggering event with a null token
  and asserting the listener throws `NullPointerException` (not that it silently proceeds).
- **Ledger:** `## Deferred from: code review of ses-1-4-registration-email-durability (2026-09-12)` —
  "`Map.of` → `HashMap` in the three registration listeners removes a fail-fast NPE on a null token."

### AC7: `RegistrationEmailDurabilityIT`'s scheduler assertions scan the whole `envelope_entity` table

- **Task:** Scope the IT's `committedRowFor`/scheduler-assertion helpers to the test's own seeded row
  instead of `findAll()` + in-memory filter, so the test doesn't grow fragile as the shared JVM-static
  Postgres accumulates rows from other tests in the suite.
- **Verified at HEAD:** `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java:75-78`
  — `committedRowFor(email)` calls `envelopeEntityRepository.findAll().stream()...` and filters in memory;
  every call site (`:114,137,173,193,226,238`) goes through it, and the scheduler cases additionally call
  `emailRetryScheduler.retryFailedEmails()` (`:224,236`), which itself polls the *entire* table
  (`EmailRetryScheduler:89`) — so the scheduler will also re-drive `FAILED`/`retry=true` rows left by other
  tests in the same shared Postgres. Assertions are correct today because they're scoped by a UUID-unique
  email address baked into the row's `data`, but `findAll()` cost grows with the suite. Confirmed a
  `findBySendId(String)` repository method already exists and is used elsewhere in this codebase
  (`EnvelopeEntityRepository.java`) — the gap is that this IT doesn't capture the `sendId` its own listener
  call generates, so it can't use that finder directly.
- **Fix approach:** capture the `sendId` the registration flow generates for the test's own send, then use
  `envelopeEntityRepository.findBySendId(capturedSendId)` for every subsequent assertion in the same test
  method instead of re-scanning `findAll()`. **Preferred approach:** have the IT read the row back once by
  its UUID-unique email address immediately after the listener fires (the one `findAll()` + filter this AC
  is trying to get rid of, but paid only once per test instead of at every assertion), capture that row's
  `sendId`, and use `findBySendId` thereafter. An `ArgumentCaptor`/test seam on the event is a viable
  alternative but is more fragile — it couples the test to the event's internal shape and breaks silently if
  that shape changes, whereas reading back by email is a black-box assertion on behaviour the test already
  relies on. This is a test-only change; no production repository/query change is needed.
- **Files:** `RegistrationEmailDurabilityIT.java`.
- **Test:** this is itself a test-fragility fix — verify by running the full IT class alongside a seeded
  extra `FAILED`/`retry=true` row from an unrelated email address in the same shared database and
  confirming the scoped assertions are unaffected (they should already pass either way given the
  UUID-unique email scoping, but the fix removes the `findAll()` cost and the "other tests' rows are also
  re-driven by the scheduler call" fragility this bullet names).
- **Ledger:** `## Deferred from: code review of ses-1-4-registration-email-durability (2026-09-12)` —
  "`RegistrationEmailDurabilityIT`'s scheduler cases operate on global repository state."

### AC8: `envelope_entity` Flyway callout is stale about the migration's existence

- **Task:** Reword `docs/dev-docs/notification/index.html:336`'s callout, which still frames a *missing*
  `CREATE TABLE envelope_entity` migration as the gap, and add the missing `<a href>` cross-reference to
  `deferred-work.md`.
- **Verified at HEAD:** the callout's premise is doubly wrong now: (1) `skillars-deferred-110` AC9 shipped
  `V136__pin_envelope_entity_schema.sql`, so there is no longer a missing migration to warn about, and (2)
  the callout cites `deferred-work.md` as a bare `<code>` filename with no path and no `<a href>`, while
  every other cross-reference on that page is a real link (confirmed by reading the page's other callouts).
  `deferred-work.md`'s own post-`skillars-deferred-110` prune (2026-09-14) rewrote the ledger bullet this
  AC closes to point at `V136` instead of describing it as absent — this AC is the corresponding doc fix.
- **Fix approach:** Reword the callout to state that `V136__pin_envelope_entity_schema.sql` (shipped by
  `skillars-deferred-110`) pins the schema Hibernate's `generate-ddl: true` + `ddl-auto: none`
  precedence quirk was silently auto-managing (see `docs/dev-docs/database/index.html`'s own corrected
  section on this), and add a proper `<a href="...">deferred-work.md</a>`-style link matching the page's
  existing cross-reference convention. Keep (or raise) the callout's severity framing — since
  `skillars-ses-1.4`, registration and OTP mail route through `EnvelopeEntity` for the first time, so an
  envelope_entity issue affects account verification, not just booking-email history.
- **Files:** `docs/dev-docs/notification/index.html`.
- **Test:** the AC6-style tag-balance check other dev-doc-editing stories use (see
  `ses-1-7-documentation.md`'s own AC6 for the exact script) against the edited `notification/index.html`.
- **Ledger:** `## Deferred from: code review of ses-1-7-documentation (2026-09-14)` — "`envelope_entity`
  Flyway callout does not reflect its raised severity" (rewritten by the post-`skillars-deferred-110` ledger
  prune to note the "missing migration" premise is now stale; the doc-only fix itself remains open).

### AC9: A wrong or expired SMTP password is retried until attempts are exhausted

- **Task:** Classify `MailAuthenticationException`/`AuthenticationFailedException` as permanent in
  `SmtpErrorClassifier`, mirroring the precedent `skillars-deferred-110` AC2 set for other SMTP failure
  shapes.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:83-84`
  — `NON_REPAIRABLE_ERRORS` still lists only `MailParseException`, `MailPreparationException`,
  `AddressException`, `ParseException`. `JavaMailSenderImpl.doSend` catches
  `jakarta.mail.AuthenticationFailedException` before any `failedMessages` bookkeeping and rethrows Spring's
  `MailAuthenticationException` (a `MailException`, not a `MailSendException`), so `classify(Exception ex)`
  takes the plain `isPermanentByCauseChain` branch, not the `MailSendException`-specific one. Neither
  exception type appears in the cause-chain list, so it is classified transient. Result: 3 in-process
  `RetryTemplate` attempts plus up to 6 `EmailRetryScheduler` re-drives against a credential that can never
  succeed. This is the exact scenario `docker-compose.local.yml`'s bogus `${GMX_PASSWORD:dev_gmx_password}`
  default produces (per `ses-1-2`'s own ledger note).
- **Fix approach:** Add `org.springframework.mail.MailAuthenticationException` and
  `jakarta.mail.AuthenticationFailedException` to `NON_REPAIRABLE_ERRORS`. Confirm both are reachable within
  the existing 2-level cause-chain walk (`direct`/`cause`/`causeOfCause`) — `MailAuthenticationException`
  wraps `AuthenticationFailedException` as its cause per `JavaMailSenderImpl.doSend`, so `direct` alone
  should already catch `MailAuthenticationException`; verify during dev whether the underlying
  `AuthenticationFailedException` also needs to be listed for a path that surfaces it unwrapped.
- **Files:** `SmtpErrorClassifier.java`, `SmtpErrorClassifierTest.java`.
- **Test:** new case — a `MailAuthenticationException` (constructed the way `JavaMailSenderImpl.doSend`
  actually throws it, not a bare `new MailAuthenticationException(...)`) → assert
  `EmailTransportPermanentException`. `// Mutation:` comment naming the addition this pins.
- **Ledger:** `## Deferred from: code review of skillars-deferred-110 (2026-09-14)` — "A wrong or expired
  SMTP password is retried until attempts are exhausted, for every queued email."

### AC10: `MailSenderProvider.toMailSender` ignores `ProviderConfig.implicitTls`

- **Task:** Honor the per-provider `implicitTls` field when building each provider's `JavaMailSenderImpl`,
  so a port-465 (implicit-TLS) provider actually sends over implicit TLS instead of plaintext-plus-STARTTLS.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProvider.java:65-78`
  — `toMailSender` hardcodes `protocol = "smtp"` and `mail.smtp.starttls.enable = true` for every
  configured provider, never reading `providerConfig.getImplicitTls()`. Meanwhile
  `SmtpHealthIndicator.java:213,229-231` already has an `isImplicitTls(config, port)` helper (defaults from
  `port == 465` when the field is unset) and performs a real TLS handshake for such a provider, correctly
  reporting it UP — while the actual send path would still talk plaintext-plus-STARTTLS to that same
  endpoint and fail. No current caller configures a port-465 provider (dev ships two STARTTLS providers,
  gmx/gmail), so this is latent, not an active incident.
- **Fix approach:** Extract `SmtpHealthIndicator`'s private `isImplicitTls(ProviderConfig, int)` into a
  shared location (e.g. a static method on `ProviderConfig` itself, or a small shared utility both classes
  depend on) so the two call sites can't drift again, then use it in `MailSenderProvider.toMailSender`: when
  true, set `protocol = "smtps"` and move **every** property currently set under `mail.smtp.*` to
  `mail.smtps.*` — not just `starttls.enable` (which should also be dropped, not merely renamed, since
  implicit TLS needs no STARTTLS upgrade). **This is load-bearing, not cosmetic:** confirmed via
  disassembly of the pinned `org.eclipse.angus:angus-mail:2.0.5` jar that `smtps` resolves to
  `SMTPSSLTransport`, whose constructor passes the literal string `"smtps"` as its own property-prefix name
  to the shared `SMTPTransport` base — meaning `SMTPSSLTransport` reads `mail.smtps.auth`,
  `mail.smtps.connectiontimeout`, `mail.smtps.timeout`, `mail.smtps.writetimeout`, etc., and will **not**
  fall back to reading `mail.smtp.*` for these. `JavaMailSenderImpl.connectTransport()` passes
  host/port/username/password directly as `connect(...)` arguments (unaffected by the prefix), but every
  other property this class currently sets via `getJavaMailProperties()` — `auth`, `connectiontimeout`,
  `timeout`, `writetimeout` — must be re-keyed under `mail.smtps.*` for the implicit-TLS branch, or those
  settings silently stop applying (auth would still work via the `connect(...)` username/password path, but
  the three timeouts would silently revert to JavaMail's own defaults instead of this class's configured
  5000ms). When `implicitTls` is false, keep today's `mail.smtp.*`/STARTTLS behaviour unchanged.
- **Files:** `MailSenderProvider.java`, `SmtpHealthIndicator.java` (extracting the shared helper),
  `MailSenderProviderTest.java`.
- **Test:** a `ProviderConfig` with `implicitTls=true` (or `port=465`, `implicitTls` unset) → assert the
  built `JavaMailSenderImpl.getProtocol()` is `"smtps"`, `getJavaMailProperties()` has no `mail.smtp.*` keys
  at all, and `mail.smtps.connectiontimeout`/`mail.smtps.timeout`/`mail.smtps.writetimeout` are present with
  the expected values (not just that `starttls.enable` is absent — that alone would pass even if the
  timeouts were left mis-keyed under the old namespace); a STARTTLS provider → assert today's behaviour is
  unchanged.
- **Ledger:** `## Deferred from: pre-dev story-review of skillars-deferred-110 (2026-09-14)` —
  "`MailSenderProvider.toMailSender` ignores `ProviderConfig.implicitTls` entirely."

### AC11: SMTP failure messages and persisted stacktraces expose recipient addresses (and could echo OTPs) — decision needed

- **Task:** Decide how to close two related PII-exposure gaps that both point at the same root cause (no
  logging-layer sanitizer exists; masking today is a call-site patch applied only to the log-transport's
  happy-path INFO line):
  1. `SmtpErrorClassifier`/`MailManager` write recipient addresses into exception messages and the
     persisted `envelope_entity.error` stacktrace, in tension with the recipient masking
     `skillars-deferred-110` landed for `LoggingEmailSender`.
  2. `ses-1.4` AC7's log masking (`MailManager.loggableData(...)`, which redacts the interpolated `data={}`
     argument) is bypassed by the separately-logged `exception` argument on the same line — a Jackson
     serialisation failure can echo partially-written JSON, and a JDBC failure can echo bound statement
     parameters, either of which may reproduce the OTP the masking exists to hide.
- **Verified at HEAD:**
  - `SmtpErrorClassifier.java:98,108,116` (the `build(...)` calls) — `representative.getMessage()` becomes
    part of the returned exception's message, and `MailSendException.getMessage()`/`SendFailedException`'s
    own message/`toString()` enumerate invalid and valid-unsent addresses. `MailManager.java:248`
    (`toEnvelopeEntity`) — `ExceptionUtils.getStackTrace(exception)` is persisted verbatim into
    `envelopeEntity.error`, and the addresses embedded in the message above ride along in that stacktrace
    string.
  - `MailManager.java:146-149` — `logger.error(..., exception)` passes the exception as SLF4J's trailing
    throwable argument, which prints its full stack trace at ERROR, independently of `loggableData(...)`'s
    redaction of the `data={}` argument two positions earlier on the same log line.
  - Pre-existing in both cases (the classifier interpolated `ex.getMessage()` before `skillars-deferred-110`
    too; `MailManager`'s exception-argument logging predates `ses-1.4`'s AC7 masking) — but
    `skillars-deferred-110`'s masking work makes the asymmetry newly visible on the SMTP path, and closing
    it properly needs a shared sanitizer, not two independent call-site patches.
- **Decision needed:** (1) Is a logging-layer sanitizer (e.g. a `Marker`/`MessageConverter` or a small
  utility applied at every log/persist call site that might carry PII) worth building now, given it touches
  both the SMTP classifier's exception messages and `MailManager`'s exception-argument logging? (2) If yes,
  should `envelope_entity.error` itself be sanitized before persistence (the durable record), the log line
  only, or both — the durable record has a narrower audience (DB access) than Loki-shipped UAT logs, so the
  two may warrant different treatment. (3) If a full sanitizer is out of scope for this bundle, is a
  narrower interim fix acceptable — e.g. mask addresses at the point `SmtpErrorClassifier` builds its
  exception message (mirroring `LoggingEmailSender.maskAddress`), leaving the OTP-via-serialization-failure
  gap (which needs the JSON/JDBC boundary, not the classifier) explicitly still open? Record the decision
  and rationale in the Dev Agent Record; if declined, leave this AC unimplemented and re-annotate the ledger
  bullets with the renewed deferral.
- **Fix approach (once scoped):** depends on the decision above — ranges from a narrow
  `SmtpErrorClassifier`-only address mask to a shared sanitizer applied at both the classifier and
  `MailManager`'s exception-argument log/persist sites.
- **Files:** `SmtpErrorClassifier.java`, `MailManager.java`, plus whatever shared utility the decision
  produces.
- **Test:** deferred until the decision is made — do not write a test pinning a specific sanitizer shape
  before that.
- **Ledger:** `## Deferred from: code review of skillars-deferred-110 (2026-09-14)` — the recipient-address-
  in-exception-messages bullet; and `## Deferred from: code review of ses-1-4-registration-email-durability
  (2026-09-12)` — "ses-1.4 AC7's log masking is bypassed by the logged exception argument itself."

### AC12: `envelope_entity_recipients` has no primary key and no index on its foreign key — decision needed

- **Task:** Decide whether to add a primary key and an index on `envelope_entity_id` to
  `envelope_entity_recipients`, now that `V136` has promoted its current (unindexed, no-PK) shape from an
  accident of Hibernate's auto-DDL to an explicit, version-controlled schema.
- **Verified at HEAD:** `src/main/resources/db/migration/V136__pin_envelope_entity_schema.sql:103-111` (the
  `CREATE TABLE main.envelope_entity_recipients` block) has no `PRIMARY KEY` clause and no index on its
  `envelope_entity_id` foreign key — confirmed a faithful pin of what Hibernate's `@ElementCollection`
  mapping actually produced (the migration's own header comment, `:68-70`, documents this as deliberate:
  "Hibernate's own auto-DDL did not create one … this migration stays a faithful, additive pin of the
  live shape"). Every
  collection load and every FK cascade-check on this join table is therefore a sequential scan, and nothing
  at the DB level prevents duplicate recipient rows for the same envelope.
- **Decision needed:** (1) Is the current unindexed/no-PK shape acceptable given today's per-envelope
  recipient counts (this codebase only ever sends one recipient per envelope today, per
  `SmtpErrorClassifier`'s own javadoc) — i.e. defer until multi-recipient sending is real? (2) If fixing now,
  add a surrogate PK (e.g. `id bigserial PRIMARY KEY`) plus a non-unique index on `envelope_entity_id`
  (matching `PessimisticLockRetryer`/other join-table conventions in this codebase), or a composite PK on
  `(envelope_entity_id, email)` — the table's actual recipient-identifying column (confirmed: the table has
  no column literally named `recipient`; its columns are `email`, `firstname`, `gender`, `lang_key`,
  `lastname`, `title`) — if duplicates should be prevented outright? A composite PK is a stronger guarantee
  but changes behaviour (an INSERT of a true duplicate now fails instead of silently succeeding) — before
  choosing it, query a live dev/uat database for any existing `(envelope_entity_id, email)` duplicate rows
  (there should be none today, since every current caller sends exactly one recipient per envelope, but this
  confirms it empirically rather than by inference) and grep for any code path that relies on inserting a
  duplicate silently succeeding. Record the decision and rationale in the Dev Agent Record; if declined,
  leave this AC unimplemented and re-annotate the ledger bullet with the renewed deferral.
- **Fix approach (once decided):** an additive `V###__` migration adding the chosen PK/index to
  `envelope_entity_recipients`, following this project's `docs/deployment/migration-conventions.md` and
  `MigrationLint`'s online-safety rules (a non-`CONCURRENTLY` `CREATE INDEX` needs the `allow-blocking-index`
  opt-out and a `SET lock_timeout`, per `V136`'s own header notes on this).
- **Files:** new `V###__` migration; `EnvelopeEntity`/`@ElementCollection` mapping if a composite PK is
  chosen and Hibernate needs to agree with it.
- **Test:** the new `MigrationConventionLintTest` run clean against the added migration; extend
  `EnvelopeEntitySchemaIT` (added by `skillars-deferred-110` AC9) to assert the new PK/index exists.
- **Ledger:** `## Deferred from: code review of skillars-deferred-110 (2026-09-14)` —
  "`envelope_entity_recipients` has no primary key and no index on its `envelope_entity_id` foreign key."

---

## Dev Notes

- **Off-limits reminder:** none of these 12 items touch the SES transport cutover switch or SMTP removal —
  all are bug fixes / gaps in already-shipped `ses-1.1`–`ses-1.4`/`ses-1.7`/`skillars-deferred-110` code,
  independent of the still-open D-1 sending-domain decision blocking `ses-1-5`/`ses-1-6`.
- **Three ACs are decision points, not one** — AC4, AC11 and AC12. Surface all three early in dev-story
  rather than deferring them to code review, per this project's established pattern
  (`skillars-deferred-110`'s own AC4/AC5/AC8/AC9).
- **AC10 and AC9 both touch `SmtpErrorClassifier.java`/`MailSenderProvider.java`'s neighbourhood** — no
  file conflict expected (AC9 touches `SmtpErrorClassifier`'s `NON_REPAIRABLE_ERRORS`, AC10 touches
  `MailSenderProvider.toMailSender`), but sequence them in the same commit pass to avoid two separate
  reviews of adjacent SMTP-adapter code.
- **AC11 is explicitly narrower in scope than the isRetryable/circuit-breaker architectural gap** that
  `deferred-work.md`'s `skillars-deferred-109`/`skillars-deferred-110` sections both explicitly excluded
  from their own bundles as "deserves its own investigation-led story" — do not fold that item into AC11's
  decision; it stays on the ledger, untouched by this story.
- **Explicitly NOT included** (re-verified, then left on the ledger — do not re-pick without new
  information): `VideoModerationEmailListener`'s `persisted == null` bullet (`skillars-deferred-110` AC5,
  still HELD pending a real occurrence); `MailManager.isRetryable`/circuit-breaker misclassification
  (explicitly scoped to its own future story by both `skillars-deferred-109` and `skillars-deferred-110`);
  "No non-production environment exercises the SES path" (by-design, not a defect, per `ses-1.1`'s own
  ledger note).
- **Testing standard:** every AC above with a code change needs a `// Mutation:` comment on its new/changed
  test naming the specific revert it catches, per this project's established convention
  (`skillars-deferred-110`'s own review pass).
- No `mvn verify` locally — GitHub CI is the verification gate.

### Git intelligence (last 3 relevant commits)

`ab193011` (deferred-work.md prune after `skillars-deferred-110`, PR #189) → `64c08877`
(`skillars-deferred-110`, PR #188 — source of AC9's `V136` migration AC8/AC11 build on, AC10's
`SmtpErrorClassifier`/`MailSenderProvider` precedent AC9/AC10 extend, and AC11/AC12's own newly-filed code-
review bullets) → `0bf1fc96` (`ses-1-4`, registration email durability — introduces the three registration
listeners AC6 touches and the `RegistrationEmailDurabilityIT` AC7 touches). `git log --oneline --
src/main/java/com/softropic/skillars/infrastructure/email` and
`-- src/main/java/com/softropic/skillars/platform/security/infrastructure/listener` are good starting
points for context on the files this story touches.

### Project Structure Notes

- All Java changes stay within `infrastructure.email`/`infrastructure.email.smtp`/`infrastructure.ses`/
  `platform.security.infrastructure.listener`/`platform.notification.service`, matching this codebase's
  existing module boundaries — no new packages needed.
- Doc changes (AC8) stay within `docs/dev-docs/notification/index.html`, matching where the callout already
  lives.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (ledger source for every AC above; see each AC's
  own `Ledger:` line for the exact heading).
- `_bmad-output/implementation-artifacts/skillars-deferred-110-email-adapter-hardening-outbox-durability-and-doc-accuracy-fixes.md`
  (format/rigor template; AC2/AC9's precedents this story extends).
- `requirements/ses-email-consolidation.md#§7.2` (source of AC3's adapter-wrap-depth requirement).
- `docs/deployment/migration-conventions.md`, `MigrationLint` (AC12's migration constraints).

---

## Dev Agent Record

### Agent Model Used

_(to be filled in by dev-story)_

### Debug Log References

_(to be filled in by dev-story)_

### Completion Notes List

_(to be filled in by dev-story)_

### File List

_(to be filled in by dev-story)_

### Owner Decisions (surfaced before implementation, per the story's own Dev Note instruction)

_(to be filled in by dev-story — AC4, AC11, AC12 each need an explicit decision before/at dev-story time,
per this project's established pattern)_
