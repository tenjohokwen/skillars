package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.notification.repo.NotificationEmailPreferenceRepository;
import com.softropic.skillars.platform.notification.repo.UserNotificationRepository;
import com.softropic.skillars.platform.security.contract.event.UserErasedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * GDPR erasure: deletes the erased user's inbox (whose params can name coaches/players) and their
 * email preferences. AFTER_COMMIT + REQUIRES_NEW, the same shape as {@code UserErasedEventListener}:
 * it only runs once the erasure itself has committed, and a failure here cannot roll it back. The
 * retention sweep ({@code InAppNotificationRetentionScheduler}) bounds anything this misses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationUserErasedListener {

    private final UserNotificationRepository notificationRepository;
    private final NotificationEmailPreferenceRepository preferenceRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserErased(final UserErasedEvent event) {
        if (event.userId() == null) {
            return;
        }
        final int notifications = notificationRepository.deleteAllForRecipient(event.userId());
        final int preferences = preferenceRepository.deleteAllForUser(event.userId());
        log.info("[USER_ERASED] deleted {} in-app notifications and {} email preferences for userId={}",
            notifications, preferences, event.userId());
    }
}
