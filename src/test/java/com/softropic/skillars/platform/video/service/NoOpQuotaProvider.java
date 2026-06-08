package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.ConsistencyGuarantee;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Default no-op implementation of {@link QuotaProvider}.
 * 
 * <p>NOTE: This class is intentionally located in {@code src/test/java} and
 * must NOT be moved to production source directories. It is intended for
 * use in integration tests where quota enforcement is not required, allowing
 * the application context to start without a specific {@link QuotaProvider}
 * implementation.
 * 
 * <p>The {@code @Component} annotation ensures it is auto-registered for tests.
 * This class must not exist in staging or production.
 *
 * <p>This implementation is stateless and therefore thread-safe.
 */
@Component
public class NoOpQuotaProvider implements QuotaProvider {

    @Override
    public boolean check(String ownerId, long requestedBytes) {
        return true;
    }

    @Override
    public String reserve(String ownerId, long bytes) {
        return "noop-" + UUID.randomUUID().toString();
    }

    @Override
    public void commit(String reservationHandle) {
        // no-op
    }

    @Override
    public void release(String reservationHandle) {
        // no-op
    }

    @Override
    public ConsistencyGuarantee getConsistencyGuarantee() {
        return ConsistencyGuarantee.STRICT;
    }
}
