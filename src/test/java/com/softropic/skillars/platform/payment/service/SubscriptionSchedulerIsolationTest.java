package com.softropic.skillars.platform.payment.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscription;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChange;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChange;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-116 AC1/AC2: {@code SubscriptionService.applyPendingChanges()} and
 * {@code checkPastDueGracePeriod()} were restructured from a single method-level {@code @Transactional}
 * wrapping the whole batch into short-transaction batch load + per-item {@code TransactionTemplate}
 * execution wrapped in try/catch. These tests pin the resulting failure-isolation behavior: one bad
 * row must not roll back its siblings in either loop, in either method, and a failed row's mutation
 * must not be persisted (evidenced here via its {@code applied} flag / tier staying unchanged).
 *
 * <p>No existing test file exercised this failure-isolation shape before this story (checked
 * {@code SubscriptionLifecycleIT}, {@code PastDueGracePeriodTest}, {@code TierEntitlementGatingTest}
 * per the story's own Dev Notes) — new test-class territory, not an extension.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionSchedulerIsolationTest {

    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock CoachSubscriptionChangeRepository coachSubscriptionChangeRepository;
    @Mock PlayerSubscriptionChangeRepository playerSubscriptionChangeRepository;
    @Mock CoachSubscriptionRepository coachSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock ConfigService configService;
    @Mock StripeClient stripeClient;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ParentPlayerLinkRepository parentPlayerLinkRepository;
    @Mock TransactionTemplate transactionTemplate;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PessimisticLockRetryer lockRetryer;

    @InjectMocks SubscriptionService service;

    @BeforeEach
    void setUpTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        // skillars-deferred-131 AC1 Fix 4: syncMarketplaceTier now takes the coach_profiles row lock
        // before its find-or-create. lenient() since skillars-deferred-132's reconcileMarketplaceTiers
        // tests include a "tiers already match" case that never reaches syncMarketplaceTier at all.
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        lenient().when(coachProfileRepository.findByIdForUpdate(any()))
            .thenReturn(java.util.Optional.of(Instancio.create(CoachProfile.class)));
    }

    // ─── AC1: applyPendingChanges() ────────────────────────────────────────────────

    @Test
    void applyPendingChanges_malformedCoachToTier_othersStillApply() {
        UUID failingCoachId = UUID.randomUUID();
        UUID validCoachId = UUID.randomUUID();
        long validPlayerId = 7001L;

        // Not a real CoachSubscriptionTier constant — reproduces the unconstrained VARCHAR(20)
        // to_tier column feeding straight into CoachSubscriptionTier.valueOf(...).
        CoachSubscriptionChange failingCoachChange = coachChange(failingCoachId, "BOGUS_TIER");
        CoachSubscriptionChange validCoachChange = coachChange(validCoachId, "ACADEMY");
        PlayerSubscriptionChange validPlayerChange = playerChange(validPlayerId, "PRO");

        when(coachSubscriptionChangeRepository.findPendingForScheduler(any()))
            .thenReturn(List.of(failingCoachChange, validCoachChange));
        when(playerSubscriptionChangeRepository.findPendingForScheduler(any()))
            .thenReturn(List.of(validPlayerChange));
        when(paymentCoachSubscriptionRepository.findByCoachId(failingCoachId))
            .thenReturn(Optional.of(coachSub(failingCoachId, "INSTRUCTOR")));
        when(paymentCoachSubscriptionRepository.findByCoachId(validCoachId))
            .thenReturn(Optional.of(coachSub(validCoachId, "INSTRUCTOR")));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(validPlayerId))
            .thenReturn(Optional.of(playerSub(validPlayerId, "SEMI_PRO")));

        service.applyPendingChanges();

        assertThat(failingCoachChange.isApplied())
            .as("malformed to_tier must leave applied=false so it is re-selected next run")
            .isFalse();
        assertThat(validCoachChange.isApplied()).isTrue();
        assertThat(validPlayerChange.isApplied()).isTrue();
    }

    @Test
    void applyPendingChanges_playerDowngradeWriteFails_othersStillApply() {
        UUID validCoachId = UUID.randomUUID();
        long failingPlayerId = 7002L;
        long validPlayerId = 7003L;

        CoachSubscriptionChange validCoachChange = coachChange(validCoachId, "ACADEMY");
        PlayerSubscriptionChange failingPlayerChange = playerChange(failingPlayerId, "PRO");
        PlayerSubscriptionChange validPlayerChange = playerChange(validPlayerId, "PRO");

        PaymentPlayerSubscription failingSub = playerSub(failingPlayerId, "SEMI_PRO");

        when(coachSubscriptionChangeRepository.findPendingForScheduler(any()))
            .thenReturn(List.of(validCoachChange));
        when(playerSubscriptionChangeRepository.findPendingForScheduler(any()))
            .thenReturn(List.of(failingPlayerChange, validPlayerChange));
        when(paymentCoachSubscriptionRepository.findByCoachId(validCoachId))
            .thenReturn(Optional.of(coachSub(validCoachId, "INSTRUCTOR")));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(failingPlayerId))
            .thenReturn(Optional.of(failingSub));
        when(paymentPlayerSubscriptionRepository.save(failingSub))
            .thenThrow(new RuntimeException("simulated DB write failure"));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(validPlayerId))
            .thenReturn(Optional.of(playerSub(validPlayerId, "SEMI_PRO")));

        service.applyPendingChanges();

        assertThat(validCoachChange.isApplied())
            .as("a failure in the second (player) loop must not have prevented the first (coach) loop from applying")
            .isTrue();
        assertThat(failingPlayerChange.isApplied()).isFalse();
        assertThat(validPlayerChange.isApplied())
            .as("one player row failing must not abort its sibling player rows")
            .isTrue();
    }

    @Test
    void applyPendingChanges_bothCoachAndPlayerFail_neitherLoopAbortsTheOther() {
        UUID failingCoachId = UUID.randomUUID();
        UUID validCoachId = UUID.randomUUID();
        long failingPlayerId = 7004L;
        long validPlayerId = 7005L;

        CoachSubscriptionChange failingCoachChange = coachChange(failingCoachId, "NOT_A_TIER");
        CoachSubscriptionChange validCoachChange = coachChange(validCoachId, "ACADEMY");
        PlayerSubscriptionChange failingPlayerChange = playerChange(failingPlayerId, "PRO");
        PlayerSubscriptionChange validPlayerChange = playerChange(validPlayerId, "PRO");

        PaymentPlayerSubscription failingPlayerSub = playerSub(failingPlayerId, "SEMI_PRO");

        when(coachSubscriptionChangeRepository.findPendingForScheduler(any()))
            .thenReturn(List.of(failingCoachChange, validCoachChange));
        when(playerSubscriptionChangeRepository.findPendingForScheduler(any()))
            .thenReturn(List.of(failingPlayerChange, validPlayerChange));
        when(paymentCoachSubscriptionRepository.findByCoachId(failingCoachId))
            .thenReturn(Optional.of(coachSub(failingCoachId, "INSTRUCTOR")));
        when(paymentCoachSubscriptionRepository.findByCoachId(validCoachId))
            .thenReturn(Optional.of(coachSub(validCoachId, "INSTRUCTOR")));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(failingPlayerId))
            .thenReturn(Optional.of(failingPlayerSub));
        when(paymentPlayerSubscriptionRepository.save(failingPlayerSub))
            .thenThrow(new RuntimeException("simulated DB write failure"));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(validPlayerId))
            .thenReturn(Optional.of(playerSub(validPlayerId, "SEMI_PRO")));

        service.applyPendingChanges();

        assertThat(failingCoachChange.isApplied()).isFalse();
        assertThat(failingPlayerChange.isApplied()).isFalse();
        assertThat(validCoachChange.isApplied()).isTrue();
        assertThat(validPlayerChange.isApplied()).isTrue();
    }

    // ─── AC2: checkPastDueGracePeriod() ────────────────────────────────────────────

    @Test
    void checkPastDueGracePeriod_coachDowngradeWriteFails_othersStillDowngrade() {
        when(configService.getBoundedLong("subscription.pastDue.gracePeriodDays", 0L, 365L)).thenReturn(7L);

        PaymentCoachSubscription failingCoach = pastDueCoach("INSTRUCTOR");
        PaymentCoachSubscription validCoach = pastDueCoach("ACADEMY");
        PaymentPlayerSubscription validPlayer = pastDuePlayer("SEMI_PRO");

        when(paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any()))
            .thenReturn(List.of(failingCoach, validCoach));
        when(paymentPlayerSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any()))
            .thenReturn(List.of(validPlayer));
        when(paymentCoachSubscriptionRepository.save(failingCoach))
            .thenThrow(new RuntimeException("simulated DB write failure"));

        service.checkPastDueGracePeriod();

        // Note on scope: unlike AC1's `applied` flag (a separate row only stamped true once the
        // whole per-item block succeeds), the failing coach's in-memory tier/status are mutated
        // before the throwing save() call — in a real transaction that write is never committed
        // (the whole per-item transaction rolls back), but a plain Mockito save() stub cannot
        // reproduce that rollback on a POJO. What this test actually pins, per AC2's "Verified by",
        // is that the failure does not propagate past its own per-item transaction and abort its
        // siblings.
        assertThat(validCoach.getTier()).isEqualTo("SCOUT");
        assertThat(validCoach.getStatus()).isEqualTo("CANCELLED");
        assertThat(validPlayer.getTier()).isEqualTo("ATHLETE");
        assertThat(validPlayer.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void checkPastDueGracePeriod_bothCoachAndPlayerFail_neitherLoopAbortsTheOther() {
        when(configService.getBoundedLong("subscription.pastDue.gracePeriodDays", 0L, 365L)).thenReturn(7L);

        PaymentCoachSubscription failingCoach = pastDueCoach("INSTRUCTOR");
        PaymentCoachSubscription validCoach = pastDueCoach("ACADEMY");
        PaymentPlayerSubscription failingPlayer = pastDuePlayer("SEMI_PRO");
        PaymentPlayerSubscription validPlayer = pastDuePlayer("PRO");

        when(paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any()))
            .thenReturn(List.of(failingCoach, validCoach));
        when(paymentPlayerSubscriptionRepository.findByStatusAndPastDueSinceBefore(any(), any()))
            .thenReturn(List.of(failingPlayer, validPlayer));
        when(paymentCoachSubscriptionRepository.save(failingCoach))
            .thenThrow(new RuntimeException("simulated DB write failure"));
        when(paymentPlayerSubscriptionRepository.save(failingPlayer))
            .thenThrow(new RuntimeException("simulated DB write failure"));

        service.checkPastDueGracePeriod();

        // See the sibling single-failure test above for why the failing rows' in-memory tier is not
        // asserted here — this test's job is proving neither loop aborts the other.
        assertThat(validCoach.getTier()).isEqualTo("SCOUT");
        assertThat(validPlayer.getTier()).isEqualTo("ATHLETE");
    }

    // ─── AC1 Fix 3: reconcileMarketplaceTiers() ────────────────────────────────────

    /**
     * skillars-deferred-132 AC1 Fix 3. A coach whose {@code marketplace.coach_subscriptions} tier is
     * stale relative to {@code payment.coach_subscriptions} gets corrected by one sweep pass, via the
     * same {@code syncMarketplaceTier} write path the two sibling schedulers already call.
     */
    @Test
    void reconcileMarketplaceTiers_staleMarketplaceTier_correctedViaSyncMarketplaceTier() {
        UUID coachId = UUID.randomUUID();
        PaymentCoachSubscription paymentSub = coachSub(coachId, "INSTRUCTOR");
        CoachSubscription marketplaceSub = Instancio.of(CoachSubscription.class)
            .set(field(CoachSubscription::getCoachId), coachId)
            .set(field(CoachSubscription::getTier), CoachSubscriptionTier.SCOUT)
            .create();

        when(paymentCoachSubscriptionRepository.findAllByStatusIn(List.of("ACTIVE", "TRIALLING")))
            .thenReturn(List.of(paymentSub));
        when(coachSubscriptionRepository.findByCoachId(coachId)).thenReturn(Optional.of(marketplaceSub));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(SubscriptionService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.reconcileMarketplaceTiers();
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(marketplaceSub.getTier())
            .as("the stale SCOUT marketplace projection must be corrected to match the payment-side tier")
            .isEqualTo(CoachSubscriptionTier.INSTRUCTOR);
        verify(coachSubscriptionRepository).save(marketplaceSub);
        assertThat(logCapture.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("COACH_TIER_RECONCILED");
        });
    }

    /**
     * skillars-deferred-132 AC1 Fix 3. A coach whose tiers already match must cause no write and no
     * {@code COACH_TIER_RECONCILED} log line — the corrected enum-vs-{@code valueOf} comparison this
     * story's own story-review pass fixed (the original snippet's {@code Enum.equals(String)} would
     * have made this assertion impossible to pass, since it was always false).
     */
    @Test
    void reconcileMarketplaceTiers_tiersAlreadyMatch_noWriteNoReconciledLog() {
        UUID coachId = UUID.randomUUID();
        PaymentCoachSubscription paymentSub = coachSub(coachId, "INSTRUCTOR");
        CoachSubscription marketplaceSub = Instancio.of(CoachSubscription.class)
            .set(field(CoachSubscription::getCoachId), coachId)
            .set(field(CoachSubscription::getTier), CoachSubscriptionTier.INSTRUCTOR)
            .create();

        when(paymentCoachSubscriptionRepository.findAllByStatusIn(List.of("ACTIVE", "TRIALLING")))
            .thenReturn(List.of(paymentSub));
        when(coachSubscriptionRepository.findByCoachId(coachId)).thenReturn(Optional.of(marketplaceSub));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(SubscriptionService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.reconcileMarketplaceTiers();
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        verify(coachSubscriptionRepository, never()).save(any());
        assertThat(logCapture.list).noneSatisfy(event ->
            assertThat(event.getFormattedMessage()).contains("COACH_TIER_RECONCILED"));
    }

    /**
     * skillars-deferred-132 AC1 Fix 3. A payment row with a tier outside {@code CoachSubscriptionTier}
     * is skipped (logged, not thrown) and must not abort the rest of the sweep.
     */
    @Test
    void reconcileMarketplaceTiers_unrecognizedTier_skippedNotThrown_doesNotAbortSweep() {
        UUID badCoachId = UUID.randomUUID();
        UUID validCoachId = UUID.randomUUID();
        PaymentCoachSubscription badSub = coachSub(badCoachId, "NOT_A_REAL_TIER");
        PaymentCoachSubscription validSub = coachSub(validCoachId, "ACADEMY");
        CoachSubscription validMarketplaceSub = Instancio.of(CoachSubscription.class)
            .set(field(CoachSubscription::getCoachId), validCoachId)
            .set(field(CoachSubscription::getTier), CoachSubscriptionTier.SCOUT)
            .create();

        when(paymentCoachSubscriptionRepository.findAllByStatusIn(List.of("ACTIVE", "TRIALLING")))
            .thenReturn(List.of(badSub, validSub));
        when(coachSubscriptionRepository.findByCoachId(validCoachId)).thenReturn(Optional.of(validMarketplaceSub));

        service.reconcileMarketplaceTiers();

        assertThat(validMarketplaceSub.getTier())
            .as("the bad row's IllegalArgumentException from CoachSubscriptionTier.valueOf must not "
                + "abort the sweep before the valid row is reconciled")
            .isEqualTo(CoachSubscriptionTier.ACADEMY);
        verify(coachSubscriptionRepository).save(validMarketplaceSub);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private CoachSubscriptionChange coachChange(UUID coachId, String toTier) {
        return Instancio.of(CoachSubscriptionChange.class)
            .set(field(CoachSubscriptionChange::getCoachId), coachId)
            .set(field(CoachSubscriptionChange::getFromTier), "INSTRUCTOR")
            .set(field(CoachSubscriptionChange::getToTier), toTier)
            .set(field(CoachSubscriptionChange::getEffectiveAt), Instant.now().minus(1, ChronoUnit.HOURS))
            .set(field(CoachSubscriptionChange::getTriggerSource), "SCHEDULED")
            // isApplied() defaults to false on the real entity and several tests here depend on that
            // starting value (e.g. "malformed tier leaves applied=false so it is re-selected next
            // run") — Instancio's random boolean generation would otherwise flip it non-deterministically.
            .set(field(CoachSubscriptionChange::isApplied), false)
            .create();
    }

    private PlayerSubscriptionChange playerChange(long playerId, String toTier) {
        return Instancio.of(PlayerSubscriptionChange.class)
            .set(field(PlayerSubscriptionChange::getPlayerId), playerId)
            .set(field(PlayerSubscriptionChange::getFromTier), "SEMI_PRO")
            .set(field(PlayerSubscriptionChange::getToTier), toTier)
            .set(field(PlayerSubscriptionChange::getEffectiveAt), Instant.now().minus(1, ChronoUnit.HOURS))
            .set(field(PlayerSubscriptionChange::getTriggerSource), "SCHEDULED")
            // See coachChange's identical comment above.
            .set(field(PlayerSubscriptionChange::isApplied), false)
            .create();
    }

    private PaymentCoachSubscription coachSub(UUID coachId, String tier) {
        return Instancio.of(PaymentCoachSubscription.class)
            .set(field(PaymentCoachSubscription::getCoachId), coachId)
            .set(field(PaymentCoachSubscription::getTier), tier)
            .set(field(PaymentCoachSubscription::getStatus), "ACTIVE")
            .create();
    }

    private PaymentPlayerSubscription playerSub(long playerId, String tier) {
        return Instancio.of(PaymentPlayerSubscription.class)
            .set(field(PaymentPlayerSubscription::getPlayerId), playerId)
            .set(field(PaymentPlayerSubscription::getTier), tier)
            .set(field(PaymentPlayerSubscription::getStatus), "ACTIVE")
            .create();
    }

    private PaymentCoachSubscription pastDueCoach(String tier) {
        return Instancio.of(PaymentCoachSubscription.class)
            .set(field(PaymentCoachSubscription::getCoachId), UUID.randomUUID())
            .set(field(PaymentCoachSubscription::getTier), tier)
            .set(field(PaymentCoachSubscription::getStatus), "PAST_DUE")
            .set(field(PaymentCoachSubscription::getPastDueSince), Instant.now().minus(8, ChronoUnit.DAYS))
            .create();
    }

    private static long nextPastDuePlayerId = 9000L;

    private PaymentPlayerSubscription pastDuePlayer(String tier) {
        // Direct list members in these tests, not looked up by id — uniqueness only avoids
        // confusing test failure output, no functional dependency on the value.
        return Instancio.of(PaymentPlayerSubscription.class)
            .set(field(PaymentPlayerSubscription::getPlayerId), nextPastDuePlayerId++)
            .set(field(PaymentPlayerSubscription::getTier), tier)
            .set(field(PaymentPlayerSubscription::getStatus), "PAST_DUE")
            .set(field(PaymentPlayerSubscription::getPastDueSince), Instant.now().minus(8, ChronoUnit.DAYS))
            .create();
    }
}
