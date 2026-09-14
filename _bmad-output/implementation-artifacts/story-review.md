# Senior Dev Review — `ses-1-7-documentation.md`

**Reviewed:** 2026-09-14 · **Branch:** `story/ses-1-7-documentation` · **Verdict: changes required before dev-story**

Every finding below was verified by direct read of the current code, config and docs on this branch — no
finding is inferred from the story's own text or from another document's claims. Line numbers are as of
this branch. A "confirmed-correct" list is at the end so the dev does not re-litigate what the story got right.

The story's own stated goal is that reading these pages "does not actively mislead me." Judged against that
goal rather than against its AC list, it has three classes of problem:

1. **Three ACs instruct the dev to write statements that are themselves false** (F1, F3, F4, F5). These are
   the blockers — following the story as written *adds* new wrong claims to the docs.
2. **The single biggest staleness in `notification/index.html` is not the transport at all** — it is that the
   module moved to a transactional outbox (deferred-91/92) and the page still describes the pre-outbox event
   path (F1, F2, F11). The story scoped only the SES/SMTP delta and asserts the rest of the page is fine.
3. **Three files with live false claims are out of scope, one of them inside the source doc's own named
   scope** (F6, F7, F8), and two sections of an in-scope file are explicitly fenced off by a
   "do not touch anything else" instruction (F9, F10).

---

## Blockers — following the AC as written writes a NEW false claim

### F1. AC1 assumes the "Booking confirmed" diagram needs one participant rename. The whole flow is wrong.

AC1 says: *"in 'Booking confirmed → templated email sent,' replace `Mail->>SMTP: send MimeMessage` with
`Mail->>Port: send(OutboundEmailRequest)`"* — and nothing else in that diagram.

`BookingEmailListener` does not publish an `Envelope` Spring event any more:

- `platform/notification/infrastructure/listener/BookingEmailListener.java:49` injects
  `NotificationOutboxSupport`; every handler is `@TransactionalEventListener(phase = BEFORE_COMMIT)`
  (`:60`, `:86`, …). Same for `SessionPackEmailListener.java:35,37`.
- The real chain is: domain event → listener (BEFORE_COMMIT, *inside* the business transaction) →
  `NotificationOutboxSupport.enqueueEmail` (`Propagation.MANDATORY`) → outbox row committed atomically with
  the business row → `OutboxService`'s poller (`platform/outbox/service/OutboxService.java:119`) →
  `NotificationEmailOutboxHandler` → `MailManager.sendEmailSync` → `MailService` → `OutboundEmailSender`.

So diagram lines 189-197 of `notification/index.html` are false independent of transport:
`Listener->>Bus: publish Envelope(BOOKING_CONFIRMED, ...)` and
`Bus->>Manager: sendEmailFromTemplate(envelope) [async, sendMailPool]` no longer happen for booking mail.
The prose under it (`:205`, "picks it up asynchronously on the `sendMailPool` executor … sends via SMTP") is
false for the same reason, and AC1 does not mention that paragraph at all.

**Complication the story must not flatten:** there are now **three** producer shapes, not one.

| Producer | Mechanism | Phase |
|---|---|---|
| `BookingEmailListener`, `SessionPackEmailListener` | `NotificationOutboxSupport` → outbox | `@TransactionalEventListener(BEFORE_COMMIT)` |
| `Coach`/`Parent`/`PlayerRegistrationEmailListener` (in **security**) | `NotificationOutboxSupport` → outbox | `@TransactionalEventListener(BEFORE_COMMIT)` |
| `AccountChangeEmailListener:44`, `VideoModerationEmailListener:59,152` | still `publisher.publishEvent(envelope)` | plain `@EventListener` (deliberate — see that file's :31-34 comment) |

A rewrite that says "everything goes through the outbox now" would be as wrong as what is there today.

**Action:** widen AC1's diagram bullet from a participant rename to a flow correction, and either add a
second diagram or fork the existing one to show the outbox path and the surviving direct-`Envelope` path.

### F2. AC1 asserts the "Failed send → durable retry" diagram "is unchanged and needs no edit." It is not.

That diagram contains `participant SMTP as JavaMailSenderImpl` (`:248`) and `Manager->>SMTP: send MimeMessage`
(`:252`) — the exact staleness AC1 fixes in the *other* diagram. `MailManager` reaches the transport only via
`MailService` → `OutboundEmailSender` (`MailService.java:7,30,76`); it never touches `JavaMailSenderImpl`.

Same wording survives in three more places AC1 does not name:

- `:112` `EmailRetryScheduler` row — "SMTP I/O is deliberately kept off the DB connection"
- `:267` — "When an SMTP send throws a retryable exception…"
- `:271` — "keeping SMTP network I/O off the database connection pool"

Executing AC1 literally leaves the page self-contradictory: one diagram transport-neutral, the next one
naming `JavaMailSenderImpl`. The *scheduler's* shape is indeed unchanged — the story is right about that —
but the participant and the surrounding prose are not.

### F3. AC1 instructs putting the registration listeners in a package they are not in.

AC1: *"Add the three registration listeners (`Coach`/`Parent`/`PlayerRegistrationEmailListener`) to the
`infrastructure.listener.*` row's description."*

That row (`:117`) documents **this module's** `platform.notification.infrastructure.listener` package.
The three registration listeners live in `platform/security/infrastructure/listener/` — verified; the
notification listener package contains exactly four files (`AccountChange`, `Booking`, `SessionPack`,
`VideoModeration`). Doing what AC1 says replaces one false claim with another: that these classes moved
modules. They did not — only what they *call* changed (`CoachRegistrationEmailListener.java:1-5,51`).

Two knock-ons AC1 misses: the row opens with "Four listener classes", which the edit would contradict; and
`ses-1.4` changed the *existing* four-listener description too, since two of the four no longer publish
`Envelope`s (F1).

**Action:** document the registration listeners in "Dependencies on other layers" / the Key Flows section as
*security-module* listeners that call into this module's `NotificationOutboxSupport` — not as rows in this
module's own package table. AC1's claim that they are "exactly the same shape as
`BookingEmailListener`/`SessionPackEmailListener`" is **correct** and worth keeping.

### F4. "One bean per transport value is selected by `EmailTransportPropertyValidator`" is false.

This sentence appears in AC2 and again in the staleness note, and AC2 tells the dev to put it in the doc.

`EmailTransportPropertyValidator` is an `EnvironmentPostProcessor`. Its entire body reads the property and
throws `IllegalStateException` on a value outside `{ses, smtp, log}`. It selects no bean and has no bean
knowledge at all. Selection is `@ConditionalOnProperty(name = "app.email.transport", havingValue = …)` on
each sender/config — e.g. `SesConfig.java:34,46`.

Two more details the doc wording must not lose, both load-bearing:

- **An absent value is allowed**, by design — it falls through to `application.yaml`'s base default `log`
  (validator javadoc: aborting on unset "would break every Spring test context that loads no profile").
  AC1's callout instruction ("aborts startup on an unrecognised value") is fine, but a dev compressing it to
  "aborts unless set" would be wrong.
- Matching is **case-insensitive**, mirroring `@ConditionalOnProperty` semantics, with no whitespace tolerance.

**Action:** "each transport's sender bean is `@ConditionalOnProperty` on `app.email.transport`;
`EmailTransportPropertyValidator` is a separate startup guard that rejects an unrecognised value (an absent
value is legal and falls through to the `log` default)."

### F5. AC2's "Given" says `SesConfig` was deleted. It still exists.

AC2: *"…`app.ses.enabled`/`SesConfig` gating a `SesV2Client` bean — a class shape `ses-1.1` deleted."*

`infrastructure/ses/SesConfig.java` is present and still creates the `SesV2Client` bean. `ses-1.1` changed
its **gate** (`app.ses.enabled=true` → `@ConditionalOnProperty("app.email.transport", havingValue="ses")`,
`:34,46`) and its javadoc explicitly records "`app.ses.enabled` no longer exists". What `ses-1.1` deleted was
`SesEmailService`/`SesEmailServiceImpl`/`NoOpSesEmailService`/`DevSesEmailService` — not `SesConfig`.

As written, AC2 invites the dev to drop `SesConfig` from the doc or describe it as gone. It also omits
`SesPropertiesValidator`, which is the class most likely to stop a prod boot (blank `from-address`,
unresolvable credential chain) and is already cited in `secrets-reference.md:158`.

---

## Missed files — live false claims outside the story's scope

### F6. AC3's core premise is disproven by `secrets-reference.md` itself.

The story states, twice, that all six deployment docs are current and that "none claim anything false about
the current SES/SMTP config surface," making AC3 verify-only.

`docs/deployment/secrets-reference.md:188-193` (**"AWS SES (legacy)"**), present tense, operator-facing:

> `application-prod.yaml` sets `app.ses.enabled: true`, so a production boot constructs a real `SesV2Client`
> … If AWS SES is not needed, set `app.ses.enabled: false` in `application.yaml`. `uat` and `dev` set it to
> `false` and use `NoOpSesEmailService`, which logs the subject and drops the message.

Every clause is false: the property does not exist; `application-prod.yaml:8` sets `app.email.transport: ses`;
`application-uat.yaml:9` and `application-dev.yaml:16` set `smtp`; `NoOpSesEmailService` is deleted. It also
contradicts the *same file* thirty lines above (`:157-158`, "Production runs `app.email.transport: ses`").
An operator following it would edit a property nothing reads and conclude mail is suppressed when it is not.

Why AC3 misses it: AC3's test is narrowed to "any claim **ses-1.4** falsifies (registration/OTP durability,
the `registrationEmailService` circuit breaker, OTP delivery deadlines)". This block is falsified by
**ses-1.1**, so the AC as written passes over it. The narrowing is the defect — the story is the migration's
documentation closer, not a ses-1.4 delta pass.

**Action:** broaden AC3 from verify-only to "delete the `AWS SES (legacy)` block; re-verify the rest," and
restate its test as *"any claim falsified by ses-1.1 … ses-1.4"*.

### F7. `docs/dev-docs/index.html` — the dev-docs landing page — carries the same dead gotcha. Not in scope.

`:163`:

> **Email is silently suppressed in dev.** When `app.ses.enabled=false` (the local default),
> `NoOpSesEmailService` logs the subject line only and sends nothing — a common source of "I registered but
> got no email" confusion locally.

Dead property, deleted class, and the advice inverts current behaviour: `application-dev.yaml:11-16` records
that "dev now runs the SMTP transport — including registration/OTP mail, for the first time". This is the
first page the story's own stated persona ("an engineer onboarding onto the notification/email stack") opens.
One `callout` to fix. Add it to AC1/AC2's file list.

### F8. `requirements/deployment/local/local-manual-testing.md` — inside the source doc's named scope, missed.

The story maps "the deployment requirements docs" to `docs/deployment/*.md`. The source doc's §4.5 impact
table (`requirements/ses-email-consolidation.md:502`) names **`requirements/deployment/*`, `deploy/` docs** —
a different tree, never checked.

`requirements/deployment/local/local-manual-testing.md:230`, under "Creating the accounts", present tense:

> **Emails never arrive.** `SesEmailServiceImpl` is annotated `@Profile("!dev")`, so under the `dev` profile
> the `NoOpSesEmailService` bean wins and logs only the subject: `NoOp SES: email suppressed — subject=…`
> The verification token is still written to the database, so pull it from there.

False since `ses-1.2`. This is step-by-step manual-testing instruction, so it misroutes a real workflow.

Checked and **not** stale in the same file: §3 "Fixed: dev no longer builds an AWS SES client" (`:146-165`)
is correctly past-tense incident history — leave it, or add one dated line noting the property is gone.
`.env.example` was checked too and is fully current (ses-1.1/ses-1.2 updated it) — no action.

---

## In-scope file, fenced off by an over-tight instruction

### F9. AC2's "do not touch any other section of this page" strands two stale sections.

AC2 restricts edits to lines ~49-76 and ~165-173 of `infrastructure/index.html`. Two live false claims sit
outside that range:

- **`:292-297`, Conventions & Gotchas:** *"**Emails are silently suppressed in most local/dev setups.**
  `NoOpSesEmailService` activates whenever `app.ses.enabled` is `false` *or unset* … check for that log line
  before assuming a bug."* Dead class, dead property, inverted advice — the highest-traffic section of the page.
- **`:342`, Related Modules:** *"Notification — the primary consumer of `ses` for outbound email."* Notification
  consumes the `infrastructure.email` **port**; `ses` is the active transport only on prod.

Obeying AC2 literally produces a page whose `ses` card is correct and whose gotcha callout contradicts it.

### F10. AC1 fixes one of two identical false claims in Related Modules.

Task 1.7 says "Fix the Related Modules **Infrastructure** entry" (`notification/index.html:334-335`). The
**Security** entry, `:329-331`, carries the same claim from the other side: *"its own registration listeners
call `infrastructure.ses` directly (bypassing this module)"* — made false by the very ses-1.4 change this
story exists to document. Both entries, not one.

---

## Gaps that make the corrected page incomplete

### F11. The outbox is invisible on the page, and no AC adds it.

`grep -i outbox docs/dev-docs/notification/index.html` → no match, and there is no `docs/dev-docs/outbox/`
page anywhere. Concretely:

- Package-layout `service` row (`:91`) lists "the core send pipeline (`MailManager` → `MailService`)" and
  omits `NotificationOutboxSupport`, `NotificationEmailOutboxHandler`, `EmailContentRenderer`, `MailMetrics`
  — all present in `platform/notification/service/`.
- AC1's replacement text for the `MailService` row references `EmailContentRenderer`, which after the edit
  appears nowhere else on the page.
- Business-overview bullet `:65-67` — "Every outbound email is modeled as an `Envelope` … that any module can
  publish as a Spring application event" — is now true for only two of the six producing listeners (F1).

**Action:** add `NotificationOutboxSupport` / `NotificationEmailOutboxHandler` / `EmailContentRenderer` to the
package-layout and key-services tables, and qualify the `Envelope`-as-event bullet.

### F12. The `MailManager` row still describes a single circuit breaker.

`:109` — "runs the send through a Resilience4j circuit breaker + Spring `RetryTemplate`". `MailManager.java:115-117`
now resolves the breaker **per template** via `EmailTemplate.circuitBreakerName()`. That is exactly the
`registrationEmailService` breaker AC3 names in passing. AC1 adds `circuitBreakerName()` to the `contract`
row but never corrects the row that describes the behaviour it drives.

### F13. AC4 is not executable by the agent that has to satisfy it.

"open in a browser, or at minimum confirm no unclosed tags by eye" — no browser, and "by eye" is not a check.
A deterministic substitute costs one command and needs no new tooling:

```bash
python3 - <<'PY'
from html.parser import HTMLParser
import sys
VOID={'area','base','br','col','embed','hr','img','input','link','meta','source','track','wbr'}
class P(HTMLParser):
    def __init__(s): super().__init__(convert_charrefs=False); s.st=[]; s.bad=[]
    def handle_starttag(s,t,a):
        if t not in VOID: s.st.append((t,s.getpos()))
    def handle_endtag(s,t):
        if not s.st or s.st[-1][0]!=t: s.bad.append((t,s.getpos()))
        else: s.st.pop()
for f in ('docs/dev-docs/notification/index.html','docs/dev-docs/infrastructure/index.html'):
    p=P(); p.feed(open(f,encoding='utf-8').read())
    print(f,'OK' if not p.st and not p.bad else f'MISMATCH unclosed={p.st[:5]} stray={p.bad[:5]}')
PY
grep -n 'sequenceDiagram\|participant \|->>' docs/dev-docs/notification/index.html
```

Pair that with one literal reading of the mermaid blocks — the Mermaid gotcha in Dev Notes is a real risk and
correctly flagged; it just needs a check the dev can actually run.

### F14. `LoggingEmailSender`'s package is wrong in AC2.

AC2 places it in `infrastructure.email`; it is `infrastructure/email/log/LoggingEmailSender.java`. If the
doc names exact classes, name the exact package. Also absent from AC2's enumerations, all present on disk:
`SesPropertiesValidator`, `SesProperties`, `SmtpErrorClassifier`, `SmtpConfig`, `SmtpProperties`,
`SmtpHealthProperties`. Not all need naming — decide deliberately rather than by omission.

### F15. "Don't document the future" is right, but its example under-states prod.

Dev Notes says only "`uat`/`dev` still run `smtp`, not `ses`". Prod has run `app.email.transport: ses` since
ses-1.1 (`application-prod.yaml:8`). A dev over-applying the guidance could write "SES isn't in use yet",
which is false. The unambiguous form is a four-row table, which both pages would benefit from anyway:
base default `log` · dev `smtp` · uat `smtp` · prod `ses`. Phase 5 then flips two cells, which is exactly the
"few sentences, not a rewrite" follow-up Task 5.2 promises.

---

## Verified correct — do not re-litigate

Checked against current code and confirmed as the story states:

- `EmailProperties`/`ProviderConfig` are gone from `platform/notification/contract/` (it holds only
  `AlertFiredEvent`, `EmailDeliveryStatus`, `EmailTemplate`, `Envelope`, `Recipient`) — AC1's removal is right.
- `EmailTemplate.deliveryDeadline()` (`:97`) and `.circuitBreakerName()` (`:109`) exist.
- `MailSenderProvider` now lives in `infrastructure/email/smtp/`; notification's `infrastructure/` holds only
  `EmailRetryScheduler` and `listener/` — AC1's `infrastructure`-row edit is right.
- `MailService` does render via `EmailContentRenderer` then dispatch through `OutboundEmailSender`
  (`MailService.java:30,34,76`) — AC1's `MailService` row text is accurate.
- **No Flyway migration creates `envelope_entity`** — the existing callout is still true; keeping it is correct.
- `local-deployment.md:228` frames the `app.ses.enabled: true` crash loop as history, past-tense — correctly
  excluded from edits.
- `MAX_RETRY_ATTEMPTS = 6` (`EmailRetryScheduler.java:61`) — that business rule still holds.
- `git log --follow` on both dev-docs pages: last touched in `bf9c8280`; no `ses-1-*` commit — the staleness
  premise is sound.
- The four `docs/deployment/` files outside the story's six (`uat-hostwinds-deployment.md`, `monitoring.md`,
  `rollback.md`, `backup-restore.md`) contain no email/SES/SMTP configuration — the six-file list is complete
  **for that directory**. The gap is the other directory (F8).
- Registration listeners really are the same shape as `BookingEmailListener`/`SessionPackEmailListener`
  (`NotificationOutboxSupport` + `BEFORE_COMMIT`) — only their claimed *location* is wrong (F3).

Minor, not worth an AC: the `contract` row says "~45 templates"; `EmailTemplate` currently declares 40. Only
adjust if that row is being edited anyway.

---

## Recommended changes before `dev-story`

1. **AC1** — replace the diagram bullet with a flow correction covering the outbox path *and* the surviving
   direct-`Envelope` path; extend to the second diagram and the three prose spots naming SMTP/`JavaMailSenderImpl`
   (F1, F2); move the registration listeners out of the package-table row (F3); add both Related Modules
   entries (F10); add the outbox/renderer services and correct the `MailManager` breaker row (F11, F12).
2. **AC2** — drop the "`SesConfig` deleted" premise (F5); fix the bean-selection sentence (F4); fix
   `LoggingEmailSender`'s package (F14); lift the "do not touch any other section" fence to cover the gotcha
   callout at `:292` and Related Modules at `:342` (F9).
3. **AC3** — promote from verify-only to an edit: delete `secrets-reference.md`'s "AWS SES (legacy)" block,
   and restate the test as "falsified by ses-1.1 … ses-1.4", not ses-1.4 alone (F6).
4. **New AC** — `docs/dev-docs/index.html:163` and
   `requirements/deployment/local/local-manual-testing.md:230` (F7, F8).
5. **AC4** — swap "by eye" for the well-formedness command above (F13).
6. **Dev Notes** — add the four-profile transport table and state that prod is already on `ses` (F15).
