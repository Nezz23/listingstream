# Reconciliation: stream vs. batch warehouse

The same raw Inside Airbnb files were processed twice: once by the batch dbt + DuckDB warehouse
([AirbnbWarehouse](https://github.com/Nezz23/airbnb-analytics-warehouse)) and once by this stream
(Kafka -> Spring Boot -> PostgreSQL). `GET /api/reconciliation` compares them; `FullReconciliationIT` asserts
every check on all 284,472 events. Result on 2026-11-23: **14 of 14 checks match exactly.**

| Check | Batch warehouse | Stream |
|---|---|---|
| Accepted snapshots | 9 | 9 |
| Quarantined snapshots | 2026-06-08 | 2026-06-08 |
| Type 2 history versions | 56,657 | 56,657 |
| Listings with history | 41,760 | 41,760 |
| Current versions | 29,873 | 29,873 |

## Listings per Local Law 18 status, every accepted snapshot

| Snapshot | Registered | Exempt | 30+ nights | Unregistered short-term | Unknown | Stream matches |
|---|---|---|---|---|---|---|
| 2026-03-16 | 2,302 | 2,723 | 30,371 | 582 | 1 | yes |
| 2026-04-14 | 2,305 | 2,757 | 29,376 | 595 | 3 | yes |
| 2026-05-14 | 2,333 | 2,843 | 23,342 | 475 | 1 | yes |
| 2026-06-14 | 2,330 | 2,956 | 24,605 | 367 | 1 | yes |
| 2026-07-12 | 2,310 | 2,934 | 24,475 | 375 | 2 | yes |
| 2026-08-10 | 2,284 | 2,935 | 24,298 | 715 | 2 | yes |
| 2026-09-14 | 2,250 | 2,877 | 24,028 | 757 | 6 | yes |
| 2026-10-04 | 2,258 | 2,736 | 23,804 | 779 | 0 | yes |
| 2026-11-01 | 2,283 | 2,784 | 24,012 | 794 | 0 | yes |

The quarantined scrape (2026-06-08, 4,506 rows after 28,994) is rejected by the same volume rule in both;
the stream records the reason `volume: 4506 rows is a 84% drop vs last applied (28994); limit 50%`.
