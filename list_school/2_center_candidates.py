from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import csv
import json
import os
import time

from dotenv import load_dotenv

PROJECT_ROOT = Path(r"C:\dev\projects\lostory-backend")
DATA_DIR = Path(r"C:\Users\User\Desktop\SSU\2026-Summer\소프트웨어공모전\data\list_school")

load_dotenv(PROJECT_ROOT / ".env")

API_KEY = os.environ.get("KAKAO_REST_API_KEY")

if API_KEY:
    API_KEY = API_KEY.strip().strip('"').strip("'")

if not API_KEY:
    raise SystemExit("KAKAO_REST_API_KEY is missing. Check your .env file.")

INPUT = DATA_DIR / "universities_seoul_seed.csv"
OUTPUT = DATA_DIR / "center_candidates_raw.csv"

KEYWORDS = [
    "분실물",
    "유실물",
    "학생지원센터",
    "학생지원팀",
    "종합서비스센터",
    "총무팀",
    "경비실",
    "중앙도서관",
    "생활관",
    "기숙사",
    "경찰서",
    "지구대",
    "파출소",
]

def kakao_get(url, params):
    full_url = url + "?" + urlencode(params)
    req = Request(full_url, headers={"Authorization": f"KakaoAK {API_KEY}"})

    try:
        with urlopen(req, timeout=10) as res:
            return json.loads(res.read().decode("utf-8"))

    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise SystemExit(
            f"\nKakao API HTTP Error: {e.code}\n"
            f"URL: {full_url}\n"
            f"Response body: {body}\n"
            f"API key loaded: {'yes' if API_KEY else 'no'}\n"
            f"API key length: {len(API_KEY) if API_KEY else 0}\n"
            f"Check that .env contains only the REST API key, not KakaoAK or JavaScript key.\n"
        )

    except URLError as e:
        raise SystemExit(f"Network error: {e}")

def geocode(address):
    data = kakao_get(
        "https://dapi.kakao.com/v2/local/search/address.json",
        {"query": address},
    )

    docs = data.get("documents", [])
    if not docs:
        return None

    return {
        "lat": docs[0].get("y", ""),
        "lng": docs[0].get("x", ""),
    }

def search_keyword(query, lat=None, lng=None, radius=1500):
    params = {
        "query": query,
        "size": 10,
        "page": 1,
    }

    if lat and lng:
        params["y"] = lat
        params["x"] = lng
        params["radius"] = radius

    data = kakao_get(
        "https://dapi.kakao.com/v2/local/search/keyword.json",
        params,
    )

    return data.get("documents", [])

def main():
    if not INPUT.exists():
        raise SystemExit(f"Input file not found: {INPUT}")

    with INPUT.open("r", encoding="utf-8-sig", newline="") as f:
        universities = list(csv.DictReader(f))

    if not universities:
        raise SystemExit("No universities found in seed file.")

    rows = []
    seen = set()

    print(f"Loaded universities: {len(universities)}")
    print(f"API key length: {len(API_KEY)}")

    for univ in universities:
        name = univ.get("name", "").strip()
        address = univ.get("address", "").strip()

        if not name or not address:
            print(f"SKIP invalid row: {univ}")
            continue

        anchor = geocode(address)

        if not anchor:
            print(f"MISS anchor: {name} / {address}")
            continue

        print(f"\n[{name}] anchor: {anchor['lat']}, {anchor['lng']}")

        for keyword in KEYWORDS:
            query = f"{name} {keyword}"
            docs = search_keyword(query, lat=anchor["lat"], lng=anchor["lng"])

            print(f"- {query}: {len(docs)}")

            for doc in docs:
                place_id = doc.get("id", "")
                place_name = doc.get("place_name", "")
                road_address = doc.get("road_address_name", "")
                jibun_address = doc.get("address_name", "")
                address_value = road_address or jibun_address

                key = place_id or f"{place_name}|{address_value}"

                if key in seen:
                    continue

                seen.add(key)

                rows.append({
                    "candidate_id": f"kakao_{place_id}" if place_id else f"candidate_{len(rows) + 1}",
                    "name": place_name,
                    "parent_place": name,
                    "phone": doc.get("phone", ""),
                    "address": address_value,
                    "lat": doc.get("y", ""),
                    "lng": doc.get("x", ""),
                    "center_role": "",
                    "type": "",
                    "coverage_scope": "",
                    "detail_location": "",
                    "operating_hours": "",
                    "handoff_available": "",
                    "inquiry_available": "",
                    "website": doc.get("place_url", ""),
                    "source_type": "kakao_local_api",
                    "source_url": "https://developers.kakao.com/docs/en/local/dev-guide",
                    "verification_status": "auto_collected",
                    "confidence_score": "",
                    "notes": f"source_query={query}; category={doc.get('category_name', '')}",
                })

            time.sleep(0.2)

    fieldnames = [
        "candidate_id",
        "name",
        "parent_place",
        "phone",
        "address",
        "lat",
        "lng",
        "center_role",
        "type",
        "coverage_scope",
        "detail_location",
        "operating_hours",
        "handoff_available",
        "inquiry_available",
        "website",
        "source_type",
        "source_url",
        "verification_status",
        "confidence_score",
        "notes",
    ]

    with OUTPUT.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nSaved: {OUTPUT}")
    print(f"Candidates: {len(rows)}")

if __name__ == "__main__":
    main()
