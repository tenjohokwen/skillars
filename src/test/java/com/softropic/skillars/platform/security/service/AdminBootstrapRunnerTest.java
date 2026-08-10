package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.AdminBootstrapProperties;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.AuthorityRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminBootstrapRunner}.
 *
 * <p>Deliberately a plain unit test, not an {@code *IT}. The runner is property-driven, and the
 * obvious integration test — {@code @TestPropertySource(app.bootstrap.admin.*)} on a concrete
 * {@code *IT} — would fork a Spring context and trip
 * {@code IntegrationTestConventionTest.EXPECTED_TEST_PROPERTY_SOURCE_COUNT}. Constructor injection
 * makes every enable/disable/idempotency case reachable here for free; the IT alongside covers only
 * what a unit test cannot (that the resulting row can actually authenticate).
 */
class AdminBootstrapRunnerTest {

    private static final String CONFIGURED_EMAIL = "Admin@Company.com";
    private static final String NORMALIZED_LOGIN = "admin@company.com";
    private static final String RAW_PASSWORD = "s3cret-bootstrap-pw";

    private AdminBootstrapProperties properties;
    private UserRepository userRepository;
    private AuthorityRepository authorityRepository;
    private PasswordEncoder passwordEncoder;
    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        properties = new AdminBootstrapProperties();
        userRepository = mock(UserRepository.class);
        authorityRepository = mock(AuthorityRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        // A real TransactionTemplate over a no-op transaction manager: the callback runs inline and
        // any exception propagates exactly as it would in production, without needing a database.
        // Deliberately NOT a mock — stubbing executeWithoutResult would let the callback be skipped
        // entirely, and every assertion below would then pass against a runner that does nothing.
        runner = new AdminBootstrapRunner(properties, userRepository, authorityRepository,
            passwordEncoder, new TransactionTemplate(new NoOpTransactionManager()));

        Authority adminAuthority = new Authority();
        adminAuthority.setName(SecurityConstants.ROLE_ADMIN);
        when(authorityRepository.findOneByName(SecurityConstants.ROLE_ADMIN))
            .thenReturn(Optional.of(adminAuthority));
        when(userRepository.findOneByEmail(anyString())).thenReturn(Optional.empty());
    }

    private void enableBootstrap() {
        properties.setEmail(CONFIGURED_EMAIL);
        properties.setPassword(RAW_PASSWORD);
        properties.setPhone("+49301234567");
    }

    @Test
    @DisplayName("blank email or password disables the bootstrap entirely — no repository is touched")
    void disabledByDefault() {
        runner.run(null);
        verifyNoInteractions(userRepository, authorityRepository);

        properties.setEmail(CONFIGURED_EMAIL);
        runner.run(null);
        verifyNoInteractions(userRepository, authorityRepository);

        properties.setEmail("");
        properties.setPassword(RAW_PASSWORD);
        runner.run(null);
        verifyNoInteractions(userRepository, authorityRepository);
    }

    @Test
    @DisplayName("enabled without a phone refuses to start rather than writing a half-valid row")
    void enabledWithoutPhoneFailsFast() {
        properties.setEmail(CONFIGURED_EMAIL);
        properties.setPassword(RAW_PASSWORD);

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("phone");
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("creates an admin that satisfies every gate on the login path")
    void createsLoginCapableAdmin() {
        enableBootstrap();

        runner.run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User user = saved.getValue();

        // AuthService resolves logins via findOneByLogin(email.toLowerCase()), so an upper-case
        // character anywhere in these two columns makes the account permanently unreachable.
        assertThat(user.getLogin()).isEqualTo(NORMALIZED_LOGIN);
        assertThat(user.getEmail()).isEqualTo(NORMALIZED_LOGIN);
        assertThat(user.getLoginIdType()).isEqualTo(LoginIdType.EMAIL);

        // activated=false => DisabledException on login, AND removeNotActivatedUsers deletes the
        // row on its next daily run.
        assertThat(user.isActivated()).isTrue();
        // Anything other than BASIC_VERIFIED => SkillarsAccountNotVerifiedException, because the
        // phone-otp-required toggle defaults to true and an admin has no OTP flow to complete.
        assertThat(user.getVerificationStatus()).isEqualTo(SkillarsVerificationStatus.BASIC_VERIFIED);
        assertThat(user.getSkillarsRole()).isEqualTo(SkillarsRole.ADMIN);
        assertThat(user.getStatus()).isEqualTo(EntityStatus.ACTIVE);

        assertThat(user.getAuthorities())
            .as("grants ROLE_ADMIN and nothing else — ROLE_LTD_ADMIN is deliberately not granted")
            .extracting(Authority::getName)
            .containsExactly(SecurityConstants.ROLE_ADMIN);

        assertThat(user.getPassword())
            .as("stored bcrypt, never plaintext — password_hash is @Size(min=60,max=60)")
            .isNotEqualTo(RAW_PASSWORD)
            .hasSize(60);
        assertThat(passwordEncoder.matches(RAW_PASSWORD, user.getPassword())).isTrue();

        assertThat(user.getPhone()).isNotNull();
        assertThat(user.getPhone().getPhone()).isEqualTo("+49301234567");
    }

    @Test
    @DisplayName("an existing account makes the runner skip, never elevate")
    void existingAccountIsSkipped() {
        enableBootstrap();
        when(userRepository.findOneByEmail(NORMALIZED_LOGIN)).thenReturn(Optional.of(new User()));

        runner.run(null);

        verify(userRepository, never()).save(any());
    }

    /**
     * The regression that motivated normalizing the email exactly once.
     *
     * <p>{@code UserRepository.findOneByEmail} is a derived query with no {@code lower()}, so it is
     * case-sensitive, while {@code login} and {@code email} are both UNIQUE. Looking the account up
     * by the RAW configured value while storing the LOWERCASED one means the second boot misses the
     * row the first boot wrote, falls through to {@code save()}, and violates the constraint — and
     * an exception out of {@code ApplicationRunner.run} propagates from {@code SpringApplication.run}
     * and fails startup. Leaving the env vars set across a {@code docker compose up} is the normal
     * case, not an edge case, so this would have bricked the box on its second boot.
     *
     * <p><strong>Mutation-checked:</strong> changing the runner's lookup to
     * {@code findOneByEmail(properties.getEmail())} fails this test with
     * "expected: 1 but was: 2" on the save count (and, against a real database, with the
     * DataIntegrityViolationException this simulates).
     */
    @Test
    @DisplayName("re-running with a mixed-case configured email is a no-op, not a startup failure")
    void mixedCaseEmailIsIdempotentAcrossBoots() {
        enableBootstrap();

        // A stand-in for the unique index: the store is keyed by the value actually persisted, and
        // any second insert of that same key throws exactly as Postgres would.
        Map<String, User> persisted = new HashMap<>();
        when(userRepository.findOneByEmail(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(persisted.get(inv.getArgument(0, String.class))));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0, User.class);
            if (persisted.putIfAbsent(u.getEmail(), u) != null) {
                throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
            }
            return u;
        });

        runner.run(null);
        assertThat(persisted).containsOnlyKeys(NORMALIZED_LOGIN);

        // Second boot, same configuration — the operator has not unset the variables yet.
        assertThatCode(() -> runner.run(null))
            .as("a second boot must not throw — an ApplicationRunner exception fails startup")
            .doesNotThrowAnyException();

        verify(userRepository, org.mockito.Mockito.times(1)).save(any(User.class));
        assertThat(persisted).hasSize(1);
    }

    /**
     * Minimal real {@link org.springframework.transaction.PlatformTransactionManager} so the
     * {@link TransactionTemplate} the runner depends on executes its callback inline and propagates
     * exceptions unchanged. There is no database here, so there is nothing to commit — but the
     * template must be genuine, not stubbed, or the callback could be silently skipped and every
     * assertion in this class would pass against a runner that does nothing at all.
     */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no resource to bind
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no resource to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no resource to roll back
        }
    }

    @Test
    @DisplayName("a collision with pre-existing data logs and returns — it never fails startup")
    void integrityViolationDoesNotFailStartup() {
        enableBootstrap();
        // Models the documented pre-V92 workaround: an operator hand-inserted an admin via raw SQL,
        // at a casing or phone number this bootstrap collides with but findOneByEmail cannot see.
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    /**
     * The catch must be by outcome ("the row could not be written"), not by exception type.
     *
     * <p>A Bean Validation violation fails at COMMIT and Spring surfaces it as
     * {@link org.springframework.transaction.TransactionSystemException}, NOT as
     * {@code DataIntegrityViolationException} — verified by running the real runner against a real
     * database. A catch aimed only at the latter therefore let it escape and fail startup, which is
     * precisely the contract this class claims to honour.
     *
     * <p><strong>Mutation-checked:</strong> narrowing the catch back to
     * {@code DataIntegrityViolationException} fails this test.
     */
    @Test
    @DisplayName("a commit-time failure of any type is swallowed, not just DataIntegrityViolationException")
    void commitTimeFailureOfAnyTypeDoesNotFailStartup() {
        enableBootstrap();
        when(userRepository.save(any(User.class)))
            .thenThrow(new TransactionSystemException("Could not commit JPA transaction"));

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    /**
     * {@code Customer.email}'s pattern is {@code [a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,3}} — the TLD is
     * capped at three characters, so an ordinary modern address is rejected platform-wide. Left to
     * the commit this produced a {@code TransactionSystemException} naming a JPA transaction, which
     * told the operator nothing about which variable to change; caught here it names the value.
     */
    @Test
    @DisplayName("an email the User entity would reject fails fast with an actionable message")
    void invalidEmailFailsFastBeforeAnyDatabaseWork() {
        properties.setEmail("admin@company.info");   // 4-character TLD
        properties.setPassword(RAW_PASSWORD);
        properties.setPhone("+49301234567");

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("app.bootstrap.admin.email")
            .hasMessageContaining("top-level domain");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an over-long configured name fails fast rather than at commit")
    void oversizedNameFailsFast() {
        enableBootstrap();
        properties.setFirstName("x".repeat(51));   // Customer.firstName is @Size(max = 50)

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("first-name");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("a missing ROLE_ADMIN row still refuses to start — it is a broken deployment, not bad data")
    void missingAdminAuthorityStillFailsStartup() {
        enableBootstrap();
        when(authorityRepository.findOneByName(SecurityConstants.ROLE_ADMIN)).thenReturn(Optional.empty());

        // Raised INSIDE the transaction, so it has to be re-thrown past the catch-all above it.
        // If it were absorbed, a database with no migrations would boot and every admin endpoint
        // would 403 with nothing in the logs to explain why.
        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("ROLE_ADMIN");
    }

    @Test
    @DisplayName("the properties' toString never contains the password")
    void toStringDoesNotLeakPassword() {
        enableBootstrap();
        // @ConfigurationProperties beans are printed by Spring Boot in binding-failure messages and
        // reflected over by /actuator/configprops, so toString() is a real egress path.
        assertThat(properties.toString())
            .doesNotContain(RAW_PASSWORD)
            .contains(CONFIGURED_EMAIL);
    }
}
