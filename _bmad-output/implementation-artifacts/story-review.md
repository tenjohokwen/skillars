# Senior-dev audit — story skillars-deferred-87

> **RESOLVED 2026-08-31 — all findings applied to the story (v0.2 in its Change Log).** Every finding
> (H1–H6, M1–M5, L1–L8) was verified against the actual files and held up; **no false positives**.
> M2 was folded in as a "pre-existing, out-of-scope caveat, do not overstate" note rather than a new
> fix; L4 as a wording correction. See the story's Change Log v0.2 row for the per-finding disposition.

---


**Reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-87-backup-upload-verification-hardening-provision-volume-device-and-pre-volume-data-migration-ci-latest-ordering-guard.md`
**Method:** every AC cross-checked against the actual files it modifies (`deploy/backup/pg-backup.sh`, `deploy/backup/volume-backup.sh`, `deploy/backup/env-guard.sh`, `deploy/backup/install-crons.sh`, `deploy/provision.sh`, `docs/deployment/first-time-setup.md`, `.github/workflows/ci.yml`, `.github/workflows/pr-build.yml`, `.github/actions/docker-build/action.yml`, `_bmad-output/implementation-artifacts/deferred-work.md`, `DrillUploadService.java`).
**Verdict:** the AC intent is sound and the ledger-hygiene claims mostly check out, but **AC5 has a design flaw that would abort a clean first install**, **AC4 has an upgrade-path regression on already-provisioned nodes**, and **AC7 over-counts one ledger bullet in a way that would produce a false `[CLOSED]` annotation**. Details below, ordered by severity, each tagged with confidence.

---

## What is solid (no action needed)

- **AC7 cross-drill `videoId` closure is real.** `DrillUploadService.initiateUpload` (`:99-107`) and `.deleteVideo` (`:152-159`) both call `videoRepository.findByIdForUpdate(...)` inside the `lockRetryer.withBoundedRetry` block, with the Drill→Video lock-order comment citing "Deferred-81 AC3". `VideoRepository.findByIdForUpdate` exists. The IT name in the story matches. Annotating `skillars-4-3` W2 and `skillars-6-1` Def14 as closed is correct.
- **AC7 `skillars-deferred-10` D5 closure is real.** `pr-build.yml:88` calls `./.github/actions/docker-build`, which carries `cache-from: type=gha` / `cache-to: type=gha,mode=max` (`action.yml:49-50`). D5's "`pr-build.yml` has no Docker layer caching" is genuinely obsolete.
- **AC7 `skillars-deferred-10` D6 closure is real.** `first-time-setup.md:130` now reads "...a claim about Hetzner's infrastructure that we have not verified or found documented — treat it as unconfirmed rather than relying on it." D6's "asserts ... as fact with no citation" no longer holds.
- **AC1/AC2 scoping to only `pg-backup.sh` + `volume-backup.sh` is correct** — `restore-from-dump.sh`, `restore-from-volume-backup.sh`, `prune-backups.sh` contain no `head-object`/ETag verification block (grep confirms), so there is nothing to change there.
- **AC1 behaviour change strands no docs** — no file under `docs/deployment/` mentions the ETag/MD5 verification behaviour.
- **The `deploy/backup/*.sh` "no shell test harness" claim is accurate** — no `.bats` / `*_test.sh` anywhere outside `node_modules`.
- **AC6 not exercisable from a PR** is correctly stated (`ci.yml` is `push: [master]` only).

---

## HIGH — substantive gaps / false assumptions

### H1. AC5 detection predicate is always true — it cannot tell real pre-Volume data from an empty dir section 6 just created
**Confidence: high.** `provision.sh` section 6 (`:124-126`) runs `mkdir -p "${DEPLOY_ROOT}/data/postgres"` **before** section 7. `MOUNT_POINT="${DEPLOY_ROOT}/data"` (`:161`). So by the time AC5's check runs, `${MOUNT_POINT}` on the root disk **always** contains at least `postgres/`. The AC's literal predicate — `[ -n "$(ls -A "${MOUNT_POINT}" 2>/dev/null)" ]` (AC5, line 85) — is therefore true on *every* run, including a pristine first provision on a host that had the Volume attached from the start.
**Fix:** the trigger must test for *meaningful* pre-Volume payload, not "non-empty" — e.g. presence of `traefik/acme.json`, or a non-empty `redis/`, or a non-empty LGTM dir. "`postgres/` exists and is empty" must not arm the migration.

### H2. AC5 verification step will `exit 1` on a clean first install
**Confidence: high.** AC5 (line 86) says after migrating, "verify a representative path landed (`traefik/acme.json` mode `600`, `redis/` owner `999:1000`)". But `redis/` and `traefik/acme.json` are created by **section 7.5, which runs *after* section 7** (`provision.sh:218`, `:224-245`). On a fresh host with the Volume attached from the start, section 7 runs `mkfs.ext4` → "first mount" is true, and (per H1) the non-empty check is also true, so the migration path is entered — then the `stat` on a non-existent `traefik/acme.json` fails and, under `set -euo pipefail`, **aborts a clean provision**. The verification must only assert paths that actually existed in the migration source, or be gated behind H1's tighter trigger.

### H3. AC5 has no recovery path if the migration itself is interrupted — it recreates the exact bug it fixes
**Confidence: high.** `provision.sh` is `set -euo pipefail`. If `rsync` (disk full on the Volume, etc.) or the post-migration verify fails **after** `mount "${VOLUME_DEVICE}" "${MOUNT_POINT}"` but **before** the root-disk copy is removed, the script aborts with the Volume mounted. On the operator's re-run: the already-mounted check (`mountpoint -q`) short-circuits section 7, and the pre-Volume tree is now **hidden underneath the mount** — AC5's own detection (`ls -A "${MOUNT_POINT}"` now lists Volume contents) can never see it again. Result: the pre-Volume certs/data are stranded and hidden on the root disk, permanently, with no script path to recover them — *precisely* the failure AC5 exists to prevent, now unrecoverable. AC5 needs either (a) migrate-before-mount (rm the root copy before `mount`), or (b) an explicit re-run detector that checks the root filesystem *under* the mount (`findmnt` + a bind mount) and resumes.

### H4. AC4 fstab handling regresses already-provisioned nodes
**Confidence: high.** Current `FSTAB_ENTRY="${VOLUME_DEVICE} ${MOUNT_POINT} ext4 defaults,nofail 0 2"` with idempotency via `grep -qF "${FSTAB_ENTRY}" /etc/fstab` (`provision.sh:162`, `:181`). A node provisioned with today's script already has a `/dev/sdb /opt/skillars/data ext4 ...` line in `/etc/fstab`. After AC4 rewrites `FSTAB_ENTRY` to the `/dev/disk/by-id/scsi-0HC_Volume_*` form, `grep -qF` no longer matches the old line, so the script **appends a second fstab entry for the same mount point**. On the next reboot `mount -a` processes both. AC4 says "the `grep -qF` check must match on the exact string now written" but does not address the stale `/dev/sdb` line already on disk. It needs an explicit purge/replace of the old line — the pattern already exists in this codebase (`install-crons.sh:26-29` removes the stale `volume-snapshot.sh` cron the same way). This is exactly the re-run scenario the story cares about.

### H5. AC4 leaves three `/dev/sdb` statements in `first-time-setup.md` that the script no longer honours
**Confidence: high.** `first-time-setup.md` still says:
- `:41` — "it expects the device at `/dev/sdb`"
- `:43` — "run `lsblk` to confirm the volume appears as `/dev/sdb`. If it is listed under a different name, the provisioning script hardcodes `/dev/sdb` and will mount the wrong device — **stop and verify**"
- `:93` — "Mounts the Hetzner Volume (`/dev/sdb`) at `/opt/skillars/data`"

AC4's scope is `provision.sh` + AC7 only. AC3 and AC5 both edit `first-time-setup.md`, so the file *is* in play this story — AC4 must update these three spots too, or the doc will actively instruct operators to abort on the very device-name variation AC4 is making safe.

### H6. AC7 item 5 over-counts the `skillars-deferred-22` bullets → risks a false `[CLOSED]` annotation
**Confidence: high.** The `## Deferred from: code review of skillars-deferred-22 …` section has exactly **one** cross-drill-lock `initiateUpload` bullet — `deferred-work.md:1109` ("...opens the same TOCTOU window as `deleteVideo`'s already-deferred `Def14` race... The cross-drill variant ... remains open"). The story (line 116 and line 25) says "**both** `skillars-deferred-22` `initiateUpload` bullets". The other `initiateUpload` bullet in that section — `:1108` ("doesn't confirm the video row itself still exists before publishing `VideoPhysicalDeletionEvent`") — is a **different, still-open** concern: `deferred-81` AC3 added the videoId lock but the `publishEvent` at `DrillUploadService.java:~121` still fires on `!existsByVideoId(...)` alone with no video-row existence check. Appending `[CLOSED by skillars-deferred-81 AC3]` to `:1108` would be factually wrong. AC7 must name the single correct bullet (`:1109`) and leave `:1108` untouched.

---

## MEDIUM — likely to bite in implementation or first prod run

### M1. AC6's prescribed `imagetools inspect` template will probably not work against the real image
**Confidence: medium.** `docker/build-push-action@v6` (`action.yml:41`) attaches **provenance attestations by default on push**, so `ghcr.io/…:latest` is published as an **OCI image index**, not a single image manifest. Against an index, the Go-template context's `.Image` is a per-platform map (`{"linux/amd64": {...}}`) with no `.Labels` field, so the AC's `--format '{{ index .Image.Labels "org.opencontainers.image.revision" }}'` (AC6, line 99) fails. The AC needs an index-aware template (`{{ (index .Image "linux/amd64").Config.Labels ... }}`), or should read the revision from a more robust source, and the dev must verify it against an actually-published image — which cannot be done until the first post-merge run, so this risk lands in prod.

### M2. AC6's "SHA tag is always published regardless" is not guaranteed by the current concurrency config
**Confidence: medium.** `build-and-push` has `concurrency: { group: build-and-push-${{ github.ref }}, cancel-in-progress: false }` (`ci.yml:52-54`). GitHub keeps only **one** run pending per concurrency group and **cancels the previously-pending run** when a newer one queues. With ≥3 master pushes in quick succession (story merge + two Dependabot squashes), the middle run's `build-and-push` is cancelled while pending and its `sha-<short>` tag is **never pushed** — so `docs/deployment/rollback.md` has no rollback target for that commit. AC6 (line 101) asserts "The immutable `sha-<short>` tag ... is **always** pushed regardless"; that is only true for runs that actually execute. Either weaken the claim or note the burst case explicitly.

### M3. AC6 has no defined behaviour when the published revision is absent from local git history
**Confidence: medium.** `git merge-base --is-ancestor <published-rev> "$GITHUB_SHA"` exits **128** (not 0/1) when `<published-rev>` is unknown to the checked-out repo — reachable if `master` was ever force-pushed, or if `:latest` points at a commit from a deleted/rebased branch. AC6 handles "no such tag" but not "tag exists, commit unknown". Under `set -e` in the step this hard-fails the job. Specify: unknown commit ⇒ treat as "not a descendant" (safe: push SHA only + notice), don't abort.

### M4. AC3's `provision.sh` recursive check is UID-only; the doc remediation in the same AC checks UID *and* GID
**Confidence: medium.** AC3 prescribes `find "$dir" \! -user "${owner%%:*}" -print -quit` for the script, but `chown -R "$owner"` sets `uid:gid` (e.g. `999:1000`, `65534:65534`). A provision killed mid-`chown -R` that got children's UID right but GID wrong is not detected by a `\! -user` test, so the re-run still skips — the partial-completion hole AC3 is closing stays open for the GID half. The AC's *doc* snippet correctly uses `\! -user X -o \! -group X`; make the script side consistent.

### M5. AC3 reintroduces a full recursive walk on the common re-run path
**Confidence: medium.** The design intent recorded in `provision.sh:23-25` is that `chown_if_needed` does **no** recursive traversal when the top-level owner already matches. AC3's second tier walks `find "$dir" ... -print -quit`, and `-quit` only short-circuits when a mismatch is **found** — on a healthy re-run (top-level matches, no mismatch anywhere) it walks the *entire* subtree of `prometheus/`, `loki/`, `tempo/`, `grafana/`, `redis/` on the mounted Volume, every time `provision.sh` is re-run. On a node with real observability retention that is a non-trivial metadata scan on every idempotent re-run. Acknowledge the cost, or bound the walk (e.g. `-maxdepth`, or only walk when a cheap sentinel suggests trouble).

---

## LOW — nits, wording, and implementation traps worth pre-empting

### L1. AC1 — the "ETag matches local MD5" success line must move into an `else`
**Confidence: high.** In both scripts the success `echo` (`pg-backup.sh:96`, `volume-backup.sh:101`) sits after the `if [ "${REMOTE_ETAG}" != "${LOCAL_MD5}" ]; then … exit 1; fi` inside the `*)` arm. AC1 says "emit `[warn]` and continue instead of `exit 1`" and separately "keep the success line for the genuine-match case", but never says *restructure to `if/else`*. A literal edit (drop `exit 1`, keep everything else) prints the misleading "Upload verified: single-part ETag matches local MD5." line immediately after the warning on a real mismatch. Make the restructure explicit.

### L2. AC2 — the prescribed helper shape will trip the story's own `shellcheck` gate
**Confidence: high.** AC2 wants a local function and "keep every capture `|| true`-guarded". `local REMOTE_SIZE=$(aws … ) || true` inside the helper (a) triggers **SC2155** ("Declare and assign separately") — a *new* shellcheck finding, which fails AC2's own "no-new-findings vs. baseline" verification bar — and (b) makes the `|| true` dead, because `local`'s own exit status (always 0) masks the command substitution. The helper must `local REMOTE_SIZE` then `REMOTE_SIZE=$(…) || true` on separate lines. Worth stating in the AC so the dev doesn't discover it via a failing gate.

### L3. AC2 — "15s" arithmetic
**Confidence: high.** 5 attempts with a 3s sleep *between* attempts = 4 × 3s = 12s of waiting, not 15s. AC2's `exit 1` message wording ("a genuinely-invisible-after-15s object") and the hand-trace description should say 12s, or the loop should sleep after the 5th attempt too (5 × 3s = 15s) — pick one.

### L4. AC1 — the two `case` blocks are *not* identical
**Confidence: high.** They differ in variable (`DUMP_FILE` vs `ARCHIVE_FILE`), log tag (`[pg-backup]` vs `[volume-backup]`), and comment wording ("dumps > 8 MB" vs "archives > 8 MB"). AC1's "identical `case … esac` block" prose is loose; the Tasks section already handles them separately, so this is only a wording fix in the AC.

### L5. AC5 — "freshly-mounted Volume is empty" must ignore `lost+found`
**Confidence: medium.** Every fresh `mkfs.ext4` volume mounts with a `lost+found` directory, so a bare `ls -A` emptiness test on the just-mounted Volume is never empty. The AC's alternate trigger ("the freshly-mounted Volume's `${MOUNT_POINT}` is empty") needs to exclude `lost+found`.

### L6. AC5 — removing the migrated root-disk copy through a shadowed mount is under-specified for a no-live-test prod script
**Confidence: medium.** Once the Volume is mounted over `${MOUNT_POINT}`, the root-disk copy is only reachable via a bind mount of the parent, and the cleanup is an `rm -rf` adjacent to real `acme.json` cert data. The AC offers "(or mount at `${MOUNT_POINT}` then rsync from a bind-saved copy)" as a parenthetical alternative and picks neither. Given this project's "code-review + hand-trace, no live infra run" convention for `provision.sh`, the AC should commit to **one** mechanism and include a guard that refuses the `rm` if the bind source and the Volume resolve to the same filesystem (`findmnt` / `stat -f`).

### L7. Story rationale — "a false page" is not supported by anything in the repo
**Confidence: high.** The Story statement and creation context say a false backup failure produces "a false page" "via the backup cron's alerting". There is **no** alerting or paging wired to the backup cron: `install-crons.sh` installs `… >> /var/log/skillars-backup.log 2>&1` and nothing scrapes that log or the backup freshness (no Prometheus rule, no Grafana alert, no `backup` reference in `deploy/**` YAML). The genuine harm AC1/AC2 fix is a **false failure line in the backup log plus deletion of the local artifact via the `trap`** — which is real and worth fixing, but the "false page" framing overstates the current wiring. Doesn't change any AC's mechanics; worth correcting so a reviewer of the *next* story doesn't go looking for alert config that isn't there.

### L8. AC3/AC4/AC5 all rewrite the same ~50 lines of `provision.sh` section 7 (+ 7.5) with no stated sub-ordering
**Confidence: medium.** `chown_if_needed` (AC3), device resolution + fstab (AC4), and the pre-Volume migration (AC5) all land in / around section 7, under `set -euo pipefail` and the "idempotent, re-run-safe by contract" requirement. The story lists them as independent ACs and the hand-trace cases don't include the combined "already-provisioned-with-old-script, new script + Volume now attached" path — which is the one that exercises H3 (recovery), H4 (stale fstab line) and AC4 device resolution all at once. Add that as an explicit hand-trace case in the Dev Agent Record.

---

## Suggested AC edits (summary)

| # | AC | Change |
|---|----|--------|
| H1/H2 | AC5 | Arm migration only on a concrete pre-Volume marker (`traefik/acme.json` present, or non-empty `redis/`/LGTM dir) — not `ls -A` non-empty. Verify only source paths that existed. |
| H3 | AC5 | Migrate-before-mount, or add a re-run detector that inspects the root fs under the mount; define the interrupted-migration recovery path. |
| H4 | AC4 | Detect and replace/remove a pre-existing `/dev/sdb …` line in `/etc/fstab` (mirror `install-crons.sh`'s stale-cron purge). |
| H5 | AC4 | Add `first-time-setup.md` lines 41, 43, 93 to AC4's file scope. |
| H6 | AC7 §5 | Annotate only `deferred-work.md:1109`; do **not** touch `:1108` (different, still-open concern). |
| M1 | AC6 | Index-aware `imagetools` template (or alternative revision source); require verification against a real published image. |
| M2 | AC6 | Weaken / qualify the "SHA tag always pushed regardless" claim for the ≥3-push burst case. |
| M3 | AC6 | Define behaviour when the published revision is not in local history (⇒ SHA-only + notice, don't abort). |
| M4 | AC3 | Make the `provision.sh` recursive check test GID as well as UID, matching the AC's own doc snippet. |
| M5 | AC3 | Acknowledge / bound the full-subtree `find` on the healthy re-run path. |
| L1 | AC1 | State explicitly: convert the `*)` arm to `if mismatch → warn; else → success line`. |
| L2 | AC2 | Split `local` declaration from assignment in the helper (SC2155 / dead `|| true`). |
| L3 | AC2 | Reconcile "15s" vs 5×3s / 4×3s. |
| L7 | Story | Drop or qualify "false page"; there is no backup alerting in the repo. |
| L8 | AC3-5 | Add the "already-provisioned node, old script, Volume attached later" combined hand-trace case. |
