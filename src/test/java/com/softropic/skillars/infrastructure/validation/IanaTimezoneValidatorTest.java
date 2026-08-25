package com.softropic.skillars.infrastructure.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IanaTimezoneValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private record TimezoneHolder(@IanaTimezone String timezone) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Berlin", "America/Los_Angeles", "Etc/UTC", "Pacific/Niue"})
    void validRegionId_passes(String timezone) {
        Set<ConstraintViolation<TimezoneHolder>> violations = validator.validate(new TimezoneHolder(timezone));
        assertThat(violations).isEmpty();
    }

    // AC2 (skillars-deferred-65): fixed offsets and other non-region forms are DST-blind and are
    // parseable by ZoneId.of but not members of ZoneId.getAvailableZoneIds() — the exact case this
    // tightening exists to reject.
    @ParameterizedTest
    @ValueSource(strings = {"+01:00", "+05:30", "UTC+02:00", "GMT+2", "Z"})
    void fixedOffsetOrNonRegionForm_fails(String timezone) {
        Set<ConstraintViolation<TimezoneHolder>> violations = validator.validate(new TimezoneHolder(timezone));
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
            .contains("validation.timezone.invalid")
            .contains("Timezone must be a recognized timezone identifier");
    }

    @Test
    void outrightGarbage_fails() {
        Set<ConstraintViolation<TimezoneHolder>> violations = validator.validate(new TimezoneHolder("Not/AZone"));
        assertThat(violations).hasSize(1);
    }

    @Test
    void nullValue_passes() {
        Set<ConstraintViolation<TimezoneHolder>> violations = validator.validate(new TimezoneHolder(null));
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankValue_passes(String timezone) {
        Set<ConstraintViolation<TimezoneHolder>> violations = validator.validate(new TimezoneHolder(timezone));
        assertThat(violations).isEmpty();
    }
}
