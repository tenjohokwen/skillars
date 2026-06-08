# File Storage Module: Addendum

## Technical Implementation Notes
- **Streaming:** Heavy reliance on `InputStream` and `OutputStream` is mandatory.
- **Outbox Pattern:** Replication jobs should be stored in the primary application database using a status-driven table (PENDING -> PROCESSING -> COMPLETED/FAILED).
- **Configuration:** YAML-based configuration using consistent keys across all storage implementations.
- **Timeouts:** Externally configurable request and connection timeouts are mandatory for all network-bound operations.
- **Provider Migration:** Support for object-copy tooling (e.g., streaming read from Provider A, write to Provider B) as a utility within the gateway.
- **Metadata Portability:** Utility support for metadata export/import to ensure portability during provider migration.
- **Local Development:** `LocalFileSystemStorageService` must mirror the directory structure of the cloud bucket strategy for consistency.
- **Safe Sequencing (Orphan Prevention):** Implementations should follow: Upload object -> Store DB reference -> Commit transaction to prevent orphaned DB records.

## Decisions Considered
- **Versioning:** Bucket versioning explicitly disabled to reduce operational cost/complexity.
- **Soft Deletes:** Explicitly excluded from the storage module; handled by the calling application's database logic.
- **Bucket Strategy:** One-bucket-per-application-per-environment for strict logical isolation.
- **Encryption at Rest:** Enabled at the provider level (SSE-S3 or SSE-KMS) to meet security requirements without adding module complexity.
- **Secret Management:** Secrets must be injected via environment variables or secret managers; hardcoding is strictly forbidden.
- **Metadata Responsibility:** The module provides/extracts metadata during transport, but the consuming application is responsible for its persistence and querying in the application database.
