# Handling Time: Best Practices

The industry-standard rule for distributed systems is simple: **Everything is UTC until it is displayed to the user.**

## 1. The Golden Rule: "UTC Always"
*   **Backend:** Always persist dates in UTC (database `TIMESTAMP` columns). Always perform calculations (business logic, cron jobs, date math) in UTC.
*   **Transmission (JSON):** Send and receive timestamps strictly in **ISO-8601 UTC** format.
    *   Example: `2026-05-19T14:30:00Z` (The `Z` is crucial; it explicitly indicates UTC).
*   **Frontend:** The frontend should receive that ISO-8601 string, treat it as UTC, and **only convert it to the user's local time zone at the very last second** for display in the UI.

## 2. Why UTC Transmission?
*   **Eliminates Ambiguity:** UTC prevents bugs where events shift because a client changed time zones.
*   **Sorting & Filtering:** It is trivial to sort and filter lists of events when they are all in UTC.
*   **Consistency:** Your database, cache (Redis), and logs will all be in the same time zone, which is critical for debugging.

## 3. Practical Implementation

### Backend (Java/Spring)
*   **Use `java.time.Instant` or `java.time.OffsetDateTime` (UTC).**
*   **Avoid:** `java.util.Date` or `java.time.LocalDateTime` (unless explicitly handling "local" calendar time, not a point in time).
*   **Configuration:** Ensure your application is configured to serialize UTC:
    ```yaml
    spring:
      jackson:
        time-zone: UTC
        serialization:
          WRITE_DATES_AS_TIMESTAMPS: false
    ```

### Frontend (Vue/Quasar/JS)
*   **Parse as UTC:** When you receive a string from the API, treat it as a UTC date.
*   **"Last-Mile" UI Display:** Convert only for the final display.
    *   **Using Day.js (Recommended):**
        ```javascript
        import dayjs from 'dayjs';
        import utc from 'dayjs/plugin/utc';
        dayjs.extend(utc);

        // Parse received UTC string
        const apiDate = dayjs.utc('2026-05-19T14:30:00Z');
        
        // Format for display in user's browser local time zone
        const displayDate = apiDate.local().format('YYYY-MM-DD HH:mm');
        ```

## 4. Special Case: Local "Calendar" Time
If you need to store "local" time (e.g., a reminder set for "08:00 AM" regardless of time zone), store two fields:
1.  `trigger_time`: The local time string (e.g., `08:00`).
2.  `timezone_id`: The IANA time zone identifier (e.g., `Africa/Douala`).

**Never store points-in-time as offsets or local strings.**
