package kr.lostory.backend;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.domain.UserStatus;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
@Transactional
class UserPersistenceIntegrationTest {

	private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void activeUserPersistsAndReloadsWithSingleRoleAfterV5() {
		String email = uniqueEmail();
		Instant beforeSave = Instant.now();

		User saved = userRepository.saveAndFlush(new User(email, HASH));
		entityManager.clear();
		User reloaded = userRepository.findByEmail(email).orElseThrow();
		Boolean userMigrationApplied = jdbcTemplate.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version = '5')",
				Boolean.class
		);

		assertThat(userMigrationApplied).isTrue();
		assertThat(reloaded.getId()).isEqualTo(saved.getId());
		assertThat(reloaded.getEmail()).isEqualTo(email);
		assertThat(reloaded.getDisplayName()).isEqualTo("User");
		assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(reloaded.getRole()).isEqualTo(UserRole.USER);
		assertThat(reloaded.getCreatedAt()).isAfterOrEqualTo(beforeSave.minusSeconds(1));
		assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(beforeSave.minusSeconds(1));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT password_hash FROM users WHERE id = ?",
			String.class,
			reloaded.getId()
		)).isEqualTo(HASH).isNotEqualTo("plaintext-password");
	}

	@Test
	void databaseRejectsNoncanonicalEmail() {
		assertThatThrownBy(() -> insertUser(" Mixed-" + UUID.randomUUID() + "@Example.test "))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause().hasMessageContaining("ck_users_email_canonical");
	}

	@Test
	void databaseRejectsControlWhitespaceAroundEmail() {
		assertThatThrownBy(() -> insertUser("\t" + uniqueEmail() + "\r"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause().hasMessageContaining("ck_users_email_canonical");
	}

	@Test
	void databaseRejectsCaseInsensitiveDuplicateEmail() {
		String email = uniqueEmail();
		insertUser(email);

		assertThatThrownBy(() -> insertUser(email.toUpperCase()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause().hasMessageContaining("users_email_ci_uq");
	}

	@Test
	void databaseRejectsInvalidRole() {
		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO users (email, password_hash, status, role, created_at) VALUES (?, ?, 'ACTIVE', 'OWNER', CURRENT_TIMESTAMP)",
			uniqueEmail(),
			HASH
		)).isInstanceOf(DataIntegrityViolationException.class)
			.rootCause().hasMessageContaining("ck_users_role");
	}

	private void insertUser(String email) {
		jdbcTemplate.update(
			"INSERT INTO users (email, password_hash, status, created_at) VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP)",
			email,
			HASH
		);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.test";
	}
}
