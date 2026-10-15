# Story Review — skillars-12-2-session-close-recap-capture

**Audit date:** 2026-10-15
**Story:** `_bmad-output/implementation-artifacts/skillars-12-2-session-close-recap-capture.md` (Story 12.2: Session-Close Recap Capture), status `ready-for-dev`
**Real HEAD at audit time:** `9e3ae6a1` — *Merge pull request #266 from tenjohokwen/story/12-1-coach-player-roster-and-attention-summary*

HEAD was read with `git rev-parse` / `git log -1` **before** the story was opened, and re-confirmed unchanged at the end of the audit. **Every citation below was verified against that HEAD on disk — not against any commit SHA, line number, or "master is at…" claim written inside the story itself.** The source brainstorm doc was read from the **working tree** (it carries uncommitted annotations).

Verification ran as four parallel read-only layers, followed by a mandatory adversarial re-verification pass (Step 4b) in which every surviving finding's evidence was re-read fresh with the goal of refuting it. Section 6 lists what that pass killed or downgraded — including findings the layers raised and findings the auditor raised.

**Scale:** ~95 discrete claims checked (46 code citations, 23 ledger/precedent attributions, 24 mechanistic claims, plus AC-by-AC flow walks). **2 HIGH + 5 MEDIUM + 13 LOW findings survived.** 9 candidate findings were killed or downgraded in Step 4b.

---

## 1. Verdict

The story is **unusually well-grounded** — its design is correct, its single-redaction-point analysis is right, its ID-space reasoning is right, its back-compat constructor strategy is right, and the overwhelming majority of its 46 file:line citations land exactly. It is **not** ready to hand to a developer as-is, for three reasons, in priority order:

1. **One product decision is presented as settled when the source doc says the opposite** (Finding M1). It drives AC4, AC6, Task 3.2 and one of Task 3.3's three tests. It needs an owner's call before dev starts, not after.
2. **Two defects would bite a developer who follows the tasks literally** (Findings H1, H2) — one guaranteed CI failure, one silent user-facing dead-end.
3. **A cluster of counting/attribution errors** (Findings L1–L3, M4) would mislead a developer mid-task or survive into the next story as inherited misinformation.

---

## 2. Citation verification (Layer 1)

46 citations checked. **41 MATCH, 5 DRIFTED, 0 fabricated.** Only the drifts and the consequential confirmations are tabulated; a plain MATCH with no implication is omitted for length.

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| `WrapUpRequest.java:13-21` | MATCH | 7 record components, order exactly as described; `@NotBlank` already imported (AC1 needs no new import). File is 21 lines. |
| `WrapUpRequest.java:18` | MATCH | `    String voiceNoteText,` — and **zero** validation annotations (bears on Finding L9). |
| `SessionCompletionData.java:17-65`; `:49-50` | MATCH | `@Entity` L17, class L22, closes L65. L49 `@Column(name = "voice_note_text", length = 2000)` / L50 `private String voiceNoteText;` |
| `BookingCompletionService.java:138-143`; `:142` | MATCH | L142 is `scd.setVoiceNoteText(req.voiceNoteText());` exactly. (Setter block actually begins L135.) |
| `BookingCompletionService.java:166-170` | MATCH | LIVE-mode publish, inside `if ("LIVE".equals(req.mode()))` at L160. |
| `BookingCompletionService.java:197-198`; `:201-205` | MATCH | L197-198 `findByBookingId(...).orElseThrow(...)`; L201-205 the publish. |
| `submitWrapUp = :128-182` | MATCH | `@Transactional` L127, signature L128, closes L182. |
| **`confirmCompletion = :185-215`** | **DRIFTED** | Actual **L184-206** (`@Transactional` L184, sig L185, closes L206). The cited range overshoots 9 lines into the unrelated private helper `deserializeHomeworkDrillIds` (**L208-218**) — a developer told to edit "at L185-215" is editing across a method boundary. |
| `QuickCompleteTimeoutService.java:65-69`; `:50-78` | MATCH | Publish L65-69 inside `transactionTemplate.executeWithoutResult`; method `@Scheduled` L50, `@SchedulerLock` L51-52, sig L53, closes L78. |
| `BookingCompletedEvent.java:10-37` | MATCH on range; **framing wrong** | Range exact (file is 37 lines). But it is a **`class extends ApplicationEvent`** with Lombok `@Getter`, **not** a record, and its constructor takes **10** parameters. See Finding L1. |
| `TimelineEventListener.java:34-48`; `:66-78` | MATCH | Annotations verified on `onBookingCompleted` itself (L34-35), not borrowed from the identically-annotated sibling `onRadarEntrySubmitted` (L50-52). `Map.of("coachId", …)` at L43. |
| `TimelineQueryService.java:28-58`; `:39-48`; `:50-57`; `:53-56` | MATCH | All four ranges exact. |
| `TimelineEventResponse.java` | MATCH | Record, 6 components, `metadata` last, typed `Map<String, Object>`. |
| `SluCalculationServiceIT.java:768` | MATCH | Sole `new BookingCompletedEvent(` in the file. |
| `HomeworkAssignmentServiceTest.java:68,82,92,104,125` | MATCH | Exactly those 5, no more. |
| `SessionPlanServiceTest.java:319,332,348,367` | MATCH | Exactly those 4, no more. |
| `SessionCompletionResourceIT.java:188-234` | MATCH (body offsets ~2 low) | Both named tests exist (L189, L213). `Map.of(` bodies are at **L196-202** and **L220-226**; story said ~194-201 / ~218-225. Story used "~", so advisory only. |
| `BookingCompletionServiceTest.java:99,132,255` | MATCH | All three are `new WrapUpRequest(...)`, currently 7-arg. Repo-wide: exactly these 3. |
| `PlayerTimelineResourceIT.java:492-499` | MATCH | `insertTimelineEvent`; insert columns `(id, player_id, event_type, reference_module, occurred_at)` — the story's "inserts no metadata at all today" is **correct**, and is the root of Finding H1. |
| **V157 / V158 "nullable single-purpose `ADD COLUMN` … no backfill"** | **DRIFTED — materially wrong** | See Finding M4. `SET LOCAL lock_timeout = '5s';` + `ADD COLUMN IF NOT EXISTS` do check out; "single-purpose" and "no backfill" do not. **V159 is confirmed free** (highest on disk is V158). |
| `docs/deployment/migration-conventions.md` rule 1 | MATCH | L54-56, quoted verbatim: "New columns are nullable or carry a default". |
| `WrapUpSequence.vue:6` | MATCH | `v-for="n in 4"`; pip classes use `n === step` / `n < step`, correct at 5. |
| `WrapUpSequence.vue:221-227` | MATCH | Step-4 Done button; `:label` L224, `@click` L226. |
| `WrapUpSequence.vue:30,59,84,112` → `t('common.next')` | MATCH | All four exact, and all four really are in Steps 1–2 as the story says. (A fifth use at L174 exists but is in **Step 3** — see Section 6, killed finding K1.) |
| `WrapUpSequence.vue:~260`; `:340-348` | MATCH | Refs block L253-268 (`voiceNoteText` L260); payload object L340-348. |
| `PlayerTimelinePanel.vue:31-45` | MATCH | `q-timeline-entry` loop; reads only `event.id`/`eventType`/`occurredAt`. No `metadata`, no `v-html`. |
| `en-US/index.js:1203-1220`; `:861-876` | MATCH | Both exact. `step4Done: 'Done',` at L1219. |
| **`de-DE/index.js:382-398`; `fr-FR/index.js:1491-1507`** | **DRIFTED (off-by-one)** | Blocks close one line later: **de-DE 382-399**, **fr-FR 1491-1508**. All three locales have 16-key parity. |
| New spec files absent | MATCH | `components/booking/__tests__/` does not exist; `components/development/__tests__/` holds only `SkillsRadarChartSpec.js`. No spec anywhere references either component. Story correct. |

---

## 3. Ledger & precedent attribution (Layer 2)

23 attributions checked against **three** source classes each where applicable (the cited document; code comments/Javadoc near the discussed method; `git log --grep` / `git blame`). **No attribution was fabricated** — every cited document, heading and rule exists. **14 SUPPORTED, 7 PARTIALLY_SUPPORTED, 2 CONTRADICTED.**

| Claim | Sources checked | Result | Verdict |
|---|---|---|---|
| Build order P1–P6 as described | brainstorm L194-201, L171-173, L239-243 | Build order verbatim as story says. But the doc never says P6 is *permanently* deferred — Deferred #17 is "intentionally parked", and the Action Plan says "**revisit** once homework/messaging-adjacent stories are scheduled". | PARTIALLY_SUPPORTED |
| Pattern #4 = "Structured Micro-Recap" | brainstorm L74-76 | Title exact. Concept names **three** components: "what we did, what went well, what's next". | PARTIALLY_SUPPORTED — see L8 |
| Converge #15 "describes the recap as 'a small structured note (what we did / what's next), plus an optional free-text note'" | brainstorm L163-165, L217; phrase grep | Converge #15 contains **no** such parenthetical. The quoted phrase is **Priority 2 Next Steps item 1 (L217)**. Content real, location wrong. | PARTIALLY_SUPPORTED — see L7 |
| Converge #16 + report-gen is the Fast-Follow | brainstorm L167-169, L200, L239-240 | Confirmed in two places ("**Fast-follow:** recap -> report-generation tie-in"). | SUPPORTED |
| Decision #8 = "Coach A never sees Coach B's notes on the same player" | brainstorm L90-92, L131, L188 | Verbatim match. Decision #8 *itself* says nothing about player/parent visibility. | SUPPORTED |
| Priority 2 Next Steps contains "Keep it coach-private per Decision #8" | brainstorm L219 | Verbatim: "3. Keep it coach-private per Decision #8 (siloed notes)." | SUPPORTED |
| Role-Playing frames player/parent as wanting *more* visibility | brainstorm L97-104 | Both quoted fragments exact (L103). But the sentence's own point is that this need is **already satisfied** by an existing dashboard — not that the family should see coach-authored notes. | PARTIALLY_SUPPORTED |
| Priority 2 plan: recap form → `SESSION_COMPLETED.metadata` JSONB | brainstorm L217-218, L133 | Verbatim match on both halves. | SUPPORTED |
| **"Second use for the recap… is about which *feature* consumes the recap next, not a statement that the raw text must be hidden from the family"** | brainstorm L134, L131-132, L219, L221 | **The story's ellipsis elides the decisive clause.** See Finding M1. | **CONTRADICTED** |
| Story 12.1 flagged the Quick-Complete blind spot for `lastSessionDate` | 12.1 L76-78, AC3 L25 | Heading and body exact; substance confirmed verbatim. Only nit: cited anchor is a truncated slug. | SUPPORTED |
| 12.1 flagged `/neglected-skills` + `/slu/coach-contributions` gap as separate follow-up | 12.1 L82; brainstorm L118, L251; `deferred-work.md:3785`; `story-review.md:207` | Confirmed in four independent places. | SUPPORTED |
| Priority 1 is `done` | `sprint-status.yaml:364`; `git log --grep`; HEAD | `done`; merged as `d7fb01bb`, merge commit `9e3ae6a1` = current HEAD. | SUPPORTED |
| 12.2 is the second story of Epic 12 | `sprint-status.yaml` grep | Exactly two `skillars-12-*` keys. | SUPPORTED |
| Awaitility required for async assertions | `project-context.md:63` | "Use **Awaitility** for verifying asynchronous outcomes." | SUPPORTED |
| "Prefer Domain Events for cross-module communication" | `project-context.md:123` | Verbatim. | SUPPORTED |
| Record DTOs / `<script setup>` / centralized `*.api.js` / i18n / Prettier gate | `project-context.md:42, 45, 47, 56, 70`; `.github/actions/frontend-quality/action.yml:36` | All five verbatim; Prettier gate confirmed live in CI (and in `.github/actions/`, not `workflows/`). | SUPPORTED |
| **"Do not run `mvn verify` locally … GitHub CI is this project's *sole* full-verification authority"** attributed to `project-context.md` | `project-context.md` (full grep); no root `CLAUDE.md`; `docs/validation-strategy.md` | **Not in `project-context.md`.** Real authority is `docs/validation-strategy.md` (L20, L38), which the story never cites. And **"sole" is wrong** — L25-29 explicitly permits local `mvn verify` on user request, for cross-cutting changes, or as final pre-PR validation. | PARTIALLY_SUPPORTED — see L6 |
| **V157/V158 are "nullable single-purpose `ADD COLUMN` … with no backfill"** | full text of V157, V158, V156; migration dir listing | **False on both counts.** | **CONTRADICTED** — see M4 |
| Awaitility is an available test dependency | `pom.xml:474-479`; 17 importing test files | `awaitility 4.3.0`, `test` scope. In-module precedent exists (`SluCalculationServiceIT`, `ReportGenerationServiceIT`). Task 3.4 is feasible as written. | SUPPORTED |

---

## 4. Mechanistic claims (Layer 3)

24 claims checked by reading the **full body** of each named method, with an explicit check for confusable same-file siblings, and by tracing real transaction boundaries. **17 CONFIRMED, 6 PARTIALLY_CORRECT, 1 WRONG.**

**Confirmed (consequential):**

- **`submitWrapUp` is mode-agnostic until the publish decision** (`BookingCompletionService.java:127-182`). L129-158 identical for both modes; divergence begins L160. No `Session`/`SessionRepository` reference anywhere in the file. The story's core design premise holds. *Nuance the story glosses:* the LIVE branch also performs a state-machine `transition(QUICK_COMPLETE)` the QUICK branch does not.
- **Single endpoint, `@Valid` applied** (`SessionCompletionResource.java:79-81`, `@Validated` at class level L38). `POST /{id}/complete` with `@RequestBody @Valid WrapUpRequest`. AC1's new `@NotBlank` will genuinely produce a rejection, and `ApiAdvice.java:471-473` maps `MethodArgumentNotValidException` → **400**, as claimed.
- **`SessionCompletionData` is 1:1 per booking.** Entity `unique = true` (`:28-29`); DDL `session_completion_data_booking_id_key UNIQUE (booking_id)` (`V138:2194-2195`). AC2's premise holds.
- **All 3 production publishers already hold the recap data** — `submitWrapUp` has `req`; `confirmCompletion` has `scd` (`:197-205`); the timeout job has `scd` (`:59-69`). No new repository lookup needed anywhere. Claim holds exactly.
- **`confirmCompletion`'s `scd` can never be null** — `orElseThrow` → 404. And the `transition(COMPLETE)` at L192 shares the **same** `@Transactional` scope as that throw, so a missing row rolls the transition back: **no partial completion**. (Transaction boundary traced, not assumed.)
- **Listener annotations are genuinely on `onBookingCompleted`** — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("taskExecutor")` (`TimelineEventListener.java:34-35`), delegating via `self` to `writeTimelineEvent` which is `@Transactional(REQUIRES_NEW)` (`:66`). The identically-annotated sibling `onRadarEntrySubmitted` (`:50-52`) was explicitly checked and is not the source.
- **`metadata` is `Map<String, Object>`** via hypersistence `@Type(JsonType.class)`, column `jsonb`, **`updatable = false`** (`PlayerTimelineEvent.java:47-49`). Task 3.2's `coachId.toString().equals(metadata.get("coachId"))` **type-checks** (`String.equals(Object)`), behaves correctly (JSONB string → `String`), and is receiver-first so it is NPE-safe when the *key* is absent (though not when the *map* is null — Finding H1).
- **ID spaces match.** `verifyCoachOwnership` compares `booking.getCoachId()` against `CoachProfile.getId()` (`:225-229`), and the reader uses `coachProfileService.getCoachIdByUserId(...)` → `CoachProfile::getId` (`CoachProfileService.java:707-711`). Same space. AC4's authoring-coach comparison **will** match — a plausible failure mode that does not materialize.
- **`TimelineQueryService.getTimeline` is the only read path.** `PlayerTimelineRepository` has exactly three consumers in `src/main`: `TimelineEventListener` (write), `TimelineQueryService` (read), `GdprErasureService:1153` (bulk delete). One redaction point suffices. *Advisory:* `ReportGenerationService.java:216-220` is a **fourth caller of `writeTimelineEvent`** — Task 3.1 is safe only because it changes the map *inside* `onBookingCompleted` rather than the helper's signature.
- **No ADMIN/STAFF path to the endpoint.** `@PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(...)")` (`PlayerTimelineResource.java:27`); `PlayerOwnershipGuard.check` matches only `existsByIdAndParentId`/`existsByIdAndUserId`; **no `RoleHierarchy` bean anywhere** in `src/main`. An admin is 403'd at the gate. AC4's `!isCoach` → never-redact branch creates **no** admin exposure.
- **`voiceNoteText` is genuinely optional and write-only.** Zero validation annotations (`WrapUpRequest.java:18`); `grep getVoiceNoteText src/main` → **nothing**. Never read back, never exposed. The story's decision to leave it untouched is sound.
- **`WrapUpSequence.vue` really does back both paths** — single mount at `CoachCommandCenterPage.vue:15` with `:is-live-mode`; Quick Complete sets `isLiveMode=false` (`:576`), live End-Session sets `true` (`:549`); both converge on `handleSubmitWrapUp` → `booking.api.js:57` → `POST /complete`.
- **`*.api.js` is a true passthrough** — `export const submitWrapUp = (id, data) => api.post(...)` forwarding the whole payload object to `/api/bookings/{id}/complete`. No named-field picking. No edit needed.
- **`SluCalculationService.onBookingCompleted` silently no-ops without a `Session` row** (`:57-62`, `log.debug`). Sole listener method in the class; no confusable sibling. *Downstream consequence the story leans on correctly but does not spell out:* this is also why a Quick-Complete-only coach has no `findLastSessionDate`, so such a coach gets `accessExpired=true` and sees **no** timeline at all — making AC4's "any other coach with non-expired access" a narrower population than it reads, which Task 3.3's second-coach IT must seed deliberately (an SLU row, not just a booking).

**Partially correct / wrong:** see Findings L1 (arity), L2 (call-site count), L3 (expiry window), L4 (synchronicity), and Section 5.

---

## 5. Corner cases, false assumptions and missed flows (survived Step 4b)

### HIGH

**H1 — Task 3.2's redaction condition NPEs on a `SESSION_COMPLETED` row with NULL `metadata`, breaking an existing IT that AC8 does not flag.**
`PlayerTimelineResourceIT.java:492-499`, `:141`; story AC4 / Task 3.2.

Task 3.2 prescribes the guard verbatim as `isCoach && coachId != null && coachId.toString().equals(metadata.get("coachId"))`, plus "build a defensive copy of the map before removing keys" — with **no `metadata != null` guard** in either the condition or the copy step. But `metadata` is a nullable JSONB column, and the existing IT seeding helper inserts **no metadata column at all**:

```java
"INSERT INTO development.player_timeline_events " +
"(id, player_id, event_type, reference_module, occurred_at) " +
"VALUES (gen_random_uuid(), ?, ?, ?, NOW())"
```

**Failure scenario:** a developer implements Task 3.2 exactly as written. `getTimeline_asCoach_withActiveAccess_returns200WithEvents` (`PlayerTimelineResourceIT.java:133`) seeds an SLU row *and* a `SESSION_COMPLETED` row with `metadata = NULL` at `:141`, then performs a **coach-authenticated** GET. It passes the expiry gate, reaches the `.map(...)`, and throws NPE inside `TimelineQueryService` (no try/catch there) → **500 instead of 200 → IT fails**. Both halves of Task 3.2 fail this way: `new HashMap<>(null)` NPEs too.

Precisely **one** existing IT breaks. The other two seeded `SESSION_COMPLETED` rows are safe: `:191` is in `getTimeline_asParent_...` and `:226` in `getTimeline_asSelfRegisteredPlayer_...`, both `!isCoach` → short-circuit before touching the map. *(Layer 4 reported "two ITs" and named `:191` as a coach test; that is wrong — corrected here.)*

**Fix:** add `metadata != null` to the guard, and have the helper return the map untouched when null. The story's AC8 carefully enumerates test fallout for AC1's validation change but performs no equivalent sweep for AC4's redaction change — that sweep is the real gap.

**H2 — AC1 makes a 400 reachable from the wizard for the first time, and nothing in the chain can surface it: the coach gets a silent dead-end.**
`WrapUpSequence.vue:337-353`, `booking.store.js:516-519`, `boot/axios.js:157-250`; story AC1 / Task 4.1.

Task 4.1 specifies the two new inputs as `q-input type="textarea" outlined autogrow` with **no `maxlength`, no `counter`, no `:rules`**; the only client gate is `:disable="!recapWhatWeDid.trim() || !recapWhatsNext.trim()"`, which guards blankness only. AC1 adds `@Size(max = 500)` server-side. The full error chain, verified end to end:

- `WrapUpSequence.vue:337-353` — `try { … } finally { submitting.value = false }`. **No `catch`.**
- `booking.store.js:516-519` — `await submitWrapUp(...)`. **No catch.**
- `boot/axios.js` interceptor — explicit branches for **401** (`:162`), **403** (`:226`, `console.warn` only), **5xx** (`:236`, `console.error` only), network and setup errors. **No 400 branch** → falls through to `return Promise.reject(error)` (`:250`) with no notify and not even a console line.

**Failure scenario:** a coach writes a 520-character "what we did" in an `autogrow` textarea with no visible limit and taps submit → backend returns 400 → unhandled promise rejection inside a `@click` handler → `submitting` resets, the overlay stays open, **no error message, no hint what is wrong**, and the booking remains stuck in `COMPLETED_PENDING_CONFIRMATION`.

This is genuinely new. Today **no** validated field on this request can fail from the UI — ratings are `q-rating` 1–5, `mode` is hardcoded, `playerAttended` is a checkbox, and `homeworkDrillIds` is capped at 2 by `toggleDrill` (`:273`, matching `@Size(max = 2)`). I verified each. AC1's `@Size(max=500)` is the first field a coach can actually violate, and it lands on a path with zero error surfacing. No task adds a `maxlength`, a `:rules`, or a `catch`.

### MEDIUM

**M1 — AC4's player/parent-visible recap contradicts the source brainstorm in four places; the story presents a product departure as a settled inference, and supports it with a truncated quote.**
Story Dev Notes reason (d) + AC4; brainstorm L131-132, L134, L219, L221.

The story writes: *"The brainstorm's own 'Second use for the recap… feeding `ReportGenerationService`' note is about which **feature** consumes the recap next, not a statement that the raw text must be hidden from the family on the timeline."* The ellipsis removes the decisive clause. Verbatim L134:

> "Second use for the recap, **chosen explicitly over a parent-visible summary for now**: feeding the existing `ReportGenerationService`/`PerformanceReportResource` pipeline…"

That clause *is* a decision not to make the recap parent-visible yet. Three further independent signals point the same way:

- **L131-132:** "Decision #8 (siloed history) is refined, not reversed: **raw session notes stay private per coach**; aggregate skill-contribution percentages stay cross-coach visible (confirmed explicitly for the coach view too, not just parent/player)." The contrast structure is explicit — *raw notes* are private per coach; *aggregates* are the thing visible to coach **and** parent/player.
- **L219:** "Keep it coach-private per Decision #8 (siloed notes)."
- **L221** (Priority 2 success indicator): "every completed session has a recap attached, visible back to **the coach** on that player's timeline." Coach only.

The story goes further than omitting this: it instructs the developer to disregard L219 — *"Do not read 'coach-private' … as player/parent-exclusion."* Read against L131-132, "coach-private" is not merely cross-coach.

In fairness, the story's other three reasons are factually sound: Decision #8's literal text **is** cross-coach only (verified), and the player/parent **do** see their own full unredacted timeline today (verified). So this is a defensible product argument — but it is a **deliberate departure from the source doc**, not an inference from it, and the story presents it as settled. It drives AC4, AC6, Task 3.2 and one of Task 3.3's three tests.

**Recommended action:** get an explicit owner decision before dev starts. Absent one, the default consistent with the source doc is to redact `whatWeDid`/`whatsNext` for every viewer except the authoring coach.

**M2 — Neither AC2's persistence nor AC3's two *deferred* publish paths have any planned test coverage: a forgotten setter passes every planned test while silently losing every QUICK-mode recap.**
Story Tasks 1.5, 1.6, 3.3, 3.4.

Tracing what the planned tests actually assert:
- **Task 1.5** — the two ITs assert `204` + `bookings.status`. They never read `booking.session_completion_data`.
- **Task 1.6** — the three unit call sites get two extra ctor args; existing assertions are `event.getBookingId()` / `event.isPlayerAttended()`. Nothing asserts the new scd fields or the event's new fields.
- **Task 3.4** — a **LIVE**-mode `submitWrapUp` → Awaitility-poll the timeline. But the LIVE publish reads from the **request**, not the row (`BookingCompletionService.java:166-170`), and Task 2.2 keeps it that way (`pass req.recapWhatWeDid(), req.recapWhatsNext()`). So this test passes without the row ever being correct.
- **Task 3.3** — seeds `player_timeline_events` metadata directly via `jdbcTemplate`, bypassing the write path entirely.

**No planned test ever reads `what_we_did`/`whats_next` back out of `session_completion_data`, and neither scd-sourced publisher (`confirmCompletion:197-205`, `QuickCompleteTimeoutService:59-69`) is exercised at all.**

**Failure scenario:** the developer omits Task 1.4's two setters, or transposes them (`setWhatsNext(req.recapWhatWeDid())`). Every test in Tasks 1.5, 1.6, 3.3 and 3.4 stays green. In production, LIVE-mode recaps appear correctly; **every QUICK-mode booking writes NULL recap columns**, both deferred publishers then pass `null, null`, and Task 3.1's "include only when non-null" silently omits the keys — so the entire Quick-Complete population gets no recap on the timeline, with no error anywhere. AC1 explicitly promises "This applies identically whether `mode` is `LIVE` or `QUICK`"; the test plan only proves LIVE.

**M3 — If the async timeline write fails, the recap is absent from the timeline forever, with no reconciliation path. AC3 states the write unconditionally.**
`TimelineEventListener.java:34-48`; story AC3.

Transaction boundaries traced and confirmed as the story assumes: all three publish sites are inside a real transaction, so `AFTER_COMMIT` does fire for all three. But on failure the listener does `catch (Exception e) { log.error(...) }` — **no outbox, no retry, no DLQ**, unlike the sibling `SluPersistenceRetrier`/`SluPersistenceDispatcher` in the same package, which do have retry infrastructure.

**Failure scenario:** the `taskExecutor` pool rejects the task under saturation, or the `REQUIRES_NEW` transaction hits a connection timeout → one ERROR log line → the player/parent and the authoring coach never see that session's recap, and **nothing ever reconciles it from `session_completion_data`**, where the text is sitting durably. Note `Map.of` also rejects nulls, so Task 3.1's "include only when non-null" is load-bearing: get it wrong and every recap write NPEs into this same silent catch. The story's "Known residual edge case" section discusses only a hypothetical future 9-arg publisher, not this live path.

**M4 — The V157/V158 migration precedent is mischaracterized on both counts.**
Story Task 1.3 + Dev Notes "Migration safety".

The story says V159 should match "the exact convention used by `V157…` and `V158…` (both nullable single-purpose `ADD COLUMN` migrations with no backfill)". Verified by reading both files in full:

- **V157 is not backfill-free.** It ends with `UPDATE reviews.coach_reviews SET author_last_edited_at = CASE WHEN moderation_epoch = 0 THEN created_at ELSE last_modified_at END WHERE author_last_edited_at IS NULL;` — with ~20 lines of comment justifying it, its own header calling it a "Single-transaction backfill".
- **V158 is not single-purpose.** After its `ADD COLUMN` it runs two CTE-based `UPDATE main.platform_config` statements, self-described as "unrelated to the column above but added to this migration because V158 is new/unmerged and this is cheap".

The `SET LOCAL lock_timeout = '5s';` + `ADD COLUMN IF NOT EXISTS` half of the claim **is** correct, and **V159 is confirmed free**. The story's *actual plan* (nullable, no backfill) is also correct and properly grounded in `migration-conventions.md` rule 1 — which I verified independently. **The hazard is instructional:** a developer copying the stated convention will open both files, find backfills and unrelated DML, and could reasonably conclude extra statements are acceptable here.

**M5 — Task 1.3 never states the SQL column type, against a table whose only text precedent uses a different size.**
`V138__baseline_schema.sql:270-283`; `SessionCompletionData.java:49-50`; story Tasks 1.2/1.3.

Task 1.2 specifies `@Column(length = 500)`; Task 1.3 says only "additive, nullable `ADD COLUMN IF NOT EXISTS`" — **no type** — while the V157/V158 migrations it claims to copy "verbatim" both name an explicit type. The table's actual precedent is `voice_note_text character varying(2000)` with `@Column(length = 2000)` and **no `@Size`** on the DTO — four times the story's cap. With `ddl-auto: none`, nothing validates the entity's `length=500` against whatever V159 creates.

**Failure scenario:** the developer writes `ADD COLUMN IF NOT EXISTS what_we_did text` (a reasonable reading of "additive, nullable"). The 500 bound then exists only in `@Size(max=500)` on the DTO — which, per Finding H2, surfaces to the user as nothing at all. Either type is *functionally* safe (a passing `@Size` value cannot overflow `varchar(500)`), so this is a specification gap rather than a data bug — but it should be pinned explicitly.

### LOW

**L1 — `BookingCompletedEvent` constructor arity is off by one throughout.** The existing constructor takes **10** parameters (`Object source` + 9 fields), not 9. So the story's "new 11-arg constructor" is really **12-arg**, and "keep the existing 9-arg constructor" means the existing **10-arg** one. Every "9-arg"/"11-arg" mention in Task 2.1 and the blast-radius Dev Note is wrong. *The plan itself is sound:* it is a `class extends ApplicationEvent` with Lombok `@Getter`, so a second delegating constructor is legal and idiomatic, the accessors the story uses (`event.getCoachId()`, `getWhatWeDid()`) match, and there is no overload ambiguity. Had it been a record, the story's wording would have been impossible to implement as written.

**L2 — Call-site count is wrong.** `grep -rn "new BookingCompletedEvent(" src/` → **13** total: **3** production + **10** test. The story says 11 (3 + 8). Its own enumerated list contains all 10 test line numbers and every one is correct — only the arithmetic is wrong, in both Task 2.1 and the "touch exactly 3 production files, 0 test files" heading. The plan is unaffected (the overload still makes all 10 zero-change).

**L3 — "The 90-day expiry check" is not a fixed 90 days.** `TimelineQueryService.java:33` reads `configService.getBoundedLong("development.timeline.coachAccessExpiryDays", 1L, 3650L)`; 90 is the seeded value (`V139__baseline_seed_data.sql:79`) and the missing-key fallback (`:35-36`). The story states "the 90-day-since-last-session expiry check" as fact at least three times. **This matters for Task 3.3:** the second-coach IT must seed "non-expired" access robustly against the configured value rather than assuming 90.

**L4 — AC3's "fires synchronously in the same request for `LIVE` mode" is misleading.** The *publish* is in-request and inside the transaction, but delivery is always `AFTER_COMMIT` + `@Async`, so nothing recap-related reaches the timeline in-request. The story's own Task 3.4 states this correctly — AC3 is the inconsistent one.

**L5 — Task 1.1 and Task 1.6 are mutually incompatible.** Task 1.1 says insert the two fields *"after the existing `voiceNoteText` field and before `homeworkDrillIds`/`mode`"* (record positions 6–7). Task 1.6 says update the three unit call sites *"with two new **trailing** string args"*. For a record's positional canonical constructor these cannot both be right. Following Task 1.6 after Task 1.1 yields a **compile error** (arg 6 would be a `List` where a `String` is expected) — so it fails loudly rather than binding wrong values, but it will cost the developer a cycle. Pick one; trailing is the lower-risk choice.

**L6 — The `mvn verify` rule is attributed to the wrong document, and overstated.** The story's Testing Standards says "GitHub CI is this project's **sole** full-verification authority" and the References section credits `project-context.md`. The rule is not in `project-context.md`; it lives in `docs/validation-strategy.md` (L20, L38), which the story never cites. And "sole" is wrong — L25-29 explicitly permits local `mvn verify` on user request, for cross-cutting changes, or as final pre-PR validation.

**L7 — Converge #15 is misquoted.** The story attributes "(what we did / what's next), plus an optional free-text note" to Converge #15, which contains no such parenthetical. The wording is **Priority 2 Next Steps item 1 (brainstorm L217)**. Content real, location wrong.

**L8 — Pattern #4's third component is silently dropped.** Pattern #4 (L74-76) names *three* pieces: "what we did, **what went well**, what's next". The story claims to cover Pattern #4 and ships two. *Largely mitigated:* the brainstorm's own **converged** text (L133, L217) settles on two plus an optional free-text note, and the story's Dev Notes reason this through explicitly, treating the third piece as the pre-existing `voiceNoteText`. So the two-field design is correct; only the unqualified "covers Pattern #4" claim needs a sentence.

**L9 — `voiceNoteText` is described as having "app-level-only requiredness via the request DTO".** It has **no validation annotation at all** (`WrapUpRequest.java:18`) — it is not required at any level. The story's own earlier Dev Notes correctly call it "optional", so this is an internal inconsistency in the Migration-safety paragraph.

**L10 — `confirmCompletion` citation overshoots a method boundary.** Cited `:185-215`; actual `L184-206`. `L208-218` is the unrelated private helper `deserializeHomeworkDrillIds`.

**L11 — de-DE / fr-FR i18n block ends are off by one** (de-DE **382-399**, fr-FR **1491-1508**). Also, Task 5.2's unnumbered "de-DE/fr-FR siblings" hides that the `development.timeline` blocks sit at **de-DE:1098** and **fr-FR:854** — nowhere near the en-US line numbers given. All three locales do have structurally identical key sets, so the task is doable; the pointer is just unhelpful.

**L12 — No recap-correction path exists, and the idempotency guard the flow relies on does not fire where its comment claims.** `SessionCompletionData.id` is `GenerationType.UUID`, an in-VM generator, so `save()` issues no immediate INSERT and the `session_completion_data_booking_id_key` violation surfaces at flush/commit — **outside** the `catch (DataIntegrityViolationException)` at `BookingCompletionService.java:152-158`. That constraint is also absent from `ApiAdvice`'s `CONFLICT_CONSTRAINTS` (`:159-173`), so the caller gets a **400**, not the silent no-op the comment promises. Either way no `BookingCompletedEvent` is re-published. In LIVE mode a second submit is blocked earlier by `verifyStatus` (403); in **QUICK** mode the status stays `COMPLETED_PENDING_CONFIRMATION`, so a retry reaches `save` and fails there. Pre-existing in shape — but this story is the first to put user-authored content on that path, and it adds no edit capability and no AC covering correction.

**L13 — AC4's "the player/parent viewing their own timeline" does not hold for a dual-role user.** `isCoach` is computed purely from the authority list (`PlayerTimelineResource.java:30`), never cross-referenced against ownership, and `User.authorities` is a `Set`. A coach who is also a parent on the platform takes the `isCoach` branch on their own child's timeline, so the expiry gate applies and — after this story — recaps authored by their child's other coaches are stripped, contrary to AC4's promise. Pre-existing in shape (such a user already gets an empty timeline today absent an SLU row); the story's wording simply does not acknowledge the case. Blast radius unknown: the data model permits the combination and nothing forbids it, but no seed data, test, or product doc establishes it as a live state.

**L14 — Vitest runs with `globals: false`.** `vitest.config.mjs` registers no test-runner globals, so Task 6's two new specs must explicitly import `describe`/`it`/`expect`/`vi`. Not mentioned in Task 6. (Spec *discovery* is fine — `include` covers `src/**/__tests__/**/*.{js,mjs}`, so the brand-new `components/booking/__tests__/` directory is picked up automatically.)

---

## 6. What did not survive re-verification

Nine candidate findings were killed or downgraded in Step 4b. Four were raised by the layers; five were raised by me during independent verification. Listing them is the point of this section — a report with uniformly high confidence and no calibration differences is itself evidence this pass did not happen.

**K1 — "A fifth `t('common.next')` at `WrapUpSequence.vue:174` is left behind." (Layers 1 and 4) — KILLED.** The story's citation reads "reusing the same key already used by **Steps 1 and 2** at lines 30, 59, 84, 112". `:30` is Step 1; `:59/:84/:112` are Step 2 (block `37-119`). The fifth use at `:174` is inside **Step 3** (block `120-177`), which Task 4.1 does not touch. The citation is accurate as written and nothing is left behind. Both layers flagged this; both were wrong.

**K2 — "A duplicate QUICK-mode `submitWrapUp` silently returns 204 and discards the recap." (mine) — CORRECTED, then folded into L12.** My initial reading assumed the `catch (DataIntegrityViolationException)` fired at `save()`. It does not: `GenerationType.UUID` defers the INSERT past the `try`. The outcome is a commit-time 400, not a silent success. The underlying concern (no correction path, no re-publish) survives as L12 with the mechanism stated correctly.

**K3 — "Layer 4: two existing ITs break on NULL metadata." — DOWNGRADED to one.** Layer 4 named `PlayerTimelineResourceIT:191` as a coach test. It is not: `:191` sits in `getTimeline_asParent_returns200WithFullTimeline`, which takes the `!isCoach` short-circuit. Only `:141` (in the coach test at `:133`) breaks. H1 reports one.

**K4 — "`Map.of()`'s 10-pair ceiling will break Task 1.5's IT request bodies." (mine) — KILLED.** Both bodies carry 5 pairs (`SessionCompletionResourceIT:196-202`, `:220-226`); adding two lands at 7, well under the limit.

**K5 — "Stale recap text leaks into the next wrap-up because the new refs are never reset." (mine) — KILLED.** `WrapUpSequence` is mounted under `v-if="showWrapUp"` (`CoachCommandCenterPage.vue:15`), so the component is destroyed and every `<script setup>` ref is re-created on each reopen. No reset needed. Relatedly verified: no back/previous mechanism, no `maxStep` guard, no `watch` on `step`, and nothing treats `step === 4` as "last" except the button being replaced — so the `v-if`/`v-else-if` chain accepts `step === 5` cleanly and the pip loop is correct at 5.

**K6 — "An admin/staff caller falls into `!isCoach` and sees every coach's private recap." (mine) — KILLED.** No admin path exists: `@PreAuthorize` admits only `ROLE_COACH` or the ownership guard, there is **no `RoleHierarchy` bean** in `src/main`, and no impersonation/support-view feature. An admin is 403'd before reaching `getTimeline`. Decision #8 holds. *(Worth a code comment in the new helper: if a future story grants admins read access, that branch silently becomes a leak.)*

**K7 — "In-place mutation of the entity's metadata map triggers a stray UPDATE / data loss." — DOWNGRADED to non-issue.** `TimelineQueryService` is class-level `@Transactional(readOnly = true)` (`:21`) and `metadata` is `@Column(..., updatable = false)` (`:48`), so even a developer who ignores Task 3.2's "defensive copy" instruction cannot persist a stripped map. The instruction is still correct practice; the risk behind it is not live.

**K8 — "Task 6's new specs won't be verified because frontend tests aren't in CI." (mine) — DOWNGRADED to advisory.** `.github/workflows/frontend-unit-tests.yml` auto-triggers on any PR whose diff touches `src/frontend/**`, so the two specs **will** run. They are explicitly **not** a required status check, so a red suite does not block merge — but the story never claims otherwise, and its only CI claim (the mandatory Prettier gate) is accurate. Advisory, not a finding.

**K9 — "Duplicate `BookingCompletedEvent` could create duplicate recap rows." — KILLED.** The confirm/timeout race is state-machine guarded: `findPendingQuickCompletes` requires `status = COMPLETED_PENDING_CONFIRMATION`, `confirmCompletion` requires the same and both go through `bookingService.transition(..., COMPLETE, ...)`; the timeout job catches `BookingStateTransitionException | OptimisticLockingFailureException` and skips. Multi-instance duplication is covered by real ShedLock (`@SchedulerLock(name=…, lockAtMostFor="PT15M", lockAtLeastFor="PT2M")`). *Residual, not a finding:* the timeline write itself has no unique constraint and no existence check (unlike `SluCalculationService.onBookingCompleted:79-84`, which does guard) — so if a future path ever double-publishes, the duplicate becomes user-visible now that the row carries real text. Worth a note, not a change.

---

## 7. Also checked and found correct

Recording these because they are the places a reviewer would most expect a defect, and the story got them right:

- **No other caller of `POST /api/bookings/{id}/complete` anywhere.** Full sweep across `.java/.ts/.js/.vue/.yaml/.yml/.json/.sh/.sql/.py/.http`: production is the single centralized `booking.api.js:57`, reached only from `booking.store.js:516`, reached only from `WrapUpSequence.vue:340`. Tests are the two ITs. `new WrapUpRequest(...)` is exactly the three unit sites. **No** Playwright/e2e suite, **no** OpenAPI/springdoc/swagger artifact, **no** Postman collection, no seed script, no admin tool, no mobile client. The story's "2 ITs + 3 unit sites + 1 component" is exactly right.
- **No non-coach-typed wrap-up flow exists** that AC1's `@NotBlank` would start 400'ing — no force-complete, no auto-complete, no retry job builds a `WrapUpRequest`. `confirmCompletion` and the timeout job only *read* the row.
- **GDPR erasure already covers the new free text** — `GdprErasureService:1153` deletes timeline rows by `playerId`, so no new erasure gap.
- **No XSS exposure.** `PlayerTimelinePanel.vue` uses only `{{ }}` interpolation; 500 chars of free text in a `q-timeline-entry` default slot is auto-escaped. Task 5.1 needs no `v-html`. `event.metadata` arrives camelCase as a parsed object (no naming strategy in play; `development.store.js:199-210` assigns the response with zero transformation).
- **Reachability is unchanged.** `PlayerTimelinePanel` is mounted only at `ParentDevelopmentPortalPage.vue:97` and `PlayerDevelopmentDashboardPage.vue:115`, routed `meta.role: 'PARENT'` / `'PLAYER'` (`routes.js:140-144`, `:278-282`). No coach or admin route renders either page — so the story's "zero new routes" claim holds, and AC4's redaction is an API-level fix with no frontend surface today.
- **i18n is safe to edit as planned.** `common.next` exists in all three locales (`en-US:669`, `de-DE:7`, `fr-FR:671`); `step4Done` appears in exactly four places — `WrapUpSequence.vue:224` plus the three locale entries — so removing it breaks nothing. `development.timeline` exists in all three with identical key sets.
- **Test infrastructure is ready.** Awaitility 4.3.0 is `test`-scoped with in-module precedent. No Jacoco coverage threshold in `pom.xml`. `IntegrationTestConventionTest` constrains only context forking / `@TestPropertySource` counts — extending two existing IT classes trips neither.
- **AC→task mapping is complete in both directions.** AC1→T1.1/1.5, AC2→T1.2/1.3/1.4, AC3→T2.1/2.2/3.1/3.4, AC4→T3.2/3.3, AC5→T4.1/4.2/6.1, AC6→T5.1/6.2, AC7→T4.3/5.2, AC8→T1.5; AC9 is a negative AC with no task by design. No orphan tasks. The coverage *gaps inside* that mapping are Findings M2 and H2.

---

## 8. Recommendation

**Per-finding confidence**, rather than a blanket score:

| Finding | Confidence | Basis |
|---|---|---|
| H1 (NULL-metadata NPE) | **Very high** | Independently verified twice; exact breaking test identified by reading all three seeding call sites and their auth context. Layer 4's count corrected. |
| H2 (unsurfaced 400) | **Very high** | Full chain read end to end by hand — component, store, and all interceptor branches. The "first reachable validation failure" framing verified field-by-field, including `toggleDrill`'s cap. |
| M1 (brainstorm contradiction) | **High on the facts; it is a product call, not a bug** | All four brainstorm passages read verbatim in the working tree. The elided clause is unambiguous. Whether to follow the brainstorm or depart from it is the owner's decision — I am flagging that a decision is being made silently, not asserting AC4 is wrong. |
| M2 (untested persistence + deferred paths) | **High** | Derived by reading each planned task against the code it targets; the LIVE-publish-reads-`req` detail is the load-bearing step and was verified directly. |
| M3 (silent async loss) | **High** on mechanism; **moderate** on likelihood | The swallow-and-log is verified verbatim and the absent retry infra confirmed against siblings. Trigger frequency is environmental. |
| M4 (V157/V158 precedent) | **Very high** | Both migrations read in full, twice. |
| M5 (unspecified column type) | **High** | Specification gap, verified; functionally safe either way, so low blast radius. |
| L1, L2, L10, L11 (counts and ranges) | **Very high** | Mechanical, re-counted by hand. |
| L3, L4, L7, L8, L9 | **High** | Each traced to the exact line. |
| L5 (Task 1.1 vs 1.6) | **High** | Record semantics; fails at compile time, so self-limiting. |
| L6 (`mvn verify` attribution) | **High** | Confirmed absent from the cited doc and present in another. |
| L12 (no correction path) | **Moderate-high** on mechanism | The deferred-INSERT reasoning is sound and `CONFLICT_CONSTRAINTS` was checked, but the precise flush point was reasoned about rather than observed at runtime; two layers disagreed on it, which is why it is LOW and why the finding is framed around the missing capability rather than the exact status code. |
| L13 (dual-role user) | **High** on mechanism; **unknown** blast radius | Code path verified; whether the role combination occurs in practice could not be established from the repo. |
| L14 (`globals: false`) | **Very high** | Read from config. |

**What was independently re-checked by me, not just by a layer:** the `BookingCompletedEvent` type/arity; the full `submitWrapUp` / `confirmCompletion` / `QuickCompleteTimeoutService` bodies; `TimelineEventListener`; `TimelineQueryService`; `PlayerTimelineResource` and its `@PreAuthorize`; `PlayerTimelineEvent.metadata`'s type and `updatable=false`; the `session_completion_data` DDL and unique constraint; V157/V158 in full; the coachId ID-space chain; all four brainstorm passages behind M1; the Pattern-#4-vs-converged-text question; every `new BookingCompletedEvent(` call site (re-counted after Layer 1 contradicted itself); the three timeline-seeding call sites and their auth context; the entire 400-surfacing chain; `toggleDrill`; `insertTimelineEvent`; the i18n key sweep; the vitest config; and `GdprErasureService`.

**What I did not independently re-verify** and am relaying on a single layer's evidence: the `ApiAdvice` 403/5xx branch details beyond the 400 gap; the `SluPersistenceRetrier` comparison in M3; the exhaustive "no Playwright/OpenAPI/Postman" sweep in Section 7; and the `CoachCommandCenterPage` entry-path line numbers (`:549`, `:576`, `:590-594`). None of these carries a finding on its own.

**Suggested disposition before dev starts:**

1. **Resolve M1 with the owner** — it is the only item that changes what gets built.
2. **Patch H1 and H2 into the story** — add `metadata != null` to Task 3.2's guard and an AC8-style regression note naming `PlayerTimelineResourceIT:133`; add `maxlength`/`counter` to Task 4.1's inputs and either a `catch` in `handleSubmitWrapUp` or a `:rules` gate.
3. **Add the missing coverage from M2** — one assertion reading the two columns back out of `session_completion_data`, and one test through a deferred publisher (`confirmCompletion` is the cheaper of the two).
4. **Correct the counting and attribution errors** (L1, L2, L3, L5, L6, L7, L10, L11, M4) — cheap edits that prevent the errors propagating into the next story.
5. Pin the column type (M5), and note the silent-failure mode in AC3 (M3).

The story does **not** need redesign. Items 2–5 are edits to the story file; item 1 is a question for the owner.
