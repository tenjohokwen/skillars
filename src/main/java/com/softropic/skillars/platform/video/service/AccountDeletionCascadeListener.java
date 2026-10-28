package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.AccountRole;
import com.softropic.skillars.platform.security.contract.event.AccountDeletionRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionCascadeListener {

    private final VideoDeletionService videoDeletionService;
    private final ConfigService configService;

    /**
     * Cascades video purge for all videos owned by the deleted account and any linked player profiles.
     * Must NOT be @Transactional — AFTER_COMMIT means no surrounding transaction is active.
     * Each deleteVideo() call creates its own per-video transaction inside cascadeDeleteForAccount().
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeleted(AccountDeletionRequestedEvent event) {
        log.info("[VIDEO_ACCOUNT_DELETION] Cascading video purge for userId={}, role={}", event.userId(), event.userRole());

        // Invariant guard: PLAYER role must never have linked player IDs (would cascade to other users' videos)
        // Do NOT return early — primary account cascade and approval cancellation must still run.
        if (event.userRole() == AccountRole.PLAYER && event.linkedPlayerIds() != null && !event.linkedPlayerIds().isEmpty()) {
            log.error("[ACCOUNT_DELETION_INVARIANT_VIOLATION userId={} linkedPlayerIds={}] PLAYER role must have empty linkedPlayerIds — skipping linked cascade only",
                event.userId(), event.linkedPlayerIds());
        }

        List<String> affectedOwnerIds = new ArrayList<>();
        affectedOwnerIds.add(event.userId());

        videoDeletionService.cascadeDeleteForAccount(event.userId());

        if (event.userRole() == AccountRole.PARENT
                && event.linkedPlayerIds() != null
                && !event.linkedPlayerIds().isEmpty()) {
            // linkedPlayerIds are PlayerProfile ids, which ARE the player-video ownerId space (Story 6.6).
            // This used to look each one up as a main."user" id and cascade on that user's id instead —
            // a different id space, so a parent's erasure never purged the children's videos.
            for (Long playerId : event.linkedPlayerIds()) {
                String playerOwnerId = String.valueOf(playerId);
                affectedOwnerIds.add(playerOwnerId);
                videoDeletionService.cascadeDeleteForAccount(playerOwnerId);
            }
        }

        if (configService.getBoolean("platform.video.approvalCancellation.enabled", true)) {
            // skillars-deferred-91 AC13: through a @Transactional VideoDeletionService method — this
            // listener is AFTER_COMMIT with no ambient transaction, so a direct @Modifying repo call
            // here throws TransactionRequiredException (same class as the cascade's quota reset).
            videoDeletionService.cancelPendingApprovalsForOwners(affectedOwnerIds);
        }
    }
}
