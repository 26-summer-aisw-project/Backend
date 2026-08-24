package kr.lostory.backend;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LostReportDatabaseFixture {

	private final JdbcTemplate jdbc;

	LostReportDatabaseFixture(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	Long user() {
		return jdbc.queryForObject("""
				INSERT INTO users (email, password_hash, display_name, status, role, created_at, updated_at)
				VALUES (?, 'hash', 'reporter', 'ACTIVE', 'USER', ?, ?) RETURNING id
				""", Long.class, UUID.randomUUID() + "@example.test",
				Timestamp.from(LostReportRadiusAndGuidanceIntegrationTest.NOW),
				Timestamp.from(LostReportRadiusAndGuidanceIntegrationTest.NOW));
	}

	Long center(String sourceKey, String name, String longitude, boolean active, String verificationStatus) {
		return jdbc.queryForObject("""
				INSERT INTO lost_centers
					(source_key, name, address, location, contact_phone, operating_hours, verification_status,
					 is_active, is_csv_managed, created_at, updated_at)
				VALUES (?, ?, 'address', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
					'02-0000-0000', 'always', ?, ?, false, ?, ?) RETURNING id
				""", Long.class, sourceKey, name, new BigDecimal(longitude),
				LostReportRadiusAndGuidanceIntegrationTest.LATITUDE, verificationStatus, active,
				Timestamp.from(LostReportRadiusAndGuidanceIntegrationTest.NOW),
				Timestamp.from(LostReportRadiusAndGuidanceIntegrationTest.NOW));
	}

	Long centerAtDistance(String sourceKey, double distanceMeters) {
		return jdbc.queryForObject("""
				INSERT INTO lost_centers
					(source_key, name, address, location, contact_phone, operating_hours, verification_status,
					 is_active, is_csv_managed, created_at, updated_at)
				VALUES (?, 'Boundary', 'address', ST_Project(
					ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, radians(90)),
					'02-0000-0000', 'always', 'official_verified', true, false, ?, ?) RETURNING id
				""", Long.class, sourceKey,
				LostReportRadiusAndGuidanceIntegrationTest.LONGITUDE,
				LostReportRadiusAndGuidanceIntegrationTest.LATITUDE,
				distanceMeters,
				Timestamp.from(LostReportRadiusAndGuidanceIntegrationTest.NOW),
				Timestamp.from(LostReportRadiusAndGuidanceIntegrationTest.NOW));
	}
}
