from __future__ import annotations

import csv
from pathlib import Path


DATA_DIR = Path(__file__).resolve().parents[1]
INPUT = DATA_DIR / "universities_seoul_seed.csv"

REQUIRED_COLUMNS = [
    "university_id",
    "name",
    "address",
    "lat",
    "lng",
    "homepage",
    "main_phone",
    "source_url",
    "collection_status",
    "notes",
]

REQUIRED_VALUES = ["university_id", "name", "address"]


def main() -> None:
    with INPUT.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        missing_columns = [column for column in REQUIRED_COLUMNS if column not in (reader.fieldnames or [])]
        if missing_columns:
            raise SystemExit(f"Missing columns: {missing_columns}")

        rows = list(reader)

    if not rows:
        raise SystemExit("No universities found.")

    seen_ids: set[str] = set()

    for index, row in enumerate(rows, start=2):
        for column in REQUIRED_VALUES:
            if not row[column].strip():
                raise SystemExit(f"Row {index}: {column} is empty")

        university_id = row["university_id"].strip()
        if university_id in seen_ids:
            raise SystemExit(f"Row {index}: duplicate university_id: {university_id}")
        seen_ids.add(university_id)

    print(f"OK: {len(rows)} universities")
    for row in rows:
        print(f"- {row['university_id']}: {row['name']} / {row['address']}")


if __name__ == "__main__":
    main()
