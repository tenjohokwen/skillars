# Story Review: skillars-deferred-162

**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-162-otp-hmac-verify-email-rate-limit-client-ip-drill-ownership-timeline-idempotency-erasure-and-dispute-recap-lock.md`
**Audit date:** 2026-10-26
**Real HEAD at audit time:** `5f5e2994` — "feat: alert recipient from config, ConfigService single-flight refresh, Vitest 4 + AE 2.0.0 (deferred-161) (#300)"
**Note:** every citation below was independently re-opened and read at this HEAD. The story's own "Verified state at drafting" header also claims `5f5e2994`, so there is no commit drift between drafting and audit time in this case — but every citation was still re-verified from the real file content, not trusted from that match. ~140 individual claims were checked across 7 parallel verification passes (2 citation-location passes, 1 ledger/precedent pass, 2 mechanistic-behavior passes, 2 corner-case passes), followed by my own adversarial re-check (Step 4b) of every finding that survived, done by re-opening the cited files myself.

---

## 1. Citation verification

~100 `file:line` citations were checked by opening the real file at the cited location. The overwhelming majority are accurate. Only citations with a verdict other than a clean MATCH are listed individually; everything else is summarized by AC.

| AC | Citations checked | Clean MATCH | Issues |
|----|---|---|---|
| AC1 (verify-email / client IP) | 18 | 15 | 3 (see below) |
| AC2 (OTP hash) | 15 | 14 | 1 (see below) |
| AC3 (OTP budget) | 6 | 4 | 2 (see below) |
| AC4 (plan drills) | 13 | 12 | 1 (see below) |
| AC5 (erasure) | 11 | 11 | 0 (two trivial caveats, not defects) |
| AC6 (disputes) | 9 | 9 | 0 |
| AC7 (timeline idempotency) | 11 | 11 | 0 (one trivial caveat) |
| AC8 (token writes) | 4 | 4 | 0 |

**Issues found:**

1. **`ApiAdvice:242-249` claim is factually wrong (real defect).** The story's "Verified state" for AC1 says the rate-limit handler "maps to 401 `security.unauthorized` **plus a `SecurityAlertEvent`**." Re-read directly: `handleAuthException(AuthorizationException)` (`ApiAdvice.java:246-249`) does **not** publish anything. `SecurityAlertEvent` is only published by the separate `accessDeniedHandler(AccessDeniedException)` (`ApiAdvice.java:314-318`) — a different exception type entirely, thrown by method-security `@PreAuthorize` failures, not by the rate limiter. **CONFIRMED by my own re-read.** This doesn't block any AC (it's background context, not something an AC depends on), but a dev reading this literally would wrongly assume rate-limit hits already trigger a security alert.

2. **`TimelineDrillSnapshotResolver.forBooking:51-76`, "session loaded at `:53` has `getCoachId()`" — fabricated detail (real defect).** Re-read the full file directly: line 53 is `Session session = sessionRepository.findByBookingId(bookingId).orElse(null);`. `grep -n "getCoachId" TimelineDrillSnapshotResolver.java` returns **zero matches** anywhere in the file. **CONFIRMED by my own re-read.** This matters more than item 1: AC4.3 tells the dev to pass `session.getCoachId()` into the new ownership filter for this resolver, on the premise that the session object already exposes it at the point the story cites — true (Session does have a `getCoachId()` method generally, used elsewhere), but the specific claim that this call already appears at this location is false. Not a blocker, but the dev should not go looking for an existing call there.

3. Minor, non-blocking citation imprecisions (listed for completeness, no action implied): `CoachRegistrationService:165-179` says "sends an SMS/email" — only email is sent (`sendOtpEmail`/`CoachOtpEmailEvent`), there is no SMS path. `application.yaml:27-29`'s dotted-path paraphrase ("server.tomcat.remoteip.remote-ip-header") has a spurious extra segment; the real property is `server.tomcat.remote-ip-header`. The "14 call-site uses" of `getIpAddress()` is accurate as a raw grep count but 6 of the 14 are the method's own getter/interface declarations, not call sites (8 are genuine invocations). `SkillarsPlatformProperties:52-65`'s "≥32 chars, startup-checked" detail is true but the actual length check lives in `RegistrationVerificationTokenService`'s constructor, not at the cited lines. `CoachRegistrationService` line citations `:190` (should be `:191` for the `findFirst...` call) and `:211` (should be `:211-212`, the compare is on 212) are off by one line each — same conclusion reachable either way. V138's `uq_pot_one_active_per_user` is a partial **unique index**, not a named CONSTRAINT clause — functionally identical, syntactically imprecise wording.

None of the minor items above change any AC's required work; items 1 and 2 are real but non-blocking since no AC's acceptance test depends on either claim being true.

---

## 2. Ledger & precedent attribution

All 11 ledger-bullet line-number citations in the "Ledger → story mapping" table are **stale** — every single one is off by roughly 9–22 lines from its real current location in `deferred-work.md`, consistent with the story's own disclaimer that the working tree had uncommitted deletions at drafting time. This is cosmetic *except* for three rows where the drift also names the **wrong section/group letter**, which is more than cosmetic — a reader following the citation literally lands in the wrong block:

| Ledger bullet | Story's claimed section | Real section (verified) | Real line |
|---|---|---|---|
| "D1: `/verify-email` endpoint not rate-limited" | "...Group C (2026-06-11)" | **Group D** | 862 |
| "D6: verifyEmail endpoint not @RateLimited" | "...(2026-06-11)" labeled as a Group-C duplicate | **Group A** | 888 |
| "D3: OTP hash SHA-256(otp+userId) no random salt" | "...Group C" | **Group B** | 878 |

The other 8 rows drift only in line number, not section — all were re-located and confirmed to match their claimed lead text and section description. The `[PICKED UP by skillars-deferred-162 ACn]` tags are correctly worded throughout.

**One real, independently-confirmed citation error:** the story cites migration **V161** as the "precedent" for marking a `CREATE UNIQUE INDEX` with the `-- migration-lint: allow-blocking-index` marker (AC7.1). I re-read `V161__development_player_timeline_events_coach_lookup_index.sql` directly: it creates a plain, **non-unique** `CREATE INDEX`, not a unique one. The lint-marker pattern itself is a valid precedent (any blocking index needs the marker, unique or not), but citing V161 specifically as precedent for a *unique* blocking index overstates what that migration actually shows.

**Everything else checked out as accurate, independently confirmed against primary sources (not just the story's own paraphrase):**
- `skillars-10-3-dispute-resolution.md:562-568` genuinely says "Do NOT transition the booking to `DISPUTED` status in Story 10.3" — verbatim match.
- The "Plan-path private drill names" / "Erasure marker not checked on the delayed QUICK path" findings genuinely originate in `skillars-deferred-159`'s own text (lines 39 and 42 respectively), not just in deferred-162's paraphrase of it.
- The ledger-hygiene convention ("if a section ends up with no bullets, remove its heading") is genuinely traceable to a real deferred-161 review-findings patch, not fabricated.
- V160's cited DML-marker justification text is a close paraphrase of the real (slightly richer) comment.
- The `DISPUTED` `BookingStatus` enum value exists in the codebase (a different, in-session mechanism per Story 7.2) but has zero production callers triggering that transition — so its existence does not make the "booking status stays untouched" decision stale.
- `skillars-12-7`, `skillars-12-8`, and `skillars-deferred-143` story files all exist and all genuinely contain the findings deferred-162 attributes to them, verified by reading each file directly rather than trusting the paraphrase.

---

## 3. Mechanistic claims

All 26 mechanistic/behavioral claims checked (transaction boundaries, control-flow order, exception types, annotation values) were verified by reading the full method bodies, not snippets. **24 of 26 are clean matches**, confirmed by my own re-read of the highest-stakes ones (the AC3 verify-order claim, the AC5 transaction-boundary claim, the AC7 `REQUIRES_NEW` claim, the `AuthorizationException`/`ApiAdvice` mapping). Two have a minor precision gap, neither changing any AC's required work:

- The claim that "listeners are all AFTER_COMMIT + @Async(taskExecutor)" is true for all three AC5 listeners, but omits that `TimelineEventListener.onHomeworkAssigned` also carries `fallbackExecution = true` — a real, functionally-necessary extra attribute (its publisher has no active transaction), just not mentioned.
- The story cites `GdprErasureIT:1497-1510` as covering the "[the AC8 call] rolls back and is retried" claim. Re-read: that test (`erase_parentUser_laterFailureDoesNotRollBackAlreadyCommittedChild`) calls `erase()` directly, bypassing the `GdprEventListener`/`markFailed`/`retryFailedErasures` path entirely — it proves the atomicity-tradeoff half of the claim (already-committed children survive), not the retry half. The retry mechanism itself is real (confirmed in `GdprEventListener.onErasureRequested` and `retryFailedErasures`), just not exercised by the cited test.

---

## 4. Corner cases, false assumptions, and missed flows (survived adversarial re-check)

Each of these was independently re-opened and re-read by me after the hunting passes reported them — see §5 for what did not survive.

### 4.1 AC4 — a false "closed transitively" assumption (real gap)
The story explicitly asserts: *"Templates copy blocks only from the coach's own sessions (`:67`, `:155`), so it is closed transitively once plan writes are checked."* **This is false.** I re-read `SessionTemplateService.deployTemplate` (lines 112–166) directly: it builds a brand-new `Session` from a template's stored blocks (`session.setBlocks(t.getBlocks()...)`, line 151) and calls `sessionRepository.save(session)` (line 163) **without ever calling `SessionPlanService.createSession`/`updateSession`** — so AC4.2's new ownership validation, which the story scopes only to those two `SessionPlanService` methods, never runs on this path. Worse, `buildResponse` (lines 195+, explicitly commented `// mirrors SessionPlanService.buildResponse — keep in sync`) does the exact same unfiltered `drillRepository.findAllById(drillIds)` → `drillLibraryService.toResponse(...)` the story is fixing in `SessionPlanService.toResponseMap` — and AC4.3's read-side filtering list never mentions `SessionTemplateService` at all.
**Concrete failure scenario:** a coach saved a plan with another coach's PRIVATE drill before this fix (the pre-existing bug), turned it into a template. After the fix ships, deploying that template (a) persists the foreign drill id into a brand-new session row with zero validation, and (b) the deploy response itself hands the deploying coach the foreign drill's full detail (name, description, coaching points) via the unfiltered `buildResponse`/`toResponse` call — on every future deploy of that template, indefinitely, since templates are archived rather than deleted.

### 4.2 AC5 — the fix's own title is not actually achieved; two more writers are untouched (real gap)
AC5 is titled "No new development data is written for an erased player," but I independently confirmed (via `grep`, then reading both services directly) that **two additional listeners write into tables the GDPR erasure explicitly scrubs, and neither is in the story's list of three**:
- `TimelineEventListener.onRadarEntrySubmitted` (writes a `RADAR_ASSESSMENT` timeline row) ← fed by `RadarAssessmentService.submitAssessment`, which writes to `RadarAssessmentEntry` rows.
- `ReportGenerationService.onReportGenerated` (writes a `PERFORMANCE_REPORT` timeline row) ← fed by `ReportGenerationService.generateReport`, which writes `PerformanceReport` rows.

I confirmed directly that `GdprErasureService` deletes both tables during erasure (`radarAssessmentRepository.deleteAllByPlayerId(playerId)` at line 1483, `performanceReportRepository.deleteAllByPlayerId(playerId)` at line 1496), and confirmed via `grep` that neither `RadarAssessmentService` nor `ReportGenerationService` contains any `developmentDataErasedAt`/`ErasedAt` check anywhere. Their only gate is a coach-player relationship/access-window check, unrelated to erasure. **Concrete failure scenario:** a coach with an open access window to an already-erased player can still submit a radar assessment or generate a performance report; both actions insert fresh rows into the exact tables GDPR erasure just emptied, completely unguarded, after this story ships.

### 4.3 AC5 — the proposed lock, if implemented as a single wrapping transaction, risks silently breaking `HomeworkAssignmentService`'s documented replay-tolerance (real implementation risk)
I re-read `HomeworkAssignmentService.handleBookingCompleted` directly (lines 57–116): it carries **no** `@Transactional` today — only `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`. Each `homeworkAssignmentRepository.saveAndFlush(assignment)` (line 101, inside a per-drill loop) auto-commits independently, and the method's own comment explicitly says why: *"No active transaction here (each saveAndFlush committed on its own)..."* (line 110), and *"Published from a finally so a non-duplicate-key failure on drill 2 cannot swallow the already-committed drill 1"* (line 80–81). AC5.1 requires checking the erasure marker "under the `player_profiles` row lock... inside the same transaction as their write" — to close the check-then-act gap, the lock must stay held across the write(s), which for this method means wrapping the per-drill loop in one new transaction. If done naively (one `@Transactional(REQUIRES_NEW)` around the whole loop), a `DataIntegrityViolationException` on drill 2 — the *exact replay scenario this method is already designed to tolerate* — would poison that shared transaction; catching the Java exception does not un-poison a transaction, so its eventual commit would behave as a rollback, silently discarding drill 1's otherwise-successful insert. This is a real regression risk the story doesn't flag, and the fix would need per-iteration savepoints (the same technique `PessimisticLockRetryer` already uses elsewhere in this codebase) to avoid it.

### 4.4 AC6 — `resolution <> 'NO_ACTION'` relies on an unenforced invariant, not a constraint (real latent risk, currently dormant)
I confirmed directly: `Dispute.resolution` (`Dispute.java:47-48`) has no `nullable = false` — it's nullable at the schema level. The proposed nudge-exclusion JPQL predicate (`d.status = 'RESOLVED' AND d.resolution <> 'NO_ACTION'`) relies on SQL's three-valued logic resolving `NULL <> 'NO_ACTION'` to `UNKNOWN`/not-true for safety to hold, which happens to match today's single-writer behavior (only `DisputeService.resolveDispute` ever sets `status = 'RESOLVED'`, and it always also sets a non-null `resolution` from a `switch` with no fall-through default). But this safety comes from a single-method invariant, not a schema guarantee — a future bulk-fix script, data import, or legacy row with `status='RESOLVED'` and `resolution = NULL` would silently leave that booking's recap nudge-eligible (and editable) when AC6 intends it locked. Recommend `COALESCE(d.resolution, '') <> 'NO_ACTION'` or a DB CHECK, not flagged in the story.

### 4.5 AC7 — "all in one transaction" (AC7.4) is locally true but easy to misread across the AC5/AC7 combination
`writeTimelineEvent` genuinely is `@Transactional(propagation = REQUIRES_NEW)` and the lock+check+insert inside it are one real transaction — confirmed by direct re-read. But `REQUIRES_NEW` *suspends* any caller's transaction; it does not extend atomicity to the original booking-completion write or to `HomeworkAssignmentService`'s own (separately committed) write. The phrase "all in one transaction" in AC7.4 should not be read as implying broader rollback coupling than actually exists — worth one clarifying sentence so an implementer doesn't assume more safety than the propagation model provides. This is the same underlying mechanism as finding 4.3, surfacing from the AC7 side.

---

## 5. What did not survive re-verification

- **AC1 blast-radius worry (AC1.5) is more resolved than the story treats it, not less.** The hunting pass found that `PersistentToken` is dead code in production (never constructed/queried outside GDPR-erasure clearing) and `JWTAuthorizationFilter` never binds session validity to IP at all (its only IP use is an audit-alert dedup key). I did not re-verify this one myself byte-for-byte, but it is not a defect in the story — if anything it means AC1.5's hedge ("record it in Completion Notes; it is acceptable because there is no prod deployment") can be answered with higher confidence than the story assumes, not a finding against the story. Not included in §4 because it doesn't identify a wrong or missing requirement — it only means the dev's eventual answer to AC1.5 will likely be "no impact," which the story already anticipates as an acceptable outcome either way.
- **AC5.5's Awaitility-test false-pass concern** (raised by the hunting pass: an async listener simply not having run yet could produce a false "nothing appears" pass that a same-signal positive control can't fully rule out) is a legitimate test-design subtlety, but I downgraded it out of the confirmed-findings list: the story already asks for "a positive control so it cannot pass vacuously," and the specific scenario described (no third always-fires signal from the same event delivery) is a general hazard of this testing style in this codebase, not something unique to this AC that the story overlooked. Worth the dev's attention during implementation, but not rising to a corner case the story missed.
- **AC6 multiple-disputes-per-booking aggregation** — checked directly against the proposed `NOT EXISTS` predicate; it correctly excludes `DISMISSED` rows from being confused with a separate active dispute. No defect.
- **AC7 cross-event-type `reference_id` collision** (worry that `HOMEWORK_ASSIGNED` rows sharing the same `reference_id` value could be mis-touched by the dedupe DELETE or the partial index) — confirmed both are scoped by `event_type = 'SESSION_COMPLETED'`, so this is structurally excluded. No defect.
- **AC6 ArchUnit/module-boundary risk** for the new cross-module JPQL — confirmed no such test exists, but also confirmed the exact same pattern (referencing another module's entity by name in JPQL with no Java import) is already shipped and working in `BookingRepository.findBookingsMissingRecap`. Not a new risk; downgraded to an informational note.
- **AC4 duplicate/null drill-id handling** — initially flagged as a possible gap; confirmed directly that `fetchDrills` already `.distinct()`s ids, and new-write-path null ids are already rejected by `@NotNull`/`@Valid` Bean Validation before reaching the service. No live gap on the write path (a residual, low-confidence concern about pre-existing rows with a literal null in legacy JSON data was raised but not confirmed, so it is not included as a finding).

---

## 6. Recommendation

This story is unusually well-grounded for its size — of the ~140 individual claims checked (citations, ledger attributions, mechanistic behavior, and corner-case assumptions), the large majority were confirmed exactly as written, including every one of the highest-stakes mechanistic claims (the AC3 control-flow order the whole fix depends on, the AC5/AC7 transaction boundaries, the AC6 module-direction and status-mutation claims). That said, calibration matters more than a single score here, so treat the findings by tier:

- **High confidence, should be addressed before/during dev:** §4.1 (SessionTemplateService is an unvalidated/unfiltered second path AC4 doesn't cover — the story's own "closed transitively" justification is demonstrably false) and §4.2 (AC5 doesn't actually achieve its own title; two more writers need the same guard). Both were confirmed by me re-reading the exact cited and uncited files directly, not just taken from the hunting pass.
- **Medium confidence, worth a note in Dev Notes before implementation starts:** §4.3 (transaction-poisoning risk if the AC5 lock is added to `HomeworkAssignmentService` naively) and §4.4 (nullable `resolution` column + three-valued-logic risk in the AC6 predicate) — both real and directly confirmed, but currently dormant/avoidable with a small implementation choice rather than requiring a design change.
- **Low confidence / cosmetic, no action required:** the two factual citation errors in §1 (items 1–2) and the ledger line-drift in §2 (all 11 lines stale, 3 with a wrong section letter) — true defects in the story's own self-documentation, but none of them changes what the dev needs to build.
- **Explicitly downgraded after adversarial re-check:** the four items in §5 — each was raised by a verification pass and then independently re-examined and found to be either already-resolved, already-covered, or not actually load-bearing.
