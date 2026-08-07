package com.softropic.skillars.config;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide Testcontainers instances, started once and deliberately never stopped.
 *
 * <h2>Why these are not Spring beans</h2>
 *
 * They used to be. {@code TestConfig} declared the PostgreSQL and Redis containers as
 * {@code @Bean}s and {@code MinioTestConfig} did the same for MinIO. Spring Boot's
 * {@code TestcontainersLifecycleBeanPostProcessor} starts any {@link org.testcontainers.lifecycle.Startable}
 * bean when its context refreshes and stops it when that context closes, which binds
 * <strong>container lifetime 1:1 to the Spring {@code ApplicationContext}</strong>.
 *
 * <p>The Spring TestContext Framework caches contexts keyed by {@code MergedContextConfiguration}.
 * Two test classes share a context only if every component of that key matches, including the
 * bean-override customizer built from the set of {@code @MockitoBean}/{@code @MockitoSpyBean}
 * declarations. One extra mocked type on one test class therefore produced another context — and
 * so another PostgreSQL container and another Redis container. The suite needed 37 contexts
 * against a cache ceiling of 32, so it also evicted and rebuilt them mid-run.
 *
 * <p>Holding the containers here, outside the Spring lifecycle, decouples the two completely.
 * {@code TestConfig} exposes them to Boot through {@code JdbcConnectionDetails} and
 * {@code RedisConnectionDetails} beans, and {@code MinioTestConfig} through a
 * {@code DynamicPropertyRegistrar}. None of those is {@code Startable}, so the lifecycle
 * post-processor never touches them.
 *
 * <p><strong>Do not "tidy" these back into {@code @Bean} methods, and do not return these
 * instances from an {@code @ServiceConnection @Bean} either.</strong> The post-processor treats
 * any {@code Startable} bean as its own to destroy, so handing it a shared static instance means
 * the first context to close stops the container every other context is still using. Ryuk (the
 * Testcontainers reaper) removes these at JVM exit, which is the only shutdown they need.
 *
 * <h2>Why one holder class per container</h2>
 *
 * Three {@code static final} fields on a single class would share one static initializer, so
 * touching any one of them would start all three — including MinIO for every JVM, even
 * {@code -Dit.test=SomeBookingIT}. That is precisely the single-class iteration loop this design
 * exists to keep fast, and it would discard the intent {@code MinioTestConfig} documents: tests
 * that never touch blob storage should not pay for a MinIO container.
 *
 * <p>The initialization-on-demand holder idiom below gives each container its own class, so each
 * starts on first touch and not before. It is also thread-safe without synchronization: the JVM
 * guarantees a class's static initializer runs exactly once.
 *
 * @see <a href="file:../../../../../../../docs/testing/container-architecture.md">docs/testing/container-architecture.md</a>
 */
public final class SharedContainers {

    /**
     * Test PostgreSQL image.
     *
     * <p>MUST track the production image in {@code docker-compose.yml:64}. Any divergence means
     * every integration test validates against a database the product does not run on.
     */
    static final String POSTGRES_IMAGE = "postgres:14.18";

    /** Test Redis image. Tracks {@code docker-compose.yml:89}. */
    static final String REDIS_IMAGE = "redis:7-alpine";

    /** MinIO image. No production compose entry — object storage is S3 in production. */
    static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-13T07-53-03Z";

    /**
     * Database name for the shared PostgreSQL container.
     *
     * <p>Previously read from the Spring context as {@code ${spring.application.name}}, which a
     * static container constructed before any context exists cannot do. Pinned here to the same
     * value that property resolves to ({@code application.yaml:41}). The only other bindings of
     * {@code spring.application.name} are Micrometer metric/observation tags
     * ({@code application.yaml:341,347}) and the logback {@code appName}; none is
     * database-related, so pinning it here changes nothing.
     */
    static final String POSTGRES_DB = "skillars";

    static final String POSTGRES_USER = "postgres";
    static final String POSTGRES_PASSWORD = "postgres";

    private static final int REDIS_PORT = 6379;

    private SharedContainers() {
    }

    /** Lazy holder for the shared PostgreSQL container. */
    public static final class Postgres {

        static final PostgreSQLContainer<?> INSTANCE = create();

        private static PostgreSQLContainer<?> create() {
            // CustomPostgresContainer sets TZ and PGTZ to UTC. Combined with the
            // -Duser.timezone=UTC that failsafe passes, that is what the timezone-integrity
            // stories (deferred-17, deferred-18) rest on. Do not drop it.
            PostgreSQLContainer<?> container =
                new CustomPostgresContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName(POSTGRES_DB)
                    .withUsername(POSTGRES_USER)
                    .withPassword(POSTGRES_PASSWORD)
                    // Runs once per JVM now instead of once per context.
                    .withInitScript("sql/createSchema.sql");
            container.start();
            return container;
        }

        private Postgres() {
        }
    }

    /** Lazy holder for the shared Redis container. */
    public static final class Redis {

        static final GenericContainer<?> INSTANCE = create();

        private static GenericContainer<?> create() {
            GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT);
            container.start();
            return container;
        }

        private Redis() {
        }
    }

    /**
     * Lazy holder for the shared MinIO container.
     *
     * <p>Only touched by {@code MinioTestConfig}, which only the storage/video IT families
     * import. A JVM that runs no storage test never starts MinIO.
     */
    public static final class Minio {

        static final MinIOContainer INSTANCE = create();

        private static MinIOContainer create() {
            MinIOContainer container = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE));
            container.start();
            return container;
        }

        private Minio() {
        }
    }

    public static PostgreSQLContainer<?> postgres() {
        return Postgres.INSTANCE;
    }

    public static GenericContainer<?> redis() {
        return Redis.INSTANCE;
    }

    public static MinIOContainer minio() {
        return Minio.INSTANCE;
    }
}
