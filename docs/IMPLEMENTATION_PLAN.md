# LOSTORY 백엔드 구현 계획 (P0 · P1)

문서 상태: Draft  
작성일: 2026-08-08  
기준: [현재 제품 기획서](../../Docs/LOSTORY_PRODUCT_PLAN.md), 현재 Backend 코드베이스, [ERD 명세](./diagram/erd-spec.json)

## 1. 목표와 범위

이 계획의 목표는 Spring Boot 단일 애플리케이션에서 다음 두 결과를 안전하게 제공하는 것이다.

1. 장소 핀 주변의 모든 분실물 센터를 안내한다.
2. 파트너 센터가 실제 보관 중인 습득물에 한해서만, 정보 노출을 통제한 Top-5 후보 매칭과 인계·반환·포인트 흐름을 제공한다.

이 문서는 P0과 P1 기능만 다룬다. 장기 확장 기능은 구현 순서, 데이터 모델, API, 테스트 범위에 포함하지 않는다.

### 1.1 반드시 지킬 제품 규칙

| 규칙 | 백엔드 강제 방식 |
|---|---|
| 행사 계정·행사 단위 기능은 사용하지 않는다. | 기존 event 패키지와 Organization/Event 모델을 제거하고, 센터 중심 모델로 대체한다. 새 API·테이블에 행사 개념을 추가하지 않는다. |
| 디렉터리 센터와 파트너 센터를 분리한다. | 디렉터리 센터는 위치·연락처만 조회 가능하며 보관 물품·후보 풀에 절대 포함하지 않는다. |
| 습득자는 사진과 물품 분류만 직접 입력한다. | 위치·시각은 서버가 검증한 제안값으로 저장하고, 상세 특징은 입고 후 담당자만 기록한다. |
| 후보는 소유권 판정이 아니다. | 후보 생성과 상세 공개를 분리한다. 열람 전에는 점수만, 열람 뒤에도 제한된 공개 정보만 응답한다. |
| 비공개 특징은 매칭·응답·감사 로그에 노출하지 않는다. | 권한 있는 센터 담당자만 읽을 수 있게 저장·복호화하며, 매칭에는 담당자 확정 공개 특징만 사용한다. |
| 포인트는 불변 원장으로 추적한다. | 차감·적립은 원장과 잔액 투영을 같은 트랜잭션에서 처리하고, 멱등 키와 유니크 제약으로 중복 처리하지 않는다. |

### 1.2 P0과 P1의 경계

| 구간 | 구현 결과 |
|---|---|
| P0 | 회원·권한, 센터 디렉터리/공간 검색, 습득물 사전 등록과 파트너 입고, 이미지 특징 제안, 분실 신고·Top-5, 안전한 후보 열람, 데모 포인트 원장, 반환 보상, 감사 로그와 기본 속도 제한 |
| P1 | 실결제 포인트 충전, 현물 교환, 비도입 센터 인계 증빙 보상, 센터 승인·직원 초대, 푸시 알림, 후보 근거 설명, 위험 점수화, 중복 습득물 경고 |

## 2. 현재 코드베이스 진단과 선행 정리

| 영역 | 현재 상태 | 계획상 조치 |
|---|---|---|
| 런타임 | Java 21, Spring Boot, Web, Validation, Security, JPA, Flyway, PostgreSQL, Actuator, OpenAPI, Testcontainers가 이미 포함되어 있다. | 기존 Gradle 기반을 유지하고 필요한 공간·JWT·파일 저장·속도 제한 의존성만 추가한다. |
| 보안 | SecurityConfig는 health/Swagger만 공개하고 나머지 요청은 인증을 요구하지만, 사용자 저장소·로그인·토큰 검증은 없다. | P0 첫 단계에서 BCrypt와 서명된 액세스 토큰 기반의 stateless 인증을 완성한다. |
| DB 설정 | 기본 application.properties는 JDBC/JPA/Flyway 자동 설정을 제외하며, local/test 프로필은 Flyway 사용을 선언한다. migration 파일은 없다. | 공통 프로필의 광범위한 제외 설정을 제거하고, local/test에서 동일한 Flyway 스키마를 적용한다. 스키마 변경은 Flyway만 사용한다. |
| 도메인 | Organization과 Event 엔터티만 있으며, Event는 현재 제품 기획과 정면으로 충돌한다. repository/controller/migration은 없다. | 실제 운영 데이터가 없다는 확인 후 event·organization 패키지를 삭제하고, lost_center 중심의 새 도메인을 도입한다. 데이터가 존재하면 삭제 전에 별도 마이그레이션·백업 결정을 받는다. |
| 테스트 | 기본 context/health와 공통 예외 처리 테스트만 있다. Testcontainers는 postgres:16-alpine을 사용한다. | PostGIS가 포함된 테스트 DB로 바꾸고, 핵심 도메인 규칙을 API 통합 테스트로 검증한다. |
| API 공통 | OpenAPI와 ErrorResponse 골격이 존재한다. | 응답 형식은 유지하고 도메인 오류 코드, 인증/권한 오류, 멱등성 충돌 오류를 추가한다. |

이 단계는 기존 Organization 모델을 새 Center 모델에 억지로 호환시키지 않는다. 아직 영속 스키마가 없으므로, 제품 모델과 맞지 않는 코드를 먼저 걷어내는 편이 가장 작은 변경이다.

## 3. 목표 구조

### 3.1 모듈형 모놀리스

P0과 P1은 하나의 Spring Boot 애플리케이션과 PostgreSQL/PostGIS에서 처리한다. Redis, MQ, 별도 Python 서비스는 도입하지 않는다.

| 모듈 | 책임 |
|---|---|
| auth, user | 가입, 로그인, 비밀번호 해시, 토큰 발급·검증, 현재 사용자 조회 |
| center | 디렉터리/파트너 센터, 센터 위치, 운영 정보, 센터 소속 권한, 반경 검색 |
| founditem | 습득물 사전 등록, 이미지 저장, 입고, 특징, 상태 전이, 인계 |
| lostreport | 분실 신고, 장소 핀, 신고 소유자 검사 |
| matching | 반경 내 파트너 재고 후보 조회, 점수 계산, Top-5 저장, 제한 응답 |
| point | 포인트 계정 투영, 불변 원장, 후보 열람 차감, 반환 보상 |
| audit | 상태 변경·열람·포인트 변동의 감사 로그, P0 기본 속도 제한 |
| notification, payment, reward, risk | P1에서만 추가할 기능 모듈 |

각 모듈은 Controller, 요청/응답 DTO, Service, Repository, Entity를 같은 모듈 안에 둔다. 모듈 사이에는 다른 모듈의 Controller나 DTO를 직접 호출하지 않고, 필요한 서비스 메서드만 사용한다. 공통 예외·응답·보안 필터만 common/config에 둔다.

### 3.2 인증과 권한 모델

| 구분 | P0 설계 |
|---|---|
| 전역 계정 | 모든 사람은 users의 한 계정을 사용한다. 일반 회원은 습득자와 신고자를 겸할 수 있다. |
| 전역 역할 | USER와 ADMIN을 둔다. ADMIN은 디렉터리/파트너 센터의 기본 관리와 감사 로그 조회를 할 수 있다. |
| 센터 역할 | center_memberships에 CENTER_MANAGER와 CENTER_STAFF를 둔다. 센터별 권한은 토큰 claim만 믿지 않고 매 요청 DB 소속 검사로 판정한다. |
| 비밀번호 | BCrypt 해시만 저장한다. 평문·복호화 가능한 비밀번호는 저장하거나 로그에 남기지 않는다. |
| 토큰 | 짧은 수명의 서명된 액세스 토큰을 발급하고 Spring Security의 stateless 필터에서 검증한다. 비밀키·issuer·만료 시간은 환경 변수로 주입한다. |
| 공개 경로 | 가입, 로그인, health, OpenAPI/Swagger만 공개한다. 나머지는 인증 후 세부 소유권 또는 센터 소속 검사를 통과해야 한다. |

P0에서는 갱신 토큰, 소셜 로그인, 셀프서비스 센터 심사를 만들지 않는다. P1의 직원 초대와 승인 흐름이 필요해질 때 토큰 폐기·초대 만료 정책을 확장한다.

### 3.3 공간·이미지·외부 API 원칙

| 영역 | 선택 | 구현 원칙 |
|---|---|---|
| 공간 검색 | PostgreSQL + PostGIS | 위치 열은 WGS84 geography(Point, 4326)로 두고, Spring Data native query의 ST_DWithin으로 검색한다. 경도·위도 순서를 고정하고 GiST 인덱스를 만든다. 별도 지오메트리 추상화는 만들지 않는다. |
| 지도/장소 | 클라이언트가 선택한 장소명과 좌표를 전달 | 백엔드는 위도·경도 범위, 핀 개수, 중복을 검증해 저장한다. P0에서 지도 제공사 프록시는 만들지 않는다. |
| 파일 저장 | S3 호환 Object Storage | 업로드는 백엔드가 파일 형식·크기·소유자를 검증한 후 서버 생성 key로 저장한다. DB에는 key와 공개 수준만 저장하고, 원본 URL은 절대 반환하지 않는다. 로컬은 MinIO 호환 구성으로 검증한다. |
| 제한 사진 | 짧은 만료의 서명 URL 또는 서버 스트리밍 | 유효한 후보 열람 권한이 있는 신고자에게만 제한 썸네일을 제공한다. 원본과 정확한 보관 위치는 제공하지 않는다. |
| Vision | 외부 Vision API의 직접 연동 클라이언트 | 입고 트랜잭션과 분리해 담당자가 요청할 때 실행한다. 타임아웃/실패는 입고를 실패시키지 않고 감사 로그에 기록한다. 결과는 AI_SUGGESTED로만 저장한다. |
| 속도 제한 | P0 단일 인스턴스 메모리 버킷 | 로그인·신고 생성·후보 열람에 사용자 기준 제한을 둔다. 로그인 전에는 정규화 이메일과 IP를 함께 사용한다. 재시작/다중 인스턴스에 공유되지 않는 한계를 운영 문서에 명시한다. |

### 3.4 추가 의존성의 최소 범위

| 시점 | 추가 대상 | 이유 |
|---|---|---|
| P0 | Spring Security의 JWT/JOSE 모듈 | 직접 토큰 파서를 만들지 않고 서명·검증을 구현한다. |
| P0 | S3 호환 Java SDK | 이미지 원본·제한 썸네일을 Object Storage에 저장하고 짧은 만료 URL을 발급한다. |
| P0 | Bucket4j | 단일 인스턴스의 사용자별 속도 제한을 작게 구현한다. |
| P0 | PostGIS Testcontainer 이미지 | 공간 extension과 ST_DWithin을 실제 DB에서 검증한다. Java 공간 타입 라이브러리는 native query 방식을 선택했으므로 추가하지 않는다. |
| P1 | 선택한 결제·푸시 제공사의 공식 SDK | 제공사와 정책이 확정된 뒤에만 추가한다. |

## 4. 데이터 모델과 Flyway 순서

ID는 현재 엔터티와 같은 BIGINT identity를 사용한다. 시각은 UTC Instant, 외부 API/응답은 ISO-8601로 통일한다. 모든 외래 키, 상태 enum, 유니크 제약은 migration에서 명시한다.

| 순서 | migration과 핵심 테이블 | 핵심 제약 |
|---|---|---|
| V1 | PostGIS extension 및 공통 enum/check 기반 | 테스트 DB와 로컬 DB 모두 PostGIS extension을 생성할 수 있어야 한다. |
| V2 | users, user_roles | email 유니크, password_hash 필수, USER/ADMIN 역할 제약 |
| V3 | lost_centers, center_memberships | kind은 DIRECTORY/PARTNER, 파트너 활성 여부, location GiST 인덱스, (center_id, user_id) 유니크 |
| V4 | found_items, found_item_images, item_features, handover_records | found_item은 finder 필수·holding_center는 nullable, 등록 완료 시 이미지 최소 1장, 사전 등록은 매칭 불가 |
| V5 | lost_reports, report_waypoints | 신고자 필수, 핀 순서 유니크, 신고당 핀 1개 이상은 서비스 검증 |
| V6 | match_candidates, candidate_accesses | 후보 점수·점수 세부값 저장, 활성 후보는 서비스에서 최대 5개, 신고자별 열람 권한 유니크 |
| V7 | point_accounts, point_ledger, return_records, audit_logs | 원장은 append-only, idempotency_key 유니크, 반환 보상 참조 유니크, 감사 metadata는 허용 목록만 저장 |

### 4.1 핵심 테이블별 구현 규칙

| 테이블 | P0 필드와 규칙 |
|---|---|
| lost_centers | 이름, kind, dashboard_enabled, 위치, 연락처, 운영시간, 활성 상태를 저장한다. DIRECTORY는 dashboard_enabled=false여야 하며 보관 센터가 될 수 없다. |
| center_memberships | 사용자와 파트너 센터의 N:M 소속을 표현한다. P0 데모에서는 ADMIN이 생성하거나 seed로 준비한다. 셀프서비스 승인·초대는 P1로 둔다. |
| found_items | finder_id, holding_center_id, category, found_at, found_location, 상태, 제한 보관 위치, 보상 가격대 정책 키를 둔다. 상태는 PRE_REGISTERED → IN_STORAGE → VERIFYING → RETURNED 또는 CLOSED만 허용한다. RETURNED/CLOSED는 다시 후보 풀이 될 수 없다. |
| found_item_images | item_id, object key, visibility, content type, byte size, checksum을 저장한다. 원본과 공개 가능한 제한 썸네일을 구분한다. |
| item_features | category/color/public_description/private_feature, visibility, source를 저장한다. private_feature는 애플리케이션 레벨 암호문으로 저장하고, STAFF_CONFIRMED 공개 값만 매칭에 사용한다. |
| handover_records | item, center, accepted_by, accepted_at, 상태를 이력으로 보존한다. P0에서 보상 가능한 인계는 PARTNER 센터의 ACCEPTED 기록 하나여야 한다. |
| lost_reports | reporter_id, category, 분실 추정 시작/종료 시각, 짧은 설명, 상태, 후보 버전을 저장한다. |
| report_waypoints | report_id, ordinal, place_name, location을 저장한다. 중복 센터는 핀이 여러 개여도 결과에서 한 번만 보인다. |
| match_candidates | report_id, item_id, score, score_breakdown JSON, generation, status를 저장한다. 재매칭 시 이전 활성 후보를 만료 처리하고 새 최대 5건만 활성화한다. |
| candidate_accesses | report_id, user_id, point transaction_id, unlocked_at을 저장한다. 한 신고자는 한 번만 결제하고 이후 유효한 최신 후보의 제한 상세를 볼 수 있다. |
| point_accounts / point_ledger | point_accounts는 현재 잔액 투영과 행 잠금 용도이고, point_ledger가 거래 근거다. 둘은 한 트랜잭션에서 함께 갱신한다. 잔액만 갱신하는 API는 만들지 않는다. |
| return_records | item, report, center, confirmed_by, returned_at를 저장한다. 같은 반환·인계 조합에 보상 원장을 두 번 만들 수 없다. |
| audit_logs | actor, action, target type/id, 허용된 metadata, created_at을 남긴다. 비공개 특징 원문, 비밀번호, 토큰, 원본 이미지 URL은 저장하지 않는다. |

## 5. P0 구현 순서

### P0-0. 프로젝트 기준선 정리

1. event·organization 패키지를 제거한다. 아직 migration과 운영 데이터가 없다는 사전 확인이 전제다.
2. common application.properties를 공통 설정만 남기고, local/test가 DataSource·JPA·Flyway를 실제로 기동하도록 정리한다.
3. Flyway 경로와 naming 규칙을 만든 뒤 V1부터 빈 DB에서 반복 적용한다. Hibernate ddl-auto는 none으로 유지한다.
4. PostGIS 테스트 컨테이너 이미지를 사용하도록 PostgresTestContainerConfig를 교체한다.
5. OpenAPI 서버 URL과 Actuator health 동작을 유지한다.

완료 조건: 빈 로컬 DB와 PostGIS Testcontainer에서 migration이 끝까지 적용되고, 기존 context/health 테스트가 통과한다.

### P0-1. 통합 회원·인증·권한

1. users, user_roles를 구현하고 가입 시 USER 역할을 부여한다.
2. 가입·로그인·현재 사용자 API를 만들고 BCrypt 해시와 signed access token을 적용한다.
3. SecurityConfig를 stateless 인증으로 바꾸고, 현재 사용자·신고 소유자·센터 소속을 검증하는 공통 인가 헬퍼를 둔다.
4. ErrorCode에 중복 이메일, 잘못된 자격 증명, 만료/잘못된 토큰, 센터 소속 없음, 상태 전이 불가, 멱등성 충돌을 추가한다.
5. Swagger에서 인증 토큰을 넣어 보호 API를 시험할 수 있게 Bearer 보안 스키마를 추가한다.

완료 조건: 보호된 API는 무토큰 401, 타인 리소스는 403, 센터 담당자가 아닌 사용자의 입고/반환 요청은 403을 반환한다.

### P0-2. 센터 디렉터리와 장소 핀 반경 검색

1. lost_centers와 center_memberships를 구현한다. P0에서는 ADMIN의 기본 센터 CRUD와 자신의 파트너 센터 운영 정보 수정만 제공한다.
2. 검색 API는 하나 이상의 위도·경도를 받고, 핀별 1 km 기본 반경 내 센터를 ST_DWithin으로 찾는다. 반경은 서버 환경 설정값으로만 조정한다.
3. 검색 결과는 center_id로 중복 제거하고 DIRECTORY/PARTNER를 함께 반환한다. 이 API에는 보관 물품이나 후보 정보를 섞지 않는다.
4. 경도·위도 범위, 핀 최소 개수, 최대 개수, 반경 상한을 요청 검증으로 강제한다.
5. list_school/data의 검증된 센터 CSV를 이용해 개발·데모 seed를 만들되, 원본 수집 스크립트는 런타임 API에 포함하지 않는다.

완료 조건: 한 핀·복수 핀 모두 반경 내 센터를 중복 없이 반환하며, 디렉터리 센터는 후보 재고 검색에 참여하지 않는다.

### P0-3. 습득물 등록, 이미지, 파트너 입고

1. 로그인한 습득자가 사진 한 장 이상, 물품 분류, 수정 가능한 위치·시각으로 PRE_REGISTERED 습득물을 등록하게 한다.
2. 파일은 content type, 파일 크기, 이미지 디코딩 가능 여부를 검증한 뒤 Object Storage에 저장한다. 실패한 파일은 아이템을 만들지 않거나 보상 트랜잭션과 분리해 정리한다.
3. 파트너 센터 담당자는 사전 등록 건을 검색해 ACCEPTED handover와 IN_STORAGE 상태로 전환하거나, 현장 신규 입고를 등록한다.
4. 담당자는 보관 위치, 공개 특징, 비공개 특징을 기록한다. 정확한 보관 위치와 비공개 특징은 센터 소속 사용자만 읽을 수 있다.
5. Vision 제안 API는 입고 뒤 명시적으로 실행한다. AI 결과는 AI_SUGGESTED로 저장하고, 담당자가 확정하지 않은 값은 후보 점수에 사용하지 않는다.
6. 상태 전이는 한 Service 메서드에서 검증하고, 전이·입고·특징 변경마다 audit_logs를 남긴다.

완료 조건: PRE_REGISTERED 물품은 후보에 포함되지 않고, 파트너 센터가 수락해 IN_STORAGE가 된 물품만 매칭 후보가 된다. Vision 실패만으로 입고가 실패하지 않는다.

### P0-4. 분실 신고, 센터 안내, Top-5 매칭

1. 로그인한 신고자가 물품 분류, 분실 추정 시간 범위, 짧은 설명, 장소 핀 1개 이상을 한 요청으로 생성한다.
2. 신고 생성 뒤 센터 안내와 후보 생성은 같은 입력을 사용하되 결과를 분리한다. 센터 안내는 모든 센터, 후보 풀은 반경 내 PARTNER 센터의 IN_STORAGE 물품만 사용한다.
3. 초기 점수는 아래 고정 가중합을 서버에서 계산하고, 각 부분 점수를 score_breakdown에 저장한다.

   - route_proximity_score: 습득 지점과 모든 장소 핀 중 최소 거리
   - time_score: 분실 추정 시각과 습득 시각 차이
   - category_score: 신고 분류와 센터 확정 분류 일치도
   - color_score: 담당자 확정 색상 유사도
   - description_similarity_score: 신고 설명과 담당자 확정 공개 설명 유사도

   가중치는 각각 0.35, 0.20, 0.20, 0.15, 0.10이며 합계는 항상 1.0이어야 한다.

4. matching은 비공개 특징, AI 미확정 값, 원본 이미지 내용을 읽지 않는다. 후보를 점수 내림차순과 안정적인 ID tie-breaker로 정렬해 최대 5개 저장한다.
5. 잠긴 후보 목록 API는 candidate ID와 점수만 반환한다. 물품명, 사진, 특징, 센터 식별 정보, 보관 위치를 포함하지 않는다.
6. 재매칭 API는 신고 소유자만 실행할 수 있으며 기존 활성 후보를 만료 처리한 뒤 새 후보를 만든다.

완료 조건: 후보가 0~5개인 경우 모두 정상 처리되고, 잠긴 응답의 JSON에 금지 정보가 직렬화되지 않는다.

### P0-5. 후보 열람, 포인트, 반환 보상, 최소 악용 방지

1. 가입 처리에서 point_accounts와 한 번의 DEMO_GRANT 원장을 함께 만들고, 이미 존재하는 개발 계정은 동일 원칙의 seed로만 초기화한다.
2. 후보 열람 API는 신고 소유자와 Idempotency-Key를 요구한다. 같은 키의 재시도는 기존 결과를 반환하고 새 차감을 만들지 않는다.
3. 하나의 트랜잭션에서 point_accounts 행을 잠그고 잔액을 확인한 뒤, CANDIDATE_ACCESS_DEBIT 원장·candidate_accesses·잔액 투영을 함께 만든다.
4. 열람 권한이 생긴 뒤에도 후보 상세 API는 대략적 분류·색상, 제한 사진, 발견 시각 범위, 보관 센터 연락 경로만 반환한다. 원본·비공개 특징·정확한 보관 위치는 계속 제외한다.
5. 센터 담당자의 반환 API는 해당 센터 소속, 대상 아이템의 ACCEPTED 파트너 인계, 아직 반환되지 않음, 신고/후보 관계를 검증한다.
6. 반환 확정 트랜잭션에서 RETURNED 상태, return_records, RETURN_REWARD_CREDIT 원장, 습득자 잔액 투영을 함께 처리한다. 반환 ID를 참조하는 유니크 제약으로 보상 중복을 막는다.
7. 로그인, 신고 생성, 후보 열람, 상태 변경, 포인트 변동을 audit_logs에 기록한다. 로그인·신고·열람에는 P0 메모리 버킷 속도 제한을 적용한다.

완료 조건: 같은 열람 요청과 같은 반환 요청을 반복해도 원장 차감·적립은 각각 한 번만 기록되며, 잔액 부족·타인 신고·타 센터 반환은 안전하게 거부된다.

## 6. P0 API 계약 초안

모든 새 API는 /api/v1 접두사를 사용한다. 요청 검증 실패는 기존 ErrorResponse 형식을, 시각은 UTC ISO-8601을 사용한다.

| 영역 | 주요 API | 접근 규칙 |
|---|---|---|
| 인증 | POST /auth/signup, POST /auth/login, GET /users/me | 가입/로그인만 공개 |
| 센터 | GET /centers/nearby, GET /centers/{id}, POST/PATCH /admin/centers, PATCH /centers/{id}/profile | nearby는 로그인 사용자, 관리 변경은 ADMIN 또는 해당 CENTER_MANAGER |
| 습득물 | POST /found-items, GET /found-items/mine, GET /centers/{centerId}/found-items, POST /centers/{centerId}/found-items/{itemId}/receive, PATCH /centers/{centerId}/found-items/{itemId} | 등록자 본인 또는 해당 센터 소속 |
| 이미지·특징 | POST /found-items/{id}/images, POST /centers/{centerId}/found-items/{itemId}/vision-suggestions, PATCH /centers/{centerId}/found-items/{itemId}/features | 원본·비공개 특징은 센터 소속만 |
| 신고·매칭 | POST /lost-reports, GET /lost-reports/mine, GET /lost-reports/{id}/centers, GET /lost-reports/{id}/candidates, POST /lost-reports/{id}/matches:refresh | 신고 소유자만 |
| 열람·포인트 | POST /lost-reports/{id}/candidate-accesses, GET /lost-reports/{id}/candidates/unlocked, GET /points/balance, GET /points/ledger | 신고·포인트 소유자만, 열람 POST는 Idempotency-Key 필수 |
| 반환·감사 | POST /centers/{centerId}/returns, GET /admin/audit-logs | 반환은 해당 센터 소속, 감사 조회는 ADMIN |

상세 DTO에는 Entity를 직접 노출하지 않는다. 각 응답 DTO는 잠김/해제 상태에 맞춰 별도 타입으로 만들고, Jackson 직렬화 우연에 의존해 숨기지 않는다.

## 7. P1 구현 순서

P1은 P0의 migration, 권한, 원장, 감사 로그, 후보 score_breakdown이 검증된 뒤에만 시작한다.

### P1-1. 실제 포인트 충전과 현물 교환

1. 선택한 결제대행사의 결제 생성·승인·웹훅 검증을 point 모듈에 추가한다.
2. payment_orders와 provider_transaction_id 유니크 제약을 두고, 승인 웹훅의 서명·금액·통화·주문 상태를 검증한 후에만 POINT_PURCHASE_CREDIT 원장을 기록한다.
3. 환불은 원 결제·원장 참조를 보존하는 역거래로 처리한다. 금액, 영수증, 약관, 개인정보 처리, 환불 정책은 연동 시작 전에 확정한다.
4. reward_catalog와 reward_redemptions를 추가해 현물 교환을 구현한다. 재고 차감과 REWARD_REDEMPTION_DEBIT 원장은 하나의 트랜잭션에서 처리하고, 재고 부족·중복 요청·취소를 감사한다.

완료 조건: 웹훅 재전송은 포인트를 중복 충전하지 않고, 재고가 1개일 때 동시 교환은 한 건만 성공한다.

### P1-2. 비도입 센터 증빙 보상과 센터 운영 워크플로

1. handover_evidences를 추가해 디렉터리 센터 인계 영수증·확인 자료를 비공개 Object Storage에 보관한다.
2. ADMIN 검토 상태를 PENDING, APPROVED, REJECTED로 관리하고, APPROVED 전환에서만 DIRECTORY_HANDOVER_REWARD_CREDIT 원장을 한 번 만든다.
3. center_applications와 초대 토큰 해시/만료 정보를 추가한다. 파트너 승인과 직원 초대·수락으로 center_memberships를 만들고 역할을 부여한다.
4. 기존 P0 센터 CRUD와 소속 검사 규칙을 재사용한다. P1에서도 디렉터리 센터가 후보 풀에 들어가지 않는 규칙은 바꾸지 않는다.

완료 조건: 증빙 승인 재시도와 초대 링크 재사용이 중복 보상·중복 소속을 만들지 않으며, 승인되지 않은 센터 직원은 재고를 관리할 수 없다.

### P1-3. 알림, 후보 근거, 위험 신호, 중복 경고

1. notification_devices와 notification_outbox를 추가하고, 후보 생성·입고 연결·소유 확인 요청·반환·포인트 적립 이벤트를 기록한다. 작은 주기 작업으로 전송하고 실패 상태를 보존한다.
2. P0의 score_breakdown에서 개인정보를 포함하지 않는 근거만 추려, 열람 권한이 있는 신고자에게 제한 문구로 제공한다. 가중치나 비공개 특징 원문은 응답하지 않는다.
3. audit_logs의 열람·결제·신고 이벤트를 기반으로 risk_assessments와 검토 큐를 만든다. 위험 점수는 자동 제재가 아니라 ADMIN 검토 우선순위로만 사용한다.
4. 이미지 checksum, 시간, 위치, 분류를 이용해 유사한 신규 입고를 찾아 담당자에게 경고한다. 자동 병합·자동 삭제는 하지 않는다.

완료 조건: 동일 이벤트는 알림을 한 번만 전송 대상으로 만들고, 후보 근거·위험 화면·중복 경고가 비공개 특징이나 원본 사진을 노출하지 않는다.

## 8. 테스트와 수동 검증 계획

### 8.1 자동 테스트

| 범위 | 검증 |
|---|---|
| migration | 빈 PostGIS Testcontainer에서 V1~최신 migration을 적용하고 모든 테이블·공간 인덱스·유니크 제약을 확인한다. |
| 인증/인가 | 비밀번호 해시, 토큰 인증, 무토큰 401, 타인 신고 403, 타 센터 입고/반환 403을 API 통합 테스트로 확인한다. |
| 공간 검색 | 단일/복수 핀, 반경 경계, 중복 센터 제거, DIRECTORY/PARTNER 동시 반환을 검증한다. |
| 습득물 상태 | 허용/비허용 상태 전이, 파트너 센터만 입고 가능, PRE_REGISTERED/RETURNED/CLOSED 후보 제외를 확인한다. |
| 매칭 | 후보 0개·1개·5개·초과 후보, 가중치 합계, 안정적 정렬, 비공개 특징 제외, 잠긴 응답의 필드 부재를 확인한다. |
| 포인트 | 잔액 부족, 동일 Idempotency-Key 재시도, 동시 열람, 동일 반환 재시도에서 원장과 잔액이 정확히 한 번만 바뀌는지 확인한다. |
| P1 | 웹훅 재전송, 교환 재고 경쟁, 증빙 승인 재시도, 만료 초대, 알림 outbox 재시도를 각각 통합 테스트로 확인한다. |

기본 검증 명령은 Backend 디렉터리에서 실행하는 ./gradlew test다. Testcontainer가 필요하므로 Docker가 실행 중이어야 한다.

### 8.2 P0 수동 QA 시나리오

1. ADMIN이 같은 생활권의 DIRECTORY 센터와 PARTNER 센터를 준비한다.
2. 일반 사용자가 로그인해 사진 1장과 분류만으로 습득물을 사전 등록하고, 파트너 담당자가 이를 입고 처리해 공개/비공개 특징을 나눈다.
3. 다른 일반 사용자가 두 개의 장소 핀으로 분실 신고를 만든다. 센터 목록에는 두 종류의 센터가 모두 보이고, 잠긴 후보에는 점수만 보이는지 확인한다.
4. 후보 열람을 같은 Idempotency-Key로 두 번 요청한다. 원장 차감은 한 건이고, 해제 뒤에도 비공개 특징·원본 사진·정확한 보관 위치가 보이지 않는지 확인한다.
5. 담당자가 반환을 기록한 뒤 습득자 보상 원장이 한 건 생성되는지, 같은 반환을 다시 요청해도 중복 보상이 없는지 확인한다.
6. Swagger와 /actuator/health가 정상 응답하며, 보호 API와 감사 로그의 권한 경계가 올바른지 확인한다.

### 8.3 P1 수동 QA 시나리오

1. 결제 웹훅을 같은 provider_transaction_id로 재전송해도 충전 원장이 하나인지 확인한다.
2. 재고 1개 보상을 두 사용자가 동시에 교환할 때 하나만 확정되는지 확인한다.
3. 디렉터리 센터 인계 증빙이 ADMIN 승인 전에는 보상되지 않고, 승인 뒤 한 번만 보상되는지 확인한다.
4. 입고·반환 이벤트가 알림 outbox와 수신 기기에 한 번만 전달되며, 위험/중복 경고가 담당자 검토용으로만 표시되는지 확인한다.

## 9. 구현 전 확정할 운영값

다음 값은 코드에 하드코딩하지 않고 환경 설정 또는 관리되는 정책 값으로 둔다. P0 착수 전에 데모값을 정해 테스트 fixture와 문서에 함께 고정한다.

| 값 | 초기 기준 |
|---|---|
| 센터 검색 기본 반경 | 1 km |
| 후보 열람 비용 | 데모 포인트 정책으로 결정 |
| 가입 초기 포인트와 반환 보상 가격대 | 데모 포인트 정책으로 결정 |
| 액세스 토큰 만료·서명 키 | 배포 환경의 보안 정책으로 결정 |
| 업로드 허용 형식·최대 크기·보존 기간 | Object Storage와 개인정보 정책에 맞춰 결정 |
| Vision 제공사와 요청 보존 정책 | 개인정보 처리 검토 후 결정 |
| P1 결제대행사·현물 공급 방식 | 환불·영수증·세무·제휴 정책 확정 후 결정 |

## 10. 완료 기준

P0 완료는 다음을 모두 만족할 때다.

- Flyway와 PostGIS를 사용하는 빈 DB 재현이 가능하다.
- 행사 도메인이 코드와 새 스키마에 남아 있지 않다.
- 센터 탐색, 파트너 재고 매칭, 잠긴/해제 후보 응답, 입고·반환·포인트 흐름이 실제 API에서 동작한다.
- 비공개 특징·원본 사진·정확한 보관 위치가 비권한 API 응답과 로그에 포함되지 않는다.
- 인증·센터 소속·신고 소유권·포인트 멱등성·상태 전이 테스트가 통과한다.
- 자동 테스트와 8.2의 수동 QA가 같은 빌드에서 통과한다.

P1 완료는 P0 기준을 유지하면서, 결제/현물/증빙/초대/알림/위험/중복 기능의 8.1·8.3 검증을 모두 통과하는 것이다.
