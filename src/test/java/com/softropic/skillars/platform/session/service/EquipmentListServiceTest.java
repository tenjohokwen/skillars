package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.platform.session.contract.DrillMetadata;
import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class EquipmentListServiceTest {

    private final EquipmentListService service = new EquipmentListService();

    @Test
    void generate_emptyList_returnsEmpty() {
        assertThat(service.generate(List.of())).isEmpty();
    }

    @Test
    void generate_nullList_returnsEmpty() {
        assertThat(service.generate(null)).isEmpty();
    }

    @Test
    void generate_singleDrill_returnsSortedList() {
        DrillMetadata m = drillWithEquipment(List.of("poles", "cones", "bibs"));
        assertThat(service.generate(List.of(m))).containsExactly("bibs", "cones", "poles");
    }

    @Test
    void generate_duplicateEquipment_deduplicates() {
        DrillMetadata m1 = drillWithEquipment(List.of("cones"));
        DrillMetadata m2 = drillWithEquipment(List.of("cones"));
        assertThat(service.generate(List.of(m1, m2))).containsExactly("cones");
    }

    @Test
    void generate_caseInsensitiveDedup() {
        DrillMetadata m1 = drillWithEquipment(List.of("Cones"));
        DrillMetadata m2 = drillWithEquipment(List.of("cones"));
        assertThat(service.generate(List.of(m1, m2))).containsExactly("cones");
    }

    @Test
    void generate_nullEquipmentRequired_skipped() {
        DrillMetadata m = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::equipmentRequired), null)
            .create();
        assertThat(service.generate(List.of(m))).isEmpty();
    }

    @Test
    void generate_multiDrills_sortedAlphabetically() {
        DrillMetadata m1 = drillWithEquipment(List.of("poles"));
        DrillMetadata m2 = drillWithEquipment(List.of("bibs"));
        DrillMetadata m3 = drillWithEquipment(List.of("cones"));
        assertThat(service.generate(List.of(m1, m2, m3))).containsExactly("bibs", "cones", "poles");
    }

    @Test
    void generate_blankEquipmentEntry_skipped() {
        DrillMetadata m = drillWithEquipment(List.of("", " ", "cones"));
        assertThat(service.generate(List.of(m))).containsExactly("cones");
    }

    private DrillMetadata drillWithEquipment(List<String> equipment) {
        return Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::equipmentRequired), equipment)
            .create();
    }
}
