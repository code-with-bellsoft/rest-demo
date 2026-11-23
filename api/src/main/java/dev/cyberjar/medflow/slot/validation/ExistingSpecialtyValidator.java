package dev.cyberjar.medflow.slot.validation;

import dev.cyberjar.medflow.reference.ReferenceDataRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ExistingSpecialtyValidator implements ConstraintValidator<ExistingSpecialty, Long> {

    private final ReferenceDataRepository repository;

    public ExistingSpecialtyValidator(ReferenceDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isValid(Long id, ConstraintValidatorContext context) {
        return id == null || id <= 0 || repository.existsSpecialtyById(id);
    }
}
