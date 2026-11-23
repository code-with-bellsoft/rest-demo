package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.exception.NotFoundException;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.dto.SlotResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppointmentSlotService {

    private final AppointmentSlotRepository repository;

    public AppointmentSlotService(AppointmentSlotRepository repository) {
        this.repository = repository;
    }

    public List<SlotResponse> findSlots(Long facilityId, Long specialtyId) {
        return repository.findSlots(facilityId, specialtyId);
    }

    public SlotResponse findSlotById(Long id) {
        return repository.findSlotById(id).orElseThrow(() -> slotNotFound(id));
    }

    public SlotResponse createSlot(SlotRequest slot) {
        return repository.createSlot(slot);
    }

    public SlotResponse updateSlot(Long id, int newCapacity) {
        return repository.updateSlot(id, newCapacity).orElseThrow(() -> slotNotFound(id));
    }

    public void deleteSlot(Long id) {
        if (!repository.deleteSlot(id)) {
            throw slotNotFound(id);
        }
    }

    private NotFoundException slotNotFound(Long id) {
        return new NotFoundException("Slot with id " + id + " not found");
    }
}
