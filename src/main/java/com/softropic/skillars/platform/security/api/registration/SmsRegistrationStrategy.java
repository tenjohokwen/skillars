package com.softropic.skillars.platform.security.api.registration;

import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.platform.security.repo.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SMS-based implementation of the registration notification strategy.
 * <p>
 * Sends SMS notifications during the user registration process for phone-based
 * registrations. This includes:
 * <ul>
 *   <li>Activation SMS with verification code for newly registered users</li>
 *   <li>Security alerts when registration is attempted for existing phone numbers</li>
 * </ul>
 * <p>
 * <b>Implementation Status:</b> This is currently a stub implementation.
 * Full SMS integration requires:
 * <ul>
 *   <li>SMS gateway configuration (Twilio, AWS SNS, etc.)</li>
 *   <li>Message template management</li>
 *   <li>Delivery status tracking</li>
 *   <li>Rate limiting and fraud prevention</li>
 *   <li>Internationalization for phone number formats</li>
 * </ul>
 *
 * @see RegistrationNotificationStrategy
 */
@Component
public class SmsRegistrationStrategy implements RegistrationNotificationStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmsRegistrationStrategy.class);
    private static final String CHANNEL_TYPE = "SMS";

    /**
     * {@inheritDoc}
     * <p>
     * <b>TODO:</b> Implement SMS sending via configured SMS gateway.
     * Should send activation code to user's phone number.
     *
     * @param user the newly created user with phone number
     * @return a tracking code for the SMS (currently returns a random UUID)
     */
    @Override
    @RateLimited(key = "registration_sms", capacity = 2, duration = 5)
    public String notifyNewUser(final User user) {
        LOGGER.warn("SMS notification requested for new user {}, but SMS is not yet implemented",
                    user.getLogin());
        // TODO: Implement SMS sending logic
        // 1. Generate activation code (6-digit OTP)
        // 2. Build SMS message with activation instructions
        // 3. Send via SMS gateway API
        // 4. Store message ID and status in database
        // 5. Return tracking code
        return UUID.randomUUID().toString();
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>TODO:</b> Implement SMS notification for duplicate registration attempts.
     * Should alert existing user that someone tried to register with their number.
     * <p>
     * <b>Security Note:</b> Rate limiting is enforced via @RateLimited aspect.
     *
     * @param user the existing user whose phone was used in registration attempt
     * @return a tracking code for the SMS (currently returns a random UUID)
     */
    @Override
    @RateLimited(key = "registration_alert_sms", capacity = 2, duration = 5)
    public String notifyUserExists(final User user) {
        LOGGER.warn("SMS notification requested for existing user {}, but SMS is not yet implemented",
                    user.getLogin());
        // TODO: Implement SMS sending logic for duplicate account alert
        // 1. Check rate limiting / blacklist
        // 2. Build security alert message
        // 3. Send via SMS gateway API
        // 4. Store message ID and status in database
        // 5. Return tracking code
        return UUID.randomUUID().toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }
}
