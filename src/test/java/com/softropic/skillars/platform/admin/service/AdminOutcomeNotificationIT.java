package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.messaging.contract.ModerationFailureEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import com.softropic.skillars.utils.TestMailManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6 (feat/admin-outcome-notifications), end to end against the real database and the real event
 * wiring: admin enforcement outcomes reach the coach (outbox email + inbox row, atomically with the
 * decision), a CSAM safety suspension takes the coach's marketplace profile down (idempotent, coach not
 * notified, REQUESTED bookings cancelled), a moderation-provider failure is stamped onto the open hold
 * alert, the recipient lookup is case-insensitive, and the admin_action_log CHECK matches the enum.
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim — no extra mocks or properties.
 */
class AdminOutcomeNotificationIT extends AbstractIntegrationTest {

    private static final long COACH_USER_ID = 9176_000_010L;
    private static final long ADMIN_ID = 9176_000_100L;
    private static final long PARENT_ID = 9176_000_001L;
    private static final long CASE_A_ID = 9176_000_020L;
    private static final long CASE_B_ID = 9176_000_021L;
    private static final String COACH_EMAIL = "Coach.Outcome9176@Skillars-Test.com";
    private static final String MESSAGE_ALERT_REF = "9176000777";
    /** 60 chars: User.password is @Size(60,60), validated when the CSAM account listener updates the user. */
    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired private AdminCoachEnforcementService enforcementService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TestMailManager testMailManager;
    @Autowired private NotificationRecipientLookup recipientLookup;
    @Autowired private AppealService appealService;

    private UUID coachId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        coachId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        testMailManager.clear();
        transactionTemplate.execute(status -> {
            insertAuthority(9176, "ROLE_COACH");
            insertUser(COACH_USER_ID, COACH_EMAIL, "COACH");
            grantAuthority(COACH_USER_ID, "ROLE_COACH");
            insertUser(ADMIN_ID, "admin.outcome9176@skillars-test.com", "ADMIN");
            insertUser(PARENT_ID, "parent.outcome9176@skillars-test.com", "PARENT");
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles (id, user_id, display_name, bio, city, languages, "
                    + "canonical_timezone, status) VALUES (?, ?, 'Outcome Coach', 'Bio', 'Paris', "
                    + "ARRAY['French']::varchar[], 'Europe/Paris', 'ACTIVE')",
                coachId, COACH_USER_ID);
            // One Instant for start and end (chk_bkg_end_after_start).
            Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, coach_id, parent_id, player_id, status, requested_start_time, "
                    + "requested_end_time, version, created_at, updated_at, canonical_timezone) "
                    + "VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, 0, now(), now(), 'Europe/Paris')",
                bookingId, coachId, PARENT_ID, 9176_000_002L,
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM admin.appeals WHERE subject_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM admin.admin_action_log WHERE reference_id = ?", coachId.toString());
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id IN (?, ?)",
                coachId.toString(), MESSAGE_ALERT_REF);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'replica'");
            jdbcTemplate.update("DELETE FROM payment.parent_credit_ledger WHERE reference_id = ?", bookingId);
            jdbcTemplate.execute("SET SESSION session_replication_role = 'origin'");
            jdbcTemplate.update("DELETE FROM booking.bookings WHERE id = ?", bookingId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachId);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?, ?, ?)",
                COACH_USER_ID, ADMIN_ID, PARENT_ID, CASE_A_ID, CASE_B_ID);
            // user_notifications cascades from main."user".
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?, ?, ?)",
                COACH_USER_ID, ADMIN_ID, PARENT_ID, CASE_A_ID, CASE_B_ID);
            jdbcTemplate.execute("DELETE FROM main.authority WHERE id = 9176");
            return null;
        });
    }

    // ── issue 1: enforcement outcomes reach the coach ──────────────────────────

    @Test
    void adminSuspendWithNotify_thenReinstate_emailsAndInboxesTheCoach() {
        enforcementService.suspendCoach(coachId, "Repeated no-shows", true, ADMIN_ID, true);

        assertThat(sentTo(EmailTemplate.COACH_SUSPENDED))
            .singleElement()
            .satisfies(e -> assertThat(e.data()).containsEntry("reason", "Repeated no-shows")
                .containsEntry("coachDisplayName", "Outcome Coach"));
        assertThat(inbox(COACH_USER_ID)).containsExactly("COACH_SUSPENDED|MODERATION|/profile");

        enforcementService.reinstateCoach(coachId, "Appeal accepted", ADMIN_ID);

        assertThat(sentTo(EmailTemplate.COACH_REINSTATED)).hasSize(1);
        assertThat(inbox(COACH_USER_ID))
            .containsExactlyInAnyOrder("COACH_SUSPENDED|MODERATION|/profile", "COACH_REINSTATED|MODERATION|/dashboard");
    }

    @Test
    void adminSuspendWithoutNotify_sendsTheCoachNothing() {
        enforcementService.suspendCoach(coachId, "Quiet suspension", false, ADMIN_ID, true);

        assertThat(sentTo(EmailTemplate.COACH_SUSPENDED)).isEmpty();
        assertThat(inbox(COACH_USER_ID)).isEmpty();
    }

    // ── issue 6: CSAM safety hold takes the marketplace profile down ──────────

    @Test
    void csamSuspension_ofACoachOwnedVideo_suspendsTheProfile_cancelsRequests_idempotently_withoutNotifying() {
        UUID videoId = UUID.randomUUID();
        // Published outside any transaction, exactly as ModerationOrchestrationService does.
        eventPublisher.publishEvent(new AccountSuspensionRequestedEvent(coachId.toString(), videoId));

        Map<String, Object> profile = jdbcTemplate.queryForMap(
            "SELECT status, status_changed_at FROM marketplace.coach_profiles WHERE id = ?", coachId);
        assertThat(profile.get("status")).isEqualTo("SUSPENDED");
        assertThat(jdbcTemplate.queryForMap(
            "SELECT status, cancel_reason FROM booking.bookings WHERE id = ?", bookingId))
            .containsEntry("status", "CANCELLED")
            // C2: neutral on the row — the parent's GDPR export carries it and must not reveal the hold.
            .containsEntry("cancel_reason", "COACH_UNAVAILABLE");

        // Re-delivery is a no-op: the suspension timestamp (the appealable enforcement action) is unchanged.
        eventPublisher.publishEvent(new AccountSuspensionRequestedEvent(coachId.toString(), videoId));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status_changed_at FROM marketplace.coach_profiles WHERE id = ?", Timestamp.class, coachId))
            .isEqualTo(profile.get("status_changed_at"));

        // A safety action never tips off the account it acts against; it is logged once, with no admin.
        assertThat(sentTo(EmailTemplate.COACH_SUSPENDED)).isEmpty();
        assertThat(inbox(COACH_USER_ID)).isEmpty();
        assertThat(jdbcTemplate.queryForList(
            "SELECT action_type || '|' || admin_id FROM admin.admin_action_log "
                + "WHERE reference_id = ?", String.class, coachId.toString()))
            .containsExactly("COACH_SAFETY_HOLD|0");

        // ... and the coach cannot appeal it through the normal appeal flow.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> appealService.submit(
                COACH_USER_ID, com.softropic.skillars.platform.admin.contract.AppealSubjectType.COACH_SUSPENSION,
                coachId, "please reinstate"))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("appeals.notAppealable"));
    }

    @Test
    void adminSuspension_remainsAppealable() {
        enforcementService.suspendCoach(coachId, "Repeated no-shows", false, ADMIN_ID, true);

        var appeal = appealService.submit(COACH_USER_ID,
            com.softropic.skillars.platform.admin.contract.AppealSubjectType.COACH_SUSPENSION, coachId, "unfair");

        assertThat(appeal.subjectId()).isEqualTo(coachId);
    }

    @Test
    void csamSuspension_ofAPlayerOwnedVideo_leavesCoachProfilesAlone() {
        // No account resolves for this PlayerProfile id, so security's AccountSuspensionEventListener throws
        // (#321: the publisher turns that into a "suspension FAILED" alert). This listener runs first and
        // ignores a non-UUID owner either way.
        try {
            eventPublisher.publishEvent(new AccountSuspensionRequestedEvent("9176000002", UUID.randomUUID()));
        } catch (RuntimeException expected) {
            // the account-side listener's "no account" failure — not under test here
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM marketplace.coach_profiles WHERE id = ?", String.class, coachId))
            .isEqualTo("ACTIVE");
    }

    // ── issue 2: moderation provider failure marks the open hold alert ────────

    @Test
    void moderationFailure_restampsTheOpenHoldAlertReason() {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO admin.admin_alerts (alert_id, type, reference_id, reference_type, status, reason, created_at) "
                + "VALUES (?, 'MODERATION_UNRESOLVED', ?, 'MESSAGE', 'OPEN', 'MODERATION_UNCERTAIN', now())",
            UUID.randomUUID(), MESSAGE_ALERT_REF));

        eventPublisher.publishEvent(new ModerationFailureEvent(Long.valueOf(MESSAGE_ALERT_REF), 1L, "timeout"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT reason FROM admin.admin_alerts WHERE reference_id = ? AND type = 'MODERATION_UNRESOLVED'",
            String.class, MESSAGE_ALERT_REF))
            .isEqualTo("MODERATION_PROVIDER_FAILED");
    }

    // ── issue 3: case-insensitive recipient lookup ─────────────────────────────

    @Test
    void recipientLookup_matchesAnAddressRegardlessOfCase_butNeverPicksBetweenTwoCaseVariants() {
        assertThat(recipientLookup.findByEmail(COACH_EMAIL.toLowerCase(), true))
            .hasValueSatisfying(r -> {
                assertThat(r.userId()).isEqualTo(COACH_USER_ID);
                assertThat(r.coach()).isTrue();
            });

        transactionTemplate.execute(status -> {
            insertUser(CASE_A_ID, "twin.outcome9176@skillars-test.com", "PARENT");
            insertUser(CASE_B_ID, "Twin.Outcome9176@skillars-test.com", "PARENT");
            return null;
        });
        // An exact match still wins over the case-folded fallback ...
        assertThat(recipientLookup.findByEmail("Twin.Outcome9176@skillars-test.com", false))
            .hasValueSatisfying(r -> assertThat(r.userId()).isEqualTo(CASE_B_ID));
        // ... and an ambiguous folded match resolves to nobody rather than to the wrong inbox.
        assertThat(recipientLookup.findByEmail("TWIN.OUTCOME9176@SKILLARS-TEST.COM", false)).isEmpty();
    }

    // ── issue 4: admin_action_log CHECK parity ─────────────────────────────────

    @Test
    void adminActionLogCheck_allowsExactlyTheAdminActionTypeValues() {
        String definition = jdbcTemplate.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'admin.admin_action_log'::regclass AND conname = 'admin_action_log_action_type_check'",
            String.class);
        Set<String> allowed = new TreeSet<>();
        Matcher m = Pattern.compile("'([A-Z0-9_]+)'::character varying").matcher(definition);
        while (m.find()) {
            allowed.add(m.group(1));
        }
        Set<String> enumNames = Arrays.stream(AdminActionType.values()).map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(allowed).as("add a Flyway migration widening admin_action_log_action_type_check (see V183)")
            .isEqualTo(enumNames);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<Envelope> sentTo(EmailTemplate template) {
        return testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == template)
            .filter(e -> e.recipients().stream().anyMatch(r -> COACH_EMAIL.equals(r.getEmail())))
            .toList();
    }

    private List<String> inbox(long userId) {
        return jdbcTemplate.queryForList(
            "SELECT type || '|' || category || '|' || deep_link FROM main.user_notifications "
                + "WHERE recipient_user_id = ?", String.class, userId);
    }

    private void insertAuthority(int id, String name) {
        jdbcTemplate.update("INSERT INTO main.authority (id, name, status, created_by, created_date) "
            + "VALUES (?, ?, 'ACTIVE', 'system', ?) ON CONFLICT (name) DO NOTHING", id, name, Timestamp.from(Instant.now()));
    }

    private void grantAuthority(long userId, String roleName) {
        jdbcTemplate.update("INSERT INTO main.user_authority (user_id, authority_id) "
            + "VALUES (?, (SELECT id FROM main.authority WHERE name = ?)) ON CONFLICT DO NOTHING", userId, roleName);
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                + "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, "
                + "skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, "
                + "true, false, ?, 'EMAIL', ?, false, ?, 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email, "9176" + (id % 1000000), email, PASSWORD_HASH, role);
    }
}
