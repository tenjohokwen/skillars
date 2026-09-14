# Story Ses-1.4: Registration Email Durability

Status: done

**Pre-dev review applied (2026-09-12, `story-review.md`):** an independent senior-dev review of this story file, done before any implementation, found 3 blocking issues (AC3 didn't check its deadline on the actual send path; AC4's proposed exception-catch was unreachable given `EnvelopeEntity`'s id/version shape; a related dead-code addition to `MailManager.NON_REPAIRABLE_ERRORS`) and 5 significant issues (two IT-design flaws in AC6, a test-scoping gap in AC3's parity test, an OTP/token logging exposure with no AC covering it, and `sendId` uniqueness being documented as stronger than it is). All were verified against the code (not taken on faith) and are folded into the ACs below — nothing here is speculative. See `story-review.md` for the full evidence trail if you want it; the corrected ACs are self-contained and don't require reading that file to implement correctly.

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the platform's email transport owner,
I want coach/parent/player verification and OTP mail routed through the same durable outbox path every other email already uses, with an OTP-appropriate delivery deadline and its own circuit breaker,
so that a failed registration or OTP send is retried automatically instead of silently lost, an expired-by-the-time-it-arrives OTP is never delivered, and a burst of bad registration addresses cannot trip the breaker that gates booking confirmations.

This is **Phase 4 of 7** in `requirements/ses-email-consolidation.md` (closes D3). It builds on Phase 1 (`ses-1.1`, done — the port), Phase 2 (`ses-1.2`, done — SMTP contained, `EmailContentRenderer` extracted), and Phase 3 (`ses-1.3`, done — health, rate limiting, transport-tagged metrics). Per the source doc's exit criterion: *"one durability model for every email in the system, on either transport."* This story does not touch the SES cutover (Phase 5) or add anything transport-specific — it moves three listener pairs from a direct, AFTER_COMMIT, fire-and-forget port call onto the pre-existing `NotificationOutboxSupport` → `NotificationEmailOutboxHandler` → `MailManager` pipeline that booking, session-pack and video-moderation mail already use.

**Already true, not part of this story — verify by reading, don't re-derive:**
- The outbox pipeline itself (`NotificationOutboxSupport.enqueueEmail`, `NotificationEmailOutboxHandler`, `MailManager.sendEmailSync`, `EmailRetryScheduler`) is unchanged plumbing from `skillars-deferred-91`/`-92`. This story is a **new producer** onto existing infrastructure, not new infrastructure.
- `EmailContentRenderer` (ses-1.2 AC1) already renders `${recipient}` and `${map}` into the six registration templates (`coachEmailVerify.html`, `coachOtp.html`, `parentEmailVerify.html`, `parentOtp.html`, `playerEmailVerify.html`, `playerOtp.html`) — verified by direct read, all six use `${recipient.firstname}` in their greeting line (`th:text="#{email.greeting(${recipient.firstname})}"`) and one `${map.*}` variable each (`verifyUrl` or `otpCode`). Nothing about template content or i18n changes in this story.
- `CoachRegistrationService`, `ParentRegistrationService`, `PlayerRegistrationService` and `RegistrationOtpResendSupport` already publish `{Coach,Parent,Player}{Verification,Otp}EmailEvent` from inside an `@Transactional` method via `ApplicationEventPublisher.publishEvent(...)` (verified: `CoachRegistrationService.java:52,256,261`, `RegistrationOtpResendSupport.java:34,85`). **Nothing about event publication changes** — only what the six listener methods do with the event once BEFORE_COMMIT fires.
- The registration OTP TTL is **10 minutes** (`CoachRegistrationService:162`, `ParentRegistrationService`, `PlayerRegistrationService`, `RegistrationOtpResendSupport:82` — all `Instant.now().plus(10, ChronoUnit.MINUTES)`). This is **not** `SecurityConstants.OTP_TTL` (30 minutes — the login 2FA OTP, a different flow, `LoginInfoService`). Do not confuse the two when picking the delivery deadline.
- A dead OTP email is user-recoverable, not just operator-recoverable: all three roles expose `POST /resend-otp` (`CoachRegistrationResource:65`, `ParentRegistrationResource:62`, `PlayerRegistrationResource:65`) via `RegistrationOtpResendSupport`, which mints a fresh 10-minute token. This is what makes an aggressive delivery deadline the right trade, not a risk.

**A source-doc claim this story corrects, found while creating it — verify before trusting §7.2 item 20 of the requirements doc:** it says *"there are currently no tests at all for these three listeners."* That is stale. `CoachRegistrationEmailListenerTest`, `ParentRegistrationEmailListenerTest` and `PlayerRegistrationEmailListenerTest` already exist (added by `ses-1.1` AC11) and currently assert the **direct port-call** behavior this story replaces (mock `OutboundEmailSender`, verify `.send(...)` was called with a rendered `OutboundEmailRequest`). This story **rewrites all three test classes** to assert the new outbox-enqueue behavior — it is not writing tests from a blank slate, and the old assertions (UUID correlation-id pattern, widened-catch-on-blank-address, widened-catch-on-transport-exception) no longer apply once rendering moves out of the listener.

**A gap the requirements doc's Phase 4 section does not mention, found while creating this story — read before implementing AC1:** `NotificationOutboxSupport.NotificationEmailPayload` carries only `toAddress` and `langKey` for the recipient (`NotificationOutboxSupport.java:143-144`), and `NotificationEmailOutboxHandler.handle` reconstructs a bare `Recipient` from exactly those two fields (`NotificationEmailOutboxHandler.java:66-68`) — `firstname` is never part of the payload. This has been harmless so far because **no template routed through the outbox today uses `${recipient.firstname}`** (verified: `grep` across every `BookingEmailListener`/`SessionPackEmailListener`/`VideoModerationEmailListener` call site shows none set it; verified by template content — only the six registration templates plus `activation`/`creationDup`/`passwordReset`/`profileChange`/`sendOtp`, all direct-publish today, use it). Routing the six registration templates through the outbox **without addressing this would silently blank every greeting** ("Hello ," instead of "Hello Ada,") — a real regression, not a hypothetical, and not called out anywhere in `requirements/ses-email-consolidation.md`. AC2 below is new scope this story adds to close it; **the person who scoped Phase 4 should be told this is not in the source doc**, since it changes the shape of `NotificationEmailPayload` (a JSON-persisted outbox row shape) rather than being purely additive to the listeners.

## Acceptance Criteria

**AC1 — Registration verification/OTP listeners move from direct AFTER_COMMIT port calls to BEFORE_COMMIT + the outbox**

- Given `CoachRegistrationEmailListener`, `ParentRegistrationEmailListener`, `PlayerRegistrationEmailListener` (`platform/security/infrastructure/listener`), each currently `@TransactionalEventListener(phase = AFTER_COMMIT)`, rendering the Thymeleaf template inline and calling `outboundEmailSender.send(new OutboundEmailRequest(...))` directly inside a `try { } catch (EmailTransportException | IllegalArgumentException ex) { log.error(...) }`
- Then all six methods (`onVerificationEmail`/`onOtpEmail` × 3 classes) change to `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)` and stop rendering entirely — mirror `BookingEmailListener`'s established shape **exactly, including its blank-address guard** (`BookingEmailListener.java:60-84`, specifically the `if (event.getCoachEmail() == null || event.getCoachEmail().isBlank()) { log.warn(...); return; }` at `:63-66` — do not drop this): check the event's `toAddress` for null/blank first and `return` early with a `log.warn` carrying registration context (coach/parent/player, which method) before building anything; only then build a `Map<String, Object> data`, build a `Recipient` (email + langKey — see AC2 for firstname), and call `notificationOutboxSupport.enqueueEmail(EmailTemplate.X, recipient, data, sendId)` inside a `try { } catch (Exception e) { log.error(...) }`. **Do not drop the guard and rely on `[NOTIFICATION_EMAIL_UNDELIVERABLE]` to catch it** — code review 2026-09-12 found that without it, a blank address costs a full outbox row + `EnvelopeEntity` row + a delivery round trip before surfacing as a generic notification-module log with no registration context, where today it is rejected inline with one that has it; AC6 must keep a test for the blank case. Delete the now-unused `SpringTemplateEngine templateEngine` and `MessageSource messageSource` constructor dependencies and the `OutboundEmailSender` dependency — replace with a single `NotificationOutboxSupport notificationOutboxSupport` dependency, exactly as `BookingEmailListener`'s constructor shows
- And the `data` map carries exactly what the template needs, matching what the listener puts in the Thymeleaf context today: `data.put("verifyUrl", event.verifyUrl())` for the two verify methods, `data.put("otpCode", event.otp())` for the two OTP methods — no other keys, and both are already strings (source doc §6.14 confirms this: no reformatting needed)
- And the widened `catch (EmailTransportException | IllegalArgumentException ex)` becomes a plain `catch (Exception e)`, matching every other outbox-producing listener in the codebase (`BookingEmailListener`, `SessionPackEmailListener`) — `enqueueEmail` can throw `IllegalStateException` on serialization failure (`NotificationOutboxSupport.java:131`) or propagate a DB failure that must roll back the transaction per that class's own javadoc "Failure semantics" section; catching narrower than `Exception` here would either miss the serialization case or accidentally swallow the DB-failure case this class deliberately lets propagate
- And the log messages keep their operational meaning but drop the now-wrong "user is EMAIL_VERIFIED but OTP unreachable; resend-OTP endpoint required" framing for the *enqueue-failure* path specifically — that framing describes what happens when a send is lost outright, which after this story only happens if enqueueing itself fails (a DB/serialization problem), not a transport failure (which the outbox now retries automatically). A transport failure is no longer this listener's problem to log at all — `EmailRetryScheduler`/`NotificationEmailOutboxHandler` own that now
- **New failure mode this AC introduces — record it, do not silently accept it.** `AFTER_COMMIT → BEFORE_COMMIT` plus `enqueueEmail`'s `Propagation.MANDATORY` means a failed outbox `INSERT` now marks the *registration/resend-OTP transaction itself* rollback-only — per `NotificationOutboxSupport`'s own "Failure semantics" javadoc, this happens whether or not the listener catches the exception (`UnexpectedRollbackException` at commit). Concretely: an email-infrastructure problem (DB unavailable at exactly the wrong moment) can now fail `POST /register` or `POST /resend-otp` with a 500, where today the account is created and only the email is silently lost. This is the same trade booking already makes, and it is the right one, but it is a genuine change to the registration API's failure contract that the story must state explicitly, not one this AC should introduce quietly. Confirm `ApiAdvice` maps `UnexpectedRollbackException` to a sensible response before calling this AC done.
- [Source: requirements/ses-email-consolidation.md#5 Phase 4 item 1; verified against `BookingEmailListener.java:60-84` as the established pattern; new-failure-mode note per code review 2026-09-12 (L1)]

**AC2 — Recipient `firstname` survives the outbox round trip (new scope this story adds — not in the source doc)**

- Given `NotificationOutboxSupport.NotificationEmailPayload` (`NotificationOutboxSupport.java:143-144`) carries only `toAddress`/`langKey` for the recipient, and `NotificationEmailOutboxHandler.handle` (`:66-68`) reconstructs a bare `Recipient` from exactly those two fields
- Then extend `NotificationEmailPayload` with a nullable `String firstname` field, populate it in `enqueueEmail` from `recipient.getFirstname()`, and set it on the reconstructed `Recipient` in `NotificationEmailOutboxHandler.handle`
- And every **existing** caller of `enqueueEmail` (`BookingEmailListener`, `SessionPackEmailListener`) is unaffected — they build a `Recipient` with `firstname` left `null` (its current, unchanged behavior), and `null` round-trips through Jackson as `null`, so no existing outbox row's shape changes and no existing template (none of which reads `${recipient.firstname}`) is affected
- And the registration listeners (AC1) call `recipient.setFirstname(event.firstName())` before `enqueueEmail`, exactly as they do today before rendering — the one line of behavior this AC exists to preserve
- And a new regression test proves the round trip: enqueue with a non-null firstname, decode the persisted outbox payload (or call the handler directly with a `TestMailManager`/captured `Envelope`), assert the reconstructed `Recipient.getFirstname()` matches — this is the test that would have caught the gap described above before it reached UAT
- **Do not** thread `firstname` through as a `data` map entry instead — the template already reads it via `${recipient.firstname}`, and changing the template to read `${map.firstname}` instead would touch six `.html` files and diverge registration templates from the `${recipient}`-context convention every other template (that uses it) follows. Fixing the payload/handler is the smaller, more consistent change
- [Source: found during story creation — verified by direct read, no requirements-doc citation exists for this AC]

**AC3 — OTP delivery deadline, on the enum, shorter than the OTP's own 10-minute TTL, and actually checked on the send path**

- Given `NotificationOutboxSupport.DELIVERY_DEADLINE` is currently one constant (`Duration.ofDays(1)`, `NotificationOutboxSupport.java:92`) applied to every enqueued email regardless of template
- Then add `EmailTemplate.deliveryDeadline()` returning a `Duration`, alongside the existing `subjectKey()` accessor — same enum-per-value pattern. Every `EmailTemplate` constant needs a second constructor argument; default it to `Duration.ofDays(1)` for all existing values and override to **`Duration.ofMinutes(5)`** for exactly `COACH_OTP`, `PARENT_OTP`, `PLAYER_OTP`. **This enum accessor is read by exactly one caller, `NotificationOutboxSupport.enqueueEmail` — say so explicitly, and do not claim it makes forgetting structurally impossible.** Six producers build an `Envelope` directly with their own caller-supplied deadline and never consult this enum at all: `AccountManagementFacade`, `EmailRegistrationStrategy`, `SendMailListener` (deadline is a parameter in all three), `AlertNotificationListener` (hardcoded 5 min), `VideoModerationEmailListener` (1 h / 1 d), `ReportGenerationService` (48 h) — and, most relevant here, `TwoFactorLoginService.java:60` already gives the **login-2FA `SEND_OTP`** flow its own hardcoded 10-minute deadline, one file away from this enum and entirely independent of it. `deliveryDeadline()` closes the "forgets to special-case OTP" gap only for outbox-routed producers (booking, session-pack, video-moderation, and now registration/OTP) — it is not a codebase-wide guarantee, and `SEND_OTP` in particular is not part of Phase 4's scope and its deadline is not changed by this AC
- And `NotificationOutboxSupport.enqueueEmail` reads `template.deliveryDeadline()` instead of the fixed constant when stamping `NotificationEmailPayload.deadline` — the constant itself can be deleted once nothing references it, or kept as the enum's own default value (either is fine; do not leave both a class-level constant and an enum-level default disagreeing with each other)
- And 5 minutes, not something more conservative, is the source doc's own recommendation ("leaving the recipient roughly half the TTL to actually use the code") — do not pick a different number without recording why, since `OtpDeadlineParityTest` (AC6 below) pins this exact relationship, **scoped to outbox-routed templates only** (see AC6)
- **Blocking gap found by code review 2026-09-12 (B1) — this AC does not yet close its own "so that" clause.** `deadline` is consulted in exactly one place in `src/main`: `EmailRetryScheduler.java:97`, and only for rows already `FAILED`+`retry=true`. Neither `NotificationEmailOutboxHandler.handle` nor `MailManager.sendEmailSync` checks `envelope.deadline()` before a **first-attempt** send — `handle()` builds the deadline-bearing `Envelope` and passes it straight to `sendEmailSync`, which ignores it and sends. `OutboxService` retries a stuck row **forever** by design (`OutboxService.java`'s `STUCK_ATTEMPTS_THRESHOLD` javadoc: "It is still retried forever") with `OutboxRowProcessor.backoffFor` escalating 30s → 1m → 2m → … → a 1h cap, so an OTP whose outbox row is delayed (drain-pool saturation, a pod restart between commit and drain, an SES outage) is delivered **whenever it finally drains**, potentially hours after its 10-minute TTL — the exact scenario this story's "so that" clause promises to prevent, and which `EmailRetryScheduler`'s deadline check does not reach because the row was never `FAILED` in the first place
- **Fix, required for this AC to be complete:** add an explicit deadline check to `NotificationEmailOutboxHandler.handle`, immediately after deserializing the payload and before calling `sendEmailSync`: if `Instant.now().isAfter(deadlineOf(p))`, log `[NOTIFICATION_EMAIL_DEADLINE_EXPIRED]` at WARN with `sendId`/`template`, and `return` without sending — releasing the outbox row with no delivery attempt. This is the deliberately narrower of two options: it changes behavior only for the outbox-routed path this story already touches, not for `EmailRetryScheduler` (already deadline-aware) or any direct-publish producer. Guarding inside `sendEmailSync` instead would cover every caller uniformly but changes behavior for every existing producer of that shared method — a materially larger blast radius for a Phase-4-scoped story. If a reviewer would rather have the uniform guard, that's a legitimate alternative, but it must be a stated choice, not a silent scope expansion
- [Source: requirements/ses-email-consolidation.md#5 Phase 4 item 3, #6.16b; B1/S4 corrections per code review 2026-09-12 — see story-review.md]

**AC4 — Distinct `sendId` per email; a reused `sendId` surfaces as a logged, retried failure instead of vanishing silently**

- Given `EnvelopeEntity.sendId` is `@Column(unique = true)` (`EnvelopeEntity.java:56-57`), and each registration flow now enqueues two emails (verification, then later a separate OTP, plus any `resend-otp` re-enqueue) that must not share one
- Then each of the six listener methods generates its own fresh `sendId` via `UUID.randomUUID().toString()` at enqueue time — matching what `BookingEmailListener` already does at every one of its thirteen `enqueueEmail` call sites, and what the pre-this-story registration listeners already did for their `correlationId` (same pattern, renamed to the concept the outbox actually uses). This part is unaffected by the correction below
- **Corrected per code review 2026-09-12 (B2) — the story's first draft specified a catch that cannot fire.** `EnvelopeEntity` has `@Id private UUID id` with **no** `@GeneratedValue` — the id is assigned by hand in `EnvelopeMapper.toEntity` (`:24`, `envelopeEntity.setId(UUID.randomUUID())`) — and `@Version private long version` is a **primitive**. Spring Data JPA's `isNew()` check does not use version-based detection for a primitive version field; it falls back to "id is non-null ⇒ not new," so `envelopeEntityRepository.save(...)` calls `entityManager.merge(...)`, not `persist(...)`. Neither `merge` nor `persist` flushes — the actual `INSERT` against the `sendId` unique constraint fires when `sendEmailSync`'s `REQUIRES_NEW` transaction **commits**, after the method body (and any `try/catch` inside it) has already returned. A `try { save(envelopeEntity) } catch (DataIntegrityViolationException)` around the call at `MailManager.java:121` therefore never catches a `sendId` collision. Do not implement it this way
- **Also corrected (B2, second half) — the originally-specified recovery cannot work even if the exception is forced to surface early.** Calling `saveAndFlush(...)` instead of `save(...)` does make the violation surface synchronously, inside `sendEmailSync`'s own call — but a flush failure marks the enclosing transaction **rollback-only**, so a "re-query by `sendId` and fall through to update" recovery attempted in that same transaction cannot commit either. Do not attempt an in-transaction recovery (re-query + update) after the flush fails
- **Fix:** change `save(envelopeEntity)` to `saveAndFlush(envelopeEntity)` (so a collision is surfaced at a predictable point instead of an arbitrary later commit) and let the resulting `DataIntegrityViolationException` **propagate out of `sendEmailSync` uncaught** — do not add a recovery catch. Both real callers already handle this correctly, unchanged: `EmailRetryScheduler.sendAll` (`:139-144`) already wraps the call in `catch (Exception e) { log.error("Email retry attempt failed", kv("sendId", envelope.sendId()), e) }`, which is already the "log the catch at ERROR with the `sendId`" the source doc asks for. On the outbox path, `NotificationEmailOutboxHandler.handle` does not itself catch the call — an exception propagates to `OutboxRowProcessor`, whose per-row failure handling (separate `REQUIRES_NEW` transaction, `BASE_BACKOFF` 30s doubling to a 1h `MAX_BACKOFF`) is exactly "the row is retried, which is already correct behaviour" for what is, once it actually happens, a real infrastructure problem. This is materially simpler than the original catch-and-recover design and loses nothing the source doc's §6.18 actually asked for (a collision "should be a clear error, not an unclassified integrity violation" — a `DataIntegrityViolationException` propagating to an existing named-and-logged catch already satisfies that)
- **Do not add `DataIntegrityViolationException` to `MailManager.NON_REPAIRABLE_ERRORS`** (correction to the story's first draft, B3). That list is consulted only by `isRetryable`, called only from inside the retry loop on the caught transport exception (`MailManager.java:91`) and from the exception caught out of `circuitBreaker.run(...)` (`MailManager.java:137`, fed by the catch at `:107`) — both **before** the `saveAndFlush` this AC touches, which runs after `circuitBreaker.run(...)` has already returned normally (`envelopeEntity` was already classified as the success case). A `sendId` collision exception can never reach `isRetryable`; adding it to the list is dead code. It also breaks a documented invariant — `MailManager.java:39-43` (ses-1.2 AC4) states this list is **transport-neutral**, populated only by `OutboundEmailSender` classifiers; a Spring DAO exception is not one and no transport can throw it
- **State plainly what `sendId` uniqueness does and does not achieve (S5) — the source doc does not spell this out, and the story's first draft implied more than is true.** `sendId` dedupes the `EnvelopeEntity` **bookkeeping row**, not the underlying email dispatch. `NotificationEmailOutboxHandler.handle` calls `sendEmailSync` — and therefore a real `mailService.sendEmailFromTemplate` dispatch — on **every** invocation, including every re-drive of a `FAILED`/`retry=true` row; `findBySendId` only decides whether that dispatch's *outcome* updates an existing row or inserts a new one. A re-drive of an already-`SENT` envelope (which does not happen today via the normal `FAILED`-only retry paths, but is not structurally prevented) would send the email again and then merely update its row. For registration OTP mail this is benign — the recipient gets the same code twice, not two different ones — but do not describe or test `sendId` as duplicate-*send* prevention; it is duplicate-*row* prevention only
- And a new `DuplicateSendIdTest`, reframed accordingly: assert that a genuine `sendId` collision (two `sendEmailSync` calls, same `sendId`, deliberately racing so the first has not yet committed when the second attempts its `saveAndFlush`) results in exactly one persisted `EnvelopeEntity` row and a **logged** failure (via one of the two existing catches above) — not that it "prevents a duplicate send," which it does not
- [Source: requirements/ses-email-consolidation.md#5 Phase 4 item 4, #6.15, #6.18; B2/B3/S5 corrections per code review 2026-09-12 — see story-review.md]

**AC5 — Registration/OTP mail gets its own circuit breaker name (D-8), without changing `sendEmailSync`'s signature**

- Given `MailManager.sendEmailSync` hardcodes `circuitBreakerFactory.create("emailService")` (`MailManager.java:70`), a single breaker shared by booking, password-reset, session-pack and every other email, and D-8 is **settled** ("Proceeding with a separate breaker name for the registration/OTP path... a signup spike with bad addresses should not be able to open the breaker that gates booking confirmations")
- Then derive the breaker id from the envelope's template rather than adding a parameter to `sendEmailSync` (which has five call sites across `MailManager`, `EmailRetryScheduler`, `VideoModerationEmailListener`, `AlertNotificationListener` and `NotificationEmailOutboxHandler` — a signature change is unnecessary churn when the `Envelope` already carries everything needed). Add `EmailTemplate.circuitBreakerName()` alongside `deliveryDeadline()` — same enum-per-value pattern — defaulting to `"emailService"` for every existing value, overridden to **`"registrationEmailService"`** for exactly `COACH_EMAIL_VERIFY`, `COACH_OTP`, `PARENT_EMAIL_VERIFY`, `PARENT_OTP`, `PLAYER_EMAIL_VERIFY`, `PLAYER_OTP`. Change `MailManager.java:70` to `circuitBreakerFactory.create(envelope.emailTemplate().circuitBreakerName())`
- And `ComponentConfig.defaultCustomizer()`'s `factory.configureDefault(id -> ...)` (`ComponentConfig.java:89-100`) already applies the **same** `CircuitBreakerConfig`/`TimeLimiterConfig` to every breaker id — `configureDefault` is keyed by id but configures all ids identically, so `"registrationEmailService"` automatically gets the same `slidingWindowSize(5)/minimumNumberOfCalls(5)/failureRateThreshold(50%)/waitDurationInOpenState(5s)` shape as `"emailService"`, tracked as an independent instance. **No change to `ComponentConfig` is needed for this AC** — say so explicitly in the PR, since it is easy to assume a second named customizer is required when it is not
- And `EmailRetryScheduler`'s re-drive path builds its `Envelope` from a persisted `EnvelopeEntity` via `EnvelopeMapper.toEnvelope`, which already carries `emailTemplate` — the breaker-name derivation is automatically correct on retry too, with no change needed in that class
- **Guard the new call site (L3).** `circuitBreakerFactory.create(envelope.emailTemplate().circuitBreakerName())` sits at `MailManager.java:70`, **before** the `try` block at `:81` that records every other failure onto an envelope row. Three producers (`AccountManagementFacade`, `EmailRegistrationStrategy`, `SendMailListener`) take `emailTemplate` as a method parameter rather than a hardcoded enum reference, so a caller passing `null` — not observed today, but not structurally prevented either — would NPE here and escape `sendEmailSync` uncaught, unrecorded. Either null-guard (`emailTemplate() != null ? ... : "emailService"`) or move the `create(...)` call inside the existing `try` so a null template is recorded as a failed envelope like everything else
- **Correct the cost framing D-8 already accepted (L4).** `ComponentConfig.java:92-93`'s `slidingWindowSize(5)`/`minimumNumberOfCalls(5)` is a **count-based** window with no time decay. On the shared, high-volume `emailService` breaker that window closes in seconds; on the new, low-volume `registrationEmailService` breaker the same 5-call window can span hours, so three registration failures from a morning signup spike can still be sitting in the window that afternoon when a fourth arrives. The isolation itself is still correct and still the settled call — record this in Dev Notes as the concrete shape of D-8's "one extra breaker to watch" cost, not a new problem to solve in this story
- [Source: requirements/ses-email-consolidation.md#5 Phase 4 item 5, #9.2 D-8; verified against `ComponentConfig.java:88-100` and `MailManager.java:70`; L3/L4 corrections per code review 2026-09-12]

**AC6 — Tests: rewrite the three existing listener tests, add the new coverage the source doc specifies**

- Given `CoachRegistrationEmailListenerTest`, `ParentRegistrationEmailListenerTest`, `PlayerRegistrationEmailListenerTest` already exist and assert the pre-this-story direct-port-call behavior (mock `OutboundEmailSender`, assert `.send(...)` called with a rendered `OutboundEmailRequest`, assert the UUID-shaped correlation id, assert the widened catch swallows a blank address / transport exception) — **rewrite, do not delete-and-recreate**, all three to assert the new behavior instead: mock `NotificationOutboxSupport`, assert `enqueueEmail(EmailTemplate.X, recipientMatchingEmailAndLangKeyAndFirstname, dataMapWithExactlyOneKey, anySendId)` is called once per event, that each call uses a **distinct** `sendId` across the two methods in the same test class, that an `enqueueEmail`-thrown exception is caught and logged rather than propagating (mirroring `BookingEmailListenerTest`'s pattern — that class exists, use it as the mocking-shape reference directly), and **keep a blank-address case** per AC1's retained guard (do not simply delete the existing blank-address tests — adapt them to assert the early-return-before-`enqueueEmail` behavior)
- **Corrected test design for `RegistrationEmailDurabilityIT` (S1) — `BookingReminderEmailWiringIT` is the wrong model.** That class runs with `enable.test.mail=true`, which swaps the entire `MailManager` bean for `TestMailManager` (`TestConfig.java:88-91`) and asserts against `TestMailManager`/the `outbox_messages` table directly — it never produces an `EnvelopeEntity` row at all, so there is no `FAILED`/`retry=true` state for anything to re-drive, and the exact assertions this AC needs are structurally unreachable in that shape. `RegistrationEmailDurabilityIT` must instead run with **`enable.test.mail=false`** (a second Spring context — accept the CI cost) so the real `MailManager` bean is active, and must call `EmailRetryScheduler.retryFailedEmails()` **directly** rather than waiting on its `@Scheduled` cadence, since `app.scheduling.enabled=false` under the test profile
- **Avoid the dual-retry-driver race (S2).** A `FAILED`/`retry=true` envelope can be re-driven by *two* independent mechanisms: `EmailRetryScheduler`'s 60s `@Scheduled` pass, and the underlying generic outbox's own per-row backoff (`NotificationEmailOutboxHandler.handle` re-throws on a retryable `FAILED` result, and `OutboxRowProcessor` backs the row off 30s → 1m → 2m → … independently). Both call `sendEmailSync` and both increment `attempts` on the same row; racing them in a test risks `ATTEMPTS_EXHAUSTED` firing before (or instead of) the `DEADLINE_EXPIRED` assertion this AC wants. **Do not let a failing send flow through the full pipeline to produce test state.** Persist the `EnvelopeEntity` row directly with the exact state each assertion needs (a past `deadline` for the expiry case; `attempts`/`status=FAILED`/`retry=true` for the re-drive case), then invoke `EmailRetryScheduler.retryFailedEmails()` once and assert its outcome — deterministic, and it tests the scheduler's own logic rather than an emergent race between two unrelated retry mechanisms. Note the dual-driver behavior itself in Dev Notes as a pre-existing, surprising property of the shared outbox — out of this story's scope to fix
- And add `OtpDeadlineParityTest` (source doc #7.2 item 22), **scoped to outbox-routed templates only (S4)** — say so in the test's own javadoc. Iterate every `EmailTemplate` value **except `SEND_OTP`** (the login-2FA flow, which is not routed through `NotificationOutboxSupport.enqueueEmail` and carries its own independent 10-minute deadline hardcoded at `TwoFactorLoginService.java:60` — asserting `SEND_OTP.deliveryDeadline() == 24h` here would pin a value that enum accessor advertises but that flow never reads, which is misleading, not protective). Assert every OTP-bearing outbox-routed template (`COACH_OTP`, `PARENT_OTP`, `PLAYER_OTP`) returns a `deliveryDeadline()` strictly less than `Duration.ofMinutes(10)`, and every other outbox-routed template returns the 24-hour default
- And add `DuplicateSendIdTest` per AC4's reframed version — it proves a collision is logged and leaves one row, not that duplicate sends are prevented
- **Fix the two non-existent file references from the story's first draft (L5):** there is no `MailManagerErrorClassificationTest` in this codebase — the file that exists is `MailManagerResilienceTest`; check whether it needs a new case for the AC4 change (it likely does not, now that AC4 no longer touches `NON_REPAIRABLE_ERRORS`). Separately, `NotificationOutboxSupport`'s own javadoc (`:79`) cites `EmailDataStringContractTest` as an existing build gate — that file also does not exist; this story does not depend on it, but do not go looking for it
- And run the full targeted set unchanged after this story to confirm nothing else regressed: `MailManagerIT`, `EmailRetrySchedulerIT`/`Test`, `NotificationEmailOutboxAtomicityIT`, `BookingReminderEmailWiringIT` (source doc §7.1 — these should need **no changes**, since `enable.test.mail=true` replaces the whole `MailManager` bean and none of them touch the registration listeners)
- [Source: requirements/ses-email-consolidation.md#7.2 items 20-22, #7.1; S1/S2/S4/L2/L5 corrections per code review 2026-09-12 — see story-review.md]

**AC7 — Decide, explicitly, what happens to OTP codes and verification tokens once they flow through `MailManager`'s logging (new AC, found by code review 2026-09-12, S3)**

- Given today the registration listeners render the OTP into HTML and log only `correlationId` (`CoachRegistrationEmailListener.java:70-81`), and `MailService` logs only `correlationId`/`messageId` — the OTP code and the verification token never appear in application logs
- Then recognize that routing through `MailManager` changes this, newly, for a **different sensitivity class** than the templates already on this path (booking/session-pack data is not a bearer secret; an OTP code and a verification-URL token are):
  - `MailManager.java:64` — `logger.info("sendEmailFrom template called:  Envelope {}", envelope)`. `Envelope` is a `record`; its generated `toString()` prints every component **including `data`**, i.e. `{otpCode=123456}` or `{verifyUrl=…&token=<uuid>}`, at **INFO**, on every send
  - `MailManager.java:110` — `logger.error("… {}", envelopeEntity, exception)`; `EnvelopeEntity.toString()` (`:147-162`) also explicitly prints `data`
  - `EnvelopeEntity.data` is a persisted `jsonb` column (`:41-43`) with **no purge or retention job anywhere in the repo** (checked: no scheduler, no delete call against `envelope_entity`) — the OTP and the verification token live in the database indefinitely
  - There is no log-masking converter anywhere in this project (checked `src/main/java`, `src/main/resources`)
  - This is already true today for activation/password-reset tokens, which already flow through `MailManager` via `AccountManagementFacade`/`EmailRegistrationStrategy` — this AC is not the first instance of the pattern, but it is the first time an OTP **code** (not a token requiring URL access, but a value a user types directly) joins it
- Then **make an explicit, recorded decision** rather than letting this ride in silently — options, roughly in order of effort: (a) mask/omit `data` for OTP-bearing templates specifically in both log statements (smallest, template-scoped fix); (b) demote `MailManager.java:64`'s log to DEBUG (reduces exposure surface but does not close the DB-column or ERROR-log cases); (c) accept in writing that this is consistent with the pre-existing activation/password-reset exposure and out of scope for this story, with an explicit owner sign-off recorded in this file's Dev Notes once decided
- **Do not silently ship option (c) by omission** — a decision not written down here reads, on the next audit, as an oversight rather than a choice
- [Source: found during story creation by code review 2026-09-12 (S3) — no requirements-doc citation exists for this AC]

## Tasks / Subtasks

- [x] **Task 1 — AC1: registration listeners move to BEFORE_COMMIT + outbox**
  - [x] 1.1 Rewrite `CoachRegistrationEmailListener` (both methods): `BEFORE_COMMIT`, blank-address guard mirroring `BookingEmailListener`, build `data`/`Recipient`, `enqueueEmail(...)`, `catch (Exception e)`, drop `templateEngine`/`messageSource`/`OutboundEmailSender`, add `NotificationOutboxSupport`
  - [x] 1.2 Same rewrite for `ParentRegistrationEmailListener`
  - [x] 1.3 Same rewrite for `PlayerRegistrationEmailListener`
  - [x] 1.4 Confirm `ApiAdvice` maps `UnexpectedRollbackException` to a sensible response (verify the existing `Throwable`-catchall → 500 is adequate; no code change expected) and record the confirmation in Dev Notes
- [x] **Task 2 — AC2: `firstname` survives the outbox round trip**
  - [x] 2.1 Add nullable `firstname` to `NotificationOutboxSupport.NotificationEmailPayload`, populate from `recipient.getFirstname()` in `enqueueEmail`
  - [x] 2.2 Set `firstname` on the reconstructed `Recipient` in `NotificationEmailOutboxHandler.handle`
  - [x] 2.3 Registration listeners (Task 1) call `recipient.setFirstname(event.firstName())` before `enqueueEmail`
  - [x] 2.4 New regression test proving the round trip (firstname survives enqueue → handle)
- [x] **Task 3 — AC3: OTP delivery deadline, checked on the send path**
  - [x] 3.1 Add `EmailTemplate.deliveryDeadline()`, default `Duration.ofDays(1)`, override `Duration.ofMinutes(5)` for `COACH_OTP`/`PARENT_OTP`/`PLAYER_OTP`
  - [x] 3.2 `NotificationOutboxSupport.enqueueEmail` reads `template.deliveryDeadline()` instead of the fixed constant
  - [x] 3.3 Add deadline check to `NotificationEmailOutboxHandler.handle` before calling `sendEmailSync` — log `[NOTIFICATION_EMAIL_DEADLINE_EXPIRED]` WARN and return if expired
- [x] **Task 4 — AC4: distinct `sendId`, collision surfaces as logged retried failure**
  - [x] 4.1 Each of the six listener methods generates its own `UUID.randomUUID().toString()` `sendId` (covered by Task 1)
  - [x] 4.2 `MailManager`: change `envelopeEntityRepository.save(envelopeEntity)` to `saveAndFlush(...)`, let `DataIntegrityViolationException` propagate uncaught — no recovery catch, no `NON_REPAIRABLE_ERRORS` change
  - [x] 4.3 Update `MailManagerResilienceTest` call-site verifications from `save(...)` to `saveAndFlush(...)`
  - [x] 4.4 New `DuplicateSendIdTest` — a genuine collision yields exactly one persisted row and a logged failure
- [x] **Task 5 — AC5: registration/OTP mail gets its own circuit breaker name**
  - [x] 5.1 Add `EmailTemplate.circuitBreakerName()`, default `"emailService"`, override `"registrationEmailService"` for the six registration templates
  - [x] 5.2 `MailManager.sendEmailSync`: derive breaker id from `envelope.emailTemplate().circuitBreakerName()`, null-guard or move inside the existing `try`
  - [x] 5.3 Confirm `ComponentConfig` needs no change (record this explicitly in Dev Notes) and record the L4 cost-framing note
- [x] **Task 6 — AC6: tests**
  - [x] 6.1 Rewrite `CoachRegistrationEmailListenerTest`/`ParentRegistrationEmailListenerTest`/`PlayerRegistrationEmailListenerTest` to assert outbox-enqueue behavior, distinct `sendId`s, caught-and-logged enqueue failure, retained blank-address case
  - [x] 6.2 New `RegistrationEmailDurabilityIT` — `enable.test.mail=false` context fork, calls `EmailRetryScheduler.retryFailedEmails()` directly, persists `EnvelopeEntity` rows directly for deterministic re-drive/expiry assertions
  - [x] 6.3 New `OtpDeadlineParityTest`, scoped to outbox-routed templates only (excludes `SEND_OTP`)
  - [x] 6.4 Run the unchanged targeted set to confirm no regressions: `MailManagerIT`, `EmailRetrySchedulerIT`/`Test`, `NotificationEmailOutboxAtomicityIT`, `BookingReminderEmailWiringIT`
- [x] **Task 7 — AC7: OTP/token logging exposure decision**
  - [x] 7.1 Decide and implement: mask `data` for the six registration templates in both `MailManager` log statements (option a)
  - [x] 7.2 Record the decision explicitly in Dev Notes/Completion Notes
- [x] **Task 8 — Story wrap-up**
  - [x] 8.1 Update File List, Change Log, Dev Agent Record
  - [x] 8.2 Run full targeted regression pass per `docs/validation-strategy.md` (no `mvn verify`)

### Review Findings

_Code review 2026-09-12 (`/bmad-code-review`, 3 adversarial layers: Blind Hunter / Edge Case Hunter / Acceptance Auditor). 5 decision-needed, 10 patch, 5 deferred, 8 dismissed as false positives with recorded evidence._

- [x] [Review][Patch] **The 5-minute OTP deadline is exactly the outbox sweep interval, so a discarded inline drain means zero delivery attempts** — `app.outbox.sweep-ms` defaults to `300000` (`OutboxService.java:117`) with no override in any profile, and `outboxDrainPool` uses `ThreadPoolExecutor.DiscardPolicy` (`OutboxConfig.java:49`, core 1 / max 2 / queue 50). Whenever the inline AFTER_COMMIT drain is dropped — signup burst filling the queue, pod killed between commit and drain, shutdown budget exceeded — the sweep is the documented safety net, and it arrives at or after the 5-minute deadline. The new guard then fires, `handle()` returns normally, and `OutboxRowProcessor` deletes the row: the OTP is never attempted, never retried, and leaves one WARN. `OutboxConfig:48`'s stated invariant ("no row is ever lost") becomes false for exactly the three OTP templates. Two compounding effects: (a) `OutboxRowProcessor.backoffFor` is 30s/1m/2m/4m, so a transient SES blip is past the deadline by attempt 4 and is discarded rather than recovered — where the old 24h default recovered it; (b) the deadline is stamped on the producer pod (`NotificationOutboxSupport:113`) and evaluated on the consumer pod (`NotificationEmailOutboxHandler:75`) under `SKIP LOCKED`, so a 24h window absorbed NTP skew and a 5-minute one does not. Options: raise the OTP deadline above sweep+backoff; lower `app.outbox.sweep-ms`; make the guard skew-tolerant; or accept, on the basis that `POST /resend-otp` is the user-facing recovery. [blind+edge] **RESOLVED — option 1:** raise the three OTP templates' `deliveryDeadline()` from 5 min to **8 min** (clears the 300s sweep so a sweep-recovered OTP is always attempted; leaves >=2 min of the 10-min TTL in the worst case, ~5 min in the common case). `app.outbox.sweep-ms` stays at its 300000 default. Also update `OtpDeadlineParityTest:46`, which pins `isEqualTo(Duration.ofMinutes(5))`; the `< Duration.ofMinutes(10)` bound at :38 still holds. Accepted trade: this erodes AC3's original "roughly half the TTL" rationale in exchange for a guaranteed delivery attempt.
- [x] [Review][Patch] **`*_EMAIL_VERIFY`'s 24h deadline equals the verification token's own 24h TTL** — AC3's design rule ("deadline strictly under the TTL") was applied to the three OTP constants and not to the three verify constants that share the same coupling. `EmailTemplate.java:14,16,18` gives 24h; `CoachRegistrationService.java:249` (identical in Parent/Player) mints `token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))` in the same transaction. A verification mail delivered near its deadline carries a token that expires seconds later, so the user clicks and gets an unexplained failure whose only recovery is resend. Options: shorten the verify deadline (e.g. 12h); lengthen the token TTL; or accept and record why the rule applies to OTP only. [edge] **RESOLVED — option 1:** shorten the three `*_EMAIL_VERIFY` deadlines to **12h**, leaving half the token's 24h TTL. Update `OtpDeadlineParityTest`'s default-deadline assertion, which currently expects 24h for them.
- [x] [Review][Patch] **OTP codes and verification tokens are now durably persisted in cleartext, and AC7 addressed only the log channel** — AC1 moves the six templates from a fire-and-forget in-memory send onto a durable outbox, so `data` (`{"otpCode": …}` / `{"verifyUrl": …&token=…}`) is now serialised into `outbox_messages.payload` and into `envelope_entity.data` (`jsonb`, `EnvelopeEntity.java:41-43`), which has no purge or retention job anywhere in the repo. AC7's own comment names `data` "a bearer secret" and then masks two SLF4J call sites while leaving the larger, indefinite at-rest exposure unaddressed and unrecorded. Options: add a retention/purge job for `envelope_entity`; null out `data` for sensitive templates once `SENT`; encrypt the column; or accept explicitly and write it into the AC7 decision record. [blind] **RESOLVED — option 2:** null out `EnvelopeEntity.data` for the sensitive templates once the send reaches `SENT`, in both `MailManager.sendEmailSync` persistence branches. Retained while `FAILED`, since a re-drive needs it. Residue to record explicitly: rows that end terminal-but-failed (`DEADLINE_EXPIRED` / `ATTEMPTS_EXHAUSTED`, both set by `EmailRetryScheduler`) keep their `data` — closing that too is a follow-up, not part of this decision.
- [x] [Review][Patch] **`SEND_OTP` is excluded from masking, and AC7's premise for excluding it is factually wrong** — AC7 states this story is the first time an OTP *code* joins the `MailManager` logging path. It is not: `TwoFactorLoginService.processLogin` builds `new SendMailEvent(…, EmailTemplate.SEND_OTP, …, Map.of("otpCode", otp, …))` which already flows through `SendMailListener` → `MailManager.sendEmailSync` and is printed unmasked by the INFO log today. `SENSITIVE_DATA_TEMPLATES` (`MailManager.java:56-59`) deliberately excludes it, and the Dev Notes' out-of-scope list names `ACTIVATION`/`PASSWORD_RESET`/`EMAIL_CHANGE`/`PROFILE_CHANGE` but not the one pre-existing case in the *same* sensitivity class. Adding `SEND_OTP` to the set is a one-line change with no behavioural risk; the alternative is to correct the premise and name it in the out-of-scope list. [auditor] **RESOLVED — option 1:** add `SEND_OTP` to `SENSITIVE_DATA_TEMPLATES` and correct the AC7 premise. Combined with the keys-preserving redaction patch below, `SEND_OTP`'s `helpCode` value is redacted along with `otpCode`; the key name survives, so the line still shows a help code was present.
- [x] [Review][Patch] **The first-attempt deadline guard leaves no durable record at all, asymmetric with the retry path** — `NotificationEmailOutboxHandler:75-80` returns before `MailManager.sendEmailSync` is reached, so no `EnvelopeEntity` is ever created; `OutboxRowProcessor` then deletes the outbox row. `EmailRetryScheduler:97-101` handles the identical condition by persisting `EmailDeliveryStatus.DEADLINE_EXPIRED` with `retry=false`. A dropped registration OTP is therefore invisible to every operational surface — no status row, no `[OUTBOX_STUCK]`, no metric — and "how many OTPs were dropped as stale" is answerable from the DB on one path and only from log scraping on the other. AC3 literally specified WARN-and-return, and `RegistrationEmailDurabilityIT.expiredDeadlinePayload_isReleasedWithNoDeliveryAttempt` now regression-locks the no-row outcome, so this is a deliberate choice worth re-confirming rather than a bug. Options: persist a `DEADLINE_EXPIRED` envelope before returning; add a counter; or accept log-only. [blind+edge+auditor — all three layers] **RESOLVED — option 1:** persist a `DEADLINE_EXPIRED` / `retry=false` `EnvelopeEntity` before returning, symmetric with `EmailRetryScheduler:97-101`. Must go through `findBySendId` first and update-if-present rather than blind-insert, or a re-drive collides on the `send_id` unique constraint. `RegistrationEmailDurabilityIT.expiredDeadlinePayload_isReleasedWithNoDeliveryAttempt` currently asserts the no-row outcome and must be updated to assert the `DEADLINE_EXPIRED` row instead.
- [x] [Review][Patch] **`MailManagerDuplicateSendIdIT` cannot fail if AC4's `saveAndFlush` is reverted** — the `catch (Throwable)` wraps `requiresNewB.executeWithoutResult(...)`, i.e. the `TransactionTemplate`, not `sendEmailSync`. With the pre-story `save()` the INSERT fires at that same template's commit and surfaces as one of the two asserted types from the same call, and `rowCountForSendId` is still 1 — both assertions pass either way. The test's own javadoc concedes the gap ("a hand-built `MailManager` has no Spring proxy, so its `@Transactional` annotations do not apply on their own"). Move the catch inside the transaction so the failure is observed synchronously at `saveAndFlush`; narrow `catch (Throwable)` (today any unrelated failure in thread B satisfies "not null"); and wrap the executor in try/finally with `shutdownNow` so a `first.get(30s)` timeout cannot leak a thread holding a row lock for the rest of the Failsafe run. [src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerDuplicateSendIdIT.java]
- [x] [Review][Patch] **AC7's masking has no regression test, and the Dev Notes record a verification the committed test cannot perform** — lines 213-214 state "Verified in `MailManagerDuplicateSendIdIT`'s captured log output: `data={data=[REDACTED]}` for `COACH_OTP`", but that IT contains no appender or log assertion; `grep -rn "REDACTED" src/test/java` matches only `BodySanitizerTest`. A security control with no guard: a change to `Envelope`'s shape, to the ERROR format string, or a new registration template silently reintroduces the leak. Add a masking test and correct the Dev Notes claim. [ses-1-4-registration-email-durability.md:213]
- [x] [Review][Patch] **`OtpDeadlineParityTest`'s "scoped to outbox-routed templates only (S4)" claim is false** — the javadoc says it, but `everyOtherTemplate_keepsTheDefaultDeadline()` iterates `EnumSet.allOf(EmailTemplate.class)` excluding only `SEND_OTP`, so it pins `deliveryDeadline() == 24h` for `ACTIVATION`, `PASSWORD_RESET`, `EMAIL_CHANGE`, `PROFILE_CHANGE`, `CREATION_DUP`, the video/report/reliability templates and `NONE` — every one a direct-`Envelope` producer with a caller-supplied deadline, i.e. exactly the category S4 said must not be pinned because the advertised value is misleading rather than protective. The implementation follows AC6's literal wording, which contradicts AC6's own framing sentence; fix the test scoping and the AC text together. [src/test/java/com/softropic/skillars/platform/notification/contract/OtpDeadlineParityTest.java]
- [x] [Review][Patch] **The enum's block comment claims a 5-minute deadline for three constants that get 24 hours** — "Story ses-1.4 AC3/AC5: shorter delivery deadline (5 min…)" heads a block of six constants; `COACH_EMAIL_VERIFY`, `PARENT_EMAIL_VERIFY` and `PLAYER_EMAIL_VERIFY` are `Duration.ofDays(1)`, and `OtpDeadlineParityTest` asserts the contradiction explicitly. [src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java:11-19]
- [x] [Review][Patch] **The listeners' failure log carries no recoverable identifier** — the replaced code generated `correlationId` outside the try specifically "so the catch can name it"; the new code passes `UUID.randomUUID().toString()` inline as a call argument, so the sendId is not in scope in the catch and is not logged, and neither is the address, langKey, nor any user reference. `BookingEmailListener` — the pattern AC1 said to mirror exactly — does log a business identifier (`kv("bookingId", …)`). When the enqueue-failure path fires, the operator learns only that *some* coach lost *some* email. The message string also has no `{}` placeholder, so the template name appears only in the JSON field, not the rendered line. Hoist the sendId to a local and log it. [Coach/Parent/PlayerRegistrationEmailListener.java]
- [x] [Review][Patch] **The listener class javadoc states only half of its own failure contract** — it says a failed outbox INSERT makes `POST /register` fail with a 500, which is true for a DB/constraint failure (the transaction is already rollback-only). But `enqueueEmail` also throws `IllegalStateException` on a serialisation failure (`NotificationOutboxSupport.java:131`), and the listener's `catch (Exception)` swallows that — the registration commits with no outbox row and no email, which is the silent-loss mode AC1 exists to remove. The test javadoc asserts the opposite of the class javadoc. The *behaviour* is correct and deliberate — `NotificationOutboxSupport`'s "Failure semantics" section documents exactly this split, and AC1 required mirroring `BookingEmailListener` — so fix the javadoc, not the code. [Coach/Parent/PlayerRegistrationEmailListener.java class javadoc]
- [x] [Review][Patch] **`NotificationOutboxSupport`'s javadoc is now stale on a file this story edits** — `enqueueEmail`'s javadoc still says "the only two callers of this method are the two email listeners, both `BEFORE_COMMIT`"; there are now five (Booking, SessionPack, Coach/Parent/Player registration). The class-level failure-semantics prose likewise still reasons only about "the booking commits while its email is lost", which is now also the registration and resend-OTP contract. The correct new-failure-mode text lives in the listener javadoc but not here, where the `MANDATORY`/rollback-only behaviour actually is. [src/main/java/com/softropic/skillars/platform/notification/service/NotificationOutboxSupport.java]
- [x] [Review][Patch] **Redaction erases the key names, not just the values** — `loggableEnvelope` substitutes `Map.of("data", "[REDACTED]")`, so the INFO line renders `data={data=[REDACTED]}` regardless of whether the original map held `otpCode`, `verifyUrl`, both, or was empty — and an empty `data` map is a real failure mode worth seeing. Masking values while preserving `keySet()` costs nothing and keeps the line diagnostic. [src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:170-176]
- [x] [Review][Patch] **Listener test assertions are weaker than AC6's wording** — only `CoachRegistrationEmailListenerTest.onVerificationEmail_happyPath_…` asserts `recipient.getLangKey()`; the Parent and Player happy-path tests and all three `onOtpEmail` happy-path tests assert email + firstname only. langKey is the one recipient field with i18n consequences — `EmailContentRenderer.java:55` derives the whole `Locale` from it. Separately, all six `*_enqueueThrows_caughtAndLogged_*` tests assert only `doesNotThrowAnyException()`; the method name promises "logged" and nothing verifies it. [Coach/Parent/PlayerRegistrationEmailListenerTest.java]
- [x] [Review][Patch] **`MailManager`'s new comment names "both real callers" and omits a third** — the `@Async` `sendEmailFromTemplate` → `sendEmailSync` self-invocation path (`MailManager.java:58-60`) bypasses the proxy, so `sendEmailSync`'s `REQUIRES_NEW` never starts a nested transaction and a `saveAndFlush` failure rolls back the async listener's transaction and is swallowed by `AsyncConfig`'s uncaught-exception handler. Six producers use that path. Behaviour is unchanged by this story — with `save()` the same rollback happened at commit — so this is a comment-accuracy fix, not a defect. [src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:160-163]
- [x] [Review][Defer] **`envelope_entity` has no Flyway DDL and its `send_id` unique index is unversioned** [src/main/resources/db/migration/] — deferred, pre-existing. No `CREATE TABLE envelope_entity` exists in any of the 129 migrations, nor in `src/test/resources/sql/`, and `spring.jpa.hibernate.ddl-auto: none` (`application.yaml:66`) with `baseline-on-migrate: true`. AC4's `saveAndFlush` and `MailManagerDuplicateSendIdIT` both now depend on that unique constraint existing. The project's own `docs/dev-docs/notification/index.html:309` already records the gap.
- [x] [Review][Defer] **`EmailTemplate.valueOf(p.template())` creates an immortal poison outbox row for a removed or renamed constant** [src/main/java/com/softropic/skillars/platform/notification/service/NotificationEmailOutboxHandler.java:90] — deferred, pre-existing (the line is unchanged by this story). `OutboxRowProcessor` explicitly handles the analogous rolling-deploy case for a missing *handler*; a missing enum constant has no equivalent guard and can never resolve itself.
- [x] [Review][Defer] **`Map.of` → `HashMap` removes the fail-fast NPE on a null token** [Coach/Parent/PlayerRegistrationEmailListener.java] — deferred, not reachable today. `Map.of` rejected null values loudly; `HashMap` accepts them, so a null `otp`/`verifyUrl` would now round-trip the outbox and be delivered as an empty value with `status=SENT`. `generateOtp()` and the concatenated `verifyUrl` are never null, so there is no current trigger.
- [x] [Review][Defer] **AC7's masking is bypassed by the logged exception argument itself** [src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:137] — deferred, residual risk. `loggableData` redacts the interpolated argument while the stack trace beside it prints in full; a Jackson serialisation failure can echo the partially-written JSON and a JDBC exception can echo bound statement parameters. Hard to bound without a logging-level sanitiser.
- [x] [Review][Defer] **`RegistrationEmailDurabilityIT`'s scheduler cases operate on global repository state** [src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java] — deferred, fragility note. `retryFailedEmails()` polls the whole table and the helpers use `findAll()`; assertions are scoped by a UUID-unique address so it is correct today, but both grow with the suite.

### Code Review Follow-up (2026-09-12) — dev-story pass

All 15 `[Review][Patch]` findings verified against the code (each one independently reproduced or
directly confirmed by reading the referenced lines before touching anything — none taken on faith)
and applied. No false positives found among them. The 5 `[Review][Defer]` items were already accepted
as deferred by the review itself and required no code change. Summary by finding:

- **147** (5-min OTP deadline == 300s outbox sweep interval): OTP deadlines raised to **8 minutes**
  (`EmailTemplate`). `OtpDeadlineParityTest` updated.
- **148** (`*_EMAIL_VERIFY` 24h deadline == verification token's own 24h TTL): verify deadlines lowered
  to **12 hours** (`EmailTemplate`). `OtpDeadlineParityTest` updated.
- **149** (OTP/token persisted in cleartext indefinitely, AC7 addressed only the log channel):
  `MailManager` now nulls `EnvelopeEntity.data` for sensitive templates once a send reaches `SENT`, in
  both persistence branches (`scrubDataIfSentAndSensitive`). Retained while `FAILED` for re-drive.
- **150** (`SEND_OTP` excluded from masking, AC7's "first time" premise factually wrong):
  `SEND_OTP` added to `MailManager.SENSITIVE_DATA_TEMPLATES`; AC7 decision record corrected.
- **151** (first-attempt deadline guard left no durable record, asymmetric with `EmailRetryScheduler`):
  `NotificationEmailOutboxHandler.handle` now persists a `DEADLINE_EXPIRED`/`retry=false`
  `EnvelopeEntity` (via `findBySendId` first, not a blind insert) before returning.
  `RegistrationEmailDurabilityIT`'s corresponding test updated to assert the row instead of its absence.
- **152** (`MailManagerDuplicateSendIdIT` couldn't fail if `saveAndFlush` reverted to `save`): narrowed
  the caught exception type, added executor `shutdownNow` safety. The proposed "catch only around
  `sendEmailSync`" scope change was **tried and reverted** — reproduced against the real container,
  the collision surfaces at the transaction's commit either way (`hibernate.jdbc.batch_size: 15`), so
  that narrower scope made the test fail on correct code. Documented honestly in the class javadoc;
  mutation-sensitivity for `save()` vs `saveAndFlush()` specifically is covered by
  `MailManagerResilienceTest`'s existing mock-based `verify(...).saveAndFlush(...)`.
- **153** (AC7 masking had no regression test; Dev Notes claimed a verification no test performed): new
  `MailManagerRedactionTest` (5 cases: OTP/verify/SEND_OTP masked-with-keys-preserved, a non-sensitive
  control proving masking is conditional, and the ERROR-path log). Dev Notes' false claim corrected.
- **154** (`OtpDeadlineParityTest`'s "outbox-routed only" claim was false — it pinned 24h for every
  direct-`Envelope` producer too): rewritten with an explicit `OUTBOX_ROUTED` allow-list.
- **155** (enum block comment claimed 5 min for constants that get 24h/12h): comment corrected.
- **156** (listener failure log carried no recoverable identifier): `sendId` hoisted to a local in all
  six listener methods and added as a `kv(...)` argument to the error log, matching
  `BookingEmailListener`'s `kv("bookingId", ...)` pattern.
- **157** (listener class javadoc stated only half its own failure contract): corrected to document
  both outcomes — the atomic DB-failure case and the non-atomic serialisation-failure case.
- **158** (`NotificationOutboxSupport` javadoc stale — "only two callers", wrong package reference):
  corrected to reflect all five current callers and both listener packages.
- **159** (redaction erased key names via `Map.of("data", "[REDACTED]")`): new `maskedData` helper
  preserves every key, masks only values.
- **160** (listener tests weaker than AC6 — `langKey` not asserted everywhere, enqueue-failure tests
  didn't verify logging): `langKey` now asserted on every happy-path test; all six
  `*_enqueueThrows_caughtAndLogged_*` tests now capture and assert the actual log output (message text
  + a UUID-shaped `sendId` argument).
- **161** (`MailManager` comment named "both real callers", omitted the `@Async` self-invocation path):
  corrected to name and explain the third path.

**Full regression after follow-up:** all previously-green targeted tests still green, plus the new/
updated ones — 14 unit test classes and 7 IT classes re-run, all passing. No `mvn verify` (CI is the
gate), per `docs/validation-strategy.md`.

## Dev Notes

### Files this story touches

**Modify:**
- `platform/security/infrastructure/listener/CoachRegistrationEmailListener.java` — AC1, AC2
- `platform/security/infrastructure/listener/ParentRegistrationEmailListener.java` — AC1, AC2
- `platform/security/infrastructure/listener/PlayerRegistrationEmailListener.java` — AC1, AC2
- `platform/notification/contract/EmailTemplate.java` — AC3 (`deliveryDeadline()`), AC5 (`circuitBreakerName()`)
- `platform/notification/service/NotificationOutboxSupport.java` — AC2 (payload field), AC3 (read from enum instead of constant)
- `platform/notification/service/NotificationEmailOutboxHandler.java` — AC2 (reconstruct firstname), AC3 (deadline check before send)
- `platform/notification/service/MailManager.java` — AC4 (`save` → `saveAndFlush`, no new catch, no `NON_REPAIRABLE_ERRORS` change), AC5 (breaker-name derivation + null-guard/try placement), AC7 (log statement decision)

**No change expected (verify, don't assume):**
- `ComponentConfig.java` — AC5 explicitly does not need a change; confirm this holds once `circuitBreakerName()` is wired
- `EmailRetryScheduler.java` — the breaker-name and deadline changes are both derived from data the scheduler already carries through unchanged; `sendAll`'s existing `catch (Exception e)` (`:139-144`) already satisfies AC4's "log at ERROR with sendId" requirement with no change needed

**Rewrite (existing files):**
- `platform/security/infrastructure/listener/CoachRegistrationEmailListenerTest.java`
- `platform/security/infrastructure/listener/ParentRegistrationEmailListenerTest.java`
- `platform/security/infrastructure/listener/PlayerRegistrationEmailListenerTest.java`

**New:**
- `platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java` (or wherever the equivalent booking IT lives — check `BookingReminderEmailWiringIT`'s package first)
- `platform/notification/contract/OtpDeadlineParityTest.java` (or co-located with `EmailTemplate`)
- `platform/notification/infrastructure/MailManagerDuplicateSendIdTest.java` (name at implementer's discretion; the test's job is described in AC4)

### Pre-existing quirk this story exposed but does not need to fix (S2)

The outbox has two independent retry drivers that can both act on the same `EnvelopeEntity` row: `EmailRetryScheduler`'s 60s `@Scheduled` pass over `retry=true AND status=FAILED` rows, and the generic outbox's own per-row backoff (`NotificationEmailOutboxHandler.handle` re-throws on a retryable `FAILED` outcome, and `OutboxRowProcessor` backs that same outbox row off independently, 30s → 1m → 2m → … up to a 1h cap). Both ultimately call `sendEmailSync` and both increment `attempts` on the same row. This is pre-existing — booking and session-pack mail already go through the same handler — and not something this story introduces or needs to resolve. It matters here only because `EmailRetryScheduler.MAX_RETRY_ATTEMPTS = 6` can, in the worst case, be consumed by the pair faster than a naive test (or a naive mental model of "one retry mechanism") would predict — see AC6's `RegistrationEmailDurabilityIT` design for how to test around it deterministically instead of relying on it.

### Out of scope, explicitly

- **The login 2FA OTP flow** (`SEND_OTP` template, `LoginInfoService`, `SecurityConstants.OTP_TTL = 30 min`) — different flow, not touched.
- **Activation, creation-dup, password-reset, profile-change mail** — still direct-publish, `AFTER_COMMIT`, not routed through the outbox by this story (source doc §9.3: "Activation / password-reset / 2FA durability... stay on the older direct-publish AFTER_COMMIT model... Phase 4 moves registration/OTP only"). Do not opportunistically migrate these — they are explicitly a non-goal here even though they share the same `${recipient.firstname}` pattern AC2 fixes for the outbox path.
- **Multi-recipient partial-failure re-send** (§6.19) — pre-existing, unreachable today (every registration email has exactly one recipient), not fixed by this story.
- **The transaction-timeout hardening from §6.17** (`sendEmailSync`'s missing `@Transactional(timeout = ...)`) — not part of any Phase 4 item; if it still needs doing, it is a `ses-1.3` or general-hardening follow-up, not this story's scope. Do not fold it in silently.

### Task 1.4 confirmation — ApiAdvice / UnexpectedRollbackException

Verified by reading `ApiAdvice.java` end to end: there is no handler for `UnexpectedRollbackException`
(or any `TransactionException` supertype) anywhere in `ApiAdvice` or any of the other `*ApiAdvice`
classes. It therefore falls through to the class's own catch-all `@ExceptionHandler(Throwable.class)`
(`defaultErrorHandler`), which returns `500 INTERNAL_SERVER_ERROR` with a logged help code via
`ErrorLog.logError` and the generic `generic.unknown` message key — no exception detail leaks to the
client. That is a sensible response for the new failure mode AC1 introduces (a failed outbox `INSERT`
now rolling back `POST /register`/`POST /resend-otp` with a 500 instead of silently losing the email).
No code change was needed or made.

### Task 5.3 confirmation — ComponentConfig / L4 cost framing

Verified by reading `ComponentConfig.java`: `defaultCustomizer()`'s `factory.configureDefault(id -> ...)`
configures every circuit-breaker id identically, so `"registrationEmailService"` automatically gets the
same `CircuitBreakerConfig`/`TimeLimiterConfig` shape as `"emailService"` with no change to this class.
Cost framing (L4): `slidingWindowSize(5)`/`minimumNumberOfCalls(5)` is a count-based window with no
time decay. On the new, low-volume `registrationEmailService` breaker, that 5-call window can span
hours in practice, so three registration failures from a morning signup spike can still be sitting in
the window that afternoon when a fourth arrives. The isolation itself (D-8) is still correct and still
the settled call — this is the concrete shape of its "one extra breaker to watch" cost, not a new
problem introduced or solved by this story.

### AC7 decision — OTP/token logging exposure

**Decision: option (a)** — mask `data` for the sensitive templates in both `MailManager` log statements
(`sendEmailFrom template called: ...` at INFO, and the failure-path ERROR log). Implemented as
`MailManager.SENSITIVE_DATA_TEMPLATES` + `loggableEnvelope`/`loggableData` helpers, backed by a
key-preserving `maskedData` helper (code review 2026-09-12: the first draft's
`Map.of("data", "[REDACTED]")` erased the original key names — `data={otpCode=[REDACTED]}` is what
actually ships, not the misleading `data={data=[REDACTED]}` this section originally, and wrongly,
claimed).

**Correction (code review 2026-09-12):** this section previously claimed the masking was "Verified in
`MailManagerDuplicateSendIdIT`'s captured log output" — that was a manual console observation made
during development, not an assertion any test actually made; that IT contains no log appender or
assertion at all. `MailManagerRedactionTest` (new, added by this same review pass) is the actual
regression test: it captures `MailManager`'s real log output via a `ListAppender` and asserts, for
each sensitive template (including `SEND_OTP`, added per the correction below) and one non-sensitive
control (`BOOKING_CONFIRMED`), that the key name survives and the value is masked on both the INFO and
ERROR log statements.

**Correction (code review 2026-09-12, auditor finding):** the original template set
(`COACH_EMAIL_VERIFY`, `COACH_OTP`, `PARENT_EMAIL_VERIFY`, `PARENT_OTP`, `PLAYER_EMAIL_VERIFY`,
`PLAYER_OTP`) missed `SEND_OTP` — AC7's premise that this story was "the first time an OTP code joins
the MailManager logging path" was factually wrong: the login-2FA flow (`TwoFactorLoginService` →
`SendMailListener` → the `Envelope`-typed `MailManager.sendEmailFromTemplate`) already puts an OTP code
through this exact path today. `SEND_OTP` is now in `SENSITIVE_DATA_TEMPLATES` too.

**Scope, explicitly:** this is template-scoped, not a codebase-wide fix. `ACTIVATION`/`PASSWORD_RESET`/
`EMAIL_CHANGE`/`PROFILE_CHANGE` already flow through `MailManager` today (via `AccountManagementFacade`/
`EmailRegistrationStrategy`) with the same pre-existing exposure — that is unchanged and out of scope
for this story, per AC7's own framing ("this AC is not the first instance of the pattern"). The
`EnvelopeEntity.data` jsonb column's lack of a purge/retention job is also unchanged and out of scope —
AC7 asks for a decision on the two log statements specifically, not a retention policy.

This decision was made during dev-story execution, as the story explicitly authorized ("decision
explicitly deferred to dev-story" — see the Dev Notes gap note above AC7).

### Previous story intelligence (ses-1.3)

- The code review on `ses-1.3` reverted an interim workaround back to straightforward, correctly-scoped `@Conditional`/`@ConditionalOnProperty` bean gating rather than accepting a plausible-looking shortcut — the same discipline applies here: AC5's enum-driven breaker name was chosen specifically to avoid a five-call-site signature change that would have been "the first thing that works" but not the smallest correct change.
- `ses-1.3`'s own post-merge CI catch (documented in this repo's commit history, not the story file) was a `dev`+`test` profile-stacking bug in `application-test.yaml` that had nothing to do with the story's stated ACs — a reminder to actually boot an integration test under the real active profile combination (`dev`,`test`) rather than trusting an `ApplicationContextRunner`-based unit test alone when a change touches Spring configuration merging across profiles. This story's changes are pure Java (enum, listener, service), not YAML, so the specific failure mode does not recur, but the general lesson (run at least one real `@SpringBootTest` after a change that touches shared config, don't rely solely on mocked-unit-test green) applies to AC5 in particular.

### Git intelligence (last 5 commits)

`3c00173f` (ses-1.2) → `9e07b386` (ses-1.1) → `82bdf293` (ses-1.3) → `b083f651` (ses-1.3 CI fix) → `5f302d26` (deferred-work.md prune). Confirms the phase sequence this story continues; no other in-flight work touches `platform.notification` or `platform.security.infrastructure.listener` right now.

## Project Context Reference

See `_bmad-output/project-context.md` for platform-wide rules (module layering, testing conventions, `@Observed`, i18n). Nothing in that file is specific to this story beyond what's already cited above — this is a same-module, same-pattern change (mirroring `BookingEmailListener`), not new architecture.

## Dev Agent Record

### Debug Log

- Enum constructor delegation (`this(subjectKey, DEFAULT_DEADLINE, DEFAULT_BREAKER)`) referencing a
  static field from an enum constant's own constructor hit Java's "illegal reference to static field
  from initializer" — enum constants initialize before static fields on the same class. Fixed by
  inlining the default literals directly in the single-arg constructor instead of referencing static
  constants.
- `MailManagerResilienceTest` and `VideoModerationEmailListenerTest$RealMailManagerMappingAC122` both
  stubbed/verified `envelopeEntityRepository.save(...)` directly against a mock — AC4's `save` →
  `saveAndFlush` rename broke both until updated to stub/verify `saveAndFlush(...)` instead.
- `EmailDataRoundTripContractTest` constructed `NotificationEmailPayload` positionally — needed a
  `null` inserted for the new `firstname` component (AC2).
- First draft of `RegistrationEmailDurabilityIT`'s handler-level tests called
  `NotificationEmailOutboxHandler.handle(...)` with no ambient transaction; `findBySendId`'s
  `@Lock(PESSIMISTIC_WRITE)` requires one (production always calls `handle` from inside
  `OutboxRowProcessor`'s own `@Transactional(REQUIRES_NEW)`). Fixed by wrapping the call in
  `transactionTemplate.executeWithoutResult(...)`.
- `MailManagerDuplicateSendIdIT`'s two-thread race against a real unique constraint reproduced the
  target failure mode (`SQLState 23505 duplicate key value violates unique constraint`) on the first
  run — no flakiness observed across repeated local runs.
- Named the new AC4 test `*IT`, not `*Test` as the story's Dev Notes suggested ("name at implementer's
  discretion") — it needs the real Postgres-backed `EnvelopeEntityRepository` and a genuine
  unique-constraint conflict, and `IntegrationTestConventionTest` requires exactly that shape be filed
  under `*IT` so Failsafe (not Surefire) owns it.

### Completion Notes

All 7 ACs implemented and tested. Summary by AC:

- **AC1**: `CoachRegistrationEmailListener`/`ParentRegistrationEmailListener`/
  `PlayerRegistrationEmailListener` rewritten to `BEFORE_COMMIT` + `NotificationOutboxSupport`,
  mirroring `BookingEmailListener` exactly (blank-address guard, single dependency, `catch (Exception)`).
  Rendering (Thymeleaf + subject lookup) removed from all three classes entirely. `ApiAdvice`'s
  existing `Throwable` catch-all confirmed to map the new `UnexpectedRollbackException` failure mode to
  a sensible 500 — no code change needed (see Dev Notes).
- **AC2**: `NotificationOutboxSupport.NotificationEmailPayload` gained a nullable `firstname`
  component; `NotificationEmailOutboxHandler.handle` reconstructs it onto the `Recipient`. Existing
  producers (`BookingEmailListener`, `SessionPackEmailListener`) are unaffected (`null` round-trips as
  `null`). Proven end-to-end (not just at the payload level) by `RegistrationEmailDurabilityIT`, which
  publishes a real event and asserts the persisted `EnvelopeEntity`'s recipient firstname.
- **AC3**: `EmailTemplate.deliveryDeadline()` added (24h default; **8 min** for the three OTP
  templates, **12h** for the three `*_EMAIL_VERIFY` templates — both corrected from the initial 5min/24h
  by code review, see Review Findings 147/148); `enqueueEmail` reads it instead of the old fixed
  constant (now deleted). The first-attempt deadline guard was added to
  `NotificationEmailOutboxHandler.handle` — an already-expired payload is released with
  `[NOTIFICATION_EMAIL_DEADLINE_EXPIRED]` logged, and (per Review Finding 151) a durable
  `DEADLINE_EXPIRED`/`retry=false` `EnvelopeEntity` is persisted before returning, symmetric with
  `EmailRetryScheduler`'s identical precheck — proven by dedicated `RegistrationEmailDurabilityIT` cases.
- **AC4**: `MailManager.java`'s `save(...)` → `saveAndFlush(...)`, no recovery catch added, no
  `NON_REPAIRABLE_ERRORS` change. `MailManagerDuplicateSendIdIT` drives a real two-thread race against
  Postgres and reproduces the exact `23505` unique-constraint failure the AC describes, asserting
  exactly one persisted row and an uncaught, propagating exception.
- **AC5**: `EmailTemplate.circuitBreakerName()` added (`"emailService"` default, `"registrationEmailService"`
  for the six registration templates); `MailManager.sendEmailSync` derives the breaker id from the
  envelope's template, moved inside the `try` with a null-guard fallback. Confirmed (Dev Notes) that
  `ComponentConfig` needs no change.
- **AC6**: all three listener test classes rewritten (mocked `NotificationOutboxSupport`, distinct
  `sendId` assertions, retained blank-address case, caught-enqueue-failure case). New
  `RegistrationEmailDurabilityIT` (6 cases: BEFORE_COMMIT wiring + firstname for both verify/OTP
  templates, the AC3 first-attempt deadline guard in both directions, and deterministic
  `EmailRetryScheduler` re-drive/expiry for registration templates). New `OtpDeadlineParityTest`
  (scoped to outbox-routed templates, excludes `SEND_OTP` per S4). The unchanged targeted set
  (`MailManagerIT`, `EmailRetrySchedulerIT`/`Test`, `NotificationEmailOutboxAtomicityIT`,
  `BookingReminderEmailWiringIT`, plus `EmailTransportArchitectureTest`/`NoStraySmtpConfigTest` and
  `VideoModerationEmailListenerTest`/`VideoModerationAdminAlertEnvelopeIT` for the shared
  `MailManager`/breaker changes) all pass unchanged except the two `save`→`saveAndFlush` mock-stub
  fixes noted in the Debug Log.
- **AC7**: decided and implemented option (a) — see the dedicated "AC7 decision" Dev Notes section.

**Validation performed** (per `docs/validation-strategy.md` — targeted tests only, no `mvn verify`):
`mvn compile` / `mvn test-compile` clean; targeted unit tests (`CoachRegistrationEmailListenerTest`,
`ParentRegistrationEmailListenerTest`, `PlayerRegistrationEmailListenerTest`, `MailManagerResilienceTest`,
`EmailDataRoundTripContractTest`, `OtpDeadlineParityTest`, `EmailRetrySchedulerTest`,
`VideoModerationEmailListenerTest`, `BookingEmailListenerTest`, `SessionPackEmailListenerTest`,
`EmailTransportArchitectureTest`, `NoStraySmtpConfigTest`, `IntegrationTestConventionTest`) all green;
targeted ITs via scoped `failsafe:integration-test` runs (`MailManagerDuplicateSendIdIT`,
`RegistrationEmailDurabilityIT`, `MailManagerIT`, `EmailRetrySchedulerIT`,
`NotificationEmailOutboxAtomicityIT`, `BookingReminderEmailWiringIT`, `VideoModerationAdminAlertEnvelopeIT`)
all green. No `mvn verify` run — GitHub CI is the full-suite gate.

## File List

**Modified (main):**
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/ParentRegistrationEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/PlayerRegistrationEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/NotificationOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/NotificationEmailOutboxHandler.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java`

**Rewritten (test):**
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/listener/ParentRegistrationEmailListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/listener/PlayerRegistrationEmailListenerTest.java`

**Modified (test — incidental fixes required by AC4/AC2):**
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerResilienceTest.java` (`save` → `saveAndFlush`)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListenerTest.java` (`save` → `saveAndFlush` mock stub)
- `src/test/java/com/softropic/skillars/platform/notification/service/EmailDataRoundTripContractTest.java` (payload constructor arg)
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java` (`EXPECTED_TEST_PROPERTY_SOURCE_COUNT` 5 → 6)

**New (test):**
- `src/test/java/com/softropic/skillars/platform/notification/contract/OtpDeadlineParityTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerDuplicateSendIdIT.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java`

**Code Review Follow-up (2026-09-12) — additional changes:**
- Modified (main, again): `EmailTemplate.java` (deadlines 5min→8min, 24h→12h, comment fix),
  `MailManager.java` (`SEND_OTP` added to sensitive set, key-preserving `maskedData`, data-scrub-on-SENT,
  third-caller comment fix), `NotificationEmailOutboxHandler.java` (persist `DEADLINE_EXPIRED`),
  `NotificationOutboxSupport.java` (javadoc), `Coach/Parent/PlayerRegistrationEmailListener.java`
  (sendId hoisted + logged, class javadoc fix)
- Modified (test, again): `OtpDeadlineParityTest.java` (rewritten — allow-list scoping + new deadline
  values), `MailManagerDuplicateSendIdIT.java` (narrowed catch type, executor safety, corrected javadoc),
  `RegistrationEmailDurabilityIT.java` (deadline assertions updated, `DEADLINE_EXPIRED` row assertion),
  `Coach/Parent/PlayerRegistrationEmailListenerTest.java` (`langKey` assertions, real log-output
  verification on the enqueue-failure tests)
- New: `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerRedactionTest.java`

**Implementation artifacts:**
- `_bmad-output/implementation-artifacts/ses-1-4-registration-email-durability.md` (this file — Tasks/Subtasks, Dev Notes confirmations, Dev Agent Record, File List, Change Log, Status added during dev-story; Review Findings checked off and Code Review Follow-up section added)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status transitions + code review summary)
- `_bmad-output/implementation-artifacts/deferred-work.md` (the review's 5 `[Defer]` findings recorded to the running ledger, by the review process itself)

## Change Log

- 2026-09-12: Story implementation complete — all 7 ACs (AC1-AC7) implemented and tested. Registration
  verification/OTP listeners moved from direct AFTER_COMMIT port calls to BEFORE_COMMIT + the durable
  outbox; recipient firstname now survives the outbox round trip; OTP templates get a delivery
  deadline enforced on both the retry path and the first-attempt send path; `sendId` collisions surface
  as a synchronous, propagating, loggable failure instead of an unreachable in-method recovery attempt;
  registration/OTP mail gets its own `registrationEmailService` circuit breaker; OTP codes and
  verification-URL tokens are masked out of `MailManager`'s INFO/ERROR logs. 4 pre-existing test files
  required incidental fixes for the `save`→`saveAndFlush` rename and the new payload field. Status →
  review.
- 2026-09-12: Code review (`/bmad-code-review`, 3 layers) applied — 5 decision-needed (all resolved) +
  10 patch findings, all 15 verified against the code and applied; 5 deferred to ledger; 8 dismissed as
  false positives. Notable corrections: OTP deadline 5min→8min (equaled the outbox sweep interval,
  risking zero delivery attempts on a sweep-recovered send); `*_EMAIL_VERIFY` deadline 24h→12h (equaled
  the verification token's own TTL); `EnvelopeEntity.data` now nulled on `SENT` for sensitive templates
  (closes an indefinite-cleartext-persistence gap AC7 didn't address); `SEND_OTP` added to the masked
  template set (AC7's "first time an OTP joins this path" premise was factually wrong — the login-2FA
  flow already did); a first-attempt deadline expiry now persists a durable `DEADLINE_EXPIRED` record
  instead of log-only; redaction now preserves key names instead of collapsing them; new
  `MailManagerRedactionTest` (masking had no regression test); `OtpDeadlineParityTest` rewritten with an
  explicit outbox-routed allow-list (its "outbox-routed only" claim was false); listener sendId hoisted
  and logged on enqueue failure; several stale/inaccurate javadoc comments corrected. Full regression
  re-run, all green. Status remains review.
- 2026-09-14: Code review's 15 patch findings and 5 decisions were already fully applied with no
  outstanding follow-up; story marked done.

## Status

Done
