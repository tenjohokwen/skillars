# 🚀 Spring Boot 3.5.x Production Logging Standard

## LGTM Stack (Grafana + Loki + Tempo + Prometheus)

This document defines the **official logging and observability standard** for all Spring Boot applications.

It ensures:

* Structured, queryable logs in **Grafana**
* Log aggregation in **Grafana Loki**
* Trace correlation via **Grafana Tempo**
* Metrics collection using **Prometheus**
* Full-stack observability with correlation across logs, traces, and metrics

This is the mandatory production standard for all microservices.

---

# 1️⃣ Core Principles

1. Logs **must be structured JSON**
2. Field names **must be consistent across all services**
3. Never rely on parsing free text
4. Always include correlation IDs (traceId/spanId)
5. Optimize for Loki queries
6. Follow Google SRE **Golden Signals**
7. Avoid high-cardinality Loki labels

---

# 2️⃣ Standard Logging Stack (Spring Boot)

### ✅ Required Components

| Area         | Standard                 |
| ------------ | ------------------------ |
| Logging API  | SLF4J                    |
| Logger       | Logback                  |
| Format       | JSON                     |
| JSON Encoder | logstash-logback-encoder |
| Tracing      | OpenTelemetry            |
| Correlation  | traceId + spanId         |
| Transport    | stdout                   |

Spring Boot already includes:

* `spring-boot-starter-logging`
* SLF4J
* Logback

---

# 3️⃣ Required Dependencies

### JSON Logging

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### OpenTelemetry (Trace Injection)

```xml
<dependency>
  <groupId>io.opentelemetry.instrumentation</groupId>
  <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

This automatically injects:

* `traceId`
* `spanId`

into MDC so they appear in logs.

---

# 4️⃣ Logback Configuration (logback-spring.xml)

```xml
<configuration>

  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">

          <providers>
              <timestamp/>
              <logLevel/>
              <threadName/>
              <loggerName/>
              <message/>
              <mdc/>
              <arguments/>
              <stackTrace/>

              <pattern>
                  <pattern>
                      {
                        "service":"${spring.application.name}",
                        "environment":"${ENVIRONMENT:-dev}",
                        "version":"${APP_VERSION:-unknown}"
                      }
                  </pattern>
              </pattern>

          </providers>

      </encoder>
  </appender>

  <root level="INFO">
      <appender-ref ref="JSON"/>
  </root>

</configuration>
```

### Why stdout?

Logs must go to stdout because:

Spring Boot
↓
JSON logs (stdout)
↓
Grafana Alloy / Promtail
↓
Loki
↓
Grafana

Ideal for Docker & Kubernetes.

---

# 5️⃣ Production Log Schema (Standardized Fields)

All services must log the following grouped fields.

---

## 5.1 Core Log Metadata

| Field     | Description     |
| --------- | --------------- |
| timestamp | ISO-8601 UTC    |
| level     | INFO/WARN/ERROR |
| message   | Human readable  |
| logger    | Logger class    |
| thread    | Thread name     |

---

## 5.2 Service Identity

| Field       | Description      |
| ----------- | ---------------- |
| service     | Service name     |
| environment | prod/stage/dev   |
| version     | App version      |
| instanceId  | Pod/container ID |
| host        | Node name        |

---

## 5.3 Distributed Tracing (Critical)

| Field        |
| ------------ |
| traceId      |
| spanId       |
| parentSpanId |

This links:

Loki ↔ Tempo
Logs ↔ Traces

Most important debugging feature.

---

## 5.4 Request Context

| Field     |
| --------- |
| requestId |
| method    |
| path      |
| clientIp  |
| userAgent |

Allows request reconstruction even if tracing fails.

---

## 5.5 Business Context (Domain Identifiers)

| Field        |
| ------------ |
| operation    |
| userId       |
| resourceType |
| resourceId   |

Example:

```json
{
  "operation": "create_order",
  "userId": 1821,
  "resourceType": "order",
  "resourceId": 8121
}
```

Loki Queries:

```
| json | resourceId=8121
| json | userId=1821
```

---

## 5.6 Golden Signals Fields

Based on Google SRE:

| Field      | Signal  |
| ---------- | ------- |
| operation  | Traffic |
| durationMs | Latency |
| status     | Errors  |
| errorCode  | Errors  |
| retryCount | Errors  |

### Success Example

```json
{
  "operation": "process_payment",
  "durationMs": 231,
  "status": "SUCCESS"
}
```

### Failure Example

```json
{
  "operation": "process_payment",
  "durationMs": 3000,
  "status": "ERROR",
  "errorCode": "PAYMENT_TIMEOUT"
}
```

---

## 5.7 Infrastructure & Saturation

| Field             |
| ----------------- |
| dbQueryTimeMs     |
| externalService   |
| externalLatencyMs |
| queueSize         |
| dbPoolActive      |
| dbPoolMax         |
| executorQueueSize |

Example:

```json
{
  "externalService": "payment-gateway",
  "externalLatencyMs": 812
}
```

---

# 6️⃣ Structured Logging Rules

## ❌ Never Do This

```java
log.info("User {} reserved ticket {}", userId, ticketId);
```

## ✅ Always Use Structured Arguments

```java
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Ticket reserved",
        kv("operation", "reserve_ticket"),
        kv("userId", userId),
        kv("resourceType", "ticket"),
        kv("resourceId", ticketId),
        kv("durationMs", duration),
        kv("status", "SUCCESS"));
```

Produces:

```json
{
  "message": "Ticket reserved",
  "operation": "reserve_ticket",
  "userId": 21,
  "resourceType": "ticket",
  "resourceId": 91,
  "durationMs": 120,
  "status": "SUCCESS"
}
```

Fully queryable in Loki.

---

# 7️⃣ Request Lifecycle Logging Pattern

Each request must log:

### 1️⃣ Request Start

```json
{
  "event": "request_start",
  "operation": "create_order"
}
```

### 2️⃣ Request End

```json
{
  "event": "request_end",
  "operation": "create_order",
  "durationMs": 182,
  "status": "SUCCESS"
}
```

### 3️⃣ Request Error (if failure)

```json
{
  "event": "request_error",
  "operation": "create_order",
  "errorCode": "PAYMENT_TIMEOUT",
  "status": "ERROR"
}
```

This makes latency & failure analysis trivial.

---

# 8️⃣ MDC Usage (Request Context)

Use MDC for contextual values:

```java
MDC.put("requestId", requestId);
MDC.put("userId", userId);
```

All logs in that request automatically include them.

---

# 9️⃣ Log Level Standard

| Level | Use Case           |
| ----- | ------------------ |
| ERROR | System failures    |
| WARN  | Recoverable issues |
| INFO  | Business events    |
| DEBUG | Debugging          |
| TRACE | Deep diagnostics   |

### Log Business Events — Not Code Flow

✅ Good:

* OrderCreated
* PaymentAuthorized
* TicketReserved
* UserLoginFailed

❌ Bad:

* Entering method
* Leaving repository
* Processing step 1

---

# 🔟 Sensitive Data Protection

Never log:

* Passwords
* Tokens
* Credit cards
* Personal data

❌

```java
log.info("Login {}", password);
```

Zero tolerance rule.

---

# 1️⃣1️⃣ Loki Label Strategy (Avoid Cardinality Explosion)

### Recommended Loki Labels

* service
* environment
* level

### Keep as JSON Fields (Not Labels)

* traceId
* userId
* resourceId
* operation
* version
* durationMs
* errorCode
* requestId

This prevents index explosion.

---

# 1️⃣2️⃣ Full Example Production Log

```json
{
  "timestamp": "2026-03-14T10:33:21Z",
  "level": "INFO",
  "message": "Order created",
  "logger": "OrderService",
  "thread": "http-nio-8080",

  "service": "order-service",
  "environment": "prod",
  "version": "1.8.3",
  "instanceId": "order-service-7a82",
  "host": "node-3",

  "traceId": "7d3fa91",
  "spanId": "ab21f1",

  "requestId": "req-92a1",
  "method": "POST",
  "path": "/orders",

  "operation": "create_order",
  "userId": 1821,
  "resourceType": "order",
  "resourceId": 8121,

  "durationMs": 213,
  "status": "SUCCESS"
}
```

~25 standardized fields.

---

# 1️⃣3️⃣ Architecture Overview

Spring Boot
↓
JSON Logs (stdout)
↓
Grafana Alloy / Promtail
↓
Grafana Loki
↓
Grafana

Tracing:

Spring Boot
↓
OpenTelemetry
↓
Grafana Tempo
↓
Grafana

Metrics:

Spring Boot (Micrometer)
↓
Prometheus
↓
Grafana

---

# ✅ Final Standard Summary

All Spring Boot services must:

* Use SLF4J + Logback
* Output structured JSON logs
* Use logstash-logback-encoder
* Inject traceId/spanId via OpenTelemetry
* Log business events (not code flow)
* Follow the standardized schema (~25 fields)
* Implement request lifecycle logging
* Include Golden Signals fields
* Avoid sensitive data
* Prevent Loki cardinality explosion

---

# 🎯 Result

When implemented consistently across microservices:

* Cross-service debugging becomes dramatically faster
* Traces ↔ logs correlation is seamless
* Production incidents resolve quicker
* Observability maturity significantly increases

This is the official logging baseline for all Spring Boot applications.
