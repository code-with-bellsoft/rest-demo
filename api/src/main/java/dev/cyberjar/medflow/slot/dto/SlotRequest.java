package dev.cyberjar.medflow.slot.dto;

import dev.cyberjar.medflow.slot.validation.EndAfterStart;
import dev.cyberjar.medflow.slot.validation.ExistingFacility;
import dev.cyberjar.medflow.slot.validation.ExistingSpecialty;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

@EndAfterStart
public record SlotRequest(
        @NotNull @Positive @ExistingFacility Long facilityId,
        @NotNull @Positive @ExistingSpecialty Long specialtyId,
        @NotNull @Future OffsetDateTime startsAt,
        @NotNull @Future OffsetDateTime endsAt,
        @NotNull @Positive Integer capacity
) {
}
