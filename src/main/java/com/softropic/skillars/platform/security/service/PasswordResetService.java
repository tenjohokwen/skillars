package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.util.RandomUtil;
import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.contract.ChangePasswordDto;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service for handling password reset operations.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    /**
     * Prepares a user account for password reset by generating a reset key.
     * The reset key validity duration is configured via SecurityProperties.
     * Delegates to User domain entity for reset preparation logic.
     *
     * @param changePasswordDto contains user identification information
     * @return the user with reset key if successful, empty otherwise
     */
    public Optional<User> prepareForPasswordReset(ChangePasswordDto changePasswordDto) {
        return userRepository.findOneByEmail(changePasswordDto.getCurrentEmail())
                .filter(user -> StringUtils.equals(user.getLogin(), changePasswordDto.getLoginId()))
                .filter(user -> changePasswordDto.getDob().equals(user.getDateOfBirth()))
                .filter(User::canInitiatePasswordReset) // Use domain method
                .map(user -> {
                    String resetKey = RandomUtil.generateResetKey();
                    long expirationHours = securityProperties.getPasswordResetExpiration().toHours();
                    user.preparePasswordReset(resetKey, expirationHours); // Use domain method
                    return user;
                });
    }

    /**
     * Completes the password reset process using the reset key.
     * Delegates to User domain entity for password reset completion logic.
     *
     * @param newPassword the new password
     * @param key the reset key
     * @return the user with updated password if successful, empty otherwise
     */
    public Optional<User> completePasswordReset(final String newPassword, final String key) {
        return userRepository.findOneByResetKey(key)
                .filter(User::isActivated)
                .filter(User::hasValidResetKey) // Use domain method
                .map(user -> {
                    String encodedPassword = passwordEncoder.encode(newPassword);
                    user.completePasswordReset(encodedPassword); // Use domain method
                    return user;
                });
    }

    /**
     * Checks if a password matches the encoded password.
     *
     * @param password the plain text password
     * @param encodedPassword the encoded password
     * @return true if the password matches, false otherwise
     */
    public boolean isPasswordMatch(String password, String encodedPassword) {
        return passwordEncoder.matches(password, encodedPassword);
    }
}
