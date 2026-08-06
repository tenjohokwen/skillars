package com.softropic.skillars.infrastructure.storage;

import com.softropic.skillars.config.MinioTestConfig;
import com.softropic.skillars.config.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Import({TestConfig.class, MinioTestConfig.class})
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
}
