package dev.cyberjar.medflow.ui.dto;

import java.time.OffsetDateTime;

public record SlotDto(Long id, Long facilityId, String facilityName, Long specialtyId,
                      String specialtyName, OffsetDateTime startsAt, OffsetDateTime endsAt, int capacity,
                      int activeBookings, int remainingCapacity) {
}
