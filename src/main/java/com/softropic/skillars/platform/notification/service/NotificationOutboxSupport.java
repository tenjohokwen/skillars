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
 * <p><strong>Code review 2026-09-15 (H1) corrected this section — it previously claimed an in-memory
 * failure is "not atomic," which is false.</strong> {@link #enqueueEmail} is {@code
 * Propagation.MANDATORY}: it always <em>joins</em> the caller's existing transaction rather than
 * starting its own. When a participating (non-new) transactional method throws an exception that
 * matches Spring's default rollback rule (any unchecked exception, and {@link IllegalStateException}
 * is one), {@code TransactionInterceptor} marks the underlying transaction rollback-only <em>before
 * the exception ever reaches the caller</em> — {@code AbstractPlatformTransactionManager.rollback()}
 * takes the "not a new transaction" branch, which sets the flag rather than performing a real
 * rollback, precisely because it is not this method's transaction to roll back. A listener's own
 * {@code catch (Exception)} runs after that flag is already set; catching the exception stops it
 * from propagating further, but it cannot un-set a rollback-only flag once {@code
 * TransactionInterceptor} has set it. Both failure kinds therefore behave identically:
 *
 * <ul>
 *   <li><strong>Infrastructure failure (the outbox {@code INSERT} itself fails) — atomic.</strong>
 *       A DB error marks the transaction rollback-only, so the business transaction rolls back at
 *       commit with {@code UnexpectedRollbackException} <em>whether or not</em> the listener catches
 *       the exception. Nothing can commit a booking whose outbox row failed to write.</li>
 *   <li><strong>In-memory failure (serialisation, a bug building the payload) — also atomic, by the
 *       same mechanism.</strong> {@link #enqueueEmail} rethrows rather than swallowing (it is the
 *       wrong layer to decide what a caller does with the failure), but by the time any listener's
 *       {@code catch (Exception)} runs, {@code MANDATORY} propagation has already marked the shared
 *       transaction rollback-only. The listener's catch only controls whether the {@code
 *       IllegalStateException} itself propagates further (it does not, today) — it does not, and
 *       cannot, save the business commit. The business transaction — a booking, a session-pack
 *       change, or (story ses-1.4) a registration/resend-OTP request — rolls back with a 500 either
 *       way. AC2's string-typed {@code data} contract shrinks the input space that can trigger this
 *       to essentially nothing.</li>
 * </ul>
 *
 * <p>Payload construction should still stay defensive — no I/O, no external call, no lookup that can
 * fail on data the business transaction has not already validated — simply because there is no
 * upside to a payload-construction bug taking down an otherwise-valid business operation. None of the
 * producing listeners hold a repository, which is what makes that structural rather than a rule
 * someone has to remember.
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

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * MUST be a cross-bean call (the producing listeners live in a separate {@code ...infrastructure
     * .listener} package — {@code platform.notification} for booking/session-pack,
     * {@code platform.security} for registration/OTP) so the propagation advice goes through the
     * proxy.
     *
     * <p>{@link Propagation#MANDATORY}, not {@code REQUIRED}: "there must already be a business
     * transaction to join" is this method's whole contract after AC4, and {@code MANDATORY} makes a
     * caller that forgets fail loudly at the call instead of quietly opening its own transaction and
     * reintroducing the non-atomic window. Every call path was audited when this changed — all 28
     * publish sites across twelve producers are inside {@code @Transactional} or a
     * {@code TransactionTemplate}. The callers of this method were {@code BookingEmailListener} and
     * {@code SessionPackEmailListener} when that audit was done; story ses-1.4 added three more
     * ({@code Coach}/{@code Parent}/{@code PlayerRegistrationEmailListener}) — all five are
     * {@code BEFORE_COMMIT}, and the audit's conclusion (every publish site already has an ambient
     * business transaction to join) holds for all five, not just the original two.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEmail(EmailTemplate template, Recipient recipient, Map<String, Object> data, String sendId) {
        // Stamped here, at enqueue time, not recomputed at delivery — otherwise a message that sat in
        // the outbox through an outage is delivered with a fresh deadline and any staleness guard is
        // unreachable (skillars-deferred-91 code review).
        //
        // Story ses-1.4 AC3: deadline now comes from the template itself (COACH_OTP/PARENT_OTP/
        // PLAYER_OTP get a much shorter window than the 24h default), not the fixed constant.
        NotificationEmailPayload payload = new NotificationEmailPayload(
            template.name(), recipient.getEmail(), recipient.getLangKey(), recipient.getFirstname(), sendId,
            Instant.now().plus(template.deliveryDeadline()), data);
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
            // So: rethrow. This layer reports the failure; it does not decide what happens next. Each
            // listener's catch (Exception) still logs it at ERROR with the sendId — but per this
            // class's javadoc, "Failure semantics" (corrected 2026-09-15, H1), that catch does not
            // save the business commit: MANDATORY propagation has already marked the shared
            // transaction rollback-only by the time the catch runs, same as the outbox-INSERT-failure
            // path above. The catch only prevents this exception itself from propagating further.
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
     *
     * <p>{@code firstname} (story ses-1.4 AC2) is nullable and, for every producer that predates
     * this story, always {@code null} — {@code null} round-trips through Jackson as {@code null}, so
     * no existing outbox row's shape changes and no existing template (none of which reads
     * {@code ${recipient.firstname}}) is affected. Only the six registration templates populate it.
     */
    public record NotificationEmailPayload(String template, String toAddress, String langKey, String firstname,
                                           String sendId, Instant deadline, Map<String, Object> data) {
    }
}
