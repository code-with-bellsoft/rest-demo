package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.slot.dto.CapacityRequest;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.dto.SlotResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    public List<SlotResponse> getAllSlots(@RequestParam(required = false) Long facilityId,
                                          @RequestParam(required = false) Long specialtyId) {
        return service.findSlots(facilityId, specialtyId);
    }

    @GetMapping("/{id}")
    public SlotResponse getSlotById(@PathVariable Long id) {
        return service.findSlotById(id);
    }

    @PostMapping
    public ResponseEntity<SlotResponse> createSlot(@RequestBody SlotRequest request) {
        SlotResponse slot = service.createSlot(request);
        return ResponseEntity.created(URI.create("/api/slots/" + slot.id())).body(slot);
    }

    @PatchMapping("/{id}")
    public SlotResponse updateSlot(@PathVariable Long id, @RequestBody CapacityRequest request) {
        if (request.capacity() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return service.updateSlot(id, request.capacity());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@PathVariable Long id) {
        service.deleteSlot(id);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void handleConstraintViolation() {
    }
}
