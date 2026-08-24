package com.softropic.skillars.platform.session.repo;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DrillVideoRefTest {

    @Test
    void isNew_true_forFreshlyConstructedInstance() {
        DrillVideoRef ref = new DrillVideoRef();
        ref.setDrillId(UUID.randomUUID());

        assertThat(ref.isNew()).isTrue();
    }

    @Test
    void isNew_false_afterMarkNotNew_simulatingPostPersistOrPostLoad() {
        DrillVideoRef ref = new DrillVideoRef();
        ref.setDrillId(UUID.randomUUID());
        ref.markNotNew();

        assertThat(ref.isNew()).isFalse();
    }

    @Test
    void getId_returnsDrillId() {
        UUID drillId = UUID.randomUUID();
        DrillVideoRef ref = new DrillVideoRef();
        ref.setDrillId(drillId);

        assertThat(ref.getId()).isEqualTo(drillId);
    }
}
