package kr.lostory.backend;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.domain.UserStatus;
import kr.lostory.backend.user.repository.UserRepository;
import org.hibernate.Hibernate;
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
	void activeUserPersistsAndReloadsWithRolesAfterV2() {
		String email = uniqueEmail();
		Instant beforeSave = Instant.now();

		User saved = userRepository.saveAndFlush(new User(email, HASH));
		entityManager.clear();
		User reloaded = userRepository.findByEmail(email).orElseThrow();
		String latestMigration = jdbcTemplate.queryForObject(
			"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
			String.class
		);

		assertThat(latestMigration).isEqualTo("2");
		assertThat(reloaded.getId()).isEqualTo(saved.getId());
		assertThat(reloaded.getEmail()).isEqualTo(email);
		assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(reloaded.getCreatedAt()).isAfterOrEqualTo(beforeSave);
		assertThat(Hibernate.isPropertyInitialized(reloaded, "roles")).isTrue();
		assertThat(reloaded.getRoles()).containsExactly(UserRole.USER);
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
	void databaseRejectsDuplicateEmail() {
		String email = uniqueEmail();
		insertUser(email);

		assertThatThrownBy(() -> insertUser(email))
			.isInstanceOf(DataIntegrityViolationException.class)
			.rootCause().hasMessageContaining("uk_users_email");
	}

	@Test
	void databaseRejectsInvalidRole() {
		Long userId = jdbcTemplate.queryForObject(
			"INSERT INTO users (email, password_hash, status, created_at) VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP) RETURNING id",
			Long.class,
			uniqueEmail(),
			HASH
		);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO user_roles (user_id, role) VALUES (?, 'OWNER')",
			userId
		)).isInstanceOf(DataIntegrityViolationException.class)
			.rootCause().hasMessageContaining("ck_user_roles_role");
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
