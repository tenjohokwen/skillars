# Story 12.5: Coach Session Recap on the Player Timeline

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a coach,
I want the "what we worked on / what's next" recap I wrote at session close to appear on that player's timeline when I open their detail page,
so that my own session notes become a readable history of the relationship instead of write-only data I can never see again.

## Source

This story closes the **read-surface gap for Converge #15** of `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md`, whose own success indicator is *"every completed session has a recap attached, **visible back to the coach on that player's timeline**"* (Action Planning → Priority 2). The capture half shipped in `skillars-12-2-session-close-recap-capture`; the display half never did.

The gap is traceable to a handoff between two stories that were each individually correct:

- `skillars-12-2` AC8 deliberately forbade rendering: *"any frontend rendering of the recap (there is currently no coach-facing page for it to render on … rendering is deferred until Priority 4's coach-facing player-detail page exists to actually reuse that panel from a coach route)"*. Its AC4 states the consequence plainly: *"this recap is not visible through **any** UI to anyone for the duration of this story."*
- `skillars-12-4` then built that page — but its AC4 required `PlayerTimelinePanel.vue` **unchanged**, assembling *"exactly three pieces and nothing else"*, faithfully quoting Converge #12's own wording (*"`PlayerTimelinePanel.vue` (unchanged)"*).

So 12.2 deferred rendering **to** 12.4, and 12.4's scope statement excluded it. Neither story was wrong; the recap simply fell between them. This story is that follow-on, and it is the **last** open item of Converge #15.

**Precisely what is and is not missing.** The recap is not entirely unreadable today: `skillars-12-3`'s Session Prep Card already renders it (`SessionPrepCard.vue:25,29`), fed by `SessionPrepService.java:59-67`, which reads the same two `METADATA_KEY_*` values out of the same `PlayerTimelineEvent`. But that card is **forward-looking and single-session** — it answers "what should I do in Thursday's session with Alex?" by showing the *most recent* recap inside one upcoming booking's prep dialog. What has never existed is the **backward-looking, whole-relationship** view: the recap on the player's timeline, where a coach can scroll the history of everything they have worked on with that player. That is the surface Converge #15's success indicator names ("on that player's timeline") and the one this story builds. Consequently `skillars-12-2` AC4's "not visible through **any** UI to anyone" was true when written but has been superseded by 12-3 — do not quote it as current state. The practical upshot for the implementer is a reuse instruction, not a blank slate: the markup idiom for these two fields already exists and must be mirrored, not reinvented (AC6).

Still explicitly **not** in scope and not reopened here: Converge #16's report-generation consumption (the recorded Fast-Follow after `skillars-12-2`) and Deferred #17's player/parent-initiated flag (permanently parked). See AC9.

Per this project's convention, the source brainstorm document is annotated alongside this file's creation to record that Converge #15's read surface is covered here.

## Acceptance Criteria

1. **`PlayerTimelinePanel.vue` renders the recap inside the `q-timeline-entry`'s currently-unused default slot.** The insertion point is between the closing `</template>` of the existing `#title` slot (`PlayerTimelinePanel.vue:44`) and `</q-timeline-entry>` (`:45`) — the default slot is empty today and is the only free content area; `icon`/`color`/`subtitle` are passed as **props** (`:34-36`), not slots, so do not repurpose `#subtitle`. Do not restructure the existing `#title` slot, the `q-timeline` wrapper (`:30`), or any of the panel's seven state branches (see Dev Notes "State branches that must keep working").

2. **No role prop, no `useAuthStore` in the panel, and no change to the parent or player pages.** The recap block is gated **only** on data presence, not on who is viewing. Rationale is load-bearing and must not be "improved" into a client-side role check — see Dev Notes "Why there is no `showRecap`/`isCoach` prop". Concretely:
   - `PlayerTimelinePanel.vue` gains **zero** new props. Its `defineProps` stays exactly `{ playerId: { type: [Number, String], required: true } }` (`:88-90`).
   - `src/frontend/src/pages/parent/ParentDevelopmentPortalPage.vue:122` and `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue:118` are **not edited at all**.
   - `src/frontend/src/pages/coach/CoachPlayerDetailPage.vue:68` is **not edited either** — `<PlayerTimelinePanel :player-id="playerId" :key="playerId" />` already passes everything needed. **Do not touch `loading = ref(true)` (`CoachPlayerDetailPage.vue:129`)**; that initial value is a documented fix for a double-mount bug that fired two `/timeline` requests (see its comment at `:124-128`).
   - **Do not** mirror `PlayerDevelopmentDashboardPage.vue:107`'s `<PerformanceReportsPanel … :is-coach="isCoach" />` onto the timeline panel. That page has `isCoach` in scope (`:193`) and a dev agent pattern-matching on its sibling line will wire it in; that is the single most likely wrong turn in this story.

3. **The recap block is triple-guarded and renders only real text.** All three conditions, together:
   - `event.eventType === 'SESSION_COMPLETED'` — the other two producible event types carry structurally different metadata (`RADAR_ASSESSMENT` → `{skillCodes: [...]}`, an array; `PERFORMANCE_REPORT` → `{coachName, reportId}`) and must render nothing from this block.
   - Optional chaining on every metadata read — `event.metadata?.whatWeDid`, `event.metadata?.whatsNext`. `metadata` is a **nullable** JSONB column (`V138__baseline_schema.sql:427`) and legacy rows genuinely have `null`. Optional chaining on metadata is the universal convention in this codebase (`DrillCard.vue:34,39,53`; `DrillDetailPanel.vue:46,54,63`).
   - **No current code path produces exactly one of the two fields** — `WrapUpRequest.java:21-22` marks both `@NotBlank`, and every publisher passes them as a pair (`BookingCompletionService.java:190`, `:267`, `:292`; `QuickCompleteTimeoutService.java:69`), so in production they are both present or both absent. The independent guards are deliberate defence-in-depth against the two nullable columns, not a response to a live scenario; AC8 case 2 is therefore a synthetic unit test and should be understood as such rather than "fixed" to match production.
   - Each of the two fields is rendered **independently**, each behind its own `v-if`, so an absent field contributes **neither its label nor an empty value div**. The prohibition in Task 2.1 is on collapsing *these two inner guards* into a single combined `hasRecap`; it does **not** forbid — and AC4 in fact requires — an OR-guard on the **outer** wrapper so that a recap-less entry emits no wrapper element at all.
4. **An entry with no recap renders exactly as it does today — no empty state, no placeholder, and no empty wrapper `div`.** This is the common case and must stay visually silent. **Exactly three** distinct real sources produce a recap-less entry — all three a coach will see:
   1. Sessions predating the `V159` migration.
   2. **Force-completed/abandoned wrap-ups on the coach's own bookings.** `forceCompleteWithPlaceholderData` (`BookingCompletionService.java:240-269`) is the **only** method that ever writes a `SessionCompletionData` row with null recap fields; it publishes `BookingCompletedEvent(…, List.of(), null, null)` (`:265-268`), so `TimelineEventListener.java:54-57` writes metadata containing only `coachId`. Note the actor: it is **never coach-initiated** — its only two callers are `StuckCompletionSweeperService` (`ActorRole.SYSTEM`, `:76-77`) and `AdminBookingCompletionService` (`ActorRole.ADMIN`, `:33`). The coach nonetheless sees the gap *on their own session*, which is why this is the source most likely to be mis-reported as "my recap vanished".
   3. On the coach page specifically, **every `SESSION_COMPLETED` event authored by a different coach**, whose recap keys the backend strips server-side (`TimelineQueryService.java:87-88`).

   **There is no fourth "Quick-Complete with null recap" source — do not go looking for one.** A genuine Quick-Complete always carries real recap text: the row can only originate from `submitWrapUp`, whose `@Valid WrapUpRequest` marks both fields `@NotBlank` (`WrapUpRequest.java:21-22`), and `QuickCompleteTimeoutService` only ever *reads* such rows back (`:69` forwards `scd.getWhatWeDid()/getWhatsNext()`, non-null by construction) from a query filtered on `COMPLETED_PENDING_CONFIRMATION` (`SessionCompletionDataRepository.java:31-34`). Source 2 and any "Quick-Complete" framing are the same single code path, not two.

   A coach's timeline for a multi-coach player will therefore legitimately show a *mix* of entries with and without recap text; that is Decision #8 (siloed per-coach notes) working as designed, not a defect. **Do not** add a "No recap yet" line per entry — `SessionPrepCard.vue:31` shows that string because it is a single focused card about one session; repeating it down a 50-entry timeline would be noise.

5. **Newlines in the recap are preserved and long text cannot break the layout.** The capture inputs are `q-input type="textarea"` (`WrapUpSequence.vue:230,241`, inside the blocks opening at `:228`/`:239`), so the stored text can contain newlines; rendered in a plain `div` they would collapse into one run-on line. The value elements must carry `white-space: pre-wrap` and `overflow-wrap: anywhere` (a scoped `<style>` block in the panel, or Quasar utility classes — the panel has no `<style>` block today, so adding a small scoped one is acceptable and preferred over inline `style` attributes). Each field is capped at 500 chars by `V159__booking_session_completion_data_recap.sql:9-10` and `WrapUpRequest.java:21-22`, so no truncation/expand affordance is required — see Dev Notes "Rejected: clamp-and-expand".

6. **Markup and typography mirror `SessionPrepCard.vue:20-32` exactly** — the established in-repo idiom for these two fields: a `text-caption text-weight-bold` label div followed by a `text-body2` value div, with `q-mt-xs` separating the second label from the first value. Use the panel's existing **global `$t`** injection; do **not** add a `useI18n()` import to `PlayerTimelinePanel.vue` (`SessionPrepCard.vue` uses `t` from `useI18n()`, but the timeline panel deliberately has no i18n import today — `:84-86` — and the template already uses `$t`/`$te` throughout).

7. **Two new i18n keys in all three locales, with the existing human-reviewed translations copied verbatim.** Add `whatWeDidLabel` and `whatsNextLabel` to the `development.timeline` block, positioned **after `accessExpired` and before the `eventType:` sub-object** so `eventType` stays last as it does in all three files: `en-US/index.js` (`accessExpired` `:889`, `eventType` `:891`), `de-DE/index.js` (`:1132` / `:1134`), `fr-FR/index.js` (`:884` / `:885`). Copy the strings verbatim from the already-shipped, already-reviewed `coach.sessionPrep*Label` keys — do **not** write new translations:

   | Key | en-US (`:257-258`) | de-DE (`:288-289`) | fr-FR (`:21-22`) |
   |---|---|---|---|
   | `whatWeDidLabel` | `What we worked on` | `Was wir erarbeitet haben` | `Ce sur quoi nous avons travaillé` |
   | `whatsNextLabel` | `What's next` | `Was als Nächstes kommt` | `La prochaine étape` |

   **New keys under `development.timeline.*`, not reuse of the `coach.*` keys in place.** `PlayerTimelinePanel.vue` is a shared `components/development/` component rendered on parent and player pages; reaching into the `coach.*` namespace from it would couple a non-coach surface to a coach namespace and would be the first cross-namespace key reuse in this codebase. Duplicated strings are the cheaper trade here.

8. **Test coverage extends the existing `PlayerTimelinePanelSpec.js`, reusing its `mountPanel` helper (`:10-31`) — do not write a new spec file or a new mounting harness.** That helper's shape is non-obvious and must be reused as-is: `createTestingPinia({ createSpy: vi.fn, initialState: { development: {...} } })` with `stubActions` left at its **default `true`** (so the `onMounted` `fetchTimeline` is inert), a non-shallow `mount` with `global: { plugins: [pinia] }` only, and **no router, no explicit i18n plugin, and no explicit Quasar plugin** — Quasar and i18n come from `test/vitest/setup-file.js` (`:10`, `:18-27`). Import `describe/it/expect/vi` explicitly; `globals` is `false` (`vitest.config.mjs`). Run with `cd src/frontend && npm run test:unit`.

   **Assert on raw i18n keys, not translated copy.** The global `$t` mock echoes the key back (`setup-file.js:34-36`), so the assertion is `expect(wrapper.text()).toContain('development.timeline.whatWeDidLabel')`. (`$te` is falsy under this stub — already documented at `PlayerTimelinePanelSpec.js:87-88` — which is why existing entry titles fall through to the raw `eventType`; that behaviour is unchanged by this story.)

   New cases required, all built on fixtures shaped like the spec's existing `{ id: 'e1', eventType: 'SESSION_COMPLETED', occurredAt: new Date().toISOString() }` (which carries **no `metadata` key at all** today — the null-metadata case is therefore already half-covered by the existing five tests, which must all still pass):
   1. Both fields present → both labels **and** both text values render.
   2. Only `whatWeDid` present → its label and value render; `development.timeline.whatsNextLabel` is **absent** from `wrapper.text()`.
   3. `metadata: { coachId: '…' }` with both recap keys absent — the **redacted / other-coach** shape, which is what the backend actually sends (keys are `remove`d from the map, not nulled: `TimelineQueryService.java:86-88`) → **neither** label renders.
   4. `metadata` absent entirely (legacy row) → no crash, neither label renders.
   5. A `RADAR_ASSESSMENT` entry with `metadata: { skillCodes: ['PASSING'] }` → neither recap label renders and no array content leaks into the output.
   6. A mixed list — one entry with a recap, one `SESSION_COMPLETED` without — asserting the labels appear exactly once (AC4's mixed-coach case).

9. **Explicitly out of scope — must not be implemented here:**
   - **Any backend change at all.** The data is already on the wire for the authoring coach; see AC10. Do not add an endpoint, a DTO field, a flattening mapper, or a migration.
   - Any change to `src/frontend/src/stores/development.store.js` or `src/frontend/src/api/development.api.js` — `metadata` already survives untouched into store state (`development.store.js:252-255` assigns the raw response wholesale, with array concatenation as the only transformation), and the API call needs nothing (`development.api.js:39-40`).
   - Converge #16's recap → `ReportGenerationService`/`PerformanceReportResource` tie-in (the Fast-Follow; confirmed still unimplemented — `ReportGenerationService` has no `SessionCompletionDataRepository` dependency and no recap reference).
   - The player/parent-initiated flag (Deferred #17, permanently parked).
   - Surfacing `attentionScore`, a days-since-last-session count, or `SkillTrendDirection` on the roster — separate gaps, `skillars-12-1` AC10 explicitly defers the trend field.
   - Any **edit/delete** affordance for a recap. This story is read-only display; the recap is written once at wrap-up and there is no update path today.
   - Stripping `metadata.coachId` from the response for non-coach viewers — a pre-existing, deliberate denylist behaviour (`TimelineQueryService.java:86-88` removes only the two recap keys; `PlayerTimelineResourceIT.java:326` asserts `coachId` **remains** for a second coach). It is a raw UUID, is not rendered by this story, and changing it would break that assertion. See Dev Notes "Observed, not fixed".

10. **Verification that no backend work was done or was needed.** The story is complete only if `git diff --stat` for the implementation touches **no** file under `src/main/java`, `src/main/resources`, or `src/test/java`. The recap is already serialized to the authoring coach today — proven by the existing, passing `PlayerTimelineResourceIT.getTimeline_asAuthoringCoach_includesRecapFields` (`:267-294`), which reads `whatWeDid`/`whatsNext` straight out of the HTTP JSON body.

## Tasks / Subtasks

- [x] Task 1: Add the two i18n keys to all three locales (AC: 7)
  - [x] 1.1 `src/frontend/src/i18n/en-US/index.js` — insert `whatWeDidLabel: 'What we worked on',` and `whatsNextLabel: "What's next",` inside the `development.timeline` block, after the `accessExpired` entry (`:889-890`) and before `eventType: {` (`:891`).
  - [x] 1.2 `src/frontend/src/i18n/de-DE/index.js` — same two keys at the analogous position (`accessExpired` `:1132`, `eventType` `:1134`), values `'Was wir erarbeitet haben'` / `'Was als Nächstes kommt'`.
  - [x] 1.3 `src/frontend/src/i18n/fr-FR/index.js` — same, at `accessExpired` `:884` / `eventType` `:885`, values `'Ce sur quoi nous avons travaillé'` / `'La prochaine étape'`.
  - [x] 1.4 Confirm all three files still parse and that key **order and nesting stay structurally parallel** across locales (they are identical today; keep them so). There is **no automated key-parity test** anywhere under `src/frontend/src/i18n/` — this check is manual, so actually perform it rather than assuming CI will catch a missed locale.

- [x] Task 2: Render the recap in the timeline entry's default slot (AC: 1, 2, 3, 4, 5, 6)
  - [x] 2.1 In `src/frontend/src/components/development/PlayerTimelinePanel.vue`, add the recap block between `:44` (`</template>`, closing `#title`) and `:45` (`</q-timeline-entry>`). Shape it on `SessionPrepCard.vue:20-32`:
    ```vue
    <div
      v-if="
        event.eventType === 'SESSION_COMPLETED' &&
        (event.metadata?.whatWeDid || event.metadata?.whatsNext)
      "
    >
      <div v-if="event.metadata?.whatWeDid">
        <div class="text-caption text-weight-bold">
          {{ $t('development.timeline.whatWeDidLabel') }}
        </div>
        <div class="text-body2 recap-text">{{ event.metadata.whatWeDid }}</div>
      </div>
      <div v-if="event.metadata?.whatsNext">
        <div class="text-caption text-weight-bold q-mt-xs">
          {{ $t('development.timeline.whatsNextLabel') }}
        </div>
        <div class="text-body2 recap-text">{{ event.metadata.whatsNext }}</div>
      </div>
    </div>
    ```
    The outer guard carries the OR so a recap-less `SESSION_COMPLETED` emits **no element at all** (AC4). The two **inner** `v-if`s stay independent — do not collapse *those* into one combined `hasRecap`, because AC3 requires each field to disappear on its own.
  - [x] 2.2 Add a scoped `<style>` block (the file has none today) defining `.recap-text { white-space: pre-wrap; overflow-wrap: anywhere; }` per AC5.
  - [x] 2.3 Confirm **no** new prop, **no** `useAuthStore` import, and **no** `useI18n` import were added (AC2, AC6); `defineProps` (`:88-90`) is unchanged.
  - [x] 2.4 Confirm `src/frontend/src/pages/coach/CoachPlayerDetailPage.vue`, `.../parent/ParentDevelopmentPortalPage.vue` and `.../player/PlayerDevelopmentDashboardPage.vue` are **untouched** (AC2).

- [x] Task 3: Extend the panel spec (AC: 8)
  - [x] 3.1 In `src/frontend/src/components/development/__tests__/PlayerTimelinePanelSpec.js`, add the six cases from AC8 using the existing `mountPanel` helper (`:10-31`) unchanged.
  - [x] 3.2 Re-run the five pre-existing tests in that file and confirm they still pass untouched.
  - [x] 3.3 Run the full frontend suite (`cd src/frontend && npm run test:unit`) and confirm no regression in `CoachPlayerDetailPageSpec.js`, `ParentDevelopmentPortalPageSpec.js`, `PlayerDevelopmentDashboardPageSpec.js`, or `developmentStoreSpec.js`. Note `CoachPlayerDetailPageSpec.js` stubs the panel (`PlayerTimelinePanel: true`, `:60`) and asserts only `props('playerId')` (`:141`, `:230`) — since this story adds no prop, those assertions need **no** update.
  - [x] 3.4 Prettier + ESLint clean on every touched file (Prettier is a CI gate — `.github/actions/`).

- [x] Task 4: Verify the no-backend-change contract (AC: 9, 10)
  - [x] 4.1 Run `git diff --stat` and confirm zero files under `src/main/java`, `src/main/resources`, `src/test/java`.
  - [x] 4.2 Confirm `src/frontend/src/stores/development.store.js` and `src/frontend/src/api/development.api.js` are unmodified.

### Review Findings

Raised by `/bmad-code-review` on 2026-10-19 (3 parallel layers — Blind Hunter, Edge Case Hunter, Acceptance Auditor — against the uncommitted working tree, base `d35d146d`). 21 raw findings -> 18 after cross-layer dedup: 4 patch, 4 deferred, 10 dismissed. Zero decision-needed. All 10 ACs verified MET against real source; full frontend suite independently re-run green by two layers (50 files / 384 tests).

- [x] [Review][Patch] Recap wrapper is missing the precedent's `q-mt-sm`, so the block abuts the entry title [src/frontend/src/components/development/PlayerTimelinePanel.vue:46] — AC6 requires mirroring `SessionPrepCard.vue:20-32` *exactly*, and that precedent's wrapper is `<div v-if="hasRecap" class="q-mt-sm">` (`SessionPrepCard.vue:21`). The new outer wrapper carries no class, so with `whatWeDid` present the recap butts against the `#title` output with zero gap — while a `whatsNext`-only entry *does* get a gap from the `q-mt-xs` at `:59`, making the component space itself differently depending on which field the coach filled in. Task 2.1's illustrative snippet also omits it, but that snippet is explicitly derivative ("Shape it on `SessionPrepCard.vue:20-32`"), so AC6's named precedent governs. Raised independently by two layers (blind+auditor). Fix: add `class="q-mt-sm"` to the wrapper at `:46`; leave the `q-mt-xs` at `:59` alone.
- [x] [Review][Patch] The `eventType === 'SESSION_COMPLETED'` clause has no discriminating test fixture [src/frontend/src/components/development/__tests__/PlayerTimelinePanelSpec.js:112] — no fixture in the new describe carries recap keys on a non-`SESSION_COMPLETED` event (the `RADAR_ASSESSMENT` case at `:185` uses `metadata: { skillCodes: ['PASSING'] }`, which the two inner guards reject on their own). Dropping the `event.eventType === 'SESSION_COMPLETED' &&` clause from `:48` leaves all six new cases green, so the clause that enforces "only session events show recaps" is unpinned. The state is not producible today (verified: `RADAR_ASSESSMENT` writes only `skillCodes`, `PERFORMANCE_REPORT` only `coachName`/`reportId`, and the other five `PlayerTimelineEventType` values have no writer at all), so this is defence-in-depth — but pinning it is what stops a future recap-bearing event type leaking silently. Fix: add one case with `{ eventType: 'RADAR_ASSESSMENT', metadata: { whatWeDid: 'x' } }` asserting neither label renders.
- [x] [Review][Patch] Mixed-list "exactly once" assertion counts globally and passes under an inverted guard [src/frontend/src/components/development/__tests__/PlayerTimelinePanelSpec.js:227] — `text.split('development.timeline.whatWeDidLabel').length - 1).toBe(1)` counts over the whole `wrapper.text()` and never ties a label to its own `q-timeline-entry`. Invert the inner guards to `!event.metadata?.whatWeDid` and the counts are still 1 and 1: entry `e1` (both fields) renders nothing, entry `e2` (`{ coachId: 'c2' }`) renders both labels with empty bodies. The test satisfies AC8 case 6 as literally worded but cannot prove the per-entry correctness its name claims. Fix: also assert the *values* appear exactly once (`text.split('Passing drills').length - 1).toBe(1)`), which ties each label to its own entry's data.
- [x] [Review][Patch] Dev Agent Record File List omits three modified workflow artifacts [_bmad-output/implementation-artifacts/skillars-12-5-coach-recap-on-player-timeline.md:242] — the File List names 5 files; `git diff HEAD --name-only` returns 8, adding `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` (the Converge #15 annotation this story's own Source section mandates), `sprint-status.yaml`, and `story-review.md`. No AC is violated (none is under an AC10-restricted path), but the record is narrower than the working tree. Fix: list the three under a "workflow artifacts" note.
- [x] [Review][Defer] Recap guards test truthiness, not blankness — a whitespace-only value renders an orphaned bold label [src/frontend/src/components/development/PlayerTimelinePanel.vue:52] — deferred, not reachable through the product and identical in the shipped sibling. `v-if="event.metadata?.whatWeDid"` is true for `" "` / `"\n"`, which would render the bold label over a visually empty `pre-wrap` line. Unreachable in practice: `WrapUpRequest.java:21-22` is `@NotBlank @Size(max = 500)` enforced by `@Valid` at `SessionCompletionResource.java:81`, and `WrapUpSequence.vue:257-260,389-390` trims twice client-side; `TimelineEventListener.java:56-57` only null-checks on the way in, so a blank needs a direct DB write (pre-V159 rows have the keys *absent*, not blank). The precedent uses the same plain truthiness (`SessionPrepCard.vue:84`), so this should be fixed in both places or neither. Raised by blind+edge.
- [x] [Review][Defer] No i18n key-parity or key-resolution test anywhere in the frontend [src/frontend/test/vitest/setup-file.js:34] — deferred, pre-existing and project-wide. The global `$t` mock echoes the key back, so AC8's mandated raw-key assertions pin the *template* spelling but would stay green against a typo'd or mis-nested key in a locale file — the suite cannot see the three hand-edited locale files at all. Task 1.4 already records that no automated key-parity test exists and that the check is manual. Both project-aware layers performed that manual check for this story and it passes (keys resolve in all three locales: `en-US:891-892`, `de-DE:1134-1135`, `fr-FR:885-886`, exactly one `timeline:` block per file), so this story is clean; the standing gap is the deferred item.
- [x] [Review][Defer] Recap label/value pairs have no semantic association for assistive tech [src/frontend/src/components/development/PlayerTimelinePanel.vue:52] — deferred, fixing it here would violate AC6. The label is a `text-caption text-weight-bold` div and the value a sibling `text-body2` div, so a screen reader announces four unrelated text runs with no label->value relationship, and boldness is conveyed by class rather than `<strong>`. A `<dl>/<dt>/<dd>` or `aria-labelledby` pairing would fix it at no visual cost — but AC6 mandates mirroring `SessionPrepCard.vue:20-32` exactly, which uses this same div idiom, so the change belongs to both surfaces at once rather than to this story.
- [x] [Review][Defer] Offset pagination can repeat an event across pages and the store appends without an id-keyed merge [src/frontend/src/stores/development.store.js:255] — deferred, pre-existing (deferred-153 lineage) and AC9 forbids touching the store. `PlayerTimelineRepository.java:21-29` added an `IdDesc` tie-break precisely because a row can appear on two consecutive pages, but that fixes *ties*, not the offset shift from a row inserted at the head between two requests (`PageRequest.of(page, size)`, `TimelineQueryService.java:61`). With 51 events, a wrap-up submitted in another tab between page 0 and "Load more" re-serves the old event 50, and the store concatenates it without de-duplication -> the same recap renders twice under a duplicate `:key`. Pre-existing, but the recap text is what makes it visually obvious for the first time.


**Patch audit (2026-10-19).** All four patch findings applied by the user and independently audited: patch 1 (`q-mt-sm` at `PlayerTimelinePanel.vue:51`) matches the `SessionPrepCard.vue:21` precedent AC6 names; patches 2 and 3 verified by mutation testing rather than by inspection — removing the `event.eventType === 'SESSION_COMPLETED' &&` clause fails exactly the new `:205` case and nothing else, and inverting all three recap guards is caught only by the new value-count assertion at `:248` while the two original label-count assertions still pass 1/1 (the precise hole the finding described); patch 4's workflow-artifact list matches `git status` exactly. Full frontend suite 50 files / 385 tests green (384 + 1 new case), Prettier + ESLint clean, AC10 re-verified at 0 files under `src/main/java`, `src/main/resources`, `src/test/java`. The four deferred items remain open in `deferred-work.md`.

## Dev Notes

### Why there is no `showRecap`/`isCoach` prop

The obvious-looking design — a `showRecap` Boolean prop defaulting to `false`, passed `true` only from `CoachPlayerDetailPage.vue`, mirroring `PerformanceReportsPanel.vue:76`'s `isCoach: { type: Boolean, default: false }` — is **deliberately rejected**. Three reasons:

1. **The security boundary is already server-side, and a second client-side gate can only drift from it.** `TimelineQueryService.redactRecapIfNotAuthoringCoach` (`:76-90`) removes `whatWeDid`/`whatsNext` from the response for every viewer except the coach whose id matches that event's own `metadata.coachId` — including other coaches with valid access, and including the player and parent. This is the single read path: `PlayerTimelineResource.java:43` is the only production caller of `getTimeline` (verified by grep), and every `TimelineEventResponse` it builds passes through the redactor (`:66`). A `v-if="event.metadata?.whatWeDid"` is therefore *exactly* as restrictive as the policy, automatically, with no role logic to keep in sync. Coverage already exists for all three viewer classes: `PlayerTimelineResourceIT.java:272-294` (authoring coach sees it), `:302-328` (second coach does not — assertion at `:327`), `:332-347` (parent does not — assertion at `:346`).
2. **The `PerformanceReportsPanel` analogy does not actually hold.** Its `isCoach` prop gates an *action affordance* (`v-if="isCoach && canGenerate"`, `:7` — whether to offer a Generate button), not the visibility of sensitive data. There is no precedent in this codebase for a client-side prop hiding data the server already filtered.
3. **Zero-touch on the parent and player pages is the lowest-regression outcome.** A prop means editing three call sites and adding defensive assertions to two specs that do not import the component today. More importantly it creates a live copy-paste trap: `PlayerDevelopmentDashboardPage.vue` has `isCoach` in scope (`:193`) and already forwards it to a sibling panel on `:107`, so the prop design invites exactly the wrong wiring on the line below.

**The no-prop design has no behavioural consequence anywhere — verified, not assumed.** A coach cannot reach `PlayerDevelopmentDashboardPage.vue` at all: its only route is `player/development/:playerId` with `meta: { requiresAuth: true, role: 'PLAYER' }` (`routes.js:283-288`), and the guard at `router/index.js:79-82` redirects any authenticated non-player away via `routeForRole`. `authStore.role` is a single string, so `isCoach` and `isPlayer` are mutually exclusive (`auth.store.js:16-18`) and no dual-role account exists. That page's own `isCoach` (`:193`) is therefore permanently `false`. Combined with server-side redaction, the recap can surface on exactly one screen — the coach player-detail page — with or without a prop. **Do not "tighten" this with a prop later on the belief that the recap is leaking somewhere; it is not.**

### State branches that must keep working

`PlayerTimelinePanel.vue` has seven render states. The recap block lives **inside** the events list, i.e. inside branch 4 only, and must not perturb the others:

1. `timelineLoading` → `q-inner-loading` (`:7`); title still shown. Only the **error** banner is guarded on `!timelineLoading` (`:10`); the `accessExpired` banner at `:19` is a bare `v-else-if` with no loading guard and can therefore paint during a load. Pre-existing, unchanged by this story — noted so it is not mistaken for a regression introduced here.
2. `timelineError && !timelineLoading` → negative banner (`:9-16`) replaces everything below, including Load-more.
3. `timeline?.accessExpired` → orange `lock_clock` banner (`:18-27`), no list. This is also what a coach past the 90-day access clock receives — **HTTP 200**, not 403, with `events: []` (empty-list return at `TimelineQueryService.java:53`; the `200` itself is `ResponseEntity.ok(...)` one layer up at `PlayerTimelineResource.java:43`).
4. events present → `q-timeline` list (`:30-46`) ← **the only branch this story changes.**
5. events empty → `noEvents` text (`:48-50`).
6. `timeline === null`, no error, not loading → title only (first paint).
7. `hasMore` → Load-more button (`:52-59`) with nested inline retry on `timelineLoadMoreError` (`:66-77`).

### What `SESSION_COMPLETED.metadata` actually contains

At most exactly three keys, written at `TimelineEventListener.java:54-57`:

- `coachId` — **always** present, a UUID string. Written unconditionally (`:55`).
- `whatWeDid` — only if non-null (`:56`).
- `whatsNext` — only if non-null (`:57`).

Note the asymmetry the UI must respect: `metadata.coachId` is populated for **every** session, but the `coach_id` *column* is attendance-gated (`:61`, `coachIdForClock = event.isPlayerAttended() ? … : null`). The column drives the access clock; the metadata key drives the redaction check. They are not the same thing, and this story touches neither.

The other two producible event types and their metadata, for the AC3 guard: `RADAR_ASSESSMENT` → `{skillCodes: [...]}` (`TimelineEventListener.java:84`); `PERFORMANCE_REPORT` → `{coachName, reportId}` (`ReportGenerationService.java:245-249`). The remaining five enum values (`MILESTONE_REACHED`, `HOMEWORK_ASSIGNED`, `VIDEO_UPLOADED`, `PAYMENT_RECEIVED`, `REVIEW_LEFT`) are stubs with no producer today. `ICON_MAP`/`COLOUR_MAP` (`:94-114`) already cover all eight, and their accessor helpers add `?? 'circle'` / `?? 'grey'` fallbacks (`:117`, `:121`), so the panel is already robust to unknown types — do not touch those maps.

### Rejected: clamp-and-expand

A 3-line clamp with a "show more" toggle was considered, since 50 entries × up to 1000 chars of recap is a lot of vertical text. Rejected for this story: it requires per-entry expansion state inside a `v-for`, a 44px-minimum touch target per the UX spec's accessibility rules, and two more i18n keys — real complexity for a problem nobody has reported, on a surface that does not exist yet. The fields are hard-capped at 500 chars each and the list is already paginated (`size = 50`, `development.api.js:39-40`). Revisit only if coaches with long histories actually complain; `white-space: pre-wrap` (AC5) keeps the text readable in the meantime.

### Observed, not fixed

- **`metadata.coachId` reaches the player and parent.** Redaction is a **denylist** of exactly the two recap keys (`TimelineQueryService.java:86-88`), explicitly described as such at `TimelineEventListener.java:29-31` ("not a guarantee that every FUTURE key…"). So a parent fetching their child's timeline receives the coach's internal UUID. Pre-existing, asserted-as-present by `PlayerTimelineResourceIT.java:326`, not rendered by this story, and out of scope (AC9) — but worth a ledger entry, because this story is the first thing to put `metadata` on screen and the next key added to that map will be exposed by default.
- **The recap copy in `development.player_timeline_events.metadata` is outside both GDPR anonymisation paths.** `SessionCompletionDataRepository.anonymizeByPlayerId`/`anonymizeByCoachId` (`:43`, `:50`) scrub only `booking.session_completion_data`; `skillars-deferred-154` AC2 explicitly deferred "the `player_timeline_events.metadata` recap copy" (see `deferred-work.md`, the owner decision of 2026-10-17). The **player** side is nonetheless covered in practice — player erasure deletes the timeline rows outright (`GdprErasureService.java:1217`, `playerTimelineRepository.deleteByPlayerId`). The **coach** side is not: after a coach erasure, their authored recap survives in timeline metadata. This story does not widen the exposure — the surviving copy is visible only to the authoring coach, who is the erased party — but it does make the copy renderable for the first time, so it is recorded here rather than left implicit.
- **A just-finished session can be briefly absent from the timeline altogether.** `TimelineEventListener.onBookingCompleted` is `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` writing in its own `REQUIRES_NEW` transaction, with failures caught and logged (`:42-73`). So a coach who submits a wrap-up and immediately opens that player's detail page may see **no entry at all** for that session for a moment — distinct from AC4's three recap-less cases, which are durable states. Transient, self-resolving on refresh, not specific to recap data (it applies to `SESSION_COMPLETED` visibility generally), and AC9 forbids touching the listener. Noted only so it is not mistaken for a bug in this story's rendering.
- **There is no `TimelineQueryServiceTest`.** All redaction coverage is at the IT level (`PlayerTimelineResourceIT`). Adding a unit test is not required by this story (no backend change), but is a cheap future hardening if someone touches the redactor.

### Testing standards

Frontend unit tests are Vitest + `@vue/test-utils` on `happy-dom`, specs beside the code in `__tests__/`, `describe/it/expect/vi` imported explicitly (`globals: false`). They run via `cd src/frontend && npm run test:unit` and are **not** part of `mvn verify` — the Maven-invoked `npm test` script is a permanent no-op stub; CI runs them through the `frontend-unit-tests.yml` job. Per this project's standing convention (`docs/validation-strategy.md`), do **not** run `mvn verify` locally; CI is the full-verification gate. Since this story changes no Java, the frontend suite plus Prettier/ESLint is the complete local gate.

### Project Structure Notes

Entirely within `src/frontend/src`. No new files, no new routes, no new store state, no new API call — this is the smallest story in Epic 12: one component edit, three i18n edits, one spec extension. The `development` backend module is untouched, so the `platform.{module}.{layer}` conventions, the Flyway/migration-conventions rules, and the `@PreAuthorize`-on-every-endpoint rule are all unengaged by this change.

### References

- `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` — Converge #15 and Action Planning → Priority 2 ("visible back to the coach on that player's timeline"), the success indicator this story closes.
- `_bmad-output/implementation-artifacts/skillars-12-2-session-close-recap-capture.md` — AC4 (coach-only redaction policy) and AC8 (the deferral that created this gap).
- `_bmad-output/implementation-artifacts/skillars-12-4-coach-facing-player-detail-page.md` — AC4 ("`PlayerTimelinePanel.vue` **unchanged** … exactly three pieces and nothing else"), the other half of the handoff.
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineQueryService.java#L76-90` — `redactRecapIfNotAuthoringCoach`; the server-side gate AC2 relies on.
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineEventListener.java#L32-34,L54-61` — the three metadata key constants and the conditional writes behind AC3.
- `src/test/java/com/softropic/skillars/platform/development/api/PlayerTimelineResourceIT.java#L272-294` (`getTimeline_asAuthoringCoach_includesRecapFields`, the AC10 wire-shape proof), `#L302-328` (second coach — `doesNotContainKeys` at `:327`, `coachId` still asserted present at `:326`), `#L332-347` (parent — `doesNotContainKeys` at `:346`).
- `src/frontend/src/components/development/PlayerTimelinePanel.vue#L29-50,L88-90,L94-122` — the insertion point, the unchanged `defineProps`, and the icon/colour maps not to touch.
- `src/frontend/src/components/booking/SessionPrepCard.vue#L20-32,L84` — the markup/typography idiom AC6 mirrors, and the existing `hasRecap` OR-guard precedent.
- `src/frontend/src/stores/development.store.js#L252-255` — proof that `metadata` survives into store state untouched (AC9).
- `src/frontend/src/components/development/__tests__/PlayerTimelinePanelSpec.js#L10-31,L87-88` — the `mountPanel` helper AC8 reuses, and the documented `$te`-is-falsy gotcha.
- `src/frontend/test/vitest/setup-file.js#L10,L18-27,L34-36` — global Quasar/i18n install and the `$t` key-echo mock the assertions depend on.
- `src/frontend/src/components/development/PerformanceReportsPanel.vue#L7,L76` and `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue#L107,L193` — the `isCoach`-prop precedent Dev Notes explains away, and the wrong-wiring trap AC2 forbids.
- `_bmad-output/implementation-artifacts/deferred-work.md` — `skillars-deferred-154` AC2's owner decision of 2026-10-17 deferring the `player_timeline_events.metadata` recap copy.
- `_bmad-output/project-context.md` — i18n externalization, Prettier-mandatory, Vue `<script setup>`, and frontend test conventions.

### Pre-Implementation Story Review Resolution (2026-10-19)

Reviewed by `story-review.md` (4 parallel layers, audited against real HEAD `d35d146d`): 59 citations, 12 ledger/precedent attributions, 10 mechanistic claims, plus a corner-case sweep. Every finding below was **independently re-verified against real source before being accepted** — per this project's standing rule that a review's own confidence is not evidence.

**Accepted and applied (4):**
- **AC4 overstated its source count: four → three.** `forceCompleteWithPlaceholderData` is the only writer of a null-recap row, so the former items 2 ("force-completed") and 3 ("Quick-Completes with null recap fields") were the same code path counted twice. Re-verified directly: `QuickCompleteTimeoutService:69` forwards `scd.getWhatWeDid()/getWhatsNext()` read back from a row that `submitWrapUp` populated under `@NotBlank` validation, off a query filtered on `COMPLETED_PENDING_CONFIRMATION` — a genuine Quick-Complete can never carry a null recap. AC4 now states three sources and explicitly warns off the phantom fourth.
- **AC4's "the coach's own force-completed" misnamed the actor.** Confirmed by grep: the only two callers are `StuckCompletionSweeperService` (`ActorRole.SYSTEM`) and `AdminBookingCompletionService` (`ActorRole.ADMIN`) — never coach-initiated. The coach still sees the gap on their own session; only the actor wording was wrong, and is now stated precisely.
- **`WrapUpSequence.vue:229,240` was off by one** — those are the `v-model` lines; `type="textarea"` is at `:230`/`:241`. Corrected.
- **The `ICON_MAP`/`COLOUR_MAP` range `:94-114` excluded the `??` fallbacks it was cited for** (they live in the accessor helpers at `:117`/`:121`). Range and the References entry corrected.

**Accepted as a nuance (2):** the `200` in AC9's access-expired note comes from `ResponseEntity.ok(...)` at `PlayerTimelineResource.java:43`, not from `TimelineQueryService.java:53` itself (both now cited); and the async-listener timing window — a just-completed session can be briefly absent from the timeline entirely — is added to "Observed, not fixed" as informational, since it is transient, pre-existing, not recap-specific, and AC9 forbids touching the listener.

**Rejected as false positives (4).** The review reported "path drift" on `DrillCard.vue`, `DrillDetailPanel.vue`, `WrapUpRequest.java`, and `CoachPlayerDetailPageSpec.js`, claiming this story cited them under `components/development/`, `booking.dto`, and `components/development/__tests__/` respectively. **This story cites all four by bare filename with no directory or package at all** — the review supplied the wrong paths itself and then flagged them. No edit made; recorded here so a later pass does not "re-fix" citations that were never wrong.

Everything else held: 52/59 citations exact, all 12 ledger attributions confirmed unfabricated (including the `skillars-deferred-154` owner-decision reference), all 10 mechanistic claims confirmed by full-method reads — notably that `PlayerTimelineResource.java:43` is the sole production caller of `getTimeline` and that redaction is a denylist every response passes through. The review's own kill list separately confirmed the Quasar `dense`-layout non-clipping behaviour, the impossibility of a `whatWeDid`-without-`coachId` shape, and current i18n key-order parity across all three locales. No AC or Task required a structural change; Tasks 1–4 are unchanged.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Debug Log References

None — implementation went exactly per the story's prescriptive Tasks/Subtasks and Dev Notes; no deviation, no HALT condition hit.

### Completion Notes List

- Task 1: Added `whatWeDidLabel`/`whatsNextLabel` to `development.timeline` in all three locale files, at the exact position specified (after `accessExpired`, before `eventType`), with values copied verbatim from the existing `coach.sessionPrep*Label` keys — verified byte-for-byte against the live `coach.*` strings before writing, not retyped from the story table. Key order/nesting stays structurally parallel across `en-US`/`de-DE`/`fr-FR`.
- Task 2: Added the triple-guarded recap block to `PlayerTimelinePanel.vue`'s default slot exactly as specified in 2.1 (outer OR-guard on `SESSION_COMPLETED` + either field present; two independent inner `v-if`s), plus the scoped `.recap-text { white-space: pre-wrap; overflow-wrap: anywhere; }` style block (AC5). Confirmed no new prop, no `useAuthStore`/`useI18n` import, and the three page files (`CoachPlayerDetailPage.vue`, `ParentDevelopmentPortalPage.vue`, `PlayerDevelopmentDashboardPage.vue`) are untouched (`git diff --stat` confirms).
- Task 3: Added all six AC8 cases to `PlayerTimelinePanelSpec.js` using the existing `mountPanel` helper, unchanged. Full spec file: 11/11 passing (5 pre-existing + 6 new). Full frontend suite: 50 files / 384 tests passing, zero regressions (including `developmentStoreSpec.js`, `CoachPlayerDetailPageSpec.js`, `ParentDevelopmentPortalPageSpec.js`, `PlayerDevelopmentDashboardPageSpec.js`). Prettier `--check` and ESLint both clean on every touched file.
- Task 4: `git diff --stat` confirms zero files under `src/main/java`, `src/main/resources`, or `src/test/java`, and `development.store.js`/`development.api.js` are unmodified — the no-backend-change contract (AC9, AC10) holds.
- Per `docs/validation-strategy.md`, `mvn verify` was not run locally (no Java changed); the targeted frontend suite above is the complete local validation gate for this story.

### File List

- `src/frontend/src/components/development/PlayerTimelinePanel.vue` (modified)
- `src/frontend/src/components/development/__tests__/PlayerTimelinePanelSpec.js` (modified)
- `src/frontend/src/i18n/en-US/index.js` (modified)
- `src/frontend/src/i18n/de-DE/index.js` (modified)
- `src/frontend/src/i18n/fr-FR/index.js` (modified)

**Workflow artifacts** (pre-existing from this story's own creation/review lifecycle, not implementation output; included for completeness per the code review's Patch finding):
- `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` (modified — Converge #15 annotation, per this story's own Source section)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — status transitions)
- `_bmad-output/implementation-artifacts/story-review.md` (modified — pre-implementation story review)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — 4 items logged by the code review)

## Change Log

- 2026-10-19: Implemented Tasks 1-4. Added `whatWeDidLabel`/`whatsNextLabel` i18n keys (3 locales); rendered the triple-guarded recap block in `PlayerTimelinePanel.vue`'s default slot with a scoped `pre-wrap` style; extended `PlayerTimelinePanelSpec.js` with 6 new cases (11/11 passing); verified zero backend files touched. Status: ready-for-dev → review.
- 2026-10-19: Addressed `/bmad-code-review` Patch findings (4/4): added the missing `q-mt-sm` wrapper class to mirror `SessionPrepCard.vue:21` exactly (AC6); added a `RADAR_ASSESSMENT`-with-recap-shaped-metadata test case pinning the `eventType === 'SESSION_COMPLETED'` guard; strengthened the mixed-list test to assert on actual recap text values, not just label-key counts, closing the inverted-guard blind spot; completed the File List with the three pre-existing workflow artifacts. Full frontend suite re-run: 50 files / 385 tests passing. Prettier + ESLint clean. All 4 Deferred findings were independently re-verified against real source and confirmed correctly deferred (logged to `deferred-work.md`); no action needed on them.
