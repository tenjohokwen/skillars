package com.softropic.skillars.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) {
        return new CustomPostgresContainer(DockerImageName.parse("postgres:14.18"))
                .withDatabaseName(dbName)
                .withPassword("postgres")
                .withUsername("postgres")
                .withInitScript("sql/createSchema.sql");
    }
}
