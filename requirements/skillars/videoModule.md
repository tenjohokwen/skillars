# Video Module (Production-Ready Specification)

The Video Module is the technical engine of the platform, enabling remote coaching through professional-grade sports analysis. This specification ensures cost-efficiency, security, and strict quota enforcement integrated with the platform's payment and subscription systems.

### 1\. System Architecture & Integration (Bunny.net)

The platform utilizes the Bunny Stream API for video hosting, transcoding, and delivery.

*   **Decoupled Metadata Logic:** The application server maintains a `VideoMetadata` entity (PostgreSQL) to track ownership, status, and quotas.
*   **Asynchronous Processing Pipeline:**
    1.  **Initiate (Pre-flight):** User sends `POST /video/upload/initiate { fileSize }`. Backend verifies remaining storage quota and issues a temporary signed upload URL.
    2.  **Upload:** User uploads directly to Bunny.net.
    3.  **Transcode:** Bunny.net automatically transcodes to 720p maximum (to optimize storage/bandwidth costs).
    4.  **Sync (Webhook):** Bunny.net triggers a `video.transcoded` webhook. The backend updates the exact file size and updates the player's/coach's usage statistics.

### 2\. Quota Enforcement & Metering (Concurrency-Safe)

To protect the platform from unexpected costs and prevent users from "double-spending" quotas via simultaneous requests, the system utilizes **Atomic Database Operations** and a **Reservation Pattern**.

#### 2.1 Storage Quota (Coach-Centric)
*   **Atomic Increment:** All storage updates utilize an atomic SQL `UPDATE` statement. The system checks and increments the `used_bytes` in a single database transaction. 
*   **Reservation (Pre-flight):** Upon calling `POST /video/upload/initiate`, the system creates a `QuotaReservation` entry. The requested size is "reserved" in the user's quota.
    *   **Success:** When the Bunny.net webhook confirms transcoding, the reservation is converted into a permanent `VideoMetadata` record.
    *   **Failure/Timeout:** If the upload fails or exceeds the 60-minute timeout, a background task (Spring `@Scheduled`) releases the reservation.
*   **Deduplication Safety:** Platform-provided "Master Drills" utilize **Global Reference Counting**. A single file is stored on Bunny.net; individual coach records point to this file. The reference counter is incremented/decremented atomically.

#### 2.2 Streaming & Bandwidth Quota (Player-Centric)
*   **Atomic Reservation:** The platform tracks **Signed URL Generation** as a proxy for bandwidth intent. Every time a signed URL is requested, the estimated bandwidth is deducted atomically from the monthly cap.
*   **Simultaneous Request Protection:** By using atomic increments, even if a user tries to load two videos simultaneously from different browsers, the second request will fail the atomic "check-and-update" if the total exceeds the cap.

### 3\. Streaming Protection & Security

To prevent bandwidth theft and ensure minor safety:

*   **Tokenized Signed URLs:** All playback URLs are signed by the application server.
*   **IP-Address Binding:** Signed tokens are optionally bound to the requester's IP address to prevent sharing links outside the session.
*   **Referrer Whitelisting:** Bunny.net Edge Rules restrict video chunk delivery strictly to the platform's authorized domains (e.g., `app.skillars.com`).
*   **Expiry:** Signed links expire within 2 hours.
*   **Minor Safety:** Videos of minors are flagged `HIDDEN` until a Parent account issues an `APPROVED` status via the backend.

### 4\. Lifecycle & Cleanup Logic

The module automates data cleanup based on account status and payment history.

#### 4.1 Subscription-Linked Lifecycle
*   **LOCKED (Grace Period):** 0–30 days after subscription expiration. Videos remain in storage but the `VideoService` refuses to sign playback URLs.
*   **ARCHIVED:** 31–90 days. Videos are moved to cold storage (Bunny.net Storage Zones) to minimize costs.
*   **PURGED:** After 90 days of continuous non-payment. A batch job triggers a permanent API `DELETE` to Bunny.net and purges metadata.

#### 4.2 Account Deletion
*   **Immediate Deactivation:** Upon account deletion request, access is revoked instantly.
*   **Cascade Purge:** The system identifies all videos owned by the user (and their shadow accounts) and executes a physical purge within 30 days.

#### 4.3 Authorization Rules (RBAC)
*   **Deletion Safety:** A `DELETE` request is only authorized if `requesterId == ownerId` OR the requester is the verified Parent of the owner.
*   **Admin Override:** Platform admins may delete content for moderation reasons (e.g., policy violations).

### 5\. Technical Constraints for Sports Analysis

To support professional-grade review (slow-motion scrubbing):

*   **Format:** HLS (HTTP Live Streaming) with 2-second segment sizes for precise frame seeking.
*   **Codec:** H.264/AAC for universal compatibility.
*   **Duration/Size Caps:**
    *   **Homework:** 60s (250MB).
    *   **Drill Demos:** 120s (500MB).
    *   **Coach Reviews:** 300s (1GB).

### 6\. Automated Moderation & Safety

*   **Async Scan:** Every upload is passed to an AI moderation service (e.g., Hive or AWS Rekognition) to detect nudity or violence.
*   **Enforcement:** If a video is flagged with high confidence, the system auto-locks the account and notifies administrators for manual review.
