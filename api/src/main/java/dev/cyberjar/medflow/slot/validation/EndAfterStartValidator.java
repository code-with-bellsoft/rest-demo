package dev.cyberjar.medflow.slot.validation;

import dev.cyberjar.medflow.slot.dto.SlotRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EndAfterStartValidator implements ConstraintValidator<EndAfterStart, SlotRequest> {

    @Override
    public boolean isValid(SlotRequest request, ConstraintValidatorContext context) {
        if (request == null || request.startsAt() == null || request.endsAt() == null
                || request.endsAt().isAfter(request.startsAt())) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("endsAt")
                .addConstraintViolation();
        return false;
    }
}
