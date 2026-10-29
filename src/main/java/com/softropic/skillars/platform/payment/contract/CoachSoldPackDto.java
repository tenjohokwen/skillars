package com.softropic.skillars.platform.payment.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * feat/coach-workflow-gaps A20: one session pack a coach has sold, for the coach's "sold packs" list and its
 * Extend action. {@code buyerName} is the purchasing account's name as the coach already sees it on booking
 * requests (first + last name); for a self-registered adult player who bought their own pack it is the
 * player's own name. No contact details are exposed.
 *
 * <p>{@code extendable} mirrors {@code SessionPackPaymentService.extendPack}'s own rules (not yet extended,
 * and now within the 14 days before expiry) so the UI only offers Extend where the endpoint will accept it;
 * {@code expiringSoon} is the same window, flagged for packs that still have sessions left.
 */
public record CoachSoldPackDto(
    UUID purchaseId,
    String buyerName,
    Long playerId,
    String playerName,
    String tierLabel,
    int sessionCount,
    int remainingSessions,
    Instant expiresAt,
    Instant extendedAt,
    Instant createdAt,
    String status,
    boolean expiringSoon,
    boolean extendable,
    /* fix/pack-status-badges: why a REVOKED pack was revoked (SessionPackPurchase.REVOKED_*); null unless revoked. */
    String revokedReason
) {}
