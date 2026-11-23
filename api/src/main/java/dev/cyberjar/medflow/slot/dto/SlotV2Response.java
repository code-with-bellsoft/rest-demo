package dev.cyberjar.medflow.slot.dto;

import java.time.OffsetDateTime;

public record SlotV2Response(
        Long id,
        Long facilityId,
        String facilityName,
        Long specialtyId,
        String specialtyName,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Availability availability
) {
    public static SlotV2Response from(SlotResponse slot) {
        int remaining = slot.remainingCapacity();
        if (remaining < 0) {
            throw new IllegalStateException("Slot has more active bookings than capacity");
        }
        var availability = new Availability(slot.capacity(), slot.activeBookings(), remaining,
                remaining > 0 ? Status.AVAILABLE : Status.FULL);
        return new SlotV2Response(slot.id(), slot.facilityId(), slot.facilityName(),
                slot.specialtyId(), slot.specialtyName(), slot.startsAt(), slot.endsAt(), availability);
    }

    public record Availability(int capacity, int activeBookings, int remainingCapacity, Status status) {
    }

    public enum Status {
        AVAILABLE, FULL
    }
}
