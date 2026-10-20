package io.github.shtaigen.support.ticket;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class NotBlankTextValidator implements ConstraintValidator<NotBlankText, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.indexOf('\u0000') >= 0) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("must not contain NUL characters")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
