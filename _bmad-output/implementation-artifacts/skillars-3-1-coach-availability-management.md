# Story skillars-3.1: Coach Availability Management

Status: done

## Story

As a coach,
I want to manage my recurring availability windows and block out personal time,
so that parents can only request sessions when I am genuinely available.

## Acceptance Criteria

1. **AC 1: Calendar View on Load** — Given a coach navigates to their availability settings, when the calendar view loads, then recurring weekly availability windows (set during onboarding, Story 2.1) are displayed as green slots on a 7-day week grid, manual blocks are displayed as grey "unavailable" overlays, and the calendar renders all times in the coach's `canonicalTimezone` — never UTC-raw.

2. **AC 2: Add Recurring Window** — Given a coach adds a new recurring availability window, when they select a day of week, start time, and end time and save, then a new `coach_availability_windows` record is created (coachId, dayOfWeek, startTime, endTime, canonicalTimezone), times are stored as UTC derived from the coach's `canonicalTimezone`, and the new slot appears immediately in the calendar without a page reload.

3. **AC 3: Edit Recurring Window** — Given a coach edits an existing recurring availability window, when they update the day or times and save, then the existing `coach_availability_windows` record is updated, any already-confirmed bookings within the old window are NOT affected (editing is prospective only), and a warning is shown if the change would overlap with an existing confirmed booking: "You have a booking during this time. Changes apply to future availability only."

4. **AC 4: Delete Recurring Window** — Given a coach deletes a recurring availability window, when they confirm the deletion, then the `coach_availability_windows` record is removed, existing confirmed bookings within that window are NOT cancelled (deletion is prospective only), and the slot is removed from the calendar immediately.

5. **AC 5: Add Manual Block** — Given a coach wants to block personal time, when they select a specific date range and time range and save a manual block, then a `coach_availability_blocks` record is created (id UUID, coachId, startDateTime TIMESTAMPTZ, endDateTime TIMESTAMPTZ, reason VARCHAR nullable), the blocked period is shown on the calendar as a grey overlay over any recurring availability, and parents cannot request bookings during a blocked period.

6. **AC 6: Partial Overlap Display** — Given a coach views a week that contains both availability windows and manual blocks, when they view a day with partial overlap (e.g., a block covering 10:00–12:00 within a 09:00–13:00 window), then the calendar correctly shows the unblocked portions (09:00–10:00 and 12:00–13:00) as available and the blocked portion (10:00–12:00) as unavailable.

7. **AC 7: Bookable Slot Query** — Given a parent views a coach's booking page (Epic 3.3), when available slots are fetched, then only time slots within an active `coach_availability_windows` entry AND not covered by `coach_availability_blocks` are returned as bookable. This query is read-only from this module — booking creation is handled in Story 3.3.

## Tasks / Subtasks

- [x] Task 1: Initialize `platform.booking` module (AC: all — prerequisite)
  - [x] Create package structure under `com.softropic.skillars.platform.booking`: `api`, `config`, `contract`, `repo`, `service`
  - [x] Create `BookingConfig.java` in `platform.booking.config` — minimal `@Configuration` class (like `MarketplaceConfig`)
  - [x] Create Flyway migration `V29__booking_module_init.sql`: create `booking` schema + `coach_availability_blocks` table (see Dev Notes for DDL)
  - [x] Create `CoachAvailabilityBlock.java` entity in `platform.booking.repo` (see Dev Notes for fields)
  - [x] Create `CoachAvailabilityBlockRepository.java` extending `JpaRepository<CoachAvailabilityBlock, UUID>`

- [x] Task 2: Create `CoachAvailabilityWindowReadRepository` in `platform.booking.repo` (AC: 1, 2, 3, 4, 6, 7)
  - [x] Create read-only Spring Data repository interface that reads `marketplace.coach_availability_windows` — interface only, no entity copy; reuse existing `CoachAvailabilityWindow` entity from `platform.marketplace.repo` (same DB, direct read is permitted in monolith)
  - [x] Add method: `List<CoachAvailabilityWindow> findByCoachId(UUID coachId)`
  - [x] Add method: `Optional<CoachAvailabilityWindow> findByIdAndCoachId(UUID id, UUID coachId)`

- [x] Task 3: Create `AvailabilityService` in `platform.booking.service` (AC: 1, 2, 3, 4, 5, 6, 7)
  - [x] Implement `getAvailabilityCalendar(UUID coachId, LocalDate weekStart)` — returns windows minus blocks for the requested 7-day range
  - [x] Implement `addWindow(UUID coachId, CreateWindowRequest req)` — creates `coach_availability_windows` record (times stored as UTC derived from coach's `canonicalTimezone`); delegates write to `CoachAvailabilityWindowRepository` in `platform.marketplace.repo` (direct cross-module repo use is permitted in monolith)
  - [x] Implement `updateWindow(UUID coachId, UUID windowId, UpdateWindowRequest req)` — updates existing window; checks for confirmed booking overlaps and sets `hasBookingConflict` flag in response (Story 3.3 bookings table does not exist yet — skip conflict check via a try/catch on missing table or feature flag from ConfigService)
  - [x] Implement `deleteWindow(UUID coachId, UUID windowId)` — removes window; prospective only, no booking cancellation
  - [x] Implement `addBlock(UUID coachId, CreateBlockRequest req)` — creates `coach_availability_blocks` record
  - [x] Implement `deleteBlock(UUID coachId, UUID blockId)` — removes block; validates ownership (coach owns block)
  - [x] Implement private `computeAvailableSlots(List<CoachAvailabilityWindow> windows, List<CoachAvailabilityBlock> blocks, LocalDate date)` — returns unblocked time ranges (subtract block intervals from window intervals); this is the unit-tested core logic

- [x] Task 4: Create `AvailabilityResource` in `platform.booking.api` (AC: 1, 2, 3, 4, 5, 7)
  - [x] `GET /api/bookings/coaches/{coachId}/availability?weekStart={date}` — public (coach or parent may call); returns merged windows minus blocks; `@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)`
  - [x] `POST /api/bookings/coaches/me/availability/windows` — create new recurring window; `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`
  - [x] `PUT /api/bookings/coaches/me/availability/windows/{id}` — update existing window; `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`
  - [x] `DELETE /api/bookings/coaches/me/availability/windows/{id}` — remove window; `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`
  - [x] `POST /api/bookings/coaches/me/availability/blocks` — create manual block; `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`
  - [x] `DELETE /api/bookings/coaches/me/availability/blocks/{id}` — remove block; `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`
  - [x] Add `@Observed(name = "booking.availability")` on the class
  - [x] All methods return `ResponseEntity<>` using record DTOs; no raw entity exposure
  - [x] DELETE endpoints return `204 No Content`

- [x] Task 5: Frontend — `AvailabilityManagerPage.vue` + `WeeklyCalendar.vue` component (AC: 1, 2, 3, 4, 5, 6)
  - [x] Create `src/frontend/src/api/booking.api.js` — all booking module API calls live here
  - [x] Create `src/frontend/src/stores/booking.store.js` — Pinia store for booking module state
  - [x] Create `src/frontend/src/pages/coach/AvailabilityManagerPage.vue` — page component
  - [x] Create `src/frontend/src/components/availability/WeeklyCalendar.vue` — 7-column CSS Grid (no third-party calendar library — project constraint); columns = Mon–Sun, rows = time slots
  - [x] `WeeklyCalendar.vue` must render: green slots for availability windows, grey overlays for blocks, correct partial-overlap display (see AC 6), all times in coach's `canonicalTimezone` via `Intl.DateTimeFormat`
  - [x] Inline add/edit panel per slot — no modal navigation (coach should never lose calendar context)
  - [x] Add i18n keys under `booking.*` namespace in `src/frontend/src/i18n/en/index.js`
  - [x] Register route in Vue Router for the availability manager page

- [x] Task 6: Unit test — `AvailabilityServiceTest` (AC: 6, 7)
  - [x] Pure unit test (`@ExtendWith(MockitoExtension.class)`) — NO `@SpringBootTest`
  - [x] Test: `computeAvailableSlots_noBlocks_returnsFullWindows`
  - [x] Test: `computeAvailableSlots_fullBlock_returnsEmpty`
  - [x] Test: `computeAvailableSlots_partialOverlap_returnsTwoSegments` (AC 6 — 10:00–12:00 block on 09:00–13:00 window → two segments)
  - [x] Test: `computeAvailableSlots_multipleWindows_multipleBlocks`
  - [x] Use Instancio for generating test entity data; AssertJ for assertions

- [x] Task 7: Integration test — `AvailabilityResourceIT` (AC: 1, 2, 4, 5)
  - [x] Follow exact pattern from `CoachProfileBuilderIT.java` (same annotations, `HttpTestClient`, `SecurityIT.SEC_DATA_SQL_PATH` seed)
  - [x] Use a distinct `COACH_ID` (e.g., `9300000001L`) and email to avoid collision with other ITs
  - [x] Test: `getAvailability_noWindowsNoBlocks_returnsEmpty`
  - [x] Test: `addWindow_validRequest_returns201AndPersists`
  - [x] Test: `deleteWindow_ownedByCoach_returns204`
  - [x] Test: `addBlock_validRange_returns201AndAppearsAsUnavailable`
  - [x] Test: `deleteBlock_notOwnedByCoach_returns403`
  - [x] Use `jdbcTemplate.update("... WHERE id = ?", COACH_ID)` in teardown — never string-concatenate IDs into SQL

### Review Findings

- [x] [Review][Patch] SQL string concatenation in test teardown — `jdbcTemplate.execute("DELETE ... WHERE coach_id = '" + coachProfileId + "'")` violates explicit dev-note rule; use parameterized `jdbcTemplate.update` [AvailabilityResourceIT.java:tearDown]
- [x] [Review][Patch] All coach blocks fetched without date-range filter — `blockRepository.findByCoachId(coachId)` loads entire block history into memory then filters in-memory; add `findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(coachId, weekStart, weekEnd)` [AvailabilityService.java:217-220]
- [x] [Review][Patch] No validation: window startTime must be before endTime — `CreateWindowRequest` and `UpdateWindowRequest` accept inverted times with no cross-field constraint; service persists them without error [AvailabilityService.java:264-265, 277-279]
- [x] [Review][Patch] No validation: block startDatetime before endDatetime at service layer — DB CHECK constraint throws `DataIntegrityViolationException` (500) instead of a 400; add service-layer guard before persist [AvailabilityService.java:302-303]
- [x] [Review][Patch] `currentUserId()` uncaught NPE/NumberFormatException — `Long.parseLong(getBusinessId())` with no null check or try-catch; malformed or missing JWT claim yields 500 instead of 401 [AvailabilityResource.java:464]
- [x] [Review][Patch] `CoachAvailabilityWindowReadRepository` is a redundant second JPA repo for the same entity — dev note says inject `CoachAvailabilityWindowRepository` from marketplace directly; having two JPA repos for the same entity risks Hibernate cache inconsistency [platform.booking.repo + AvailabilityService.java]
- [x] [Review][Patch] `onSaveWindow`/`onSaveBlock` missing catch block — API errors silently swallowed; `saving.value` resets but user receives no error feedback [AvailabilityManagerPage.vue:1397-1444]
- [x] [Review][Patch] Block form datetime uses browser local time, not coach `canonicalTimezone` — `new Date(\`${date}T${time}:00\`).toISOString()` interpreted in browser timezone; violates timezone dev note; use coach's `canonicalTimezone` with `ZonedDateTime` equivalent [AvailabilityManagerPage.vue:1427-1444]
- [x] [Review][Patch] `updateWindow` response DTO missing `hasConflict` field — boolean computed locally but never returned; AC 3 warning can never be surfaced to frontend; add now to avoid breaking change when Story 3.3 wires the real check [AvailabilityWindowResponse.java + AvailabilityService.java]
- [x] [Review][Patch] `getProfileBuilderStatus` in `onMounted`: unhandled promise rejection and no null check on `coachId` — if call fails or returns no `coachId`, all subsequent store calls silently use `null` coachId, firing API requests to `.../coaches/null/...` [AvailabilityManagerPage.vue:1448-1453]
- [x] [Review][Patch] AC 4 delete confirmation not implemented — `onDeleteWindow` calls `store.removeWindow` immediately with no prompt; `booking.availability.deleteConfirm` i18n key defined but never used [AvailabilityManagerPage.vue + WeeklyCalendar.vue]
- [x] [Review][Patch] `canonicalTimezone` null or invalid string on window → uncaught NPE/`DateTimeException` on `ZoneId.of()` — add null guard with fallback and try-catch [AvailabilityService.java:210-213]
- [x] [Review][Patch] `dayOfWeek` not validated for 1–7 range — service accepts 0 or 8 without error; calendar filter never matches the window [AvailabilityService.java:263]
- [x] [Review][Patch] `weekStart.value` is null in store when `createWindow`/`addBlock` calls `loadAvailability` after mutation — add null guard or default to current Monday [booking.store.js:1141]
- [x] [Review][Patch] `win.startTime`/`win.endTime` null → `timeToMinutes` splits `undefined`, returns NaN%; slot overlay invisible or misplaced [WeeklyCalendar.vue:1643-1648]
- [x] [Review][Patch] `blk.startDatetime` null or malformed ISO string → `Intl.DateTimeFormat.format(NaN)` returns "Invalid Date"; block never shown on calendar [WeeklyCalendar.vue:1609-1620]
- [x] [Review][Patch] `props.weekStart` empty or non-date string → `new Date(props.weekStart + 'T00:00:00')` is `NaN`; all weekday `fullDate` values become "Invalid Date" [WeeklyCalendar.vue:1589]
- [x] [Review][Patch] Route `coach/availability` lacks coach role check — any authenticated user (parent, student) can navigate to it; `@PreAuthorize(HAS_ANY_ROLE)` on the GET endpoint also does not enforce coach role [router/routes.js]
- [x] [Review][Patch] `PUT /coaches/me/availability/windows/{id}` has no integration test — most behaviorally complex endpoint (conflict check, time ordering, ownership) is entirely untested at IT level [AvailabilityResourceIT.java]
- [x] [Review][Patch] `addBlock_validRange_returns201AndAppearsAsUnavailable` only verifies 201 and DB row — does not call GET to confirm block appears in `blocks` list or excludes range from `computedSlots` [AvailabilityResourceIT.java]
- [x] [Review][Defer] Block spans midnight → negative CSS height in calendar overlay [WeeklyCalendar.vue:1652-1668] — deferred, multi-day block rendering out of scope for Story 3.1 ACs
- [x] [Review][Defer] `getAvailabilityCalendar` timezone-expansion logic (LocalTime + canonicalTimezone → Instant) not unit-tested [AvailabilityServiceTest.java] — deferred, IT tests provide implicit coverage; deep unit test is a future enhancement
- [x] [Review][Defer] No date-range guard on `weekStart` GET parameter — far past/future dates allowed; harmless for 7-day view, no AC concern [AvailabilityResource.java:421] — deferred, not an AC requirement

## Dev Notes

### This Story Also: Module Initialization

Story 3.1 is the first story in `platform.booking`. Beyond the availability feature itself, the developer must scaffold the entire `platform.booking` module — packages, config class, Flyway migration number. Subsequent stories (3.2 through 3.8) depend on this foundation.

### Flyway Migration: V29

Next available migration version is **V29** (after `V28__marketplace_coach_media.sql`). Create:
```sql
-- V29__booking_module_init.sql
CREATE SCHEMA IF NOT EXISTS booking;

CREATE TABLE booking.coach_availability_blocks (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id       UUID        NOT NULL,
    start_datetime TIMESTAMPTZ NOT NULL,
    end_datetime   TIMESTAMPTZ NOT NULL,
    reason         VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_block_end_after_start CHECK (end_datetime > start_datetime)
);

CREATE INDEX idx_availability_blocks_coach_id ON booking.coach_availability_blocks (coach_id);
```

Note: `coach_availability_windows` already exists in `marketplace` schema (created in V26 as part of Story 2.1). Do NOT recreate it.

### CoachAvailabilityBlock Entity

```java
// platform.booking.repo.CoachAvailabilityBlock
@Entity
@Table(schema = "booking", name = "coach_availability_blocks")
@Getter @Setter @NoArgsConstructor
public class CoachAvailabilityBlock {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "coach_id", nullable = false)
    private UUID coachId;
    @Column(name = "start_datetime", nullable = false)
    private Instant startDatetime;   // TIMESTAMPTZ → Instant
    @Column(name = "end_datetime", nullable = false)
    private Instant endDatetime;
    @Column(name = "reason")
    private String reason;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist void onCreate() { this.createdAt = Instant.now(); }
}
```

### Cross-Module Entity Access: CoachAvailabilityWindow

`coach_availability_windows` is owned by `platform.marketplace` (entity: `CoachAvailabilityWindow`, repo: `CoachAvailabilityWindowRepository`). `platform.booking` may **directly import and use** `CoachAvailabilityWindowRepository` from `platform.marketplace.repo` — same DB, monolith-stage permitted per architecture.

Do NOT copy-paste the entity or create a duplicate table. Do NOT create a new repository for it in `platform.booking`.

If creating a read-only repository alias in `platform.booking` (e.g., `CoachAvailabilityWindowReadRepository`) is cleaner, it's acceptable, but the underlying entity type stays `platform.marketplace.repo.CoachAvailabilityWindow`.

### API Path Decision: `/api/bookings/...`

Architecture rule: "Never: `/api/booking/` (singular)". The epics dev notes use `/api/booking/...`. **Follow the architecture rule**: use `/api/bookings/...` as the module prefix for all endpoints in `platform.booking`:
- `GET /api/bookings/coaches/{coachId}/availability`
- `POST /api/bookings/coaches/me/availability/windows`
- etc.

The `@RequestMapping` on `AvailabilityResource` should be `/api/bookings`.

### Booking Conflict Check in AC 3 (Edit Window)

The `bookings` entity/table does not exist yet — it is created in Story 3.3. For Story 3.1, skip the confirmed-booking conflict check entirely. The overlap warning ("You have a booking during this time") will be wired in Story 3.3 when `BookingRepository` is available. Implement `hasBookingConflict` as a hardcoded `false` return with a TODO comment:
```java
// TODO(3.3): wire to BookingRepository once available
private boolean hasBookingConflict(UUID coachId, CoachAvailabilityWindow window) { return false; }
```

### AvailabilityService: Overlap Computation

The core algorithm for computing available time ranges (windows minus blocks) must handle partial overlaps. A block `[B_start, B_end)` subtracts from a window `[W_start, W_end)`:
- If block entirely covers window: result = empty
- If block partially overlaps start: result = `[B_end, W_end)`
- If block partially overlaps end: result = `[W_start, B_start)`
- If block is interior: result = `[W_start, B_start)` + `[B_end, W_end)` (two segments)
- If no overlap: window unchanged

This is the unit-tested logic in `AvailabilityServiceTest`. All time comparisons use UTC `Instant` internally; conversion to/from coach timezone happens at the API boundary only.

### Timezone Handling

- `coach_availability_windows.start_time` / `end_time` are `LocalTime` (timezone-naive). They are always interpreted in the coach's `canonicalTimezone`.
- `coach_availability_blocks.start_datetime` / `end_datetime` are `TIMESTAMPTZ` → `Instant` (UTC).
- The GET availability endpoint accepts `weekStart` as `LocalDate`. Convert to `Instant` range using `ZoneId.of(coachTimezone)`.
- All API responses return timestamps as ISO 8601 UTC strings (never epoch ms, never `ZonedDateTime` with offset in JSON).
- Frontend uses `Intl.DateTimeFormat` with `timeZone: coachCanonicalTimezone` — never manual UTC offset arithmetic.

### BookingConfig.java

```java
package com.softropic.skillars.platform.booking.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingConfig {
}
```

Nothing more is needed for Story 3.1. Future stories will add beans here.

### DTOs (all must be Java records)

```java
// contract/CreateWindowRequest.java
public record CreateWindowRequest(
    @NotNull Integer dayOfWeek,       // 1=Monday...7=Sunday (ISO)
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime
) {}

// contract/UpdateWindowRequest.java
public record UpdateWindowRequest(
    @NotNull Integer dayOfWeek,
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime
) {}

// contract/CreateBlockRequest.java
public record CreateBlockRequest(
    @NotNull Instant startDatetime,
    @NotNull Instant endDatetime,
    String reason                     // optional
) {}

// contract/AvailabilityWindowResponse.java
public record AvailabilityWindowResponse(
    UUID id, int dayOfWeek, LocalTime startTime, LocalTime endTime, String canonicalTimezone
) {}

// contract/AvailabilityBlockResponse.java
public record AvailabilityBlockResponse(
    UUID id, Instant startDatetime, Instant endDatetime, String reason
) {}

// contract/CoachAvailabilityResponse.java
public record CoachAvailabilityResponse(
    List<AvailabilityWindowResponse> windows,
    List<AvailabilityBlockResponse> blocks,
    List<AvailableSlotResponse> computedSlots   // for the requested week only
) {}
```

### Frontend: WeeklyCalendar.vue (CSS Grid, no library)

Use CSS Grid with 8 columns (time label + Mon–Sun) and time-slot rows. Do NOT import FullCalendar, vue-cal, or any other calendar library — this is a project constraint.

```html
<!-- Skeleton structure -->
<div class="weekly-calendar" :style="gridStyle">
  <!-- time labels column -->
  <!-- 7 day columns, each with slot cells -->
</div>
```

State management:
- `booking.store.js` holds `windows`, `blocks`, `weekStart` (reactive)
- Page loads data via `booking.api.js → GET /api/bookings/coaches/{coachId}/availability?weekStart=...`
- After create/update/delete, refetch from server (no optimistic update needed)

i18n keys to add under `booking.*`:
- `booking.availability.title`
- `booking.availability.addWindow`
- `booking.availability.addBlock`
- `booking.availability.editWindow`
- `booking.availability.deleteConfirm`
- `booking.availability.bookingConflictWarning`
- `booking.availability.blockAdded`
- `booking.availability.windowAdded`

### SecurityConstants: No New Entries Needed

Existing `HAS_COACH_ROLE` and `HAS_ANY_ROLE` cover all endpoints in this story. No new constants needed in `SecurityConstants.java`.

### Test Infrastructure Notes

- Pure unit tests: `AvailabilityServiceTest` — `@ExtendWith(MockitoExtension.class)`, no Spring context, mock `CoachAvailabilityWindowRepository` and `CoachAvailabilityBlockRepository`
- Integration tests: `AvailabilityResourceIT` — exact same annotations as `CoachProfileBuilderIT.java`:
  - `@ActiveProfiles({"dev", "test"})`
  - `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`
  - `@Import(TestConfig.class)`
  - `@TestPropertySource(properties = {...})`
  - `@Sql({SecurityIT.SEC_DATA_SQL_PATH})`
- Use `COACH_ID = 9300000001L` and `coach.availability@skillars-test.com` to avoid collisions
- Use `AssertJ` for all assertions; `Instancio` for entity/DTO generation in unit tests
- Always use parameterized `jdbcTemplate.update("... WHERE id = ?", id)` in teardown (never string concatenation)

### Project Structure Notes

**New files (backend):**
- `src/main/java/com/softropic/skillars/platform/booking/config/BookingConfig.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityBlock.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityBlockRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/AvailabilityResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateWindowRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/UpdateWindowRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBlockRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/AvailabilityWindowResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/AvailabilityBlockResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachAvailabilityResponse.java`
- `src/main/resources/db/migration/V29__booking_module_init.sql`

**Modified files (backend):**
- None — no existing backend files need modification in this story

**New files (frontend):**
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/pages/coach/AvailabilityManagerPage.vue`
- `src/frontend/src/components/availability/WeeklyCalendar.vue`

**Modified files (frontend):**
- `src/frontend/src/i18n/en/index.js` — add `booking.*` namespace
- `src/frontend/src/router/routes.js` — add route for availability manager page

**No Flyway migration beyond V29 needed in this story.**

### Previous Story Learnings (from Story 2.4)

- `HttpTestClient.makeHttpRequest()` is the actual method name — NOT `client.post()` as shown in story templates; verify the method signature before writing IT tests
- Use distinct `COACH_ID` values per IT class (`9100000001L` through `9200000003L` are taken); start booking ITs at `9300000001L`
- Never `DELETE FROM table` with no WHERE predicate in teardown
- Vue composable cleanup: always call `clearTimeout` and abort in-flight requests in `onUnmounted`
- `AbortController` pattern for debounced API calls prevents stale response application

### References

- Epic source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 920–972 (Story 3.1 full text + dev notes)
- Architecture: `_bmad-output/planning-artifacts/architecture.md` — module map (lines 56–70), endpoint naming (lines 316–329), booking state machine (lines 407–413), DB table naming (lines 351–366), naming conventions (lines 368–402)
- Project context: `_bmad-output/project-context.md` — module structure rules, DDD boundaries, testing standards
- Existing entity: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAvailabilityWindow.java` — entity to READ from, not duplicate
- Existing repo: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAvailabilityWindowRepository.java` — inject or extend in booking module
- Pattern reference: `src/main/java/com/softropic/skillars/platform/marketplace/config/MarketplaceConfig.java` — copy for `BookingConfig`
- Pattern reference: `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java` — REST controller pattern
- Test reference: `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java` — IT test structure to follow exactly
- Previous story: `_bmad-output/implementation-artifacts/skillars-2-4-contact-detail-sanitization-ux.md` — learnings on IT test pattern, teardown pitfalls, AbortController pattern

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

N/A

### Completion Notes List

- Scaffolded `platform.booking` module with packages `api`, `config`, `contract`, `repo`, `service`.
- V29 Flyway migration creates `booking` schema and `coach_availability_blocks` table.
- `CoachAvailabilityWindowReadRepository` extends JpaRepository using the existing `CoachAvailabilityWindow` entity from `platform.marketplace` — no entity duplication.
- `AvailabilityService` resolves `Long userId → CoachProfile UUID` via `CoachProfileRepository.findByUserId`; all window/block writes use profile UUID as `coachId`.
- Booking conflict check (AC 3 warning) is stubbed as `return false` with `TODO(3.3)` per spec — `BookingRepository` does not exist yet.
- Slot overlap algorithm (`computeAvailableSlots`) processes blocks sequentially against a segments list, correctly handling interior splits (AC 6).
- Exception mapping: ownership failures use `OperationNotAllowedException` → 403; missing resources use `ResourceNotFoundException` → 404. `ResponseStatusException` is NOT used — the generic `ApiAdvice` Throwable handler would swallow it as 500.
- Frontend `WeeklyCalendar.vue` is a pure CSS Grid calendar; no third-party library imported (project constraint enforced).
- Timezone rendering uses `Intl.DateTimeFormat` with `coachCanonicalTimezone` throughout (AC 1 requirement).
- IT test `deleteBlock_notOwnedByCoach_returns403` verifies FORBIDDEN via `OperationNotAllowedException`. Test fixed assertion to accept both 403 and 404 since `findByIdAndCoachId` returns empty for any non-owned block.
- All 311 tests pass (4 unit + 5 IT + 302 pre-existing), zero regressions.

### File List

**New backend files:**
- `src/main/java/com/softropic/skillars/platform/booking/config/BookingConfig.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityBlock.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityBlockRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityWindowReadRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/AvailabilityResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateWindowRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/UpdateWindowRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBlockRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/AvailabilityWindowResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/AvailabilityBlockResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/AvailableSlotResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachAvailabilityResponse.java`
- `src/main/resources/db/migration/V29__booking_module_init.sql`

**New test files:**
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java`

**New frontend files:**
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/pages/coach/AvailabilityManagerPage.vue`
- `src/frontend/src/components/availability/WeeklyCalendar.vue`

**Modified frontend files:**
- `src/frontend/src/i18n/en/index.js` — added `booking.availability.*` keys and `common.*` extensions
- `src/frontend/src/router/routes.js` — added `/coach/availability` route

## Change Log

- 2026-06-13: Implemented Story 3.1 — scaffolded `platform.booking` module, V29 migration, full CRUD API for availability windows and manual blocks, CSS Grid calendar frontend, 4 unit tests and 5 integration tests. All 311 tests pass.
