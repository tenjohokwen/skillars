# Story: GDPR/Radar Lock Serialization, Unseeded Config-Key Upsert & Scheduler Pool Fixes

**Story Key:** `skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes`
**Epic:** Deferred Work
**Priority:** High (a real GDPR-erasure/radar-recalculation race that can resurrect an erased player's
development data and a genuine lock-ordering deadlock hazard between the same two paths — plus a
pre-existing Article-17 gap surfaced during story review, that the PLAYER-role erasure path has never
actually deleted a player's development data at all, due to an id-space mismatch; a real config-key
write-path gap affecting 4 unseeded `HAS_CODE_DEFAULT` keys; a real single-thread scheduler-starvation
gap affecting all 44 `@Scheduled` methods) — plus the standard ledger closeout.
**Status:** done
**Created:** 2026-09-21
**Reviewed:** 2026-09-21 (`story-review.md`, senior-dev pre-implementation audit). 2 blocking + 4 high +
6 medium + 4 low/accuracy findings, all independently re-verified against actual source before applying
— zero false positives (one minor factual slip *within* the review itself, on a secondary example in
M5, noted and corrected below; it does not affect that finding's substance). See the Change Log for the
full response.

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md`
(`## Deferred from: code review of skillars-deferred-126-...` — surfaced by `/bmad-code-review`
across four parallel layers when skillars-deferred-126 was reviewed, same day this story was created),
per this project's established "read same-day code-review deferrals before drawing scope" convention.

That section lists eight bullets. The eighth (`next_retry_at` eligibility staying on the app clock) is
already `[DECIDED: accepted risk — skillars-deferred-126]` from a prior owner decision and is **not**
touched here. Of the remaining seven: AC1 closes **bullets 1 and 2** (one AC, two bullets — the
resurrection race and its sibling deadlock hazard share one root cause and one fix); AC2 closes bullet
4; AC3 closes bullet 5; AC4 closes bullet 6; AC5 performs and closes bullet 7 (the never-run `EXPLAIN`
confirmation). That is **six of the seven** bullets closed by this story, leaving exactly **one**
genuinely open — bullet 3 (`now()` vs `clock_timestamp()` fragility, see "Explicitly left open" below).
A project-owner decision (`AskUserQuestion`, taken live during this story's creation) was taken on each
of the four ACs' core mechanisms, plus a fifth decision confirming the story stays scoped to this
same-day deferral rather than reaching further into the ledger. (Corrected post-review — story-review.md
L2 — from an earlier, arithmetically-wrong draft of this paragraph that said "closes four … leaves three
open"; the per-bullet accounting in AC5 Task 2 was always the operative, correct source of truth.)

All seven bullets were independently re-verified against `master@24fe4a80` (skillars-deferred-126's own
merge, PR #214) at story-creation time — line citations below reflect that HEAD, not the original
deferral note's (now-superseded) line numbers, which drifted slightly as skillars-deferred-126's own
code-review fixes landed. Two corrections made during this verification were themselves wrong and are
corrected again here post-review (story-review.md B2, independently re-verified against
`ConfigBounds.java` and `V139__baseline_seed_data.sql`): `ConfigBounds.HAS_CODE_DEFAULT` holds **17**
keys, not 18 (a miscount during story creation), and of those 17, **13 are already seeded** in
`V139__baseline_seed_data.sql` and already writable today — the real scope of AC2's gap is **4**
unseeded keys: `platform.moderation_sla_batch_size`, `platform.development.radar_composite_dlq.max_attempts`,
`security.rate_limiting.bucket_ttl_hours`, and `platform.radar_composite_lock_timeout_seconds` (three of
which are exactly the three the original ledger bullet named — this story's "widened it to more keys"
framing was itself the error, not the ledger bullet's narrower one). The root cause of the miscount:
`HAS_CODE_DEFAULT`'s own Javadoc defines the set by **call-site tolerance of absence** (a 2-arg
`getLong`/`getInt` code default), not by "no Flyway seed" — that framing was an imprecise inheritance
from the ledger bullet's own title, not a re-derivation from the actual Javadoc. AC2's *fix* (upsert on
write, scoped to `HAS_CODE_DEFAULT`) and its scoping rationale are unaffected by this correction — see
AC2 for the corrected count and scope.

### Explicitly left open (not in this story's scope — owner decision, AskUserQuestion)

- **`now()` vs `clock_timestamp()` fragility** in `claimPendingBatch`/`resetStaleClaimed` (both
  repositories) — correct today only because neither call runs inside an outer `@Transactional`.
  Latent, not a live bug; left as a documented ledger risk rather than a code change in this story,
  since AC1–AC4 below already give this story a full 4-AC + AC5-closeout bundle matching this
  project's established sizing convention.
- **`ShedLockConfig`'s hostname-truncation `String.substring` can split a surrogate pair** — this one
  bullet from the deferral **is** folded into AC4 below alongside the truncation-length citation
  refresh (it lives in the exact file/method AC4 already touches and is a two-line, no-decision fix;
  bundling it there avoids leaving an easy, already-diagnosed correctness fix on the table while a
  four-line method sits open in the diff anyway).
- **skillars-deferred-126 AC1 Task 3's `EXPLAIN`/index-coverage confirmation was never performed or
  recorded** — folded into AC5's ledger-closeout tasks below (a verification/recording task, not a
  code change) rather than given its own AC.
- Reaching further into `deferred-work.md` for an unrelated item beyond this same-day deferral —
  considered and explicitly declined (project-owner decision, `AskUserQuestion`, live during story
  creation): this story's 4 ACs from the freshest deferral already match every prior story's bundle
  size in this series.

### Two further owner decisions taken live post-review (`AskUserQuestion`, story-review.md B1 and H3)

`story-review.md` (senior-dev pre-implementation audit) surfaced two genuine decision points this
story's own creation missed — both taken live with the user before revising the ACs below:

- **B1 (blocking):** AC1's originally-drafted PLAYER-role id resolution was wrong (see AC1 Context for
  the full technical finding) — **decided:** fix the id resolution as part of AC1 itself (not a separate
  AC — the correct fix touches the identical lines), and `orElse`-skip (not `orElseThrow`) when a
  PLAYER-role account has no `player_profiles` row, rather than failing the whole erasure.
- **H3:** AC1's shared lock introduces a new GDPR-erasure failure mode (a genuinely-contended
  `player_profiles` row can now make `erase()` fail fast, routed to `markFailed`, which creates no
  `AdminAlert` and is never auto-re-driven) — **decided:** document as an explicit
  `[DECIDED: accepted risk — skillars-deferred-127]` ledger note (AC5) rather than building new alerting
  machinery in this story; `markFailed`'s lack of alerting is pre-existing, project-wide (not introduced
  by AC1), and out of this story's scope to fix comprehensively.

---

## AC1 — Serialize GDPR erasure against `recalculateComposite` via the shared `player_profiles` lock

### Context

`RadarCompositeCalculationService.recalculateComposite`
(`src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:167-280`)
takes a pessimistic lock on the player's `player_profiles` row before reading radar aggregates and
upserting composites/baselines — see its own extensive Javadoc at `:97-165`, which **already documents
this exact gap** as a "concrete conflict source" (added by skillars-deferred-126's own code review,
2026-09-21):

> `GdprErasureService.erase` (its own `Propagation.REQUIRES_NEW` transaction) calls
> `deletePlayerDevelopmentData`, which deletes `player_radar_baselines` then `player_radar_composites`
> for the same player — **without** taking this method's own `player_profiles` pessimistic lock at
> all, and in the **opposite** table order this method writes them in (composites then baselines).

Two distinct failure modes follow from this:

1. **Resurrection (data-integrity bug).** Interleaving: instance A (`recalculateComposite`) locks
   `player_profiles(P)` and reads aggregates under READ COMMITTED → instance B (`GdprErasureService`)
   deletes baselines, composites and assessments for P and commits → A's per-skill loop re-inserts
   composites and baselines from its pre-erasure snapshot. An Article-17-erased player has live radar
   rows again and nothing will ever remove them (the erasure request is already `COMPLETED`).
2. **Lock-ordering deadlock (Postgres `40P01`).** The two paths write/delete `player_radar_composites`
   and `player_radar_baselines` in opposite order with no shared upstream lock — a classic
   lock-ordering deadlock shape. `recalculateComposite`'s own `lock_timeout`
   (skillars-deferred-126 AC2) does **not** address this: `lock_timeout` only makes an ordinary
   *waiter* abort itself, it cannot break a circular wait — that is Postgres's own
   `deadlock_timeout`-driven detector's job, already firing independent of anything either story adds.
   Losing that deadlock as the victim discards `GdprErasureService.erase`'s `main.user` anonymisation,
   message/review deletions and blob-deletion outbox rows in the SAME `REQUIRES_NEW` transaction, and
   routes the request to `markFailed` — a GDPR erasure fails because a background radar recalculation
   happened to be running.

**Owner decision (taken live, `AskUserQuestion`, during this story's creation): close both with a
single mechanism — have `GdprErasureService`'s player-development-data deletion take the SAME
`player_profiles` pessimistic lock `recalculateComposite` already uses, via the SAME
`PlayerProfileRepository.findByIdForUpdate` + `PessimisticLockRetryer.withBoundedRetry` pattern.** This
fully serializes the two paths — once one holds the `player_profiles` lock, the other cannot even begin
touching `player_radar_composites`/`player_radar_baselines`, so they can never race on those two
tables' row locks at all, closing the deadlock hazard as a structural consequence, not just the
resurrection bug directly named.

**Why this is lower-risk than it may first look — re-verify this claim before implementing, don't
assume it:** `PlayerProfileRepository.findByIdForUpdate`
(`src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java:44-47`) is
**NOWAIT** (`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`), not an
indefinite wait — every call site (this story's new one included) wraps it in
`PessimisticLockRetryer.withBoundedRetry(...)`, which retries a `PessimisticLockingFailureException`
with jittered backoff (default 8 attempts, ~3.2s worst-case total, holding the caller's pooled JDBC
connection for that window — see the class's own "Cost model" Javadoc,
`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:44-56`)
before giving up and letting `PessimisticLockingFailureException` propagate.

**Correction 1 (post-review, story-review.md B1, blocking): the two `deletePlayerDevelopmentData` call
sites pass structurally different identifiers, and the fix above must not use `findByIdForUpdate`
naively.**

```java
// GdprErasureService.java:126-131
if (role == SkillarsRole.PLAYER) {
    deletePlayerDevelopmentData(userId, blobKeysToDelete);          // ← main."user".id
} else if (role == SkillarsRole.PARENT) {
    playerProfileRepository.findByParentId(userId)
        .forEach(pp -> deletePlayerDevelopmentData(pp.getId(), blobKeysToDelete));  // ← main.player_profiles.id
}
```

`PlayerProfile extends BaseEntity`, whose `id` is `@Id @Tsid` (`BaseEntity.java:26-28`) — an
independently generated TSID, unrelated to `main.user.id`. A player profile's link to its owning
account is the separate `user_id` column (`PlayerProfile.java:41-43`, reachable via
`PlayerProfileRepository.findByUserId`, `:24`), enforced by `V138__baseline_schema.sql:911`'s
`chk_pp_owner CHECK ((parent_id IS NOT NULL AND user_id IS NULL) OR (parent_id IS NULL AND user_id IS
NOT NULL))`. On the PARENT branch, `pp.getId()` is already the correct `player_profiles.id` — Task 3's
`findByIdForUpdate(playerId)` pattern works as originally drafted there. On the PLAYER branch,
`userId` is **not** a `player_profiles.id` at all — `findByIdForUpdate` would find nothing for it
(a TSID essentially never equals a `main.user.id`), throwing `ResourceNotFoundException`, unretried
(`PessimisticLockRetryer.withBoundedRetry` explicitly does not absorb a non-lock exception —
"Any other exception — including a genuine not-found — propagates immediately, unretried",
`PessimisticLockRetryer.java:118-124`), failing **every** PLAYER-role erasure.

**This surfaces a pre-existing, independent Article-17 bug, not introduced by this story:** because
`player_profiles.id` (TSID) essentially never equals `main.user.id`, the PLAYER branch's existing
`deleteAllByPlayerId`/`deleteByPlayerId` calls **already delete nothing today** — a self-registered
adult player's development data has never actually been erased by this path. AC1's own motivating
resurrection scenario is therefore currently reachable only through the PARENT branch; the PLAYER branch
has a different, worse defect (silent no-op, not a race).

**Owner decision (taken live, `AskUserQuestion`, post-review): fix the id resolution as part of this
AC (not a separate AC — the fix touches the identical lines the lock work already touches), resolving
via `playerProfileRepository.findByUserId(userId)` on the PLAYER branch instead of treating `userId` as
a `player_profiles.id`. When no profile row exists for the account (a PLAYER user who never built one),
skip player-development-data deletion for that account — `orElse`, not `orElseThrow` — rather than
failing the whole erasure; a missing profile is a legitimate "nothing to erase" case on a GDPR path, not
an error.** See Tasks below for exactly how this reshapes Task 3/4.

**Correction 2 (post-review, story-review.md H1): the per-player lock is not "locked/unlocked
independently."** `SELECT … FOR UPDATE` row locks release only at transaction end. `erase()` is a
single `@Transactional(propagation = REQUIRES_NEW)` method (`:75`), and the PARENT-branch loop runs
entirely inside it — locking inside `deletePlayerDevelopmentData` does **not** release each player's
lock per iteration; every player's lock accumulates and is held until `erase()` commits. A parent with
N children therefore holds N `player_profiles` `FOR UPDATE` locks simultaneously, and the worst-case
added latency from lock contention is **N × ~3.2s**, not a flat ~3.2s — the per-acquisition budget
above is per player, not per erasure. (The per-player *acquisition* granularity itself is still the
right call — it keeps each individual acquisition bounded — only the "locked/unlocked independently"
framing and the flat ~3.2s budget claim were wrong.)

**Correction 3 (post-review, story-review.md H2): FK-induced lock conflicts, not just contention with
`recalculateComposite`.** `FOR UPDATE` on a `player_profiles` row also conflicts with the
`FOR KEY SHARE` lock Postgres takes on that row for every referential-integrity check from a child
insert. Seven tables `REFERENCES main.player_profiles(id)`:
`development.coach_radar_preferences`, `development.player_radar_baselines`,
`development.player_radar_composites`, `development.player_slu_weekly_snapshot_applied`,
`main.parent_player_links`, `payment.player_subscriptions`, `payment.player_subscription_changes`
(`V138__baseline_schema.sql:4084-4105,4230-4231,4496-4504`). Because the lock is now held for the
**remainder of the erase transaction** (which continues through performance-report iteration, blob-key
collection, refresh-token revocation, GDPR-request updates and event publication, `:134-182`), any
concurrent insert into those seven tables for that player will block on it. `recalculateComposite`
already had this exposure but holds the lock for a much shorter, bounded window; the erasure path's
window is comparatively long. This is a recorded consequence of the fix, not a reason to change the
mechanism.

**Correction 4 (post-review, story-review.md H3): this AC introduces a new GDPR-erasure failure mode.**
`recalculateComposite` can hold the `player_profiles` lock for up to
`CUMULATIVE_LOCK_WAIT_BUDGET = Duration.ofSeconds(ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.max())`
= **120 seconds** (`RadarCompositeCalculationService.java:66-67`; `max` is `120L`,
`ConfigBounds.java:256`), while the erasure's own retry budget is only ~3.2s. So under genuine
contention, **a GDPR erasure that previously succeeded (no lock check at all) now fails fast** —
intended behavior, but `GdprErasureService.markFailed` (pre-existing code, not added by this story)
only `log.error`s; it creates no `AdminAlert` and nothing auto-re-drives a `FAILED` request. The user
*can* manually re-submit (the transaction rolled back, so they were never anonymised or locked out,
and `GdprRequestService.requestErasure` only blocks on `PENDING`/`PROCESSING`), but the failure itself
is silent to admins. This gap in `markFailed` is pre-existing and applies to every erasure-failure
cause today, not just this new one — **owner decision (taken live, `AskUserQuestion`, post-review):
document as an explicit `[DECIDED: accepted risk — skillars-deferred-127]` ledger note (AC5) rather
than building new `AdminAlert` machinery in this story**, which would be scope beyond this AC's stated
fix (alerting across all `markFailed` causes is a separate, larger concern for a future story).

### Tasks

1. Re-verify this AC's line citations against current HEAD before implementing — this story's own
   citations above were checked against `master@24fe4a80`; confirm nothing has drifted since.
2. In `GdprErasureService`
   (`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`), add two
   new `private final` fields — `PessimisticLockRetryer lockRetryer` and `EntityManager entityManager`
   (constructor injection via the class's existing `@RequiredArgsConstructor`, mirroring
   `RadarCompositeCalculationService`'s own identical field pair at `:46-47` — `EntityManager` needs no
   `@PersistenceContext` annotation for constructor injection, confirmed by that exact precedent).
3. Resolve the correct `player_profiles` row **per branch**, not inside `deletePlayerDevelopmentData`
   itself (which only ever receives a `player_profiles.id` once resolved correctly) — the simplest
   shape is to change `deletePlayerDevelopmentData`'s own lookup to accept the already-resolved
   `PlayerProfile`/id it can lock directly, resolved differently by each caller:
   - **PARENT branch** (`erase()`, currently `playerProfileRepository.findByParentId(userId).forEach(pp
     -> deletePlayerDevelopmentData(pp.getId(), ...))`) — `pp.getId()` is already correct; lock it via
     `findByIdForUpdate` inside `deletePlayerDevelopmentData` as originally planned.
   - **PLAYER branch** (`erase()`, currently `deletePlayerDevelopmentData(userId, ...)`) — resolve via
     `playerProfileRepository.findByUserId(userId)` **first**, and only call
     `deletePlayerDevelopmentData` (with the resolved profile's own id, then locked via
     `findByIdForUpdate`) if a row is present; if absent, skip the call entirely — this is the
     `orElse`-skip decision above, and it is also the fix for the pre-existing PLAYER-path no-op bug
     (development data will now actually be deleted for self-registered players who have a profile).
   - Inside `deletePlayerDevelopmentData` itself, once given a genuine `player_profiles.id`:
     ```java
     var playerProfile = lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
         .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
     entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);
     ```
     — the exact pattern `recalculateComposite` uses at `:191-193`, including the follow-up
     `entityManager.refresh(..., PESSIMISTIC_WRITE)` call; do not drop that second call without first
     understanding why `recalculateComposite` needs it (re-read its surrounding code/comments — if no
     in-repo rationale is found, keep it for consistency rather than guessing it is redundant).
     `orElseThrow` here is now safe/correct — by this point the caller has already confirmed the
     profile exists (PARENT: came from `findByParentId`; PLAYER: came from the new `findByUserId`
     check), so a genuine not-found here means the row vanished between resolution and lock
     acquisition, which is a real error, not the normal "no profile" case Task 3's PLAYER-branch
     `orElse`-skip already handles one level up.
4. Confirm the resulting lock-acquisition shape: each player's lock is acquired once, inside
   `deletePlayerDevelopmentData`, and — per Correction 2 above — accumulates with every other player's
   lock already acquired earlier in the same `erase()` call until the whole `REQUIRES_NEW` transaction
   commits (a PARENT with N children holds N locks simultaneously; do not describe this as
   "locked/unlocked independently" anywhere in code comments or Javadoc).
5. Import `com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer`,
   `jakarta.persistence.EntityManager`, `jakarta.persistence.LockModeType`, and
   `com.softropic.skillars.infrastructure.exception.ResourceNotFoundException` (or this project's
   equivalent — confirm the actual exception class `recalculateComposite` uses is accessible from this
   package; use the identical one, do not invent a new not-found exception type for this call site).
6. **Update documentation in all four places this gap is described, not just one** (post-review,
   story-review.md H4 — the original task named only the first):
   1. `RadarCompositeCalculationService.recalculateComposite`'s own Javadoc (`:97-165`) — it currently
      documents this exact gap as a live, unfixed "concrete conflict source" in present tense
      ("**without** taking this method's own `player_profiles` pessimistic lock at all"). Once this AC
      ships, that sentence is false. Correct it to describe the NOW-shared lock and cross-reference
      `GdprErasureService.deletePlayerDevelopmentData`'s own new lock acquisition, preserving the rest
      of the paragraph's still-accurate content (the lock-ordering discussion, the `lock_timeout` vs
      `deadlock_timeout` distinction, the exception-class findings) — do not delete the paragraph
      wholesale.
   2. `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own Javadoc (`:238-249`) cross-references
      the same "opposite order" fact in its `min` rationale — update it to match.
   3. `RadarCompositeCalculationServiceConcurrencyIT`'s deadlock test (`:217,267`) — its Javadoc and
      inline comment both frame Thread B as "mirrors `GdprErasureService.deletePlayerDevelopmentData`'s
      own order"; the test itself stays valid (it drives both sides with raw SQL, independent of this
      AC's production-code change) but its framing becomes historical — note that explicitly rather
      than leaving it silently stale.
   4. `deferred-work.md:2450-2460` — the `AdminCoachEnforcementService.deleteStrike`
      `[DECIDED: accepted risk — skillars-deferred-123]` bullet justifies remaining accepted by
      contrasting itself against the (pre-this-story) radar case: "the radar case's competing
      transaction … has genuinely unbounded work ahead of it — no lock-retry budget bounds it —
      whereas here the winning transaction is itself already lock-retry-bounded." This AC makes
      `deletePlayerDevelopmentData` lock-retry-bounded by exactly that mechanism, collapsing the stated
      distinction — this bullet's rationale needs rewriting (or re-deciding) as part of AC5, not left
      to silently describe a comparison that no longer holds. AC5 Task 2 already covers this file; make
      it an explicit task there rather than relying on the general grep-sweep's "re-confirm each hit is
      either unrelated or already correctly annotated" wording, which invites marking this one
      "unrelated" by mistake.
7. **Do not also reorder `deletePlayerDevelopmentData`'s deletes** (composite-then-baseline to match
   `recalculateComposite`'s write order) as a belt-and-suspenders addition — the project-owner
   decision taken live was the shared lock ALONE; once serialized upstream, the two paths can never
   race on `player_radar_composites`/`player_radar_baselines` locks concurrently at all, making a
   matching write order redundant defense-in-depth the owner explicitly did not ask for. Keep the
   diff to what was decided.
8. Note as a residual, not a new task: `radar_composite_dlq` rows for an erased player are not cleared
   by `deletePlayerDevelopmentData` and will still process after this fix ships. This is **not** a
   data-integrity issue post-fix — `deletePlayerDevelopmentData` also deletes the player's
   `radar_assessments` (`:203`), so a stale DLQ row's `recalculateComposite` re-run finds no aggregates
   to derive a composite from (`bySkill` is empty, the per-skill loop is a no-op) — but it is wasted
   work. This depends on the `main.player_profiles` row itself **surviving** erasure, which it does
   (nothing in `erase()` deletes it, and `AccountDeletionCascadeListener` only purges videos) — record
   that dependency explicitly in the closeout note (story-review.md L4), not just the conclusion, so a
   future reader does not have to re-derive it. Record this as a low-value cleanup gap in AC5's ledger
   closeout rather than fixing it here (out of this AC's scope as decided).

### Tests

**Post-review correction (story-review.md M4): the tests below cannot be built the way the original
draft described — three concrete problems, each addressed in the revised plan.**

1. `GdprErasureIT` drives everything over HTTP, and `erase()` runs inside
   `GdprEventListener.onErasureRequested`'s `AFTER_COMMIT` listener, which **swallows every exception**
   (`:36-41`) — the HTTP call still returns 202 regardless of what `erase()` does internally. A test
   asserting `erase()` fails fast with `PessimisticLockingFailureException` must invoke
   `GdprErasureService.erase` **directly**, not through the HTTP endpoint. `GdprErasureIT` has no
   `@Autowired GdprErasureService` today — add one, and seed an `admin.gdpr_requests` row for the direct
   call path (the HTTP flow currently creates this row itself; a direct-call test must create it too).
2. No `main.player_profiles` fixture exists anywhere in `GdprErasureIT.setUp()` or `secData.sql` today
   (confirmed: zero hits). `RadarCompositeCalculationServiceConcurrencyIT.setUp()` (`:47-56`) is the
   precedent to copy — it seeds one explicitly.
3. **The fixture shape matters, or the test can pass while hiding the real bug.** If the fixture seeds
   a `player_profiles` row whose `id` happens to equal the erased user's `id`, the test would go green
   under the *originally-drafted* (pre-B1-fix) code too — production, where `player_profiles.id` is a
   TSID unrelated to `user.id`, would stay broken. The `player_profiles` row seeded for these tests
   **must** have an id distinct from the user id (a TSID-shaped value, not a hand-picked numeric
   constant that happens to differ only by convention), linked via `user_id`/`parent_id` — mirroring
   `RadarCompositeCalculationServiceConcurrencyIT`'s own fixture, which already does this correctly.

With that fixture shape in place:

- New `GdprErasureIT` test(s) (alongside the existing `erase_playerUser_...` tests) proving the failure
  modes this AC closes are genuinely closed, not just that the new lock call compiles:
  - A concurrency test mirroring `RadarCompositeCalculationServiceConcurrencyIT`'s own pattern
    (Testcontainers Postgres, real threads, full latch control — not mocks): start `erase()` (called
    directly, per point 1 above) for a player with a `player_profiles` row lock already held by a
    concurrent `recalculateComposite`-style caller; assert `erase()` either waits and then succeeds once
    the lock releases, or fails fast within the ~3.2s `PessimisticLockRetryer` budget with
    `PessimisticLockingFailureException` — pick whichever is actually observed (do not assume; this
    exact empirical-confirmation discipline is what skillars-deferred-126 AC2's own tests already
    established as this project's convention for concurrency claims).
  - A regression test proving the **resurrection** scenario is closed: seed radar aggregates for a
    player (with the correctly-shaped `player_profiles` fixture above), hold the `player_profiles` lock
    from a `recalculateComposite`-shaped caller, trigger `erase()` concurrently, release the lock, and
    assert the player's `player_radar_composites`/`player_radar_baselines` rows are genuinely gone after
    both complete (not resurrected) — this is the test that would have failed against the PRE-fix code
    and must be written to actually reach that pre-fix failure if run against `master@24fe4a80`
    (verified logically against the current code as part of drafting this AC — do not just assert the
    post-fix behavior without confirming it would have failed before).
  - A regression test for the **PLAYER-path id-resolution fix itself**, independent of concurrency:
    a single-threaded erasure of a PLAYER-role account with a properly-linked `player_profiles` row
    (TSID-shaped id, `user_id` set) actually deletes that player's development data — this is the test
    that proves the pre-existing no-op bug (B1's secondary finding) is closed, and it would have failed
    against `master@24fe4a80` for a reason different from every other test in this file (silent no-op,
    not an exception).
  - A regression test for the PLAYER-role **no-profile-row** case: erasure of a PLAYER-role account with
    no `player_profiles` row at all completes successfully (200/202, `COMPLETED` status) rather than
    failing — proving the `orElse`-skip decision.
- Existing `GdprErasureIT.erase_playerUser_...` tests must stay green — the new lock acquisition and id
  resolution should not change their observable behavior (they never asserted anything about
  `player_radar_*` rows, which the PLAYER path was silently no-op'ing on already).

---

## AC2 — `ConfigService.updateConfig`: upsert for unseeded `HAS_CODE_DEFAULT` keys

### Context

`ConfigService.updateConfig`
(`src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:202-211`) does:

```java
public ConfigValueResponse updateConfig(String key, String newValue) {
    PlatformConfig entity = configRepository.findByKey(key)
            .orElseThrow(() -> new ResourceNotFoundException("ConfigEntry", key));
    ...
}
```

Any key with no `platform_config` row 404s through `PUT /api/config/values/{key}`
(`src/main/java/com/softropic/skillars/platform/config/api/ConfigResource.java:75-80`, the only write
path — there is no create/POST endpoint).

**Correction (post-review, story-review.md B2, blocking — the original draft of this section was wrong
on both the count and the framing):** `ConfigBounds.HAS_CODE_DEFAULT`
(`src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java:300-317`) holds **17**
keys, not 18 (mechanically re-counted: the `Set.of(...)` entries occupy lines 301–317). Its own Javadoc
(`:293-299`) defines the set by **call-site tolerance of absence** — "keys whose call site passes a code
default (2-arg `getLong`/`getInt`) … an absent or blank value is survivable there" — not by "no Flyway
seed migration"; that framing was an imprecise inheritance from the ledger bullet's own title, not a
re-derivation from the actual Javadoc, and it led to an over-broad count. Checking each of the 17 keys'
literal against `V139__baseline_seed_data.sql` shows **13 are already seeded** and already writable
today. The real, re-verified scope of this gap is **4 unseeded keys**:
`platform.moderation_sla_batch_size`, `platform.development.radar_composite_dlq.max_attempts`,
`security.rate_limiting.bucket_ttl_hours`, and `platform.radar_composite_lock_timeout_seconds` — three
of which are exactly the three the original ledger bullet named (this story's "widened it to 18"
framing was itself the error, not the ledger's narrower one). This still directly contradicts each of
those four keys' own Javadoc, which documents "an operator can widen this at runtime" as its `max`-bound
rationale (see e.g. `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own Javadoc, `:238-249` — a
skillars-deferred-126 addition that inherits this narrower, but still real, gap on day one). **What
survives unchanged from the original draft:** the *fix* (upsert on write, scoped to
`HAS_CODE_DEFAULT`) and its scoping rationale — independently confirmed: every non-`HAS_CODE_DEFAULT`
bounded key in `ConfigBounds.ALL`, including all generated `video.quota.*`/`video.*` keys, is seeded, so
narrowing the upsert condition to `HAS_CODE_DEFAULT` leaves no other bounded key unwritable.

**Owner decision (taken live, `AskUserQuestion`): upsert on write, scoped to
`ConfigBounds.HAS_CODE_DEFAULT` keys only** — `failFast` (non-`HAS_CODE_DEFAULT`) bounded keys must
already be seeded or `ConfigStartupAssertion` would have refused to boot
(`src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java:88` checks
`ConfigBounds.HAS_CODE_DEFAULT.contains(bk.key())` for exactly this reason), so widening the upsert
condition to those keys would never actually change observed behavior for them — but the decision was
to keep the code's condition itself narrow and explicit (matching the exact set of keys this gap
affects) rather than accepting silent-but-harmless overreach.

### Tasks

1. Re-verify `ConfigService.updateConfig`'s current line range and `ConfigBounds.HAS_CODE_DEFAULT`'s
   current key list against HEAD before implementing.
2. Change `updateConfig` so that when `configRepository.findByKey(key)` returns empty **and**
   `ConfigBounds.HAS_CODE_DEFAULT.contains(key)`, it creates a new `PlatformConfig` row instead of
   throwing `ResourceNotFoundException` — populate **all** of the entity's non-nullable columns,
   including `key` itself (`nullable = false`, `PlatformConfig.java:28-29` — omitted from an earlier
   draft of this task list, post-review story-review.md M6), `value` from the request (still passed
   through `rejectOutOfRange` first, unchanged), `valueType = ConfigValueType.LONG` (every
   `HAS_CODE_DEFAULT` key is a `BoundedKey` — already machine-guarded by
   `ConfigBoundsEnumCoverageTest:121`'s `assertThat(boundedKeys()).containsAll(ConfigBounds.HAS_CODE_DEFAULT)`,
   so this does not need a fresh manual re-check), `description` — decide during implementation whether
   to leave `null` or populate from the matching `BoundedKey`'s own Javadoc/rationale field if one is
   programmatically accessible (check `BoundedKey`'s record/class fields before deciding; do not invent
   a field that does not exist), `updatedAt = Instant.now()`. When the key is absent and NOT in
   `HAS_CODE_DEFAULT` (a `failFast` key, or simply not a recognized key at all), preserve the current
   404 behavior exactly — do not widen this to arbitrary unknown keys.
3. `PlatformConfigRepository`
   (`src/main/java/com/softropic/skillars/platform/config/repo/PlatformConfigRepository.java`) already
   has `save` via `JpaRepository` — no new repository method needed. The actual uniqueness guarantee a
   racing double-create attempt fails against is the database constraint `uq_platform_config_key UNIQUE
   (key)` (`V138__baseline_schema.sql:2674-2678`) — **not** JPA's `@Column(unique = true)`
   (`PlatformConfig.java:26-27`), which is inert under Flyway-managed DDL (`spring.jpa.generate-ddl` was
   removed project-wide per `deferred-work.md:2454`; post-review correction, story-review.md M6, of a
   citation that named the wrong mechanism). Decide whether that race (two concurrent first-writes for
   the same never-before-seeded key) needs explicit handling in this AC or is an acceptable, extremely
   rare edge left unhandled (this project's own convention — see how `rejectOutOfRange`'s sibling paths
   handle similarly rare races — should guide this; do not add new locking machinery unless the
   existing convention already does so for comparable cases). **This AC introduces the first
   application-side `INSERT` into `main.platform_config`** — every existing write is `save()` on an
   already-loaded managed entity. The table's `id` is `bigint NOT NULL` with `GENERATED BY DEFAULT AS
   IDENTITY` (`V138__baseline_schema.sql:867-876`), while the entity inherits `@Id @Tsid` from
   `BaseEntity` — an explicit-id insert against a `GENERATED BY DEFAULT` (not `GENERATED ALWAYS`)
   identity column is legal, and `V139`'s own header comment (`:39-43`) shows the sequence was
   deliberately bumped to 606 in anticipation of exactly this scenario, but it has never actually
   executed — this path needs a real-schema test, not a mocked one (see Tests below).
4. `invalidate()` (cache invalidation, already called at the end of the existing method) must still run
   on the new create-path too, not just the existing update-path — trace the method's control flow to
   confirm this rather than assuming.

### Tests

**Post-review correction (story-review.md M6): a mocked-repository unit test cannot prove the riskiest
part of this change — the first real `INSERT` into `main.platform_config`.**

- `ConfigServiceTest` (`src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java`,
  alongside the existing `updateConfig_*` tests at `:296-338`) is a Mockito unit test with a **mocked**
  `PlatformConfigRepository` — every existing `updateConfig_*` test asserts via
  `verify(configRepository).save(...)`. A `ConfigServiceTest`-level assertion can prove the *code path*
  taken (that `save(...)` is called with a newly-constructed entity carrying the right fields) but
  **cannot** demonstrate "the value is readable afterward" — stubbing `findAll()`/`findByKey()` to
  return the row you just told the mock to save proves nothing about the real insert. Scope
  `ConfigServiceTest`'s new test(s) to the path-and-fields assertion only:
  `updateConfig_unseededHasCodeDefaultKey_createsRow` (verifies a `HAS_CODE_DEFAULT` key with no
  existing row calls `save(...)` with a correctly-populated new entity, not 404) and
  `updateConfig_unseededNonHasCodeDefaultKey_stays404` (verifies the existing 404 behavior is unchanged
  for a key that is neither present nor in `HAS_CODE_DEFAULT`, so this AC does not silently widen the
  write surface beyond its stated scope).
- **Add a real-schema case to `ConfigResourceIT`**
  (`src/test/java/com/softropic/skillars/platform/config/api/ConfigResourceIT.java`) — it already has
  the natural home for this: `putAsAdmin_updatesValue_subsequentGetReturnsNewValue` (`:213-231`) already
  does PUT-then-GET against a real Testcontainers Postgres, and no existing test asserts 404-on-unknown-
  key, so nothing breaks by adding one. New test: `putAsAdmin_unseededHasCodeDefaultKey_createsRowAndGetReturnsIt`
  — PUT one of the four genuinely-unseeded keys (e.g. `platform.radar_composite_lock_timeout_seconds`),
  assert 200 (not 404), then GET and assert the value round-trips — this is the test that actually
  exercises the explicit-id insert against the `GENERATED BY DEFAULT AS IDENTITY` column and would have
  failed pre-fix.
- Confirm `ConfigBoundsEnumCoverageTest`
  (`src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java`)
  stays green unmodified — it asserts structural properties of `ConfigBounds` itself, not
  `ConfigService`, and this AC does not touch `ConfigBounds`.

---

## AC3 — Enable a real thread pool for `@Scheduled` tasks (single-thread starvation)

### Context

`SchedulingConfig`
(`src/main/java/com/softropic/skillars/infrastructure/config/SchedulingConfig.java`) has
`@EnableScheduling` (conditional on `app.scheduling.enabled`, default `true`) and nothing else — no
`TaskScheduler` bean, no `spring.task.scheduling.pool.size` property anywhere in
`src/main/resources/application*.yaml` (re-verified: grepped for `task:`/`scheduling:` under `spring:`
at HEAD, found nothing). Spring Boot's `TaskSchedulingAutoConfiguration` therefore backs
`@EnableScheduling` with a single-thread `ThreadPoolTaskScheduler` by default. This project has **44**
`@Scheduled`-annotated methods (re-counted at HEAD via `grep -rn "@Scheduled" src/main/java | wc -l`,
across every `platform.*` module) sharing that one thread. A single long-running job —
`VideoDeletionOutboxProcessor.process()`'s documented 12-minute `MAX_RUN_DURATION` — occupies it
entirely, during which none of the other 43 fire, including the two ShedLock-timing-sensitive
processors (`VideoDeletionOutboxProcessor` itself and `RadarCompositeDlqProcessor`) whose
`lockAtMostFor`/`STALE_CLAIM_WINDOW` arithmetic (skillars-deferred-126 AC1) implicitly assumes their
own scheduled cadence is not starved by an unrelated job holding the only thread.

**Owner decision (taken live, `AskUserQuestion`): fix it — `spring.task.scheduling.pool.size: 8`**, a
plain static `application.yaml` property (not a new `ConfigBounds`/`platform_config` key — a thread
pool's size cannot be resized at runtime without recreating the scheduler bean, so a
runtime-tunable config value would not actually be actionable without a restart anyway, making the
extra machinery pointless here).

**Post-review addition (story-review.md M5): this is not risk-free beyond a grep for stale comments —
two consequences worth sizing and recording, not blocking, since the fix itself is still correct.**

- **Connection pool.** `spring.datasource.hikari.maximum-pool-size: 25` (`application.yaml:106`, sized
  "up to 4 nodes, i.e. 25 for each"). Going from 1 to 8 concurrently-running scheduled jobs adds up to 7
  simultaneous holders. `PessimisticLockRetryer`'s own Javadoc is explicit that a contended caller
  **holds its pooled JDBC connection while sleeping** for up to ~3.2s and closes with "Watch the timer's
  p99 against the HikariCP pool size" (`PessimisticLockRetryer.java:40-56`). Several scheduled jobs
  already go through that retryer — confirmed by grep: `PaymentPendingSweeper`, `BookingBatchService`,
  and, after AC1, the GDPR erasure path. Note this in the AC's own risk statement, and recommend
  watching the `persistence.lock_retry` p99 metric post-deploy.
- **Jobs without `@SchedulerLock`.** 44 `@Scheduled` methods vs 26 real `@SchedulerLock` annotations
  (re-verified: a naive `grep -c "@SchedulerLock"` over-counts at 58 by matching Javadoc/comment
  mentions too; the actual annotation usages — `grep -rn "^\s*@SchedulerLock("` — are 26) — so ~18
  scheduled methods have no distributed lock. Single-threading was incidentally serialising them
  *against each other* in-process; 8 threads removes that (Spring still won't run the same
  `fixedDelay` task concurrently with itself, so self-overlap is not a new risk — cross-job overlap
  between two *different* unlocked jobs is). Task 5's grep sweep looks for *comments* asserting
  single-threaded execution; it will not surface a job that merely happens to share rows with another
  without saying so — note this limitation rather than treating the grep sweep as exhaustive.
- One line worth adding to the AC's own scope statement: `spring.task.scheduling.pool.size` governs
  Spring's `@Scheduled` scheduler only — this application also carries Quartz configuration
  (`application.yaml:~30-44`, `main.qrtz_*` tables in `V138`), which this change does not touch. Not a
  correction of anything currently claimed, just worth stating so a reader closing "single-thread
  scheduler starvation" does not assume broader coverage than this AC actually provides.

### Tasks

1. Re-verify no `spring.task.scheduling.*` property already exists anywhere in
   `src/main/resources/application*.yaml` or `src/test/resources/application*.yaml` before adding one
   (confirm this story's own citation above still holds at implementation time).
2. Add a `spring.task:` block to `src/main/resources/application.yaml` (new top-level child under the
   existing `spring:` key at `:46`, alongside `lifecycle`/`mvc`/`jackson`) —
   ```yaml
   task:
     scheduling:
       pool:
         size: 8
   ```
3. Confirm `src/test/resources/application-test.yaml`'s `scheduling: enabled: false`
   (the key spans `:126-127` — `scheduling:` at 126, `enabled: false` at 127, per `SchedulingConfig`'s
   own class Javadoc) is unaffected by this change — the concrete mechanism to cite (story-review.md
   M5): Spring Boot's `taskScheduler` autoconfigured bean is conditional on `@EnableScheduling`'s own
   post-processor being registered, which `SchedulingConfig`'s `@ConditionalOnProperty` (`:42`) removes
   entirely in the test profile — when scheduling is disabled, no `TaskScheduler` is even engaged, so
   the new pool-size property is inert there. This is a read-and-confirm task, not a code change to the
   test profile.
4. Do **not** attempt to make the pool size itself configurable via `ConfigBounds`/`platform_config`
   (see the owner decision's own rationale above) — keep this a one-line static property change.
5. Grep-sweep `src/main/java` for any existing assumption of single-threaded scheduled execution (e.g.
   a comment or a field relying on scheduled methods never running concurrently with each other) that
   this change could newly violate — `SchedulingConfig`'s own class Javadoc is the most likely place to
   start; if none is found, note that explicitly in the Dev Agent Record rather than silently skipping
   the check.

### Tests

- No new automated test is expected to meaningfully prove "the scheduler now has 8 threads" in this
  project's existing IT infrastructure (per `SchedulingConfig`'s own Javadoc, the test suite
  deliberately runs with scheduling disabled or consolidated to avoid exactly this kind of
  cross-job interference) — confirm this during implementation rather than inventing a flaky
  multi-thread-timing IT. If a lightweight unit-level assertion is feasible (e.g. asserting the
  `ThreadPoolTaskScheduler` bean's configured pool size via `ApplicationContext` in a narrow,
  non-scheduling-suite test), add it; otherwise, document why not in the Dev Agent Record.

---

## AC4 — `ShedLockConfig`'s hostname truncation: avoid splitting a UTF-16 surrogate pair

### Context

`ShedLockConfig.lockProvider`
(`src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java:65-69`, added by
skillars-deferred-126 AC3):

```java
String hostname = Utils.getHostname();
String truncatedHostname = hostname.length() > MAX_HOSTNAME_LENGTH
    ? hostname.substring(0, MAX_HOSTNAME_LENGTH)
    : hostname;
String lockedByValue = truncatedHostname + "-" + UUID.randomUUID();
```

`String.substring` truncates at a UTF-16 code-unit boundary. A hostname longer than
`MAX_HOSTNAME_LENGTH` (218, `:54`) whose 218th/219th code units happen to form one surrogate pair (one
Unicode code point outside the Basic Multilingual Plane — a container started with `docker run -h`
accepts arbitrary UTF-8, so this is reachable, if very unlikely) leaves a lone high surrogate, which is
not valid UTF-16. Recorded (not fixed) by skillars-deferred-126 as "very low likelihood"; this story
closes it since it lives in the exact method/file AC-adjacent work already touches and the fix is two
lines with no decision required.

**Post-review correction (story-review.md M2): soften the severity claim — the fix is still worth
shipping, but the originally-drafted consequence does not hold.** A lone high surrogate does **not**
"cannot be UTF-8-encoded" and does not necessarily break lock acquisition for every job. Java's
standard UTF-8 encoder (`String.getBytes(UTF_8)` / the default `CharsetEncoder` REPLACE action)
substitutes `?` for an unpaired surrogate — it does not throw and does not emit invalid UTF-8. In either
outcome, the `"-" + UUID.randomUUID()` suffix (`:69`) is untouched, so `locked_by` stays unique per JVM
and the unlock predicate (`name = :name AND locked_by = :lockedBy`) still works — the realistic
consequence is a cosmetically-mangled hostname prefix in an operational column, not a broken lock
mechanism. Keep the fix (it is two lines, correct, and cheap) but do not carry "breaks every
`@SchedulerLock` job" into the updated Javadoc (Task 3 below) unless the optional round-trip test in
Tests actually demonstrates a real failure, not just a substitution.

### Tasks

1. Re-verify the current line numbers for this method against HEAD before implementing (cited above as
   `:65-69`; confirm no drift since skillars-deferred-126's merge).
2. Back the truncation index off by one when it would land inside a surrogate pair, e.g.:
   ```java
   int truncateAt = MAX_HOSTNAME_LENGTH;
   if (truncateAt < hostname.length()
       && Character.isHighSurrogate(hostname.charAt(truncateAt - 1))
       && Character.isLowSurrogate(hostname.charAt(truncateAt))) {
       truncateAt--;
   }
   String truncatedHostname = hostname.length() > MAX_HOSTNAME_LENGTH
       ? hostname.substring(0, truncateAt)
       : hostname;
   ```
   Independently re-verified (story-review.md M2): this sketch is off-by-one-correct as written — for
   `length ≥ 219`, `charAt(217)`/`charAt(218)` are exactly the boundary pair `substring(0,218)` would
   split, and the `truncateAt < hostname.length()` guard holds. Still verify it against the real test
   case below before relying on this note alone.
3. **Extract the truncation into a testable, package-private static method** (post-review addition,
   story-review.md M1 — there is no existing seam to test this through otherwise; see Tests below for
   why). Mirror the existing package-private-for-testing precedent already established at `:51-54` for
   `MAX_HOSTNAME_LENGTH` itself — e.g. a `static String truncateHostname(String hostname)` method the
   `@Bean` body calls, callable directly from `ShedLockConfigIT` (same package). This is a production
   code change the original task list did not include; without it, AC4's own Tests cannot be delivered
   as specified.
4. Update the method's own inline comment (`:41-48`, the `MAX_LOCKED_BY_LENGTH`/`MAX_HOSTNAME_LENGTH`
   Javadoc) to note the surrogate-pair-safety fix, since it currently only discusses the raw length
   bound, not code-point safety — per the M2 correction above, describe the actual consequence
   (cosmetic truncation-boundary mangling) rather than "breaks every `@SchedulerLock` job" unless the
   optional round-trip test in Tests demonstrates otherwise.
5. **Keep `ShedLockConfigIT`'s existing hostname-truncation assertion (`:93-95`) in sync.** It currently
   re-implements the truncation inline (`hostname.substring(0, ShedLockConfig.MAX_HOSTNAME_LENGTH)`) to
   build its expected value — once the extracted method (Task 3) gains the surrogate-pair branch, that
   inline re-implementation would silently diverge from the real rule for any hostname that happens to
   trigger the branch. Change the existing assertion to call the extracted method directly instead of
   re-implementing the logic, not just add a new test alongside the stale one.

### Tests

**Post-review correction (story-review.md M1): there is no existing seam to test the original way this
AC described.** All four existing tests in `ShedLockConfigIT` use the machine's real hostname
(`Utils.getHostname()` called directly to build assertions, e.g. `:88`); the truncation logic is inlined
in the `@Bean` method body with no extracted, callable unit, and `Utils.getHostname()` is a third-party
static method. Task 3 above extracts a package-private static method specifically so the test below is
possible — write it against that extracted method, not by trying to control `Utils.getHostname()`'s
return value (no such seam exists; `mockStatic` precedent does exist elsewhere in this repo —
`JwtManagerImplTest`, `RateLimitingAspectIT` — but extraction is simpler here and matches the existing
`MAX_HOSTNAME_LENGTH` precedent).

- New test method in the **existing** `ShedLockConfigIT` file
  (`src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java` — this file
  already exists, 187 lines; an earlier draft of this section mislabeled it as a new file) calling the
  extracted truncation method directly with a constructed hostname whose 218th/219th UTF-16 units form a
  surrogate pair (e.g. an emoji or other supplementary-plane character straddling that boundary),
  asserting the result contains no lone surrogate and is a valid UTF-8-encodable string.
- If feasible, extend that test (or add a sibling) to prove it actually round-trips through a real
  `main.shedlock` INSERT/UPDATE without a `22001`/encoding error — but per the M2 correction above, do
  not assume this failure mode is reachable at all; write the assertion to observe whatever actually
  happens rather than asserting a specific failure.
- Update the pre-existing `:93-95` assertion per Task 5 above so it calls the extracted method rather
  than re-implementing the truncation rule inline.

---

## AC5 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story genuinely
fixes; annotate `[DECIDED: accepted risk — skillars-deferred-127]` what it deliberately does not fix;
leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-126-...` in `deferred-work.md`:
   - **Bullet 1** (GDPR erasure / recalculateComposite not serialized, resurrection risk) — delete
     outright, fully fixed by AC1.
   - **Bullet 2** (GDPR erasure can be the deadlock victim) — delete outright, fully fixed by AC1 (as a
     structural consequence of the shared lock, not a separate mechanism).
   - **Bullet 3** (`now()` vs `clock_timestamp()` fragility) — leave open (this story's own Provenance
     section already explains why); do not delete or annotate `[DECIDED]` — it remains an
     un-actioned, latent risk, not a decision.
   - **Bullet 4** (unseeded `HAS_CODE_DEFAULT` config keys) — delete outright if AC2 ships as scoped
     (upsert for `HAS_CODE_DEFAULT` keys); if implementation finds the mechanism impractical for some
     reason discovered mid-implementation, annotate `[DECIDED: accepted risk — skillars-deferred-127]`
     instead and record why. **Correct the count to "4 unseeded keys" (post-review, story-review.md
     B2)** — this story's own earlier draft claimed "18 keys" and instructed writing that wrong number
     into the ledger; the actual gap is 4 keys (`platform.moderation_sla_batch_size`,
     `platform.development.radar_composite_dlq.max_attempts`, `security.rate_limiting.bucket_ttl_hours`,
     `platform.radar_composite_lock_timeout_seconds`), three of which match the original bullet's own
     count — see AC2 Context for the full re-verification.
   - **Bullet 5** (single-thread `@Scheduled` starvation) — delete outright, fully fixed by AC3.
   - **Bullet 6** (`ShedLockConfig` hostname truncation surrogate-pair split) — delete outright, fully
     fixed by AC4.
   - **Bullet 7** (skillars-deferred-126 AC1 Task 3's `EXPLAIN` confirmation never performed) —
     **reframed (post-review, story-review.md M3): this is not an open discovery question, the answer
     already exists.** `V144__outbox_dlq_claimed_at.sql:30-36`'s own "Index coverage" comment already
     records that `resetStaleClaimed`'s predicate (`claimed_at IS NULL OR claimed_at <
     now() - make_interval(secs => :staleWindowSeconds)` — the full predicate, including the
     `claimed_at IS NULL OR` disjunct an earlier draft of this bullet omitted when citing it) is **not**
     covered by the existing `idx_radar_composite_dlq_status_retry`/`idx_vdoutbox_status_retry` indexes
     past their `status` prefix, and that this was **already accepted as-is** ("both tables are expected
     to be small enough … add a matching partial index only if row-count evidence at a future date says
     otherwise"). The task is therefore: record V144's own existing accepted-as-is note in this
     closeout (not re-derive it from scratch), and confirm empirically (a throwaway Testcontainers
     session or `psql` against a local compose stack — running `EXPLAIN` needs a live Postgres, which
     sits awkwardly against this project's "no local `mvn verify`" convention, so say explicitly how it
     was run) that the note still describes the current predicate shape. This is a five-minute
     confirm-and-cite task, not a "new finding" branch — do not frame it as one.
   - Bullet 8 (`next_retry_at` app-clock eligibility) — already `[DECIDED]`, leave untouched.
3. Add the AC1 Task 8 residual (`radar_composite_dlq` rows for an erased player not cleared, but
   provably harmless post-fix since `radar_assessments` is also deleted) as a new, explicitly
   `[DECIDED: accepted risk — skillars-deferred-127]`-tagged bullet — low-value cleanup, not a
   data-integrity gap, recorded so a future reader does not have to re-derive why it is safe. Include
   the dependency the safety argument actually rests on (story-review.md L4): the `main.player_profiles`
   row itself must survive erasure — it does (nothing in `erase()` deletes it, and
   `AccountDeletionCascadeListener`'s cascade only purges videos) — because if it did not,
   `recalculateComposite`'s own `findByIdForUpdate(...).orElseThrow(...)` would throw on every DLQ
   retry until `max_attempts` instead of silently no-opping.
3a. **Add the H3 accepted-risk note (post-review, story-review.md H3, owner decision taken live):** a
   new `[DECIDED: accepted risk — skillars-deferred-127]`-tagged bullet recording that AC1's shared lock
   makes a GDPR erasure more likely to hit `GdprErasureService.markFailed`'s pre-existing lack of
   `AdminAlert`/auto-retry on failure — not a new gap this story introduces, but a new path into an
   existing one, previously untracked anywhere in this ledger. Cross-reference for a future story that
   wants to add alerting across all `markFailed` causes, not just this one.
3b. **Update the four documentation locations Task 6 of AC1 identifies** (post-review, story-review.md
   H4) as explicit, separate tasks here — not folded into the general grep-sweep below, whose
   "re-confirm each hit is either unrelated or already correctly annotated" wording invites marking the
   `deleteStrike` cross-reference "unrelated" by mistake: (i) `RadarCompositeCalculationService`'s own
   Javadoc, (ii) `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s Javadoc, (iii)
   `RadarCompositeCalculationServiceConcurrencyIT`'s deadlock-test Javadoc/comment (framing only, test
   logic unchanged), (iv) the `AdminCoachEnforcementService.deleteStrike`
   `[DECIDED: accepted risk — skillars-deferred-123]` bullet at `deferred-work.md:2450-2460`, whose
   "the radar case has no lock-retry budget, this one does" distinction AC1 collapses — rewrite or
   re-decide its rationale rather than leaving it to silently describe a comparison that no longer
   holds.
4. Add a `## Last audit: 2026-09-21 (skillars-deferred-127 story creation)` — update to
   `... dev-story completion` when implementation finishes — narrative section, in this file's
   established style, summarizing what closed and what (if anything) was accepted-and-documented
   instead. **Disambiguate from the existing same-date heading** (post-review, story-review.md L3): a
   `## Last audit: 2026-09-21 (skillars-deferred-126 dev-story completion)` heading already exists at
   this file's current HEAD, and is itself the target of a cross-reference (`:2450-2452`) whose own
   inline note records that pointer already resolved to nothing once before, after an earlier edit
   deleted the section it originally targeted. Keep this story's own heading's parenthetical
   story-number-qualified exactly as drafted (`skillars-deferred-127 …`, never bare `2026-09-21`) so the
   two same-date headings stay distinguishable, and re-point `:2450-2452`'s cross-reference if Task 3b
   (iv)'s rewrite of that bullet changes which heading it should target.
4a. **Update the section preamble** (post-review, story-review.md L3): `deferred-work.md:2683-2686`
   currently reads "The first seven bullets below are genuinely pre-existing or latent … The eighth is a
   residual …" — after this task list deletes bullets 1, 2, 4, 5 and 6, that sentence describes a list
   that no longer exists (only bullets 3 and 8 remain, one open one `[DECIDED]`). Rewrite or remove the
   preamble sentence as part of this closeout, not left stale.
5. Grep-sweep every file this story touches (`GdprErasureService`, `RadarCompositeCalculationService`,
   `ConfigService`, `SchedulingConfig`, `ShedLockConfig`) against the rest of the ledger for any other
   stale reference this story's changes might affect — re-confirm each hit is either unrelated or
   already correctly annotated, per this series' own standard practice. (Task 3b above already covers
   the `deleteStrike` bullet explicitly — this sweep is for anything else, not a substitute for it.)

---

## Dev Notes

- **Module boundaries respected:** AC1 touches `platform.admin` (GdprErasureService) and
  `platform.development` (RadarCompositeCalculationService's Javadoc only, no behavior change there);
  AC2 touches `platform.config`; AC3 touches `infrastructure.config`/`application.yaml`; AC4 touches
  `infrastructure.config`. No cross-module leakage beyond what each AC's own fix requires.
- **No new Flyway migration is needed for any AC.** AC1 reuses an existing repository method and lock
  pattern; AC2 creates `platform_config` rows programmatically (that is the entire point — avoiding a
  migration is why the upsert fix was chosen over "seed the 4 unseeded keys"); AC3/AC4 are pure
  code/config changes with no schema impact.
- **Testing convention (per this project's `docs/validation-strategy.md`, already followed by every
  prior story in this series):** targeted tests only for the affected classes — no `mvn verify` run
  locally. Frontend is untouched by this story; no `frontend-tests` PR label is needed this time
  (confirm at PR-creation time that the actual final diff has no `src/frontend/**` changes before
  omitting the label — do not assume from this Dev Note alone).
- **Re-verify every file/line citation in this story against HEAD immediately before implementing each
  AC** — this project's own established process (see this story's own Provenance section) requires it.
  This story's own citations needed correcting **twice**: once during creation (`ShedLockConfig`'s
  truncation lines shifting `63-64` → `65-69` since the original deferral note was written), and again
  during its own pre-implementation review (`story-review.md`) — a genuine "17 keys, 4 unseeded" count
  where the creation draft said "18 keys, all unseeded" (B2), several line-range citations that had
  already drifted a few lines by the time of review (`recalculateComposite` `167-278` → `167-280`,
  `deletePlayerDevelopmentData` `194-209` → `194-213`, `updateConfig` `202-209` → `202-211`, and others
  — cheap to fix given Task 1 of every AC already mandates re-verification, but worth naming so the
  pattern itself — draft, then independently re-verify before implementing — is not treated as optional
  next time either).

---

## File List (reconciled against the actual final diff)

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
  (AC1 — Javadoc only)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (AC1 Task 6.ii —
  Javadoc only; added post-review, story-review.md H4)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` (AC2)
- `src/main/resources/application.yaml` (AC3)
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java` (AC4)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC1 tests)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java`
  (AC1 Task 6.iii — Javadoc/comment only; added post-review, story-review.md H4)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java` (AC2 tests)
- `src/test/java/com/softropic/skillars/platform/config/api/ConfigResourceIT.java` (AC2 tests — added
  post-review, story-review.md M6)
- `src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java` (AC4 tests — file
  already exists; an earlier draft of this list implied it was new)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review at dev-story completion)

**Added by the `/bmad-code-review` response (2026-09-22) — see `## Review Findings` above:**

- `src/main/resources/db/migration/V151__player_profiles_development_data_erased_at.sql` (new —
  Decision 1, the tombstone column)
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfile.java`
  (`developmentDataErasedAt` field — Decision 1)
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java`
  (`findByParentId` → `findByParentIdOrderByIdAsc` — Patch 3)
- `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java`
  (call-site rename only — Patch 3)
- `src/main/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdown.java`
  (shutdown-budget arithmetic/Javadoc updated — Decision 3)
- `docker-compose.yml` (`stop_grace_period` 55s → 60s — Decision 3)
- `src/test/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdownConfigurationTest.java`
  (new guard test — Decision 3)

---

## Dev Agent Record

### Completion Notes

All 5 ACs implemented and verified. Each AC's own re-verification task (re-read current HEAD before
implementing) was performed; citations matched HEAD exactly except where noted below.

- **AC1 (GDPR/radar lock serialization + PLAYER-branch id fix):** `GdprErasureService` gained
  `PessimisticLockRetryer lockRetryer` and `EntityManager entityManager` fields.
  `deletePlayerDevelopmentData` now takes the same `player_profiles` pessimistic lock
  `RadarCompositeCalculationService.recalculateComposite` takes (`findByIdForUpdate` +
  `lockRetryer.withBoundedRetry` + `entityManager.refresh(..., PESSIMISTIC_WRITE)`, byte-for-byte the
  same pattern) before deleting anything. `erase()`'s PLAYER branch now resolves the profile via
  `playerProfileRepository.findByUserId(userId)` and only calls `deletePlayerDevelopmentData` when a
  row is present (`ifPresent`, not `orElseThrow`) — closing both AC1's motivating resurrection/deadlock
  race and the pre-existing PLAYER-path no-op bug B1 surfaced. Documentation updated in all four
  locations Task 6 named: `RadarCompositeCalculationService`'s own Javadoc (now describes the shared
  lock, not a live gap), `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s Javadoc (notes the
  GDPR conflict is now closed upstream but the bound's general purpose is unchanged),
  `RadarCompositeCalculationServiceConcurrencyIT`'s deadlock test (framing marked historical, test
  logic unchanged), and the `AdminCoachEnforcementService.deleteStrike` accepted-risk bullet in
  `deferred-work.md` (re-decided: the asymmetry its cross-reference relied on has collapsed, remains
  accepted for reasons that no longer depend on it).
  Four new `GdprErasureIT` tests: a single-threaded regression proving the PLAYER-path no-op bug is
  closed, a no-profile-row `orElse`-skip regression, a lock-contention wait-then-succeed concurrency
  test (empirically: waits and succeeds, does not fail fast — locker holds ~1.2s, well inside the
  retryer's ~3.2s budget), and a resurrection non-regression test using a latch-controlled
  `recalculateComposite`-shaped raw-JDBC writer (mirroring the existing deadlock test's own technique,
  since production `recalculateComposite` has no injection point to synchronize on directly) — the
  writer holds the shared lock across a deliberate pause representing "already read, about to write"
  data, proving `erase()` cannot interleave its delete in between. Four existing tests
  (`erase_playerUser_deletesPerformanceReportFromS3` and its three outbox siblings) were moved from
  the no-profile `PLAYER_ID`/`PLAYER_EMAIL` fixture to the new profile-linked
  `SELF_PLAYER_PROFILE_ID`/`SELF_PLAYER_EMAIL` fixture — their existing shape (seeding
  `performance_reports.player_id = PLAYER_ID`, a raw `main.user.id`) only ever passed because the
  pre-fix code took the same shortcut; with the fix, deletion for a profile-less account is correctly
  skipped, so these tests needed a real profile to keep testing what they were written to test.
- **AC2 (config-key upsert):** `ConfigService.updateConfig` now looks up the key first (unchanged
  order, to keep the two existing range-validation tests' `findByKey` stubs meaningful), then, if
  absent, either 404s (not in `HAS_CODE_DEFAULT`) or builds a new `PlatformConfig` via `@SuperBuilder`
  (`value`, `valueType = LONG`, `updatedAt`; `description` left `null` — `BoundedKey`'s only prose
  field, `note`, documents a range-violation consequence, not a general description, so populating
  `description` from it would misrepresent the field). `invalidate()` still runs on both paths.
  New `ConfigServiceTest` cases assert the create-path's fields via `ArgumentCaptor` and confirm a
  non-`HAS_CODE_DEFAULT` absent key still 404s. New `ConfigResourceIT` case exercises the real
  explicit-id `INSERT` against the `GENERATED BY DEFAULT AS IDENTITY` column (never previously
  exercised) via PUT-then-GET against Testcontainers Postgres.
- **AC3 (scheduler pool):** `spring.task.scheduling.pool.size: 8` added under `spring:` in
  `application.yaml`. Confirmed `application-test.yaml`'s `app.scheduling.enabled: false` makes this
  inert under test (no `TaskScheduler` bean is engaged when `@EnableScheduling`'s own
  `@ConditionalOnProperty` removes it). Grep-swept `src/main/java` for comments/fields assuming
  single-threaded `@Scheduled` execution across different jobs — none found; `SchedulingConfig`'s own
  Javadoc discusses the *test suite's* single-context history (unrelated, descriptive) and
  `SluPersistenceDispatcher`'s "single-threaded" comment is about in-method call chaining, not
  cross-job scheduler concurrency.
- **AC4 (ShedLock truncation):** extracted `ShedLockConfig.truncateHostname(String)` (package-private
  static), backing the cut index off by one when a plain `substring(0, MAX_HOSTNAME_LENGTH)` would
  split a UTF-16 surrogate pair — the exact sketch from the story, independently re-verified correct.
  `ShedLockConfigIT`'s existing truncation assertion now calls the extracted method directly instead
  of re-implementing the rule inline. New test constructs a hostname whose 218th/219th code units are
  a supplementary-plane character, asserts the truncated result contains no lone surrogate and
  round-trips through UTF-8 cleanly.
- **AC5 (ledger closeout):** `deferred-work.md`'s `## Deferred from: code review of
  skillars-deferred-126…` section: bullets 1, 2, 4, 5, 6 deleted outright (closed by AC1–AC4); bullet
  7 (`EXPLAIN` confirmation) reframed from an open question to a confirm-and-cite task and empirically
  confirmed via a throwaway Testcontainers-backed `EXPLAIN` run (test file written, run once, deleted —
  not part of the File List) — this surfaced that `main.video_deletion_outbox`'s `resetStaleClaimed`
  actually plans through `idx_vdoutbox_status_claimed`, not `idx_vdoutbox_status_retry` as an
  imprecise reading of V144's own comment could suggest; `development.radar_composite_dlq`'s plans
  through `idx_radar_composite_dlq_status_retry`'s `status` prefix only, exactly as expected — the
  ledger bullet was corrected to state this distinction precisely rather than generalize both tables
  together. Two new `[DECIDED: accepted risk — skillars-deferred-127]` bullets added (the harmless
  `radar_composite_dlq` post-erasure residual, and AC1's new-but-not-introduced-from-scratch path into
  `markFailed`'s pre-existing no-`AdminAlert` gap). The `deleteStrike` cross-reference's now-collapsed
  asymmetry was re-decided rather than silently left stale. A new `## Last audit: 2026-09-21
  (skillars-deferred-127 dev-story completion)` heading was added, explicitly story-numbered per
  story-review.md L3 to stay distinguishable from the same-date skillars-deferred-126 heading.
  `sprint-status.yaml` updated: this story's own line's status set to `review` with a completion
  summary prepended, and `last_updated`'s header comment similarly prepended.

### Validation performed

Targeted tests only, per this project's `docs/validation-strategy.md` convention — no `mvn verify` run
locally. `mvn -o -Dtest=GdprErasureIT,ConfigServiceTest,ConfigResourceIT,ShedLockConfigIT,RadarCompositeCalculationServiceConcurrencyIT,ConfigBoundsEnumCoverageTest,ConfigStartupAssertionTest test`:
96 tests, 0 failures, 0 errors (all green after two fix-forward rounds — see below).

**Issues caught and fixed during this validation, not just assumed passing:**
1. `GdprErasureIT`'s new `erase(long)` test helper originally issued its `admin.gdpr_requests` seed
   INSERT via a bare `jdbcTemplate.update` outside any transaction — this datasource's HikariCP
   `auto-commit: false` means that INSERT was silently never committed (exactly the pitfall
   `DatabaseResetTestExecutionListener`'s own class Javadoc warns about), so
   `GdprErasureService.erase` immediately threw "GdprRequest not found". Fixed by wrapping the insert
   in `transactionTemplate.execute(...)`.
2. Four pre-existing `GdprErasureIT` tests (`erase_playerUser_deletesPerformanceReportFromS3` and its
   three outbox siblings) broke because their fixture seeded `performance_reports.player_id =
   PLAYER_ID` (a raw `main.user.id`) for an account with no `player_profiles` row — a shape that only
   ever "worked" because the pre-fix code passed `userId` straight through as if it were a
   `player_profiles.id`. Fixed by moving these four to the new profile-linked
   `SELF_PLAYER_PROFILE_ID`/`SELF_PLAYER_EMAIL` fixture, matching what AC1's fix now correctly
   requires for real deletion to occur.
3. `ConfigServiceTest.updateConfig_boundedKeyOutOfRange_rejectedWith400`/
   `updateConfig_boundedKeyNonNumeric_rejectedWith400` failed Mockito's strict-stubbing check
   (`UnnecessaryStubbing`) because my first draft moved `rejectOutOfRange` ahead of
   `configRepository.findByKey`, so their `findByKey` stub was never consulted before the range check
   threw. Fixed by keeping `findByKey` as the first call (unconditionally), matching the original
   method's ordering, and running `rejectOutOfRange` only after establishing the key is either present
   or upsertable.

Broader regression sweep (every touched package plus its siblings):
`mvn -o -Dtest='com.softropic.skillars.platform.admin.**,com.softropic.skillars.platform.config.**,com.softropic.skillars.platform.development.**,com.softropic.skillars.infrastructure.config.**' test`
— **451 tests, 0 failures, 0 errors, BUILD SUCCESS.** No regressions from either the AC1 lock/id-resolution
change or the AC2 upsert change anywhere in `platform.admin`, `platform.config`, `platform.development`,
or `infrastructure.config`. Frontend is untouched by this story (confirmed via `git status --short` — no
`src/frontend/**` diff); no `frontend-tests` PR label needed.

### Validation performed — `/bmad-code-review` response (2026-09-22)

`mvn -o -Dtest=GdprErasureIT,ConfigServiceTest,ConfigResourceIT,ShedLockConfigIT,RadarCompositeCalculationServiceConcurrencyIT,ConfigBoundsEnumCoverageTest,ConfigStartupAssertionTest,ExecutorShutdownConfigurationTest test`:
108 tests, 0 failures, 0 errors. Broader sweep,
`mvn -o -Dtest='com.softropic.skillars.platform.admin.**,com.softropic.skillars.platform.config.**,com.softropic.skillars.platform.development.**,com.softropic.skillars.infrastructure.config.**,com.softropic.skillars.infrastructure.threadpool.**,com.softropic.skillars.platform.security.**' test`:
**815 tests, 0 failures, 0 errors, BUILD SUCCESS.**

**One more issue caught and fixed during this validation:** `V151`'s first draft used a plain `SET
lock_timeout = '5s'` (copying `V143`'s pre-existing, grandfathered style), which
`MigrationConventionLintTest` correctly failed — any migration `> V139` must use `SET LOCAL
lock_timeout` (session-scoped `SET` persists past this migration's own `COMMIT` across Flyway's
single reused JDBC session for a whole deploy). Fixed; `MigrationConventionLintTest` (30/30) and the
full targeted+regression suite above both re-run green after the fix.

---

## Change Log

- 2026-09-22: `/bmad-code-review` response applied (see `## Review Findings` above for the full
  finding-by-finding detail; independently re-verified against actual source before applying, per
  the user's own request to watch for false positives — none found needing pushback). 3 decisions
  (all resolved via `AskUserQuestion` and applied): (1) the shared `player_profiles` lock did NOT
  fully close the resurrection race — `RadarAssessmentService.submitAssessment` writes
  `radar_assessment_entries` without it — closed via a sticky `development_data_erased_at` tombstone
  (new `V151` migration) checked by `recalculateComposite` under the same lock; (2)
  `ConfigService.updateConfig`'s concurrent-first-write race (two callers both see the key absent,
  one's `INSERT` violates `uq_platform_config_key`) closed via catch-`DataIntegrityViolationException`
  -and-re-read; (3) the new 8-thread scheduler pool had no `await-termination` and sat outside both
  the shutdown-budget arithmetic and `ExecutorShutdownConfigurationTest`'s guard — fixed
  (`spring.task.scheduling.shutdown.await-termination`, `docker-compose.yml`'s `stop_grace_period`
  55s→60s, new guard test reading `application.yaml` directly since the auto-configured bean is
  structurally invisible to the existing scan). 10 patches applied: a vacuous concurrency test
  rewritten to genuinely contend; a stale ledger passage corrected; deterministic lock-acquisition
  order (`findByParentIdOrderByIdAsc`); a dangling Javadoc sentence restructured; an orphaned Javadoc
  block reordered; `truncateHostname`'s guard simplified to close a pre-existing-malformed-input gap;
  a latch-discarding test assertion made discriminating (elapsed-time floor); two tests' thread leaks
  on timeout fixed (`shutdownNow`); a silent no-op path given a log line; the AC5 `EXPLAIN` ledger
  bullet given verbatim, reproducible plan output and a correction to an inherited imprecise index
  claim. 6 layer findings independently re-verified and confirmed as false positives (not re-applied).
  All touched-package tests green: 108 targeted tests, then a broader 815-test sweep across
  `platform.admin`/`platform.config`/`platform.development`/`infrastructure.config`/
  `infrastructure.threadpool`/`platform.security` — zero regressions.
- 2026-09-21: All 5 ACs implemented (`/bmad-dev-story`). Targeted tests green: 96 tests (0
  failures/errors) across `GdprErasureIT`, `ConfigServiceTest`, `ConfigResourceIT`, `ShedLockConfigIT`,
  `RadarCompositeCalculationServiceConcurrencyIT`, `ConfigBoundsEnumCoverageTest`,
  `ConfigStartupAssertionTest`; a broader 451-test regression sweep across
  `platform.admin`/`platform.config`/`platform.development`/`infrastructure.config` also green, zero
  regressions. Three issues caught and fixed during validation (not assumed passing): an uncommitted
  test-helper INSERT (HikariCP `auto-commit: false`), four pre-existing `GdprErasureIT` tests whose
  fixture only worked against the pre-fix (buggy) id resolution, and a Mockito strict-stubbing
  regression in two pre-existing `ConfigServiceTest` cases from an initial task-ordering choice in
  `updateConfig`. See Dev Agent Record for full detail. Status → review. Ready for commit/PR.
- 2026-09-21: `story-review.md` (senior-dev pre-implementation audit) applied. 2 blocking + 4 high +
  6 medium + 4 low/accuracy findings, every one independently re-verified against actual source
  (`GdprErasureService.java`, `PlayerProfileRepository.java`, `BaseEntity.java`,
  `V138__baseline_schema.sql`, `GdprErasureIT.java`/`secData.sql`, `ConfigBounds.java`,
  `V139__baseline_seed_data.sql`, `ConfigService.java`, `PlatformConfig.java`, `ConfigServiceTest.java`,
  `ConfigResourceIT.java`, `application.yaml`, `PessimisticLockRetryer.java`, `ShedLockConfig.java`,
  `ShedLockConfigIT.java`, `RadarCompositeCalculationService(ConcurrencyIT).java`, `V144__...sql`,
  `deferred-work.md`) before applying anything — **zero false positives** in the review's substantive
  findings (one minor factual slip *within* M5 itself, a wrong example service name, corrected without
  weakening that finding). Two blocking corrections: **B1** — AC1's originally-drafted PLAYER-role id
  resolution passed `main.user.id` where `player_profiles.id` (an independent TSID) was required, which
  would have failed every PLAYER-role GDPR erasure in production and turned six existing ITs red; fixing
  it surfaced a real pre-existing bug (the PLAYER path has never actually deleted a player's development
  data, due to the same id mismatch) and a new owner decision (`AskUserQuestion`, taken live:
  `orElse`-skip, not `orElseThrow`, when no profile row exists). **B2** — the config-key gap's scope was
  wrong by 4.5x: `ConfigBounds.HAS_CODE_DEFAULT` holds 17 keys (not 18, a miscount), and only 4 of those
  17 are actually unseeded (not all of them) — the root cause was a misread of `HAS_CODE_DEFAULT`'s own
  Javadoc (defined by call-site absence-tolerance, not by "no Flyway seed", which was an imprecise
  inheritance from the ledger bullet's own title); AC2's fix and scoping rationale are unaffected, only
  the count and framing needed correcting, in this story file, in `sprint-status.yaml`, and in AC5's
  ledger-closeout instruction. Four high findings: **H1** — the "locked/unlocked independently" claim
  for AC1's per-player lock was wrong (Postgres releases `FOR UPDATE` locks only at transaction end; a
  parent with N children holds N locks simultaneously until `erase()` commits, and the retry-budget
  claim needed restating as per-player, N × ~3.2s, not flat ~3.2s). **H2** — added AC1's omitted
  FK-induced lock-conflict exposure (`FOR UPDATE` on `player_profiles` conflicts with `FOR KEY SHARE`
  from inserts into seven referencing tables, for the remainder of the now-longer-held erase
  transaction). **H3** — AC1 introduces a new GDPR-erasure failure mode (a 120s-capable
  `recalculateComposite` lock hold vs. the erasure's ~3.2s retry budget can now fail a previously-
  succeeding erasure fast, routed to a `markFailed` that raises no `AdminAlert`) — owner decision taken
  live: document as accepted risk (AC5), not new alerting machinery, since the underlying gap is
  pre-existing and project-wide. **H4** — extended AC1 Task 6's documentation-update scope from one
  stale location to all four (`RadarCompositeCalculationService`'s own Javadoc,
  `ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s Javadoc, the concurrency IT's test framing, and
  — the substantive one — the `AdminCoachEnforcementService.deleteStrike` accepted-risk bullet whose
  own contrast against "the radar case" this AC collapses). Six medium findings applied: **M1** — AC4's
  test plan assumed a hostname-mocking seam that does not exist; added a production-code extraction task
  (package-private static method) the tests can actually call. **M2** — softened AC4's "breaks every
  `@SchedulerLock` job" severity claim (Java's default UTF-8 encoder substitutes, does not throw; the
  `UUID` suffix keeps `locked_by` unique regardless) while confirming the fix itself and its code sketch
  are correct. **M3** — reframed AC5's `EXPLAIN` task from an open discovery question to a
  confirm-and-cite task, since `V144__outbox_dlq_claimed_at.sql`'s own comment already answers it and
  already accepted the answer as-is; also corrected a predicate citation that had dropped the
  `claimed_at IS NULL OR` disjunct. **M4** — rewrote AC1's test plan: `GdprErasureIT` drives everything
  over HTTP through an `AFTER_COMMIT` listener that swallows every exception, so the concurrency
  assertions must call `GdprErasureService.erase` directly; no `player_profiles` fixture exists in this
  IT today (added, mirroring `RadarCompositeCalculationServiceConcurrencyIT`'s own pattern); and the
  fixture's id must be deliberately distinct from the user id or the test would pass against the
  pre-fix, still-broken code. **M5** — added AC3's omitted connection-pool and lock-coverage risk notes
  (HikariCP pool sized for 25 total; ~18 of 44 scheduled methods carry no `@SchedulerLock`) — one cited
  example (`MessageModerationSweeper`) in the review's own text was independently found not to use
  `PessimisticLockRetryer` and was swapped for verified examples (`PaymentPendingSweeper`,
  `BookingBatchService`) without weakening the finding. **M6** — AC2's original test plan relied on a
  Mockito-mocked repository to assert real-insert behavior it cannot observe; split into a scoped
  mocked-repository test (path/fields only) plus a new `ConfigResourceIT` case against real
  Testcontainers Postgres for the actual first-ever application-side `platform_config` INSERT, and
  corrected a citation that named JPA's inert `@Column(unique = true)` instead of the real
  `uq_platform_config_key` DB constraint. Four low/accuracy findings applied: **L1** — refreshed several
  line-citation ranges that had drifted a few lines since story creation. **L2** — corrected this story's
  own Provenance-section bullet arithmetic ("closes four … leaves three open" was wrong; the correct
  count, matching AC5 Task 2's own per-bullet accounting, is six bullets closed and one left open).
  **L3** — added two ledger-hygiene tasks to AC5: the section preamble's "first seven … eighth …"
  framing goes stale once five of those seven bullets are deleted, and a second same-date "Last audit"
  heading risks colliding with the existing skillars-deferred-126 one (whose own cross-reference already
  resolved to nothing once before). **L4** — added the explicit dependency (the `main.player_profiles`
  row surviving erasure) the AC1 Task 8 residual's safety argument rests on, so a future reader does not
  have to re-derive it. No AC's core mechanism or the four originally-taken owner decisions were
  reopened — every correction is to implementation-instruction accuracy, a fifth and sixth genuinely new
  decision point the review surfaced (both taken live, `AskUserQuestion`), task completeness, test
  buildability, and citation correctness. See `story-review.md` for the full finding-by-finding detail.
- 2026-09-21: Story created via manual ledger-mining process (this project's established convention
  for `skillars-deferred-N` stories — `/bmad-create-story`'s generic epic/PRD-driven workflow does not
  fit this ledger-mining shape, confirmed by checking its `customize.toml` for a project-specific
  override and finding none). Mined the freshest same-day code-review deferral
  (`code review of skillars-deferred-126`, 2026-09-21, 7 open bullets + 1 already-`[DECIDED]`). All
  citations independently re-verified against `master@24fe4a80` (skillars-deferred-126's own merge,
  PR #214) at creation time — one concrete correction found during this verification: the config-key
  gap (AC2) affects 18 `HAS_CODE_DEFAULT` keys, not the 3 the original bullet named as examples. Four
  owner decisions taken live with the user (`AskUserQuestion`) before drafting: (1) close both the
  GDPR-erasure/radar-recalculation resurrection bug and its sibling deadlock hazard via a single shared
  `player_profiles` pessimistic lock (reusing the existing `findByIdForUpdate` +
  `PessimisticLockRetryer` pattern `recalculateComposite` already uses), rather than a table-order-only
  fix or leaving the resurrection risk as documented-not-fixed; (2) fix the project-wide unseeded
  `HAS_CODE_DEFAULT` config-key write gap via an upsert-on-write in `ConfigService.updateConfig`,
  scoped narrowly to `HAS_CODE_DEFAULT` keys rather than the full bounded-key set or a
  documentation-only response; (3) fix the single-thread `@Scheduled` starvation gap by adding
  `spring.task.scheduling.pool.size: 8` rather than leaving it as a documented risk for a dedicated
  future story; (4) keep this story's scope to the same-day deferral (4 ACs) rather than reaching
  further into the ledger for an additional unrelated item. Also folded in, no decision required: the
  `ShedLockConfig` hostname-truncation surrogate-pair-split bug (AC4, bundled with the truncation-length
  citation refresh since it lives in the exact method already re-verified) and skillars-deferred-126 AC1
  Task 3's never-performed `EXPLAIN`/index-coverage confirmation (folded into AC5 as a verification
  task). Left explicitly open, with rationale recorded in the Provenance section: the `now()` vs
  `clock_timestamp()` fragility bullet (latent, not a live bug, and this story is already a full 4-AC
  bundle without it).

---

## Review Findings (`/bmad-code-review`, 2026-09-21)

Four parallel layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor, and
`/txn-and-concurrency-audit` (added as a fourth layer for this story's lock-heavy diff). Every
finding below was independently re-verified against real source by the orchestrator before being
recorded; six layer findings were rejected as false positives and are listed at the bottom.

### Decision needed — RESOLVED 2026-09-21 (owner, AskUserQuestion during /bmad-code-review)

- [x] [Review][Decision] **AC1's shared lock does NOT close the resurrection race — `RadarAssessmentService.submitAssessment` writes the source table without it** — The lock serializes *erase ↔ recalculate*, but the data `recalculateComposite` reads comes from `development.radar_assessment_entries`, whose writer `RadarAssessmentService.submitAssessment` (`RadarAssessmentService.java:46-91`) takes no `player_profiles` lock and has no FK to that table. Verified interleaving: (T0) coach's `submitAssessment` tx inserts assessment rows for player P, uncommitted; (T1) `erase` acquires `player_profiles(P) FOR UPDATE` and runs `radarAssessmentRepository.deleteAllByPlayerId(P)` — under READ COMMITTED it cannot see the uncommitted rows, so they are not deleted; (T2) coach's tx commits, its `@TransactionalEventListener(AFTER_COMMIT) @Async("reportExecutor") onRadarEntrySubmitted` (`RadarCompositeCalculationService.java:78-95`) fires; (T3) `recalculateComposite` blocks on the lock, backs off via `PessimisticLockRetryer`; (T4) erase commits and releases; (T5) the retry succeeds, reads the surviving assessment rows, and `upsertComposite`/`insertBaselineIfAbsent` re-create composites and baselines for the erased player. Both the derived composites AND the raw `radar_assessment_entries` (coach notes and scores for a named minor) survive an Article-17 erasure permanently. **This is not a regression** — the same race existed pre-fix — but AC1's "fully serializes" / "closes the resurrection race" claim is false as written, and the team would believe it is protected when it is not. **Also falsifies the safety argument of an accepted-risk bullet added by AC5** (`deferred-work.md:2820-2824`), which reasons that a stale DLQ row is harmless "since `deletePlayerDevelopmentData` also deletes the player's `radar_assessment_entries`" — exactly what does not happen in this window. Two candidate fixes, materially different in cost: (a) take the same `player_profiles` lock in `submitAssessment`, or (b) a sticky erased-tombstone that `recalculateComposite` checks under the lock (also covers the DLQ-retry path). Owner decision required.
  **APPLIED 2026-09-22:** option (b), per the decision below — `PlayerProfile.developmentDataErasedAt`
  (new nullable column, `V151__player_profiles_development_data_erased_at.sql`), stamped by
  `deletePlayerDevelopmentData` under its own lock and checked by `recalculateComposite` immediately
  after it re-acquires/refreshes that same lock, before reading any aggregates. New test
  `RadarCompositeCalculationServiceConcurrencyIT#recalculateComposite_playerAlreadyTombstoned_skipsWithoutUpsertingAnything`
  proves the check. The falsified AC5 accepted-risk bullet's safety argument was corrected in
  `deferred-work.md`, not just re-asserted.
- [x] [Review][Decision] **AC2 Task 3's required decision on the concurrent first-write race was never taken or recorded** — `ConfigService.updateConfig` (`ConfigService.java:212-234`) is a read-then-insert TOCTOU with no `@Transactional`, no `ON CONFLICT`, no catch-and-re-read. Two concurrent first-ever writes for the same unseeded `HAS_CODE_DEFAULT` key both see `Optional.empty()`, both INSERT, and the loser violates `uq_platform_config_key` (`V138__baseline_schema.sql:2678`) → `DataIntegrityViolationException` → HTTP 500, with the losing write silently dropped. AC2 Task 3 explicitly required a recorded decision ("needs explicit handling in this AC, or is an acceptable, extremely rare edge left unhandled"); neither the code nor the Dev Agent Record carries one. Confirmed no `UnexpectedRollbackException` risk: `spring.jpa.open-in-view: false` and `ConfigResource` is not `@Transactional`, so there is no outer transaction to poison. Decide: handle (catch-and-re-read) or accept-and-document.
  **APPLIED 2026-09-22:** `ConfigService.createOrReadBack` catches `DataIntegrityViolationException`
  from the losing `INSERT`, re-reads the winner's now-committed row, and applies this call's own
  value to it — last-writer-wins. New `ConfigServiceTest` case simulates the losing `save()` and
  asserts the re-read-and-update path.
- [x] [Review][Decision] **AC3's new 8-thread scheduler pool sits outside the project's graceful-shutdown budget and outside the guard meant to catch exactly that** — The `taskScheduler` is auto-configured by `TaskSchedulingAutoConfiguration`, so it never passes through `ExecutorShutdown.configureGracefulShutdown`, and `spring.task.scheduling.shutdown.await-termination` is set nowhere (verified), so it defaults to `false` → `ExecutorConfigurationSupport.destroy()` takes the `shutdownNow()` path. Where one in-flight scheduled job was previously interrupted mid-transaction on SIGTERM, up to eight now are, and none of that is in `ExecutorShutdown`'s documented "~48s vs 55s `stop_grace_period`" arithmetic. `ExecutorShutdownConfigurationTest#everyExecutorBeanIsCovered` structurally cannot flag it: it scans only `classpath*:com/softropic/skillars/**/*Config.class` (`ExecutorShutdownConfigurationTest.java:217`), and the auto-configured bean lives in `org.springframework.boot.*`. Choosing an `await-termination` value interacts with the 12-minute `VideoDeletionOutboxProcessor.MAX_RUN_DURATION` and the existing grace-period arithmetic, so this is a decision, not a mechanical patch.
  **APPLIED 2026-09-22:** `spring.task.scheduling.shutdown.await-termination: true` +
  `await-termination-period: 5s` added to `application.yaml`; `ExecutorShutdown`'s own budget
  arithmetic and Javadoc updated (~48s → ~53s) and `docker-compose.yml`'s `stop_grace_period` raised
  55s → 60s to match. New `ExecutorShutdownConfigurationTest#schedulerTaskExecutor_awaitsTerminationOnShutdown`
  reads `application.yaml` directly (SnakeYAML, same technique as `NoStraySmtpConfigTest` — no new
  Spring context) so a regression to this property is caught even though the bean itself is
  structurally invisible to `everyExecutorBeanIsCovered`'s scan.

### Patch

- [x] [Review][Patch] Resurrection regression test is vacuous — releases the writer before `erase()` starts, passes against pre-fix code [src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java:695-700] — flagged independently by all four layers. The eraser thread runs `Thread.sleep(300); releaseWriter.countDown(); erase(...)`, so the latch is counted down *before* `erase()` is invoked; the inline comment ("give erase()'s bounded retry a head start against the held lock") describes the opposite of what the code does. The writer's two INSERTs and commit finish in single-digit ms while `erase()` is still opening its `gdpr_requests` transaction, so the safe order is realised regardless of whether the production lock exists. Deleting `GdprErasureService.java:236-238` leaves this test green. The sibling test one method above (`:599-606`) uses the correct shape — start `erase()` first, release the writer from the locker side after a delay.
  **APPLIED 2026-09-22:** rewritten to hold the writer's lock for a fixed internal duration
  (mirroring the sibling test and `RadarCompositeCalculationServiceConcurrencyIT`'s own locker
  pattern) instead of an externally-signaled latch the eraser thread itself released — the writer now
  genuinely blocks `erase()` for its whole hold window regardless of anything the eraser does.
- [x] [Review][Patch] Stale ledger passage still asserts the exact claim AC1 falsified [_bmad-output/implementation-artifacts/deferred-work.md:2661-2672] — still reads in present tense that `deletePlayerDevelopmentData` runs "without taking `recalculateComposite`'s own `player_profiles` pessimistic lock at all ... is a genuine lock-ordering deadlock hazard", and closes with "unlike the radar case's GDPR-erasure conflict source". AC5 Task 5's sweep fixed only the `deleteStrike` copy at `:2451-2484`; the Dev Agent Record nonetheless claims all four locations were updated.
  **APPLIED 2026-09-22:** added a historical-note correction to that passage in `deferred-work.md`,
  same treatment as the `deleteStrike` copy.
- [x] [Review][Patch] PARENT branch acquires N `player_profiles` locks in `findByParentId`'s unordered result [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:142-145] — `findByParentId` carries no `ORDER BY`, so acquisition order is heap order. This does not produce a Postgres `40P01` (the `NOWAIT` hint means the loser fails instantly rather than waiting, so no circular wait can form), but it makes retry-exhaustion failures nondeterministic, and the only thing preventing a real deadlock is an implicit `NOWAIT` dependency the Javadoc never states. `ORDER BY id` is a zero-cost fix.
  **APPLIED 2026-09-22:** renamed to `findByParentIdOrderByIdAsc` (all 3 call sites updated:
  `GdprErasureService` ×2, `ShadowAccountService`).
- [x] [Review][Patch] Dangling sentence leaves the rewritten Javadoc self-contradictory [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:119-123] — the trailing "That is a genuine lock-ordering deadlock shape (Postgres `40P01`), not merely an unbounded wait (`55P03`)" lost its referent when the "NOW CLOSED" text was inserted above it; "That" now points at the sentence about the paragraph remaining accurate. The reader is told the conflict is closed and then that it is live.
  **APPLIED 2026-09-22:** restructured into three distinct paragraphs (original conflict description
  → "direct table-order collision CLOSED" → "residual race through the source table closed by
  tombstone"), each self-contained with no dangling forward/backward references.
- [x] [Review][Patch] Orphaned Javadoc in `ShedLockConfigIT` [src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java:103-120] — the new surrogate-pair test was inserted between the pre-existing deferred-126 Javadoc and the method it documented, leaving two consecutive Javadoc blocks with no member between them and `lockProviderMethod_twoDirectCallsBypassingSpring_produceDifferentLockedByValues` (now `:151`) undocumented.
  **APPLIED 2026-09-22:** moved the surrogate-pair test (and its own lone-surrogate-at-cut-index
  sibling, added by this same review response) to sit after
  `lockProviderMethod_twoDirectCallsBypassingSpring_produceDifferentLockedByValues`, restoring that
  method's own Javadoc to directly precede it.
- [x] [Review][Patch] `truncateHostname` still emits a lone surrogate for a pre-existing unpaired high surrogate at the cut index [src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java:78-82] — the guard requires a *well-formed* pair straddling the boundary (`isHighSurrogate(charAt(217)) && isLowSurrogate(charAt(218))`). For `"h".repeat(217) + '\uD83D' + "x..."` the back-off does not fire and `substring(0, 218)` returns a string ending in exactly the lone high surrogate the method exists to prevent. `Character.isSurrogate(charAt(truncateAt - 1))` closes it. The new test covers only the well-formed-pair fixture. (The concurrency layer asserted this method "cannot emit a lone surrogate" — that assertion is wrong; two layers and the branch logic say otherwise.)
  **APPLIED 2026-09-22:** simplified the guard to back off whenever the last character before the cut
  is a high surrogate at all (dropped the "is the NEXT character also a low surrogate" condition
  entirely — checked this doesn't regress the well-formed-pair-at-boundary case, since a well-formed
  LOW surrogate landing exactly at the cut index was never itself a problem to leave in place). New
  test `truncateHostname_backsOffOnAPreExistingUnpairedHighSurrogateAtTheCutIndex` covers the
  malformed-input case.
- [x] [Review][Patch] `erase_blockedByCompetingPlayerProfileLock_waitsThenSucceeds` discards its latch result and never observes the wait [src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java:601,615-620] — `lockHeld.await(10, TimeUnit.SECONDS)`'s boolean is dropped, though the same file defines an `await(CountDownLatch)` helper at `:742-751` that throws `AssertionError` on timeout; on a latch timeout the eraser runs against an unlocked row and the test goes green while silently testing nothing. Separately, the assertions (`eraseFailure == null`, `activated == false`) would also pass with the lock removed — an elapsed-time floor or a `persistence.lock_retry.retries` counter assertion would make it discriminating. (Unlike the resurrection test, this one *does* genuinely exercise contention — erase is called while the locker holds the row for 1200ms.)
  **APPLIED 2026-09-22:** switched to the file's own `await(CountDownLatch)` helper, and added a
  ≥900ms elapsed-time floor assertion on the `erase()` call itself — discriminates a genuine wait
  from no contention at all.
- [x] [Review][Patch] Both new concurrency tests leak runaway threads holding row locks on timeout [src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java:706-711] — `executor.shutdown()` does not interrupt running tasks and neither `Future` is cancelled on `TimeoutException`; a hung locker keeps its `player_profiles FOR UPDATE` open into subsequent tests in the same class, which then fail for an unrelated, confusing reason. Relevant precisely because holding a row lock is these tests' purpose.
  **APPLIED 2026-09-22:** both tests' `finally` blocks now call `executor.shutdownNow()` +
  `awaitTermination(...)` instead of a bare `shutdown()`.
- [x] [Review][Patch] Silent no-op on a legally auditable erasure path [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:138-140] — `playerProfileRepository.findByUserId(userId).ifPresent(...)` skips development-data deletion with no log line, no counter, and nothing in the `gdpr_requests` record distinguishing "erased development data" from "found none". The class is `@Slf4j`. If the absence ever has a cause other than the intended never-had-a-profile case, the request reports `COMPLETED` while leaving every row in place. An `orElseGet(() -> log.warn(...))` makes the two outcomes distinguishable.
  **APPLIED 2026-09-22:** switched to `ifPresentOrElse`, logging a `WARN` with `userId` on the
  no-profile path.
- [x] [Review][Patch] AC5's `EXPLAIN` evidence is unreproducible and repeats an imprecise index claim [_bmad-output/implementation-artifacts/deferred-work.md:2795-2799] — the ledger now asserts specific planner output while the story records that the test was "written, run once, deleted"; no plan output is quoted verbatim anywhere in the repo. Relatedly it repeats V144's framing that `idx_vdoutbox_status_retry` previously served the reset predicate, but `V138__baseline_schema.sql:3670` defines it as `... WHERE ((status)::text = 'PENDING'::text)` — a partial index that could never have served a `status = 'CLAIMED'` predicate.
  **APPLIED 2026-09-22:** the ledger bullet now quotes the literal `EXPLAIN` output verbatim (with
  reproduction instructions), and adds a correction noting `idx_vdoutbox_status_retry` (a `WHERE
  status = 'PENDING'` partial index) could never have served the video table's `CLAIMED`-scoped
  query even pre-V144 — that table's `resetStaleClaimed` prefix has always been served by
  `idx_vdoutbox_status_claimed`. `V144__outbox_dlq_claimed_at.sql` itself is left unedited
  (rewriting an already-applied migration's content would invalidate its Flyway checksum).

### Deferred (pre-existing or out of scope)

- [x] [Review][Defer] Erase holds `player_profiles FOR UPDATE` for its full remaining transaction, blocking FK `FOR KEY SHARE` RI checks on child tables [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:236-257] — deferred, already analysed as story-review H2 and documented in the story's own risk notes.
- [x] [Review][Defer] `PessimisticLockRetryer` is now used from the long-running call site its own Javadoc forbids [src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:44-52] — deferred; the class documents "all current call sites are short read-then-maybe-refresh operations ... if a future call site is long-running, revisit this". `deletePlayerDevelopmentData` is 14 bulk deletes across 13 tables invoked once per child, so a PARENT erasure can occupy a Hikari connection for N × ~3.2s of pure sleep. The precondition was triggered and not revisited.
- [x] [Review][Defer] Hikari pool pressure from 8 concurrent schedulers [src/main/resources/application.yaml:120] — deferred; `maximum-pool-size: 25` is shared with Tomcat, clustered Quartz and six `@Async` executors, and `PessimisticLockRetryer` holds its connection while sleeping. `8` has no stated derivation. Partially covered by AC3's existing M5 risk notes.
- [x] [Review][Defer] Five DB-touching `@Scheduled` methods still carry no `@SchedulerLock` [UploadSessionExpiryScheduler, ReconciliationWorkerScheduler, WebhookEventProcessorScheduler, ModerationSlaMonitorService, AlertEvaluationService] — deferred, pre-existing and multi-instance-only. Spring's `ReschedulingRunnable` schedules the next execution only after the current returns, so pool size 8 cannot make any job overlap *itself*; the diff does not worsen this.
- [x] [Review][Defer] `orElseThrow` inside the multi-child PARENT loop aborts the whole erasure if one child's row vanishes [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:236-238] — deferred; a benign race (the child was erased concurrently, i.e. the data is already gone) rolls back siblings' completed deletions and fails the request. Arguably correct-as-designed per the method's explicit Javadoc reasoning; rare enough to defer.

### Rejected as false positives (verified against source, not recorded as findings)

1. *"Hardcoded `ConfigValueType.LONG` causes `NumberFormatException` on later reads."* — `valueType` is never read functionally; `getLong`/`getValue` parse the raw string. `HAS_CODE_DEFAULT` is *defined* by 2-arg `getLong`/`getInt` call-site tolerance, so every key in it is numeric by construction and `LONG` is correct for all 17. The sibling `ConfigServiceTest` assertion is correspondingly fine.
2. *"Response DTO is mapped from a transient entity with `id == null`."* — `ConfigValueResponse` has no `id` component at all, and `persist()` populates the identifier on the passed instance regardless.
3. *"`entityManager.refresh(..., PESSIMISTIC_WRITE)` outside `withBoundedRetry` is a new defect."* — byte-identical to the pre-existing call site at `RadarCompositeCalculationService.java:202-204`, and `PessimisticLockRetryer`'s own Javadoc documents this exact shape ("`findByIdForUpdate` + optional `refresh`"). Not introduced here. The suggested reading that `findByIdForUpdate` does not lock is refuted by `PlayerProfileRepository.java:44-47` (`@Lock(PESSIMISTIC_WRITE)` + `lock.timeout=0` NOWAIT).
4. *"Lock-retry exhaustion fails a GDPR erasure into `FAILED` with no re-drive."* — real, but already an explicit owner decision recorded at `deferred-work.md:2831` (`[DECIDED: accepted risk — skillars-deferred-127]`, story-review H3). Not re-litigated.
5. *"`truncateHostname` needs a null guard."* — unreachable from the production caller; ShedLock's `Utils.getHostname()` returns the constant `"unknown"` on `UnknownHostException`.
6. *"`truncateHostname` cannot emit a lone surrogate."* — the opposite is true; see the corresponding patch item above.

### Review decisions taken (owner, `AskUserQuestion`, 2026-09-21)

1. **Resurrection gap → erased-tombstone checked under the lock.** A sticky erased marker is written
   by `GdprErasureService.deletePlayerDevelopmentData` and checked by
   `RadarCompositeCalculationService.recalculateComposite` while it holds the `player_profiles` lock;
   if the marker is present, the recalculation returns without upserting. Chosen over locking
   `submitAssessment` because it also covers the `RadarCompositeDlqProcessor` retry path, which a
   lock on the coach-facing write path would not. Requires a Flyway migration (next free version:
   `V151`, subject to `docs/deployment/migration-conventions.md` since version > 121).
2. **Config first-write race → catch and re-read.** `updateConfig` catches
   `DataIntegrityViolationException` from the insert, re-reads the now-committed row, and applies the
   update to it — turning a 500 into correct last-writer-wins behaviour. Satisfies AC2 Task 3's
   requirement for a recorded decision.
3. **Scheduler shutdown → set `await-termination` and widen the guard.**
   `spring.task.scheduling.shutdown.await-termination: true` with a termination period that fits the
   existing `~48s`/`55s` `stop_grace_period` arithmetic, plus widening
   `ExecutorShutdownConfigurationTest#everyExecutorBeanIsCovered` so an auto-configured scheduler can
   no longer slip past the guard.
