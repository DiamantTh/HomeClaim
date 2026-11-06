# Snapshot-performance harness

`./gradlew :homeclaim-platform-paper:paperSnapshotPerformanceMatrix --no-daemon`
starts one disposable, loopback-bound Paper server and writes a run-ID and
production-JAR-SHA-bound E2E result. It runs serially, in a distinct temporary
server directory, and accepts success only when the result file is parseable and
contains `snapshot-performance-matrix` as a passing scenario.

The fixture measures three different workloads:

| Case | Workload | Blocks |
| --- | --- | ---: |
| SMALL | uniform stone | 6,144 |
| MEDIUM | mixed oak-stair BlockData | 55,296 |
| LARGE | populated barrels among full blocks | 221,184 |

For each case the report separates tickwise capture, file encode/write,
read/decode/validation, apply, and canonical verification. It also reports
bounded capture/apply/verify batch counts with `p95/max` milliseconds, scheduled
heartbeat median/p95/max, the phase associated with the maximum heartbeat gap,
and JVM GC collection-count/time deltas. The heartbeat is a diagnostic scheduled
task, not an MSPT profiler or TPS guarantee.

## Step 24A local evidence

The final isolated Paper run (Java 25, 2 GiB heap) passed all three cases:

| Case | Capture | Apply | Verify | Heartbeat median/p95/max | Batch p95/max (capture / apply / verify) | GC |
| --- | ---: | ---: | ---: | --- | --- | --- |
| SMALL | 1,654 ms | 1,550 ms | 1,649 ms | 49.99 / 51.39 / 375.65 ms | 2.11 / 357.04, 1.47 / 9.10, 0.62 / 0.65 ms | 7 / 35 ms |
| MEDIUM | 14,208 ms | 14,400 ms | 14,450 ms | 50.00 / 50.20 / 139.28 ms | 0.63 / 8.59, 0.53 / 89.69, 0.55 / 6.80 ms | 4 / 17 ms |
| LARGE | 57,650 ms | 57,600 ms | 57,650 ms | 50.00 / 50.30 / 140.64 ms | 0.59 / 11.21, 1.04 / 90.63, 0.50 / 5.17 ms | 12 / 56 ms |

An earlier isolated MEDIUM run observed a 346.73 ms apply batch and 394.85 ms
heartbeat maximum. A diagnostic 64-record experiment tripled total duration but
still observed a 296.24 ms apply batch. The spikes vary across fresh server
runs and phases; GC time alone does not explain the 357 ms SMALL capture batch.
The evidence does not support adaptive batching or changing the bounded 192
record limit. No unbounded queue, synchronous file IO, or large verify pass was
introduced by this harness.
