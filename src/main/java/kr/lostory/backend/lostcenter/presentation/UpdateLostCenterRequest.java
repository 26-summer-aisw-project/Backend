package kr.lostory.backend.lostcenter.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record UpdateLostCenterRequest(
        @Size(min = 1, max = 255) String name,
        @Size(min = 1, max = 1000) String address,
        @Size(min = 1, max = 100) String contactPhone,
        @Valid CenterLocationRequest location,
        Boolean isActive
) {
}
