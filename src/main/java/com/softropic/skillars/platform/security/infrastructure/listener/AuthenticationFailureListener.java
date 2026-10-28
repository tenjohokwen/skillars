package com.softropic.skillars.platform.security.infrastructure.listener;



import com.softropic.skillars.infrastructure.security.event.FraudEvent;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
public class AuthenticationFailureListener {
    private static final Logger               logger = LoggerFactory.getLogger(AuthenticationFailureListener.class);

    // Only the in-memory, best-effort client blacklist remains here (see LoginAttemptsService).
    private final LoginAttemptsService loginAttemptsService;

    @Autowired
    public AuthenticationFailureListener(final LoginAttemptsService loginAttemptsService) {
        this.loginAttemptsService = loginAttemptsService;
    }

    // No BadCredentialsEvent handler any more: failed /authenticate attempts are recorded at their
    // source (FraudAwareAuthenticationManager) into the shared DB-backed LoginRateLimiter. This
    // event fires app-wide — for the SPA's /api/auth/login (which records its own) and for
    // refresh-token denials — so counting it here would double-count or charge non-login failures.

    @EventListener
    public void captureFraudEvent(final FraudEvent fraudEvent) {
        logger.debug("About to register fraud event. Message: {}", fraudEvent.msg());
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        loginAttemptsService.blacklistClient(requestMetadata);
    }
}
