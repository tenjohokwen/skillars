# Story Ses-1.1: Introduce the Outbound Email Port

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Revision 1, after story-review.md #1 (2026-09-11): 4 blockers, 6 high, 9 medium, 3 config, 2
     deviation, 6 low findings, all verified against HEAD and the resolved AWS SDK jars, all
     applied. No false positives found in that review — every spot-checked claim held up. -->
<!-- Revision 2, after story-review.md #2 (2026-09-11): test-rigor and operational-clarity
     findings applied (see Dev Notes' closing section for what was accepted vs. downgraded/
     rejected, including one finding whose own suggested fix was factually backwards —
     verified against source, not taken on faith). -->

## Story

As the platform's email transport owner,
I want a transport-neutral `OutboundEmailSender` port in `infrastructure/email`, with the existing AWS SES adapter rebuilt behind it and the three registration listeners calling it directly,
so that "who sends this email and how" becomes one pluggable seam instead of a `SesEmailService` interface with one real implementation, a no-op, and a dev-only bridge into SMTP — and so later phases (SMTP behind the same port, health/rate-limiting, registration durability, the SES cutover, SMTP deletion) each land as an independent, low-risk follow-on.

This is **Phase 1 of 7** in `requirements/ses-email-consolidation.md`. It changes nothing about SMTP, `MailManager`, `MailService`, or booking/session-pack/alert email — those stay exactly as they are today. It only rebuilds the SES side (Stack B, §2.1) behind a neutral port and points the six registration/OTP emails at it. Per the source doc: *"SES behaves identically for the six registration emails. SMTP untouched... Risk: low — mechanical."*

## Acceptance Criteria

**AC1 — The port exists and is transport-neutral**
- Given the new `com.softropic.skillars.infrastructure.email` package
- Then it contains `OutboundEmailSender` (one method, `OutboundEmailResult send(OutboundEmailRequest request)`), `OutboundEmailRequest` (record: `toAddress`, `subject`, `htmlBody`, `textBody`, `correlationId`) and `OutboundEmailResult` (record: `messageId`)
- And `OutboundEmailRequest`'s compact constructor throws `IllegalArgumentException` when `toAddress`, `subject`, or `correlationId` is null/blank, and when **both** `htmlBody` and `textBody` are null/blank — html-only and text-only requests are both valid
- And the record's constructor is the single validation point for **presence** (null/blank) only. Transport-specific **format** validation — e.g. AC4's recipient address check — is a separate concern that belongs in the adapter, per §6.3's argument that format rules must live with the transport and disappear with it. This is not a contradiction: presence is checked once, centrally; shape is checked per-transport, where the transport's own rules apply.
- [Source: requirements/ses-email-consolidation.md#3.1]

**AC2 — Exception taxonomy is transport-neutral**
- Given `EmailTransportException` (abstract, extends `RuntimeException`), `EmailTransportTransientException` and `EmailTransportPermanentException`
- Then no code outside `infrastructure.ses` throws or catches an SES-SDK-specific or SMTP-specific exception type for an email send failure
- [Source: requirements/ses-email-consolidation.md#3.2]

**AC3 — Transport selector exists; a present-and-unrecognised value aborts startup; an absent value falls back to a safe base default**
- Given `EmailTransport` (enum: `SES`, `SMTP`, `LOG`), `EmailTransportProperties` (`@ConfigurationProperties("app.email")`, holding `transport`), and `EmailTransportPropertyValidator` (an `EnvironmentPostProcessor`, same shape as the `SesEnabledPropertyValidator` it replaces)
- Given `application.yaml` sets a base default of `app.email.transport: log` — **not** "no default" as a literal reading of the source doc's §4.5 table would suggest. Reason: `EnvironmentPostProcessor`s registered in `META-INF/spring.factories` run for **every** `SpringApplication`, including the ones Spring Boot's test infrastructure builds for `@SpringBootTest` and `@WebMvcTest` slices. Several existing test classes activate **no profile at all** and therefore resolve only `application.yaml` — `AdminLoginResourceTest` (`@WebMvcTest`), `RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT` (both plain `@SpringBootTest`, no `@ActiveProfiles`). A repo-wide check confirms only `AbstractIntegrationTest` and `MessageModerationSweeperIT` declare `@ActiveProfiles` at all. A true "unset ⇒ abort" design would fail every one of those contexts, and `mvn spring-boot:run` with no profile, before a single bean is created — the build goes red on the first Surefire class, not from a real misconfiguration
- Each of `application-dev.yaml`, `application-uat.yaml`, `application-prod.yaml`, and `src/test/resources/application-test.yaml` **still explicitly states its own value** (§4.5's actual intent — "every profile states its own" — is about explicitness, not about the base file being unparseable): `log` for dev/uat/test (redundant with the base default, stated anyway), `ses` for prod. A dedicated test (see Task 9) asserts all four files declare the key explicitly, so the intent doesn't silently erode even though the base default makes it technically optional
- When `app.email.transport` resolves to a **present** value that is neither `ses`, `smtp`, nor `log` (case-insensitive, matching `@ConditionalOnProperty` semantics) — **including `smtp` itself in this phase**, see below — the application aborts startup with a one-line `IllegalStateException` naming the property and the offending value, before any bean is created
- **`smtp` is syntactically part of the enum but is rejected by the validator in this story**, with a message distinct from the generic unrecognised-value one: `"app.email.transport=smtp is not implemented until Phase 2 — use 'log' or 'ses'"`. Reason: `SmtpEmailSender` has no bean until Phase 2 (Dev Notes), so accepting `smtp` now would let it pass the fail-fast gate and then die in a `NoSuchBeanDefinitionException` when the registration listeners fail to inject `OutboundEmailSender` — precisely the failure mode `SesEnabledPropertyValidator`'s own javadoc says it exists to eliminate (*"anything this validator accepts also wires the beans, and anything it rejects would have left them unwired"*). `smtp` is also the single most likely value a developer reaches for once Task 8 deletes `DevSesEmailService` — it is what "I want a real dev email" looks like to someone who hasn't read this story. The enum keeps all three values (Phase 2 only needs to add a bean and flip the validator's accepted set, not touch the selector type)
- And `EmailTransportPropertyValidator` is registered in `src/main/resources/META-INF/spring.factories`, **replacing** the `SesEnabledPropertyValidator` line
- And the validator's javadoc states its `EnvironmentPostProcessor` ordering assumption explicitly: it implements no `Ordered`, sorting to `LOWEST_PRECEDENCE`, which is *required* — it must run after `ConfigDataEnvironmentPostProcessor` (`HIGHEST_PRECEDENCE + 10`) has loaded the profile YAMLs, or it would run before any YAML is loaded and reject the base default on every boot. Mirror `SesEnabledPropertyValidator`'s existing behavior exactly; do not add `@Order(HIGHEST_PRECEDENCE)`
- [Source: requirements/ses-email-consolidation.md#4.1, #6.1]

**AC4 — SES adapter rebuilt behind the port**
- Given `SesEmailService` (interface) and `SesEmailServiceImpl` are gone, folded into a new `SesEmailSender implements OutboundEmailSender`, gated on `app.email.transport=ses` (not `app.ses.enabled`)
- Then `SesEmailSender.send(...)` builds an SESv2 `simple` content with an `html` part **when `request.htmlBody()` is present** and, **independently**, a `text` part **when `request.textBody()` is present** — at least one is always present per AC1, but which one(s) is not fixed; a text-only request (the `EmailTemplate.NONE`/§6.2 shape Phase 2 introduces) must not produce a null `html` part. Sets `configurationSetName` (when `app.ses.configuration-set` is set), `replyToAddresses` (when `app.ses.reply-to-address` is set — the **raw**, unparsed string, so a display-name form is preserved on the wire), and stamps `request.correlationId()` as a message tag; returns `OutboundEmailResult(response.messageId())`
- And before calling the SDK, it validates `request.toAddress()` via `EmailAddressParser` (see AC8 for the parser's exact contract) and throws `EmailTransportPermanentException` on a malformed address or an address string that parses to more than one recipient, with **zero** SDK interaction for that case. The **raw** `toAddress` string is what's sent to `.toAddresses(...)` — the parser is used for validation only, never to rewrite the outgoing value
- And it catches `SdkException` (the common supertype of both `SesV2Exception` — the service-side hierarchy — and `SdkClientException`/`ApiCallTimeoutException` — the client-side hierarchy, which are **siblings**, not subtypes, of `SesV2Exception`; verified against the resolved `sdk-core-2.54.13.jar`). Catching only `SesV2Exception`, as a literal transcription of "on an SesV2Exception, classify..." would produce, misses every connect/read timeout and DNS failure: `apiCallTimeout`/`apiCallAttemptTimeout` (AC6) make `ApiCallTimeoutException` routine, not theoretical, and an uncaught SDK exception here propagates out of a `@TransactionalEventListener(AFTER_COMMIT)` method — this codebase configures no `ErrorHandler` on the event multicaster, so it would surface as a 500 on an already-committed registration. It classifies via `SesErrorClassifier` and throws the matching neutral exception — never leaks `SdkException`, `SesV2Exception`, or the old `SesException`
- And `@Profile("!dev")` is dropped — transport selection is now purely `app.email.transport`, with no profile dimension
- [Source: requirements/ses-email-consolidation.md#4.2, #6.3]

**AC5 — Error classification matches the documented table (corrected class names)**
- Given `SesErrorClassifier` (new, in `infrastructure.ses`), accepting any `SdkException` per AC4
- Then it classifies exactly per this table: `TooManyRequestsException`/`LimitExceededException` → Transient; `SendingPausedException` → Transient; HTTP 5xx / `SdkClientException` / `ApiCallTimeoutException` → Transient; **`MessageRejectedException`** (not `MessageRejected` — that is the SES v1 name; verified against `sesv2-2.54.13.jar`, which has no class of that name) → Permanent; `MailFromDomainNotVerifiedException`/`AccountSuspendedException` → **Transient** (deliberately — see the javadoc requirement below); `BadRequestException` / other 4xx → Permanent; an unrecognised SDK exception (including any `SdkClientException` subtype not named above) → **Transient** (defaulting to Permanent silently drops mail; defaulting to Transient costs retries, which is the safe direction)
- And the class's javadoc states, verbatim in spirit: *"Transient on purpose — the message is fine, the account is blocked; envelopes stay retryable so the scheduler drains them on recovery. Only a block outlasting the envelope deadline loses mail."* (this envelope/scheduler language is a Phase 4 concept — for Phase 1 the classifier exists and is tested on its own, since `MailManager` doesn't consume it yet)
- And the javadoc **separately** documents the unknown-exception default (Transient) with the same asymmetry reasoning given above — not just the account-blocked case — so a future "improvement" to default Permanent instead doesn't slip past a reviewer who only reads the account-blocked paragraph
- And `SesEmailSender` logs any exception that reaches the unknown/default branch at **WARN** (naming the exception's class) before wrapping it, so a new SDK exception type introduced by an AWS SDK upgrade is visible to whoever's watching logs, not just silently retried
- [Source: requirements/ses-email-consolidation.md#3.2]

**AC6 — SES client, credentials, and timeouts**
- Given `SesConfig`
- Then it builds an explicit `AwsCredentialsProvider`: `StaticCredentialsProvider`/`AwsBasicCredentials` when `app.ses.access-key` and `app.ses.secret-key` are both non-blank, else `DefaultCredentialsProvider` — mirror `BlobstoreConfig.credentialsProvider` (`BlobstoreConfig.java:40-56`) exactly, don't reinvent
- And `ClientOverrideConfiguration` sets **`retryStrategy(AwsRetryStrategy.doNotRetry())`** — **not** `retryPolicy(RetryPolicy.builder().numRetries(0).build())`. `RetryPolicy` and `ClientOverrideConfiguration.Builder.retryPolicy(RetryPolicy)` are both `@Deprecated` in the pinned SDK (verified against `sdk-core-2.54.13.jar`'s bytecode); the build won't fail on the warning (no `-Werror`), but a story explicitly building new code shouldn't ship a deprecated call it could avoid for free. `AwsRetryStrategy.doNotRetry()` is confirmed present in the same jar and is the direct replacement
- And `apiCallTimeout = 5s`, `apiCallAttemptTimeout = 3s` — note the 2-second gap between them is not redundant even with zero retries: it covers marshalling, request signing, and credential resolution time that sits outside the single attempt's own budget
- And an optional `endpointOverride` is applied when `app.ses.endpoint-url` is set (needed for the WireMock-backed IT in this story — see Testing)
- And the `SesV2Client` bean is gated on `app.email.transport=ses`, not `app.ses.enabled`
- **Accepted, narrow behaviour change for this phase only, stated here rather than left implicit:** §6.4's reasoning for zero SDK retries is *"the application's two layers [`EmailRetryScheduler` × `RetryTemplate`, both inside `MailManager`] already implement the retry semantics we want"* — true once `MailManager` owns the call, which for booking/session-pack mail is already the case and for registration/OTP mail is a **Phase 4** change (D3). Until Phase 4, a transient SES failure on one of the six registration/OTP emails gets **zero** retries (today's `SesEmailServiceImpl` gets the SDK's un-configured default retry policy, so this is a real narrowing, not a wash). Accepted because: (a) it is symmetric with `EmailTransportTransientException` being logged and swallowed either way in this phase (AC11) — no scheduler exists yet to benefit from more SDK-level retries; (b) every affected flow has a user-facing, self-service recovery path — `POST /resend-otp` (`CoachRegistrationResource`, `ParentRegistrationResource`, `PlayerRegistrationResource`, per §6.16b) and the resend-verification endpoints; (c) it closes automatically in Phase 4, not left open-ended. Do not "fix" this by re-introducing SDK-level retries as a local deviation — that reintroduces the retry-multiplication problem §6.4 exists to prevent, once `MailManager` also starts calling this same client in Phase 2+.
- [Source: requirements/ses-email-consolidation.md#4.2, #6.4, #6.6]

**AC7 — SesProperties expanded and validated at startup, via a conditionally-created validator bean**
- Given `SesProperties` gains `accessKey`, `secretKey`, `configurationSet`, `endpointUrl`, `maxSendRatePerSecond` (default 10), `replyToAddress`, and **loses** `enabled`
- And a separate `SesPropertiesValidator` component — **not** a `@PostConstruct` method on `SesProperties` itself — performs the validation below, gated `@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")`. Reason: `SesProperties` is registered via `@EnableConfigurationProperties(SesProperties.class)` (`SesConfig.java:10`), and `@ConditionalOnProperty` is silently **ignored** on a `@ConfigurationProperties` class registered that way — the bean is created unconditionally regardless of any annotation placed on the class. A `@PostConstruct` there would therefore fire under `transport=log` too. A dedicated `@Component` constructor-injecting `SesProperties` is conditional by construction, keeps `SesProperties` a plain binding POJO, and makes "the checks don't fire under `transport=log`" assertable as **bean absence** via `ApplicationContextRunner` rather than as behaviour requiring a full context
- Then, only when that bean exists (i.e. only while `app.email.transport=ses`), application startup fails with a message naming the property when: `fromAddress` is blank or not parseable by `EmailAddressParser`; `replyToAddress` is set but not parseable; `configurationSet` is set but doesn't match `^[a-zA-Z0-9_-]{1,64}$`; `maxSendRatePerSecond` is not positive
- And none of these checks fire while `transport` is `log` (or, later, `smtp`) — a dev/uat box with a blank `fromAddress` must still boot
- **`SesPropertiesValidator`'s javadoc states explicitly what it does and doesn't cover**: it validates the *semantics* of already-bound values (blank, unparseable, non-positive). A structurally malformed value of the wrong type — e.g. `max-send-rate-per-second: not-a-number` against a numeric field — never reaches this validator at all; Spring's own `@ConfigurationProperties` binder rejects it first, at context-refresh time, with its own message naming the offending property and the conversion failure. That binder-level failure is standard Spring Boot behaviour for every `@ConfigurationProperties` class in this codebase (not something introduced or fixable by this story), already names the property, and is not this validator's job to duplicate or intercept
- **Separately, `SesPropertiesValidator` logs a startup WARN — not a failure — when `maxSendRatePerSecond >= 10` while `transport=ses`**: *"app.ses.max-send-rate-per-second={value} assumes a production (non-sandboxed) SES account; a sandboxed account is capped at 1/s and will throttle on the first burst — see §6.5."* The property's default (10) is the production-access assumption from §6.5; nothing in this story can detect *from the app's side* whether an account is sandboxed (that's `SesHealthIndicator`'s `GetAccount` check, Phase 3), so a WARN — cheap, no new dependency, no false sense of enforcement — is the right level for Phase 1. This is the most likely first-day surprise once uat flips to `ses` in Phase 5 on a sandboxed account with the default left untouched
- [Source: requirements/ses-email-consolidation.md#4.2 row for SesProperties, #6.5, #6.8]

**AC8 — Shared address parser: validates, rejects multi-address input, and never rewrites the value it's asked to check**
- Given `EmailAddressParser` in `infrastructure.email`, built on `jakarta.mail.internet.InternetAddress`
- Then it accepts both `user@domain` and `Display Name <user@domain>` (RFC 5322 forms) and returns the bare address for validation purposes
- And it **actively validates** — `new InternetAddress(raw).validate()` (or `InternetAddress.parse(raw, true)`), not the bare `new InternetAddress(raw)` constructor alone. The bare constructor does **not** throw for garbage like `"not-an-address"`; `.validate()` (or strict `parse`) is what performs RFC 822 syntax checking and throws `AddressException`. Getting this wrong makes AC8's own malformed-input test pass vacuously
- And a value that parses to **more than one address** (`InternetAddress.parse` treats a comma-separated string as a list — `"victim@x.com, attacker@y.com"` parses to two) is **rejected**, not silently narrowed to the first — a single request field must not smuggle a second recipient past validation
- And it is the **one** implementation used by AC7's `fromAddress`/`replyToAddress` validation and AC4's recipient check — no second regex anywhere
- [Source: requirements/ses-email-consolidation.md#4.2 row for EmailAddressParser, #6.3]

**AC9 — Logging transport replaces the no-op; degrades gracefully, never overwrites, never corrupts a filename**
- Given `NoOpSesEmailService` is deleted and `LoggingEmailSender` (in `infrastructure.email.log`, gated on `app.email.transport=log`) exists
- Then it logs recipient + subject + correlation id and **never throws**
- And when `app.email.log.outbox-dir` is configured to a **non-blank** value, it writes the rendered body to disk: `htmlBody` when present, to `<dir>/<correlationId>.html`; else `textBody`, to `<dir>/<correlationId>.txt`. (An unconditional `.html` write, as a literal reading of "writes the rendered HTML body" would produce, breaks on the text-only requests AC1 explicitly makes legal — §6.2's `EmailTemplate.NONE` shape, which Phase 2's `EmailContentRenderer` starts producing.) A **blank** `outbox-dir` value is treated identically to **unset** — both mean disabled; this matters because `application-test.yaml` sets it to `""` specifically to override `application-dev.yaml`'s non-blank value (see Task 9/H4)
- And `correlationId` is sanitised before it becomes a filename — `LoggingEmailSender` restricts it to `[A-Za-z0-9._-]` and truncates to a sane length (e.g. 200 chars) before building the path, rather than trusting every current and future caller to hand it a safe value. `OutboundEmailSender` is a public port; today's only caller passes `UUID.randomUUID().toString()` (AC11), but Phase 4 replaces that with a `sendId` minted by a component this story doesn't touch, and the guard belongs with the filename, not with each caller
- And the outbox directory is created at startup (not first send). A failure to create it at startup logs a **WARN and disables file-writing for the process** — it does **not** abort context startup. "Surfaces immediately" means the operator sees a WARN at boot instead of discovering it silently on the first registration; it does not mean a misconfigured `outbox-dir` should be able to fail a boot, which would contradict this AC's own "never throws" requirement two bullets up
- And when the target file already exists (a retry reusing the same correlation id), it does **not** overwrite: open with `StandardOpenOption.CREATE_NEW`, and on `FileAlreadyExistsException` retry as `-2`, `-3`, … up to a bounded number of attempts (e.g. 100) before falling through to log-only — the `CREATE_NEW` failure is the concurrency check, no separate exists-check needed, but the loop must not spin forever on a persistently-colliding or permission-denied path. **Exhausting the bound is not silent**: log at WARN with the correlation id and the attempt count, e.g. `"Failed to write outbox file for correlationId={id} after 100 collision attempts; degraded to log-only"` — a dev relying on `outbox-dir` for template work should see why a file didn't appear, not just fail to find it
- And a write-time `IOException` other than `FileAlreadyExistsException` (for example `NoSuchFileException` if the directory is removed after startup but before a send — a real TOCTOU window since creation and writing happen at different times) is caught, **logged at WARN with the underlying exception's message**, and degrades to log-only for that send, consistent with "never throws"
- **Explicitly out of scope for this story**: a metric/counter on the degrade path (e.g. an `email.log.file_write.degraded` counter). Transport-tagged metrics are Phase 3's job (§5 Phase 3 item 3 — *"transport-tagged metrics (`mail.send` with an `outcome` + `transport` tag)"*); inventing a separate, differently-shaped metric here ahead of that would duplicate or conflict with it. The WARN-level logging above is the Phase 1-appropriate signal; don't add a metrics dependency to reach for it
- And the "log a warning once" requirement for an unwritable directory uses an `AtomicBoolean` (the sender is a singleton invoked from multiple listener threads) and warns again if the directory transitions from writable back to unwritable, not just once per process lifetime
- [Source: requirements/ses-email-consolidation.md#4.1 row for log/LoggingEmailSender]

**AC9b — `app.email.log.outbox-dir` binding location, and `OutboundEmailResult.messageId` for the logging transport**
- Given `EmailTransportProperties` (`@ConfigurationProperties("app.email")`) holds only `transport`
- Then `app.email.log.outbox-dir` binds to a named location — either a nested `Log` record on `EmailTransportProperties`, or a separate `@ConfigurationProperties("app.email.log")` properties class gated on `transport=log` — not left to Spring's default "ignore unknown fields" behaviour, which would silently work today and silently break if anyone ever sets `ignoreUnknownFields = false`
- And `LoggingEmailSender.send(...)` returns `OutboundEmailResult("log:" + correlationId)`, never a null `messageId` — Phase 4 persists this value onto `EnvelopeEntity`, and a null nobody decided on becomes an unexplainable null column later. `log:<correlationId>` is self-documenting and traceable back to the written file
- [Source: requirements/ses-email-consolidation.md#4.1 row for log/LoggingEmailSender]

**AC10 — Wiring is total and unambiguous for the transports this phase implements**
- Given `app.email.transport = ses | log` (this phase's validator rejects `smtp` — AC3)
- Then exactly one `OutboundEmailSender` bean exists for `ses` (a `SesEmailSender`, with `SesV2Client` also present) and for `log` (a `LoggingEmailSender`, with **no** `SesV2Client` present)
- [Source: requirements/ses-email-consolidation.md#6.1]

**AC11 — Registration listeners call the port directly, with construction and send both inside the failure boundary**
- Given `CoachRegistrationEmailListener`, `ParentRegistrationEmailListener`, `PlayerRegistrationEmailListener`
- Then each replaces its `SesEmailService` field with `OutboundEmailSender`, and **inside** the existing `try` block (not before it) builds an `OutboundEmailRequest` (htmlBody = the rendered Thymeleaf template, textBody = null, correlationId = `UUID.randomUUID().toString()` — see Dev Notes for why) and calls `send(...)`
- And the catch clause becomes `catch (EmailTransportException | IllegalArgumentException ex)`, not just `catch (EmailTransportException ex)`. Reason: `OutboundEmailRequest`'s compact constructor (AC1) throws `IllegalArgumentException` on a blank `toAddress`/`subject`/`correlationId` — a case that cannot happen today (`SesEmailServiceImpl.send` accepts nulls and lets SES itself reject them, which the existing `catch (SesException)` already handles). Moving construction inside the try without widening the catch converts a today-impossible scenario into an uncaught `IllegalArgumentException` escaping an `@TransactionalEventListener(AFTER_COMMIT)` method — this codebase configures no event-multicaster `ErrorHandler`, so it would 500 an already-committed registration, exactly the failure this try/catch exists to prevent
- And each catch block keeps its **exact existing log wording**, per file — do not normalise them:
  - `CoachRegistrationEmailListener`: *"Failed to send verification email — registration may be orphaned..."* / *"Failed to send OTP email — user is EMAIL_VERIFIED but OTP unreachable..."* (no role-name prefix)
  - `ParentRegistrationEmailListener`: same two messages, each with **"parent"** inserted (*"Failed to send parent verification email..."*, *"...parent OTP email..."*)
  - `PlayerRegistrationEmailListener.onVerificationEmail`: additionally has a `log.atInfo().addKeyValue(...)` structured-logging block (first name, lang key) **inside** the try, immediately before the send call — preserve it verbatim, in place, right before the (now-rebuilt) send call:
    ```java
    log.atInfo()
       .addKeyValue("First name", event.firstName())
       .addKeyValue("Language used", event.langKey())
       .setMessage("Handling PlayerVerificationEmailEvent. About to handover to email send service").log();

    outboundEmailSender.send(request); // was: sesEmailService.send(event.toAddress(), subject, html);
    ```
    It is easy to lose in a mechanical field/catch rewrite because the other two listeners don't have it, and it only appears on `onVerificationEmail`, not `onOtpEmail`
- And the calls remain direct (`@TransactionalEventListener(AFTER_COMMIT)` → port), **not** routed through `MailManager`/outbox yet — this story does not add durability; that is Phase 4 (D3, tracked, not a regression)
- [Source: requirements/ses-email-consolidation.md#5 Phase 1 item 4]

**AC12 — Every environment reproduces today's behaviour**
- Given `app.email.transport` is set explicitly per profile, overriding `application.yaml`'s `log` base default (AC3)
- Then: `application-prod.yaml` → `ses` (prod is already fully on SES today); `application-dev.yaml`, `application-uat.yaml`, `src/test/resources/application-test.yaml` → `log` (today they all resolve to `NoOpSesEmailService` — absent or `false` `app.ses.enabled` — so `log` is the faithful successor, not `smtp`, which this phase doesn't implement anyway; SMTP for booking mail is untouched and unaffected by this property)
- And `app.ses.from-address` is no longer a literal in any committed YAML: `${APP_SES_FROM_ADDRESS:dev@localhost}` in dev, `${APP_SES_FROM_ADDRESS:}` in uat and prod (see Dev Notes for why prod also uses the `:` empty-default form, not a bare reference)
- And `app.ses.enabled` is removed from all three profile files
- And this AC deliberately narrows §6.11 of the source doc, which says dev keeps Gmail SMTP (via `transport=smtp`) until Phase 5. This story cannot do that — `SmtpEmailSender` doesn't exist until Phase 2 — so dev moves to `log` one phase earlier than the doc's own table. The dev-only escape hatch this removes (`app.ses.enabled=true` routing through `DevSesEmailService` into real SMTP, Task 8) was already inert before this story: `application-dev.yaml` pins `enabled: false` today specifically because `true` could never work on that profile (see the comment this story deletes). The loss is therefore narrow — no route to a *real* dev email exists until Phase 2's `SmtpEmailSender` ships, where none reliably existed before either — and `outbox-dir` (AC9) gives template work a better artifact than a mailbox in the meantime
- [Source: requirements/ses-email-consolidation.md#4.5, #6.7, #6.11]

**AC13 — SMTP and everything else is untouched**
- Given the diff for this story
- Then it touches nothing under `platform/notification/service/MailService.java`, `MailManager.java`, `SenderProvider.java`, `platform/notification/infrastructure/MailSenderProvider.java`, `platform/notification/health/SmtpHealthIndicator.java`, `email.providerConfigs`, `spring.mail.*`, or any `mails/*.html` template
- And the sole exception inside `platform/` is the three registration listeners (AC11) and the deletion of `platform/notification/infrastructure/DevSesEmailService.java` (see Dev Notes — pulled forward from Phase 2 out of necessity, not scope creep)
- [Source: requirements/ses-email-consolidation.md#4.3 "Not touched anywhere else in this module"]

## Tasks / Subtasks

- [x] Task 1 — Port and exception taxonomy (AC: #1, #2)
  - [x] Create `infrastructure/email/OutboundEmailSender.java`, `OutboundEmailRequest.java`, `OutboundEmailResult.java` per §3.1
  - [x] Create `infrastructure/email/EmailTransportException.java` (+ `EmailTransportTransientException`, `EmailTransportPermanentException`)
  - [x] Unit test: `OutboundEmailRequestValidationTest` — every null/blank combination named in AC1, plus html-only and text-only both accepted

- [x] Task 2 — Transport selector + fail-fast validator (AC: #3)
  - [x] Create `infrastructure/email/EmailTransport.java` (enum: `SES`, `SMTP`, `LOG`), `EmailTransportProperties.java`
  - [x] Create `infrastructure/email/EmailTransportPropertyValidator.java` (mirror `SesEnabledPropertyValidator`'s structure; javadoc must state both the ordering assumption — `LOWEST_PRECEDENCE`, must run after `ConfigDataEnvironmentPostProcessor` — and the deliberate rejection of a present `smtp` value in this phase, with the rationale from AC3)
  - [x] `application.yaml`: add `app.email.transport: log` as the base default (see AC3/Dev Notes — this is a deliberate deviation from the source doc's literal "no default" wording)
  - [x] Update `META-INF/spring.factories`: remove the `SesEnabledPropertyValidator` line, add `EmailTransportPropertyValidator`
  - [x] Delete `infrastructure/ses/SesEnabledPropertyValidator.java` and `SesEnabledPropertyValidatorTest.java`
  - [x] Unit test: `EmailTransportPropertyValidatorTest` — unset allowed (falls through to base default); `ses`/`log` case-insensitively allowed; `smtp` rejected with the Phase-2 message; anything else rejected with the value in the message

- [x] Task 3 — `EmailAddressParser` (AC: #8)
  - [x] Create `infrastructure/email/EmailAddressParser.java` — use `jakarta.mail.internet.InternetAddress` with `.validate()` (or strict `InternetAddress.parse(raw, true)`), not the bare constructor alone (AC8 explains why); already on the classpath via `spring-boot-starter-mail`, still present until Phase 6
  - [x] Unit tests: both accepted shapes, a malformed input (`"not-an-address"`), and a comma-separated multi-address input (`"a@b.com, c@d.com"`). **At least one malformed-input case must specifically be a value the *bare*, non-strict `InternetAddress(String)` constructor would accept without throwing** (e.g. a local-part containing an unescaped space or another RFC-822 violation that isn't caught by lenient parsing — confirm empirically against the pinned `jakarta.mail` version while writing the test, since exact leniency boundaries vary by version) — a test built only from inputs that already fail the bare constructor (like `"not-an-address"`, which may or may not even reach that far) would pass against a non-validating implementation and give false confidence that `.validate()` is actually being invoked

- [x] Task 4 — Rebuild the SES adapter (AC: #4, #5, #6, #7)
  - [x] Delete `infrastructure/ses/SesEmailService.java`, `SesEmailServiceImpl.java`, `NoOpSesEmailService.java`, `exception/SesException.java`
  - [x] Create `infrastructure/ses/SesEmailSender.java implements OutboundEmailSender` (folds in the old `SesEmailServiceImpl` body; see AC4 — catches `SdkException`, not `SesV2Exception`)
  - [x] Create `infrastructure/ses/SesErrorClassifier.java` per the AC5 table (note: `MessageRejectedException`), with javadoc covering **both** the account-blocked mapping's rationale **and** the unknown-exception-defaults-Transient rationale (AC5) — don't document only the first and assume the second is obvious
  - [x] Rewrite `SesProperties.java`: add the new fields, remove `enabled`
  - [x] Create `infrastructure/ses/SesPropertiesValidator.java` — a separate `@Component`, `@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")`, constructor-injecting `SesProperties`, enforcing AC7 (see AC7 for why this can't be a `@PostConstruct` on `SesProperties` itself)
  - [x] Rewrite `SesConfig.java`: credentials provider (mirror `BlobstoreConfig`), `ClientOverrideConfiguration` using `retryStrategy(AwsRetryStrategy.doNotRetry())`, optional `endpointOverride`, gate on `app.email.transport=ses`
  - [x] Unit tests: `SesEmailSenderTest` (mocked `SesV2Client`; request shape for all three body combinations — html-only, text-only, both — `messageId`, and an `ApiCallTimeoutException` case asserting it surfaces as `EmailTransportTransientException`, not an uncaught SDK type; also asserts the unknown-exception case logs at WARN before wrapping — AC5), `SesErrorClassifierTest` (table-driven per AC5, includes the unknown-`SdkClientException`-defaults-Transient case), `SesAddressValidationTest` (malformed address and a multi-address string both → `EmailTransportPermanentException`, `verifyNoInteractions` on the client), `SesPropertiesValidationTest` (every field in AC7, using `ApplicationContextRunner` to assert the validator bean is present under `transport=ses` and **absent** under `transport=log` — not just "checks don't fire"; separately, assert a WARN is logged, not a startup failure, when `maxSendRatePerSecond=10` under `transport=ses`, and no WARN when it's set below 10; also assert a structurally malformed numeric field, e.g. `max-send-rate-per-second: not-a-number`, fails at Spring's own binding stage with a message naming the property — this is Spring's job, not `SesPropertiesValidator`'s, and the test documents that boundary rather than trying to re-implement it)
  - [x] Integration test: `SesEmailEndToEndIT` — see Task 4b below for how it's allowed to exist without forking every other test's context

- [x] Task 4b — Budget the Spring context for `SesEmailEndToEndIT` (AC: #4, #6)
  - [x] This class needs `app.email.transport=ses` and `app.ses.endpoint-url=<wiremock base url>` — neither can go in `application-test.yaml` (that would flip the *entire* suite onto the SES adapter) or reuse `AbstractIntegrationTest`'s two named WireMock servers (`bunny-service`, `stripe-service` only — a third server name is a documented trap per that class's own javadoc, since other tests' placeholders resolve with no default against exactly those two names)
  - [x] Add it to `IntegrationTestConventionTest.ALLOWLIST` (`src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java:50-56`) as a **sliced** `@SpringBootTest(classes = {SesConfig.class, SesEmailSender.class, SesErrorClassifier.class, SesProperties.class, /* its own `@EnableWireMock` server */})` that starts no database/Redis containers — this is the cheaper of the two documented options (the other is bumping `EXPECTED_TEST_PROPERTY_SOURCE_COUNT` from 5 to 6 with a `@TestPropertySource`, appropriate for a class that needs the full app context, which this one doesn't)
  - [x] Pin `app.ses.from-address` via an explicit test property on this class rather than letting it inherit from any profile — don't rely on `dev@localhost` resolving correctly by accident of profile inheritance
  - [x] Assert one SES call with the expected body (including the message tag) and the returned `messageId`

- [x] Task 5 — `LoggingEmailSender` (AC: #9, #9b)
  - [x] Create `infrastructure/email/log/LoggingEmailSender.java` per AC9/AC9b (directory creation at startup with WARN-not-abort on failure, `CREATE_NEW` collision handling bounded to a sane retry count, catch-and-degrade on other `IOException`s, `AtomicBoolean`-guarded once-per-transition warning, sanitised correlation-id filenames, html-or-text body selection, `"log:" + correlationId` result)
  - [x] Decide and implement the `app.email.log.outbox-dir` binding location (AC9b)
  - [x] Unit tests: `LoggingEmailSenderTest` (writes when configured, no-op when unset **or blank**, degrades on an unwritable dir without throwing, returns a non-null `messageId`, text-only request writes `.txt` not `.html`, a correlation id containing `../` or path separators is sanitised rather than escaping `outbox-dir` — assert the resulting file stays inside the configured directory, not just that no exception is thrown), `LoggingEmailSenderCollisionTest` (two sends sharing a correlation id produce two files; a directory removed between startup and send degrades to log-only rather than throwing, logging the WARN from AC9; exhausting the bounded collision-retry loop logs the specified WARN message rather than degrading silently)

- [x] Task 6 — Wiring test and cleanup of the old one (AC: #10) — **two separate test classes/mechanisms are required, not interchangeable, and not to be consolidated back into one for simplicity**
  - [x] Delete `SesConditionalWiringTest.java` (asserts against beans/properties this story removes)
  - [x] `TransportWiringTest.java` — **`ApplicationContextRunner`-based, bean-wiring only**: given `app.email.transport` is directly supplied as a property on the runner (already resolved, no YAML/EnvironmentPostProcessor involved), `hasSingleBean(OutboundEmailSender.class)` for `ses` and `log`; `ses` → `SesV2Client` also present, `log` → absent. This mechanism is correct here because `@ConditionalOnProperty` bean gating doesn't need `EnvironmentPostProcessor` support — but it must **not** be used for anything below, because it would produce a false pass
  - [x] `EmailTransportBootIT.java` (or a nested class in the same file, but a **real `SpringApplicationBuilder(...).web(WebApplicationType.NONE).run(...)`**, not `ApplicationContextRunner`) — everything that depends on `EmailTransportPropertyValidator` actually running as a registered `EnvironmentPostProcessor`:
    - the validator is registered at all (drive a context that reads the real `META-INF/spring.factories`, not by calling the class directly)
    - a present, unrecognised value (e.g. `bogus`) aborts startup with *this validator's* one-line message — **not** merely "the context fails to start", because it would also fail without the validator at all: `EmailTransportProperties.transport` is a typed `EmailTransport` enum, and Spring's own relaxed binder independently rejects an unmappable value with its *own*, worse, generic enum-binding error. An assertion that only checks "context failed" cannot tell these two failure sources apart and would stay green even if the `spring.factories` registration silently broke — assert on the **message text**, not just failure
    - a present `smtp` value aborts with the Phase-2-specific message (AC3)
    - an **absent** value does **not** abort and resolves to `log` — this is the assertion that actually proves the ordering assumption in AC3's javadoc (`EnvironmentPostProcessor` running after `ConfigDataEnvironmentPostProcessor` has loaded `application.yaml`'s base default); it must run with no profile active and no property manually supplied, i.e. the same shape as `AdminLoginResourceTest`/`RateLimitingAspectIT`, not a synthetic one
  - [x] Add a small standalone test (or a section of the boot-based one above) asserting `application-dev.yaml`, `application-uat.yaml`, `application-prod.yaml`, and `application-test.yaml` each declare `app.email.transport` explicitly, per AC3's "every profile states its own" intent

- [x] Task 7 — Point the registration listeners at the port (AC: #11)
  - [x] Update `CoachRegistrationEmailListener`, `ParentRegistrationEmailListener`, `PlayerRegistrationEmailListener`: swap the field, build `OutboundEmailRequest` **inside** the try, widen the catch to `EmailTransportException | IllegalArgumentException`, generate `correlationId = UUID.randomUUID().toString()`, preserve each file's own log wording and `PlayerRegistrationEmailListener`'s extra structured-log block exactly (AC11)
  - [x] Unit test per listener (new — none exist today) covering at minimum: happy path still renders and sends; a blank `toAddress`/subject is caught by the widened catch and logged rather than propagating out of the `AFTER_COMMIT` method

- [x] Task 8 — Pull `DevSesEmailService` deletion forward from Phase 2 (AC: #13)
  - [x] Delete `platform/notification/infrastructure/DevSesEmailService.java` — its target interface (`SesEmailService`) no longer exists after Task 4, and its trigger property (`app.ses.enabled`) no longer exists after Task 4's `SesProperties` rewrite, so it cannot compile or activate as-is
  - [x] In `application-dev.yaml`, delete the 10-line comment block documenting the `@Profile("!dev")` trap (lines ~31-40 today) — the trap it describes no longer exists

- [x] Task 9 — Configuration per environment (AC: #12)
  - [x] `application.yaml`: base default `app.email.transport: log` (Task 2)
  - [x] `application-dev.yaml`: `app.ses.enabled: false` line removed; add `app.email.transport: log` (explicit, even though redundant with the base default); `from-address: ${APP_SES_FROM_ADDRESS:dev@localhost}`; add `app.email.log.outbox-dir: target/mails`
  - [x] `application-uat.yaml`: `app.ses.enabled: false` line removed; add `app.email.transport: log` (explicit); `from-address: ${APP_SES_FROM_ADDRESS:}`
  - [x] `application-prod.yaml`: `app.ses.enabled: true` line removed; add `app.email.transport: ses`; `from-address: ${APP_SES_FROM_ADDRESS:}`
  - [x] `src/test/resources/application-test.yaml`: add `app.email.transport: log` (explicit) **and** `app.email.log.outbox-dir: ""`. The second line is load-bearing, not decorative: `AbstractIntegrationTest` is `@ActiveProfiles({"dev", "test"})`, so **every** integration test — including `CoachRegistrationResourceIT`/`ParentRegistrationResourceIT`/`PlayerRegistrationResourceIT`, which exercise the registration flow directly — loads `application-dev.yaml`'s `outbox-dir: target/mails` first. `TestConfig` overrides only `MailManager` (→ `TestMailManager`), nothing in the SES/email path, so without this line every registration-flow IT in the suite starts writing real files to `target/mails` on every CI run (profile order `{"dev","test"}` means `test` applies last and wins, so `""` here disables it — AC9 defines blank as disabled for exactly this reason)
  - [x] `docker-compose.yml` (prod): add `APP_SES_FROM_ADDRESS` (no `:-` default — a genuinely missing value must reach the app as blank and trip AC7's validator, not silently vanish), `APP_SES_REGION` (`:-eu-west-1`), `APP_SES_ACCESS_KEY`/`APP_SES_SECRET_KEY` (`:-`, blank ⇒ default credential chain, mirroring the storage block right above where these should sit), `APP_SES_CONFIGURATION_SET` (`:-`), `APP_SES_REPLY_TO_ADDRESS` (`:-`). **Do not** add `APP_EMAIL_TRANSPORT` here or to any compose file — see the dedicated warning below
  - [x] `docker-compose.local.yml`: this file's `environment:` block restates dev's own defaults specifically because listing a variable in the base `docker-compose.yml` makes it present-and-blank under compose, which overrides `${VAR:dev-default}` in `application-dev.yaml` (`docker-compose.yml:35-42`'s own "EMPTY IS NOT UNSET" comment, and `docker-compose.local.yml:33-40` already does this for the storage/video secrets). Since `APP_SES_FROM_ADDRESS` is added to the base file in this task, add `APP_SES_FROM_ADDRESS=dev@localhost` to `docker-compose.local.yml` restating dev's own default too, or the dev-under-compose default is silently dead — the `${APP_SES_FROM_ADDRESS:dev@localhost}` fallback in `application-dev.yaml` never applies once compose passes the variable through blank. Harmless today (dev runs `transport=log`, which doesn't validate `fromAddress`), but confusing, and it stops being harmless the moment anyone runs dev locally against `transport=ses` for a manual check
  - [x] Same file, lines ~26-28: replace the stale comment (*"No `APP_SES_ENABLED` override here any more: `application-dev.yaml` now sets `app.ses.enabled: false`... see the comment there..."*) — both the property and the comment it points at are gone after Task 8/this task. Replace with a one-line note that dev now runs `app.email.transport: log`
  - [x] `.env.example`: document the five `APP_SES_*` variables in the `--- Email / SMTP ---` block, noting `APP_SES_FROM_ADDRESS` is **required** once this ships to prod. Immediately above the block, add a bold-flagged line: *"**Do not add `APP_EMAIL_TRANSPORT` here or to any compose file** — see `EmailTransportPropertyValidator`'s javadoc; a blank value would abort boot in every environment simultaneously."*
  - [x] **Do not** touch `SPRING_MAIL_PASSWORD`, `GMX_PASSWORD`, `GMAIL_PASSWORD`, or `email.providerConfigs` anywhere — that cleanup is Phase 2 (D9)
  - [x] **Never add `APP_EMAIL_TRANSPORT` to any compose file in this story, and enforce it, not just document it.** This is the highest-consequence trap this story's own design creates: if a future change adds `APP_EMAIL_TRANSPORT=${APP_EMAIL_TRANSPORT:-}` to `docker-compose.yml` — the established convention for every other variable in that block — a blank value overrides **every profile's** `app.email.transport` simultaneously (prod, uat, and local all inherit the base file) and aborts boot everywhere at once, since blank is a present-and-unrecognised value under AC3. Prose warnings in a 300-line story file get skipped by someone following the existing "add a `:-` line" pattern by muscle memory — back it with a cheap repo-convention test in the same spirit as `NoHardcodedSenderTest`:
    ```java
    @Test
    void noAppEmailTransportEnvVarInComposeFiles() throws IOException {
        for (String file : List.of("docker-compose.yml", "docker-compose.uat.yml",
                                    "docker-compose.uat-hostwinds.yml", "docker-compose.local.yml")) {
            assertThat(Files.readString(Path.of(file)))
                .as("APP_EMAIL_TRANSPORT in " + file + " would override app.email.transport in "
                    + "every profile simultaneously — see requirements/ses-email-consolidation.md#4.5 "
                    + "and EmailTransportPropertyValidator's javadoc")
                .doesNotContain("APP_EMAIL_TRANSPORT");
        }
    }
    ```
    Put this alongside `NoHardcodedSenderTest` (same task, same rationale for existing as a fast, non-`*IT` guardrail) rather than as its own separate story item
  - [x] Add `NoHardcodedSenderTest` (§7.2 item 9) — no shipped `application*.yaml` contains a *real domain* literal for `app.ses.from-address`, and uat/prod supply no non-empty default. This needs an explicit carve-out for dev's own `${APP_SES_FROM_ADDRESS:dev@localhost}`, which **is** a literal default in a shipped YAML by design (AC12) — the rule that matters is "no real domain, and no default at all in uat/prod", not "no default anywhere". A naive implementation of this test without the carve-out fails against this story's own dev configuration
  - [x] **Operational note to call out at PR time**: prod's real `.env` (not `.env.example`) must have `APP_SES_FROM_ADDRESS` set *before* this deploys, or prod fails to boot by design (AC7). Coordinate with whoever holds prod's env file — and note that `deploy-2-2`'s manual deploy workflow has a smoke-test auto-revert, so a missing value will present as a failed smoke test and an automatic revert, not as a readable boot error in the deploy console
  - [x] **Known doc drift accepted for this story, not fixed here**: `docs/deployment/secrets-reference.md:158-164`, `docs/deployment/local-deployment.md:222-224`, `requirements/deployment/local/local-manual-testing.md:147-166`, `docs/dev-docs/index.html:163`, `docs/dev-docs/infrastructure/index.html:168-169,295`, and `docs/dev-docs/notification/index.html:278,283` all describe the old `app.ses.enabled`/`NoOpSesEmailService` model in prose. Rewriting them belongs to Phase 7 (Documentation, §5 Phase 7), which also needs to describe the full `app.email.transport` model these docs don't know about yet — a partial fix now would just be replaced. Leave as-is; do not expand this story to cover them.

### Review Findings

<!-- /bmad-code-review 2026-09-11, 3 layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor),
     all three completed. 5 decision-needed, 19 patch, 5 defer, 5 dismissed as false positives.
     Every claim below was independently re-verified by the orchestrator against source or the
     resolved jars before being written here; the 5 dismissals are recorded at the bottom with the
     evidence that killed them. -->

**Decisions resolved (2026-09-11, owner: Mbah) — all applied** — all five became patches; recorded here with the call and its rationale so the reasoning isn't re-litigated.

- [x] [Review][Patch] **D1 → keep the SDK default retry strategy until Phase 4.** Remove `.retryStrategy(AwsRetryStrategy.doNotRetry())`; restore it in Phase 4 when `MailManager` actually owns the call and AC6's comment ("the application's own retry layers ... once MailManager owns the call") becomes true. Today nothing consumes `SesErrorClassifier`'s transient/permanent split, and the client this replaces carried the SDK default (3 attempts) — verified via `git show HEAD:...SesConfig.java`. `apiCallTimeout(5s)` bounds the entire call including all retry attempts, so this cannot blow the latency budget in an `AFTER_COMMIT` listener. Update the AC6 comment to say the zero-retry flip is deferred to Phase 4 rather than implying it is already justified. [src/main/java/com/softropic/skillars/infrastructure/ses/SesConfig.java:49]
- [x] [Review][Patch] **D2 → fail fast on unresolvable AWS credentials.** Call `resolveCredentials()` under `transport=ses` in `SesPropertiesValidator` so a credential chain that cannot resolve aborts boot, instead of booting green and dropping 100% of mail as *transient*. Consistent with the blank-`from-address` abort already in that method; Phase 3's `SesHealthIndicator` covers ongoing health (sandbox status via `GetAccount`), a separate concern, so this is not duplicated work. Note this lands in the same method as the `region` blank check below — do both in one pass. [src/main/java/com/softropic/skillars/infrastructure/ses/SesPropertiesValidator.java:56]
- [x] [Review][Patch] **D3 → narrow §3.4's invariant and carve out `EmailAddressParser`.** The invariant targets SMTP transport plumbing (`JavaMailSender`, `org.springframework.mail..`), not a shared transport-neutral address validator that the SES adapter also depends on. Record the carve-out in `requirements/ses-email-consolidation.md` §3.4 so Phase 2's `EmailTransportArchitectureTest` (§7.2 item 14) is written expecting it, and record that **Phase 6 must keep `jakarta.mail` as a direct dependency** rather than let it disappear with `spring-boot-starter-mail`. Rejected: moving the parser under `.smtp` (inverts the dependency — SES would import from the SMTP package Phase 6 deletes) and reimplementing without `jakarta.mail` (hand-rolled RFC 5322 against the class's own "no second regex" rule; a regex would miss the group-syntax case reproduced in this review). [requirements/ses-email-consolidation.md:374]
- [x] [Review][Patch] **D4 → mask the recipient on the INFO line; keep the full value in the outbox file.** `LoggingEmailSender:83-84` logs `to={}` in full; the `NoOpSesEmailService` it replaces logged `subject` only. UAT runs `transport: log` with `LOKI_ENABLED=true`, so every registrant's address — players are minors, plus their parents — enters Loki retention. Log a masked form (`j***@example.com`); the rendered outbox `.html` still carries everything, so the dev workflow is unaffected. [src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java:83]
- [x] [Review][Patch] **D5 → add the `APP_SES_FROM_ADDRESS` required-secret row to `docs/deployment/secrets-reference.md` now.** Additive and contained; does not conflict with the Phase 7 narrative rewrite that Task 9 deliberately defers. Closes the gap Task 9's own operational note flags: a missing value presents as a failed smoke test and an automatic revert, not a readable boot error, and the doc an operator reads before a prod deploy currently omits the variable entirely (`grep -rn "APP_SES" docs` → no match). Scope this to the required-secret row only — leave the stale `app.ses.enabled` prose for Phase 7 as already decided. [docs/deployment/secrets-reference.md:159]

**Patch**

- [x] [Review][Patch] RFC 822 group syntax bypasses the multi-recipient guard — REPRODUCED against `angus-mail-2.0.5`: `InternetAddress.parse("undisclosed: victim@x.com, attacker@y.com;", true)` returns **n=1**, `validate()` passes, `isGroup()=true`, and `getAddress()` is the whole group string. `isGroup()` is never checked, so the `parsed.length != 1` guard is dead for this input class and the class javadoc's own promise — *"a single request field must not smuggle a second recipient past validation"* — is false. `SesEmailSender.java:41` then puts the raw string on the wire. Same hole on `app.ses.reply-to-address`. Not exploitable through registration today (`@Email` on the request DTOs rejects it), but this is a public port. Fix: reject `address.isGroup()`. [src/main/java/com/softropic/skillars/infrastructure/email/EmailAddressParser.java:43]
- [x] [Review][Patch] Validation normalises the address but the un-normalised raw string is what gets sent — REPRODUCED: `"  john@example.com  "`, `"john@example.com\n"` and `"a@b.com,"` all parse to n=1 VALID with a trimmed `getAddress()`. `SesEmailSender.java:71` discards the parser's return value, and `:38`/`:41`/`:60` send the raw field. A trailing newline in `APP_SES_FROM_ADDRESS` (trivially produced by `$(cat file)` in a deploy script) passes `SesPropertiesValidator` and then fails every send at SES as a *permanent* error. Fix: reject when raw differs from the parsed form, or send the parsed form. [src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailSender.java:69]
- [x] [Review][Patch] HTTP 429 and 403 fall into the "4xx ⇒ permanent" bucket — `SesErrorClassifier.java:71-78` tests `>= 500` then `>= 400`, so any throttle arriving as a generic `AwsServiceException` (unmodelled error code, or a 429 from a proxy/WAF) and any `RequestTimeTooSkewed` 403 is classified permanent and dropped without retry. The SDK's own `isThrottlingException()` / `isClockSkewException()` predicates exist on `AwsServiceException` and are unused. Fix: consult both before the 4xx bucket. [src/main/java/com/softropic/skillars/infrastructure/ses/SesErrorClassifier.java:71]
- [x] [Review][Patch] `correlationId` is sanitised for the log transport and sent raw to SES — `LoggingEmailSender.java:49-50` restricts it to `[A-Za-z0-9._-]` and 200 chars; `SesEmailSender.java:53` passes it straight into `emailTags`, where SES enforces ASCII letters/digits/`_`/`-` and ≤256. The two sanitisers even disagree on `.`. A future caller's id succeeds on every non-prod profile (all `log`) and fails prod-only as a *permanent* `BadRequestException` — the whole email dropped over a tag. Fix: sanitise adapter-side, matching §6.3's "format rules live with the transport". [src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailSender.java:53]
- [x] [Review][Patch] `app.ses.region` is the one SES property with no validation — `Region.of("")` throws `IllegalArgumentException` inside `@Bean` creation (the opaque bean-creation trace `EmailTransportPropertyValidator` exists to prevent, for a sibling property), and a typo like `eu-wset-1` is accepted, boots green, and turns every send into a DNS failure classified *transient* — indistinguishable from a network blip, forever. Fix: add a blank/whitespace check to `SesPropertiesValidator`. [src/main/java/com/softropic/skillars/infrastructure/ses/SesPropertiesValidator.java:56]
- [x] [Review][Patch] An absent `app.email.transport` leaves zero `OutboundEmailSender` beans with no abort — the validator returns early on `raw == null` (`EmailTransportPropertyValidator.java:47-50`) and neither sender gate declares `matchIfMissing`. The deleted `NoOpSesEmailService` carried `matchIfMissing = true`, which is what made "unset is allowed" safe; that property was removed with the bean and not replaced. Only one line of YAML stands between absent and `NoSuchBeanDefinitionException` on the three listeners. Fix: restore `matchIfMissing = true` on the `log` gate. [src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java:46]
- [x] [Review][Patch] `InvalidPathException` escapes `LoggingEmailSender`, contradicting its documented "never throws / never aborts startup" — `@PostConstruct` catches `IOException` only, but `Path.of` throws the unchecked `InvalidPathException`, failing context refresh for the transport whose entire purpose is to be the safe default. Same unguarded call at `:102`. [src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java:73]
- [x] [Review][Patch] `EmailAddressParser.validateSingle(null)` throws NPE, not the documented `IllegalArgumentException` — `InternetAddress.parse` dereferences before any syntax check, so the NPE escapes both catch blocks, past `SesEmailSender.validateRecipient`'s `IllegalArgumentException`-only conversion, past the port's declared taxonomy, and out of an `AFTER_COMMIT` listener. Unreachable today; this is a public `@Component`. [src/main/java/com/softropic/skillars/infrastructure/email/EmailAddressParser.java:36]
- [x] [Review][Patch] `OutboundEmailResult.messageId` is documented "Never null" but `response.messageId()` is unchecked — nullable SDK member, no `requireNonNull`, no fallback; every test stubs it present. Phase 4 persists this column. [src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailSender.java:63]
- [x] [Review][Patch] `.env.example`'s `APP_SES_FROM_ADDRESS=noreply@example.com` defeats the fail-fast its own comment describes — the comment says a missing value "reaches the app blank and fails SesPropertiesValidator's startup check", but a `cp .env.example .env` yields a syntactically valid address that passes both checks, and SES then returns `MailFromDomainNotVerifiedException`, classified *transient*. 100% of mail lost while the logs say "failed transiently". Fix: ship it blank, matching the four keys below it. [.env.example:67]
- [x] [Review][Patch] `APP_SES_MAX_SEND_RATE_PER_SECOND` is absent from `docker-compose.yml` and `.env.example`, which §4.5 explicitly required — §4.5: *"Include `APP_SES_MAX_SEND_RATE_PER_SECOND` carrying the §6.5 arithmetic in its comment"*. The property defaults to 10 and `SesPropertiesValidator.java:92` WARNs at `>= 10`, so every prod boot emits the sandbox warning and the remediation it prescribes (set it to 1) cannot be applied from the environment at all. Task 9 narrowed §4.5 to five variables without recording the drop. [docker-compose.yml:88]
- [x] [Review][Patch] Two tests pinning the load-bearing blank-outbox behaviour cannot fail — `unsetOutboxDir_writesNoFile` and `blankOutboxDir_treatedAsUnset_writesNoFile` construct the sender with `null`/`""` and then assert `Files.list(tempDir)` is empty, but the sender was never told about `tempDir`. Drop `!configuredDir.isBlank()` from `LoggingEmailSender:64` and `Path.of("")` resolves to the module root — every registration IT writes rendered OTP emails into the repo while both tests stay green. That is exactly what `application-test.yaml:128-133` calls load-bearing. [src/test/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSenderTest.java:63]
- [x] [Review][Patch] The `AwsServiceException` status-code branch has zero test coverage — `SesErrorClassifierTest.cases()` carries 429, 400×5, 404 and two `SdkClientException`s, all *modelled* types, so no case reaches `SesErrorClassifier.java:71-78`. Deleting the `>= 500` branch leaves 5xx falling through to the transient default — suite green; inverting it to permanent — also green. Add generic `AwsServiceException` cases at 500, 429 and 403. [src/test/java/com/softropic/skillars/infrastructure/ses/SesErrorClassifierTest.java:33]
- [x] [Review][Patch] `EmailTransportProfileDeclarationTest` accepts `smtp`, the value that aborts boot — the pattern is `(ses|log|smtp)`, while `EmailTransportPropertyValidator:51-54` rejects `smtp` outright. Set `application-prod.yaml` to `transport: smtp` and the whole suite stays green while prod dies on the next deploy. Fix: drop `smtp` from the alternation. [src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportProfileDeclarationTest.java:32]
- [x] [Review][Patch] Two guardrail tests assert too weakly to catch what they were written for — `EmailTransportBootIT:74-79` asserts only `.contains("app.email.transport").contains("bogus")`, both of which also appear in Spring's generic binder failure, while the sibling smtp test one method below asserts the validator's distinctive sentence; Task 6 demanded message text. And five failure cases in `SesPropertiesValidationTest:72-119` assert only `hasFailed()` despite AC7 requiring "a message naming the property" — the class already has a `fullCauseChainMessage` helper it uses elsewhere. [src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportBootIT.java:74]
- [x] [Review][Patch] The path-traversal test's two security assertions are tautologies — `allSatisfy(p -> p.normalize().startsWith(tempDir))` is trivially true because `Files.walk(tempDir)` only yields paths under `tempDir`, and `assertThat(Files.exists(Path.of("/etc/passwd.html"))).isFalse()` holds on any machine. Only `hasSize(1)` carries weight; `sanitize()`'s actual behaviour is unpinned. Fix: assert the expected sanitised filename. [src/test/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSenderTest.java:136]
- [x] [Review][Patch] The listeners mint a `correlationId` and omit it from the only line that reports failure — it is generated inline as the fifth constructor argument, never held in a local, and the `catch` logs a static message plus the exception. The id is in the SES `emailTags` and the outbox filename but not the failure record, so a batch of drops yields N identical ERROR lines with nothing to join against CloudWatch — the tag's only purpose. All six handlers across the three listeners. [src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListener.java:51]
- [x] [Review][Patch] Leftover scaffolding comment — `// was: sesEmailService.send(event.toAddress(), subject, html);` trails the send call; the Coach and Parent listeners carry no equivalent. [src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/PlayerRegistrationEmailListener.java:54]
- [x] [Review][Patch] The test counts in the Dev Agent Record and the sprint-status note are wrong — both claim *"61 new/updated tests across 16 test classes"*; the working tree holds **17** new test classes and **80** `@Test`/`@ParameterizedTest` methods, which expand to more than 80 executions given the parameterised ones. [_bmad-output/implementation-artifacts/ses-1-1-introduce-outbound-email-port.md:380]

**Deferred**

- [x] [Review][Defer] `app.ses.max-send-rate-per-second` is validated and warned on but never enforced — deferred, no rate limiter exists in the adapter and §5 assigns throttling to a later phase
- [x] [Review][Defer] Collision-exhaustion in `LoggingEmailSender` is unthrottled and costs 100 filesystem syscalls per send — deferred, degraded-path nuisance reachable only with a reused constant correlationId against an already-full dev outbox
- [x] [Review][Defer] No non-production environment exercises the SES path at all — deferred, dev/uat are both `log` and `smtp` is rejected until Phase 2; the first real SES call happens in prod by design of the phase plan
- [x] [Review][Defer] `LoggingEmailSender` silently discards the text part when both bodies are present — deferred, no current caller sends both; the outbox artifact is simply not a faithful record for that shape
- [x] [Review][Defer] `NoHardcodedSenderTest` only inspects the first `from-address` match per file — deferred, `matcher.find()` stops at the first hit so a second hardcoded literal added later escapes the guard

<!-- Dismissed as false positives, with the evidence that killed each:
     1. "app.ses.endpoint-url is an exfiltration vector" (Blind Hunter) — BlobstoreProperties:23 /
        BlobstoreConfig:84 establish this exact property as project convention, APP_STORAGE_ENDPOINT_URL
        is set in all four compose files including UAT, and SesConfig:28 states it mirrors that class
        deliberately. The threat model also requires container env control, which is already total
        compromise.
     2. "Player listener's widened catch swallows Thymeleaf failures" (Blind Hunter) — verified false.
        templateEngine.process and messageSource.getMessage are at PlayerRegistrationEmailListener:44-45,
        OUTSIDE the try, identical to Coach and Parent. Only the structured-log call is inside.
     3. "NoHardcodedSenderTest errors with NoSuchFileException on missing compose files" (Blind Hunter)
        — all four exist: docker-compose{,.local,.uat,.uat-hostwinds}.yml.
     4. "Docs describe deleted classes / app.ses.enabled" (all three layers) — already recorded as a
        deliberate Phase 7 deferral in this story's own Task 9, which names the exact files and lines.
        Only the narrower missing-required-secret half survives, as a decision item above.
     5. "EmailTransportProfileDeclarationTest's unanchored regex could match an unrelated transport: key"
        — verified exactly one `transport:` key per file today, so the risk is theoretical. The smtp half
        of that finding is real and is a patch item above. -->

## Dev Notes

### Why this scope, and where the doc's phase boundary was adjusted

This is Phase 1 of the 7-phase plan in `requirements/ses-email-consolidation.md` (§5). Its own exit criteria: *"SES behaves identically for the six registration emails. SMTP untouched... Risk: low — mechanical."* Everything in `platform/notification` except the three registration listeners is out of scope — confirmed by §4.3's explicit "not touched anywhere else in this module" list (`MailService`, `MailManager`, `SenderProvider`, `MailSenderProvider`, `SmtpHealthIndicator`, all 31 Thymeleaf templates, all i18n bundles, `EnvelopeMapper`, the outbox support, `EmailRetryScheduler`, every other `infrastructure/listener/*`).

**Three deviations from the doc's literal text, each with a verified rationale — read these before implementing, they're encoded directly into the ACs above but the "why" is worth having in one place:**

1. **`DevSesEmailService` deletion pulled forward from Phase 2 to Phase 1.** It `implements SesEmailService` and is gated on `app.ses.enabled`, both removed in Task 4. A class that can't compile against its own interface, gated on a property that no longer exists, can't be left for "later" — that's a build break. See Task 8 and AC12's closing bullet for the (narrow, already-mostly-inert) functional consequence.

2. **`app.email.transport` gets a base default (`log`) in `application.yaml`, not "no default."** A literal "no default" design — the validator aborting on *unset* — breaks every Spring test context that loads no profile (`AdminLoginResourceTest`, `RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT`, confirmed via grep to be the only three such classes plus every future one like them, since only two classes in the whole suite declare `@ActiveProfiles` at all) and any bare `mvn spring-boot:run`. `EnvironmentPostProcessor`s run for every `SpringApplication`, test or production; they are not gated on "the app is actually starting for real." See AC3.

3. **Dev and uat get `transport=log` in Phase 1, not `transport=smtp` as §4.5's table shows for the end state.** `SmtpEmailSender` doesn't exist until Phase 2 — there is nothing for `smtp` to wire to yet. `log` is the faithful successor to today's actual behaviour (`NoOpSesEmailService`, both profiles pin `app.ses.enabled: false` today). See AC12.

**The `EmailTransport` enum has three values from this story, but the validator currently accepts only two.** The enum is written complete now (`SES`, `SMTP`, `LOG`) so Phase 2 only has to flip the validator's accepted set and add a bean, not touch the selector type. But `EmailTransportPropertyValidator` **rejects** a present `smtp` value in this phase with a specific message (AC3) — accepting it now would pass the fail-fast gate and then die in `NoSuchBeanDefinitionException` when nothing implements `OutboundEmailSender` for it, which is exactly the ambiguous-failure mode the validator exists to prevent. **Do not** write `TransportWiringTest` assertions expecting a bean for `transport=smtp` in this story, and **do** assert that it's explicitly rejected (not merely "no bean created").

**`SesProperties` validation lives in a separate `@ConditionalOnProperty`-gated bean, not a `@PostConstruct` on `SesProperties` itself — this is not optional, it doesn't work the other way.** `@ConditionalOnProperty` is ignored on a class registered via `@EnableConfigurationProperties(...)` (`SesConfig.java:10`); the bean is created unconditionally no matter what's annotated on the class. See AC7 for the fix and why it's actually the better design anyway (testable via `ApplicationContextRunner`, no coupling of a plain binding POJO to `EmailTransportProperties`).

**Correlation id for the three listeners: generate a fresh `UUID.randomUUID().toString()` per send.** `OutboundEmailRequest.correlationId` is required (AC1) and the source doc describes it as "opaque id echoed into logs" and the SES message tag / `LoggingEmailSender` filename key (§3.1, §4.1) — it carries no idempotency contract (§3.1 says so explicitly: *"a null would be silently lossy... it is not [an idempotency key]"*). The doc doesn't pin a source for it at this call site, because in the doc's target end-state (Phase 4) these listeners no longer call the port directly at all — they enqueue through `NotificationOutboxSupport`, which is where a real `sendId` gets minted (§6.15). For this story, the listeners are unchanged in every other way (still direct `AFTER_COMMIT` calls, still no durability, still D3), so there is no existing stable id to reuse and no retry mechanism at this layer to make one matter yet. `UUID.randomUUID().toString()` is therefore the correct, simplest choice, and gets thrown away in Phase 4 when `enqueueEmail`'s real `sendId` replaces it. Don't try to anticipate Phase 4's `sendId` scheme here. **Accepted, one-time logging discontinuity**: any registration email sent in Phase 1-3 carries a random UUID in its logs/message-tag/filename; the same email type sent from Phase 4 onward carries a `sendId`. There is no way to cross-reference a Phase-1-era send after the Phase 4 cutover — nothing persists the UUID anywhere durable (no `EnvelopeEntity` row exists for these sends until Phase 4), so there is nothing to reconcile. This is a non-issue in practice, not a gap to design around now.

**Present-but-unrecognised enum values can be rejected by Spring's own binder, independent of `EmailTransportPropertyValidator` — don't let a test mistake one for the other.** `EmailTransportProperties.transport` is a typed `EmailTransport` enum; a value like `app.email.transport=bogus` that doesn't match any constant fails Spring's *own* relaxed-binding conversion, with a generic enum-binding message, regardless of whether the custom `EnvironmentPostProcessor` is registered at all. A test that only asserts "the context fails to start" for an unrecognised value provides no evidence the validator's fail-fast path (with its specific, named-property message) is what actually fired — it would pass identically whether `spring.factories` is correctly wired or silently broken. Task 6's boot-based test asserts on the **message text**, not just failure, specifically to close this gap.

**`SesProperties.maxSendRatePerSecond` and `configurationSet` are added and validated (AC7) but not yet enforced.** `SesSendRateLimiter` — the Resilience4j rate limiter that actually reads `maxSendRatePerSecond` and gates the SES call — is a Phase 3 component (§5 Phase 3 item 1). This story only adds the property, validates it's a positive number at startup, and (for `configurationSet`/`replyToAddress`) actually *uses* the other two values on every request (AC4 — they're set on the SESv2 call itself, unlike the rate limit). Do not build `SesSendRateLimiter` or wire anything ahead of the API call in this story.

**`${APP_SES_FROM_ADDRESS:}` (with the trailing colon), not a bare `${APP_SES_FROM_ADDRESS}` — even in prod.** The source doc's §4.5 table writes `from-address: ${APP_SES_FROM_ADDRESS}` "with no default" for uat and prod. Taken literally, a **bare** Spring placeholder with zero fallback throws `IllegalArgumentException: Could not resolve placeholder` at property-binding time if the env var is absent from *every* property source — this happens regardless of whether `transport=ses`, because `SesProperties` is an unconditional `@ConfigurationProperties` bean bound at context refresh. The existing codebase already solved this exact problem for `APP_STORAGE_BUCKET`, `APP_STORAGE_ENDPOINT_URL`, and `APP_STORAGE_S3_ACCESS_KEY` in `application-uat.yaml` (lines 41-44): all three use the `${VAR:}` form specifically so the *value* can be blank while the *placeholder* always resolves, and application-level validation — not Spring's placeholder mechanism — enforces "must not actually be blank." Follow that established pattern here.

**Adding `APP_SES_FROM_ADDRESS` to `docker-compose.yml`'s base `environment:` block has a side effect on dev and uat, both of which inherit it.** See Task 9's `docker-compose.local.yml` bullet and C1 in the review this revision responds to: compose's "empty is not unset" behaviour means dev's `:dev@localhost` YAML fallback stops applying the moment the variable is merely *listed* in the base file, even with `dev@localhost` value. Restating it in `docker-compose.local.yml` is required, not tidiness.

### Files this story deletes

- `infrastructure/ses/SesEmailService.java`
- `infrastructure/ses/SesEmailServiceImpl.java`
- `infrastructure/ses/NoOpSesEmailService.java`
- `infrastructure/ses/exception/SesException.java` (delete the `exception` package too if left empty)
- `infrastructure/ses/SesEnabledPropertyValidator.java`
- `platform/notification/infrastructure/DevSesEmailService.java` (see rationale above)
- `src/test/java/.../infrastructure/ses/SesEnabledPropertyValidatorTest.java`
- `src/test/java/.../infrastructure/ses/SesConditionalWiringTest.java`

### Files this story creates

- `infrastructure/email/OutboundEmailSender.java`, `OutboundEmailRequest.java`, `OutboundEmailResult.java`
- `infrastructure/email/EmailTransportException.java`, `EmailTransportTransientException.java`, `EmailTransportPermanentException.java`
- `infrastructure/email/EmailTransport.java`, `EmailTransportProperties.java`, `EmailTransportPropertyValidator.java`
- `infrastructure/email/EmailAddressParser.java`
- `infrastructure/email/log/LoggingEmailSender.java`
- `infrastructure/ses/SesEmailSender.java`, `SesErrorClassifier.java`, `SesPropertiesValidator.java`

### Files this story modifies

- `infrastructure/ses/SesConfig.java`, `SesProperties.java`
- `platform/security/infrastructure/listener/CoachRegistrationEmailListener.java`, `ParentRegistrationEmailListener.java`, `PlayerRegistrationEmailListener.java`
- `META-INF/spring.factories`
- `application.yaml`, `application-dev.yaml`, `application-uat.yaml`, `application-prod.yaml`, `src/test/resources/application-test.yaml`
- `docker-compose.yml`, `docker-compose.local.yml`, `.env.example`
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java` (`ALLOWLIST` addition for `SesEmailEndToEndIT`)

### Current-state read of what's being replaced (verified against HEAD at story-creation time)

- `SesEmailService` today is a one-method interface (`send(String to, String subject, String htmlBody)`); `SesEmailServiceImpl` is `@Service @Profile("!dev") @ConditionalOnProperty(app.ses.enabled=true)`, builds an SESv2 `simple`/`html`-only content, throws `SesException` on `SesV2Exception` (only — it does not catch `SdkClientException`, which is the exact gap this revision closes). `SesConfig` builds a bare `SesV2Client.builder().region(...)` with no credentials provider, no override config, gated the same way. `SesProperties` today has just `fromAddress`, `region` (default `eu-west-1`), `enabled` (default `true`). `NoOpSesEmailService` is `@ConditionalOnProperty(havingValue="false", matchIfMissing=true)` and just logs. None of this sets `configurationSetName`, `replyToAddresses`, or a message tag — those are new in this story, not a regression to preserve.
- The three registration listeners are structurally identical except for the log-message wording and `PlayerRegistrationEmailListener`'s extra structured-log block (see AC11): `@TransactionalEventListener(AFTER_COMMIT)`, build a `Recipient`, render via `SpringTemplateEngine.process(...)` + `MessageSource.getMessage(...)` directly (this hand-rolled duplication is D5 — **not** fixed in this story; `EmailContentRenderer` is Phase 2), call `sesEmailService.send(...)` **inside** the try, catch `SesException` and log. Only the field type, the request construction, and the catch clause change here.
- `application-dev.yaml`/`application-uat.yaml` both set `app.ses.enabled: false` and the literal `from-address: noreply@skillars.com`; `application-prod.yaml` sets `enabled: true` with the same literal. None of the three currently source `from-address` from an env var — this story is what introduces that.
- `pom.xml` already has `software.amazon.awssdk:sesv2` on the BOM (`2.54.13`) and `apache-client`; no new AWS dependency is needed for `endpointOverride`/WireMock. Verified against the resolved jars: `RetryPolicy`/`ClientOverrideConfiguration.Builder.retryPolicy(RetryPolicy)` are `@Deprecated`; `AwsRetryStrategy.doNotRetry()` (in `aws-core`, already on the classpath transitively) is the replacement and is present in the pinned version.
- `jakarta.mail` (hence `jakarta.mail.internet.InternetAddress`, recommended for `EmailAddressParser`) is already on the classpath via `spring-boot-starter-mail`, used today by `MailService.java`. It stays on the classpath until Phase 6, so no new dependency is needed for Task 3 either.
- `AbstractIntegrationTest` (`src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java`) is `@ActiveProfiles({"dev", "test"})` with exactly two named `@EnableWireMock` servers (`bunny-service`, `stripe-service`) — its own javadoc documents why a third server name is a trap. `IntegrationTestConventionTest` pins `EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5` and an `ALLOWLIST` of exactly three sliced-context classes (`RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT`, `MessageModerationSweeperIT`) — `SesEmailEndToEndIT` becomes the fourth.

### Testing standards

- Follow the existing `infrastructure/ses` test conventions: `ApplicationContextRunner`/`UserConfigurations` for bean-wiring tests (no `@SpringBootTest`, per `SesConditionalWiringTest`'s own Dev Notes on the context-count ceiling — see `IntegrationTestConventionTest`), and `MockEnvironment` for `EnvironmentPostProcessor` unit tests driven directly against the validator class (see `SesEnabledPropertyValidatorTest`, the direct template for `EmailTransportPropertyValidatorTest`). **`ApplicationContextRunner` cannot be used to prove `EmailTransportPropertyValidator` is registered via `spring.factories`** — it never runs `EnvironmentPostProcessor`s, because it isn't a `SpringApplication`. That one assertion needs a real `SpringApplicationBuilder(...).web(WebApplicationType.NONE).run(...)` (Task 6).
- `SesEmailEndToEndIT` uses WireMock against `app.ses.endpoint-url`, added to `IntegrationTestConventionTest.ALLOWLIST` as a sliced context (Task 4b) — it must **not** extend `AbstractIntegrationTest` or reuse its two named WireMock servers.
- Do **not** add tests for `transport=smtp` wiring, `SesHealthIndicator`, `SesSendRateLimiter`, `EmailContentRenderer`, or `MailManager`'s exception classification in this story — none of that code exists yet (Phases 2-3). Adding tests against nonexistent beans is a sign the story has scope-crept into a later phase. Do assert `transport=smtp` is explicitly rejected by the validator (that's this phase's concern, per AC3/H2).
- `application-test.yaml`'s `app.email.log.outbox-dir: ""` addition is load-bearing for CI hygiene (Task 9/H4) — confirm no `target/mails/*.html` files appear after running the registration-flow ITs locally.
- Confirm `IntegrationTestConventionTest` stays green with **both** changes: the `application-test.yaml` property additions (no new count needed — they're not `@TestPropertySource`) and the new `ALLOWLIST` entry (no count change needed for the allowlist itself, but confirm the "no redeclared context annotations" check still passes for the new sliced class).

### Project Structure Notes

- New code lives under `com.softropic.skillars.infrastructure.email` (port + exceptions + transport selector + `log/`) and the existing `com.softropic.skillars.infrastructure.ses` package (adapter). This matches the target architecture diagram in §3 exactly.
- `infrastructure.ses` must not import `platform.*` (rule already true today; stays true — `SesEmailSender`'s input is a rendered `OutboundEmailRequest`, never an `Envelope`/`EmailTemplate`/`Recipient`).
- The three registration listeners stay in `platform/security/infrastructure/listener/` — this story does not move them.
- No `infrastructure/email/smtp/` package is created in this story — that's Phase 2. Don't pre-create it or Phase 2's `EmailTransportArchitectureTest` (which must land with that package, per §7.2 item 14) has nothing to anchor against yet.

### References

- [Source: requirements/ses-email-consolidation.md#1] — goal and staging table
- [Source: requirements/ses-email-consolidation.md#2.1, #2.2] — current two-stack architecture and defects D1-D9 this migration addresses over its full 7 phases (this story starts on D1/D2's groundwork by making `fromAddress` configurable and validated; D3-D9 are later phases)
- [Source: requirements/ses-email-consolidation.md#3, #3.1, #3.2, #3.3, #3.4] — target architecture, port API, exception taxonomy, what's preserved, containment design
- [Source: requirements/ses-email-consolidation.md#4.1, #4.2, #4.3, #4.5] — file-by-file inventory this story implements a subset of
- [Source: requirements/ses-email-consolidation.md#5 Phase 1] — this story's exact scope and exit criteria
- [Source: requirements/ses-email-consolidation.md#6.1, #6.3, #6.4, #6.6, #6.7, #6.11] — corner cases this story's ACs encode
- [Source: requirements/ses-email-consolidation.md#7.2 items 1, 2, 3, 5, 8, 9, 16b, 16c, 16d, 17] — the subset of the documented test plan in scope this phase (item 9, `NoHardcodedSenderTest`, has an explicit task — Task 9 — with the dev-default carve-out it needs)
- [Source: infrastructure/blobstore/config/BlobstoreConfig.java:40-56] — credentials-provider pattern to mirror in `SesConfig`
- [Source: infrastructure/ses/SesConditionalWiringTest.java, SesEnabledPropertyValidatorTest.java] — existing test patterns this story's new tests follow
- [Source: src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java, IntegrationTestConventionTest.java] — context-sharing convention `SesEmailEndToEndIT` must respect
- [Source: docker-compose.yml:35-42, docker-compose.local.yml:26-40] — "empty is not unset" compose convention this story's env-var additions must respect
- [Source: resolved jars — sdk-core-2.54.13.jar, sesv2-2.54.13.jar, aws-core-2.54.13.jar] — verified exception hierarchy (`SdkClientException`/`ApiCallTimeoutException` are siblings of, not subtypes of, `SesV2Exception`), verified class name (`MessageRejectedException`), verified deprecated/replacement API (`RetryPolicy` deprecated, `AwsRetryStrategy.doNotRetry()` current)

### Known remaining follow-ups for Phase 5 (not this story, noted so they aren't rediscovered)

- Phase 5's uat flip to `transport=ses` will require `APP_SES_FROM_ADDRESS` to actually be set for uat at that point (this story leaves it validated-but-unenforced there, since uat stays on `log`) — a second env-file coordination event, same shape as this story's prod one.

### Second review pass (story-review.md #2) — disposition

Applied as test-rigor and operational-clarity improvements (folded into the ACs/Tasks above): the compose-file guardrail test for `APP_EMAIL_TRANSPORT` (Task 9), the sandbox-rate startup WARN on `SesProperties` (AC7), the unknown-exception javadoc/WARN-logging requirement on `SesErrorClassifier` (AC5), the collision-loop-exhaustion and directory-removed WARN messages on `LoggingEmailSender` (AC9), the path-traversal sanitisation test (Task 5), the `EmailAddressParser` test strengthened to actually prove `.validate()` runs (Task 3), the exact `PlayerRegistrationEmailListener` snippet (AC11), and the UUID/sendId cross-phase note (Dev Notes above). Task 6 was rewritten to unambiguously separate the two required test mechanisms rather than leaving the split implicit.

Two findings were downgraded from "Blocker" after verification, and one was rejected outright as factually incorrect:

- **Not adopted as a design change: the "sequencing gap" between `SesProperties` binding and `SesPropertiesValidator` running.** The review is right that a structurally malformed value (`max-send-rate-per-second: not-a-number`) fails at Spring's own binding stage with a generic-but-property-naming message, before `SesPropertiesValidator` ever runs — but this is standard `@ConfigurationProperties` behaviour in every Spring Boot app, including every other `@ConfigurationProperties` class already in this codebase (`BlobstoreProperties`, `SmtpProperties`, etc.), not a defect this story introduces or could meaningfully "fix" without reimplementing Spring's own type binding. Its suggested remedy — "ensure Spring's binding is strict for numeric fields" — is already the default; there is nothing to add. What *was* adopted: a one-line javadoc clarification on `SesPropertiesValidator` naming the boundary (binding errors are Spring's job; this validator only checks already-bound values), and a test that documents rather than works around the boundary.
- **Downgraded from Blocker to a test-clarity fix: `EnvironmentPostProcessor` ordering "not enforced."** The ordering assumption (`LOWEST_PRECEDENCE`, must run after `ConfigDataEnvironmentPostProcessor`) is Spring Boot's own documented, stable contract — the same one `SesEnabledPropertyValidator` already relies on in production today — not a fragile guess this story invents. There was a real, narrower gap underneath the "Blocker" framing, though: Task 6, as first written, didn't clearly say which of its two test mechanisms would actually prove the "absent → falls back to base default, no abort" behaviour, and an `ApplicationContextRunner`-based attempt at that assertion would silently prove nothing (it doesn't load `application.yaml` via the real boot sequence, and separately, Spring's own enum-binding rejection can produce a "context failed" result that has nothing to do with this story's validator — see the new Dev Notes paragraph above). That real gap is fixed; the "Blocker" severity was not warranted for what is, underneath, a missing test-mechanism boundary in an already-correct design.
- **Rejected — factually backwards, not applied: LOW-1's suggested justification for why `DevSesEmailService` was already inert.** It proposed *"`DevSesEmailService` is `@Profile("!dev")`, so it never creates on dev even if `enabled=true`"* — verified against `DevSesEmailService.java:23` directly: the class is `@Profile("dev")` (the opposite). The story's existing explanation (the trap is that `SesEmailServiceImpl` is `@Profile("!dev")`, leaving zero real `SesEmailService` implementations under `dev` even when `enabled=true`, while `SesConfig`'s `SesV2Client` bean — which carries no profile restriction — still tries to build a real AWS client dev never uses) was already correct and is unchanged.

Everything else in that review (the two other blockers' concrete asks stripped of the "sequencing gap"/"not enforced" framing, all four highs, all three mediums, and the two lows not addressed above) was accurate and is reflected in the ACs/Tasks directly.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code)

### Debug Log References

- `SesEmailSenderTest` stubbing: `SesV2Client.sendEmail(SendEmailRequest)` is itself a default
  interface method whose body throws `UnsupportedOperationException` (the real implementation lives
  only in `DefaultSesV2Client`). Mocking the whole client with `CALLS_REAL_METHODS` therefore breaks
  during `when(...)` stub registration. Fix: leave the mock on Mockito's normal default answer, and
  `when(client.sendEmail(any(Consumer.class))).thenCallRealMethod()` for the `Consumer` overload
  only — its real body builds a `SendEmailRequest` and re-enters the mock via the `SendEmailRequest`
  overload, which is then stubbed normally.
- `EmailTransportBootIT`: `SpringApplicationBuilder.properties(String...)` adds a
  lowest-precedence "defaultProperties" source, which `application.yaml`'s base default
  (`app.email.transport: log`) shadows — every override via that method was silently defeated.
  Fixed by passing overrides as `--key=value` command-line args instead (`.run(args)`), which take
  precedence over `application.yaml`.
- `NoHardcodedSenderTest.noAppEmailTransportEnvVarInComposeFiles`: initially failed against
  `docker-compose.yml` because a comment I added there explaining "don't add APP_EMAIL_TRANSPORT"
  itself contained the literal string the test bans. Reworded the compose-file comment to describe
  the property without spelling out its name; `.env.example`'s equivalent warning is fine since
  that file isn't in the test's scanned list.
- Empirically probed `angus-mail` 2.0.5 (the resolved `jakarta.mail` implementation) to confirm
  AC8/Task 3's claim: the bare, non-strict `new InternetAddress("not-an-address")` constructor does
  **not** throw — only `.validate()` catches it (`"Missing final '@domain'"`). Used as the primary
  `EmailAddressParserTest` case, and confirms `EmailAddressParser` must call `.validate()`
  explicitly, not just `InternetAddress.parse(raw, true)`.
- Confirmed against a live `SesEmailEndToEndIT` WireMock capture that AWS SESv2's `SendEmail`
  operation is `POST /v2/email/outbound-emails` with a JSON body — used to build the IT's request
  assertions without needing the (absent from the resolved jar) `service-2.json` model file.

### Completion Notes List

- Implemented all 7 tasks (Task 4b folded into Task 4) exactly per the story's ACs; no scope
  deviations beyond what the story itself already called out as deliberate (base `log` default,
  `smtp` rejected this phase, dev/uat moved to `log` one phase early, `DevSesEmailService` deletion
  pulled forward).
- `SesErrorClassifier`'s WARN-on-unknown-exception logging lives in the classifier itself (where the
  classification decision is made), not literally inside `SesEmailSender` as AC5's prose names it —
  this avoids duplicating the classification `instanceof` chain in two places while still satisfying
  the AC's actual intent (the WARN fires before the exception is wrapped and is visible to anyone
  watching logs). `SesEmailSenderTest.unknownSdkException_logsWarnBeforeWrapping` verifies the
  observable behaviour either way.
- `EmailTransportProperties` binds `app.email.log.outbox-dir` via a nested `Log` static class
  (AC9b's first option), not a separate `@ConfigurationProperties("app.email.log")` class — avoids
  an extra properties bean for a single field.
- All 9 tasks' unit/integration tests run and pass locally (see below); additionally re-ran the
  three pre-existing registration-flow ITs (`CoachRegistrationResourceIT` 24/24,
  `ParentRegistrationResourceIT` 20/20, `PlayerRegistrationResourceIT` 15/15) end-to-end against
  Testcontainers to confirm the real registration flow still works through the new port with no
  regressions, and confirmed no stray files land under `target/mails` (AC9/Task 9 H4 — the
  `application-test.yaml` blank `outbox-dir` override is effective).
- Did not run `mvn verify` locally, per `docs/validation-strategy.md` (this repo's persistent
  `/bmad-dev-story` policy) — targeted test classes only. GitHub CI is the full-verification gate.

### File List

**Created:**
- `src/main/java/com/softropic/skillars/infrastructure/email/OutboundEmailSender.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/OutboundEmailRequest.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/OutboundEmailResult.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportException.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportTransientException.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportPermanentException.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransport.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportProperties.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailTransportPropertyValidator.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/EmailAddressParser.java`
- `src/main/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSender.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailSender.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesErrorClassifier.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesPropertiesValidator.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/OutboundEmailRequestValidationTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportPropertyValidatorTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/EmailAddressParserTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/TransportWiringTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportBootIT.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportProfileDeclarationTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSenderTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/email/log/LoggingEmailSenderCollisionTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesEmailSenderTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesErrorClassifierTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesAddressValidationTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesPropertiesValidationTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesEmailEndToEndIT.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/NoHardcodedSenderTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/listener/ParentRegistrationEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/listener/PlayerRegistrationEmailListenerTest.java`

**Modified:**
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesConfig.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesProperties.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/ParentRegistrationEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/PlayerRegistrationEmailListener.java`
- `src/main/resources/META-INF/spring.factories`
- `src/main/resources/application.yaml`
- `src/main/resources/application-dev.yaml`
- `src/main/resources/application-uat.yaml`
- `src/main/resources/application-prod.yaml`
- `src/test/resources/application-test.yaml`
- `docker-compose.yml`
- `docker-compose.local.yml`
- `.env.example`
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`

**Deleted:**
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailService.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailServiceImpl.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/NoOpSesEmailService.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/exception/SesException.java` (and the
  now-empty `exception` package)
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesEnabledPropertyValidator.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/DevSesEmailService.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesEnabledPropertyValidatorTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesConditionalWiringTest.java`

## Change Log

- 2026-09-11: Implemented Phase 1 of the SES/SMTP transport consolidation. Introduced the
  transport-neutral `OutboundEmailSender` port, exception taxonomy, and `EmailTransport` selector
  with fail-fast `EmailTransportPropertyValidator`; rebuilt the SES adapter as `SesEmailSender`
  behind the port with `SesErrorClassifier` and a conditionally-created `SesPropertiesValidator`;
  added `LoggingEmailSender` as the dev/uat/test transport, replacing `NoOpSesEmailService`; pointed
  the three registration listeners at the port; deleted `SesEmailService`/`SesEmailServiceImpl`/
  `NoOpSesEmailService`/`SesException`/`SesEnabledPropertyValidator`/`DevSesEmailService` and their
  now-obsolete tests; updated all four environment profiles, both compose files, and `.env.example`.
  17 new test classes carrying 92 `@Test`/`@ParameterizedTest` methods, which expand to 136
  executions across the 15 non-IT classes (plus `SesEmailEndToEndIT` and `EmailTransportBootIT`),
  all green; re-verified the three registration-flow ITs end-to-end. Status → review.
  (The original "61 tests across 16 classes" was measured wrong and was corrected at code review
  2026-09-11; the figures above were counted off the working tree and off a real Surefire run.)

- 2026-09-11 — Code review (/bmad-code-review, 3 layers: Blind Hunter / Edge Case Hunter / Acceptance Auditor). 5 decision-needed (all resolved by owner), 24 patches (all applied), 5 deferred to deferred-work.md, 5 dismissed as false positives with recorded evidence. Highest-value findings, both reproduced against the resolved jars rather than reasoned about: (a) RFC 822 group syntax bypassed EmailAddressParser's multi-recipient guard — `InternetAddress.parse("undisclosed: a@x.com, b@y.com;", true)` returns length 1 with isGroup()=true, so `parsed.length != 1` was dead for that whole input class and the class's own documented promise was false; (b) LoggingEmailSenderTest's two blank/unset outbox tests asserted against a @TempDir the sender was never given, so both stayed green under the exact mutation they existed to catch (removing !isBlank() makes Path.of("") resolve to the module root and every registration IT writes OTP emails into the repo) — reproduced, then fixed and re-verified RED on that mutation. Also: 429/403 were classified permanent (now consult the SDK's isThrottlingException/isClockSkewException, verified against aws-core 2.54.13); transport=ses booted green with unresolvable credentials; app.ses.region had no validation. Owner decisions: keep the SDK default retry until Phase 4 (the AC6 rationale is forward-looking and apiCallTimeout already bounds retries), fail fast on credentials, carve EmailAddressParser out of §3.4 with a Phase 6 note, mask recipients at INFO, and add the APP_SES_FROM_ADDRESS row to secrets-reference.md. 136 test executions green.

