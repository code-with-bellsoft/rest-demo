package dev.cyberjar.medflow.slot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CapacityRequest(@NotNull @Positive Integer capacity) {
}
