package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.event.RegistrationDuplicateAttemptEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The neutral duplicate-registration path shared by {@link ParentRegistrationService},
 * {@link PlayerRegistrationService} and {@link CoachRegistrationService}.
 *
 * <p>Registration must not reveal whether an email address or phone number is already on the
 * platform. When either already belongs to an account, the caller creates nothing and returns
 * normally, so the HTTP response is identical to a successful registration; the existing account's
 * owner is told instead, by a {@code CREATION_DUP} email enqueued through the transactional outbox
 * ({@code RegistrationDuplicateAttemptListener}).
 *
 * <p>Both checks run BEFORE any insert on purpose. Catching the {@code user_phone_key} violation
 * instead would leave the transaction rollback-only, and the notice enqueued after it would never
 * commit.
 */
final class RegistrationDuplicateGuard {

    private static final Logger log = LoggerFactory.getLogger(RegistrationDuplicateGuard.class);

    // GdprErasureService's tombstone address shape: an erased account keeps its row but is no one's.
    private static final String ERASED_EMAIL_DOMAIN = "@erased.io";

    private RegistrationDuplicateGuard() {}

    /**
     * @return {@code true} if {@code email} or {@code phone} already belongs to an account, in which
     *     case the duplicate notice has been published and the caller must return without creating
     *     anything; {@code false} if the caller should go on and create the account.
     */
    static boolean handledAsDuplicate(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                      ApplicationEventPublisher publisher, String flow,
                                      String email, String phone, String rawPassword) {
        String reason = "email";
        Optional<User> existing = userRepository.findOneByEmail(email);
        if (existing.isEmpty() && phone != null && !phone.isBlank()) {
            reason = "phone";
            existing = userRepository.findOneByPhoneNumber(phone);
        }
        if (existing.isEmpty()) {
            return false;
        }

        // Timing: the create path pays for a bcrypt hash; without this the neutral path would answer
        // measurably faster and the response time alone would reveal the account exists.
        passwordEncoder.encode(rawPassword);

        User user = existing.get();
        log.info("[REGISTRATION_DUPLICATE_ATTEMPT] registration matched an existing account",
            kv("flow", flow), kv("reason", reason), kv("userId", user.getId()));

        if (user.isLocked() && user.getEmail() != null && user.getEmail().endsWith(ERASED_EMAIL_DOMAIN)) {
            // A GDPR-erased tombstone: nobody receives mail there, so there is no one to tell.
            return true;
        }
        publisher.publishEvent(new RegistrationDuplicateAttemptEvent(
            user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getLangKey()));
        return true;
    }
}
