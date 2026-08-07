package com.softropic.skillars.platform.video;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.config.AbstractIntegrationTest;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.InjectWireMock;

/**
 * Base class for Video module integration tests.
 * Starts PostgreSQL (via {@link TestConfig}) and WireMock for Bunny.net stubs.
 * The WireMock server URL is wired into {@code app.video.bunny.cdn-hostname} via
 * {@code application-test.yaml}, which references the auto-registered
 * {@code wiremock.server.bunny-service.baseUrl} property.
 */
public abstract class BaseVideoIT extends AbstractIntegrationTest {

    /**
     * Hoisted to the family base rather than the root, per AC4.2.
     *
     * <p>Trap-check discharged before hoisting: every {@code *IT} that references
     * {@code VideoProviderAdapter} already mocks it, including the only three that drive Bunny
     * over WireMock ({@code VideoUploadInitializationIT}, {@code VideoRetryUploadIT},
     * {@code VideoUploadConfirmationIT}) -- so this cannot silently make a live WireMock stub
     * unreachable. It stays OFF {@code AbstractIntegrationTest}: ~100 classes never reference the
     * adapter and could in principle reach it transitively, which static analysis cannot rule out.
     *
     * <p>No manual reset needed -- {@code MockReset.AFTER} is the default.
     */
    @MockitoBean
    protected VideoProviderAdapter videoProviderAdapter;


    @InjectWireMock("bunny-service")
    protected WireMockServer wireMockServer;

}
