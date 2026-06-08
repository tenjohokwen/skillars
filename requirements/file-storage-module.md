# Standardized File Storage Gateway Specification

## 1. Overview

This specification defines a reusable, production-grade file storage module intended to be shared across multiple applications. The module provides a standardized abstraction over S3-compatible object storage systems while supporting replication, backup, metadata management, signed URLs, observability, and interchangeable providers.

The module is designed to:

* support multiple applications
* remain provider-agnostic
* support future migrations
* simplify application integration
* isolate infrastructure concerns
* provide production-grade reliability

The architecture supports:

* primary object storage
* optional asynchronous replication to backup storage
* local development storage
* interchangeable S3-compatible providers

Primary intended providers:

* Danube Data S3 Storage
* Wasabi
* MinIO (dev/integration testing)

The implementation MUST remain fully compatible with any S3-compatible provider.

---

# 2. Core Design Principles

The storage module MUST:

* expose a single generic storage abstraction
* hide provider-specific implementation details
* support interchangeable S3-compatible providers
* support asynchronous backup replication
* avoid predictable file names
* support centralized object key generation
* support streaming uploads/downloads
* support metadata storage
* support signed URLs
* support migration between providers
* support application-level soft deletes
* avoid coupling storage logic with business logic

---

# 3. Architecture Overview

```text
Application
    |
    v
StorageService
    |
    +-- LocalFileSystemStorageService
    |
    +-- S3StorageService
    |
    +-- ReplicatedStorageService
            |
            +-- Primary S3 Provider
            +-- Backup S3 Provider

Additional Components:
    - ReplicationQueue
    - ReplicationWorker
    - StorageKeyGenerator
    - SignedUrlService
    - MetadataRepository
```

---

# 4. Storage Providers

## 4.1 Supported Providers

The system MUST support interchangeable S3-compatible providers.

Examples:

* Danube Data
* Wasabi
* AWS S3
* MinIO
* Cloudflare R2
* Backblaze B2 (S3-compatible mode)

Applications MUST NOT depend on provider-specific APIs.

---

# 5. Storage Abstraction

## 5.1 StorageService Interface

The module MUST expose a provider-agnostic interface.

Example:

```java
public interface StorageService {

    StoredObject put(
        String bucket,
        String key,
        InputStream stream,
        ObjectMetadata metadata
    );

    InputStream get(
        String bucket,
        String key
    );

    void delete(
        String bucket,
        String key
    );

    boolean exists(
        String bucket,
        String key
    );

    URI getSignedDownloadUrl(
        String bucket,
        String key,
        Duration ttl
    );

    URI getSignedUploadUrl(
        String bucket,
        String key,
        Duration ttl
    );

    ObjectMetadata stat(
        String bucket,
        String key
    );

    void copy(
        String sourceBucket,
        String sourceKey,
        String targetBucket,
        String targetKey
    );
}
```

---

# 6. Implementations

## 6.1 LocalFileSystemStorageService

Used for:

* local development
* unit testing
* lightweight environments

This implementation MUST emulate the generic storage interface.

---

## 6.2 S3StorageService

Production implementation using S3-compatible APIs.

Responsibilities:

* upload/download/delete objects
* multipart uploads
* metadata handling
* signed URL generation
* retries
* encryption support

---

## 6.3 ReplicatedStorageService

Decorator implementation adding asynchronous replication support.

Responsibilities:

* upload to primary storage
* enqueue replication jobs
* enqueue deletion replication jobs
* avoid blocking application requests

Replication MUST be configurable and optional.

---

# 7. Backup and Replication

## 7.1 Replication Requirements

Replication MUST:

* be asynchronous
* be configurable
* support enable/disable toggling
* support retries
* support failure recovery

Replication MUST NOT block uploads.

---

## 7.2 Recommended Backup Architecture

```text
Application Upload
        |
        v
Primary Storage
        |
        v
Replication Outbox
        |
        v
Replication Worker
        |
        v
Backup Storage
```

---

## 7.3 Replication Mode

Supported mode:

```text
Asynchronous Replication
```

Uploads succeed once primary storage succeeds.

Replication to backup storage occurs independently.

---

## 7.4 Outbox Pattern

Replication MUST use a durable outbox pattern.

The system MUST NOT rely on:

* in-memory queues
* threads only
* fire-and-forget execution

Replication jobs MUST be persisted.

Example states:

* PENDING
* PROCESSING
* FAILED
* COMPLETED

---

# 8. Object Naming Strategy

## 8.1 Naming Rules

Object keys MUST:

* use UUIDs
* avoid predictable names
* avoid collisions
* support cache busting

Applications MUST NOT generate object keys directly.

---

## 8.2 Centralized Key Generation

The module MUST provide a centralized `StorageKeyGenerator`.

Example structure:

```text
{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}
```

Example:

```text
users/123/2026/05/550e8400-e29b-41d4-a716-446655440000.jpg
```

---

# 9. Metadata Support

## 9.1 Object Metadata

The module MUST support metadata storage.

Supported metadata SHOULD include:

* content type
* content length
* checksum
* upload timestamp
* original filename
* custom tags
* tenant identifier

Example:

```java
class ObjectMetadata {
    String contentType;
    long contentLength;
    String checksum;
    Map<String, String> tags;
}
```

---

# 10. Streaming Support

The module MUST support streaming uploads and downloads.

The system MUST NOT require loading entire files into memory.

Uploads and downloads MUST use:

* InputStream
* OutputStream
* multipart upload support

---

# 11. Content Validation

The module MUST support content validation hooks.

Validation SHOULD include:

* MIME type validation
* extension validation
* maximum file size checks
* image verification
* optional antivirus scanning integration

Invalid files MUST be rejected before storage.

---

# 12. Signed URL Support

The module MUST support:

* signed upload URLs
* signed download URLs

Signed URLs SHOULD be the preferred method for:

* browser uploads
* browser downloads
* large file transfer

Benefits:

* reduced application bandwidth
* lower infrastructure costs
* improved scalability

---

# 13. Bucket Strategy

## 13.1 Bucket Isolation

The system MUST use:

```text
One bucket per application per environment
```

Examples:

```text
crm-prod
crm-stage
billing-prod
billing-stage
```

---

## 13.2 Shared Provider Accounts

Multiple applications MAY share:

* the same Wasabi account
* the same Danube account

Logical isolation MUST occur through bucket separation.

---

# 14. Versioning and Deletion

## 14.1 Bucket Versioning

S3 bucket versioning MUST remain disabled.

Reasoning:

* simpler lifecycle management
* lower storage cost
* reduced operational complexity

---

## 14.2 Soft Delete

Soft delete MUST be handled at the application/database layer.

The storage layer MUST support physical deletion only.

---

## 14.3 Recommended Deletion Strategy

Deletion workflow:

```text
Application Soft Delete
        |
        v
Retention Period
        |
        v
Physical Delete
        |
        v
Delete Replication Job
```

The system MUST replicate deletions to backup storage.

---

# 15. Encryption Requirements

The module MUST support:

* TLS in transit
* server-side encryption at rest

Optional support:

* customer-managed encryption keys
* client-side encryption

All providers MUST use HTTPS connections.

---

# 16. Retry and Failure Semantics

The module MUST support configurable:

* retry counts
* retry backoff
* request timeouts
* connection timeouts

Replication failures MUST:

* be logged
* remain retryable
* avoid data loss

Primary upload success MUST NOT depend on backup success.

---

# 17. Observability

The module MUST provide:

* structured logging
* upload metrics
* replication metrics
* error counters
* latency metrics

Recommended integrations:

* OpenTelemetry
* Prometheus
* Grafana

The system SHOULD support tracing of:

* uploads
* downloads
* replication operations

---

# 18. Transaction Boundaries

The module MUST NOT tightly couple:

* database transactions
* object storage uploads

Recommended workflow:

```text
1. Upload object
2. Store metadata reference in DB
3. Commit application transaction
```

OR:

```text
1. Persist upload request
2. Async upload workflow
```

The system MUST avoid:

* orphaned DB records
* orphaned objects

---

# 19. Binary Storage vs Metadata Storage

Binary content MUST remain in object storage.

Metadata MUST remain in the application database.

Recommended metadata table:

```text
file_objects
    id
    bucket
    storage_key
    content_type
    file_size
    checksum
    owner_id
    created_at
    deleted_at
```

---

# 20. Migration Capability

The module MUST support migration between providers.

Migration support SHOULD include:

* object copy tooling
* replication-assisted migration
* bucket migration support
* metadata export/import support

Applications MUST remain unaware of provider changes.

---

# 21. Development and Testing Strategy

## 21.1 Local Development

Recommended:

* LocalFileSystemStorageService

---

## 21.2 Integration Testing

Recommended:

* Dockerized MinIO

MinIO MUST be used for:

* integration testing
* CI pipelines
* signed URL testing
* multipart upload testing
* S3 compatibility testing

Filesystem storage alone is insufficient for production parity.

---

# 22. Configuration Model

Example configuration:

```yaml
storage:
  provider: replicated

  primary:
    type: s3
    endpoint: https://s3.danube.example
    region: eu-central
    bucket: crm-prod
    access-key: xxx
    secret-key: xxx

  backup:
    enabled: true
    type: s3
    endpoint: https://s3.wasabisys.com
    region: eu-central-1
    bucket: crm-prod-backup
    access-key: xxx
    secret-key: xxx

  replication:
    enabled: true
    mode: async
    retries: 10
    backoff-seconds: 30

  signed-urls:
    upload-ttl-minutes: 15
    download-ttl-minutes: 60

  validation:
    max-file-size-mb: 25
    allowed-content-types:
      - image/jpeg
      - image/png
      - application/pdf
```

---

# 23. Security Requirements

The module MUST:

* sanitize filenames
* prevent path traversal
* validate content types
* avoid predictable object paths
* support short-lived signed URLs

Secrets MUST NOT be hardcoded.

Credentials MUST be externally configurable.

---

# 24. Non-Goals

The storage module is NOT responsible for:

* business-level authorization
* application soft delete policies
* document workflows
* image transformations
* CDN management
* media transcoding

These concerns remain application responsibilities.

---

# 25. Recommended Defaults

| Feature           | Recommended Default |
| ----------------- | ------------------- |
| Replication       | Enabled             |
| Replication Mode  | Async               |
| Bucket Versioning | Disabled            |
| Object Naming     | UUID-based          |
| Signed URLs       | Enabled             |
| Encryption        | Enabled             |
| Retry Policy      | Exponential Backoff |
| Local Testing     | MinIO               |
| Metadata Storage  | Database            |
| Key Generation    | Centralized         |

---

# 26. Final Recommendation

The storage module SHOULD be treated as a reusable infrastructure component shared across applications.

The architecture prioritizes:

* portability
* reliability
* scalability
* provider independence
* operational simplicity
* production readiness

The final implementation MUST ensure that:

* applications interact only with the generic abstraction
* storage providers remain interchangeable
* replication remains resilient
* failures remain observable
* migrations remain possible without application rewrites
