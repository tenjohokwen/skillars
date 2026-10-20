# Story Review: skillars-deferred-155

**Story:** `skillars-deferred-155-recap-report-tie-in-player-session-request-gdpr-metadata-scrub-and-hardening-sweep`
**Status at review:** `ready-for-dev`
**Audit date:** 2026-10-19
**Real HEAD at review time:** `c71320641ec6a084d5b5dfd6ec1561d61eb80ddb` (`c7132064`) — `feat(development): render coach session recap on player timeline (skillars-12-5) (#280)`

Every citation below was checked against the working tree at **this** HEAD, obtained by running `git rev-parse HEAD` directly — not against any SHA the story claims. For the record, the story's self-described baseline is **accurate**: it claims `c71320641ec6a084d5b5dfd6ec1561d61eb80ddb` and that is the real HEAD. `master` points at the same commit; the working branch is `story/skillars-deferred-155-recap-hardening`, so the story's "branch `master`" is loosely worded but content-identical (`git diff HEAD master` is empty). The stale-commit defect that motivated this skill is **not** present here.

Working tree at review time carried uncommitted edits to `deferred-work.md` (−6 lines) and `brainstorming-session-2026-10-14-19-55-07.md` (+7/−1), plus the untracked story file.

**Scope checked:** 56 code citations, 18 ledger/precedent attributions, 20 mechanistic claims, 8 corner-case areas across all 17 ACs. Four parallel read-only layers, then a mandatory self-performed adversarial re-verification (Step 4b) of every finding.

---

## 1. Verdict summary

The story is unusually well-researched — its hardest technical claim (the attendance-gating trap) is correct and survived deliberate attempts to break it, its GDPR transaction reasoning is sound, and the known `REQUIRES_NEW` self-deadlock trap in this project does **not** apply. It is not a story built on invented citations: nothing was fabricated.

The defects are concentrated in three places: **AC1 contradicts itself**, **AC5 describes code shapes that do not exist**, and **three ACs rest on premises that are false** (AC17's bug, AC14's clean baseline, AC11's "no CSS change").

| Severity | Count | Blocking? |
|---|---|---|
| Blocking (wrong as specified — would ship a bug or not compile) | 6 | Yes |
| Significant (missing flow / false premise / incomplete instruction) | 11 | Should fix before dev |
| Minor (citation drift, cosmetic, narrative) | 12 | Fix opportunistically |

---

## 2. Citation verification (Layer 1 — 56 items)

48 of 56 verified **MATCH**, including every line range in `ReportGenerationService`, `GdprErasureService`, `SessionPrepService`, the migration set, and the docs. Sub-3-line offsets (immaterial, noted for accuracy): sessionCount/totalSlu is `:187-188` not `:186-188`; `clearUser` is `:27` and `isAuthenticated` is `:15`, not `:29`/`:14`.

The 8 that did not match:

| # | Citation | Verdict | Reality |
|---|---|---|---|
| C1 | "`ReportGenerationServiceTest.java` (630 lines… **no corresponding IT exists**)" | **FALSE** | Line count right; `ReportGenerationServiceIT.java` **does exist** (313 lines, `extends BaseStorageIT`, Deferred-77 AC2). |
| C2 | "`notesLabel: 'Notes (optional)'` at `en-US/index.js:827`" as the **`booking.*`** precedent | **WRONG NAMESPACE** | `:827` is `development.radar.notesLabel` (`development:` opens `:803`, `radar:` `:818`; `booking:` doesn't open until `:994`). Real booking analogue: `booking.requests.notes` at **`:1017`**. |
| C3 | "the **six**-argument private `toResponse` at `:1064`" | **WRONG ARITY** | 7 parameters (`b, coachName, playerName, parentName, pendingReschedule, batchId, batchSize`). Both call sites pass 7. |
| C4 | "`TimelineEventListener.java:56`-area" in `development/listener/` | **PATH + LINE DRIFT** | No `listener/` package exists. Real: `development/service/TimelineEventListener.java`, and `coachIdForClock` is at **`:61`**. |
| C5 | "every existing mutate-path (`acceptBooking`, `declineBooking`) is coach-only" | **FALSE** | `createBookingRequest` (`@PostMapping`, `:36-38`) is a mutating endpoint guarded by `HAS_PARENT_OR_PLAYER_ROLE`. Only *post-creation* mutations are coach-only. |
| C6 | AC13: "any existing `SessionPrepCard` spec — check for one; if none exists, this is the first" | **DEAD BRANCH** | Both specs exist: `PlayerTimelinePanelSpec.js` and `SessionPrepCardSpec.js`. Instruction should be "extend both". |
| C7 | AC5's quoted ownership snippet: `callerId` / `"Caller does not own this booking"` | **INEXACT QUOTE** | Real code (`:875-876`): `parentUserId` / `"Parent does not own this booking"`. Copying the snippet silently changes a user-facing message. |
| C8 | "`GdprErasureIT…ForThatCoachOnly` (`:731-761`)" | **RANGE WRONG** | Javadoc `:732-740`, `@Test` `:741`, method `:742`, body runs to **`:795`**. Cited end cuts off mid-fixture. |

---

## 3. Ledger & precedent attribution (Layer 2 — 18 items)

Method: every claim checked against **all three** of the ledger file (working tree *and* `git show HEAD:`), source comments/Javadoc at the cited location, and `git log --grep`/`git blame`. **No fabricated citation was found** — every attributed item genuinely exists in at least one source, most verbatim. The defects are attribution *precision*.

| # | Claim | Sources | Verdict |
|---|---|---|---|
| L1 | "its own story, not a review patch" describes the metadata gap | ledger `:3303`, code `:299-331`, deferred-154 AC2 | **MISATTRIBUTED QUOTE.** Phrase is verbatim, but its full sentence is *"Closing it properly means a coach-side erased-at tombstone + Flyway migration + a `submitWrapUp` guard — its own story, not a review patch"* — i.e. it describes the tombstone work this story explicitly **rejected**. The metadata gap is real but appears one sentence earlier, as one of three enumerated side-gaps. |
| L2 | "the four findings deferred by `skillars-12-5`'s code review" | ledger `:3308-3313` | **CONFIRMED (4 exist, all covered) — but Task 6.4 mis-maps them.** They map to AC11–AC15, not "AC12/13's items" as Task 6.4 says. Following 6.4 literally leaves two ledger bullets open. Also finding ② asks for key-parity **plus** template-key resolution; AC14 scopes the second half out (disclosed), so that bullet can't be fully closed. |
| L3 | "Three older, unrelated items (404 guard, nav flicker, pagination dedup)" | full-file grep | **WRONG THREE WAYS.** Only *two* items, neither older nor unrelated: the 404 guard (`:3263`) and nav flicker (`:3273`) both sit **inside** the `skillars-deferred-154 (2026-10-17)` section the story already mines for AC9. The pagination bug is **not a third item** — it is finding ④ of "the four", at `:3313`, so it is **double-counted**. Correct arithmetic: 4 + 3 = 7 ledger items. |
| L4a | ledger: "the change belongs to both surfaces in one pass" | ledger `:3312` | **CONFIRMED verbatim.** |
| L4b | ledger: "fix both or neither" | zero-match grep in WT **and** HEAD | **PARAPHRASE IN QUOTE MARKS.** Real text (`:3310`): *"fix both surfaces together (`?.trim()` on both guard sites) **or neither**."* Semantically identical; not fabricated. A verifier grepping the quoted phrase gets nothing. |
| L5 | `anonymizeByCoachId` exists, runs in the coach txn, added by deferred-154 **AC2** | code `:337`, comment `:299`, commit `d35d146d`, story AC2 | **FULLY CONFIRMED.** One nit: AC9 says "update the surrounding **Javadoc** (`:299-331`)" — that range is `//` line comments; the method Javadoc is `:260-265`. |
| L6 | "no empty wrapper" = 12-5 **AC4** | 12-5 source | **CONFIRMED verbatim.** |
| L7 | independent-field discipline = 12-5 **AC3** | 12-5 source | **CONFIRMED verbatim.** |
| L8 | `metadata.coachId` out of scope = 12-5 **AC9** | 12-5 source + `TimelineQueryService` | **CONFIRMED verbatim.** |
| L9 | AC6 "matching how `skillars-12-5` added fields to existing structures without reordering" | 12-5 AC7/AC9/AC10 | **NO SUCH PRECEDENT.** 12-5 added **zero** fields to any structure — AC10 makes *"no file under `src/main/java`, `src/main/resources` or `src/test/java` is touched"* a completion condition, and AC9 forbids "a DTO field". Its only additions were i18n keys, placed deliberately **not last** (*"after `accessExpired` and before the `eventType:` sub-object"*). AC6's append-last design is fine on its own merits; the attribution is backwards. |
| L10 | `coachId` surviving redaction is asserted-correct | IT `:326-327` + service + comment | **CONFIRMED.** |
| L11 | the prune deleted exactly `:3205-3210`, a pure tombstone, nothing else | `git diff`, `git show HEAD:`, both cited .vue files | **CONFIRMED.** One hunk, exactly −6 lines, genuinely pure `[CLOSED by deferred-142]` tombstone, **no undisclosed deletion elsewhere**. Citation drift: `OtpPage.vue:162` → `routeForRole` is at **`:160`** (so the claim of having "independently re-read both citations" missed a 2-line drift). |
| L12 | `developmentDataErasedAt` guards a resurrection race, checked by `RadarCompositeCalculationService` | field, both services' comments, git log | **CONFIRMED.** "Resurrection" is the codebase's own word (`ShadowAccountService:158`, `GdprErasureService:727,1069`). The story's "wrong precedent to mirror" reasoning holds. |
| L13 | Epic 12 `12-1`…`12-5` all done | `sprint-status.yaml:376-380` | **CONFIRMED.** |
| L14 | brainstorm citations for Converge #16 / Deferred #17 | file at HEAD + WT + `git diff` | **PARTIALLY WRONG.** Deferred #17: 4/4 exact. Converge #16: 3/4 — cited `:221` is actually Converge **#15**. **All line numbers are now stale against the file a dev will open** (the story's own edit shifted #16 to `:175-177`, #17 to `:181-183`). **Undisclosed third edit:** the diff also rewrote `:141` and inserted a Converge #14 coverage annotation at `:165` — self-documented inside the brainstorm but mentioned nowhere in the story or sprint-status, which disclose only the `deferred-work.md` prune. The #17 precondition claim itself checks out (`4-6`, `8-1`, `8-2`, `8-4` all done). |
| L15 | "`ContactDetailSanitizer`… already used at `CoachProfileService:216`"; playerRequest is "the **first** player/parent-authored free text reaching a coach" | full grep of all call sites + `notes` write path | **FALSE — self-contradiction.** See §5 Finding 7. |
| L16 | `notes` is surfaced to the coach on `CoachBookingRequestsPage.vue` | file `:101-102` | **CONFIRMED.** |
| L17 | `migration-conventions.md` rule 1 = additive-first; V160/V161 `allow-*` establish the posture | doc `:54`, both migrations | **CITATIONS CONFIRMED; INFERENCE MISAPPLIED.** AC9 invokes them to justify *"no new index needed"*, but those markers waive **lock-acquisition** safety, not index necessity. The real justification already exists in-source at `GdprErasureService:319-321`. |
| L18 | "re-verified against real HEAD `c7132064…`, branch `master`" | `git rev-parse` | **SHA EXACT; branch name wrong, harmless** (content-identical). |

---

## 4. Mechanistic claims (Layer 3 — 20 items)

**Confirmed correct (the story's strong core):** M1 (no date-range concept anywhere in the report path — correction accepted), M2 (ID spaces match exactly; `PlayerTimelineEvent.playerId` is `Long` PlayerProfile-PK, `coachId` is `UUID` CoachProfile-PK — the project's known `Booking.playerId` trap explicitly checked and **clear**), M4, M6 (JPQL genuinely cannot filter a JSONB key — 77 native queries in the repo, none does), M9 (`setNotes` has exactly one `Booking` call site; the `RadarAssessmentService:82` same-name trap ruled out), M10, M12, M14, M16, M17, M20.

**M3 — the story's strongest claim, and it holds.** This is the load-bearing premise for both AC1 and AC9, so it was attacked from three angles: whether `coachIdForClock` is a decoy variable, whether recaps are even written for unattended sessions, and whether `@NotBlank` makes the case unreachable. It survived all three.

```java
// TimelineEventListener.java:61  — the gate
UUID coachIdForClock = event.isPlayerAttended() ? event.getCoachId() : null;
// :68 passed through → :113 evt.setCoachId(coachId) — the ONLY setCoachId in the file
// :55  metadata.put(METADATA_KEY_COACH_ID, …) — unconditional, outside every branch
// :56-57 recap keys written on a NULL-check only, NOT an attendance check
```
`WrapUpRequest.java:21-22` marks both recap fields `@NotBlank` **regardless of `playerAttended`**, so "unattended + real recap text" is not an edge case — it is the guaranteed shape of every wrap-up where the coach unchecks attendance. An in-source comment (`:58-60`, skillars-12-3 AC1) confirms the gate is deliberate.

**M15 — GDPR transaction reasoning sound; the project's known trap does not apply.** `requiresNewTemplate` is genuinely `PROPAGATION_REQUIRES_NEW` (`:217-221`); the `catch` is outside the template (`try` `:334`, template `:335-338`, `catch` `:339`), so both statements commit or roll back together — "one unit" is accurate. The memory-flagged *"REQUIRES_NEW self-deadlocks if the caller already wrote the same row"* trap was investigated specifically and is **clear** for three independent reasons: the only `player_timeline_events` write (`:1217 deleteByPlayerId`) is ordered *after* the coach block, is *itself* `REQUIRES_NEW` (`:1190`), and nothing in `:268-352` touches that table. `@Modifying` without `clearAutomatically` is moot (REQUIRES_NEW binds a fresh persistence context; `metadata` is `updatable = false`) and matches the sibling's annotation. SQL is valid: column is genuinely `jsonb` (`V138`), so `-` works; `metadata->>'coachId'` skips NULL rows safely; the UUID→text format matches because `:55` writes `UUID.toString()`.

**The four that did not hold:**

### M7 — AC1 contradicts itself (BLOCKING, highest severity)

AC1's headline: *"**Filter on `metadata.get("coachId")`, not the entity's `coachId` column**… A query against the column would silently exclude recaps from completed-but-unattended sessions."*

AC1, three clauses later: *"fetch by `playerId` + the new method's `coachId` parameter (**bound against the entity column is fine for the *initial* filter**…) and then, in Java, extract… keeping only events where at least one is non-null."*

Traced mechanically: the prescribed method `findByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc` derives `WHERE player_id = ? AND coach_id = ? AND event_type = ?`. For every unattended session `coach_id IS NULL` (M3), and `NULL = ?` is never true — **those rows never leave the database**. The Java step filters only on recap presence; it *narrows* an already-returned set and has no mechanism to recover SQL-excluded rows. It never reads `metadata.get("coachId")` at all, despite that being the AC's headline.

A dev following AC1 to the letter ships **exactly the bug AC1 was written to prevent**, while believing the Dev Notes warning — which calls column-pattern-matching *"the single most likely wrong turn in this story"* — has been honoured. AC3's test list contains no unattended case, so it ships untested.

AC9 solves the identical trap **correctly** (`WHERE metadata->>'coachId' = …`). AC1 and AC9 are internally inconsistent about the same problem. Fix: use AC9's native-query approach in AC1 too. (Dropping `AndCoachId` and filtering in Java also works but abandons `idx_player_timeline_events_coach_lookup` and re-introduces the unbounded fetch `skillars-deferred-153 AC4` just fixed.)

Aggravating nuance: `V160`'s backfill set `coach_id` from metadata *unconditionally*, so **pre-**V160 unattended rows *do* have the column populated. The dataset is inconsistent and the bug bites only post-V160 rows — which makes it harder to notice in testing.

### M13 — AC5 describes a code shape that does not exist (BLOCKING)

AC5: *"wire it into **both** existing construction sites: the private `toResponse` at `:1067-1083`… and the public `toBookingResponse` at `:478` (read its body first… and add the same field in the matching position)."*

There is exactly **one** `BookingResponse` construction site in the entire repo (main *and* test):
```
BookingService.java:1067:        return new BookingResponse(
```
`toBookingResponse` **delegates** — it constructs nothing (`:478-483`, ending `return toResponse(booking, coachName, playerName, null, null, null, null);`). There is no "matching position" to add anything at. Adding `b.getPlayerRequest()` to `toResponse` alone covers every path automatically. A dev following AC5 literally finds nothing to add, or adds an 8th `toResponse` parameter — a worse change than intended. Compounded by C3 (7 args, not 6).

### M11 — `rejectIfPostCompletion` is far narrower than AC5 claims (SIGNIFICANT)

AC5's intent: *"a request can only be set/edited while the booking has not yet completed."* The helper (`:706-712`) rejects on **one exact string match**:
```java
if (BookingStatus.COMPLETED.name().equals(booking.getStatus())) { throw … }
```
`BookingStatus` has **18** values. This blocks exactly **1**. Writes are permitted on `COMPLETED_PENDING_CONFIRMATION` (session physically over, wrap-up already submitted), `DECLINED`, `CANCELLED`, `CANCELLED_PARENT`, `CANCELLED_COACH`, `NO_SHOW_PLAYER`, `NO_SHOW_COACH`, `DISPUTED`, `REFUND_PENDING`, `REFUNDED`, and `IN_PROGRESS`. AC5's "Do not add a second, bespoke time-based guard" forecloses the fix without acknowledging the gap.

Separately, the helper's message is **wrong for this use**: `"A completed booking cannot be cancelled — raise a dispute instead"` / `BOOKING_ALREADY_COMPLETED` will be shown to a parent who merely tried to edit a text field.

### M5 — "every existing boundary uses the metadata key" is false (SIGNIFICANT)

The named boundary does (`TimelineQueryService:81-82`). But the `coach_id` **column** feeds three live boundaries via `findTopByPlayerIdAndCoachIdAndEventType…`: the coach access-expiry gate (`TimelineQueryService:49-54`), recap sourcing (`SessionPrepService:54-59`), and report generation's own `AccessDeniedException` gate (`ReportGenerationService:158-161`).

This is not cosmetic: **AC1 instructs mirroring `SessionPrepService.java:60-68`, whose recap arrives via the column-filtered query** — the story holds up as precedent a code path that does the exact thing the story warns against. That is the root of M7.

---

## 5. Corner cases, false assumptions & missed flows (Layer 4, post-Step-4b)

### Finding 1 — AC17 fixes a bug that cannot occur (BLOCKING: the AC is invalid)

AC17's premise: the Dashboard nav link *"briefly shows to every role during logout"*. The enclosing element:
```html
MainLayout.vue:109-110    <q-drawer
                            v-if="authStore.isAuthenticated"
```
The Dashboard `q-item` is at `:126-140`, **inside** that drawer. `clearUser()` (`auth.store.js:27-32`) nulls `userId` (`:28`) and `role` (`:29`) in the same synchronous call before any `await` (`logout()` `:49`, then `:51`), so `isAuthenticated` and the role computeds flip in one reactive tick. There is no tick in which a logged-out user renders the item — the whole drawer is gone.

The proposed `authStore.isAuthenticated && (…)` gate is a **no-op**, and no test or manual check can reproduce the stated symptom. The fallback clause's actual live effect — unchanged by AC17 — is "authenticated with a role outside {COACH, PARENT, PLAYER, ADMIN}" (e.g. a malformed `skp` cookie, `:58-60`). If that is worth guarding, the AC should say so; the logout-flicker rationale is wrong.

> **Note on layer disagreement:** Layer 3 independently concluded the fix *works*. It verified the condition logic and the `auth.store.js` timing correctly but never checked the enclosing element. Step 4b resolved this in Layer 4's favour by reading `MainLayout.vue:105-142` directly. This is precisely the failure mode this skill exists to catch.

### Finding 2 — AC1 pushes coach recap text into PDFs that coach erasure never scrubs (BLOCKING: new GDPR hole)

The coach-erasure branch (`:293-350`) scrubs `coach_profiles.{bio,city,district}` + `session_completion_data` + (AC9) the JSONB copy. `performance_reports` is purged **only** in the player path:
```
GdprErasureService.java:1244-1245   performanceReportRepository.findStorageKeysByPlayerId(playerId);
                                    performanceReportRepository.deleteAllByPlayerId(playerId);
```
`deferred-154`'s own AC2 listed **three** gaps left for a future pass: the metadata copy, **`PerformanceReport.nextSteps`**, and `CoachProfile.displayName`. This story closes the first and **enlarges the second without noticing**.

Failure scenario: a coach generates reports for 20 players, each now containing verbatim recap text under "Session Notes". The coach requests Article-17 erasure. AC9 scrubs the JSONB copy; the same text survives indefinitely in 20 downloadable S3 PDFs plus `performance_reports.next_steps`, readable by every parent via `listReports`. AC9's new Javadoc ("two tables being scrubbed") becomes actively misleading about completeness.

### Finding 3 — AC5's endpoint path does not exist on this resource (BLOCKING)

AC5: *"`PATCH /api/booking/bookings/{id}/player-request` endpoint in `BookingResource.java`"*. The real base:
```java
BookingResource.java:29   @RequestMapping("/api/bookings/requests")
```
A `@PatchMapping("/{id}/player-request")` there resolves to `/api/bookings/requests/{id}/player-request`. `/api/booking/…` (singular) appears nowhere in the codebase. Every sibling booking-mutation resource (`CancellationResource:21`, `RescheduleResource:24`, `SessionCompletionResource:35`) is `@RequestMapping("/api/bookings")` with `/{id}/<verb>`. A dev writing the Pinia action against the AC's literal path gets a 404; one writing it against the resource gets a path inconsistent with every sibling. AC5 and AC7 need the path decided explicitly.

### Finding 4 — AC14's parity spec fails on day one (BLOCKING for AC14)

Independently reproduced (not taken from the layer):
```
counts en=1252 de=1251 fr=1251
de missing: [ 'auth.emailVerifiedPleaseLogin' ]
fr missing: [ 'auth.emailVerifiedPleaseLogin' ]
```
`en-US/index.js:25` is live, rendered at `LoginPage.vue:29`. Two consequences: (a) a German or French user who verifies their email **today** sees the raw key string — a real shipped bug; (b) AC14's spec goes red the moment it is written, with no instruction anywhere in the story to fix the divergence. Add "backfill `auth.emailVerifiedPleaseLogin` in de-DE and fr-FR" to AC14. (Locale set verified complete: exactly `en-US`, `fr-FR`, `de-DE`.)

### Finding 5 — AC11's "no new CSS, no behavior change" is false (BLOCKING for AC11)

`<dd>` carries a UA default `margin-inline-start: 40px` and `<dl>` a `margin-block: 1em`. Verified there is **no** reset: zero `dl`/`dt`/`dd` rules in `src/frontend/src/css/` (all six partials), and Quasar's `normalize.sass` has no `dl`/`dd` rule at all.

Failure scenario: every recap value in `PlayerTimelinePanel.vue:57,63` and `SessionPrepCard.vue:25,29` becomes indented 40px relative to its label, and the former `<div class="q-mt-sm">` wrapper gains ~16px above *and* below on top of its 8px — inside a `q-timeline-entry` slot and a `q-card-section`. AC11 budgets no CSS, so this ships as a visual regression.

### Finding 6 — AC12 undercounts the guards: three, not two (SIGNIFICANT)

AC12 says *"both `PlayerTimelinePanel.vue` conditions"*. There are **three** (verified at `:46-65`): the outer OR-wrapper (`:47-50`), plus the inner `whatWeDid` (`:53`) and `whatsNext` (`:59`). Fixing only the two inner ones leaves a whitespace-only value rendering an **empty wrapper** — which, post-AC11, is a `<dl>` carrying `q-mt-sm` *plus* 1em UA margins. That is exactly the "no empty wrapper `div`" rule `skillars-12-5` AC4 established. State "all three".

### Finding 7 — "First player/parent-authored free text reaching a coach" is false, inverting AC5's rationale (SIGNIFICANT)

`ContactDetailSanitizer` has **six** production call sites, three of which already sanitize player/parent-authored input:
```
PlayerRegistrationService.java:95-96    ParentRegistrationService.java:80-81
CoachRegistrationService.java:80-81     ShadowAccountService.java:56,101
CoachProfileService.java:216 (coach's own bio — the one AC5 cites)
SanitizePreviewResource.java:32
```
AC5 cites the single *coach*-authored example as "the existing precedent for **player/parent**-authored free text". Meanwhile `Booking.notes` — player/parent-authored, `varchar(500)`, persisted **unsanitized** (`BookingService:336`; `BookingService` contains no sanitizer at all) — reaches the coach at `CoachBookingRequestsPage.vue:101-102` **and** in their email at `BookingEmailListener.java:72`. AC4's own text says so two sentences earlier.

Consequence: AC5's *"a deliberate consistency choice, not optional hardening"* is **inverted**. Sanitizing `playerRequest` while the adjacent, identically-shaped, same-author, same-audience `notes` column stays unsanitized creates an inconsistency, and the real pre-existing hole in `notes` goes unrecorded. (Sanitizing is still the right call — the justification is wrong.)

> **Step 4b note:** I initially challenged the "six call sites" count with a grep for `contactDetailSanitizer.sanitize` that returned only two. That grep was wrong — those classes name the field `sanitizer`. The layer's count stands.

### Finding 8 — sanitizer output can exceed the 500-char column (SIGNIFICANT)

`REDACTION` is a 25-char string that **replaces** each match (`ContactDetailSanitizer:21,25-27`), so sanitization can grow the input. AC4 prescribes `@Column(length = 500)` and AC5 `@Size(max = 500)`.

Failure scenario: a parent pastes 45 × `"1234567890 "` (495 chars, passes `@Size`) → each 10-digit run matches → ~1125+ chars → insert fails SQLSTATE 22001 → `DataIntegrityViolationException` → opaque **400 `generic.dataError`** on input the UI called valid. The cited precedent is safe only by accident: `coach_profiles.bio` is `text` (`V138:1574`), not varchar; `notes` is `varchar(500)` (`V138:236`) but never sanitized.

### Finding 9 — AC5 omits the project's optimistic-lock pattern → 500 (SIGNIFICANT)

`Booking` carries `@Version` (`:54-56`). `BookingService` wraps **six** mutate paths in `catch (OptimisticLockingFailureException e)` → `BookingError.CONCURRENT_MODIFICATION` (`:396, 461, 674, 825, 862, 899`). AC5 prescribes load → set → save with no such catch, and there is **no global handler** (`ApiAdvice` has none; falls to `@ExceptionHandler(Throwable.class)`). Two tabs saving → **500** instead of the established 4xx.

### Finding 10 — AC9 doubles the lock budget and makes the alert ambiguous (SIGNIFICANT)

The block's own comment (`:306-310`) stresses `set_config('lock_timeout', ?, true)` is **transaction**-scoped, not per-statement. Adding a second statement means worst-case wait 2 × 5s, and a trip on the **new** statement now rolls back the **already-succeeding** `anonymizeByCoachId` that previously committed independently. The two reason codes (`COACH_RECAP_CONTENDED`/`COACH_RECAP_LOCK_TIMEOUT`) can no longer tell an operator which table failed. The daily retry makes it eventually consistent, so this is acceptable — but AC9 asserts "no new transaction boundary… no new alert reason code" while presenting the change as pure benefit. Also: the new `metadata->>'coachId'` predicate **cannot** use `idx_player_timeline_events_coach_lookup` (V161 indexes the *column*), so it is a guaranteed sequential scan — which is why the doubled budget matters.

### Finding 11 — a parented child PLAYER cannot use the page AC7 targets (SIGNIFICANT)

`getParentBookings` reads `findAllByParentIdOrderByRequestedStartTimeAsc(parentId)`, and `parentId` is always the *requesting* party. For a parent-created booking that is the parent's userId. AC7 frames `ParentBookingsPage.vue` as *"the single shared list view for both parent and player roles"* — true, but a logged-in child PLAYER gets an **empty list** and a **403** from the new ownership check on their own parent-created bookings. The player half works only for self-registered players. AC8's matrix encodes the same blind spot. Decide and state it: either the child is intentionally excluded, or the check needs the `PlayerProfile.userId` arm `createBookingRequest:181-189` already has.

### Finding 12 — returning `toBookingResponse` wipes list-view state (SIGNIFICANT)

`toBookingResponse` passes **null** for `parentName`, `pendingReschedule`, `batchId`, `batchSize` (`:482`), whereas `getParentBookings` populates all four. `booking.store.js` has no per-booking update action (`parentBookings` only ever assigned wholesale, `:353`). Failure scenario: a parent with a pending coach reschedule edits their request; the implementer splices the response in; `pendingReschedule` → null, so the banner (`ParentBookingsPage.vue:59-74`) and Accept/Decline buttons (`:78-90`) vanish until a full reload. AC7 says only "refreshes… in the Pinia booking store" with no mechanism.

### Finding 13 — AC2's "concatenated" + AC3's single-field test print literal `"null"` (SIGNIFICANT)

AC1 keeps entries where *at least one* key is non-null; AC3 explicitly requires a test for *"only one of the two fields non-null"*. Java concatenation with a null reference yields `"null"`, and `buildPdf` has no null handling anywhere (`:517-518`). A pre-V159 entry with `whatWeDid` set and `whatsNext` absent renders `"Worked on first touch null"` in a parent-facing PDF. AC2 never says to skip an absent field — and AC3's own new test case is the trigger.

### Finding 14 — AC7 needs i18n keys the story never lists, against a hard lint error (SIGNIFICANT)

Task 2.5 specifies one key. The edit affordance needs a field label, Save, Cancel, and success/error notifications. `eslint.config.js` sets `'vue/no-bare-strings-in-template': ['error', …]`, so any literal fails the lint Task 6.3 requires — and each key must land in all three locales or AC14's own new spec fails.

### Finding 15 — three test classes named in the ACs do not exist (MINOR but will stall dev)

- AC8 names **`BookingResourceIT`** — does not exist. Real siblings: `BookingRequestResourceIT`, `PlayerBookingRequestIT`, `BookingBatchResourceIT`, `SessionCompletionResourceIT`, `RescheduleResourceIT`, `ScheduleResourceIT`, `AvailabilityResourceIT`, `BookingEventResourceIT`, `BookingSseIT`.
- AC8 names **`SessionPrepServiceTest`** — does not exist; only `development/api/SessionPrepResourceIT.java`.
- AC10's `GdprErasureIT:731-761` → see C8.

### Finding 16 — openpdf 2.0.5 + base-14 Helvetica silently drops non-cp1252 characters (MINOR, pre-existing)

`buildPdf` uses `FontFactory.getFont(FontFactory.HELVETICA, 10)` — WinAnsiEncoding. Live proof in the same method: the `" ✓ Verified"` note (`:521-523`) uses U+2713, already silently missing from every PDF generated today. AC2 now routes up to 500 chars × N entries of coach free text through the same font in a product shipping de-DE and fr-FR. "Worked on Łukasz's weak foot" → "Worked on ukasz's weak foot". Worth an explicit acknowledgement in AC2 rather than a surprise.

### Finding 17 — a fourth unguarded player-id navigation, in a file cited as clean precedent (MINOR)

AC16 says the three siblings *"all truthiness-guard the value"*. `BookingRequestPage.vue` guards its submit paths (`:297, 550, 651`) but not:
```js
BookingRequestPage.vue:480-481   function goToPurchase() {
  router.push(`/parent/coaches/${coachId}/purchase-sessions?playerId=${playerId.value}`)
```
`playerId.value` is nullable (`:250`). Milder than AC16's case — a query param, not a path segment, so no 404 — but structurally the same defect in a file held up as already-clean.

### Finding 18 — AC16's fallback can bounce the user back to the form they just submitted (MINOR)

AC16 sends a falsy `profile?.id` to `/player/home`, whose own missing-id handling is `router.replace('/player/profile-builder')` (`:29, :40, :46`). So: submit → `/player/home` → id unresolved → back on the form, no error shown. AC16 calls that route one that "has its own null-handling" without noting the handling is "return where you came from". It also says "mirror `:39-42`'s **exact** guard shape" while prescribing a different destination *and* `push` instead of `replace` (with `push`, Back returns to the dead form). The premise also has no live trigger today — `PlayerProfileResponse` does carry `id` — so this is defence-in-depth, not a reproducible fix.

### Finding 19 — AC11 leaves the two intermediate group wrappers unspecified (MINOR)

Structure is `outer div > [div v-if=whatWeDid > label + value], [div v-if=whatsNext > …]`. AC11 maps outer→`<dl>` and label/value→`<dt>`/`<dd>` but never mentions the two `v-if` **group** wrappers (`:53`, `:59`). Keeping them as `<div>` inside the `<dl>` is valid HTML (`dl > div > dt + dd`); deleting them to "clean up" collapses 12-5 AC3's independent-field guards. No lint will catch either choice (no `eslint-plugin-vuejs-accessibility` in the project). Say which.

### Finding 20 — `:coachId::text` has no precedent in this repo (MINOR)

Zero `:<param>::` hits across `src/main/java`; the established idiom is JPQL `CAST(:type AS text)` (`AdminAlertRepository:32,38`), and no existing native query uses `->>` or a postfix `::` cast. Hibernate 6 does handle it, and the cast is *necessary* (`text = uuid` has no Postgres operator), so this probably works — but it is an untested idiom in a GDPR path. `CAST(:coachId AS text)` matches house style. Confirm in the IT rather than assume. Also minor: no `AND (metadata ? 'whatWeDid' OR metadata ? 'whatsNext')` guard, so every one of the coach's rows gets a no-op rewrite on a statement that is already a seq scan.

### Finding 21 — `playerRequest` gets no GDPR erasure coverage (MINOR, consistent with existing behaviour)

Nothing in `GdprErasureService` scrubs `booking.bookings.notes` (no `notes = NULL`, no `setNotes(null)`, no `UPDATE Booking`; booking rows are never deleted). AC4 adds a second player/parent-authored PII-capable column with the same Article-17 gap. DSAR *export* is automatic (`GdprExportService.buildBookings:117-137` serializes the whole entity). Not a new regression — but an unacknowledged new erasure surface in a story whose own AC9 exists to close exactly this class of gap.

---

## 6. Verified clean (checked, no finding)

Recorded so the report's coverage is auditable:

- **AC2's date formatting is fulfillable** — `buildPdf` already formats one (`:463-467`, `FormatStyle.LONG`/`Locale.ENGLISH`).
- **`buildPdf` signature change breaks nothing** — one caller (`:195`); the 630-line unit test only calls `generateReport`.
- **No N+1 / lazy-load risk on `metadata`** — `@Type(JsonType.class)` `jsonb` on the same row, not an `@ElementCollection`; `generateReport` is `@Transactional` (`:128`).
- **`generateReport`'s authorization is real** — `requireCoachPlayerRelationship` (`:139`) + recency gate (`:177-181`), so a coach who no longer coaches is refused entirely.
- **`Objects.equals(null, null)` auth bypass is unreachable** — `parent_id` is `nullable = false`; `SecurityUtil.requireCurrentUserId` throws rather than returning null. All three `setParentId` sites write a real id; neither batch nor duplication copies `notes`, so `playerRequest` correctly starts null.
- **AC5's dual parent/player `parentId` claim is correct** — `createBookingRequest:181-189` branches but stores the same resolved id either way (independently corroborated by `GdprExportService:128-131`).
- **`SessionPrepService.getPrep` is coach-scoped** (`:48-50`), and `SessionPrepResponse` has exactly one construction site, so AC6's 7th field breaks nothing.
- **No read path NPEs on absent metadata keys** — no `metadata.get(…).toString()` anywhere; post-scrub the DTO carries nulls and both guards hide the block.
- **Player erasure needs no metadata scrub** — `deleteByPlayerId` (`:1217`) removes the rows outright. Nothing earlier in the coach branch deletes them, so AC9's UPDATE is not a no-op. AC10's unattended fixture is constructible.
- **No rate-limit or audit-log inconsistency** — no sibling player/parent booking write is rate-limited or audit-logged.
- **Migration is correct** — V162 is latest, table is `booking.bookings`, `V163__booking_bookings_player_request.sql` is the right name, and `MigrationLint` needs no `allow-*` marker for a plain nullable `ADD COLUMN IF NOT EXISTS` behind `SET LOCAL lock_timeout = '5s'`. V159/V151/V160/V161 style precedents all verified.
- **AC15 does not under-solve** — `:255` is the only append site; `TimelineEventResponse` leads with `id`. For the stated trigger (head insertion) dedup is complete; the skip direction needs a head *deletion*, and the only deleter removes the whole timeline.
- **AC13's selector concern is a non-issue** — `PlayerTimelinePanelSpec.js` uses only `find('button')`/`findAll('button')`, nothing structure-keyed.
- **AC12's "not reachable in production" is correct** — both `WrapUpRequest` fields are `@NotBlank` and publishers pass them as a pair.
- **Task 6.2/6.3 commands are real** — `npm run test:unit` → `vitest run`; CI prettier gate at `.github/actions/frontend-quality/action.yml:36`.
- **`playerTimelineRepository` is already injected** into `GdprErasureService` (`:104`), so AC9's snippet is complete; `coachRecapLockTimeoutSeconds` exists as a **local** (`:332-333`) so the snippet compiles only at the exact insertion point AC9 specifies — which is where AC9 puts it.

---

## 7. What did **not** survive re-verification

This section is the point of the exercise — these were raised by a layer and then killed or downgraded by my own Step 4b pass.

1. **KILLED — Layer 3's M18 conclusion that AC17's fix works.** Layer 3 correctly verified the nav condition and all of `auth.store.js`'s timing, then concluded the `isAuthenticated` gate "eliminates the flicker". It never read the enclosing element. `MainLayout.vue:109-110` already gates the whole drawer on `isAuthenticated`, so there is no flicker to eliminate. Layer 4's Finding #7 is correct and Layer 3's was not. Resolved by reading `MainLayout.vue:105-142` myself.
2. **KILLED — my own challenge to Layer 2's "six sanitizer call sites".** My grep for `contactDetailSanitizer.sanitize` returned two, and I nearly reported the layer as wrong. The registration services name the field `sanitizer`; the layer's count of six was right. Finding 7 stands as the layer reported it.
3. **KILLED — my own `BookingStatus` count of 17.** My `grep -c` missed the comma-less final enum constant. There are 18; Layer 3's figure was right. `rejectIfPostCompletion` blocks 1 of 18.
4. **DOWNGRADED — Layer 2's L3 ("three older, unrelated items").** Confirmed wrong on all three counts, but it is a sourcing/narrative error in the Source section, not something that changes what a dev builds. Moved from "significant" to "minor".
5. **DOWNGRADED — Layer 1's C7 (inexact ownership-check quote).** Real, but the only consequence of copying the snippet verbatim is a changed error-message string. Minor.
6. **DOWNGRADED — Layer 4's #19 (AC16 bounce) and Layer 3's M19 (fourth unguarded site).** Both confirmed, both low-impact: AC16's premise has no live trigger, and `goToPurchase` interpolates into a query param rather than a path segment, so no 404 results. Minor.
7. **NOT UPHELD as a defect — Layer 2's L17 inference complaint.** The observation is right (the `allow-*` markers waive lock-safety, not index necessity), but AC9's actual conclusion ("no new index needed") is still correct for a rare admin-driven path, and the better in-source justification exists at `:319-321`. Recorded as a citation-precision note, not a defect.
8. **CONFIRMED-CORRECT, not a finding — M3.** Listed here because it was attacked hardest and could easily have been reported as a defect. Three independent attempts to break it (decoy variable, recap-not-written-for-unattended, `@NotBlank`-makes-it-unreachable) all failed. The story's hardest claim is sound; the irony is that AC9 acts on it correctly and AC1 does not.

---

## 8. Recommendation

**Per-claim confidence, not a blanket score.**

**HIGH confidence, independently re-verified by me after the layers reported (quoted the deciding lines myself):**
- M7 / AC1 self-contradiction — *the single most important finding; fix before any implementation*
- M13 / AC5's non-existent construction site + 7-arg `toResponse`
- Finding 3 / AC5's endpoint path vs `@RequestMapping("/api/bookings/requests")`
- Finding 1 / AC17 fixes an unreachable bug (resolved against a dissenting layer)
- Finding 4 / AC14 fails day one (reproduced the parity run myself)
- Finding 5 / `<dl>`/`<dd>` UA margins with no reset
- Finding 6 / three guards, not two
- Finding 7 / sanitizer "first" claim false
- Finding 2 / `performance_reports` never scrubbed on coach erasure
- Finding 9 / missing optimistic-lock catch
- Finding 15 / three named test classes don't exist
- C1–C6, C8 (citation defects)

**MEDIUM confidence — mechanism verified, consequence reasoned rather than executed:**
- Finding 8 (sanitizer expansion: regex and column types verified; the specific 495-char payload not run)
- Finding 13 (`"null"` concatenation: no null handling confirmed; not rendered)
- Finding 10 (lock-budget doubling: transaction scope confirmed from the code's own comment; not load-tested)
- Finding 11 (parented-player 403: query and ownership logic read; not exercised against a fixture)
- Finding 12 (store-state wipe: null args and store shape confirmed; depends on how the dev wires the refresh)
- Finding 16 (openpdf encoding: the existing dropped `✓` is strong corroboration; no PDF generated to confirm)

**LOW confidence / advisory only:** Findings 17–21, L4b, L17, L18.

**Not independently re-checked by me** (accepted as the layers reported, since nothing hinged on them): the bulk of the 48 MATCH citations in §2, the "verified clean" items in §6 beyond those I quote lines for, and the precise line numbers inside `skillars-12-5`/`deferred-154` story files.

**Suggested disposition:** send back for revision rather than starting implementation. Six blocking items need author decisions — AC1's query strategy (adopt AC9's native-query approach), AC5's endpoint path and response mapper instruction, AC17's justification or removal, AC14's baseline backfill, AC11's CSS budget, and whether Finding 2's `performance_reports` hole is in scope or explicitly deferred with a ledger entry. AC6, AC13 and AC15 are clean as written. The story's research quality is high and its central technical insight is correct; the problem is that AC1 does not implement the insight the story's own Dev Notes defend.
