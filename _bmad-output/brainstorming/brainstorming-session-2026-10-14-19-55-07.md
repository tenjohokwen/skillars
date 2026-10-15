---
stepsCompleted: [1, 2, 3, 4]
session_active: false
workflow_completed: true
inputDocuments: []
session_topic: 'Coach-to-player relationship management in Skillars: giving a coach a way to see which players he has trained, their session/interaction history, and what they worked on, across players who may train with him intermittently or concurrently with other coaches'
session_goals: 'Generate elegant, product-shaped concepts (not implementation detail) for how a coach manages "his" players over time -- including how similar apps (sports coaching, personal training, tutoring, CRM-style tools) solve this -- covering the roster/relationship model, the history/timeline view, and how multi-coach / lapsed-relationship realities are handled'
selected_approach: 'ai-recommended'
techniques_used: ['Analogical Thinking', 'Role Playing', 'SCAMPER Method']
ideas_generated: ['People-First Roster', 'Dormant/Active Signal', 'Archive Don''t Delete', 'Structured Micro-Recap', 'Feed-Forward Planning', 'Running Problem/Focus Thread', 'Auto-Drafted History', 'Siloed Per-Coach History', 'Session Prep Card', 'Codebase Grounding: existing timeline/neglected-skill/drill-suggestion/coach-contribution infra', 'Derived Lightweight Roster', 'Roster-Summary Endpoint', 'Reused PlayerTimelinePanel', 'Neglected-Skill Roster Sort', 'Computed Lapsed State', 'Cross-Coach Contribution Visible to Coach', 'Attention Score', 'Derived Focus Thread from developmentFocus', 'Session-Close Recap Capture', 'Recap Feeds SESSION_COMPLETED Metadata', 'Recap Feeds Report Generation', 'Eliminated Build Surface', 'Reversed Player/Parent-Initiated Flag (deferred)']
context_file: ''
---

# Brainstorming Session Results

**Facilitator:** Mbah
**Date:** 2026-10-14

## Session Overview

**Topic:** Coach-to-player relationship management -- a coach currently has no way to see which players he has a training history with, when he last trained a given player, or what they worked on. Players may train with a coach for a stretch and stop, or train with multiple coaches concurrently.

**Goals:** Surface elegant, concrete product concepts for: (1) a "my players" roster/relationship model for a coach, (2) a per-player interaction/session history & timeline, (3) handling of lapsed and concurrent multi-coach relationships -- informed by how comparable apps (coaching platforms, personal training apps, tutoring marketplaces, CRMs) solve this.

### Context Guidance

_No separate context file was provided. Session context comes from the Skillars domain itself: the app already models Coaches, PlayerProfiles, and Bookings (a booking links a coach, a player, a time slot, and -- per earlier epics -- exercises/session content). Booking.playerId resolves to a PlayerProfile, not a User, and a PlayerProfile can have its own userId/parentId. There is currently no "coach roster" or player-history surface in the product._

### Session Setup

Topic and goals were provided directly by the user at session start (no live discovery Q&A needed); the facilitator will confirm the framing above before selecting a brainstorming approach.

## Technique Selection

**Approach:** AI-Recommended Techniques
**Analysis Context:** Coach-to-player relationship/history management, with focus on an elegant roster + timeline model that survives lapsed relationships and concurrent multi-coach training, informed by comparable apps.

**Recommended Techniques:**

- **Analogical Thinking (creative):** This is explicitly a "how do similar apps solve this" problem -- personal training apps (Trainerize, TrueCoach), tutoring marketplaces (Wyzant, Preply), music-lesson platforms, CRMs (pipeline/contact history), even medical EHR "patient chart" models all solve "a professional has an evolving, sometimes-lapsed, sometimes-shared relationship with a client and needs a history view." Pulling patterns from those domains first gives us a rich vocabulary before we invent from scratch.
- **Role Playing (collaborative):** Builds on the analogies by stress-testing them against Skillars' actual actors -- the coach, the player, the parent (who may be the account holder for a minor player), and even the "other coach" the player is also training with. Each has different needs from a roster/history feature, and this phase surfaces ideas the analogies alone wouldn't.
- **SCAMPER Method (structured):** Converges the raw ideas into concrete, shippable concepts by systematically running Substitute/Combine/Adapt/Modify/Put-to-other-uses/Eliminate/Reverse against Skillars' existing Booking/PlayerProfile/Coach concepts -- turning "what other apps do" + "what each stakeholder needs" into specific feature shapes.

**AI Rationale:** The topic is concrete and product-shaped (not abstract/emotional), the user explicitly asked to look at comparable apps, and the core tension (relationship continuity across lapses and multiple concurrent coaches) benefits from seeing it through several stakeholder lenses before locking a design. Analogical -> Role Playing -> SCAMPER moves from external inspiration, to internal stakeholder validation, to systematic convergence on buildable concepts -- ending the session with action-oriented ideas rather than just a long list.

## Technique Execution Results

### Analogical Thinking

**Interactive Focus:** Pulled patterns from personal-training apps (Trainerize/TrueCoach), tutoring marketplaces (Wyzant/Preply), the EHR/patient-chart model, CRM contact timelines, and gym/studio multi-trainer platforms -- mapped each onto Skillars' existing Coach/PlayerProfile/Booking model.

**Key Breakthroughs:**
- Confirmed via the user that per-session exercises already exist on bookings today -- so the real gap is an **aggregation lens** over existing data plus **qualitative continuity** (notes/progress threads), not new data capture from scratch.
- Surfaced the central fork for multi-coach handling -- siloed-per-coach (gym-trainer pattern) vs. shared-global (EHR pattern) -- and got an explicit decision: **siloed, Option A**. Coach A never sees Coach B's notes on the same player.

**Ideas Generated:**

> **Covered by `skillars-12-1-coach-player-roster-and-attention-summary`** (created 2026-10-14). Pattern #1, Pattern #2, Pattern #3, Converge #10, Converge #11, and Converge #13 below are implemented by that story. Do not re-pick them in a future brainstorm-mining pass.

> **Covered by `skillars-12-2-session-close-recap-capture`** (created 2026-10-15). Pattern #4 below is implemented by that story. Do not re-pick it in a future brainstorm-mining pass.

**[Pattern #1]: People-First Roster**
_Concept:_ Coach's home view is a list of players (derived from completed bookings), not a list of bookings -- each with a last-session date.
_Novelty:_ Inverts the existing booking-centric UI into a relationship-centric one without new entities.

**[Pattern #2]: Dormant/Active Signal**
_Concept:_ Surface "last trained X days ago" per player so lapsed relationships are visible at a glance.
_Novelty:_ Cheap to compute from existing data; turns silence into a signal instead of an absence.

**[Pattern #3]: Archive, Don't Delete**
_Concept:_ A lapsed coach-player relationship isn't deleted -- it just stops being "active," and history stays intact if they resume.
_Novelty:_ Treats "stopped training" as a state, not a data-loss event.

**[Pattern #4]: Structured Micro-Recap**
_Concept:_ A tiny structured note (not freeform essay) per session: what we did, what went well, what's next.
_Novelty:_ Low-friction enough coaches will actually fill it in, unlike open text fields.

**[Pattern #5]: Feed-Forward Planning**
_Concept:_ The "what's next" from the last recap pre-populates/suggests the next session's focus.
_Novelty:_ History stops being a passive log and starts actively shaping future sessions.

**[Pattern #6]: Running Problem/Focus Thread**
_Concept:_ A persistent, cross-session thread (e.g. "building weak-foot confidence") that multiple session notes attach to, distinct from any single session's note.
_Novelty:_ Separates long-arc development goals from session-by-session noise.

**[Pattern #7]: Auto-Drafted History**
_Concept:_ Since exercises are already attached to bookings when a coach builds a session in-app, the per-player timeline can be auto-assembled from existing booking data rather than requiring new manual entry.
_Novelty:_ Reframes this as a "new lens" feature, not a "new data capture" feature -- much cheaper to build.

**[Decision #8]: Siloed Per-Coach History**
_Concept:_ A player's history is private to each coach -- Coach A never sees Coach B's notes on the same player, even though both see the same player on their own roster.
_Novelty:_ Avoids consent/sharing complexity in a marketplace of independent coaches; matches gym-trainer-silo pattern over EHR-shared-record pattern.

**User Creative Strengths:** Decisive on the hard product-fork question (siloed vs. shared history) and quick to identify that the real gap is content, not recency.
**Energy Level:** Strong, focused engagement -- ready to move into stakeholder stress-testing.

### Role Playing

> **Covered by `skillars-12-3-session-prep-card-and-timeline-access-clock-fix`** (created 2026-10-15). Pattern #9 below is implemented by that story, bundled with the SLU-clock-proxy fix from `deferred-work.md`. Do not re-pick it in a future brainstorm-mining pass.

**Interactive Focus:** Walked through the coach's day-to-day moment of truth, then the player/parent's motivation, then paused to ground the session against the real Skillars codebase (`development` module) before continuing.

**Key Breakthroughs:**
- The coach's single highest-value moment isn't a roster screen -- it's **prepping for a specific upcoming session with a specific player**. The roster is the index; the payoff is inside the booking.
- The player/parent's need is **progress reassurance** ("are we actually improving?") plus visibility into **neglected skills** -- and critically, the user pointed out this already exists as a player-facing development dashboard today.
- That pointer triggered a codebase deep-dive (see Codebase Grounding below), which substantially reframes Phase 3: most of the data model this session was about to invent already exists; the real gap is almost entirely a missing coach-facing surface.

**Ideas Generated:**

**[Pattern #9]: Session Prep Card**
_Concept:_ Inside an upcoming booking's detail view, auto-surface: last session date + what was covered, the open "what's next" from the last recap, the running focus thread, and a quick note field for this session's plan.
_Novelty:_ History finds the coach at the moment he needs it (pre-session), instead of requiring him to go hunting through a separate roster/history screen.

**Codebase Grounding (ran mid-Role-Playing, informs all of Phase 3):**
- `PlayerTimelineEvent` + `TimelineQueryService`: a generic, append-only per-player timeline already exists (SESSION_COMPLETED, RADAR_ASSESSMENT, PERFORMANCE_REPORT now; MILESTONE_REACHED, HOMEWORK_ASSIGNED, VIDEO_UPLOADED, PAYMENT_RECEIVED, REVIEW_LEFT stubbed for later stories). This is Pattern #7 (auto-drafted history), already built -- not hypothetical.
- Coach access to a player's timeline already expires after N days (default 90, configurable) since that coach's **last session with that specific player** -- the lapsed-relationship problem already has a real, working mechanism, just no UI consuming it for coaches.
- `NeglectedSkillDetectionService` (weekly batch) flags skills a player hasn't exercised recently; `DrillSuggestionService` already scores session-builder drill suggestions higher if they address a neglected skill, and `DrillCard.vue` already shows a "neglected skill" badge during session building.
- `CoachContributionDto` / `/slu/coach-contributions` already exposes a **cross-coach**, per-skill percentage-contribution breakdown for a player -- this is in tension with the earlier Decision #8 (siloed-per-coach history) and needs reconciling: raw session notes can stay siloed while aggregate skill-development numbers are already, by design, shared across coaches.
- **The actual gap is almost entirely frontend.** `PlayerTimelinePanel.vue` and the exposure/narrative/neglected-skill/coach-contribution endpoints are consumed only by the Player and Parent dashboards. No coach-facing page (Command Center, Session Builder, Drill Library, Revenue, Reviews, etc.) surfaces any of it, and there is no "my players" roster anywhere in the app today.
- Side observation (not in scope for this session): `/exposure`, `/narrative`, `/neglected-skills`, `/coach-contributions` are gated only by `hasRole('ROLE_COACH')`, with no check that *this* coach has ever trained *that* player -- unlike the timeline endpoint, which does enforce the relationship via the 90-day rule. Worth a follow-up look outside this brainstorm. **[CORRECTED by `skillars-12-4-coach-facing-player-detail-page` (created 2026-10-15): re-verified against HEAD — this list is only half right.** `SluDashboardService.requireCoachPlayerRelationshipIfCoach` already gated `/exposure` and `/narrative` (and `/slu/skill-trends`, not named here) at the time of this brainstorm; only `/neglected-skills` and `/slu/coach-contributions` genuinely lacked the check. That story closes the gap for those two.]**

**User Creative Strengths:** Pulled the session back to ground truth at exactly the right moment -- recognizing that "the player already has this" was a signal the brainstorm was about to reinvent something, rather than letting the facilitator keep speculating.
**Energy Level:** High -- the grounding sharpened rather than deflated the session; moving into SCAMPER with a much more concrete convergence target.

### SCAMPER Method

**Interactive Focus:** Ran all seven SCAMPER lenses against the grounded reality from Phase 2 -- assembling a coach roster/history feature largely from existing `development` module infrastructure rather than inventing new data capture.

**Key Breakthroughs:**
- The roster can be entirely **derived** (no new table) from the existing coach-player booking relationship query, closing the loop with `CoachPlayerAuthorizationService`.
- "Archived"/lapsed is a **computed predicate**, not a stored state -- eliminating a whole category of data-migration/sync concerns.
- `Session.developmentFocus` (already captured per session for drill suggestion) can double as the Pattern #6 "focus thread" with zero new fields.
- Decision #8 (siloed history) is refined, not reversed: **raw session notes stay private per coach; aggregate skill-contribution percentages stay cross-coach visible** (confirmed explicitly for the coach view too, not just parent/player).
- The one genuine new data-capture need identified across the whole session: a **session-close recap** (what we did / what's next), which the user confirmed should be written at session close and read back both in next-session prep *and* in the roster/history drill-down.
- That recap should populate `PlayerTimelineEvent`'s existing (currently-unused-for-this) `SESSION_COMPLETED.metadata` JSONB field rather than needing a new event type or table.
- Second use for the recap, chosen explicitly over a parent-visible summary for now: feeding the existing `ReportGenerationService`/`PerformanceReportResource` pipeline -- turning session-by-session recaps into material for the reports that already get generated.
- A deliberately provocative Reverse-lens idea -- letting the player/parent flag something *into* the coach's prep flow -- was kept on the list but explicitly tagged **deferred scope** (adjacent to the already-deferred homework/messaging stories), not part of the near-term build.

**Ideas Generated:**

> **Converge #10, Converge #11, and Converge #13 are covered by `skillars-12-1-coach-player-roster-and-attention-summary`** (created 2026-10-14) — see the annotation under Pattern #1 above. **Converge #15 (the capture itself) and the write-side half of Converge #16 (threading the recap onto `BookingCompletedEvent`) are covered by `skillars-12-2-session-close-recap-capture`** (created 2026-10-15) — see the annotation under Converge #15 below; Converge #16's report-generation-consumption half is explicitly that story's own Fast-Follow and remains open. **Converge #12 is covered by `skillars-12-4-coach-facing-player-detail-page`** (created 2026-10-15) — see the annotation under Converge #12 below. Converge #14 and Deferred #17 are NOT covered by any story yet and remain open for future Epic 12 stories.

**[Converge #10]: Derived Lightweight Roster**
_Concept:_ "My players" = distinct players with an active-status booking against this coach, no new entity.
_Novelty:_ Zero new persistence; reuses the exact relationship predicate other coach-facing features already enforce.

**[Converge #11]: Roster-Summary Endpoint**
_Concept:_ One new, dedicated endpoint returns thin per-player signals (last session, neglected-skill count, attention score) for the whole roster in one call; existing single-player endpoints stay untouched for drill-down.
_Novelty:_ Solves the N+1 problem without duplicating any existing single-player logic.

> **Covered by `skillars-12-4-coach-facing-player-detail-page`** (created 2026-10-15). Converge #12 below is implemented by that story, bundled with the pre-existing `/neglected-skills` + `/slu/coach-contributions` relationship-check-gap fix from `deferred-work.md`. Do not re-pick it in a future brainstorm-mining pass.

**[Converge #12]: Reused Player Panels on a New Coach Page**
_Concept:_ A new coach-facing player-detail page assembles `PlayerTimelinePanel.vue` (unchanged), a neglected-skills panel, and the coach-contribution breakdown -- all existing endpoints, new page only.
_Novelty:_ The "new feature" is almost entirely composition, not new backend work.

**[Converge #13]: Attention Score**
_Concept:_ Roster rows rank by a composite of days-since-last-session, open neglected-skill count, and `SkillTrendDirection` (declining skills surface sooner) -- one sortable signal instead of three raw numbers.
_Novelty:_ Magnifies existing signals into a single prioritization cue rather than adding new tracked metrics.

**[Converge #14]: Derived Focus Thread**
_Concept:_ The "what have we been working on" thread is computed from the frequency/recency of `Session.developmentFocus` codes across past sessions, not a new freeform field.
_Novelty:_ Reuses data already captured for drill suggestion; no new capture burden on the coach.

> **Covered by `skillars-12-2-session-close-recap-capture`** (created 2026-10-15). Converge #15 (the capture itself) and the write-side half of Converge #16 (threading the recap onto `BookingCompletedEvent` into `PlayerTimelineEvent.SESSION_COMPLETED` metadata) below are implemented by that story; Converge #16's report-generation-consumption half remains open as that story's own Fast-Follow. Do not re-pick Converge #15 in a future brainstorm-mining pass.

**[Converge #15]: Session-Close Recap**
_Concept:_ A small structured note captured when a coach closes out a session, surfaced both forward (next session's prep) and backward (this player's history view).
_Novelty:_ The one deliberately-added new piece of manual data capture in the whole design -- justified because nothing else in the system currently captures "what actually happened" in the coach's own words.

**[Converge #16]: Recap Feeds Report Generation**
_Concept:_ Session-close recaps accumulate as raw material consumed by the existing performance-report generator.
_Novelty:_ One new small capture step pays for itself twice -- prep today, polished reports later -- without a second data-entry burden.

**[Deferred #17]: Player/Parent-Initiated Flag**
_Concept:_ Player or parent can flag a request ("work on X next time") that surfaces on the coach's Session Prep Card.
_Novelty:_ Reverses the single-direction authorship assumption running through every other idea; intentionally parked as deferred scope, adjacent to the already-deferred homework/messaging stories.

**User Creative Strengths:** Fast, confident convergence -- answered every SCAMPER fork decisively and caught the scope-creep risk in the Reverse idea himself, asking to keep it but flag it rather than either dropping it or quietly expanding scope.
**Energy Level:** High and focused through to convergence -- ready to organize.

## Idea Organization and Prioritization

**Thematic Organization:**

**Theme 1: Derived Roster & Prioritization** -- Derived Lightweight Roster, Roster-Summary Endpoint, Attention Score, Dormant/Active Signal and Archive-Don't-Delete (both resolved as computed predicates, not stored state). Pattern insight: almost entirely a query-shape decision, not a data-model decision.

**Theme 2: Session Prep & Feed-Forward** -- Session Prep Card, Feed-Forward Planning, Derived Focus Thread (reuses `Session.developmentFocus`), tied into the existing `DrillSuggestionService` neglected-skill scoring and `DrillCard.vue` badge. Pattern insight: the theme with the most pre-existing backend support -- mostly a presentation problem.

**Theme 3: History, Continuity & the One New Capture** -- Auto-Drafted History (`PlayerTimelineEvent`/`TimelineQueryService`, including the existing 90-day lapsed-access rule), Session-Close Recap (the one genuinely new manual capture in the design), Recap -> `SESSION_COMPLETED.metadata`, Recap -> Report Generation. Pattern insight: one small new input pays for three different outputs.

**Theme 4: Multi-Coach Data Boundaries** -- Siloed Per-Coach History (raw notes/recaps stay private per coach) and Cross-Coach Contribution Visible to Coach (aggregate skill-contribution % stays shared, confirmed visible to coaches too). Pattern insight: the boundary is qualitative-private vs. quantitative-shared, matching what the system already does today.

**Deferred / Parked:** Player/Parent-Initiated Flag -- reverses authorship direction; kept on the list but explicitly tagged deferred, adjacent to already-deferred homework/messaging stories.

**Breakthrough Concept:** The real breakthrough was discovering mid-session that most of the "roster + history" picture already exists in the `development` module with no coach-facing surface -- turning an invent-a-data-model problem into an assemble-plus-one-small-capture problem.

**Prioritization Results -- Agreed Build Order:**

1. Roster-summary endpoint + derived roster page — **covered by `skillars-12-1-coach-player-roster-and-attention-summary` (created 2026-10-14)**
2. Session-close recap capture — **covered by `skillars-12-2-session-close-recap-capture` (created 2026-10-15)**
3. Session Prep Card inside the booking view — **covered by `skillars-12-3-session-prep-card-and-timeline-access-clock-fix` (created 2026-10-15)**
4. Coach-facing player-detail page (reused panels)
5. Fast-follow: recap -> report-generation tie-in
6. Deferred: player/parent-initiated flag

## Action Planning

**Priority 1: Roster-Summary Endpoint + Derived Roster Page** — ✅ **Covered by `skillars-12-1-coach-player-roster-and-attention-summary` (created 2026-10-14, Epic 12)**
_Why this matters:_ It's the entry point for every other idea in this session -- without it, a coach has no "my players" surface at all today.
_Next steps:_
1. Define the roster-summary query: distinct players with an ACCEPTED/CONFIRMED/COMPLETED/UPCOMING booking against this coach (reuse `CoachPlayerAuthorizationService`'s relationship predicate), with last-session date, open neglected-skill count, and `SkillTrendDirection`.
2. Compute the Attention Score and sort order server-side so the frontend stays dumb.
3. Build the roster page as a new coach-facing route, sorted by attention, with a dormant/active visual cue.
_Resources needed:_ one new endpoint/service in `development` (or a thin composition over `SluDashboardService`/`NeglectedSkillFlagRepository`/`BookingRepository`), one new Vue page + route under `pages/coach/`.
_Success indicator:_ a coach can open one page and see every player he has an active or recently-lapsed relationship with, ranked by who needs attention.

**Priority 2: Session-Close Recap Capture** — ✅ **Covered by `skillars-12-2-session-close-recap-capture` (created 2026-10-15, Epic 12)**
_Why this matters:_ the one genuinely new data point the whole design depends on -- unlocks prep, history, and reports.
_Next steps:_
1. Add a small structured recap form (what we did / what's next, plus an optional free-text note) triggered at session/booking completion.
2. Persist it into the existing `PlayerTimelineEvent.SESSION_COMPLETED` metadata JSONB rather than a new table/event type.
3. Keep it coach-private per Decision #8 (siloed notes).
_Resources needed:_ a small frontend form (likely in the booking-completion flow), a write path into the existing timeline event metadata.
_Success indicator:_ every completed session has a recap attached, visible back to the coach on that player's timeline.

**Priority 3: Session Prep Card** — ✅ **Covered by `skillars-12-3-session-prep-card-and-timeline-access-clock-fix` (created 2026-10-15, Epic 12)**
_Why this matters:_ this is the coach's single highest-value moment, identified directly in Role Playing.
_Next steps:_
1. Surface, inside an upcoming booking's detail view: last recap's "what's next," the derived focus thread, the player's open neglected-skill flags, and days-since-last-session.
2. Reuse the same single-player endpoints the player-detail page uses (Priority 4) -- one data-fetching pattern, two presentation contexts.
_Resources needed:_ a new UI section on the booking detail view; no new backend beyond what Priority 2/4 already expose.
_Success indicator:_ a coach opening Thursday's booking with Alex sees what they worked on last time without navigating anywhere else.

**Priority 4: Coach-Facing Player-Detail Page** — ✅ **Covered by `skillars-12-4-coach-facing-player-detail-page` (created 2026-10-15, Epic 12)**
_Why this matters:_ turns the roster from a list into a real relationship/history view.
_Next steps:_
1. New coach-only route/page assembling `PlayerTimelinePanel.vue` (unchanged), a neglected-skills panel (`/neglected-skills`), and the coach-contribution breakdown (`/coach-contributions`), all existing endpoints.
2. Confirm the cross-coach contribution breakdown renders for the coach view too, per the explicit decision in SCAMPER.
_Resources needed:_ one new Vue page; no new backend.
_Success indicator:_ clicking a player on the roster shows the same richness of history a parent already sees today, from the coach's side.

**Fast-Follow: Recap -> Report Generation**
_Next steps:_ feed accumulated session-close recaps into `ReportGenerationService`/`PerformanceReportResource` as additional narrative material once Priority 2 has real data flowing.

**Deferred: Player/Parent-Initiated Flag**
_Next steps:_ revisit once homework/messaging-adjacent stories are scheduled; not part of this build.

## Session Summary and Insights

**Key Achievements:**
- A concrete, sequenced build plan for coach-facing roster + history management, derived almost entirely from existing `development` module infrastructure
- A resolved, non-hypothetical answer to the multi-coach data-boundary question (private notes, shared aggregates)
- Exactly one new piece of manual data capture identified and justified (the session-close recap), rather than a sprawling new data model
- A flagged, out-of-session security observation (missing coach-player relationship check on several `/development` endpoints) for separate follow-up

**Session Reflections:**
The session's defining moment was the user redirecting the facilitator mid-Role-Playing to go look at the actual codebase, after noticing the brainstorm was about to reinvent a player-facing feature that already existed. That single redirect turned three analogical/role-play patterns from hypothetical into "already built, just not coach-facing," and let SCAMPER converge fast and cleanly rather than speculatively. Every SCAMPER fork got a decisive, confident answer, and the one place scope threatened to creep (the Reverse-lens player-initiated flag) was caught and deliberately parked by the user rather than silently expanded or silently dropped.

**Your Next Steps:**
1. Review this session document
2. Start with Priority 1 (roster-summary endpoint + roster page) -- likely the next story/epic slice
3. Treat the flagged `/development` authorization gap as a separate follow-up, not part of this feature
4. Revisit the deferred player/parent-initiated flag alongside homework/messaging work later
