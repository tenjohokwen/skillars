# Pre-Implementation Story Review — skillars-deferred-152

**Story:** `skillars-deferred-152-store-error-i18n-sweep-contribution-threshold-dedup-and-coach-roster-fixes`
**Title:** Store-Wide Error-Message i18n Sweep, Coach-Contribution Threshold Dedup, Report-Generation SLU-Clock Fix, and Coach-Roster Hygiene
**Status at review:** `ready-for-dev`
**Audit date:** 2026-10-16

**Real HEAD at audit time:** `5dd19a85 docs(story): create skillars-deferred-152`
(verified by `git rev-parse --short HEAD` / `git log -1` at the start of this review; working tree clean, so HEAD == the tree that was read)

**On the story's own commit claim:** the story says every citation was re-verified against real HEAD `8d537200`. Unlike the defect that motivated this skill, **this claim is legitimate** — `8d537200` is `5dd19a85`'s parent and the only commit between them is the story's own docs commit, so the code is byte-identical. All citations below were nonetheless re-checked against `5dd19a85`, not taken on the story's word.

**Scale:** 4 verification layers ran in parallel over ~60 checkable claims (31 code citations, 18 ledger/precedent attributions, 13 mechanistic claims, plus per-AC flow walks). **27 findings survived** adversarial re-verification; **6 were killed or materially downgraded** by it — including the single most dramatic finding any layer produced.

---

## 1. Citation verification (Layer 1)

### Confirmed accurate — no action needed

| Citation | Evidence |
|---|---|
| `development.store.js` ten sites: `93,103,113,124,136,148,168,180,195,208` | All ten byte-exact `err?.response?.data?.message ?? '<English>'`. Independent sweep confirms the count is **exactly ten** — no missed site, none drifted. |
| `booking.store.js:293` | `packsError.value = e?.response?.data?.message ?? e?.message ?? 'Failed to load session packs'` — and it is the only hardcoded user-facing fallback in that file. |
| `errorHandler.js:86-100` (status switch) | Construct spans `83-101`; the five literals are at `87, 90, 93, 96, 99`. Cited range is correct for the switch itself. |
| `errorHandler.js:166-169` (`getErrorMessage`) | Exact. Body is two statements. |
| `ReportGenerationService.java:145-148` | `:145` comment, `:146` `sluRepository.findLastSessionDate(...)`, `:147` `if (lastSession == null)`, `:148` `throw`. Range covers comment→throw. |
| `CoachPlayerLastSessionResolver.java:27` | `public LastCoachSessionSignal resolve(Long playerId, UUID coachId) {` — the declaration of the method the story tells the dev to call. |
| `CoachRosterService.java:129 / :131 / :146 / :80-83` | All four exact (`toDays()`, `.size()`, unguarded `namesByPlayerId.get()`, the `findAllById` loop). |
| `SessionPrepService.java:80` | Exact; and repo-wide these two really are the only `.size()`-for-a-count sites. |
| `RosterAttentionCalculator.java:58` / `:64-65` | Exact (see §3-H for the omitted conjunct). |
| `NeglectedSkillFlagRepository.java:11` | Exact; interface has 3 methods and **no** count variant, so AC4's method is genuinely new. |
| `CoachContributionSection.vue:35` | `.filter((c) => c.percentageContribution >= 30)`; `topAttributions` computed is `:25-36`. |
| `ProfilePage.vue:290` / `:554` | Exact. `EditCoachBrandingDialog.vue:186`, `EditCoachBrandingDialogSpec.js:21` exact. |
| `ErrorDto.java` has no top-level `message` | Confirmed: fields are only `helpCode`, `errorMsg`, `fieldErrors`; `ErrorMsg` is `record ErrorMsg(String errorKey, String message)`. |
| `CoachSessionPackTiersPageSpec.js` exclusion | Confirmed: `not.toContain('deactivate')` is **gone** from the file; `:69-81` is exactly the `payment.myTiers.*` / one-`withdrawOffer`-button test the story describes. The story's exclusion of this half is independently correct. |
| All `deferred-work.md` section line numbers | Every cited line exists and holds the described bullet (see §2). |

### Drifted — corrections required

| Citation | Verdict | Corrected |
|---|---|---|
| `errorHandler.js:28-33` (`defaultErrorKeyByStatus`) | DRIFTED +1 | **`:29-34`** (`:28` is the preceding comment's `*/`) |
| `errorHandler.js:58` (`error.network` branch) | DRIFTED | **`:60`** is the `i18n.global.t('error.network')` call; `:56-59` are comment prose |
| `CoachPlayerDetailPageSpec.js:150-165` | DRIFTED | **`:157-172`**; the 10% datum is `:163`. The cited range starts inside the *previous* test (`:144-155`) and cuts the target test's assertions off |
| `CoachPlayerDetailPage.vue:101-111` | Short by 1 | **`:101-112`** |
| `SessionPrepCard.vue` | Path + element wrong | Real path **`src/frontend/src/components/booking/SessionPrepCard.vue`** (not `components/development/`), and `:59` is a **`<q-btn :to=…>`**, not a `RouterLink` — the file contains zero `RouterLink`/`router-link` |
| `submitAssessment:136` | Wrong identifier | **`submitRadarAssessment`** (decl `:130`); line correct |
| `savePreferences:168` | Wrong identifier | **`saveRadarPreferences`** (decl `:163`); line correct |
| `ProfilePage.vue:270-291` | Short by 14 | Section is **`:270-305`**; the cited range ends mid-`<template v-else>` and excludes the edit button (`:293-302`) |

---

## 2. Ledger & precedent attribution (Layer 2)

**Headline: nothing in this story is fabricated.** All 10 Source-table ledger citations point at the right line, the bullets say what the story says they say, and every verbatim quote checked out — including `"booking.store.js:293 has the same bug and should be included"`, `"Revisit when either a real roster exceeds ~100 players…"`, `"the roster-membership-width half is still open for a future story"`, `"Consider decoupling… if the knob is ever exposed in an admin UI"`, `"Settle alongside voice_note_text rather than in isolation"`, `"not currently reachable"`, and `"if a dual-role path is ever introduced"`.

Each claim was checked against **all three** sources (ledger text, nearby code comments/Javadoc, and `git log --grep` / `git blame`) per this skill's method rule. Notably, the story's re-anchored source line numbers are **consistently more accurate than the ledger's own stale inner citations** (e.g. ledger says `CoachRosterService:112`/`RosterAttentionCalculator:40`; real is `:129`/`:58`, which is what the story used).

### Corrections

| Claim | Sources checked | Verdict |
|---|---|---|
| `:3766-3769` = three orphaned any-role backend features | ledger | **PARTIALLY WRONG** — the three described features are at **`:3766-3767`** only. `:3768` is the `/neglected-skills` item (different, partly resolved by 12-4) and `:3769` is `/slu/skill-trends` + `/api/session/sessions/{id}`. Range 2 lines too wide. |
| "`skillars-12-3`'s **AC2**" interpreted the quick-note field as the session-builder link | 12-3 story file | **WRONG AC number** — it is 12-3's **AC6** (`:31`, *"A 'Plan this session' action inside the same card links to the existing `coach/session-builder/:bookingId` route"*). 12-3's AC2 is the SLU/timeline signal merge. Substance is right; the number is not. |
| `requireCoachPlayerRelationshipIfCoach` "now in **five** copies (`deferred-work.md:3812`)" | ledger + source grep | **Ledger is self-inconsistent**, not the story: `:3812` says "four copies" twice *and* "five copies" once. Source has **3** copies of the named private method (`SluContributionService:100`, `SluTargetService:66`, `SluDashboardService:132`) plus 2 inline equivalents = 5. The story matches the ledger's own prescription ("Fix all five copies together"). **Non-actionable** — the item is deliberately excluded from this story. The *ledger* needs the fix. |

### Confirmed precedents

- `skillars-deferred-151` AC3 (`:229`) really is the `coach_branding` ownership fix, and its Finding 3 #1 (`:56-70`) really does name `ReportGenerationServiceTest` mocking `FileStorageService` as the blind spot that let it ship. ✓
- `CoachPlayerLastSessionResolver` was introduced by `1db98964` (skillars-12-3); the file's own comment says *"skillars-12-3 Task 2.1"*. ✓
- `coach.playerDetailUnknownPlayerName` exists in all three bundles and `git log -S` attributes it to `8d537200` (skillars-12-4). ✓
- **`ReportGenerationService:146` really is the last direct `findLastSessionDate` caller** — the only other production occurrence is inside the resolver itself. The "third and final" claim holds; there is no fourth instance. ✓
- File List negatives all verified: no `SessionPrepServiceTest.java`; `stores/__tests__/` holds exactly `bookingStoreSpec.js`/`playerStoreSpec.js`/`paymentStoreSpec.js`; no spec covers `CoachContributionSection.vue`; `bookingStoreSpec.js` has no existing `packsError` coverage. ✓
- `SessionBuilderPage.vue` closure finding is correct — the link exists and `SessionPrepCard` is rendered from `CoachCommandCenterPage.vue:322`. ✓

---

## 3. Mechanistic claims (Layer 3)

| # | Claim | Verdict |
|---|---|---|
| A | `getErrorMessage`'s only content is `parseApiError`'s `message` | **TRUE** — but see §4-#8 and §5 for how one layer's framing of its *effect* was downgraded |
| B | `data.message` "never exists on the real `ErrorDto` shape" | **TRUE, and more strongly than argued.** `ErrorMvcAutoConfiguration` is excluded (`application.yaml:136`), zero `ProblemDetail` usage, all 7 `@ControllerAdvice`s return `ErrorDto`, `JWTAuthorizationFilter` hand-builds `ErrorDto`, and both security handlers delegate back to `ApiAdvice`. **No regression risk from the sweep.** |
| C | `i18n.global.t` is safe at module scope in `errorHandler.js` | **TRUE** — `boot/i18n.js:6` calls `createI18n` eagerly at module evaluation; no import cycle. No boot-order hazard. |
| D | All `defaultErrorKeyByStatus` values + `error.unknown` exist in all 3 bundles | **TRUE** (resolved by importing each bundle, not grepping). But see §4-#7 — *existing* is not the same as *correct*. |
| E | Resolver already consumed by `CoachRosterService`, `TimelineQueryService`, `SessionPrepService` | **PARTLY TRUE** — all three confirmed, but all three are **direct** consumers. See §4-#21. |
| F | AC3 is a call-site swap | **PARTLY TRUE** — the guard is a bare `== null` check, `lastSession` is never used again, and the resolver joins `generateReport`'s existing `@Transactional` (`REQUIRED`), so **no transaction boundary changes and nothing commits differently**. But it is an authorization widening — see §4-#5. |
| G | `findAllById` short-return → unguarded `null` `playerName` | **Mechanism TRUE, consequence FALSE** — see §4-#10. |
| H | Dormancy boundary, `toDays()` as root cause | **Disagreement TRUE and exactly one day; root cause INVERTED** — see §4-#1. |
| I | `ReportGenerationServiceTest` "mocks `FileStorageService` away entirely" | **FALSE** — see §4-#12. |
| J | `EditCoachBrandingDialog:186` has "no error-handling branch at all" | **PARTLY TRUE; two stated mechanics wrong** — see §4-#13, #14. |
| K | AC7's four id-space call sites | **All four exist.** Site 1's `getCurrentCoachUserId()` is a method on **`SecurityUtil`**, called at `CoachBrandingResource:45` (not a method on the resource). Site 4's literal is `entityId: String(userId)` at `EditCoachBrandingDialog.vue:179`, destructured via a local first. `StorageResource:41-42` `OWNER_BOUND_ENTITIES` and `ReportGenerationService:281` `assertOwnership` exact. |
| L | AC4's new method is additive and `playerId` is `Long` | **TRUE** — type is `Long` (the trap being the repository's own `UUID` id type, which the story got right). The "other callers, **if any**" hedge understates it: there are **four** entity-consuming callers (`SluDashboardService:78`, `SluTargetService:60`, `NeglectedSkillProcessor:58`, `DrillSuggestionService:55`), so keeping the existing method is mandatory, not hypothetical. |
| M | The two 30%-threshold copies are "identical" | **TRUE** — same grouping key, same strict-`>` max-within-skill, same `>= 30`, same percent unit. Only `.filter()` vs `.some()` and the data source differ. A shared predicate is exactly behaviour-preserving. |

---

## 4. Findings that survived adversarial re-verification

### HIGH

**#1 — AC5's root cause is inverted, and one of the two fixes it offers provably does not work.**
AC5 and its Dev Notes say *"`CoachRosterService.java:129`'s `Duration.between(...).toDays()` truncation-toward-zero **is the root cause**"* and that fixing either side *"resolves the boundary disagreement **either way**."*

Worked arithmetic at `daysSinceLastActivity == dormantAfterDays == D`:
- `RosterAttentionCalculator.java:58-59` → `dormant = (D > D) && !hasUpcomingBooking` = **`false`**
- `:64` → `clampedDays = min(max(D,0), D) = D`; `:65` → `recencyNorm = D/D` = **`1.0`, fully saturated`**

The disagreement lives **entirely in integer space inside the pure function**: for any integer `d`, saturation holds at `d >= D` while `dormant` requires `d > D`. **How `d` was computed is irrelevant** — rounding instead of truncating at `:129` changes *which* `d` an elapsed duration maps to and does not close the `>` vs `clamp-at-D` gap at all. So the "rounding the day count" option does not satisfy AC5's own stated requirement.

Worse, that option is actively harmful: `daysSinceLastActivity` feeds `recencyNorm` → `attentionScore` → the roster's sort order (`CoachRosterService:94`), so rounding changes the score for **every** player with a ≥0.5 fractional day — contradicting AC5's own promise of "no change … for any value away from the exact boundary." `toDays()` is correct "full elapsed days" semantics and should not be touched.

Two further notes: AC5's quote of `:58` **omits the `&& !signals.hasUpcomingBooking()` conjunct** (`:59`), which a deliberate owner-decision comment at `:58+` governs; and the ledger bullet at `:3786` actually describes a *different* real problem (operator expectation: `dormantAfterDays = 30` flags only after 31 full days). AC5 conflated the two.
**Action: fix `RosterAttentionCalculator.java:58` only; drop the `:129` option and the "either way" claim; state which of the two ledger problems is in scope.**

**#2 — AC5 reverses an existing, deliberately-named test, and AC10 lists that test as a pass-check.**
`RosterAttentionCalculatorTest.java:50-56`:
```java
void daysSinceLastActivityAtThreshold_isNotDormant() {
    …compute(new RosterAttentionCalculator.Signals(30, 0, 0, 30, false));
    assertThat(r.dormant()).isFalse();
}
```
Its neighbour at `:74-84` deliberately uses `31`, commenting *"exactly-at-threshold-**plus-one**"*. Any AC5-conformant fix turns this test RED. Task 5 says only *"**Add** a boundary test"* and AC10 names the class for a pass-check. **Action: AC5/Task 5 must say this test is inverted or deleted.**

**#3 — AC2's literal instruction is not implementable, and forces a file absent from the File List.**
`CoachContributionSection.vue:20` is `<script setup>`, which compiles to a single default export — a named `export` inside it is a build error. Repo-wide confirmation: zero `.vue` files under `components/` or `pages/` contain a top-level named export. The convention for shared logic is `src/utils/` or `src/composables/`. A correct fix needs a new shared module (e.g. `src/utils/coachContributions.js`) that appears in **neither** the File List nor AC10's Vitest list. **Action: restate AC2 as "extract to a shared module" and add it to both lists.**

**#4 — AC4 breaks five existing tests that Task 4 says will "pass unchanged".**
`CoachRosterServiceTest.java:50` is `@ExtendWith(MockitoExtension.class)` (strict stubs by default), and `:212-213`:
```java
private void stubNoNeglectedFlags() {
    when(neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());
}
```
is called non-leniently from `:109, :127, :155, :169, :189`. Switching `CoachRosterService:131` to `countBy…` leaves that stub uninvoked → **`UnnecessaryStubbingException` on all five tests**. (The `SessionPrepResourceIT` half of the claim is correct — it is a real-DB IT and genuinely unaffected.) **Action: Task 4 must say the helper is rewritten, not confirmed.**

### MEDIUM

**#5 — AC3 is an authorization widening on a player-level cross-coach aggregate, not a mechanical swap.**
The guard is `lastSession == null` only, so swapping in the resolver's merged signal is purely additive on the "allow" side — that part is sound. But the report's entire body is keyed by `playerId` alone, **not coach-scoped**: `buildSkillEntries(playerId)` (`:291-312`, including a per-skill `distinctCoachCount`), `sumTotalSluByPlayerId(playerId)`, `countDistinctSessions(playerId)`. So the surface being unlocked is a **cross-coach aggregate of the player's whole SLU history**, whereas the three surfaces AC3 cites for parity (timeline, roster entry, Prep Card) are coach-scoped. The "same fix already applied to that coach's timeline and roster" justification is therefore not exact.

Two riders: `notifyParent(...)` fires **unconditionally** at `:226`, so a newly-permitted coach also triggers a `PERFORMANCE_REPORT_SHARED` email to the parent; and where the player has no SLU rows at all, the PDF is structurally empty (`Sessions: 0 | Total SLU: 0`, header-only skill table).

**Severity is bounded, which is why this is MEDIUM not HIGH:** `generateReport` already calls `coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachUserId, playerId)` at `:137`, which requires an active booking relationship. The SLU gate is a *second*, narrower layer — not the only protection. **Action: AC3 should name the widening explicitly, state whether an empty report is acceptable, and say whether the parent notification should be gated.**

**#6 — AC3's new dependency breaks two constructor sites, not one.**
`new ReportGenerationService(...)` appears **twice** in `ReportGenerationServiceTest.java` (`:90` and `:406`). AC3/AC10 imply a single construction site.

**#7 — AC1's "reuse the existing map, introduce no new keys" ships semantically wrong copy, and is simply false for 5xx.**
`defaultErrorKeyByStatus` (`:29-34`) was built to pick an `errorKey`, not a user-facing message. Resolving its values:

| Status | Mapped key | Actual en-US text |
|---|---|---|
| 400 | `validation.badRequest` | *"The request is not valid. Check method argument mismatch, missing parameters e.t.c."* — developer-facing, and carries a typo |
| 401 | `security.unauthorized` | *"You do not have the required rights. You can contact help desk"* — **this is a 403 message.** 401 means *not authenticated*; the correct existing key is `security.sessionExpired` (*"Your session is no longer valid. You need to sign-in again"*) |
| 403 | `security.opForbidden` | *"Access has been denied…"* ✓ |
| 404 | `error.notFound` | *"The requested resource was not found"* ✓ |

And the map has **four** entries, not five — there is **no `error.serverError` key anywhere in any bundle** (verified). AC1's *"not introduced new keys where an existing bundle key already covers the status"* is therefore false for 5xx: it silently collapses both `'Server error'` **and** `'An error occurred'` (409/422/429 and every other unmapped <500) into `error.unknown` = *"An unknown Exception has occurred"*, losing the 5xx-vs-client distinction users see today. `error.generic` (*"An unexpected error occurred. Please try again."*) is the better 5xx fit. **Action: AC1 must make the 401 and 5xx key decisions explicitly rather than deferring to a map built for a different purpose.**

**#8 — AC1 trades ten operation-specific messages for one generic network message on any non-HTTP failure.**
`errorHandler.js:52-65` returns `message: i18n.global.t('error.network')` for **any** thrown value without a `.response` — a `TypeError` in response mapping, a plain `throw new Error`, an aborted promise. Today each of the ten sites at least names the operation (*"Failed to generate report"*); after the sweep every one says *"Network error. Please check your connection."* for a client-side bug. Additionally `booking.store.js:293` is **three-armed** (`?? e?.message ??`), and `getErrorMessage(e)` drops the middle arm — the one carrying information for a non-axios throw. **Action: acknowledge the trade-off, or keep a per-site key as the no-response fallback.**

**#9 — AC2 misses a third consumer of `CoachContributionSection`.**
`ParentDevelopmentPortalPage.vue:62` renders `<CoachContributionSection :contributions="store.coachContributions" />` with **no `v-if`, no `v-else`, no empty state**, relying on the component's silent root-`v-if` collapse — the exact defect AC2's split fixes for the coach page. It is also a consumer of whatever shared module #3 creates. Absent from the File List, even though the Dev Notes send the implementer into this very file for AC6 precedent.

**#10 — AC6's NPE framing is false and the severity is over-stated.**
The User Story says *"risking a `NullPointerException` deep in a `Map.of`-style response builder."* `RosterEntryResponse` is a plain record with no null constraint and no compact constructor, and **`Map.of` appears nowhere in `CoachRosterService`** (grep: zero). The real consequence is `"playerName": null` in JSON — an identity-less roster row, exactly as the ledger says. The `Map.of` framing is lifted from a different story (`TimelineEventListener:46`, a skillars-12-2 recap-null note). *(AC6's "latent, not reachable today" framing is independently **correct**: `player_profiles.name` is `NOT NULL`, `GdprErasureService` tombstones rather than deletes, and no `playerProfileRepository.delete*` call exists in `src/main`.)*

**#11 — AC6's cited precedent is the wrong shape for what AC6 asks.**
`coach.playerDetailUnknownPlayerName` is `'this player'` / `'ce joueur'` / German dative `'diesem Spieler'` — a lowercase mid-sentence fragment, used only as an interpolated pronoun at `CoachPlayerDetailPage.vue:92`. AC6 asks the **backend** to "substitute a safe fallback display value" following this precedent, which would put untranslated English into an API field. The correct reading of the precedent is the opposite: **keep returning `null` and localize client-side**, which is what the frontend already does. **Action: reword AC6 to "filter, or return null and localize client-side".**

**#12 — Dev Notes' AC7 rationale is stale and self-contradicting.**
*"`ReportGenerationServiceTest` mocks `FileStorageService` away entirely"* is **false at HEAD**. `@Mock` is class-level (`:67`), but `saveBranding_realFileStorageService_ownershipCheckPassesForCallersOwnUserId` (`:377-423`) constructs a **real** `FileStorageService` (`:390-391`, injected at `:409`), and the test's own comment at `:366` says so verbatim. The same paragraph then concedes *"the one existing test that does exercise the real service"* — so it contradicts itself. This describes the pre-`deferred-151` state. *(The `.ownerId(…)` fixture half is **TRUE** — `:380-387`, `COACH_USER_ID = 1001L`.)* **Action: drop "entirely"; AC7's real justification is the synthetic fixture, which stands on its own.**

**#13 — Dev Notes' AC9 rationale states two mechanics that don't exist.**
*"has no error-handling branch at all … as opposed to the surrounding presign/confirm/save calls which **do** have `security.featureGated`-style catches."* Both halves are wrong: there is exactly **one** `try` (`:163`) wrapping presign→fetch→confirm→save with a single `catch (err) { setError(err) }` (`:205-207`), so a PUT-step *network* failure **is** already caught; and `signUpload`/`confirmUpload`/`saveCoachBranding` have **no individual catches whatsoever** — the only `featureGated` linkage in the component is a code comment at `:206`. An implementer told to "mirror the surrounding catches" will find nothing to mirror. The real defect is narrower and sound: **no `!res.ok` check**.

**#14 — AC9: a failed upload is not silent, so the defect is a misleading message, not data corruption.**
`FileStorageService.confirmUpload` does `s3Client.headObject(...)`, throws `StorageObjectNotFoundException` on `NoSuchKeyException`, and additionally validates `contentType`, `fileSizeBytes`, and ETag presence. So a non-2xx PUT leaves no object and **fails at the very next call** — the coach sees an error, just a confusing one (*"The requested resource was not found"*). AC9's fix is still worth doing, and needs **zero new i18n keys**: `if (!res.ok) throw new Error(...)` lands in the existing `setError` → `!error.response` → `error.network` path, rendered by the dialog's existing `{{ errorMessage }}` at `:51`. **Action: keep the "prefer the former" recommendation — it is cheaper than the story thinks — but drop the implied data-corruption severity.**

**#15 — AC7 is implementable, and the exact precedent exists but goes unnamed.**
The File List hedges *"or a new test class if no existing IT class fits."* It fits: `BaseStorageIT` + `StorageTestConfig` bring up a real SeaweedFS S3 container; **`ReportGenerationServiceIT` already extends it** in the same package; and `StorageResourceIT:138-143` **already performs the real `signUploadHttp → putToS3 → confirmUploadHttp` round trip** with helpers at `:222-265`. Unmentioned setup burden: the test coach needs `ROLE_COACH` + a `CoachProfile` + `CoachSubscriptionTier.ACADEMY` (`ReportGenerationService:276-279` throws `FeatureGatedException` otherwise) **and** `Principal.businessId` equal to the request's `entityId` (`StorageResource:50`).

**#16 — AC1's "repo-wide sweep" overclaims; "store-wide" would be accurate.**
Two more sites carry the identical bug *with hardcoded English*:
```
pages/coach/AvailabilityManagerPage.vue:279  saveError.value      = e?.response?.data?.message ?? e?.message ?? 'Failed to save window'
pages/coach/AvailabilityManagerPage.vue:358  blockSaveError.value = e?.response?.data?.message ?? e?.message ?? 'Failed to save block'
```
(Three further dead reads of the same nonexistent field exist at `SkillsRadarAssessmentPanel.vue:177`, `SessionPackPurchasePage.vue:164`, `ParentCreditWalletPage.vue:108` — those already fall back to `t(...)`, so no English leaks.) Also: **`errorHandler.js` has zero direct test coverage** (`utils/__tests__/` holds only `sessionRedirectSpec.js`) despite being the file AC1 changes most invasively and being consumed by `useErrorHandler.js`, `CoachPublicProfilePage.vue`, and `CoachReviewsPage.vue`. AC10 omits it.

**#17 — Task 1's last subtask is not expressible as written.**
*"asserting a translated (not hardcoded-English) message"*: `test/vitest/setup-file.js` installs an i18n with `messages: { 'en-US': {} }`, but `errorHandler.js:1` imports the **real** `boot/i18n` (real bundles, `locale: 'en-US'`). A spec therefore gets real **English** back and cannot distinguish "i18n'd" from "hardcoded" without switching locale to `fr-FR`/`de-DE` inside the test.

### LOW

**#18** Citation drift: `defaultErrorKeyByStatus` `:28-33`→`:29-34`; `error.network` `:58`→`:60`; `CoachPlayerDetailPageSpec.js` `:150-165`→`:157-172`; `hasTopContributions` `:101-111`→`:101-112`; `ProfilePage.vue` branding section `:270-291`→`:270-305`.

**#19** Wrong identifiers (line numbers correct): `submitAssessment`→**`submitRadarAssessment`**, `savePreferences`→**`saveRadarPreferences`**.

**#20** `SessionPrepCard.vue` lives under **`components/booking/`**, and `:59` is a **`<q-btn :to>`**, not a `RouterLink`.

**#21** Dev Notes AC3: *"`TimelineQueryService` (via `SessionPrepService`'s own dependency)"* — `TimelineQueryService:29` injects the resolver **directly**; it contains no reference to `SessionPrepService`. Harmless to the three-consumer count, but any reasoning premised on "changing `SessionPrepService` reaches `TimelineQueryService`" is wrong.

**#22** AC10's CI claim is imprecise in two ways. Per `frontend-unit-tests.yml:13-21` (skillars-deferred-129 AC2) the job **auto-fires on any PR touching `src/frontend/**`** — which this diff does — so the `frontend-tests` label is belt-and-braces, not the mechanism. And the workflow header states it is *"NOT a required status check"* and that *"a red frontend suite still does not block a merge"*, so *"actually checked by CI"* overstates what a result enforces.

**#23** AC4: both call sites assign to `int` (`CoachRosterService:131`, `SessionPrepService:80`), so declare `int countByPlayerIdAndResolvedAtIsNull(Long)` or cast. *(Derivation verified safe: `NeglectedSkillFlag.playerId`/`resolvedAt` are plain `@Column` fields, and the hydrated entities are discarded at both sites and never reused later in either method.)*

**#24** AC2's boundary premise reads as a current discrepancy that doesn't exist: both copies use `>= 30`, so page and component **already agree** at exactly 30. The new test is a drift guard, not a fix. *(Splitting `coach.playerDetailNoContributions` orphans nothing — its only uses are `CoachPlayerDetailPage.vue:59` and `CoachPlayerDetailPageSpec.js:170`, the latter correctly in AC10's list. And a new `CoachContributionSectionSpec.js` mounts cheaply: setup already provides Quasar + i18n globally, and the component needs no Pinia or router.)*

**#25** `ApiAdvice:131` stamps `generic.unknown` for every unhandled 500, and **`generic.unknown` is missing from all three bundles** (there is no `generic:` section in en-US). Harmless for AC1 as scoped, but if the implementation translates `parsed.errorKey` instead of the status map, a 500 will render the literal key.

**#26** `ProfilePage.vue` never references `logoSignedUrl` (0 hits) — which is precisely the AC8 defect, not an error in AC8. `:290` is plain hex text with an `'—'` fallback, not a visual swatch.

**#27** AC8: `logoSignedUrl` is presigned per-`getBranding` call (`ReportGenerationService:268-269`) but fetched once on mount (`ProfilePage.vue:548-561`), so a `/profile` tab left open past the TTL renders a broken image. Task 8 covers only the "absent" case. Precedent to follow is `EditCoachBrandingDialog.vue:13-14` (`<q-img>` behind a presence `v-else-if`); add a `q-img` error slot or a re-fetch affordance. *(The "locked" state the AC mentions is real: `ProfilePage.vue:282-284` renders `security.featureGated` for non-ACADEMY.)*

---

## 5. What did **not** survive re-verification

This section exists because a clean report is a warning sign. Six items a layer raised were killed or downgraded when I re-read their evidence myself:

1. **KILLED — "a failed logo upload silently persists a broken `logo_key` and shows a success toast; the broken key is sticky."** This was the single most alarming finding produced, and it is **wrong**. `FileStorageService.confirmUpload` runs `headObject` and throws `StorageObjectNotFoundException` on `NoSuchKey`, plus contentType/size/ETag validation, *before* anything is persisted. A non-2xx PUT fails at the next call. Replaced by the accurate, much smaller #14.

2. **DOWNGRADED — "widening AC3's gate produces empty PDFs and fires a parent email, which the SLU gate is what prevents."** `generateReport:137` already enforces `requireCoachPlayerRelationship`, so the SLU gate is a *second* layer, not the only protection. The finding survives as #5 at MEDIUM, reframed around the report's player-level cross-coach scope rather than an unprotected-access claim.

3. **DOWNGRADED — "the backend already localizes errors, so AC1's blast radius is far narrower than the story suggests."** True for `parseApiError`'s *internal* status fallbacks (`ApiAdvice.toErrorDTO` does localize `errorMsg.message` via `Accept-Language`, which axios sets). But **false as applied to the ten store sites**: they read `data.message`, which never exists, so the English literal fires on *every* backend error today. The store half of AC1 is high-value exactly as the story claims. Kept only as the narrower #7/#8.

4. **KILLED — "`CoachPlayerLastSessionResolver.java:27` is a drifted citation."** `:27` *is* the `resolve(Long, UUID)` declaration. Citing a method's declaration line for the method you're instructing someone to call is a valid citation, not drift.

5. **KILLED — "the report guard should be cited `:146-149`, not `:145-148`."** `:145` is the construct's own comment and `:149` its closing brace; a range covering comment→`throw` is normal practice. Not a defect.

6. **KILLED — "AC8's claim that the page already fetches `logoSignedUrl` is unsupported because the page never references that field."** The page fetches the whole `CoachBrandingResponse`, which carries the field; never *reading* it is the defect AC8 fixes. The claim is substantively true. Retained only as the neutral note #26.

Also tested and found **not** to be problems (do not chase): no i18n boot-order or circular-import hazard; no division-by-zero in `recencyNorm` (`ConfigBounds:162-164` bounds `dormantAfterDays` to `[1, 3650]`); no NPE in the PDF totals (`sumTotalSluByPlayerId` is `COALESCE(...,0)` and the table always gets 4 header cells); no circular dependency from injecting the resolver; no transaction-boundary change from AC3 (resolver's `readOnly` + `REQUIRED` joins the caller's read-write transaction — Spring does not downgrade it); the error-state shape is a plain string at every consumer; and no spec asserts any of the ten English literals.

---

## 6. Recommendation

**Status: keep at `ready-for-dev`, but apply a patch pass before implementation.** The story's architecture and core premise are sound, nothing is fabricated, and its own re-anchored citations are *better* than the ledger's. Four items must change because an implementer following the text literally will write the wrong code or break tests they were told would pass.

**Must fix before dev starts:**
- **#1** — AC5 points at the wrong line and offers a fix that doesn't work. Highest-value correction in this review.
- **#2, #4** — three test classes must be edited, not "confirmed passing" (1 inverted, 5 re-stubbed).
- **#3** — AC2's instruction is a build error as written; add the missing shared module to the File List.

**Should fix (decisions the story defers but shouldn't):**
- **#7** — the 401 key is wrong and no 5xx key exists; make both choices explicitly.
- **#5** — name AC3's widening and decide on the parent notification.
- **#8, #11** — one trade-off to accept, one AC to reword.

**Confidence, per claim rather than blanket:**
- **Independently re-verified by me, line by line, after the layers reported:** #1 (arithmetic worked by hand), #2, #3, #4, #5, #7, #9, #10, #14, #16, #19, #20, #22, #23, plus all six kills in §5. Highest confidence.
- **Verified once by a layer, with its quoted evidence consistent across layers:** #6, #12, #13, #15, #18, #21, #24, #26, #27, and the §2 ledger table. High confidence, but the quotes were not re-opened a third time.
- **Single-layer, not re-opened:** #17 (Vitest i18n setup) and #25 (`generic.unknown`) — both LOW, both conditional on implementation choices. Treat as leads, not verdicts.

Layers 1, 2 and 4 disagreed with each other on five citations (the `:3812` copy count, resolver `:27`, the guard range, the `development.store.js` function names, and the `12-3` AC number). Every one was resolved by my own re-read, and in three of the five the *more* critical layer was the one that was wrong.
