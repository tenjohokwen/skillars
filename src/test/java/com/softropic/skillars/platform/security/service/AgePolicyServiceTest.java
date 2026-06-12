package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgePolicyServiceTest {

    @Mock
    private ConfigService configService;

    @Mock
    private PlayerProfileRepository playerProfileRepository;

    @InjectMocks
    private AgePolicyService agePolicyService;

    @BeforeEach
    void setUp() {
        // Default: ConfigService returns empty — exercises the orElse(DEFAULTS) path (9, 12, 17)
        when(configService.find(anyString())).thenReturn(Optional.empty());
    }

    // ---- getAgeTier tests ----

    @Test
    void getAgeTier_age9_returnsU10() {
        LocalDate dob = LocalDate.now().minusYears(9);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.U10);
    }

    @Test
    void getAgeTier_age10_returnsAge1012() {
        LocalDate dob = LocalDate.now().minusYears(10);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.AGE_10_12);
    }

    @Test
    void getAgeTier_age12_returnsAge1012() {
        LocalDate dob = LocalDate.now().minusYears(12);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.AGE_10_12);
    }

    @Test
    void getAgeTier_age13_returnsAge1317() {
        LocalDate dob = LocalDate.now().minusYears(13);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.AGE_13_17);
    }

    @Test
    void getAgeTier_age17_returnsAge1317() {
        LocalDate dob = LocalDate.now().minusYears(17);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.AGE_13_17);
    }

    @Test
    void getAgeTier_age18_returnsAdult() {
        LocalDate dob = LocalDate.now().minusYears(18);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.ADULT);
    }

    @Test
    void getAgeTier_configOverride_respectsCustomBoundary() {
        // Override u10-max-age to 8 — age 9 should now be AGE_10_12
        when(configService.find("security.age-policy.u10-max-age")).thenReturn(Optional.of("8"));
        LocalDate dob = LocalDate.now().minusYears(9);
        assertThat(agePolicyService.getAgeTier(dob)).isEqualTo(AgeTier.AGE_10_12);
    }

    // ---- getMessagingPolicy tests ----

    @Test
    void getMessagingPolicy_u10Player_returnsProhibited() {
        PlayerProfile player = playerWithDob(LocalDate.now().minusYears(8));
        when(playerProfileRepository.findById(1L)).thenReturn(Optional.of(player));

        MessagingPolicy policy = agePolicyService.getMessagingPolicy(1L);

        assertThat(policy.canMessage()).isFalse();
        assertThat(policy.parentVisible()).isTrue();
        assertThat(policy.directAllowed()).isFalse();
    }

    @Test
    void getMessagingPolicy_age1012Player_returnsParentManaged() {
        PlayerProfile player = playerWithDob(LocalDate.now().minusYears(11));
        when(playerProfileRepository.findById(2L)).thenReturn(Optional.of(player));

        MessagingPolicy policy = agePolicyService.getMessagingPolicy(2L);

        assertThat(policy.canMessage()).isTrue();
        assertThat(policy.parentVisible()).isTrue();
        assertThat(policy.directAllowed()).isFalse();
    }

    @Test
    void getMessagingPolicy_age1317Player_returnsSupervised() {
        PlayerProfile player = playerWithDob(LocalDate.now().minusYears(15));
        when(playerProfileRepository.findById(3L)).thenReturn(Optional.of(player));

        MessagingPolicy policy = agePolicyService.getMessagingPolicy(3L);

        assertThat(policy.canMessage()).isTrue();
        assertThat(policy.parentVisible()).isTrue();
        assertThat(policy.directAllowed()).isTrue();
    }

    @Test
    void getMessagingPolicy_adultPlayer_returnsUnrestricted() {
        PlayerProfile player = playerWithDob(LocalDate.now().minusYears(25));
        when(playerProfileRepository.findById(4L)).thenReturn(Optional.of(player));

        MessagingPolicy policy = agePolicyService.getMessagingPolicy(4L);

        assertThat(policy.canMessage()).isTrue();
        assertThat(policy.parentVisible()).isFalse();
        assertThat(policy.directAllowed()).isTrue();
    }

    @Test
    void getMessagingPolicy_unknownPlayer_throwsUserNotFoundException() {
        when(playerProfileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agePolicyService.getMessagingPolicy(99L))
            .isInstanceOf(UserNotFoundException.class);
    }

    private PlayerProfile playerWithDob(LocalDate dob) {
        PlayerProfile p = new PlayerProfile();
        p.setDateOfBirth(dob);
        return p;
    }
}
