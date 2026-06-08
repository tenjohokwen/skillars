package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.repo.Address;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.contract.exception.ProfileActionException;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
@Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class UserProfileServiceIT {

    private static final String LOGIN      = "me@yahoo.com";
    private static final String CORRECT_PW = "admin*123!";

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccountChangeEventCaptor eventCaptor;

    @BeforeEach
    void setUp() {
        initSecurityContext();
        eventCaptor.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // updateUserEmail
    // -------------------------------------------------------------------------

    @Test
    void updateUserEmail_success_updatesEmailAndPublishesEvent() {
        String newEmail = "newemail@example.com";

        Optional<User> result = userProfileService.updateUserEmail(LOGIN, newEmail, CORRECT_PW);

        assertThat(result).isPresent();
        User updated = result.get();
        assertThat(updated.getEmail()).isEqualTo(newEmail);
        assertThat(updated.getLogin()).isEqualTo(newEmail);

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.EMAIL_CHANGED);
        assertThat(event).isNotNull();
        assertThat(event.getOldValue()).isEqualTo(LOGIN);
        assertThat(event.getNewValue()).isEqualTo(newEmail);
        // Notification should go to OLD email address for security
        assertThat(event.getUserInfo().email()).isEqualTo(LOGIN);
    }

    @Test
    void updateUserEmail_wrongPassword_throwsProfileActionException() {
        assertThatThrownBy(() -> userProfileService.updateUserEmail(LOGIN, "new@example.com", "wrongPassword"))
                .isInstanceOf(ProfileActionException.class);
    }

    @Test
    void updateUserEmail_wrongOldEmail_throwsProfileActionException() {
        assertThatThrownBy(() -> userProfileService.updateUserEmail("wrong@example.com", "new@example.com", CORRECT_PW))
                .isInstanceOf(ProfileActionException.class);
    }

    // -------------------------------------------------------------------------
    // changePassword
    // -------------------------------------------------------------------------

    @Test
    void changePassword_success_encodesNewPasswordAndPublishesEvent() {
        String newPassword = "NewSecureP@ss99!";

        Optional<User> result = userProfileService.changePassword(CORRECT_PW, newPassword);

        assertThat(result).isPresent();
        User updated = result.get();
        assertThat(passwordEncoder.matches(newPassword, updated.getPassword())).isTrue();
        assertThat(passwordEncoder.matches(CORRECT_PW, updated.getPassword())).isFalse();

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.PASSWORD_CHANGED);
        assertThat(event).isNotNull();
        assertThat(event.getOldValue()).isNull();
        assertThat(event.getNewValue()).isNull();
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsProfileActionException() {
        assertThatThrownBy(() -> userProfileService.changePassword("wrongPassword", "NewSecureP@ss99!"))
                .isInstanceOf(ProfileActionException.class);
    }

    // -------------------------------------------------------------------------
    // updatePhone
    // -------------------------------------------------------------------------

    @Test
    void updatePhone_success_updatesPhoneAndPublishesEvent() {
        // Valid Cameroon MTN number (9 digits, starts with 67x)
        String newPhone = "678684749";

        Optional<User> result = userProfileService.updatePhone(newPhone);

        assertThat(result).isPresent();

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.PHONE_CHANGED);
        assertThat(event).isNotNull();
        assertThat(event.getNewValue()).isEqualTo(newPhone);
    }

    @Test
    void updatePhone_capturesOldPhoneInEvent() {
        String oldPhone = userRepository.findOneByLogin(LOGIN).orElseThrow().getPhone().getPhone();

        userProfileService.updatePhone("678684749");

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.PHONE_CHANGED);
        assertThat(event.getOldValue()).isEqualTo(oldPhone);
    }

    // -------------------------------------------------------------------------
    // toggle2fa
    // -------------------------------------------------------------------------

    @Test
    void toggle2fa_disabling_updatesUserAndPublishesDisabledEvent() {
        // Test user has otp_enabled=true
        Optional<User> result = userProfileService.toggle2fa(false, CORRECT_PW);

        assertThat(result).isPresent();
        assertThat(result.get().isOtpEnabled()).isFalse();

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.TWO_FACTOR_AUTH_DISABLED);
        assertThat(event).isNotNull();
        assertThat(event.getOldValue()).isEqualTo("true");
        assertThat(event.getNewValue()).isEqualTo("false");
    }

    @Test
    void toggle2fa_enabling_updatesUserAndPublishesEnabledEvent() {
        // Disable first, then re-enable
        userProfileService.toggle2fa(false, CORRECT_PW);
        eventCaptor.clear();

        Optional<User> result = userProfileService.toggle2fa(true, CORRECT_PW);

        assertThat(result).isPresent();
        assertThat(result.get().isOtpEnabled()).isTrue();

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.TWO_FACTOR_AUTH_ENABLED);
        assertThat(event).isNotNull();
        assertThat(event.getOldValue()).isEqualTo("false");
        assertThat(event.getNewValue()).isEqualTo("true");
    }

    @Test
    void toggle2fa_wrongPassword_throwsProfileActionException() {
        assertThatThrownBy(() -> userProfileService.toggle2fa(false, "wrongPassword"))
                .isInstanceOf(ProfileActionException.class);
    }

    // -------------------------------------------------------------------------
    // updatePostalAddress
    // -------------------------------------------------------------------------

    @Test
    void updatePostalAddress_success_publishesEventWithFormattedAddresses() {
        Address newAddress = buildAddress("HOME", "123 Main St", "Berlin", "Berlin", "12345", "Germany");

        Optional<User> result = userProfileService.updatePostalAddress(newAddress);

        assertThat(result).isPresent();

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.ADDRESS_CHANGED);
        assertThat(event).isNotNull();
        assertThat(event.getNewValue()).contains("Berlin").contains("Germany");
    }

    @Test
    void updatePostalAddress_capturesOldAddressInEvent() {
        // The test user already has an address from userData.sql (city=DQUYPTHOW)
        Address newAddress = buildAddress("HOME", "New Street 1", "Munich", "Bavaria", "80331", "Germany");

        userProfileService.updatePostalAddress(newAddress);

        AccountChangeEvent event = eventCaptor.lastEventOf(AccountChangeEvent.Action.ADDRESS_CHANGED);
        // Old address had city DQUYPTHOW from seed data
        assertThat(event.getOldValue()).contains("DQUYPTHOW");
    }

    private Address buildAddress(String name, String line1, String city, String state, String postal, String country) {
        Address a = new Address();
        a.setName(name);
        a.setAddressLine1(line1);
        a.setCity(city);
        a.setStateProvince(state);
        a.setPostalCode(postal);
        a.setCountry(country);
        return a;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initSecurityContext() {
        Principal principal = new Principal.Builder()
                .username(LOGIN)
                .password("$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i")
                .authorities(Set.of(new Authority("ROLE_USER")))
                .displayName("RZZ AGZM")
                .businessId("586920556720583008")
                .enabled(true)
                .otpEnabled(true)
                .phone("0248888736")
                .build();
        var token = new UsernamePasswordAuthenticationToken(principal.getUsername(), null, principal.getAuthorities());
        token.setDetails(principal);
        SecurityContext ctx = new SecurityContextImpl(token);
        SecurityContextHolder.setContext(ctx);
    }

    @TestConfiguration
    static class Config {
        @Bean
        AccountChangeEventCaptor accountChangeEventCaptor() {
            return new AccountChangeEventCaptor();
        }
    }

    /**
     * Captures AccountChangeEvents published during tests.
     */
    static class AccountChangeEventCaptor {

        private final List<AccountChangeEvent> captured = new ArrayList<>();

        @EventListener
        public void onEvent(AccountChangeEvent event) {
            captured.add(event);
        }

        public AccountChangeEvent lastEventOf(AccountChangeEvent.Action action) {
            return captured.stream()
                           .filter(e -> e.getAction() == action)
                           .reduce((first, second) -> second)
                           .orElse(null);
        }

        public void clear() {
            captured.clear();
        }
    }
}
