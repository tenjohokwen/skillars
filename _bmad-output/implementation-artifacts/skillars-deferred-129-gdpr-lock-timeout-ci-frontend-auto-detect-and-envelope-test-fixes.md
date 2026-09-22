# Story: GDPR Erasure Statement Lock Timeout, CI Frontend-Change Auto-Detection & Envelope Durability Test Fix

**Story Key:** `skillars-deferred-129-gdpr-lock-timeout-ci-frontend-auto-detect-and-envelope-test-fixes`
**Epic:** Deferred Work
**Priority:** Medium (a real, still-unbounded blocking-DELETE hazard that silently defeats skillars-deferred-128 AC2's own per-erase deadline — plus two genuine, low-risk gaps: a CI safety net that depends on human memory to fire, and a test helper whose full-table scan the codebase's own established convention already flags as avoidable).
**Status:** ready-for-dev
**Created:** 2026-09-22

---

## Provenance & Scoping (read before starting)

This story mines `deferred-work.md` per this project's established "read same-day code-review
deferrals before drawing scope" convention, plus two owner-selected thin/previously-declined items
per an explicit owner decision (`AskUserQuestion`, this story's own creation session) taken because
the freshest same-day section alone (one bullet) was too thin for this series' "do not create small
stories" bundling convention, and the owner declined the alternative of dispatching a fresh ad-hoc
concurrency audit of `platform.marketplace`/`platform.reviews` (the only two modules never swept
under that lens) in favor of closing out existing, already-documented gaps instead.

### `## Deferred from: code review of skillars-deferred-128-...` (2026-09-22) — the one fresh bullet

`deferred-work.md:3001-3022`. Surfaced by `/bmad-code-review`'s `/txn-and-concurrency-audit` layer
while reviewing skillars-deferred-128, and explicitly deferred there (not fixed) as a pre-existing
gap the story's own new `gdprEraseLockBudget` bound made newly worth documenting. **Not
`[CLOSED]`/`[DECIDED]`** — genuinely open. **Closed by AC1 below.**

**Citation re-verified against current `HEAD` (`ff49c148`, skillars-deferred-128 merged) —
drifted, corrected here:** the ledger cites `GdprErasureService.java:436-453`; at current `HEAD`
the method is `deletePlayerDevelopmentData` at **`:487-527`**, with its 11
`deleteAllByPlayerId`/`deleteByPlayerId` calls at **`:498-515`** (line numbers shifted when
skillars-deferred-128's own `/bmad-code-review` response landed, after the ledger bullet was
written).

### `## Deferred from: code review of skillars-deferred-108` (2026-09-10) — one still-open bullet, owner-selected

`deferred-work.md:2049-2061`. **Not `[CLOSED]`/`[DECIDED]`** (owner decision D2 there only decided
to make the job opt-in *deliberately* — this bullet records the coupling, not a disagreement, and
explicitly says "Revisit if/when the job is promoted to a required check"). Re-verified against
current `HEAD`: `.github/workflows/frontend-unit-tests.yml` still gates `frontend-unit` on
`github.event_name == 'workflow_dispatch' || contains(github.event.pull_request.labels.*.name,
'frontend-tests')` (`:51-53`) — unchanged since 2026-09-10. **Closed by AC2 below**, per this
story's own owner decision (`AskUserQuestion`): replace the human-memory dependency with automatic
detection, not promote to an unconditional required check (the latter would cost ~10 CI minutes on
every PR regardless of whether frontend was touched).

### `## Deferred from: code review of ses-1-4-registration-email-durability` (2026-09-12) — one still-open bullet, owner-selected

`deferred-work.md:2198`. **Not `[CLOSED]`/`[DECIDED]`** — explicitly re-confirmed still open at the
2026-09-15 full-file re-audit (`[Restored 2026-09-15 — see the audit below.]`) and again untouched
by the skillars-deferred-110/-111 prunes (`deferred-work.md:2245-2251` lists it by name as "none
were in scope … and none were touched by it"). **Partially pre-closed by skillars-deferred-111
AC7** (added `committedRowBySendId`, a targeted `findBySendId` lookup, for every call site that
already knows its own generated `sendId` — see
`RegistrationEmailDurabilityIT.java:95-107`), **but one call site remains**:
`committedRowFor(String email)` (`:85-93`) still does a full `envelopeEntityRepository.findAll()`
scan with an in-memory recipient-email filter, because at the point that helper is first called in
each test, only the UUID-unique seeded email is known (the listener generates `sendId` internally)
— its own Javadoc (`:76-84`) documents this as "the one `findAll()` … this class cannot avoid,"
which was true given the tools available to skillars-deferred-111 (`findBySendId` only), but is
**not actually true** — a JPQL query joining the `recipients` `@ElementCollection` on `email` was
never added. **Closed by AC3 below.**

### Out of scope (explicitly, not re-decided here)

- A fresh ad-hoc concurrency/TOCTOU audit of `platform.marketplace`/`platform.reviews` — offered as
  an alternative during this story's own scoping (`AskUserQuestion`) and declined by the owner in
  favor of closing the two items above instead. Still a real candidate for a future story: these are
  the only two modules with no dedicated audit in this series' history (skillars-deferred-115 through
  -128 swept every other module under the `@Scheduled`/TOCTOU lens).
- `GdprErasureService.markFailed`'s lack of `AdminAlert`/auto-retry for the *generic* failure case —
  already `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened (the deadline and
  skip-and-continue paths already gained targeted alerts under skillars-deferred-128).
- `radar_composite_dlq` rows for an erased player not cleared — already
  `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.
- `main."user"` cleanup-sweep missing index — still blocked on production `EXPLAIN`/row-count
  evidence this project has no deploy history to generate; not reopened.

All citations below were independently re-verified against `master@ff49c148` (this story's own
creation-time `HEAD`, skillars-deferred-128 merged via PR #216) by direct `Read`/`Grep` against the
actual source files, not assumed from the ledger's own (in one case, stale) citations.
**Re-verify again at actual implementation time** if this worktree's `HEAD` has moved.

---

## AC1 — Bound the GDPR erasure inner transaction's bulk deletes with a `lock_timeout`

### Context

`GdprErasureService.deletePlayerDevelopmentData`
(`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:487-527`,
added by skillars-deferred-128 AC1) runs, per child, inside its own `REQUIRES_NEW` transaction
(`requiresNewTemplate.executeWithoutResult(status -> { ... })`, `:488`): it re-acquires the
`player_profiles` pessimistic lock (`:489-491`), then issues 11 bulk
`deleteAllByPlayerId`/`deleteByPlayerId` calls across 11 tables (`:498-515`), a
`performance_reports` scan collecting S3 keys, a blob-deletion enqueue (`:517-520`), and the
`developmentDataErasedAt` tombstone stamp (`:522-525`) — all before the lock is released at that
inner transaction's commit.

The lock *acquisition* itself is bounded: `findByIdForUpdate` uses `NOWAIT`
(confirmed by `PlayerProfileRepository`'s own `@QueryHints`), and `PessimisticLockRetryer.
withBoundedRetry` wraps it in a jittered backoff with a documented ~3.2s worst case. **Nothing
downstream of that acquisition is bounded.** None of the 11 `DELETE` statements, the blob-enqueue
`INSERT`, or the final tombstone `save()` carries a `lock_timeout` — unlike
`RadarCompositeCalculationService.recalculateComposite`
(`src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:287-289`),
which issues `SELECT set_config('lock_timeout', ?1, true)` before its own per-skill upsert
statements, this method issues no such call at all, and `application.yaml`'s
`connection-init-sql` sets only the session time zone — there is no global `lock_timeout` either.

**The concrete hazard:** if a concurrent writer holds a conflicting row lock on any of the 11
target tables (for example, `development.radar_assessment_entries` — a coach's `submitAssessment`
transaction can legitimately hold row locks there), one of `deletePlayerDevelopmentData`'s
`DELETE` statements blocks **indefinitely** while the request thread still holds that child's
`player_profiles FOR UPDATE` lock. This is strictly worse than the already-`[DECIDED: accepted
risk]` single-child hold-time gap `PessimisticLockRetryer`'s own Javadoc names as future work,
because:

- It silently defeats skillars-deferred-128 AC2's own `gdprEraseLockBudget` deadline
  (`GdprErasureService.java:99-116`, `~10s` by default): that deadline is sampled only at the top of
  each PARENT-loop iteration, before a child's own processing begins — once control is inside
  `deletePlayerDevelopmentData`, it is never re-evaluated, so a blocked `DELETE` can hold the
  connection and the lock well past the configured budget with no timeout, no exception, and no
  `AdminAlert` firing (the alert only fires on the budget check itself, which a blocked-mid-child
  call never reaches).
- It extends the exposure window for the 7 FK'd tables' `FOR KEY SHARE` RI checks that
  skillars-deferred-128 AC1 was specifically written to narrow — an unbounded block re-opens exactly
  the class of exposure that story closed, just relocated from "spans the whole outer transaction"
  to "spans however long one contended `DELETE` happens to block."
- The inner `REQUIRES_NEW` transaction also holds a **second** pooled Hikari connection for its own
  duration (skillars-deferred-128's own `/bmad-code-review` "STALE ON ARRIVAL" finding,
  `application.yaml:93-99`) — an indefinitely blocked `DELETE` pins that second connection
  indefinitely too, not just the lock.

**Owner decision (this story's own creation session, `AskUserQuestion`): bound it via a new
`ConfigBounds` runtime-tunable key, mirroring `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own bounds
exactly** (default 5s, min 2s, max 120s) rather than a plain constant — this is the mechanism the
ledger bullet itself already names as the intended fix shape ("a future story bounding single-child
hold time directly (e.g. a `statement_timeout`/`lock_timeout` mirroring
`RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s approach)"), and an operator can tune it live without a
redeploy exactly as they already can for the radar-composite case.

### Tasks

1. Re-verify all line citations above against current `HEAD` before implementing.
2. Add `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS`
   (`src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java`), a new
   `BoundedKey("platform.gdpr_erase_statement_lock_timeout_seconds", 2L, 120L, false, ...)` —
   copy `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own bounds (`:255-258`) and Javadoc reasoning
   verbatim where it applies (the `min = 2L` floor sitting above Postgres's own ~1s
   `deadlock_timeout` default applies identically here — same deadlock-vs-lock-timeout
   misclassification risk this key must also avoid). Add its key to `HAS_CODE_DEFAULT` (`:304-321`)
   and the key itself to the `ALL` list (`:326-354`) — read via the 4-arg `getBoundedLong` route
   exactly like `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own call site, not the `failFast` shape.
3. Inject `ConfigService` into `GdprErasureService` (not currently a dependency — add it alongside
   the existing constructor-injected fields, `:66-90`).
4. Inside `deletePlayerDevelopmentData`'s `REQUIRES_NEW` lambda, immediately after
   `entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE)` (`:491`) and before the
   first `deleteByPlayerId`/`deleteAllByPlayerId` call (`:498`), read the bounded value
   (`configService.getBoundedLong(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L,
   2L, 120L)`) and issue `entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1,
   true)").setParameter(1, seconds + "s").getSingleResult();` — mirror
   `RecalculateComposite`'s own placement (after the lock is held, before the statements it must
   bound), not before the lock acquisition (the lock acquisition already has its own `NOWAIT`+retry
   mechanism and should not be conflated with a plain `lock_timeout`).
5. Document on `deletePlayerDevelopmentData`'s own Javadoc (`:440-486`): what this bounds (the 11
   deletes, the blob enqueue, the tombstone save — everything issued after the lock is held, inside
   this one `REQUIRES_NEW` transaction), what it does NOT bound (the lock acquisition itself, which
   `PessimisticLockRetryer`'s own budget already covers), and its relationship to
   skillars-deferred-128 AC2's cross-child `gdprEraseLockBudget` (this bounds one child's own
   worst-case hold time; that bounds how many children can be attempted in sequence — the two are
   complementary, not overlapping).
6. Confirm which exception Postgres raises on a `lock_timeout` trip against a plain `DELETE`
   (expected: `55P03`/`PessimisticLockingFailureException`-family, likely surfacing through the same
   Spring exception translation `PessimisticLockingFailureException` already does elsewhere in this
   file) — if it is genuinely the same exception class AC4's PARENT-loop catch
   (`eraseParentChildren`, skillars-deferred-128 AC4) already handles, this newly-bounded failure
   mode is automatically skip-and-continued rather than aborting the whole request; do not assume
   this without confirming it against a real Testcontainers-backed test (Tests below).

### Tests

- A `GdprErasureIT` test proving the bound is real: hold a conflicting row lock on one of the 11
  target tables (e.g. `development.radar_assessment_entries`, mirroring
  `RadarCompositeCalculationServiceConcurrencyIT`'s own Testcontainers-plus-real-threads pattern for
  asserting on lock timing) from a second connection/thread, start `erase()` for a child whose
  `deletePlayerDevelopmentData` will contend on that table, and assert the call fails within
  (bound + small margin) rather than hanging — use the seam Task 3's config-bounds addition
  provides (`@TestPropertySource`/a seeded `platform_config` row, per this project's own established
  `ConfigBounds`-plus-`@TestPropertySource` seam, not a real ~5s wait if a smaller overridden value
  is cleaner for a fast test).
- Confirm (per Task 6) which concrete exception surfaces, and add or extend a test asserting AC4's
  existing skip-and-continue catch in `eraseParentChildren` correctly absorbs it — do not assume;
  if it turns out to be a genuinely different exception type, this AC must either widen that catch
  (with the same "genuinely retryable, not benign" reasoning skillars-deferred-128 AC4 M11 already
  established) or document why it should NOT be caught there, as an explicit decision.
- A `GdprErasureIT` test (or extension of an existing one) with no contention, asserting the new
  `set_config` call introduces no observable behavior change to the existing happy-path/
  lock-released-early/atomicity test suite skillars-deferred-128 already added.

---

## AC2 — Auto-detect frontend changes so the frontend unit-test suite no longer depends on a human remembering the `frontend-tests` label

### Context

`.github/workflows/frontend-unit-tests.yml` (`skillars-deferred-104`) runs the real Vitest suite
only on `workflow_dispatch` or when a PR carries the `frontend-tests` label
(`:51-53`, `contains(github.event.pull_request.labels.*.name, 'frontend-tests')`) — confirmed
unchanged at current `HEAD`. This is a **deliberate, already-`[DECIDED]`** choice (owner decision
D2, skillars-deferred-104/108: the job is not referenced by `ci.yml`/`pr-build.yml`, not a required
status check, and `mvn verify`'s own `npm test` execution is a no-op stub) — the gap this bullet
records is not the decision itself but its **failure mode**: a PR that touches `src/frontend/**`
and genuinely re-breaks covered behavior (the ledger's own example: `slotRows`, the
`loadCoachBookingRequests` sequencing guard, `handleLogout`, the batch-basket `.startDatetime`
mapping) **can merge green** if nobody remembers to add the label. This is not hypothetical:
earlier in this same session, merging skillars-deferred-128's own PR (#216) required a manual
`git status --short | grep -i "src/frontend"` check to confirm the label was correctly *not*
needed — exactly the kind of manual step this AC should make unnecessary.

**Owner decision (this story's own creation session, `AskUserQuestion`): auto-detect via a path
filter, not promote to an unconditional required check.** An unconditional required run would cost
this job's own ~10-minute budget (two-leg `tz` matrix, `frontend-unit-tests.yml:58-69`) on every PR
regardless of whether frontend was touched — wasteful for the (currently much more common) backend-
only PR. Detecting the touched-paths instead preserves the label as a manual override (for a PR that
touches something indirectly frontend-relevant without matching the path filter) while removing the
human-memory dependency for the common case.

**No existing precedent for path-based diff detection in this repo's `.github/` workflows** (grepped
for `git diff --name-only`, `paths-filter`, `dorny`, `changed-files` — no hits). This project's own
established convention for custom CI logic is a hand-rolled bash script, not a third-party action
(see `.github/scripts/assert-context-count.sh`, `.github/scripts/container-sampler.sh`) — follow
that precedent here rather than introducing a new pinned third-party action for this.

### Tasks

1. Re-verify `frontend-unit-tests.yml`'s current trigger/gate logic against `HEAD` before
   implementing (cited above as `:39-53`).
2. Design and add a step (or a small preliminary job the `frontend-unit` job then depends on via
   `needs:`) that computes whether the PR's diff touches `src/frontend/**`, using `git diff
   --name-only` against the PR's base/head SHAs (both available on the `pull_request` event payload:
   `github.event.pull_request.base.sha` / `.head.sha`) — checked out with sufficient history
   (`fetch-depth` deep enough to diff those two SHAs directly, not a shallow single-commit checkout).
   Follow this project's existing pinned-action-by-SHA convention for `actions/checkout` (see this
   same file's own `:75` and `pr-build.yml:23` for the current pinned version) if a checkout step is
   needed for the diff.
3. Widen `frontend-unit`'s own `if:` condition (`:51-53`) to also fire when that step/job's output
   indicates a frontend-path change — keep the existing `workflow_dispatch`/label conditions as
   alternatives (an "OR" of three conditions now, not a replacement), so a human can still force a
   run via the label or manual dispatch even when the auto-detected diff would not have triggered
   one.
4. Update `docs/testing/frontend-unit-tests.md` (`:28-55`) to document the new auto-detection
   behavior alongside the existing manual-dispatch/label instructions — the label section should be
   reframed as "force a run even when the diff wouldn't auto-trigger one," not deleted (it remains a
   genuine, still-needed override path).
5. Confirm this does not change `frontend-unit-tests.yml`'s own already-`[DECIDED]` non-gating status
   (D2/D6, cited in the file's own header comment `:36-38`) — it still is not referenced by
   `ci.yml`/`pr-build.yml` and is still not a required status check; this AC only widens *when it
   fires automatically*, not what blocks a merge.

### Tests

- Verify on a real PR during implementation (this project's own established practice for
  workflow-file changes, since GitHub Actions logic cannot be meaningfully unit-tested locally):
  confirm a throwaway PR touching only `src/frontend/**` auto-triggers the job with no label
  applied, and a PR touching only backend files does not trigger it (absent the label/manual
  dispatch). Record both confirmations in the Dev Agent Record, mirroring how prior CI-file changes
  in this series (e.g. the `assert-context-count.sh` ceiling bump on skillars-deferred-128's own PR)
  cited the actual CI run that confirmed the change.
- No unit/integration test applicable — this is a CI-configuration-only change.

---

## AC3 — Replace `RegistrationEmailDurabilityIT.committedRowFor`'s full-table `findAll()` scan with a targeted repository query

### Context

`RegistrationEmailDurabilityIT.committedRowFor(String email)`
(`src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java:85-93`)
still does `envelopeEntityRepository.findAll().stream().filter(...)` — a full-table scan followed by
an in-memory filter on the nested `recipients` `@ElementCollection`, used at the one point in each
test where only the UUID-unique seeded email address is known (the registration/OTP listener
generates `sendId` internally, so it cannot be known upfront the way
`committedRowBySendId`'s call sites already know theirs — see skillars-deferred-111 AC7,
`:75-107`, which added `committedRowBySendId` for exactly that reason and left `committedRowFor` as
"the one `findAll()` … this class cannot avoid").

**That framing was accurate given the tool skillars-deferred-111 had (`findBySendId` only), but the
underlying constraint isn't actually "cannot avoid a full scan" — it's "cannot avoid looking up by
email instead of `sendId`."** `EnvelopeEntityRepository`
(`src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java`)
has no query method scoped to a recipient's email at all; a JPQL query joining the `recipients`
`@ElementCollection` on `RecipientEntity.email` (`RecipientEntity.java:24-27`, a plain `@Embeddable`
field, not a separate entity/repository) can do a single, indexed-by-composite-PK
(`envelope_entity_recipients(envelope_entity_id, email)`, `V137__envelope_entity_recipients_
composite_pk.sql`) database lookup instead of scanning and materializing every row in the table —
this is a genuine improvement, not a rationalization: the current scan grows with everything else
the shared JVM-static Postgres test database accumulates across the whole suite (`RegistrationEmail
DurabilityIT`'s own class Javadoc, `:76-84`, already names this as the specific cost), while the
composite PK gives a targeted query real index support.

### Tasks

1. Re-verify all citations above against current `HEAD` before implementing.
2. Add a JPQL query method to `EnvelopeEntityRepository`:
   `@Query("SELECT e FROM EnvelopeEntity e JOIN e.recipients r WHERE r.email = :email") List<EnvelopeEntity>
   findByRecipientsEmail(@Param("email") String email);` — a plain read, no `@Lock` (unlike
   `findBySendId`, which needs `PESSIMISTIC_WRITE` for its own production call sites;
   this method's only caller is test code asserting on already-committed state, so no lock is
   needed). Name it to read naturally at the call site and avoid colliding with any existing
   Spring Data derived-query-method convention already in this repository interface.
3. Rewrite `committedRowFor` (`:85-93`) to call the new repository method instead of
   `findAll().stream().filter(...)` — keep the existing `assertThat(rows).hasSize(1)` uniqueness
   assertion (still meaningful: it proves the UUID-unique seeded email genuinely produced exactly one
   committed row, now via a targeted query rather than a full scan).
4. Update the method's own Javadoc (`:75-84`) — the "the one `findAll()` … this class cannot avoid"
   framing is now inaccurate; replace it with the real remaining constraint (only the seeded email is
   known at this call site, not the generated `sendId`) and note the targeted-query fix, cross-
   referencing this story.
5. Confirm no other test class in this package has an equivalent avoidable `findAll()` scan this fix
   should also cover — grep `src/test/java/com/softropic/skillars/platform/notification` for
   `findAll()` and check each hit's own reasoning before deciding whether this AC's scope should
   widen; if every other hit already has a documented reason a targeted query doesn't apply (e.g.
   genuinely needs every row), leave them and note why in the Dev Agent Record.

### Tests

- `RegistrationEmailDurabilityIT`'s own existing suite (all cases that call `committedRowFor`,
  `:138`, `:161`, `:197`, `:217`) must stay green with unchanged externally-observable behavior —
  this is a pure internal-implementation change to a test helper, not a behavior change.
- Add a focused unit or slice-level test for the new `findByRecipientsEmail` query method if this
  project's convention for a single new repository query method calls for one (check sibling
  `EnvelopeEntityRepository`/similar-repository test coverage in this package for the established
  bar before deciding whether a dedicated test is expected here or whether `RegistrationEmail
  DurabilityIT`'s own existing coverage — which now exercises this method on every one of its
  `committedRowFor` call sites — is sufficient).

---

## AC4 — Standard `deferred-work.md` ledger closeout

### Tasks

1. Delete outright the fresh bullet this story closes: `deferred-work.md:3001-3022` (the GDPR erase
   statement `lock_timeout` gap — fixed by AC1). Also delete or reword its cross-reference inside
   `PessimisticLockRetryer`'s own Javadoc that names this as unresolved future work, since it is no
   longer unresolved.
2. Delete outright `deferred-work.md:2049-2061` (the `frontend-tests` label-dependency gap — fixed
   by AC2).
3. Delete outright `deferred-work.md:2198` (`RegistrationEmailDurabilityIT`'s `findAll()` fragility —
   fixed by AC3), and update the skillars-deferred-110 prune's own cross-reference to it
   (`deferred-work.md:2250`, which currently lists this bullet as "confirmed still open and
   untouched") so it does not describe stale state.
4. Grep-sweep `deferred-work.md` for any other reference to `GdprErasureService.
   deletePlayerDevelopmentData`'s missing statement timeout, `frontend-unit-tests.yml`'s label gate,
   or `RegistrationEmailDurabilityIT`'s `findAll()` scan that this story's own AC1–AC3 closes but
   that a targeted line-range delete above might miss (e.g. a narrative mention inside a `## Last
   audit:` summary section) — correct or annotate any such mention so it does not describe stale
   state, following this file's own established "narrative sections are corrected, not deleted"
   convention (see skillars-deferred-128's own AC7 for the precedent).
5. Add a new `## Last audit: <implementation date> (skillars-deferred-129 dev-story completion)`
   heading, positioned per this file's own established precedent (immediately above the sections
   whose bullets it closes), summarizing what was closed and any corrections found during
   implementation — mirror the structure of the equivalent sections in skillars-deferred-126/-127/
   -128's own closeouts (already read in full during this story's own creation).
6. Re-confirm this story's own "Out of scope" section above (the marketplace/reviews audit
   candidate, `markFailed`'s generic alerting gap, the `radar_composite_dlq` cleanup gap, and the
   `main."user"` index gap) remains correctly untouched — do not close or reword any of those four
   as part of this AC; they were deliberately left out of this story's own scope.

### Tests

- None expected — this AC is a documentation-only ledger edit.

---

## Dev Notes

- This story bundles three independently-scoped, previously-identified gaps (not a fresh audit) —
  see Provenance & Scoping above for exactly which ledger sections each AC closes and why the
  freshest same-day section alone was too thin to stand as its own story.
- AC1 and AC3 both touch files already touched by skillars-deferred-127/-128 (`GdprErasureService`,
  `ConfigBounds`) and skillars-deferred-111 (`RegistrationEmailDurabilityIT`,
  `EnvelopeEntityRepository`) respectively — read each file's own recent history/Javadoc in full
  before editing, per this project's own "read files being modified" convention; do not assume the
  citations above are still accurate without re-checking `HEAD` first, per this file's own repeated
  instruction.
- AC2 is the only AC in this story that is not a Java/Maven change — it is a GitHub Actions
  workflow-file change, verified by observing a real CI run rather than a local test. No `mvn
  verify` is run locally for this story either way, per this project's established convention
  (GitHub CI is the sole full-verification gate).
- No frontend Vue/JS source is touched by this story (AC2 only touches the CI workflow that decides
  *when* the existing frontend test suite runs, not the suite itself) — confirm via `git status
  --short` before opening the PR whether the `frontend-tests` label is needed under the *current*
  (pre-AC2) gating rules, the same manual check this story's own AC2 exists to make unnecessary for
  future stories.

### References

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` — AC1
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` — AC1
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` — AC1 (pattern to mirror)
- `.github/workflows/frontend-unit-tests.yml` — AC2
- `docs/testing/frontend-unit-tests.md` — AC2
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java` — AC3
- `src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java` — AC3
- `src/main/java/com/softropic/skillars/platform/notification/repo/RecipientEntity.java` — AC3
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC4

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-09-22: Story created via manual ledger-mining process (this project's established convention
  for `skillars-deferred-N` stories). The freshest same-day code-review deferral (skillars-
  deferred-128's own, one bullet) was too thin alone for this series' "do not create small stories"
  convention; the owner declined a fresh ad-hoc audit of `platform.marketplace`/`platform.reviews`
  (the only two modules never swept under this series' concurrency/TOCTOU lens) and instead selected
  two previously-identified, still-open, low-risk ledger items to bundle in (`AskUserQuestion`). Two
  further owner decisions taken live: (1) AC1's lock_timeout mechanism — a new `ConfigBounds`
  runtime-tunable key mirroring `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own bounds, over a plain
  constant; (2) AC2's CI fix — auto-detect frontend-path changes via a hand-rolled `git diff`-based
  step, over promoting the job to an unconditional required check. AC1's citation was corrected
  during creation (ledger cited `:436-453`; current `HEAD` has the method at `:487-527`, deletes at
  `:498-515`, drifted when skillars-deferred-128's own code-review response landed after the ledger
  bullet was written). AC3's citation confirmed partially-pre-closed by skillars-deferred-111 AC7
  (added `committedRowBySendId`), narrowing this story's own scope to the one remaining
  `committedRowFor` call site. Branch: `story/deferred-129-lock-timeout-ci-fixes` (to be created).
