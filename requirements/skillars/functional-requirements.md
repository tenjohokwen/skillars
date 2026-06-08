# Functional Requirements: Football Coaching Marketplace Platform

This document outlines the core functional requirements for the Football Coaching Marketplace Platform, derived from the product specifications.

## 1. User Management & Safety
### 1.1 User Roles
The system must support the following roles with distinct permissions:

*   **Guest:** Browse coaches, search, and register. Cannot contact coaches, book sessions, or leave reviews.
*   **Player:** View assigned homework, progress reports, and timeline; message coaches (age-dependent). Cannot manage payments or upload videos directly if under policy age restrictions.
*   **Parent:** Manage family accounts (multiple children), handle payments, request bookings, contact coaches, leave reviews, access player history, manage permissions, and view all player-coach messages. Cannot modify coach-owned templates.
*   **Coach:** Create profiles, set pricing, manage availability, accept/reject bookings, build sessions, upload drill content, assign homework, create reports, upload feedback videos, and message players/parents. Cannot access players without permission or export unrelated player data.
*   **Admin:** Suspend accounts, moderate content, process refunds, handle disputes, view reports, delete content, and manage moderation queues.

### 1.2 Age-Based Safeguards
*   **Under 10:** No independent accounts; parent-managed only; no direct messaging.
*   **10–12:** Parent-controlled; parent-visible communication; parent approval for uploads.
*   **13–17:** Player accounts allowed, but parent visibility is mandatory for all messages.
*   **18+:** Full independent account.

## 2. Marketplace & Discovery
### 2.1 Search & Filtering
Users must be able to discover coaches based on:
*   Location (City, District/Quarter).
*   Language, Price Range, and Age Groups coached.
*   Skill Specialization and Ratings.

### 2.2 Coach Profiles & Verification
*   **Profile Content:** Name, bio, languages spoken, specialties, pricing, location, availability, ratings and reviews, verification status, and media gallery.
*   **Verification Levels:** Basic (Email/Phone verified), Trusted (Government ID verified), and Featured (Platform Approved).
*   **Capability Badges:** Visual indicators on coach cards for "Video Feedback," "Performance Reports," "Homework Assignments," "Skills Radar," and "Verified Identity" usage.

## 3. Booking & Scheduling
### 3.1 Request/Approval Workflow
*   **Credit Check:** System must block booking requests if session credits are exhausted. Parents must see a visual credit balance (e.g., "7/10 Sessions Used") before booking.
*   **Workflow:** Parent/Player requests → Coach reviews → Coach accepts/declines → Payment (if not prepaid) → Confirmation.
*   **Session Deduction:** Credits are deducted only when a session is marked `COMPLETED`.

### 3.2 Booking States
The system must manage the full booking lifecycle through the following states:

*   `REQUESTED` — Parent/player has submitted a booking request.
*   `ACCEPTED` — Coach has accepted the request.
*   `DECLINED` — Coach has declined the request.
*   `PAYMENT_PENDING` — Awaiting payment processing.
*   `CONFIRMED` — Payment processed; session confirmed.
*   `UPCOMING` — Transitioned 24 hours before session start; triggers final reminders.
*   `IN_PROGRESS` — Coach has tapped "Start Session" or a manual override has been applied.
*   `COMPLETED` — Session concluded; credits deducted.
*   `CANCELLED_PARENT` — Cancelled by parent.
*   `CANCELLED_COACH` — Cancelled by coach.
*   `NO_SHOW_PLAYER` — Player absent after 15-minute grace period.
*   `NO_SHOW_COACH` — Coach absent; automatic refund and reliability strike issued.
*   `REFUND_PENDING` — Refund has been initiated.
*   `REFUNDED` — Refund completed.

### 3.3 Timezone Management (Pitch-First Protocol)
*   **Default Display:** All sessions display in the "Pitch Timezone" (geographic location of training). All timestamps are stored in UTC.
*   **Browser Override:** Users can toggle to their local timezone, but the system must warn if different from the pitch timezone.
*   **Travel Detection:** The system must notify users on login if their browser timezone differs from their profile or pitch timezone.

### 3.4 Scheduling Features
*   **One-Tap RSVP:** A "Request Change" button allows parents to suggest new times; the coach accepts to update the session instantly.
*   **Calendar Integration:** Two-way sync with Google and Apple Calendars for coaches and parents.
*   The system must also support availability windows, time blocking, session duration tracking, automated reminders, and session duplication.

## 4. Session & Sequence Builder
### 4.1 Sequence Builder
Coaches must be able to:
*   Select a "Development Focus" layer (e.g., "Weak Foot").
*   View "Session DNA" analysis (Technical vs. Physical vs. Cognitive vs. Match Realism).
*   Receive intelligent drill suggestions based on player age and neglected skills.
*   Manage timing and organization via drag-and-drop.

### 4.2 Standard Session Structure
The default session structure is:
1.  Warm-up
2.  Technical
3.  Game Intensity
4.  Cool-down

### 4.3 Smart Session Features
*   **Equipment Packing List:** The system automatically aggregates equipment requirements across all drills in a session into a single packing list.
*   **Reusable Templates:** Coaches can save sessions as reusable templates.

### 4.4 Completion Workflows
*   **Live Session Mode:** Pitch-side UX with a live timer and attendance tracking. Coaches may manually mark drills as done instead of using the automated timer.
*   **Quick Complete Mode:** After-the-fact completion without live interaction; triggers parent confirmation and session consumption.
*   **30-Second Wrap-Up:** Post-session quick ratings (1–5 stars for Effort, Focus, Technique), optional voice-to-text notes, optional homework assignment from the drill library, and an explicit attendance confirmation checkbox. These ratings are for qualitative feedback only and are not used to calculate the 1–100 Skills Radar scores.

## 5. Drill Library
### 5.1 Library Management
*   Support for a central platform library and private coach libraries.
*   Coaches may tag, categorize, clone, and search drills within both libraries.

### 5.2 Drill Metadata
Each drill must store the following metadata:

*   **Skill Weighting:** Contribution per skill across the 15-skill taxonomy.
*   **Rep Density:** Estimated repetitions per minute.
*   **Cognitive Load:** Decision complexity (1–5).
*   **Match Realism:** Similarity to match scenarios (1–5).
*   **Intensity:** Physical load (1–5).
*   **Video Demo:** 15-second infinite loop.
*   **Coaching Points:** Key instructions for the coach.
*   **Equipment List:** Required equipment for the drill.
*   **Difficulty Level:** Age-graded difficulty from U8 to Pro.
*   **Tags:** Developmental intent labels.

### 5.3 Initial Content
The MVP platform launches with 20 professionally curated drills in the platform library.

## 6. Player Development Intelligence
### 6.1 Skill Taxonomy
The system tracks development across 15 specific skills: PAC (Pace), SHO (Shooting), PAS (Passing), DRI (Dribbling), PHY (Physicality), DEF (Defending), WEF (Weak Foot), FIN (Finishing), 1V1 (One-on-Ones), HED (Heading), CRO (Crossing), IBS (In-Box Shooting), OBS (Out-Box Shooting), FKI (Free Kicks), FIT (First Touch).

### 6.2 Skill Load Units (SLU)
*   **Automatic Generation:** SLUs represent estimated developmental exposure per skill, automatically generated from session completion data.
*   **Snapshot Logic:** SLUs must be "baked" into summary tables upon session completion to ensure performance and data integrity (historical data is permanent even if drill metadata changes).
*   **Tracking & Alerts:** System must provide weekly skill exposure charts and "Neglected Skill Detection" alerts.

### 6.3 Player Timeline & Radar
*   **Unified Timeline:** Chronological record of the following event types: sessions, homework assignments, video uploads, coach feedback, "Big Test" assessments, milestones, payments, and reviews.
*   **Timeline Access:** Any coach with an active or previous paid training relationship with the player retains timeline access. Access is also governed by parent permissions and player ownership.
*   **Skills Radar:** 1–100 ability ratings from standardized "Big Tests."
*   **Development Insights:** Line and radar charts correlating accumulated SLUs with assessment improvements.
*   **PDF Reports:** Coach-generated progress reports for parents, exportable as PDF.

### 6.4 Report Branding
Coaches may include their logo, branding, and custom notes in progress reports.

## 7. Messaging
*   **Scope:** Intended for scheduling, coaching communication, and homework feedback (not a general-purpose chat replacement).
*   **Rules:**
    *   Mandatory parent visibility for all minor accounts.
    *   Abuse detection and reporting functionality required.
    *   24-month message retention policy.

## 8. Video Module
### 8.1 Video Workflows
*   **Homework Uploads:** Players submit clips of practice (max 60s / 250MB / 720p).
*   **Coach Feedback:** Coaches provide time-stamped video analysis and telestration (Elite tier) (max 5m / 1GB).
*   **Drill Videos:** Professional demos for library use (max 2m / 500MB).

### 8.2 Video Policies
*   **Privacy:** All videos are private by default; no public video feeds.
*   **Transcoding:** Server-side transcoding is mandatory. Output format: H.264 MP4 with adaptive bitrate streaming.
*   **Downloads:** Player-owned homework videos are downloadable by the player. Coach proprietary drill videos are not downloadable.
*   **Access Termination:** Player access to a coach's tagged videos is revoked once a paid session pack is exhausted or the subscription expires.

### 8.3 Access & Quotas
*   **Quota Enforcement:** Pre-flight verification and atomic increments required for storage management.
*   **Tiered Storage:**

    | Tier | Storage |
    |---|---|
    | Scout Coach (Free) | 0 GB (platform drills only) |
    | Instructor Coach (Pro) | 5 GB |
    | Academy Coach (Elite) | 20 GB |
    | Athlete Player | 2 GB |
    | Semi-Pro Player | 4 GB |
    | Pro Player | 7 GB |

### 8.4 Video Retention
*   **Yearly Subscribers:** Lifetime retention while the subscription remains active.
*   **Non-Yearly Subscribers:** Performance and homework videos older than 90 days are subject to automatic purging.
*   **Account Deletion:** Physical video files are purged within 30 days of an account deletion request.

## 9. Reviews & Ratings
*   **Eligibility:** Only users with a completed, paid session on the platform and no active dispute can leave a review.
*   **Structure:** Includes a star rating, text review, and coach reply.
*   **Moderation:** Admins can hide/remove abusive reviews and suspend repeat offenders.

## 10. Payments & Subscriptions
### 10.1 Revenue Streams
*   **Coach Subscriptions:** Scout (Free), Instructor (Pro), Academy (Elite).
*   **Player Subscriptions:** Athlete, Semi-Pro, Pro.
*   **Marketplace Commission:** 8% platform fee on all marketplace transactions.
*   **Future:** Sponsored coach listings.

### 10.2 Coach Tier Features

| Feature | Scout (Free) | Instructor (Pro) | Academy (Elite) |
|---|---|---|---|
| Marketplace profile | ✓ | ✓ | ✓ |
| Booking requests | ✓ | ✓ | ✓ |
| Basic scheduling | ✓ | ✓ | ✓ |
| Unlimited player management | ✓ | ✓ | ✓ |
| Sequence builder | — | ✓ | ✓ |
| Equipment packing lists | — | ✓ | ✓ |
| Homework assignment | — | ✓ | ✓ |
| Skills Radar (1–100 Assessment) | — | ✓ | ✓ |
| Standard reports | — | ✓ | ✓ |
| Timeline access | — | ✓ | ✓ |
| Video reviews room (time-stamped feedback) | — | — | ✓ |
| Telestration | — | — | ✓ |
| Advanced correlation analytics (SLU vs. Radar) | — | — | ✓ |
| Branded reports | — | — | ✓ |

### 10.3 Player Tier Features

| Feature | Athlete | Semi-Pro | Pro |
|---|---|---|---|
| Billing cycle | Monthly, Quarterly, or Yearly | Yearly only | Yearly only |
| Storage | 2 GB | 4 GB | 7 GB |
| Contact coaches | ✓ | ✓ | ✓ |
| Upload homework | ✓ | ✓ | ✓ |
| Access reports | ✓ | ✓ | ✓ |
| Unified Timeline across all coaches | ✓ | ✓ | ✓ |
| Historical Data Sharing Control | — | — | ✓ |

### 10.4 Premium Feature Activation
*   Advanced features ("Video Reviews Room," "Advanced Analytics," "Branded Reports") activate only for valid, paid player-coach pairs to prevent platform bypass.
*   Premium features are deactivated immediately when the paid session pack is exhausted or the subscription expires.

## 11. Cancellation & Refund Policies
### 11.1 Parent Cancellations
*   **>24h Notice:** Full refund.
*   **6–24h Notice:** 50% refund.
*   **<6h Notice:** No refund.

### 11.2 Coach Cancellations
*   **>24h Notice:** Full refund to parent.
*   **<24h Notice:** Full refund + a reliability strike for the coach.

### 11.3 No-Show Policies
*   **Player No-Show:** Session marked completed after 15 minutes of absence; credits consumed.
*   **Coach No-Show:** Automatic refund + reliability strike. Repeated offences may result in account suspension.

### 11.4 Weather Cancellations
When weather affects a session, the coach may choose to:
*   Proceed with the session.
*   Reschedule to a new time.
*   Cancel (standard refund rules apply).

## 12. Trust, Safety & Compliance
### 12.1 Prohibited Conduct
The following conduct is prohibited on the platform:
*   Harassment and hate speech.
*   Sexual content or inappropriate content involving minors.
*   Violence or threats.
*   Fraud or misrepresentation.
*   Off-platform payment coercion.

### 12.2 Content Moderation
*   **Automated Scanning:** Mandatory scan of all uploads for nudity, violence, hate symbols, explicit content, and non-football inappropriate content.
*   **Reporting:** User reporting for abuse, fraud, inappropriate content, or off-platform payment coercion.
*   **Enforcement:** Tiered penalties — Warning (minor violations), Temporary Suspension (repeated or moderate abuse), Permanent Ban (severe abuse or safety violations).

### 12.3 Appeals
Users subject to enforcement actions may submit one appeal within 14 days. The platform decision is final.

### 12.4 GDPR Compliance
*   **Required Legal Documents:** The platform must publish and maintain: Terms of Service, Privacy Policy, Cookie Policy, Media Consent Policy, Data Processing Agreements, and Parent Consent Policy.
*   **Data Rights:** Users may export personal data, player history, and reports.
*   **Account Deletion:** Upon a deletion request, data becomes inaccessible immediately. Permanent deletion completes within 30 days. Physical backups are purged within 90 days. Deleted data includes: videos, reports, messages, timeline events, and backups.
*   **EU Storage:** All user and video data must remain in EU-region storage.
*   **Data Retention:** Chat messages are retained for 24 months. Performance and homework videos for non-yearly subscribers are purged after 90 days. Yearly subscribers retain videos for the lifetime of their active subscription. General development data (reports, radar charts) is retained while the account is active.
