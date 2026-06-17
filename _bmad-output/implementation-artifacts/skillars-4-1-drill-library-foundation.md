# Story skillars-4.1: Drill Library Foundation

Status: done

## Story

As a coach,
I want access to a curated platform drill library and a private library for my own drills,
so that I have professionally validated content to build sessions from and a space to store my own coaching material.

## Acceptance Criteria

1. **AC 1: Schema and tables exist** — Given `V38__session_module_init.sql` runs, then the `session` PostgreSQL schema exists; `session.drills` table has: `id UUID PK`, `name VARCHAR`, `description VARCHAR`, `library_type VARCHAR` (PLATFORM|COACH), `owner_coach_id UUID nullable`, `status VARCHAR` (ACTIVE|ARCHIVED), `metadata JSONB` (13-field DrillMetadata), `trans_key VARCHAR(100) UNIQUE nullable` (frontend i18n key; populated for platform drills, null for coach drills), `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`, `version INT` (optimistic locking); `session.drill_video_refs` table has: `drill_id UUID FK`, `video_id UUID nullable`, `ref_count INT DEFAULT 1`.

2. **AC 2: Foundation 20 seed drills** — Given `V39__session_foundation_20_drills.sql` runs, then 20 Platform Library drills are present in 4 packs: Master Touch (5), Sniper (5), Escape Artist (5), Wall (5); all have `library_type = 'PLATFORM'`, `owner_coach_id = null`, all 13 metadata fields populated, and `trans_key` populated with a unique `sessDrill.*` key for each drill; corresponding `name` and `desc` entries exist under the `sessDrill` namespace in `en/index.js` and `de/index.js`; `drill_video_refs.video_id` may be null at seed time.

3. **AC 3: Browse platform drill library** — Given an authenticated coach calls `GET /api/session/drills?library=PLATFORM`, then all 20 ACTIVE Platform Library drills are returned with full metadata including `transKey`; guests and parents receive 403. If the `library` param is omitted or has any value other than `PLATFORM` or `PRIVATE`, the endpoint returns `400 Bad Request` with `ErrorDto` code `validation.invalidParam`.

4. **AC 4: Browse private drill library** — Given an authenticated coach calls `GET /api/session/drills?library=PRIVATE`, then only drills where `owner_coach_id = authenticated coach's UUID` **and** `status = ACTIVE` are returned (archived private drills are excluded); an empty list (not error) is returned if the coach has no active private drills.

5. **AC 5: Clone a drill** — Given a coach calls `POST /api/session/drills/{drillId}/clone`:
   - If `drillId` does not exist, return `404 Not Found`.
   - If the source drill has `library_type = 'COACH'` (i.e. it is another coach's private drill or the caller's own), return `403 Forbidden` — only `PLATFORM` drills may be cloned.
   - If the source drill has `status = 'ARCHIVED'`, return `404 Not Found` (treat archived as non-existent to callers).
   - Otherwise, a new `session.drills` row is created with `library_type = 'COACH'`, `owner_coach_id = authenticated coach's id`, all metadata copied from source; if the source drill has a video reference, `drill_video_refs.ref_count` on the original is atomically incremented via `UPDATE session.drill_video_refs SET ref_count = ref_count + 1 WHERE drill_id = ?` (no @Version); the cloned drill is immediately available in the coach's private library.

6. **AC 6: Session Builder feature gate — Scout blocked** — Given a Scout-tier coach calls `POST /api/session/plans` (stub endpoint), when the feature gate is evaluated via `ConfigService.getBoolean("feature.sessionBuilder.enabled.SCOUT")` (resolves to false from V38 seed), then the endpoint returns `403 Forbidden` with `ErrorDto` code `security.featureGated` (handled by existing `FeatureGatedException` in ApiAdvice). The drill library browse endpoints (AC 3, AC 4) remain ungated.

7. **AC 7: Session Builder gate — Instructor+ passes** — Given an Instructor or Academy tier coach calls `POST /api/session/plans`, when the gate resolves `feature.sessionBuilder.enabled.INSTRUCTOR` or `feature.sessionBuilder.enabled.ACADEMY` (both true), then a `201` stub response is returned. Full session plan creation implemented in Story 4.4.

## Tasks / Subtasks

### Backend — Database Migrations

- [x] Task 1: Write `V38__session_module_init.sql` (AC: 1, 6)
  - [x] `CREATE SCHEMA IF NOT EXISTS session;`
  - [x] Create `session.drills` table:
    ```sql
    CREATE TABLE session.drills (
        id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        name            VARCHAR(200) NOT NULL,
        description     TEXT,
        library_type    VARCHAR(10) NOT NULL CHECK (library_type IN ('PLATFORM', 'COACH')),
        owner_coach_id  UUID,
        status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
        metadata        JSONB       NOT NULL,
        version         INT         NOT NULL DEFAULT 0,
        created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
        CONSTRAINT chk_drill_owner CHECK (
            (library_type = 'PLATFORM' AND owner_coach_id IS NULL) OR
            (library_type = 'COACH'    AND owner_coach_id IS NOT NULL)
        )
    );
    ```
  - [x] Create `session.drill_video_refs` table:
    ```sql
    CREATE TABLE session.drill_video_refs (
        drill_id    UUID PRIMARY KEY REFERENCES session.drills(id) ON DELETE CASCADE,
        video_id    UUID,
        ref_count   INT NOT NULL DEFAULT 1
    );
    ```
  - [x] Add indexes: `idx_drills_library_type`, `idx_drills_owner_coach_id`, `idx_drills_status`
  - [x] Seed feature gate config keys (in same migration, after tables):
    ```sql
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
      (60, 'feature.sessionBuilder.enabled.SCOUT',      'false', 'STRING', 'Session Builder gate: Scout tier',      NOW()),
      (61, 'feature.sessionBuilder.enabled.INSTRUCTOR', 'true',  'STRING', 'Session Builder gate: Instructor tier', NOW()),
      (62, 'feature.sessionBuilder.enabled.ACADEMY',    'true',  'STRING', 'Session Builder gate: Academy tier',    NOW())
    ON CONFLICT DO NOTHING;
    ```
  - [x] **CRITICAL**: Check the last config id in V37 to ensure id 60–62 do not conflict. V37 used ids 51–55; next safe start is 60. If unsure, inspect existing rows at startup — use ON CONFLICT DO NOTHING.
  - [x] File: `src/main/resources/db/migration/V38__session_module_init.sql`

- [x] Task 2: Write `V39__session_foundation_20_drills.sql` (AC: 2)
  - [x] Insert 20 Platform Library drills with full JSONB metadata. Each drill's `metadata` column must include ALL 13 fields: `primarySkills` (array), `secondarySkills` (array), `skillWeighting` (object: skill→weight), `repDensity` (int), `intensity` (1–5), `pressureLevel` (1–5), `cognitiveLoad` (1–5), `matchRealism` (1–5), `weakFootBias` (boolean), `difficultyTier` (string: U8/U10/U12/U14/U16/U18/AMATEUR/PRO), `equipmentRequired` (array), `recommendedGroupSize` (string), `coachingPoints` (array of 3–4 strings)
  - [x] 4 packs × 5 drills each: **Master Touch** (ball mastery/control), **Sniper** (finishing/shooting), **Escape Artist** (dribbling/turns under pressure), **Wall** (passing/receiving)
  - [x] All drills: `library_type = 'PLATFORM'`, `owner_coach_id = NULL`, `status = 'ACTIVE'`
  - [x] Do NOT insert `drill_video_refs` rows yet — video association happens in Story 4.3
  - [x] File: `src/main/resources/db/migration/V39__session_foundation_20_drills.sql`

### Backend — Module Scaffold

- [x] Task 3: Scaffold `platform.session` module structure (AC: all)
  - [x] Create package hierarchy:
    ```
    com.softropic.skillars.platform.session/
      api/
        DrillLibraryResource.java
        SessionPlanResource.java     ← stub for tier-gate AC
      service/
        DrillLibraryService.java
      repo/
        Drill.java
        DrillVideoRef.java
        DrillRepository.java
        DrillVideoRefRepository.java
      contract/
        DrillResponse.java
        SessionErrorCode.java
      config/
        SessionConfig.java
    ```
  - [x] Create `SessionConfig.java` — `@Configuration` (empty for now)
  - [x] Check `SecurityConstants` (in `platform.security.contract`) for any session-module entries required by `DrillLibraryResource` — if a session-specific role constant is needed beyond the existing `HAS_COACH_ROLE`, add it here; if `HAS_COACH_ROLE` suffices, no change needed (verify before assuming)
  - [x] File locations: `src/main/java/com/softropic/skillars/platform/session/...`

### Backend — Entities

- [x] Task 4: Implement `Drill.java` entity (AC: 1, 3, 4, 5)
  - [x] Use `@Type(JsonBinaryType.class)` from `com.vladmihalcea.hibernate.type.json.JsonBinaryType` for `metadata` JSONB field (hypersistence-utils — already on classpath from video module)
  - [x] `DrillMetadata` is a Java record (in `platform.session.contract` or as an inner class) with all 13 fields mapped to the JSONB schema:
    ```java
    public record DrillMetadata(
        List<String> primarySkills,
        List<String> secondarySkills,
        Map<String, Integer> skillWeighting,
        int repDensity,
        int intensity,        // 1–5
        int pressureLevel,    // 1–5
        int cognitiveLoad,    // 1–5
        int matchRealism,     // 1–5
        boolean weakFootBias,
        String difficultyTier, // U8/U10/U12/U14/U16/U18/AMATEUR/PRO
        List<String> equipmentRequired,
        String recommendedGroupSize,
        List<String> coachingPoints   // 3–4 items
    ) {}
    ```
  - [x] `Drill` entity: `@Entity @Table(schema="session", name="drills")` with `@Version` field
  - [x] Use `@Getter @Setter @NoArgsConstructor` (Lombok)
  - [x] `libraryType` field as `String` (PLATFORM/COACH); `status` field as `String` (ACTIVE/ARCHIVED)
  - [x] `ownerCoachId` as `UUID nullable`
  - [x] `metadata` as `DrillMetadata` type with `@Column(columnDefinition = "jsonb")` and `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6 pattern — equivalent to @Type(JsonBinaryType.class))
  - [x] `@PrePersist` / `@PreUpdate` for timestamps

- [x] Task 5: Implement `DrillVideoRef.java` entity (AC: 1, 5)
  - [x] `@Entity @Table(schema="session", name="drill_video_refs")`
  - [x] `@Id drillId UUID` (drill_id is the PK and FK)
  - [x] `videoId UUID nullable`
  - [x] `refCount int` (no @Version needed — managed via atomic SQL)

### Backend — Repository

- [x] Task 6: Implement `DrillRepository.java` (AC: 3, 4)
  - [x] `JpaRepository<Drill, UUID>`
  - [x] `List<Drill> findByLibraryTypeAndStatus(String libraryType, String status)` — for platform drills
  - [x] `List<Drill> findByOwnerCoachIdAndStatus(UUID ownerCoachId, String status)` — for private drills

- [x] Task 7: Implement `DrillVideoRefRepository.java` (AC: 5)
  - [x] `JpaRepository<DrillVideoRef, UUID>`
  - [x] `Optional<DrillVideoRef> findByDrillId(UUID drillId)`
  - [x] `List<DrillVideoRef> findByDrillIdIn(Collection<UUID> drillIds)` — used by list endpoints to batch-fetch video refs in a single query (avoids N+1)
  - [x] Custom `@Modifying @Query` for atomic ref_count increment:
    ```java
    @Modifying
    @Query("UPDATE DrillVideoRef d SET d.refCount = d.refCount + 1 WHERE d.drillId = :drillId")
    void incrementRefCount(@Param("drillId") UUID drillId);
    ```

### Backend — ConfigService Extension

- [x] Task 8: Add `getBoolean(String key)` to `ConfigService.java` (AC: 6, 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
  - [x] Add method after `getLong`:
    ```java
    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(getString(key));
    }
    ```
  - [x] This is the ONLY call pattern permitted — never cache the result in a field

### Backend — Service

- [x] Task 9: Implement `DrillLibraryService.java` (AC: 3, 4, 5, 6, 7)
  - [x] `@Service @Transactional @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `DrillRepository`, `DrillVideoRefRepository`, `ConfigService`, `CoachProfileService` (from `platform.marketplace`)
  - [x] **Cross-module rule**: inject `CoachProfileService` (service-to-service is allowed); do NOT inject `CoachSubscriptionRepository` (cross-module repo injection is forbidden by architecture)
  - [x] `getCoachTier(UUID coachId)` — private helper calling `CoachProfileService.getCoachSubscriptionTier(coachId)` returning `CoachSubscriptionTier` (you may need to add this method to `CoachProfileService`)
  - [x] `listPlatformDrills()`: batch-fetch with N+1 prevention
  - [x] `listPrivateDrills(Long coachUserId)`: resolves userId→coachId via CoachProfileService, batch-fetch with N+1 prevention
  - [x] `cloneDrill(UUID sourceDrillId, Long coachUserId)`: 404/403/clone/refcount logic
  - [x] `checkSessionBuilderGate(Long coachUserId)`: tier lookup + config gate

- [x] Task 10: Add `getCoachSubscriptionTier(UUID coachId)` to `CoachProfileService.java` (AC: 6, 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
  - [x] Inject `CoachSubscriptionRepository` (already in CoachProfileService scope)
  - [x] Return `CoachSubscriptionTier` — throw `ResourceNotFoundException` if no subscription found
  - [x] Also added `getCoachIdByUserId(Long userId)` to resolve userId→UUID profileId

### Backend — Contract

- [x] Task 11: Implement `DrillResponse.java` record (AC: 3, 4, 5)
  - [x] `platform.session.contract` package
  - [x] Fields: `id UUID`, `name String`, `description String`, `libraryType String`, `ownerCoachId UUID` (nullable), `status String`, `metadata DrillMetadata`, `hasVideo boolean`, `transKey String` (nullable), `createdAt Instant`
  - [x] Java `record` type

- [x] Task 12: Implement `SessionErrorCode.java` enum (AC: 6)
  - [x] `public enum SessionErrorCode implements ErrorCode`
  - [x] Values: `DRILL_NOT_FOUND` — only error code needed in this story
  - [x] `@Override public String getErrorCode() { return this.name(); }`

- [x] Task 13: Register `DrillNotFoundException` / `DrillNotFoundHandler` in `ApiAdvice.java` (AC: 3)
  - [x] Used `ResourceNotFoundException` (already handled → 404). Also added `InvalidParamException` + handler for library param validation → 400 with `validation.invalidParam`

### Backend — Resource (API)

- [x] Task 14: Implement `DrillLibraryResource.java` (AC: 3, 4, 5)
  - [x] `@Observed(name = "session.drills") @RestController @RequestMapping("/api/session/drills") @RequiredArgsConstructor`
  - [x] `GET /api/session/drills?library=PLATFORM|PRIVATE` → `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` with validation → 400
  - [x] `POST /api/session/drills/{drillId}/clone` → `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` → 201
  - [x] Use `SecurityUtil` to resolve coach Long userId from Principal

- [x] Task 15: Implement `SessionPlanResource.java` stub (AC: 6, 7)
  - [x] `@Observed(name = "session.plans") @RestController @RequestMapping("/api/session/plans") @RequiredArgsConstructor`
  - [x] `POST /api/session/plans` → `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`; calls `drillLibraryService.checkSessionBuilderGate(coachUserId)` → 201 stub
  - [x] This is a temporary stub — Story 4.4 fully implements the request body and business logic

### Frontend — API

- [x] Task 16: Update `session.api.js` (AC: 3, 4, 5)
  - [x] File: `src/frontend/src/api/session.api.js`
  - [x] Kept existing `refresh()` method; added `getDrills(library, params)` and `cloneDrill(drillId)`

- [x] Task 17: Create `DrillLibraryPage.vue` stub in coach pages (AC: 3, 4)
  - [x] File: `src/frontend/src/pages/coach/DrillLibraryPage.vue`
  - [x] `<script setup>` — on mount, calls `sessionApi.getDrills('PLATFORM')`; minimal template with i18n keys
  - [x] Route wired in `src/frontend/src/router/routes.js` under coach layout (`/coach/drills`)
  - [x] Scout-tier overlay deferred to Story 4.2

### Testing

- [x] Task 18: `DrillLibraryServiceTest.java` — unit tests (AC: 5)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java`
  - [x] 7 unit tests — all pass; `@ExtendWith(MockitoExtension.class)`, no Spring context
  - [x] Covers: clone with/without video ref, COACH-type 403, ARCHIVED 404, not-found 404, Scout gate 403, Instructor gate pass

- [x] Task 19: `DrillLibraryResourceIT.java` — integration tests (AC: 3, 4, 5, 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/api/DrillLibraryResourceIT.java`
  - [x] 11 integration tests; `@SpringBootTest`, `HttpTestClient`, `JdbcTemplate` for setup/teardown
  - [x] Covers all AC scenarios: PLATFORM/PRIVATE list, 400 validation, clone happy/sad paths, parent 403, Scout gate 403, Instructor gate 201

- [x] Task 20: Add `trans_key` for drill i18n (AC: 1, 2, 3)
  - [x] `trans_key VARCHAR(100) UNIQUE` (nullable) added to `session.drills` in V38 migration
  - [x] All 20 platform seed drills in V39 populated with `sessDrill.*` keys
  - [x] `transKey` field added to `Drill.java` entity and `DrillResponse.java` record
  - [x] `DrillLibraryService.toResponse` passes `drill.getTransKey()`; clone sets no `transKey` (coach drills are user-authored, not translated)
  - [x] `sessDrill` namespace added to `en/index.js` and `de/index.js` with `name` and `desc` sub-keys for all 20 drills
  - [x] Frontend resolution rule: `transKey` present → `t(drill.transKey + '.name')` / `t(drill.transKey + '.desc')`; null → use `drill.name` / `drill.description` directly (coach drills)

## Dev Notes

### This is a First-in-Module Story — Critical Scaffolding
`platform.session` does NOT exist yet. This story creates it from scratch. Every story file, package, and directory must be created — nothing to extend.

### Cross-Module Dependency: Coach Tier Lookup
`DrillLibraryService` needs the coach's `CoachSubscriptionTier`. The architecture forbids cross-module repository injection. Inject `CoachProfileService` from `platform.marketplace` instead, and add a `getCoachSubscriptionTier(UUID coachId)` method to it. `CoachSubscriptionRepository` is already injected in `CoachProfileService`.

### ConfigService.getBoolean Does NOT Exist Yet
The existing `ConfigService` at `platform.config.service.ConfigService` has `getString`, `getLong`, `find`, `findResponse`, `updateConfig` — but no `getBoolean`. Add it in Task 8. The pattern from `getLong` applies: call `getString(key)` and parse.

### Feature Gate Config Key Format
The V20 seed has keys like `coach.tier.scout.session_builder = 'true'`. This story uses a DIFFERENT key format: `feature.sessionBuilder.enabled.{TIER}` (uppercase tier name). Add these new keys via V38 migration. Do NOT modify V20 (Flyway migrations are immutable). The V20 keys remain in the DB but are NOT used by `DrillLibraryService` — Story 4.1 uses the new format.

### JSONB Dependency — hypersistence-utils
`@Type(JsonBinaryType.class)` requires `hypersistence-utils` — already on the classpath (used in `platform.video` and/or `platform.filestorage`). Do NOT add a new Maven dependency. Verify by checking `pom.xml` for `com.vladmihalcea:hypersistence-utils-hibernate-63` or similar.

### Flyway V-Number Sequence
The last migration is `V37__session_pack_expiry_pause.sql`. This story introduces:
- V38 = session module init (schema, tables, config seed)
- V39 = Foundation 20 drills seed

### Session Schema
Use PostgreSQL schema `session` (like `booking` uses `booking` schema). In entities: `@Table(schema = "session", name = "drills")`.

### AtomicSQL for ref_count — No @Version on DrillVideoRef
Per epics dev notes: `drill_video_refs.refCount` increment/decrement uses atomic SQL — no `@Version`. Use `@Modifying @Query` on the repository.

### ref_count Lifecycle — Increment Only in This Story
This story only increments `drill_video_refs.refCount` (on clone). The **decrement** path — when a cloned drill is deleted/archived — is **not** implemented here and is owned by Story 4.3. Story 4.3 must decrement the SOURCE drill's `refCount` when a coach deletes their clone. `ON DELETE CASCADE` on `drill_video_refs` removes the clone's own row but does NOT touch the source drill's row — the decrement requires an explicit `@Modifying @Query` triggered from the archival/delete flow.

### FeatureGatedException Already Exists and Is Handled
`platform.security.contract.exception.FeatureGatedException` already exists. `ApiAdvice` already handles it with `@ResponseStatus(HttpStatus.FORBIDDEN)` → returns `ErrorDto` with code `security.featureGated`. No new exception class or ApiAdvice handler needed.

### Session Builder Gate — What It Guards
Only `POST /api/session/plans` (stub in this story) is gated. The drill BROWSE and CLONE endpoints are accessible to all coach tiers — Scout can browse and clone drills for discovery. The full session plan creation (Story 4.4) is what requires Instructor+.

### SecurityUtil — Getting Coach UUID
Use `SecurityUtil` (already exists in `platform.security.service`) to get the authenticated coach's `businessId` as a `UUID`. Pattern from `BookingResource`:
```java
private UUID currentCoachUserId() {
    return securityUtil.getCurrentPrincipal()
        .map(p -> UUID.fromString(p.getBusinessId()))
        .orElseThrow(InsufficientAuthenticationException::new);
}
```

### Frontend: session.api.js Already Exists (but is minimal)
`src/frontend/src/api/session.api.js` already exists with a single `refresh()` method. Add drill methods; do NOT delete `refresh()`.

### DrillLibraryPage Route
Add to the coach route group in `src/frontend/src/router/index.js`. The existing coach pages include `CommandCenter`, `BookingRequests`, `AvailabilityManager` — place `DrillLibrary` route alongside them.

### i18n Keys Required
Add to both `src/frontend/src/i18n/en/index.js` and German locale file (if German locale exists):
- `session.drillLibrary.title`
- `session.drillLibrary.placeholder`

### Test Base Class
Create `BaseSessionIT.java` following the exact pattern of `BaseBookingIT` — test container config, coach/parent user creation helpers, token helpers.

### Project Structure Notes

| Component | Location |
|---|---|
| Session module Java root | `src/main/java/com/softropic/skillars/platform/session/` |
| V38 migration | `src/main/resources/db/migration/V38__session_module_init.sql` |
| V39 migration | `src/main/resources/db/migration/V39__session_foundation_20_drills.sql` |
| DrillLibraryPage stub | `src/frontend/src/pages/coach/DrillLibraryPage.vue` |
| session.api.js | `src/frontend/src/api/session.api.js` (already exists — UPDATE) |
| ConfigService | `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` (UPDATE — add getBoolean) |
| CoachProfileService | `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (UPDATE — add getCoachSubscriptionTier) |
| ApiAdvice | `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (NO change needed — FeatureGatedException already handled) |

### References

- Epic 4, Story 4.1 AC + dev notes [Source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 1435–1479]
- Architecture: platform.session module spec [Source: `_bmad-output/planning-artifacts/architecture.md` — Module 5, directory tree at `platform/session/`]
- Architecture: ConfigService usage pattern [Source: `_bmad-output/planning-artifacts/architecture.md` — "ConfigService Usage Pattern" section]
- Architecture: Cross-module repo injection prohibition [Source: `_bmad-output/planning-artifacts/architecture.md` — "Service Boundary — internal module contracts"]
- FeatureGatedException: `platform.security.contract.exception.FeatureGatedException` + ApiAdvice handler at line 293
- CoachSubscriptionTier enum: `platform.marketplace.contract.CoachSubscriptionTier` (SCOUT, INSTRUCTOR, ACADEMY)
- ConfigService existing methods: `getString`, `getLong`, `find` — no `getBoolean` yet
- V20 config seed (existing but unused by this story): `coach.tier.scout.session_builder = 'true'`
- V37 was last migration — V38 and V39 are next
- UX-DR14 (DrillCard anatomy), UX-DR22 (Scout tier soft teaser overlay — frontend work in Stories 4.2+) [Source: `_bmad-output/planning-artifacts/ux-design-specification.md`]
- Booking entity pattern for @Version, @PrePersist/@PreUpdate: `platform.booking.repo.Booking`
- `BookingResource` pattern for @PreAuthorize, @Observed, SecurityUtil usage
- `session.api.js` current state: `src/frontend/src/api/session.api.js` — only has `refresh()` method

## Review Findings

### External Code Review — 2026-06-17

#### Patch Items
- [x] [Review][Patch] ARCHIVED guard fires after library_type guard — ARCHIVED COACH drill returns 403 instead of spec-required 404; swap order so status is checked before type in `cloneDrill` [`DrillLibraryService.java:58-64`]
- [x] [Review][Patch] `hasVideo=true` when source `DrillVideoRef.videoId` is null — `cloneDrill` sets `hasVideo` based on ref presence alone, not `videoId != null`; add null check [`DrillLibraryService.java:80-84`]

#### Deferred Items
- [x] [Review][Defer] `resolveMinEnabledTier` returns `"NONE"` when all gate keys are false — misleading required-tier in `FeatureGatedException`; low-probability misconfiguration edge case [`DrillLibraryService.java:103-110`] — deferred, pre-existing
- [x] [Review][Defer] `DrillVideoRef.save()` issues merge (SELECT + INSERT) instead of persist (INSERT-only) — extra SELECT on clone ref creation; no data corruption; fix with `Persistable<UUID>` when performance matters [`DrillLibraryService.java:82`] — deferred, low risk
- [x] [Review][Defer] No unique constraint on `(owner_coach_id, name)` — coach can clone the same platform drill multiple times, creating duplicates in their private library [`V38__session_module_init.sql`] — deferred, design decision

### Patch Items

- [x] [Review][Patch] `@Modifying incrementRefCount` missing `@Transactional` on repository method — relies silently on ambient transaction; if ever called from a non-transactional context will throw at runtime with no compile-time warning [`DrillVideoRefRepository.java:19`]
- [x] [Review][Patch] `@Modifying` missing `clearAutomatically = true` — Hibernate L1 cache not flushed after bulk `ref_count` UPDATE; stale cached entity visible within same transaction if read again [`DrillVideoRefRepository.java:19`]
- [x] [Review][Patch] V38 config INSERT uses `ON CONFLICT DO NOTHING` on PK (ids 60–62), not on the business key `(key)` — a PK collision would silently skip the feature gate row, leaving the config absent [`V38__session_module_init.sql:38-42`]
- [x] [Review][Patch] `getDrills` frontend: `library` set first then `...params` spread — a caller passing `{ library: 'PRIVATE' }` inside params overrides the explicit argument; should be `{ ...params, library }` [`session.api.js:9`]
- [x] [Review][Patch] `ResourceNotFoundException` args are inverted — `new ResourceNotFoundException("Drill", uuid)` sets `msg="Drill"` and `resourceName=UUID`; correct order is `(uuid, "Drill")` [`DrillLibraryService.java:53,59`]
- [x] [Review][Patch] `FeatureGatedException` wrong semantic for clone-of-COACH-drill restriction — produces misleading `security.featureGated` error code and "Feature gate blocked" log for what is a data-ownership restriction; use `ForbiddenException` or equivalent [`DrillLibraryService.java:56`]
- [x] [Review][Patch] `currentCoachUserId()` duplicated verbatim in `DrillLibraryResource` and `SessionPlanResource` — security fix applied to one will silently miss the other; extract to shared utility [`DrillLibraryResource.java:53`, `SessionPlanResource.java:33`]
- [x] [Review][Patch] Clone performs redundant `findByDrillId(saved.getId())` re-query to determine `hasVideo` — result is already known from the `ifPresent` block; use a local boolean flag instead [`DrillLibraryService.java:80`]
- [x] [Review][Patch] Missing config key → `IllegalStateException` → `ApiAdvice` maps to 409 Conflict — a misconfigured/absent feature gate key produces a semantically wrong HTTP status; add null/missing handling in `getBoolean` with a meaningful error [`ConfigService.java:getBoolean`]
- [x] [Review][Patch] `checkSessionBuilderGate` hard-codes `requiredTier = "INSTRUCTOR"` string literal in `FeatureGatedException` — should derive from tier logic or document intent; wrong for any future sub-SCOUT tier [`DrillLibraryService.java:88`]
- [x] [Review][Patch] `SessionErrorCode.DRILL_NOT_FOUND` is dead code — `ResourceNotFoundException` is thrown everywhere; either use the enum code or remove the enum [`SessionErrorCode.java`]
- [x] [Review][Patch] Clone `DrillVideoRef.refCount` relies on Java field default `= 1` rather than explicit `setRefCount(1)` — removing the field initializer would produce a DB-level insert of `ref_count = 0` [`DrillLibraryService.java:73`]

### Deferred Items

- [x] [Review][Defer] `session` schema name is a PostgreSQL non-reserved keyword — works on all tested PG versions; too disruptive to rename after migration is written [`V38__session_module_init.sql`] — deferred, pre-existing
- [x] [Review][Defer] V39 seed drills use `gen_random_uuid()` — non-deterministic IDs differ between environments; migration already written, fix would require V40 [`V39__session_foundation_20_drills.sql`] — deferred, pre-existing
- [x] [Review][Defer] Feature gate config key format relies on `tier.name()` matching DB key suffix exactly — acceptable by migration convention; new tier addition requires a matching migration [`DrillLibraryService.java:86`] — deferred, pre-existing
- [x] [Review][Defer] `POST /api/session/plans` returns 201 empty body — intentional stub; Story 4.4 fully implements it — deferred, pre-existing
- [x] [Review][Defer] `DrillLibraryPage.vue` `onMounted` no error handling — stub page; Story 4.2 builds the full UI [`DrillLibraryPage.vue:15`] — deferred, pre-existing
- [x] [Review][Defer] New coach with no profile gets ResourceNotFoundException → 404 from `getCoachIdByUserId` on private drill list — Story 4.2 edge case to guard [`CoachProfileService.java`] — deferred, pre-existing
- [x] [Review][Defer] `listPrivateDrills` no explicit `library_type = 'COACH'` filter — safe today due to DB `chk_drill_owner` constraint [`DrillRepository.java`] — deferred, pre-existing
- [x] [Review][Defer] `DrillResponse.ownerCoachId` is always null for PLATFORM drills — nullable contract undocumented; Story 4.2 frontend rendering should null-check [`DrillResponse.java`] — deferred, pre-existing
- [x] [Review][Defer] `ConfigService.getBoolean` no logging when returning false for a non-"true" value — operational visibility gap for misconfigured (not absent) keys [`ConfigService.java`] — deferred, pre-existing

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed stray `]` JSON syntax error in V39 drill seed (Pressure Escape Rondo drill `recommendedGroupSize` field)
- Resolved two UnnecessaryStubbing errors in DrillLibraryServiceTest: consolidated double stub for `findByDrillId` into chained `.thenReturn()` calls; removed redundant `Optional.empty()` stub (Mockito 4+ default for Optional-typed methods)
- Used `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) instead of `@Type(JsonBinaryType.class)` (Hibernate 5) — project uses Hibernate 6
- `businessId` in JWT is user's `Long id`, not profile UUID — resolved by adding `getCoachIdByUserId(Long userId)` to `CoachProfileService` and having service resolve internally
- `ResponseStatusException` would not produce correct error shape (ApiAdvice doesn't extend `ResponseEntityExceptionHandler`) — created `InvalidParamException` + dedicated handler → 400 with `validation.invalidParam`

### Completion Notes List

- Created `platform.session` module from scratch: config, repo, contract, service, api packages
- V38 migration creates `session` schema, `session.drills` and `session.drill_video_refs` tables with constraints and indexes; seeds feature gate config keys 60–62
- V39 seeds 20 Platform Library drills in 4 packs (Master Touch, Sniper, Escape Artist, Wall) with all 13 DrillMetadata fields
- `DrillLibraryService` handles platform/private listing (batch video lookup — no N+1), clone with atomic refCount increment, and session builder feature gate; takes `Long coachUserId` and resolves to profile UUID internally via CoachProfileService
- Added `getBoolean(String key)` to `ConfigService` and `getCoachSubscriptionTier(UUID)` + `getCoachIdByUserId(Long)` to `CoachProfileService`
- Added `InvalidParamException` + handler to `ApiAdvice` → 400 with `validation.invalidParam`
- Frontend: `session.api.js` updated, `DrillLibraryPage.vue` stub created, route added, i18n keys added to en and de locales
- Added `trans_key VARCHAR(100) UNIQUE` (nullable) to `session.drills`; all 20 seed drills carry a `sessDrill.*` key; `DrillResponse.transKey` surfaces it to the frontend; coach-cloned drills have `transKey = null` and render their stored `name`/`description` directly
- `sessDrill` namespace added to en and de i18n files with `name` + `desc` sub-keys for all 20 platform drills
- 7 unit tests and 11 integration tests — all pass; build successful (exit code 0)

### File List

**Created:**
- `src/main/resources/db/migration/V38__session_module_init.sql`
- `src/main/resources/db/migration/V39__session_foundation_20_drills.sql`
- `src/main/java/com/softropic/skillars/platform/session/config/SessionConfig.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/Drill.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRef.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRefRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/InvalidParamException.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`
- `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`
- `src/main/java/com/softropic/skillars/platform/session/api/SessionPlanResource.java`
- `src/frontend/src/pages/coach/DrillLibraryPage.vue`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/api/DrillLibraryResourceIT.java`

**Modified:**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` — added `getBoolean(String key)`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` — added `getCoachSubscriptionTier(UUID)` and `getCoachIdByUserId(Long)`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` — added `InvalidParamException` handler
- `src/frontend/src/api/session.api.js` — added `getDrills` and `cloneDrill` methods
- `src/frontend/src/router/routes.js` — added `/coach/drills` route
- `src/frontend/src/i18n/en/index.js` — added `session.drillLibrary` keys
- `src/frontend/src/i18n/de/index.js` — added `session.drillLibrary` keys
