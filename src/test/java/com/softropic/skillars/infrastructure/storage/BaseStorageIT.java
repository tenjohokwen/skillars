package com.softropic.skillars.infrastructure.storage;

import com.softropic.skillars.config.MinioContainerConfig;
import com.softropic.skillars.config.PostgresContainerConfig;
import com.softropic.skillars.config.RedisContainerConfig;
import com.softropic.skillars.config.TestMailConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Testcontainers
@Import({MinioContainerConfig.class, PostgresContainerConfig.class, RedisContainerConfig.class,
         TestMailConfig.class, BaseStorageIT.StorageTestConfig.class})
@ActiveProfiles({"dev", "test"})
public abstract class BaseStorageIT {

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDownSec() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfig {

        @Bean
        @Primary
        RestTemplate restTemplate(RestTemplateBuilder builder) {
            return builder.build();
        }
    }
}
