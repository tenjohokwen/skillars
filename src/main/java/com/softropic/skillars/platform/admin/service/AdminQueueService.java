package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertDto;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.AdminQueueSummaryDto;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.messaging.repo.ConversationReportRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminQueueService {

    /** Content-preview length in code points, for the message-backed alert summaries. */
    private static final int PREVIEW_CODE_POINTS = 100;

    private final AdminAlertRepository adminAlertRepository;
    private final MessageRepository messageRepository;
    private final ConversationReportRepository conversationReportRepository;
    private final ReviewFlagRepository reviewFlagRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<AdminAlertDto> getAlerts(String typeParam, int page) {
        AdminAlertType type;
        if ("ALL".equalsIgnoreCase(typeParam) || typeParam == null) {
            type = null;
        } else {
            try {
                type = AdminAlertType.valueOf(typeParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unknown alert type: " + typeParam);
            }
        }
        Pageable p = PageRequest.of(Math.max(0, page), 20);
        // skillars-deferred-91 AC6: read the rows as raw strings so a single row carrying an
        // alert_type this instance's enum does not know (rolling deploy, skillars-deferred-16 D4) is
        // skipped with a WARN instead of 500ing the whole page during entity hydration.
        Page<Object[]> raw = adminAlertRepository.findOpenRawByType(type == null ? null : type.name(), p);
        List<AdminAlertDto> dtos = new ArrayList<>(raw.getNumberOfElements());
        int skipped = 0;
        for (Object[] row : raw.getContent()) {
            AdminAlertDto dto = toDtoTolerant(row);
            if (dto != null) {
                dtos.add(dto);
            } else {
                skipped++;
            }
        }
        // skillars-deferred-91 code review: the total must exclude the rows this page dropped, or the
        // client is told "21 alerts" on a page that returned 19 and pagination arithmetic goes wrong.
        // Only this page's skips are knowable without a second count query; that is enough to keep
        // the rendered page self-consistent.
        return new PageImpl<>(dtos, p, raw.getTotalElements() - skipped);
    }

    /**
     * Maps one raw {@code admin_alerts} row to a DTO, or returns {@code null} (with a WARN) if the
     * row cannot be mapped on this instance. AC6: the page renders every alert it can map.
     *
     * <p>skillars-deferred-91 code review: the guard used to cover only {@code AdminAlertType.valueOf},
     * leaving {@code toUuid(row[0])} ({@code UUID.fromString} on a non-UUID, or an NPE on a null
     * {@code alert_id}) and {@code toInstant(row[5])} ({@code Instant.parse} on an unrecognised type)
     * outside it — so one malformed row still collapsed the whole {@code /queue} page, which is the
     * outcome AC6 exists to prevent. The whole mapping is now inside the guard. Note that
     * {@code reference_type} is passed through as an opaque string and is deliberately NOT validated
     * against an enum here (the earlier javadoc claimed otherwise).
     */
    private AdminAlertDto toDtoTolerant(Object[] row) {
        final String rawType = asString(row[1]);
        try {
            final AdminAlertType alertType = AdminAlertType.valueOf(rawType);
            final String referenceId = asString(row[2]);
            final String reason = asString(row[6]);
            return new AdminAlertDto(
                toUuid(row[0]),
                alertType.name(),
                referenceId,
                asString(row[3]),
                asString(row[4]),
                toInstant(row[5]),
                buildSummary(alertType, referenceId, reason));
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            log.warn("[ADMIN_QUEUE_UNKNOWN_ALERT_TYPE alertId={} rawType={}] skipping row — not "
                + "mappable on this instance (rolling deploy?)", asString(row[0]), rawType, e);
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID toUuid(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        return o == null ? null : Instant.parse(o.toString());
    }

    private String buildSummary(AdminAlertType type, String referenceId, String reason) {
        return switch (type) {
            case MESSAGE_REPORT -> {
                try {
                    Long msgId = Long.parseLong(referenceId);
                    yield messageRepository.findById(msgId)
                        .map(m -> preview(m.getContent()))
                        .orElse("[message not found]");
                } catch (NumberFormatException e) {
                    yield "[invalid referenceId]";
                }
            }
            case CONVERSATION_REPORT -> {
                try {
                    Long convId = Long.parseLong(referenceId);
                    List<?> reports = conversationReportRepository.findByConversationId(convId);
                    if (reports.isEmpty()) yield "[no report]";
                    var first = (com.softropic.skillars.platform.messaging.repo.ConversationReport) reports.get(0);
                    yield first.getReason() != null ? first.getReason().name() : "[no report]";
                } catch (NumberFormatException e) {
                    yield "[invalid referenceId]";
                }
            }
            case REVIEW_FLAG -> {
                try {
                    UUID reviewId = UUID.fromString(referenceId);
                    long count = reviewFlagRepository.countByReviewId(reviewId);
                    String top = topFlagReason(reviewId);
                    yield count + " flags, top: " + top;
                } catch (IllegalArgumentException e) {
                    yield "[invalid referenceId]";
                }
            }
            case MODERATION_UNRESOLVED -> {
                try {
                    Long msgId = Long.parseLong(referenceId);
                    // Lead with the reason: MODERATION_UNCERTAIN (the classifier ran and was
                    // unsure) and MODERATION_ORPHAN_SWEPT (no verdict ever landed — nothing has
                    // assessed this content at all) warrant different scrutiny from the reviewer.
                    String prefix = reason != null ? reason + ": " : "";
                    yield messageRepository.findById(msgId)
                        .map(m -> prefix + preview(m.getContent()))
                        .orElse(prefix + "[message not found]");
                } catch (NumberFormatException e) {
                    yield "[invalid referenceId]";
                }
            }
            case STRIKE_THRESHOLD -> "";
            // skillars-deferred-128 story review (Decision 2): reason distinguishes the
            // GdprErasureService causes — same reason-prefix pattern MODERATION_UNRESOLVED already
            // uses above. Complete list (skillars-deferred-157 AC2; keep in step with
            // GdprErasureService.raiseErasureAlert's Javadoc):
            //   * DEADLINE_EXCEEDED — PARENT loop budget exhausted;
            //   * CHILD_VANISHED / CHILD_CONTENDED / CHILD_DELETE_LOCK_TIMEOUT (skillars-deferred-129
            //     AC1) / CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED (skillars-deferred-137 AC1) — a child
            //     player's development.* deletion;
            //   * CHILD_BOOKING_LOCK_TIMEOUT / CHILD_BOOKING_CONTENDED (skillars-deferred-157 AC1) —
            //     the same deletion's booking.* statements;
            //   * COACH_RECAP_{SCD,TIMELINE,REPORT}_{LOCK_TIMEOUT,CONTENDED} (skillars-deferred-156
            //     AC7), COACH_AVAILABILITY_BLOCK_{LOCK_TIMEOUT,CONTENDED} (skillars-deferred-157
            //     AC4) and COACH_PLAN_NOTE_{LOCK_TIMEOUT,CONTENDED} (skillars-12-6 AC5) — the
            //     coach-erasure scrub groups;
            //   * UNCLASSIFIED_FAILURE (skillars-deferred-133 AC1) — markFailed's own catch-all, an
            //     accepted, disclosed semantic widening: this bucket also counts non-deadline
            //     failures, pool saturation and missing-row cases, not deadline exceedances alone.
            case GDPR_ERASURE_DEADLINE -> {
                String prefix = reason != null ? reason + ": " : "";
                yield prefix + "GDPR erasure request " + referenceId;
            }
            // skillars-deferred-133 AC3: referenceId is the coach_profiles id (a UUID) —
            // AdminAlertEventListener.onCoachSubscriptionOrphaned's own dedup is per-coach, not per
            // orphaned Stripe subscription id, so no free-text field for the subscription id is
            // needed here (see StripeWebhookService's own Javadoc for that dedup-granularity note).
            case SUBSCRIPTION_ORPHANED -> "Coach " + referenceId + " has a live Stripe subscription "
                + "with no matching local payment.coach_subscriptions row";
            // C2: referenceId is the booking id, reason its state (IN_PROGRESS / PAUSED /
            // PAYMENT_IN_FLIGHT). The parent's contact is resolved here, at read time, rather than
            // copied into the alert row — admin_alerts must not become a second store of contact PII.
            case SAFETY_HOLD_BOOKING -> {
                String state = reason != null ? reason : "UNKNOWN";
                yield "URGENT — coach under child-safety hold; booking " + referenceId + " is " + state
                    + " and could not be cancelled. Contact the parent: " + parentContact(referenceId);
            }
            // feat/coach-payouts-pack-and-credit: referenceId is the cash-out id
            // (payment.cash_out_reconciliations); reason carries the last refund error.
            case CASH_OUT_UNRESOLVED -> "Credit cash-out " + referenceId + ": Stripe refund not confirmed"
                + (reason != null ? " (" + reason + ")" : "")
                + " — wallet debited, reconcile in payment.cash_out_reconciliations";
            // fix/payment-payouts-disputes-webhooks: reason carries the latest state (OPENED / WON / LOST and
            // amounts for a chargeback, the transfer failure reason for a held payout).
            case CHARGEBACK -> "Card dispute on payment " + referenceId + ": " + (reason != null ? reason : "")
                + " — not-yet-released coach payouts it funded are frozen; respond in the Stripe Dashboard";
            case EXTERNAL_REFUND -> "Payment " + referenceId + " refunded outside the app"
                + (reason != null ? " (" + reason + ")" : "")
                + " — no credit or payout was adjusted; reconcile the booking and coach payout by hand";
            case PAYOUT_HELD -> "Coach payout for booking " + referenceId + " is on HOLD"
                + (reason != null ? " (" + reason + ")" : "")
                + " — the transfer cannot be made; see runbook COACH_PAYOUT_HELD";
            case PAYOUT_REVERSAL_FAILED -> "Coach payout reversal failed for booking " + referenceId
                + (reason != null ? " (" + reason + ")" : "") + " — see runbook COACH_PAYOUT_REVERSAL";
            default -> "";
        };
    }

    /**
     * C2: an admin marks an urgent {@code SAFETY_HOLD_BOOKING} alert handled (the parent was contacted,
     * the live session ended, the in-flight payment was dealt with). Only that type: every other alert
     * type is resolved by its own workflow (message approve/block, reinstate, dispute resolution, ...).
     *
     * @throws ResponseStatusException 404 when no OPEN safety-hold alert has this id (already resolved,
     *                                 by a concurrent admin too)
     */
    @Transactional
    public void resolveSafetyHoldBookingAlert(UUID alertId, Long adminId) {
        int updated = adminAlertRepository.resolveOpenAlert(alertId, AdminAlertType.SAFETY_HOLD_BOOKING.name(), adminId);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "admin.moderation.alertNotOpen");
        }
        log.info("Safety-hold booking alert resolved: alertId={} adminId={}", alertId, adminId);
    }

    /**
     * fix/payment-payouts-disputes-webhooks: an admin marks a payment-integrity alert (CHARGEBACK,
     * EXTERNAL_REFUND, PAYOUT_HELD, PAYOUT_REVERSAL_FAILED) handled — like SAFETY_HOLD_BOOKING, these have
     * no workflow of their own. One conditional UPDATE per type; at most one matches the id.
     *
     * @throws ResponseStatusException 404 when no OPEN payment-integrity alert has this id
     */
    @Transactional
    public void resolvePaymentIntegrityAlert(UUID alertId, Long adminId) {
        int updated = 0;
        for (AdminAlertType type : AdminAlertType.PAYMENT_INTEGRITY) {
            updated += adminAlertRepository.resolveOpenAlert(alertId, type.name(), adminId);
        }
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "admin.moderation.alertNotOpen");
        }
        log.info("Payment-integrity alert resolved: alertId={} adminId={}", alertId, adminId);
    }

    /** "email (user id)" of the booking's payer — booking.parentId is a user id (the requester's own). */
    private String parentContact(String bookingId) {
        try {
            return bookingRepository.findById(UUID.fromString(bookingId))
                .map(b -> userRepository.findById(b.getParentId())
                    .map(u -> u.getEmail() + " (user " + u.getId() + ")")
                    .orElse("[user " + b.getParentId() + " not found]"))
                .orElse("[booking not found]");
        } catch (IllegalArgumentException e) {
            return "[invalid referenceId]";
        }
    }

    /**
     * Truncates a content preview on a code-point boundary. {@code substring(0, 100)} cuts at a
     * UTF-16 index and will split a surrogate pair — content such as {@code "a" + "😀".repeat(60)}
     * ends the preview on an unpaired high surrogate, which is not encodable as UTF-8, so Jackson
     * fails the whole queue page mid-serialization rather than corrupting one field. Messages can
     * carry up to 2000 code points (up to 4000 UTF-16 chars), so this is reachable content, not a
     * theoretical edge.
     */
    private static String preview(String content) {
        if (content == null) return null;
        if (content.codePointCount(0, content.length()) <= PREVIEW_CODE_POINTS) return content;
        return content.substring(0, content.offsetByCodePoints(0, PREVIEW_CODE_POINTS));
    }

    private String topFlagReason(UUID reviewId) {
        return reviewFlagRepository.findByReviewIdOrderByCreatedAtAsc(reviewId).stream()
            .collect(java.util.stream.Collectors.groupingBy(
                f -> f.getReason().name(),
                java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("NONE");
    }

    @Transactional(readOnly = true)
    public AdminQueueSummaryDto getSummary() {
        // skillars-deferred-91 AC6: raw-string grouping — a row whose alert_type this instance's
        // enum does not know is skipped with a WARN, not a 500 for the whole summary.
        List<Object[]> rows = adminAlertRepository.countOpenByTypeRaw();
        Map<AdminAlertType, Long> counts = new EnumMap<>(AdminAlertType.class);
        for (Object[] row : rows) {
            String rawType = asString(row[0]);
            AdminAlertType t;
            try {
                t = AdminAlertType.valueOf(rawType);
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("[ADMIN_QUEUE_UNKNOWN_ALERT_TYPE rawType={}] excluded from /queue/summary "
                    + "counts — enum not recognised on this instance (rolling deploy?)", rawType);
                continue;
            }
            counts.put(t, ((Number) row[1]).longValue());
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new AdminQueueSummaryDto(
            counts.getOrDefault(AdminAlertType.MESSAGE_REPORT, 0L),
            counts.getOrDefault(AdminAlertType.CONVERSATION_REPORT, 0L),
            counts.getOrDefault(AdminAlertType.REVIEW_FLAG, 0L),
            counts.getOrDefault(AdminAlertType.STRIKE_THRESHOLD, 0L),
            counts.getOrDefault(AdminAlertType.DISPUTE_RAISED, 0L),
            counts.getOrDefault(AdminAlertType.MODERATION_UNRESOLVED, 0L),
            // skillars-deferred-128 story review (Decision 2): without its own bucket here, an open
            // GDPR_ERASURE_DEADLINE alert (skillars-deferred-128 AC2/AC4) fell into `default -> ""`
            // in buildSummary below AND was invisible from this six-bucket summary entirely, while
            // still counted in `total` above — making `total` silently exceed the sum of every
            // bucket this DTO actually reports. This bucket closes that gap.
            counts.getOrDefault(AdminAlertType.GDPR_ERASURE_DEADLINE, 0L),
            // skillars-deferred-133 AC3: same fix skillars-deferred-128 applied above, for
            // SUBSCRIPTION_ORPHANED.
            counts.getOrDefault(AdminAlertType.SUBSCRIPTION_ORPHANED, 0L),
            counts.getOrDefault(AdminAlertType.SAFETY_HOLD_BOOKING, 0L),
            counts.getOrDefault(AdminAlertType.CASH_OUT_UNRESOLVED, 0L),
            AdminAlertType.PAYMENT_INTEGRITY.stream().mapToLong(t -> counts.getOrDefault(t, 0L)).sum(),
            total);
    }
}
