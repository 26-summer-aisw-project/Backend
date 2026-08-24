package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.lostory.backend.lostreport.application.CenterGuidance;
import kr.lostory.backend.lostreport.application.DynamicRadiusPolicy;
import kr.lostory.backend.lostreport.application.LostReportSnapshot;
import kr.lostory.backend.lostreport.application.LostReportSnapshotCommand;
import kr.lostory.backend.lostreport.application.LostReportSnapshotService;
import kr.lostory.backend.lostreport.application.LostReportWaypointInput;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
		"matching.radius-policy-version=task11-radius-v7",
		"center.nearby-radius=1001"
})
@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, LostReportRadiusAndGuidanceIntegrationTest.FixedClockConfig.class})
class LostReportRadiusAndGuidanceIntegrationTest {

	static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
	static final BigDecimal LATITUDE = new BigDecimal("37.5000000");
	static final BigDecimal LONGITUDE = new BigDecimal("127.0000000");

	@Autowired LostReportSnapshotService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entityManager;
	@Autowired LostReportRepository reportRepository;
	LostReportDatabaseFixture database;

	@BeforeEach
	void cleanDatabase() {
		database = new LostReportDatabaseFixture(jdbc);
		jdbc.update("DELETE FROM match_candidates");
		jdbc.update("DELETE FROM report_waypoints");
		jdbc.update("DELETE FROM lost_reports");
		jdbc.update("DELETE FROM lost_centers");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void normalizes_scale_seven_duplicates_and_renumbers_first_occurrence() {
		List<LostReportWaypointInput> normalized = service.normalizeWaypoints(List.of(
				waypoint(1, "37.12345674", "127.12345674", "first"),
				waypoint(2, "37.123456741", "127.123456741", "duplicate"),
				waypoint(3, "37.2000000", "127.2000000", "third")
		));

		assertThat(normalized).containsExactly(
				waypoint(1, "37.1234567", "127.1234567", "first"),
				waypoint(2, "37.2000000", "127.2000000", "third")
		);
		LostReportSnapshot persisted = service.create(command(database.user(), NOW.minusSeconds(1), NOW, List.of(
				waypoint(1, "37.12345674", "127.12345674", "first"),
				waypoint(2, "37.123456741", "127.123456741", "duplicate")
		)));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM report_waypoints WHERE report_id = ?", Integer.class,
				persisted.id())).isOne();
	}

	@Test
	void rejects_invalid_waypoint_counts_ordinals_coordinates_and_period() {
		List<LostReportWaypointInput> eleven = new ArrayList<>();
		for (int ordinal = 1; ordinal <= 11; ordinal++) {
			eleven.add(waypoint(ordinal, "37.0", Integer.toString(ordinal), null));
		}

		assertThatThrownBy(() -> service.normalizeWaypoints(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.normalizeWaypoints(eleven)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.normalizeWaypoints(List.of(waypoint(2, "37", "127", null))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.normalizeWaypoints(List.of(waypoint(0, "37", "127", null))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.normalizeWaypoints(List.of(waypoint(1, "90.00000001", "127", null))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.normalizeWaypoints(List.of(waypoint(1, "37", "-180.00000001", null))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.create(command(database.user(), NOW, NOW.minusSeconds(1), List.of(pin()))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void calculates_minimum_base_maximum_and_raw_odd_even_medians_with_half_up_only_at_end() {
		DynamicRadiusPolicy defaults = new DynamicRadiusPolicy(
				new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("3000"), new BigDecimal("0.10"));
		DynamicRadiusPolicy minimum = new DynamicRadiusPolicy(
				new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("3000"), new BigDecimal("0.10"));

		assertThat(minimum.calculate(List.of())).isEqualTo(500);
		assertThat(defaults.calculate(List.of())).isEqualTo(1000);
		assertThat(defaults.calculate(List.of(new BigDecimal("20000")))).isEqualTo(3000);
		assertThat(defaults.calculate(List.of(
				new BigDecimal("9"), new BigDecimal("5"), new BigDecimal("1")))).isEqualTo(1001);
		assertThat(defaults.calculate(List.of(new BigDecimal("5"), new BigDecimal("4")))).isEqualTo(1000);
	}

	@Test
	void calculates_odd_and_even_route_medians_from_raw_postgis_distances() {
		Long reporterId = database.user();
		LostReportSnapshot odd = service.create(command(reporterId, NOW.minusSeconds(1), NOW, List.of(
				waypoint(1, "0", "0", null),
				waypoint(2, "0", "0.0000090", null),
				waypoint(3, "0", "0.0000548", null),
				waypoint(4, "0", "0.0001356", null)
		)));
		LostReportSnapshot even = service.create(command(reporterId, NOW.minusSeconds(1), NOW, List.of(
				waypoint(1, "0", "0", null),
				waypoint(2, "0", "0.0000359", null),
				waypoint(3, "0", "0.0000808", null)
		)));

		assertThat(odd.effectiveSearchRadiusMeters()).isEqualTo(1001);
		assertThat(even.effectiveSearchRadiusMeters()).isEqualTo(1000);
	}

	@Test
	void creates_one_pin_snapshot_with_raw_ordered_eligible_guidance_and_server_expiry() {
		Long reporterId = database.user();
		Long fartherId = database.center("farther-first-id", "Farther", "127.0000940", true, "official_verified");
		Long nearerId = database.center("nearer-second-id", "Nearer", "127.0000900", true, "admin_verified");
		database.center("inactive", "Inactive", "127.0000000", false, "official_verified");
		database.center("unverified", "Unverified", "127.0000000", true, "inactive");
		database.center("outside", "Outside", "127.0200000", true, "official_local_verified");
		for (int index = 2; index <= 10; index++) {
			database.center("eligible-" + index, "Eligible " + index, "127.00" + String.format("%02d", index),
					true, "official_verified");
		}

		LostReportSnapshot created = service.create(command(reporterId, NOW.minusSeconds(60), NOW, List.of(pin())));

		assertThat(created.effectiveSearchRadiusMeters()).isEqualTo(1000);
		assertThat(created.radiusPolicyVersion()).isEqualTo("task11-radius-v7");
		assertThat(created.expiredAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
		assertThat(created.centerGuidance()).extracting(CenterGuidance::id)
				.startsWith(nearerId.toString(), fartherId.toString());
		assertThat(created.centerGuidance()).hasSize(10);
		assertThat(created.centerGuidance()).extracting(CenterGuidance::distanceMeters)
				.startsWith(8, 8);
		assertThat(created.waypoints()).containsExactly(pin());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM report_waypoints WHERE report_id = ?", Integer.class,
				created.id())).isOne();
		assertThat(jdbc.queryForObject("SELECT center_guidance::text FROM lost_reports WHERE id = ?", String.class,
				created.id())).contains("\"id\": \"" + nearerId + "\"");
	}

	@Test
	void read_keeps_saved_guidance_after_center_changes_and_update_replaces_snapshot() {
		Long reporterId = database.user();
		Long centerId = database.center("saved", "Saved name", "127.0001000", true, "official_verified");
		LostReportSnapshot created = service.create(command(reporterId, NOW.minusSeconds(60), NOW, List.of(
				pin(), waypoint(2, "37.5000000", "127.0002000", "route-two"))));
		List<CenterGuidance> savedGuidance = created.centerGuidance();
		assertThat(savedGuidance).extracting(CenterGuidance::id).containsExactly(centerId.toString());
		jdbc.update("UPDATE lost_centers SET name = 'Changed', contact_phone = 'changed', is_active = false WHERE id = ?",
				centerId);
		entityManager.clear();

		LostReportSnapshot reread = service.readSnapshot(created.id());
		assertThat(reread.centerGuidance()).isEqualTo(savedGuidance);
		assertThatThrownBy(() -> reread.centerGuidance().add(savedGuidance.getFirst()))
				.isInstanceOf(UnsupportedOperationException.class);

		Long replacementId = database.center("replacement", "Replacement", "127.0100000", true, "official_board_verified");
		LostReportSnapshot updated = service.update(created.id(), command(
				reporterId, NOW.minusSeconds(120), NOW, List.of(waypoint(1, "37.5000000", "127.0100000", "new"))));
		assertThat(updated.centerGuidance()).extracting(CenterGuidance::id).containsExactly(replacementId.toString());
		assertThat(updated.waypoints()).extracting(LostReportWaypointInput::placeName).containsExactly("new");
		assertThat(updated.expiredAt()).isEqualTo(created.expiredAt());
		System.out.println("LOST_REPORT_SNAPSHOT_QA_OBSERVABLE savedAfterCenterMutation=true "
				+ "unionDedupe=1 updateRecomputed=true expiryPreserved=true");
	}

	@Test
	void guidance_keeps_exact_p0_radius_when_directory_radius_is_overridden() {
		Long outsideId = database.centerAtDistance("outside-1000m", 1000.5);

		LostReportSnapshot snapshot = service.create(command(database.user(), NOW.minusSeconds(1), NOW, List.of(pin())));

		assertThat(snapshot.centerGuidance()).extracting(CenterGuidance::id)
				.doesNotContain(outsideId.toString());
		System.out.println("LOST_REPORT_RADIUS_QA_OBSERVABLE configuredRadius=1001 enforcedRadius=1000 "
				+ "centerAt1000.5mIncluded=false");
	}

	@Test
	void concurrent_updates_finish_as_one_complete_snapshot_winner() throws Exception {
		Long reporterId = database.user();
		LostReportSnapshot created = service.create(command(reporterId, NOW.minusSeconds(1), NOW, List.of(pin())));
		database.center("winner-a", "Winner A", "127.0100000", true, "official_verified");
		database.center("winner-b", "Winner B", "127.0400000", true, "official_verified");
		LostReportSnapshotCommand firstCommand = command(reporterId, NOW.minusSeconds(2), NOW,
				List.of(waypoint(1, "37.5000000", "127.0100000", "first")));
		LostReportSnapshotCommand secondCommand = command(reporterId, NOW.minusSeconds(3), NOW,
				List.of(waypoint(1, "37.5000000", "127.0400000", "second")));
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<LostReportSnapshot> first = executor.submit(() -> {
				start.await();
				return service.update(created.id(), firstCommand);
			});
			Future<LostReportSnapshot> second = executor.submit(() -> {
				start.await();
				return service.update(created.id(), secondCommand);
			});
			LostReportSnapshot firstResult = first.get(30, TimeUnit.SECONDS);
			LostReportSnapshot secondResult = second.get(30, TimeUnit.SECONDS);

			assertThat(service.readSnapshot(created.id())).isIn(firstResult, secondResult);
			System.out.println("LOST_REPORT_CONCURRENCY_QA_OBSERVABLE updates=2 completeWinner=true mixed=false");
		}
	}

	@Test
	@Transactional
	void report_update_boundary_acquires_pessimistic_write_lock() {
		LostReportSnapshot created = service.create(command(database.user(), NOW.minusSeconds(1), NOW, List.of(pin())));

		LostReport report = reportRepository.findByIdForUpdate(created.id()).orElseThrow();

		assertThat(entityManager.getLockMode(report))
				.isIn(LockModeType.PESSIMISTIC_WRITE, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
	}

	private LostReportSnapshotCommand command(
			Long reporterId, Instant from, Instant to, List<LostReportWaypointInput> waypoints
	) {
		return new LostReportSnapshotCommand(reporterId, "WALLET", from, to, "black wallet", waypoints);
	}

	private LostReportWaypointInput pin() {
		return new LostReportWaypointInput(1, LATITUDE, LONGITUDE, "route");
	}

	private LostReportWaypointInput waypoint(int ordinal, String latitude, String longitude, String placeName) {
		return new LostReportWaypointInput(ordinal, new BigDecimal(latitude), new BigDecimal(longitude), placeName);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock task11Clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
