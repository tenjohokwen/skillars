package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.security.contract.AdminBootstrapProperties;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.AuthorityRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Creates the platform's first administrator from configuration, once, at startup.
 *
 * <h2>Why this exists</h2>
 *
 * {@code SecurityConstants.HAS_ADMIN_ROLE} gates the entire admin surface, but there is no admin
 * registration endpoint and no self-service path to an admin account — the three registration
 * services cover coach, parent and player only. Until this runner, the sole way to obtain an admin
 * was hand-written SQL against a live database: insert the authority row, insert a
 * {@code main."user"} row with a bcrypt hash, then insert the join row. That was written down
 * nowhere, and it is not something an operator should be doing by hand.
 *
 * <h2>Safety posture</h2>
 *
 * <ul>
 *   <li><strong>Opt-in.</strong> Silently does nothing unless {@code app.bootstrap.admin.email} and
 *       {@code .password} are both non-blank. Every environment that has not deliberately set them
 *       is unaffected.</li>
 *   <li><strong>Not {@code @Profile}-gated.</strong> Production boots with no
 *       {@code SPRING_PROFILES_ACTIVE} at all (see the note on {@code PaymentConfig}), so a profile
 *       guard would fail-close in precisely the environment that needs to bootstrap.</li>
 *   <li><strong>Grants {@code ROLE_ADMIN} only.</strong> Never {@code ROLE_LTD_ADMIN}, never both,
 *       and never elevates a pre-existing user — if the login is taken, this skips. Upgrading an
 *       existing account to admin is a different and riskier feature, deliberately absent.</li>
 *   <li><strong>Never fails startup on data.</strong> Any failure originating from the database or
 *       from entity validation is logged and swallowed — a bootstrap that cannot write its row must
 *       not take the application down with it.</li>
 *   <li><strong>Fails startup on misconfiguration, deliberately.</strong> There are exactly three
 *       such paths, all raising {@link AppSetupException} so a half-configured bootstrap is loud
 *       rather than silently wrong: a blank phone, an email or name that the {@code User} entity's
 *       own constraints would reject, and a missing {@code ROLE_ADMIN} authority row. The first two
 *       are checked before any database work; the third can only be detected inside the
 *       transaction, so {@link #run} re-throws it explicitly past the catch-all.</li>
 *   <li><strong>Never logs the password</strong>, in any branch, at any level — see
 *       {@code AdminBootstrapProperties#password} for why {@code toString()} matters here too.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    /**
     * Copied verbatim from {@code Customer.email}'s {@code @Email(regexp = ...)}. Duplicated rather
     * than shared because the entity declares it inline in an annotation, where it cannot be
     * referenced; if that pattern changes, this must be changed with it. The pre-flight check exists
     * precisely so a mismatch surfaces as a named configuration error instead of a commit failure.
     */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,3}", Pattern.CASE_INSENSITIVE);

    /**
     * ISO-3166 alpha-2 is required by {@code PhoneNumber.iso2Country} ({@code @Size(min = 2, max = 2)},
     * {@code @NotEmpty}), but the bootstrap has no country to record and the admin's phone is never
     * dialled — it exists only to satisfy that constraint and the UNIQUE index on the column.
     * {@code "XX"} is the codebase's established placeholder for exactly this: all three
     * registration services pass it the same way ({@code CoachRegistrationService}, and its parent
     * and player counterparts). It also sits in ISO-3166's user-assigned range (XA-XZ), so it can
     * never collide with a real country code. Kept consistent with those services deliberately —
     * inventing a different placeholder here would be the inconsistency.
     */
    private static final String PLACEHOLDER_ISO2_COUNTRY = "XX";

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (StringUtils.isBlank(properties.getEmail()) || StringUtils.isBlank(properties.getPassword())) {
            return;
        }

        if (StringUtils.isBlank(properties.getPhone())) {
            throw new AppSetupException(
                "app.bootstrap.admin.email and .password are set but .phone is blank. "
                + "main.\"user\".phone carries a UNIQUE constraint and PhoneNumber.phone is @NotEmpty, "
                + "so there is no safe default to fall back on — set APP_BOOTSTRAP_ADMIN_PHONE.");
        }

        // Normalized ONCE, here, and used for the lookup, both stored columns and the log line.
        // Do not re-read properties.getEmail() below this point. findOneByEmail is a derived query
        // with no lower(), i.e. case-sensitive, while login and email are both UNIQUE — so looking
        // up the raw value while storing the lowercased one makes the second boot miss the row it
        // wrote on the first, fall through to save(), and violate the constraint. An exception out
        // of ApplicationRunner.run propagates from SpringApplication.run and fails startup, which
        // would turn "operator left the env vars set" into "the box no longer boots".
        String login = properties.getEmail().trim().toLowerCase(Locale.ROOT);

        // Check the configured values against the User entity's OWN constraints, here, before any
        // database work. Skipping this does not make the bad value work — it makes it explode at
        // COMMIT, and a commit-time bean-validation failure surfaces as TransactionSystemException,
        // NOT DataIntegrityViolationException (verified by execution). It therefore slips past a
        // catch aimed at persistence errors and takes the whole application down on startup.
        //
        // This is not hypothetical. Customer's @Email regex caps the TLD at three characters
        // ([a-z]{2,3}), so an ordinary admin address on .info, .cloud, .tech or .online is rejected
        // — a configuration a reasonable operator would expect to work, turning the first boot into
        // a box that will not start. Failing here instead names the offending value and says what
        // is wrong with it.
        validateAgainstEntityConstraints(login);

        if (userRepository.findOneByEmail(login).isPresent()) {
            log.info("Admin bootstrap skipped — account already exists",
                kv("operation", "admin_bootstrap"),
                kv("action", "skip_existing"),
                kv("status", "SUCCESS"),
                kv("login", login));
            return;
        }

        try {
            // The authority lookup and the save MUST share one transaction. User.authorities is
            // @ManyToMany(cascade = {REFRESH, DETACH, PERSIST}), so an Authority read in a
            // different transaction is detached by the time save() cascades PERSIST onto it, and
            // Hibernate rejects it with "detached entity passed to persist". CoachRegistrationService
            // never hits this only because it is class-level @Transactional.
            //
            // TransactionTemplate rather than @Transactional on run(): the catch below must sit
            // OUTSIDE the transaction. Catching a constraint violation inside a still-open
            // transaction leaves it rollback-only and turns the commit into an UnexpectedRollbackException
            // — the same swallow-the-wrong-exception trap deferred-12 and deferred-14 both had to fix.
            // Here the violation surfaces from execute()'s commit, after the rollback has happened.
            transactionTemplate.executeWithoutResult(status -> {
                Authority adminAuthority = authorityRepository.findOneByName(SecurityConstants.ROLE_ADMIN)
                    .orElseThrow(() -> new AppSetupException(
                        "ROLE_ADMIN authority not found. It is seeded by V92__seed_admin_authorities.sql — "
                        + "a missing row means migrations did not run or were tampered with."));

                User user = new User();
                user.setLogin(login);
                user.setLoginIdType(LoginIdType.EMAIL);
                user.setEmail(login);
                user.setPassword(passwordEncoder.encode(properties.getPassword()));
                user.setFirstName(properties.getFirstName());
                user.setLastName(properties.getLastName());
                user.setPhone(new PhoneNumber(properties.getPhone(), PLACEHOLDER_ISO2_COUNTRY));
                user.setGender(Gender.OTHER);
                user.setDateOfBirth(LocalDate.of(1900, 1, 1));
                user.setLangKey("en");
                // The three fields below are what make the account actually usable, each load-bearing:
                //   activated=true      -> AuthService throws DisabledException otherwise, AND
                //                          UserAdminService.removeNotActivatedUsers (daily, 01:00)
                //                          DELETES non-activated users past the expiry window — an
                //                          admin left inactive would silently vanish overnight.
                //   BASIC_VERIFIED      -> AuthService refuses login when skillarsRole != null and the
                //                          phone-otp-required toggle (default true) is on and the
                //                          status is anything else. An admin has no OTP flow to complete.
                //   skillarsRole=ADMIN  -> without it AuthService still REPORTS role "ADMIN" (its null
                //                          fallback) while granting no authority, which is a confusing
                //                          half-state; GdprErasureService also compares against this enum.
                user.setActivated(true);
                user.setVerificationStatus(SkillarsVerificationStatus.BASIC_VERIFIED);
                user.setSkillarsRole(SkillarsRole.ADMIN);
                user.setStatus(EntityStatus.ACTIVE);
                user.setAuthorities(Set.of(adminAuthority));

                userRepository.save(user);
            });
        } catch (AppSetupException ex) {
            // The ROLE_ADMIN-missing case, which can only be detected inside the transaction. It is
            // a deliberate refuse-to-start (migrations did not run, or were tampered with), so it
            // must NOT be absorbed by the catch below.
            throw ex;
        } catch (RuntimeException ex) {
            // Deliberately every remaining runtime failure, not just DataIntegrityViolationException.
            // The failure this catch exists for does not arrive as that type: a bean-validation
            // violation fails at COMMIT and surfaces as TransactionSystemException, so a narrower
            // catch let it escape and fail startup — the exact contract this class claims to honour.
            // Anything reaching here means the row could not be written; there is no caller to
            // report to, and rethrowing from an ApplicationRunner takes the application down. The
            // pre-flight check above already rejects the misconfigurations worth naming, so what is
            // left is genuinely environmental — most often a UAT database that already holds a
            // hand-inserted admin (the documented pre-V92 workaround) at a different casing, or a
            // row occupying this phone number. Log loudly, skip, let the application serve traffic.
            log.warn("Admin bootstrap skipped — could not create the account",
                kv("operation", "admin_bootstrap"),
                kv("action", "skip_failed"),
                kv("status", "WARN"),
                kv("login", login),
                ex);
            return;
        }

        log.info("Admin bootstrap created the first administrator",
            kv("operation", "admin_bootstrap"),
            kv("action", "create_admin"),
            kv("status", "SUCCESS"),
            kv("login", login),
            kv("authority", SecurityConstants.ROLE_ADMIN));
    }

    /**
     * Rejects configuration the {@code User} entity's own Bean Validation constraints would reject,
     * before the transaction opens.
     *
     * <p>These mirror {@code Customer}/{@code User} deliberately rather than inventing new rules —
     * if the entity ever relaxes them, this only becomes conservative, never wrong. Kept as an
     * explicit pre-flight instead of letting the commit fail because a commit-time violation is
     * both untyped (it arrives as {@code TransactionSystemException}) and unhelpful: the message
     * names a JPA transaction, not the environment variable the operator has to change.
     */
    private void validateAgainstEntityConstraints(String login) {
        // Customer.email / User.login: @Size(min = 6, max = 100).
        if (login.length() < 6 || login.length() > 100) {
            throw new AppSetupException(
                "app.bootstrap.admin.email must be between 6 and 100 characters (got "
                + login.length() + "). This mirrors the User entity's own @Size constraint.");
        }
        // Customer.email / User.login: @Email(regexp = "[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,3}").
        // NOTE the {2,3}: this codebase's email pattern does NOT accept a four-or-more character
        // top-level domain, so .info/.cloud/.tech/.online are rejected platform-wide, not just here.
        // Say so plainly — an operator hitting this has done nothing unreasonable.
        if (!EMAIL_PATTERN.matcher(login).matches()) {
            throw new AppSetupException(
                "app.bootstrap.admin.email ('" + login + "') does not satisfy the User entity's "
                + "@Email pattern. Note that this application's pattern accepts only 2-3 character "
                + "top-level domains, so addresses such as .info, .cloud or .tech are rejected — "
                + "not a bootstrap limitation, the same rule applies to every account in the system. "
                + "Use an address with a 2-3 character TLD, or relax the pattern on Customer.email.");
        }
        // Customer.firstName / lastName: @Size(max = 50), both @Column(nullable = false).
        requireName(properties.getFirstName(), "app.bootstrap.admin.first-name");
        requireName(properties.getLastName(), "app.bootstrap.admin.last-name");
    }

    private void requireName(String value, String property) {
        if (StringUtils.isBlank(value)) {
            throw new AppSetupException(property + " must not be blank — the column is NOT NULL.");
        }
        if (value.length() > 50) {
            throw new AppSetupException(
                property + " must be at most 50 characters (got " + value.length() + ").");
        }
    }
}
