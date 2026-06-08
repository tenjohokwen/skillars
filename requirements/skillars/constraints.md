# Project Constraints: Football Coaching Marketplace Platform

This document outlines the technical, operational, and regulatory constraints that must be adhered to during the development of the platform.

## 1. Technical Stack Constraints
The following technologies are mandatory for the implementation:

*   **Backend:** Spring Boot with Java 21+.
*   **Frontend:** Vue 3 with the Quasar Framework (using TypeScript).
*   **Database:** PostgreSQL (utilizing JSONB for extensible metadata).
*   **Video Hosting:** Bunny.net (EU region storage only).
*   **Payments:** Stripe Connect.
*   **Deployment:** Containerized (Docker) with Nginx as a reverse proxy.

## 2. Video & Media Constraints
Strict technical limits are enforced to manage storage costs and performance:

*   **Homework Uploads:** Max 60 seconds, max 250 MB, max 720p resolution.
*   **Coach Feedback Clips:** Max 5 minutes, max 1 GB.
*   **Drill Uploads:** Max 2 minutes, max 500 MB.
*   **Transcoding:** Mandatory server-side transcoding to H.264 MP4 with adaptive bitrate streaming.
*   **Access:** Direct physical file downloads are restricted for coach proprietary drill videos.

## 3. Storage Quotas
Storage limits are tied to user subscription tiers:

*   **Coaches:**
    *   Scout (Free): 0 GB (platform drills only).
    *   Instructor (Pro): 5 GB.
    *   Academy (Elite): 20 GB.
*   **Players:**
    *   Athlete: 2 GB.
    *   Semi-Pro: 4 GB.
    *   Pro: 7 GB.

## 4. Safety & Age Constraints
Development must enforce the following safety logic:

*   **Under 10:** No independent accounts; all activity parent-managed; no direct messaging.
*   **Ages 10–12:** Parent-controlled; all communication visible to parents; parent approval required for all uploads.
*   **Ages 13–17:** Player accounts allowed; parent visibility is mandatory for all messaging.
*   **Verification:** High-level coaching badges require government-issued identity verification.

## 5. Regulatory & Compliance Constraints
*   **GDPR:** All user data and video assets must be stored within EU-region data centers.
*   **Data Retention:** 
    *   Non-yearly subscriber performance videos must be purged after 90 days.
    *   Chat messages must be retained for exactly 24 months.
    *   Account deletion must result in permanent data erasure within 30 days.

## 6. Business & Scope Constraints (Out of Scope for MVP)
The following features are explicitly **excluded** from the initial release:

*   Group training sessions or team/academy management.
*   Real-time video coaching or live streaming.
*   Native mobile applications (MVP is web-responsive only).
*   Offline mode or AI-based ranking systems.
*   Wearable integrations or complex accounting/tax systems.

## 7. Operational Constraints
*   **Timezone Protocol:** System must default to the training pitch location ("Pitch-First") for all scheduling to prevent "Wall Time" confusion.
*   **Payment Eligibility:** Premium coaching features (Video Feedback, Skills Radar) must only activate if a valid, paid session credit exists for the specific player-coach pair.
