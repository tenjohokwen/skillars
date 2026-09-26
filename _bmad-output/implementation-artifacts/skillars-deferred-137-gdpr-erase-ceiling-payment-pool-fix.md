# Story: GDPR Erasure Cumulative Lock-Wait Ceiling & Booking-Payment REQUIRES_NEW Connection-Pool Isolation

**Story Key:** `skillars-deferred-137-gdpr-erase-ceiling-payment-pool-fix`
**Epic:** Deferred Work
**Priority:** Medium (two real, currently-open hazards — one an owner-decided-but-revisitable aggregate
wait bound, one a resource-isolation gap with no prior owner decision at all — but deliberately a
**smaller story than this series' recent norm**; see Context below for why).
**Status:** ready-for-dev
**Created:** 2026-09-26

---

## Context

Master is at `c9a2691d` (`skillars-deferred-136`, PR #231, merged — GDPR datasource+retry hardening,
`PackSessionService` lock/TOCTOU fix, test hygiene, plus a same-branch CI-break fix to `TestConfig`'s
Hikari pool binding). Per this project's own standing convention, this story was sourced from a fresh
mining pass over `deferred-work.md` (4013+ lines) plus two full rounds of a live
`txn-and-concurrency-audit` sweep against modules never previously audited — both described in full
below, since **this story is deliberately smaller than stories 126–136 and the reason why is itself
part of its own record.**

**Why this story has only 2 ACs, not 5–8 like its recent predecessors:**

1. A full ledger-mining pass across every un-reread section of `deferred-work.md` (roughly 2,400 lines
   this story's own drafting session had not previously read) surfaced exactly two live leads — both
   investigated to a false-positive conclusion:
   - The `ses-1-4` `Map.of`→`HashMap` null-token guard, which a stale, superseded audit block
     (`deferred-work.md:109-119`, written earlier the same day as its own correction) still describes
     as open — the correction immediately below it (`:192-215`) confirms it was already fixed by
     `skillars-deferred-111` AC6 and deleted from tracking. Not a real candidate.
   - Three `deploy-1-5`/`deploy-1-3` "still open" rows in an older audit table
     (`deferred-work.md:364-365`) — a later, more authoritative audit
     (`deferred-work.md:1625-1638`) confirms all were closed by `skillars-deferred-102` AC6/AC7/AC9.
     Not real candidates.
   - `AdminVideoService.deleteVideo`'s `Def17` (release() exception inside the delete's
     `TransactionTemplate`) — re-read the live method (`AdminVideoService.java:45-90`): already fixed,
     the quota release is Phase 2, outside the `TransactionTemplate` entirely, per a `deferred-64 AC5`
     comment. Not a real candidate.
   - `UploadSessionExpiryScheduler`'s `Def22` (non-atomic release-then-mark-expired) — re-read the live
     code: this is the SAME already-decided design (`AC-5: QuotaProvider.release() OUTSIDE any
     @Transactional boundary`), not an unaddressed gap.
2. Two live rounds of `/txn-and-concurrency-audit`, run against every module this project's own prior
   audit series (stories 115, 116) had NOT yet covered, both came back **completely clean**:
   - Round 1 (booking + messaging): `BookingExpiryScheduler`, `BookingReminderScheduler`,
     `QuickCompleteTimeoutService`, `MessageModerationSweeper`, `MessageRetentionScheduler` — all
     already meticulously hardened by `skillars-deferred-118`, which shipped with zero deferred
     residue (hence no ledger trace, and this story's own initial assumption that these modules were
     "unaudited" was wrong — they were audited, just cleanly, by a story that generated nothing to
     defer).
   - Round 2 (security/admin, filestorage, development): `AuthCleanupService`, `UserAdminService`,
     `DeletionSchedulerService`, `OutboxPollerScheduler`, `NeglectedSkillDetectionService`,
     `SluSnapshotAppliedRetentionService` — all already hardened by `skillars-deferred-119`/`120`/`123`,
     same pattern: clean implementations, no ledger residue, discoverable only by reading the code
     directly rather than the ledger.
3. **Owner decision (`AskUserQuestion`, this story's own drafting session):** after 10 audited classes
   across 2 rounds returned zero findings, on top of the exhausted ledger-mining pass, the owner chose
   to ship this story at its current, smaller size rather than manufacture additional scope — an
   explicit, disclosed departure from this series' recent norm, not an oversight.

**The two ACs that did survive verification:**

1. **AC1 — `GdprErasureService.deletePlayerDevelopmentData`'s aggregate lock-wait has no
   method-level ceiling.** This is `deferred-work.md`'s own `D1` from the `skillars-deferred-129` code
   review (`:3259-3277`) — **owner-decided once already** ("the simpler per-statement bound over
   porting `RadarCompositeCalculationService`'s cumulative spend-down mechanism"), not a fresh finding.
   Re-surfaced here because story 136 built exactly the dedicated-pool infrastructure that makes the
   *other* half of this method's risk profile (Hazard 2, connection-acquisition wait) cheap — the
   aggregate lock-wait ceiling is the one piece of that same method's risk surface still genuinely
   open, and the ledger's own "revisit if" condition (a future story asked to bound it) is what this
   story is. **Owner re-confirmed via `AskUserQuestion`: fix it now**, porting
   `RadarCompositeCalculationService.recalculateComposite`'s own already-shipped cumulative
   spend-down mechanism, the same fix shape the ledger entry itself names.
2. **AC2 — `BookingPaymentPersistenceService` has three `REQUIRES_NEW` methods, each opening a
   second pooled connection under lock contention, with no pool-sizing analysis ever done.**
   Flagged twice in old reviews (2026-06-25 `D14`, 2026-08-24) and never fixed or dismissed — this is
   the identical class of problem story 136 just solved for `GdprErasureService`'s own `REQUIRES_NEW`
   acquisitions, in a module (payment) that carries real production request-thread traffic, not an
   admin-only path. **Owner decision: fix it now**, reusing story 136's own `RoutingDataSource`/
   `RoutingDataSourceContext` infrastructure — built explicitly "business-agnostic, reusable by any
   future module needing a second dedicated pool" (its own Javadoc) — rather than inventing a second
   mechanism.

**Considered and explicitly excluded** (verified closed/stale/already-decided during drafting — do not
re-surface without new information): the `ses-1-4` null-token guard, the three `deploy-1-5`/`deploy-1-3`
rows, `AdminVideoService.deleteVideo`'s `Def17`, `UploadSessionExpiryScheduler`'s `Def22`, and every
class touched by the two clean audit rounds (see above) — all re-verified against current `HEAD` during
this story's own drafting, not carried over from stale ledger text.

---

## AC1: Cumulative lock-wait ceiling for `GdprErasureService.deletePlayerDevelopmentData`

**Files:**
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
  (`deletePlayerDevelopmentDataInDedicatedPool`, `:1040-1112`; its own Javadoc, `:980-1024`)
- Extended `GdprErasureIT`/`GdprErasureServiceTest` coverage

### Current state (re-verified against `HEAD`, exact current line numbers — drifted materially from
this item's own original `deferred-129`-era citations, since `skillars-deferred-132`/`136` both
touched this method)

- `deletePlayerDevelopmentDataInDedicatedPool` (`:1040-1112`) issues exactly ONE
  `SELECT set_config('lock_timeout', ?1, true)` call (`:1049-1051`), immediately after acquiring the
  `player_profiles` pessimistic lock, with `lockTimeoutSeconds` a caller-supplied value bounded by
  `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` (`[2, 120]`, default `5`).
- Because Postgres `lock_timeout` is per-STATEMENT, not per-transaction, that single `set_config` call
  bounds each of the ~12 fixed delete/scan statements at `:1065-1080` (nine `deleteAllByPlayerId`/
  `deleteByPlayerId` calls, the `performance_reports` key scan + its own delete, the
  `homework_completions` delete) INDEPENDENTLY — plus one additional outbox `INSERT` per non-null
  `storage_key` the blob-enqueue at `:1086` issues (`M`, unbounded by anything in this method).
- **Real worst case: `(12 + M) ×` the configured seconds — not a method-level ceiling.** At the default
  `5s` that is ≥60s for one child; at the configured `max = 120s` it is ≥24 minutes, all held on the
  admin's own HTTP request thread (`GdprEventListener.onErasureRequested` is a plain, non-`@Async`
  `AFTER_COMMIT` listener), holding a dedicated-pool connection (post-136 AC1: bounded to that pool's
  own `connection-timeout`, not the primary pool's) and an exclusive lock on the already-anonymised
  `main."user"` row for the duration.
- `eraseParentChildren`'s own `gdprEraseLockBudget` field (`:149`, `Duration.ofSeconds(10)` default,
  `skillars-deferred-128` AC2) is sampled only BETWEEN children in the PARENT-erasure loop (`:637`) —
  it cannot interrupt a single child's own in-progress `deletePlayerDevelopmentData` call, and does not
  apply at all to the `role == PLAYER` direct call site in `eraseTransactional`.
- **This was owner-decided once already** (`skillars-deferred-129` story review, 2026-09-22,
  `AskUserQuestion`): the simpler per-statement bound was chosen deliberately over porting
  `RadarCompositeCalculationService.recalculateComposite`'s own cumulative spend-down mechanism, and
  the ledger entry itself documents the `N × seconds` arithmetic honestly rather than overclaiming a
  ceiling that does not exist. The "revisit if" condition named there — "the bound is ever raised
  above the default in production, or if erasure moves off the request thread" — has NOT actually
  fired; this story reopens it anyway per the owner's live decision during this story's own drafting,
  on the strength of the now-cheap-to-port precedent rather than a new production trigger. Disclosed
  explicitly, per this project's own "disclosed, not silent" scoping convention, since this does depart
  from the "only reopen `[DECIDED]` items on a new trigger" norm most of this ledger otherwise follows.

### The fix — port `RadarCompositeCalculationService.recalculateComposite`'s own already-shipped
mechanism (`RadarCompositeCalculationService.java:260-324`)

Read that method's cumulative spend-down loop in full before implementing — it is the exact shape to
mirror, not a template to reinvent:

- Introduce a `remainingLockBudget` (or equivalently-named) `Duration`, seeded from a new bounded
  config key (mirror `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own shape for the analogous
  `CUMULATIVE_LOCK_WAIT_BUDGET`-style constant there — decide during implementation whether this is a
  fixed constant like Radar's or itself config-bound; Radar's own is a fixed constant, so matching that
  is the lower-risk default unless a reason to diverge surfaces).
- Before each of the ~12 fixed statements (or, more coarsely, before each logical delete "step" if
  wrapping all 12 individually proves too invasive — decide the exact granularity during
  implementation, documenting the choice, since Radar's own precedent operates per-skill, a coarser
  grain than per-statement) and before each blob-enqueue `INSERT`, compute
  `thisStatementLockTimeoutSeconds = min(lockTimeoutSeconds, remainingLockBudget.toSeconds())`
  (Radar's own halves it across a statement PAIR — `deletePlayerDevelopmentData` has more than two
  statements per iteration if done coarsely, so the exact divisor is an implementation decision, not a
  copy-paste of Radar's `/2`).
- If the shrunk value would fall below the `2s` floor `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS`
  already enforces as its own `min`, bail out of the remaining statements for THIS child rather than
  issuing a near-zero, contention-indistinguishable `set_config` call — mirroring Radar's own
  `IllegalStateException` "cumulative budget exhausted" bail-out, adapted to this method's own existing
  `DeleteStatementLockTimeoutException`/`CannotAcquireLockException` catch shape (decide during
  implementation which exception type a mid-child bail-out should surface as, and confirm it reaches
  the same `CHILD_DELETE_LOCK_TIMEOUT` alert path the existing per-statement trip already does — a
  cumulative-budget bail-out is arguably a DIFFERENT condition from a single-statement trip and may
  warrant its own alert reason; decide and document).
- Re-issue the `set_config` call with the shrunk value before each guarded statement, then spend the
  budget down by ACTUAL elapsed time (not worst-case), exactly as Radar's own `Duration.between(...)`
  bookkeeping does, floored at zero.
- This method's own re-drivability (`deletePlayerDevelopmentData`'s Javadoc: "re-driving makes genuine
  forward progress with no data left unrecoverable") means a mid-child bail-out is safe to leave
  partially applied — confirm this still holds once some-but-not-all of the 12 statements have run
  before a bail-out (i.e. confirm there's no ordering dependency between the 12 deletes such that
  stopping after statement 7 but not 8 leaves an inconsistent intermediate state a re-drive can't
  recover from — read all 12 statements' own ordering rationale, if any exists in the method's history,
  before assuming this is a non-issue).

### Test plan

- New unit/IT coverage proving: (a) a child whose statements individually stay well within budget is
  unaffected (happy path, no behavior change); (b) a child that would exceed the cumulative budget
  bails out partway through rather than running to `(12 + M) ×` seconds unbounded, and does so via a
  distinguishable exception/alert reason; (c) the bailed-out child's own re-drive (via AC2's sibling
  retry scheduler from story 136, or a manual resubmit) completes cleanly on a subsequent attempt,
  confirming no unrecoverable partial state was left behind.
- Confirm existing `GdprErasureIT`/`GdprErasureServiceTest` coverage for the single-statement
  `lock_timeout` trip (`skillars-deferred-129`'s own IT) still passes unmodified — this AC must not
  change the single-statement-contention happy/failure path, only the cumulative, multi-statement one.

---

## AC2: Dedicated connection pool for `BookingPaymentPersistenceService`'s `REQUIRES_NEW` methods

**Files:**
- `src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java`
  (`reserveCapture` `:91-92`, `persistPaymentFailure` `:246-247`, `declineBatchBooking` `:326-327`)
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` (new named pool,
  reusing the existing `RoutingDataSource` bean — do NOT create a second `RoutingDataSource`; add a new
  entry to its existing `namedTargets` map)
- `src/test/java/com/softropic/skillars/config/TestConfig.java` (test-side equivalent)
- New/extended concurrency IT

### Current state (re-verified against `HEAD`, exact current line numbers)

- `reserveCapture` (`:91-92`, `@Transactional(propagation = Propagation.REQUIRES_NEW)`): the method's
  own Javadoc (`:76-85`) already documents that `REQUIRES_NEW` "runs on a *second pooled connection*"
  and explicitly warns future callers to verify no self-deadlock risk before adding a third call site —
  but never discusses whether the PRIMARY pool has enough headroom for this second-connection pattern
  under real concurrent-reservation load.
- `persistPaymentFailure` (`:246-247`) and `declineBatchBooking` (`:326-327`) are both also
  `REQUIRES_NEW` — this class now has **three** such methods, not the one originally flagged in the
  2026-06-25 ledger entry (`reserveCapture` alone) — the risk surface has grown since that item was
  last looked at, not shrunk.
- No pool-sizing analysis has ever been written for any of the three, despite two separate ledger
  entries (2026-06-25 `D14`, 2026-08-24) flagging exactly this gap and neither being fixed or formally
  declined.
- Unlike `GdprErasureService`'s `REQUIRES_NEW` acquisitions (admin-triggered, low-frequency), these
  three run on real booking/payment request-thread and batch-listener traffic — a burst of concurrent
  settle/reservation/decline attempts against contended booking rows is a more production-realistic
  resource-exhaustion vector than the GDPR case story 136 already closed.
- `DataSourceConfig.dataSource()` (`:48-53`) already builds exactly ONE `RoutingDataSource`, wrapping
  the primary pool plus a `namedTargets` map currently containing a single entry
  (`GDPR_ERASURE_DATASOURCE_KEY`) — this is the reusable extension point story 136's own Javadoc
  invites: *"both fully business-agnostic, reusable by any future module needing a second dedicated
  pool."*

### The fix — add a second named target to the EXISTING `RoutingDataSource`, do not build a new
routing mechanism

- Add a new `HikariConfig` bean (mirroring `gdprErasureHikariConfig`'s own shape:
  `@ConditionalOnProperty` matching the primary pool's condition, own `maximumPoolSize`/
  `connection-timeout`/`minimumIdle` sized for THIS pool's own narrow purpose, `auto-commit`/
  `connection-init-sql` read from the same `spring.datasource.hikari.*` keys to avoid drift, per
  story 136's own review-fixed pattern) and a new `DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY`
  constant.
- Size the pool from real reasoning, not a copy of GDPR's `max 3`/`10s` — these three methods run on
  live request-thread and batch-listener paths, plausibly with more concurrent callers than GDPR
  erasure's admin-only trigger; a `connection-timeout` shorter than the primary pool's 30s is still the
  right shape (fail fast rather than block the caller for up to 30s), but the `maximumPoolSize` should
  be justified against this class's own realistic concurrent-call volume (batch settle fan-out size,
  typical concurrent booking-accept rate) — document the arithmetic the way `DeletionSchedulerService`/
  `OutboxPollerScheduler`'s own sizing comments already do for this codebase's convention.
- Wrap `reserveCapture`, `persistPaymentFailure`, and `declineBatchBooking`'s own bodies with the same
  `RoutingDataSourceContext.set(...)`/`try`/`finally { RoutingDataSourceContext.clear(); }` pattern
  `GdprErasureService.erase()`/`deletePlayerDevelopmentData` already establish — do NOT set the routing
  key inside a `@Transactional` method's own first line if Spring's transactional advice would run
  BEFORE that line executes (re-verify `GdprErasureService`'s own placement rationale, `erase()`'s
  Javadoc `:224-227`, for why the key must be set before the proxy's transactional advice begins, not
  merely before the annotated method's first statement — this may require the same self-invocation
  split `PackSessionService.pausePack` needed in story 136, or it may not, depending on how these three
  methods are currently invoked; investigate before assuming a drop-in wrap is safe).
- Test-side equivalent in `TestConfig.java`: extend the EXISTING `dataSource()` bean's `Map.of(...)` to
  a second entry rather than building a parallel mechanism — this is exactly the shape 136's own
  `RoutingDataSource` was designed to make cheap for a second module.

### Test plan

- New concurrency/IT coverage proving the three methods now route to the dedicated pool (mirroring
  `GdprErasureDataSourceRoutingIT`'s own `HikariPoolMXBean` active-connection delta proof), and that
  exhausting ONLY that dedicated pool (not the primary) makes them fail fast at the dedicated pool's own
  shorter timeout.
- Confirm existing `CaptureReservationIT`/`BatchPaymentIT`/`PaymentWebhookIdempotencyIT` (or whichever
  currently exercise these three methods — identify the actual current test classes during
  implementation, these names are illustrative from this story's own drafting-session file list, not a
  verified-current inventory) still pass unmodified — this AC must not change any of the three methods'
  observable transactional behavior, only which pool their `REQUIRES_NEW` connection comes from.

---

## Dev Notes

- **This story is intentionally smaller than stories 126–136** — see Context above for the full
  drafting-session record of what was checked and ruled out. Do not treat the smaller size as a signal
  to pad scope during implementation; if a genuinely new finding surfaces while implementing AC1 or
  AC2, follow this project's own "disclosed, not silent" convention and raise it, but do not go looking
  for additional unrelated work to fill the story out further.
- **AC1's exact statement-grouping granularity (per-statement vs. per-logical-group) is left for
  implementation to decide**, informed by `RadarCompositeCalculationService`'s own precedent but not
  required to copy its per-skill grain exactly — `deletePlayerDevelopmentData`'s 12 statements don't
  have Radar's natural "per-skill" grouping, so the implementer should pick the grain that keeps the
  fix legible without over-engineering a budget check between every single `deleteAllByPlayerId` call.
- **AC2's pool-sizing arithmetic is the implementer's own to derive** — this story deliberately does
  not pre-specify `maximumPoolSize`/`connection-timeout` numbers, the way `deferred-136` AC1 also left
  the GDPR pool's exact sizing for implementation, sized from the actual code paths' own realistic
  concurrency rather than a guessed constant.
- **This story's citations were verified against `master@c9a2691d`** (post `skillars-deferred-136`/PR
  #231). Re-diff every cited line against whatever `master` actually looks like by the time
  implementation starts, per this project's own standing "diff cited lines to ensure they're still
  accurate" convention.

---

## Tasks

- [ ] 1. **AC1:** Read `RadarCompositeCalculationService.recalculateComposite`'s cumulative spend-down
   mechanism in full (`:260-324`). Design the equivalent for `deletePlayerDevelopmentData` (statement
   grouping granularity, bail-out exception/alert-reason shape, config key or fixed constant for the
   cumulative budget). Implement and add new tests per AC1's test plan.
- [ ] 2. **AC2:** Add a new `HikariConfig` bean + `DataSourceConfig` named-target key, sized from real
   reasoning about this class's own concurrent-call volume. Wrap the three `REQUIRES_NEW` methods with
   the routing-key set/clear pattern, investigating the self-invocation-split question before assuming
   a drop-in wrap is safe. Test-side `TestConfig.java` equivalent. New tests per AC2's test plan.
- [ ] 3. Full targeted-suite regression run for every touched class/package (`GdprErasureService` and
   its existing IT/unit suites, `BookingPaymentPersistenceService` and whichever ITs currently exercise
   `reserveCapture`/`persistPaymentFailure`/`declineBatchBooking`) — no local `mvn verify` per standing
   convention.
- [ ] 4. Ledger closeout: annotate the `skillars-deferred-129` D1 bullet (`deferred-work.md:3259-3277`)
   `[CLOSED by skillars-deferred-137 AC1 — <summary>]`; annotate both 2026-06-25/2026-08-24
   `BookingPaymentPersistenceService` pool-pressure bullets `[CLOSED by skillars-deferred-137 AC2 —
   <summary>]`. Add `last_updated`/`development_status` entries to `sprint-status.yaml`.

---

## Dev Agent Record

### Completion Notes

(Not yet implemented.)

### File List

(Not yet implemented.)

### Change Log

- 2026-09-26: Story created via `/bmad-create-story`. Sourced from an exhaustive ledger-mining pass
  (all previously-unread sections of `deferred-work.md`) plus two full rounds of a live
  `/txn-and-concurrency-audit` sweep against 10 classes across 6 modules never covered by this
  project's own prior audit series — both came back clean, and the ledger-mining pass's own leads were
  all confirmed false positives against current `HEAD`. Two live, verified candidates survived: AC1 (a
  once-already-declined GDPR aggregate-lock-wait ceiling, reopened by owner decision now that story
  136's own dedicated-pool work makes the adjacent fix cheap) and AC2 (a never-addressed
  `BookingPaymentPersistenceService` connection-pool-pressure gap, to be closed by reusing story 136's
  own `RoutingDataSource` infrastructure). Owner decision (`AskUserQuestion`): ship this story at its
  current, smaller-than-usual 2-AC size rather than manufacture additional scope, given how
  thoroughly this codebase has already been hardened by 137 prior stories. Status: ready-for-dev.

## Story Completion Status

Not yet implemented. Status: ready-for-dev.
