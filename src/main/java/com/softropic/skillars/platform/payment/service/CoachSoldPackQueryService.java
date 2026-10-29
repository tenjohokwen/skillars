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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * feat/coach-workflow-gaps A20: the coach-scoped list of session packs the coach has sold. Before this the
 * manual-extend widget on the coach's pack-tier page required the coach to type a raw purchase UUID that
 * nothing in the UI ever showed them.
 *
 * <p>Read-only. Every name lookup is batched per page (tiers, buyers, players: three {@code findAllById}
 * calls regardless of page size) — no per-row queries.
 */
@Service
@RequiredArgsConstructor
public class CoachSoldPackQueryService {

    /** {@code SessionPackPaymentService.extendPack}'s own eligibility window — one definition. */
    static final Duration EXTENSION_WINDOW = SessionPackPaymentService.EXTENSION_WINDOW;

    private final SessionPackPurchaseRepository sessionPackPurchaseRepository;
    private final SessionPackTierRepository sessionPackTierRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<CoachSoldPackDto> getSoldPacks(UUID coachId, Pageable pageable) {
        Page<SessionPackPurchase> page = sessionPackPurchaseRepository.findByCoachIdOrderByExpiresAtDesc(coachId, pageable);
        List<SessionPackPurchase> rows = page.getContent();
        if (rows.isEmpty()) {
            return page.map(p -> null);
        }

        Map<UUID, SessionPackTier> tiers = sessionPackTierRepository.findAllById(
                rows.stream().map(SessionPackPurchase::getPackTierId).distinct().toList())
            .stream().collect(Collectors.toMap(SessionPackTier::getPackTierId, Function.identity()));
        Map<Long, PlayerProfile> players = playerProfileRepository.findAllById(
                rows.stream().map(SessionPackPurchase::getPlayerId).distinct().toList())
            .stream().collect(Collectors.toMap(PlayerProfile::getId, Function.identity()));
        Map<Long, User> buyers = userRepository.findAllById(
                rows.stream().map(SessionPackPurchase::getParentId).distinct().toList())
            .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Instant now = Instant.now();
        return page.map(p -> toDto(p, tiers.get(p.getPackTierId()), players.get(p.getPlayerId()),
            buyers.get(p.getParentId()), now));
    }

    static CoachSoldPackDto toDto(SessionPackPurchase p, SessionPackTier tier, PlayerProfile player, User buyer,
                                  Instant now) {
        String playerName = player != null ? player.getName() : null;
        // A self-registered adult buying their own pack: the buyer IS the player (purchase.parentId is the
        // player's own user id), so show the player's name rather than repeating the user-row name.
        boolean selfPurchase = player != null && player.getParentId() == null
            && Objects.equals(player.getUserId(), p.getParentId());
        String buyerName = selfPurchase ? playerName : formatName(buyer);

        String status = computeStatus(p, now);
        boolean inWindow = !now.isBefore(p.getExpiresAt().minus(EXTENSION_WINDOW)) && !now.isAfter(p.getExpiresAt());
        boolean extendable = inWindow && p.getExtendedAt() == null;
        boolean expiringSoon = inWindow && p.getRemainingSessions() > 0;

        return new CoachSoldPackDto(
            p.getPurchaseId(),
            buyerName,
            p.getPlayerId(),
            playerName,
            tier != null ? tier.getLabel() : null,
            tier != null ? tier.getSessionCount() : 0,
            p.getRemainingSessions(),
            p.getExpiresAt(),
            p.getExtendedAt(),
            p.getCreatedAt(),
            status,
            expiringSoon,
            extendable,
            p.getRevokedReason()
        );
    }

    private static String formatName(User u) {
        if (u == null) return null;
        String first = u.getFirstName() != null ? u.getFirstName() : "";
        String last = u.getLastName() != null ? u.getLastName() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }

    private static String computeStatus(SessionPackPurchase p, Instant now) {
        return SessionPackPaymentService.computeStatus(p, now);
    }
}
