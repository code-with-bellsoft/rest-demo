package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.slot.dto.CapacityRequest;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.dto.SlotResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/slots")
public class AppointmentSlotController {

    private final AppointmentSlotService service;

    public AppointmentSlotController(AppointmentSlotService service) {
        this.service = service;
    }

    @GetMapping
    public List<SlotResponse> getAllSlots(@Positive @RequestParam(required = false) Long facilityId,
                                          @Positive @RequestParam(required = false) Long specialtyId) {
        return service.findSlots(facilityId, specialtyId);
    }

    @GetMapping("/{id}")
    public SlotResponse getSlotById(@Positive @PathVariable Long id) {
        return service.findSlotById(id);
    }

    @PostMapping
    public ResponseEntity<SlotResponse> createSlot(@Valid @RequestBody SlotRequest request) {
        SlotResponse slot = service.createSlot(request);
        return ResponseEntity.created(URI.create("/api/slots/" + slot.id())).body(slot);
    }

    @PatchMapping("/{id}")
    public SlotResponse updateSlot(@Positive @PathVariable Long id,
                                   @Valid @RequestBody CapacityRequest request) {
        return service.updateSlot(id, request.capacity());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@Positive @PathVariable Long id) {
        service.deleteSlot(id);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void handleConstraintViolation() {
    }
}
