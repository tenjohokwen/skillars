# Scorecard: phase3-security-2026-11-03

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

## Arm: security

25 cases (0 reviewer errors), $95.65 reviewer cost.

| slice | expected | recall | reported | precision | unverified |
|---|---|---|---|---|---|
| **all** | 116 | **57%** | 186 | **62%** | 46 |
| kind:introducing-diff | 10 | 40% | 26 | 35% | 10 |
| kind:pre-fix-snapshot | 106 | 58% | 160 | 67% | 36 |
| cat:architecture | 1 | 0% | - | - | - |
| cat:concurrency | 13 | 35% | - | - | - |
| cat:database | 2 | 100% | - | - | - |
| cat:edge-case | 9 | 39% | - | - | - |
| cat:logic | 53 | 50% | - | - | - |
| cat:performance | 1 | 0% | - | - | - |
| cat:security | 37 | 80% | - | - | - |

## Per case

| arm | case | kind | expected | recall | reported | precision |
|---|---|---|---|---|---|---|
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
| security | 242-logout-race-and-cookie-id | pre | 5 | 0% | 13 | 38% |
| security | 307-frontend-links-and-tus | pre | 6 | 17% | 15 | 47% |
| security | 307-player-ownership-guards | pre | 6 | 83% | 6 | 100% |
| security | 318-cashout-money-integrity | pre | 8 | 62% | 8 | 88% |
| security | 319-csam-suspension-logout-login-limiter | pre | 8 | 62% | 13 | 62% |
| security | 330-filestorage-quota-race | pre | 1 | 100% | 8 | 38% |
| security | 330-missing-version-lost-updates | pre | 3 | 50% | 5 | 20% |
| security | 333-cancel-emails-and-uncaptured-refunds | pre | 4 | 62% | 3 | 100% |
| security | 337-subscription-entitlement-integrity | pre | 13 | 69% | 10 | 100% |
| security | 340-unpaid-paused-grace-sweep | introducing | 1 | 100% | 7 | 57% |
| security | 350-pack-refund-rounding-remainder | introducing | 3 | 0% | 6 | 17% |
| security | 351-paused-subscription-card-sync | introducing | 3 | 33% | 8 | 25% |
| security | 353-agreed-price-null-fallback | introducing | 3 | 67% | 5 | 40% |
| security | 353-excused-cancel-cap-race | pre | 1 | 100% | 2 | 100% |
| security | 355-pack-double-settle-expired-parent-cancel | pre | 2 | 50% | 3 | 33% |
| security | 359-charge-idempotency-unknown-outcome | pre | 10 | 25% | 6 | 67% |
| security | 368-pack-status-badges | pre | 4 | 75% | 4 | 100% |
| security | 376-completion-dispute-integrity | pre | 7 | 29% | 6 | 50% |
| security | 381-auth-hardening-rate-limits-and-keys | pre | 8 | 75% | 13 | 62% |
| security | 382-upload-content-verification | pre | 5 | 100% | 10 | 80% |
| security | 384-stripe-oauth-state-and-payout-destination | pre | 2 | 100% | 4 | 75% |
| security | 385-pack-tier-and-payment-method-input-bounds | pre | 2 | 100% | 5 | 60% |
| security | 386-messaging-contact-detail-hardening | pre | 6 | 58% | 10 | 80% |
| security | 388-jwt-signing-key-in-database | pre | 2 | 50% | 4 | 25% |
| security | 398-ltd-admin-full-admin-rights | pre | 3 | 100% | 12 | 100% |
