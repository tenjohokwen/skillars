# Story Review: ses-1-5-ses-cutover (re-scoped, domain-free version)

- **Story:** `ses-1-5-ses-cutover` (`_bmad-output/implementation-artifacts/ses-1-5-ses-cutover.md`, untracked, `ready-for-dev`); sibling `ses-1-5b-uat-ses-cutover.md` (`deferred`) read for consistency.
- **Audit date:** 2026-10-24
- **Real HEAD:** `4869338a test(infra): consolidate Spring test contexts below the cache cap (deferred-160) (#297)` (working tree has uncommitted story/sprint-status edits; verified against the working tree). The story's own "verified at `4869338a`" claim equals real HEAD, but was re-checked, not trusted.
- **Method:** four read-only parallel layers (citations / precedents / mechanisms / corner cases), then Step 4b: I re-read the deciding evidence for every HIGH/MED finding myself. This **supersedes** the earlier review of the pre-rescope story; its leads were re-checked against the new scope (see §6).
- **Calibration:** "Confirmed" = I re-read the lines. "Reasoned" = follows from code I read but depends on Docker/AWS/Postgres runtime behaviour nobody ran. Nothing here was executed.

## 1. Citation verification (Layer 1; ~55 citations)

All MATCH except the drifts below. Spot-confirmed by me in Step 4b: `V138:613` constraint, `EmailTemplate.java:74`, the `MailManager` comment, `application-dev.yaml:68-74`, `docker-compose.yml:105,110`, `.env.example:172,175`, `AlertNotificationListener.java:61`.

| Citation | Verdict | Corrected |
|---|---|---|
| `application.yaml:~494` "-dev/-uat: smtp" | DRIFTED | text is at line **496** |
| `MailService:59` correlationId UUID | DRIFTED | line **60** |
| `SesConfig:53-60` | DRIFTED (±1) | block is **52-60** |
| `AlertNotificationListener.java:61` | MATCH, package unstated | `platform/notification/service/` |
| `docs/dev-docs/notification/index.html` ~343-351 as a stale dev/uat claim | DRIFTED | text exists but is transport-neutral; **nothing stale there** (it names `ses`/`smtp`/`log` generically). Real stale prose: `infrastructure/index.html` 346-348 and 397-399, `dev-docs/index.html` 163-168 |
| `docs/deployment/uat-deployment.md` as the home for `.env.uat` keys | WRONG TARGET | it has no `.env.uat`/`ENVIRONMENT`/`APP_FRONTEND_URL` text; `.env.uat` instructions live in `uat-hostwinds-deployment.md` (~114, 239-247); `secrets-reference.md:137,139` list the two vars |
| runbook step 1 `GET /actuator/health/notification` (~648) and `secrets-reference.md:175` | NOT IN STORY | same 404 URL the story condemns in scope note 2; both need the 8367 `/manage/...` form |
| `deploy-guide.md:101` | MATCH | real path `docs/deployment/deploy-guide.md` |
| `retry` is a text column | CANNOT_LOCATE by L1; **confirmed by L3** | `main.envelope_entity.retry text` holding `'true'`/`'false'` |
| `manual-testing.md:561` ("or use real SMTP credentials"), `docs/deployment/local/deployment.md:150-170` (inline copy of the local compose) | NOT IN STORY | additional stale/drift-prone spots |

## 2. Ledger & precedent attribution (Layer 2)

| Claim | Sources | Verdict |
|---|---|---|
| ses-1.3 reproduced the "contributor 'smtp' … does not exist" boot failure; pinned by `TransportWiringTest` | ses-1.3 story l.165-172, `application-test.yaml` comment, `b083f651`/`82bdf293` | CONFIRMED |
| `SesConfig` comment: flip to `doNotRetry()` "in Phase 4"; ses-1.4 shipped without flipping | `SesConfig:52-60`, ses-1.1 l.244, ses-1.4 file, git log | CONFIRMED |
| deferred-90 migration-safety convention | `docs/deployment/migration-conventions.md`, `project-context.md:75`, `MigrationLint` | CONFIRMED, **but story under-specifies it** (finding M1) |
| "deferred-work.md reconstruction-check convention" (AC5) | deferred-work.md l.176, 1374…1700 | **PARTIAL/misleading.** It governs *pruning*, not appending. Appending follows "How to read this file" (l.5-17) and `## Deferred from: <story> (<date>)` sections at EOF (last: deferred-160 at l.3511). Neither new item is already ledgered |
| "owner decision 4B / 5A" | story, story-review, 5b, git | **CONTRADICTED as traceable labels.** They are option codes from this chat's Q&A, recorded nowhere else. Cite as "owner, 2026-10-24" |
| dev→log is "the D-4 workaround" | source doc l.1206 | **CONTRADICTED.** D-4 *is* the decided end state ("Rendered HTML to disk, Phase 5"); the workaround part is only that uat stays on SMTP |
| ses-1.7 left a forward pointer | ses-1-7 l.501-512 | CONFIRMED, but it says "whichever of ses-1-5/ses-1-6 lands last flips the `uat` row" — now stale (the uat flip is ses-1-5b) |
| `deferred` is a safe parked status / "auto-pick guard" | `bmad-sprint-status`, `bmad-sprint-planning`, `bmad-create-story`, `bmad-dev-story`, `mto-story-review` | **PARTIAL.** `create-story`/`dev-story`/`mto-story-review` select only `backlog`/`ready-for-dev`, so `deferred` is skipped (guard holds). But `bmad-sprint-status` flags unknown statuses and prompts a correction; and re-running `bmad-sprint-planning` "upgrades to at least `ready-for-dev` if a story file exists" (SKILL.md l.147-152), which would un-defer `ses-1-5b` (it has a file). Nothing in the repo documents `deferred` |
| Source doc: Phase 5 "changes configuration only"; header `Status: Proposed` (2026-09-11) | source l.3, 619 | CONFIRMED; the story now adds a migration + test, so AC4's doc update must record it. §9.2 still calls D-1b a hard gate, contradicting the owner's sandbox-first decision recorded only in 5b |
| ses-1.7's pre-dev review "found 15" | ses-1-7 l.8, 632 | CONFIRMED (15 issues *in the story*) |

## 3. Mechanistic claims (Layer 3, re-checked)

| Claim | Evidence | Verdict |
|---|---|---|
| dev `log` + `include: smtp` aborts boot; removing the block is safe | `SmtpHealthIndicator:95,400,404` (AllNestedConditions on `transport=smtp` AND `provider-configs[0].host`); validator is Boot's `HealthEndpointGroupMembershipValidator` (javap'd by L3); base `application.yaml` declares only `smoke`; no main code injects `MailSenderProvider`/`SmtpEmailSender`/`JavaMailSender` outside the gated `email/smtp` package | TRUE |
| `application-test.yaml` `include: ""` ≈ "no group" shape | Boot 3.5.x `IncludeExcludeGroupMemberPredicate`: empty include ⇒ includes **everything** | **FALSE as worded in the existing test-YAML comment** (supports the story's reword; the story's caution is right) |
| `AbstractIntegrationTest` is `@ActiveProfiles({"dev","test"})`; test YAML pins `log` and blanks include | `AbstractIntegrationTest:83`, `application-test.yaml` | TRUE |
| A plain-text/YAML test can read the three profiles | all YAML single-document, no anchors/`spring.config.activate`; `NoStraySmtpConfigTest` already uses snakeyaml | TRUE (use snakeyaml, split `include` on commas) |
| Log sender: relative dump-dir ⇒ `/app/target/mails` unwritable ⇒ WARN + log-only | `Dockerfile:51,71-72,76`; `LoggingEmailSender` (createDirectories → catch → `startupWriteDisabled`, WARN) | TRUE |
| `APP_EMAIL_LOG_DUMP_DIR` binds | repo convention `APP_STORAGE_S3_ACCESS_KEY`; Boot relaxed binding | Reasoned |
| Bind mount to `./target/mails` works for `appuser` | busybox `adduser -S` ⇒ unpinned system uid; on **Linux** a host dir (uid 1000, 755) or a Docker-created root-owned dir is not writable by it | **Story overstated** (finding L3). Likely fails on Linux; probably fine on Docker Desktop macOS (Reasoned) |
| Preflight insert violates `envelope_entity_email_template_check` | L3 diffed programmatically: constraint 40 values, enum 41, only `SES_CUTOVER_PREFLIGHT` missing, none the other way; `@Enumerated(STRING)`; no later migration touches it; send occurs first inside `circuitBreaker.run`, persist last via `saveAndFlush` in `REQUIRES_NEW` | TRUE for the violation. **New fact:** the failure surfaces *after* SES has accepted the mail (the test email is sent, then the endpoint 500s) and also on the FAILED-row path. The 500 was not reproduced |
| `TestMailManager` universal ⇒ no IT exercises the real insert | `application-test.yaml:84-92`, `TestConfig:214`; preflight tests mock `MailManager` | TRUE |
| Recovery-SQL columns | `main.envelope_entity`: `retry text`, `status varchar` (CHECK: SENT/FAILED/SENDING/DELETE/DEADLINE_EXPIRED/ATTEMPTS_EXHAUSTED), `attempts bigint`, `deadline timestamptz`, `send_id`, `error`; recipients `(envelope_entity_id, email)` + `delivered boolean` (V140); scheduler `LIMIT 10`, 60 s, `MAX_RETRY_ATTEMPTS=6`, expiry at `EmailRetryScheduler:117-128` | TRUE |
| Env beats YAML for `APP_FRONTEND_URL`/`ENVIRONMENT`; neither uat overlay overrides | `docker-compose.yml:105,110`; overlays grep'd | TRUE |
| `ops@skillars.internal` is really mailed | `AlertNotificationListener:61,73` → `sendEmailSync`, only when a rule's channel is `EMAIL` (default `LOG`) | TRUE, narrower than "alerts hard-bounce" — only EMAIL-channel rules |
| `NoHardcodedSenderTest` forbids only `from-address` shape and the literal `APP_EMAIL_TRANSPORT` in four compose files | read in full | TRUE; `APP_EMAIL_LOG_DUMP_DIR` + a volume line are allowed |

## 4. Findings that survived Step 4b

### HIGH
**H1. AC3's "real `runPreflight` IT … reuse an existing context" is not achievable as written, and "if feasible" hides it.**
- `SesCutoverPreflightResource:86` is `@ConditionalOnProperty(transport=ses)`; every shared context is `transport=log` with `TestMailManager`, which never writes an `EnvelopeEntity`.
- The only contexts with the real `MailManager` are the `enable.test.mail=false` fork (`RegistrationEmailDurabilityIT:59`, `ModerationOutboxIT`). A new `ses` context or a new `@TestPropertySource` trips three pins I read: `assert-context-count.sh` ceiling (CI measured 17), `IntegrationTestConventionTest` `EXPECTED_DISTINCT_CONTEXT_KEYS=14` and `EXPECTED_TEST_PROPERTY_SOURCE_COUNT=4`.
- **Fix:** (a) parity test goes in `EnvelopeEntitySchemaIT` (base context; it already asserts `envelope_entity_email_template_check` exists) and compares `pg_get_constraintdef` against `EmailTemplate.values()` — not a row-insert loop (one failed insert aborts the Postgres transaction and hides the other misses); (b) the persistence regression is a method added to a class **already in the existing fork** that sends an `SES_CUTOVER_PREFLIGHT` envelope through the real `MailManager`; no new context, no new `@TestPropertySource`. Drop "if feasible".

### MED
**M1. AC3 doesn't name the migration rules that a naive migration would trip.** Next number **V168** (V138-V167 contiguous, no `R__`). `MigrationConventionLintTest`/`MigrationLint` run in the surefire phase: `DROP … IF EXISTS`; `SET LOCAL lock_timeout` (a plain `SET` fails `SESSION_SCOPED_LOCK_TIMEOUT` above V150); `ADD CONSTRAINT … CHECK` must be `NOT VALID` or carry `-- migration-lint: allow-validating-constraint <reason>` (I read the V162 header and clause); rule 5 (widen one release before first write) is satisfied by the same argument V162 uses — no production deploy has ever happened, and the preflight is a manual admin call; rule 7 (a poller reads the table: `EmailRetryScheduler` every 60 s) must be acknowledged. Cite V162 as the template.

**M2. AC4's runbook edits miss or mis-state several things.** (a) Prod has been `ses` since ses-1.1, so "revert the flip commit" is not a prod rollback; a real one needs `smtp.provider-configs`, `transport: smtp` **and** `include: smtp` re-added (or `log`, which silently drops mail) — say so. (b) Owner/Trigger lines (`runbook.md:629-630`) and check 3 ("immediately after flipping") describe a pending flip that already happened. (c) Step 1's URL (~648) and `secrets-reference.md:175` 404 (scope note 2). (d) "Keep the first-ever-production-exercise language" is correct while uat is on SMTP — keep, but add the pointer to 5b.

**M3. The shared-AWS-account gate is placed where nobody reads it before a prod deploy.** The runbook is consulted for incidents; `first-time-setup.md` and `deploy-guide.md` mention no release gate. Add a pre-deploy pointer in `first-time-setup.md` and in `secrets-reference.md`'s `APP_SES_*` block (~157).

**M4. `deferred` is undocumented and can be un-deferred by tooling.** See §2. Document the status in `sprint-status.yaml`'s header and add a "do not re-run sprint-planning over this" note to `ses-1-5b`; owner may prefer a status the tooling already tolerates.

**M5. Recovery-SQL spec needs a selection predicate and a real exclusion list.** It must target only `DEADLINE_EXPIRED`/`ATTEMPTS_EXHAUSTED` (both set `retry=false` by the scheduler); permanent rejects are `FAILED, retry=false` and must be left alone. The exclusion is wrong: `LIKE '%_OTP'` treats `_` as a wildcard, and `*_OTP`/`*_EMAIL_VERIFY` still misses `PASSWORD_RESET`, `ACTIVATION`, `EMAIL_CHANGE` (tokens) and `BOOKING_REMINDER` (stale). Use an explicit template allow-list.

### LOW
- **L1. Dev-experience after dev=log:** the OTP appears only in the dump file (log line has masked address + correlationId; file names are random UUIDs). Docs should say "open the newest file or grep it for the recipient"; in all-Docker mode it depends entirely on the mount. Add `manual-testing.md:561` and `deployment.md:150-170` to the sweep.
- **L2. Drop `notification/index.html` as a mandatory edit** (nothing false there); keep `infrastructure/index.html` 346-348, 397-399 and `dev-docs/index.html` 163-168.
- **L3. Bind mount:** don't promise portability. Pre-create the host dir in `setup.sh` (a Docker-created dir is root-owned and later breaks `mvn clean`; `target/` is gitignored), document log-only fallback as the *expected* outcome on Linux if it can't be made writable.
- **L4/L5:** see M5; and 5b has dangling refs (`AC5a` → ses-1-5 **AC3**; "AC6's recovery query" → ses-1-5 **AC4**), duplicates ses-1-5 work (frontend-url/ENVIRONMENT, `application.yaml` comment, SDK-retry ledger entry), and doesn't own the "re-flip uat=smtp prose" doc pass that goes stale again; ses-1.7's forward pointer now names the wrong stories.
- **L6.** AC2: parse structurally (snakeyaml), not regex. Testing section omits `EmailTransportBootIT`/`SmtpTransportBootIT` (failsafe).
- **Citation hygiene:** the six drifts in §1; "owner decision 4B/5A" → "owner, 2026-10-24"; "D-4 workaround" wording; ledger-append convention; AC4 must reconcile source doc §9.2 (D-1b hard gate) with the sandbox-first decision and note Phase 5's "config only" line no longer holds.

## 5. What did not survive / was not independently re-checked
- **"Infrastructure `index.html:208` 'dev ships two: gmx and gmail' is stale"** — stays true while dev keeps its provider-configs; dropped.
- **"dev breaks on boot for any other smtp reference"** — searched (main, tests, scripts, workflows, compose, e2e, provision.sh): nothing else depends on dev SMTP. `deploy.yml` `SMTP_*` secrets are CI notifications. Not a finding.
- **Layer 3's "5xx after the mail was sent" severity** — mechanics follow from the `MailManager` comment and `circuitBreaker.run`-then-persist order, but the 500 was never reproduced; kept as a stated consequence, not a proven one.
- **Not independently re-checked by me:** Boot `IncludeExcludeGroupMemberPredicate` empty-include behaviour (L3 read bytecode), the Linux bind-mount ownership failure, `APP_EMAIL_LOG_DUMP_DIR` binding, snakeyaml-vs-regex feasibility details, the deferred-work.md section layout beyond the lines quoted.
- **Prior-review leads dropped for the re-scope:** sandbox/health-DOWN/`ready:false`, config-set IAM, SPF alignment, ops-alert checklist, session-pack trigger, `compose config` secret leak, uat rollback boot trap — all moved to `ses-1-5b` (still valid there); C-6/C-9/C-10/C-14 were folded into the story already and re-verified.

## 6. Recommendation (per-claim)
The re-scope is sound: the dev=log + health-group removal, the migration, the profile-consistency test and the doc/ledger work are each correct and independently useful, and uat is untouched. **Not ready as written; ready after these corrections** (all applied to the story file in the same session):
- **Must fix:** H1 (IT placement), M1 (migration rules/V168), M2, M5 (recovery SQL), the AC4 doc-target fixes (§1), the 5b dangling refs.
- **Should fix:** M3, M4, L1-L3, L6, citation hygiene.
- **Owner call remaining:** whether to keep `deferred` (documented) or use a status existing tooling tolerates (M4).
