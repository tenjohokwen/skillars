# skillars-deferred-102: Deploy & Backup Resilience + Cross-Module Bug Sweep

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high
**Story ID:** deferred-102
**Branch:** `story/deferred-102-deploy-resilience-bug-sweep`
**Created:** 2026-09-08
**Supersedes:** `skillars-deferred-97` (backlog stub, no story file) — its four backup/deploy-smoke
items are absorbed here as AC1, AC2, AC4, AC5. Mark `skillars-deferred-97` `withdrawn` in
`sprint-status.yaml` when this story is created.

---

## Story Overview

A large, cross-module reliability story drawn from
`_bmad-output/implementation-artifacts/deferred-work.md`.

The **"genuine one-off bugs & gaps"** class in that ledger is **exhausted** — `skillars-deferred-91`
through `-101` picked it clean, and both the `deferred-100` and `deferred-101` full-file audits
confirm it. What remains in `deferred-work.md` is overwhelmingly `[DECIDED]` / `[DISMISSED]` (do not
re-litigate), "no dev agent can close this" (native DE/FR register review, parent legal copy),
carved-out initiatives (`frontend-test-framework-initiative`, completion-gated coach payout, the
pre-production migration rebaseline), the migration `ACCESS EXCLUSIVE` lock class (documented +
lint-guarded), and speculative / load-dependent notes.

Per the project owner's bucket priority (messaging → video → Database/Performance → payment → SLU →
Platform/Admin → Frontend/UX → Payment/Stripe → Infrastructure/Deployment → booking → drills) and the
"no small stories" instruction, this story bundles:

1. **Backup & restore resilience** (Infrastructure/Deployment) — trap ordering in `pg-backup.sh`, an
   unretried `APP_CID` capture in `restore-from-dump.sh`, and a content-level gap in the pre-Volume
   migration verification. Absorbs `skillars-deferred-97` AC1/AC2 + the `deferred-87` review item.
2. **Deploy-workflow smoke** (Infrastructure/Deployment) — a wasted trailing sleep in the poll loop
   and SSH/transport failures that masquerade as an unhealthy app and auto-revert a good deploy.
   Absorbs `skillars-deferred-97` AC3/AC4.
3. **Provisioning hardening** (Infrastructure/Deployment) — the repo is cloned as **root** directly
   into `/opt/skillars` beside runtime data (a stray `git clean -fdx` wipes every database, Redis
   AOF, metrics store and TLS cert); the checkout races the Volume mount; a single-Volume host with
   an explicit-but-unresolvable `HETZNER_VOLUME_ID` warns-then-guesses instead of failing; `awscli`
   is the Ubuntu-apt v1.
4. **Database / migration** — `stripe_webhook_events.event_id` is an unbounded `VARCHAR` that is also
   the primary key.
5. **Payment reporting correctness** — the revenue running-balance understates a page when two
   ledger rows share an identical `createdAt` across a page boundary.
6. **Session status constants** — `"COMPLETED"` / `"CANCELLED"` string literals scattered through
   `SessionPlanService`, fragile to any status rename.
7. **Drills** — the private-drill repository queries lack an explicit `library_type = 'COACH'`
   filter (defense-in-depth behind the `chk_drill_owner` constraint).
8. **Messaging performance** — `MessagingService.getConversations` was flagged N+1; re-measure at
   HEAD and either batch the residual per-row lookups or pin the measured cost with a query-count IT.
9. **Video entity** — verify `@GeneratedValue(AUTO)` on `VideoApprovalRequest`'s `UUID` PK resolves
   to the SQL `gen_random_uuid()` default under Hibernate 6.
10. **Frontend/UX** — a rapid double-click on the theme toggle can desync the DOM attribute from the
    `darkMode` ref; `booking.store.js`'s `batchAcceptResultsByBatch` map grows unbounded during a
    failed-refresh streak.
11. **Dependency upgrade** — Dependabot PR **#141** (`openpdf` 1.3.43 → 3.0.5) currently **fails the
    build** because openpdf 3.x renamed `com.lowagie.text.*` → `org.openpdf.text.*`; migrate
    `ReportGenerationService.java` and merge it.
12. **Ledger hygiene** — delete the bullets this story closes; record the two project-owner
    decisions taken during story creation.

**Source:** `deferred-work.md` re-verified 2026-09-08 against `master` @ `31982170` (HEAD, immediately
after `skillars-deferred-101` merged and the ledger was pruned of spent sections). Every AC carries a
**Verified at HEAD** block recording what the cited code actually looks like now — several ledger
lines were found already-closed or narrowed during creation and were dropped (see
**Items examined and NOT folded in**).

---

## User Story

**As a** platform engineer responsible for Skillars' operability and its recovery from partial
failure,
**I want** the backup/restore and deploy-smoke scripts hardened so a transient failure can't leak a
truncated dump, abort a successful restore, or auto-revert a healthy deploy; the provisioning script
changed so a mistyped command or a mis-ordered mount can't destroy production data; and the residual
cross-module correctness gaps (unbounded PK column, revenue running-balance twin, session-status
literals, private-drill filter, messaging N+1, theme-toggle desync, unbounded client map) closed in
one pass,
**So that** the platform's operational surface is safe to run under real conditions before the first
production deploy, and `deferred-work.md` reflects only work that genuinely still needs doing.

---

## Project-Owner Decisions (captured 2026-09-08 during story creation)

| # | Question | Decision |
|---|----------|----------|
| D1 | Anchor / scope of this story | **Infra + cross-module combined** — one large story; absorb the `deferred-97` stub. |
| D2 | Completion-gated coach payout (`deferred-91` AC5 Part B) — include here? | **No.** Keep as its own future story; it needs `payout-and-capture-pending.md` D1–D5 sign-off first. Ledger bullet stays. |
| D3 | `deploy-1-5` "repo cloned as root beside runtime data" — approach? | **Dedicated non-root deploy user** owns the `/opt/skillars` checkout; runtime data dirs stay service/root-owned and live outside the checkout root. (AC6) |
| D4 | Dependabot #141 `openpdf` 1.3.43 → 3.0.5 (fails build) | **Fold the migration into this story** (AC18): move `ReportGenerationService.java` to `org.openpdf.text.*`, bump `pom.xml`, verify PDF output, merge #141. |

---

## Acceptance Criteria

> Legend: each AC ends with **Ledger** (the `deferred-work.md` bullet it closes) and **Test**
> (verification). Backend ACs: **GitHub CI is the full-verification gate** — do **not** run
> `mvn verify` locally; `mvn -o test-compile` + targeted `mvn -o test -Dtest=...` for the touched
> classes is the local sanity bar. Shell ACs: `bash -n` + `shellcheck` + a dry-run walkthrough of the
> changed path; there is no CI harness that executes `deploy/**`. Follow
> `_bmad-output/project-context.md` (records DTOs, MapStruct, `@PreAuthorize` on every endpoint,
> `@Testcontainers` ITs, Instancio + AssertJ, Flyway for **all** schema changes,
> `docs/deployment/migration-conventions.md` for lock-safe migration patterns).

---

### AC1: `pg-backup.sh` — register the cleanup trap before the dump pipeline runs

- **Task:** Move `trap 'rm -f "${DUMP_FILE}"' EXIT` so it is armed **before** the
  `pg_dump … | gzip > "${DUMP_FILE}"` pipeline executes, not after it.
- **Verified at HEAD:** `deploy/backup/pg-backup.sh` (`set -euo pipefail` at `:4`). The pipeline is
  `:33-34`; the trap is registered at `:39`, with a `:36-38` comment that already acknowledges the
  hazard ("multi-GB dumps until pg_dump itself starts failing") but does not fix the ordering. Under
  `pipefail`, a `pg_dump` failure at `:33` aborts the script before `:39`, leaving a partial
  `${DUMP_FILE}` that is never cleaned; a cron loop of failures accumulates GB files until the disk
  fills.
- **Fix approach:** Compute `DUMP_FILE` (already `:21`), `trap 'rm -f "${DUMP_FILE}"' EXIT`
  immediately after, *then* run the pipeline. The existing `:41` `[ ! -s "${DUMP_FILE}" ]` empty-file
  guard stays. Keep the comment, updated to say the trap now precedes the pipeline. Confirm the
  `aws s3 cp` success path still ends with the file removed by the trap (it does — the trap fires on
  normal exit too).
- **Ledger:** `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — "`pg-backup.sh`
  leaks a truncated dump on `pg_dump` failure … `[PICKED UP by skillars-deferred-97 AC1]`". Delete
  the bullet.
- **Test:** `bash -n` + `shellcheck deploy/backup/pg-backup.sh`. Manual dry-run: replace `pg_dump`
  with `false` in a scratch copy, run under `set -euo pipefail`, assert no `${DUMP_FILE}` remains.

---

### AC2: `restore-from-dump.sh` — bounded retry around the `APP_CID` capture

- **Task:** Wrap the single `docker compose … ps -q app` capture in a bounded retry loop (e.g. 5
  attempts, 2 s apart) so a slow container registration does not abort a restore whose integrity
  checks have already passed.
- **Verified at HEAD:** `deploy/backup/restore-from-dump.sh:200` —
  `APP_CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q app 2>/dev/null | head -1)`,
  single unretried call; `:201` comment already names the race. `:153` comment shows the empty/
  non-numeric capture is currently handled by failing with an "integrity check failed" message +
  `exit 1` — but `RESTORE_OK` (`:119`) is still `0` at that point, so the EXIT trap (`:121`) restarts
  the app and `rm -f "${LOCAL_DUMP}"` is skipped (dump leaks), for a restore that in fact succeeded.
- **Fix approach:** Retry loop around the `ps -q app` capture; on success proceed; only after the
  loop exhausts do the existing `err …; exit 1`. Do **not** set `RESTORE_OK=1` on the retry-exhausted
  path — a genuinely missing container is still a real failure — but emit a distinct diagnostic
  ("app container did not register within Ns after start; the restore data is intact, re-run the
  health wait manually") so the operator knows the DB restore itself completed. Keep the `:201`
  comment, reworded.
- **Ledger:** `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — "`restore-from-dump.sh`
  empty `APP_CID` fails a restore that already succeeded … `[PICKED UP by skillars-deferred-97 AC2]`",
  and the older `## Deferred from: code review of deploy-3-4-operational-documentation-suite` "APP_CID
  capture races container registration" bullet (same root cause, now fixed). Delete both.
- **Test:** `bash -n` + `shellcheck`. Dry-run: stub `docker` so `ps -q app` prints empty for 3 calls
  then a fake id; assert the loop proceeds and the trap-restart path is not taken.

---

### AC3: Pre-Volume migration verification — catch a torn same-size file

- **Task:** Add `--checksum` to the post-migration verification dry-run in
  `provision.sh:migrate_pre_volume_data` so a file that transferred with the right size but wrong
  content is detected before `${STAGING}` is deleted.
- **Verified at HEAD:** `deploy/provision.sh:399-440`. Current verification is **stronger than the
  ledger bullet describes**: `:402` does `rsync -aHAX`, then `:408` runs
  `rsync -aHAXni --numeric-ids "${STAGING}/" "${MOUNT_POINT}/"` — an itemized dry-run over the
  **whole** staged tree (`postgres/` included), failing (`:412-414`) if anything is still pending;
  then `:417-427` does `stat -c '%a %u:%g'` ownership/mode checks on `traefik/acme.json` and each
  armed dir. The one real residual: `rsync -ni` without `--checksum` compares size + mtime, so a
  torn write that lands at the expected size (SIGKILL / Volume `ENOSPC` mid-file) passes the dry-run.
- **Fix approach:** Change `:408` to `rsync -aHAXcni …` (`-c` = `--checksum`). This is a one-time
  provisioning step over a small tree — the extra hashing cost is irrelevant. The per-dir `stat`
  loop (`:423-427`) becomes belt-and-suspenders; keep it. Update the `:406-407` comment to state the
  dry-run is now content-level.
- **Ledger:** `## Deferred from: code review of skillars-deferred-87-…` — "Pre-Volume migration
  verify is stat-only on the armed paths; `postgres/` is migrated but never verified before
  `rm -rf "${STAGING}"`". The `postgres/`-not-verified half is already false at HEAD (the `-ni`
  dry-run covers it); reword the remaining bullet to the narrowed "no `--checksum`" concern if the
  fix is deferred, else delete it. **This story deletes it** (fix is trivial).
- **Test:** `bash -n` + `shellcheck`. Dry-run: create `${STAGING}/postgres/x` and a same-size
  different-content `${MOUNT_POINT}/postgres/x`; assert `rsync -aHAXcni` reports it and the function
  aborts leaving `${STAGING}` in place.

---

### AC4: Deploy smoke poll — no wasted trailing sleep, wider effective window

- **Task:** Skip the `sleep 5` on the final loop iteration and widen the smoke window so a
  slow-starting JVM is not reverted needlessly.
- **Verified at HEAD:** `.github/workflows/deploy.yml:96-113`. `sleep 60` (`:98`) then
  `for i in $(seq 1 12)` (`:100`) with `sleep 5` (`:110`) **unconditional** — iteration 12 checks,
  then sleeps 5 s pointlessly before the loop ends. Effective health window ≈ 60 s + 11×5 s + check
  latency ≈ ~120 s; a JVM needing longer is auto-reverted.
- **Fix approach:** Guard the sleep: `[ "$i" -lt 12 ] && sleep 5`. Bump the iteration count (e.g.
  `seq 1 24` at 5 s = ~180 s post-startup) — pick the number from `docker-compose.yml`'s `app`
  `start_period` + observed cold-start, and leave a comment with the arithmetic. Keep the
  `result=$RESULT` / `exit` contract (`:112-113`) and every downstream `if:` guard
  (`:132`, `:169`, `:220`) unchanged.
- **Ledger:** `## Deferred from: code review of skillars-deferred-96 (2026-09-07)` — "Deploy smoke
  poll window is effectively ~55s after the initial 60s wait … `[PICKED UP by skillars-deferred-97
  AC3]`". Delete the bullet.
- **Test:** `bash -n` on the extracted script body; re-read the loop and confirm the last iteration
  no longer sleeps. No CI harness runs `deploy.yml`; a follow-up real deploy is the live check.

---

### AC5: Deploy smoke — distinguish SSH/transport failure from an unhealthy app

- **Task:** A failed SSH connection (bad key, DNS, runner network) must **not** count as a smoke
  failure that triggers auto-revert. Separate "could not reach the host" from "host reached, app not
  UP".
- **Verified at HEAD:** `.github/workflows/deploy.yml:101-105`. The whole `ssh … "<remote script>"`
  is `2>/dev/null || echo 0` (`:105`), and the remote body's `wget` is also `2>/dev/null` (`:103`).
  Any transport error yields `STATUS=0`, identical to "app down". After 12 such iterations
  `RESULT=fail` → the `Auto-Revert on smoke test failure` step (`:130-132`) rolls back a deploy that
  may be perfectly healthy.
- **Fix approach:** Capture the `ssh` exit status separately from the remote script's stdout. On
  `ssh` exit 255 (its transport-failure code) increment a transport-failure counter and `continue`
  without counting it toward the health verdict; if every iteration is a transport failure, exit the
  smoke step with `result=error` (the workflow already handles `result == 'error'` distinctly at
  `:169` / `:198` / `:220` — route it so it fails the job **without** auto-reverting, i.e. the
  `Auto-Revert` step's `if:` at `:132` must not fire on `result == 'error'`; verify it currently
  keys only on `'fail'`). Add a short comment block explaining the three outcomes (pass / app-down /
  unreachable).
- **Ledger:** `## Deferred from: code review of skillars-deferred-96 (2026-09-07)` — "Deploy smoke
  SSH failures are swallowed to `echo 0` … `[PICKED UP by skillars-deferred-97 AC4]`". Delete the
  bullet.
- **Test:** `bash -n`. Re-read the revert step's `if:` and confirm `result == 'error'` does not
  reach it. Live check on the next real deploy.

---

### AC6: Provisioning — a dedicated non-root deploy user owns the checkout (per decision D3)

- **Task:** Change `provision.sh` / `runbook.md` so the Git checkout of the application repo is owned
  by a dedicated unprivileged **`deploy`** user, and the runtime data tree
  (`/opt/skillars/data/**` — PostgreSQL, Redis AOF, the LGTM stack, Traefik `acme.json`) lives
  **outside** the checkout root, so no `git clean` invoked from the checkout can reach it.
- **Verified at HEAD:** `deploy/provision.sh` clones/uses `${DEPLOY_ROOT}` = `/opt/skillars` as
  root; `${DEPLOY_ROOT}/data/**` is the Volume mount point (`:282`, `:332`, `MOUNT_POINT`); `.git`
  sits at `/opt/skillars/.git` beside it. `.gitignore` carries a `/data/` entry (from `deferred-94`),
  so `git clean -fd` is safe, but `git clean -fdx` (`-x` ignores `.gitignore`) still deletes the
  entire runtime tree. `deferred-94` AC8 added prose warnings only; `deferred-101` flagged this
  "needs a tracked deploy-hardening follow-up".
- **Fix approach (design — confirm details with the owner during implementation if any step is
  ambiguous):**
  - Create a system user `deploy` (no login shell needed for the deploy path, or a restricted one)
    in `provision.sh`; add it to the `docker` group.
  - Relocate the application checkout to a path the `deploy` user owns — either
    `/opt/skillars/app` (checkout) with `/opt/skillars/data` as a sibling the checkout can't `clean`,
    **or** `/srv/skillars` for the checkout with `/opt/skillars/data` unchanged. Prefer the option
    that moves the **least** — keeping `MOUNT_POINT` = `/opt/skillars/data` avoids touching every
    backup/restore script's path assumptions; moving only the checkout to `/opt/skillars/app` is the
    smaller blast radius. Update `docker-compose*.yml` working-dir / relative bind paths accordingly.
  - `chown -R deploy:deploy` the checkout; leave `data/**` owned by the service UIDs as today.
  - The deploy workflow's SSH user (`secrets.SSH_USER`) switches to `deploy`; `runbook.md` /
    `first-time-setup` updated; every `cd /opt/skillars` in `deploy/**` and the workflows updated to
    the new checkout path.
  - Document in `runbook.md` that `data/**` is deliberately not under the checkout and why.
- **Ledger:** `## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)`
  — "Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data" and
  its sibling "git clone root … contains the volume data subdirectory" bullet; plus
  `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — "Repo cloned as root
  directly into `/opt/skillars` … Needs a tracked deploy-hardening follow-up." Delete all three
  (this story is that follow-up).
- **Test:** `bash -n` + `shellcheck` all changed scripts. Dry-run the provisioning path on a scratch
  tree (fake `${DEPLOY_ROOT}`), assert: checkout owned by `deploy`, `data/**` not a child of the
  checkout, `git -C <checkout> clean -fdx` cannot touch `data/**`. Grep `deploy/**` + `.github/**`
  for stale `/opt/skillars` path assumptions after the move.

---

### AC7: Provisioning — the checkout must not precede the Volume mount

- **Task:** Order `provision.sh` so the application checkout (and any write into a path that will be
  overlaid by the Hetzner Volume) happens **after** the Volume is mounted, or is structured so the
  mount cannot hide freshly-written files.
- **Verified at HEAD:** `deploy/provision.sh` — section 6 (`:280-286`) `mkdir -p` the directory
  structure, section 7 mounts the Volume at `${MOUNT_POINT}`. The `:275-279` comment for the
  `acme.json` block already documents this exact class of bug ("Creating it from this position would
  write it to the ROOT DISK and section 7's mount would then hide it"). The repo checkout has the
  same exposure if it lands under `${DEPLOY_ROOT}` before the mount.
- **Fix approach:** After AC6 moves the checkout to its own path, confirm that path is **not** under
  `${MOUNT_POINT}`; if the chosen layout keeps anything writable under `${MOUNT_POINT}` pre-mount,
  move that write after the mount. Add an assertion in `provision.sh` right after the mount:
  `mountpoint -q "${MOUNT_POINT}"` (pattern already used at `:437`) before any step that depends on
  Volume-backed storage. Update the section-6/section-7 comments.
- **Ledger:** `## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)`
  — "Repo cloned to `/opt/skillars` before Hetzner Volume mounted". Delete the bullet.
- **Test:** Dry-run the reordered script against a scratch tree with a loopback block device as the
  fake Volume; assert no file written pre-mount is shadowed post-mount.

---

### AC8: Provisioning — single-Volume host with an unresolvable explicit `HETZNER_VOLUME_ID` must hard-fail

- **Task:** When exactly one `scsi-0HC_Volume_*` symlink exists **and** `HETZNER_VOLUME_ID` is set
  but does not resolve, fail hard (as the multi-Volume branch already does) instead of warning and
  falling back to the lone attached Volume.
- **Verified at HEAD:** `deploy/provision.sh:343-362`. `:344-345` use the id when it resolves;
  `:350-353` hard-fail when it doesn't resolve **and** `_vol_count > 1`; `:357` — the single-Volume
  case — only `err "… falling back to the single attached Volume / /dev/sdb."` and proceeds, and
  would `mkfs.ext4` it if unformatted. An operator who pinned a device by id and typo'd it is the
  case most likely to be on the wrong host.
- **Fix approach:** In the `HETZNER_VOLUME_ID` set-but-unresolvable branch, remove the single-Volume
  special-case fallback: `err` + `exit 1` regardless of `_vol_count`, with the message telling the
  operator to fix the id or unset it. Keep the "not set at all + single Volume" path
  (no `HETZNER_VOLUME_ID`) unchanged — that is the unambiguous case AC5 of `deferred-88` deliberately
  allows.
- **Ledger:** `## Deferred from: code review of skillars-deferred-88-… (2026-08-31)` — "AC5
  single-Volume + explicit-but-unresolvable `HETZNER_VOLUME_ID` still warns-then-falls-back". Delete
  the bullet.
- **Test:** `bash -n` + `shellcheck`. Dry-run with one fake `scsi-0HC_Volume_123` symlink and
  `HETZNER_VOLUME_ID=999`; assert `exit 1` and no `mkfs`.

---

### AC9: Provisioning — install AWS CLI v2 from the official installer, not Ubuntu-apt v1

- **Task:** Replace the `apt-get install … awscli` (v1) with the official AWS CLI v2 install
  (`awscli-exe-linux-<arch>.zip` → `./aws/install`), pinned to a specific v2 version.
- **Verified at HEAD:** `deploy/provision.sh:137` —
  `apt-get install -y curl git unzip jq rsync fail2ban ufw ca-certificates gnupg lsb-release awscli`.
  `unzip` is already installed on the same line, so the v2 installer's only prerequisite is present.
  `deploy/backup/*.sh` call `aws s3 cp` / `aws s3api …` — all v2-compatible.
- **Fix approach:** Drop `awscli` from the apt line. Add a dedicated block: download the versioned
  zip for the host arch (`$(uname -m)`), verify with the AWS-published GPG signature (their public
  key is documented), `unzip`, `./aws/install --update`, `aws --version` sanity check. Pin the
  version in a variable with a comment on how to bump it. Idempotent (`--update`).
- **Ledger:** `## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)`
  — "awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage".
  Delete the bullet.
- **Test:** `bash -n` + `shellcheck`. Dry-run the block with a stubbed downloader; assert it is
  idempotent and `aws --version` reports v2.

---

### AC10: `stripe_webhook_events.event_id` — bound the primary-key `VARCHAR`

- **Task:** New Flyway migration giving `payment.stripe_webhook_events.event_id` a bounded length
  (`VARCHAR(255)` — Stripe event ids are `evt_` + 24 base62 ≈ 28 chars today; 255 is generous and
  matches the codebase's other id columns). It is the PK, so an unbounded text PK is both a B-tree
  footgun and unusual.
- **Verified at HEAD:** `src/main/resources/db/migration/V61__payment_module_init.sql:22` —
  `event_id VARCHAR NOT NULL`, `:25` — `CONSTRAINT pk_stripe_webhook_events PRIMARY KEY (event_id)`.
  Confirm the JPA entity's field length annotation matches after the change
  (`grep -rn "class StripeWebhookEvent" src/main/java`).
- **Fix approach:** `ALTER TABLE payment.stripe_webhook_events ALTER COLUMN event_id TYPE VARCHAR(255);`
  in a new `V13x__…` migration. Follow `docs/deployment/migration-conventions.md` — an `ALTER COLUMN
  … TYPE` that only **adds** a length bound and shrinks nothing is a metadata-only change in
  PostgreSQL when the new limit is ≥ every existing value **and** ≥ the old (unbounded) declared
  type only re-scans if narrowing; a `USING` clause is not needed. At current row counts this is not
  a lock concern, but add the `MISSING_LOCK_TIMEOUT` / rebaseline note per the conventions doc if the
  lint requires it (`V128+` binding). Set `@Column(length = 255)` on the entity field.
- **Ledger:** `## Deferred from: code review of skillars-7-1-stripe-connect-onboarding-commission-engine
  (2026-06-24)` — "D3: Unbounded `VARCHAR` on `stripe_webhook_events.event_id`". Delete the D3
  bullet (D4 stays — see **Items examined and NOT folded in**).
- **Test:** `@Testcontainers` IT: existing webhook-idempotency IT still green (it inserts/reads
  `event_id`). `MigrationLint` / `MigrationConventionTest` passes. `mvn -o test-compile`.

---

### AC11: Revenue running balance — a prior-page `createdAt`-twin must not be dropped from the opening balance

- **Task:** Fix `RevenueReportingService`'s running-balance pagination anchor so two
  `ParentCreditLedger` rows with an identical `createdAt` instant straddling a page boundary do not
  understate the current page's running balance by the excluded twin's amount.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java:233`
  — `BigDecimal openingBalance = parentCreditLedgerRepository.sumByParentIdAndCreatedAtBefore(parentId, oldest.getCreatedAt());`
  then `:238` `BigDecimal balance = openingBalance;` and the per-row accumulation follows. The
  strict-`<` predicate in `sumByParentIdAndCreatedAtBefore` excludes any row whose `createdAt`
  equals `oldest.getCreatedAt()` — including a twin that sorts onto the previous page. Rare, but
  exact when it happens.
- **Fix approach:** The page is ordered by `(createdAt, <tiebreaker>)`. The opening balance must sum
  every row that sorts **before the first row of this page**, which means "`createdAt <
  oldest.createdAt`" **plus** "`createdAt = oldest.createdAt` **and** tiebreaker `< oldest.<tiebreaker>`".
  Identify the actual sort tiebreaker used by the page query (likely `id` or a sequence column);
  add a repository method `sumByParentIdBeforeAnchor(parentId, createdAt, tiebreaker)` with the
  compound predicate, and call it from `:233`. If the page query has **no** deterministic tiebreaker,
  that is the real bug — add one (`ORDER BY created_at, id`) and make the opening-balance sum match.
- **Ledger:** `## Deferred from: code review of skillars-7-5-revenue-dashboard-financial-reporting
  (2026-06-26)` — "D1: Running balance incorrect when two ParentCreditLedger entries share an
  identical createdAt instant and straddle a page boundary". Delete the bullet.
- **Test:** Service-layer / `@Testcontainers` IT: seed two ledger rows with identical `createdAt`,
  page size 1 so they straddle; assert page 2's opening balance includes page 1's twin. Mutation:
  revert to the strict-`<` call → assertion fails.

---

### AC12: `SessionPlanService` — replace scattered `"COMPLETED"` / `"CANCELLED"` string literals

- **Task:** Introduce a single source of truth for session status values and use it everywhere
  `SessionPlanService` currently compares/sets a bare `"COMPLETED"` / `"CANCELLED"` string.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
  — `:128` `if ("COMPLETED".equals(session.getStatus()) || "CANCELLED".equals(session.getStatus()))`,
  `:170` `if (!"COMPLETED".equals(session.getStatus()))`, `:171` `session.setStatus("COMPLETED")`,
  `:197` `if ("COMPLETED".equals(event.newStatus()) …`. `Session.status` is a `String` column
  (raw-string status is the codebase convention — see `deferred-work.md` `skillars-7-2` D4 for the
  same pattern in payment, left as-is by cost). This AC does **not** migrate the column to an enum —
  it removes the *magic strings* in this one service.
- **Fix approach:** Add `public final class SessionStatus { public static final String COMPLETED =
  "COMPLETED"; public static final String CANCELLED = "CANCELLED"; … private SessionStatus(){} }` in
  the `session` module's `contract` (or wherever `Session` lives), covering the full set the column
  can hold (grep `setStatus(` / `.getStatus()` across `session/**` for the complete list). Replace
  the four literals. Do **not** chase every other module in this story — scope is `SessionPlanService`
  (the ledger bullet's cited file class), but if `SessionCompletionDataRepository` still carries a
  JPQL `'COMPLETED'` literal (the original W1 citation), swap it to a bound parameter fed from the
  constant in the same pass.
- **Ledger:** `## Deferred from: code review of skillars-3-6-session-completion-live-mode-quick-complete
  (2026-06-16)` — "W1: JPQL string literal `'COMPLETED'` in `findPendingQuickCompletes` is fragile
  against `BookingStatus` enum rename". Delete the W1 bullet.
- **Test:** `mvn -o test-compile` + targeted `mvn -o test -Dtest=SessionPlanServiceTest,SessionPlanServiceIT`
  (whichever exist) — behaviour unchanged, so all existing session-plan tests stay green. No new
  behaviour to assert; the value is compile-time safety.

---

### AC13: `DrillRepository` — explicit `library_type = 'COACH'` filter on the private-drill queries

- **Task:** Add an explicit `library_type = 'COACH'` predicate to the repository methods that list a
  coach's private drills, as defense-in-depth rather than relying solely on the `chk_drill_owner` DB
  constraint.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/session/repo/DrillRepository.java:19`
  — `List<Drill> findByOwnerCoachIdAndStatus(UUID ownerCoachId, String status);` (derived query, no
  `library_type` clause). `:17` `findByLibraryTypeAndStatus(String, String)` is the PLATFORM-list
  counterpart. The safety today rests entirely on `chk_drill_owner` (`V38`/later) forbidding a
  PLATFORM drill from carrying a non-null `owner_coach_id`.
- **Fix approach:** Either rename to `findByOwnerCoachIdAndLibraryTypeAndStatus` and pass
  `"COACH"` at the call sites, or convert to an explicit `@Query("… WHERE d.ownerCoachId = :coachId
  AND d.libraryType = 'COACH' AND d.status = :status")` (use the AC12 `LibraryType`-style constant if
  one exists after that AC, otherwise a bound param). Update `DrillLibraryService` call sites. Keep
  the method's return contract identical.
- **Ledger:** `## Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)` —
  "D7: `listPrivateDrills` no explicit `library_type = 'COACH'` filter". Delete the D7 bullet.
- **Test:** `@Testcontainers` `DrillRepositoryIT` / `DrillLibraryServiceIT`: seed a COACH drill and
  (bypassing the constraint via direct SQL, or asserting the query text) confirm the private list
  returns only `library_type = 'COACH'` rows. `mvn -o test -Dtest=DrillLibraryServiceIT`.

---

### AC14: `MessagingService.getConversations` — re-measure the N+1 and either batch it or pin the cost

- **Task:** Determine the actual per-request query count of `getConversations` at HEAD; if there is a
  residual per-row lookup in `toSummary` / `buildSummaryContext`, batch it; if the cost is already
  constant, add a query-count IT as the regression guard and close the ledger item as measured.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java`
  — `getConversations` (`:105`) already batches: the age-policy lookup is a single
  `agePolicyService.findMessagingPoliciesByPlayerIds(…)` (`:116`, from `deferred-90` AC13), and
  `buildSummaryContext` (`:~424-449`) does `coachProfileRepository.findAllById(coachIds)`,
  `findLatestApprovedPerConversation(conversationIds)`, `countUnreadPerConversation(conversationIds, …)`.
  The remaining question is whether `toSummary` (`:~164`) does anything per-row (player name? last
  message body?) that escapes the context. `deferred-101`'s audit still lists `skillars-8-1` D2 as
  "real, open (MVP-volume perf tradeoff, explicitly parked)".
- **Fix approach:** Read `toSummary` and `buildSummaryContext` in full. If a per-row repository call
  remains, add it to `SummaryContext` as a batched map (mirror the existing `findAllById` pattern).
  Whatever the outcome, add `MessagingConversationsQueryCountIT` in the style of
  `CoachPublicProfileQueryCountIT` (`deferred-91` AC11) — seed N conversations, assert the JDBC
  round-trip count is **constant** as N doubles. If a genuine batch was added, the IT fails without
  it (mutation-sensitive by construction).
- **Ledger:** the `skillars-8-1` D2 line — currently only referenced in audit-block prose (the
  standalone bullet was pruned long ago). If a standalone `skillars-8-1` bullet still exists, delete
  it; otherwise add one line to the `deferred-101` audit block's "Items re-verified still open" list
  noting D2 is now closed/measured by `deferred-102` AC14.
- **Test:** `MessagingConversationsQueryCountIT` (new). `mvn -o test -Dtest=MessagingConversationsQueryCountIT,AgeTierTransitionTest`.

---

### AC15: `VideoApprovalRequest` — verify the `UUID` PK generation strategy under Hibernate 6

- **Task:** Confirm `@GeneratedValue(strategy = AUTO)` on `VideoApprovalRequest`'s `UUID` id resolves
  to the SQL `gen_random_uuid()` column default (not a Hibernate sequence/UUID generator that would
  diverge from every other UUID entity in the codebase). Pin the strategy explicitly if the resolved
  behaviour is not the SQL default.
- **Verified at HEAD:** `grep -rn "class VideoApprovalRequest" src/main/java` then read the `@Id` /
  `@GeneratedValue` / `@Column` annotations and the matching migration's `id UUID … DEFAULT
  gen_random_uuid()`. Compare with a known-good sibling (e.g. `Dispute`, `ReconciliationIncident` —
  whichever uses `UUID DEFAULT gen_random_uuid()` + the codebase's standard annotation).
- **Fix approach:** If the sibling pattern is `@GeneratedValue(strategy = GenerationType.UUID)` or a
  bare `@Id` with insert relying on the DB default + `@Generated`/`insertable=false`, make
  `VideoApprovalRequest` match it. If `AUTO` already resolves identically on `postgres:17` +
  Hibernate 6.x (it may — `AUTO` → `UUIDGenerator` for `UUID` types since Hibernate 6), the fix is a
  one-line annotation change to remove the ambiguity plus a comment; no data migration.
- **Ledger:** `## Deferred from: code review of skillars-6-6-player-video-management-portal
  (2026-06-24)` — "W5: `@GeneratedValue(AUTO)` on `VideoApprovalRequest` entity vs `UUID DEFAULT
  gen_random_uuid()` in SQL". Delete the W5 bullet.
- **Test:** `@Testcontainers` IT that persists a `VideoApprovalRequest` via the repository and
  asserts the row's `id` is a valid v4 UUID and round-trips; if an approval-request IT already
  exists, extend it. `mvn -o test -Dtest=VideoApproval*IT`.

---

### AC16: Theme toggle — guard against rapid double-click DOM/`darkMode` desync

- **Task:** Make the theme toggle idempotent under a rapid double-click so the persisted DOM
  `data-theme` (or `body` class) attribute and the `darkMode` ref cannot end up disagreeing.
- **Verified at HEAD:** Find the toggle — `grep -rn "darkMode\|toggleTheme\|data-theme\|prefers-color-scheme"
  src/frontend/src` (per the memory note `project_skillars_ui.md`, the design system is
  dual dark/light glassmorphism with a token-driven theme). The `skillars-1-2` W6 bullet:
  "`toggleTheme` is synchronous so window is negligible in practice; acceptable" — re-verify it is
  still synchronous and still writes both the ref and the DOM attribute in one call.
- **Fix approach:** If `toggleTheme` derives the next state from the **current DOM attribute** rather
  than from the ref (or vice-versa) and the two writes are not in the same synchronous tick, unify
  them: compute next state from a single source (the ref), write the ref, then reflect it to the DOM
  in the same function (or via a single `watch(darkMode, …, { flush: 'sync' })`). Add a guard so a
  second invocation before the first's `watch` flush is a no-op or coalesces. Keep the Quasar
  `$q.dark` integration (if used) consistent.
- **Ledger:** `## Deferred from: code review of skillars-1-2-skillars-design-system-foundation
  (2026-06-11)` — "W6: Rapid double-click theme toggle can briefly desync DOM attribute and
  `darkMode` ref". Delete the W6 bullet.
- **Test:** No frontend test runner exists (`frontend-test-framework-initiative` backlog).
  Verification is `eslint` + `quasar build` + a manual double-click in `quasar dev` confirming the
  attribute and the ref agree after the dust settles. Record the manual check in the Dev Agent
  Record.

---

### AC17: `booking.store.js` — bound `batchAcceptResultsByBatch` growth during a failed-refresh streak

- **Task:** Cap or prune the `batchAcceptResultsByBatch` map so a run of failed
  `loadCoachBookingRequests` calls cannot let it grow unbounded.
- **Verified at HEAD:** `src/frontend/src/stores/booking.store.js` — `grep -n "batchAcceptResultsByBatch"`.
  The `skillars-deferred-37` bullet: pruning "only runs on `loadCoachBookingRequests`'s success path,
  per AC1's own explicit requirement mirroring the function's existing stale-on-failure CONTRACT — so
  a streak of failed refreshes lets the map keep growing unboundedly". Confirm the prune is still
  success-path-only and the map is still keyed by batch id with no eviction.
- **Fix approach:** Add a bound that does not depend on a successful refresh: either (a) an LRU-style
  cap (keep the N most-recently-written batch ids, N generous — e.g. 200), evicting on every
  **write** regardless of refresh outcome; or (b) a TTL: drop entries older than a few minutes on
  each access. (a) is simpler and matches the "these are transient accept-result annotations" intent.
  Preserve the documented stale-on-failure contract for entries that are still within the cap.
- **Ledger:** `## Deferred from: code review of skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound
  (2026-08-19)` — the whole bullet. Delete it.
- **Test:** `eslint` + `quasar build`. Manual: in `quasar dev`, force `loadCoachBookingRequests` to
  reject repeatedly (devtools network block) and confirm the map size plateaus. Record in the Dev
  Agent Record. (A real unit test is one of the gaps `frontend-test-framework-initiative` unblocks —
  note it there.)

---

### AC18: `openpdf` 1.3.43 → 3.0.5 — migrate `ReportGenerationService` and merge Dependabot #141

- **Task:** Migrate `ReportGenerationService.java` from `com.lowagie.text.*` to openpdf 3.x's
  `org.openpdf.text.*` package, bump the dependency in `pom.xml`, verify PDF report output is
  byte-comparable / visually identical, and merge Dependabot PR **#141**.
- **Verified at HEAD:** `pom.xml:484-485` — `com.github.librepdf:openpdf` (version property
  elsewhere; currently 1.3.43). `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java:3-12`
  — 12 imports: `com.lowagie.text.{Document,Font,FontFactory,Image,PageSize,Paragraph,Phrase}` and
  `com.lowagie.text.pdf.{PdfPCell,PdfPTable,PdfWriter}`. Dependabot #141's CI run failed with
  `package com.lowagie.text does not exist` (10 compile errors, all in this one file). openpdf 3.x
  moved the group to `org.openpdf:openpdf` and the packages to `org.openpdf.text.*` (the `com.lowagie`
  Java package was retired; some 3.x lines kept a `com.github.librepdf` groupId — confirm the exact
  coordinates from the #141 diff and the openpdf 3.0.5 release notes during implementation).
- **Fix approach:**
  - Take the `pom.xml` change from Dependabot #141 (groupId/artifactId/version as #141 sets them).
  - Rewrite the 12 imports to the 3.x package names. openpdf 3.x is API-compatible with iText 2.x /
    openpdf 1.x at the class level — `Document`, `PdfWriter.getInstance`, `PdfPTable`, `PdfPCell`,
    `FontFactory`, `Image.getInstance`, `PageSize` all keep their signatures; only the package
    changed. Compile-check for any method that *was* removed in 3.x and adjust.
  - openpdf 3.x requires Java 17+ — the project is on JDK 17 (`_bmad/bmm/config.yaml` context / the
    CI `Set up JDK 17` step), so no toolchain change.
  - After the branch is green, merge Dependabot #141 (or push the equivalent change and close #141
    with a note pointing at this story).
- **Ledger:** no `deferred-work.md` bullet (the openpdf bump is a Dependabot item, not a ledger
  entry) — instead add a one-line note to the story's Completion Notes and, if a "Dependency
  upgrades" section exists in the ledger, record it there; otherwise nothing to delete.
- **Test:** `mvn -o test-compile` (the whole point — must compile). Targeted
  `mvn -o test -Dtest=ReportGenerationServiceTest` + any `PerformanceReport*IT`. Generate a report
  PDF locally before and after and diff the extracted text (`pdftotext`) — content must be
  identical; a pixel diff of page 1 is a bonus. Record the comparison in the Dev Agent Record.

---

### AC19: Ledger hygiene — delete closed bullets, record the decisions

- **Task:** In `deferred-work.md`, delete every bullet closed by AC1–AC18 (each AC names its bullet),
  per the file's delete-outright convention (no `[CLOSED by …]` tag). Add a
  `## Last audit: 2026-09-08 (skillars-deferred-102 story creation + implementation)` block that
  lists exactly what was removed, carries the line-for-line **Reconstruction check** statement the
  file's convention requires, and records:
  - **Decision D2** — completion-gated coach payout stays its own future story; the
    `deferred-91` AC5 Part B bullet is untouched.
  - **Decision D3** — `deploy-1-5` clone-as-root closed via a dedicated non-root deploy user (AC6),
    not "accept + document".
  - **`skillars-deferred-97`** is superseded by this story — set its `sprint-status.yaml` entry to
    `withdrawn` with a comment pointing here.
- **Verified at HEAD:** the bullets named by AC1–AC18 all exist in `deferred-work.md` @ `31982170`
  (re-confirm each citation immediately before deleting — line numbers will have drifted).
- **Do NOT touch:** any `[DECIDED]` / `[DISMISSED]` / `[PICKED UP]` bullet, and every item in
  **Items examined and NOT folded in** below.
- **Ledger:** n/a (this AC *is* the ledger work).
- **Test:** `git diff deferred-work.md` reviewed against this AC's list; the Reconstruction check
  statement is literally true (every surviving non-blank line matches the pre-edit file, in order).

---

## Items examined and NOT folded in (recorded so the next audit does not re-litigate)

- **Completion-gated coach payout — `deferred-91` AC5 Part B.** Its own story (decision D2). Needs
  `docs/architecture/payout-and-capture-pending.md` D1–D5 answered and the doc promoted out of
  `DRAFT` first. Payment-architecture change (destination charges → separate charges & transfers,
  `coach_payouts` table, `COACH_PAYOUT_TRANSFER` outbox handler). Bullet untouched.
- **`frontend-test-framework-initiative`** (backlog story). Standing up Vitest + Vue Test Utils is a
  standalone setup task, not this story's scope; AC16/AC17 note their unblockable unit tests there.
- **Pre-production migration rebaseline** (`deferred-101` future task, no owner). Squash `V1..V<n>`
  before the first production deploy. Large, disruptive, pre-data only. Untouched.
- **`main.pending_blob_deletions` table + entity/repo + `PendingBlobDeletionResidualDrainRunner`
  drop** (`deferred-100` AC6 follow-up). A `DROP TABLE` in a `> V131` migration, only once
  `deferred-100` is confirmed deployed and the table is provably empty everywhere. Untouched.
- **`skillars-7-1` D4** — `acceptBooking` fires `INITIATE_PAYMENT` without a real capture. **Stale as
  written** — the payment lifecycle has been rebuilt since Story 7.1 (`acceptAndInitiatePayment`
  moves `REQUESTED → PAYMENT_PENDING`; `PaymentLifecycleService` / `BookingPaymentPersistenceService`
  / `reserveCapture` / `CAPTURE_PENDING` sweepers own capture, from `uat-3` / `deferred-91`). Left
  in the ledger, but re-word it to the current architecture in a future audit rather than treating
  the 7.1-era text as an open bug.
- **`deploy-3-3` Alertmanager double-notification** — design-deferred (Alertmanager is not deployed;
  `deferred-94` AC4 added the design-gate comment). No code change until Alertmanager is on the
  roadmap.
- **`deploy-3-3` node_exporter reachable from `app` / `grafana`** — narrowed by `deferred-88` AC8;
  the residual (`app`/`grafana` join both networks by design for egress) is an accepted structural
  tradeoff.
- **`deploy-3-1` credentials in `/proc/<pid>/environ`** — project-wide, not deploy-specific; needs a
  secrets-management initiative, not a script patch.
- **`skillars-deferred-87` CI `:latest` freeze after a `master` history rewrite** — the
  `workflow_dispatch` force-publish escape hatch **already exists** at HEAD
  (`.github/workflows/ci.yml:12-16`, `:164-167`, master-ref-gated) — closed by `deferred-88` AC6.
  The automatic ancestor check not self-recovering from a history rewrite is the documented,
  accepted fail-safe design. Nothing to do; delete the bullet as stale in AC19 if it is still
  present.
- **`deploy-1-3` LGTM `mkdir -p` gated inside `[ -b ]`** — at HEAD `${DEPLOY_ROOT}/lgtm` and
  `data/postgres` are created **unconditionally** in `provision.sh` section 6 (`:281-283`); the
  gated-inside-`[ -b ]` premise appears stale/narrowed. Not folded in — flag for the next deploy
  audit to confirm and delete, rather than change code on an unverified premise.
- **All `[DECIDED]` (10) / `[DISMISSED]` (24) bullets** — kept by design so the decision is not
  re-litigated. Includes the SLU bucket (essentially all `[DISMISSED 2026-08-30]`), `skillars-11-1`
  D1–D9 (legacy-mirrored by AC4 of that story), `skillars-deferred-49` overnight-window / DST items,
  `skillars-deferred-63` two-sided disputes, `skillars-uat-6` shared `stripe_customers` row.
- **Speculative / load-dependent** — `reserveCapture` / `persistPaymentFailure` second-connection
  cost, `PessimisticLockRetryer` `Supplier` contract, `skillars-uat-3` D11 sweeper-lock-without-a-test.
  Measure under real load before touching.
- **"No dev agent can close"** — de-DE / fr-FR native register review, parent legal copy
  (ToS/privacy/consent) in de-DE + fr-FR. Needs a human native speaker / legal review.

---

## Dev Notes

### Sequencing / blast radius

- **AC6 + AC7 are the highest-risk changes** and touch the most files (provisioning + every
  `cd /opt/skillars` in `deploy/**` and `.github/workflows/*.yml`). Do them **first** on the branch,
  in their own commits, and grep-sweep for stale path assumptions before moving on. If the owner
  wants the checkout path kept at `/opt/skillars` and only ownership changed, that is a smaller
  variant — confirm before writing.
- **AC1–AC5, AC8, AC9** are small, independent shell edits — one commit each, `shellcheck` clean.
- **AC10** is the only schema change — new Flyway migration, follows
  `docs/deployment/migration-conventions.md`.
- **AC18** must land before any `mvn` step can pass on the branch (the build currently fails on
  Dependabot #141's tree) — but #141 is a *separate* branch; this story's branch is unaffected until
  you take the `pom.xml` bump. Take the bump + import migration in one commit so the branch never has
  a broken compile.
- **AC11–AC17** are independent; group by module in commits.
- **AC19** is last.

### Files in play (indicative — re-verify at implementation time)

| Area | Files |
|------|-------|
| Backup/restore | `deploy/backup/pg-backup.sh`, `deploy/backup/restore-from-dump.sh`, `deploy/provision.sh` (`migrate_pre_volume_data`) |
| Deploy workflow | `.github/workflows/deploy.yml` |
| Provisioning | `deploy/provision.sh`, `docs/deployment/runbook.md`, `docs/deployment/first-time-setup*.md`, `docker-compose.yml` / `docker-compose.local.yml` (if the checkout path moves) |
| DB | `src/main/resources/db/migration/V13x__stripe_webhook_event_id_length.sql` (new), `StripeWebhookEvent.java` |
| Payment reporting | `RevenueReportingService.java`, `ParentCreditLedgerRepository.java` |
| Session status | `SessionPlanService.java`, new `SessionStatus` constants class, `SessionCompletionDataRepository.java` |
| Drills | `DrillRepository.java`, `DrillLibraryService.java` |
| Messaging | `MessagingService.java`, new `MessagingConversationsQueryCountIT.java` |
| Video | `VideoApprovalRequest.java` (+ its migration for reference), video-approval IT |
| Frontend | theme-toggle composable / layout (`grep darkMode`), `src/frontend/src/stores/booking.store.js` |
| Dependency | `pom.xml`, `ReportGenerationService.java` |
| Ledger | `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml` |

### Testing standards (from `_bmad-output/project-context.md`)

- Backend: `@Testcontainers` ITs against `postgres:17-alpine`, Instancio for fixtures, AssertJ
  assertions, MapStruct mappers, records for DTOs, `@PreAuthorize` on every endpoint. **GitHub CI is
  the full-verification gate** — locally run only `mvn -o test-compile` + targeted `-Dtest=`.
- Migrations: Flyway only, follow `docs/deployment/migration-conventions.md`;
  `MigrationConventionTest` / `MigrationLint` must stay green.
- Shell: no CI executes `deploy/**` — `bash -n`, `shellcheck`, and a written dry-run walkthrough are
  the bar. Each shell AC's **Test** block names the walkthrough.
- Frontend: no unit runner — `eslint` + `quasar build` + a recorded manual check in `quasar dev`.

### Project Structure Notes

- Module boundaries per the memory notes: file/image storage always via the `filestorage` module
  (never `infrastructure.blobstore` directly); the `session` schema name is a PG non-reserved
  keyword (known, `skillars-4-1` D1, not touched here).
- New `SessionStatus` constants belong beside `Session` in the `session` module, not in a shared
  `common` package (matches how `BookingStatus` / other status sets are scoped).

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — every AC's **Ledger** line,
  re-verified against `master` @ `31982170` on 2026-09-08.
- [Source: `docs/deployment/migration-conventions.md`] — AC10 lock-safe pattern + `MigrationLint`
  binding.
- [Source: `docs/deployment/runbook.md`, `docs/deployment/first-time-setup*.md`] — AC6/AC7 operator
  docs to update.
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-100-…md`,
  `skillars-deferred-101-…md`] — prior-story format, the "genuine one-off bugs exhausted" finding,
  and the `deferred-101` audit block this story's AC19 block continues.
- [Source: Dependabot PR #141 + openpdf 3.0.5 release notes] — AC18 coordinates and package rename.

---

## Dev Agent Record

### Agent Model Used

_(to be filled by the dev agent)_

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

| Date | Change |
|------|--------|
| 2026-09-08 | Story created from `deferred-work.md` @ `31982170`. 19 ACs across backup/restore resilience, deploy-smoke, provisioning hardening, one schema change, and cross-module correctness (payment reporting, session-status constants, drills, messaging perf, video entity, two frontend, openpdf 3.x). Absorbs `skillars-deferred-97`. Project-owner decisions D1–D4 captured. Status: ready-for-dev. |
