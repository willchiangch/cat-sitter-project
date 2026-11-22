package com.petsitter.application.common.text;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointLengthValidator implements ConstraintValidator<CodePointLength, String> {

    private int min;
    private int max;
    private boolean strip;

    @Override
    public void initialize(CodePointLength annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
        this.strip = annotation.strip();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = strip ? TextRules.strippedLength(value) : TextRules.codePointLength(value);
        return length >= min && length <= max;
    }
}
