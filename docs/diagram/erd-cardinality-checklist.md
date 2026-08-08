# LOSTORY ERD 카디널리티 검증 체크리스트

기준: `../LOSTORY_PRODUCT_PLAN.md`와 `erd-spec.json`  
표기: 왼쪽 엔터티의 참여 수 `min..max` — 오른쪽 엔터티의 참여 수 `min..max`

| 관계 | 카디널리티 | 근거 | 검증 |
|---|---|---|---|
| `lost_centers` — `center_memberships` | `lost_centers 0..*` — `center_memberships 1..1` | 디렉터리 센터는 담당자가 없을 수 있고, 하나의 소속은 하나의 센터에만 속한다. | 반대 방향과 모순 없음 |
| `users` — `center_memberships` | `users 0..*` — `center_memberships 1..1` | 일반 회원은 센터 소속이 없을 수 있고, 한 담당자는 여러 센터에 소속될 수 있다. | N:M을 조인 엔터티로 분해함 |
| `users` — `found_items` | `users 0..*` — `found_items 1..1` | 한 습득자는 여러 물건을 등록할 수 있고, 모든 습득물은 로그인한 등록자가 있어야 한다. | 등록자 FK는 NOT NULL |
| `lost_centers` — `found_items` | `lost_centers 0..*` — `found_items 0..1` | 센터는 여러 물건을 보관할 수 있으나, 인계 전 사전 등록 물건은 보관 센터가 없다. | `holding_center_id` NULL 허용; 파트너 센터만 참조 가능 |
| `users` — `lost_reports` | `users 0..*` — `lost_reports 1..1` | 한 회원은 여러 신고를 만들 수 있고, 각 신고에는 한 명의 신고자가 있다. | 신고자 FK는 NOT NULL |
| `found_items` — `found_item_images` | `found_items 1..*` — `found_item_images 1..1` | 습득물 등록에는 사진이 최소 한 장 필요하고, 사진은 한 습득물에만 연결된다. | 등록 완료 전 이미지 수 확인 |
| `found_items` — `item_features` | `found_items 0..*` — `item_features 1..1` | 입고 전에는 특징이 없을 수 있으며, 특징은 한 물건에만 속한다. | AI 제안과 담당자 확정값 모두 이력으로 가능 |
| `found_items` — `handover_records` | `found_items 0..*` — `handover_records 1..1` | 아직 인계하지 않았거나, 이관/재접수로 복수 이력이 존재할 수 있다. | P0 확정 인계는 하나지만 삭제하지 않고 이력 보존 |
| `lost_reports` — `report_waypoints` | `lost_reports 1..*` — `report_waypoints 1..1` | 단일 장소 검색도 핀 1개로 저장하며, 복수 핀은 경로 근사 검색에 쓴다. | 신고 생성 시 핀 수 최소 1 검증 |
| `found_items` — `match_candidates` | `found_items 0..*` — `match_candidates 1..1` | 한 보관 물건은 여러 신고의 후보가 될 수 있고, 후보는 한 물건만 가리킨다. | `IN_STORAGE` 및 파트너 센터 조건 검사 |
| `lost_reports` — `match_candidates` | `lost_reports 0..*` — `match_candidates 1..1` | 조건에 맞는 후보가 없을 수 있고, 후보는 한 신고의 결과다. | 활성 후보는 서비스 계층에서 최대 5개 강제 |
| `lost_reports` — `candidate_accesses` | `lost_reports 0..*` — `candidate_accesses 1..1` | 신고자는 열람하지 않을 수 있고, 재실행·정책 변경에 따른 열람 이력을 남길 수 있다. | 본인 신고에 대한 권한만 허용 |
| `candidate_accesses` — `point_ledger` | `candidate_accesses 1..1` — `point_ledger 0..1` | 유료 열람은 반드시 한 건의 차감 원장을 사용하며, 일반 포인트 적립은 열람 권한이 없다. | 원장 참조와 멱등 키 유니크 |
| `return_records` — `point_ledger` | `return_records 0..1` — `point_ledger 0..1` | 반환 후 보상 정책 미충족·보류일 수 있고, 확정 보상은 한 번만 발행한다. | 반환 ID 참조 보상 원장 유니크 |

## 추가 무결성 점검

- [x] 센터 담당자 N:M 관계는 `center_memberships`로 분해했다.
- [x] 후보는 `found_items`와 `lost_reports`를 참조하는 교차 엔터티로 분해했다.
- [x] 후보 열람·반환 보상은 포인트 원장 참조로 추적한다.
- [x] 디렉터리 센터가 후보 매칭 풀에 들어가지 않도록 `dashboard_enabled`를 도메인 제약으로 정의했다.
- [x] 비공개 특징은 후보 테이블이나 후보 API가 아닌 `item_features`의 권한 보호 값으로만 보관한다.
- [x] 정확한 소유자 판정 관계를 만들지 않았다. `return_records`는 센터가 수행한 반환 처리의 기록이다.
