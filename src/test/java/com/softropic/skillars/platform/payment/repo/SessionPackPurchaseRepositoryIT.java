package com.softropic.skillars.platform.payment.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// AC1 (skillars-deferred-27, reordered by skillars-deferred-65 AC1): PackSessionServiceParityTest
// mocks findActivePacks to already return ordered results and never exercises the real
// ORDER BY p.expiresAt ASC, p.createdAt DESC clause (SessionPackPurchaseRepository.java:36-45)
// against a real database. This IT proves that ordering.
class SessionPackPurchaseRepositoryIT extends AbstractIntegrationTest {

    // Fixture id range 9620000001-9620000004, claimed in docs/testing/test-data-isolation.md.
    private static final long COACH_USER_ID = 9_620_000_001L;
    private static final long PLAYER_ID = 9_620_000_002L;
    private static final long OTHER_PLAYER_ID = 9_620_000_003L;
    private static final long OTHER_COACH_USER_ID = 9_620_000_004L;

    @Autowired private SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Autowired private CoachProfileRepository coachProfileRepository;
    @Autowired private SessionPackTierRepository sessionPackTierRepository;

    private void seedCoachUser(long coachUserId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, "
                    + "first_name, last_name, gender, dob, email) "
                    + "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Test', 'Coach', 'MALE', ?, ?) "
                    + "ON CONFLICT (id) DO NOTHING",
                coachUserId, "coach" + coachUserId + "@test.com",
                Date.valueOf(LocalDate.of(1990, 1, 1)), "coach" + coachUserId + "@test.com");
            return null;
        });
    }

    @Test
    void findActivePacks_returnsSoonestExpiringFirst() {
        long coachUserId = COACH_USER_ID;
        seedCoachUser(coachUserId);

        CoachProfile coach = new CoachProfile();
        coach.setUserId(coachUserId);
        coach.setDisplayName("Coach Ordering Test");
        coach.setCanonicalTimezone("Europe/Berlin");
        coach = coachProfileRepository.save(coach);

        SessionPackTier tier = new SessionPackTier();
        tier.setCoachId(coach.getId());
        tier.setLabel("Standard Pack");
        tier.setSessionCount(10);
        tier.setTotalPrice(new BigDecimal("100.00"));
        tier.setPricePerSession(new BigDecimal("10.00"));
        tier = sessionPackTierRepository.save(tier);

        Long playerId = PLAYER_ID;
        Instant now = Instant.now();

        // createdAt and expiresAt are deliberately INVERTED relative to each other: the pack created
        // first has the LATER expiresAt, and the pack created second has the SOONER expiresAt. This
        // means ORDER BY p.createdAt ASC (the old clause) and ORDER BY p.expiresAt ASC (the new one)
        // disagree on the result order — only the new clause can produce the asserted
        // soonest-expiring-first result. Do not "tidy" this by aligning the two timestamps.
        SessionPackPurchase laterExpiringPack = new SessionPackPurchase();
        laterExpiringPack.setParentId(1L);
        laterExpiringPack.setPlayerId(playerId);
        laterExpiringPack.setCoachId(coach.getId());
        laterExpiringPack.setPackTierId(tier.getPackTierId());
        laterExpiringPack.setPricePerSession(new BigDecimal("10.00"));
        laterExpiringPack.setRemainingSessions(5);
        laterExpiringPack.setExpiresAt(now.plusSeconds(86_400 * 10));
        laterExpiringPack.setCreatedAt(now.minus(Duration.ofDays(2)));
        sessionPackPurchaseRepository.save(laterExpiringPack);

        SessionPackPurchase soonerExpiringPack = new SessionPackPurchase();
        soonerExpiringPack.setParentId(1L);
        soonerExpiringPack.setPlayerId(playerId);
        soonerExpiringPack.setCoachId(coach.getId());
        soonerExpiringPack.setPackTierId(tier.getPackTierId());
        soonerExpiringPack.setPricePerSession(new BigDecimal("10.00"));
        soonerExpiringPack.setRemainingSessions(5);
        soonerExpiringPack.setExpiresAt(now.plusSeconds(86_400));
        soonerExpiringPack.setCreatedAt(now);
        sessionPackPurchaseRepository.save(soonerExpiringPack);

        // Guard the premise: if @PrePersist or a DB default ever overwrote the explicit createdAt
        // values, the ordering assertion below would pass or fail for the wrong reason.
        assertThat(laterExpiringPack.getCreatedAt()).isBefore(soonerExpiringPack.getCreatedAt());
        assertThat(soonerExpiringPack.getExpiresAt()).isBefore(laterExpiringPack.getExpiresAt());

        List<SessionPackPurchase> activePacks =
            sessionPackPurchaseRepository.findActivePacks(playerId, coach.getId(), Instant.now());

        assertThat(activePacks).hasSize(2);
        assertThat(activePacks.get(0).getPurchaseId()).isEqualTo(soonerExpiringPack.getPurchaseId());
        assertThat(activePacks.get(1).getPurchaseId()).isEqualTo(laterExpiringPack.getPurchaseId());
    }

    // Story-review finding (skillars-deferred-65 AC1): two active packs can share an identical
    // expiresAt with no ordering guarantee among tied rows without a secondary sort key. Proves the
    // p.createdAt DESC secondary key — the primary ordering test above never exercises it, since its
    // two rows have distinct expiresAt values.
    @Test
    void findActivePacks_tiedExpiresAt_returnsNewestCreatedFirst() {
        long coachUserId = COACH_USER_ID;
        seedCoachUser(coachUserId);

        CoachProfile coach = new CoachProfile();
        coach.setUserId(coachUserId);
        coach.setDisplayName("Coach Tiebreak Test");
        coach.setCanonicalTimezone("Europe/Berlin");
        coach = coachProfileRepository.save(coach);

        SessionPackTier tier = new SessionPackTier();
        tier.setCoachId(coach.getId());
        tier.setLabel("Standard Pack");
        tier.setSessionCount(10);
        tier.setTotalPrice(new BigDecimal("100.00"));
        tier.setPricePerSession(new BigDecimal("10.00"));
        tier = sessionPackTierRepository.save(tier);

        Long playerId = PLAYER_ID;
        Instant now = Instant.now();
        Instant sharedExpiresAt = now.plusSeconds(86_400);

        SessionPackPurchase olderCreatedPack = new SessionPackPurchase();
        olderCreatedPack.setParentId(1L);
        olderCreatedPack.setPlayerId(playerId);
        olderCreatedPack.setCoachId(coach.getId());
        olderCreatedPack.setPackTierId(tier.getPackTierId());
        olderCreatedPack.setPricePerSession(new BigDecimal("10.00"));
        olderCreatedPack.setRemainingSessions(5);
        olderCreatedPack.setExpiresAt(sharedExpiresAt);
        olderCreatedPack.setCreatedAt(now.minus(Duration.ofDays(2)));
        sessionPackPurchaseRepository.save(olderCreatedPack);

        SessionPackPurchase newerCreatedPack = new SessionPackPurchase();
        newerCreatedPack.setParentId(1L);
        newerCreatedPack.setPlayerId(playerId);
        newerCreatedPack.setCoachId(coach.getId());
        newerCreatedPack.setPackTierId(tier.getPackTierId());
        newerCreatedPack.setPricePerSession(new BigDecimal("10.00"));
        newerCreatedPack.setRemainingSessions(5);
        newerCreatedPack.setExpiresAt(sharedExpiresAt);
        newerCreatedPack.setCreatedAt(now);
        sessionPackPurchaseRepository.save(newerCreatedPack);

        assertThat(olderCreatedPack.getExpiresAt()).isEqualTo(newerCreatedPack.getExpiresAt());
        assertThat(olderCreatedPack.getCreatedAt()).isBefore(newerCreatedPack.getCreatedAt());

        List<SessionPackPurchase> activePacks =
            sessionPackPurchaseRepository.findActivePacks(playerId, coach.getId(), Instant.now());

        assertThat(activePacks).hasSize(2);
        assertThat(activePacks.get(0).getPurchaseId()).isEqualTo(newerCreatedPack.getPurchaseId());
        assertThat(activePacks.get(1).getPurchaseId()).isEqualTo(olderCreatedPack.getPurchaseId());
    }

    // AC5 (skillars-deferred-29): findActivePacks_returnsSoonestExpiringFirst above only proves
    // ordering — both its rows sit at comfortable mid-range values on every other predicate, so
    // remainingSessions > 0, expiresAt > :now, the pausedUntil OR-clause, and the playerId match could
    // all be deleted from the JPQL without failing it. This test seeds one negative row per predicate
    // (plus a control row) and proves each one is actually excluded.
    @Test
    void findActivePacks_excludesExhaustedExpiredPausedOtherPlayerAndOtherCoachPacks() {
        long coachUserId = COACH_USER_ID;
        seedCoachUser(coachUserId);

        CoachProfile coach = new CoachProfile();
        coach.setUserId(coachUserId);
        coach.setDisplayName("Coach Boundary Test");
        coach.setCanonicalTimezone("Europe/Berlin");
        coach = coachProfileRepository.save(coach);

        SessionPackTier tier = new SessionPackTier();
        tier.setCoachId(coach.getId());
        tier.setLabel("Standard Pack");
        tier.setSessionCount(10);
        tier.setTotalPrice(new BigDecimal("100.00"));
        tier.setPricePerSession(new BigDecimal("10.00"));
        tier = sessionPackTierRepository.save(tier);

        Long playerId = PLAYER_ID;
        Instant now = Instant.now();

        SessionPackPurchase controlPack = newPack(playerId, coach.getId(), tier.getPackTierId());
        controlPack.setRemainingSessions(5);
        controlPack.setExpiresAt(now.plusSeconds(86_400));
        controlPack.setPausedUntil(null);
        controlPack = sessionPackPurchaseRepository.save(controlPack);

        SessionPackPurchase exhaustedPack = newPack(playerId, coach.getId(), tier.getPackTierId());
        exhaustedPack.setRemainingSessions(0);
        exhaustedPack.setExpiresAt(now.plusSeconds(86_400));
        exhaustedPack.setPausedUntil(null);
        sessionPackPurchaseRepository.save(exhaustedPack);

        SessionPackPurchase expiredPack = newPack(playerId, coach.getId(), tier.getPackTierId());
        expiredPack.setRemainingSessions(5);
        expiredPack.setExpiresAt(now.minusSeconds(3_600));
        expiredPack.setPausedUntil(null);
        sessionPackPurchaseRepository.save(expiredPack);

        SessionPackPurchase pausedPack = newPack(playerId, coach.getId(), tier.getPackTierId());
        pausedPack.setRemainingSessions(5);
        pausedPack.setExpiresAt(now.plusSeconds(86_400));
        pausedPack.setPausedUntil(now.plusSeconds(3_600));
        sessionPackPurchaseRepository.save(pausedPack);

        // pausedUntil is never cleared in src/main (only ever set forward, e.g.
        // PackSessionService.pausePack) — this is the sole row proving the `OR p.pausedUntil <= :now`
        // disjunct itself is exercised, not just the `pausedUntil IS NULL` half. Without it, an elapsed
        // pause could never make a pack spendable again and nothing here would notice.
        SessionPackPurchase elapsedPausePack = newPack(playerId, coach.getId(), tier.getPackTierId());
        elapsedPausePack.setRemainingSessions(5);
        elapsedPausePack.setExpiresAt(now.plusSeconds(86_400));
        elapsedPausePack.setPausedUntil(now.minusSeconds(3_600));
        elapsedPausePack = sessionPackPurchaseRepository.save(elapsedPausePack);

        SessionPackPurchase otherPlayerPack = newPack(OTHER_PLAYER_ID, coach.getId(), tier.getPackTierId());
        otherPlayerPack.setRemainingSessions(5);
        otherPlayerPack.setExpiresAt(now.plusSeconds(86_400));
        otherPlayerPack.setPausedUntil(null);
        sessionPackPurchaseRepository.save(otherPlayerPack);

        // Sits exactly on the expiresAt > :now boundary — relaxing > to >= would let this row through.
        SessionPackPurchase exactlyExpiredPack = newPack(playerId, coach.getId(), tier.getPackTierId());
        exactlyExpiredPack.setRemainingSessions(5);
        exactlyExpiredPack.setExpiresAt(now);
        exactlyExpiredPack.setPausedUntil(null);
        sessionPackPurchaseRepository.save(exactlyExpiredPack);

        // AC4 (skillars-deferred-30): every row above shares the SAME coach, so `p.coachId = :coachId`
        // is unproven — deleting it from the JPQL would still leave both existing tests in this class
        // green. This row is otherwise identical to controlPack (same player, active/unpaused/unexpired)
        // but belongs to a SECOND, unrelated coach. Reuses coach A's tier fixture deliberately — the
        // assertion only depends on the pack row's own coach_id column, not its tier's coach.
        seedCoachUser(OTHER_COACH_USER_ID);
        CoachProfile otherCoach = new CoachProfile();
        otherCoach.setUserId(OTHER_COACH_USER_ID);
        otherCoach.setDisplayName("Coach Boundary Test — Other Coach");
        otherCoach.setCanonicalTimezone("Europe/Berlin");
        otherCoach = coachProfileRepository.save(otherCoach);

        SessionPackPurchase otherCoachPack = newPack(playerId, otherCoach.getId(), tier.getPackTierId());
        otherCoachPack.setRemainingSessions(5);
        otherCoachPack.setExpiresAt(now.plusSeconds(86_400));
        otherCoachPack.setPausedUntil(null);
        sessionPackPurchaseRepository.save(otherCoachPack);

        // Pass the seeded `now`, not a fresh Instant.now(), so the query's `:now` boundary is fixed
        // relative to the seeded expiresAt/pausedUntil values rather than drifting later by however long
        // the seeding above took.
        List<SessionPackPurchase> activePacks =
            sessionPackPurchaseRepository.findActivePacks(playerId, coach.getId(), now);

        assertThat(activePacks)
            .extracting(SessionPackPurchase::getPurchaseId)
            .containsExactlyInAnyOrder(controlPack.getPurchaseId(), elapsedPausePack.getPurchaseId());
    }

    private SessionPackPurchase newPack(Long playerId, UUID coachId, UUID packTierId) {
        SessionPackPurchase pack = new SessionPackPurchase();
        pack.setParentId(1L);
        pack.setPlayerId(playerId);
        pack.setCoachId(coachId);
        pack.setPackTierId(packTierId);
        pack.setPricePerSession(new BigDecimal("10.00"));
        return pack;
    }
}
