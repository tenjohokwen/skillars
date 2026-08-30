# Story skillars-deferred-84: coach_radar_preferences FK hardening, SLU snapshot-persistence resilience, skill-toggle debounce, and SkillDefinitionResource architecture compliance

Status: ready-for-dev

## Story

As the platform owner,
I want (1) `coach_radar_preferences` to stop leaving orphaned rows on player deletion, (2) the weekly SLU snapshot write to get the same retry/compensation protection its sibling `saveAll` call already has, (3) rapid skill-selection clicks on the radar chart to stop firing a PUT per click, and (4) `SkillDefinitionResource` to stop bypassing the service layer every other resource in this module uses,
so that four real, previously-picked-up-but-left-open findings from the `skillars-5-1` through `skillars-5-4` code-review cycles (2026-06-18/19) finally close, instead of continuing to sit in `deferred-work.md` as known gaps nobody returns to.

## Story creation context

Per standing instruction, this story's creation targeted the SLU/Radar module first (this project's standing priority order for `deferred-work.md` re-mining, per `[[project_skillars_release_workflow]]`, currently has this module first once the Booking/Availability/Reschedule → Video/Playback/Moderation → Messaging/Admin/Reviews/Disputes → Payments/Stripe/Credit wallet rotation used by `skillars-deferred-80` through `-83` came up dry two cycles running).

The SLU/Radar sections of `deferred-work.md` (`skillars-5-1` through `skillars-5-4` code-review sections, 2026-06-18/19) had **not** been re-mined during that four-module rotation at all — these predate it. A full pass across all six sections turned up a long tail of items, most already closed by `skillars-deferred-76`/`-77`'s own dedicated hardening stories or explicitly accepted as intentional tradeoffs at review time. Six candidates survived as genuinely open and were verified live against current source before inclusion or rejection:

- **`coach_radar_preferences.player_id` has no FK** (`skillars-5-4` W1, partially closed) — `skillars-deferred-77` AC9's `V113` migration added `ON DELETE CASCADE` FKs to `player_radar_composites` and `player_radar_baselines` but explicitly left `coach_radar_preferences.player_id` out of scope. Confirmed still true by reading `V51__radar_display_correlation.sql:13-18` directly — no FK on either `coach_id` or `player_id`. Became AC1 (scoped to `player_id` only, matching what the ledger item actually flags — see Dev Notes for why `coach_id` is knowingly left out).
- **`SnapshotBatchWriter.writeAll` has no retry/compensation** (`skillars-5-2` W1, partially addressed) — `skillars-deferred-77` AC8 added `SluPersistenceRetrier` around `sluRepository.saveAll`, but the ledger item's own text already flagged that the *snapshot* write (`snapshotBatchWriter.writeAll`) got no equivalent protection. Confirmed live: `SnapshotBatchWriter.java` has no `@Retryable` anywhere, unlike `SluCalculationService.java:48`'s `sluPersistenceRetrier` field. Became AC2.
- **Skill-toggle fires a PUT per click, no debounce** (`skillars-5-4` W2) — confirmed live by tracing the full chain: `SkillsRadarChart.vue:87`'s `@click="toggleSkill(...)"` emits `update:selectedSkillCodes` (`:272-278`) on every single click, which `PlayerDevelopmentDashboardPage.vue:240-244`'s `onSkillSelectionChange` forwards straight into `store.saveRadarPreferences(...)` — one PUT per click, no debounce anywhere in the chain. Became AC3.
- **`SkillDefinitionRepository` injected directly into `SkillDefinitionResource`, no service layer** (`skillars-5-3` DEF1, "fix at next planned touch") — confirmed live: `SkillDefinitionResource.java:19` still injects `SkillDefinitionRepository` and `SkillDefinitionMapper` directly, the one resource in this module that does. This story is that next planned touch. Became AC4.
- **`RadarDisplayService` silently drops a player's baseline from the radar chart when the skill is deactivated** (`skillars-5-4` W4) — investigated and put to the project owner directly (2026-08-30): confirmed this is the intended design (deactivating a skill removes it from radar display everywhere, full stop — `active` is the single source of truth for what's currently tracked). **Decided: leave as-is**, not a bug. Closed as ledger hygiene, not picked up.
- **`IMPROVEMENT_THRESHOLD = 3.0` hardcoded in `DevelopmentCorrelationService`** (`skillars-5-4` W7, "configurable in a future story") — investigated and put to the project owner directly (2026-08-30): no concrete need has surfaced for tuning this value; making it configurable now would be speculative. **Decided: leave hardcoded.** Closed as ledger hygiene, not picked up.

The remaining ~20 items across these six sections (accepted design tradeoffs like `CallerRunsPolicy` blocking, duration-rounding approximation, `Thread.sleep` in negative-path tests, deliberate no-FK-on-audit-rows patterns, platform_config id gaps, an already-fixed race-condition false premise, and a `SluTargetEditor` fix already confirmed live during `skillars-deferred-21`) were re-verified during this pass and closed as ledger hygiene (Task 5) rather than picked up — none represent live, actionable work; each already carries its own "accepted"/"pre-existing pattern" framing in the ledger and this pass found nothing to change that assessment.

**Four real, independently-verified items — spanning schema, backend resilience, frontend UX, and architecture compliance — clear this project's "no small stories" bar without needing to reach into the Deploy/Infra → Drills/Session-Builder → Auth/Registration fallback order.**

## Acceptance Criteria

1. **`coach_radar_preferences.player_id` gets an `ON DELETE CASCADE` FK to `main.player_profiles(id)`, mirroring `V113`'s exact pattern for the sibling `player_radar_composites`/`player_radar_baselines` tables.**

   - New migration `V117__coach_radar_preferences_player_fk.sql` (next free version — `V117` was previously claimed and reverted during `skillars-deferred-83`'s own dev pass for an unrelated table, confirmed free by `ls src/main/resources/db/migration/`, latest is `V116`). Mirror `V113__radar_composite_baseline_player_fk.sql` structure exactly:
     - Defensive `DELETE FROM development.coach_radar_preferences WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles WHERE id = coach_radar_preferences.player_id)` first, so the migration cannot fail against a deployed environment with unexpected orphans (same reasoning `V113`'s own comment gives, itself mirroring `V109`'s established pattern).
     - `ALTER TABLE development.coach_radar_preferences ADD CONSTRAINT fk_crp_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;`
   - **Scope boundary, deliberate**: `coach_radar_preferences.coach_id` also has no FK to `marketplace.coach_profiles(id)` (confirmed by the same `V51` read) — this AC does **not** touch it. The ledger item this AC closes only ever flagged `player_id`; `coach_id`'s gap was never raised as a finding by any prior code review and pulling it in now would be scope creep beyond what was actually decided. Note it in Dev Notes as a known, distinct, not-yet-flagged gap for a future pass to pick up on its own merits if it ever becomes one.
   - New IT test `CoachRadarPreferencePlayerFkIT` (package `com.softropic.skillars.platform.development.repo`), mirroring `RadarCompositeBaselinePlayerFkIT.java` structure exactly: seed a parent `main."user"` row + a `main.player_profiles` row + a `coach_radar_preferences` row (needs a real-looking `coach_id` UUID value — no FK means any UUID satisfies the column, but use a realistic one for clarity), assert the preference row exists, delete the `player_profiles` row, assert the preference row is gone. Claim id range `9650000001`–`9650000010` (first free block per `docs/testing/test-data-isolation.md`'s registry — `9650`–`9690` is listed free) and register it in the fixture-id registry table.
   - Test: `mvn -o test -Dtest=CoachRadarPreferencePlayerFkIT`, confirm green. This IT extends `AbstractIntegrationTest` — no new Spring context (shares the existing shared context every plain `AbstractIntegrationTest` subclass does, since it declares no `@MockitoBean`/`@TestPropertySource` of its own), so it does not touch the CI context-count ceiling `skillars-deferred-83` just raised to 37.

2. **`SnapshotBatchWriter.writeAll` gets the same retry/compensation protection `SluPersistenceRetrier` already gives `sluRepository.saveAll`.**

   - Create `SnapshotPersistenceRetrier` (new `@Component`, package `com.softropic.skillars.platform.development.service`), mirroring `SluPersistenceRetrier.java` structure exactly — including its own javadoc's stated reason for being a separate bean (so `@Retryable` goes through the Spring AOP proxy; a call from `SluCalculationService` via a hypothetical `this.writeAllWithRetry(...)` would bypass the proxy and silently never retry, the same self-invocation pitfall `SluPersistenceRetrier`'s own class javadoc documents):
     ```java
     @Component
     @RequiredArgsConstructor
     public class SnapshotPersistenceRetrier {
         private final SnapshotBatchWriter snapshotBatchWriter;

         @Retryable(
             retryFor = DataAccessException.class,
             maxAttemptsExpression = "${app.slu.snapshot-retry.max-attempts:3}",
             backoff = @Backoff(
                 delayExpression = "${app.slu.snapshot-retry.backoff-initial-ms:100}",
                 multiplierExpression = "${app.slu.snapshot-retry.backoff-multiplier:2.0}"
             )
         )
         public void writeAllWithRetry(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
             snapshotBatchWriter.writeAll(stats, isoYear, isoWeek);
         }

         @Recover
         public void recoverSnapshotWriteFailure(DataAccessException ex, List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
             log.error("Failed to write SLU weekly snapshot after retries — {} rows lost for {}-W{}, manual recovery needed",
                 stats.size(), isoYear, isoWeek, ex);
         }
     }
     ```
     Use a distinct property namespace (`app.slu.snapshot-retry.*`, not `app.slu.retry.*`) so the two retriers can be tuned independently — matching `SluPersistenceRetrier`'s own precedent of inline `@Value`-style defaults with no corresponding `application.yaml` entry (confirmed by grep: no `app.slu.retry.*` key exists in any `application*.yaml` file either — the defaults are the only configuration today, and that is the established pattern to follow, not a gap to fill).
   - **Retry-safety note (verify during implementation, no code change needed if confirmed)**: `SnapshotBatchWriter.writeAll` is `@Transactional` and loops calling `snapshotRepository.upsertAdd(...)` (an additive `ON CONFLICT ... DO UPDATE SET total_slu = total_slu + EXCLUDED.total_slu` upsert). A retry of the whole method is safe under the same reasoning `SluPersistenceRetrier`'s retry of `saveAll` already relies on: an uncaught exception during the loop rolls back the entire transaction (nothing partially commits), so a retry re-runs against a clean slate, not a partially-applied one. Confirm this by reading `SnapshotBatchWriter.java` fully before wiring the retrier — if some other code path already reads mid-batch state non-transactionally, flag it as a blocker rather than proceeding.
   - Change `SluCalculationService.java:186`'s `snapshotBatchWriter.writeAll(stats, isoYear, isoWeek)` call to `snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek)`, and add the new `snapshotPersistenceRetrier` field alongside the existing `sluPersistenceRetrier` field (`:48`).
   - New unit test `SnapshotPersistenceRetrierTest` (package `com.softropic.skillars.platform.development.service`), mirroring `SluPersistenceRetrierTest.java`'s exact three-test shape: `writeAllWithRetry_delegatesToWriter` (verifies delegation via Mockito, plain instantiation), `recoverSnapshotWriteFailure_logsAndDoesNotRethrow` (asserts no exception via `assertThatCode`), `writeAllWithRetry_writerThrows_propagatesToCaller` (pins that the un-proxied method is a thin pass-through — retries come from the AOP proxy, not self-invocation).
   - Test: `mvn -o test -Dtest=SnapshotPersistenceRetrierTest,SluCalculationServiceIT`, confirm both green (the IT confirms the wiring change didn't break the existing end-to-end snapshot-write flow).

3. **Rapid skill-selection clicks on the radar chart no longer fire one PUT per click.**

   - In `PlayerDevelopmentDashboardPage.vue`, wrap the `store.saveRadarPreferences(playerId.value, codes)` call inside `onSkillSelectionChange` (`:240-244`) in a debounce, mirroring `DrillLibraryPage.vue`'s own established inline pattern exactly (`:164-169` — a local `useDebounce(fn, delay)` helper function defined in the same `<script setup>` block, not a shared composable; this codebase has no shared debounce composable, confirmed by grep, so introducing one here would be a new abstraction beyond this AC's scope). Use the same `300`ms delay `DrillLibraryPage.vue`'s own `debouncedSearch` uses (`:208-211`), for consistency across the two existing debounce usages in this codebase (the third, `TimezoneSelect.vue`'s `input-debounce="200"`, is a Quasar-component prop, not a comparable hand-rolled case).
   - **Do not debounce the visual toggle itself** — `SkillsRadarChart.vue:272-278`'s `toggleSkill` already mutates its own local selection state and emits synchronously on every click, giving instant visual feedback independent of the parent's network call. Only the `store.saveRadarPreferences(...)` call inside `onSkillSelectionChange` needs debouncing; wrapping the whole handler (including any local state the parent itself tracks, if any — confirm none exists before wrapping) would risk introducing a visible lag between click and chart update that does not exist today. Confirm via a full read of `PlayerDevelopmentDashboardPage.vue`'s `onSkillSelectionChange` and surrounding state before implementing — if the parent turns out to hold no local UI state of its own here (only forwarding to the store), wrapping the whole function body is equivalent and simpler; state that confirmation explicitly in the Completion Notes either way.
   - Test: no frontend test framework exists (standing convention); verify by `npx eslint` clean plus a manual dev-server pass as a coach viewing a player's radar chart — click multiple skill nodes in rapid succession, confirm the chart's visual selection updates instantly per click while only one PUT request fires (verify via browser devtools Network tab) roughly 300ms after the last click, not one per click.

4. **`SkillDefinitionResource` goes through a service layer, matching every other resource in this module.**

   - Create `SkillDefinitionService` (new `@Service`, package `com.softropic.skillars.platform.development.service`), moving `SkillDefinitionRepository`/`SkillDefinitionMapper` injection and the existing `findAllByActiveTrueOrderByDisplayOrderAsc().stream().map(skillDefinitionMapper::toDto).toList()` logic (currently inline in the resource, `SkillDefinitionResource.java:24-27`) into a single method, e.g. `getActiveSkillDefinitions(): List<SkillDefinitionDto>`.
   - Update `SkillDefinitionResource.java` to inject `SkillDefinitionService` only, delegating its one endpoint (`GET /api/development/skill-definitions`) to the new method. No behavior change — same response shape, same `@PreAuthorize("isAuthenticated()")` gate, same `@Observed` name.
   - **Bonus, while touching this file**: `SkillDefinitionResource` currently has zero test coverage at any layer (confirmed: no `SkillDefinitionResourceIT`, no `SkillDefinitionServiceTest` exists today). Add both:
     - `SkillDefinitionServiceTest` (unit, Mockito, mirroring `SluPersistenceRetrierTest.java`'s plain-instantiation style): confirms the method delegates to the repository's `findAllByActiveTrueOrderByDisplayOrderAsc()` and maps each result through the mapper.
     - `SkillDefinitionResourceIT` (new, extends `AbstractIntegrationTest`, package `com.softropic.skillars.platform.development.api`): seed one active and one inactive `skill_definitions` row (reuse the existing seeded rows from `V46__development_module_init.sql`'s taxonomy if convenient, or insert directly), hit `GET /api/development/skill-definitions` as an authenticated user, assert the response contains only the active skill and omits the inactive one. Claim id range from the `9650`–`9690` free block used by AC1's IT, immediately following it (e.g. `9651000001`–`9651000005`, register both in the fixture-id registry).
   - Test: `mvn -o test -Dtest=SkillDefinitionServiceTest,SkillDefinitionResourceIT`, confirm both green.

## Tasks / Subtasks

- [ ] Task 1: `coach_radar_preferences` player FK hardening (AC: #1)
  - [ ] Add `V117__coach_radar_preferences_player_fk.sql` (defensive orphan delete + `ADD CONSTRAINT ... ON DELETE CASCADE`)
  - [ ] Add `CoachRadarPreferencePlayerFkIT`, register `9650000001`–`9650000010` in `docs/testing/test-data-isolation.md`
  - [ ] Run `mvn -o test -Dtest=CoachRadarPreferencePlayerFkIT`, confirm green
- [ ] Task 2: SLU snapshot-write retry/compensation (AC: #2)
  - [ ] Add `SnapshotPersistenceRetrier` mirroring `SluPersistenceRetrier`
  - [ ] Wire `SluCalculationService` to call the retrier instead of `SnapshotBatchWriter` directly
  - [ ] Add `SnapshotPersistenceRetrierTest` mirroring `SluPersistenceRetrierTest`'s 3-test shape
  - [ ] Run `mvn -o test -Dtest=SnapshotPersistenceRetrierTest,SluCalculationServiceIT`, confirm both green
- [ ] Task 3: Skill-toggle debounce (AC: #3)
  - [ ] Add local `useDebounce` helper to `PlayerDevelopmentDashboardPage.vue` mirroring `DrillLibraryPage.vue`'s pattern, wrap the `saveRadarPreferences` call at 300ms
  - [ ] Confirm visual toggle stays instant (no debounce on `SkillsRadarChart.vue`'s own local state)
  - [ ] `npx eslint` clean; manual dev-server pass confirming one PUT per burst, not per click
- [ ] Task 4: `SkillDefinitionResource` service-layer extraction (AC: #4)
  - [ ] Add `SkillDefinitionService`, update `SkillDefinitionResource` to delegate to it
  - [ ] Add `SkillDefinitionServiceTest` and `SkillDefinitionResourceIT` (both currently missing), register IT's id range
  - [ ] Run `mvn -o test -Dtest=SkillDefinitionServiceTest,SkillDefinitionResourceIT`, confirm both green
- [x] Task 5: Ledger hygiene (no AC — completed during this story's own creation research pass)
  - [x] Decided with the project owner (2026-08-30): `RadarDisplayService`'s deactivated-skill baseline drop (`skillars-5-4` W4) is intended behavior, not a bug — leave as-is
  - [x] Decided with the project owner (2026-08-30): `DevelopmentCorrelationService`'s hardcoded `IMPROVEMENT_THRESHOLD` (`skillars-5-4` W7) stays fixed — no concrete need to make it configurable
  - [x] Closed/dismissed ~20 further items across `skillars-5-1` through `skillars-5-4` as accepted design tradeoffs or already-resolved-elsewhere (see `deferred-work.md` tags for the full per-item disposition)

## Dev Notes

### Source ledger mapping

- AC1 ← `skillars-5-4` W1 (partially closed by `skillars-deferred-77` AC9, `player_id` remainder picked up here).
- AC2 ← `skillars-5-2` W1 (partially addressed by `skillars-deferred-77` AC8, `writeAll`-specific remainder picked up here).
- AC3 ← `skillars-5-4` W2 (never previously picked up).
- AC4 ← `skillars-5-3` DEF1 (never previously picked up; this story is the "next planned touch" its own ledger text anticipated).
- Task 5's two decided items ← `skillars-5-4` W4 and W7, resolved directly with the project owner during this story's creation rather than picked up as work.

### Architecture / conventions to follow

- **Migration pattern for adding a late FK to an existing table**: `V113__radar_composite_baseline_player_fk.sql` is the direct precedent for AC1 — defensive orphan-delete before `ADD CONSTRAINT`, itself following `V109`'s established pattern. Do not skip the defensive delete even though no application code path is known to hard-delete `player_profiles` today (same caveat `V113`'s own comment makes) — the migration must not depend on that remaining true.
- **`@Retryable` self-invocation pitfall**: `SluPersistenceRetrier`'s own class javadoc is the canonical explanation in this codebase (also cross-referenced from `BookingService.acceptAndInitiatePayment` and `TimelineEventListener`'s `@Lazy @Autowired self` field). AC2's `SnapshotPersistenceRetrier` must be its own `@Component`, never a method added directly onto `SluCalculationService` or `SnapshotBatchWriter`.
- **Fixture id registry**: per `docs/testing/test-data-isolation.md`, every new IT class claims an unused id range and registers it in the table before use. AC1 and AC4 both need one; use the `9650`–`9690` free block for both, contiguous but non-overlapping.
- **No shared frontend debounce composable exists**: confirmed by grep across `src/frontend/src`. `DrillLibraryPage.vue`'s inline `useDebounce(fn, delay)` local helper is the only precedent to mirror for AC3; do not introduce a new `src/composables/useDebounce.js` file as part of this story — that would be a new shared abstraction beyond what this AC needs, and only one other call site exists in the whole codebase to justify one.
- **Testing**: per `docs/validation-strategy.md`, do not run `mvn verify` locally — GitHub CI is the sole full-verification gate. Run targeted `mvn -o test -Dtest=<ClassName>` for touched backend classes.
- **CI context-count ceiling**: `skillars-deferred-83` raised this to 37 (`.github/scripts/assert-context-count.sh`) for an unrelated `@MockitoSpyBean` addition. None of this story's four new IT/unit test classes declare their own `@MockitoBean`/`@MockitoSpyBean`/`@TestPropertySource`/`@SpringBootTest` — all either extend `AbstractIntegrationTest` unchanged (AC1, AC4's IT) or are plain Mockito unit tests with no Spring context at all (AC2, AC4's service unit test) — so none of this story's own work should move that number. If CI reports a different context count than 37 after this story's changes, investigate before assuming it's fine; do not silently bump the ceiling again without confirming why.
- **Implementation order**: all four ACs are fully independent (different files, different layers — schema-only, backend-resilience-only, frontend-only, architecture-only). Any order is fine; no shared state between them.

### Project Structure Notes

Touches four independent areas: `src/main/resources/db/migration/V117__coach_radar_preferences_player_fk.sql` + `src/test/java/.../development/repo/CoachRadarPreferencePlayerFkIT.java` (AC1); `src/main/java/.../development/service/SnapshotPersistenceRetrier.java` + `SluCalculationService.java` (modified) + `src/test/java/.../development/service/SnapshotPersistenceRetrierTest.java` (AC2); `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue` (modified, AC3); `src/main/java/.../development/service/SkillDefinitionService.java` (new) + `SkillDefinitionResource.java` (modified) + `src/test/java/.../development/service/SkillDefinitionServiceTest.java` + `src/test/java/.../development/api/SkillDefinitionResourceIT.java` (AC4). Plus `docs/testing/test-data-isolation.md` (registry entries for AC1/AC4's new IT id ranges) and `_bmad-output/implementation-artifacts/deferred-work.md` (ledger tags for Task 5's closures/decisions, done during story creation). No frontend i18n changes needed (AC3 has no new user-facing strings). No backend API contract changes (AC4's endpoint response is unchanged; AC1/AC2 are invisible to any API consumer).

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source ledger, `skillars-5-1` through `skillars-5-4` code-review sections (now annotated `[PICKED UP by skillars-deferred-84 ...]`/`[DECIDED 2026-08-30 ...]`/ledger-hygiene closures).
- [Source: `skillars-deferred-77` AC8/AC9] — the `SluPersistenceRetrier` and `V113` FK precedents AC1/AC2 both directly mirror.
- [Source: `skillars-deferred-83` — `.github/scripts/assert-context-count.sh`] — the CI context-count ceiling this story's new tests must not move.
- [[project_skillars_release_workflow]] — this story's own creation followed the standing SLU/Radar-first re-mining priority and this project's "no small stories" convention.

## Dev Agent Record

### Completion Notes

_To be filled in by the dev agent during implementation._

### File List

_To be filled in by the dev agent during implementation._

## Change Log

- 2026-08-30: Story created from `deferred-work.md` mining. SLU/Radar module (`skillars-5-1` through `skillars-5-4` code-review sections, 2026-06-18/19) had not been re-mined during the four-module rotation `skillars-deferred-80` through `-83` used — this pass covered it directly, per standing priority order. Six candidates verified live against current source; two required a project-owner decision (deactivated-skill baseline display behavior, and whether to make `IMPROVEMENT_THRESHOLD` configurable) — both resolved directly with the project owner (2026-08-30): leave both as-is, neither is a bug or a concrete near-term need. Four items survived as genuinely open, independently verified, and became AC1 (`coach_radar_preferences` player FK), AC2 (SLU snapshot-write retry/compensation), AC3 (skill-toggle debounce), and AC4 (`SkillDefinitionResource` service-layer extraction). ~20 further items across the six sections closed as ledger hygiene (accepted tradeoffs, already resolved elsewhere, or already confirmed fixed by a prior audit) — see Task 5. Status: ready-for-dev.
