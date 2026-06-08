# Frontend Security Features Implementation Specification

This document provides specifications for implementing authentication and security features in a Vue.js 3 + Quasar 2 frontend application. Use this alongside implementation prompts.

---

## Table of Contents

1. [Code Standards](#1-code-standards)
2. [Architecture](#2-architecture)
3. [File Structure](#3-file-structure)
4. [API Endpoints Reference](#4-api-endpoints-reference)
5. [Cross-Cutting Concerns](#5-cross-cutting-concerns)
6. [API Services](#6-api-services)
7. [Error Handling](#7-error-handling)
8. [Internationalization](#8-internationalization)
9. [Page Components](#9-page-components)
10. [Router Configuration](#10-router-configuration)

---

## 1. Code Standards

All code MUST follow these standards:

| Standard | Requirement |
|----------|-------------|
| Vue API | `<script setup>` only. No Options API. |
| Language | Plain JavaScript. No TypeScript. |
| Styling | Primary color `#1976d2`. Mobile-first responsive. |
| Forms | Use `lazy-rules` for validation on blur. |
| Buttons | Use `:loading` and `:disable` on submit buttons. |
| Cleanup | Use `onUnmounted` for timers/listeners. |
| i18n | All user-facing text via `$t()` or `t()`. |
| Components | Under 250 lines. Single responsibility. |

### Component Template

```vue
<template>
  <!-- Mobile-first: col-12 col-sm-8 col-md-6 col-lg-4 -->
</template>

<script setup>
import { ref, computed, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useQuasar } from 'quasar';
import { useI18n } from 'vue-i18n';

const router = useRouter();
const $q = useQuasar();
const { t } = useI18n();

// Cleanup
onUnmounted(() => {
  // Clear timers, listeners, pending requests
});
</script>
```

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Vue Components                           │
│        (Focus on UI only - no session/error logic)          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Composables Layer                         │
│      useSession, useErrorHandler, useLoading                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   API Services Layer                         │
│         authApi, accountApi, sessionApi                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Axios Instance + Interceptors                   │
│                                                              │
│   CROSS-CUTTING CONCERNS (Transparent to components):       │
│   • Session management (auto-refresh, expiry detection)     │
│   • Error handling (parse ErrorDto, show notifications)     │
│   • Loading state (track pending requests)                  │
│   • Auto-logout on 401                                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                      [ Backend API ]
```

**Key Principle**: Components make API calls without knowing about session management, error handling, or loading states. Interceptors handle everything transparently.

---

## 3. File Structure

Create these files:

```
src/
├── api/
│   ├── index.js              # Re-exports all APIs
│   ├── auth.api.js           # Login, logout, OTP
│   ├── account.api.js        # Register, activate, password reset
│   └── session.api.js        # Session refresh
├── boot/
│   ├── axios.js              # Axios instance + interceptors
│   └── i18n.js               # i18n configuration
├── composables/
│   ├── useSession.js         # Session state and actions
│   ├── useErrorHandler.js    # Error state for forms
│   └── useLoading.js         # Global loading state
├── components/
│   └── common/
│       ├── SessionWarningDialog.vue
│       └── GlobalLoadingBar.vue
├── i18n/
│   ├── en-US/
│   │   └── index.js
│   └── fr-FR/
│       └── index.js
├── pages/
│   └── auth/
│       ├── LoginPage.vue
│       ├── RegisterPage.vue
│       ├── OtpPage.vue
│       ├── ForgotPasswordPage.vue
│       ├── ResetPasswordPage.vue
│       └── ActivatePage.vue
├── plugins/
│   └── sessionManager.js     # Session timer logic
├── router/
│   ├── index.js              # Router with guards
│   └── routes.js             # Route definitions
├── utils/
│   └── errorHandler.js       # Error parsing utilities
└── App.vue                   # Include global components
```

---

## 4. API Endpoints Reference

### Authentication

| Action | Method | Endpoint | Auth | Request Body |
|--------|--------|----------|------|--------------|
| Login | POST | `/authenticate` | No | `{ id, password, loginCode }` |
| Verify OTP | POST | `/otp` | No* | `{ loginInfoId, otp }` |
| Logout | POST | `/api/logout` | No | - |
| Check Auth | GET | `/v1/account/authenticate` | No | - |
| Refresh Session | GET | `/refresh` | Yes | - |

*Requires 2FA cookie from login

### Account Management

| Action | Method | Endpoint | Auth | Request Body |
|--------|--------|----------|------|--------------|
| Register | POST | `/v1/account/register` | No | See below |
| Resend Activation | POST | `/v1/account/regislink?login=X&password=Y` | No | - |
| Activate | POST | `/v1/account/activate?key=X` | No | - |
| Password Reset Init | POST | `/v1/account/reset_password/init` | No | `{ loginId, dob, currentEmail }` |
| Password Reset Finish | POST | `/v1/account/reset_password/finish` | No | `{ key, password }` |
| Get Account | GET | `/v1/account/` | Yes | - |

### Registration Request Body

```json
{
  "login": "string (1-50 chars, required)",
  "password": "string (5-100 chars, required)",
  "firstName": "string (max 50, required)",
  "lastName": "string (max 50, required)",
  "email": "string (valid email, 5-100 chars, required)",
  "phone": {
    "phone": "+1234567890",
    "iso2Country": "US",
    "phoneType": "MOBILE",
    "provider": "Carrier"
  },
  "langKey": "en (2-5 chars, default: en)",
  "gender": "MALE | FEMALE | OTHER",
  "dob": "yyyy-MM-dd (past date)",
  "otpEnabled": "boolean"
}
```

### Login Codes

| Code | Description |
|------|-------------|
| 1 | Login with Email (default) |
| 2 | Login with Phone |
| 3 | Login with Username |

### Response Formats

**Success Response:**
```json
{
  "id": "help-code-or-id",
  "message": "message.key",
  "description": "Human readable message",
  "data": {}
}
```

**Login Success (No 2FA):**
```json
{
  "id": "JWT_CREATED",
  "message": "jwt.created",
  "description": "JWT token has been created",
  "data": {}
}
```

**Login Success (2FA Required):**
```json
{
  "id": "obfuscated-id",
  "message": "check.otp",
  "description": "Check your email for the otp",
  "data": { "loginInfoId": "obfuscated-id" }
}
```

### Cookies Set on Login

| Cookie | HTTPOnly | Purpose | JS Access |
|--------|----------|---------|-----------|
| `potc` | Yes | JWT Token | No |
| `user` | No | Display name | Yes |
| `admin` | No | Admin flag | Yes |
| `rint` | No | Session countdown | Yes |

---

## 5. Cross-Cutting Concerns

### 5.1 Session Manager Plugin

**File:** `src/plugins/sessionManager.js`

**Requirements:**
- Track last activity time
- Show warning 2 minutes before 15-minute session expires
- Check session status every 30 seconds
- Provide reactive state: `showWarning`, `timeUntilExpiry`, `isRefreshing`
- Export functions: `recordActivity()`, `startSessionMonitoring()`, `stopSessionMonitoring()`, `refreshSession()`, `cleanup()`
- Dispatch `session:expired` event when session expires

```javascript
// Constants
const SESSION_WARNING_THRESHOLD = 2 * 60 * 1000; // 2 minutes
const SESSION_CHECK_INTERVAL = 30 * 1000;        // 30 seconds
const SESSION_TTL = 15 * 60 * 1000;              // 15 minutes
```

### 5.2 Axios Interceptors

**File:** `src/boot/axios.js`

**Request Interceptor Must:**
1. Increment pending request counter
2. Notify loading state change
3. Call `recordActivity()` (skip for `/refresh` endpoint)

**Response Interceptor Must:**
1. Decrement pending request counter
2. Notify loading state change
3. On 401 with `security.sessionExpired` or `security.unauthorized`:
   - Stop session monitoring
   - Cleanup session
   - Redirect to `/login?redirect=CURRENT_PATH&expired=true`
4. On 403: Show notification with error message
5. On 5xx: Show notification with help code
6. On network error: Show network error notification
7. Always reject with original error for component handling

**Export:**
- `api` - Axios instance with `withCredentials: true`
- `onLoadingChange(callback)` - Subscribe to loading state

### 5.3 Session Composable

**File:** `src/composables/useSession.js`

**Returns:**
```javascript
{
  showWarning,      // computed - show warning dialog
  timeUntilExpiry,  // computed - ms until expiry
  minutesRemaining, // computed - minutes until expiry
  isRefreshing,     // computed - refresh in progress
  handleRefresh,    // async function - refresh session
  handleLogout,     // async function - logout and redirect
  initSession,      // function - start monitoring
  destroySession    // function - stop monitoring
}
```

### 5.4 Error Handler Composable

**File:** `src/composables/useErrorHandler.js`

**Returns:**
```javascript
{
  error,            // readonly ref - full error object
  fieldErrors,      // readonly ref - { fieldName: message }
  hasError,         // computed - boolean
  hasFieldErrors,   // computed - true if any field-specific errors exist
  errorMessage,     // computed - main error message
  errorKey,         // computed - error key for i18n
  helpCode,         // computed - support help code
  isValidationError,// computed - is field validation error
  setError,         // function(err) - parse and set error
  clearError,       // function - clear all errors
  hasFieldError,    // function(fieldName) - boolean
  getFieldError     // function(fieldName) - message or null
}
```

### 5.5 Loading Composable

**File:** `src/composables/useLoading.js`

**Returns:**
```javascript
{
  isLoading  // readonly ref - true if any request pending
}
```

Subscribe to `onLoadingChange` in `onMounted`, unsubscribe in `onUnmounted`.

### 5.6 Global Components

**SessionWarningDialog.vue:**
- Shows when `showWarning` is true
- Displays minutes remaining with progress bar
- "Continue Session" button calls `handleRefresh()`
- "Logout" button calls `handleLogout()`
- Use `:loading` and `:disable` on buttons

**GlobalLoadingBar.vue:**
- Fixed position at top of viewport
- Shows `q-linear-progress` when `isLoading` is true
- z-index: 9999

**App.vue Must Include:**
```vue
<template>
  <GlobalLoadingBar />
  <SessionWarningDialog />
  <router-view />
</template>
```

---

## 6. API Services

### 6.1 Auth API

**File:** `src/api/auth.api.js`

```javascript
import { api } from 'src/boot/axios';

export const authApi = {
  login(id, password, loginCode = 1) {
    return api.post('/authenticate', { id, password, loginCode });
  },

  verifyOtp(loginInfoId, otp) {
    return api.post('/otp', { loginInfoId, otp });
  },

  logout() {
    return api.post('/api/logout');
  },

  checkAuth() {
    return api.get('/v1/account/authenticate');
  }
};
```

### 6.2 Account API

**File:** `src/api/account.api.js`

```javascript
import { api } from 'src/boot/axios';

export const accountApi = {
  register(userData) {
    return api.post('/v1/account/register', userData);
  },

  resendActivation(login, password) {
    return api.post('/v1/account/regislink', null, {
      params: { login, password }
    });
  },

  activate(key) {
    return api.post('/v1/account/activate', null, {
      params: { key }
    });
  },

  requestPasswordReset(loginId, dob, currentEmail) {
    return api.post('/v1/account/reset_password/init', {
      loginId, dob, currentEmail
    });
  },

  resetPassword(key, password) {
    return api.post('/v1/account/reset_password/finish', {
      key, password
    });
  },

  getAccount() {
    return api.get('/v1/account/');
  }
};
```

### 6.3 Session API

**File:** `src/api/session.api.js`

```javascript
import { api } from 'src/boot/axios';

export const sessionApi = {
  refresh() {
    return api.get('/refresh');
  }
};
```

### 6.4 Index Re-export

**File:** `src/api/index.js`

```javascript
export { authApi } from './auth.api';
export { accountApi } from './account.api';
export { sessionApi } from './session.api';
```

---

## 7. Error Handling

### 7.1 Backend Error Structure (ErrorDto)

```json
{
  "helpCode": "abc123xyz",
  "errorMsg": {
    "errorKey": "validation.invalidData",
    "message": "Invalid Data"
  },
  "fieldErrors": [
    {
      "objectName": "registrationDto",
      "field": "email",
      "errorMsg": {
        "errorKey": "invalid.email",
        "message": "Must be a valid email address"
      }
    }
  ]
}
```

### 7.2 Error Translation Flow

The frontend uses the `errorKey` from the backend response to display translated error messages:

1. **Backend returns error** with `errorMsg.errorKey` (e.g., `security.opForbidden`)
2. **Error parser** extracts the `errorKey` from the response
3. **useErrorHandler composable** checks if a translation exists for the key using `te(errorKey)`
4. **If translation exists**: Display the translated message from i18n
5. **If no translation**: Fall back to `errorMsg.message` from the server

```
Backend Response                    Frontend Display
─────────────────                   ────────────────
{
  "errorMsg": {                     ┌─────────────────────────────┐
    "errorKey": "security.opForbidden",  │ 1. Check: te('security.opForbidden') │
    "message": "The operation..."   │ 2. Found? → t('security.opForbidden') │
  }                                 │ 3. Not found? → Use raw message  │
}                                   └─────────────────────────────┘
```

### 7.2.1 Consistent Error Display Pattern

All pages MUST use a consistent error display pattern to ensure uniform user experience across the application. This pattern handles both general errors (API failures, server errors) and field-level validation errors.

#### Standard Error Banner Component

Use this exact pattern for displaying errors at the top of forms or page content:

```vue
<q-banner
  v-if="hasError && (!isValidationError || !hasFieldErrors)"
  class="bg-negative text-white q-mb-md"
  rounded
>
  {{ errorMessage }}  <!-- Automatically translated via errorKey -->
  <template v-if="helpCode">
    <br />
    <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
  </template>
</q-banner>
```

**Key attributes explained:**

| Attribute | Purpose |
|-----------|---------|
| `v-if="hasError && (!isValidationError || !hasFieldErrors)"` | Show for non-validation errors OR validation errors without field-specific details. This ensures users always see an error message even when the server returns a validation error without field mappings. |
| `class="bg-negative text-white"` | Quasar's negative color (red by default) with white text for high visibility |
| `class="q-mb-md"` | Consistent margin-bottom spacing before form content |
| `rounded` | Rounded corners matching Quasar's design language |
| `{{ errorMessage }}` | Pre-translated message from useErrorHandler (automatically uses i18n) |
| `{{ helpCode }}` | Support reference code for troubleshooting - only shown when available |

#### Implementation Checklist for New Pages

When creating a new page that makes API calls, follow these steps:

**Step 1: Import and Initialize useErrorHandler**

```vue
<script setup>
import { useErrorHandler } from 'src/composables/useErrorHandler';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
const {
  setError,
  clearError,
  hasError,
  hasFieldErrors,
  errorMessage,
  errorKey,
  helpCode,
  isValidationError,
  hasFieldError,
  getFieldError
} = useErrorHandler();
</script>
```

**Step 2: Add Error Banner to Template**

Place the error banner immediately before your form or main content area:

```vue
<template>
  <q-page class="flex flex-center">
    <q-card class="q-pa-lg" style="width: 100%; max-width: 400px;">
      <q-card-section>
        <div class="text-h5 text-center q-mb-md">{{ t('page.title') }}</div>

        <!-- ERROR BANNER - Place before form -->
        <q-banner
          v-if="hasError && (!isValidationError || !hasFieldErrors)"
          class="bg-negative text-white q-mb-md"
          rounded
        >
          {{ errorMessage }}
          <template v-if="helpCode">
            <br />
            <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>

        <!-- FORM CONTENT -->
        <q-form @submit="handleSubmit">
          <!-- Form fields here -->
        </q-form>
      </q-card-section>
    </q-card>
  </q-page>
</template>
```

**Step 3: Handle Errors in Submit Function**

```javascript
async function handleSubmit() {
  clearError();  // Always clear previous errors before new submission
  loading.value = true;

  try {
    const response = await someApi.action(formData);
    // Handle success
  } catch (err) {
    setError(err);  // Parse and set error - translation happens automatically
  } finally {
    loading.value = false;
  }
}
```

**Step 4: Handle Field-Level Validation Errors**

For forms with multiple fields, use `hasFieldError()` and `getFieldError()` to display inline validation messages:

```vue
<q-input
  v-model="form.email"
  :label="t('auth.email')"
  type="email"
  :error="hasFieldError('email')"
  :error-message="getFieldError('email')"
  lazy-rules
  :rules="[
    val => !!val || t('validation.required'),
    val => isValidEmail(val) || t('validation.email')
  ]"
/>

<q-input
  v-model="form.password"
  :label="t('auth.password')"
  type="password"
  :error="hasFieldError('password')"
  :error-message="getFieldError('password')"
  lazy-rules
  :rules="[val => !!val || t('validation.required')]"
/>
```

**Field error props explained:**

| Prop | Source | Purpose |
|------|--------|---------|
| `:error="hasFieldError('fieldName')"` | useErrorHandler | Sets input to error state when backend returns validation error for this field |
| `:error-message="getFieldError('fieldName')"` | useErrorHandler | Displays the server-provided error message below the field |
| `:rules="[...]"` | Local validation | Client-side validation rules (runs before submission) |

#### Error Type Handling Summary

| Error Type | Display Method | Condition |
|------------|----------------|-----------|
| API/Server errors (401, 403, 500) | Error banner at top | `hasError && (!isValidationError || !hasFieldErrors)` |
| Validation errors without field details | Error banner at top | `hasError && (!isValidationError || !hasFieldErrors)` |
| Field validation errors | Inline on each field | `hasFieldError('fieldName')` |
| Client-side validation | Inline via `:rules` | Form validation before submit |

#### Complete Page Example

```vue
<template>
  <q-page class="flex flex-center">
    <q-card class="q-pa-lg" style="width: 100%; max-width: 400px;">
      <q-card-section>
        <div class="text-h5 text-center q-mb-md">{{ t('auth.register') }}</div>

        <!-- General error banner -->
        <q-banner
          v-if="hasError && (!isValidationError || !hasFieldErrors)"
          class="bg-negative text-white q-mb-md"
          rounded
        >
          {{ errorMessage }}
          <template v-if="helpCode">
            <br />
            <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>

        <q-form @submit="handleSubmit" class="q-gutter-md">
          <q-input
            v-model="form.email"
            :label="t('auth.email')"
            type="email"
            :error="hasFieldError('email')"
            :error-message="getFieldError('email')"
            lazy-rules
            :rules="[
              val => !!val || t('validation.required'),
              val => isValidEmail(val) || t('validation.email')
            ]"
          />

          <q-input
            v-model="form.password"
            :label="t('auth.password')"
            :type="showPassword ? 'text' : 'password'"
            :error="hasFieldError('password')"
            :error-message="getFieldError('password')"
            lazy-rules
            :rules="[
              val => !!val || t('validation.required'),
              val => val.length >= 5 || t('validation.minLength', { min: 5 })
            ]"
          >
            <template v-slot:append>
              <q-icon
                :name="showPassword ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                @click="showPassword = !showPassword"
              />
            </template>
          </q-input>

          <q-btn
            type="submit"
            :label="t('auth.register')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
          />
        </q-form>
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useErrorHandler } from 'src/composables/useErrorHandler';
import { accountApi } from 'src/api';

const { t } = useI18n();
const {
  setError,
  clearError,
  hasError,
  hasFieldErrors,
  errorMessage,
  helpCode,
  isValidationError,
  hasFieldError,
  getFieldError
} = useErrorHandler();

const loading = ref(false);
const showPassword = ref(false);
const form = ref({
  email: '',
  password: ''
});

function isValidEmail(val) {
  const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailPattern.test(val);
}

async function handleSubmit() {
  clearError();
  loading.value = true;

  try {
    await accountApi.register(form.value);
    // Handle success - redirect or show message
  } catch (err) {
    setError(err);
  } finally {
    loading.value = false;
  }
}
</script>
```

#### Styling Consistency Guidelines

To maintain visual consistency across all pages:

1. **Error banner placement**: Always immediately before the form/content area, inside the card section
2. **Spacing**: Use `q-mb-md` class on the banner for consistent spacing
3. **Colors**: Always use `bg-negative text-white` for error banners
4. **Help code format**: Always use `<small>` tag with line break for secondary information
5. **Field errors**: Let Quasar's built-in error styling handle field-level display

### 7.3 Error Keys Reference

Error keys are organized by category in the i18n files:

**Error Keys (`error.*`)**
| Error Key | Description |
|-----------|-------------|
| `error.generic` | Generic unexpected error |
| `error.network` | Network/connection error |
| `error.unknown` | Unknown server exception |
| `error.dataError` | Data integrity error |
| `error.notFound` | Resource not found |

**Security Keys (`security.*`)**
| Error Key | HTTP Status | Description |
|-----------|-------------|-------------|
| `security.unauthorized` | 401 | Not authenticated / insufficient rights |
| `security.authError` | 401 | Authentication issue |
| `security.badCreds` | 401 | Invalid login/password combination |
| `security.sessionExpired` | 401 | JWT/session expired |
| `security.accNotEnabled` | 401 | Account not activated |
| `security.accLocked` | 401 | Account locked |
| `security.accountExpired` | 401 | Account expired |
| `security.credExpired` | 401 | Credentials expired |
| `security.opForbidden` | 403 | Operation forbidden / access denied |
| `security.generic` | 500 | Internal security exception |

**Validation Keys (`validation.*`)**
| Error Key | HTTP Status | Description |
|-----------|-------------|-------------|
| `validation.badRequest` | 400 | Invalid request format |
| `validation.invalidData` | 400 | Field validation errors |

### 7.4 Error Parser Utility

**File:** `src/utils/errorHandler.js`

Parses axios errors and extracts ErrorDto fields:

```javascript
// Parse API error into structured object
parseApiError(error) → {
  helpCode: string | null,
  errorKey: string,        // e.g., "security.opForbidden"
  message: string,         // Raw message from server (fallback)
  fieldErrors: { [fieldName]: message },
  isValidationError: boolean,
  status: number
}

// Default error keys by HTTP status (when server doesn't provide errorKey)
const defaultErrorKeyByStatus = {
  400: 'validation.badRequest',
  401: 'security.unauthorized',
  403: 'security.opForbidden',
  404: 'error.notFound',
};
// 500+ errors default to 'error.unknown'
// Network errors (no response) default to 'error.network'
```

### 7.5 Error Handler Composable

**File:** `src/composables/useErrorHandler.js`

Uses vue-i18n to provide translated error messages:

```javascript
import { useI18n } from 'vue-i18n';

export function useErrorHandler() {
  const { t, te } = useI18n();

  // Translated error message - uses errorKey for translation, falls back to raw message
  const errorMessage = computed(() => {
    if (!error.value) return null;

    const key = error.value.errorKey;
    if (key && te(key)) {
      return t(key);  // Return translated message
    }
    // Fallback to raw message from server
    return error.value.message || null;
  });

  // ... rest of composable
}
```

**Returns:**
```javascript
{
  error,            // readonly ref - full error object
  fieldErrors,      // computed - { fieldName: message }
  hasError,         // computed - boolean
  hasFieldErrors,   // computed - true if any field-specific errors exist
  errorMessage,     // computed - TRANSLATED error message (or fallback)
  errorKey,         // computed - raw error key for custom handling
  helpCode,         // computed - support help code
  isValidationError,// computed - is field validation error
  setError,         // function(err) - parse and set error
  clearError,       // function - clear all errors
  hasFieldError,    // function(fieldName) - boolean
  getFieldError     // function(fieldName) - message or null
}
```

### 7.6 Usage in Components

Components use `errorMessage` which automatically displays translated text:

```vue
<template>
  <q-banner v-if="hasError && (!isValidationError || !hasFieldErrors)" class="bg-negative text-white">
    {{ errorMessage }}  <!-- Automatically translated -->
    <template v-if="helpCode">
      <br />
      <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
    </template>
  </q-banner>
</template>

<script setup>
import { useErrorHandler } from 'src/composables/useErrorHandler';

const {
  setError,
  clearError,
  hasError,
  hasFieldErrors,   // True if any field-specific errors exist
  errorMessage,     // Translated message
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError
} = useErrorHandler();

async function handleSubmit() {
  clearError();
  try {
    await api.someAction();
  } catch (err) {
    setError(err);  // Error is parsed and translated automatically
  }
}
</script>
```

### 7.7 i18n Error Keys Structure

Both `en-US/index.js` and `fr-FR/index.js` must include these error keys:

```javascript
export default {
  // ... other keys ...

  error: {
    generic: 'An unexpected error occurred. Please try again.',
    network: 'Network error. Please check your connection.',
    unknown: 'An unknown Exception has occurred',
    dataError: 'Data integrity error',
    notFound: 'The requested resource was not found',
    helpCode: 'Help Code',
    tryAgain: 'Please try again',
    contactSupport: 'If the problem persists, contact support with the help code.'
  },

  security: {
    unauthorized: 'You do not have the required rights. You can contact help desk',
    authError: 'Authentication issue has occurred.',
    accountExpired: 'Your account has expired.',
    credExpired: 'Your credentials have expired.',
    accNotEnabled: 'Your account is not enabled.',
    accLocked: 'There is an issue with your account. Check your email and contact the support team. Remember to save the help code.',
    badCreds: 'The login and password combination does not exist',
    opForbidden: 'Access has been denied. You can contact help desk',
    sessionExpired: 'Your session is no longer valid. You need to sign-in again',
    generic: 'Internal unknown exception. You can contact help desk with your help code'
  },

  validation: {
    // ... existing validation keys ...
    badRequest: 'The request is not valid. Check method argument mismatch, missing parameters e.t.c.',
    invalidData: 'Invalid Data'
  }
};
```

---

## 8. Internationalization

### 8.1 Translation Keys

Both `en-US/index.js` and `fr-FR/index.js` MUST have identical keys.

```javascript
export default {
  // Auth
  auth: {
    login: '',
    logout: '',
    register: '',
    email: '',
    password: '',
    confirmPassword: '',
    firstName: '',
    lastName: '',
    phone: '',
    dateOfBirth: '',
    gender: '',
    male: '',
    female: '',
    other: '',
    forgotPassword: '',
    resetPassword: '',
    newPassword: '',
    currentPassword: '',
    rememberMe: '',
    noAccount: '',
    haveAccount: '',
    createAccount: '',
    activateAccount: '',
    enterOtp: '',
    otpSent: '',
    resendOtp: '',
    verifyOtp: ''
  },

  // Session
  session: {
    expiring: '',
    expiringDesc: '',  // Use {minutes} placeholder
    continueQuestion: '',
    continueSession: '',
    refreshed: '',
    expired: ''
  },

  // Validation
  validation: {
    required: '',
    email: '',
    minLength: '',     // Use {min} placeholder
    maxLength: '',     // Use {max} placeholder
    passwordMatch: '',
    passwordStrength: '',
    invalidDate: '',
    pastDate: ''
  },

  // Errors
  error: {
    generic: '',
    network: '',
    helpCode: '',
    tryAgain: '',
    contactSupport: ''
  },

  // Success messages
  success: {
    registered: '',
    activated: '',
    passwordReset: '',
    emailSent: '',
    loggedOut: ''
  },

  // Common
  common: {
    submit: '',
    cancel: '',
    save: '',
    back: '',
    next: '',
    loading: '',
    or: ''
  }
};
```

### 8.2 English Translations

**File:** `src/i18n/en-US/index.js`

```javascript
export default {
  auth: {
    login: 'Login',
    logout: 'Logout',
    register: 'Register',
    email: 'Email',
    password: 'Password',
    confirmPassword: 'Confirm Password',
    firstName: 'First Name',
    lastName: 'Last Name',
    phone: 'Phone Number',
    dateOfBirth: 'Date of Birth',
    gender: 'Gender',
    male: 'Male',
    female: 'Female',
    other: 'Other',
    forgotPassword: 'Forgot Password?',
    resetPassword: 'Reset Password',
    newPassword: 'New Password',
    currentPassword: 'Current Password',
    rememberMe: 'Remember Me',
    noAccount: "Don't have an account?",
    haveAccount: 'Already have an account?',
    createAccount: 'Create Account',
    activateAccount: 'Activate Account',
    enterOtp: 'Enter Verification Code',
    otpSent: 'A verification code has been sent to your email',
    resendOtp: 'Resend Code',
    verifyOtp: 'Verify'
  },
  session: {
    expiring: 'Session Expiring',
    expiringDesc: 'Your session will expire in {minutes} minute(s).',
    continueQuestion: 'Would you like to continue your session?',
    continueSession: 'Continue Session',
    refreshed: 'Session refreshed successfully',
    expired: 'Your session has expired. Please log in again.'
  },
  validation: {
    required: 'This field is required',
    email: 'Please enter a valid email address',
    minLength: 'Must be at least {min} characters',
    maxLength: 'Must be no more than {max} characters',
    passwordMatch: 'Passwords do not match',
    passwordStrength: 'Password must contain at least one uppercase letter, one lowercase letter, and one number',
    invalidDate: 'Please enter a valid date',
    pastDate: 'Date must be in the past'
  },
  error: {
    generic: 'An unexpected error occurred. Please try again.',
    network: 'Network error. Please check your connection.',
    helpCode: 'Help Code',
    tryAgain: 'Please try again',
    contactSupport: 'If the problem persists, contact support with the help code.'
  },
  success: {
    registered: 'Registration successful! Please check your email to activate your account.',
    activated: 'Your account has been activated. You can now log in.',
    passwordReset: 'Your password has been reset. You can now log in with your new password.',
    emailSent: 'Please check your email for further instructions.',
    loggedOut: 'You have been logged out successfully.'
  },
  common: {
    submit: 'Submit',
    cancel: 'Cancel',
    save: 'Save',
    back: 'Back',
    next: 'Next',
    loading: 'Loading...',
    or: 'or'
  }
};
```

### 8.3 French Translations

**File:** `src/i18n/fr-FR/index.js`

```javascript
export default {
  auth: {
    login: 'Connexion',
    logout: 'Déconnexion',
    register: "S'inscrire",
    email: 'Email',
    password: 'Mot de passe',
    confirmPassword: 'Confirmer le mot de passe',
    firstName: 'Prénom',
    lastName: 'Nom',
    phone: 'Numéro de téléphone',
    dateOfBirth: 'Date de naissance',
    gender: 'Genre',
    male: 'Homme',
    female: 'Femme',
    other: 'Autre',
    forgotPassword: 'Mot de passe oublié ?',
    resetPassword: 'Réinitialiser le mot de passe',
    newPassword: 'Nouveau mot de passe',
    currentPassword: 'Mot de passe actuel',
    rememberMe: 'Se souvenir de moi',
    noAccount: "Vous n'avez pas de compte ?",
    haveAccount: 'Vous avez déjà un compte ?',
    createAccount: 'Créer un compte',
    activateAccount: 'Activer le compte',
    enterOtp: 'Entrer le code de vérification',
    otpSent: 'Un code de vérification a été envoyé à votre email',
    resendOtp: 'Renvoyer le code',
    verifyOtp: 'Vérifier'
  },
  session: {
    expiring: 'Session expirant',
    expiringDesc: 'Votre session expirera dans {minutes} minute(s).',
    continueQuestion: 'Voulez-vous continuer votre session ?',
    continueSession: 'Continuer la session',
    refreshed: 'Session actualisée avec succès',
    expired: 'Votre session a expiré. Veuillez vous reconnecter.'
  },
  validation: {
    required: 'Ce champ est requis',
    email: 'Veuillez entrer une adresse email valide',
    minLength: 'Doit contenir au moins {min} caractères',
    maxLength: 'Ne doit pas dépasser {max} caractères',
    passwordMatch: 'Les mots de passe ne correspondent pas',
    passwordStrength: 'Le mot de passe doit contenir au moins une majuscule, une minuscule et un chiffre',
    invalidDate: 'Veuillez entrer une date valide',
    pastDate: 'La date doit être dans le passé'
  },
  error: {
    generic: 'Une erreur inattendue est survenue. Veuillez réessayer.',
    network: 'Erreur réseau. Veuillez vérifier votre connexion.',
    helpCode: "Code d'aide",
    tryAgain: 'Veuillez réessayer',
    contactSupport: "Si le problème persiste, contactez le support avec le code d'aide."
  },
  success: {
    registered: 'Inscription réussie ! Veuillez vérifier votre email pour activer votre compte.',
    activated: 'Votre compte a été activé. Vous pouvez maintenant vous connecter.',
    passwordReset: 'Votre mot de passe a été réinitialisé. Vous pouvez maintenant vous connecter avec votre nouveau mot de passe.',
    emailSent: 'Veuillez vérifier votre email pour les instructions.',
    loggedOut: 'Vous avez été déconnecté avec succès.'
  },
  common: {
    submit: 'Soumettre',
    cancel: 'Annuler',
    save: 'Enregistrer',
    back: 'Retour',
    next: 'Suivant',
    loading: 'Chargement...',
    or: 'ou'
  }
};
```

---

## 9. Page Components

### 9.1 LoginPage.vue

**Route:** `/login`
**Meta:** `{ requiresGuest: true }`

**Features:**
- Email input with validation
- Password input with show/hide toggle
- Login button with loading state
- Link to forgot password
- Link to register
- Handle `check.otp` response → redirect to `/otp?id={loginInfoId}`
- Handle success → redirect to `query.redirect` or `/dashboard`
- Show session expired message if `query.expired === 'true'`
- Use `useErrorHandler` for form errors

**Form Fields:**
| Field | Type | Validation |
|-------|------|------------|
| email | email | Required, valid email |
| password | password | Required |

### 9.2 RegisterPage.vue

**Route:** `/register`
**Meta:** `{ requiresGuest: true }`

**Features:**
- Multi-field registration form
- Password confirmation with match validation
- Gender select (Male, Female, Other)
- Date of birth picker (must be past date)
- Phone input (optional)
- 2FA opt-in checkbox
- Success → show message, redirect to login

**Form Fields:**
| Field | Type | Validation |
|-------|------|------------|
| login | text | Required, 1-50 chars |
| email | email | Required, valid email, 5-100 chars |
| password | password | Required, 5-100 chars |
| confirmPassword | password | Required, must match password |
| firstName | text | Required, max 50 chars |
| lastName | text | Required, max 50 chars |
| phone | tel | Optional |
| dob | date | Optional, must be past date |
| gender | select | Optional (MALE, FEMALE, OTHER) |
| otpEnabled | checkbox | Optional |

### 9.3 OtpPage.vue

**Route:** `/otp`
**Meta:** `{ requiresGuest: true }`

**Features:**
- 6-digit OTP input
- Auto-focus first input
- Auto-submit when all digits entered
- Resend OTP link (calls login again)
- Get `loginInfoId` from `route.query.id`
- Success → redirect to `query.redirect` or `/dashboard`
- 30-minute OTP expiry warning

**Form Fields:**
| Field | Type | Validation |
|-------|------|------------|
| otp | text | Required, 6 digits |

### 9.4 ForgotPasswordPage.vue

**Route:** `/forgot-password`
**Meta:** `{ requiresGuest: true }`

**Features:**
- Email input
- Date of birth input (for verification)
- Submit sends password reset email
- Success → show confirmation message
- Link back to login

**Form Fields:**
| Field | Type | Validation |
|-------|------|------------|
| email | email | Required, valid email |
| dob | date | Required, valid date |

### 9.5 ResetPasswordPage.vue

**Route:** `/reset-password`
**Meta:** `{ requiresGuest: true }`

**Features:**
- Get reset `key` from `route.query.key`
- New password input
- Confirm password input
- Password strength indicator (optional)
- Success → redirect to login with success message
- Handle invalid/expired key error

**Form Fields:**
| Field | Type | Validation |
|-------|------|------------|
| password | password | Required, 5-50 chars |
| confirmPassword | password | Required, must match |

### 9.6 ActivatePage.vue

**Route:** `/activate`
**Meta:** `{ requiresGuest: true }`

**Features:**
- Get activation `key` from `route.query.key`
- Auto-activate on mount (call API immediately)
- Show loading state during activation
- Success → show message, auto-redirect to login after 3 seconds
- Error → show error message with link to resend activation
- Handle invalid/expired key

---

## 10. Router Configuration

### 10.1 Routes Definition

**File:** `src/router/routes.js`

```javascript
export default [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    component: () => import('pages/auth/LoginPage.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/register',
    component: () => import('pages/auth/RegisterPage.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/otp',
    component: () => import('pages/auth/OtpPage.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/forgot-password',
    component: () => import('pages/auth/ForgotPasswordPage.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/reset-password',
    component: () => import('pages/auth/ResetPasswordPage.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/activate',
    component: () => import('pages/auth/ActivatePage.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/dashboard',
    component: () => import('pages/DashboardPage.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/ErrorNotFound.vue')
  }
];
```

### 10.2 Router with Guards

**File:** `src/router/index.js`

**Navigation Guard Logic:**

```javascript
Router.beforeEach((to, from, next) => {
  const requiresAuth = to.matched.some(r => r.meta.requiresAuth);
  const requiresGuest = to.matched.some(r => r.meta.requiresGuest);

  // Check authentication via 'user' cookie
  const isAuthenticated = document.cookie.includes('user=');

  if (requiresAuth && !isAuthenticated) {
    next({ path: '/login', query: { redirect: to.fullPath } });
    return;
  }

  if (requiresGuest && isAuthenticated) {
    next('/dashboard');
    return;
  }

  next();
});
```

---

## Implementation Checklist

Use this checklist to track implementation progress:

### Core Infrastructure
- [ ] `src/boot/axios.js` - Axios with interceptors
- [ ] `src/plugins/sessionManager.js` - Session timer logic
- [ ] `src/utils/errorHandler.js` - Error parsing utilities

### Composables
- [ ] `src/composables/useSession.js`
- [ ] `src/composables/useErrorHandler.js`
- [ ] `src/composables/useLoading.js`

### API Services
- [ ] `src/api/auth.api.js`
- [ ] `src/api/account.api.js`
- [ ] `src/api/session.api.js`
- [ ] `src/api/index.js`

### Global Components
- [ ] `src/components/common/GlobalLoadingBar.vue`
- [ ] `src/components/common/SessionWarningDialog.vue`
- [ ] `src/App.vue` - Include global components

### i18n
- [ ] `src/i18n/en-US/index.js`
- [ ] `src/i18n/fr-FR/index.js`
- [ ] `src/boot/i18n.js`

### Page Components
- [ ] `src/pages/auth/LoginPage.vue`
- [ ] `src/pages/auth/RegisterPage.vue`
- [ ] `src/pages/auth/OtpPage.vue`
- [ ] `src/pages/auth/ForgotPasswordPage.vue`
- [ ] `src/pages/auth/ResetPasswordPage.vue`
- [ ] `src/pages/auth/ActivatePage.vue`

### Router
- [ ] `src/router/routes.js`
- [ ] `src/router/index.js` - With auth guards

---

## Example Prompt Usage

When using this specification with an LLM, structure your prompts like:

```
Using the frontend-implementation-spec.md as reference, implement [SPECIFIC FEATURE].

Requirements:
- Follow all code standards in Section 1
- Use the architecture pattern in Section 2
- Implement cross-cutting concerns as specified in Section 5
- Use the exact API endpoints from Section 4
- Include all i18n keys from Section 8
```

Example specific prompts:

1. "Implement the session manager plugin and axios interceptors for transparent session management"
2. "Create the LoginPage.vue component with full error handling and i18n support"
3. "Implement all API services (auth, account, session) following the specification"
4. "Create the useErrorHandler composable that parses backend ErrorDto responses"
