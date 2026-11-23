package dev.cyberjar.medflow.ui.dto;

import java.time.OffsetDateTime;

public record CreateSlotRequest(Long facilityId, Long specialtyId, OffsetDateTime startsAt,
                                OffsetDateTime endsAt, Integer capacity) {
}
