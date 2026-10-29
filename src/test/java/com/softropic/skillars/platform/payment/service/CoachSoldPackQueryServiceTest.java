package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.CoachSoldPackDto;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** feat/coach-workflow-gaps A20. */
@ExtendWith(MockitoExtension.class)
class CoachSoldPackQueryServiceTest {

    private static final UUID COACH = UUID.randomUUID();

    @Mock SessionPackPurchaseRepository purchaseRepository;
    @Mock SessionPackTierRepository tierRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock UserRepository userRepository;
    @InjectMocks CoachSoldPackQueryService service;

    private static SessionPackPurchase purchase(Long parentId, Long playerId, UUID tierId, int remaining,
                                                Instant expiresAt, Instant extendedAt) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(UUID.randomUUID());
        p.setParentId(parentId);
        p.setPlayerId(playerId);
        p.setCoachId(COACH);
        p.setPackTierId(tierId);
        p.setPricePerSession(new BigDecimal("30.00"));
        p.setRemainingSessions(remaining);
        p.setExpiresAt(expiresAt);
        p.setExtendedAt(extendedAt);
        p.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return p;
    }

    @Test
    void mapsRowsWithBatchedLookups_andFlagsExtensionWindow() {
        UUID tierId = UUID.randomUUID();
        Instant now = Instant.now();
        SessionPackPurchase expiring = purchase(10L, 100L, tierId, 3, now.plus(5, ChronoUnit.DAYS), null);
        SessionPackPurchase alreadyExtended = purchase(10L, 100L, tierId, 3, now.plus(5, ChronoUnit.DAYS), now);
        SessionPackPurchase farOut = purchase(11L, 101L, tierId, 5, now.plus(40, ChronoUnit.DAYS), null);
        SessionPackPurchase selfBought = purchase(77L, 102L, tierId, 0, now.minus(1, ChronoUnit.DAYS), null);
        when(purchaseRepository.findByCoachIdOrderByExpiresAtDesc(COACH, PageRequest.of(0, 20)))
            .thenReturn(new PageImpl<>(List.of(expiring, alreadyExtended, farOut, selfBought)));

        SessionPackTier tier = new SessionPackTier();
        tier.setPackTierId(tierId);
        tier.setLabel("5 pack");
        tier.setSessionCount(5);
        when(tierRepository.findAllById(anyList())).thenReturn(List.of(tier));

        PlayerProfile child = new PlayerProfile();
        child.setId(100L);
        child.setName("Kid");
        child.setParentId(10L);
        PlayerProfile other = new PlayerProfile();
        other.setId(101L);
        other.setName("Other Kid");
        other.setParentId(11L);
        PlayerProfile adult = new PlayerProfile();
        adult.setId(102L);
        adult.setName("Adult Player");
        adult.setUserId(77L);
        when(playerProfileRepository.findAllById(anyList())).thenReturn(List.of(child, other, adult));

        User parent = new User();
        parent.setId(10L);
        parent.setFirstName("Pat");
        parent.setLastName("Parent");
        when(userRepository.findAllById(anyList())).thenReturn(List.of(parent));

        Page<CoachSoldPackDto> page = service.getSoldPacks(COACH, PageRequest.of(0, 20));
        List<CoachSoldPackDto> rows = page.getContent();

        assertThat(rows.get(0).buyerName()).isEqualTo("Pat Parent");
        assertThat(rows.get(0).playerName()).isEqualTo("Kid");
        assertThat(rows.get(0).tierLabel()).isEqualTo("5 pack");
        assertThat(rows.get(0).expiringSoon()).isTrue();
        assertThat(rows.get(0).extendable()).isTrue();
        assertThat(rows.get(1).extendable()).isFalse();
        assertThat(rows.get(2).expiringSoon()).isFalse();
        assertThat(rows.get(2).extendable()).isFalse();
        assertThat(rows.get(2).buyerName()).isNull();
        assertThat(rows.get(3).buyerName()).isEqualTo("Adult Player");
        assertThat(rows.get(3).status()).isEqualTo("EXHAUSTED");
        assertThat(rows.get(3).extendable()).isFalse();

        verify(tierRepository, times(1)).findAllById(anyList());
        verify(playerProfileRepository, times(1)).findAllById(anyList());
        verify(userRepository, times(1)).findAllById(anyList());
    }

    /** fix/pack-status-badges: the coach's sold-pack row reports a revoked pack as REVOKED with its reason. */
    @Test
    void toDto_revokedPack_reportsRevokedWithReason() {
        Instant now = Instant.now();
        SessionPackPurchase revoked = purchase(10L, 100L, UUID.randomUUID(), 0, now.plus(5, ChronoUnit.DAYS), null);
        revoked.setRevokedAt(now.minus(1, ChronoUnit.HOURS));
        revoked.setRevokedReason(SessionPackPurchase.REVOKED_COACH_SUSPENDED);
        SessionPackPurchase frozen = purchase(10L, 100L, UUID.randomUUID(), 3, now.plus(30, ChronoUnit.DAYS), null);
        frozen.setDisputeFrozenAt(now.minus(1, ChronoUnit.HOURS));

        CoachSoldPackDto revokedRow = CoachSoldPackQueryService.toDto(revoked, null, null, null, now);
        CoachSoldPackDto frozenRow = CoachSoldPackQueryService.toDto(frozen, null, null, null, now);

        assertThat(revokedRow.status()).isEqualTo("REVOKED");
        assertThat(revokedRow.revokedReason()).isEqualTo("COACH_SUSPENDED");
        assertThat(frozenRow.status()).isEqualTo("FROZEN");
        assertThat(frozenRow.revokedReason()).isNull();
    }

    @Test
    void emptyPage_skipsLookups() {
        when(purchaseRepository.findByCoachIdOrderByExpiresAtDesc(COACH, PageRequest.of(0, 20)))
            .thenReturn(Page.empty());

        assertThat(service.getSoldPacks(COACH, PageRequest.of(0, 20)).getContent()).isEmpty();
        verify(userRepository, never()).findAllById(anyList());
    }
}
