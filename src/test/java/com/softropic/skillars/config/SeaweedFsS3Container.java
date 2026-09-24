package com.softropic.skillars.config;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * SeaweedFS running its S3 gateway, standing in for {@code org.testcontainers.containers.MinIOContainer}
 * (see {@link SharedContainers#MINIO_IMAGE}'s Javadoc for why MinIO itself is no longer usable here).
 *
 * <p>Testcontainers has no dedicated SeaweedFS module, so this wraps {@link GenericContainer} directly
 * and deliberately mirrors {@code MinIOContainer}'s own public surface ({@link #getS3URL()},
 * {@link #getUserName()}, {@link #getPassword()}) so every call site that used to hold a
 * {@code MinIOContainer} only needs its declared type changed, not its usage.
 *
 * <h2>Credentials</h2>
 *
 * SeaweedFS's {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} environment-variable path has a
 * live upstream reliability issue (seaweedfs/seaweedfs#7311, still open as of this writing). This
 * container instead mounts a static S3 identity config
 * ({@code src/test/resources/seaweedfs/s3-identity.json}) via {@code -s3.config}, the mechanism
 * SeaweedFS's own docs recommend — verified locally (manual {@code docker run} + unsigned-request
 * probe returning 403, i.e. the S3 gateway is live and enforcing auth) before wiring this in.
 *
 * <h2>Path-style addressing</h2>
 *
 * SeaweedFS's S3 gateway does not provision per-bucket virtual-host subdomains and 405s a
 * virtual-hosted-style request (bucket in the hostname). Every {@code S3Client}/{@code S3Presigner}
 * this codebase builds already sets {@code pathStyleAccessEnabled(true)}
 * ({@link com.softropic.skillars.infrastructure.blobstore.config.BlobstoreConfig}), so this is already
 * satisfied without any application-code change.
 */
public class SeaweedFsS3Container extends GenericContainer<SeaweedFsS3Container> {

    private static final int S3_PORT = 8333;
    private static final String ACCESS_KEY = "seaweedtest";
    private static final String SECRET_KEY = "seaweedtestsecret123";
    private static final String S3_CONFIG_CONTAINER_PATH = "/etc/seaweedfs/s3.json";

    public SeaweedFsS3Container(DockerImageName dockerImageName) {
        super(dockerImageName);
        withExposedPorts(S3_PORT);
        withCopyFileToContainer(
            MountableFile.forClasspathResource("seaweedfs/s3-identity.json"), S3_CONFIG_CONTAINER_PATH);
        withCommand(
            "server", "-s3", "-dir=/data", "-ip.bind=0.0.0.0",
            "-s3.config=" + S3_CONFIG_CONTAINER_PATH, "-master.volumeSizeLimitMB=128");
        // Observed local boot time (master+volume+filer+s3 bootstrapping in combined "mini" mode)
        // was ~23s; 90s leaves headroom for slower CI runners without masking a genuine hang.
        waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
            .withStartupTimeout(Duration.ofSeconds(90)));
    }

    public String getS3URL() {
        return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
    }

    public String getUserName() {
        return ACCESS_KEY;
    }

    public String getPassword() {
        return SECRET_KEY;
    }
}
