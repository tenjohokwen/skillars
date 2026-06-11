---
project_name: 'javatemplate'
user_name: 'Mbah'
date: '2026-05-23'
sections_completed: ['technology_stack', 'language_rules', 'framework_rules', 'testing_rules', 'quality_rules', 'workflow_rules', 'critical_rules']
status: 'complete'
rule_count: 32
optimized_for_llm: true
---

# Project Context for AI Agents

_This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss._

---

## Technology Stack & Versions

### Backend
- **Language:** Java 17
- **Framework:** Spring Boot 3.5.11, Spring Cloud 2025.0.1
- **Persistence:** PostgreSQL, Flyway, Spring Data JPA, Hibernate 6
- **Tools:** Lombok, MapStruct 1.6.3
- **Security:** Spring Security 6, JJWT 0.13.0
- **Observability:** Micrometer Tracing (OTEL), OTLP exporter, Prometheus, Loki Logback
- **Testing:** JUnit 5, Testcontainers, Instancio, AssertJ, Mockito, WireMock

### Frontend
- **Framework:** Quasar 2.16.0 (Vue 3.5.22)
- **State Management:** Pinia 3.0.1
- **Routing:** Vue Router 4.x
- **API Client:** Axios 1.2.1
- **Build Tool:** Vite (via Quasar CLI)
- **Node Version:** v22.16.0

---

## Critical Implementation Rules

### Language-Specific Rules

- **Java Records:** All request and response DTOs **must** be implemented as Java `record` types.
- **MapStruct:** Use MapStruct for all Entity/DTO mappings. Mappers reside in `contract` or `service` packages.
- **Lombok:** Use `@Getter`, `@Setter`, and `@Slf4j` for entities and services.
- **Vue Composition API:** Use `<script setup>` for all Vue components.
- **Async/Await:** Use `async/await` for all frontend asynchronous operations; avoid `.then()`.
- **Centralized API:** All frontend API calls must be defined in `src/frontend/src/api/*.api.js`.

### Framework-Specific Rules

- **Resource Naming:** REST controllers must be suffixed with `Resource` and placed in an `api` package.
- **REST Conventions:** Use `@PatchMapping` for partial updates; return `204 No Content` for body-less success.
- **Security:** Every resource method **must** have a `@PreAuthorize` annotation using `SecurityConstants`. For file deletion (`DELETE /api/storage/**`), the service layer must also verify that the authenticated user owns the file via `fso.getCreatedBy()`.
- **Observability:** Use `@Observed(name = "...")` on resources to enable metrics.
- **Validation:** Use Jakarta Validation (`@NotBlank`, etc.) on all request DTO records.
- **I18n:** All user-facing text must be externalized via `vue-i18n`.

### Testing Rules

- **Integration Tests:** Use `@SpringBootTest` + `@Testcontainers`. Do not mock the database.
- **Data Generation:** Use **Instancio** for generating DTO and Entity test data.
- **Assertions:** Use **AssertJ** (`assertThat`) for all assertions.
- **Async Testing:** Use **Awaitility** for verifying asynchronous outcomes.

### Code Quality & Style Rules

- **Platform Structure:** Follow `com.softropic.skillars.platform.{module}.{layer}` package hierarchy.
- **Exception Handling:** Handle exceptions via `@RestControllerAdvice`; do not catch generic `Exception`.
- **Frontend Formatting:** **Prettier** is mandatory for all `.js`, `.vue`, `.scss`, and `.json` files.

### Development Workflow Rules

- **Database Migrations:** All schema changes must use Flyway scripts in `src/main/resources/db/migration`.
- **Auditing:** Use Hibernate Envers for entity auditing.

### Critical Don't-Miss Rules

- **No Direct Entity Exposure:** Never return JPA entities directly from a `@RestController`. Always use a `record` DTO and MapStruct.
- **Security First:** Never add a new REST endpoint without a corresponding `@PreAuthorize` check. Unprotected endpoints are a security violation.
- **Database Consistency:** Do not use `DDL` statements in Java code; use Flyway migrations.
- **Sensitive Data:** Never include raw secrets or API keys in logs or responses. Raw keys must be shown once and never stored.
- **Frontend State:** Use Pinia stores for shared state; avoid global variables.
- **Hardcoding:** Never hardcode URLs, environment names, or localized strings. Use properties or I18n.

---

## Architecture & Module Design (DDD)

This project follows a **Modular Monolith** architecture with a focus on **Domain-Driven Design (DDD)**. The codebase is strictly partitioned between business domains and technical infrastructure.

### 1. Root Package Roles

#### `com.softropic.skillars.platform` (Business Domain Layer)
This is the heart of the application. It contains the **Bounded Contexts** (Modules) of the system.
- **Goal:** Encapsulate business logic and domain rules.
- **Isolation:** Modules should communicate via well-defined contracts or events.
- **Examples:** `security`, `tenant`, `notification`, `admin`, `filestorage`.

#### `com.softropic.skillars.infrastructure` (Global Infrastructure Layer)
This is the technical foundation. It contains cross-cutting concerns that provide technical capabilities to the domain modules.
- **Goal:** Provide reusable, generic technical infrastructure — capabilities that any platform module could theoretically use.
- **Constraint:** Must remain **Business-Agnostic**. It must have no knowledge of specific business domains or `platform` modules.
- **Examples:** Global security filters, logging utilities, persistence extensions, feature toggles, blob storage provider adapters (`blobstore`).

### 2. Module Internal Structure
Every new module in `com.softropic.skillars.platform.{module}` must adhere to the following layer structure:

| Layer | Package | Responsibility |
| :--- | :--- | :--- |
| **Web** | `api` | REST Controllers (Resources), DTO mapping. |
| **Domain** | `service` | Business logic, domain services, orchestration, schedulers. |
| **Persistence**| `repo` | JPA Entities and Spring Data Repositories. |
| **Contract** | `contract` | Public API of the module: DTO records, Events, Exceptions. |
| **Config** | `config` | Spring `@Configuration` beans for the module. |

### 3. Implementation Guidelines

- **Module Creation:** When adding a new feature, first identify if it belongs to an existing Bounded Context or requires a new module under `platform`.
- **Infrastructure Consolidation:** If a technical component is needed by more than one platform module, move it from `platform.{module}` to the root `com.softropic.skillars.infrastructure`.
- **Dependency Flow:** Business modules (`platform`) depend on Infrastructure (`infrastructure`). Infrastructure MUST NOT depend on Business modules.
- **Communication:** Prefer **Domain Events** for cross-module communication to maintain loose coupling.
- **Schedulers belong to platform:** A `@Scheduled` service that drives domain lifecycle (e.g., deletion, outbox polling, async replication) belongs in `platform.{module}.service`, not infrastructure. Infrastructure is invoked by those schedulers, not the other way around.

### 4. Domain vs. Infrastructure Rule (The "Business-Agnostic" Boundary)

#### Infrastructure Layer (`com.softropic.skillars.infrastructure`)
**MUST be purely technical.** Ask: "Could a completely different business domain (e.g., `platform.reporting`) use this class as-is, without any modification?" If yes, it belongs in infrastructure. If no, it belongs in a platform module.

Infrastructure MUST NOT contain:
- Business rules or validation logic (e.g., "Max file size", "Allowed mime-types", quota checks)
- JPA entities or repositories that model domain state (e.g., `FileStorageObject`, `OutboxReplicationJob`, `StorageAccessEvent`)
- Domain-lifecycle schedulers (e.g., a deletion scheduler that processes domain entities)
- Domain-specific configuration properties (e.g., quotas, retention periods, presigned URL TTLs)
- Any `import com.softropic.skillars.platform.*` statement

#### Platform Layer (`com.softropic.skillars.platform`)
IS the only place where business logic resides. Platform modules own their persistence model (JPA entities and repositories live in `platform.{module}.repo`), their domain services, and their lifecycle schedulers.

If a technical service needs to enforce a business constraint, the constraint must be applied by a Domain Service in platform **before** delegating to the infrastructure layer.

#### The Provider Adapter Pattern (for infrastructure modules like `blobstore`)
When infrastructure provides a technology adapter (e.g., S3, local filesystem), the correct structure is:

- **`infrastructure.{adapter}`** contains only:
  - The port interface (e.g., `StorageService`)
  - Provider implementations (e.g., `S3StorageService`, `LocalFileSystemStorageService`)
  - Transport-layer types (e.g., `StorageObject`, `StorageObjectMetadata`)
  - Provider-level exceptions (e.g., `StorageProviderException`, `StorageObjectNotFoundException`)
  - Provider configuration properties (`BlobstoreProperties` — AWS credentials, bucket, endpoint, retry)
  - The `@Configuration` class that wires provider beans

- **`platform.{module}`** contains everything else: entities, repositories, domain services, schedulers, validation, quota logic, and any decorator of the provider interface that carries domain awareness (e.g., `ReplicatedStorageService` that also writes outbox jobs).

**Reference implementation:** `infrastructure.blobstore` (pure S3/local adapter) + `platform.filestorage` (all file domain logic).

### 5. Violation Checklist

Before committing any class to `infrastructure.*`, verify all of the following:
- [ ] The class contains zero business rules (no if-statements based on domain state like quotas or file types).
- [ ] The class has zero imports from `com.softropic.skillars.platform.*`.
- [ ] The class name contains no domain-specific terms (e.g., `FileStorage`, `Tenant`, `Quota`, `Validation`, `AccessEvent`).
- [ ] The class could be used by a second, unrelated platform module without modification.
- [ ] No JPA `@Entity` or Spring Data `Repository` lives here (persistence models belong in `platform.{module}.repo`).

If any check fails, the class belongs in a platform module.

---

## Usage Guidelines

**For AI Agents:**
- Read this file before implementing any code.
- Follow ALL rules exactly as documented.
- When in doubt, prefer the more restrictive option.
- Update this file if new patterns emerge.

**For Humans:**
- Keep this file lean and focused on agent needs.
- Update when the technology stack changes.
- Review quarterly for outdated rules.
- Remove rules that become obvious over time.

_Last Updated: 2026-05-27_

