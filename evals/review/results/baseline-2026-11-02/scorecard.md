# Scorecard: baseline-2026-11-02

## Arm: blind-hunter

25 cases (0 reviewer errors), $14.53 reviewer cost.

| slice | expected | recall | reported | precision |
|---|---|---|---|---|
| **all** | 108 | **67%** | 442 | **46%** |
| kind:introducing-diff | 9 | 67% | 58 | 36% |
| kind:pre-fix-snapshot | 99 | 67% | 384 | 48% |
| cat:architecture | 1 | 0% | - | - |
| cat:concurrency | 11 | 73% | - | - |
| cat:database | 1 | 50% | - | - |
| cat:edge-case | 6 | 50% | - | - |
| cat:logic | 51 | 71% | - | - |
| cat:performance | 1 | 0% | - | - |
| cat:security | 37 | 68% | - | - |

## Arm: control

25 cases (0 reviewer errors), $17.99 reviewer cost.

| slice | expected | recall | reported | precision |
|---|---|---|---|---|
| **all** | 108 | **62%** | 203 | **81%** |
| kind:introducing-diff | 9 | 61% | 38 | 61% |
| kind:pre-fix-snapshot | 99 | 63% | 165 | 85% |
| cat:architecture | 1 | 50% | - | - |
| cat:concurrency | 11 | 64% | - | - |
| cat:database | 1 | 50% | - | - |
| cat:edge-case | 6 | 33% | - | - |
| cat:logic | 51 | 76% | - | - |
| cat:performance | 1 | 0% | - | - |
| cat:security | 37 | 50% | - | - |

## Per case

| arm | case | kind | expected | recall | reported | precision |
|---|---|---|---|---|---|---|
| blind-hunter | 242-logout-race-and-cookie-id | pre | 5 | 50% | 21 | 43% |
| blind-hunter | 307-frontend-links-and-tus | pre | 6 | 58% | 40 | 28% |
| blind-hunter | 307-player-ownership-guards | pre | 6 | 75% | 16 | 38% |
| blind-hunter | 318-cashout-money-integrity | pre | 8 | 94% | 25 | 56% |
| blind-hunter | 319-csam-suspension-logout-login-limiter | pre | 8 | 69% | 27 | 56% |
| blind-hunter | 330-filestorage-quota-race | pre | 1 | 100% | 14 | 29% |
| blind-hunter | 330-missing-version-lost-updates | pre | 3 | 50% | 15 | 60% |
| blind-hunter | 333-cancel-emails-and-uncaptured-refunds | pre | 4 | 75% | 18 | 39% |
| blind-hunter | 337-subscription-entitlement-integrity | pre | 11 | 73% | 22 | 68% |
| blind-hunter | 340-unpaid-paused-grace-sweep | introducing | 1 | 100% | 18 | 50% |
| blind-hunter | 350-pack-refund-rounding-remainder | introducing | 2 | 50% | 14 | 29% |
| blind-hunter | 351-paused-subscription-card-sync | introducing | 3 | 33% | 12 | 33% |
| blind-hunter | 353-agreed-price-null-fallback | introducing | 3 | 100% | 14 | 29% |
| blind-hunter | 353-excused-cancel-cap-race | pre | 1 | 100% | 9 | 11% |
| blind-hunter | 355-pack-double-settle-expired-parent-cancel | pre | 2 | 75% | 9 | 56% |
| blind-hunter | 359-charge-idempotency-unknown-outcome | pre | 9 | 39% | 17 | 59% |
| blind-hunter | 368-pack-status-badges | pre | 3 | 100% | 13 | 46% |
| blind-hunter | 376-completion-dispute-integrity | pre | 7 | 29% | 16 | 50% |
| blind-hunter | 381-auth-hardening-rate-limits-and-keys | pre | 8 | 56% | 25 | 72% |
| blind-hunter | 382-upload-content-verification | pre | 5 | 80% | 16 | 50% |
| blind-hunter | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 100% | 13 | 23% |
| blind-hunter | 385-pack-tier-and-payment-method-input-bounds | pre | 2 | 100% | 13 | 46% |
| blind-hunter | 386-messaging-contact-detail-hardening | pre | 5 | 60% | 18 | 61% |
| blind-hunter | 388-jwt-signing-key-in-database | pre | 1 | 100% | 17 | 47% |
| blind-hunter | 398-ltd-admin-full-admin-rights | pre | 2 | 100% | 20 | 45% |
| control | 242-logout-race-and-cookie-id | pre | 5 | 20% | 8 | 88% |
| control | 307-frontend-links-and-tus | pre | 6 | 83% | 11 | 100% |
| control | 307-player-ownership-guards | pre | 6 | 100% | 8 | 88% |
| control | 318-cashout-money-integrity | pre | 8 | 62% | 10 | 70% |
| control | 319-csam-suspension-logout-login-limiter | pre | 8 | 38% | 15 | 87% |
| control | 330-filestorage-quota-race | pre | 1 | 100% | 3 | 33% |
| control | 330-missing-version-lost-updates | pre | 3 | 67% | 7 | 86% |
| control | 333-cancel-emails-and-uncaptured-refunds | pre | 4 | 100% | 9 | 56% |
| control | 337-subscription-entitlement-integrity | pre | 11 | 86% | 16 | 94% |
| control | 340-unpaid-paused-grace-sweep | introducing | 1 | 100% | 13 | 62% |
| control | 350-pack-refund-rounding-remainder | introducing | 2 | 50% | 9 | 67% |
| control | 351-paused-subscription-card-sync | introducing | 3 | 50% | 9 | 78% |
| control | 353-agreed-price-null-fallback | introducing | 3 | 67% | 7 | 29% |
| control | 353-excused-cancel-cap-race | pre | 1 | 100% | 3 | 67% |
| control | 355-pack-double-settle-expired-parent-cancel | pre | 2 | 100% | 5 | 80% |
| control | 359-charge-idempotency-unknown-outcome | pre | 9 | 39% | 5 | 100% |
| control | 368-pack-status-badges | pre | 3 | 100% | 8 | 100% |
| control | 376-completion-dispute-integrity | pre | 7 | 21% | 5 | 100% |
| control | 381-auth-hardening-rate-limits-and-keys | pre | 8 | 56% | 10 | 100% |
| control | 382-upload-content-verification | pre | 5 | 40% | 5 | 100% |
| control | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 100% | 6 | 67% |
| control | 385-pack-tier-and-payment-method-input-bounds | pre | 2 | 75% | 6 | 100% |
| control | 386-messaging-contact-detail-hardening | pre | 5 | 30% | 8 | 100% |
| control | 388-jwt-signing-key-in-database | pre | 1 | 100% | 7 | 57% |
| control | 398-ltd-admin-full-admin-rights | pre | 2 | 100% | 10 | 80% |
