# Story Review: skillars-deferred-159-homework-timeout-drop-gdpr-export-id-space-and-epic-12-integrity-hardening

- **Audit date:** 2026-10-23
- **Real HEAD (checked at audit start):** `34084961` (feat(development): coach/player access consistency and hardening - shared access window, radar/preferences auth, lapsed roster, N+1 fix (12.10) (#295))
- **Note:** every citation below was checked against the working tree at this HEAD; source files are unmodified relative to it (only `_bmad-output/` is dirty). The story's own "re-verified against `master` @ `34084961`" happens to match the real HEAD, but was not relied on.
- **Method:** four parallel read-only verifier layers (citations, ledger/precedent, mechanism, corner cases), then a skeptical re-read by the reviewer of each surviving finding's evidence (Step 4b).
- **Prior review:** this story was already revised once against an earlier `story-review.md` (dispositions C1-C12, M1b). This report replaces that one.

---

## 1. Citation verification (Layer 1)

All ~45 citations resolve. None DRIFTED, none CANNOT_LOCATE. Approximate ranges are noted.

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| `QuickCompleteTimeoutService ~63-68` | MATCH (approx.) | publish spans `:65-70`; `List.of()` at `:68` |
| `QuickCompleteTimeoutService :31-35` | MATCH | 5 fields, no `ObjectMapper` |
| `QuickCompleteTimeoutService :56` | MATCH | `Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);` |
| `QuickCompleteTimeoutServiceTest:58` | MATCH | `service = new QuickCompleteTimeoutService(` |
| `BookingCompletionService:409` | MATCH | `deserializeHomeworkDrillIds(scd.getHomeworkDrillIds())` |
| `BookingCompletionService:183-189` | MATCH | only `objectMapper.writeValueAsString(req.homeworkDrillIds())` |
| `BookingCompletionService:168` / `:170` | MATCH | `verifyStatus(...COMPLETED_PENDING_CONFIRMATION)` / `new SessionCompletionData()`; deferred-153 placeholder check at `:160-167` |
| `BookingCompletionService:207` | MATCH | LIVE publishes raw `req.homeworkDrillIds()` |
| `BookingCompletionService:417-427` | MATCH | private `deserializeHomeworkDrillIds` |
| `BookingCompletionService:394-415`, `:146-221` | MATCH | `confirmCompletion` (`@Transactional` `:393`), `submitWrapUp` (`@Transactional` `:146`) |
| `WrapUpRequest:18` | MATCH | `@Size(max = 2) List<UUID> homeworkDrillIds,` |
| `HomeworkAssignmentService:60-71` | MATCH (partial) | empty-check + `limit(2)`; the "assigns any id" loop is actually `:84-107` |
| `HomeworkAssignmentService:57-115` | MATCH | method closes at `:116` |
| `GdprExportService:121-123` / `:122` | MATCH | `bookings.addAll(bookingRepository.findAllByPlayerId(userId));` |
| `GdprExportService:114-140` | MATCH (approx.) | `buildBookings` is `:117-137` |
| `GdprExportServiceTest:51` | MATCH | `lenient().when(coachProfileRepository.findByUserId(userId))...`; `findAllByPlayerId(userId)` stubs at `:49`, `:77` |
| `TimelineQueryService:72-74`, `:30-93` | MATCH | early `return null`; `referenceId` passed raw at `:51` |
| `TimelineEventListener:72`, `:105`, `:194-195`, `:229-253` | MATCH | metadata coachId; attendance-gated column; 5-arg overload; overload delegation |
| `PlayerTimelineRepository:62-68` | MATCH (approx.) | deferred-155 comment `:63-68`; query `:82-89` |
| `ConfigBounds:185-190` | MATCH | key `[1, 3650]`, `failFast=false`, default 90 |
| `ConfigStartupAssertionTest:130` | MATCH | `nonFailFastKeyOutOfRange_noThrow_metricIncrementedWithKeyAndReasonTagsOnly` |
| `CoachPayoutEnqueueListener:38` | MATCH | `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)` |
| `DrillSuggestionService:145-148` | MATCH | PRIVATE filter lives in `DrillRepository:19` JPQL / `chk_drill_owner`; rule equivalent |
| `DrillLibraryService:153` | MATCH | `addTag` rule: rejects PLATFORM, ignores status |
| `ApiAdvice:388-393` | MATCH | `logErrorAndReturnDTO(ex, ex.getMessage(), "security.featureGated")`; `ErrorMsg(errorKey, message)` |
| `sessionBuilder.store.js:151-154` | MATCH | `:153` `data?.helpCode === 'security.featureGated'` |
| `sessionBuilderStoreSpec.js ~123` | MATCH | `data: { helpCode: 'security.featureGated' }` |
| `SkillsRadarAssessmentPanel.vue:177` | MATCH | `errorMsg?.errorKey === 'security.featureGated'` |
| `GdprErasureServiceTest:836`, `:863` | MATCH | both single-child |
| `GdprErasureService ~1710`, `~1045` | MATCH | private static class; PARENT catch. The PLAYER-branch catch of the same type is at `:670`, so don't confuse the two |
| `GdprErasureIT ~2188` | MATCH | two-child tombstone assertions, but on the `DeleteStatementLockTimeout` catch (`:1012`), as the story says |
| V160 / V161 | MATCH | backfill `coach_id = (metadata->>'coachId')::uuid`; lookup index |
| `CreditStatementPage.vue` `referenceId` | MATCH | only frontend `referenceId` hits (`:54,59`) |

## 2. Ledger & precedent attribution (Layer 2)

Sources checked per claim: `deferred-work.md`, code comments/Javadoc, `git log`/`git blame`.

| Claim | Verdict | Notes |
|---|---|---|
| H1 (12.8 review) | CONFIRMED | ledger + 12.8 story `:138,:187` |
| H2 (12.8 review) | CONFIRMED | |
| H3 (12.6 dev) | CONFIRMED | the bullet sits in the "dev of 12.6" section |
| H4, H5 (12.10 review) | CONFIRMED | ledger's own H5 line ref `:80-81` drifted to `:78-79` (ledger-side drift, not the story's) |
| H6 premise ("only a WARN") | CONFIRMED, and the story's correction is accurate | `ConfigStartupAssertion:125,128` ERROR + metric regardless of failFast |
| H7 (12.9 review) | CONFIRMED | |
| H8 (deferred-158 review) | CONFIRMED | |
| `:836` = deferred-157, `:863` = deferred-158 | CONFIRMED | blame `7121ace5` / `4cea321f`; comment `:858` |
| deferred-153 placeholder message | CONFIRMED | `BookingCompletionService:152` comment, commit `6b798c34` |
| deferred-155 `metadata->>'coachId'` precedent | CONFIRMED | applies to the report author filter, not the viewer check; still a fair precedent |
| AC9 (12.10) allowlist | CONFIRMED | `TimelineQueryService:58` comment |
| Every "Explicitly NOT in this story" item exists in the ledger | CONFIRMED | the two new bullets (plan path, erasure marker) are correctly marked new |
| Missed picks from the mined sections | NONE | every post-158 item is either picked or excluded |
| `@Tsid` id spaces | CONFIRMED | `BaseEntity:27`; User and PlayerProfile both extend it |

## 3. Mechanistic claims (Layer 3)

| # | Claim | Verdict | Deciding evidence |
|---|---|---|---|
| M1 | Timeout service shape, `List.of()`, no mapper, bare `Instant.now()`, `created_at updatable=false`, two catches | TRUE | `QuickCompleteTimeoutService:50-52,56,61-71,73,75`; `SessionCompletionData:67` |
| M2 | Publish must stay in txn (payout BEFORE_COMMIT) | TRUE | `CoachPayoutEnqueueListener:38`. The story names three AFTER_COMMIT listeners; there is a **fifth** listener, `SessionPlanService.handleBookingCompleted:166-169` (AFTER_COMMIT). The conclusion is unaffected. Publishing outside a txn would also silently skip all `@TransactionalEventListener`s (no `fallbackExecution`) |
| M3 | `submitWrapUp` ordering, txn, lenient helper | **PARTIALLY TRUE** | ordering and txn correct; a throw at `:168-170` leaves no save and no transition. **But** "null/blank/unparseable → empty list + WARN" is wrong; see F2 |
| M4 | `@Size(max=2) List<UUID>` accepts `[null]`; `@Valid` on controller | TRUE | `SessionCompletionResource:81`; `mode` is a `String` `@Pattern("LIVE\|QUICK")` |
| M5 | Homework listener async/AFTER_COMMIT, truncates, idempotent, assigns any id | TRUE | `drill_id` has no FK (`V138:2071`), so unknown ids become orphan rows; `TimelineDrillSnapshotResolver` drops unresolved ids, so no timeline row for an all-unknown list |
| M6 | Drill rule source; no ArchUnit/Modulith; `findAllById` available | TRUE | `DrillRepository extends JpaRepository<Drill, UUID>`; fields are `String`/`UUID` (not enums); no ArchUnit (`EmailTransportArchitectureTest:21`) |
| M7 | GDPR export shape; `findByUserId` returns `Optional`; parent query runs for every role | TRUE | `PlayerProfileRepository:49`; `uq_pp_user_id` (one profile per user); `BookingService:204,347` sets `parentId` = self-registered player's own user id |
| M8 | `referenceId` raw; early return before authorship | **PARTIALLY TRUE (trap)** | see F3 |
| M9 | Listener write paths for H5 equivalence | TRUE | `:72`, `:105`, `:194-195`, `:233` |
| M10 | Config H6 closure facts | TRUE | 4-arg `getBoundedLong` (`ConfigService:115-123`) WARNs and falls back; the 3-arg overload (`:133-146`) clamps, so don't confuse the two. `CoachAccessWindow` uses the 4-arg form. `rejectOutOfRange` returns a real 400 |
| M11 | featureGated shape | TRUE | `ApiAdvice:705-719`; the branch can actually fire via `SessionPlanService:232-233` |
| M12 | Erasure PARENT loop, per-child REQUIRES_NEW, mutation feasible | TRUE | `:1538-1543` wrap mapping; `requiresNewTemplate` per child; tombstone save `~:1563-1564` after booking statements |

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

**F1. MED, AC2. Two more hand-written constructor calls break, and the story names neither.** `BookingCompletionService` is `@RequiredArgsConstructor`. Injecting `DrillRepository` breaks:
- `BookingCompletionServiceTest.java:70`
- `SessionPauseResumeServiceTest.java:62`

Both call `new BookingCompletionService(bookingService, bookingRepository, completionDataRepository, coachProfileRepository, userRepository, playerProfileRepository, eventPublisher, new ObjectMapper(), configService)` (re-read at `:68-74`). The story calls out only `QuickCompleteTimeoutServiceTest:58`, for AC1. **Fix:** list both files in Task 2.1.

**F2. MED, AC1. The helper's "lenient behaviour" is misstated.** `BookingCompletionService:419`: `if (json == null || json.isBlank()) return List.of();` has no log. Only the `catch` at `:423-425` WARNs. Implemented as the AC is written, the extracted helper would WARN on every homework-less completion (the common case) on both the confirm and timeout paths. Task 1.2's helper tests would also assert the wrong thing. **Fix:** null/blank → empty list silently; unparseable → empty list + WARN.

**F3. MED, AC4. The "lift authorship ahead of the early return" instruction can NPE as written.** `isAuthoringCoach` is defined from `metadata.get(METADATA_KEY_COACH_ID)` (`TimelineQueryService:78-79`). Hoisting it above `if (metadata == null) return null;` (`:72-74`) without a guard dereferences null. The guarded form (`metadata != null && ...`) means a null-metadata row is never authored. That is the safe outcome the AC wants (nulled `referenceId` for every coach), but it also nulls `referenceId` for the authoring coach on such a row. **Fix:** state the guard explicitly, and say that a null-metadata row hides `referenceId` from all coaches. In practice SESSION_COMPLETED/HOMEWORK_ASSIGNED producers always write metadata (`TimelineEventListener:72,193`), so this only affects the defensive test fixture AC4 asks for.

**F4. LOW, AC4. Type enumeration vs allowlist.** AC4 nulls `referenceId` for coaches only on SESSION_COMPLETED / HOMEWORK_ASSIGNED / PERFORMANCE_REPORT. The enum also holds four stub types (MILESTONE_REACHED, VIDEO_UPLOADED, PAYMENT_RECEIVED, REVIEW_LEFT). They have no producer today, but their comments describe future referenceIds (e.g. PAYMENT_RECEIVED "would expose a parent's payments to coaches"). The AC9 rule the story builds on is an allowlist ("Unknown/stub event types expose nothing", `TimelineQueryService:63`). **Suggest:** "for a coach viewer, `referenceId` is null unless `isAuthoringCoach`". That gives an identical outcome on today's types and is safe by default. No current leak.

**F5. LOW, AC2. Reject null elements before deduplicating.** `Set.copyOf`/`List.copyOf` throw an NPE on a null element, which would surface as a 500 instead of the intended `ResourceNotFoundException`. **Fix:** null-check first, then `stream().distinct()` / `LinkedHashSet` (which also preserves order for the persisted list).

**F6. LOW, AC1. Task 1.3 IT setup details.**
- The cutoff is `now − booking.quick_complete_timeout_hours`, seeded at 24 (`V139:59`), so backdate `created_at` by more than 24h.
- `@SchedulerLock(... lockAtLeastFor = "PT2M")` (`QuickCompleteTimeoutService:51-52`) means a second invocation in the same test needs the scheduler lock released.
- The homework drill must be a real ACTIVE drill (AC2's gate, and `TimelineDrillSnapshotResolver` drops unresolved ids, so no `HOMEWORK_ASSIGNED` row would appear for a random UUID).
- `SessionCompletionResourceIT` already has a QUICK wrap-up fixture, so extending it may be cheaper than a new class. (Reported by Layer 4; I re-verified the lock annotation and seed value. I did not independently re-open the IT fixture or the lock-release helper.)

**F7. LOW, Testing standards / AC4. "Every new test must fail on pre-change code" is unsatisfiable for the positive cases the story itself requires:** own-PRIVATE accepted, PLATFORM accepted, author/player still see `referenceId`. **Fix:** "every negative case fails pre-change; positive cases are regression guards."

**F8. LOW, AC7. Put child A (the throwing one) first in the `findByParentIdOrderByIdAsc` stub list.** Mockito strict stubs flag an *unused* stubbing whose args mismatch an actual call (`PotentialStubbingProblem`). If child B is processed first, its `anonymizeNotesAndPlayerRequestByPlayerId(childB.getId())` call can trip the unused child-A `doThrow` stub. Child-A-first is also the only order in which the `break` mutation is detectable. (The mechanism comes from Mockito's documented strict-stubs behaviour and was not executed.)

**F9. LOW, AC1 (ledger note).** QUICK rows created before AC2 deploys are still pending (up to the timeout, default 24h) with unvalidated `homework_drill_ids`. After AC1 the timeout publishes them. This is not new in kind: `confirmCompletion:409` already publishes the same rows today. It is worth one line beside the plan-path ledger bullet.

## 5. What did not survive re-verification

- **"~18 keys" is wrong (Layer 3, M10). KILLED.** Layer 3 counted ~48 `getBoundedLong` *call sites*. The story says "~18 keys", and `ConfigBounds:61` says "all 18 via the 4-arg `getBoundedLong`" for the `HAS_CODE_DEFAULT` keys. Keys ≠ call sites. The story is accurate and the "log per request" argument stands either way.
- **"GdprErasureIT:~2188 not verified" (Layers 2 and 3). KILLED as a finding.** Layer 1 located and quoted it (`:2187-2190`, DeleteStatementLockTimeout path), which matches the story's parenthetical.
- **"Missing fifth BookingCompletedEvent listener" (M2). DOWNGRADED to a note.** Re-grepped: `SessionPlanService:166-169` is AFTER_COMMIT. Its existence doesn't change the "publish inside the txn" conclusion.
- **`HomeworkAssignmentService:60-71` cites the wrong lines for "assigns any id". DOWNGRADED to a note.** It is the `:84-107` loop; the claim itself is true.
- **"`DrillSuggestionService:145-148` doesn't show a PRIVATE filter". DOWNGRADED.** The rule is equivalent via `DrillRepository:19` and `chk_drill_owner` (`V138:2056`). Optionally add that one-clause justification to AC2.

## 6. Recommendation

The story is implementable as-is. Its core premises held on independent re-check: H1–H8 are real, the H3 "hygiene, not a leak" rationale, the H5/H6 closures, the AC2 rule source, and the AC7 mutation target. Confidence by area:

- **Citations & ledger attributions:** high. Every one was opened and quoted. Remaining drift is ±2 lines, plus one in the ledger itself.
- **AC1:** apply F2 (wording that would cause a real behaviour/test error) and F6 (IT details). The F6 IT-fixture and lock-release helpers were not independently opened by me.
- **AC2:** apply F1 (compile break in two test classes; directly re-verified) and F5.
- **AC3:** high. No findings.
- **AC4:** apply F3 (directly re-verified); consider F4.
- **AC5 / AC6:** high. No findings; the `helpCode` defect has no other occurrences in the frontend.
- **AC7:** F8 is a test-layout note, based on reasoning about Mockito behaviour rather than execution.
- **Testing standards:** reword per F7.

Not independently checked by the reviewer (only by one layer): `SessionCompletionResourceIT` QUICK fixture location, `AbstractIntegrationTest` lock-release helper, and the 12.8 story-file line refs for H1/H4 history.
