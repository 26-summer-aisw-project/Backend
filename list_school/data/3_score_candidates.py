from __future__ import annotations

import csv
from collections import Counter, defaultdict
from pathlib import Path


DATA_DIR = Path(__file__).resolve().parent
INPUT = DATA_DIR / "center_candidates_raw.csv"
OUTPUT = DATA_DIR / "center_candidates_review.csv"

LOST_KEYWORDS = ["분실", "유실", "lost", "found"]
PRIMARY_KEYWORDS = ["분실물센터", "유실물센터", "학생지원", "학생서비스", "종합서비스", "원스톱", "총무"]
LOCAL_HANDOFF_KEYWORDS = ["경비", "안내", "관리실", "도서관", "생활관", "기숙사"]
PUBLIC_POLICE_KEYWORDS = ["경찰서", "지구대", "파출소", "치안센터"]
PUBLIC_TRANSIT_KEYWORDS = ["서울교통공사", "역유실물", "지하철", "철도", "코레일"]
PLACE_ONLY_KEYWORDS = ["강의동", "체육관", "공학관", "인문관", "사회관", "법학관", "학생회관"]
NEGATIVE_KEYWORDS = ["식당", "카페", "커피", "술집", "혼술", "카레", "문구", "편의점", "마트", "캐터링"]
OFF_TOPIC_SUPPORT_KEYWORDS = ["장애학생지원", "입학", "취업", "상담", "창업", "국제교류"]

ROLE_PRIORITY = {
    "primary": 1,
    "official_local": 2,
    "public_fallback": 3,
    "online_board": 4,
    "candidate_needs_call": 5,
    "place_only": 8,
    "non_lost_support": 9,
    "reject_likely": 10,
}


def contains_any(text: str, keywords: list[str]) -> bool:
    lowered = text.lower()
    return any(keyword.lower() in lowered for keyword in keywords)


def row_text(row: dict[str, str]) -> str:
    return " ".join(
        [
            row.get("name", ""),
            row.get("parent_place", ""),
            row.get("category_name", ""),
            row.get("address", ""),
            row.get("source_query", ""),
        ]
    )


def infer_role(row: dict[str, str]) -> str:
    text = row_text(row)

    if contains_any(text, NEGATIVE_KEYWORDS):
        return "reject_likely"
    if contains_any(text, OFF_TOPIC_SUPPORT_KEYWORDS):
        return "non_lost_support"
    if contains_any(text, PUBLIC_POLICE_KEYWORDS + PUBLIC_TRANSIT_KEYWORDS):
        return "public_fallback"
    if contains_any(text, LOST_KEYWORDS) and contains_any(text, PRIMARY_KEYWORDS):
        return "primary"
    if contains_any(text, PRIMARY_KEYWORDS):
        return "candidate_needs_call"
    if contains_any(text, LOCAL_HANDOFF_KEYWORDS):
        return "official_local"
    if contains_any(text, PLACE_ONLY_KEYWORDS):
        return "place_only"
    if contains_any(text, LOST_KEYWORDS):
        return "candidate_needs_call"
    return "reject_likely"


def score_candidate(row: dict[str, str], role: str) -> int:
    text = row_text(row)
    score = {
        "primary": 85,
        "official_local": 70,
        "public_fallback": 65,
        "candidate_needs_call": 55,
        "place_only": 25,
        "non_lost_support": 15,
        "reject_likely": 5,
    }.get(role, 0)

    if contains_any(text, LOST_KEYWORDS):
        score += 10
    if row.get("phone", "").strip():
        score += 5
    if row.get("place_url", "").strip():
        score += 3
    if contains_any(text, NEGATIVE_KEYWORDS + OFF_TOPIC_SUPPORT_KEYWORDS):
        score -= 25

    return max(0, min(score, 100))


def review_decision(role: str) -> tuple[str, str]:
    if role in {"primary", "official_local"}:
        return "review_first", "공식 출처 확인 후 master 반영 검토"
    if role == "public_fallback":
        return "fallback_only", "학교 센터가 아닌 공공 fallback으로만 검토"
    if role == "candidate_needs_call":
        return "verify_before_use", "전화 또는 공식 페이지 확인 필요"
    return "exclude", "서비스 후보에서 제외 권장"


def candidate_key(row: dict[str, str]) -> str:
    place_url = row.get("place_url", "").strip()
    if place_url:
        return place_url
    return f"{row.get('parent_place', '')}|{row.get('name', '')}|{row.get('address', '')}"


def analyze_row(row: dict[str, str]) -> dict[str, str]:
    role = infer_role(row)
    score = score_candidate(row, role)
    decision, notes = review_decision(role)

    return {
        "university_id": row.get("university_id", ""),
        "parent_place": row.get("parent_place", ""),
        "name": row.get("name", ""),
        "role": role,
        "score": str(score),
        "review_decision": decision,
        "review_notes": notes,
        "phone": row.get("phone", ""),
        "address": row.get("address", ""),
        "lat": row.get("lat", ""),
        "lng": row.get("lng", ""),
        "category_name": row.get("category_name", ""),
        "place_url": row.get("place_url", ""),
        "source_query": row.get("source_query", ""),
        "distance_m": row.get("distance_m", ""),
    }


def is_better_candidate(new_row: dict[str, str], old_row: dict[str, str]) -> bool:
    new_rank = ROLE_PRIORITY.get(new_row["role"], 99)
    old_rank = ROLE_PRIORITY.get(old_row["role"], 99)
    if new_rank != old_rank:
        return new_rank < old_rank
    return int(new_row["score"]) > int(old_row["score"])


def main() -> None:
    if not INPUT.exists():
        raise SystemExit(f"Input file not found: {INPUT}. Run 2_center_candidates.py first.")

    with INPUT.open("r", encoding="utf-8-sig", newline="") as file:
        raw_rows = list(csv.DictReader(file))

    if not raw_rows:
        raise SystemExit(f"No rows found in {INPUT}")

    deduped: dict[str, dict[str, str]] = {}

    for raw_row in raw_rows:
        analyzed = analyze_row(raw_row)
        key = candidate_key(raw_row)

        if key not in deduped or is_better_candidate(analyzed, deduped[key]):
            deduped[key] = analyzed

    output_rows = sorted(
        deduped.values(),
        key=lambda row: (
            row["parent_place"],
            ROLE_PRIORITY.get(row["role"], 99),
            -int(row["score"]),
            row["name"],
        ),
    )

    fieldnames = [
        "university_id",
        "parent_place",
        "name",
        "role",
        "score",
        "review_decision",
        "review_notes",
        "phone",
        "address",
        "lat",
        "lng",
        "category_name",
        "place_url",
        "source_query",
        "distance_m",
    ]

    with OUTPUT.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(output_rows)

    role_counts = Counter(row["role"] for row in output_rows)
    grouped = defaultdict(list)
    for row in output_rows:
        if row["role"] not in {"reject_likely", "place_only", "non_lost_support"}:
            grouped[row["parent_place"]].append(row)

    print(f"Raw candidates: {len(raw_rows)}")
    print(f"Deduped candidates: {len(output_rows)}")
    print(f"Saved review file: {OUTPUT}")
    print("Role counts:")
    for role, count in sorted(role_counts.items(), key=lambda item: ROLE_PRIORITY.get(item[0], 99)):
        print(f"- {role}: {count}")

    print("\nTop candidates by university:")
    for parent_place in sorted(grouped):
        print(f"\n[{parent_place}]")
        for row in grouped[parent_place][:5]:
            print(f"- {row['score']} | {row['name']} | {row['role']} | {row['review_decision']}")


if __name__ == "__main__":
    main()
