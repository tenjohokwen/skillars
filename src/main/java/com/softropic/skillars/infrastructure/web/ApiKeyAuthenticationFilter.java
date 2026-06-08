package com.softropic.skillars.infrastructure.web;

import com.softropic.skillars.infrastructure.security.TenantContext;
import com.softropic.skillars.platform.security.config.AppEndpoints;
import org.slf4j.MDC;
import com.softropic.skillars.platform.tenant.contract.TenantPrincipal;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;
import com.softropic.skillars.platform.tenant.repo.TenantApiKey;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that authenticates incoming API requests via the {@code X-Api-Key} header.
 *
 * <p>Registered in the {@code @Order(1)} security filter chain ({@code TenantSecurityConfig})
 * which scopes it to {@code /v1/**} paths. NOT registered as a {@code @Component} — it is
 * added exclusively via {@code addFilterBefore} in {@code TenantSecurityConfig} to prevent
 * auto-registration with the servlet container.
 *
 * <p>Paths that bypass API key authentication (pass-through to the JWT chain):
 * <ul>
 *   <li>{@code /v1/account/**} — user account management (register, activate, login, etc.)
 *       is handled by the existing JWT security chain.
 *   <li>{@code AppEndpoints.PUBLIC_ENDPOINTS} paths — explicitly public paths (reset password,
 *       email verification, etc.) that require no authentication from either chain.
 * </ul>
 *
 * <p>Authentication flow for tenant payment paths:
 * <ol>
 *   <li>Read the {@code X-Api-Key} header. If absent, send 401 immediately.
 *   <li>Delegate to {@link ApiKeyService#authenticate(String)} which hashes the raw key
 *       and queries with the 24-hour rotation grace window.
 *   <li>On success: set {@link TenantContext} and populate {@link SecurityContextHolder}.
 *   <li>Always clear {@link TenantContext} in the {@code finally} block — servlet containers
 *       reuse threads so leaking context between requests is a real risk.
 * </ol>
 *
 * <p>The raw key value is NEVER logged. Only the key prefix (extracted from the
 * underscore-delimited raw key format) is used in log output to aid debugging without
 * exposing secrets.
 *
 * <p>The {@link TenantApiKey#getTenant()} call is safe here because
 * {@code TenantApiKeyRepository.findValidKeyByHash} uses {@code JOIN FETCH k.tenant},
 * meaning the tenant proxy is fully loaded before the {@code @Transactional(readOnly=true)}
 * authenticate() transaction closes.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    /** Paths that bypass API key auth and are handled by the JWT chain or as public. */
    private static final List<String> BYPASS_PATTERNS;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    static {
        BYPASS_PATTERNS = new ArrayList<>();
        BYPASS_PATTERNS.add("/v1/account/**");   // user account management — JWT chain
        BYPASS_PATTERNS.add("/v1/admin/**");     // admin investigation — JWT chain
        BYPASS_PATTERNS.addAll(AppEndpoints.PUBLIC_ENDPOINTS);
    }

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Skip this filter for paths that belong to the JWT chain or are explicitly public.
     * Spring Security's authorization rules (permitAll / authenticated) still apply —
     * this method only controls whether the API-key extraction logic runs.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return BYPASS_PATTERNS.stream()
            .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            log.error("Expected X-Api-Key header");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-Api-Key header");
            return;
        }

        TenantApiKey tenantApiKey;
        try {
            tenantApiKey = apiKeyService.authenticate(rawKey);
        } catch (Exception e) {
            log.warn("API key authentication failed",
                    kv("operation", "api_key_auth"),
                    kv("keyPrefix", rawKey.contains("_") ? rawKey.substring(0, rawKey.indexOf("_")) : "[unknown]"),
                    kv("status", "UNAUTHORIZED"));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired API key");
            return;
        }

        // TENT-09: Block SUSPENDED tenants before SecurityContext is populated
        if (tenantApiKey.getTenant().getTenantStatus() == TenantStatus.SUSPENDED) {
            log.warn("Tenant is suspended",
                    kv("operation", "api_key_auth"),
                    kv("tenantRef", tenantApiKey.getTenant().getTenantRef()),
                    kv("status", "FORBIDDEN"));
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant is suspended");
            return;
        }

        String tenantRef = tenantApiKey.getTenant().getTenantRef();
        Long   tenantId  = tenantApiKey.getTenant().getId();
        TenantContext.set(tenantRef);
        MDC.put("tenantId", tenantRef);  // LOG-MDC-01: tenantId in MDC for every log in this request thread

        TenantPrincipal principal = new TenantPrincipal(tenantRef, tenantId);
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();               // ALWAYS clear — servlet containers reuse threads
            MDC.remove("tenantId");             // Mirror MDC.put — remove exactly what was added
            SecurityContextHolder.clearContext();
        }
    }
}
