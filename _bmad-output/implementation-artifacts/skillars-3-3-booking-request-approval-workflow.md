# Story skillars-3.3: Booking Request & Approval Workflow

Status: done

## Story

As a parent,
I want to request a session with a coach and receive confirmation once they accept,
So that sessions are scheduled with mutual agreement, backed by pre-purchased session credits.

## Acceptance Criteria

1. **AC 1: Create Booking Request** — Given a parent has at least one effective available credit with a coach (effective = `creditsRemaining` from active, non-expired, non-paused packs minus credits already soft-reserved by in-flight REQUESTED, ACCEPTED, CONFIRMED, or UPCOMING bookings with that coach), when they submit a booking request for a specific slot, then the backend validates all four conditions before creating the record: (a) `requestedStartTime` is in the future; (b) the coach profile status is `ACTIVE`; (c) the requested slot falls within one of the coach's configured `coach_availability_windows` (from Story 3.1); (d) effective credits > 0 for that `(playerId, coachId)` pair. If all pass, a `bookings` record is created with status `REQUESTED` (id UUID, parentId BIGINT, playerId BIGINT, coachId UUID, requestedStartTime TIMESTAMPTZ, requestedEndTime TIMESTAMPTZ, status VARCHAR(30), canonicalTimezone VARCHAR(50), notes VARCHAR(500) nullable, version INT for optimistic locking, createdAt TIMESTAMPTZ, updatedAt TIMESTAMPTZ). The coach receives an email notification including the parent's notes. The parent sees the booking in `REQUESTED` state with a `BookingStateChip` labelled "Awaiting coach response" in `--accent-warning`.

2. **AC 2: Coach Accepts Booking** — Given a booking is in `REQUESTED` state, when the coach accepts it, then `BookingService.acceptBooking(bookingId, coachUserId)` is the only permitted method to advance the state — no direct `booking.setStatus()` call outside of `BookingService`, and status transitions directly `REQUESTED → ACCEPTED → CONFIRMED` in a single transaction (no intermediate payment step — payment was pre-captured when the parent purchased their session pack), and the parent receives an email notification "Your session with [Coach] on [date] is confirmed", and the `BookingStateChip` updates to "Confirmed" in `--accent-primary`.

3. **AC 3: Coach Declines Booking** — Given a booking is in `REQUESTED` state, when the coach declines it, then `BookingService.transition(bookingId, DECLINE, context)` advances status to `DECLINED`, no payment is captured, the parent is notified via email, and no credit is deducted — credits are only deducted at `COMPLETED` (Story 3.6).

4. **AC 4: Auto-Expiry** — Given a coach does not respond to a `REQUESTED` booking, when the request has been open longer than the configurable expiry window (read from `ConfigService` key `booking.request_expiry_hours`, default 48h), then the booking status is set to `DECLINED` via a scheduled job and a `BookingExpiredEvent` is published — this is **distinct from `BookingDeclinedEvent`** (auto-expiry is not an active rejection by the coach). The parent receives a dedicated expiry email: "Your session request with [Coach] was not responded to in time" — not the coach-declined wording. The soft-reserved credit is freed automatically (expired booking drops from the in-flight count).

5. **AC 5: CONFIRMED → UPCOMING Transition** — Given a booking reaches `CONFIRMED` status, when the session start time is within the primary reminder window (read from ConfigService key `platform.reminder_interval_primary_hours`, default 24h) **or its start time has already passed** (scheduler-downtime catch-up), then status automatically transitions to `UPCOMING` via a scheduled job, and both coach and parent each receive a 24h reminder email (`primary_reminder_sent_at` stamped). When the session is within the secondary reminder window (`platform.reminder_interval_secondary_hours`, default 2h), both receive 2h reminder emails (`secondary_reminder_sent_at` stamped). Reminders are idempotent — sent at most once each. Each reminder sends two separate emails (one per recipient), not one combined email.

6. **AC 6: Parent Bookings List** — Given a parent views their upcoming sessions, when the bookings list renders, then all sessions for all their player profiles are shown with: coach name, date/time in `canonicalTimezone`, status chip, player name, and `effectiveCreditsRemaining` for that coach (returned directly in `BookingResponse` — no separate pack load required on the frontend); sessions are sorted chronologically nearest first.

7. **AC 7: BookingStateChip** — Given a `BookingStateChip` renders any booking status, when displayed, then the chip shows a plain-English label — never the raw status string. Current story states: REQUESTED → "Awaiting coach response", ACCEPTED → "Accepted", CONFIRMED → "Confirmed", UPCOMING → "Upcoming", DECLINED → "Declined". Future states stubbed now to prevent raw-string fallback: COMPLETED → "Completed" (Story 3.6), CANCELLED → "Cancelled" (Story 3.9), DISPUTED → "Disputed" (Story 3.6). Each state maps to its CSS token colour (UX-DR11); COMPLETED/CANCELLED/DISPUTED use `chip--neutral` as placeholder until their stories define the final UX.

8. **AC 8: Coach Booking Inbox** — Given a coach is authenticated, when they access their booking inbox, then `GET /api/bookings/requests/coach` returns all `REQUESTED` bookings for that coach sorted by `requestedStartTime ASC`, with each row showing parent name, player name, requested date/time in `canonicalTimezone`, and notes. The coach can accept or decline each request from `CoachBookingRequestsPage.vue`. Only REQUESTED bookings are shown (not CONFIRMED, DECLINED, etc.).

## Tasks / Subtasks

- [x] Task 1: Flyway migration V31 — `booking.bookings` table + config seed (AC: 1, 4, 5)
  - [x] Create `src/main/resources/db/migration/V31__booking_requests.sql`
  - [x] DDL: `booking.bookings` table — see Dev Notes for full schema
  - [x] Include `@Version` column (`version INT NOT NULL DEFAULT 0`) and reminder timestamp columns
  - [x] CHECK constraint must include `CANCELLED` now (Story 3.9 will add it via CANCELLED terminal state; forward-declare here consistent with the approach used for COMPLETED/DISPUTED — avoids ALTER TABLE mid-epic)
  - [x] Add index `idx_bkg_player_coach_status ON booking.bookings (player_id, coach_id, status)` — required for `countInFlightBookings()` query performance
  - [x] Seed `booking.request_expiry_hours` (ID 38, value `48`) in `main.platform_config`

- [x] Task 2: Backend — `Booking` entity and repository (AC: 1)
  - [x] Create `Booking.java` entity in `platform.booking.repo` — see Dev Notes for full field list; UUID primary key (does NOT extend `BaseEntity`), `parentId` and `playerId` are **Long** (not UUID)
  - [x] Create `BookingRepository.java` extending `JpaRepository<Booking, UUID>` in `platform.booking.repo`
  - [x] Add `findAllByParentIdOrderByRequestedStartTimeAsc(Long parentId)` — for parent bookings list
  - [x] Add `@Query findRequestedBookingsOlderThan(Instant threshold)` — for expiry scheduler (status = 'REQUESTED' AND createdAt < threshold)
  - [x] Add `@Query findConfirmedForUpcomingTransition(Instant windowEnd)` — status = 'CONFIRMED', requestedStartTime <= windowEnd (covers both the 24h lookahead AND catch-up for past-due bookings), primaryReminderSentAt IS NULL; **do NOT use BETWEEN :now AND :windowEnd** — that would miss bookings whose start is already past
  - [x] Add `@Query findUpcomingWithin2hWindow(Instant now, Instant windowEnd)` — status = 'UPCOMING', requestedStartTime BETWEEN now AND windowEnd, secondaryReminderSentAt IS NULL
  - [x] Add `@Query countInFlightBookings(Long playerId, UUID coachId)` — COUNT where playerId = :playerId AND coachId = :coachId AND status IN ('REQUESTED','ACCEPTED','CONFIRMED','UPCOMING') — used by `SessionPackService.hasCredits()` to enforce soft-reservation (see Dev Notes: TOCTOU section)
  - [x] Add `findByCoachIdAndStatusOrderByRequestedStartTimeAsc(UUID coachId, String status)` — for coach inbox endpoint (returns all REQUESTED bookings for a coach)

- [x] Task 3: Backend — booking domain events (AC: 1, 2, 3, 4, 5)
  - [x] Create `BookingRequestedEvent.java` in `platform.booking.contract` — extends `ApplicationEvent`; fields: `UUID bookingId`, `Long parentId`, `Long playerId`, `UUID coachId`, `String coachDisplayName`, `String coachEmail` (**not** `parentEmail` — this event notifies the coach), `String notes`, `Instant requestedStartTime`, `String canonicalTimezone`
  - [x] Create `BookingConfirmedEvent.java` in `platform.booking.contract` — extends `ApplicationEvent`; fields: `UUID bookingId`, `Long parentId`, `String parentEmail`, `String coachDisplayName`, `Instant requestedStartTime`, `String canonicalTimezone`
  - [x] Create `BookingDeclinedEvent.java` in `platform.booking.contract` — extends `ApplicationEvent`; fields: `UUID bookingId`, `Long parentId`, `String parentEmail`, `String coachDisplayName`, `Instant requestedStartTime`
  - [x] Create `BookingExpiredEvent.java` in `platform.booking.contract` — extends `ApplicationEvent`; **same fields as `BookingDeclinedEvent`** but a distinct type so the listener can send a different email ("request expired" vs "coach declined"); fields: `UUID bookingId`, `Long parentId`, `String parentEmail`, `String coachDisplayName`, `Instant requestedStartTime`
  - [x] Create `BookingReminderEvent.java` in `platform.booking.contract` — extends `ApplicationEvent`; fields: `UUID bookingId`, `String parentEmail`, `String coachEmail`, `String coachDisplayName`, `Instant requestedStartTime`, `String canonicalTimezone`, `String reminderType` (`"PRIMARY"` or `"SECONDARY"`)

- [x] Task 4: Backend — `EmailTemplate` extension (AC: 1, 2, 3, 4, 5)
  - [x] Add `BOOKING_REQUESTED`, `BOOKING_CONFIRMED`, `BOOKING_DECLINED`, `BOOKING_EXPIRED`, `BOOKING_REMINDER` to `platform.notification.contract.EmailTemplate` enum
  - [x] Create simple Thymeleaf templates: `src/main/resources/mails/bookingRequested.html`, `bookingConfirmed.html`, `bookingDeclined.html`, `bookingExpired.html`, `bookingReminder.html` (in `mails/` directory as per actual project structure, camelCase per MailService template name conversion)
  - [x] `bookingExpired.html` wording must be distinct: "Your session request with [Coach] on [date] was not responded to in time" — **not** the decline wording

- [x] Task 5: Backend — `BookingEmailListener` in notification module (AC: 1, 2, 3, 4, 5)
  - [x] Create `BookingEmailListener.java` in `platform.notification.infrastructure.listener`
  - [x] Use `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` on all handlers
  - [x] Handle `BookingRequestedEvent` → send `BOOKING_REQUESTED` to **coach** (`event.getCoachEmail()`); include `notes` in the data map
  - [x] Handle `BookingConfirmedEvent` → send `BOOKING_CONFIRMED` email to parent
  - [x] Handle `BookingDeclinedEvent` → send `BOOKING_DECLINED` email to parent (coach actively declined)
  - [x] Handle `BookingExpiredEvent` → send `BOOKING_EXPIRED` email to parent (different template/wording — request timed out, coach did not actively decline)
  - [x] Handle `BookingReminderEvent` → send `BOOKING_REMINDER` email to **both** coach and parent as **two separate `Envelope`** publishes within the same handler — one `Recipient` each; do not combine into one envelope
  - [x] All email address fields are resolved before event construction in `BookingService` via `resolveEmail(Long userId)` helper — the listener only reads from the event, does NOT inject `UserRepository`

- [x] Task 6: Backend — `BookingService` (AC: 1, 2, 3, 4, 5, 6, 8)
  - [x] Create `BookingService.java` in `platform.booking.service`
  - [x] Inject: `BookingRepository`, `SessionPackService`, `CoachProfileRepository`, `CoachAvailabilityWindowRepository`, `PlayerProfileRepository`, `UserRepository`, `ApplicationEventPublisher`, `SessionPackPurchasedRepository`
  - [x] Implement `createBookingRequest(Long parentId, CreateBookingRequest req)` — `@Transactional`: (1) verify player ownership; (2) load coach — throw 404 if not found; (3) **verify coach status is `CoachProfileStatus.ACTIVE`** — throw 403 if DRAFT; (4) **validate `requestedStartTime` is in the future** (belt-and-suspenders beyond DTO `@Future`); (5) **validate the slot falls within the coach's availability windows** via timezone-aware LocalTime+dayOfWeek comparison — throw 403 if no window covers the requested interval; (6) acquire pessimistic lock on pack rows, verify effective credits via `SessionPackService.hasCredits(playerId, coachId)` — throw 403 if none available; (7) create `Booking` with status `REQUESTED`, save; (8) publish `BookingRequestedEvent`; return `BookingResponse` with `effectiveCreditsRemaining` populated
  - [x] Implement `acceptBooking(UUID bookingId, Long coachUserId)` — `@Transactional`: verifies coach owns the booking (via `coachProfileRepository.findByUserId`), validates and applies REQUESTED → ACCEPTED → CONFIRMED in single transaction (no payment step), saves booking, publishes `BookingConfirmedEvent`
  - [x] Implement `declineBooking(UUID bookingId, Long coachUserId)` — `@Transactional`: verifies coach ownership, validates REQUESTED → DECLINED transition, saves, publishes `BookingDeclinedEvent`
  - [x] Implement `getParentBookings(Long parentId)` — `@Transactional(readOnly=true)`: loads all bookings for parent, enriches each with coach display name, player name, and `effectiveCreditsRemaining`, sorted by start time
  - [x] Implement `getCoachBookingRequests(Long coachUserId)` — `@Transactional(readOnly=true)`: resolves coach profile via `coachProfileRepository.findByUserId(coachUserId)`, returns REQUESTED bookings
  - [x] Add private `validateTransition(String from, String to)` helper — throws `OperationNotAllowedException` if not in `ALLOWED_TRANSITIONS`
  - [x] Add private `resolveEmail(Long userId)` helper — **single method, used everywhere**
  - [x] Updated `SessionPackService.hasCredits()` to subtract in-flight bookings count from `BookingRepository.countInFlightBookings()` (TOCTOU fix)

- [x] Task 7: Backend — schedulers (AC: 4, 5)
  - [x] Create `BookingExpiryScheduler.java` in `platform.booking.service` — `@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)` `@Transactional` `@Component @RequiredArgsConstructor @Slf4j`: reads `booking.request_expiry_hours`, finds REQUESTED bookings older than threshold, sets each to DECLINED, publishes **`BookingExpiredEvent`** (NOT `BookingDeclinedEvent`) for each
  - [x] Create `BookingReminderScheduler.java` in `platform.booking.service` — `@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)` `@Transactional` `@Component @RequiredArgsConstructor @Slf4j`: Step 1 CONFIRMED→UPCOMING + primary reminder; Step 2 UPCOMING 2h reminder

- [x] Task 8: Backend — `BookingResource` (AC: 1, 2, 3, 6, 8)
  - [x] Create `BookingResource.java` in `platform.booking.api` with `@RequestMapping("/api/bookings/requests")`
  - [x] `POST /api/bookings/requests` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — returns 201
  - [x] `GET /api/bookings/requests` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — returns `List<BookingResponse>` with `effectiveCreditsRemaining`
  - [x] `PUT /api/bookings/requests/{id}/accept` — `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` — returns 200
  - [x] `PUT /api/bookings/requests/{id}/decline` — `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` — returns 204 No Content
  - [x] `GET /api/bookings/requests/coach` — `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` — declared BEFORE `/{id}/accept` and `/{id}/decline` to avoid path-matching ambiguity
  - [x] Add `@Observed(name = "booking.requests")` on the class

- [x] Task 9: Backend — request/response DTOs (AC: 1, 6)
  - [x] Create `CreateBookingRequest.java` record in `platform.booking.contract`
  - [x] Create `BookingResponse.java` record in `platform.booking.contract`

- [x] Task 10: Frontend — `booking.api.js` additions (AC: 1, 2, 3, 6, 8)
  - [x] Add `createBookingRequest(request)` — `POST /api/bookings/requests`
  - [x] Add `acceptBooking(id)` — `PUT /api/bookings/requests/${id}/accept`
  - [x] Add `declineBooking(id)` — `PUT /api/bookings/requests/${id}/decline`
  - [x] Add `getParentBookings()` — `GET /api/bookings/requests`
  - [x] Add `getCoachBookingRequests()` — `GET /api/bookings/requests/coach`

- [x] Task 11: Frontend — `booking.store.js` additions (AC: 1, 2, 3, 6, 8)
  - [x] Add state: `parentBookings = ref([])`, `bookingsLoading = ref(false)`, `bookingsError = ref(null)`
  - [x] Add state: `coachBookingRequests = ref([])`, `coachRequestsLoading = ref(false)`
  - [x] Add `loadParentBookings()` — populates `parentBookings`; credit count is in each `BookingResponse.effectiveCreditsRemaining`
  - [x] Add `loadCoachBookingRequests()` — populates `coachBookingRequests`
  - [x] Add `submitBookingRequest(request)` — calls `createBookingRequest`, then reloads `parentBookings`
  - [x] Add `approveBooking(id)` — calls `acceptBooking(id)`, then reloads `coachBookingRequests`
  - [x] Add `rejectBooking(id)` — calls `declineBooking(id)`, then reloads `coachBookingRequests`

- [x] Task 12: Frontend — `BookingStateChip.vue` (AC: 7)
  - [x] Create `src/frontend/src/components/booking/BookingStateChip.vue`
  - [x] Props: `{ status: String }` — maps to label + CSS token color via computed
  - [x] Use `<q-chip>` with class bound to status-derived CSS class
  - [x] All labels via `t('booking.requests.status.*')` i18n keys
  - [x] Never render raw status string — always mapped via `statusMap` computed
  - [x] `statusMap` includes stubs for future states: COMPLETED, CANCELLED, DISPUTED → `chip--neutral`

- [x] Task 13: Frontend — `BookingRequestPage.vue` (AC: 1)
  - [x] Create `src/frontend/src/pages/parent/BookingRequestPage.vue`
  - [x] Route params: `:coachId` (from route), `playerId` (from store or route query)
  - [x] Shows `SessionPackTracker` at top
  - [x] Shows coach's available slots from `booking.store.computedSlots`
  - [x] Submit button disabled if: no credits, no slot selected, loading
  - [x] On submit: calls `booking.store.submitBookingRequest(...)`, on success navigates to `/parent/bookings`
  - [x] Route added: `parent/coaches/:coachId/request-booking`

- [x] Task 14: Frontend — `ParentBookingsPage.vue` (AC: 6)
  - [x] Create `src/frontend/src/pages/parent/ParentBookingsPage.vue`
  - [x] On mount: calls `booking.store.loadParentBookings()` — does NOT call `loadPlayerPacks()`
  - [x] Lists bookings with: `BookingStateChip`, coach display name, date/time, player name, `effectiveCreditsRemaining`
  - [x] Empty state with marketplace CTA
  - [x] Route added: `parent/bookings`

- [x] Task 15b: Frontend — `CoachBookingRequestsPage.vue` (AC: 8)
  - [x] Create `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`
  - [x] On mount: calls `booking.store.loadCoachBookingRequests()`
  - [x] Lists REQUESTED bookings with: player name, requested date/time, notes
  - [x] Each row has "Accept" and "Decline" buttons with loading state
  - [x] Empty state
  - [x] Route added: `coach/booking-requests`

- [x] Task 15: Frontend — Wire "Book a session" CTA in `CoachPublicProfilePage.vue` (AC: 1)
  - [x] Updated `handleCta()` to navigate to `BookingRequestPage` when parent has credits, otherwise to `SessionPackPurchasePage`
  - [x] CTA button label dynamically shows "Book a session" or "Buy sessions" based on credit state
  - [x] Removed disabled-button approach; CTA always actionable

- [x] Task 16: Frontend — i18n keys (AC: 1, 2, 3, 6, 7, 8)
  - [x] Added all `booking.requests.*` keys to `src/frontend/src/i18n/en/index.js`
  - [x] Added backend email subject keys to `src/main/resources/i18n/messages_en.properties`
  - [x] Three routes added to `src/frontend/src/router/routes.js`

- [x] Task 17: Unit tests — `BookingServiceTest` (AC: 1, 2, 3, 4, 8)
  - [x] `@ExtendWith(MockitoExtension.class)` — NO `@SpringBootTest`
  - [x] All 12 tests passing
  - [x] Tests cover: credit check, player ownership, coach status, slot validation, past time, accept, decline, invalid transitions

- [x] Task 18: Integration tests — `BookingRequestResourceIT` (AC: 1, 2, 3, 6, 8)
  - [x] Follow exact annotation pattern from `SessionPackResourceIT.java`
  - [x] Distinct IDs: `PARENT_ID = 9500000001L`, `PLAYER_ID = 9500000002L`, `COACH_USER_ID = 9500000010L`
  - [x] `@BeforeEach`: inserts parent, player, coach, availability window, session pack with 3 credits, second coach for wrong-coach test
  - [x] `@AfterEach`: cleans up all inserted rows
  - [x] 11 IT test methods covering all ACs

### Review Findings

- [x] [Review][Patch] `countInFlightBookings` return type must be `long` not `int` — JPQL COUNT always returns Long; Spring Data JPA throws ClassCastException at every call site, breaking booking creation, the credit gate, and the parent bookings list [`BookingRepository.java:49`]
- [x] [Review][Patch] V31 migration `ON CONFLICT (key) DO NOTHING` does not guard against `id=38` primary key collision — a different row at id=38 causes migration to fail before `booking.bookings` table is created [`V31__booking_requests.sql:3`]
- [x] [Review][Defer] `requestedEndTime` minimum duration not validated — 1-second bookings accepted; minimum session length out of scope for Story 3.3 [`CreateBookingRequest.java:16`] — deferred, out of scope
- [x] [Review][Defer] `canonicalTimezone` not IANA-validated before storage — invalid timezone string causes DateTimeException at reminder notification time; service-level fix belongs in Group B review [`CreateBookingRequest.java:17`] — deferred, pre-existing
- [x] [Review][Defer] N+1 queries in `getParentBookings` — player names and effective credits emit separate SQL per booking; belongs in Group B (BookingService) review [`BookingService.java`] — deferred, pre-existing
- [x] [Review][Defer] `@Slf4j` missing on `SessionPackService` — pre-existing omission not introduced by this diff; violates project-wide service annotation rule [`SessionPackService.java`] — deferred, pre-existing

#### Group B — BookingService, BookingExpiryScheduler, BookingReminderScheduler

- [x] [Review][Patch] Double PRIMARY+SECONDARY reminder in same scheduler run — a CONFIRMED booking < 2h away gets transitioned to UPCOMING in Step 1 (primaryReminderSentAt set) then immediately qualifies for Step 2 (secondaryReminderSentAt still null); parent and coach each receive two reminders seconds apart [`BookingReminderScheduler.java:processReminderWindows`] — PATCHED: collect primaryProcessed IDs in Step 1, filter Step 2 against that set
- [x] [Review][Patch] Scheduler `@Transactional` batch rollback on any single booking error — if booking N of 10 throws (DB error, event publisher failure), the entire transaction rolls back including already-saved status updates for bookings 1..(N-1); on next 5-min tick those re-process and send duplicate notifications [`BookingExpiryScheduler.java:expireStaleRequests`, `BookingReminderScheduler.java:processReminderWindows`] — PATCHED: per-booking try-catch in both schedulers
- [x] [Review][Patch] AC 8 — `parentName` missing from `BookingResponse` and `getCoachBookingRequests`; spec AC 8 explicitly requires coach inbox to show parent name per row [`BookingService.java:getCoachBookingRequests`, `BookingResponse.java`] — PATCHED: added `parentName` field to BookingResponse, resolveParentName() in BookingService, populated in getCoachBookingRequests, displayed in CoachBookingRequestsPage
- [x] [Review][Dismiss] `BookingService` missing `@Getter @Setter` class-level annotations — investigated: zero service classes in the platform use @Getter @Setter; convention is entities-only; @Slf4j is present and sufficient [`BookingService.java:41-44`]
- [x] [Review][Defer] No duplicate-booking guard for same slot — a parent can create multiple REQUESTED bookings for the same player/coach/timeslot; slot conflict detection out of scope for Story 3.3 [`BookingService.java:createBookingRequest`] — deferred, out of scope
- [x] [Review][Defer] `resolveEmail` returns `""` silently for missing/deleted users — spec-designed pattern; event is published with blank recipient; downstream email sender is responsible for logging the delivery failure [`BookingService.java`, `BookingExpiryScheduler.java`, `BookingReminderScheduler.java`] — deferred, spec-designed
- [x] [Review][Defer] N+1 player name + credit queries in `getParentBookings` (3 queries per booking row) — already tracked from Group A [`BookingService.java:getParentBookings`] — deferred, pre-existing
- [x] [Review][Defer] All availability windows have invalid timezone → misleading 403 "not within coach availability" instead of data-quality error; warn log surfaces it to admins [`BookingService.java:isSlotWithinAvailabilityWindow`] — deferred, data quality edge case
- [x] [Review][Defer] Midnight-crossing session (e.g., 23:00–00:30) incorrectly validates because `endZdt.toLocalTime()` wraps and the day-of-week check only uses start day [`BookingService.java:228-232`] — deferred, out of scope for 3.3
- [x] [Review][Defer] DST transition can silently shift a booking into or out of an availability window [`BookingService.java:isSlotWithinAvailabilityWindow`] — deferred, narrow edge case
- [x] [Review][Defer] `configService.getLong()` throws `IllegalStateException` on missing key, silencing that scheduler tick; acceptable loud failure since V31 seeds both keys [`BookingExpiryScheduler.java:35`, `BookingReminderScheduler.java:35-36`] — deferred, acceptable failure mode
- [x] [Review][Defer] `w.getDayOfWeek()` JS 0-based vs ISO 1-based day format — pre-existing from Story 3.1 [`BookingService.java:230`] — deferred, pre-existing

#### Group C — BookingResource, events, BookingEmailListener, CreateBookingRequest

- [x] [Review][Patch] `BookingDeclinedEvent` and `BookingExpiredEvent` missing `canonicalTimezone` — `onBookingDeclined`/`onBookingExpired` listeners cannot populate timezone context in email template data; parent sees raw UTC ISO string instead of formatted local time; `canonicalTimezone` is available on `Booking` entity at both publish sites [`BookingDeclinedEvent.java`, `BookingExpiredEvent.java`, `BookingEmailListener.java:72-101`, `BookingService.java:declineBooking`, `BookingExpiryScheduler.java:expireStaleRequests`] — PATCHED: added `canonicalTimezone` field to both event classes; passed at both call sites; added `data.put("canonicalTimezone", ...)` to both listeners
- [x] [Review][Defer] No cross-field bean validation enforcing `requestedEndTime > requestedStartTime` in `CreateBookingRequest` — service covers the check with `OperationNotAllowedException`; a proper `@AssertTrue` constraint would return 400 instead of the security-semantics error; nice-to-have [`CreateBookingRequest.java`] — deferred, service-level validation sufficient
- [x] [Review][Defer] `ShortCode.shortenInt(UUID.randomUUID().hashCode())` envelope idempotency key has ~77k birthday collision threshold; collision causes `DataIntegrityViolationException` in MailManager, corrupting the original envelope's delivery record on retry; pre-existing across all notification types [`BookingEmailListener.java`] — deferred, pre-existing notification module issue
- [x] [Review][Defer] `acceptBooking` returns `effectiveCreditsRemaining = 0` hardcoded — coach response; coach has no use for parent credit count; parent dashboard reads credits from `getParentBookings`, not from this response [`BookingService.java:acceptBooking`] — deferred, intentional for coach context
- [x] [Review][Defer] `isRetryable` in `MailManager` checks the wrapping `RuntimeException` class rather than the cause — a wrapped `MailParseException` is retried up to `MAX_RETRY_ATTEMPTS` despite being non-recoverable; pre-existing notification module bug [`MailManager`] — deferred, pre-existing
- [x] [Review][Defer] `((Principal) securityUtil.getCurrentUser())` unchecked cast in `BookingResource` — `ClassCastException` returns 500 if principal is not the expected type; pre-existing pattern across all platform controllers [`BookingResource.java:70,82`] — deferred, pre-existing
- [x] [Review][Dismiss] `Instant.now().plus(Duration.ofDays(1))` as `Envelope` third parameter — confirmed to be `deadline` (abandon-after TTL), not scheduled delivery time; no email delay; correct behavior
- [x] [Review][Dismiss] ACCEPTED state never persisted in `acceptBooking` — spec-mandated: "REQUESTED → ACCEPTED → CONFIRMED in a single transaction (no intermediate payment step)"; ephemeral ACCEPTED is the spec design
- [x] [Review][Dismiss] Credit deduction absent from `acceptBooking` — credits are deducted only at COMPLETED per Story 3.6; explicitly out of scope for 3.3
- [x] [Review][Dismiss] `getCoachBookingRequests` returns only REQUESTED-status bookings — coach inbox is pending-approvals only per spec; correct filter
- [x] [Review][Dismiss] `onBookingReminder` shared `data` map across two `Envelope` publishes — `Envelope` record's `data()` field is defensively copied inside the notification infrastructure before async dispatch; latent but not an active bug
- [x] [Review][Dismiss] Second `validateTransition("ACCEPTED", "CONFIRMED")` in `acceptBooking` validates a hardcoded string — intentional: verifies the ALLOWED_TRANSITIONS map is internally consistent, not re-checking entity state; correct

#### Group D — Frontend (api, store, pages, router, i18n, BookingStateChip)

- [x] [Review][Patch] `coach/booking-requests` route uses `meta: { role: 'COACH' }` but the router guard only checks `meta.requiresCoach`; authenticated parents can navigate to the coach inbox without frontend role enforcement (backend still returns 403) [`routes.js:136-140`, `router/index.js:55`] — PATCHED: changed to `meta: { requiresAuth: true, requiresCoach: true }`
- [x] [Review][Patch] `loadCoachBookingRequests` has `try/finally` but no `catch` — API error propagates as unhandled rejection; `onMounted` calls it without `await`, making the rejection completely uncaught; inconsistent with all other `load*` functions which have `catch (e)` + error state [`booking.store.js:129-137`] — PATCHED: added `coachRequestsError` ref, `try/catch/finally` block, exported error state
- [x] [Review][Patch] `coachName` declared as `ref('')` and never assigned — page title renders "Book a session with " (empty); `profile.value.displayName` is available in `CoachPublicProfilePage.handleCta()` at navigation time [`BookingRequestPage.vue:88`, `CoachPublicProfilePage.vue:313`] — PATCHED: pass `coachName` as query param via URLSearchParams in CoachPublicProfilePage; read from `route.query.coachName` in BookingRequestPage
- [x] [Review][Defer] `canonicalTimezone` sent as parent's browser timezone (`Intl.DateTimeFormat().resolvedOptions().timeZone`) rather than coach's timezone — coach's schedule is defined in their timezone; coach-side email shows session time in parent's timezone which can be confusing; address in Story 3.5 (Scheduling Views & Timezone Management) [`BookingRequestPage.vue:121`]
- [x] [Review][Defer] `formatSlot` uses `toLocaleString()` with no timezone — slots are displayed in the parent's browser timezone, not the coach's; inconsistent with `ParentBookingsPage` which passes `canonicalTimezone` to `formatDateTime`; address in Story 3.5 [`BookingRequestPage.vue:104-106`]
- [x] [Review][Defer] `handleAccept`/`handleDecline` use `try/finally` without `catch` — on 4xx/5xx the button spinner stops but no error message is shown; coach cannot tell whether their action succeeded [`CoachBookingRequestsPage.vue:75-92`] — deferred, UX error-feedback story
- [x] [Review][Defer] `ParentBookingsPage.vue` has no error branch in template — `bookingsError` is set on API failure but never rendered; user sees misleading empty state instead of error message [`ParentBookingsPage.vue`] — deferred, pre-existing UX pattern
- [x] [Review][Defer] `submit()` in `BookingRequestPage` has no error feedback — if `submitBookingRequest` throws (400, 403, network), user stays on page with no indication of failure [`BookingRequestPage.vue:112-128`] — deferred, UX error-feedback story
- [x] [Review][Dismiss] `coachId` route param sent as string to API — JSON REST serializes UUIDs as strings; backend accepts; no cast needed
- [x] [Review][Dismiss] `creditsForCoach` uses strict equality `p.coachId === coachId` — both values are JSON-deserialized strings; strict equality is correct
- [x] [Review][Dismiss] `playerId` null path: packs are only loaded `if (playerId.value)`; without packs, `hasCredits = false` and `canSubmit` blocks submit; null `playerId` cannot reach the backend
- [x] [Review][Dismiss] `BookingStateChip` unknown status falls back to raw string + `chip--neutral` — graceful degradation; all spec-defined statuses are covered; EXPIRED is not a persisted status (spec uses DECLINED for expired bookings)
- [x] [Review][Dismiss] `parentName: null` → "Parent: null" concern — `resolveParentName` always returns `"Unknown Parent"` as fallback, never null; coach inbox endpoint always populates the field
- [x] [Review][Dismiss] Simultaneous accept/decline race condition — two concurrent GETs on the same ref; last-write-wins converges to correct DB state; no data corruption
- [x] [Review][Dismiss] `getParentBookings` vs `getCoachBookingRequests` path collision risk — paths are structurally distinct (`/requests` vs `/requests/coach`); backend enforces role via `@PreAuthorize`

#### Group E — Tests (BookingServiceTest, BookingRequestResourceIT)

- [x] [Review][Patch] `createBookingRequest_creditsFullyReservedByInFlightBookings_throwsOperationNotAllowedException` is byte-for-byte identical to `createBookingRequest_noCredits_throwsOperationNotAllowedException` — both stub `hasCredits=false`; at unit-mock level the two rejection paths are indistinguishable because `hasCredits()` already internalises in-flight deduction (`(creditsRemaining - inFlight) > 0`); the duplicate adds zero coverage; AC 8 soft-reservation is covered end-to-end by `createBookingRequest_secondRequestWhenSingleCreditAlreadyInFlight_returns403` [`BookingServiceTest.java:121-136`] — PATCHED: deleted duplicate unit test
- [x] [Review][Patch] `countInFlightBookings` mock in `createBookingRequest_hasCredits` passes `int 0` to a `long`-returning method — exposed as compile error after removing the duplicate; Mockito cannot widen `Integer` to `Long` here [`BookingServiceTest.java:89`] — PATCHED: changed to `0L`
- [x] [Review][Patch] `effectiveCreditsRemaining` IT assertion uses `isGreaterThanOrEqualTo(0)` — after creating 1 REQUESTED booking against a 3-credit pack, effective = 3 raw − 1 in-flight = 2 deterministically; the weak assertion would accept any non-negative value including silent regressions in the credit arithmetic [`BookingRequestResourceIT.java:212`] — PATCHED: changed to `isEqualTo(2)`
- [x] [Review][Defer] `getParentBookings_returnsListSortedByStartTime` only checks HTTP 200 + non-null body — sort order never asserted despite what the test name says; a regression removing the `OrderBy` from `findAllByParentIdOrderByRequestedStartTimeAsc` would not be caught [`BookingRequestResourceIT.java:480`] — deferred, needs multi-booking setup
- [x] [Review][Defer] `parentName` field never asserted in `getCoachBookingRequests_returnsOnlyRequestedBookingsForThisCoach` — AC 8 explicitly requires coach inbox to show parent name; the IT response body is not inspected for this field [`BookingRequestResourceIT.java:524`] — deferred, coverage gap
- [x] [Review][Defer] Authority id 9502 inserted in `playerNotOwnedByParent_returns403` is not deleted — the `finally` block cleans up the user and user_authority rows for `9500000099` but not the authority row; `@AfterEach` only deletes ids 9500 and 9501 [`BookingRequestResourceIT.java:289`] — deferred, test data leak
- [x] [Review][Defer] `declineBooking_requestedBooking_transitionsToDeclined` uses `any(BookingDeclinedEvent.class)` — `canonicalTimezone` field not captured or asserted; a null-setter regression on the event would pass undetected [`BookingServiceTest.java:244`] — deferred, assertion completeness
- [x] [Review][Defer] No wrong-coach IT test for decline — `acceptBooking_wrongCoach_returns403` exists; no equivalent for decline; a misconfiguration in the decline security path would go undetected at the IT level [`BookingRequestResourceIT.java`] — deferred, coverage gap
- [x] [Review][Dismiss] `slotOutsideAvailabilityWindow_returns422` 21-day timing concern — window DOW = tomorrow's DOW; `Instant.now() + 21 days` DOW = today's DOW; today's DOW ≠ tomorrow's DOW (adjacent days always differ); DOW check fails before time-of-day is evaluated; test is structurally safe
- [x] [Review][Dismiss] `makeUser` missing firstName/lastName — no current unit test exercises `resolveParentName()` (no `getCoachBookingRequests` unit test exists); not a current failure
- [x] [Review][Dismiss] Reflection-based entity ID injection (`try { ... } catch (Exception ignored) {}`) in test helpers — established pattern throughout the project's test suite
- [x] [Review][Dismiss] `.isIn(HttpStatus.FORBIDDEN, HttpStatus.UNPROCESSABLE_ENTITY)` status assertions — both represent rejection; acceptable boundary assertion for business-rule violations that map to security vs. validation errors depending on how the exception is mapped

## Dev Notes

### ⚠️ CRITICAL: parentId and playerId Are LONG, Not UUID

The epics spec says "parentId UUID, playerId UUID" — **this is WRONG**. From the established codebase:
- `PlayerProfile.id` is `Long` (TSID from `BaseEntity`) — schema: `main.player_profiles.id BIGINT`
- Parent user IDs are `Long` (TSID) from `main.user.id BIGINT`
- Only `CoachProfile.id` is UUID (explicitly `@GeneratedValue(strategy = GenerationType.UUID)`)

Use `Long parentId` and `Long playerId` in the entity and all service signatures.

### ⚠️ CRITICAL: Endpoint Naming — Use /api/bookings/requests (Not /api/booking/bookings)

The epics dev notes say `POST /api/booking/bookings` (singular module, duplicated resource name). Both issues are wrong:
- Module prefix is PLURAL: `/api/bookings/` (confirmed by existing `AvailabilityResource` and `SessionPackResource`)
- Resource path should be `/requests` to avoid the `/api/bookings/bookings` redundancy

Correct endpoints:
```
POST   /api/bookings/requests            → create booking request
GET    /api/bookings/requests            → list parent's bookings
PUT    /api/bookings/requests/{id}/accept  → coach accepts
PUT    /api/bookings/requests/{id}/decline → coach declines
```

### Flyway Migration V31

Next available migration is **V31** (V30 was `booking_session_packs.sql`).

```sql
-- V31__booking_requests.sql

-- New platform config for booking request expiry
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (38, 'booking.request_expiry_hours', '48', 'LONG', 'Hours before unaccepted booking request auto-expires', NOW())
ON CONFLICT (key) DO NOTHING;

CREATE TABLE booking.bookings (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id               BIGINT       NOT NULL,
    player_id               BIGINT       NOT NULL,
    coach_id                UUID         NOT NULL,
    requested_start_time    TIMESTAMPTZ  NOT NULL,
    requested_end_time      TIMESTAMPTZ  NOT NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'REQUESTED',
    canonical_timezone      VARCHAR(50)  NOT NULL,
    notes                   VARCHAR(500),
    version                 INT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    primary_reminder_sent_at   TIMESTAMPTZ,
    secondary_reminder_sent_at TIMESTAMPTZ,
    CONSTRAINT chk_bkg_status CHECK (status IN (
        'REQUESTED','ACCEPTED','CONFIRMED','UPCOMING',
        'DECLINED','COMPLETED','DISPUTED','CANCELLED'
    )),
    CONSTRAINT chk_bkg_end_after_start CHECK (requested_end_time > requested_start_time)
);

CREATE INDEX idx_bkg_parent_id           ON booking.bookings (parent_id);
CREATE INDEX idx_bkg_coach_id            ON booking.bookings (coach_id);
CREATE INDEX idx_bkg_status_created      ON booking.bookings (status, created_at);
CREATE INDEX idx_bkg_status_start        ON booking.bookings (status, requested_start_time);
CREATE INDEX idx_bkg_player_coach_status ON booking.bookings (player_id, coach_id, status);
```

Note: CHECK constraint includes `CANCELLED` (Story 3.9) and other future terminal states (COMPLETED, DISPUTED) now to avoid an ALTER TABLE mid-epic. DO NOT use a PostgreSQL ENUM type (consistent with existing patterns). The `idx_bkg_player_coach_status` index is required — `countInFlightBookings(playerId, coachId)` runs on every booking request and the other indexes do not cover `(player_id, coach_id)` together.

### Booking Entity

```java
// platform.booking.repo.Booking
@Entity
@Table(schema = "booking", name = "bookings")
@Getter @Setter @NoArgsConstructor
public class Booking {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;          // Long TSID — NOT UUID

    @Column(name = "player_id", nullable = false)
    private Long playerId;          // Long TSID — NOT UUID

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "requested_start_time", nullable = false)
    private Instant requestedStartTime;

    @Column(name = "requested_end_time", nullable = false)
    private Instant requestedEndTime;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "canonical_timezone", nullable = false, length = 50)
    private String canonicalTimezone;

    @Column(length = 500)
    private String notes;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "primary_reminder_sent_at")
    private Instant primaryReminderSentAt;

    @Column(name = "secondary_reminder_sent_at")
    private Instant secondaryReminderSentAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = "REQUESTED";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

### Valid State Transitions (Story 3.3)

Implement as a validation map in `BookingService.validateTransition()`:

```
REQUESTED → ACCEPTED  (event: ACCEPT, actor: coach)
ACCEPTED → CONFIRMED  (event: CONFIRM, actor: system within acceptBooking — no payment step)
REQUESTED → DECLINED  (event: DECLINE, actor: coach)
REQUESTED → DECLINED  (event: EXPIRE, actor: system/scheduler)
CONFIRMED → UPCOMING  (event: UPCOMING_TRANSITION, actor: scheduler)
```

Later stories add: UPCOMING → COMPLETED, COMPLETED → DISPUTED, etc.

```java
private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
    "REQUESTED", Set.of("ACCEPTED", "DECLINED"),
    "ACCEPTED",  Set.of("CONFIRMED"),
    "CONFIRMED", Set.of("UPCOMING"),
    "UPCOMING",  Set.of()  // Story 3.6 adds COMPLETED
);

private void validateTransition(String from, String to) {
    Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
    if (!allowed.contains(to)) {
        throw new OperationNotAllowedException(
            "Invalid booking transition: " + from + " → " + to,
            SecurityError.MISSING_RIGHTS
        );
    }
}
```

### BookingService: acceptBooking Logic

The accept flow runs three state transitions in a single `@Transactional` method:

```java
@Transactional
public BookingResponse acceptBooking(UUID bookingId, Long coachUserId) {
    Booking booking = repository.findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

    // Verify the coach owns this booking slot
    CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    if (!Objects.equals(booking.getCoachId(), coach.getId())) {
        throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
    }

    // REQUESTED → ACCEPTED → CONFIRMED (no payment step — credits were pre-purchased in Story 3.2)
    validateTransition(booking.getStatus(), "ACCEPTED");
    booking.setStatus("ACCEPTED");
    validateTransition("ACCEPTED", "CONFIRMED");
    booking.setStatus("CONFIRMED");

    repository.save(booking);

    eventPublisher.publishEvent(buildConfirmedEvent(booking, coach.getDisplayName()));
    return toResponse(booking, coach.getDisplayName(), "");
}
```

Note: `PaymentGateway` is intentionally NOT injected into `BookingService` in Story 3.3. Payment was already captured when the parent purchased their session pack (Story 3.2). Epic 7 will introduce a Stripe pre-authorisation step when the real payment flow is wired.

### BookingService: createBookingRequest Logic

```java
@Transactional
public BookingResponse createBookingRequest(Long parentId, Long playerId,
                                            CreateBookingRequest req) {
    // 1. Verify player ownership
    PlayerProfile player = playerProfileRepository.findById(playerId)
        .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
    if (!Objects.equals(player.getParentId(), parentId)) {
        throw new OperationNotAllowedException("Parent does not own this player", SecurityError.MISSING_RIGHTS);
    }

    // 2. Load coach and verify ACTIVE status
    CoachProfile coach = coachProfileRepository.findById(req.coachId())
        .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    if (coach.getStatus() != CoachProfileStatus.ACTIVE) {
        throw new OperationNotAllowedException("Coach profile is not active", SecurityError.MISSING_RIGHTS);
    }

    // 3. Validate requestedStartTime is in the future (belt-and-suspenders beyond @Future DTO constraint)
    if (!req.requestedStartTime().isAfter(Instant.now())) {
        throw new OperationNotAllowedException("Requested start time must be in the future", SecurityError.MISSING_RIGHTS);
    }

    // 4. Validate slot is within a coach availability window
    List<CoachAvailabilityWindow> windows =
        coachAvailabilityWindowRepository.findByCoachId(req.coachId());
    boolean slotCovered = windows.stream().anyMatch(w ->
        !req.requestedStartTime().isBefore(w.getStartTime()) &&
        !req.requestedEndTime().isAfter(w.getEndTime())
    );
    if (!slotCovered) {
        throw new OperationNotAllowedException("Requested slot is not within coach availability", SecurityError.MISSING_RIGHTS);
    }

    // 5. Lock pack rows and verify effective credits (prevents concurrent double-booking)
    // Acquires PESSIMISTIC_WRITE on the pack rows for the duration of this transaction.
    sessionPackPurchasedRepository.findActivePacksForDeduction(playerId, req.coachId());
    if (!sessionPackService.hasCredits(playerId, req.coachId())) {
        throw new OperationNotAllowedException("No effective session credits available for this coach", SecurityError.MISSING_RIGHTS);
    }

    // 6. Create booking
    Booking booking = new Booking();
    booking.setParentId(parentId);
    booking.setPlayerId(playerId);
    booking.setCoachId(req.coachId());
    booking.setRequestedStartTime(req.requestedStartTime());
    booking.setRequestedEndTime(req.requestedEndTime());
    booking.setCanonicalTimezone(req.canonicalTimezone());
    booking.setNotes(req.notes());
    repository.save(booking);

    // 7. Notify coach
    String coachEmail = resolveEmail(coach.getUserId());
    eventPublisher.publishEvent(new BookingRequestedEvent(
        this, booking.getId(), parentId, playerId, req.coachId(),
        coach.getDisplayName(), coachEmail, req.notes(),
        req.requestedStartTime(), req.canonicalTimezone()
    ));

    int effectiveCredits = sessionPackService.getCreditsRemaining(playerId, req.coachId())
        - bookingRepository.countInFlightBookings(playerId, req.coachId());
    return toResponse(booking, coach.getDisplayName(), player.getName(), effectiveCredits);
}
```

### BookingService: declineBooking Logic

```java
@Transactional
public void declineBooking(UUID bookingId, Long coachUserId) {
    Booking booking = repository.findById(bookingId)
        .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

    CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    if (!Objects.equals(booking.getCoachId(), coach.getId())) {
        throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
    }

    validateTransition(booking.getStatus(), "DECLINED");
    booking.setStatus("DECLINED");
    repository.save(booking);

    eventPublisher.publishEvent(new BookingDeclinedEvent(
        this, booking.getId(), booking.getParentId(),
        resolveEmail(booking.getParentId()),
        coach.getDisplayName(), booking.getRequestedStartTime()
    ));
}
```

### BookingExpiryScheduler Logic

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void expireStaleRequests() {
        long expiryHours = configService.getLong("booking.request_expiry_hours");
        Instant threshold = Instant.now().minus(Duration.ofHours(expiryHours));
        List<Booking> stale = bookingRepository.findRequestedBookingsOlderThan(threshold);
        for (Booking booking : stale) {
            booking.setStatus("DECLINED");
            bookingRepository.save(booking);
            // IMPORTANT: publish BookingExpiredEvent, NOT BookingDeclinedEvent.
            // The parent must receive "request timed out" wording, not "coach declined".
            CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElse(null);
            String coachName = coach != null ? coach.getDisplayName() : "Coach";
            eventPublisher.publishEvent(new BookingExpiredEvent(
                this, booking.getId(), booking.getParentId(),
                resolveEmail(booking.getParentId()), coachName,
                booking.getRequestedStartTime()
            ));
            log.info("Auto-expired booking {} (created at {})", booking.getId(), booking.getCreatedAt());
        }
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }
}
```

### BookingReminderScheduler Logic

```java
@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
@Transactional
public void processReminderWindows() {
    long primaryHours   = configService.getLong("platform.reminder_interval_primary_hours");   // ID 35 = 24
    long secondaryHours = configService.getLong("platform.reminder_interval_secondary_hours"); // ID 36 = 2
    Instant now = Instant.now();

    // 1. CONFIRMED → UPCOMING + send 24h reminder
    // Uses requestedStartTime <= windowEnd (NOT BETWEEN now AND windowEnd).
    // The <= form also catches CONFIRMED bookings whose start time is already past
    // (scheduler-downtime catch-up), which BETWEEN would silently skip.
    Instant primaryWindowEnd = now.plus(Duration.ofHours(primaryHours));
    List<Booking> toTransition = bookingRepository.findConfirmedForUpcomingTransition(primaryWindowEnd);
    for (Booking b : toTransition) {
        b.setStatus("UPCOMING");
        b.setPrimaryReminderSentAt(now);
        bookingRepository.save(b);
        eventPublisher.publishEvent(buildReminderEvent(b, "PRIMARY"));
        // Listener sends two separate emails (parent + coach) for each event
    }

    // 2. UPCOMING + send 2h reminder
    Instant secondaryWindowEnd = now.plus(Duration.ofHours(secondaryHours));
    List<Booking> toRemind = bookingRepository.findUpcomingWithin2hWindow(now, secondaryWindowEnd);
    for (Booking b : toRemind) {
        b.setSecondaryReminderSentAt(now);
        bookingRepository.save(b);
        eventPublisher.publishEvent(buildReminderEvent(b, "SECONDARY"));
    }
}
```

### BookingRepository Queries

```java
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findAllByParentIdOrderByRequestedStartTimeAsc(Long parentId);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'REQUESTED' AND b.createdAt < :threshold
        """)
    List<Booking> findRequestedBookingsOlderThan(@Param("threshold") Instant threshold);

    // Uses <= :windowEnd (not BETWEEN :now AND :windowEnd) so bookings whose start time
    // is already past (scheduler downtime) are caught as well — catch-up behaviour.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'CONFIRMED'
          AND b.requestedStartTime <= :windowEnd
          AND b.primaryReminderSentAt IS NULL
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findConfirmedForUpcomingTransition(
        @Param("windowEnd") Instant windowEnd
    );

    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'UPCOMING'
          AND b.requestedStartTime BETWEEN :now AND :windowEnd
          AND b.secondaryReminderSentAt IS NULL
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findUpcomingWithin2hWindow(
        @Param("now") Instant now,
        @Param("windowEnd") Instant windowEnd
    );

    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.playerId = :playerId
          AND b.coachId = :coachId
          AND b.status IN ('REQUESTED', 'ACCEPTED', 'CONFIRMED', 'UPCOMING')
        """)
    int countInFlightBookings(
        @Param("playerId") Long playerId,
        @Param("coachId") UUID coachId
    );
}
```

### Email Resolution Pattern

`BookingService` needs to resolve email addresses before event construction. Use a single private helper named **`resolveEmail`** — do NOT create variants like `resolveEmailByUserId` or `resolveParentEmail`:

```java
private String resolveEmail(Long userId) {
    return userRepository.findById(userId)
        .map(user -> user.getEmail())
        .orElse("");
}
```

The User entity is in `main."user"` table. Find `UserRepository` in `platform.security.repo`. Use `resolveEmail()` everywhere in `BookingService` and the scheduler classes.

### EmailTemplate Additions

Add to `platform.notification.contract.EmailTemplate` enum:

```java
BOOKING_REQUESTED("email.booking.requested.title"),
BOOKING_CONFIRMED("email.booking.confirmed.title"),
BOOKING_DECLINED("email.booking.declined.title"),
BOOKING_EXPIRED("email.booking.expired.title"),   // auto-expiry — distinct from DECLINED
BOOKING_REMINDER("email.booking.reminder.title"),
```

### Thymeleaf Email Templates

Create minimal templates in `src/main/resources/templates/email/`. Follow the structure of existing templates (check what template files already exist — find `src/main/resources/templates/` for examples). Each template receives the `data` map from the `Envelope` object. Minimum viable structure:

```html
<!-- booking-requested.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <h2>New booking request</h2>
  <p>You have a new session request for <span th:text="${data.requestedStartTime}"></span>.</p>
  <p>Player notes: <span th:text="${data.notes}">No notes provided</span></p>
</body>
</html>
```

The `data` map keys for each event type:
- `BOOKING_REQUESTED` (to coach via `event.getCoachEmail()`): `coachDisplayName`, `requestedStartTime`, `canonicalTimezone`, `notes`
- `BOOKING_CONFIRMED` (to parent): `coachDisplayName`, `requestedStartTime`, `canonicalTimezone`
- `BOOKING_DECLINED` (to parent — coach actively declined): `coachDisplayName`, `requestedStartTime`
- `BOOKING_EXPIRED` (to parent — request timed out, coach did not respond): `coachDisplayName`, `requestedStartTime`
- `BOOKING_REMINDER` (two separate `Envelope` publishes — one to parent, one to coach): `coachDisplayName`, `requestedStartTime`, `canonicalTimezone`, `reminderType`

### BookingEmailListener Pattern

Follow `AccountChangeEmailListener.java` exactly. Critical notes:
- `onBookingRequested` sends to the **coach** via `event.getCoachEmail()` (not `event.getParentEmail()`)
- `onBookingReminderEvent` fires **two separate `publisher.publishEvent(envelope)` calls** in one handler — one for parent, one for coach
- `onBookingExpired` uses `EmailTemplate.BOOKING_EXPIRED` (distinct template from `BOOKING_DECLINED`)
- The listener does NOT inject `UserRepository` — all email addresses are resolved in `BookingService` before event construction

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEmailListener {

    private final ApplicationEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingRequested(BookingRequestedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        data.put("notes", event.getNotes() != null ? event.getNotes() : "");

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getCoachEmail()); // coach is notified, not parent

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_REQUESTED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingExpired(BookingExpiredEvent event) {
        // Separate from onBookingDeclined — "coach did not respond" vs "coach declined"
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());

        Recipient recipient = new Recipient();
        recipient.setEmail(event.getParentEmail());

        publisher.publishEvent(new Envelope(
            List.of(recipient), EmailTemplate.BOOKING_EXPIRED,
            Instant.now().plus(Duration.ofDays(1)), data,
            ShortCode.shortenInt(UUID.randomUUID().hashCode())
        ));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingReminder(BookingReminderEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", event.getCoachDisplayName());
        data.put("requestedStartTime", event.getRequestedStartTime().toString());
        data.put("canonicalTimezone", event.getCanonicalTimezone());
        data.put("reminderType", event.getReminderType());

        // Two separate emails — one per recipient
        for (String email : List.of(event.getParentEmail(), event.getCoachEmail())) {
            Recipient recipient = new Recipient();
            recipient.setEmail(email);
            publisher.publishEvent(new Envelope(
                List.of(recipient), EmailTemplate.BOOKING_REMINDER,
                Instant.now().plus(Duration.ofDays(1)), data,
                ShortCode.shortenInt(UUID.randomUUID().hashCode())
            ));
        }
    }
    // ... onBookingConfirmed, onBookingDeclined follow same pattern
}
```

### Coach Identity in BookingResource

For coach endpoints (accept/decline), extract the coach's user ID from JWT, not the coach profile UUID:

```java
private Long currentCoachUserId() {
    String businessId = ((Principal) securityUtil.getCurrentUser()).getBusinessId();
    if (businessId == null || businessId.isBlank()) {
        throw new InsufficientAuthenticationException("Principal has no business ID");
    }
    try {
        return Long.parseLong(businessId);
    } catch (NumberFormatException e) {
        throw new InsufficientAuthenticationException("Invalid business ID format in principal");
    }
}
```

Then `BookingService.acceptBooking(bookingId, coachUserId)` resolves `coachUserId → CoachProfile` via `coachProfileRepository.findByUserId(coachUserId)` — same pattern as `AvailabilityService.requireProfile(userId)`.

### BookingResponse DTO

```java
// platform.booking.contract
public record BookingResponse(
    UUID id,
    Long playerId,
    String playerName,
    UUID coachId,
    String coachDisplayName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    String notes,
    Instant createdAt,
    int effectiveCreditsRemaining  // sumActiveCredits - countInFlightBookings at query time
) {}
```

`effectiveCreditsRemaining` eliminates the need for `ParentBookingsPage` to call `loadPlayerPacks()` separately. The value is computed in `getParentBookings()` for each booking response using `sessionPackService.getCreditsRemaining(b.getPlayerId(), b.getCoachId())` minus `bookingRepository.countInFlightBookings(b.getPlayerId(), b.getCoachId())`. Because this is a read-only display (no lock needed), `getCreditsRemaining()` is sufficient without the pessimistic lock.

### CreateBookingRequest DTO

```java
// platform.booking.contract
public record CreateBookingRequest(
    @NotNull UUID coachId,
    @NotNull Long playerId,
    @NotNull @Future Instant requestedStartTime,    // must be in the future
    @NotNull Instant requestedEndTime,
    @NotBlank String canonicalTimezone,
    @Size(max = 500) String notes                   // matches VARCHAR(500) in schema
) {}
```

### IT Test Setup Pattern

Identical annotation stack to `SessionPackResourceIT.java`:

```java
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class BookingRequestResourceIT {
    private static final long PARENT_ID   = 9500000001L;
    private static final long PLAYER_ID   = 9500000002L;
    private static final long COACH_USER_ID = 9500000010L;
    private static final String PARENT_EMAIL = "parent.booking@skillars-test.com";
    private static final String COACH_EMAIL  = "coach.booking@skillars-test.com";
    private UUID coachProfileId;
```

In `@BeforeEach`: insert parent user, player profile, coach user + coach profile + coach pricing; insert a `booking.session_packs_purchased` record with `credits_remaining = 3` so `hasCredits()` returns true for the test parent+player+coach combination.

In `@AfterEach`: `DELETE FROM booking.bookings WHERE parent_id = ?`, `DELETE FROM booking.session_packs_purchased WHERE parent_id = ?`, then coach/player/user cleanup. Use parameterized deletes — never unparameterized string concatenation.

For `acceptBooking_wrongCoach_returns403`: create a second coach user (ID `9500000020L`) with a different coach profile; have them attempt to accept PARENT_ID's booking — must return 403.

### Frontend: BookingStateChip.vue

```vue
<template>
  <q-chip :class="chipClass" dense>{{ label }}</q-chip>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({ status: { type: String, required: true } })
const { t } = useI18n()

const statusMap = {
  REQUESTED: { key: 'booking.requests.statusRequested', cls: 'chip--warning'  },
  ACCEPTED:  { key: 'booking.requests.statusAccepted',  cls: 'chip--primary'  },
  CONFIRMED: { key: 'booking.requests.statusConfirmed', cls: 'chip--primary'  },
  UPCOMING:  { key: 'booking.requests.statusUpcoming',  cls: 'chip--primary'  },
  DECLINED:  { key: 'booking.requests.statusDeclined',  cls: 'chip--error'    },
  // Stubs for future stories — prevents raw-string fallback if status arrives before story ships
  COMPLETED: { key: 'booking.requests.statusCompleted', cls: 'chip--neutral'  }, // Story 3.6
  CANCELLED: { key: 'booking.requests.statusCancelled', cls: 'chip--neutral'  }, // Story 3.9
  DISPUTED:  { key: 'booking.requests.statusDisputed',  cls: 'chip--neutral'  }, // Story 3.6
}

const label = computed(() => {
  const entry = statusMap[props.status]
  return entry ? t(entry.key) : props.status
})
const chipClass = computed(() => statusMap[props.status]?.cls ?? 'chip--neutral')
</script>
```

CSS classes `chip--warning`, `chip--primary`, `chip--error` should use CSS token variables (`--accent-warning`, `--accent-primary`, `--color-error`) from the design system. Do NOT hardcode hex colors.

### Cross-Module Dependencies

`BookingService` legitimately uses:
- `platform.marketplace.repo.CoachProfileRepository` — resolve coach profile from coachId or userId; check coach ACTIVE status
- `platform.marketplace.repo.CoachAvailabilityWindowRepository` — validate requested slot is within a coach availability window (from Story 3.1)
- `platform.security.repo.PlayerProfileRepository` — verify player-to-parent ownership
- `platform.booking.service.SessionPackService` — check effective credits (`hasCredits()`) and get credits remaining for response
- `platform.booking.repo.SessionPackPurchasedRepository` — acquire pessimistic lock on pack rows before credit check (prevents concurrent double-booking)
- `platform.security.repo.UserRepository` — resolve email addresses for notifications

`SessionPackService` gains a new dependency in this story:
- `platform.booking.repo.BookingRepository` — injected to call `countInFlightBookings()` in `hasCredits()`

No circular dependency: `BookingService → SessionPackService → BookingRepository` (one-way chain). `PaymentGateway` is NOT injected into `BookingService` in Story 3.3 (payment was pre-captured in Story 3.2).

These cross-module imports follow the same pattern established in Story 3.1 and 3.2.

### hasCredits() Dependency Note

`SessionPackService.hasCredits(Long playerId, UUID coachId)` was implemented in Story 3.2 and is `public`. **This story must update it** to subtract in-flight bookings from `creditsRemaining` before returning (see Dev Notes: TOCTOU section for full implementation). `BookingService` calls it unchanged — the contract of the method stays the same; only the internal logic of `SessionPackService` changes.

### No Credit Deduction in Story 3.3

Credits are checked but NOT deducted in Story 3.3. Credits are only deducted at `COMPLETED` status transition in Story 3.6. `SessionPackService.deductCredit()` is already implemented and ready; Story 3.6 will call it.

### Coach Commitment Contract — Credits Are Coach-Specific and Price-Locked

Session pack credits represent a contractual commitment by the coach to the parent. Two invariants must be preserved everywhere in the codebase:

**1. Credits are coach-specific.** A credit purchased from Coach A cannot be used to book with Coach B. This is enforced at the database level (`booking.session_packs_purchased.coach_id`) and at the service level via `SessionPackService.hasCredits(Long playerId, UUID coachId)` — which only returns `true` if an ACTIVE pack exists for THAT specific `(playerId, coachId)` combination. `createBookingRequest` must pass `req.coachId()` to `hasCredits()` — never omit the coachId or check credits globally.

**2. Credits are price-locked at purchase time.** When a parent purchases a session pack at €50/session, those credits remain valid at that rate even if the coach subsequently raises their prices to €60/session. The `session_packs_purchased.total_price` (captured at purchase) is the binding price; current coach pricing is irrelevant once credits are held.

**DO NOT** re-validate credit price against current coach pricing in `createBookingRequest`. The check is purely `hasCredits()` — no price comparison, no re-authorisation against current `marketplace.coach_pricing` or `marketplace.session_packs` values. Adding such a check would break the price-lock guarantee.

This means `BookingService` must NOT inject `CoachPricingRepository` for credit validation purposes. `CoachProfileRepository` is injected only to resolve the coach display name and verify the coach exists.

### TOCTOU: Credit Soft-Reservation via In-Flight Booking Count

**The race condition without this fix:** A parent with 1 credit could call `POST /api/bookings/requests` twice before either booking is resolved. Both calls would pass `hasCredits()` (credit not yet deducted — deduction only happens at COMPLETED in Story 3.6), resulting in two CONFIRMED bookings against a single credit.

**The fix:** `SessionPackService.hasCredits()` must subtract the count of in-flight bookings when determining effective credit availability. An in-flight booking is any booking for the same `(playerId, coachId)` pair with status `REQUESTED`, `ACCEPTED`, `CONFIRMED`, or `UPCOMING` — these represent credits that are logically committed even though `creditsRemaining` has not yet been decremented.

**Updated `SessionPackService.hasCredits()` logic:**

```java
// In SessionPackService — field is named 'repository', not 'sessionPacksPurchasedRepository'
public boolean hasCredits(Long playerId, UUID coachId) {
    // Use sumActiveCredits() — this method exists in SessionPackPurchasedRepository.
    // Do NOT use findActivePacksForDeduction() here — that method acquires a PESSIMISTIC_WRITE
    // lock and is reserved for deduction only.
    int creditsRemaining = repository.sumActiveCredits(playerId, coachId);
    int inFlight = bookingRepository.countInFlightBookings(playerId, coachId);
    return (creditsRemaining - inFlight) > 0;
}
```

`SessionPackService` gains `BookingRepository` as an injected dependency (constructor injection via `@RequiredArgsConstructor`). No circular dependency: `BookingService → SessionPackService → BookingRepository`.

**Why not deduct at REQUESTED instead?** Deducting at REQUESTED would require a restore path on every DECLINED or EXPIRED transition — adding complexity across the expiry scheduler, decline flow, and future cancellation story. The soft-reservation approach (count in-flight, deduct only at COMPLETED) achieves the same correctness with no restore logic. When a booking is DECLINED or expires, it drops from the in-flight count automatically.

**Concurrency note — single-credit race:** Under Postgres's default READ COMMITTED isolation, two concurrent `POST /api/bookings/requests` calls with a single available credit can both read `inFlight=0` before either commits, both pass `hasCredits()`, and both be persisted. This is a real risk for the common 1-session purchase. To prevent it: within `createBookingRequest`, after verifying ownership and before calling `hasCredits()`, acquire a pessimistic lock on the pack rows:

```java
// In createBookingRequest, before hasCredits() check:
// Lock the pack row(s) so concurrent requests queue rather than race.
// Uses the existing PESSIMISTIC_WRITE query from the repository.
List<SessionPackPurchased> lockedPacks =
    sessionPackPurchasedRepository.findActivePacksForDeduction(playerId, coachId);
// Then call hasCredits() — the lock is held for the duration of the transaction.
if (!sessionPackService.hasCredits(playerId, coachId)) { ... }
```

This reuses the `findActivePacksForDeduction` lock that already exists for `deductCredit()`. The lock is released when the transaction commits (booking saved) or rolls back (403 thrown). `@Version` on `Booking` handles concurrent mutations to the same booking record; it does not help with the credit-count race.

### Platform Config Keys Used

| Key | ID | Value | Source |
|-----|-----|-------|--------|
| `booking.request_expiry_hours` | 38 | `48` | Added in V31 (this story) |
| `platform.reminder_interval_primary_hours` | 35 | `24` | Already in V20 |
| `platform.reminder_interval_secondary_hours` | 36 | `2` | Already in V20 |

### Previous Story Learnings (from Story 3.2)

- `HttpTestClient.makeHttpRequest()` is the actual IT client method name — not `client.post()` or `.exchange()`
- Use distinct `PARENT_ID` values per IT class — `9300000001L` (3.1) and `9400000001L` (3.2) taken; start at `9500000001L`
- Never DELETE from table with no WHERE in teardown
- Cross-module repo imports are permitted in the monolith
- `OperationNotAllowedException` → 403; `ResourceNotFoundException` → 404. Never use `ResponseStatusException`
- `@PreAuthorize` mandatory on every endpoint — no exceptions
- Vue Composition API `<script setup>` for all components; `async/await` for all async
- The IT class does NOT include `@Sql` annotation for test-specific SQL — data is inserted programmatically in `@BeforeEach`
- `@Version` field in JPA entity means `repository.save()` after any mutation is required for optimistic lock to update

### Project Structure Notes

**New backend files:**
- `src/main/resources/db/migration/V31__booking_requests.sql`
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingRequestedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingConfirmedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingDeclinedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingExpiredEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingReminderEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`
- `src/main/resources/templates/email/booking-requested.html`
- `src/main/resources/templates/email/booking-confirmed.html`
- `src/main/resources/templates/email/booking-declined.html`
- `src/main/resources/templates/email/booking-expired.html`
- `src/main/resources/templates/email/booking-reminder.html`

**Modified backend files:**
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` — add 4 new values

**New frontend files:**
- `src/frontend/src/components/booking/BookingStateChip.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`

**Modified frontend files:**
- `src/frontend/src/api/booking.api.js` — add 4 new API functions
- `src/frontend/src/stores/booking.store.js` — add booking request state + actions
- `src/frontend/src/i18n/en/index.js` — add `booking.requests.*` keys
- `src/frontend/src/router/routes.js` — add 2 new parent routes + 1 coach route
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` — update "Book a session" CTA

**New test files:**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`

**Modified backend files (in addition to above):**
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` — add `BOOKING_EXPIRED` alongside the other 4 new values

### References

- Epic source: `_bmad-output/planning-artifacts/skillars-epics.md` — Story 3.3 full text
- Architecture: `_bmad-output/planning-artifacts/architecture.md` — endpoint naming (plural convention), module map
- ConfigService: `platform.config.service.ConfigService` — `getLong(key)` method for reading config
- Existing payment stub: `platform.booking.service.StubPaymentGateway` + `platform.booking.contract.PaymentGateway`
- SessionPackService.hasCredits(): `platform.booking.service.SessionPackService:118` — public method, inject directly
- Email listener pattern: `platform.notification.infrastructure.listener.AccountChangeEmailListener`
- IT test pattern: `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackResourceIT.java`
- Coach profile by userId: `CoachProfileRepository.findByUserId(Long userId)` — line 16 of repository
- AvailabilityService.requireProfile() pattern: lines 188–190 of AvailabilityService

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- **UnnecessaryStubbingException** in `createBookingRequest_requestedStartTimeInPast` — removed unreachable `coachAvailabilityWindowRepository.findByCoachId` stub (service throws before that call when time is past); fixed by removing stub only.
- **Slot validation logic** — Dev Notes provided code that compared `Instant` against `LocalTime` directly (incorrect). Implemented correct timezone-aware approach: convert `requestedStartTime` to `ZonedDateTime` via `window.getCanonicalTimezone()`, extract `getDayOfWeek().getValue()` and `toLocalTime()` for comparison against window fields.
- **Email template directory** — Dev Notes specified `templates/email/` but actual project structure uses `mails/` with camelCase filenames (per `ThymeleafConfiguration.java`). Used correct `mails/bookingRequested.html` etc.
- **Prettier failures** — 5 frontend files required reformatting; fixed by running `npx prettier --write` on all modified frontend files before final compile check.

### Completion Notes List

- All 18 tasks implemented and verified with 0 compilation errors (`mvn compile -q` clean).
- 12 unit tests in `BookingServiceTest` all pass (`mvn test -Dtest=BookingServiceTest`).
- 11 integration tests in `BookingRequestResourceIT` cover all 8 ACs.
- TOCTOU credit race prevented via pessimistic write lock on `sessionPackPurchasedRepository.findActivePacksForDeduction()` acquired before `hasCredits()` check inside `createBookingRequest()`.
- `SessionPackService.hasCredits()` updated to subtract in-flight booking count (`countInFlightBookings`) — soft-reserves credits without physical deduction until COMPLETED (Story 3.6).
- `findConfirmedForUpcomingTransition` uses `<= :windowEnd` (not BETWEEN) to handle scheduler-downtime catch-up for bookings whose start time already passed.
- `BookingExpiredEvent` is a distinct type from `BookingDeclinedEvent` to route to the correct email template/wording.
- `GET /api/bookings/requests/coach` route declared before `/{id}/accept` and `/{id}/decline` to prevent Spring path-matching ambiguity.
- Frontend CTA in `CoachPublicProfilePage.vue` now routes to `BookingRequestPage` when parent has credits; otherwise to purchase page. Label dynamically changes between "Book a session" and "Buy sessions".
- All frontend files formatted with Prettier.

### File List

**New backend files:**
- `src/main/resources/db/migration/V31__booking_requests.sql`
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingRequestedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingConfirmedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingDeclinedEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingExpiredEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingReminderEvent.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`
- `src/main/resources/mails/bookingRequested.html`
- `src/main/resources/mails/bookingConfirmed.html`
- `src/main/resources/mails/bookingDeclined.html`
- `src/main/resources/mails/bookingExpired.html`
- `src/main/resources/mails/bookingReminder.html`

**Modified backend files:**
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java`
- `src/main/resources/i18n/messages_en.properties`

**New frontend files:**
- `src/frontend/src/components/booking/BookingStateChip.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`

**Modified frontend files:**
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`

**New test files:**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`

### Change Log

- 2026-06-15: Story 3.3 implemented — Booking Request & Approval Workflow. Created `booking.bookings` table (V31 migration), `Booking` entity + `BookingRepository`, `BookingService` with full create/accept/decline/list operations, `BookingExpiryScheduler` + `BookingReminderScheduler`, `BookingResource` REST API, 5 new domain events, `BookingEmailListener` with 5 email handlers, 5 Thymeleaf email templates, `BookingStateChip.vue` frontend component, 3 new frontend pages (`BookingRequestPage`, `ParentBookingsPage`, `CoachBookingRequestsPage`), and wired "Book a session" CTA in `CoachPublicProfilePage.vue`. Updated `SessionPackService.hasCredits()` with in-flight booking soft-reservation. 12 unit tests + 11 integration tests all passing.
