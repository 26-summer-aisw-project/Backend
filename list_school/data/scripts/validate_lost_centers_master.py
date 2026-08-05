from __future__ import annotations

import csv
from collections import Counter
from pathlib import Path


DATA_DIR = Path(__file__).resolve().parents[1]
DEFAULT_CSV = DATA_DIR / "lost_centers_master.csv"

REQUIRED_COLUMNS = [
    "center_id",
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
    "verified_at",
    "confidence_score",
    "partner_status",
    "notes",
]

REQUIRED_VALUES = [
    "center_id",
    "name",
    "parent_place",
    "address",
    "lat",
    "lng",
    "center_role",
    "verification_status",
]


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        missing_columns = [column for column in REQUIRED_COLUMNS if column not in (reader.fieldnames or [])]
        if missing_columns:
            raise SystemExit(f"Missing columns: {missing_columns}")
        return list(reader)


def validate_rows(rows: list[dict[str, str]]) -> None:
    if not rows:
        raise SystemExit("No rows found.")

    center_ids = [row["center_id"].strip() for row in rows]
    duplicates = [center_id for center_id, count in Counter(center_ids).items() if count > 1]
    if duplicates:
        raise SystemExit(f"Duplicate center_id values: {duplicates}")

    missing_values: list[str] = []
    invalid_coordinates: list[str] = []
    invalid_confidence: list[str] = []

    for index, row in enumerate(rows, start=2):
        for column in REQUIRED_VALUES:
            if not row.get(column, "").strip():
                missing_values.append(f"row {index}: {column}")

        try:
            lat = float(row["lat"])
            lng = float(row["lng"])
            if not (33.0 <= lat <= 39.0 and 124.0 <= lng <= 132.0):
                invalid_coordinates.append(row["center_id"])
        except ValueError:
            invalid_coordinates.append(row["center_id"])

        try:
            score = int(row["confidence_score"])
            if not (0 <= score <= 100):
                invalid_confidence.append(row["center_id"])
        except ValueError:
            invalid_confidence.append(row["center_id"])

    if missing_values:
        raise SystemExit("Missing required values:\n" + "\n".join(missing_values))
    if invalid_coordinates:
        raise SystemExit(f"Invalid coordinates: {invalid_coordinates}")
    if invalid_confidence:
        raise SystemExit(f"Invalid confidence_score values: {invalid_confidence}")


def print_summary(rows: list[dict[str, str]]) -> None:
    parent_places = {row["parent_place"].strip() for row in rows}
    primary_official_places = {
        row["parent_place"].strip()
        for row in rows
        if row["center_role"] == "primary" and row["verification_status"] == "official_verified"
    }
    role_status_counts = Counter((row["center_role"], row["verification_status"]) for row in rows)

    print(f"OK: {len(rows)} rows")
    print(f"Covered parent places: {len(parent_places)}")
    print(f"Primary official places: {len(primary_official_places)}")
    print()
    print("Role/status counts:")
    for (role, status), count in sorted(role_status_counts.items()):
        print(f"- {role} / {status}: {count}")


def main() -> None:
    rows = read_rows(DEFAULT_CSV)
    validate_rows(rows)
    print_summary(rows)


if __name__ == "__main__":
    main()
