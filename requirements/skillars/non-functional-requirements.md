# Non-Functional Requirements: Football Coaching Marketplace Platform

This document defines the quality attributes, technical constraints, and operational requirements for the Football Coaching Marketplace Platform.

## 1. Performance
*   **Page Load Time:** All core pages (Marketplace, Profiles, Portals) must load in under 3 seconds on standard 4G/Broadband.
*   **Video Playback:** Video startup time must be under 2 seconds.
*   **Data Retrieval (Snapshot Logic):** SLU (Skill Load Units) calculations must be "baked" into summary tables (`player_skill_stats`) upon session completion. Real-time joins for historical development data are prohibited to ensure sub-second response times for long-term player histories. Once a session is marked `COMPLETED`, its SLU contribution is permanent — subsequent changes to drill metadata (e.g., changing a drill's Rep Density) must not retroactively alter historical SLU data.
*   **Frontend Responsiveness:** The Vue frontend must use polling or WebSockets to update UI states during asynchronous video processing pipelines (Transcoding/Scanning).

## 2. Availability & Reliability
*   **Uptime Target:** 99.5% availability for core services.
*   **Concurrency & Multi-Node Safety:**
    *   **Atomic Quota Increments:** All storage/bandwidth updates must use atomic SQL updates (e.g., `UPDATE ... SET used = used + :amount WHERE (used + :amount) <= limit`) to prevent race conditions in multi-node environments.
    *   **Distributed Consistency:** Use PostgreSQL `READ COMMITTED` or `REPEATABLE READ` isolation levels with explicit row-level locking (`SELECT ... FOR UPDATE`) where atomic increments are insufficient.
    *   **Optimistic Locking:** Mandatory use of Hibernate `@Version` on all metadata entities to prevent stale-data overwrites.

## 3. Scalability
*   **Architecture:** Modular monolith initially, with clear service boundaries to facilitate future microservice extraction.
*   **Database:** PostgreSQL with `JSONB` for `drill_metadata`, `session_dna`, and `assessment_payloads` to support an extensible taxonomy without schema migrations. JSONB fields must be mapped using `hypersistence-utils` to maintain Java type-safety alongside database flexibility.
*   **Media Hosting:** Integration with Bunny.net for global edge delivery of video assets.

## 4. Security & Data Isolation
*   **Data Isolation (Shadow Accounts):**
    *   **Family Isolation:** Enforcement of family-level isolation using Spring Security expressions (e.g., `@PreAuthorize("@securityService.isParentOf(#playerId)")`).
    *   **Query Filtering:** All Hibernate queries for player data must include a `parent_id` filter as a mandatory security layer.
*   **Media Security:**
    *   **Tokenization:** Playback URLs must be signed and IP-bound.
    *   **Referrer Whitelisting:** Bunny.net Edge Rules must enforce referrer whitelisting to prevent unauthorized hotlinking.
*   **Authentication:** JWT-based RBAC (Role-Based Access Control) with secure, encrypted token storage.

## 5. Video Engineering & Quotas
*   **Pre-flight Reservation:** All uploads must begin with a `POST /video/upload/initiate { fileSize }` request. This call must not only check but **reserve** storage quota against the user's current usage and subscription tier.
    *   A `PendingUpload` record must temporarily block the requested size.
    *   Reservations must automatically expire and release if the Bunny.net `video.transcoded` webhook is not received within 60 minutes.
*   **Deduplication Safety:** Shared drill videos must use a global reference counter. Physical files are only deleted when the reference count reaches zero.
*   **Video Lifecycle States:**
    *   **LOCKED:** Access is revoked immediately upon subscription expiration.
    *   **ARCHIVED:** Videos are moved to lower-cost storage after 30 days of non-payment.
    *   **PURGED:** Physical deletion from Bunny.net occurs after 90 days of non-payment, or within 30 days of an account deletion request.
*   **Video State Machine:** Implement a strict state machine: `PENDING` → `UPLOADED` → `SCANNING` → `TRANSCODING` → `PUBLISHED`.

## 6. Compliance & Safety
*   **GDPR:** EU-region storage only. Workflows for data export and permanent erasure.
*   **Data Retention:**
    *   **Chat:** 24-month retention.
    *   **Videos (Non-Yearly):** Purge after 90 days.
    *   **Physical Deletion:** fizik physical deletion from Bunny.net must occur within 30 days of an account deletion request.
*   **Content Safety:** Mandatory automated moderation scanning for nudity, violence, and hate speech upon upload.

## 7. Operational & Maintainability
*   **Timezone Protocol (Pitch-First):**
    *   **Database:** Use `TIMESTAMPTZ` for all temporal fields. Every `Session` and `CoachProfile` entity must store a `canonical_timezone` string (e.g., `"Europe/Berlin"`).
    *   **Application Layer:** Use `java.time.OffsetDateTime` or `java.time.ZonedDateTime` for all business logic. All internal calculations (availability checks, overlap detection) must occur in UTC.
    *   **Frontend:** Use `Luxon` or `Day.js` for timezone manipulation. The UI displays Pitch Time (Wall Time) by default; a "Browser Time" toggle is provided for remote coordination use cases (e.g., video reviews).
    *   **Display:** UI defaults to the training location timezone to prevent "Wall Time" confusion.
    *   **Detection:** System notifies users if their browser timezone differs from their profile/pitch timezone upon login.
*   **Observability:**
    *   Centralized logging (Loki) and metrics (Prometheus/Grafana).
    *   Distributed tracing (Tempo) for debugging across service boundaries.

