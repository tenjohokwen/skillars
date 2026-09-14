# skillars-deferred-110: Email Adapter Hardening, Outbox Durability & Doc Accuracy Fixes

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high
**Story ID:** deferred-110
**Branch:** `story/deferred-110-email-adapter-hardening`
**Created:** 2026-09-14

---

## Story Overview

A cross-cutting cleanup story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`, mined after
`ses-1-7-documentation` shipped (PR #186). `ses-1-5-ses-cutover` and `ses-1-6-delete-smtp` remain `backlog`,
gated on the still-open D-1 sending-domain decision — nothing in this story touches the transport cutover
itself.

**Revised 2026-09-14 after an independent senior-dev audit (`story-review.md`) found 4 of the original 11 ACs
rested on a false premise, 2 prescribed a fix that contradicts an explicit decision already recorded elsewhere
in this codebase, and 3 would fail or silently break something as written.** Every finding in that audit was
independently re-verified against the actual code/bytecode before being applied here (regex executed directly;
Spring Boot's `HibernateProperties`/`HibernateJpaVendorAdapter` precedence traced through decompiled bytecode
and corroborated by three independent runtime facts; `jakarta.mail`'s exception hierarchy inspected directly;
`MigrationLint`'s rules read directly) — nothing below was taken on faith from either pass. Net effect: AC1/AC2/
AC3 keep their diagnosis but needed a different fix; AC5/AC8/AC9 are now explicit decision points, not
mechanical fixes, because each contradicts either an unproven premise or a precedent already recorded in this
codebase; AC6 is downgraded from "real defect" to "defensive invariant" (the failure path it originally
described is unreachable in current code); AC7/AC10/AC11 keep their diagnosis with a corrected/widened fix.

Themes:

1. **SMTP adapter hardening** — the classifier misses a real JavaMail failure shape (a `SendFailedException`,
   not `AddressException`); the provider bean has no startup validation and can divide by zero or wrap to a
   negative array index, and is unconditionally constructed even in prod where SMTP is unused.
2. **Outbox/retry decisions** — two ACs (video-moderation alert row retention, poison-row handling for a
   renamed template) turn out to need an explicit owner decision rather than a mechanical fix, because the
   "obvious" fix in each case either creates a new problem (duplicate alerts) or contradicts a "never silently
   drop a row" precedent this codebase has already established elsewhere. A third (`envelope_entity`'s missing
   migration) turns out to rest on a wrong premise — Hibernate is silently auto-managing that table's schema
   today, which the platform's own dev-docs incorrectly deny.
3. **CI gap** — the frontend Vitest job never runs under a non-UTC timezone, so DST-class regressions pass
   silently; the fix also needs an artifact-name collision addressed or the added CI leg breaks its own
   coverage upload.
4. **Doc accuracy** — a config-property name collides with the transactional outbox; three dev-docs statements
   are over-broad, incomplete, or (in one newly-found case) directly wrong about Hibernate's actual behaviour.
5. **Decision-needed (4 ACs, not 1):** AC4 (retry-exclusion widening, as originally flagged), plus AC5, AC8, and
   AC9 — each surfaced by the audit as needing explicit owner sign-off rather than a silent "obvious fix",
   per this project's established pattern (see `ses-1.3`/`ses-1.4`'s own decision-needed ACs).

**Source:** `deferred-work.md`, re-verified 2026-09-14 against `master@f6a093e0`; ACs then re-verified and
corrected against a full senior-dev audit the same day. Ledger citations below name the exact
`## Deferred from: ...` heading each item closes.

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

### AC1: `NoHardcodedSenderTest` must catch a second hardcoded `from-address` in the same file — INCLUDING a bare literal

- **Task:** Add a second regex that matches any `from-address:` line NOT using the `${APP_SES_FROM_ADDRESS...}`
  placeholder form, and assert it has **zero** matches in every case file. Additionally loop the existing
  placeholder-matching assertion over every match, not just the first.
- **Verified at HEAD:** `src/test/java/com/softropic/skillars/infrastructure/ses/NoHardcodedSenderTest.java:27-31`
  — `FROM_ADDRESS_LINE = Pattern.compile("from-address:\\s*\"?\\$\\{APP_SES_FROM_ADDRESS(:([^}]*))?}\"?")`
  **requires** the placeholder form; a bare literal (`from-address: noreply@skillars.com`) does not match this
  pattern at all. Confirmed by direct execution: against a file containing both a valid placeholder line and a
  bare-literal line, the pattern finds exactly 1 match (the placeholder), zero for the literal. **Looping
  `matcher.find()` over this pattern therefore does not close the gap the AC originally described** — it only
  catches a second *placeholder-form* line with a different default (a narrower, less likely regression). The
  original single-`find()` call is real (confirmed), but the fix must be a second pattern, not a loop over the
  first.
- **Fix approach:** Add `NON_PLACEHOLDER_FROM_ADDRESS = Pattern.compile("from-address:\\s*(?!\\$\\{APP_SES_FROM_ADDRESS)\\S.*")`
  (or equivalent — matches a `from-address:` line whose value is not the placeholder form) and assert zero
  matches per file. Keep the existing placeholder-pattern assertion, but loop it over all matches (bonus
  hardening, not the primary fix) so a second placeholder-form line with a wrong default is also caught.
- **Files:** `NoHardcodedSenderTest.java`.
- **Test:** this AC's own rewritten test IS the test — during development, add a temporary bare-literal
  `from-address:` line to a case file to confirm the **new** pattern now fails the build, then revert. (The
  original AC's proposed verification — a bare-literal mutation checked only against the looped placeholder
  pattern — would pass without exercising the actual fix; do not use it.)
- **Ledger:** `## Deferred from: code review of ses-1-1-introduce-outbound-email-port (2026-09-11)` — "
  `NoHardcodedSenderTest` only inspects the first `from-address` match per file." (Re-scoped after story-review
  2026-09-14 found the originally-prescribed fix — a bare `matcher.find()` loop — does not catch a bare literal,
  the actual regression scenario the AC describes.)

### AC2: `SmtpErrorClassifier` must inspect `MailSendException.getFailedMessages()` — classify `SendFailedException`, not just `AddressException`

- **Task:** When classifying a `MailSendException`, scan `getFailedMessages().values()` and classify a
  `SendFailedException` permanent when it carries only invalid addresses (no valid-unsent ones), in addition to
  the existing `NON_REPAIRABLE_ERRORS` walk.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:35-48`
  — `classify(Exception ex)` builds `direct`/`cause`/`causeOfCause` from `ex` itself and never touches
  `MailSendException`'s own `getFailedMessages()` map. Traced `JavaMailSenderImpl.doSend` (spring-context-support
  6.2.x source): it calls `transport.sendMessage(mimeMessage, addresses)` and stores whatever that throws,
  **unwrapped**, into `failedMessages.put(original, ex)`. For a per-recipient rejection (e.g. 550), the SMTP
  transport throws `jakarta.mail.SendFailedException` — confirmed via bytecode that `SendFailedException
  extends MessagingException` directly, **not** `AddressException`/`ParseException` (those are a sibling
  branch, for address *parse* failures, not server *rejections*). **The originally-prescribed fix — scanning
  `failedMessages.values()` against the unchanged `NON_REPAIRABLE_ERRORS` list — would still misclassify this
  AC's own headline case as transient**, because `SendFailedException` is not an instance of any of that list's
  four classes.
- **Second correction — the circuit-breaker claim is false.** The original framing said this fix relieves
  "circuit-breaker failure-rate pressure … that a single bad address can use to degrade delivery for unrelated
  mail." Classifying a failure as *permanent* does not exempt it from the breaker: only
  `EmailTransportRateLimitedException` is `ignoreException`'d in `ComponentConfig.defaultCustomizer()` (`:97`);
  a permanent exception still counts as a failed call in the sliding window exactly like a transient one (and
  AC4's retry-exclusion doesn't change this either — `retryTemplate.execute` runs *inside* `circuitBreaker.run`,
  confirmed at `MailManager.java:113-135`, so the breaker records one call regardless of retry count). The real,
  narrower benefit is: `retry=false` on the envelope, so `EmailRetryScheduler` stops re-driving an
  unfixable rejection. State that, not a breaker-relief claim. (Blast radius is bounded anyway — per-template
  circuit breakers exist, so "unrelated mail" only means the same breaker group.)
- **Fix approach:** In `classify`, if `ex instanceof MailSendException mse`, iterate `mse.getFailedMessages().values()`;
  for each value that is a `SendFailedException`, classify it **permanent** when `getValidUnsentAddresses()` is
  null/empty (nothing left that could still be delivered) and **transient** otherwise (a genuine partial/timing
  failure, still worth retrying). Keep scanning non-`SendFailedException` values against the existing
  `NON_REPAIRABLE_ERRORS` list (walking each value's own cause chain, mirroring the existing depth). Leave the
  existing direct-exception walk for every other exception type unchanged.
- **Files:** `SmtpErrorClassifier.java`, `SmtpErrorClassifierTest`.
- **Test:** new case — `MailSendException` (no cause) whose `failedMessages` map contains one
  `SendFailedException` with `invalid=[addr]`, `validUnsent=[]` → assert `EmailTransportPermanentException`.
  Second case — same but `validUnsent` non-empty → assert `EmailTransportTransientException`. Existing
  direct-throw and one-level-wrapped `AddressException` cases must stay green (that path is already correctly
  classified today, via `SmtpEmailSender:64-66`'s own walk before `SmtpErrorClassifier` is even reached, since
  `helper.setTo(...)` throws `AddressException` synchronously for a malformed address).
- **Ledger:** `## Deferred from: code review of ses-1-2-smtp-behind-port-and-containment (2026-09-11)` —
  "`SmtpErrorClassifier` never inspects `MailSendException.getFailedMessages()`." (Re-scoped after story-review
  2026-09-14: the originally-prescribed fix left the AC's own headline case still transient, and the
  circuit-breaker-relief justification was false.)

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
  - **Gate `MailSenderProvider` itself on `transport=smtp`** (`@ConditionalOnProperty(name =
    "app.email.transport", havingValue = "smtp")` on the class, matching `SmtpEmailSender` — its one and only
    consumer, which is already gated the same way). Without this, a malformed port in the base
    `provider-configs` (defined unconditionally for all profiles, per `application.yaml:158-171`) still crashes
    **prod** boot (`transport=ses`) with a raw `NumberFormatException` out of an always-constructed bean —
    exactly the opaque-failure class `SesPropertiesValidator` exists to prevent on the SES side. This is the
    primary fix, not the validator alone.
  - **Validator/constructor ordering is not guaranteed** — a `@PostConstruct` in a sibling bean does not run
    before `MailSenderProvider`'s own constructor. Do not rely on `SmtpPropertiesValidator` alone to make the
    friendly error win the race for *either* the empty-list case or the non-numeric-port case. Perform the
    equivalent checks **inside `MailSenderProvider`'s own constructor** (blank host, unparseable port, blank
    username, empty list — each throwing `AppSetupException` naming the property) so the friendly error always
    wins regardless of bean-creation order. `SmtpPropertiesValidator` (mirroring `SesPropertiesValidator`'s
    shape, `@ConditionalOnProperty(transport=smtp)`, `@PostConstruct`) can still exist as a matching sibling for
    consistency/discoverability, but treat it as redundant defense, not the load-bearing check.
  - `MailSenderProvider.nextSender()`: replace `currentCounterValue % providers.size()` with
    `Math.floorMod(counter.getAndIncrement(), providers.size())` for the overflow-to-negative case. Note
    `Math.floorMod(x, 0)` still throws `ArithmeticException` exactly like `%` does — the constructor-level empty-
    list guard above is what actually closes the divide-by-zero case; `floorMod` alone does not.
  - **Decision-adjacent risk, not a blocker:** rejecting a blank `username` at boot is a **new** hard failure
    for anyone running `transport=smtp` against a local no-auth relay (e.g. MailHog/Mailpit). Confirm this is
    acceptable (existing `SmtpTransportBootIT` already sets a non-blank username/password, so it stays green) —
    note it in the Dev Agent Record either way rather than silently introducing a dev-experience regression.
- **Files:** `MailSenderProvider.java` (constructor validation + class-level `@ConditionalOnProperty` +
  `floorMod`); new `SmtpPropertiesValidator.java` + test (secondary/redundant check); `MailSenderProviderTest`
  (new or extended).
- **Test:** `MailSenderProvider` constructed with an empty list / blank host / non-numeric port / blank
  username each throw `AppSetupException` naming the property, from the constructor itself (not dependent on
  validator ordering); a valid config constructs successfully. `nextSender()` with `counter` pre-set near
  `Integer.MAX_VALUE` never returns a negative index across the rollover. Confirm the bean does not construct
  at all under `transport=ses` even with a malformed `provider-configs` present (the gating fix).
- **Ledger:** `## Deferred from: code review of ses-1-2-smtp-behind-port-and-containment (2026-09-11)` —
  "`MailSenderProvider` round-robin counter overflow indexes a negative position", "`SmtpEmailSender`/
  `MailSenderProvider.nextSender()` misclassifies an empty provider list as retryable", "No
  `SmtpPropertiesValidator` counterpart to `SesPropertiesValidator`." (Amended after story-review 2026-09-14:
  a validator gated on `transport=smtp` alone does not protect prod boot from this same bean's unconditional
  construction, and ordering between a `@PostConstruct` validator and this bean's own constructor is undefined.)

### AC4: `EmailTransportPermanentException` should stop being retried — **decision needed**

- **Task:** Add `.notRetryOn(EmailTransportPermanentException.class)` to `ComponentConfig.retryTemplate()`,
  alongside the existing `EmailTransportRateLimitedException` exclusion.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java:61-66`
  — `retryTemplate()` calls only `.notRetryOn(EmailTransportRateLimitedException.class)`. Every other exception
  type, including `EmailTransportPermanentException` (already listed in `MailManager.NON_REPAIRABLE_ERRORS`),
  keeps the builder's `true`-retry default — burning 3 attempts with 1s fixed backoff inside an open
  `REQUIRES_NEW` transaction and against the circuit breaker's 10s `TimeLimiter`, for a failure the codebase
  already knows cannot be fixed by retrying.
- **Correction (story-review 2026-09-14):** the effect is **latency only, not circuit-breaker relief.**
  `retryTemplate.execute(...)` runs *inside* `circuitBreaker.run(...)` (confirmed at `MailManager.java:113-135`),
  so the breaker records exactly one call regardless of whether the retry template makes 1 or 3 attempts inside
  it. The real win of this AC is bounding the 10s `TimeLimiter` budget and the open `REQUIRES_NEW` transaction's
  duration — say that; don't let it drift into a breaker-pressure claim.
- **Decision needed (surface to owner before/at dev-story) — two questions, not one:** (1) `ses-1.3`'s own AC4
  deliberately scoped itself to "exactly one new exception type, nothing else" when it added the rate-limited
  exclusion, and its code review explicitly deferred this exact fix as "a behaviour change for every permanent
  failure [that] deserves its own decision" rather than folding it in silently. The fix itself is a one-line,
  low-risk change — the decision is purely whether widening `notRetryOn` now (vs. leaving it deferred again) is
  wanted. (2) **The symmetric question is unasked:** should `EmailTransportPermanentException` also join
  `ComponentConfig.defaultCustomizer()`'s `ignoreException` predicate (`:97`, currently only
  `EmailTransportRateLimitedException`)? `ses-1.3`'s own code review reached exactly that conclusion for the
  rate-limited type once `notRetryOn` made it propagate on the first attempt — deciding `notRetryOn` here
  without deciding `ignoreException` at the same time repeats the sequence that produced that follow-up.
  Record both decisions and rationale in the Dev Agent Record; if declined, leave this AC unimplemented and
  re-annotate the ledger bullet with the renewed deferral rather than silently dropping it.
- **Fix approach (if approved):** the one-line addition above. No other change — `EmailTransportPermanentException`
  is already excluded from `MailManager.isRetryable`'s own re-drive logic; this only stops `ComponentConfig`'s
  separate `RetryTemplate` (used for the synchronous send attempt) from burning 3 in-process attempts on a
  failure already known to be permanent.
- **Files:** `ComponentConfig.java`.
- **Test:** a `RetryTemplate`-level unit/slice test asserting a callable throwing `EmailTransportPermanentException`
  executes exactly once (no retry), while one throwing a plain transient exception still retries 3x.
- **Ledger:** `## Deferred from: code review of ses-1-3-health-monitoring-rate-limiting (2026-09-12)` —
  "`EmailTransportPermanentException` is still retried 3x by `ComponentConfig.retryTemplate()`."

### AC5: `VideoModerationEmailListener`'s `null` read-back — establish the real cause before changing behaviour (HOLD)

- **Task:** Before writing any fix, determine what actually produces `persisted == null` — the AC's own premise
  (a `REQUIRES_NEW` commit-visibility race) does not hold under this codebase's transaction/isolation setup, so
  a bounded-retry fix built on that premise is not justified yet.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java:94-106`
  (`sendAdminAlertSync`) — `envelopeEntityRepository.findBySendId(envelope.sendId())` returning `null` logs a
  WARN and returns normally; a normal return from the *handler* (`ModerationAdminAlertOutboxHandler.handle`,
  which this method backs) is what causes `OutboxRowProcessor.claimAndHandle` (`:116`, the generic outbox
  poller — not the handler itself) to delete the row. `mailManager.sendEmailSync(envelope)` (`MailManager.java:86-87`) is `@Transactional(REQUIRES_NEW)`,
  invoked cross-bean through the Spring proxy — its transaction has **committed** by the time the call returns
  to the listener. The listener's subsequent read then runs under PostgreSQL's default READ COMMITTED, which
  takes a fresh snapshot per statement — a committed row is immediately visible under that isolation level. The
  "visibility race" the AC assumed does not occur under READ COMMITTED, and if some other cause (a different
  isolation level in a specific path, a connection-pool subtlety, replica lag if any exists) is producing the
  `null`, a bounded re-read on the *same* connection/transaction context cannot help either way.
- **Fix's side effects the original draft did not analyse:**
  - `adminAlertEnvelope()` mints a **fresh `sendId`** on every call (`:148`/`:173`,
    `ShortCode.shortenInt(UUID.randomUUID().hashCode())`). If the fix throws after a bounded wait, the outbox row
    is retained and re-driven with a **new** envelope and a new `sendId` — so if the original send actually
    *did* succeed (the case this AC is worried about), the fix guarantees a **duplicate** admin alert, repeating
    once per backoff cycle for as long as the `null` persists.
    `ModerationAdminAlertOutboxHandler`'s own javadoc does accept duplicates as the safer side to err on for
    this alert type, so this is a stated trade-off, not necessarily wrong — but the AC must say so explicitly.
  - If the `null` is in fact persistent (not transient), throwing creates exactly the immortal-poison-row
    problem **AC8 in this same story exists to eliminate** — the two ACs currently pull in opposite directions
    without reconciling that tension.
- **Fix approach (once cause is established):** add the read-back result and transaction/isolation context to
  the existing WARN log and capture one real occurrence before writing a fix. If a bounded re-read is still
  judged worthwhile as defense-in-depth (belt-and-braces for an as-yet-unproven condition), say so explicitly,
  bound the retry to a small fixed attempt count, and cap how many times the row can be retained this way before
  falling back to log-and-release (so AC5 cannot itself create an immortal row).
- **Files:** `VideoModerationEmailListener.java`, `VideoModerationEmailListenerTest` /
  `VideoModerationAdminAlertEnvelopeIT`.
- **Test:** deferred until the cause is established — do not write a test asserting a specific fix mechanism
  before that.
- **Ledger:** `## Deferred from: code review of skillars-deferred-109 (2026-09-11)` — the
  `VideoModerationEmailListener` `persisted == null` bullet. (Held after story-review 2026-09-14: the AC's
  causal premise is unproven and likely wrong under this codebase's actual transaction semantics; the
  prescribed fix has an unanalysed duplicate-alert side effect and conflicts with AC8's opposite stance on
  outbox-row retention.)

### AC6: `booking.store.js` `handleAcceptAllBatch` — add a defensive guard on the delete (NOT a live bug fix)

- **Task:** Add a guard so `deleteBatchAcceptResult(batchId)` in the `catch` block only fires when the entry is
  still the `null` seed — as a forward-looking defensive invariant, not because a real defect is reachable
  today.
- **Correction (story-review 2026-09-14) — the originally-described defect is unreachable:**
  `loadCoachBookingRequests` (`src/frontend/src/stores/booking.store.js:388-450`) is a
  `try { … } catch (e) { coachRequestsError.value = e; return false } finally { … }` that **swallows every
  rejection and returns `false`** — it never propagates a throw. Confirmed by direct read, and the store's own
  test suite documents this explicitly (`bookingStoreSpec.js:191-193`: "Refresh rejects →
  loadCoachBookingRequests **swallows** it"; deferred-109 AC5.2 specs assert `refreshed === false` on a bad
  response, never a thrown error). So in `handleAcceptAllBatch` (`:635-666`), nothing between
  `setBatchAcceptResult(batchId, results)` (`:646`) and the end of the `try` can throw — the `catch` block
  (`:655`) is reachable **only** when `acceptAllBatch` itself rejects, which is exactly the case where the entry
  still holds the `null` seed from `:638`. The "real results wiped by a refresh failure" scenario this AC
  originally described cannot happen with the code as it exists today.
- **Also wrong in the original draft:** it cited this function's own contract comment ("callers read results
  from here") as justification. The comment at `:647-651` says the *opposite* — "Callers must read results from
  here, **not** from `batchAcceptResultsByBatch[batchId]`" ("here" = the return value) — and on the
  (unreachable) path the AC worried about, the promise rejects anyway, so no caller receives anything either way.
- **Fix approach (re-scoped):** keep the guard — `if (batchAcceptResultsByBatch.value[batchId] === null) { deleteBatchAcceptResult(batchId) }`
  — as cheap, sound defensive hardening against a **future** change that lets `loadCoachBookingRequests` (or
  whatever runs after it) actually propagate. It costs one line and cannot regress current behaviour. Do **not**
  describe it as fixing a live UI defect in the Story Overview or anywhere else.
- **Files:** `booking.store.js`.
- **Test:** unit-test the guard's logic directly against store state (e.g. call the guard/delete path with the
  entry pre-set to a real-results object vs. pre-set to `null`, asserting the conditional behaves correctly) —
  do **not** write a test that drives `loadCoachBookingRequests` to reject from within `handleAcceptAllBatch`,
  since that path does not exist in the real store (constructing one would require first changing
  `loadCoachBookingRequests` to propagate, which is a separate, undecided change).
- **Ledger:** `## Deferred from: code review of skillars-deferred-109 (2026-09-11)` — the
  `handleAcceptAllBatch`/`deleteBatchAcceptResult` bullet. (Downgraded from "real defect" to "defensive
  invariant" after story-review 2026-09-14 found the failure path unreachable in current code.)

### AC7: Frontend Vitest CI job must also run under a non-UTC timezone

- **Task:** Add a second run of the same Vitest suite under a non-UTC timezone (e.g. `America/New_York`), so a
  DST-boundary regression fails CI instead of passing silently.
- **Verified at HEAD:** `.github/workflows/frontend-unit-tests.yml` — one job (`frontend-unit`), no matrix, no
  `TZ` env var; `happy-dom`'s `Intl`/date handling in this job is UTC-only. `skillars-deferred-109`'s own AC7.2
  spec is cited by the ledger as reproducibly failing under `TZ=America/New_York` — this job would never have
  caught that on its own.
- **Fix approach:** Add a `TZ: America/New_York` env var to a second leg via `strategy.matrix`
  (`tz: [UTC, America/New_York]`, passed through as `env.TZ`) or a duplicated job, running the same
  `npm run test:unit:ci` step. Keep the job opt-in/non-gating exactly as today (this workflow is deliberately
  decoupled from `ci.yml`/`pr-build.yml` per its own header comment) — do not add it to any required check.
  **Must also fix the coverage-upload step for a matrix**: `Upload coverage report`
  (`.github/workflows/frontend-unit-tests.yml:69-75`) uploads with a fixed name,
  `frontend-unit-reports-${{ github.run_id }}` — `actions/upload-artifact@v7` (v4+ semantics) fails the step on
  a duplicate artifact name within one workflow run, and this step has `if: always()` so it is not skipped.
  Both matrix legs would collide on the same name. Include the matrix value in the artifact name, e.g.
  `frontend-unit-reports-${{ github.run_id }}-${{ matrix.tz }}`.
- **Files:** `.github/workflows/frontend-unit-tests.yml` (matrix + artifact-name fix, same file).
- **Test:** this is itself a CI-config AC — verify by triggering the workflow manually (`workflow_dispatch`)
  after the change and confirming both legs run and both pass (or, if a currently-passing spec is timezone-
  sensitive and was masked by UTC-only CI, confirming it now surfaces — fix or explicitly note any such spec
  found, as a deviation).
- **Ledger:** `## Deferred from: code review of skillars-deferred-109 (2026-09-11)` — the CI-timezone bullet.

### AC8: `EmailTemplate.valueOf` poison-row handling — DECISION NEEDED, current behaviour is the documented precedent, not a silent gap

- **Task:** Decide, explicitly, between (a) today's behaviour — retain-and-alert (matches this codebase's
  existing "never drop" precedent for the structurally identical missing-handler case) — and (b) catch-and-drop.
  Do not implement (b) as a silent "obvious fix"; it contradicts a decision already recorded elsewhere in this
  codebase.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/notification/service/NotificationEmailOutboxHandler.java:76`
  — `EmailTemplate.valueOf(p.template())` is called directly, no surrounding try/catch. If a rolling deploy
  renames/removes the enum constant a still-live payload was queued under, `valueOf` throws
  `IllegalArgumentException`, which `OutboxRowProcessor.claimAndHandle` (`:105-120`) wraps as `OutboxRowFailure`
  and retries with backoff capped at 1h.
- **Correction (story-review 2026-09-14) — this is not the silent, unrecoverable state the original draft
  described, and the prescribed fix contradicts an explicit precedent:**
  `OutboxRowProcessor.claimAndHandle` (`:109-114`) already makes the opposite decision for the *structurally
  identical* missing-handler case, in the same method: `if (handler == null) { throw new IllegalStateException(
  "no OutboxMessageHandler registered..."); }`, with the comment *"Never dropped: it keeps its data safe until
  a deploy that carries the handler picks it up. Common during a rolling deploy…"*. A removed/renamed enum
  constant self-resolves the **same** way — a rollback, or an old pod still running the previous code in a
  rolling deploy (both pods drain the same `outbox_messages` table). Catching and dropping means the **new**
  pod claims the row, fails `valueOf`, and permanently destroys a message the **old** pod could still have
  delivered — trading a loud, recoverable state for irreversible data loss. And the current state is not silent
  today: `OutboxRowProcessor.recordFailure` logs `[OUTBOX_RETRY]` on every failed attempt, and
  `OutboxService.sweep()` (`:123-128`) raises `[OUTBOX_STUCK]` at ERROR once the attempt count crosses its
  threshold — an alerting state meant for a human to act on, not a bug.
- **If (b) log-and-drop is chosen anyway, three gaps the original draft missed:** (1) `EmailTemplate.valueOf(null)`
  throws `NullPointerException`, not `IllegalArgumentException` — a payload with a null `template` would reach
  the same path uncaught unless both are caught (`RuntimeException`, or explicitly both types). (2) Dropping
  leaves **no durable record** — no `EnvelopeEntity` row, since `EnvelopeEntity.emailTemplate` is the very enum
  that failed to resolve — reintroducing the asymmetry `ses-1.4`'s own code review closed for the sibling
  deadline-expiry path (`NotificationEmailOutboxHandler:89-95`'s own javadoc: *"a first-attempt deadline expiry
  used to leave no durable record at all … asymmetric with EmailRetryScheduler's identical DEADLINE_EXPIRED
  precheck"*). At minimum log the full raw payload so the message is reconstructible from logs. (3) Gate any
  drop behind an attempts threshold (mirroring `OutboxService.sweep()`'s own `[OUTBOX_STUCK]` threshold) so a
  rolling deploy's own window is survived before giving up.
- **Files:** `NotificationEmailOutboxHandler.java`. No dedicated test class currently exists for this class —
  confirmed (`grep -rl NotificationEmailOutboxHandler src/test/` returns only `MailManagerDuplicateSendIdIT`,
  `EmailDataRoundTripContractTest`, `RegistrationEmailDurabilityIT`) — a new test class is needed if this AC is
  implemented, not an extension of an existing one.
- **Test:** deferred until the decision is made. If (b): a payload `template` string matching no
  `EmailTemplate` constant, and separately a null `template`, both → row completes only after the attempts
  threshold, raw payload logged, no unhandled exception reaching `OutboxRowProcessor`.
- **Ledger:** `## Deferred from: code review of ses-1-4-registration-email-durability (2026-09-12)` —
  "`EmailTemplate.valueOf(p.template())` creates an immortal poison outbox row for a removed or renamed
  constant." (Held after story-review 2026-09-14: the codebase's own `OutboxRowProcessor` precedent for the
  identical missing-handler case is "never drop", which the originally-prescribed fix silently contradicted.)

### AC9: `envelope_entity` schema gap — the premise was wrong (Hibernate auto-DDL is silently active); SPLIT & HOLD on the real decision

- **Task:** (a) Empirically confirm Hibernate is auto-managing this schema (5-minute check). (b) Recognize that
  removing the auto-DDL behaviour is a separate, owner-level decision with real blast radius — do not attempt it
  in this bundle. (c) If a migration is still written (as a safety net / to stop relying on implicit behaviour
  for this one table), pin it to the actually-live schema and declare uniqueness inline to pass `MigrationLint`.
  (d) Fix the platform's own dev-doc, which asserts the opposite of what the codebase actually does.
- **The original premise was wrong — corrected by story-review 2026-09-14, re-verified independently:**
  `src/main/resources/application.yaml:64-66` sets **both** `jpa.generate-ddl: true` **and**
  `jpa.hibernate.ddl-auto: none` together. That combination does not mean "no DDL": Spring Boot's
  `HibernateJpaVendorAdapter.getJpaPropertyMap()` sets `hibernate.hbm2ddl.auto=update` whenever
  `generate-ddl=true`, **independently** of `HibernateProperties`' own handling of `ddl-auto: none` (which only
  removes the key from a *different* property map) — and `EntityManagerFactoryBuilder.Builder.build()` applies
  the vendor adapter's map **after** (so it overrides) the `HibernateProperties`-derived map. Net effect:
  Hibernate runs with `hbm2ddl.auto=update` today, silently creating/altering tables including
  `envelope_entity`. **Independently confirmed, not just reasoned from framework internals:** there is
  genuinely no `CREATE TABLE envelope_entity` in any of the 129 files under `src/main/resources/db/migration/`,
  nor in `src/test/resources/sql/createSchema.sql` (a single `CREATE SCHEMA IF NOT EXISTS main;`, used as the
  Testcontainers `withInitScript` per `SharedContainers.java:112`) — yet `MailManagerDuplicateSendIdIT` asserts
  a real Postgres unique-constraint conflict on `send_id`, and `EmailRetrySchedulerIT:60-61` /
  `sql/cleanup.sql:8-9` both successfully `DELETE FROM main.envelope_entity`/`main.envelope_entity_recipients`.
  None of that is possible unless something creates the table — Hibernate's `update` mode is that something.
- **Consequences this changes:**
  - **A bare `IF NOT EXISTS` migration is a permanent no-op.** Every environment that has ever booted the app
    already has the table (Hibernate created it), so the migration never actually executes anywhere — it
    documents an assumption rather than verifying or repairing anything. If written, its value is as an explicit,
    version-controlled declaration of the schema (so a *future* environment/DB doesn't depend on Hibernate
    auto-DDL for this table), not as a fix for a currently-broken environment.
  - **`retry`'s `columnDefinition = "text"` is load-bearing, not a latent bug.** With `hbm2ddl=update` in
    effect, `columnDefinition` *is* used for DDL — `envelope_entity.retry` really is a `text` column in every
    existing database, which is exactly why `EnvelopeEntityRepository.java:17`'s native query compares
    `e.retry = 'true'` (a string literal, not a boolean literal). **Do not "fix" this to `boolean`** — that
    would diverge a freshly-migrated schema from every existing database and break the native query. Pin any
    migration to `text`, matching the live shape, not to a "corrected" regenerated type.
  - **The real, larger gap is an inaccurate platform doc.** `docs/dev-docs/database/index.html:79` states, in a
    table cell: *"**Hibernate never creates or alters a table.** Every schema change must be a migration —
    there is no 'it'll be created on startup' fallback."* That is false today, for this exact table. For a
    story whose theme 4 is doc accuracy, this is a materially bigger miss than either AC11 item and should be
    fixed in the same pass as this AC.
- **Explicitly out of scope for this bundle:** whether `generate-ddl: true` should be removed so Hibernate
  actually stops auto-managing schema. That is an owner-level decision with real blast radius — *every*
  Hibernate-managed-but-unmigrated table (not just `envelope_entity`) would need a migration written first, or
  removing it breaks boot. Flag it for a dedicated follow-up story; do not fold it into this AC.
- **Fix approach:**
  1. Confirm empirically before writing anything: boot dev with
     `logging.level.org.hibernate.tool.schema=DEBUG` (or dump `hibernate.hbm2ddl.auto` from the running EMF's
     properties) and observe whether Hibernate performs a schema update at boot. This is cheap and removes any
     remaining doubt beyond the reasoning above.
  2. Dump the actual live shape (`\d main.envelope_entity` and `\d main.envelope_entity_recipients` against a
     real dev DB) — do not regenerate DDL from the entity and do not hand-guess column types.
  3. Write an additive, `IF NOT EXISTS`-guarded migration pinned to that dumped shape. **Declare the `send_id`
     uniqueness inline as a table constraint in the `CREATE TABLE`** (matching what `@Column(unique = true)`
     actually produced), not as a separate `CREATE UNIQUE INDEX` statement — `MigrationLint` binds both
     `Rule.BLOCKING_INDEX` (a non-`CONCURRENTLY` `CREATE INDEX` needs an `allow-blocking-index` opt-out) and
     `Rule.MISSING_LOCK_TIMEOUT` to `CREATE (UNIQUE) INDEX` (confirmed at `MigrationLint.java:209-210,269-270`),
     and `CREATE INDEX CONCURRENTLY` cannot run inside this project's single-transaction Flyway migrations
     (per `V135`'s own header comment) — an inline constraint sidesteps both rules cleanly and matches the
     column's actual origin.
  4. Fix `docs/dev-docs/database/index.html:79` to state the truth: `generate-ddl: true` combined with
     `hibernate.ddl-auto: none` still results in `hbm2ddl.auto=update` due to a Spring Boot property-precedence
     quirk, and at least `envelope_entity` currently relies on it. Name this AC's own new migration (once it
     exists) as the one table that has since been made explicit.
- **Files:** new `V###__create_envelope_entity.sql` (inline unique constraint, no separate index statement);
  `docs/dev-docs/database/index.html`. **Not** `src/test/resources/sql/createSchema.sql` — confirmed that file
  is only `CREATE SCHEMA IF NOT EXISTS main;`, unrelated to individual table DDL; adding a table there would
  fork the test schema away from Flyway/Hibernate, not align it.
- **Test:** the empirical Hibernate-DDL confirmation (step 1) recorded in the Dev Agent Record; the new
  `MigrationConventionLintTest` run clean against the added migration; confirm `MailManagerDuplicateSendIdIT`
  still passes (it should be unaffected — the table already existed before and after). Run the AC6-style
  tag-balance check (see `ses-1-7-documentation.md`'s own AC6 for the exact script) against the edited
  `database/index.html` — same hand-written-HTML-with-no-linter risk as the other dev-docs pages.
- **Ledger:** `## Deferred from: code review of ses-1-4-registration-email-durability (2026-09-12)` —
  "`envelope_entity` has no Flyway DDL and its `send_id` unique index is unversioned." (Split and held after
  story-review 2026-09-14: the original "out-of-band provenance" premise was wrong — Hibernate auto-DDL is
  silently active — which changes what the migration actually accomplishes and surfaces a bigger, separate doc
  bug. The `generate-ddl` removal question is explicitly deferred to its own story.)

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
  (field, getter/setter, javadoc), every `LoggingEmailSender` reference, `application-dev.yaml`'s key
  **and its prose comment at `:13`** (also names `app.email.log.outbox-dir`), and `infrastructure/index.html:182`.
- **Correction (story-review 2026-09-14) — the verification scope was too narrow, and it missed the riskiest
  file:**
  - **`requirements/ses-email-consolidation.md` has 7 occurrences**, not zero — confirmed by direct grep:
    `:431`, `:494`, `:616`, `:831`, `:1044`, `:1114`, and `:1206` (decision **D-4**, "Dev transport after
    cutover … `app.email.log.outbox-dir`, Phase 5"). Phase 5 is `ses-1-5-ses-cutover`, still `backlog` and out
    of this story's scope — but leaving the requirements doc naming a property that no longer exists would
    strand the still-unimplemented cutover spec on a stale name. Update the property-name references in this
    doc (mechanical rename, same as everywhere else) — this does **not** touch D-4's actual decision content or
    any Phase-5-scoped behavior, only the property name it refers to.
  - **`src/test/resources/application-test.yaml:128-136` is the single highest-risk file and was not in the
    original Files list.** Its own comment is explicit: *"the blank `outbox-dir` is load-bearing, not
    decorative. `AbstractIntegrationTest` is `@ActiveProfiles({"dev","test"})`, so every integration test loads
    `application-dev.yaml`'s `outbox-dir: target/mails` FIRST … `""` here disables file-writing"* — confirmed,
    this exact blank override exists at `:136` (`outbox-dir: ""`) with matching commentary at `:128-133`. Miss
    this file during the rename and the override silently stops applying (profile-key mismatch, no error) —
    every registration-flow IT in the suite would start writing real HTML files to `target/mails` on every run.
    **This failure mode is not a failing test**, so the originally-prescribed verification ("existing
    `LoggingEmailSender`-related tests still pass") would not catch it — the repo-wide grep (below) is the real
    gate.
  - **Widen the verification grep to the repo root**, not just `src/` and `docs/`, and run it as the actual
    gate before considering this AC done: `grep -rn "outbox-dir\|outboxDir" .` (excluding `.git`) should return
    zero hits after the rename.
- **Files:** `EmailTransportProperties.java`, `LoggingEmailSender.java`, `application-dev.yaml` (key **and**
  `:13` comment), `src/test/resources/application-test.yaml` (**highest risk — do not miss**),
  `docs/dev-docs/infrastructure/index.html`, `requirements/ses-email-consolidation.md` (7 occurrences, property
  name only), any other test fixture the repo-wide grep below surfaces.
- **Test:** existing `LoggingEmailSender`-related tests still pass under the renamed property; **the repo-wide
  grep is the actual completeness gate**, not the test suite — a passing test suite would not have caught the
  `application-test.yaml` gap.
- **Ledger:** `## Deferred from: code review of ses-1-7-documentation (2026-09-14)` — "`app.email.log.outbox-dir`
  collides in name with the transactional outbox." (Amended after story-review 2026-09-14: the original grep
  scope missed 7 references in `requirements/ses-email-consolidation.md` and the one file — a test-profile
  override — whose silent breakage would not show up as a failing test.)

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
- **Correction (story-review 2026-09-14) — both proposed fixes were incomplete:**
  - **(a) is still wrong even after the proposed reword.** `enable.test.mail` is not the only surviving toggle
    in `platform.notification`: `EmailRetryScheduler:46` also carries
    `@ConditionalOnProperty(name = "email.retry.enabled", havingValue = "true", matchIfMissing = true)`, and
    `application-prod.yaml:13` sets `email.retry.enabled: true` — confirmed, a second real boolean
    enable/disable toggle in the same area. Don't name a single "exception" when there are two; either name
    both explicitly or drop the enumeration entirely and scope the claim to what's actually being described
    (the transport *selection* mechanism, not "this area" generally).
  - **(b) under-documents `ProviderConfig` and would enshrine a real, separate bug as correct behaviour.**
    `ProviderConfig` (`infrastructure/email/smtp/ProviderConfig.java`) has **six** fields, not four —
    confirmed: `name`, `host`, `port`, `username`, `password`, `implicitTls`. The two omitted ones both matter:
    `name` is the per-provider label `SmtpHealthIndicator` surfaces in the `notification` health group
    (operator-visible, not decorative); `implicitTls` (added by `skillars-deferred-99` AC5, its own javadoc:
    "defaults from `port == 465` when unset") is the more important omission, because
    `MailSenderProvider.toMailSender` (`:49-64`) **ignores it entirely** — it hardcodes `protocol = "smtp"` and
    `mail.smtp.starttls.enable = true` for every provider, so a port-465 (implicit-TLS) provider is probed as
    UP by `SmtpHealthIndicator` (which does perform a real TLS handshake for it) while the actual send path
    talks plaintext-plus-STARTTLS to that same endpoint and fails. Documenting round-robin config without
    naming this split is documenting a half-truth. **Do not fix the underlying `MailSenderProvider` bug as
    part of this doc AC** (out of scope here) — but do name all six fields, and add a one-sentence note that
    `implicitTls` is not yet honoured by the send path (file this as a new `deferred-work.md` entry if it
    isn't already tracked, rather than silently describing broken behaviour as working).
- **Fix approach:** (a) Reword to name both surviving toggles explicitly, or scope the sentence to "the email
  *transport* selection mechanism carries no boolean flag; other flags in `platform.notification`
  (`enable.test.mail`, `email.retry.enabled`) are unrelated." (b) Document all six `ProviderConfig` fields
  under the `infrastructure.email.smtp` bullet (shape, not just `{host, port, username, password}`), name
  `app.email.smtp.provider-configs`, the round-robin selection `MailSenderProvider.nextSender()` implements,
  and the `implicitTls`-not-honoured gap. Reference the renamed-by-AC10 property only where it's actually
  relevant — `dump-dir` belongs to the **`log`** transport bullet, not `email.smtp`; do not cross-reference it
  here. Do this **after** AC10 lands, so nothing new references the old `outbox-dir` name.
- **Files:** `docs/dev-docs/infrastructure/index.html`; consider a new `deferred-work.md` entry for the
  `implicitTls` gap if AC11(b)'s research doesn't find it already tracked. Run the AC6-style tag-balance check
  (see `ses-1-7-documentation.md`'s own AC6 for the exact script) against the edited HTML file — same
  hand-written-HTML-with-no-linter risk applies here.
- **Test:** the tag-balance script (adapt from `ses-1-7-documentation.md` AC6) run clean against the edited
  file.
- **Ledger:** `## Deferred from: code review of ses-1-7-documentation (2026-09-14)` — the "unbounded ... no
  boolean flag" bullet and the "SMTP round-robin provider config documented nowhere" bullet. (Amended after
  story-review 2026-09-14: (a)'s proposed reword still named only one of two surviving toggles; (b)'s proposed
  fix under-documented `ProviderConfig` and would have described the `implicitTls`-ignoring bug as working
  behaviour.) The third item in that same ledger section — the `envelope_entity` callout's severity framing and
  its unlinked `deferred-work.md` citation — is intentionally **not** picked up here: `ses-1.7`'s AC1 explicitly
  scoped that specific callout to "leave as-is beyond an optional cross-reference," and re-opening it needs its
  own framing decision, not a mechanical fix. Left on the ledger.

---

## Dev Notes

- **Validator pattern for AC3:** `src/main/java/com/softropic/skillars/infrastructure/ses/SesPropertiesValidator.java`
  is the exact template to mirror (component shape, `@ConditionalOnProperty`, `@PostConstruct`, `AppSetupException`
  message convention).
- **`OutboxRowProcessor`'s "never silently drop" precedent governs both AC5 and AC8.** `claimAndHandle`
  (`:109-114`) throws (retains the row, logs, backs off) rather than deletes when its own structurally-identical
  gap (no handler registered for an aggregate type) fires — with an explicit comment explaining why ("keeps its
  data safe until a deploy that carries the handler picks it up"). Both AC5 and AC8 originally proposed the
  opposite (throw-to-retain for AC5; catch-and-drop for AC8) without engaging this precedent — read it before
  deciding either.
- **Bounded-retry idiom, if AC5 ends up needing one:** `PessimisticLockRetryer`
  (`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`) is this
  codebase's established "small bounded retry, then give up loudly" shape — reuse its philosophy rather than
  inventing a new one. Establish the actual cause first (see AC5) before reaching for it.
- **Migration conventions for AC9:** `docs/deployment/migration-conventions.md` governs the new migration;
  `MigrationLint`'s online-safety rules bind from `V128+` (per its own comments) — confirm the new migration's
  number lands above that baseline and passes lint. **Declare `send_id` uniqueness inline in the `CREATE TABLE`,
  not as a separate `CREATE UNIQUE INDEX`** — the latter trips both `Rule.BLOCKING_INDEX` and
  `Rule.MISSING_LOCK_TIMEOUT`, and `CONCURRENTLY` cannot run inside this project's single-transaction Flyway
  migrations. Do not hand-guess the schema shape — dump it from a live dev DB (Hibernate auto-DDL is silently
  active, see AC9).
- **AC10 and AC11 share a file** (`docs/dev-docs/infrastructure/index.html`) and must be sequenced — AC11 depends
  on AC10's rename landing first, so it doesn't introduce a new reference to the old `outbox-dir` name. (The
  original claim that "each of the 11 items touches a distinct file/file-set" was wrong on this one pair —
  corrected here so a dev planning commit order sees the constraint.)
- **Four ACs are decision points, not one** — AC4 (as originally flagged), plus AC5, AC8, and AC9, each
  surfaced by the 2026-09-14 audit. Surface all four early in dev-story rather than deferring them to code
  review; for AC5/AC8/AC9 specifically, the "obvious" fix each AC's first draft proposed turned out to be wrong
  (unproven cause, precedent-contradicting, or premise-false, respectively) — do not silently re-derive and
  implement a fix without the decision being made explicitly first.
- **Off-limits reminder:** none of these 11 items touch the SES transport cutover switch or SMTP removal — all
  are bug fixes / gaps in already-shipped `ses-1.1`–`ses-1.4`/`ses-1.7` code, independent of the still-open D-1
  sending-domain decision blocking `ses-1-5`/`ses-1-6`. (AC10's requirements-doc update touches property-name
  references only, never Phase 5's actual decision content.)
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
- 2026-09-14: Independent senior-dev audit (`story-review.md`) applied, pre-dev, after every finding was
  independently re-verified against the code (not taken on faith) — no false positives found across 4 BLOCKER,
  6 MAJOR, and 5 MINOR findings sampled and checked. **AC1** re-scoped: the prescribed `matcher.find()`-loop fix
  does not catch a bare-literal hardcoded sender (confirmed by executing the regex) — added a second pattern
  that actually rejects the non-placeholder form. **AC2** re-scoped: the prescribed fix (scanning
  `MailSendException.getFailedMessages()` against the unchanged `NON_REPAIRABLE_ERRORS` list) would still leave
  its own headline case — a 550 rejection — misclassified transient, because a 550 surfaces as
  `SendFailedException`, confirmed by bytecode not an instance of `AddressException`/`ParseException`; widened
  to classify `SendFailedException` by invalid-vs-valid-unsent addresses. Also dropped a false
  circuit-breaker-relief claim (the breaker records one call regardless of retry outcome — `retryTemplate` runs
  inside `circuitBreaker.run`, confirmed at `MailManager.java:113-135`). **AC3** amended: gate
  `MailSenderProvider` itself on `transport=smtp` (a validator alone leaves prod's unconditional bean
  construction exposed to a malformed base-YAML port) and move validation into the constructor (validator/bean
  ordering is undefined); noted `Math.floorMod` still divides by zero on an empty list. **AC4** unchanged in
  substance; corrected to "latency only, not breaker relief" and added the symmetric `ignoreException` question
  as a second decision. **AC5** held: the "commit-visibility race" premise doesn't hold under this codebase's
  `REQUIRES_NEW`-via-proxy + READ COMMITTED semantics (traced directly); the prescribed throw-to-retain fix
  also guarantees a duplicate admin alert on every backoff cycle if the original send actually succeeded, and
  conflicts with AC8's opposite stance on outbox-row retention. Re-scoped to "establish the real cause first."
  **AC6** downgraded from "real defect" to "defensive invariant, not currently reachable": traced
  `loadCoachBookingRequests` and confirmed it swallows every rejection and returns `false` (matches its own
  test suite's documented behaviour) — the `catch` block the AC described as wiping real results can only be
  reached when `acceptAllBatch` itself rejects, which is the case that already worked correctly. **AC7**
  amended: adding a `TZ` matrix leg collides with the coverage-upload step's fixed artifact name
  (`actions/upload-artifact@v4+` fails on a duplicate name within one run) — added the matrix value to the
  artifact name. **AC8** held: the prescribed catch-and-drop fix directly contradicts
  `OutboxRowProcessor`'s own recorded "never silently drop — a rolling deploy/rollback self-resolves it"
  decision for the structurally identical missing-handler case (`:109-114`); reframed as an explicit
  retain-vs-drop decision, and noted an uncaught NPE path plus a missing durable record if drop is chosen.
  **AC9** split and held — its "out-of-band provenance" premise was wrong: `generate-ddl: true` +
  `hibernate.ddl-auto: none` together still yield `hbm2ddl.auto=update` (a documented Spring Boot
  property-precedence interaction, confirmed via decompiled bytecode and independently corroborated by three
  live-test facts that are only possible if Hibernate is in fact auto-managing the table). This means the
  prescribed migration would be a permanent no-op wherever it runs, `retry`'s `text` column type is load-bearing
  (not a bug to "fix"), and `docs/dev-docs/database/index.html:79`'s "Hibernate never creates or alters a
  table" claim is false — added as a new fix target in the same AC. Removing `generate-ddl` is flagged as a
  separate, owner-level decision explicitly out of this story's scope. **AC10** amended: verification grep was
  scoped to `src/` and `docs/` only, missing 7 references in `requirements/ses-email-consolidation.md` and,
  more importantly, a load-bearing blank-override in `src/test/resources/application-test.yaml` whose silent
  breakage would not show up as a failing test — widened to a repo-root grep as the actual completeness gate.
  **AC11** amended: (a)'s proposed reword still named only one of two surviving boolean toggles in
  `platform.notification` (missed `email.retry.enabled`); (b)'s proposed doc fix under-documented
  `ProviderConfig` (4 of 6 fields) and would have enshrined a real, separate bug — `MailSenderProvider` ignoring
  `implicitTls` entirely — as correct behaviour; widened to name all six fields and flag that gap explicitly.
  Story Overview, Themes, and Dev Notes updated to reflect the corrected AC set, including fixing a false "no
  shared files across items" claim (AC10 and AC11 both edit the same HTML file and must be sequenced).
