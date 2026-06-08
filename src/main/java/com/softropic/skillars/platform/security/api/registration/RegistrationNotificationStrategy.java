package com.softropic.skillars.platform.security.api.registration;

import com.softropic.skillars.platform.security.repo.User;

/**
 * Strategy interface for sending registration notifications via different channels.
 * <p>
 * Implementations provide notification logic for specific channels (email, SMS, etc.)
 * when users register or when a registration is attempted for an existing account.
 * <p>
 * This pattern allows easy addition of new notification channels without modifying
 * existing code (Open/Closed Principle).
 *
 * @see EmailRegistrationStrategy
 * @see SmsRegistrationStrategy
 */
public interface RegistrationNotificationStrategy {

    /**
     * Sends a notification to a newly registered user with activation instructions.
     * <p>
     * This method is called after successful user persistence and should include
     * the activation key/link required for the user to activate their account.
     *
     * @param user the newly created user with activation key
     * @return a tracking code (e.g., short code for support, SMS confirmation ID)
     *         that can be used to reference this notification
     */
    String notifyNewUser(User user);

    /**
     * Sends a notification when registration is attempted for an existing account.
     * <p>
     * This notification informs the user that an attempt was made to register
     * with their credentials, helping them detect potential account takeover attempts.
     * <p>
     * <b>Security Note:</b> This method should implement rate limiting to prevent
     * abuse by attackers attempting to enumerate valid accounts.
     *
     * @param user the existing user whose account was targeted
     * @return a tracking code for this notification
     */
    String notifyUserExists(User user);

    /**
     * Returns the notification channel type handled by this strategy.
     *
     * @return the channel type (e.g., "EMAIL", "SMS", "PUSH")
     */
    String getChannelType();
}
