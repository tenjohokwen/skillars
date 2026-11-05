package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.admin.service.GdprRequestService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.api.AccountManagementFacade;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.platform.security.contract.exception.EmailChangeLinkInvalidException;
import com.softropic.skillars.platform.security.contract.exception.ProfileActionException;
import com.softropic.skillars.platform.security.contract.exception.RecentEmailChangeException;
import com.softropic.skillars.platform.security.repo.Authority;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-172 AC1, AC3-AC8: the email change end to end, against the real database and outbox (the test
 * profile drains the outbox synchronously after commit, so the envelopes are in {@link TestMailManager}).
 * Not transactional: every service call commits, as in production.
 */
@Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
class EmailChangeServiceIT extends AbstractIntegrationTest {

    private static final String ME = "me@yahoo.com";
    private static final long ME_ID = 586920556720583008L;
    private static final String OTHER = "queb@yahoo.com";
    private static final String PASSWORD = "admin*123!";
    private static final String NEW = "new.me@example.com";

    @Autowired private EmailChangeService emailChangeService;
    @Autowired private UserProfileService userProfileService;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private GdprRequestService gdprRequestService;
    @Autowired private AccountManagementFacade facade;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TestMailManager testMailManager;
    @Autowired private RateLimitingService rateLimitingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        clearRateLimits();
        testMailManager.clear();
        signInAs(ME, ME_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        clearRateLimits();
    }

    // ── AC4: nothing changes until the new address confirms ──────────────────────────────────────────

    @Test
    void request_sendsTheLinkToTheNewAddressOnly_andChangesNothing() {
        emailChangeService.request(ME, NEW, PASSWORD);

        User me = user(ME_ID);
        assertThat(me.getEmail()).isEqualTo(ME);
        assertThat(me.getLogin()).isEqualTo(ME);
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE)).containsExactly(NEW);
        assertThat(testMailManager.getEnvelopes()).hasSize(1);
    }

    @Test
    void request_wrongPasswordOrCurrentAddress_isRefused_andTheCurrentAddressMatchesInAnyCasing() {
        assertThatThrownBy(() -> emailChangeService.request(ME, NEW, "wrong-password"))
            .isInstanceOf(ProfileActionException.class);
        assertThatThrownBy(() -> emailChangeService.request("someone@else.com", NEW, PASSWORD))
            .isInstanceOf(ProfileActionException.class);

        emailChangeService.request("Me@Yahoo.COM", NEW, PASSWORD);
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE)).containsExactly(NEW);
    }

    @Test
    void aSecondRequest_supersedesTheFirst_andAnExpiredLinkIsRefused() {
        emailChangeService.request(ME, NEW, PASSWORD);
        String first = confirmToken();
        testMailManager.clear();
        emailChangeService.request(ME, "other.new@example.com", PASSWORD);
        String second = confirmToken();

        assertThatThrownBy(() -> emailChangeService.confirm(first)).isInstanceOf(EmailChangeLinkInvalidException.class);

        commit("UPDATE main.email_change_requests SET confirm_expires_at = now() - interval '1 minute' "
            + "WHERE confirmed_at IS NULL AND superseded_at IS NULL");
        assertThatThrownBy(() -> emailChangeService.confirm(second)).isInstanceOf(EmailChangeLinkInvalidException.class);
        assertThat(user(ME_ID).getEmail()).isEqualTo(ME);
    }

    @Test
    void aPasswordChange_endsThePendingRequest() {
        emailChangeService.request(ME, NEW, PASSWORD);
        String token = confirmToken();

        userProfileService.changePassword(PASSWORD, "another*Pass1");

        assertThatThrownBy(() -> emailChangeService.confirm(token)).isInstanceOf(EmailChangeLinkInvalidException.class);
    }

    // ── AC3: a taken address is answered like a free one, and its holder is told ──────────────────────

    @Test
    void aTakenAddress_inAnyCasing_writesNoRow_endsThePreviousLink_andTellsItsOwnerAtMostTwiceAnHour() {
        emailChangeService.request(ME, NEW, PASSWORD);
        String previous = confirmToken();
        testMailManager.clear();

        emailChangeService.request(ME, "QUEB@Yahoo.com", PASSWORD); // returns normally, like a free address

        assertThat(openRequests()).isZero();
        assertThatThrownBy(() -> emailChangeService.confirm(previous))
            .isInstanceOf(EmailChangeLinkInvalidException.class);
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE)).containsExactly(OTHER);
        assertThat(onlyEnvelope(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE).data()).containsEntry("reserved", false);

        emailChangeService.request(ME, OTHER, PASSWORD); // the holder's second notice this hour
        clearRequestBucketOnly(); // the caller's own bucket, not the holder's notice bucket under test
        emailChangeService.request(ME, OTHER, PASSWORD); // third attempt: nothing more
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE)).hasSize(2);
    }

    @Test
    void theRequestBucket_answers429AfterThreeRequestsAnHour() {
        for (int i = 0; i < 3; i++) {
            emailChangeService.request(ME, "n" + i + "@example.com", PASSWORD);
        }
        assertThatThrownBy(() -> emailChangeService.request(ME, "n4@example.com", PASSWORD))
            .hasMessageContaining("email.change.tooManyRequests");
    }

    @Test
    void wrongPasswords_countTowardsAnAttemptLimit_soTheEndpointCannotBeUsedToGuessThePassword() {
        for (int i = 0; i < EmailChangeService.ATTEMPT_CAPACITY; i++) {
            assertThatThrownBy(() -> emailChangeService.request(ME, NEW, "guess-" + Math.random()))
                .isInstanceOf(ProfileActionException.class);
        }
        // Even the right password is refused now: the limit is consumed before the password is checked.
        assertThatThrownBy(() -> emailChangeService.request(ME, NEW, PASSWORD))
            .hasMessageContaining("email.change.tooManyRequests");
    }

    @Test
    void anotherAccount_cannotUseUpTheConfirmationMailsOfAnAddress_butTheAddressHasADailyTotal() {
        signInAs(OTHER, otherId());
        for (int i = 0; i <= EmailChangeService.CONFIRM_MAIL_CAPACITY; i++) {
            clearRequestBucketOnly(); // the caller's request bucket, not the confirmation-mail limits under test
            emailChangeService.request(OTHER, NEW, PASSWORD);
        }
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE)).hasSize((int) EmailChangeService.CONFIRM_MAIL_CAPACITY);

        // The owner of NEW, moving their own account there, still gets the link.
        testMailManager.clear();
        signInAs(ME, ME_ID);
        emailChangeService.request(ME, NEW, PASSWORD);
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE)).containsExactly(NEW);

        // Until the address's daily total is used up (by however many accounts), silently.
        testMailManager.clear();
        for (int i = 0; i < EmailChangeService.CONFIRM_MAIL_TARGET_CAPACITY; i++) {
            rateLimitingService.tryConsume(NEW, EmailChangeService.CONFIRM_MAIL_TARGET_LIMIT_KEY,
                EmailChangeService.CONFIRM_MAIL_TARGET_CAPACITY, EmailChangeService.CONFIRM_MAIL_WINDOW_HOURS,
                TimeUnit.HOURS);
        }
        clearRequestBucketOnly();
        emailChangeService.request(ME, NEW, PASSWORD);
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE)).isEmpty();
    }

    @Test
    void aResetKeyReplacedWhileItsResetIsRunning_isRefused_soTheReplacementPasswordSurvives() throws Exception {
        // An undo replaces the reset key and the password in one transaction. A reset with the previous key that
        // runs at the same moment must not overwrite them: its lookup locks the row, waits for the undo, and
        // then no longer finds the key. The undo's write is simulated by an open transaction held on a latch.
        commit("UPDATE main.\"user\" SET reset_key = 'oldkey', reset_expiration = now() + interval '1 hour' "
            + "WHERE id = ?", ME_ID);
        String undoPasswordHash = passwordEncoder.encode("undo-replacement");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> undo = pool.submit(() -> transactionTemplate.executeWithoutResult(s -> {
                jdbcTemplate.update("UPDATE main.\"user\" SET reset_key = 'undokey', password_hash = ? WHERE id = ?",
                    undoPasswordHash, ME_ID);
                written.countDown();
                await(release);
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<User>> reset = pool.submit(() -> passwordResetService.completePasswordReset(
                "Attacker*123!", "oldkey"));
            waitUntilABackendWaitsOnALock();
            release.countDown();
            undo.get(10, TimeUnit.SECONDS);

            assertThat(reset.get(10, TimeUnit.SECONDS).map(User::getId)).as("reset with the replaced key").isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        User me = user(ME_ID);
        assertThat(me.getResetKey()).isEqualTo("undokey");
        assertThat(passwordEncoder.matches("undo-replacement", me.getPassword())).isTrue();
    }

    private void waitUntilABackendWaitsOnALock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the reset never waited on the row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void theUserForeignKey_cascades_soAHardDeleteOfTheAccountIsNeverBlocked() {
        // UserAdminService's sweep hard-deletes never-activated accounts; GDPR erasure keeps the row.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT confdeltype FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'main.email_change_requests'::regclass "
                + "AND confrelid = 'main.\"user\"'::regclass", String.class))
            .isEqualTo("c");
    }

    // ── AC1 + AC5: confirming switches the address, revokes sessions, notifies the old address ─────────

    @Test
    void confirm_switchesEmailAndLoginLowercased_revokesEverySession_andMailsTheUndoLinkToTheOldAddress() {
        commit("INSERT INTO main.refresh_tokens (id, user_id, token_hash, expires_at, used) VALUES (990172, ?, 'h172', ?, false)",
            ME_ID, Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        emailChangeService.request(ME, "New.Me@Example.COM", PASSWORD);
        String token = confirmToken();
        testMailManager.clear();

        emailChangeService.confirm(token);

        User me = user(ME_ID);
        assertThat(me.getEmail()).isEqualTo(NEW);
        assertThat(me.getLogin()).isEqualTo(NEW);
        assertThat(userRepository.findOneByLogin("New.Me@Example.COM".toLowerCase())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT used FROM main.refresh_tokens WHERE id = 990172", Boolean.class))
            .isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?", Timestamp.class, ME_ID)).isNotNull();
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGED_NOTICE)).containsExactly(ME);
        assertThat(onlyEnvelope(EmailTemplate.EMAIL_CHANGED_NOTICE).data())
            .containsEntry("newEmailMasked", "n…@example.com")
            .containsKey("undoUrl");
        assertThat(testMailManager.getEnvelopes().values())
            .noneMatch(e -> e.emailTemplate() == EmailTemplate.PROFILE_CHANGE);

        assertThatThrownBy(() -> emailChangeService.confirm(token)).isInstanceOf(EmailChangeLinkInvalidException.class);
    }

    @Test
    void confirm_isRefusedAndEndsTheRequest_whenTheAddressWasTakenMeanwhile() {
        emailChangeService.request(ME, NEW, PASSWORD);
        String token = confirmToken();
        commit("UPDATE main.\"user\" SET email = ?, login = ? WHERE email = ?", NEW, NEW, OTHER);

        assertThatThrownBy(() -> emailChangeService.confirm(token)).isInstanceOf(EmailChangeLinkInvalidException.class);

        assertThat(user(ME_ID).getEmail()).isEqualTo(ME);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.email_change_requests WHERE superseded_at IS NOT NULL", Integer.class)).isOne();
    }

    @Test
    void confirm_isRefused_whenTheAccountIsLockedOrItsEmailChangedSince() {
        emailChangeService.request(ME, NEW, PASSWORD);
        String token = confirmToken();
        commit("UPDATE main.\"user\" SET locked = true WHERE id = ?", ME_ID);
        assertThatThrownBy(() -> emailChangeService.confirm(token)).isInstanceOf(EmailChangeLinkInvalidException.class);

        commit("UPDATE main.\"user\" SET locked = false WHERE id = ?", ME_ID);
        clearRateLimits();
        emailChangeService.request(ME, NEW, PASSWORD);
        String second = confirmToken();
        commit("UPDATE main.\"user\" SET email = 'moved@example.com', login = 'moved@example.com' WHERE id = ?", ME_ID);
        assertThatThrownBy(() -> emailChangeService.confirm(second)).isInstanceOf(EmailChangeLinkInvalidException.class);
        assertThat(user(ME_ID).getEmail()).isEqualTo("moved@example.com");
    }

    // ── AC6: undo restores the account and locks out whoever changed it ───────────────────────────────

    @Test
    void undo_restoresTheAddress_killsTheOldPassword_andMailsAResetToTheRestoredAddress() {
        String undo = confirmChangeTo(NEW);
        testMailManager.clear();

        EmailChangeService.UndoOutcome outcome = emailChangeService.undo(undo);

        User me = user(ME_ID);
        assertThat(outcome.passwordResetSent()).isTrue();
        assertThat(me.getEmail()).isEqualTo(ME);
        assertThat(me.getLogin()).isEqualTo(ME);
        assertThat(passwordEncoder.matches(PASSWORD, me.getPassword())).isFalse();
        assertThat(me.getResetKey()).isNotBlank();
        assertThat(sentTo(EmailTemplate.PASSWORD_RESET)).containsExactly(ME);
        assertThat(onlyEnvelope(EmailTemplate.PASSWORD_RESET).data()).containsEntry("resetKey", me.getResetKey());
        // V2: the address the account left is told, in case the undo did not come from its owner.
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGED_NOTICE)).containsExactly(NEW);
        assertThat(onlyEnvelope(EmailTemplate.EMAIL_CHANGED_NOTICE).data())
            .containsEntry("revertedAway", true)
            .containsEntry("oldEmailMasked", EmailChangeService.mask(ME))
            .doesNotContainKeys("undoUrl", "resetKey");

        assertThatThrownBy(() -> emailChangeService.undo(undo)).isInstanceOf(EmailChangeLinkInvalidException.class);
    }

    @Test
    void undo_onASuspendedAccount_restoresTheAddress_butLeavesTheSuspensionAndPasswordAlone() {
        String undo = confirmChangeTo(NEW);
        commit("UPDATE main.\"user\" SET locked = true WHERE id = ?", ME_ID);
        testMailManager.clear();

        EmailChangeService.UndoOutcome outcome = emailChangeService.undo(undo);

        User me = user(ME_ID);
        assertThat(outcome.passwordResetSent()).isFalse();
        assertThat(me.getEmail()).isEqualTo(ME);
        assertThat(me.isLocked()).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, me.getPassword())).isTrue();
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGED_NOTICE)).containsExactlyInAnyOrder(ME, NEW);
        assertThat(envelopeTo(EmailTemplate.EMAIL_CHANGED_NOTICE, ME).data()).containsEntry("undone", true);
        assertThat(envelopeTo(EmailTemplate.EMAIL_CHANGED_NOTICE, NEW).data()).containsEntry("revertedAway", true);
    }

    @Test
    void anExpiredUndoLink_isRefused() {
        String undo = confirmChangeTo(NEW);
        commit("UPDATE main.email_change_requests SET undo_expires_at = now() - interval '1 minute'");

        assertThatThrownBy(() -> emailChangeService.undo(undo)).isInstanceOf(EmailChangeLinkInvalidException.class);
        assertThat(user(ME_ID).getEmail()).isEqualTo(NEW);
    }

    // ── AC7: one change per undo window, and the old address stays reserved ───────────────────────────

    @Test
    void duringTheUndoWindow_aNewRequestIsRefusedWithItsEnd_andAfterAnUndoItIsAllowedAgain() {
        String undo = confirmChangeTo(NEW);
        signInAs(NEW, ME_ID);

        assertThatThrownBy(() -> emailChangeService.request(NEW, "third@example.com", PASSWORD))
            .isInstanceOfSatisfying(RecentEmailChangeException.class, e -> {
                assertThat(e.getErrorKey()).isEqualTo(RecentEmailChangeException.EMAIL_CHANGE_KEY);
                assertThat(e.getUntil()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
            });

        emailChangeService.undo(undo);
        commit("UPDATE main.\"user\" SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), ME_ID);
        signInAs(ME, ME_ID);
        clearRateLimits();
        emailChangeService.request(ME, "third@example.com", PASSWORD);
        assertThat(openRequests()).isOne();
    }

    @Test
    void theOldAddress_isReservedForTheUndoWindow_againstAnotherAccountsChangeAndARegistration() {
        confirmChangeTo(NEW);
        testMailManager.clear();
        signInAs(OTHER, otherId());

        emailChangeService.request(OTHER, "ME@yahoo.com", PASSWORD);

        assertThat(openRequests()).isZero();
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE)).containsExactly(ME);
        assertThat(onlyEnvelope(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE).data()).containsEntry("reserved", true);

        testMailManager.clear();
        clearRateLimits();
        facade.registerAccount(registration(ME));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM main.\"user\" WHERE lower(email) = ?",
            Integer.class, ME)).isZero();
        assertThat(sentTo(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE)).containsExactly(ME);

        commit("UPDATE main.email_change_requests SET undo_expires_at = now() - interval '1 minute'");
        clearRateLimits();
        emailChangeService.request(OTHER, ME, PASSWORD);
        assertThat(openRequests()).isOne();
    }

    // ── AC8: erasure is held while undo is possible, and cleans up after ──────────────────────────────

    @Test
    void erasure_isHeldDuringTheUndoWindow_andDeletesTheRowsOnceItRuns() {
        confirmChangeTo(NEW);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> gdprRequestService.requestErasure(ME_ID)))
            .isInstanceOfSatisfying(RecentEmailChangeException.class,
                e -> assertThat(e.getErrorKey()).isEqualTo(RecentEmailChangeException.ERASURE_KEY));

        commit("UPDATE main.email_change_requests SET undo_expires_at = now() - interval '1 minute'");
        transactionTemplate.executeWithoutResult(s -> gdprRequestService.requestErasure(ME_ID));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.email_change_requests WHERE user_id = ?", Integer.class, ME_ID)).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** Requests and confirms a change of ME to {@code target}; returns the undo token. */
    private String confirmChangeTo(String target) {
        emailChangeService.request(ME, target, PASSWORD);
        emailChangeService.confirm(confirmToken());
        String undoUrl = (String) onlyEnvelope(EmailTemplate.EMAIL_CHANGED_NOTICE).data().get("undoUrl");
        return undoUrl.substring(undoUrl.indexOf("token=") + 6);
    }

    private String confirmToken() {
        String url = (String) onlyEnvelope(EmailTemplate.EMAIL_CHANGE).data().get("confirmUrl");
        assertThat(url).contains("/#/email-change/confirm?token=");
        return url.substring(url.indexOf("token=") + 6);
    }

    private Envelope onlyEnvelope(EmailTemplate template) {
        List<Envelope> matching = testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template).toList();
        assertThat(matching).as("envelopes of " + template).isNotEmpty();
        return matching.get(matching.size() - 1);
    }

    private Envelope envelopeTo(EmailTemplate template, String address) {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .filter(e -> e.recipients().stream().anyMatch(r -> address.equals(r.getEmail())))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no " + template + " envelope to " + address));
    }

    private List<String> sentTo(EmailTemplate template) {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .flatMap(e -> e.recipients().stream().map(Recipient::getEmail))
            .toList();
    }

    private int openRequests() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM main.email_change_requests "
            + "WHERE confirmed_at IS NULL AND superseded_at IS NULL", Integer.class);
    }

    private User user(long id) {
        return userRepository.findOneById(id).orElseThrow();
    }

    private long otherId() {
        return jdbcTemplate.queryForObject("SELECT id FROM main.\"user\" WHERE email = ?", Long.class, OTHER);
    }

    private void commit(String sql, Object... args) {
        transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(sql, args));
    }

    @SuppressWarnings("unchecked")
    private void clearRateLimits() {
        ((Map<String, ?>) ReflectionTestUtils.getField(rateLimitingService, "buckets")).clear();
    }

    @SuppressWarnings("unchecked")
    private void clearRequestBucketOnly() {
        ((Map<String, ?>) ReflectionTestUtils.getField(rateLimitingService, "buckets"))
            .keySet().removeIf(k -> k.startsWith(EmailChangeService.REQUEST_LIMIT_KEY + ":"));
    }

    private static void signInAs(String login, long userId) {
        Principal principal = new Principal.Builder()
            .username(login)
            .password("N/A")
            .authorities(Set.of(new Authority("ROLE_USER")))
            .businessId(String.valueOf(userId))
            .enabled(true)
            .otpEnabled(false)
            .build();
        var token = new UsernamePasswordAuthenticationToken(principal.getUsername(), null, principal.getAuthorities());
        token.setDetails(principal);
        SecurityContextHolder.setContext(new SecurityContextImpl(token));
    }

    private static UserDto registration(String email) {
        UserDto dto = new UserDto();
        dto.setEmail(email);
        dto.setPassword(PASSWORD);
        dto.setFirstName("Someone");
        dto.setLastName("Else");
        dto.setLangKey("en");
        dto.setDob(LocalDate.of(1990, 1, 15));
        return dto;
    }
}
