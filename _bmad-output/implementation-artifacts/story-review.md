# Senior-Dev Audit — `skillars-deferred-129-gdpr-lock-timeout-ci-frontend-auto-detect-and-envelope-test-fixes`

**Reviewed at:** `HEAD = 7af5eddf` (story-creation commit; `ff49c148` = skillars-deferred-128 merged)
**Method:** every file, line citation, constraint, precedent and test file named by the story was opened and
checked against the actual source. Nothing below is inferred from the story's own prose or from
`deferred-work.md`; every claim carries a `file:line` that was read directly.
**Verdict:** **Do not implement as written.** Three findings (H1–H3) would either not deliver the AC's stated
goal or break an existing green test. Six more (M1–M10) are wrong instructions, non-existent seams, or ledger
deletions that would destroy still-open state.

---

## Summary table

| # | AC | Severity | Finding |
|---|----|----------|---------|
| H1 | AC1 | **Blocking** | `lock_timeout` is per-**statement**; the method issues ≥12 of them, so AC1's bound does not bound what AC1 says it bounds. The cited precedent already solved this and the story mirrors only half of it. |
| H2 | AC1 | **Blocking** | The second `deletePlayerDevelopmentData` call site (the PLAYER branch, `:191`) has no catch. AC1 turns a previously-slow-but-successful erasure into a hard `FAILED` with no alert. Story never mentions this call site. |
| H3 | AC3 | **Blocking** | The exact JPQL the story dictates (`JOIN`, not `JOIN FETCH`) makes two existing assertions throw `LazyInitializationException`. |
| M1 | AC1 | Medium | Task 4 misreads the precedent's placement and puts a potential DB round trip *inside* the lock window the AC exists to shorten. |
| M2 | AC1 | Medium | The named test seam (`@TestPropertySource`) cannot work here, and would also cost a Spring context against a CI-enforced ceiling. The "smaller value for a fast test" floor is 2s, not arbitrary. |
| M3 | AC4 | Medium | Task 3 deletes a ledger bullet whose *primary* claim AC3 does not touch. |
| M4 | AC4 | Medium | Task 2 deletes a ledger bullet whose core claim AC2 Task 5 explicitly preserves. Internally contradictory. |
| M5 | AC4 | Medium | Task 1 deletes a ledger bullet documenting a **second** hazard `lock_timeout` does nothing about. |
| M6 | AC1 | Medium | AC1 makes the existing `CHILD_CONTENDED` log + alert factually wrong; the file's own comment forbids exactly this without re-verification, and Task 6 asks the wrong question. |
| M7 | AC2 | Medium | "a step (or a small preliminary job)" is not a real choice — only the job form can satisfy Task 3. |
| M8 | AC2 | Medium | Two-dot `git diff` gives false positives; `workflow_dispatch` has no PR payload; a new job needs its own `permissions:`. |
| M9 | AC3 | Medium | "indexed-by-composite-PK … real index support" is false. The fix is still worth doing, for a different reason. |
| M10 | AC3 | Medium | "one call site remains" is wrong — there are two, and the second has no documented justification. |
| L1–L7 | — | Low | Citation drift, a cited migration file that does not exist, unachievable placement rule, missing `concurrency:` group, stale doc counts. |

---

## H1 — AC1's mechanism does not bound what AC1 claims it bounds (Blocking)

**Claim under audit.** AC1 Context: issuing one `set_config('lock_timeout', …, true)` bounds "the 11 deletes,
the blob enqueue, the tombstone save — everything issued after the lock is held". AC1 Tests: "assert the call
fails within (bound + small margin) rather than hanging."

**What the source says.** `lock_timeout` in Postgres applies **per statement**, not per transaction. This is not
a subtle point this codebase has yet to learn — it is written down, in the very file AC1 names as the pattern to
mirror:

> `RadarCompositeCalculationService.java:51-68` —
> *"`lock_timeout` is per STATEMENT, so at the configured ceiling (120s) the two statements per skill could each
> independently wait up to 120s — an N-skill call's cumulative worst case is `2 * N * lockTimeoutSeconds`,
> **unbounded by the per-statement timeout alone**."*

That file solves it with `CUMULATIVE_LOCK_WAIT_BUDGET` (`:66-68`) plus a spend-down loop
(`:265-284`: `Math.min(lockTimeoutSeconds, remainingLockBudget.toSeconds() / 2)`, throwing when the remainder
drops below the 2s floor). **AC1 copies the `set_config` call (`:287-289`) and omits the budget entirely.**

**Statement count inside `deletePlayerDevelopmentData` (`GdprErasureService.java:487-527`), counted from source:**

| Statement | Line | Blocking? |
|---|---|---|
| `findByIdForUpdate` (`NOWAIT` + retryer) | `:489-490` | bounded already — *before* `set_config` |
| `entityManager.refresh(…, PESSIMISTIC_WRITE)` | `:491` | re-locks a row this tx already holds → cannot block |
| `playerTimelineRepository.deleteByPlayerId` | `:498` | **1 SELECT + one DELETE per row** — see below |
| 8 JPQL bulk deletes (`slu`, `sluWeekly`, `sluApplied`, `sluTarget`, `neglected`, `baseline`, `composite`, `radarAssessment`) | `:499-506` | 8 statements |
| `performanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc` | `:507` | 1 SELECT |
| `performanceReportRepository.deleteAllByPlayerId` | `:514` | 1 |
| `homeworkCompletionRepository.deleteAllByPlayerId` | `:515` | 1 |
| `blobDeletionOutboxSupport.enqueue(childBlobKeys)` | `:520` | 1+ INSERTs |
| tombstone `save()` | `:525` | already-locked row → cannot block |

That is **≥12 independently-timeout-able statements**, so the real worst-case hold is `12 × bound`, not `bound`:

- at the story's proposed default (5s): **~60s** — **6× the `~10s` `gdprEraseLockBudget`** (`:116`) that AC1's own
  Context says this exists to stop being "silently defeated";
- at the story's proposed max (120s): **~24 minutes**, on the request thread, pinning two of the 25 Hikari
  connections (`application.yaml:181`) the whole time.

**And the count is not even fixed.** `PlayerTimelineRepository.deleteByPlayerId` (`PlayerTimelineRepository.java:10`)
is a **Spring Data derived delete** — no `@Modifying`/`@Query`, unlike all ten siblings (verified: `SluRepository.java:71-73`,
`SluWeeklySnapshotRepository.java:77-79`, `PlayerSluWeeklySnapshotAppliedRepository.java:17-19`,
`SluTargetRepository.java:42-44`, `PlayerRadarBaselineRepository.java:30-32`, `PlayerRadarCompositeRepository.java:35-37`,
`RadarAssessmentRepository.java:79-81`, `HomeworkCompletionRepository.java:18-20` all carry `@Modifying @Query`).
A derived delete loads the entities and removes them one at a time, so it contributes **one DELETE per
`player_timeline_events` row**. The worst case therefore grows with the player's timeline length. AC1's Context
calls all 11 "bulk `deleteAllByPlayerId`/`deleteByPlayerId` calls" (`:498-515`); one of them is not bulk.

**Consequence.** The AC ships something that *looks* bounded and is testable-green against a single contended
table, while the property it advertises — "one child's own worst-case hold time is bounded" — is false by an
order of magnitude. The Tests section's assertion ("fails within bound + small margin") only holds when exactly
one statement contends; it passes and proves nothing about the real bound.

**Required before implementation.** Either (a) port `CUMULATIVE_LOCK_WAIT_BUDGET`'s spend-down shape so the
*method's* total lock wait is bounded (note it is harder here than in the radar case: the deletes are not a loop,
so the budget must be threaded through ~11 call sites, or re-issued between them), or (b) state explicitly and
in the Javadoc that the bound is `N × seconds` and re-derive the default from the `~10s` budget it must respect
(e.g. a sub-second-per-statement value — which the 2s `min` floor forbids, so this route needs its own decision).
Do not leave Task 5's Javadoc claiming it bounds "everything issued after the lock is held" without a wall-clock
number.

---

## H2 — AC1 introduces a new hard-failure mode on a call site the story never mentions (Blocking)

`deletePlayerDevelopmentData` has **two** call sites, not one:

1. `GdprErasureService.java:330` — inside `eraseParentChildren`'s loop, wrapped in
   `catch (ResourceNotFoundException)` / `catch (PessimisticLockingFailureException)` (`:332-354`).
   The story's Task 6 reasons about this one.
2. **`GdprErasureService.java:191`** — the `role == SkillarsRole.PLAYER` branch, inside
   `findByUserId(userId).ifPresentOrElse(pp -> { deletePlayerDevelopmentData(pp.getId()); … })`.
   **No try/catch at all.** The story does not mention this branch anywhere — not in AC1's Context, Tasks,
   Tests, or Dev Notes.

Today, a concurrent writer holding a row lock on (say) `development.radar_assessment_entries` makes that
DELETE **block and then succeed** once the writer commits. After AC1 it **throws** — and on this path the throw
propagates straight out of `erase()`, aborting the whole erasure and routing to `markFailed`
(`:262-268`), which is `log.error` only:

```java
public void markFailed(UUID requestId) {
    gdprRequestRepository.findById(requestId).ifPresent(r -> {
        r.setStatus("FAILED");
        gdprRequestRepository.save(r);
        log.error("[GDPR_ERASURE_MARKED_FAILED] requestId={}", requestId);
    });
}
```

No `AdminAlert`, no auto-retry — and the story's own "Out of scope" section declines to reopen that
(`[DECIDED: accepted risk — skillars-deferred-127]`). So AC1, as scoped, converts a slow-but-correct Article 17
erasure into a **silent FAILED**, on the most common account shape (a self-registered player), for the most
common trigger (any coach `submitAssessment` holding a row lock on any of 11 tables).

This is a net regression in reachability, not a bound: previously the failure required exhausting
`PessimisticLockRetryer`'s ~3.2s budget on `player_profiles` specifically; now any contended row on any of the
11 tables produces it.

**Also true on the PARENT path, differently.** There the new exception *is* absorbed by `:344-352`, so the child
is skipped, `gdpr_requests.status` is set to `COMPLETED` (`:226`), and the child's development data survives
intact behind a deduplicated alert. That trade already exists for genuine `player_profiles` contention — AC1
widens its trigger surface by ~11×. The story presents "automatically skip-and-continued" (Task 6) as the
desirable outcome without noting it means *an erasure reporting COMPLETED while a child's data is untouched*.

**Required before implementation.** AC1 must state what happens at `:191` — wrap it, or accept and document the
new `FAILED`-without-alert path as an explicit decision, and reconcile it with the "Out of scope" markFailed
item.

---

## H3 — AC3's dictated JPQL breaks two currently-green assertions (Blocking)

AC3 Task 2 dictates the exact query:

```java
@Query("SELECT e FROM EnvelopeEntity e JOIN e.recipients r WHERE r.email = :email")
List<EnvelopeEntity> findByRecipientsEmail(@Param("email") String email);
```

`JOIN` — **not** `JOIN FETCH`.

- `EnvelopeEntity.recipients` is `@ElementCollection` (`EnvelopeEntity.java:33-34`) with no `fetch` attribute →
  **LAZY** by JPA default.
- `AbstractIntegrationTest` (`src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java:60-81`)
  is `@SpringBootTest(webEnvironment = RANDOM_PORT)` — **not** `@Transactional`. No persistence context spans the
  test method.
- `committedRowFor` (`RegistrationEmailDurabilityIT.java:85-93`) returns the entity **out of** its
  `transactionTemplate.execute(…)` block. It works today only because the current
  `findAll().stream().filter(e -> e.getRecipients() != null && e.getRecipients().stream()…)` (`:87-90`)
  **forces the lazy collection to initialize inside that transaction.**
- Two callers then read the collection after return:
  - `:143` — `assertThat(row.getRecipients().get(0).getFirstname())…isEqualTo("Ada")`
  - `:164` — `assertThat(row.getRecipients().get(0).getFirstname()).isEqualTo("Ada")`

A plain `JOIN` filters but does not initialize. Both assertions would throw `LazyInitializationException`.

This is not speculation — the repo documents the exact constraint, in the same package:

> `MailManagerDuplicateSendIdIT.java:124-127` — *"`EnvelopeEntity.recipients` is a lazy `@ElementCollection`
> **that cannot be read once the entity is detached** (same constraint `MailManagerRateLimitIT` documents)."*

AC3's Tests section asserts the opposite: *"must stay green with unchanged externally-observable behavior — this
is a pure internal-implementation change."* It is not; as written it is a red build.

**Fix.** Use `LEFT JOIN FETCH e.recipients` for initialization plus a separate `JOIN` (or an `EXISTS` subquery)
for the predicate — filtering on the *fetched* association is the classic JPA trap that would silently prune the
returned collection to only the matching recipient, which would still pass these two tests (one recipient per
envelope here) but is wrong in general and must not be the shipped shape.

---

## M1 — AC1 Task 4 misreads its own precedent and puts a DB round trip inside the lock window

Task 4: *"immediately after `entityManager.refresh(…)` (`:491`) and before the first `deleteByPlayerId` (`:498`),
**read the bounded value** … and issue `set_config` … — **mirror `RecalculateComposite`'s own placement**."*

`recalculateComposite` does **not** do that. It reads the config **before** taking the lock, and says why in a
comment written specifically to stop someone doing what Task 4 instructs:

> `RadarCompositeCalculationService.java:195-212` —
> *"read the lock-timeout tunable **BEFORE** taking the pessimistic lock below. `ConfigService` is TTL-cached,
> but a cache-expiry read triggers a real DB round trip (`configRepository.findAll()`) — **doing this after
> `findByIdForUpdate`/`entityManager.refresh` would put that round trip inside the transaction already holding
> the `player_profiles` pessimistic lock, for no reason.**"*

`ConfigService.ensureFresh()` → `refreshCache()` → `configRepository.findAll()` is real (`ConfigService.java:63-67`,
`:190-193`), and the TTL is 300s by default (`ConfigProperties.java:8`), so the expiry hit is a genuine round trip
on a cold-ish call. Only the `set_config` statement belongs after the lock.

Task 4's literal `5L, 2L, 120L` argument form **is** correct and matches the documented convention
(`RadarCompositeCalculationService.java:204-212`) — keep that; move the read.

---

## M2 — AC1's test seam does not exist, costs a CI-gated resource, and has a 2s floor

AC1 Tests: *"use the seam Task 3's config-bounds addition provides (`@TestPropertySource`/a seeded
`platform_config` row, per this project's own established `ConfigBounds`-plus-`@TestPropertySource` seam, not a
real ~5s wait if a smaller overridden value is cleaner for a fast test)."*

Three problems, all verified:

1. **`@TestPropertySource` cannot override a `ConfigService` value.** `ConfigService` reads only from
   `configRepository` into its own `ConcurrentHashMap` cache (`ConfigService.java:40-53`, `:190-193`). The only
   Spring property it binds is `app.config.cache-ttl-seconds` (`ConfigProperties.java:5-8`). There is no
   property-to-key bridge. The "established `ConfigBounds`-plus-`@TestPropertySource` seam" does not exist in
   this repository.
2. **The real seam is different.** `RadarCompositeCalculationServiceConcurrencyIT.java:401-411`:
   `jdbcTemplate.update("INSERT INTO main.platform_config (key, value) … ON CONFLICT (key) DO UPDATE …")`
   followed by `configService.invalidate()`. Use that.
3. **`@TestPropertySource` would also fork a Spring context**, which is gated in CI:
   `.github/scripts/assert-context-count.sh:1-20` fails the build past a ceiling on `missCount`
   (contexts actually built). Adding a property source to `GdprErasureIT` — a class already in the shared
   context — spends a slot against that ceiling for no benefit, given (1).
4. **The floor is 2s.** The 4-arg `getBoundedLong(key, default, min, max)` **falls back to `defaultValue`** on
   out-of-range, it does not clamp (`ConfigService.java:110-117`). A test seeding `1` gets `5`, not `1`. The
   existing precedent test seeds exactly `2` and asserts `elapsed >= 2s && < 15s`
   (`RadarCompositeCalculationServiceConcurrencyIT.java:229-236`). The parenthetical suggesting a "smaller
   overridden value" implies a freedom the bounds forbid.

---

## M3 — AC4 Task 3 deletes a ledger bullet whose primary claim AC3 does not close

`deferred-work.md:2198` (one physical line, read in full) is titled:

> **`RegistrationEmailDurabilityIT`'s scheduler cases operate on global repository state**

and makes **two** claims:

1. `emailRetryScheduler.retryFailedEmails()` polls the whole `envelope_entity` table (`EmailRetryScheduler:106`)
   and **"will also re-drive `FAILED`/`retry=true` rows left by other tests in the shared JVM-static Postgres"**;
2. the `committedRowFor`/`committedRowBySendId` helpers use `findAll()`, which grows with the suite.

AC3 closes half of claim (2) — the `committedRowFor` half. Claim (1), the scheduler's whole-table poll, is
untouched by this story and is the bullet's *headline*. AC4 Task 3 says **"Delete outright"**. That erases a
still-open, genuinely-unaddressed cross-test-interference hazard.

The story's Provenance also mischaracterises the bullet as *"`RegistrationEmailDurabilityIT`'s `findAll()`
fragility"* — that is the subordinate clause, not the bullet's subject.

**Fix.** Reword the bullet down to claim (1), don't delete it.

---

## M4 — AC4 Task 2 deletes a bullet that AC2 Task 5 explicitly confirms is still true

`deferred-work.md:2049-2061`, read in full. Its thesis:

> *"deferred-108 AC10 deleted six ledger bullets on the strength of that coverage; the specs do exist and are
> mutation-sensitive …, but **"closed" here means *a spec guards this*, not *CI enforces this*.**"*
> *"Revisit if/when the job is promoted to a required check."*

AC2 auto-detects frontend changes — it does **not** promote the job to a required check, and **AC2 Task 5 says so
explicitly**: *"Confirm this does not change `frontend-unit-tests.yml`'s own already-`[DECIDED]` non-gating
status … it still is not referenced by `ci.yml`/`pr-build.yml` and is still not a required status check."*
Confirmed against source — `.github/workflows/frontend-unit-tests.yml:3-7` and `pr-build.yml` (no reference).

So after AC2: a frontend regression still cannot block a merge. The bullet's stated revisit condition is
**not met**. AC4 Task 2's "Delete outright" directly contradicts AC2 Task 5.

**Fix.** Narrow the bullet to its still-true half ("a red frontend suite still does not block a merge"), delete
only the "unless someone remembers the label" sentence.

---

## M5 — AC4 Task 1 deletes a bullet documenting a second hazard AC1 does nothing about

`deferred-work.md:3001-3022`, read in full, documents two mechanisms. AC1 addresses the first. The second, at
`:3015-3018`:

> *"The same hole exists on the cheaper path — the inner `REQUIRES_NEW` **must acquire a second pooled
> connection and can wait up to `connection-timeout: 30000` (three times the whole 10s budget) without the
> deadline firing**."*

Verified: `application.yaml:178` — `connection-timeout: 30000`; `:181` — `maximum-pool-size: 25`. A Postgres
`lock_timeout` has no effect whatsoever on HikariCP connection acquisition. AC1's Context mentions the second
connection only in the "an indefinitely blocked DELETE pins it too" sense — it never carries over the 30s
acquisition wait, and Task 5's Javadoc scope ("what it does NOT bound") lists only the lock acquisition.

AC4 Task 1 says "Delete outright `deferred-work.md:3001-3022`." That erases a documented, still-open hazard.

**Fix.** Reword to retain the pool-acquisition half, or add it to AC1 Task 5's explicit "does NOT bound" list and
re-file it as its own bullet.

---

## M6 — AC1 makes the existing `CHILD_CONTENDED` handler's log and alert factually wrong; Task 6 asks the wrong question

Task 6 asks only *"is it genuinely the same exception class AC4's PARENT-loop catch already handles?"* — and
treats "yes" as the good answer. But the file itself says class identity is precisely **not** sufficient:

> `GdprErasureService.java:322-329` —
> *"(L5, story review 2026-09-22): both catches below are precise **ONLY by construction** — today the sole
> thrower inside `deletePlayerDevelopmentData` reachable this way is the `orElseThrow` for
> `ResourceNotFoundException`, and `PessimisticLockingFailureException` can only surface once
> `PessimisticLockRetryer`'s own retry budget is exhausted. **If `deletePlayerDevelopmentData`'s shape ever
> changes (e.g. a repository call deeper in the delete chain starts throwing either exception for an unrelated
> reason), that new throw must NOT be silently swallowed here as "child vanished/contended" without first
> re-verifying this assumption still holds.**"*

AC1 is exactly that change. And the handler it lands in emits a message that becomes untrue
(`GdprErasureService.java:348-353`):

```java
log.warn("[GDPR_ERASURE] PARENT-branch child playerId={} lock was genuinely contended "
        + "and its retry budget was exhausted — skipping …", …);
raiseErasureAlert(requestId, "CHILD_CONTENDED");
```

For a downstream `DELETE` timing out on `radar_assessment_entries`, the retryer was never involved and
`player_profiles` was never contended. Operators reading `CHILD_CONTENDED` would investigate the wrong lock.
Note also the alert is **deduplicated per `requestId`** (`:390-396`), so the two now-conflated causes cannot be
told apart even by count.

**Fix.** Either add a distinct `reason` (e.g. `CHILD_DELETE_LOCK_TIMEOUT`) with its own catch discriminating on
cause — the codebase already knows the cause differs per SQLSTATE
(`RadarCompositeCalculationService.java:160-167`: `55P03` → cause `org.hibernate.PessimisticLockException`;
`40P01` → cause `org.postgresql.util.PSQLException`) — or update the log/alert text and the L5 comment to cover
the new thrower. Task 6 should be rewritten to ask "does the existing handler's *semantics* still hold", not
"is it the same class".

*(Note on Task 6's premise: the precedent's empirical confirmation at
`RadarCompositeCalculationServiceConcurrencyIT.java:213-228` was for a **native** query. AC1's timeouts arise on
JPQL `@Modifying` bulk deletes and on a derived delete's flush — a different Hibernate path. Task 6 is right to
demand empirical confirmation; it just needs to confirm it for all three shapes, not one.)*

---

## M7 — AC2 Task 2's "a step (or a small preliminary job)" is not a real choice

Task 2 offers either. Task 3 then says *"Widen `frontend-unit`'s own `if:` condition (`:51-53`) to also fire when
that step/job's output indicates a frontend-path change."*

A **job-level `if:`** is evaluated before the job's own steps run. It cannot reference `steps.*` of that same job.
Only the separate-job form (`needs.detect.outputs.frontend == 'true'`) can satisfy Task 3. The "step" alternative
would force the job to always start — spinning up both matrix legs and the checkout — and guard each later step
individually, which partially defeats the stated cost rationale.

**Fix.** Delete the "step" option from Task 2; specify the `needs:`/`outputs:` job form.

---

## M8 — AC2's diff mechanics have three concrete gaps

1. **Two-dot vs three-dot.** Task 2 says *"using `git diff --name-only` against the PR's base/head SHAs …
   to **diff those two SHAs directly**"*. `git diff A B` is a two-dot diff: it includes everything that landed on
   `master` since the branch point. A backend-only PR opened before someone else merged a frontend change would
   be reported as touching `src/frontend/**`. Use `git diff --name-only "$BASE...$HEAD"` (three-dot, merge-base)
   or `git merge-base` explicitly. Not a merge-safety bug (it errs toward running the job) but it silently
   re-creates the ~10 CI minutes the AC's rationale is built on avoiding.
2. **`workflow_dispatch` has no PR payload.** `github.event.pull_request.base.sha` / `.head.sha` are empty on
   manual dispatch (the workflow triggers on both — `frontend-unit-tests.yml:41-44`). The detector job needs its
   own `if: github.event_name == 'pull_request'` guard, or the script must short-circuit. Task 2 does not
   mention it; Task 3's three-way OR only protects the *consumer*, not the detector.
3. **Per-job `permissions:`.** This workflow declares permissions at both workflow level (`:46-47`) and job level
   (`:56-57`). A new detector job needs its own `permissions: contents: read` block to match the file's
   convention.

The story's claim that no precedent exists is **correct** — verified:
`grep -rn "paths:|paths-ignore|git diff --name-only|dorny|changed-files" .github/` returns zero hits. So is the
hand-rolled-bash precedent (`.github/scripts/assert-context-count.sh`, `container-sampler.sh` — both exist).
What the story never records is *why the native `on.pull_request.paths` filter was rejected* — worth one line,
since the reason (it is an AND at trigger level, so it would break the label override for non-frontend PRs) is
real and would otherwise be re-litigated at implementation time.

---

## M9 — AC3's index justification is false (the fix is still right, for a different reason)

AC3 Context: *"a single, **indexed-by-composite-PK** (`envelope_entity_recipients(envelope_entity_id, email)`)
database lookup … the composite PK gives a targeted query **real index support**."*

Verified against the actual schema:

```sql
-- V138__baseline_schema.sql:2362-2363
ALTER TABLE ONLY main.envelope_entity_recipients
    ADD CONSTRAINT envelope_entity_recipients_pkey PRIMARY KEY (envelope_entity_id, email);
```

and `grep "CREATE INDEX" src/main/resources/db/migration/*.sql | grep -i recipient` → **no hits.** The composite
PK's B-tree is the table's only index, and `envelope_entity_id` is its **leading** column. A predicate on
`email` alone cannot use it — Postgres will seq-scan `envelope_entity_recipients`.

The ledger itself hints at this: the closed bullet at `deferred-work.md:107-108` says only *"the baseline schema
now carries a composite PK"* — the `/index` half of the original "`envelope_entity_recipients` missing PK/index"
finding (`deferred-work.md:2254`) was quietly dropped.

**The fix is still worth doing**, just not for the stated reason: it avoids `findAll()` materialising *every*
`EnvelopeEntity` in the table into the persistence context and hydrating each one's `recipients` collection.
That is the real cost, and it is a large one in a shared JVM-static test database. Correct the rationale so a
future reader does not trust a non-existent index — and if index support is actually wanted, that is a separate
migration, which this story does not propose.

---

## M10 — AC3's "one call site remains" is wrong; there are two identical ones

AC3 Context: *"**but one call site remains**: `committedRowFor(String email)`."*

`grep -rn "findAll()" src/test/java/com/softropic/skillars/platform/notification/` returns a second,
**structurally identical** helper in the same package:

```java
// VideoModerationAdminAlertEnvelopeIT.java:159-169
private EnvelopeEntity committedRow() {
    List<EnvelopeEntity> rows = new TransactionTemplate(transactionManager).execute(status ->
        envelopeEntityRepository.findAll().stream()
            .filter(e -> e.getRecipients() != null && e.getRecipients().stream()
                .anyMatch(r -> adminEmail.equals(r.getEmail())))
            .toList());
    assertThat(rows).as("exactly one COMMITTED envelope row for this test's admin recipient").hasSize(1);
    return rows.get(0);
}
```

Same anti-pattern, same email-keyed filter, same package, **no documented reason** — it would be fixed by the
same new repository method. AC3 Task 5 frames widening as conditional (*"if every other hit already has a
documented reason … leave them"*), which understates it: this hit plainly has none. Fold it into AC3's scope
rather than leaving it to a judgement call.

(The third hit, `MailManagerDuplicateSendIdIT.java:118-121` `rowCountForSendId`, **does** have a real reason:
it *counts* rows for a `sendId`, and the existing `findBySendId` returns a single entity under
`PESSIMISTIC_WRITE` (`EnvelopeEntityRepository.java:20-21`). Leave it, and say so.)

---

## Low-severity findings

**L1 — a cited migration file does not exist.** AC3 cites
`V137__envelope_entity_recipients_composite_pk.sql`. The migrations directory starts at `V138__baseline_schema.sql`
— V137 was squashed into the baseline. The constraint exists (`V138:2362-2363`); the file does not. The story's
Provenance claims every citation was re-verified *"by direct `Read`/`Grep` against the actual source files"*;
this one could not have been. (`RecipientEntity.java:9-14`'s own Javadoc carries the same stale reference —
worth correcting while AC3 is in that file.)

**L2 — "constructor-injected fields" is imprecise.** `GdprErasureService` uses Lombok `@RequiredArgsConstructor`
(`:62`); there is no hand-written constructor to add a parameter to — just add a `private final ConfigService`
field in the `:66-90` block. Verified safe: `grep -rn "new GdprErasureService(" src/` → no hits, so no test
constructs the bean manually. No module-boundary concern either — `ConfigService` is already injected across
`video`, `security`, `development`, `payment`.

**L3 — wrong test path.** AC1's Tests reference `GdprErasureIT` as if under `…/admin/service/`. It is at
`src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (1316 lines).

**L4 — AC4 Task 5's placement rule is unachievable.** It says to place the new `## Last audit:` heading
*"immediately above the sections whose bullets it closes"*. This story closes bullets in three sections:
`deferred-work.md:2041` (deferred-108), `:2195` (ses-1-4), `:2994` (deferred-128). One heading cannot sit
immediately above all three. The precedent it names (`:2757`, the deferred-128 closeout) worked only because its
sections (`:2840`, `:2974`, `:2994`) were all below it. Pick one position and say so.

**L5 — AC2 has no `concurrency:` group, and amplifies `labeled` events.** `frontend-unit-tests.yml` has no
`concurrency:` block (contrast `pr-build.yml:7-9`, which has `cancel-in-progress: true`). After auto-detection,
every push to a frontend-touching PR queues a fresh 2-leg 10-minute run with no cancellation of the previous
one. Worse, the workflow triggers on `types: [opened, labeled, synchronize, reopened]` (`:43-44`): today a
`labeled` event on a PR without `frontend-tests` is a free skip; after AC2, adding **any** unrelated label to a
frontend-touching PR fires a full run. Given the AC's entire justification is CI minutes, add a `concurrency:`
group in the same change.

**L6 — a stale doc count AC4's sweep will miss.** `ConfigResourceIT.java:236-238` documents
*"`platform.radar_composite_lock_timeout_seconds` is one of the **4** `HAS_CODE_DEFAULT` keys
`V139__baseline_seed_data.sql` never seeded"*. AC1 makes it 5. AC4 Task 4's grep-sweep is scoped to
`deferred-work.md` only. (No test asserts the count numerically —
`ConfigBoundsEnumCoverageTest.java:112-121` only checks set containment and uniqueness — so this is doc drift,
not a build break.)

**L7 — minor citation drift.** `application.yaml:93-99` is cited for the "STALE ON ARRIVAL" second-connection
finding; that block actually begins at `:97`. `RecipientEntity.java:24-27` is cited for the `email` field; it is
`:24-25`.

---

## Citations that check out (verified, no action needed)

Recorded so a re-reviewer does not repeat the work:

- `GdprErasureService.java:487-527` (method), `:488` (`executeWithoutResult`), `:489-491` (lock+refresh),
  `:498-515` (the 11 delete calls), `:517-520` (blob enqueue), `:522-525` (tombstone), `:99-116`
  (`gdprEraseLockBudget`, `~10s` default), `:66-90` (injected fields) — **all accurate.**
- The deadline is sampled only at the top of each PARENT-loop iteration (`:312`) and never re-evaluated inside a
  child — **accurate**, and the alert only fires on that check (`:316`), so a mid-child block is invisible.
  AC1's core hazard description is correct.
- `RadarCompositeCalculationService.java:287-289` (`set_config`) — accurate.
- `ConfigBounds.java:255-258` (`RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`, bounds `2L, 120L, false`), `:304-321`
  (`HAS_CODE_DEFAULT`), `:326-354` (`ALL`) — **all accurate.** Adding to `HAS_CODE_DEFAULT` correctly avoids a
  `ConfigStartupAssertion` ERROR for an unseeded key (`ConfigStartupAssertion.java:83-106`), and the key really
  is live-tunable without a redeploy — `ConfigService.updateConfig` upserts unseeded `HAS_CODE_DEFAULT` keys
  (`:213-234`, `createOrReadBack` at `:249`). No Flyway seed needed.
- `application.yaml` `connection-init-sql: "SET TIME ZONE 'UTC'"` (`:184`), no global `lock_timeout` — accurate.
- `PessimisticLockRetryer.java:40-64` — the ~3.2s budget and the "a future story bounding single-child hold time
  directly (e.g. a `statement_timeout`/`lock_timeout` mirroring `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s
  approach)" cross-reference AC4 Task 1 targets both exist, verbatim.
- `frontend-unit-tests.yml:51-53` (gate), `:58-69` (tz matrix), `:75` (pinned checkout `@3d3c42e5…` v7.0.1),
  `pr-build.yml:23` (same pin), `:3-7`/`:36-38` (D2/D6 non-gating header) — **all accurate.**
- `docs/testing/frontend-unit-tests.md:28-55` covers the non-gating statement and the label instructions AC2
  Task 4 must reframe — accurate.
- `RegistrationEmailDurabilityIT.java:75-84` (Javadoc), `:85-93` (`committedRowFor`), `:94-107`
  (`committedRowBySendId`), call sites `:138`, `:161`, `:197`, `:217` — **all accurate.**
- `EnvelopeEntityRepository.java` has no email-scoped method — accurate. `findBySendId` is
  `@Lock(PESSIMISTIC_WRITE)` (`:20-21`), so AC3 Task 2's "no `@Lock` needed for the new one" is right.
- `deferred-work.md:3001-3022`, `:2049-2061`, `:2198`, `:2245-2251`, `:2250` — all resolve to the bullets the
  story names, and none carries a `[CLOSED]`/`[DECIDED]` tag. The correction of the stale `:436-453` citation to
  `:487-527` is itself correct.
- The "Out of scope" section's four items are all genuinely out of scope and correctly not reopened.

---

## Recommended disposition

1. **AC1 — rework before dev.** Resolve H1 (cumulative bound or an honest `N × seconds` number), H2 (the `:191`
   call site), M1 (move the config read before the lock), M2 (real test seam), M6 (alert/log semantics).
   As it stands, AC1 would ship a bound that does not bound, plus a new silent-failure path on the most common
   account shape.
2. **AC2 — tighten before dev.** M7 (job, not step), M8 (three-dot diff, dispatch guard, permissions), L5
   (`concurrency:` group). The AC's direction and its "no existing precedent" research are sound.
3. **AC3 — fix the query, correct the rationale, widen the scope.** H3 (`JOIN FETCH`), M9 (index claim), M10
   (second call site), L1 (V137).
4. **AC4 — change all three "delete outright" instructions to targeted rewordings.** M3, M4, M5 each delete
   still-open state; M4 also contradicts AC2 Task 5. Resolve L4's placement ambiguity and add `ConfigResourceIT`
   to the sweep (L6).
