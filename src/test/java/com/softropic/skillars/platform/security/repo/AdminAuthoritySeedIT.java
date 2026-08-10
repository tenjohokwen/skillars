package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.security.SecurityConstants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards V92's admin-authority seed (story skillars-uat-1, AC1).
 *
 * <p>Before V92, {@code main.authority} held only ROLE_COACH/ROLE_PARENT (V21) and ROLE_PLAYER
 * (V84) — no migration ever seeded ROLE_ADMIN or ROLE_LTD_ADMIN, so
 * {@link SecurityConstants#HAS_ADMIN_ROLE} gated 30+ endpoints against authorities that did not
 * exist. This pins the seed so a future migration cannot quietly remove it.
 */
class AdminAuthoritySeedIT extends AbstractIntegrationTest {

    @Autowired
    private AuthorityRepository authorityRepository;

    @Test
    @DisplayName("V92 seeds ROLE_ADMIN and ROLE_LTD_ADMIN, resolvable by the name @PreAuthorize uses")
    void adminAuthoritiesAreSeeded() {
        // Deliberately asserted through the repository lookup the production code path uses
        // (AdminBootstrapRunner and CoachRegistrationService both resolve authorities by name),
        // not a raw SELECT — a seed that exists but is unreachable by findOneByName is useless.
        assertThat(authorityRepository.findOneByName(SecurityConstants.ROLE_ADMIN))
            .as("ROLE_ADMIN must be seeded — HAS_ADMIN_ROLE is a string expression, "
                + "so a missing or misspelled row fails silently as a 403")
            .isPresent();
        assertThat(authorityRepository.findOneByName(SecurityConstants.ROLE_LTD_ADMIN))
            .as("ROLE_LTD_ADMIN must be seeded — HAS_ADMIN_ROLE accepts it too")
            .isPresent();
    }

    @Test
    @DisplayName("the seed statement is re-runnable: ON CONFLICT (name) absorbs a pre-existing row")
    void seedStatementIsIdempotent() {
        List<String> before = adminAuthorityNames();
        assertThat(before).containsExactlyInAnyOrder(
            SecurityConstants.ROLE_ADMIN, SecurityConstants.ROLE_LTD_ADMIN);

        // Replays V92's exact statement with DIFFERENT ids. This is the real-world case the
        // ON CONFLICT clause exists for: P0-1 forced operators to hand-insert admin authority rows
        // to work around the missing seed, so a UAT database may already carry these names at ids
        // of someone else's choosing. Conflicting on `name` (UNIQUE, V10) rather than `id` is what
        // makes the migration survive that.
        assertThatCode(() -> jdbcTemplate.update(
            "INSERT INTO main.authority (id, name, status, created_by, created_date) VALUES "
                + "(9103, 'ROLE_ADMIN', 'ACTIVE', 'system', ?), "
                + "(9104, 'ROLE_LTD_ADMIN', 'ACTIVE', 'system', ?) "
                + "ON CONFLICT (name) DO NOTHING",
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())))
            .doesNotThrowAnyException();

        assertThat(adminAuthorityNames())
            .as("re-running the seed must not duplicate either row")
            .containsExactlyInAnyOrder(SecurityConstants.ROLE_ADMIN, SecurityConstants.ROLE_LTD_ADMIN);
    }

    private List<String> adminAuthorityNames() {
        return jdbcTemplate.queryForList(
            "SELECT name FROM main.authority WHERE name IN (?, ?)",
            String.class, SecurityConstants.ROLE_ADMIN, SecurityConstants.ROLE_LTD_ADMIN);
    }
}
