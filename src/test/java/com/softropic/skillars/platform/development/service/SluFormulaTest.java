package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.session.contract.DrillMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SluFormulaTest {

    private static final BigDecimal SCALE_0_10 = new BigDecimal("0.10");

    private DrillMetadata metadata(Map<String, Integer> skillWeighting, int repDensity, int intensity,
                                   int pressureLevel, int matchRealism) {
        return new DrillMetadata(
            List.of(), List.of(), skillWeighting,
            repDensity, intensity, pressureLevel, 5, matchRealism,
            false, "U12", List.of(), "2", List.of(), null
        );
    }

    @Test
    void calculate_withValidMetadata_returnsNonZeroSluForWeightedSkills() {
        // repDensity=8, weight=5, intensity=7, pressure=6, matchRealism=5, duration=5
        // slu = 8 × 5 × (7×0.10) × (6×0.10) × (5×0.10) × 5 = 8×5×0.7×0.6×0.5×5 = 42.0
        DrillMetadata meta = metadata(Map.of("PAC", 5), 8, 7, 6, 5);

        Map<String, BigDecimal> result = SluFormula.calculate(meta, 5, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).containsKey("PAC");
        assertThat(result.get("PAC")).isEqualByComparingTo(new BigDecimal("42.0000"));
    }

    @Test
    void calculate_withZeroWeight_excludesSkillFromResult() {
        DrillMetadata meta = metadata(Map.of("SHO", 0, "PAS", 5), 5, 5, 5, 5);

        Map<String, BigDecimal> result = SluFormula.calculate(meta, 5, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).doesNotContainKey("SHO");
        assertThat(result).containsKey("PAS");
    }

    @Test
    void calculate_withZeroDuration_returnsEmptyMap() {
        DrillMetadata meta = metadata(Map.of("DRI", 5), 5, 5, 5, 5);

        Map<String, BigDecimal> result = SluFormula.calculate(meta, 0, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).isEmpty();
    }

    @Test
    void calculate_withNullMetadata_returnsEmptyMap() {
        Map<String, BigDecimal> result = SluFormula.calculate(null, 5, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).isEmpty();
    }

    @Test
    void calculate_withNullSkillWeighting_returnsEmptyMap() {
        DrillMetadata meta = metadata(null, 5, 5, 5, 5);

        Map<String, BigDecimal> result = SluFormula.calculate(meta, 5, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).isEmpty();
    }

    @Test
    void calculate_withNegativeWeight_excludesSkillFromResult() {
        DrillMetadata meta = metadata(Map.of("PAC", -3, "SHO", 5), 5, 5, 5, 5);

        Map<String, BigDecimal> result = SluFormula.calculate(meta, 5, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).doesNotContainKey("PAC");
        assertThat(result).containsKey("SHO");
    }

    @Test
    void calculate_allFifteenSkills_scalesWithDuration() {
        Map<String, Integer> allSkills = Map.ofEntries(
            Map.entry("PAC", 1), Map.entry("SHO", 1), Map.entry("PAS", 1),
            Map.entry("DRI", 1), Map.entry("PHY", 1), Map.entry("DEF", 1),
            Map.entry("WEF", 1), Map.entry("F1T", 1), Map.entry("FIN", 1),
            Map.entry("1V1", 1), Map.entry("HED", 1), Map.entry("CRO", 1),
            Map.entry("IBS", 1), Map.entry("OBS", 1), Map.entry("FKI", 1)
        );
        DrillMetadata meta = metadata(allSkills, 5, 5, 5, 5);

        Map<String, BigDecimal> result = SluFormula.calculate(meta, 10, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        assertThat(result).hasSize(15);
        assertThat(result).containsKey("1V1");
        assertThat(result).containsKey("F1T");
    }

    @Test
    void calculate_largerDuration_proportionallyIncreasesAllSlu() {
        DrillMetadata meta = metadata(Map.of("PHY", 4), 3, 5, 5, 5);

        Map<String, BigDecimal> result5  = SluFormula.calculate(meta, 5,  SCALE_0_10, SCALE_0_10, SCALE_0_10);
        Map<String, BigDecimal> result10 = SluFormula.calculate(meta, 10, SCALE_0_10, SCALE_0_10, SCALE_0_10);

        BigDecimal slu5  = result5.get("PHY");
        BigDecimal slu10 = result10.get("PHY");

        assertThat(slu5).isNotNull();
        assertThat(slu10).isNotNull();
        assertThat(slu10).isEqualByComparingTo(slu5.multiply(BigDecimal.valueOf(2)));
    }
}
