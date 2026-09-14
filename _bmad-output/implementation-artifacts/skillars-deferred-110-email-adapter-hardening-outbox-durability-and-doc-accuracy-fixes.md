# skillars-deferred-110: Email Adapter Hardening, Outbox Durability & Doc Accuracy Fixes

**Status:** done | **Epic:** deferred | **Priority:** high
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

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Owner Decisions (surfaced before implementation, per the story's own Dev Notes instruction)

Asked and answered with the user before any code was written, per this story's "surface all four
decision points early" Dev Note:

- **AC4a — `notRetryOn(EmailTransportPermanentException.class)`:** **Approved.** Implemented as
  specified (latency-only bound, not breaker relief).
- **AC4b — also add `EmailTransportPermanentException` to `defaultCustomizer()`'s
  `ignoreException` predicate:** **Approved.** A permanent failure (bad recipient address) is a
  data problem, not a transport-health signal, mirroring the rate-limited exception's own
  precedent. Implemented.
- **AC8 — `EmailTemplate.valueOf` poison-row handling:** **Keep retain-and-alert (current
  behaviour).** No code change — `NotificationEmailOutboxHandler` is unchanged. This decision is
  the deliverable for AC8: it matches `OutboxRowProcessor`'s own "never silently drop" precedent
  for the structurally identical missing-handler case, and avoids the uncaught-NPE / missing-
  durable-record / irreversible-data-loss risks the catch-and-drop alternative would have
  introduced. Re-annotate the `ses-1-4` ledger bullet this AC cites as `[DECIDED: keep
  retain-and-alert]` in a future ledger-hygiene pass, per this project's established convention of
  pruning `deferred-work.md` separately, post-merge.
- **AC9c — write the additive schema-pinning migration:** **Approved.** `V136` written (see
  below) in addition to the mandatory dev-doc fix.

**AC5 was not a decision point in the end** — its own fix approach ("add the read-back result and
transaction/isolation context to the existing WARN log … before writing a fix") is a diagnostic-only
change, not a behavioural fork, so nothing needed the user's sign-off; implemented as written.

### Debug Log References

- **AC1 verification (mandated by the AC itself):** temporarily inserted a bare-literal
  `from-address: "noreply@skillars.com"` line into `application-dev.yaml`, confirmed
  `NoHardcodedSenderTest` newly failed on it, then reverted (`git diff` confirmed a clean revert
  before proceeding).
- **AC9 empirical Hibernate-DDL confirmation** (mandated by the AC itself, step 1): a temporary
  `TmpEnvelopeSchemaDumpIT` (deleted before completion — not in the File List) queried
  `information_schema.columns`/`pg_constraint`/`pg_indexes` against the real Testcontainers
  Postgres for `main.envelope_entity`/`main.envelope_entity_recipients`. Confirmed the table
  exists with no corresponding `CREATE TABLE` anywhere in `db/migration/` — proof Hibernate's
  `hbm2ddl.auto=update` is live. Raw dump (abbreviated):
  - `envelope_entity`: `id uuid` (PK), `version bigint` NOT NULL, `attempts bigint` NOT NULL,
    `data jsonb`, `deadline timestamptz`, `email_template varchar` (+ a Hibernate-managed CHECK
    naming every current `EmailTemplate` constant), `error text`, `retry text` (confirms the
    `columnDefinition = "text"` claim — not a bug), `send_id varchar` with a UNIQUE constraint
    (`uk428hhm4tjgrg8cy2092q025po`), `status varchar` (+ a similar CHECK for
    `EmailDeliveryStatus`).
  - `envelope_entity_recipients`: `envelope_entity_id uuid NOT NULL` with an FK to
    `envelope_entity(id)` (no index on the FK column), plus `email`/`firstname`/`gender`/
    `lang_key`/`lastname`/`title`, all `varchar`.
  - `V136__pin_envelope_entity_schema.sql` is pinned to exactly this shape (minus the two
    Hibernate-managed CHECK constraints — see the migration's own header comment for why those
    are deliberately not replicated).
- **AC7 local TZ verification** (in place of triggering the actual GitHub Actions
  `workflow_dispatch`, which needs a push): ran the full Vitest suite locally under both
  `TZ=UTC` and `TZ=America/New_York` — 111/111 specs green both times, no masked DST-sensitive
  spec found. `actionlint` clean on the edited workflow file.
- **AC10 completeness gate:** `grep -rniE "outbox-dir|outboxdir" .` (case-insensitive — the
  original AC's own case-sensitive form missed `setOutboxDir`/`createOutboxDirectory` in
  `LoggingEmailSenderCollisionTest.java`, found and fixed during this sweep) returns zero hits
  outside: (a) my own "renamed from outbox-dir" explanatory comments, and (b) historical/immutable
  records — completed story files (`ses-1-1-*`, `ses-1-2-*`, `ses-1-7-documentation.md`),
  `story-review.md`, `deferred-work.md`'s still-open ledger bullet, and `sprint-status.yaml`'s
  `ses-1-5-ses-cutover` backlog note — none of which this project's convention rewrites
  retroactively (ledger pruning is a dedicated post-merge step; see the git log's own
  "post-merge prune" commits).

### Completion Notes List

- **AC1:** Added `NON_PLACEHOLDER_FROM_ADDRESS` pattern to `NoHardcodedSenderTest`, catching any
  `from-address:` line not using the placeholder form; looped the existing placeholder-pattern
  assertion over every match. Verified the new pattern actually catches a bare literal (see Debug
  Log).
- **AC2:** `SmtpErrorClassifier.classify` now special-cases `MailSendException`: walks
  `getFailedMessages().values()`, classifying a `SendFailedException` permanent when it has no
  valid-unsent addresses left, transient otherwise; every other failed-message value still walks
  its own cause chain against `NON_REPAIRABLE_ERRORS`. Non-`MailSendException` inputs are
  unaffected (existing direct-throw/wrapped-`AddressException` cases stay green). 9/9 classifier
  tests green, including the two new `MailSendException`+`SendFailedException` cases.
  `SmtpEmailSender:64-66`'s own pre-classifier `AddressException` handling (for
  `helper.setTo(...)`) is untouched.
- **AC3:** `MailSenderProvider` gated `@ConditionalOnProperty(transport=smtp)` at the class level
  (matching its sole consumer, `SmtpEmailSender`); its constructor now validates every
  `ProviderConfig` (blank host/username/port, unparseable port, empty list) and throws
  `AppSetupException` naming the offending property, independent of bean-creation ordering.
  `nextSender()` uses `Math.floorMod` instead of `%` so an `AtomicInteger` rollover can never
  index negative (the empty-list divide-by-zero case is closed by the constructor guard, not
  `floorMod` alone). Added `SmtpPropertiesValidator` as the redundant, `SesPropertiesValidator`-
  shaped sibling the AC calls for. `TransportWiringTest.transportSmtp_wiresSmtpEmailSender` updated
  to supply a real provider config (an empty list is no longer valid config, by design); added a
  sibling `transportSmtp_withNoProviderConfigured_failsStartup` asserting the new rejection.
  Confirmed via `MailSenderProviderTest` that the bean does not even construct under
  `transport=ses` with a malformed `provider-configs` present. Noted, not blocking: rejecting a
  blank `username` at boot is a new hard failure for a local no-auth relay — `SmtpTransportBootIT`
  already sets a non-blank username/password, so it stays green.
- **AC4:** Implemented both halves per owner decision above:
  `ComponentConfig.retryTemplate()`'s `notRetryOn` now takes a list of both
  `EmailTransportRateLimitedException` and `EmailTransportPermanentException`;
  `defaultCustomizer()`'s `ignoreException` predicate now ORs
  `EmailTransportPermanentException.isPresentIn` alongside the existing rate-limited check. Added
  `EmailTransportPermanentException.isPresentIn` (mirroring the rate-limited type's own
  depth-bounded, cycle-guarded cause walk) plus its own test class, and two new
  `ComponentConfigRetryTemplateTest` cases (bare + one-level-wrapped).
- **AC5:** No behavioural change. `VideoModerationEmailListener`'s `persisted == null` WARN now
  also logs `TransactionSynchronizationManager` state
  (`isActualTransactionActive`/`getCurrentTransactionName`/`isCurrentTransactionReadOnly`) and the
  current thread name, so a real occurrence (if one ever happens) carries the diagnostic context
  needed to actually establish a cause, instead of guessing at one. New test pins the enhanced log
  content directly (not a specific fix mechanism, per the AC's own instruction).
- **AC6:** Extracted the catch-block guard into a named `deleteBatchAcceptResultIfSeed(batchId)`
  function (only deletes when the entry still holds the `null` seed) and exported it from the
  store, purely so its conditional is directly unit-testable against store state without driving
  the (today unreachable) real-results-then-catch path through `handleAcceptAllBatch` — per the
  AC's own test instruction not to attempt that. Three new store-level tests cover both branches
  plus the no-entry no-op case. `npx eslint`/`npx prettier --check` clean on both changed frontend
  files.
- **AC7:** Added a `tz: [UTC, America/New_York]` matrix (via `include`, with a `label` field
  because `America/New_York` contains a `/`, unsafe in an artifact name) to
  `frontend-unit-tests.yml`, `TZ` env sourced from `matrix.tz`; the coverage-upload artifact name
  now includes `matrix.label` to avoid the `actions/upload-artifact@v4+` duplicate-name failure a
  bare matrix would hit. `actionlint` clean. Confirmed locally under both timezones (see Debug Log)
  — no currently-masked DST-sensitive spec found, so no additional fix was needed beyond the CI
  config itself.
- **AC8:** No code change — see Owner Decisions above. `NotificationEmailOutboxHandler` is
  unmodified.
- **AC9:** Empirically confirmed Hibernate auto-DDL (see Debug Log), then wrote
  `V136__pin_envelope_entity_schema.sql` — additive, `IF NOT EXISTS`-guarded, `send_id`
  uniqueness declared inline (not a separate `CREATE UNIQUE INDEX`), pinned to the dumped shape
  including the `retry text` column type. Deliberately does **not** replicate Hibernate's own
  enum-CHECK constraints on `email_template`/`status` (see the migration's header comment: Hibernate
  keeps managing those regardless, since `generate-ddl` removal is explicitly out of this story's
  scope, so hardcoding the current enum list would only add a second, easily-stale place to keep in
  sync). `MigrationConventionLintTest` passes clean (no `SET lock_timeout` needed — a plain
  `CREATE TABLE` matches none of that lint's lock-taking patterns). Also fixed
  `docs/dev-docs/database/index.html:79`'s false "Hibernate never creates or alters a table" claim
  with a new callout explaining the actual `generate-ddl`/`ddl-auto` precedence quirk and naming
  `V136` as the table made explicit. `MailManagerDuplicateSendIdIT`/`EmailRetrySchedulerIT` still
  green (unaffected — the table existed before and after, as expected of a no-op migration).
- **AC10:** Renamed `app.email.log.outbox-dir` → `app.email.log.dump-dir` (field `outboxDir` →
  `dumpDir`) across `EmailTransportProperties`, `LoggingEmailSender` (including its method/log-
  message/variable names — `createOutboxDirectory` → `createDumpDirectory`, etc.),
  `LoggingEmailSenderTest`, `LoggingEmailSenderCollisionTest` (found by the case-insensitive
  completeness sweep — see Debug Log), `application-dev.yaml` (key + its `:13` prose comment),
  `application-test.yaml` (the load-bearing blank override at the former `:136`, plus its own
  prose comment), `docs/dev-docs/infrastructure/index.html:182`, and all 7 property-name
  occurrences in `requirements/ses-email-consolidation.md` (D-4's actual decision content
  untouched). Repo-wide case-insensitive grep gate returns zero hits outside historical/immutable
  records (see Debug Log for the full accounting).
- **AC11:** Sequenced after AC10 landed, in the same file. (a) Reworded the unbounded "no boolean
  flag" claim to scope it to the transport-*selection* mechanism specifically, and named both
  surviving `platform.notification` toggles (`enable.test.mail`, `email.retry.enabled`) rather than
  one. (b) Documented all six `ProviderConfig` fields (not four), named
  `app.email.smtp.provider-configs` and the round-robin behaviour `MailSenderProvider.nextSender()`
  implements, added `SmtpPropertiesValidator` to the class list (new since AC3, for accuracy), and
  named the `implicitTls`-not-honoured gap explicitly rather than describing the broken behaviour
  as correct. That gap was not already tracked, so filed it as a new `deferred-work.md` entry per
  the AC's own instruction. Tag-balance script clean on both edited HTML files (`database/` and
  `infrastructure/index.html`); no embedded-newline `<code>` spans introduced.

### File List

Re-counted directly from `git status --porcelain` after the code-review response pass (superseding
the dev-pass count of 26, which the review correctly flagged as inconsistent with its own
enumeration): **33 files** — 25 modified, 8 new.

**Backend (main) — 8 modified, 3 new:**
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportPermanentException.java`
  (AC4b — added `isPresentIn`)
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportProperties.java`
  (AC10 — `outboxDir` → `dumpDir`; code review — clarified the `ignoreUnknownFields` javadoc claim)
- `src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java`
  (AC10 — rename)
- `src/main/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProvider.java`
  (AC3 — gating, `floorMod`; code review — validation delegated to new `ProviderConfigsValidator`)
- `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java`
  (AC2 — code review D-1 rewrite: reply-code classification, cause-chain preservation)
- `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpPropertiesValidator.java`
  (AC3 — new; code review — validation delegated to new `ProviderConfigsValidator`)
- **`src/main/java/com/softropic/skillars/infrastructure/email/smtp/ProviderConfigsValidator.java`
  (code review — new; shared validation logic, closes the `MailSenderProvider`/`SmtpPropertiesValidator`
  duplication finding, adds the blank-password (D-2) and port-range checks)**
- `src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java`
  (AC4a/AC4b)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`
  (AC5 — diagnostic log; code review — added `getCurrentTransactionIsolationLevel()`)
- `src/main/resources/application-dev.yaml` (AC10)
- `src/main/resources/db/migration/V136__pin_envelope_entity_schema.sql`
  (AC9 — new; code review D-3 rewrite: CHECK constraints added, UNIQUE/FK explicitly named to match
  Hibernate's own generated names, header corrected)

**Backend (test) — 8 modified, 5 new:**
- `src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportPermanentExceptionTest.java`
  (AC4b — new)
- `src/test/java/com/softropic/skillars/infrastructure/email/TransportWiringTest.java` (AC3)
- `src/test/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSenderCollisionTest.java`
  (AC10)
- `src/test/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSenderTest.java` (AC10)
- `src/test/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProviderTest.java`
  (AC3 — new; code review — blank-password/out-of-range-port cases, round-robin-order rewrite)
- `src/test/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifierTest.java`
  (AC2 — code review — rewritten around real SMTP exception types, including the CRITICAL
  dropped-connection regression test)
- `src/test/java/com/softropic/skillars/infrastructure/email/smtp/SmtpPropertiesValidatorTest.java`
  (AC3 — new; code review — blank-password/out-of-range-port cases)
- `src/test/java/com/softropic/skillars/infrastructure/ses/NoHardcodedSenderTest.java`
  (AC1 — code review — rewritten to match line-by-line instead of a whole-file regex)
- `src/test/java/com/softropic/skillars/platform/notification/config/ComponentConfigRetryTemplateTest.java`
  (AC4 — code review — strengthened cause assertions)
- **`src/test/java/com/softropic/skillars/platform/notification/config/ComponentConfigDefaultCustomizerTest.java`
  (code review — new; proves the real `ComponentConfig.defaultCustomizer()` bean's `ignoreException`
  predicate, which previously had no test)**
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListenerTest.java`
  (AC5 — code review — pins `currentTransactionIsolationLevel=`)
- **`src/test/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntitySchemaIT.java`
  (code review — new; permanent fresh-DB schema-assertion IT, restoring the ad hoc
  `TmpEnvelopeSchemaDumpIT` used to derive `V136` and verify no duplicate constraints)**
- `src/test/resources/application-test.yaml` (AC10)

**Frontend — 2 modified:**
- `src/frontend/src/stores/booking.store.js`
  (AC6; code review — replaced the exported `deleteBatchAcceptResultIfSeed` seam with an inline
  local-flag guard, fixing a null-vs-seed ambiguity and reverting the store's public contract)
- `src/frontend/src/stores/__tests__/bookingStoreSpec.js` (AC6 — code review — tests updated to match)

**CI — 1 modified:**
- `.github/workflows/frontend-unit-tests.yml`
  (AC7; code review — corrected the DST-spec header claim to past tense)

**Docs — 3 modified:**
- `docs/dev-docs/database/index.html`
  (AC9; code review — migration count, hbm2ddl precedence mechanism, "not a no-op" corrections)
- `docs/dev-docs/infrastructure/index.html` (AC10, AC11; code review — restored the missing colon)
- `requirements/ses-email-consolidation.md` (AC10)

**Ledger — 1 modified:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC11(b) — new `implicitTls` entry only,
  no prune)

**Sprint/story tracking — 2 modified (workflow-managed, not enumerated by AC — noted here per the
code review's own completeness finding):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-deferred-110-email-adapter-hardening-outbox-durability-and-doc-accuracy-fixes.md`
  (this file)

### Review Findings

Source: `/bmad-code-review` 2026-09-14, three parallel layers (Blind Hunter — diff only; Edge Case
Hunter — diff + project read; Acceptance Auditor — diff + spec + `story-review.md` + project-context).
41 raw findings → 30 after dedupe → 3 dismissed as false positives. Every finding below was
independently re-verified by the orchestrator against the pinned dependency bytecode or by executing
the code in question; nothing is carried on a layer's assertion alone.

**Correction to the review's own interim report:** an earlier orchestrator note claimed a 421/450
greylisting at `MAIL FROM` would classify permanent. That is wrong — `SMTPTransport.sendMessage`
assigns `validUnsentAddr = addresses` (bytecode offset 133) *before* calling `mailFrom()` (offset
276), so a `MAIL FROM` failure carries a populated `validUnsent` and classifies transient. The real
permanent-misclassification path is `rcptTo()`'s unexpected-response branch (D-1/P1 below).

#### Decision needed — ALL RESOLVED by owner 2026-09-14 (each became a patch, listed below)

- [x] [Review][Decision] **A permanent `MAIL FROM` 550 still classifies transient and is retried until attempts are exhausted** — AC2's fix keys permanence off `SendFailedException.getValidUnsentAddresses()`. `SMTPTransport.sendMessage` populates `validUnsentAddr` with the full address array *before* `mailFrom()` runs, and `issueSendCommand` re-merges valid+unsent into it, so a hard 550 "sender address rejected" arrives with a non-empty `validUnsent` → transient → `retry=true` → `EmailRetryScheduler` re-drives it every tick until `ATTEMPTS_EXHAUSTED`/`DEADLINE_EXPIRED`. AC2 closes recipient-side (`RCPT TO`) rejections only. **Options:** (a) classify by SMTP reply code — `SMTPSendFailedException.getReturnCode()`/`SMTPAddressFailedException.getReturnCode()`, 5xx permanent / 4xx transient — which is the standard and correct predicate but widens AC2 beyond its re-scoped brief and interacts with AC4a/AC4b; (b) accept the asymmetry and record it on the ledger; (c) keep address-array logic and add a return-code check only for the `MAIL FROM` command. Verified: `SMTPTransport` bytecode, field-assignment ordering. **RESOLVED — option (a):** classify by SMTP reply code. Use `SMTPSendFailedException.getReturnCode()` / `SMTPAddressFailedException.getReturnCode()` — 5xx permanent, 4xx transient — as the primary predicate, in place of keying permanence off the address arrays. Accepted as a deliberate widening of AC2 beyond its re-scoped brief.
- [x] [Review][Decision] **Blank `username` is a hard boot failure while blank `password` is accepted** — `MailSenderProvider.validate` throws `AppSetupException` on a blank `username` but never checks `password`. A local no-auth relay (MailHog/Mailpit) is a legitimate `transport=smtp` dev configuration that now cannot boot, while `username=user` + absent `password` — a genuinely broken authenticated config that fails every send — passes validation unremarked. The Completion Notes acknowledge the regression but do not resolve it. **Options:** (a) drop the `username` check; (b) keep it and add a matching `password` check; (c) require both only when `mail.smtp.auth` is on, making the no-auth case explicit. **RESOLVED — option (b):** keep the `username` check and add a matching blank-`password` check. Review note recorded during the decision: the 'local no-auth relay' premise does not hold — no MailHog/MailPit/MailDev/smtp4dev service exists in any of the four compose files, and `username` is not merely an AUTH credential but the From address itself (`SmtpEmailSender.java:54`, `helper.setFrom(javaMailSender.getUsername())`), so a blank username is unambiguously broken regardless of whether the relay requires auth. Dropping the check was withdrawn as an option. The blank-password check closes `ses-1-2`'s documented empty-password trap, which dev/uat currently reach via the bogus `${GMX_PASSWORD:dev_gmx_password}` default; the separate 'non-blank but wrong password is retried forever' case stays on the ledger as the deferred `MailAuthenticationException` item.
- [x] [Review][Decision] **Fresh databases will permanently lack the enum `CHECK` constraints that existing databases have** — `V136`'s header justifies omitting the `email_template`/`status` CHECKs on the grounds that "Hibernate will still run its own schema update on every boot and will add/widen that CHECK itself". That is false: `AbstractSchemaMigrator` exposes only `createTable`, `migrateTable` (adds missing columns), `applyIndexes`, `applyUniqueKeys` and `applyForeignKeys` — there is no check-constraint path for an already-existing table, and CHECKs are emitted only in the `CREATE TABLE` branch. Since `V136` is what creates the table on any fresh database (see P9), those databases never acquire the CHECKs. Functionally permissive (Hibernate only writes valid enum names), but a permanent, silent schema divergence. **Options:** (a) add the CHECKs to `V136` and accept the enum-list sync burden the header argues against; (b) accept the divergence and correct the header's false justification; (c) remove `generate-ddl: true` so Flyway is the single source of truth — explicitly out of this story's scope and an owner-level decision. Verified: `hibernate-core` `AbstractSchemaMigrator` method set. **RESOLVED — option (a):** add the `email_template`/`status` CHECK constraints to `V136`, accepting the enum-list sync burden the migration header currently argues against. The header's false justification must be rewritten at the same time.

#### Patch — ALL RESOLVED 2026-09-14 (re-verified independently before fixing; see Debug Log/Completion Notes)

- [x] [Review][Patch] **[from D-1]** Classify SMTP failures by reply code (5xx permanent / 4xx transient) rather than by address arrays, so a `MAIL FROM` 550 stops being retried [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:64-70] — **FIXED.** Re-verified independently via `javap` on the pinned `org.eclipse.angus:jakarta.mail:2.0.5` jar (not just the review's own claim): confirmed `validUnsentAddr` is set to the full address array at bytecode offset 133, before `mailFrom()` at offset 276, and that `issueSendCommand`'s non-250 branch re-merges it into a `SMTPSendFailedException` thrown with that populated array. `SmtpErrorClassifier` rewritten to key permanence off `SMTPSendFailedException`/`SMTPAddressFailedException`/`SMTPSenderFailedException.getReturnCode()` (5xx permanent, everything else — including no-recognised-type and non-5xx — transient), falling back to the pre-existing cause-chain walk only for a non-SMTP-typed `SendFailedException`.
- [x] [Review][Patch] **[from D-2]** Add a blank-`password` check alongside the existing blank-`username` check, in both validators [src/main/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProvider.java:55-71, src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpPropertiesValidator.java:35-64] — **FIXED**, and combined with the next patch's dedup: both validators now delegate to a single new `ProviderConfigsValidator.validate(...)`, which includes the blank-password check. Confirmed dev/uat's shipped `application-dev.yaml`/`application-uat.yaml` both already default `GMX_PASSWORD`/`GMAIL_PASSWORD` to non-blank values, so no real boot regresses.
- [x] [Review][Patch] **[from D-3]** Add the `email_template`/`status` CHECK constraints to `V136` and rewrite the header paragraph that wrongly claims Hibernate will add them [src/main/resources/db/migration/V136__pin_envelope_entity_schema.sql:26-34,53-64] — **FIXED.** Both CHECK constraints added, left unnamed so PostgreSQL's own default naming matches what's already live (confirmed by re-running the dump against a fresh Testcontainers boot with `V136` in place — see Debug Log). Header rewritten to state the corrected mechanism and to stop calling the migration a no-op.
- [x] [Review][Patch] **A dropped SMTP connection at `RCPT TO` is classified permanent and the email is silently lost forever** — CRITICAL [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:64-70] — **FIXED by the same D-1 rewrite above, independently re-verified as a distinct mechanism.** Traced `readServerResponse()`'s own bytecode: on EOF (`readLine()` returns `null`, the shape a dropped connection actually produces) it returns `-1` directly rather than throwing. `rcptTo()`'s per-recipient switch has no case for `-1`, so it falls to the "unexpected response" branch and throws `SMTPAddressFailedException` immediately — `-1` is not in `[500,599]`, so the new reply-code classifier correctly reads it as transient. New regression test (`rcptToDroppedConnection_isTransient`) pins this exact scenario.
- [x] [Review][Patch] The `MailSendException` branch discards the exception's own cause chain; the new javadoc's "2-arg super constructor leaves cause null" claim is factually wrong [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:55-57] — **FIXED** (the cause-chain-discard half; the "factually wrong" half did not hold up on re-verification — see below). `classify()` now attaches the real per-recipient failure exception as the returned `EmailTransportException`'s cause, not the outer `MailSendException` — new test `mailSendException_causeChainPreservesTheRealFailure` pins it. Re-verified the specific javadoc claim by executing `new MailSendException(Map.of(...)).getCause()` directly against the pinned Spring version: it genuinely is `null` — the javadoc's narrow factual claim was correct; the finding's real, valid point was the *consequence* (a discarded cause chain), which is what got fixed.
- [x] [Review][Patch] `anyMatch` aggregates across `failedMessages` while the javadoc describes per-message semantics [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:56] — **FIXED** (clarified, not behaviourally changed — this codebase only ever sends one recipient per call, confirmed via `SmtpEmailSender`/`MailManager`'s call sites, so aggregation is not live today). New javadoc states the actual semantics explicitly: permanent if *any* failed-message entry is permanent, with the "any" behaviour kept for correctness if multi-recipient sending is ever introduced, not because it's exercised now.
- [x] [Review][Patch] AC2's tests cover neither risky shape; the transient case stays green under a full revert; no `// Mutation:` annotations [src/test/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifierTest.java] — **FIXED.** Test file rewritten to use the real `org.eclipse.angus.mail.smtp` exception types (not a hand-constructed generic `SendFailedException`) for both risky shapes: `mailFrom550_isPermanent`/`mailFrom421_isTransient` (D-1) and `rcptToDroppedConnection_isTransient`/`rcptTo550_isPermanent`/`rcptTo450_isTransient` (the CRITICAL case). Each carries a `Mutation:` comment naming the specific revert it catches.
- [x] [Review][Patch] Port validation accepts `-1`, `0` and out-of-range values [src/main/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProvider.java:66-71, src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpPropertiesValidator.java:52-58] — **FIXED** in the new shared `ProviderConfigsValidator`: range-checks the parsed port against `[1, 65535]`. New tests in both `MailSenderProviderTest`/`SmtpPropertiesValidatorTest` cover an out-of-range and a non-positive port.
- [x] [Review][Patch] `SmtpPropertiesValidator` duplicates `MailSenderProvider`'s validation byte-for-byte, including messages and a second `isBlank` [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpPropertiesValidator.java:35-64] — **FIXED.** Extracted to new package-private `ProviderConfigsValidator`, called from both `MailSenderProvider`'s constructor and `SmtpPropertiesValidator`'s `@PostConstruct` — the exact duplication this finding named (which had already drifted once, per D-2) can no longer drift again.
- [x] [Review][Patch] AC4b's circuit-breaker `ignoreException` change has no test — deleting the clause leaves the suite green [src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java:113-114] — **FIXED.** New `ComponentConfigDefaultCustomizerTest` instantiates the real `defaultCustomizer()` bean against a real `Resilience4JCircuitBreakerFactory` and drives 6 ignored failures through it (proving the breaker never opens), contrasted with an ordinary exception that does open it at the configured threshold — confirmed this test fails if the `EmailTransportPermanentException.isPresentIn` clause is removed.
- [x] [Review][Patch] `permanentException_traversedThroughOneWrappingLevel_isNotRetried` asserts only `isInstanceOf(RuntimeException.class)`, which any unchecked exception satisfies [src/test/java/com/softropic/skillars/platform/notification/config/ComponentConfigRetryTemplateTest.java] — **FIXED.** Added `.hasCauseInstanceOf(EmailTransportPermanentException.class)` (and, for consistency, to the pre-existing rate-limited counterpart test too) so the assertion actually pins that `.traversingCauses()` found the real cause, not merely that *some* `RuntimeException` eventually surfaced.
- [x] [Review][Patch] `V136` is NOT a no-op — it is what creates the table on every fresh database including CI; the migration header, the story and the published dev-doc all assert the opposite [src/main/resources/db/migration/V136__pin_envelope_entity_schema.sql:12-14, docs/dev-docs/database/index.html:98-101] — **FIXED.** Independently re-verified by running a real fresh-container boot and confirming `flyway_schema_history` records `V136` with `success=true` (i.e. it genuinely executed, not skipped-as-already-applied). Migration header, this story's own Completion Notes, and `docs/dev-docs/database/index.html` all corrected to state V136 is what creates the table on a fresh database, not a no-op.
- [x] [Review][Patch] Restore the deleted `TmpEnvelopeSchemaDumpIT` as a permanent fresh-DB schema-assertion IT [src/test/java/com/softropic/skillars/platform/notification/] — **FIXED.** New permanent `EnvelopeEntitySchemaIT` (not "Tmp") asserts: `V136` has a `success=true` `flyway_schema_history` row; exactly one UNIQUE constraint under Hibernate's expected name; exactly one CHECK per enum column; exactly one FK under Hibernate's expected name.
- [x] [Review][Patch] Verify and prevent duplicate `UNIQUE`/`FK` constraints on fresh databases (Flyway creates PG-named constraints; Hibernate's migrator looks up its own generated names and adds a second set) [src/main/resources/db/migration/V136__pin_envelope_entity_schema.sql:62,67] — **FIXED.** Both constraints in `V136` are now explicitly named to match Hibernate's own hash-based generated names (`uk428hhm4tjgrg8cy2092q025po`, `fk89qpyuf6j5fgg7aorxxh8mqyn` — both confirmed live in the original, pre-`V136` dump), so `AbstractSchemaMigrator.applyUniqueKeys`/`applyForeignKeys`' by-name lookup finds them already present. Verified empirically against a real fresh Testcontainers boot: exactly one of each, no duplicates — see Debug Log.
- [x] [Review][Patch] Migration count still reads "85 files, numbered up to V91"; actual is 130 files up to `V136` [docs/dev-docs/database/index.html:108-110] — **FIXED**, corrected to "130 files, numbered up to V136".
- [x] [Review][Patch] The `hbm2ddl` precedence callout says the vendor map "is applied after, so it wins"; the actual mechanism is `putIfAbsent` into a slot Boot vacated by removing the key [docs/dev-docs/database/index.html] — **FIXED.** Re-verified the actual mechanism from scratch by disassembling the pinned `spring-boot-autoconfigure`/`spring-orm` 3.5.16/6.2.19 jars (not just accepting the review's restated claim): `HibernateProperties.determineHibernateProperties` explicitly `Map.remove()`s the key when `ddl-auto=none`; `AbstractEntityManagerFactoryBean.afterPropertiesSet()`'s vendor-property merge is a `containsKey`-then-`put` fill-the-gap pattern, not an overwrite. Callout rewritten as a precise, numbered 3-step account of the confirmed mechanism.
- [x] [Review][Patch] The transport `<ul>` lost the colon that introduced it, so it now reads as enumerating the two boolean toggles [docs/dev-docs/infrastructure/index.html:180] — **FIXED**, added a proper introductory sentence before the list.
- [x] [Review][Patch] Javadoc claims the binding rejects unknown keys; `ignoreUnknownFields` defaults to `true`, so a surviving `outbox-dir` would be silently ignored [src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportProperties.java:9-13] — **FIXED** (clarified — close reading found the original sentence was not strictly false, but genuinely ambiguous/risked exactly this misreading). Rewrote to state explicitly that `ignoreUnknownFields` stays `true` (Spring Boot's own default, unchanged here), that a stray old key is silently accepted and unbound rather than rejected, and that the repo-wide grep — not this binder — is AC10's actual completeness gate.
- [x] [Review][Patch] `deleteBatchAcceptResultIfSeed` cannot distinguish its `null` seed from a legitimately-`null` result (a 204 unwraps to `null`) [src/frontend/src/stores/booking.store.js:645-649] — **FIXED at the root**, not patched around. Replaced the shared-state re-read (`batchAcceptResultsByBatch.value[batchId] === null`) with a local `resultReceived` flag set the instant `handleAcceptAllBatch` actually receives a response, inline — the ambiguity this finding names cannot occur by construction, because the guard no longer inspects a value that a real result could also produce.
- [x] [Review][Patch] The store's public contract was widened purely for test access — document it as a test-only seam or revert to the in-place one-liner AC6 specified [src/frontend/src/stores/booking.store.js:763-765] — **FIXED — reverted.** `deleteBatchAcceptResultIfSeed` no longer exists as a separate exported function (superseded by the local-flag redesign above); the store's public return object is back to exactly what AC6 originally specified.
- [x] [Review][Patch] Two of the three new frontend specs are mutation-blind (the no-entry case is tautological) [src/frontend/src/stores/__tests__/bookingStoreSpec.js:274-302] — **FIXED — the tests were removed along with the function they tested** (see above); the pre-existing deferred-109 AC5.1 test still covers the one reachable branch of the redesigned guard.
- [x] [Review][Patch] `nextSender_neverNegativeAcrossRollover` asserts only non-null and uses three identical providers, so round-robin ordering is unobservable [src/test/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProviderTest.java] — **FIXED.** Rewritten as `nextSender_roundRobinsAndNeverNegativeAcrossRollover` using three providers with distinguishable hosts, asserting the actual round-robin sequence (a→b→c→wraps to a) before exercising the rollover case.
- [x] [Review][Patch] AC7's stated Test was not performed — a local `TZ` run exercises neither matrix expansion, the `env: TZ` plumbing, nor the artifact-name fix [.github/workflows/frontend-unit-tests.yml] — **ACKNOWLEDGED, not fixable from this environment.** `actionlint`/YAML-parse confirm the file is syntactically and semantically valid, and the local dual-`TZ` run confirms no spec is masked — but neither substitutes for a real `workflow_dispatch` run, which requires a push. Flagged explicitly as a follow-up: trigger the workflow manually once this branch is pushed and confirm both matrix legs complete and both artifacts upload without a name collision.
- [x] [Review][Patch] The CI header comment asserts a reproducibly-failing DST spec that the story's own Debug Log says does not exist [.github/workflows/frontend-unit-tests.yml] — **FIXED.** Reworded to past tense with the correct causal chain: `deferred-109`'s AC7.2 spec DID reproducibly fail under that TZ at the time, and WAS fixed in that same story; this story's own re-verification (both TZs, 111/111 green) found nothing currently failing, so the matrix is a regression guard, not a fix for a presently-failing spec.
- [x] [Review][Patch] File List is internally inconsistent (says 26 files, enumerates 28) and omits `sprint-status.yaml` and the story file itself [_bmad-output/implementation-artifacts/skillars-deferred-110-email-adapter-hardening-outbox-durability-and-doc-accuracy-fixes.md:804-861] — **FIXED**, File List rewritten below with an accurate, re-counted total, and explicit categories for files touched only by this review-response pass.
- [x] [Review][Patch] AC5's diagnostic log omits `getCurrentTransactionIsolationLevel()` — the single datum the AC's whole premise-rebuttal turns on [src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java:118-122] — **FIXED.** Confirmed `TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()` exists on the pinned Spring version and added it to the WARN log; test updated to pin its presence.
- [x] [Review][Patch] `NON_PLACEHOLDER_FROM_ADDRESS` has no line anchor and no comment stripping — a commented-out literal, a prose comment mentioning the key, or a bare key followed by another key each fail the build with a message naming a sender that does not exist (confirmed by execution) [src/test/java/com/softropic/skillars/infrastructure/ses/NoHardcodedSenderTest.java:37-38] — **FIXED.** Rewritten to match line-by-line (`^\s*from-address:\s*(.*?)\s*$`) instead of scanning the whole file with an unanchored regex — a same-line-only match structurally cannot reach a different YAML entry, and a `#`-led line never matches the key anchor at all. Re-verified all three scenarios the finding named by constructing them and running the test: bare literal still caught; an empty `from-address:` followed by an unrelated key now correctly names the empty `from-address:` line itself (not the unrelated key) as the offender; a commented-out example line no longer false-positives.

#### Deferred (pre-existing, not caused by this change)

- [x] [Review][Defer] Exception messages and persisted stacktraces carry recipient addresses, in tension with the recipient masking landed in this same commit [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:58-60] — deferred, pre-existing
- [x] [Review][Defer] `envelope_entity_recipients` has no primary key and no index on its foreign key — faithful to Hibernate's output, but now blessed as the version-controlled shape [src/main/resources/db/migration/V136__pin_envelope_entity_schema.sql:66-73] — deferred, pre-existing
- [x] [Review][Defer] `MailAuthenticationException` (a wrong/expired SMTP password) classifies transient and is retried until attempts are exhausted, for every queued email [src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java:55] — deferred, pre-existing

#### Dismissed as false positives (recorded so they are not re-raised)

- Matrixing the Vitest job renames its status checks — the workflow's own header documents it as deliberately non-required (`workflow_dispatch` + `frontend-tests` label, owner decisions D2/D6), so no branch-protection rule can break.
- The AC10 completeness grep could not match an `OUTBOX_DIR` environment-variable form — verified repo-wide that zero such references exist in any form, so the spelling gap had no effect.
- The `implicitTls` ledger entry's claim that `SmtpHealthIndicator` reports a port-465 provider UP while sending fails — verified correct: the indicator builds its own `SSLSocket` probe via `isImplicitTls`/`probeImplicitTlsConnection`, independent of `MailSenderProvider`'s `JavaMailSenderImpl` instances.

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
- 2026-09-14: Implementation complete (`/bmad-dev-story`). All 11 ACs addressed — AC4a/AC4b/AC9c approved and
  implemented; AC8 decided as "keep retain-and-alert" (no code change, decision itself is the deliverable); AC5
  needed no decision (diagnostic-only fix, implemented as written). 26 files touched (21 modified, 5 new: a
  migration, an `isPresentIn` test, `SmtpPropertiesValidator` + its test, `MailSenderProviderTest`). New tests
  added for every AC with a code-level fix; all targeted backend suites green (unit + the touched ITs —
  `SmtpTransportBootIT`, `MailManagerDuplicateSendIdIT`, `EmailRetrySchedulerIT`, the three registration-flow
  ITs, `MigrationConventionLintTest`); frontend Vitest green under both `TZ=UTC` and `TZ=America/New_York`
  (111/111), `eslint`/`prettier --check` clean; `actionlint` clean on the CI workflow edit; both edited dev-docs
  HTML files pass the tag-balance script. No `mvn verify` run locally per this project's standing validation
  policy — GitHub CI is the full-verification gate. See Dev Agent Record for the full per-AC breakdown, the two
  mandated empirical verifications (AC1's bare-literal mutation check, AC9's live-Postgres schema dump), and the
  owner decisions recorded before implementation began.
- 2026-09-14: Code review (`/bmad-code-review`, three parallel layers) applied. 3 decision-needed findings,
  each taken to the owner and resolved (D-1: classify SMTP failures by reply code, not address-array presence
  — this was the fix for the review's own separately-flagged CRITICAL finding too, a dropped connection during
  RCPT TO being misclassified permanent and silently losing mail forever; D-2: add a blank-password check
  alongside the existing blank-username check; D-3: add the `email_template`/`status` CHECK constraints to
  `V136` and correct its header's false "Hibernate will keep managing them" justification). 24 additional patch
  findings, all fixed. Every finding was independently re-verified before being acted on, per this project's
  standing "beware false positives" practice — not accepted on the review's assertion alone: D-1/the CRITICAL
  RCPT-TO finding were confirmed by disassembling the pinned `org.eclipse.angus:jakarta.mail:2.0.5` jar
  directly (traced `SMTPTransport.sendMessage`/`mailFrom()`/`rcptTo()`/`readServerResponse()` bytecode);
  the `hbm2ddl` precedence mechanism finding was confirmed by disassembling `spring-boot-autoconfigure`/
  `spring-orm` 3.5.16/6.2.19 (the actual mechanism is a `containsKey`-then-`put` fill-the-gap merge into a slot
  Boot's own `HibernateProperties` explicitly vacated, not "vendor map applied after, so it wins"); the
  duplicate-UNIQUE/FK-constraint risk was confirmed (and then closed, by naming both constraints in `V136` to
  match Hibernate's own generated names) via a real fresh-Testcontainers-Postgres boot, not reasoning alone.
  One finding's narrow factual sub-claim ("the javadoc's cause-null claim is factually wrong") did not survive
  re-verification — `MailSendException(Map).getCause()` genuinely is null, confirmed by executing it directly
  — but the finding's real point (the cause chain gets discarded downstream) was valid and got fixed anyway.
  Net changes: `SmtpErrorClassifier` rewritten around SMTP reply codes instead of address-array presence
  (closes both the MAIL-FROM-550 and RCPT-TO-dropped-connection misclassifications in one fix, plus preserves
  the real failure as the returned exception's cause); new shared `ProviderConfigsValidator` closes the
  `MailSenderProvider`/`SmtpPropertiesValidator` duplication finding while adding the blank-password and
  port-range checks; `V136` gained its CHECK constraints plus explicitly-named UNIQUE/FK constraints (verified
  no duplicates on a real fresh boot) and a corrected header; new permanent `EnvelopeEntitySchemaIT` restores
  the ad hoc schema-verification IT as a regression guard; new `ComponentConfigDefaultCustomizerTest` closes
  the untested `ignoreException` predicate; `booking.store.js`'s AC6 guard redesigned around a local
  `resultReceived` flag (fixing a null-vs-seed ambiguity at the root, not by patching around it) and the
  store's public contract reverted to exactly what AC6 originally specified; `NoHardcodedSenderTest` rewritten
  to match line-by-line instead of an unanchored whole-file regex; several dev-doc/javadoc/CI-comment accuracy
  corrections. 3 pre-existing issues explicitly deferred to the ledger (recipient addresses in exception
  messages/stacktraces; `envelope_entity_recipients` has no PK/FK-index; `MailAuthenticationException`
  classifies transient forever) — none introduced by this story. 3 findings dismissed as false positives after
  verification (matrix job status-check naming; an `OUTBOX_DIR` env-var-form grep gap with zero real
  occurrences; the `implicitTls`/`SmtpHealthIndicator` claim, which turned out to be already correct — the
  indicator's own TLS probe is independent of `MailSenderProvider`). One item (AC7's real-`workflow_dispatch`
  verification) is acknowledged as not performable from this environment — flagged as a follow-up for after
  push. File List recounted from `git status` after this pass: 33 files (25 modified, 8 new), up from the
  dev-pass's 26. All touched suites re-run green: 19 backend unit/slice test classes, the full IT sweep
  (`SmtpTransportBootIT`, `MailManagerDuplicateSendIdIT`, `EmailRetrySchedulerIT`, `EnvelopeEntitySchemaIT`,
  the three registration-flow ITs), `MigrationConventionLintTest`, frontend Vitest (108/108, both TZs),
  `eslint`/`prettier --check`, `actionlint`. No `mvn verify` run locally — GitHub CI remains the
  full-verification gate.
