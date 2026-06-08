package com.softropic.skillars.e2e;

import com.softropic.skillars.config.E2ESecurityConfig;
import com.softropic.skillars.config.PostgresContainerConfig;
import com.softropic.skillars.config.RedisContainerConfig;
import com.softropic.skillars.config.TestClockConfig;
import com.softropic.skillars.config.TestDataCleaner;
import com.softropic.skillars.config.TestMailConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import({PostgresContainerConfig.class, RedisContainerConfig.class,
         E2ESecurityConfig.class, TestClockConfig.class, TestMailConfig.class})
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false"
})
public abstract class AbstractSkillarsE2ETest {

    @LocalServerPort
    protected int serverPort;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected E2ESecurityConfig e2eSecurityConfig;

    @Autowired
    protected TestDataCleaner testDataCleaner;

    @BeforeEach
    void baseSetUp() {
        e2eSecurityConfig.seedSecurityRow();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void baseTearDown() {
        testDataCleaner.wipeAll();
    }
}
