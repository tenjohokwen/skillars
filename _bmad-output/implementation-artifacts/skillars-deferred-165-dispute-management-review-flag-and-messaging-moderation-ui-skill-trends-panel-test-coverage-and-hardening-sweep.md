# Story skillars-deferred-165: Dispute management, review-flag and messaging-moderation UI, skill-trends panel, test-coverage closures and hardening sweep

Status: done

<!-- CREATED 2026-10-27 (/bmad-create-story). User-requested bundle mined from deferred-work.md —
     3 user-selected buckets (Tests, Frontend polish, Recap attendance) plus the freshest
     not-yet-storied ledger entries (skillars-deferred-163's own deferrals, code review of
     skillars-deferred-163, code review of skillars-deferred-164). Every item was independently
     re-verified against HEAD ac745443 (3 parallel Explore agents) before drafting — several no
     longer match the ledger's own framing; see "Verified state at drafting" and "Owner decisions"
     below. Ledger line numbers shift constantly; locate bullets by the quoted lead text, not by
     number. -->

## Story

As a maintainer of the Skillars platform,
I want the genuinely-missing-UI backend features (dispute raising/resolution, review flagging,
message moderation, skill-trend viewing) built out, the oldest still-open test-coverage gaps
closed, a real cross-booking prep-source bug fixed, and two small reliability gaps hardened,
so that coaches/parents/players/admins can actually use capabilities that already exist
server-side, regression coverage matches what the code already claims to do, and the ledger
stops re-offering items that are either already closed or mis-described.

## Owner decisions (2026-10-27)

Resolved with the user (AskUserQuestion) before drafting, each re-verified against HEAD first —
see "Verified state at drafting":

- **D165-A — Build real UI for all three "endpoint has no frontend" ledger items, including
  `/api/disputes`.** Offered as three sizes (skill-trends/session-by-id = small, review-flag/
  messaging = small, disputes = substantial, net-new, multi-role). User chose to build all three.
  **Correction made after the decision, not re-asked**: of the two endpoints bundled under one
  ledger bullet ("skill-trends and session-by-id... no non-test callers"), only skill-trends has
  any plausible non-invented integration point — re-verified session-by-id has **zero** existing
  screen anywhere in the frontend where a user holds a session-plan id without also holding (or
  being able to resolve) the booking id; building a caller for it would mean inventing a flow that
  doesn't exist today. See AC4 and the ledger-mapping table — session-by-id is **not** built, only
  re-documented, to avoid unwanted scope the "build all three" decision didn't actually ask for
  once the distinction was visible.
- **D165-B — Admin disputes gets a real worklist, not ID-only.** `AdminDisputeResource` has no
  list/queue endpoint today (unlike `AdminModerationResource`/`AdminReviewResource`, which both
  have `GET /queue`). User chose to add a matching `GET /api/admin/disputes/queue` endpoint (AC1)
  rather than ship an admin UI that can only resolve a dispute reached by direct id.
- **D165-C — Recap attendance on recovery stays an open question, no code.** The ledger itself
  tags this `(Open Question 1)`, not `[DECIDED]`/`[DISMISSED]` — a real fix touches the coach-access
  clock and SLU calculation, a product decision, not a mechanical patch. User confirmed: keep
  documented, do not write an AC. Re-annotated in the ledger mapping below with today's
  verification so the next pass doesn't have to re-derive it.
- **D165-D — `CoachRadarCard` pagehide keepalive race stays out of this bundle.** Confirmed
  genuinely greenfield: no sequence-number/`If-Match`/version-header ordering primitive exists
  anywhere in the codebase to reuse, and deferred-163's own code review already called this
  "inherent to pagehide; needs server-side ordering." User confirmed: exclude, it needs its own
  design-first story.
- **D165-E — happy-dom full-suite timeout flakes: bounded investigate-and-tune AC, not a
  guaranteed fix.** Version is confirmed current (`^20.11.6`); no `testTimeout`/`poolOptions`
  config exists in `vitest.config.mjs` to tune today, and the root cause (suite-level load) is
  unconfirmed. User chose to include it as AC16 with an explicit, falsifiable done-condition
  rather than leaving it as a ledger note only.

Two further items were decided by direct evidence rather than re-asked, following the same
"already-established-convention" pattern as prior stories' D164-E/D164-F:

- **D165-F — `ParentDevelopmentPortalResourceIT`'s missing `@Testcontainers` is obsolete, not a
  gap.** The entire IT suite abandoned `@Testcontainers`/container-as-`@Bean` project-wide in
  commit `b1d8c831` ("Story Deferred-19: Integration-Test Context & Container Consolidation",
  documented in `docs/testing/container-architecture.md:1-40`); containers are now JVM-static
  singletons started lazily, deliberately **not** wired through the JUnit extension. Zero files
  in `src/test` carry `@Testcontainers` today. Adding it to this one class would make it the only
  inconsistent IT in the suite, not bring it into line with a correctly-annotated sibling — there
  is no such sibling anymore. No AC; closed by verification.
- **D165-G — `AuthResourceIT`'s raw `jdbcTemplate` seeding stays as-is, not migrated to
  Instancio.** Confirmed every real write already commits correctly (wrapped in
  `transactionTemplate`/a dedicated `commitWrite` helper — the one body-level raw call sits inside
  a `/* ... */`-commented-out test, i.e. dead code) — this is a style/consistency gap, not a
  correctness bug, exactly as the ledger's own 2026-06-12 note already concluded. The file also
  fixes numeric ids (`AUTHORITY_COACH_ID=901L`, `COACH_USER_ID=9000000001L`, etc.) referenced by FK
  joins across 20+ `@Test` methods — a full Instancio migration here is a materially larger, riskier
  refactor than the Instancio precedents in the same module (`UserRepositoryIT`, `UserServiceIT`,
  `UserEnversAuditGapIT`), which each seed one unrelated entity, not a cross-referenced fixture
  graph. No AC; closed by verification, not worth the risk for a non-defect.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets below live in `_bmad-output/implementation-artifacts/deferred-work.md`. On completion
the dev **deletes** closed bullets and re-annotates anything closed by decision/verification
rather than code (file convention, same as skillars-deferred-161/163/164).

| AC | Ledger bullet (lead text) | Section |
|----|---------------------------|---------|
| 1-3 | "`POST/GET /api/disputes`... zero frontend usage for any role" | bmad-quick-dev coach UI-gap scan (2026-10-14) |
| 4 | "skill-trends and session-by-id... minor, low-priority unused endpoints" (skill-trends half only — see D165-A) | bmad-quick-dev coach UI-gap scan (2026-10-14) |
| 5 | "`POST /api/reviews/{id}/flag`... no UI for anyone" | bmad-quick-dev coach UI-gap scan (2026-10-14) |
| 6 | "messaging delete/report endpoints... no UI for anyone" | bmad-quick-dev coach UI-gap scan (2026-10-14) |
| 7 | "Template name inputs lack a client-side maxlength=200" | (user-selected candidate, re-mined) |
| 8 | "Plan-note panel/Apply stay active after a locked 403" | code review of skillars-12-6 (2026-10-22) |
| 9 | "`CoachPlayerLastSessionResolver`'s `lastWhatWeDid`/`lastWhatsNext` source is still unbounded" | skillars-deferred-163 (2026-10-26) |
| 10 | "Recovered recap prefill is lost when SessionRecapDialog's GET fails" | code review of skillars-deferred-163 (2026-10-27) |
| 11 | "`AlertNotificationListener` EMAIL_CONFIG_ERROR path is not throttled" | code review of skillars-deferred-164 (2026-10-27) |
| 12 | "`voice_note_text` drop, steps 2 and 3 of 3" (step 2 only — step 3 stays forbidden by `migration-conventions.md` rule 2) | skillars-deferred-163 (2026-10-26) |
| 13 | "No tests for the `(\d+)` route constraints" | code review of skillars-deferred-154 (2026-10-17) |
| 14 | "No page-level spec for SessionBuilderPage mount ordering" | dev of skillars-12-6 (2026-10-22) |
| 15 | "`CoachPlanNoteResourceIT.patchNote` uses Map.of... null clear isn't tested end to end" | code review of skillars-12-6 (2026-10-22) |
| 16 | "Frontend full-suite `Test timed out in 5000ms` flakes after the happy-dom bump" | code review of skillars-deferred-164 (2026-10-27) |
| — (re-annotate, no AC) | "`ParentDevelopmentPortalResourceIT` is missing @Testcontainers" — **closed D165-F, obsolete** | adversarial code review of skillars-5-6 (2026-06-19) |
| — (re-annotate, no AC) | "AuthResourceIT uses raw jdbcTemplate instead of Instancio" — **closed D165-G, non-defect** | code review of skillars-1-5 (2026-06-12) |
| — (re-annotate, no AC) | "A swept placeholder keeps playerAttended=false" — **D165-C, stays Open Question** | dev of skillars-12-7 (2026-10-22) |
| — (leave, own future story) | "CoachRadarCard pagehide keepalive PUT can be overtaken by an older save" — **D165-D, excluded** | code review of skillars-deferred-163 (2026-10-27) |
| — (new, session-by-id) | "session-by-id... minor, low-priority unused endpoint" — **D165-A correction: re-document, no realistic caller exists, not built** | bmad-quick-dev coach UI-gap scan (2026-10-14) |

## Verified state at drafting (HEAD ac745443)

- **AC1-AC3 (disputes)**: `DisputeResource.java:37-55` already exposes `POST /api/disputes`
  (`RaiseDisputeRequest(bookingId, reason, details)` → `DisputeCreatedResponse(disputeId)`) and
  `GET /api/disputes/{disputeId}` (`DisputeResponse(disputeId, bookingId, reason, details, status,
  resolution, resolutionNote, createdAt)`), both `IS_AUTHENTICATED`. `DisputeService.raiseDispute`
  (`:81-140+`) enforces: `reason` ∈ `VALID_REASONS` (`COACH_NO_SHOW, SESSION_QUALITY,
  SAFETY_CONCERN, UNAUTHORISED_CHARGE, OTHER`, `:63-64`); caller must be the booking's
  parent/player/coach (resolved through `PlayerProfile.userId`/`CoachProfile.userId`, not the raw
  profile ids — same cross-ID-space hop as `ReviewSubmissionService`); `booking.status` ∈
  `ELIGIBLE_STATUSES` (`COMPLETED, CANCELLED, CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_PLAYER,
  NO_SHOW_COACH`, `:60-61`); a configurable submission window (`disputes.submissionWindowDays`,
  default 14, clamped 1-365); and at most one **open** dispute per booking
  (`DisputeRepository.findOpenByBookingId`). `AdminDisputeResource.java` has `GET /{disputeId}`
  (`AdminDisputeDetailDto` — richer, includes `raisedByRole, creditAmount, coachName, sessionDate,
  cancellationHistory`, etc., `:8-29`), `POST /{disputeId}/resolve`, `POST /{disputeId}/dismiss` —
  all `HAS_ADMIN_ROLE` — but **no list/queue endpoint**, confirmed by reading the full resource
  class. `AdminModerationResource.getQueue` (`:42-54`) is the precedent to mirror: `@GetMapping
  ("/queue")`, params `type` (default `ALL`), `status` (default `OPEN`, only `OPEN` supported —
  non-`OPEN` throws 400), `page`; returns `Page<AdminAlertDto>` via `adminQueueService.getAlerts`.
  `DisputeRepository` has no paged "all open disputes" query yet — closest existing shape is
  `findOpenByBookingId` (single row, not paged). Frontend: zero callers anywhere for raise, get, or
  admin-resolve/dismiss (confirmed via full-repo grep for `disputes`, `raiseDispute`, `Dispute`) —
  the only existing UI traces are an unrelated `DISPUTED` status chip (`BookingStateChip.vue:46`)
  and a disabled-recap message (`SessionRecapDialog.vue:36-37,210`, keyed off
  `notEditableReason === 'booking.recapNotEditableDisputed'`). Booking-detail surfaces per role,
  confirmed: parent/self-booking-player → `ParentBookingsPage.vue` (per-booking action buttons at
  `:118-177`, e.g. cancel `:118-131`, player-request `:145-158`, state chip + confirm-completion
  `:160-177`); coach → `CoachCommandCenterPage.vue` (per-booking action-button block
  `.week-grid__booking-block`, `:148-299`, e.g. no-show-report `:228-238`, cancel `:239-249`). Admin
  pages: only `HealthDashboardPage.vue` exists today, routed as the sole child under
  `routes.js:370-381`'s `admin` parent route (`roles: ['ADMIN']`) — a new Disputes admin page is a
  sibling child in that same array.
- **AC4 (skill-trends only — session-by-id re-documented, not built)**: `GET
  /api/development/players/{playerId}/slu/skill-trends` (`SkillExposureResource.java:39-47`) has
  zero frontend callers (`development.api.js:68-69` defines `getSkillTrends` but nothing imports it
  outside that file) while being fully covered by backend tests (`CoachAccessWindowIT`,
  `SluDashboardServiceTest`, `SluSkillTrendIT`) — reachable and correct, just unwired.
  `SkillTrendResponse(List<SkillTrend> trends)` where `SkillTrend(skillCode, direction
  [SkillTrendDirection: presumably IMPROVING/FLAT/DECLINING/INSUFFICIENT_DATA-shaped],
  slopePerWeek: BigDecimal, weeksObserved: int)` — **a materially different shape** from what's
  already displayed: `SkillExposureTrendChart.vue` (already wired into both target pages) consumes
  `store.exposure.trend`, a **weekly-total time series**, not a per-skill direction/slope row. So
  this AC adds a genuinely new, non-duplicate view (per-skill trend direction), not a second display
  of the same data. Insertion points: `PlayerDevelopmentDashboardPage.vue` — new `q-card` after the
  existing trend-chart card (after line 61, same `q-card`/`q-card-section`(title)/`q-card-
  section`(content) pattern, import alongside line 89); `CoachPlayerDetailPage.vue` — new `q-card`
  between the existing trend-chart card (ending `:156`) and the next card (`:162`), following the
  same empty-state-then-content pattern the existing card uses (`:141-149` empty, `:150-154`
  content). **Session-by-id** (`GET /api/session/sessions/{sessionId}`,
  `SessionBuilderResource.java:52-56`, `HAS_COACH_ROLE`): confirmed zero frontend callers and,
  unlike skill-trends, **no plausible non-invented integration point** — every `sessionId` usage in
  the frontend (`session.api.js`, `SessionBuilderPage.vue`, `sessionBuilder.store.js`,
  `sessionTemplate.store.js`) is derived from a prior booking-id-based load; no screen anywhere
  holds a session-plan id without a resolvable booking id. Re-documented in the ledger with this
  finding; not built (see D165-A correction).
- **AC5 (review-flag)**: non-owner review display lives at
  `CoachPublicProfilePage.vue:98-117` (`v-for="review in reviews"` loop; pagination `:118-125`;
  the viewer's **own** review is fetched separately via `getMyReviewForCoach` and rendered outside
  this loop at `:130-179`). The list DTO (`ReviewDto.java:6-15`:
  `reviewId, authorRole, rating, body, coachResponseBody, coachResponseAt, createdAt,
  lastModifiedAt`) carries no `authorId`/`isOwnReview` flag, so "don't show Flag on my own review"
  must be done client-side by comparing `review.reviewId === myReview.value?.reviewId` (both
  already loaded in the same `onMounted`, `:461-510`) — no existing precedent for that comparison in
  this component, this AC adds it. `reviews.api.js` has no flag wrapper at all today. Target:
  `POST /api/reviews/{reviewId}/flag` (`ReviewResource.java:126-135`, `IS_AUTHENTICATED`),
  `ReviewFlagRequest(reason: ReviewFlagReason, details: String @Size(max=500))`,
  `ReviewFlagReason = FAKE_REVIEW | OFFENSIVE_CONTENT | CONFLICT_OF_INTEREST | OTHER`.
- **AC6 (messaging delete/report)**: `MessagingPage.vue` has no separate bubble/thread component —
  everything inline: message bubble render `:45-57` (`v-for="msg in currentMessages"`, keyed
  `msg.messageId`, aligned by `msg.senderId === myUserId`), conversation list item `:11-34`. No
  kebab/context-menu exists anywhere in messaging today; the project's own precedent for one is
  `SessionTemplateVault.vue:36-52` (`q-btn icon="more_vert"` → `q-menu`/`q-list`/`q-item clickable
  v-close-popup`). `messaging.api.js` has no delete/report wrappers. Targets: `DELETE
  /api/messaging/conversations/{conversationId}/messages/{messageId}`
  (`MessagingResource.java:145-155`, `softDeleteMessage`, no body, `IS_AUTHENTICATED` — service-
  layer should be checked for an own-message-only restriction before wiring the button visibility);
  `POST .../messages/{messageId}/report` (`:157-174`, `ReportRequest(reason: MessageReportReason,
  details: String @Size(max=500))`, `MessageReportReason = HARASSMENT | INAPPROPRIATE_CONTENT |
  SPAM | CONTACT_SOLICITATION | OTHER`); `POST .../conversations/{conversationId}/report` (`:176-
  192`, same `ReportRequest` shape, conversation-level). All three take an optional `role` query
  param (`resolveRole(auth, roleHint)`) — the frontend already resolves/sends this pattern elsewhere
  in `messaging.api.js` callers; mirror it.
- **AC7 (template maxlength)**: exactly two frontend inputs map to the backend's `@Size(max=200)`
  (`CreateTemplateRequest.java:11`, `RenameTemplateRequest.java:7`) — the rename dialog
  (`SessionTemplateVault.vue:64-69`) and the "Save as Template" dialog inside Session Builder
  (`SessionBuilderPage.vue:242-247`). Neither `q-input` has a `maxlength` attribute today. No
  third template-name field exists (confirmed — only one backend `SessionTemplate` entity).
- **AC8 (plan-note panel)**: `editable` is a **prop**, not a local ref, on both
  `CoachPlanNotePanel.vue` (`:59`, bound from `:prep.coachPlanNoteEditable` at
  `SessionPrepCard.vue:106`) and `PrepFeedForwardCard.vue` (bound at its own `:50`). Both
  components' catch blocks on a failed save (`CoachPlanNotePanel.vue save()`, `:83-97`;
  `PrepFeedForwardCard.vue`'s apply handler, `:117-130`) only call `$q.notify(...)` with
  `planNoteErrorMessage(err, t)` (`planNoteError.js`, `LOCKED_ERROR_KEYS` at `:16-17` covering
  `booking.notEditable`/`booking.sessionAlreadyEnded`) — neither flips any local/emitted state, so
  the textarea and Save/Apply button stay fully interactive after a 403 that means "this will never
  succeed again." `coachPlanNoteEditable` is confirmed never reassigned client-side anywhere
  (grepped the whole frontend).
- **AC9 (resolver unbounded)**: `CoachPlayerLastSessionResolver.resolve` (`:28-33`) calls
  `PlayerTimelineRepository.findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc` (`:51-52`
  in the repo interface) — a plain derived query, **no join to `booking.bookings`, no time bound**.
  That result feeds `lastWhatWeDid`/`lastWhatsNext` via `SessionPrepService.java:66-83`
  (`signal.recapSourceEventOrNull()`), with no comparison against the prepped booking's
  `requestedStartTime` anywhere on that path — so a later-scheduled booking's recap can pair with an
  earlier-scheduled booking's prep card, the exact bug class deferred-163 AC3.2 already fixed for
  the sibling `whatsNext` **suggestion** field. The bounded sibling,
  `PlayerTimelineRepository.findLatestWhatsNextSuggestion` (`:110-121`, native `@Query`): `LEFT JOIN
  booking.bookings b ON b.id = pte.reference_id ... AND (b.requested_start_time IS NULL OR
  b.requested_start_time < :preppedBookingStartTime)`, called as
  `findLatestWhatsNextSuggestion(playerId, coachId, bookingId, booking.getRequestedStartTime())`
  from `SessionPrepService.java:111-115`. This AC mirrors that exact shape for the event backing
  `lastWhatWeDid`/`lastWhatsNext`.
- **AC10 (recap prefill retry)**: `SessionRecapDialog.vue:172-193` (`load()`): on success,
  `applyPrefillIntoEmptyFields()` runs at line 186 (inside `try`); on failure, the `catch` block
  (`:187-189`) only sets `loadError.value = true` — no retry, no re-invocation, and the dialog's
  template (`:21-23`) renders a static localized error string with **zero recovery UI**. The typed
  prefill (passed via `props.prefill`) is silently and permanently dropped on that path. (Ledger's
  own `:189` citation was stale by three lines — the real call site is `:186`.)
- **AC11 (alert throttle)**: `AlertNotificationListener.java:98-110` catches a `ConfigService.find`
  failure and logs `ERROR` with a full stack trace on **every** invocation, unthrottled
  (`status=EMAIL_CONFIG_ERROR`). The sibling no-recipient path already has a throttle to mirror
  exactly: `lastNoRecipientLogAt` (`AtomicReference<Instant>`, `:70`) +
  `logNoRecipientThrottled` (`:152-164`, a simple `now.isBefore(last.plus(cooldown))` time-bucket,
  cooldown via constructor-injected `Duration` + `Clock`, `:72-78`). Note for the AC/tests:
  `AlertEvaluationService.computeMetricValue` is still a stub returning `-1.0` (`:87-91`), so no
  `AlertFiredEvent` is ever published in production today — this whole listener path (old and new
  throttle alike) is only exercisable via a unit test invoking `onAlertFired` directly.
- **AC12 (voiceNoteText step 2 only)**: `SessionCompletionData.java:49-50` still has
  `@Column(name = "voice_note_text", length = 2000) private String voiceNoteText;`;
  `SessionCompletionDataRepository.anonymizeByPlayerId`/`anonymizeByCoachId` (`:53,60`) still `SET
  s.voiceNoteText = NULL`. `isSweeperPlaceholder` already does not read it (step 1 done, per
  `BookingCompletionService.java:533-543`'s own Javadoc). No migration for the eventual `DROP
  COLUMN` exists anywhere (grepped `src/main/resources/db/migration`, zero hits, no
  `drop-prepared-in` marker referencing this column). `migration-conventions.md` rule 2 (`:58-85`)
  is explicit: the `DROP` must be a **later, separate release** after every code reference is
  removed, guarded by `-- migration-lint: drop-prepared-in: V<n>` naming a release already on disk —
  compressing field-removal and column-drop into one release is exactly what the rule forbids and
  `MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP` would fail the build over. This AC does the
  pure-Java removal only; the migration is explicitly **not** in scope.
- **AC13 (route constraint test)**: `routes.js` constraint lines unchanged (`:130,135,140,169,236,
  278,284`, each `:playerId(\\d+)`); catch-all 404 at `:388-392`. `routerGuardSpec.js` imports the
  real router (`:26-30`) but every pushed `:playerId` is an all-digit Tsid fixture
  (`:136,144,152,164,176`) — it never pushes a non-digit value, so the digit-only **rejection**
  behavior is not actually proven anywhere, even though the file is wired against the real config.
  Seven page specs still build their own unconstrained test router — all seven confirmed still
  present today (3 of 7 line citations drifted since 2026-10-17, same pattern, re-verified exact
  current lines): `MainLayoutSpec.js:87-88`, `MyPlayersPageSpec.js:39`,
  `CoachPlayerDetailPageSpec.js:62`, `ParentDevelopmentPortalPageSpec.js:39,71`,
  `PlayerSubscriptionPageSpec.js:41`, `ParentPlayerPortalPageSpec.js:39,43`,
  `PlayerDevelopmentDashboardPageSpec.js:36`. Deliberately scoped out before (deferred-154 AC6:
  "A manual check... is sufficient; do not add a new test file") — this AC closes only the literal
  "zero coverage of the rejecting behavior" gap (one new `routerGuardSpec.js` case); retrofitting
  all seven page-spec routers stays optional/stretch, not required.
- **AC14 (SessionBuilderPage mount spec)**: `SessionBuilderPage.vue:307-315`'s `onMounted` does
  `await builderStore.fetchExistingPlan(bookingId)` then, only `if (!builderStore.isGated)`, `await
  Promise.all([builderStore.fetchPrep(bookingId), fetchDrills()])` — the exact sequential-then-
  gated-parallel ordering the deferred-work bullet describes, confirmed unchanged, confirmed still
  with no page-level spec (`sessionBuilderStoreSpec.js` exists for the store; no
  `SessionBuilderPageSpec.js` exists for the page). Heavy dependency graph (draggable, drill
  library, DNA chart) is why no spec exists yet — this AC needs those stubbed, not real.
- **AC15 (CoachPlanNoteResourceIT null-clear)**: `patchNote` helper (`:295-303`) builds its body
  with `Map.of("planNote", planNote)` — throws NPE immediately if `planNote` is `null`, a hard
  `java.util.Map.of` API constraint. No test anywhere — IT or unit — currently exercises a literal
  Java `null` (only blank/whitespace: IT's `setCoachPlanNote_blankClearsExistingNote` at `:163-171`
  uses `""`; the unit test `BookingServiceTest:1858-1866` uses `"   "`), slightly broader than the
  ledger's own framing ("covered... via the unit tests" is itself generous — the unit test covers
  blank, not null, either). Production code already correctly treats both identically
  (`UpdateCoachPlanNoteRequest` has no `@NotNull`, its Javadoc says "null/blank clears"; `BookingService
  .sanitizeAndTruncate`, `:1127-1131`: `if (input == null || input.isBlank()) return null;`) — no
  production change needed. Precedent for sending a real HTTP null via a mutable map:
  `SanitizePreviewResourceIT.java:142-157` and `SkillExposureResourceIT.java:250-253`, both
  `new HashMap<>(); map.put(key, null);`.
- **AC16 (happy-dom flake)**: `src/frontend/package.json:49` confirms `"happy-dom": "^20.11.6"`
  (already current, not stale). `vitest.config.mjs` has no `testTimeout` and no
  `pool`/`poolOptions` — Vitest's 5000ms default and default thread pool are in effect, unchanged.
  None of the three affected specs (`routerGuardSpec.js`, `CoachPlayerDetailPageSpec.js`,
  `CoachSessionPackTiersPageSpec.js`) has a per-test timeout override. Root cause (suite-level
  load under full-suite parallelism) is plausible but **unconfirmed** in this session — scope the
  AC as a bounded experiment with a clear, falsifiable done-condition, not a guaranteed diff.

## Acceptance Criteria

1. **Add `GET /api/admin/disputes/queue` — a paged admin worklist of open disputes**, mirroring
   `AdminModerationResource.getQueue`'s shape (`status` param, default/only `OPEN` supported in
   this version; `page` param; returns `Page<AdminDisputeQueueDto>` — a new, lighter DTO than
   `AdminDisputeDetailDto`, e.g. `disputeId, bookingId, raisedByRole, reason, status, createdAt`,
   enough for a worklist row without the full detail payload). Backing repository method: a new
   paged query on `DisputeRepository` (e.g. `findByStatusOrderByCreatedAtAsc(String status,
   Pageable)` or an explicit `@Query`), `HAS_ADMIN_ROLE`. Add an IT (mirror
   `AdminModerationResource`'s own queue IT shape) covering: empty queue, one open dispute appears,
   a resolved/dismissed dispute does not appear, pagination works across >1 page.
2. **Frontend: let a parent, self-booking player, or coach raise a dispute on an eligible booking,
   and view its own status.** Add `raiseDispute(bookingId, reason, details)` and
   `getDispute(disputeId)` to a new `src/frontend/src/api/disputes.api.js`. Add a "Raise a dispute"
   action to `ParentBookingsPage.vue` (alongside the existing per-booking actions at `:118-177`) and
   to `CoachCommandCenterPage.vue` (alongside `:228-249`), visible only when the booking's status is
   one of `COMPLETED, CANCELLED, CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_PLAYER, NO_SHOW_COACH`
   (match `DisputeService.ELIGIBLE_STATUSES` client-side, purely for UX — the server is the real
   gate). Dialog: a `reason` select over the fixed server-accepted values (`COACH_NO_SHOW,
   SESSION_QUALITY, SAFETY_CONCERN, UNAUTHORISED_CHARGE, OTHER`) and a `details` textarea (max
   2000, matching `RaiseDisputeRequest`). On success, store the returned `disputeId` against the
   booking (local/session state is fine — there's no existing booking→dispute link surfaced to the
   client) and show a simple status view (reason, status, resolution/resolutionNote once present)
   via `getDispute`. Surface the server's 409 (`disputes.alreadyRaised`), 400
   (`disputes.invalidReason`), and `NOT_ELIGIBLE`/`WINDOW_EXPIRED` errors as toasts with
   i18n'd messages. Add a page spec covering: button visibility by booking status, dialog submit
   happy path, and the conflict/ineligible error paths.
3. **Frontend: a minimal admin "Disputes" page** — new `src/frontend/src/pages/admin/
   AdminDisputesPage.vue`, added as a sibling child route under `routes.js`'s existing `admin`
   parent (next to `health-dashboard`, same `roles: ['ADMIN']` guard). List the open-dispute queue
   (AC1's endpoint); clicking a row opens a detail view backed by `GET
   /api/admin/disputes/{disputeId}` (`AdminDisputeDetailDto` — render `raisedByRole, reason,
   details, creditAmount, coachName, sessionDate, bookingStatus, cancellationHistory` etc.) with
   `Resolve` (amount + resolution + note → `POST .../resolve`) and `Dismiss` (reason →
   `POST .../dismiss`) actions, both already-existing backend endpoints. Add a page spec covering
   queue render, detail load, and both resolve/dismiss happy paths.
4. **Wire `GET .../slu/skill-trends` into a new "Skill Trends" panel** on both
   `PlayerDevelopmentDashboardPage.vue` (new `q-card` placed after the existing trend-chart card,
   after line 61) and `CoachPlayerDetailPage.vue` (new `q-card` between lines 156 and 162, matching
   the existing empty-state-then-content pattern at `:141-154`). New store action/getter wiring
   `getSkillTrends` (already defined in `development.api.js:68-69`, just unwired) into the relevant
   Pinia store(s) these pages already use. Render each `SkillTrend` row as a per-skill
   direction/slope indicator (this is a different shape than `SkillExposureTrendChart.vue`'s weekly
   time series — do not attempt to reuse that component's props as-is; it needs its own small
   rendering, e.g. an icon/arrow per `direction` plus `slopePerWeek`/`weeksObserved` as a caption).
   Add i18n strings for the panel title and each `SkillTrendDirection` value across all locale
   files. **`session-by-id` (`GET /api/session/sessions/{sessionId}`) is explicitly NOT wired in
   this story** — no realistic non-booking-id caller exists anywhere in the current frontend (see
   "Verified state at drafting"); re-document the ledger bullet's session-by-id half with this
   finding instead of inventing a caller.
5. **Add a "Flag" action to the coach review list a parent/player sees.** In
   `CoachPublicProfilePage.vue`'s review loop (`:98-117`), add a flag icon/button per row, hidden
   when `review.reviewId === myReview.value?.reviewId` (the viewer's own review, loaded separately
   at `:130-179`/`:461-510` — add this comparison, it doesn't exist today). Add `flagReview(reviewId,
   payload)` to `reviews.api.js` calling `POST /api/reviews/{reviewId}/flag`. Dialog: a `reason`
   select over `FAKE_REVIEW, OFFENSIVE_CONTENT, CONFLICT_OF_INTEREST, OTHER` and a `details`
   textarea (max 500). Show success/already-flagged/error toasts. Add a page-spec case covering:
   flag button hidden on own review, dialog submit happy path.
6. **Add delete-own-message and report-message/report-conversation actions to messaging.** In
   `MessagingPage.vue`, add a `q-menu`-based kebab action (follow `SessionTemplateVault.vue:36-52`'s
   exact pattern — no such menu exists in messaging today) to each message bubble (`:45-57`):
   "Delete" (own messages only — `msg.senderId === myUserId`, calling the new
   `softDeleteMessage(conversationId, messageId)` wrapper against `DELETE
   /api/messaging/conversations/{conversationId}/messages/{messageId}`, no body) and "Report" (any
   message, dialog with `reason` ∈ `HARASSMENT, INAPPROPRIATE_CONTENT, SPAM, CONTACT_SOLICITATION,
   OTHER` + `details` max 500, calling `POST .../messages/{messageId}/report`). Add a conversation-
   level "Report conversation" action to the conversation list item (`:11-34`), same `ReportRequest`
   shape, calling `POST /api/messaging/conversations/{conversationId}/report`. Add the three
   wrapper functions to `messaging.api.js` (none exist today), threading the existing `role` query-
   param pattern the other wrappers in that file already use. After a successful delete, remove/
   grey out the message locally (confirm against `MessagingResource.softDeleteMessage`'s actual
   response contract — it returns `204 No Content`, so the frontend must optimistically update, not
   rely on a response body). Add page-spec cases for: delete visibility (own messages only), delete
   happy path, message report happy path, conversation report happy path.
7. **Add `maxlength="200"` to both template-name inputs.** `SessionTemplateVault.vue:64-69`
   (rename dialog) and `SessionBuilderPage.vue:242-247` ("Save as Template" dialog) — both bind the
   same 200-char server limit (`CreateTemplateRequest.java:11`, `RenameTemplateRequest.java:7`).
   Pure attribute addition, no behavior change (server `@Size` already enforces it). Add/extend a
   spec assertion for each dialog confirming the attribute is present.
8. **Flip the plan-note panel non-editable after a locked 403, client-side.** In both
   `CoachPlanNotePanel.vue` (`save()`, `:83-97`) and `PrepFeedForwardCard.vue` (apply handler,
   `:117-130`), when `planNoteErrorMessage`'s underlying error key is one of
   `planNoteError.js`'s `LOCKED_ERROR_KEYS` (`booking.notEditable`/`booking.sessionAlreadyEnded`),
   set a local ref (e.g. `locallyLocked`) in addition to the existing toast, and compute the
   textarea/button's actual disabled state as `!props.editable || locallyLocked.value` (the
   `editable` prop itself stays read-only/unchanged — don't try to mutate a prop). Reset
   `locallyLocked` when the component is freshly mounted/re-opened for a different booking. Add a
   spec case per component: a locked-error response disables further edits without a page reload.
9. **Bound `CoachPlayerLastSessionResolver`'s prep-card source the same way
   `findLatestWhatsNextSuggestion` is already bounded.** Add a new bounded query (mirror
   `PlayerTimelineRepository.findLatestWhatsNextSuggestion`'s exact shape: `LEFT JOIN
   booking.bookings b ON b.id = pte.reference_id ... AND (b.requested_start_time IS NULL OR
   b.requested_start_time < :preppedBookingStartTime)`) backing the event `resolve()` (`:28-33`)
   uses for `lastWhatWeDid`/`lastWhatsNext`, replacing the current unbounded
   `findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc` call on this path. Thread the
   prepped booking's `requestedStartTime` through `resolve`'s existing call chain
   (`SessionPrepService.java:66-83`), matching how `:111-115` already threads it for the sibling
   suggestion query. Add a regression test proving a later-scheduled booking's recap no longer
   pairs with an earlier-scheduled booking's prep card (mirror whatever test already covers the
   AC3.2 fix for `findLatestWhatsNextSuggestion`).
10. **Add a retry affordance to `SessionRecapDialog`'s load-error state.** In the `loadError`
    branch (`:21-23` template, `:187-189` catch), add a retry button that re-invokes `load()`
    (reusing the existing `loadToken` race-guard already in place). No change to the success path.
    Add a spec case: GET failure shows the retry button; clicking it re-fetches and, on success,
    applies prefill exactly as the happy path does.
11. **Throttle `AlertNotificationListener`'s `EMAIL_CONFIG_ERROR` path**, mirroring the existing
    `lastNoRecipientLogAt`/`logNoRecipientThrottled` pattern exactly (`:70,152-164`): add a second
    `AtomicReference<Instant>` and reuse the same injected `Clock` + cooldown `Duration` around the
    `catch` block at `:98-110`. Add a unit test invoking `onAlertFired` directly (the only way to
    exercise this — `AlertEvaluationService.computeMetricValue` is still a stub returning `-1.0`,
    so no real `AlertFiredEvent` reaches this listener in production today) proving a second
    failure within the cooldown window does not log again, and one past the cooldown does.
12. **Remove `SessionCompletionData.voiceNoteText` (entity field) and both GDPR scrub references —
    pure Java, no migration.** Delete the `@Column(name = "voice_note_text", ...)` field
    (`SessionCompletionData.java:49-50`) and strip it from `anonymizeByPlayerId`/
    `anonymizeByCoachId` (`SessionCompletionDataRepository.java:53,60`). **Do not** add the
    `ALTER TABLE ... DROP COLUMN` migration — `migration-conventions.md` rule 2 requires it in a
    *later, separate* release, and this is already step 2 of 3 (step 1, removing
    `isSweeperPlaceholder`'s read, shipped in deferred-163). Note in the ledger mapping which future
    release will carry the `-- migration-lint: drop-prepared-in: V<this release's own number>`
    marker once this one merges.
13. **Add a non-digit-`playerId` regression case to `routerGuardSpec.js`.** Push a route like
    `/coach/players/abc` through the real router (same `import('src/router')` factory the file
    already uses) and assert it falls through to the catch-all 404 (`meta.notFound`), proving the
    `(\\d+)` constraint's actual rejecting behavior for the first time. Retrofitting the seven
    page-spec test routers (`MainLayoutSpec.js`, `MyPlayersPageSpec.js`,
    `CoachPlayerDetailPageSpec.js`, `ParentDevelopmentPortalPageSpec.js`,
    `PlayerSubscriptionPageSpec.js`, `ParentPlayerPortalPageSpec.js`,
    `PlayerDevelopmentDashboardPageSpec.js`) to also declare the constraint is optional/stretch —
    deliberately out of scope per deferred-154 AC6's own waiver.
14. **Add a page-level spec for `SessionBuilderPage`'s mount ordering.** New
    `SessionBuilderPageSpec.js` covering `onMounted`'s actual sequencing (`:307-315`):
    `fetchExistingPlan` always awaited first; `fetchPrep`+`fetchDrills` only fire, in parallel, when
    `!builderStore.isGated`; a scout-gated booking never calls `fetchPrep` at all. Stub the heavy
    dependency graph (draggable, drill library, DNA chart) rather than mounting them for real —
    this is precisely why no spec exists yet; keep the stubs minimal and scoped to what the mount-
    ordering assertions need.
15. **Add an end-to-end null-clear test for the coach plan note PATCH.** Replace
    `CoachPlanNoteResourceIT`'s `Map.of`-based `patchNote` body construction with a mutable-map
    builder (`new HashMap<>()`, following `SanitizePreviewResourceIT.java:142-157`/
    `SkillExposureResourceIT.java:250-253`'s exact precedent) so a literal JSON `null` can be sent,
    and add a new test (e.g. `setCoachPlanNote_nullClearsExistingNote`) asserting a `{"planNote":
    null}` PATCH clears an existing note via a real HTTP round trip — not just blank/whitespace, the
    only cases covered anywhere today. No production code change (`UpdateCoachPlanNoteRequest`/
    `BookingService.sanitizeAndTruncate` already handle `null` correctly).
16. **Investigate the happy-dom full-suite timeout flakes and, if load-related, tune
    `vitest.config.mjs`.** Bounded experiment, not a guaranteed diff: try raising `test.testTimeout`
    (e.g. to 10000ms) and/or constraining `poolOptions` (e.g. `threads.maxThreads` or
    `singleThread`) in `vitest.config.mjs`; re-run the full suite enough times to form a real
    signal. **Done-condition**: either (a) N consecutive full-suite CI runs come back green with no
    `Test timed out` failures in `routerGuardSpec.js`/`CoachPlayerDetailPageSpec.js`/
    `CoachSessionPackTiersPageSpec.js` (pick N with the team, e.g. 3-5 PR-build runs), in which case
    keep the tuned config; or (b) the flake persists unchanged after tuning, in which case revert
    the config change and leave the ledger note as-is with this story's negative result recorded
    ("tuned `testTimeout`/pool, no effect — root cause is not suite-level load as hypothesized").
    Either outcome is an acceptable close for this AC — do not keep retuning indefinitely.
17. **Ledger hygiene.** Delete every bullet closed by code (ACs 1-16 where code shipped). For items
    closed by decision/verification rather than code, re-annotate (don't delete) with today's date
    and this story's id: `ParentDevelopmentPortalResourceIT`'s missing `@Testcontainers` (D165-F,
    `[DECIDED — verified obsolete, no defect]`), `AuthResourceIT`'s raw `jdbcTemplate` (D165-G,
    `[DECIDED — verified non-defect, not worth the risk]`), recap attendance on recovery (D165-C,
    stays `(Open Question 1)`, add this story's verification evidence), `CoachRadarCard` pagehide
    race (D165-D, re-annotate "needs its own design-first story," do not delete), and session-by-id
    (re-annotate with the "no realistic caller exists" finding, do not delete). If AC16 lands as
    outcome (b) (no effect), add that negative result to the ledger rather than deleting the bullet.

## Tasks / Subtasks

- [x] Task 1: Admin disputes queue endpoint (AC: #1)
  - [x] `AdminDisputeQueueDto`, repository query, resource method, IT
- [x] Task 2: Raise/view dispute UI — parent/player/coach (AC: #2)
  - [x] `disputes.api.js`, dialog component, booking-page wiring (both surfaces), page specs
- [x] Task 3: Admin Disputes page (AC: #3)
  - [x] Route, `AdminDisputesPage.vue`, queue + detail + resolve/dismiss wiring, page spec
- [x] Task 4: Skill Trends panel, both pages (AC: #4)
  - [x] Store wiring, new panel component(s), i18n, ledger re-doc for session-by-id (ledger text
        edit deferred to Task 17's consolidated hygiene pass)
- [x] Task 5: Review flag UI (AC: #5)
  - [x] `flagReview` wrapper, own-review exclusion, dialog, page-spec case
- [x] Task 6: Messaging delete/report UI (AC: #6)
  - [x] Kebab menu on message + conversation, three API wrappers, page-spec cases (also fixed a
        pre-existing bug: `myUserId` read a non-existent `authStore.user` property, so no message
        ever rendered as "mine" — found while wiring the own-message-only Delete gate)
- [x] Task 7: Template-name `maxlength` (AC: #7) — SessionBuilderPage.vue's own spec assertion
      lands with Task 14's new SessionBuilderPageSpec.js
- [x] Task 8: Plan-note panel locked-after-403 (AC: #8)
  - [x] `CoachPlanNotePanel.vue`, `PrepFeedForwardCard.vue`, specs
- [x] Task 9: Bound `CoachPlayerLastSessionResolver` (AC: #9)
  - [x] New bounded query, thread `requestedStartTime`, regression test (overload added rather
        than changing `resolve(Long,UUID)`'s signature, since 3 unrelated callers only read
        `lastSessionDate()` and must keep their unbounded behavior unchanged; the new bound also
        had to special-case `reference_id = bookingId` as always-valid, unlike the sibling
        suggestion query, or the existing clock-skew regression test would regress — caught by
        running it)
- [x] Task 10: `SessionRecapDialog` retry on load error (AC: #10)
- [x] Task 11: `AlertNotificationListener` EMAIL_CONFIG_ERROR throttle (AC: #11)
- [x] Task 12: `voiceNoteText` step 2 removal (AC: #12)
- [x] Task 13: `routerGuardSpec.js` non-digit case (AC: #13)
- [x] Task 14: `SessionBuilderPageSpec.js` mount-ordering spec (AC: #14)
- [x] Task 15: `CoachPlanNoteResourceIT` null-clear test (AC: #15)
- [x] Task 16: happy-dom flake investigate/tune experiment (AC: #16) — outcome: could not reproduce
      the flake locally even at baseline (2/2 clean full-suite runs before any config change);
      applied the low-risk `testTimeout: 10000` bump anyway (directly matches the reported
      "Test timed out in 5000ms" symptom, zero behavioral cost) and confirmed no regression (2/2
      clean runs after); kept the tuned config but recorded in the ledger that local evidence
      could not confirm it as the actual fix — the real done-condition (N consecutive green CI
      full-suite runs) can only be checked post-merge
- [x] Task 17: Ledger hygiene pass (AC: #17)

### Review Findings

Code review 2026-10-27 (/bmad-code-review: Blind Hunter + Edge Case Hunter + Acceptance Auditor, re-verified against the working tree).

Outcome: Decision 1 → a (became a Patch), Decision 2 → a (deferred). All 16 patches applied the same day.
Verification: targeted unit tests green (CoachPlayerLastSessionResolverTest 16, GdprErasureServiceTest 32,
TimelineQueryServiceTest 15, CoachRosterServiceTest 18). Targeted ITs green with fresh failsafe reports
(SessionPrepResourceIT 21/21 incl. the new `getPrep_laterScheduledSessionWithSluRow_doesNotNullTheBoundedEarlierRecap`,
GdprErasureIT 44/44). Affected frontend specs 423/423 across 38 files, Prettier clean. Mutation-checked:
(1) reverting the resolver to the unbounded SLU signal fails the new IT ("but was: null");
(2) replacing SessionBuilderPage's Promise.all with sequential awaits fails the strengthened AC14 spec.
No local `mvn verify`; GitHub CI is the full gate.

- [x] [Review][Patch] GDPR erasure must keep scrubbing legacy `voice_note_text` until the column drop ships — restore a native-SQL `SET voice_note_text = NULL` in both anonymize paths and revert the two `GdprErasureIT` assertions to `isNull()` (Decision 1 → a, 2026-10-27) [src/main/java/com/softropic/skillars/platform/booking/repo/SessionCompletionDataRepository.java:55-64]
- [x] [Review][Defer] Coach-side dispute action can only appear on COMPLETED bookings — `getCoachWeekSchedule` never returns CANCELLED/NO_SHOW bookings [src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:615-618] — deferred (Decision 2 → a, 2026-10-27): parents can still dispute every eligible status; widening the coach week schedule is a visible behavior change for its own story
- [x] [Review][Patch] Bounded recap source is still checked against the unbounded SLU signal, so a later-scheduled session with an SLU row empties the earlier session's recap; the new IT seeds no SLU rows [src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerLastSessionResolver.java:55-66]
- [x] [Review][Patch] Admin resolve sends a stale `creditAmount` after switching away from PARTIAL_CREDIT/COACH_WARNING (`DisputeService.resolveDispute` stores it on every resolution); PARTIAL_CREDIT can also be submitted with no amount [src/frontend/src/pages/admin/AdminDisputesPage.vue:187,335-339]
- [x] [Review][Patch] Admin queue shows "No open disputes" and hides the pager after the last row of the last page is resolved or dismissed — `currentPage` is never clamped [src/frontend/src/pages/admin/AdminDisputesPage.vue:285-297]
- [x] [Review][Patch] `openDetail` has no stale-response guard — a late response for dispute A can render while Resolve/Dismiss target `selectedDisputeId` B [src/frontend/src/pages/admin/AdminDisputesPage.vue:304-318]
- [x] [Review][Patch] After `disputes.alreadyResolved` the dialogs stay open and the queue is not reloaded, so the admin keeps acting on a closed dispute [src/frontend/src/pages/admin/AdminDisputesPage.vue:344-352,366-372]
- [x] [Review][Patch] Admin detail omits `raisedByRole` and `creditAmount`, which AC3 names; `fmt(null)` renders a missing amount as "€0.00" [src/frontend/src/pages/admin/AdminDisputesPage.vue:79-100,378-380]
- [x] [Review][Patch] Dispute submit allows blank Details, but `RaiseDisputeRequest.details` is `@NotBlank`, so the user only gets the generic failure toast [src/frontend/src/components/booking/DisputeAction.vue:62]
- [x] [Review][Patch] Dispute sessionStorage key is not user-scoped, so another user on the same tab sees "View dispute" for someone else's dispute [src/frontend/src/components/booking/DisputeAction.vue:142]
- [x] [Review][Patch] Review Flag button shows to logged-out visitors and non-parent/player roles on the public coach profile (AC5: "the coach review list a parent/player sees") [src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue:108-111]
- [x] [Review][Patch] Delete is offered on own UNDER_REVIEW/BLOCKED messages, which the server always rejects with `messaging.moderationPending`, unmapped, so the user gets a generic error [src/frontend/src/pages/messaging/MessagingPage.vue:97-103,266-281]
- [x] [Review][Patch] MessagingPageSpec delete test mutates the shared `MY_MESSAGE` fixture and asserts on that same object, so it can't fail on reactivity and makes later tests order-dependent [src/frontend/src/pages/messaging/__tests__/MessagingPageSpec.js:37,84-88]
- [x] [Review][Patch] Plan-note locks are not shared — a locked 403 on Apply leaves the embedded panel editable and vice versa (AC8) [src/frontend/src/components/session/PrepFeedForwardCard.vue:28,50]
- [x] [Review][Patch] Skill-trends fetch failure renders as "No trend data yet" (`skillTrendsError` unread); INSUFFICIENT_DATA rows show a "0.00 SLU/week" slope; an unknown direction prints the raw i18n key [src/frontend/src/components/development/SkillTrendsPanel.vue:3-26]
- [x] [Review][Patch] AC14 "in parallel" is not proven — mocks resolve immediately, so sequential awaits would still pass [src/frontend/src/pages/coach/__tests__/SessionBuilderPageSpec.js:126-132]
- [x] [Review][Patch] Story bookkeeping: `BookingCompletionServiceTest.java` missing from File List; "All 17 ACs implemented and verified" contradicts AC16's pending-CI caveat; AC17 note misstates which ledger bullets were deleted vs re-annotated; the AC12 ledger entry leaves `V<n>` unexplained [this story file; deferred-work.md]
- [x] [Review][Defer] `disputes.alreadyRaised` leaves "Raise" visible with no path to "View" once the client-side link is lost [src/frontend/src/components/booking/DisputeAction.vue:184] — deferred, needs a server-side booking→dispute link (out of scope per Dev Notes)
- [x] [Review][Defer] Message Delete has no confirmation step [src/frontend/src/pages/messaging/MessagingPage.vue:102] — deferred, not specified by AC6
- [x] [Review][Defer] Messaging calls never pass the `role` disambiguator, so a dual-role user can't act under their second role [src/frontend/src/pages/messaging/MessagingPage.vue:296-298] — deferred, pre-existing (no messaging call on the page passes `role`)
- [x] [Review][Defer] Admin dispute detail shows raw enum values (`raisedByRole`, `bookingStatus`) and hardcodes "€" [src/frontend/src/pages/admin/AdminDisputesPage.vue:24,85,90-95] — deferred, polish


## Dev Notes

- This story is larger than the typical "mechanical bugfix bundle" precedent (e.g. deferred-161/
  -163/-164) because the user explicitly chose to build three real net-new UI surfaces (disputes,
  review-flag, messaging moderation) rather than defer them further — see Owner decisions D165-A/
  D165-B. Budget dev time accordingly; consider landing ACs 1-6 (the UI builds) and ACs 7-17 (the
  smaller fixes/tests) as reviewable in that rough order even within one story/PR.
- No local `mvn verify` — GitHub CI is the sole full-verification gate for this project.
- `IS_AUTHENTICATED`-gated endpoints (disputes, review-flag, messaging report/delete) authorize at
  the service layer via party-membership checks (`DisputeService`'s owner-eligibility resolution,
  `MessagingReportService.verifyIsParty`) — the frontend must handle the resulting 403/409/400
  error bodies gracefully, not assume the button's own visibility logic is the only gate.
- For AC2/AC3: there is no existing booking→dispute link surfaced anywhere in the API today (no
  `Booking.disputeId` or similar). Don't invent a backend change to add one unless it becomes
  genuinely necessary during implementation — client-side tracking of "this booking's dispute id"
  is sufficient for this story's scope (a page refresh losing that link is an acceptable, pre-
  existing-shaped limitation, not a regression this story introduces, since no persistence existed
  before either).
- For AC6: confirm `MessagingService.softDeleteMessage`'s actual authorization behavior (does it
  throw if a non-sender calls it, or silently no-op?) before finalizing the delete button's
  visibility condition — don't assume client-side `senderId` comparison alone is sufficient if the
  server has a different/looser rule.

### Project Structure Notes

- New frontend files: `src/frontend/src/api/disputes.api.js`,
  `src/frontend/src/pages/admin/AdminDisputesPage.vue`, plus whatever dialog/panel components ACs
  2-6 need (follow this project's existing convention of co-locating a dialog as its own component
  under the page's directory, matching e.g. `SessionRecapDialog.vue`'s placement).
- New backend file: `AdminDisputeQueueDto` (contract), alongside the existing
  `AdminDisputeDetailDto` in `platform.admin.contract`.
- No new migrations in this story (AC12 is pure Java; AC1 adds a derived/native query, no schema
  change — `disputes.status` is an existing plain `String` column).

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (ledger; see "Ledger → story mapping"
  above for exact source sections).
- `docs/deployment/migration-conventions.md` rule 2 (AC12's constraint).
- `docs/testing/container-architecture.md` (D165-F's evidence).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

### Completion Notes List

All 17 ACs implemented; 16 verified locally. AC16's flake experiment stays open until CI confirms
it (see the AC16 note below), so it is not counted as verified. No local `mvn verify` / no local full `npm run test:unit`
beyond this story's own targeted + full-suite-sanity runs (see below) — GitHub CI is the sole
full-verification gate for this project (`docs/validation-strategy.md`).

- **AC1 (admin disputes queue)**: `GET /api/admin/disputes/queue` added — new `AdminDisputeQueueDto`,
  `DisputeRepository.findByStatusOrderByCreatedAtAsc`, `DisputeService.getDisputeQueue`,
  `AdminDisputeResource.getQueue`. `AdminDisputeQueueIT` (6 tests): empty queue, one open dispute
  appears, resolved/dismissed excluded, pagination across >1 page, 403 for non-admin, 400 for a
  non-OPEN status param. Backend regression (`AdminDisputeResolveIT`, `DisputeDismissIT`,
  `DisputeSubmissionIT`) stayed green.
- **AC2 (raise/view dispute UI)**: new `disputes.api.js` + `DisputeAction.vue` (self-contained
  trigger+dialog component, booking/session-scoped `disputeId` link kept client-side per Dev
  Notes), wired into `ParentBookingsPage.vue` and `CoachCommandCenterPage.vue`. Caught and fixed a
  real bug in my own first draft: `raiseDispute`/`getDispute`'s resolved value is already unwrapped
  by `boot/axios.js`'s response interceptor (`response.data`), not an axios `Response` — my first
  pass read `resp.data.disputeId`/`resp.data`, which would have been `undefined` at runtime despite
  passing unit tests whose mocks matched the same (wrong) shape. Fixed before marking the task done.
- **AC3 (admin disputes page)**: new `AdminDisputesPage.vue` (queue table, detail dialog,
  resolve/dismiss dialogs), route + nav link added. `admin.api.js` gained the 4 dispute wrappers
  (same unwrap bug caught and fixed here too).
- **AC4 (skill trends panel)**: `getSkillTrends` wired into `development.store.js`
  (`fetchSkillTrends`, unwraps `{trends: [...]}`) and a new `SkillTrendsPanel.vue`, added to both
  `PlayerDevelopmentDashboardPage.vue` and `CoachPlayerDetailPage.vue`. `session-by-id` intentionally
  NOT wired (D165-A correction) — re-documented in the ledger instead.
- **AC5 (review flag UI)**: `flagReview` wrapper + new `ReviewFlagAction.vue`, wired into
  `CoachPublicProfilePage.vue`'s review loop with the own-review exclusion
  (`review.reviewId !== myReview?.reviewId`).
- **AC6 (messaging delete/report UI)**: three new `messaging.api.js` wrappers, kebab menus added to
  both the message bubble and the conversation list item, shared report dialog.
  **Found and fixed a real pre-existing production bug while wiring the own-message-only Delete
  gate**: `MessagingPage.vue`'s `myUserId` read `authStore.user?.id`, a property that never existed
  on `auth.store.js` (only a flat `userId` ref) — so `msg.senderId === myUserId` never matched and no
  message bubble ever rendered as "mine" in production. Fixed to read `authStore.userId` directly;
  pinned by a new regression test.
- **AC7 (template maxlength)**: `maxlength="200"` added to both inputs; new
  `SessionTemplateVaultSpec.js` (first spec for that page) and an assertion folded into the new
  `SessionBuilderPageSpec.js` (AC14).
- **AC8 (plan-note locked-after-403)**: `isLockedPlanNoteError` exported from `planNoteError.js`;
  `locallyLocked` ref added to both `CoachPlanNotePanel.vue` and `PrepFeedForwardCard.vue` (separate
  write paths, each needed its own guard), reset on `bookingId` change since neither component
  remounts per booking.
- **AC9 (bound CoachPlayerLastSessionResolver)**: new `PlayerTimelineRepository.findLatestSessionCompletedEvent`
  + `CoachPlayerLastSessionResolver.resolve` overload, threaded through `SessionPrepService`. Added
  as an overload (not a signature change) because 3 unrelated callers (`TimelineQueryService`,
  `CoachRosterService`, `ReportGenerationService`) only read `lastSessionDate()`, never
  `recapSourceEventOrNull()`, and must keep the prior unbounded behavior for that unrelated signal.
  The new query also had to special-case `reference_id = bookingId` as always-valid (unlike the
  sibling suggestion query, which excludes it) — caught by running
  `getPrep_sameSessionWriteClockSkew_recapStillAttributedDespiteSluTimestampWinning`, an existing
  test whose fixture is exactly a same-booking recap, before marking the task done. New regression
  test `getPrep_recapSourceBoundedByReferencedBookingTime_notJustOccurredAt` mirrors the existing
  AC3.2 suggestion-bound test's fixture shape.
- **AC10 (SessionRecapDialog retry)**: retry button added to the load-error branch, reusing `load()`
  and its existing `loadToken` race guard as-is.
- **AC11 (AlertNotificationListener throttle)**: second `AtomicReference`/throttle method mirroring
  the existing no-recipient one exactly, reusing the same injected `Clock` + cooldown `Duration`.
- **AC12 (voiceNoteText step 2)**: entity field + both GDPR scrub query references removed (pure
  Java, no migration). Had to delete one now-impossible-to-construct unit test
  (`submitWrapUp_placeholderWithLegacyVoiceNoteText_isStillRecognisedAsPlaceholder` — its whole
  premise was an in-memory entity carrying a field that no longer exists) and flip two
  `GdprErasureIT` assertions from "becomes null after erasure" to "stays unchanged" (the column is no
  longer in the scrub's scope). `GdprErasureIT` (44 tests), `SessionCompletionResourceIT` (37),
  `BookingCompletionServiceTest` (56) all green after the fix.
- **AC13 (routerGuardSpec.js non-digit case)**: one new test case, real router, asserts the
  catch-all 404 (`meta.notFound`) for `/coach/players/abc`.
- **AC14 (SessionBuilderPageSpec.js)**: new page spec (first ever for this page) covering the actual
  `fetchExistingPlan` → (gated ? skip : `fetchPrep`+`fetchDrills` in parallel) ordering with a
  deferred-promise proof, plus the gated/non-gated branches and the AC7 maxlength assertion.
- **AC15 (CoachPlanNoteResourceIT null-clear)**: `patchNote` helper switched to a mutable
  `HashMap` (from `Map.of`, which NPEs on a null value) and a new
  `setCoachPlanNote_nullClearsExistingNote` test added — no production change needed.
- **AC16 (happy-dom flake investigation)**: ran the full frontend suite twice as a baseline before
  any change — both clean (643/643, no timeouts), so the flake could not be reproduced locally even
  before tuning. Applied `vitest.config.mjs`'s `testTimeout: 10000` anyway (directly matches the
  reported "Test timed out in 5000ms" symptom, zero behavioral cost) and re-ran twice more — also
  clean. Kept the change on its own low-risk merits, but recorded honestly in the ledger that local
  evidence cannot confirm it as the actual fix; the real done-condition (N consecutive green CI
  full-suite runs) is only checkable post-merge.
- **AC17 (ledger hygiene)**: `deferred-work.md` — deleted every bullet closed by code (ACs 1-11,
  13-15; AC12's and AC16's bullets were re-annotated, not deleted, since each still has open work);
  re-annotated the decision/verification-only items (D165-C recap attendance
  stays Open Question, D165-D CoachRadarCard pagehide stays excluded, D165-F/D165-G
  Testcontainers/Instancio non-defects, AC12's voice_note_text bullet updated to "step 3 of 3
  remains", AC16's happy-dom bullet updated with the investigation outcome, session-by-id
  re-documented standalone from the now-closed skill-trends half).

Two real, pre-existing bugs were found and fixed incidentally while implementing the ACs above (the
axios response-unwrap mismatch in AC2/AC3, and the `authStore.user` typo in AC6) — both caught by
running the actual tests against realistic mocks/fixtures before marking those tasks done, not by
inspection alone.

Backend:
- NEW `src/main/java/com/softropic/skillars/platform/admin/contract/AdminDisputeQueueDto.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/admin/repo/DisputeRepository.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/admin/api/AdminDisputeResource.java`
- NEW `src/test/java/com/softropic/skillars/platform/admin/api/AdminDisputeQueueIT.java`

Frontend:
- NEW `src/frontend/src/api/disputes.api.js`
- NEW `src/frontend/src/components/booking/DisputeAction.vue`
- NEW `src/frontend/src/components/booking/__tests__/DisputeActionSpec.js`
- MODIFIED `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- MODIFIED `src/frontend/src/pages/parent/__tests__/ParentBookingsPageSpec.js`
- MODIFIED `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- MODIFIED `src/frontend/src/pages/coach/__tests__/CoachCommandCenterPageSpec.js`
- MODIFIED `src/frontend/src/api/admin.api.js`
- NEW `src/frontend/src/pages/admin/AdminDisputesPage.vue`
- NEW `src/frontend/src/pages/admin/__tests__/AdminDisputesPageSpec.js`
- MODIFIED `src/frontend/src/layouts/MainLayout.vue`
- MODIFIED `src/frontend/src/router/routes.js`
- MODIFIED `src/frontend/src/i18n/en-US/index.js`
- MODIFIED `src/frontend/src/i18n/de-DE/index.js`
- MODIFIED `src/frontend/src/i18n/fr-FR/index.js`
- MODIFIED `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`
- NEW `src/frontend/src/components/development/SkillTrendsPanel.vue`
- NEW `src/frontend/src/components/development/__tests__/SkillTrendsPanelSpec.js`
- MODIFIED `src/frontend/src/stores/development.store.js`
- MODIFIED `src/frontend/src/stores/__tests__/developmentStoreSpec.js`
- MODIFIED `src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue`
- MODIFIED `src/frontend/src/pages/coach/CoachPlayerDetailPage.vue`
- MODIFIED `src/frontend/src/api/reviews.api.js`
- NEW `src/frontend/src/components/marketplace/ReviewFlagAction.vue`
- NEW `src/frontend/src/components/marketplace/__tests__/ReviewFlagActionSpec.js`
- MODIFIED `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`
- NEW `src/frontend/src/pages/marketplace/__tests__/CoachPublicProfilePageSpec.js`
- MODIFIED `src/frontend/src/api/messaging.api.js`
- MODIFIED `src/frontend/src/pages/messaging/MessagingPage.vue`
- NEW `src/frontend/src/pages/messaging/__tests__/MessagingPageSpec.js`
- MODIFIED `src/frontend/src/pages/coach/SessionTemplateVault.vue`
- MODIFIED `src/frontend/src/pages/coach/SessionBuilderPage.vue`
- NEW `src/frontend/src/pages/coach/__tests__/SessionTemplateVaultSpec.js`
- MODIFIED `src/frontend/src/utils/planNoteError.js`
- MODIFIED `src/frontend/src/components/booking/CoachPlanNotePanel.vue`
- MODIFIED `src/frontend/src/components/booking/__tests__/CoachPlanNotePanelSpec.js`
- MODIFIED `src/frontend/src/components/session/PrepFeedForwardCard.vue`
- MODIFIED `src/frontend/src/components/session/__tests__/PrepFeedForwardCardSpec.js`
- MODIFIED `src/main/java/com/softropic/skillars/platform/development/repo/PlayerTimelineRepository.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java` (code review patch: bound the SLU-side signal the same way, see Review Findings)
- MODIFIED `src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerLastSessionResolver.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/development/service/SessionPrepService.java`
- MODIFIED `src/test/java/com/softropic/skillars/platform/development/api/SessionPrepResourceIT.java`
- MODIFIED `src/frontend/src/components/booking/SessionRecapDialog.vue`
- MODIFIED `src/frontend/src/components/booking/__tests__/SessionRecapDialogSpec.js`
- MODIFIED `src/main/java/com/softropic/skillars/platform/notification/service/AlertNotificationListener.java`
- MODIFIED `src/test/java/com/softropic/skillars/platform/notification/service/AlertNotificationListenerTest.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/booking/repo/SessionCompletionData.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/booking/repo/SessionCompletionDataRepository.java`
- MODIFIED `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- MODIFIED `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`
- MODIFIED `src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java` (AC12: removed the voice-note test and assertion that read the deleted field)
- MODIFIED `src/frontend/src/router/__tests__/routerGuardSpec.js`
- NEW `src/frontend/src/pages/coach/__tests__/SessionBuilderPageSpec.js`
- MODIFIED `src/test/java/com/softropic/skillars/platform/booking/api/CoachPlanNoteResourceIT.java`
- MODIFIED `src/frontend/vitest.config.mjs`
- MODIFIED `_bmad-output/implementation-artifacts/deferred-work.md` (AC17 ledger hygiene)
