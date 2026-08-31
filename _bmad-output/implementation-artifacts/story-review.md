# Senior-dev audit — Story skillars-deferred-84

**Story:** `coach_radar_preferences` FK hardening, SLU snapshot-persistence resilience, skill-toggle debounce, and `SkillDefinitionResource` architecture compliance
**Reviewed against:** current `master` source (`274e074`)
**Scope of review:** missed corner cases, false assumptions, missed flows. Findings verified live against source; low-confidence items are labelled as such and separated from blocking ones.

---

## Verdict

Three of the four ACs (AC1, AC2, AC4) are sound in intent and mostly accurate, with a handful of concrete gaps to close before/during implementation. **AC3 as written rests on a false premise about the frontend component architecture and cannot satisfy its own acceptance test** — it needs a design change, not just a debounce wrapper.

---

## Blocking

### B1 — AC3: "instant visual feedback" premise is false; the prescribed change regresses the UX it is meant to leave untouched

AC3 states:

> Do not debounce the visual toggle itself — `SkillsRadarChart.vue:272-278`'s `toggleSkill` already mutates its own local selection state and emits synchronously on every click, giving instant visual feedback independent of the parent's network call.

Verified against source — this is not how it works:

- `SkillsRadarChart.vue` has **no local selection state**. `toggleSkill` (`:272-279`) computes `updated` from the `selectedSkillCodes` **prop** and does nothing but `emit('update:selectedSkillCodes', updated)`. Every derived view (`activeSkills` `:198-203`, `polygonPoints`, `nodePositions`, `hasBaseline`) reads the prop directly.
- The parent binds that prop to `store.radarPreferences?.selectedSkillCodes ?? []` (`PlayerDevelopmentDashboardPage.vue:27`) and uses `@update:selected-skill-codes` (not `v-model`), so nothing flows back into the chart except through the store.
- `onSkillSelectionChange` (`:240-244`) does **not** write `codes` anywhere synchronously — it only calls `store.saveRadarPreferences(playerId.value, codes)`.
- `development.store.js:147-154`: `saveRadarPreferences` updates `radarPreferences.value` **only after `await putRadarPreferences(...)` resolves** (`:149-150`). It is not optimistic.

Consequences:

1. The chart's selected-skill polygon **already** lags one network round-trip per click today — the "instant" feedback AC3 says must be preserved does not exist.
2. Wrapping `store.saveRadarPreferences` in a trailing 300 ms debounce — while AC3 explicitly forbids adding local optimistic state — means the polygon, the `hasBaseline` computed, and the "Compare to baseline" toggle (`v-if="hasBaseline"`) will not visually change until ~300 ms after the *last* click **plus** the PUT round-trip. During a rapid burst there is **no visual feedback at all** until the burst ends.
3. AC3's own manual acceptance test — "confirm the chart's visual selection updates instantly per click while only one PUT fires" — is **not achievable** with the prescribed implementation. A dev implementing exactly to spec will fail their own test.

**What the fix actually requires:** decouple visual state from persistence. Introduce a local `selectedSkillCodes` ref in `PlayerDevelopmentDashboardPage.vue` (seeded from `store.radarPreferences` and kept in sync via a `watch`), update it synchronously in `onSkillSelectionChange`, drive `SkillsRadarChart` (and `hasBaseline`) from it, and debounce **only** the `store.saveRadarPreferences` call. This is precisely the "local UI state" AC3 tells the dev they will not need — they do.

---

## Should-fix

### S1 — AC3: debounced write is silently lost on unmount / navigation

`DrillLibraryPage.vue`'s `useDebounce` (`:163-169`) is a bare trailing `setTimeout` with no `cancel`/`flush`. AC3 says to mirror it "exactly". For a *search* re-run that is harmless; for a **persistence** call it is not: a selection change followed by a route change or component unmount within 300 ms drops the PUT entirely, and `store.error` is never set, so the coach gets no feedback that their preference did not save. Add an `onBeforeUnmount` flush (or a debounced helper with a `flush()` method) and call it on teardown. The story's framing of the two debounce sites as equivalent overlooks this asymmetry.

### S2 — AC3: stale `playerId` capture across a player switch

`playerId` is `computed(() => Number(route.params.playerId))`. A debounced `store.saveRadarPreferences(playerId.value, codes)` resolves `playerId.value` at **fire time**. If the coach changes the selection and then navigates to a different player within the debounce window, the trailing call persists the *previous* player's `codes` against the *new* player's id, and also races the new player's `store.fetchRadarPreferences(id)` in `loadPlayerData`. Capture `playerId` at click time and cancel any pending save in the `watch(() => route.params.playerId, …)` handler (which already calls `clearDevelopmentState()` at `:227-233`).

### S3 — AC1: no index backs the new FK / cascade on `coach_radar_preferences.player_id`

`coach_radar_preferences` PK is `(coach_id, player_id)` (`V51:18`), so `player_id` is **not** the leading column and has no standalone index. The V113 precedent tables this AC says to "mirror exactly" — `player_radar_composites` and `player_radar_baselines` — are both PK-led by `player_id`, so their cascade deletes and FK integrity checks are index-covered. Here they are not: every `DELETE FROM main.player_profiles …` will sequentially scan the whole `coach_radar_preferences` table, and so will each FK check. Add `CREATE INDEX ix_crp_player_id ON development.coach_radar_preferences (player_id);` in the same migration. Impact is low today (player deletion is rare, table is small) but it is a real divergence from the precedent and cheap to close.

### S4 — AC4: the IT design has three concrete problems

1. **No seeded inactive skill exists.** All 15 rows in `V46__development_module_init.sql:34-49` default to `active = true`, and no later migration (`V48`, `V50`, `V51`) deactivates any. So "reuse the existing seeded rows from V46's taxonomy if convenient" is impossible for the *inactive* case — the IT must `INSERT` an inactive `skill_definitions` row.
2. **No transactional rollback.** `AbstractIntegrationTest` carries no class-level `@Transactional` (verified). Sibling ITs (`RadarCompositeBaselinePlayerFkIT`, `SluCalculationServiceIT`) clean up their own rows explicitly. `skill_definitions` is a small **shared reference table** many tests read; any inserted row (especially an active one) that is not deleted in teardown will break other ITs' skill-count / taxonomy assertions (e.g. `SkillExposureResourceIT`, radar ITs) and can collide on the PK `code` on a re-run. The story does not call out this cleanup requirement.
3. **Brittle assertion.** "assert the response contains only the active skill and omits the inactive one" — the endpoint returns *all* active skills (15 seeded + any inserted). The assertion must be relative: contains the test active `code`, does not contain the test inactive `code`.

Also: the story names no `…development.api` sibling IT to model the authenticated-request setup on (`GET /api/development/skill-definitions` is `@PreAuthorize("isAuthenticated()")`, and `@SpringBootTest(RANDOM_PORT)` rules out `@WithMockUser`). Point the dev at `SkillExposureResourceIT` / `RadarDisplayResourceIT` for the auth pattern.

---

## Minor / accuracy

### M1 — AC2: the `SnapshotPersistenceRetrier` code block does not compile as written

The literal snippet declares only `@Component` + `@RequiredArgsConstructor` but calls `log.error(...)` in `@Recover`. It is missing `@Slf4j` (which `SluPersistenceRetrier` — the class it is told to mirror — has) and all imports. A dev following "mirror exactly" will likely add it, but the provided artifact is defective.

### M2 — AC2: `SluCalculationServiceIT` does not actually verify the snapshot write

The story claims running `SluCalculationServiceIT` "confirms the wiring change didn't break the existing end-to-end snapshot-write flow". The IT contains **no assertion against `development.player_slu_weekly_snapshot`** (grep-confirmed). It exercises the code path — so a broken AOP proxy or a thrown exception would fail it — but a `writeAllWithRetry` that silently no-ops would pass green. Either add a snapshot-row assertion to the IT or state the residual gap explicitly.

### M3 — AC2: misleading success log after `@Recover`

Once `@Recover` swallows the `DataAccessException`, `snapshotPersistenceRetrier.writeAllWithRetry(...)` returns normally even on total failure, so `SluCalculationService:187`'s `log.debug("Weekly snapshot updated: …")` will fire even when the snapshot write failed and was only recovered-logged. Today a hard failure suppresses that line. This is consistent with the pre-existing SLU path (`:179-180` behaves the same after `saveSluWithRetry`), so it is a precedent-consistent wart, not a regression — worth a one-line comment or a reordering.

### M4 — AC2: residual data-consistency risk on retry exhaustion (acknowledge, not a defect)

When all retries fail, `@Recover` only logs "manual recovery needed" while `player_skill_stats` already holds the committed rows — `player_slu_weekly_snapshot` is then permanently under-counted and the NFR-001 dashboard under-reports. The story correctly mirrors the accepted `SluPersistenceRetrier` precedent, so this is a known tradeoff. Note, though, that unlike raw SLU rows the snapshot is a **derived aggregate** and could be rebuilt from `player_skill_stats`; a stronger `@Recover` (or a reconciliation job) is possible if desired. Out of "mirror the sibling" scope, but flag it in the ledger rather than leaving it implicit.

### M5 — AC1: the "V117 was previously claimed and reverted during skillars-deferred-83" rationale is unsubstantiated

Git history shows **no `V117__*` file ever added or reverted** (`git log --all --diff-filter=A -- 'src/main/resources/db/migration/V117*'` is empty), and the most recent `db/migration` commit is deferred-78's, not deferred-83's. The **conclusion is correct** — latest is `V116__session_status_cancelled.sql`, so `V117` is the next free number — but the parenthetical justification is fabricated and could send a dev looking for a non-existent reverted migration. Drop or correct it.

### M6 — Registry entries are already committed; the story describes them as pending

`docs/testing/test-data-isolation.md:209-210` already contains both new ranges (`CoachRadarPreferencePlayerFkIT` → `9650000001`–`9650000010`, `SkillDefinitionResourceIT` → `9651000001`–`9651000005`), and the "Free blocks" list is already updated to `9652`–`9690`. AC1/AC4 and Task 1/Task 4 describe registering these ranges as dev work to be done. Not harmful, but the dev should not re-add them, and AC1's inline "`9650`–`9690` is listed free" is now stale (it is `9652`–`9690`).

### M7 — Minor line-reference / package drift

- AC4: inline logic is at `SkillDefinitionResource.java:26-29`, not `:24-27`.
- AC4 / Project Structure Notes: `SkillDefinitionMapper` lives in `…development.contract`, not adjacent to the repo — the new `SkillDefinitionService` will import it cross-package (fine, just not as stated).
- AC2 line refs `SluCalculationService.java:48` (field) and `:186` (call site) are **accurate** — verified.

---

## Confirmed correct (no action needed)

- **AC1 FK mechanics:** `coach_radar_preferences.player_id` is `BIGINT` (`V51:16`), `main.player_profiles.id` is `BIGINT` — the `ON DELETE CASCADE` FK and the defensive orphan-delete-first pattern correctly mirror `V113`. The unaliased correlated subquery form is valid PostgreSQL.
- **AC1 `coach_id` scope exclusion** is well-reasoned and correctly deferred as a distinct, not-yet-flagged gap.
- **AC2 retry-safety analysis** is sound: `SnapshotBatchWriter.writeAll` is `@Transactional` and loops an additive `upsertAdd` (`ON CONFLICT … DO UPDATE SET total_slu = total_slu + EXCLUDED.total_slu`, confirmed in `SkillExposureResourceIT:347`); an uncaught exception rolls the whole method back, so a fresh-transaction retry re-runs against a clean slate. The "read `SnapshotBatchWriter` fully before wiring" instruction is appropriate.
- **AC2 separate `@Component`** for the `@Retryable` AOP proxy correctly follows the documented self-invocation precedent on `SluPersistenceRetrier`.
- **AC2 property namespace** `app.slu.snapshot-retry.*` with inline defaults and no `application.yaml` entry correctly matches `SluPersistenceRetrier`'s precedent — grep confirms no `app.slu.retry.*` key exists in any resource file.
- **CI context-count reasoning** is correct: ceiling is `37` (`assert-context-count.sh:75`); all four new test classes either extend `AbstractIntegrationTest` with no added `@MockitoBean`/`@TestPropertySource` (AC1 IT, AC4 IT) or are context-free Mockito unit tests (AC2, AC4 service test), so none moves the count.
- **AC4 premise** verified: `SkillDefinitionResource` is the only resource in the module injecting a repository directly; no `SkillDefinitionService` exists; the resource has zero test coverage at any layer.
- **AC independence** holds — schema / backend-resilience / frontend / architecture, no shared files or state.

---

## Recommended pre-dev actions

1. **AC3:** rewrite to add a local `selectedSkillCodes` ref in `PlayerDevelopmentDashboardPage.vue`, drive the chart + `hasBaseline` from it, debounce only `store.saveRadarPreferences`, and flush the debounce on unmount and on player switch (addresses B1, S1, S2). Update the acceptance test wording accordingly.
2. **AC1:** add the `(player_id)` index to the migration (S3).
3. **AC4:** rewrite the IT bullet — require an explicitly inserted inactive row, explicit teardown of all inserted `skill_definitions` rows, a relative assertion, and a named sibling IT for the auth pattern (S4).
4. **AC2:** add `@Slf4j` + imports to the snippet (M1); decide whether to strengthen `SluCalculationServiceIT` with a snapshot assertion or document the gap (M2).
5. Correct or drop the V117 rationale (M5); note the registry rows are already committed (M6).
