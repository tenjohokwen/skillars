package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.platform.security.contract.event.UserErasedEvent;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserErasedEventListener {

    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserErased(UserErasedEvent event) {
        log.info("[USER_ERASED] Confirming session invalidation for userId={}", event.userId());
        userRepository.findOneById(event.userId()).ifPresent(user -> {
            if (user.isActivated() || !user.isLocked()) {
                user.setActivated(false);
                user.setLocked(true);
                user.getPersistentTokens().clear();
                userRepository.save(user);
                log.warn("[USER_ERASED_REMEDIATION] userId={} was still active after erasure — corrected", event.userId());
            }
        });
    }
}
