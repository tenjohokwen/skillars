package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    private static User argThatLoginEquals(String login) {
        return org.mockito.ArgumentMatchers.argThat(u -> u != null && login.equals(u.getLogin()));
    }
}
