package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.security.contract.AccountRole;
import com.softropic.skillars.platform.security.contract.event.AccountDeletionRequestedEvent;
import com.softropic.skillars.platform.security.contract.event.PlayerProfileErasedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionCascadeListener {

    private final VideoDeletionService videoDeletionService;

    /**
     * Cascades video purge for all videos owned by the deleted account and any linked player profiles,
     * plus (V184) the coach videos whose SUBJECT is a deleted player.
     * Must NOT be @Transactional — AFTER_COMMIT means no surrounding transaction is active.
     * Each deleteVideo() call creates its own per-video transaction inside cascadeDeleteForAccount().
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeleted(AccountDeletionRequestedEvent event) {
        log.info("[VIDEO_ACCOUNT_DELETION] Cascading video purge for userId={}, role={}", event.userId(), event.userRole());

        // Invariant guard: PLAYER role must never have linked player IDs (would cascade to other users' videos)
        // Do NOT return early — the primary account cascade must still run.
        if (event.userRole() == AccountRole.PLAYER && event.linkedPlayerIds() != null && !event.linkedPlayerIds().isEmpty()) {
            log.error("[ACCOUNT_DELETION_INVARIANT_VIOLATION userId={} linkedPlayerIds={}] PLAYER role must have empty linkedPlayerIds — skipping linked cascade only",
                event.userId(), event.linkedPlayerIds());
        }

        videoDeletionService.cascadeDeleteForAccount(event.userId());

        if (event.userRole() == AccountRole.PLAYER) {
            // A PLAYER event's userId is the PlayerProfile id (the player-video ownerId space), which is
            // also the subject_player_id space.
            purgeVideosAbout(event.userId());
        }

        if (event.userRole() == AccountRole.PARENT
                && event.linkedPlayerIds() != null
                && !event.linkedPlayerIds().isEmpty()) {
            // linkedPlayerIds are PlayerProfile ids, which ARE the player-video ownerId space (Story 6.6).
            // This used to look each one up as a main."user" id and cascade on that user's id instead —
            // a different id space, so a parent's erasure never purged the children's videos.
            for (Long playerId : event.linkedPlayerIds()) {
                videoDeletionService.cascadeDeleteForAccount(String.valueOf(playerId));
                videoDeletionService.purgeVideosAboutPlayer(playerId);
            }
        }
    }

    /**
     * V184: a parent-requested erasure of one child profile purges the coach videos about that child
     * (conservative choice over unlinking: the footage shows the erased child). Shadow children own no
     * videos (uploads need a self-registered profile), so only the subject purge applies here.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlayerProfileErased(PlayerProfileErasedEvent event) {
        if (event.playerId() == null) {
            return;
        }
        log.info("[VIDEO_SUBJECT_ERASURE] Purging coach videos about erased playerId={}", event.playerId());
        videoDeletionService.purgeVideosAboutPlayer(event.playerId());
    }

    private void purgeVideosAbout(String playerOwnerId) {
        try {
            videoDeletionService.purgeVideosAboutPlayer(Long.parseLong(playerOwnerId));
        } catch (NumberFormatException e) {
            log.error("[VIDEO_SUBJECT_ERASURE] PLAYER deletion event carries a non-numeric profile id={} — "
                + "subject-linked coach videos NOT purged", playerOwnerId);
        }
    }
}
