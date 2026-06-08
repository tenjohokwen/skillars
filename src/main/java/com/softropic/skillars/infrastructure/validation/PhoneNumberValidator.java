package com.softropic.skillars.infrastructure.validation;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import static net.logstash.logback.argument.StructuredArguments.kv;


@Slf4j
public class PhoneNumberValidator implements ConstraintValidator<Phone, PhoneNumber> {

    @Override
    public void initialize(Phone constraintAnnotation) {

    }

    @Override
    public boolean isValid(PhoneNumber phoneNumber, ConstraintValidatorContext context) {
        if (phoneNumber.getIso2Country() == null || phoneNumber.getPhone() == null || !isIso2CodeValid(phoneNumber.getIso2Country())) {
            return false;
        }
        try {
            PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();
            return phoneNumberUtil.isValidNumber(phoneNumberUtil.parse(phoneNumber.getPhone(),
                                                                       phoneNumber.getIso2Country()));
        }
        catch (NumberParseException e) {
            if (phoneNumber != null) {
                log.warn("Invalid phone number",
                        kv("operation", "phone_validation"),
                        kv("status", "INVALID"),
                        e);
            }
            return false;
        }
    }

    private boolean isIso2CodeValid(String iso2CountryCode) {
        return IsoLangUtil.isValidISO2Country(iso2CountryCode);
    }
}
