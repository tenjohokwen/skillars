## Deferred from: adversarial code review of skillars-5-6-parent-development-portal (2026-06-19)
- AD1: Stale error state between player switches — each store action resets its own error ref at call-start; fully resolved when route.params.playerId watch patch is applied [`ParentDevelopmentPortalPage.vue`]
- AD2: MapStruct not used for `Object[]` → `CoachContributionDto` mapping — MapStruct cannot transform raw JDBC Object[] projections; inherent native query limitation [`SluContributionService.java`]
- AD3: `@Testcontainers` annotation absent from IT class — tests pass (6/6); infrastructure activated via TestConfig; add annotation for explicitness in future test-hardening pass [`ParentDevelopmentPortalResourceIT.java`]
- AD4: Instancio not used in IT — FK-constrained integration seeding requires fixed IDs; project-wide IT pattern [`ParentDevelopmentPortalResourceIT.java`]
- AD5: `SecurityConstants` not used in `@PreAuthorize` — pre-existing convention in the file; new endpoint follows the same pattern as existing endpoints [`SkillExposureResource.java`]
- AD6: Triple null guard inconsistency in service — `AND coach_id IS NOT NULL` in SQL already prevents nulls; guards are over-defensive but harmless [`SluContributionService.java`]
- AD7: `BigDecimal` vs JS number comparison in `CoachContributionSection` — speculative risk only if Jackson serialization changes to string representation [`CoachContributionSection.vue`]
- AD8: Authority row `id=9612` not cleaned up in `asCoach` test — `ON CONFLICT DO NOTHING` prevents failure on re-run [`ParentDevelopmentPortalResourceIT.java`]
- AD9: Route path naming inconsistency (`/parent/players/` plural) — cosmetic; consistent with existing sibling route in the same block [`routes.js`]
- AD10: `neglectedSkillNames` shows raw skill codes if `skillDefinitions` not yet loaded — transient window; `q-inner-loading` overlays content during load [`ParentDevelopmentPortalPage.vue`]
- AD11: Dual-counter race between `loadGeneration` (page) and `_coachContributionsSeq` (store) — `finally` in `loadPortal` always fires; loading flag cannot hang [`ParentDevelopmentPortalPage.vue`]
- AD12: Mock state override inside `transactionTemplate` in percentages test — Spring Boot 3.4+ automatically resets `@MockitoBean` between test methods [`ParentDevelopmentPortalResourceIT.java`]

## Deferred from: code review of skillars-5-6-parent-development-portal (2026-06-19)
- D1: `IllegalArgumentException` if `row[0]` (coach_id from native query) is not a valid UUID string — unlikely under DB schema constraints but unguarded [`SluContributionService.java`]
- D2: No exception wrapping around `coachProfileService.getDisplayNamesByIds` — propagates as 500 on failure; consistent with general project error-handling convention [`SluContributionService.java`]
- D3: Double iteration of `rows` list in `getCoachContributions` — could use SQL window function for in-DB percentage calculation; optimisation, not a correctness issue [`SluContributionService.java`]
- D4: `PerformanceReportsPanel` wrapped in extra `q-card` in parent portal may produce a double-card visual artifact — requires runtime visual verification [`ParentDevelopmentPortalPage.vue`]

## Deferred from: code review of skillars-5-5-pdf-performance-report-unified-player-timeline — Round 3 (2026-06-19)
- R3-D1: Orphaned `player_timeline_events` PERFORMANCE_REPORT row when outer `generateReport` transaction rolls back after `writeTimelineEvent` REQUIRES_NEW commit — timeline shows an event with a dead `referenceId`; accepted MVP trade-off per spec dev notes [`ReportGenerationService.java:144-153`]
- R3-D2: `findLastSessionDate` queries `MAX(calculated_at)` over all `player_skill_stats` rows without filtering for session-only writes — if `RadarAssessmentService` writes SLU rows to `player_skill_stats` (to be confirmed), radar assessments could reset the coach timeline-access expiry window, contradicting the design comment in `TimelineQueryService` [`SluRepository.java:43-48`]
- R3-D3: Stale branding logo key persists after coach tier downgrade from ACADEMY — re-upgrade reuses the prior logo key without re-validation; graceful try/catch fallback in `buildPdf` prevents crash [`ReportGenerationService.java:278`]

## Deferred from: code review of skillars-5-5-pdf-performance-report-unified-player-timeline (2026-06-19)
- D1: `slu_value` and `calculated_at` column names in `SluRepository` native queries not explicitly verified against the actual migration file — runtime `BadSqlGrammarException` risk; confirm column names from V-series migration before deploying [`SluRepository.java:36,42`]
- D2: S3 I/O (Academy logo download + PDF upload to S3) executes inside `@Transactional generateReport` — blocking calls hold DB connection for the duration; may exhaust connection pool under concurrent load [`ReportGenerationService.java:87-142`]
- ~~D3: Email Thymeleaf template at `mails/performanceReportShared.html` — verify this matches the path `MailManager` scans; if the scanner expects `templates/mail/`, emails will silently fail to render with no exception thrown [`src/main/resources/mails/performanceReportShared.html`]~~ — **RESOLVED 2026-06-19**: path confirmed correct by Acceptance Auditor; `MailManager` scans `mails/` prefix with CamelCase name.
- D4: No rate limit on `POST /api/development/players/{playerId}/reports` — a coach can call in a loop; each call generates a PDF, uploads to S3, inserts a DB row, writes a timeline event, and queues a parent email; trivial cost-amplification DoS vector [`PerformanceReportResource.java`]
- D5: `nextSteps` stored permanently on `performance_reports` with no redaction or deletion API — coach cannot correct defamatory or incorrect notes after generation; GDPR erasure story must add `PerformanceReportRepository.deleteByPlayerId` + S3 object deletion for each `storage_key` [`ReportGenerationService.java`, `performance_reports` table]
- D6: `getParentEmailByPlayerId` fires 2 separate DB queries (getParentIdByPlayerId + userRepository.findById) — no single-query fetch for parent email+name; TOCTOU gap if parent account deleted between calls [`PlayerProfileService.java`]
- D7: Any `ROLE_COACH` can call `GET /api/development/players/{playerId}/reports` for any player — `hasRole('ROLE_COACH')` short-circuits the parent ownership guard; explicit MVP trade-off per spec dev notes; revisit if enumeration attacks become a concern [`PerformanceReportResource.java:listReports`]

## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation (2026-06-19)
- W1: No FK from `player_radar_baselines.player_id` / `coach_radar_preferences.player_id` to `main.player_profiles` — accepted limitation per spec dev notes; consistent with Stories 5.1–5.3 no-FK pattern across `development.*` tables [`V51__radar_display_correlation.sql`]
- W2: Rapid skill-toggle fires a PUT per click — no debounce; last-write-wins for fast toggling; low risk [`PlayerDevelopmentDashboardPage.vue`]
- W3: `insertBaselineIfAbsent` `@Transactional` participates in outer transaction — `ON CONFLICT DO NOTHING` cannot protect across a rollback on first-ever baseline write; documented MVP limitation in spec dev notes [`PlayerRadarBaselineRepository.java`]
- W4: Skill deactivation silently drops baseline from display — `findAllByActiveTrueOrderByDisplayOrderAsc` excludes inactive skills; baseline re-appears on reactivation [`RadarDisplayService.java:39`]
- W5: IT `assertThat(minimumSessionCount).isEqualTo(5L)` hardcodes config value — low risk with Testcontainers; `ON CONFLICT DO NOTHING` in V51 migration [`RadarDisplayResourceIT.java:333`]
- W6: Any ACADEMY coach can call `GET /radar/correlation` for any player — no player-coach ownership check; consistent with `GET /radar/display`; platform-wide security hardening needed (see also DEF5) [`RadarDisplayResource.java:64-69`]
- W7: `IMPROVEMENT_THRESHOLD = 3.0` hardcoded — exactly-3-point improvement classified as "no improvement"; explicitly accepted in spec dev notes; configurable in a future story [`DevelopmentCorrelationService.java:33`]
- W8: `(int)` cast on `totalCount` in `RadarCompositeCalculationService` — pre-existing silent overflow for very high entry counts; not introduced in this diff (see DEF6) [`RadarCompositeCalculationService.java`]
- W9: `SkillsRadarChartSpec.js` tests cannot run — vitest / `@vue/test-utils` not installed; explicitly accepted in story completion notes; frontend test-runner setup is a separate initiative

## Deferred from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation — Pass 2 (2026-06-19)
- DEF5: No coach-player relationship check on radar endpoints — any INSTRUCTOR+ coach can submit/read assessments for any player; `parentId` JOIN only provides cross-family isolation (FR-TSC-009), not coach-to-player assignment enforcement; same architectural gap as Story 5.2 narrative access (DEF0); address in a platform-wide security hardening story auditing all coach-scoped development endpoints [`RadarAssessmentService.java`, `RadarAssessmentResource.java`]
- DEF6: `entry_count` long→double→int narrowing in composite calculator — count from native SQL is cast double→int; silently overflows above Integer.MAX_VALUE; irrelevant at current volumes [`RadarCompositeCalculationService.java:61-69`]
- DEF6: Orphaned `player_radar_composites` rows on player deletion — `player_id` column has no FK to `player_profiles`; deleted player leaves stale composite rows; pre-existing no-FK pattern across the development module [`player_radar_composites`, `V50__radar_assessment_entries.sql`]
- DEF7: Async composite silently stales on failure — `@Async` listener swallows all exceptions (logged only); no retry/dead-letter queue; composite frozen at prior value until next submission triggers recomputation; accepted per story dev notes [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`]

## Deferred from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation (2026-06-19)
- DEF1: `SkillDefinitionRepository` injected directly into `SkillDefinitionResource` (no service layer) — pre-existing architecture; fix at next planned touch of `SkillDefinitionResource` [`SkillDefinitionResource.java:17`]
- DEF2: `entry_count` in `player_radar_composites` stores total rows across all assessment types, not distinct coaches — semantic mismatch with Story 5.4 confidence indicator design ("3+ entries = filled dot" is misleading when all 3 rows come from one coach); 5.4 author should add a `distinct_coach_count` column or revise the confidence model [`RadarCompositeCalculationService.java`, `player_radar_composites`]
- DEF3: Concurrent async composite recalculation race — two simultaneous submissions for the same player trigger two `@Async` events that can both query aggregates before either upserts; last writer wins and self-corrects on the next submission; theoretical low-probability issue [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`]
- DEF4: No retry or dead-letter queue for async composite failure — failure logged but composite silently stale until next assessment triggers recomputation; accepted per story dev notes; address in an infrastructure hardening story if operational visibility requires it [`RadarCompositeCalculationService.java`]

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group C (2026-06-19)
- D3: `getNeglectedSkills` in `development.api.js` is dead code — neglected codes are bundled in the exposure response; the standalone API export is never called. Remove in a cleanup pass. [`development.api.js:11-12`]
- D4: `useDevelopmentStore` is a singleton and not reset on player navigation — coaches managing multiple players see stale data briefly between navigations. Fix: clear `exposure`, `targets`, `narrative` to null/[] at the top of `onMounted`. [`PlayerDevelopmentDashboardPage.vue:81`]
- D5: `SluTargetEditor` `currentTargets` watcher can discard in-progress user input if `fetchTargets` resolves while the dialog is open (race between fetchExposure completing and fetchTargets completing). Low probability; fix by guarding the targets-loaded state or deferring the watcher when open. [`SluTargetEditor.vue:51`]
- D6: `de/index.js` development block uses English strings — translate to German in a dedicated i18n story. [`de/index.js:388-404`]

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group B (2026-06-19)
- D1: Year-boundary week arithmetic in `SluDashboardServiceTest` — `prevWeek = curWeek > 1 ? curWeek-1 : 52` assigns ISO week 52 to the current year when curWeek=1; the correct week 52 belongs to the prior ISO year. Test passes because the mock returns whatever keys are given, but snapshot IDs are semantically invalid and would produce wrong data if copied to an IT test. Only manifests in tests run in early January (ISO week 1). Fix: compute prev/prevPrev using `ZonedDateTime.minusWeeks(n)` + `WEEK_BASED_YEAR`, same as the narrative test. [`SluDashboardServiceTest.java:690-692`]
- D2: AC 7 (neglected skill detection uses highest coach target) has no IT-level regression test — the `multipleCoachesUsesHighestTarget` unit test pre-bakes the MAX result as a stub, so it does not verify that the JPQL `SELECT ... MAX(t.weeklyTargetSlu) ... GROUP BY ...` query actually aggregates across two coaches. If the query gains a `WHERE coach_id = :coachId` filter by mistake, AC 7 silently breaks. Add an IT that inserts targets from two coaches for the same player/skill, fires the detection, and verifies the flag reflects the highest target. Defer to Story 5.3 IT suite expansion.

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A (2026-06-19)
- D0: Narrative sharing consent system — player or parent should be able to grant a coach access to their narrative summary, with a visible toggle to revoke it; coaches currently have unrestricted read access to all narratives via `ROLE_COACH` guard; restrict access and implement a proper permission model in a dedicated story [`SkillExposureResource.java:34-38`]
- D1: V49 `CREATE UNIQUE INDEX` blocks startup if phased deploy allowed Monday batch to create duplicate flags between V48 and V49 — mitigated by same-commit deployment of both migrations; negligible in standard CI pipeline [`V49__neglected_skill_unique_open_constraint.sql`]
- D2: No distributed lock on `@Scheduled` — multi-instance neglected-skill detection race; V49 unique index prevents data corruption but rolls back flag resolutions on the losing node; ShedLock is the correct fix, out of scope for this story [`NeglectedSkillDetectionService.java`]
- D3: All skills flagged neglected for inactive/new player — `actual=0` falls below every coach target; technically correct per AC 4 literal but causes flag-flood on first evaluation; consider a "minimum sessions in the evaluated period" guard in a future UX refinement story [`NeglectedSkillProcessor.java`]
- D4: `slu.neglected.threshold > 1` silently disables all neglected-skill detection — negative `oneMinus` makes `lowerBound` always negative; no range validation on the config value [`NeglectedSkillDetectionService.java:22-27`]
- D5: No upper bound on `findByPlayerIdFromWeek` JPQL query — future-dated snapshot rows from a clock-skew or ingestion error inflate trend data; fix requires an upper-bound year/week filter at ingestion time [`SluWeeklySnapshotRepository.java:21-27`]
- D6: `SluCalculationService` async ISO week boundary race — `now` captured pre-`saveAll`; a session straddling Monday midnight writes SLU rows and snapshot to different ISO weeks — pre-existing design acknowledged in story dev notes [`SluCalculationService.java:177-187`]

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection (2026-06-19)
- W1: Partial snapshot missing if failure occurs between `sluRepository.saveAll` and `snapshotBatchWriter.writeAll` — acknowledged in dev notes; snapshot is eventually-consistent and does not roll back with SLU rows [`SluCalculationService.java:177-187`]
- W2: SluCalculationService week-boundary race — `now` captured before saveAll; a failure spanning midnight ISO week boundary could mismatch iso_week between SLU rows and their snapshot entry; negligible probability
- W3: V48 `INSERT INTO platform_config ON CONFLICT (key) DO NOTHING` silently preserves wrong existing value — pre-existing migration pattern across all stories [`V48__development_exposure_dashboard.sql:43`]

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy — Pass 2 (2026-06-18)
- D1: No retry on saveAll failure — SLU permanently lost on transient DB error; dev notes acknowledge and provide a recovery query; infrastructure-wide limitation [`SluCalculationService.java:165`]
- D2: CallerRunsPolicy can block HTTP thread under executor saturation — prior review explicit tradeoff: AbortPolicy silently drops SLU vs CallerRunsPolicy blocks request thread [`AsyncConfig.java:40`]
- D3: Duration rounding over/under-counts block time — prior review accepted as intentional approximation; documented in dev notes [`SluCalculationService.java:121`]
- D4: Thread.sleep in negative-path IT tests — prior review explicitly deferred; acceptable for negative async tests with no positive signal [`SluCalculationServiceIT.java`]
- D5: No booking_id stored in player_skill_stats — no DB-level idempotency anchor; behavioral gap addressed by idempotency pre-check patch; schema addition out of story scope [`V46__development_module_init.sql`]
- D6: No guard on zero/negative repDensity/intensity metadata fields — pre-existing drill creation validation gap; zero repDensity silently produces no SLU without warning log [`SluFormula.java`]
- D7: NUMERIC(10,4) overflow at extreme session attribute values — theoretical at realistic gameplay values with default 0.10 scales [`SluFormula.java`, `V46__development_module_init.sql`]
- D8: SluRepository inherits deleteAll/deleteById — AC 4 met; comment warns developers; runtime override-to-throw is defense-in-depth only [`SluRepository.java`]
- D9: Skill code case-sensitivity — lowercase skillWeighting keys silently dropped; fix belongs at drill creation (input normalisation), not SLU calculation [`SluCalculationService.java`]

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy (2026-06-18)
- W1: Negative metadata fields (repDensity/intensity/etc.) can produce corrupt SLU via double-negative — pre-existing validation gap at drill creation; fix at DrillMetadata validation layer [`SluFormula.java:45-66`]
- W2: @Async executor naming ambiguity — explicit `@Async("taskExecutor")` qualifier would eliminate uncertainty; largely covered by the AsyncUncaughtExceptionHandler patch [`SluCalculationService.java:43`]
- W3: configService.getString whitespace/empty string causes NumberFormatException → silent SLU abort — pre-existing ConfigService limitation; fix belongs at config write validation [`SluCalculationService.java:78-85`]
- W4: Thread.sleep in negative-path IT tests — acceptable for negative async assertions where no positive signal exists; replace with Awaitility + log spy if flakiness is observed in CI [`SluCalculationServiceIT.java:107,125,135,171`]
- W5: Platform config IDs 70-72 skip 68-69 — intentional gap; no migration uses 68-69; ON CONFLICT DO NOTHING prevents failures [`V46__development_module_init.sql:51-55`]
- W6: player_id and coach_id have no FK constraints on player_skill_stats — intentional for immutable audit rows; cascading deletes would corrupt historical SLU [`V46__development_module_init.sql:19,21`]

## Deferred from: code review of skillars-4-6-homework-assignment-player-locker-room (2026-06-18)
- W1: `getLockerRoomDrills` calls `hasActivePack` once per unique coach (N+1 queries) — performance concern, not correctness; batch API needed; address in a performance-hardening pass [`HomeworkAssignmentService.java:getLockerRoomDrills`]
- W2: Missing composite index on `(player_id, coach_id)` on `homework_assignments` — full player-index scan used for the coach-filter path as data grows [`V45__homework_assignments.sql`]
- W3: `handleBookingCompleted` stores null sessionId with no log.warn — async bean ordering is not guaranteed; add warn log if sessionId resolves null [`HomeworkAssignmentService.java:handleBookingCompleted`]
- W4: `@Size(max=2)` on `WrapUpRequest.homeworkDrillIds` not enforced on event-driven path — HTTP validation is the only entry point today; add size guard in service if other publishers emerge [`HomeworkAssignmentService.java`]

## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates — Round 2 (2026-06-18)
- W1: `deleteTemplate()` no ARCHIVED guard — idempotent re-archive silently succeeds (204) on already-archived template; acceptable behavior [`SessionTemplateService.java:deleteTemplate`]
- W2: `deployTemplate()` passes `t.getBlocks()` by reference not defensive copy — safe in current code path; no mutation after save in same transaction [`SessionTemplateService.java:deployTemplate`]
- W3: `computeFocusScore()` returns 0 for all-unsupported focus values — random subset within age-fit tier (0.10 base score); by-design stub behavior [`DrillSuggestionService.java:computeFocusScore`]
- W4: Template name inputs missing `maxlength="200"` client-side — server `@Size(max=200)` catches it; generic error is acceptable UX [`SessionTemplateVault.vue`, `SessionBuilderPage.vue`]
- W5: `createTemplate()` store action never sets `error.value` on failure — callers handle errors; minimal impact on store error state [`sessionTemplate.store.js`]
- W6: `SessionTemplate.blocks` null risk if `session.getBlocks()` null — `Session.blocks` is NOT NULL in DB so sessions should never have null blocks; constraint prevents [`SessionTemplateService.java:createTemplate`]
- W7: V44 migration no index on `source_template_id` — performance concern for future analytics queries on deployed sessions per template; not needed for current functionality [`V44__session_templates.sql`]
- W8: `deployTemplate()` race condition (duplicate booking, `existsByBookingId` + `save` not atomic) — pre-existing pattern identical to `SessionPlanService.createSession`; deferred from Round 1 [`SessionTemplateService.java:deployTemplate`]

## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates (2026-06-18)
- W1: Race condition: `existsByBookingId` check and `save` not atomic in `SessionTemplateService.deployTemplate` — same pre-existing pattern as `SessionPlanService.createSession`; no DB unique constraint on `session.sessions(booking_id)`; concurrent deploys to the same booking could both succeed [`SessionTemplateService.java:deployTemplate`]

## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna — round 2 (2026-06-18)
- W8: `isBookingPlannable` accepts `"UPCOMING"` but no known code path transitions a Booking to this status — proactive future-proofing, harmless if UPCOMING is never set [`SessionPlanService.java:167`]
- W9: `updateSession` does not re-validate booking plannable status at update time — a booking cancelled after session plan creation can still be updated freely; outside story scope [`SessionPlanService.java:updateSession`]

## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna (2026-06-18)
- W1: COMPLETED status transition never wired from booking completion — `session.status` is set to `COMPLETED` on `createSession` guard but no code path (booking completion event, scheduler, or explicit endpoint) ever transitions a DRAFT/SAVED session to COMPLETED. Cross-story dependency: Story 3.6 booking completion event flow. [`SessionPlanService.java`]
- W2: IT test `updateSession_completedSession` does not assert SESSION_PLAN_LOCKED helpCode in response body — test verifies 403 status but never reads `response.body.helpCode` to confirm the correct error code is returned. Test quality improvement. [`SessionBuilderResourceIT.java:271`]
- W3: `WrapUpSequence` uses `variant="compact"` instead of spec-specified `"full"` — cosmetic deviation; DNA chart renders at 160px instead of 240px in the wrap-up overlay. [`WrapUpSequence.vue:163`]
- W4: `SessionBlockRequest.drills` has no `@Size(max=...)` upper-bound constraint — unbounded drill count per block; a malicious payload could include thousands of drills, causing runaway DNA/equipment computation. Hardening concern, not MVP-blocking. [`SessionBlockRequest.java:16`]
- W5: `WrapUpSequence.fetchSessionDna` has no loading or error state — fires on component mount (step 1 entry), not step 4; chart renders without any spinner while fetching; failed fetch silently leaves chart absent. UX polish. [`WrapUpSequence.vue:309`]
- W6: `buildResponse` calls `drillRepository.findAllById` twice for the same ID set — once inside `resolveMetaMap`, once inside `buildResponse` itself. Redundant DB round-trip for every read. Performance optimization. [`SessionPlanService.java:220`]
- W7: IT teardown `DELETE FROM session.sessions` runs before `DELETE FROM booking.bookings` — safe today because there is no FK between the tables; would fail if a FK is ever added. Future-proofing. [`SessionBuilderResourceIT.java:104`]

## Deferred from: code review of skillars-4-3-custom-drill-uploads — round 2 (2026-06-17)
- W10: `FeatureGatedException` error code not matched by frontend catch block — `startUpload` checks `video.quotaExceeded` and `video.constraintViolated` but not the helpCode produced by `ApiAdvice` for `FeatureGatedException`; requires stale eligibility cache + server gate both firing; generic "upload failed" is not wrong; low probability [`DrillDetailPanel.vue` — `startUpload` catch]

## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)
- W1: Concurrent `initiateUpload` on same drill — two provider video objects created by racing threads; loser's video is orphaned at provider; DataIntegrityViolationException handles DB race but provider allocation happens before the save [`DrillUploadService.java`]
- W2: `existsByVideoId` timing in concurrent `deleteVideo` for shared-video drills — both concurrent deletes may pass the check before either commits, publishing deletion event twice; double-delete is idempotent at Bunny.net; near-impossible in normal usage [`DrillUploadService.java`]
- W3: Transaction rollback after `videoService.initializeUpload` — provider video created, DB transaction rolls back (including UploadSession), so reconciliation worker cannot find the orphaned provider asset [`DrillUploadService.java`]
- W4: `resolveMinUploadTier` depends on `CoachSubscriptionTier` enum declaration order — informational only; used in error message hint, not in access control; wrong hint if enum is not declared in ascending rank order [`DrillUploadService.java`]
- W5: Signed playback URL expires in 2 h but is cached in Pinia store indefinitely — coach must reload to get fresh URL after 2+ hours of idle time; expected signed-URL behaviour [`DrillLibraryService.java`]
- W6: `@Async` on `VideoPhysicalDeletionListener` uses default `SimpleAsyncTaskExecutor` (unbounded threads) — low volume expected; add named executor if burst deletion scenarios emerge [`VideoPhysicalDeletionListener.java`]
- W7: IT test `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` does not verify error code in response body — functional behaviour is correct; test hardening pass [`DrillUploadResourceIT.java`]
- W8: AC 3 "configurable 60-min timeout" not specifically wired to drill uploads — inherits pre-existing `UploadSession.expiresAt` scheduler; not changed by this story [`platform.video` scheduler]
- W9: `@TransactionalEventListener` silently drops events if called outside a transaction — hypothetical only; `DrillUploadService` is `@Transactional` so all call paths have a transaction [`VideoPhysicalDeletionListener.java`]

## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)
- W1: Concurrent fetch race between applyFilters and onTabChange — two in-flight API calls (searchDrills + fetchDrills) can overwrite each other's results; last response wins; address with request ID or AbortController in a UX hardening pass [`DrillLibraryPage.vue`, `session.store.js`]
- W2: sluBreakdown silent 0 for null repDensity — `null * weight = 0` in JS; renders "0 SLU" instead of "—"; Foundation 20 drills all have valid repDensity; add a null guard when coaches can upload custom drills [`DrillDetailPanel.vue`]
- W3: removeTag chip visible for any COACH drill — component assumes ownership from context (correct in PRIVATE tab); defensive concern if DrillCard is reused in a multi-coach admin context [`DrillCard.vue`]
- W4: DrillTagId @Column(name="tag") missing length=50 — JPA default column length is 255 vs DB VARCHAR(50); harmless with schema validation off; add `length=50` if ddl-auto validation is enabled [`DrillTagId.java`]
- W5: COACH vs PRIVATE naming inconsistency — entity/DB stores library_type="COACH"; API param and frontend use "PRIVATE"; pre-existing from Story 4.1; fragile on new developer additions [`DrillLibraryResource.java`, `DrillLibraryService.java`]

## Deferred from: external code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `resolveMinEnabledTier` returns `"NONE"` when all gate config keys are false — misleading required-tier in `FeatureGatedException`; low-probability misconfiguration edge case [`DrillLibraryService.java:103-110`]
- D2: `DrillVideoRef.save()` issues merge (SELECT + INSERT) instead of persist (INSERT-only) — extra SELECT on clone ref insert; no data corruption in normal flow; fix with `Persistable<UUID>` implementation when performance becomes a concern [`DrillLibraryService.java:82`]
- D3: No unique constraint on `(owner_coach_id, name)` — coach can clone the same platform drill multiple times, silently creating duplicates in their private library; UI drill list may show dups [`V38__session_module_init.sql`]

## Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `session` schema name is a PostgreSQL non-reserved keyword — works on all tested PG versions; renaming after migration is written would require a destructive V40 migration [`V38__session_module_init.sql`]
- D2: V39 seed drills use `gen_random_uuid()` — non-deterministic IDs differ between environments; migration already written; deterministic UUIDs would require a V40 fix migration [`V39__session_foundation_20_drills.sql`]
- D3: Feature gate config key format relies on `tier.name()` matching DB key suffix exactly — new tier addition requires a matching migration; acceptable by convention; no compile-time enforcement [`DrillLibraryService.java:86`]
- D4: `POST /api/session/plans` returns 201 empty body — intentional stub per story dev notes; full implementation in Story 4.4 [`SessionPlanResource.java`]
- D5: `DrillLibraryPage.vue` `onMounted` no error handling — stub page; Story 4.2 builds full UI [`DrillLibraryPage.vue:15`]
- D6: New coach with no profile gets `ResourceNotFoundException` → 404 from `getCoachIdByUserId` on private drill list — edge case; Story 4.2 to guard on the frontend; backend always requires a complete profile [`CoachProfileService.java`]
- D7: `listPrivateDrills` no explicit `library_type = 'COACH'` filter — safe today due to DB `chk_drill_owner` constraint preventing PLATFORM drills from having a non-null `owner_coach_id` [`DrillRepository.java`]
- D8: `DrillResponse.ownerCoachId` is always null for PLATFORM drills — nullable contract undocumented; Story 4.2 frontend rendering should null-check [`DrillResponse.java`]
- D9: `ConfigService.getBoolean` no logging when returning false for a present-but-non-"true" value — operational visibility gap for misconfigured (not absent) keys [`ConfigService.java`]

## Deferred from: code review of skillars-3-7-session-pause-resume (2026-06-16)
- D1: SSE race during in-flight pause — if remote resume (SSE `IN_PROGRESS`) arrives while local pause API is in-flight, `watch` restarts timer while `pausing=true`; UI self-corrects on next event; multi-device edge case [`ActiveSessionScreen.vue`]
- D2: SSE heartbeat handler closes/reopens EventSource unconditionally, resetting retry counter while active polling is running — can cause multi-second status gaps; pre-existing in `booking.store.js`
- D3: `elapsed` resets to 0 on component remount; `sessionStartTime` prop is never consumed to reconstruct elapsed time — accumulated active time is lost on browser refresh; pre-existing [`ActiveSessionScreen.vue`]
- D4: `completionLoading` flag shared across pause/resume/end — consumers cannot distinguish which operation is in-flight; component uses local `pausing`/`resuming` refs for buttons so user-visible impact is nil; pre-existing store design [`booking.store.js`]

## Deferred from: code review of skillars-3-6-session-completion-live-mode-quick-complete (2026-06-16)
- W1: JPQL string literal `'COMPLETED'` in `findPendingQuickCompletes` is fragile against `BookingStatus` enum rename — pre-existing pattern project-wide [`SessionCompletionDataRepository.java:22`]
- W2: `currentUserId()` casts `getCurrentUser()` to `Principal` without null guard — same unchecked cast used in all platform Resources; pre-existing [`SessionCompletionResource.java`]
- W3: `BookingCompletedEvent` has no retry/DLQ mechanism if listener fails after commit — infrastructure limitation, pre-existing across all event consumers [`BookingEmailListener.java`]
- W4: `getDrillSuggestions` has no `@Max` constraint on `limit` parameter — stub endpoint fully replaced by Epic 4; guard when real implementation lands [`SessionCompletionResource.java`]
- W5: Auto-return after wrap-up reloads `selectedWeek` instead of current week — minor UX edge case when coach was browsing a different week [`CoachCommandCenterPage.vue:305`]
- W6: V33 migration uses hardcoded `id = 39` for `platform_config` insert — low collision risk given sequential pattern; validate before deploying to environments with manual config inserts [`V33__session_completion_data.sql:3`]

## Deferred from: code review of skillars-3-5-scheduling-views-timezone-management (2026-06-15)
- W1: Revenue gross calculation ignores variable session pricing (pack discounts, multi-session rates) — spec defines gross as `perSessionPrice × count` (AC 2), variable pricing is out of scope; revisit in a pricing-model story [ProjectedRevenueService.java]
- W2: N+1 DB queries in `getParentPlayerSchedule` (coachProfile + credits + in-flight count per booking) — pre-existing codebase pattern shared with `getParentBookings`; address in a performance-hardening pass [BookingService.java]

## Deferred from: code review of skillars-3-4-booking-state-machine-sse (2026-06-15)
- No optimistic/pessimistic lock on `transition()` — concurrent callers can both pass `validate()` on the same booking; add `@Lock(PESSIMISTIC_WRITE)` in a concurrency-hardening pass [BookingService.java:85]
- `getRequestedStartTime()` null not guarded before `ChronoUnit.HOURS.between()` in `applyRefundLogic` — in practice never null (set at creation); add guard if entity nullability changes [BookingService.java:256]
- SSE endpoint accepts subscriptions for terminal-state bookings — emitters accumulate for COMPLETED/CANCELLED/REFUNDED bookings; implement lifecycle-based subscription guard in a resource-management pass [BookingEventResource.java:37]
- `verifyIsParty` has no admin bypass path — no admin role exists yet; revisit when admin management stories are implemented
- Negative `hoursUntilSession` for past-session cancellations silently maps to NONE — probably correct but undocumented; add an explicit branch or comment [BookingService.java:256]
- Polling fallback has no exponential backoff — 2 s fixed interval is spec-prescribed degraded mode; add backoff if hammering becomes observable in production [booking.store.js]
- `isCoachParty()` returns generic 403 when coach profile is deleted — indistinguishable from unauthorized third-party; improve error when coach-account-deletion story is implemented [BookingEventResource.java:70-73]
- Dead `CANCELLED` entry in `BookingStateChip.statusMap` — harmless graceful-degradation fallback; clean up after data migration is confirmed complete [BookingStateChip.vue]
- `PAYMENT_FAILED` sets no `refundEligibility` — `null` is intentional; Epic 7 handles payment-failure refund logic independently [BookingService.java:applyRefundLogic]
- `useBookingSse()` not wired into `BookingStateChip` — SSE wire-up deferred to consuming page/component story; chip will be connected when the parent booking detail page is built

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group E (2026-06-15)
- `getParentBookings_returnsListSortedByStartTime` IT test asserts only HTTP 200 + non-null body — sort order never verified; needs 2+ bookings in reverse chronological order + `extracting("requestedStartTime").isSorted()` assertion; a regression removing the `OrderBy` from the JPA method would not be caught [BookingRequestResourceIT.java:480]
- `parentName` field not asserted in `getCoachBookingRequests` IT test — AC 8 requires parent name on coach inbox rows; `response.getBody().get(0).get("parentName")` never checked [BookingRequestResourceIT.java:524]
- Authority id 9502 leaked in `playerNotOwnedByParent_returns403` test — `finally` block cleans user + user_authority but not the authority row; `@AfterEach` only deletes ids 9500, 9501; add `DELETE FROM main.authority WHERE id = 9502` to the finally block [BookingRequestResourceIT.java:289]
- `declineBooking` unit test uses `any(BookingDeclinedEvent.class)` — `canonicalTimezone` field not captured/asserted via `ArgumentCaptor`; regression where timezone is null would pass [BookingServiceTest.java:244]
- No wrong-coach IT test for `declineBooking` — `acceptBooking_wrongCoach_returns403` exists but no equivalent for the decline endpoint; role-guard misconfiguration on that path would go undetected [BookingRequestResourceIT.java]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group D (2026-06-15)
- `canonicalTimezone` sent as parent's browser timezone — session time in coach notification email shown in parent's TZ, not coach's; canonical timezone for a session should be the coach's timezone; revisit in Story 3.5 (Scheduling Views & Timezone Management) [BookingRequestPage.vue:121]
- `formatSlot()` in BookingRequestPage uses `toLocaleString()` with no timezone — slots display in parent's local time, not coach's timezone; inconsistent with ParentBookingsPage which uses `{ timeZone: timezone }`; address in Story 3.5 [BookingRequestPage.vue:104]
- No user-visible error feedback on `handleAccept`/`handleDecline` failure — button spinner stops but no toast/snackbar is shown; coach cannot distinguish success from silent failure; add error notification in a UX polish story [CoachBookingRequestsPage.vue:75-92]
- `ParentBookingsPage` error state (`bookingsError`) is captured but never rendered — user sees empty-state message on API failure; add error branch to template in a UX polish story [ParentBookingsPage.vue]
- `submitBookingRequest` error path in BookingRequestPage — 400/403/network failures leave user on page with no feedback; add error notification in a UX polish story [BookingRequestPage.vue:112-128]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group C (2026-06-15)
- `ShortCode.shortenInt(UUID.randomUUID().hashCode())` as `Envelope` idempotency key — 32-bit int space has ~77k birthday collision threshold; collision causes `DataIntegrityViolationException` in `MailManager`, which retries the conflicting sendId and corrupts the original envelope's delivery record; replace with full UUID or at minimum a 64-bit random value; pre-existing across all notification types [BookingEmailListener.java]
- `isRetryable(exception)` in `MailManager` checks the wrapping `RuntimeException` instance, not the cause — a `MessagingException` wrapping a `MailParseException` is marked retryable and retried to exhaustion despite being non-recoverable; fix: unwrap cause before checking [MailManager]
- `((Principal) securityUtil.getCurrentUser())` unchecked cast in `BookingResource` returns HTTP 500 (`ClassCastException`) if the security principal is an unexpected type; add `instanceof` guard and return 401 with a structured error; pre-existing pattern in all platform controllers [BookingResource.java:70,82]
- No cross-field `@AssertTrue` on `CreateBookingRequest` enforcing `requestedEndTime > requestedStartTime` — currently caught at service layer with a security-semantics error (`OperationNotAllowedException`) instead of a 400 Bad Request; add class-level constraint to return proper validation error [CreateBookingRequest.java]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)
- No duplicate-booking guard for same slot — multiple REQUESTED bookings for same player/coach/timeslot are possible; credit soft-reservation handles the economic constraint; add a unique partial index on (player_id, coach_id, requested_start_time) WHERE status IN (...) in a future scheduling-conflicts story [BookingService.java:createBookingRequest, V31 migration]
- `resolveEmail` silently returns "" for missing/deleted users — event is published with blank recipient; downstream email sender logs the failure; add a warn-log at resolveEmail call sites or return Optional if blank-email delivery failures need observability [BookingService.java, BookingExpiryScheduler.java, BookingReminderScheduler.java]
- N+1 player name + credit queries in `getParentBookings` — already tracked from Group A
- All availability windows have invalid timezone → misleading 403; add a distinct error code or admin-visible flag when no valid windows exist vs. slot outside valid windows [BookingService.java:isSlotWithinAvailabilityWindow]
- Midnight-crossing sessions fail/pass incorrectly in availability window check because endZdt.toLocalTime() wraps past midnight; add explicit day-boundary guard when requestedEnd < requestedStart (in LocalTime) [BookingService.java:228-232]
- DST transition can shift booking time by 1h relative to window boundary; acceptable for current scope; revisit when timezone management (Story 3.5) is implemented [BookingService.java:isSlotWithinAvailabilityWindow]
- `configService.getLong()` throws on missing key, silencing that scheduler tick; consider a `getLongOrDefault(key, default)` overload so schedulers degrade gracefully if config is accidentally deleted [BookingExpiryScheduler.java:35, BookingReminderScheduler.java:35-36]
- `w.getDayOfWeek()` vs JS 0-based day format — verify that the availability-windows frontend sends ISO 1-7 (not JS 0-6); pre-existing from Story 3.1 [BookingService.java:230, CreateWindowRequest.java]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group A (2026-06-15)
- `requestedEndTime` minimum duration not validated — 1-second bookings accepted; minimum session length not in scope for Story 3.3; add a `@PositiveDuration(min=15m)` or service-level check in a future session-constraints story [CreateBookingRequest.java:16]
- `canonicalTimezone` not IANA-validated before storage — invalid string passed by client causes DateTimeException at reminder notification time; add `ZoneId.of(canonicalTimezone)` validation in BookingService.createBookingRequest [CreateBookingRequest.java:17, BookingService.java]
- N+1 queries in `getParentBookings` — player names and effective credits each fire separate SQL per booking row; batch player name lookup the same way coach names are batched; catch when booking volume per parent grows [BookingService.java:getParentBookings]
- `@Slf4j` missing on `SessionPackService` — pre-existing omission; violates project-wide "@Slf4j for all services" rule; fix in any story that touches SessionPackService next [SessionPackService.java]

## Deferred from: code review of skillars-3-2-session-pack-purchase-credit-dashboard (2026-06-13)
- Payment charged before record persisted — `capturePayment` called before `repository.save`; `@Transactional` does not roll back external gateway call. Stub safe now; fix with idempotency key when Stripe is wired in Epic 7. [SessionPackService.java:purchasePack+purchaseSingleSession]
- Payment reference (transaction ID) never stored — return value of `capturePayment` discarded; no `payment_reference` column in schema. Add column and persist in Epic 7. [SessionPackService.java + V30 migration]
- `deductCredit()` has no `parentId` re-authorization — acknowledged by TODO(3.3); Story 3.3's `BookingService` must only supply a verified `playerId` from a committed booking entity. [SessionPackService.java:deductCredit]
- No concurrency integration test for `deductCredit` / pessimistic lock — `SELECT FOR UPDATE` correctness not exercise by real concurrent transactions. Add in a dedicated testing story or before Story 3.6 wires `deductCredit`. [SessionPackResourceIT.java]
- `@Sql(SecurityIT.SEC_DATA_SQL_PATH)` ordering dependency undocumented in `SessionPackResourceIT` — pre-existing pattern from 3.1; document what ID ranges SEC_DATA_SQL_PATH uses to prevent collision. [SessionPackResourceIT.java]
- `tearDown` deletes `main.sec` / `main.refresh_tokens` without `WHERE` — unconditional delete matches other IT classes; revisit if tests ever run in parallel against a shared DB. [SessionPackResourceIT.java:tearDown]
- `PlayerProfileRepository.findById` used instead of `findByIdAndParentId` in `verifyPlayerOwnership` — diverges from the repo's family-isolation contract; not a correctness bug now. [SessionPackService.java:verifyPlayerOwnership]
- No DB-level state machine constraints — `ACTIVE+credits=0` or `EXHAUSTED+credits>0` not prevented at DB layer; enforce with additional `CHECK` constraints when the status lifecycle is fully stable. [V30__booking_session_packs.sql]
- In-memory `coachId` filter in `getPacksForPlayer` — loads all packs for parent+player then Java stream filters by coachId; push filter into SQL when pack volumes grow. [SessionPackService.java:getPacksForPlayer]

## Deferred from: code review of skillars-3-1-coach-availability-management (2026-06-13)
- Block spans midnight → negative CSS height in WeeklyCalendar overlay — multi-day block rendering is out of scope for Story 3.1 ACs; handle when calendar becomes a product priority [WeeklyCalendar.vue:1652-1668]
- `getAvailabilityCalendar` timezone-expansion logic (LocalTime + canonicalTimezone → Instant) not unit-tested — IT tests cover it implicitly; add targeted unit test when timezone bugs appear or before Story 3.5 timezone management work [AvailabilityServiceTest.java]
- No date-range guard on `weekStart` GET parameter — far past/future dates are harmless for a 7-day view; address if API is ever exposed to untrusted external callers [AvailabilityResource.java:421]

## Deferred from: code review of skillars-2-4-contact-detail-sanitization-ux (2026-06-13)
- Phone regex false positives — `PHONE_PATTERN` can match dates and numeric prose (e.g. "49-60 EUR") in bio text; no false-positive boundary test exists [ContactDetailSanitizer.java]
- `wasModified` semantics with sequential email-then-phone substitution — phone regex runs on already-redacted string; edge case may cause unexpected detection flag behavior [ContactDetailSanitizer.java]
- Duplicate i18n key `auth.coach.bioSanitizationWarning` (near-identical to `contactDetailWarning`, trailing period differs) — unused by this story but will silently diverge if either string is updated [src/frontend/src/i18n/en/index.js]

## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)
- N+1 queries — `getPublicProfile` fires 8 sequential DB round-trips; acceptable for single-entity load now, but batch loading or `@EntityGraph` should be considered before Epic 3 traffic ramp [CoachProfileService.java]
- Floating-point savings math in `SessionPackTracker.vue` — `perSessionPrice * sessionCount - totalPrice` uses IEEE 754 arithmetic; add a currency library (e.g. `currency.js`) before pack discounts are prominent in UI [SessionPackTracker.vue]
- `CoachMediaItem.uploadedAt` uses field initializer `OffsetDateTime.now()` — consistent with existing `CoachProfile.createdAt` pattern but should migrate to `@CreationTimestamp` codebase-wide [CoachMediaItem.java]
- `UNIQUE (coach_id, display_order)` makes naive gallery reorder impossible without a temp value; make the constraint `DEFERRABLE INITIALLY DEFERRED` or redesign reorder API in the media management story [V28__marketplace_coach_media.sql]
- `aggregateRating`/`reviewCount` hardcoded to `0.0`/`0` — wire to reviews aggregate in Epic 9 [CoachProfileService.java]
- `long → int` cast on `strikeCount` — replace with `Math.toIntExact()` to catch overflow explicitly if strike volume ever grows large [CoachProfileService.java]
- Test `unknownId_returns404` uses `assertThatThrownBy().isInstanceOf(HttpClientErrorException.class)` — add `satisfies()` on the outer exception to verify status 404 before the inner cast, so a 5xx surfaces a cleaner failure [CoachProfileResourceIT.java]
- `VerificationBadge.vue` tooltip presence — verify the existing component already includes tier-explanation tooltip (AC 2); if not, add it in a follow-up [CoachPublicProfilePage.vue]

## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)
- Flyway V25 hardcoded IDs 112–114 — `ON CONFLICT (key) DO NOTHING` does not guard against PK collision if those IDs are already taken by different rows with different keys; spec explicitly verified the ID range is safe; established codebase Flyway seed pattern [V25__age_policy_config_seed.sql:1–6]

## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)
- Tests use raw `jdbcTemplate` inserts instead of Instancio for test data — project rule violation but tests are functionally correct [AuthResourceIT.java]
- `AuthResourceIT` lacks `@Testcontainers` annotation — may be managed via inherited `TestConfig` or `SecurityIT` base class; verify before next review [AuthResourceIT.java]
- `@Observed` at class level vs per-method on `AuthResource` — class-level is a valid Micrometer pattern; no metric data lost [AuthResource.java]
- `refresh_alreadyUsedToken` test does not assert `Set-Cookie: rtkn=; Max-Age=0` in the 401 response — minor AC2 coverage gap [AuthResourceIT.java]
- `ROLE_ROUTES` duplicated across `LoginPage.vue` and `router/index.js` — DRY violation; divergence would cause infinite redirect loop, but no current divergence
- `fr-FR` locale may be missing `auth.coach` sub-tree — investigate whether gap is pre-existing from a prior story [i18n/fr-FR/index.js]
- `hydrated` flag in router factory is closure-scoped — SSR-unsafe but app is SPA only [src/frontend/src/router/index.js]
- Client-side `skp` clear in `auth.store.logout()` is redundant — server `logout()` already sends `Set-Cookie: skp=; Max-Age=0`; the `document.cookie` write is belt-and-suspenders [auth.store.js]

## Deferred from: code review of skillars-1-4-parent-registration-player-profiles-shadow-accounts Group 1 (2026-06-12)
- OTP hash uses `SHA-256(otp+userId)` no separator — same pre-existing pattern as CoachRegistrationService (already tracked Story 1.3 Group B D3); rate limiting is primary mitigation [ParentRegistrationService.java — hashOtp]
- `verifyEmail` saves `activated=true` before optimistic-lock check — correctly rolled back by `@Transactional`; same pattern as CoachRegistrationService; would break if called inside `REQUIRES_NEW` propagation [ParentRegistrationService.java:129–137]
- `PhoneNumber("XX")` hardcoded country placeholder — intentional per Dev Notes; same as coach flow (Story 1.3 Dev Notes) [ParentRegistrationService.java:98]
- Migration IDs 100–102 in `platform_config` — different table from V21's authority rows; `ON CONFLICT (key) DO NOTHING` is correct idempotency guard [V22__parent_player_shadow_accounts.sql]
- `dateOfBirth = LocalDate.of(1900, 1, 1)` parent user placeholder — intentional per Dev Notes; same pattern as coach (Story 1.3 Dev Notes) [ParentRegistrationService.java:102]
- Age tier snapshotted at creation, never recomputed as child ages — by design per spec; explicit consent-escalation update deferred to Story 1.6 [PlayerProfile.java; ShadowAccountService.java]
- `@Past` constraint allows 1-day-old player DOB; no minimum player age enforced — not in scope per spec; no AC addresses minimum player age [CreatePlayerProfileRequest.java:12]
- OTP rate-limit key is `userId` only — expired-OTP resubmissions drain legitimate user's budget; same pre-existing pattern as CoachRegistrationService (Story 1.3 Group B D2) [ParentRegistrationService.java:154]
- Phone-collision detection via `msg.contains("phone")` — DB-dialect fragile; same pre-existing pattern as CoachRegistrationService [ParentRegistrationService.java:100–104]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group D (2026-06-11)
- D1: `/verify-email` endpoint not rate-limited — large UUID space; already tracked Group A D6; acceptable risk [CoachRegistrationResource.java]
- D2: Rate limit consumed before user table lookup in `verifyPhone` — targeted bucket exhaustion possible; design limitation of public OTP endpoints; mitigated by per-userId keying [CoachRegistrationService.java:145–147]
- D3: `SUSPENDED` user in `EMAIL_VERIFIED` state can complete phone OTP — no suspension code exists yet; guard should be updated when suspension story is implemented [CoachRegistrationService.java]
- D4: SES failure during `/resend-verification` creates valid DB token with no email delivery — logged at ERROR; resend button available [CoachRegistrationEmailListener.java]
- D5: Frontend 60s cooldown resets on page refresh — UI-only throttle; server-side rate limit is authoritative [CoachEmailPendingPage.vue]
- D6: `ContactDetailSanitizer` double-redaction edge case — phone pattern can match trailing digits in already-redacted string; benign, no exploitable effect [ContactDetailSanitizer.java]
- D7: `ON CONFLICT (name) DO NOTHING` in authority seed does not protect against PK collision on `id` — already tracked Group A D4; id=100/101 safe for this project [V21__skillars_security_extension.sql]
- D8: `verifyEmail` response leaks internal userId as URL query param — already tracked Group C D1; spec-mandated (AC4); mitigated by per-userId rate limiting [CoachRegistrationService.java:142, CoachEmailVerifyPage.vue:72]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group C (2026-06-11)
- D1: userId in URL query param as tamper vector — spec-mandated design (AC4); mitigated by per-userId rate limiting (Group B P4) [CoachEmailVerifyPage.vue, CoachPhoneVerifyPage.vue]
- D2: GET with token in query string exposes token to server logs/Referer — spec-mandated endpoint design (AC4); single-use token mitigates
- D3: sessionStorage fragility / cross-device flow — architectural limitation of spec-prescribed flow; out of scope for story 1.3
- D4: useContactDetector PHONE_RE may false-positive on numeric strings in name fields — low practical risk in practice
- D5: OTP handlers reimplemented instead of reused from OtpPage.vue per spec Dev Notes — functionally equivalent; refactor candidate
- D6: useContactDetector not applied to phone field — less relevant; spec doesn't require it here
- D7: canResend read directly from err.response.data bypassing parseApiError — works correctly; architectural cleanup is future work
- D8: resendSuccess banner implies email was always sent — intentional anti-enumeration security design
- D9: auth.firstName/validation.* absent from en/index.js — false positive: app default is en-US; en falls back to en-US for these keys
- D10: --accent-warning CSS token confirmed present at _colors.scss lines 31 and 88

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group B (2026-06-11)
- D1: verifyPhone caller-supplied userId with no ownership binding — spec-required design; rate limiting is primary mitigation [VerifyPhoneRequest.java]
- D2: IP-keyed rate limiting timing oracle on /resend-verification — pre-existing RateLimitingService limitation [CoachRegistrationService.java]
- D3: OTP hash SHA-256(otp+userId) no random salt — spec-prescribed; already tracked as W1 [CoachRegistrationService.java:hashOtp]
- D4: Hardcoded DOB(1900,1,1) and Gender.OTHER placeholders persisted to DB — spec-acknowledged; cleaned up in Story 2.1 [CoachRegistrationService.java]
- D5: registerCoach returns void not CoachRegistrationResult — intentional simplification; void sufficient for current ACs [CoachRegistrationService.java]
- D6: resendVerificationEmail deletes unused tokens instead of marking used=true — deletion achieves invalidation intent [CoachRegistrationService.java:168]
- D7: Hardcoded BIGINT test fixture IDs risk TSID collision — low probability, acceptable in test-only code [CoachRegistrationResourceIT.java]
- D8: SecureRandom re-instantiated per generateOtp() call — low severity performance concern [CoachRegistrationService.java:generateOtp]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group A (2026-06-11)
- D1: BIGINT PK with no DB sequence — pre-existing @Tsid pattern; direct SQL inserts require manual TSID generation [V21__skillars_security_extension.sql]
- D2: verification_status unconstrained VARCHAR(20) — no CHECK constraint; pre-existing pattern for enum-backed columns [V21__skillars_security_extension.sql]
- D3: SES region hardcoded eu-west-1 in SesProperties, not overridden in application-prod.yaml — deployment config concern [SesProperties.java, application-prod.yaml]
- D4: Authority id 100/101 magic numbers — PK collision if authority sequence reaches these values; ON CONFLICT (name) DO NOTHING does not protect against PK clash with different name [V21__skillars_security_extension.sql]
- D5: phone_otp_tokens no partial unique index on active OTPs — multiple valid OTPs possible if service doesn't invalidate old tokens first; verify in Group B [V21__skillars_security_extension.sql]
- D6: verifyEmail endpoint not @RateLimited — brute-force UUID token space; Group B code [CoachRegistrationService.java]
- D7: resendVerificationEmail accepts EMAIL_VERIFIED users and re-triggers email verification instead of OTP step — flow regression; Group B code [CoachRegistrationService.java]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)
- W1: OTP hash uses `SHA-256(otp+userId)` — 6-digit OTP space vulnerable to offline pre-computation if DB is breached; hash scheme is spec-prescribed; rate limiting on `/verify-phone` is primary mitigation [CoachRegistrationService.java:hashOtp]
- W2: `verifyPhone` accepts caller-supplied `userId` with no ownership binding — spec-required field; risk mitigated by rate limiting [VerifyPhoneRequest.java]
- W3: SES conditional bean: unrecognized value for `app.ses.enabled` (e.g. `enabled: yes`) leaves `SesEmailService` unwired at startup [SesConfig.java, SesEmailServiceImpl.java]
- W4: `BaseEntity` TSID + V21 `BIGINT PRIMARY KEY` with no sequence — direct SQL inserts in future migrations or test fixtures require manual TSID generation [V21__skillars_security_extension.sql]
- W5: `ContactDetailSanitizer.PHONE_PATTERN` may redact digit-heavy name segments (e.g. "Type 2 Analyst") — pattern is spec-prescribed; refine when real-world false positives are observed [ContactDetailSanitizer.java]
- W6: `RateLimitingService` uses in-process `ConcurrentHashMap` — not cluster-safe, no eviction; pre-existing infrastructure issue not introduced by this story
- W7: `TokenErrorResponse.errorKey` field alignment with `useErrorHandler` composable — confirm when applying patches; likely aligned by naming convention [ApiAdvice.java]
- W8: `EMAIL_VERIFIED` users have no path to re-request phone OTP via `/resend-verification` — resend endpoint intentionally scoped to email verification only; add dedicated `/resend-otp` endpoint in a later story

## Deferred from: code review of skillars-1-2-skillars-design-system-foundation (2026-06-11)
- W1: `.glass-card` still uses `transition: all` — inconsistent with `.hover-lift` narrowed to `transform + box-shadow` in this story; pre-existing in glass.scss, out of story scope [src/frontend/src/css/glass.scss]
- W2: `auth`, `profile`, `session` keys missing from `en`/`de` locale stubs — pre-existing template strings not added by this story; `en-US` fallback handles them at runtime [src/frontend/src/i18n/en/index.js]
- W3: `app-bg` class has no boot-failure fallback in `App.vue` — boot file is the canonical owner per spec design; fallback in App.vue would duplicate logic; acceptable exceptional-case gap
- W4: `onSessionExpired` in MainLayout clears username but does not redirect to `/login` — pre-existing behaviour not introduced by this story
- W5: `variables.scss` dual import path — `app.scss` imports `tokens/colors` directly AND `variables.scss` also forwards to `tokens/colors`; any file that @imports `variables.scss` picks up colour tokens twice; latent build-warning risk
- W6: Rapid double-click theme toggle can briefly desync DOM attribute and `darkMode` ref — `toggleTheme` is synchronous so window is negligible in practice; acceptable
- W7: No CSP header coverage for `fonts.googleapis.com` — infrastructure/deployment concern outside story scope

## Deferred from: code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11)
- `IllegalStateException` → HTTP 409 semantically wrong for missing config keys (should be 500 for misconfiguration); pre-existing ApiAdvice mapping not introduced by this story [ApiAdvice.java:existing illegalStateExceptionHandler]
- `refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409 instead of serving stale data during DB outage; acceptable design choice for this scope [ConfigService.java]
- Scheduled refresh + lazy TTL `ensureFresh()` can both fire near-simultaneously, causing ~2x DB polls per TTL period; minor efficiency concern, spec-designed dual-refresh pattern [ConfigService.java]
- IT test fixture hardcodes bcrypt hash for test user seed SQL; follows existing project IT test pattern [ConfigResourceIT.java:setUp]

## Deferred from: code review of deploy-3-4-operational-documentation-suite (2026-06-05)
- Integrity check (table count ≥ 1) is trivially weak — a partially-loaded dump that created only one table passes; pre-existing restore-from-dump.sh limitation [docs/deployment/backup-restore.md]
- DROP DATABASE may fail if services other than `app` hold open DB connections — script stops only `app` before drop; pre-existing script limitation [docs/deployment/backup-restore.md]
- Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions — upstream UID changes (historically seen with Grafana) would silently break subdirectory ownership after snapshot restore [docs/deployment/backup-restore.md]
- /tmp space check in restore-from-dump.sh validates compressed dump size only — decompressed SQL is typically 5-10x larger; mid-restore /tmp exhaustion possible; pre-existing script gap [docs/deployment/backup-restore.md]
- APP_CID capture races container registration immediately after `docker compose start app` — health-wait loop can time out on a healthy app; pre-existing script race condition [docs/deployment/backup-restore.md]
- WebhookPermanentFailure Admin API re-trigger has no endpoint or auth reference — Admin API not defined in this story's scope; needs dedicated API documentation [docs/deployment/monitoring.md]
- CallbackRateZero public callback endpoint undocumented — application-specific URL not defined in deployment docs; needs a secrets-reference or application guide entry [docs/deployment/monitoring.md]

## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)
- Double notification risk if Alertmanager added later — Prometheus rules and Grafana alerting both evaluate the same infra alerts; currently no Alertmanager so only Grafana notifies, but future Alertmanager addition would cause duplicate ops notifications for every infra alert
- CallbackFailureRatioHigh divide-by-zero on zero callback traffic — pre-existing rule divides rate by rate with no zero-denominator guard; fires spuriously during quiet periods [deploy/lgtm/alerts.yml]
- node_exporter network isolation — shares `skillars-internal` network with app containers; port 9100 reachable by any compromised container; FR-9 required this topology, changing it is out of scope
- Empty notification vars cause silent delivery failure — if `GF_ALERT_NOTIFY_EMAIL` or `GF_SLACK_WEBHOOK_URL` are empty (compose defaults), Grafana provisions the contact point but notifications silently fail; intentional spec design tradeoff (`${VAR:-}`)
- DiskDataVolumeHigh requires Hetzner Volume mounted at `/opt/skillars/data` — if volume not provisioned, no metrics series exists and alert never fires; infrastructure provisioning dependency

## Deferred from: code review of deploy-3-2-scripted-restore-process (2026-06-04)
- fstab not updated after snapshot restore — new volume mounted with `mount /dev/sdb ...` but no fstab update; volume won't auto-mount on reboot if volume UUID changed. Beyond Task 2 scope [restore-from-snapshot.sh:62]
- DOMAIN sourced from .env in restore-from-snapshot.sh but never used — spec says "source .env for DOMAIN" but no reference to DOMAIN in script [restore-from-snapshot.sh:19]
- App and DB left in partial state on mid-restore failure — no recovery trap by design; operator must manually restart the app service and investigate [restore-from-dump.sh:90]
- /dev/sdb hardcoded, no filesystem UUID verification — per spec; operator confirms correct attachment at the Hetzner Console ENTER prompt [restore-from-snapshot.sh:23]

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) — spec-prescribed pattern; would require Docker secrets or a wrapper script to fix [deploy/backup/pg-backup.sh:22]
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story
- No retention policy — S3 dumps and Hetzner snapshots accumulate unbounded; add lifecycle rules or a rotation script in a future backup hardening story
- install-crons.sh installs cron for the invoking user with no enforcement — typically root; document the expected user or add a guard in a future hardening pass
- No upload integrity check (checksum / ETag verification after aws s3 cp) — out of scope for this story
- No handling for Hetzner API HTTP 409 (action in progress) or 422 (quota exhausted) in volume-snapshot.sh — out of scope for this story
- awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — spec-approved as sufficient; revisit if upload failures occur in production

## Deferred from: code review of deploy-2-3-deployment-rollback-documentation (2026-06-04)
- No pre-deploy GHCR image existence check — no step to verify the image tag exists in GHCR before triggering deploy; typo causes mid-run failure after 2–5 min wait.
- No GHCR auth failure handling — no guidance if `docker login` fails (expired PAT, wrong token scope) before `docker compose pull`.
- Step 5 health check retry loop is manual — "retry after 10 seconds" gives no command to re-run; a simple loop would be deterministic [rollback.md:139–142].
- Partial pull failure leaves .env inconsistent — if `docker compose pull` times out, .env holds new tag but image not available; no recovery path documented [rollback.md:106].
- 60s smoke test window vs. `start_period: 60s` — docker-compose.yml sets start_period to 60s; slow JVM startup can exhaust all 12 smoke test retries during startup grace period, triggering false Auto-Revert [deploy.yml + docker-compose.yml].
- Auto-Revert fails if previous image deleted from GHCR — GHCR retention policies can evict old images; Auto-Revert pull then fails with `outcome=failed` and production may be in unknown state.
- `SSH_KNOWN_HOST` empty or multi-line edge cases — empty secret bypasses known-host verification; multi-line `ssh-keyscan` output is valid but undocumented [deploy.yml:27].
- Container name `skillars-app-1` hardcoded in expected output without explaining Docker Compose naming convention (project-service-index) [rollback.md:113].
- No explicit guidance if `docker compose pull app` fails mid-execution — the `&&` chain halts correctly, but no next-step is documented for auth errors, network timeout, or image-not-found.

## Deferred from: code review of deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert (2026-06-04)
- `Fail workflow` step is unreachable if a notification step throws — job still fails (attributed to the notification step instead), same end outcome, low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143].

## Deferred from: code review of deploy-2-1-automated-ci-build-pipeline (2026-06-04)
- No PR trigger — a broken `Dockerfile` or workflow is only discovered after merge to `main`; no pre-merge build validation. Add a `pull_request:` trigger (build-only, no push) in a future CI hardening pass.
- Actions pinned by floating tag not SHA digest — `checkout@v4`, `login-action@v3`, `build-push-action@v6` are mutable tags; a force-push to any tag is a supply-chain attack vector. Pin to immutable commit SHAs.
- ~~No `HEALTHCHECK` in `Dockerfile`~~ — **RESOLVED 2026-06-04**: added `HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3` using the actuator health endpoint.
- ~~No `--platform` flag on builder stage~~ — **RESOLVED 2026-06-04**: added `--platform=linux/amd64` to both `FROM` stages in `Dockerfile`.
- No `SPRING_PROFILES_ACTIVE` in `ENTRYPOINT` — the container boots on the base profile; prod-specific beans and any config not overridden by environment variables silently use dev defaults. Recommend documenting the required env var in the Compose service definition.
- No stable/latest symbolic tag alongside the SHA tag — downstream scripts and Helm charts must be updated on every push or they silently run stale images; a `main` or `latest` tag would provide a stable pointer.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)
- Repo cloned to `/opt/skillars` before Hetzner Volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile if repo structure changes.
- `acme.json` lives on root disk — server rebuild loses all TLS certificates; Let's Encrypt rate limits make reissuance slow; no backup or restore guidance exists.
- `ufw` installed by `provision.sh` but never enabled — Hetzner-level firewall is the sole protection layer with no host-level fallback [deploy/provision.sh].
- Redis data on named Docker volume (root disk), not Hetzner persistent Volume — session/cache data lost on server rebuild [docker-compose.yml].
- No outbound firewall rules — observability containers (Prometheus, Loki, Tempo, Redis) have unrestricted internet egress; security hardening enhancement.
- Docker Hub unauthenticated pull rate limits not documented — shared Hetzner egress IPs can hit the 100/6h limit; rare but unmitigated.
- Partial `provision.sh` failure recovery undocumented — `set -euo pipefail` exits on first error; re-run may silently skip a broken install block [deploy/provision.sh].
- No rollback procedure documented for a bad `APP_IMAGE` deploy when Flyway migrations have already run — operational concern for Epic 3.
- Loki (720h), Tempo (336h), Prometheus (15d) retention periods inconsistent and undocumented — no disk sizing or tuning guidance [deploy/lgtm/].
- `docker-compose-lgtm.yaml` in repo root has anonymous Grafana auth enabled and ports exposed — not warned against production use; dev-only artifact.
- No secret rotation procedure documented (PostgreSQL password, JWT secret, Grafana admin password) — ongoing operational maintenance concern.
- JWT_SECRET minimum length stated (64+) but Spring algorithm and actual enforcement not documented — application implementation detail.
- Grafana admin initial login not explicitly verified as part of Step 7 deployment completion check.
- `provision.sh` re-run while stack is live runs `chown -R` over live data mounts — safe with current UIDs but fragile on container image UID changes.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)
- Firewall applied after provisioning — SSH port 22 is open to all internet IPs during the provisioning window. Deliberate ordering constraint (Hetzner firewall requires local hcloud CLI run, user may not have local clone yet). Consider documenting the exposure window or restructuring for users who already have a local clone.
- `/dev/sdb` hardcoded device path unreliable on multi-volume servers — if Hetzner changes device assignment order the mount silently fails. The doc accuracy fix is a patch (see F2); fixing the script is Story 1.1 territory [deploy/provision.sh:145].
- Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data and secrets. Pre-existing architectural decision; would require a deploy-user or sparse-checkout approach to change.
- `bantime=3600s` in fail2ban is a minimal starter value — inadequate for production. 1-hour bans are bypassed by slow-rate botnets. Pre-existing Story 1.1 config [deploy/provision.sh].
- No rollback / disaster-recovery documentation — explicitly out of scope for Story 1.5; belongs to Epic 3 (Stories 3.2 and 3.4).
- git clone root (`/opt/skillars`) contains the volume data subdirectory (`/opt/skillars/data`) — `git clean` could interact with data dirs if `.gitignore` coverage lapses. Pre-existing architecture.
- `apply-firewall.sh` accumulates old SSH allowlist rules when re-run with a different `SSH_ALLOWLIST_IP` — delete step targets `0.0.0.0/0` source, not the previously-set specific CIDR. Pre-existing script bug from Story 1.1 [deploy/firewall/apply-firewall.sh].

## Deferred from: code review of deploy-1-4-security-hardening (2026-06-03)
- `err()` writes to stderr — lost in stdout-only log capture; if callers redirect stdout to a log file, error messages won't appear in it [deploy/provision.sh].
- `touch` will error without parent dir if sections are reordered — parent dir (`${DEPLOY_ROOT}/traefik`) is created in section 5 which runs first; only an issue if the script structure is modified [deploy/provision.sh].

## Deferred from: code review of deploy-1-3-lgtm-observability-stack Round 2 (2026-06-03)
- `chown` calls in provision.sh run unconditionally on every execution — safe for first provision, but re-running against a live system can interrupt in-progress container writes; document script as "first provision only" [deploy/provision.sh].
- `${MOUNT_POINT}/postgres` has no `chown` after `mkdir -p` — Postgres (UID 999) will fail to write on a fresh volume. Pre-existing from Story 1.2; fix in Story 1.4 or a dedicated housekeeping ticket [deploy/provision.sh:126].
- Duplicate logical alert definitions for PaymentFailureRateHigh, OrangeCircuitBreakerOpen, MtnCircuitBreakerOpen exist in both `alerts.yml` (Prometheus rules) and `grafana-alerts.yml` (Grafana unified alerts) — different notification paths with no Alertmanager wired; revisit when Alertmanager is added to avoid double-paging.

## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)
- Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh, PaymentFailureRateHigh) in `deploy/lgtm/alerts.yml` — pre-existing in root `alerts.yml`; copied per spec. Guards like `and (...) > 0` needed on all ratio denominators.
- `DbConnectionPoolHigh` alert has no label selector — pre-existing in root `alerts.yml`. Add `by (pool)` clause or label filter.
- TraceID regex `[a-f0-9]{32}` only matches lowercase hex; OTel SDKs may emit uppercase. Pre-existing in root `grafana-datasources.yml`.
- `spanStartTimeShift`/`spanEndTimeShift` of 1h creates extremely wide Loki query windows on trace drill-down. Pre-existing in root `grafana-datasources.yml`. Reduce to 1m/1m.
- Prometheus has no `depends_on: app` in compose — cold-start scrape failures on first `docker compose up`. Acceptable gap; scrapes recover once app is healthy.
- LGTM data `mkdir -p` calls gated inside Hetzner Volume device `if [ -b ]` check — consistent with existing postgres pattern. If volume is absent at provision time, Docker auto-creates dirs as root (further compounds the permission issue once it's resolved).

## Deferred from: code review (2026-05-26)
- ~~Potential Path Traversal [S3StorageService.java]~~ — **RESOLVED 2026-05-28**: `StorageKeyGenerator` strips `/` from `entity` and `entityId` inputs via `[^a-zA-Z0-9_-]` sanitization. The `/` chars in the composed S3 key come exclusively from hardcoded format-string separators, not user input.
- ~~Unbounded Thread Pool [BlobstoreConfig.java]~~ — **RESOLVED 2026-05-28**: Replaced `Executors.newFixedThreadPool` with `ThreadPoolExecutor` backed by `ArrayBlockingQueue(100)` and `CallerRunsPolicy`. Pool size and queue capacity configurable via `app.storage.executor.*`.

## Deferred from: code review of skillars-3-9-bulk-session-request-from-calendar (2026-06-16)
- W1: Race condition in `updateBatchStatusFromBooking` under concurrent coach actions — `REQUIRES_NEW` opens a fresh transaction but two concurrent individual accepts can both call this before either commits; batch status outcome is indeterminate; REQUIRES_NEW is the spec-prescribed pattern [`BookingBatchService.java`]
- W2: `bookingRepository.findById` in `BookingBatchStatusListener` runs outside explicit transaction — fires in AFTER_COMMIT context without a wrapping transaction; works under Spring Boot defaults but may fail on stricter configurations [`BookingBatchStatusListener.java`]
- W3: `parentName` null in `getParentBookings()` — pre-existing behavior, no AC requires parent name on parent's own bookings view [`BookingService.java`]
- W4: `getCoachBookingRequests` derives `parentName` from first booking in batch — data invariant guaranteed at creation; reachable only via direct DB manipulation [`BookingService.java`]
- W5: Confirm button in batch review dialog has no `hasCredits` guard — backend validates and returns error; Epic 7 will wire credit display to frontend [`BookingRequestPage.vue`]

## Deferred from: code review of skillars-3-8-rescheduling-duplication-reminders (2026-06-16)
- D1: `completionLoading` shared across all reschedule/duplicate store actions — consumers cannot distinguish which operation is in-flight; per-booking scoping refs partially mitigate; pre-existing [`booking.store.js`]
- D2: `ShortCode.shortenInt(UUID.randomUUID().hashCode())` idempotency key collision risk — 128-bit UUID collapsed to 32-bit int; low-probability but non-zero collision chance; pre-existing across all email handlers [`BookingEmailListener.java`]
- D3: Empty email silently accepted in notification loops — if coach/parent user lookup fails, empty-string email is used with no exception or warning; pre-existing pattern [`BookingEmailListener.java`]
- D4: `currentUserId()` ClassCastException risk — `getCurrentUser()` cast to `Principal` without instanceof check; 500 instead of 401 if principal type changes; pre-existing across all resources [`RescheduleResource.java`]
- D5: `COACH` value in `proposedBy` DB constraint allowed but never set — coach-initiated reschedule path not in scope this story; DB is future-proof [`BookingRescheduleRequest.java`]
- D6: Service-layer tests use Mockito unit test pattern (`@ExtendWith(MockitoExtension.class)`) — story spec Task 20/21 explicitly defined unit tests; integration coverage provided by `RescheduleResourceIT` [`RescheduleServiceTest.java`, `BookingDuplicationServiceTest.java`]
- D7: `datetime-local` input in reschedule dialog coerces proposed times to browser local timezone — the ISO-8601 string sent to the API reflects the user's local offset, not the coach's canonical timezone; browser local time intent is ambiguous (parent and coach may be in different timezones); add a visible canonical timezone hint label next to the inputs in a future UX polish story [`ParentBookingsPage.vue`]

## Deferred from: code review of skillars-3-10-session-pack-expiry-pause-management (2026-06-17)
- D1: No distributed locking on `SessionPackExpiryScheduler` — multi-instance deployments will concurrently process packs, causing duplicate status transitions and duplicate warning emails. Requires Shedlock or a DB advisory lock. Pre-existing pattern across all project schedulers. [`SessionPackExpiryScheduler.java`]
- D2: `@TransactionalEventListener(AFTER_COMMIT)` failure silently loses coach cancellation notifications — if email dispatch fails after commit, the coach is never notified even though bookings are `CANCELLED`. Event delivery reliability (retry/DLQ) is an infrastructure-wide concern not introduced by this change. [`BookingEmailListener.java`, `SessionPackEmailListener.java`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)
- Def1: `expireStaleReservations()` loop has no circuit breaker — sustained high rate of new expired reservations could delay other scheduled work indefinitely; no max-iteration or max-time guard. [`QuotaReservationTimeoutService.java:expireStaleReservations`]
- Def2: `VideoQuotaReservation.status` as raw String vs enum — intentional per story notes to avoid JPA enum binding complexity with raw SQL paths; values DB-constrained via CHECK constraint. [`VideoQuotaReservation.java:status`]
- Def3: Long arithmetic overflow in `storageUsedBytes + requestedBytes` — theoretical at practical quota sizes (max ~9.2 EB); no guard exists. [`QuotaService.java:check`, `QuotaService.java:reserve`]
- Def4: `commit()` no-op on already-COMMITTED is indistinguishable from not-found — `updated == 0` is logged as debug; callers cannot differentiate idempotent from non-existent handle; intentional idempotency design. [`QuotaService.java:commit`]
- Def5: `expireBatch()` exception mid-loop not caught — exception terminates the do-while; Spring `@Scheduled` catches it at the framework level; next firing will retry. [`QuotaReservationTimeoutService.java:expireStaleReservations`]
- Def6: `BandwidthResetService` has no distributed locking — multi-instance deployments can fire the cron simultaneously; data is idempotent but wasteful; single-instance deploy assumed for now. [`BandwidthResetService.java`]
- Def7: `VideoConfig.quotaProviderValidator` consistency guarantee logging — AC 10 requires logging the guarantee at startup; validator not in this diff; needs verification that it calls `getConsistencyGuarantee()` and logs it. [Out-of-diff verification needed]
- Def8: `BandwidthResetService` period drift when job runs late — `bandwidth_period_start` set to `NOW()` on actual run date, not 1st of month; next period boundary shifts accordingly; acceptable drift for non-billing context. [`BandwidthResetService.java:resetMonthlyBandwidth`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system Run 2 (2026-06-20)
- Def9: `sumActiveReservedBytes` includes expired-but-unreaped ACTIVE rows — brief (<60s) window between expiry and reaper firing causes conservative over-reporting; intentional design. [`VideoQuotaReservationRepository.java:22`]
- Def10: `BandwidthResetService` full-table lock risk at month boundary — single unpartitioned UPDATE locks all video_quotas rows, blocking concurrent `reserve()` calls; scaling concern. [`BandwidthResetService.java`]
- Def11: `bandwidth_used_bytes` never incremented — tracking deferred to Story 6.3 (streaming/playback pipeline); schema and reset job created now so schema is ready. [`QuotaService.java`]
- Def12: `QuotaConfigService.resolveTierKey()` exhaustive switch will throw `MatchException` if `CoachSubscriptionTier` enum grows — safe now but fragile if a new coach tier is added. [`QuotaConfigService.java:39`]
- Def13: `DrillUploadService` video replacement orphans old quota reservation — pre-existing; replacing a non-READY video's `DrillVideoRef` does not call `release()` on the old reservation; orphaned bytes held until reaper. [`DrillUploadService.java:~69-85`]
- Def14: `DrillUploadService.deleteVideo()` TOCTOU on `clearVideoId`/`existsByVideoId` — pre-existing; concurrent deletes on different drills sharing the same `videoId` can publish `VideoPhysicalDeletionEvent` twice. [`DrillUploadService.java:~104-108`]
- Def15: PII logging in `AccountManagementFacade` (user login at INFO level) — pre-existing; `user.getLogin()` logged at INFO; GDPR concern. [`AccountManagementFacade.java:~204-206`]
- Def16: `AccountManagementFacade` phone registration NullPointerException — pre-existing; `getEmail().toLowerCase()` throws NPE for phone-only registrations. [`AccountManagementFacade.java:~231`]
- Def17: `AdminVideoService.deleteVideo()` — `release()` exception inside `TransactionTemplate` kills delete transaction — pre-existing. [`AdminVideoService.java`]
- Def18: V53 platform_config IDs 117-132 hardcoded — verify against all intermediate migrations (V43–V52) before deploying; any ID conflict causes Flyway failure. [`V53__video_quota_system.sql:32-50`]
- Def19: `sumActiveReservedBytes` theoretical `ClassCastException` — PostgreSQL BIGINT SUM typically maps to Long via JDBC but no compile-time guarantee. [`VideoQuotaReservationRepository.java:22`]
