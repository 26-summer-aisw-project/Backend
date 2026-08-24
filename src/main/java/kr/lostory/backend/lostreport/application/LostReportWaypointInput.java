package kr.lostory.backend.lostreport.application;

import java.math.BigDecimal;

public record LostReportWaypointInput(int ordinal, BigDecimal latitude, BigDecimal longitude, String placeName) {
}
