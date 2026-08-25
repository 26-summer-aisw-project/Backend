# LOSTORY 백엔드 구현 계획

**상태:** 확정
**기준 문서:** [MVP_IMPLEMENTATION_PLAN.md](./MVP_IMPLEMENTATION_PLAN.md)
**문서 우선순위:** MVP 구현 기준 → 이 기술 계획 → API 계약 → 이전 제품 기획. 충돌 시 상위 문서를 따른다.

## 1. 목표와 비목표

P0는 센터 대시보드 없이 개인 습득물 등록, 센터 안내, 신고별 점수 후보를 제공한다. P1은 파트너 센터와 실제 담당자 화면을 함께 도입해 사용자 인계 주장 수락, 반환, 포인트 후보 상세 열람을 제공한다.

P0에서 만들지 않는 것:

- `center_partnerships`, 센터 담당 권한, 센터 입고·반환 대시보드
- 포인트 잔액·원장·후보 상세 열람
- 실제 결제, 현물 교환, 익명 메시지, 정확한 비센터 보관 위치 공개
- 다중 Vision 제공사, 자동 대체, 별도 큐·Redis·MQ

## 2. 확정 설계 요약

| 영역 | P0 | P1 |
|---|---|---|
| 센터 | 인증된 P0 사용자용 디렉터리와 1 km 내 거리순 최대 10개 추천 | ADMIN 승인 파트너와 대시보드 전용 관리 계정 |
| 습득물 | `DRAFT` 생성, Vision 추출, 사용자 특징 확정, 보관 방식·인계 확정 | 센터가 기존 사용자 인계 주장을 실물 확인해 수락 |
| 후보 | 모든 `ACTIVE` 항목으로 Top-5, ID·순위·점수만 반환 | 신고별 1회 포인트 차감 후 제한 상세 반환 |
| 반환 | 신고는 닫을 수 있으나 FoundItem은 반환 처리하지 않음 | 담당자가 반환 기록, FoundItem `RETURNED`, 보상 지급 |
| 포인트 | 없음 | 가입 10, 상세 열람 1, 센터 확인 반환 5. 모두 설정값 |

## 3. 모듈과 경계

| 모듈 | P0 책임 | P1 확장 |
|---|---|---|
| auth, user | 가입·로그인·USER/ADMIN 인가 | 대시보드 전용 관리 계정 활성화 |
| center | 센터 디렉터리, 반경 검색, 인계 후보 추천 | 신청·승인·파트너십·담당자 대시보드 |
| founditem | 업로드, DRAFT, Vision, 사용자 확정 특징, 보관 방식 | 센터 수락·비공개 특징·반환 상태 |
| lostreport, matching | 핀, 반경 스냅샷, Top-5, stale | 열람 권한 뒤 제한 상세 |
| point | 없음 | 계정·원장·신고 단위 멱등 열람·반환 보상 |
| audit | 로그인·등록·수정·매칭 기록 | 승인·수락·반환·포인트 기록 |

모듈은 다른 모듈의 Controller·DTO를 호출하지 않는다. 소유권·상태 전이·포인트 차감은 Service 트랜잭션 경계에서 검증한다.

## 4. 데이터와 Flyway

현재 저장소의 migration inventory는 `V1`–`V26`이다. 이미 적용된 migration은 수정하거나 재번호하지 않는다. P0 보강은 `V20`–`V26`에 순서대로 반영됐으며 후속 스키마 변경은 V27부터 추가한다.

### 4.1 적용된 P0 migration

1. **V20: FoundItem 수명주기·사진·Vision·신고 매칭 보강**
   - `DRAFT`, `PENDING_HANDOVER`, `ACTIVE`, `EXPIRED`, `RETURNED` 상태와 `vision_status`를 지원한다.
   - 등록 전 필드의 nullable 제약, 24시간 `DRAFT` 정리 기준, `handed_at`, 사용자 인계 상태와 변경 이력을 추가한다.
   - `PENDING_HANDOVER`는 매칭 쿼리에서 제외한다.
2. **V21: 레거시 종료 인계 삭제 trigger 수정**
   - DELETE에서 `OLD`를 반환해 종료 레거시 행 정리 동작을 보존한다.
3. **V22: UTC 일별 Vision 작업 예약량**
   - 전체 일일 한도를 DB에서 원자적으로 예약하고 실패·supersede 시 반환한다.
4. **V23: ADMIN 검증 센터 상태 허용**
   - `admin_verified` 센터를 P0 추천 가능 검증 상태에 포함한다.
5. **V24: 인계 대기 항목 만료 허용**
   - `PENDING_HANDOVER`가 인계 확인 없이 `EXPIRED/NONE`으로 전이될 수 있게 CHECK를 보강한다.
6. **V25: 결정적 특징 동률 허용**
   - 동일 ordinal 특징을 보존하고 최종 ID tie-break index로 결정적으로 선택한다.
7. **V26: 삭제 사용자 상태 허용**
   - `users.status` CHECK에 `DELETED`를 허용한다. 기준 migration은 `V26__allow_deleted_users.sql`이다.

`V20`에 포함된 주요 구성은 다음과 같다.

- **이미지·Vision 작업 정보**
   - FE→BE 업로드 메타데이터, 분석 상태, AI 원문과 사용자가 확정한 공개 특징을 구분한다.
   - AI 원문은 후보 응답에 직렬화하지 않는다.
- **센터 디렉터리 보강**
   - 활성 상태·위치·연락처·보존 정책을 유지하고, 단일 습득 위치에서 1 km 내 최대 10개를 찾는 GiST 쿼리를 만든다.
- **신고 안내·매칭 보강**
   - `effective_search_radius_meters`, 정책 버전, `center_guidance` 스냅샷, 후보 stale 상태를 추가한다.
   - 후보는 모든 `ACTIVE`·미만료 FoundItem에서 최대 5개를 저장한다.

### 4.2 P1 migration 순서

1. `center_applications`, 신규 대시보드 관리 계정의 활성화 토큰 해시·만료, `center_partnerships`를 추가한다.
2. `handover_records`, `return_records`를 추가한다. 기존 `USER_CONFIRMED`를 대시보드 수락 대기열로 조회할 수 있게 한다.
3. `point_accounts`, `point_ledger`, `candidate_accesses`를 추가한다.
   - `report_id + user_id` 열람 권한과 `idempotency_key`는 유니크다.
   - 기존 활성 사용자와 P1 이후 가입자는 정확히 한 번 `DEMO_GRANT`를 받는다.
   - `return_records` 참조 보상은 유니크다.

스키마를 실제로 바꾸기 전 [ERD.md](./ERD.md)의 필드·제약도 같은 결정으로 갱신한다. 이 계획은 ERD 변경 자체를 포함하지 않는다.

## 5. P0 구현 순서

### P0-0. 기준선과 환경 설정

1. 기존 Flyway·PostGIS Testcontainer·JWT·health 검증을 유지한다.
2. 다음 설정을 `@ConfigurationProperties`로 제공한다.

   | 설정 | 초기값 |
   |---|---:|
   | `found-item.draft-ttl` | 24 h |
   | `found-item.terminal-media-retention` | 30 d |
   | `center.nearby-radius` | 1,000 m |
   | `center.nearby-limit` | 10 |
   | `matching.radius-min` / `base` / `max` | 500 m / 1,000 m / 3,000 m |
   | `matching.radius-coefficient` | 0.10 |
   | `matching.radius-policy-version` | 배포별 값 |
   | `found-item.ttl` / `lost-report.ttl` | 14 d / 14 d |
   | `matching.time-window` | 24 h |
   | `vision` 제공사·보존·처리 지역·비용 한도 | Google Cloud Vision / global / 0 s / USD 10 |
   | `vision.daily-job-limit` | UTC 하루 100 |

3. 단일 Vision 제공사를 선정하고 학습 미사용, 보존 기간, 처리 지역, 비용 한도를 문서화한다. 조건을 충족하지 않으면 Vision 호출을 출시하지 않는다.

완료 조건: 빈 PostGIS DB에서 migration이 적용되고 설정값으로 테스트가 재현된다.

### P0-1. 인증 사용자용 디렉터리

1. 기존 USER/ADMIN JWT 인증을 유지하고, 본인 리소스 소유권과 ADMIN 권한만 검증한다.
2. 인증된 사용자의 센터 디렉터리 조회·ADMIN 생성/수정 API를 구현한다.
3. 습득 위치 기준 활성·검증 센터를 고정 1 km 안에서 거리순 최대 10개 반환한다. `admin_verified`는 디렉터리·안내에는 포함하지만 P0 인계 확정은 세 공식 검증 상태만 허용한다. 목록 밖·비활성·반경 밖 센터는 선택할 수 없다.

완료 조건: 무토큰은 401, 타인 리소스는 404/403 정책대로 거부되며, 비활성·반경 밖 센터는 인계 대상으로 선택되지 않는다.

### P0-2. 습득물 등록과 Vision

1. FE가 사진을 BE로 업로드하면 소유자 전용 `DRAFT`와 이미지 행을 만든다.
2. 비동기 Vision 작업은 `PENDING`, `PROCESSING`, `READY`, `FAILED`, `SUPERSEDED` 작업 상태와 공개 `PENDING`, `READY`, `FAILED` 상태를 기록한다. 실패해도 등록은 계속할 수 있다.
3. 습득자는 장소·시각·분류를 입력하고 AI 제안을 수정 또는 승인한다. 후보에 노출할 특징은 사용자 확정 값만 사용한다. Vision이 `PENDING` 또는 `FAILED`여도 수동 입력·확정으로 등록을 계속할 수 있다.
4. `LEFT_IN_PLACE`·`MOVED_TO_SAFE_PLACE`는 필수 보관 정보가 갖춰지면 `ACTIVE`가 된다.
5. `HANDED_TO_CENTER`는 추천 센터 선택 뒤 `PENDING_HANDOVER`가 되고, 별도 인계 확정 요청의 서버 시각에 `USER_CONFIRMED`·`ACTIVE`가 된다.
6. P0 인계 확정은 사용자 진술인 `USER_CONFIRMED` 상태다. 소유자는 P1 센터 수락 전까지 수정·철회할 수 있고 변경은 감사한다.
7. `DRAFT` 정리와 종료 항목 미디어 30일 정리를 예약 작업으로 수행한다.
8. 사진 변경만 Vision 재분석을 요청한다. 사진 이외의 모든 `PATCH`는 재분석 없이 후보만 stale 처리한다.

완료 조건: 인계 대기 항목은 후보에 없고, Vision `PENDING`·`FAILED`와 누락 특징이 등록을 막지 않으며, 사진 변경은 재분석과 후보 stale을 일으키고 그 밖의 `PATCH`는 후보만 stale 처리한다.

### P0-3. 신고, 센터 안내, 점수 후보

1. 신고 생성·수정에서 핀을 1개 이상 받아 `effectiveSearchRadiusMeters`를 계산한다.
2. 인접 핀 거리 중앙값으로 `clamp(min, max, base + coefficient × median)`을 계산하고 정책 버전·`centerGuidance` 스냅샷을 저장한다.
3. `centerGuidance`는 신고 생성과 조회 응답에 포함한다. 신고 수정 때만 새로 계산한다.
4. 모든 `ACTIVE`·미만료 FoundItem에 대해 위치 0.35, 시간 0.20, 분류 0.20, 색상 0.15, 공개 설명 0.10의 고정 가중합을 계산한다. 누락 특징 점수는 0이고 가중치를 재분배하지 않는다. 시간 근접 창 기본값은 `PT24H`다.
5. 반경은 최종 미터만 `HALF_UP` 정수 반올림하고 점수는 중간 반올림 없이 최종값만 소수 둘째 자리 `HALF_UP`으로 만든다. 점수 내림차순·FoundItem ID 오름차순으로 최대 5개 후보를 원자적으로 교체한다.
6. P0 후보 API의 후보 배열 원소는 정확히 `candidateId`, `rank`, `score`만 갖는다. 응답 최상위에서 허용하는 추가 필드는 `lastMatchedAt`, `candidatesStale`뿐이다. 신고 종료는 LostReport만 닫는다.

완료 조건: 0~5개 후보, 핀 중복 제거, 동적 반경의 최소·최대 경계, stale 재계산, 상세 필드 부재가 통합 테스트로 검증된다.

### P0-4. 수동 QA

1. 사진 업로드 후 등록을 중단해 24시간 뒤 DRAFT와 미디어가 정리되는지 확인한다.
2. Vision 실패 후에도 수동 확정으로 등록이 완료되는지 확인한다.
3. [FE/mobile 수동 QA 경계] 센터 선택 후 NAVER 지도 딥링크로 이동하고, 앱의 미완료 인계 안내에서 인계를 확정하는지 확인한다.
4. 단일·복수 핀 신고에서 저장된 센터 안내와 점수 전용 후보를 확인한다.
5. 습득물 수정 뒤 열린 신고가 stale이 되고 재계산되는지 확인한다.
6. 실 Vision·S3 저장 검증은 [docs/operations/vision-and-storage-demo.md](./operations/vision-and-storage-demo.md) runbook을 따른다.

## 6. P1 구현 순서

### P1-1. 파트너 신청과 대시보드 계정

1. ADMIN이 오프라인 검토 뒤 기존 디렉터리 센터와 중복되지 않는 새 관리 이메일을 `PENDING`으로 연결한다.
2. 승인 뒤 토큰 해시만 저장한 24시간·1회 링크를 생성한다. ADMIN은 링크를 별도 채널로 전달하며 재발급 시 이전 링크를 폐기한다.
3. 비밀번호 설정이 끝나면 정확히 하나의 대시보드 전용 관리 계정과 활성 `center_partnership`을 만든다.
4. React Native 담당자 화면에서 대기 인계, 수락, 보관·특징, 반환 목록을 제공한다.

완료 조건: 기존 일반 계정은 연결할 수 없고, 만료·사용된 링크는 활성화할 수 없으며, 활성 관리 계정만 자기 센터 대시보드를 볼 수 있다.

### P1-2. 센터 수락, 반환, 포인트

1. 선택된 센터 담당자는 `USER_CONFIRMED` 대기열에서 실물 존재를 확인하고 `CENTER_CONFIRMED`로 수락하거나 거절한다.
2. 수락 뒤 센터 담당자는 비공개 특징과 센터 보관 정보를 관리한다. P0 후보 풀은 계속 모든 `ACTIVE` 항목을 사용한다.
3. 후보 상세 열람은 신고 소유자와 Idempotency-Key를 요구한다. 같은 신고의 재시도·재매칭은 추가 차감하지 않는다.
4. 상세 응답에는 제한 썸네일, 분류, 대략적 발견일, 사용자 확정 공개 특징만 넣는다. 정확한 위치·보관 설명·습득자 식별자는 제외한다. 사용자 인계 확정 항목에는 `센터 검증 전` 또는 센터 확인 상태와 센터 연락처를 포함한다.
5. 센터 담당자의 반환은 수락된 인계와 신고 관계를 검증하고, `RETURNED`, `return_records`, 보상 원장을 한 트랜잭션에서 처리한다.

완료 조건: 신고별 열람 차감은 한 번, 반환 보상은 한 번만 기록되며, 타 센터·타 신고·만료 링크·비활성 관리 계정은 모두 거부된다.

### P1-3. 수동 QA

1. ADMIN이 새 센터와 관리 계정을 만들고 별도 채널의 링크로 활성화한다.
2. 관리자가 P0 사용자 인계 주장을 실물 확인 후 수락하고, 거절 기록이 보존되는지 확인한다.
3. 일반 사용자가 같은 신고의 상세을 여러 번 열고 후보를 재매칭해도 포인트가 한 번만 차감되는지 확인한다.
4. 담당자가 반환을 기록할 때만 습득자 보상이 한 번 생성되는지 확인한다.

## 7. API 경계

| 릴리스 | 주요 API |
|---|---|
| P0 | 인증·사용자, 센터 디렉터리·인계 후보, DRAFT 업로드·등록 확정·인계 확정, 신고·센터 안내·점수 후보 |
| P1 | ADMIN 파트너 등록·승인·활성화, 센터 대시보드 인계 수락·반환, 후보 상세 열람, 포인트 잔액·원장 |

경로·요청·응답 payload는 [API_SPEC.md](./API_SPEC.md)를 이 계획과 함께 재작성해야 한다. 구현은 새 계약이 승인되기 전의 이전 v1 payload를 기준으로 시작하지 않는다.

## 8. 전체 완료 기준

- P0과 P1 경계에 `center_partnerships`·대시보드·포인트·반환 기능이 섞여 있지 않다.
- 모든 스키마 변경은 적용 이력이 있는 Flyway migration 뒤에 추가된다.
- P0·P1 자동 테스트와 위 수동 QA가 같은 빌드에서 통과한다.
- 후보·응답·감사 로그에 비공개 특징, 정확한 위치, 원본 이미지, 비밀값이 없다.
