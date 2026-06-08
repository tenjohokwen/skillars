# Payments & Tier Strategy

This module defines the functional and technical requirements for the Payment, Subscription, and Financial Management System, extending the Session entity defined in requirements-v1_2.md.

---

## 1. Integrated Payment Architecture
The platform will utilize both Stripe Connect and paypal to handle all professional, secure, and automated revenue flows for independent coaches while protecting the platform's commission model. However, for the the MVP only stripe will be be implemented. Stripe Connect's regulated payment flow is used as the payment escrow.

*   **Provider:** Stripe Connect is the primary service for handling coach payouts, subscription billing, and marketplace commissions.
*   **Platform Commission:** The platform shall automatically deduct an **8% commission** from every session or session pack payment processed through the system.
*   **Gross vs. Net Transparency:** The coach dashboard must show total money collected versus the net amount after platform commissions and processing fees (e.g., Stripe’s 2.9% + $0.30).
*   **Currency Support:** Initial support for EUR (€) aligned with the German market launch.

---

## 2. Payment Lifecycle and Enforcement
To prevent platform leakage and ensure financial integrity:

*   **Pre-Authorization/Credit Check:** Payment is authorized or session credits are validated at the REQUESTED state. A booking cannot be submitted if the player has exhausted their session pack.
*   **Deduction Point:** A session credit is ONLY deducted from a pack (and funds released to the coach) once the session reaches the COMPLETED state.
*   **Premium Activation (Anti-Bypass):** High-value coach features (e.g., Video Review Room) are only activated for a specific player-coach pair if there are active, paid session credits for that pair. This ensures the 8% commission is captured before "Elite" tools are used.
*   **Off-Platform Prevention:** "Platform Payments Only" is a key Trust Badge. Coercing users into off-platform payments (Cash, Venmo) is prohibited and may lead to account termination.

---

## 3. Coach Tier Strategy (Business Efficiency)
Coaches can register and list in the marketplace for free. High-value "Business" and "Coaching" tools are gated behind paid subscriptions.

| Tier | Price | Business Focus | Storage (Drills) | Bandwidth |
| :--- | :--- | :--- | :--- | :--- |
| **Scout (Free)** | €0 | Marketplace Listing | 0 GB | 5 GB/mo |
| **Instructor (Pro)** | Monthly | Business Management | 5 GB | 50 GB/mo |
| **Academy (Elite)** | Monthly | Remote Coaching | 20 GB | 200 GB/mo |

### Coach Feature Breakdown
*   **Scout (Free):** Public profile, basic scheduling, booking requests, unlimited players managed.
*   **Instructor (Pro):** Everything in Scout + Sequence Builder, Equipment Packing Lists, Session Templates, Basic PDF Reports.
*   **Academy (Elite):** Everything in Instructor + Video Review Room (Time-stamped voice/text feedback), Branded PDF Reports, Advanced Benchmark Analytics.
*   **Ownership:** The coach owns all custom drills uploaded to their storage. Shared drills count against the coach's bandwidth when viewed by students.

---

## 4. Player Tier Strategy (Development & History)
Players must subscribe to a paid tier to contact coaches. Data storage is the primary lever, as players own their homework and performance history.

| Tier | Billing Options | Storage | Bandwidth | History Portability |
| :--- | :--- | :--- | :--- | :--- |
| **Athlete** | Monthly, Quarterly, Yearly | 2 GB | 10 GB/mo | Fixed (1 Coach) |
| **Semi-Pro Athlete** | **Strictly Yearly** | 4 GB | 25 GB/mo | Fixed (1 Coach) |
| **Pro Athlete** | **Strictly Yearly** | 7 GB | 50 GB/mo | **Multi-Coach Sharing** |

### Player Feature Breakdown
*   **Athlete:** Ability to message coaches, upload homework (2GB cap), access the 6-skill radar chart, and view session history.
*   **Semi-Pro Athlete:** All Athlete features + increased storage (4GB) and bandwidth. **Strictly Yearly Billing.**
*   **Pro Athlete:** All Semi-Pro features + increased storage (7GB) and **Multi-Coach History Sharing** (grant access to historical data to new coaches). **Strictly Yearly Billing.**
*   **Long-Term History:** Lifetime data retention (guaranteed no purge during training gaps) is only available for users on **Yearly Subscriptions**.

---

## 5. Technical Quota & Cost Controls
*   **Resolution Capping:** All player-uploaded performance videos are transcoded to a maximum of **720p** to optimize their storage quota.
*   **Bandwidth Consumption:**
    *   **Player Bandwidth:** Consumed when the Player or Coach watches Player-uploaded content (homework/feedback).
    *   **Coach Bandwidth:** Consumed when any student watches Coach-uploaded "Master Drills."
*   **Data Retention Purge:** For non-yearly subscribers, performance videos older than **90 days** are subject to automatic purging.

---

## 6. Billing & Cancellation Policy
### 6.1 Billing Logic
*   **Coach Billing:** Monthly only (standard SaaS model).
*   **Player Billing:** Designed for commitment. "Semi-Pro" and "Pro" tiers require a yearly commitment to justify the cost of long-term data storage and history portability.

### 6.2 Cancellation and Refund Policy
*   **Parent/Player Cancellation:**
    *   \> 24 hours: Full refund.
    *   6–24 hours: 50% refund.
    *   < 6 hours: No refund.
*   **Coach Cancellation:**
    *   \> 24 hours: Full refund to parent.
    *   < 24 hours: Full refund + a "reliability strike" against the coach.
*   **No-Show Policy:**
    *   Player No-Show: After 15 minutes, session marked COMPLETED and deducted from pack.
    *   Coach No-Show: Automatic refund and reliability strike.

---

## 7. Financial Reporting & Disputes
*   **Invoicing:** Parents and coaches have access to past payments with downloadable PDF receipts.
*   **Dispute Management:** If a parent flags a session, state becomes DISPUTED and funds are frozen. Admin is notified on the dashboard to review Start/End audit trails.
