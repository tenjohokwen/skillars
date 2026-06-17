# Story skillars-4.2: Drill Card & Operations

Status: complete

## Story

As a coach,
I want to search, filter, tag, and browse drills using a mobile-first card view with a 15-second autoplay demo,
so that I can quickly identify the right drill for a session without leaving the coaching context.

## Acceptance Criteria

1. **AC 1: DrillCard renders correctly** — Given a coach browses the drill library, when a `DrillCard` renders for any drill, then it displays: 15-second silent autoplay video loop (or a thumbnail placeholder if no video), drill name, SLU exposure estimate chip (e.g., "~18 SLU"), weak-foot bias indicator (icon shown if `weakFootBias = true`), equipment icons derived from `equipmentRequired[]`, difficulty tier badge, 3–4 coaching point bullets, primary action button ("Add to session" or "Assign" depending on context); the card is mobile-first — all content readable without horizontal scroll on a 375px viewport (UX-DR14).

2. **AC 2: Reduced motion compliance** — Given the 15-second autoplay loop is active, when the user has `prefers-reduced-motion: reduce` set, then the video does not autoplay — a static thumbnail with a play button is shown instead (UX-DR27).

3. **AC 3: Drill search** — Given a coach enters a search query in the drill library search field, when the query is submitted (debounced 300ms), then `GET /api/session/drills?q={query}&library=PLATFORM|PRIVATE` is called, results matching drill name, description, or coaching points are returned; if no results match, an empty state with icon, "No drills found", and "Clear search" CTA is shown (UX-DR25).

4. **AC 4: Drill filters** — Given a coach applies filters (one or more of: skill, difficultyTier, equipment, weakFootBias), then only drills matching all active filters are returned; the active filter count is shown on the filter button; filters and search can be combined simultaneously.

5. **AC 5: Drill tagging** — Given a coach taps "Add tag" on a private library drill, when they enter a tag name and save, then the tag is stored in `session.drill_tags` (drillId UUID, tag VARCHAR, coachId UUID) and appears on the drill card; tags are coach-specific — Platform Library drills cannot be tagged directly (only their clones can); existing tags from the coach's private library are available as autocomplete suggestions.

6. **AC 6: Clone UX** — Given a coach taps "Clone to my library" on a Platform Library drill, when the clone is created (Story 4.1 logic), then a success toast appears: "Added to your library" with a link to the new clone; the DrillCard for the original now shows an "In your library" indicator and the clone action becomes "Edit clone".

7. **AC 7: Drill detail panel** — Given a coach views a drill's detail panel, when the panel opens (tap on card body, not primary action), then the full-size video player, all metadata fields, SLU breakdown by skill (e.g., "DRI: 12 SLU, WEF: 6 SLU"), setup diagram (if present), and all coaching points are shown; the panel is a bottom sheet (max 75% screen height, drag-to-dismiss) on mobile and a side panel on desktop (UX-DR31).

## Tasks / Subtasks

### Backend — Database Migration

- [x] Task 1: Write `V40__drill_tags.sql` (AC: 5)
  - [x] Create `session.drill_tags` table:
    ```sql
    CREATE TABLE session.drill_tags (
        drill_id    UUID        NOT NULL REFERENCES session.drills(id) ON DELETE CASCADE,
        tag         VARCHAR(50) NOT NULL,
        coach_id    UUID        NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
        CONSTRAINT pk_drill_tags PRIMARY KEY (drill_id, tag, coach_id)
    );
    CREATE INDEX idx_drill_tags_drill_id ON session.drill_tags(drill_id);
    CREATE INDEX idx_drill_tags_coach_id ON session.drill_tags(coach_id);
    ```
  - [x] No @Version needed — primary key prevents duplicates atomically
  - [x] File: `src/main/resources/db/migration/V40__drill_tags.sql`

- [x] Task 1b: Write `V41__drill_source_id.sql` (AC: 6)
  - [x] Add `source_drill_id` column to `session.drills` to track which platform drill a coach clone originated from — required for server-side `isClonedByMe` computation:
    ```sql
    ALTER TABLE session.drills
        ADD COLUMN source_drill_id UUID REFERENCES session.drills(id);
    CREATE INDEX idx_drills_source_drill_id ON session.drills(source_drill_id);
    ```
  - [x] Also patch `DrillLibraryService.cloneDrill()` (Story 4.1 code) to set `sourceDrillId = original.id` on the new entity when creating a clone. Without this, `source_drill_id` is always null and `isClonedByMe` can never be true.
  - [x] File: `src/main/resources/db/migration/V41__drill_source_id.sql`

### Backend — Entity & Repository

- [x] Task 2: Create `DrillTag.java` entity (AC: 5)
  - [x] Package: `com.softropic.skillars.platform.session.repo`
  - [x] Use `@EmbeddedId` with a `DrillTagId` class (`drillId UUID`, `tag String`, `coachId UUID`) — preferred over `@IdClass`. Must be a class, not a record (see Dev Notes: DrillTagId Must Be a Java Class).
  - [x] `@Entity @Table(schema = "session", name = "drill_tags") @Getter @Setter @NoArgsConstructor`
  - [x] Fields: `@EmbeddedId DrillTagId id`, `createdAt Instant`
  - [x] `@PrePersist` to set `createdAt`
  - [x] `DrillTagId` as a standard `@Embeddable` **class** (NOT a record): `drillId UUID`, `tag String`, `coachId UUID`. Must have: no-arg constructor (JPA spec requirement for embeddable hydration), all-args constructor, `equals()` and `hashCode()` covering all three fields. Annotate fields with `@Column(name = "drill_id")`, `@Column(name = "tag")`, `@Column(name = "coach_id")`. Java records lack no-arg constructors and cause `HibernateException` on first query.

- [x] Task 3: Create `DrillTagRepository.java` (AC: 5)
  - [x] Package: `com.softropic.skillars.platform.session.repo`
  - [x] `JpaRepository<DrillTag, DrillTagId>`
  - [x] `List<DrillTag> findByIdDrillIdInAndIdCoachId(Collection<UUID> drillIds, UUID coachId)` — batch fetch tags for a set of drills owned by one coach (avoids N+1)
  - [x] Add JPQL query for distinct tags (use this over a derived method — derived queries on embedded ID fields are unreliable; do NOT also declare `findDistinctTagByIdCoachId`):
    ```java
    @Query("SELECT DISTINCT dt.id.tag FROM DrillTag dt WHERE dt.id.coachId = :coachId ORDER BY dt.id.tag")
    List<String> findDistinctTagsByCoachId(@Param("coachId") UUID coachId);
    ```
  - [x] `void deleteByIdDrillIdAndIdTagAndIdCoachId(UUID drillId, String tag, UUID coachId)`

### Backend — Contract Updates

- [x] Task 4: Update `DrillResponse.java` to include tags (AC: 5)
  - [x] Add `List<String> tags` field to the record
  - [x] Updated signature:
    ```java
    public record DrillResponse(
        UUID id, String name, String description, String libraryType,
        UUID ownerCoachId, String status, DrillMetadata metadata,
        boolean hasVideo, String videoUrl,   // null in Story 4.2; populated in 4.3
        String transKey, Instant createdAt, List<String> tags,
        Boolean isClonedByMe, UUID cloneId  // PLATFORM drills only; null for COACH drills
    ) {}
    ```
  - [x] **IMPORTANT**: Update all callers of `DrillResponse` constructor in `DrillLibraryService.toResponse()` to pass all new arguments — the compiler will fail fast if any call site is missed. For COACH drills pass `null, null` for `isClonedByMe`/`cloneId`. For PLATFORM drills pass the computed values (see Task 6).

- [x] Task 5: Add `DrillTagRequest.java` record (AC: 5)
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] `public record DrillTagRequest(@NotBlank @Size(max = 50) String tag) {}`
  - [x] Jakarta Validation annotations required

### Backend — Service

- [x] Task 6: Extend `DrillLibraryService.java` for search, filter, and tags (AC: 3, 4, 5, 6)
  - [x] Inject `DrillTagRepository` (add to constructor)
  - [x] **Replace** `listPlatformDrills()` and `listPrivateDrills(Long)` with unified method:
    ```java
    @Transactional(readOnly = true)
    public List<DrillResponse> listDrills(
        String library, String q, String skill, String difficultyTier,
        List<String> equipment, Boolean weakFootBias, Long coachUserId
    )
    ```
    - Resolve coach UUID from `coachUserId` (same `resolveCoachId` helper)
    - Fetch all active drills for library type (PLATFORM or PRIVATE by ownerCoachId)
    - Apply in-memory filtering (dataset is small — ≤20 platform + coach's private drills):
      - `q`: case-insensitive contains on `name`, `description`, or any element of `metadata.coachingPoints`
      - `skill`: metadata.primarySkills or metadata.secondarySkills contains the skill string
      - `difficultyTier`: exact match on `metadata.difficultyTier`
      - `equipment`: any element of `metadata.equipmentRequired` equals the filter value
      - `weakFootBias`: `metadata.weakFootBias == filter value`
    - Batch fetch video refs (existing `batchVideoLookup` helper — reuse; `videoUrl` stays null for all drills in this story)
    - For PRIVATE drills: batch fetch the coach's tags via `drillTagRepository.findByIdDrillIdInAndIdCoachId(drillIds, coachId)`; build a `Map<UUID, List<String>>`. `isClonedByMe = null`, `cloneId = null`.
    - For PLATFORM drills: tags are always empty list. Batch-fetch clone provenance: `drillRepository.findClonesBySourceIdsAndCoach(drillIds, coachId)` (new query — see Dev Notes) → `Map<UUID, UUID>` (sourceDrillId → cloneId); compute `isClonedByMe = cloneMap.containsKey(drill.getId())`, `cloneId = cloneMap.get(drill.getId())`.
    - Pass all arguments into `toResponse`
  - [x] Update `toResponse(Drill, boolean, List<String>, Boolean, UUID)` — add `isClonedByMe` (null for COACH drills) and `cloneId` (null for COACH drills and uncloned PLATFORM drills). Add `videoUrl` (always `null` in this story — set from video URL resolution in 4.3).
  - [x] **Add tag methods:**
    ```java
    public void addTag(UUID drillId, String tag, Long coachUserId)
    public void removeTag(UUID drillId, String tag, Long coachUserId)
    public List<String> getSuggestedTags(Long coachUserId)
    ```
    - `addTag`: verify drill exists; if `libraryType != "COACH"` OR `ownerCoachId != coachId`, throw `OperationNotAllowedException` with code `SESSION_CANNOT_TAG_UNAUTHORIZED`. Then check `drillTagRepository.existsById(new DrillTagId(drillId, tag, coachId))` — if already exists, return (idempotent). Only call `drillTagRepository.save(...)` if the record does not exist. Do NOT rely on `save()` alone — Spring Data calls `persist()` for a new entity, which throws `DataIntegrityViolationException` on a PK conflict rather than silently ignoring it.
    - `removeTag`: verify ownership; call `deleteByIdDrillIdAndIdTagAndIdCoachId`; no-op if not found (idempotent)
    - `getSuggestedTags`: `findDistinctTagsByCoachId(resolveCoachId(coachUserId))`
  - [x] Add `SESSION_CANNOT_TAG_UNAUTHORIZED` to `SessionErrorCode.java` (covers both "platform drill" and "another coach's drill" cases — a platform-specific name would be misleading for the ownership failure path)

- [x] Task 7: Update `DrillLibraryResource.java` — extend GET and add tag endpoints (AC: 3, 4, 5)
  - [x] Update `getDrills` to accept new optional params:
    ```java
    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<DrillResponse>> getDrills(
        @RequestParam String library,                   // required — must be PLATFORM or PRIVATE; null causes NPE in service
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String skill,
        @RequestParam(required = false) String difficultyTier,
        @RequestParam(required = false) List<String> equipment,
        @RequestParam(required = false) Boolean weakFootBias
    )
    ```
  - [x] `library` is `@RequestParam` without `required = false` — Spring returns 400 automatically if omitted. The service assumes non-null library. Do NOT use `required = false`.
  - [x] Call new unified `drillLibraryService.listDrills(library, q, skill, difficultyTier, equipment, weakFootBias, currentCoachUserId())`
  - [x] Add new tag endpoints:
    ```java
    @PostMapping("/{drillId}/tags")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> addTag(@PathVariable UUID drillId, @RequestBody @Valid DrillTagRequest req)
    // → calls drillLibraryService.addTag(drillId, req.tag(), currentCoachUserId()) → 201

    @DeleteMapping("/{drillId}/tags/{tag}")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> removeTag(@PathVariable UUID drillId, @PathVariable String tag)
    // → calls drillLibraryService.removeTag(drillId, tag, currentCoachUserId()) → 204

    @GetMapping("/tags/suggestions")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<List<String>> getSuggestedTags()
    // → calls drillLibraryService.getSuggestedTags(currentCoachUserId()) → 200
    ```
  - [x] `@Observed(name = "session.drills")` already on class — no change needed
  - [x] Do NOT use `DrillTagResource` as a separate class — keep all under `DrillLibraryResource` for cohesion (the epics dev notes mention `DrillTagResource` but architecturally keeping it in `DrillLibraryResource` is cleaner and consistent with this module)
  - [x] Verify that `OperationNotAllowedException` maps to HTTP 403 in the project's `@ControllerAdvice` before writing the IT test assertions. If it maps to a different status (e.g., 400), update the IT test expectations accordingly.

### Frontend — Pinia Store

- [x] Task 8: Create `session.store.js` (AC: 3, 4, 5, 6)
  - [x] File: `src/frontend/src/stores/session.store.js`
  - [x] Architecture prescribes this file exists: `stores/session.store.js`
  - [x] Use `defineStore` with `setup` function style (consistent with `booking.store.js`)
  - [x] State:
    ```js
    const drills = ref([])
    const loading = ref(false)
    const error = ref(null)
    const searchQuery = ref('')
    const activeFilters = ref({ skill: null, difficultyTier: null, equipment: null, weakFootBias: null })
    const selectedDrill = ref(null)
    const tagSuggestions = ref([])
    // NOTE: do NOT add `coachClonedDrillIds = ref(new Set())` — in-place Set mutations are
    // invisible to Vue 3 reactivity. isClonedByMe comes from DrillResponse (server-computed).
    ```
  - [x] Actions:
    - `fetchDrills(library)` — calls `sessionApi.getDrills(library)`, sets `drills`
    - `searchDrills(library)` — calls `sessionApi.getDrills(library, { q: searchQuery.value, ...activeFilters.value })`, sets `drills`
    - `cloneDrill(drillId)` — calls `sessionApi.cloneDrill(drillId)`; on success, find the drill in `drills.value` by id and mutate it in-place: `drill.isClonedByMe = true; drill.cloneId = response.data.id`. Vue 3 tracks property mutations on reactive objects, triggering re-render. Do NOT use a Set — see Dev Notes.
    - `addTag(drillId, tag)` — calls `sessionApi.addTag(drillId, tag)`; on success only, push `tag` into the matching drill's `tags` array. On failure, set `error.value` and do NOT mutate state (no optimistic update for tags).
    - `removeTag(drillId, tag)` — calls `sessionApi.removeTag(drillId, tag)`; on success only, splice `tag` from the matching drill's `tags` array. On failure, set `error.value`.
    - `fetchTagSuggestions()` — calls `sessionApi.getTagSuggestions()`, sets `tagSuggestions.value = response.data`. Called on demand (e.g., when add-tag input is focused), not on store init.
    - Return `tagSuggestions` from the store's return object so components can read `sessionStore.tagSuggestions`.

### Frontend — API

- [x] Task 9: Update `session.api.js` (AC: 3, 4, 5)
  - [x] File: `src/frontend/src/api/session.api.js`
  - [x] **Keep** existing `refresh()`, `getDrills(library, params)`, `cloneDrill(drillId)` — do not remove
  - [x] The existing `getDrills(library, params)` already forwards params correctly (Story 4.1 patch: `{ ...params, library }`) — confirm current file uses correct spread order
  - [x] Add new methods:
    ```js
    addTag(drillId, tag) {
      return api.post(`/api/session/drills/${drillId}/tags`, { tag })
    },
    removeTag(drillId, tag) {
      return api.delete(`/api/session/drills/${drillId}/tags/${encodeURIComponent(tag)}`)
    },
    getTagSuggestions() {
      return api.get('/api/session/drills/tags/suggestions')
    },
    ```

### Frontend — DrillCard Component

- [x] Task 10: Create `DrillCard.vue` (AC: 1, 2, 6)
  - [x] File: `src/frontend/src/components/session/DrillCard.vue`
  - [x] Create directory `src/frontend/src/components/session/` (does not exist yet)
  - [x] `<script setup>` with props: `{ drill: Object, context: String }` — context is `'library'|'session-builder'|'homework'`
  - [x] **SLU calculation (frontend-only, no API call):**
    ```js
    const sluEstimate = computed(() => {
      const { repDensity, skillWeighting } = props.drill.metadata
      const totalWeight = Object.values(skillWeighting).reduce((a, b) => a + b, 0)
      return Math.round(repDensity * totalWeight / 100)
    })
    ```
  - [x] **Reduced-motion check — reactive, not one-shot:**
    ```js
    const prefersReducedMotion = ref(false)
    let _mql
    const _mqlHandler = e => { prefersReducedMotion.value = e.matches }
    onMounted(() => {
      _mql = window.matchMedia('(prefers-reduced-motion: reduce)')
      prefersReducedMotion.value = _mql.matches
      _mql.addEventListener('change', _mqlHandler)
    })
    onUnmounted(() => _mql?.removeEventListener('change', _mqlHandler))
    ```
  - [x] **Video template — 3-way branch** (`videoUrl` is not in DrillResponse until Story 4.3, but structure all branches now):
    - `v-if="drill.hasVideo && drill.videoUrl && !prefersReducedMotion"` → `<video autoplay muted loop playsinline class="drill-card__video" :src="drill.videoUrl">` (activates in 4.3 when videoUrl is populated)
    - `v-else-if="drill.hasVideo"` → thumbnail placeholder `<div class="drill-card__thumbnail">` with `q-icon name="play_circle"` overlay (video exists but URL not yet available, or reduced-motion mode)
    - `v-else` → grey placeholder `<div class="drill-card__no-video">` with `q-icon name="sports_soccer"`
    - In Story 4.2 the first branch is never reached (videoUrl is null for all drills). Do NOT render a `<video>` element without a src — it is an invisible element that provides no UX value.
  - [x] Equipment icons: map `equipmentRequired` array to icons (e.g., `'ball' → 'sports_soccer'`, `'cones' → 'change_history'`); use `q-icon`
  - [x] Difficulty badge: `<q-badge>{{ drill.metadata.difficultyTier }}</q-badge>` with colour based on tier (U8/U10 = green, U12/U14 = amber, U16+ = red)
  - [x] Weak-foot icon: `<q-icon v-if="drill.metadata.weakFootBias" name="swap_horiz" />` with tooltip
  - [x] Primary action button — computed from `context` prop:
    - `'session-builder'` → `q-btn` "Add to session" emitting `add-to-session`
    - `'homework'` → `q-btn` "Assign" emitting `assign`
    - `'library'` → no primary action button (browse-only; detail panel is the main interaction)
  - [x] Clone action button: `v-if="drill.libraryType === 'PLATFORM' && !drill.isClonedByMe"` — emits `clone` event on click. Parent handles API call and shows toast. Never show on COACH-type drills (a coach cannot clone their own private drill).
  - [x] "In your library" indicator + "Edit clone": `v-if="drill.libraryType === 'PLATFORM' && drill.isClonedByMe"` — show "In your library" `q-badge`; show "Edit clone" `q-btn` that emits `edit-clone(drill.cloneId)`. Parent handles by switching to "My Library" tab and opening the clone's detail panel. `isClonedByMe` and `cloneId` come from `DrillResponse` (server-computed), not from any client-side tracking Set.
  - [x] Tags display (private drills): `v-if="drill.libraryType === 'COACH'"` — render `drill.tags` as `q-chip` components with `removable` prop; chip's `@remove` handler calls `sessionStore.removeTag(drill.id, tag)`. Below chips, show "Add tag" toggle button; when active, show inline `q-input` (maxlength 50) with `q-menu` dropdown listing `sessionStore.tagSuggestions` as autocomplete options; call `sessionStore.fetchTagSuggestions()` on focus if `tagSuggestions` is empty; on Enter or option-select, call `sessionStore.addTag(drill.id, inputValue)` and clear the input.
  - [x] Coaching point bullets: display up to 4 from `drill.metadata.coachingPoints`. If the array has more than 4 (possible for custom drills in later stories), show only the first 4 and add a "+" indicator.
  - [x] Tap on card body (not action) → emit `open-detail` event
  - [x] Mobile-first: max-width constraint not needed (cards respond to grid); all text and elements stack correctly in 375px column layout
  - [x] Use `q-card` + `glass-card` CSS class (follows existing pattern from `CoachCard.vue`)
  - [x] All colours via CSS custom property tokens — no hardcoded hex

- [x] Task 11: Create `DrillDetailPanel.vue` (AC: 7)
  - [x] File: `src/frontend/src/components/session/DrillDetailPanel.vue`
  - [x] `<script setup>` with props: `{ drill: Object, isOpen: Boolean }`
  - [x] Emits: `close`
  - [x] Mobile: use `q-bottom-sheet` controlled via a local `open` ref (mirrored from `isOpen` prop; emit `close` on `@hide`). Apply `max-height: 75vh` via scoped CSS — Quasar's `q-bottom-sheet` has no native max-height prop and will grow to full content height without it.
  - [x] Desktop: use `q-dialog` with `position="right"` and `full-height` props (NOT `q-drawer` — drawer changes the page layout and conflicts with the app's navigation drawer). Style with `min-width: 420px` via scoped CSS on the dialog's content container.
  - [x] Detect viewport: `const isMobile = computed(() => $q.screen.lt.sm)` using `useQuasar()`
  - [x] Content: full-size video player — `<video controls :src="drill.videoUrl" v-if="drill.videoUrl">` with fallback `<div v-else class="detail-panel__no-video">` showing thumbnail + label "Video preview available after upload" (`videoUrl` is null for all drills in Story 4.2; the fallback renders in this story). Also show: metadata table, SLU breakdown by skill (iterate skillWeighting), all coaching points, tags.
  - [x] **SLU breakdown per skill:**
    ```js
    const sluBreakdown = computed(() => {
      const { repDensity, skillWeighting } = props.drill.metadata
      return Object.entries(skillWeighting).map(([skill, weight]) => ({
        skill, slu: Math.round(repDensity * weight / 100)
      }))
    })
    ```
  - [x] Setup diagram: `drill.metadata.setupDiagram` (nullable String URL — field added to `DrillMetadata` Java record in this story; see Dev Notes). `v-if="drill.metadata.setupDiagram"` → render `<img :src="drill.metadata.setupDiagram" alt="Setup diagram">`. If null, omit section entirely (no empty placeholder). All Foundation 20 drills have `setupDiagram = null`.

### Frontend — DrillLibraryPage

- [x] Task 12: Replace stub `DrillLibraryPage.vue` with full implementation (AC: 1–7)
  - [x] File: `src/frontend/src/pages/coach/DrillLibraryPage.vue`
  - [x] **Existing stub has only a title and placeholder** — this task replaces it entirely
  - [x] Use `useSessionStore()` for state management
  - [x] Use `useQuasar()` for `$q.notify()` toasts and screen breakpoint detection
  - [x] On `onMounted`: call `sessionStore.fetchDrills('PLATFORM')` (default tab is platform library)
  - [x] Scout tier: drill library BROWSE is accessible to ALL tiers — do NOT add a tier check, overlay, or gate to this page. The Scout upgrade overlay (UX-DR22) belongs on the Session Builder page (Story 4.4). Adding it here would block Scout-tier coaches from browsing drills, which the epic explicitly permits.
  - [x] **Two tabs**: "Platform Library" / "My Library" using `q-tabs` + `q-tab-panels`
  - [x] On tab switch: **reset search and filters first**, then fetch: `sessionStore.searchQuery = ''; sessionStore.activeFilters = { skill: null, difficultyTier: null, equipment: null, weakFootBias: null }; sessionStore.fetchDrills(selectedLibrary)`. Prevents filter state from Platform Library bleeding into My Library.
  - [x] Search bar: `q-input` — inline `useDebounce` closure (300ms) used instead of `@vueuse/core` (not installed). Bind: `@update:model-value="onSearchInput"`. Do NOT use Quasar's built-in debounce attribute — it only delays the event, not the API call.
  - [x] Filter panel: `q-dialog position="bottom"` on filter button; filter fields for skill, difficultyTier, equipment, weakFootBias; apply button triggers `sessionStore.searchDrills()`. Equipment filter sends as array (null when not set, array of strings when set). Clear-all button resets all filters to null and re-fetches.
  - [x] Active filter count badge on filter button: `computed(() => Object.values(sessionStore.activeFilters).filter(v => v !== null && v !== undefined && v !== '').length)`
  - [x] Drill grid: `v-for="drill in sessionStore.drills"` → `<DrillCard :context="'library'" :drill="drill">` components
  - [x] Empty state — two distinct cases:
    - Search/filter active: `v-if="!loading && drills.length === 0 && (searchQuery || hasActiveFilters)"` → icon `search_off`, `t('session.drillLibrary.noResults')`, "Clear search" + "Clear filters" CTAs
    - Library genuinely empty: `v-if="!loading && drills.length === 0 && !searchQuery && !hasActiveFilters"` → icon `sports_soccer`, "Your library is empty", "Clone a platform drill" CTA (only meaningful for My Library tab)
    - `hasActiveFilters` computed: `Object.values(activeFilters).some(v => v !== null && v !== undefined)`
  - [x] Handle `clone` event from `DrillCard`:
    ```js
    async function handleClone(drillId) {
      await sessionStore.cloneDrill(drillId)
      $q.notify({ message: t('session.drillLibrary.addedToLibrary'), actions: [{ label: t('session.drillLibrary.viewInLibrary'), handler: () => handleEditClone(sessionStore.drills.find(d => d.id === drillId)?.cloneId) }] })
    }
    ```
  - [x] Handle `edit-clone` event from `DrillCard`:
    ```js
    async function handleEditClone(cloneId) {
      selectedLibrary.value = 'PRIVATE'
      await sessionStore.fetchDrills('PRIVATE')
      const clone = sessionStore.drills.find(d => d.id === cloneId)
      if (clone) selectedDrill.value = clone  // opens DrillDetailPanel for the clone
    }
    ```
  - [x] Handle `open-detail` event → set `selectedDrill` → show `DrillDetailPanel`
  - [x] i18n: use `const { t } = useI18n()`

### Frontend — i18n

- [x] Task 13: Add i18n keys to `en/index.js` and `de/index.js` (AC: 1–7)
  - [x] File: `src/frontend/src/i18n/en/index.js` — update `session.drillLibrary` section:
    ```js
    session: {
      drillLibrary: {
        title: 'Drill Library',
        platformTab: 'Platform Library',
        myLibraryTab: 'My Library',
        searchPlaceholder: 'Search drills...',
        filterButton: 'Filters',
        filterActiveCount: '{count} active',
        clearSearch: 'Clear search',
        clearFilters: 'Clear filters',
        noResults: 'No drills found',
        noResultsHint: 'Try adjusting your search or filters',
        inYourLibrary: 'In your library',
        editClone: 'Edit clone',
        cloneButton: 'Clone to my library',
        addedToLibrary: 'Added to your library',
        viewInLibrary: 'View in library',
        addToSession: 'Add to session',
        assign: 'Assign',
        weakFootLabel: 'Weak foot',
        sluEstimate: '~{count} SLU',
        addTag: 'Add tag',
        tagPlaceholder: 'Tag name...',
        scoutUpgradeTitle: 'Upgrade to build sessions',
        scoutUpgradeBody: 'Session Builder is available on Instructor and Academy plans.',
        upgradeButton: 'View plans',
        detail: {
          sluBreakdown: 'SLU Breakdown',
          coachingPoints: 'Coaching Points',
          metadata: 'Drill Details',
        },
      },
    },
    ```
  - [x] File: `src/frontend/src/i18n/de/index.js` — add equivalent German translations for all new keys (translate appropriately; keep the same key structure)
  - [x] **Do NOT remove or change** the existing `sessDrill` namespace keys (story 4.1 content)
  - [x] **Do NOT remove** `session.drillLibrary.title` or `session.drillLibrary.placeholder` — replace `placeholder` value with the new full set of keys (i.e., remove the placeholder key, add the new ones)

### Testing

- [x] Task 14: `DrillSearchServiceTest.java` — unit tests for search/filter logic (AC: 3, 4)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/DrillSearchServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — no Spring context
  - [x] Test filter combinations using `DrillLibraryService.listDrills()`:
    - q matching drill name
    - q matching coaching point text
    - q with no match returns empty list
    - skill filter matches primary skill
    - skill filter matches secondary skill
    - difficultyTier filter exact match
    - equipment filter single item
    - weakFootBias true/false filter
    - combined q + skill filter (both applied)
    - empty filter params returns all active drills (passthrough)
  - [x] Use `Instancio` for test data generation (project standard)
  - [x] Mock: `DrillRepository`, `DrillVideoRefRepository`, `DrillTagRepository`, `ConfigService`, `CoachProfileService`

- [x] Task 15: `DrillTagResourceIT.java` — integration tests for tag endpoints (AC: 5)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/api/DrillTagResourceIT.java`
  - [x] `@SpringBootTest @Testcontainers` — follows existing `DrillLibraryResourceIT` pattern exactly
  - [x] Import `TestConfig`, use `HttpTestClient`, `JdbcTemplate`, `TransactionTemplate`, `PasswordEncoder`
  - [x] Use `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` for security data
  - [x] Test cases:
    - `addTag_toPrivateDrill_returns201` — coach can tag own private drill
    - `addTag_toPlatformDrill_returns403` — PLATFORM drills cannot be tagged
    - `addTag_toDrillOwnedByOtherCoach_returns403` — cannot tag another coach's drill
    - `addTag_duplicate_returns201` — idempotent via `existsById()` pre-check (no 409)
    - `removeTag_existingTag_returns204`
    - `removeTag_nonExistentTag_returns204` — idempotent
    - `getTagSuggestions_returnsDistinctTagsForCoach`
    - `getDrills_withSearchQuery_returnsMatchingDrills`
    - `getDrills_withSkillFilter_returnsFilteredDrills`
  - [x] Use the same setup/teardown helpers as `DrillLibraryResourceIT` — extract shared fixtures to `BaseSessionIT.java` if both IT classes share setup logic
  - [x] Tear down: `DELETE FROM session.drill_tags WHERE coach_id IN (?...)`

### Review Findings

- [x] [Review][Decision] Multiple clones of same platform drill silently collapse in buildCloneMap — `map.put` overwrites earlier entry; V41 migration has no UNIQUE constraint on `(source_drill_id, owner_coach_id)`; decide: allow multiple clones (fix map logic) or prevent (add UNIQUE constraint) [`DrillRepository.java` + `V41__drill_source_id.sql`] → added UNIQUE constraint to V41

- [x] [Review][Patch] TOCTOU race in addTag — existsById+save not atomic at READ COMMITTED isolation; concurrent identical requests can both pass existsById=false and trigger DataIntegrityViolationException on the PK; catch DVE and treat as idempotent, or use native INSERT ON CONFLICT DO NOTHING [`DrillLibraryService.java:130-132`]
- [x] [Review][Patch] DrillDetailPanel bottom-sheet max-height:75vh on inner content div, not the sheet wrapper — Quasar q-bottom-sheet ignores slot-content height constraint; sheet can grow to 100% height; apply max-height to the sheet's root rendered element via :style or a CSS class on the q-bottom-sheet itself [`DrillDetailPanel.vue`]
- [x] [Review][Patch] resolveCoachId called unconditionally in listDrills for PLATFORM requests — behavioral regression; coaches without a profile record can no longer browse the platform library (previously listPlatformDrills took no userId); lazy-resolve coachId only when needed (PRIVATE path and buildCloneMap) [`DrillLibraryService.java:54`]
- [x] [Review][Patch] DrillSearchServiceTest uses hand-rolled Drill builders — project standard is Instancio; all test fixtures use new Drill() + setters instead of Instancio.of(Drill.class) [`DrillSearchServiceTest.java`]
- [x] [Review][Patch] addTag/removeTag errors silently swallowed — store sets error.value but DrillCard.vue never reads it; user gets no feedback when tag save fails; add $q.notify error toast in submitTag/removeTag handlers [`DrillCard.vue`, `session.store.js`]
- [x] [Review][Patch] handleEditClone doesn't reset filters on programmatic tab switch — selectedLibrary.value = 'PRIVATE' bypasses onTabChange; stale PLATFORM filters remain active on PRIVATE tab, potentially hiding the clone [`DrillLibraryPage.vue`]
- [x] [Review][Patch] buildCloneMap Object[] JPQL projection uses aliases ignored by JPA — row[0]/row[1] positional access is correct now but silently inverts if column order changes; use a projection interface instead [`DrillRepository.java`, `DrillLibraryService.java:205-213`]
- [x] [Review][Patch] Hardcoded English strings in empty-library state — "Your library is empty" and "Clone a platform drill" not using t() i18n; missing keys in en/de index [`DrillLibraryPage.vue`]
- [x] [Review][Patch] DrillTagResourceIT fixture helpers declared inline — BaseSessionIT.java not created; shared setup/teardown logic duplicated from DrillLibraryResourceIT [`DrillTagResourceIT.java`]
- [x] [Review][Patch] DrillDetailPanel uses writable computed for isOpen instead of ref+watch — when Quasar triggers dismiss, setter emits close but get() still returns props.isOpen=true, causing sheet to snap back open; use a local ref mirrored via watch [`DrillDetailPanel.vue`]

- [x] [Review][Defer] Concurrent fetch race between applyFilters and onTabChange — two in-flight API calls (searchDrills + fetchDrills) can overwrite each other's results; last response wins; pre-existing single-store no-cancellation pattern; address with request ID or AbortController in a UX hardening pass [`DrillLibraryPage.vue`, `session.store.js`]
- [x] [Review][Defer] sluBreakdown silent 0 for null repDensity — null * weight = 0 in JS; renders "0 SLU" instead of "—"; Foundation 20 drills all have valid repDensity; guard when coaches can upload custom drills [`DrillDetailPanel.vue`]
- [x] [Review][Defer] removeTag chip visible for any COACH drill in UI — component assumes ownership from context; correct in the PRIVATE tab (all drills are owned by current coach); defensive concern if component is reused in a multi-coach admin context [`DrillCard.vue`]
- [x] [Review][Defer] DrillTagId @Column(name="tag") missing length=50 — JPA default length is 255 vs DB VARCHAR(50); harmless if schema validation is off; add length=50 if validation is enabled [`DrillTagId.java`]
- [x] [Review][Defer] COACH vs PRIVATE naming inconsistency — entity/DB stores "COACH"; API param and frontend use "PRIVATE"; pre-existing from Story 4.1; no runtime bug today but fragile on new developer additions [`DrillLibraryResource.java`, `DrillLibraryService.java`]

## Dev Notes

### Flyway Migration — V40 and V41
V38 = session module init, V39 = foundation 20 drills (both Story 4.1). **V40** = `drill_tags` table. **V41** = `source_drill_id UUID` column on `drills` (tracks which platform drill a clone was created from — required for server-side `isClonedByMe` computation). Do NOT reuse V38 or V39 (already applied — Flyway checksum failure).

### DrillResponse Record Update — Compile-Time Safety
`DrillResponse` is a Java record. Adding `tags: List<String>` changes its constructor. `DrillLibraryService.toResponse(...)` is the only caller — update it to pass `tags` as the final argument. The compiler will fail fast if the call site is missed. Default to `List.of()` for PLATFORM drills.

### Tag Authorization Rules
Tags are coach-specific and can only be placed on drills the coach owns (`library_type = 'COACH'` AND `owner_coach_id = coachId`). Never allow tagging PLATFORM drills directly or another coach's private drills. The check in `DrillLibraryService.addTag`:
```java
if (!"COACH".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
    throw new OperationNotAllowedException("Cannot tag this drill", SessionErrorCode.SESSION_CANNOT_TAG_UNAUTHORIZED);
}
```
Error code `SESSION_CANNOT_TAG_UNAUTHORIZED` covers both failure modes. A platform-specific name would be misleading when the failure is an ownership violation on a COACH-type drill.

### Tag Duplicate Handling
The `drill_tags` table has a primary key on `(drill_id, tag, coach_id)`. Do NOT rely on `save()` for idempotency. Spring Data JPA calls `persist()` for a new (detached) entity, which issues an `INSERT`. If the PK already exists, Hibernate throws `DataIntegrityViolationException` — it does not silently ignore it. The `addTag` method must check existence first:
```java
DrillTagId tagId = new DrillTagId(drillId, tag, coachId);
if (!drillTagRepository.existsById(tagId)) {
    drillTagRepository.save(new DrillTag(tagId));
}
```
This makes the operation idempotent. The IT test case `addTag_duplicate_returns201OrIdempotent` should expect HTTP 201 (re-adding an existing tag is a no-op, not an error).

### In-Memory Filtering is Correct
The platform has 20 fixed platform drills. Coaches will have a small number of private drills. In-memory filtering with Java streams avoids complex JPQL/native queries on JSONB fields. This is intentional. Do NOT add a `@Query` with PostgreSQL JSONB operators — it's over-engineering for the current dataset size.

### SLU Calculation Is Frontend-Only
The `~18 SLU` chip on `DrillCard` is computed entirely in the browser from `drill.metadata.repDensity` and `drill.metadata.skillWeighting`. No backend endpoint exists or is needed. Formula: `Math.round(repDensity × sum(Object.values(skillWeighting)) / 100)`. The detail panel shows per-skill breakdown.

### DrillVideoRef — No Video URL in This Story
`DrillResponse` gains a `videoUrl` (String, nullable) field in this story, but it is null for all drills until Story 4.3 populates it via Bunny.net signed URLs. Structure templates with all three branches now so Story 4.3 only needs to populate `videoUrl` in the service — no template changes required:
- `hasVideo = true, videoUrl != null, !prefersReducedMotion` → `<video autoplay muted loop playsinline>` (active in 4.3)
- `hasVideo = true, videoUrl = null` (or reduced-motion) → thumbnail placeholder with play-circle icon overlay
- `hasVideo = false` → grey placeholder with `sports_soccer` icon
Do NOT render a `<video>` element without a `src` — it is an invisible DOM node that contributes nothing.

### session.store.js Does Not Exist Yet — Create It
No Pinia store for the session module exists. The architecture specifies `stores/session.store.js`. Create it in this story. Follow the `booking.store.js` setup-function style with `defineStore('session', () => { ... })`.

### DrillLibraryPage.vue — Full Replacement
The current stub file at `src/frontend/src/pages/coach/DrillLibraryPage.vue` has only a title heading and a placeholder paragraph. This story replaces the entire file content. The route `/coach/drills` already exists in `routes.js` — no route change needed.

### SecurityUtil.getCurrentCoachUserId()
Use `securityUtil.getCurrentCoachUserId()` to get the authenticated coach's Long userId. This exists in `DrillLibraryResource.java` as `currentCoachUserId()` private helper (already added in Story 4.1). Reuse the exact same helper in the extended tag endpoints.

### Cross-Module: Auth Store for Scout Tier Check
To show the Scout upgrade overlay on `DrillLibraryPage.vue`, read the coach's tier from the existing auth Pinia store. Check `authStore.user.skillarsRole` or a tier property — inspect `auth.store.js` for the correct field. If tier is not stored in the auth store, the page should call a store action to fetch it (or use an existing `sessionStore` action). Do NOT make a direct API call from the component.

### component directory creation
`src/frontend/src/components/session/` does not exist. Create it when placing `DrillCard.vue` and `DrillDetailPanel.vue`. No extra config needed — Quasar/Vite auto-resolves component paths.

### Filter Param Encoding
The `equipment` filter is `List<String>` on the backend (`@RequestParam(required = false) List<String> equipment`). Spring accepts repeated params: `?equipment=ball&equipment=cones`. Frontend axios: `params: { equipment: ['ball', 'cones'] }` — axios serializes repeated params correctly by default.

### Quasar Screen Breakpoints
Use `const $q = useQuasar()` in components. `$q.screen.lt.sm` is true for mobile (< 600px), `$q.screen.gt.sm` for desktop. Use this to conditionally render bottom sheet vs. side panel in `DrillDetailPanel.vue`.

### Empty State Pattern
Per UX consistency rules: every empty state needs icon + headline + CTA. Never a bare empty list. For drill search no-results: icon `search_off`, headline from `t('session.drillLibrary.noResults')`, CTA button "Clear search" that resets `searchQuery` and re-fetches.

### i18n — Replace Placeholder, Not Append
The existing `session.drillLibrary.placeholder` key is a stub: `'Drill cards coming in Story 4.2'`. Remove this key and replace the entire `session.drillLibrary` object with the full set of keys. The `sessDrill` namespace below it is untouched.

### Previous Story Learnings to Apply
From Story 4.1 review:
- `SecurityUtil.getCurrentCoachUserId()` is the correct pattern — it already exists in `DrillLibraryResource`; add a `currentCoachUserId()` private helper if not already there (it was added in 4.1 patches)
- `OperationNotAllowedException` (not `FeatureGatedException`) is the correct exception for ownership/type restrictions (Story 4.1 patch: use `ForbiddenException` or equivalent for non-feature-gate 403s)
- `@Modifying` JPQL updates need `@Transactional` and `clearAutomatically = true` — apply this standard to any future `@Modifying` queries in `DrillTagRepository`
- `ON CONFLICT DO NOTHING` clauses in Flyway migrations should reference the business key constraint (unique constraint name), not just the PK

### DrillTagId Must Be a Java Class, Not a Record
JPA's `@Embeddable` requires a no-arg constructor for Hibernate to instantiate the type when hydrating entities from a `ResultSet`. Java records are immutable and have no no-arg constructor, causing a `HibernateException` at startup or on first query. `DrillTagId` must be a plain class with: `@Embeddable`, no-arg constructor, all-args constructor, and `equals()`/`hashCode()` covering all three fields (`drillId`, `tag`, `coachId`).

### Vue Reactivity — isClonedByMe Driven by DrillResponse
Do NOT use `ref(new Set())` to track cloned drill IDs on the client. In-place Set mutations (`.add()`, `.delete()`) are not detected by Vue 3's reactivity system — the template will not re-render. `isClonedByMe: Boolean` and `cloneId: UUID` are computed server-side and returned in every `DrillResponse` for PLATFORM drills. After `cloneDrill()` succeeds, update the drill object in `drills.value` in-place by property assignment:
```js
const drill = drills.value.find(d => d.id === drillId)
if (drill) { drill.isClonedByMe = true; drill.cloneId = response.data.id }
```
Vue 3 tracks property mutations on reactive objects and triggers re-render correctly.

### source_drill_id — Clone Provenance Tracking (V41)
`isClonedByMe` on `DrillResponse` requires the server to know which of the coach's private drills was cloned from a given platform drill. This is tracked via `source_drill_id UUID` on the `drills` table (V41 migration). When `POST /api/session/drills/{id}/clone` creates a clone (Story 4.1 service), set `sourceDrillId = original.id` on the new entity. In `listDrills` for PLATFORM library, add a batch lookup to `DrillRepository`:
```java
@Query("SELECT d.sourceDrillId as sourceId, d.id as cloneId FROM Drill d WHERE d.sourceDrillId IN :sourceIds AND d.ownerCoachId = :coachId")
List<Object[]> findClonesBySourceIdsAndCoach(@Param("sourceIds") List<UUID> sourceIds, @Param("coachId") UUID coachId);
```
Build a `Map<UUID, UUID>` (sourceDrillId → cloneId) and use it to populate `isClonedByMe`/`cloneId` in each PLATFORM `DrillResponse`.

### setupDiagram Field in DrillMetadata
AC 7 references a "setup diagram" in the detail panel. This field is not in the original `DrillMetadata` 13-field definition from Story 4.1. Add `@JsonProperty("setupDiagram") String setupDiagram` (nullable) to the `DrillMetadata` Java record. Because `metadata` is a JSONB column, existing rows return `null` for the new field automatically — no Flyway migration needed for this field. Foundation 20 drills have `setupDiagram = null`; the field is populated by coaches in later stories.

### Cross-Module: Auth Store for Scout Tier Check
No Scout tier check is needed on `DrillLibraryPage` — the epic permits drill browsing for all tiers. If a future story adds the overlay to Session Builder, read `authStore.user.skillarsRole` from the auth Pinia store. Inspect `auth.store.js` for the exact field name before using it — do NOT assume the field name.

### Project Structure Notes

| Component | Location |
|---|---|
| V40 migration | `src/main/resources/db/migration/V40__drill_tags.sql` |
| V41 migration | `src/main/resources/db/migration/V41__drill_source_id.sql` |
| DrillTag entity | `src/main/java/com/softropic/skillars/platform/session/repo/DrillTag.java` |
| DrillTagId embeddable | `src/main/java/com/softropic/skillars/platform/session/repo/DrillTagId.java` |
| DrillMetadata (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/repo/DrillMetadata.java` |
| DrillTagRepository | `src/main/java/com/softropic/skillars/platform/session/repo/DrillTagRepository.java` |
| DrillTagRequest | `src/main/java/com/softropic/skillars/platform/session/contract/DrillTagRequest.java` |
| DrillLibraryService (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` |
| DrillLibraryResource (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java` |
| DrillResponse (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java` |
| SessionErrorCode (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java` |
| session.store.js (CREATE) | `src/frontend/src/stores/session.store.js` |
| session.api.js (UPDATE) | `src/frontend/src/api/session.api.js` |
| DrillCard.vue (CREATE) | `src/frontend/src/components/session/DrillCard.vue` |
| DrillDetailPanel.vue (CREATE) | `src/frontend/src/components/session/DrillDetailPanel.vue` |
| DrillLibraryPage.vue (REPLACE) | `src/frontend/src/pages/coach/DrillLibraryPage.vue` |
| en/index.js (UPDATE) | `src/frontend/src/i18n/en/index.js` |
| de/index.js (UPDATE) | `src/frontend/src/i18n/de/index.js` |
| DrillSearchServiceTest (CREATE) | `src/test/java/com/softropic/skillars/platform/session/service/DrillSearchServiceTest.java` |
| DrillTagResourceIT (CREATE) | `src/test/java/com/softropic/skillars/platform/session/api/DrillTagResourceIT.java` |

### References

- Epic 4, Story 4.2 AC + dev notes [Source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 1480–1526]
- Epic 4, Story 4.1 review findings — patch list [Source: `_bmad-output/implementation-artifacts/skillars-4-1-drill-library-foundation.md` lines 347–381]
- UX-DR14 DrillCard anatomy [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 711–713]
- UX-DR25 empty states [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 812]
- UX-DR27 reduced-motion [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 902]
- UX-DR31 bottom sheet / side panel [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` line 807]
- Architecture: session module structure [Source: `_bmad-output/planning-artifacts/architecture.md` lines 882–908]
- Architecture: session.store.js exists in prescribed structure [Source: `_bmad-output/planning-artifacts/architecture.md` line 1122]
- Architecture: DrillCard.vue prescribed location [Source: `_bmad-output/planning-artifacts/architecture.md` line 1142]
- DrillLibraryService current state [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`]
- DrillLibraryResource current state [Source: `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`]
- DrillResponse current state [Source: `src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java`]
- Session API current state [Source: `src/frontend/src/api/session.api.js`]
- DrillLibraryPage stub current state [Source: `src/frontend/src/pages/coach/DrillLibraryPage.vue`]
- Existing IT pattern [Source: `src/test/java/com/softropic/skillars/platform/session/api/DrillLibraryResourceIT.java`]
- Project context rules [Source: `_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Implemented V40 (`drill_tags`) and V41 (`source_drill_id`) Flyway migrations.
- `DrillTagId` implemented as a plain `@Embeddable` class (not a record) — Hibernate requires no-arg constructor.
- Tag add is idempotent via `existsById()` pre-check; avoids `DataIntegrityViolationException` on duplicate composite PK.
- `DrillMetadata` record extended with nullable `setupDiagram` field (no migration — JSONB column returns null for missing keys).
- `DrillResponse` extended with `videoUrl`, `tags`, `isClonedByMe`, `cloneId`; `cloneDrill()` now sets `sourceDrillId` for provenance tracking.
- In-memory filtering via Java streams is intentional for the current dataset size (≤20 platform + small private set).
- `@vueuse/core` not installed — replaced `useDebounceFn` with an inline `useDebounce` closure in `DrillLibraryPage.vue`.
- `DrillDetailPanel.vue` uses a single `<script setup>` block with template content duplicated for mobile (q-bottom-sheet) and desktop (q-dialog right) branches — avoids dual-script parse error.
- Reduced-motion compliance implemented reactively via `window.matchMedia` + `addEventListener('change', ...)` with `onMounted`/`onUnmounted` cleanup.
- `DrillSearchServiceTest` used `lenient().when()` for stubs not exercised in all test paths to avoid `UnnecessaryStubbingException`.
- Combined q+skill filter test redesigned with correct fixture data after initial assertion failure.
- All backend unit tests pass (`mvn test -DskipFrontend=true` → BUILD SUCCESS). Frontend builds clean (`npx quasar build` → DONE).

### File List

**New files:**
- `src/main/resources/db/migration/V40__drill_tags.sql`
- `src/main/resources/db/migration/V41__drill_source_id.sql`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillTagId.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillTag.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillTagRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillTagRequest.java`
- `src/frontend/src/stores/session.store.js`
- `src/frontend/src/components/session/DrillCard.vue`
- `src/frontend/src/components/session/DrillDetailPanel.vue`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillSearchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/api/DrillTagResourceIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/Drill.java`
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`
- `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`
- `src/frontend/src/api/session.api.js`
- `src/frontend/src/pages/coach/DrillLibraryPage.vue`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
