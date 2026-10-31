package com.softropic.skillars.platform.security.config;


import com.softropic.skillars.infrastructure.web.LoggingFilter;
import com.softropic.skillars.platform.security.service.LoginTokenManager;
import com.softropic.skillars.platform.security.infrastructure.filter.SecurityAdviceFilter;
import com.softropic.skillars.platform.security.infrastructure.filter.SessionRefreshFilter;
import com.softropic.skillars.infrastructure.security.AjaxLogoutSuccessHandler;
import com.softropic.skillars.infrastructure.security.ApplicationAccessDeniedHandler;
import com.softropic.skillars.infrastructure.security.AuthenticationExceptionHandler;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.security.infrastructure.jwt.filter.JWTAuthorizationFilter;
import com.softropic.skillars.platform.security.service.ClientIdAccessDecisionManager;
import com.softropic.skillars.platform.security.infrastructure.SecuredHttpEndpointGuard;
import com.softropic.skillars.platform.security.infrastructure.UnanimousAuthorizationManager;
import com.softropic.skillars.platform.security.service.DaoAuthProvider;
import com.softropic.skillars.platform.security.service.LoadUserByUserNameService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.softropic.skillars.platform.security.contract.AdminBootstrapProperties;
import com.softropic.skillars.platform.security.contract.SecurityProperties;

import java.util.Arrays;
import java.util.List;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.ADMIN_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.B_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JAVA_SESSION_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_COOKIE_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.USER_COOKIE;
import static com.softropic.skillars.platform.security.config.AppEndpoints.PUBLIC_ENDPOINTS;
import static com.softropic.skillars.platform.security.config.AppEndpoints.PUBLIC_STATIC_RESOURCES;
import static com.softropic.skillars.platform.security.config.AppEndpoints.SECURED_ENDPOINTS;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true,
                      jsr250Enabled = true)
@EnableConfigurationProperties({SecurityProperties.class, AdminBootstrapProperties.class})
public class SecurityConfiguration {

    @Bean
    public DaoAuthProvider authProvider(LoadUserByUserNameService loadUserByUserNameService) {
        final DaoAuthProvider authProvider = new DaoAuthProvider();
        authProvider.setUserDetailsService(loadUserByUserNameService);
        authProvider.setPasswordEncoder(passwordEncoder());
        authProvider.setPreAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthProvider daoAuthProvider) {
        return new ProviderManager(daoAuthProvider);
    }

    @Bean
    public AuthorizationManager<RequestAuthorizationContext> authorizationManager(ClientIdAccessDecisionManager clientIdAccessDecisionManager,
                                                                                  Environment env) {
        //TODO hasAnyRole is used for roles without the "ROLE_" prefix
        final AuthorityAuthorizationManager<RequestAuthorizationContext> aaManager = AuthorityAuthorizationManager.hasAnyAuthority(AppEndpoints.SECURED_MAPPINGS.get(AppEndpoints.SECURED));
        final List<AuthorizationManager<RequestAuthorizationContext>> authManagers = activateSecurity(env) ? List.of(
                clientIdAccessDecisionManager,
                aaManager) : List.of(clientIdAccessDecisionManager);
        return new UnanimousAuthorizationManager(authManagers);
    }

    /**
     * {@code activate.security=false} drops the role-based authorization manager (a test/dev
     * convenience), so it must never survive into a deployed environment: a stray env var or config
     * overlay would silently turn every secured endpoint open. Startup fails instead.
     */
    static boolean activateSecurity(Environment env) {
        boolean active = env.getProperty("activate.security", Boolean.class, true);
        if (!active && env.acceptsProfiles(Profiles.of("prod", "uat"))) {
            throw new IllegalStateException(
                "activate.security=false is not allowed while the 'prod' or 'uat' profile is active");
        }
        return active;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); //The default encryption round count is 10
    }

    @Bean
    public AuthenticationEventPublisher authEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new DefaultAuthenticationEventPublisher(applicationEventPublisher);
    }

    /** Trimmed, blanks dropped: a blank {@code allowed.clients} accepts no machine client at all. */
    @Bean
    public List<String> allowedClients(@Value("${allowed.clients:}") final String allowedClients) {
        return Arrays.stream(allowedClients.split(","))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .toList();
    }

    @Bean
    public SecurityFilterChain filterChain(final HttpSecurity http,
                                           AjaxLogoutSuccessHandler ajaxLogoutSuccessHandler,
                                           SecurityAdviceFilter securityAdviceFilter,
                                           HandlerExceptionResolver handlerExceptionResolver,
                                           DaoAuthProvider daoAuthProvider,
                                           AuthorizationManager<RequestAuthorizationContext> authorizationManager,
                                           ApplicationEventPublisher applicationEventPublisher,
                                           LoginTokenManager loginTokenManager,
                                           SecurityUtil securityUtil,
                                           CorsConfiguration corsConfiguration,
                                           org.springframework.context.MessageSource messageSource,
                                           com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           Environment env) throws Exception {
        //TODO test first, then remove the following line
        //http.authorizeRequests().accessDecisionManager(accessDecisionManager);
        http.cors(cors -> cors.configurationSource(request -> corsConfiguration));

        //http.cors(Customizer.withDefaults()); //enable cors (configure it to block iframes from unknown third parties)
        //https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
        http.csrf(AbstractHttpConfigurer::disable);//not needed when using token-based authentication, such as JWT
        http.exceptionHandling(exceptionHandling -> {
                //used whenever access is denied (this is authorization and not authentication
            exceptionHandling.accessDeniedHandler(new ApplicationAccessDeniedHandler(handlerExceptionResolver));
                // handles an AuthenticationException raised anywhere in the chain (e.g. a missing/invalid
                // JWT on a secured endpoint). Password login is POST /api/auth/login (AuthResource).
                exceptionHandling.authenticationEntryPoint(new AuthenticationExceptionHandler(handlerExceptionResolver));
        });
        // No formLogin(): the only password entry point is POST /api/auth/login (AuthResource ->
        // AuthService.login, rate limited by LoginRateLimiter). The default form login added a second,
        // unthrottled POST /login that verified passwords through the AuthenticationManager and
        // persisted the result in an HttpSession. The legacy POST /authenticate -> POST /otp filter
        // pair (JWTAuthenticationFilter / SecondFactorLoginFilter) was removed for the same reason.
        http.logout(customizer ->
            // POST only: with CSRF disabled, logoutUrl() alone matches every HTTP method, so a
            // cross-site GET navigation (SameSite=Lax still sends rtkn) could revoke a victim's session.
            customizer.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/logout"))
                      // Same server-side teardown as POST /api/auth/logout (AuthService.logout):
                      // revoke this session's refresh token (REQUIRES_NEW, durable) and clear the
                      // rtkn/skp/JWT cookies. Without it this endpoint only dropped cookies, leaving
                      // the refresh token valid for its full TTL to anyone holding a copy.
                      .addLogoutHandler((request, response, authentication) ->
                          securityUtil.terminateSession(request, response))
                      .logoutSuccessHandler(ajaxLogoutSuccessHandler)
                      .deleteCookies(JAVA_SESSION_COOKIE,
                                     JWT_COOKIE_NAME,
                                     B_COOKIE,
                                     USER_COOKIE,
                                     ADMIN_COOKIE)
                      .permitAll()
        );
        // Nothing legitimately embeds the app in a frame (no iframes in the SPA, no third-party embedders), so
        // framing is limited to same-origin. The CSP carries ONLY frame-ancestors: a full CSP could break the Quasar
        // SPA / Google Fonts and is a separate (report-only first) rollout. X-Frame-Options covers old browsers.
        // HSTS is only emitted on requests the container sees as secure (RemoteIpValve honours X-Forwarded-Proto
        // from Traefik, which sets no HSTS header itself). Camera/microphone/geolocation are unused by the SPA
        // (video upload is a file input), so they are denied.
        http.headers(customizer -> customizer
            .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
            .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'"))
            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
            .permissionsPolicy(permissions -> permissions.policy(
                "camera=(), microphone=(), geolocation=(), payment=(), usb=(), accelerometer=(), gyroscope=(), magnetometer=()")));
        http.authorizeHttpRequests(configureRequestMatching(authorizationManager, env));
        http.addFilterAfter(new JWTAuthorizationFilter(daoAuthProvider,
                                                       applicationEventPublisher,
                                                       new SecuredHttpEndpointGuard(AppEndpoints.SECURED_MAPPINGS, AppEndpoints.ALL_UNRESTRICTED),
                                                       loginTokenManager,
                                                       securityUtil,
                                                       env,
                                                       messageSource,
                                                       objectMapper),
                            BasicAuthenticationFilter.class)
            .addFilterBefore(securityAdviceFilter, WebAsyncManagerIntegrationFilter.class)
            .addFilterBefore(new LoggingFilter(PUBLIC_STATIC_RESOURCES),
                             SecurityAdviceFilter.class)
            .addFilterAfter(new SessionRefreshFilter(AppEndpoints.REFRESH), JWTAuthorizationFilter.class);
        return http.build();
    }

    private static Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> configureRequestMatching(
            AuthorizationManager<RequestAuthorizationContext> authorizationManager, Environment env) {
        return (customizer) -> {
            // Async dispatches (e.g. SSE error-path Tomcat re-dispatch) must not be re-authorized;
            // the original request already passed security, and the response is already committed.
            customizer.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();

            //permit scraper
            customizer.requestMatchers(AppEndpoints.PUBLIC_MGMT_ENDPOINTS.toArray(new String[0])).permitAll();

            customizer.requestMatchers(PUBLIC_STATIC_RESOURCES.toArray(new String[0])).permitAll()
                      //.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                      .requestMatchers(PUBLIC_ENDPOINTS.toArray(new String[0])).permitAll();
            if (activateSecurity(env)) {

                //.requestMatchers(AppEndpoints.SECURED).hasAnyAuthority(AppEndpoints.SECURED_MAPPINGS.get(AppEndpoints.SECURED))
                customizer.requestMatchers(SECURED_ENDPOINTS.toArray(new String[0])).access(authorizationManager);
            }
            else {
                customizer.requestMatchers(SECURED_ENDPOINTS.toArray(new String[0])).permitAll();
            }
        };
    }


    //@Bean
    /** Seems to be added conditionally if spring data is also on the classpath
     * Exposes Spring Security as SpEL expressions for creating Spring Data queries
     * @Query("select m from Message m where m.to.id = ?#{ principal?.id }")
     *

    public SecurityEvaluationContextExtension securityEvaluationContextExtension() {
    return new SecurityEvaluationContextExtension();
    }
     */

}
