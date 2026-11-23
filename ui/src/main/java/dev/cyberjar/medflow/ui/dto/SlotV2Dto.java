package dev.cyberjar.medflow.ui.dto;

import java.time.OffsetDateTime;
import java.util.Objects;

public record SlotV2Dto(Long id, Long facilityId, String facilityName, Long specialtyId,
                        String specialtyName, OffsetDateTime startsAt, OffsetDateTime endsAt,
                        Availability availability) {

    public SlotV2Dto {
        Objects.requireNonNull(availability, "availability");
    }

    public SlotDto toGridSlot() {
        return new SlotDto(id, facilityId, facilityName, specialtyId, specialtyName, startsAt, endsAt,
                availability.capacity(), availability.activeBookings(), availability.remainingCapacity());
    }

    public record Availability(int capacity, int activeBookings, int remainingCapacity, String status) {
    }
}
