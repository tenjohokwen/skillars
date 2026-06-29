package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class CoachMarketplaceResourceIT {

    private static final String SEARCH_ENDPOINT = "/api/marketplace/coaches";
    private static final String CLIENT_ID        = "testClientId";

    // Unique ID ranges to avoid conflicts with other ITs
    private static final long COACH_ID_1 = 9200000001L;
    private static final long COACH_ID_2 = 9200000002L;
    private static final long COACH_ID_3 = 9200000003L;
    private static final long COACH_ID_4 = 9200000004L; // DRAFT — must never appear
    private static final long COACH_ID_5 = 9200000005L; // Berlin — different city

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private HttpTestClient httpTestClient;

    @LocalServerPort
    private int randomServerPort;

    // UUID storage so we can reference them in assertions
    private UUID coachProfileId1;
    private UUID coachProfileId2;
    private UUID coachProfileId3;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (9200, 'ROLE_COACH', 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );

            // 3 ACTIVE Frankfurt coaches
            insertCoachUser(COACH_ID_1, "mkt.coach1@skillars-test.com");
            insertCoachUser(COACH_ID_2, "mkt.coach2@skillars-test.com");
            insertCoachUser(COACH_ID_3, "mkt.coach3@skillars-test.com");
            // 1 DRAFT Frankfurt coach — must never appear in search
            insertCoachUser(COACH_ID_4, "mkt.coach4.draft@skillars-test.com");
            // 1 ACTIVE Berlin coach — different city
            insertCoachUser(COACH_ID_5, "mkt.coach5.berlin@skillars-test.com");

            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "SELECT u.id, a.id FROM main.\"user\" u, main.authority a " +
                "WHERE u.id IN (?,?,?,?,?) AND a.name = 'ROLE_COACH' ON CONFLICT DO NOTHING",
                COACH_ID_1, COACH_ID_2, COACH_ID_3, COACH_ID_4, COACH_ID_5
            );

            coachProfileId1 = insertActiveCoachProfile(COACH_ID_1, "Alice Frankfurt",  "Frankfurt", "Sachsenhausen");
            coachProfileId2 = insertActiveCoachProfile(COACH_ID_2, "Bob Frankfurt",    "Frankfurt", null);
            coachProfileId3 = insertActiveCoachProfile(COACH_ID_3, "Carol Frankfurt",  "Frankfurt", "Bornheim");
            insertDraftCoachProfile(COACH_ID_4, "Draft Coach",     "Frankfurt", null);
            insertActiveCoachProfile(COACH_ID_5, "Dave Berlin",    "Berlin",    "Mitte");

            // Specialties for coach 1
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_specialties (id, coach_id, skill) VALUES (gen_random_uuid(), ?, 'Dribbling')",
                coachProfileId1
            );
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_specialties (id, coach_id, skill) VALUES (gen_random_uuid(), ?, 'Shooting')",
                coachProfileId1
            );

            // Age groups for coach 2
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_age_groups (id, coach_id, age_tier) VALUES (gen_random_uuid(), ?, 'U10')",
                coachProfileId2
            );

            // Pricing for coach 3
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 40.00, 'EUR')",
                coachProfileId3
            );

            // Language for coach 1 — set via UPDATE since profile insert uses DEFAULT for languages
            jdbcTemplate.update(
                "UPDATE marketplace.coach_profiles SET languages = '{english}' WHERE id = ?",
                coachProfileId1
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM marketplace.coach_reliability_strikes");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_subscriptions");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_availability_windows");
            jdbcTemplate.execute("DELETE FROM marketplace.session_packs");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_pricing");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_age_groups");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_specialties");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_profiles");
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.execute("DELETE FROM main.user_authority WHERE user_id IN (" +
                COACH_ID_1 + "," + COACH_ID_2 + "," + COACH_ID_3 + "," + COACH_ID_4 + "," + COACH_ID_5 + ")");
            jdbcTemplate.execute("DELETE FROM main.\"user\" WHERE id IN (" +
                COACH_ID_1 + "," + COACH_ID_2 + "," + COACH_ID_3 + "," + COACH_ID_4 + "," + COACH_ID_5 + ")");
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9200");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // ======================== TEST CASES ========================

    @Test
    void searchCoaches_missingCity_returns400() {
        assertThatThrownBy(() ->
            new RestTemplate().exchange(
                baseUrl() + SEARCH_ENDPOINT, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(clientHeaders()), Map.class
            )
        ).isInstanceOf(HttpClientErrorException.class)
         .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void searchCoaches_byCity_returnsOnlyMatchingCity() {
        ResponseEntity<Map> response = searchCoaches("Frankfurt", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // 3 ACTIVE Frankfurt coaches, not the DRAFT or Berlin coach
        assertThat((Integer) response.getBody().get("totalElements")).isEqualTo(3);
        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        assertThat(coaches).hasSize(3);
        assertThat(coaches).allMatch(c -> "Frankfurt".equalsIgnoreCase((String) c.get("city")));
    }

    @Test
    void searchCoaches_draftCoachExcluded() {
        ResponseEntity<Map> response = searchCoaches("Frankfurt", null);

        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        assertThat(coaches).noneMatch(c -> "Draft Coach".equals(c.get("displayName")));
        assertThat((Integer) response.getBody().get("totalElements")).isEqualTo(3);
    }

    @Test
    void searchCoaches_filterByAgeGroup_returnsOnlyMatchingCoaches() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&ageGroup=U10",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        // Only coach 2 has U10 age group
        assertThat(coaches).hasSize(1);
    }

    @Test
    void searchCoaches_filterBySkill_returnsOnlyMatchingCoaches() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&skill=Dribbling",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        // Only coach 1 has Dribbling specialty
        assertThat(coaches).hasSize(1);
        assertThat(coaches.get(0).get("displayName")).isEqualTo("Alice Frankfurt");
    }

    @Test
    void searchCoaches_filterByPriceRange_returnsOnlyMatchingCoaches() {
        // Coach 3 has per_session_price = 40.00; coaches 1 and 2 have no pricing row → BigDecimal.ZERO
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&minPrice=35&maxPrice=50",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        // Only coach 3 has pricing in the range
        assertThat(coaches).hasSize(1);
        assertThat(coaches.get(0).get("displayName")).isEqualTo("Carol Frankfurt");
    }

    @Test
    void searchCoaches_filterByLanguage_returnsOnlyMatchingCoaches() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&language=English",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        // Only coach 1 has english language set
        assertThat(coaches).hasSize(1);
        assertThat(coaches.get(0).get("displayName")).isEqualTo("Alice Frankfurt");
    }

    @Test
    void searchCoaches_noMatches_returnsEmptyPageNotError() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Hamburg",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) response.getBody().get("totalElements")).isEqualTo(0);
        assertThat((List<?>) response.getBody().get("coaches")).isEmpty();
    }

    @Test
    void searchCoaches_paginationPage0_returnsSizeCoaches() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&page=0&size=2",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) response.getBody().get("page")).isEqualTo(0);
        assertThat((List<?>) response.getBody().get("coaches")).hasSize(2);
        assertThat((Boolean) response.getBody().get("hasNext")).isTrue();
        assertThat((Integer) response.getBody().get("totalElements")).isEqualTo(3);
    }

    @Test
    void searchCoaches_paginationPage1_returnsNextCoaches() {
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&page=1&size=2",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) response.getBody().get("page")).isEqualTo(1);
        assertThat((List<?>) response.getBody().get("coaches")).hasSize(1);
        assertThat((Boolean) response.getBody().get("hasNext")).isFalse();
    }

    @Test
    void searchCoaches_unauthenticated_returns200() {
        // Truly anonymous request — no X-Client-Id, no JWT, no cookies (AC 5, FR-MKT-005)
        HttpHeaders anonHeaders = new HttpHeaders();
        anonHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt",
            HttpMethod.GET, null, anonHeaders, Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) response.getBody().get("totalElements")).isEqualTo(3);
    }

    @Test
    void searchCoaches_reliabilityStrikeCount_reflected() {
        // Insert one strike for coach 1 within the 90-day window (explicit transaction ensures commit)
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_reliability_strikes (id, coach_id, reason, created_at) " +
                "VALUES (gen_random_uuid(), ?, 'Late cancellation', now())",
                coachProfileId1
            );
            return null;
        });

        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&skill=Dribbling",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        assertThat(coaches).hasSize(1);
        assertThat(coaches.get(0).get("displayName")).isEqualTo("Alice Frankfurt");
        assertThat((Integer) coaches.get(0).get("reliabilityStrikeCount")).isEqualTo(1);
    }

    @Test
    void searchCoaches_aggregateRating_defaultsToZero() {
        ResponseEntity<Map> response = searchCoaches("Frankfurt", null);

        List<Map<String, Object>> coaches = (List<Map<String, Object>>) response.getBody().get("coaches");
        assertThat(coaches).allMatch(c -> ((Number) c.get("averageRating")).doubleValue() == 0.0);
        assertThat(coaches).allMatch(c -> ((Integer) c.get("reviewCount")) == 0);
    }

    @Test
    void searchCoaches_invalidAgeGroup_returns400() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&ageGroup=INVALID",
            HttpMethod.GET, null, clientHeaders(), Map.class
        )).isInstanceOf(HttpClientErrorException.class)
         .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void searchCoaches_minRatingFilter_returnsEmptyPage() {
        // All coaches have aggregateRating = 0.0 until Epic 9 — any minRating > 0 must return empty
        ResponseEntity<Map> response = httpTestClient.makeHttpRequest(
            baseUrl() + SEARCH_ENDPOINT + "?city=Frankfurt&minRating=3.0",
            HttpMethod.GET, null, clientHeaders(), Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) response.getBody().get("totalElements")).isEqualTo(0);
        assertThat((List<?>) response.getBody().get("coaches")).isEmpty();
    }

    // ======================== HELPERS ========================

    private ResponseEntity<Map> searchCoaches(String city, String extraParams) {
        String url = baseUrl() + SEARCH_ENDPOINT + "?city=" + city;
        if (extraParams != null) url += "&" + extraParams;
        return httpTestClient.makeHttpRequest(url, HttpMethod.GET, null, clientHeaders(), Map.class);
    }

    private UUID insertActiveCoachProfile(long userId, String displayName, String city, String district) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, district, canonical_timezone, status, verification_tier, created_at) " +
            "VALUES (?, ?, ?, 'Test bio', ?, ?, 'Europe/Berlin', 'ACTIVE', 'BASIC', now())",
            id, userId, displayName, city, district
        );
        return id;
    }

    private void insertDraftCoachProfile(long userId, String displayName, String city, String district) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_profiles " +
            "(id, user_id, display_name, bio, city, district, canonical_timezone, status, verification_tier, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, 'Test bio', ?, ?, 'Europe/Berlin', 'DRAFT', 'BASIC', now())",
            userId, displayName, city, district
        );
    }

    private void insertCoachUser(long id, String email) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-03-15', ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "670" + (id % 10000000),
            email
        );
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }
}
