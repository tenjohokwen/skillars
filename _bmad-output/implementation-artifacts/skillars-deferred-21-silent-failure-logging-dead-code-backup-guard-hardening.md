# Story Deferred-21: Silent-Failure Logging, Dead Code Removal & Backup Script Guard Consolidation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want a handful of small, independently-verified code-review deferrals closed — two silent misconfiguration/failure paths that log nothing, two confirmed-dead code paths, and the backup/restore script family's duplicated `.env` guard consolidated into one shared, better-tested implementation,
so that operational failures are diagnosable from logs instead of invisible, dead branches stop confusing future readers, and a 5th backup/restore script can't "forget" the credential guard the way the ledger warned it eventually would.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md` per Mbah's direction to group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit as `skillars-deferred-11` (6 unrelated Stripe/frontend items) and `skillars-deferred-20` (3 small backup-script hardening items). Every item below was re-verified against the **current** source during story creation (not trusted from the ledger's text, which the ledger's own header warns can be stale) — see **Deferred Items Closed** for the corrected current locations.

This story does **not** re-litigate the large body of 2026-06-era "pre-existing pattern" / "accepted trade-off" / "spec-designed" items still sitting in `deferred-work.md` — those were consciously left, not merely unnoticed, and 10+ subsequent stories have shipped without anyone judging them worth picking up. See **Explicitly NOT in this story** below for what was considered and rejected.

## Deferred Items Closed

| Source | Item | Current location (re-verified) | AC |
|---|---|---|---|
| `skillars-5-2-skill-exposure-dashboard-neglected-skill-detection` — Round 2 Group C code review (2026-06-19) | D3 — `getNeglectedSkills` is dead code | `src/frontend/src/api/development.api.js:11-12` | 1 |
| `skillars-6-6-player-video-management-portal` code review (2026-06-24) | W6 — `PURGED` branch is dead code | `src/frontend/src/pages/VideoManagementPage.vue:123-131` (condition at line 124) | 2 |
| `skillars-4-1-drill-library-foundation` code review (2026-06-17) | D9 — `ConfigService.getBoolean` logs nothing for a present-but-invalid value | `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:106-113` (1-arg) **and** `:115-119` (2-arg overload — same bug, found during story creation, not named by the original ledger item) | 3 |
| `skillars-4-6-homework-assignment-player-locker-room` code review (2026-06-18) | W3 — `handleBookingCompleted` stores a null `sessionId` with no log | `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java:55-61` | 4 |
| `skillars-deferred-20-backup-restore-script-hardening` code review (2026-08-13) | 3 items: guard duplication, no directory-vs-missing distinction, no post-source var validation | `deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-volume-backup.sh`, `restore-from-dump.sh`, `prune-backups.sh` | 5 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **`skillars-5-2` Round 2 Group C, D5** (`SluTargetEditor` race) — checked against current code and it's **already fixed**: `SluTargetEditor.vue:58-62` has the `if (open.value) return` guard the item asked for. The ledger is stale; worth a `[CLOSED]` annotation but there's no code left to write.
- **`skillars-3-3` Group E** (authority id 9502 test-data leak) — **not reproducible**: `main.authority.name` is `UNIQUE`, `id=9500` already holds `name='ROLE_PARENT'` from the test class's own `@BeforeEach`, so the `INSERT ... ON CONFLICT (name) DO NOTHING` this item complains about silently no-ops every run. Nothing leaks.
- **`skillars-4-6` W4** (`@Size(max=2)` not enforced on the event-driven homework path) — verified all current publishers (`BookingCompletionService`, `QuickCompleteTimeoutService`) source `homeworkDrillIds` from data already validated by the HTTP `WrapUpRequest`'s `@Size(max=2)` at creation. The ledger's own text calls this speculative ("add ... if other publishers emerge"); no live path is unbounded today.
- The broad body of 2026-06-era Story 1.x–8.x deferred items not listed above — each still carries "pre-existing pattern" / "accepted trade-off" / "spec-designed" / "needs product input" language in its own ledger entry. Not this story's job to re-open them.
- `deploy-1`/`deploy-2`/`deploy-3` infra items (SSH firewall exposure window, `PGPASSWORD` in `ps aux`, no image vuln scan, etc.) — the ledger's own audits repeatedly flag the whole `deploy-*` block as never re-verified against current scripts; each of those needs its own from-scratch verification pass, not a grab-bag slot.
- `skillars-11-2` D2 (pack-selection FIFO vs. soonest-expiring) and `skillars-7-5` D1 (running-balance pagination edge case) — real, but one explicitly needs a product decision and the other touches financial-reporting correctness on a boundary condition; both deserve their own scoped story with dedicated tests, not a bundled slot.

## Acceptance Criteria

1. **`getNeglectedSkills` removed from `development.api.js`.** Zero callers exist anywhere in `src/frontend/src` (confirmed by full-tree grep — only the export line itself matches `getNeglectedSkills`). Neglected-skill codes are already bundled into the exposure endpoint's response; this standalone export was never wired to any component or store action.

2. **The dead `PURGED` branch removed from `VideoManagementPage.vue`'s `onStatusChanged`.** `VideoSseService.TERMINAL_STATES` (`VideoSseService.java:29-31`) is `{READY, LOCKED, REJECTED, FAILED, DELETED}` — `PURGED` is not a member, and the class's own comment above `TERMINAL_STATES` explains exactly which states are/aren't terminal and why, with no mention of `PURGED` ever being added. No SSE event can ever carry `newState === 'PURGED'` into this handler. `onStatusChanged` keeps its `'DELETED'` branch unchanged; only the unreachable `|| newState === 'PURGED'` disjunct is removed.

3. **Both `getBoolean` overloads log a warning when the stored value is present but is not (case-insensitively) `"true"` or `"false"`.** The ledger's D9 item names `ConfigService.getBoolean` without disambiguating overloads, and the bug is identical in both:
   - `getBoolean(String key)` (`ConfigService.java:106-113`) — the `.orElseGet(...)` branch (key absent) already logs a warning; the `.map(...)` branch (key present but e.g. `"yes"`, `"1"`, or a typo) silently returns `false` with no log line at all.
   - `getBoolean(String key, boolean defaultValue)` (`ConfigService.java:115-119`) — has the exact same `.map(v -> "true".equalsIgnoreCase(v))` shape. A present-but-invalid value is caught by `.map` and returns `false`; it does **not** fall through to `.orElse(defaultValue)` — that branch only fires when the key is absent entirely. So a misconfigured value silently produces `false` regardless of what `defaultValue` was, which is the more surprising of the two variants since a caller who passed `defaultValue = true` would reasonably expect a garbled value to at least fail toward their stated default, not silently flip to `false`.
   - **This overload has more production call sites than the 1-arg one** — found during story creation, not in the original ledger item: `AuthService.java:99` (`security.registration.phone-otp-required`, a registration/OTP security gate), `VideoDeletionService.java:129`, `AccountDeletionCascadeListener.java:63`, `VideoApprovalService.java:103`, `PlaybackService.java:103`. None of these call sites need to change — only `ConfigService` gets the new log line — but the wider blast radius is why this AC covers both overloads rather than just the one named in the ledger.
   - A misconfigured-but-present key is operationally indistinguishable from a correctly-disabled feature gate today; add the missing log to each `.map` branch only. Behavior (the returned `boolean`) does not change in either overload.

4. **`HomeworkAssignmentService.handleBookingCompleted` logs when it cannot resolve a `sessionId` for the completed booking.** Today (`HomeworkAssignmentService.java:55-61`) `sessionId` is resolved via `sessionRepository.findByBookingId(...).map(...).orElse(null)` with no log on the `null` branch. A `null` sessionId is expected and harmless for QUICK-mode bookings (per the method's own inline comment on the idempotency anchor) but is currently invisible in logs either way — add a log line so an operator investigating a homework-assignment issue can see whether session resolution actually ran and what it returned, without having to reason about async listener ordering from the code alone.

5. **The `/opt/skillars/.env` readability/type/content guard used by `pg-backup.sh`, `volume-backup.sh`, `restore-from-volume-backup.sh`, `restore-from-dump.sh`, and `prune-backups.sh` is extracted into one shared, sourced library (`deploy/backup/env-guard.sh`)**, closing all three items the `skillars-deferred-20` code review recorded and explicitly left open (`deferred-work.md` lines 1404-1406):
   - **No more duplication.** One `require_env_vars()` function, sourced by all five scripts, replaces five separate inline (four of them byte-for-byte identical) guard blocks. A 6th backup/restore script added later cannot "forget" the pattern — it just calls the shared function.
   - **Directory vs. missing/unreadable is distinguished.** `[ -d "$env_file" ]` is checked before the readability check, with its own clearly-worded error, instead of a directory silently passing `[ ! -r ]`'s test and failing unpredictably at the `source` line.
   - **Required credentials are validated as non-empty after sourcing**, not just "the file was readable." A present-but-empty/truncated `.env`, or one missing a specific key this script needs, now fails immediately with a `[<tag>][error]`-tagged message naming exactly which variable(s) are missing, instead of failing later at some unrelated, confusing downstream command (e.g. a blank `aws s3 cp --endpoint-url ""`).
   - `prune-backups.sh` is included even though the original ledger items named only the four newer scripts — leaving it on its own separate (if similar) guard would recreate the exact "inconsistent with the pattern they were told to copy" problem item 2 describes, one level up. This mirrors the precedent `skillars-deferred-20` itself set by pulling in `restore-from-dump.sh` (not named in *its* source ledger item) to avoid the same kind of new inconsistency.
   - Every script's own `[<tag>][error]` prefix convention (`[pg-backup]`, `[volume-backup]`, `[restore-from-volume-backup]`, `[restore-dump]`, `[prune-backups]`) and message wording style is preserved — the shared function takes the tag and an action word (`"backup"` / `"restore"` / `"retention"`) as parameters, it does not flatten every script's error text to one generic message.

6. **Ledger hygiene in `deferred-work.md`.** Annotate every item this story closes (see **Deferred Items Closed** table) with `[CLOSED by skillars-deferred-21 ACn]`, and annotate the two rejected-as-already-fixed/unreproducible items (`5-2` D5, `3-3` Group E) with `[AUDIT: verified against current code during skillars-deferred-21 story creation — <one-line reason>]` so a future reader doesn't re-open them as ambiguous.

## Tasks / Subtasks

- [x] Task 1 — Remove dead `getNeglectedSkills` export (AC: #1)
  - [x] Delete the `getNeglectedSkills` export from `src/frontend/src/api/development.api.js:11-12`
  - [x] Re-grep `src/frontend/src` for `getNeglectedSkills` to confirm zero remaining references (including dynamic/string-based calls) before deleting
  - [x] `eslint` clean

- [x] Task 2 — Remove dead `PURGED` branch (AC: #2)
  - [x] In `VideoManagementPage.vue`'s `onStatusChanged`, change `if (newState === 'DELETED' || newState === 'PURGED')` to `if (newState === 'DELETED')`
  - [x] Confirm no other frontend code references a `'PURGED'` state string against video SSE events (grep `src/frontend/src` for `PURGED`) — if any other reference exists, stop and report it rather than silently leaving an inconsistency; none is expected based on story-creation research, but re-verify at implementation time since the ledger's own accuracy record on this file is imperfect
  - [x] `eslint` clean

- [x] Task 3 — `ConfigService.getBoolean` logging, both overloads (AC: #3)
  - [x] In `ConfigService.getBoolean(String key)` (lines 106-113), inside the `.map(v -> ...)` lambda, log a `log.warn` when `v` does not case-insensitively equal `"true"` or `"false"`, before returning the boolean result — mirror the wording style of the existing `log.warn` two lines below in the same method (the absent-key branch)
  - [x] In `getBoolean(String key, boolean defaultValue)` (lines 115-119), apply the same fix to its `.map(v -> ...)` lambda — same invalid-value condition, log message should additionally name the `defaultValue` that was passed and ignored (since that's the detail specific to this overload a reader would want)
  - [x] Add a unit test to `ConfigServiceTest` for each overload covering a present value that is neither `"true"` nor `"false"` (e.g. `"yes"`) — assert `getBoolean(key)` still returns `false`, and assert `getBoolean(key, true)` still returns `false` (not `true`) proving the invalid value does not fall through to `defaultValue` (behavior must not change in either overload, only the log line is new); there is no existing precedent in this codebase for asserting log output (no `ListAppender`/`LogCaptor`/`OutputCaptureExtension` usage anywhere under `src/test`), so do **not** invent new log-assertion test infrastructure for a two-line logging change — verify the log statements by reading the diff, not by a new test harness
  - [x] `mvn -o test -Dtest=ConfigServiceTest` green

- [x] Task 4 — `HomeworkAssignmentService.handleBookingCompleted` logging (AC: #4)
  - [x] Add a `log.warn`/`log.debug` (match the method's own existing debug-level idempotency-skip log two lines below for tone; the story leaves the exact level to the dev's judgment since this is an expected, non-error condition, not an error) immediately after `sessionId` resolves to `null`, before the assignment-creation loop
  - [x] The existing test `handleBookingCompleted_withDrills_createsAssignments` already exercises the null-sessionId path (`sessionRepository.findByBookingId(bookingId)` stubbed to `Optional.empty()`) — confirm it still passes unchanged; do not add a log-assertion test for the same reason given in Task 3
  - [x] `mvn -o test -Dtest=HomeworkAssignmentServiceTest` green

- [x] Task 5 — Backup/restore script guard consolidation (AC: #5)
  - [x] Create `deploy/backup/env-guard.sh` (not directly executable — sourced only) implementing `require_env_vars <tag> <action> <VAR1> [VAR2 ...]` per the Dev Notes reference implementation: checks `SKILLARS_ENV_FILE:-/opt/skillars/.env}` is not a directory, then is readable, sources it, then checks every named var is non-empty, each failure exiting 1 with a `[<tag>][error]`-tagged message
  - [x] `pg-backup.sh`: replace the existing inline guard + source block (currently lines 6-11) with a call to the shared library — tag `pg-backup`, action `backup`, required vars `HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT POSTGRES_PASSWORD`
  - [x] `volume-backup.sh`: same replacement (currently lines 9-14) — tag `volume-backup`, action `backup`, required vars `HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT`
  - [x] `restore-from-volume-backup.sh`: same replacement (currently lines 12-17) — tag `restore-from-volume-backup`, action `restore`, required vars `HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT`. Its own `log()`/`err()` helpers (relocated to right after `set -euo pipefail` by `skillars-deferred-20`) stay in the file for the rest of the script's use — they are simply no longer called by the guard itself, which now goes through the shared function's own `echo ... >&2`
  - [x] `restore-from-dump.sh`: same replacement (currently lines 14-19) — tag `restore-dump` (matches its existing message prefix, not `restore-from-dump`), action `restore`, required vars `HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT POSTGRES_PASSWORD`
  - [x] `prune-backups.sh`: replace the existing inline guard + source block (currently lines 35-40) with a call to the shared library — tag `prune-backups`, action `retention`, required vars `HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT`. Keep the guard call positioned exactly where it is today: **after** the `--dry-run` argument-parsing block (arg parsing must stay fail-fast before anything env-dependent runs, per that script's own header comment). **Also delete the now-unused `ENV_FILE="/opt/skillars/.env"` declaration at line 17** — it is referenced nowhere outside the block being replaced (confirmed by grep), so leaving it in place after removing lines 35-40 turns it into a dead variable and fails the shellcheck-clean bar with `SC2034 (warning): ENV_FILE appears unused` (reproduced during story creation)
  - [x] `shellcheck` clean on `env-guard.sh` and all five callers — **use `shellcheck -x`** (e.g. `shellcheck -x deploy/backup/*.sh`, or `shellcheck -x deploy/backup/<script>.sh` when checking one caller in isolation). Without `-x`, checking any single caller script alone reproduces `SC1091 (info): Not following: env-guard.sh was not specified as input` with a non-zero exit — the `# shellcheck source=env-guard.sh` directive only resolves cleanly when either all six files are checked together in one invocation (so `env-guard.sh` is itself part of the input set) or `-x` is passed; the old per-script `source=/dev/null` idiom this replaces didn't have this invocation-order sensitivity, so don't mistake the bare `SC1091` for a real regression if you happen to check one script alone without the flag (reproduced during story creation)
  - [x] Manually verify all three guard branches fire correctly using `SKILLARS_ENV_FILE` overrides (no root/`/opt/skillars` access needed): (a) `SKILLARS_ENV_FILE=/tmp/does-not-exist.env <script>` → tagged "cannot read" error; (b) `mkdir -p /tmp/fake-env-dir && SKILLARS_ENV_FILE=/tmp/fake-env-dir <script>` → tagged "is a directory" error; (c) a temp file with only some required vars set → tagged "missing required value(s): ..." naming exactly the absent ones; (d) a temp file with every required var set → guard passes and the script proceeds to its next line (fails naturally afterward for unrelated reasons, e.g. no `aws` credentials that actually work — that's fine, the guard itself passing is what's being verified)

- [x] Task 6 — Ledger hygiene (AC: #6)
  - [x] Annotate all closed items per the **Deferred Items Closed** table and the two rejected/already-fixed items per **Explicitly NOT in this story**, directly in `deferred-work.md` at their current locations
  - [x] Add `skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening: ready-for-dev` to `sprint-status.yaml`'s DEFERRED WORK block (already added at story creation — when this story moves to `review`/`done`, update *that* status value; do not add a duplicate entry)

### Review Findings

- [x] [Review][Decision] AC2's "no SSE event can ever carry PURGED" premise holds for the SSE-push path but not the polling fallback — `useVideoStatusSse`'s `startPolling()` (`src/frontend/src/stores/video.store.js:23-48`) hits `GET /api/video/{id}/status`, which `VideoEventResource.getStatus`/`computeDisplayState` (`VideoEventResource.java:45-57`) answers with the raw `operationalState` name (`"PURGED"`) whenever `AccessState` isn't `BLOCKED`/`ARCHIVED` — there is no PURGED→DELETED translation on this endpoint, unlike the SSE event stream where `markPurged()` fires a `DELETED`-carrying event. Polling engages on every SSE reconnect (network drop, redeploy, backgrounded tab — not rare), so a coach/player can genuinely receive `onStatusChanged(videoId, 'PURGED')` during that window. With AC2's branch removed, this now falls through to `video.operationalState = 'PURGED'`: `VideoStatusCard`'s `statusConfigs` has no `PURGED` entry so the card silently renders empty, the row is never removed from `videos.value`, `fetchQuota()` never runs, and — because `TERMINAL_SSE_STATES` in `video.store.js:13` also excludes `PURGED` — polling never stops and re-fires every 2s indefinitely. Needs a decision: (a) restore PURGED handling in `onStatusChanged` and add `PURGED` to `TERMINAL_SSE_STATES` so polling actually stops, or (b) fix it at the source by having `VideoEventResource.computeDisplayState` translate `PURGED` → `DELETED` for parity with the SSE stream, or (c) both. Verified end-to-end by reading `VideoManagementPage.vue`, `VideoStatusCard.vue`, `video.store.js`, and `VideoEventResource.java` directly — not a hypothetical. **DECISION (Mbah, 2026-08-14): (c) both.** Fixed server-side (`VideoEventResource.computeDisplayState` now translates `PURGED`→`DELETED`, mirroring `VideoSseService.onVideoPurged`'s existing SSE translation, confirmed by tracing `VideoDeletionService.deleteVideo()` → `VideoPurgedEvent` → `VideoSseService.onVideoPurged` → `DELETED`) and client-side (`onStatusChanged`'s `PURGED` branch restored as a documented defensive backstop, `PURGED` added to `TERMINAL_SSE_STATES`). New `VideoSseIT.getStatus_purgedVideo_displayStateIsDeleted` test added and passing (7/7 in `VideoSseIT`).

- [x] [Review][Patch] `ConfigService.getBoolean` invalid-value warning logic is duplicated near-identically between the 1-arg and 2-arg overloads instead of being factored into a small shared private helper [`src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:106-125`] — **Applied:** extracted into a private `parseBoolean(String key, String value, String logSuffix)` helper both overloads now call; `logSuffix` lets the 2-arg overload keep naming its ignored default in the warning. `ConfigServiceTest` re-run green (11/11), log wording unchanged for both overloads.

- [x] [Review][Defer] `ConfigService.getBoolean` fails open (returns `false`) for a misconfigured-but-present value guarding security-sensitive gates (e.g. `AuthService.java:99`'s `security.registration.phone-otp-required`) — now logged at WARN but not alertable; pre-existing behavior, unchanged by this diff (only the log line is new) [`src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:106-125`] — deferred, pre-existing
- [x] [Review][Defer] `env-guard.sh`'s `require_env_vars` sources `.env` with no file-mode/ownership check — a world-readable or world-writable `/opt/skillars/.env` sources without warning; pre-existing gap carried over from the inline guards this consolidates, out of AC5's readability/content scope [`deploy/backup/env-guard.sh`] — deferred, pre-existing
- [x] [Review][Defer] The new `. "$(...)/env-guard.sh"` sourcing line in all 5 callers has no existence/readability guard of its own — if `env-guard.sh` is ever missing from a partial deploy, callers fail via a bare unprefixed `set -e` shell error instead of the family's `[<tag>][error]` convention; matches the story's own mandated reference "Caller shape" verbatim, so deviating needs a decision rather than a unilateral patch [`deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-volume-backup.sh`, `restore-from-dump.sh`, `prune-backups.sh`] — deferred, pre-existing

## Dev Notes

- **Scope discipline.** This is a bundle of five small, independently-safe items — do not use it as a pretext to "clean up while you're in there" on adjacent code. If something adjacent looks wrong, note it as a new `deferred-work.md` item; don't fix it here.

- **Reference implementation for `deploy/backup/env-guard.sh`** (Task 5) — write it to this shape; the `SKILLARS_ENV_FILE` override exists specifically so this can be verified without root/`/opt/skillars` access on a dev machine, which none of the prior backup-script stories in this codebase have had either:
  ```bash
  # shellcheck shell=bash
  # Sourced by every deploy/backup/*.sh script — do not execute directly.
  # SKILLARS_ENV_FILE may be overridden (e.g. by a test) to point at a throwaway file instead
  # of the real /opt/skillars/.env; production callers never set it.
  require_env_vars() {
    local tag="$1" action="$2"
    shift 2
    local env_file="${SKILLARS_ENV_FILE:-/opt/skillars/.env}"

    if [ -d "$env_file" ]; then
      echo "[${tag}][error] ${env_file} is a directory, not a file — cannot source credentials" >&2
      exit 1
    fi
    if [ ! -r "$env_file" ]; then
      echo "[${tag}][error] cannot read ${env_file} — ${action} cannot run without credentials" >&2
      exit 1
    fi
    # shellcheck source=/dev/null
    . "$env_file"

    local missing=() var
    for var in "$@"; do
      [ -z "${!var:-}" ] && missing+=("$var")
    done
    if [ "${#missing[@]}" -gt 0 ]; then
      echo "[${tag}][error] ${env_file} is missing required value(s): ${missing[*]}" >&2
      exit 1
    fi
  }
  ```
  Caller shape (replaces each script's own inline guard + source block, in the same position — before the `# shellcheck source=/dev/null` comment convention this replaces):
  ```bash
  # shellcheck source=env-guard.sh
  . "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
  require_env_vars "pg-backup" "backup" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT POSTGRES_PASSWORD
  ```

- **Why `env-guard.sh` gets `# shellcheck shell=bash` instead of a `#!/usr/bin/env bash` shebang:** it is never executed directly (only sourced), so a shebang would be misleading; the directive tells shellcheck which dialect to lint it as without implying it's runnable on its own.

- **`# shellcheck source=env-guard.sh` is invocation-sensitive in a way the pattern it replaces wasn't.** Every script's old guard used `# shellcheck source=/dev/null`, which tells shellcheck "don't try to follow this, trust it" — that resolves identically no matter how you invoke shellcheck. Pointing the directive at a real relative path (`env-guard.sh`) instead means shellcheck will only follow it when it's allowed to reach outside the current invocation's file list, which needs either `-x`/`--external-sources` or checking `env-guard.sh` in the same invocation as its caller. Reproduced during story creation: `shellcheck caller.sh` alone → `SC1091`, non-zero exit; `shellcheck -x caller.sh` or `shellcheck caller.sh env-guard.sh` → clean. Task 5's shellcheck step always uses `-x` for exactly this reason — don't drop the flag when re-running it ad hoc during development.

- **Required vars per script were derived by grepping each script for every `${VAR}`/`${VAR:-default}` reference that originates from `.env`** (i.e. excluding ones with a hardcoded fallback like `POSTGRES_USER:-postgres`, which don't need to be *required*). Re-verify this list against the actual script bodies at implementation time — story creation grepped the current tree, but re-check before trusting it blindly, per this file's own standing warning about ledger/story claims aging.

- **`restore-from-dump.sh` uses tag `restore-dump`, not `restore-from-dump`**, matching its own existing message convention (e.g. its usage-error message and line 30's error) — don't "fix" this to match the filename; it would change the operator-facing log tag for no reason.

- **This story touches shell scripts and two small Java/Vue methods — no migration, no schema change.** `mvn -o test` (targeted, not full `verify`) is sufficient for AC3/AC4; `shellcheck` + the manual guard verification in Task 5 is the bar for AC5, consistent with every prior story touching `deploy/backup/*.sh` (`skillars-uat-3`, `skillars-uat-6`, `skillars-deferred-20`) — none of them have ever run these scripts against live Hetzner Object Storage credentials in this environment either; don't attempt to fabricate a live run.

- **File paths this story touches:**
  - `src/frontend/src/api/development.api.js` (AC1)
  - `src/frontend/src/pages/VideoManagementPage.vue` (AC2)
  - `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` + `ConfigServiceTest.java` (AC3)
  - `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java` (AC4; no test file change expected — existing coverage already exercises the branch)
  - `deploy/backup/env-guard.sh` (new file), `pg-backup.sh`, `volume-backup.sh`, `restore-from-volume-backup.sh`, `restore-from-dump.sh`, `prune-backups.sh` (AC5)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC6, annotation only)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (status line only)

### Project Structure Notes

- All touched shell scripts already live in `deploy/backup/`; `env-guard.sh` is a new file in the same directory, following the flat, no-subdirectory convention already established there.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses in `sprint-status.yaml` (the "DEFERRED WORK" block, not nested under any `skillars-epic-N` key).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — lines 692 (5-2 D3), 728 (4-6 W3), 785 (4-1 D9), 1098 (6-6 W6), 1404-1406 (deferred-20 code review) — the 5 source locations this story closes; lines 693 (5-2 D5) and the `skillars-3-3` Group E entry — the 2 rejected/already-fixed items this story annotates but does not implement
- [Source: deploy/backup/prune-backups.sh:35-40] — the pre-existing guard pattern this story generalizes into a shared function
- [Source: deploy/backup/restore-from-volume-backup.sh:8-9] — existing `log()`/`err()` helpers this story leaves in place for the rest of the script
- [Source: _bmad-output/implementation-artifacts/skillars-deferred-20-backup-restore-script-hardening.md] — the immediately-preceding story in this script family; its own code review is the direct source of AC5's three sub-items
- [Source: _bmad-output/implementation-artifacts/skillars-deferred-11-stripe-card-collection.md] — precedent for the "Deferred Items Closed" table format and "Explicitly NOT in this story" section used above

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -o test -Dtest=ConfigServiceTest` — 11 tests, 0 failures, 0 errors
- `mvn -o test -Dtest=HomeworkAssignmentServiceTest` — 9 tests, 0 failures, 0 errors
- `mvn -o test` (full unit suite) — 868 tests, 0 failures, 0 errors, 1 skipped (pre-existing skip, unrelated to this story)
- `npx eslint .` (full frontend) — clean
- `shellcheck -x deploy/backup/*.sh` (run with CWD `deploy/backup/`) — clean on all 6 files (`env-guard.sh` + 5 callers)
- Manual guard-branch verification via `SKILLARS_ENV_FILE` overrides against `pg-backup.sh`, `prune-backups.sh`, `restore-from-volume-backup.sh`, `restore-from-dump.sh` — all four branches (missing file, directory, missing vars, guard-passes-and-proceeds) fired the expected tagged messages

### Completion Notes List

- All 6 ACs implemented as specified. No deviations from the story's Dev Notes reference implementation for `env-guard.sh`.
- **AC3/AC4 (logging):** Both `ConfigService.getBoolean` overloads now log a `WARN` when the stored value is present but not case-insensitively `"true"`/`"false"`; behavior (returned boolean) is unchanged in both, verified by two new unit tests (`getBoolean_singleArg_returnsFalseForInvalidPresentValue`, `getBoolean_withDefault_returnsFalseNotDefaultForInvalidPresentValue`) asserting the invalid value still returns `false` and does not fall through to a `true` default. No log-assertion test infrastructure was added, per the story's explicit instruction — verified by reading the diff and observing the WARN lines in the test run's console output instead. `HomeworkAssignmentService.handleBookingCompleted` now logs at `DEBUG` (matching the tone of the existing idempotency-skip `log.debug` two lines below) when `sessionId` resolves to `null`; the existing test `handleBookingCompleted_withDrills_createsAssignments` continues to pass unchanged.
- **AC5 (shellcheck invocation nuance, found during implementation, not in the story text):** `shellcheck -x deploy/backup/*.sh` only resolves the `# shellcheck source=env-guard.sh` directive cleanly when shellcheck's **working directory** is `deploy/backup/` itself (i.e. `cd deploy/backup && shellcheck -x *.sh`, or equivalently `shellcheck -x -P SCRIPTDIR deploy/backup/*.sh` from anywhere) — running the exact literal command `shellcheck -x deploy/backup/*.sh` from the **repo root** reproduces `SC1091 (info): Not following: env-guard.sh: openBinaryFile: does not exist`, because this shellcheck version (0.11.0) resolves a relative `source=` path against the invocation's CWD, not the referencing script's own directory, even with `-x`. This is a shellcheck-invocation detail, not a script defect — all 6 files are confirmed clean once invoked from the correct directory (or with `-P SCRIPTDIR`). Recording this since the story's Task 5 wording (`shellcheck -x deploy/backup/*.sh`) reads as directory-independent and isn't, on this shellcheck version.
- **AC5 (manual verification):** performed against `pg-backup.sh` (all 4 branches, including confirming the guard-pass case proceeds to the script's first `docker compose` line and fails there for unrelated reasons — exit 14, no guard error) plus a spot-check of the "missing file" / "directory" / "missing vars" branches on `prune-backups.sh`, `restore-from-volume-backup.sh`, and `restore-from-dump.sh` respectively, since all five scripts share the same `env-guard.sh` function.
- **AC6 (ledger hygiene):** all annotations required by AC6 (5 `[CLOSED by skillars-deferred-21 ACn]` tags + 2 `[AUDIT ...]` tags) were found **already present** in `deferred-work.md` and `sprint-status.yaml`'s `ready-for-dev` line already present — both were written during story creation (see `git status` at session start showing both files already modified before any dev work began). No further edits were needed for AC6 beyond this story's own Status/Task-checkbox/Dev-Agent-Record updates; verified the annotation text against the story's **Deferred Items Closed** table and **Explicitly NOT in this story** section and confirmed an exact match.
- Full mvn -o test (unit only, no ITs) run as the regression check, consistent with the story's own Dev Notes ("`mvn -o test` (targeted, not full verify) is sufficient for AC3/AC4 ... none of them have ever run these scripts against live Hetzner Object Storage credentials in this environment either") — no migration, no schema change, so a full `mvn -o verify` IT run was deliberately not performed.
- **Review follow-up (2026-08-14):** 2 action items from the code review resolved. (1) The Decision item on AC2's polling-fallback gap — user chose "(c) both": `VideoEventResource.computeDisplayState` now translates `PURGED`→`DELETED` (mirroring `VideoSseService.onVideoPurged`'s existing SSE-side translation), and `VideoManagementPage.vue`'s `onStatusChanged` + `video.store.js`'s `TERMINAL_SSE_STATES` regained `PURGED` handling as a documented defensive backstop. New `VideoSseIT.getStatus_purgedVideo_displayStateIsDeleted` IT added (7/7 passing in `VideoSseIT`). (2) The Patch item — `ConfigService.getBoolean`'s duplicated invalid-value warning logic extracted into a private `parseBoolean` helper shared by both overloads; behavior and per-overload log wording unchanged, `ConfigServiceTest` re-run green (11/11). Full `mvn -o test` re-run after both fixes: 868 tests, 0 failures, 0 errors. Full `npx eslint .` re-run: clean.

### File List

- `src/frontend/src/api/development.api.js` (AC1 — removed dead `getNeglectedSkills` export)
- `src/frontend/src/pages/VideoManagementPage.vue` (AC2 — removed dead `PURGED` branch; review follow-up restored it as a defensive backstop)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` (AC3 — added WARN logging to both `getBoolean` overloads; review follow-up deduped into a shared `parseBoolean` helper)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java` (AC3 — 2 new unit tests)
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java` (AC4 — added DEBUG logging on null sessionId)
- `deploy/backup/env-guard.sh` (AC5 — new shared guard library)
- `deploy/backup/pg-backup.sh` (AC5 — guard consolidated)
- `deploy/backup/volume-backup.sh` (AC5 — guard consolidated)
- `deploy/backup/restore-from-volume-backup.sh` (AC5 — guard consolidated)
- `deploy/backup/restore-from-dump.sh` (AC5 — guard consolidated)
- `deploy/backup/prune-backups.sh` (AC5 — guard consolidated, dead `ENV_FILE` var removed)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC6 — ledger annotations, already present from story creation)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status line: `ready-for-dev` → `in-progress` → `review`)
- `src/main/java/com/softropic/skillars/platform/video/api/VideoEventResource.java` (review follow-up — PURGED→DELETED translation in `computeDisplayState`)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSseIT.java` (review follow-up — new IT for the PURGED translation)
- `src/frontend/src/stores/video.store.js` (review follow-up — `PURGED` added to `TERMINAL_SSE_STATES`)

## Change Log

| Date | Change |
|---|---|
| 2026-08-13 | Story implemented: dead code removed (AC1/AC2), silent-failure logging added (AC3/AC4), backup script env-guard consolidated into shared `env-guard.sh` (AC5), ledger hygiene confirmed already applied from story creation (AC6). Status → review. |
| 2026-08-14 | Code review follow-up: PURGED→DELETED polling-fallback gap fixed both server-side and client-side per Mbah's decision; `ConfigService.getBoolean` duplication patched into a shared helper. Full regression re-run green. |
