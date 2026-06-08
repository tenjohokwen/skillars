# OWASP Top 10 2021 Violations Report for Skillars

This document outlines potential OWASP Top 10 2021 violations found in the Skillars application.

## A06:2021 - Vulnerable and Outdated Components

Several dependencies with known vulnerabilities are being used in the project.

### Backend (`pom.xml`)

*   **`org.springframework.boot:spring-boot-starter-parent:3.5.7`**:
    *   **CVE-2024-38807 (Medium)**: A signature forgery vulnerability exists in Spring Boot versions prior to 3.6.0. This could allow an attacker to forge signatures and execute unauthorized content. It is recommended to upgrade to a newer version of Spring Boot.
*   **`io.jsonwebtoken:jjwt-*:0.12.6`**:
    *   While there are no direct vulnerabilities for this version, a later version (0.12.7) fixed an issue with empty custom claims (CVE-2023-5072). This could potentially lead to unexpected behavior in token validation.

### Frontend (`src/frontend/package.json`)

*   **`axios:^1.2.1`**:
    *   **SSRF (Server-Side Request Forgery)**: Axios 1.2.1 is vulnerable to SSRF.
    *   **DoS (Denial of Service)**: The version is vulnerable to a DoS attack due to resource allocation without limits.
    *   **ReDoS (Regular Expression Denial of Service)**: The version is vulnerable to a ReDoS attack.
    *   **CSRF (Cross-Site Request Forgery)**: The version is vulnerable to CSRF.
    *   It is highly recommended to upgrade `axios` to a newer version.

## A05:2021 - Security Misconfiguration

The `application.yaml` and `application-dev.yaml` files contain several security misconfigurations.

*   **Hardcoded Secrets**: The file contains multiple hardcoded secrets in plaintext.
    *   `spring.mail.password`: `7QYDI33TVFD3OGXOJFFL`
    *   `email.providerConfigs`: Passwords for `gmx` and `gmail` are hardcoded.
    *   `client.momo.headers.Ocp-Apim-Subscription-Key`: `6534e66d51c549f6860326cea5188b57`
*   **Actuator Endpoints Exposed**: `management.endpoints.web.exposure.include: "*"` exposes all actuator endpoints. While some endpoints might be protected by Spring Security, this is a very broad configuration and could expose sensitive information.
*   **Permissive CORS Configuration**: The CORS configuration is very permissive, allowing all methods and headers from multiple origins. This could be a security risk if the application is deployed to a production environment with this configuration.
*   **JMX Enabled**: `spring.jmx.enabled: true` can expose management beans and operations, which could be a security risk if not properly secured.

## A02:2021 - Cryptographic Failures

*   **Plaintext Secrets**: The hardcoded secrets in `application.yaml` are a clear instance of cryptographic failure. These secrets should be encrypted or loaded from a secure source.

## A03:2021 - Injection

Further investigation is needed to identify injection vulnerabilities. A review of the frontend and backend code is recommended to ensure that user input is properly sanitized and that prepared statements are used for all database queries.

## A01:2021 - Broken Access Control

Multiple controllers expose sensitive endpoints without any authorization checks. This means that any user, authenticated or not, can access these endpoints.

### `AccountResource.java`

This controller, mapped under `/v1/account`, has no authorization checks on its methods. Sensitive endpoints include:
*   `/register`: Allows user registration.
*   `/regislink`: Resends a registration link.
*   `/activate`: Activates a user account.
*   `/authenticate`: Checks if a user is authenticated.
*   `/`: Retrieves the current user's account details.
*   `/reset_password/init`: Initiates a password reset.
*   `/reset_password/finish`: Finishes a password reset.
*   `/change_email`: Changes a user's email address.

### `ProfileResource.java`

This controller, mapped under `/api/account`, also has no authorization checks on its methods. Sensitive endpoints include:
*   `/profile`: Retrieves the current user's profile.
*   `/email`: Updates the current user's email.
*   `/password`: Changes the current user's password.
*   `/phone`: Updates the current user's phone number.
*   `/address`: Updates the current user's address.
*   `/info`: Updates the current user's core information.
*   `/2fa`: Toggles two-factor authentication.
