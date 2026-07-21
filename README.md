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

## Local Test
테스트를 실행하기 전에 Docker Desktop을 실행시킵니다.
왜냐하면, TestContainers를 사용해 테스트용 PostgreSQL 컨테이너를 실행하기에 DOcker가 실행중이지 않으면 실패할 수 있습니다.

```powershell
.\gradlew.bat test
```
