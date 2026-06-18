package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.SessionDnaScore;
import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SessionDnaCalculatorTest {

    private final SessionDnaCalculator calculator = new SessionDnaCalculator();

    @Test
    void calculate_emptyList_returnsAllZeros() {
        SessionDnaScore score = calculator.calculate(List.of());
        assertThat(score.technical()).isZero();
        assertThat(score.physical()).isZero();
        assertThat(score.cognitive()).isZero();
        assertThat(score.matchRealism()).isZero();
        assertThat(score.weakFootFocus()).isZero();
    }

    @Test
    void calculate_nullList_returnsAllZeros() {
        SessionDnaScore score = calculator.calculate(null);
        assertThat(score.technical()).isZero();
        assertThat(score.physical()).isZero();
        assertThat(score.cognitive()).isZero();
        assertThat(score.matchRealism()).isZero();
        assertThat(score.weakFootFocus()).isZero();
    }

    @Test
    void calculate_singleDrillIntensity1_returnsMinScore() {
        DrillMetadata m = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 1)
            .set(Select.field(DrillMetadata::pressureLevel), 1)
            .set(Select.field(DrillMetadata::cognitiveLoad), 1)
            .set(Select.field(DrillMetadata::matchRealism), 1)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();

        SessionDnaScore score = calculator.calculate(List.of(m));

        assertThat(score.technical()).isZero();
        assertThat(score.physical()).isZero();
        assertThat(score.cognitive()).isZero();
        assertThat(score.matchRealism()).isZero();
        assertThat(score.weakFootFocus()).isZero();
    }

    @Test
    void calculate_singleDrillIntensity5_returnsMaxScore() {
        DrillMetadata m = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 5)
            .set(Select.field(DrillMetadata::pressureLevel), 5)
            .set(Select.field(DrillMetadata::cognitiveLoad), 5)
            .set(Select.field(DrillMetadata::matchRealism), 5)
            .set(Select.field(DrillMetadata::weakFootBias), true)
            .create();

        SessionDnaScore score = calculator.calculate(List.of(m));

        assertThat(score.technical()).isEqualTo(100);
        assertThat(score.physical()).isEqualTo(100);
        assertThat(score.cognitive()).isEqualTo(100);
        assertThat(score.matchRealism()).isEqualTo(100);
        assertThat(score.weakFootFocus()).isEqualTo(100);
    }

    @Test
    void calculate_singleDrillIntensity3_returnsMidScore() {
        DrillMetadata m = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 3)
            .set(Select.field(DrillMetadata::pressureLevel), 3)
            .set(Select.field(DrillMetadata::cognitiveLoad), 3)
            .set(Select.field(DrillMetadata::matchRealism), 3)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();

        SessionDnaScore score = calculator.calculate(List.of(m));

        assertThat(score.technical()).isEqualTo(50);
        assertThat(score.physical()).isEqualTo(50);
        assertThat(score.cognitive()).isEqualTo(50);
        assertThat(score.matchRealism()).isEqualTo(50);
        assertThat(score.weakFootFocus()).isZero();
    }

    @Test
    void calculate_multiDrill_averagesCorrectly() {
        DrillMetadata low = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 1)
            .set(Select.field(DrillMetadata::pressureLevel), 1)
            .set(Select.field(DrillMetadata::cognitiveLoad), 1)
            .set(Select.field(DrillMetadata::matchRealism), 1)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();
        DrillMetadata high = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 5)
            .set(Select.field(DrillMetadata::pressureLevel), 5)
            .set(Select.field(DrillMetadata::cognitiveLoad), 5)
            .set(Select.field(DrillMetadata::matchRealism), 5)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();

        SessionDnaScore score = calculator.calculate(List.of(low, high));

        // avgIntensity = (1+5)/2 = 3 → physical score = (3-1)*25 = 50
        assertThat(score.physical()).isEqualTo(50);
    }

    @Test
    void calculate_weakFootFocus_percentageOfDrills() {
        DrillMetadata withBias = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 3)
            .set(Select.field(DrillMetadata::pressureLevel), 3)
            .set(Select.field(DrillMetadata::cognitiveLoad), 3)
            .set(Select.field(DrillMetadata::matchRealism), 3)
            .set(Select.field(DrillMetadata::weakFootBias), true)
            .create();
        DrillMetadata noBias1 = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 3)
            .set(Select.field(DrillMetadata::pressureLevel), 3)
            .set(Select.field(DrillMetadata::cognitiveLoad), 3)
            .set(Select.field(DrillMetadata::matchRealism), 3)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();
        DrillMetadata noBias2 = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 3)
            .set(Select.field(DrillMetadata::pressureLevel), 3)
            .set(Select.field(DrillMetadata::cognitiveLoad), 3)
            .set(Select.field(DrillMetadata::matchRealism), 3)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();

        SessionDnaScore score = calculator.calculate(List.of(withBias, noBias1, noBias2));

        // 1 of 3 have weakFootBias → round(1/3 * 100) = 33
        assertThat(score.weakFootFocus()).isEqualTo(33);
    }

    @Test
    void calculate_duplicateDrillInTwoBlocks_countsEachOccurrence() {
        DrillMetadata m = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 5)
            .set(Select.field(DrillMetadata::pressureLevel), 5)
            .set(Select.field(DrillMetadata::cognitiveLoad), 3)
            .set(Select.field(DrillMetadata::matchRealism), 3)
            .set(Select.field(DrillMetadata::weakFootBias), true)
            .create();
        DrillMetadata mLow = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 1)
            .set(Select.field(DrillMetadata::pressureLevel), 1)
            .set(Select.field(DrillMetadata::cognitiveLoad), 1)
            .set(Select.field(DrillMetadata::matchRealism), 1)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .create();

        // Same drill twice (as if placed in 2 blocks) + one low drill
        SessionDnaScore twice = calculator.calculate(List.of(m, m, mLow));
        SessionDnaScore once = calculator.calculate(List.of(m, mLow));

        // Duplicated high drill shifts the average upward
        assertThat(twice.physical()).isGreaterThan(once.physical());
    }

    @Test
    void calculate_nullEquipmentInMetadata_doesNotThrow() {
        DrillMetadata m = Instancio.of(DrillMetadata.class)
            .set(Select.field(DrillMetadata::intensity), 3)
            .set(Select.field(DrillMetadata::pressureLevel), 3)
            .set(Select.field(DrillMetadata::cognitiveLoad), 3)
            .set(Select.field(DrillMetadata::matchRealism), 3)
            .set(Select.field(DrillMetadata::weakFootBias), false)
            .withNullable(Select.field(DrillMetadata::equipmentRequired))
            .create();

        assertThat(calculator.calculate(List.of(m))).isNotNull();
    }
}
