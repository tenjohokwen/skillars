package com.softropic.skillars.platform.security.infrastructure;



import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.service.LoginDecisionManager;

import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Adds functionality to the 'AuthenticationManager' by using a login decision manager that blocks clients suspected of fraud.
 * Should not be autowired or auto-created since this is just a wrapper around the real AuthenticationManager (Or else the real one may not be auto-created by the framework).
 */
public class FraudAwareAuthenticationManager implements AuthenticationManager {

    private final AuthenticationManager                 authenticationManager;
    private final LoginDecisionManager<RequestMetadata> loginDecisionManager;
    private final AuthenticationManager                 simulator;


    public FraudAwareAuthenticationManager(final AuthenticationManager authenticationManager,
                                           final LoginDecisionManager<RequestMetadata> loginDecisionManager,
                                           final AuthenticationManager simulator) {
        this.authenticationManager = authenticationManager;
        this.loginDecisionManager = loginDecisionManager;
        this.simulator = simulator;
    }

    /**
     * Before making an attempt to authenticate, it verifies if the current login request is allowed.
     * If the current request is not allowed, no authentication failure event is thrown, since no further handling is required.
     * <b>An authentication failure event should not be fired if {@code LoginDecisionManager} does not allow it.
     * Firing it would eventually lead to blocking the ip address for a given user and even blocking the user completely
     * whereas the {@code LoginDecisionManager} is already locking using the first level locking
     * {@link com.softropic.skillars.platform.security.service.LoginAttemptsService}</b>
     * @param authentication is the result from authenticating a user
     * @return a new authentication
     * @throws AuthenticationException indicates the account state
     *
     */
    @Override
    public Authentication authenticate(final Authentication authentication) {
        try {
            return verifyAccountStatusThenAuthenticate(authentication);
        } catch (AccountStatusException ase) {
            //AccountStatusException will short-circuit the process, giving room for timing attacks
            //The step is to mitigate timing attacks (see www.shorturl.at/hqDU3)
            simulator.authenticate(authentication);
            throw ase;
        }
    }

    private Authentication verifyAccountStatusThenAuthenticate(final Authentication authentication) {
        if(loginDecisionManager.isAllowed(RequestMetadataProvider.getClientInfo())) {
            return authenticationManager.authenticate(authentication);
        } else {
            final String errorMsg = String.format("Possibly too many failed login attempts. " +
                    "Client device or ip address or user has been locked.");
            throw new LockedException(errorMsg);
        }
    }
}
