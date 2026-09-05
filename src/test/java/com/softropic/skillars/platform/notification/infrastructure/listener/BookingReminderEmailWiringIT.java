package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.booking.contract.BookingReminderEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingReminderScheduler;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-92 AC29 — the regression guard for "booking reminder emails have never been
 * sent".
 *
 * <p>{@code BookingEmailListener.onBookingReminder} carried <strong>no annotation at all</strong>
 * until 2026-09-04. Spring dispatches {@code publishEvent} only to annotated methods and to
 * {@code ApplicationListener} beans, so an unannotated public method taking an event type is never
 * invoked; {@code BookingReminderScheduler} published {@code BookingReminderEvent} every five
 * minutes into nothing, and no other class in {@code src/main} references
 * {@code EmailTemplate.BOOKING_REMINDER}.
 *
 * <p><strong>Why this test is an IT and not another case in {@code BookingEmailListenerTest}.</strong>
 * That class calls {@code listener.onBookingReminder(event)} <em>directly</em>, which is exactly why
 * a unit suite full of green reminder tests coexisted with a feature that had never once run: a
 * direct-invocation test verifies a method body and is structurally incapable of noticing that
 * nothing dispatches to it. This test publishes through the real {@link ApplicationEventPublisher},
 * inside a real transaction, against the real container — the only shape that can fail when the
 * annotation is missing. (Dev Notes §6 of the story: "a passing unit test is not proof a feature is
 * wired".)
 *
 * <p><strong>Where the outbox row is observed.</strong> The listener is
 * {@code @TransactionalEventListener(BEFORE_COMMIT)}, so its row exists only between the listener
 * running and the commit; under the test profile {@code outboxDrainPool} is a {@code SyncTaskExecutor}
 * ({@code OutboxConfig}), so the AFTER_COMMIT drain consumes and deletes the row inline before
 * control returns. The count is therefore taken from
 * {@link TransactionSynchronization#beforeCompletion()}, which Spring's
 * {@code AbstractPlatformTransactionManager.processCommit} invokes strictly <em>after</em> every
 * {@code beforeCommit} callback and before the commit itself — no ordering assumption about two
 * same-order synchronizations is needed.
 */
class BookingReminderEmailWiringIT extends AbstractIntegrationTest {

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestMailManager testMailManager;
    @Autowired private BookingReminderScheduler bookingReminderScheduler;
    @Autowired private BookingRepository bookingRepository;

    private final String parentEmail = "ac29.parent." + UUID.randomUUID() + "@skillars-test.com";
    private final String coachEmail = "ac29.coach." + UUID.randomUUID() + "@skillars-test.com";

    private BookingReminderEvent event(String reminderType) {
        return BookingReminderEvent.builder()
            .source(this)
            .bookingId(UUID.randomUUID())
            .parentEmail(parentEmail)
            .coachEmail(coachEmail)
            .coachDisplayName("AC29 Coach")
            .requestedStartTime(Instant.now().plusSeconds(7200))
            .canonicalTimezone("Europe/Berlin")
            .reminderType(reminderType)
            .build();
    }

    /** Outbox rows for this test's two unique addresses, counted on the transaction's own connection. */
    private int myOutboxRows() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? "
                + "AND (payload::text LIKE ? OR payload::text LIKE ?)",
            Integer.class,
            NotificationOutboxSupport.AGGREGATE_TYPE, "%" + parentEmail + "%", "%" + coachEmail + "%");
    }

    @Test
    @DisplayName("publishing BookingReminderEvent writes outbox rows inside the producing transaction and delivers both reminders")
    void publishedReminderEvent_isDispatched_andEnqueuedInsideTheTransaction() {
        testMailManager.clear();
        AtomicInteger rowsAtBeforeCompletion = new AtomicInteger(-1);

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event("PRIMARY"));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCompletion() {
                    rowsAtBeforeCompletion.set(myOutboxRows());
                }
            });
        });

        assertThat(rowsAtBeforeCompletion.get())
            .as("""
                The BEFORE_COMMIT listener must have written one outbox row per recipient INSIDE the \
                producing transaction. 0 here is the AC29 bug itself: nothing dispatched to \
                onBookingReminder. -1 means beforeCompletion never ran, i.e. the transaction did not \
                reach commit at all.""")
            .isEqualTo(2);

        List<Envelope> reminders = testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.BOOKING_REMINDER)
            .toList();

        assertThat(reminders)
            .as("the drain must have turned both rows into real sends — enqueue alone is not delivery")
            .hasSize(2);
        assertThat(reminders).allSatisfy(e ->
            assertThat(e.data()).containsEntry("reminderType", "PRIMARY")
                                .containsEntry("coachDisplayName", "AC29 Coach"));
        assertThat(reminders.stream().flatMap(e -> e.recipients().stream()).map(r -> r.getEmail()))
            .containsExactlyInAnyOrder(parentEmail, coachEmail);
    }

    @Test
    @DisplayName("a rolled-back producing transaction leaves no reminder outbox row and sends nothing")
    void rolledBackTransaction_enqueuesNothing() {
        testMailManager.clear();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(event("SECONDARY"));
                throw new IllegalStateException("AC29: deliberate rollback");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("deliberate rollback");
        }

        assertThat(myOutboxRows())
            .as("a BEFORE_COMMIT listener does not fire on a transaction that never commits")
            .isZero();
        assertThat(testMailManager.getEnvelopes().values())
            .as("and nothing may have been sent")
            .noneMatch(e -> e.emailTemplate() == EmailTemplate.BOOKING_REMINDER);
    }

    /**
     * skillars-deferred-92 code review. The two tests above publish {@code BookingReminderEvent}
     * themselves, which proves the listener is wired but says nothing about the only thing that
     * publishes it in production. This drives {@code BookingReminderScheduler.processReminderWindows}
     * against a real seeded booking, so the assertion covers the chain the feature actually uses:
     * query, per-booking transaction, {@code transition}, stamp, publish, BEFORE_COMMIT enqueue,
     * drain, send.
     *
     * <p>It also pins the per-booking transaction boundary the review asked for. The stamp and the
     * status transition are visible here only because this booking's own transaction committed;
     * under the batch-wide {@code @Transactional} this method used to carry, they committed with
     * every other booking's or with none.
     */
    @Test
    @DisplayName("the scheduler transitions a real booking, stamps it, and delivers its reminder")
    void processReminderWindows_drivesTheWholeChainForARealBooking() {
        testMailManager.clear();
        long parentId = 92_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000L);
        insertParent(parentId, parentEmail);

        UUID bookingId = insertConfirmedBooking(parentId, Instant.now().plusSeconds(3600));
        releaseSchedulerLock("BookingReminderScheduler_remind");

        bookingReminderScheduler.processReminderWindows();

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.getStatus())
            .as("the primary pass transitions CONFIRMED bookings inside the reminder window")
            .isEqualTo("UPCOMING");
        assertThat(booking.getPrimaryReminderSentAt())
            .as("the stamp must have COMMITTED with this booking's own transaction; null means "
                + "either the transition threw or that transaction rolled back, which the "
                + "batch-wide @Transactional used to make indistinguishable from a batch failure")
            .isNotNull();

        List<Envelope> reminders = testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.BOOKING_REMINDER)
            .filter(e -> e.recipients().stream().anyMatch(r -> parentEmail.equals(r.getEmail())))
            .toList();
        assertThat(reminders)
            .as("the scheduler is the only production publisher of BookingReminderEvent; empty here "
                + "while the two tests above pass means the listener is wired but nothing reaches "
                + "it, which is a subtler shape of the AC29 bug rather than a different one")
            .hasSize(1);
        assertThat(reminders.get(0).data()).containsEntry("reminderType", "PRIMARY");
    }

    /**
     * bookings.parent_id/player_id/coach_id carry no FK constraints, so this needs no coach or
     * player fixture. A fresh random coach id keeps the row clear of V87's
     * {@code excl_bkg_coach_slot_overlap} exclusion constraint.
     */
    private UUID insertConfirmedBooking(long parentId, Instant startTime) {
        Booking booking = new Booking();
        booking.setParentId(parentId);
        booking.setPlayerId(parentId);
        booking.setCoachId(UUID.randomUUID());
        booking.setRequestedStartTime(startTime);
        booking.setRequestedEndTime(startTime.plusSeconds(3600));
        booking.setCanonicalTimezone("Europe/Berlin");
        booking.setStatus("CONFIRMED");
        return transactionTemplate.execute(s -> bookingRepository.save(booking)).getId();
    }

    /**
     * The reminder's parent recipient. Without a real row {@code resolveEmail} yields "" and the
     * listener filters that recipient out, so the test would pass for the wrong reason.
     */
    private void insertParent(long id, String email) {
        transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, "
                + "session_id, status, dob, email, first_name, gender, lang_key, last_name, "
                + "iso2_country, phone, activated, locked, login, login_id_type, password_hash, "
                + "otp_enabled, skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', 'Parent', 'DE', ?, "
                + "true, false, ?, 'EMAIL', 'x', false, 'PARENT', 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, "69" + (id % 100000000L), email));
    }
}
