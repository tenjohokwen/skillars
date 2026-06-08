package com.softropic.skillars.config;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class CustomPostgresContainer extends PostgreSQLContainer<CustomPostgresContainer> {

    public CustomPostgresContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        this.withEnv("TZ", "UTC"); // Sets the system timezone
        this.withEnv("PGTZ", "UTC"); // Ensures PostgreSQL uses UTC internally
    }
}
