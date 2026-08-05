from pathlib import Path
import csv

BASE_DIR = Path(r"C:\Users\User\Desktop\SSU\2026-Summer\소프트웨어공모전\data\list_school")
INPUT = BASE_DIR / "universities_seoul_seed.csv"

REQUIRED = [
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

with INPUT.open("r", encoding="utf-8-sig", newline="") as f:
    reader = csv.DictReader(f)
    missing = [c for c in REQUIRED if c not in (reader.fieldnames or [])]
    if missing:
        raise SystemExit(f"Missing columns: {missing}")

    rows = list(reader)

if not rows:
    raise SystemExit("No universities found.")

ids = set()

for i, row in enumerate(rows, start=2):
    university_id = row["university_id"].strip()
    name = row["name"].strip()
    address = row["address"].strip()

    if not university_id:
        raise SystemExit(f"Row {i}: university_id is empty")
    if not name:
        raise SystemExit(f"Row {i}: name is empty")
    if not address:
        raise SystemExit(f"Row {i}: address is empty")

    if university_id in ids:
        raise SystemExit(f"Row {i}: duplicate university_id: {university_id}")
    ids.add(university_id)

print(f"OK: {len(rows)} universities")
for row in rows:
    print(f"- {row['university_id']}: {row['name']} / {row['address']}")
