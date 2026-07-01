package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.config.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story deferred-4, AC 1: confirms Flyway created main.shedlock and that ShedLock's
 * JdbcTemplateLockProvider is wired up so distributed scheduler locking is active on startup.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class ShedLockConfigIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    @Test
    void shedlockTable_existsAfterStartup() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'main' AND table_name = 'shedlock'",
            Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void lockProviderBean_isConfigured() {
        assertThat(lockProvider).isNotNull();
    }
}
