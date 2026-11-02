# Scorecard: baseline-smoke-2026-11-02

## Arm: blind-hunter

5 cases (0 reviewer errors), $1.81 reviewer cost.

| slice | expected | recall | reported | precision |
|---|---|---|---|---|
| **all** | 9 | **78%** | 69 | **32%** |
| kind:introducing-diff | 5 | 60% | 28 | 29% |
| kind:pre-fix-snapshot | 4 | 100% | 41 | 34% |
| cat:concurrency | 1 | 100% | - | - |
| cat:edge-case | 2 | 0% | - | - |
| cat:logic | 3 | 100% | - | - |
| cat:security | 3 | 100% | - | - |

## Arm: control

5 cases (0 reviewer errors), $1.95 reviewer cost.

| slice | expected | recall | reported | precision |
|---|---|---|---|---|
| **all** | 9 | **83%** | 31 | **45%** |
| kind:introducing-diff | 5 | 80% | 15 | 40% |
| kind:pre-fix-snapshot | 4 | 88% | 16 | 50% |
| cat:concurrency | 1 | 100% | - | - |
| cat:edge-case | 2 | 50% | - | - |
| cat:logic | 3 | 100% | - | - |
| cat:security | 3 | 83% | - | - |

## Per case

| arm | case | kind | expected | recall | reported | precision |
|---|---|---|---|---|---|---|
| blind-hunter | 350-pack-refund-rounding-remainder | introducing | 2 | 50% | 16 | 31% |
| blind-hunter | 353-agreed-price-null-fallback | introducing | 3 | 67% | 12 | 25% |
| blind-hunter | 353-excused-cancel-cap-race | pre | 1 | 100% | 9 | 11% |
| blind-hunter | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 100% | 16 | 31% |
| blind-hunter | 388-jwt-signing-key-in-database | pre | 1 | 100% | 16 | 50% |
| control | 350-pack-refund-rounding-remainder | introducing | 2 | 50% | 8 | 38% |
| control | 353-agreed-price-null-fallback | introducing | 3 | 100% | 7 | 43% |
| control | 353-excused-cancel-cap-race | pre | 1 | 100% | 3 | 33% |
| control | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 75% | 4 | 75% |
| control | 388-jwt-signing-key-in-database | pre | 1 | 100% | 9 | 44% |
