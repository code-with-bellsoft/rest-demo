package dev.cyberjar.medflow.slot;

import dev.cyberjar.medflow.slot.dto.CapacityRequest;
import dev.cyberjar.medflow.slot.dto.SlotRequest;
import dev.cyberjar.medflow.slot.dto.SlotResponse;
import dev.cyberjar.medflow.slot.dto.SlotV2Response;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
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

    @GetMapping(version = "1.0")
    public List<SlotResponse> getAllSlots(@Positive @RequestParam(required = false) Long facilityId,
                                          @Positive @RequestParam(required = false) Long specialtyId,
                                          HttpServletResponse response) {
        varyByVersion(response);
        return service.findSlots(facilityId, specialtyId);
    }

    @GetMapping(version = "2.0")
    public List<SlotV2Response> getAllSlotsV2(@Positive @RequestParam(required = false) Long facilityId,
                                             @Positive @RequestParam(required = false) Long specialtyId,
                                             HttpServletResponse response) {
        varyByVersion(response);
        return service.findSlots(facilityId, specialtyId).stream().map(SlotV2Response::from).toList();
    }

    @GetMapping(path = "/{id}", version = "1.0")
    public SlotResponse getSlotById(@Positive @PathVariable Long id, HttpServletResponse response) {
        varyByVersion(response);
        return service.findSlotById(id);
    }

    @GetMapping(path = "/{id}", version = "2.0")
    public SlotV2Response getSlotByIdV2(@Positive @PathVariable Long id, HttpServletResponse response) {
        varyByVersion(response);
        return SlotV2Response.from(service.findSlotById(id));
    }

    @PostMapping(version = "1.0")
    public ResponseEntity<SlotResponse> createSlot(@Valid @RequestBody SlotRequest request) {
        SlotResponse slot = service.createSlot(request);
        return ResponseEntity.created(slotLocation(slot)).body(slot);
    }

    @PostMapping(version = "2.0")
    public ResponseEntity<SlotV2Response> createSlotV2(@Valid @RequestBody SlotRequest request) {
        SlotResponse slot = service.createSlot(request);
        return ResponseEntity.created(slotLocation(slot)).body(SlotV2Response.from(slot));
    }

    @PatchMapping(path = "/{id}", version = "1.0")
    public SlotResponse updateSlot(@Positive @PathVariable Long id,
                                   @Valid @RequestBody CapacityRequest request) {
        return service.updateSlot(id, request.capacity());
    }

    @PatchMapping(path = "/{id}", version = "2.0")
    public SlotV2Response updateSlotV2(@Positive @PathVariable Long id,
                                       @Valid @RequestBody CapacityRequest request) {
        return SlotV2Response.from(service.updateSlot(id, request.capacity()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@Positive @PathVariable Long id) {
        service.deleteSlot(id);
    }

    private static URI slotLocation(SlotResponse slot) {
        return URI.create("/api/slots/" + slot.id());
    }

    private static void varyByVersion(HttpServletResponse response) {
        response.addHeader(HttpHeaders.VARY, "API-Version");
    }
}
