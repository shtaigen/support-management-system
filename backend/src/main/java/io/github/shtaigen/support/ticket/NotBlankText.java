package io.github.shtaigen.support.ticket;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string must not be {@code null}, must not be blank by {@link String#isBlank()} and must not
 * contain NUL characters. Unlike {@code @NotBlank}, which uses {@code trim()}, this treats Unicode
 * whitespace such as U+2003 as blank, the same as the domain. NUL is rejected because Postgres
 * cannot store it in text columns.
 */
@Documented
@Constraint(validatedBy = NotBlankTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface NotBlankText {

    String message() default "must not be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
