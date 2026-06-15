# Story skillars-3.5: Scheduling Views & Timezone Management

Status: done

## Story

As a coach or parent,
I want to see my schedule displayed in the correct timezone with projected revenue and session pack status always visible,
So that I can manage my time and finances without mental timezone arithmetic.

## Acceptance Criteria

1. **AC 1: Coach Command Center Layout** — Given a coach navigates to the Command Center, when the schedule view loads, then all sessions are rendered in the coach's `canonicalTimezone` by default. Layout is 3 columns on desktop: sidebar (active client roster + quick actions), schedule (week view with session blocks), revenue panel. On mobile, the three columns collapse to a single content stream with activity-first ordering (UX-DR5).

2. **AC 2: Revenue Panel** — Given the Command Center revenue panel renders, when sessions in `CONFIRMED` or `UPCOMING` status exist for the current week, then total projected gross revenue is displayed (sum of `CoachPricing.perSessionPrice × booking count`). The 8% platform commission is shown as a deduction line so the coach sees net projected payout. Commission rate is read from `ConfigService` key `platform.commission_rate` — never hardcoded.

3. **AC 3: Schedule Gap Visualization** — Given the Command Center schedule view renders, when the coach's week is displayed, then schedule gaps (available windows with no booking) are visually distinct from booked sessions and blocked times. Tapping a gap offers a one-tap "Share this slot" action that copies a booking link to the clipboard.

4. **AC 4: Parent Timezone Toggle** — Given a parent views their upcoming sessions list, when the list renders, then all session times display in the Pitch Timezone (the coach's `canonicalTimezone`) by default. A toggle "Show in my time" switches all displayed times to the browser's local timezone for that session. The toggle state is per-session and does not persist across page reloads.

5. **AC 5: Timezone Info Bar on Login** — Given a user logs in and their browser timezone differs from the Pitch Timezone of their most recent booking, when login completes and the home screen loads, then a non-blocking info bar appears: "Your browser timezone ([zone]) differs from the session timezone ([pitch zone]). Times are shown in session timezone." (UX-DR23). The bar auto-dismisses after 8 seconds and does not appear again in the same session after dismissal.

6. **AC 6: Player Portal Sessions** — Given a parent views the Player Portal sessions screen, when the upcoming sessions section renders, then they see only sessions for the currently active player profile (respecting `ParentChildSwitcher` selection). Session pack credits remaining for each coach are shown inline next to their sessions.

7. **AC 7: Mobile Start Session Button** — Given the Command Center is viewed on mobile, when the Start Session button for an `UPCOMING` session is rendered, then it is full-width, minimum 56px height, `--accent-primary` fill, and reachable without any navigation (UX-DR6). The button is thumb-reachable at the bottom of the screen on mobile viewports.

## Tasks / Subtasks

### Backend

- [x] Task 1: `BookingRepository` — 2 new JPQL queries (AC: 1, 2, 6)
  - [x] Add `findByCoachIdAndStatusInAndTimeBetween(UUID coachId, List<String> statuses, Instant weekStart, Instant weekEnd)` — returns `List<Booking>` ordered by `requestedStartTime ASC`
  - [x] Add `findByParentIdAndPlayerIdAndStatusIn(Long parentId, Long playerId, List<String> statuses)` — returns `List<Booking>` ordered by `requestedStartTime ASC`

- [x] Task 2: Contract records in `platform.booking.contract` (AC: 1, 2, 6)
  - [x] Create `ProjectedRevenueResult.java` record: `(BigDecimal grossRevenue, BigDecimal commissionDeduction, BigDecimal netRevenue)`
  - [x] Create `ScheduleBookingItem.java` record: `(UUID bookingId, String playerName, Instant requestedStartTime, Instant requestedEndTime, String status, String canonicalTimezone)`
  - [x] Create `CoachScheduleResponse.java` record: `(String weekStart, String coachTimezone, List<ScheduleBookingItem> bookings, List<AvailabilityWindowResponse> availabilityWindows, List<AvailabilityBlockResponse> availabilityBlocks, BigDecimal projectedGrossRevenue, BigDecimal commissionDeduction, BigDecimal projectedNetRevenue)`
  - [x] Create `ParentScheduleItem.java` record: `(UUID bookingId, UUID coachId, String coachDisplayName, Instant requestedStartTime, Instant requestedEndTime, String status, String canonicalTimezone, int effectiveCreditsRemaining)`
  - [x] Create `ParentScheduleResponse.java` record: `(Long playerId, List<ParentScheduleItem> sessions)`

- [x] Task 3: `ProjectedRevenueService` in `platform.booking.service` (AC: 2)
  - [x] Create `ProjectedRevenueService.java` as `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Inject: `BookingRepository bookingRepository`, `CoachPricingRepository coachPricingRepository`, `ConfigService configService`
  - [x] `@Transactional(readOnly = true) public ProjectedRevenueResult calculateWeeklyRevenue(UUID coachId, LocalDate weekStart)`:
    - Compute `Instant wkStart = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant()` and `Instant wkEnd = weekStart.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant()`
    - Load `findByCoachIdAndStatusInAndTimeBetween(coachId, List.of("CONFIRMED", "UPCOMING"), wkStart, wkEnd)`
    - `BigDecimal perSessionPrice = coachPricingRepository.findByCoachId(coachId).map(CoachPricing::getPerSessionPrice).orElse(BigDecimal.ZERO)`
    - `BigDecimal gross = perSessionPrice.multiply(BigDecimal.valueOf(bookings.size()))`
    - `BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission_rate"))`
    - `BigDecimal commission = gross.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP)`
    - Return `new ProjectedRevenueResult(gross, commission, gross.subtract(commission))`
  - [x] Handle `CoachPricingRepository` returning empty (coach not yet priced): log warning, return zeros

- [x] Task 4: `BookingService` — 2 new schedule query methods (AC: 1, 6)
  - [x] Add `@Transactional(readOnly = true) public List<ScheduleBookingItem> getCoachWeekSchedule(UUID coachId, LocalDate weekStart)`:
    - Compute `wkStart` / `wkEnd` (same pattern as `ProjectedRevenueService`)
    - Load all bookings (any status) for that week via `findByCoachIdAndStatusInAndTimeBetween` with statuses `List.of("CONFIRMED","UPCOMING","REQUESTED","IN_PROGRESS","COMPLETED_PENDING_CONFIRMATION")`
    - Map each `Booking` to `ScheduleBookingItem(b.getId(), resolvePlayerName(b.getPlayerId()), b.getRequestedStartTime(), b.getRequestedEndTime(), b.getStatus(), b.getCanonicalTimezone())`
    - Return list
  - [x] Add `@Transactional(readOnly = true) public ParentScheduleResponse getParentPlayerSchedule(Long parentId, Long playerId)`:
    - Verify player belongs to parent: `playerProfileRepository.findById(playerId)` → check `player.getParentId().equals(parentId)`, else throw `OperationNotAllowedException`
    - Load active bookings via `findByParentIdAndPlayerIdAndStatusIn(parentId, playerId, List.of("CONFIRMED","UPCOMING","REQUESTED","IN_PROGRESS"))`
    - For each booking: look up coachName via `coachProfileRepository.findById(b.getCoachId())`, compute `effectiveCredits` via `sessionPackService.getCreditsRemaining(playerId, b.getCoachId()) - bookingRepository.countInFlightBookings(playerId, b.getCoachId())`
    - Map to `ParentScheduleItem` and return `new ParentScheduleResponse(playerId, items)`

- [x] Task 5: `ScheduleResource` in `platform.booking.api` (AC: 1, 2, 3, 6)
  - [x] Create `ScheduleResource.java` as `@Observed(name = "booking.schedule") @RestController @RequestMapping("/api/bookings") @RequiredArgsConstructor`
  - [x] Inject: `BookingService bookingService`, `ProjectedRevenueService projectedRevenueService`, `AvailabilityService availabilityService`, `CoachProfileRepository coachProfileRepository`, `SecurityUtil securityUtil`
  - [x] `@GetMapping("/coaches/me/schedule") @PreAuthorize(SecurityConstants.HAS_COACH_ROLE) public ResponseEntity<CoachScheduleResponse> getCoachSchedule(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart)`:
    - Resolve `coachUserId` via `((Principal) securityUtil.getCurrentUser()).getBusinessId()`
    - Look up `CoachProfile coach` via `coachProfileRepository.findByUserId(coachUserId)`
    - Call `bookingService.getCoachWeekSchedule(coach.getId(), weekStart)`
    - Call `availabilityService.getAvailabilityCalendar(coach.getId(), weekStart)`
    - Call `projectedRevenueService.calculateWeeklyRevenue(coach.getId(), weekStart)`
    - Assemble and return `CoachScheduleResponse`
  - [x] `@GetMapping("/parents/me/schedule") @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE) public ResponseEntity<ParentScheduleResponse> getParentSchedule(@RequestParam Long playerId)`:
    - Resolve `parentUserId` from `securityUtil.getCurrentUser()`
    - Delegate to `bookingService.getParentPlayerSchedule(parentUserId, playerId)`
    - Return 200

- [x] Task 6: Unit test — `ProjectedRevenueServiceTest` (AC: 2)
  - [x] Create `src/test/java/com/softropic/skillars/platform/booking/service/ProjectedRevenueServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — no Spring context
  - [x] Test: no bookings this week → all revenue values zero
  - [x] Test: 3 CONFIRMED bookings, perSessionPrice=€50, commissionRate=0.08 → gross=150, commission=12.00, net=138.00
  - [x] Test: mixed CONFIRMED+UPCOMING bookings → only these 2 statuses are summed (verify REQUESTED are excluded)
  - [x] Test: coach has no pricing entry → gross=ZERO, commission=ZERO, net=ZERO (no exception)
  - [x] Mock `configService.getString("platform.commission_rate")` returning `"0.08"`

- [x] Task 7: Integration test — `ScheduleResourceIT` (AC: 1, 6)
  - [x] Create `src/test/java/com/softropic/skillars/platform/booking/api/ScheduleResourceIT.java`
  - [x] Follow `BookingRequestResourceIT` annotation pattern: `@SpringBootTest`, `@Testcontainers`, `@Import(TestConfig.class)`, `@ActiveProfiles({"dev","test"})`
  - [x] Test 1: Coach GET `/api/bookings/coaches/me/schedule?weekStart=2026-06-15` returns 200 with correct `CoachScheduleResponse` shape (non-null `weekStart`, `coachTimezone`, `bookings`, revenue fields)
  - [x] Test 2: Unauthenticated access returns 401
  - [x] Test 3: Parent attempting coach schedule returns 403
  - [x] Test 4: Parent GET `/api/bookings/parents/me/schedule?playerId={id}` with own player returns 200
  - [x] Test 5: Parent GET with another parent's player ID returns 403

### Frontend

- [x] Task 8: `booking.api.js` — add schedule endpoints (AC: 1, 6)
  - [x] Add `export const getCoachSchedule = (weekStart) => api.get('/api/bookings/coaches/me/schedule', { params: { weekStart } })`
  - [x] Add `export const getParentSchedule = (playerId) => api.get('/api/bookings/parents/me/schedule', { params: { playerId } })`

- [x] Task 9: `booking.store.js` — add schedule state and actions (AC: 1, 6)
  - [x] Import `getCoachSchedule`, `getParentSchedule` from `booking.api`
  - [x] Add state: `coachSchedule = ref(null)`, `coachScheduleLoading = ref(false)`, `coachScheduleError = ref(null)`, `parentSchedule = ref(null)`, `parentScheduleLoading = ref(false)`, `parentScheduleError = ref(null)`
  - [x] Add `async function loadCoachSchedule(weekStart)` — sets loading, calls `getCoachSchedule(weekStart)`, stores `res.data` in `coachSchedule`
  - [x] Add `async function loadParentSchedule(playerId)` — sets loading, calls `getParentSchedule(playerId)`, stores `res.data` in `parentSchedule`
  - [x] Expose all new state and actions from `return { ... }`

- [x] Task 10: `useTimezone.js` composable — new file in `src/frontend/src/composables/` (AC: 4, 5)
  - [x] `export function useTimezone(canonicalTimezone)` — accepts a String (not a ref)
  - [x] `const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone`
  - [x] `function formatInPitchTimezone(isoString)` — uses `Intl.DateTimeFormat` with `timeZone: canonicalTimezone`, `dateStyle: 'medium'`, `timeStyle: 'short'`
  - [x] `function formatInBrowserTimezone(isoString)` — uses `Intl.DateTimeFormat` with `timeZone: browserTimezone`, `dateStyle: 'medium'`, `timeStyle: 'short'`
  - [x] `const timezonesDiffer = canonicalTimezone != null && canonicalTimezone !== browserTimezone`
  - [x] Return `{ formatInPitchTimezone, formatInBrowserTimezone, browserTimezone, timezonesDiffer }`

- [x] Task 11: `auth.store.js` — add timezone dismissal state (AC: 5)
  - [x] Add `const timezoneNoticeDismissed = ref(false)` inside the store
  - [x] Add `const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone` (module-level constant, outside store, computed once)
  - [x] Add `function dismissTimezoneNotice() { timezoneNoticeDismissed.value = true }`
  - [x] Expose `timezoneNoticeDismissed`, `browserTimezone`, `dismissTimezoneNotice` from `return { ... }`

- [x] Task 12: `TimezoneNotice.vue` component — new in `src/frontend/src/components/booking/` (AC: 5)
  - [x] Props: `pitchTimezone: { type: String, required: true }`
  - [x] Import `useAuthStore` and check `pitchTimezone !== authStore.browserTimezone && !authStore.timezoneNoticeDismissed`
  - [x] Show notice: `t('booking.timezone.noticeDiffers', { browser: authStore.browserTimezone, pitch: pitchTimezone })`
  - [x] On mount: `setTimeout(() => authStore.dismissTimezoneNotice(), 8000)` — auto-dismiss
  - [x] Manual "X" dismiss button also calls `authStore.dismissTimezoneNotice()`
  - [x] Position: non-blocking info bar (not a modal); use `position: fixed; top: 0; width: 100%` or append to page top
  - [x] Style: use `--color-info` or `--accent-primary` background, white text, subtle close button

- [x] Task 13: `CoachCommandCenterPage.vue` — new in `src/frontend/src/pages/coach/` (AC: 1, 2, 3, 5, 7)
  - [x] `<script setup>`: import `useBookingStore`, `useAuthStore`, `useI18n`
  - [x] On mount: call `bookingStore.loadCoachSchedule(currentMonday())` where `currentMonday()` returns ISO date string `YYYY-MM-DD`
  - [x] Week navigation: `selectedWeek = ref(currentMonday())`; `prevWeek()` / `nextWeek()` subtract/add 7 days, re-call `loadCoachSchedule(selectedWeek.value)`
  - [x] Show `<TimezoneNotice :pitch-timezone="bookingStore.coachSchedule?.coachTimezone" />` at top if schedule is loaded
  - [x] Desktop layout: CSS Grid `grid-template-columns: 260px 1fr 280px`; 3 panes — sidebar, schedule, revenue
  - [x] Sidebar: "Active Clients" list = unique player names from `bookingStore.coachSchedule?.bookings`; if empty: `t('coach.commandCenterNoClients')`
  - [x] Schedule pane: render 7-day week grid using CSS Grid (`grid-template-columns: repeat(7, 1fr)`)
  - [x] Revenue pane: show `projectedGrossRevenue`, `commissionDeduction`, `projectedNetRevenue` with `t('booking.revenue.*')` labels; currency hardcoded to `€` for now
  - [x] "Start Session" button for UPCOMING bookings: `full-width`, `min-height: 56px`, `background: var(--accent-primary)`, label `t('booking.schedule.startSession')`
  - [x] "Share this slot" button: calls `navigator.clipboard.writeText(generateSlotLink())` with `$q.notify` on success
  - [x] `currentMonday()` helper: computes current week's Monday date as `YYYY-MM-DD` string
  - [x] Mobile: `@media (max-width: 768px)` — single column, revenue panel first, then schedule, then sidebar

- [x] Task 14: Update `routes.js` — command center and player portal (AC: 1, 6)
  - [x] Change `coach/command-center` route component from `CoachCommandCenterPlaceholderPage.vue` to `import('pages/coach/CoachCommandCenterPage.vue')`
  - [x] Add `meta: { requiresAuth: true, requiresCoach: true }` to the command center route
  - [x] Add new route `path: 'parent/players/:playerId/sessions'` → `import('pages/parent/ParentPlayerPortalPage.vue')` with `meta: { requiresAuth: true, role: 'PARENT' }`

- [x] Task 15: `ParentPlayerPortalPage.vue` — new in `src/frontend/src/pages/parent/` (AC: 6)
  - [x] Import `useBookingStore`, `useI18n`
  - [x] On mount: read `playerId` from `route.params.playerId`; call `bookingStore.loadParentSchedule(playerId)` and `loadPlayerPacks(playerId)`
  - [x] Show loading spinner while `bookingStore.parentScheduleLoading`
  - [x] Empty state: `t('booking.timezone.parentScheduleEmpty')` if `parentSchedule.sessions` is empty
  - [x] Session list: for each `ParentScheduleItem`: coach name, time in canonicalTimezone, `BookingStateChip`, `SessionPackTracker`
  - [x] Include `TimezoneNotice` if first booking's timezone differs from browser timezone

- [x] Task 16: `ParentBookingsPage.vue` — modify for timezone toggle and pack tracker (AC: 4, 5)
  - [x] Add `const showInMyTime = ref({})` — map of `bookingId → Boolean`
  - [x] Add `function toggleTimezone(bookingId)` — `showInMyTime.value[bookingId] = !showInMyTime.value[bookingId]`
  - [x] Add `const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone`
  - [x] Refactor `formatDateTime(isoString, timezone)` to use `Intl.DateTimeFormat` with `dateStyle: 'medium', timeStyle: 'short'`
  - [x] In template: timezone-aware datetime display with toggle button
  - [x] Add `TimezoneNotice` at page top using first booking's timezone

- [x] Task 17: `i18n/en/index.js` — add schedule and timezone keys (AC: 1, 2, 4, 5, 6)
  - [x] Under `booking`, add `schedule` namespace:
    ```
    schedule: {
      weekOf: 'Week of {date}',
      prevWeek: 'Previous week',
      nextWeek: 'Next week',
      noBookings: 'No sessions scheduled this week',
      startSession: 'Start Session',
      shareSlot: 'Share this slot',
      slotCopied: 'Booking link copied to clipboard',
    }
    ```
  - [x] Under `booking`, add `revenue` namespace:
    ```
    revenue: {
      projectedTitle: 'Projected Revenue',
      gross: 'Gross',
      commission: 'Platform commission ({rate}%)',
      net: 'Your payout',
    }
    ```
  - [x] Under `booking`, add `timezone` namespace:
    ```
    timezone: {
      noticeDiffers: 'Your browser timezone ({browser}) differs from the session timezone ({pitch}). Times are shown in session timezone.',
      showInMyTime: 'Show in my time',
      showInSessionTime: 'Show in session time',
      parentScheduleTitle: 'Upcoming sessions',
      parentScheduleEmpty: 'No upcoming sessions for this player',
    }
    ```
  - [x] Under `coach`, add:
    ```
    commandCenterSidebar: 'Active Clients',
    commandCenterNoClients: 'No active clients this week',
    commandCenterSchedule: 'Schedule',
    ```

## Dev Notes

### ⚠️ CRITICAL: No Flyway Migration Needed

Story 3.5 adds NO new tables or columns. All required schema is already in place:
- `platform.commission_rate` config key exists since V20 (`"0.08"`)
- `bookings` table has all required columns
- `coach_pricing` table has `per_session_price`

Do **not** create a V33 migration for this story.

### ⚠️ CRITICAL: API Path Convention

All new endpoints follow the established `/api/bookings/...` plural prefix — **not** `/api/booking/...`. The epics dev notes say `/api/booking/coaches/me/schedule` but this is a documentation error matching the same mistake caught in Story 3.4. Correct paths:
- `GET /api/bookings/coaches/me/schedule?weekStart={date}`
- `GET /api/bookings/parents/me/schedule?playerId={id}`

### ⚠️ CRITICAL: `Booking.parentId` and `Booking.playerId` are `Long` (TSID), NOT UUID

From Story 3.4 learnings: `parentId` and `playerId` on the `Booking` entity are `Long` (TSID). Only `coachId` is UUID. Verify this before writing any JPQL query parameter types.

### ⚠️ CRITICAL: Server Always Returns UTC; Frontend Formats

The server must return all timestamps as UTC `Instant` (ISO 8601 format e.g. `2026-06-15T10:00:00Z`). The timezone display logic lives **entirely on the frontend** via `Intl.DateTimeFormat`. Do NOT apply timezone conversions on the backend — just pass UTC instants.

### ⚠️ CRITICAL: `CoachCommandCenterPage.vue` Replaces Placeholder

The route `coach/command-center` currently points to `CoachCommandCenterPlaceholderPage.vue`. The new `CoachCommandCenterPage.vue` replaces it. Do NOT delete `CoachCommandCenterPlaceholderPage.vue` in case it is referenced elsewhere — just update the route import. The placeholder can be cleaned up in a future pass.

### Cross-Module Dependency: `CoachPricingRepository`

`ProjectedRevenueService` imports `CoachPricingRepository` from `platform.marketplace.repo`. This cross-module dependency is acceptable because both modules live in the same monolith and the `booking` module already imports from `marketplace` (e.g., `CoachProfileRepository` is used in `BookingService`). Do NOT move `CoachPricingRepository` to `infrastructure` — it models domain state.

### `ScheduleResource` — `resolveCurrentUserId()` Helper Pattern

Follow `BookingResource`'s pattern for getting the current user ID:
```java
private Long resolveCurrentUserId() {
    String businessId = ((Principal) securityUtil.getCurrentUser()).getBusinessId();
    if (businessId == null || businessId.isBlank()) {
        throw new InsufficientAuthenticationException("User ID not found in security context");
    }
    return Long.parseLong(businessId);
}
```
Import `com.softropic.skillars.platform.security.contract.Principal` (not `java.security.Principal`).

### `ProjectedRevenueService` — Commission Rate Parsing

`configService.getString("platform.commission_rate")` returns `"0.08"`. Parse with `new BigDecimal(configService.getString("platform.commission_rate"))` — NOT `Double.parseDouble` (avoids floating-point precision errors). All monetary calculations use `BigDecimal` with `RoundingMode.HALF_UP`.

### `BookingService.getCoachWeekSchedule()` — Week Boundary

Use `ZoneOffset.UTC` for week boundary computation:
```java
Instant wkStart = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
Instant wkEnd   = weekStart.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
```
The `BETWEEN :wkStart AND :wkEnd` (inclusive start, exclusive end) ensures only the 7 days of the requested week are returned. Use `>=` and `<` in JPQL to match this exactly.

### `CoachScheduleResponse` — Availability Data

The `CoachScheduleResponse` includes `availabilityWindows` and `availabilityBlocks` from `AvailabilityService.getAvailabilityCalendar(coachId, weekStart)`. This bundles the 3-API calls (schedule, availability, revenue) into a single client request and avoids exposing the coachId to the frontend. The `ScheduleResource` assembles the compound response server-side.

The existing `CoachAvailabilityResponse` has 3 fields: `windows`, `blocks`, `computedSlots`. The schedule response only needs `windows` and `blocks` (not computed slots — the frontend renders gaps itself by comparing windows against bookings).

### `ParentScheduleResponse` — `effectiveCreditsRemaining` Computation

For each booking in `getParentPlayerSchedule`:
```java
int credits = (int)(sessionPackService.getCreditsRemaining(b.getPlayerId(), b.getCoachId())
    - bookingRepository.countInFlightBookings(b.getPlayerId(), b.getCoachId()));
int effectiveCredits = Math.max(0, credits);
```
`countInFlightBookings` counts REQUESTED, ACCEPTED, CONFIRMED, UPCOMING status rows. This is the same computation used in `BookingService.getParentBookings()` — reuse the exact same logic.

### Frontend — `SessionPackTracker` on `ParentBookingsPage.vue`

`SessionPackTracker.vue` takes `creditsRemaining` and `sessionCount`. In `ParentBookingsPage.vue`, the `BookingResponse.effectiveCreditsRemaining` is available but `sessionCount` is not (it requires fetching the pack). Pass `sessionCount=0` as a stub:
- When `sessionCount=0`, the tracker always shows "Exhausted" state (progress bar at 0%)
- This is visually wrong but acceptable for this story — Story 3.9 (Session Pack Expiry & Pause Management) will properly wire the dashboard with full pack data
- Add an inline comment: `/* sessionCount not available in booking response; tracker shows credit count only */`

**Alternative if unacceptable:** Don't show `SessionPackTracker` in `ParentBookingsPage.vue`, only show the credits count text that already exists (`creditsRemaining: '{count} credits remaining with this coach'`). Use the proper tracker only in `ParentPlayerPortalPage.vue` where the full pack data can be fetched. This avoids the misleading 0% progress bar.

**Recommended:** Use the text-only credits in `ParentBookingsPage.vue` (no tracker component) and add the `SessionPackTracker` with proper `sessionCount` in `ParentPlayerPortalPage.vue` after calling `bookingStore.loadPlayerPacks(playerId)` on mount to load the full pack data.

### Frontend — `useTimezone.js` Not a Vue Composable Pattern

`useTimezone` is a pure utility function, not a Vue composable (it doesn't call `ref`, `computed`, or lifecycle hooks). Therefore:
- It CAN be called inside `v-for` template helpers (use it as a plain function)
- It does NOT need to follow `use*` composable rules (no `onMounted`, etc.)
- It is placed in `src/composables/` for organization, but is stateless

In `ParentBookingsPage.vue`, call it directly in the template helper:
```javascript
function formatDateTime(isoString, timezone) {
  return new Intl.DateTimeFormat('en', { timeZone: timezone, dateStyle: 'medium', timeStyle: 'short' })
    .format(new Date(isoString))
}
const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
```

### Frontend — `CoachCommandCenterPage.vue` Share Slot Logic

The "Share this slot" generates a URL pointing to the coach's public profile with a pre-selected time. Since the coach's `coachId` (UUID) is available in the schedule response (bookings carry it, or the response could carry it separately), use:
```javascript
function generateSlotLink(slotStartTime) {
  const base = window.location.origin
  // coachId not in CoachScheduleResponse — use the window that loaded this page
  // Simpler: link to the marketplace page (no coachId needed for MVP)
  return `${base}/marketplace`
}
```
For MVP (this story): just copy the marketplace URL + a notification. The actual deep-link slot sharing can be done when the booking-request page supports pre-selected times. Add a `// TODO(3.x): deep-link to coach profile with pre-selected slot` comment.

### Frontend — `CoachCommandCenterPage.vue` Week Grid Layout

The schedule week view uses CSS Grid. Server returns UTC instants; render them in `coachSchedule.coachTimezone` using:
```javascript
function slotLabel(instant, timezone) {
  return new Intl.DateTimeFormat('en', {
    timeZone: timezone,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(instant))
}
```
Group bookings by day-of-week in `coachTimezone` to place them in the correct column. Use:
```javascript
function getDayIndex(instant, timezone) {
  const parts = new Intl.DateTimeFormat('en', { timeZone: timezone, weekday: 'long' }).formatToParts(new Date(instant))
  const day = parts.find(p => p.type === 'weekday').value
  return ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'].indexOf(day)
}
```

### Frontend — `TimezoneNotice.vue` Auto-Dismiss

```javascript
onMounted(() => {
  const timer = setTimeout(() => authStore.dismissTimezoneNotice(), 8000)
  onUnmounted(() => clearTimeout(timer))
})
```
The `authStore.timezoneNoticeDismissed` is a `ref` — the notice `v-if` reacts to it automatically.

### Package and File Location Summary

| File | Package / Path |
|------|----------------|
| `ProjectedRevenueResult.java` | `platform.booking.contract` |
| `ScheduleBookingItem.java` | `platform.booking.contract` |
| `CoachScheduleResponse.java` | `platform.booking.contract` |
| `ParentScheduleItem.java` | `platform.booking.contract` |
| `ParentScheduleResponse.java` | `platform.booking.contract` |
| `ProjectedRevenueService.java` | `platform.booking.service` |
| `ScheduleResource.java` | `platform.booking.api` |
| `ProjectedRevenueServiceTest.java` | `src/test/.../platform/booking/service/` |
| `ScheduleResourceIT.java` | `src/test/.../platform/booking/api/` |
| `useTimezone.js` | `src/frontend/src/composables/` |
| `TimezoneNotice.vue` | `src/frontend/src/components/booking/` |
| `CoachCommandCenterPage.vue` | `src/frontend/src/pages/coach/` |
| `ParentPlayerPortalPage.vue` | `src/frontend/src/pages/parent/` |

### Files Modified (Not New)

| File | Change |
|------|--------|
| `BookingRepository.java` | Add 2 new JPQL queries |
| `BookingService.java` | Add `getCoachWeekSchedule()` and `getParentPlayerSchedule()` methods |
| `booking.api.js` | Add `getCoachSchedule`, `getParentSchedule` |
| `booking.store.js` | Add `coachSchedule`, `parentSchedule` state + load actions |
| `auth.store.js` | Add `timezoneNoticeDismissed`, `browserTimezone`, `dismissTimezoneNotice()` |
| `ParentBookingsPage.vue` | Add per-booking timezone toggle + credit count + `TimezoneNotice` |
| `routes.js` | Update `coach/command-center` import; add `parent/players/:playerId/sessions` |
| `i18n/en/index.js` | Add `booking.schedule`, `booking.revenue`, `booking.timezone`, `coach.*` keys |

### Previous Story Learnings from Stories 3.3 and 3.4

- `parentId` and `playerId` on `Booking` are **Long** (TSID), not UUID — only `coachId` is UUID
- Module URL prefix is **plural**: `/api/bookings/...` (not `/api/booking/...`)
- `@TransactionalEventListener(AFTER_COMMIT)` in `platform.notification` for all notifications
- `resolveEmail(Long userId)` is a private helper in `BookingService` — reuse pattern
- `BookingRepository.countInFlightBookings()` returns `long` (not `int`) — JPQL COUNT always returns Long
- `BookingStateChip` fallback for unknown status shows raw string + `chip--neutral` — intentional
- `SecurityUtil.getCurrentUser()` returns `User` interface — cast to `Principal` via `((Principal) securityUtil.getCurrentUser()).getBusinessId()` and `Long.parseLong()`
- SSE endpoint path was wrong in epics (same pattern likely applies to schedule path — use `/api/bookings/...`)

### Project Structure Notes

- `ProjectedRevenueService` belongs in `platform.booking.service` — it is domain logic (reads booking state to compute financial projections) and references `platform.marketplace.repo.CoachPricingRepository`
- `ScheduleResource` belongs in `platform.booking.api` — it is a REST controller for the booking bounded context
- Do NOT put `ProjectedRevenueService` in `infrastructure.*` — it reads domain state (`Booking` entity) and applies business rules (commission rate from config)
- The `useTimezone.js` composable is pure JS (no Vue reactivity) — it's a formatting utility, not a Vue composable in the strict sense. Keep it in `composables/` for discoverability but do not apply `use*` lifecycle constraints to it.

### References

- Previous story: `_bmad-output/implementation-artifacts/skillars-3-4-booking-state-machine-sse.md`
- `BookingService.java`: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `AvailabilityService.java`: `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `ConfigService.java`: `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
- `CoachPricingRepository.java`: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachPricingRepository.java`
- `SessionPackTracker.vue`: `src/frontend/src/components/booking/SessionPackTracker.vue`
- `booking.store.js`: `src/frontend/src/stores/booking.store.js`
- `auth.store.js`: `src/frontend/src/stores/auth.store.js`
- `playerStore.js` (has `activePlayerId`, `activePlayer`): `src/frontend/src/stores/playerStore.js`
- `ParentChildSwitcher.vue`: `src/frontend/src/components/ParentChildSwitcher.vue`
- Platform config key `platform.commission_rate = 0.08`: V20 migration
- Epic 3.5 source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 1133–1180
- Project context: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed 3 ESLint lint errors in frontend: `props` unused in `TimezoneNotice.vue`, `tz` unused in `CoachCommandCenterPage.vue`, `slot` param unused in `shareSlot`. All resolved.
- Fixed Java compilation error in `ScheduleResourceIT`: `Map<?,?>` wildcard prevents `containsKey(String)` — cast to `Map<String, Object>` with `@SuppressWarnings("unchecked")`.
- Dev Notes recommended not passing `sessionCount` to `SessionPackTracker` in `ParentBookingsPage.vue` (text-only credits is cleaner). Followed recommendation and used text-only credits display on that page instead.

### Completion Notes List

- **Task 1**: Added 2 JPQL queries to `BookingRepository` using `>=` / `<` boundary (not BETWEEN) as specified in Dev Notes.
- **Task 2**: Created 5 contract records in `platform.booking.contract`.
- **Task 3**: `ProjectedRevenueService` uses `BigDecimal` throughout with `RoundingMode.HALF_UP`; logs warning on missing pricing, returns zeros.
- **Task 4**: Added `getCoachWeekSchedule` and `getParentPlayerSchedule` to `BookingService`; reused existing `resolvePlayerName`, `coachProfileRepository`, `sessionPackService` patterns already in the class.
- **Task 5**: `ScheduleResource` assembles all 3 data sources (schedule, availability, revenue) server-side into single `CoachScheduleResponse`. Follows `BookingResource` pattern for user ID resolution.
- **Task 6**: 4 unit tests, all passing. Third test verifies REQUESTED bookings are excluded by checking exact statuses list passed to mock.
- **Task 7**: 5 integration tests covering 200/401/403 cases. Uses same `@BeforeEach`/`@AfterEach` teardown pattern as `BookingRequestResourceIT`.
- **Task 8-9**: `booking.api.js` and `booking.store.js` extended cleanly; `currentMonday()` already existed in the store so not duplicated.
- **Task 10**: `useTimezone.js` is a pure utility, not a Vue composable; placed in `composables/` for organization.
- **Task 11**: `browserTimezone` computed once at module level (outside store) so it's stable.
- **Task 12**: `TimezoneNotice.vue` uses `onMounted`/`onUnmounted` pair to clear the auto-dismiss timer safely. `defineProps` not captured to avoid `no-unused-vars` lint error.
- **Task 13**: `CoachCommandCenterPage.vue` — full 3-column desktop layout with mobile reorder via CSS `order`. `shareSlot` links to `/marketplace` for MVP per Dev Notes.
- **Task 14**: Route updated; `requiresCoach: true` added; `parent/players/:playerId/sessions` added.
- **Task 15**: `ParentPlayerPortalPage.vue` loads both schedule and packs on mount; `sessionCountFor()` looks up active packs from store.
- **Task 16**: `ParentBookingsPage.vue` uses text-only credits (no `SessionPackTracker`) per Dev Notes recommendation. Timezone toggle is per-booking via `showInMyTime` ref map.
- **Task 17**: All i18n keys added under `booking.schedule`, `booking.revenue`, `booking.timezone`, and `coach.*`.

### File List

**New backend files:**
- `src/main/java/com/softropic/skillars/platform/booking/contract/ProjectedRevenueResult.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/ScheduleBookingItem.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachScheduleResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/ParentScheduleItem.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/ParentScheduleResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/ProjectedRevenueService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/ScheduleResource.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/ProjectedRevenueServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/ScheduleResourceIT.java`

**Modified backend files:**
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`

**New frontend files:**
- `src/frontend/src/composables/useTimezone.js`
- `src/frontend/src/components/booking/TimezoneNotice.vue`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- `src/frontend/src/pages/parent/ParentPlayerPortalPage.vue`

**Modified frontend files:**
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/stores/auth.store.js`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/i18n/en/index.js`

### Review Findings

_(Code review — 2026-06-15 | layers: blind-hunter, acceptance-auditor | edge-case-hunter: unavailable)_

**Decision-needed:** _(all resolved — converted to patches)_
- [x] [Review][Decision] D1: IDOR → **resolved: return 404** (enumeration-resistant) — `BookingService.getParentPlayerSchedule()` will return 404 instead of 403 when parent does not own the requested `playerId`. ✅ Patch applied.
- [x] [Review][Decision] D2: `activeClients` dedup → **resolved: add `playerId` to `ScheduleBookingItem`** — Backend contract change required to enable ID-based deduplication in `CoachCommandCenterPage.vue`. ✅ Patch applied.
- [x] [Review][Decision] D3: AC 5 `TimezoneNotice` → **resolved: add notice to home screen post-login** — Notice to also appear on home screen after login when browser timezone differs from most recent booking's pitch timezone. ✅ Patch applied.

**Patches:** _(all applied 2026-06-15)_
- [x] [Review][Patch] P1: Commission rate config parsing guarded with null/blank check + try-catch → zero fallback [`ProjectedRevenueService.java`] ✅
- [x] [Review][Patch] P2: `Long.parseLong(businessId)` wrapped in try-catch → `InsufficientAuthenticationException` on malformed ID [`ScheduleResource.java`] ✅
- [x] [Review][Patch] P3: Over-booking warning log added when `credits < 0` before clamping to 0 [`BookingService.java`] ✅
- [x] [Review][Patch] P4: `shareSlot()` catch block now uses `slotCopyFailed` i18n key + `type: 'negative'`; key added to i18n [`CoachCommandCenterPage.vue`, `i18n/en/index.js`] ✅
- [x] [Review][Patch] P5: `ParentPlayerPortalPage` now watches `playerStore.activePlayerId` and reloads schedule on switcher change [`ParentPlayerPortalPage.vue`] ✅
- [x] [Review][Patch] P6: "Start Session" button changed to `position: fixed; bottom: 0` on mobile for guaranteed thumb-reachability (UX-DR6) [`CoachCommandCenterPage.vue`] ✅
- [x] [Review][Patch] P7: `loadCoachSchedule` now sets `coachSchedule.value = null` before setting loading flag to prevent stale week display [`booking.store.js`] ✅
- [x] [Review][Patch] D1→Patch: Unauthorized player schedule access now throws `ResourceNotFoundException` (→ HTTP 404) instead of 403 to prevent player ID enumeration [`BookingService.java`] ✅
- [x] [Review][Patch] D2→Patch: `playerId: Long` added to `ScheduleBookingItem` contract; `activeClients` in sidebar now deduplicates by player ID [`ScheduleBookingItem.java`, `BookingService.java`, `CoachCommandCenterPage.vue`] ✅
- [x] [Review][Patch] D3→Patch: `TimezoneNotice` added to `DashboardPage.vue`; loads parent bookings on mount and shows notice if first booking's timezone differs from browser timezone (AC 5) [`DashboardPage.vue`] ✅

**Deferred:**
- [x] [Review][Defer] W1: Revenue gross ignores variable session pricing (e.g. pack discounts, multi-session rates) [`ProjectedRevenueService.java`] — deferred, spec-intentional: AC 2 explicitly defines gross as `perSessionPrice × booking count`; variable pricing is out of scope for this story.
- [x] [Review][Defer] W2: N+1 DB queries per booking in `getParentPlayerSchedule` (coachProfile + credits + in-flight per booking) [`BookingService.java`] — deferred, pre-existing pattern in codebase; not introduced by this story.

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-06-15 | 1.0 | Story created with full implementation context | claude-sonnet-4-6 |
| 2026-06-15 | 1.1 | Full implementation: backend schedule API, revenue service, frontend command center, timezone management | claude-sonnet-4-6 |
| 2026-06-15 | 1.2 | Code review findings added (3 decision-needed, 7 patch, 2 defer) | claude-sonnet-4-6 |
| 2026-06-15 | 1.3 | All review patches applied; story marked done | claude-sonnet-4-6 |
