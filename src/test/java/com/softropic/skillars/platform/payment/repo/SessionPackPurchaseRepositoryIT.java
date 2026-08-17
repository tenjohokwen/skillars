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

import static org.assertj.core.api.Assertions.assertThat;

// AC1 (skillars-deferred-27): PackSessionServiceParityTest mocks findActivePacks to already return
// ordered results and never exercises the real ORDER BY p.createdAt ASC clause
// (SessionPackPurchaseRepository.java:37-46) against a real database. This IT proves that ordering.
class SessionPackPurchaseRepositoryIT extends AbstractIntegrationTest {

    // Fixture id range 9620000001-9620000002, claimed in docs/testing/test-data-isolation.md.
    private static final long COACH_USER_ID = 9_620_000_001L;
    private static final long PLAYER_ID = 9_620_000_002L;

    @Autowired private SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Autowired private CoachProfileRepository coachProfileRepository;
    @Autowired private SessionPackTierRepository sessionPackTierRepository;

    @Test
    void findActivePacks_returnsOldestCreatedAtFirst() {
        long coachUserId = COACH_USER_ID;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, login, login_id_type, password_hash, activated, "
                    + "first_name, last_name, gender, dob, email) "
                    + "VALUES (?, ?, 'EMAIL', '{noop}test', true, 'Test', 'Coach', 'MALE', ?, ?)",
                coachUserId, "coach" + coachUserId + "@test.com",
                Date.valueOf(LocalDate.of(1990, 1, 1)), "coach" + coachUserId + "@test.com");
            return null;
        });

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
}
