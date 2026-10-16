# Pre-Implementation Story Review — skillars-12-4-coach-facing-player-detail-page

| | |
|---|---|
| **Story** | `skillars-12-4-coach-facing-player-detail-page` (Status: `ready-for-dev`) |
| **Story file** | `_bmad-output/implementation-artifacts/skillars-12-4-coach-facing-player-detail-page.md` |
| **Audit date** | 2026-10-15 |
| **Real HEAD at audit time** | `5c069307 docs(story): create skillars-12-4-coach-facing-player-detail-page` |
| **Method** | `/mto-story-review` — 4 parallel read-only verification layers + mandatory orchestrator-level adversarial re-verification (Step 4b) |

> **Verification basis.** Every citation, ledger reference, precedent attribution and mechanistic claim
> below was checked against the working tree **as it stands at `5c069307`**, obtained by running
> `git rev-parse --short HEAD` directly — *not* against any SHA, line number or "already verified"
> assertion written inside the story itself. The story's own Source section claims it was
> "re-verified against current HEAD during drafting"; that claim was treated as unverified input.
>
> Note: stale duplicate copies of the tree exist under `./.claude/worktrees/`. Only the live tree was verified.

**Scope checked:** 20 code citations, 11 ledger/precedent attributions, 12 mechanistic claim clusters,
plus a full AC-by-AC code-path sweep. **11 findings survived** Step 4b; **14 candidate findings were
killed or downgraded** by it (§6).

---

## 1. Citation verification (Layer 1)

All 20 citations resolve. Nineteen are exact; one has a stale end-boundary.

| # | Citation | Verdict | Evidence at real HEAD |
|---|---|---|---|
| 1 | `SluDashboardService.java:132-138` — the `requireCoachPlayerRelationshipIfCoach` helper | **MATCH** (exact) | Helper occupies exactly 132–138. Call sites at `:43` (`getWeeklyExposure`), `:98` (`getSkillTrends`), `:128` (`getNarrativeSummary`) |
| 2 | `SluDashboardService.java:78-83` — `neglectedSkillCodes` population | **MATCH** (exact) | `78: List<String> neglectedCodes = flagRepository.findByPlayerIdAndResolvedAtIsNull(playerId)` … `83: return new SkillExposureResponse(currentWeekMap, trend, neglectedCodes);` |
| 3 | `SluTargetService.java:23` — `CoachPlayerAuthorizationService` already injected | **MATCH** (exact) | `23: private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;` (Lombok `@RequiredArgsConstructor`, `:18`) |
| 4 | `SluTargetService.java:52-58` — `getNeglectedSkills` | **MATCH** (exact) | Method body is a bare `flagRepository` read; no `coachId` param, no authorization call |
| 5 | `SluContributionService.java:28-29` — only 2 constructor deps | **MATCH** (exact) | `28: SluRepository`, `29: CoachProfileService`; the only two `private final` fields |
| 6 | `SluContributionService.java:35` — `getCoachContributions(Long, Instant)` | **MATCH** (exact) | Signature exact; no authorization check anywhere in 35–91 |
| 7 | `NeglectedSkillResource.java:22` — dual-access `@PreAuthorize` | **MATCH** (exact) | `hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)` |
| 8 | `SluTargetResource.java:35,45` — coachId resolution | **MATCH** (exact) | Identical `coachProfileService.getCoachIdByUserId(securityUtil.getCurrentCoachUserId())` on both lines |
| 9 | `routes.js:163-167` — existing `coach/players` route + meta | **MATCH** | Real object literal is 162–167 (opening brace on 162); every content line is inside the cited range |
| 10 | `ParentDevelopmentPortalPage.vue:97/114` — `PlayerTimelinePanel` | **MATCH** (exact) | `97: <PlayerTimelinePanel :player-id="playerId" />` (no `:key` today), `114:` the import |
| 11 | `ParentDevelopmentPortalPage.vue:62/112/159` — `CoachContributionSection` | **MATCH** (exact) | `62:` binding, `112:` import, `159: store.fetchCoachContributions(id),` (arg is `loadPortal`'s `id` param, i.e. the playerId) |
| 12 | `ParentDevelopmentPortalPage.vue:131-135` — `neglectedSkillNames` | **MATCH** (exact) | Code→`displayName` map with `?? c` raw-code fallback |
| 13 | `ParentDevelopmentPortalPage.vue:138-143` — `pageError` | **MATCH** (exact) | Aggregates `[store.error, bookingStore.packsError, store.coachContributionsError]`, returns first truthy |
| 14 | `ParentDevelopmentPortalPage.vue:171-176` — playerId watcher | **MATCH** (exact) | A second, unrelated watcher on `playerStore.activePlayerId` sits at 182–189 — correctly *not* the one cited |
| 15 | `PlayerTimelinePanel.vue:100-103` — fetch only in `onMounted` | **MATCH** (exact) | `grep -n "watch"` over the whole file returns nothing |
| 16 | `development.store.js:46` — `neglectedCodes` | **MATCH** (exact) | `const neglectedCodes = computed(() => exposure.value?.neglectedSkillCodes ?? [])` |
| 17 | `en-US/index.js:246-264` — `coach.myPlayers*`/`coach.sessionPrep*` block | **MATCH** (exact) | `myPlayers*` 246–254, `sessionPrep*` 255–264, inside `coach: {` opened at `:239` |
| 18 | `SkillExposureResourceIT.java:113` — COACH2 has a COMPLETED booking with PLAYER_ID | **MATCH** (exact) | `113: insertCompletedBooking(coach2ProfileId, PARENT_USER_ID, PLAYER_ID);` — `COMPLETED` is in `ACTIVE_RELATIONSHIP_STATUSES`, so the story is right that COACH2 cannot serve as the negative fixture |
| 19 | `SkillExposureResourceIT.java:117-126` — `insertCompletedBooking` helper | **MATCH, stale end-boundary** | Real range is **118–129**. Cited range starts on a blank line and truncates the final 3 lines. Content unambiguous |
| 20 | `vitest.config.mjs` — `globals: false` | **MATCH** | `:47 globals: false,` with in-file rationale at 45–46 |

**Zero DRIFTED, zero CANNOT_LOCATE.** The story's three independently-verifiable *negative* claims also
hold: `getNeglectedSkills` has no authorization call, `getCoachContributions` has no authorization call,
and `PlayerTimelinePanel.vue` contains no `watch` at all.

---

## 2. Ledger & precedent attribution (Layer 2)

Each claim was checked against **all three** of (a) the source document, (b) source-code
comments/Javadoc near the code in question, (c) `git log --grep` / `git blame`. No fabrications found.

| # | Claim | Sources checked | Verdict |
|---|---|---|---|
| 1 | Brainstorm Priority 4 = "Coach-Facing Player-Detail Page"; Converge #12 quote | (a) brainstorm L198 (build order), L154 (Converge #12 `_Concept:_`), L235 (Action Planning); (c) `git show 5c069307` | **CONFIRMED** — quote is byte-for-byte, and it is under `### SCAMPER Method` → `**[Converge #12]: Reused Player Panels on a New Coach Page**` |
| 2 | Priorities 1–3 = 12-1/12-2/12-3, all `done` | (a) brainstorm L195–197; `sprint-status.yaml` L365/366/368 | **CONFIRMED** — all three `done`; 12-4 is `ready-for-dev` at L367 |
| 3 | Priority 5 = recap→report Fast-Follow; player/parent flag parked | (a) brainstorm L199–200, L190, L175, L243, L262 | **CONFIRMED** — `**[Deferred #17]: Player/Parent-Initiated Flag … intentionally parked**` |
| 4 | `deferred-work.md` 12-1-code-review heading + "Worth settling alongside…" quote | (a) `deferred-work.md:3782`, `:3785`; (c) `git log -L 3785,3785` → introduced `d7fb01bb` | **CONFIRMED** verbatim |
| 5 | Brainstorm **Role Playing** section's four-endpoint side-observation | (a) brainstorm L120 under `### Role Playing` (L97); (b) `SluDashboardService.java:132-138` + callers `:43,:98,:128` | **CONFIRMED** — quote verbatim, in the right section, **and the story's "only half right" correction of it is itself correct** |
| 6 | `deferred-work.md` UI-gap-scan heading + `/neglected-skills` supersession quote | (a) `deferred-work.md:3761`, `:3768`; (b) `development.store.js:46` | **CONFIRMED** verbatim |
| 7 | Story line 22's claim that coverage annotations were **already written** into both source docs | (a) both files at HEAD; (c) `git show --stat 5c069307` | **CONFIRMED empirically** — HEAD touches 4 files incl. both docs; annotations present at brainstorm L141/L151/L235 and `deferred-work.md:3768`/`:3785` |
| 8 | "skillars-12-3 Task 4.2" established the `playerName`-from-parent precedent | (a) 12-3 story Task 4.2/4.3/AC5/review log; (b) real `SessionPrepCard.vue:4,59,72-74`, `CoachCommandCenterPage.vue:322` | **PARTIALLY_CONFIRMED** — precedent is real and shipped as described, but the *rationale* lives in 12-3's **AC5** and **Task 4.3**, not Task 4.2. See §5 F-10 |
| 9 | `ROSTER_MEMBERSHIP_STATUSES` = 10, `ACTIVE_RELATIONSHIP_STATUSES` = 4, 6 extras named | (a) `deferred-work.md:3785`; (b) `CoachRosterService.java:43-56` + its own comment, `CoachPlayerAuthorizationService.java:20-21`; (c) `git blame -L 50,57` | **CONTRADICTED** — ledger text confirmed verbatim, but the real source says **12** and **8**. See §5 F-8 |
| 10 | `docs/validation-strategy.md` records the no-local-`mvn verify` convention | (a) full file (75 lines) | **CONFIRMED** in substance (§2 + L38); it is a default with three carve-outs, and the story paraphrases rather than quotes — not presented as a quote, so not a defect |
| 11 | `ReportGenerationService`'s independent third SLU-only gate, found during 12-3's review | (a) 12-3 story L35/105/127/187, `deferred-work.md:3800-3802`; (b) `ReportGenerationService.java:138-149`; (c) `grep -rn findLastSessionDate` | **CONFIRMED** — real code matches the ledger quote incl. its `// Coach-player relationship guard` comment. One-word nit: it was found during the **pre-implementation story review**, not the code review (12-3 has separate ledger headings for each) |

---

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict | Deciding evidence |
|---|---|---|
| **A.** The helper already gates exactly `getWeeklyExposure`, `getSkillTrends`, `getNarrativeSummary` | **TRUE** | Those are the class's only three public methods, and the helper is the first statement of each body (`:43`, `:98`, `:128`). One definition + three call sites repo-wide. `SluDashboardService`'s only consumer is `SkillExposureResource` (3 endpoints) → no endpoint it serves is left ungated |
| **B.** Helper shape / exception / dependency set | **PARTIALLY_TRUE** | Dependency set is exactly right (`SecurityUtil` + `PlayerProfileRepository` + `CoachPlayerAuthorizationService`, no more). First condition is as stated. `OperationNotAllowedException` is right but is thrown by the *delegate* (`CoachPlayerAuthorizationService:33,46`), not the helper. **The story omits the helper's second condition** → §5 F-9. Confusable siblings `existsByIdAndUserId`/`existsByUserId`/`findByIdAndParentId` all exist in `PlayerProfileRepository` (`:35,:46,:51`) and the helper uses none of them |
| **B-txn.** Transaction boundary | **Traced, not asserted** | `SluDashboardService` is class-level `@Transactional(readOnly = true)`; the helper is `private` and self-invoked, so no proxy — it inherits the caller's read-only transaction. `CoachPlayerAuthorizationService` is also `readOnly=true` and joins the same transaction (default `REQUIRED`). Nothing commits; there is no partial-commit or rollback surface in either fix |
| **C.** `getNeglectedSkills` has no `coachId` and no check; `getTargets`/`setTargets` take explicit `UUID coachId` | **TRUE** | `:26-27`, `:35-36`, `:53-57`. Also traced: `setTargets` self-invokes `getTargets` at `:49`, so the writes + final read commit together in one read-write transaction with the auth check preceding every write |
| **D.** `SluContributionService` constructor is `(SluRepository, CoachProfileService)`; `getCoachContributions` ungated | **TRUE** | Confirmed by Lombok field set **and** independently by `SluContributionServiceTest:37`'s direct `new SluContributionService(sluRepository, coachProfileService)`. Class has exactly one public method, so fixing that one is complete *within the class* |
| **E.** Resource split: `/targets` coach-only, `/neglected-skills` dual-access, `/slu/coach-contributions` on `SkillExposureResource` | **PARTIALLY_TRUE** | All three attributions correct (`SluTargetResource:31-32,39-40`; `NeglectedSkillResource:21-22`; `SkillExposureResource:56`). `SecurityConstants:35` → `HAS_COACH_ROLE = "hasRole('ROLE_COACH')"`. Correction: that annotation is **method-level on both methods**, not class-level; neither resource has a class-level `@RequestMapping` or `@PreAuthorize` |
| **F.** All `SkillExposureResource` `@PreAuthorize` lines identical | **TRUE** | All four (`:30,:40,:50,:57`) character-for-character identical |
| **G.** `PlayerTimelinePanel` fetches only in `onMounted`, no prop watcher; `:player-id` binding correct | **TRUE** | `:56` imports only `onMounted`; `:61 playerId: { type: [Number, String], required: true }`; `:100-103` sole fetch. A changed `:key` destroys/recreates → re-runs `onMounted` → the `:key` fix does work |
| **H.** All nine store symbols exist as named | **TRUE** | `:22,:23,:27,:42,:44,:46,:48,:58,:214`, all exported. `fetchCoachContributions(playerId)` takes only `playerId` (the `days = 30` default lives in `development.api.js:46`). `fetchExposure(playerId, weeks = 8)`'s second param is optional, so the story's single-arg call works |
| **I.** `CoachContributionSection` prop is `contributions` | **TRUE** | `:23 defineProps({ contributions: { type: Array, default: () => [] } })` |
| **J.** `/exposure` and `/neglected-skills` read the same data | **TRUE** | Identical `flagRepository.findByPlayerIdAndResolvedAtIsNull(playerId)` call, no filter/limit/sort/threshold in either path. Only difference is shape: `/exposure` flattens to codes, `getNeglectedSkills` keeps `detectedAt`. Confusable sibling `findByPlayerIdAndSkillCodeAndResolvedAtIsNull` (`:13`) used by neither |
| **K.** `MyPlayersPage.vue` rows are a dead end | **TRUE** | Row div `:35-39` has no `@click`/`:to`/`router-link`; `useRouter` not imported. Row fields `player.playerId` (`:37`), `player.playerName` (`:42`) exist as AC5 assumes |
| **L(i)** No `SluTargetServiceTest` today | **TRUE** | `find` returns nothing (a `SluDashboardServiceTest` does exist, with all six mocks) |
| **L(ii)** `SluContributionServiceTest` is JUnit5+Mockito, no Spring | **TRUE** | `@ExtendWith(MockitoExtension.class)` `:24`, `@Mock` fields only, direct `new` in `setUp` |
| **L(iii)** `SkillExposureResourceIT` spans three controllers | **TRUE** | Hosts `/exposure` (133,153,169,179), `/targets` (188,205,228,297), `/neglected-skills` (245), `/narrative` (274,312,322) |
| **L(iv)** `/slu/coach-contributions` has **zero** IT coverage | **FALSE** | **Six ITs exist** in `ParentDevelopmentPortalResourceIT`. See §5 F-1 — highest-severity finding in this review |
| **L(v)** `COACH2_USER_ID` cannot be the no-relationship fixture | **TRUE** | `setUp` seeds `COMPLETED` bookings for *both* coaches (`:112,:113`); `COMPLETED` ∈ `ACTIVE_RELATIONSHIP_STATUSES` |

---

## 4. Findings that survived adversarial re-verification — HIGH

### F-1 (HIGH) — AC9's "zero IT coverage" is false, and the story's own fix turns a currently-green IT red

**Story text (AC9, line 34):** *"`/slu/coach-contributions` currently has **zero** IT coverage of any kind (confirmed via grep)."*
**Task 3.3** accordingly asks for *"a first-ever happy-path 200 test"*.

**Reality.** `src/test/java/com/softropic/skillars/platform/development/api/ParentDevelopmentPortalResourceIT.java`
has **six** ITs on that exact endpoint — `:107`, `:140`, `:150`, `:160`, `:170`, `:213`, all via
`contributionsUrl(playerId)` (`:259-261`). A plain `grep -rn "coach-contributions" src/test` finds this
file, so the story's "confirmed via grep" did not actually run against the whole test tree.

One of them directly contradicts the story's own fix (`:168-209`):

```java
@Test
void getCoachContributions_asCoach_returns200() {
    long coachUserId = 9610000001L;
    ...
    transactionTemplate.execute(status -> {
        insertAuthority(9612, "ROLE_COACH");
        insertUser(coachUserId, coachEmail, passwordHash, "COACH");
        grantRole(coachUserId, "ROLE_COACH");
        jdbcTemplate.update("INSERT INTO marketplace.coach_profiles ...", coachProfileId, coachUserId);
        ...
    });
    ...
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
```

I independently verified the decisive fact: **`grep -n "booking" ParentDevelopmentPortalResourceIT.java`
returns nothing at all** — this IT seeds no `booking.bookings` rows anywhere. So its coach has
`ROLE_COACH`, a `coach_profiles` row, and **no relationship whatsoever** with `PLAYER_ID`, and it asserts
`200 OK` today.

**Failure scenario.** After Task 2.2 lands the gate: `isCurrentUserInRole(COACH)` → true; the coach is
not `PLAYER_ID`'s parent so `existsByIdAndParentId` → false; `requireCoachPlayerRelationship` finds no
booking → `OperationNotAllowedException` → **403**. `httpTestClient` throws `HttpClientErrorException`,
the assertion never runs, **CI goes red**. No task in the story touches this file.

**Also checked:** the sibling `getCoachContributions_percentages_sumTo100PerSkill` (`:213`) authenticates
as `PARENT_EMAIL` (`:234`), so it takes the non-coach path and is unaffected. Only the one test breaks.

**Required:** Task 3.3 must be rewritten — the happy path already exists; the work is to either give
that coach a booking fixture or re-point the test at a 403 expectation, and to decide where the new
coverage belongs (that file, not `SkillExposureResourceIT`, is where this endpoint's ITs actually live).
*Affects AC2, AC9, Task 3.3.*

> The story **does** already anticipate the related unit-test break: Task 2.3 says to update
> `SluContributionServiceTest`'s constructor call (`:37` calls the 2-arg constructor directly). That part
> is covered.

### F-2 (HIGH) — The previous player's data renders under the new player's name on the normal roster→detail path

**Evidence.** The store is an app-lifetime Pinia singleton and **never clears its data refs** — only its
error refs. `development.store.js:58-69`:

```js
async function fetchExposure(playerId, weeks = 8) {
  loading.value = true
  error.value = null            // error cleared…
  try {
    const response = await getSkillExposure(playerId, weeks)
    exposure.value = response   // …but exposure.value is only ever overwritten on SUCCESS
  } catch (err) {
    error.value = err?.response?.data?.message ?? 'Failed to load skill exposure'
  } finally { loading.value = false }
}
```

`fetchCoachContributions` (`:214-234`) has the same shape — `coachContributions.value` is untouched on
failure. `neglectedCodes` derives straight off `exposure.value` (`:46`). The precedent the story copies
renders every section **unconditionally**, with `q-inner-loading` as a translucent *overlay*, not a render
gate (`ParentDevelopmentPortalPage.vue:3` vs `:62/:73/:81`), and Task 4.2 specifies a "loading spinner"
without saying the sections are gated on it.

**Failure scenario.** Coach opens Player A (200 OK) → sidebar → My Players → clicks Player B. The heading
paints "Player B" synchronously from `route.query.playerName` while the neglected-skills list and
`CoachContributionSection` still hold **A's** data for the duration of the round trip. If B's `/exposure`
403s — which is exactly AC6's own wider-roster-status case — `loading` goes false and **Player A's
neglected skills and Player A's coach-contribution breakdown remain on screen indefinitely, labelled
"Player B"**, next to the error banner. This is a cross-player data-leak-shaped misattribution, not a
cosmetic flash.

**Note on AC7.** AC7's `:key` + `watch` only fire for a *param-only* navigation. AC4 adds no on-page
player switcher, so no UI path produces one — roster→A→roster→B is a route-record change (full
unmount/remount) each time. The `:key` is harmless and worth keeping, but it does **not** address F-2;
the page needs the data refs cleared (or the sections gated) on player change.
*Affects AC4, AC6, AC7, Task 4.2.*

### F-3 (HIGH) — Tsid precision trap on `route.params.playerId` is unstated, and Task 5.2's fixture cannot catch it

**Evidence.** `CommonConfig.java:48` installs `module.addSerializer(Long.class, ToStringSerializer.instance)`,
so `RosterEntryResponse.playerId` (a `Long`) reaches the browser as a JSON **string**. The precedent page
therefore reads the param raw — `ParentDevelopmentPortalPage.vue:123: const playerId = computed(() => route.params.playerId)`
— with no `Number()`. This is a **twice-fixed production bug** on these exact pages, documented at the top
of `ParentDevelopmentPortalPageSpec.js:1-10`:

```
// skillars-deferred-148 AC1: ParentDevelopmentPortalPage.vue read the :playerId route param via
// Number(route.params.playerId). Player ids are backend Tsids (18-19 digits), well past
// Number.MAX_SAFE_INTEGER ... converting it back to a Number here silently corrupted it, so every
// development API call on this page hit a player id that does not exist.
// Mirrors PlayerDevelopmentDashboardPageSpec.js's precedent (deferred-147).
```

Tasks 4.2 / 4.3 / 5.1 / 5.2 say only "reads `playerId` from `route.params`" and never state the
constraint. Worse, the fixture Task 5.2 extends uses a tiny id — `MyPlayersPageSpec.js:15: playerId: 1` —
so a navigation test written on it **passes even under a `Number()` corruption**.

**Failure scenario.** A dev writes `Number(route.params.playerId)` (the precise prior regression), every
API call on the new page hits a nonexistent id, the coach sees an empty page with no error, and the new
tests are green.

**Required:** state the raw-string constraint in Task 4.2, and make Task 5.2/5.1 assert against a
realistic 18–19-digit Tsid so the mutation is actually caught. *Affects AC4, AC5, AC10.*

---

## 5. Findings that survived adversarial re-verification — MEDIUM / LOW

### F-4 (MEDIUM) — `CoachContributionSection` renders literally nothing in the most likely state, so AC4(b)'s section silently vanishes

`CoachContributionSection.vue:2` puts the `v-if` on the **root** element and `:25-36` filters hard:

```js
<div v-if="topAttributions.length > 0">
...
  return Object.values(bySkill).filter((c) => c.percentageContribution >= 30)
```

It keeps only the **top** coach per skill, then drops anything under 30%. The endpoint window defaults to
30 days (`development.api.js:46`, `SkillExposureResource.java:61`) while the roster admits players up to
**730 days** stale (`CoachRosterService.java:58`).

**Failure scenario.** A coach clicks a dormant roster row → `[]` → the middle of the three sections
produces **zero DOM**: no heading, no empty state, nothing to distinguish "no recent data" from "broken".
Task 4.2 gives this section no wrapper/heading/empty state, unlike the neglected-skills section which
gets `coach.playerDetailNoNeglectedSkills`. AC10's "all three sections appear" assertion is only writable
with ≥30% seeded data, so the common case stays untested.

Secondary: the story's own narrative promise — *"how much of their recent progress came from training
with me versus other coaches"* — is not what this component shows. It names only the single top
contributor per skill and **omits the viewing coach entirely** if they are under 30%.
*Affects AC4, AC10, Task 4.2.*

### F-5 (MEDIUM) — AC6's banner can only ever show hardcoded English or a raw i18n key, contradicting AC8

Both store error refs derive from `err?.response?.data?.message` (`development.store.js:65`, `:227`), but
this backend's error body has **no top-level `message`**. I read `ErrorDto` directly — its only fields are:

```java
19:    private final String helpCode;
20:    private final ErrorMsg errorMsg;
22:    private final List<FieldErrorDto> fieldErrors = new ArrayList<>();
```

and the project's own axios interceptor reads `data?.errorMsg?.message` (`boot/axios.js:228`). So
`data.message` is always `undefined` and the `??` fallback always wins:

- `error` → `'Failed to load skill exposure'` — **hardcoded English**
- `coachContributionsError` → `'development.portal.contributionsLoadError'` — **a literal i18n key**

and the precedent interpolates it raw: `ParentDevelopmentPortalPage.vue:8: {{ pageError }}`, not `$t(...)`.

**Failure scenario.** A `de-DE` coach clicks a roster row whose only booking is `PAUSED`; contributions
403s; the banner displays the literal string `development.portal.contributionsLoadError`. Meanwhile AC8
declares a `coach.playerDetailError` key that Task 4.2 never uses. *Affects AC6, AC8.*

### F-6 (MEDIUM) — `fetchExposure` has no in-flight sequence guard, unlike `fetchCoachContributions`

`development.store.js:212-234` guards contributions with `_coachContributionsSeq` on all three of
assignment, error and `loading`. `:58-69` `fetchExposure` has **no equivalent** (nor do
`fetchSkillDefinitions` `:48-56` or `fetchTimeline` `:199-210`), and its `finally { loading.value = false }`
fires for whichever request settles last regardless of which player it belonged to.

**Failure scenario.** A→B→A within one round trip (double-click, or back/forward on the roster) issues A1,
B, A2. If B resolves last, `exposure.value` holds **B's** data while the route and heading say **A** — and
`coachContributions` is correctly A2's *because it is guarded*. Result: Player A's name, Player B's
neglected skills, Player A's contributions, **and no error at all**. A coach roster holds many more
players than the single-child parent page this pattern was copied from, so rapid switching is materially
likelier here. *Affects AC7.*

### F-7 (MEDIUM) — Task 5.1's test recipe is not runnable as written

Two blockers, both verified:

1. **Pinia is not global.** `src/frontend/test/vitest/setup-file.js` ends with: *"Pinia is intentionally
   NOT installed globally here. Specs that need a store opt in with `createTestingPinia()`."* The new page
   and its child both call `useDevelopmentStore()` (`PlayerTimelinePanel.vue:64`). Task 5.1 specifies only
   "`vi.mock` `development.api.js`, mount with a route/query stub" — no Pinia, and no router for `useRoute()`.
2. **The api mock must be total.** `development.store.js:3-19` imports **15** named exports from
   `src/api/development.api`. A `vi.mock` factory returning fewer throws *"No X export is defined on the
   mock"*. The fixture Task 5.2 points at does exactly that — `MyPlayersPageSpec.js:7-9` mocks a single
   export (`getCoachRoster`), which is fine for `MyPlayersPage` because it never touches the store, but
   fatal for the new page.

The established precedent for a store-backed page is different: `ParentDevelopmentPortalPageSpec.js:17-19`
mocks `src/boot/axios` and uses `createTestingPinia` + a real memory router. Task 5.1 should point there.
*Affects AC10.*

### F-8 (MEDIUM) — Stale status counts: the figures are 12 and 8, not 10 and 6

AC6 (line 31) says *"`ROSTER_MEMBERSHIP_STATUSES` (10 statuses)"* and Dev Notes (line 77) says *"10
statuses: includes `NO_SHOW_PLAYER`, `NO_SHOW_COACH`, `PAYMENT_PENDING`, `IN_PROGRESS`, `PAUSED`,
`COMPLETED_PENDING_CONFIRMATION`"* and *"the six wider statuses"*.

I counted both lists myself at HEAD. `CoachRosterService.java:53-56` holds **12**:

```java
private static final List<String> ROSTER_MEMBERSHIP_STATUSES = List.of(
    "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED",
    "COMPLETED_PENDING_CONFIRMATION", "COMPLETED", "NO_SHOW_PLAYER", "NO_SHOW_COACH",
    "DISPUTED", "REFUND_PENDING");
```

`CoachPlayerAuthorizationService.java:20-21` holds 4 (`ACCEPTED`, `CONFIRMED`, `COMPLETED`, `UPCOMING`) —
correct. Set difference is **8**, not 6: the six named **plus `DISPUTED` and `REFUND_PENDING`**.

`git blame` shows the two extras were added in `d7fb01bb` — *the same commit that introduced the ledger
bullet* — with an in-source comment recording the decision (`CoachRosterService.java:47-52`):
*"skillars-12-1 code review (2026-10-15) Patch, owner decision: DISPUTED and REFUND_PENDING added — both
are reachable from COMPLETED … and represent a session that genuinely happened."*

So the story faithfully propagated figures that were already stale in `deferred-work.md:3785`.

**Why it matters.** AC6's residual-risk surface is a third larger than stated, and `DISPUTED` /
`REFUND_PENDING` are precisely the mid-dispute sessions 12-1's owner decision described as *"a session
that genuinely happened"* — i.e. among the pairs a coach is most likely to click into and now get a 403
on. **The upstream ledger entry should be corrected too, not just the story.** *Affects AC6, Dev Notes.*

### F-9 (LOW) — AC3 and Task 1.3 describe only 2 of the helper's 3 branches

The real helper is a **two-condition** guard (`SluDashboardService.java:132-134`):

```java
private void requireCoachPlayerRelationshipIfCoach(Long playerId) {
    if (securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)
            && !playerProfileRepository.existsByIdAndParentId(playerId, securityUtil.requireCurrentUserId())) {
```

AC3 (line 28) describes only *"its own first condition"*. The second condition exempts **a coach who is
also the player's parent** from the relationship check entirely — which is why `PlayerProfileRepository`
is in the dependency list at all. AC1's "byte-for-byte equivalent" instruction saves a careful
implementer, but Task 1.3's unit-test spec covers only the parent/player path and the coach
with/without-relationship paths; the coach-is-also-parent branch is never specified, so the new
`SluTargetServiceTest` will under-cover the helper it is meant to pin down.

**Pre-existing adjacent gap (noted, not a defect in this story):** the exemption checks only
`existsByIdAndParentId`, not the `existsByIdAndUserId` branch that `PlayerOwnershipGuard.java:33-34` was
explicitly fixed to add (deferred-147) — so a self-registered adult player who also held `ROLE_COACH`
would be 403'd on their own profile. **Verified not currently triggerable:**
`CoachRegistrationService.java:84,100` sets `authorities = Set.of(coachAuthority)` plus a single
`skillarsRole`, and `JwtManagerImpl.java:119-121` states *"no production path grants a second role-bearing
authority after account creation."* The same incomplete predicate already exists in
`RadarDisplayService.java:46` and `ReportGenerationService.java:231`. *Affects AC3, Task 1.3.*

### F-10 (LOW) — Four attribution/citation imprecisions

| Story text | Correction |
|---|---|
| "(skillars-12-3 Task 4.2)" for the `playerName`-from-parent precedent (line 30) | The rationale lives in 12-3's **AC5** and **Task 4.3**. Task 4.2 additionally specified a `playerId` prop that 12-3's code review **deleted as dead**, making it the least reliable line to cite |
| "found during `skillars-12-3`'s review" (AC11, line 36) | It was 12-3's **pre-implementation story review**; 12-3 has separate `deferred-work.md` headings for its story review (`:3800`) and its code review (`:3804`) |
| `SkillExposureResourceIT.java:117-126` (Task 3.1) | Real helper range is **118–129** |
| "`SluTargetResource` **is** `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`" (AC1) | Method-level on both methods (`:32`, `:40`), not class-level. Substance (coach-only, no ownership-guard alternative) is correct |

### F-11 (LOW) — No exit from a bookmarked detail URL

AC4 enumerates exactly three pieces and adds no breadcrumb or back-to-roster control. AC5 explicitly
anticipates the bookmark/share entry path (that is why `coach.playerDetailUnknownPlayerName` exists), and
on that path the only way back is the sidebar. Worth one line in Task 4.2. *Affects AC4, AC5.*

---

## 6. What did **not** survive re-verification

Listed so the surviving findings can be weighed honestly. Each was raised by a layer and then killed or
downgraded at Step 4b.

**Killed — the claim was checked and the code is sound:**

1. **ID-space mismatch between the route param and the four endpoints.** The single highest-risk
   hypothesis in this codebase (`Booking.playerId` is a `PlayerProfile` PK, not a `User` id) is **clean
   here**. `RosterEntryResponse.playerId` = `booking.player_id` = `PlayerProfile` PK
   (`CoachRosterService.java:80-84` feeds those ids straight into `playerProfileRepository.findAllById`);
   `CoachPlayerAuthorizationService.java:44` compares the same space; `PlayerOwnershipGuard.java:33-34`
   and `SluDashboardService.java:134` use `PlayerProfileRepository.existsByIdAnd*`; `/neglected-skills`,
   `/exposure`, `/slu/coach-contributions` and `/timeline` all bind `@PathVariable Long playerId` in that
   space. The only id hazard is **precision** (F-3), not space.
2. **An internal/system/scheduled caller broken by a SecurityContext-dependent check.** Whole-repo grep:
   `SluTargetService` is injected only by `SluTargetResource` + `NeglectedSkillResource`;
   `SluContributionService` only by `SkillExposureResource`. No scheduler, batch job, report generator or
   SecurityContext-less caller exists. This was the brief's designated highest-value risk and it is clean.
3. **Route conflict or a missing `requiresCoach` guard.** `coach/players` (`routes.js:162-167`) and
   `coach/players/:playerId` cannot collide; the catch-all is last and outside `MainLayout`'s children
   (`routes.js:382-386`); nothing else matches `coach/players/*`. `meta.requiresCoach` **is** enforced
   (`router/index.js:49`, `:69-72`), and `MainLayout.vue:157`'s nav item stays correctly active on the child path.
4. **`Promise.all` leaving a stuck spinner on partial failure.** All three fetch actions swallow and never
   rethrow (`:53-55`, `:64-68`, `:224-233`), so `Promise.all` always resolves. (`submitRadarAssessment` /
   `generateReport` *do* rethrow — the page calls neither.)
5. **Sticky error state from a previous failed visit.** `error.value = null` / `coachContributionsError.value = null`
   run synchronously before the first `await` (`:61-62`, `:217-218`), so a stale error clears on mount.
   (The *data* is not cleared — that is F-2, which survived.)
6. **`PlayerTimelinePanel`'s error being orphaned by AC6's two-field aggregation.** It renders its own
   banner from `store.timelineError` (`:9-16`). And for an unrelated coach the timeline returns
   `accessExpired: true` with empty events rather than a 403 (`TimelineQueryService.java:42-51`), so it
   degrades independently. It also will not stale-flash: its template gates on `!store.timelineLoading`
   (`:10`, `:29`), which is set synchronously.
7. **Task 3.1's IT fixture being infeasible.** `SkillExposureResourceIT.setUp()` already wraps everything
   in `transactionTemplate.execute` (`:78-115`), so a fixture added there commits — the known
   bare-`jdbcTemplate`/hikari-autocommit hazard is avoided. No existing test depends on a coach or row
   count a third coach would perturb (`getNeglectedSkills_returnsOpenFlags:269`'s `hasSize(1)` is against
   per-test flag inserts for `PLAYER_ID` only). `insertUser`'s derived phone (`:408`) stays unique.
   `chk_bkg_end_after_start` is a non-issue — the new coach inserts no booking, and `insertCompletedBooking`
   uses a fixed 3600s gap, so it is not the known two-`Instant.now()` flake either.
8. **`/skill-definitions` being unreachable for a coach** (which would break AC4(c)'s code→name mapping).
   `SkillDefinitionResource.java:21` is `@PreAuthorize("isAuthenticated()")`.
9. **Duplicate work** — `pages/coach/` has no existing player-detail page or route.
10. **A garbage `:playerId` crashing the page.** `/api/development/players/abc/exposure` fails
    `@PathVariable Long` binding → 400 → store catches → banner. No crash, no blank screen.
11. **Vue Router failing to encode `playerName`** — query values are percent-encoded and `{{ }}` escapes
    output, so `&`, `#` and non-ASCII names are safe.

**Downgraded or reframed:**

12. **"The project has a locale-parity CI gate, so a missed locale fails the build."** Investigated and
    **false** — no locale-parity test or lint exists, so a missed `de-DE`/`fr-FR` key fails *silently* at
    runtime rather than in CI. This makes AC8's three-locale instruction more important, not less, but it
    is not a CI risk. (All three files do have a top-level `coach` namespace at full parity today:
    `en-US:239`, `de-DE:268`, `fr-FR:2`, with 8 `myPlayers*` + 10 `sessionPrep*` keys each. Caution for the
    implementer: each locale file contains three `coach:` keys at different nesting levels; only one is
    top-level. `vue/no-bare-strings-in-template` **is** `'error'` (`eslint.config.js:64`), so
    un-externalized *template* text does fail lint.)
13. **"The story mischaracterizes `PlayerProfileRepository`'s role as an id-space conversion."** Dropped —
    re-reading the story, it never characterizes that dependency's purpose at all; it only lists the field.
    The real, narrower defect is the unexplained second condition, kept as F-9.
14. **An array-valued `?playerName=a&playerName=b` rendering as `a,b`.** Technically real, but the trigger
    is contrived (no code path produces it) — not worth an AC.

---

## 7. Recommendation

**Per-claim calibration, not a blanket score.** The story's citation hygiene is genuinely strong: 20/20
code citations resolve, all six ledger/brainstorm quotes are byte-for-byte accurate and in the sections
claimed, its correction of the brainstorm's own four-endpoint over-claim is itself correct, and its claim
to have already annotated both source documents is empirically true at HEAD. No fabricated citation was
found. Three separate layers independently re-derived the same helper body, call sites and status lists.

**Confidence, finding by finding:**

| Finding | Confidence | Basis |
|---|---|---|
| F-1 (IT break) | **Highest.** I re-ran the grep and read the test and its `setUp` myself; `grep -n "booking"` over that file returns nothing, which is the decisive fact | Directly re-verified by orchestrator |
| F-8 (12/8 not 10/6) | **Highest.** I counted both `List.of(...)` literals myself and read the `git blame` comment explaining when the two extras were added | Directly re-verified by orchestrator |
| F-9 (second condition) | **Highest.** Helper body read directly and compared against AC3's prose line | Directly re-verified by orchestrator |
| F-2, F-3, F-5, F-6, F-7 | **High.** Each rests on a quoted source line I re-opened at Step 4b (store body, `ErrorDto` fields, `CommonConfig:48` + the deferred-148 comment, the seq-guard asymmetry, `setup-file.js`'s closing note + the 15-export import block) | Directly re-verified by orchestrator |
| F-4 | **High** on the mechanism (`v-if` on root + `>= 30` filter, both read directly); **medium** on how often an empty result occurs in practice, which depends on real SLU data volume | Mechanism re-verified; frequency inferred from the 30-day vs 730-day window mismatch |
| F-10, F-11 | **High** but low impact — documentation precision and one missing affordance | Re-verified; neither changes implementation correctness |

**What was *not* independently re-checked by me:** the completeness of the four layers' own greps for
*additional* callers and *additional* tests beyond those reported (I re-verified the specific hits they
returned, not the exhaustiveness of their searches), and the runtime frequency assumptions behind F-4.

**Verdict: send back for revision before `/bmad-dev-story`.** F-1 alone will turn CI red as the story is
written, and Task 3.3 asks for a test that already exists. F-2 and F-3 are both specification gaps where
the story's own cited precedent would lead a careful implementer into a known, previously-fixed bug class
(F-3) or a cross-player misattribution (F-2). F-8 should be fixed in `deferred-work.md:3785` as well as in
the story, since the story merely inherited it.

Minimum edits to reach implementable state: rewrite **AC9 + Task 3.3** around the six existing ITs and the
one that will break (F-1); add data-clearing or section-gating to **Task 4.2** (F-2); state the
raw-string Tsid constraint in **Task 4.2** and require a realistic Tsid fixture in **Tasks 5.1/5.2**
(F-3); give AC4(b) a wrapper + empty state (F-4); decide what the error banner actually renders (F-5);
correct the counts in **AC6/Dev Notes** and in the ledger (F-8); and specify the coach-is-also-parent
branch in **Task 1.3** (F-9). F-6, F-7, F-10 and F-11 are cheap to fold into the same pass.
