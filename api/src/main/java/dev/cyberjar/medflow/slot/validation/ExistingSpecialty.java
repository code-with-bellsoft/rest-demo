package dev.cyberjar.medflow.slot.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ExistingSpecialtyValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExistingSpecialty {
    String message() default "Specialty does not exist";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
