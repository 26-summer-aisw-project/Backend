# Backend

Backend project of LOSTORY.

## Requirements

- Java 21
- Docker Desktop
- Git

## Local Setup

Clone the repository.

```bash
git clone https://github.com/26-summer-aisw-project/Backend.git
cd Backend
```

## Data

분실물 센터 수집하는 코드, 실행파일, 결과물(csv파일)은 모두 'list_school/data'에 존재합니다.

## Local Test

테스트를 실행하기 전에 Docker Desktop을 실행시킵니다.
왜냐하면, TestContainers를 사용해 테스트용 PostgreSQL 컨테이너를 실행하기에 DOcker가 실행중이지 않으면 실패할 수 있습니다.

```powershell
.\gradlew.bat test
```

## Docs

- [ERD.md](docs/ERD.md) : 개발에 필요한 Entity들을 문서화한 것
  - [ERD.html](docs/ERD.html) : ERD
- [LOSTORY_PRODUCT_PLAN.md](docs/LOSTORY_PRODUCT_PLAN.md) : 초기 기획서
- [MVP_IMPLEMENTATION_PLAN.md](docs/MVP_IMPLEMENTATION_PLAN.md) : MVP 기준 기획
- [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) : MVP 기준 구현 계획서
- [API_SPEC.md](docs/API_SPEC.md) : 

