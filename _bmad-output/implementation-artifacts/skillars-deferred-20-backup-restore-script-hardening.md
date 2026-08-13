# Story Deferred-20: Backup & Restore Script Defensive Guards

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator running the Skillars backup/restore scripts on the production VPS,
I want `/opt/skillars/.env` sourcing failures and partial volume-backup archives to fail with a clear, tagged error instead of a bare shell error or a mid-restore crash with services already stopped,
so that a broken cron run is diagnosable from `/var/log/skillars-backup.log` alone without SSHing in, and a bad restore attempt fails with an unambiguous, correctly-tagged message instead of a raw shell error.

Note the two halves apply to different scripts for different reasons: `pg-backup.sh` and `volume-backup.sh` are the only two scripts with a cron entry (`install-crons.sh:11-12`, `PG_CRON`/`VOLUME_CRON`) — for these, "diagnosable from the log alone" is the real motivation, since nobody is watching the terminal when cron fires. `restore-from-volume-backup.sh` and `restore-from-dump.sh` have no cron entry and are only ever run by an operator already at an interactive terminal (`restore-from-volume-backup.sh` even blocks on a `read -r` confirmation prompt before doing anything) — for these two, the guard is about a correctly-tagged, unambiguous error and family-wide consistency, not headless-log diagnosis.

### Why this story exists

Drawn from `deferred-work.md`'s **"Deferred from: code review of `skillars-uat-6-coach-subscription-and-volume-backup`"** section (2026-08-13) — 8 items the `uat-6` code review recorded and explicitly did not fix. Of those 8, 5 are deliberate design decisions or exact mirrors of a pre-existing, non-incident gap elsewhere in the same script family (see "Explicitly NOT fixed" below) — this story does **not** touch them. The remaining 3 are genuine defensive gaps with an established in-repo pattern to mirror, and are cheap enough to fix in one pass. No UAT-readiness item remains unclaimed in `uat-readiness-priorities.md` (P0/P1/P2 are fully claimed by `skillars-uat-1` through `skillars-uat-6`; only P3 — explicitly "do not touch for UAT" — remains there), so this story is sourced directly from `deferred-work.md` instead, per Mbah's direction.

**A fourth item was found during this story's creation, not in the ledger**: `restore-from-dump.sh` sources `/opt/skillars/.env` with the exact same unguarded pattern the ledger flagged in `volume-backup.sh`/`restore-from-volume-backup.sh`. Fixing the two ledger-named scripts while leaving this sibling (and `pg-backup.sh`, the script the ledger explicitly compared them to) unguarded would create a new, avoidable inconsistency across the backup script family — the same trap prior stories in this codebase have repeatedly called out (see `skillars-uat-4`'s i18n sweep, `skillars-uat-2`'s "fixing this page alone would create a new cross-page inconsistency"). All four scripts that source `/opt/skillars/.env` are fixed together.

## Acceptance Criteria

1. **Guarded `.env` sourcing across the whole backup/restore script family.** `pg-backup.sh`, `volume-backup.sh`, `restore-from-volume-backup.sh`, and `restore-from-dump.sh` each check `/opt/skillars/.env` is readable *before* sourcing it, and exit 1 with the script's own `[<script-name>][error]`-prefixed message if it is not — mirroring the exact guard `prune-backups.sh` already has (`prune-backups.sh:35-38`), placed in the exact same position relative to the `# shellcheck source=/dev/null` directive (see Task 1 and Dev Notes — that comment must stay immediately adjacent to the `.` line it annotates, or shellcheck's SC1091 re-fires). In `restore-from-volume-backup.sh` specifically, the guard must call the script's own `err()` helper, which means `err()`/`log()` must be defined *before* the guard runs — today they are defined after the `.env` source line, so a naive "insert the guard right above the source line" edit calls an undefined function (see Task 1). Today a missing/unreadable `.env` on any of these four fails via bare `set -e` with no tag, which is invisible when grepping `/var/log/skillars-backup.log` for `[error]`. Closes `deferred-work.md`'s uat-6-code-review items *"`volume-backup.sh` sources `/opt/skillars/.env` with no readability guard"* and *"`restore-from-volume-backup.sh` has the same untagged-`.env`-failure gap"*, plus the `restore-from-dump.sh` instance of the same gap found during this story's creation.

2. **`restore-from-volume-backup.sh`'s post-extraction ownership fixups only touch subdirectories that actually exist**, mirroring the existence check the same script already uses one section earlier when moving pre-restore directories aside (`restore-from-volume-backup.sh:63-67`, `if [ -d "${DATA_DIR}/${d}" ]`). Today the `chown -R`/`chmod` block at lines 75-81 runs unconditionally against `redis`, `prometheus`, `loki`, `tempo`, `grafana`, `traefik` — a `chown -R` on a directory that is not in a given archive (e.g. a service added to `VOLUME_SUBDIRS` after some backups were already taken, or a directory legitimately absent because a service was never provisioned) exits non-zero via `set -e` **after** `docker compose down` (line 43) has already stopped every service, and the script's own `restore_failed` ERR trap (lines 48-52) only restarts the stack with whatever is currently on disk — it does not retry or complete the ownership fixups for the directories that *did* extract successfully. Closes the ledger's *"`chown -R` calls assume every extracted archive has all expected subdirectories"* item.

3. **Ledger hygiene in `deferred-work.md`.** Annotate all 8 items under *"Deferred from: code review of `skillars-uat-6-coach-subscription-and-volume-backup` (2026-08-13)"*:
   - 2 items → `[CLOSED by skillars-deferred-20 AC1]` (the two `.env`-guard items)
   - 1 item → `[CLOSED by skillars-deferred-20 AC2]` (the `chown` subdirectory item)
   - 5 items → `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20]`, each with a one-line reason already present in the existing entry text (see "Explicitly NOT fixed" below) — so a future ledger read does not re-open them as ambiguously-still-open follow-ups.

## Tasks / Subtasks

- [x] Task 1 — Env-file readability guard (AC: #1)
  - [x] **Placement rule for all four scripts, no exceptions:** each `.env` source line is preceded by a `# shellcheck source=/dev/null` comment that must stay on the line *immediately* above the `.` command (shellcheck requires directive adjacency; inserting anything between the comment and the source line re-triggers SC1091 and fails the "must be shellcheck clean" bar). So the guard block goes **before the `# shellcheck source=/dev/null` comment**, never between the comment and the source line — this is exactly how `prune-backups.sh` is already structured (`prune-backups.sh:35-40`: guard block, *then* the comment, *then* the source line — copy that ordering verbatim, not just the guard's contents).
  - [x] `pg-backup.sh`: insert the guard before the `# shellcheck source=/dev/null` comment (currently line 6), tagged `[pg-backup][error]`
  - [x] `volume-backup.sh`: insert the guard before the `# shellcheck source=/dev/null` comment (currently line 9), tagged `[volume-backup][error]` — this file already has a `[volume-backup][error]`-tagged check pattern at lines 23-26 to copy the message style from
  - [x] `restore-from-volume-backup.sh` — **two changes, in order, not one:**
    1. Move the `log()`/`err()` helper definitions (currently lines 22-23, well after the `.env` source line) up to immediately after `set -euo pipefail` (currently line 2), so they exist before anything that might need them.
    2. Insert the guard before the `# shellcheck source=/dev/null` comment (currently line 3, after the move), tagged `[restore-from-volume-backup][error]`, calling `err "cannot read /opt/skillars/.env — restore cannot run without credentials"; exit 1`.

    Do **not** just copy the guard above the source line as in the other three scripts — at that point in the file, as it exists today, `err()` is not yet defined, and the script would die with an untagged `line 9: err: command not found` (exit 127) instead of the intended tagged error. Verify this by testing with `.env` temporarily renamed away *after* making the change: the failure must show `[restore-from-volume-backup][error] cannot read...`, not `command not found`.
  - [x] `restore-from-dump.sh`: insert the guard before the `# shellcheck source=/dev/null` comment (currently line 14), tagged `[restore-dump][error]` (matches the file's existing message style, e.g. line 30)
  - [x] Run `shellcheck` on all four touched scripts — must be clean. Also manually verify (temporarily move `/opt/skillars/.env` aside, run each script, confirm a `[<script>][error]`-tagged exit 1, then restore the file) that each of the four actually produces the tagged message and not a bare shell error — shellcheck passing is necessary but, as `restore-from-volume-backup.sh`'s `err()`-ordering trap above proves, not sufficient
- [x] Task 2 — Guard `restore-from-volume-backup.sh`'s ownership fixups (AC: #2)
  - [x] Wrap each `chown -R`/`chmod` line (lines 75-81) in a `[ -d "${DATA_DIR}/<subdir>" ]` (or equivalent) check before acting on it, reusing the same `VOLUME_SUBDIRS` list already iterated at line 63 rather than hand-repeating six directory names — a `for d in $VOLUME_SUBDIRS` loop with a per-directory ownership-map lookup is the natural shape, but keep it simple; do not over-engineer a generic ownership-table abstraction for six fixed, unlikely-to-grow entries
  - [x] A directory that does not exist is logged (`log "skipping ownership fix for <dir> — not present in this archive"`) and skipped, not silently ignored — an operator reading the restore log should be able to tell a legitimately-missing directory from the script quietly doing less than expected
  - [x] `shellcheck` clean
- [x] Task 3 — Ledger hygiene (AC: #3)
  - [x] In `deferred-work.md`, locate `## Deferred from: code review of skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)` (currently line 1391) and annotate each of its 8 bullet items per AC3's closed/dismissed split
  - [x] `sprint-status.yaml` already carries `skillars-deferred-20-backup-restore-script-hardening: ready-for-dev` (added at story creation, currently line 464) — when this story moves to `review`/`done`, update *that* status value; do not add a duplicate entry

### Review Findings

- [x] [Review][Decision] `sprint-status.yaml` bundles an unrelated status transition into this diff — `skillars-uat-6-coach-subscription-and-volume-backup` flips from `review` to `done` alongside deferred-20's own status line. **Resolved by Mbah:** intentional — uat-6's own review already completed (see its sprint-status comment), leave bundled, no split needed.
- [x] [Review][Patch] `restore-from-volume-backup.sh`'s traefik ownership fixup `chmod`s `acme.json` without checking the file itself exists — only the parent directory is guarded; a traefik dir present with `acme.json` absent (e.g. deleted for a forced cert reissue before the backup ran) still fails `chmod` under `set -e`, reintroducing the exact "assumes everything is present" failure mode AC2 exists to close, one level deeper. [deploy/backup/restore-from-volume-backup.sh]
- [x] [Review][Patch] Ownership-fixup `case "$d" in ... esac` has no `*)` default branch — currently unreachable (`VOLUME_SUBDIRS` and the case arms are 1:1), but if a 7th subdirectory is ever added to `VOLUME_SUBDIRS` without a matching case arm, it would silently get zero ownership fix and zero log output (unlike the "not present" path, which does log). A one-line default arm closes it cheaply. [deploy/backup/restore-from-volume-backup.sh]
- [x] [Review][Defer] `.env` guard is duplicated verbatim across 4 scripts instead of a shared sourced snippet or function — deferred, pre-existing (matches `prune-backups.sh`'s established per-script convention; the story's own Dev Notes explicitly said not to invent a new style). [deploy/backup/pg-backup.sh, volume-backup.sh, restore-from-volume-backup.sh, restore-from-dump.sh]
- [x] [Review][Defer] The `[ ! -r ]` guard doesn't distinguish "file missing" from "path is a directory" (a directory at that path would pass `-r` and then fail unpredictably at the `source` line) — deferred, pre-existing (identical limitation in the mirrored `prune-backups.sh:35` guard; fixing it only here would be inconsistent). [deploy/backup/prune-backups.sh:35]
- [x] [Review][Defer] No post-source validation that required `.env` variables were actually set after sourcing (a readable-but-empty-or-truncated file still passes the guard) — deferred, pre-existing (out of AC1's readability-only scope; would need its own scoping decision about which vars to validate and how). [deploy/backup/pg-backup.sh, volume-backup.sh, restore-from-volume-backup.sh, restore-from-dump.sh]

## Dev Notes

- **Scope discipline — do not expand beyond AC1-3.** The 5 dismissed items are dismissed for a reason already stated in their own ledger text, not because nobody looked:
  - *Shared `payment.stripe_customers` row across parent/player/coach roles* — this is the story's own explicitly-documented design decision ("do not add a `payer_type` column"), not a defect.
  - *Removing `attachPaymentMethod` in `subscribeCoach`* — deliberately mirrors `subscribePlayer`'s already-shipped, working pattern; the spec explicitly directed this.
  - *`HCLOUD_TOKEN` still read by `apply-firewall.sh` locally* — explicitly scoped out as a separate, untouched **local-operator-machine** concern; nothing server-side reads it anymore.
  - *No disk-space precheck in `volume-backup.sh`* — identical, non-incident gap in `pg-backup.sh` (the script this mirrors) has existed since `deploy-3-1` with no failure recorded; unlike `restore-from-dump.sh`'s precheck (lines 37-51 — it can size the *known* S3 object before downloading), a backup script has no reliable size estimate before writing, making an accurate check meaningfully harder to get right than to skip. Not in scope here.
  - *`prune_volume_backups()` trusts `aws s3api` output shape* — identical gap in `prune_s3_dumps()`, the exact function it mirrors; both already treat an empty/unparseable listing as fatal (`prune-backups.sh:93-97`, `178-182`) and validate every key's timestamp format before acting on it (`prune-backups.sh:130-135`, `216-221`), so the actual blast radius of a garbled response is already bounded. No incident recorded.

  If asked to "just fix everything while you're in there," don't — re-read this list and the original ledger entries (`deferred-work.md:1391-1400`) first.

- **Established guard pattern to copy verbatim, including its position relative to the shellcheck directive (do not invent a new style).** `prune-backups.sh:35-40`:
  ```bash
  if [ ! -r "$ENV_FILE" ]; then
    echo "[prune-backups][error] cannot read ${ENV_FILE} — retention cannot run without credentials" >&2
    exit 1
  fi
  # shellcheck source=/dev/null
  . "$ENV_FILE"
  ```
  Note the order: guard first, `# shellcheck source=/dev/null` comment second, source line third — the comment is never separated from the line it annotates. Each of the four target scripts already has its own tag convention (`[pg-backup]`, `[volume-backup]`, `[restore-dump]`; `restore-from-volume-backup.sh` uses `log()`/`err()` helper functions instead of raw `echo` — use `err` there, not a raw `echo ... >&2`, but only *after* relocating those helper definitions above the guard per Task 1; they are not in scope yet at the `.env` source line's current position in the file).

- **`restore-from-volume-backup.sh`'s existing `VOLUME_SUBDIRS` and existence-check precedent (line 20, lines 63-67):**
  ```bash
  VOLUME_SUBDIRS="redis prometheus loki tempo grafana traefik"
  ...
  for d in $VOLUME_SUBDIRS; do
    if [ -d "${DATA_DIR}/${d}" ]; then
      mv "${DATA_DIR}/${d}" "${PRERESTORE_DIR}/${d}"
    fi
  done
  ```
  The ownership-fixup block this story guards (lines 75-81) has one line per directory with a directory-specific `chown`/`chmod` (different uid:gid per service) — it cannot be collapsed into the same generic loop as-is without a small per-directory ownership table (e.g. a `case` on `$d`, or parallel arrays). Keep whatever shape is clearest; six fixed entries do not need a config-driven abstraction.

- **This story touches shell scripts only — no Java, no Vue, no migration.** No `mvn -o verify` is required for this story's own correctness; `shellcheck` is the applicable check (every prior story touching `deploy/backup/*.sh` — `skillars-uat-3`, `skillars-uat-6` — held itself to "shellcheck clean" as the bar, with a standing, explicitly-recorded caveat: none of these scripts have ever been executed against live Hetzner Object Storage credentials in this environment, so a live dry run is not possible here either. Do not attempt to fabricate one.

- **File paths this story touches:**
  - `deploy/backup/pg-backup.sh`
  - `deploy/backup/volume-backup.sh`
  - `deploy/backup/restore-from-volume-backup.sh`
  - `deploy/backup/restore-from-dump.sh`
  - `_bmad-output/implementation-artifacts/deferred-work.md` (annotation only)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (status line only)

### Project Structure Notes

- All touched shell scripts already live in `deploy/backup/`; no new files, no new directories.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses in `sprint-status.yaml` (the "DEFERRED WORK" block, not nested under any `skillars-epic-N` key).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md#Deferred from: code review of skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)] (lines 1391-1400) — the 8 source items this story triages
- [Source: _bmad-output/implementation-artifacts/uat-readiness-priorities.md] — confirms P0/P1/P2 fully claimed, this story is intentionally sourced outside that document
- [Source: deploy/backup/prune-backups.sh:35-38] — the env-guard pattern to mirror
- [Source: deploy/backup/restore-from-volume-backup.sh:20,63-67] — the existence-check pattern to mirror
- [Source: deploy/backup/restore-from-dump.sh:37-51] — precedent for a disk-space precheck existing elsewhere in this file family (cited to explain why AC1-3 deliberately does *not* add one to the backup-side scripts — see Dev Notes)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `shellcheck deploy/backup/pg-backup.sh deploy/backup/volume-backup.sh deploy/backup/restore-from-volume-backup.sh deploy/backup/restore-from-dump.sh` — clean, no findings.
- `shellcheck deploy/backup/*.sh` (full directory regression) — clean, no findings.
- Manually ran all four scripts with `/opt/skillars/.env` absent (no `/opt/skillars` exists on this dev machine, equivalent to the story's "temporarily renamed away" test): each produced its own `[<script>][error] cannot read /opt/skillars/.env — ... credentials`-tagged message and exit 1, not a bare shell error. `restore-from-volume-backup.sh` specifically confirmed as `[restore-from-volume-backup][error] cannot read...` — not `command not found` — verifying the `log()`/`err()` relocation-before-guard ordering works.
- Isolated unit test of the ownership-fixup loop logic (temp dir with only `redis`/`traefik` present) confirmed present directories are acted on and absent ones are logged via `log "skipping ownership fix for <dir> — not present in this archive"` and skipped, not silently ignored and not fatal.
- Post-review: `shellcheck deploy/backup/restore-from-volume-backup.sh` re-run after the two review-follow-up patches — clean.
- Post-review: isolated unit test re-run with `traefik/acme.json` deliberately absent and an extra `unknownsvc` directory injected into `VOLUME_SUBDIRS` — confirmed the missing `acme.json` is logged and skipped (not fatal), and the unmatched subdirectory hits the new `*)` default arm and is logged rather than silently no-op'd.

### Completion Notes List

- AC1: Added a `.env` readability guard (mirroring `prune-backups.sh:35-40`'s exact structure — guard block, then `# shellcheck source=/dev/null`, then the source line) to `pg-backup.sh`, `volume-backup.sh`, `restore-from-volume-backup.sh`, and `restore-from-dump.sh`. In `restore-from-volume-backup.sh`, `log()`/`err()` were relocated to immediately after `set -euo pipefail` so the guard can call `err()` before it would otherwise be defined.
- AC2: The `chown -R`/`chmod` ownership-fixup block in `restore-from-volume-backup.sh` now loops over the existing `VOLUME_SUBDIRS` list with a per-directory `case` on ownership values, guarded by a `[ -d ... ]` existence check; a missing subdirectory is logged and skipped rather than crashing `set -e` mid-restore with services already stopped.
- AC3: All 8 items under `deferred-work.md`'s uat-6 code-review section annotated: 2 `[CLOSED by skillars-deferred-20 AC1]` (the two `.env`-guard items), 1 `[CLOSED by skillars-deferred-20 AC2]` (the `chown` subdirectory item), 5 `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: ...]` each with the existing one-line reason inlined. `sprint-status.yaml` already carried the `ready-for-dev` entry at story creation; no duplicate added — it is updated to `review` as part of this workflow's completion step.
- A fourth `.env`-sourcing gap (`restore-from-dump.sh`, not named in the original ledger) was found and fixed during story creation per the story's own "Why this story exists" rationale — closes the same untagged-failure gap consistently across the whole script family.
- Out of scope by design (Dev Notes "Scope discipline"): the 5 dismissed ledger items (shared Stripe customer row, `attachPaymentMethod` removal, `HCLOUD_TOKEN` in `apply-firewall.sh`, no disk-space precheck in `volume-backup.sh`, `prune_volume_backups()` trusting `aws s3api` output shape) were left untouched, each dismissed with its own pre-existing reason.
- No Java/Vue/migration changes; `shellcheck` is the applicable check per Dev Notes. No live dry run against Hetzner Object Storage was performed — consistent with the standing, explicitly-recorded caveat in this script family that no prior story has run one in this environment either.
- ✅ Resolved review finding [Patch]: traefik ownership fixup `chmod`'d `acme.json` without checking the file exists — now guarded with `[ -f "${DATA_DIR}/${d}/acme.json" ]`, logging and skipping (not failing) when the file is legitimately absent.
- ✅ Resolved review finding [Patch]: ownership-fixup `case` statement had no default arm — added a `*)` branch that logs `"no ownership fix defined for ${d} — skipping"` instead of silently no-op'ing if `VOLUME_SUBDIRS` ever grows a 7th entry without a matching case arm.
- Review finding [Decision] (`sprint-status.yaml` bundling uat-6's `review`→`done` transition into this diff) was resolved by Mbah as intentional — no code change needed.
- Review findings tagged `[Defer]` (guard duplication across 4 scripts, `[ ! -r ]` not distinguishing missing-file from is-a-directory, no post-source `.env` variable validation) were left as-is per the review's own rationale: each is pre-existing and shared with `prune-backups.sh`'s established convention, out of this story's AC1 scope.

### File List

- `deploy/backup/pg-backup.sh` (modified — AC1 guard)
- `deploy/backup/volume-backup.sh` (modified — AC1 guard)
- `deploy/backup/restore-from-volume-backup.sh` (modified — AC1 guard + helper relocation, AC2 ownership-fixup guard, review-follow-up acme.json existence check + case default arm)
- `deploy/backup/restore-from-dump.sh` (modified — AC1 guard)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — AC3 ledger annotations)
