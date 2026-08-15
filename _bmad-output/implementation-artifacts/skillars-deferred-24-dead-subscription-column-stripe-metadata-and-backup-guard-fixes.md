# Story Deferred-24: Dead Subscription Column, Stripe Metadata Mislabel, Backup-Script Guard Gaps & Quota-Release Test Coverage

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want five small, independently-verified deferred items closed — a dead subscription column with
no remaining reader, a coach-facing Stripe customer record whose dashboard metadata always claims
"parentId" even when the caller is a coach, a backup-credentials guard with no file-permission check,
five backup/restore scripts whose `env-guard.sh` sourcing line fails with a raw untagged shell error
instead of the family's own error convention, and a drill-video-replacement code path whose only test
coverage stops at "the deletion event was published" rather than "the quota it held was actually freed",
so that each of five unrelated, previously-deferred defects — spanning the payment module, Stripe
integration, ops scripts and video-quota test depth — gets fixed without bundling any of them into a
larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit
as `skillars-deferred-11`, `-20`, `-21`, `-22` and `-23`. All items below were independently re-verified
against **current** code during this story's creation, not trusted from the ledger's text, which the
ledger's own header warns can be stale:

- Two items surfaced during `skillars-uat-6-coach-subscription-and-volume-backup` story creation
  (2026-08-13), never claimed by a subsequent story.
- Two items surfaced by the code review of `skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening`
  (2026-08-14), explicitly scoped out of that story's AC5 (readability/content only) as their own
  follow-up.
- One item surfaced by the code review of `skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes`
  (2026-08-14), flagged there as "a real test-depth gap, not a functional defect."

## Deferred Items Closed

| Source | Item | Current location (re-verified) | AC |
|---|---|---|---|
| `skillars-uat-6-coach-subscription-and-volume-backup` story creation (2026-08-13) D1 | `payment.coach_subscriptions.stripe_customer_id` column has no remaining reader anywhere in production code | `PaymentCoachSubscription.java:41-42`, `V64__subscription_tiers.sql:16` | 1 |
| `skillars-uat-6-coach-subscription-and-volume-backup` story creation (2026-08-13) D2 | `StripePaymentGateway.createStripeCustomer` tags every Stripe-side customer's metadata `parentId=<id>` even when the caller is a coach | `StripePaymentGateway.java:152-160` | 2 |
| code review of `skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening` (2026-08-14) | `env-guard.sh`'s `require_env_vars` sources `.env` with no file-mode/ownership check — a world-readable/writable `.env` sources without warning | `deploy/backup/env-guard.sh` | 3 |
| code review of `skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening` (2026-08-14) | the `. ".../env-guard.sh"` sourcing line in all 5 callers has no existence/readability guard of its own — a missing `env-guard.sh` fails via a raw untagged shell error instead of the family's `[<tag>][error]` convention | `deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-dump.sh`, `restore-from-volume-backup.sh`, `prune-backups.sh` | 4 |
| code review of `skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes` (2026-08-14) | AC5's new `DrillUploadServiceTest` tests assert `VideoPhysicalDeletionEvent` publication but never prove the quota reservation the old video held is actually released | `DrillUploadServiceTest.java`, new IT in `DrillUploadResourceIT.java` | 5 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **`skillars-uat-6` D3** (no dedicated `SubscriptionResourceIT` covering all 8 subscription endpoints
  for both roles) — the ledger's own text calls this "a separate, larger testing initiative than this
  story's AC1-2 surface"; still true here, not a bundled slot.
- **`skillars-uat-6`'s "Ops note"** (stale `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` in an already-provisioned
  node's live `/opt/skillars/.env`) — nothing to change in this repo; it is a note for whoever next
  touches a live server `.env` file, not a code change.
- **`ConfigService.getBoolean` fails open for a misconfigured-but-present security-sensitive value**
  (deferred-21 review) — already logged at WARN; making it alertable needs metrics/alerting
  infrastructure this codebase doesn't have yet, not a script/annotation fix.
- **The rest of the `skillars-deferred-22` review's deferred items** (`initiateUpload`'s own
  check-then-act TOCTOU mirroring the already-accepted `Def14` race; `SessionPlanService.buildResponse`'s
  pre-save/post-save coupling; feature-gate misconfiguration alerting) — all three are explicitly
  recorded by that review as pre-existing, accepted-risk, or needing dedicated alerting infra. Not
  revisited.
- **`skillars-deferred-23`'s own two review-deferred items** (`ci.yml`'s residual `needs: test`
  ordering race; `PlaybackServiceIT`'s structural wall-clock assertion) — already explicitly documented
  as accepted, pre-existing limitations by that story's own ACs. Not revisited.
- **The systemic Hibernate/Postgres `jakarta.persistence.lock.timeout` no-op finding** (filed by
  `skillars-deferred-23` under its own "Deferred from" heading, 2026-08-14) — needs a real design
  decision (switching all four `findByIdForUpdate` repositories to `NO_WAIT` + retry, or an explicit
  `SET LOCAL lock_timeout`) with its own regression proof across the payment and booking modules. Not a
  bundled-fix candidate.
- The broad body of pre-2026-08 deferred items and all `deploy-1`/`deploy-2`/`deploy-3` items outside
  the two picked here — never re-verified against current scripts by any recent audit.

## Acceptance Criteria

1. **The dead `payment.coach_subscriptions.stripe_customer_id` column is dropped, and its JPA field is
   removed.** `PaymentCoachSubscription.stripeCustomerId` (`PaymentCoachSubscription.java:41-42`) is
   written by nothing in production code — `SubscriptionService.subscribeCoach` resolves the Stripe
   customer id from the separate, still-live `payment.stripe_customers` table
   (`stripeCustomer.getStripeCustomerId()` at `SubscriptionService.java:130`/`:301`, backed by the
   `StripeCustomer` entity, a different class/table entirely) — confirmed by `grep -rn
   "PaymentCoachSubscription" src/main/java/` returning zero `.setStripeCustomerId(`/`.getStripeCustomerId()`
   calls on any `PaymentCoachSubscription` instance anywhere in `src/main/java/`. The player-subscription
   equivalent, `payment.player_subscriptions`, never had this column at all — confirming the design intent.
   Add a new Flyway migration `V96__drop_coach_subscription_stripe_customer_id.sql` that runs `ALTER TABLE
   payment.coach_subscriptions DROP COLUMN stripe_customer_id;`, and remove the `@Column(name =
   "stripe_customer_id") private String stripeCustomerId;` field from `PaymentCoachSubscription.java`
   (`:41-42`). `SubscriptionLifecycleIT.java`'s `setUpCoach()` (`:64-70`) seeds this exact column with an
   explicit comment admitting it is "unused by the `subscribeCoach()` code path under test; left as
   harmless dead data" — after the column is dropped that `INSERT ... (coach_id, stripe_customer_id)`
   statement will fail at runtime; update it to `INSERT INTO payment.coach_subscriptions (coach_id) VALUES
   (?) ON CONFLICT (coach_id) DO NOTHING` (drop the now-nonexistent column and its now-meaningless
   `STRIPE_CUSTOMER_ID` seed value from that statement) and delete the stale comment explaining why the
   column was harmless dead data. Do **not** touch `payment.stripe_customers`/`StripeCustomer.java` — that
   is a different table/entity and is very much alive.

2. **`StripePaymentGateway.createStripeCustomer`'s Stripe-side metadata reflects the actual caller, not
   a hardcoded "parentId".** `createStripeCustomer(Long parentId)` (`StripePaymentGateway.java:152-160`)
   unconditionally calls `.putMetadata("parentId", parentId.toString())` (`:154`). Its two REST callers —
   `SessionPackPaymentResource.createSetupIntent` and `.savePaymentMethod`
   (`SessionPackPaymentResource.java:142-183`) — are both annotated
   `@PreAuthorize(SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE)`, so a coach calling either endpoint
   (to set up a card for a coach subscription, via `SubscriptionService.subscribeCoach`'s own dependency
   on this same `payment.stripe_customers` row) gets a Stripe Customer object whose dashboard metadata
   still says `parentId`. Cosmetic — Stripe-dashboard-only, no functional impact — but mislabeled for a
   real, live call path, not a hypothetical one. Fix: rename the metadata key from `"parentId"` to
   `"userId"` (`StripePaymentGateway.java:154`) — a role-neutral label that is accurate for both callers
   without adding any new role-tracking column or parameter. This deliberately does **not** attempt to
   pass the caller's actual role through to Stripe metadata — `uat-6`'s own Dev Notes explicitly call out
   "do not add a `payer_type` column" as this design's intentional shape (the `payment.stripe_customers`
   table is a single opaque-id table shared by both roles by design); a role-neutral metadata key label is
   the smallest fix consistent with that decision. Leave the `Long parentId` parameter name and the
   `PaymentGateway` interface signature (`PaymentGateway.java:28`) untouched — renaming a parameter across
   the interface and all call sites is a larger, purely-cosmetic rename with no bug-fix value; only the
   Stripe-visible metadata key string changes.

3. **`env-guard.sh`'s `require_env_vars` warns when the `.env` file it is about to source is
   world-readable or world-writable.** Today (`deploy/backup/env-guard.sh:10-19`) the function checks
   that `$env_file` is not a directory and is readable, then sources it unconditionally — no check of the
   file's permission bits. A `/opt/skillars/.env` accidentally left `644`+group or `666` sources without
   any warning, even though it holds `HOS_ACCESS_KEY`/`HOS_SECRET_KEY`/`POSTGRES_PASSWORD` and similar
   credentials. Add a permission check between the existing readability check (`:14-17`) and the `source`
   line (`:19`): read the file's octal mode (`stat -c '%a' "$env_file"` — this project's deploy target is
   Ubuntu-only per `prune-backups.sh:14`'s existing GNU-only precedent, so GNU `stat`'s `-c` flag is safe
   to rely on), and if the mode has any group- or other- read/write bit set (`mode & 0077 != 0` — e.g. via
   `(( 8#$perm & 0077 ))` in bash arithmetic), print `[${tag}][warn] ${env_file} is readable/writable by
   group or others (mode ${perm}) — credentials should be 600, owned by the deploying user` to stderr.
   **Warn, do not fail** — matching the ledger item's own wording ("sources without warning", not "must
   block"); a hard failure here would turn an existing, already-deployed `.env` with slightly loose
   permissions into a backup-cron outage, which is a worse failure mode than a warning. Do not change the
   directory-check or readability-check branches (`:10-17`), which already correctly `exit 1`.

4. **All 5 callers' `env-guard.sh` sourcing line fails with the family's own tagged error format, not a
   raw untagged shell error.** Every one of `pg-backup.sh` (`:6-7`), `volume-backup.sh` (`:9-10`),
   `restore-from-dump.sh` (`:14-15`), `restore-from-volume-backup.sh` (`:12-13`) and `prune-backups.sh`
   (`:31-32`) sources `env-guard.sh` via `. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"`
   with no existence/readability check of its own. Under `set -euo pipefail` (present in all 5), a missing
   `env-guard.sh` (e.g. a partial/corrupted deploy) still fails the script and exits non-zero — but via a
   bare `bash: .../env-guard.sh: No such file or directory` message, not this family's own `[<tag>][error]`
   convention that every other failure mode in these scripts uses. Fix each of the 5 callers identically:
   resolve the guard path into a variable first, check it with `[ ! -r "$GUARD_PATH" ]`, print a tagged
   error and `exit 1` if unreadable, then source the now-verified path — mirroring `env-guard.sh`'s own
   `.env`-readability-guard shape exactly:
   ```bash
   GUARD_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
   if [ ! -r "$GUARD_PATH" ]; then
     echo "[<tag>][error] cannot read ${GUARD_PATH} — required for credential loading" >&2
     exit 1
   fi
   # shellcheck source=env-guard.sh
   . "$GUARD_PATH"
   ```
   Use each script's own existing tag literal exactly as already passed to its own `require_env_vars`
   call, so the new guard's tag matches that script's established identity: `pg-backup`, `volume-backup`,
   `restore-dump` (not `restore-from-dump` — `restore-from-dump.sh:16` already calls `require_env_vars
   "restore-dump" ...`, use that exact string), `restore-from-volume-backup`, `prune-backups`. Keep the
   existing `# shellcheck source=env-guard.sh` directive immediately above the `.` line in all 5, per
   `skillars-deferred-21`'s own established note that this directive only resolves correctly when
   `env-guard.sh` is checked in the same shellcheck invocation as its caller.

5. **A new integration test proves the full async chain from a drill-video replacement to the old
   video's quota reservation actually being released — not just that the deletion event was published.**
   `DrillUploadServiceTest`'s `initiateUpload_replacesProcessingVideo_releasesOrphanedReservation`
   (`DrillUploadServiceTest.java:121-150`, a pure-mock unit test) verifies only that
   `eventPublisher.publishEvent(...)` was called with a `VideoPhysicalDeletionEvent` carrying the right
   `videoId`/`drillId` — it cannot see past that boundary, since `DrillUploadService` itself never touches
   `QuotaProvider`. The actual release happens three hops downstream, entirely untested as a chain:
   `VideoPhysicalDeletionListener.onVideoPhysicalDeletion` (`@Async @TransactionalEventListener(phase =
   AFTER_COMMIT)`, `VideoPhysicalDeletionListener.java:19-27`) calls `adminVideoService.deleteVideo(event.videoId())`,
   which (`AdminVideoService.java:44-77`) finds the video's most recent `PENDING` `UploadSession` and calls
   `quotaProvider.release(s.getReservationHandle())` before marking it `EXPIRED`. `AdminVideoIT` already
   proves `AdminVideoService.deleteVideo` does this correctly in isolation
   (`deleteVideo_readyVideo_marksDeletedReleasesQuotaForPendingSession`,
   `AdminVideoIT.java:64-75`), but nothing proves the chain actually connects end-to-end starting from a
   coach's drill-video replacement — the specific scenario this AC's source item was filed against. Add a
   new test to `DrillUploadResourceIT.java` (extends `BaseSessionIT`, already has `instrCoachId`/
   `coachDrillId`/`INSTR_EMAIL` fixtures and a `videoProviderAdapter` `@MockitoBean` from `setUp()`):
   seed a `PROCESSING` video (raw `INSERT INTO main.videos ... operational_state = 'PROCESSING'`, owned by
   `instrCoachId`) with a `PENDING` `main.upload_sessions` row carrying a **`reservation_handle` that is a
   valid UUID string (e.g. `UUID.randomUUID().toString()`) — not an arbitrary label.**
   `QuotaService.release(String reservationHandle)` (`QuotaService.java:122-128`) opens with
   `UUID.fromString(reservationHandle)` and throws `IllegalArgumentException` if it doesn't parse; that
   throw happens inside `AdminVideoService.deleteVideo`'s `transactionTemplate.execute(...)` block
   (`AdminVideoService.java:68`) with no local `try`/`catch`, so it rolls back the whole block (neither
   `operational_state` nor the session `status` update commits) and then propagates up into
   `VideoPhysicalDeletionListener.onVideoPhysicalDeletion`'s `catch (Exception e) { log.error(...); }`
   (`VideoPhysicalDeletionListener.java:22-26`), which swallows it entirely — no exception surfaces
   anywhere the test can see. The `reservation_handle` column itself is a plain `VARCHAR`
   (`V16__upload_sessions.sql:8`, no format constraint), so a non-UUID insert compiles and runs fine, and
   `AdminVideoIT.seedPendingSession`'s *other* call sites in the same file use exactly that
   readable-label style (`"handle-1"`, `"handle-2"`, `"handle-get-session"` at `AdminVideoIT.java:105,116,117`)
   — only the one call site that actually exercises `deleteVideo()`'s release path
   (`AdminVideoIT.java:67`) happens to pass a real UUID. Getting this wrong doesn't fail loudly: the new
   test would seed cleanly, the `POST .../initiate` call would return 201, and the bounded wait below
   would simply time out on the two DB-state assertions with the real cause — an `IllegalArgumentException`
   on the async listener thread — visible only in a log line the test never asserts on. Mirror
   `AdminVideoIT.seedPendingSession`'s shape (`AdminVideoIT.java:174`, adapted to raw SQL/JDBC since this
   test extends `BaseSessionIT` not `BaseVideoIT`), but use its UUID-handle variant
   (`AdminVideoIT.java:67`), not its label-handle variants. Add a `session.drill_video_refs` row pointing
   `coachDrillId` at that video (`ref_count = 1`, matching this file's existing
   `deleteVideo_coachDrill_noOtherRef_returns204AndPublishesEvent` pattern at `:251-260`); then call `POST
   {DRILLS_BASE}/{coachDrillId}/video/initiate` as `INSTR_EMAIL` with a valid replacement payload (the
   existing `initiateUpload_instructorCoach_returns201WithUploadUrl` payload shape at `:132-137` is a
   template). Because the release happens on an `@Async` listener after commit, assert with a bounded wait
   — this codebase's own precedent is `VideoPurgedEventIT`'s `Awaitility.await()`/`Mockito.timeout(...)`
   usage (`VideoPurgedEventIT.java:20-59`); either is acceptable, pick whichever fits `DrillUploadResourceIT`'s
   existing JDBC-polling style better — that within 5 seconds: the old video's `main.videos.operational_state`
   row becomes `DELETED`, and the old `main.upload_sessions` row's `status` becomes `EXPIRED` (the exact
   two assertions `AdminVideoIT.java:70-75` already makes for the generic case, now proven reachable from
   the real trigger this AC is about).

6. **Ledger hygiene in `deferred-work.md`.** Annotate every item this story closes (see **Deferred Items
   Closed** table) with `[CLOSED by skillars-deferred-24 ACn]` at its current ledger location once
   implemented, following this file's established annotation convention (do not delete the original
   item text).

## Tasks / Subtasks

- [x] Task 1 — Drop the dead `stripe_customer_id` column (AC: #1)
  - [x] Add `src/main/resources/db/migration/V96__drop_coach_subscription_stripe_customer_id.sql` with
    `ALTER TABLE payment.coach_subscriptions DROP COLUMN stripe_customer_id;`
  - [x] Remove the `stripeCustomerId` field (`@Column(name = "stripe_customer_id")`) from
    `PaymentCoachSubscription.java:41-42`
  - [x] Update `SubscriptionLifecycleIT.java`'s `setUpCoach()` (`:64-70`): change the
    `payment.coach_subscriptions` seed `INSERT` to `INSERT INTO payment.coach_subscriptions (coach_id)
    VALUES (?) ON CONFLICT (coach_id) DO NOTHING` with only `coachId` as a bind param, and delete the
    now-inaccurate "Bootstrap coach subscription row with stripe_customer_id..." comment above it. Leave
    the `payment.stripe_customers` seed block (`:58-62`) untouched.
  - [x] Grep for any other reference to `PaymentCoachSubscription`'s `stripeCustomerId`/`StripeCustomerId`
    field (getter/setter or field name) across `src/main/java` and `src/test/java` to confirm the removal
    is complete — the story's own research found none beyond the two sites above, but re-verify at
    implementation time
  - [x] `mvn -o test -Dtest=SubscriptionLifecycleIT,TierEntitlementGatingTest,PastDueGracePeriodTest,RevenueReportingServiceTest,StripeWebhookVerificationTest`
    green (the 5 test classes that reference `PaymentCoachSubscription` per this story's research)

- [x] Task 2 — Fix the coach Stripe Customer metadata label (AC: #2)
  - [x] Change `.putMetadata("parentId", parentId.toString())` to `.putMetadata("userId",
    parentId.toString())` at `StripePaymentGateway.java:154`
  - [x] Grep for any test asserting the literal `"parentId"` metadata key on a `CustomerCreateParams`
    built by `createStripeCustomer` (e.g. via a captured `CustomerCreateParams` in a Stripe-gateway unit
    test) and update it to `"userId"` if found
  - [x] `mvn -o test -Dtest=StripePaymentGatewayTest` (or whatever test class covers this method — locate
    via `grep -rln "createStripeCustomer" src/test/java/`) green

- [x] Task 3 — Add a permission warning to `env-guard.sh` (AC: #3)
  - [x] Insert a permission check in `require_env_vars` between the existing readability check (`:14-17`)
    and the `source` line (`:19`): `stat -c '%a' "$env_file"`, then warn (not fail) via
    `[${tag}][warn] ...` to stderr if group/other read or write bits are set
  - [x] Manually verify both branches: a `600`-mode test file produces no warning; a `644`- or
    `666`-mode test file produces the warning and the script still proceeds to source it (warn, don't
    block) — use `SKILLARS_ENV_FILE` override as the existing test convention does (see
    `skillars-deferred-21`'s own manual-verification note for this pattern)
  - [x] `shellcheck deploy/backup/env-guard.sh` clean (or `shellcheck -x` from `deploy/backup/`, matching
    `skillars-deferred-21`'s documented CWD-dependent resolution quirk for the `source=env-guard.sh`
    directive)

- [x] Task 4 — Guard the `env-guard.sh` sourcing line in all 5 callers (AC: #4)
  - [x] Apply the `GUARD_PATH` existence/readability check (see AC4's code block) to `pg-backup.sh:6-7`,
    tag `pg-backup`
  - [x] Same for `volume-backup.sh:9-10`, tag `volume-backup`
  - [x] Same for `restore-from-dump.sh:14-15`, tag `restore-dump` (match the existing
    `require_env_vars "restore-dump" ...` call at `:16`, not the filename)
  - [x] Same for `restore-from-volume-backup.sh:12-13`, tag `restore-from-volume-backup`
  - [x] Same for `prune-backups.sh:31-32`, tag `prune-backups`
  - [x] Manually verify at least one caller's new guard fires correctly: temporarily rename
    `env-guard.sh` (or point `dirname` at an empty dir via a copy) and confirm the tagged error appears
    instead of a raw bash sourcing error, then restore
  - [x] `shellcheck -x deploy/backup/*.sh` (invoked from `deploy/backup/`, per the CWD-dependent
    `source=` resolution note above) clean on all 5 modified callers plus `env-guard.sh`

- [x] Task 5 — Prove the drill-upload quota-release chain end-to-end (AC: #5)
  - [x] Add a new test to `DrillUploadResourceIT.java` seeding a `PROCESSING` video + `PENDING` upload
    session with a reservation handle + a `drill_video_refs` row, per AC5's exact shape
  - [x] **`reservation_handle` must be `UUID.randomUUID().toString()`, not a readable label** —
    `QuotaService.release()` calls `UUID.fromString()` on it and a non-UUID value throws, silently
    rolling back `AdminVideoService.deleteVideo`'s transaction and getting swallowed by
    `VideoPhysicalDeletionListener`'s catch-all; the test would then just time out with no clue why
  - [x] Call `POST {DRILLS_BASE}/{coachDrillId}/video/initiate` to trigger the replacement
  - [x] Assert (with a bounded async wait — `Awaitility` or `Mockito.timeout`, matching
    `VideoPurgedEventIT`'s precedent) that the old video's `operational_state` becomes `DELETED` and its
    `PENDING` upload session's `status` becomes `EXPIRED`
  - [x] Add teardown for any new rows this test inserts beyond what `DrillUploadResourceIT`'s existing
    `@AfterEach` already cleans (check whether `main.videos`/`main.upload_sessions` cleanup already
    covers rows owned by `instrCoachId` — `:106-109` suggests it does; verify before assuming)
  - [x] `mvn -o verify -Dit.test=DrillUploadResourceIT` green (this is a new IT method in an existing IT
    class — run via failsafe/`verify`, not `test`, matching this project's established IT/unit split)

- [x] Task 6 — Ledger hygiene (AC: #6)
  - [x] Annotate all 5 closed items per the **Deferred Items Closed** table in `deferred-work.md` with
    `[CLOSED by skillars-deferred-24 ACn]`
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-24-...` entry status as this story progresses
    (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention

### Review Findings

- [x] [Review][Patch] `env-guard.sh`'s new `stat -c '%a'` call has no error handling — a `stat` failure
  (e.g. the env file is deleted/replaced in the narrow window between the readability check and the
  `stat` call) aborts the script via a raw, untagged bash error under `set -euo pipefail`, instead of
  this story's own `[<tag>][error]` convention. [deploy/backup/env-guard.sh:19]
- [x] [Review][Patch] The new `GUARD_PATH` sourcing guard in all 5 callers checks only
  `[ ! -r "$GUARD_PATH" ]`, not `-d`, unlike `env-guard.sh`'s own `env_file` check which explicitly
  guards against a directory before the readability check. A readable directory passes `-r`, so if
  `GUARD_PATH` ever resolved to a directory the script would fall through to `. "$GUARD_PATH"` and fail
  with a raw "Is a directory" error — the exact class of untagged error this story exists to eliminate.
  Low likelihood (requires a directory literally named `env-guard.sh`), but a one-line, unambiguous fix
  mirroring the existing pattern. [deploy/backup/pg-backup.sh, volume-backup.sh, restore-from-dump.sh,
  restore-from-volume-backup.sh, prune-backups.sh]
- [x] [Review][Defer] Stripe metadata key rename (`parentId`→`userId`) has no test asserting the
  metadata map content, so a future accidental revert would be silent. [StripePaymentGateway.java:154]
  — deferred, pre-existing (no such test existed before this change either; explicitly cosmetic/
  dashboard-only per AC2).
- [x] [Review][Defer] The `GUARD_PATH` existence/readability guard block is duplicated verbatim across
  all 5 caller scripts rather than factored into one shared function. [deploy/backup/pg-backup.sh,
  volume-backup.sh, restore-from-dump.sh, restore-from-volume-backup.sh, prune-backups.sh] — deferred,
  spec-directed (AC4's code block explicitly prescribes this exact per-caller shape); a DRY refactor
  here would need its own sign-off, not a silent deviation from the story's prescribed fix.
- [x] [Review][Defer] No inline comment on the `V96` migration or the `PaymentCoachSubscription` field
  removal explaining why the drop is safe (column always nullable, no backfill needed).
  [src/main/resources/db/migration/V96__drop_coach_subscription_stripe_customer_id.sql,
  PaymentCoachSubscription.java] — deferred, pre-existing convention (this repo's other schema-cleanup
  migrations carry no such comments either; the rationale lives in this story's Dev Notes).

## Dev Notes

- **Scope discipline.** Five small, independently-safe items across five different files/mechanisms — a
  dead column, a metadata string, a shell permission check, five shell sourcing guards, and one new IT.
  Do not use this as a pretext to "clean up while you're in there" on adjacent code (e.g. don't touch
  `payment.stripe_customers`/`StripeCustomer.java`, don't add a `payer_type` column, don't expand the new
  IT into a broader `DrillUploadResourceIT` refactor). If something adjacent looks wrong, note it as a new
  `deferred-work.md` item; don't fix it here.

- **AC1's migration must run cleanly against whatever state `V64`'s comment describes.** `V64`'s own
  header comment notes some columns in this migration file were designed assuming empty tables at
  deploy time; `stripe_customer_id` was always nullable (`V64__subscription_tiers.sql:16`, no `NOT NULL`),
  so `DROP COLUMN` needs no data migration or `USING` clause — this is a pure schema cleanup, not a data
  transformation. Do not add a `USING`/backfill step that isn't needed.

- **AC2 is deliberately the smallest fix consistent with the existing opaque-id design.** `uat-6`'s own
  story explicitly rejected adding a `payer_type` column to `payment.stripe_customers` to distinguish
  parent vs. coach rows — that decision stands. Renaming the metadata key to a role-neutral `"userId"`
  fixes the specific mislabeling (Stripe dashboards will no longer say "parentId" for a coach) without
  reopening that design question. Do not add role detection/branching logic to `createStripeCustomer`.

- **AC3 is a WARN, not a hard failure — this is a deliberate choice, not an oversight.** A `.env` file
  that has been in production with slightly loose permissions for months should not suddenly break the
  backup cron the next time this script runs. If you're tempted to make this `exit 1` for "consistency"
  with the other guards in this function, don't — the readability/directory checks guard against the
  script being unable to function at all; this one guards against a security *hygiene* issue that the
  script can still safely proceed past.

- **AC4's tag literals must match each script's own existing `require_env_vars` call, not the filename.**
  `restore-from-dump.sh` is the one place this trips people up — its own tag is `"restore-dump"`
  (`restore-from-dump.sh:16`), not `"restore-from-dump"`. Get this wrong and the new guard's tag won't
  match the rest of that script's error output, which is exactly the inconsistency this AC exists to
  remove.

- **AC5's IT extends `BaseSessionIT`, not `BaseVideoIT` — do not try to reuse `AdminVideoIT`'s
  `seedPendingSession` helper directly.** `DrillUploadResourceIT` already has its own JDBC-based seeding
  style (`insertTestVideo`, raw `jdbcTemplate.update` calls in `@BeforeEach`/tests) — follow that existing
  style for the new video + upload session rows rather than importing a helper from a different test base
  class. Check `main.upload_sessions`' actual column list (`id`, `video_id`, `provider_upload_id`,
  `status`, `reserved_bytes`, `reservation_handle`, `expires_at`, `created_at` per
  `UploadSession.java:17-49`) before writing the raw `INSERT` — don't guess column names.

- **This story touches Java (payment + session/video test), one SQL migration, and Bash — no other
  modules.** `mvn -o verify` (unit + IT, since AC5 adds a new IT method) plus a manual `shellcheck`/
  permission-check verification for the Bash changes is the full verification bar.

- **File paths this story touches:**
  - `src/main/resources/db/migration/V96__drop_coach_subscription_stripe_customer_id.sql` (new, AC1)
  - `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscription.java` (AC1)
  - `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java` (AC1)
  - `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` (AC2)
  - `deploy/backup/env-guard.sh` (AC3)
  - `deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-dump.sh`,
    `restore-from-volume-backup.sh`, `prune-backups.sh` (AC4)
  - `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` (AC5)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC6)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC6, status line only)

### Project Structure Notes

- All five ACs are same-file-or-narrower fixes to existing files, plus one new migration file and one new
  test method in an existing IT class — no new production classes expected.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses
  in `sprint-status.yaml` (the "DEFERRED WORK" block).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from:
  skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)" D1, D2 (AC1, AC2); "## Deferred from:
  code review of skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening
  (2026-08-14)" items 2, 3 (AC3, AC4); "## Deferred from: code review of
  skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes
  (2026-08-14)" AC5 test-depth item (AC5)
- [Source: src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscription.java:41-42;
  src/main/resources/db/migration/V64__subscription_tiers.sql:16] — confirms AC1's dead-column premise
- [Source: src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:100-147,
  600-650] — confirms `subscribeCoach` resolves the Stripe customer id from `StripeCustomer`
  (`payment.stripe_customers`), never from `PaymentCoachSubscription.stripeCustomerId`
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java:50-70]
  — the test's own comment already documents the column as "unused ... left as harmless dead data",
  independently corroborating AC1
  and current metadata shape
- [Source: src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java:140-183]
  — confirms `createSetupIntent`/`savePaymentMethod` are both callable by a coach
  (`HAS_PARENT_PLAYER_OR_COACH_ROLE`), so AC2's mislabeling is a real, reachable call path
- [Source: deploy/backup/env-guard.sh:1-29] — confirms AC3's current guard shape and the gap
- [Source: deploy/backup/pg-backup.sh:1-8, volume-backup.sh:1-11, restore-from-dump.sh:1-16,
  restore-from-volume-backup.sh:1-14, prune-backups.sh:1-33] — confirms AC4's current sourcing shape and
  each script's own tag literal
- [Source: src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java:120-150]
  — confirms AC5's current test only asserts event publication
- [Source: src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:60-119,
  VideoPhysicalDeletionListener.java:1-36,
  src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:44-77] — confirms
  AC5's full release chain: `initiateUpload` → `VideoPhysicalDeletionEvent` → `onVideoPhysicalDeletion`
  (`@Async`, `AFTER_COMMIT`) → `AdminVideoService.deleteVideo` → `quotaProvider.release(...)`
- [Source: src/test/java/com/softropic/skillars/platform/video/service/AdminVideoIT.java:64-75, 174-182]
  — precedent for AC5's exact assertion shape (`operational_state` → `DELETED`, session `status` →
  `EXPIRED`) and a `seedPendingSession`-style helper, generically proving the downstream half of the chain
  already works
- [Source: src/test/java/com/softropic/skillars/platform/video/service/VideoPurgedEventIT.java:1-60] —
  precedent for asserting an `@Async @TransactionalEventListener(AFTER_COMMIT)` chain with a bounded wait
  (`Mockito.timeout`/`Awaitility`)
- [Source: src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java:1-381] —
  existing fixtures (`instrCoachId`, `coachDrillId`, `INSTR_EMAIL`, `videoProviderAdapter` mock,
  `insertTestVideo` helper, teardown shape) AC5's new test builds on directly
- [Source: src/main/java/com/softropic/skillars/platform/video/repo/UploadSession.java:17-49] — confirms
  the exact column set for AC5's raw upload-session seed insert

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no test failures encountered during implementation.

### Completion Notes List

- **AC1**: Confirmed via grep that `PaymentCoachSubscription.stripeCustomerId` had zero
  `.getStripeCustomerId()`/`.setStripeCustomerId(` call sites anywhere in `src/main/java`. Added
  `V96__drop_coach_subscription_stripe_customer_id.sql`, removed the JPA field, and updated
  `SubscriptionLifecycleIT.setUpCoach()`'s seed insert to the column-free form the story specified,
  also cleaning up a second stale comment (line 55-57) that referenced the now-removed getter. Targeted
  suite (`SubscriptionLifecycleIT`, `TierEntitlementGatingTest`, `PastDueGracePeriodTest`,
  `RevenueReportingServiceTest`, `StripeWebhookVerificationTest`) green — 42 tests, 0 failures.
- **AC2**: Changed the metadata key from `"parentId"` to `"userId"` at `StripePaymentGateway.java:154`.
  Grepped for any test asserting the literal `"parentId"` metadata key — none found; `createStripeCustomer`
  has no existing unit-test coverage of its metadata content. `StripePaymentGatewayTest` still green (4
  tests).
- **AC3**: Added a permission check to `env-guard.sh`'s `require_env_vars` using `stat -c '%a'` (GNU-only,
  matching the established Ubuntu-target precedent) and bash arithmetic `(( 8#$perm & 0077 ))` to warn
  (not fail) on group/other read-or-write bits. Verified both branches in a Linux container (`bash:5`
  Docker image, since the local dev machine is macOS/BSD `stat`) matching the real GNU `stat` deploy
  target: 600 → no warning, 644/666 → tagged warning + script still proceeds (exit 0). `shellcheck
  env-guard.sh` clean.
- **AC4**: Applied the identical `GUARD_PATH` existence/readability check to all 5 callers
  (`pg-backup.sh`, `volume-backup.sh`, `restore-from-dump.sh`, `restore-from-volume-backup.sh`,
  `prune-backups.sh`), each with its own established tag literal (confirmed `restore-dump`, not
  `restore-from-dump`, matching that script's own `require_env_vars` call). Verified the guard fires with
  a tagged error instead of a raw bash sourcing error by running `pg-backup.sh` against a directory
  missing `env-guard.sh`. `shellcheck -x` clean on all 5 callers + `env-guard.sh`.
- **AC5**: Added
  `initiateUpload_replacesProcessingVideo_releasesOldReservationEndToEnd` to `DrillUploadResourceIT`,
  seeding a `PROCESSING` video + `PENDING` upload session with a real UUID `reservation_handle` (per the
  story's explicit warning that a non-UUID handle throws inside `QuotaService.release()`, silently
  rolling back `AdminVideoService.deleteVideo` and getting swallowed by
  `VideoPhysicalDeletionListener`'s catch-all) + a `drill_video_refs` row, then triggers the replacement
  via `POST .../video/initiate` and asserts — with a bounded `Awaitility.await()` — that the old video's
  `operational_state` becomes `DELETED` and its upload session's `status` becomes `EXPIRED`. No extra
  teardown needed: the existing `@AfterEach` already deletes videos/upload_sessions by `owner_id` and
  `drill_video_refs` by `drill_id`, both of which the new rows fall under. `mvn -o verify
  -Dit.test=DrillUploadResourceIT` green — 12 tests (11 existing + 1 new), 0 failures.
- **AC6**: Annotated all 5 closed `deferred-work.md` items — changing their pre-existing
  `[OWNED BY skillars-deferred-24 — story creation, 2026-08-15, ACn]` markers (added at story-creation
  time) to the file's established `[CLOSED by skillars-deferred-24 ACn]` convention. Updated
  `sprint-status.yaml`'s status line to `review`.
- Scope discipline held: did not touch `payment.stripe_customers`/`StripeCustomer.java`, did not add a
  `payer_type` column, did not expand the new IT beyond the one method the AC specified.
- **Review follow-up (2026-08-15)**: applied both `[Review][Patch]` findings. `env-guard.sh`'s `stat -c
  '%a'` call now checks its own exit status and prints a tagged `[${tag}][error]` (mirroring the
  existing readability-check branch) instead of letting a `stat` failure abort via a raw `set -euo
  pipefail` shell error — verified by shadowing `stat` in `PATH` to force a failure and confirming the
  tagged message fires. All 5 callers' `GUARD_PATH` guard now checks `-d` before `-r`, mirroring
  `env-guard.sh`'s own directory-then-readability check order — verified by pointing `GUARD_PATH` at an
  actual directory named `env-guard.sh` and confirming the tagged error fires instead of a raw "Is a
  directory" shell error. Re-ran the pre-existing AC3/AC4 manual verifications (600/644 permission
  branches, missing-`env-guard.sh` guard) to confirm no regression — all unchanged. `shellcheck -x`
  clean on all 6 modified scripts. The 3 `[Review][Defer]` findings needed no code change — verified
  they were correctly appended to `deferred-work.md` under a new "Deferred from: code review of
  skillars-deferred-24..." heading, matching the ledger's established convention.

### File List

- `src/main/resources/db/migration/V96__drop_coach_subscription_stripe_customer_id.sql` (new)
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscription.java` (modified)
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java` (modified)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` (modified)
- `deploy/backup/env-guard.sh` (modified)
- `deploy/backup/pg-backup.sh` (modified)
- `deploy/backup/volume-backup.sh` (modified)
- `deploy/backup/restore-from-dump.sh` (modified)
- `deploy/backup/restore-from-volume-backup.sh` (modified)
- `deploy/backup/prune-backups.sh` (modified)
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified)

## Change Log

| Date | Change |
|---|---|
| 2026-08-15 | Story created from `deferred-work.md` — 5 items grouped (dead subscription column, Stripe metadata mislabel, backup-script guard gaps ×2, quota-release test-depth gap). |
| 2026-08-15 | Pre-dev review found AC5's fixture spec would let an implementer seed a non-UUID `reservation_handle`, which `QuotaService.release()` rejects via `UUID.fromString()` — the throw rolls back `AdminVideoService.deleteVideo`'s transaction and is silently swallowed by `VideoPhysicalDeletionListener`'s catch-all, so the new test would fail on timeout with no visible cause. AC5 and Task 5 now require a real UUID handle and explain why. |
| 2026-08-15 | Implementation complete — all 6 ACs done. AC1: dropped `payment.coach_subscriptions.stripe_customer_id` (V96 migration + JPA field removal), fixed `SubscriptionLifecycleIT` seed; 42 targeted tests green. AC2: renamed Stripe metadata key `parentId`→`userId`; no existing test asserted the old key. AC3: added a warn-only permission check to `env-guard.sh`, verified both branches in a Linux container (macOS dev machine lacks GNU `stat`). AC4: guarded the `env-guard.sh` sourcing line in all 5 callers with tagged existence/readability checks; verified the tagged-error path fires correctly. AC5: added a new `DrillUploadResourceIT` test proving the full async quota-release chain end-to-end with a real UUID reservation handle; 12/12 tests green via `mvn -o verify -Dit.test=DrillUploadResourceIT`. AC6: annotated all 5 closed `deferred-work.md` items, updated `sprint-status.yaml` to `review`. Status: review. |
| 2026-08-15 | Code review complete — 2 patches applied, 3 deferred (new `deferred-work.md` entries under "Deferred from: code review of skillars-deferred-24..."). Patch 1: `env-guard.sh`'s `stat -c '%a'` call now handles its own failure with a tagged error instead of a raw `set -euo pipefail` abort. Patch 2: all 5 callers' `GUARD_PATH` guard now checks `-d` before `-r`, mirroring `env-guard.sh`'s own check order. Both verified by targeted failure-injection (shadowed `stat`; a real directory named `env-guard.sh`); pre-existing AC3/AC4 manual verifications re-run with no regression; `shellcheck -x` clean on all 6 scripts. |
| 2026-08-15 | Full `mvn -o verify` re-run after review patches: 898 tests, 0 failures, 0 errors, 4 skipped — BUILD SUCCESS, identical to the pre-patch run. Status: done. |
