package com.softropic.skillars.config;

import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.utils.TestMailManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Minimal test configuration providing a no-op {@link MailManager} bean.
 *
 * <p>Activated by {@code enable.test.mail=true} (same condition as in {@code TestConfig}).
 * Exists as a standalone class so it can be imported by {@link AbstractSkillarsE2ETest} without
 * pulling in the rest of {@code TestConfig} (which declares duplicate container beans already
 * provided by {@link PostgresContainerConfig} and {@link RedisContainerConfig}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "enable.test.mail", havingValue = "true")
    public MailManager mailManager() {
        return new TestMailManager();
    }
}
