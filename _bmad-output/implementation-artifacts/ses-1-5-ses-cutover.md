# Story Ses-1.5: Domain-Free Cutover Work (dev → log, preflight fix, runbook/doc corrections)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

**Re-scoped 2026-10-24 (owner decisions after `story-review.md`).** The original Phase 5 ("flip uat to SES, dev to
log") needs a registered sending domain (D-1) and an SES account with production access (D-1b). The owner will
register the domain only after the app is thoroughly tested, so everything that needs it moved to
**`ses-1-5b-uat-ses-cutover`** (status `deferred`, never auto-picked) and `ses-1-6-delete-smtp` is parked behind it.
**This story contains only what needs no domain:**

| Environment | After this story | Why |
|---|---|---|
| dev | `transport=log` (rendered HTML to disk) | needs no domain; this is the decided end state (source doc D-4, §6.11), shipped early because it needs no domain |
| uat | **stays on Gmail/GMX SMTP** | the workaround until the domain exists |
| prod | unchanged (`ses`, not live) | — |

## Story

As a developer on the platform,
I want dev to write rendered email to disk instead of sending through Gmail, the SES preflight endpoint to actually
work against the database, and the deployment docs to stop asserting things that are false,
so that the SES migration is safe to finish later without surprises, and local development no longer needs mail credentials.

## Verified scope notes (re-verified by `story-review.md`, 2026-10-24, HEAD `4869338a`)

0. **A Flyway migration is needed.** `V138__baseline_schema.sql:613` `envelope_entity_email_template_check` does not
   list `SES_CUTOVER_PREFLIGHT` (`EmailTemplate.java:74`); no later migration touches it (V139-V167 grep'd).
   `SesCutoverPreflightResource` persists a fresh-`sendId` envelope through `MailManager.sendEmailSync`, whose insert
   path is `saveAndFlush` and is deliberately left to throw (`MailManager` comment ~267-282), so the preflight would hit a
   check violation, contradicting the resource's own comment that `sendEmailSync` "never rethrows". The prod runbook
   gate step 3 has the same latent bug. ("No `src/main/java` change" still holds; the migration is under `src/main/resources`.)
1. **Per-profile `notification` health group fails boot if not flipped with the transport.**
   `application-dev.yaml:73-74` declares `management.endpoint.health.group.notification.include: smtp`;
   `SmtpHealthIndicator` exists only when `transport=smtp`, so `transport=log` + `include: smtp` aborts startup in
   `HealthEndpointGroupMembershipValidator` (reproduced by ses-1.3; see `application.yaml:~493-503`,
   `application-test.yaml:~185-198`). dev → remove the block (base declares no group for `log`; `LoggingEmailSender`
   has no indicator).
2. **Health URLs.** Actuator is on port **8367**, base-path `/manage` (`application.yaml:473-478`); Traefik exposes only
   `/actuator/health` → `/manage/health/smoke` (`docker-compose.yml:143-155`). `/actuator/health/notification` 404s
   regardless of config. Use `docker exec <app> wget -qO- http://localhost:8367/manage/health/notification` (details need
   `ROLE_ADMIN`; 8367 is loopback-only on Hostwinds).
3. **Env beats YAML in compose.** `docker-compose.yml:105,110` inject `ENVIRONMENT=${ENVIRONMENT:-prod}` and
   `APP_FRONTEND_URL=${APP_FRONTEND_URL:-https://skillars.com}`, which override `application-uat.yaml:25`; `.env.example:172,175`
   seeds the prod values and neither uat overlay overrides them. So uat mail links already point at production
   (latent today, under SMTP) unless `.env.uat` sets both. Cheap to fix now (AC4).
4. **`ops@skillars.internal`** is hardcoded at `platform/notification/service/AlertNotificationListener.java:61` (alerts are mailed only for rules whose channel is `EMAIL`; default is `LOG`) — a mailbox that cannot exist, so ops
   alerts hard-bounce (and, on SES, count against the bounce rate). Out of scope to fix (Java); ledger it (AC5).
5. **Rollback of a transport flip is a revert of both `transport:` and `notification.include:`** in the profile YAML.
   `APP_EMAIL_TRANSPORT` is deliberately never in any compose file (a blank value would override every profile and abort
   boot everywhere; see `.env.example:58-61`, `docker-compose.yml:85-89`, `NoHardcodedSenderTest`. Those comments cite
   `EmailTransportPropertyValidator`'s javadoc, which does not actually say this).

## Acceptance Criteria

**AC1 — dev flips to log (`application-dev.yaml`, `docker-compose.local.yml`).**
- `app.email.transport: log`; keep `app.email.log.dump-dir: target/mails` (the comment at lines 11-16 says it "becomes
  load-bearing again" — update it to past tense, don't delete the key).
- **Remove** the `management.endpoint.health.group.notification` block (~69-74) and its ses-1.3 comment — required, not
  cosmetic (scope note 1). Do not replace it with `include: ""`.
- Keep `app.email.smtp.provider-configs` (uat still uses SMTP; Phase 6 removes them) and `app.ses.from-address:
  "${APP_SES_FROM_ADDRESS:dev@localhost}"` — kept because `NoHardcodedSenderTest` pins dev's allowed default to exactly
  `dev@localhost` (not because the log sender uses it: only `SesEmailSender:60` reads it).
- **All-Docker local mode — owner, 2026-10-24:** `dump-dir: target/mails` resolves to `/app/target/mails` in the
  container, which is root-owned (`Dockerfile`: `WORKDIR /app` as root, only `app.jar` chowned, `USER appuser`), so
  `LoggingEmailSender` would WARN and fall back to log-only. In `docker-compose.local.yml` add a writable dump dir with a
  **bind mount** to the host (e.g. `./target/mails`) and set `APP_EMAIL_LOG_DUMP_DIR` to the in-container path. `APP_EMAIL_LOG_DUMP_DIR`
  binds by Boot's relaxed rules (same convention as `APP_STORAGE_S3_ACCESS_KEY`). **Permissions are the hard part
  (story-review L3):** `Dockerfile:71-72` creates `appuser` with busybox `adduser -S` (unpinned system uid). On a Linux
  host a directory you own (uid 1000, 755) or one Docker auto-creates (root-owned) is **not writable** by that uid, and
  a Docker-created root-owned `./target/mails` later breaks `mvn clean`. Docker Desktop on macOS is permissive and will
  probably work. So: pre-create the host directory in `setup.sh` (never let Docker create it) with a mode `appuser` can
  write, avoid `chmod 777` unless documented as dev-only, and treat "documented log-only fallback" as the **expected**
  outcome on Linux if no portable mode works — say so in the docs rather than promising the file. Verify on whichever
  OS you have and record which one.
- `docker-compose.local.yml` comments (~38-66) were written for SMTP-in-dev (the `GMAIL_PASSWORD`/`GMX_PASSWORD`
  rationale). Keep the vars (uat still uses SMTP in the shared base file; Phase 6 removes them) but correct comments so
  nobody is told dev sends through Gmail. **Never write the literal `APP_EMAIL_TRANSPORT` in any compose file, comments
  included** — `NoHardcodedSenderTest.noAppEmailTransportEnvVarInComposeFiles` does a raw `doesNotContain`.
- Verify by running the real dev profile on the host (not only the test profile — AC2's trap): boot must succeed (proof
  the health group was removed correctly); `/manage/health/notification` no longer has the group (nothing logs the chosen
  transport at startup and `beans` isn't exposed; confirm via `/manage/env` as admin or the first send's "log transport"
  line); a register-coach flow writes `target/mails/<correlationId>.html` — named by the per-attempt correlationId
  UUID (`MailService:60`), not the envelope `sendId`.
- **Dev docs must say where the OTP/token now is (story-review L1):** the INFO log carries only the masked recipient,
  subject and correlationId; the rendered body (with the OTP / verification link) exists only in the dump file, whose
  name is a random UUID. Document: "open the newest file in `target/mails`, or grep the directory for the recipient".
  In all-Docker mode this depends on the bind mount.

**AC2 — Pin the transport↔health-group consistency per profile (new source-level test).** Add a plain JUnit test next
to `EmailTransportProfileDeclarationTest` (same style: read the YAML files, no Spring context) asserting for each of
`dev`, `uat`, `prod`: `transport: log` ⇒ the profile declares **no** non-empty `notification.include`;
`transport: ses` ⇒ `include` contains `ses` and not `smtp`; `transport: smtp` ⇒ contains `smtp` and not `ses`. It must
pass today (dev=log/no group, uat=smtp/smtp, prod=ses/ses) and keep working unchanged when ses-1-5b flips uat.
Parse structurally with snakeyaml (precedent: `NoStraySmtpConfigTest`), reading `management.endpoint.health.group.notification.include`
as a comma-separated scalar — not regex over raw text. **Why a test:** `AbstractIntegrationTest` runs `@ActiveProfiles({"dev","test"})` and `application-test.yaml` pins
`transport: log` and blanks the group, so no existing IT can fail on a dev/uat transport↔group mismatch. Mutation-check:
restore dev's `include: smtp` with `transport: log`; flip uat's transport only; flip prod's include only — each must be
RED (reproduce, don't assume). Also reword the `application-test.yaml` comment (~185-193) that says dev's
`include: smtp` loads first (no longer true); keep the blank override as defensive. The existing comment's claim that a blank include matches the base file's "no group" shape is **wrong**: in Boot 3.5.x an empty include means *include everything* (`IncludeExcludeGroupMemberPredicate`) — reword accordingly (it only works in tests because every contributor exists).

**AC3 — Fix the preflight's DB constraint (migration + parity test + persistence regression).**
- *Migration* **V168** (V138-V167 are contiguous; no `R__`). Drop and re-create `envelope_entity_email_template_check`
  with the full current `EmailTemplate` list (41 values; the constraint has 40 and **only** `SES_CUTOVER_PREFLIGHT` is
  missing — diff again at dev time). Template: `V162__admin_action_log_booking_force_complete_type.sql`. It must satisfy
  `MigrationConventionLintTest`/`MigrationLint` (docs/deployment/migration-conventions.md): `SET LOCAL lock_timeout = '5s'`
  (a plain `SET` fails `SESSION_SCOPED_LOCK_TIMEOUT` above V150), `DROP CONSTRAINT IF EXISTS`, and the re-added CHECK either
  `NOT VALID` or carrying `-- migration-lint: allow-validating-constraint <reason>` (small transient table). Header must
  address rule 5 (widen one release before the first write) with V162's argument — no production deploy has ever happened and
  the only writer is a manual admin call — and rule 7 (`EmailRetryScheduler` polls `envelope_entity` every 60 s).
- *Why it matters:* the preflight sends the SES test email **first** (`circuitBreaker.run`) and persists the envelope
  **last** (`saveAndFlush`, left to throw — `MailManager` ~264-285), so today the endpoint would 500 *after* SES accepted
  the mail; the FAILED-row path hits the same constraint. (Not reproduced against a live DB — reproduce first by inserting an
  `SES_CUTOVER_PREFLIGHT` row and seeing the check violation.)
- *Parity test:* add to `EnvelopeEntitySchemaIT` (base context — it already asserts this constraint exists): compare
  `pg_get_constraintdef('envelope_entity_email_template_check')` against `EmailTemplate.values()` (both directions). Do **not**
  insert one row per value in a loop — the first failure aborts the Postgres transaction and hides the rest.
- *Persistence regression:* every shared context is `transport=log` with `TestMailManager` (writes no `EnvelopeEntity`),
  and `SesCutoverPreflightResource` only exists under `transport=ses`. So add a method to a class **already in the existing
  `enable.test.mail=false` fork** (e.g. `RegistrationEmailDurabilityIT`) that sends an `SES_CUTOVER_PREFLIGHT` envelope
  through the real `MailManager` and asserts a `SENT` row is persisted. Do **not** add a `transport=ses` context or a new
  `@TestPropertySource`: they trip `assert-context-count.sh` (ceiling; CI measured 17), `IntegrationTestConventionTest`
  (`EXPECTED_DISTINCT_CONTEXT_KEYS=14`, `EXPECTED_TEST_PROPERTY_SOURCE_COUNT=4`). Mutation-check both tests (remove the value
  from the migration list → RED).

**AC4 — Docs and env corrections that hold regardless of the uat transport.**
- `docs/deployment/runbook.md` "Pre-production release gate: SES cutover": replace the false opening paragraph (~632-633
  "dev/uat/test all run `log` today, `smtp` is deliberately rejected until Phase 2") with the true state: dev=`log`,
  uat=`smtp`, test=`log`, prod configured for `ses` since ses-1.1 (not live). Also fix: the **Owner/Trigger** lines
  (~629-630) and check 3's "immediately after flipping" — prod already runs `ses`, so there is no pending flip commit; and
  **step 1's URL** (~648) plus `secrets-reference.md:~175`, which use the 404 path `/actuator/health/notification` (use
  `http://localhost:8367/manage/health/notification` inside the container; scope note 2). **Fix check 4**: "roll back to
  `smtp`/`log`" is not a prod rollback — prod has no SMTP config and reverting only the transport trips the `include: ses`
  boot trap. A real one re-adds `smtp.provider-configs`, `transport: smtp` **and** `include: smtp` (or `log`, which silently
  drops mail). Note check 3 (the preflight) works only after AC3. Do **not** retire the "first-ever
  production exercise" language or the "revisit this gate" paragraph yet — uat still doesn't exercise SES; add a pointer
  that ses-1-5b will change that.
- Add the **incident recovery query** the source doc §8 asks for. Spec: `retry` is a **text** column; the scheduler selects
  `retry='true' AND status='FAILED' … LIMIT 10` (`EnvelopeEntityRepository:18`) and `EmailRetryScheduler:117-121`
  re-expires past-deadline rows first, so the update must set `status='FAILED'`, `retry='true'`, `attempts=0` **and** push
  `deadline` forward; select only `status IN ('DEADLINE_EXPIRED','ATTEMPTS_EXHAUSTED')` (the scheduler sets both to
  `retry=false`; permanent rejects are `FAILED, retry=false` and must be left alone); exclude time-sensitive templates by an
  **explicit allow-list** of templates to retry, not a `LIKE` (`_` is a wildcard; and a `*_OTP`/`*_EMAIL_VERIFY` pattern still
  misses `SEND_OTP`, `PASSWORD_RESET`, `ACTIVATION`, `EMAIL_CHANGE` tokens and stale `BOOKING_REMINDER`); never reset
  `envelope_entity_recipients.delivered` (V140) or delivered recipients get duplicates; the scheduler drains 10 rows per
  60 s tick. Write the SQL against the real table/columns (read `EnvelopeEntity` + migrations; don't guess) and test it
  on a scratch row.
- **Shared-AWS-account gate (owner delegated the call; decision = C with a written gate).** Add to the same runbook gate
  section: uat may share prod's AWS account and region while prod is not live; **before prod serves real traffic**, either
  move uat to a separate AWS account (preferred) or record explicit acceptance of the shared bounce/complaint-pause risk
  (SES reputation is per account+region; a configuration set doesn't isolate it — PLAUSIBLE, AWS behaviour). The runbook is
  only read during incidents, so also add a one-line **pre-deploy pointer** to this gate in
  `docs/deployment/first-time-setup.md` and in `secrets-reference.md`'s `APP_SES_*` block (~157).
- `.env.example` (~172-175), `docs/deployment/uat-hostwinds-deployment.md` (the `.env.uat` instructions live there, ~114 and
  ~239-247; `uat-deployment.md` has no `.env.uat` text) and `secrets-reference.md` (~137, 139): uat **must** set
  `APP_FRONTEND_URL` and `ENVIRONMENT=uat` in `.env.uat` (scope note 3); add them to the uat env-key list. If the Hetzner
  `uat-deployment.md` has an env-key table, add them there too.
- `docs/dev-docs/infrastructure/index.html` (~346-348 "Dev/uat deliver real email over SMTP", ~397-399 "(`uat`/`dev` run
  `smtp`)"): update for dev=`log`; uat is still SMTP. (`notification/index.html` ~343-351 is transport-neutral — nothing false
  there; leave it.) ses-1.7's forward pointer lives in `ses-1-7-documentation.md` Dev Notes, not in the pages. Also fix
  `docs/dev-docs/index.html:163-168` ("Dev delivers real email over SMTP"), `docs/deployment/local/manual-testing.md`
  (~93, 105, 302-317, 561, 631-640), `docs/deployment/local/deployment.md` (~652-654, and the inline copy of the local
  compose at ~150-170 — keep it in step with the new bind mount; `setup.sh` warns users to compare against it), the `application.yaml:~496` comment
  ("-dev/-uat: smtp" → dev is now log; comment-only edit), and `deploy-guide.md:101` (says the smoke test polls
  `/manage/health`; `deploy.yml:116` uses `/smoke` — already stale). Grep `docs/` for `smtp` near `dev` to catch the rest.
  Treat each sentence as a claim to verify against code.
- `requirements/ses-email-consolidation.md`: update the status header and §9.2/§5 Phase 5 to record the split (Phase 5 →
  ses-1.5 domain-free part + ses-1-5b deferred), the D-1b sandbox-first decision (§9.2 currently calls D-1b a hard gate — reconcile), that Phase 5's "configuration only — no
  code" line no longer holds (this story adds a migration and tests), that D-4 (dev→log) is shipped, and that D-1 is deliberately
  deferred until after thorough testing. The header still says `Status: Proposed` (2026-09-11) — refresh it. Record decisions,
  never secrets.

**AC5 — Ledger and sprint bookkeeping.**
- Add to `deferred-work.md` as a new `## Deferred from: ses-1-5 (2026-10-24)` section at the end of the file (follow its
  "How to read this file" header, l.5-17: open items only, one bullet per item; the "reconstruction check" there governs
  *pruning*, not appending): (a) `SesConfig:52-60`
  still uses the SDK default retry strategy; its comment says to flip it "in Phase 4", which shipped (ses-1.4) without
  flipping — on a 1/s account SDK-internal retries bypass the app rate limiter (owner, 2026-10-24: ledger, not fixed here);
  (b) `AlertNotificationListener:61` hardcoded `ops@skillars.internal` — ops alerts go to a non-existent mailbox;
  (c) both must be looked at before ses-1-5b is reactivated.
- `sprint-status.yaml` already shows `ses-1-5b-uat-ses-cutover: deferred` and `ses-1-6-delete-smtp: deferred` (set at
  re-scope time). dev-story updates only `ses-1-5` per the normal workflow. Do **not** set 5b/1-6 to `backlog`.
  `deferred` is a new, undocumented status: add a one-line definition to `sprint-status.yaml`'s header comment, and note that
  `/bmad-sprint-status` will flag it as unknown and re-running `/bmad-sprint-planning` would upgrade `ses-1-5b` (it has a story
  file) to `ready-for-dev` — don't run sprint-planning over this file without re-checking those two rows (story-review M4).

**AC6 — Containment tests stay green, unmodified.** `EmailTransportArchitectureTest`, `NoStraySmtpConfigTest`,
`TransportWiringTest`, `EmailTransportBootIT`, `SmtpTransportBootIT`, `NoHardcodedSenderTest`,
`EmailTransportProfileDeclarationTest` pass with no edits (new: AC2 test, AC3 IT; edited: only the
`application-test.yaml` comment). A needed edit to any of them means the change is wrong, not the test.
`SmtpTransportBootIT` still proves the SMTP path boots — uat depends on it. `EmailTransportBootIT` and `SmtpTransportBootIT`
are ITs: run them via scoped failsafe, not surefire.

**AC7 — No Java changes.** `git diff --stat` shows nothing under `src/main/java`. If a Java change seems needed, stop and
raise it.

## Tasks / Subtasks

- [x] Task 1 (AC1): `application-dev.yaml` → `log`, remove health group block, comments; `docker-compose.local.yml` bind mount + comment fixes; `setup.sh` pre-creates `target/mails`
  - [x] Task 1.1 (AC1 runtime verification, owner OK 2026-10-24): rebuilt `skillars:local` and recreated `skillars-app-1` (all-Docker, `SPRING_PROFILES_ACTIVE=dev`). Boot healthy in 69 s under `transport=log` with no notification group (no `HealthEndpointGroupMembershipValidator` abort); Flyway applied V168 on the real dev DB; the container shows the bind mount `./target/mails -> /app/target/mails`; a coach registration (`POST /api/security/coach/register`, 200) produced `LoggingEmailSender` log line `to=s***@example.com ... correlationId=79c8799a-...` and the host file `target/mails/79c8799a-7ba0-41a0-b4cf-89869772abc6.html` containing the verify-email link on `http://localhost:9990`. Host (non-Docker) boot not run separately: same profile files, and the dev+test ITs already boot it.
- [x] Task 2 (AC2): profile transport↔group consistency test; three mutation checks; reword `application-test.yaml` comment
- [x] Task 3 (AC3): reproduce constraint failure; V168 migration (lint-clean); parity test in `EnvelopeEntitySchemaIT`; preflight-persistence regression inside the existing mail-fork IT; mutation-check both
- [x] Task 4 (AC4): runbook rewrite/fix + tested recovery SQL + shared-account gate; `.env.example`/uat doc env keys; dev-docs and stale-sentence sweep; source-doc status
- [x] Task 5 (AC5): ledger entries; confirm sprint-status shows 5b/1-6 as `deferred`
- [x] Task 6 (AC6/AC7): run targeted tests only; confirm no `src/main/java` diff

### Review Findings

Code review 2026-10-24 (`/bmad-code-review`, 3 layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor; diff = working tree vs `4869338a`, BMAD bookkeeping files excluded).

- [x] [Review][Patch] Recovery SQL: switch to an allow-list of templates that are safe to re-send, and add an `incident_end` bound. Decision (owner, 2026-10-24) was option (a) of: (a) allow-list + `incident_end`; (b) keep the deny-list + `incident_end` + a "review when adding a template" note; (c) accept as is. The current deny-list re-arms future templates by default, and stale ones (`BOOKING_PAYMENT_UNRESOLVED`, `BOOKING_QUICK_COMPLETE_CONFIRM`, `VIDEO_MODERATION_ADMIN_ALERT`, …) today. The query also has no end bound. Also document that the fixed `now() + 12h` deadline re-expires a backlog over ~7,200 rows (drain is 10 rows/60 s) [docs/deployment/runbook.md:712-730]
- [x] [Review][Patch] Runbook still says the SES client runs with "zero retries" — `SesConfig` keeps the SDK default retry strategy (this story's own deferred-work entry says so) [docs/deployment/runbook.md:640]
- [x] [Review][Patch] `start.sh` does not recreate `target/mails`: after any `mvn clean`, `docker compose up` lets Docker create the bind-mount source (root-owned on Linux), so `LoggingEmailSender` falls back to log-only. Add `mkdir -p target/mails && chmod 777 target/mails` to the generated helper and to the committed `start.sh` [docs/deployment/local/setup.sh:490; docs/deployment/local/start.sh:5]
- [x] [Review][Patch] "Re-run `setup.sh`" cannot fix a root-owned `target/mails` on Linux (`chmod` fails with EPERM). Give the real remedy (`sudo rm -rf target/mails`, then re-run) in the doc and in the `ensure_mail_dump_dir` warning [docs/deployment/local/manual-testing.md:312; docs/deployment/local/setup.sh:427]
- [x] [Review][Patch] `check_mail_dump_dir` requires mode exactly `777`; a setgid/sticky directory (`2777`/`1777`) never passes. Compare the last three digits [docs/deployment/local/setup.sh:185]
- [x] [Review][Patch] "Otherwise boot aborts" is overstated: boot aborts only when a *declared* `include` names a missing indicator; exporting `smtp` over dev (no group declared) boots fine [docs/deployment/local/manual-testing.md:321]
- [x] [Review][Patch] Runbook gate check 3 says "Immediately after the first deploy that serves real traffic"; this contradicts the trigger ("before that first live deploy") and check 4 ("do not let real users on"). Change it to "on the first prod deploy, before real users are let on" [docs/deployment/runbook.md:662]
- [x] [Review][Patch] The label "owner exception recorded 2026-10-24" doesn't say what the exception is (uat may run SES sandbox-first), so the paragraph still reads as unconditional [docs/deployment/secrets-reference.md:181]
- [x] [Review][Patch] The source doc's Phase 5 step 3 still cites `/actuator/health/notification`, which returns 404 (the real path is `localhost:8367/manage/health/notification`) [requirements/ses-email-consolidation.md:617]
- [x] [Review][Patch] The V168 header and lint-marker justification call `envelope_entity` a "small transient table"; nothing deletes its rows. Re-justify with V162's reason (no production deploy has happened yet). Note: editing the file changes its checksum, so the local dev DB that already applied V168 needs `flyway repair` or a reset [src/main/resources/db/migration/V168__envelope_entity_email_template_check_ses_cutover_preflight.sql:13,24]
- [x] [Review][Patch] The parity regex `'([A-Z_]+)'` misses enum names that contain digits, which would give a false "missing from constraint" result. Use `[A-Z0-9_]+` [src/test/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntitySchemaIT.java:94]
- [x] [Review][Patch] `TransportHealthGroupConsistencyTest` hard-codes `{dev,uat,prod}` and reads profile files only. Glob `application-*.yaml`, and overlay the base `application.yaml` so an include inherited from the base file is caught [src/test/java/com/softropic/skillars/infrastructure/email/TransportHealthGroupConsistencyTest.java:44-58]
- [x] [Review][Patch] The `ses-1-5b` hand-off is internally inconsistent:
  - dangling AC references ("see AC6", "stop after AC1-AC9", "(AC9)"); 5b only has AC0/1/4/5/5b/7;
  - AC0 says production access is a "hard gate" with the sandbox branch removed, which contradicts the header's owner sandbox-first decision;
  - AC1 (`application.yaml:~494` comment) and AC4 (`.env.example` `APP_FRONTEND_URL`/`ENVIRONMENT`) redo work ses-1-5 already did

  [_bmad-output/implementation-artifacts/ses-1-5b-uat-ses-cutover.md]
- [x] [Review][Defer] Nothing checks that `EmailDeliveryStatus` matches `envelope_entity_status_check` (the same failure class as `SES_CUTOVER_PREFLIGHT`, only for status) [src/test/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntitySchemaIT.java:72] — deferred, pre-existing

## Dev Notes

### Current state (verified at `4869338a`, 2026-10-24)

| File | Today | This story |
|---|---|---|
| `application.yaml` | `transport: log` default; no `notification` group (comment ~496-503) | comment-only edit (~496) |
| `application-dev.yaml` | `smtp`, `dump-dir: target/mails`, gmx+gmail provider-configs, `include: smtp` | `log`; drop group block; keep rest |
| `application-uat.yaml` | `smtp`, provider-configs, `from-address: ${APP_SES_FROM_ADDRESS:}`, `include: smtp` | **unchanged** |
| `application-prod.yaml` | `ses`, `include: ses`, no SMTP config | unchanged |
| `application-test.yaml` | `log`, `dump-dir: ""`, `include: ""` (~138, ~198) | comment reword only |
| compose | base passes all `APP_SES_*`; no `APP_EMAIL_TRANSPORT` anywhere (intentional) | local file only |

### Traps (documented/reproduced by earlier stories — don't re-litigate)
- Beans are gated by `@ConditionalOnProperty(app.email.transport=…)` (`LoggingEmailSender` has `matchIfMissing = true`);
  `SmtpHealthIndicator` additionally needs `provider-configs[0].host`. In Boot 3.5.x a blank `notification.include` means *all* contributors, not "no group".
- `HealthEndpointGroupMembershipValidator` fails boot on any include name with no bean (ses-1.3, pinned by `TransportWiringTest`
  with synthetic properties — not by the real profile files, hence AC2).
- `NoStraySmtpConfigTest` doesn't forbid `provider-configs` in non-smtp profiles; keeping them is safe.
- Reviews on earlier ses stories repeatedly found tests green under the mutation they exist to catch, and docs asserting
  things not true of the tree (ses-1.7's pre-dev review found 15). Mutation-check; verify every doc sentence.

### Git intelligence
Last five commits are unrelated (`deferred-160`, `-159`, 12.x). No in-flight SES work. Branch: `story/ses-1-5-ses-cutover`.
Commit style `feat(email): …` / `chore(deploy): …`, ending with the repo's Co-Authored-By line.

### Testing requirements
No local `mvn verify`; CI is the gate. Run only targeted tests (new profile test, `EmailTransportProfileDeclarationTest`,
`NoHardcodedSenderTest`, `EmailTransportArchitectureTest`, `NoStraySmtpConfigTest`, `TransportWiringTest`, the new
constraint IT) with scoped surefire/failsafe. Failsafe context cap (deferred-160: 17): the AC2 test is plain JUnit;
the AC3 tests must reuse the existing base context and the existing `enable.test.mail=false` fork (no new context or `@TestPropertySource`).

### Out of scope
Anything needing the domain or production access (ses-1-5b); deleting SMTP (ses-1-6); SDK retry fix; the ops-alert
recipient fix; bounce/complaint consumer; adding `APP_EMAIL_TRANSPORT` to compose; any `src/main/java` edit.

### References
`requirements/ses-email-consolidation.md` §3.4, §4.5, §6.5, §6.11, §8, §9; `docs/deployment/runbook.md` SES gate;
`src/main/resources/application{,-dev,-uat,-prod}.yaml`; `src/test/resources/application-test.yaml`;
`SesCutoverPreflightResource`, `MailManager`, `EmailTemplate`, `V138__baseline_schema.sql`;
`ses-1-5b-uat-ses-cutover.md` (deferred), `story-review.md`.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5.5 (`claude-sonnet-5-5`)

### Implementation Plan

Red-green per AC. AC2: wrote `TransportHealthGroupConsistencyTest` first (green on the unchanged tree, as designed), then flipped dev's transport alone and watched it go RED before removing dev's `notification` block. AC3: wrote the parity test and the persistence regression first, ran them with the migration moved aside (both RED, reproducing the defect on a real Postgres), then restored V168 (GREEN).

### Debug Log References

- RED run (no V168): `RegistrationEmailDurabilityIT.sesCutoverPreflightEnvelope_persistsThroughRealMailManager` -> `DataIntegrityViolation ... violates check constraint "envelope_entity_email_template_check"` on `insert ... ('SES_CUTOVER_PREFLIGHT') ... status SENT`; `EnvelopeEntitySchemaIT.emailTemplateCheck_allowsExactlyTheEmailTemplateEnumValues` -> missing-from-constraint non-empty. This **reproduces story-review H1/C-1 on a real database** (previously only read from source): `MailManager.sendEmailSync` throws from the final `saveAndFlush`.
- GREEN run: `MigrationConventionLintTest` 30/30 (includes V168), `TransportHealthGroupConsistencyTest` 11/11, `EmailTransportProfileDeclarationTest` 5/5, `NoHardcodedSenderTest` 2/2, `NoStraySmtpConfigTest` 2/2, `EmailTransportArchitectureTest` 4/4, `TransportWiringTest` 10/10; failsafe `EnvelopeEntitySchemaIT` 6/6, `RegistrationEmailDurabilityIT` 9/9, `EmailTransportBootIT` 3/3, `SmtpTransportBootIT` 1/1. BUILD SUCCESS. Commands used `-DskipFrontend`; no `mvn verify` of the whole suite (CI is the gate).
- AC2 mutation checks on the real files (each RED, then restored, `git diff` empty for uat/prod): dev `transport: log` with `include: smtp`; uat `transport: ses` with `include: smtp`; prod `include: smtp` with `transport: ses`.
- Recovery SQL run on a throwaway `postgres:16-alpine` container built from the V138 DDL (container removed): of six seeded rows only the two eligible ones (`ATTEMPTS_EXHAUSTED` booking, `DEADLINE_EXPIRED` report) were re-armed; an OTP row, a permanent reject (`FAILED, retry=false`), a pre-incident row and a `BOOKING_REMINDER` stayed untouched.
- `docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.example config` resolves the new bind mount (`./target/mails` -> `/app/target/mails`) and the file contains no `APP_EMAIL_TRANSPORT` literal.

### Completion Notes List

- **dev -> log (AC1):** `application-dev.yaml` transport `log`, dev's `notification` health group removed, comments rewritten; `docker-compose.local.yml` bind-mounts `./target/mails`; `docs/deployment/local/setup.sh` gained `check_mail_dump_dir`/`ensure_mail_dump_dir` (mode 777, dev-only, because the container user's uid is unpinned and Docker would otherwise create a root-owned directory). I did **not** add `APP_EMAIL_LOG_DUMP_DIR`: the relative `target/mails` already resolves to `/app/target/mails` (`WORKDIR /app`), which is the mount target. **Deviation from the story text**, which suggested setting it.
- **Runtime verification (Task 1.1) done 2026-10-24** (see the Task 1.1 line). Side effects on the owner's local stack: app container recreated from a fresh `skillars:local` build; test user `ses-1-5-verify@example.com` (coach, unverified) left in the local dev DB; `target/mails/` created with mode 777. A first attempt with `@example.test` returned 500 because `User` `@Email` rejects that address (test-data issue, not this story; no email was sent). Still reasoned, not run: Linux bind-mount ownership (macOS Docker Desktop only).
- **AC2:** `TransportHealthGroupConsistencyTest` (snakeyaml, structural). `application-test.yaml` comment reworded (also corrects the claim that a blank include equals "no group": in Boot it means every contributor).
- **AC3:** `V168__envelope_entity_email_template_check_ses_cutover_preflight.sql`; only `SES_CUTOVER_PREFLIGHT` was missing (41 enum values vs 40). `EnvelopeEntitySchemaIT` parity test + `RegistrationEmailDurabilityIT` persistence regression (existing context fork; no new context or `@TestPropertySource`).
- **AC4:** runbook (opening, owner/trigger, step 1 URL, step 3 wording, step 4 prod rollback, new step 5 shared-AWS-account gate, "revisit when ses-1-5b ships", new tested recovery SQL section), `secrets-reference.md`, `.env.example`, `uat-deployment.md` (new `ENVIRONMENT`/`APP_FRONTEND_URL` rows), `uat-hostwinds-deployment.md`, `first-time-setup.md` pointer, `deploy-guide.md` smoke URL, dev-docs (`index.html`, `infrastructure/index.html`), `manual-testing.md`, `local/deployment.md`, source requirements doc status/D-1/D-1b/D-4/Phase 5 note. `notification/index.html` deliberately untouched (nothing false there).
- **AC5:** three ledger items appended to `deferred-work.md`; `sprint-status.yaml` header now defines `deferred`.
- **Judgement calls to review:** (1) the recovery SQL uses an explicit `NOT IN` exclusion list rather than the story's "allow-list" wording - same effect, safer default for templates added later is arguable (a future template is retried by default); (2) `setup.sh` uses `chmod 777` on a gitignored dev-only directory; (3) `deferred-work.md` entry placed at end of file per its header conventions.

### File List

- `.env.example` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified)
- `_bmad-output/implementation-artifacts/story-review.md` (modified)
- `_bmad-output/implementation-artifacts/ses-1-5-ses-cutover.md` (new)
- `_bmad-output/implementation-artifacts/ses-1-5b-uat-ses-cutover.md` (new)
- `docker-compose.local.yml` (modified)
- `docs/deployment/deploy-guide.md`, `first-time-setup.md`, `runbook.md`, `secrets-reference.md`, `uat-deployment.md`, `uat-hostwinds-deployment.md` (modified)
- `docs/deployment/local/deployment.md`, `manual-testing.md`, `setup.sh` (modified)
- `docs/dev-docs/index.html`, `docs/dev-docs/infrastructure/index.html` (modified)
- `requirements/ses-email-consolidation.md` (modified)
- `src/main/resources/application-dev.yaml`, `application.yaml` (modified, comment-only for `application.yaml`)
- `src/main/resources/db/migration/V168__envelope_entity_email_template_check_ses_cutover_preflight.sql` (new)
- `src/test/resources/application-test.yaml` (modified, comment only)
- `src/test/java/com/softropic/skillars/infrastructure/email/TransportHealthGroupConsistencyTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntitySchemaIT.java` (modified)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java` (modified)

### Change Log

- 2026-10-24: dev-story run. dev transport -> `log`; V168 + parity/persistence tests; profile consistency test; runbook/doc corrections and recovery SQL; ledger entries; `deferred` status documented. Task 1.1 runtime verification then done (same day); Status -> `review`.

- 2026-10-24: marked done at the owner's instruction (dev complete, Task 1.1 runtime-verified; no separate /bmad-code-review run).
