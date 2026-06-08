package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.util.RandomUtil;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.infrastructure.validation.Provider;
import com.softropic.skillars.platform.security.contract.ChangePasswordDto;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.platform.security.repo.Address;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"logging.level.org.springframework.security=TRACE", "enable.test.mail=true"})
@Import(TestConfig.class)
@Sql({UserServiceIT.SEC_DATA_SQL_PATH})
class UserServiceIT {

    public static final String  USER_DATA_SQL_PATH = "/sql/userData.sql";
    private static final String LOGIN_NAME         = "me@yahoo.com";
    public static final String SEC_DATA_SQL_PATH = "/sql/secData.sql";
    public static final String AUTHORITY_SQL_PATH = "/sql/authorityData.sql";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserMapper userMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        template.execute(status ->  {
            jdbcTemplate.execute("delete from main.sec");
            jdbcTemplate.execute("delete from main.user_addresses");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.authority");
            jdbcTemplate.execute("delete from main.user");
            return 0;
        });
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void createUser() {
        final UserDto userDTO = getUserData();
        final User user = userMapper.toUser(userDTO);
        final String password = "my!pass12Word*";
        final User userData = userRegistrationService.createUser(user, password);
        final Long id = userData.getId();
        final Optional<User> userOpt = userRepository.findById(id);
        assertThat(userOpt).isPresent();
        final User fetchedUser = userOpt.get();

        assertThat(fetchedUser).isNotNull();
        assertThat(fetchedUser.getActivationKey()).isNotBlank();
        assertThat(fetchedUser.getStatus()).isEqualTo(EntityStatus.INACTIVE);
        assertThat(fetchedUser.isActivated()).isFalse();
        assertThat(fetchedUser.getLogin()).isEqualTo(fetchedUser.getEmail());
        //password encrypted
        assertThat(fetchedUser.getPassword()).isNotEqualTo(user.getPassword());

        final Map<String, Object> userAuthMap = jdbcTemplate.queryForMap(
                "select ua.*, a.name as authority_name from main.user_authority ua join main.authority a on ua.authority_id = a.id where ua.user_id =" + id);
        assertThat(userAuthMap).isNotNull().isNotEmpty().containsEntry("authority_name", "ROLE_USER");
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void activateUser() {
        final UserDto userDTO = getUserData();
        final User user = userMapper.toUser(userDTO);
        final String password = "my!pass12Word12345*";
        final User userData = userRegistrationService.createUser(user, password);

        final Long id = userData.getId();
        final User fetchedUser = userRepository.findById(id).get();
        userRegistrationService.activateUser(fetchedUser.getActivationKey());

        final User fetchedActivatedUser = userRepository.findById(id).get();
        assertThat(fetchedActivatedUser.getActivationKey()).isBlank();
        assertThat(fetchedActivatedUser.getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(fetchedActivatedUser.isActivated()).isTrue();
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void testPasswordReset() {
        //user: loginId: me@yahoo.com p/w: admin*123!
        final String email = "me@yahoo.com";
        final var changePasswordDto = new ChangePasswordDto(email, LocalDate.parse("1978-03-19"), email);
        final Optional<User> userOpt = passwordResetService.prepareForPasswordReset(changePasswordDto);

        assertThat(userOpt).isNotEmpty();
        final User user = userOpt.get();
        final Long id = user.getId();
        final User fetchedUser = userRepository.findById(id).get();

        // verify prepareForPasswordReset
        final String resetKey = fetchedUser.getResetKey();
        assertThat(resetKey).isNotBlank();
        assertThat(fetchedUser.getResetExpiration()).isAfter(Instant.now(ClockProvider.getClock()));

        //reset password
        final String newPassword = "newPassword";
        passwordResetService.completePasswordReset(newPassword, resetKey);
        final String oldPassword = fetchedUser.getPassword();
        final User fetchedUserWithNewPW = userRepository.findById(id).get();
        assertThat(fetchedUserWithNewPW.getPassword()).isNotEqualTo(oldPassword);
        //Saved p/w is encrypted
        assertThat(newPassword).isNotEqualTo(fetchedUserWithNewPW.getPassword());
        assertThat(fetchedUserWithNewPW.getResetExpiration()).isNull();
        assertThat(fetchedUserWithNewPW.getResetKey()).isNull();
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void testAddressUpdate() {
        initSecurityContext();
        final Address address = Instancio.create(Address.class);
        address.setName("HOME");

        final Optional<User> userOpt = userProfileService.updatePostalAddress(address);
        assertThat(userOpt).isPresent();
        final User user = userOpt.get();
        final Long id = user.getId();
        final User fetchedUser = userRepository.findOneById(id).get();
        final Set<Address> addresses = fetchedUser.getAddresses();
        assertThat(addresses).hasSize(2).contains(address);

        final String belvueCity = "BelvueCity";
        address.setCity(belvueCity);
        final Optional<User> userOpt2 = userProfileService.updatePostalAddress(address);
        assertThat(userOpt2).isPresent();
        final User fetchedUser2 = userRepository.findById(id).get();
        final Set<Address> addresses2 = fetchedUser2.getAddresses();
        assertThat(addresses2).hasSize(2).contains(address);
        final Address address2 = addresses2.stream().findFirst().get();
        assertThat(address2.getCity()).isEqualTo(belvueCity);

        final Address address3 = Instancio.create(Address.class);
        address3.setName("WORK");
        final Optional<User> userOpt3 = userProfileService.updatePostalAddress(address3);
        assertThat(userOpt3).isPresent();
        final User fetchedUser3 = userRepository.findById(id).get();
        assertThat(fetchedUser3.getAddresses()).hasSize(3).contains(address3);
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void findUserWithAuthoritiesByLogin() {
        initSecurityContext();
        final Optional<User> userOpt = userService.findUserWithAuthoritiesByLogin(LOGIN_NAME);
        assertThat(userOpt).isPresent();
        final User user = userOpt.get();
        assertThat(user.getAuthorities()).hasSize(1);
    }

    @Test
    @Sql({AUTHORITY_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void lockUserAccount() {
        initAdminSecurityContext();
        final Optional<User> userOpt = userAdminService.lockUserAccount(LOGIN_NAME);
        assertThat(userOpt).isPresent();
        final User user = userOpt.get();
        final Long id = user.getId();
        final User fetchedUser = userRepository.findOneById(id).get();

        assertThat(fetchedUser.isLocked()).isTrue();
    }

    private UserDto getUserData() {
        final String email = "testuser" + System.currentTimeMillis() + "@yahoo.com";
        final UserDto userDto = new UserDto();
        userDto.setEmail(email);
        userDto.setLogin(email);
        userDto.setLoginIdType(LoginIdType.EMAIL);
        final PhoneNumber phoneNumber = generatePhone();
        userDto.setPhone(phoneNumber.getPhone());
        userDto.setActivated(false);
        userDto.setLangKey("en");
        userDto.setGender(Gender.MALE);
        userDto.setDob(LocalDate.of(1990, 2, 20));
        userDto.setPassword(RandomUtil.generatePassword());
        userDto.setOtpEnabled(true);
        userDto.setFirstName("Test");
        userDto.setLastName("User");
        userDto.setNationalId("nationalIdQ");
        userDto.setAuthorities(Set.of("ROLE_USER"));
        return userDto;
    }

    private PhoneNumber generatePhone() {
        final String prefix = "65";
        final Optional<String> strOpt = new Random().ints(7, 0, 9)
                                                            .mapToObj(String::valueOf)
                                                            .reduce((x, y) -> x + y);
        return new PhoneNumber(prefix+strOpt.get(), Provider.MTN, "DE");
    }

    private Principal createPrincipal() {
        return new Principal.Builder().username("me@yahoo.com")
                               .password("$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i")
                               .authorities(Set.of(new Authority("ROLE_USER")))
                               .displayName("Genie")
                               .businessId("586920556720583008")
                                      .enabled(true).otpEnabled(true)
                               .phone(generatePhone().getPhone()).build();
    }

    private void initSecurityContext() {
        final Principal principal = createPrincipal();
        var token = new UsernamePasswordAuthenticationToken(principal.getUsername(),null, principal.getAuthorities());
        token.setDetails(principal);
        final SecurityContext securityContext = new SecurityContextImpl(token);
        SecurityContextHolder.setContext(securityContext);
    }

    private Principal createAdminPrincipal() {
        return new Principal.Builder().username("queb@yahoo.com")
                                      .password("$2a$10$P52r3eqqNKljVcKTUjTJ3.P1xQiMgBq18pUsuG2JHx2QpqwIMIR5a")
                                      .authorities(Set.of(new Authority("ROLE_USER"), new Authority("ROLE_ADMIN")))
                                      .displayName("Admin")
                                      .businessId("675373350208068096")
                                      .otpEnabled(true)
                                      .enabled(true)
                                      .phone(generatePhone().getPhone())
                                      .build();
    }

    private void initAdminSecurityContext() {
        final Principal principal = createAdminPrincipal();
        var token = new UsernamePasswordAuthenticationToken(principal.getUsername(),null, principal.getAuthorities());
        token.setDetails(principal);
        final SecurityContext securityContext = new SecurityContextImpl(token);
        SecurityContextHolder.setContext(securityContext);
    }
}