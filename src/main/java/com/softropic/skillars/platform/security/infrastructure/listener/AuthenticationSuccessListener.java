package com.softropic.skillars.platform.security.infrastructure.listener;



import com.softropic.skillars.infrastructure.security.event.AuthenticationAction;
import com.softropic.skillars.infrastructure.security.event.AuthEvent;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.service.LoginAttemptConsumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class AuthenticationSuccessListener {

    private final LoginAttemptConsumer<RequestMetadata> loginAttemptConsumer;

    @Autowired
    public AuthenticationSuccessListener(@Qualifier("loginAttemptService") final LoginAttemptConsumer<RequestMetadata> loginAttemptConsumer) {
        this.loginAttemptConsumer = loginAttemptConsumer;
    }

    @EventListener
    public void AuthEvent(final AuthEvent authEvent) {
        final AuthenticationAction action = authEvent.getAction();
        switch (action) {
            case SUCCESSFUL_AUTHENTICATION,  SUCCESSFUL_2FA -> {
                RequestMetadataProvider.setUserName(authEvent.getAuthentication().getName());
                final RequestMetadata requestMetadata = RequestMetadataProvider.getClientInfo();
                loginAttemptConsumer.loginSucceeded(requestMetadata);
            }
        }
    }
}
