# Story skillars-deferred-88: review-moderation stale-verdict epoch guard, `SoftDeleteIT` race-test hardening, `provision.sh` concurrency/fstab/staging safety, CI `:latest` recovery, Grafana single-channel receiver rendering, an observability egress firewall, and Auth/Registration OTP hardening

Status: ready-for-dev

## Story

As the platform operator and as an engineer maintaining the review, deployment and registration paths,
I want (1) `ReviewModerationService` to carry a per-review **moderation epoch** on `ReviewSubmittedEvent` so a slow, in-flight Gemini verdict for a *superseded* review edit can no longer land on top of a fresher edit that shares the same `PENDING` status — the current `PENDING`-only guard has no way to tell two deliveries apart, and the only thing making it unreachable today is the 365-day re-edit rule, which a GDPR/admin re-publish path or a future product change would remove; (2) `SoftDeleteIT.concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` to actually prove the row lock serialises the read-check-write section rather than passing by HTTP-request serialisation, and to drop its tautological `SELECT COUNT(*) … WHERE id = ? AND deleted_at IS NOT NULL` assertion; (3) `provision.sh` to be safe under a second concurrent run (`flock`), to back up `/etc/fstab` before it edits it and to stop its stale-line purge from deleting an operator's *commented-out* alternate entry, to refuse to stage the pre-Volume tree when the root disk cannot hold a second copy (`df` guard) and to refuse to guess the device on a multi-Volume host with no `HETZNER_VOLUME_ID`; (4) `ci.yml` to have a manual `workflow_dispatch` path that force-advances `:latest` after a `master` history rewrite freezes the ancestor check, and to recognise GHCR's `denied` / `name unknown` first-publish responses as "`:latest` absent"; (5) `provision.sh` to render the Grafana `notify-ops` contact point from only the alert channels that are actually configured, so a single-channel deployment stops provisioning a second empty receiver that silently drops every alert routed to it; (6) the Prometheus / Loki / Tempo / Redis / node-exporter containers to lose their unrestricted outbound internet access (none of them need it); and (7) the Auth/Registration paths hardened — `app.ses.enabled` bound strictly so a non-`true`/`false` value fails fast instead of silently leaving SES unwired, a DB-level partial unique index enforcing the "one live phone OTP per user" invariant the three registration services already maintain in code, and `verifyEmail` / `verifyPhone` refusing to advance a `locked` `User`,
so that a review verdict always reflects the content it was computed against, a lock regression in the messaging soft-delete path is caught by a test that fails without the lock, a partially-completed or double-invoked provision no longer corrupts `/etc/fstab` or strands data, `:latest` has a defined recovery path, a one-channel alert setup is not silently muted, a compromised observability container cannot exfiltrate or call home, and a mid-registration account that gets locked or a mistyped `app.ses.enabled` value cannot slip through.

## Story creation context

Per the standing `deferred-work.md` re-mining priority order (`[[project_skillars_release_workflow]]`): **Messaging/Admin/Reviews/Disputes is mined out of decision-light bundleable work — this is the fourth consecutive confirmation.** `skillars-deferred-82` and `skillars-deferred-83` both re-mined it and came up dry; this pass re-read every open bullet touching `platform.messaging` / `platform.admin` / `platform.reviews` / `DisputeService` and found only two genuinely-actionable items, both small:

- **`## Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity` (2026-08-05), D3** — `ReviewModerationService`'s `PENDING`-only guard cannot distinguish a stale in-flight Gemini verdict for a superseded edit from a fresh one. The 2026-08-05 audit note on the bullet says the scenario "is NOT reachable today" behind `ReviewSubmissionService.updateReview`'s 365-day re-edit rule, but explicitly says *keep the item*: "the **design** limitation is real and would become reachable the moment the 365-day edit rule is relaxed or an admin/GDPR path republishes the event, and the guard carries no version or nonce to survive that." **Project-owner decision 2026-08-31: close it now defensively** — add a monotonic epoch to `ReviewSubmittedEvent` + the `reviews.coach_reviews` row and check it under the existing lock. → **AC1**.
- **`## Deferred from: code review of skillars-deferred-16-messaging-moderation-recovery-identity-safety` (2026-08-05), D6** — `SoftDeleteIT.concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` synchronises the *start* of two HTTP round-trips rather than the read-check-write critical section, so it can pass by request serialisation rather than by the lock; its `SELECT COUNT(*) … WHERE id = ? AND deleted_at IS NOT NULL` assertion is additionally tautological over a primary key. Same class of finding the `skillars-deferred-13`, `-15` and `skillars-uat-3` D11 reviews all raised. → **AC2**. Precedent for the fix exists: `DrillUploadServiceConcurrencyIT` (`skillars-deferred-83` AC2) proves lock causality by holding the row lock in an external transaction and asserting the second caller waits.

Everything else in the Messaging/Admin/Reviews/Disputes surface is `DECIDED` (leave as-is), `DISMISSED`, unreachable on the single-instance Docker Compose deployment (`skillars-deferred-16` D4 rolling-deploy, D3 orphaned-profile), an accepted MVP performance tradeoff (`skillars-8-1` D2 N+1 in `getConversations`), an accepted architectural note (`skillars-8-4` W5, `skillars-8-3` W1), or a settled product decision (`skillars-deferred-63` two-sided disputes — "keep first-raiser-wins as final"). Per this project's documented dry-ledger fallback and the explicit "no small stories" instruction, priority advances to **Deploy/Infra** and then **Auth/Registration**, and the project owner chose to bundle broadly:

- **`## Deferred from: code review of skillars-deferred-87-…` (2026-08-31)** — the code review of the story that shipped one release ago left a fresh cluster of concrete `provision.sh` / `ci.yml` residuals:
  - **No `flock` anywhere in `provision.sh`** — two overlapping runs can `rm -rf "${STAGING}"` mid-`rsync` or double-`mount`. → **AC3**.
  - **`provision.sh` edits `/etc/fstab` in place with `sed -i` and no `.bak`** — a boot-critical file modified with no recovery artifact. And the **`sed -i` stale-fstab purge also matches a commented-out `# … /opt/skillars/data …` line** when an active non-canonical line for the same mount point also exists. → **AC4** (two bullets).
  - **Staging `rsync` duplicates the pre-Volume tree on the same root filesystem with no free-space pre-check** — ENOSPC aborts before `mount` and loops on re-run. And a **multi-Volume host without `HETZNER_VOLUME_ID` binds to the lexically-first `scsi-0HC_Volume_*` symlink and would `mkfs.ext4` it** if unformatted. → **AC5** (two bullets).
  - **CI `:latest` freezes permanently after a `master` history rewrite** — the ancestor check returns 1/128 forever with no manual-override path; "a `workflow_dispatch` 'force-publish :latest' escape hatch would be a separate follow-up." And **first-ever `:latest` publish where GHCR returns `denied` / `name unknown`** instead of `not found` / `MANIFEST_UNKNOWN` falls through to the generic "read failed → SHA-only" branch and `:latest` is not created until a later run. → **AC6** (two bullets).
- **`## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules` (2026-06-05)** — the `skillars-deferred-85` AC6 residual: *"a single-channel deployment still provisions one empty notify-ops receiver that silently no-ops — the fuller fix is conditional receiver templating in `grafana-alerts.yml`, not done here."* `grafana-alerts.yml` hard-codes both an `email` and a `slack` receiver under the `notify-ops` contact point and Grafana file provisioning has no conditionals. **Project-owner decision 2026-08-31: fold it in — `provision.sh` renders the receiver list.** → **AC7**.
- **`## Deferred from: code review of deploy-1-5-first-time-setup-documentation` (2026-06-04)** — *"No outbound firewall rules — observability containers (Prometheus, Loki, Tempo, Redis) have unrestricted internet egress; security hardening enhancement."* `skillars-deferred-85` deferred the egress firewall "to its own design story"; **project-owner decision 2026-08-31: fold it in here.** → **AC8**.
- **`## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification` (2026-06-11), W3 / Group A D5 / Group D D3** — three Auth/Registration hardening items the project owner selected (2026-08-31):
  - **W3** — `SesConfig`'s `@ConditionalOnProperty(name = "app.ses.enabled", havingValue = "true", matchIfMissing = false)` requires the literal string `true`; `SesProperties.enabled` defaults to `true`. An unrecognised value (`enabled: yes`, `True `, a stray space) silently leaves `SesV2Client` unwired at startup while `SesProperties.isEnabled()` still reports `true`. → **AC9**.
  - **Group A D5** — `phone_otp_tokens` has no partial unique index on active OTPs. All three registration services (`CoachRegistrationService:138`, `PlayerRegistrationService:153`, `ParentRegistrationService:142`) already call `otpTokenRepository.deleteByUserIdAndUsedFalse(...)` immediately before inserting a new token, so the invariant is maintained in code — but nothing enforces it at the DB, and two concurrent OTP requests for the same user can each delete-then-insert and both commit. → **AC10** (DB backstop + concurrent-request conflict handling).
  - **Group D D3** — a `SUSPENDED` / locked user in the `EMAIL_VERIFIED` state can still complete phone OTP. At review time "no suspension code exists yet"; `User.locked` now exists and `LoginAttemptsService` sets it. `verifyEmail` / `verifyPhone` in all three registration services check `verificationStatus` but not `user.isLocked()`. → **AC11**.

**Twelve ACs across the reviews module, one messaging IT, `provision.sh`, `ci.yml`, `docker-compose.yml`, `grafana-alerts.yml`, the SES config, three registration services and two Flyway migrations — comfortably past this project's "no small stories" bar. Two migrations (`V120`, `V121`), both small-table and additive; one new Spring context is *not* expected (all new tests reuse existing IT base classes — confirm during dev).**

## Acceptance Criteria

### AC1 — `ReviewModerationService` discards a verdict computed for a superseded review edit, via a monotonic moderation epoch.

**Problem** (verified against source at `5efc0f0`): `ReviewModerationService.handleReviewSubmitted` (`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java`) runs the Gemini call outside any transaction, then in a `REQUIRES_NEW` transaction does `reviewRepository.findByIdForUpdate(reviewId)` and writes the verdict **only if `review.getModerationStatus() == PENDING`**. `ReviewSubmissionService.submitReview` (`:63,:72`) and `.updateReview` (`:98,:103`) are the only publishers of `ReviewSubmittedEvent` (`src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewSubmittedEvent.java` — a 5-field record: `reviewId, coachId, authorId, rating, body`), and both set `PENDING` immediately before publishing. If a review is edited again while the previous edit's Gemini call is still in flight, both deliveries race the same `PENDING` status with nothing to tell them apart — the slower Gemini call (possibly evaluating stale, already-overwritten body text) can write its verdict, and the fresher delivery then sees a non-`PENDING` status and discards *itself*. Unreachable today only because `updateReview:82-86` rejects any edit within 365 days of the last one; a GDPR/admin re-publish path or a relaxed edit rule opens it.

**Fix:**

- **`V120__coach_reviews_moderation_epoch.sql`** — `ALTER TABLE reviews.coach_reviews ADD COLUMN moderation_epoch BIGINT NOT NULL DEFAULT 0;`. Small table, additive, `DEFAULT 0` backfills every existing row in one pass — no `NOT VALID`/`VALIDATE` split needed at this size (note the `skillars-deferred-84` code-review deferral about online-safe migration convention; this column is `DEFAULT`-backfilled and takes only a brief `ACCESS EXCLUSIVE` on a tiny table, so it is in the same accepted class as `V117`/`V118`). No index — the column is only ever read via the already-locked `findByIdForUpdate` row.
- **`CoachReview` entity** (`src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReview.java`) — add `@Column(name = "moderation_epoch", nullable = false) private long moderationEpoch = 0L;` (primitive `long`, mirrors the entity's existing plain-field style; `@Getter`/`@Setter` are class-level via Lombok).
- **`ReviewSubmissionService`:**
  - `submitReview` — a newly-created review keeps `moderationEpoch = 0` (the default). Publish `new ReviewSubmittedEvent(reviewId, coachId, authorId, rating, body, review.getModerationEpoch())` — i.e. `0`.
  - `updateReview` — **increment** `review.setModerationEpoch(review.getModerationEpoch() + 1)` right where it already does `review.setModerationStatus(PENDING)` (`:98`), before the `save`/publish, so the persisted row and the event agree. Publish the same 6-arg event carrying the *new* epoch.
  - Both publish sites must pass the epoch read **after** the increment/`setModerationStatus` and from the same `review` instance that is saved in that transaction.
- **`ReviewSubmittedEvent`** — add `long moderationEpoch` as the 6th record component. Grep for every constructor call (`grep -rn "new ReviewSubmittedEvent(" src`) — the two production sites above plus any test builders — and update each. Keep the component name `moderationEpoch`.
- **`ReviewModerationService.handleReviewSubmitted`** — inside the `findByIdForUpdate(...).ifPresentOrElse(review -> { … })` block, **before** the existing `current != PENDING` check, add:
  ```java
  if (review.getModerationEpoch() != event.moderationEpoch()) {
      log.warn("ReviewModerationService: review {} epoch moved {} -> {} since this verdict was "
              + "requested — discarding stale moderation verdict {}",
          reviewId, event.moderationEpoch(), review.getModerationEpoch(), finalStatus);
      return;
  }
  ```
  The epoch check is first because it is the more specific signal: a stale-epoch delivery must be dropped even if the row is still `PENDING` (the fresher edit re-set it to `PENDING` with a higher epoch). The existing `PENDING` guard stays exactly as it is for the admin-decision / flag-threshold / duplicate-delivery cases its own comment enumerates. Update that comment to name the new epoch guard as the "superseded edit" case it previously could not cover (this is the exact gap `skillars-deferred-14` D3 recorded).
- **Do not** change `CoachRatingService.recompute` semantics, the `HeldReason` mapping, the `REQUIRES_NEW`/`AFTER_COMMIT` structure, or the outer `catch` that swallows to prevent a 500.

**Tests:**
- New `ReviewModerationServiceTest` case (or extend the existing one if present — `grep -rn "ReviewModerationServiceTest\|ReviewModerationIT" src/test`): seed a `PENDING` review at `moderationEpoch = 1`, deliver a `ReviewSubmittedEvent` carrying `moderationEpoch = 0` (the superseded edit's epoch), assert the row is **unchanged** (`moderationStatus` still `PENDING`, no `recompute` call) and the "discarding stale moderation verdict" warning fired. Mutation check: remove the epoch guard → the stale verdict overwrites the row → test fails.
- Extend the existing "fresh verdict lands on PENDING" case to seed `moderationEpoch = 0` on both the row and the event so it still passes (proves the guard is transparent to the normal path).
- If a `ReviewModerationIT` exists that drives the real `AFTER_COMMIT` listener, add an epoch-mismatch case there too; otherwise a service-level test is sufficient (mirrors how `skillars-deferred-16` AC1 was pinned).
- `grep -rn "new ReviewSubmittedEvent(" src/test` — every test builder compiles with the new arg.

### AC2 — `SoftDeleteIT.concurrentDoubleSoftDelete…` proves the lock, and drops the tautological assertion.

**Problem** (`src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java:246-289`): the test starts two `DELETE` HTTP calls on one `startLatch`, then asserts `successCount == 1` / `conflictCount == 1`. The latch releases both threads at *request start* (TCP, filter chain, controller dispatch) — not at `MessagingService.softDeleteMessage`'s locked-read → `deletedAt` check → write critical section (`src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:296-330`), so the test can pass by request serialisation. The trailing `SELECT COUNT(*) FROM messaging.messages WHERE id = ? AND deleted_at IS NOT NULL` → `isEqualTo(1)` is tautological: over a primary key it is 0 or 1, and given `successCount == 1` it is always 1.

**Fix** — mirror `DrillUploadServiceConcurrencyIT` (`skillars-deferred-83` AC2)'s external-lock technique:

- Rewrite the test so the race is exercised deterministically: open a **separate transaction** (a `TransactionTemplate` / `@Autowired PlatformTransactionManager`, or the existing helper if `SoftDeleteIT`'s base class has one) that does `SELECT … FOR UPDATE` on the target `messaging.messages` row and **holds** it; then fire a single `DELETE` HTTP call on a background thread; assert it is still blocked (has not returned) after a short wait; release the external lock; assert the call then completes `204`. Then fire a **second** `DELETE` and assert it returns `409` with body error key `messaging.alreadyDeleted` (the losing-caller outcome the lock+guard produce). This proves the request genuinely waits on the row lock.
- Keep a lighter version of the original two-thread race (both on a barrier) as a second assertion if useful, but it is no longer the primary guard.
- **Replace** the tautological `SELECT COUNT(*) … deleted_at IS NOT NULL` assertion with meaningful ones: (a) the losing caller's response carries `messaging.alreadyDeleted`; (b) exactly one row now has `deleted_at IS NOT NULL` **and** `deleted_at` is stable across a re-read (i.e. the second caller did not overwrite it) — assert on the concrete `deleted_at` value, or assert the winning caller's `deleted_at` equals the row's current `deleted_at`.
- Do **not** add `@Version` to `Message` (the code comment at `MessagingService.java:315-317` and `skillars-deferred-16`'s Dev Notes both forbid it — `ModerationResultApplier` / `AdminMessageService` / `MessageModerationSweeper` all `save()` this row).
- Mutation check to record in the Dev Agent Record: revert `softDeleteMessage`'s `findByIdForUpdate` to `findById` → the "still blocked after a short wait" assertion fails (no lock to block on) → test fails. This is the durable guard the old test lacked.

**Tests:** the rewritten `SoftDeleteIT` case itself. Run `-Dtest=SoftDeleteIT` green; `grep` confirms no new `*IT` convention violation (it is already an `*IT`).

### AC3 — `provision.sh` is safe under a concurrent second run (`flock`).

**`deploy/provision.sh`** — the script has no locking; section 7 (Volume resolve/stage/mount/fstab/migrate) and `settle_pre_volume_migration` can be run twice concurrently (two operators, or manual + automation), which can `rm -rf "${STAGING}"` mid-`rsync` or double-`mount`/`mkfs.ext4` the same device.

- At the **top of the script** (right after the `set -euo pipefail` / helper-function block, before section 1), acquire an exclusive `flock` on a fixed path (`/var/lock/skillars-provision.lock` — create it with `: >` / `install -m 0644` if absent). Use the self-re-exec idiom so it works when the script is piped or run directly:
  ```sh
  LOCK_FILE="/var/lock/skillars-provision.lock"
  if [ "${_PROVISION_LOCKED:-}" != "1" ]; then
    exec env _PROVISION_LOCKED=1 flock --exclusive --nonblock "${LOCK_FILE}" "$0" "$@"
  fi
  ```
  `--nonblock` so a second invocation **fails fast** with a clear message ("another provision.sh is already running (holds ${LOCK_FILE}); refusing to run concurrently") rather than hanging — `flock`'s own non-zero exit on a held lock, caught and rephrased. If `flock` is not installed, `apt-get install -y util-linux` already provides it on the CX32 base (confirm `command -v flock`; it is part of `util-linux`, always present on Ubuntu) — no new package.
- The lock must cover the **whole** script (idempotent re-runs included) so a scheduled re-provision cannot overlap a manual one at any section.
- Document at the acquisition point: this is a coarse whole-script lock, deliberately not per-section; concurrent `provision.sh` is not a supported scenario and the lock exists to make that explicit rather than to enable parallelism.

**Verification:** `bash -n`; `shellcheck` no-new-findings vs. the saved baseline; Dev Agent Record hand-trace: (a) first run acquires the lock, re-execs once, proceeds; (b) a second run while the first holds the lock → `flock --nonblock` exits non-zero → the wrapper prints the refusal message and exits non-zero, first run unaffected; (c) `_PROVISION_LOCKED=1` already set (the re-exec'd child) → skips re-acquisition, runs the body.

### AC4 — `provision.sh` backs up `/etc/fstab` before editing it, and its stale-line purge ignores commented-out lines.

**`deploy/provision.sh`** section 7 fstab-maintenance block (`:333-350`):

- **Before the `sed -i` purge at `:341`**, `cp -p /etc/fstab "/etc/fstab.bak.$(date +%Y%m%d%H%M%S)"` (timestamped so repeated runs do not clobber the first backup), and `log` the backup path. `date` is available; no `set -e` hazard (`cp` of an existing file). Keep at most the last few backups is out of scope — a bare timestamped copy is the ask.
- **The purge match must exclude commented-out lines.** Current: `grep -vFx "${FSTAB_ENTRY}" /etc/fstab | grep -qE "[[:space:]]${MOUNT_POINT}[[:space:]]"` then `sed -i "\#[[:space:]]${MOUNT_POINT}[[:space:]]#d" /etc/fstab`. Both the `grep -qE` guard and the `sed` address must additionally require the line **not** start with optional whitespace then `#`:
  - guard: `grep -vFx "${FSTAB_ENTRY}" /etc/fstab | grep -E "[[:space:]]${MOUNT_POINT}[[:space:]]" | grep -qvE '^[[:space:]]*#'`
  - `sed`: switch to an address that anchors on a non-comment device field, e.g. `sed -i -E "\#^[[:space:]]*[^#[:space:]][^[:space:]]*[[:space:]]+${MOUNT_POINT}[[:space:]]#d" /etc/fstab` (device field is a real token, not `#…`). Pick one consistent regex and use it in both the guard and the delete so they cannot diverge; document the intent ("delete only an **active** non-canonical line for ${MOUNT_POINT}; never an operator's commented-out alternate").
- The existing "add if not `grep -qF "${FSTAB_ENTRY}"`" block and the trailing-newline guard (`:348`) stay unchanged.

**Verification:** `bash -n`; `shellcheck` no-new-findings; Dev Agent Record hand-trace: (a) `/etc/fstab` with an active `/dev/sdb /opt/skillars/data …` line → backup written, line purged, by-id entry added; (b) `/etc/fstab` with **only** a commented `# /dev/sdb /opt/skillars/data …` line and the canonical by-id line already present → guard's `grep -qvE '^[[:space:]]*#'` fails → no purge, no spurious add; (c) `/etc/fstab` with both an active non-canonical line **and** a commented alternate → only the active line is deleted, the comment is preserved; (d) no `/opt/skillars/data` line at all → backup still written (harmless), nothing purged, by-id entry appended.

### AC5 — `provision.sh` refuses to stage without room, and refuses to guess the device on a multi-Volume host.

**`deploy/provision.sh`** section 7:

- **Free-space guard before the staging `rsync`** (`:297-298`, `rsync -aHAX --numeric-ids "${MOUNT_POINT}/" "${STAGING}/"`): before `mkdir -p "${STAGING}"`, compute the size of the pre-Volume payload (`du -sk "${MOUNT_POINT}"` → KiB) and the free space on the filesystem that will hold `${STAGING}` (`df -Pk --output=avail "${DEPLOY_ROOT}" | tail -1`, or `stat -f` — pick the portable one, `df -Pk` is fine on Ubuntu). If `payload_kb * 2 > avail_kb` (×2 headroom — the copy plus rsync temp files), **`err` and `exit 1`** with a message naming both numbers and the fix ("free space on the root disk or attach the Volume before the tree grows"). This runs only when the migration is armed (`pre_volume_payload_present` true), so a from-scratch host is unaffected.
  - Both captures `|| true`-guarded per the `set -euo pipefail` convention (`skillars-deferred-85` AC3/AC6 precedent); an empty/failed `df` read → skip the guard with a `log` line rather than abort (fail-open on the *guard*, since the real failure mode — ENOSPC during `rsync` — still surfaces).
- **Multi-Volume device resolution** (`:193-202`, the `/dev/disk/by-id/scsi-0HC_Volume_*` resolution added by `skillars-deferred-87` AC4): after globbing the `by-id` symlinks, if **more than one** `scsi-0HC_Volume_*` symlink exists **and** `HETZNER_VOLUME_ID` is unset/empty, **`err` and `exit 1`**: "N Hetzner Volumes are attached and HETZNER_VOLUME_ID is not set — refusing to guess; export HETZNER_VOLUME_ID=<id> (the digits after `scsi-0HC_Volume_`) and re-run." The existing single-symlink path and the `HETZNER_VOLUME_ID`-set exact-match path are unchanged; the existing `err` warning for "`HETZNER_VOLUME_ID` set but its symlink absent" (added by `skillars-deferred-87` code review) is unchanged. The `/dev/sdb` fallback still applies only when **zero** `by-id` symlinks exist.

**Verification:** `bash -n`; `shellcheck` no-new-findings; Dev Agent Record hand-trace: (a) armed migration, payload 200 MB, root free 5 GB → guard passes, stages; (b) armed, payload 40 GB, root free 30 GB → `err` + `exit 1`, no `mkdir`/`rsync`; (c) `df` read returns empty → guard skipped with a `log` line, stage proceeds; (d) one `by-id` symlink → resolves as today; (e) two `by-id` symlinks, `HETZNER_VOLUME_ID` unset → `err` + `exit 1`; (f) two symlinks, `HETZNER_VOLUME_ID=12345` matching one → resolves to that one (unchanged).

### AC6 — CI has a manual `:latest` recovery path, and recognises GHCR's first-publish `denied`/`name unknown`.

**`.github/workflows/ci.yml`** — the `build-and-push` job's "Decide image tags (:latest ordering guard)" step (added by `skillars-deferred-87` AC6):

- **`workflow_dispatch` force-publish.** Add `workflow_dispatch:` to the workflow's `on:` (alongside the existing `push: { branches: [master] }`) with a boolean input `force_publish_latest` (default `false`). In the guard step, **before** the `imagetools`/`merge-base` logic, if `github.event_name == 'workflow_dispatch' && inputs.force_publish_latest == 'true'` → set the tag list to `sha-<short>` **and** `latest` unconditionally, `echo "::notice::force_publish_latest=true — publishing :latest for $GITHUB_SHA without the ancestor check"`, and skip the rest of the guard. This is the manual override for when a `master` history rewrite has frozen `:latest` (the ancestor check returns 1/128 forever). Document in a comment that this is the sanctioned recovery for that specific scenario.
  - `workflow_dispatch` on `ci.yml` runs against the ref it is dispatched for; the run still needs `test` to pass (`build-and-push` keeps `needs: test`). No change to `pr-build.yml`.
- **Widen the "`:latest` absent" detection.** In the `if [ "${inspect_rc}" -ne 0 ]` branch, the current `grep -qiE 'not ?found|MANIFEST_UNKNOWN|manifest unknown'` misses GHCR's brand-new-package responses `denied` and `name unknown`. Extend to `grep -qiE 'not ?found|manifest ?unknown|MANIFEST_UNKNOWN|name ?unknown|denied'` so a first-ever publish is treated as "`:latest` absent → publish both tags" instead of falling to the generic "read failed → SHA-only" branch. Keep the generic branch for any *other* read failure (network, auth on an existing package). Add a one-line comment that `denied`/`name unknown` are GHCR's phrasings when the package does not exist yet.
- The `set +e` / explicit-`$?`-capture / never-abort contract from `skillars-deferred-87` AC6 is preserved. `action.yml` untouched.

**Verification:** this workflow runs only on `push` to `master` and now `workflow_dispatch` — not exercisable from a PR. (a) `actionlint .github/workflows/ci.yml` clean (+ `pr-build.yml` clean, untouched); (b) YAML parse; (c) Dev Agent Record walk-through: `workflow_dispatch` + `force_publish_latest=true` → both tags, no `imagetools` call; normal `push`, `:latest` ancestor of HEAD → both tags (unchanged); `push`, GHCR returns `denied` on a fresh repo → treated as absent → both tags; `push`, `imagetools` network error on an existing `:latest` → SHA-only + notice (unchanged). Confirm on the first post-merge `master` run that the widened grep did not change behaviour for the now-existing `:latest` (it should still take the success path and read the revision label).

### AC7 — `provision.sh` renders the Grafana `notify-ops` contact point from only the configured channels.

**Problem:** `deploy/lgtm/grafana-alerts.yml`'s `contactPoints[0]` (`notify-ops`) hard-codes **both** a `notify-ops-email` receiver (`addresses: "${GF_ALERT_NOTIFY_EMAIL}"`) and a `notify-ops-slack` receiver (`url: "${GF_SLACK_WEBHOOK_URL}"`). When only one channel is configured, the other provisions with an empty target and **silently drops every alert routed to it** (Grafana routes to the whole contact point; a receiver with no address/url fails per-notification with only a Grafana-internal log). `skillars-deferred-85` AC6 made `provision.sh` **warn** about this; it did not fix it. Grafana file provisioning has no conditionals, so the fix is to generate the receiver list.

**Fix** — `provision.sh` section 8 (alert-routing, `:423+`), **after** the existing validation/warn block and **before** Grafana would read the file:

- Treat `deploy/lgtm/grafana-alerts.yml` as having a **static rules/policies head** and a **generated `contactPoints` block**. Options, pick the one that fits the repo's existing style (confirm during dev):
  1. Split `grafana-alerts.yml` into `grafana-alerts.rules.yml` (everything up to `# ── Contact Points ──`) committed as-is, and have `provision.sh` concatenate it with a generated `contactPoints:` + `policies:` tail into the path the container bind-mounts (`docker-compose.yml:234` maps `./deploy/lgtm/grafana-alerts.yml` → `/etc/grafana/provisioning/alerting/alerts.yml:ro`). **Preferred** — keeps the generated file a build artifact, not a committed one.
  2. Keep one committed `grafana-alerts.yml` with a clearly delimited `# >>> GENERATED CONTACT POINTS >>>` … `# <<< END GENERATED <<<` region that `provision.sh` rewrites in place (with a `.bak`, per AC4's spirit).
- The generated `contactPoints[0].receivers` list contains **only**: the `email` receiver iff `GF_ALERT_NOTIFY_EMAIL` is non-empty (and, per the existing warn, `GF_SMTP_ENABLED=true` — if email is set but SMTP is not, still emit the receiver but keep the existing loud warning); the `slack` receiver iff `GF_SLACK_WEBHOOK_URL` is non-empty. The Slack receiver's `title`/`text` templates are copied verbatim from the current file.
- If **both** are empty, the existing `skillars-deferred-85` AC6 hard-fail (`exit 1`) already fires earlier in the section — the renderer is never reached. Keep that.
- If exactly one is set, the `notify-ops` contact point has exactly one receiver and the `policies` block (`receiver: notify-ops`) is unchanged — no empty receiver, no silent drop.
- Idempotent: re-running `provision.sh` regenerates the same file from the same `.env`; a `diff`-quiet no-op on an unchanged `.env`.
- Update `deploy/lgtm/grafana-alerts.yml`'s own header comment and `docs/deployment/*.md` (whichever documents alert routing — `grep -rn "notify-ops\|grafana-alerts" docs/`) to say the receiver list is rendered by `provision.sh` from the configured channels.

**Verification:** `bash -n` on `provision.sh`; `shellcheck` no-new-findings; a YAML parse of the generated file for each of the three reachable cases (email-only, slack-only, both) — `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))"`; Dev Agent Record hand-trace of all three cases plus the re-run no-op. No live Grafana run (project convention — `skillars-deferred-85` AC6, `skillars-uat-6`).

### AC8 — Prometheus / Loki / Tempo / Redis / node-exporter lose outbound internet access.

**Problem:** `docker-compose.yml` puts every service on a single `skillars-internal` bridge network whose own comment says *"Do NOT set `internal: true` — app needs outbound internet for OTLP/email/Bunny.net"*. Prometheus, Loki, Tempo, Redis and node-exporter need **no** egress; a compromised one of them currently has unrestricted internet access.

**Fix — compose-only, no host iptables:**

- Add a second network `skillars-observability` with `internal: true` (no gateway → no NAT → no egress).
- Move `prometheus`, `loki`, `tempo`, `redis`, `node-exporter` **off** `skillars-internal` and **onto** `skillars-observability` only — **unless** a service both needs to be reached from an egress-capable peer and initiates no outbound calls of its own (being *reached* across networks requires shared network membership). Concretely:
  - `redis` — reached by `app`. Put `redis` on `skillars-observability`; add `skillars-observability` to `app`'s network list so `app` can reach it. `redis` itself gets no egress.
  - `loki` / `tempo` — reached by `app` (log/trace push) and `grafana` (queries). Put both on `skillars-observability`; add `skillars-observability` to `app` and `grafana`. Neither Loki nor Tempo needs egress.
  - `prometheus` — scrapes `app` (`spring-boot-app` job) and `node-exporter`, queried by `grafana`. Put `prometheus` + `node-exporter` on `skillars-observability`; `grafana` already gains that network for loki/tempo; `app` already gains it for redis. `prometheus` gets no egress.
  - `grafana` — needs egress for email/Slack alert delivery → stays on `skillars-internal` **and** joins `skillars-observability`.
  - `app` — stays on `skillars-internal` (egress for OTLP-to-nothing-external/email/Bunny/Stripe) **and** joins `skillars-observability`.
  - `traefik`, `postgres`, backup/cron containers — unchanged on `skillars-internal`.
- Net effect: every service keeps every peer it talks to today (verify each `depends_on` / URL env — `LOKI_URL=http://loki:3100`, `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318`, Prometheus scrape config, Grafana datasource provisioning), and Prometheus/Loki/Tempo/Redis/node-exporter can no longer reach the internet.
- `docker-compose.local.yml` / `docker-compose.uat.yml` — check they still merge (they add their own `volumes:` blocks; confirm they do not pin `networks:` in a way that conflicts — `grep -n "networks:" docker-compose*.yml`). Add the second network there too if those overlays redefine the service network lists.
- Update the `skillars-internal` comment and add one on `skillars-observability` explaining the split. Update `docs/deployment/*.md` where the network topology or the "no egress firewall" gap is described (`grep -rn "skillars-internal\|egress\|outbound" docs/deployment/`).

**Verification:** `docker compose -f docker-compose.yml config` parses and shows each service on the intended networks; `docker compose -f docker-compose.yml -f docker-compose.local.yml config` still parses; Dev Agent Record: a table of every service → networks before/after → every existing peer edge still satisfied (shared network) → the five restricted services have **only** `skillars-observability`. No live deploy (project convention); note in Completion Notes that a post-merge smoke check on the real host should confirm Grafana still renders Prometheus/Loki/Tempo panels and alert delivery still works.

### AC9 — `app.ses.enabled` is bound strictly; a non-boolean value fails fast.

**Problem:** `src/main/java/com/softropic/skillars/infrastructure/ses/SesConfig.java` gates `SesV2Client` on `@ConditionalOnProperty(name = "app.ses.enabled", havingValue = "true", matchIfMissing = false)` (literal `"true"` only), while `src/main/java/com/softropic/skillars/infrastructure/ses/SesProperties.java`'s `enabled` field defaults to `true`. `app.ses.enabled: yes` (or `True `, or a trailing space) → the bean condition does not match → `SesV2Client` is not created → `SesEmailServiceImpl` (which injects it — confirm) is unwired or broken, while `SesProperties.isEnabled()` still returns `true`. Silent in prod (which boots with no active profile).

**Fix:**

- **Fail fast on an unrecognised value.** Add a `@PostConstruct` (or a small `@Bean` `SmartInitializingSingleton` / an `EnvironmentPostProcessor` — pick the lightest that runs at startup) that reads the **raw** `app.ses.enabled` property string via `Environment.getProperty("app.ses.enabled")` and, if it is non-null and not (case-insensitively, trimmed) one of `true` / `false`, throws an `IllegalStateException("app.ses.enabled must be 'true' or 'false', got: '<value>'")` — aborting startup. A `null` (unset) value is allowed and means "use the `SesProperties` default".
- **Align the defaults.** Either set `SesProperties.enabled` default to `false` to match `matchIfMissing = false`, **or** set `@ConditionalOnProperty(..., matchIfMissing = true)` to match the property default of `true`. Choose based on what production actually wants when the key is absent — check `application-prod.yaml` / `application.yaml` for an explicit `app.ses.enabled` (`grep -rn "ses:" src/main/resources/application*.y*ml`). If prod sets it explicitly (likely), the mismatch is only a latent trap; still align them and document the chosen default in a comment on both the field and the annotation so they cannot drift.
- Keep `SesProperties` a plain `@ConfigurationProperties` POJO (Spring's relaxed binding already coerces `true`/`True`/`TRUE`/`yes`/`on` to `boolean true` for the *field* — which is exactly why the field and the `@ConditionalOnProperty` string-match disagree; the new startup check closes that gap by rejecting anything the `@ConditionalOnProperty` would not honour).

**Tests:** a `@SpringBootTest` slice (or a focused context test) asserting: (a) `app.ses.enabled=true` → `SesV2Client` bean present; (b) `app.ses.enabled=false` → absent, context starts; (c) `app.ses.enabled=yes` → context **fails to start** with the `IllegalStateException` message; (d) property unset → context starts, bean presence matches the aligned default. Mirror an existing conditional-bean test if one exists (`grep -rn "ConditionalOnProperty\|SesConfig" src/test`).

### AC10 — a partial unique index enforces "one live phone OTP per user", and concurrent OTP requests conflict cleanly.

**Problem:** `main.phone_otp_tokens` (`V21__skillars_security_extension.sql:23-33`) has only `idx_pot_userid` (non-unique). The three registration services already delete unused tokens before inserting (`CoachRegistrationService:138`, `PlayerRegistrationService:153`, `ParentRegistrationService:142` and `:206`), so the "one live OTP" invariant holds in code — but nothing enforces it, and two concurrent OTP-issue calls for the same user can each `DELETE … WHERE used = false` (seeing nothing under READ COMMITTED) then `INSERT`, and both commit.

**Fix:**

- **`V121__phone_otp_tokens_one_active_per_user.sql`** — `CREATE UNIQUE INDEX IF NOT EXISTS uq_pot_one_active_per_user ON main.phone_otp_tokens (user_id) WHERE used = false;`. Partial predicate is `used = false` only — **not** `expires_at > now()` (`now()` is not `IMMUTABLE` and is rejected in an index predicate). An expired-but-unused row still counts as "the one active row"; the services' delete-before-insert clears it, so this is correct. Non-`CONCURRENTLY` (Flyway runs migrations in a transaction; the table is tiny at this stage — same accepted class as every other index in this codebase's history; note the `skillars-deferred-84` online-migration deferral applies here too and is accepted at this size).
  - Pre-flight in the migration: the table is expected to already satisfy the constraint (services maintain it). If a defensive dedup is wanted, prepend `DELETE FROM main.phone_otp_tokens a USING main.phone_otp_tokens b WHERE a.user_id = b.user_id AND a.used = false AND b.used = false AND a.id < b.id;` (keep the newest unused row per user) so the `CREATE UNIQUE INDEX` cannot fail on legacy data. Include it — cheap insurance, matches `skillars-deferred-57` D-item guidance about `VALIDATE CONSTRAINT` having no remediation path.
- **Concurrent-request conflict handling.** In each of the three `verifyEmail` → OTP-issue paths (the code around `otpTokenRepository.deleteByUserIdAndUsedFalse(...)` + `new PhoneOtpToken()` + `.save(...)`), wrap the `save` so a `DataIntegrityViolationException` on `uq_pot_one_active_per_user` is caught and re-thrown as the existing rate-limit / retry-shaped exception the endpoint already returns (`OtpVerificationException` / a 409-mapped `…Exception` — match what the surrounding code throws for "try again"), with a `log.warn` naming the race. This is a rare path (two near-simultaneous "resend OTP" clicks); the friendly outcome is "an OTP is already being sent, check your email / retry in a moment". Factor the tiny catch helper per-service (the `deploy/backup` per-script-duplication precedent's Java equivalent — the three registration services already duplicate near-identical OTP blocks; do not introduce a shared base class for this).
- Optionally mirror the same partial unique index onto `main.email_verification_tokens (user_id) WHERE used = false` in the **same** `V121` — `resendVerificationEmail` already deletes-before-insert there too, so the invariant already holds; the index is symmetric defense-in-depth. Include it only if it does not break any test fixture (`grep -rn "email_verification_tokens\|EmailVerificationToken" src/test` — check for fixtures that seed two unused rows for one user). If it risks fixtures, scope `V121` to `phone_otp_tokens` only and note email tokens as a follow-up.

**Tests:**
- `@DataJpaTest` / repository IT: inserting a second `used = false` `PhoneOtpToken` for a `user_id` that already has one → `DataIntegrityViolationException`; inserting one where the prior row is `used = true` → succeeds.
- Service test for one of the three flows: two OTP-issue calls for the same user, the second (simulated concurrent, i.e. bypassing the delete via a spy or a second connection) → the caught-and-rethrown friendly exception, not a raw 500.
- `-Dtest` the three `*RegistrationService*` test classes + `PhoneOtpToken` repo tests green.
- Confirm existing registration ITs still pass (they issue one OTP per user per test — the index does not bite them).

### AC11 — `verifyEmail` / `verifyPhone` refuse to advance a locked `User`.

**Problem:** `User.locked` exists (`User.java:71`, set by `LoginAttemptsService` on brute-force lockout and clearable by admin). `CoachRegistrationService.verifyEmail` (`:108-131`) and `.verifyPhone` (`:151-181`), and the `PlayerRegistrationService` / `ParentRegistrationService` equivalents, check `verificationStatus` but never `user.isLocked()`. An account locked mid-registration (e.g. OTP brute-force lockout — the endpoints are rate-limited but a lockout can still land) can still complete email/phone verification and reach `BASIC_VERIFIED`.

**Fix:**

- In each of `verifyEmail` and `verifyPhone` across all three registration services, **after** the `User` is loaded and **before** any state mutation, add:
  ```java
  if (user.isLocked()) {
      throw new <SameExceptionThisMethodAlreadyThrows>("security.accountLocked");
  }
  ```
  Use the exception type each method already uses for its other guard failures (`EmailTokenException` in `verifyEmail`, `OtpVerificationException` in `verifyPhone`) so the HTTP mapping and the `boolean` "terminal" flag are consistent. Add the `security.accountLocked` message key to `en`/`de`/`fr` i18n bundles if the client renders these keys (check how `security.emailTokenInvalid` / `security.otpMismatch` are surfaced — `grep -rn "security.otpMismatch\|security.emailTokenInvalid" src/frontend/src/i18n`). If those keys are backend-only error codes with a frontend fallback, follow that pattern instead.
- Do **not** silently no-op (that hides the lock from the user); a clear terminal error is correct. Do not change lock-*clearing* — that is admin-side and out of scope.
- `AuthService` login already rejects a locked user (`UserAccountLockedException`) — mirror that intent, not that exact type, since the registration methods have their own exception vocabulary.

**Tests:** for each service, a `verifyEmail_lockedUser_rejected` and `verifyPhone_lockedUser_rejected` case (seed `user.locked = true`, assert the method throws and the row is **not** advanced). Mutation check: remove the guard → the locked user reaches `EMAIL_VERIFIED` / `BASIC_VERIFIED` → test fails. `-Dtest` the three registration test classes green.

### AC12 — `deferred-work.md` ledger hygiene.

Edit **`_bmad-output/implementation-artifacts/deferred-work.md`** in place (follow the file's current tail-section `[CLOSED by …]`-append style):

1. `## Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity (2026-08-05)` — append `[CLOSED by skillars-deferred-88 AC1 — ReviewSubmittedEvent now carries a monotonic moderationEpoch (V120, reviews.coach_reviews.moderation_epoch), incremented by ReviewSubmissionService.updateReview; ReviewModerationService discards a verdict whose event epoch != the row's current epoch under the existing findByIdForUpdate lock. The design limitation the 2026-08-05 audit flagged (superseded-edit verdict racing on a shared PENDING) is now closed regardless of the 365-day edit rule.]` to the D3 bullet.
2. `## Deferred from: code review of skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)` — append `[CLOSED by skillars-deferred-88 AC2 — SoftDeleteIT.concurrentDoubleSoftDelete… rewritten to hold the messaging.messages row lock in an external transaction and assert the DELETE request genuinely blocks until release (mutation-verified: reverting softDeleteMessage's findByIdForUpdate to findById fails it); the tautological SELECT COUNT(*) … deleted_at IS NOT NULL assertion is replaced with the losing-caller messaging.alreadyDeleted body check and a stable-deleted_at assertion.]` to the D6 bullet.
3. `## Deferred from: code review of skillars-deferred-87-… (2026-08-31)` — append `[CLOSED by skillars-deferred-88 AC3]` to the "No `flock` anywhere in `provision.sh`" bullet; `[CLOSED by skillars-deferred-88 AC4]` to the `/etc/fstab` `.bak` bullet **and** the `sed -i` commented-line bullet; `[CLOSED by skillars-deferred-88 AC5]` to the "Staging `rsync` … no free-space pre-check" bullet **and** the "Multi-Volume host without `HETZNER_VOLUME_ID`" bullet; `[CLOSED by skillars-deferred-88 AC6]` to the "CI `:latest` freezes permanently after a `master` history rewrite" bullet **and** the "First-ever `:latest` publish where GHCR returns `denied` / `name unknown`" bullet.
4. `## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)` — the "Empty notification vars cause silent delivery failure" bullet already carries a `[CLOSED (both-empty case) by skillars-deferred-85 AC6 …]` tag ending "RESIDUAL, still open: a single-channel deployment still provisions one empty notify-ops receiver …". Append `[RESIDUAL CLOSED by skillars-deferred-88 AC7 — provision.sh now renders the notify-ops receiver list from only the channels configured in .env, so a single-channel deployment provisions exactly one receiver and no silent no-op.]`.
5. `## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)` — append `[CLOSED by skillars-deferred-88 AC8 — Prometheus/Loki/Tempo/Redis/node-exporter moved onto an internal:true skillars-observability network with no gateway; egress-needing peers (app, grafana) join both networks. No host iptables.]` to the "No outbound firewall rules" bullet.
6. `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)` — append `[CLOSED by skillars-deferred-88 AC9]` to **W3** (`app.ses.enabled` unrecognised value). `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group A (2026-06-11)` — append `[CLOSED by skillars-deferred-88 AC10 — V121 uq_pot_one_active_per_user partial unique index on phone_otp_tokens(user_id) WHERE used = false; the three registration services already delete-before-insert, now with a DataIntegrityViolationException→friendly-retry catch for the concurrent-resend race.]` to **D5**. `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group D (2026-06-11)` — append `[CLOSED by skillars-deferred-88 AC11 — verifyEmail/verifyPhone in all three registration services now reject a User with locked = true before any state mutation, using each method's existing exception vocabulary.]` to **D3**.
7. Add a new `## Deferred from: code review of skillars-deferred-88 …` section only if this story's code review surfaces new deferrals — not required at creation time.

Record the exact set of edited line numbers in the Dev Agent Record.

## Tasks / Subtasks

- [ ] **Task 1: Review-moderation epoch guard (AC: #1)**
  - [ ] `V120__coach_reviews_moderation_epoch.sql` — `ADD COLUMN moderation_epoch BIGINT NOT NULL DEFAULT 0`
  - [ ] `CoachReview` entity: `long moderationEpoch = 0L` field
  - [ ] `ReviewSubmittedEvent`: add `long moderationEpoch` (6th component); update every `new ReviewSubmittedEvent(` call site (grep src + test)
  - [ ] `ReviewSubmissionService.submitReview` publishes epoch `0`; `.updateReview` increments `moderationEpoch` next to `setModerationStatus(PENDING)` and publishes the new value from the saved instance
  - [ ] `ReviewModerationService.handleReviewSubmitted`: epoch-mismatch guard **before** the `current != PENDING` guard, with the warn log; update the guard comment to name the "superseded edit" case
  - [ ] `ReviewModerationServiceTest`: stale-epoch delivery is discarded (mutation-verified); fresh-epoch delivery still lands
- [ ] **Task 2: `SoftDeleteIT` race-test hardening (AC: #2)**
  - [ ] Rewrite `concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` to hold the `messaging.messages` row lock in an external transaction and assert the `DELETE` request blocks until release, then a second `DELETE` → `409 messaging.alreadyDeleted`
  - [ ] Replace the tautological `SELECT COUNT(*) … deleted_at IS NOT NULL` assertion with: losing-caller body carries `messaging.alreadyDeleted`; `deleted_at` is stable (winner's value unchanged by the loser)
  - [ ] Record the mutation check (`findByIdForUpdate` → `findById` fails the block assertion) in the Dev Agent Record
  - [ ] `-Dtest=SoftDeleteIT` green
- [ ] **Task 3: `provision.sh` `flock` (AC: #3)**
  - [ ] Whole-script exclusive `flock --nonblock` on `/var/lock/skillars-provision.lock` via the `_PROVISION_LOCKED` self-re-exec idiom; fail-fast refusal message on a held lock
  - [ ] `bash -n` + `shellcheck` no-new-findings; 3-case hand-trace
- [ ] **Task 4: `/etc/fstab` safety (AC: #4)**
  - [ ] Timestamped `cp -p /etc/fstab /etc/fstab.bak.<ts>` before the `sed -i` purge, with a `log` line
  - [ ] Purge guard + `sed` address both require a non-comment, real-device line for `${MOUNT_POINT}`; one shared regex
  - [ ] `bash -n` + `shellcheck` no-new-findings; 4-case hand-trace (active line / commented-only / both / none)
- [ ] **Task 5: Staging free-space + multi-Volume guards (AC: #5)**
  - [ ] `du -sk`/`df -Pk` free-space guard (×2 headroom) before the staging `rsync`, armed-migration only, `|| true`-guarded, fail-open on an unreadable `df`
  - [ ] `>1` `scsi-0HC_Volume_*` symlink + unset `HETZNER_VOLUME_ID` → `err` + `exit 1`
  - [ ] `bash -n` + `shellcheck` no-new-findings; 6-case hand-trace
- [ ] **Task 6: CI `:latest` recovery + first-publish detection (AC: #6)**
  - [ ] `workflow_dispatch` + `force_publish_latest` boolean input on `ci.yml`; guard step short-circuits to both tags on `force_publish_latest=true` with a `::notice::`
  - [ ] Widen the "`:latest` absent" grep to include `name ?unknown` / `denied`
  - [ ] `actionlint` clean on `ci.yml` + `pr-build.yml` (pr-build untouched); YAML parse; 4-case walk-through
- [ ] **Task 7: Grafana single-channel receiver rendering (AC: #7)**
  - [ ] Split `grafana-alerts.yml` into a static rules/policies head + a `provision.sh`-generated `contactPoints` block (or an in-place delimited generated region); container bind-mount path unchanged
  - [ ] Receiver list contains only the configured channel(s); both-empty still hits the existing `exit 1`; exactly-one → one receiver
  - [ ] Idempotent regeneration; header comment + deployment doc updated
  - [ ] `bash -n` + `shellcheck`; YAML-parse the generated file for email-only / slack-only / both; 3-case + re-run hand-trace
- [ ] **Task 8: Observability egress firewall (AC: #8)**
  - [ ] `skillars-observability` network `internal: true`; move prometheus/loki/tempo/redis/node-exporter onto it only; `app` + `grafana` join both networks
  - [ ] Every existing peer edge re-verified (LOKI_URL, OTLP endpoint, Prometheus scrape targets, Grafana datasources, `app`→`redis`)
  - [ ] `docker compose config` parses for base + `-f docker-compose.local.yml`; overlays updated if they redefine service networks
  - [ ] Network comments + deployment topology doc updated; before/after service→networks table in the Dev Agent Record
- [ ] **Task 9: `app.ses.enabled` strict binding (AC: #9)**
  - [ ] Startup check rejecting a non-null `app.ses.enabled` that is not `true`/`false` (trimmed, case-insensitive) with a clear `IllegalStateException`
  - [ ] Align `SesProperties.enabled` default with the `@ConditionalOnProperty` `matchIfMissing`; document the chosen default on both
  - [ ] Context tests: `true` → bean present; `false` → absent, starts; `yes` → startup fails; unset → matches aligned default
- [ ] **Task 10: Phone-OTP uniqueness backstop (AC: #10)**
  - [ ] `V121__phone_otp_tokens_one_active_per_user.sql` — defensive dedup `DELETE` + `CREATE UNIQUE INDEX … (user_id) WHERE used = false` (optionally also `email_verification_tokens` if fixtures allow)
  - [ ] Per-service `DataIntegrityViolationException` → friendly-retry catch around the OTP `save` in all three registration services
  - [ ] `@DataJpaTest`/repo IT for the index; service test for the concurrent-resend conflict; existing registration ITs still green
- [ ] **Task 11: Lock-aware verification (AC: #11)**
  - [ ] `user.isLocked()` guard in `verifyEmail` + `verifyPhone` across all three registration services, using each method's existing exception type + `security.accountLocked` key
  - [ ] i18n key added where these keys are client-rendered (or follow the backend-error-code pattern)
  - [ ] Per-service locked-user rejection tests, mutation-verified
- [ ] **Task 12: Ledger hygiene (AC: #12)**
  - [ ] Append the `[CLOSED by skillars-deferred-88 AC…]` / `[RESIDUAL CLOSED …]` tags per AC12 (deferred-14 D3, deferred-16 D6, deferred-87 ×6, deploy-3-3 residual, deploy-1-5 egress, skillars-1-3 W3 / Group A D5 / Group D D3)
  - [ ] Record every edited `deferred-work.md` line number in the Dev Agent Record

## Dev Notes

### Source ledger mapping

| AC | `deferred-work.md` source |
|----|---------------------------|
| AC1 | `## Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity (2026-08-05)` — D3 (`ReviewModerationService` `PENDING`-only guard cannot distinguish a stale in-flight Gemini verdict for a superseded edit). Project-owner decision 2026-08-31: close defensively now. |
| AC2 | `## Deferred from: code review of skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)` — D6 (`SoftDeleteIT` synchronises request start not the critical section; tautological PK assertion). |
| AC3–AC6 | `## Deferred from: code review of skillars-deferred-87-… (2026-08-31)` — `flock` (AC3); `/etc/fstab` `.bak` + `sed -i` commented-line (AC4); staging free-space + multi-Volume `HETZNER_VOLUME_ID` (AC5); CI `:latest` freeze after history rewrite + GHCR `denied`/`name unknown` first-publish (AC6). |
| AC7 | `## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)` — the `skillars-deferred-85` AC6 RESIDUAL: single-channel deployment still provisions one empty notify-ops receiver. Grafana file provisioning has no conditionals → `provision.sh` renders it. |
| AC8 | `## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)` — "No outbound firewall rules — observability containers have unrestricted internet egress." `skillars-deferred-85` deferred it to its own story; folded in here per project-owner decision. |
| AC9 | `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)` — W3 (`SesConfig` unrecognised `app.ses.enabled` value leaves `SesEmailService` unwired at startup). |
| AC10 | Same review, Group A — D5 (`phone_otp_tokens` no partial unique index on active OTPs). Services already delete-before-insert; this adds the enforced backstop + concurrent-request conflict handling. |
| AC11 | Same review, Group D — D3 (`SUSPENDED`/locked user in `EMAIL_VERIFIED` can complete phone OTP; "guard should be updated when suspension story is implemented" — `User.locked` now exists). |

### Project-owner decisions folded in (2026-08-31)

- **Module priority**: Messaging/Admin/Reviews/Disputes is mined out (4th confirmation — `skillars-deferred-82`/`-83` both dry). Take the two real items (AC1, AC2) as the anchor, then bundle Deploy/Infra (AC3–AC8) and Auth/Registration (AC9–AC11). Chosen from four options presented.
- **AC1 (deferred-14 D3)**: **include it** — add the epoch/nonce now, even though the scenario is unreachable behind the 365-day edit rule, because a GDPR/admin re-publish path or a relaxed rule opens it and the event carries no version today.
- **AC7 (Grafana single-channel residual)**: **fold in** — `provision.sh` renders the receiver list from the configured channels (Grafana file provisioning has no native conditionals).
- **AC8 (egress firewall)**: **fold in** — previously carved out by `skillars-deferred-85` as "its own design story"; now in scope. Compose-only (`internal: true` network), no host iptables.
- **AC9–AC11 (Auth/Registration)**: **include all three** — `app.ses.enabled` strict binding, `phone_otp_tokens` partial unique index, locked-user verification guard. The related "`resendVerificationEmail` accepts `EMAIL_VERIFIED` users" item (skillars-1-3 Group A D7) was **verified already fixed** (`CoachRegistrationService.resendVerificationEmail:185-193` only re-sends for `null`/`UNVERIFIED`) and is **excluded**.

### Architecture / conventions to follow

- **No local `mvn verify`** — `[[feedback_no_local_mvn_verify]]`. Backend changes (AC1, AC9, AC10, AC11) are verified by targeted `-Dtest` runs recorded in the Dev Agent Record; the full suite is CI's gate. Shell/YAML/compose changes (AC3–AC8) are verified by `bash -n`, `shellcheck` (no-new-findings vs. a saved baseline), `actionlint`, `docker compose config`, and YAML parses — matching the established `deploy/**` verification path (`skillars-deferred-85`, `-87`, `skillars-uat-6`).
- **Two Flyway migrations**: `V120` (reviews epoch), `V121` (phone-OTP unique index). Both additive, both small-table. Max existing migration is `V119`. No `NOT VALID`/`VALIDATE` split at this table size (accepted class — see the `skillars-deferred-84` online-migration deferral, which stays open as a codebase-wide convention item).
- **`ReviewModerationService` structure is load-bearing** — the `REQUIRES_NEW` `TransactionTemplate` exists specifically to suspend the stale `AFTER_COMMIT` `EntityManager`; the `findByIdForUpdate` is the first read in that transaction so it needs no `entityManager.refresh` (contrast `BookingService.createBookingRequest`). The outer `catch` swallows to stop an `AFTER_COMMIT` exception becoming an HTTP 500. AC1 adds one guard clause inside the existing `ifPresentOrElse` block and one migration/entity/event field — nothing else moves.
- **`Message` must not get `@Version`** (AC2) — `MessagingService.java:315-317` and `skillars-deferred-16` Dev Notes: `ModerationResultApplier` / `AdminMessageService` / `MessageModerationSweeper` all `save()` the row and optimistic locking would turn benign moderation interleavings into `OptimisticLockingFailureException`s on a request thread with SSE callbacks and no retry.
- **`provision.sh` is idempotent and re-run-safe by contract** — AC3/AC4/AC5/AC7 all sit in or around section 7–8; every change must keep first-provision behaviour unchanged and re-runs a no-op. The `flock` (AC3) wraps the whole script including the idempotent re-run path.
- **`deploy/backup/*.sh` / `provision.sh` `set -euo pipefail`** — every command substitution whose failure must not abort is `|| true`-guarded; extend that, do not remove it (`skillars-deferred-85` AC3/AC6 Dev Notes).
- **The three registration services deliberately duplicate near-identical OTP/verification blocks** — do not introduce a shared base class for AC10's catch helper or AC11's lock guard; add the small block to each, matching how the delete-before-insert is already triplicated.
- **CI**: `.github/actions/docker-build/action.yml` stays a dumb "push these tags" action (AC6 logic lives in `ci.yml`); `pr-build.yml` is untouched; `:latest` is an OCI image index (default provenance attestation) — AC6's widened grep is in the `inspect_rc != 0` branch and does not touch the index-aware success path.

### Files expected to change

- **Backend (AC1)**: `src/main/resources/db/migration/V120__coach_reviews_moderation_epoch.sql` (new), `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReview.java`, `.../reviews/contract/ReviewSubmittedEvent.java`, `.../reviews/service/ReviewSubmissionService.java`, `.../reviews/service/ReviewModerationService.java`, `src/test/.../reviews/**` (test + any event builders).
- **Backend (AC9)**: `src/main/java/com/softropic/skillars/infrastructure/ses/SesConfig.java`, `SesProperties.java`, possibly a new tiny startup-validator class in the same package; `src/test/.../ses/**`.
- **Backend (AC10)**: `src/main/resources/db/migration/V121__phone_otp_tokens_one_active_per_user.sql` (new), `.../security/service/CoachRegistrationService.java`, `PlayerRegistrationService.java`, `ParentRegistrationService.java`; `src/test/.../security/**`.
- **Backend (AC11)**: the same three registration services (`verifyEmail` + `verifyPhone`); i18n bundles under `src/frontend/src/i18n/**` if `security.*` keys are client-rendered; `src/test/.../security/**`.
- **IT (AC2)**: `src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java`.
- **Ops (AC3–AC5)**: `deploy/provision.sh`.
- **Ops (AC6)**: `.github/workflows/ci.yml`.
- **Ops (AC7)**: `deploy/provision.sh`, `deploy/lgtm/grafana-alerts.yml` (split or delimited), `docs/deployment/*.md`.
- **Ops (AC8)**: `docker-compose.yml`, possibly `docker-compose.local.yml` / `docker-compose.uat.yml`, `docs/deployment/*.md`.
- **Ledger (AC12)**: `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`, this story file.

### Files being created

- `src/main/resources/db/migration/V120__coach_reviews_moderation_epoch.sql`
- `src/main/resources/db/migration/V121__phone_otp_tokens_one_active_per_user.sql`
- Possibly one small SES startup-validator class (AC9) and one static `grafana-alerts.rules.yml` split-out (AC7) — dev's call on the exact shape.

### Project Structure Notes

- Reviews module lives under `src/main/java/com/softropic/skillars/platform/reviews/**` (schema `reviews`). Messaging under `platform/messaging/**` (schema `messaging`). Registration under `platform/security/service/**` (table `main.phone_otp_tokens` / `main.email_verification_tokens` / `main."user"`).
- Migration numbering: next is `V120`, then `V121`. Zero other migrations pending.
- New tests should reuse existing IT base classes (`SoftDeleteIT` already extends the messaging IT base; SES/reviews/security tests should mirror existing siblings) — **no new Spring `@SpringBootTest` context configuration** is expected; if a new context is unavoidable, flag it (context-count ceiling matters — see `skillars-deferred-86` Dev Notes).

### References

- [Source: `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java`] — `handleReviewSubmitted`, `AFTER_COMMIT` + `REQUIRES_NEW`, `findByIdForUpdate` + `PENDING`-only guard (the guard AC1 extends).
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:63,72,98,103`] — the two `ReviewSubmittedEvent` publish sites; `updateReview:82-86` is the 365-day edit rule.
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewSubmittedEvent.java`] — 5-field record; AC1 adds `moderationEpoch`.
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReview.java`] — entity; `@Column(name="moderation_status")`, add `moderation_epoch`.
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java:23-25`] — `@Lock(PESSIMISTIC_WRITE) findByIdForUpdate`.
- [Source: `src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java:246-289`] — the concurrent double-soft-delete test AC2 rewrites.
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:296-330`] — `softDeleteMessage`: unlocked read + authz, then `findByIdForUpdate` + `deletedAt` check + write.
- [Source: `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java`] — the external-lock lock-causality pattern AC2 mirrors (`skillars-deferred-83` AC2).
- [Source: `deploy/provision.sh:193-202`] — `/dev/disk/by-id/scsi-0HC_Volume_*` resolution (AC5 multi-Volume guard).
- [Source: `deploy/provision.sh:297-298`] — the staging `rsync` (AC5 free-space guard).
- [Source: `deploy/provision.sh:333-350`] — the `/etc/fstab` maintenance block (AC4).
- [Source: `deploy/provision.sh:423-475`] — section 8 alert-routing validation/warn (AC7 renderer goes here).
- [Source: `.github/workflows/ci.yml`] — `build-and-push` "Decide image tags (:latest ordering guard)" step (AC6).
- [Source: `deploy/lgtm/grafana-alerts.yml`] — `contactPoints[0]` hard-codes both an `email` and a `slack` receiver (AC7).
- [Source: `docker-compose.yml:293-296`] — the single `skillars-internal` network with the "Do NOT set internal: true" comment (AC8); `:234` the `grafana-alerts.yml` bind mount (AC7).
- [Source: `src/main/java/com/softropic/skillars/infrastructure/ses/SesConfig.java`] — `@ConditionalOnProperty(name="app.ses.enabled", havingValue="true", matchIfMissing=false)` vs `SesProperties.enabled = true` (AC9).
- [Source: `src/main/resources/db/migration/V21__skillars_security_extension.sql:23-33`] — `main.phone_otp_tokens` DDL, `idx_pot_userid` (non-unique) (AC10).
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java:138,145,151-181,185-193`] — delete-before-insert OTP path (AC10); `verifyPhone`/`verifyEmail` guards (AC11); `resendVerificationEmail` already `UNVERIFIED`-only (D7 excluded).
- [Source: `src/main/java/com/softropic/skillars/platform/security/repo/User.java:71`] — `private boolean locked` (AC11).
- [Source: `src/main/java/com/softropic/skillars/platform/security/repo/PhoneOtpTokenRepository.java`] — `deleteByUserIdAndUsedFalse`, `findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc` (AC10).

## Dev Agent Record

### Agent Model Used

_(dev-story)_

### Debug Log References

_(to be filled during implementation — `bash -n` / `shellcheck` baselines, `actionlint`, `docker compose config`, `-Dtest` runs)_

### Completion Notes List

_(to be filled)_

### File List

_(to be filled)_

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-08-31 | 0.1 | Story created via story-creation process. Messaging/Admin/Reviews/Disputes confirmed mined out (4th time) — anchor is the two real items (AC1 review-moderation epoch guard, AC2 SoftDeleteIT race-test hardening); bundled with Deploy/Infra from the fresh skillars-deferred-87 code-review vein (AC3 provision.sh flock, AC4 /etc/fstab .bak + comment-safe purge, AC5 staging df guard + multi-Volume HETZNER_VOLUME_ID hard-fail, AC6 CI :latest workflow_dispatch recovery + GHCR first-publish grep) plus two previously-carved-out design items the project owner folded in (AC7 Grafana single-channel receiver rendering, AC8 observability egress firewall) and three Auth/Registration items (AC9 app.ses.enabled strict binding, AC10 phone_otp_tokens partial unique index, AC11 locked-user verification guard). 2 Flyway migrations (V120, V121), both additive small-table. resendVerificationEmail flow-regression item (skillars-1-3 Group A D7) verified already fixed and excluded. Status: ready-for-dev. | Mbah (create-story) |
