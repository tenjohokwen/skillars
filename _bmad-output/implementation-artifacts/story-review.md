# Senior-Dev Audit — `skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes`

**Reviewed:** 2026-09-21 · **Against:** working tree at `a0d91f95` (branch `story/deferred-127-lock-config-scheduler-fixes`)
**Method:** every file, line citation, constraint, precedent and test file named in the story was opened and
checked against actual source. Findings that could not be substantiated from source were dropped rather than
reported. Where a claim is *correct*, it is listed in § "Verified correct" so the dev does not re-litigate it.

**Verdict: `ready-for-dev` is premature.** Two findings are blocking (B1, B2). B1 means AC1 as written will fail
every PLAYER-role GDPR erasure in production and turn six currently-green integration tests red. B2 means the
story's own headline "re-verification finding" is wrong by a factor of 4.5x and has already propagated into
`sprint-status.yaml`. The underlying four fixes are all worth doing; the specifications need correcting first.

---

## Blocking

### B1 — AC1 locks on the wrong identifier: the PLAYER-role path passes a `user.id` where a `player_profiles.id` is required

**Severity:** blocking · **Files:** `GdprErasureService.java:126-131`, `PlayerProfileRepository.java:44-47`,
`BaseEntity.java`, `V138__baseline_schema.sql:896-912,4087-4105`, `GdprErasureIT.java`

`deletePlayerDevelopmentData` is called with two structurally different identifiers:

```java
// GdprErasureService.java:126-131
if (role == SkillarsRole.PLAYER) {
    deletePlayerDevelopmentData(userId, blobKeysToDelete);          // ← main."user".id
} else if (role == SkillarsRole.PARENT) {
    playerProfileRepository.findByParentId(userId)
        .forEach(pp -> deletePlayerDevelopmentData(pp.getId(), blobKeysToDelete));  // ← main.player_profiles.id
}
```

These are not the same key space:

- `PlayerProfile extends BaseEntity`, whose `id` is `@Id @Tsid` (`BaseEntity.java:26-28`) — an
  independently generated TSID. A player profile's link to its owning account is the **separate**
  `user_id` column (`PlayerProfile.java:41-43`), reachable via `PlayerProfileRepository.findByUserId`
  (`:24`), not via `findById`.
- `V138__baseline_schema.sql:911` enforces `chk_pp_owner CHECK ((parent_id IS NOT NULL AND user_id IS NULL)
  OR (parent_id IS NULL AND user_id IS NOT NULL))` — confirming `user_id` is the account link and `id` is not.
- Every development table keys on `player_profiles.id`, not on a user id:
  `fk_prb_player_id`, `fk_prc_player_id`, `fk_pswsa_player_id` all
  `REFERENCES main.player_profiles(id)` (`V138:4091,4098,4105`).

AC1 Task 3 mandates adding, at the top of that method:

```java
var playerProfile = lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
    .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
```

On the PLAYER branch `playerId` is a `user.id`, so `findByIdForUpdate` finds nothing and
`ResourceNotFoundException` propagates out of `erase()`. `GdprEventListener.onErasureRequested`
(`:34-41`) catches it, calls `markFailed`, and the whole `REQUIRES_NEW` transaction rolls back.
**Every PLAYER-role GDPR erasure fails.** `PessimisticLockRetryer.withBoundedRetry` explicitly does *not*
absorb this — "Any other exception — including a genuine not-found — propagates immediately, unretried"
(`PessimisticLockRetryer.java:118-124`).

**The story's "existing tests must stay green" assertion is false.** `GdprErasureIT` never inserts a
`main.player_profiles` row — not in `setUp()` (`:67-100`), and not in `secData.sql` (grepped: zero hits).
`development.performance_reports.player_id` carries no FK (confirmed: no `REFERENCES` for that table in
V138), which is why the fixtures get away with using `PLAYER_ID = 9210_000_003L` — a `user.id` — as a
`player_id`. Six tests drive a PLAYER-role erasure and would all go red:

| Line | Test |
|---|---|
| 181 | `requestErasure_playerUser_erasureCompletesSuccessfully` |
| 316 | `erase_playerUser_deletesPerformanceReportFromS3` |
| 345 | `erase_playerUser_reportKeyGoesThroughOutbox_thenDrainClearsIt` |
| 369 | `erase_playerUser_s3DeleteFails_leavesOutboxRow_reDrivableOnceBackoffExpires` |
| 416 | `erase_playerUser_s3DeleteFails_erasureStillCompletes` |
| 442 | `erase_playerUser_videoCascade_purgesVideos_resetsQuota_cancelsPendingApprovals` |

**Secondary consequence — a pre-existing bug the story assumes away.** Because a TSID `player_profiles.id`
will essentially never equal a `user.id`, the PLAYER branch's `deleteAllByPlayerId` calls already delete
*nothing* today. So AC1's own motivating scenario (resurrection of an erased player's radar rows) is
currently reachable only through the **PARENT** branch. The story's Context section presents it as
universal.

**What the story must specify instead:**
1. Resolve the profile correctly per branch — e.g. `playerProfileRepository.findByUserId(userId)` on the
   PLAYER branch, or hoist id resolution into `erase()` so `deletePlayerDevelopmentData` always receives a
   `player_profiles.id`. Note this is itself a behaviour change (PLAYER development data would start
   actually being deleted) and deserves its own owner decision.
2. Decide explicitly what happens when **no** profile row exists for the account (a PLAYER user who never
   had a profile). The mandated `orElseThrow` converts today's silent no-op into a hard erasure failure.
   `orElse`-and-skip is almost certainly the right call for a GDPR path; the story currently forbids it by
   prescribing `recalculateComposite`'s pattern verbatim.
3. Add `main.player_profiles` seeding to `GdprErasureIT.setUp()` — see F10 for why the *shape* of that
   seeding matters.

---

### B2 — AC2's key count and scope characterisation are both wrong: 17 keys, of which only 4 are affected

**Severity:** blocking (factual) · **Files:** `ConfigBounds.java:293-317`, `V139__baseline_seed_data.sql`

The story states, in four places (Priority line, AC2 Context, AC2 Task 2, AC5 Task 2 bullet 4, Change Log),
that `ConfigBounds.HAS_CODE_DEFAULT` holds **18** keys and that all 18 are unwritable. It frames this as the
one concrete correction its own re-verification produced ("not just the 3 the original deferral bullet named
as examples — confirmed by reading `ConfigBounds.HAS_CODE_DEFAULT`'s full `Set.of(...)` in full").

**Two independent errors:**

**(a) The count is 17.** `ConfigBounds.java:300-317` — the `Set.of(...)` opens on 300 and the 17 entries
occupy lines 301–317. Verified mechanically: `17`.

**(b) 13 of the 17 *are* seeded and are already writable today.** Checked each key's literal against
`V139__baseline_seed_data.sql`:

| Key | Seeded in V139? |
|---|---|
| `pack.pause.maxDays` | yes |
| `disputes.submissionWindowDays` | yes |
| `platform.video.lifecycle.blocked_to_archived_days` | yes |
| `platform.video.lifecycle.archived_to_deleted_days` | yes |
| `platform.video.lifecycle.batch_size` | yes |
| **`platform.moderation_sla_batch_size`** | **no** |
| `platform.video.playback.signed_url_ttl_minutes` | yes |
| `platform.video.access.coach_window_days` | yes |
| `platform.video.deletion.max_attempts` | yes |
| **`platform.development.radar_composite_dlq.max_attempts`** | **no** |
| `gdpr.export.urlExpiryHours` | yes |
| `platform.message_retention_months` | yes |
| `reviews.submissionWindowDays` | yes |
| `reviews.autoHoldFlagThreshold` | yes |
| `development.timeline.coachAccessExpiryDays` | yes |
| **`security.rate_limiting.bucket_ttl_hours`** | **no** |
| **`platform.radar_composite_lock_timeout_seconds`** | **no** |

The four unseeded keys appear nowhere in `src/main/resources` (grepped). **The real scope of the gap is 4
keys, not 18** — and three of those four are exactly the three the original ledger bullet named. The correct
ledger correction in AC5 Task 2 is **"3 keys → 4 keys"**, not "3 → 18". As written, AC5 would write a wrong
number into `deferred-work.md`.

**Root cause of the error — a misread Javadoc.** AC2 Context asserts `HAS_CODE_DEFAULT` "is, by its own class
Javadoc, deliberately the set of keys with **no Flyway seed migration**". The actual Javadoc
(`ConfigBounds.java:293-299`) says something different:

> Keys whose call site passes a code default (2-arg `getLong`/`getInt`) or catches the missing-key
> `IllegalStateException` — an *absent* or *blank* value is survivable there…

The set is defined by **call-site tolerance of absence**, not by seeding. "No Flyway seed" appears only as an
incidental aside in `ConfigStartupAssertion.java:89-90`. The story inherited an imprecise framing from the
ledger bullet's own title and escalated it into a hard count without checking.

**Already propagated:** `sprint-status.yaml:2` carries "corrected the config-key gap's scope from the
original bullet's '3 keys' to the actual 18 HAS_CODE_DEFAULT keys". That line needs correcting too.

**What is still correct:** the *fix* (upsert on write, scoped to `HAS_CODE_DEFAULT`) and its scoping rationale
survive. I independently confirmed the scoping argument: every non-`HAS_CODE_DEFAULT` key in
`ConfigBounds.ALL` — including all 12 generated `video.quota.*` and 6 `video.*` keys — is seeded in V139, so
narrowing the upsert to `HAS_CODE_DEFAULT` leaves no other bounded key unwritable. Only the framing,
the count, and the ledger correction need rewriting.

---

## High

### H1 — AC1 Task 4's "locked/unlocked independently" is false: Postgres holds row locks to end of transaction

**Files:** `GdprErasureService.java:75,128-131`

> "confirm the new lock acquisition happens once per player inside the method itself (not hoisted above the
> loop in `erase()`), so each player in a multi-child family is **locked/unlocked independently**"

`SELECT … FOR UPDATE` row locks are released only at transaction end. `erase()` is a single
`@Transactional(propagation = REQUIRES_NEW)` method (`:75`), and the PARENT loop runs entirely inside it.
Locking inside `deletePlayerDevelopmentData` does **not** release each player's lock per iteration — the
locks accumulate and are all held until `erase()` commits. The placement decision is still right (per-player
acquisition keeps the *acquisition* granular and bounded), but the stated reason is wrong, and two
consequences follow that the story does not account for:

- A parent with N children holds N `player_profiles` `FOR UPDATE` locks simultaneously.
- Worst-case added latency is **N × ~3.2s**, not ~3.2s. The "fails fast within ~3.2s" claim in the Context
  section is per-acquisition, not per-erasure.

Rewrite the task's rationale, and restate the budget as per-player.

### H2 — AC1's blast-radius analysis omits FK-induced lock conflicts on `player_profiles`

**Files:** `V138__baseline_schema.sql:4083-4105,4230-4231,4496-4504`

The "Why this is lower-risk than it may first look" paragraph reasons only about contention between the
erasure path and `recalculateComposite`. But `FOR UPDATE` on a `player_profiles` row conflicts with the
`FOR KEY SHARE` lock Postgres takes on that row for **every referential-integrity check** from a child
insert. Seven tables reference `main.player_profiles(id)`:

`development.coach_radar_preferences`, `development.player_radar_baselines`,
`development.player_radar_composites`, `development.player_slu_weekly_snapshot_applied`,
`main.parent_player_links`, `payment.player_subscriptions`, `payment.player_subscription_changes`.

Because the lock is now held for the **remainder of the erase transaction** (which continues through
performance-report iteration, blob-key collection, refresh-token revocation, GDPR-request updates and event
publication — `:134-182`), any concurrent insert into those seven tables for that player will block on it.
This is a new exposure specific to the erasure path; `recalculateComposite` already had it but holds the lock
for a much shorter, bounded window.

Add this to the AC's risk statement. It does not change the recommended mechanism, but it should be a
recorded consequence rather than a surprise.

### H3 — AC1 introduces a new GDPR-erasure failure mode, unweighed

**Files:** `RadarCompositeCalculationService.java:66-67,191-193`, `ConfigBounds.java:255-256`,
`PessimisticLockRetryer.java:40-50,71-78`, `GdprEventListener.java:36-41`, `GdprRequestService.java:80-101`

`recalculateComposite` can hold the `player_profiles` lock for up to
`CUMULATIVE_LOCK_WAIT_BUDGET = Duration.ofSeconds(ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.max())`
= **120 seconds** (`RadarCompositeCalculationService.java:66-67`; `max` is `120L`,
`ConfigBounds.java:256`). The erasure's retry budget is ~3.2s (8 attempts, 100ms→800ms×1.6,
`PessimisticLockRetryer.java:44-45,71-81`).

So under genuine contention, the fix's effect is: **a GDPR erasure that previously succeeded now fails.**
The story acknowledges the fast failure but characterises the outcome benignly — "routed to `markFailed` by
its existing caller, retriable". Checked against source:

- "Retriable" is technically true but only manually: `markFailed` sets `status = 'FAILED'`
  (`GdprErasureService.java:185-192`) and nothing re-drives it — there is no sweeper over FAILED GDPR
  requests. `requestErasure` blocks only on `PENDING`/`PROCESSING` (`GdprRequestService.java:82-85`), so the
  user *can* re-submit. Because the transaction rolled back, the user was never anonymised or locked out, so
  they still can log in to do so. That much holds.
- But `markFailed` raises **no `AdminAlert`** and emits only `log.error` (`:190`). A regulatory-deadline
  operation now has a new silent-failure path caused by unrelated background work.

Recommend either (a) an explicit owner decision to accept this with an `AdminAlert` on `markFailed`, or (b) a
larger retry budget scoped to this one call site. Either way it needs to be stated, not implied.

### H4 — AC1 Task 6's documentation correction is scoped to one of four stale locations

**Files:** `ConfigBounds.java:238-249`, `RadarCompositeCalculationServiceConcurrencyIT.java:217,267`,
`deferred-work.md:2450-2460`

Task 6 correctly identifies that `RadarCompositeCalculationService`'s Javadoc (`:104-112`) states the gap in
present tense and will become false. Three other places do the same and are not mentioned:

1. **`ConfigBounds.java:243-245`** — `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s Javadoc: *"see
   `recalculateComposite`'s own Javadoc for the write-order deadlock hazard this guards against
   (`GdprErasureService.deletePlayerDevelopmentData` takes the same two tables in the opposite order)"*.
2. **`RadarCompositeCalculationServiceConcurrencyIT.java:217` and `:267`** — the deadlock test's Javadoc and
   inline comment, both framed as *"mirrors `GdprErasureService.deletePlayerDevelopmentData`'s own order"*.
   The test itself stays valid (it drives both sides with raw SQL), but its framing becomes historical.
3. **`deferred-work.md:2450-2460`** — this is the substantive one. The
   `AdminCoachEnforcementService.deleteStrike` `[DECIDED: accepted risk]` bullet justifies *remaining*
   accepted by contrasting itself against the radar case:

   > "the radar case's competing transaction (`GdprErasureService.deletePlayerDevelopmentData`) has
   > genuinely unbounded work ahead of it — **no lock-retry budget bounds it** — whereas here the winning
   > transaction is itself already lock-retry-bounded (`PessimisticLockRetryer`'s ~3.2s worst-case budget)"

   AC1 makes `deletePlayerDevelopmentData` lock-retry-bounded by exactly that mechanism, collapsing the
   stated distinction. That accepted-risk decision's rationale needs rewriting or re-deciding.

AC5 Task 5's grep-sweep is ledger-only ("against the rest of the ledger") and is phrased as "re-confirm each
hit is either unrelated or already correctly annotated" — wording that invites marking (3) "unrelated". Make
(1)–(3) explicit tasks. Note the File List currently lists neither `ConfigBounds.java` nor
`RadarCompositeCalculationServiceConcurrencyIT.java`.

---

## Medium

### M1 — AC4's test plan assumes a hostname seam that does not exist

**Files:** `ShedLockConfig.java:56-69`, `ShedLockConfigIT.java` (whole file)

AC4 Tests says to construct a hostname "via whatever seam the existing tests already use to control
`Utils.getHostname()`'s effective value, or a directly-testable extraction of the truncation logic if
`Utils.getHostname()` itself is not mockable — check the existing test file's own pattern before inventing a
new one."

I checked. **There is no seam.** All four existing tests in `ShedLockConfigIT` use the machine's real
hostname (`:88` calls `Utils.getHostname()` directly to build its assertion). The truncation is inlined in
the `@Bean` method body (`:65-69`) with no extracted, callable unit. `net.javacrumbs.shedlock.support.Utils`
is a third-party class and `getHostname()` is static.

So AC4 as written cannot be delivered without a **production code change the tasks do not list** — extracting
the truncation into a package-private static method (mirroring the package-private-for-testing precedent
already established at `:51-54` for `MAX_HOSTNAME_LENGTH`). Add that to Tasks. Alternatively, `mockStatic`
precedent does exist in this repo (`JwtManagerImplTest`, `RateLimitingAspectIT`) — name whichever you pick
rather than leaving the dev to discover the seam is missing.

Also: `ShedLockConfigIT:93-95` re-implements the truncation (`hostname.substring(0,
ShedLockConfig.MAX_HOSTNAME_LENGTH)`) inside its assertion. Once the production logic gains a
surrogate-pair branch, that assertion duplicates a now-divergent rule. AC4 Task 3 (comment update) should
extend to keeping that assertion consistent.

Minor: the File List labels `ShedLockConfigIT.java` as new; it already exists (187 lines). Not a defect, but
the AC4 Tests section says "New `ShedLockConfigIT` test" where it means "new test method in the existing
file".

### M2 — AC4's severity claim is unsupported; the fix is still worth shipping

**Files:** `ShedLockConfig.java:41-48,65-69`

The Context asserts a lone high surrogate "cannot be UTF-8-encoded", that the driver "either substitutes
`U+FFFD` or the `INSERT`/`UPDATE` fails with `invalid byte sequence for encoding "UTF8"`", and concludes it
would break "**every** `@SchedulerLock` job".

The hedge ("either… or") is doing all the work, and the conclusion does not follow from either branch.
Java's standard UTF-8 encoder (`String.getBytes(UTF_8)` / `CharsetEncoder` with the default REPLACE action)
substitutes `?` for an unpaired surrogate — it does not throw and does not emit invalid UTF-8. And in either
substitution outcome, the `"-" + UUID.randomUUID()` suffix (`:69`) is untouched, so `locked_by` remains
unique per JVM and the unlock predicate (`name = :name AND locked_by = :lockedBy`) still works. Nothing
breaks; a hostname prefix is cosmetically mangled in an operational column.

The ledger recorded this as "very low likelihood" and did not verify the consequence. The story promotes it
to a stated mechanism without new evidence. Keep the fix — it is two lines, correct, and the sketch in
Task 2 is in fact off-by-one-correct as written (verified: for `length ≥ 219`, `charAt(217)`/`charAt(218)`
are exactly the boundary pair `substring(0,218)` would split, and the `truncateAt < hostname.length()` guard
holds) — but do not carry "breaks every `@SchedulerLock` job" into the updated Javadoc unless the optional
round-trip test in AC4 Tests actually demonstrates it.

### M3 — AC5's `EXPLAIN` task rests on a premise the repo already answers

**Files:** `V144__outbox_dlq_claimed_at.sql:30-36`, `VideoDeletionOutboxRepository.java:99-105`,
`RadarCompositeDlqRepository.java:81-87`

AC5 Task 2 bullet 7 says to run `EXPLAIN`, "confirm the intended index is still used", and adds: "if index
coverage turns out NOT to hold, that is itself a new finding to surface, not silently absorb."

It will not hold, and it is not new. `V144__outbox_dlq_claimed_at.sql:30-36` already records the answer and
the decision:

> Index coverage: `resetStaleClaimed`'s predicate moves from `next_retry_at < :deadline` (served by
> `idx_radar_composite_dlq_status_retry` ON `(status, next_retry_at)` / `idx_vdoutbox_status_retry`) to
> `claimed_at < :deadline`, which those indexes **no longer cover past their status prefix** … Both tables
> are expected to be small enough (near-zero CLAIMED rows at any moment) that **this is accepted as-is**;
> add a matching partial index only if row-count evidence at a future date says otherwise.

Reframe the task as "record V144's existing accepted-as-is note in the closeout, and confirm empirically that
it still describes the current predicate" — which is a genuinely useful five-minute task — rather than as a
discovery exercise with a pre-wired "new finding" branch.

Two precision issues in the same bullet:
- It cites the predicate as `claimed_at < now() - make_interval(secs => ?)`, omitting the `claimed_at IS NULL
  OR` disjunct that is actually present in both queries (`VideoDeletionOutboxRepository.java:103`,
  `RadarCompositeDlqRepository.java:85`). That disjunct is part of why an index-only path is unavailable.
- Running `EXPLAIN` needs a live Postgres, which sits awkwardly against this project's standing "no local
  `mvn verify`" convention (Dev Notes). Say how: a throwaway Testcontainers session or a `psql` against a
  local compose stack.

### M4 — AC1's test plan cannot observe what it asserts, and as specified would mask B1

**Files:** `GdprErasureIT.java` (whole file), `GdprEventListener.java:31-42`,
`RadarCompositeCalculationServiceConcurrencyIT.java:35-118`

Three concrete problems with placing these tests in `GdprErasureIT`:

1. **The assertion is unobservable through the existing harness.** `GdprErasureIT` drives everything over
   HTTP. `erase()` runs inside `GdprEventListener.onErasureRequested`'s `AFTER_COMMIT` listener, which
   **swallows every exception** (`:36-41`) — the HTTP call still returns 202. A test asserting "`erase()` …
   fails fast … with `PessimisticLockingFailureException`" must invoke `GdprErasureService.erase` directly.
   `GdprErasureIT` has no `@Autowired GdprErasureService` (fields at `:54-63`) and seeds no
   `admin.gdpr_requests` row for the erase path, both of which the test would need to add.

2. **No `player_profiles` fixture exists.** Per B1, `setUp()` (`:67-100`) and `secData.sql` create none. The
   `RadarCompositeCalculationServiceConcurrencyIT` precedent the story points at does seed one
   (`:52-56`), which is exactly the pattern to copy.

3. **The obvious way to make the test pass hides the real bug.** If the fixture seeds a `player_profiles`
   row whose `id` equals the erased user's `id` — the only way the PLAYER path works under the story's
   prescribed lookup — the test goes green while production, where `player_profiles.id` is a TSID unrelated
   to `user.id`, stays broken. Specify that the test must seed a `player_profiles` row with a **TSID-shaped
   id distinct from the user id**, linked via `user_id` / `parent_id`, so it actually exercises the
   production key relationship.

The story's own instruction — "must be written to actually reach that pre-fix failure if run against
`master@24fe4a80`" — is the right discipline; it just needs the fixture shape nailed down, or the test will
satisfy the letter of it against a fixture production never produces.

### M5 — AC3 raises scheduler concurrency 8× with no connection-pool or lock-coverage analysis

**Files:** `application.yaml:101-107`, `PessimisticLockRetryer.java:40-56`, `SchedulingConfig.java:15-20`

The counts in AC3 check out (44 `@Scheduled` methods; no `spring.task.*` anywhere in main or test resources;
`VideoDeletionOutboxProcessor.MAX_RUN_DURATION = Duration.ofMinutes(12)` at `:85`). But the AC treats "a
one-line static property change" as carrying no risk beyond a grep for stale comments. Two things worth
sizing:

- **Connection pool.** `spring.datasource.hikari.maximum-pool-size: 25` (`application.yaml:106`), sized
  as "up to 4 nodes, i.e. 25 for each". Going from 1 to 8 concurrently-running scheduled jobs adds up to 7
  simultaneous holders. `PessimisticLockRetryer`'s own Javadoc is explicit that a contended caller **holds
  its pooled JDBC connection while sleeping** for up to ~3.2s and closes with "Watch the timer's p99
  against the HikariCP pool size" (`:40-56`). Several scheduled jobs go through that retryer
  (`PaymentPendingSweeper`, `MessageModerationSweeper`, `BookingBatchService`, and after AC1 the erasure
  path). This deserves a sentence, and ideally a note to watch `persistence.lock_retry` p99 post-deploy.
- **Jobs without ShedLock.** 44 `@Scheduled` vs 26 `@SchedulerLock` annotations — so ~18 scheduled methods
  have no distributed lock. Single-threading was incidentally serialising them *against each other*
  in-process; 8 threads removes that. (Spring still won't run the same `fixedDelay` task concurrently with
  itself, so self-overlap is not a new risk — cross-job overlap is.) Task 5's grep sweep looks for
  *comments* asserting single-threaded execution; it will not surface a job that merely happens to share
  rows with another.

Also worth one line: `spring.task.scheduling.pool.size` governs Spring's `@Scheduled` scheduler only. This
application also carries Quartz configuration (`application.yaml:~30-44`, `main.qrtz_*` tables in V138);
that pool is unaffected. The AC never claims otherwise, but a reader closing "single-thread scheduler
starvation" may assume broader coverage.

*Correct as stated:* AC3 Task 3's claim that the property is inert under `app.scheduling.enabled: false` —
Boot's `taskScheduler` bean is conditional on the `@EnableScheduling` annotation post-processor, which
`SchedulingConfig`'s `@ConditionalOnProperty` (`:42`) removes in the test profile. Worth naming that
mechanism so the "read-and-confirm" task has something concrete to confirm against.

### M6 — AC2's tests cannot prove the claim, and the riskiest part of the change is untested

**Files:** `ConfigServiceTest.java:296-338`, `PlatformConfig.java:26-42`,
`V138__baseline_schema.sql:855-876,2674-2678`, `ConfigResourceIT.java:213-231`

- `ConfigServiceTest` is a Mockito unit test with a mocked `PlatformConfigRepository` (existing
  `updateConfig_*` tests all `verify(configRepository).save(...)`). The proposed
  `updateConfig_unseededHasCodeDefaultKey_createsRow` assertion that "the value is readable afterward"
  cannot be demonstrated there without stubbing `findAll()` to return the row you just asked the mock to
  save — which proves nothing.
- **This AC introduces the first application-side `INSERT` into `main.platform_config`.** Today the only
  write is `save()` on an already-loaded managed entity. That table is unusual:
  `id bigint NOT NULL` with `ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY` (`V138:870-876`), while
  the entity inherits `@Id @Tsid` from `BaseEntity`. Explicit-id insert against a `GENERATED BY DEFAULT`
  identity is legal, and `V139`'s header (`:39-43`) shows the sequence was deliberately bumped to 606
  against exactly this scenario — but it has never actually executed. It needs a real-schema test.
  `ConfigResourceIT` is the natural home: it already does PUT-then-GET at `:213-231`, and no existing test
  asserts 404-on-unknown-key, so nothing breaks.
- Task 2 enumerates `value`, `valueType`, `description`, `updatedAt` on the new entity but never **`key`**
  — which is `nullable = false` (`PlatformConfig.java:28`).
- Task 3 says "confirm `key` is declared `unique = true` (it is, `PlatformConfig.java:27`) so a racing
  double-create attempt fails at the DB constraint". Right conclusion, wrong evidence: JPA's
  `@Column(unique = true)` is inert under Flyway-managed DDL (`spring.jpa.generate-ddl` was removed per
  `deferred-work.md:2454`). The actual guarantee is `uq_platform_config_key UNIQUE (key)`
  (`V138:2674-2678`). Cite that.
- *Correct as stated:* `ConfigValueType.LONG` exists, and "every `HAS_CODE_DEFAULT` key is a `BoundedKey`"
  is already machine-guarded by `ConfigBoundsEnumCoverageTest:121`
  (`assertThat(boundedKeys()).containsAll(ConfigBounds.HAS_CODE_DEFAULT)`) — Task 2's "confirm this holds"
  is satisfied by an existing test, no manual re-check needed.

---

## Low / accuracy

### L1 — Line-citation drift

Several citations have shifted. The story mandates re-verification at implementation time (Task 1 of each
AC), so these are cheap, but they undercut the Provenance section's claim that everything was checked at
`master@24fe4a80`.

| Story citation | Actual |
|---|---|
| `RadarCompositeCalculationService.java:167-278` (`recalculateComposite`) | `167-280` |
| `RadarCompositeCalculationService.java:97-160` / `:102-160` (its Javadoc) | `97-165` |
| `GdprErasureService.deletePlayerDevelopmentData` `:194-209` | `194-213` |
| `ConfigService.updateConfig` `:202-209` | `202-211` |
| `PlatformConfig.java:27` (`key … unique = true`) | `:28-29` (line 27 is blank) |
| `application-test.yaml:126` (`app.scheduling.enabled: false`) | `scheduling:` at 126, `enabled: false` at 127 |
| `ConfigServiceTest.java:298-336` (`updateConfig_*`) | `296-338` |

### L2 — The Provenance section's bullet arithmetic contradicts AC5

Provenance says the story "closes four (bundled below as AC1–AC4) and leaves three open". Counting actual
ledger bullets against AC5 Task 2's own instructions:

- AC1 closes **bullets 1 and 2** (two bullets, one AC)
- AC2 closes bullet 4 · AC3 closes bullet 5 · AC4 closes bullet 6 · AC5 closes bullet 7

So AC1–AC4 close **five** bullets and AC5 closes a sixth, leaving exactly **one** genuinely open (bullet 3,
`now()` vs `clock_timestamp()`). "Leaves three open" points at an "Explicitly left open" list of four items,
two of which the same list then says are folded into AC4 and AC5, and one of which ("reaching further into
the ledger") is not a deferral bullet at all. AC5 Task 2 — which is the operative instruction — is correct
and internally consistent; only the Provenance narrative is not. Worth fixing because the whole story is
structured around precise bullet accounting.

### L3 — AC5 omits two ledger-hygiene consequences

**Files:** `deferred-work.md:2679-2687, 2450-2452`

- The section preamble (`:2683-2686`) reads "The first seven bullets below are genuinely pre-existing or
  latent … The eighth is a residual …". After AC5 deletes bullets 1, 2, 4, 5 and 6, that sentence describes
  a list that no longer exists. AC5 has no task to update it.
- AC5 Task 4 adds `## Last audit: 2026-09-21 (skillars-deferred-127 …)`. A `## Last audit: 2026-09-21
  (skillars-deferred-126 dev-story completion)` heading already exists, and is the target of a
  cross-reference at `:2450-2452` — a pointer whose own inline note records that it has already resolved to
  nothing once before. Two same-date audit headings invite a repeat. Either disambiguate the new heading or
  add a task to re-point the cross-reference.

### L4 — AC1 Task 8's residual claim holds, and the reason is worth recording

Verified rather than assumed, since the story asks for it: a stale `radar_composite_dlq` row for an erased
player is harmless post-fix. `deletePlayerDevelopmentData` deletes `radar_assessments` (`:203`), so the
retried `recalculateComposite` finds no aggregates, `bySkill` is empty and the per-skill loop is a no-op.
Critically, this depends on the `main.player_profiles` row **surviving** erasure — it does: nothing in
`erase()` deletes it, and the `AccountDeletionRequestedEvent` cascade
(`video/service/AccountDeletionCascadeListener.java`) only purges videos. If it did not survive,
`recalculateComposite`'s own `findByIdForUpdate(...).orElseThrow(...)` (`:191-192`) would throw on every DLQ
retry until `max_attempts`. Worth one clause in the closeout note so a future reader does not have to
re-derive it.

---

## Verified correct (do not re-litigate)

Checked against source and accurate as written:

- The resurrection interleaving in AC1 is a real race — on the PARENT path. `recalculateComposite` locks
  only `player_profiles` (`:191-193`) and reads aggregates under READ COMMITTED; nothing stops a concurrent
  delete of `player_radar_*` rows for that player.
- The opposite-table-order claim: `deletePlayerDevelopmentData` deletes baselines (`:201`) then composites
  (`:202`); `recalculateComposite` writes `upsertComposite` then `insertBaselineIfAbsent`. Genuinely
  inverted.
- `lock_timeout` cannot break a circular wait — correct, and already stated in
  `RadarCompositeCalculationService.java:113-120`.
- `PlayerProfileRepository.findByIdForUpdate` is NOWAIT (`@QueryHint … value = "0"`, `:44-47`), every call
  site wraps it in `PessimisticLockRetryer.withBoundedRetry`, and the ~3.2s / 8-attempt budget is accurate
  (`PessimisticLockRetryer.java:44-45,71-81`).
- The `EntityManager`-via-`@RequiredArgsConstructor` precedent is real and needs no `@PersistenceContext`
  (`RadarCompositeCalculationService.java:33-34,46-47`).
- `ResourceNotFoundException(String, String)` exists at
  `infrastructure/exception/ResourceNotFoundException.java:7-10` and is the constructor
  `recalculateComposite` uses.
- `erase()` is `@Transactional(propagation = REQUIRES_NEW)` (`:75`), and `GdprEventListener:34-41` does
  route a thrown exception to `markFailed` in a fresh transaction.
- `ConfigStartupAssertion.java:88` is exactly `if (ConfigBounds.HAS_CODE_DEFAULT.contains(bk.key()))`.
- `ConfigResource` `PUT /api/config/values/{key}` at `:75-81` is the only write path; there is no POST.
- `ConfigBounds.HAS_CODE_DEFAULT`'s `Set.of(...)` spans exactly `:300-317`.
- AC2's narrow scoping is sound: all non-`HAS_CODE_DEFAULT` bounded keys in `ConfigBounds.ALL` — including
  all 18 generated `video.quota.*` / `video.*` keys — are seeded, so widening the condition would change
  nothing.
- `invalidate()` is called at the end of `updateConfig` (`:209`) — AC2 Task 4's "confirm it runs on the
  create path" is the right check.
- AC3's counts: 44 `@Scheduled`, zero `spring.task.*` in `src/main/resources/application*.yaml` or
  `src/test/resources/application*.yaml`, `spring:` at `application.yaml:46` with `lifecycle`/`mvc`/
  `jackson` siblings, `VideoDeletionOutboxProcessor.MAX_RUN_DURATION = 12m` (`:85`).
- AC4's citations: `ShedLockConfig.java:65-69` (truncation), `:54` (`MAX_HOSTNAME_LENGTH = 255 - 37 = 218`),
  `:41-48` (the Javadoc to update). All exact.
- AC4 Task 2's code sketch is off-by-one-correct as written (see M2).
- Dev Notes' "no new Flyway migration is needed" — correct for all four ACs.
- Dev Notes' frontend claim — correct; nothing in the expected File List touches `src/frontend/**`.

---

## Recommended disposition

1. **B1** — rewrite AC1 Task 3 around correct id resolution plus an explicit absent-profile decision; add
   `player_profiles` seeding to `GdprErasureIT`. Consider splitting the "PLAYER-path erasure deletes nothing
   today" bug into its own AC — it is a live Article-17 gap, independent of the lock work.
2. **B2** — correct 18 → 17 and "all keys" → "4 unseeded keys" in the Priority line, AC2 Context, AC2
   Task 2, AC5 Task 2 bullet 4, the Change Log, and `sprint-status.yaml:2`. Fix the
   `HAS_CODE_DEFAULT` characterisation. The fix itself is unchanged.
3. **H1–H4** — rewrite AC1's Task 4 rationale and Context risk paragraph; extend Task 6's doc scope to
   `ConfigBounds`, the concurrency IT, and the `deleteStrike` accepted-risk bullet; take an owner decision
   on H3.
4. **M1–M6** — add the `ShedLockConfig` extraction task; soften AC4's severity claim; reframe AC5's
   `EXPLAIN` task; nail down AC1's and AC2's test fixtures and add a real-schema `ConfigResourceIT` case;
   add the pool-size note to AC3.
5. **L1–L4** — refresh citations, fix the Provenance arithmetic, add the two ledger-hygiene tasks.
6. Add to the File List: `ConfigBounds.java`, `RadarCompositeCalculationServiceConcurrencyIT.java`,
   `ConfigResourceIT.java`.
