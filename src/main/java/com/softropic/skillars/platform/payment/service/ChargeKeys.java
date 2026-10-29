package com.softropic.skillars.platform.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * fix/payment-charge-idempotency: the Stripe idempotency keys of booking charges — deterministic per charge
 * attempt, so the key can be written onto the CAPTURE_PENDING reservation BEFORE the Stripe call and the
 * PaymentIntent found again from it when the response is lost.
 *
 * <p>A booking is reserved (and so charged) at most once — {@code booking_payments} is keyed by booking id
 * and {@code reserveCapture} never reserves twice — so the booking id alone names the attempt. A batch is
 * not: two concurrent accepts of one batch settle disjoint booking sets, so its key also names the exact
 * set of reserved bookings this settlement charges for.
 */
final class ChargeKeys {

    private ChargeKeys() {
    }

    static String forBooking(UUID bookingId) {
        return "pi-booking-" + bookingId;
    }

    /** {@code pi-batch-{batchId}-{first 16 hex of sha256(sorted reserved booking ids)}} — 69 chars. */
    static String forBatch(UUID batchId, Collection<UUID> reservedBookingIds) {
        String joined = reservedBookingIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return "pi-batch-" + batchId + "-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every Java platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
