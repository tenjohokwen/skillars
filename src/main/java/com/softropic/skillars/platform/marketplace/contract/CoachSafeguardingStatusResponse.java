package com.softropic.skillars.platform.marketplace.contract;

import java.time.Instant;
import java.time.LocalDate;

/**
 * S-09: the coach's OWN view of their safeguarding check. {@code status} is one of
 * {@code NONE / PENDING / VERIFIED / EXPIRED / REJECTED}: a valid record is VERIFIED (even while a renewal
 * request is pending, see {@code renewalPending}); otherwise the request's own state (PENDING / REJECTED); a lapsed
 * record with no request is EXPIRED.
 * Never carries the inspector's identity or anything from the certificate.
 *
 * @param validUntil     end of validity for VERIFIED / EXPIRED, else null
 * @param renewalPending a request is waiting for staff while the coach's current check is still valid
 * @param uploadOnFile   an encrypted certificate upload is currently held for the pending request
 * @param uploadDeleteAt when the sweeper will delete that upload if staff have not decided by then
 * @param uploadRetentionDays how long an unprocessed upload is kept before the sweeper deletes it
 * @param canRequest     whether a (new) request may be filed now: nothing pending and no check that is valid
 *                       for longer than the renewal window
 */
public record CoachSafeguardingStatusResponse(
    String status,
    String checkType,
    LocalDate validUntil,
    boolean renewalPending,
    Instant requestedAt,
    boolean uploadOnFile,
    Instant uploadDeleteAt,
    int uploadRetentionDays,
    boolean canRequest
) {
    public static final String NONE = "NONE";
    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REJECTED = "REJECTED";
}
