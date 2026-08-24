package kr.lostory.backend;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class MatchScoringDatabaseFixture {

	static final Instant LOST_FROM = Instant.parse("2030-01-01T10:00:00Z");
	static final Instant LOST_TO = Instant.parse("2030-01-01T11:00:00Z");
	static final BigDecimal LONGITUDE = new BigDecimal("126.9780");
	static final BigDecimal LATITUDE = new BigDecimal("37.5665");

	private final JdbcTemplate jdbc;

	MatchScoringDatabaseFixture(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	void clean() {
		jdbc.update("DELETE FROM match_candidates");
		jdbc.update("DELETE FROM report_waypoints");
		jdbc.update("DELETE FROM lost_reports");
		jdbc.update("DELETE FROM item_features");
		jdbc.update("DELETE FROM found_item_vision_jobs");
		jdbc.update("DELETE FROM found_item_images");
		jdbc.update("DELETE FROM found_items");
		jdbc.update("DELETE FROM lost_centers");
		jdbc.update("DELETE FROM users");
	}

	Long user() {
		return jdbc.queryForObject("""
				INSERT INTO users (email, password_hash, display_name, status, role, created_at, updated_at)
				VALUES (?, 'hash', 'matcher', 'ACTIVE', 'USER', clock_timestamp(), clock_timestamp())
				RETURNING id
				""", Long.class, UUID.randomUUID() + "@example.test");
	}

	Long report(Long reporterId, String category, String description, int radius) {
		Long reportId = jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'test-radius', '[]', true, 'legacy-unmatched', 'OPEN',
				        clock_timestamp() + interval '14 days', clock_timestamp(), clock_timestamp())
				RETURNING id
				""", Long.class, reporterId, category, Timestamp.from(LOST_FROM), Timestamp.from(LOST_TO),
				description, radius, radius);
		jdbc.update("""
				INSERT INTO report_waypoints (report_id, ordinal, place_name, location, created_at)
				VALUES (?, 1, 'route', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, clock_timestamp())
				""", reportId, LONGITUDE, LATITUDE);
		return reportId;
	}

	Long activeItem(Long finderId, String category, Instant foundAt, double distanceMeters) {
		return item(finderId, category, foundAt, distanceMeters, "ACTIVE",
				"clock_timestamp() + interval '14 days'", "clock_timestamp()");
	}

	Long terminalItem(Long finderId, String category, String status, double distanceMeters) {
		return item(finderId, category, LOST_FROM, distanceMeters, status,
				"clock_timestamp() + interval '14 days'", "clock_timestamp()");
	}

	Long expiredActiveItem(Long finderId, String category, double distanceMeters) {
		return item(finderId, category, LOST_FROM, distanceMeters, "ACTIVE",
				"clock_timestamp() - interval '1 day'", "clock_timestamp() - interval '2 days'");
	}

	Long draftItem(Long finderId) {
		return jdbc.queryForObject("""
				INSERT INTO found_items
				    (finder_id, handover_status, status, vision_status, analysis_generation,
				     created_at, updated_at, draft_expires_at)
				VALUES (?, 'NONE', 'DRAFT', 'PENDING', 0, clock_timestamp(), clock_timestamp(),
				        clock_timestamp() + interval '1 day')
				RETURNING id
				""", Long.class, finderId);
	}

	Long pendingHandoverItem(Long finderId, String category, double distanceMeters) {
		Long centerId = jdbc.queryForObject("""
				INSERT INTO lost_centers
				    (source_key, name, address, location, contact_phone, operating_hours,
				     verification_status, is_active, is_csv_managed,
				     created_at, updated_at)
				VALUES (?, 'center', 'address', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
				        '02-0000-0000', 'always', 'official_verified', true, false,
				        clock_timestamp(), clock_timestamp())
				RETURNING id
				""", Long.class, UUID.randomUUID().toString(), LONGITUDE, LATITUDE);
		return jdbc.queryForObject("""
				INSERT INTO found_items
				    (finder_id, name, category, description, found_at, found_location, storage_method,
				     center_id, handover_status, status, vision_status, analysis_generation,
				     created_at, updated_at, expired_at)
				VALUES (?, 'item', ?, 'description', ?, ST_Project(
				        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, radians(90)),
				        'HANDED_TO_CENTER', ?, 'NONE', 'PENDING_HANDOVER', 'READY', 0,
				        clock_timestamp(), clock_timestamp(), clock_timestamp() + interval '14 days')
				RETURNING id
				""", Long.class, finderId, category, Timestamp.from(LOST_FROM), LONGITUDE, LATITUDE,
				distanceMeters, centerId);
	}

	void feature(Long itemId, String kind, String value, int ordinal, String source, String visibility) {
		jdbc.update("""
				INSERT INTO item_features
				    (item_id, kind, feature_value, ordinal, source, visibility, confidence, created_at)
				VALUES (?, ?, ?, ?, ?, ?, null, clock_timestamp())
				""", itemId, kind, value, ordinal, source, visibility);
	}

	private Long item(
			Long finderId,
			String category,
			Instant foundAt,
			double distanceMeters,
			String status,
			String expiredAtExpression,
			String updatedAtExpression
	) {
		return jdbc.queryForObject("""
				INSERT INTO found_items
				    (finder_id, name, category, description, found_at, found_location, storage_method,
				     handover_status, status, vision_status, analysis_generation, created_at, updated_at, expired_at)
				VALUES (?, 'item', ?, 'description', ?, ST_Project(
				        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, radians(90)),
				        'LEFT_IN_PLACE', 'NONE', ?, 'READY', 0,
				        clock_timestamp() - interval '3 days', """ + updatedAtExpression + ", "
				        + expiredAtExpression + ") RETURNING id",
				Long.class, finderId, category, Timestamp.from(foundAt), LONGITUDE, LATITUDE,
				distanceMeters, status);
	}
}
