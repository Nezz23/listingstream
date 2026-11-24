# Benchmarks

Apple M4 Pro (12 cores), Java 21, one local Kafka 4.4.0 broker (KRaft) and PostgreSQL 17 on the same machine, with
the local model also resident in memory. Workload: all 10 Inside Airbnb NYC scrapes, 284,472 listing events.

## End to end, with and without the audit ledger

From `POST /api/replay` until all 10 snapshots are decided (published, consumed, validated, staged, history merged
and, with the ledger, 10 signed blocks holding 58,232 entries written). Tables truncated before each run; the
harness polls PostgreSQL every 50 ms. Run 1 is a cold JVM and is excluded from the range.

| Build | Runs 2-4 | Events/s |
|---|---|---|
| Before the ledger (commit 3a53583) | 2.72-2.74 s | ~104,000 |
| RentGuard with the ledger | 2.95-3.09 s | ~95,000 |

The ledger costs about 10%. Most of that is writing entries: turning on the PostgreSQL driver's
`reWriteBatchedInserts` (JDBC batches become multi-row `INSERT`s) cut runs from 3.77 s to about 3.0 s.

An earlier measurement of the pre-ledger build, with a different polling harness and without the model in memory,
gave 1.76-1.81 s. Compare numbers only within one table.

## Ledger

| Measure | Value |
|---|---|
| Blocks / entries after the full replay | 10 / 58,232 |
| Largest block (first scrape: every listing is new) | 35,980 entries |
| Full verification: every entry hash, Merkle root, link and signature | 103-107 ms |

## History merges

Three set-based SQL statements per accepted snapshot, in one transaction: 917-1,003 ms for all 9 merges together,
246-333 ms for the slowest (the first scrape, 35,979 new versions).

## Duplicates

Replaying the full dataset a second time into a populated database: 284,472 events recognized as duplicates by the
staging primary key, 0 rows changed. Kafka delivers at least once; the idempotent write makes the result exactly once.
