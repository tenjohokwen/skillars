package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminSuspendCoachResponse;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** B6, issue 6: the video ownerId ID space decides whether a coach profile is taken down. */
@ExtendWith(MockitoExtension.class)
class CoachSafetySuspensionListenerTest {

    @Mock CoachProfileRepository coachProfileRepository;
    @Mock AdminCoachEnforcementService enforcementService;
    @InjectMocks CoachSafetySuspensionListener listener;

    @Test
    void coachOwnerId_suspendsThatCoachProfile() {
        UUID coachId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        when(coachProfileRepository.existsById(coachId)).thenReturn(true);
        when(enforcementService.suspendCoachForSafetyHold(coachId, videoId))
            .thenReturn(new AdminSuspendCoachResponse(true, 2, 1));

        listener.onAccountSuspensionRequested(new AccountSuspensionRequestedEvent(coachId.toString(), videoId));

        verify(enforcementService).suspendCoachForSafetyHold(coachId, videoId);
    }

    @Test
    void playerOwnerId_aPlayerProfileNumber_touchesNoCoachProfile() {
        listener.onAccountSuspensionRequested(new AccountSuspensionRequestedEvent("123456789", UUID.randomUUID()));

        verifyNoInteractions(coachProfileRepository, enforcementService);
    }

    @Test
    void unknownUuid_isIgnored() {
        UUID notACoach = UUID.randomUUID();
        when(coachProfileRepository.existsById(notACoach)).thenReturn(false);

        listener.onAccountSuspensionRequested(new AccountSuspensionRequestedEvent(notACoach.toString(), UUID.randomUUID()));

        verifyNoInteractions(enforcementService);
    }

    @Test
    void suspensionFailure_isSwallowed_soTheAccountSuspensionListenerStillRuns() {
        UUID coachId = UUID.randomUUID();
        when(coachProfileRepository.existsById(coachId)).thenReturn(true);
        when(enforcementService.suspendCoachForSafetyHold(any(), any()))
            .thenThrow(new PessimisticLockingFailureException("contended"));

        listener.onAccountSuspensionRequested(new AccountSuspensionRequestedEvent(coachId.toString(), UUID.randomUUID()));

        // C2: the whole hold is re-attempted before giving up.
        verify(enforcementService, org.mockito.Mockito.times(CoachSafetySuspensionListener.SAFETY_HOLD_ATTEMPTS))
            .suspendCoachForSafetyHold(any(), any());
    }

    @Test
    void transientLockContention_isRetried_soTheHoldStillLands() {
        UUID coachId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        when(coachProfileRepository.existsById(coachId)).thenReturn(true);
        when(enforcementService.suspendCoachForSafetyHold(coachId, videoId))
            .thenThrow(new PessimisticLockingFailureException("contended"))
            .thenReturn(new AdminSuspendCoachResponse(true, 1, 0));

        listener.onAccountSuspensionRequested(new AccountSuspensionRequestedEvent(coachId.toString(), videoId));

        verify(enforcementService, org.mockito.Mockito.times(2)).suspendCoachForSafetyHold(coachId, videoId);
    }
}
