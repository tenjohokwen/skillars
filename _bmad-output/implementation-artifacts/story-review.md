# Senior Dev Review — Story `ses-1.3` (Health, Monitoring, Rate Limiting)

**Reviewed:** `_bmad-output/implementation-artifacts/ses-1-3-health-monitoring-rate-limiting.md` @ `3c00173f`
**Scope:** corner cases, false assumptions, missed flows, missed call sites. Every finding below was
verified against HEAD sources or against the resolved jars (`javap`); nothing is inferred from
documentation alone. A "Verified accurate" section at the end records the story claims I checked and
found *correct*, so they are not re-litigated.

**Verdict: changes required before dev.** The story is unusually well-researched — most of its
"verified" assertions hold. But four findings will stop a literal implementation dead (a runtime
`IllegalArgumentException` at bean creation, two existing tests that break and are not listed as
modified, and a leaked resilience4j exception type that AC3 explicitly forbids), and AC1's stated
purpose (sandbox detection) is not achieved by AC1's own UP/DOWN mapping.

---

## Blocking

### B1 — AC4's prescribed `RetryTemplate` construction throws at bean creation

AC4 says to rebuild `ComponentConfig.retryTemplate()` "from its current no-policy
`RetryTemplate.builder().maxAttempts(3).fixedBackoff(1s).build()` to carry a `SimpleRetryPolicy(3, …)`".
The 4-arg `SimpleRetryPolicy` constructor does exist (verified, `spring-retry-2.0.13`), but there is no
way to hand it to the existing builder chain:

```
RetryTemplateBuilder.maxAttempts(int):
  Assert.isNull(this.baseRetryPolicy, "You have already selected another retry policy")
RetryTemplateBuilder.customPolicy(RetryPolicy):   // sets baseRetryPolicy
```

`.maxAttempts(3).customPolicy(new SimpleRetryPolicy(3, …))` fails with
`IllegalArgumentException: You have already selected another retry policy` — at context startup, for
every environment. (Verified from the bytecode of `RetryTemplateBuilder.maxAttempts`.)

Two workable routes; the AC should name one:
- **`customPolicy` alone** — drop `.maxAttempts(3)`; `SimpleRetryPolicy`'s first arg already carries it.
  `RetryTemplate.builder().customPolicy(policy).fixedBackoff(Duration.ofSeconds(1)).build()`.
- **Builder-native (preferred)** —
  `.maxAttempts(3).notRetryOn(EmailTransportRateLimitedException.class).traversingCauses().fixedBackoff(Duration.ofSeconds(1))`.
  `build()` composes this into a `CompositeRetryPolicy(MaxAttemptsRetryPolicy, BinaryExceptionClassifierRetryPolicy)`
  with `defaultValue=true` inferred from using `notRetryOn` (verified in `build()`'s bytecode), i.e.
  exactly AC4's intended semantics with none of the hand-rolled construction.

### B2 — AC3's catch is incomplete; a resilience4j type still leaks out of `SesSendRateLimiter`

AC3 requires that `RequestNotPermitted` "never leak past this class". `RateLimiter.waitForPermission`
throws a **second** resilience4j type. From `resilience4j-ratelimiter-2.2.0`:

```
waitForPermission(RateLimiter, int):
  acquirePermission(int)
  Thread.currentThread().isInterrupted()  -> throw new AcquirePermissionCancelledException()
  if (!permission)                        -> throw RequestNotPermitted.createRequestNotPermitted(...)
```

`io.github.resilience4j.core.exception.AcquirePermissionCancelledException` is an unchecked
`RuntimeException`, so it sails through:
`SesSendRateLimiter` (catches only `RequestNotPermitted`) → `SesEmailSender.send`'s
`catch (SdkException)` (not an `SdkException`) → AC5's `catch (EmailTransportException)`
(not one either, so **no metric is recorded at all** — see M5) → `MailManager.isRetryable` →
classified retryable by accident rather than by design.

Reachable whenever the sending thread carries an interrupt — most obviously graceful shutdown of the
`sendMailPool` executor while an `@Async` `sendEmailFromTemplate` is in flight. Fix: either catch
`RuntimeException` and re-classify, or call `rateLimiter.acquirePermission()` directly (returns
`boolean`, throws neither) instead of the `waitForPermission` static helper — the latter is simpler
and makes the "never blocks" contract self-evident.

### B3 — Three existing test call sites break; the story lists one

The Dev Notes say only "`SesEmailSenderTest` already exists and will need the new mock added". Also
affected, none of them mentioned:

| File | Why it breaks |
|---|---|
| `src/test/java/…/infrastructure/ses/SesAddressValidationTest.java:29` | `new SesEmailSender(client, props, new EmailAddressParser(), new SesErrorClassifier())` — compile error once AC3 adds the `rateLimiter` field. |
| `src/test/java/…/infrastructure/email/TransportWiringTest.java:38-43` | The runner registers `SesConfig, SesEmailSender, SesErrorClassifier, EmailAddressParser, …` but **not** `SesSendRateLimiter`, and has no `MeterRegistry` bean. `transportSes_wiresSesEmailSenderAndSesV2Client` fails on `NoSuchBeanDefinitionException`. Needs `SesSendRateLimiter.class` **plus** a `SimpleMeterRegistry` supplied via `withBean(...)`. |
| `src/test/java/…/infrastructure/email/TransportWiringTest.java:110-124` | `healthRunner` sets `provider-configs[0].host/port` but **no `app.email.transport`**. After AC2's gate, the existing `assertThat(ctx).hasSingleBean(SmtpHealthIndicator.class)` assertion **fails**. AC6 says "extend this test file" — it must also say "fix the existing assertion to supply `app.email.transport=smtp`", or this reads as an unrelated regression to whoever hits it. |

### B4 — AC2's premise "never happens today" is false; it is happening in prod right now

AC2 justifies the transport gate with: *"a `transport=ses` environment with SMTP providers still
configured in YAML (**never happens today**, but nothing prevents it)"*.

`src/main/resources/application.yaml:158-170` sets `app.email.smtp.provider-configs[0].host = mail.gmx.net`
**unconditionally** (base document, no profile), and `application-prod.yaml` does not clear it while
setting `app.email.transport: ses`. So prod today satisfies `provider-configs[0].host` and
`SmtpHealthIndicator` **is an active bean in production**, opening sockets to `mail.gmx.net:587` and
`smtp.gmail.com:587` every 60s from prod nodes and reporting their reachability as this app's
`notification` health.

The fix AC2 prescribes is correct and this makes it *more* urgent, not less — but the framing should
change from hypothetical to live-defect, and AC6's test plan should assert the **real prod shape**:
`app.email.transport=ses` **with** base-yaml-style `provider-configs[0].host` set ⇒ no
`SmtpHealthIndicator` bean. As written, AC6 describes that case but calls it "even if SMTP provider
properties happen to be set", which will read to a dev as a defensive edge case rather than the
production configuration.

---

## Should fix

### M1 — AC1 does not detect the sandbox, which the story says is its purpose

The Story statement ("reports whether SES can actually send — **sandboxed**, suspended, or genuinely
healthy") and the `§6.8` citation both name sandbox/account-state detection as this indicator's job.
AC1's mapping is UP ⟺ `sendingEnabled() == true`.

`sendingEnabled` is **true** on a sandboxed account — sandbox restricts *recipients*, it does not
disable sending. `productionAccessEnabled` is the sandbox signal, and AC1 relegates it to a detail
that nothing alerts on. Verified against `sesv2-2.54.13`'s `GetAccountResponse`, which also exposes
`enforcementStatus()` (`HEALTHY` / `PROBATION` / `SHUTDOWN`) — the actual *suspension* signal — which
AC1 does not read at all.

Net effect as specified: a sandboxed or on-probation prod account reports **UP**, i.e. the exact
"before it matters" failure the story exists to prevent stays invisible. Decide explicitly and write
it into the AC — a reasonable mapping is UP only when `sendingEnabled && productionAccessEnabled &&
!"SHUTDOWN".equals(enforcementStatus)`, or UP-with-a-warning-detail if the owner prefers not to page
on sandbox. Either is fine; silently mapping sandbox to UP is not.

### M2 — AC1's justification for the local catch is factually wrong

> "this indicator's failure to reach AWS must never throw out of `doHealthCheck` — actuator health
> indicators that throw break the endpoint for every other indicator in the same group"

`AbstractHealthIndicator.health()` already wraps `doHealthCheck` in `try { … } catch (Exception ex) {
builder.status(DOWN).withException(ex); }`. A throwing `doHealthCheck` degrades **only that
contributor** to DOWN; it does not break the group or the endpoint.

Catching locally is still the right call — for the detail shape AC1 specifies and to keep a stack
trace out of the payload — but the AC should say that, because the stated reason invites a reviewer
to accept a defensive `catch (Throwable)` on a premise that does not hold.

### M3 — Null-unboxing on the SDK response

Both accessors AC1 relies on are boxed/nullable (verified):
`GetAccountResponse.sendingEnabled()` → `java.lang.Boolean`; `GetAccountResponse.sendQuota()` →
`SendQuota` (all three of whose accessors return `java.lang.Double`).

A literal reading of AC1 (`UP when response.sendingEnabled() is true`) produces
`if (response.sendingEnabled())` → **NPE on a partial response**, and
`response.sendQuota().maxSendRate()` → NPE when the quota block is absent. AC1 hedges with "(when
available)" but never says what "available" means in code.

This is not theoretical for this codebase: `SesEmailEndToEndIT` already exercises SES against WireMock
stubs, and a hand-written `GetAccount` stub is precisely where a partial body appears. Spell out
`Boolean.TRUE.equals(...)` and a null-guarded `sendQuota` in the AC, and add a
"`getAccount` returns a response with null `sendQuota`" case to `SesHealthIndicatorTest`.

### M4 — AC5's transport tag NPEs in a state the codebase deliberately supports

AC5 tags the metric `transport=<ses|smtp|log lowercase>` from `EmailTransportProperties`. That field
has **no default** (`EmailTransportProperties.java:17`), and property-absent is a state this codebase
explicitly keeps working: `LoggingEmailSender` carries `matchIfMissing = true` with a javadoc calling
it "load-bearing, not decoration", and `TransportWiringTest.transportAbsent_fallsBackToTheSafeLoggingTransport`
pins it. In that state `getTransport()` is `null`, and Micrometer rejects a null tag value — so
**every email send throws** from the metrics wrapper.

Add the fallback to the AC (`transport = props.getTransport() == null ? "log" : props.getTransport().name().toLowerCase(Locale.ROOT)`)
and a `MailServiceTest` case for it. Note `Locale.ROOT` explicitly — `toLowerCase()` with a Turkish
default locale turns `SES` into `ses` fine but is a latent trap worth closing while you're here.

### M5 — AC5's catch scope leaves an unmeasured hole

AC5 records `outcome=failure` "on any `EmailTransportException`". Anything else thrown by the port —
B2's `AcquirePermissionCancelledException`, a Mockito/SDK `NullPointerException`, a
`RejectedExecutionException` — records **neither** success nor failure, so
`sum(mail_send_seconds_count)` silently under-counts attempts, which is the one property an outcome
metric has to have.

Use `try { … } catch (Throwable t) { record(failure); throw t; }` or a `finally` with an outcome
variable. The AC's genuinely important constraint — *rethrow the identical instance, never wrap* —
is preserved either way, and AC6's `isSameAs` assertion still proves it.

### M6 — AC4's fail-fast has an unstated cost: mid-loop abort ⇒ duplicate sends on re-drive

`MailManager.sendEmailSync` (`MailManager.java:74-90`) runs `retryTemplate.execute(...)` **per
recipient inside a loop**. Today a transient failure on recipient 3 of 5 gets up to 3 attempts with a
`fixedBackoff(1s)`. AC4 makes a rate-limit rejection abort on attempt 1, which aborts the whole loop:
recipients 4–5 are never attempted, the envelope is persisted `FAILED, retry=true`, and
`EmailRetryScheduler` re-drives the **entire envelope** (`EmailRetryScheduler.sendAll` →
`mailManager.sendEmailSync(envelope)`), re-sending to recipients 1–2 who already received it.

The sharp edge: the existing `fixedBackoff` is **1 second** and the limiter's `limitRefreshPeriod` is
**1 second**. The retry AC4 removes is the one most likely to have succeeded — it lands exactly one
refresh window later. §6.17's rationale (don't hold `sendEmailSync`'s transaction open) is real, but
the RetryTemplate already holds it open for up to 2s of backoff on every *other* transient failure, so
AC4 buys a narrow reduction in hold time and pays for it in duplicate mail.

This may still be the right call — it is the owner's decision. It should be *recorded as a decision*
in the AC, with the duplicate-send consequence named, not presented as strictly better.

### M7 — Rate-limit rejections still consume the circuit breaker's failure window

AC4 says the fix avoids attempts "burned against the 5-recipient-loop/**circuit-breaker**/transaction
budget". It avoids burning *retries*; it does not stop the rejection counting as one failure against
the `emailService` breaker, because `circuitBreaker.run(...)` wraps the whole loop and the exception
propagates out of it (`MailManager.java:72-96`).

With `slidingWindowSize=5, minimumNumberOfCalls=5, failureRateThreshold=50%, waitDurationInOpenState=5s`
(`ComponentConfig.defaultCustomizer`), **five rate-limited envelopes open the breaker for all outbound
mail for 5 seconds** — including sends that were comfortably under the limit. That is arguably
acceptable backpressure, but it is a behaviour change the story does not mention and the AC's wording
currently implies is avoided.

### M8 — Burst → attempt exhaustion → permanent mail loss

Interaction the story does not walk: `EnvelopeEntityRepository:17` fetches `LIMIT 10` retryable
envelopes per tick (default 60s), and `EmailRetryScheduler.sendAll` dispatches them in a tight,
**unpaced** loop. Each `sendEmailSync` increments `attempts`; `MAX_RETRY_ATTEMPTS = 6` then marks the
envelope `ATTEMPTS_EXHAUSTED` with `retry=false` — permanently dropped.

With single-recipient envelopes and the default `max-send-rate-per-second=10` this is marginal
(10 sends vs 10 permits). With multi-recipient envelopes the burst is 10 × N sends against 10 permits,
so roughly `10N − 10` rejections per tick, each burning one of six attempts on an envelope that hit no
actual SES error. Sustained saturation therefore reaches permanent mail loss in ~6 minutes.

At minimum, the story should state whether a rate-limit rejection *should* consume a scheduler attempt.
A cheap, in-scope mitigation: have `SesSendRateLimiter` be the only thing that fails, and let the
scheduler's attempt counter skip `EmailTransportRateLimitedException` — but that is a real design call
and belongs in the AC, not in a dev's judgement at 5pm.

---

## Minor / nits

- **N1 — arch-test doc trap AC6 doesn't warn about.** `EmailTransportArchitectureTest:68` lists
  `"SmtpHealthIndicator"` in `SMTP_ONLY_CLASS_NAMES`, and rule 2 matches `\bSmtpHealthIndicator\b`
  against **whole file content, javadoc included**, for every main source file outside
  `infrastructure/email/smtp/`. AC1 instructs the dev to *"mirror `SmtpHealthIndicator`'s
  `AtomicReference<Cached>` + double-checked-locking pattern
  (`infrastructure/email/smtp/SmtpHealthIndicator.java`) exactly"* — writing that sentence into
  `SesHealthIndicator`'s javadoc, the natural thing to do, fails rule 2. AC6's "no rule changes
  needed" is correct but should add: *the new class must not name `SmtpHealthIndicator` in code or
  comments.*
- **N2 — stale version citation.** AC1 says the accessors were "verified against `sesv2-2.54.7.jar`".
  `pom.xml`'s `dependencyManagement` imports `software.amazon.awssdk:bom:**2.54.13**`. The accessors
  exist in both, so the conclusion holds — the citation is just stale and will mislead the next reader.
- **N3 — AC3's dependency list is incomplete.** The "Given" clause says `SesSendRateLimiter`
  constructor-injects `SesProperties`; the counter requirement means it also needs `MeterRegistry`.
  Trivial, but B3's `TransportWiringTest` breakage comes directly from this omission.
- **N4 — startup-order race on a misconfigured rate.** `SesPropertiesValidator` is a separate
  `@Component` with `@PostConstruct validate()` (`SesPropertiesValidator.java:42,61-62`) and owns the
  friendly `app.ses.max-send-rate-per-second must be positive` message.
  `RateLimiterConfig.custom().limitForPeriod(n)` throws its own `IllegalArgumentException` for `n <= 0`.
  Bean init order between the two is not pinned, so a misconfigured deployment may get the resilience4j
  message instead of the actionable one. Build the `RateLimiter` lazily on first use, or add
  `@DependsOn("sesPropertiesValidator")`.
- **N5 — AC6's rate-limiter test is timing-flaky as specified.** Two issues: (a) the wall-clock
  assertion is inherently CI-sensitive; (b) "N+1 acquires within one window" straddles
  `AtomicRateLimiter`'s refresh boundary — with `limitForPeriod=10, limitRefreshPeriod=1s` the 11th
  acquire legitimately succeeds if the window rolls mid-test. Construct the limiter under test with a
  deliberately awkward config (e.g. `limitForPeriod=2, limitRefreshPeriod=60s`) so the rejection is
  deterministic, and assert an upper bound (`< 200ms`) rather than a tight one. §7.2 item 23's intent
  — prove "fails fast", not "blocks" — survives both changes.
- **N6 — `transport=log` has no health contributor.** After AC2, `management.endpoint.health.group.notification`
  has zero members in any `transport=log` environment (`application.yaml:154` base default,
  `application-test.yaml:134`), so `/manage/health/notification` returns 404 there. Today the base
  yaml's SMTP providers happen to keep the group populated. Nothing consumes the endpoint — I grepped
  `docs/`, `deploy/`, `.github/` and `src/test` and found no reference — so this is informational, but
  the source doc's *"whichever transport an environment runs, its health is visible"* is not literally
  satisfied for `log`. Worth one line in Dev Notes saying that is intentional.
- **N7 — metric blind spot in AC5.** Wrapping only `outboundEmailSender.send(request)` means the
  `EmailTransportPermanentException` `MailService` itself raises for a malformed payload
  (`MailService.java:62-65`, added by ses-1.2's code review) never appears as `outcome=failure`. If
  the metric is meant to answer "what happened to this email", that class of failure should be in it.
- **N8 — nothing alerts on the new signals.** No rule in `deploy/lgtm/alerts.yml` or
  `grafana-alerts.yml` references mail at all. `mail.send{outcome=failure}` and
  `mail.ses.rate_limiter.rejected` will be scrapeable and unwatched. Out of scope for this story, but
  §10's *"prod's health becomes observable before it serves real traffic"* is only half-delivered
  without a follow-up item.

---

## Verified accurate — no action (recorded so these are not re-audited)

These story claims were checked against HEAD or the resolved jars and are **correct**:

- `SimpleRetryPolicy(int, Map<Class<? extends Throwable>, Boolean>, boolean, boolean)` exists in
  `spring-retry-2.0.13` (`javap`).
- `AllNestedConditions(ConfigurationPhase)` exists in `spring-boot-autoconfigure-3.5.16` (`javap`), and
  `@ConditionalOnProperty` is indeed not `@Repeatable`.
- No `AllNestedConditions` / `AnyNestedCondition` / `@Conditional(` usage anywhere in `src/main/java`
  today — the pattern genuinely has no house precedent, as AC2 states.
- `MailManager.sendEmailSync` rewraps *every* exception in `new RuntimeException(...)` before rethrow
  (`MailManager.java:78-84`), so AC4's `traverseCauses=true` is genuinely required, and
  `BinaryExceptionClassifier` with `defaultValue=true` does walk that one level and return `false`.
- AC4's claimed end state is right: `isRetryable` finds no `EmailTransportPermanentException` in the
  bounded 2-level walk, so the envelope persists as `FAILED, retry=true` and is re-driven by
  `EmailRetryScheduler`, exactly as described.
- `MailManagerResilienceTest.setUp()` really does build its own
  `RetryTemplate.builder().maxAttempts(3).fixedBackoff(Duration.ofMillis(10)).build()` and never
  touches `ComponentConfig`'s bean — AC6's "would pass vacuously" warning is correct and valuable.
- `EmailTransportTransientException` / `EmailTransportPermanentException` are plain non-`final`
  `public class` — the new subclass compiles without touching the parent.
- `TransportWiringTest`'s `smtpHealthIndicator_activatesOnlyWhenAProviderIsConfigured` javadoc does
  name `SesHealthIndicator`'s non-existence as the reason the exclusivity story was deferred.
- `GetAccountResponse.sendingEnabled()` / `productionAccessEnabled()` / `sendQuota()` and
  `SendQuota.max24HourSend()` / `maxSendRate()` / `sentLast24Hours()` all exist (the *version* cited is
  stale — see N2 — but the accessors are real).
- `SesConfig`'s `SesV2Client` bean carries `apiCallTimeout(5s)` / `apiCallAttemptTimeout(3s)`, so
  `SesHealthIndicator` inherits the bound with no extra wiring.
- `app.ses.max-send-rate-per-second` is fully wired (`SesProperties:27` default 10,
  `SesPropertiesValidator` positive check + `>= 10` sandbox WARN, `docker-compose.yml:99`,
  `.env.example:81`) — and `.env.example:77-80` **does** document the per-node division
  ("that ceiling is shared, so divide by the number of app nodes"), so the References claim holds.
- `resilience4j-ratelimiter:2.2.0` is present on the compile classpath transitively; no `pom.xml`
  change is needed.
- Metric-name style: `mail.ses.rate_limiter.rejected`'s underscore-inside-segment matches existing
  house precedent (`video.orphan_asset.purge_failed`, `video.error.count`) — not a defect.
- Package placement needs no arch-rule changes: `infrastructure/ses` already owns the SES SDK carve-out
  (rule 3), `infrastructure.email` is transport-neutral, and rule 4 only bans SES-SDK / mail-API
  package tokens under `platform/**`, which `MailMetrics` and the new exception do not carry.
- No new Spring-context-forking test is introduced; `ApplicationContextRunner` contexts do not enter
  the TestContext cache, so `assert-context-count.sh`'s `CEILING=39` is unaffected.
- Group member naming is right: `SesHealthIndicator` → `ses`, `SmtpHealthIndicator` → `smtp`, derived
  from the bean name with the `HealthIndicator` suffix stripped.

---

## Suggested AC edits (condensed)

1. **AC4** — replace the `SimpleRetryPolicy` construction with the builder-native
   `.maxAttempts(3).notRetryOn(EmailTransportRateLimitedException.class).traversingCauses().fixedBackoff(Duration.ofSeconds(1))`,
   and add the M6 duplicate-send consequence + M7 circuit-breaker note as recorded decisions.
2. **AC3** — add `MeterRegistry` to the dependency list; require the class to translate *any* exception
   out of the permit acquisition, not just `RequestNotPermitted` (or use `acquirePermission()` directly).
3. **AC1** — decide and state the sandbox/`enforcementStatus` mapping; require null-safe reads of
   `sendingEnabled()` / `sendQuota()`; drop the false "breaks the endpoint for every other indicator"
   rationale; forbid naming `SmtpHealthIndicator` in the new file's comments (N1).
4. **AC2** — restate the premise as a live prod defect (base `application.yaml:158-170` + prod's
   `transport: ses`), and make AC6's exclusivity test use that exact shape.
5. **AC5** — add the `transport == null` fallback and widen the catch to `Throwable`-and-rethrow.
6. **AC6 / Task list** — add `SesAddressValidationTest` and both broken `TransportWiringTest` cases to
   the modified-files list; de-flake the rate-limiter test per N5.
