# Security API Endpoints Documentation

This document describes the REST API endpoints for authentication, user registration, and password management. These endpoints are designed for integration with Vue.js 3 + Quasar 2 frontend applications.

## Base URL

All API calls should be made to your backend server. In development, this is typically `http://localhost:8080`.

## CORS Configuration

The backend accepts requests from the following origins in development:
- `http://localhost:8080`
- `https://localhost:9090`
- `http://localhost:5175`
- `http://localhost:9000`

**Important:** All requests that need to send/receive cookies must include:
```javascript
// Axios configuration
axios.defaults.withCredentials = true;

// Or per-request
axios.post('/authenticate', data, { withCredentials: true });
```

---

## 1. User Registration

### Register New Account

Creates a new user account and sends an activation email/SMS.

**Endpoint:** `POST /v1/account/register`

**Authentication:** Not required (public)

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "login": "johndoe",
  "password": "securePassword123",
  "firstName": "John",
  "lastName": "Doe",
  "email": "john.doe@example.com",
  "phone": {
    "phone": "+1234567890",
    "iso2Country": "US",
    "phoneType": "MOBILE",
    "provider": "Carrier"
  },
  "langKey": "en",
  "gender": "MALE",
  "dob": "1990-05-15",
  "otpEnabled": true
}
```

**Field Validations:**
| Field | Required | Constraints |
|-------|----------|-------------|
| login | Yes | 1-50 characters |
| password | Yes | 5-100 characters |
| firstName | Yes | Max 50 characters |
| lastName | Yes | Max 50 characters |
| email | Yes | Valid email, 5-100 characters |
| phone | No | Phone object |
| langKey | No | 2-5 characters (default: "en") |
| gender | No | MALE, FEMALE, OTHER |
| dob | No | Past date in yyyy-MM-dd format |
| otpEnabled | No | Boolean (enables 2FA) |

**Success Response (201 Created):**
```json
{
  "id": "abc123-help-code",
  "message": "user.creation.feedback",
  "description": "You will receive an email shortly with an activation key or else contact support with the help code",
  "data": {}
}
```

**Error Responses:**
- `400 Bad Request` - Validation errors
- `500 Internal Server Error` - Server error

**Vue.js/Quasar Example:**
```javascript
import { api } from 'src/boot/axios';

async function registerUser(userData) {
  try {
    const response = await api.post('/v1/account/register', userData);
    // Show success notification
    $q.notify({
      type: 'positive',
      message: 'Registration successful! Check your email for activation link.'
    });
    return response.data;
  } catch (error) {
    if (error.response?.status === 400) {
      // Handle validation errors
    }
    throw error;
  }
}
```

---

### Resend Activation Link

Resends the activation link if the user didn't receive it.

**Endpoint:** `POST /v1/account/regislink`

**Authentication:** Not required (public)

**Query Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| login | Yes | Username |
| password | Yes | User's password |

**Example Request:**
```
POST /v1/account/regislink?login=johndoe&password=securePassword123
```

**Success Response (200 OK):**
```json
{
  "id": "abc123-help-code",
  "message": "user.creation.feedback",
  "description": "You will receive an email shortly with an activation key",
  "data": {}
}
```

---

### Activate Account

Activates a user account using the activation key from email.

**Endpoint:** `POST /v1/account/activate`

**Authentication:** Not required (public)

**Query Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| key | Yes | Activation key from email |

**Example Request:**
```
POST /v1/account/activate?key=abc123xyz789
```

**Success Response (200 OK):**
```json
{
  "id": null,
  "message": "user.activation.success",
  "description": "Account has been activated",
  "data": {}
}
```

**Error Response (400 Bad Request):**
```json
{
  "id": "error-code",
  "message": "INVALID_ACTIVATION_KEY",
  "description": "The activation key is invalid or has already been used",
  "data": {}
}
```

**Notes:**
- Activation keys expire after **7 days**
- Each key can only be used once

---

## 2. Authentication (Login)

### Login - Standard Authentication

Authenticates a user and creates a JWT session.

**Endpoint:** `POST /authenticate`

**Authentication:** Not required (public)

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "id": "john.doe@example.com",
  "password": "securePassword123",
  "loginCode": 1
}
```

**Login Code Values:**
| Code | Description |
|------|-------------|
| 1 | Login with Email (default) |
| 2 | Login with Phone |
| 3 | Login with Username |

**Success Response - Without 2FA (200 OK):**
```json
{
  "id": "JWT_CREATED",
  "message": "jwt.created",
  "description": "JWT token has been created",
  "data": {}
}
```

**Success Response - With 2FA Enabled (200 OK):**
```json
{
  "id": "obfuscated-login-info-id",
  "message": "check.otp",
  "description": "Check your email for the otp",
  "data": {
    "loginInfoId": "obfuscated-login-info-id"
  }
}
```

**Cookies Set on Successful Login:**
| Cookie | HTTPOnly | Purpose | TTL |
|--------|----------|---------|-----|
| potc | Yes | JWT Token | 15 minutes |
| bcookie | Yes | Browser Client ID | Session |
| ION | Yes | JWT Session ID | Session |
| user | No | Display name (readable by JS) | 15 minutes |
| admin | No | Admin indicator (if applicable) | 15 minutes |
| rint | No | Session refresh countdown | 15 minutes |

**Error Responses:**
- `401 Unauthorized` - Invalid credentials
- `423 Locked` - Account locked due to too many failed attempts

**Vue.js/Quasar Example:**
```javascript
import { api } from 'src/boot/axios';
import { useCookies } from '@vueuse/integrations/useCookies';

async function login(email, password) {
  try {
    const response = await api.post('/authenticate', {
      id: email,
      password: password,
      loginCode: 1
    }, { withCredentials: true });

    if (response.data.message === 'check.otp') {
      // 2FA required - redirect to OTP page
      return {
        requires2FA: true,
        loginInfoId: response.data.data.loginInfoId
      };
    }

    // Login successful - cookies are automatically set
    const cookies = useCookies();
    const displayName = cookies.get('user');
    const isAdmin = cookies.get('admin') === 'true';

    return {
      requires2FA: false,
      displayName,
      isAdmin
    };
  } catch (error) {
    if (error.response?.status === 401) {
      throw new Error('Invalid email or password');
    }
    if (error.response?.status === 423) {
      throw new Error('Account locked. Please try again later.');
    }
    throw error;
  }
}
```

---

### Two-Factor Authentication (OTP Verification)

Completes login when 2FA is enabled.

**Endpoint:** `POST /otp`

**Authentication:** Not required (but requires valid 2FA cookie from login)

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "loginInfoId": "obfuscated-login-info-id",
  "otp": "123456"
}
```

**Success Response (200 OK):**
```json
{
  "id": "",
  "message": "login.success",
  "description": "Login was successful",
  "data": {}
}
```

**Error Response (401 Unauthorized):**
```json
{
  "id": "error-code",
  "message": "invalid.otp",
  "description": "The OTP code is invalid or expired",
  "data": {}
}
```

**Notes:**
- OTP codes expire after **30 minutes**
- The `loginInfoId` from the login response must be passed

**Vue.js/Quasar Example:**
```javascript
async function verifyOTP(loginInfoId, otpCode) {
  try {
    const response = await api.post('/otp', {
      loginInfoId: loginInfoId,
      otp: otpCode
    }, { withCredentials: true });

    // Login complete - cookies are set
    return true;
  } catch (error) {
    if (error.response?.status === 401) {
      throw new Error('Invalid or expired OTP code');
    }
    throw error;
  }
}
```

---

### Check Authentication Status

Checks if the current user is authenticated.

**Endpoint:** `GET /v1/account/authenticate`

**Authentication:** Not required (public)

**Success Response (200 OK):**
```
johndoe
```
Returns the username if authenticated, or empty string if not.

---

### Logout

Logs out the current user and clears all session cookies.

**Endpoint:** `POST /api/logout`

**Authentication:** Not required

**Success Response (200 OK):**
```json
{
  "status": "logged_out"
}
```

**Cookies Cleared:**
- `JSESSIONID`
- `potc` (JWT)
- `bcookie`
- `user`
- `admin`
- `ION`

**Vue.js/Quasar Example:**
```javascript
async function logout() {
  try {
    await api.post('/api/logout', {}, { withCredentials: true });
    // Redirect to login page
    router.push('/login');
  } catch (error) {
    // Even if logout fails, clear local state and redirect
    router.push('/login');
  }
}
```

---

## 3. Password Reset

### Request Password Reset

Initiates the password reset process by sending a reset link to the user's email.

**Endpoint:** `POST /v1/account/reset_password/init`

**Authentication:** Not required (public)

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "loginId": "john.doe@example.com",
  "dob": "1990-05-15",
  "currentEmail": "john.doe@example.com"
}
```

**Field Validations:**
| Field | Required | Description |
|-------|----------|-------------|
| loginId | Yes | Valid email address |
| dob | Yes | Date of birth (yyyy-MM-dd) |
| currentEmail | Yes | Current email on account |

**Success Response (202 Accepted):**
```json
{
  "id": "tracking-code",
  "message": "password.reset.emailed",
  "description": "Check your email for a link to reset your password",
  "data": {}
}
```

**Notes:**
- Password reset links expire after **24 hours**
- For security, the same response is returned even if the email doesn't exist

**Vue.js/Quasar Example:**
```javascript
async function requestPasswordReset(email, dob) {
  try {
    const response = await api.post('/v1/account/reset_password/init', {
      loginId: email,
      dob: dob,
      currentEmail: email
    });

    $q.notify({
      type: 'positive',
      message: 'Password reset link sent! Check your email.'
    });

    return response.data;
  } catch (error) {
    throw error;
  }
}
```

---

### Complete Password Reset

Resets the password using the token from email.

**Endpoint:** `POST /v1/account/reset_password/finish`

**Authentication:** Not required (public)

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "key": "reset-token-from-email",
  "password": "newSecurePassword123"
}
```

**Field Validations:**
| Field | Required | Constraints |
|-------|----------|-------------|
| key | Yes | 2-25 characters (token from email) |
| password | Yes | 5-50 characters |

**Success Response (200 OK):**
```json
{
  "id": null,
  "message": "password.reset.success",
  "description": "Your password has now been reset. You can now login with your new password.",
  "data": {}
}
```

**Error Response (400 Bad Request):**
```json
{
  "id": "error-code",
  "message": "INVALID_RESET_KEY",
  "description": "The password reset key is invalid or has expired",
  "data": {}
}
```

**Vue.js/Quasar Example:**
```javascript
async function resetPassword(token, newPassword) {
  try {
    const response = await api.post('/v1/account/reset_password/finish', {
      key: token,
      password: newPassword
    });

    $q.notify({
      type: 'positive',
      message: 'Password reset successful! You can now login.'
    });

    // Redirect to login
    router.push('/login');

    return response.data;
  } catch (error) {
    if (error.response?.status === 400) {
      throw new Error('Invalid or expired reset link');
    }
    throw error;
  }
}
```

---

## 4. Account Management (Authenticated)

### Get Current User Account

Retrieves the current authenticated user's account details.

**Endpoint:** `GET /v1/account/`

**Authentication:** Required (JWT)

**Success Response (200 OK):**
```json
{
  "id": "user-id",
  "login": "johndoe",
  "firstName": "John",
  "lastName": "Doe",
  "email": "john.doe@example.com",
  "phone": {
    "phone": "+1234567890",
    "iso2Country": "US",
    "phoneType": "MOBILE"
  },
  "langKey": "en",
  "gender": "MALE",
  "dob": "1990-05-15",
  "authorities": ["ROLE_USER"],
  "activated": true,
  "otpEnabled": true
}
```

**Error Response (401 Unauthorized):**
Session expired or not authenticated.

---

### Change Email Address

Changes the user's email address.

**Endpoint:** `POST /v1/account/change_email`

**Authentication:** Required (JWT)

**Headers:**
```
Content-Type: application/json
```

**Request Body:**
```json
{
  "oldEmail": "old.email@example.com",
  "newEmail": "new.email@example.com",
  "password": "currentPassword123"
}
```

**Field Validations:**
| Field | Required | Constraints |
|-------|----------|-------------|
| oldEmail | Yes | Valid email, max 100 characters |
| newEmail | Yes | Valid email, max 100 characters |
| password | Yes | 4-60 characters |

**Success Response (200 OK):**
```json
{
  "id": "tracking-code",
  "message": "email.updated",
  "description": "Your email address has been updated",
  "data": {}
}
```

---

## 5. Health Check

### Ping

Simple health check endpoint.

**Endpoint:** `GET /v1/account/ping`

**Authentication:** Not required (public)

**Success Response (200 OK):**
```json
{
  "server": "up"
}
```

---

## Error Response Format

All error responses follow this format:

```json
{
  "id": "unique-error-id",
  "message": "error.code",
  "description": "Human-readable error description",
  "data": {}
}
```

## Rate Limiting

The API implements rate limiting to prevent brute-force attacks:

| Level | Trigger | Lockout Duration |
|-------|---------|------------------|
| Client + Username | 3 failed attempts | 4 hours |
| IP + Username | 3 failed attempts | 4 hours |
| Username Only | 5 failed attempts | 4 hours |

After exceeding the limit, you'll receive a `423 Locked` response.

---

## Quick Reference

| Feature | Endpoint | Method | Auth Required |
|---------|----------|--------|---------------|
| Register | `/v1/account/register` | POST | No |
| Resend Activation | `/v1/account/regislink` | POST | No |
| Activate Account | `/v1/account/activate` | POST | No |
| Login | `/authenticate` | POST | No |
| 2FA/OTP | `/otp` | POST | No* |
| Check Auth | `/v1/account/authenticate` | GET | No |
| Logout | `/api/logout` | POST | No |
| Password Reset Request | `/v1/account/reset_password/init` | POST | No |
| Password Reset Complete | `/v1/account/reset_password/finish` | POST | No |
| Get Account | `/v1/account/` | GET | Yes |
| Change Email | `/v1/account/change_email` | POST | Yes |
| Session Refresh | `/refresh` | ANY | Yes |
| Ping | `/v1/account/ping` | GET | No |

*Requires valid 2FA cookie from login response
