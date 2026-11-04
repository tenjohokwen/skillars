package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.security.contract.event.RegistrationDuplicateAttemptEvent;
import com.softropic.skillars.platform.security.contract.event.RegistrationPhoneInUseEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
 * <p>When the match is on the PHONE only (the typed email is new), the address the registrant typed is
 * ALSO told, by a {@code REGISTRATION_PHONE_IN_USE} email: phone numbers are not verified at
 * registration, so the number may sit on someone else's account, and without this the real owner
 * would wait forever for a verification mail that never comes. That mail goes only to the typed
 * address, which the registrant controls, and says only that the number is taken (the owner accepted
 * this, option A). An email match is unchanged: only the existing account is told.
 *
 * <p>Both checks run BEFORE any insert on purpose. Catching the {@code user_phone_key} violation
 * instead would leave the transaction rollback-only, and the notice enqueued after it would never
 * commit.
 */
final class RegistrationDuplicateGuard {

    private static final Logger log = LoggerFactory.getLogger(RegistrationDuplicateGuard.class);

    // GdprErasureService's tombstone address shape: an erased account keeps its row but is no one's.
    private static final String ERASED_EMAIL_DOMAIN = "@erased.io";

    // Per existing account, across all three flows and every IP: the register endpoints' own limits
    // are per IP, so without this a pool of IPs could flood one inbox with notices (the resend path's
    // per-email bucket guards the same thing). Exhaustion skips the notice silently; the response
    // stays the same, so the limit is no oracle either.
    static final String NOTICE_LIMIT_KEY = "registration_duplicate_notice";
    static final long NOTICE_CAPACITY = 2;
    static final long NOTICE_WINDOW_MINUTES = 60;

    // Per typed address (lowercased), for the phone-in-use mail to the registrant: without it, anyone
    // holding one registered phone number could mail any address over and over. Independent of the
    // per-account bucket above; exhaustion skips the mail silently, the response is unchanged.
    static final String REGISTRANT_NOTICE_LIMIT_KEY = "registration_phone_in_use_notice";
    static final long REGISTRANT_NOTICE_CAPACITY = 2;
    static final long REGISTRANT_NOTICE_WINDOW_MINUTES = 60;

    private RegistrationDuplicateGuard() {}

    /**
     * @return {@code true} if {@code email} or {@code phone} already belongs to an account, in which
     *     case the duplicate notice has been published and the caller must return without creating
     *     anything; {@code false} if the caller should go on and create the account.
     * @param langKey the registration request's language; blank falls back to {@code "en"}, as the
     *     create path does. Used only for the phone-in-use mail to the typed address.
     */
    static boolean handledAsDuplicate(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                      ApplicationEventPublisher publisher, RateLimitingService rateLimitingService,
                                      String flow,
                                      String email, String phone, String rawPassword, String langKey) {
        String reason = "email";
        // login too: the legacy facade stored login as typed and email lowercased, and the new row's
        // login (= email) would hit user_login_key, an error only an existing account produces.
        Optional<User> existing = userRepository.findOneByEmail(email).or(() -> userRepository.findOneByLogin(email));
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

        notifyExistingAccount(publisher, rateLimitingService, user);
        if ("phone".equals(reason)) {
            // Independent of the existing-account notice: sent even when that one was skipped (its
            // limit, or an erased tombstone, though erasure clears the phone today).
            notifyRegistrant(publisher, rateLimitingService, email, langKey);
        }
        return true;
    }

    private static void notifyExistingAccount(ApplicationEventPublisher publisher,
                                              RateLimitingService rateLimitingService, User user) {
        if (user.isLocked() && user.getEmail() != null && user.getEmail().endsWith(ERASED_EMAIL_DOMAIN)) {
            // A GDPR-erased tombstone: nobody receives mail there, so there is no one to tell.
            return;
        }
        if (!rateLimitingService.tryConsume(String.valueOf(user.getId()), NOTICE_LIMIT_KEY, NOTICE_CAPACITY,
                NOTICE_WINDOW_MINUTES, TimeUnit.MINUTES)) {
            return;
        }
        publisher.publishEvent(new RegistrationDuplicateAttemptEvent(
            user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getLangKey()));
    }

    private static void notifyRegistrant(ApplicationEventPublisher publisher, RateLimitingService rateLimitingService,
                                         String email, String langKey) {
        if (email == null || email.isBlank()) {
            return;
        }
        if (!rateLimitingService.tryConsume(email.toLowerCase(Locale.ROOT), REGISTRANT_NOTICE_LIMIT_KEY,
                REGISTRANT_NOTICE_CAPACITY, REGISTRANT_NOTICE_WINDOW_MINUTES, TimeUnit.MINUTES)) {
            return;
        }
        String lang = langKey != null && !langKey.isBlank() ? langKey : "en";
        publisher.publishEvent(new RegistrationPhoneInUseEvent(email, lang));
    }
}
