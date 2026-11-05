package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-172 AC2: V231 makes the database refuse two accounts whose email or login differ only by
 * letter case, and its collision guard stops the migration instead of merging such accounts.
 */
@Sql({"/sql/authorityData.sql", "/sql/userData.sql"})
class UserIdentityCaseIT extends AbstractIntegrationTest {

    private static final Path V231 =
        Path.of("src/main/resources/db/migration/V231__user_email_login_case_insensitive.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void anEmailDifferingOnlyByCase_isRefusedByTheLowerIndex() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "UPDATE main.\"user\" SET email = 'ME@Yahoo.com' WHERE email = 'queb@yahoo.com'")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_user_email_lower");
    }

    @Test
    void aLoginDifferingOnlyByCase_isRefusedByTheLowerIndex() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "UPDATE main.\"user\" SET login = 'ME@Yahoo.com' WHERE login = 'queb@yahoo.com'")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_user_login_lower");
    }

    @Test
    void theCollisionGuard_stopsTheMigrationAndNamesTheAddress() throws IOException {
        String guard = collisionGuardOf(Files.readString(V231, StandardCharsets.UTF_8));

        // Recreate the pre-V231 state the guard exists for (two rows differing only by case) inside one
        // transaction that is rolled back: Postgres DDL is transactional, so the indexes come back.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> {
            s.setRollbackOnly();
            jdbcTemplate.execute("DROP INDEX main.ux_user_email_lower");
            jdbcTemplate.update("UPDATE main.\"user\" SET email = 'ME@Yahoo.com' WHERE email = 'queb@yahoo.com'");
            jdbcTemplate.execute(guard);
        }))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("differ only by letter case")
            .hasMessageContaining("email me@yahoo.com");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'main' AND indexname = 'ux_user_email_lower'",
            Integer.class)).isEqualTo(1);
    }

    private static String collisionGuardOf(String migration) {
        Matcher m = Pattern.compile("DO \\$\\$.*?END \\$\\$;", Pattern.DOTALL).matcher(migration);
        assertThat(m.find()).as("V231 collision guard").isTrue();
        return m.group();
    }
}
