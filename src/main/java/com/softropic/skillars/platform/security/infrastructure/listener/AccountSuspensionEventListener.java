package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Uses @EventListener (not @TransactionalEventListener) because the event is published outside
// an active TX from ModerationOrchestrationService. @Transactional on the handler gives the
// suspension DB write its own TX.
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSuspensionEventListener {

    private final UserRepository userRepository;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountSuspensionRequested(AccountSuspensionRequestedEvent event) {
        String ownerId = event.ownerId();
        log.error("Processing account suspension: ownerId={} triggeredByVideoId={}", ownerId, event.videoId());

        userRepository.findOneByLogin(ownerId)
            .or(() -> userRepository.findOneByEmail(ownerId))
            .ifPresentOrElse(
                user -> {
                    if (user.getVerificationStatus() == SkillarsVerificationStatus.SUSPENDED) {
                        log.warn("Account already suspended: ownerId={}", ownerId);
                        return;
                    }
                    user.setVerificationStatus(SkillarsVerificationStatus.SUSPENDED);
                    userRepository.save(user);
                    // Durable audit link: suspension ← CSAM scan. A dedicated account_suspension_audits
                    // table is Story 10 scope; at minimum log at ERROR level so it appears in audit trail.
                    log.error("Account suspended: ownerId={} triggeredByVideoId={} verificationStatus=SUSPENDED",
                              ownerId, event.videoId());
                },
                () -> log.error("CRITICAL: Cannot suspend account — user not found: ownerId={} videoId={}",
                                ownerId, event.videoId())
            );
    }
}
