# Vision·Object Storage 데모 운영 기준

이 문서는 P0 데모에서 Google Cloud Vision과 Google Cloud Storage를 제한적으로 켜고, 검증 후 안전하게 끄는 절차다. 기본 설정은 `VISION_ENABLED=false`, `OBJECT_STORAGE_ENABLED=false`이며 실제 자격 증명이 없는 테스트는 fake만 사용한다.

## 데이터 처리와 허용 기능

- 온라인 `BatchAnnotateImages`만 사용한다. Google 문서에 따르면 온라인 요청 이미지는 메모리에서 처리되고 디스크에 저장되지 않으며, 요청 시각·크기 같은 일부 메타데이터는 일시적으로 기록될 수 있다. 전송 콘텐츠는 서비스 제공 외 목적으로 사용하거나 모델 학습에 사용하지 않는다는 제공사 정책을 데모 전에 다시 확인한다.
- 요청 feature는 `LABEL_DETECTION`과 `IMAGE_PROPERTIES` 두 개만 허용한다. 얼굴, OCR, 로고, 랜드마크, 웹 탐색, SafeSearch 기능은 요청하지 않는다.
- 각 feature는 이미지당 별도 과금 단위다. Google의 현재 가격표와 프로젝트 Billing 보고서를 데모 전 확인한다.
- 원본 이미지는 애플리케이션 버킷에만 보존한다. Vision 원문은 후보·로그·감사 metadata에 넣지 않고, 사용자가 확정한 공개 특징만 후보 계산에 사용한다.

공식 기준: [Vision 데이터 사용](https://docs.cloud.google.com/vision/docs/data-usage), [Vision 가격](https://cloud.google.com/vision/pricing).

## 비용과 호출량 보호

1. 데모 전용 프로젝트에 월 USD 10 예산과 50%, 80%, 100% 이메일 알림을 만든다.
2. 예산 알림은 지출 상한이 아니므로 `VISION_DAILY_JOB_LIMIT=100`의 DB 원자적 예약을 함께 유지한다.
3. 80% 알림부터 새 live 테스트를 중단하고, 100% 또는 비정상 증가 시 `VISION_ENABLED=false`로 재배포한 뒤 Vision API를 비활성화한다.
4. Billing 예산 범위가 데모 프로젝트와 Vision/Storage 비용을 포함하는지 확인한다.

공식 기준: [Cloud Billing 예산 알림](https://docs.cloud.google.com/billing/docs/how-to/budgets). 알림만으로 사용량이나 비용이 자동 차단되지 않는다.

## 최소 권한과 비밀 주입

- 운영 workload에는 사용자 관리 service account를 연결하고 Application Default Credentials를 사용한다. Owner/Editor/Viewer 기본 역할과 장기 service-account key 파일은 사용하지 않는다.
- 애플리케이션 버킷에는 필요한 object create/read/delete 권한만 부여하고 다른 버킷 접근은 차단한다. Vision 요청이 GCS URI를 사용하지 않으므로 광범위한 `storage.objectViewer` 프로젝트 권한은 부여하지 않는다.
- `JWT_SECRET`, ADC, 버킷 이름과 배포 환경값은 secret manager/배포 플랫폼 환경 변수로 주입한다. 저장소, PR 본문, 로그, 데모 영상에 값이나 credential 경로를 남기지 않는다.
- 데모 전 service account 권한, API enable 상태, 버킷 CORS/공개 접근 차단을 별도 계정으로 확인한다.

공식 기준: [Vision 인증](https://docs.cloud.google.com/vision/docs/authentication), [Google Cloud 인증](https://docs.cloud.google.com/docs/authentication).

## fake 테스트와 live 데모 분리

| 구분 | 설정 | 통과 기준 |
|---|---|---|
| 자동/로컬 테스트 | 두 provider `enabled=false`, in-memory fake | 전체 Gradle 테스트가 네트워크·실제 GCP 없이 통과 |
| 제한 live 확인 | 전용 프로젝트와 버킷, 두 provider `enabled=true` | 사진 한 장 업로드, 현재 사진 조회·교체, Vision READY/FAILED, 이전 객체 outbox 삭제를 확인 |

live 확인은 자동 테스트의 대체가 아니다. 먼저 fake 전체 테스트를 통과한 뒤 승인된 운영자가 최소 이미지로 한 번 수행한다. 실제 GCP 접근이 없었다면 PR에 “live GCP 미실행”을 명시한다.

## 데모 녹화 체크리스트

1. 테스트용 비식별 이미지를 준비하고 업로드 동의를 확인한다.
2. DRAFT 생성 → 사진 조회 → Vision 제안 → 사용자 특징 확정 → 필요 시 사진 교체 → 센터 안내/인계 확인 → 신고 후보 갱신 순서로 녹화한다.
3. 화면에는 객체 키, 정확한 비센터 위치, 사용자 식별자, Authorization header, 환경 변수, 콘솔 credential을 노출하지 않는다.
4. Billing 알림과 API disable 화면은 민감한 프로젝트 정보를 가린 별도 캡처로 남긴다.
5. 녹화 파일의 접근자를 제한하고 발표 종료 뒤 보존 여부를 결정한다.

## 종료, 복구, 레거시 미디어

1. 데모 직후 `VISION_ENABLED=false`, `OBJECT_STORAGE_ENABLED=false`로 재배포하고 Vision API를 비활성화한다. 예약 작업이 남아 있으면 새 호출이 없는지 확인한다.
2. DRAFT는 기본 24시간 뒤 DB와 미디어를 정리하고, `EXPIRED/RETURNED` 미디어는 기본 30일 뒤 삭제 outbox로 보낸다. outbox의 `PENDING/PROCESSING/DONE`, lease, 재시도와 오류 코드를 모니터링한다.
3. 복구할 때는 DB 백업을 먼저 복원하고 동일 버킷 객체를 연결한다. DB가 가리키지 않는 객체는 `OBJECT_STORAGE_ORPHAN_GRACE=PT1H` 뒤 sweep 대상으로 처리한다.
4. V19 이전 `legacy_storage_path` 미디어는 자동으로 신규 `object_key`라고 간주하지 않는다. 파일 존재·소유자·해시를 확인해 명시적으로 이관하거나, 종료·보존 정책에 따라 삭제하고 감사 근거를 남긴다.
5. 객체를 먼저 지우고 DB를 복원하면 참조가 깨질 수 있으므로 outbox 완료 시점보다 오래된 백업 복원은 별도 복구 검토를 거친다.
