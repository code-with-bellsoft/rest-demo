package dev.cyberjar.medflow.slot.validation;

import dev.cyberjar.medflow.slot.dto.SlotRequest;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EndAfterStartValidatorTest {

    private final OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);

    @ParameterizedTest
    @CsvSource({"1, true", "0, false", "-1, false"})
    void comparesInstantsAndReportsViolationsOnEndsAt(int minutesAfterStart, boolean valid) {
        var end = start.plusMinutes(minutesAfterStart).withOffsetSameInstant(ZoneOffset.ofHours(-5));
        var context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
        when(context.getDefaultConstraintMessageTemplate()).thenReturn("Invalid end time");

        assertThat(new EndAfterStartValidator().isValid(new SlotRequest(null, null, start, end, 2), context))
                .isEqualTo(valid);
        if (!valid) {
            verify(context).disableDefaultConstraintViolation();
            verify(context.buildConstraintViolationWithTemplate("Invalid end time")
                    .addPropertyNode("endsAt")).addConstraintViolation();
        }
    }

    @Test
    void leavesMissingValuesToRequiredFieldValidation() {
        var validator = new EndAfterStartValidator();
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(new SlotRequest(null, null, null, start, 2), null)).isTrue();
        assertThat(validator.isValid(new SlotRequest(null, null, start, null, 2), null)).isTrue();
    }
}
