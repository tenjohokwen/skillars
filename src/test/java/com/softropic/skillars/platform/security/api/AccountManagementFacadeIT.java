package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.platform.security.contract.exception.ProfileActionException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.security.TestRequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "spring.cloud.compatibility-verifier.enabled=false",
        "rate.limiting.enabled=false"
})
@Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
@Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AccountManagementFacadeIT {

    private static final String EXISTING_LOGIN    = "me@yahoo.com";
    private static final String CORRECT_PW        = "admin*123!";
    private static final String NOT_ACTIVATED_LOGIN = "not-activated@yahoo.com";

    @Autowired
    private AccountManagementFacade facade;

    @Autowired
    private MailManager mailManager;

    private TestMailManager testMailManager;

    @BeforeEach
    void setUp() {
        testMailManager = (TestMailManager) mailManager;
        testMailManager.clear();
        // Provide a minimal request context so ClientContextProvider does not blow up
        TestRequestMetadataProvider.setIpAddress("127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // registerAccount — email path
    // -------------------------------------------------------------------------

    @Test
    void registerAccount_emailPath_newUser_sendsActivationEmail() {
        UserDto dto = newEmailUserDto("newuser@example.com", "657123456");

        String helpCode = facade.registerAccount(dto);

        assertThat(helpCode).isNotBlank();
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        Envelope envelope = testMailManager.getEnvelope(helpCode);
        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.ACTIVATION);
        assertThat(envelope.data()).containsKey("activationKey");
        assertThat(envelope.data().get("activationKey")).isNotNull();
    }

    @Test
    void registerAccount_emailPath_existingUser_sendsSecurityAlertEmail() {
        // EXISTING_LOGIN is already in the DB via userData.sql
        UserDto dto = newEmailUserDto(EXISTING_LOGIN, "657123456");

        String helpCode = facade.registerAccount(dto);

        assertThat(helpCode).isNotBlank();
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        Envelope envelope = testMailManager.getEnvelope(helpCode);
        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.CREATION_DUP);
    }

    @Test
    void registerAccount_emailPath_loginNormalisedToLowerCase() {
        UserDto dto = newEmailUserDto("MixedCase@Example.COM", "657123456");

        String helpCode = facade.registerAccount(dto);

        assertThat(helpCode).isNotBlank();
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        Envelope envelope = testMailManager.getEnvelope(helpCode);
        // Activation email must be addressed to the lower-cased form
        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.ACTIVATION);
        assertThat(envelope.recipients()).hasSize(1);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo("mixedcase@example.com");
    }

    // -------------------------------------------------------------------------
    // resendRegistrationLink
    // -------------------------------------------------------------------------

    @Test
    void resendRegistrationLink_notActivatedUser_sendsActivationEmail() {
        String helpCode = facade.resendRegistrationLink(NOT_ACTIVATED_LOGIN, CORRECT_PW);

        assertThat(helpCode).isNotBlank();
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        Envelope envelope = testMailManager.getEnvelope(helpCode);
        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.ACTIVATION);
    }

    @Test
    void resendRegistrationLink_alreadyActivatedUser_throws() {
        // me@yahoo.com has activation_date set → treated as already activated
        assertThatThrownBy(() -> facade.resendRegistrationLink(EXISTING_LOGIN, CORRECT_PW))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void resendRegistrationLink_wrongPassword_throws() {
        assertThatThrownBy(() -> facade.resendRegistrationLink(NOT_ACTIVATED_LOGIN, "wrongPassword!"))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    // -------------------------------------------------------------------------
    // changeEmail
    // -------------------------------------------------------------------------

    @Test
    void changeEmail_success_sendsProfileChangeEmail() {
        initSecurityContext();
        String newEmail = "changed@example.com";

        String helpCode = facade.changeEmail(EXISTING_LOGIN, newEmail, CORRECT_PW);

        assertThat(helpCode).isNotBlank();
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        Envelope envelope = testMailManager.getEnvelope(helpCode);
        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.PROFILE_CHANGE);
        assertThat(envelope.data()).containsEntry("action", "EMAIL_CHANGED");
        assertThat(envelope.data()).containsEntry("oldValue", EXISTING_LOGIN);
        assertThat(envelope.data()).containsEntry("newValue", newEmail);
    }

    @Test
    void changeEmail_wrongPassword_throws() {
        initSecurityContext();

        assertThatThrownBy(() -> facade.changeEmail(EXISTING_LOGIN, "new@example.com", "wrongPassword!"))
                .isInstanceOf(ProfileActionException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserDto newEmailUserDto(String email, String phone) {
        UserDto dto = new UserDto();
        dto.setEmail(email);
        dto.setPhone(phone);
        dto.setPassword(CORRECT_PW);
        dto.setFirstName("Test");
        dto.setLastName("User");
        dto.setLangKey("en");
        dto.setGender(Gender.MALE);
        dto.setDob(LocalDate.of(1990, 1, 15));
        return dto;
    }

    private void initSecurityContext() {
        Principal principal = new Principal.Builder()
                .username(EXISTING_LOGIN)
                .password("$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i")
                .authorities(Set.of(new Authority("ROLE_USER")))
                .displayName("RZZ AGZM")
                .businessId("586920556720583008")
                .enabled(true)
                .otpEnabled(true)
                .phone("0248888736")
                .build();
        var token = new UsernamePasswordAuthenticationToken(
                principal.getUsername(), null, principal.getAuthorities());
        token.setDetails(principal);
        SecurityContext ctx = new SecurityContextImpl(token);
        SecurityContextHolder.setContext(ctx);
    }
}
