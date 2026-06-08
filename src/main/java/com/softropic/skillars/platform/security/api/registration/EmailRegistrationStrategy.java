package com.softropic.skillars.platform.security.api.registration;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.infrastructure.security.ClientContextProvider;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.platform.security.repo.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Email-based implementation of the registration notification strategy.
 * <p>
 * Sends email notifications during the user registration process, including:
 * <ul>
 *   <li>Activation emails for newly registered users with activation links</li>
 *   <li>Security alerts when registration is attempted for existing accounts</li>
 * </ul>
 * <p>
 * All emails include client context information (browser, OS, device, IP address)
 * to help users identify suspicious activity.
 *
 * @see RegistrationNotificationStrategy
 */
@Component
@Transactional
public class EmailRegistrationStrategy implements RegistrationNotificationStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailRegistrationStrategy.class);
    private static final String CHANNEL_TYPE = "EMAIL";

    private final ApplicationEventPublisher publisher;
    private final String host;

    public EmailRegistrationStrategy(final ApplicationEventPublisher publisher,
                                     @Value("${baseurl}") final String baseurl,
                                     @Value("${server.port}") final String serverPort) {
        this.publisher = publisher;
        this.host = baseurl + ":" + serverPort;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sends an activation email to the newly registered user containing:
     * <ul>
     *   <li>Activation link with the user's activation key</li>
     *   <li>Client context (browser, OS, device, IP) for security awareness</li>
     *   <li>Support tracking code for reference</li>
     * </ul>
     * <p>
     * The activation email has no deadline, encouraging immediate activation.
     *
     * @param user the newly created user with activation key set
     * @return a short tracking code that can be used for support reference
     */
    @Override
    @RateLimited(key = "registration_email", capacity = 2, duration = 5) // Max 2 emails per 5 minutes per IP
    public String notifyNewUser(final User user) {
        final Map<String, Object> dataMap = ClientContextProvider.getClientContextMap();
        dataMap.put("activationKey", user.getActivationKey());
        dataMap.put("lang", user.getLangKey());

        return sendEmail(EmailTemplate.ACTIVATION,
                         Instant.now(ClockProvider.getClock()),
                         dataMap,
                         user);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sends a security notification email to the existing user informing them that
     * someone attempted to register with their email address. This helps users
     * detect potential account takeover attempts.
     * <p>
     * The email includes client context to help the user determine if the attempt
     * was legitimate (e.g., they forgot they already registered) or suspicious.
     * <p>
     * <b>Security Note:</b> Callers should implement rate limiting to prevent
     * abuse by attackers attempting to enumerate valid email addresses.
     *
     * @param user the existing user whose email was used in the registration attempt
     * @return a short tracking code that can be used for support reference
     */
    @Override
    @RateLimited(key = "registration_alert_email", capacity = 2, duration = 5)
    public String notifyUserExists(final User user) {
        final Map<String, Object> dataMap = ClientContextProvider.getClientContextMap();

        return sendEmail(EmailTemplate.CREATION_DUP,
                         Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
                         dataMap,
                         user);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    /**
     * Sends an email by publishing an envelope event to the messaging system.
     * <p>
     * This method creates a unique tracking code, builds the email envelope with
     * the provided template and data, and publishes it as an event for asynchronous
     * processing by the email service.
     *
     * @param emailTemplate the template to use for the email content
     * @param deadline the deadline by which the email should be sent
     * @param dataMap template variables including client context
     * @param user the recipient user
     * @return a short tracking code for support reference
     */
    private String sendEmail(final EmailTemplate emailTemplate,
                             final Instant deadline,
                             final Map<String, Object> dataMap,
                             final User user) {
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

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Email notification queued for user: {}, template: {}, trackingCode: {}",
                        user.getLogin(), emailTemplate, shortCode);
        }

        return shortCode;
    }

    /**
     * Builds a recipient object from a user entity.
     *
     * @param user the user to convert to a recipient
     * @return a recipient configured with the user's details
     */
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
}
