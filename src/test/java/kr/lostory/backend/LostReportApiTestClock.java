package kr.lostory.backend;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

final class LostReportApiTestClock extends Clock {
	private final AtomicReference<Instant> current;

	LostReportApiTestClock(Instant initial) {
		current = new AtomicReference<>(initial);
	}

	void set(Instant instant) {
		current.set(instant);
	}

	@Override
	public ZoneId getZone() {
		return ZoneId.of("UTC");
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return current.get();
	}

	@TestConfiguration
	static class Config {
		@Bean
		@Primary
		LostReportApiTestClock lostReportApiTestClock() {
			return new LostReportApiTestClock(Instant.parse("2026-08-25T00:00:00Z"));
		}
	}
}
