# Skillars PRD — Addendum

**Project:** Skillars — Football Coaching Marketplace Platform
**Created:** 2026-06-09
**Status:** In Progress

This document captures content that is relevant to the project but belongs in downstream documents (architecture, legal, UX spec) rather than the PRD itself.

---

## A1 — Terms of Service & Platform Rules

The following Terms of Service governs all users of the Skillars platform. All users must explicitly accept these terms at registration. Parents must additionally accept the Parent Consent Policy before creating a minor player profile.

---

### A1.1 — General Terms

1. **Platform Purpose.** Skillars is a marketplace connecting independent football coaches with players and their families. The platform provides tools for scheduling, session management, player development tracking, video coaching, and payments.

2. **Account Eligibility.** Users must be at least 18 years old to create an independent account. Players under 18 must be registered and managed by a parent or legal guardian. Players under 10 may not hold any account; their profile is managed entirely by the parent.

3. **Account Accuracy.** Users must provide accurate and truthful information at registration and keep their profile up to date. Misrepresentation of identity, credentials, or qualifications is a prohibited conduct violation.

4. **One Account per User.** Each individual may hold one account of each role type. A parent account may manage multiple player profiles.

---

### A1.2 — Coach Obligations

1. **Marketplace Listing.** Coaches agree that their public profile, including name, bio, location, specialties, and pricing, will be visible to all visitors (including unauthenticated guests) on the Skillars marketplace.

2. **Accurate Representation.** Coaches must accurately represent their qualifications, coaching experience, and capabilities. Capability badges (Video Feedback, Performance Reports, etc.) may only be displayed when the coach actively uses these features through the platform.

3. **Platform Payments Only.** All payments for coaching services booked through Skillars must be processed through the platform's payment system. Soliciting, accepting, or coercing users into off-platform payments (cash, bank transfer, third-party payment apps) is a prohibited conduct violation subject to account termination.

4. **No Contact Detail Sharing.** Coaches must not share personal email addresses or phone numbers in any free-text field on the platform (bio, session descriptions, messages) with the intent to conduct business outside the platform. The platform will automatically redact contact details found in free-text areas.

5. **Child Safeguarding.** Coaches working with minor players must comply with all platform safeguarding rules, including maintaining parent-visible communication at all times and not communicating with players outside the platform.

6. **Session Honesty.** Coaches must only mark sessions as completed when the session has genuinely taken place. False completion submissions constitute fraud and will result in account termination.

7. **Reliability Obligations.** Coaches accept that cancellations with less than 24 hours notice and coach no-shows will result in reliability strikes. Accumulation of strikes above the platform threshold will trigger account review and may result in suspension.

---

### A1.3 — Parent & Player Obligations

1. **Booking Integrity.** Parents and players must only initiate bookings for sessions they genuinely intend to attend. Repeated no-shows (player absent after 15-minute grace period) result in session credit deduction with no refund.

2. **Platform Payments.** All payments must be made through the Skillars payment system. Parents must not attempt to pay coaches off-platform.

3. **Cancellation Policy.** Parents acknowledge the following cancellation refund policy:
   - More than 24 hours notice: Full refund.
   - 6–24 hours notice: 50% refund.
   - Less than 6 hours notice: No refund.

4. **Minor Account Responsibility.** Parents are fully responsible for the conduct of minor player accounts they manage. Parents acknowledge that all messages between their child and any coach are visible to the parent account.

5. **Honest Reviews.** Players and parents may only submit reviews for coaches with whom they have completed at least one paid session. Reviews must be honest, factual, and not defamatory.

---

### A1.4 — Prohibited Conduct

The following conduct is prohibited and may result in immediate account suspension or permanent ban:

- Harassment, hate speech, or threats directed at any platform user.
- Sexual content or any inappropriate content involving or directed at minors.
- Violence or threats of violence.
- Fraud or misrepresentation of identity, credentials, or session completion.
- Soliciting or accepting off-platform payments.
- Sharing personal contact details in free-text areas to circumvent platform restrictions.
- Uploading content that violates platform content standards (nudity, violence, hate symbols, explicit content).
- Attempting to bypass platform payment or access controls.

---

### A1.5 — Cancellation & Refund Policy

**Parent/Player Cancellations:**

| Notice Given | Refund |
|---|---|
| More than 24 hours | Full refund |
| 6–24 hours | 50% refund |
| Less than 6 hours | No refund |

**Coach Cancellations:**

| Notice Given | Refund to Parent | Penalty to Coach |
|---|---|---|
| More than 24 hours | Full refund | None |
| Less than 24 hours | Full refund | Reliability strike |

**No-Shows:**

| Scenario | Outcome |
|---|---|
| Player no-show | Session marked COMPLETED after 15-minute grace period; credit deducted. |
| Coach no-show | Full automatic refund; reliability strike issued. |

**Weather Cancellations:** The coach may choose to proceed, reschedule, or cancel. Standard cancellation refund rules apply if the session is cancelled.

---

### A1.6 — Data Rights & Privacy

1. **Data Ownership.** Player development data (Skills Radar scores, SLU history, timeline, reports, videos) is owned by the player, not the coach. Players retain their data history when changing coaches.

2. **Data Portability.** Users may request a full export of their personal data, player history, and reports at any time.

3. **Right to Erasure.** Users may request permanent deletion of their account and all associated data. Access is revoked immediately upon request. Physical deletion completes within 30 days. Physical backups are purged within 90 days.

4. **EU Data Residency.** All user data and video assets are stored on EU-region infrastructure in compliance with GDPR.

5. **Data Retention.** Skillars retains data as follows:
   - Chat messages: 24 months.
   - Performance and homework videos (non-yearly subscribers): 90 days.
   - Performance and homework videos (yearly subscribers): Lifetime of active subscription.
   - Development data (reports, radar charts, timeline): Duration of active account.

6. **Minor Data.** For users under 18, the parent or legal guardian controls data export and deletion requests. Videos of minors require parent approval before becoming visible to the minor player.

---

### A1.7 — Dispute Resolution

If a dispute arises over a session (no-show, quality, incomplete delivery), the parent may flag the session through the platform. The session enters a DISPUTED state; funds are frozen pending admin review. The platform admin reviews the session audit trail and resolves the dispute. The platform's decision following review is final. Users subject to enforcement decisions may submit one appeal within 14 days.

---

### A1.8 — Platform Commission & Fees

Skillars charges an 8% commission on all session payments processed through the platform. This commission is deducted automatically before funds are released to the coach. The platform commission rate is subject to change with notice to coaches.

Coaches acknowledge that payment processor fees (e.g., Stripe) are deducted separately and are visible in the coach revenue dashboard.

---

### A1.9 — Account Termination

The platform reserves the right to suspend or permanently ban accounts for violations of these Terms of Service. Users subject to termination will be notified. One appeal may be submitted within 14 days. The platform decision is final.

Subscriptions terminated due to policy violation are not eligible for refund of unused subscription periods.

---

## A2 — Competitive Context

Primary competitors in the German market:
- **Superprof:** Directory-style marketplace with no booking, payment, or development tools.
- **WhatsApp + cash:** The dominant informal workflow for private football coaching.

Skillars' primary differentiation is the combination of professional business tools for coaches (scheduling, session planning, financial reporting) with a longitudinal development record for players — neither competitor provides both in a single platform.

---

## A3 — Pitch Reference

Full pitch narrative (value propositions, competitive differentiator table, sticky factor analysis) is preserved in `requirements/skillars/pitch.md`. The pitch uses simplified language for non-technical audiences (e.g., "5-Pillar Radar" for the 15-skill Skills Radar); the PRD uses accurate technical specifications throughout.

---
