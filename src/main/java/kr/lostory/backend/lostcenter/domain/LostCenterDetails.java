package kr.lostory.backend.lostcenter.domain;

import java.math.BigDecimal;

public record LostCenterDetails(
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String contactPhone,
        Boolean active
) {
}
