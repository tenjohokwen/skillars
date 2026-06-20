package com.softropic.skillars.platform.security.api;


import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.util.PhoneNumberUtil;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.api.registration.EmailRegistrationStrategy;
import com.softropic.skillars.platform.security.api.registration.RegistrationNotificationStrategy;
import com.softropic.skillars.platform.security.api.registration.SmsRegistrationStrategy;
import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.platform.security.contract.ChangePasswordDto;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.security.ClientContextProvider;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.platform.security.contract.event.UserRegisteredEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.service.UserService;
import com.softropic.skillars.platform.security.service.UserRegistrationService;
import com.softropic.skillars.platform.security.service.UserProfileService;
import com.softropic.skillars.platform.security.service.PasswordResetService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.common.util.StringUtils;

import static com.softropic.skillars.infrastructure.security.SecurityError.EMAIL_OR_PW_MISMATCH;
import static com.softropic.skillars.infrastructure.security.SecurityError.PWD_RESET_REJECTED;


@Service
@Transactional
public class AccountManagementFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountManagementFacade.class);

    private final UserService userService;
    private final UserRegistrationService userRegistrationService;
    private final UserProfileService userProfileService;
    private final PasswordResetService passwordResetService;
    private final ApplicationEventPublisher publisher;
    private final EmailRegistrationStrategy emailStrategy;
    private final SmsRegistrationStrategy smsStrategy;
    private final String host;

    public AccountManagementFacade(final UserService userService,
                                   final UserRegistrationService userRegistrationService,
                                   final UserProfileService userProfileService,
                                   final PasswordResetService passwordResetService,
                                   final ApplicationEventPublisher publisher,
                                   final EmailRegistrationStrategy emailStrategy,
                                   final SmsRegistrationStrategy smsStrategy,
                                   @Value("${baseurl}") final String baseurl,
                                   @Value("${server.port}") final String serverPort) {
        this.userService = userService;
        this.userRegistrationService = userRegistrationService;
        this.userProfileService = userProfileService;
        this.passwordResetService = passwordResetService;
        this.publisher = publisher;
        this.emailStrategy = emailStrategy;
        this.smsStrategy = smsStrategy;
        this.host = baseurl + ":" + serverPort;
    }

    @RateLimited(key = "password_reset_request", capacity = 3, duration = 15) // 3 requests per 15 minutes
    public String sendPasswordResetMail(ChangePasswordDto changePasswordDto) {
        return passwordResetService.prepareForPasswordReset(changePasswordDto)
                          .map(user -> {
                              final Map<String, Object> dataMap = ClientContextProvider.getClientContextMap();
                              dataMap.put("resetKey", user.getResetKey());
                              return sendMail(EmailTemplate.PASSWORD_RESET,
                                              Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(1)),
                                              dataMap,
                                              user);
                          }
                          ).orElseThrow(() -> new OperationNotAllowedException("The given data or account state does not permit the reset of the password.",
                                                                               Map.of("email", changePasswordDto.getCurrentEmail(),
                                                                                      "loginId", changePasswordDto.getLoginId(),
                                                                                      "dob", changePasswordDto.getDob()),
                                                                               PWD_RESET_REJECTED));
    }

    @RateLimited(key = "password_reset_finish", capacity = 5, duration = 10)
    public User finishPasswordReset(final KeyAndPasswordDto keyAndPassword) {
        return passwordResetService.completePasswordReset(keyAndPassword.password(), keyAndPassword.key())
                          .orElseThrow(() -> new OperationNotAllowedException("The account state does not permit the reset of the password.",
                                                                              Map.of("key", keyAndPassword.key()),
                                                                              PWD_RESET_REJECTED));
    }

    /**
     * Registers a new user account and sends appropriate notification via email or SMS.
     * <p>
     * This method handles both email-based and phone-based registration by using
     * the Strategy pattern to delegate notification logic to the appropriate channel.
     * <p>
     * <b>Security Considerations:</b>
     * <ul>
     *   <li>When user already exists, sends security alert notification</li>
     *   <li>Uses timing-safe comparison to prevent user enumeration</li>
     *   <li>Delegates to strategies that implement rate limiting</li>
     * </ul>
     * <p>
     * <b>Security implementation:</b> Rate limiting is enforced via @RateLimited aspect.
     * <p>
     * <b>TODO:</b> Messaging module should avoid sending duplicate notifications to
     * the same user within 5 minutes.
     *
     * @param userDTO the user registration data
     * @return a tracking code for the notification sent
     */
    @RateLimited(key = "account_registration", capacity = 5, duration = 60) // 5 registrations per hour per IP
    public String registerAccount(final UserDto userDTO) {
        final Optional<User> optionalUser = userRegistrationService.findUserByEmailOrLogin(
            userDTO.getEmail() != null ? userDTO.getEmail().toLowerCase() : null,
            userDTO.getLogin() != null ? userDTO.getLogin().toLowerCase() : null
        );

        final RegistrationNotificationStrategy strategy;

        if (StringUtils.isNotBlank(userDTO.getEmail())) {
            userDTO.setLoginIdType(LoginIdType.EMAIL);
            userDTO.setLogin(userDTO.getEmail());
            strategy = emailStrategy;
        } else {
            userDTO.setLoginIdType(LoginIdType.PHONE);
            userDTO.setLogin(userDTO.getPhone());
            strategy = smsStrategy;
        }

        if (optionalUser.isPresent()) {
            return strategy.notifyUserExists(optionalUser.get());
        } else {
            final User user = persistUser(userDTO);
            publisher.publishEvent(new UserRegisteredEvent(user.getLogin(), userDTO.getLoginIdType().name()));
            return strategy.notifyNewUser(user);
        }
        // Note: The notification works only when login matches the notification channel
        // (e.g., email for EMAIL type). Using login names with email notifications could
        // expose valid logins to attackers.
    }

    @RateLimited(key = "resend_registration", capacity = 3, duration = 30)
    public String resendRegistrationLink(String login, String password) {
        return userService.findUserByLogin(login)
                .filter(user -> user.getActivationDate() == null)
                .filter(user -> passwordResetService.isPasswordMatch(password, user.getPassword()))
                .map(emailStrategy::notifyNewUser)
                .orElseThrow(() -> new OperationNotAllowedException("Resending registration link not allowed.",
                        Map.of("login", login),
                        EMAIL_OR_PW_MISMATCH));
    }

    @RateLimited(key = "change_email", capacity = 5, duration = 60)
    public String changeEmail(String oldEmail, String newEmail, String password) {
        return userProfileService.updateUserEmail(oldEmail, newEmail, password)
                .map(user -> {
                    Map<String, Object> dataMap = ClientContextProvider.getClientContextMap();
                    dataMap.put("action", "EMAIL_CHANGED");
                    dataMap.put("oldValue", oldEmail);
                    dataMap.put("newValue", newEmail);
                    return sendMail(EmailTemplate.PROFILE_CHANGE,
                            Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
                            dataMap,
                            user);
                })
                .orElseThrow(() -> new OperationNotAllowedException("Email change not allowed.",
                        Map.of("oldEmail", oldEmail, "newEmail", newEmail),
                        EMAIL_OR_PW_MISMATCH));
    }

    private String sendMail(EmailTemplate emailTemplate,
                          Instant deadline,
                          Map<String, Object> dataMap,
                          User user) {
        final String shortCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        dataMap.put("helpCode", shortCode);
        dataMap.put("baseUrl", host);
        final Recipient recipient = buildRecipient(user);
        final Envelope envelope = new Envelope(List.of(recipient),
                                               emailTemplate,
                                               deadline,
                                               dataMap,
                                               shortCode);
        publisher.publishEvent(envelope);

        if(LOGGER.isInfoEnabled()) {
            LOGGER.info(user.getLogin());
        }
        return shortCode;
    }

    private User persistUser(final UserDto userDTO){
        return userRegistrationService.createUser(toUser(userDTO), userDTO.getPassword());
    }

    private Recipient buildRecipient(final User user) {
        final Recipient recipient = new Recipient();
        recipient.setFirstname(user.getFirstName());
        recipient.setLastname(user.getLastName());
        recipient.setEmail(user.getEmail());
        recipient.setLangKey(user.getLangKey());
        recipient.setTitle(user.getTitle());
        recipient.setGender(Objects.toString(user.getGender()));
        return recipient;
    }

    private User toUser(final UserDto userDTO) {
        final User user = new User();
        user.setLogin(userDTO.getLogin());
        user.setLoginIdType(userDTO.getLoginIdType());
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail().toLowerCase());
        user.setPhone(toPhoneNumber(userDTO.getPhone()));
        user.setLangKey(userDTO.getLangKey());
        user.setGender(userDTO.getGender());
        user.setTitle(userDTO.getTitle());
        user.setDateOfBirth(userDTO.getDob());
        user.setOtpEnabled(userDTO.isOtpEnabled());
        user.setNationalId(userDTO.getNationalId());
        return user;
    }

    protected PhoneNumber toPhoneNumber(String phone) {
        return PhoneNumberUtil.fromString(phone);
    }


}
