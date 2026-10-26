package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** skillars-deferred-162 AC5.1: the marker is read from the locked, refreshed profile row. */
@ExtendWith(MockitoExtension.class)
class DevelopmentDataErasureGuardTest {

    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private PessimisticLockRetryer lockRetryer;
    @Mock private EntityManager entityManager;
    @InjectMocks private DevelopmentDataErasureGuard guard;

    @BeforeEach
    void runSupplierDirectly() {
        lenient().when(lockRetryer.withBoundedRetry(any(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private static PlayerProfile profile(Instant erasedAt) {
        PlayerProfile p = new PlayerProfile();
        p.setDevelopmentDataErasedAt(erasedAt);
        return p;
    }

    @Test
    void erasedPlayer_isReportedErased_afterLockAndRefresh() {
        PlayerProfile p = profile(Instant.now());
        when(playerProfileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(p));

        assertThat(guard.isErasedUnderLock(7L, "test")).isTrue();
        verify(lockRetryer).withBoundedRetry(eq("test"), any());
        verify(entityManager).refresh(p, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void liveplayer_isNotErased() {
        when(playerProfileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile(null)));

        assertThat(guard.isErasedUnderLock(7L, "test")).isFalse();
    }

    @Test
    void missingProfile_hasNoMarker_soIsNotErased() {
        when(playerProfileRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThat(guard.isErasedUnderLock(7L, "test")).isFalse();
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));
    }

    @Test
    void nullPlayerId_isNotErased_andTakesNoLock() {
        assertThat(guard.isErasedUnderLock(null, "test")).isFalse();
        verifyNoInteractions(playerProfileRepository, entityManager);
    }
}
