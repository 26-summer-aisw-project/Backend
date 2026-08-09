# LOSTORY JWT Login Implementation Plan

Status: Ready to implement  
Scope: P0 user registration, JWT login, current-user lookup, and their security boundary  
Source: [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md), sections 2, 3.2, 4, 5.1, 6, and 8.1

## 1. Goal and done condition

Deliver stateless authentication for the shared LOSTORY account model:

```text
POST /api/v1/auth/signup  -> create a USER account
POST /api/v1/auth/login   -> verify BCrypt password and issue a signed access token
GET  /api/v1/users/me     -> return the authenticated user's safe profile
```

Done means all of the following are observable:

- A password is stored only as a BCrypt hash.
- A valid login receives a short-lived signed JWT.
- A missing, expired, malformed, or altered token receives `401` JSON in the existing `ErrorResponse` shape.
- A valid token can call `/api/v1/users/me`.
- No DTO, token, log, or error response exposes a password or password hash.
- `./gradlew test` passes with the Flyway schema running in Testcontainers.

## 2. Fixed P0 decisions

- Use `/api/v1` for every new endpoint. The source plan states this globally even though its API table omits the prefix.
- Keep signup and login separate: signup returns `201 UserResponse`; login returns `200 LoginResponse`. Do not silently log a new user in.
- Use one global account with `USER` and `ADMIN` roles. Do not add center roles to a JWT; future center actions must check `center_memberships` in the database.
- Use Spring Security's resource-server JWT support, not a handwritten `OncePerRequestFilter` or JWT parser.
- For this single P0 application, use an HMAC-signed JWT with one Base64-encoded secret supplied by the environment. Use `HS256`; move to an asymmetric key only if token issuance and API validation become separate services.
- Require `JWT_ISSUER` and `JWT_SECRET` at startup. Allow only `JWT_ACCESS_TOKEN_TTL` to default to `PT15M` for local/test use.
- JWT claims are limited to `iss`, `sub` (numeric user ID), `iat`, `exp`, and global `roles`. JWTs are signed, not encrypted.

## 3. Explicit non-goals

- Refresh tokens, logout/revocation storage, social login, email verification, password-reset flows, and role-management APIs.
- Login rate limiting and audit-log persistence; the source plan schedules those in P0-5 after the audit module exists.
- Center-membership authorization helpers; no center model exists yet.
- Deleting `event` and `organization`. That is a separate P0-0 action requiring confirmation that no operating data exists.

## 4. Execution plan

### Task 0 — Make the persistence baseline usable

**Depends on:** nothing.  
**Files:** `src/main/resources/application*.properties`, `src/main/resources/db/migration/`, `src/test/java/kr/lostory/backend/PostgresTestContainerConfig.java`.

1. Remove the global exclusions for DataSource, Hibernate JPA, and Flyway from `application.properties`; retain `UserDetailsServiceAutoConfiguration` exclusion because this design does not use form-login user details.
2. Keep local/test datasource and Flyway configuration profile-specific, with `spring.jpa.hibernate.ddl-auto=none`.
3. Add the standard Flyway migration directory. If P0-0 is not already complete, add/retain V1 for PostGIS and switch the test container to a PostGIS image before adding V2.
4. Do not use Hibernate schema generation for any auth table.

**Verify:** an empty local/test database applies migrations; existing health/context tests still pass.

### Task 1 — Add the user schema and JPA model

**Depends on:** Task 0.  
**Files:**

- `src/main/resources/db/migration/V2__create_users_and_user_roles.sql`
- `src/main/java/kr/lostory/backend/user/domain/User.java`
- `src/main/java/kr/lostory/backend/user/domain/UserRole.java`
- `src/main/java/kr/lostory/backend/user/domain/UserStatus.java`
- `src/main/java/kr/lostory/backend/user/repository/UserRepository.java`

1. Create `users` with BIGINT identity ID, normalized unique email, non-null `password_hash`, status, and UTC `created_at`.
2. Create `user_roles` with a foreign key to `users`, a `USER`/`ADMIN` check constraint, and unique `(user_id, role)`.
3. Map roles as a small `Set<UserRole>` collection. Keep it lazy by default.
4. Give each new user `ACTIVE` status and exactly one `USER` role. Do not invent account-suspension workflows in P0.
5. Add only these repository methods:

   ```java
   boolean existsByEmail(String email);
   @EntityGraph(attributePaths = "roles")
   Optional<User> findByEmail(String email);
   Optional<User> findByIdAndStatus(Long id, UserStatus status);
   ```

6. Do not add a password query such as `findByEmailAndPassword`; BCrypt matching belongs in the service.

**Verify:** Flyway creates both tables, duplicate emails fail at the database constraint, and loading a user for login includes roles.

### Task 2 — Build the registration and user-facing API boundary

**Depends on:** Task 1.  
**Files:**

- `src/main/java/kr/lostory/backend/auth/AuthController.java`
- `src/main/java/kr/lostory/backend/auth/AuthService.java`
- `src/main/java/kr/lostory/backend/auth/dto/SignupRequest.java`
- `src/main/java/kr/lostory/backend/auth/dto/LoginRequest.java`
- `src/main/java/kr/lostory/backend/auth/dto/LoginResponse.java`
- `src/main/java/kr/lostory/backend/user/api/UserController.java`
- `src/main/java/kr/lostory/backend/user/api/UserResponse.java`
- `src/main/java/kr/lostory/backend/common/exception/ErrorCode.java`
- `src/main/java/kr/lostory/backend/common/exception/GlobalExceptionHandler.java`

1. Validate email and password at the request boundary with `@Valid`; normalize email with `trim().toLowerCase(Locale.ROOT)` before every repository lookup or save.
2. Expose `POST /api/v1/auth/signup`. Check for an existing email, hash the password with a `PasswordEncoder`, save the user with `USER`, and return `201 UserResponse`.
3. Expose `GET /api/v1/users/me`. Read the JWT subject as a user ID, load an active user, and return only ID, email, roles, and safe status fields.
4. Add domain error codes for duplicate email, invalid credentials, invalid token, and expired token. Map duplicate email to `409`; map credential/token failures to `401`.
5. Treat an `existsByEmail` result as a friendly pre-check only. Translate a database unique-constraint race to the same duplicate-email error.

**Verify:** invalid DTOs return the current `ErrorResponse`; no response serializes `passwordHash` or an entity.

### Task 3 — Issue and validate JWTs

**Depends on:** Tasks 1–2.  
**Files:**

- `build.gradle`
- `src/main/java/kr/lostory/backend/auth/JwtProperties.java`
- `src/main/java/kr/lostory/backend/auth/JwtTokenService.java`
- `src/main/java/kr/lostory/backend/config/SecurityConfig.java`
- `src/main/resources/application.properties`
- `src/main/resources/application-local.properties`
- `src/main/resources/application-test.properties`

1. Add Spring Boot's OAuth2 resource-server starter. Do not add another JWT library.
2. Bind and validate `app.jwt.issuer`, `app.jwt.secret`, and `app.jwt.access-token-ttl`. Decode the Base64 secret once at startup and reject an absent or too-short key.
3. Add a BCrypt `PasswordEncoder` bean.
4. Implement `JwtTokenService.issue(User)` with these claims only: issuer, numeric subject, issued-at, expiration, and global roles.
5. Configure a `JwtEncoder` and `JwtDecoder` with the same HS256 key. The decoder must validate the signature, issuer, and timestamps.
6. Implement `POST /api/v1/auth/login`: load user and roles, confirm active status, use `passwordEncoder.matches`, return one generic invalid-credentials response for unknown email and wrong password, then issue `LoginResponse`.
7. Make `SecurityConfig` stateless, disable form/basic login and server sessions, and use `.oauth2ResourceServer(...jwt...)` so Spring Security supplies the bearer-token filter.
8. Permit only signup, login, health, and OpenAPI/Swagger paths without a token. Require authentication for every other endpoint.
9. Map `roles` to `ROLE_USER`/`ROLE_ADMIN`. Use database checks, not JWT claims, for future ownership and center-membership decisions.
10. Configure the security entry point and access-denied handler to serialize the existing `ErrorResponse`; controller advice does not handle filter-chain failures.

**Verify:** valid token is accepted; a modified, expired, or wrong-issuer token is rejected with `401`; no HTTP session is created.

### Task 4 — Make Swagger usable and add regression coverage

**Depends on:** Task 3.  
**Files:**

- `src/main/java/kr/lostory/backend/config/OpenApiConfig.java`
- `src/test/java/kr/lostory/backend/auth/AuthIntegrationTest.java`
- `src/test/java/kr/lostory/backend/common/exception/GlobalExceptionHandlerTest.java` if error mappings change
- `src/main/resources/application-test.properties`

1. Add an OpenAPI HTTP Bearer/JWT security scheme. Keep signup/login marked public; allow Swagger users to paste the login token for protected calls.
2. Add one API integration-test class using the existing random-port/Testcontainers style. Test data must use a test-only JWT secret, never a real secret.
3. Cover these scenarios:

   - signup stores a BCrypt hash and returns `201` without a hash;
   - duplicate signup returns `409`;
   - correct login returns a Bearer token and expiry;
   - unknown email and wrong password both return the same `401` credentials response;
   - `/api/v1/users/me` is `401` without a token and succeeds with a valid token;
   - malformed, altered, expired, and wrong-issuer tokens return `401` JSON;
   - health and OpenAPI remain public.

4. Do not create a fake production endpoint just to test `403`. Add ownership and center-membership `403` scenarios with the first real lost-report and center endpoints in their respective feature plans.

**Verify:** `./gradlew test` exits `0` with Docker/Testcontainers available.

### Task 5 — Run the manual API gate

**Depends on:** Task 4.

1. Start the local profile with `JWT_ISSUER`, `JWT_SECRET`, and `JWT_ACCESS_TOKEN_TTL` supplied outside source control.
2. In Swagger or curl: signup a user, log in, copy the token into `Authorization: Bearer <token>`, and call `/api/v1/users/me`.
3. Repeat `/users/me` with no token and an altered token; both must be `401` using the standard error JSON.
4. Confirm Swagger, `/v3/api-docs`, and `/actuator/health` remain public.

**Evidence to record:** `./gradlew test` exit code, successful authenticated `/users/me` response with no sensitive fields, and observed `401` responses for both no-token and altered-token requests.

## 5. Follow-up integration rules

- When `lostreport` arrives, compare its owner ID to JWT `sub` in the service and return `403` on mismatch.
- When `center` arrives, query `center_memberships` on every intake/return action. A stale JWT role must never grant center access.
- When P0-5 arrives, add login audit events and a normalized-email-plus-IP rate limit without changing the JWT contract.
- When a refresh/revocation requirement is approved, add it as a separate security design; do not retrofit session state into this P0 access-token flow.
