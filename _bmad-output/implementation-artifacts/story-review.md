# Senior-Dev Review — Story ses-1.4: Registration Email Durability

**Reviewed:** `_bmad-output/implementation-artifacts/ses-1-4-registration-email-durability.md`
**Date:** 2026-09-12
**Method:** every claim below was checked against the code on `master` before it was written. Claims the story got right are listed in §3 so they are not re-litigated during dev. Nothing here is speculative — each finding cites the file and line that makes it true.

**Verdict:** the story's *direction* is right and its research is unusually good (AC2 in particular catches a real regression the source doc missed). But three ACs contain assumptions that do not hold against the current code, and one of them means the story does not achieve its own stated goal. **Not ready for dev as written** — AC3 and AC4 need rework, AC6 needs a different test strategy.

---

## 1. Blocking findings

### B1 — AC3's delivery deadline is never consulted on the delivery path. The story's headline goal is not met.

The story's "so that" clause promises *"an expired-by-the-time-it-arrives OTP is never delivered."* AC3 as specified cannot deliver that.

`deadline` is read in exactly one place in `src/main`: `EmailRetryScheduler.java:97`. That loop only ever sees rows returned by `EnvelopeEntityRepository.fetchFailedEmails()` (`EnvelopeEntityRepository.java:17`), whose predicate is `retry = 'true' AND status = 'FAILED'`. Neither `MailManager.sendEmailSync` nor `MailService.sendEmailFromTemplate` looks at `envelope.deadline()` at all — confirmed by reading both files end to end and by grepping every `deadline` reference in `src/main`.

Consequence: `NotificationEmailOutboxHandler.handle` (`:70-71`) constructs the `Envelope` *with* the deadline and then hands it to `sendEmailSync`, which ignores it and sends. So:

- An OTP whose outbox row is not drained promptly — `outboxDrainPool` saturated, pod restart between commit and drain, the `app.outbox.sweep-ms:300000` five-minute safety-net sweep, an SES outage — is delivered **whenever it finally drains**, with no staleness check.
- `OutboxService` retries a failed row **forever** by design (`OutboxService.java:37-40`: "It is still retried forever… Crossing this threshold means the operation needs a human, not that it is abandoned"), with `OutboxRowProcessor.backoffFor` escalating 30s → 1m → 2m → … → 1h cap (`OutboxRowProcessor.java:144-155`). A registration OTP can therefore be delivered hours after its 10-minute TTL expired.
- The 5-minute deadline only ever fires for an envelope that has already been recorded `FAILED` **and** is picked up by `EmailRetryScheduler` before it succeeds. That is the narrow case, not the one the story is worried about.

**Fix:** AC3 needs a second half — an explicit deadline check on the delivery path. The natural place is the top of `NotificationEmailOutboxHandler.handle` (after deserialising, before `sendEmailSync`): if `p.deadline()` has passed, persist a `DEADLINE_EXPIRED` envelope (or log `[NOTIFICATION_EMAIL_DEADLINE_EXPIRED]`), return normally so the outbox row is released, and **do not send**. Guarding inside `sendEmailSync` instead also covers the `EmailRetryScheduler` and direct-publish paths but changes behaviour for every existing producer, so it is the larger blast radius — pick one deliberately and say which.

### B2 — AC4's `try { save } catch (DataIntegrityViolationException)` cannot fire where AC4 puts it.

AC4 instructs: *"wrap the `save(envelopeEntity)` call in `try { } catch (DataIntegrityViolationException) { }`"* (`MailManager.java:121`). That catch is unreachable for the duplicate-`sendId` case:

- `EnvelopeEntity` has `@Id private UUID id` with **no** `@GeneratedValue` (`EnvelopeEntity.java:27-28`); the id is assigned by hand in `EnvelopeMapper.toEntity` (`EnvelopeMapper.java:24`).
- `@Version private long version` is a **primitive** (`EnvelopeEntity.java:30-31`). Spring Data's `JpaMetamodelEntityInformation.isNew` skips the version-based check for primitive version attributes and falls back to the id-null check. The id is non-null, so the entity is treated as *not new* and `SimpleJpaRepository.save` calls `em.merge(...)`.
- Neither `save` nor `merge` flushes. The INSERT against the `sendId` unique constraint (`EnvelopeEntity.java:56-57`) is issued when `sendEmailSync`'s `REQUIRES_NEW` transaction commits — **after** the method body has returned. The catch never sees it.

There is a second, independent problem with the prescribed recovery. AC4 says: *"on catch, re-query `findBySendId` and fall through to the same update logic."* Even if you force the exception to surface early (with `saveAndFlush` — the pattern `RegistrationOtpResendSupport.resendPhoneOtp` already uses deliberately for exactly this reason, see its javadoc bullet on `uq_pot_one_active_per_user`), a constraint violation leaves the Hibernate persistence context in an undefined state and marks the transaction rollback-only. The re-query runs on a poisoned `EntityManager`, and any dirty-checked update it makes cannot commit. Catch-and-continue inside the same transaction is not a valid recovery here.

**Fix:** AC4 must either (a) use `saveAndFlush` **and** perform the recovery in a fresh transaction (a small `REQUIRES_NEW` collaborator), or (b) let the violation propagate out of `sendEmailSync` and be handled by the caller — for the outbox path that means the row is retried, which is already correct behaviour — and settle for the ERROR log. Option (b) is materially simpler and loses nothing AC4 actually promised. Whichever is chosen, the AC needs to stop describing a catch around a non-flushing `save`.

### B3 — AC4's `NON_REPAIRABLE_ERRORS` addition is dead code for the case it names.

`NON_REPAIRABLE_ERRORS` (`MailManager.java:42-43`) is consulted only by `isRetryable`, which is called from exactly two places: inside the retry loop on the transport exception (`MailManager.java:91`) and from `toEnvelopeEntity` on the exception caught out of `circuitBreaker.run(...)` (`MailManager.java:137`, fed by the catch at `:107`). A duplicate-`sendId` `DataIntegrityViolationException` arises at `:121`/commit — *after* `envelopeEntity` has already been built and classified. It never reaches `isRetryable`, so adding `DataIntegrityViolationException` to the list changes nothing about whether `EmailRetryScheduler` re-drives it.

It also breaks a deliberate abstraction. `MailManager.java:39-43` documents (ses-1.2 AC4) that this list is *transport-neutral* — "every `OutboundEmailSender` implementation already classifies its own failures into this taxonomy… `MailManager` no longer needs to know about any transport-specific exception type." A Spring DAO exception is not a transport classification, and no `OutboundEmailSender` can throw one.

**Fix:** drop this bullet from AC4. If the intent is "a duplicate `sendId` must never be retried six times," the real mechanism is B2's decision about where the violation is handled, not the classification list.

---

## 2. Significant findings (fix before merge, not necessarily before starting)

### S1 — AC6's IT model cannot demonstrate what AC6 asks for.

AC6 says to drive `RegistrationEmailDurabilityIT` *"the same way `BookingReminderEmailWiringIT` drives its own outbox-to-delivery assertion."* That class runs under the shared test profile, where `enable.test.mail=true` swaps the entire `MailManager` bean for `TestMailManager` (`src/test/.../config/TestConfig.java:87-91`, `@Primary @ConditionalOnProperty(name="enable.test.mail", havingValue="true")`), and it asserts against `TestMailManager` — it never touches `envelope_entity` at all. With `TestMailManager` in place there is no `EnvelopeEntity` row, no `FAILED`/`retry=true`, and nothing for `EmailRetryScheduler` to re-drive, so the exact assertions AC6 specifies are unreachable in that shape.

The IT therefore needs `enable.test.mail=false` — which forks a second Spring context (real cost on CI time) — and it must call `EmailRetryScheduler.retryFailedEmails()` directly, because scheduling is disabled under the test profile (`app.scheduling.enabled`, per `OutboxService.sweep`'s javadoc). AC6 should say this outright instead of pointing at a class whose mechanism is the opposite of what is needed.

### S2 — AC6's IT has two competing retry drivers and will be flaky as specified.

A `FAILED`/`retry=true` envelope is re-driven by **both**:
1. `EmailRetryScheduler` on a flat 60-second `fixedDelay` (`EmailRetryScheduler.java:86`), and
2. the outbox row itself — `NotificationEmailOutboxHandler.handle` throws on `FAILED` + retryable (`:74-78`), so `OutboxRowProcessor` backs the row off 30s → 1m → 2m → … and re-drives it too.

Both call `sendEmailSync`, and **both increment `attempts` on the same envelope row**. `MAX_RETRY_ATTEMPTS = 6` (`EmailRetryScheduler.java:61`) can be consumed in roughly two minutes by the pair — i.e. *before* a 5-minute OTP deadline is reached. Since `EmailRetryScheduler` checks the deadline first but only for rows still `FAILED`+`retry=true` (`:97` then `:103`), the AC6 assertion "…is marked `DEADLINE_EXPIRED`" may instead observe `ATTEMPTS_EXHAUSTED`, non-deterministically.

**Fix:** the IT must set the envelope's state explicitly (persist a row with a past deadline and a controlled `attempts`) and invoke the scheduler directly, rather than letting the two drivers race. Worth a line in Dev Notes either way — the dual-driver behaviour is pre-existing and surprising.

### S3 — AC1 routes live OTP codes and verification tokens into two log statements and an indefinitely-retained DB column.

Today the registration listeners render the OTP into HTML and never log it (`CoachRegistrationEmailListener.java:70-81` logs only `correlationId`), and `MailService` logs only `correlationId`/`messageId` (`:77`). Routing through `MailManager` changes that:

- `MailManager.java:64` — `logger.info("sendEmailFrom template called:  Envelope {}", envelope)`. `Envelope` is a record, so its generated `toString()` prints every component **including `data`**: `{otpCode=123456}`, `{verifyUrl=…&token=<uuid>}`. At **INFO**, on every send.
- `MailManager.java:110` — `logger.error("… {}", envelopeEntity, exception)`; `EnvelopeEntity.toString()` also prints `data` (`EnvelopeEntity.java:155`).
- `EnvelopeEntity.data` is a persisted `jsonb` column (`:41-43`). There is **no purge or retention job** for `envelope_entity` anywhere in the repo (checked). The OTP and the verification token live in the database indefinitely.
- There is no log-masking converter in this project (checked `src/main/java` and `src/main/resources`).

This is pre-existing for activation/password-reset tokens, which already flow through `MailManager` via `AccountManagementFacade`. It becomes newly true for **OTP secrets**, which is a different sensitivity class. The story should make this an explicit, recorded decision — mask/omit `data` for OTP templates, demote the INFO log, or accept it in writing.

### S4 — AC3's "a producer can never forget" rationale only holds for outbox producers.

AC3 justifies putting the deadline on the enum because a per-call-site override *"fails silently for the next one that forgets."* But `EmailTemplate.deliveryDeadline()` would only be read by `NotificationOutboxSupport.enqueueEmail`. Six producers construct `Envelope` directly and pass their own deadline, bypassing it entirely:

| Call site | Deadline |
|---|---|
| `AccountManagementFacade.java:197` | caller-supplied |
| `EmailRegistrationStrategy.java:142` | caller-supplied (`Instant.now()` for `ACTIVATION`) |
| `SendMailListener.java:51` | from `SendMailEvent` |
| `AlertNotificationListener.java:66` | 5 min |
| `VideoModerationEmailListener.java:143, :168` | 1 h / 1 d |
| `ReportGenerationService.java:337` | 48 h |

Most relevant: `TwoFactorLoginService.java:60` gives the login-2FA `SEND_OTP` flow its own **10-minute** deadline. `OtpDeadlineParityTest` as AC6 specifies it ("every other template returns the 24-hour default") would assert `SEND_OTP.deliveryDeadline() == 24h` — pinning a value the enum advertises but that flow never uses, sitting one file away from the real 10-minute value. That is the *opposite* of the "can never forget" property AC3 claims.

**Fix:** either scope `OtpDeadlineParityTest` to outbox-routed templates and say so in the test's javadoc, or make `deliveryDeadline()` the default that the direct producers fall back to (larger change, probably a separate story). Do not ship a parity test that pins a misleading value for `SEND_OTP`.

### S5 — `sendId` uniqueness dedupes the envelope row, not the send. AC4's test would pass while the email goes out twice.

The outbox is at-least-once by construction (`OutboxService` javadoc: "A row is **never dropped**"). On a re-drive, `NotificationEmailOutboxHandler.handle` calls `sendEmailSync` again with the same `sendId`; `mailService.sendEmailFromTemplate` runs and **a second real email is delivered** before `findBySendId` (`MailManager.java:112`) finds the existing row and merely updates it. Worse, if that second attempt fails, the row flips `SENT → FAILED, retry=true`, re-arming `EmailRetryScheduler` for a third send.

AC4's stated assertions — "does not throw, does not create a second row, does not silently drop the second send's outcome" — are all satisfied by this behaviour. The test as specified is blind to the thing a reader would assume it proves. For registration OTP this is benign (the same code, sent twice), but the story should say so explicitly rather than leaving "distinct `sendId`" reading like duplicate-suppression.

Related, and relevant to why B2/S5 matter in practice: the producer most likely to actually collide on `sendId` today is `VideoModerationEmailListener` (`:148`, `:173`), which uses `ShortCode.shortenInt(UUID.randomUUID().hashCode())` — a 32-bit value, with birthday-bound collisions at a few tens of thousands of sends. The registration listeners' `UUID.randomUUID().toString()` will not realistically collide.

---

## 3. Claims the story got right — verified, do not re-derive

These were checked because they are load-bearing, and they hold:

- **Template-name resolution works with no extra mapping.** `EmailContentRenderer.java:66` derives the Thymeleaf template from the enum via Guava `UPPER_UNDERSCORE → LOWER_CAMEL`, so `COACH_EMAIL_VERIFY → coachEmailVerify`, `PLAYER_OTP → playerOtp`, etc. All six files exist under `src/main/resources/mails/`.
- **AC2's gap is real and correctly scoped.** All six registration templates use `${recipient.firstname}`; the full set of templates that do is `{coach,parent,player}{EmailVerify,Otp}`, `activation`, `creationDup`, `passwordReset`, `profileChange`, `sendOtp` — and **none** of the booking / session-pack / video-moderation templates that route through the outbox today. Routing without AC2 would blank the greeting exactly as the story says.
- **AC5's "no `ComponentConfig` change needed" is correct.** `defaultCustomizer()` (`ComponentConfig.java:89-100`) calls `factory.configureDefault(id -> …)`, which applies the same config to every id while keeping independent breaker instances. `"emailService"` appears only at `MailManager.java:70` and in one test comment — nothing keys health checks, metrics or alerting off the literal name, so a second id introduces no orphaned dashboards.
- **AC2's payload change is backward-compatible with in-flight rows.** `NotificationEmailPayload` is JSON in a text column (no migration), and Jackson leaves a missing record component `null` by default (`FAIL_ON_MISSING_CREATOR_PROPERTIES` is off and not overridden in `CommonConfig`).
- **`Propagation.MANDATORY` will be satisfied.** `CoachRegistrationService` (`:52-54`), `ParentRegistrationService`, `PlayerRegistrationService` and `RegistrationOtpResendSupport` are all class-level `@Transactional`.
- **`enable.test.mail=true` really does replace the whole `MailManager` bean** (`TestConfig.java:87-91` vs `ComponentConfig.java:32-37`), so AC6's "the §7.1 targeted set needs no changes" is sound.
- **`verifyUrl` and `otp` are already `String`**, so AC1's "no reformatting needed" holds.
- **The blank-address failure terminates; it is not a poison row.** `MailService.java:73-75` wraps `OutboundEmailRequest`'s `IllegalArgumentException` into `EmailTransportPermanentException`, which `isRetryable` classifies non-retryable, so the envelope lands `FAILED`/`retry=false` and `NotificationEmailOutboxHandler:79-82` releases the outbox row. (See L2 below for what does change.)

---

## 4. Lower-severity findings

**L1 — Unstated user-visible behaviour change: an outbox failure can now fail a signup.**
`AFTER_COMMIT → BEFORE_COMMIT` plus `Propagation.MANDATORY` means a failed outbox INSERT marks the transaction rollback-only, and per `NotificationOutboxSupport`'s own "Failure semantics" javadoc this rolls the business transaction back **whether or not the listener catches it** (`UnexpectedRollbackException` at commit). So an email-infrastructure problem can now fail `POST /register` or `POST /resend-otp` with a 500, where today the user is created and only the email is lost. That is a defensible trade — it is the same one booking already makes — but it is a new failure mode for the registration API and the story never states it. Add it to Dev Notes and confirm `ApiAdvice` renders `UnexpectedRollbackException` sensibly.

**L2 — AC1 drops the blank-recipient short-circuit that every other outbox producer keeps.**
AC1 says to "mirror `BookingEmailListener`'s established shape exactly," but omits the part of that shape that guards the address: `BookingEmailListener.java:65-68` does an explicit blank check with `log.warn` and an early return *before* building anything. Today a blank registration address is rejected inside the listener's catch with a log carrying registration context; after AC1 it costs an outbox row, an `EnvelopeEntity` row and a delivery round trip, and surfaces as `[NOTIFICATION_EMAIL_UNDELIVERABLE]` from the notification module with no registration context. AC6 explicitly retires the two existing blank-address tests without specifying a replacement. Decide: keep the guard (recommended, it is one `if`) and keep a test for it, or state that the diagnostic moves.

**L3 — AC5's line-70 change moves a potential NPE outside the failure-recording block.**
`circuitBreakerFactory.create(envelope.emailTemplate().circuitBreakerName())` sits at `MailManager.java:70`, before the `try` at `:81`. Every other failure in `sendEmailSync` is recorded onto an envelope row; a throw at `:70` escapes uncaught. `emailTemplate` is non-null at all current call sites (verified), but three of them take it as a parameter (`AccountManagementFacade`, `EmailRegistrationStrategy`, `SendMailListener`). Either null-guard or move the `create(...)` inside the `try`.

**L4 — D-8's "one extra breaker to watch" understates the window semantics.**
`ComponentConfig.java:92-99` configures a **count**-based sliding window (`slidingWindowSize(5)`, `minimumNumberOfCalls(5)`) with no time-based decay. On the shared high-volume `emailService` breaker that window spans seconds; on a low-volume `registrationEmailService` breaker it can span hours, so three failures from a morning signup spike still sit in the window that afternoon. The isolation is still the right call, but record this — it is the practical cost of the split, and it is not "one extra breaker to watch."

**L5 — Two file references in AC6 do not exist.**
`MailManagerErrorClassificationTest` is named in AC6's last-but-one bullet; there is no such file (`MailManagerResilienceTest` and `MailManagerIT` do exist). Separately, `NotificationOutboxSupport`'s javadoc (`:79`) cites `EmailDataStringContractTest` as a build gate that "fails the build otherwise" — that test does not exist either. The story does not depend on it, but a dev following the javadoc will look for a guard that is not there; the AC1 string-typing claim rests on manual inspection only (which is fine here — both values are already `String`).

---

## 5. Recommended AC changes, in order

1. **AC3** — add a delivery-path deadline check (`NotificationEmailOutboxHandler.handle`, before `sendEmailSync`), or the story does not close its own "so that" clause. *(B1)*
2. **AC4** — delete the `NON_REPAIRABLE_ERRORS` bullet *(B3)*; replace the `try { save } catch` bullet with either `saveAndFlush` + a `REQUIRES_NEW` recovery, or let-it-propagate + ERROR log *(B2)*; and state explicitly that `sendId` dedupes the envelope row, not the send *(S5)*.
3. **AC6** — drop `BookingReminderEmailWiringIT` as the model and specify `enable.test.mail=false` + a direct `retryFailedEmails()` call with explicitly seeded envelope state *(S1, S2)*; scope `OtpDeadlineParityTest` to outbox-routed templates *(S4)*; restore a blank-address case *(L2)*.
4. **New AC or explicit Dev Note** — the OTP-in-logs / OTP-in-`jsonb` exposure *(S3)*, and the rollback-on-enqueue-failure change to the registration API's contract *(L1)*.
5. **AC1** — keep `BookingEmailListener`'s blank-address guard *(L2)*.
6. **AC5** — one-line null guard or move `create(...)` inside the `try` *(L3)*; amend the D-8 cost note *(L4)*.

## 6. What this review deliberately does not flag

- `ACTIVATION` envelopes are created with `deadline = Instant.now()` (`EmailRegistrationStrategy.java:79-80`), so a single failure marks them `DEADLINE_EXPIRED` immediately. Pre-existing, unrelated to this story, and correctly outside its stated scope.
- The `MailManager.sendEmailSync` missing `@Transactional(timeout)` (§6.17) — the story already scopes this out explicitly and is right to.
- `EmailRetryScheduler`'s `ORDER BY e.deadline LIMIT 10` will now put 5-minute OTP envelopes at the front of every retry batch. This is a *benefit* of AC3, not a defect; noted only so it is not mistaken for one during review.
