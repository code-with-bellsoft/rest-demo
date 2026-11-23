package dev.cyberjar.medflow.slot.dto;

import java.time.OffsetDateTime;

public record SlotRequest(
        Long facilityId,
        Long specialtyId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer capacity
) {
}
