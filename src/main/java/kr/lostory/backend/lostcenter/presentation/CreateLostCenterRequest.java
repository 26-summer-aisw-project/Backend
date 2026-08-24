package kr.lostory.backend.lostcenter.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateLostCenterRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 1000) String address,
        @NotBlank @Size(max = 100) String contactPhone,
        @NotNull @Valid CenterLocationRequest location
) {
}
