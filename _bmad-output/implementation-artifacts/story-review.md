# Story Review: skillars-deferred-113-email-notification-robustness-quota-completeness

**Reviewer:** senior-dev audit (grounded against current repo state, `HEAD` = `bdcf782d`, branch `story/deferred-113-email-quota-robustness`)
**Story file reviewed:** `skillars-deferred-113-email-notification-robustness-quota-completeness.md`, Status: `ready-for-dev`

**Verdict: NOT ready for dev as written.** The story's own commit message claims "All items verified against HEAD. Line numbers and code citations confirmed accurate" — that claim is false. Of the 10 ACs, **6 (AC1, AC2, AC4, AC5, AC6, AC7) describe bugs that are already fixed on `HEAD`** — most of them closed by `skillars-deferred-110` and `skillars-deferred-111`, both merged *before* this story was authored today (2026-09-15). One of the two ACs marked CRITICAL (**AC9**) does not reproduce against the real `CircuitBreakerFactory` — I traced the exact bytecode path through resilience4j 2.2.0 / spring-cloud-circuitbreaker-resilience4j 3.3.3 (the versions this project actually resolves) and then ran the existing test that already exercises this scenario; it passes today. Only **AC3** and **AC10** describe genuine, currently-reproducible gaps, and even AC10's "Fix" section cites a repository class that doesn't exist and omits a real complication in the class it does correctly point at (`SubscriptionService`). **AC8** is real code with a real defensive gap, but the story's "Failure Scenario" overstates the certainty of a root cause that a prior story's own investigation explicitly found does not hold under this codebase's transaction semantics.

Every claim below was checked directly against file contents, `git log -p -L` history, decompiled/`javap`'d library bytecode (for the versions this project's `pom.xml` actually resolves — confirmed via `mvn dependency:tree`), and by running the relevant test classes locally (`MailManagerResilienceTest`, `RegistrationEmailDurabilityIT` — both 100% green pre-story, which is itself evidence against several of the claims below).

---

## Summary table

| AC | Claim | Verdict |
|----|-------|---------|
| AC1 | Adapter-wrap-depth guard | **Wrong bug, wrong file.** Ledger points at a nonexistent package; the real source item is about `MailManager.isRetryable`'s fixed-depth cause walk, not `MailSenderProvider`. |
| AC2 | DOWN-health-caching TTL | **Already fixed** — `skillars-deferred-111` AC4, shipped and documented in `SesHealthIndicator.java`. |
| AC3 | Mid-loop rate-limit recipient duplication | **Confirmed open, accurately described.** Genuine bug, correctly CRITICAL. |
| AC4 | Rate-limit WARN-log-level consistency | **Already fixed, and "consistent" would be a regression** — `skillars-deferred-111` AC5 deliberately made it WARN-on-transition / DEBUG-while-throttled to kill log spam. |
| AC5 | `Map.of`→`HashMap` null-token guard | **Miscited — no `Map.of` exists at the cited location**, and never has. The line has been `new HashMap<>(...)` since the initial commit. |
| AC6 | Log-masking-bypass (exception vs data asymmetry) | **Already fixed** — `skillars-deferred-111` AC11, verified by re-reading the exact code and by running `RegistrationEmailDurabilityIT` (log shows `data={verifyUrl=[REDACTED]}`). |
| AC7 | `RegistrationEmailDurabilityIT` global-state | **Mischaracterized.** Original source item calls this a "fragility note... correct today"; the story reframes it as a correctness bug needing `@AfterEach` cleanup, which contradicts the test file's own documented, deliberate design (UUID-unique data, no cleanup, by design). |
| AC8 | `persisted==null` outcome | **Real code, real gap — but overstated certainty.** A prior story's own investigation concluded the suspected root cause "does not hold under this codebase's actual transaction semantics" and left only diagnostics. AC8 presents it as a confirmed, reproducible loss. |
| AC9 | Circuit-breaker classification (CRITICAL) | **Does not reproduce.** Bytecode-traced through the real resilience4j/TimeLimiter/Spring-Cloud-CircuitBreaker call chain (versions this project actually resolves) plus an existing, currently-green test (`isRetryable_permanentTransportException_persistsRetryFalse`) that exercises exactly this path. |
| AC10 | Player quota tier gap | **Confirmed open, correctly described** — but the "Fix" section names a repository class that doesn't exist and misses a real complication in the alternative it names. |

---

## AC1 — Adapter-wrap-depth guard: wrong bug, wrong file

The AC describes "multiple layers of provider configuration nesting" in SMTP adapter instantiation, and points the ledger at `platform/notification/infrastructure/adapter/*MailSenderProvider.java`.

- That package **does not exist**. The real file is `src/main/java/com/softropic/skillars/infrastructure/email/smtp/MailSenderProvider.java` — a different package entirely (`infrastructure.email.smtp`, not `platform.notification.infrastructure.adapter`).
- I read `MailSenderProvider.java` in full: it is a flat round-robin list of `JavaMailSenderImpl` built from `ProviderConfig`s (`ProviderConfigsValidator.validate(...)` at construction, `Math.floorMod` round-robin selection). There is no wrapping/nesting concept in this class at all — nothing to guard a "depth" on.
- Traced the item to its actual origin: `deferred-work.md:2090` cites `ses-1-2's §7.2 item 13 adapter-wrap-depth guard`, which points to `requirements/ses-email-consolidation.md:1072-1082`. That item is **`MailManagerErrorClassificationTest`** — a test verifying `MailManager.isRetryable`'s *fixed two-level cause-chain walk* correctly classifies `EmailTransportPermanentException` **regardless of whether an adapter wraps it at depth 1 or depth 2**, so that "an adapter that adds one extra wrapper for context" can't silently push the real type out of the inspected range. This is a test-coverage concern about `MailManager.isRetryable`, closely related to (arguably a precursor of) **AC9** — not about SMTP provider config nesting.
- The ses-1-2 story that first raised it explicitly recorded it as **`[Review][Defer]`**, not carried into that story's ACs (`ses-1-2-smtp-behind-port-and-containment.md:186`).

**Recommendation:** rewrite AC1 to target what the source item actually asks for — additional `MailManagerErrorClassificationTest`/`MailManagerResilienceTest` cases proving `isRetryable` is robust to an adapter wrapping at depth 1 *or* depth 2 — or drop it if `MailManagerResilienceTest.isRetryable_permanentTransportException_persistsRetryFalse` (which already runs through the real circuit breaker) is judged sufficient. Either way, the current AC1 text and ledger should not survive into a dev-ready story unchanged.

## AC2 — DOWN-health-caching TTL: already shipped

`SesHealthIndicator.java` already implements exactly what this AC asks for, and says so in its own doc comment:

> "skillars-deferred-111 AC4 (owner decision) gave this indicator the same `SesHealthProperties` config surface `SmtpHealthProperties` already had... a freshly-computed `DOWN` result is now cached for `SesHealthProperties.getDownTtl()` (default 15s), shorter than an `UP` result's `getTtl()` (default 60s)."

Confirmed independently in `skillars-deferred-111-...md:146-173,513,606-632`: AC4 there is titled "SES/SMTP health indicator DOWN-result caching — decision needed", decided as "configurable DOWN TTL", and modified exactly `SesHealthIndicator.java` + new `SesHealthProperties.java` + `SesHealthIndicatorTest.java`. This AC asks for a fully-shipped feature to be built again.

**Recommendation:** drop AC2, or convert it into a much narrower "confirm `SesHealthIndicatorTest` still covers the TTL behavior" verification task if there's a specific coverage gap — but as written it duplicates completed work.

## AC3 — Mid-loop rate-limit recipient duplication: confirmed, accurately described

This is real and correctly the other CRITICAL item. Verified directly:

- `MailManager.sendEmailSync` (`MailManager.java:119-136`) loops over `recipients` inside a single `circuitBreaker.run(...)` call with **no per-recipient success tracking**. A rejection at recipient *k* throws out of the loop; the whole envelope is marked `FAILED`/`retry=true`.
- `EmailRetryScheduler.sendAll` (`EmailRetryScheduler.java:157-165`) re-drives via `EnvelopeMapper.toEnvelope(entity)` → `mailManager.sendEmailSync(envelope)`, reconstructing the **full original recipient list** — there is no "already delivered" state anywhere on `EnvelopeEntity` (`EnvelopeEntity.java` has no such field). The retry genuinely restarts the loop at recipient 1.
- The "latent case" claim is accurate: `SendMailListener.java:42-51` does build multi-recipient `Envelope`s from `sendMailEvent.userIds()`.

No corrections needed here.

## AC4 — Rate-limit WARN-log-level: already fixed, and the literal ask is a regression

`SesSendRateLimiter.acquireOrThrow()` (`SesSendRateLimiter.java:84-97`) already has a single, deliberate log-level policy, shipped as `skillars-deferred-111 AC5`:

- First rejection in a burst (the `false → true` transition into `throttled`): `log.warn(...)`.
- Every subsequent rejection while still throttled: `log.debug(...)`.
- A `code review 2026-09-15 (H2)` comment on the same file explains *why*: an earlier version's unconditional-WARN approach produced "~60 WARN lines per minute under sustained load" at the sandboxed 1/s rate, so a 3-second cooldown (`THROTTLE_LOG_COOLDOWN`) was added specifically to prevent that.

AC4 asks for "Log level is consistent across all rate-limit rejection paths" — taken literally, that means reverting this fix and going back to WARN-per-rejection, which is the exact log-spam problem `deferred-111` closed.

**Recommendation:** drop AC4, or if the concern is something narrower (e.g., a *different*, still-inconsistent rate-limit log site elsewhere), the story needs to name it — I could not find one; `MailManager`'s own rate-limit failures are only ever logged via the single catch-all `logger.error(...)` at `MailManager.java:155`, which applies uniformly to every failure type, not just rate-limits.

## AC5 — `Map.of`→`HashMap` null-token guard: miscited, no live risk at the cited location

The ledger cites `MailManager.java:50-65` for a `Map.of(...)` construction. That line range is the `SENSITIVE_DATA_TEMPLATES` set declaration and constructor, not the token map. The actual per-recipient data map is built at `MailManager.java:121`:

```java
final Map<String, Object> data = new HashMap<>(envelope.data());
```

`git log -p -L 120,123:...MailManager.java` shows this line has been `new HashMap<>(...)` **since the very first "init" commit** — it has never been `Map.of(...)`. There is no mutation risk or null-value NPE risk at this call site.

I searched every `Map.of(...)` call site in the codebase that feeds an `Envelope`/`SendMailEvent`'s data map (`AlertNotificationListener.java:70`, `TwoFactorLoginService.java:64`, `AccountManagementFacade.java:94,104,167,185`, `ReportGenerationService.java:134,219`). None of these are cited by AC5. Spot-checked `AlertNotificationListener.java:70` (`Map.of("subject", subject, "body", body)`) — both values are locally-computed, always-non-null Strings, so no live NPE risk there either. I did not exhaustively verify every other call site's nullability, but none of them is the location the story points at.

**Recommendation:** either drop AC5, or — if there's a genuine concern about `Map.of()` NPE-ing on a null token somewhere in the OTP/registration/account-management call chain — re-scope it to name the actual candidate sites (`AccountManagementFacade`, `TwoFactorLoginService`) instead of a `MailManager` location that has never had this shape.

## AC6 — Log-masking-bypass (exception vs data asymmetry): already fixed

The exact asymmetry described (data redacted, exception cause-chain not) is already closed, and the fix is `skillars-deferred-111 AC11` — documented in-place at `MailManager.java:147-159`:

> "skillars-deferred-111 AC11 (owner decision: full sanitizer): the exception is logged as a sanitized STRING parameter, not passed as SLF4J's dedicated trailing-Throwable argument... envelopeEntity.getError() is already the same sanitized stacktrace toEnvelopeEntity persisted just above."

Mechanically: `envelopeEntity.getError()` is a `String` (see `EnvelopeEntity.error` field), and the log statement has exactly 6 `{}` placeholders matched to exactly 6 arguments — so SLF4J never treats it as the special trailing-Throwable argument that would bypass redaction. `toEnvelopeEntity` (`MailManager.java:264-273`) builds that string via `EmailPiiSanitizer.sanitize(ExceptionUtils.getStackTrace(exception))` before it's ever logged.

I ran `RegistrationEmailDurabilityIT` locally (7/7 green) and the log output for a `COACH_EMAIL_VERIFY` send shows masking working end-to-end: `data={verifyUrl=[REDACTED]}`.

**Recommendation:** drop AC6, or re-scope to a genuinely-unmasked call site if one exists elsewhere (I did not find one in `platform/notification/`).

## AC7 — `RegistrationEmailDurabilityIT` global-state: mischaracterized

The AC asks for `@AfterEach` cleanup, no cross-test coupling, and "template seed data is not mutated" — framed as a correctness gap.

The actual source item (`ses-1-4-registration-email-durability.md:166`) is far narrower and already triaged:

> "`RegistrationEmailDurabilityIT`'s scheduler cases operate on global repository state — deferred, fragility note. `retryFailedEmails()` polls the whole table and the helpers use `findAll()`; assertions are scoped by a UUID-unique address **so it is correct today**, but both grow with the suite."

That's a scalability/performance fragility note (unbounded `findAll()` scan cost as the shared test DB accumulates rows over the life of the suite), explicitly marked "correct today" — not a data-correctness bug. The test file's own class-level Javadoc (`RegistrationEmailDurabilityIT.java:71-107`) documents a **deliberate** isolation strategy: every test uses `freshEmail(prefix)` (`UUID.randomUUID()`-suffixed addresses) precisely so tests don't need cleanup or ordering guarantees. There is no `@AfterEach` anywhere in the file, no template mutation anywhere in the file, and the class comment explicitly accepts that rows "grow with everything else the shared JVM-static Postgres accumulates across the suite" as a known tradeoff.

I ran the class locally: 7/7 tests pass (note: the story says "6 cases", the file has 7 `@Test` methods — a minor count discrepancy worth fixing regardless of the larger point).

**Recommendation:** if this AC survives, re-scope it to the actual concern (bound/optimize the `findAll()` scans, e.g. by narrowing the query rather than scanning the whole table) instead of proposing `@AfterEach` row deletion, which cuts against the file's own documented design intent and isn't what the original source item asked for.

## AC8 — `persisted==null` outcome: real gap, overstated certainty

The code is real: `VideoModerationEmailListener.sendAdminAlertSync` (`VideoModerationEmailListener.java:83-145`) does log-WARN-and-return on `persisted==null`, and `ModerationAdminAlertOutboxHandler.handle` (confirmed) does not throw in that case, so `OutboxRowProcessor.claimAndProcess` (confirmed: `handler.handle(...); repository.delete(row);` with no exception in between) deletes the durable row on a normal return. The mechanical chain the story describes is accurate.

What's missing is that this exact scenario was already investigated by a prior story, and the investigation's conclusion is documented **in the same method**, immediately above the `persisted==null` branch (`VideoModerationEmailListener.java:104-118`):

> "skillars-deferred-110 AC5: a REQUIRES_NEW commit-visibility race was the original suspected cause, but **does not hold under this codebase's actual transaction semantics** — mailManager.sendEmailSync's own REQUIRES_NEW transaction has already committed by the time control returns here..., and this read then runs under PostgreSQL's default READ COMMITTED... Rather than guess at a fix for an unproven cause, this log now captures the transaction/isolation context alongside the read-back result, so a real occurrence can be diagnosed instead of assumed."

And `deferred-work.md`'s own audit trail confirms this is still the status quo: "`skillars-deferred-109`'s `VideoModerationEmailListener` `persisted == null` bullet (AC5 — **root cause still unestablished**, diagnostic-only WARN enrichment added)."

AC8's "Failure Scenario" section states the consequence ("permanently-failed moderation audit alert silently lost") as if it's a confirmed, reproducible outcome. It isn't — it's a theoretical race two prior stories looked at and could not establish as reachable under this codebase's actual transaction/isolation behavior, and deliberately chose diagnostics over a guessed fix for that reason.

**Recommendation:** keep AC8 as defense-in-depth (Option B — throw `IllegalStateException` to retain the row — is safe regardless of whether the race is real, and costs nothing since the scenario is rare-to-never by the prior analysis), but rewrite the "Failure Scenario" to say what's actually known: root cause unconfirmed, diagnostic logging already in place from two prior stories, this AC is precautionary hardening rather than a confirmed-active data-loss bug. Option A (bounded retry) is harder to justify given the prior analysis found no plausible visibility-lag mechanism to wait out.

## AC9 — Circuit-breaker classification (CRITICAL): does not reproduce

This is the most consequential finding. I verified this three independent ways; all three agree the bug as described does not exist in the current code.

**1. Source-level trace of `MailManager.sendEmailSync`'s fallback (`MailManager.java:137-142`):**

```java
}, throwable -> {
    if (throwable instanceof RuntimeException && throwable.getCause() != null) {
        throw (RuntimeException) throwable;
    }
    throw new RuntimeException("Email sending failed via Circuit Breaker", throwable);
});
```

`git log -p -L 137,142:...MailManager.java` shows this exact code **since the initial commit** — it is not new, and not part of any pending fix. Every exception the retry-loop callback throws is always `new RuntimeException("Unexpected {retryable,non-retryable} email error", e)` — always a `RuntimeException` with a non-null cause. So the first branch (preserve-unchanged) is the one that fires for every exception coming out of the retry loop, including a permanent one; the generic "Email sending failed via Circuit Breaker" wrap is only reached for exceptions that *don't* go through the retry loop at all (e.g. `CallNotPermittedException` when the breaker is OPEN — itself correctly transient/retryable).

**2. Bytecode trace of the actual library versions this project resolves** (`mvn dependency:tree` confirms `resilience4j-circuitbreaker:2.2.0`, `resilience4j-timelimiter:2.2.0`, `spring-cloud-circuitbreaker-resilience4j:3.3.3` — not just "some resilience4j version"):

- `TimeLimiterImpl.lambda$decorateFutureSupplier$0` (`javap -c`'d directly): catches `ExecutionException` from `future.get(...)`, unwraps via `getCause()`, and rethrows the **unmodified cause** — it does not lose or re-wrap it.
- `CircuitBreaker.lambda$decorateCallable$4`: calls `callable.call()`, and on exception calls `onError(...)` then `athrow`s the **exact original `Throwable`**, unconditionally — whether or not the exception matches `ignoreException(...)` only affects internal failure-rate bookkeeping, never whether/how the exception propagates.
- `Resilience4JCircuitBreaker.getAndApplyFallback` (Spring Cloud's own wrapper): `catch (Throwable t) { return fallback.apply(t); }` — no wrapping at all.

So the exception `MailManager`'s fallback function receives is, byte-for-byte, the same `RuntimeException(msg, EmailTransportPermanentException)` the retry loop threw. `ignoreException(EmailTransportPermanentException.isPresentIn(...))` in `ComponentConfig.defaultCustomizer()` (itself already correctly matching, per `skillars-deferred-110 AC4b`) only stops it counting toward the breaker's failure rate — it does not touch cause preservation.

**3. Empirical:** ran `MailManagerResilienceTest` locally — **7/7 green**, including `isRetryable_permanentTransportException_persistsRetryFalse`, which drives an `EmailTransportPermanentException` through a **real** `Resilience4JCircuitBreakerFactory` (not a mock) and asserts `isRetry()==false` on the persisted row. This is essentially the scenario AC9 claims is broken, and it currently passes.

**Recommendation:** do not implement AC9 as written. Before doing any work here, write the proposed `MailManagerCircuitBreakerClassificationIT` first and confirm it actually fails on current `HEAD` — I expect it will pass immediately, in which case AC9 should be closed as already-correct (possibly folded into the AC1 rewrite above, since they're the same underlying concern: proving `isRetryable`'s fixed-depth walk is robust). If it does fail, that means my trace above missed some other production wiring difference between `ComponentConfig`'s real beans and what I inspected — worth a second look at `ComponentConfig` (real file: `platform/notification/config/ComponentConfig.java`, **not** the ledger's cited `platform/notification/infrastructure/config/ComponentConfig.java`, which doesn't exist) before trusting either verdict blindly.

## AC10 — Player quota tier gap: confirmed open, "Fix" section needs correction

The core claim is accurate and current: `QuotaConfigService.resolveTierKey` (`QuotaConfigService.java:47-62`) still has only a coach-UUID switch and a bare `"athlete"` fallback for every non-UUID `ownerId`; `ConfigBounds.java:224` already lists `semiPro`/`pro` (confirming AC10 of `deferred-109` did its part); `V139__baseline_seed_data.sql:171-172,175-176` carries the `video.quota.pro.*` / `video.quota.semiPro.*` rows (2 GiB/10 GiB athlete vs 4 GiB/25 GiB semiPro vs 7 GiB/30 GiB pro, matching the story's numbers). `deferred-work.md`'s own 2026-09-15 ad-hoc audit independently re-confirmed this is still open, same day this story was created.

Two corrections needed in the "Fix" section:

1. **`PlayerSubscriptionTierBillingRepository` does not exist.** Only `PlayerSubscriptionTierBilling` (an enum, `payment/contract/PlayerSubscriptionTierBilling.java` — `ATHLETE, SEMI_PRO, PRO`) exists; there is no repository by that name. The other option named, `SubscriptionService`, does exist but:
2. **`SubscriptionService.getPlayerSubscription(Long parentUserId, Long playerId)` is not a drop-in tier lookup.** It requires a `parentUserId` that `resolveTierKey(String ownerId)` doesn't have, and its first line is `assertPlayerOwnership(parentUserId, playerId)` — an authorization check that has no meaning for an internal quota resolution and would need a `null`/synthetic `parentUserId` to even call, likely throwing. The method that actually does the DB lookup without an ownership check, `findOrCreatePlayerSubscription(Long playerId)`, is **private** — and critically, it **writes**: `paymentPlayerSubscriptionRepository.findByPlayerId(playerId).orElseGet(() -> { ...save(newSub)... })`. Wiring quota resolution through it (even via a new public wrapper) would turn every quota check for a player with no subscription row yet into a DB insert, on what's likely a hot path (video upload quota checks) — a real, previously-unflagged side effect and transaction-boundary question the story should call out rather than leave to the implementer to discover.

**Recommendation:** point the implementer at `PaymentPlayerSubscriptionRepository.findByPlayerId(Long)` directly (read-only, `Optional`-returning — the `else → "athlete"` branch AC10 already specifies handles the not-found case correctly without needing the auto-create side effect), not at `SubscriptionService` or the nonexistent repository name.

---

## Additional notes

- **The story's "Files to Read Before Implementation" section is stale**: it cites `V53__video_quota_system.sql` (lines 37-38, 44-45) for the SEMI_PRO/PRO seed rows. That file no longer exists — `skillars-deferred-112` (merged as the immediate parent of this story's own commit, same branch history) squashed `V02`-`V137` into `V138__baseline_schema.sql` + `V139__baseline_seed_data.sql`. The equivalent rows are now `V139__baseline_seed_data.sql:171-172,175-176` (see AC10 above). This is a small thing on its own, but it's a second, independent data point (alongside AC1/AC2/AC4/AC5/AC6/AC7 above) that this story was not actually checked against `HEAD` before being marked `ready-for-dev`, despite the commit message's claim.
- **Ledger path errors, summarized** (all independently confirmed against the actual directory tree):
  - AC1: `platform/notification/infrastructure/adapter/*MailSenderProvider.java` → doesn't exist; real file is `infrastructure/email/smtp/MailSenderProvider.java`.
  - AC2: `platform/notification/infrastructure/health/SesHealthIndicator.java` → doesn't exist; real file is `infrastructure/ses/SesHealthIndicator.java`.
  - AC9: `infrastructure/config/ComponentConfig.java` → doesn't exist; real file is `platform/notification/config/ComponentConfig.java` (no `infrastructure` segment).

## Recommendation

Given 6 of 10 ACs are already resolved and one CRITICAL AC (AC9) doesn't reproduce, this story needs a fresh authoring pass against current `HEAD` before it's dev-ready, not a line-edit. Suggested scope for a corrected story:

- **Keep, unchanged:** AC3 (mid-loop rate-limit duplication).
- **Keep, corrected:** AC10 (fix the "Fix" section per above).
- **Keep, reframed:** AC8 (defense-in-depth framing, not confirmed-data-loss framing).
- **Rewrite or fold into AC9's replacement:** AC1 (retarget at `isRetryable`'s depth-robustness, not `MailSenderProvider`).
- **Verify-then-likely-close:** AC9 (write the IT first; expect it to pass; close as already-correct if so).
- **Drop:** AC2, AC4, AC5, AC6, AC7 — all already shipped by `deferred-110`/`deferred-111`, or (AC5, AC7) miscited/mischaracterized with no live gap found at the cited location.
