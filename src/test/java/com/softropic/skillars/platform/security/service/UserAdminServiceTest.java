package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-118 AC2 — no existing test covered {@code UserAdminService.removeNotActivatedUsers}
 * / {@code deleteUserInTransaction} before this story (new coverage, not an extension).
 *
 * <p>Central case: {@code deleteUserInTransaction} re-fetches by login before deleting, but never
 * re-checked {@code activated} on that fresh row. A user who completes email verification (any of the
 * four registration flows) in the window between {@link UserAdminService#findExpiredUsers}'s batch
 * select and their own turn in the per-user delete loop had their now-legitimate account destroyed —
 * the re-fetch read the current, activated row and deleted it anyway.
 *
 * <p>skillars-deferred-122: {@code self} is wired to the same instance in {@link #setUp()}, mirroring
 * {@code RadarCompositeCalculationServiceTest}'s established self-proxy unit-test convention (there is
 * no Spring context here, so the real {@code @Autowired @Lazy} proxy reference production uses is
 * unavailable) — see {@link #removeNotActivatedUsers_selfNotWired_deleteNeverAttempted} for the test
 * that specifically proves the production code path genuinely goes through {@code self}, which this
 * same-instance wiring alone cannot distinguish.
 */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    private static final String LOGIN = "stale-user@example.com";

    @Mock UserRepository userRepository;

    private UserAdminService service;

    @BeforeEach
    void setUp() {
        // Plain @Data POJO, not a Spring bean here — real defaults (batchSize=100,
        // accountActivationExpirationDays=3) exercise the same values production uses.
        service = new UserAdminService(userRepository, new SecurityProperties());
        ReflectionTestUtils.setField(service, "self", service);
    }

    private User buildUser(String login, boolean activated) {
        User user = new User();
        user.setLogin(login);
        user.setActivated(activated);
        return user;
    }

    @Test
    void removeNotActivatedUsers_expiredAndStillUnactivated_isDeleted() {
        User batchUser = buildUser(LOGIN, false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(batchUser), List.of());
        // deleteUserInTransaction's own re-fetch: still unactivated, so it must proceed.
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Optional.of(buildUser(LOGIN, false)));

        service.removeNotActivatedUsers();

        verify(userRepository).delete(any(User.class));
    }

    @Test
    void removeNotActivatedUsers_activatedBetweenSelectAndDelete_isSkippedNotDeleted() {
        // Batch select read this user as not-yet-activated...
        User batchUser = buildUser(LOGIN, false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(batchUser), List.of());
        // ...but a distinct, fresher instance is what the per-user REQUIRES_NEW transaction re-fetches
        // — modeling the real race: the user's own email-verification commit landed in between.
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Optional.of(buildUser(LOGIN, true)));

        service.removeNotActivatedUsers();

        verify(userRepository, never()).delete(any());
    }

    @Test
    void removeNotActivatedUsers_batchLoopContinuesPastAPerUserException() {
        User first = buildUser("first@example.com", false);
        User second = buildUser("second@example.com", false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(first, second), List.of());
        when(userRepository.findOneByLogin("first@example.com"))
            .thenReturn(Optional.of(buildUser("first@example.com", false)));
        when(userRepository.findOneByLogin("second@example.com"))
            .thenReturn(Optional.of(buildUser("second@example.com", false)));
        doThrow(new RuntimeException("simulated delete failure"))
            .when(userRepository).delete(argThatLoginEquals("first@example.com"));

        service.removeNotActivatedUsers();

        verify(userRepository).delete(argThatLoginEquals("first@example.com"));
        verify(userRepository).delete(argThatLoginEquals("second@example.com"));
    }

    /**
     * skillars-deferred-122 code review 2026-09-18: with AC9's one-shot {@code cleanup_failed_at}
     * marker replaced by a {@code CLEANUP_FAILURE_THRESHOLD}-consecutive-<em>run</em> counter (see
     * {@code UserAdminService.recordCleanupFailure}'s Javadoc), a single run's own DB-level query
     * ({@code PageRequest.of(0, batchSize)} — always page 0, never advancing) cannot advance past an
     * entire page of distinct users that all fail deletion: none of them cross the threshold within
     * one run, so the persisted exclusion never fires this run, and the raw page the DB returns is
     * identical on every re-fetch. Further batches would just re-read the same, already-attempted
     * page — {@link #removeNotActivatedUsers_stuckUserDoesNotBlockDeletableUsersBehindIt} already
     * proves a <em>partially</em>-failing page still makes progress on its deletable entries; this
     * proves the pathological all-fail case now stops itself at exactly one batch's worth of attempts
     * instead of either silently exiting with no ERROR log (the bug this fix closes) or spinning
     * through the full {@code maxBatches} ceiling doing nothing (see
     * {@link #removeNotActivatedUsers_backlogExceedsAttemptsCeiling_stopsAtMaxBatchesCap} for that,
     * genuinely-productive, cap-hitting scenario). Uses 20,000 distinct always-failing users — well
     * more than one batch's worth — so a regression back to "keep re-fetching the same page until
     * {@code maxBatches}" would be caught by the attempt count below, not just by timing out.
     *
     * <p>{@code @Timeout} guards the CI failure mode directly: if this early-exit regresses to an
     * unbounded loop, this test would otherwise hang until the CI job's own timeout instead of failing
     * fast (code review 2026-09-17, Patch #7).
     */
    @Test
    @Timeout(10)
    void removeNotActivatedUsers_entirePageFailsDeletion_stopsAfterOneBatchNotFullCeiling() {
        List<User> pool = IntStream.range(0, 20_000)
            .mapToObj(i -> buildUser("stuck" + i + "@example.com", false))
            .toList();
        // No user ever gets deleted and cleanup_failed_at is never stamped within this one run (below
        // CLEANUP_FAILURE_THRESHOLD), so — unlike the mutable-list mocks elsewhere in this class —
        // every page-0 fetch genuinely returns the identical first batchSize(100) entries, exactly as
        // the real DB query would.
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenAnswer(inv -> {
                Pageable pageable = inv.getArgument(1);
                return pool.stream().limit(pageable.getPageSize()).toList();
            });
        when(userRepository.findOneByLogin(any()))
            .thenAnswer(inv -> Optional.of(buildUser(inv.getArgument(0), false)));
        doThrow(new RuntimeException("deterministic delete failure"))
            .when(userRepository).delete(any(User.class));

        service.removeNotActivatedUsers();

        // Exactly one batch's worth of distinct users attempted (the default batchSize, 100) — proving
        // this stopped after the one page it could make no progress on, not after re-attempting it
        // maxBatches(100) times or silently exiting with zero attempts.
        verify(userRepository, times(100)).delete(any(User.class));
        // Never crosses CLEANUP_FAILURE_THRESHOLD(3) in a single run — the permanent exclusion marker
        // must not be stamped from one run's worth of failures alone.
        verify(userRepository, never()).markCleanupFailed(any(), any());
    }

    /**
     * skillars-deferred-120 AC3 Finding 1 / skillars-deferred-122 AC7: the {@code maxBatches} safety
     * cap must still bound a single run's total work even when every delete genuinely succeeds and the
     * table genuinely shrinks batch over batch (unlike the all-failing scenario above, where the DB
     * page can never advance) — a backlog simply larger than the derived
     * {@code MAX_DELETE_ATTEMPTS_PER_RUN(10,000)} ceiling must stop there, not attempt the whole
     * thing in one run. Uses a mutable backing list (deletes actually remove entries, as in
     * {@link #removeNotActivatedUsers_stuckUserDoesNotBlockDeletableUsersBehindIt}) so each batch
     * genuinely returns fresh rows, proving the cap — not a natural empty-batch drain — is what stops
     * the loop.
     */
    @Test
    @Timeout(10)
    void removeNotActivatedUsers_backlogExceedsAttemptsCeiling_stopsAtMaxBatchesCap() {
        List<User> remaining = new ArrayList<>(IntStream.range(0, 20_000)
            .mapToObj(i -> buildUser("many" + i + "@example.com", false))
            .toList());

        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenAnswer(inv -> {
                Pageable pageable = inv.getArgument(1);
                return remaining.stream().limit(pageable.getPageSize()).toList();
            });
        when(userRepository.findOneByLogin(any())).thenAnswer(inv -> {
            String login = inv.getArgument(0);
            return remaining.stream().filter(u -> u.getLogin().equals(login)).findFirst();
        });
        doAnswer(inv -> {
            User user = inv.getArgument(0);
            remaining.removeIf(u -> u.getLogin().equals(user.getLogin()));
            return null;
        }).when(userRepository).delete(any(User.class));

        service.removeNotActivatedUsers();

        // 100 == MAX_DELETE_ATTEMPTS_PER_RUN(10,000) / default batchSize(100), plus 1 extra read-only
        // call: code review 2026-09-17 Patch #2's fix re-queries once after the loop to confirm a real
        // backlog remains before alerting (here it genuinely does — 10,000 of the 20,000 seeded users
        // were never reached).
        verify(userRepository, times(101))
            .findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any());
        verify(userRepository, times(10_000)).delete(any(User.class));
        assertThat(remaining).hasSize(10_000);
    }

    /**
     * skillars-deferred-120 code review (2026-09-17, Decision 3): {@code findExpiredUsers} re-scans
     * the same paged expired-user query on every call, so without excluding already-failed logins
     * in-run, one deterministically-undeletable user occupying a batch slot would occupy the
     * <em>same</em> slot on every subsequent batch — starving every deletable user behind it once the
     * undeletable set reaches {@code batchSize}. Proves that a single stuck login does not block the
     * deletable logins behind it within the same run.
     */
    @Test
    @Timeout(10)
    void removeNotActivatedUsers_stuckUserDoesNotBlockDeletableUsersBehindIt() {
        String stuckLogin = "stuck@example.com";
        // Mutable backing store so a successful delete is actually reflected on the next query —
        // real Mockito stubs return static canned data, so this is what lets the test prove
        // "deletable users behind the stuck one get processed", not just "the stuck one is skipped".
        List<User> remaining = new ArrayList<>();
        remaining.add(buildUser(stuckLogin, false));
        IntStream.range(0, 150).forEach(i -> remaining.add(buildUser("ok" + i + "@example.com", false)));

        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenAnswer(inv -> new ArrayList<>(remaining));
        when(userRepository.findOneByLogin(any())).thenAnswer(inv -> {
            String login = inv.getArgument(0);
            return remaining.stream().filter(u -> u.getLogin().equals(login)).findFirst();
        });
        doAnswer(inv -> {
            User user = inv.getArgument(0);
            if (stuckLogin.equals(user.getLogin())) {
                throw new RuntimeException("stuck — deterministic delete failure");
            }
            remaining.removeIf(u -> u.getLogin().equals(user.getLogin()));
            return null;
        }).when(userRepository).delete(any(User.class));

        service.removeNotActivatedUsers();

        // Every "ok*" login was deleted; only the permanently-stuck login remains — proving it did
        // not occupy a batch slot on every iteration and starve the deletable users behind it.
        assertThat(remaining).extracting(User::getLogin).containsExactly(stuckLogin);
    }

    @Test
    void removeNotActivatedUsers_carriesSchedulerLock() throws NoSuchMethodException {
        Method method = UserAdminService.class.getMethod("removeNotActivatedUsers");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("removeNotActivatedUsers() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isEqualTo("UserAdminService_removeNotActivatedUsers");
        // Pinned to the actual sizing values (code review 2026-09-17, Patch #6) — asserting only
        // isPositive() would leave the worst-case arithmetic in the method's Javadoc unguarded; a
        // regression to e.g. PT1S would still pass.
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofHours(1));
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofMinutes(1));
    }

    // ── skillars-deferred-122 AC6: findExpiredUsers genuinely paginates ──

    @Test
    void findExpiredUsers_passesRealPageableWithConfiguredBatchSize() {
        int batchSize = 50;
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of());

        service.findExpiredUsers(Instant.now(), batchSize, Set.of());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(
            any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize())
            .as("the query must be genuinely server-side paged at the configured batch size, not a "
                + "post-hoc Java .limit()")
            .isEqualTo(batchSize);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
    }

    @Test
    void findExpiredUsers_inRunFailedLoginsFilter_appliesOverReturnedPageOnly() {
        User excluded = buildUser("excluded@example.com", false);
        User included = buildUser("included@example.com", false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(excluded, included));

        List<User> result = service.findExpiredUsers(
            Instant.now(), 100, Set.of("excluded@example.com"));

        assertThat(result).extracting(User::getLogin).containsExactly("included@example.com");
    }

    // ── skillars-deferred-122 AC7: MAX_DELETE_ATTEMPTS_PER_RUN scales with configured batch size ──

    /**
     * A smaller configured batch size must not silently shrink the total per-run delete-attempt
     * ceiling — the old {@code MAX_BATCHES_PER_RUN (100)} constant, unscaled, would have capped this
     * run at {@code 100 batches x 10 batchSize = 1,000} attempts. The new derivation
     * ({@code MAX_DELETE_ATTEMPTS_PER_RUN(10,000) / batchSize}) keeps the 10,000-attempt ceiling
     * invariant to the configured batch size. All-succeeding deletes (see
     * {@link #removeNotActivatedUsers_backlogExceedsAttemptsCeiling_stopsAtMaxBatchesCap}'s Javadoc for
     * why this must be a genuinely-shrinking mutable backing list, not an always-failing one, to prove
     * the {@code maxBatches} cap specifically).
     */
    @Test
    @Timeout(10)
    void removeNotActivatedUsers_smallerBatchSize_stillAllowsFullAttemptsCeiling() {
        SecurityProperties smallBatchProps = new SecurityProperties();
        smallBatchProps.setUserCleanupBatchSize(10);
        UserAdminService smallBatchService = new UserAdminService(userRepository, smallBatchProps);
        ReflectionTestUtils.setField(smallBatchService, "self", smallBatchService);

        List<User> remaining = new ArrayList<>(IntStream.range(0, 20_000)
            .mapToObj(i -> buildUser("many" + i + "@example.com", false))
            .toList());
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenAnswer(inv -> {
                Pageable pageable = inv.getArgument(1);
                return remaining.stream().limit(pageable.getPageSize()).toList();
            });
        when(userRepository.findOneByLogin(any())).thenAnswer(inv -> {
            String login = inv.getArgument(0);
            return remaining.stream().filter(u -> u.getLogin().equals(login)).findFirst();
        });
        doAnswer(inv -> {
            User user = inv.getArgument(0);
            remaining.removeIf(u -> u.getLogin().equals(user.getLogin()));
            return null;
        }).when(userRepository).delete(any(User.class));

        smallBatchService.removeNotActivatedUsers();

        // maxBatches = 10,000 / 10 = 1,000 batches x batchSize(10) = 10,000 attempts — the same
        // total ceiling as the default batchSize(100) case, not a 10x-shrunk 1,000.
        verify(userRepository, times(10_000)).delete(any(User.class));
        assertThat(remaining).hasSize(10_000);
    }

    @Test
    void removeNotActivatedUsers_zeroConfiguredBatchSize_doesNotThrowArithmeticException() {
        SecurityProperties zeroBatchProps = new SecurityProperties();
        zeroBatchProps.setUserCleanupBatchSize(0);
        UserAdminService zeroBatchService = new UserAdminService(userRepository, zeroBatchProps);
        ReflectionTestUtils.setField(zeroBatchService, "self", zeroBatchService);

        // PageRequest.of(0, 0) throwing IllegalArgumentException is an existing, unrelated failure
        // mode this AC does not need to fix (the raw, possibly-0 batchSize is still passed through
        // unchanged) — only avoid compounding it with an earlier, separate ArithmeticException
        // computing maxBatches (division by an unguarded effectiveBatchSize of 0).
        assertThatThrownBy(zeroBatchService::removeNotActivatedUsers)
            .isNotInstanceOf(ArithmeticException.class);
    }

    // ── skillars-deferred-122 AC8: deleteUserInTransaction is public and routed through self ──

    @Test
    void deleteUserInTransaction_isPublicWithRequiresNewPropagation() throws NoSuchMethodException {
        Method method = UserAdminService.class.getMethod("deleteUserInTransaction", String.class);

        assertThat(Modifier.isPublic(method.getModifiers()))
            .as("deleteUserInTransaction must be public — Spring's default proxy-mode "
                + "AnnotationTransactionAttributeSource ignores @Transactional on non-public methods "
                + "regardless of self-proxy invocation")
            .isTrue();

        org.springframework.transaction.annotation.Transactional tx =
            method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    /**
     * skillars-deferred-122 AC8 verification bar: a bare {@code verify(self).deleteUserInTransaction(...)}
     * only proves the call site changed, and would pass even on the unfixed code, since every other
     * test in this class wires {@code self} to the same instance (there is no Spring context to
     * supply the real proxy — see the class Javadoc). This test instead proves the production code
     * path genuinely dereferences {@code self} rather than calling {@code deleteUserInTransaction}
     * directly: with {@code self} deliberately left {@code null} (unlike every other test's
     * {@link #setUp()}), the per-user call throws {@link NullPointerException}, which
     * {@code removeNotActivatedUsers}'s own catch block swallows as a delete failure — so every user
     * in the batch is recorded as failed and {@code delete(...)} is never reached. A regression that
     * reverted the call site back to a plain, non-proxied {@code deleteUserInTransaction(...)} call
     * would make this test fail: the delete would silently succeed instead.
     */
    @Test
    void removeNotActivatedUsers_selfNotWired_deleteNeverAttempted() {
        UserAdminService unwiredService = new UserAdminService(userRepository, new SecurityProperties());
        // self left null — no ReflectionTestUtils.setField call, unlike setUp().

        User batchUser = buildUser(LOGIN, false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(batchUser), List.of());

        unwiredService.removeNotActivatedUsers();

        verify(userRepository, never()).delete(any());
        // One failure (attempts=1) is below CLEANUP_FAILURE_THRESHOLD(3) — recorded, not permanently
        // excluded. See the AC9 block below for the threshold-crossing case.
        verify(userRepository).recordCleanupAttemptFailure(eq(LOGIN), eq(1), any(Instant.class), any());
        verify(userRepository, never()).markCleanupFailed(any(), any());
    }

    // ── skillars-deferred-122 AC9 (code review 2026-09-18): attempt-count marker, not a one-shot flag ──

    /**
     * "Query it back, not just assert the setter was called": this test's own assertion bar is that
     * {@link UserRepository#recordCleanupAttemptFailure} — an explicit {@code @Modifying @Query}
     * update — is what gets invoked, never a plain {@code user.setCleanupFailedAttempts(...)} against
     * the detached entity {@code findExpiredUsers} returns (which would dirty-check and persist
     * nothing under this method's {@code NOT_SUPPORTED} propagation). The real-database proof that the
     * columns are genuinely persisted and queryable back lives in {@code UserCleanupFailedMarkerIT} —
     * a Mockito unit test cannot itself prove a real column was written.
     * <p>
     * Code review 2026-09-18: a single failure must record the attempt (attempts=1) but must NOT
     * stamp the permanent {@code cleanup_failed_at} exclusion marker — that only happens once
     * {@code CLEANUP_FAILURE_THRESHOLD} separate-run failures accumulate; see
     * {@link #removeNotActivatedUsers_deleteFailsAtThreshold_stampsPermanentExclusionMarker} for that
     * case. The one-shot marker this replaced stamped on the very first failure, which could not
     * distinguish a deterministically-undeletable user from a transient one.
     */
    @Test
    void removeNotActivatedUsers_deleteFailsBelowThreshold_recordsAttemptButDoesNotExclude() {
        User batchUser = buildUser(LOGIN, false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(batchUser), List.of());
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Optional.of(buildUser(LOGIN, false)));
        doThrow(new RuntimeException("simulated delete failure")).when(userRepository).delete(any(User.class));

        service.removeNotActivatedUsers();

        verify(userRepository).recordCleanupAttemptFailure(eq(LOGIN), eq(1), any(Instant.class), any());
        verify(userRepository, never()).markCleanupFailed(any(), any());
    }

    /**
     * Code review 2026-09-18: once a user's already-persisted {@code cleanupFailedAttempts} (read off
     * the entity {@link #findExpiredUsers} returns, as production does — see
     * {@code UserAdminService.recordCleanupFailure}'s Javadoc) reaches {@code
     * CLEANUP_FAILURE_THRESHOLD - 1} and this run's delete fails too, the count crosses the threshold
     * and the permanent {@code cleanup_failed_at} exclusion marker must be stamped — modeling a user
     * that has now failed on 3 separate scheduled runs, not 3 retries within one.
     */
    @Test
    void removeNotActivatedUsers_deleteFailsAtThreshold_stampsPermanentExclusionMarker() {
        User batchUser = buildUser(LOGIN, false);
        batchUser.setCleanupFailedAttempts(2); // failed on 2 prior separate runs already
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(batchUser), List.of());
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Optional.of(buildUser(LOGIN, false)));
        doThrow(new RuntimeException("simulated delete failure")).when(userRepository).delete(any(User.class));

        service.removeNotActivatedUsers();

        verify(userRepository).recordCleanupAttemptFailure(eq(LOGIN), eq(3), any(Instant.class), any());
        verify(userRepository).markCleanupFailed(eq(LOGIN), any(Instant.class));
    }

    /**
     * A failure persisting the attempt/marker itself must not abort the sweep — that persistence
     * lives in its own {@code try/catch}.
     */
    @Test
    void removeNotActivatedUsers_recordCleanupAttemptFailureItselfThrows_sweepContinues() {
        User first = buildUser("first@example.com", false);
        User second = buildUser("second@example.com", false);
        when(userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(any(), any()))
            .thenReturn(List.of(first, second), List.of());
        when(userRepository.findOneByLogin("first@example.com"))
            .thenReturn(Optional.of(buildUser("first@example.com", false)));
        when(userRepository.findOneByLogin("second@example.com"))
            .thenReturn(Optional.of(buildUser("second@example.com", false)));
        doThrow(new RuntimeException("simulated delete failure"))
            .when(userRepository).delete(argThatLoginEquals("first@example.com"));
        doThrow(new RuntimeException("simulated attempt-record failure"))
            .when(userRepository).recordCleanupAttemptFailure(eq("first@example.com"), anyInt(), any(Instant.class), any());

        service.removeNotActivatedUsers();

        // The second user's delete must still be attempted despite the first user's attempt-record
        // failure — proves the record's own try/catch does not let that failure abort the sweep.
        verify(userRepository).delete(argThatLoginEquals("second@example.com"));
    }

    private static User argThatLoginEquals(String login) {
        return org.mockito.ArgumentMatchers.argThat(u -> u != null && login.equals(u.getLogin()));
    }
}
