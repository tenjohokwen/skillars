package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * skillars-deferred-162 AC5: race-free "has this player's development data been erased?" check for code
 * that is about to write development data (timeline rows, homework assignments) for a player.
 * <p>
 * The check takes the {@code player_profiles} row lock and must run inside the caller's write transaction
 * ({@link Propagation#MANDATORY}), so the lock is held until that write commits. GDPR erasure stamps
 * {@code development_data_erased_at} under the same lock, in the same transaction that deletes the rows, so
 * either the erasure committed first (this sees the marker and the caller skips) or it waits for the
 * caller's commit and then deletes what the caller wrote. An unlocked read-then-insert would leave a window
 * in which a row lands after the delete. Same mechanism as
 * {@code RadarCompositeCalculationService.recalculateComposite}.
 * <p>
 * Lock order: {@code player_profiles} first, then the caller's insert. Erasure takes the same order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DevelopmentDataErasureGuard {

    private final PlayerProfileRepository playerProfileRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;

    /**
     * @return {@code true} if the player's development data was erased, so the caller must not write.
     *     A player with no profile row has no marker to check and returns {@code false}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isErasedUnderLock(Long playerId, String caller) {
        if (playerId == null) {
            return false;
        }
        Optional<PlayerProfile> profile = lockRetryer.withBoundedRetry(caller,
            () -> playerProfileRepository.findByIdForUpdate(playerId));
        if (profile.isEmpty()) {
            log.debug("{}: no player_profiles row for player {} -- no erasure marker to check", caller, playerId);
            return false;
        }
        entityManager.refresh(profile.get(), LockModeType.PESSIMISTIC_WRITE);
        if (profile.get().getDevelopmentDataErasedAt() != null) {
            log.info("{}: skipping write for player {} -- development data was erased at {}",
                caller, playerId, profile.get().getDevelopmentDataErasedAt());
            return true;
        }
        return false;
    }
}
