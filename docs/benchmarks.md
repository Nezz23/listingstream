# Benchmarks

Measured 2026-11-23 on an Apple M4 Pro (12 cores), Java 21.0.13, a single local Kafka 4.4.0 broker (KRaft) and
PostgreSQL 17, all on the same machine. Workload: all 10 Inside Airbnb NYC scrapes, 284,472 listing events.

## End to end

From `POST /api/replay` to the last snapshot decided (published, consumed, validated, staged in PostgreSQL,
history merged), with the consumers already running. Tables truncated before each run.

| Run | Seconds | Events/s |
|---|---|---|
| 1 | 1.76 | ~162,000 |
| 2 | 1.81 | ~157,000 |
| 3 | 1.80 | ~158,000 |

The producer alone published the 284,472 events in 1.25 s (about 228,000/s) with `acks=all`, LZ4 compression,
`linger.ms=20` and 64 KB batches.

## History merges

Each accepted snapshot is merged into the Type 2 history by three set-based SQL statements in one transaction.

| Measure | Value |
|---|---|
| All 9 merges, total | 607-647 ms |
| Slowest single merge (the first scrape: 35,979 new versions) | 155-181 ms |
| Typical later merge (~2-4K versions opened and closed) | 52-79 ms |

## Duplicates

Replaying the full dataset a second time into a populated database: 284,472 events recognized as duplicates by
the staging primary key, 0 rows changed, reconciliation still 14 of 14. Kafka delivers at least once; the
idempotent write makes the result exactly once.
