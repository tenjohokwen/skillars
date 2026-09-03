package com.softropic.skillars.platform.security.infrastructure.jwt.filter;


import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.infrastructure.security.event.PreAuthEvent;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.event.SecurityAlertEvent;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.contract.exception.JWTExpiredException;
import com.softropic.skillars.platform.security.contract.exception.JWTTheftException;
import com.softropic.skillars.platform.security.infrastructure.SecuredHttpEndpointGuard;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.service.DaoAuthProvider;
import com.softropic.skillars.platform.security.service.LoadUserByUserNameService;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.platform.security.service.SecurityUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JWTAuthorizationFilterTest {

    @Mock
    private LoadUserByUserNameService loadUserByUserNameService;

    @Mock
    private DaoAuthProvider daoAuthProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SecuredHttpEndpointGuard securedHttpEndpointGuard;

    @Mock
    private LoginTokenManager loginTokenManager;

    @Mock
    private SecurityUtil securityUtil;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private Principal principal; // Mocked principal from token

    @Mock
    private Environment environment;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private MessageSource messageSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StringWriter responseBody;

    @Spy
    private UserDetails userDetails = principal; //new User("testuser", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))); // Mocked, returned by daoAuthProvider

    private JWTAuthorizationFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);
        daoAuthProvider.setUserDetailsService(loadUserByUserNameService);
        SecurityContextHolder.clearContext(); // Ensure context is clean before each test

        // Constructor for JWTAuthorizationFilter
        filter = new JWTAuthorizationFilter(
                daoAuthProvider,
                eventPublisher,
                securedHttpEndpointGuard,
                loginTokenManager,
                securityUtil,
                environment,
                refreshTokenRepository,
                messageSource,
                objectMapper
        );

        // The 401 path now writes an ErrorDto body via the response writer + MessageSource.
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody, true));
        when(messageSource.getMessage(anyString(), nullable(Object[].class), anyString(), nullable(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));

        when(environment.getProperty("activate.security", Boolean.class, true)).thenReturn(true);
        // Default "happy path" mocks, can be overridden in specific tests
        when(request.getRequestURI()).thenReturn("/api/some_endpoint"); // For guard
        when(principal.getUsername()).thenReturn("testuser");
        when(userDetails.getUsername()).thenReturn("testuser"); // Ensure userDetails also has username
        //when(userDetails.getAuthorities()).thenReturn(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        when(userDetails.isEnabled()).thenReturn(true);
        when(userDetails.isAccountNonExpired()).thenReturn(true);
        when(userDetails.isAccountNonLocked()).thenReturn(true);
        when(userDetails.isCredentialsNonExpired()).thenReturn(true);

        // Default behavior for securityUtils if needed, e.g. if it's used to check current auth
        // For now, not mocking specific securityUtils methods unless a test shows it's directly invoked by the filter logic being tested.
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // Ensure context is clean after each test
    }

    @Test
    void testFilterInstantiation() {
        assertNotNull(filter, "Filter should be instantiated.");
    }

    @Test
    @DisplayName("Valid Token: User is authenticated and context is set")
    void testDoFilterInternal_ValidToken_UserAuthenticated() throws ServletException, IOException {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        when(loadUserByUserNameService.loadUserByUsername("testuser")).thenReturn(userDetails);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(false);

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "Authentication should not be null in SecurityContext");
        assertTrue(authentication.isAuthenticated());
        assertSame(principal, authentication.getPrincipal(), "Principal in Authentication object should be the UserDetails");
        assertEquals(userDetails.getAuthorities(), authentication.getAuthorities(), "Authorities should match");

        verify(eventPublisher).publishEvent(any(PreAuthEvent.class)); // Or a more specific custom event
        verify(securedHttpEndpointGuard).isUnrestricted(request);
        verify(filterChain).doFilter(request, response);

    }

    @Test
    @DisplayName("No Token: 401 sent, filter chain aborted")
    void testWhenNoToken_ThrowMissingAuthenticationException() throws ServletException, IOException {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(null);

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        // setStatus() alone only proves a setter was called on the mock; assert the ErrorDto body
        // too, so dropping the objectMapper.writeValue call cannot pass this test.
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.unauthorized\""),
                "missing-token 401 body must carry errorKey=security.unauthorized, was: " + responseBody);
    }

    @Test
    @DisplayName("Expired Token: 401 with ErrorDto body carrying errorKey=security.sessionExpired")
    void testWhenJWTExpiredException_LetExceptionBubbleUp() throws ServletException, IOException {
        // Setup
        JWTExpiredException expiredException = new JWTExpiredException("Token expired", null, null);
        when(loginTokenManager.extractPrincipal(request)).thenThrow(expiredException);

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json");
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.sessionExpired\""),
                "expired-JWT 401 body must carry errorKey=security.sessionExpired, was: " + responseBody);
    }

    @Test
    @DisplayName("Invalid Signature Token: 401 with ErrorDto body carrying errorKey=security.unauthorized")
    void testWhenInvalidJWTDataException_LetExceptionBubbleUp() throws ServletException, IOException {
        // Setup
        InvalidJWTDataException invalidSigException = new InvalidJWTDataException("Invalid signature", null);
        when(loginTokenManager.extractPrincipal(request)).thenThrow(invalidSigException);

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.unauthorized\""),
                "non-expired 401 body must carry errorKey=security.unauthorized, was: " + responseBody);
    }

    @Test
    @DisplayName("Token Fixation Detected: 401 sent, filter chain aborted")
    void testWhenTokenFixed_ThrowJWTTheftExceptionAndLetItBubbleUp() throws ServletException, IOException {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal); // Token initially seems valid
        when(loadUserByUserNameService.loadUserByUsername("testuser")).thenReturn(userDetails); // User details are loaded
        when(loginTokenManager.isTokenFixed(request)).thenReturn(true); // But then fixation is detected

        // Pre-populate context to ensure it's cleared
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken tempAuth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        context.setAuthentication(tempAuth);
        SecurityContextHolder.setContext(context);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "Context should have auth before isTokenFixed check");

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        // setStatus() alone only proves a setter was called on the mock; assert the ErrorDto body
        // too, so dropping the objectMapper.writeValue call cannot pass this test.
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.unauthorized\""),
                "token-fixation 401 body must carry errorKey=security.unauthorized, was: " + responseBody);
    }

    @Test
    @DisplayName("User Disabled: 401 sent, filter chain aborted")
    void testWhenUserDisabled_LetDisabledExeceptionBubbleUp() throws ServletException, IOException {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(false);
        when(loginTokenManager.hasDbRefreshTokenExpired(request)).thenReturn(true);
        when(daoAuthProvider.authorize(any(), any())).thenThrow(new DisabledException("Account is disabled"));

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        // setStatus() alone only proves a setter was called on the mock; assert the ErrorDto body
        // too, so dropping the objectMapper.writeValue call cannot pass this test.
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.unauthorized\""),
                "disabled-user 401 body must carry errorKey=security.unauthorized, was: " + responseBody);
    }

    @Test
    @DisplayName("User Locked: 401 sent, filter chain aborted")
    void testWhenUserLocked_LetLockedExceptionBubbleUp() throws ServletException, IOException {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(false);
        when(loginTokenManager.hasDbRefreshTokenExpired(request)).thenReturn(true);
        when(daoAuthProvider.authorize(any(), any())).thenThrow(new LockedException("Account is locked"));

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        // setStatus() alone only proves a setter was called on the mock; assert the ErrorDto body
        // too, so dropping the objectMapper.writeValue call cannot pass this test.
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.unauthorized\""),
                "locked-user 401 body must carry errorKey=security.unauthorized, was: " + responseBody);
    }

    @Test
    @DisplayName("User Not Found by DAO: UsernameNotFoundException propagates (not an auth-token failure)")
    void testUserNotFoundByDao_LetUsernameNotFoundExceptionBubbleUp() {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        UsernameNotFoundException notFoundException = new UsernameNotFoundException("User testuser not found");
        when(daoAuthProvider.authorize(any(), any())).thenThrow(notFoundException);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(false);
        when(loginTokenManager.hasDbRefreshTokenExpired(request)).thenReturn(true);

        // Action
        assertThrows(UsernameNotFoundException.class, () -> filter.doFilterInternal(request, response, filterChain));
    }

    @Test
    @DisplayName("Authorization Exception: 401 sent, filter chain aborted")
    void testAuthorizationExceptionBubblesUp() throws ServletException, IOException {
        // Setup
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(false);
        final AuthorizationException missingRights = new AuthorizationException("Missing rights",
                                                                                SecurityError.MISSING_RIGHTS);
        doThrow(missingRights).when(daoAuthProvider).checkAuthorities(any(), any());

        // Action
        filter.doFilterInternal(request, response, filterChain);

        // Verification
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(securityUtil).logout(response);
        verify(filterChain, never()).doFilter(request, response);
        // setStatus() alone only proves a setter was called on the mock; assert the ErrorDto body
        // too, so dropping the objectMapper.writeValue call cannot pass this test.
        assertTrue(responseBody.toString().contains("\"errorKey\":\"security.unauthorized\""),
                "authorization-exception 401 body must carry errorKey=security.unauthorized, was: " + responseBody);

        verify(loginTokenManager).ensureClientHasPostLoginId();
        verify(loginTokenManager).isTokenFixed(request);
        verify(loginTokenManager, times(2)).extractPrincipal(request);
        verify(loadUserByUserNameService, never()).loadUserByUsername("testuser");
        verify(eventPublisher).publishEvent(any(PreAuthEvent.class)); // Or a more specific custom event
        verify(securedHttpEndpointGuard).isUnrestricted(request);
        verify(loginTokenManager, never()).extendTtlOfToken(any(), any());
    }

    // --- skillars-deferred-90 AC5: helpCode + scoped SecurityAlertEvent -------------------------

    @Test
    @DisplayName("AC5: filter-origin 401 body now carries a non-null helpCode")
    void testWriteUnauthorized_populatesHelpCode() throws ServletException, IOException {
        when(loginTokenManager.extractPrincipal(request)).thenReturn(null); // MissingAuthenticationException

        filter.doFilterInternal(request, response, filterChain);

        assertTrue(responseBody.toString().matches("(?s).*\"helpCode\":\"[^\"]+\".*"),
                "401 body must carry a non-null helpCode, was: " + responseBody);
    }

    @Test
    @DisplayName("AC5: no SecurityAlertEvent for a tokenless request (MissingAuthenticationException)")
    void testNoSecurityAlert_forMissingToken() throws ServletException, IOException {
        when(loginTokenManager.extractPrincipal(request)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(eventPublisher, never()).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    @DisplayName("AC5: no SecurityAlertEvent for an expired JWT")
    void testNoSecurityAlert_forExpiredJwt() throws ServletException, IOException {
        when(loginTokenManager.extractPrincipal(request))
                .thenThrow(new JWTExpiredException("Token expired", null, null));

        filter.doFilterInternal(request, response, filterChain);

        verify(eventPublisher, never()).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    @DisplayName("AC5: SecurityAlertEvent IS fired for token fixation (JWTTheftException)")
    void testSecurityAlert_forTokenFixation() throws ServletException, IOException {
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        when(loadUserByUserNameService.loadUserByUsername("testuser")).thenReturn(userDetails);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(eventPublisher).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    @DisplayName("AC5: SecurityAlertEvent IS fired for an invalid-signature token (InvalidJWTDataException)")
    void testSecurityAlert_forInvalidJwtData() throws ServletException, IOException {
        when(loginTokenManager.extractPrincipal(request))
                .thenThrow(new InvalidJWTDataException("Invalid signature", null));

        filter.doFilterInternal(request, response, filterChain);

        verify(eventPublisher).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    @DisplayName("AC5: SecurityAlertEvent IS fired for a locked account (AccountStatusException)")
    void testSecurityAlert_forLockedAccount() throws ServletException, IOException {
        when(loginTokenManager.extractPrincipal(request)).thenReturn(principal);
        when(loginTokenManager.isTokenFixed(request)).thenReturn(false);
        when(loginTokenManager.hasDbRefreshTokenExpired(request)).thenReturn(true);
        when(daoAuthProvider.authorize(any(), any())).thenThrow(new LockedException("Account is locked"));

        filter.doFilterInternal(request, response, filterChain);

        verify(eventPublisher).publishEvent(any(SecurityAlertEvent.class));
    }

}
