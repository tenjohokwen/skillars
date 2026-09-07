# Senior Dev Review — skillars-deferred-94 (Deploy Hardening, Security, Operational & Listener Safety)

**Reviewed:** 2026-09-07
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-94-deploy-hardening-security-operational-and-listener-safety.md`
**Verdict:** **Do not implement as written.** Two ACs (AC1, AC11) prescribe changes that are broken or impossible and will break production paths. Three ACs (AC2, AC13, AC15) rest on factual errors about the current code. AC16 asks the dev to re-fight a convention that already blocks it. The doc-only ACs (AC3–10, AC12, AC14, AC17) are mostly harmless but several cite wrong files/line numbers or a file that does not exist.

Findings are ranked. Every claim below was checked against the actual tree; false-positive filtering notes are at the end.

---

## CRITICAL

### C1 — AC1: the heredoc / command-substitution pattern does **not** keep the password out of `ps`, and as written it breaks DB auth entirely

**Story claims (AC1):**
> Use heredoc with process substitution … Credentials never appear in `ps aux` (process argument, not env var visible to ps) … Most concise and secure pattern.
> ```bash
> docker exec -e PGPASSWORD="$(cat <<'PGPW'
> $DB_PASS
> PGPW
> )" app psql ...
> ```

**Two independent defects:**

1. **It provides zero `ps` protection.** After shell expansion the kernel still receives
   `execve("docker", ["docker","exec","-e","PGPASSWORD=<thevalue>", ...])`. `ps aux` / `ps -ef`
   read `/proc/<pid>/cmdline`, which is exactly that argv. Command substitution and here-docs
   only change *how the shell assembles the string*, never the final argv. The proposed pattern
   is byte-for-byte equivalent, from `ps`'s point of view, to the current
   `docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}"` (`deploy/backup/pg-backup.sh:32`,
   `deploy/backup/restore-from-dump.sh:134,137,143,152`). The AC's central security rationale
   is false.

2. **The quoted here-doc delimiter disables expansion — the "password" becomes a literal string.**
   `<<'PGPW'` (quoted) means the body is emitted verbatim, so `PGPASSWORD` is set to the literal
   6-char string `$DB_PASS` (or `${POSTGRES_PASSWORD}`), not the secret. The spec writes the
   quoted form in **three** places (AC1 bullet "Spec", AC1 "Example pattern", Dev Notes AC1).
   Following it verbatim makes `psql` auth fail on every backup and every restore. In
   `restore-from-dump.sh` the first failure is the `DROP DATABASE` at line 134 → `set -e` abort
   → EXIT trap restarts the app; the restore path is completely dead. If a dev "fixes" it to an
   unquoted `<<PGPW`, then a password containing `$`, `` ` ``, or `\` is mangled — strictly worse
   than today's direct `"${POSTGRES_PASSWORD}"` expansion.

**Also wrong in AC1:**

- **Occurrence count is 5, not 4.** `grep -n 'docker exec.*PGPASSWORD' deploy/` →
  `pg-backup.sh:32`, `restore-from-dump.sh:134,137,143,152`. AC1 enumerates only
  32 / 135 / 138 / 142 and misses `restore-from-dump.sh:152` — the `run_psql()` helper, which is
  invoked five times for the post-restore integrity checks. Line numbers are also off by 1–10.
- **The verification grep has a hole.** `grep -r "docker exec -e PGPASSWORD" deploy/` does **not**
  match `restore-from-dump.sh:143`, which is `docker exec -i -e PGPASSWORD=` (the `-i` sits
  between). A dev could "pass" AC1's verification with line 143 untouched.

**Recommendation:** Replace the whole approach. The only patterns that actually keep the secret
out of argv without a temp file:
- `PGPASSWORD="${POSTGRES_PASSWORD}" docker exec -e PGPASSWORD "$CID" psql …` — the bare
  `-e PGPASSWORD` (no `=value`) tells Docker to copy the value from its own environment; the
  `VAR=val cmd` prefix goes into the environment, not argv. One-line change per call site,
  covers all 5, works with arbitrary password characters.
- or feed SQL over stdin with a mounted `~/.pgpass`.

Then fix the verification to `grep -rE 'docker exec .*-e +PGPASSWORD=' deploy/` (note `=`).

---

### C2 — AC11: `apt-get install -y awscli=2.*` does not exist; it will abort provisioning

**Story claims (AC11):**
> ```bash
> # Before:
> apt-get install -y awscli
> # After:
> apt-get install -y awscli=2.* python3-pip
> ```

**Reality:**

- `provision.sh:131` is **one combined line**:
  `apt-get install -y curl git unzip jq rsync fail2ban ufw ca-certificates gnupg lsb-release awscli`.
  The "Before" in the AC does not exist as its own statement.
- **Debian/Ubuntu do not package AWS CLI v2 at all.** The `awscli` apt package is 1.x on every
  current Ubuntu (including 24.04). `apt-get install -y "awscli=2.*"` → *"Version '2.\*' for
  'awscli' was not found"* → non-zero exit → `set -euo pipefail` → **provisioning aborts**. AWS
  CLI v2 is distributed only as the bundled zip installer from `awscli.amazonaws.com`; it is also
  **not on PyPI**, so `python3-pip` does nothing for it.
- **Regression risk even if it installed.** `pg-backup.sh` contains a large block of comments
  written specifically around **awscli v1** semantics (`multipart_threshold = 8 MB`, single-part
  vs `-N` ETag, "awscli v1"). v2 also enables a **client-side pager by default**; the
  `aws s3api head-object … --output text` calls in `pg-backup.sh` and `restore-from-dump.sh` run
  inside `$(…)` in a non-TTY cron context and can hang/misbehave unless `AWS_PAGER=""` is
  exported. AC11 mentions none of this.

**Recommendation:** If v2 is really wanted, use the official installer
(`curl … awscli-exe-linux-$(uname -m).zip` → `./aws/install`), pin its version, set
`AWS_PAGER=""` in the backup scripts, and re-audit the v1-specific comments/logic in
`pg-backup.sh`. Otherwise drop AC11 — v1 is functioning and the "Hetzner edge cases" claim is
unquantified.

---

## HIGH

### H1 — AC15: premise is factually wrong, and the fix contradicts an explicit in-code design decision

**Story claims (AC15):**
> When `platform.admin_alert_email` is blank, `sendAdminAlertSync()` returns normally without
> sending. **Issue:** No ERROR log; outbox row is released silently; operator has no signal…
> **Fix:** throw `ConfigurationException` so the listener framework retains the outbox row.
> … Spring `@TransactionalEventListener` catches exception, logs ERROR, and retains outbox row.

**What the code actually does** (`VideoModerationEmailListener.java`):

- **There IS an ERROR log.** `adminAlertEnvelope()` line 115:
  `log.error("platform.admin_alert_email config key is blank — admin alert NOT sent: {}", …)`.
- **There is also a startup signal.** `@PostConstruct checkAdminAlertConfig()` (lines 46–57)
  logs ERROR at boot when the key is blank, and **hard-aborts startup**
  (`throw new IllegalStateException`) when `ARACHNID_ENABLED` is on. So for the case that
  actually matters (CSAM detection enabled) the app already refuses to boot.
- **Returning normally is a deliberate, documented decision.** Lines 86–89:
  *"Returning normally is deliberate: no number of re-drives fixes an unset config key, and a
  retained row would occupy a claim slot until a human noticed."* AC15 proposes the exact
  opposite with no acknowledgement of the trade-off it reverses. Implementing it means every
  moderation alert raised while the key is blank permanently pins an outbox claim slot on every
  retry.
- **Wrong framework mechanism.** The class comment (lines 31–34) states, with rationale, that it
  uses `@EventListener`, **not** `@TransactionalEventListener`. And `sendAdminAlertSync()` is not
  an event-listener method at all — it is the `ModerationAdminAlertSender` contract impl called
  directly by the outbox handler. Whether a throw retains the row depends on that handler's
  claim/transaction semantics, not on "Spring listener framework."
- **`ConfigurationException` does not exist in the repo** (`find … -name ConfigurationException.java`
  → nothing). The established pattern here is `IllegalStateException` (lines 51, 97). AC15's
  snippet will not compile as written.
- **The blank check is in a shared helper.** `adminAlertEnvelope()` (line 112, check at line 114)
  is called by **both** `sendAdminAlertSync()` (line 84) **and** the `@EventListener`
  `onAdminAlert()` (line 61). Making it throw also changes `onAdminAlert()` — an exception out of
  an `@EventListener` propagates into whichever moderation-processing code published the event,
  which can break/rollback video moderation itself. AC15 treats this as a localized one-liner.
- **An existing test pins the current behavior.** `VideoModerationEmailListenerTest`
  `blankRecipient_returnsWithoutSending()` asserts no throw. AC15 silently inverts it; the story
  does not call out that this test (and any `ModerationOutboxIT` path) must be rewritten.

**Recommendation:** If a stronger signal is wanted without slot exhaustion: keep the normal
return, add a rate-limited WARN + a metric/counter, or gate the whole alert path on a
"moderation configured" feature check mirroring the existing `ARACHNID_ENABLED` guard. If a
throw really is wanted, scope it to `sendAdminAlertSync()` only (not the shared helper), reuse
`IllegalStateException`, and confirm the outbox handler actually retains-and-backs-off rather
than hot-looping.

---

### H2 — AC13: `depends_on: [app]` couples the entire monitoring stack to app health; spec and rationale contradict each other

**Story claims (AC13):**
> Add `depends_on: - app` to Prometheus … *Ensure app is healthy before Prometheus starts
> scraping* … Verification: Prometheus enters `healthy` after app reaches `healthy` … No
> regression risk.

**Problems:**

- **Short form ≠ health gate.** `depends_on: [app]` is `condition: service_started`. It waits
  only for the app *container* to start, not for the healthcheck (`app` has
  `start_period: 60s`, 3 retries). So it does **not** deliver "app healthy before scraping" and
  the stated verification step is unachievable with the stated spec. Every other service in this
  file uses the long form with explicit `condition:` — the story should too, and should say
  which condition.
- **If it is "corrected" to `condition: service_healthy`, that is a real operational
  regression.** `grafana.depends_on.prometheus` (line 338). Chain becomes
  postgres/redis healthy → app healthy → prometheus → grafana. A crash-looping or slow app then
  means **Prometheus and Grafana never come up** — you lose metrics, alerting, and dashboards in
  precisely the incident where you need them. Today prometheus/loki/tempo/grafana start
  independently of `app`, which is correct for a monitoring stack.
- **Even the short form** blocks Prometheus (and transitively Grafana) from starting on a fresh
  `docker compose up` if the app image/config is bad enough that the container never reaches
  "started".
- **The problem being solved is benign.** A target being briefly `up == 0` at cold start is
  normal Prometheus behaviour; the next `scrape_interval` recovers it. There is no failure to
  eliminate. The AC's "eliminates cold-start scrape failures" overstates a non-issue.
- AC13's YAML sample shows `image: prom/prometheus:latest`; the file pins
  `prom/prometheus:v3.4.1`.

**Recommendation:** Drop AC13, or (weakest acceptable form) add
`depends_on: { app: { condition: service_started } }` **with an explicit comment that it must
never be tightened to `service_healthy`**, because monitoring must survive an unhealthy app.

---

## MEDIUM

### M1 — AC2: the container-UID table is wrong (`10001` is Loki/Tempo, not postgres) and the sites list is incomplete

- `provision.sh:597–603` and `restore-from-volume-backup.sh:93–99` both show the real mapping:
  `prometheus 65534:65534`, `loki 10001:10001`, `tempo 10001:10001`, `grafana 472:472`.
  `redis` is `999:1000` (`provision.sh:627`, `restore-from-volume-backup.sh:95`), and there is a
  `traefik` case too. AC2 says *"10001 = postgres"* — that is simply incorrect and would be
  copied into the docs it asks the dev to write. `postgres` (`postgres:17-alpine`) gets **no
  chown** in `provision.sh` (line 595 is `mkdir -p` only); it relies on the image entrypoint.
- AC2 names only 2 files; the UID assumptions live in **3** places (add
  `restore-from-volume-backup.sh` — the story lists it but the checkboxes/line refs only cover
  provision.sh + the volume-restore snippet inconsistently) and omit redis and traefik entirely.
- **Premise is softer than stated:** every image in `docker-compose.yml` is already version-
  pinned (`postgres:17-alpine`, `prom/prometheus:v3.4.1`, `grafana/grafana:11.4.0`,
  `grafana/loki:3.4.2`, `grafana/tempo:2.9.0`, `prom/node-exporter:v1.9.0`). UIDs only move on
  deliberate image bumps, which already go through review. "Snapshot base images in
  `.env.example`" duplicates the pins that are already authoritative in the compose file.

### M2 — AC2 & AC6 reference `docs/deployment/prerequisites.md`, which does not exist

`ls docs/deployment/` → `backup-restore.md, deploy-guide.md, first-time-setup.md,
local-deployment.md, migration-conventions.md, monitoring.md, rollback.md, runbook.md,
secrets-reference.md, traefik-tls.md, uat-*.md` — no `prerequisites.md`. The story's
"Code Locations → Docs" list presents it as existing. The dev needs to be told to create it or
to fold these notes into `first-time-setup.md` / `deploy-guide.md`.

### M3 — AC16: the "gap" is already a conscious trade-off with compensating coverage, and the prescribed IT is blocked by a convention test

`VideoModerationEmailListenerTest`'s own class Javadoc spells it out:
- A real-`MailManager` FAILED-row IT needs a throwing `MailService` or `@MockitoBean`, and
  *"both fork the Spring context and trip `IntegrationTestConventionTest`"* — both referenced
  files exist (`src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`,
  `.../notification/infrastructure/MailManagerResilienceTest.java`).
- `MailManager`'s exception→`EnvelopeEntity(FAILED, isRetry)` mapping is **already pinned by
  `MailManagerResilienceTest`**; the listener's branch-from-outcome logic is pinned by the unit
  tests in `VideoModerationEmailListenerTest` (`retryableFailure…`, `permanentFailure…`,
  `sentEnvelope…`, `noPersistedEnvelope…`).

AC16 says "`VideoModerationEmailListenerTest:150-173` uses mocked `JavaMailSender`" — it mocks
**`MailManager`**, not `JavaMailSender`, and the branch mapping it says is "never tested" is
tested in two places. Also AC16's "permanent failure → row deleted" describes the outbox
handler, not the listener (the listener logs `[..._UNDELIVERABLE]` and returns). Net: this AC
asks the dev to build an IT that the codebase deliberately disallows, to cover logic that is
already covered. If there is a genuine residual gap it needs to be stated precisely (which
exact transition, and why the existing two tests don't cover it).

### M4 — AC12: the framing is imprecise; the real hole is a suppressed failure email, and there is a cheap actual fix

`.github/workflows/deploy.yml`: the four notification steps (128, 139, 151, 167) have **no
`continue-on-error`**, and "Fail workflow" (184) uses a plain
`if: steps.smoke.outputs.result == 'fail'` (implicit `success()`). Consequences when, e.g.,
"Notify Slack — failure & revert" (139) errors on a real deploy failure:
- the job **still goes red** (a failed non-`continue-on-error` step fails the job) — so the
  failure is *not* hidden, contrary to AC12's "may hide the true deploy failure";
- but "Email — failure & revert" (151) and "Fail workflow" (184) are **skipped** → the failure
  **email never sends** and the explicit marker never runs. *That* is the defect worth noting.

A comment is fine, but the one-line real fix is `continue-on-error: true` on the four
notification steps (or `if: always() && …`). AC12 should at least mention it rather than
enshrining the limitation.

### M5 — AC6: the "silently never fires" risk has a partial backstop the AC ignores

`alerts.yml` also defines a root-filesystem alert on `mountpoint="/"` (lines ~82–90). If the
Hetzner volume is **not** mounted, writes to `/opt/skillars/data` land on `/`, so the root-fs
alert still catches disk exhaustion (just without the per-volume threshold/attribution).
Worth stating so the doc doesn't overclaim total blindness.

---

## LOW / NITS

- **AC1 Dev Notes** call the value `DB_PASS`; the scripts use `POSTGRES_PASSWORD` (loaded by
  `env-guard.sh`). Minor, but the snippet is copy-paste bait.
- **AC3** line ref `restore-from-dump.sh:197-199`; actual fail-fast block is `191-195`. Content
  claim (already fails fast on empty `APP_CID`) is correct.
- **AC9** is effectively already done: `.gitignore:89` has `/data/` with an explanatory comment
  at 86–87. The AC reads as new work; it is verification-only. Fine, just label it so.
- **AC5** attributes node_exporter isolation to "deferred-88 AC8"; the compose comment ties AC8
  to the observability **egress firewall** generally. Cosmetic.
- **AC4** creates checkbox obligations for a hypothetical future Alertmanager that isn't
  deployed. Better as a note in `docs/deployment/monitoring.md` than as story ACs that can
  never be "done".
- **AC17** is reasonable (doc-only, pattern pre-decided in deferred-82/83). One addition: the
  javadoc should also note `createPurchase()`'s own transactionality, since the compensating
  path assumes that call is all-or-nothing. The AC title says "@Transactional Audit" but only
  `purchasePack` is examined — `extendPack`/`createTier`/`deactivateTier` are already
  `@Transactional`, so state that the audit was done and came back clean.
- **AC14** — accurate; `provision.sh:594-603` matches. Doc-only, low risk.

---

## What is solid

- AC3, AC5, AC7, AC8, AC10, AC14 — accurate "accepted limitation / document only" items; the
  underlying code matches the descriptions.
- AC17 — correct call to leave `purchasePack` non-`@Transactional`; the compensating-refund
  block (`SessionPackPaymentService.java:85-95`) is real and matches the description.
- The overall instinct to close `docker exec -e PGPASSWORD=<value>` (C1) is right — the current
  code *does* expose the password in `ps` on the deploy host. Only the prescribed mechanism is
  wrong.

---

## False-positive filtering (checks performed, not flagged)

- Confirmed `docker exec -e VAR=val` argv exposure via `/proc/<pid>/cmdline` semantics — the
  heredoc rewrite genuinely changes nothing; not a nitpick.
- Confirmed quoted-heredoc (`<<'PGPW'`) suppresses `$` expansion per POSIX — the AC's example is
  genuinely broken, verified against the exact text in three locations in the story.
- Confirmed 5th `PGPASSWORD` call site via `grep` (`restore-from-dump.sh:152`), not an
  over-count.
- Confirmed `ConfigurationException` absent and `IllegalStateException` is the local idiom
  (`find` + reading the listener).
- Confirmed `MailManagerResilienceTest` and `IntegrationTestConventionTest` both exist before
  asserting AC16 is redundant/blocked.
- Confirmed `docs/deployment/prerequisites.md` absent via `ls`.
- Confirmed UID `10001` maps to loki+tempo in **both** `provision.sh` and
  `restore-from-volume-backup.sh` before calling AC2's table wrong.
- Confirmed `grafana.depends_on.prometheus` before asserting the AC13 monitoring-coupling
  regression.
- AWS CLI v2 packaging: not in Debian/Ubuntu apt, not on PyPI — high confidence; flagged the
  pager behaviour as "verify on the target image" rather than asserting a specific failure.
