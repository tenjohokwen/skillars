package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.security.contract.AgeTier;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** S-16: short public identity fields reject contact details and over-long items instead of storing them. */
class ProfileBuilderRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static ProfileBuilderStep1Request step1(String name, List<String> languages) {
        return new ProfileBuilderStep1Request(name, "bio", "Berlin", null, languages, "Europe/Berlin");
    }

    @Test
    void step1_normalValues_valid() {
        assertThat(validator.validate(step1("Coach Carter", List.of("English", "German")))).isEmpty();
    }

    @Test
    void step1_displayNameWithPhoneOrEmail_rejected() {
        assertThat(validator.validate(step1("Coach 0791 112 3456", List.of("English")))).hasSize(1);
        assertThat(validator.validate(step1("coach at gmail dot com", List.of("English")))).hasSize(1);
        assertThat(validator.validate(step1("Coach wa.me/447911123456", List.of("English")))).hasSize(1);
    }

    @Test
    void step1_languageWithContactDetailsOrTooLong_rejected() {
        assertThat(validator.validate(step1("Coach", List.of("English, call 0791 112 3456")))).isNotEmpty();
        assertThat(validator.validate(step1("Coach", List.of("x".repeat(51))))).isNotEmpty();
    }

    @Test
    void step1_tooManyLanguages_rejected() {
        List<String> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "Lang" + i).toList();
        assertThat(validator.validate(step1("Coach", eleven))).isNotEmpty();
    }

    @Test
    void step2_specialtyItems_sizeAndContactDetailsEnforced_listCapKept() {
        List<AgeTier> ages = List.of(AgeTier.ADULT);
        assertThat(validator.validate(new ProfileBuilderStep2Request(List.of("Dribbling", "Set Pieces"), ages))).isEmpty();
        assertThat(validator.validate(new ProfileBuilderStep2Request(List.of("x".repeat(51)), ages))).isNotEmpty();
        assertThat(validator.validate(new ProfileBuilderStep2Request(List.of("insta: coach_john"), ages))).isNotEmpty();
        List<String> twentyOne = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "Skill" + i).toList();
        assertThat(validator.validate(new ProfileBuilderStep2Request(twentyOne, ages))).isNotEmpty();
    }

    @Test
    void step3_packLabelWithContactDetails_rejected() {
        var ok = new ProfileBuilderStep3Request.SessionPackRequest(5, BigDecimal.TEN, "5-pack, save 10%");
        var bad = new ProfileBuilderStep3Request.SessionPackRequest(5, BigDecimal.TEN, "5-pack call 0791 112 3456");
        assertThat(validator.validate(ok)).isEmpty();
        assertThat(validator.validate(bad)).hasSize(1);
    }
}
