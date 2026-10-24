# Story Ses-1.5b: uat SES Cutover (DEFERRED — gated on domain registration)

Status: deferred

<!-- Deliberately NOT `backlog`: bmad-create-story auto-picks the first `backlog` story. This one must only be picked
     up when the owner says so. Split out of ses-1-5-ses-cutover on 2026-10-24 (owner decision). -->

**DO NOT create/dev this automatically.** The owner will register the sending domain only after the app is thoroughly
tested (D-1). Everything in this file needs that domain and an SES account with production access (D-1b), so it is
parked. Reactivate by (1) owner confirming the domain is registered + DKIM verified, (2) setting this story to
`backlog` in `sprint-status.yaml`, (3) re-running `/mto-story-review` — the ACs below were written and reviewed
2026-10-24 against HEAD `4869338a` and **will have drifted**.

**Workaround until then (owner decision):** `dev` runs `transport=log` (rendered HTML to disk — no domain needed,
shipped by ses-1-5); `uat` stays on Gmail/GMX SMTP; `prod` stays configured for `ses` but is not live.
Phase 6 (`ses-1-6-delete-smtp`) depends on this story (uat must be off SMTP) and is parked too.

**Decision D-1b (owner, 2026-10-24): sandbox first, production access later.** When reactivated, the uat flip may be
run sandbox-first with *verified individual identities* for the technical checks (AC1, AC4, AC5 below), with the
sandbox pass criteria spelled out in AC5, then **re-run AC5/AC5b once production access is granted**; the story is
not `done` until the production-access re-run passes. (Sandbox: `ready:false` and a DOWN `ses` health contributor are
expected — the draft's AC0 originally forbade sandbox; the owner overrode that.)

**Decision (owner delegated, 2026-10-24) — shared AWS account:** uat may share prod's AWS account and region *while
prod is not live*. Before prod serves real traffic, either move uat to a separate AWS account (preferred) or record
explicit acceptance of the shared bounce/complaint-pause risk (SES reputation is per account+region; a configuration
set does not isolate it). Tracked as a runbook gate in ses-1-5 AC4.

## Story

As the platform owner,
I want `uat` to send through SES (own identity + configuration set),
so that the SES path is exercised end-to-end in a non-production environment before real users hit it, and Phase 6
(delete SMTP) becomes safe.

## Verified scope corrections (still valid unless the tree has drifted)

1. **Per-profile `notification` health group will fail boot if not flipped together with the transport.**
   `application-dev.yaml:73-74` and `application-uat.yaml:71-72` declare `management.endpoint.health.group.notification.include: smtp`.
   `SmtpHealthIndicator` is `@Conditional` on `transport=smtp`, so the moment either profile flips, Spring's
   `HealthEndpointGroupMembershipValidator` aborts startup with `Included health contributor 'smtp' in group
   'notification' does not exist` (this exact failure was reproduced by ses-1.3 and is documented at
   `application.yaml:~493-503` and `application-test.yaml:~185-198`). uat → `include: ses`; dev → remove the block
   (base `application.yaml` declares no group for `transport=log`, and `LoggingEmailSender` has no health indicator).
2. **`APP_SES_MAX_SEND_RATE_PER_SECOND` cannot be set in YAML for uat.** `docker-compose.yml:99` always injects
   `APP_SES_MAX_SEND_RATE_PER_SECOND=${...:-10}`; an env var beats `application-uat.yaml`. A sandboxed account (1/s)
   must therefore be configured in the server's `.env.uat`, not in the repo. Same for `APP_SES_CONFIGURATION_SET`,
   `APP_SES_ACCESS_KEY/SECRET_KEY`, `APP_SES_FROM_ADDRESS`, `APP_SES_REPLY_TO_ADDRESS`, `APP_SES_REGION`.
3. **UAT runs on a Hetzner box (`docker-compose.uat.yml`) or a 1 vCPU Hostwinds box (`docker-compose.uat-hostwinds.yml`), neither on AWS.** There is no instance
   role, so the default credential chain will not resolve. `SesPropertiesValidator` deliberately forces the chain at
   boot (ses-1.1 code review D2) → uat **must** be given explicit `APP_SES_ACCESS_KEY`/`APP_SES_SECRET_KEY` for a
   least-privilege IAM user (`ses:SendEmail`, `ses:GetAccount` — the same two permissions `docs/deployment/runbook.md`
   already lists; if a configuration set is used, scope `SendEmail` to include the configuration-set ARN or use `*`, since SES v2 authorizes against it too and a 403 classifies Permanent (`SesErrorClassifier`) = dropped mail — PLAUSIBLE, AWS behaviour, verify in the AWS console/test), or boot aborts.
4. **Rollback is NOT "flip a property".** The source doc §8 says rollback is `app.email.transport=smtp` + redeploy.
   But `APP_EMAIL_TRANSPORT` is deliberately never passed through any compose file (`.env.example:58-61`,
   `docker-compose.yml:85-89`, `NoHardcodedSenderTest`: a blank value would override every
   profile's transport at once; note those comments cite `EmailTransportPropertyValidator`'s javadoc, which does not actually say this). In this repo the transport lives in the committed profile YAML, so rollback of uat
   = a revert of **both** `transport:` and `notification.include:` in `application-uat.yaml` (reverting only the transport leaves `include: ses`, `SesHealthIndicator` has no bean, and boot aborts in `HealthEndpointGroupMembershipValidator` — scope correction 1 inverted) + rebuild/redeploy. Simplest: revert the whole flip commit. Say so in the runbook; do **not** "fix" it by
   adding `APP_EMAIL_TRANSPORT` to compose (`NoHardcodedSenderTest`/review history says why).
5. **Stale docs state things Phase 5 makes false or that were already false** (ses-1-5 AC4 fixed the dev side; this story's second docs pass, see Notes): `docs/deployment/runbook.md`
   "Pre-production release gate: SES cutover" (lines ~632-633) claims *"`dev`/`uat`/`test` all run `app.email.transport: log` today,
   `smtp` is deliberately rejected until Phase 2"* — wrong since ses-1.2 (dev/uat run `smtp`) and wrong again after
   this story. Its closing paragraph says to *"revisit this gate only if a later change adds a non-production
   environment that genuinely exercises the SES path end-to-end"* — **that is exactly this story.**
6. **uat mail links/env point at production unless overridden.** `docker-compose.yml:105,110` always inject
   `ENVIRONMENT=${ENVIRONMENT:-prod}` and `APP_FRONTEND_URL=${APP_FRONTEND_URL:-https://skillars.com}`; env beats
   `application-uat.yaml:25`, and `.env.example:172,175` seeds the prod values. Neither uat overlay overrides them.
   Latent under SMTP, but this is the first time uat mail reliably reaches real inboxes. Add both to AC4 and AC5b.
7. **Health URLs.** Actuator is on port **8367**, base-path `/manage` (`application.yaml:473-478`); Traefik only
   exposes `/actuator/health` → `/manage/health/smoke` (`docker-compose.yml:143-155`). `/actuator/health/notification`
   404s regardless of config, so it proves nothing. Use
   `docker exec <app> wget -qO- http://localhost:8367/manage/health/notification` (details need `ROLE_ADMIN`, so an
   authenticated call via SSH tunnel; 8367 is loopback-only on Hostwinds). Every health-URL mention below means this.
8. **Config-set ≠ reputation isolation (PLAUSIBLE, AWS behaviour).** SES reputation/pause thresholds are per
   account+region; a configuration set only separates metrics/events. Only a separate AWS account or region isolates
   uat from prod (source §6.9 itself says a high bounce rate pauses the whole account).

## Acceptance Criteria (moved from ses-1-5; AC numbers kept for traceability)

Prerequisite: ses-1-5 is done (dev on `log`, preflight migration AC3 there, docs fixed). Items below that say
"ses-1-5 AC3" mean ses-1-5's **AC3** (migration V168 + tests); references to "AC6's recovery query" mean ses-1-5's **AC4**.

**AC0 — Owner gate (blocking; record the answers in the Dev Agent Record).** Before the uat flip merges, the owner
confirms, with evidence:
- D-1: the chosen sending domain/address for **uat** (own identity, different from prod's) — supplied only via
  `.env.uat` `APP_SES_FROM_ADDRESS`; never committed.
- D-1b: SES account state for uat's region (`APP_SES_REGION`). **Owner decision 2026-10-24 (source doc §9.2 D-1b,
  supersedes its earlier "hard gate for Phase 5"):** uat may flip **sandbox-first**, sending only to verified
  individual identities. In sandbox the preflight `ready` is always `false` and the `ses` health contributor is DOWN
  (`SesCutoverPreflightResource` requires `productionAccessEnabled`; `SesHealthIndicator:162`), so the default
  `/manage/health` and the admin dashboard read DOWN. That is the expected shape there, so AC5's sandbox pass criteria
  are `accountCheck.error == null` and `testSend.status == "SENT"`, with `ready:false` expected. Re-run AC5 with
  `ready:true` once production access is granted; production access stays a hard gate for prod serving real users.
  Record which state the account is in. Domain DKIM `verified`, SPF includes `amazonses.com`, DMARC ≥ `p=none` with `rua`.
- A uat-specific SES **configuration set** exists (§6.7 item 4) → `APP_SES_CONFIGURATION_SET` (optional at boot —
  validated only when non-blank — so this is an owner check, not enforced). Also record whether uat shares the prod
  AWS account/region; if yes, record the shared-reputation risk as accepted (scope correction 8).
- IAM user/keys for uat → `.env.uat`: `ses:SendEmail` (incl. configuration-set ARN, or `*`) + `ses:GetAccount`.
- `.env.uat` also sets `APP_FRONTEND_URL` (uat URL) and `ENVIRONMENT=uat` (scope correction 6).
If any is missing: stop after the code/doc parts of AC1 and AC4 are implemented on the branch, report which item blocks, do **not** open the
merge. Never invent or hardcode a placeholder domain to proceed.

**AC1 — uat flips to SES (`application-uat.yaml`).**
- `app.email.transport: ses`; `app.ses.from-address: "${APP_SES_FROM_ADDRESS:}"` stays exactly as is (blank default →
  fail-fast; `NoHardcodedSenderTest` expects uat's allowed default to be empty — must stay green unmodified).
- `management.endpoint.health.group.notification.include: ses` (replaces `smtp`) — **in the same commit** (scope
  correction 1).
- Keep `app.email.smtp.provider-configs` in the file for now (Phase 6 deletes it; it is inert when transport≠smtp:
  `SmtpPropertiesValidator`, `MailSenderProvider`, `SmtpEmailSender`, `SmtpHealthIndicator` are all gated on
  `transport=smtp`). Rewrite the stale ses-1.2 comment block above it (says "UAT now runs the SMTP transport") to
  say uat runs SES and the SMTP block is retained only as the rollback path until Phase 6.
- Add a comment on the sandbox rate (`.env.uat` `APP_SES_MAX_SEND_RATE_PER_SECOND=1` if sandboxed; per-node, divide by
  node count) pointing at §6.5 (where the source doc asks for the uat-profile comment; §4.5 asks for `max-send-rate-per-second: 1` itself).
- `application.yaml`'s notification-group comment (~494) was already corrected by ses-1-5 for dev; only re-word its uat half (`-uat: smtp` → `ses`) — comment-only edit.

**AC4 — Env/secret surface for uat (no secrets committed).**
- `.env.example`: update the `--- SES ---` block comments (lines ~62-81) (the app block's `APP_FRONTEND_URL`/`ENVIRONMENT=uat` note was already added by ses-1-5 — do not redo): UAT now also requires
  `APP_SES_FROM_ADDRESS` (not "REQUIRED once this ships to prod"); add a note that on Hostwinds/Hetzner uat the
  access/secret keys are **not optional** (no instance role); keep `APP_SES_MAX_SEND_RATE_PER_SECOND` guidance and
  add "set `1` while sandboxed". Mark `GMX_PASSWORD`/`GMAIL_PASSWORD` "temporary — removed in Phase 6 (ses-1.6)" if
  not already worded so.
- `docker-compose.uat.yml` and `docker-compose.uat-hostwinds.yml` overlay `docker-compose.yml` (which already passes
  every `APP_SES_*` var); verify with `docker compose -f docker-compose.yml -f docker-compose.uat.yml config` that
  the app env contains `APP_SES_FROM_ADDRESS`, keys, config set, rate — and that **`APP_EMAIL_TRANSPORT` is absent**.
  Run it with `--env-file .env.example` and grep key names only: without an env file it aborts on
  `GF_SECURITY_ADMIN_PASSWORD`, and with the real `.env.uat` it would print every secret.
  Add nothing unless a var is genuinely missing. `APP_SES_FROM_ADDRESS` must keep **no `:-` fallback** in uat files.
- `docs/deployment/secrets-reference.md` (~157-172): extend the SES table's "required on prod" wording to uat.
- `docs/deployment/uat-deployment.md` has **no** SES/email section today (grep'd): add a short "Email (SES)" section:
  required `.env.uat` keys (incl. `APP_FRONTEND_URL`/`ENVIRONMENT=uat`), how to read the notification health group
  (port 8367, `/manage/health/notification`, admin), rollback = revert both lines (transport + health include) / the
  flip commit + redeploy (scope correction 4). `uat-hostwinds-deployment.md` has no env table (checked) — add only a
  one-line pointer to the new section.

**AC5 — Health and preflight verification on uat (after deploy; evidence in Dev Agent Record).**
- The notification health group (`http://localhost:8367/manage/health/notification` from inside the container; details need an admin-authenticated call — scope correction 7) reports the **SES** indicator with `productionAccessEnabled` as
  expected for the account state — this is the Phase 5 step 3 "confirm SES in both prod and uat". Prod is
  `application-prod.yaml:22-24` `include: ses` already; just confirm, change nothing.
- Run `POST /v1/admin/ses/preflight` (`SesCutoverPreflightResource`, admin-only,
  `@ConditionalOnProperty(app.email.transport=ses)` — so it **now exists on uat**; depends on ses-1-5 AC3) with an internal recipient. Expect
  `ready: true` (requires production access — AC0); if `accountCheck.error` set → IAM; if `testSend.status=="FAILED"` → send path. Confirm the mail
  actually arrived (the endpoint reports API acceptance, not mailbox delivery). This also de-risks prod's first
  flip, which the runbook gate currently calls "the first time ever `SesEmailSender` runs against real AWS".

**AC5b — §7.3 manual checklist on uat against a real inbox** (owner-run; the dev agent prepares the checklist and
records results): register a coach (verification + OTP — the path UAT never delivered before ses-1.2), password reset,
book a session, session-pack expiry warning, admin alert. For each: correct `From` (the AC0 identity, with display name
if configured), links point at the **uat** host (scope correction 6), `Authentication-Results` show DKIM `pass` and DMARC `pass` (via DKIM alignment; SPF `pass` may not be *aligned* without a custom MAIL FROM domain — PLAUSIBLE, don't fail on SPF alignment alone), not in spam, HTML renders in Gmail
and Outlook. Also exercise: language variants (`langKey` de/fr/en subjects) and one send SES rejects (e.g. unverified From in a scratch config) to see the failure shape:
`MessageRejected` classifies **Permanent** (`SesErrorClassifier:68-70`) → the row ends `FAILED, retry=false` after one
attempt, never re-driven; Transient classes (`TooManyRequests`, `LimitExceeded`, `SendingPaused`,
`MailFromDomainNotVerified`, `AccountSuspended`, and the local rate limiter) → `FAILED, retry=true` → re-driven.
Confirm no failed row is silently `SENT`.
Setup notes: the "ops alert" path (`AlertNotificationListener:61`) hardcodes `ops@skillars.internal`, which cannot
receive mail (hard bounce, counts against the account bounce rate) — use the video-moderation admin alert (recipient
`platform.admin_alert_email`) instead; the session-pack expiry warning is a daily 08:00 UTC job
(`SessionPackExpiryNotifier:60`) for a paid pack within 14 days of expiry — create one or trigger the job manually.

**AC7 — Rollout order and Phase 6 separation (documented, not built).** Record in Dev Notes and the runbook:
(a) Phase 6 (`ses-1-6-delete-smtp`) must **not** ship in the same release as this flip — leave at least one full
release cycle (source doc §8); (b) dev's flip needs no deploy coordination; uat's flip merges only after AC0;
(c) because `EmailRetryScheduler`/the outbox are transport-agnostic, mail enqueued under SMTP before the uat deploy
and still `retry=true` will be re-driven through SES after it (§6.12) — From is resolved at send time (`SesEmailSender:60`), as are pending platform-outbox rows; check before deploying: `SELECT count(*) FROM main.envelope_entity WHERE status='FAILED' AND retry='true';` (a pre-deploy check, separate from AC6's recovery query).
(d) Known gap, not fixed here (no `src/main/java` change in this story): `SesConfig:53-60` still has the SDK default retry strategy with a comment saying to flip it in Phase 4, which shipped without flipping; on a 1/s account SDK retries bypass the app rate limiter. Already filed in `deferred-work.md` by ses-1-5 — look at it before this flip.


**Sandbox-first pass criteria for AC5 (owner decision D-1b):** `accountCheck.error == null`, `testSend.status == "SENT"`
to a *verified* recipient, `ready:false` expected, `ses` health contributor DOWN expected (document the DOWN default
`/manage/health` shape in the uat doc). Re-run with the standard `ready:true` criteria after production access.

## Notes
- Not yet decomposed into Tasks — do that on reactivation.
- Rollback = revert BOTH `transport` and `notification.include` in `application-uat.yaml` (or the whole flip commit).
- Phase 6 must not ship in the same release as this flip; leave one full release cycle.
- Known gap carried to the ledger by ses-1-5: SDK default retry strategy in `SesConfig` bypasses the app rate limiter
  on a 1/s sandboxed account — more relevant here than in ses-1-5.

## Owner/tooling notes added by the 2026-10-24 story review
- **Do not re-run `/bmad-sprint-planning` over this file blindly:** it upgrades any story that has a story file to at least
  `ready-for-dev`, which would un-defer this story. `/bmad-sprint-status` flags `deferred` as an unknown status.
- **Already done by ses-1-5, do not redo:** `APP_FRONTEND_URL`/`ENVIRONMENT=uat` env-key docs, the `application.yaml` comment fix,
  the SDK-retry and ops-alert ledger entries, the runbook opening-paragraph/step-1/check-4 fixes, the recovery SQL, the shared-AWS-account gate.
- **This story owns a second docs pass** when it ships: ses-1-5 rewrites docs to say "dev=log, uat=smtp"; flipping uat makes
  `infrastructure/index.html` (~346-348, ~397-399), `manual-testing.md`, `runbook.md` (retire the "first-ever production
  exercise" language and the "revisit this gate" paragraph — this story *is* the revisit), `uat-hostwinds-deployment.md` and
  the source doc stale again. ses-1.7's forward pointer (`ses-1-7-documentation.md` ~501-512) names `ses-1-5`/`ses-1-6`; the uat
  flip it describes is now **this** story.
- Source doc §9.2 D-1b now records the owner's sandbox-first decision (superseding "hard gate for Phase 5"); AC0 above follows it.
