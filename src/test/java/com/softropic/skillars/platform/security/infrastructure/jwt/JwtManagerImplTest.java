package com.softropic.skillars.platform.security.infrastructure.jwt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.security.config.SimpleGrantedAuthorityMixin;
import com.softropic.skillars.infrastructure.security.CookieUtil;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.contract.exception.JWTExpiredException;
import com.softropic.skillars.platform.security.contract.exception.MissingClientIdException;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.service.SecretService;
import com.softropic.skillars.platform.security.repo.Secret;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.net.HttpCookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.ADMIN_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.API_KEY_HEADER;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.BUS_ID;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.B_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.CLIENT_ID;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.DB_REFRESH_TOKEN;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.DB_REFRESH_TOKEN_INTERVAL;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.DISPLAY_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.GENDER;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_COOKIE_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_SESSION_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_TTL;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.ROLES;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.SESSION_ID;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.SESSION_REFRESH_COUNTDOWN;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.USER_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityError.MISSING_JWT_DB_REFRESH_TOKEN;
import static com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants.ANONYMOUS;
import static io.jsonwebtoken.Claims.SUBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;


import static com.softropic.skillars.infrastructure.security.SecurityConstants.ANONYMOUS_SESSION_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.LOGIN_INFO_ID;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.OTP_TTL;
import static com.softropic.skillars.infrastructure.security.SecurityError.MISSING_JWT_PRINCIPAL;
import static org.assertj.core.api.Assertions.assertThatCode;

public class JwtManagerImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String       OPF_SEED        = "opfSeed";
    private static final String       USER_AGENT_HASH = "userAgent";
    public static final String ROLE_USER = "ROLE_USER";

    static {
        MAPPER.setVisibility(MAPPER.getSerializationConfig()
                                   .getDefaultVisibilityChecker()
                                   .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                                   .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                                   .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                                   .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
        // Ensure SimpleGrantedAuthorityMixin is accessible. This might require moving the Mixin or adjusting its visibility.
        // If SimpleGrantedAuthorityMixin is an inner class of JwtManagerImpl, it needs to be static and public,
        // or moved to its own file, or this test needs to be a nested test class to access it if it's package-private/protected.
        // For now, assuming it's resolvable.
        MAPPER.addMixIn(SimpleGrantedAuthority.class, SimpleGrantedAuthorityMixin.class);
    }

    @Mock
    private SecretService secretService;

    private final Secret secret = new Secret("v1", "jot");

    private JwtManagerImpl jwtManager;
    private ClaimsExtractor claimsExtractor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(secretService.fetchSecret(anyString(), anyString())).thenReturn(secret);

        JwtConfiguration jwtConfiguration = new JwtConfiguration(secretService);
        TokenCreator tokenCreator = new TokenCreatorImpl(jwtConfiguration);
        this.claimsExtractor = new ClaimsExtractorImpl(jwtConfiguration);
        TokenValidator tokenValidator = new TokenValidatorImpl(claimsExtractor);

        // Instantiate JwtManagerImpl with its dependencies
        jwtManager = new JwtManagerImpl(tokenCreator, tokenValidator, claimsExtractor, jwtConfiguration);

        TestClockProvider.setSystemClock();
    }

    @AfterEach
    void tearDown() {
        reset(secretService);
        RequestMetadataProvider.cleanup();
    }

    // Helper to build a token with custom claims for testing invalid/malformed scenarios
    private String buildTokenWithCustomClaims(Map<String, Object> customClaims, Instant expirationInstant, Secret secret) {
        return Jwts.builder()
                .claims(customClaims)
                .issuedAt(Date.from(Instant.now(TestClockProvider.getClock()))) // use fixed clock
                .expiration(Date.from(expirationInstant))
                .signWith(Keys.hmacShaKeyFor(secret.getSecretBytes()))
                .compact();
    }
    

    @Test
    void testGenerateToken_whenClientIdNotSet_shouldThrowMissingClientIdException() {
        RequestMetadataProvider.cleanup();
        // 1. Create a Principal object
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority("ROLE_ADMIN"));
        final Principal principal = createPrincipal(authorities);

        // 2. Call jwtManager.generateToken
        String opfSeed = UUID.randomUUID().toString();
        assertThrows(MissingClientIdException.class, () -> jwtManager.generateToken(principal, opfSeed));
    }

    private static Principal createPrincipal(Set<SimpleGrantedAuthority> authorities) {
        return new Principal.Builder()
                .accountNonExpired(true)
                .accountNonLocked(true)
                .authorities(authorities)
                .username("testUser")
                .password("password")
                .enabled(true)
                .otpEnabled(true)
                .gender(Gender.MALE)
                .displayName("TestUserDisplayName")
                .businessId("bus123")
                .build();
    }

    @Test
    void testGenerateToken_success() throws JsonProcessingException {
        // 0. Init requestMetadata
        initRequestMetadata();

        // 1. Create a Principal object
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority("ROLE_ADMIN"));
        final Principal principal = createPrincipal(authorities);

        // 2. Call jwtManager.generateToken
        String opfSeed = UUID.randomUUID().toString();
        String token = jwtManager.generateToken(principal, opfSeed);
        assertThat(token).isNotNull();

        // 3. Parse the token
        final SecretKey secretKey = Keys.hmacShaKeyFor(secret.getSecretBytes());
        Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();

        // 4. Assert claims
        assertThat(claims.getSubject()).isEqualTo(principal.getUsername());
        Set<SimpleGrantedAuthority> actualAuthorities = MAPPER.readValue(claims.get(ROLES, String.class),
                                                                         new TypeReference<>() {
                                                                         });
        assertThat(actualAuthorities).isEqualTo(authorities);
        assertThat(claims.get(GENDER, String.class)).isEqualTo(principal.getGender().toString());
        assertThat(claims.get(DISPLAY_NAME, String.class)).isEqualTo(principal.getDisplayName());
        assertThat(claims.get(BUS_ID, String.class)).isEqualTo(principal.getBusinessId()); // JwtManagerImpl stores it as Long
        assertThat(claims.get(OPF_SEED, String.class)).isEqualTo(opfSeed);
        assertThat(claims.get(DB_REFRESH_TOKEN, Long.class)).isNotNull().isPositive();
        Instant expectedIssuedAt = Instant.now(TestClockProvider.getClock());
        Instant actualIssuedAt = claims.getIssuedAt().toInstant();
        assertThat(actualIssuedAt).isCloseTo(expectedIssuedAt, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        Instant expectedExpiration = expectedIssuedAt.plus(JWT_TTL);
        Instant actualExpiration = claims.getExpiration().toInstant();
        assertThat(actualExpiration).isCloseTo(expectedExpiration, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        assertThat(claims.get(USER_AGENT_HASH, String.class)).isEqualTo(String.valueOf(clientInfo.getUserAgent().hashCode()));
        assertThat(claims.get(CLIENT_ID, String.class)).isEqualTo(clientInfo.getApiKey());
    }

    private static void initRequestMetadata() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-FORWARDED-FOR")).thenReturn("139.23.24.5");
        final String userAgent = "useragent";
        when(request.getHeader("user-agent")).thenReturn(userAgent);
        final String apiKey = "API_KEY";
        when(request.getHeader(API_KEY_HEADER)).thenReturn(apiKey);
        final String sessionId = "test-session-id";
        when(request.getSession(true)).thenReturn(new org.springframework.mock.web.MockHttpSession(null, sessionId));
        when(request.getRequestURL()).thenReturn(new StringBuffer("http:/local/host/path"));
        RequestMetadataProvider.initRequestMetadata(request);
    }


    @Test
    void testCreateLoginToken_success() throws JsonProcessingException {
        // 1. Mock HttpServletResponse and HttpServletRequest
        HttpServletResponse mockResponse = new MockHttpServletResponse();

        // 2. Mock RequestMetadata and RequestMetadataProvider
        final RequestMetadata mockRequestMetadata = mock(RequestMetadata.class);
        when(mockRequestMetadata.getUserAgent()).thenReturn("TestUserAgent");
        when(mockRequestMetadata.getClientIdentifier()).thenReturn("TestClientIdentifier");
         // getClientId in toClaims uses getFingerprintCookie if available, then getClientIdentifier
        final String browserCookieValue = "TestClientFingerprintCookie";
        when(mockRequestMetadata.getFingerprintCookie()).thenReturn(browserCookieValue);
        final String mockSessionId = "mockSessionId";
        when(mockRequestMetadata.getSessionId()).thenReturn(mockSessionId);


        // 3. Use Mockito.mockStatic for CookieUtil and RequestMetadataProvider
        try (MockedStatic<RequestMetadataProvider> mockedMetadataProvider = Mockito.mockStatic(RequestMetadataProvider.class)) {

            mockedMetadataProvider.when(RequestMetadataProvider::getClientInfo).thenReturn(mockRequestMetadata);


            // 4. Create a sample Principal
            Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("ROLE_GUEST"));
            final Principal principal = createPrincipal(authorities);


            // 5. Call jwtManager.createLoginToken
            // createLoginToken generates a new opfSeed internally, so we don't provide one.
            jwtManager.createLoginToken(mockResponse, principal);

            final String jwt = extractCookie(mockResponse, JWT_COOKIE_NAME);
            final Claims claims = claimsExtractor.extractClaims(jwt);
            assertThat(claims.getSubject()).isEqualTo(principal.getUsername());
            Set<SimpleGrantedAuthority> actualAuthorities = MAPPER.readValue(claims.get(ROLES, String.class), new TypeReference<>() {});
            assertThat(actualAuthorities).isEqualTo(authorities);
            assertThat(claims.get(SUBJECT, String.class)).isEqualTo(principal.getUsername());
            assertThat(claims.get(GENDER, String.class)).isEqualTo(principal.getGender().toString());
            assertThat(claims.get(DISPLAY_NAME, String.class)).isEqualTo(principal.getDisplayName());
            assertThat(claims.get(SESSION_ID, String.class)).isEqualTo(mockSessionId);
            assertThat(claims.get(BUS_ID, String.class)).isEqualTo(principal.getBusinessId());
            assertThat(claims.get(DB_REFRESH_TOKEN, Long.class)).isNotNull().isPositive(); // Generated by SQIDS
            assertThat(claims.containsKey(USER_AGENT_HASH)).isTrue();
            assertThat(claims.get(USER_AGENT_HASH, String.class)).isNotNull().isNotBlank();
            //assertThat(claims).containsKey(OPF_SEED); // OPF_SEED is generated internally for login tokens
            assertThat(claims.get(CLIENT_ID, String.class)).isEqualTo(browserCookieValue);
            final String roles = (String) claims.get(ROLES);
            final List<SimpleGrantedAuthority> grantedAuthorities = MAPPER.readValue(roles, new TypeReference<>() {});
            assertThat(authorities).containsAll(grantedAuthorities);

            final String bCookieValueFromHeader = extractCookie(mockResponse, B_COOKIE);
            assertThat(bCookieValueFromHeader).isEqualTo(browserCookieValue);
            final String userCookieValueFromHeader = extractCookie(mockResponse, USER_COOKIE);
            assertThat(userCookieValueFromHeader).isEqualTo(principal.getDisplayName());
            final String sessionTimeoutWarningInterval = extractCookie(mockResponse, SESSION_REFRESH_COUNTDOWN);
            assertThat(sessionTimeoutWarningInterval).isEqualTo(String.valueOf(JWT_TTL.minusMinutes(5).toMillis()));
        }
    }

    @Test
    void testCreateLoginToken_adminRole_shouldSetAdminCookie() throws JsonProcessingException {
        HttpServletResponse mockResponse = new MockHttpServletResponse();
        initRequestMetadata();

        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        Principal principal = createPrincipal(authorities);

        jwtManager.createLoginToken(mockResponse, principal);

        String adminCookie = extractCookie(mockResponse, ADMIN_COOKIE);
        assertThat(adminCookie).isEqualTo("admin");
    }

    private static String extractCookie(HttpServletResponse mockResponse, String cookieName) {
        final String jwtKeyValue = mockResponse.getHeaders("Set-Cookie")
                                               .stream()
                                               .filter(str -> str.contains(cookieName+"="))
                                               .findFirst()
                                               .orElseThrow(() -> new RuntimeException("Cookie not found: " + cookieName));

        final List<HttpCookie> cookies = HttpCookie.parse(jwtKeyValue);
        HttpCookie httpCookie = cookies.get(0);
        return httpCookie.getValue();
    }

    // --- extractPrincipal Tests ---

    @Test
    void testExtractPrincipal_success() {
        // 1. prepare requestMetadata
        initRequestMetadata();

        // 2. Generate a valid token string
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER));
        Principal originalPrincipal = createPrincipal(authorities);
        String opfSeed = UUID.randomUUID().toString();
        String token = jwtManager.generateToken(originalPrincipal, opfSeed);

        // 3. Mock HttpServletRequest and CookieUtil.getCookieValue
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);

            // 4. Call jwtManager.extractPrincipal
            Principal extractedPrincipal = jwtManager.extractPrincipal(mockRequest);

            // 5. Assert the returned Principal
            assertThat(extractedPrincipal).isNotNull();
            assertThat(extractedPrincipal.getUsername()).isEqualTo(originalPrincipal.getUsername());
            assertThat(extractedPrincipal.getAuthorities()).containsExactlyInAnyOrderElementsOf(originalPrincipal.getAuthorities());
            assertThat(extractedPrincipal.getGender()).isEqualTo(originalPrincipal.getGender());
            assertThat(extractedPrincipal.getDisplayName()).isEqualTo(originalPrincipal.getDisplayName());
            assertThat(extractedPrincipal.getBusinessId()).isEqualTo(originalPrincipal.getBusinessId());
            // opfSeed is not part of the Principal object itself, but is in the token.
            // The Principal object reconstruction from claims does not include opfSeed.
        }
    }

    @Test
    void testExtractPrincipal_ExpectAnonymous() {
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(null);
            Principal principal = jwtManager.extractPrincipal(mockRequest);
            assertThat(principal).isNotNull().matches(p -> p.getUsername().equals(ANONYMOUS) && p.getAuthorities().isEmpty());

            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn("");
            principal = jwtManager.extractPrincipal(mockRequest);
            assertThat(principal).isNotNull().matches(p -> p.getUsername().equals(ANONYMOUS) && p.getAuthorities().isEmpty());
        }
    }

     @Test
    void testExtractPrincipal_invalidTokenSignature() {
         initRequestMetadata();

        String token = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ0ZXN0VXNlciJ9.invalidSignature"; // Tampered token

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);

            InvalidJWTDataException exception = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidJWTDataException.class,
                () -> jwtManager.extractPrincipal(mockRequest)
            );
            assertThat(exception.getMessage()).contains("The Jwt token could not be parsed"); // Or check for specific error code if available
            assertThat(exception.getCause()).isInstanceOf(SignatureException.class);
        }
    }

    @Test
    void testExtractPrincipal_expiredToken() {
        initRequestMetadata();

        // 1. Create a Principal and generate a token
        final Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER));
        Principal originalPrincipal = createPrincipal(authorities);
        //set time in the past so that an expired token is created
        // This is the only way to test this since the jjwt lib uses Date() (the old java class) instead of the new DateTime lib
        TestClockProvider.setClock(Clock.offset(TestClockProvider.getClock(), Duration.ofSeconds(-(JWT_TTL.toSeconds()+1))));
        String token = jwtManager.generateToken(originalPrincipal, UUID.randomUUID().toString());


        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);

            // 3. Assert JWTExpiredException
            JWTExpiredException exception = org.junit.jupiter.api.Assertions.assertThrows(
                JWTExpiredException.class,
                () -> jwtManager.extractPrincipal(mockRequest)
            );
            assertThat(exception.getMessage()).contains("The JWT has expired");
        }
    }

    @Test
    void testExtractPrincipal_malformedRolesClaim() {
        initRequestMetadata();

        Map<String, Object> claims = new HashMap<>();
        claims.put(ROLES, "{not_a_valid_json_array]"); // Malformed roles claim
        claims.put(SUBJECT, "userWithMalformedRoles");
        claims.put(GENDER, Gender.FEMALE.toString());
        claims.put(DISPLAY_NAME, "Malformed Display");
        claims.put(BUS_ID, 123L);
        claims.put(OPF_SEED, UUID.randomUUID().toString());
        claims.put(DB_REFRESH_TOKEN, Instant.now(ClockProvider.getClock()).plus(DB_REFRESH_TOKEN_INTERVAL).toEpochMilli());

        String token = buildTokenWithCustomClaims(claims, Instant.now(ClockProvider.getClock()).plus(JWT_TTL), secret);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);

            InvalidJWTDataException exception = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidJWTDataException.class,
                () -> jwtManager.extractPrincipal(mockRequest)
            );
            // Check for a message that indicates roles deserialization failed.
            // The exact message might depend on Jackson's error reporting.
            assertThat(exception.getMessage()).contains("Attempt to read roles from claims failed");
            assertThat(exception.getCause()).isInstanceOf(JsonProcessingException.class);
        }
    }

    // --- deleteLoginToken Test ---
    @Test
    void testDeleteLoginToken_success() {
        HttpServletResponse mockResponse = new MockHttpServletResponse();
        jwtManager.deleteLoginToken(mockResponse);

        final List<String> loginCookieNames = List.of(JWT_COOKIE_NAME,
                                                      B_COOKIE,
                                                      USER_COOKIE,
                                                      ADMIN_COOKIE,
                                                      JWT_SESSION_COOKIE,
                                                      SESSION_REFRESH_COUNTDOWN);
        final Collection<String> headers = mockResponse.getHeaders("Set-Cookie");
        final List<String> actualCookieNames = headers.stream()
                                         .map(HttpCookie::parse)
                                         .flatMap(Collection::stream)
                                         .map(HttpCookie::getName)
                                         .toList();
        assertThat(actualCookieNames).containsAll(loginCookieNames).hasSize(loginCookieNames.size());
        headers.stream().map(HttpCookie::parse).flatMap(Collection::stream).forEach(httpCookie -> {
            assertThat(httpCookie.getValue()).isBlank();
            assertThat(httpCookie.getMaxAge()).isZero();
        });
    }

    // --- renewLoginToken Test ---
    @Test
    void testRenewLoginToken_success() throws JsonProcessingException {
        initRequestMetadata();

        HttpServletResponse mockResponse = new MockHttpServletResponse();


        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("ROLE_RENEWER"));
        Principal principal = createPrincipal(authorities);

        jwtManager.renewLoginToken(mockResponse, principal);

        final String jwt = extractCookie(mockResponse, JWT_COOKIE_NAME);
        final Claims claims = claimsExtractor.extractClaims(jwt);
        assertThat(claims.getSubject()).isEqualTo(principal.getUsername());
        Set<SimpleGrantedAuthority> actualAuthorities = MAPPER.readValue(claims.get(ROLES, String.class), new TypeReference<>() {});
        assertThat(actualAuthorities).isEqualTo(authorities);
        assertThat(claims.get(SUBJECT, String.class)).isEqualTo(principal.getUsername());
        assertThat(claims.get(GENDER, String.class)).isEqualTo(principal.getGender().toString());
        assertThat(claims.get(DISPLAY_NAME, String.class)).isEqualTo(principal.getDisplayName());
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        assertThat(claims.get(SESSION_ID, String.class)).isEqualTo(clientInfo.getSessionId());
        assertThat(claims.get(BUS_ID, String.class)).isEqualTo(principal.getBusinessId());
        assertThat(claims.get(DB_REFRESH_TOKEN, Long.class)).isNotNull().isPositive(); // Generated by SQIDS
        assertThat(claims.containsKey(USER_AGENT_HASH)).isTrue();
        assertThat(claims.get(USER_AGENT_HASH, String.class)).isNotNull().isNotBlank();
        //assertThat(claims).containsKey(OPF_SEED); // OPF_SEED is generated internally for login tokens
        assertThat(claims.get(CLIENT_ID, String.class)).isEqualTo(clientInfo.getClientIdentifier());
        final String roles = (String) claims.get(ROLES);
        final List<SimpleGrantedAuthority> grantedAuthorities = MAPPER.readValue(roles, new TypeReference<>() {});
        assertThat(authorities).containsAll(grantedAuthorities);

        final String userCookieValueFromHeader = extractCookie(mockResponse, USER_COOKIE);
        assertThat(userCookieValueFromHeader).isEqualTo(principal.getDisplayName());
        final String sessionTimeoutWarningInterval = extractCookie(mockResponse, SESSION_REFRESH_COUNTDOWN);
        assertThat(sessionTimeoutWarningInterval).isEqualTo(String.valueOf(JWT_TTL.minusMinutes(5).toMillis()));

        Instant expectedIssuedAt = Instant.now(ClockProvider.getClock());
        assertThat(claims.getIssuedAt().toInstant()).isCloseTo(expectedIssuedAt, org.assertj.core.api.Assertions.within(2, ChronoUnit.SECONDS));
        Instant expectedExpiration = expectedIssuedAt.plus(JWT_TTL);
        assertThat(claims.getExpiration().toInstant()).isCloseTo(expectedExpiration, org.assertj.core.api.Assertions.within(2, ChronoUnit.SECONDS));
    }

    // --- refreshLoginToken Tests ---
    @Test
    void testRefreshLoginToken_success() throws JsonProcessingException {
        initRequestMetadata();

        // 1. Generate initial principal and token
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority("ROLE_REFRESH_USER"));
        Principal originalPrincipal = createPrincipal(authorities);
        String opfSeedOriginal = UUID.randomUUID().toString();

        String initialTokenString = jwtManager.generateToken(originalPrincipal, opfSeedOriginal);


        Claims initialClaims = claimsExtractor.extractClaims(initialTokenString);
        Long originalDbRefreshToken = initialClaims.get(DB_REFRESH_TOKEN, Long.class);

        // 2. Mock response and CookieUtil
        HttpServletResponse mockResponse = new MockHttpServletResponse();

        // 3. Advance clock slightly to ensure new iat/exp
        Instant refreshInstant = Instant.now(ClockProvider.getClock());

        // 4. Call refreshLoginToken
        jwtManager.refreshLoginToken(mockResponse, initialTokenString);

        // 5. Verify CookieUtil.addCookie and capture the new token
        final String jwt = extractCookie(mockResponse, JWT_COOKIE_NAME);
        final Claims refreshedClaims = claimsExtractor.extractClaims(jwt);


        // 6. Parse and assert claims of the refreshed token

        assertThat(refreshedClaims.getSubject()).isEqualTo(originalPrincipal.getUsername());
        assertThat(MAPPER.readValue(refreshedClaims.get(ROLES, String.class),
                                    new TypeReference<Set<SimpleGrantedAuthority>>() {
                                    })).isEqualTo(authorities);
        assertThat(refreshedClaims.get(GENDER, String.class)).isEqualTo(originalPrincipal.getGender().toString());
        assertThat(refreshedClaims.get(DISPLAY_NAME, String.class)).isEqualTo(originalPrincipal.getDisplayName());
        assertThat(refreshedClaims.get(BUS_ID, String.class)).isEqualTo(originalPrincipal.getBusinessId());
        assertThat(refreshedClaims.get(OPF_SEED, String.class)).isEqualTo(initialClaims.get(OPF_SEED,
                                                                                            String.class)); // OPF Seed should be preserved on refresh

        // Assert new iat and dbRefreshToken
        assertThat(refreshedClaims.getIssuedAt().toInstant()).isCloseTo(refreshInstant,
                                                                        org.assertj.core.api.Assertions.within(1,
                                                                                                               ChronoUnit.SECONDS));
        assertThat(refreshedClaims.getExpiration().toInstant()).isCloseTo(refreshInstant.plus(JWT_TTL),
                                                                          org.assertj.core.api.Assertions.within(1,
                                                                                                                 ChronoUnit.SECONDS));
        assertThat(refreshedClaims.get(DB_REFRESH_TOKEN, Long.class)).isNotNull().isNotEqualTo(originalDbRefreshToken);
        // The new DB_REFRESH_TOKEN should be based on the new 'iat' + JWT_REFRESH_TTL
        // SQIDS encoding might cause slight variations, so check it's roughly what we expect or greater than original.
        // For simplicity, checking it's greater than original and positive.
        assertThat(refreshedClaims.get(DB_REFRESH_TOKEN, Long.class)).isPositive()
                                                                     .isGreaterThan(originalDbRefreshToken);

        // Check client specific claims are present
        assertThat(refreshedClaims.get(CLIENT_ID, String.class)).isEqualTo(RequestMetadataProvider.getClientInfo()
                                                                                                  .getClientIdentifier());

    }

    @Test
    void testRefreshLoginToken_invalidInitialToken() {
        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            // This should ideally throw an exception which is caught and logged by JwtManagerImpl,
            // and then it proceeds to effectively do nothing regarding cookie setting.
            // Or, if it's more robust, it might call deleteLoginToken.
            // Based on current code, it logs and does not set cookies.
            
            assertThrows(InvalidJWTDataException.class, () -> jwtManager.refreshLoginToken(mockResponse, "invalid.token.string"));

            // Verify that addCookie was NOT called for JWT_COOKIE_NAME
            // (or any cookie if that's the behavior for invalid token)
            mockedCookieUtil.verifyNoInteractions(); // Or more specifically, verify addCookie was not called
                                                    // if deleteLoginToken might be called internally.
                                                    // If it calls delete, then verify removeCookie was called.
                                                    // Current implementation: it logs and returns, so no cookie interactions.
        }
    }

    // --- extendTtlOfToken Tests ---
    @Test
    void testExtendTtlOfToken_success() throws JsonProcessingException {
        initRequestMetadata();

        // 1. Generate initial principal and token
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER));
        Principal originalPrincipal = createPrincipal(authorities);

        String opfSeedOriginal = UUID.randomUUID().toString();
        String initialTokenString = jwtManager.generateToken(originalPrincipal, opfSeedOriginal);

        Claims initialClaims = claimsExtractor.extractClaims(initialTokenString);
        Long originalDbRefreshToken = initialClaims.get(DB_REFRESH_TOKEN, Long.class);
        //String originalOpfSeed = initialClaims.get(OPF_SEED, String.class);


        final MockHttpServletRequest mockHttpServletRequest = new MockHttpServletRequest();
        final MockHttpServletResponse mockHttpServletResponse = new MockHttpServletResponse();
        // 3. Advance clock slightly
        TestClockProvider.setClock(Clock.offset(TestClockProvider.getClock(), Duration.ofSeconds(20)));
        Instant extendInstant = Instant.now(ClockProvider.getClock());

        final Cookie cookie = new Cookie(JWT_COOKIE_NAME, initialTokenString);
        mockHttpServletRequest.setCookies(cookie);

        // 4. Call extendTtlOfToken
        jwtManager.extendTtlOfToken(mockHttpServletRequest, mockHttpServletResponse);

        // 5. Parse and assert claims of the extended token
        final String jwt = extractCookie(mockHttpServletResponse, JWT_COOKIE_NAME);
        Claims extendedClaims = claimsExtractor.extractClaims(jwt);

        assertThat(extendedClaims.getSubject()).isEqualTo(originalPrincipal.getUsername());
        assertThat(MAPPER.readValue(extendedClaims.get(ROLES, String.class), new TypeReference<Set<SimpleGrantedAuthority>>() {})).isEqualTo(authorities);
        assertThat(extendedClaims.get(DB_REFRESH_TOKEN, Long.class)).isEqualTo(originalDbRefreshToken); // DB_REFRESH_TOKEN must be the same
        //assertThat(extendedClaims.get(OPF_SEED, String.class)).isEqualTo(originalOpfSeed); // OPF_SEED should also be the same

        assertThat(extendedClaims.getIssuedAt().toInstant()).isCloseTo(extendInstant, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
        assertThat(extendedClaims.getExpiration().toInstant()).isCloseTo(extendInstant.plus(JWT_TTL), org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
            
        assertThat(extendedClaims.get(CLIENT_ID, String.class)).isEqualTo(RequestMetadataProvider.getClientInfo().getClientIdentifier());

    }

    @Test
    void testExtendTtlOfToken_anonymousPrincipal_shouldThrowException() {
        initRequestMetadata();
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            // No cookie -> Anonymous principal
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(null);

            InvalidJWTDataException exception = org.junit.jupiter.api.Assertions.assertThrows(
                    InvalidJWTDataException.class,
                    () -> jwtManager.extendTtlOfToken(mockRequest, mockResponse)
            );
            assertThat(exception.getErrorCode()).isEqualTo(MISSING_JWT_PRINCIPAL);
        }
    }

    @Test
    void testExtendTtlOfToken_missingDbRefreshToken_shouldThrowException() {
        initRequestMetadata();
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER));
        Principal principal = createPrincipal(authorities);

        // Build token WITHOUT dbRefreshToken
        Map<String, Object> claims = new HashMap<>();
        claims.put(SUBJECT, principal.getUsername());
        claims.put(ROLES, "[]");
        String token = buildTokenWithCustomClaims(claims, Instant.now(ClockProvider.getClock()).plus(JWT_TTL), secret);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);

            InvalidJWTDataException exception = org.junit.jupiter.api.Assertions.assertThrows(
                    InvalidJWTDataException.class,
                    () -> jwtManager.extendTtlOfToken(mockRequest, mockResponse)
            );
            assertThat(exception.getErrorCode()).isEqualTo(MISSING_JWT_DB_REFRESH_TOKEN);
        }
    }

    // --- isTokenFixed Tests ---
    @ParameterizedTest
    @MethodSource("claimsData")
    void testIsTokenFixed_userAgentMismatch(Map<String, Object> claims, Boolean expectedOutcome) {
        //create requestMetadata initialized in dataProvider method (claimsData)
        //initRequestMetadata();

        String token = buildTokenWithCustomClaims(claims, Instant.now(ClockProvider.getClock()).plus(JWT_TTL), secret);

        final MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        final Cookie cookie = new Cookie(JWT_COOKIE_NAME, token);
        mockRequest.setCookies(cookie);

        assertThat(jwtManager.isTokenFixed(mockRequest)).isEqualTo(expectedOutcome);
    }

    private static Stream<Arguments> claimsData() {
        initRequestMetadata();

        Map<String, Object> expectedClaims = new HashMap<>();
        expectedClaims.put(USER_AGENT_HASH, String.valueOf(RequestMetadataProvider.getClientInfo().getUserAgent().hashCode()));
        expectedClaims.put(CLIENT_ID, RequestMetadataProvider.getClientInfo().getClientIdentifier());
        expectedClaims.put(SESSION_ID, RequestMetadataProvider.getClientInfo().getSessionId());

        final HashMap<String, Object> userAgentMismatch = new HashMap<>(expectedClaims);
        userAgentMismatch.put(USER_AGENT_HASH, "123");
        final HashMap<String, Object> clientIdMismatch = new HashMap<>(expectedClaims);
        clientIdMismatch.put(CLIENT_ID, "clientId123");
        final HashMap<String, Object> sessionIdMismatch = new HashMap<>(expectedClaims);
        sessionIdMismatch.put(SESSION_ID, "sessionId123");

        return Stream.of(
                Arguments.of(expectedClaims, Boolean.FALSE),
                Arguments.of(userAgentMismatch, Boolean.TRUE),
                Arguments.of(clientIdMismatch, Boolean.TRUE),
                Arguments.of(sessionIdMismatch, Boolean.TRUE)
        );
    }


    // --- hasDbRefreshTokenExpired Tests ---
    @ParameterizedTest
    @MethodSource("dbRefreshData")
    void testHasDbRefreshTokenExpired_true(Long dbRefreshInterval, Boolean expired) {
        initRequestMetadata();

        Map<String, Object> claims = new HashMap<>();
        claims.put(SUBJECT, "dbRefreshUser");
        // DB_REFRESH_TOKEN is in the past relative to current Clock
        claims.put(DB_REFRESH_TOKEN, dbRefreshInterval);
        claims.put(ROLES, "[{\"authority\":\"ROLE_USER\"}]");

        String token = buildTokenWithCustomClaims(claims, Instant.now(ClockProvider.getClock()).plus(JWT_TTL), secret);

        final MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        final Cookie cookie = new Cookie(JWT_COOKIE_NAME, token);
        mockRequest.setCookies(cookie);

        assertThat(jwtManager.hasDbRefreshTokenExpired(mockRequest)).isEqualTo(expired);
    }

    private static Stream<Arguments> dbRefreshData() {
        final Instant instant = ClockProvider.getClock().instant();
        return Stream.of(
                Arguments.of(instant.plusSeconds(1000).toEpochMilli(), Boolean.FALSE),
                Arguments.of(instant.minusSeconds(1000).toEpochMilli(), Boolean.TRUE)
        );
    }


    @Test
    void testHasDbRefreshTokenExpired_missingToken() {
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);

        InvalidJWTDataException exception = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidJWTDataException.class,
                () -> jwtManager.hasDbRefreshTokenExpired(mockRequest)
        );
        assertThat(exception.getErrorCode()).isEqualTo(MISSING_JWT_DB_REFRESH_TOKEN);
    }

    @Test
    void testGenerateAnonymousSession() {
        initRequestMetadata();
        HttpServletResponse mockResponse = new MockHttpServletResponse();
        
        String sessionId = jwtManager.generateAnonymousSession(mockResponse);
        
        assertThat(sessionId).isEqualTo(RequestMetadataProvider.getClientInfo().getSessionId());
        String cookieValue = extractCookie(mockResponse, ANONYMOUS_SESSION_COOKIE);
        assertThat(cookieValue).isEqualTo(sessionId);
    }

    @Test
    void testRemoveTwoFactorCookie() {
        HttpServletResponse mockResponse = new MockHttpServletResponse();
        jwtManager.removeTwoFactorCookie(mockResponse);
        
        String cookieHeader = mockResponse.getHeader("Set-Cookie");
        assertThat(cookieHeader).contains(LOGIN_INFO_ID + "=");
        assertThat(cookieHeader).contains("Max-Age=0");
    }

    @Test
    void testAddLoginInfoIdCookie() {
        HttpServletResponse mockResponse = new MockHttpServletResponse();
        String val = "test-val";
        jwtManager.addLoginInfoIdCookie(mockResponse, val);
        
        String cookieValue = extractCookie(mockResponse, LOGIN_INFO_ID);
        assertThat(cookieValue).isEqualTo(val);
        // Verify TTL
        String cookieHeader = mockResponse.getHeader("Set-Cookie");
        assertThat(cookieHeader).contains("Max-Age=" + OTP_TTL.toSeconds());
    }

    @Test
    void testExtractLoginInfoIdCookie() {
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        String val = "test-val";
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, LOGIN_INFO_ID)).thenReturn(val);
            
            String extracted = jwtManager.extractLoginInfoIdCookie(mockRequest);
            assertThat(extracted).isEqualTo(val);
        }
    }

    @Test
    void testEnsureAuthTokenPresent() {
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn("some-token");
            assertThatCode(() -> jwtManager.ensureAuthTokenPresent(mockRequest)).doesNotThrowAnyException();
            
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(null);
            assertThrows(org.springframework.security.access.AccessDeniedException.class, 
                    () -> jwtManager.ensureAuthTokenPresent(mockRequest));
        }
    }

    @Test
    void testExtractUserNameSilently() {
        initRequestMetadata();
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER));
        Principal principal = createPrincipal(authorities);
        String token = jwtManager.generateToken(principal, "seed");

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);
            
            String username = jwtManager.extractUserNameSilently(mockRequest);
            assertThat(username).isEqualTo(principal.getUsername());
            
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(null);
            assertThat(jwtManager.extractUserNameSilently(mockRequest)).isNull();
        }
    }

    @Test
    void testExtractSessionIdSilently() {
        initRequestMetadata();
        Set<SimpleGrantedAuthority> authorities = Set.of(new SimpleGrantedAuthority(ROLE_USER));
        Principal principal = createPrincipal(authorities);
        String token = jwtManager.generateToken(principal, "seed");

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        try (MockedStatic<CookieUtil> mockedCookieUtil = Mockito.mockStatic(CookieUtil.class)) {
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(token);
            
            Optional<String> sessionId = jwtManager.extractSessionIdSilently(mockRequest);
            assertThat(sessionId).isPresent().contains(RequestMetadataProvider.getClientInfo().getSessionId());
            
            mockedCookieUtil.when(() -> CookieUtil.getCookieValue(mockRequest, JWT_COOKIE_NAME)).thenReturn(null);
            assertThat(jwtManager.extractSessionIdSilently(mockRequest)).isEmpty();
        }
    }

    @Test
    void testEnsureClientHasPreLoginId() {
        initRequestMetadata();
        // Machine client has API key, so should not throw
        assertThatCode(() -> jwtManager.ensureClientHasPreLoginId()).doesNotThrowAnyException();
        
        RequestMetadataProvider.cleanup();
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost"));
        // No API key, no fingerprint cookie -> should throw
        RequestMetadataProvider.initRequestMetadata(mockRequest);
        assertThrows(MissingClientIdException.class, () -> jwtManager.ensureClientHasPreLoginId());
    }

    @Test
    void testEnsureClientHasPostLoginId() {
        initRequestMetadata();
        // Machine client has API key, so should not throw
        assertThatCode(() -> jwtManager.ensureClientHasPostLoginId()).doesNotThrowAnyException();
        
        RequestMetadataProvider.cleanup();
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost"));
        // No API key, no browser cookie -> should throw
        RequestMetadataProvider.initRequestMetadata(mockRequest);
        assertThrows(MissingClientIdException.class, () -> jwtManager.ensureClientHasPostLoginId());
    }

}
