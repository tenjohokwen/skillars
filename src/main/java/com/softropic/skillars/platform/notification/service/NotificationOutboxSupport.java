package com.softropic.skillars.platform.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Enqueues a transactional email onto the durable outbox (skillars-deferred-91 AC3), <em>inside</em>
 * the business transaction that promised it (skillars-deferred-92 AC4).
 *
 * <h2>What this guarantees</h2>
 *
 * The producing listeners in {@code ...infrastructure.listener} are
 * {@code @TransactionalEventListener(BEFORE_COMMIT)}, so they run inside the producing transaction's
 * synchronisation, and {@link #enqueueEmail} joins that transaction ({@link Propagation#MANDATORY}).
 * The outbox row and the business work therefore commit together or not at all — which is the entire
 * point of a transactional outbox, and what skillars-deferred-91 AC1 asked for.
 *
 * <h2>What it used to guarantee, and why that was weaker</h2>
 *
 * Until skillars-deferred-92 the listeners were {@code AFTER_COMMIT} and this method was
 * {@code REQUIRES_NEW}. Two consequences:
 *
 * <ul>
 *   <li><strong>Closed by deferred-91:</strong> the nested-AFTER_COMMIT silent drop —
 *       {@code MailManager}'s own {@code @TransactionalEventListener(AFTER_COMMIT,
 *       fallbackExecution=false)} listener had no transaction to hang off, so the email was never
 *       sent at all. Routing through the outbox made it re-drivable, with {@code attempts} /
 *       {@code last_error} and an {@code [OUTBOX_STUCK]} alert.</li>
 *   <li><strong>Closed by deferred-92 AC4:</strong> a crash or DB failure in the window between the
 *       business commit and this enqueue's own commit lost the email outright. The
 *       {@code AFTER_COMMIT} → {@code BEFORE_COMMIT} flip removes the window; there is no longer a
 *       second transaction to fail.</li>
 * </ul>
 *
 * <h2>Failure semantics — read before changing anything here</h2>
 *
 * A throw inside a {@code BEFORE_COMMIT} listener rolls the business transaction back. That is the
 * correct atomic semantic, but it means a bad email payload could roll back a booking. The split
 * this codebase settled on:
 *
 * <ul>
 *   <li><strong>Infrastructure failure (the outbox {@code INSERT} itself fails) — atomic.</strong>
 *       A DB error marks the transaction rollback-only, so the business transaction rolls back at
 *       commit with {@code UnexpectedRollbackException} <em>whether or not</em> the listener catches
 *       the exception. Nothing can commit a booking whose outbox row failed to write.</li>
 *   <li><strong>In-memory failure (serialisation, a bug building the payload) — not atomic, by
 *       decision.</strong> {@link #enqueueEmail} rethrows rather than swallowing (it is the wrong
 *       layer to set that policy), and each listener's {@code catch (Exception)} then decides: a
 *       notification failure must not roll back the business operation it merely describes. The
 *       residual is one lost email, logged at ERROR, while the booking commits. AC2's string-typed
 *       {@code data} contract shrinks the input space that can trigger it to essentially nothing.</li>
 * </ul>
 *
 * <p>Payload construction must stay defensive to keep that split honest: build the map, serialise,
 * enqueue — no I/O, no external call, no lookup that can fail on data the business transaction has
 * not already validated. Neither listener holds a repository, which is what makes this structural
 * rather than a rule someone has to remember.
 *
 * <h2>Email template data is string-typed by contract (AC2)</h2>
 *
 * {@code data} is serialised to JSON here and deserialised in
 * {@link NotificationEmailOutboxHandler}, so a value's Java type does not survive the round trip: an
 * {@code Instant} returns as a {@code String}, a {@code BigDecimal} as a {@code Double}
 * ({@code 40.00} → {@code 40.0}). <strong>Format numbers and instants at the producer, never in the
 * template.</strong> Every value put into an email {@code data} map must be a {@code String} or a
 * {@code List<String>}; {@code EmailDataStringContractTest} fails the build otherwise.
 *
 * @see RefundOutboxSupport for the same pattern on the money path (already correct — it is called
 *     inside its listener's own {@code REQUIRES_NEW}; do not "fix" it)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxSupport {

    public static final String AGGREGATE_TYPE = "NOTIFICATION_EMAIL";

    /** How long after enqueue the send is still considered timely. Stamped into the payload. */
    private static final Duration DELIVERY_DEADLINE = Duration.ofDays(1);

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * MUST be a cross-bean call (the listeners live in {@code ...infrastructure.listener}) so the
     * propagation advice goes through the proxy.
     *
     * <p>{@link Propagation#MANDATORY}, not {@code REQUIRED}: "there must already be a business
     * transaction to join" is this method's whole contract after AC4, and {@code MANDATORY} makes a
     * caller that forgets fail loudly at the call instead of quietly opening its own transaction and
     * reintroducing the non-atomic window. Every call path was audited when this changed — all 28
     * publish sites across twelve producers are inside {@code @Transactional} or a
     * {@code TransactionTemplate}, and the only two callers of this method are the two email
     * listeners, both {@code BEFORE_COMMIT}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEmail(EmailTemplate template, Recipient recipient, Map<String, Object> data, String sendId) {
        // Stamped here, at enqueue time, not recomputed at delivery — otherwise a message that sat in
        // the outbox through an outage is delivered with a fresh deadline and any staleness guard is
        // unreachable (skillars-deferred-91 code review).
        NotificationEmailPayload payload = new NotificationEmailPayload(
            template.name(), recipient.getEmail(), recipient.getLangKey(), sendId,
            Instant.now().plus(DELIVERY_DEADLINE), data);
        try {
            outboxService.enqueue(AGGREGATE_TYPE, objectMapper.writeValueAsString(payload));
            outboxService.requestDrainAfterCommit();
        } catch (JsonProcessingException e) {
            // AC4.2 — this used to log and return normally. That was right under AFTER_COMMIT: the
            // business transaction had already committed, so a loud log was the only move left. Under
            // BEFORE_COMMIT it is the opposite of what this class now promises — the transaction is
            // still open, and returning normally means the booking commits while its email is lost
            // inside the very method whose job is to make that impossible.
            //
            // So: rethrow. This layer reports the failure; it does not decide what the failure costs.
            // The calling listener owns that policy (see this class's javadoc, "Failure semantics") —
            // today each catches and logs, deliberately, because a malformed notification payload
            // must not roll back the booking it merely describes.
            throw new IllegalStateException(
                "[NOTIFICATION_EMAIL_ENQUEUE_FAILED] could not serialise the email payload for template="
                    + payload.template() + " to=" + payload.toAddress() + " sendId=" + payload.sendId(), e);
        }
    }

    /**
     * Outbox payload for a re-drivable transactional email.
     *
     * <p>{@code deadline} is nullable only so rows enqueued before it was introduced still
     * deserialise; {@code NotificationEmailOutboxHandler} falls back for those.
     */
    public record NotificationEmailPayload(String template, String toAddress, String langKey,
                                           String sendId, Instant deadline, Map<String, Object> data) {
    }
}
