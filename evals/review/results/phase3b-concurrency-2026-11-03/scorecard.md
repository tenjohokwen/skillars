# Scorecard: phase3b-concurrency-2026-11-03

## Arm: concurrency

25 cases (0 reviewer errors), $96.59 reviewer cost.

| slice | expected | recall | reported | precision | unverified |
|---|---|---|---|---|---|
| **all** | 116 | **40%** | 177 | **55%** | 64 |
| kind:introducing-diff | 10 | 70% | 35 | 54% | 7 |
| kind:pre-fix-snapshot | 106 | 37% | 142 | 56% | 57 |
| cat:architecture | 1 | 50% | - | - | - |
| cat:concurrency | 13 | 73% | - | - | - |
| cat:database | 2 | 100% | - | - | - |
| cat:edge-case | 9 | 22% | - | - | - |
| cat:logic | 53 | 40% | - | - | - |
| cat:performance | 1 | 0% | - | - | - |
| cat:security | 37 | 30% | - | - | - |

## Arm: control

25 cases (0 reviewer errors), $17.99 reviewer cost.

| slice | expected | recall | reported | precision | unverified |
|---|---|---|---|---|---|
| **all** | 116 | **65%** | 203 | **73%** | 34 |
| kind:introducing-diff | 10 | 65% | 38 | 47% | 10 |
| kind:pre-fix-snapshot | 106 | 65% | 165 | 79% | 24 |
| cat:architecture | 1 | 50% | - | - | - |
| cat:concurrency | 13 | 65% | - | - | - |
| cat:database | 2 | 75% | - | - | - |
| cat:edge-case | 9 | 44% | - | - | - |
| cat:logic | 53 | 79% | - | - | - |
| cat:performance | 1 | 0% | - | - | - |
| cat:security | 37 | 51% | - | - | - |

## Per case

| arm | case | kind | expected | recall | reported | precision |
|---|---|---|---|---|---|---|
| concurrency | 242-logout-race-and-cookie-id | pre | 5 | 20% | 9 | 89% |
| concurrency | 307-frontend-links-and-tus | pre | 6 | 0% | 10 | 20% |
| concurrency | 307-player-ownership-guards | pre | 6 | 33% | 12 | 42% |
| concurrency | 318-cashout-money-integrity | pre | 8 | 56% | 12 | 50% |
| concurrency | 319-csam-suspension-logout-login-limiter | pre | 8 | 56% | 11 | 64% |
| concurrency | 330-filestorage-quota-race | pre | 1 | 100% | 3 | 100% |
| concurrency | 330-missing-version-lost-updates | pre | 3 | 50% | 5 | 40% |
| concurrency | 333-cancel-emails-and-uncaptured-refunds | pre | 4 | 62% | 5 | 40% |
| concurrency | 337-subscription-entitlement-integrity | pre | 13 | 58% | 14 | 93% |
| concurrency | 340-unpaid-paused-grace-sweep | introducing | 1 | 50% | 10 | 80% |
| concurrency | 350-pack-refund-rounding-remainder | introducing | 3 | 67% | 10 | 50% |
| concurrency | 351-paused-subscription-card-sync | introducing | 3 | 50% | 7 | 43% |
| concurrency | 353-agreed-price-null-fallback | introducing | 3 | 100% | 8 | 38% |
| concurrency | 353-excused-cancel-cap-race | pre | 1 | 100% | 3 | 67% |
| concurrency | 355-pack-double-settle-expired-parent-cancel | pre | 2 | 50% | 1 | 100% |
| concurrency | 359-charge-idempotency-unknown-outcome | pre | 10 | 15% | 5 | 60% |
| concurrency | 368-pack-status-badges | pre | 4 | 50% | 8 | 50% |
| concurrency | 376-completion-dispute-integrity | pre | 7 | 0% | 2 | 0% |
| concurrency | 381-auth-hardening-rate-limits-and-keys | pre | 8 | 25% | 8 | 38% |
| concurrency | 382-upload-content-verification | pre | 5 | 40% | 7 | 43% |
| concurrency | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 50% | 6 | 33% |
| concurrency | 385-pack-tier-and-payment-method-input-bounds | pre | 2 | 0% | 7 | 57% |
| concurrency | 386-messaging-contact-detail-hardening | pre | 6 | 17% | 7 | 71% |
| concurrency | 388-jwt-signing-key-in-database | pre | 2 | 50% | 3 | 33% |
| concurrency | 398-ltd-admin-full-admin-rights | pre | 3 | 67% | 4 | 75% |
| control | 242-logout-race-and-cookie-id | pre | 5 | 20% | 8 | 38% |
| control | 307-frontend-links-and-tus | pre | 6 | 83% | 11 | 100% |
| control | 307-player-ownership-guards | pre | 6 | 100% | 8 | 100% |
| control | 318-cashout-money-integrity | pre | 8 | 62% | 10 | 80% |
| control | 319-csam-suspension-logout-login-limiter | pre | 8 | 38% | 15 | 80% |
| control | 330-filestorage-quota-race | pre | 1 | 100% | 3 | 33% |
| control | 330-missing-version-lost-updates | pre | 3 | 83% | 7 | 43% |
| control | 333-cancel-emails-and-uncaptured-refunds | pre | 4 | 100% | 9 | 56% |
| control | 337-subscription-entitlement-integrity | pre | 13 | 81% | 16 | 100% |
| control | 340-unpaid-paused-grace-sweep | introducing | 1 | 100% | 13 | 62% |
| control | 350-pack-refund-rounding-remainder | introducing | 3 | 67% | 9 | 33% |
| control | 351-paused-subscription-card-sync | introducing | 3 | 50% | 9 | 56% |
| control | 353-agreed-price-null-fallback | introducing | 3 | 67% | 7 | 29% |
| control | 353-excused-cancel-cap-race | pre | 1 | 100% | 3 | 67% |
| control | 355-pack-double-settle-expired-parent-cancel | pre | 2 | 100% | 5 | 60% |
| control | 359-charge-idempotency-unknown-outcome | pre | 10 | 35% | 5 | 100% |
| control | 368-pack-status-badges | pre | 4 | 100% | 8 | 100% |
| control | 376-completion-dispute-integrity | pre | 7 | 36% | 5 | 80% |
| control | 381-auth-hardening-rate-limits-and-keys | pre | 8 | 62% | 10 | 80% |
| control | 382-upload-content-verification | pre | 5 | 40% | 5 | 100% |
| control | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 100% | 6 | 83% |
| control | 385-pack-tier-and-payment-method-input-bounds | pre | 2 | 75% | 6 | 83% |
| control | 386-messaging-contact-detail-hardening | pre | 6 | 42% | 8 | 75% |
| control | 388-jwt-signing-key-in-database | pre | 2 | 100% | 7 | 43% |
| control | 398-ltd-admin-full-admin-rights | pre | 3 | 100% | 10 | 100% |
