package com.softropic.skillars.infrastructure.ses;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;

/**
 * skillars-deferred-114 AC5: a live (uncached) SES account check for the SES cutover preflight
 * endpoint — deliberately separate from {@link SesHealthIndicator#doHealthCheck}, whose whole point
 * is a TTL-cached result, exactly the staleness a cutover preflight must bypass.
 *
 * <h2>Why this lives here, not in the {@code platform.notification.api} resource that calls it</h2>
 *
 * {@code EmailTransportArchitectureTest} enforces "only {@code infrastructure.ses} may reference
 * {@code software.amazon.awssdk.services.sesv2}" and "no {@code platform/**} class references the
 * SES SDK directly" — real containment rules, not just documentation. This class, not the platform
 * resource, is the one that imports the SDK; {@link AccountStatus} is a plain, SDK-free record so
 * the platform layer can consume the result without ever touching an SDK type itself.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
public class SesAccountChecker {

    private final SesV2Client sesV2Client;

    @Autowired
    public SesAccountChecker(SesV2Client sesV2Client) {
        this.sesV2Client = sesV2Client;
    }

    /** {@code error} is non-null only when {@code GetAccount} itself threw. */
    public record AccountStatus(boolean sendingEnabled, boolean productionAccessEnabled,
                                 String enforcementStatus, String error) {
    }

    /** Always issues a live {@code GetAccount} call — never served from {@link SesHealthIndicator}'s cache. */
    public AccountStatus checkLive() {
        try {
            GetAccountResponse response = sesV2Client.getAccount(r -> { });
            return new AccountStatus(
                Boolean.TRUE.equals(response.sendingEnabled()),
                Boolean.TRUE.equals(response.productionAccessEnabled()),
                response.enforcementStatus(),
                null);
        } catch (SdkException ex) {
            log.warn("[SES_CUTOVER_PREFLIGHT] GetAccount failed: {}", ex.getMessage(), ex);
            // getMessage() is nullable on some SdkException subtypes.
            String error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return new AccountStatus(false, false, null, error);
        }
    }
}
