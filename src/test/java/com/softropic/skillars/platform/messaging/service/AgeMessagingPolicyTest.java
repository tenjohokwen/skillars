package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgeMessagingPolicyTest {

    @Test
    void from_prohibited_returnsProhibited() {
        AgeMessagingPolicy policy = AgeMessagingPolicy.from(MessagingPolicy.prohibited());
        assertThat(policy).isEqualTo(AgeMessagingPolicy.PROHIBITED);
        assertThat(policy.playerIsBlocked()).isTrue();
        assertThat(policy.parentIsBlocked()).isFalse();
        assertThat(policy.parentHasAccess()).isTrue();
        assertThat(policy.parentIsPrimary()).isTrue();
        assertThat(policy.visibleToPlayer()).isFalse();
    }

    @Test
    void from_parentManaged_returnsParentManaged() {
        AgeMessagingPolicy policy = AgeMessagingPolicy.from(MessagingPolicy.parentManaged());
        assertThat(policy).isEqualTo(AgeMessagingPolicy.PARENT_MANAGED);
        assertThat(policy.playerIsBlocked()).isTrue();
        assertThat(policy.parentIsBlocked()).isFalse();
        assertThat(policy.parentHasAccess()).isTrue();
        assertThat(policy.parentIsPrimary()).isTrue();
        assertThat(policy.visibleToPlayer()).isFalse();
    }

    @Test
    void from_supervised_returnsSupervised() {
        AgeMessagingPolicy policy = AgeMessagingPolicy.from(MessagingPolicy.supervised());
        assertThat(policy).isEqualTo(AgeMessagingPolicy.SUPERVISED);
        assertThat(policy.playerIsBlocked()).isFalse();
        assertThat(policy.parentIsBlocked()).isFalse();
        assertThat(policy.parentHasAccess()).isTrue();
        assertThat(policy.parentIsPrimary()).isFalse();
        assertThat(policy.visibleToPlayer()).isTrue();
    }

    @Test
    void from_unrestricted_returnsUnrestricted() {
        AgeMessagingPolicy policy = AgeMessagingPolicy.from(MessagingPolicy.unrestricted());
        assertThat(policy).isEqualTo(AgeMessagingPolicy.UNRESTRICTED);
        assertThat(policy.playerIsBlocked()).isFalse();
        assertThat(policy.parentIsBlocked()).isTrue();
        assertThat(policy.parentHasAccess()).isFalse();
        assertThat(policy.parentIsPrimary()).isFalse();
        assertThat(policy.visibleToPlayer()).isTrue();
    }
}
