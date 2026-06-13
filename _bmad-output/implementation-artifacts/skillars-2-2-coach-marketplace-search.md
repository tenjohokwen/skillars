# Story skillars-2.2: Coach Marketplace & Search

Status: in-progress

## Story

As a guest or registered user,
I want to browse and filter coaches on the marketplace,
So that I can find a coach that matches my child's needs before committing to a booking.

## Acceptance Criteria

1. **AC 1: Marketplace Grid Display** — Given any visitor (authenticated or guest) navigates to the marketplace, when the page loads, then a grid of `CoachCard` components is displayed — 3 columns on desktop (≥1200px), 2 on tablet (768–1199px), 1 on mobile. Skeleton cards matching the exact dimensions of real `CoachCard` components are shown while data loads (UX-DR26). Only coaches with `coach_profiles.status = ACTIVE` are returned — DRAFT profiles are never included.

2. **AC 2: Default Sort & Re-Sort** — Given city results are displayed, when the default sort is applied, then results are sorted alphabetically by display name within the searched city. Re-sorting by price or star rating is available via a sort control without a full page reload. Geolocation-based proximity sorting is deferred to a future story.

3. **AC 3: City-First Search & Additional Filters** — Given a user enters a city in the search field and submits, then `GET /api/marketplace/coaches?city={city}&page=0&size=20` is called and only ACTIVE coaches in that city are returned. Applying additional filters (district, language, price range, age groups, skill specialization) narrows results within the city without a full page reload. The active filter state (including city) persists if the user navigates to a profile and returns via the browser back button. The page loads in a "search prompt" state — no coach cards are shown until a city is entered; this is NOT treated as an empty state (UX-DR25 empty state only triggers when a city is entered and genuinely no coaches are found).

4. **AC 4: CoachCard Display** — Given a `CoachCard` is rendered, when displayed in the grid, then it shows: profile photo (or avatar placeholder), display name, verification tier badge (Basic/Trusted/Featured), star rating + review count, city/district, top 2 specialties, per-session price, capability badges for active premium tools, `ReliabilityIndicator` component. Trust signals (verification badge, reliability score, capability badges) appear above the price line — all visible without scrolling the card (UX-DR7). The `ReliabilityIndicator` always shows a label — "No reliability issues" in green for zero strikes, "X issues (90 days)" in amber for 1–2, "Review reliability score" in red for 3+ (UX-DR8).

5. **AC 5: Guest Restrictions** — Given a guest views the marketplace, when they attempt to contact or book a coach, then they are redirected to the registration/login page with a return URL to the coach profile. The browse and view experience is fully available without registration (FR-MKT-005).

6. **AC 6: Empty State** — Given the marketplace returns no results for the applied filters, when the empty state renders, then an icon, headline ("No coaches found"), and a "Clear filters" CTA are shown — never a bare empty list (UX-DR25).

## Tasks / Subtasks

- [x] Task 1: Flyway V27 — Marketplace search support tables and indexes (AC: 1, 2, 4)
  - [x] Create `src/main/resources/db/migration/V27__marketplace_search_support.sql`:
    ```sql
    -- Add verification tier to coach profiles (admin-grantable, defaults to BASIC)
    ALTER TABLE marketplace.coach_profiles
      ADD COLUMN verification_tier VARCHAR(20) NOT NULL DEFAULT 'BASIC',
      ADD CONSTRAINT chk_verification_tier
          CHECK (verification_tier IN ('BASIC','TRUSTED','FEATURED'));

    -- Reliability strikes table (stub schema; Epic 7 populates data)
    CREATE TABLE marketplace.coach_reliability_strikes (
        id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id   UUID        NOT NULL REFERENCES marketplace.coach_profiles(id),
        reason     VARCHAR(255),
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_reliability_strikes_coach_date
        ON marketplace.coach_reliability_strikes(coach_id, created_at);

    -- Search performance indexes
    CREATE INDEX idx_coach_profiles_city      ON marketplace.coach_profiles(city);
    CREATE INDEX idx_coach_profiles_district  ON marketplace.coach_profiles(district);
    CREATE INDEX idx_coach_profiles_vtier     ON marketplace.coach_profiles(verification_tier);
    CREATE INDEX idx_coach_pricing_price      ON marketplace.coach_pricing(per_session_price);
    CREATE INDEX idx_coach_specialties_skill  ON marketplace.coach_specialties(skill);
    CREATE INDEX idx_coach_age_groups_tier    ON marketplace.coach_age_groups(age_tier);
    ```
  - [x] Verify V27 is the next free version — V26 was `marketplace_coach_profiles.sql`

- [x] Task 2: Update `CoachProfile` entity (AC: 4)
  - [x] Open `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java`
  - [x] Add one field:
    ```java
    @Column(name = "verification_tier", nullable = false)
    private String verificationTier = "BASIC";
    ```

- [x] Task 3: `CoachReliabilityStrike` entity and repository (AC: 4)
  - [x] Create `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrike.java`:
    ```java
    @Entity
    @Table(schema = "marketplace", name = "coach_reliability_strikes")
    @Getter @Setter @NoArgsConstructor
    public class CoachReliabilityStrike {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @Column(name = "coach_id", nullable = false)
        private UUID coachId;
        private String reason;
        @Column(name = "created_at", nullable = false, updatable = false)
        private OffsetDateTime createdAt = OffsetDateTime.now();
    }
    ```
  - [x] Create `CoachReliabilityStrikeRepository.java`:
    ```java
    public interface CoachReliabilityStrikeRepository extends JpaRepository<CoachReliabilityStrike, UUID> {
        @Query("SELECT s.coachId, COUNT(s) FROM CoachReliabilityStrike s " +
               "WHERE s.coachId IN :coachIds AND s.createdAt > :since " +
               "GROUP BY s.coachId")
        List<Object[]> countByCoachIdInAndCreatedAtAfter(
            @Param("coachIds") List<UUID> coachIds,
            @Param("since") OffsetDateTime since
        );
    }
    ```

- [x] Task 4: DTO records in `platform.marketplace.contract` (AC: 1–6)
  - [x] Create `CoachCardDto.java`:
    ```java
    public record CoachCardDto(
        UUID id,
        String displayName,
        String city,
        String district,
        String photoUrl,
        String verificationTier,
        List<String> topSpecialties,
        BigDecimal perSessionPrice,
        double aggregateRating,
        int reviewCount,
        int reliabilityStrikeCount,
        List<String> capabilityBadges  // empty at this stage; wired in Story 2.3
    ) {}
    ```
  - [x] Create `CoachSearchParams.java`:
    ```java
    public record CoachSearchParams(
        @NotBlank String city,          // required — primary search dimension
        String district,
        String language,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        AgeTier ageGroup,               // null = no filter; typed as AgeTier to avoid bad-enum 400
        String skill,
        Double minRating,               // null = no filter; stub: > 0 returns 0 results until Epic 9
        String sortBy                   // "price" | "rating" | "displayName" (default)
    ) {}
    ```
  - [x] Create `CoachSearchResponse.java` (paginated wrapper):
    ```java
    public record CoachSearchResponse(
        List<CoachCardDto> coaches,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
    ) {}
    ```

- [x] Task 5: `CoachSearchSpecification` + `CoachSearchService` in `platform.marketplace.service` (AC: 1–6)
  - [x] Create `CoachSearchSpecification.java` — all filters applied at the database level via JPA Criteria API:
    ```java
    public final class CoachSearchSpecification {

        private CoachSearchSpecification() {}

        public static Specification<CoachProfile> build(CoachSearchParams p) {
            return Specification
                .where(isActive())
                .and(inCity(p.city()))
                .and(inDistrict(p.district()))
                .and(speaksLanguage(p.language()))
                .and(hasSkill(p.skill()))
                .and(hasAgeGroup(p.ageGroup()))
                .and(minPrice(p.minPrice()))
                .and(maxPrice(p.maxPrice()))
                .and(hasMinRating(p.minRating()));
        }

        private static Specification<CoachProfile> isActive() {
            return (root, q, cb) -> cb.equal(root.get("status"), CoachProfileStatus.ACTIVE);
        }

        private static Specification<CoachProfile> inCity(String city) {
            // city is required — this spec always produces a predicate
            return (root, q, cb) -> cb.equal(cb.lower(root.get("city")), city.toLowerCase());
        }

        private static Specification<CoachProfile> inDistrict(String district) {
            if (!StringUtils.hasText(district)) return null;
            return (root, q, cb) -> cb.equal(cb.lower(root.get("district")), district.toLowerCase());
        }

        private static Specification<CoachProfile> speaksLanguage(String language) {
            if (!StringUtils.hasText(language)) return null;
            // PostgreSQL array contains: language = ANY(languages)
            // Use a native fragment via CriteriaBuilder literal — Hibernate 6 supports this
            return (root, q, cb) -> cb.isTrue(
                cb.function("array_contains_text", Boolean.class,
                    root.get("languages"),
                    cb.literal(language.toLowerCase()))
            );
            // NOTE: if array_contains_text function is unavailable, fall back to a native @Query
            // on CoachProfileRepository (see CRITICAL dev note on language filter below).
        }

        private static Specification<CoachProfile> hasSkill(String skill) {
            if (!StringUtils.hasText(skill)) return null;
            return (root, q, cb) -> {
                Subquery<UUID> sub = q.subquery(UUID.class);
                Root<CoachSpecialty> spec = sub.from(CoachSpecialty.class);
                sub.select(spec.get("coachId"))
                   .where(cb.and(
                       cb.equal(spec.get("coachId"), root.get("id")),
                       cb.equal(cb.lower(spec.get("skill")), skill.toLowerCase())
                   ));
                return cb.exists(sub);
            };
        }

        private static Specification<CoachProfile> hasAgeGroup(AgeTier ageGroup) {
            if (ageGroup == null) return null;
            return (root, q, cb) -> {
                Subquery<UUID> sub = q.subquery(UUID.class);
                Root<CoachAgeGroup> ag = sub.from(CoachAgeGroup.class);
                sub.select(ag.get("coachId"))
                   .where(cb.and(
                       cb.equal(ag.get("coachId"), root.get("id")),
                       cb.equal(ag.get("ageTier"), ageGroup)
                   ));
                return cb.exists(sub);
            };
        }

        private static Specification<CoachProfile> minPrice(BigDecimal min) {
            if (min == null) return null;
            return (root, q, cb) -> {
                Subquery<BigDecimal> sub = q.subquery(BigDecimal.class);
                Root<CoachPricing> pricing = sub.from(CoachPricing.class);
                sub.select(pricing.get("perSessionPrice"))
                   .where(cb.and(
                       cb.equal(pricing.get("coachId"), root.get("id")),
                       cb.greaterThanOrEqualTo(pricing.get("perSessionPrice"), min)
                   ));
                return cb.exists(sub);
            };
        }

        private static Specification<CoachProfile> maxPrice(BigDecimal max) {
            if (max == null) return null;
            return (root, q, cb) -> {
                Subquery<BigDecimal> sub = q.subquery(BigDecimal.class);
                Root<CoachPricing> pricing = sub.from(CoachPricing.class);
                sub.select(pricing.get("perSessionPrice"))
                   .where(cb.and(
                       cb.equal(pricing.get("coachId"), root.get("id")),
                       cb.lessThanOrEqualTo(pricing.get("perSessionPrice"), max)
                   ));
                return cb.exists(sub);
            };
        }

        private static Specification<CoachProfile> hasMinRating(Double minRating) {
            if (minRating == null || minRating <= 0.0) return null;
            // aggregateRating is not stored on coach_profiles — reviews table populated in Epic 9.
            // Any minRating > 0 correctly returns 0 results at this stage.
            return (root, q, cb) -> cb.disjunction();
        }
    }
    ```
  - [x] Create `CoachSearchService.java`:
    ```java
    @Service
    @Transactional(readOnly = true)
    @RequiredArgsConstructor
    @Slf4j
    public class CoachSearchService {

        private static final int DEFAULT_PAGE_SIZE = 20;

        private final CoachProfileRepository coachProfileRepository;
        private final CoachSpecialtyRepository coachSpecialtyRepository;
        private final CoachPricingRepository coachPricingRepository;
        private final CoachReliabilityStrikeRepository strikeRepository;

        public CoachSearchResponse searchCoaches(CoachSearchParams params, int page, int size) {
            // 1. Build DB-level specification — status=ACTIVE and city filter always applied
            Specification<CoachProfile> spec = CoachSearchSpecification.build(params);

            // 2. Build sort + pageable
            Sort sort = buildSort(params.sortBy());
            Pageable pageable = PageRequest.of(page, size, sort);

            // 3. DB query — only the current page rows are loaded into memory
            Page<CoachProfile> profilePage = coachProfileRepository.findAll(spec, pageable);

            if (profilePage.isEmpty()) {
                return new CoachSearchResponse(List.of(), page, size, 0, 0, false);
            }

            // 4. Batch-load enrichment data for THIS PAGE's IDs only (not all coaches)
            List<UUID> pageIds = profilePage.map(CoachProfile::getId).toList();
            Map<UUID, List<String>> specialtiesByCoach = loadSpecialties(pageIds);
            Map<UUID, BigDecimal>   priceByCoach       = loadPrices(pageIds);
            Map<UUID, Integer>      strikesByCoach     = loadReliabilityStrikes(pageIds);

            // 5. Assemble DTOs
            List<CoachCardDto> dtos = profilePage.map(p -> new CoachCardDto(
                p.getId(),
                p.getDisplayName(),
                p.getCity(),
                p.getDistrict(),
                p.getPhotoUrl(),
                p.getVerificationTier(),
                specialtiesByCoach.getOrDefault(p.getId(), List.of()).stream().limit(2).toList(),
                priceByCoach.getOrDefault(p.getId(), BigDecimal.ZERO),
                0.0,      // aggregateRating — wired in Epic 9
                0,        // reviewCount     — wired in Epic 9
                strikesByCoach.getOrDefault(p.getId(), 0),
                List.of() // capabilityBadges — wired in Story 2.3
            )).toList();

            return new CoachSearchResponse(
                dtos,
                profilePage.getNumber(),
                profilePage.getSize(),
                profilePage.getTotalElements(),
                profilePage.getTotalPages(),
                profilePage.hasNext()
            );
        }

        private Sort buildSort(String sortBy) {
            return switch (StringUtils.hasText(sortBy) ? sortBy : "displayName") {
                case "price"  -> Sort.by(Sort.Direction.ASC,  "id"); // price sort done via JOIN — see dev note
                case "rating" -> Sort.by(Sort.Direction.DESC, "id"); // rating sort deferred (Epic 9)
                default       -> Sort.by(Sort.Direction.ASC,  "displayName");
            };
        }

        private Map<UUID, List<String>> loadSpecialties(List<UUID> ids) {
            return coachSpecialtyRepository.findByCoachIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                    CoachSpecialty::getCoachId,
                    Collectors.mapping(CoachSpecialty::getSkill, Collectors.toList())
                ));
        }

        private Map<UUID, BigDecimal> loadPrices(List<UUID> ids) {
            return coachPricingRepository.findByCoachIdIn(ids).stream()
                .collect(Collectors.toMap(CoachPricing::getCoachId, CoachPricing::getPerSessionPrice));
        }

        private Map<UUID, Integer> loadReliabilityStrikes(List<UUID> ids) {
            OffsetDateTime since = OffsetDateTime.now().minusDays(90);
            return strikeRepository.countByCoachIdInAndCreatedAtAfter(ids, since).stream()
                .collect(Collectors.toMap(
                    row -> (UUID) row[0],
                    row -> ((Long) row[1]).intValue()
                ));
        }
    }
    ```
  - [x] Extend `CoachProfileRepository` to also implement `JpaSpecificationExecutor<CoachProfile>`:
    ```java
    public interface CoachProfileRepository
            extends JpaRepository<CoachProfile, UUID>,
                    JpaSpecificationExecutor<CoachProfile> {
        Optional<CoachProfile> findByUserId(Long userId);
        boolean existsByUserId(Long userId);
        // findByStatus no longer needed — Specification handles status filter
    }
    ```
  - [x] Add `findByCoachIdIn` to `CoachSpecialtyRepository`: `List<CoachSpecialty> findByCoachIdIn(List<UUID> coachIds);`
  - [x] Add `findByCoachIdIn` to `CoachPricingRepository`: `List<CoachPricing> findByCoachIdIn(List<UUID> coachIds);`
  - [x] Remove `findCoachIdsByAgeTier` from `CoachAgeGroupRepository` — the age-group filter is now handled by an EXISTS subquery in `CoachSearchSpecification.hasAgeGroup()`

- [x] Task 6: `CoachMarketplaceResource` in `platform.marketplace.api` (AC: 1–6)
  - [x] Create `CoachMarketplaceResource.java`:
    ```java
    @Observed(name = "marketplace.search")
    @RestController
    @RequestMapping("/api/marketplace/coaches")
    @RequiredArgsConstructor
    @Slf4j
    public class CoachMarketplaceResource {

        private static final int MAX_PAGE_SIZE = 50;

        private final CoachSearchService coachSearchService;

        @GetMapping
        @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
        public ResponseEntity<CoachSearchResponse> searchCoaches(
                @RequestParam @NotBlank String city,
                @RequestParam(required = false) String district,
                @RequestParam(required = false) String language,
                @RequestParam(required = false) BigDecimal minPrice,
                @RequestParam(required = false) BigDecimal maxPrice,
                @RequestParam(required = false) AgeTier ageGroup,
                @RequestParam(required = false) String skill,
                @RequestParam(required = false) Double minRating,
                @RequestParam(required = false) String sortBy,
                @RequestParam(defaultValue = "0")  @Min(0) int page,
                @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

            CoachSearchParams params = new CoachSearchParams(
                city, district, language, minPrice, maxPrice, ageGroup, skill, minRating, sortBy
            );
            return ResponseEntity.ok(coachSearchService.searchCoaches(params, page, size));
        }
    }
    ```
  - [x] `city` is annotated `@NotBlank` — Spring MVC will return `400 Bad Request` if city is missing or blank. This enforces city as the mandatory search dimension without explicit service-layer validation.
  - [x] `ageGroup` is typed as `AgeTier` directly — Spring MVC resolves enum params by name. An unrecognised value produces `400 Bad Request` automatically; no manual `try/catch` needed.
  - [x] `IS_PERMIT_ALL` does not yet exist in `SecurityConstants` — add it (see Task 7)

- [x] Task 7: `SecurityConstants` — add `IS_PERMIT_ALL` constant (AC: 5)
  - [x] Open `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`
  - [x] Add after the existing role expressions:
    ```java
    /** Use on public endpoints that are explicitly accessible without authentication. */
    public static final String IS_PERMIT_ALL = "permitAll()";
    ```
  - [x] This enables the project rule "every resource method MUST have @PreAuthorize" to be satisfied even for intentionally public endpoints.

- [x] Task 8: `AppEndpoints` — add marketplace to PUBLIC_ENDPOINTS (AC: 5)
  - [x] Open `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java`
  - [x] `PUBLIC_ENDPOINTS` is declared as `List.of(...)` (immutable). Add `/api/marketplace/coaches**` to the existing list declaration:
    ```java
    public static final List<String> PUBLIC_ENDPOINTS = List.of(
        "/v1/account/register**", "/v1/account/regislink**", "/v1/account/activate/**",
        "/v1/account/reset_password/init", "/v1/account/reset_password/finish",
        "/api/v1/emails/**", "/authenticate",
        "/api/security/coach/register**", "/api/security/coach/verify-email**",
        "/api/security/coach/verify-phone**", "/api/security/coach/resend-verification**",
        "/api/security/parent/register**", "/api/security/parent/verify-email**",
        "/api/security/parent/verify-phone**", "/api/security/parent/resend-verification**",
        "/api/security/parent/resend-otp**",
        "/api/auth/login**",
        "/api/auth/refresh**",
        "/api/auth/logout**",
        "/api/marketplace/coaches**"   // <-- ADD THIS: guests can browse the marketplace
    );
    ```
  - [x] **WHY this is required:** The security filter chain matches `/api/**` as SECURED_ENDPOINTS before method-level `@PreAuthorize("permitAll()")` is evaluated. Without URL-level permit, unauthenticated requests to `GET /api/marketplace/coaches` receive a 401 before the controller is reached. Adding to PUBLIC_ENDPOINTS covers: (a) SecurityConfiguration filter chain, (b) ApiKeyAuthenticationFilter bypass, (c) TenantSecurityConfig bypass — all three security layers.

- [x] Task 9: Frontend — update `marketplace.api.js` (AC: 1–6)
  - [x] Open `src/frontend/src/api/marketplace.api.js`
  - [x] Add the marketplace search function:
    ```js
    export const searchCoaches = (params = {}) =>
      api.get('/api/marketplace/coaches', { params })
    ```
  - [x] The existing file already has `getProfileBuilderStatus`, `saveProfileBuilderStep`, and `publishProfile`. Append `searchCoaches` after the existing exports.

- [x] Task 10: Frontend — `marketplace.store.js` (Pinia) (AC: 1–6)
  - [x] Create `src/frontend/src/stores/marketplace.store.js`:
    ```js
    import { defineStore } from 'pinia'
    import { ref, computed } from 'vue'
    import { searchCoaches } from 'src/api/marketplace.api'

    // Note: Do NOT import useRouter/useRoute here — URL sync is handled in MarketplacePage.vue
    export const useMarketplaceStore = defineStore('marketplace', () => {
      const coaches     = ref([])
      const loading     = ref(false)
      const loadingMore = ref(false)
      const error       = ref(null)

      // Pagination state
      const currentPage  = ref(0)
      const totalPages   = ref(0)
      const totalElements = ref(0)
      const hasNext      = ref(false)

      const filters = ref({
        city:      '',   // primary — search does not fire without a city value
        district:  '',
        language:  '',
        minPrice:  null,
        maxPrice:  null,
        ageGroup:  '',
        skill:     '',
        sortBy:    'displayName',
      })

      // hasActiveFilters excludes city (city is not an optional "filter", it's the search entry)
      const hasActiveFilters = computed(() =>
        ['district','language','minPrice','maxPrice','ageGroup','skill'].some(
          k => filters.value[k] !== '' && filters.value[k] !== null
        )
      )

      const cityEntered = computed(() => filters.value.city.trim().length > 0)

      function syncFiltersFromRoute(query) {
        filters.value = {
          city:      query.city      || '',
          district:  query.district  || '',
          language:  query.language  || '',
          minPrice:  query.minPrice  ? Number(query.minPrice)  : null,
          maxPrice:  query.maxPrice  ? Number(query.maxPrice)  : null,
          ageGroup:  query.ageGroup  || '',
          skill:     query.skill     || '',
          sortBy:    query.sortBy    || 'displayName',
        }
      }

      function buildRouteQuery() {
        const q = {}
        Object.entries(filters.value).forEach(([k, v]) => {
          if (v !== '' && v !== null && !(k === 'sortBy' && v === 'displayName')) q[k] = v
        })
        return q
      }

      async function fetchCoaches() {
        if (!cityEntered.value) return  // guard: no search without city
        loading.value = true
        error.value = null
        currentPage.value = 0
        coaches.value = []
        try {
          const params = buildApiParams(0)
          const res = await searchCoaches(params)
          applyPage(res.data)
        } catch (e) {
          error.value = e
        } finally {
          loading.value = false
        }
      }

      async function fetchNextPage() {
        if (!hasNext.value || loadingMore.value) return
        loadingMore.value = true
        try {
          const params = buildApiParams(currentPage.value + 1)
          const res = await searchCoaches(params)
          coaches.value = [...coaches.value, ...res.data.coaches]  // append for infinite-scroll UX
          currentPage.value = res.data.page
          totalPages.value   = res.data.totalPages
          totalElements.value = res.data.totalElements
          hasNext.value      = res.data.hasNext
        } catch (e) {
          error.value = e
        } finally {
          loadingMore.value = false
        }
      }

      function buildApiParams(page) {
        const p = { page, size: 20 }
        Object.entries(filters.value).forEach(([k, v]) => {
          if (v !== '' && v !== null && !(k === 'sortBy' && v === 'displayName')) p[k] = v
        })
        return p
      }

      function applyPage(data) {
        coaches.value       = data.coaches
        currentPage.value   = data.page
        totalPages.value    = data.totalPages
        totalElements.value = data.totalElements
        hasNext.value       = data.hasNext
      }

      function clearFilters() {
        // Clears secondary filters only — city is preserved (it is the search entry point)
        filters.value = {
          ...filters.value,
          district: '', language: '', minPrice: null,
          maxPrice: null, ageGroup: '', skill: '', sortBy: 'displayName',
        }
        fetchCoaches()
      }

      function resetSearch() {
        coaches.value = []
        filters.value = { city: '', district: '', language: '', minPrice: null,
                          maxPrice: null, ageGroup: '', skill: '', sortBy: 'displayName' }
        currentPage.value = 0; totalPages.value = 0; hasNext.value = false
      }

      return {
        coaches, loading, loadingMore, error,
        currentPage, totalPages, totalElements, hasNext,
        filters, hasActiveFilters, cityEntered,
        syncFiltersFromRoute, buildRouteQuery,
        fetchCoaches, fetchNextPage, clearFilters, resetSearch,
      }
    })
    ```

- [x] Task 11: Frontend — `ReliabilityIndicator.vue` (AC: 4, UX-DR8)
  - [x] Create `src/frontend/src/components/marketplace/ReliabilityIndicator.vue`:
    ```vue
    <template>
      <span :class="['reliability-indicator', stateClass]">
        <q-icon :name="iconName" size="14px" class="q-mr-xs" />
        {{ label }}
      </span>
    </template>

    <script setup>
    import { computed } from 'vue'
    import { useI18n } from 'vue-i18n'

    const props = defineProps({
      strikeCount: { type: Number, required: true },
    })

    const { t } = useI18n()

    const stateClass = computed(() => {
      if (props.strikeCount === 0) return 'reliability-indicator--ok'
      if (props.strikeCount <= 2) return 'reliability-indicator--warning'
      return 'reliability-indicator--danger'
    })

    const iconName = computed(() => {
      if (props.strikeCount === 0) return 'check_circle'
      if (props.strikeCount <= 2) return 'warning'
      return 'error'
    })

    const label = computed(() => {
      if (props.strikeCount === 0) return t('marketplace.reliabilityOk')
      if (props.strikeCount <= 2) return t('marketplace.reliabilityWarning', { count: props.strikeCount })
      return t('marketplace.reliabilityDanger')
    })
    </script>

    <style lang="scss" scoped>
    .reliability-indicator {
      display: inline-flex;
      align-items: center;
      font-size: 12px;
      font-weight: 500;

      &--ok      { color: var(--accent-success); }
      &--warning { color: var(--accent-warning); }
      &--danger  { color: var(--accent-danger); }
    }
    </style>
    ```

- [x] Task 12: Frontend — `VerificationBadge.vue` (AC: 4)
  - [x] Create `src/frontend/src/components/marketplace/VerificationBadge.vue`:
    ```vue
    <template>
      <q-chip
        dense
        :color="badgeColor"
        :icon="badgeIcon"
        :label="badgeLabel"
        class="verification-badge text-white"
        size="sm"
      >
        <q-tooltip>{{ tooltipText }}</q-tooltip>
      </q-chip>
    </template>

    <script setup>
    import { computed } from 'vue'
    import { useI18n } from 'vue-i18n'

    const props = defineProps({
      tier: { type: String, required: true }, // 'BASIC' | 'TRUSTED' | 'FEATURED'
    })

    const { t } = useI18n()

    const badgeColor = computed(() => ({
      BASIC:    'grey-6',
      TRUSTED:  'blue-6',
      FEATURED: 'amber-8',
    }[props.tier] ?? 'grey-6'))

    const badgeIcon = computed(() => ({
      BASIC:    'verified',
      TRUSTED:  'verified_user',
      FEATURED: 'star',
    }[props.tier] ?? 'verified'))

    const badgeLabel = computed(() => t(`marketplace.tier${props.tier}`))
    const tooltipText = computed(() => t(`marketplace.tierTooltip${props.tier}`))
    </script>
    ```

- [x] Task 13: Frontend — `CoachCard.vue` (AC: 4, UX-DR7)
  - [x] Create `src/frontend/src/components/marketplace/CoachCard.vue`:
    ```vue
    <template>
      <q-card class="glass-card coach-card" @click="$emit('click', coach.id)">
        <!-- Photo / Avatar -->
        <div class="coach-card__photo-wrap">
          <q-img
            v-if="coach.photoUrl"
            :src="coach.photoUrl"
            class="coach-card__photo"
            fit="cover"
            :ratio="1"
          />
          <div v-else class="coach-card__avatar-placeholder">
            <q-icon name="person" size="48px" color="white" />
          </div>
        </div>

        <q-card-section class="coach-card__body">
          <!-- Trust signals ABOVE price (UX-DR7) -->
          <div class="coach-card__trust-row">
            <VerificationBadge :tier="coach.verificationTier" />
            <div v-if="coach.capabilityBadges.length" class="coach-card__capability-badges">
              <q-badge
                v-for="badge in coach.capabilityBadges"
                :key="badge"
                color="primary"
                class="q-mr-xs"
              >{{ badge }}</q-badge>
            </div>
          </div>

          <ReliabilityIndicator :strike-count="coach.reliabilityStrikeCount" class="q-mt-xs" />

          <!-- Name + location -->
          <div class="coach-card__name q-mt-sm">{{ coach.displayName }}</div>
          <div class="coach-card__location text-caption">
            <q-icon name="location_on" size="12px" />
            {{ [coach.city, coach.district].filter(Boolean).join(', ') }}
          </div>

          <!-- Specialties -->
          <div v-if="coach.topSpecialties.length" class="coach-card__specialties q-mt-xs">
            <q-chip
              v-for="s in coach.topSpecialties"
              :key="s"
              dense
              outline
              size="sm"
            >{{ s }}</q-chip>
          </div>

          <!-- Star rating -->
          <div class="coach-card__rating q-mt-xs">
            <q-rating
              :model-value="coach.aggregateRating"
              readonly
              size="14px"
              color="amber"
              max="5"
            />
            <span class="text-caption q-ml-xs">
              {{ coach.aggregateRating.toFixed(1) }}
              ({{ t('marketplace.reviewCount', { count: coach.reviewCount }) }})
            </span>
          </div>

          <!-- Price — BELOW trust signals (UX-DR7) -->
          <div class="coach-card__price q-mt-sm">
            <span class="text-h6">€{{ Number(coach.perSessionPrice).toFixed(2) }}</span>
            <span class="text-caption q-ml-xs text-secondary">/ {{ t('marketplace.perSession') }}</span>
          </div>
        </q-card-section>
      </q-card>
    </template>

    <script setup>
    import { useI18n } from 'vue-i18n'
    import VerificationBadge from './VerificationBadge.vue'
    import ReliabilityIndicator from './ReliabilityIndicator.vue'

    defineProps({
      coach: { type: Object, required: true },
    })

    defineEmits(['click'])

    const { t } = useI18n()
    </script>

    <style lang="scss" scoped>
    .coach-card {
      cursor: pointer;
      transition: transform 0.15s ease;

      &:hover { transform: translateY(-2px); }

      &__photo-wrap {
        height: 180px;
        overflow: hidden;
        border-radius: 28px 28px 0 0;
        background: linear-gradient(135deg, var(--accent-primary), var(--accent-secondary));
        display: flex;
        align-items: center;
        justify-content: center;
      }

      &__photo {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }

      &__body { padding: 12px 16px 16px; }

      &__trust-row {
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
      }

      &__name {
        font-size: 16px;
        font-weight: 700;
        line-height: 1.2;
      }

      &__location { color: var(--text-secondary); }

      &__specialties { display: flex; flex-wrap: wrap; gap: 4px; }

      &__rating {
        display: flex;
        align-items: center;
        color: var(--text-secondary);
      }

      &__price { color: var(--text-primary); }
    }
    </style>
    ```

- [x] Task 14: Frontend — `MarketplacePage.vue` (AC: 1–6)
  - [x] Create `src/frontend/src/pages/marketplace/MarketplacePage.vue`:
    ```vue
    <template>
      <q-page class="marketplace-page">
        <!-- City search — primary entry point (AC 3) -->
        <div class="marketplace-page__search-bar">
          <q-input
            v-model="filters.city"
            outlined
            :label="t('marketplace.searchByCity')"
            :placeholder="t('marketplace.searchByCityPlaceholder')"
            class="city-input"
            clearable
            @keyup.enter="onSearch"
            @clear="onCityCleared"
          >
            <template #append>
              <q-btn
                unelevated
                color="primary"
                icon="search"
                :label="t('marketplace.search')"
                :disable="!cityEntered"
                @click="onSearch"
              />
            </template>
          </q-input>
        </div>

        <!-- Secondary filters — visible only after a city is entered -->
        <div v-if="cityEntered" class="marketplace-page__filters">
          <q-input v-model="filters.district" dense outlined :label="t('marketplace.filterDistrict')"
                   clearable @update:model-value="onFilterChange" />
          <q-select v-model="filters.ageGroup" dense outlined :label="t('marketplace.filterAgeGroup')"
                    :options="ageGroupOptions" emit-value map-options clearable
                    @update:model-value="onFilterChange" />
          <q-select v-model="filters.language" dense outlined :label="t('marketplace.filterLanguage')"
                    :options="languageOptions" emit-value map-options clearable
                    @update:model-value="onFilterChange" />
          <q-select v-model="filters.sortBy" dense outlined :label="t('marketplace.sortBy')"
                    :options="sortOptions" emit-value map-options
                    @update:model-value="onFilterChange" />
          <q-btn v-if="hasActiveFilters" flat dense :label="t('marketplace.clearFilters')"
                 icon="close" @click="onClearFilters" />
        </div>

        <!-- Prompt state — no city entered yet (NOT the UX-DR25 empty state) -->
        <div v-if="!cityEntered" class="marketplace-page__prompt">
          <q-icon name="search" size="64px" color="grey-4" />
          <div class="text-h6 q-mt-md">{{ t('marketplace.enterCityPrompt') }}</div>
          <div class="text-body2 text-secondary q-mt-xs">{{ t('marketplace.enterCitySubtitle') }}</div>
        </div>

        <!-- Loading skeleton (UX-DR26) -->
        <div v-else-if="loading" class="marketplace-page__grid">
          <div v-for="n in 6" :key="n" class="glass-card coach-card-skeleton">
            <q-skeleton height="180px" square />
            <div class="q-pa-md">
              <q-skeleton type="text" width="60%" />
              <q-skeleton type="text" width="40%" class="q-mt-sm" />
              <q-skeleton type="text" width="30%" class="q-mt-sm" />
            </div>
          </div>
        </div>

        <!-- Empty state (UX-DR25) — city entered, search done, no results -->
        <div v-else-if="cityEntered && !loading && coaches.length === 0" class="marketplace-page__empty">
          <q-icon name="search_off" size="64px" color="grey-5" />
          <div class="text-h6 q-mt-md">{{ t('marketplace.noCoachesFound') }}</div>
          <div class="text-body2 text-secondary q-mt-xs">
            {{ t('marketplace.noCoachesFoundInCity', { city: filters.city }) }}
          </div>
          <q-btn v-if="hasActiveFilters" unelevated color="primary"
                 :label="t('marketplace.clearFilters')" class="q-mt-md" @click="onClearFilters" />
        </div>

        <!-- Coach grid -->
        <template v-else>
          <div class="marketplace-page__results-header">
            <span class="text-caption text-secondary">
              {{ t('marketplace.resultsCount', { count: totalElements }) }}
            </span>
          </div>

          <div class="marketplace-page__grid">
            <CoachCard
              v-for="coach in coaches"
              :key="coach.id"
              :coach="coach"
              @click="goToProfile"
            />
          </div>

          <!-- Load-more (infinite scroll / explicit button) -->
          <div v-if="hasNext" class="marketplace-page__load-more">
            <q-btn
              outline
              color="primary"
              :label="t('marketplace.loadMore')"
              :loading="loadingMore"
              @click="store.fetchNextPage()"
            />
          </div>
        </template>
      </q-page>
    </template>

    <script setup>
    import { onMounted } from 'vue'
    import { useRouter, useRoute } from 'vue-router'
    import { storeToRefs } from 'pinia'
    import { useI18n } from 'vue-i18n'
    import { useMarketplaceStore } from 'src/stores/marketplace.store'
    import CoachCard from 'src/components/marketplace/CoachCard.vue'

    const { t } = useI18n()
    const router = useRouter()
    const route  = useRoute()
    const store  = useMarketplaceStore()
    const { coaches, loading, loadingMore, filters, hasActiveFilters,
            cityEntered, hasNext, totalElements } = storeToRefs(store)

    const ageGroupOptions = [
      { label: t('marketplace.ageGroupU10'),   value: 'U10' },
      { label: t('marketplace.ageGroup1012'),  value: 'AGE_10_12' },
      { label: t('marketplace.ageGroup1317'),  value: 'AGE_13_17' },
      { label: t('marketplace.ageGroupAdult'), value: 'ADULT' },
    ]

    const languageOptions = ['German', 'English', 'Turkish', 'Arabic'].map(l => ({ label: l, value: l }))

    const sortOptions = [
      { label: t('marketplace.sortName'),   value: 'displayName' },
      { label: t('marketplace.sortPrice'),  value: 'price' },
      { label: t('marketplace.sortRating'), value: 'rating' },
    ]

    onMounted(() => {
      // Restore filters from URL (AC 3 — back-button preserves state)
      store.syncFiltersFromRoute(route.query)
      // If city was in URL, fire search immediately (user came back from a profile page)
      if (store.cityEntered) store.fetchCoaches()
    })

    function onSearch() {
      if (!store.cityEntered) return
      router.replace({ query: store.buildRouteQuery() })
      store.fetchCoaches()
    }

    function onFilterChange() {
      router.replace({ query: store.buildRouteQuery() })
      store.fetchCoaches()
    }

    function onClearFilters() {
      store.clearFilters()
      router.replace({ query: store.buildRouteQuery() }) // city stays in URL
    }

    function onCityCleared() {
      store.resetSearch()
      router.replace({ query: {} })
    }

    function goToProfile(coachId) {
      router.push({ path: `/coaches/${coachId}`, query: { returnUrl: route.fullPath } })
    }
    </script>

    <style lang="scss" scoped>
    .marketplace-page {
      max-width: 1400px;
      margin: 0 auto;
      padding: 24px;

      &__search-bar {
        margin-bottom: 20px;
        .city-input { max-width: 640px; }
      }

      &__filters {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        align-items: center;
        margin-bottom: 24px;
      }

      &__results-header {
        margin-bottom: 16px;
      }

      &__grid {
        display: grid;
        gap: 20px;
        grid-template-columns: repeat(3, 1fr);           // ≥1200px
        @media (max-width: 1199px) { grid-template-columns: repeat(2, 1fr); }  // 768–1199px
        @media (max-width: 767px)  { grid-template-columns: 1fr; }             // mobile
      }

      &__prompt, &__empty {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 80px 24px;
        text-align: center;
        color: var(--text-secondary);
      }

      &__load-more {
        display: flex;
        justify-content: center;
        padding: 32px 0;
      }
    }

    .coach-card-skeleton {
      border-radius: 28px;
      overflow: hidden;
    }
    </style>
    ```

- [x] Task 15: Frontend — Router update (AC: 3, 5)
  - [x] Open `src/frontend/src/router/routes.js`
  - [x] Add marketplace and coach profile routes (both public — no `requiresAuth`):
    ```js
    // In the MainLayout children array, add:
    {
      path: 'marketplace',
      component: () => import('pages/marketplace/MarketplacePage.vue'),
      // No meta.requiresAuth — guests can browse (AC 5, FR-MKT-005)
    },
    {
      path: 'coaches/:coachId',
      component: () => import('pages/marketplace/CoachPublicProfilePlaceholderPage.vue'),
      // Placeholder — full implementation in Story 2.3
    },
    ```
  - [x] Create the placeholder for the public profile page (Story 2.3 will replace it):
    - Create `src/frontend/src/pages/marketplace/CoachPublicProfilePlaceholderPage.vue` with minimal glass-card placeholder
  - [x] **Note on guest redirect for booking/contact:** Story 2.3 implements the booking CTA with redirect logic. For Story 2.2, the `goToProfile` router push in MarketplacePage is sufficient — no sign-up gate needed at the list level.

- [x] Task 16: Frontend — i18n keys (AC: 1–6)
  - [x] Update `marketplace:` block in all four locale files (`en/index.js`, `en-US/index.js`, `de/index.js`, `fr-FR/index.js`):
    ```js
    // English (en / en-US)
    marketplace: {
      title: 'Find a Coach',
      searchByCity: 'Search by city',
      searchByCityPlaceholder: 'e.g. Frankfurt, Berlin, Munich',
      search: 'Search',
      enterCityPrompt: 'Search for coaches in your city',
      enterCitySubtitle: 'Enter a city above to find available coaches near you',
      filterDistrict: 'District',
      filterAgeGroup: 'Age Group',
      filterLanguage: 'Language',
      sortBy: 'Sort By',
      sortName: 'Name',
      sortPrice: 'Price',
      sortRating: 'Rating',
      clearFilters: 'Clear filters',
      noCoachesFound: 'No coaches found',
      noCoachesFoundInCity: 'No active coaches in {city} match your filters',
      resultsCount: '{count} coaches found',
      loadMore: 'Load more',
      perSession: 'per session',
      reviewCount: '{count} reviews | 1 review | {count} reviews',
      reliabilityOk: 'No reliability issues',
      reliabilityWarning: '{count} issues (90 days)',
      reliabilityDanger: 'Review reliability score',
      tierBASIC: 'Basic',
      tierTRUSTED: 'Trusted',
      tierFEATURED: 'Featured',
      tierTooltipBASIC: 'Email and phone verified',
      tierTooltipTRUSTED: 'Admin-verified trusted coach',
      tierTooltipFEATURED: 'Admin-featured top coach',
      ageGroupU10: 'Under 10',
      ageGroup1012: '10–12 years',
      ageGroup1317: '13–17 years',
      ageGroupAdult: 'Adult',
    },
    ```
  - [x] Run the i18n parity check after updating:
    ```bash
    node -e "
    const en   = require('./src/frontend/src/i18n/en/index.js').default;
    const enUS = require('./src/frontend/src/i18n/en-US/index.js').default;
    const de   = require('./src/frontend/src/i18n/de/index.js').default;
    const frFR = require('./src/frontend/src/i18n/fr-FR/index.js').default;
    const k = o => JSON.stringify(Object.keys(o?.marketplace ?? {}).sort());
    const all = [k(en), k(enUS), k(de), k(frFR)];
    console.log('marketplace key parity:', all.every(x => x === all[0]) ? 'OK' : 'MISMATCH');
    "
    ```

- [x] Task 17: Integration test — `CoachMarketplaceResourceIT` (AC: 1–6)
  - [x] Create `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResourceIT.java`:
    - `@SpringBootTest(webEnvironment = RANDOM_PORT) @AutoConfigureTestDatabase(replace = NONE) @Testcontainers`
    - `@TestPropertySource(properties = "allowed.clients=testClientId")`
    - Setup: Insert 3 ACTIVE coach profiles in city "Frankfurt" + 1 DRAFT + 1 in "Berlin" via `JdbcTemplate`
    - **Do NOT log in** — these endpoints are public; call `RestTemplate` directly without auth cookies
    - Test cases:
      - `searchCoaches_missingCity_returns400` — `GET /api/marketplace/coaches` without `city` param returns 400
      - `searchCoaches_byCity_returnsOnlyMatchingCity` — Frankfurt returns 3, not the Berlin or DRAFT coach
      - `searchCoaches_draftCoachExcluded` — DRAFT profile never appears in results even if city matches
      - `searchCoaches_filterByAgeGroup_returnsOnlyMatchingCoaches`
      - `searchCoaches_filterBySkill_returnsOnlyMatchingCoaches`
      - `searchCoaches_filterByPriceRange_returnsOnlyMatchingCoaches`
      - `searchCoaches_filterByLanguage_returnsOnlyMatchingCoaches`
      - `searchCoaches_noMatches_returnsEmptyPageNotError` — returns `{ coaches: [], totalElements: 0 }` not 404
      - `searchCoaches_paginationPage0_returnsSizeCoaches` — verify `page=0&size=2` returns first 2, `hasNext=true`
      - `searchCoaches_paginationPage1_returnsNextCoaches` — page 1 returns remaining 1, `hasNext=false`
      - `searchCoaches_unauthenticated_returns200` — no `Authorization` header, no cookie → still 200
      - `searchCoaches_reliabilityStrikeCount_reflected` — insert strike row, verify `reliabilityStrikeCount == 1`
      - `searchCoaches_aggregateRating_defaultsToZero` — verify `aggregateRating == 0.0`, `reviewCount == 0`
      - `searchCoaches_invalidAgeGroup_returns400` — `ageGroup=INVALID` returns 400
      - `searchCoaches_minRatingFilter_returnsEmptyPage` — `minRating=3.0` returns `{ coaches: [], totalElements: 0 }` since all ratings are 0.0 at this stage (Epic 9 stub)
    - Coach setup helper: insert directly into `marketplace.coach_profiles` (with `status = 'ACTIVE'`, `verification_tier = 'BASIC'`), `marketplace.coach_specialties`, `marketplace.coach_age_groups`, `marketplace.coach_pricing`
    - Assert on the `CoachSearchResponse` wrapper fields: `coaches`, `totalElements`, `totalPages`, `page`, `hasNext`

## Dev Notes

### CRITICAL: `AppEndpoints.PUBLIC_ENDPOINTS` is immutable — must expand the List.of() call

`List.of(...)` creates an unmodifiable list. You CANNOT call `.add()` on it at runtime. The only correct fix is to expand the static `List.of(...)` declaration at line 23 of `AppEndpoints.java` to include `/api/marketplace/coaches**`. Do NOT try to add it in a static initializer or via a new list — the field is `public static final` and used in multiple filters at startup.

### CRITICAL: Security stack has THREE layers — all covered by PUBLIC_ENDPOINTS

1. `SecurityConfiguration` filter chain → `requestMatchers(PUBLIC_ENDPOINTS).permitAll()`
2. `ApiKeyAuthenticationFilter` → `BYPASS_PATTERNS.addAll(PUBLIC_ENDPOINTS)` at line 78
3. `TenantSecurityConfig` → public matchers built from `PUBLIC_ENDPOINTS` at line 103

Adding `/api/marketplace/coaches**` to `PUBLIC_ENDPOINTS` covers all three. If you only add `@PreAuthorize("permitAll()")` to the method without updating `PUBLIC_ENDPOINTS`, requests still fail at layer 2 (ApiKeyFilter returns 401 before the controller is reached).

### CRITICAL: `IS_PERMIT_ALL` constant must be added to SecurityConstants

Project rule: "Every resource method MUST have a `@PreAuthorize` annotation using `SecurityConstants`." The `CoachMarketplaceResource.searchCoaches` method must satisfy this even though it is intentionally public. Add `IS_PERMIT_ALL = "permitAll()"` to `SecurityConstants`. This is a **single one-line addition** — do not create a second constants class.

### CRITICAL: `CoachMarketplaceResource` needs `@Validated` for `@RequestParam` constraint enforcement

`@NotBlank` on a `@RequestParam` only fires if the controller class is annotated with `@Validated` (Spring's method-level validation). Without it, the `city` null/blank check silently passes through to the service and causes a `NullPointerException` in the Specification. Add `@Validated` at the class level:

```java
@Validated
@Observed(name = "marketplace.search")
@RestController
@RequestMapping("/api/marketplace/coaches")
// ...
```

Spring Boot 3.x auto-configures `MethodValidationPostProcessor`, so `@Validated` is all that's needed. A missing `city` will return a `400 Bad Request` with the constraint message.

### CRITICAL: `CoachProfileRepository` must extend `JpaSpecificationExecutor`

The `Specification`-based query only works if the repository declares `JpaSpecificationExecutor<CoachProfile>`. Without this, `findAll(Specification, Pageable)` does not exist. The updated declaration is:
```java
public interface CoachProfileRepository
        extends JpaRepository<CoachProfile, UUID>,
                JpaSpecificationExecutor<CoachProfile> { ... }
```
This is a non-breaking addition — existing query methods continue to work.

### CRITICAL: Language filter — PostgreSQL array `ANY` in JPA Criteria API

`coach_profiles.languages` is a `VARCHAR[]` column. The SQL predicate `:value = ANY(languages)` is PostgreSQL-specific and not directly expressible via the standard JPA Criteria API. The `speaksLanguage` Specification in the code above uses a `cb.function("array_contains_text", ...)` placeholder. **This will not work out of the box.**

The correct approach is a native `@Query` fallback. If the language filter is active, bypass the Specification for that predicate and instead use a native query. The simplest fix: add a method to `CoachProfileRepository` and call it in the service when `language` is set:

```java
// In CoachProfileRepository:
@Query(value = "SELECT id FROM marketplace.coach_profiles " +
               "WHERE status = 'ACTIVE' AND lower(:lang) = ANY(languages)", nativeQuery = true)
List<UUID> findIdsByLanguage(@Param("lang") String lang);
```

Then in `CoachSearchService`, intersect the Specification results with the language-filtered IDs if `params.language()` is set. Alternatively, replace `speaksLanguage()` in `CoachSearchSpecification` with a native EXISTS subquery or remove it from the Specification and apply it as a post-filter on the page items (acceptable since pages are small). Document the chosen approach with a comment.

### CRITICAL: Price sort cannot use `Pageable` `Sort` on a related table column

`coach_pricing.per_session_price` lives in a separate table. Spring Data JPA `Sort.by("perSessionPrice")` does not cross tables — it would cause a `PropertyReferenceException`. The `buildSort` method in `CoachSearchService` falls back to `Sort.by("id")` for `price` sort, which is wrong (gives a stable but arbitrary order).

**Fix for price sort:** After fetching the page with `Sort.by("displayName")`, sort the 20 enriched DTOs by `perSessionPrice` in Java before building the response. This means the sort applies within the current page only — cross-page price ordering is not guaranteed. This is an acceptable MVP limitation; document it with a comment. If true cross-page price sort is needed, add a denormalized `per_session_price` column to `coach_profiles` in a future migration.

```java
// In CoachSearchService after enrichment, replace the plain .toList():
List<CoachCardDto> sorted = sortPage(dtos, params.sortBy());
// ...
private List<CoachCardDto> sortPage(List<CoachCardDto> dtos, String sortBy) {
    if ("price".equals(sortBy)) {
        return dtos.stream()
            .sorted(Comparator.comparing(d -> d.perSessionPrice() != null ? d.perSessionPrice() : BigDecimal.ZERO))
            .toList();
    }
    return dtos; // displayName sort was applied at DB level via Pageable
}
```

### CRITICAL: Aggregate rating and review count are hardcoded to 0/0.0 at this stage

The `reviews` table does not exist until Epic 9. The `CoachSearchService` must return `aggregateRating = 0.0` and `reviewCount = 0` hardcoded. Do NOT attempt to query a non-existent table. This is explicitly documented in the epic: "Aggregate rating computed from reviews table (Epic 9 populates; default 0.0 / 0 reviews at this stage)."

The `minRating` filter stubs as `cb.disjunction()` (always-false predicate) for any value `> 0`. Since all coaches have `aggregateRating = 0.0` until Epic 9, this correctly returns 0 results for any positive minimum rating. The frontend should not expose the star rating filter prominently until Epic 9 wires review data.

### CRITICAL: Capability badges are empty at this stage

`CoachCapabilityService` (introduced in Story 2.3's dev notes) determines which tool badges a coach displays. For Story 2.2, `capabilityBadges` in `CoachCardDto` is always an empty list `List.of()`. The frontend `CoachCard.vue` handles an empty list gracefully (no badge row rendered). Story 2.3 wires the capability service.

### Design decision: city-first search, no geolocation at this stage

The marketplace does not auto-load coaches on page open. The user must enter a city before results appear. This avoids loading potentially thousands of unscoped results, makes the intent explicit, and prevents a heavy first query. Geolocation-based sorting and lat/lon columns are deferred — if added later, they go into a new Flyway migration and a separate story, not here.

### CRITICAL: `CoachSpecialtyRepository` and `CoachPricingRepository` need `findByCoachIdIn`

These batch-load methods don't exist yet in the repositories created in Story 2.1. Add:
```java
// CoachSpecialtyRepository
List<CoachSpecialty> findByCoachIdIn(List<UUID> coachIds);

// CoachPricingRepository  
List<CoachPricing> findByCoachIdIn(List<UUID> coachIds);
```
These are Spring Data JPA derived query methods — no `@Query` needed.

### CRITICAL: V27 migration version — verify before creating

Verify V27 is next: `ls src/main/resources/db/migration/ | sort | tail -3` should end at `V26__marketplace_coach_profiles.sql`. V27 is the correct next version.

### CRITICAL: Test setup for CoachMarketplaceResourceIT

The test inserts data directly via `JdbcTemplate`. Key points from `CoachProfileBuilderIT` learnings:
- Insert into `main."user"` (not `main.skillars_users`) for user rows
- Include `iso2_country = 'DE'` (NOT NULL column — caused 11 failures in FamilyDataIsolationIT)
- After inserting a coach user, insert into `marketplace.coach_profiles` with `status = 'ACTIVE'` and `verification_tier = 'BASIC'`
- Use `RestTemplate` directly (no auth cookies) — the endpoint is public
- Long IDs serialize as JSON strings: `Long.parseLong((String) body.get("id"))` pattern

### Frontend: MarketplacePage directory must be created

`src/frontend/src/pages/marketplace/` does not exist yet. Create it. The page file goes at `pages/marketplace/MarketplacePage.vue`.

### Frontend: `glass-card` CSS class

The glassmorphism `.glass-card` class is globally defined in the design system (Story 1.2). `CoachCard.vue` uses it as the base card style (border-radius 28px, backdrop-filter blur). No new CSS class needed for the card frame — only the card-internal styles need scoped CSS.

### Frontend: Pinia store import in Vue Router guard context

`useMarketplaceStore()` must only be called inside Vue component setup or pinia-aware contexts. The router `beforeEach` guard in `router/index.js` uses `useProfileBuilderStore()` — follow the same pattern (the store is available because Pinia is initialized before the router guard runs in App.vue).

### Backend: `AgeTier` enum location

`AgeTier` (`U10, AGE_10_12, AGE_13_17, ADULT`) is in `com.softropic.skillars.platform.security.contract.AgeTier`. Import from there — do NOT redefine in `platform.marketplace.contract`. Confirmed from Story 2.1 dev notes.

### Frontend: CoachCard skeleton dimensions

Skeleton cards MUST match the exact dimensions of real `CoachCard` components (UX-DR26). The `CoachCard` photo area is 180px high; the body is approximately 200px. The skeleton in `MarketplacePage.vue` uses `<q-skeleton height="180px" square />` for the photo area and text skeletons for the body. This ensures no layout shift when data loads.

### Project Structure

**New backend files:**
```
src/main/resources/db/migration/
└── V27__marketplace_search_support.sql                   (new)

src/main/java/com/softropic/skillars/platform/marketplace/
├── api/
│   └── CoachMarketplaceResource.java                     (new)
├── service/
│   ├── CoachSearchService.java                           (new)
│   └── CoachSearchSpecification.java                     (new)
└── repo/
    ├── CoachReliabilityStrike.java                       (new @Entity)
    └── CoachReliabilityStrikeRepository.java             (new)
```

**Modified backend files:**
- `platform/marketplace/repo/CoachProfile.java` — add `verificationTier` field
- `platform/marketplace/repo/CoachProfileRepository.java` — extend `JpaSpecificationExecutor<CoachProfile>`; add `findIdsByLanguage` native query
- `platform/marketplace/repo/CoachSpecialtyRepository.java` — add `findByCoachIdIn()`
- `platform/marketplace/repo/CoachPricingRepository.java` — add `findByCoachIdIn()`
- `infrastructure/security/SecurityConstants.java` — add `IS_PERMIT_ALL`
- `platform/security/config/AppEndpoints.java` — add marketplace endpoint to `PUBLIC_ENDPOINTS`

**New frontend files:**
```
src/frontend/src/pages/marketplace/
├── MarketplacePage.vue                                   (new)
└── CoachPublicProfilePlaceholderPage.vue                 (new placeholder for Story 2.3)

src/frontend/src/components/marketplace/
├── CoachCard.vue                                         (new)
├── VerificationBadge.vue                                 (new)
└── ReliabilityIndicator.vue                              (new)

src/frontend/src/stores/
└── marketplace.store.js                                  (new)
```

**Modified frontend files:**
- `src/frontend/src/api/marketplace.api.js` — add `searchCoaches()`
- `src/frontend/src/router/routes.js` — add `/marketplace` and `/coaches/:coachId` routes
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**New test files:**
```
src/test/java/com/softropic/skillars/platform/marketplace/api/
└── CoachMarketplaceResourceIT.java                       (new)
```

### Verification commands

```bash
# Verify V27 is next
ls src/main/resources/db/migration/ | sort | tail -3

# Verify marketplace module structure
ls src/main/java/com/softropic/skillars/platform/marketplace/api/
ls src/main/java/com/softropic/skillars/platform/marketplace/service/

# Verify IS_PERMIT_ALL exists in SecurityConstants
grep -n "IS_PERMIT_ALL" src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java

# Verify PUBLIC_ENDPOINTS has marketplace
grep -n "marketplace" src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java

# Verify CoachProfile has new field
grep -n "verificationTier" \
  src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java

# Verify new repository methods
grep -n "findByStatus\|findByCoachIdIn\|findCoachIdsByAgeTier" \
  src/main/java/com/softropic/skillars/platform/marketplace/repo/*.java

# Verify frontend marketplace store
ls src/frontend/src/stores/marketplace.store.js
ls src/frontend/src/components/marketplace/

# i18n parity check
node -e "
const en   = require('./src/frontend/src/i18n/en/index.js').default;
const enUS = require('./src/frontend/src/i18n/en-US/index.js').default;
const de   = require('./src/frontend/src/i18n/de/index.js').default;
const frFR = require('./src/frontend/src/i18n/fr-FR/index.js').default;
const k = o => JSON.stringify(Object.keys(o?.marketplace ?? {}).sort());
const all = [k(en), k(enUS), k(de), k(frFR)];
console.log('marketplace key parity:', all.every(x => x === all[0]) ? 'OK' : 'MISMATCH');
"
```

### References

- Story 2.1 (`skillars-2-1-coach-profile-builder.md`) — marketplace module structure, V26 schema, `CoachProfileBuilderIT` patterns, `iso2_country` NOT NULL, Long-as-JSON-string, `allowed.clients` test property
- `project-context.md` — `@PreAuthorize` required on every endpoint, Java records for DTOs, `com.softropic.skillars.platform.{module}.{layer}` hierarchy, no entity returns from controllers
- `AppEndpoints.java` — PUBLIC_ENDPOINTS declared as `List.of(...)` (immutable); must expand declaration
- `SecurityConfiguration.java` lines 218–225 — three security layers all reading from PUBLIC_ENDPOINTS
- `ApiKeyAuthenticationFilter.java` line 78 — bypass patterns use PUBLIC_ENDPOINTS
- `ux-design-specification.md` — CoachCard anatomy, UX-DR7 (trust above price), UX-DR8 (ReliabilityIndicator), UX-DR25 (empty state), UX-DR26 (skeleton cards)
- `skillars-epics.md` Epic 2 Story 2.2 — acceptance criteria, dev notes, table relationships

---

## Review Findings

_Generated by bmad-code-review — 3 layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor_

### Patches

- [x] [Review][Patch] P1: `findIdsByLanguage` scans ALL active coaches globally — no city constraint — load grows with platform size [`CoachProfileRepository.java:findIdsByLanguage`]
- [ ] [Review][Patch] P2: Unauthenticated test still sends `X-Client-Id` API key — does not prove true anonymous access (AC 5) [`CoachMarketplaceResourceIT.java:searchCoaches_unauthenticated_returns200`]
- [ ] [Review][Patch] P3: `loadPrices` uses `Collectors.toMap` without merge function — throws `IllegalStateException` if a coach has multiple pricing rows [`CoachSearchService.java:loadPrices()`]
- [ ] [Review][Patch] P4: `hasNext` not reset before `fetchCoaches` completes — stale `hasNext=true` from a previous search causes "Load More" to appear during loading of a new city [`marketplace.store.js:fetchCoaches()`]
- [ ] [Review][Patch] P5: `Number(query.minPrice)` returns `NaN` for non-numeric URL values — `NaN` is sent to the API instead of `null` [`marketplace.store.js:syncFiltersFromRoute()`]
- [ ] [Review][Patch] P6: `coach.capabilityBadges.length` without null guard — crashes if server ever returns `null` instead of empty array [`CoachCard.vue`]
- [ ] [Review][Patch] P7: `@NotBlank` on `CoachSearchParams.city` is dead code — constraint is never triggered (actual enforcement is on `@RequestParam @NotBlank String city` in the controller) [`CoachSearchParams.java`]
- [ ] [Review][Patch] P8: Language filter pagination metadata inaccurate — `totalElements`/`totalPages`/`hasNext` reflect pre-language-filter JPA counts; run a second `COUNT` query scoped to city+language when language filter is active [Decision D1→b] [`CoachSearchService.java:searchCoaches()`, `CoachProfileRepository.java`]
- [ ] [Review][Patch] P9: "Rating" sort option active in dropdown but produces alphabetical results — disable with tooltip "Coming soon" until Epic 9 wires review data [Decision D2→a] [`MarketplacePage.vue:sortOptions`]
- [ ] [Review][Patch] P10: AC 6 empty state lacks CTA when no secondary filters active — add "Try another city" button (always shown); keep "Clear filters" button conditional on `hasActiveFilters` [Decision D4→a] [`MarketplacePage.vue:marketplace-page__empty`]

### Deferred

- [x] [Review][Defer] W1: `verificationTier` mapped as `String` — no Java type safety; DB CHECK constraint enforces values but a typo bypasses compile-time guards [`CoachProfile.java`, `CoachCardDto.java`] — deferred, pre-existing design pattern; low functional risk at current call volume
- [x] [Review][Defer] W2: `CoachReliabilityStrike.createdAt` initialised by Java field default instead of `@PrePersist` or DB-owned value — JVM clock drift risk in multi-instance deployments [`CoachReliabilityStrike.java:33`] — deferred, pre-existing pattern, minor
- [x] [Review][Defer] W3: Strike `reason` field is free text — no enum/lookup catalog; analytics require brittle string matching [`V27__marketplace_search_support.sql`] — deferred, Epic 7/10 scope
- [x] [Review][Defer] W4: `tearDown` deletes ALL rows from `refresh_tokens` and `login_attempts` (not scoped to test user IDs) — risk of interference in parallel test runs [`CoachMarketplaceResourceIT.java:tearDown()`] — deferred, pre-existing IT pattern consistent with other suites
- [x] [Review][Defer] W5: `buildSort()` silently normalises any unrecognised `sortBy` value to `displayName` — no validation or error on garbage input [`CoachSearchService.java:buildSort()`] — deferred, low risk; add `@Pattern` constraint if API is made public-facing

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- `searchCoaches_reliabilityStrikeCount_reflected`: Strike insert must be wrapped in `transactionTemplate.execute()` — HikariCP has autoCommit=false, so plain JdbcTemplate.update() without a transaction doesn't commit before the HTTP request fires. Fixed by using transactionTemplate.

### Completion Notes List

- Implemented full Coach Marketplace & Search: V27 migration, CoachReliabilityStrike entity/repo, 3 DTO records, CoachSearchSpecification (JPA Criteria), CoachSearchService with post-page enrichment, CoachMarketplaceResource with @Validated + @PreAuthorize(IS_PERMIT_ALL).
- Security: Added IS_PERMIT_ALL to SecurityConstants; added `/api/marketplace/coaches**` to AppEndpoints.PUBLIC_ENDPOINTS covering all 3 security layers.
- Language filter: PostgreSQL array ANY() is not portable in JPA Criteria — applied as a native query post-fetch on the page items via `findIdsByLanguage` native method.
- Price sort: perSessionPrice lives in a separate table, cannot be used in Pageable.Sort. Sort applied in Java on the current page (MVP limitation documented).
- Frontend: marketplace.store.js (Pinia), CoachCard.vue, VerificationBadge.vue, ReliabilityIndicator.vue, MarketplacePage.vue, CoachPublicProfilePlaceholderPage.vue, routes + i18n (4 locales, parity: OK).
- Tests: 15/15 integration tests pass; full regression suite passes (BUILD SUCCESS).

### File List

**New backend files:**
- src/main/resources/db/migration/V27__marketplace_search_support.sql
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrike.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachCardDto.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSearchParams.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSearchResponse.java
- src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchSpecification.java
- src/main/java/com/softropic/skillars/platform/marketplace/service/CoachSearchService.java
- src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java

**Modified backend files:**
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSpecialtyRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachPricingRepository.java
- src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java
- src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java

**New frontend files:**
- src/frontend/src/pages/marketplace/MarketplacePage.vue
- src/frontend/src/pages/marketplace/CoachPublicProfilePlaceholderPage.vue
- src/frontend/src/components/marketplace/CoachCard.vue
- src/frontend/src/components/marketplace/VerificationBadge.vue
- src/frontend/src/components/marketplace/ReliabilityIndicator.vue
- src/frontend/src/stores/marketplace.store.js

**Modified frontend files:**
- src/frontend/src/api/marketplace.api.js
- src/frontend/src/router/routes.js
- src/frontend/src/i18n/en/index.js
- src/frontend/src/i18n/en-US/index.js
- src/frontend/src/i18n/de/index.js
- src/frontend/src/i18n/fr-FR/index.js

**New test files:**
- src/test/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResourceIT.java
