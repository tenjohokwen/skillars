# Story Ses-1.7: Documentation

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

**Pre-dev review applied (2026-09-14, `story-review.md`):** an independent senior-dev review of this story
file, done before any implementation, verified every claim against current code and found 15 issues — 5 of
them ACs that, as originally written, instructed the dev to write **new false statements** into the docs
(the diagram fix that ignored the outbox entirely; a bean-selection sentence that misattributes what
`EmailTransportPropertyValidator` does; a "given" clause claiming `SesConfig` was deleted when it still
exists; registration listeners placed in the wrong package's table row; a scope fence that stranded two
stale sections on the infrastructure page). It also found the single biggest staleness in
`notification/index.html` isn't the SES/SMTP transport at all — it's that the module moved most producers
onto a transactional outbox (`skillars-deferred-91`/`-92`, which predates every `ses-1.*` story) and the page
still describes the pre-outbox direct-`Envelope`-publish flow. And it found two files with live false claims
that are outside this story's original scope, one of them the platform's own dev-docs landing page. All 15
were re-verified against the code independently (not taken on faith) before folding them into the ACs below —
nothing here is speculative, and every AC is self-contained.

## Story

As an engineer onboarding onto or debugging the notification/email stack,
I want the dev-docs pages for `notification` and `infrastructure` — plus the two other files a review found
carrying the identical stale claim — to describe the email architecture as it actually exists today: the
transactional outbox most producers now use, the surviving direct-`Envelope` path for the rest, the transport
port, and the three-transport switch, instead of the pre-migration, direct-SMTP-call/SES-bypasses-everything
picture they still show,
so that reading them does not actively mislead me into the wrong mental model of how an email actually gets
sent, in dev, in prod, or during local manual testing.

This is **Phase 7 of 7** in `requirements/ses-email-consolidation.md`. Per the source doc, §5 Phase 7's stated
scope is two lines: *"`docs/dev-docs/notification/index.html` and the deployment requirements docs. Update the
transport section and diagram; document the `app.email.transport` switch."* That undersells what is actually
stale, on two independent axes — see below.

**Sequencing note, read before starting.** `ses-1-5-ses-cutover` (Phase 5, gated on D-1, the still-open
sending-domain decision) and `ses-1-6-delete-smtp` (Phase 6) are both still `backlog` and are **not** blocked
on documentation — this story was deliberately pulled forward, out of the source doc's stated phase order, to
close the growing dev-docs gap left by Phases 1-4 while Phase 5 waits on an external decision. Consequence for
scope: **document the currently-shipped reality only** — the three-transport model (`ses`/`smtp`/`log`) behind
the `OutboundEmailSender` port, exactly as Phases 1-4 left it, on top of the outbox architecture that predates
all of them. Do **not** write the docs as if SMTP is already gone or as if `uat`/`dev` already run SES — that
is Phase 5/6's future state, not today's. **`prod` is the one exception: it has run `app.email.transport: ses`
since `ses-1.1`, independent of Phase 5** (Phase 5 only flips `uat`; see the transport table in Dev Notes) —
do not undersell that either. When Phase 5/6 eventually ship, whichever of those stories lands last should do
one more documentation pass (a few sentences, not a rewrite) — note that forward pointer in this story's Dev
Notes but do not attempt it now.

**What's actually stale — verified by direct read against current code, not by trusting the source doc's
two-line scope or this story's own first draft (both undersold it):**

- **The bigger gap: `notification/index.html` describes a producer architecture that stopped being universally
  true before any `ses-1.*` story existed.** `skillars-deferred-91`/`-92` moved most producers onto a
  transactional outbox: `BookingEmailListener` and `SessionPackEmailListener` (`platform.notification`) are
  `@TransactionalEventListener(BEFORE_COMMIT)` and call `NotificationOutboxSupport.enqueueEmail` directly —
  they do **not** publish an `Envelope` Spring event any more. `ses-1.4` added the three registration
  listeners (`Coach`/`Parent`/`PlayerRegistrationEmailListener`, in **`platform.security`**, not this module)
  onto the exact same outbox call. But two listeners still use the **older, surviving** direct-publish shape
  deliberately: `AccountChangeEmailListener` (`@EventListener`) and `VideoModerationEmailListener`
  (`@EventListener`, by design — its own code comment explains why `@TransactionalEventListener` would drop
  admin alerts fired outside a transaction) both still `publisher.publishEvent(Envelope)`, picked up by
  `MailManager`'s own `@TransactionalEventListener(AFTER_COMMIT) sendEmailFromTemplate`. **There are two
  producer shapes in flight, not one, and not zero** — a doc that says "everything goes through the outbox
  now" would be exactly as wrong as the current page's "everything publishes an `Envelope` event." The current
  page's "Booking confirmed" sequence diagram, its "Failed send → durable retry" diagram's `JavaMailSenderImpl`
  participant, three more prose spots naming SMTP explicitly, and its package/service tables (missing
  `NotificationOutboxSupport`/`NotificationEmailOutboxHandler`/`EmailContentRenderer`/`MailMetrics` entirely)
  are all downstream of this same gap. AC1 below addresses all of it, not just the transport delta.
- **The transport delta itself, on both dev-docs pages:** `docs/dev-docs/infrastructure/index.html`'s
  `infrastructure.ses` description is equally stale (not even named in the source doc's Phase 7 line) — it
  still names `infrastructure.ses.SesEmailService`, `NoOpSesEmailService`, and a boolean `app.ses.enabled`
  flag, none of which exist post-`ses-1.1`. **Correction to this story's own first draft:** `SesConfig` was
  **not** deleted — it still exists and still builds the `SesV2Client` bean; only its *gate* changed
  (`app.ses.enabled=true` → `@ConditionalOnProperty("app.email.transport", havingValue="ses")`). What
  `ses-1.1` actually deleted was `SesEmailService`/`SesEmailServiceImpl`/`NoOpSesEmailService`/
  `DevSesEmailService`. Get this distinction right in AC2 below — a dev who reads "the class shape was
  deleted" and removes `SesConfig` from the doc would introduce a new false claim while fixing an old one.
- **The exact same dead claim (`app.ses.enabled`/`NoOpSesEmailService`) recurs in two more places outside
  either dev-docs page named above** — found by the pre-dev review, not the original story-creation pass:
  `docs/dev-docs/index.html:163` (the platform's own dev-docs **landing page**, in its platform-wide
  Conventions & Gotchas — the very first page this story's own stated persona would open) and
  `requirements/deployment/local/local-manual-testing.md:230` (a step-by-step manual-testing guide's
  "Creating the accounts" section, present-tense, telling the reader emails never arrive locally — false since
  `ses-1.2`, which put dev on real SMTP delivery). Both are new ACs below (AC4, AC5) — they were missed by the
  original scoping because neither file is inside `docs/dev-docs/{notification,infrastructure}/` or
  `docs/deployment/*.md`, the two trees this story originally checked.
- **One of the six `docs/deployment/*.md` files this story originally called "already current" has a live,
  self-contradicting false claim.** `secrets-reference.md:188-193`, headed **"AWS SES (legacy)"**, present
  tense: *"`application-prod.yaml` sets `app.ses.enabled: true`... `uat` and `dev` set it to `false` and use
  `NoOpSesEmailService`."* Every clause is false (verified: no `app.ses.enabled` property exists anywhere in
  `application*.yaml`; `application-prod.yaml` sets `app.email.transport: ses`; uat/dev set `smtp`;
  `NoOpSesEmailService` is deleted) — and it directly contradicts the **same file**, thirty lines above
  (`:157-158`, "Production runs `app.email.transport: ses`"). **Correction to this story's own first draft:**
  AC3 was originally scoped as verify-only, narrowed to "any claim `ses-1.4` falsifies" — this block is
  falsified by `ses-1.1`, which the narrowing missed. AC3 below is now an edit, not a verify, and its test is
  restated across all of `ses-1.1`-`ses-1.4`. (`.env.example` and `local-deployment.md`'s historical
  crash-loop framing were re-checked and remain genuinely current — no action there.)

## Acceptance Criteria

**AC1 — `docs/dev-docs/notification/index.html` describes the outbox-plus-direct-publish producer
architecture and the port-based, three-transport transport layer, not the pre-outbox, pre-port picture**

- Given the file's current content predates both the outbox migration (`skillars-deferred-91`/`-92`) and the
  transport-port migration (`ses-1.1`-`ses-1.4`) — see the staleness note above for every specific claim this
  AC corrects
- Then update, in place, preserving the page's existing structure and tone (business-first framing, tables,
  Mermaid sequence diagrams, `callout`/`callout warn` divs — do not restructure the page, only correct it):
  - **Business Overview**: add one sentence noting that registration/OTP mail (coach/parent/player
    verification, OTP codes) is now durable through this module's own outbox/retry pipeline as of `ses-1.4` —
    it used to be a separate, fire-and-forget path. Qualify the existing "Every outbound email is modeled as
    an `Envelope` ... that any module can publish as a Spring application event" bullet — that is now true for
    two of six producing listeners only (`AccountChangeEmailListener`, `VideoModerationEmailListener`); the
    rest enqueue directly onto the outbox and never construct or publish an `Envelope` themselves
  - **Package layout table**: `contract` row — remove `EmailProperties`/`ProviderConfig` (moved to
    `infrastructure.email.smtp` in `ses-1.2`); mention `EmailTemplate.deliveryDeadline()` and
    `.circuitBreakerName()` (added `ses-1.4`) alongside the existing `subjectKey()` mention; correct
    "~45 templates" to the current count (40, verified — recount at edit time in case it drifted again).
    `infrastructure` row — remove `MailSenderProvider` (moved out of this module); it can still name
    `EmailRetryScheduler`/`infrastructure.listener`. `service` row — add
    `NotificationOutboxSupport`/`NotificationEmailOutboxHandler` (the outbox producer/consumer pair) and
    `EmailContentRenderer`/`MailMetrics` (both currently absent despite living in this exact package)
  - **Key entities & DB tables / Key services tables**: `MailManager` row — it currently says "runs the send
    through a Resilience4j circuit breaker" (singular); since `ses-1.4` AC5 the breaker is resolved **per
    template** via `EmailTemplate.circuitBreakerName()` (registration/OTP templates get their own
    `registrationEmailService` breaker, everything else keeps `emailService`) — correct this row, not just the
    `contract` row's accessor mention. `MailService` row — replace "hands off to a round-robin
    `JavaMailSenderImpl` from `SenderProvider`" with "renders via `EmailContentRenderer`, then dispatches
    through the `OutboundEmailSender` port (`infrastructure.email`) — SES, SMTP, or a log/file transport,
    selected by `app.email.transport`, is invisible to this class." Delete the `MailSenderProvider` row
    entirely (that class now lives in, and is documented by, `infrastructure/email/smtp/` — cross-reference
    the `infrastructure` dev-doc instead of duplicating it here). Add a row (or extend the existing
    `infrastructure.listener.*` row's *prose*, not its class list — see the next bullet) explaining that
    `NotificationOutboxSupport.enqueueEmail` is the outbox producer entry point and
    `NotificationEmailOutboxHandler` is the consumer the generic outbox poller (`platform.outbox.service`)
    dispatches to, which calls `MailManager.sendEmailSync` — this is the pipeline the corrected diagram below
    depicts
  - **Do NOT add the three registration listeners to this module's own `infrastructure.listener.*` package
    row.** That row documents `platform.notification.infrastructure.listener`, which contains exactly four
    files (`AccountChange`, `Booking`, `SessionPack`, `VideoModeration`) — the registration listeners live in
    `platform.security.infrastructure.listener` and did not move. Document them instead in "Dependencies on
    other layers" (next bullet) and/or a short new sentence in Key Flows, framed as *security-module*
    listeners that call into this module's `NotificationOutboxSupport` — keep the accurate framing that they
    are "exactly the same shape as `BookingEmailListener`/`SessionPackEmailListener`" (same
    `@TransactionalEventListener(BEFORE_COMMIT)` + `enqueueEmail` call), just say where they actually live.
    Also update the existing four-listener row's own prose: two of those four (`Booking`, `SessionPack`) no
    longer publish `Envelope`s either, per this AC's diagram correction
  - **"Dependencies on other layers"**: rewrite the paragraph. It must state, correctly: this module's own
    send path (`MailService` → `OutboundEmailSender`) and the registration path
    (`Coach`/`Parent`/`PlayerRegistrationEmailListener`, in `platform.security` → this module's
    `NotificationOutboxSupport` → the same `MailManager`/`OutboundEmailSender` pipeline) both go through the
    same port and the same transport switch as of `ses-1.4` — there is no longer a module that "bypasses" this
    one. Keep the accurate parts: this module still doesn't construct a `SesV2Client`/`JavaMailSenderImpl`
    itself (that's `infrastructure.ses`/`infrastructure.email.smtp`'s job), and the dependency list on
    `infrastructure.threadpool`/`infrastructure.feature`/`platform.config.service.ConfigService` is unchanged
  - **Sequence diagrams — both need correcting, not one:**
    - "Booking confirmed → templated email sent": the diagram and its prose (`Listener->>Bus: publish
      Envelope(...)`, `Bus->>Manager: sendEmailFromTemplate(envelope) [async, sendMailPool]`, and the
      paragraph below it) describe a flow `BookingEmailListener` no longer executes at all — it calls
      `NotificationOutboxSupport.enqueueEmail` directly, `BEFORE_COMMIT`, inside the business transaction.
      Redraw this diagram (or replace it) to show: domain event → `BookingEmailListener` (BEFORE_COMMIT) →
      `NotificationOutboxSupport.enqueueEmail` (commits atomically with the business row) → the outbox
      poller (`platform.outbox`) → `NotificationEmailOutboxHandler` → `MailManager.sendEmailSync` →
      `MailService` → `OutboundEmailSender` (transport-neutral — do not name a specific transport here)
    - "Failed send → durable retry": its `participant SMTP as JavaMailSenderImpl` and
      `Manager->>SMTP: send MimeMessage` are the same staleness as the diagram above, independent of
      transport — `MailManager` only ever calls `MailService`, which dispatches through the port. Rename the
      participant to the port/`OutboundEmailSender` and fix the arrow label. The *scheduler's own shape*
      (poll → deadline/attempts check → re-dispatch) is otherwise accurate and needs no other change. Also
      fix the three prose spots naming "SMTP" that this diagram's surrounding text carries: the
      `EmailRetryScheduler` key-services row ("SMTP I/O is deliberately kept off the DB connection"), and the
      two sentences under this diagram ("When an SMTP send throws...", "keeping SMTP network I/O off the
      database connection pool") — all should read transport-neutral (e.g. "the transport call")
  - **Conventions & Gotchas**: rewrite the "SES is suppressed by default" `callout warn` entirely — it
    describes `app.ses.enabled`/`NoOpSesEmailService`, both gone. Replace with a callout describing
    `app.email.transport` (`ses`/`smtp`/`log`), `EmailTransportPropertyValidator`'s behaviour (see AC2 for the
    exact mechanism — do not reintroduce that AC's own corrected mistake here), and that registration/OTP mail
    now uses the *same* transport as everything else in this module (no separate suppression path to trip
    over). Leave the `EnvelopeEntity` Flyway-DDL-gap callout as-is — it is still accurate (confirmed still
    open, `ses-1.4`'s own code review re-found and deferred the identical gap in more detail; optionally
    cross-reference `deferred-work.md`'s `ses-1-4-registration-email-durability` section, do not duplicate its
    content)
  - **Related Modules — fix BOTH entries carrying the mirrored false claim, not one:** the **Infrastructure**
    entry ("provides the SES email sender (used elsewhere, not by this module)") and the **Security** entry
    ("its own registration listeners call `infrastructure.ses` directly (bypassing this module)") say the same
    wrong thing from opposite sides — both are false since `ses-1.4` routed registration mail through this
    module's own `NotificationOutboxSupport`. Fix both in the same pass
- [Source: requirements/ses-email-consolidation.md#5 Phase 7; every specific claim verified against current
  code during story creation and again during pre-dev review — see staleness note above for citations]

**AC2 — `docs/dev-docs/infrastructure/index.html`'s `infrastructure.ses` description matches the
post-`ses-1.1` package shape, and every stale claim on this page is fixed, not just the ones inside one
fenced range**

- Given the file currently names `infrastructure.ses.SesEmailService`, `NoOpSesEmailService`, and a boolean
  `app.ses.enabled` flag gating a `SesV2Client` bean — a class *shape* `ses-1.1` changed the *gate* on, not a
  class `ses-1.1` deleted (see the staleness note's correction above — `SesConfig` itself is untouched and
  still builds that bean)
- Then update the `ses` card/description and the surrounding prose (the "modules call
  `infrastructure.ses.SesEmailService`" sentence and the `NoOpSesEmailService`/`SesConfig` paragraph) to
  describe the current shape: `infrastructure.email` (the `OutboundEmailSender` port + `EmailTransport` enum),
  `infrastructure.email.log` (`LoggingEmailSender`, the `transport=log` implementation — note the exact
  sub-package, do not collapse it into `infrastructure.email` itself), `infrastructure.ses`
  (`SesConfig`/`SesEmailSender`/`SesErrorClassifier`/`SesHealthIndicator`/`SesSendRateLimiter`/
  `SesPropertiesValidator` — active when `app.email.transport=ses`; `SesPropertiesValidator` is worth naming
  explicitly, since it is the class most likely to abort a prod boot on a blank `from-address` or an
  unresolvable credential chain, and it's already cited by name in `secrets-reference.md:158`), and
  `infrastructure.email.smtp` (`SmtpEmailSender`/`MailSenderProvider`/`SmtpHealthIndicator` — active when
  `transport=smtp`). Each transport's sender bean carries its own `@ConditionalOnProperty(name =
  "app.email.transport", havingValue = ...)` — there is no boolean enable/disable flag anywhere in this area
  any more
- And state `EmailTransportPropertyValidator`'s actual role correctly — **do not write "one bean per transport
  value is selected by `EmailTransportPropertyValidator`," which is false.** It is an `EnvironmentPostProcessor`
  that only validates the raw property string; it selects no bean and has no bean knowledge. Bean selection is
  entirely the `@ConditionalOnProperty` annotations named above. Two details the wording must preserve: an
  **absent** value is legal by design (falls through to `application.yaml`'s base `log` default — the
  validator does not abort on unset, only on a present-but-unrecognised value), and matching is
  case-insensitive with no whitespace tolerance
- And do not touch any section of this page outside `infrastructure.ses` and its related prose **except** the
  following two sections, which carry the identical dead `app.ses.enabled`/`NoOpSesEmailService` claim and
  must be fixed in the same pass (found by pre-dev review — the original AC's "do not touch any other section"
  fence stranded both):
  - **Conventions & Gotchas** (~line 292): "Emails are silently suppressed in most local/dev setups" — same
    dead class/property, same inverted advice (dev/uat now deliver real mail over SMTP). Rewrite for
    `app.email.transport`
  - **Related Modules** (~line 342): "Notification — the primary consumer of `ses` for outbound email." —
    notification consumes the `infrastructure.email` **port**, not `ses` specifically; `ses` is only the
    active transport in prod. Correct the framing
- [Source: found during story creation, not named in the source doc's two-line Phase 7 scope — verified by
  direct read against `infrastructure/email/`, `infrastructure/ses/`, `infrastructure/email/smtp/`; scope
  correction per pre-dev review]

**AC3 — `secrets-reference.md`'s self-contradicting legacy SES block gets deleted; the rest of the six
deployment docs are re-verified, not assumed current**

- Given story creation found all Phase-1-3-relevant content in `secrets-reference.md` current and
  `local-deployment.md`'s historical crash-loop note correctly past-tensed, and **narrowed the verification
  test to "any claim `ses-1.4` falsifies"** — a narrowing the pre-dev review found lets a live, `ses-1.1`-era
  false claim through
- Then **delete** `secrets-reference.md`'s "AWS SES (legacy)" block (the paragraph beginning
  *"`application-prod.yaml` sets `app.ses.enabled: true`..."* through *"...and use `NoOpSesEmailService`,
  which logs the subject and drops the message"*) — it is present-tense, false on every clause (no
  `app.ses.enabled` property exists anywhere in `application*.yaml`), and directly contradicts the same file's
  own accurate content thirty lines above (`:157-158`). The `NoClassDefFoundError` packaging-bug paragraph
  immediately after it (`:195-199`) is genuine, still-relevant history (a real bug, now fixed, worth keeping)
  — do not delete that part; either fold it into the surrounding accurate section or leave it as a
  standalone dated note, but remove the false premise it currently sits under
- And re-verify (not assume — this AC's original framing wrongly treated the 2026-09-14 story-creation check
  as sufficient) that none of `local-deployment.md`, `deploy-guide.md`, `first-time-setup.md`,
  `secrets-reference.md` (after the deletion above), `uat-deployment.md`, `runbook.md` make any claim
  falsified by **`ses-1.1` through `ses-1.4`** — not `ses-1.4` alone. If dev-story finds another stale claim,
  fix it and note the deviation; record the confirmation in Dev Notes either way
- [Source: found during story creation, corrected in scope by pre-dev review 2026-09-14 — no
  requirements-doc citation beyond the two-line Phase 7 scope named above]

**AC4 — `docs/dev-docs/index.html` (the dev-docs landing page) carries the same dead email-suppression
claim in its platform-wide Conventions & Gotchas — fix it**

- Given `docs/dev-docs/index.html:163` — *"Email is silently suppressed in dev. When `app.ses.enabled=false`
  (the local default), `NoOpSesEmailService` logs the subject line only and sends nothing..."* — the same
  dead property and deleted class as AC1/AC2 correct elsewhere, on the first page this story's own stated
  persona would open
- Then rewrite that one `callout warn` to reflect that dev now delivers real SMTP mail (`ses-1.2`) —
  registration/OTP mail included, for the first time, since `ses-1.4`. If a genuinely useful "why might my
  local email not arrive" note still applies (e.g. SMTP provider credentials blank/misconfigured locally),
  state that instead of the deleted suppression path
- And touch nothing else on this page — this is a one-callout fix, not a broader pass over the landing page
- [Source: found by pre-dev review 2026-09-14, not in this story's original scope — verified by direct read]

**AC5 — `requirements/deployment/local/local-manual-testing.md`'s "Creating the accounts" section
still tells the reader emails never arrive locally — false since `ses-1.2`, and this is a step-by-step guide
someone will actually follow**

- Given `requirements/deployment/local/local-manual-testing.md:230-238`, present tense: *"**Emails never
  arrive.** `SesEmailServiceImpl` is annotated `@Profile("!dev")`, so under the `dev` profile the
  `NoOpSesEmailService` bean wins and logs only the subject... The verification token is still written to the
  database, so pull it from there."* Both named classes are deleted; dev has run real SMTP delivery since
  `ses-1.2`, including registration/OTP mail since `ses-1.4`
- Given the same file's separate §3 ("Fixed: dev no longer builds an AWS SES client," `:145-165`) already
  correctly frames the old `app.ses.enabled: true` crash loop as **past-tense, fixed history** — that section
  needs no change; it is the model for how AC4 above should read too
- Then rewrite the "Emails never arrive" callout to state that dev now delivers real email over SMTP — the
  registration/verification/OTP emails will actually arrive in whatever inbox the test account's address
  resolves to. Keep the SQL token-fetch method documented as a still-valid, often-faster alternative for local
  testing (it works regardless of whether the email also arrives) rather than removing it — do not force the
  reader down one path only
- And touch nothing else in this file — §3's historical framing and the rest of the "Creating the accounts"
  section (phone-OTP-disabled note, the numbered Steps, the API-driving gotchas) are unaffected and were
  checked and found current
- [Source: found by pre-dev review 2026-09-14, not in this story's original scope — verified by direct read;
  §3's already-correct historical framing is the template for this fix]

**AC6 — Every edited file remains structurally valid, checked by a command the dev can actually run**

- Given both HTML pages are hand-written with no build step or linter, and the story's own first draft asked
  for a check ("open in a browser, or... by eye") that isn't reliably executable by an agent
- Then run a deterministic tag-balance check against both edited HTML files after editing:
  ```bash
  python3 - <<'PY'
  from html.parser import HTMLParser
  VOID={'area','base','br','col','embed','hr','img','input','link','meta','source','track','wbr'}
  class P(HTMLParser):
      def __init__(s): super().__init__(convert_charrefs=False); s.st=[]; s.bad=[]
      def handle_starttag(s,t,a):
          if t not in VOID: s.st.append((t,s.getpos()))
      def handle_endtag(s,t):
          if not s.st or s.st[-1][0]!=t: s.bad.append((t,s.getpos()))
          else: s.st.pop()
  for f in ('docs/dev-docs/notification/index.html','docs/dev-docs/infrastructure/index.html',
            'docs/dev-docs/index.html'):
      p=P(); p.feed(open(f,encoding='utf-8').read())
      print(f,'OK' if not p.st and not p.bad else f'MISMATCH unclosed={p.st[:5]} stray={p.bad[:5]}')
  PY
  ```
- And separately read the edited Mermaid `sequenceDiagram` blocks in `notification/index.html`
  character-by-character (`grep -n 'sequenceDiagram\|participant \|->>' docs/dev-docs/notification/index.html`
  and inspect the output) — a broken `participant`/`->>` line fails silently at render time with no error
  surfacing anywhere else, which the tag-balance check above cannot catch since Mermaid blocks are inside a
  single `<pre>` tag
- [Source: found during story creation, general HTML-authoring diligence; the executable check is per pre-dev
  review 2026-09-14 — the original AC's "by eye" instruction was not something an agent can reliably perform]

## Tasks / Subtasks

- [ ] **Task 1 — AC1: rewrite `docs/dev-docs/notification/index.html`'s stale sections**
  - [ ] 1.1 Business Overview: add the registration/OTP-durability sentence; qualify the `Envelope`-as-event
    bullet to name the two listeners it still applies to
  - [ ] 1.2 Package layout table: remove `EmailProperties`/`ProviderConfig`/`MailSenderProvider`; add
    `deliveryDeadline()`/`circuitBreakerName()` mention; add `NotificationOutboxSupport`/
    `NotificationEmailOutboxHandler`/`EmailContentRenderer`/`MailMetrics` to the `service` row; correct the
    template count
  - [ ] 1.3 Key services table: correct `MailManager`'s row (per-template circuit breaker, not one shared
    breaker); rewrite `MailService` row; delete `MailSenderProvider` row; update the four-listener row's prose
    (two no longer publish `Envelope`s); explain the outbox producer/consumer pair
  - [ ] 1.4 Document the three registration listeners as security-module listeners calling into this module
    (Dependencies on other layers / Key Flows) — NOT as a row in this module's own package/service tables
  - [ ] 1.5 Rewrite "Dependencies on other layers" paragraph
  - [ ] 1.6 Redraw the "Booking confirmed" sequence diagram for the real outbox flow (domain event → listener
    BEFORE_COMMIT → `enqueueEmail` → outbox poller → `NotificationEmailOutboxHandler` → `MailManager` →
    `MailService` → port) and its surrounding prose
  - [ ] 1.7 Fix the "Failed send → durable retry" diagram's `SMTP`/`JavaMailSenderImpl` participant, plus the
    three SMTP-naming prose spots (`EmailRetryScheduler` row, two sentences under the diagram)
  - [ ] 1.8 Rewrite the "SES is suppressed by default" gotcha callout for `app.email.transport`
  - [ ] 1.9 Fix BOTH Related Modules entries (Infrastructure and Security)
- [ ] **Task 2 — AC2: rewrite `docs/dev-docs/infrastructure/index.html`'s `ses`-related prose and the two
  stranded sections**
  - [ ] 2.1 Update the `ses` card description and surrounding prose; name `SesConfig` as unchanged-but-regated,
    not deleted; name `SesPropertiesValidator` explicitly; fix `LoggingEmailSender`'s package
    (`infrastructure.email.log`)
  - [ ] 2.2 Correct the `EmailTransportPropertyValidator` bean-selection sentence
  - [ ] 2.3 Fix the Conventions & Gotchas callout (~line 292) and the Related Modules Notification entry
    (~line 342) — both outside the original fenced range, both carrying the same dead claim
- [ ] **Task 3 — AC3: delete `secrets-reference.md`'s stale legacy-SES block; re-verify all six deployment docs**
  - [ ] 3.1 Delete the false "AWS SES (legacy)" premise; preserve the still-relevant packaging-bug history
    that follows it
  - [ ] 3.2 Re-check all six deployment docs against `ses-1.1`-`ses-1.4` (not `ses-1.4` alone); fix anything
    else found, otherwise record the confirmation in Dev Notes
- [ ] **Task 4 — AC4: fix the dev-docs landing page's email-suppression callout**
  - [ ] 4.1 Rewrite `docs/dev-docs/index.html:163`
- [ ] **Task 5 — AC5: fix the local manual-testing guide's "emails never arrive" section**
  - [ ] 5.1 Rewrite `requirements/deployment/local/local-manual-testing.md`'s "Creating the accounts" callout
- [ ] **Task 6 — AC6: structural validity of every edited file**
  - [ ] 6.1 Run the tag-balance script against all three edited HTML files
  - [ ] 6.2 Manually inspect the edited Mermaid diagram blocks line by line
- [ ] **Task 7 — Story wrap-up**
  - [ ] 7.1 Update File List, Change Log, Dev Agent Record
  - [ ] 7.2 Note the Phase 5/6 forward-pointer (one more short doc pass needed once SES cutover/SMTP removal
    ship) in Dev Notes — do not act on it now

## Dev Notes

### Files this story touches

**Modify:**
- `docs/dev-docs/notification/index.html` — AC1
- `docs/dev-docs/infrastructure/index.html` — AC2
- `docs/deployment/secrets-reference.md` — AC3
- `docs/dev-docs/index.html` — AC4
- `requirements/deployment/local/local-manual-testing.md` — AC5

**No change expected (verify, don't assume):**
- `docs/deployment/local-deployment.md`, `deploy-guide.md`, `first-time-setup.md`, `uat-deployment.md`,
  `runbook.md` — AC3

**No code changes.** This is a documentation-only story — no `src/main`/`src/test` files are touched, no
tests to run beyond confirming the repo still builds untouched (it will; nothing under `src/` changes).

### Why this story exists out of the source doc's stated phase order

`requirements/ses-email-consolidation.md` lists Phase 7 last, after Phase 5 (SES cutover, gated on the
still-open D-1 domain decision) and Phase 6 (SMTP deletion, which itself depends on Phase 5 landing). Phases
5/6 are genuinely blocked on an external decision outside this codebase's control. Phase 7's actual content —
dev-docs for the port/transport architecture Phases 1-4 already shipped, plus (found during scoping) the
outbox architecture that predates all of them — has no dependency on Phase 5/6 at all. Pulling it forward
closes a real, growing gap while D-1 remains unresolved, and costs nothing when Phase 5/6 eventually land —
see Task 7.2's forward-pointer.

### The transport, per environment — do not undersell prod, and do not oversell the future

| Environment | `app.email.transport` | Since |
|---|---|---|
| base default (no profile) | `log` | ses-1.1 |
| `dev` | `smtp` | ses-1.2 |
| `uat` | `smtp` | ses-1.2 (Phase 5 flips this to `ses`) |
| `prod` | `ses` | **ses-1.1** — unrelated to Phase 5, already true today |

The "don't document the future" guidance below applies to `uat`/`dev`, not to `prod` — prod has run SES since
`ses-1.1`, and a docs pass that says "SES isn't in use yet" anywhere is itself a new false claim. Phase 5,
when it ships, flips exactly one cell in this table (`uat`: `smtp` → `ses`); Task 7.2's forward-pointer covers
that.

### The one thing to get right about scope beyond the table above: don't document the future

`uat`/`dev` still run `smtp`, not `ses`, and SMTP code still exists. Do not write either dev-docs page as
though the cutover already happened for those two environments.

### Producer architecture — the outbox split that predates every `ses-1.*` story

Two shapes coexist today, and the corrected docs must show both, not collapse to one:

| Producer | Mechanism | Since |
|---|---|---|
| `BookingEmailListener`, `SessionPackEmailListener` (`platform.notification`) | `BEFORE_COMMIT` → `NotificationOutboxSupport.enqueueEmail` → outbox | deferred-91/92 |
| `Coach`/`Parent`/`PlayerRegistrationEmailListener` (`platform.security`) | `BEFORE_COMMIT` → `NotificationOutboxSupport.enqueueEmail` → outbox | ses-1.4 |
| `AccountChangeEmailListener`, `VideoModerationEmailListener` (`platform.notification`) | `@EventListener` → `publisher.publishEvent(Envelope)` → `MailManager`'s own `AFTER_COMMIT` listener | pre-existing, deliberate (`VideoModerationEmailListener`'s own code comment explains why: alerts can fire outside a transaction, and `@TransactionalEventListener` would silently drop them) |

Both shapes converge on the same `MailManager.sendEmailSync` → `MailService` → `OutboundEmailSender` tail —
that convergence is real and worth stating plainly, since it's what makes the transport-switch documentation
(the smaller half of this story) still correct once the producer-side correction (the bigger half) is applied.

### Mermaid gotcha (from prior dev-docs work)

This page's `<pre class="mermaid">` blocks are hand-written sequence diagrams with no build-time validation —
a stray unescaped character or a broken `participant`/`->>` line fails silently at render time (the diagram
box just doesn't render, no error surfaces anywhere else). AC6's tag-balance script does not catch this (the
whole block is one `<pre>` element as far as an HTML parser is concerned) — re-read the edited diagram block
character-by-character after editing, not just the prose around it.

### Previous story intelligence (ses-1.4)

- `ses-1.4`'s own code review found and deferred (`deferred-work.md`,
  "code review of ses-1-4-registration-email-durability") the exact same `envelope_entity` no-Flyway-DDL gap
  this dev-doc's existing callout already names — confirms that callout is still accurate and should be kept,
  not rewritten, beyond an optional cross-reference
- `ses-1.4` is the story that made "registration mail bypasses the notification module" false — the single
  biggest correction this story makes to the transport-related half of `notification/index.html`. The
  producer-architecture half (outbox vs. direct-publish) predates `ses-1.4` entirely — do not attribute it to
  that story when writing the corrected prose
- Post-merge deferred-work.md prune convention (see the `## Last audit: 2026-09-14` entry) — narrow-scope
  checks only look at what the just-merged story actually touched. This story is documentation-only and adds
  no new deferred items, so no comparable prune is needed after this one ships

### Git intelligence (last 5 commits)

`f2a7f86` (requirements: record SES identity/DKIM health-check gap for ses-1-5) → `d4527da0` (deferred-work.md
prune after ses-1-4) → `0bf1fc96` (ses-1.4) → `4d318803` (merge #182, prune after ses-1-3) → `5f302d26` (prune
ses-1-1 item closed by ses-1-3). Confirms no other in-flight work touches `docs/dev-docs/` or
`requirements/deployment/` right now.

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
- 2026-09-14: Pre-dev review (`story-review.md`) applied. 15 findings, all independently re-verified against
  current code — no false positives. Widened AC1 to cover the producer-architecture staleness (the
  `skillars-deferred-91`/`-92` outbox migration, which predates every `ses-1.*` story and is the single
  biggest gap on the page — the story's first draft scoped only the transport delta on top of it). Corrected
  AC1's diagram fix from a one-participant rename to a full flow correction across both diagrams and three
  prose spots; moved the registration-listener documentation out of `notification`'s own package-table row
  (they live in `platform.security`, not `platform.notification` — they did not move modules) into
  Dependencies/Key Flows instead; fixed both mirrored-false-claim Related Modules entries, not one; added the
  outbox services and the per-template-circuit-breaker correction to the tables AC1 already touches (F1, F2,
  F3, F10, F11, F12). Corrected AC2's "Given" clause, which wrongly claimed `SesConfig` was deleted (it
  wasn't — only its gate changed); fixed a false "one bean per transport, selected by
  `EmailTransportPropertyValidator`" sentence (that class only validates the property string and selects no
  bean); fixed `LoggingEmailSender`'s package citation; widened AC2's "do not touch any other section" fence,
  which had stranded two more sections on the same page carrying the identical dead claim (F4, F5, F9, F14).
  Broadened AC3 from verify-only to an edit — `secrets-reference.md` has a live, self-contradicting "AWS SES
  (legacy)" block the original narrow verification test (scoped to claims `ses-1.4` alone falsifies) missed,
  since that block is falsified by `ses-1.1` (F6). Added AC4 and AC5 for two files outside the story's
  original scope that carry the identical dead `app.ses.enabled`/`NoOpSesEmailService` claim: the dev-docs
  landing page (the first page this story's own persona would open) and a step-by-step local manual-testing
  guide's "Creating the accounts" section (F7, F8). Replaced AC4["by eye" HTML check] with a deterministic
  tag-balance script AC6 now names explicitly, since "by eye" is not something an agent can reliably execute
  (F13). Added a four-row transport table to Dev Notes so "don't document the future" isn't over-applied to
  `prod`, which has run SES since `ses-1.1`, independent of Phase 5 (F15).
