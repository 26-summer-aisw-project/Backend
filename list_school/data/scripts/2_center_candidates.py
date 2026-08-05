from __future__ import annotations

import csv
import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[3]
DATA_DIR = Path(__file__).resolve().parents[1]
INPUT = DATA_DIR / "universities_seoul_seed.csv"
OUTPUT = DATA_DIR / "center_candidates_raw.csv"

KEYWORDS = [
    "분실물",
    "유실물",
    "학생지원센터",
    "학생지원팀",
    "학생서비스센터",
    "종합서비스센터",
    "원스톱서비스센터",
    "총무팀",
    "경비실",
    "안내데스크",
    "중앙도서관",
    "생활관",
    "기숙사",
    "경찰서",
    "지구대",
    "파출소",
]


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def kakao_api_key() -> str:
    load_env_file(DATA_DIR / ".env")
    load_env_file(ROOT / ".env")

    api_key = os.environ.get("KAKAO_REST_API_KEY", "").strip().strip('"').strip("'")
    if not api_key:
        raise SystemExit(
            "KAKAO_REST_API_KEY is missing. "
            "Create data/list_school/.env from data/list_school/.env.example."
        )
    return api_key


API_KEY = kakao_api_key()


def kakao_get(url: str, params: dict[str, str | int]) -> dict:
    full_url = url + "?" + urlencode(params)
    request = Request(full_url, headers={"Authorization": f"KakaoAK {API_KEY}"})

    try:
        with urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise SystemExit(
            f"Kakao API HTTP Error: {error.code}\n"
            f"URL: {full_url}\n"
            f"Response body: {body}\n"
            "Check that OPEN_MAP_AND_LOCAL is enabled and the REST API key is correct."
        )
    except URLError as error:
        raise SystemExit(f"Network error: {error}")


def geocode(address: str) -> dict[str, str] | None:
    data = kakao_get("https://dapi.kakao.com/v2/local/search/address.json", {"query": address})
    documents = data.get("documents", [])
    if not documents:
        return None

    return {
        "lat": documents[0].get("y", ""),
        "lng": documents[0].get("x", ""),
    }


def search_keyword(query: str, lat: str | None = None, lng: str | None = None, radius: int = 1500) -> list[dict]:
    params: dict[str, str | int] = {
        "query": query,
        "size": 10,
        "page": 1,
    }

    if lat and lng:
        params["y"] = lat
        params["x"] = lng
        params["radius"] = radius

    data = kakao_get("https://dapi.kakao.com/v2/local/search/keyword.json", params)
    return data.get("documents", [])


def main() -> None:
    if not INPUT.exists():
        raise SystemExit(f"Input file not found: {INPUT}")

    with INPUT.open("r", encoding="utf-8-sig", newline="") as file:
        universities = list(csv.DictReader(file))

    if not universities:
        raise SystemExit("No universities found in seed file.")

    rows: list[dict[str, str]] = []
    seen: set[str] = set()

    print(f"Loaded universities: {len(universities)}")
    print(f"API key length: {len(API_KEY)}")

    for university in universities:
        university_id = university.get("university_id", "").strip()
        name = university.get("name", "").strip()
        address = university.get("address", "").strip()

        if not university_id or not name or not address:
            print(f"SKIP invalid row: {university}")
            continue

        anchor = geocode(address)
        if not anchor:
            print(f"MISS anchor: {name} / {address}")
            continue

        print(f"\n[{name}] anchor: {anchor['lat']}, {anchor['lng']}")

        for keyword in KEYWORDS:
            query = f"{name} {keyword}"
            documents = search_keyword(query, lat=anchor["lat"], lng=anchor["lng"])
            print(f"- {query}: {len(documents)}")

            for document in documents:
                place_id = document.get("id", "")
                place_name = document.get("place_name", "")
                road_address = document.get("road_address_name", "")
                jibun_address = document.get("address_name", "")
                address_value = road_address or jibun_address
                dedupe_key = place_id or f"{place_name}|{address_value}"

                if dedupe_key in seen:
                    continue
                seen.add(dedupe_key)

                rows.append(
                    {
                        "candidate_id": f"kakao_{place_id}" if place_id else f"candidate_{len(rows) + 1}",
                        "university_id": university_id,
                        "name": place_name,
                        "parent_place": name,
                        "phone": document.get("phone", ""),
                        "address": address_value,
                        "lat": document.get("y", ""),
                        "lng": document.get("x", ""),
                        "category_name": document.get("category_name", ""),
                        "distance_m": document.get("distance", ""),
                        "place_url": document.get("place_url", ""),
                        "source_query": query,
                    }
                )

            time.sleep(0.2)

    fieldnames = [
        "candidate_id",
        "university_id",
        "name",
        "parent_place",
        "phone",
        "address",
        "lat",
        "lng",
        "category_name",
        "distance_m",
        "place_url",
        "source_query",
    ]

    with OUTPUT.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nSaved: {OUTPUT}")
    print(f"Candidates: {len(rows)}")


if __name__ == "__main__":
    main()
