package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.security.contract.AgeTier;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    // ---- skillars-deferred-168: the pack is validated THROUGH the outer request (the list now cascades) ----

    private static ProfileBuilderStep3Request step3(List<ProfileBuilderStep3Request.SessionPackRequest> packs) {
        return new ProfileBuilderStep3Request(new BigDecimal("40"), null, packs);
    }

    private static ProfileBuilderStep3Request step3(Integer count, String price, String label) {
        return step3(List.of(new ProfileBuilderStep3Request.SessionPackRequest(
            count, price == null ? null : new BigDecimal(price), label)));
    }

    private Set<String> step3Violations(ProfileBuilderStep3Request req) {
        return validator.validate(req).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void step3_validPack_noPack_andNullPacks_allValid() {
        assertThat(validator.validate(step3(10, "380", "Season 10"))).isEmpty();
        assertThat(validator.validate(step3(100, "50000.00", null))).isEmpty();
        assertThat(validator.validate(step3(List.of()))).isEmpty();
        assertThat(validator.validate(step3(null))).isEmpty();
    }

    @Test
    void step3_packConstraintsCascadeFromTheOuterRequest() {
        assertThat(step3Violations(step3(5, "50", "call 0791 112 3456"))).containsExactly("sessionPacks[0].label");
        assertThat(step3Violations(step3(5, "50", "x".repeat(201)))).containsExactly("sessionPacks[0].label");
        assertThat(validator.validate(step3(5, "50", "x".repeat(200)))).isEmpty();
        assertThat(step3Violations(step3(0, "50", null))).containsExactly("sessionPacks[0].sessionCount");
        assertThat(step3Violations(step3(101, "50", null))).containsExactly("sessionPacks[0].sessionCount");
        assertThat(step3Violations(step3(null, "50", null))).containsExactly("sessionPacks[0].sessionCount");
        assertThat(step3Violations(step3(5, null, null))).containsExactly("sessionPacks[0].totalPrice");
        assertThat(step3Violations(step3(5, "0.00", null))).contains("sessionPacks[0].totalPrice");
        assertThat(step3Violations(step3(5, "50000.01", null))).containsExactly("sessionPacks[0].totalPrice");
        assertThat(step3Violations(step3(5, "10.005", null))).containsExactly("sessionPacks[0].totalPrice");
    }

    @Test
    void step3_perSessionShareRoundingToZero_rejectedWithTranslatedKey() {
        var violations = validator.validate(step3(100, "0.49", null));
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("sessionPacks[0].perSessionPriceValid");
            assertThat(v.getMessage()).startsWith("marketplace.packPerSessionPriceTooLow|");
        });
        // 0.50 / 100 = 0.005 rounds HALF_UP to 0.01, the smallest share createTier accepts; 0.49 rounds to 0.00.
        assertThat(validator.validate(step3(100, "0.50", null))).isEmpty();
    }

    @Test
    void step3_moreThanOnePackOrANullPack_rejected() {
        var pack = new ProfileBuilderStep3Request.SessionPackRequest(5, new BigDecimal("50"), null);
        var two = new ProfileBuilderStep3Request.SessionPackRequest(10, new BigDecimal("90"), null);
        assertThat(step3Violations(step3(List.of(pack, two)))).containsExactly("sessionPacks");
        assertThat(step3Violations(step3(java.util.Arrays.asList((ProfileBuilderStep3Request.SessionPackRequest) null))))
            .containsExactly("sessionPacks[0].<list element>");
    }
}
