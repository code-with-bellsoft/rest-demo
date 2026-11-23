package dev.cyberjar.medflow.reference;

import dev.cyberjar.medflow.reference.ReferenceDataRepository.ReferenceEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ReferenceDataController {

    private final ReferenceDataRepository repository;

    public ReferenceDataController(ReferenceDataRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/facilities")
    public List<ReferenceEntry> getFacilities() {
        return repository.findFacilities();
    }

    @GetMapping("/specialties")
    public List<ReferenceEntry> getSpecialties() {
        return repository.findSpecialties();
    }
}
