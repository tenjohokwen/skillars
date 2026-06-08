# File Storage Module: Functional Requirements

## 1. Core Storage Operations
The module MUST provide a provider-agnostic interface (`StorageService`) supporting the following operations:
- **Put Object:** Upload a file to a specified bucket and key using an `InputStream`.
- **Get Object:** Retrieve a file from a specified bucket and key as an `InputStream`.
- **Delete Object:** Physically remove an object from a specified bucket and key.
- **Exists:** Check for the existence of an object at a specific path.
- **Stat:** Retrieve metadata (content type, length, checksum, etc.) for an existing object.
- **Copy:** Perform server-side copying of objects between buckets or keys.

## 2. Implementation Types
The system MUST support multiple storage implementation modes:
- **S3 Implementation:** Full production support for S3-compatible APIs.
- **Local Filesystem Implementation:** Emulated storage for local development and unit testing.
- **Replicated Implementation:** A decorator that wraps primary storage with automated backup logic.

## 3. Asynchronous Replication & Backup
- **Primary-Backup Flow:** Uploads MUST succeed on primary storage before being enqueued for replication.
- **Asynchronous Execution:** Replication to backup storage MUST NOT block the initial application request.
- **Durable Outbox Pattern:** Replication jobs MUST be persisted (e.g., PENDING, PROCESSING, FAILED, COMPLETED states) to ensure no data loss.
- **Retry Mechanism:** Failed replication jobs MUST support configurable retries and backoff strategies.
- **Replication Toggle:** Replication MUST be configurable and independently enable/disable-able without application changes.
- **Deletes:** Physical deletions MUST be replicated to the backup storage.

## 4. Object Naming & Key Generation
- **UUID Generation:** All object keys MUST be generated using UUIDs to prevent predictable names and collisions.
- **Centralized Strategy:** A `StorageKeyGenerator` MUST provide a standardized path structure (e.g., `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}`).
- **Cache Busting:** Naming strategies MUST inherently support cache busting.

## 5. Metadata Management
- **Standard Metadata:** Support for content-type, content-length, checksum, upload timestamps, and original filename.
- **Custom Tags:** Support for application-defined tags and tenant identifiers.
- **Database Storage:** While binaries live in object storage, descriptive metadata SHOULD be stored in the application's database.

## 6. Signed URL Support
- **Download URLs:** Generation of short-lived, pre-signed URLs for secure browser-based downloads.
- **Upload URLs:** Generation of pre-signed URLs to allow clients to upload directly to the storage provider, bypassing the application server.
- **Configurable TTL:** Time-to-live for signed URLs MUST be externally configurable.

## 7. Streaming & Large File Handling
- **Streaming Support:** All operations MUST use `InputStream` and `OutputStream` to avoid loading entire files into memory.
- **Multipart Uploads:** Support for multipart upload protocols for large file efficiency and reliability.

## 8. Content Validation
- **MIME Type Validation:** Verify files against allowed MIME types before storage.
- **Extension Validation:** Ensure file extensions match the detected content type.
- **Size Constraints:** Enforce maximum file size limits.
- **Integrity Checks:** Support for checksum validation to ensure data integrity during transit.
- **Image Verification:** Confirm that image files are structurally valid before storage.
- **Antivirus Scanning:** Optional integration point for antivirus scanning prior to storage acceptance.

## 9. Security & Isolation
- **Bucket Isolation:** Use a "one bucket per application per environment" strategy. Multiple applications MAY share the same provider account; logical isolation MUST occur through bucket separation.
- **Path Sanitization:** Sanitize all inputs to prevent path traversal attacks.
- **Filename Sanitization:** Cleanse original filenames before storing them in metadata.
- **Secrets Management:** Credentials MUST be externally configurable. Secrets MUST NOT be hardcoded.
- **Encryption:** 
    - Support for TLS for all data in transit.
    - Support for server-side encryption at rest.
    - Optional support for customer-managed encryption keys and client-side encryption.

## 10. Migration & Portability
- **Provider Agnostic:** Applications MUST NOT depend on provider-specific APIs.
- **Migration Tooling:** Support for object copying and replication-assisted migration between providers (e.g., moving from Danube to Wasabi).
- **Metadata Portability:** Support metadata export/import to allow migration without data loss.
- **Application Transparency:** Applications MUST remain unaware of provider changes during or after migration.

## 11. Versioning & Deletion

- **Bucket Versioning:** S3 bucket versioning MUST remain disabled to reduce operational complexity and storage cost.
- **Soft Delete:** Soft delete is an application/database concern only. The storage layer MUST support physical deletion exclusively.
- **Deletion Workflow:** Physical deletion MUST follow this sequence: application soft-delete → configurable retention period → physical delete from primary → deletion replication job to backup storage.

## 12. Retry & Failure Semantics

- **Retry Policy:** The module MUST support configurable retry counts and backoff strategies for all storage operations.
- **Timeouts:** Configurable request timeouts and connection timeouts MUST be supported.
- **Replication Failures:** Failed replication jobs MUST be logged, remain retryable, and MUST NOT cause data loss.
- **Upload Independence:** Primary upload success MUST NOT depend on backup replication success.

## 13. Transaction Boundaries

- **Decoupling:** The module MUST NOT tightly couple database transactions with object storage operations.
- **Safe Workflow:** The recommended sequence is: (1) upload object, (2) store metadata reference in DB, (3) commit application transaction.
- **Async Alternative:** An async workflow (persist upload request → async upload) is also supported.
- **Orphan Prevention:** The system MUST avoid both orphaned database records and orphaned storage objects.

## 14. Observability

- **Structured Logging:** All storage operations MUST produce structured logs.
- **Metrics:** The module MUST expose upload metrics, replication metrics, error counters, and latency metrics.
- **Tracing:** The module SHOULD support distributed tracing of uploads, downloads, and replication operations (e.g., via OpenTelemetry).
- **Recommended Integrations:** Prometheus, Grafana, OpenTelemetry.

## 15. Development & Testing Strategy

- **Local Development:** The `LocalFileSystemStorageService` MUST be used for local development environments.
- **Integration Testing:** Dockerized MinIO MUST be used for integration tests and CI pipelines. Filesystem storage alone is insufficient for production parity.
- **MinIO Coverage:** MinIO MUST cover signed URL testing, multipart upload testing, and S3 compatibility testing.

## 16. Non-Goals

The storage module is NOT responsible for:
- Business-level authorization or access control decisions
- Application-level soft delete policies
- Document workflow management
- Image transformations or processing
- CDN configuration or management
- Media transcoding

These concerns remain the responsibility of the consuming application.
