package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(any()))
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
        when(userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(any()))
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
        when(userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(any()))
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
     * skillars-deferred-120 AC3 Finding 1: without a cap, a run with enough distinct
     * deterministically-undeletable users (an uncovered FK, a trigger, a constraint — never the
     * <em>same</em> user twice, since code review 2026-09-17 Decision 3 excludes an already-failed
     * login from re-selection within the run; see
     * {@link #removeNotActivatedUsers_stuckUserDoesNotBlockDeletableUsersBehindIt} for that case)
     * would never terminate. {@code MAX_BATCHES_PER_RUN} bounds it at 100 batches. Uses 20,000
     * distinct always-failing users (well above {@code batchSize (100) x MAX_BATCHES_PER_RUN (100)})
     * so the cap — not a natural empty-batch drain — is what stops the loop.
     *
     * <p>{@code @Timeout} guards the CI failure mode directly: if {@code MAX_BATCHES_PER_RUN}
     * regresses (e.g. to an unbounded loop), this test would otherwise hang until the CI job's own
     * timeout instead of failing fast (code review 2026-09-17, Patch #7).
     */
    @Test
    @Timeout(10)
    void removeNotActivatedUsers_deterministicDeleteFailure_stopsAtMaxBatchesPerRunCap() {
        List<User> manyDistinctUsers = IntStream.range(0, 20_000)
            .mapToObj(i -> buildUser("stuck" + i + "@example.com", false))
            .toList();
        // The full, unpaged expired-user set is re-scanned on every call (a separate, deferred
        // inefficiency — see deferred-work.md) — returning the same 20,000-user list every time
        // mirrors that shape; the exclude-already-failed filter is what advances through it.
        when(userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(any()))
            .thenReturn(manyDistinctUsers);
        when(userRepository.findOneByLogin(any()))
            .thenAnswer(inv -> Optional.of(buildUser(inv.getArgument(0), false)));
        doThrow(new RuntimeException("deterministic delete failure"))
            .when(userRepository).delete(any(User.class));

        service.removeNotActivatedUsers();

        // 100 == MAX_BATCHES_PER_RUN, plus 1 extra read-only call: code review 2026-09-17 Patch #2's
        // fix re-queries once after the loop to confirm a real backlog remains before alerting (here
        // it genuinely does — indices 10,000-19,999 were never reached). The distinguishing part of
        // this assertion is 10,000 distinct users attempted, not 100 repeats of the same one.
        verify(userRepository, times(101)).findAllByActivatedIsFalseAndCreatedDateBefore(any());
        verify(userRepository, times(10_000)).delete(any(User.class));
    }

    /**
     * skillars-deferred-120 code review (2026-09-17, Decision 3): {@code findExpiredUsers} re-scans
     * the same unpaged, unfiltered expired-user set on every call, so without excluding
     * already-failed logins, one deterministically-undeletable user occupying a batch slot would
     * occupy the <em>same</em> slot on every subsequent batch — starving every deletable user behind
     * it once the undeletable set reaches {@code batchSize}. Proves that a single stuck login does
     * not block the deletable logins behind it within the same run.
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

        when(userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(any()))
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

    /**
     * skillars-deferred-120 AC3 Finding 1: {@code removeNotActivatedUsers} was missed by
     * {@code skillars-deferred-118} AC3's own lock-parity sweep, despite that story's AC2 touching
     * this exact method, and carried its own long-standing unactioned "consider adding ShedLock"
     * Javadoc TODO.
     */
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

    private static User argThatLoginEquals(String login) {
        return org.mockito.ArgumentMatchers.argThat(u -> u != null && login.equals(u.getLogin()));
    }
}
