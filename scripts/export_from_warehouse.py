"""Export the raw Inside Airbnb listing scrapes and the reference numbers from the AirbnbWarehouse project.

Run with that project's virtualenv, from this repository's root:
    ../AirbnbWarehouse/.venv/bin/python scripts/export_from_warehouse.py ../AirbnbWarehouse/data/warehouse.duckdb

Writes data/listings_<snapshot>.jsonl.gz (raw text, one listing per line, as published) and
src/main/resources/reference_counts.json (what the batch dbt build produced from the same files).
"""
import gzip
import json
import sys
from pathlib import Path

import duckdb

COLUMNS = ("id,name,host_id,neighbourhood_cleansed,neighbourhood_group_cleansed,property_type,room_type,"
           "accommodates,minimum_nights,maximum_nights,instant_bookable,license,price,latitude,longitude")


def main(warehouse: str) -> None:
    con = duckdb.connect(warehouse, read_only=True)
    out = Path("data")
    out.mkdir(exist_ok=True)
    names = COLUMNS.split(",")
    snapshots = [r[0] for r in con.execute("select distinct snapshot_date from bronze.listings order by 1").fetchall()]
    for snapshot in snapshots:
        rows = con.execute(f"select {COLUMNS} from bronze.listings where snapshot_date = ? "
                           "order by try_cast(id as bigint)", [snapshot]).fetchall()
        with gzip.open(out / f"listings_{snapshot}.jsonl.gz", "wt") as f:
            for row in rows:
                f.write(json.dumps(dict(zip(names, row)), ensure_ascii=False) + "\n")
        print(snapshot, len(rows))

    versions, listings = con.execute(
        "select count(*), count(distinct listing_id) from silver.listings_scd2").fetchone()
    ref = {
        "source": "AirbnbWarehouse dbt build (DuckDB), silver.stg_listings and silver.listings_scd2",
        "scd2": {"versions": versions, "listings": listings},
        "scd2_current": con.execute(
            "select count(*) from silver.listings_scd2 where dbt_valid_to is null").fetchone()[0],
        "accepted_snapshots": [str(r[0]) for r in con.execute(
            "select snapshot_date from silver.stg_bronze_manifest "
            "where dataset = 'listings' and status = 'accepted' order by 1").fetchall()],
        "quarantined_snapshots": [str(r[0]) for r in con.execute(
            "select snapshot_date from silver.stg_bronze_manifest "
            "where dataset = 'listings' and status = 'quarantined' order by 1").fetchall()],
        "ll18_counts": {},
    }
    for day, status, n in con.execute("select snapshot_date, ll18_status, count(*) from silver.stg_listings "
                                      "group by all order by 1, 2").fetchall():
        ref["ll18_counts"].setdefault(str(day), {})[status] = n
    Path("src/main/resources/reference_counts.json").write_text(json.dumps(ref, indent=2) + "\n")


if __name__ == "__main__":
    main(sys.argv[1])
