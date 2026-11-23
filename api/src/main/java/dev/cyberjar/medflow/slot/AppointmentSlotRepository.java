package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.enums.BookingStatus;
import dev.cyberjar.medflow.exception.SlotConflictException;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.dto.SlotResponse;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static dev.cyberjar.medflow.Tables.APPOINTMENT_SLOT;
import static dev.cyberjar.medflow.Tables.BOOKING;
import static dev.cyberjar.medflow.Tables.FACILITY;
import static dev.cyberjar.medflow.Tables.SPECIALTY;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.noCondition;

@Repository
public class AppointmentSlotRepository {

    private static final Field<Integer> ACTIVE_BOOKINGS = count().as("active_bookings");

    private final DSLContext db;

    public AppointmentSlotRepository(DSLContext db) {
        this.db = db;
    }

    public List<SlotResponse> findSlots(Long facilityId, Long specialtyId) {
        return selectSlots(db)
                .where(facilityId == null ? noCondition() : APPOINTMENT_SLOT.FACILITY_ID.eq(facilityId))
                .and(specialtyId == null ? noCondition() : APPOINTMENT_SLOT.SPECIALTY_ID.eq(specialtyId))
                .orderBy(APPOINTMENT_SLOT.STARTS_AT, APPOINTMENT_SLOT.ID)
                .fetch(this::toResponse);
    }

    public Optional<SlotResponse> findSlotById(Long id) {
        return selectSlots(db)
                .where(APPOINTMENT_SLOT.ID.eq(id))
                .fetchOptional(this::toResponse);
    }

    public SlotResponse createSlot(SlotRequest request) {
        return db.transactionResult(configuration -> {
            DSLContext ctx = DSL.using(configuration);
            Long id = ctx.insertInto(APPOINTMENT_SLOT)
                    .set(APPOINTMENT_SLOT.FACILITY_ID, request.facilityId())
                    .set(APPOINTMENT_SLOT.SPECIALTY_ID, request.specialtyId())
                    .set(APPOINTMENT_SLOT.STARTS_AT, request.startsAt())
                    .set(APPOINTMENT_SLOT.ENDS_AT, request.endsAt())
                    .set(APPOINTMENT_SLOT.CAPACITY, request.capacity())
                    .returningResult(APPOINTMENT_SLOT.ID)
                    .fetchSingle()
                    .value1();
            return selectSlots(ctx).where(APPOINTMENT_SLOT.ID.eq(id)).fetchSingle(this::toResponse);
        });
    }

    public Optional<SlotResponse> updateSlot(Long id, int newCapacity) {
        return db.transactionResult(configuration -> {
            DSLContext ctx = DSL.using(configuration);
            if (!lockSlot(ctx, id)) {
                return Optional.empty();
            }

            int activeBookings = countActiveBookings(ctx, id);
            if (newCapacity < activeBookings) {
                throw new SlotConflictException("Cannot set capacity below the " + activeBookings + " active bookings.");
            }

            ctx.update(APPOINTMENT_SLOT)
                    .set(APPOINTMENT_SLOT.CAPACITY, newCapacity)
                    .where(APPOINTMENT_SLOT.ID.eq(id))
                    .execute();
            return Optional.of(selectSlots(ctx).where(APPOINTMENT_SLOT.ID.eq(id)).fetchSingle(this::toResponse));
        });
    }

    public boolean deleteSlot(Long id) {
        return db.transactionResult(configuration -> {
            DSLContext ctx = DSL.using(configuration);
            if (!lockSlot(ctx, id)) {
                return false;
            }

            if (countActiveBookings(ctx, id) > 0) {
                throw new SlotConflictException("Cannot delete a slot with active bookings.");
            }

            return ctx.deleteFrom(APPOINTMENT_SLOT).where(APPOINTMENT_SLOT.ID.eq(id)).execute() == 1;
        });
    }

    private boolean lockSlot(DSLContext ctx, Long id) {
        return ctx.select(APPOINTMENT_SLOT.ID)
                .from(APPOINTMENT_SLOT)
                .where(APPOINTMENT_SLOT.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .isPresent();
    }

    private int countActiveBookings(DSLContext ctx, Long id) {
        return ctx.fetchCount(
                BOOKING,
                BOOKING.APPOINTMENT_SLOT_ID.eq(id),
                activeBooking()
        );
    }

    private Condition activeBooking() {
        return BOOKING.STATUS.in(BookingStatus.RESERVED, BookingStatus.CONFIRMED);
    }

    private SelectOnConditionStep<?> selectSlots(DSLContext ctx) {
        var bookingCounts = ctx.select(BOOKING.APPOINTMENT_SLOT_ID, ACTIVE_BOOKINGS)
                .from(BOOKING)
                .where(activeBooking())
                .groupBy(BOOKING.APPOINTMENT_SLOT_ID)
                .asTable("booking_counts");
        return ctx.select(APPOINTMENT_SLOT.ID, APPOINTMENT_SLOT.FACILITY_ID, FACILITY.NAME,
                        APPOINTMENT_SLOT.SPECIALTY_ID, SPECIALTY.NAME,
                        APPOINTMENT_SLOT.STARTS_AT, APPOINTMENT_SLOT.ENDS_AT, APPOINTMENT_SLOT.CAPACITY,
                        coalesce(bookingCounts.field(ACTIVE_BOOKINGS), 0).as(ACTIVE_BOOKINGS))
                .from(APPOINTMENT_SLOT)
                .join(FACILITY).on(FACILITY.ID.eq(APPOINTMENT_SLOT.FACILITY_ID))
                .join(SPECIALTY).on(SPECIALTY.ID.eq(APPOINTMENT_SLOT.SPECIALTY_ID))
                .leftJoin(bookingCounts).on(bookingCounts.field(BOOKING.APPOINTMENT_SLOT_ID).eq(APPOINTMENT_SLOT.ID));
    }

    private SlotResponse toResponse(Record row) {
        int capacity = row.get(APPOINTMENT_SLOT.CAPACITY);
        int activeBookings = row.get(ACTIVE_BOOKINGS);
        return new SlotResponse(
                row.get(APPOINTMENT_SLOT.ID),
                row.get(APPOINTMENT_SLOT.FACILITY_ID),
                row.get(FACILITY.NAME),
                row.get(APPOINTMENT_SLOT.SPECIALTY_ID),
                row.get(SPECIALTY.NAME),
                row.get(APPOINTMENT_SLOT.STARTS_AT),
                row.get(APPOINTMENT_SLOT.ENDS_AT),
                capacity,
                activeBookings,
                capacity - activeBookings
        );
    }
}
