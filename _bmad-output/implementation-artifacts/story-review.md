# Senior-dev audit — skillars-deferred-98 (SLU Perf Signal & Frontend UX Precision)

**Reviewer:** senior dev (adversarial read against `HEAD` = `f820f9f`)
**Date:** 2026-09-07

---

## Follow-up 2026-09-07 — all findings resolved in the revised story

The story was revised ("revised after senior code review"). All 13 findings (B1–B4, M1–M4, m1–m5)
are addressed: AC2/AC3/AC5 struck as already-shipped; AC1 re-scoped to ledger line 1292 only with
line 1241 explicitly out of scope; fictional files replaced with real classes; the additive-accumulator
data model documented; AC4 reduced to a reference task; the overstated Option-B cost corrected.

Two minor residuals raised in the follow-up were then also fixed:

- **m4 (local `mvn test` expectation).** Completion Criteria now reads "GitHub CI (the sole
  full-verification gate — no local `mvn verify`) green before requesting code review"; the standalone
  `mvn test` checkbox is removed.
- **AC1 design/implementation bundling.** AC1 is split into **AC1a** (design-approval gate, no
  production code) and **AC1b** (implementation, hard-gated on AC1a sign-off). If approval is not
  obtained within the story cycle, AC1b carves out to a follow-up story and ledger line 1292 stays
  open. Completion Criteria, Files, and Testing Strategy updated to match.

**Current verdict: ready for dev.** The original audit below is retained for the record.

---

## Original audit (pre-revision)

**Verdict:** **Not ready for dev.** Four of the six ACs describe work that has **already shipped**;
AC1 is mis-scoped and built on a wrong mental model of the SLU snapshot; AC4 re-litigates a decision
the project owner already recorded, using a cost analysis that does not hold. Every finding below was
checked against the current source, not against the ledger text the story was written from.

The root cause is uniform: the story was assembled from `deferred-work.md` bullets dated **2026-09-02**
(lines 1253, 1254, 1265) and **deferred-91-era** bullets (lines 1291, 1292) **without re-verifying them
against the tree**. `skillars-deferred-90`, `skillars-deferred-91` and `skillars-deferred-92` (all merged
*after* those bullets) closed most of them. Those ledger bullets are themselves stale and untagged — the
story inherited their staleness.

---

## BLOCKER findings — AC targets work already implemented

### B1 — AC2 is already done, and the AC text describes a superseded state

`CoachProfileService.getPublicProfile` (`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:332-349`)
**already carries the exact architectural-decision Javadoc AC2 asks for**, and it is *more* accurate than
the AC:

- The method was **collapsed from 8 round-trips to 4** by the `skillars-deferred-91` code review (D9).
  AC2 says "8 JDBC queries, constant regardless of collection size" and "**No code change to the method
  itself**" — both describe the pre-collapse state. Current `EXPECTED_QUERY_COUNT = 4`.
- The Javadoc already explains why the last two collection reads are *not* folded in and why a
  `JOIN FETCH` is declined: "would risk `MultipleBagFetchException` / a cartesian explosion — the
  hazards the original 'left as-is' analysis correctly identified."
- The IT `CoachPublicProfileQueryCountIT` **already exists** with (a) the fixed-count assertion,
  (b) `getPublicProfile_queryCountDoesNotGrowWithProfileSize` (the "doubles every collection" test),
  and (c) `getPublicProfile_collapseDoesNotChangeTheResponse` (a response-parity guard).

Following AC2 as written, a developer would add a comment asserting "8 queries" and "no code change" —
both factually wrong — and re-document a decision that is already documented correctly.
Ledger line 1291 (which the story copied verbatim) was never updated after D9.

**Wrong path:** AC2 cites `.../platform/marketplace/CoachPublicProfileQueryCountIT.java`; the file is at
`.../platform/marketplace/api/CoachPublicProfileQueryCountIT.java`.

**Action:** delete AC2, or reduce it to "confirm the D9 collapse Javadoc + IT are still present" (they are).

### B2 — AC3's documentation fix already shipped

`docs/session-refresh-mechanism.md` already documents the ±30 s variance in **three** places:

| Location | Content |
|---|---|
| Key timeouts table, line 82 | `SESSION_CHECK_INTERVAL │ 30 s │ plugins/sessionManager.js │ How often the monitor recomputes timeUntilExpiry from rint` |
| State diagram, line 498 | `±30 s check-tick granularity` |
| **Known limitations #1, lines 571-573** | "**±30 s granularity.** `SESSION_CHECK_INTERVAL` = 30 s, so the warning and the client-side expiry event can fire up to 30 s late. **Any test asserting an exact figure is flaky by construction.**" |

That last entry is, almost verbatim, what AC3 asks to add. The doc **nowhere** presents the 5-minute
warning as exact — line 78 describes `WARNING_THRESHOLD` as "Fixed client-side constant; warning shows
when `timeUntilExpiry` drops below it." AC3's premise ("Documentation presents as precise") is false
for the current doc.

AC3's cited line `docs/session-refresh-mechanism.md:67` is a stale pointer copied from the 2026-09-02
ledger bullet (line 1254); the doc was rewritten by Story 1.7a/1.7b and line 67 is now mid-section.

**Action:** delete AC3's doc sub-task. Nothing genuine remains (the `sessionManager.js:218` comment
already reads "instead of up to `SESSION_CHECK_INTERVAL` later").

### B3 — AC3's `SecurityConstants.java` instruction is not implementable as written

`SESSION_CHECK_INTERVAL` is a **frontend-only** constant (`src/frontend/src/plugins/sessionManager.js:5`).
It does not exist in `SecurityConstants.java` and is not a backend concept — a repo-wide grep confirms
it appears only in `sessionManager.js`, docs, and story/ledger artifacts. Adding a "JavaDoc note on
±30 s variance on `SESSION_CHECK_INTERVAL`" to a Java file that has no such symbol is a no-op at best.

The `SESSION_REFRESH_COUNTDOWN` (`rint`) Javadoc that *does* exist there
(`src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`) makes **no**
precise-timing claim; it names `WARNING_THRESHOLD, 5 min` only as the threshold constant.

**Wrong path:** AC3 lists `.../platform/security/config/SecurityConstants.java`; the file is at
`.../infrastructure/security/SecurityConstants.java`.

**Action:** drop the `SecurityConstants.java` item entirely.

### B4 — AC5 is already resolved; its "regression" premise is false

```
$ cd src/frontend && npx prettier --check src/App.vue src/boot/axios.js
Checking formatting...
All matched files use Prettier code style!
```

Commit `cb20f11` — *"skillars-deferred-92 AC1: mechanical `prettier --write` over src/frontend/src +
CI gate"* (2026-09-04) — reformatted both files (it is the last commit to touch either) **and** added a
CI gate at `.github/actions/frontend-quality/action.yml:36`
(`npx prettier --check "**/*.{js,vue,scss,json}"`).

AC5's premise — "Two frontend files … fail Prettier rules", "regression from deferred-89/deferred-90
work" — was true at the 2026-09-02 ledger bullet (line 1253) and false since `deferred-92`
(2026-09-04). There is nothing to reformat and no exemption to document. The "land in a separate
commit so the real diff is visible" dev-note is moot.

**Action:** delete AC5.

---

## MAJOR findings

### M1 — AC1 conflates two unrelated deferred items; provenance claim is unsupported

There are **two distinct open ledger bullets**, and the story merges them:

| Ledger | What it is | Module | Addressed by deferred-98? |
|---|---|---|---|
| **Line 1241** (the *actual* deferred-89 residual, filed by deferred-89 AC10) | A non-gating **latency** perf-tracking job for `authorizePlayback` — record p50/p95/p99 over time; today they only land in Failsafe output nothing scrapes. `[PlaybackServiceIT.java]` | video | **No — untouched** |
| **Line 1292** (a deferred-91 residual) | A non-gating **SLU skill-trend** signal — "is this player's trend improving/declining", independent of any gate | development | Yes — this is what AC1 builds |

The story's title ("SLU Perf Signal"), Executive Summary, and AC1 ("carried from skillars-deferred-89")
treat these as one item. They are not. deferred-89's SLU-related residual was the snapshot
**under-report direction**, which `deferred-91` AC4 already closed (outbox + V119 marker). The
"carried from skillars-deferred-89" attribution for a *skill-trend* feature has no basis — line 1292's
own "(carried from skillars-deferred-89, line ~1290)" is a garbled self-reference.

Consequence: the Completion Criteria says *"all six items tagged `[CLOSED by skillars-deferred-98
AC1-AC6]` and deleted."* Acting on that would wrongly close **line 1241** (the real deferred-89
perf-tracking job), which this story does not implement.

**Action:** scope AC1 explicitly to ledger line 1292 only. Either add a separate AC for line 1241
(`authorizePlayback` latency tracking) or state in the story that it is out of scope and stays open.
Drop the "carried from skillars-deferred-89" language.

### M2 — AC1's file references are fictional and its data model is wrong

**Non-existent files named by AC1:**
- `.../platform/development/service/SluService.java` — does not exist.
- `.../platform/development/repo/PlayerSkillStatsRepository.java` — does not exist.

The real SLU code is decomposed: `SluCalculationService`, `SluDashboardService`, `SluNarrativeService`,
`SnapshotBatchWriter`, `SluWeeklySnapshotRepository`, `PlayerSluWeeklySnapshot` (entity), plus the
outbox chain (`SluSnapshotOutboxHandler`, `SluSnapshotOutboxSupport`, `SluPersistenceDispatcher`,
`SnapshotPersistenceRetrier`). For a *coaching-insight* signal the natural home is
`SluDashboardService` / `SluNarrativeService`, not the write path.

**Wrong mental model of the snapshot:**

1. `player_slu_weekly_snapshot` is an **additive accumulator** keyed
   `(player_id, skill_code, iso_year, iso_week)`:
   `SluWeeklySnapshotRepository.upsertAddIdempotent` does
   `total_slu = player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu`, applied **one session-delta
   at a time** as the outbox drains (`SnapshotBatchWriter.writeAllDeltas`). There is **no single
   "snapshot write" event that ever holds the finished weekly value.** AC1's instructions "Trend signal
   should be appended to snapshot event, not replace it" and "Verify snapshot-write performance is not
   degraded by new field" assume a batch job that computes one weekly number — that is not how this
   works. A directional trend (week N vs weeks N-1, N-2…) is inherently a **read-time or post-week
   scheduled** computation over historical rows.

2. The snapshot is **per `skill_code`**, not one scalar per player per week. AC1's Implementation Notes
   examples ("Player's SLU went from 65→72→80 over three weeks") assume a single scalar and never
   define per-skill vs aggregate. That is an unresolved design decision, not a detail — it drives the
   query shape, the storage, and what "improving" even means.

3. A historical range query **already exists**:
   `SluWeeklySnapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek, toYear, toWeek)`,
   ordered `isoYear ASC, isoWeek ASC`. AC1's "new query for historical trends" is largely redundant.

**Action:** rewrite AC1's Files/Dev-Notes against the real classes; decide per-skill vs aggregate up
front (product input); state explicitly that the trend is computed on read or by a scheduled job over
`findByPlayerIdFromWeek`, not "appended to the snapshot event."

### M3 — AC4 re-opens a decision the project owner already made

Ledger line 1268 (`skillars-deferred-90`): *"`sessionManager.js` `startSessionMonitoring()`'s
early-return-with-no-timer path — **left as documented (project-owner decision)**."* Line 1265 records
the full rationale from the round-2 1.7b review.

AC4 presents Options A/B/C as an open choice and then recommends **Option A = "document current design
(silent approach)"** — i.e. re-affirming the decision already on record. As written, AC4 is near-zero
net work dressed as a design task, and risks a developer "implementing" a TODO comment that just
restates an existing decision.

**Action:** reduce AC4 to "reference the deferred-90 project-owner decision in a code comment if one is
not already present," or drop it. If the project owner genuinely wants to revisit, say so explicitly
and cite the prior decision being reversed.

### M4 — AC4's cost analysis for Option B does not hold

AC4 (and ledger line 1265) claim that arming the interval anyway would re-dispatch `session:expired`
"**every 30s until navigation completes**", triggering "**repeated backend logout calls**".

Trace the actual code:
- `App.vue:27 handleSessionExpired()` calls `cleanup()` → `stopSessionMonitoring()` →
  `clearInterval(checkIntervalId); checkIntervalId = null`.
- So an interval armed *after* the first expired `tick()` fires **once more** at +30 s, `tick()`
  dispatches `session:expired` once, `handleSessionExpired` runs `cleanup()` again, and the interval
  **tears itself down**. `startSessionMonitoring()` is not called again (only from mount /
  `initSession()`).

Net cost of Option B is **one** extra `session:expired` + **one** extra background `authStore.logout()`
— not a loop. And `authStore.logout()` is already fire-and-forget ("best-effort backend call fires in
background", `App.vue:32`), so even that is cheap. The comparison AC4 uses to prefer Option A over B
rests on an overstated cost.

This does not change the recommendation (Option A is still reasonable), but the story should not
justify it with an inaccurate mechanism.

---

## MINOR findings

### m1 — Pattern of stale / wrong file paths

Beyond B1/B3/M2: the story repeatedly cites paths and line numbers copied from 2026-09-02 ledger
bullets without re-checking. This is the same failure mode `deferred-work.md`'s own preamble warns
about ("File paths and line numbers age fast"). Every path in the story should be re-derived.

### m2 — AC6 restates a gap already dismissed ~15 times

The "no frontend test framework" gap is **real and current** — no `vitest` / `@vue/test-utils` in
`src/frontend/package.json`, no `*.spec.js` / `*.test.js` anywhere. But:
- Ledger line 722 marks the canonical instance `[DISMISSED 2026-08-30 … not a distinct action item]`.
- Ledger line 1293 calls it "its own initiative … out of scope."
- The story's affected-stories list omits `skillars-5-4 W9`, which is the origin the ledger anchors to.

An AC that re-lists this without producing a decision is ledger churn. If AC6 stays, its only real
deliverable is the explicit **"stand up Vitest in this story vs. defer to its own initiative"**
decision — make *that* the AC, not the enumeration.

### m3 — AC2 rationale coins a non-existent type name

AC2 writes "risk of `CartesianRowExplosion`" as though it is a class. The real hazards are
`MultipleBagFetchException` and cartesian row multiplication — already named correctly in the shipped
`getPublicProfile` Javadoc.

### m4 — Completion Criteria: `mvn -o verify` locally

*"Backend `mvn -o verify` green"* — per project convention (and this repo's memory notes) full
verification is the GitHub CI gate, not a local pre-push step. Cosmetic; align the wording with the
other recent stories.

### m5 — Story file is untracked while already `ready-for-dev`

`skillars-deferred-98-…md` is `??` and `sprint-status.yaml` is already modified — the same
tracking-vs-status mismatch flagged for the 1.7b artifacts (ledger line 1256). Commit the story with
its status change.

---

## What is actually left to do (if the story is kept at all)

After removing the shipped work, the residue is small:

1. **SLU skill-trend signal** (ledger line 1292) — a genuine open item, but it is a **new feature**
   that was never a reviewed deferral with an agreed shape. Needs: per-skill vs aggregate decision,
   where it surfaces (dashboard? narrative? coach analytics API?), read-time vs scheduled computation,
   and whether it needs its own table/column at all given `findByPlayerIdFromWeek` already exists.
   Treat as a scoped feature story, not a "close deferred work" bundle item.
2. **`authorizePlayback` latency perf-tracking job** (ledger line 1241) — the real deferred-89
   residual, currently unaddressed. Decide in or out; do not let it be tagged `[CLOSED]` by this story.
3. **AC4** — at most a one-line comment pointing at the deferred-90 decision, if missing.
4. **AC6** — a yes/no decision on standing up Vitest, nothing else.

AC2, AC3 (both halves), and AC5 should be struck: the code, the IT, the doc, and the Prettier CI gate
already reflect exactly what they ask for.
