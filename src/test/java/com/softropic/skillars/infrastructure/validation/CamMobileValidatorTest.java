package com.softropic.skillars.infrastructure.validation;


import com.softropic.skillars.infrastructure.validation.PhoneNumberDto;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CamMobileValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"690022002", "+237698684749", "655684749", "698684749", "00237657684749"})
    void validateOrange(String phone) {
        final PhoneNumberDto phoneNumber = CamMobileValidator.validate(phone);
        assertThat(phoneNumber.getProvider()).isEqualTo(Provider.ORANGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"650037001", "651002300", "652009900", "653009300", "654001700", "+237688684749", "675684749", "688684749", "00237654684749"})
    void validateMTN(String phone) {
        final PhoneNumberDto phoneNumber = CamMobileValidator.validate(phone);
        assertThat(phoneNumber.getProvider()).isEqualTo(Provider.MTN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"660023001", "+237668684749", "665684749", "668684749", "00237667684749"})
    void validateNextTel(String phone) {
        final PhoneNumberDto phoneNumber = CamMobileValidator.validate(phone);
        assertThat(phoneNumber.getProvider()).isEqualTo(Provider.NEXTTEL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"",     // Invalid - doesn't match any pattern
            " ",     // Invalid - doesn't match any pattern
            "6 ",     // Invalid - doesn't match any pattern
            " str",     // Invalid - doesn't match any pattern
            "788684749",     // Invalid - doesn't start with 6
            "68868474",      // Invalid - too short
            "6886847499",    // Invalid - too long
            "68868474a",     // Invalid - contains letter
            "6880007499"     // 3 consecutive zeros
    })
    void validateInvalidNumber(String phone) {
        assertThatThrownBy(() -> CamMobileValidator.validate(phone)).isInstanceOf(CamMobileValidator.InvalidMobileNumberException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"688 684 749", "+237688 684 749", "+237 688 684 749", "00237 688 684 749", "00237688684 749"})
    void whenSpacesInNumberValidate_shouldFormat(String phone) {
        final PhoneNumberDto phoneNumber = CamMobileValidator.validate(phone);
        assertThat(phoneNumber.getPhone()).isEqualTo("688684749");
    }
}