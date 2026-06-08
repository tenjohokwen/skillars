# File Storage Module: Non-Functional Requirements

## 1. Reliability & Availability
- **Production-Grade Reliability:** The module MUST be designed for mission-critical production environments.
- **Resilient Replication:** Backup replication MUST be asynchronous and durable, ensuring that failures in backup storage do not impact primary application availability.
- **Retry Semantics:** The system MUST implement configurable exponential backoff, retry counts, connection timeouts, and request timeouts for all network-bound storage operations.
- **No Data Loss:** Replication jobs MUST be persisted in a durable outbox to ensure recovery from transient failures or system restarts.

## 2. Performance & Efficiency
- **Memory Management:** The system MUST NOT load entire files into memory; all upload and download operations MUST use streaming (`InputStream`/`OutputStream`) with multipart upload support for large files.
- **Low Latency:** Operations like signed URL generation and metadata retrieval MUST be optimized for minimal overhead.
- **Asynchronous Processing:** Long-running tasks (like replication) MUST be offloaded to background workers to avoid blocking user-facing requests.
- **Reduced Bandwidth:** Use of signed URLs is preferred to offload file transfer bandwidth from the application server directly to the storage provider.

## 3. Observability
- **Structured Logging:** All storage operations, especially failures and replication attempts, MUST be logged using structured formats.
- **Metrics Collection:** The module SHOULD export metrics for:
    - Upload/Download latency.
    - Error rates per provider.
    - Replication queue depth and processing time.
    - File size distributions.
- **Tracing:** Integration with OpenTelemetry for distributed tracing of storage requests across service boundaries.

## 4. Security & Compliance
- **Data in Transit:** All connections to storage providers MUST use HTTPS/TLS.
- **Data at Rest:** Support for server-side encryption (SSE) at the provider level is mandatory.
- **Least Privilege:** Applications SHOULD use scoped credentials limited to specific buckets.
- **Input Sanitization:** Robust protection against path traversal and malicious file naming.
- **Unpredictable Object Paths:** Object keys MUST be UUID-based to prevent enumeration and direct-access attacks.
- **Short-Lived Signed URLs:** Signed URLs MUST be time-limited and SHOULD be the preferred access mechanism to avoid exposing permanent object references.
- **Credential Management:** Secrets MUST NOT be hardcoded and MUST be externally configurable via environment variables or secret managers.

## 5. Portability & Maintainability
- **Provider Agnostic:** The architecture MUST allow switching between S3-compatible providers (Danube, Wasabi, AWS, MinIO) without changing application code.
- **Decoupling:** Storage logic MUST remain isolated from business logic.
- **Local Parity:** Use of MinIO for integration testing to ensure development environments closely mirror production S3 behavior.
- **Simple Lifecycle:** Bucket versioning SHOULD be disabled to reduce operational complexity and cost.
- **Bucket Isolation:** The system MUST use one bucket per application per environment (e.g. `crm-prod`, `crm-stage`). Multiple applications MAY share the same provider account but MUST NOT share buckets.
- **Migration Capability:** The module MUST support provider migration via object copy tooling, replication-assisted migration, and metadata export/import. Applications MUST remain unaware of provider changes during migration.

## 6. Scalability
- **Horizontal Scaling:** The module (especially the replication workers) MUST support horizontal scaling to handle increased upload volumes.
- **Multi-Tenant Ready:** The bucket and key naming strategies MUST support logical isolation of data for different applications or tenants.

## 7. Operational Simplicity
- **Standardized Configuration:** Use of a consistent YAML-based configuration model for all storage settings.
- **Physical vs Soft Deletes:** Physical deletion logic is centralized in the storage module, while soft-delete logic remains the responsibility of the application layer to keep the storage interface simple.

## 8. Object Naming & Key Generation
- **UUID-Based Keys:** Object keys MUST use UUIDs to avoid collisions and prevent predictable naming.
- **Centralized Generation:** Applications MUST NOT generate object keys directly. The module MUST provide a centralized `StorageKeyGenerator`.
- **Structured Key Format:** Keys SHOULD follow the pattern `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` (e.g. `users/123/2026/05/550e8400-e29b-41d4-a716-446655440000.jpg`).

## 9. Metadata Support
- **Required Fields:** The module MUST support metadata storage covering at minimum: content type, content length, checksum, upload timestamp, original filename, and tenant identifier.
- **Custom Tags:** The module MUST support arbitrary custom tag maps for application-specific metadata.
- **Storage Separation:** Binary content MUST reside in object storage. All metadata MUST reside in the application database. The storage module MUST NOT be responsible for querying or indexing metadata.

## 10. Content Validation
- **Pre-Storage Rejection:** Invalid files MUST be rejected before any storage operation is attempted.
- **Validation Hooks:** The module MUST support configurable validation including: MIME type validation, file extension validation, and maximum file size enforcement.
- **Optional Scanning:** The module SHOULD support pluggable antivirus scanning and image verification integrations.

## 11. Transaction Boundaries
- **Decoupled Transactions:** The module MUST NOT couple database transactions with object storage uploads. These are independent operations with different failure modes.
- **Safe Ordering:** Implementations MUST follow a safe sequencing workflow — either (1) upload object → store DB reference → commit transaction, or (2) persist upload request → execute async upload.
- **No Orphans:** The system MUST prevent orphaned database records (metadata with no object) and orphaned objects (stored files with no DB reference).
