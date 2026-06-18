package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.security.api.AccountManagementFacade;
import com.softropic.skillars.platform.security.api.KeyAndPasswordDto;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.contract.ChangePasswordDto;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@Transactional
public class PasswordResetIT {

    @Autowired
    private AccountManagementFacade accountManagementFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MailManager mailManager;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChangePasswordDto changePasswordDto;

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @BeforeEach
    void setUp() {
        changePasswordDto = new ChangePasswordDto();
        changePasswordDto.setCurrentEmail("me@yahoo.com");
        changePasswordDto.setLoginId("me@yahoo.com");
        changePasswordDto.setDob(LocalDate.parse("1978-03-19"));
    }

    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    @Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void testPasswordReset_Success() {
        // --- 1. Initiate Password Reset ---
        String helpCode = accountManagementFacade.sendPasswordResetMail(changePasswordDto);
        assertThat(helpCode).isNotBlank();

        // Verify email was sent and contains reset key
        TestMailManager testMailManager = (TestMailManager) mailManager;
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        Envelope envelope = testMailManager.getEnvelope(helpCode);
        String resetKey = (String) envelope.data().get("resetKey");
        assertThat(resetKey).isNotBlank();

        // --- 2. Finish Password Reset ---
        String newPassword = "newSecurePassword123!";
        KeyAndPasswordDto keyAndPasswordDto = new KeyAndPasswordDto(resetKey, newPassword);
        User updatedUser = accountManagementFacade.finishPasswordReset(keyAndPasswordDto);
        assertThat(updatedUser).isNotNull();
        assertThat(updatedUser.getResetKey()).isNull();

        // --- 3. Verify Password Change ---
        Optional<User> userOpt = userRepository.findOneByEmail(changePasswordDto.getCurrentEmail());
        assertThat(userOpt).isPresent();
        assertThat(passwordEncoder.matches(newPassword, userOpt.get().getPassword())).isTrue();
    }
    
    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    @Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void testFinishPasswordReset_WithInvalidKey() {
        KeyAndPasswordDto keyAndPasswordDto = new KeyAndPasswordDto("invalidKey", "newPassword");
        
        // Assert that an exception is thrown
        // In the previous task, finishPasswordReset was refactored to throw OperationNotAllowedException
        assertThatThrownBy(() -> accountManagementFacade.finishPasswordReset(keyAndPasswordDto))
                .isInstanceOf(com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException.class);
    }
    
    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    @Sql(scripts = "/sql/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void testFinishPasswordReset_WithExpiredKey() {
        // Manually set an expired reset key in the DB
        String expiredKey = "expiredResetKey";
        transactionTemplate.execute(status -> {
            User user = userRepository.findOneByEmail("me@yahoo.com").orElseThrow();
            user.setResetKey(expiredKey);
            user.setResetExpiration(java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS)); // Expired
            userRepository.save(user);
            return null;
        });
        
        KeyAndPasswordDto keyAndPasswordDto = new KeyAndPasswordDto(expiredKey, "newPassword");
        
        assertThatThrownBy(() -> accountManagementFacade.finishPasswordReset(keyAndPasswordDto))
                .isInstanceOf(com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException.class);
    }
}
