package kr.lostory.backend.user.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 320, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 60)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private UserStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 16)
	private Set<UserRole> roles = EnumSet.noneOf(UserRole.class);

	protected User() {
	}

	public User(String email, String passwordHash) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.status = UserStatus.ACTIVE;
		this.createdAt = Instant.now();
		this.roles.add(UserRole.USER);
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Set<UserRole> getRoles() {
		return Set.copyOf(roles);
	}
}
