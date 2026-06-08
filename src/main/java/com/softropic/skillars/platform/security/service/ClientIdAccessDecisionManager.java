package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * AccessDecisionVoter that determines whether access may be granted to a client based on its clientIdentifier and ip.
 */
@Service
public class ClientIdAccessDecisionManager implements AuthorizationManager<RequestAuthorizationContext> {

    //TODO move to db
    //private final List<String> blacklistedIps ; //NOPMD

    //TODO move to db
    private final List<String> allowedMachineClients;

    @Autowired
    public ClientIdAccessDecisionManager(final List<String> allowedClients) {
        this.allowedMachineClients = allowedClients;
    }

    public boolean isClientIdAllowed() {
        final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
        if(requestMetadata.isMachineClient()) {
            return allowedMachineClients.contains(requestMetadata.getApiKey());
        }
        //TODO eventually add check for blacklistedClients (i.e both blacklisted browser as well as machine clients)
        //TODO add logs so that you can know when a client cannot access endpoints
        return true;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext object) {
        return new AuthorizationDecision(isClientIdAllowed());
    }

    @Override
    public AuthorizationResult authorize(Supplier<Authentication> authentication, RequestAuthorizationContext rac) {
        return new AuthorizationDecision(isClientIdAllowed());
    }
}
