package com.softropic.skillars.platform.security.infrastructure;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.function.Supplier;

public class UnanimousAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final List<AuthorizationManager<RequestAuthorizationContext>> decisionManagers;

    public UnanimousAuthorizationManager(List<AuthorizationManager<RequestAuthorizationContext>> decisionManagers) {
        this.decisionManagers = List.copyOf(decisionManagers);
    }


    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext object) {
        final boolean rejected = decisionManagers.stream().anyMatch(dm -> {
            final AuthorizationDecision decision = dm.check(authentication, object);
            return decision == null || !decision.isGranted();
        });
        return new AuthorizationDecision(!rejected);
    }
}
