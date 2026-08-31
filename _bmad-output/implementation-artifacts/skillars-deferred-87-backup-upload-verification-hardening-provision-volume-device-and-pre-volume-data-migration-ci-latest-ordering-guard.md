# Story skillars-deferred-87: backup-upload verification hardening (ETag false-fail + head-object read-after-write retry), provision.sh volume-device resolution + pre-Volume data migration, the CI `:latest`-tag ordering guard, and ledger hygiene

Status: ready-for-dev

## Story

As the platform operator,
I want (1) `pg-backup.sh` / `volume-backup.sh` to stop hard-failing a good backup upload when the object-storage ETag is not a whole-object MD5 (bucket-side encryption, provider-specific ETag scheme) — the already-verified `ContentLength` match is the authoritative check — and to tolerate a brief read-after-write visibility delay on the post-upload `head-object` instead of `exit 1`-ing on a good upload, (2) `provision.sh` to stop hard-coding the Hetzner Volume as `/dev/sdb` (unreliable device-name ordering on a multi-volume server) and to **migrate** a pre-Volume `acme.json` / `data/` tree onto the Volume on the re-run where the Volume first appears, rather than leaving it stranded and hidden on the root disk under the new mount, (3) `first-time-setup.md` to stop over-promising that `provision.sh`'s `chown_if_needed` fully covers a run killed mid-`chown -R` (top-level owner correct, children half-changed, "just re-run" then skips), (4) the `master` CI `build-and-push` job to stop being able to overwrite `ghcr.io/…:latest` with an **older** commit's image when two `master` pushes' `test` jobs finish out of push order, and (5) `deferred-work.md` brought back in sync with the code — three of its Deploy/Infra items and, unrelatedly, the whole "cross-drill `videoId` lock still open" cluster are already closed in shipped code and were never annotated,
so that a routine backup no longer emits a false failure and a false page, a Volume re-attach no longer silently loses every TLS certificate and the LGTM/Redis data written before the Volume was attached, a partially-completed provision no longer leaves permanently mixed ownership under the data mount with a doc that says it is fine, a fast Dependabot merge landing right behind a slower story merge can no longer republish stale `:latest`, and the next full-file `deferred-work.md` audit stops re-flagging six items that are done.

## Story creation context

Per the standing `deferred-work.md` re-mining priority order (`[[project_skillars_release_workflow]]`): **SLU/Radar and Drills/Session-Builder are both mined out of decision-light bundleable work** — the SLU/Radar cluster closed with `skillars-deferred-86` (#131), and a live re-scan of the Drills/Session-Builder sections during this story's creation found its one substantial remaining item — the cross-drill `videoId`-scoped double-publish race (`skillars-4-3` W2, `skillars-6-1` Def14, `skillars-deferred-22` ×2) — **already closed in shipped code by `skillars-deferred-81` AC3 and never annotated** (see AC5 below and the ledger-hygiene mapping). Priority order therefore advances to **Deploy/Infra**, whose most recent code review (`skillars-deferred-85`, 2026-08-31) left a coherent cluster of concrete residuals:

- **`## Deferred from: code review of skillars-deferred-85 …` (2026-08-31)** — three residuals from that story's backup-hardening ACs (AC4/AC5), all in `deploy/backup/*.sh` + `docs/deployment/first-time-setup.md`:
  - **"Single-part ETag/MD5 compare can hard-fail a good <8 MB backup upload under bucket encryption."** `pg-backup.sh` / `volume-backup.sh`'s `*)` (single-part, no `-` in ETag) `case` branch does `exit 1` on `REMOTE_ETAG != LOCAL_MD5`. An S3-compatible ETag equals the raw object MD5 **only** for a single-part upload to a bucket with **no** SSE-KMS/SSE-C and no provider-specific ETag scheme. While dumps are still under awscli v1's 8 MB `multipart_threshold` (the first months of production, per the script's own comment) and if Hetzner Object Storage applies any server-side encryption, an intact, **already `ContentLength`-verified** upload is declared a failure and the script exits 1 — a false backup failure on good data, and (via the backup cron's alerting) a false page. → **AC1**. `deferred-85` scoped `exit 1` here as spec-mandated (its AC4); **this story deliberately reverses that** — the `ContentLength == local size` leg immediately above it is the reliable verification and is unaffected.
  - **"`head-object` verification read fires immediately after `aws s3 cp` with no retry/backoff."** Both scripts read the brand-new key exactly once; if Hetzner Object Storage does not guarantee strong read-after-write consistency for new-object PUTs (AWS S3 now does; Hetzner's guarantee is not documented), a transient not-yet-visible object yields an empty `REMOTE_SIZE` and the script `exit 1`s on a good upload — and, per the `trap … EXIT`, deletes the local dump too. → **AC2**.
  - **"`first-time-setup.md`'s 'If `provision.sh` fails partway through' section over-promises `chown -R` idempotency for the partial-completion case."** It states the one non-idempotent hazard "is already mitigated — `chown_if_needed` skips the recursive `chown` entirely when the directory's current owner already matches." A run killed **mid-`chown -R`** (SIGKILL, OOM, power loss during the exact chown) can leave the top-level directory already owned correctly while its children are only half-changed; the "just re-run" procedure then detects the top-level match and skips, permanently leaving mixed ownership under the data mount. → **AC3**.
- **`## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)`** — **"`/dev/sdb` hardcoded device path unreliable on multi-volume servers — if Hetzner changes device assignment order the mount silently fails."** `provision.sh` section 7 sets `VOLUME_DEVICE="/dev/sdb"` unconditionally. Hetzner exposes each Cloud Volume at a stable `/dev/disk/by-id/scsi-0HC_Volume_<id>` symlink; `/dev/sdX` ordering is not guaranteed once a server has more than one volume. → **AC4** (device resolution).
- **`## Deferred from: code review of skillars-uat-2 … — Group D (2026-08-11)`** — **"If `provision.sh` is run once before the Hetzner Volume is attached, then the volume is attached and the script rerun later, the pre-volume `acme.json` (created on the root disk by section 7.5's unconditional path) is orphaned rather than migrated onto the newly-mounted volume."** Section 7.5 deliberately runs on both branches (Volume present / absent), so on a no-Volume host it writes `redis/`, `traefik/acme.json`, and the LGTM dirs onto the **root disk** at `${DEPLOY_ROOT}/data`. When the Volume is later attached, section 7's `mount` overlays `${DEPLOY_ROOT}/data` and everything written before is still on the root disk, now **hidden underneath the mount** — a server rebuild then loses every TLS certificate and all pre-Volume LGTM/Redis data, exactly the loss section 7's own warning says the Volume exists to prevent. → **AC5** (pre-Volume data migration).
- **`## Deferred from: code review of skillars-deferred-23 … (2026-08-14)`** — **"AC5's `concurrency` group doesn't guarantee the newest-pushed commit wins `:latest`."** `ci.yml`'s `build-and-push` declares `needs: test`, so its entry into the `concurrency: build-and-push-${{ github.ref }}` queue tracks **`test`-job completion time, not push order**. Two `master` pushes close together (a story merge, then a Dependabot squash right behind it) whose `test` jobs finish out of order let the older push's `build-and-push` run second and overwrite `ghcr.io/…:latest` with the older image. The `concurrency` block (added by `deferred-23` AC5) does stop two `build-and-push` runs interleaving mid-upload — a real improvement — but not this ordering race, which `deferred-23` explicitly logged as out of scope. → **AC6**.
- **Ledger hygiene, `deferred-work.md`** — six items are closed in shipped code and unannotated, re-flagged by every full-file audit:
  - The **cross-drill `videoId`-scoped double-publish race**: `skillars-4-3` W2 ("that cross-drill variant remains open; a real fix needs a videoId-scoped lock, not a drillId-scoped one"), `skillars-6-1` Def14 ("The different-drills-sharing-videoId variant … remains open"), and `skillars-deferred-22`'s two `initiateUpload` bullets ("The cross-drill variant … remains open"). **All closed by `skillars-deferred-81` AC3** — `DrillUploadService.initiateUpload` **and** `.deleteVideo` both now take `videoRepository.findByIdForUpdate(videoId)` inside the existing `lockRetryer.withBoundedRetry(...)` block, with lock ordering (Drill row → Video row) documented in-code and cited to "Deferred-81 AC3"; `VideoRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`, from `deferred-64` AC3) exists; the IT `DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent` proves it, strengthened with lock-causality timing by `skillars-deferred-83` AC2. The PR title for `#126` is literally "…cross-drill video lock…". → **AC7** marks all four `[CLOSED by skillars-deferred-81 AC3 …]`.
  - `skillars-deferred-10` **D5** ("No Docker build-layer caching in `pr-build.yml` … every PR triggers a fully cold image build"). **Closed** — `.github/actions/docker-build/action.yml` (the composite action both `ci.yml` and `pr-build.yml` call) carries `cache-from: type=gha` / `cache-to: type=gha,mode=max`; its own `description` says "GHA layer caching". → **AC7** marks it `[CLOSED …]`.
  - `skillars-deferred-10` **D6** ("The new 'defence in depth' doc callout asserts Hetzner's outage behavior … as fact with no citation"). **Closed** — `first-time-setup.md`'s callout now reads "Whether already-applied Hetzner Cloud firewall rules keep enforcing during a Hetzner API outage is a claim … we have not verified or found documented — treat it as unconfirmed rather than relying on it." → **AC7** marks it `[CLOSED …]`.

**Seven ACs spanning two backup scripts, `provision.sh`, an ops doc, a CI workflow, and a six-item ledger-hygiene pass — comfortably past this project's "no small stories" bar. No new Spring context, no migration, no frontend, no i18n.**

## Acceptance Criteria

### AC1 — A non-MD5 single-part ETag no longer hard-fails a `ContentLength`-verified backup upload.

**`deploy/backup/pg-backup.sh` and `deploy/backup/volume-backup.sh`** (identical `case "${REMOTE_ETAG}" in … esac` block — `pg-backup.sh:85-97`, `volume-backup.sh:90-102`):

- In the `*)` (single-part, no `-`) branch, when `REMOTE_ETAG != LOCAL_MD5`, **emit `[…][warn]` to stderr and continue** instead of `exit 1`. The message must state that the `ContentLength` match above (the authoritative leg) already confirmed the upload, that a single-part ETag equals the raw MD5 only for an unencrypted bucket with a plain ETag scheme, and that SSE-KMS/SSE-C or a provider-specific ETag is the expected reason for a mismatch here.
- Keep the `""|None` and `*-*` (multipart) branches exactly as they are (already informational, not fatal).
- Keep the success line (`… single-part ETag matches local MD5.`) for the genuine-match case.
- **Update the block's lead comment** in both scripts to record that a single-part ETag mismatch is now advisory (was `exit 1`, `skillars-deferred-85` AC4 — reversed here) and that the `ContentLength` check at the top of the verification section is the sole hard gate on upload integrity.
- **Note in the story's ledger closure (AC7):** this deliberately overrides `skillars-deferred-85` AC4's "spec-mandated `exit 1` on single-part mismatch". Record the reversal in `deferred-work.md`, do not silently drop the original bullet.

**Tests / verification:** these are shell scripts with no test harness in this repo (`grep -rn "\.sh" src/test` → none; the `deploy/backup/*` scripts have never had automated tests — see `skillars-deferred-85`'s own dev notes). Verify by: (a) `bash -n` parses clean on both; (b) `shellcheck deploy/backup/pg-backup.sh deploy/backup/volume-backup.sh` reports no new findings vs. the pre-change baseline (run `shellcheck` on the unmodified files first and diff); (c) a hand-trace in the story's Dev Agent Record of all three `case` branches showing the `*)` branch now falls through to the final `echo "[…] Done."`. Do **not** add a bash test framework.

### AC2 — The post-upload `head-object` size check tolerates read-after-write visibility lag.

**`deploy/backup/pg-backup.sh` and `deploy/backup/volume-backup.sh`** (the `REMOTE_SIZE=$( … aws s3api head-object … --query 'ContentLength' … ) || true` capture then the `if [ -z … ] || [ "${REMOTE_SIZE}" = "None" ] || [ … != … ]; then … exit 1` gate — `pg-backup.sh:60-70`, `volume-backup.sh:67-77`):

- Wrap **only** the `head-object` `ContentLength` read in a bounded retry loop: up to **5** attempts, **3s** `sleep` between attempts, retrying **only** while `REMOTE_SIZE` is empty / `None` (object not yet visible). A retrieved-but-mismatched size is a genuine corruption/truncation signal — **do not** retry that; break and fail as today.
- Keep every capture `|| true`-guarded so a `head-object` transport failure under `set -euo pipefail` still reaches the diagnostic + `exit 1`, not a bare abort (the scripts' existing comment on this must stay accurate — update it to mention the retry).
- On exhausting all 5 attempts still empty/`None`, keep today's exact failure: the `[…][error] upload verification failed …` line and `exit 1` (the `trap` still deletes the local artifact — unchanged; a genuinely-invisible-after-15s object is a real failure worth the page).
- The **ETag** `head-object` read (AC1's block, immediately below) can reuse the already-confirmed-visible object — it does **not** need its own retry loop; add a one-line comment saying so.
- Factor the retry as a small local function (e.g. `head_object_content_length <bucket> <key>`) **within each script** rather than a shared file — `deploy/backup/*.sh` deliberately duplicate small guards per-script (`skillars-deferred-24` AC4 precedent, logged in `deferred-work.md`); a shared helper is a separate call.

**Tests / verification:** same as AC1 — `bash -n`, `shellcheck` no-new-findings, and a hand-trace of the loop (0 visible → 5 retries → exit 1; visible on attempt 3 → proceeds; visible-but-wrong-size on attempt 1 → immediate exit 1).

### AC3 — `first-time-setup.md` states the real `chown -R` partial-completion hazard, and `provision.sh` closes it.

**`docs/deployment/first-time-setup.md`** — the "If `provision.sh` fails partway through" section (around line 119-120, "The one non-idempotent hazard is `chown -R` over live data mounts. It is already mitigated — …"):

- Correct the over-promise: `chown_if_needed` compares **only the top-level directory's owner** (`stat -c '%U:%G' "$dir"`), so a run killed **mid-`chown -R`** leaves the top-level correct and children half-changed, and the re-run then **skips**. State this explicitly.
- Give the operator the one-line remediation to run after any interrupted provision, for each data subdir that has a non-root owner (`prometheus` 65534, `loki`/`tempo` 10001, `grafana` 472, `redis` 999:1000): e.g. `find "${DEPLOY_ROOT}/data/grafana" \! -user 472 -o \! -group 472` to detect, `chown -R 472:472 "${DEPLOY_ROOT}/data/grafana"` to fix.

**`deploy/provision.sh`** — `chown_if_needed()` (around line 26-30):

- Make the idempotency check recursive-aware: after the existing top-level-owner fast path, also run a bounded check for **any** entry under `$dir` not matching `$owner` (`find "$dir" \! -user "${owner%%:*}" -print -quit` — stop at the first mismatch) and fall through to the `chown -R` when one is found. Keep the fast path (no `find` walk) for the common already-correct case; only walk when the top-level matches but we want to be sure. Document the two-tier check in the function's comment.
- This must stay **idempotent and safe to re-run**, and must not change behaviour on a first provision (a freshly-created dir has no children, so the `find` finds nothing and the existing `chown -R` still runs via the top-level branch).

**Verification:** `bash -n deploy/provision.sh`; `shellcheck` no-new-findings; Dev Agent Record hand-trace of `chown_if_needed` for (a) first-provision fresh dir, (b) fully-correct dir on re-run (fast path, no walk), (c) top-level-correct-but-child-wrong dir on re-run (walk finds mismatch → `chown -R`).

### AC4 — `provision.sh` resolves the Hetzner Volume by its stable device id, not `/dev/sdb`.

**`deploy/provision.sh`** section 7 (around line 158-160, `VOLUME_DEVICE="/dev/sdb"`):

- Resolve `VOLUME_DEVICE` at runtime: prefer the first present `/dev/disk/by-id/scsi-0HC_Volume_*` symlink (Hetzner's documented stable per-Volume path), resolved to its real device node via `readlink -f`. Fall back to `/dev/sdb` **only** if no such symlink exists, with a `log` line naming which path was used.
- If `HETZNER_VOLUME_ID` is available in the environment (it is referenced elsewhere in the deploy tooling — confirm during dev), prefer the exact `scsi-0HC_Volume_${HETZNER_VOLUME_ID}` match over "first `scsi-0HC_Volume_*`" so a multi-Volume server picks the right one deterministically; otherwise "first match" with a `log` line is acceptable (single-Volume is the current topology).
- The `FSTAB_ENTRY` must use the **same resolved identifier** the script mounts — prefer the stable `/dev/disk/by-id/scsi-0HC_Volume_*` path in `/etc/fstab` (survives device-name reordering across reboots), not the resolved `/dev/sdX`. Keep `nofail` (already present).
- All existing section-7 idempotency (already-mounted check, `blkid` "already has a filesystem" check, "already in fstab" `grep -qF`) must keep working against the new identifier. The `grep -qF "${FSTAB_ENTRY}"` check must match on the exact string now written.
- Preserve the no-Volume-attached `else` branch (the warning block) unchanged in behaviour — it triggers when neither the `by-id` symlink nor `/dev/sdb` is a block device.

**Verification:** `bash -n`; `shellcheck` no-new-findings; Dev Agent Record hand-trace of device resolution for (a) `by-id` symlink present, (b) only `/dev/sdb` present, (c) neither present → warning branch; and confirmation that the fstab entry and the `mount` target use one identifier.

### AC5 — A pre-Volume `data/` tree is migrated onto the Volume on the re-run where the Volume first mounts.

**`deploy/provision.sh`** section 7, in the branch that mounts the Volume (after `mkfs.ext4` / `blkid` decision, before or immediately after `mount "${VOLUME_DEVICE}" "${MOUNT_POINT}"`):

- Before mounting, detect a non-empty pre-Volume tree at `${MOUNT_POINT}` on the **root disk** (`[ -n "$(ls -A "${MOUNT_POINT}" 2>/dev/null)" ]`).
- If present, and the Volume is being mounted for the first time (either `mkfs.ext4` just ran, or the freshly-mounted Volume's `${MOUNT_POINT}` is empty), **migrate** the root-disk contents onto the Volume: mount to a temp mountpoint (or mount at `${MOUNT_POINT}` then rsync from a bind-saved copy), `rsync -aHAX --numeric-ids` the pre-Volume tree onto the Volume, verify a representative path landed (`traefik/acme.json` mode `600`, `redis/` owner `999:1000`), then remove the now-migrated root-disk copy so it does not linger hidden under the mount consuming root-disk space.
- If **both** the root-disk tree **and** the freshly-mounted Volume are non-empty (Volume was used before, e.g. a re-attach of an existing Volume), **do not** overwrite — `log` a clear warning naming both paths and the manual-reconciliation step, and proceed with the Volume's own contents (fail-safe: never clobber existing Volume data). This is the one genuinely ambiguous case and it must be loud, not silent.
- Keep this entirely within section 7's Volume-present branch — the no-Volume branch and section 7.5 are unchanged. Section 7.5 still runs afterward to enforce ownership/permissions on whatever is now at `${MOUNT_POINT}` (migrated or fresh), which is correct.
- Update the section-7 header comment and the no-Volume warning block's text ("Everything under … stays on the ROOT DISK until this is done") to note that a later re-run now migrates it rather than stranding it.

**`docs/deployment/first-time-setup.md`** — update the Volume-attach step to state that re-running `provision.sh` after attaching the Volume migrates any pre-Volume `data/` contents (certs included) onto it, and call out the "Volume already had data" warning path.

**Verification:** `bash -n`; `shellcheck` no-new-findings; Dev Agent Record hand-trace for (a) no pre-Volume tree (skip, mount as today), (b) pre-Volume tree + empty fresh Volume (migrate + verify + cleanup), (c) pre-Volume tree + non-empty Volume (warn, no clobber). No live Hetzner run is expected (matches this project's established "code-review + hand-trace, no live infra run" convention for `provision.sh` changes — see `skillars-deferred-85` AC5/AC6 dev notes).

### AC6 — CI `build-and-push` will not overwrite `:latest` with an older commit's image.

**`.github/workflows/ci.yml`** — the `build-and-push` job (currently: `needs: test`, `concurrency: build-and-push-${{ github.ref }}` / `cancel-in-progress: false`, then the `docker-build` action pushing `sha-<short>` **and** `latest`):

- Before the build/push step, add a step that reads the `org.opencontainers.image.revision` label from the **currently-published** `ghcr.io/${{ github.repository }}:latest` (e.g. `docker buildx imagetools inspect --format '{{ index .Image.Labels "org.opencontainers.image.revision" }}' ghcr.io/${{ github.repository }}:latest`, tolerating "no such tag" on the very first publish).
- `git fetch` enough history, then compute whether `GITHUB_SHA` is a **descendant** of that published revision: `git merge-base --is-ancestor "<published-rev>" "$GITHUB_SHA"`.
- If it is **not** a descendant (or equal) — i.e. this run's commit is older than, or divergent from, what `:latest` already points at — **push only the `sha-<short>` tag, skip `:latest`**, and `echo "::notice::"` explaining why (`:latest` already points at a newer commit `<rev>`; this run published only its SHA tag). The immutable `sha-<short>` tag `docs/deployment/rollback.md` pins to is **always** pushed regardless.
- If it **is** a descendant, or `:latest` does not exist yet, push both tags exactly as today.
- Implement by making the `tags:` input to the `./.github/actions/docker-build` step conditional (compute the tag list in the new step, pass it through `$GITHUB_OUTPUT`). Do **not** change `.github/actions/docker-build/action.yml` itself — keep it a dumb "push these tags" action.
- The `permissions:` block already has `packages: write`; `contents: read` is enough for the ancestor check on the checked-out repo (ensure the checkout fetches enough depth — `fetch-depth: 0` on this job's checkout, or a targeted `git fetch --deepen`).

**Verification:** this workflow only runs on `push` to `master`, so it cannot be exercised from a PR. Verification is: (a) `actionlint .github/workflows/ci.yml` clean (if `actionlint` is available; otherwise a YAML-syntax parse); (b) Dev Agent Record walk-through of the three cases (no `:latest` yet → both tags; `HEAD` descends from published rev → both tags; `HEAD` older/divergent → SHA tag only + notice); (c) confirm `pr-build.yml` is untouched (it never pushes `:latest`). The real proof is the first post-merge `master` run after this story lands — call that out in Completion Notes.

### AC7 — `deferred-work.md` ledger hygiene.

Edit **`_bmad-output/implementation-artifacts/deferred-work.md`** in place (this file's own convention: delete a fully-closed bullet, or append a `[CLOSED by …]` tag when the surrounding context is still useful — recent stories append; follow the file's current tail-section style):

1. **`## Deferred from: code review of skillars-deferred-85 …` (2026-08-31)** — append `[CLOSED by skillars-deferred-87 AC1]` to the single-part-ETag bullet (record that `deferred-85` AC4's `exit 1` is deliberately reversed — `ContentLength` is the sole hard gate); append `[CLOSED by skillars-deferred-87 AC2]` to the `head-object` no-retry bullet; append `[CLOSED by skillars-deferred-87 AC3]` to the `chown -R` partial-completion bullet.
2. **`## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)`** — append `[CLOSED by skillars-deferred-87 AC4]` to the `/dev/sdb` hardcoded-device bullet.
3. **`## Deferred from: code review of skillars-uat-2 … — Group D (2026-08-11)`** — append `[CLOSED by skillars-deferred-87 AC5]` to the pre-Volume `acme.json` orphaned bullet.
4. **`## Deferred from: code review of skillars-deferred-23 … (2026-08-14)`** — append `[CLOSED by skillars-deferred-87 AC6]` to the `:latest` ordering bullet (note the SHA tag is always published; only `:latest` is gated on the ancestor check).
5. **Stale — cross-drill `videoId` lock**: append `[CLOSED by skillars-deferred-81 AC3 — DrillUploadService.initiateUpload AND .deleteVideo both take videoRepository.findByIdForUpdate(videoId) inside the existing lockRetryer.withBoundedRetry block, lock order Drill→Video documented in-code; proven by DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent, strengthened with lock-causality timing by skillars-deferred-83 AC2. Found stale during skillars-deferred-87 creation.]` to: `skillars-4-3` **W2**, `skillars-6-1` **Def14**, and **both** `skillars-deferred-22` `initiateUpload` bullets.
6. **Stale — `skillars-deferred-10`**: append `[CLOSED — .github/actions/docker-build/action.yml already carries cache-from: type=gha / cache-to: type=gha,mode=max, used by both ci.yml and pr-build.yml. Found stale during skillars-deferred-87 creation.]` to **D5**; append `[CLOSED — first-time-setup.md's defence-in-depth callout now explicitly labels the Hetzner-outage behaviour "unconfirmed rather than relying on it". Found stale during skillars-deferred-87 creation.]` to **D6**.
7. Add a new `## Deferred from: code review of skillars-deferred-87 …` section only if the code review of this story surfaces new deferrals — not required at creation time.

Leave every other `deferred-work.md` item untouched. Record the exact set of edited lines in the Dev Agent Record.

## Tasks / Subtasks

- [ ] **Task 1: Backup-upload ETag false-fail → warn (AC: #1)**
  - [ ] `shellcheck deploy/backup/pg-backup.sh deploy/backup/volume-backup.sh` on the unmodified files, save the baseline output
  - [ ] `pg-backup.sh` `case` `*)` branch: `exit 1` → `[pg-backup][warn] …` + fall through; rewrite the lead comment (single-part ETag now advisory; `ContentLength` is the sole hard gate; `deferred-85` AC4 reversed)
  - [ ] `volume-backup.sh`: identical change, `[volume-backup][warn] …`
  - [ ] `bash -n` both; `shellcheck` both → no new findings vs. baseline
  - [ ] Dev Agent Record: three-branch hand-trace showing `*)` reaches the final `Done.` line
- [ ] **Task 2: `head-object` read-after-write retry loop (AC: #2)**
  - [ ] `pg-backup.sh`: add `head_object_content_length()` local fn — ≤5 attempts, 3s sleep, retry only on empty/`None`; break + fail on a retrieved-but-mismatched size; keep `|| true`; update the "Captures wrapped `|| true`" comment to mention the retry
  - [ ] Wire the fn into the `REMOTE_SIZE` capture; leave the ETag `head-object` read as a single call with a "already confirmed visible above" comment
  - [ ] `volume-backup.sh`: identical
  - [ ] `bash -n` + `shellcheck` both → no new findings
  - [ ] Dev Agent Record: loop hand-trace (0 visible→exit 1; visible@3→proceed; wrong-size@1→immediate exit 1)
- [ ] **Task 3: `chown -R` partial-completion — doc + `provision.sh` (AC: #3)**
  - [ ] `first-time-setup.md`: correct the "already mitigated" claim; add the `find … \! -user` detect + `chown -R` remediation per non-root data subdir
  - [ ] `provision.sh` `chown_if_needed()`: add the bounded `find "$dir" \! -user "${owner%%:*}" -print -quit` second-tier check; keep the top-level fast path; document the two tiers
  - [ ] `bash -n deploy/provision.sh` + `shellcheck` → no new findings
  - [ ] Dev Agent Record: hand-trace first-provision / fully-correct-re-run / child-wrong-re-run
- [ ] **Task 4: Volume device resolution (AC: #4)**
  - [ ] `provision.sh` section 7: resolve `VOLUME_DEVICE` from `/dev/disk/by-id/scsi-0HC_Volume_*` (prefer `HETZNER_VOLUME_ID` exact match if available), `readlink -f`, `/dev/sdb` fallback with a `log` line
  - [ ] `FSTAB_ENTRY` uses the stable `by-id` path; `grep -qF` idempotency still matches; `nofail` kept
  - [ ] all section-7 idempotency guards re-verified against the new identifier
  - [ ] `bash -n` + `shellcheck` → no new findings; Dev Agent Record: 3-case resolution hand-trace
- [ ] **Task 5: Pre-Volume data migration (AC: #5)**
  - [ ] `provision.sh` section 7 Volume-present branch: detect non-empty root-disk `${MOUNT_POINT}`; on first Volume mount → `rsync -aHAX --numeric-ids` onto the Volume, verify `traefik/acme.json` mode + `redis/` owner, remove the root-disk copy
  - [ ] "Volume already had data" case → loud `log` warning, no clobber, proceed with Volume contents
  - [ ] section-7 header comment + no-Volume warning text updated
  - [ ] `first-time-setup.md`: Volume-attach step notes the migrate-on-re-run behaviour + the warning path
  - [ ] `bash -n` + `shellcheck` → no new findings; Dev Agent Record: 3-case hand-trace
- [ ] **Task 6: CI `:latest` ordering guard (AC: #6)**
  - [ ] `ci.yml` `build-and-push`: `fetch-depth: 0` on checkout; new step reads published `:latest` `org.opencontainers.image.revision` (tolerate missing tag), computes `git merge-base --is-ancestor`, emits the tag list to `$GITHUB_OUTPUT`
  - [ ] `docker-build` step consumes the computed `tags`; SHA tag always included; `:latest` only when descendant/first-publish; `::notice::` on skip
  - [ ] `.github/actions/docker-build/action.yml` untouched; `pr-build.yml` untouched
  - [ ] `actionlint` (or YAML parse) clean; Dev Agent Record: 3-case walk-through
- [ ] **Task 7: Ledger hygiene (AC: #7)**
  - [ ] Append the seven `[CLOSED …]` annotations per AC7 (deferred-85 ×3, deploy-1-5 ×1, uat-2 Group D ×1, deferred-23 ×1, cross-drill ×4, deferred-10 ×2)
  - [ ] Record every edited line number in the Dev Agent Record
  - [ ] Confirm no other `deferred-work.md` item was touched

## Dev Notes

### Source ledger mapping

| AC | `deferred-work.md` source |
|----|---------------------------|
| AC1 | `## Deferred from: code review of skillars-deferred-85 …` (2026-08-31) — "Single-part ETag/MD5 compare can hard-fail a good <8 MB backup upload under bucket encryption." Deliberately reverses `deferred-85` AC4's spec-mandated `exit 1`. |
| AC2 | Same section — "`head-object` verification read fires immediately after `aws s3 cp` with no retry/backoff." |
| AC3 | Same section — "`first-time-setup.md`'s 'If `provision.sh` fails partway through' section over-promises `chown -R` idempotency for the partial-completion case." |
| AC4 | `## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)` — "`/dev/sdb` hardcoded device path unreliable on multi-volume servers." (The same section's "doc accuracy fix is a patch (see F2); fixing the script is Story 1.1 territory" hedge is superseded — this story fixes the script.) |
| AC5 | `## Deferred from: code review of skillars-uat-2-session-duration-and-booking-slot-integrity — Group D (2026-08-11)` — "provisioned without volume, then volume attached later" strands `acme.json` on the root disk under the new mount. |
| AC6 | `## Deferred from: code review of skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)` — "AC5's `concurrency` group doesn't guarantee the newest-pushed commit wins `:latest`." Logged there as explicitly out of scope; this is that follow-up. |
| AC7 | Ledger hygiene: `deferred-85` ×3 (AC1-3 sources), `deploy-1-5` `/dev/sdb`, `uat-2` Group D, `deferred-23` `:latest` — closed by this story. Plus stale, found during creation: `skillars-4-3` W2 / `skillars-6-1` Def14 / `skillars-deferred-22` ×2 (cross-drill `videoId` lock — closed by `skillars-deferred-81` AC3), `skillars-deferred-10` D5 (Docker layer cache — already in `docker-build` action) + D6 (Hetzner-outage claim — already softened in `first-time-setup.md`). |

### Project-owner decisions folded in (2026-08-31)

- **Module priority**: SLU/Radar and Drills/Session-Builder are mined out of decision-light bundleable work (the Drills anchor — cross-drill `videoId` lock — was found already shipped-unannotated by `skillars-deferred-81` AC3). Advance to **Deploy/Infra** — chosen from the four options presented: "Deploy/Infra hardening bundle."
- **AC1 reversal is approved**: downgrading the single-part-ETag `exit 1` (`skillars-deferred-85` AC4) to a warning was named explicitly in the chosen bundle. `ContentLength` is the authoritative upload-integrity gate.
- **Items deliberately NOT bundled** (surfaced, set aside as needing their own design decision): the Grafana single-channel empty-receiver residual (`deploy-3-3` / `skillars-deferred-85` AC6 residual — "conditional receiver templating in `grafana-alerts.yml`" needs a templating-mechanism decision, and Grafana file provisioning has no native conditionals); the online-safe migration convention (`skillars-deferred-84` — `V117`/`V118`/`V119` are already applied and immutable, and there is no pending FK/index migration to demonstrate the pattern on, so it degrades to a docs-only item).

### Architecture / conventions to follow

- **No local `mvn verify`** — see `[[feedback_no_local_mvn_verify]]`. This story touches zero `src/main` / `src/test` Java, so CI is exercising only the unchanged build; the shell/YAML/doc changes are verified by `bash -n`, `shellcheck` (no-new-findings vs. a saved baseline), `actionlint`/YAML parse, and hand-traces recorded in the Dev Agent Record. This matches the established verification path for `deploy/**` changes (`skillars-deferred-85` AC3-AC7, `skillars-uat-6` AC5/AC6).
- **`deploy/backup/*.sh` per-script duplication is deliberate** — `skillars-deferred-24` AC4 (logged in `deferred-work.md`) established that small guards are duplicated per caller script, not factored into `env-guard.sh`. AC2's `head_object_content_length` helper stays local to each script.
- **`set -euo pipefail` is universal in `deploy/backup/*.sh` and `provision.sh`** — every command substitution whose failure must not abort the script is `|| true`-guarded, and that guarding must be preserved / extended, not removed (`skillars-deferred-85` AC3/AC6 dev notes).
- **`provision.sh` is idempotent and re-run-safe by contract** — every change in AC3/AC4/AC5 must keep that. First-provision behaviour must not change (fresh dirs, no Volume, etc.).
- **CI tag contract** — the `sha-<short>` tag is immutable and pinned by `docs/deployment/rollback.md`; it is always published. Only `:latest` is an "addition for routine redeploys" (`ci.yml:78-79` comment) and only it is gated by AC6.
- **`.github/actions/docker-build/action.yml` stays a dumb "push these tags" composite action** — AC6's logic lives in `ci.yml`.

### Files being modified — current state

- **`deploy/backup/pg-backup.sh`** (99 lines) — `pg_dump` a running postgres container, gzip to `/tmp`, `aws s3 cp` to Hetzner Object Storage, then verify: `ContentLength` match (hard `exit 1`), then a `case` on the ETag — `""|None` / `*-*` informational, `*)` does `md5sum` compare with **`exit 1`** on mismatch. `trap 'rm -f "${DUMP_FILE}"' EXIT`. AC1 changes the `*)` `exit 1` → warn; AC2 wraps the `ContentLength` `head-object` in a retry loop. Must preserve: the `ContentLength` hard gate, the `trap`, all `|| true` guards, the `require_env_vars` guard.
- **`deploy/backup/volume-backup.sh`** (104 lines) — `tar -czf` of `/opt/skillars/data` minus `postgres/`, same upload + same verification block (`volume-backup.sh:64-102`). Same AC1/AC2 edits. Must preserve: the `TAR_STATUS` `>=2` vs `==1` handling, `cleanup() { rm -f … }; trap cleanup EXIT`.
- **`deploy/provision.sh`** — `chown_if_needed()` (line ~23-30, top-level-owner fast path only); section 7 "Hetzner Volume mount" (line ~158-204, `VOLUME_DEVICE="/dev/sdb"`, `FSTAB_ENTRY`, format/mount/fstab, then `mkdir -p` + `chown_if_needed` for `prometheus`/`loki`/`tempo`/`grafana`, `else` warning block); section 7.5 (line ~206-247, `redis/` + `traefik/acme.json` on both branches); section 8 alert-routing check. AC3 hardens `chown_if_needed`; AC4 rewrites device resolution in section 7; AC5 adds the pre-Volume migration inside section 7's mount branch. Must preserve: section 7.5 running on both branches, section 8 running last, every idempotency guard, the `acme.json` symlink refusal.
- **`docs/deployment/first-time-setup.md`** — Step 3 (`provision.sh`, `ufw` allows 22/80/443 before enable — line ~91); "If `provision.sh` fails partway through" section (line ~119-120, the `chown -R` "already mitigated" claim — AC3 corrects); the Volume-attach step (AC5 adds the migrate-on-re-run note); the "Defence in depth" callout (line ~130, already softened re: Hetzner outage — AC7 only records it as closed, no edit).
- **`.github/workflows/ci.yml`** (86 lines) — `test` job (`mvn -B verify`, `~/.m2` cache, upload reports) then `build-and-push` (`needs: test`, `concurrency: build-and-push-${{ github.ref }}` / `cancel-in-progress: false`, GHCR login, compute short SHA, `./.github/actions/docker-build` with `push: true` and tags `sha-<short>` + `latest`). AC6 inserts an ancestor-check step and makes the `tags` input conditional.
- **`_bmad-output/implementation-artifacts/deferred-work.md`** — AC7 appends `[CLOSED …]` tags to 11 bullets across 7 sections. No other edits.

### Files being created

None. (AC2's `head_object_content_length` is a function inside each existing script, not a new file.)

### Project Structure Notes

- All changes are under `deploy/`, `docs/deployment/`, `.github/workflows/`, and `_bmad-output/`. Zero `src/**` changes → no migration number consumed (max is `V119`), no Spring context impact (`missCount` unchanged), no Java compile surface touched.
- `.github/actions/docker-build/action.yml` is intentionally **not** in the modify list — AC6 is `ci.yml`-only.

### References

- [Source: `deploy/backup/pg-backup.sh:56-97`] — post-upload verification block: `ContentLength` gate then ETag `case`.
- [Source: `deploy/backup/volume-backup.sh:64-102`] — identical verification block.
- [Source: `deploy/provision.sh:23-30`] — `chown_if_needed()` top-level-only owner check.
- [Source: `deploy/provision.sh:158-204`] — section 7 Volume mount, `VOLUME_DEVICE="/dev/sdb"`, `FSTAB_ENTRY`, the no-Volume `else` warning.
- [Source: `deploy/provision.sh:206-247`] — section 7.5, `redis/` + `traefik/acme.json` created on both branches (the strand-on-root-disk source for AC5).
- [Source: `docs/deployment/first-time-setup.md:119-120`] — the `chown -R` "already mitigated" over-promise (AC3).
- [Source: `docs/deployment/first-time-setup.md:130`] — the "Defence in depth" callout, already softened re: Hetzner outage (AC7 records `deferred-10` D6 closed).
- [Source: `.github/workflows/ci.yml:49-86`] — `build-and-push` job: `needs: test`, `concurrency` on `github.ref`, tags `sha-<short>` + `latest`.
- [Source: `.github/actions/docker-build/action.yml:49-50`] — `cache-from: type=gha` / `cache-to: type=gha,mode=max` (AC7 records `deferred-10` D5 closed).
- [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:80-165`] — `initiateUpload` + `deleteVideo` both take `videoRepository.findByIdForUpdate(videoId)` in the `lockRetryer.withBoundedRetry` block, Drill→Video lock order documented (AC7 records the cross-drill cluster closed by `deferred-81` AC3).
- [Source: `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java:26-33`] — `findByIdForUpdate` `@Lock(PESSIMISTIC_WRITE)`, "Deferred-64 AC3".
- [Source: `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java:287-402`] — `deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_…` + the `deferred-83` AC2 lock-causality test.
- [Source: `deferred-work.md`] — `## Deferred from: code review of skillars-deferred-85 …` (2026-08-31); `## Deferred from: code review of deploy-1-5-… (2026-06-03)`; `## Deferred from: code review of skillars-uat-2-… — Group D (2026-08-11)`; `## Deferred from: code review of skillars-deferred-23-… (2026-08-14)`; `## Deferred from: code review of skillars-deferred-10 (2026-07-02)`; `skillars-4-3` W2; `skillars-6-1` Def14; `skillars-deferred-22`.

## Dev Agent Record

### Agent Model Used

_TBD by dev-story_

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-08-31 | 0.1 | Story created via story-creation process. Deploy/Infra priority slot (SLU/Radar + Drills/Session-Builder mined out; Drills anchor found already-shipped by deferred-81 AC3). 7 ACs: AC1 backup single-part-ETag false-fail → warn (reverses deferred-85 AC4); AC2 head-object read-after-write retry loop; AC3 chown -R partial-completion doc + provision.sh hardening; AC4 Hetzner Volume device resolution via /dev/disk/by-id; AC5 pre-Volume data migration onto the Volume on re-run; AC6 CI :latest ancestor-check ordering guard; AC7 six-item ledger hygiene (3 deferred-85 residuals + deploy-1-5 + uat-2 Group D + deferred-23 closed by this story; cross-drill videoId lock ×4 + deferred-10 D5/D6 marked stale/already-closed). No src/** changes, no migration, no Spring context impact. Status: ready-for-dev. | Mbah (create-story) |
