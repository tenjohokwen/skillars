# SES Email Consolidation — Single Outbound Email Transport

**Status:** Proposed
**Date:** 2026-09-11 (revised after decision round 1 — see §9)
**Scope:** `infrastructure/ses`, new `infrastructure/email`, `platform/notification`,
`platform/security/infrastructure/listener`, config, deployment, tests

---

## 1. Goal

Every outbound email in Skillars must leave through **one** transport, owned by the
`infrastructure` layer, with AWS SES as the destination transport. Today there are two
independent stacks plus a bridge between them (§2.1); this plan collapses them to one.

### 1.1 Staging, per the decisions in §9

The sending domain is **not yet chosen** (`skillars.com` is unavailable), and SES cannot be
verified until it is. So the migration is staged rather than a single cutover:

| Environment | Transport now | Transport after the domain decision |
|---|---|---|
| **prod** | **SES** — everything, from the first phase. Prod is not live, so there is no traffic to protect, and it must never acquire an SMTP dependency it would later have to shed | SES (unchanged) |
| **uat** | Gmail SMTP — keeps UAT usable for end-to-end testing today | SES, own identity + configuration set |
| **dev** | Gmail SMTP | rendered HTML to disk (§6.11) |
| **test** | none — see §4.4 note | none |

The consequence that shapes everything below: **SMTP is not deleted in this plan; it is
contained.** It moves behind the same port as SES, into one self-contained package with no
references from anywhere else, so that flipping a single property switches transport and a
single commit deletes SMTP entirely when the time comes. §3.4 specifies that containment and
§5 Phase 6 is the pre-written removal commit.

> **What this is not.** `MailManager` and `MailService` are **not** removed, and neither are
> the 31 Thymeleaf templates or the localised subject bundles. The cut is one layer *below*
> `MailService`. Templating, locale selection and durability are unaffected by design — §3.3
> states exactly what is preserved and where each piece lives afterwards.

---

## 2. Why this change is needed

### 2.1 There are two independent email stacks today

**Stack A — SMTP (the notification module).**

```
publisher.publishEvent(Envelope)
  └─ MailManager.sendEmailFromTemplate / sendEmailSync      (circuit breaker + RetryTemplate)
       └─ MailService.sendEmailFromTemplate                 (Thymeleaf + MessageSource)
            └─ MailService.sendEmail                        (MimeMessage)
                 └─ SenderProvider.nextSender()             (MailSenderProvider, round-robin)
                      └─ JavaMailSenderImpl → GMX or Gmail SMTP
```

Everything Envelope-shaped goes through it: bookings, reschedules, session packs, video
moderation, account changes, performance reports, ops alerts, activation and password-reset
mail (`AccountManagementFacade`, `EmailRegistrationStrategy`, `SendMailListener`), the
durable outbox (`NotificationEmailOutboxHandler`) and the retry scheduler
(`EmailRetryScheduler`).

**Stack A is itself two durability models, not one** — worth stating precisely, because the
rest of this plan says "route it like booking mail" and that phrase has to mean something:

| How the `Envelope` reaches `MailManager` | Producers | Crash-safe? |
|---|---|---|
| **Outbox** — `NotificationOutboxSupport.enqueueEmail` at `BEFORE_COMMIT`, row committed with the business transaction, delivered by `NotificationEmailOutboxHandler` | `BookingEmailListener` (20 call sites), `SessionPackEmailListener` (3) — verified: these are the *only* two callers of `enqueueEmail` | Yes |
| **Direct publish** — `ApplicationEventPublisher.publishEvent(Envelope)` inside an open transaction, consumed by `MailManager`'s own `@TransactionalEventListener(AFTER_COMMIT)` | `AccountManagementFacade`, `EmailRegistrationStrategy`, `SendMailListener` (activation, password reset, 2FA), `AccountChangeEmailListener`, `VideoModerationEmailListener`, `AlertNotificationListener`, `ReportGenerationService` | No — this is the older pattern whose nested-`AFTER_COMMIT` silent-drop hazard `NotificationOutboxSupport`'s own javadoc documents |

Both models land in `EnvelopeEntity` **once `sendEmailSync` actually runs**, and from that
point share retries, deadline and circuit breaker. The difference is upstream: on the
direct-publish path, a crash between the business commit and the async send loses the email
with no row ever written, so there is nothing for `EmailRetryScheduler` to find.

**This plan does not change that split.** Phase 4 moves registration/OTP mail onto the
*outbox* model — the stronger one — but activation, password-reset and 2FA mail stay on
direct publish. Closing that is a separate story; it is called out here so "every other email
already has durability" is not read as more than it is.

**Stack B — AWS SES (the ses module).**

```
@TransactionalEventListener(AFTER_COMMIT)
  └─ Coach/Parent/PlayerRegistrationEmailListener
       ├─ SpringTemplateEngine.process(...)   (hand-rolled, duplicated three times)
       ├─ MessageSource.getMessage(...)
       └─ SesEmailService.send(to, subject, html)  → SesV2Client.sendEmail
```

Exactly six emails use it: coach/parent/player **email verification** and **OTP**.

**Stack C — the bridge.** `notification.infrastructure.DevSesEmailService` implements the
SES port by calling the SMTP `MailService`, so under the `dev` profile Stack B secretly *is*
Stack A. A `platform` class implementing an `infrastructure` port and calling back into its
own sibling service is a layering inversion that exists only to paper over the split.

### 2.2 Concrete defects the split causes

| # | Defect | Evidence |
|---|--------|----------|
| D1 | **The sender address is not a configurable property of the platform — it is a side effect of which SMTP connection a round-robin counter picked.** `MailService:47` sets `From` to `javaMailSender.getUsername()`, i.e. whichever provider `MailSenderProvider.nextSender()` returned, alternating per send. The SES path meanwhile uses `SesProperties.fromAddress`. There is no single place that answers "who does Skillars send mail as?" | `MailService:47`, `MailSenderProvider.nextSender`, `application.yaml:144-157`, `SesProperties.fromAddress` |
| D2 | **Email authentication is structurally unachievable for the SMTP half.** SPF/DKIM/DMARC alignment requires the sending domain to be one the platform controls and has published DNS for. Mail sent through a third-party consumer SMTP account authenticates as *that provider's* domain, so no `From` the platform chooses can align — a direct spam-folder risk that cannot be fixed by configuration. Consolidating on SES makes the sending domain a deliberate, verifiable choice (§6.7) | consequence of D1 |
| D3 | **Registration and OTP email has no durability at all.** The three SES listeners catch `SesException` and log. No `EnvelopeEntity` row, no retry, no outbox, no alert. The listeners' own comments admit the consequence: *"registration may be orphaned"*, *"user is EMAIL_VERIFIED but OTP unreachable; resend-OTP endpoint required"*. Every other email at least reaches `EnvelopeEntity` and so gets 1 + 5 retries with a deadline (see §2.1 for the two durability models this path is below) | `CoachRegistrationEmailListener:47`, `PlayerRegistrationEmailListener:73` |
| D4 | **UAT silently drops registration mail.** `application-uat.yaml` sets `app.ses.enabled: false`, so `NoOpSesEmailService` wins and verification/OTP mail is logged and discarded — while booking mail still leaves via Gmail. Nobody testing registration in UAT receives anything. Fixed early here: under one transport, UAT sends *all* of it over SMTP until the SES flip | `application-uat.yaml:23-25` vs `application-prod.yaml:2-4` |
| D5 | **Thymeleaf composition is duplicated four times.** `MailService.sendEmailFromTemplate` plus one hand-rolled copy in each of the three registration listeners — same `Context`, same `recipient`/`map` variable names, same `MessageSource` lookup, independently maintained | the three listeners vs `MailService:58-77` |
| D6 | **Health monitoring watches the wrong thing.** `SmtpHealthIndicator` (270 lines, a TLS prober, a thread pool, a TTL cache) reports on GMX and Gmail reachability. Nothing checks whether SES can send — and prod is about to run entirely on SES | `SmtpHealthIndicator`, `application.yaml:409-410` |
| D7 | **Error classification is SMTP-only.** `MailManager.NON_REPAIRABLE_ERRORS` lists `MailParseException`, `MailPreparationException`, `AddressException`, `ParseException` — all jakarta.mail/Spring-mail types. A SES failure can never be classified non-repairable, so a permanently invalid address would burn all six scheduler retries | `MailManager:41-44` |
| D8 | **Three SMTP secrets provisioned everywhere.** `SPRING_MAIL_PASSWORD`, `GMX_PASSWORD`, `GMAIL_PASSWORD` in every compose file, `.env.example` and deployment docs. One of the three (`SPRING_MAIL_PASSWORD`) is for a `JavaMailSender` nothing uses — removable immediately (D9) | `docker-compose*.yml`, `.env.example:47-60` |
| D9 | **A whole `spring.mail.*` block is dead weight.** `application.yaml:118-128` configures a `JavaMailSender` bean that exists only because the starter is on the classpath. Nothing injects it — `MailSenderProvider` builds its own `JavaMailSenderImpl` instances from `email.providerConfigs`. Deletable in Phase 2 with zero effect on the Gmail path | `application.yaml:118-128` |

### 2.3 What we get

- One transport port with one implementation per environment, instead of two stacks and a
  bridge — and a sender identity set by configuration rather than by which SMTP connection
  came up next.
- One durability model: every email, including registration and OTP, gets envelope
  persistence + circuit breaker + retry + deadline + outbox re-drive.
- One composition path (deletes ~120 lines of duplicated Thymeleaf plumbing).
- A health signal that corresponds to the transport actually in use.
- SMTP reduced from nine files scattered across two modules to one sealed package that one
  commit removes — so the eventual cutover is a property flip, not a project.

---

## 3. Target architecture

```
              ┌──────────────────── platform/notification ──────────────────────┐
Envelope  →   │ MailManager     circuit breaker, RetryTemplate, EnvelopeEntity   │
              │   └─ MailService     orchestration                               │
              │        └─ EmailContentRenderer   Thymeleaf + MessageSource       │
              └───────────────────────────┬─────────────────────────────────────┘
                                          │ OutboundEmailSender  (port)
              ┌───────────────────────────▼──────── infrastructure/email ───────┐
              │ OutboundEmailSender, OutboundEmailRequest/Result                 │
              │ EmailTransportPermanentException / …TransientException           │
              │                                                                  │
              │  ├── smtp/   ⚠ TEMPORARY — sealed package, deleted in Phase 6    │
              │  │     SmtpEmailSender, SmtpProperties, MailSenderProvider,      │
              │  │     SmtpHealthIndicator                                       │
              │  └── log/    LoggingEmailSender  (dev + test)                    │
              └───────────────────────────┬─────────────────────────────────────┘
                                          │ implements
              ┌───────────────────────────▼──────── infrastructure/ses ─────────┐
              │ SesEmailSender, SesConfig, SesProperties, SesErrorClassifier,    │
              │ SesHealthIndicator, SesSendRateLimiter                           │
              └──────────────────────────────────────────────────────────────────┘

                    selected by:  app.email.transport = ses | smtp | log
```

**Rules that make it stay clean:**

1. `platform.*` never imports `software.amazon.awssdk.*`, `jakarta.mail.*` or
   `org.springframework.mail.*`. It knows only `OutboundEmailSender`.
2. `infrastructure.ses` and `infrastructure.email.smtp` never import `platform.*` — no
   Thymeleaf, no `EmailTemplate`, no `Recipient`, no `Envelope`. Their input is a rendered
   message.
3. Exactly one `OutboundEmailSender` bean exists for every value of `app.email.transport`;
   an unrecognised value aborts startup with a one-line message.
4. Everything SMTP lives under `infrastructure/email/smtp/` and nothing outside it references
   any SMTP type (§3.4).

**Why a neutral port rather than keeping `SesEmailService`.** For the window where two
transports coexist, an `SmtpEmailSender implements SesEmailService` would be an interface
whose name lies about half its implementations — the same smell as today's
`DevSesEmailService`. A neutral port also means `platform.notification` depends on
`infrastructure.email`, so when SMTP dies **nothing in `platform` changes**. Cost is a
one-time rename touching three listeners. (Considered and rejected: keeping the
`SesEmailService` name to avoid churn.)

### 3.1 Port API

The current port — `void send(String to, String subject, String htmlBody)` — cannot express
the plaintext path or return a message id. Replace it:

```java
package com.softropic.skillars.infrastructure.email;

/** @param htmlBody null for a plaintext-only message (EmailTemplate.NONE — §6.2).
 *  @param textBody null for an HTML-only message. At least one of the two must be present.
 *  @param correlationId opaque id echoed into logs; SES stamps it as a message tag. */
public record OutboundEmailRequest(String toAddress, String subject,
                                   String htmlBody, String textBody, String correlationId) {
    public OutboundEmailRequest {
        requireNonBlank(toAddress, "toAddress");
        requireNonBlank(subject, "subject");
        requireNonBlank(correlationId, "correlationId");
        if (isBlank(htmlBody) && isBlank(textBody)) {
            throw new IllegalArgumentException("at least one of htmlBody / textBody is required");
        }
    }
}

/** @param messageId transport's id for the accepted message; null for transports that have none. */
public record OutboundEmailResult(String messageId) {}

public interface OutboundEmailSender {
    OutboundEmailResult send(OutboundEmailRequest request);
}
```

**Validate in the compact constructor, not in the adapters.** A record accepts nulls silently
by default, and there are three adapters — unvalidated, each would fail differently and deep
inside its own transport (a `NullPointerException` from `MimeMessageHelper`, a
`ValidationException` from the SES SDK, a `null.html` file from the logging sender), all of
them at send time rather than at the call site that made the mistake. Checking once at the
boundary makes an invalid request impossible to construct, and the adapters need no defensive
re-checks.

Note that `htmlBody` is deliberately **not** unconditionally required: the
`EmailTemplate.NONE` path (§6.2) is plaintext-only, so the rule is "at least one body", not
"html always present". `correlationId` *is* required — `LoggingEmailSender` names files by it
(§4.1) and SES stamps it as a message tag, so a null would be silently lossy in two places.

`messageId` is what correlates a send with a later bounce/complaint event, so it must be
returned and logged (and, in a follow-up, persisted on `EnvelopeEntity`).

**No idempotency guarantee.** Neither transport deduplicates: a retry is a *potentially
duplicate delivery*, not a resumed one. `EmailRetryScheduler` re-sends whole envelopes, and
`NotificationEmailOutboxHandler`'s javadoc already accepts this ("a re-drive re-sends —
acceptable per AC3"). Producer-side dedupe stamps — e.g. `SessionPackExpiryNotifier
.expiryWarnedAt` — are what prevent the common duplicate, and any new producer needs its own.
Stated here because a port that returns a `messageId` invites the assumption that it is an
idempotency key. It is not.

### 3.2 Exception taxonomy

Transport-neutral, because `MailManager` classifies on these and must not know which
transport produced them:

```java
package com.softropic.skillars.infrastructure.email;

public abstract class EmailTransportException extends RuntimeException { ... }
public final class EmailTransportTransientException extends EmailTransportException { ... }  // retry
public final class EmailTransportPermanentException extends EmailTransportException { ... }  // don't
```

Each adapter owns its own mapping. `SesErrorClassifier` (in `infrastructure.ses`):

| SDK condition | Maps to | Reason |
|---|---|---|
| `TooManyRequestsException`, `LimitExceededException` | Transient | throttling, succeeds later |
| `SendingPausedException` | Transient | account-level pause is usually lifted |
| HTTP 5xx, `SdkClientException`, `ApiCallTimeoutException` | Transient | network/service |
| `MessageRejected` | Permanent | address or content rejected outright |
| `MailFromDomainNotVerifiedException`, `AccountSuspendedException` | **Transient** + `[EMAIL_ACCOUNT_BLOCKED]` alert | see below — this is deliberately *not* Permanent |
| `BadRequestException`, other 4xx | Permanent | malformed request |
| Locally-detected invalid address (§6.3) | Permanent | replaces the old `AddressException` signal |
| anything unrecognised | **Transient** | see §7.2 item 2 for why this default |

**Why account-level failures are Transient.** Permanent means `retry=false`, and
`EnvelopeEntityRepository.fetchFailedEmails()` is
`WHERE e.retry = 'true' AND e.status = 'FAILED' … FOR UPDATE SKIP LOCKED` — a `retry=false`
row is invisible to `EmailRetryScheduler` **forever**, with no bulk-recovery path and no
query to find "everything that failed during the incident". Classifying a suspended account
or an unverified domain as Permanent would therefore destroy every email in flight at the
moment of an account-level incident, and destroy it precisely in the scenario §6.10 names as
the reason the retry scheduler matters. The message itself is fine; the *account* is blocked,
and that condition resolves when a human fixes it — which is the definition of transient.

"Must page a human" is a real requirement, but retry classification is the wrong instrument
for it. Instead: log `[EMAIL_ACCOUNT_BLOCKED]` at ERROR with the SES error code, and let
`SesHealthIndicator` (§4.2) report DOWN — `GetAccount` surfaces exactly this. Alerting hangs
off those two signals, and the envelopes survive to be delivered once the block lifts.

Residual risk to accept: while an account block persists, every envelope burns scheduler
attempts against its 6-attempt ceiling and 24-hour deadline. An outage longer than the
deadline still loses mail — but it loses it *after* the system tried, rather than at the
first failure.

**Say this in the classifier's javadoc, not only here.** `[EMAIL_ACCOUNT_BLOCKED]` reads like
a terminal state to whoever is on call, when in fact the affected envelopes are queued and
will drain by themselves once the block lifts. The comment should state: *Transient on
purpose — the message is fine, the account is blocked; envelopes stay `retry=true` so the
scheduler drains them on recovery. Only a block outlasting the envelope deadline loses mail.*
Operators can list what is waiting with
`SELECT send_id, attempts, deadline FROM main.envelope_entity WHERE retry = true AND status =
'FAILED'`, which is the same predicate the scheduler uses; §8 carries the recovery steps for
rows that did age out.

`SmtpErrorClassifier` (in `infrastructure.email.smtp`) preserves today's behaviour exactly:
`MailParseException`, `MailPreparationException`, `AddressException`, `ParseException` →
Permanent; every other `MessagingException` → Transient. This is a pure relocation of
`MailManager.NON_REPAIRABLE_ERRORS`, so SMTP retry semantics are unchanged for dev/uat.

`MailManager.NON_REPAIRABLE_ERRORS` becomes `List.of(EmailTransportPermanentException.class)`.
Its two-level cause walk stays as-is and stays commented — the wrapping depth is unchanged.

### 3.3 What is preserved — templates, language, durability

This migration changes the **transport**. It does not change what an email says, what
language it says it in, or what happens when a send fails.

#### Localised rendering — preserved, relocated within `notification`

The entire i18n mechanism is four lines, all inside `MailService.sendEmailFromTemplate`:

```java
final Locale locale = Locale.forLanguageTag(recipient.getLangKey());            // MailService:61
final Context context = new Context(locale);                                    // MailService:70
final String content = templateEngine.process(                                  // MailService:73
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, emailTemplate.name()), context);
final String subject = messageSource.getMessage(emailTemplate.subjectKey(), null, locale); // :74
```

`Recipient.langKey` — copied from `User.getLangKey()` in `SendMailListener.buildRecipient`,
or carried on the event for registration mail — selects the `Locale`, and that one `Locale`
drives **both** the Thymeleaf body and the `MessageSource` subject lookup. Those four lines
move verbatim into `EmailContentRenderer` (§4.2).

Unchanged by this work:

| Asset | Count / location | Touched? |
|---|---|---|
| Thymeleaf email templates | 31 files in `src/main/resources/mails/` | No |
| Subject bundles | `i18n/messages{,_de,_en,_fr}.properties` | No |
| `EmailTemplate` enum and its `subjectKey()` | `notification/contract` | No |
| `ThymeleafConfiguration` (resolver + engine) | `notification/config` | No |
| The `UPPER_UNDERSCORE → lowerCamel` enum-to-filename convention | `MailService:73` | No |

#### Language survives a retry, too

`EnvelopeMapper.toRecipientEntity` persists `langKey` onto `RecipientEntity`, and
`toRecipient` reads it back when `EmailRetryScheduler` or `ResendEmailService` rebuilds the
`Envelope`. An email re-sent hours later still renders in the recipient's language. Pinned by
a test (§7.2 item 8c).

#### `MailManager` — kept, essentially unchanged

`MailManager` contains **no** template, `Locale`, or `MessageSource` reference. What it owns
is durability, and all of it stays: `EnvelopeEntity` persistence (`status`, `attempts`,
`error`, `retry` — the row `ResendEmailService` and `EmailRetryScheduler` both read), the
circuit breaker, the `RetryTemplate`, async dispatch on `sendMailPool`, and the
retryable/non-repairable classification. Its three direct callers and `TestMailManager` are
unaffected. The only edit is the exception types in `NON_REPAIRABLE_ERRORS`.

#### `MailService` — kept, narrowed

| | Today | After |
|---|---|---|
| Resolve locale from `langKey` | ✓ | ✓ (in `EmailContentRenderer`) |
| Render Thymeleaf body | ✓ | ✓ (in `EmailContentRenderer`) |
| Resolve localised subject | ✓ | ✓ (in `EmailContentRenderer`) |
| `EmailTemplate.NONE` plaintext special case | ✓ | ✓ (in `EmailContentRenderer`) |
| `@Observed(name = "mail.send_from_template")` | ✓ | ✓ |
| Build `MimeMessage` / `MimeMessageHelper` | ✓ | moved to `SmtpEmailSender` |
| Pick an SMTP provider (`SenderProvider`) | ✓ | moved to `infrastructure.email.smtp` |
| Choose a transport | — | **never** — it calls the port |

#### Ownership split after the change

| Concern | Owner |
|---|---|
| Which template, which language, what the body says | `notification` (`EmailContentRenderer`) |
| Envelope persistence, retry, circuit breaker, deadlines, outbox | `notification` (`MailManager`) |
| Credentials, endpoint, rate limit, error classification, delivery | `infrastructure.ses` / `…email.smtp` |

### 3.4 Designing for easy removal

This is a direct requirement from the decision round: *"done such that the switch and code
removal are easy."* Two mechanisms.

**Switching** is one property per environment — `app.email.transport` — with no code change,
no profile coupling and no second knob. It replaces `app.ses.enabled` entirely rather than
sitting alongside it, so there is never a matrix of two flags to reason about.

**Removal** is made a one-commit job by *containment invariants*, enforced by
`EmailTransportArchitectureTest` (§7.2 item 14) so they cannot erode:

| Invariant | Enforced how |
|---|---|
| Only `infrastructure.email.smtp..` may import `jakarta.mail..`, `javax.mail..`, `JavaMailSender` or `org.springframework.mail..` — **with one named carve-out: `infrastructure.email.EmailAddressParser`** | ArchUnit / classpath scan |

> **Carve-out for `EmailAddressParser` (decided at code review of ses-1.1, 2026-09-11).** The
> invariant above targets SMTP *transport plumbing* — `JavaMailSender`, `org.springframework.mail..`
> and friends — so that Phase 6 can delete the SMTP stack wholesale. It is not aimed at a shared,
> transport-neutral address validator that the **SES** adapter also depends on
> (`SesEmailSender.validateRecipient`, `SesPropertiesValidator`). AC8 of ses-1.1 placed
> `EmailAddressParser` in `infrastructure.email` on purpose, which collides with a literal reading
> of this row. Resolution: keep the parser where it is and carve it out by name, so Phase 2's
> `EmailTransportArchitectureTest` (§7.2 item 14) is written expecting the exception rather than
> failing on it.
>
> Rejected alternatives: moving the parser under `infrastructure.email.smtp` inverts the dependency
> (SES would import from the package Phase 6 deletes), and reimplementing it without `jakarta.mail`
> means hand-rolling RFC 5322 against the class's own "no second regex anywhere" rule — a regex
> would also miss the RFC 822 group-syntax case that code review reproduced against the real
> `angus-mail` jar.
>
> **Phase 6 consequence:** step 5 removes `spring-boot-starter-mail`, which is what currently
> supplies `jakarta.mail`. Phase 6 must therefore add `jakarta.mail-api` + `angus-mail` as **direct**
> dependencies rather than let the parser lose its dependency with the starter.
| No class outside `infrastructure.email.smtp..` references `SmtpEmailSender`, `SmtpProperties`, `ProviderConfig`, `MailSenderProvider` or `SmtpHealthIndicator` | same |
| Only `infrastructure.ses..` may import `software.amazon.awssdk..` | same |
| All SMTP configuration lives under the single `app.email.smtp` YAML key | `NoStraySmtpConfigTest` |
| The SMTP transport is reachable only via the `smtp` value of `app.email.transport` | wiring test |

**The removal commit, pre-written.** When D-1 lands and both uat and dev have moved off SMTP,
Phase 6 is exactly:

1. `rm -rf src/main/java/com/softropic/skillars/infrastructure/email/smtp/`
   and its test mirror.
2. Delete the `smtp` constant from the `EmailTransport` enum; the validator's accepted set
   shrinks with it.
3. Delete the `app.email.smtp` block from `application.yaml`, `application-dev.yaml`,
   `application-uat.yaml`.
4. Delete `GMX_PASSWORD` / `GMAIL_PASSWORD` from four compose files and `.env.example`.
   (`SPRING_MAIL_PASSWORD` is already gone — Phase 2, D9.)
5. Remove `spring-boot-starter-mail` from `pom.xml` — **but add `jakarta.mail-api` + `angus-mail` as direct dependencies in the same step**, because `infrastructure.email.EmailAddressParser` (the §3.4 carve-out) still needs `jakarta.mail` after the SMTP stack is gone.
6. Drop `smtp` from the `notification` health group.

**Nothing under `platform/` is touched by that commit.** If a Phase 6 diff shows a change
under `platform/`, containment leaked and the architecture test failed to catch it — treat
that as a defect in this design, not as a normal cost of removal.

---

## 4. File-by-file inventory

### 4.1 `infrastructure/email` — new

| File | Notes |
|---|---|
| `OutboundEmailSender`, `OutboundEmailRequest`, `OutboundEmailResult` | The port (§3.1) |
| `EmailTransportException` + `…Transient` / `…Permanent` | §3.2 |
| `EmailTransport` (enum: `SES`, `SMTP`, `LOG`) | The selector value |
| `EmailTransportProperties` | `@ConfigurationProperties("app.email")` — holds `transport` |
| `EmailTransportPropertyValidator` | `EnvironmentPostProcessor`, same role and rationale as today's `SesEnabledPropertyValidator`: an unrecognised value must abort with a one-line message, not a `NoSuchBeanDefinitionException` deep in a bean-creation stack trace. **Must be registered in `src/main/resources/META-INF/spring.factories`**, replacing the `SesEnabledPropertyValidator` entry on line 3 — an `EnvironmentPostProcessor` is found through that file, never by component scan. Forgetting it is silent: the validator simply never runs, which is worse than not having one, since its entire purpose is to fail fast |
| `log/LoggingEmailSender` | `transport=log`. Logs recipient + subject + correlation id; when `app.email.log.outbox-dir` is set, writes the rendered HTML to `<dir>/<correlationId>.html` so dev can open real emails in a browser (§6.11). **Never throws** — a dev convenience must not be able to fail a send, so an unwritable directory logs a warning once and degrades to log-only. Create the directory at startup rather than at first send, so a permission problem surfaces immediately. **Filenames must not collide**: a retry deliberately reuses the `sendId`-derived correlation id, so a bare `<correlationId>.html` silently overwrites the earlier attempt — destroying exactly the artifact §6.11 sells. Open with `StandardOpenOption.CREATE_NEW` and, on `FileAlreadyExistsException`, retry as `<correlationId>-2.html`, `-3`, … The `CREATE_NEW` failure *is* the concurrency check, so no separate exists-test is needed and two threads cannot both win. Do not fall back to random or temp-file names: the whole value is that the file is findable by the id in the log line |
| `smtp/SmtpEmailSender` | ⚠ temporary. The `MimeMessage` construction from `MailService.sendEmail` moves here verbatim |
| `smtp/MailSenderProvider`, `smtp/SmtpProperties`, `smtp/ProviderConfig` | ⚠ temporary. Moved from `platform/notification`, package-private where possible. `SenderProvider` is folded into `SmtpEmailSender` — the indirection had one implementation |
| `smtp/SmtpErrorClassifier` | ⚠ temporary. Preserves today's classification exactly (§3.2) |
| `smtp/SmtpHealthIndicator`, `smtp/SmtpHealthProperties` | ⚠ temporary. Moved unchanged from `platform/notification/health`; now gated on `transport=smtp` |

### 4.2 `infrastructure/ses` — changed / added

| File | Action |
|---|---|
| `SesEmailService` | Renamed `SesEmailSender`, implements `OutboundEmailSender`. Gated on `app.email.transport=ses` |
| `SesEmailServiceImpl` | Folded into `SesEmailSender`. Sets `simple` content with both `html` and (when present) `text` parts; adds `configurationSetName`, `replyToAddresses` and an `emailTag` carrying the correlation id; returns `OutboundEmailResult(response.messageId())`; classifies failures via `SesErrorClassifier`. Drops `@Profile("!dev")` (§6.1) |
| `NoOpSesEmailService` | **Deleted** — superseded by `LoggingEmailSender`, which is transport-neutral and does strictly more |
| `SesConfig` | Add an explicit `AwsCredentialsProvider` mirroring `BlobstoreConfig:40-56` (static keys when configured, default chain when blank — required because UAT runs on Hostwinds with no instance profile); `ClientOverrideConfiguration` with `retryPolicy(numRetries = 0)`, `apiCallTimeout = 5s`, `apiCallAttemptTimeout = 3s` (§6.4); optional `endpointOverride` |
| `SesProperties` | Add `accessKey`, `secretKey`, `configurationSet`, `endpointUrl`, `maxSendRatePerSecond`, `replyToAddress`. Remove `enabled` (replaced by `app.email.transport`). The sending domain is undecided (D-1), so nothing here may assume one. **Validate every new value at startup, not at first send** — while `transport=ses`: `fromAddress` non-blank and parseable in either accepted shape (§6.7); `replyToAddress`, when set, parseable the same way; `configurationSet`, when set, matching SES's naming rules (1–64 chars, alphanumerics plus `-` and `_`); `maxSendRatePerSecond` positive. Each of these otherwise fails on the first real email, which in prod means discovering it from a bounced signup |
| `EmailAddressParser` | **New**, in `infrastructure.email`. One implementation of "parse an address, accepting `user@domain` and `Display Name <user@domain>`, return the bare address", used by `SesProperties` validation for `fromAddress`/`replyToAddress` and by the recipient check in §6.3. Three copies of RFC 5322 handling that disagree at the edges is exactly how a display-name `from` passes validation and then fails the recipient check |
| `SesEnabledPropertyValidator` | **Deleted**, replaced by `EmailTransportPropertyValidator` (§4.1). Its rationale carries over verbatim and should be preserved in the new class's javadoc |
| `SesErrorClassifier` | **New** (§3.2) |
| `SesHealthIndicator` | **New.** `GetAccount` → UP when `sendingEnabled`; details carry `sendQuota` / `sentLast24Hours`. TTL-cached (reuse the `AtomicReference<Cached>` pattern from `SmtpHealthIndicator`) and bounded by the SDK `apiCallTimeout`. Gated on `transport=ses`, so an SMTP environment does not report an SES failure |
| `SesSendRateLimiter` | **New** (§6.5) |

### 4.3 `platform/notification` — changed

| File | Action |
|---|---|
| `service/MailService` | **Kept**, narrowed to composition + delegation. Delete `SenderProvider`/`MimeMessage`/`JavaMailSenderImpl` usage; `sendEmail(...)` and `sendEmailFromTemplate(...)` stop throwing `MessagingException`. New body: render via `EmailContentRenderer`, call `outboundEmailSender.send(...)`, log the returned `messageId`. Keeps `@Observed(name = "mail.send_from_template")` |
| `service/EmailContentRenderer` | **New.** `RenderedEmail render(Recipient, EmailTemplate, Map<String,Object>)` → `(subject, htmlBody, textBody)`. Receives `MailService:61,70,73,74` verbatim, plus the `EmailTemplate.NONE` plaintext special case. Used by `MailService` *and* the registration path, killing D5 |
| `service/MailManager` | **Kept.** Single edit: `NON_REPAIRABLE_ERRORS` → `List.of(EmailTransportPermanentException.class)`, dropping the `catch (MessagingException)` arm and the four jakarta.mail/spring-mail imports. No change to circuit breaker, retry, async dispatch or envelope bookkeeping |
| `service/SenderProvider` | **Delete** (folded into `SmtpEmailSender`) |
| `infrastructure/MailSenderProvider` | **Move** to `infrastructure/email/smtp/` |
| `infrastructure/DevSesEmailService` | **Delete** — the bridge's whole purpose (let dev send real mail while the SES flag is on) is now served by `transport=smtp` |
| `health/SmtpHealthIndicator` | **Move** to `infrastructure/email/smtp/` |
| `contract/EmailProperties`, `contract/ProviderConfig`, `contract/SmtpHealthProperties` | **Move** to `infrastructure/email/smtp/`, renaming `EmailProperties` → `SmtpProperties` and rebinding to `app.email.smtp` |
| `config/ComponentConfig` | Drop `@EnableConfigurationProperties({EmailProperties, SmtpHealthProperties})` — those properties now belong to the smtp package's own config. Everything else (the `MailManager` bean gate, `RetryTemplate`, circuit-breaker customizer) unchanged |

Two small robustness fixes bundled because this migration makes both paths more load-bearing:

| File | Action |
|---|---|
| `service/ResendEmailService` | `resendEmail(sendId)` maps the lookup result with no existence check, so an unknown or typo'd `sendId` throws a raw NPE from `EnvelopeMapper.toEnvelope` instead of a clear "no such envelope". Manual resend becomes a more load-bearing operational tool once account-level incidents leave envelopes to recover (§3.2), so add the null check and a meaningful error |
| `contract/EmailTemplate` | Phase 4 only: add `deliveryDeadline()` next to `subjectKey()` (§5 Phase 4 item 3) |

**Not touched anywhere else in this module** — listed so a reviewer can confirm blast radius
by absence: `src/main/resources/mails/*.html` (all 31), `i18n/messages*.properties`,
`contract/Recipient`, `contract/Envelope`, `contract/EmailDeliveryStatus`,
`config/ThymeleafConfiguration`, `config/AsyncConfig` (`sendMailPool`), `repo/*`,
`service/EnvelopeMapper`, `service/NotificationOutboxSupport`,
`service/NotificationEmailOutboxHandler`, `service/AlertNotificationListener`,
`infrastructure/EmailRetryScheduler`, and every `infrastructure/listener/*`. Phase 4 revisits
the outbox support and the registration listeners only.

### 4.4 `platform/security/infrastructure/listener` — changed

All three of `CoachRegistrationEmailListener`, `ParentRegistrationEmailListener`,
`PlayerRegistrationEmailListener`: drop `SesEmailService`, `SpringTemplateEngine` and
`MessageSource` injection; delete the hand-rolled `Context` construction and subject lookup;
publish through the same durable path as every other producer (Phase 4).

### 4.5 Configuration

| File | Action |
|---|---|
| `application.yaml` | Remove `spring.mail.*` (118-128) — dead, D9; move `email.providerConfigs` (144-157) under `app.email.smtp.provider-configs`; move `app.notification.smtp-health` (175-182) under `app.email.smtp.health`; health group `notification: include: smtp` → `include: smtp,ses` (both conditional, only the active one registers a bean); add `app.email.transport` with **no default** so every profile states its own |
| `application-dev.yaml` | `app.email.transport: smtp`; keep the provider configs (moved key); replace the `from-address: noreply@skillars.com` literal (30) with `${APP_SES_FROM_ADDRESS:dev@localhost}`; delete the 10-line comment at 31-40 documenting the `@Profile("!dev")` trap, which no longer exists; add `app.email.log.outbox-dir: target/mails` ready for the post-cutover flip |
| `application-uat.yaml` | `app.email.transport: smtp`; keep the provider configs (moved key); `from-address: ${APP_SES_FROM_ADDRESS}` with **no default**. Fixes D4 immediately — UAT starts sending registration mail for the first time. When it flips to `ses` in Phase 5, set `max-send-rate-per-second: 1` if the UAT account is sandboxed (§6.5) |
| `application-prod.yaml` | `app.email.transport: ses`; `from-address: ${APP_SES_FROM_ADDRESS}` with **no default**, so a prod boot without it aborts (§6.7) rather than sending as a placeholder; **no SMTP config at all** — prod never acquires a dependency it would have to shed |
| `src/test/resources/application-test.yaml` | `app.email.transport: log`. **Deliberate reading of the decision** — "tests continue as today" means *no mail is sent*, which is what `enable.test.mail: true` already guarantees by replacing `MailManager` with `TestMailManager`. `log` makes that safe by construction: a test that ever slips past the `TestMailManager` seam writes a file instead of opening a socket to Gmail or calling AWS from CI. Say so if you'd rather it be `smtp` |
| `docker-compose.yml`, `.local.yml`, `.uat.yml`, `.uat-hostwinds.yml` | Remove `SPRING_MAIL_PASSWORD` (D9); keep `GMX_PASSWORD` / `GMAIL_PASSWORD` until Phase 6; add `APP_EMAIL_TRANSPORT`, `APP_SES_FROM_ADDRESS`, `APP_SES_REPLY_TO_ADDRESS`, `APP_SES_REGION`, `APP_SES_ACCESS_KEY`, `APP_SES_SECRET_KEY`, `APP_SES_CONFIGURATION_SET`. `APP_SES_FROM_ADDRESS` gets no `:-` fallback in the uat/prod files |
| `.env.example` | Rework the `--- Email / SMTP ---` block (47-60): drop `SPRING_MAIL_*`, keep the two provider passwords with a comment marking them **temporary, removed when SES goes live**, and add the SES block using the same "leave keys blank to use the default credential chain" wording as the storage block at line 133. Include `APP_SES_MAX_SEND_RATE_PER_SECOND` carrying the §6.5 arithmetic in its comment — **1/s on a sandboxed account**, ~14/s with production access, divided by node count — since leaving the 10/s default on a sandboxed account guarantees throttling on day one |
| `pom.xml` | `spring-boot-starter-mail` **stays** until Phase 6 — `infrastructure.email.smtp` needs jakarta.mail |
| `docs/dev-docs/notification/index.html` | Update the transport section and diagram; document the `app.email.transport` switch |
| `requirements/deployment/*`, `deploy/` docs | Add the SES env rows; mark the SMTP rows temporary |

---

## 5. Phased plan

Each phase compiles, passes CI and is independently mergeable.

### Phase 1 — Introduce the port (no behaviour change)

1. Create `infrastructure/email` with the port, request/result records, the exception
   taxonomy, the `EmailTransport` enum, properties and the fail-fast validator.
2. Rename `SesEmailService` → `SesEmailSender implements OutboundEmailSender`, fold in
   `SesEmailServiceImpl`, add `SesErrorClassifier`, credentials, timeouts, retry policy and
   `fromAddress` validation.
3. Add `LoggingEmailSender`; delete `NoOpSesEmailService`.
4. Point the three registration listeners at the port (still direct calls — Phase 4 moves
   them onto `MailManager`).
5. `app.email.transport` is introduced but every environment is set to a value that
   reproduces today's behaviour.

*Exit:* SES behaves identically for the six registration emails. SMTP untouched.
*Risk:* low — mechanical.

### Phase 2 — Move SMTP behind the port and contain it

1. Extract `EmailContentRenderer` from `MailService` — a pure move of lines 61/70/73/74, with
   the characterization test of §7.2 item 8b written and green **before** the move, so the
   extraction is provably lossless for subject and body in every locale.
2. Create `infrastructure/email/smtp/`: move `MailSenderProvider`, `EmailProperties` →
   `SmtpProperties`, `ProviderConfig`, `SmtpHealthIndicator`, `SmtpHealthProperties`; fold
   `SenderProvider` and `MailService`'s `MimeMessage` code into `SmtpEmailSender`; add
   `SmtpErrorClassifier` preserving today's classification exactly.
3. Reimplement `MailService` on top of `OutboundEmailSender`. Composition is untouched: the
   only difference is that the rendered `(subject, html, text)` goes to the port instead of
   into a `MimeMessage`.
4. Update `MailManager`'s error classification to the neutral types.
5. Delete `DevSesEmailService`.
6. Set transports: **prod `ses`**, uat `smtp`, dev `smtp`, test `log`.
7. Free cleanup: delete `spring.mail.*` and `SPRING_MAIL_PASSWORD` (D9) — verified unused.
8. Land the containment architecture test (§3.4) in this commit, not later, so the invariants
   hold from the moment the package exists.

*Exit:* **all** email in every environment goes through one port. Prod is fully on SES; dev
and uat are fully on SMTP, including registration mail (fixes D4). Nothing is deleted that
uat still needs. **Gate: prod must not serve real user traffic until Phase 3 is live** — until
then prod sends through SES with no throttle protection (§6.5) and no way to see that the
account is sandboxed or suspended (§6.8). This is safe only while prod is not live, which is
an assumption that could change without this plan noticing; hence a stated gate here and in
§10, not just guidance in the rollout narrative.
*Risk:* medium. Prod flips to SES for booking mail — but prod is not live, and §6.7's
fail-fast means a missing `APP_SES_FROM_ADDRESS` stops the boot rather than sending wrongly.

### Phase 3 — Health, monitoring, rate limiting

1. Add `SesHealthIndicator` (gated `transport=ses`) and `SesSendRateLimiter`.
2. Gate the moved `SmtpHealthIndicator` on `transport=smtp`; health group `notification`
   includes both names, and only the active transport contributes a bean.
3. Add transport-tagged metrics (`mail.send` with an `outcome` + `transport` tag) alongside
   the existing `mail.send_from_template` observation.

*Exit:* whichever transport an environment runs, its health is visible.

### Phase 4 — Unify registration email durability (closes D3) — **in scope, per D-5**

Route coach/parent/player verification and OTP through `NotificationOutboxSupport` →
`NotificationEmailOutboxHandler` → `MailManager`, exactly like booking mail:

1. Replace the listeners' direct port calls with
   `@TransactionalEventListener(BEFORE_COMMIT)` + `enqueueEmail(...)`. The `EmailTemplate`
   entries and `mails/*.html` templates already exist and are correct.
2. A failed verification email is no longer lost: retried up to 6 times inside its deadline
   and surfaced as `[OUTBOX_STUCK]`.
3. **OTP deadline — must be shorter than the OTP itself.** The registration OTP TTL is
   **10 minutes**: `otpToken.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))` in
   `CoachRegistrationService:162`, `ParentRegistrationService:166`,
   `PlayerRegistrationService:177` and `RegistrationOtpResendSupport:82`. (Not to be confused
   with `SecurityConstants.OTP_TTL = 30 minutes`, which is the *login* 2FA OTP —
   `LoginInfoService` — a different flow.)

   A delivery deadline must therefore be **well under 10 minutes**, or the retry scheduler
   will cheerfully deliver a code that expired before it landed — a worse experience than not
   sending at all, in the exact flow D3 exists to fix. Recommend **5 minutes**, leaving the
   recipient roughly half the TTL to actually use the code. The 24-hour default stays for
   everything else.

   **Put the deadline on the enum, not the call site.** `NotificationOutboxSupport
   .DELIVERY_DEADLINE` is a single constant applied at `enqueueEmail`; making each producer
   remember to special-case OTP fails silently for the next one that forgets (it would
   default to 24 h, defeating the point). Add `EmailTemplate.deliveryDeadline()` alongside the
   existing `subjectKey()` — same pattern, enforced by construction — and have `enqueueEmail`
   read it. A parity test then pins that every OTP-bearing template returns a value below the
   10-minute token TTL.
4. Distinct `sendId` per email — `EnvelopeEntity.sendId` is `@Column(unique = true)`, so
   reusing one across a registration's verification *and* OTP mail would lose one (§6.15).
   Harden the failure mode at the same time: `sendEmailSync`'s insert path should handle a
   unique-constraint violation gracefully rather than propagating it unclassified (§6.18).
5. **Circuit-breaker scope (D-8).** Routing these listeners through `MailManager` puts them on
   the shared `circuitBreakerFactory.create("emailService")` breaker — 5-call window, 50 %
   threshold — alongside booking, password-reset and everything else. Today their direct
   `catch (SesException)` isolates them completely. Registration/OTP failures are partly
   user-input-driven (typo'd domains during a signup spike), so a burst could trip the breaker
   that gates booking confirmations. Use a separate breaker name for this path unless D-8
   decides otherwise.

*Exit:* one durability model for every email in the system, on either transport.

### Phase 5 — SES cutover (**gated on D-1: the domain decision**)

Blocked until a domain is chosen, verified in SES with DKIM/SPF/DMARC, and the account has
production access. Then, per environment and independently:

1. uat → `transport=ses` with its own identity and configuration set; run §7.3's manual
   checklist against a real inbox.
2. dev → `transport=log` with `outbox-dir` (per D-4).
3. Confirm `/actuator/health/notification` reports SES in both prod and uat.

*Exit:* no environment uses SMTP. This phase changes configuration only — no code.

### Phase 6 — Delete SMTP

The pre-written six-step commit in §3.4. Mechanical, touches nothing under `platform/`.

*Exit:* one transport in the codebase.

### Phase 7 — Documentation

`docs/dev-docs/notification/index.html` and the deployment requirements docs.

---

## 6. Corner cases

### 6.1 Bean wiring must remain total and unambiguous

Today the matrix is: `SesEmailServiceImpl` (`@Profile("!dev")` **and** `enabled=true`),
`NoOpSesEmailService` (`enabled=false` or absent), `DevSesEmailService` (`dev` **and**
`enabled=true`) — and `SesEnabledPropertyValidator` exists precisely because an unrecognised
value leaves *zero* implementations and the app dies in a `NoSuchBeanDefinitionException`.

Replacing two coupled conditions (profile × property) with one enumerated property removes
the trap by construction:

| `app.email.transport` | bean |
|---|---|
| `ses` | `SesEmailSender` |
| `smtp` | `SmtpEmailSender` |
| `log` | `LoggingEmailSender` |
| unset or unrecognised | startup abort, one-line message (`EmailTransportPropertyValidator`) |

No profile appears anywhere in the wiring, which is why `@Profile("!dev")` can go. The wiring
test must assert `hasSingleBean(OutboundEmailSender.class)` in every row — the current
`SesConditionalWiringTest` asserts the type but not the count.

### 6.2 Plaintext email (`EmailTemplate.NONE`)

`AlertNotificationListener` sends ops alerts with `EmailTemplate.NONE`, and `MailService:63-68`
sends those as `isHtml=false`. SESv2 `simple` content has separate `text` and `html` parts, so
the port needs the `textBody` field of §3.1; putting a plaintext body in the `html` part would
collapse the newlines. `EmailContentRenderer` produces `textBody` only (html null) for `NONE`,
html only for every other template, and both adapters must honour the distinction — the SMTP
adapter's `isHtml` flag derives from which field is populated.

### 6.3 Address validation moves from the client to us

`MimeMessageHelper.setTo` threw `AddressException` for a malformed address, which `MailManager`
classified as non-repairable → `retry=false` → one attempt only. The SES SDK does no
client-side parsing: a malformed address becomes `MessageRejected` at the API, and an address
that is merely *wrong* (valid syntax, no such mailbox) is accepted and bounces asynchronously.

1. `SesEmailSender` must validate the recipient before the API call and throw
   `EmailTransportPermanentException`. Without it, one typo'd address costs six SES API calls
   and six envelope rows. Use the shared `EmailAddressParser` (§4.2) so the rule is identical
   to the one applied to `fromAddress`, including RFC 5322 display-name form.
2. A *bounced* address is now invisible to the application — SES accepts and reports `SENT`.
   See §6.9.

Note this is an asymmetry between the adapters during the dual-transport window: SMTP fails
fast on a bad address, SES does not. Keep the validation in the SES adapter rather than in
`MailService`, so behaviour is a property of the transport and disappears with it.

### 6.4 Retry multiplication

Three retry layers stack multiplicatively:

```
EmailRetryScheduler (6)  ×  RetryTemplate (3)  ×  AWS SDK default (3)  =  54 API calls
```

The SMTP path never had the third layer. Set `retryPolicy(RetryPolicy.builder().numRetries(0))`
on the SES client override configuration; the application's two layers already implement the
retry semantics we want, with envelope bookkeeping the SDK knows nothing about.

### 6.5 Send-rate quota

SES enforces a per-second send rate (14/s typical with production access; **1/s in the
sandbox**). `sendMailPool` is 3 core / 10 max threads with a 10-deep queue and
`CallerRunsPolicy`, and `EmailRetryScheduler` dispatches a whole batch in a tight loop after
commit — a post-outage retry batch will exceed 1/s trivially.

Throttling surfaces as `TooManyRequestsException` → Transient → retried → more throttling.
Add `SesSendRateLimiter` (Resilience4j `RateLimiter`, `app.ses.max-send-rate-per-second`,
default 10) inside `SesEmailSender` ahead of the API call.

**It must not block.** The obvious shape — acquire a permit, waiting up to a few seconds —
would put a blocking wait inside `MailManager.sendEmailSync`'s transaction, which is exactly
the wrong place (§6.17). Use a **zero-wait acquire**: no permit available → throw
`EmailTransportTransientException` immediately. The envelope is then marked
`FAILED, retry=true` and `EmailRetryScheduler` re-drives it on the next tick, which is
already the correct backpressure mechanism and costs no DB connection while it waits.

This inverts the usual reflex — a rate limiter that queues feels more "correct" — but the
system already owns a durable, persistent queue for undelivered mail. Adding a second,
in-memory, connection-holding queue in front of it buys nothing and costs the thing §6.17
describes.

**Don't let the in-transaction `RetryTemplate` burn attempts on it.** A zero-wait rejection
means "the whole process is at its quota right now", which will still be true 1 second later.
`ComponentConfig`'s `RetryTemplate` (3 attempts, fixed 1 s backoff) would spend all three
inside the transaction for nothing, consuming one of the envelope's 6 scheduler attempts in
~2 seconds. Configure the retry template to **not** retry
`EmailTransportTransientException`s that carry a rate-limit cause, so the envelope goes
straight back to `EmailRetryScheduler` and is re-tried on the next 60 s tick — the timescale
that actually matches the quota window.

**The limit is per instance, and that is the number that matters.** A Resilience4j
`RateLimiter` is in-process. `application.yaml:90` sizes the connection pool for **four
nodes** (`maximum-pool-size: 25 … so we can have up to 4 nodes`), so a default of 10/s per
node is 40/s across the cluster — roughly triple a standard 14/s SES account quota, and the
limiter would be worse than useless because it would report itself as not throttling.

Set `app.ses.max-send-rate-per-second` to **account quota ÷ node count**, document that
division in the property's comment, and note that it must be revisited when either side
changes. A cluster-wide limiter (Redis-backed) is the proper fix and is out of scope; the
division is honest and cheap. Related: limiter state is per-process and resets on restart, so
a rolling deploy briefly permits up to one full allowance per starting node — harmless at
these volumes, but it belongs in the dev docs rather than being rediscovered.

**Sandbox is 1/s, not 14/s.** Any environment on a sandboxed SES account (§6.8) needs
`max-send-rate-per-second: 1` — divided by node count if it runs more than one. Leaving the
10/s default on a sandboxed account guarantees throttling under any burst. Call this out in
`.env.example` and in the uat profile's comment, since it is the likeliest first-day
surprise after the Phase 5 flip.

**Make a rejection distinguishable from a real SES throttle.** Both are Transient and both
land as `FAILED, retry=true`, but one is fixable config and the other is AWS pushing back. Log
the limiter rejection with the configured rate and emit a distinct
`mail.ses.rate_limiter.rejected` counter, separate from the send-outcome metric. Without
that, a misconfigured limit is indistinguishable from an account at quota, and the fix for the
two is opposite.

### 6.6 Circuit breaker and timeout budget

`ComponentConfig` sets `TimeLimiterConfig.timeoutDuration = 10s` and opens the breaker at a
50 % failure rate over 5 calls. The SES client must be bounded well inside that:
`apiCallTimeout = 5s`, `apiCallAttemptTimeout = 3s`, plus the rate-limiter acquire timeout.
The SDK has no `apiCallTimeout` by default, so an unbounded call would sit past the limiter
and produce confusing `TimeoutException`s instead of classified failures.

### 6.7 Sender identity — configurable, not baked in

**The sending domain is an open decision (D-1) and must not be hardcoded anywhere.**
`noreply@skillars.com` appears in `application-{dev,uat,prod}.yaml` today as a literal, but
that domain is unavailable to this project, so the final address is unknown and may differ per
environment. Treat it as pure configuration:

1. `app.ses.from-address` is the single source of truth, resolved from the environment with
   **no baked-in domain**: `${APP_SES_FROM_ADDRESS}`. A literal in committed YAML is how a
   placeholder domain reaches production.
2. **Fail fast.** Blank while `transport=ses` aborts startup. A misconfigured environment must
   refuse to start rather than send from a stale address and burn a new domain's reputation on
   day one. This is what makes Phase 2's prod flip safe despite D-1 being open.
3. **Support a display name.** SESv2 `fromEmailAddress` accepts RFC 5322 form, so
   `Skillars <noreply@example.org>` is valid and is what inboxes render. Accept both shapes and
   validate accordingly.
4. **Per-environment identities.** UAT and prod get different addresses and different SES
   configuration sets (§6.9), so UAT traffic never affects the production domain's reputation.
5. `app.ses.reply-to-address`, optional and unset by default — a `noreply@` sender with no
   reply path is a common support complaint, and adding the field now costs one line.

**Prerequisites once the domain is chosen**, all verified before Phase 5:

- The address or its parent domain is a verified identity in SES in `app.ses.region`.
- DKIM (Easy DKIM, 3 CNAMEs) published and showing *verified*.
- SPF: the domain's TXT record includes `include:amazonses.com`.
- DMARC present, at least `p=none` with `rua` reporting, so authentication results are
  observable from the first send.

**Lead time is the real risk.** Registering a domain, pointing DNS and waiting for DKIM
confirmation takes anywhere from an hour to 72. Staging the plan this way means that lead time
no longer blocks any *code* work — only Phase 5, which is configuration.

The specific addresses the SMTP path used are not worth preserving or migrating; no support
flow depends on replies to them.

### 6.8 SES sandbox and account state

A SES account without production access can only send to *verified* recipients, capped at 200
messages/day and 1/s. With prod on SES from Phase 2, a sandboxed account means prod cannot
mail real users — acceptable only because prod is not live. **Production access must be
granted before prod serves real traffic**, and it is a hard gate for Phase 5's UAT flip.
`SesHealthIndicator`'s `GetAccount` check surfaces exactly this, which is a reason to land
Phase 3 before anyone relies on prod email.

### 6.9 Bounces and complaints become invisible

SMTP surfaced a hard bounce synchronously often enough to be noticed. SES accepts, then
bounces asynchronously to an SNS topic. So:

- A permanently bad address is recorded `SENT` in `EnvelopeEntity`.
- Repeated bounces raise the account bounce rate; above ~5 % AWS pauses sending account-wide —
  stopping *all* platform email.

In scope: set `app.ses.configuration-set` and stamp the correlation id as a SES message tag so
events are correlatable later. Out of scope but strongly recommended as a follow-up: an
SNS → webhook consumer recording bounce/complaint events into a local suppression list. The
account-level suppression list is on by default and gives partial protection for free.

### 6.10 Losing the round-robin

`MailSenderProvider` spread load across two providers, which was also an accidental failover:
if GMX was down, half the mail still went out. SES is a single point of failure. Accepted —
SES is a managed service with far better availability than two consumer mailbox providers —
but the circuit breaker + `EmailRetryScheduler` are now the only cushion, and
`SesHealthIndicator` must be wired into alerting before prod carries real traffic.

### 6.11 Dev experience

Dev keeps Gmail SMTP until Phase 5, then moves to `transport=log` with
`app.email.log.outbox-dir`, writing each rendered email to `target/mails/<correlationId>.html`
— arguably better than a mailbox for template work, since the file is the exact rendered body.
Optional higher fidelity later: point `app.ses.endpoint-url` at LocalStack. Explicitly **not**
recommended: keeping a dev-only SMTP adapter past Phase 6, which is how the current split
started.

### 6.12 In-flight envelopes across the deploy

`EnvelopeEntity` rows with `status=FAILED, retry=true` written by an older build are picked up
by `EmailRetryScheduler` on the new build and re-sent via whatever transport the environment
now has. Fine — the envelope carries template + data, not transport state — but for prod the
sender address changes mid-thread, and `error` columns will hold SMTP stack traces that no
longer correspond to anything. No migration needed; call it out in the release notes.

### 6.13 `Envelope` supports many recipients

`Envelope.recipients()` is a list and `MailManager` already loops, sending one message per
recipient with a fresh `data` copy. Keep that shape: SES `destination.toAddresses` accepts a
list, but batching would expose every recipient's address to the others. Do **not** "optimise"
this into one API call.

Note that partial failure within that loop has a separate, unaddressed consequence — §6.19.

### 6.14 `data` is string-typed by contract

`NotificationOutboxSupport`'s javadoc and `EmailDataStringContractTest` require every value in
an email `data` map to be a `String` or `List<String>`. Nothing here changes that, but Phase
4's new registration path must obey it (`verifyUrl`, `otpCode` are already strings).

### 6.15 The `sendId` uniqueness constraint

`EnvelopeEntity.sendId` is `@Column(unique = true)`. Phase 4 introduces producers that must
generate one; reusing a `sendId` across a registration's verification *and* OTP email would
violate the constraint and lose one of them — surfacing today as an unclassified
`DataIntegrityViolationException` rather than a clear error. §6.18 covers both halves: don't
reuse the id, and make the failure legible when someone does.

### 6.16 Two transports means two behaviours in flight

For the duration of Phases 2–5, dev/uat and prod deliver mail differently: different sender
address, different failure modes (§6.3), different rate limits, different health indicator. A
bug reproduced in UAT may not reproduce in prod and vice versa. Mitigations: both adapters
implement the same port with the same exception taxonomy, so everything *above* the port is
identical; and the window is bounded by D-1, not open-ended. Anyone debugging email should
check `app.email.transport` first — worth a line in the dev docs.

### 6.16b The two retry ceilings, and what happens when an OTP email dies

Not a gap — recorded because the plan leans on these bounds in §3.2 and §5 Phase 4, and the
rules are easy to assume rather than check. `EmailRetryScheduler.retryFailedEmails()` already
specifies both, in a fixed order, before every send attempt:

1. `Instant.now().isAfter(entity.getDeadline())` → `DEADLINE_EXPIRED`, `retry = false`, skip.
2. `entity.getAttempts() >= MAX_RETRY_ATTEMPTS` (6) → `ATTEMPTS_EXHAUSTED`, `retry = false`,
   skip.

Deadline wins: an envelope that reaches its deadline with 5 attempts used is marked
`DEADLINE_EXPIRED` and never gets a 6th. Both terminal states set `retry = false`, so the row
leaves `fetchFailedEmails()`'s working set permanently — which is the mechanism §3.2 relies on
when it argues account-level failures must *not* be classified Permanent. `MAX_RETRY_ATTEMPTS
= 6` counts the initial send as attempt 1, so it is 1 + 5 retries, not 1 + 6.

**A dead OTP email is recoverable by the user, not just by an operator.** All three roles
expose `POST /resend-otp` (`CoachRegistrationResource:65`, `ParentRegistrationResource:62`,
`PlayerRegistrationResource:65`), and `RegistrationOtpResendSupport` mints a fresh 10-minute
token. This is what makes the aggressive 5-minute delivery deadline of §5 Phase 4 the right
trade rather than a risk: better to abandon an OTP email that would arrive expired and let the
user request a new code, than to deliver a dead one and have them retype it and fail.

### 6.17 The transport call runs inside an open DB transaction — don't make it worse

`MailManager.sendEmailSync` is `@Transactional(propagation = REQUIRES_NEW)` **with no
timeout**, and the transport call happens inside it, wrapped by the circuit breaker and a
3-attempt `RetryTemplate` with 1 s fixed backoff. On the outbox path there are in fact *two*
connections held: `OutboxRowProcessor.claimAndHandle()` is itself `REQUIRES_NEW` and invokes
`handler.handle(payload)` at line 115 — inside the claim transaction, holding the outbox row
lock — and `sendEmailSync` then opens its own.

The worst case per email is therefore roughly:

```
outbox claim tx (conn 1, row lock held)
  └─ sendEmailSync REQUIRES_NEW (conn 2, no timeout)
       └─ circuit breaker TimeLimiter (10 s)
            └─ retryTemplate — up to 3 attempts, ≥1 s backoff between
                 └─ SES apiCallTimeout 5 s / attempt 3 s
```

Worst-case concurrent connections held on email work, against a `maximum-pool-size` of 25
(`application.yaml:90`, sized so four nodes fit inside PostgreSQL's 100-connection default):

| Path | Threads | Connections each | Total |
|---|---|---|---|
| `@Async("sendMailPool")` direct-publish sends | 10 max (`AsyncConfig`) | 1 | 10 |
| Outbox drain — claim tx + nested `sendEmailSync` | 2 max (`outboxDrainPool`: core 1 / max 2) | 2 | 4 |
| `EmailRetryScheduler` afterCommit dispatch | 1 | 1 | 1 |

≈ 15 of 25 at peak, leaving ~10 for the rest of the application — not immediately alarming,
but it is the *duration* that matters: each of those is held across a network call with
retries, so a post-outage storm holds them for 10–20 s apiece during the incident that caused
it. Note the doubling applies only to the outbox path; `sendMailPool` sends hold one.

This is pre-existing (SMTP has the same shape, and `OutboxRowProcessor`'s javadoc shows the
authors accepted network I/O inside the claim transaction deliberately), but this plan is
adding to that transaction, so:

1. **The rate limiter must not wait** — §6.5. This is the one genuinely new contribution to
   the hold time, and it is avoidable outright.
2. **Bound the SES client inside the breaker budget** — §6.6, already specified.
3. **Add `timeout` to `sendEmailSync`'s `@Transactional`.** There is none today. Use
   **`timeout = 15`** (seconds): the Resilience4J `TimeLimiter` caps the whole
   `circuitBreaker.run(...)` — recipients loop, retry template and all — at 10 s, so 15 leaves
   headroom for the surrounding JPA work without letting a wedged send hold a connection
   indefinitely. Pick the number from the breaker budget, and change it if that budget
   changes. `EmailRetryScheduler` already does exactly this (`@Transactional(timeout = 600)`)
   for the same reason.
4. Revisit the pool sizing above if `sendMailPool` or `outboxDrainPool` is ever widened —
   the table is the assumption, and nothing currently fails if it stops being true.

Restructuring `sendEmailSync` so the network call happens outside its transaction is the real
fix and is **out of scope here**: it changes `MailManager`'s durability semantics, which §3.3
promises to leave unchanged, and it would have to be done identically for both transports.
Worth its own story; noted so the next person sees it was considered rather than missed.

### 6.18 First-insert of an `EnvelopeEntity` is check-then-act

`MailManager.sendEmailSync` ends with `findBySendId(sendId)` → update if found, insert if not.
`findBySendId` carries `@Lock(LockModeType.PESSIMISTIC_WRITE)`, but **a pessimistic lock on a
row that does not exist locks nothing**, so the first send for a given `sendId` has no
protection between the "not found" check and the insert. The only backstop is
`EnvelopeEntity.sendId`'s `@Column(unique = true)`, which surfaces as a
`DataIntegrityViolationException` — not an `EmailTransportException`, so `isRetryable` and
`NON_REPAIRABLE_ERRORS` never classify it, and it propagates raw through the circuit breaker.

Scope, stated honestly: the outbox holds its row lock across `handle()` (§6.17), and
`EmailRetryScheduler` only fetches rows that already exist, so a genuine *concurrent* collision
is hard to reach today. The realistic trigger is the **sequential** one §6.15 already warns
about — a producer reusing a `sendId` across two emails — and Phase 4 introduces the first new
producers since that warning was written. The symptom of that mistake should be a clear error,
not an unclassified integrity violation surfacing as a circuit-breaker failure.

Fix, cheap and worth doing in Phase 4: catch `DataIntegrityViolationException` on the insert
path, re-query by `sendId`, and fall through to the update branch — treating the loser as
"someone else owns this send". Add `DataIntegrityViolationException` to the permanent set so a
genuine duplicate can never be retried six times.

**Log the catch at ERROR with the `sendId` and template.** Recovering silently is right for
the concurrent case and wrong for the `sendId`-reuse bug, and the code cannot tell them apart
at that point. A silent fallthrough would convert a producer bug into an email that vanishes
into another envelope's row with no trace — the failure mode §6.15 exists to prevent. Loud
recovery gets both: delivery is not lost, and the bug is visible.

Note what will *not* work as an alternative: "lock the row by `sendId` first, then update or
insert" is what the code already does — `findBySendId` carries `@Lock(PESSIMISTIC_WRITE)` —
and it is precisely the thing that fails, because there is no row to lock. Any fix has to come
from the database's own uniqueness enforcement. Catch-and-update is the portable form; a
native `INSERT … ON CONFLICT (send_id) DO UPDATE` is the atomic one, at the cost of bypassing
JPA's `@Version` handling on `EnvelopeEntity`. Prefer catch-and-update unless profiling says
otherwise.

### 6.19 Partial multi-recipient failure would re-send to recipients who already succeeded

One `Envelope` maps to one `EnvelopeEntity` (recipients are an `@ElementCollection` on that
row), `MailManager` loops recipients inside a single try/catch, and one `status`/`retry`/
`error` is written for the whole envelope. If recipient 2 of 3 fails, the loop aborts,
recipient 3 is never tried, and a retry rebuilds the *same three-recipient* envelope and
re-sends to recipient 1 as well.

**Currently unreachable, and that is the finding.** Every producer in the codebase builds
`List.of(oneRecipient)`, and the only `SendMailEvent` publisher — `TwoFactorLoginService:61` —
passes a single-element `userIds` list, even though `SendMailListener` is written to fan out
over many. So this is a latent trap, not an active bug: the data model, the mapper and the
loop all support N recipients, and the first producer to actually use N inherits duplicate
sends on every retry.

Decision: **do not fix it in this migration** (it is `MailManager` durability semantics, which
§3.3 keeps unchanged), but do not leave it undocumented either. Add a javadoc note on
`Envelope.recipients()` and on `MailManager`'s loop stating that partial failure re-sends the
whole list, so whoever writes the first multi-recipient producer sees it. Per-recipient status
tracking is the real fix and belongs with the §6.17 restructuring story.

---

## 7. Test plan

### 7.1 Tests that must be changed

| Test | Change |
|---|---|
| `SesConditionalWiringTest` | Rewrite against the §6.1 transport matrix; add `hasSingleBean` assertions; drop the profile dimension |
| `MailManagerResilienceTest` | Mocks `MailService` and throws `MessagingException` / `MailParseException` / `AddressException` / `ParseException`. Swap for the neutral `EmailTransport{Transient,Permanent}Exception`. The circuit-breaker state-transition assertions are transport-agnostic and stay |
| `VideoModerationEmailListenerTest`, `VideoModerationAdminAlertEnvelopeIT` | Both mock `MailService` as a seam; signatures change (no `throws MessagingException`) |
| `SmtpHealthIndicatorTest` | **Moves** with its class to the smtp package; content unchanged. Deleted in Phase 6 |
| `MailManagerIT`, `EmailRetrySchedulerIT/Test`, `NotificationEmailOutboxAtomicityIT`, `BookingReminderEmailWiringIT`, `SessionPackExpiryWarningIT`, all `TestMailManager`-based ITs | **No change expected** — `enable.test.mail=true` replaces the whole `MailManager` bean, so they never touch a transport. This is the main reason the cutover is testable at all. Confirm by running them unchanged after Phase 2 |
| `IntegrationTestConventionTest` | Pins surviving `@TestPropertySource` counts; putting `app.email.transport` in `application-test.yaml` rather than on test classes keeps this green |
| `DefaultMessageBundleFallbackIT`, `EmailTemplateSubjectKeyParityTest` | Comments reference "MailService uses the subject key"; update to `EmailContentRenderer` |

### 7.2 Tests to add

**Unit — transport adapters**

1. `SesEmailSenderTest` — mocked `SesV2Client`: request shape (from address, destination,
   subject, html **and** text parts, configuration set, reply-to, message tag), returned
   `messageId`, and that an `SesV2Exception` is wrapped, never leaked.
2. `SesErrorClassifierTest` — table-driven over every mapping in §3.2, including an unknown
   SDK exception, which must default to **Transient**: an unclassified failure that is really
   permanent costs six retries, whereas one that is really transient and defaults to permanent
   loses the email outright.
3. `SesAddressValidationTest` — malformed addresses throw `EmailTransportPermanentException`
   *before* any client call (`verifyNoInteractions`); RFC 5322 display-name form passes.
4. `SmtpEmailSenderTest` + `SmtpErrorClassifierTest` — pin that the relocated SMTP path
   behaves exactly as `MailService.sendEmail` did, including the four permanent exception
   types. Deleted in Phase 6, but they are what make Phase 2 safe for dev/uat.
5. `LoggingEmailSenderTest` — writes the HTML file when `outbox-dir` is set, pure no-op when
   not, never throws.
6. `SesSendRateLimiterTest` — N sends over the limit take at least the expected wall-clock; an
   acquire timeout maps to `EmailTransportTransientException`.
7. `SesHealthIndicatorTest` — UP when `sendingEnabled=true`, DOWN when false, DOWN on SDK
   error, and a second call inside the TTL makes no second API call.
8. `SesPropertiesValidationTest` — `transport=ses` + blank `fromAddress` aborts startup with a
   message naming the property; `transport=smtp` + blank is fine. Covers both accepted
   `fromAddress` shapes.
9. `NoHardcodedSenderTest` — no shipped `application*.yaml` contains a literal address for
   `app.ses.from-address`; uat and prod supply no default. Cheap, and it is the specific
   regression that would put a placeholder domain in front of real users (D-1).

**Unit — composition**

10. `EmailContentRendererTest` — a normal template renders subject from `MessageSource` in the
    recipient's locale and body from Thymeleaf; `EmailTemplate.NONE` produces `textBody` and a
    null `htmlBody`; an unknown locale falls back.
11. `EmailRenderingCharacterizationTest` — **write this before the extraction, against the
    current `MailService`.** For a representative template set (one transactional, one OTP, one
    booking, plus `NONE`) × `{de, en, fr, unknown-tag}`, capture rendered subject and body and
    assert against golden strings. Re-run unchanged after the extraction: if the renderer is a
    faithful move, it passes untouched. This is what makes "templates and language are
    preserved" a checked claim.
12. `RetryPreservesLanguageTest` — a `Recipient` with `langKey=fr` round-tripped through
    `EnvelopeMapper.toEntity` → `toEnvelope` still renders French. Pins the
    `RecipientEntity.langKey` persistence `EmailRetryScheduler` and `ResendEmailService`
    depend on (§3.3).
13. `MailManagerErrorClassificationTest` — `EmailTransportPermanentException` at wrap depth 1
    and 2 both yield `retry=false`; the transient type yields `retry=true`. This is exactly
    what D7 breaks today.

    **Construct the exceptions the way an adapter actually throws them**, not with a bare
    `new EmailTransportPermanentException(...)` at the top level. `isRetryable` deliberately
    inspects a *fixed* two-level cause chain, tuned to today's wrapping; an adapter that adds
    one extra wrapper "for context" silently pushes the real type out of range and every
    permanent failure starts retrying six times. Drive at least one case through the real
    `SesEmailSender` + `SesErrorClassifier` and one through `SmtpEmailSender`, so the test
    fails if either adapter's wrap depth drifts.

13b. `AccountBlockedIsRetryableTest` — `AccountSuspendedException` and
    `MailFromDomainNotVerifiedException` classify Transient, leave `retry=true`, and are
    therefore still visible to `fetchFailedEmails()`. Pins §3.2's reasoning against a
    well-meaning future change back to Permanent.

13c. `DuplicateSendIdTest` — a second `sendEmailSync` for an existing `sendId` updates the row
    rather than raising an unclassified `DataIntegrityViolationException` (§6.18).

**Architecture / containment — the tests that keep Phase 6 a one-commit job**

14. `EmailTransportArchitectureTest` — enforces all five invariants in §3.4: the jakarta.mail
    and AWS SDK import fences, no references to SMTP types from outside the smtp package, and
    `platform..` importing neither SDK. **Must land in Phase 2**, with the package, not later.
15. `NoStraySmtpConfigTest` — all SMTP configuration resolves under `app.email.smtp`; no
    `spring.mail.*` remains in any shipped profile.
16. `TransportWiringTest` — `hasSingleBean(OutboundEmailSender.class)` for each of
    `ses` / `smtp` / `log`, and a startup abort for an unrecognised value. Two additions worth
    the lines: assert the validator is actually *registered* (drive it through a context that
    reads `META-INF/spring.factories`, not by calling the class directly — the failure mode is
    a missing registration entry, which a direct unit test cannot see); and assert health-
    indicator exclusivity, i.e. `transport=ses` yields a `SesHealthIndicator` and **no**
    `SmtpHealthIndicator`, and vice versa, so the `notification` health group can name both
    without an inactive one reporting DOWN.

16b. `OutboundEmailRequestValidationTest` — null/blank `toAddress`, `subject` and
    `correlationId` each rejected at construction; both bodies null rejected; html-only and
    text-only both accepted (§3.1). Cheap, and it is the guard that lets the three adapters
    skip defensive checks.

16c. `LoggingEmailSenderCollisionTest` — two concurrent sends sharing a `correlationId`
    produce two files, not one overwritten (§4.1); an unwritable `outbox-dir` degrades to
    log-only without throwing.

16d. `SesPropertyValidationTest` — malformed `configuration-set`, unparseable
    `reply-to-address` and a non-positive `max-send-rate-per-second` each abort startup while
    `transport=ses`, and are ignored while `transport=smtp` (§4.2).

**Integration**

17. `SesEmailEndToEndIT` — WireMock stub on `app.ses.endpoint-url` (already a test dependency,
    lighter than LocalStack): publish an `Envelope`, assert one SES call with the expected body
    and an `EnvelopeEntity` row with `status=SENT`. Runs with `enable.test.mail=false` for this
    class only — the single deliberate exception to the global `TestMailManager` override.
18. `SesFailureRetryIT` — stub returns 500 twice then 200; envelope ends `SENT` with `attempts`
    recorded, no duplicate rows.
19. `SesPermanentFailureIT` — stub returns `MessageRejected`; `status=FAILED`, `retry=false`,
    and `EmailRetryScheduler` never re-fetches the row.

**Phase 4**

20. `CoachRegistrationEmailListenerTest` / `Parent` / `Player` — **there are currently no tests
    at all for these three listeners.** One per event: right template, right `data` keys, an
    outbox row enqueued inside the producing transaction, and a rollback leaving no row.
    Also assert each enqueues **exactly one recipient** — these are the first new `MailManager`
    producers since §6.19's partial-failure trap was documented, and a single-recipient
    assertion is the cheapest way to keep them out of it.
21. `RegistrationEmailDurabilityIT` — a failing send leaves a retryable envelope and the
    scheduler re-drives it; an OTP envelope past its delivery deadline is marked
    `DEADLINE_EXPIRED` rather than delivered.

22. `OtpDeadlineParityTest` — every OTP-bearing `EmailTemplate` returns a
    `deliveryDeadline()` strictly shorter than the 10-minute registration OTP TTL, and every
    other template returns the 24-hour default. Pins the §5 Phase 4 item 3 relationship so a
    future change to either side cannot silently produce mail that outlives its own code.

23. `SesRateLimiterDoesNotBlockTest` — an exhausted limiter throws
    `EmailTransportTransientException` immediately rather than waiting (§6.5 / §6.17). Assert
    on elapsed time, since "it blocks for 2 seconds" and "it fails fast" are otherwise
    indistinguishable from the caller's perspective.

### 7.3 Manual verification

**After Phase 2, on UAT (SMTP):** register a coach (verification + OTP — the flow that
receives nothing today), request a password reset, book a session, trigger a session-pack
expiry warning and an ops alert. Confirms the port move preserved every path.

**Before Phase 5 completes, on UAT (SES):** repeat the same list and check in a real inbox —
correct `From`, DKIM `pass`, SPF `pass`, DMARC `pass`, not in spam, and HTML rendering in
Gmail and Outlook.

---

## 8. Rollout and rollback

**Order:** Phase 3's `SesHealthIndicator` must land before prod serves real user traffic, so
`GetAccount` surfaces a sandboxed or sending-disabled account (§6.8) before it matters. This
is a gate on Phase 2's exit, not advice — see §5 Phase 2 and §10 item 13.

**Recovering from an account-level incident.** Because `AccountSuspendedException` and
`MailFromDomainNotVerifiedException` classify Transient (§3.2), envelopes stranded by a
suspension or an unverified-domain window keep `retry=true` and `EmailRetryScheduler` drains
them automatically once the block lifts — no operator action, no bulk SQL. Two bounds apply:
the 6-attempt ceiling and the envelope deadline. If an incident outlasts either, the affected
rows go `ATTEMPTS_EXHAUSTED` or `DEADLINE_EXPIRED` and become invisible to the scheduler;
recovering *those* means re-flipping `retry` and resetting `attempts` for the affected
`sendId` set, then letting the scheduler pick them up. Worth a runbook entry with the query,
since the alternative is `ResendEmailService.resendEmail` one row at a time.

**Rollback is cheap for the first time in this plan's history.** Because SMTP is retained
behind the port through Phase 5, reverting any environment is a property change:
`app.email.transport=smtp`, redeploy. No code revert, no rebuild. That is the main practical
benefit of the staged approach over the original delete-at-cutover design.

**After Phase 6** the property is the only thing removed, and rollback means reverting that
commit — which is why Phase 6 must not ship in the same release as Phase 5's flip. Leave at
least one full release cycle between them.

**Blast radius if SES is misconfigured:** prod only, from Phase 2 — and prod is not live.
`EnvelopeEntity` retains every failed envelope with `retry=true`, and `EmailRetryScheduler`
re-drives for up to 6 attempts inside the envelope deadline, so a fix within that window
recovers the backlog automatically. Beyond it, rows go `DEADLINE_EXPIRED`.

---

## 9. Decisions

### 9.1 Settled (decision round 1, 2026-09-11)

| # | Decision | Outcome |
|---|---|---|
| D-3 | What does UAT do for email? | **dev, test and uat stay on Gmail SMTP** until the SES/domain decision lands, structured so the switch and the removal are easy. This is what drove §3.4 and the Phase 2/5/6 split |
| D-3b | What does prod do? | **Prod goes to SES for everything, immediately** (Phase 2). Prod is not live, and it must never acquire an SMTP dependency it would later have to shed |
| D-4 | Dev transport after cutover | **Rendered HTML to disk** (`app.email.log.outbox-dir`), Phase 5 |
| D-5 | Registration durability (Phase 4) | **In scope for this migration**, not a follow-up |

### 9.2 Still open

| # | Decision | Notes |
|---|---|---|
| **D-1** | **Which domain does the platform send from?** Undecided; `skillars.com` is unavailable | **Blocks Phase 5 only** — that is the point of the staging. Carries up to 72 h of DNS + DKIM lead time, so start it early. Whatever is chosen reaches the app only through `${APP_SES_FROM_ADDRESS}`; no literal in committed YAML (§6.7) |
| D-1b | Is the SES account out of the sandbox, in `app.ses.region`, with the chosen domain DKIM-verified? | Depends on D-1. Hard gate for Phase 5 and for prod serving real users (§6.8) |
| D-2 | Display name and reply path — bare address or `Skillars <noreply@…>`? Any `Reply-To`? | **Proceeding with:** support both shapes from the start, `app.ses.reply-to-address` present but unset. One line now versus a schema change later. Say if you want a specific default |
| D-6 | Bounce/complaint handling — this migration or the next? | **Proceeding with:** follow-up story, but set `app.ses.configuration-set` and the correlation-id message tag now so events are correlatable when the consumer lands (§6.9) |
| D-7 | Test profile transport — `log` or `smtp`? | **Proceeding with `log`.** Tests never reach a transport (`TestMailManager` replaces `MailManager`), so `log` preserves today's behaviour while guaranteeing CI can never open a socket to Gmail or call AWS (§4.5) |
| **D-8** | **Circuit-breaker scope after Phase 4.** Registration/OTP mail currently has its own isolated failure handling; routing it through `MailManager` puts it on the breaker shared with booking, password-reset and every other email | **Proceeding with a separate breaker name** for the registration/OTP path. OTP failures are partly user-input-driven, and a signup spike with bad addresses should not be able to open the breaker that gates booking confirmations. Cost is one extra breaker to watch; the alternative is a real, silent blast-radius increase introduced as a side effect of "route it like everything else". Say if you'd rather have the single shared signal |

### 9.3 Known limitations this plan does not close

Recorded so they read as decisions rather than oversights. None block the migration.

| Area | Limitation |
|---|---|
| Transaction shape | The transport call, its retries and the circuit-breaker wait all happen inside `sendEmailSync`'s open transaction, holding a DB connection (two, on the outbox path). This plan bounds it (§6.17) but does not restructure it — that changes `MailManager` durability semantics, which §3.3 promises to preserve. Own story |
| Multi-recipient envelopes | Partial failure re-sends to recipients who already succeeded (§6.19). Currently unreachable — every producer sends to exactly one recipient — so documented rather than fixed |
| Activation / password-reset / 2FA durability | These stay on the older direct-publish `AFTER_COMMIT` model, not the outbox (§2.1). Phase 4 moves registration/OTP only |
| Bounce and complaint handling | D-6 — follow-up story; this plan only makes the events correlatable |
| `EnvelopeEntity.retry` column definition | A `boolean` field annotated `@Column(columnDefinition = "text")`, almost certainly copied from the `error` field above it. Cosmetic only: `ddl-auto` is `none` and the schema comes from Flyway, so `columnDefinition` is never used for DDL. Fix if the entity is touched for another reason; do not raise a migration for it |

---

## 10. Definition of done

### After Phase 4 — this migration

1. Exactly one `OutboundEmailSender` bean exists for every value of `app.email.transport`,
   and an unrecognised value aborts startup — enforced by a test.
2. No class under `com.softropic.skillars.platform..` imports `software.amazon.awssdk..`,
   `jakarta.mail..`, `javax.mail..` or `org.springframework.mail..` — enforced by a test.
3. Every SMTP type lives under `infrastructure/email/smtp/` and is referenced from nowhere
   else — enforced by a test, so Phase 6 stays a one-commit job.
4. Prod sends every email via SES; dev and uat send every email via SMTP, **including
   registration and OTP mail**, which UAT silently dropped before (D4).
5. Recipients still receive email in their `langKey` language, from the same 31 templates and
   the same `messages{,_de,_en,_fr}.properties` bundles, with byte-identical rendering —
   proven by the characterization test passing unmodified across the extraction.
6. Every email in the system — including coach/parent/player verification and OTP — has an
   `EnvelopeEntity` row, a retry path and a deadline, on either transport.
7. `spring.mail.*` and `SPRING_MAIL_PASSWORD` are gone (D9).
8. `/actuator/health/notification` reports the transport each environment actually uses.
9. An account-level SES failure leaves envelopes `retry=true` and visible to
   `EmailRetryScheduler`, with `[EMAIL_ACCOUNT_BLOCKED]` logged and the health indicator DOWN
   — pinned by a test, because the tempting change is the one that breaks it (§3.2).
10. No OTP-bearing template has a delivery deadline at or above the 10-minute registration OTP
    TTL, and the deadline comes from `EmailTemplate`, not from each call site.
11. The SES rate limiter never blocks inside `sendEmailSync`'s transaction, and that
    transaction carries an explicit `timeout` (§6.17).
12. `EmailTransportPropertyValidator` is registered in `META-INF/spring.factories` and
    demonstrably runs — an unrecognised `app.email.transport` aborts startup in a test.
13. **Prod has not served real user traffic before Phase 3 is live** (§5 Phase 2 gate).
14. Full CI green, including the containment tests.

### After Phase 6 — SES cutover complete

15. `app.email.transport` accepts only `ses` and `log`; no SMTP code, config, secret or
    dependency remains anywhere in the repo (`GMX_PASSWORD` / `GMAIL_PASSWORD` included).
16. The Phase 6 diff touches nothing under `platform/`.
17. Every email leaves as the environment's configured `${APP_SES_FROM_ADDRESS}`, with DKIM,
    SPF and DMARC passing — verified in a real inbox from UAT. No sender address literal
    remains in any committed YAML, and an environment with the value unset refuses to start.
