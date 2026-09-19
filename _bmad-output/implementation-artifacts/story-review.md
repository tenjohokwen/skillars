# Senior-Dev Audit — `skillars-deferred-124-strike-lock-contention-outbox-resilience-and-schema-fixes`

**Reviewed:** 2026-09-19
**Reviewed against:** working tree at `b82392ab` (story-creation commit; production code identical to
`master@3bba9f8d`, the tip the story says it verified against)
**Method:** every line citation, every named class/method/constraint, every claimed precedent and every
claimed test-file list in the story was opened and checked against the actual source. Findings below are
only those I could tie to a file I read; where I suspected a defect and the source proved the story right,
I have said so explicitly in §3 rather than leaving a silent near-miss that a later reader re-raises.

**Verdict:** the story's *diagnoses* are largely sound — AC1 in particular is a real, correctly-reasoned
bug and the fix it prescribes is the right one. The defects are concentrated in the **task and test
instructions** (several are not implementable as written, one duplicates an existing test), in **two
factual claims that will be written into permanent documentation**, and in the **ledger closeout**, where
the prescribed edit would destroy still-relevant history. 4 high, 8 medium, 6 low.

---

## 1. High-severity findings

### H1 — AC1 Task 4 asks for a test that already exists, and that test is a latent flake today

`src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeConcurrencyIT.java:58-114`
already is the test AC1 Task 4 specifies. It:

- runs **two concurrent `issue()` calls for the same coach** (`race(...)` at `:105-114`),
- with **different bookings** (`UUID.randomUUID()` per thread, `:113`),
- released together from a `CountDownLatch` start gate (`:73-84`) — exactly the "latch-based concurrency
  pattern" AC1 Task 4 says to mirror "*if that class still exists*",
- and asserts **both sides succeed**: `a.get(30, SECONDS)` / `b.get(30, SECONDS)` (`:84-85`) would raise
  `ExecutionException(PessimisticLockingFailureException)` for the loser, and `:96-101` asserts
  `strikeCount == 6` ("both concurrent strike rows persisted on top of the 4 seeded").

The story's Task 4 was drafted as if this coverage might not exist. It does, and it is load-bearing for
the audit, because it inverts two of the story's own statements:

- **"New regression test proving the fix"** is the wrong instruction. The correct instruction is to
  *tighten* the existing IT (add the deterministic interleave — see H2) rather than add a second class,
  which would also add a second Spring context against a CEILING of 43 (`pr-build.yml:72`).
- **AC1 Task 6's "zero regressions expected"** understates the situation in the *opposite* direction. If
  AC1's diagnosis is correct — and I believe it is (§3) — this IT is **currently flaky on master**: when
  both threads complete the `em.flush()` at `PessimisticLockRetryer:132` before either reaches
  `findByIdForUpdate`, both exhaust the retry budget and both `Future.get()` calls throw. The window
  between the two is one `SAVEPOINT` round-trip (`PessimisticLockRetryer:134`), which two latch-released
  threads doing identical work will land inside a meaningful fraction of the time.

**Action:** replace Task 4 with "add a deterministic interleave to the existing
`ReliabilityStrikeConcurrencyIT`" and add a task to check that IT's CI history for intermittent failures
before starting — if it has never flaked, the flush→lock window is narrower than the analysis predicts
and that fact needs to be reconciled before Task 5's mutation check is trusted.

### H2 — AC1 Task 5's "fails deterministically" is not achievable with the prescribed test shape

Task 5 says: "temporarily revert the ordering … and confirm the new test fails **deterministically**".
With a latch at *thread start*, it cannot. The bug requires both threads to be between two specific
statements simultaneously:

```
PessimisticLockRetryer.java:132   entityManager.flush();        // <-- INSERT fires, FOR KEY SHARE taken
PessimisticLockRetryer.java:133-134  setSavepoint()
ReliabilityStrikeService.java:98   findByIdForUpdate(coachId)   // <-- FOR UPDATE NOWAIT attempted
```

Both threads must pass `:132` before either reaches `:98`. A start-gate latch synchronises them ~5 DB
round-trips earlier (persist → 2× `configService.getBoundedLong` → flush), so the outcome is a race, not
a guarantee. Task 4's own wording ("both starting their transaction before either reaches the lock")
describes a condition the prescribed mechanism does not produce.

The seam has to sit inside the flush→lock gap — e.g. a `@MockitoSpyBean CoachProfileRepository` whose
`findByIdForUpdate` blocks on a barrier on first entry per thread, or a spy on `PessimisticLockRetryer`.
Either introduces a new bean-override set, which is precisely the `assert-context-count.sh` ceiling-43
risk the story's own Dev Notes flag. **The story needs to state the seam and pre-authorise the ceiling
bump, or downgrade Task 5 from "deterministic" to "probabilistic, run N times".**

### H3 — AC8's `Delete (AC3)` row would destroy still-relevant closed history; the Provenance's description of that ledger section is wrong

Provenance says of the deferred-122 section: "**1 bullet remains open** (the `main."user"` schema-width
divergence); **its sibling bullet in the same section** was already closed at source by deferred-123's own
review."

There is no sibling bullet. `deferred-work.md:2439-2441` is a section header followed by **exactly one
bullet**, and that single bullet contains *all* of the following in one block:

1. the `spring.jpa.generate-ddl: true` root cause with the **decompiled three-artifact chain**
   (`HibernateProperties.getAdditionalProperties` → `HibernateJpaVendorAdapter.getJpaPropertyMap` →
   `AbstractEntityManagerFactoryBean`);
2. the **pre-removal audit** record ("every `@Table` entity has a `CREATE TABLE` in a Flyway migration…");
3. the **`main.user_aud` CHECK-constraint half, marked CLOSED**;
4. the still-open `main."user"` width half — the only part AC3 actually closes.

AC8 Task 2's disposition table says **`Delete (AC3)`** for this row. Executing that literally deletes
(1)–(3) as well — including the only ledger record of *why* auto-DDL was ever on and what was audited
before it was removed. That narrative is the justification for AC3 itself; deleting it in the same commit
that relies on it is self-defeating, and this file has a recorded history of exactly this failure mode
(`deferred-work.md:2366-2373` and `:2397-2403` are two prior "deleted a bullet wholesale by mistake,
restored" self-corrections).

**Action:** change that row to "**edit in place** — strike the `main."user"` width sentence, keep the
root-cause, pre-removal-audit and CLOSED-half text; annotate `[CLOSED by skillars-deferred-124 AC3]`
on the width clause." And correct the Provenance paragraph: one bullet, two halves, not two bullets.

### H4 — AC7 prescribes documentation that contradicts this repo's own documented Flyway behaviour

AC7 Task 2(b)/(c) instruct the dev to write into `migration-conventions.md`:

> (b) the expected failure mode (migration times out against `ACCESS EXCLUSIVE`, aborts, **leaves a failed
> `flyway_schema_history` row**); (c) the recovery step (**delete the failed history row**, retry)

`docs/deployment/migration-conventions.md:143-147` already states the opposite scoping:

> "If this were run through Flyway rather than by hand, a failed **non-transactional** migration also
> leaves a failed row in `flyway_schema_history` that blocks the next deploy until **`flyway repair`**
> removes it"

Every migration AC7 is about (`V144`-shaped `ALTER TABLE`, and AC3/AC4's own new ones) is an ordinary
**transactional** Flyway migration on PostgreSQL — no `.sql.conf` sidecar, and the repo has established
(same doc, `:99-117`) that the sidecar is deliberately not used here. A `lock_timeout` abort inside a
transactional migration rolls the whole script back, schema-history bookkeeping included; there is no row
to delete and the recovery step is "re-run the deploy".

Two problems compound: the *mechanism* is wrong for the transactional case, and even for the case where a
failed row genuinely does exist, this repo's documented tool is **`flyway repair`**, not a hand-written
`DELETE`. Writing (b)/(c) as specified puts a contradiction into the one document whose purpose is to be
the authority on this, two sections apart.

**Action:** rewrite (b)/(c) as "the migration aborts and rolls back cleanly (transactional DDL on
PostgreSQL); retry the deploy. If a non-transactional migration is ever introduced here, `flyway repair`
— not a hand `DELETE` — is the recovery, per §4's failure-recovery note." And verify against the actual
Flyway 11.7.2 behaviour on this project rather than against the ledger bullet, which carries the same
error (`deferred-work.md:2471-2472`).

---

## 2. Medium-severity findings

### M1 — AC2's fix converts a batch-abort into an un-dead-letterable poison pill, and the story asserts the opposite

AC2 Task 4 forbids any release path in the new catch and states recovery is "already correct, per AC3's
own design". Checked against `VideoDeletionOutboxRepository:57-64`:

```sql
UPDATE main.video_deletion_outbox
SET status = 'PENDING', claimed_at = NULL
WHERE status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)
```

`resetStaleClaimed` **does not touch `attempts` and does not touch `next_retry_at`**. So a row abandoned
by the new catch is reset to `PENDING` with its attempt counter unchanged and `next_retry_at` still in the
past → immediately re-eligible → claimed → throws → abandoned → reset 20 min later → forever. It never
reaches `max_attempts`, never becomes `DEAD`, and logs at `ERROR` (Task 1) on every cycle indefinitely.
`RadarCompositeDlqRepository` is structurally identical.

This is not a regression the fix introduces out of nowhere — the ledger bullet already names it ("A
permanently-throwing row also buries the rest of its batch behind it every window",
`deferred-work.md:2480-2481`). But AC2 closes only the *burying* half and AC8 then marks the whole bullet
**Delete**, recording it as fully resolved. Either route the unexpected exception through `handleFailure`
(nested try, so a `handleFailure` bug still cannot abort the batch — which was AC2's stated motivation
anyway), or keep the log-only catch and (a) say in Task 4's comment that a poison row cycles indefinitely
without dead-lettering, and (b) downgrade the AC8 row from `Delete` to a `[DECIDED]` annotation naming the
residual.

### M2 — AC4 never clears `claimed_by`, breaking the invariant Task 3 tells the dev to mirror

`VideoDeletionOutbox.claimedAt`'s Javadoc (`VideoDeletionOutbox.java:53-59`) documents:

> "stamped by `claimPendingBatch` … and **cleared back to `null` on every transition out of `CLAIMED`**
> (completion or either failure outcome)"

Every query honours it: `completeClaimed` (`:101-106`), `failClaimed` (`:110-118`), `releaseClaimed`
(`:77-82`) and `resetStaleClaimed` (`:59-64`) all `SET claimed_at = NULL`. AC4 Task 3 says to add
`claimedBy` "mirroring `claimedAt`'s existing Javadoc-and-`@Column` shape", but Tasks 4–6 only add the
stamp and swap the predicates — **nothing in the AC clears `claimed_by`**. Result: a `COMPLETED`/`DEAD`/
re-`PENDING` row permanently carries the UUID of whichever run last touched it.

Not a correctness break (every identity predicate is also gated on `status = 'CLAIMED'`), but it silently
falsifies a documented invariant the same AC is instructing the dev to replicate, and it makes the column
useless for the forensic question it exists to answer ("which run owns this row *now*"). Add `claimed_by
= NULL` to all four transitions and state it in the entity Javadoc.

### M3 — AC7's "which tables are actively polled" list is materially incomplete

AC7 Task 2(a) narrows the doc addition to two tables (`main.video_deletion_outbox`,
`development.radar_composite_dlq`) and instructs the dev to grep only for `@Scheduled` classes touching
*those two*. The codebase has **44 `@Scheduled` methods across 36 classes**. The ledger bullet AC7 closes
explicitly generalises: "Applies to any future migration touching an actively-polled table, **not just
V144**" (`deferred-work.md:2471-2472`).

A two-table list in a convention doc reads as exhaustive and will be treated as such by the next author
writing a migration against `main."user"` (swept by `UserAdminService.removeNotActivatedUsers`),
`payment.*` (`PaymentPendingSweeper`), the moderation tables, or any other polled table. Either enumerate
all polled tables or — better, since any list goes stale — state the rule structurally ("before altering
any table, grep `@Scheduled` for a poller that owns it") and use the two outbox tables only as worked
examples.

### M4 — AC3 does not consider the narrowing alternative, and inherits a self-contradicting risk framing

AC3 fixes the divergence by widening the DB to `varchar(255)` to match what Hibernate left behind. The
opposite fix — `@Column(length = 20)` on `User.skillarsRole`/`verificationStatus`, preserving `V138`'s
declared intent (`V138__baseline_schema.sql:1289-1290`) — is never mentioned.

The ledger's stated blocker for narrowing is "narrowing a populated, live column carries real risk (a
value beyond 20 chars would fail the ALTER)", but the *same bullet's closing sentence* removes it: "per
skillars-deferred-117's owner decision **no production deploy has ever happened**, so the only 'pre-fix
databases' in existence are development and CI ones." The story quotes the first half (as its "why this
needs its own migration, not a drive-by fix") and repeats the second half elsewhere in Provenance,
without noticing they cancel. The longest value in either enum is `PENDING_VERIFICATION`-class at 20
chars — checkable in one grep of `SkillarsRole`/`SkillarsVerificationStatus`.

This may still land on "widen" (it matches `V145`, it is the lower-risk direction, and an entity-side
`length` does nothing to a `varchar(255)` column that already exists). But it is a genuine fork the story
presents as settled, and the "evidence pass" the ledger asked for is exactly where it should be decided.
**Add a task: enumerate both enums' longest constant, state the chosen direction and why.**

### M5 — AC3 conflates two different Hibernate DDL mechanisms in its CHECK-constraint investigation

AC3 tells the dev to check for "an unnamed inline `CHECK` from Hibernate's own DDL (**the same mechanism
`V145`'s header describes for the audit table**)". Those are not the same mechanism:

- `main.user_aud` had **no** `skillars_role`/`verification_status` columns, so `hbm2ddl=update` emitted
  `alter table … add column … check (…)` — the check rides along with the column creation. That is what
  `V145:5-10` and `UserEnversAuditGapIT:31-35` recorded empirically.
- `main."user"` **already had** both columns (`V138:1289-1290`). Hibernate's only possible action there is
  `alter column … set data type` — which carries no `CHECK` clause. I found no `user_skillars_role_check`
  / `user_verification_status_check` in `V138` (`grep 'CONSTRAINT user_'` returns only PK/unique/FK rows).

So the likely finding is **no CHECK exists on `main."user"` in any environment**, and adding one would
create a *new* divergence in the opposite direction (Flyway-built DBs constrained, pre-fix DBs not) —
the inverse of what AC3 is for. Task 3's "if warranted" saves a careful dev, but the narrative points
the other way. **Reword the investigation to state that `V145`'s precedent does not transfer, and that
the expected answer is "no constraint present"** — with the `pg_constraint` check as confirmation rather
than discovery.

### M6 — AC5's durability test is not implementable as described

AC5 Task 5: "simulate the outer transaction failing *after* `issue()` returns but *after* the new
`self.recordManualStrikeAudit` call has also returned (e.g. **by throwing from a point after both calls,
inside the same test method**)".

In `AdminCoachEnforcementService.issueManualStrike` (`:277-311`) the only statements after both calls are
`log.info` (`:309`) and `return strike.getId()` (`:310`). There is no point after both calls to throw
from, and the test method is *outside* the `@Transactional` boundary — by the time it regains control the
outer transaction has already committed, so throwing there rolls nothing back.

The implementable shape is: have the test open its own transaction (`TransactionTemplate`, `REQUIRED`),
call `issueManualStrike` so it *joins* that transaction, then mark it rollback-only / throw. The
`REQUIRES_NEW` audit write then survives and a post-rollback `jdbcTemplate` read proves it. Worth noting
`ManualStrikeIT` does use `JdbcTemplate` throughout (`:49`, `:126`, `:172-186`), so the story's cited
assertion precedent is correct even though its failure-injection mechanism is not.

### M7 — AC6/AC7 dispositions are inconsistent with AC8's own treatment of the identical shape

AC8 Task 2's table marks:

| Item | Disposition | Shape |
|---|---|---|
| Envers null `verificationStatus` | `[DECIDED: accepted risk]`, **not deleted** | accepted risk + doc comment |
| `deleteStrike` precision mismatch (AC6) | **Delete** | accepted risk + doc comment |
| V144-vs-poller race (AC7) | **Delete** | accepted risk + doc in `migration-conventions.md` |

All three are "not fixed, documented, revisit if X changes". AC7 even states its own revisit trigger
("revisited if/when this project's own 'no production deploy has ever happened' premise changes") — and
then deletes the only ledger entry that would surface that trigger. Either all three get `[DECIDED]`
annotations or the convention needs stating; as drafted the table applies two different rules to one
shape and loses two revisit triggers.

### M8 — AC8 leaves two ledger section headers orphaned, with no instruction

The table deletes all 7 bullets of `## Deferred from: code review of skillars-deferred-123…`
(`deferred-work.md:2451`) except one annotated bullet, and the sole bullet of
`## Deferred from: code review of skillars-deferred-122…` (`:2439`). The deferred-122 header, and the
deferred-123 header's italic preamble (`:2453`, "_Four-layer review (Blind Hunter, Edge Case Hunter,
Acceptance Auditor, `txn-and-concurrency-audit`)…_"), are not mentioned anywhere in the story.

Given H3 changes the deferred-122 row to an in-place edit, that header survives. But the story should say
explicitly what happens to each header and preamble — this is the file where "removed the whole section
header wholesale rather than pruning bullet by bullet" is a *twice-recorded* past mistake (`:2366-2373`,
`:2397-2403`), and AC8 Task 2's post-edit reconstruction check only covers the two `[DECIDED]` bullets,
not the headers.

---

## 3. Claims I suspected were wrong and verified are right — do not re-raise

Recording these so the next reviewer does not spend the same time, and so none is mistaken for an
unexamined gap.

- **AC1's core diagnosis is correct in every mechanical detail.**
  `coach_reliability_strikes_coach_id_fkey` is at `V138__baseline_schema.sql:4427` exactly, and does
  reference `marketplace.coach_profiles(id)`. `CoachReliabilityStrike` uses `@GeneratedValue(UUID)`
  (`:23-25`), so `save()` does not INSERT — the INSERT is genuinely deferred to
  `PessimisticLockRetryer:132`'s flush, which is genuinely *before* the savepoint at `:133-134`, so
  rollback-to-savepoint at `:150` cannot release the FK lock. PostgreSQL's row-lock matrix does put
  `FOR UPDATE` in conflict with `FOR KEY SHARE`, and a transaction never blocks on its own held lock —
  so the fix does structurally eliminate the mutual lock-out. **All line citations (`:50`, `:51-55`,
  `:62`, `:64-67`, `:77`, `:98`, `:115`) are exact at HEAD.**
- **AC1's rejection of the "take the savepoint before the flush" alternative** (the ledger's other
  suggested fix) is correct and well-argued — that ordering is load-bearing for every other
  `withBoundedRetry` caller per `PessimisticLockRetryer:31-35`.
- **AC1's autoflush argument holds.** `countByCoachIdAndCreatedAtAfter` is a derived query over
  `CoachReliabilityStrike`, so its query space overlaps the pending INSERT and `FlushMode.AUTO` flushes
  before it — the save-after-lock ordering is still counted by `:115`.
- **AC3's premise that `hbm2ddl=update` widened the live columns is plausible and I could not falsify
  it.** Hibernate ≤6.1's schema migrator only *added* missing columns, which would have made the premise
  false; Hibernate 6.2+ (`AbstractSchemaMigrator` / `ColumnDefinitions`) does emit
  `alter column … set data type` on a type/length mismatch against a dialect that supports it, and the
  ledger records this observed empirically at DEBUG (`deferred-work.md:2441`). Treat as sound.
- **AC5's precedent citations are exact.** `UserAdminService.java:91-92` is
  `@Autowired @Lazy private UserAdminService self;` with the stated `@RequiredArgsConstructor`
  circular-dependency reasoning at `:83-90`; `:237` is `self.deleteUserInTransaction(...)`; the
  `publicMethodsOnly` proxy trap is documented at `:392-401` and the story reproduces it accurately.
  `AdminCoachEnforcementService` does import `Isolation` (`:42`) and `Transactional` (`:43`) but **not**
  `Propagation` — exactly as the story predicts.
- **AC5/AC6's line citations are correct where the ledger's were stale.** The ledger cites `:253`/`:260`
  and `:317`/`:318`/`:296-297`; the actual HEAD positions are `:300`/`:302-307` and `:364`/`:365`/
  `:341-343` — which is what the story uses. The re-verification pass did its job.
- **AC2's line citations are exact** for both processors (`VideoDeletionOutboxProcessor` `:126-156`,
  `:144-155`, `:153`, `:158-211`, `:169`, `:173-187`, `:198`, `:205-210`;
  `RadarCompositeDlqProcessor` `:105-129`, `:118-128`, `:126`, `:131-145`, `:132-144`, `:147-176`), and
  the claim that `RadarCompositeDlqProcessor.handleFailure` escapes its caller's `catch` is right —
  `handleFailure` is invoked *from* the catch block at `:143`.
- **AC4's test-file list is complete.** `grep -rl` for the five claim methods across `src/main` + `src/test`
  returns exactly the four test files named, plus the two repositories, two processors, `VideoDeletionOutbox`
  and `V144`. Nothing has grown since story creation.
- **AC4's "no backfill needed" conclusion is correct** (see L6 for a wording issue) — unlike `V144`'s
  situation, an old-shape `CLAIMED` row still carries a non-null `claimed_at`, so `resetStaleClaimed`
  recovers it after one stale window. It is not stranded.
- **`V148` is the current migration tip** and `assert-context-count.sh`'s `CEILING` is **43**
  (`:125`, and `pr-build.yml:72`). Both Dev Notes figures are accurate.
- **`ReliabilityStrikeServiceTest` will not break on AC1's reorder.** It has no `Optional.empty()` /
  not-found case, so the `when(strikeRepository.save(any()))` stub at `:63` stays used on every path and
  `MockitoExtension`'s strict stubbing will not fire.

---

## 4. Low-severity findings

- **L1 — AC6's replacement comment would itself be imprecise.** The AC describes a strike "in the
  sub-microsecond gap". `strikeCreatedAt` comes from `strikeRepository.findById(strikeId).getCreatedAt()`
  (`AdminCoachEnforcementService:341` region, read from a `timestamp with time zone` column) and is
  therefore **already microsecond-quantised** — it cannot land strictly inside a sub-microsecond gap. The
  only real divergence is the exact-equality boundary: when pgjdbc rounds `cutoff` *up* to the next
  microsecond, a strike whose `created_at` equals that exact microsecond is in-window in Java and
  out-of-window in SQL. The quoted `~1e-9 per call` follows from the wrong model too (the exact-µs-match
  model gives ~1e-12–1e-13 over a 30-day window). Since AC6's entire deliverable is *an accurate comment*,
  shipping a second inaccurate mechanism would re-open the same finding. State the boundary-equality
  mechanism, or state the probability qualitatively.
- **L2 — AC3 Task 5's cited precedents do not have the pattern.** Neither `MigrationConventionLintTest`
  (a static file-text lint; never opens a connection) nor `RescheduleResourceIT` queries
  `information_schema`. The real precedent is **`EnvelopeEntitySchemaIT.java:108`**
  (`select is_nullable from information_schema.columns where …`); `ShedLockConfigIT:33` and
  `StatusScopedPartialIndexConventionIT:71` are secondary examples. Point the dev at
  `EnvelopeEntitySchemaIT`.
- **L3 — AC3 Task 5 overstates what the test can prove.** "proving a fresh database now matches an
  already-patched one" is not assertable: CI only ever builds Flyway-only databases, so the test can
  assert the post-migration width and nothing about the Hibernate-patched shape. Reword to "asserts the
  Flyway-built width is `255`, i.e. matches the width recorded for already-booted environments."
- **L4 — AC1's "No caller-observable behaviour changes" is true for the exhaustion path but not for the
  coach-not-found path.** Today a nonexistent `coachId` fails at the flush inside `withBoundedRetry` with
  an FK-violation (`ConstraintViolationException`); after the reorder it fails at
  `findByIdForUpdate(...).orElseThrow(...)` with `ResourceNotFoundException` (`:98-99`). This is a strict
  improvement and is unreachable from `issueManualStrike` (existence-checked at `:292-293`), but it is a
  real contract change for any direct caller or future entry point, and the story asserts there is none.
  One sentence in the Task 2 comment closes it.
- **L5 — AC2 Task 5's video-side test has no available seam.** `VideoDeletionOutboxProcessorIT`
  `@Autowired`s the real `VideoRepository`/`VideoDeletionOutboxRepository` and only `@MockitoBean`s
  `VideoProviderAdapter` (`:33-39`). Task 5 explicitly excludes the `deleteAsset` path, so proving
  isolation requires a *new* `@MockitoBean` (`DrillVideoRefRepository`, `VideoRepository` or
  `ConfigService`) — which forks a Spring context against CEILING 43. The radar side is fine
  (`RadarCompositeDlqProcessorTest` is plain `@ExtendWith(MockitoExtension.class)` with every collaborator
  mocked). Name the seam and pre-authorise the ceiling bump, or accept asserting isolation on the radar
  side only and covering the video side via the unit-level path.
- **L6 — AC4 Task 2's no-backfill rationale omits its load-bearing half.** The stated reason is "`NULL =
  :runId` never matches, which is the safe direction". That is necessary but not sufficient — it is
  precisely the argument `V144:22-28` rejected for `claimed_at` ("would never match any run's `claimed_at`
  and **would be stranded forever**", hence the backfill). What actually makes it safe here is that
  `resetStaleClaimed` still keys on `claimed_at`, which old-shape rows *do* carry, so recovery happens one
  stale window later. Put that sentence in the migration header — it is the part a future reader will
  need, and without it the header reads as contradicting `V144`'s own precedent.
- **L7 — AC4 does not mention index coverage, where `V144` did.** `findClaimedBatch`'s predicate moves
  from `(status, claimed_at)` to `(status, claimed_by)`; `V144:30-36` spent six lines on exactly this
  question for the previous move. Both tables are near-empty of `CLAIMED` rows so the answer is almost
  certainly "accepted as-is, same as V144" — but the new migration header should say so rather than be
  silent where its direct predecessor was explicit.

---

## 5. Suggested minimal edits to the story before dev starts

1. **AC1 Task 4** → rewrite as "extend `ReliabilityStrikeConcurrencyIT`" + add a task to check that IT's
   CI flake history. **Task 5** → name the interleave seam and drop or qualify "deterministically". (H1, H2)
2. **AC2 Task 4** → either route the unexpected exception through `handleFailure` (nested try), or document
   the never-dead-letters consequence and downgrade AC8's row for that bullet. (M1)
3. **AC3** → add a task deciding widen-vs-narrow on enum-length evidence; reword the CHECK investigation so
   the expected answer is "no constraint present"; repoint Task 5 at `EnvelopeEntitySchemaIT`. (M4, M5, L2, L3)
4. **AC4** → add `claimed_by = NULL` to all four transitions out of `CLAIMED`; state the real no-backfill
   reason and the index note in the migration header. (M2, L6, L7)
5. **AC5 Task 5** → replace the failure-injection mechanism with a test-owned outer transaction. (M6)
6. **AC6 Task 1** → correct the mechanism to boundary-equality and drop or re-derive the `1e-9`. (L1)
7. **AC7 Task 2** → fix (b)/(c) to match this repo's transactional-Flyway reality; broaden (a) beyond two
   tables or make it a rule rather than a list. (H4, M3)
8. **AC8 Task 2** → change the deferred-122 row from `Delete` to an in-place edit; correct the Provenance
   "sibling bullet" sentence; align the AC6/AC7 rows with the `[DECIDED]` convention; add explicit
   instructions for the two section headers and the deferred-123 preamble. (H3, M7, M8)

None of these change the story's scope or its three owner decisions. AC1, AC2 and AC5 are the right fixes
to real problems; the corrections above are about making the tasks executable and keeping the two
permanent artifacts — `migration-conventions.md` and `deferred-work.md` — accurate.
