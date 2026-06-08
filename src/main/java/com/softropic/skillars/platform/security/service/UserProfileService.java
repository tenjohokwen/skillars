package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.infrastructure.util.PhoneNumberUtil;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.platform.security.contract.event.AccountChangeUserInfo;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.repo.Address;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.contract.exception.ProfileActionException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Service for handling user profile management operations.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher publisher;

    /**
     * Updates the current user's basic information (name and language preference).
     *
     * @param firstname the first name
     * @param lastname the last name
     * @param langKey the language key
     * @return the updated user if found, empty otherwise
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> updateUserInformation(String firstname, String lastname, String langKey, String nationalId, Gender gender, String title) {
        return userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername()).map(u -> {
            u.setFirstName(firstname);
            u.setLastName(lastname);
            u.setLangKey(langKey);
            u.setNationalId(nationalId);
            u.setGender(gender);
            u.setTitle(title);
            log.debug("User profile updated",
                kv("operation", "user_profile"),
                kv("field", "information"),
                kv("status", "UPDATED"));
            return u;
        });
    }

    /**
     * Updates the current user's postal address.
     *
     * @param address the new address
     * @return the updated user if found, empty otherwise
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> updatePostalAddress(Address address) {
        return userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername())
                .map(u -> {
                    // Capture old address before update
                    Address oldAddress = u.getAddresses().stream().findFirst().orElse(null);
                    String oldAddressStr = formatAddress(oldAddress);

                    u.addOrReplaceAddress(address);
                    String newAddressStr = formatAddress(address);

                    // Publish event for notification and audit
                    AccountChangeEvent event = new AccountChangeEvent(
                            AccountChangeEvent.Action.ADDRESS_CHANGED,
                            oldAddressStr,
                            newAddressStr,
                            buildUserInfo(u)
                    );
                    publisher.publishEvent(event);

                    return u;
                });
    }

    /**
     * Updates the current user's email address.
     * Requires the old email and current password for verification.
     *
     * @param oldEmail the current email address
     * @param newEmail the new email address
     * @param password the current password (for verification)
     * @return the updated user if successful
     * @throws ProfileActionException if email or password doesn't match
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> updateUserEmail(String oldEmail, final String newEmail, String password) {
        return userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername()).map(u -> {
            if (passwordEncoder.matches(password, u.getPassword()) && StringUtils.equals(oldEmail, u.getEmail())) {
                String capturedOldEmail = u.getEmail();
                u.setEmail(newEmail);
                u.setLogin(newEmail);
                log.debug("User profile updated",
                    kv("operation", "user_profile"),
                    kv("field", "email"),
                    kv("status", "UPDATED"));

                // Publish event for notification and audit (send to old email address)
                AccountChangeUserInfo userInfo = new AccountChangeUserInfo(
                        capturedOldEmail, // Override to send notification to OLD email for security
                        u.getFirstName(),
                        u.getLastName(),
                        u.getLangKey(),
                        u.getTitle(),
                        u.getGender() != null ? u.getGender().name() : null
                );
                AccountChangeEvent event = new AccountChangeEvent(
                        AccountChangeEvent.Action.EMAIL_CHANGED,
                        capturedOldEmail,
                        newEmail,
                        userInfo
                );
                publisher.publishEvent(event);

                return u;
            }
            final Map<String, Object> ctx = Map.of("oldEmail", oldEmail,
                    "newEmail", newEmail,
                    "passwordMatch", passwordEncoder.matches(password, u.getPassword()),
                    "emailMatch", StringUtils.equals(oldEmail, u.getEmail()));
            throw new ProfileActionException("Cannot update email address", ctx, SecurityError.EMAIL_OR_PW_MISMATCH);
        });
    }

    /**
     * Changes the current user's password.
     * Requires the current password for verification.
     *
     * @param currentPassword the current password
     * @param newPassword the new password
     * @return the updated user if successful
     * @throws ProfileActionException if current password doesn't match
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> changePassword(String currentPassword, String newPassword) {
        return userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername())
                .map(user -> {
                    if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                        throw new ProfileActionException("Current password does not match",
                                Map.of("login", user.getLogin()),
                                SecurityError.EMAIL_OR_PW_MISMATCH);
                    }
                    user.setPassword(passwordEncoder.encode(newPassword));
                    log.debug("User profile updated",
                        kv("operation", "user_profile"),
                        kv("field", "password"),
                        kv("status", "UPDATED"));

                    // Publish event for notification and audit
                    AccountChangeEvent event = new AccountChangeEvent(
                            AccountChangeEvent.Action.PASSWORD_CHANGED,
                            null,  // oldValue not applicable for password
                            null,  // newValue not applicable for password
                            buildUserInfo(user)
                    );
                    publisher.publishEvent(event);

                    return user;
                });
    }

    /**
     * Updates the current user's phone number.
     *
     * @param phone the new phone number string
     * @return the updated user if found
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> updatePhone(String phone) {
        return userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername())
                .map(user -> {
                    // Capture old phone before update
                    String oldPhone = user.getPhone() != null ? user.getPhone().getPhone() : null;

                    PhoneNumber phoneNumber = toPhoneNumber(phone);
                    user.setPhone(phoneNumber);
                    log.debug("User profile updated",
                        kv("operation", "user_profile"),
                        kv("field", "phone"),
                        kv("status", "UPDATED"));

                    // Publish event for notification and audit
                    AccountChangeEvent event = new AccountChangeEvent(
                            AccountChangeEvent.Action.PHONE_CHANGED,
                            oldPhone,
                            phone,
                            buildUserInfo(user)
                    );
                    publisher.publishEvent(event);

                    return user;
                });
    }

    /**
     * Toggles two-factor authentication for the current user.
     * Requires password verification for security.
     *
     * @param enabled the desired 2FA state
     * @param password the current password for verification
     * @return the updated user if successful
     * @throws ProfileActionException if password doesn't match
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> toggle2fa(boolean enabled, String password) {
        return userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername())
                .map(user -> {
                    if (!passwordEncoder.matches(password, user.getPassword())) {
                        throw new ProfileActionException("Password does not match",
                                                         Map.of("login", user.getLogin()),
                                                         SecurityError.EMAIL_OR_PW_MISMATCH);
                    }
                    user.setOtpEnabled(enabled);
                    log.debug("User profile updated",
                        kv("operation", "user_profile"),
                        kv("field", "2fa"),
                        kv("status", "UPDATED"));

                    // Publish event for notification and audit
                    AccountChangeEvent.Action action = enabled
                            ? AccountChangeEvent.Action.TWO_FACTOR_AUTH_ENABLED
                            : AccountChangeEvent.Action.TWO_FACTOR_AUTH_DISABLED;
                    AccountChangeEvent event = new AccountChangeEvent(
                            action,
                            String.valueOf(!enabled),  // oldValue: previous state
                            String.valueOf(enabled),   // newValue: new state
                            buildUserInfo(user)
                    );
                    publisher.publishEvent(event);

                    return user;
                });
    }

    private PhoneNumber toPhoneNumber(String phone) {
        return PhoneNumberUtil.fromString(phone);
    }

    private AccountChangeUserInfo buildUserInfo(User user) {
        return new AccountChangeUserInfo(
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getLangKey(),
                user.getTitle(),
                user.getGender() != null ? user.getGender().name() : null
        );
    }

    /**
     * Formats an Address entity as a string for audit trail purposes.
     *
     * @param address the address entity
     * @return formatted address string, or null if address is null
     */
    private String formatAddress(Address address) {
        if (address == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(address.getAddressLine1())) {
            sb.append(address.getAddressLine1());
        }
        if (StringUtils.isNotBlank(address.getCity())) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address.getCity());
        }
        if (StringUtils.isNotBlank(address.getStateProvince())) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address.getStateProvince());
        }
        if (StringUtils.isNotBlank(address.getCountry())) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address.getCountry());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
