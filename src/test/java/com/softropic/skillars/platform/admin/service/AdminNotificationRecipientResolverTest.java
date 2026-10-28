package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** B6: who an admin-outcome notification goes to, across the coach / user / minor ID spaces. */
@ExtendWith(MockitoExtension.class)
class AdminNotificationRecipientResolverTest {

    @Mock UserRepository userRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock AgePolicyService agePolicyService;
    @InjectMocks AdminNotificationRecipientResolver resolver;

    private static User user(String email, String langKey) {
        User u = new User();
        u.setEmail(email);
        u.setLangKey(langKey);
        return u;
    }

    @Test
    void coach_isResolvedThroughCoachProfileUserId_notTheProfileId() {
        UUID coachId = UUID.randomUUID();
        CoachProfile profile = new CoachProfile();
        profile.setUserId(42L);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user("coach@x.test", "fr")));

        assertThat(resolver.forCoach(coachId)).contains(new AdminNotificationRecipient("coach@x.test", "fr"));
    }

    @Test
    void minorPlayerWithParent_isRedirectedToTheParent() {
        PlayerProfile child = new PlayerProfile();
        child.setUserId(10L);
        child.setParentId(20L);
        LocalDate dob = LocalDate.now().minusYears(14);
        child.setDateOfBirth(dob);
        when(playerProfileRepository.findByUserId(10L)).thenReturn(Optional.of(child));
        when(agePolicyService.isMinor(dob)).thenReturn(true);
        when(userRepository.findById(20L)).thenReturn(Optional.of(user("parent@x.test", null)));

        assertThat(resolver.forUser(10L)).contains(new AdminNotificationRecipient("parent@x.test", "en"));
    }

    @Test
    void adultPlayer_withLegacyParentLink_isAddressedDirectly() {
        PlayerProfile adult = new PlayerProfile();
        adult.setUserId(10L);
        adult.setParentId(20L);
        LocalDate dob = LocalDate.now().minusYears(25);
        adult.setDateOfBirth(dob);
        when(playerProfileRepository.findByUserId(10L)).thenReturn(Optional.of(adult));
        when(agePolicyService.isMinor(dob)).thenReturn(false);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user("adult@x.test", "de")));

        assertThat(resolver.forUser(10L)).contains(new AdminNotificationRecipient("adult@x.test", "de"));
    }

    @Test
    void parentOrCoachUser_withNoPlayerProfile_isAddressedDirectly_andBlankEmailIsUnreachable() {
        when(playerProfileRepository.findByUserId(5L)).thenReturn(Optional.empty());
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(" ", "en")));

        assertThat(resolver.forUser(5L)).isEmpty();
    }

    @Test
    void accountHolder_isNeverRedirected() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(user("teen@x.test", "en")));
        assertThat(resolver.forAccountHolder(10L)).contains(new AdminNotificationRecipient("teen@x.test", "en"));
    }
}
