# Senior-dev audit — story `skillars-deferred-85`

**Scope reviewed:** the story spec only (`skillars-deferred-85-…-ops-doc-hardening.md`), cross-checked against current source:
`SluPersistenceRetrier.java`, `SnapshotPersistenceRetrier.java`, `SluCalculationService.java`, `SnapshotBatchWriter.java`,
`SluRepository.java`, `PlayerSkillStat.java`, both retrier tests, `IntegrationTestConventionTest.java`,
`assert-context-count.sh`, `pr-build.yml`, `.github/dependabot.yml`, `deploy/backup/{pg-backup,volume-backup,restore-from-dump,restore-from-volume-backup,install-crons}.sh`,
`deploy/provision.sh`, `deploy/lgtm/{alerts.yml,grafana-alerts.yml}`, `docker-compose.yml`,
`docs/deployment/{secrets-reference,first-time-setup}.md`, `infrastructure/config/AsyncConfig.java`,
`security/infrastructure/jwt/JwtSecretService.java`, `JwtSecretBootstrapRunner.java`, and the relevant `deferred-work.md` sections.

Overall: the story is well-researched and most of its factual claims check out (the `skillars-deferred-10` CI/CD
cluster really is already shipped; `alerts.yml` really has no `Callback*`/`Fraud*` alerts; the two retrier files
really only handle `DataAccessException`; `pr-build.yml` really does `load: true` + Trivy + shared composite action).
The findings below are the gaps that survived verification — no fabricated issues.

---

## High — will break CI as written

### H1. AC2's `SluRetrierProxyRetryIT` fails `IntegrationTestConventionTest`, and the story neither tasks nor scopes the fix

`IntegrationTestConventionTest.everyIntegrationTestExtendsTheCanonicalBase()` (runs in the Surefire **test** phase,
so it fails a full `mvn verify` in seconds) scans every `target/test-classes/**/*IT.class` that is non-abstract and
not a Spring Boot slice, and asserts each one either `extends AbstractIntegrationTest` **or** is in a hard-coded
`ALLOWLIST` of three FQNs.

`SluRetrierProxyRetryIT` as specified:
- is named `*IT`,
- does **not** extend `AbstractIntegrationTest` (by design — "minimal standalone context"),
- is **not** a slice (`@SpringJUnitConfig` / `@ExtendWith(SpringExtension.class)` match none of `isSlice()`'s
  `org.springframework.boot.test.autoconfigure.*` / `WebMvcTest` / `DataJpaTest` / … checks),
- is **not** in `ALLOWLIST`.

→ It becomes an `offenders` entry and `assertThat(offenders).isEmpty()` **fails the build**. This is deterministic,
not "may flag" as the Dev Notes phrase it.

Why this is dangerous rather than merely missing: **the story's own AC2 verification steps do not catch it.**
`mvn -o test -Dtest=SluRetrierProxyRetryIT` and `mvn -o test -Dtest='com.softropic.skillars.platform.development.**'`
both run green — `IntegrationTestConventionTest` is in a different package and is not named in either `-Dtest`
selector. The failure only appears in full CI.

Also inconsistent: the story's "Files being modified" / "Project Structure Notes" say "3 Java test classes
(2 updated, 1 new)" and never list `IntegrationTestConventionTest.java`.

**Fix:** pick one and put it in the tasks + File List explicitly —
(a) add `"com.softropic.skillars.platform.development.service.SluRetrierProxyRetryIT"` to `ALLOWLIST` in
`IntegrationTestConventionTest.java` (that file's own javadoc calls this "a design decision, not a fix", which
matches AC2's intent), **or**
(b) name the class `SluRetrierProxyRetryTest` — it starts no container, and `*Test` naming keeps it out of the
convention scan entirely while still running under Surefire. Note this changes the AC2 context-count wording
(the class still adds one context either way; still check `missCount`).

---

## Medium

### M1. AC6 only catches "both alert vars empty" — the ledger item is "either … empty"

`deploy-3-3` reads: *"if `GF_ALERT_NOTIFY_EMAIL` **or** `GF_SLACK_WEBHOOK_URL` are empty … Grafana provisions the
contact point but notifications silently fail"*. AC6 hard-fails only when **both** are empty.

`grafana-alerts.yml` unconditionally defines **both** receivers in the `notify-ops` contact point
(`notify-ops-email` with `addresses: "${GF_ALERT_NOTIFY_EMAIL}"` and `notify-ops-slack` with
`url: "${GF_SLACK_WEBHOOK_URL}"`). So an email-only or Slack-only deployment still provisions one receiver with an
empty setting, and every alert still half-fails silently on that channel — exactly the ledger's complaint. AC6's
Dev Notes even call out that email-only / Slack-only must keep working, but nothing makes `grafana-alerts.yml`
provision receivers conditionally.

Task 8 will mark `deploy-3-3` **closed**. Either broaden the fix (warn when exactly one is set; or template
`grafana-alerts.yml` to include only configured receivers) or leave `deploy-3-3` annotated as partially open with
the residual noted.

### M2. AC6's email check gives false confidence — `GF_ALERT_NOTIFY_EMAIL` set ≠ email delivery works

Grafana email routing also requires the `GF_SMTP_*` block (`GF_SMTP_ENABLED=true`, `GF_SMTP_HOST`, user, password,
from-address — all present in `secrets-reference.md`). AC6 treats a non-empty `GF_ALERT_NOTIFY_EMAIL` as "email
routing configured", but with SMTP disabled/unconfigured the email path still silently fails — the precise failure
mode `deploy-3-3` is about. Consider: if `GF_ALERT_NOTIFY_EMAIL` is set, also require `GF_SMTP_ENABLED=true` (+ host),
or at minimum document the SMTP dependency in the error message / `secrets-reference.md`.

### M3. AC6's suggested `.env`-parsing one-liner aborts `provision.sh` under `set -euo pipefail`

`provision.sh` runs `set -euo pipefail` throughout. The AC's proposed
`grep -E '^GF_(ALERT_NOTIFY_EMAIL|SLACK_WEBHOOK_URL)=' "${ENV_FILE}" | cut -d= -f2-` inside a command substitution:
when a variable is **absent** from `.env` (common — these are optional-looking vars), `grep` exits 1 → the pipeline
fails under `pipefail` → `VAR=$(…)` makes the assignment fail → `set -e` aborts the script **with no message**,
instead of reaching the intended `err "… set GF_ALERT_NOTIFY_EMAIL or GF_SLACK_WEBHOOK_URL …"; exit 1`.

Verified locally: `set -euo pipefail; V=$(false | tr -d ' ')` exits immediately.

**Fix:** the AC must specify `$(grep … | cut … || true)` (or `grep … || true`). As written the dev will very
plausibly copy the snippet verbatim.

### M4. AC7's JWT-rotation fallback text is wrong for this codebase — it would ship an incorrect runbook

AC7 says: *"If the app genuinely has no externally-supplied JWT secret (it is generated at boot), … describe
'rotation' as 'restart the app' with the same session-invalidation consequence."*

Actual mechanism (`JwtSecretService` + `JwtSecretBootstrapRunner`):
- `JwtSecretService.addSecretToThread()` fetches the key from the DB (`secretService.fetchSecret(JWT_VERSION,
  JWT_BUS_NAME)`) and caches it in a `volatile Secret` for the process lifetime.
- `JwtSecretBootstrapRunner` only **inserts** a key when none exists, and is **opt-in / disabled by default**
  (`app.bootstrap.jwt-secret.enabled:false`).
- There is **no rotation method** anywhere.

So a plain restart with the key already in the DB regenerates nothing — "rotation = restart the app" is false.
Real rotation = replace/re-encrypt the `Secret` row for `JWT_VERSION`/`JWT_BUS_NAME` (or bump `JWT_VERSION`), then
restart. `secrets-reference.md` lines 13–16 already document the "auto-generated once, stored encrypted" model;
AC7 must have the dev inspect `JwtSecretService`/`SecretService` for the real procedure, not follow the fallback
sentence. Since the *entire deliverable of AC7 is a correct runbook*, this is worth calling out.

### M5. New hard-`exit 1` requirement is undocumented for operators following the existing setup guide

AC6 makes at least one alert channel effectively **mandatory** (hard stop in `provision.sh` when `.env` is present
and both are blank). Neither AC6 nor AC7 updates `docs/deployment/first-time-setup.md` (Step 5 "Prepare Secrets")
or `secrets-reference.md` to say so. An operator who followed the current docs (which never mention these vars as
required) will hit a new `exit 1` on their `provision.sh` re-run. The error points at `secrets-reference.md`, but
that file won't explain the requirement unless AC7 adds it. Add a line to the `.env`/secrets docs: "at least one of
`GF_ALERT_NOTIFY_EMAIL` / `GF_SLACK_WEBHOOK_URL` is required; `provision.sh` refuses to proceed otherwise."

### M6. AC1's stated motivation is factually off — the exception is **not** unhandled today

The story (Story section, AC1 bullet, ledger mapping) says the transaction exception "propagates raw … into
`SimpleAsyncUncaughtExceptionHandler` as a bare stack trace — the intended `log.error` never fires."

`infrastructure.config.AsyncConfig` (the sole `AsyncConfigurer` bean) registers a **custom**
`AsyncUncaughtExceptionHandler` that logs `"Uncaught exception in @Async method '{}': {}"` with the throwable.
The `@Async @TransactionalEventListener` returns `void`, so an uncaught exception *is* routed there and *is*
logged with a stack trace — just not `SimpleAsyncUncaughtExceptionHandler`, and not with the domain-specific
`"… N rows lost for session X, manual recovery needed"` signal.

The fix (widen `retryFor` + add `@Recover` overload) is still correct and worth doing — the real value is the
**retry** and the **structured** recovery log. But the PR description and AC2 assertions should not lean on the
"bare unhandled stack trace / `SimpleAsyncUncaughtExceptionHandler`" framing; it overstates the current severity
and names the wrong class.

---

## Low / notes (not blockers, worth a line in the story)

### L1. AC3 leaves adjacent `deploy-3-4` items untouched — protect them in Task 8
The `deploy-3-4` ledger section has four bullets: weak integrity check (→ AC3), **"DROP DATABASE may fail if
services other than `app` hold open DB connections"**, hardcoded container UIDs, and **"APP_CID capture races
container registration immediately after `docker compose start app`"**. AC3 edits exactly the code region of the
last one (`restore-from-dump.sh:121`) and does the DB drop that the second one is about, yet addresses neither.
That's acceptable as scope, but Task 8 must close only the weak-integrity-check bullet, not delete the section.
Consider a one-line note in AC3 that the APP_CID race and the "other connections block DROP DATABASE" gaps remain
open by design.

### L2. AC1 `retryFor` uses the broad `TransactionException`
`TransactionException` also covers transaction *usage* programming errors (`IllegalTransactionStateException`,
`TransactionUsageException`, `NoTransactionException`). Not reachable for these two well-formed `@Transactional`
delegates, but it is the same "don't retry/swallow programming errors" concern the Dev Notes raise against
collapsing `@Recover` to `RuntimeException`. Either narrow to the two subclasses the ledger actually names
(`TransactionSystemException`, `CannotCreateTransactionException`) or note the tradeoff explicitly.
(Retry-safety itself is fine: `PlayerSkillStat` uses `GenerationType.UUID` — id assigned in-memory pre-INSERT — so
a rolled-back attempt followed by `saveAll` retry still results in INSERTs via `merge`→transient, no dupes,
no lost writes. The story's "no code change expected" verification conclusion holds.)

### L3. AC4's "always single-part upload" assumption is likely false in production
`aws s3 cp` (awscli v1) switches to multipart at `multipart_threshold` = 8 MB. A gzipped `pg_dump` of a
117-migration app with real booking/payment/messaging/video data will commonly exceed 8 MB within months →
multipart → `-N` ETag suffix → the MD5 comparison is **skipped** exactly in the production case it's meant to
guard. The story does guard for it ("skip … log that only size was verified"), so it's not a bug, but AC4's prose
("which a pg-backup dump of this app's size always is … single-part") oversells what the ETag check delivers.
Realistically AC4 buys you `ContentLength == local size`, which is still worth having.

### L4. AC4 uses `jq`; the sibling scripts use `--query … --output text`
`jq` is installed (`provision.sh` line 32), but `restore-from-volume-backup.sh` and `prune-backups.sh` parse
`aws s3api` output with `--query 'Contents[].Key' --output text` and never touch `jq`. `aws s3api head-object
--query 'ContentLength' --output text` (and `--query 'ETag'`) would match the idiom the story elsewhere insists on
preserving. Minor, but it's a gratuitous new dependency-on-convention.

### L5. AC3's new `psql -t` capture lines have the same `set -e` fragility as M3
The existing table-count capture (`TABLE_COUNT=$(docker exec … psql … 2>/dev/null | tr -d ' \n')`) only survives
`set -euo pipefail` because `psql` succeeds on the happy path; a failed capture aborts the script *before* the
`[ -z … ]` guard runs. AC3 adds two or three more such captures (flyway existence / row count / failed-migration
count). Spec them with `|| true` on each capture (or as `if ! …; then` blocks) so the intended "integrity check
failed" message and the new `ERR` trap fire cleanly instead of a bare `set -e` abort.

### L6. AC6 guard placement aborts provisioning before the data-volume mount
The `.env` block AC6 extends is section 6.5, *before* section 7 (Hetzner Volume mount) and 7.5 (redis dir +
`acme.json`). A hard `exit 1` there leaves a first run that pre-placed `.env` with sections 1–6 done but the data
volume unmounted and `acme.json` uncreated. Sections 7/7.5 are idempotent so a re-run recovers, but blocking
*infrastructure* provisioning on an *alert-routing* misconfig is a poor trade. Consider moving the alert-routing
check to the end of the script (after 7.5), or note the early-abort is deliberate.

### L7. AC8 slightly overstates what's left to close in the alert-guard ledger items
- The `deploy-1-3` bullet "Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh,
  PaymentFailureRateHigh)" is **already** annotated `[CLOSED by skillars-deferred-76 AC7]`. Only the `deploy-3-3`
  bullet "CallbackFailureRatioHigh divide-by-zero on zero callback traffic" is genuinely still open + unannotated.
- AC6's verification wording "confirm **every** ratio-style alert already carries an `and (denominator) > 0`
  guard" is too strong: `alerts.yml`'s `DbConnectionPoolHigh` (`…active / …max`) and `JvmHeapHigh`
  (`…used / …max`) are ratios with **no** explicit `> 0` guard. They're structurally safe (config/max gauges are
  non-zero or absent → no series, not a false alert), so **do not** add noise guards — just phrase the check as
  "every ratio with a denominator that can legitimately be zero", which is `BookingPaymentSettleFailureRateHigh`
  and the `Disk*` alerts, all already guarded.

### L8. `provision.sh` documented run form does not redirect at all
`first-time-setup.md` Step 3 shows `bash deploy/provision.sh` with no redirection, so AC5's
`provision.sh > provision.log` (no `2>&1`) scenario is a hypothetical operator variation. It is nonetheless the
verbatim `deploy-1-4` ledger item, and the `tee /dev/stderr` fix is sound (every `err` call in `provision.sh` is
immediately followed by `exit 1`, so even a non-zero `err` return under `pipefail` is harmless). No change needed —
just don't expect this to matter for the documented path.

---

## Things the story got right (verified, so the dev doesn't re-litigate)

- `skillars-deferred-10` D0/D2/D3/D4 really are shipped: `pr-build.yml` has `load: 'true'` + `aquasecurity/trivy-action`
  (`severity: CRITICAL,HIGH`, `exit-code: '1'`, `trivyignores: .trivyignore`), `.github/dependabot.yml` has
  `github-actions` + grouped `maven`, both workflows use `./.github/actions/docker-build`.
- `alerts.yml` has no `Callback*` / `Fraud*` / `PaymentFailureRate*` alerts; `BookingPaymentSettleFailureRateHigh`
  and both `Disk*` alerts carry `and (…) > 0`. `SubscriptionInvoicePaymentFailureHigh` is an `increase(...) > 5`
  count, no division.
- `assert-context-count.sh` ceiling is `37`; `pr-build.yml` invokes `assert-context-count.sh build.log 37`. The
  documented "bump 37→38 only if CI reports 38, investigate if >38" handling is correct, and the new
  `@SpringJUnitConfig` context does count toward `missCount`.
- `restore-from-volume-backup.sh` really has `restore_failed()` + `trap restore_failed ERR` / `trap - ERR` and
  `log()`/`err()` helpers — a valid template for AC3.
- Backup/restore scripts run under cron as `>> "${LOG}" 2>&1` (`install-crons.sh`), so AC5 correctly scopes the
  `err()`-to-stdout change to `provision.sh` only.
- `docker-compose.yml` passes `GF_ALERT_NOTIFY_EMAIL=${GF_ALERT_NOTIFY_EMAIL:-}` / `GF_SLACK_WEBHOOK_URL=${…:-}`
  and uses `${VAR:?…}` for `GF_SECURITY_ADMIN_PASSWORD` / `MONITORING_DOMAIN` — so AC6's reasoning for putting the
  guard in `provision.sh` rather than compose is sound.
- `secrets-reference.md` has `## Server .env` / `## GitHub Actions Secrets` / `## Notes on Secret Generation`;
  `## Secret Rotation` after the last is a clean insertion point. It already documents the "no `JWT_SECRET` env var"
  fact (see M4 for the rotation-procedure caveat).
