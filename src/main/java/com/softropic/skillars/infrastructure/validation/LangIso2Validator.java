package com.softropic.skillars.infrastructure.validation;


import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class LangIso2Validator implements ConstraintValidator<LangIso2, String> {

    @Override
    public void initialize(LangIso2 constraintAnnotation) {
        ConstraintValidator.super.initialize(constraintAnnotation);
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return IsoLangUtil.isValidISO2Language(value);
    }
}
