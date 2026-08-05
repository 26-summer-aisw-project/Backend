from pathlib import Path
from collections import defaultdict, Counter
import csv
import re

DATA_DIR = Path(r"C:\Users\User\Desktop\SSU\2026-Summer\소프트웨어공모전\data\list_school")
SCRIPT_DIR = Path(__file__).resolve().parent

INPUT = DATA_DIR / "center_candidates_raw.csv"
OUTPUT = DATA_DIR / "center_candidates_review.csv"

if not INPUT.exists():
    local_input = SCRIPT_DIR / "center_candidates_raw.csv"
    if local_input.exists():
        INPUT = local_input
        OUTPUT = SCRIPT_DIR / "center_candidates_review.csv"


PRIMARY_CENTER_KEYWORDS = [
    "분실물센터",
    "유실물센터",
    "분실물",
    "유실물",
    "학생지원센터",
    "학생지원팀",
    "학생처",
    "학생서비스센터",
    "종합서비스센터",
    "종합서비스",
    "총무팀",
    "총무처",
    "사무처",
]

LOST_SPECIFIC_KEYWORDS = [
    "분실물",
    "유실물",
    "lost",
    "found",
]

HUMAN_MANAGED_KEYWORDS = [
    "센터",
    "지원",
    "학생처",
    "학생지원",
    "학생서비스",
    "종합서비스",
    "행정실",
    "관리실",
    "관리사무소",
    "경비실",
    "안내데스크",
    "안내소",
    "안내",
    "사무실",
    "데스크",
    "고객센터",
    "민원실",
    "사무처",
    "총무팀",
    "총무처",
]

LOCAL_HANDOFF_KEYWORDS = [
    "행정실",
    "관리실",
    "관리사무소",
    "경비실",
    "안내데스크",
    "안내소",
    "사무실",
    "데스크",
    "민원실",
]

PLACE_ONLY_KEYWORDS = [
    "중앙도서관",
    "도서관",
    "생활관",
    "기숙사",
    "학생회관",
    "복지관",
    "체육관",
    "강의동",
    "공학관",
    "인문관",
    "사회관",
    "법학관",
    "경영관",
    "과학관",
    "박물관",
]

PUBLIC_POLICE_KEYWORDS = [
    "경찰서",
    "지구대",
    "파출소",
    "치안센터",
    "경찰청",
    "서울경찰청",
]

PUBLIC_TRANSIT_KEYWORDS = [
    "서울교통공사",
    "역유실물센터",
    "지하철",
    "철도",
    "코레일",
    "공항철도",
    "도시철도",
]

NEGATIVE_BUSINESS_KEYWORDS = [
    "식당",
    "카페",
    "커피",
    "술집",
    "혼술",
    "포차",
    "호프",
    "펍",
    "음식점",
    "분식",
    "캐터링",
    "케이터링",
    "푸드",
    "푸드코트",
    "매점",
    "편의점",
    "마트",
    "베이커리",
    "치킨",
    "피자",
    "버거",
    "김밥",
    "도시락",
    "떡볶이",
    "부동산",
    "원룸",
    "고시원",
    "학원",
    "주차장",
    "미용실",
    "네일",
    "노래방",
    "PC방",
    "피시방",
    "호텔",
    "모텔",
    "병원",
    "약국",
    "은행",
    "ATM",
    "헬스",
    "필라테스",
    "요가",
    "사진관",
    "인쇄",
    "복사",
    "문구",
    "서점",
    "스터디카페",
]

OFF_TOPIC_SUPPORT_KEYWORDS = [
    "장애학생지원",
    "장애학생지원센터",
    "상담센터",
    "상담",
    "취업",
    "취업지원",
    "창업",
    "입학",
    "입학처",
    "국제",
    "국제처",
    "교환학생",
    "어학",
    "장학",
    "장학팀",
    "예비군",
    "보건",
    "보건실",
    "인권",
    "성평등",
]

ROLE_PRIORITY = {
    "primary": 0,
    "local_handoff": 1,
    "public_official": 2,
    "needs_review": 3,
    "info_only": 4,
    "place_only": 5,
    "non_lost_support": 6,
    "other_university": 7,
    "reject_likely": 8,
}

ROLE_BASE_SCORE = {
    "primary": 60,
    "local_handoff": 50,
    "public_official": 45,
    "needs_review": 30,
    "info_only": 15,
    "place_only": 20,
    "non_lost_support": 0,
    "other_university": 10,
    "reject_likely": 0,
}


def get(row, *keys):
    for key in keys:
        value = row.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def normalize_text(value):
    value = str(value or "").strip()
    value = re.sub(r"\s+", " ", value)
    return value


def normalize_key(value):
    return re.sub(r"\s+", "", str(value or "").lower())


def contains_any(text, keywords):
    lower_text = str(text or "").lower()
    return any(keyword.lower() in lower_text for keyword in keywords)


def has_lost_keyword(text):
    return contains_any(text, LOST_SPECIFIC_KEYWORDS)


def has_other_university(candidate_text, parent_place):
    mentions = set(re.findall(r"[가-힣A-Za-zA-Z0-9]+대학교", candidate_text or ""))
    if not mentions:
        return False

    parent_norm = normalize_key(parent_place)

    for mention in mentions:
        if normalize_key(mention) != parent_norm:
            return True

    return False


def is_human_managed_place(candidate_text):
    return contains_any(candidate_text, HUMAN_MANAGED_KEYWORDS)


def is_place_only_name(candidate_text):
    return contains_any(candidate_text, PLACE_ONLY_KEYWORDS) and not is_human_managed_place(candidate_text)


def to_int(value):
    try:
        return int(float(str(value).strip()))
    except (TypeError, ValueError):
        return None


def build_candidate_text(row):
    name = get(row, "name", "place_name", "candidate_name")
    category = get(row, "category_name", "category")
    address = get(row, "address", "address_name", "road_address", "road_address_name")

    return normalize_text(" ".join([name, category, address]))


def infer_role(row):
    parent_place = get(row, "parent_place", "university_name", "name_ko")
    candidate_text = build_candidate_text(row)

    if contains_any(candidate_text, NEGATIVE_BUSINESS_KEYWORDS):
        return "reject_likely"

    if has_other_university(candidate_text, parent_place):
        return "other_university"

    if contains_any(candidate_text, OFF_TOPIC_SUPPORT_KEYWORDS) and not has_lost_keyword(candidate_text):
        return "non_lost_support"

    if contains_any(candidate_text, PUBLIC_POLICE_KEYWORDS):
        return "public_official"

    if contains_any(candidate_text, PUBLIC_TRANSIT_KEYWORDS):
        return "public_official"

    if contains_any(candidate_text, PRIMARY_CENTER_KEYWORDS):
        return "primary"

    if is_place_only_name(candidate_text):
        return "place_only"

    if contains_any(candidate_text, LOCAL_HANDOFF_KEYWORDS):
        return "local_handoff"

    if is_human_managed_place(candidate_text):
        return "local_handoff"

    if has_lost_keyword(candidate_text):
        return "needs_review"

    return "needs_review"


def infer_type(role, candidate_text):
    if role == "reject_likely":
        return "invalid_business"

    if role == "other_university":
        return "other_university"

    if role == "non_lost_support":
        return "non_lost_support_office"

    if role == "public_official":
        if contains_any(candidate_text, PUBLIC_TRANSIT_KEYWORDS):
            return "public_transit_lost_center"
        return "police_or_public_center"

    if contains_any(candidate_text, ["도서관"]):
        return "library"

    if contains_any(candidate_text, ["생활관", "기숙사"]):
        return "dormitory"

    if contains_any(candidate_text, ["경비실", "관리실", "관리사무소"]):
        return "facility_office"

    if role == "primary":
        return "university_primary_candidate"

    if role == "local_handoff":
        return "campus_handoff_candidate"

    if role == "info_only":
        return "information_office"

    if role == "place_only":
        return "place_only_not_center"

    return "unknown"


def infer_scope(role, place_type):
    if role == "primary":
        return "campus_wide_candidate"

    if role == "local_handoff":
        if place_type == "library":
            return "library_only_candidate"
        if place_type == "dormitory":
            return "dormitory_only_candidate"
        return "building_or_unit_candidate"

    if role == "public_official":
        return "public_fallback"

    if role == "needs_review":
        return "manual_review_required"

    if role == "info_only":
        return "information_only_candidate"

    if role == "place_only":
        return "place_only_not_center"

    if role == "non_lost_support":
        return "exclude_non_lost_support"

    if role == "other_university":
        return "exclude_other_university"

    if role == "reject_likely":
        return "exclude"

    return "unknown"


def calculate_score(row, role, candidate_text):
    if role in {"reject_likely", "non_lost_support"}:
        return 0

    score = ROLE_BASE_SCORE.get(role, 20)

    parent_place = get(row, "parent_place", "university_name", "name_ko")
    phone = get(row, "phone", "main_phone", "telephone")
    address = get(row, "address", "address_name", "road_address", "road_address_name")
    lat = get(row, "lat", "y")
    lng = get(row, "lng", "x")
    place_url = get(row, "place_url", "source_url", "website", "url")
    distance = to_int(get(row, "distance_m", "distance"))

    if parent_place and normalize_key(parent_place) in normalize_key(candidate_text):
        score += 15

    if has_lost_keyword(candidate_text):
        score += 10

    if phone:
        score += 5

    if address:
        score += 5

    if lat and lng:
        score += 5

    if place_url:
        score += 5

    if distance is not None:
        if distance <= 300:
            score += 10
        elif distance <= 1000:
            score += 5
        elif distance >= 3000:
            score -= 10

    if role == "public_official":
        score = min(score, 70)

    if role == "info_only":
        score = min(score, 45)

    if role == "needs_review":
        score = min(score, 60)

    if role == "place_only":
        score = min(score, 30)

    if role == "other_university":
        score = min(score, 20)

    return max(0, min(score, 100))


def review_decision_and_notes(role):
    if role == "primary":
        return (
            "priority_review",
            "학교 전체 분실물 창구일 가능성이 높다. 웹페이지나 전화로 실제 담당 여부 확인 필요.",
        )

    if role == "local_handoff":
        return (
            "usable_after_verify",
            "건물 또는 부서 단위 접수·보관처일 가능성이 있다. 실제 보관·반환 업무를 하는지 확인 필요.",
        )

    if role == "public_official":
        return (
            "fallback_only",
            "학교 내부 센터가 아니라 공공 유실물 fallback이다. 학교 후보보다 후순위로 사용한다.",
        )

    if role == "needs_review":
        return (
            "manual_review",
            "자동 판단이 애매한 후보다. 웹페이지, 전화번호, 장소명을 사람이 확인해야 한다.",
        )

    if role == "info_only":
        return (
            "verify_before_use",
            "안내 또는 지원 부서일 가능성이 있다. 실제 분실물 보관처인지 확인 전에는 매칭 풀에 넣지 않는다.",
        )

    if role == "place_only":
        return (
            "do_not_use_until_desk_verified",
            "건물명만 확인된 상태다. 안내데스크·행정실·관리실처럼 사람이 관리하는 단위로 보정되어야 한다.",
        )

    if role == "non_lost_support":
        return (
            "exclude",
            "분실물 접수·보관 부서가 아니라 특정 지원 업무 부서로 보인다. 분실물센터 후보에서 제외한다.",
        )

    if role == "other_university":
        return (
            "exclude",
            "다른 대학 후보로 보인다. 현재 대학의 보관처 후보에서 제외한다.",
        )

    if role == "reject_likely":
        return (
            "exclude",
            "음식점·매장·상업시설 등 분실물센터가 아닌 후보로 보인다.",
        )

    return "manual_review", "확인 필요."


def merge_source_queries(a, b):
    values = []

    for item in [a, b]:
        if not item:
            continue

        for part in str(item).split(" | "):
            part = part.strip()
            if part and part not in values:
                values.append(part)

    return " | ".join(values)


def is_better_candidate(a, b):
    a_rank = ROLE_PRIORITY.get(a["role"], 99)
    b_rank = ROLE_PRIORITY.get(b["role"], 99)

    if a_rank != b_rank:
        return a_rank < b_rank

    return int(a["score"]) > int(b["score"])


def candidate_key(row):
    parent_place = normalize_key(row["parent_place"])
    name = normalize_key(row["name"])
    phone = normalize_key(row["phone"])
    address = normalize_key(row["address"])
    lat = normalize_key(row["lat"])
    lng = normalize_key(row["lng"])

    stable_location = phone or address or f"{lat},{lng}"

    return parent_place, name, stable_location


def analyze_row(row):
    university_id = get(row, "university_id", "school_id")
    parent_place = get(row, "parent_place", "university_name", "name_ko")
    name = get(row, "name", "place_name", "candidate_name")
    phone = get(row, "phone", "main_phone", "telephone")
    address = get(row, "address", "address_name", "road_address", "road_address_name")
    lat = get(row, "lat", "y")
    lng = get(row, "lng", "x")
    category_name = get(row, "category_name", "category")
    place_url = get(row, "place_url", "source_url", "website", "url")
    source_query = get(row, "source_query", "query", "keyword", "search_query")
    distance_m = get(row, "distance_m", "distance")

    candidate_text = build_candidate_text(row)
    role = infer_role(row)
    place_type = infer_type(role, candidate_text)
    coverage_scope = infer_scope(role, place_type)
    score = calculate_score(row, role, candidate_text)
    review_decision, review_notes = review_decision_and_notes(role)

    analyzed = {
        "university_id": university_id,
        "parent_place": parent_place,
        "name": name,
        "role": role,
        "type": place_type,
        "coverage_scope": coverage_scope,
        "score": score,
        "review_decision": review_decision,
        "review_notes": review_notes,
        "phone": phone,
        "address": address,
        "lat": lat,
        "lng": lng,
        "category_name": category_name,
        "place_url": place_url,
        "source_query": source_query,
        "distance_m": distance_m,
    }

    for key, value in row.items():
        analyzed[f"raw_{key}"] = value

    return analyzed


def main():
    if not INPUT.exists():
        raise SystemExit(
            f"Input file not found: {INPUT}\n"
            "Run 2_center_candidates.py first, or check the DATA_DIR path."
        )

    with INPUT.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        raw_rows = list(reader)

    if not raw_rows:
        raise SystemExit(f"No rows found in {INPUT}")

    deduped = {}

    for raw_row in raw_rows:
        analyzed = analyze_row(raw_row)
        key = candidate_key(analyzed)

        if key not in deduped:
            deduped[key] = analyzed
            continue

        existing = deduped[key]
        merged_query = merge_source_queries(existing["source_query"], analyzed["source_query"])

        if is_better_candidate(analyzed, existing):
            analyzed["source_query"] = merged_query
            deduped[key] = analyzed
        else:
            existing["source_query"] = merged_query

    output_rows = list(deduped.values())

    output_rows.sort(
        key=lambda row: (
            row["parent_place"],
            ROLE_PRIORITY.get(row["role"], 99),
            -int(row["score"]),
            row["name"],
        )
    )

    base_fields = [
        "university_id",
        "parent_place",
        "name",
        "role",
        "type",
        "coverage_scope",
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

    raw_fields = []
    for key in raw_rows[0].keys():
        raw_key = f"raw_{key}"
        if raw_key not in base_fields:
            raw_fields.append(raw_key)

    fieldnames = base_fields + raw_fields

    with OUTPUT.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(output_rows)

    role_counts = Counter(row["role"] for row in output_rows)

    print(f"Raw candidates: {len(raw_rows)}")
    print(f"Deduped candidates: {len(output_rows)}")
    print(f"Saved review file: {OUTPUT}")
    print()
    print("Role counts:")

    for role, count in sorted(role_counts.items(), key=lambda item: ROLE_PRIORITY.get(item[0], 99)):
        print(f"- {role}: {count}")

    grouped = defaultdict(list)

    excluded_from_top = {
        "reject_likely",
        "other_university",
        "place_only",
        "info_only",
        "non_lost_support",
    }

    for row in output_rows:
        if row["role"] in excluded_from_top:
            continue

        grouped[row["parent_place"]].append(row)

    print()
    print("Top candidates by university:")

    for parent_place in sorted(grouped.keys()):
        print(f"\n[{parent_place}]")

        for row in grouped[parent_place][:10]:
            phone = row["phone"] or "-"
            print(
                f"- {row['score']} | {row['name']} | "
                f"{row['role']} | {row['review_decision']} | {phone}"
            )


if __name__ == "__main__":
    main()