package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.AuthService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final long USER_ID = 9_100_000_001L;
    private static final long ADMIN_ID = 9_100_000_002L;

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AdminActionLogRepository adminActionLogRepository;
    @Mock private GdprRequestRepository gdprRequestRepository;
    @Mock private AuthService authService;
    @Mock private PessimisticLockRetryer lockRetryer;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private AdminCoachEnforcementService coachEnforcementService;

    @InjectMocks private AdminUserService service;

    @BeforeEach
    void setUp() {
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void reactivate_refusesAChildSafetySuspension() {
        // A CSAM-match suspension (AccountSuspensionEventListener) is a safety hold, not an admin
        // lock: the generic "reactivate" must not hand the account its sessions back.
        User user = user(SkillarsVerificationStatus.SUSPENDED, true);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.reactivateUser(USER_ID, "appeal", ADMIN_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                ResponseStatusException rse = (ResponseStatusException) e;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(rse.getReason()).isEqualTo("admin.users.safetyHold");
            });
        assertThat(user.isLocked()).isTrue();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void reactivate_stillLiftsAnOrdinaryAdminSuspension() {
        User user = user(SkillarsVerificationStatus.BASIC_VERIFIED, true);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.reactivateUser(USER_ID, "appeal upheld", ADMIN_ID)).isTrue();
        assertThat(user.isLocked()).isFalse();
    }

    private static User user(SkillarsVerificationStatus status, boolean locked) {
        User user = new User();
        user.setId(USER_ID);
        user.setSkillarsRole(SkillarsRole.PARENT);
        user.setVerificationStatus(status);
        user.setLocked(locked);
        return user;
    }
}
