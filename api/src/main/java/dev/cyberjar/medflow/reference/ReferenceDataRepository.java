package dev.cyberjar.medflow.reference;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static dev.cyberjar.medflow.Tables.FACILITY;
import static dev.cyberjar.medflow.Tables.SPECIALTY;

@Repository
public class ReferenceDataRepository {

    private final DSLContext db;

    public ReferenceDataRepository(DSLContext db) {
        this.db = db;
    }

    public List<ReferenceEntry> findFacilities() {
        return db.select(FACILITY.ID, FACILITY.NAME)
                .from(FACILITY)
                .orderBy(FACILITY.NAME)
                .fetch(row -> new ReferenceEntry(row.value1(), row.value2()));
    }

    public List<ReferenceEntry> findSpecialties() {
        return db.select(SPECIALTY.ID, SPECIALTY.NAME)
                .from(SPECIALTY)
                .orderBy(SPECIALTY.NAME)
                .fetch(row -> new ReferenceEntry(row.value1(), row.value2()));
    }

    public boolean existsFacilityById(long id) {
        return db.fetchExists(FACILITY, FACILITY.ID.eq(id));
    }

    public boolean existsSpecialtyById(long id) {
        return db.fetchExists(SPECIALTY, SPECIALTY.ID.eq(id));
    }

    public record ReferenceEntry(Long id, String name) {
    }
}
