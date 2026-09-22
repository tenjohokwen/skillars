package com.softropic.skillars.infrastructure.persistence;

import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-128 AC5's own Tests bullet asked for a narrow SQL-text test asserting the
 * {@code claimed_at} claim-stamp write and its paired staleness read use {@code clock_timestamp()},
 * not {@code now()} — the difference between the two only manifests across multiple statements
 * inside one open transaction, which no existing IT exercises, so a plain reflection check on the
 * {@code @Query} text itself is what actually guards against a future revert back to {@code now()}
 * (story review, 2026-09-22 — this test discharges that bullet's own fallback, rather than
 * a rationale recorded here with nothing to enforce it).
 */
class ClaimStampUsesClockTimestampTest {

    @Test
    void videoDeletionOutboxRepository_claimStampQueriesUseClockTimestampNotNow() throws Exception {
        assertClaimStampUsesClockTimestamp(VideoDeletionOutboxRepository.class);
    }

    @Test
    void radarCompositeDlqRepository_claimStampQueriesUseClockTimestampNotNow() throws Exception {
        assertClaimStampUsesClockTimestamp(RadarCompositeDlqRepository.class);
    }

    private void assertClaimStampUsesClockTimestamp(Class<?> repositoryInterface) throws Exception {
        assertQueryText(repositoryInterface, "claimPendingBatch");
        assertQueryText(repositoryInterface, "resetStaleClaimed");
    }

    private void assertQueryText(Class<?> repositoryInterface, String methodName) throws Exception {
        Method method = findMethod(repositoryInterface, methodName);
        String sql = method.getAnnotation(Query.class).value();
        assertThat(sql)
            .as("%s.%s's claim-stamp SQL", repositoryInterface.getSimpleName(), methodName)
            .contains("clock_timestamp()")
            .doesNotContain("now()");
    }

    private Method findMethod(Class<?> repositoryInterface, String methodName) {
        for (Method m : repositoryInterface.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        throw new AssertionError("No method named " + methodName + " on " + repositoryInterface);
    }
}
