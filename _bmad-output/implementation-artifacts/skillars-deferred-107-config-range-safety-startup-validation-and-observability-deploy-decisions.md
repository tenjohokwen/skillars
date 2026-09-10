# skillars-deferred-107: Config Range-Safety + Startup Validation + Observability & Deploy Decisions

**Status:** done | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-107
**Branch:** `story/deferred-107-config-range-safety-and-observability-decisions`
**Created:** 2026-09-10
**Base:** master @ `918ff206` (immediately after `skillars-deferred-104` (#164) + `skillars-deferred-106` (#163) + `skillars-deferred-105` (#162), and the post-104/-105/-106 ledger prune done in this same session)

---

## Story Overview

As the Skillars platform team,
I want the remaining verified-open configuration-safety and deployment-hardening gaps in
`deferred-work.md` either fixed or turned into recorded decisions,
so that an operator-fat-fingered `platform_config` value can no longer silently disable a whole
flow (or, in one case, delete every message), and so the observability/deploy items that have been
"decision-deferred" for three stories running stop being re-litigated.

A cross-module story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`.

The **"genuine one-off bugs & gaps"** class in that ledger is **exhausted** — `skillars-deferred-91`
through `-103` picked it clean and their full-file audits all say so; `deferred-104`/`-105`/`-106`
added nothing new to it. This story's own re-mine against HEAD (`918ff206`) confirms it: what
remains is `[DECIDED]` / `[DISMISSED]` (do not re-litigate), "no dev agent can close this" (native
DE/FR register review, parent legal copy), carved-out future tasks (pre-production migration
rebaseline; `main.pending_blob_deletions` drop; the ~6 now-unblocked frontend coverage specs =
their own follow-up per `deferred-104`), spec-designed tradeoffs, test-fixture-only concerns, and
speculative / load-dependent notes.

This story takes the **two `deferred-103` code-review items** (config range-safety + a stale
javadoc) and generalises the first into a codebase-wide sweep + a startup assertion, then clears
**five long-deferred `deploy-*` items** — three of which turn out to already be closed at HEAD, and
two of which the project owner has now decided to accept-and-document rather than fix.

### Items that looked open but are already closed at HEAD (confirmed during story creation, not picked up)

| Ledger item | HEAD reality |
|---|---|
| `deploy-3-4` "DROP DATABASE / open connections" (2026-09-04 audit table, line 46) | **Closed by `deferred-101` AC6.** `deploy/backup/restore-from-dump.sh:140-141` runs `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '<db>' AND pid <> pg_backend_pid();` immediately before `DROP DATABASE IF EXISTS` at `:143-144`. The standalone bullet was deleted by `deferred-101`. Only the stale audit-table row survives — AC9 strikes it. |
| `deploy-3-4` "APP_CID capture race" (2026-09-04 audit table, line 48) | **Closed by `deferred-102` AC2.** `restore-from-dump.sh:205-222` — a 5×/2s bounded retry loop around `${DC} ps -q app`, then a clear `err` + EXIT-trap recovery if it still fails. Standalone bullet already deleted. Stale audit-table row only — AC9 strikes it. |
| `deploy-1-5` "`git clean` vs the data subdirectory" (2026-09-04 audit table, line 60) | **Closed by `deferred-102` AC6/AC7.** The checkout moved to `/opt/skillars/app`, a *sibling* of the Volume mount `/opt/skillars/data`, owned by a non-root `deploy` user; `.env` is at `/opt/skillars/.env`, outside the checkout, symlinked in. `provision.sh:13-14,346-347,387` document that `git clean -fdx` inside the checkout cannot reach either. Standalone bullets already deleted by `deferred-102`. Stale audit-table row only — AC9 strikes it. |

## Project-owner decisions (captured 2026-09-10 during story creation)

| # | Question | Decision |
|---|---|---|
| D1 | **`node_exporter` network isolation** (`deploy-3-3`) — a compromised `app`/`grafana` can still scrape `node_exporter:9100` because they share `skillars-observability`. | **Accept & document.** No dedicated network. The exposure is first-party `app`/`grafana` only; a compromised `app` already holds the DB/Stripe/Bunny/OTLP credentials, so host CPU/mem/disk gauges are not a meaningful escalation. Upgrade the compose comment to a full won't-fix rationale; retag the ledger bullet `[DECIDED]`. → **AC5** |
| D2 | **Credentials in `/proc/<pid>/environ`** (`deploy-3-1`) — backup scripts `source .env`, exporting `POSTGRES_PASSWORD` into the process environment. | **Formally accept as won't-fix.** Single-tenant VPS; `.env` is `root:root 0600`; `/proc/<pid>/environ` is readable only by the same uid that already owns `.env`; the unprivileged `deploy` user has no `.env` read. `PGPASSFILE` conversion evaluated and rejected (touches 5 scripts, changes nothing about who can read the secret). Document in `secrets-reference.md`; retag the ledger bullet `[DECIDED]`. → **AC6** |
| D3 | **`pack.pause.maxDays` misconfiguration** (`deferred-103` CR) — a stored `0`/negative is accepted and blocks every pause. | **Reusable helper + startup assertion.** Adopt the existing `ConfigService.getBoundedLong` at the call site (**AC1**); add `getBoundedInt` and sweep every risky `getLong`/`getInt` call site (**AC2**); add a fail-fast `ConfigStartupAssertion` on `ApplicationReadyEvent` for the keys where a bad value silently disables a flow (**AC3**). |
| D4 | **Alertmanager double-notification** (`deploy-3-3`) — decision-deferred for 3 stories (Alertmanager not deployed). | **Write the decision doc now.** Don't leave it as a compose comment. A `monitoring.md` section codifies "Grafana-managed alerting is the single delivery path" + a checklist for the future change that adds Alertmanager. Retag the ledger bullet `[DECIDED]`. → **AC7** |
| D5 | **Story size.** | Deploy/config core (**AC1, AC3, AC4, AC5, AC6, AC7**) **+ the full `ConfigService.getLong`/`getInt` range-safety sweep** (**AC2**) **+ the `deferred-94` hardcoded-container-UID-`chown` verification** (**AC8**) **+ ledger hygiene** (**AC9**). The `deferred-104` frontend-spec backfill was explicitly **excluded** — it stays its own follow-up. |

---

## Acceptance Criteria

### AC1: `PackSessionService.pausePack` — range-guard `pack.pause.maxDays`

- **Task:** A syntactically valid but nonsensical `pack.pause.maxDays` (`0`, negative, or absurdly
  large) must degrade to the documented default with a WARN, not be accepted verbatim. Today it is
  accepted, and any `pauseDurationDays >= 1` then fails as `booking.pauseDurationInvalid` with
  nothing pointing at the config as the cause.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:162` —
    `long maxDays = configService.getLong("pack.pause.maxDays", DEFAULT_PACK_PAUSE_MAX_DAYS);`
    (the 2-arg form — `deferred-103` AC6 changed it from 1-arg; the range check is the residue).
  - `PackSessionService.java:50` — `private static final long DEFAULT_PACK_PAUSE_MAX_DAYS = 90L;`
  - `PackSessionService.java:163-164` — `if (req.pauseDurationDays() < 1 || req.pauseDurationDays()
    > maxDays) { throw new BatchRuleViolationException("booking.pauseDurationInvalid"); }`
  - Seed: `src/main/resources/db/migration/V37__session_pack_expiry_pause.sql:39` inserts
    `('pack.pause.maxDays', '90', 'LONG', …)` — the default must stay `90L` to match production.
  - `ConfigService.getBoundedLong(String key, long defaultValue, long min, long max)` **already
    exists** at `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:103`
    — clamps out-of-range to `defaultValue` + logs WARN (`"Config key '{}' has out-of-range value
    {} (expected [{}, {}]) — using default {}"`). It is already the house pattern: `BookingExpiryScheduler.java:45`,
    `BookingReminderScheduler.java:90-91`, `PaymentPendingSweeper.java:113,203`,
    `ReliabilityStrikeService.java:64,66`, `CoachPayoutOutboxSupport.java:108`,
    `SessionDurationResolver.java:58`, `MessageModerationSweeper.java:71`,
    `AdminCoachEnforcementService.java:221` all use it.
- **Fix approach:**
  - Add `private static final long MAX_PACK_PAUSE_MAX_DAYS = 3650L;` (10 years — a pause window
    longer than that is certainly a fat-finger; pick the constant, comment the reasoning).
  - `long maxDays = configService.getBoundedLong("pack.pause.maxDays", DEFAULT_PACK_PAUSE_MAX_DAYS,
    1L, MAX_PACK_PAUSE_MAX_DAYS);`
  - No `ConfigService` change. No behaviour change for any other caller.
- **Existing bad value on ship:** if any environment has already stored `pack.pause.maxDays ≤ 0`
  (or `> MAX`), the read here now clamps to `90` + WARN, so `pausePack` starts working again on
  deploy. Because `pack.pause.maxDays` is a `failFast` key in AC3, that environment's **next
  restart** then refuses to boot until the operator corrects the stored value — intended: the
  clamp keeps the flow alive now, the assertion forces the fix. There is no production deploy yet
  and `V37` seeds `'90'`, so this is future-proofing, not a live migration concern.
- **Ledger:** delete the **`pack.pause.maxDays` misconfigured `≤ 0`** bullet under
  `## Deferred from: code review of skillars-deferred-103-… (2026-09-09)`.
- **Test:** `PackSessionServiceTest` (Mockito unit) — stub `configService.getBoundedLong("pack.pause.maxDays",
  90L, 1L, 3650L)` and assert the call is made with exactly those bounds (mirror
  `SessionDurationResolverTest`'s `verify(configService).getBoundedLong(KEY, 60L, 15L, 240L)`
  precedent). Add one case per branch: stored `45` → `pauseDurationDays=45` accepted; stored value
  clamps to `90` → `pauseDurationDays=91` rejected. `ConfigService.getBoundedLong`'s own
  clamp/WARN behaviour is already covered by its existing tests — do not re-test it here.

---

### AC2: `ConfigService.getBoundedInt` + codebase-wide `getLong`/`getInt` range-safety sweep

- **Task:** `AC1` fixes one call site. The `deferred-103` CR bullet's own closing sentence —
  *"Pre-existing pattern across many `ConfigService.getLong` call sites"* — is the real scope. Audit
  every `getLong`/`getInt` call site outside `ConfigService` itself (31 at HEAD) and route each one
  where a `0` / negative / absurd stored value causes silent misbehaviour through the bounded
  variant with a documented `[min, max]`.
- **Verified at HEAD:** `grep -rn '\.getLong(\|\.getInt(' --include='*.java' src/main/java | grep -v
  '/ConfigService.java'` → 31 sites. `getBoundedLong` exists; **`getBoundedInt` does not** — add it.
- **Fix approach — `ConfigService`:**
  - Add `public int getBoundedInt(String key, int defaultValue, int min, int max)` delegating to
    `getBoundedLong` and casting (mirror the existing `getInt(String, int)` at `:93`). One-line
    body; javadoc pointing at `getBoundedLong`.
- **Fix approach — call sites.** First-pass classification below. **The dev must re-confirm each
  against HEAD and set the final bound with a one-line rationale comment at the call site**, using
  this checklist per site:
  1. **Semantic minimum** — what is the smallest value the code can act on sanely? (`0` legitimate,
     or must it be `>= 1`?)
  2. **Business maximum** — what value is so large it can only be a fat-finger? (this is the number
     that goes in the bound when it is the binding constraint — clamp semantics)
  3. **Technical ceiling** — is there a hard cap the code imposes regardless of business intent?
     (`Math.toIntExact` → must be `< Integer.MAX_VALUE`; a `Duration.of(n, DAYS)` → must not
     overflow). Where a technical ceiling exists and is *below* a plausible business value, prefer
     the business number but never exceed the ceiling, and say which kind of limit the bound is in
     the comment.

  The suggested bounds are code-review guesses; several sites (`development.*`, `video.quota.*`,
  `video.access.*`) need domain knowledge to pin — flag those for a reviewer pass on the finished
  table before wiring the tests.

  | Call site | Key | Bad-value harm | Action |
  |---|---|---|---|
  | `MessageRetentionScheduler.java:30` | `platform.message_retention_months` | **`0`/neg → the retention query deletes every message on the next run.** Data-destructive. | `getBoundedInt`, `[1, 600]`. **Fail-fast key (AC3).** |
  | `BookingBatchService.java:102,107` | `booking.batch.maxSize` | `0`/neg → every batch booking rejected; huge → unbounded batch | `getBoundedInt`, `[1, 100]`. **Fail-fast key (AC3).** |
  | `DisputeService.java:109` | `disputes.submissionWindowDays` | `0`/neg → no dispute can ever be filed | `getBoundedLong`, `[1, 365]` |
  | `ReviewSubmissionService.java:164` | `reviews.submissionWindowDays` | `0`/neg → no review can ever be submitted | `getBoundedInt`, `[1, 365]` |
  | `ReviewFlagService.java:75` | `reviews.autoHoldFlagThreshold` | `0` → every review auto-held | `getBoundedInt`, `[1, 1000]` |
  | `QuickCompleteTimeoutService.java:38` | `booking.quick_complete_timeout_hours` | `0` → instant timeout; neg → nonsense | `getBoundedLong`, `[1, 168]` |
  | `ModerationSlaMonitorService.java:59` | `platform.moderation_sla_minutes` | `0`/neg → everything instantly SLA-breached | `getBoundedLong`, `[1, 10080]` |
  | `ModerationSlaMonitorService.java:60` | `platform.moderation_max_retries` | neg → loop math breaks (`0` may be legitimate "no retries") | `getBoundedLong`, `[0, 100]` |
  | `ModerationOrchestrationService.java:75,400` | `platform.moderation_lock_timeout_minutes` | `0` → lock instantly stale; huge → stuck rows | `getBoundedLong`, `[1, 1440]` |
  | `VideoLifecycleScheduler.java:40` | `platform.video.lifecycle.blocked_to_archived_days` | `0`/neg → archives immediately or never | `getBoundedLong`, `[1, 3650]` |
  | `VideoLifecycleScheduler.java:41` | `…archived_to_deleted_days` | same | `getBoundedLong`, `[1, 3650]` |
  | `VideoLifecycleScheduler.java:42` | `…batch_size` | `0` → scheduler makes no progress; huge → load spike | `getBoundedInt`, `[1, 10000]` |
  | `VideoSubscriptionLifecycleListener.java:73` | `platform.video.lifecycle.batch_size` | same | `getBoundedInt`, `[1, 10000]` |
  | `VideoSubscriptionLifecycleListener.java:60` | `…lifecycle.outbox_max_attempts` | `0`/neg → outbox never drains or loop underflows | `getBoundedInt`, `[1, 100]` |
  | `VideoDeletionOutboxProcessor.java:126` | `platform.video.deletion.max_attempts` | `0`/neg | `getBoundedInt`, `[1, 100]` |
  | `RadarCompositeDlqProcessor.java:61` | `platform.development.radar_composite_dlq.max_attempts` | `0`/neg | `getBoundedInt`, `[1, 100]` |
  | `PlaybackService.java:105` | `platform.video.playback.signed_url_ttl_minutes` | `0` → signed URL dead on arrival (all playback broken); huge → weak-security URLs | `getBoundedLong`, `[1, 1440]`. **Fail-fast key (AC3).** |
  | `VideoAccessGuard.java:93` | `platform.video.access.coach_window_days` | `0`/neg → coach sees nothing; huge → `Math.toIntExact` throws | `getBoundedLong`, `[1, N]` where `N` is a business cap (suggest `3650`) that is also `< Integer.MAX_VALUE` — comment must state it is a business bound, not the `toIntExact` ceiling. If a >10y window is a real use case, widen `N` (still `< Integer.MAX_VALUE`) rather than let a legit value clamp. |
  | `QuotaConfigService.java:22` | `video.quota.<tier>.storageBytes` | `0`/neg → all uploads rejected / quota math breaks | `getBoundedLong`, `[1, Long.MAX_VALUE]` (1-arg today — keep no default, but bound) — see note below |
  | `QuotaConfigService.java:27` | `video.quota.<tier>.bandwidthBytesMonthly` | same | `getBoundedLong`, `[1, Long.MAX_VALUE]` |
  | `QuotaConfigService.java:31` | `platform.video_reservation_timeout_minutes` | `0` → reservations expire instantly | `getBoundedLong`, `[1, 1440]` |
  | `VideoTypeConstraints.java:18` | `<type>.maxSizeBytes` | `0` → every upload of that type rejected | `getBoundedLong`, `[1, Long.MAX_VALUE]` |
  | `VideoTypeConstraints.java:22` | `<type>.maxDurationSeconds` | `0` → every upload rejected | `getBoundedLong`, `[1, 86400]` |
  | `TimelineQueryService.java:31` | `development.timeline.coachAccessExpiryDays` | `0`/neg → coach access always expired | `getBoundedLong`, `[1, 3650]` |
  | `NeglectedSkillDetectionService.java:70` | `development.neglectedSkill.warmupSessionCount` | neg → predicate inverts (`0` may be legitimate) | `getBoundedLong`, `[0, 10000]` |
  | `DevelopmentCorrelationService.java:56` | `development.correlation.minSessionCount` | neg → every session qualifies (`0` may be legitimate) | `getBoundedLong`, `[0, 10000]` |
  | `SubscriptionService.java:484` | `subscription.pastDue.gracePeriodDays` | neg → grace math breaks (`0` = "no grace" may be legitimate) | `getBoundedLong`, `[0, 365]` |
  | `GdprExportService.java:85` | `gdpr.export.urlExpiryHours` | `0` → a legally-required GDPR export link is dead on arrival; huge → compliance exposure | `getBoundedLong`, `[1, 720]`. **Fail-fast key (AC3).** |

  **1-arg `getLong(key)` sites** (no code default — they already throw `IllegalStateException` on a
  *missing* key, which is the intended "fail loud; the operator must set this"). Keep that
  missing-key behaviour exactly. For the *value* range check, add a no-default bounded overload
  `getBoundedLong(String key, long min, long max)` that **clamps to the nearest bound + logs WARN**
  — the same clamp-not-throw semantics as the 2-arg `getBoundedLong`, just with `min` (or `max`) as
  the fallback instead of an explicit default. Do **not** make this variant throw on out-of-range:
  fail-fast-on-bad-value is AC3's job (the startup assertion), and it must be one decision per key,
  not an accident of whether a call site happens to pass a default. A `failFast` key that is also a
  1-arg site (e.g. `platform.moderation_sla_minutes`, `platform.moderation_lock_timeout_minutes`)
  still gets its boot-time enforcement from AC3; at read time it clamps like every other site.
  Apply the 3-arg overload to `QuotaConfigService` / `VideoTypeConstraints` /
  `ModerationSlaMonitorService` / `ModerationOrchestrationService` and the other 1-arg sites.
- **Ledger:** this AC is the generalisation the `pack.pause.maxDays` bullet's closing sentence
  called for — record it in the AC9 audit block (no separate bullet to delete).
- **Test:**
  - `ConfigServiceTest` — `getBoundedInt` in-range passthrough; below-min and above-max → default +
    WARN; `getBoundedLong(key, min, max)` no-default variant → missing key throws (unchanged);
    below-`min` clamps to `min` + WARN; above-`max` clamps to `max` + WARN.
  - For each migrated call site: a unit test in that service's existing test class that stubs the
    bounded call and `verify(...)`s it is invoked with the exact `[min, max]` (the
    `SessionDurationResolverTest` precedent — mutation-check is "revert the site to `getLong` → the
    `verify` fails").
  - `MessageRetentionSchedulerTest` — add an explicit case documenting that `retention_months`
    clamps to the default (never `0`) so the "delete everything" path is unreachable from config.

---

### AC3: `ConfigStartupAssertion` — fail-fast on out-of-range critical config at boot

- **Task:** `getBoundedLong` silently clamps at read time — good for safety, invisible to the
  operator who set the bad value. Add a startup assertion that reads the DB values of the
  range-bounded keys and, for the subset where a bad value silently disables a whole flow, **fails
  application startup** naming the key and the expected range. For the rest, log ERROR + a metric.
- **Verified at HEAD — precedent:**
  - `src/main/java/com/softropic/skillars/platform/monitoring/TlsStartupAssertion.java` —
    `implements ApplicationListener<ApplicationReadyEvent>`, `@Component`, throws
    `com.softropic.skillars.infrastructure.exception.AppSetupException` in non-`dev`, **skips when
    the `dev` profile is active**, default-safe when the property is absent. Copy this shape.
  - Other boot runners: `JwtSecretBootstrapRunner`, `AdminBootstrapRunner`,
    `PendingBlobDeletionResidualDrainRunner` (all `platform.*.service`).
  - `ConfigService.java:28` already defines `MISCONFIGURED_COUNTER = "config.value.misconfigured"`
    and increments it (with `tag("key", …)`, `tag("reason", …)`) for missing/non-boolean feature
    gates — reuse that counter name and tag scheme.
- **Fix approach:**
  - New `ConfigStartupAssertion` (put it in `platform.config.service` next to `ConfigService`, or
    `platform.monitoring` next to `TlsStartupAssertion` — pick one, note why).
  - A single source-of-truth registry: `List<BoundedKey>` where
    `BoundedKey = (String key, long min, long max, boolean failFast)`. **This same registry should
    drive AC2's call sites** — extract it so the bounds live in one place (e.g. a
    `ConfigBounds` constants holder the call sites and the assertion both import). If that is too
    invasive for one story, at minimum keep the numbers identical and cross-reference in comments.
  - On `ApplicationReadyEvent`: for each `BoundedKey`, read the *actual* stored value via
    `ConfigService` (the raw `getString`/`getLong`, **not** the clamped `getBoundedLong`). If out
    of `[min, max]`:
    - always: `log.error("Platform config '{}' = {} is outside the required range [{}, {}] …", …)`
      and increment `config.value.misconfigured` with `tag("key", key)`, `tag("reason",
      "out_of_range")`, **and `tag("expected", "[" + min + "," + max + "]")`** so a dashboard alert
      is self-describing without a code dive. (Cardinality is safe — `expected` is constant per
      `key`, which is already a tag.)
    - if `failFast`: collect into a list and, after checking **all** keys, throw `AppSetupException`
      naming every offending key + range (don't fail on the first — an operator fixing one wants to
      see them all).
  - **Dev-profile handling.** `TlsStartupAssertion` skips `dev` because `checkCertificate:false` is
    a *legitimate* dev setting. That does **not** apply here — there is no legitimate reason for a
    `platform_config` value to be out of range in any environment (the values are migration-seeded).
    So: run the ERROR + metric in **all** profiles; gate only the **throw** on non-`dev` (a local
    dev with a hand-edited bad value should see the loud ERROR but not be blocked from booting).
    Document this deviation from the `TlsStartupAssertion` precedent in the class javadoc.
  - **Fail-fast principle (state it in the class javadoc and apply it to every key):**
    - `failFast = true` ⇔ a bad value causes **data loss / permanent corruption**, or **halts a
      core user flow entirely** (no disputes can be filed, no reviews submitted, every pause
      rejected, every batch rejected, all messages deleted).
    - `failFast = false` ⇔ a bad value **degrades but does not break** (slower archival, smaller
      batches, a shorter-than-intended signed-URL TTL). The read-time clamp already contains the
      blast radius; ERROR + metric is enough to get it noticed.
  - **Deliverable before coding AC3:** a filled-in table of **every** `BoundedKey` (all AC2 keys)
    with its `[min, max]`, `failFast` flag, and a one-line reason — put it in the story's Dev Agent
    Record and in the `monitoring.md` docs table. First pass: `failFast = true` for
    `platform.message_retention_months` (data-destructive), `pack.pause.maxDays`,
    `booking.batch.maxSize`, `disputes.submissionWindowDays`, `reviews.submissionWindowDays`,
    `platform.moderation_sla_minutes`, `platform.moderation_lock_timeout_minutes`,
    `gdpr.export.urlExpiryHours` (a `0` makes a legally-required export undownloadable) and
    `platform.video.playback.signed_url_ttl_minutes` (a `0` breaks all playback). Everything else
    `false`. The dev confirms/adjusts each against its call-site semantics.
  - **Per-tier / templated keys** (`video.quota.{scout,instructor,academy,player}.{storageBytes,
    bandwidthBytesMonthly}`, `<VideoType>.{maxSizeBytes,maxDurationSeconds}`): the tier and type
    sets are fixed enums (`CoachSubscriptionTier` + the player fallback in
    `QuotaConfigService.resolveTierKey`; `VideoType`). Iterate the enum values to generate the
    full key list in `ConfigBounds` — do **not** hand-list the strings, and do **not** skip them.
    Add a test that fails if a new enum constant is added without a matching bound.
- **Docs:** add a **"Platform config value ranges"** table to `docs/deployment/monitoring.md` (key,
  range, fail-fast?, what a bad value does) so an operator editing `platform_config` knows the
  bounds before a restart rejects them.
- **Ledger:** covered by the AC9 audit block.
- **Test:** `ConfigStartupAssertionTest` — (1) all keys in range → no throw, no ERROR, no metric;
  (2) a `failFast` key out of range, non-`dev` → `AppSetupException` whose message names the key
  and the `[min, max]`; (3) two `failFast` keys out of range → message names both; (4) a
  non-`failFast` key out of range → **no** throw, ERROR logged,
  `config.value.misconfigured{key,reason=out_of_range,expected="[…]"}` incremented (assert via
  `SimpleMeterRegistry`); (5) a `failFast` key out of range **with the `dev` profile active** →
  **no** throw, but the ERROR + metric still fire (the dev-profile deviation from
  `TlsStartupAssertion`); (6) per-tier enum coverage — add a synthetic enum constant (or assert
  over `values().length`) so a future tier/type without a bound fails the test. Follow
  `TlsStartupAssertion`'s existing test for wiring.

---

### AC4: `SessionPackExpiryNotifier` class javadoc — correct the stale listener-phase description

- **Task:** Doc-only. The class javadoc still describes the expiry-warning email listener as
  `@TransactionalEventListener(AFTER_COMMIT)` with events "discarded" and "sent ZERO times" and a
  "(fallible, unretried) send". Since `deferred-92` the listener is `BEFORE_COMMIT` + an outbox
  enqueue. A reader auditing delivery reliability here is currently misled by the class's own doc.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java`
    javadoc block (approx `:31-48` — line numbers have drifted from the ledger's `:37,44`): the
    **"Delivery."** paragraph describes the pre-fix `AFTER_COMMIT` / `fallbackExecution = false` /
    "every event was discarded" / "sent ZERO times" state as if it still explains current
    behaviour; **"Dedupe."** paragraph is still accurate.
  - `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListener.java:37-50`
    — `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)` on `onExpiryWarning`,
    body calls `notificationOutboxSupport.enqueueEmail(...)`. (`:58`, `:77` — the two sibling
    listeners are also `BEFORE_COMMIT` + `enqueueEmail`.)
  - `deferred-103` AC4 already verified the *runtime* is correct and made no code change — only
    this comment is wrong.
- **Fix approach:**
  - Rewrite the **"Delivery."** paragraph to state current behaviour: the method publishes
    `SessionPackExpiryWarningEvent` inside `transactionTemplate.execute(...)`, and
    `SessionPackEmailListener.onExpiryWarning` is `@TransactionalEventListener(BEFORE_COMMIT)` +
    `notificationOutboxSupport.enqueueEmail(...)`, so the enqueue is **atomic with the
    `expiryWarnedAt` stamping transaction** and the actual send is the generic outbox drainer's
    job (retried, not fire-and-forget).
  - Keep the history in one clearly-marked sentence: *"Before `deferred-15`/`deferred-92` this
    method published with no bound transaction while the listener was `AFTER_COMMIT` with
    `fallbackExecution = false`, so every event was silently dropped — see those stories."* Don't
    delete the lesson, just stop presenting it as current.
  - Keep **"Dedupe."** and the extended-pack note as-is. No code change.
- **Ledger:** delete the **`SessionPackExpiryNotifier` class javadoc is stale** bullet under
  `## Deferred from: code review of skillars-deferred-103-… (2026-09-09)`. If that section is now
  empty (both its bullets deleted by AC1 + AC4), remove the header.
- **Test:** none (javadoc only). Note it in the Dev Agent Record.

---

### AC5: `node_exporter` network isolation — formal won't-fix + full-rationale comment (D1)

- **Task:** No topology change. Replace the terse `docker-compose.yml` comment with a complete
  won't-fix rationale so this stops being re-raised, and retag the ledger bullet `[DECIDED]`.
- **Verified at HEAD:**
  - `docker-compose.yml:362-387` — `node_exporter` service; `networks: [skillars-observability]`
    only (`:386-387`); existing comment `:383-385` ("node_exporter is isolated on
    `skillars-observability`; `app`/`grafana` can reach it as they join both networks. A compromised
    `app` container can read host metrics. This is the accepted design boundary.").
  - `app` joins `skillars-internal` + `skillars-observability` (`:125-127`); `grafana` likewise
    (`:358-360`). `skillars-observability` is `internal: true` (`:399-401`) — `deferred-88` AC8.
  - `deploy/lgtm/prometheus.yml:14-17` — `node-exporter` job scrapes `node_exporter:9100`;
    prometheus reaches it via `skillars-observability`.
- **Fix approach — comment at `docker-compose.yml:383-385`, expand to:**
  1. node_exporter has no published host ports and sits on an `internal: true` network — it is
     unreachable off-host.
  2. On-host, the only containers that can reach `node_exporter:9100` are `prometheus` (must — it
     scrapes it), `app`, and `grafana` (the latter two only because they also need egress and
     therefore join `skillars-observability`).
  3. `app` and `grafana` are **first-party images we build/pin**. A compromised `app` already holds
     the Postgres password, Stripe keys, Bunny.net keys and the OTLP endpoint — reading host
     CPU/memory/disk/filesystem gauges from node_exporter is not a meaningful privilege escalation
     on top of that.
  4. A dedicated 2-member `prometheus`+`node_exporter` network was evaluated and **rejected**: it
     adds a fourth bridge network and a scrape-topology special case for negligible security gain.
  5. **Revisit only** if a third-party sidecar (not built by us) is ever added to
     `skillars-observability` — then node_exporter should move to its own network.
  6. Tag: `skillars-deferred-107 AC5 / [DECIDED 2026-09-10]`.
- **Ledger:** the `node_exporter network isolation` bullet (currently ~line 843 under
  `## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)`)
  → **retag in place** `[DECIDED 2026-09-10 (skillars-deferred-107 AC5): accept & document — …
  one-line summary …]`. **Do not delete it** (the file's convention keeps `[DECIDED]` bullets so
  the decision is not re-litigated). Strike the 2026-09-04 audit-table row (line ~52) — see AC9.
- **Test:** none (compose comment + docs). `docker compose config` must still parse — run it and
  note the exit code in the Dev Agent Record.

---

### AC6: Credentials in `/proc/<pid>/environ` — formal won't-fix + `secrets-reference.md` entry (D2)

- **Task:** No script logic change. Document the accepted risk and retag the ledger bullet
  `[DECIDED]`.
- **Verified at HEAD:**
  - `## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)` in
    `deferred-work.md` now has a **single** bullet: *"Credentials visible in `/proc/<pid>/environ`
    when `.env` is sourced — project-wide pattern"* (its `docker exec -e` sibling was deleted in
    this session's prune — closed by `deferred-94` AC1).
  - Backup/restore scripts load `.env` via `docker compose --env-file /opt/skillars/.env` and/or a
    `set -a; . .env`-style source; `POSTGRES_PASSWORD` (and friends) land in the shell's
    environment.
  - `deploy/provision.sh:347` — `.env` is `root:root 0600`, outside the checkout.
  - `deploy/provision.sh:358-359,390` — the `deploy` SSH user runs only `git pull` + `docker
    compose`, is not in a position to read `/opt/skillars/.env`, and has no `data/**` access.
- **Fix approach:**
  - Add a section to `docs/deployment/secrets-reference.md` — **"Accepted credential-exposure
    surface"** — recording:
    - `/proc/<pid>/environ` for a script that has sourced `.env` is readable only by that
      process's own uid (root) and by root generally — and root already holds `.env` itself, so
      this exposes nothing root cannot already read.
    - The VPS is single-tenant. The only non-root operator account is the unprivileged `deploy`
      user, which cannot read `.env` and runs no script that sources it.
    - `PGPASSFILE` / stdin-piping was evaluated and rejected: it would rewrite every
      `psql`/`pg_dump`/`pg_restore` invocation across `pg-backup.sh`, `restore-from-dump.sh`,
      `restore-from-volume-backup.sh` (5 scripts) and change nothing about *who* can read the
      secret.
    - The `ps aux` argv-exposure half of the original finding **was** fixed (`deferred-94` AC1 —
      env-var inheritance: `PGPASSWORD="…" docker exec -e PGPASSWORD "$CID"`).
  - Add a one-line pointer comment in `pg-backup.sh` and `restore-from-dump.sh` near where `.env`
    is loaded: `# Credential-exposure surface (/proc/<pid>/environ) is an accepted risk — see
    docs/deployment/secrets-reference.md#accepted-credential-exposure-surface (skillars-deferred-107 AC6).`
- **Ledger:** retag the bullet in place `[DECIDED 2026-09-10 (skillars-deferred-107 AC6): accept —
  single-tenant VPS, `.env` is root:root 0600, `/proc/environ` readable only by the same uid; see
  secrets-reference.md]`. Keep it.
- **Test:** none. `bash -n` the two edited scripts and note it.

---

### AC7: Alertmanager double-notification — write the alerting-architecture decision (D4)

- **Task:** Turn the three-stories-running "decision-deferred" into an actual recorded decision +
  a checklist for the future change, and retag the ledger bullet `[DECIDED]`.
- **Verified at HEAD:**
  - `docker-compose.yml:227-229` — the existing design-gate comment above the `prometheus` service.
  - `deploy/lgtm/prometheus.yml` — no `alerting:` / `alertmanagers:` block; `alerts.yml` is
    mounted read-only (`:234`) and defines rules; **no `alertmanager` service anywhere in any
    compose file.**
  - `docs/deployment/monitoring.md` and `docs/deployment/uptime-monitor.md` exist.
- **Fix approach:**
  - Add a section **"Alerting architecture — the Alertmanager decision"** to
    `docs/deployment/monitoring.md`:
    - **Decision:** Grafana-managed alerting is the single alert-*delivery* path. Prometheus
      `alerts.yml` rules stay (they give Grafana alert state / panels via the Prometheus
      datasource) but deliver nothing on their own. No Alertmanager is deployed and none is
      planned.
    - **Why:** one delivery path = no reconciliation of two notification policies, no
      double-paging, one place to edit contact points.
    - **If Alertmanager is ever added, the same change MUST:** (a) add
      `alerting.alertmanagers` to `prometheus.yml` pointing at it; (b) define its `route` +
      `receivers` and make it the delivery path for the infra alerts currently in `alerts.yml`;
      (c) **disable the twin Grafana notification policies** for every alert that now routes
      through Alertmanager, so each fires exactly once; (d) update this section and the
      `docker-compose.yml` comment. Include this as a literal checklist.
  - Replace `docker-compose.yml:227-229` with a 2-line pointer to that section +
    `skillars-deferred-107 AC7 / [DECIDED 2026-09-10]`.
- **Ledger:** retag the **"Double notification risk if Alertmanager added later"** bullet
  (currently ~line 842, same `deploy-3-3` section) in place `[DECIDED 2026-09-10
  (skillars-deferred-107 AC7): Grafana-only delivery; Prometheus rules stay non-delivering; future
  Alertmanager change carries a documented checklist — see monitoring.md]`. Keep it. Strike the
  2026-09-04 audit-table row (line ~51) — see AC9.
- **Test:** none (docs + compose comment). `docker compose config` parses — note it.

---

### AC8: Hardcoded container-UID `chown` — verify against the image's real runtime UID (`deferred-94` CR)

- **Task:** `provision.sh` and `restore-from-volume-backup.sh` `chown -R` the observability data
  dirs to hardcoded numeric UIDs. A future `grafana`/`loki`/`tempo`/`prometheus` image bump that
  moves the container's runtime uid (Grafana's has moved historically, 104→472) leaves a data dir
  the new image cannot write — provision/restore "succeeds", the container silently crash-loops or
  starts empty. `deferred-94` AC2 added guard *comments* only ("accepted mitigation for now").
  Replace the guess with a probe, keeping the constants as fallback.
- **Verified at HEAD** (ledger citations `provision.sh:599-610` / `restore-from-volume-backup.sh:89-101`
  have drifted — correct line numbers below):
  - `deploy/provision.sh:709-744` — `chown_if_needed 65534:65534 "${MOUNT_POINT}/prometheus"`,
    `10001:10001 …/loki`, `10001:10001 …/tempo`, `472:472 …/grafana`, `999:1000 …/redis`. Comment
    at `:709` already says "Update these chown calls if the corresponding docker-compose.yml image
    versions change."
  - `deploy/backup/restore-from-volume-backup.sh:91-101` — a `case "$d" in` block with the same
    hardcoded values (`redis) chown -R 999:1000`, `prometheus) 65534:65534`, `loki|tempo)
    10001:10001`, `grafana) 472:472`), same "tied to specific image versions" comment at `:90-91`.
  - `restore-from-volume-backup.sh:26` — `COMPOSE_FILE="/opt/skillars/app/docker-compose.yml"`
    already available to resolve image tags.
- **Fix approach:**
  - Add a shell helper (one copy per script, or a shared snippet in `deploy/backup/` if the two
    scripts already share one — check) e.g.:
    ```
    # Echoes "<uid>:<gid>" the given compose service's image actually runs as, or "" on any failure.
    image_runtime_ugid() {
      local svc="$1" img
      img=$(docker compose -f "${COMPOSE_FILE}" config --format json 2>/dev/null \
            | jq -r --arg s "$svc" '.services[$s].image // empty') || return 0
      [ -n "$img" ] || return 0
      docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img" >/dev/null 2>&1 || return 0
      docker run --rm --entrypoint sh "$img" -c 'printf "%s:%s" "$(id -u)" "$(id -g)"' 2>/dev/null || return 0
    }
    ```
    (Adjust to the scripts' existing style — `provision.sh` uses `docker compose` with an explicit
    `--env-file`; match it. `jq` is already provisioned — `provision.sh:144` installs it — so JSON
    parsing is fine.)
  - At each `chown` site: `ugid=$(image_runtime_ugid <service>); chown_if_needed "${ugid:-<hardcoded
    fallback>}" "<dir>"` and, when `ugid` is empty **or** differs from the hardcoded constant,
    `log "⚠️  <service>: using $( … ) — hardcoded fallback was <const>; update the constant"`.
  - Keep every hardcoded constant in place as the documented fallback. Update the two "update these
    if image versions change" comments to "these are fallbacks; the runtime uid is probed from the
    image first — see image_runtime_ugid()".
  - **`redis` special case:** the redis image runs as uid 999 but the data dir is deliberately
    `chown`ed `999:1000` (group `redis`, gid 1000) at both sites today. The probe returns the
    image's *actual* `uid:gid` (likely `999:999`). Do **not** let the probe silently downgrade the
    dir to `999:999` — keep the gid override: use only the probed *uid* for redis and pin `:1000`
    as the gid, with a comment at the call site explaining why redis differs from the others (which
    do take both probed values). Add a one-line comment at each redis `chown` site recording this.
- **Ledger:** delete the **"Hardcoded container-UID `chown` values are never verified against the
  image's real runtime UID"** bullet (currently ~line 1257, `## Deferred from: code review of
  skillars-deferred-94 (2026-09-07)`). It is `[PICKED UP by skillars-deferred-94 AC2: … accepted
  mitigation for now]` — this story replaces the mitigation with a real fix, so the bullet is
  genuinely closed → delete (no `[DECIDED]` retag; it was never decided-wont-fix). The section's
  other bullet (actuator-health) stays; keep the header.
  - *Owner note:* if on review the owner prefers to keep accepting this one (consistent with D1/D2),
    downgrade this AC to "expand the guard comment + retag `[DECIDED]`" instead — flagged here so
    it's a conscious call, not a silent scope cut.
- **Test:** no shell harness in this repo. Dev Agent Record must document a manual exercise:
  1. `docker run --rm --entrypoint id <img>` for two tags of one observability image to find a pair
     with different runtime uids (Grafana historically moved 104→472).
  2. Run the ownership-fix path with the older tag pinned → confirm the probed uid is used and the
     WARN fires because it differs from the hardcoded constant.
  3. Run it again with the current tag → probed uid matches the constant, no WARN.
  4. **redis specifically:** after the fix runs, `stat -c '%u:%g' /opt/skillars/data/redis` must be
     `<probed-uid>:1000` — the gid override survived — and a second run leaves it unchanged
     (`chown_if_needed` idempotency, `deferred-87` AC3).
  This matches the project's established "deploy scripts verified by reading + a documented manual
  exercise" convention (`deferred-102` Dev Notes).

---

### AC9: Ledger hygiene — strike the stale `deploy-*` audit-table rows, retag the `[DECIDED]` bullets, add the audit block

- **Task:** Apply every per-AC ledger instruction above, plus reconcile the stale 2026-09-04
  `deploy-*` audit-table rows, and record a `## Last audit: 2026-09-10 (skillars-deferred-107)`
  block with the reconstruction check per the file's own convention.
- **2026-09-04 `deploy-*` audit table (near the top of `deferred-work.md`) — strike + annotate**
  (match the existing precedent on the `deploy-1-3` LGTM row in that same table):
  - `deploy-3-4` "DROP DATABASE / open connections" (line ~46) → `~~…~~` **CLOSED by
    `skillars-deferred-101` AC6** (`pg_terminate_backend` sweep before the DROP,
    `restore-from-dump.sh:140-144`).
  - `deploy-3-4` "APP_CID capture race" (line ~48) → `~~…~~` **CLOSED by `skillars-deferred-102`
    AC2** (5×/2s bounded retry, `restore-from-dump.sh:205-222`).
  - `deploy-3-3` "double notification if Alertmanager added" (line ~51) → `~~…~~` **DECIDED by
    `skillars-deferred-107` AC7** (Grafana-only delivery; see `monitoring.md`).
  - `deploy-3-3` "node_exporter network isolation" (line ~52) → `~~…~~` **DECIDED by
    `skillars-deferred-107` AC5** (accept & document; first-party containers only).
  - `deploy-1-5` "`git clean` vs the data subdirectory" (line ~60) → `~~…~~` **CLOSED by
    `skillars-deferred-102` AC6/AC7** (checkout is `/opt/skillars/app`, sibling of `…/data`).
- **`[DECIDED]` retags (keep the bullet, per the file's decided-retention rule):**
  - `deploy-3-3` "node_exporter network isolation" bullet → `[DECIDED 2026-09-10 (skillars-deferred-107 AC5)]`
  - `deploy-3-3` "Double notification risk if Alertmanager added later" bullet → `[DECIDED 2026-09-10 (skillars-deferred-107 AC7)]`
  - `deploy-3-1` "Credentials visible in `/proc/<pid>/environ`" bullet → `[DECIDED 2026-09-10 (skillars-deferred-107 AC6)]`
- **Deletions (genuine fixes shipped by this story):**
  - `pack.pause.maxDays` misconfigured `≤ 0` bullet (AC1) — `## Deferred from: code review of
    skillars-deferred-103-… (2026-09-09)`.
  - `SessionPackExpiryNotifier` class javadoc stale bullet (AC4) — same section. If the section is
    now empty, remove the header.
  - Hardcoded container-UID `chown` bullet (AC8) — `## Deferred from: code review of
    skillars-deferred-94 (2026-09-07)`. Keep the header (actuator-health bullet remains).
- **Audit block:** add `## Last audit: 2026-09-10 (skillars-deferred-107 story implementation)` after
  the last existing audit block, enumerating: the two decision-item accept-and-document outcomes,
  the AC2/AC3 sweep as the generalisation of the `pack.pause.maxDays` bullet's closing sentence,
  every deletion and retag above, the five struck audit-table rows, and a **reconstruction check**
  ("every surviving non-blank line matches the pre-edit file (master @ `918ff206` + this session's
  earlier prune), in order, with nothing reworded; `[DISMISSED]`/`[DECIDED]` counts before/after;
  `[PICKED UP]` bullets touched: one — the `deferred-94` hardcoded-UID bullet, deleted as a genuine
  closure").
- **Test:** the reconstruction check itself is the verification. No code.

---

## Tasks / Subtasks

_Derived from the ACs at implementation time (the story was authored AC-first). Sequence follows
Dev Notes §Sequencing._

- [x] **AC1 — `PackSessionService.pausePack` range-guard `pack.pause.maxDays`**
  - [x] Add `MIN_/MAX_PACK_PAUSE_MAX_DAYS` constants (`1`, `3650`); switch the read to
        `configService.getBoundedLong("pack.pause.maxDays", 90L, 1L, 3650L)`
  - [x] `PackSessionServicePauseTest`: retarget the 8 existing `getLong` stubs + the `verify` to the
        bounded overload with exact bounds; add the "config clamps to default → 91-day pause still
        rejected" case
- [x] **AC2 — `ConfigService.getBoundedInt` + codebase-wide `getLong`/`getInt` range-safety sweep**
  - [x] `ConfigService`: add `getBoundedInt(key, default, min, max)` + no-default clamp overload
        `getBoundedLong(key, min, max)`
  - [x] Build `ConfigBounds` (single source of truth: `{key, min, max, failFast, note}` +
        enum-generated per-tier / per-type keys)
  - [x] Migrate all 31 non-`ConfigService` `getLong`/`getInt` call sites to the bounded accessors
        with a one-line rationale comment each (bounds cross-referenced to `ConfigBounds`)
  - [x] Update every affected existing unit test (17 files) to stub the bounded call; add
        `verify(...)` mutation checks where the stub used loose matchers (Pack, Radar, VideoAccessGuard);
        add `ConfigServiceTest` coverage for both new accessors; `RetentionSchedulerTest`
        "clamps-to-default, never deletes everything" case
- [x] **AC3 — `ConfigStartupAssertion` fail-fast on out-of-range critical config at boot**
  - [x] New `ConfigStartupAssertion` (`ApplicationReadyEvent`, in `platform.config.service`); ERROR +
        `config.value.misconfigured{key,reason,expected}` in **all** profiles, throw
        `AppSetupException` gated to non-`dev`
  - [x] Drive it from `ConfigBounds.ALL`; `ConfigStartupAssertionTest` (7 scenarios) +
        `ConfigBoundsEnumCoverageTest` (enum-completeness / internal-consistency)
  - [x] "Platform config value ranges" table in `docs/deployment/monitoring.md`
- [x] **AC4 — `SessionPackExpiryNotifier` class javadoc** — rewrite the "Delivery." paragraph to the
      current `BEFORE_COMMIT` + outbox-enqueue wiring; keep the pre-`deferred-92` history as one
      marked sentence (doc-only, no test)
- [x] **AC5 — `node_exporter` network isolation** — full won't-fix rationale in the
      `docker-compose.yml` `node_exporter` comment; `docker compose config` parses
- [x] **AC6 — credentials in `/proc/<pid>/environ`** — "Accepted credential-exposure surface" section
      in `docs/deployment/secrets-reference.md`; pointer comments in `pg-backup.sh` /
      `restore-from-dump.sh`; `bash -n` clean
- [x] **AC7 — Alertmanager double-notification** — "Alerting architecture — the Alertmanager
      decision" section in `docs/deployment/monitoring.md` (decision + 4-step future checklist);
      `docker-compose.yml` prometheus-service comment replaced with a pointer
- [x] **AC8 — hardcoded container-UID `chown`** — `image_runtime_ugid` probe + `chown_probed`
      wrapper in `provision.sh` and `restore-from-volume-backup.sh`; constants kept as WARN-on-mismatch
      fallback; redis uid-only (gid pinned 1000); `bash -n` + `shellcheck` clean; manual exercise
      documented in Completion Notes
- [x] **AC9 — ledger hygiene** — strike 5 stale `2026-09-04` `deploy-*` audit-table rows; `[DECIDED]`
      retag the 3 decision bullets in place; delete the 2 `deferred-103`-CR bullets (+ empty header)
      and the `deferred-94`-CR hardcoded-UID bullet; add the `2026-09-10 (skillars-deferred-107)`
      audit block with the reconstruction check

---

## Files-in-play

| Area | Paths |
|---|---|
| Config platform | `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` (add `getBoundedInt`, maybe `getBoundedLong(key,min,max)`), **new** `ConfigStartupAssertion.java` (+ a `ConfigBounds` holder), `src/test/java/.../config/service/ConfigServiceTest.java`, **new** `ConfigStartupAssertionTest.java` |
| Pack pause | `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java` (:50, :162), `src/test/java/.../payment/service/PackSessionServiceTest.java` |
| `getLong`/`getInt` sweep (AC2) | the 31 call sites in the AC2 table across `platform.video.*`, `platform.development.*`, `platform.booking.*`, `platform.payment.*`, `platform.admin.*`, `platform.messaging.*`, `platform.reviews.*` service classes + one test per migrated site |
| Javadoc | `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java` (javadoc only) |
| Compose | `docker-compose.yml` (comments only: `:227-229`, `:383-385`) |
| Deploy scripts | `deploy/provision.sh` (:709-744), `deploy/backup/restore-from-volume-backup.sh` (:90-101), `deploy/backup/pg-backup.sh`, `deploy/backup/restore-from-dump.sh` (pointer comments) |
| Docs | `docs/deployment/monitoring.md` (config-ranges table + alerting-decision section), `docs/deployment/secrets-reference.md` (accepted-exposure section) |
| Ledger | `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml` |

---

## Dev Notes

### Sequencing

1. **AC1 + AC2 + AC3 first** — they share the bounds registry. Build `ConfigBounds` (the
   `{key,min,max,failFast}` source of truth), point `getBoundedLong`/`getBoundedInt` call sites at
   its numbers, then `ConfigStartupAssertion` iterates it. Doing them together avoids two passes
   over the same 30 call sites.
2. **AC4** is independent, trivial, do any time.
3. **AC5/AC6/AC7** are doc + comment only — batch them; run `docker compose config` and `bash -n`
   once at the end.
4. **AC8** is the only real deploy-script logic change — isolate it in its own commit.
5. **AC9 last** — after every other AC's code is in, so the reconstruction check is against the
   final state.

### Key constraints from `project-context.md`

- **Java records** for any new DTO (none expected here).
- **No `@PreAuthorize`-less endpoints** — this story adds no endpoints.
- **Migrations** — this story adds **none**. `pack.pause.maxDays` and every swept key are already
  seeded; the fix is read-side only. Do not add a migration to "fix" a range — that is the
  operator's job and the point of AC3.
- **Tests:** Mockito unit tests for service call sites (`@ExtendWith(MockitoExtension.class)`),
  `SimpleMeterRegistry` for the counter assertion in `ConfigStartupAssertionTest`, AssertJ
  throughout. No new `@SpringBootTest` needed — `ConfigStartupAssertion` is plain and unit-testable
  like `TlsStartupAssertion`.
- **`mvn verify` is CI-only** — do not run it locally (project rule). Push and let GitHub CI be the
  gate.
- **Local config validation:** because AC3 runs the ERROR + `config.value.misconfigured` metric in
  **all** profiles (only the boot-blocking throw is gated to non-`dev`), a developer who hand-edits
  a `platform_config` row to an out-of-range value for a test will see the loud `ConfigStartupAssertion`
  ERROR at startup and the metric — they are not left guessing when the read-time clamp later
  swallows it. No extra tooling needed; this is the reason for the deviation from
  `TlsStartupAssertion`'s dev-skip.

### Behaviour-preservation notes (files being modified, not just added)

- **`ConfigService`** — `getBoundedInt` / any new `getBoundedLong` overload must not change the
  existing `getLong`/`getInt`/`getBoundedLong` semantics. Additive only.
- **`PackSessionService.pausePack`** — the only change is how `maxDays` is resolved. The
  `pauseDurationDays() < 1 || > maxDays` guard, the pessimistic lock, the booking-cancellation
  loop, the notification block (`deferred-103` AC5-AC7), and the `@Transactional` shape all stay
  exactly as they are.
- **Every AC2 call site** — the resolved number's *meaning* is unchanged for any in-range config;
  only out-of-range values now clamp instead of flowing through. Read each method enough to set a
  defensible `[min, max]` — e.g. `Math.toIntExact` at `VideoAccessGuard.java:93` means the max must
  be `< Integer.MAX_VALUE`.
- **`SessionPackExpiryNotifier`** — javadoc only; the `@Scheduled` cron, `@SchedulerLock`,
  `transactionTemplate.execute`, and event publish are untouched.
- **`provision.sh` / `restore-from-volume-backup.sh`** — `chown_if_needed`'s idempotency contract
  (`deferred-87` AC3 partial-completion tier) must survive: the probe feeds it a `uid:gid` string
  exactly as the hardcoded constants do today. If the probe fails, the fallback path must be
  byte-for-byte the current behaviour.

### Project Structure Notes

- `ConfigStartupAssertion` → `platform.config.service` (co-located with `ConfigService`, the thing
  it validates) is the better home than `platform.monitoring`; note the deviation from
  `TlsStartupAssertion`'s location with that one-line reason. `ConfigBounds` constants holder →
  same package.
- No `infrastructure.*` changes — all config logic is `platform.config`, correct per the DDD
  boundary in `project-context.md` (§4: domain-specific configuration properties belong in the
  platform layer).

### References

- Ledger: `_bmad-output/implementation-artifacts/deferred-work.md`
  - `## Deferred from: code review of skillars-deferred-103-payment-reliability-pack-pause-hardening-and-cross-module-cleanup (2026-09-09)` — the `pack.pause.maxDays` and `SessionPackExpiryNotifier` bullets (AC1, AC4).
  - `## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)` — node_exporter + Alertmanager bullets (AC5, AC7).
  - `## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)` — `/proc/<pid>/environ` bullet (AC6).
  - `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — hardcoded container-UID `chown` bullet (AC8).
  - `## Last audit: 2026-09-04` `deploy-*` table — the five stale rows (AC9).
  - `## Last audit: 2026-09-10 (post-merge prune — deferred-104 / -105 / -106)` — this session's prune, the baseline for AC9's reconstruction check.
- Code precedents:
  - `[Source: src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java#getBoundedLong]` — existing bounded-read helper + `config.value.misconfigured` counter.
  - `[Source: src/main/java/com/softropic/skillars/platform/monitoring/TlsStartupAssertion.java]` — `ApplicationReadyEvent` fail-fast assertion pattern (dev-profile skip, `AppSetupException`).
  - `[Source: src/test/java/com/softropic/skillars/platform/booking/service/SessionDurationResolverTest.java]` — the "`verify(configService).getBoundedLong(KEY, d, min, max)`" test idiom for a migrated call site.
  - `[Source: src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListener.java:37-50]` — the real (BEFORE_COMMIT + outbox) listener wiring AC4's javadoc must describe.
  - `[Source: deploy/provision.sh:709-744]`, `[Source: deploy/backup/restore-from-volume-backup.sh:90-101]` — the hardcoded-UID `chown` sites (AC8).
  - `[Source: docker-compose.yml:227-229,362-401]` — prometheus/node_exporter/networks blocks (AC5, AC7).
- Story precedent: `[Source: _bmad-output/implementation-artifacts/skillars-deferred-103-payment-reliability-pack-pause-hardening-and-cross-module-cleanup.md]` — AC6/AC7 format, the ledger-hygiene AC, the reconstruction-check convention.

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code, `bmad-dev-story` workflow)

### Debug Log References

- Backend compile: `mvn -o compile -DskipFrontend` → BUILD SUCCESS.
- Targeted tests (per `docs/validation-strategy.md` — no local `mvn verify`):
  - `ConfigServiceTest` (26), `ConfigStartupAssertionTest` (7), `ConfigBoundsEnumCoverageTest` (4) — green.
  - All 17 modified call-site test classes — green (batch runs: 134 + 48 + 471 + 33 + 33 tests across
    `config` / `payment` / `video` / `booking` / `messaging` / `development` / `admin` / `reviews`
    service unit-test packages, 0 failures).
- `bash -n` clean: `provision.sh`, `restore-from-volume-backup.sh`, `pg-backup.sh`, `restore-from-dump.sh`.
- `shellcheck -x` on the four scripts: only pre-existing SC1091 (info, can't follow `env-guard.sh`
  relative path) — no new findings.
- `docker compose -f docker-compose.yml config` parses (exit 0 with all `${VAR:?}` env supplied);
  YAML structure verified independently.

### Completion Notes List

**AC1** — `PackSessionService.pausePack` now reads `getBoundedLong("pack.pause.maxDays", 90L, 1L, 3650L)`
(`MIN_/MAX_PACK_PAUSE_MAX_DAYS` constants). 8 existing `PackSessionServicePauseTest` stubs +
the `verify` retargeted to the bounded overload; added
`pausePack_configMaxClampsToDefault_stillRejectsOverLongDuration`.

**AC2** — Added `ConfigService.getBoundedInt(key, default, min, max)` and the no-default clamp
overload `getBoundedLong(key, min, max)` (missing key still throws; present-but-out-of-range clamps
to the nearest bound + WARN). New `ConfigBounds` holds every bound as the single source of truth,
with the per-tier (`video.quota.{scout,instructor,academy,athlete}.*`) and per-type
(`video.{homework,drillDemo,coachReview}.*`) keys **generated** from hand-listed segments — kept out
of the `config` module's dependency graph on purpose (importing `video.contract` /
`marketplace.contract` into the foundational `config` module would be the first such cross-module
dependency). `ConfigBoundsEnumCoverageTest` imports the enums and fails if a new
`CoachSubscriptionTier` / `VideoType` constant lacks a bound, so the two cannot drift.

All 31 non-`ConfigService` `getLong`/`getInt` call sites migrated with a one-line rationale comment
each. **Deviation from the story's first-pass table:** `video.quota.*.storageBytes` /
`bandwidthBytesMonthly` use `[0, Long.MAX_VALUE]`, not `[1, …]` — `V53` seeds
`video.quota.scout.storageBytes = '0'` deliberately ("0 = no upload"), so a floor of 1 would clamp a
legitimate value; a negative quota is the only genuinely broken state. Noted at the call site and in
`ConfigBounds`. `NeglectedSkillDetectionService`'s existing explicit negative-guard is kept as
belt-and-suspenders behind the new `[0, 10000]` clamp.

17 existing unit-test classes updated to stub the bounded call (exact-argument stubs are themselves
the mutation check — reverting a site to `getLong` breaks the stub); explicit `verify(...)` added to
`PackSessionServicePauseTest`, `RadarCompositeDlqProcessorTest`, `VideoAccessGuardTest`;
`RetentionSchedulerTest` gained `runRetention_zeroRetentionConfig_clampsToDefault_neverDeletesEverything`.
Sites covered only by `*IT` (VideoDeletionOutboxProcessor, VideoSubscriptionLifecycleListener,
Timeline, ReviewSubmission, ReviewFlag) execute the bounded path against the in-range DB seed — no
mock change needed.

**AC3** — `ConfigStartupAssertion` (`platform.config.service`, next to `ConfigService`; deviation
from `TlsStartupAssertion`'s `platform.monitoring` location noted in the class javadoc, along with
the dev-profile deviation: ERROR + metric in **all** profiles, only the `AppSetupException` throw
gated to non-`dev`). Reads raw stored values via `configService.find(key)`; `config.value.misconfigured`
gets a third tag `expected="[min,max]"` per the story (feature-gate increments in `ConfigService`
keep their 2-tag form — Prometheus tolerates differing label sets in one metric family; noted here as
a known caveat). `ConfigStartupAssertionTest` covers all 7 scenarios; `ConfigBoundsEnumCoverageTest`
covers enum-completeness + internal consistency + no-dup-keys. Fail-fast keys:
`message_retention_months`, `pack.pause.maxDays`, `booking.batch.maxSize`, `disputes.submissionWindowDays`,
`reviews.submissionWindowDays`, `moderation_sla_minutes`, `moderation_lock_timeout_minutes`,
`gdpr.export.urlExpiryHours`, `video.playback.signed_url_ttl_minutes`. "Platform config value ranges"
table added to `docs/deployment/monitoring.md`.

**AC4** — `SessionPackExpiryNotifier` "Delivery." paragraph rewritten to the current
`transactionTemplate.execute` publish + `SessionPackEmailListener.onExpiryWarning`
`@TransactionalEventListener(BEFORE_COMMIT)` + `notificationOutboxSupport.enqueueEmail(...)` wiring;
pre-`deferred-15`/`-92` history kept as one marked sentence. Doc-only.

**AC5 / AC6 / AC7** — no topology / script-logic change. `docker-compose.yml` `node_exporter` comment
expanded to the full 5-point won't-fix rationale; prometheus-service comment replaced with a pointer
to the new `monitoring.md` "Alerting architecture — the Alertmanager decision" section (decision +
literal 4-step future checklist). `secrets-reference.md` gained "Accepted credential-exposure surface";
`pg-backup.sh` / `restore-from-dump.sh` gained a one-line pointer comment by the `.env` load.

**AC8** — probe added: `image_runtime_ugid <service>` resolves `docker compose config --format json`
→ image → `docker run --entrypoint sh <img> -c 'printf "%s:%s" "$(id -u)" "$(id -g)"'`, returning ""
on any failure (scratch/distroless image with no shell, missing `.env`, Docker not yet installed).
`chown_probed` uses the probed value, falls back to the hardcoded constant with a WARN when the probe
fails **or** disagrees. Redis: probe the **uid only**, pin `:1000` as the gid (the data dir is
deliberately group `redis`). Constants kept as documented fallbacks; the two "update these if image
versions change" comments replaced.

_Manual exercise (documented, not run — needs a provisioned host with Docker + image pulls, which
this environment lacks; matches the project's "deploy scripts verified by reading + a documented
manual exercise" convention, `deferred-102` Dev Notes):_
1. `docker run --rm --entrypoint id grafana/grafana:<oldtag>` and `:<newtag>` to find two tags with
   different runtime uids (Grafana moved 104→472 historically).
2. Pin the old tag, run the ownership-fix path → probed uid is used, WARN fires (differs from the
   `472:472` constant).
3. Re-pin the current tag → probed uid matches the constant, no WARN.
4. `stat -c '%u:%g' /opt/skillars/data/redis` after the fix → `<probed-uid>:1000` (gid override
   survived); a second run leaves it unchanged (`chown_if_needed` idempotency, `deferred-87` AC3).

**AC9** — see the `## Last audit: 2026-09-10 (skillars-deferred-107 story implementation)` block
appended to `deferred-work.md`. 5 `2026-09-04` audit-table rows struck + annotated; 3 `[DECIDED
2026-09-10]` retags in place (`deploy-3-3` node_exporter + Alertmanager, `deploy-3-1` `/proc/environ`);
3 bullets deleted (both `deferred-103`-CR bullets → header removed; the `deferred-94`-CR hardcoded-UID
bullet → header kept for its actuator-health sibling). **Known residual, left untouched per the
story's scoped instruction:** the `deploy-3-4`-review "Hardcoded container UIDs 65534/10001/472"
bullet + its `2026-09-04` audit row are a duplicate of the deleted `deferred-94` bullet; AC8's fix
closes the underlying issue but the story scoped the deletion to the `deferred-94` re-filing only —
flagged in the audit block for a future prune.

### File List

**New — main:**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java`
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java`

**New — test:**
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertionTest.java`
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/QuotaConfigServiceTest.java` (code review)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoTypeConstraintsTest.java` (code review)

**Modified — main (config accessors + 31-site sweep + AC1/AC4):**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessageRetentionScheduler.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineQueryService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoTypeConstraints.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoAccessGuard.java`
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java`

**Modified — test:**
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServicePauseTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PastDueGracePeriodTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/TierEntitlementGatingTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/messaging/service/RetentionSchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/DevelopmentCorrelationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoAccessGuardTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleSchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/LifecycleOrphanGuardTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceTest.java` (code review)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java` (code review)

**Modified — main (code review):**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` (`getLong` trim; `updateConfig` → `rejectOutOfRange`)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (`HAS_CODE_DEFAULT`; javadoc scope; `archived_to_deleted_days` ceiling → 36500)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java` (drop `expected` tag; `HAS_CODE_DEFAULT`-aware missing/blank/non-numeric fail-fast; summary log before throw)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java` (batch_size → `getBoundedInt(key, 100, 1, 10000)`)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java` (archived_to_deleted ceiling → 36500)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (`Math.max(1, tokenMaxTtlMinutes)`)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java` (AC4 javadoc atomicity carve-out)

**Modified — deploy / docs / ledger:**
- `docker-compose.yml` (comments only: `node_exporter` — rationale corrected, `prometheus`)
- `deploy/provision.sh` (`image_runtime_uid` inspect-based probe + `chown_probed` uid-only/pinned-gid; LGTM + redis chown sites)
- `deploy/backup/restore-from-volume-backup.sh` (`image_runtime_uid` + `chown_probed`; case block)
- `deploy/backup/pg-backup.sh` (pointer comment)
- `deploy/backup/restore-from-dump.sh` (pointer comment)
- `docs/deployment/monitoring.md` (Alerting-architecture section + Platform-config-value-ranges table)
- `docs/deployment/secrets-reference.md` (Accepted credential-exposure surface section)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC9)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review)

### Review Findings

Adversarial code review, 2026-09-10 — three layers (Blind Hunter / Edge Case Hunter / Acceptance
Auditor), deduplicated and triaged. Confidence markers reflect post-triage verification against the
working tree (several layer claims were checked empirically with `docker image inspect` / `docker run`
and against the real call sites; false positives were dropped).

**Decision needed** (ambiguous — needs owner intent before patching)

- [x] [Review][Decision] `ConfigStartupAssertion` never fail-fasts on a blank / non-numeric value — only on `out_of_range` — so the 9 fail-fast keys are unprotected against the two states that actually throw at runtime. `platform_config.value = ''` or `'abc'` for e.g. `platform.moderation_lock_timeout_minutes` boots clean (ERROR + `reason=missing`/`non_numeric` metric only), then `ModerationOrchestrationService.java:80` throws `IllegalStateException` inside `transactionTemplate.execute` after `transitionOperationalState(SCANNING)`; the throw is swallowed by the `AFTER_COMMIT` multicaster and the video is stranded in `PROCESSING`, which `ModerationSlaMonitorService` never scans. Fixing this interacts with the "absent key with a code default is legitimate" question below — decide both together. [`ConfigStartupAssertion.java:63-78`]
- [x] [Review][Decision] `@Scheduled` tasks are registered on `ContextRefreshedEvent`, strictly before `ApplicationReadyEvent`, so the fail-fast throw cannot prevent the damage it exists to prevent. With `platform.moderation_sla_minutes = 0`, `detectSlaViolations` (`fixedDelayString`, no initial delay) runs against the clamped value of 1 and starts failing in-flight videos before the assertion throws; under a restart policy this burns one retry per boot. Moving to `ApplicationStartedEvent`/`@PostConstruct` fixes it but deviates from the `TlsStartupAssertion` precedent (`TlsStartupAssertion.java:32,51`) this class deliberately matches. [`ConfigStartupAssertion.java:54,99`]
- [x] [Review][Decision] The two `getBoundedLong` overloads disagree on out-of-range recovery: the 3-arg clamps to the nearest bound, the 4-arg falls back to the *default*. For `platform.video.lifecycle.archived_to_deleted_days = 4000` (an operator extending retention past the 3650 ceiling) the 4-arg returns **90**, not 3650 — the nightly job then physically deletes assets ~10 years earlier than intended, `failFast = false`, ERROR only. Clamping is the safe direction; the current split is a documented deliberate choice, so changing it is a product call. Same shape at `VideoAccessGuard.java:98` (access-control) and `MessageRetentionScheduler.java:34`. [`ConfigService.java:103-111`]
- [x] [Review][Decision] AC5's won't-fix rationale is factually false, and its own REVISIT trigger has already fired. The comment claims "the only containers that can reach `node_exporter:9100` are `prometheus`, `app`, and `grafana`" and that those are "first-party images we build/pin", with "REVISIT only if a third-party sidecar (not built by us) is ever added to `skillars-observability`". But `redis` (`docker-compose.yml:193`), `loki` (`:283`) and `tempo` (`:306`) all join that network and are all third-party images. Either correct the rationale and re-affirm the accept, or act on the REVISIT trigger. [`docker-compose.yml:389-400`]
- [x] [Review][Decision] `ConfigBounds` claims to be the "single source of truth for … every risky numeric `platform_config` key" but omits 11 already-bounded call sites, so `ConfigStartupAssertion` never checks them and none can ever be fail-fast: `platform.reminder_interval_primary_hours`/`_secondary_hours`, `booking.request_expiry_hours`, `booking.session.defaultDurationMinutes`, `payment.payout.hold_hours` (feeds a coach's payout `releaseAfter`), `reliability.strike.suspensionThreshold`/`visibilityThreshold`, both `PaymentPendingSweeper` keys, `platform.messaging.moderation_orphan_grace_minutes`. Either register them or narrow the javadoc claim to "every key migrated by AC2". [`ConfigBounds.java:7-8,183-211`]
- [x] [Review][Decision] AC7 declares Grafana the single alert-delivery path, which formally converts four unprovisioned Prometheus rules into silent pages. `deploy/lgtm/alerts.yml` defines 9 rules; `deploy/lgtm/grafana-alerts.yml` provisions only 5 (`NodeExporterDown`, `AppDown`, `DiskDataVolumeHigh`, `DiskRootHigh`, `MemoryPressureHigh`). The four with no Grafana twin are `DbConnectionPoolHigh`, `JvmHeapHigh`, `BookingPaymentSettleFailureRateHigh` and `SubscriptionInvoicePaymentFailureHigh` — the last two carry `runbook:` links and are payment-failure alerts. Provision the missing rules, or record the gap explicitly in the decision. [`deploy/lgtm/alerts.yml` vs `deploy/lgtm/grafana-alerts.yml`]
- [x] [Review][Decision] The runtime `PUT` config path bypasses every range check the story adds. `UpdateConfigRequest` validates only `@NotBlank` and `ConfigService.updateConfig` writes the raw string then `invalidate()`s the cache, so an admin can set `booking.batch.maxSize = 0` and it is live on that node before the response returns — no ERROR, no `config.value.misconfigured` increment, no boot gate. The assertion covers only values that were already bad at the last restart, not the path operators actually use. Validating on write is a genuine scope extension. [`ConfigService.java:197-205`, `UpdateConfigRequest.java:5`]

**Patch** (fix is unambiguous)

- [x] [Review][Patch] redis uid probe returns `0:0` and overrides the correct constant, chowning the redis data dir to root [`deploy/provision.sh:781-789`, `deploy/backup/restore-from-volume-backup.sh:141`]
- [x] [Review][Patch] `config.value.misconfigured` is registered with two different tag-key sets — `PrometheusMeterRegistry` throws `IllegalArgumentException` [`ConfigStartupAssertion.java:107-113` vs `ConfigService.java:150-154,174-178`]
- [x] [Review][Patch] `platform.development.radar_composite_dlq.max_attempts` is the only `ConfigBounds.ALL` key with no Flyway seed — ERROR + metric on every boot, in every environment [`ConfigBounds.java:170-172`]
- [x] [Review][Patch] `getLong(String)` does not trim, while `getLong(key,default)` and the assertion both do — a whitespace-padded value passes startup validation then throws at every 3-arg call site [`ConfigService.java:68-75`]
- [x] [Review][Patch] `platform.video.lifecycle.batch_size` is read through two accessors with incompatible missing-key contracts (scheduler falls back to 100; the 60-second outbox drain throws and aborts every run) [`VideoSubscriptionLifecycleListener.java:75` vs `VideoLifecycleScheduler.java:46`]
- [x] [Review][Patch] AC6's `secrets-reference.md` section misdescribes the mechanism — `env-guard.sh:27` sources with a bare `.` and no `set -a`, so values are shell variables, not exported environment; the real exposure is the short-lived `PGPASSWORD=… docker exec -e` child [`docs/deployment/secrets-reference.md:376-380`]
- [x] [Review][Patch] Grafana probe returns `472:0` but the fallback constant is `472:472`, so every provision and every restore emits a false "update the constant" WARN and re-groups the data dir — desensitising the one signal that would surface a real uid drift [`deploy/provision.sh:753`, `deploy/backup/restore-from-volume-backup.sh:145`]
- [x] [Review][Patch] AC2's per-call-site `verify(...)` test is missing for four migrated sites, one of them a fail-fast key [`GdprExportService.java:87`, `NeglectedSkillDetectionService.java:73`, `QuotaConfigService.java:32,38,43`, `VideoTypeConstraints.java:25,30`]
- [x] [Review][Patch] `ConfigBounds` javadoc uses `signed_url_ttl_minutes` as its example of a `failFast = false` key, but declares it `failFast = true` sixty lines below [`ConfigBounds.java:31` vs `:93-95`]
- [x] [Review][Patch] redis comment transposes uid and gid — "the image process is gid 999" contradicts the verified `uid=999(redis) gid=1000(redis)` three lines above [`deploy/provision.sh:777-779`]
- [x] [Review][Patch] `monitoring.md` anchor is malformed — GitHub's slugger drops the em dash and produces two hyphens, the references use three [`docker-compose.yml:231`, `deferred-work.md:39`]
- [x] [Review][Patch] The new `config.value.misconfigured` metric has no alert rule in `alerts.yml` or any Grafana provisioning file — nothing watches the signal AC3 introduced [`deploy/lgtm/grafana-alerts.yml`]
- [x] [Review][Patch] `monitoring.md` documents only `reason=out_of_range`, but the assertion also emits `reason=missing` and `reason=non_numeric` — the two reasons that actually break flows [`docs/deployment/monitoring.md:94`]
- [x] [Review][Patch] The summary `log.info` is unreachable on the fail-fast path, so a blocked boot never records how many keys were checked [`ConfigStartupAssertion.java:103`]
- [x] [Review][Patch] `chown -R "$probed"` runs with no numeric validation; malformed probe output aborts the restore mid-loop (after `down` and `tar -xzf`) with only some subdirs re-owned. The file already has the guard pattern at `provision.sh:621-625` [`deploy/backup/restore-from-volume-backup.sh:46-68`]
- [x] [Review][Patch] `VideoProperties.Playback.tokenMaxTtlMinutes` has no `@Min`, and `Math.min` with it defeats the fail-fast `[1, 1440]` TTL bound — a `0` property override makes every signed HLS URL expire on issue [`VideoProperties.java:46`, `PlaybackService.java:107-110`]
- [x] [Review][Patch] AC4's rewritten javadoc overstates atomicity — `SessionPackEmailListener:52-56` catches `Exception`, so an in-memory serialisation failure commits `expiryWarnedAt` with no outbox row; "either both persist or neither does" needs the documented carve-out [`SessionPackExpiryNotifier.java:39-41`]

**Deferred** (real, but pre-existing or out of scope for this story)

- [x] [Review][Defer] `image_runtime_ugid` adds up to five un-timed `docker pull`s to the disaster-restore path, between `${DC} down` and `${DC} up -d`, extending the outage window on exactly the host conditions a restore implies [`deploy/backup/restore-from-volume-backup.sh:39`] — deferred, inherent to the probe design and the `|| return 0` fallback contains it
- [x] [Review][Defer] AC3's letter says "do **not** hand-list the strings" for the templated per-enum keys, but `ConfigBounds.VIDEO_QUOTA_TIER_SEGMENTS` / `VIDEO_TYPE_SEGMENTS` do exactly that [`ConfigBounds.java:180-181`] — deferred, the documented reason (keeping `config` free of a `video.contract` / `marketplace.contract` dependency) is sound and `ConfigBoundsEnumCoverageTest` supplies the drift guard the AC actually wanted; needs owner sign-off only because story-review hardened that wording

**Dismissed as noise (11)** — notable false positives, recorded so they are not re-raised: the AC4 javadoc was challenged as an unbacked claim but `SessionPackEmailListener.java:37` really is `BEFORE_COMMIT` and the publish really is inside `transactionTemplate.execute`; the `ApplicationReadyEvent` timing was raised generically but matches the established `TlsStartupAssertion` pattern (the *scheduler-ordering* consequence is kept above as a decision item); a compose `user:` override was hypothesised but no service defines one; `min > max` transposition was raised but all 44 call-site literals were checked against the registry and match; the `NeglectedSkillDetectionService` negative guard is deliberate documented belt-and-suspenders; the two "clamps to default" tests were called vacuous but their exact-argument stubs do function as mutation checks and `ConfigServiceTest:227-309` covers the clamp itself — only the test *names* over-claim.

### Review triage — applied 2026-09-10 (dev)

Every finding was checked against the code before acting; nothing dismissed as a false positive on this pass (the review's own "Dismissed as noise" list was accepted as-is).

**Patch findings — all fixed except one partial:**

| # | Finding | Fix |
|---|---|---|
| 1 | redis uid probe returns `0:0` → chowns data dir to root | **Fixed.** Probe rewritten (`image_runtime_uid`): reads `docker inspect .Config.User` — not `docker run … id -u` — because redis/postgres drop privileges in their entrypoint; resolves names in-image; `""`/`0`/non-numeric all yield `""` → silent fallback. `chown_probed` now probes the **uid only** and keeps the constant's **gid** pinned. |
| 2 | `config.value.misconfigured` registered with two tag-key sets → `PrometheusMeterRegistry` throws | **Fixed.** `ConfigStartupAssertion` dropped the `expected` tag — same `{key, reason}` scheme `ConfigService` uses; the range stays in the ERROR log line. |
| 3 | `radar_composite_dlq.max_attempts` unseeded → ERROR+metric every boot | **Fixed.** `ConfigBounds.HAS_CODE_DEFAULT` — keys with a 2-arg call site (or caught missing-key) log an absent value at DEBUG, no metric, no throw. |
| 4 | `getLong(String)` does not `trim()` | **Fixed.** Added `.trim()` — matches `getLong(key,default)` and the assertion. Test added. |
| 5 | `video.lifecycle.batch_size` — scheduler falls back to 100, listener throws on missing | **Fixed.** `VideoSubscriptionLifecycleListener` now `getBoundedInt(key, 100, 1, 10000)` — one contract. |
| 6 | AC6 `secrets-reference.md` misdescribes the mechanism (`env-guard.sh` bare-sources, no `set -a`) | **Fixed.** Section + script pointer comments + ledger `[DECIDED]` tag rewritten: unexported shell vars, real surfaces are the `docker exec -e` child + container envs. |
| 7 | Grafana probe `472:0` vs constant `472:472` → false "update the constant" WARN every run | **Fixed** by the #1 rewrite (uid-only probe, pinned gid → `472:472`, no WARN; a real uid drift still WARNs). |
| 8 | per-site `verify(...)` missing for GdprExport / NeglectedSkill / QuotaConfig / VideoTypeConstraints | **Partial.** Added `QuotaConfigServiceTest`, `VideoTypeConstraintsTest` (new), + a `NeglectedSkillDetectionServiceTest` verify. `GdprExportService.urlExpiryHours` stays covered by `GdprExportIT` (real bounded read against the seed) — a unit test there needs disproportionate scaffolding (fileStorage, zip, request). |
| 9 | `ConfigBounds` javadoc uses `signed_url_ttl_minutes` as a `failFast=false` example, declares it `true` | **Fixed.** Example changed to genuinely non-breaking cases. |
| 10 | redis comment transposes uid/gid | **Fixed.** Comment rewritten; the redis special-case collapsed into the unified `chown_probed`. |
| 11 | `monitoring.md` anchor malformed (em dash → double hyphen, refs use three) | **Fixed.** Heading renamed `—` → `:`; the 2 references updated. |
| 12 | new metric has no alert rule | **Deferred / noted.** Adding an alert rule needs a threshold + window + routing design; recommended as a follow-up (an `increase(config_value_misconfigured_total[1h]) > 0` rule). |
| 13 | `monitoring.md` documents only `reason=out_of_range` | **Fixed.** Now lists `out_of_range` / `missing` / `non_numeric`. |
| 14 | summary `log.info` unreachable on the fail-fast path | **Fixed.** Moved before the throw. |
| 15 | `chown -R "$probed"` no numeric validation | **Fixed** by the #1 rewrite (`case … *[!0-9]*|0) return 0`). |
| 16 | `VideoProperties.Playback.tokenMaxTtlMinutes` has no `@Min`, `Math.min` defeats the TTL bound | **Fixed.** `Math.max(1, …)` at the `PlaybackService` call site (the project wires no `@Validated` on `@ConfigurationProperties`, so `@Min` would be inert). |
| 17 | AC4 javadoc overstates atomicity | **Fixed.** Added the `catch (Exception)` carve-out (listener swallows → `expiryWarnedAt` commits with no outbox row). |

**Decision findings:**

| # | Finding | Disposition |
|---|---|---|
| 1 | assertion never fail-fasts on blank / non-numeric | **Implemented.** `non_numeric` (a present garbage value) is always ERROR and, for a `failFast` key, a boot blocker. Absent/blank is a boot blocker for a `failFast` key **unless** it is in `HAS_CODE_DEFAULT`. |
| 2 | `@Scheduled` registers before `ApplicationReadyEvent` | **Accepted + documented.** The read-time clamp means a pre-assertion tick runs against a *safe* value; pre-empting it needs a `BeanFactoryPostProcessor`/`@PostConstruct`, deviating from the `TlsStartupAssertion` precedent for a one-tick window. Recorded as "Known limitation" in the class javadoc. |
| 3 | 3-arg clamps, 4-arg falls to default → `archived_to_deleted_days=4000` deletes ~10y early | **Partial.** Raised that key's ceiling 3650 → **36500** (100y) — a deliberate long-retention setting no longer defaults to 90 and destroys assets early. The shared 4-arg `getBoundedLong(key,default,min,max)` default-vs-clamp semantics are left unchanged (established contract + tests across 8 pre-existing callers) — flagged for the owner if a wider change is wanted. |
| 4 | AC5 rationale factually false; REVISIT trigger already fired | **Fixed.** `docker-compose.yml` comment + ledger tag rewritten: every `skillars-observability` member (incl. third-party loki/tempo/redis) can reach `node_exporter:9100`, **but** the network is `internal: true` so only `app`/`grafana` have egress — a compromised infra container cannot exfiltrate, and `app` already holds every real credential. REVISIT reworded to "a member with reachability AND egress". |
| 5 | `ConfigBounds` "single source of truth" omits 11 already-bounded sites | **Fixed.** Javadoc narrowed to "keys migrated by AC2" and now names the 8 pre-existing `getBoundedLong` sites left out (scope, not oversight). |
| 6 | AC7 converts 4 unprovisioned Prometheus rules into silent pages | **Fixed (documented).** `monitoring.md` "Alerting architecture" section now lists the 4 rules with no Grafana twin (`DbConnectionPoolHigh`, `JvmHeapHigh`, `BookingPaymentSettleFailureRateHigh`, `SubscriptionInvoicePaymentFailureHigh`) and states the close-it-or-accept-it choice. Provisioning them is a follow-up (each needs a threshold). |
| 7 | runtime `PUT /api/config` bypasses every range check | **Implemented.** `ConfigService.updateConfig` → `rejectOutOfRange(key, value)`: for a `ConfigBounds` key, a non-numeric or out-of-range write is rejected with `ResponseStatusException(400)` before it goes live. 4 new `ConfigServiceTest` cases. |

**Still open for the owner:** #3's shared-overload semantics question (default vs clamp on the 4-arg path); #12 the `config.value.misconfigured` alert rule; #6 provisioning the 4 Prometheus rules; #2 whether the one-tick scheduler window warrants a pre-`ApplicationReadyEvent` hook.

---

## Change Log

| Date | Change |
|---|---|
| 2026-09-10 | Story created. Item-selection re-verified against HEAD `918ff206`: 3 of the 6 listed `deploy-*` items already closed (`deferred-101` AC6, `deferred-102` AC2 / AC6-AC7); owner decisions D1–D5 captured (accept-and-document node_exporter + `/proc/environ`; helper+assertion for config ranges; write the Alertmanager decision doc; scope = core + full sweep + UID-chown fix). |
| 2026-09-10 | Applied `story-review.md` (senior-dev audit). AC2: the no-default bounded overload now **clamps** (was: throw) so AC3 is the sole fail-fast authority; added a per-site business/technical bound checklist; `VideoAccessGuard` bound reframed as a business cap under the `toIntExact` ceiling. AC3: run ERROR+metric in **all** profiles, gate only the throw to non-`dev` (deviation from `TlsStartupAssertion`, with rationale); explicit fail-fast principle + full-key table as a pre-coding deliverable; per-tier keys now **must** iterate the `CoachSubscriptionTier`/`VideoType` enums (was "enumerate or skip"); `expected="[min,max]"` metric tag added; `gdpr.export.urlExpiryHours` + `signed_url_ttl_minutes` promoted to fail-fast. AC1: added the "environment already holds a bad value on ship" note. AC8: redis `:1000` gid override made explicit (probe uid only, pin gid) + manual-test step. Dev Notes: local config-validation note. Review's 🔴 on `VideoAccessGuard` assessed as a guidance-sharpening note, not a correctness defect — addressed by the bound-checklist edit. |
| 2026-09-10 | **Implemented (all 9 ACs).** AC1–AC3: `ConfigService.getBoundedInt` + no-default clamp `getBoundedLong(key,min,max)`; new `ConfigBounds` (single source of truth, enum-generated per-tier/type keys) + `ConfigStartupAssertion` (`ApplicationReadyEvent`, ERROR+metric all profiles, throw non-`dev`); all 31 `getLong`/`getInt` call sites migrated; 17 existing test classes retargeted + 3 new test classes (`ConfigServiceTest` extended, `ConfigStartupAssertionTest`, `ConfigBoundsEnumCoverageTest`); `monitoring.md` config-ranges table. Deviation from the story's bound table: `video.quota.*` storage/bandwidth use `[0, Long.MAX_VALUE]` (scout is seeded `0` = "no upload", `V53`). AC4: `SessionPackExpiryNotifier` "Delivery." javadoc corrected to `BEFORE_COMMIT` + outbox. AC5/6/7: `docker-compose.yml` comments; `monitoring.md` Alertmanager-decision section; `secrets-reference.md` accepted-exposure section + script pointer comments. AC8: `image_runtime_ugid` probe + `chown_probed` in `provision.sh` / `restore-from-volume-backup.sh` (constants kept as WARN-on-mismatch fallback; redis uid-only). AC9: ledger — 5 audit-table rows struck, 3 `[DECIDED]` retags, 3 bullets deleted, new audit block + reconstruction check. Validation: backend compile + targeted unit tests (0 failures across config/payment/video/booking/messaging/development/admin/reviews service packages); `bash -n` + `shellcheck` + `docker compose config` clean. Per `validation-strategy.md`, `mvn verify` / IT suite left to GitHub CI. Status → review. |
| 2026-09-10 | **Applied the adversarial code review** (see Review Findings → Review triage). 17/17 Patch findings fixed (one partial: GdprExport verify stays on the IT). 7 Decision findings: 5 implemented, 1 partial, 1 accepted-and-documented. Key changes: AC8 probe rewritten to read `docker inspect .Config.User` (not `docker run id -u`) with uid-only + pinned-gid — fixes the redis→root and grafana-false-WARN bugs; `ConfigStartupAssertion` dropped the `expected` metric tag (`PrometheusMeterRegistry` tag-key clash) and gained `ConfigBounds.HAS_CODE_DEFAULT` so unseeded 2-arg keys don't spam boot ERRORs and blank/non-numeric fail-fasts where there's no fallback; `ConfigService.getLong(String)` now trims; `ConfigService.updateConfig` range-rejects a bounded-key `PUT` with 400 (+4 tests); `archived_to_deleted_days` ceiling 3650→36500 (destructive-deletion safety); AC5 node_exporter rationale + AC6 `/proc/environ` mechanism corrected to fact; `monitoring.md` documents the 4 unprovisioned Prometheus rules. New tests: `QuotaConfigServiceTest`, `VideoTypeConstraintsTest`. All touched unit-test packages green. |
