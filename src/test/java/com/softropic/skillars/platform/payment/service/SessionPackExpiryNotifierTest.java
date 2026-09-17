package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-120 AC3 Finding 3 — written from scratch (no existing
 * {@code PaymentPendingSweeperTest}/{@code -IT} case of this shape to mirror; grepped for
 * {@code OptimisticLocking}, zero hits in either file), using {@link PaymentPendingSweeper}'s
 * *production* {@code catch (OptimisticLockingFailureException e) { log.info(...) }} split
 * (:129-134) as the pattern.
 *
 * <p>{@code SessionPackPurchase} carries a real {@code @Version} column, so a legitimate concurrent
 * {@code extendPack}/{@code pausePack} write landing between this scheduler's batch load and its own
 * {@code save(pack)} throws {@code OptimisticLockingFailureException} at commit — a benign,
 * self-correcting race, not an operator-actionable failure. Logging it at ERROR (the pre-fix
 * behaviour, still exercised by the generic-exception test below) dilutes the ERROR signal this
 * class's alerts depend on.
 */
@ExtendWith(MockitoExtension.class)
class SessionPackExpiryNotifierTest {

    private static final UUID COACH_ID = UUID.randomUUID();

    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;

    private SessionPackExpiryNotifier notifier;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger notifierLogger;

    @BeforeEach
    void setUp() {
        notifier = new SessionPackExpiryNotifier(sessionPackPurchaseRepository, coachProfileRepository,
            userRepository, eventPublisher, transactionTemplate);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
        lenient().when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach()));
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());

        notifierLogger = (Logger) LoggerFactory.getLogger(SessionPackExpiryNotifier.class);
        // logback-test.xml pins root at WARN; raise this logger alone so the INFO assertion below
        // actually observes the event instead of it being filtered before reaching the appender.
        notifierLogger.setLevel(Level.INFO);
        logAppender = new ListAppender<>();
        logAppender.start();
        notifierLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        notifierLogger.detachAppender(logAppender);
        notifierLogger.setLevel(null);
    }

    private static CoachProfile coach() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(9001L);
        coach.setDisplayName("Coach Test");
        coach.setCanonicalTimezone("UTC");
        return coach;
    }

    private static SessionPackPurchase pack() {
        SessionPackPurchase pack = new SessionPackPurchase();
        pack.setPurchaseId(UUID.randomUUID());
        pack.setParentId(4001L);
        pack.setPlayerId(4002L);
        pack.setCoachId(COACH_ID);
        pack.setRemainingSessions(3);
        pack.setExpiresAt(Instant.now().plusSeconds(600));
        return pack;
    }

    @Test
    void notifyExpiringPacks_optimisticLockingFailure_logsInfoAndContinuesLoop() {
        SessionPackPurchase racedPack = pack();
        SessionPackPurchase healthyPack = pack();
        when(sessionPackPurchaseRepository.findExpiringWithinWindowAndSessionsRemaining(any(), any()))
            .thenReturn(List.of(racedPack, healthyPack));
        // The racedPack's save loses a concurrent extendPack/pausePack write; healthyPack's succeeds.
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(sessionPackPurchaseRepository).save(racedPack);

        notifier.notifyExpiringPacks();

        // Loop continues past the raced pack — the healthy pack still gets its warning event.
        verify(sessionPackPurchaseRepository).save(healthyPack);
        // racedPack's save() throws before eventPublisher.publishEvent runs, so exactly one
        // publishEvent call proves the healthy pack specifically got its event — not merely that
        // publishEvent was called at all (code review 2026-09-17, Patch #10).
        verify(eventPublisher, times(1)).publishEvent(any(SessionPackExpiryWarningEvent.class));

        assertThat(logAppender.list)
            .as("the optimistic-lock race must log at INFO, not ERROR")
            .anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage()).contains(racedPack.getPurchaseId().toString());
            });
        assertThat(logAppender.list)
            .noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void notifyExpiringPacks_genericFailure_stillLogsError() {
        SessionPackPurchase failingPack = pack();
        when(sessionPackPurchaseRepository.findExpiringWithinWindowAndSessionsRemaining(any(), any()))
            .thenReturn(List.of(failingPack));
        doThrow(new RuntimeException("unexpected"))
            .when(sessionPackPurchaseRepository).save(failingPack);

        notifier.notifyExpiringPacks();

        // Pinned to the specific message/purchaseId, and that the event was never published for
        // this pack (save() throws before publishEvent runs) — code review 2026-09-17, Patch #10:
        // the prior version asserted only "some ERROR event exists", which would also pass
        // unmodified (a RuntimeException always hit the generic catch, before and after this fix).
        assertThat(logAppender.list)
            .as("a genuinely unrecoverable failure must still surface at ERROR")
            .anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getFormattedMessage()).contains("Failed to send expiry warning")
                    .contains(failingPack.getPurchaseId().toString());
            });
        verify(eventPublisher, never()).publishEvent(any());
    }
}
