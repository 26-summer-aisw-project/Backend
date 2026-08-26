# LOSTORY API 계약

**상태:** P0 구현 동기화 · 2026-08-25
**Base path:** `/api/v1`
**전송 형식:** HTTPS JSON. 사진 생성·교체는 `multipart/form-data`, 사진 조회는 비공개 객체의 5분 유효 서명 URL JSON을 사용한다.

이 문서는 [MVP 구현 기준](./MVP_IMPLEMENTATION_PLAN.md)의 HTTP 계약이다. 이전 OpenAPI YAML과 이전 payload 초안은 이 문서와 다르면 사용하지 않는다.

## 1. 공통 규칙

- 경로의 ID와 User·센터·습득물·신고·후보 응답 ID, JWT `sub`는 10진 문자열이다. 현재 사진 교체 응답의 이미지 `id`, `foundItemId`만 JSON number다.
- 가입·로그인만 공개다. 나머지 P0 경로는 Bearer JWT가 필요하다.
- P1 대시보드 경로는 활성 대시보드 관리 계정만, `/admin/*`은 ADMIN만 사용한다.
- 오류는 `{ "code": "...", "message": "..." }` 형식이다.
- 존재를 숨겨야 하는 타인 FoundItem·LostReport는 `404`를, 명백한 ADMIN/대시보드 권한 위반은 `403`을 반환한다.
- 시간은 UTC RFC 3339 문자열, 좌표는 `{ "latitude": number, "longitude": number }`이다.

### 1.1 상태값

| 대상 | 값 |
|---|---|
| User | `ACTIVE`, `BLOCKED`, `DELETED` |
| FoundItem | `DRAFT`, `PENDING_HANDOVER`, `ACTIVE`, `EXPIRED`, `RETURNED` |
| Vision | `PENDING`, `READY`, `FAILED` |
| 사용자 인계 | P0 공개값 `NONE`, `USER_CONFIRMED` |
| LostReport | `OPEN`, `CLOSED`, `EXPIRED` |

`BLOCKED`·`DELETED` 사용자는 로그인과 기존 토큰의 `/users/me` 조회를 통과할 수 없다. `DRAFT`와 `PENDING_HANDOVER`는 후보가 아니다. `DRAFT` 기본 TTL은 `PT24H`, 등록된 습득물과 신고의 기본 TTL은 각각 `P14D`다. `RETURNED`는 P1 센터 담당자만 만들 수 있다. 기존 데이터의 `LEGACY_UNVERIFIED`는 저장소 내부 상태이며 P0 응답에서는 `NONE`으로 숨긴다.

### 1.2 공통 오류

모든 오류 응답은 아래 두 필드만 사용한다. 닫히거나 만료된 신고의 수정·재종료·후보 조회는 `409 REPORT_NOT_OPEN`이다.

```json
{ "code": "REPORT_NOT_OPEN", "message": "The lost report is not open." }
```

### 1.3 FoundItem 응답 범위

P0에는 모든 FoundItem 필드를 한 번에 반환하는 공통 응답이 없다. 아래 각 엔드포인트의 payload가 실제 계약이다. 원본 Vision 값, 스토리지 키, 비공개 특징은 어떤 P0 응답에도 포함하지 않는다.

## 2. P0: 인증과 센터 디렉터리

#### signup
> POST `/auth/signup`

- 공개 엔드포인트다. `ACTIVE` 상태의 일반 사용자를 만든다.

**요청 payload**

```json
{ "email": "user@example.com", "password": "8바이트 이상 비밀번호", "displayName": "사용자" }
```

**응답 payload — 201 Created**

```json
{ "id": "101", "email": "user@example.com", "displayName": "사용자", "status": "ACTIVE", "roles": ["USER"] }
```

#### login
> POST `/auth/login`

- 공개 엔드포인트다. 차단·삭제 계정도 일반적인 `401 AUTH-002`로 처리한다.

**요청 payload**

```json
{ "email": "user@example.com", "password": "비밀번호" }
```

**응답 payload — 200 OK**

```json
{ "accessToken": "<jwt>", "tokenType": "Bearer", "expiresAt": "2026-08-23T09:15:00Z", "user": { "id": "101", "status": "ACTIVE" } }
```

#### get-my-user
> GET `/users/me`

- 현재 로그인한 사용자의 안전한 프로필을 반환한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Header | `Authorization` | `Bearer <jwt>` |

**응답 payload — 200 OK**

```json
{ "id": "101", "email": "user@example.com", "displayName": "사용자", "status": "ACTIVE", "roles": ["USER"] }
```

#### list-lost-centers
> GET `/lost-centers`

- 인증 사용자가 공개 센터 디렉터리를 조회한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Query | `page`, `pageSize`, `q` | 선택. 기본 `1`, `20`, 검색어 |

**응답 payload — 200 OK**

```json
{ "data": [{ "id": "20", "name": "캠퍼스 분실물 센터", "address": "서울시 …", "contactPhone": "02-000-0000", "location": { "latitude": 37.5665, "longitude": 126.9780 }, "isActive": true }], "meta": { "page": 1, "pageSize": 20, "totalItems": 1 } }
```

#### find-nearby-lost-centers
> GET `/lost-centers/nearby`

- 습득자가 인계할 수 있는 활성 센터 후보를 거리순으로 최대 10개 반환한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Query | `latitude`, `longitude` | 필수. 습득 장소 좌표 |

**응답 payload — 200 OK**

```json
{ "data": [{ "id": "20", "name": "캠퍼스 분실물 센터", "contactPhone": "02-000-0000", "location": { "latitude": 37.5665, "longitude": 126.9780 }, "distanceMeters": 240 }] }
```

반경은 서버 설정 1,000 m다. 목록 밖 센터는 P0에서 인계 대상으로 선택할 수 없다.
목록은 활성 `official_verified`, `official_board_verified`, `official_local_verified`, `admin_verified`를 보여 주지만, P0 인계 등록은 앞의 세 공식 검증 상태만 허용한다. 따라서 `admin_verified`는 안내에는 나타날 수 있어도 인계 대상으로 확정할 수 없다.

#### create-lost-center
> POST `/admin/lost-centers`

- ADMIN이 공개 센터 디렉터리 항목을 만든다.

**요청 payload**

```json
{ "name": "캠퍼스 분실물 센터", "address": "서울시 …", "contactPhone": "02-000-0000", "location": { "latitude": 37.5665, "longitude": 126.9780 } }
```

**응답 payload — 201 Created**

```json
{ "id": "20", "name": "캠퍼스 분실물 센터", "isActive": true }
```

#### update-lost-center
> PATCH `/admin/lost-centers/{centerId}`

- ADMIN이 공개 센터 정보를 변경하거나 비활성화한다.

**요청 payload**

```json
{ "contactPhone": "02-111-1111", "isActive": false }
```

**응답 payload — 200 OK**

```json
{ "id": "20", "contactPhone": "02-111-1111", "isActive": false }
```

## 3. P0: 습득물 등록과 사용자 인계

#### create-found-item-draft
> POST `/found-items/drafts`

- 사진 업로드로 소유자 전용 `DRAFT` 습득물을 만들고 Vision 작업을 시작한다.
- FE는 이 경로로 이미지를 BE에 전송한다. presigned 직접 업로드는 사용하지 않는다.

**요청 payload**

| 위치 | 필드 | 형식 | 설명 |
|---|---|---|---|
| Multipart | `image` | JPEG, PNG, WebP | 필수. 서버 제한 이내의 정확히 한 장이며 다른 multipart·form 필드는 허용하지 않는다. |

**응답 payload — 201 Created**

```json
{ "id": "300", "status": "DRAFT", "visionStatus": "PENDING", "draftExpiresAt": "2026-08-24T08:30:00Z" }
```

#### get-found-item
> GET `/found-items/{itemId}`

- 소유자 또는 ADMIN이 등록 진행 상태와 Vision 제안을 확인한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Path | `itemId` | 습득물 ID |

**응답 payload — 200 OK**

```json
{ "id": "300", "status": "DRAFT", "handoverStatus": "NONE", "visionStatus": "READY", "visionSuggestion": { "color": "BLACK", "publicDescription": "검은 카드 지갑" }, "draftExpiresAt": "2026-08-24T08:30:00Z" }
```

`visionSuggestion`은 Vision이 `READY`이고 색상 또는 LABEL이 있을 때만 채워진다. 이 값은 소유자와 ADMIN의 상세 조회에만 제공하며 후보 응답에는 절대 포함하지 않는다.

#### get-found-item-image
> GET `/found-items/{itemId}/image`

- 소유자 또는 ADMIN이 현재 비공개 사진의 5분 유효 GET 서명 URL을 조회한다. 객체 키·저장 경로·저장 파일명은 반환하지 않으며 응답은 캐시하지 않는다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Path | `itemId` | 습득물 ID |

**응답 payload — 200 OK**

Header: `Cache-Control: no-store`

```json
{ "url": "https://signed.example/…", "expiresAt": "2026-08-23T08:45:00Z" }
```

#### replace-found-item-image
> PUT `/found-items/{itemId}/image`

- 소유자가 현재 사진 한 장을 원자적으로 교체한다. 새 Vision 세대를 시작하고 열린 신고 후보를 stale로 표시하며, 이전 객체는 삭제 outbox로 보낸다.
- 교체 시 이전 AI 특징과 소유자가 확정한 `COLOR`·`PUBLIC_DESCRIPTION` 특징도 제거된다. 새 Vision 결과를 확인한 뒤 습득자는 등록 PATCH에서 특징을 다시 확정해야 한다.

**요청 payload**

| 위치 | 필드 | 형식 | 설명 |
|---|---|---|---|
| Multipart | `image` | JPEG, PNG, WebP | 정확히 한 장, 다른 필드 없음 |

**응답 payload — 200 OK**

```json
{ "id": 501, "foundItemId": 300, "contentType": "image/png", "sizeBytes": 48213, "createdAt": "2026-08-23T08:40:00Z" }
```

#### list-my-found-items
> GET `/found-items`

- 현재 사용자가 만든 습득물을 페이지 단위로 반환한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Query | `page`, `pageSize`, `status` | 선택. 상태 필터는 P0 상태값만 허용 |

**응답 payload — 200 OK**

```json
{ "data": [{ "id": "300", "status": "PENDING_HANDOVER", "visionStatus": "READY", "category": "WALLET", "handoverStatus": "NONE" }], "meta": { "page": 1, "pageSize": 20, "totalItems": 1 } }
```

#### finalize-found-item-registration
> PATCH `/found-items/{itemId}/registration`

- 장소·시각·분류·사용자 확정 특징·보관 방식을 저장한다.
- Vision은 보조 제안이다. `READY`는 이 요청의 선행조건이 아니며 `PENDING`·`FAILED`여도 사용자가 직접 입력한 `confirmedFeatures`로 등록을 완료할 수 있다.
- 이 PATCH는 매칭 입력과 사용자 확정 특징을 저장하고 열린 신고 후보를 stale로 표시하지만 Vision 작업을 다시 만들지 않는다. 사진 교체만 새 Vision 작업을 만든다.

**요청 payload**

```json
{
  "category": "WALLET",
  "foundAt": "2026-08-23T08:00:00Z",
  "foundLocation": { "latitude": 37.5665, "longitude": 126.9780 },
  "confirmedFeatures": { "color": "BLACK", "publicDescription": "검은 카드 지갑" },
  "storageMethod": "HANDED_TO_CENTER",
  "centerId": "20"
}
```

| 필드 | 규칙 |
|---|---|
| `centerId` | `HANDED_TO_CENTER`에서만 필수다. 다른 보관 방식에서는 생략하거나 `null`이어야 한다. |
| `storageDescription` | `MOVED_TO_SAFE_PLACE`에서만 필수다. 다른 보관 방식에서는 생략하거나 `null`이어야 한다. |
| `handedAt` | 서버 전용 기록값이다. 클라이언트는 비어 있지 않은 값을 보낼 수 없다. |

**응답 payload — 200 OK**

```json
{ "id": "300", "status": "PENDING_HANDOVER", "storageMethod": "HANDED_TO_CENTER", "centerId": "20", "handoverStatus": "NONE", "handedAt": null }
```

`LEFT_IN_PLACE`·`MOVED_TO_SAFE_PLACE`는 이 요청 성공 뒤 `ACTIVE`가 된다. `HANDED_TO_CENTER`는 `centerId`가 현재 추천 목록에 있을 때만 `PENDING_HANDOVER`가 된다.

#### get-found-item-nearby-centers
> GET `/found-items/{itemId}/nearby-centers`

- 등록 화면에서 해당 습득물의 습득 장소 기준 인계 후보를 다시 조회한다. `foundLocation`이 아직 저장되지 않은 DRAFT에서는 사용할 수 없다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Path | `itemId` | 습득물 ID |

**응답 payload — 200 OK**

```json
{ "data": [{ "id": "20", "name": "캠퍼스 분실물 센터", "contactPhone": "02-000-0000", "location": { "latitude": 37.5665, "longitude": 126.9780 }, "distanceMeters": 240.0 }] }
```

#### confirm-handover
> POST `/found-items/{itemId}:confirm-handover`

- 소유자가 센터 인계를 마쳤다고 확정한다.
- 서버가 이 요청 시각을 `handedAt`으로 기록한다. 이는 P1 센터 수락 전 사용자 진술이다.

**요청 payload**

요청 본문 없이 호출한다.

**응답 payload — 200 OK**

```json
{ "id": "300", "status": "ACTIVE", "storageMethod": "HANDED_TO_CENTER", "handoverStatus": "USER_CONFIRMED", "centerId": "20", "handedAt": "2026-08-23T09:10:00Z" }
```

P1 센터 수락 전에는 `PATCH /found-items/{itemId}/registration`으로 인계 선택을 수정·철회할 수 있다.

### 3.1 P0 인계 상태 전이

| 현재 상태 | 요청/조건 | 다음 상태 | 인계 상태 | 비고 |
|---|---|---|---|---|
| `DRAFT` | 비센터 보관 방식으로 등록 확정 | `ACTIVE` | `NONE` | 센터·인계 시각 없음 |
| `DRAFT` 또는 수정 가능 항목 | 추천 가능한 센터 선택 | `PENDING_HANDOVER` | `NONE` | 활성·검증·반경 내 센터만 가능 |
| `PENDING_HANDOVER` | `:confirm-handover` | `ACTIVE` | `USER_CONFIRMED` | 서버 시각을 `handedAt`으로 저장 |
| `PENDING_HANDOVER` | 비센터 방식으로 수정 | `ACTIVE` | `NONE` | 사용자 인계 선택 철회 |
| `USER_CONFIRMED` + `ACTIVE` | 센터 또는 보관 방식을 수정 | `PENDING_HANDOVER` 또는 `ACTIVE` | `NONE` | P1 센터 수락 전만 허용, 감사 기록 |
| `DRAFT`/`PENDING_HANDOVER`/`ACTIVE` | TTL 경계 도달 | 삭제 또는 `EXPIRED` | 기존 값 유지 | 후보에서 제외 |
| `LEGACY_UNVERIFIED` | P0 조회 | 저장 상태 유지 | 응답은 `NONE` | 가짜 사용자 확인으로 승격 금지 |

## 4. P0: 신고·센터 안내·점수 후보

#### create-lost-report
> POST `/lost-reports`

- 신고, 동적 반경, `centerGuidance` 스냅샷, 최초 점수 후보를 원자적으로 만든다.

**요청 payload**

```json
{
  "category": "WALLET",
  "description": "검은 카드 지갑을 잃었습니다.",
  "lostAtFrom": "2026-08-23T07:00:00Z",
  "lostAtTo": "2026-08-23T09:00:00Z",
  "waypoints": [
    { "ordinal": 1, "point": { "latitude": 37.5665, "longitude": 126.9780 } },
    { "ordinal": 2, "point": { "latitude": 37.5650, "longitude": 126.9800 } }
  ]
}
```

**응답 payload — 201 Created**

```json
{
  "id": "900",
  "status": "OPEN",
  "effectiveSearchRadiusMeters": 1000,
  "radiusPolicyVersion": "p0-radius-v1",
  "centerGuidance": [{ "id": "20", "name": "캠퍼스 분실물 센터", "contactPhone": "02-000-0000", "distanceMeters": 210 }],
  "candidatesStale": false
}
```

#### list-my-lost-reports
> GET `/lost-reports`

- 현재 사용자의 신고 목록을 반환한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Query | `page`, `pageSize`, `status` | 선택 |

**응답 payload — 200 OK**

```json
{ "data": [{ "id": "900", "category": "WALLET", "status": "OPEN", "effectiveSearchRadiusMeters": 1000, "candidatesStale": false }], "meta": { "page": 1, "pageSize": 20, "totalItems": 1 } }
```

#### get-lost-report
> GET `/lost-reports/{reportId}`

- 저장된 핀, 동적 반경, `centerGuidance` 스냅샷을 포함한 신고를 반환한다. 매 요청에 센터 안내를 다시 계산하지 않는다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Path | `reportId` | 신고 ID |

**응답 payload — 200 OK**

```json
{ "id": "900", "status": "OPEN", "effectiveSearchRadiusMeters": 1000, "radiusPolicyVersion": "p0-radius-v1", "centerGuidance": [{ "id": "20", "name": "캠퍼스 분실물 센터", "contactPhone": "02-000-0000", "distanceMeters": 210.0 }], "candidatesStale": false }
```

#### update-lost-report
> PATCH `/lost-reports/{reportId}`

- 신고의 매칭 입력을 바꾸고, 핀이 바뀌면 새 반경·센터 안내·후보를 원자적으로 계산한다.

**요청 payload**

```json
{ "description": "검은색 카드 지갑", "waypoints": [{ "ordinal": 1, "point": { "latitude": 37.5665, "longitude": 126.9780 } }] }
```

**응답 payload — 200 OK**

```json
{ "id": "900", "effectiveSearchRadiusMeters": 1000, "centerGuidance": [{ "id": "20", "name": "캠퍼스 분실물 센터" }], "candidatesStale": false }
```

#### list-score-candidates
> GET `/lost-reports/{reportId}/candidates`

- P0 후보는 점수만 제공한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Path | `reportId` | 신고 ID |

**응답 payload — 200 OK**

```json
{ "lastMatchedAt": "2026-08-23T09:30:00Z", "candidatesStale": false, "data": [{ "candidateId": "810", "rank": 1, "score": 82.40 }] }
```

반경은 인접 핀 거리 중앙값 `m`에 대해 `clamp(500, 3000, 1000 + 0.10 × m)`를 계산하고 최종 미터만 `HALF_UP` 정수 반올림한다. 점수는 위치 0.35, 시간 0.20, 분류 0.20, 색상 0.15, 공개 설명 0.10의 가중합에 100을 곱하며 중간 반올림 없이 최종값만 소수 둘째 자리 `HALF_UP`으로 반올림한다. 시간창 기본값은 `PT24H`이며 누락 특징은 0점이고 가중치를 재분배하지 않는다. `ACTIVE`이면서 `expiredAt`이 현재보다 뒤인 항목만 후보이며, 점수 내림차순·습득물 ID 오름차순으로 최대 5개다.

#### close-lost-report
> POST `/lost-reports/{reportId}:close`

- 신고 소유자가 자신의 신고를 닫는다. FoundItem 상태나 반환 기록은 바꾸지 않는다.

**요청 payload**

```json
{}
```

**응답 payload — 200 OK**

```json
{ "id": "900", "status": "CLOSED" }
```

## 5. P1: 파트너 센터와 대시보드

#### create-partner-center
> POST `/admin/partner-centers`

- ADMIN이 오프라인 검토를 마친 기존 디렉터리 센터와 새 대시보드 관리 계정을 `PENDING`으로 연결한다.

**요청 payload**

```json
{ "centerId": "20", "manager": { "email": "manager@center.example", "displayName": "센터 담당자" } }
```

**응답 payload — 201 Created**

```json
{ "partnershipId": "50", "centerId": "20", "status": "PENDING", "managerEmail": "manager@center.example" }
```

기존 사용자 이메일은 사용할 수 없다.

#### approve-partner-center
> POST `/admin/partner-centers/{partnershipId}:approve`

- ADMIN이 파트너십을 승인하고 별도 채널 전달용 일회성 활성화 링크를 발급한다.

**요청 payload**

```json
{}
```

**응답 payload — 200 OK**

```json
{ "partnershipId": "50", "status": "PENDING_ACTIVATION", "activationUrl": "https://app.example/partner-activation/<opaque-token>", "expiresAt": "2026-08-24T09:30:00Z" }
```

링크는 24시간·1회 사용이며 재발급은 이전 링크를 폐기한다. URL은 감사 로그에 기록하지 않는다.

#### activate-partner-manager
> POST `/partner-manager-activations/{activationToken}`

- 새 관리 계정이 비밀번호를 설정하고 대시보드 전용으로 활성화된다.

**요청 payload**

```json
{ "password": "8바이트 이상 비밀번호" }
```

**응답 payload — 200 OK**

```json
{ "partnershipId": "50", "centerId": "20", "managerUserId": "501", "status": "ACTIVE" }
```

#### list-user-confirmed-handovers
> GET `/dashboard/handovers`

- 활성 관리 계정이 자기 센터를 선택한 사용자 인계 주장 대기열을 조회한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Query | `status` | 선택. 기본 `USER_CONFIRMED` |

**응답 payload — 200 OK**

```json
{ "data": [{ "handoverId": "700", "itemId": "300", "category": "WALLET", "handedAt": "2026-08-23T09:10:00Z", "status": "USER_CONFIRMED" }] }
```

#### accept-handover
> POST `/dashboard/handovers/{handoverId}:accept`

- 담당자가 실물을 확인한 경우에만 사용자 인계를 센터 확인으로 수락한다.

**요청 payload**

```json
{ "privateFeatures": ["내부 확인 특징"] }
```

**응답 payload — 200 OK**

```json
{ "handoverId": "700", "itemId": "300", "handoverStatus": "CENTER_CONFIRMED", "acceptedAt": "2026-08-24T10:00:00Z" }
```

#### reject-handover
> POST `/dashboard/handovers/{handoverId}:reject`

- 담당자가 실물을 찾지 못하면 수락하지 않는다. 사용자 진술은 삭제하지 않는다.

**요청 payload**

```json
{ "reason": "센터 보관 목록에서 실물을 찾지 못했습니다." }
```

**응답 payload — 200 OK**

```json
{ "handoverId": "700", "handoverStatus": "REJECTED" }
```

#### record-return
> POST `/dashboard/returns`

- 담당자가 수락된 인계와 신고 관계를 확인해 반환을 기록한다.

**요청 payload**

```json
{ "itemId": "300", "reportId": "900" }
```

**응답 payload — 201 Created**

```json
{ "returnId": "600", "itemId": "300", "reportId": "900", "status": "RETURNED", "rewardGranted": 5 }
```

## 6. P1: 후보 상세과 포인트

#### unlock-candidate-details
> POST `/lost-reports/{reportId}/candidate-accesses`

- 신고 소유자가 포인트로 해당 신고의 후보 상세 열람 권한을 얻는다.
- 같은 신고의 재시도·재매칭은 추가 차감하지 않는다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Header | `Idempotency-Key` | 필수. 요청당 고유 키 |

**응답 payload — 200 OK**

```json
{ "reportId": "900", "unlockedAt": "2026-08-24T11:00:00Z", "debitedPoints": 1, "remainingBalance": 9, "replayed": false }
```

#### list-unlocked-candidates
> GET `/lost-reports/{reportId}/candidates/unlocked`

- 열람 권한이 있는 신고의 제한 상세을 반환한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Path | `reportId` | 신고 ID |

**응답 payload — 200 OK**

```json
{
  "data": [{
    "candidateId": "810",
    "rank": 1,
    "score": 82.4,
    "category": "WALLET",
    "foundDate": "2026-08-23",
    "thumbnailUrl": "https://signed.example/…",
    "publicFeatures": { "color": "BLACK", "publicDescription": "검은 카드 지갑" },
    "center": { "name": "캠퍼스 분실물 센터", "contactPhone": "02-000-0000", "handoverStatus": "USER_CONFIRMED", "notice": "사용자 인계 확인, 센터 검증 전" }
  }]
}
```

정확한 위치·보관 설명·습득자 식별자·원본 이미지·AI 원문은 이 응답에도 없다. 센터 정보는 사용자 인계 확정 또는 센터 확인 항목에만 포함한다.

#### get-point-balance
> GET `/points/balance`

- 현재 사용자의 포인트 잔액을 반환한다.

**요청 payload**

```json
{}
```

**응답 payload — 200 OK**

```json
{ "balance": 9 }
```

#### list-point-ledger
> GET `/points/ledger`

- 현재 사용자의 불변 포인트 거래를 반환한다.

**요청 payload**

| 위치 | 필드 | 설명 |
|---|---|---|
| Query | `page`, `pageSize` | 선택 |

**응답 payload — 200 OK**

```json
{ "data": [{ "id": "1000", "type": "CANDIDATE_ACCESS_DEBIT", "amount": -1, "referenceType": "LOST_REPORT", "referenceId": "900", "createdAt": "2026-08-24T11:00:00Z" }], "meta": { "page": 1, "pageSize": 20, "totalItems": 1 } }
```

## 7. 구현 전제와 수용 기준

- Vision 제공사는 단일 제공사만 지원하며, 제공사 정책 문서가 없으면 호출하지 않는다.
- 포인트 정책값은 `signupGrant=10`, `candidateAccessCost=1`, `centerConfirmedReturnReward=5`의 환경 설정 기본값을 사용한다.
- 실제 결제·현물 교환·익명 연락 중계·비센터 보관 위치 공개 경로는 없다.
- P0 후보에는 상세 정보가 없고, P1 상세 열람은 신고당 한 번만 차감된다.
- 모든 신규 schema는 기존 Flyway 이력 뒤 새 migration으로 추가한다.

## 8. 폐기된 P0 경로

아래 이전 경로는 호환 별칭이 아니며 `404`를 반환한다. 새 클라이언트는 호출하지 않는다.

| Method | 폐기 경로 | 대체 경로 |
|---|---|---|
| POST | `/found-items` | `POST /found-items/drafts` 후 등록 확정 |
| GET | `/nearby-lost-centers` | `GET /lost-centers/nearby` |
| POST/GET | `/found-items/{itemId}/images` | `PUT/GET /found-items/{itemId}/image` |
