# Performance evidence

Accepted local measurements from 2026-10-30. These are separate from live AWS latency. The exact 200,000-row final-audit proof and current mixed-sample recheck are recorded below.

## Measured local performance

Machine: Intel Core i5-1240P, 12 physical / 16 logical cores, 15.69 GiB OS-reported RAM, Windows 11 Home Single Language build 26200. Docker Desktop Engine 29.9.2: 16 logical CPUs and 7.60 GiB VM memory. Host benchmark JDK 22.0.1 compiles release 21; the running app uses Temurin 21. These are local measurements, not AWS sizing guarantees.

The benchmark uses three runs in one dedicated 256 MiB JVM, a 200,000-employee source, deterministic seed 20261030, 1% mutation / 0.5% drop / 0.1% new. Each file has 199,190 valid rows and plans exactly 1,922 updates, 1,010 deactivations and 200 creates. Peak heap is sampled every 50 ms; coverage remains enabled for normal tests and is excluded from benchmark timing.

| Run | Dry-run wall time | Diff phase | Apply 3,132 actions | Sampled peak heap |
|---|---|---|---|---|
| 1 | 23.455 s | 23.109 s | 12.713 s | 99.69 MiB |
| 2 | 17.067 s | 16.954 s | 10.563 s | 103.62 MiB |
| 3 | 15.548 s | 15.369 s | 10.272 s | 109.40 MiB |
| **Median** | **17.067 s** | **16.954 s** | **10.563 s** | **103.62 MiB** |

Keep the **40-action default**. Median mixed-file apply throughput in this repeat is **296.5 actions/s**, about 1.2% below the 300 target; earlier repeats reached 303–304, so that throughput floor is not consistent across repeats. No further application tuning was performed. Across **237 nonempty committed chunks**, including cold chunks and transaction commit, nearest-rank **p50 / p99 / max = 127.6 / 310.0 / 1,253.6 ms**. The approved criterion is **p99 <1 second AND max <2.5 seconds** (half the 5-second lock timeout): **PASS; the chunk acceptance item is closed**. Raw durations are generated under target.

The matched update-only profiling comparison used 1,000 warm-up actions, 5,000 measured actions, chunk 64, the same 256 MiB heap and no coverage agent: **89.6 → 380.3 actions/s (4.25×)**. JDBC execution/batch/commit calls fell from **6.08 → 0.17 per action**. These counts describe JDBC calls, not packet captures. The implementation batches action status updates and outbox inserts, defers flushing, and reads/locks employee sets together.

Cold JIT, class loading and host scheduling vary. Earlier mixed-file repeats measured 303–304 actions/s with maxima 779–1,265 ms; instrumented 40-action probes measured 251–258 actions/s and cold maxima 833–1,605 ms. A stressed 64-action probe reached 6,140 ms. These are different workloads/instrumentation and are retained rather than hidden. The old saved summaries contained only maxima, so their p50/p99 cannot be recovered; this repeat adds complete distribution capture. The new criterion replaces the old maximum-below-one-second target. It is a local acceptance measurement, not a guarantee for every host or cold start. See [trade-offs](docs/DECISIONS.md).

The latest separate 40-action JDBC-counting probe measured 252.2 actions/s, 330 ms maximum after warm-up and 925 ms cold maximum. Its first measured chunk used 0.25 JDBC calls/action. This instrumented update-only workload is separate from both the mixed-file result and the archived matched 64-action comparison.

### HTTP ingestion scaling (k6)

Each case is configured for 60 seconds, with per-VU IDs and a 90% fresh / 5% replay / 5% stale cycle. All six cases had **zero HTTP and outcome failures**. Login/setup is excluded from request latency. Batch size 1 measures actual single-event response latency; the per-event columns for batch size 50 divide each request duration by 50 and are **amortized cost, not individual response latency**. All baseline rows use `synchronous_commit=on`.

| Events/request | VUs | Events/s | Request p50 / p99 (ms) | Per-event p50 / p99 (ms) | Max pool pending | PG commits/s |
|---|---|---|---|---|---|---|
| 1 | 5 | 59.0 | 71.2 / 235.4 | 71.18 / 235.36 | 0 | 61.2 |
| 50 | 1 | 75.9 | 650.6 / 933.1 | 13.01 / 18.66 | 0 | 96.2 |
| 50 | 5 | 245.1 | 985.6 / 1,380.6 | 19.71 / 27.61 | 0 | 243.4 |
| 50 | 10 | 322.1 | 1,446.4 / 2,502.7 | 28.93 / 50.05 | 0 | 301.2 |
| 50 | 20 | 405.0 | 2,325.4 / 3,832.8 | 46.51 / 76.66 | 1 | 372.9 |
| 50 (sync off, diagnostic) | 20 | 492.0 | 1,873.7 / 3,780.7 | 37.47 / 75.61 | 2 | 436.0 |

The pool size stayed at 20. Hikari gauges were sampled every 200 ms (observed maxima; no gauge errors). PG commits/s is the `xact_commit` delta divided by its measured enclosing wall window: 65.6–69.8 seconds, including container launch/login/teardown, publisher and observation transactions. It is not ingestion-only commits during exactly 60 seconds. Each case started with an empty outbox queue. Raw JSON/logs stay in ignored loadtest/results; the matrix is resumable because draining between cases takes substantially longer than the timed traffic.

1. Scaling bends above 5–10 VUs: 10→20 adds 26% events/s while request p99 rises 53%, so more concurrency increasingly buys waiting time.
2. Normal-run spot samples showed WALWrite/WalSync waits; `synchronous_commit=off` raised throughput 21.5% and removed those observed waits. Durable commit I/O contributes, but per-record SQL/commit coordination and CPU/runnable work remain; this single diagnostic does not isolate every remaining cost.
3. Baseline max pending connections was 0/0/0/1 across batch-50 cases; raising the pool is not justified by that and cannot remove commit costs. The 20-VU case ended with 22,260 pending outbox rows: 405 events/s is an **ingress burst rate**, not sustainable end-to-end delivery capacity.

The diagnostic temporarily relaxed durability only on the local database and restored `synchronous_commit=on` in finally, verified before the test suite. It is not a production recommendation: acknowledged commits may be lost after a crash with asynchronous commit. The earlier isolated 20-VU run measured 423 events/s (request p50/p99 2,210/3,912 ms); retain that as variation, rather than treating either run as a hardware-independent guarantee.

Run `BATCH_SIZE=1 LOAD_VUS=5 ./scripts/loadtest.sh` or the default batch50 case. `python loadtest/run-matrix.py` runs the local-only matrix, including the temporary durability diagnostic. Docker Desktop uses `BASE_URL=http://host.docker.internal:8080`; Linux may use `DOCKER_NETWORK=host BASE_URL=http://127.0.0.1:8080`. See [test methodology](docs/TESTING.md).


## Reproduce

Run `./mvnw -B verify -Pbenchmark` with Docker available. Benchmark JVMs are capped at 256 MiB and excluded from normal push CI. Weekly/manual CI publishes benchmark reports. Raw results stay under ignored target/ and loadtest/results/. See [testing](TESTING.md) for the full method.

## Final audit: 2026-10-31

`./mvnw -B verify -Pbenchmark` passes 295 cases (145 unit/property/guards, 147 PostgreSQL integration and 3 benchmark methods), zero failures/errors/skips, in 12m47s. The exact-size case processes **200,000 file rows** in **16.571 s wall / 16.388 s diff** with a **256 MiB** heap and **104.84 MiB** sampled peak. It asserts PLANNED, 200,000 unchanged, zero rejections and no brake.

The exact-size case runs before the three mixed samples in the same fork. Their comparison path is therefore warmed; apply still includes its first cold chunks and transaction commit. The recheck is not evidence of another code optimization. Previous measurements above remain visible as workload/JIT variation.

| Mixed sample | Wall (s) | Diff (s) | Apply (s) | Peak heap (MiB) |
|---|---:|---:|---:|---:|
| 1 | 16.131 | 15.967 | 12.109 | 120.56 |
| 2 | 13.306 | 12.961 | 9.989 | 120.14 |
| 3 | 12.314 | 12.167 | 9.761 | 119.75 |

Final mixed medians: wall13.306 s, diff12.961 s, apply9.989 s, peak120.14 MiB; 3,132 actions /9.989 s =313.5 actions/s. Over237 committed chunks: p50/p99/max120.9/301.2/1,174.4 ms. Both chunk bounds pass; default40 stays. No production algorithm or pool change was made. The k6 baseline and its durability diagnostic were not repeated during this documentation/release audit.
