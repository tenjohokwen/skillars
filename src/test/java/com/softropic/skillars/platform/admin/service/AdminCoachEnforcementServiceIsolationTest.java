package com.softropic.skillars.platform.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-122 AC3: {@code getEnforcementProfile} and {@code getCoachesUnderEnforcement}
 * must read a consistent status + strike-count snapshot instead of two separate READ COMMITTED
 * statements.
 *
 * <p><strong>Why an annotation-presence test, not a snapshot-consistency IT:</strong> the story's own
 * Dev Notes explicitly sanction this fallback — proving a genuine {@code REPEATABLE READ} snapshot
 * requires pausing execution between the method's two internal statements (status read, then strike
 * count read), which needs either a precise instrumentation hook inside the service method or a
 * bespoke test-only seam; neither is available without a larger change to the class than this AC
 * warrants. Per the story: "If instrumenting a precise pause point inside the method proves
 * impractical, a lighter-weight test asserting the isolation level itself is configured ... is an
 * acceptable fallback." Marked here explicitly, per that same instruction, as <strong>unverified
 * behavior, annotation only</strong> — this test proves the {@code @Transactional} declaration is
 * correct, not that a concurrent writer's torn read is actually prevented at runtime.
 *
 * <p>Both existing HTTP-driven ITs ({@code CoachEnforcementListIT}, {@code ManualStrikeIT}) already
 * exercise these two methods outside of any enclosing transaction (via {@code HttpTestClient}, never
 * a directly-injected transaction-wrapped service call) — see this AC's own isolation warning about
 * Spring's {@code validateExistingTransaction=false} default silently dropping the isolation request
 * inside an ambient transaction. Nothing here needs to re-prove that; it only pins the annotation
 * itself so a future edit cannot silently drop {@code isolation = REPEATABLE_READ}.
 */
class AdminCoachEnforcementServiceIsolationTest {

    @Test
    void getEnforcementProfile_isReadOnlyRepeatableRead() throws NoSuchMethodException {
        Method method = AdminCoachEnforcementService.class.getMethod(
            "getEnforcementProfile", java.util.UUID.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).as("getEnforcementProfile must carry @Transactional").isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void getCoachesUnderEnforcement_isReadOnlyRepeatableRead() throws NoSuchMethodException {
        Method method = AdminCoachEnforcementService.class.getMethod(
            "getCoachesUnderEnforcement", String.class, int.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).as("getCoachesUnderEnforcement must carry @Transactional").isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
}
