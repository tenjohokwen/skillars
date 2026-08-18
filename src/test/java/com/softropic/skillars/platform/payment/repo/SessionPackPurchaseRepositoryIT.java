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

// AC1 (skillars-deferred-27): PackSessionServiceParityTest mocks findActivePacks to already return
// ordered results and never exercises the real ORDER BY p.createdAt ASC clause
// (SessionPackPurchaseRepository.java:37-46) against a real database. This IT proves that ordering.
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
    void findActivePacks_returnsOldestCreatedAtFirst() {
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

        // Insertion order is deliberately the REVERSE of the expected result order. Each save() commits
        // its own transaction (SimpleJpaRepository.save is @Transactional, this class has no ambient
        // transaction), so physical heap order is newer-then-older. A seq scan over these two rows
        // therefore returns newer-first, and only ORDER BY p.createdAt ASC can produce the asserted
        // older-first result — drop the ORDER BY from the query and this test fails, which is the whole
        // point of it. Do not "tidy" this by seeding the older pack first.
        SessionPackPurchase newerPack = new SessionPackPurchase();
        newerPack.setParentId(1L);
        newerPack.setPlayerId(playerId);
        newerPack.setCoachId(coach.getId());
        newerPack.setPackTierId(tier.getPackTierId());
        newerPack.setPricePerSession(new BigDecimal("10.00"));
        newerPack.setRemainingSessions(5);
        newerPack.setExpiresAt(now.plusSeconds(86_400));
        newerPack.setCreatedAt(now);
        sessionPackPurchaseRepository.save(newerPack);

        SessionPackPurchase olderPack = new SessionPackPurchase();
        olderPack.setParentId(1L);
        olderPack.setPlayerId(playerId);
        olderPack.setCoachId(coach.getId());
        olderPack.setPackTierId(tier.getPackTierId());
        olderPack.setPricePerSession(new BigDecimal("10.00"));
        olderPack.setRemainingSessions(5);
        olderPack.setExpiresAt(now.plusSeconds(86_400));
        olderPack.setCreatedAt(now.minus(Duration.ofDays(2)));
        sessionPackPurchaseRepository.save(olderPack);

        // Guard the premise: if @PrePersist or a DB default ever overwrote the explicit createdAt values,
        // the ordering assertion below would pass or fail for the wrong reason.
        assertThat(olderPack.getCreatedAt()).isBefore(newerPack.getCreatedAt());

        List<SessionPackPurchase> activePacks =
            sessionPackPurchaseRepository.findActivePacks(playerId, coach.getId(), Instant.now());

        assertThat(activePacks).hasSize(2);
        assertThat(activePacks.get(0).getPurchaseId()).isEqualTo(olderPack.getPurchaseId());
        assertThat(activePacks.get(1).getPurchaseId()).isEqualTo(newerPack.getPurchaseId());
    }

    // AC5 (skillars-deferred-29): findActivePacks_returnsOldestCreatedAtFirst above only proves
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
