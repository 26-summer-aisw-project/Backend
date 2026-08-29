package kr.lostory.backend;

import java.util.List;
import java.util.Set;

final class ApiContractMatrix {

	enum Priority { P0, P1 }
	enum Security { PUBLIC, USER, ADMIN, CENTER_MANAGER }
	enum Body { NONE, JSON, EMPTY_JSON, MULTIPART }
	enum Flag { PAGE, IDEMPOTENCY, REPLAY, SIGNED_URL, PRIVATE_NON_PERSISTENT, SAFE_RETURN }

	record Operation(
		Priority priority,
		String method,
		String path,
		Security security,
		int successStatus,
		Set<String> parameters,
		Set<String> decimalPathParameters,
		Body body,
		Set<String> bodyProperties,
		Set<String> requiredBodyProperties,
		Set<String> successFields,
		Set<Flag> flags
	) {
		String key() {
			return method + " " + path;
		}
	}

	private static final Set<String> ERROR_FIELDS = Set.of("code", "message");

	static final List<Operation> OPERATIONS = List.of(
		op(Priority.P0, "POST", "/api/v1/auth/signup", Security.PUBLIC, 201, Body.JSON,
			Set.of("id", "email", "displayName", "status", "roles")),
		op(Priority.P0, "POST", "/api/v1/auth/login", Security.PUBLIC, 200, Body.JSON,
			Set.of("accessToken", "tokenType", "expiresAt", "user")),
		op(Priority.P0, "GET", "/api/v1/users/me", Security.USER, 200, Body.NONE,
			Set.of("id", "email", "displayName", "status", "roles")),
		op(Priority.P0, "GET", "/api/v1/lost-centers", Security.USER, 200, Set.of("page", "pageSize", "q"), Body.NONE,
			Set.of("data", "meta"), Flag.PAGE),
		op(Priority.P0, "GET", "/api/v1/lost-centers/nearby", Security.USER, 200,
			Set.of("latitude", "longitude"), Body.NONE, Set.of("data")),
		op(Priority.P0, "POST", "/api/v1/admin/lost-centers", Security.ADMIN, 201, Body.JSON,
			Set.of("id", "name", "address", "contactPhone", "location", "isActive")),
		op(Priority.P0, "PATCH", "/api/v1/admin/lost-centers/{centerId}", Security.ADMIN, 200,
			Set.of("centerId"), Body.JSON, Set.of("id", "name", "address", "contactPhone", "location", "isActive")),
		op(Priority.P0, "POST", "/api/v1/found-items/drafts", Security.USER, 201,
			Set.of("image"), Body.MULTIPART, Set.of("id", "status", "visionStatus", "draftExpiresAt")),
		op(Priority.P0, "GET", "/api/v1/found-items/{id}", Security.USER, 200,
			Set.of("id"), Body.NONE, Set.of("id", "status", "handoverStatus", "visionStatus", "visionSuggestion", "draftExpiresAt")),
		op(Priority.P0, "GET", "/api/v1/found-items/{foundItemId}/image", Security.USER, 200,
			Set.of("foundItemId"), Body.NONE, Set.of("url", "expiresAt"), Flag.SIGNED_URL),
		op(Priority.P0, "PUT", "/api/v1/found-items/{foundItemId}/image", Security.USER, 200,
			Set.of("foundItemId", "image"), Body.MULTIPART,
			Set.of("id", "foundItemId", "contentType", "sizeBytes", "createdAt")),
		op(Priority.P0, "GET", "/api/v1/found-items", Security.USER, 200, Set.of("page", "pageSize", "status"), Body.NONE,
			Set.of("data", "meta"), Flag.PAGE),
		op(Priority.P0, "PATCH", "/api/v1/found-items/{id}/registration", Security.USER, 200,
			Set.of("id"), Body.JSON, Set.of("id", "status", "storageMethod", "centerId", "handoverStatus", "handedAt")),
		op(Priority.P0, "GET", "/api/v1/found-items/{foundItemId}/nearby-centers", Security.USER, 200,
			Set.of("foundItemId"), Body.NONE, Set.of("data")),
		op(Priority.P0, "POST", "/api/v1/found-items/{id}:confirm-handover", Security.USER, 200,
			Set.of("id"), Body.NONE, Set.of("id", "status", "storageMethod", "centerId", "handoverStatus", "handedAt")),
		op(Priority.P0, "POST", "/api/v1/lost-reports", Security.USER, 201, Body.JSON,
			Set.of("id", "status", "effectiveSearchRadiusMeters", "radiusPolicyVersion", "centerGuidance", "candidatesStale")),
		op(Priority.P0, "GET", "/api/v1/lost-reports", Security.USER, 200, Set.of("page", "pageSize", "status"), Body.NONE,
			Set.of("data", "meta"), Flag.PAGE),
		op(Priority.P0, "GET", "/api/v1/lost-reports/{reportId}", Security.USER, 200,
			Set.of("reportId"), Body.NONE, Set.of("id", "category", "description", "lostAtFrom", "lostAtTo", "effectiveSearchRadiusMeters", "radiusPolicyVersion", "centerGuidance", "candidatesStale", "status", "waypoints", "expiredAt", "createdAt", "updatedAt")),
		op(Priority.P0, "PATCH", "/api/v1/lost-reports/{reportId}", Security.USER, 200,
			Set.of("reportId"), Body.JSON, Set.of("id", "effectiveSearchRadiusMeters", "centerGuidance", "candidatesStale")),
		op(Priority.P0, "GET", "/api/v1/lost-reports/{reportId}/candidates", Security.USER, 200,
			Set.of("reportId"), Body.NONE, Set.of("lastMatchedAt", "candidatesStale", "data")),
		op(Priority.P0, "POST", "/api/v1/lost-reports/{reportId}:close", Security.USER, 200,
			Set.of("reportId"), Body.EMPTY_JSON, Set.of("id", "status")),
		op(Priority.P1, "POST", "/api/v1/admin/partner-centers", Security.ADMIN, 201, Body.JSON,
			Set.of("partnershipId", "centerId", "status", "managerEmail")),
		op(Priority.P1, "POST", "/api/v1/admin/partner-centers/{partnershipId}:approve", Security.ADMIN, 200,
			Set.of("partnershipId"), Body.NONE, Set.of("partnershipId", "status", "expiresAt")),
		op(Priority.P1, "POST", "/api/v1/partner-manager-activations/{activationToken}", Security.PUBLIC, 200,
			Set.of("activationToken"), Body.JSON, Set.of("partnershipId", "centerId", "managerUserId", "status")),
		op(Priority.P1, "GET", "/api/v1/dashboard/handovers", Security.CENTER_MANAGER, 200,
			Set.of("status"), Body.NONE, Set.of("data")),
		op(Priority.P1, "POST", "/api/v1/dashboard/handovers/{handoverId}:accept", Security.CENTER_MANAGER, 200,
			Set.of("handoverId"), Body.JSON, Set.of("handoverId", "itemId", "handoverStatus", "acceptedAt"), Flag.PRIVATE_NON_PERSISTENT),
		op(Priority.P1, "POST", "/api/v1/dashboard/handovers/{handoverId}:reject", Security.CENTER_MANAGER, 200,
			Set.of("handoverId"), Body.JSON, Set.of("handoverId", "handoverStatus")),
		op(Priority.P1, "POST", "/api/v1/dashboard/returns", Security.CENTER_MANAGER, 201, Body.JSON,
			Set.of("returnId", "itemId", "reportId", "status", "rewardGranted"), Flag.SAFE_RETURN),
		op(Priority.P1, "POST", "/api/v1/lost-reports/{reportId}/candidate-accesses", Security.USER, 200,
			Set.of("reportId", "Idempotency-Key"), Body.NONE,
			Set.of("reportId", "unlockedAt", "debitedPoints", "remainingBalance", "replayed"), Flag.IDEMPOTENCY, Flag.REPLAY),
		op(Priority.P1, "GET", "/api/v1/lost-reports/{reportId}/candidates/unlocked", Security.USER, 200,
			Set.of("reportId"), Body.NONE, Set.of("data"), Flag.SIGNED_URL),
		op(Priority.P1, "GET", "/api/v1/points/balance", Security.USER, 200, Body.NONE,
			Set.of("balance")),
		op(Priority.P1, "GET", "/api/v1/points/ledger", Security.USER, 200, Set.of("page", "pageSize"), Body.NONE,
			Set.of("data", "meta"), Flag.PAGE)
	);

	static Set<String> errorFields() {
		return ERROR_FIELDS;
	}

	private static Operation op(Priority priority, String method, String path, Security security,
			int successStatus, Body body, Set<String> successFields, Flag... flags) {
		return op(priority, method, path, security, successStatus, Set.of(), body, successFields, flags);
	}

	private static Operation op(Priority priority, String method, String path, Security security,
			int successStatus, Set<String> parameters, Body body, Set<String> successFields, Flag... flags) {
		return new Operation(priority, method, path, security, successStatus, parameters,
			decimalPathParameters(path), body,
			bodyProperties(method + " " + path), requiredBodyProperties(method + " " + path),
			successFields, Set.of(flags));
	}

	private static Set<String> decimalPathParameters(String path) {
		return Set.of("id", "foundItemId", "centerId", "reportId", "partnershipId", "handoverId")
			.stream().filter(name -> path.contains("{" + name + "}"))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static Set<String> bodyProperties(String key) {
		return switch (key) {
			case "POST /api/v1/auth/signup" -> Set.of("email", "password", "displayName");
			case "POST /api/v1/auth/login" -> Set.of("email", "password");
			case "POST /api/v1/admin/lost-centers" -> Set.of("name", "address", "contactPhone", "location");
			case "PATCH /api/v1/admin/lost-centers/{centerId}" -> Set.of("name", "address", "contactPhone", "location", "isActive");
			case "POST /api/v1/found-items/drafts", "PUT /api/v1/found-items/{foundItemId}/image" -> Set.of("image");
			case "PATCH /api/v1/found-items/{id}/registration" -> Set.of("category", "foundAt", "foundLocation", "confirmedFeatures", "storageMethod", "centerId", "storageDescription", "handedAt");
			case "POST /api/v1/lost-reports" -> Set.of("category", "description", "lostAtFrom", "lostAtTo", "waypoints");
			case "PATCH /api/v1/lost-reports/{reportId}" -> Set.of("category", "description", "lostAtFrom", "lostAtTo", "waypoints");
			case "POST /api/v1/lost-reports/{reportId}:close" -> Set.of();
			case "POST /api/v1/admin/partner-centers" -> Set.of("centerId", "manager");
			case "POST /api/v1/partner-manager-activations/{activationToken}" -> Set.of("password");
			case "POST /api/v1/dashboard/handovers/{handoverId}:accept" -> Set.of("privateFeatures");
			case "POST /api/v1/dashboard/handovers/{handoverId}:reject" -> Set.of("reason");
			case "POST /api/v1/dashboard/returns" -> Set.of("itemId", "reportId");
			default -> Set.of();
		};
	}

	private static Set<String> requiredBodyProperties(String key) {
		return switch (key) {
			case "POST /api/v1/auth/signup" -> Set.of("email", "password", "displayName");
			case "POST /api/v1/auth/login" -> Set.of("email", "password");
			case "POST /api/v1/admin/lost-centers" -> Set.of("name", "address", "contactPhone", "location");
			case "POST /api/v1/found-items/drafts", "PUT /api/v1/found-items/{foundItemId}/image" -> Set.of("image");
			case "PATCH /api/v1/found-items/{id}/registration" -> Set.of("category", "foundAt", "foundLocation", "confirmedFeatures", "storageMethod");
			case "POST /api/v1/lost-reports" -> Set.of("category", "description", "lostAtFrom", "lostAtTo", "waypoints");
			case "POST /api/v1/admin/partner-centers" -> Set.of("centerId", "manager");
			case "POST /api/v1/partner-manager-activations/{activationToken}" -> Set.of("password");
			case "POST /api/v1/dashboard/handovers/{handoverId}:accept" -> Set.of("privateFeatures");
			case "POST /api/v1/dashboard/handovers/{handoverId}:reject" -> Set.of("reason");
			case "POST /api/v1/dashboard/returns" -> Set.of("itemId", "reportId");
			default -> Set.of();
		};
	}

	private ApiContractMatrix() {
	}
}
