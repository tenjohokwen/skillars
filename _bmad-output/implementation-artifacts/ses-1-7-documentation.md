# Story Ses-1.7: Documentation

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer onboarding onto or debugging the notification/email stack,
I want the dev-docs pages for `notification` and `infrastructure` to describe the email architecture as it
actually exists today — the transport port, the three-transport switch, and registration/OTP mail's durable
outbox routing — instead of the pre-migration, SMTP-only/SES-bypasses-everything picture they still show,
so that reading them does not actively mislead me into the wrong mental model of how an email actually gets sent.

This is **Phase 7 of 7** in `requirements/ses-email-consolidation.md`. Per the source doc, §5 Phase 7's stated
scope is two lines: *"`docs/dev-docs/notification/index.html` and the deployment requirements docs. Update the
transport section and diagram; document the `app.email.transport` switch."* That undersells what is actually
stale — see the note below.

**Sequencing note, read before starting.** `ses-1-5-ses-cutover` (Phase 5, gated on D-1, the still-open
sending-domain decision) and `ses-1-6-delete-smtp` (Phase 6) are both still `backlog` and are **not** blocked
on documentation — this story was deliberately pulled forward, out of the source doc's stated phase order, to
close the growing dev-docs gap left by Phases 1-4 while Phase 5 waits on an external decision. Consequence for
scope: **document the currently-shipped reality only** — the three-transport model (`ses`/`smtp`/`log`) behind
the `OutboundEmailSender` port, exactly as Phases 1-4 left it. Do **not** write the docs as if SMTP is already
gone or as if `uat`/`dev` already run SES — that is Phase 5/6's future state, not today's. When Phase 5/6
eventually ship, whichever of those stories lands last should do one more documentation pass (a few sentences,
not a rewrite) — note that forward pointer in this story's Dev Notes but do not attempt it now.

**What's actually stale — verified by direct read, not by trusting the source doc's two-line scope:**

- `docs/dev-docs/notification/index.html` has not been touched by any of `ses-1.1`-`ses-1.4` (confirmed: `git
  log --follow` on the file shows no `ses-1-*` commit touching it). It still describes the **pre-Phase-1**
  architecture almost end to end: a SMTP-only `notification` module using `SenderProvider`/`JavaMailSenderImpl`
  directly (no port), `MailSenderProvider` and `email.providerConfigs` as if they still live in this module
  (both moved to `infrastructure.email.smtp` / `app.email.smtp.provider-configs` in `ses-1.2`), and a
  "Dependencies on other layers" section that flatly states *"This module does not use `infrastructure.ses`"*
  and that SES "does not route through this module at all" for registration mail — both now false after
  `ses-1.4` routed registration/OTP mail through this module's own `NotificationOutboxSupport`/`MailManager`,
  and false even before that for booking/pack mail, which has gone through `OutboundEmailSender` (dispatching
  to SES, SMTP, or a log/file transport depending on `app.email.transport`) since `ses-1.1`/`ses-1.2`, not raw
  SMTP. The "SES is suppressed by default" gotcha callout describes a boolean `app.ses.enabled` flag and a
  `NoOpSesEmailService` that no longer exist — replaced by the `app.email.transport` enum (`ses`/`smtp`/`log`)
  and `EmailTransportPropertyValidator`, which aborts startup on an unrecognised value rather than silently
  suppressing sends. The "Related Modules" section's Infrastructure entry — *"provides the SES email sender
  (used elsewhere, not by this module)"* — is the same false claim from the other direction.
- `docs/dev-docs/infrastructure/index.html`'s `infrastructure.ses` description is equally pre-Phase-1: it names
  `infrastructure.ses.SesEmailService`, `NoOpSesEmailService`, and `app.ses.enabled=true`/`SesConfig` gating a
  `SesV2Client` bean — none of which exist post-`ses-1.1`. The real shape today is `infrastructure.email`
  (the `OutboundEmailSender` port, `EmailTransport` enum, `LoggingEmailSender`, `EmailAddressParser`, the
  exception taxonomy), `infrastructure.ses` (`SesEmailSender`/`SesErrorClassifier`/`SesHealthIndicator`/
  `SesSendRateLimiter`, active when `app.email.transport=ses`), and `infrastructure.email.smtp`
  (`SmtpEmailSender`/`MailSenderProvider`/`SmtpHealthIndicator`, active when `transport=smtp`) — one bean per
  transport value, selected by `EmailTransportPropertyValidator`, not a boolean flag.
- **The "deployment requirements docs" half of Phase 7's stated scope is already done, incrementally, by each
  prior story as it shipped** — checked all six deployment docs that mention SMTP/SES
  (`local-deployment.md`, `deploy-guide.md`, `first-time-setup.md`, `secrets-reference.md`,
  `uat-deployment.md`, `runbook.md`) against current code. `secrets-reference.md` already documents
  `GMX_PASSWORD`/`GMAIL_PASSWORD` under `app.email.smtp.provider-configs` (ses-1.2), `APP_SES_FROM_ADDRESS`
  and the rest of the `app.ses.*` block (ses-1.1), the `ses:GetAccount` IAM requirement and the Phase-5
  ordering constraint (ses-1.3) — each entry cites its story number. `local-deployment.md` correctly frames
  the old `app.ses.enabled: true` crash loop as historical, past-tense, "now fixed in the code." Nothing in
  any of the six needs a Phase-4-driven update: none mention registration/OTP durability, the
  `registrationEmailService` circuit breaker, or OTP delivery deadlines, and none claim anything false about
  the current SES/SMTP config surface. **This story therefore does not touch any deployment doc** — AC3 below
  is a verify-only AC recording that check, not new doc content.

## Acceptance Criteria

**AC1 — `docs/dev-docs/notification/index.html` describes the port-based, three-transport architecture,
not the pre-Phase-1 SMTP-only one**

- Given the file's current content is the pre-migration architecture (see the staleness note above for every
  specific claim this AC corrects)
- Then update, in place, preserving the page's existing structure and tone (business-first framing, tables,
  Mermaid sequence diagrams, `callout`/`callout warn` divs — do not restructure the page, only correct it):
  - **Business Overview**: add one sentence noting that registration/OTP mail (coach/parent/player
    verification, OTP codes) is now durable through this module's own outbox/retry pipeline as of `ses-1.4` —
    it used to be a separate, fire-and-forget path
  - **Package layout table**: `contract` row — remove `EmailProperties`/`ProviderConfig` (moved to
    `infrastructure.email.smtp` in `ses-1.2`); mention `EmailTemplate.deliveryDeadline()` and
    `.circuitBreakerName()` (added `ses-1.4`) alongside the existing `subjectKey()` mention. `infrastructure`
    row — remove `MailSenderProvider` (moved out of this module); it can still name
    `EmailRetryScheduler`/`infrastructure.listener`
  - **Key services table**: `MailService` row — replace "hands off to a round-robin `JavaMailSenderImpl` from
    `SenderProvider`" with "renders via `EmailContentRenderer`, then dispatches through the
    `OutboundEmailSender` port (`infrastructure.email`) — SES, SMTP, or a log/file transport, selected by
    `app.email.transport`, is invisible to this class." Delete the `MailSenderProvider` row entirely (that
    class now lives in, and is documented by, `infrastructure.email.smtp` — cross-reference the
    `infrastructure` dev-doc instead of duplicating it here). Add the three registration listeners
    (`Coach`/`Parent`/`PlayerRegistrationEmailListener`) to the `infrastructure.listener.*` row's description —
    they moved from a direct port call to `NotificationOutboxSupport.enqueueEmail` in `ses-1.4` and are now
    exactly the same shape as `BookingEmailListener`/`SessionPackEmailListener`
  - **"Dependencies on other layers"**: rewrite the paragraph. It must state, correctly: this module's own
    send path (`MailService` → `OutboundEmailSender`) and the registration path
    (`Coach`/`Parent`/`PlayerRegistrationEmailListener` → `NotificationOutboxSupport` → the same
    `MailManager`/`OutboundEmailSender` pipeline) both go through the same port and the same transport switch
    as of `ses-1.4` — there is no longer a module that "bypasses" this one. Keep the accurate parts: this
    module still doesn't construct a `SesV2Client`/`JavaMailSenderImpl` itself (that's `infrastructure.ses`/
    `infrastructure.email.smtp`'s job), and the dependency list on `infrastructure.threadpool`/
    `infrastructure.feature`/`platform.config.service.ConfigService` is unchanged and still correct
  - **Sequence diagrams**: in "Booking confirmed → templated email sent," replace
    `Mail->>SMTP: send MimeMessage` with `Mail->>Port: send(OutboundEmailRequest)` (participant renamed from
    `SMTP` to `Port` or `OutboundEmailSender`) — do not claim a specific transport, since the diagram is
    transport-agnostic by design now. The "Failed send → durable retry" diagram's shape (`MailManager` →
    `envelope_entity` → `EmailRetryScheduler`) is unchanged and needs no edit
  - **Conventions & Gotchas**: rewrite the "SES is suppressed by default" `callout warn` entirely — it
    describes `app.ses.enabled`/`NoOpSesEmailService`, both gone. Replace with a callout describing
    `app.email.transport` (`ses`/`smtp`/`log`), `EmailTransportPropertyValidator`'s startup-abort-on-unknown-
    value behaviour, and that registration/OTP mail now uses the *same* transport as everything else in this
    module (no separate suppression path to trip over). Leave the `EnvelopeEntity` Flyway-DDL-gap callout
    as-is — it is still accurate (confirmed still open, `ses-1.4`'s own code review re-found and deferred the
    identical gap in more detail; optionally cross-reference `deferred-work.md`'s
    `ses-1-4-registration-email-durability` section, do not duplicate its content)
  - **Related Modules**: fix the Infrastructure entry — it currently reads "provides the SES email sender
    (used elsewhere, not by this module)"; correct to reflect that this module's own sends go through that
    same infrastructure via the port
- [Source: requirements/ses-email-consolidation.md#5 Phase 7; every specific claim verified against current
  code during story creation — see staleness note above for citations]

**AC2 — `docs/dev-docs/infrastructure/index.html`'s `infrastructure.ses` description matches the
post-`ses-1.1` package shape**

- Given the file currently names `infrastructure.ses.SesEmailService`, `NoOpSesEmailService`, and
  `app.ses.enabled`/`SesConfig` gating a `SesV2Client` bean — a class shape `ses-1.1` deleted
- Then update the `ses` card/description and the surrounding prose (the "modules call
  `infrastructure.ses.SesEmailService`" sentence and the `NoOpSesEmailService`/`SesConfig` paragraph) to
  describe the current shape: `infrastructure.email` (the `OutboundEmailSender` port + `EmailTransport` enum +
  `LoggingEmailSender`, the `transport=log` implementation, which replaced the no-op suppression path),
  `infrastructure.ses` (`SesEmailSender`, `SesErrorClassifier`, `SesHealthIndicator`, `SesSendRateLimiter` —
  active when `app.email.transport=ses`), and `infrastructure.email.smtp` (`SmtpEmailSender`,
  `MailSenderProvider`, `SmtpHealthIndicator` — active when `transport=smtp`). One bean per transport value is
  selected by `EmailTransportPropertyValidator`; there is no boolean enable/disable flag anywhere in this area
  any more
- And do not touch any other section of this page — the scan for this story found staleness only in the
  `ses`-related prose (lines ~49-76 and ~165-173 in the current file); the rest of the page's content
  (S3/Gemini/BunnyCDN/other infra) is outside this story's scope
- [Source: found during story creation, not named in the source doc's two-line Phase 7 scope — verified by
  direct read against `infrastructure/email/`, `infrastructure/ses/`, `infrastructure/email/smtp/`]

**AC3 — Deployment requirements docs: verify, do not assume, that none need a Phase-4-driven update**

- Given the source doc's Phase 7 line also names "the deployment requirements docs," and story creation found
  all Phase-1-3-relevant content in `secrets-reference.md` already current (each entry cites its `ses-1.*`
  story) and `local-deployment.md`'s historical crash-loop note correctly past-tensed
- Then confirm (re-verify at dev-story time, in case something changed since story creation) that none of
  `local-deployment.md`, `deploy-guide.md`, `first-time-setup.md`, `secrets-reference.md`,
  `uat-deployment.md`, `runbook.md` make any claim ses-1.4 falsifies (registration/OTP durability, the
  `registrationEmailService` circuit breaker, OTP delivery deadlines) — **no such claim exists today**, so no
  edit is expected. If dev-story finds one, fix it and note the deviation; if not, record the confirmation in
  Dev Notes (mirroring the "No change expected (verify, don't assume)" pattern `ses-1.4`'s own story used for
  `ComponentConfig`/`EmailRetryScheduler`) rather than silently skipping this AC
- [Source: found during story creation — no requirements-doc citation beyond the two-line Phase 7 scope named
  above]

**AC4 — Both edited HTML pages remain structurally valid**

- Given both target files are hand-written HTML (no build step, no templating), a broken tag or an
  unescaped `<`/`&` in prose silently corrupts the rendered page
- Then after editing, visually sanity-check both pages render (open in a browser, or at minimum confirm no
  unclosed tags by eye — there is no linter for this project's dev-docs HTML) and confirm the Mermaid diagram
  in `notification/index.html` still parses (its syntax is easy to break with a stray edit — see the Dev
  Notes gotcha below)
- [Source: found during story creation, general HTML-authoring diligence — no requirements-doc citation]

## Tasks / Subtasks

- [ ] **Task 1 — AC1: rewrite `docs/dev-docs/notification/index.html`'s stale sections**
  - [ ] 1.1 Business Overview: add the registration/OTP-durability sentence
  - [ ] 1.2 Package layout table: remove `EmailProperties`/`ProviderConfig`/`MailSenderProvider` from this
    module's rows; add `deliveryDeadline()`/`circuitBreakerName()` mention
  - [ ] 1.3 Key services table: rewrite `MailService` row; delete `MailSenderProvider` row; update
    `infrastructure.listener.*` row for the three registration listeners
  - [ ] 1.4 Rewrite "Dependencies on other layers" paragraph
  - [ ] 1.5 Update the "Booking confirmed" sequence diagram's `SMTP` participant to a transport-neutral
    `OutboundEmailSender`/port participant
  - [ ] 1.6 Rewrite the "SES is suppressed by default" gotcha callout for `app.email.transport`
  - [ ] 1.7 Fix the Related Modules Infrastructure entry
- [ ] **Task 2 — AC2: rewrite `docs/dev-docs/infrastructure/index.html`'s `ses`-related prose**
  - [ ] 2.1 Update the `ses` card description
  - [ ] 2.2 Update the "modules call `infrastructure.ses.SesEmailService`" sentence and the
    `NoOpSesEmailService`/`SesConfig` paragraph
- [ ] **Task 3 — AC3: verify deployment docs need no change**
  - [ ] 3.1 Re-check all six named deployment docs against current code; fix if something is found stale,
    otherwise record the confirmation in Dev Notes
- [ ] **Task 4 — AC4: structural sanity of both edited pages**
  - [ ] 4.1 Visually confirm both pages render; confirm the Mermaid diagram still parses
- [ ] **Task 5 — Story wrap-up**
  - [ ] 5.1 Update File List, Change Log, Dev Agent Record
  - [ ] 5.2 Note the Phase 5/6 forward-pointer (one more short doc pass needed once SES cutover/SMTP removal
    ship) in Dev Notes — do not act on it now

## Dev Notes

### Files this story touches

**Modify:**
- `docs/dev-docs/notification/index.html` — AC1
- `docs/dev-docs/infrastructure/index.html` — AC2

**No change expected (verify, don't assume):**
- `docs/deployment/local-deployment.md`, `deploy-guide.md`, `first-time-setup.md`, `secrets-reference.md`,
  `uat-deployment.md`, `runbook.md` — AC3

**No code changes.** This is a documentation-only story — no `src/main`/`src/test` files are touched, no
tests to run beyond confirming the repo still builds untouched (it will; nothing under `src/` changes).

### Why this story exists out of the source doc's stated phase order

`requirements/ses-email-consolidation.md` lists Phase 7 last, after Phase 5 (SES cutover, gated on the
still-open D-1 domain decision) and Phase 6 (SMTP deletion, which itself depends on Phase 5 landing). Phases
5/6 are genuinely blocked on an external decision outside this codebase's control. Phase 7's actual content —
dev-docs for the port/transport architecture Phases 1-4 already shipped — has no dependency on Phase 5/6 at
all; the only reason it was sequenced last in the source doc is narrative (it reads as a natural "wrap up the
whole migration" closer), not a technical one. Pulling it forward closes a real, growing gap (two dev-docs
pages actively wrong for four stories' worth of shipped changes) while D-1 remains unresolved, and costs
nothing when Phase 5/6 eventually land — see Task 5.2's forward-pointer.

### The one thing to get right about scope: don't document the future

Because Phase 5/6 haven't shipped, `uat`/`dev` still run `smtp`, not `ses`, and SMTP code still exists. Do not
write either dev-docs page as though the cutover already happened. Every fact in AC1/AC2 above is scoped to
**what Phases 1-4 actually shipped** (the port, the three-transport switch, registration/OTP durability) —
verify this distinction is preserved if the language drifts during editing (e.g. don't write "uat sends via
SES" anywhere; it doesn't, today).

### Mermaid gotcha (from `project-context.md` / prior dev-docs work)

This page's `<pre class="mermaid">` blocks are hand-written sequence diagrams with no build-time validation —
a stray unescaped character or a broken `participant`/`->>` line fails silently at render time (the diagram
box just doesn't render, no error surfaces anywhere else). Re-read the edited diagram block character-by-
character after editing, not just the prose around it.

### Previous story intelligence (ses-1.4)

- `ses-1.4`'s own code review found and deferred (`deferred-work.md`,
  "code review of ses-1-4-registration-email-durability") the exact same `envelope_entity` no-Flyway-DDL gap
  this dev-doc's existing callout already names — confirms that callout is still accurate and should be kept,
  not rewritten, beyond an optional cross-reference
- `ses-1.4` is the story that made "registration mail bypasses the notification module" false — the single
  biggest correction this story makes to `notification/index.html`
- Post-merge deferred-work.md prune convention (see the `## Last audit: 2026-09-14` entry) — narrow-scope
  checks only look at what the just-merged story actually touched. This story is documentation-only and adds
  no new deferred items, so no comparable prune is needed after this one ships

### Git intelligence (last 5 commits)

`d4527da0` (deferred-work.md prune after ses-1-4) → `0bf1fc96` (ses-1.4) → `4d318803` (merge #182, prune after
ses-1-3) → `5f302d26` (prune ses-1-1 item closed by ses-1-3) → `59a4641b` (merge #181, ses-1.3). Confirms no
other in-flight work touches `docs/dev-docs/` right now.

## Project Context Reference

See `_bmad-output/project-context.md` for platform-wide rules. Nothing in that file is specific to this story
beyond the Mermaid gotcha already folded into Dev Notes above — this is a documentation-only story with no
architecture, testing, or code-structure implications.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-09-14: Story created. Pulled forward from the source doc's stated phase order (Phase 5/6 remain
  blocked on the still-open D-1 domain decision; Phase 7's documentation scope has no such dependency).
  Story creation found the source doc's own two-line Phase 7 description undersells the actual gap:
  `docs/dev-docs/notification/index.html` has not been touched by any of `ses-1.1`-`ses-1.4` and still
  describes the pre-Phase-1 SMTP-only architecture almost end to end (verified by direct read against
  current code); `docs/dev-docs/infrastructure/index.html`'s `infrastructure.ses` prose is equally stale
  (not named in the source doc's Phase 7 line at all — found during story creation). Conversely, all six
  deployment docs that mention SMTP/SES were checked and found already current — each prior `ses-1.*` story
  updated `secrets-reference.md` inline as it shipped — so AC3 is verify-only, not new content.
