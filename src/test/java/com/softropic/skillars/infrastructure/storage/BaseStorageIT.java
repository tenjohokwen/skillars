package com.softropic.skillars.infrastructure.storage;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.config.MinioTestConfig;
import org.springframework.context.annotation.Import;

/**
 * Base class for the storage IT family.
 *
 * <p>This is one of the deliberate context forks documented in {@code docs/testing/}. Importing
 * {@link MinioTestConfig} is what causes {@code SharedContainers.Minio} to be touched, so only
 * a JVM that runs a storage test pays for a MinIO container at all.
 */
@Import(MinioTestConfig.class)
public abstract class BaseStorageIT extends AbstractIntegrationTest {

}
