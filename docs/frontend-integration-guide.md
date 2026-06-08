# Vue.js 3 + Quasar 2 Frontend Integration Guide

This guide provides a complete setup for integrating your Vue.js 3 + Quasar 2 frontend with the Skillars security backend.

## Table of Contents

1. [Development Guidelines](#development-guidelines)
2. [Architecture Overview](#architecture-overview)
3. [Cross-Cutting Concerns](#cross-cutting-concerns)
4. [Cookie Management](#cookie-management)
5. [CORS Configuration](#cors-configuration)
6. [Project Setup](#project-setup)
7. [API Services](#api-services)
8. [Error Handling](#error-handling)
9. [Internationalization (i18n)](#internationalization-i18n)
10. [Authentication Service](#authentication-service)
11. [Page Components](#page-components)
12. [Router Configuration](#router-configuration)

---

## Development Guidelines

All code in this project must adhere to the following guidelines to ensure consistency, maintainability, and quality.

### Core Principles

| Requirement | Description |
|-------------|-------------|
| **Vue 3 Composition API** | Use `<script setup>` exclusively. No Options API or `export default` |
| **Plain JavaScript** | No TypeScript syntax, interfaces, or type annotations |
| **Component Splitting** | Each distinct feature is its own component with single responsibility |
| **Quasar Integration** | Use Quasar components and composables consistently |
| **Clean & Readable** | Components under 250 lines, reusable logic extracted to composables |

### Testing Standards

| Requirement | Description |
|-------------|-------------|
| **Component Isolation** | Each component has its own dedicated test file |
| **Vitest + Vue Test Utils** | Tests written in plain JavaScript |
| **Realistic Scenarios** | Tests simulate actual user flows with edge cases |

### UX Consistency

| Requirement | Description |
|-------------|-------------|
| **Design System** | Primary color is `#1976d2`. Follow color palette, typography, and layout specifications |
| **Quasar Design Language** | Consistent padding, typography, Material Icons |
| **Clear Feedback** | Loading indicators for all async operations, `$q.notify()` for errors and success |
| **Accessibility** | Form labels, WCAG AA contrast, keyboard navigation, aria attributes |
| **Responsive** | Mobile-first design, use Quasar grid system (`col-12 col-md-6`), mobile-tested |

### Performance Requirements

| Requirement | Description |
|-------------|-------------|
| **Lazy Loading** | Route components are async, heavy components dynamically imported |
| **Efficient Reactivity** | Use `computed` for derived state, debounced search inputs |
| **Network Hygiene** | Cancel requests on unmount, clean up timers/listeners in `onUnmounted` |
| **Bundle Awareness** | Import only needed Quasar components, evaluate dependency impact |

### Form & Validation Standards

| Requirement | Description |
|-------------|-------------|
| **Validate on Blur** | Use `lazy-rules` on inputs, not on every keystroke |
| **Loading States** | Use `:loading` and `:disable` on submit buttons |
| **Error Display** | Show field-level errors with `:error` and `:error-message` |

### Code Organization

| Requirement | Description |
|-------------|-------------|
| **API Services** | Centralize API calls in dedicated `src/api/` folder with services for each feature |
| **Internationalization** | Support English and French via i18n keys |
| **Translation Parity** | `i18n/en-US/index.js` and `i18n/fr-FR/index.js` must always have the same keys |
| **UI Consistency** | Consistent look and feel across all pages |

### Checklist for New Features

```markdown
## Core Principles Compliance
- [ ] Vue 3 Composition API: Uses `<script setup>` exclusively
- [ ] Plain JavaScript: No TypeScript syntax
- [ ] Functional Component Splitting: Single responsibility per component
- [ ] Quasar Integration: Uses Quasar components consistently
- [ ] Clean Code: Under 250 lines, logic extracted to composables

## Testing Standards Compliance
- [ ] Component has dedicated test file
- [ ] Tests use Vitest + Vue Test Utils in plain JavaScript
- [ ] Tests cover realistic user scenarios

## UX Consistency Compliance
- [ ] Uses primary color #1976d2
- [ ] Consistent padding and typography
- [ ] Loading indicators for async operations
- [ ] $q.notify() for errors and success
- [ ] Form labels and WCAG AA contrast
- [ ] Keyboard navigation support
- [ ] Mobile-first responsive design

## Performance Requirements Compliance
- [ ] Route components use lazy loading
- [ ] Uses computed for derived state
- [ ] Debounced search/filter inputs
- [ ] Cleans up on unmount (timers, listeners, requests)

## Form Standards Compliance
- [ ] Uses lazy-rules for validation on blur
- [ ] Submit buttons have :loading and :disable
- [ ] Field errors displayed properly

## Code Organization Compliance
- [ ] API calls in src/api/ services
- [ ] All text uses i18n keys
- [ ] Translation files are in parity
```

---

## Architecture Overview

The frontend architecture separates **business logic** from **cross-cutting concerns** to ensure maintainability and scalability.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Vue Components                                 │
│  (Pages, Forms, UI Elements - Focus on presentation and user interaction)│
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Composables Layer                                │
│         (useAuth, useSession, useApi - Reusable reactive logic)         │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          API Services Layer                              │
│          (auth.api.js, account.api.js - Domain-specific endpoints)      │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Axios Instance (src/boot/axios.js)                   │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              CROSS-CUTTING CONCERNS (Interceptors)               │   │
│  │                                                                   │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │   │
│  │  │   Session   │  │    Error    │  │      Loading State      │  │   │
│  │  │ Management  │  │  Handling   │  │       Management        │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │   │
│  │                                                                   │   │
│  │  • Auto token refresh        • Parse ErrorDto         • Track    │   │
│  │  • Session expiry detection  • Show notifications     • pending  │   │
│  │  • Auto logout on 401        • Field error mapping    • requests │   │
│  │  • Session warning dialog    • Help code display                 │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                            ┌───────────────┐
                            │  Backend API  │
                            └───────────────┘
```

### Key Principles

| Principle | Description |
|-----------|-------------|
| **Transparent Session Management** | Components make API calls without knowing about session refresh, expiry, or token management |
| **Centralized Error Handling** | All API errors are processed uniformly; components receive clean, structured error data |
| **Single Responsibility** | Components focus on UI; cross-cutting concerns live in interceptors and composables |
| **Automatic Cleanup** | Session timers, listeners, and pending requests are managed transparently |

---

## Cross-Cutting Concerns

Cross-cutting concerns are aspects of the application that affect multiple components but should not be duplicated in each component. They are handled transparently via **interceptors**, **composables**, and **plugins**.

### Identified Cross-Cutting Concerns

| Concern | Implementation | Location |
|---------|----------------|----------|
| **Session Management** | Axios interceptors + composable | `src/boot/axios.js`, `src/composables/useSession.js` |
| **Error Handling** | Axios interceptors + store | `src/boot/axios.js`, `src/stores/error.js` |
| **Loading State** | Axios interceptors + composable | `src/boot/axios.js`, `src/composables/useLoading.js` |
| **Authentication Guard** | Router navigation guards | `src/router/index.js` |
| **Internationalization** | Vue-i18n plugin | `src/boot/i18n.js` |
| **Notifications** | Quasar Notify plugin | `src/boot/axios.js` (via interceptors) |

### Folder Structure for Cross-Cutting Concerns

```
src/
├── boot/
│   ├── axios.js              # HTTP client with all interceptors
│   └── i18n.js               # Internationalization setup
├── composables/
│   ├── useSession.js         # Session management composable
│   ├── useLoading.js         # Global loading state
│   └── useErrorHandler.js    # Error handling utilities
├── stores/
│   ├── auth.js               # Authentication state
│   └── error.js              # Error state management
├── plugins/
│   └── sessionManager.js     # Session refresh timer plugin
└── utils/
    └── errorHandler.js       # Error parsing utilities
```

---

### 1. Session Management (Transparent)

Session management is completely transparent to components. The axios interceptors handle:
- Automatic session refresh before expiry
- Detection of session expiration
- Automatic logout and redirect on 401
- Session warning dialogs

#### Session Manager Plugin

```javascript
// src/plugins/sessionManager.js
import { ref, readonly } from 'vue';
import { sessionApi } from 'src/api';

// Private state
const SESSION_WARNING_THRESHOLD = 2 * 60 * 1000; // 2 minutes before expiry
const SESSION_CHECK_INTERVAL = 30 * 1000; // Check every 30 seconds
const SESSION_TTL = 15 * 60 * 1000; // 15 minutes

let sessionTimer = null;
let warningTimer = null;
let lastActivityTime = Date.now();

// Reactive state (read-only externally)
const _showWarning = ref(false);
const _timeUntilExpiry = ref(SESSION_TTL);
const _isRefreshing = ref(false);

export const sessionState = {
  showWarning: readonly(_showWarning),
  timeUntilExpiry: readonly(_timeUntilExpiry),
  isRefreshing: readonly(_isRefreshing)
};

/**
 * Record user activity - called by interceptors on each request
 */
export function recordActivity() {
  lastActivityTime = Date.now();
  _showWarning.value = false;
  resetTimers();
}

/**
 * Start session monitoring
 */
export function startSessionMonitoring() {
  if (sessionTimer) return; // Already running

  sessionTimer = setInterval(() => {
    const elapsed = Date.now() - lastActivityTime;
    const remaining = SESSION_TTL - elapsed;
    _timeUntilExpiry.value = Math.max(0, remaining);

    // Show warning when approaching expiry
    if (remaining <= SESSION_WARNING_THRESHOLD && remaining > 0) {
      _showWarning.value = true;
    }

    // Session expired
    if (remaining <= 0) {
      handleSessionExpired();
    }
  }, SESSION_CHECK_INTERVAL);
}

/**
 * Stop session monitoring
 */
export function stopSessionMonitoring() {
  if (sessionTimer) {
    clearInterval(sessionTimer);
    sessionTimer = null;
  }
  if (warningTimer) {
    clearTimeout(warningTimer);
    warningTimer = null;
  }
  _showWarning.value = false;
}

/**
 * Refresh session - can be called by warning dialog
 */
export async function refreshSession() {
  if (_isRefreshing.value) return false;

  _isRefreshing.value = true;
  try {
    await sessionApi.refresh();
    recordActivity();
    return true;
  } catch (error) {
    handleSessionExpired();
    return false;
  } finally {
    _isRefreshing.value = false;
  }
}

/**
 * Handle session expiration
 */
function handleSessionExpired() {
  stopSessionMonitoring();
  _showWarning.value = false;

  // Dispatch custom event for app to handle
  window.dispatchEvent(new CustomEvent('session:expired'));
}

/**
 * Reset timers on activity
 */
function resetTimers() {
  _timeUntilExpiry.value = SESSION_TTL;
}

/**
 * Cleanup - call on logout
 */
export function cleanup() {
  stopSessionMonitoring();
  lastActivityTime = Date.now();
  _timeUntilExpiry.value = SESSION_TTL;
}
```

#### Axios Interceptors with Session Management

```javascript
// src/boot/axios.js
import { boot } from 'quasar/wrappers';
import axios from 'axios';
import { Notify } from 'quasar';
import {
  recordActivity,
  startSessionMonitoring,
  stopSessionMonitoring,
  cleanup as cleanupSession
} from 'src/plugins/sessionManager';
import { parseApiError } from 'src/utils/errorHandler';

// Create axios instance
const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
  withCredentials: true,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// Track pending requests for loading state
let pendingRequests = 0;
const loadingCallbacks = new Set();

export function onLoadingChange(callback) {
  loadingCallbacks.add(callback);
  return () => loadingCallbacks.delete(callback);
}

function notifyLoadingChange(isLoading) {
  loadingCallbacks.forEach(cb => cb(isLoading));
}

export default boot(({ app, router, store }) => {
  // ============================================
  // REQUEST INTERCEPTOR
  // ============================================
  api.interceptors.request.use(
    config => {
      // Track loading state
      pendingRequests++;
      if (pendingRequests === 1) {
        notifyLoadingChange(true);
      }

      // Record activity for session management (skip refresh endpoint to avoid loops)
      if (!config.url.includes('/refresh')) {
        recordActivity();
      }

      return config;
    },
    error => {
      pendingRequests = Math.max(0, pendingRequests - 1);
      if (pendingRequests === 0) {
        notifyLoadingChange(false);
      }
      return Promise.reject(error);
    }
  );

  // ============================================
  // RESPONSE INTERCEPTOR
  // ============================================
  api.interceptors.response.use(
    response => {
      // Track loading state
      pendingRequests = Math.max(0, pendingRequests - 1);
      if (pendingRequests === 0) {
        notifyLoadingChange(false);
      }

      return response;
    },
    error => {
      // Track loading state
      pendingRequests = Math.max(0, pendingRequests - 1);
      if (pendingRequests === 0) {
        notifyLoadingChange(false);
      }

      // ========== SESSION MANAGEMENT (TRANSPARENT) ==========
      const status = error.response?.status;
      const errorData = error.response?.data;
      const errorKey = errorData?.errorMsg?.errorKey;

      // Handle 401 - Session expired or unauthorized
      if (status === 401) {
        // Stop session monitoring
        stopSessionMonitoring();

        // Determine if session expired vs bad credentials
        const isSessionExpired = errorKey === 'security.sessionExpired' ||
                                 errorKey === 'security.unauthorized';

        if (isSessionExpired) {
          // Clear auth state
          cleanupSession();

          // Redirect to login (avoid redirect loop)
          const currentPath = router.currentRoute.value.fullPath;
          if (currentPath !== '/login') {
            router.push({
              path: '/login',
              query: { redirect: currentPath, expired: 'true' }
            });
          }

          // Don't show notification for session expiry - the login page handles it
          return Promise.reject(error);
        }
      }

      // ========== ERROR HANDLING (TRANSPARENT) ==========
      // Handle 403 - Forbidden
      if (status === 403) {
        Notify.create({
          type: 'negative',
          message: errorData?.errorMsg?.message || 'Access denied',
          position: 'top',
          timeout: 5000
        });
      }

      // Handle 5xx - Server errors
      if (status >= 500) {
        const helpCode = errorData?.helpCode;
        Notify.create({
          type: 'negative',
          message: 'A server error occurred. Please try again later.',
          caption: helpCode ? `Help Code: ${helpCode}` : undefined,
          position: 'top',
          timeout: 5000
        });
      }

      // Handle network errors
      if (!error.response) {
        Notify.create({
          type: 'negative',
          message: 'Network error. Please check your connection.',
          position: 'top',
          timeout: 5000
        });
      }

      // Always reject with the original error for component-level handling
      return Promise.reject(error);
    }
  );

  // ============================================
  // SESSION EVENT LISTENERS
  // ============================================

  // Handle session expired event from session manager
  window.addEventListener('session:expired', () => {
    const currentPath = router.currentRoute.value.fullPath;
    if (currentPath !== '/login') {
      router.push({
        path: '/login',
        query: { redirect: currentPath, expired: 'true' }
      });
    }
  });

  // Start session monitoring when user is authenticated
  // (Called after successful login)
  app.config.globalProperties.$startSession = () => {
    startSessionMonitoring();
  };

  // Stop session monitoring on logout
  app.config.globalProperties.$endSession = () => {
    cleanupSession();
  };

  // Expose api globally
  app.config.globalProperties.$axios = axios;
  app.config.globalProperties.$api = api;
});

export { api };
```

#### Session Composable for Components

```javascript
// src/composables/useSession.js
import { computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useQuasar } from 'quasar';
import { useI18n } from 'vue-i18n';
import {
  sessionState,
  refreshSession,
  startSessionMonitoring,
  stopSessionMonitoring,
  cleanup
} from 'src/plugins/sessionManager';

/**
 * Session management composable
 * Provides reactive session state and actions to components
 *
 * Usage:
 *   const { showWarning, minutesRemaining, handleRefresh, handleLogout } = useSession();
 */
export function useSession() {
  const router = useRouter();
  const $q = useQuasar();
  const { t } = useI18n();

  // Computed values from session state
  const showWarning = computed(() => sessionState.showWarning.value);
  const timeUntilExpiry = computed(() => sessionState.timeUntilExpiry.value);
  const isRefreshing = computed(() => sessionState.isRefreshing.value);
  const minutesRemaining = computed(() => Math.ceil(timeUntilExpiry.value / 60000));

  // Handle session refresh (from warning dialog)
  async function handleRefresh() {
    const success = await refreshSession();
    if (success) {
      $q.notify({
        type: 'positive',
        message: t('session.refreshed'),
        position: 'top'
      });
    }
    return success;
  }

  // Handle logout
  async function handleLogout() {
    cleanup();
    router.push('/login');
  }

  // Initialize session monitoring
  function initSession() {
    startSessionMonitoring();
  }

  // Cleanup on component unmount (if needed)
  function destroySession() {
    stopSessionMonitoring();
  }

  return {
    // State (reactive, read-only)
    showWarning,
    timeUntilExpiry,
    minutesRemaining,
    isRefreshing,
    // Actions
    handleRefresh,
    handleLogout,
    initSession,
    destroySession
  };
}
```

#### Session Warning Dialog Component

```vue
<!-- src/components/common/SessionWarningDialog.vue -->
<template>
  <q-dialog
    :model-value="showWarning"
    persistent
    @hide="onHide"
  >
    <q-card style="min-width: 350px">
      <q-card-section class="row items-center">
        <q-avatar icon="access_time" color="warning" text-color="white" />
        <span class="q-ml-sm text-h6">{{ $t('session.expiring') }}</span>
      </q-card-section>

      <q-card-section>
        <p>{{ $t('session.expiringDesc', { minutes: minutesRemaining }) }}</p>
        <p>{{ $t('session.continueQuestion') }}</p>
      </q-card-section>

      <q-card-section>
        <q-linear-progress
          :value="progressValue"
          color="warning"
          class="q-mt-md"
        />
      </q-card-section>

      <q-card-actions align="right">
        <q-btn
          flat
          :label="$t('auth.logout')"
          color="negative"
          :disable="isRefreshing"
          @click="handleLogout"
        />
        <q-btn
          :label="$t('session.continueSession')"
          color="primary"
          :loading="isRefreshing"
          :disable="isRefreshing"
          @click="handleRefresh"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed } from 'vue';
import { useSession } from 'src/composables/useSession';

const {
  showWarning,
  timeUntilExpiry,
  minutesRemaining,
  isRefreshing,
  handleRefresh,
  handleLogout
} = useSession();

// Progress from 1 to 0 over warning period (2 minutes)
const WARNING_PERIOD = 2 * 60 * 1000;
const progressValue = computed(() => {
  return Math.max(0, Math.min(1, timeUntilExpiry.value / WARNING_PERIOD));
});

function onHide() {
  // Dialog hidden - session either refreshed or expired
}
</script>
```

---

### 2. Error Handling (Transparent)

Error handling is centralized in the axios interceptors. Components receive clean, structured errors.

#### Error Handler Utility

```javascript
// src/utils/errorHandler.js

/**
 * Parse API error response into a structured object
 * Works with the backend's ErrorDto structure
 */
export function parseApiError(error) {
  const response = error.response?.data;

  // Network error - no response
  if (!response) {
    return {
      helpCode: null,
      errorKey: 'generic.network',
      message: 'Network error. Please check your connection.',
      fieldErrors: {},
      isValidationError: false,
      status: 0
    };
  }

  // Parse field errors into a map
  const fieldErrors = {};
  if (response.fieldErrors && Array.isArray(response.fieldErrors)) {
    response.fieldErrors.forEach(fe => {
      fieldErrors[fe.field] = fe.errorMsg?.message || 'Invalid value';
    });
  }

  return {
    helpCode: response.helpCode || null,
    errorKey: response.errorMsg?.errorKey || 'generic.unknown',
    message: response.errorMsg?.message || 'An unexpected error occurred',
    fieldErrors,
    isValidationError: response.errorMsg?.errorKey === 'validation.invalidData',
    status: error.response?.status || 0
  };
}

/**
 * Check if error is a validation error with field-level errors
 */
export function isValidationError(error) {
  return parseApiError(error).isValidationError;
}

/**
 * Get field error message
 */
export function getFieldError(error, fieldName) {
  const parsed = parseApiError(error);
  return parsed.fieldErrors[fieldName] || null;
}

/**
 * Get all field errors as a map
 */
export function getFieldErrors(error) {
  return parseApiError(error).fieldErrors;
}

/**
 * Get the main error message
 */
export function getErrorMessage(error) {
  return parseApiError(error).message;
}

/**
 * Get help code for support
 */
export function getHelpCode(error) {
  return parseApiError(error).helpCode;
}
```

#### Error Handler Composable

```javascript
// src/composables/useErrorHandler.js
import { ref, computed, readonly } from 'vue';
import { parseApiError, getFieldErrors } from 'src/utils/errorHandler';

/**
 * Error handling composable for forms and components
 *
 * Usage:
 *   const { error, fieldErrors, setError, clearError, hasFieldError, getFieldError } = useErrorHandler();
 */
export function useErrorHandler() {
  const _error = ref(null);
  const _fieldErrors = ref({});

  // Computed values
  const hasError = computed(() => _error.value !== null);
  const errorMessage = computed(() => _error.value?.message || '');
  const errorKey = computed(() => _error.value?.errorKey || '');
  const helpCode = computed(() => _error.value?.helpCode || '');
  const isValidationError = computed(() => _error.value?.isValidationError || false);

  /**
   * Set error from API response
   */
  function setError(err) {
    _error.value = parseApiError(err);
    _fieldErrors.value = getFieldErrors(err);
  }

  /**
   * Clear all errors
   */
  function clearError() {
    _error.value = null;
    _fieldErrors.value = {};
  }

  /**
   * Check if a specific field has an error
   */
  function hasFieldError(fieldName) {
    return !!_fieldErrors.value[fieldName];
  }

  /**
   * Get error message for a specific field
   */
  function getFieldError(fieldName) {
    return _fieldErrors.value[fieldName] || null;
  }

  return {
    // State (read-only)
    error: readonly(_error),
    fieldErrors: readonly(_fieldErrors),
    // Computed
    hasError,
    errorMessage,
    errorKey,
    helpCode,
    isValidationError,
    // Actions
    setError,
    clearError,
    hasFieldError,
    getFieldError
  };
}
```

---

### 3. Loading State (Transparent)

Global loading state is tracked automatically by the axios interceptors.

#### Loading Composable

```javascript
// src/composables/useLoading.js
import { ref, readonly, onMounted, onUnmounted } from 'vue';
import { onLoadingChange } from 'src/boot/axios';

/**
 * Global loading state composable
 * Automatically tracks all pending API requests
 *
 * Usage:
 *   const { isLoading } = useLoading();
 */
export function useLoading() {
  const _isLoading = ref(false);
  let unsubscribe = null;

  onMounted(() => {
    unsubscribe = onLoadingChange(loading => {
      _isLoading.value = loading;
    });
  });

  onUnmounted(() => {
    if (unsubscribe) {
      unsubscribe();
    }
  });

  return {
    isLoading: readonly(_isLoading)
  };
}
```

#### Global Loading Indicator Component

```vue
<!-- src/components/common/GlobalLoadingBar.vue -->
<template>
  <q-linear-progress
    v-if="isLoading"
    indeterminate
    color="primary"
    class="global-loading-bar"
  />
</template>

<script setup>
import { useLoading } from 'src/composables/useLoading';

const { isLoading } = useLoading();
</script>

<style scoped>
.global-loading-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 9999;
}
</style>
```

---

### 4. Authentication Guard (Transparent)

Router guards handle authentication checks transparently.

```javascript
// src/router/index.js
import { route } from 'quasar/wrappers';
import { createRouter, createWebHistory } from 'vue-router';
import routes from './routes';

export default route(function () {
  const Router = createRouter({
    history: createWebHistory(process.env.VUE_ROUTER_BASE),
    routes
  });

  // ============================================
  // AUTHENTICATION GUARD (TRANSPARENT)
  // ============================================
  Router.beforeEach((to, from, next) => {
    const requiresAuth = to.matched.some(record => record.meta.requiresAuth);
    const requiresGuest = to.matched.some(record => record.meta.requiresGuest);

    // Check authentication via cookie (set by backend)
    const userCookie = document.cookie
      .split('; ')
      .find(row => row.startsWith('user='));
    const isAuthenticated = !!userCookie;

    // Protected route - redirect to login if not authenticated
    if (requiresAuth && !isAuthenticated) {
      next({
        path: '/login',
        query: { redirect: to.fullPath }
      });
      return;
    }

    // Guest-only route (login, register) - redirect to dashboard if authenticated
    if (requiresGuest && isAuthenticated) {
      next('/dashboard');
      return;
    }

    next();
  });

  return Router;
});
```

---

### 5. Using Cross-Cutting Concerns in Components

Components don't need to worry about session management, error handling, or loading states. They just make API calls and the cross-cutting concerns are handled automatically.

#### Example: Simple Form Component

```vue
<!-- src/pages/auth/LoginPage.vue -->
<template>
  <q-page class="row justify-center items-center q-pa-md">
    <!-- Global loading bar is automatic -->

    <q-card class="col-12 col-sm-8 col-md-6 col-lg-4">
      <q-card-section class="bg-primary text-white">
        <div class="text-h5">{{ $t('auth.login') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form ref="formRef" @submit.prevent="handleLogin" class="q-gutter-md">
          <q-input
            v-model="email"
            :label="$t('auth.email')"
            type="email"
            lazy-rules
            :rules="emailRules"
            :error="hasFieldError('id')"
            :error-message="getFieldError('id')"
            outlined
          />

          <q-input
            v-model="password"
            :label="$t('auth.password')"
            :type="showPassword ? 'text' : 'password'"
            lazy-rules
            :rules="passwordRules"
            :error="hasFieldError('password')"
            :error-message="getFieldError('password')"
            outlined
          />

          <!-- Error display uses composable -->
          <q-banner v-if="hasError" class="bg-negative text-white" rounded>
            {{ errorMessage }}
            <template v-if="helpCode" v-slot:action>
              <small>{{ $t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-btn
            type="submit"
            :label="$t('auth.login')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
          />
        </q-form>
      </q-card-section>
    </q-card>

    <!-- Session warning dialog is automatic (in App.vue) -->
  </q-page>
</template>

<script setup>
import { ref, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useErrorHandler } from 'src/composables/useErrorHandler';
import { authApi } from 'src/api';

const router = useRouter();
const route = useRoute();
const { t } = useI18n();

// Error handling composable - provides hasError, errorMessage, setError, etc.
const {
  hasError,
  errorMessage,
  helpCode,
  hasFieldError,
  getFieldError,
  setError,
  clearError
} = useErrorHandler();

// Local state
const formRef = ref(null);
const email = ref('');
const password = ref('');
const showPassword = ref(false);
const loading = ref(false);

// Validation rules
const emailRules = [
  val => !!val || t('validation.required'),
  val => /.+@.+\..+/.test(val) || t('validation.email')
];
const passwordRules = [
  val => !!val || t('validation.required')
];

// Cleanup on unmount
onUnmounted(() => {
  clearError();
});

// Handle login - session management is TRANSPARENT
async function handleLogin() {
  const valid = await formRef.value.validate();
  if (!valid) return;

  loading.value = true;
  clearError();

  try {
    // Just make the API call - interceptors handle everything else:
    // - Session token refresh
    // - 401 handling and redirect
    // - Server error notifications
    // - Network error notifications
    const response = await authApi.login(email.value, password.value);

    if (response.data.message === 'check.otp') {
      // Handle 2FA
      router.push({ path: '/otp', query: { id: response.data.data.loginInfoId } });
    } else {
      // Success - session monitoring starts automatically via $startSession
      const redirect = route.query.redirect || '/dashboard';
      router.push(redirect);
    }
  } catch (err) {
    // Only handle validation/bad credentials errors here
    // Server errors and session errors are handled by interceptors
    setError(err);
  } finally {
    loading.value = false;
  }
}
</script>
```

#### App.vue - Including Global Components

```vue
<!-- src/App.vue -->
<template>
  <!-- Global loading indicator - shows during any API call -->
  <GlobalLoadingBar />

  <!-- Session warning dialog - shows automatically when session is expiring -->
  <SessionWarningDialog />

  <!-- Main router view -->
  <router-view />
</template>

<script setup>
import GlobalLoadingBar from 'components/common/GlobalLoadingBar.vue';
import SessionWarningDialog from 'components/common/SessionWarningDialog.vue';
</script>
```

---

### Benefits of This Architecture

| Benefit | Description |
|---------|-------------|
| **Separation of Concerns** | Components focus on UI; cross-cutting logic is centralized |
| **Transparency** | Components don't need to know about session management or global error handling |
| **Consistency** | All API calls are processed uniformly |
| **Maintainability** | Changes to session or error handling are made in one place |
| **Testability** | Cross-cutting concerns can be mocked easily in tests |
| **Scalability** | New components automatically get all cross-cutting features |

---

## Cookie Management

### Cookies Set by Backend

| Cookie | HTTPOnly | Secure | Purpose | TTL | Frontend Access |
|--------|----------|--------|---------|-----|-----------------|
| `potc` | Yes | Yes | JWT Token | 15 min | No (HTTP only) |
| `bcookie` | Yes | Yes | Browser Client ID | Session | No (HTTP only) |
| `ION` | Yes | Yes | JWT Session ID | Session | No (HTTP only) |
| `user` | No | No | Display name | 15 min | Yes |
| `admin` | No | No | Admin indicator | 15 min | Yes |
| `rint` | No | No | Session countdown | 15 min | Yes |
| `lang` | No | No | Language preference | Long | Yes |
| `lii` | Yes | Yes | 2FA Login Info ID | 30 min | No (HTTP only) |

### Reading Cookies in Vue.js

```javascript
// Using native JavaScript
function getCookie(name) {
  const value = `; ${document.cookie}`;
  const parts = value.split(`; ${name}=`);
  if (parts.length === 2) return parts.pop().split(';').shift();
  return null;
}

// Usage
const displayName = getCookie('user');
const isAdmin = getCookie('admin') === 'true';

// Using @vueuse/integrations
import { useCookies } from '@vueuse/integrations/useCookies';

const cookies = useCookies(['user', 'admin', 'rint']);
const displayName = cookies.get('user');
```

### Cookie Security Notes

- **HTTPOnly cookies** (`potc`, `bcookie`, `ION`, `lii`) cannot be accessed via JavaScript - this is intentional for security
- **Non-HTTPOnly cookies** (`user`, `admin`, `rint`) can be read to update UI state
- Always use `withCredentials: true` for API calls to ensure cookies are sent
- The backend handles all cookie creation and validation

---

## CORS Configuration

### Backend CORS Settings (Development)

```yaml
# application-dev.yaml
custom:
  cors:
    allowed-origins: 'http://localhost:8080,https://localhost:9090,http://localhost:5175,http://localhost:9000'
    allowed-methods: '*'
    allowed-headers: '*'
    allow-credentials: true
    max-age: 1800
```

### Frontend Configuration

```javascript
// quasar.config.js
module.exports = configure(function (ctx) {
  return {
    devServer: {
      port: 9000, // Must match allowed-origins
      proxy: {
        // Optional: Proxy API calls in development
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/v1': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/authenticate': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/otp': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/refresh': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  };
});
```

### CORS Troubleshooting

| Issue | Solution |
|-------|----------|
| Cookies not sent | Add `withCredentials: true` to axios |
| CORS error | Ensure frontend URL is in `allowed-origins` |
| Preflight fails | Check `allowed-methods` and `allowed-headers` |
| Credentials blocked | Ensure `allow-credentials: true` on backend |

---

## Project Setup

### 1. Create Quasar Project

```bash
npm init quasar

# Select:
# - Quasar v2
# - Vue 3
# - Vite
# - Pinia (for state management)
# - Composition API
```

### 2. Install Dependencies

```bash
cd your-project
npm install axios @vueuse/integrations universal-cookie
```

### 3. Configure Environment Variables

```env
# .env.development
VITE_API_URL=http://localhost:8080

# .env.production
VITE_API_URL=https://your-api-domain.com
```

### 4. Configure Axios Boot File

```javascript
// src/boot/axios.js
import { boot } from 'quasar/wrappers';
import axios from 'axios';
import { Notify } from 'quasar';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
  withCredentials: true,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

export default boot(({ app, router }) => {
  // Response interceptor
  api.interceptors.response.use(
    response => response,
    error => {
      const status = error.response?.status;
      const errorData = error.response?.data;

      // Handle session expiration
      if (status === 401) {
        const errorKey = errorData?.errorMsg?.errorKey;
        if (errorKey === 'security.sessionExpired') {
          const currentPath = router.currentRoute.value.fullPath;
          if (currentPath !== '/login') {
            router.push({
              path: '/login',
              query: { redirect: currentPath, expired: 'true' }
            });
          }
        }
      }

      // Handle server errors with notification
      if (status >= 500) {
        const helpCode = errorData?.helpCode;
        Notify.create({
          type: 'negative',
          message: 'A server error occurred. Please try again later.',
          caption: helpCode ? `Help Code: ${helpCode}` : undefined,
          position: 'top',
          timeout: 5000
        });
      }

      // Handle network errors
      if (!error.response) {
        Notify.create({
          type: 'negative',
          message: 'Network error. Please check your connection.',
          position: 'top',
          timeout: 5000
        });
      }

      return Promise.reject(error);
    }
  );

  app.config.globalProperties.$axios = axios;
  app.config.globalProperties.$api = api;
});

export { api };
```

---

## API Services

Centralize all API calls in a dedicated `src/api/` folder. Each feature should have its own service file.

### Folder Structure

```
src/
├── api/
│   ├── index.js           # Re-exports all services
│   ├── auth.api.js        # Authentication endpoints
│   ├── account.api.js     # Account management endpoints
│   └── session.api.js     # Session management endpoints
```

### Authentication API Service

```javascript
// src/api/auth.api.js
import { api } from 'src/boot/axios';

/**
 * Authentication API service
 * Handles login, logout, and 2FA verification
 */
export const authApi = {
  /**
   * Authenticate user with email/password
   * @param {string} id - Email, phone, or username
   * @param {string} password - User password
   * @param {number} loginCode - 1=email, 2=phone, 3=username
   * @returns {Promise} Response with JWT or 2FA challenge
   */
  login(id, password, loginCode = 1) {
    return api.post('/authenticate', { id, password, loginCode });
  },

  /**
   * Verify OTP for 2FA
   * @param {string} loginInfoId - ID from login response
   * @param {string} otp - One-time password
   * @returns {Promise} Response confirming login success
   */
  verifyOtp(loginInfoId, otp) {
    return api.post('/otp', { loginInfoId, otp });
  },

  /**
   * Logout current user
   * @returns {Promise} Logout confirmation
   */
  logout() {
    return api.post('/api/logout');
  },

  /**
   * Check current authentication status
   * @returns {Promise} Current username or empty
   */
  checkAuth() {
    return api.get('/v1/account/authenticate');
  }
};
```

### Account API Service

```javascript
// src/api/account.api.js
import { api } from 'src/boot/axios';

/**
 * Account API service
 * Handles registration, activation, and account management
 */
export const accountApi = {
  /**
   * Register new user account
   * @param {Object} userData - Registration data
   * @returns {Promise} Registration confirmation
   */
  register(userData) {
    return api.post('/v1/account/register', userData);
  },

  /**
   * Activate account with key from email
   * @param {string} key - Activation key
   * @returns {Promise} Activation confirmation
   */
  activate(key) {
    return api.post(`/v1/account/activate?key=${encodeURIComponent(key)}`);
  },

  /**
   * Resend activation link
   * @param {string} login - Username
   * @param {string} password - Password
   * @returns {Promise} Confirmation
   */
  resendActivation(login, password) {
    return api.post(`/v1/account/regislink?login=${encodeURIComponent(login)}&password=${encodeURIComponent(password)}`);
  },

  /**
   * Request password reset
   * @param {string} email - User email
   * @param {string} dob - Date of birth (yyyy-MM-dd)
   * @returns {Promise} Confirmation
   */
  requestPasswordReset(email, dob) {
    return api.post('/v1/account/reset_password/init', {
      loginId: email,
      dob: dob,
      currentEmail: email
    });
  },

  /**
   * Complete password reset
   * @param {string} key - Reset token from email
   * @param {string} password - New password
   * @returns {Promise} Confirmation
   */
  resetPassword(key, password) {
    return api.post('/v1/account/reset_password/finish', { key, password });
  },

  /**
   * Get current user account details
   * @returns {Promise} User account data
   */
  getCurrentUser() {
    return api.get('/v1/account/');
  },

  /**
   * Change email address
   * @param {string} oldEmail - Current email
   * @param {string} newEmail - New email
   * @param {string} password - Current password
   * @returns {Promise} Confirmation
   */
  changeEmail(oldEmail, newEmail, password) {
    return api.post('/v1/account/change_email', { oldEmail, newEmail, password });
  }
};
```

### Session API Service

```javascript
// src/api/session.api.js
import { api } from 'src/boot/axios';

/**
 * Session API service
 * Handles session refresh and health checks
 */
export const sessionApi = {
  /**
   * Refresh current session
   * @returns {Promise} Session refresh confirmation
   */
  refresh() {
    return api.get('/refresh');
  },

  /**
   * Health check / ping
   * @returns {Promise} Server status
   */
  ping() {
    return api.get('/v1/account/ping');
  }
};
```

### API Index (Re-exports)

```javascript
// src/api/index.js
export { authApi } from './auth.api';
export { accountApi } from './account.api';
export { sessionApi } from './session.api';
```

### Using API Services in Components

```javascript
// In a component or store
import { authApi, accountApi } from 'src/api';

// Login example
async function handleLogin(email, password) {
  const response = await authApi.login(email, password);
  return response.data;
}

// Register example
async function handleRegister(formData) {
  const response = await accountApi.register(formData);
  return response.data;
}
```

---

## Error Handling

The backend uses a uniform error response structure handled by `ApiAdvice.java`. All errors follow the same format, making it easy to handle them consistently in the frontend.

### Error Response Structure

All API errors follow this JSON structure:

```javascript
// Error Response Object
{
  helpCode: 'abc123',           // Unique support code for tracking issues
  errorMsg: {
    errorKey: 'security.badCreds',  // Translation key
    message: 'Invalid email or password'  // Human-readable message
  },
  fieldErrors: [                // Present for validation errors (optional)
    {
      objectName: 'registrationDto',  // Form/DTO name
      field: 'email',                  // Field name
      errorMsg: {
        errorKey: 'invalid.email',
        message: 'Please enter a valid email'
      }
    }
  ]
}
```

### Error Keys Reference

The backend returns specific error keys that can be used for frontend translation:

| Error Key | HTTP Status | Description |
|-----------|-------------|-------------|
| `generic.unknown` | 500 | Unknown server error |
| `generic.dataError` | 400 | Data integrity error |
| `security.unauthorized` | 401 | Unauthorized access |
| `security.authError` | 401 | Authentication issue |
| `security.accountExpired` | 401 | Account has expired |
| `security.credExpired` | 401 | Credentials have expired |
| `security.accNotEnabled` | 401 | Account not activated |
| `security.accLocked` | 401 | Account locked (too many failed attempts) |
| `security.badCreds` | 401 | Invalid login/password |
| `security.opForbidden` | 403 | Operation not allowed |
| `security.sessionExpired` | 401 | Session/JWT expired |
| `security.generic` | 401 | Generic security error |
| `validation.badRequest` | 400 | Missing or invalid parameter |
| `validation.invalidData` | 400 | Form validation failed (check fieldErrors) |

### Error Handler Utility

Create a centralized error handler to process API errors:

```javascript
// src/utils/errorHandler.js
import { useI18n } from 'vue-i18n';

/**
 * Parses the API error response and returns a structured error object
 */
export function parseApiError(error) {
  const response = error.response?.data;

  if (!response) {
    return {
      helpCode: null,
      errorKey: 'generic.network',
      message: 'Network error. Please check your connection.',
      fieldErrors: [],
      isValidationError: false
    };
  }

  return {
    helpCode: response.helpCode || null,
    errorKey: response.errorMsg?.errorKey || 'generic.unknown',
    message: response.errorMsg?.message || 'An unexpected error occurred',
    fieldErrors: response.fieldErrors || [],
    isValidationError: response.errorMsg?.errorKey === 'validation.invalidData'
  };
}

/**
 * Extracts field-specific errors into a map for form binding
 * @returns Object with field names as keys and error messages as values
 */
export function extractFieldErrors(error) {
  const parsed = parseApiError(error);
  const fieldErrorMap = {};

  if (parsed.fieldErrors && parsed.fieldErrors.length > 0) {
    parsed.fieldErrors.forEach(fe => {
      fieldErrorMap[fe.field] = fe.errorMsg?.message || 'Invalid value';
    });
  }

  return fieldErrorMap;
}

/**
 * Gets the primary error message for display
 */
export function getErrorMessage(error) {
  const parsed = parseApiError(error);
  return parsed.message;
}

/**
 * Gets the help code for support reference
 */
export function getHelpCode(error) {
  const parsed = parseApiError(error);
  return parsed.helpCode;
}
```

### Error Store (Pinia)

Create a dedicated store for managing errors:

```javascript
// src/stores/error.js
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { parseApiError, extractFieldErrors } from 'src/utils/errorHandler';

export const useErrorStore = defineStore('error', () => {
  // State
  const currentError = ref(null);
  const fieldErrors = ref({});

  // Computed
  const hasError = computed(() => currentError.value !== null);
  const errorMessage = computed(() => currentError.value?.message || '');
  const errorKey = computed(() => currentError.value?.errorKey || '');
  const helpCode = computed(() => currentError.value?.helpCode || '');
  const isValidationError = computed(() => currentError.value?.isValidationError || false);

  // Actions
  function setError(error) {
    currentError.value = parseApiError(error);
    fieldErrors.value = extractFieldErrors(error);
  }

  function clearError() {
    currentError.value = null;
    fieldErrors.value = {};
  }

  function getFieldError(fieldName) {
    return fieldErrors.value[fieldName] || null;
  }

  function hasFieldError(fieldName) {
    return !!fieldErrors.value[fieldName];
  }

  return {
    // State
    currentError,
    fieldErrors,
    // Computed
    hasError,
    errorMessage,
    errorKey,
    helpCode,
    isValidationError,
    // Actions
    setError,
    clearError,
    getFieldError,
    hasFieldError
  };
});
```

### Error Alert Component

Create a reusable error alert component:

```vue
<!-- src/components/ErrorAlert.vue -->
<template>
  <q-banner
    v-if="errorStore.hasError"
    class="bg-negative text-white q-mb-md"
    rounded
  >
    <template v-slot:avatar>
      <q-icon name="error" />
    </template>

    <div class="text-body1">{{ translatedMessage }}</div>

    <div v-if="errorStore.helpCode" class="text-caption q-mt-sm">
      {{ $t('error.helpCode') }}: {{ errorStore.helpCode }}
    </div>

    <template v-slot:action>
      <q-btn
        flat
        dense
        icon="close"
        @click="errorStore.clearError()"
      />
    </template>
  </q-banner>
</template>

<script setup>
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useErrorStore } from 'stores/error';

const { t, te } = useI18n();
const errorStore = useErrorStore();

// Try to translate the error key, fallback to server message
const translatedMessage = computed(() => {
  const key = `errors.${errorStore.errorKey}`;
  if (te(key)) {
    return t(key);
  }
  return errorStore.errorMessage;
});
</script>
```

### Field Error Display Component

Create a component for displaying field-level errors:

```vue
<!-- src/components/FieldError.vue -->
<template>
  <div v-if="error" class="text-negative text-caption q-mt-xs">
    <q-icon name="error" size="xs" class="q-mr-xs" />
    {{ error }}
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useErrorStore } from 'stores/error';

const props = defineProps({
  field: {
    type: String,
    required: true
  }
});

const errorStore = useErrorStore();

const error = computed(() => errorStore.getFieldError(props.field));
</script>
```

### Using Error Handling in Forms

Example of integrating error handling in a form:

```vue
<template>
  <q-form @submit.prevent="handleSubmit">
    <!-- Global error alert -->
    <ErrorAlert />

    <q-input
      v-model="form.email"
      label="Email"
      :error="errorStore.hasFieldError('email')"
      :error-message="errorStore.getFieldError('email')"
      outlined
    />

    <q-input
      v-model="form.password"
      label="Password"
      type="password"
      :error="errorStore.hasFieldError('password')"
      :error-message="errorStore.getFieldError('password')"
      outlined
    />

    <q-btn type="submit" label="Submit" color="primary" :loading="loading" />
  </q-form>
</template>

<script setup>
import { ref, reactive, onUnmounted } from 'vue';
import { api } from 'src/boot/axios';
import { useErrorStore } from 'stores/error';
import ErrorAlert from 'components/ErrorAlert.vue';

const errorStore = useErrorStore();
const loading = ref(false);

const form = reactive({
  email: '',
  password: ''
});

async function handleSubmit() {
  loading.value = true;
  errorStore.clearError();

  try {
    await api.post('/authenticate', form);
    // Handle success
  } catch (error) {
    errorStore.setError(error);
  } finally {
    loading.value = false;
  }
}

// Clear errors when leaving the page
onUnmounted(() => {
  errorStore.clearError();
});
</script>
```

### Axios Interceptor with Error Handling

Update the axios boot file to integrate with error handling:

```javascript
// src/boot/axios.js (updated)
import { boot } from 'quasar/wrappers';
import axios from 'axios';
import { Notify } from 'quasar';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080',
  withCredentials: true,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

export default boot(({ app, router }) => {
  // Response interceptor
  api.interceptors.response.use(
    response => response,
    error => {
      const status = error.response?.status;
      const errorData = error.response?.data;

      // Handle session expiration
      if (status === 401) {
        const errorKey = errorData?.errorMsg?.errorKey;

        if (errorKey === 'security.sessionExpired') {
          const currentPath = router.currentRoute.value.fullPath;
          if (currentPath !== '/login') {
            router.push({
              path: '/login',
              query: { redirect: currentPath, expired: 'true' }
            });
          }
        }
      }

      // Handle server errors with notification
      if (status >= 500) {
        const helpCode = errorData?.helpCode;
        Notify.create({
          type: 'negative',
          message: 'A server error occurred. Please try again later.',
          caption: helpCode ? `Help Code: ${helpCode}` : undefined,
          position: 'top',
          timeout: 5000
        });
      }

      // Handle network errors
      if (!error.response) {
        Notify.create({
          type: 'negative',
          message: 'Network error. Please check your connection.',
          position: 'top',
          timeout: 5000
        });
      }

      return Promise.reject(error);
    }
  );

  app.config.globalProperties.$axios = axios;
  app.config.globalProperties.$api = api;
});

export { api };
```

---

## Internationalization (i18n)

The frontend supports English and French translations. The backend sends localized error messages based on the user's language preference (via the `lang` cookie).

### Install vue-i18n

```bash
npm install vue-i18n@9
```

### Configure i18n Boot File

```javascript
// src/boot/i18n.js
import { boot } from 'quasar/wrappers';
import { createI18n } from 'vue-i18n';
import messages from 'src/i18n';

const i18n = createI18n({
  locale: 'en',
  fallbackLocale: 'en',
  legacy: false,
  globalInjection: true,
  messages
});

export default boot(({ app }) => {
  app.use(i18n);

  // Set initial locale from cookie or browser
  const langCookie = document.cookie
    .split('; ')
    .find(row => row.startsWith('lang='));

  if (langCookie) {
    const lang = langCookie.split('=')[1];
    if (['en', 'fr'].includes(lang)) {
      i18n.global.locale.value = lang;
    }
  } else {
    // Fallback to browser language
    const browserLang = navigator.language.split('-')[0];
    if (['en', 'fr'].includes(browserLang)) {
      i18n.global.locale.value = browserLang;
    }
  }
});

export { i18n };
```

### Update quasar.config.js

```javascript
// quasar.config.js
module.exports = configure(function (ctx) {
  return {
    boot: [
      'i18n',
      'axios'
    ],
    // ... rest of config
  };
});
```

### Create Translation Files

```javascript
// src/i18n/index.js
import enUS from './en-US';
import frFR from './fr-FR';

export default {
  'en': enUS,
  'fr': frFR
};
```

### English Translations

**Important:** The English and French translation files must always be in parity. When adding a new key to one file, add it to the other immediately.

```javascript
// src/i18n/en-US/index.js
export default {
  // App
  app: {
    name: 'Skillars',
    tagline: 'Your trusted platform'
  },

  // Navigation
  nav: {
    home: 'Home',
    dashboard: 'Dashboard',
    profile: 'Profile',
    settings: 'Settings',
    logout: 'Logout'
  },

  // Authentication
  auth: {
    login: 'Login',
    register: 'Create Account',
    forgotPassword: 'Forgot Password',
    forgotPasswordDesc: 'Enter your email and date of birth to receive a password reset link.',
    resetPassword: 'Reset Password',
    logout: 'Logout',
    email: 'Email',
    password: 'Password',
    confirmPassword: 'Confirm Password',
    newPassword: 'New Password',
    username: 'Username',
    firstName: 'First Name',
    lastName: 'Last Name',
    dateOfBirth: 'Date of Birth',
    gender: 'Gender',
    phone: 'Phone Number',
    rememberMe: 'Remember me',
    noAccount: "Don't have an account?",
    hasAccount: 'Already have an account?',
    signUp: 'Sign up',
    signIn: 'Sign in',
    verificationCode: 'Verification Code',
    twoFactorAuth: 'Two-Factor Authentication',
    enableTwoFactor: 'Enable Two-Factor Authentication (Recommended)',
    otpSent: 'Enter the verification code sent to your email',
    backToLogin: 'Back to Login',
    sendResetLink: 'Send Reset Link',
    checkEmail: 'Check Your Email',
    checkEmailDesc: 'If an account exists for {email}, you will receive a password reset link.',
    passwordResetComplete: 'Password Reset Complete',
    passwordResetDesc: 'Your password has been reset. You can now login with your new password.',
    goToLogin: 'Go to Login',
    activating: 'Activating your account...',
    accountActivated: 'Account Activated',
    accountActivatedDesc: 'Your account has been activated. You can now login.',
    activationFailed: 'Activation Failed',
    registrationSuccess: 'Registration Successful',
    registrationSuccessDesc: 'Please check your email for the activation link.'
  },

  // Gender options
  gender: {
    male: 'Male',
    female: 'Female',
    other: 'Other'
  },

  // Session
  session: {
    expiring: 'Session Expiring',
    expiringDesc: 'Your session will expire in {minutes} minute(s).',
    continueQuestion: 'Do you want to continue your session?',
    continueSession: 'Continue Session',
    expired: 'Your session has expired. Please login again.',
    refreshed: 'Session refreshed successfully'
  },

  // Errors - mapped to backend error keys
  errors: {
    'generic.unknown': 'An unexpected error has occurred. Please try again later.',
    'generic.network': 'Network error. Please check your connection.',
    'generic.dataError': 'A data error occurred. Please verify your input.',
    'security.unauthorized': 'You are not authorized to access this resource.',
    'security.authError': 'Authentication failed. Please try again.',
    'security.accountExpired': 'Your account has expired. Please contact support.',
    'security.credExpired': 'Your credentials have expired. Please reset your password.',
    'security.accNotEnabled': 'Your account is not activated. Please check your email for the activation link.',
    'security.accLocked': 'Your account has been locked due to too many failed attempts. Please try again later or contact support.',
    'security.badCreds': 'Invalid email or password. Please try again.',
    'security.opForbidden': 'This operation is not allowed. Please contact support.',
    'security.sessionExpired': 'Your session has expired. Please login again.',
    'security.generic': 'A security error occurred. Please contact support.',
    'validation.badRequest': 'Invalid request. Please check your input.',
    'validation.invalidData': 'Please correct the errors in the form.'
  },

  // Error display
  error: {
    helpCode: 'Help Code',
    contactSupport: 'If the problem persists, please contact support with this code.'
  },

  // Validation messages
  validation: {
    required: 'This field is required',
    email: 'Please enter a valid email address',
    minLength: 'Must be at least {min} characters',
    maxLength: 'Must be at most {max} characters',
    passwordMatch: 'Passwords must match',
    pastDate: 'Date must be in the past',
    invalidFormat: 'Invalid format'
  },

  // Common actions
  actions: {
    submit: 'Submit',
    cancel: 'Cancel',
    save: 'Save',
    delete: 'Delete',
    edit: 'Edit',
    close: 'Close',
    confirm: 'Confirm',
    back: 'Back',
    next: 'Next',
    retry: 'Retry'
  },

  // Common messages
  messages: {
    loading: 'Loading...',
    saving: 'Saving...',
    success: 'Operation completed successfully',
    error: 'An error occurred'
  }
};
```

### French Translations

**Important:** The English and French translation files must always be in parity. When adding a new key to one file, add it to the other immediately.

```javascript
// src/i18n/fr-FR/index.js
export default {
  // App
  app: {
    name: 'Skillars',
    tagline: 'Votre plateforme de confiance'
  },

  // Navigation
  nav: {
    home: 'Accueil',
    dashboard: 'Tableau de bord',
    profile: 'Profil',
    settings: 'Paramètres',
    logout: 'Déconnexion'
  },

  // Authentication
  auth: {
    login: 'Connexion',
    register: 'Créer un compte',
    forgotPassword: 'Mot de passe oublié',
    forgotPasswordDesc: 'Entrez votre e-mail et votre date de naissance pour recevoir un lien de réinitialisation.',
    resetPassword: 'Réinitialiser le mot de passe',
    logout: 'Déconnexion',
    email: 'Adresse e-mail',
    password: 'Mot de passe',
    confirmPassword: 'Confirmer le mot de passe',
    newPassword: 'Nouveau mot de passe',
    username: "Nom d'utilisateur",
    firstName: 'Prénom',
    lastName: 'Nom',
    dateOfBirth: 'Date de naissance',
    gender: 'Genre',
    phone: 'Numéro de téléphone',
    rememberMe: 'Se souvenir de moi',
    noAccount: "Vous n'avez pas de compte ?",
    hasAccount: 'Vous avez déjà un compte ?',
    signUp: "S'inscrire",
    signIn: 'Se connecter',
    verificationCode: 'Code de vérification',
    twoFactorAuth: 'Authentification à deux facteurs',
    enableTwoFactor: "Activer l'authentification à deux facteurs (Recommandé)",
    otpSent: 'Entrez le code de vérification envoyé à votre adresse e-mail',
    backToLogin: 'Retour à la connexion',
    sendResetLink: 'Envoyer le lien de réinitialisation',
    checkEmail: 'Vérifiez votre e-mail',
    checkEmailDesc: 'Si un compte existe pour {email}, vous recevrez un lien de réinitialisation.',
    passwordResetComplete: 'Réinitialisation terminée',
    passwordResetDesc: 'Votre mot de passe a été réinitialisé. Vous pouvez maintenant vous connecter.',
    goToLogin: 'Aller à la connexion',
    activating: 'Activation de votre compte...',
    accountActivated: 'Compte activé',
    accountActivatedDesc: 'Votre compte a été activé. Vous pouvez maintenant vous connecter.',
    activationFailed: "Échec de l'activation",
    registrationSuccess: 'Inscription réussie',
    registrationSuccessDesc: "Veuillez vérifier votre e-mail pour le lien d'activation."
  },

  // Gender options
  gender: {
    male: 'Homme',
    female: 'Femme',
    other: 'Autre'
  },

  // Session
  session: {
    expiring: 'Session expirante',
    expiringDesc: 'Votre session expirera dans {minutes} minute(s).',
    continueQuestion: 'Voulez-vous continuer votre session ?',
    continueSession: 'Continuer la session',
    expired: 'Votre session a expiré. Veuillez vous reconnecter.',
    refreshed: 'Session actualisée avec succès'
  },

  // Errors - mapped to backend error keys
  errors: {
    'generic.unknown': 'Une erreur inattendue s\'est produite. Veuillez réessayer plus tard.',
    'generic.network': 'Erreur réseau. Veuillez vérifier votre connexion.',
    'generic.dataError': 'Une erreur de données s\'est produite. Veuillez vérifier votre saisie.',
    'security.unauthorized': 'Vous n\'êtes pas autorisé à accéder à cette ressource.',
    'security.authError': 'Échec de l\'authentification. Veuillez réessayer.',
    'security.accountExpired': 'Votre compte a expiré. Veuillez contacter le support.',
    'security.credExpired': 'Vos identifiants ont expiré. Veuillez réinitialiser votre mot de passe.',
    'security.accNotEnabled': 'Votre compte n\'est pas activé. Veuillez vérifier votre e-mail pour le lien d\'activation.',
    'security.accLocked': 'Votre compte a été verrouillé en raison de trop nombreuses tentatives échouées. Veuillez réessayer plus tard ou contacter le support.',
    'security.badCreds': 'Adresse e-mail ou mot de passe invalide. Veuillez réessayer.',
    'security.opForbidden': 'Cette opération n\'est pas autorisée. Veuillez contacter le support.',
    'security.sessionExpired': 'Votre session a expiré. Veuillez vous reconnecter.',
    'security.generic': 'Une erreur de sécurité s\'est produite. Veuillez contacter le support.',
    'validation.badRequest': 'Requête invalide. Veuillez vérifier votre saisie.',
    'validation.invalidData': 'Veuillez corriger les erreurs dans le formulaire.'
  },

  // Error display
  error: {
    helpCode: 'Code d\'aide',
    contactSupport: 'Si le problème persiste, veuillez contacter le support avec ce code.'
  },

  // Validation messages
  validation: {
    required: 'Ce champ est obligatoire',
    email: 'Veuillez entrer une adresse e-mail valide',
    minLength: 'Doit contenir au moins {min} caractères',
    maxLength: 'Doit contenir au maximum {max} caractères',
    passwordMatch: 'Les mots de passe doivent correspondre',
    pastDate: 'La date doit être dans le passé',
    invalidFormat: 'Format invalide'
  },

  // Common actions
  actions: {
    submit: 'Soumettre',
    cancel: 'Annuler',
    save: 'Enregistrer',
    delete: 'Supprimer',
    edit: 'Modifier',
    close: 'Fermer',
    confirm: 'Confirmer',
    back: 'Retour',
    next: 'Suivant',
    retry: 'Réessayer'
  },

  // Common messages
  messages: {
    loading: 'Chargement...',
    saving: 'Enregistrement...',
    success: 'Opération terminée avec succès',
    error: 'Une erreur s\'est produite'
  }
};
```

### Language Switcher Component

```vue
<!-- src/components/LanguageSwitcher.vue -->
<template>
  <q-btn-dropdown
    flat
    :label="currentLanguageLabel"
    icon="language"
  >
    <q-list>
      <q-item
        v-for="lang in languages"
        :key="lang.value"
        clickable
        v-close-popup
        @click="changeLanguage(lang.value)"
      >
        <q-item-section avatar>
          <q-icon :name="locale === lang.value ? 'check' : ''" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ lang.label }}</q-item-label>
          <q-item-label caption>{{ lang.native }}</q-item-label>
        </q-item-section>
      </q-item>
    </q-list>
  </q-btn-dropdown>
</template>

<script setup>
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from 'src/boot/axios';

const { locale } = useI18n();

const languages = [
  { value: 'en', label: 'English', native: 'English' },
  { value: 'fr', label: 'French', native: 'Français' }
];

const currentLanguageLabel = computed(() => {
  const lang = languages.find(l => l.value === locale.value);
  return lang ? lang.native : 'English';
});

function changeLanguage(lang) {
  locale.value = lang;

  // Set cookie for backend and persistence
  document.cookie = `lang=${lang};path=/;max-age=31536000`; // 1 year

  // Optionally notify backend of language change
  // api.post('/v1/account/language', { langKey: lang }).catch(() => {});
}
</script>
```

### Using Translations in Components

All page components should use i18n and follow the development guidelines. See the Page Components section for complete examples.

---

## Authentication Service

The auth store uses the centralized API services and follows the development guidelines.

### Auth Store (Pinia)

```javascript
// src/stores/auth.js
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { authApi, accountApi, sessionApi } from 'src/api';
import { parseApiError } from 'src/utils/errorHandler';

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref(null);
  const isAuthenticated = ref(false);
  const isAdmin = ref(false);

  // Computed
  const displayName = computed(() => {
    if (user.value?.displayName) return user.value.displayName;
    if (user.value?.firstName) return `${user.value.firstName} ${user.value.lastName}`;
    return '';
  });

  // Initialize from cookies
  function initFromCookies() {
    const userCookie = getCookie('user');
    const adminCookie = getCookie('admin');

    if (userCookie) {
      isAuthenticated.value = true;
      user.value = { displayName: decodeURIComponent(userCookie) };
      isAdmin.value = adminCookie === 'true';
    } else {
      isAuthenticated.value = false;
      user.value = null;
      isAdmin.value = false;
    }
  }

  // Logout - clears state
  async function logout() {
    try {
      await authApi.logout();
    } finally {
      user.value = null;
      isAuthenticated.value = false;
      isAdmin.value = false;
    }
  }

  // Refresh session
  async function refreshSession() {
    try {
      await sessionApi.refresh();
      return true;
    } catch {
      return false;
    }
  }

  // Get current user details from API
  async function fetchCurrentUser() {
    const response = await accountApi.getCurrentUser();
    user.value = response.data;
    return response.data;
  }

  // Helper: Read cookie by name
  function getCookie(name) {
    const value = `; ${document.cookie}`;
    const parts = value.split(`; ${name}=`);
    if (parts.length === 2) return parts.pop().split(';').shift();
    return null;
  }

  return {
    // State
    user,
    isAuthenticated,
    isAdmin,
    // Computed
    displayName,
    // Actions
    initFromCookies,
    logout,
    refreshSession,
    fetchCurrentUser
  };
});
```

**Note:** The store is simplified to only manage auth state. API calls are made directly using the API services in page components, with loading and error states managed locally. This follows the single-responsibility principle.

---

## Page Components

All page components follow the development guidelines:
- `<script setup>` with Composition API
- Plain JavaScript (no TypeScript)
- `lazy-rules` for validation on blur
- `:loading` and `:disable` on submit buttons
- Mobile-first responsive design
- i18n for all user-facing text
- Cleanup in `onUnmounted`
- Uses centralized API services

### Login Page

```vue
<!-- src/pages/auth/LoginPage.vue -->
<template>
  <q-page class="row justify-center items-center q-pa-md">
    <q-card class="col-12 col-sm-8 col-md-6 col-lg-4">
      <q-card-section class="bg-primary text-white row items-center justify-between">
        <div class="text-h5">{{ $t('auth.login') }}</div>
        <LanguageSwitcher />
      </q-card-section>

      <!-- Login Form -->
      <q-card-section v-if="!requires2FA">
        <q-form
          ref="loginFormRef"
          @submit.prevent="handleLogin"
          class="q-gutter-md"
        >
          <q-input
            v-model="email"
            :label="$t('auth.email')"
            type="email"
            lazy-rules
            :rules="emailRules"
            :error="errorStore.hasFieldError('id')"
            :error-message="errorStore.getFieldError('id')"
            outlined
            aria-label="Email address"
          >
            <template v-slot:prepend>
              <q-icon name="email" />
            </template>
          </q-input>

          <q-input
            v-model="password"
            :label="$t('auth.password')"
            :type="showPassword ? 'text' : 'password'"
            lazy-rules
            :rules="passwordRules"
            :error="errorStore.hasFieldError('password')"
            :error-message="errorStore.getFieldError('password')"
            outlined
            aria-label="Password"
          >
            <template v-slot:prepend>
              <q-icon name="lock" />
            </template>
            <template v-slot:append>
              <q-icon
                :name="showPassword ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                role="button"
                :aria-label="showPassword ? 'Hide password' : 'Show password'"
                @click="showPassword = !showPassword"
              />
            </template>
          </q-input>

          <ErrorAlert />

          <q-banner
            v-if="sessionExpired"
            class="bg-warning text-white q-mb-md"
            rounded
          >
            <template v-slot:avatar>
              <q-icon name="warning" />
            </template>
            {{ $t('session.expired') }}
          </q-banner>

          <div class="row justify-between items-center">
            <router-link
              to="/forgot-password"
              class="text-primary"
            >
              {{ $t('auth.forgotPassword') }}?
            </router-link>
          </div>

          <q-btn
            type="submit"
            :label="$t('auth.login')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
            aria-label="Login"
          />

          <div class="text-center q-mt-md">
            {{ $t('auth.noAccount') }}
            <router-link to="/register" class="text-primary">
              {{ $t('auth.signUp') }}
            </router-link>
          </div>
        </q-form>
      </q-card-section>

      <!-- 2FA OTP Form -->
      <q-card-section v-else>
        <div class="text-subtitle1 q-mb-md">
          {{ $t('auth.otpSent') }}
        </div>

        <q-form
          ref="otpFormRef"
          @submit.prevent="handleOTPVerify"
          class="q-gutter-md"
        >
          <q-input
            v-model="otpCode"
            :label="$t('auth.verificationCode')"
            mask="######"
            lazy-rules
            :rules="otpRules"
            outlined
            autofocus
            aria-label="Verification code"
          >
            <template v-slot:prepend>
              <q-icon name="security" />
            </template>
          </q-input>

          <ErrorAlert />

          <q-btn
            type="submit"
            :label="$t('actions.confirm')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
            aria-label="Verify code"
          />

          <q-btn
            flat
            :label="$t('auth.backToLogin')"
            color="grey"
            class="full-width"
            :disable="loading"
            @click="cancelOTP"
            aria-label="Back to login"
          />
        </q-form>
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useQuasar } from 'quasar';
import { useAuthStore } from 'stores/auth';
import { useErrorStore } from 'stores/error';
import { authApi } from 'src/api';
import ErrorAlert from 'components/common/ErrorAlert.vue';
import LanguageSwitcher from 'components/common/LanguageSwitcher.vue';

const router = useRouter();
const route = useRoute();
const { t } = useI18n();
const $q = useQuasar();
const authStore = useAuthStore();
const errorStore = useErrorStore();

// Form refs
const loginFormRef = ref(null);
const otpFormRef = ref(null);

// State
const email = ref('');
const password = ref('');
const showPassword = ref(false);
const otpCode = ref('');
const loading = ref(false);
const requires2FA = ref(false);
const loginInfoId = ref(null);

// Abort controller for cleanup
let abortController = null;

// Computed
const sessionExpired = computed(() => route.query.expired === 'true');

// Validation rules (lazy-rules validates on blur)
const emailRules = [
  val => !!val || t('validation.required'),
  val => isValidEmail(val) || t('validation.email')
];

const passwordRules = [
  val => !!val || t('validation.required')
];

const otpRules = [
  val => !!val || t('validation.required'),
  val => val.length === 6 || t('validation.minLength', { min: 6 })
];

// Lifecycle
onMounted(() => {
  authStore.initFromCookies();
  if (authStore.isAuthenticated) {
    navigateAfterLogin();
  }
});

onUnmounted(() => {
  // Cleanup: cancel pending requests
  if (abortController) {
    abortController.abort();
  }
  errorStore.clearError();
});

// Methods
function isValidEmail(email) {
  return /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i.test(email);
}

function navigateAfterLogin() {
  const redirect = route.query.redirect || '/dashboard';
  router.push(redirect);
}

async function handleLogin() {
  const valid = await loginFormRef.value.validate();
  if (!valid) return;

  loading.value = true;
  errorStore.clearError();
  abortController = new AbortController();

  try {
    const response = await authApi.login(email.value, password.value);

    if (response.data.message === 'check.otp') {
      // 2FA required
      requires2FA.value = true;
      loginInfoId.value = response.data.data.loginInfoId;
    } else {
      // Login successful
      authStore.initFromCookies();
      $q.notify({
        type: 'positive',
        message: t('messages.success'),
        position: 'top'
      });
      navigateAfterLogin();
    }
  } catch (err) {
    if (err.name !== 'AbortError') {
      errorStore.setError(err);
    }
  } finally {
    loading.value = false;
  }
}

async function handleOTPVerify() {
  const valid = await otpFormRef.value.validate();
  if (!valid) return;

  loading.value = true;
  errorStore.clearError();

  try {
    await authApi.verifyOtp(loginInfoId.value, otpCode.value);
    authStore.initFromCookies();
    $q.notify({
      type: 'positive',
      message: t('messages.success'),
      position: 'top'
    });
    navigateAfterLogin();
  } catch (err) {
    errorStore.setError(err);
  } finally {
    loading.value = false;
  }
}

function cancelOTP() {
  requires2FA.value = false;
  loginInfoId.value = null;
  otpCode.value = '';
  errorStore.clearError();
}
</script>
```

### Registration Page

```vue
<!-- src/pages/auth/RegisterPage.vue -->
<template>
  <q-page class="row justify-center items-center q-pa-md">
    <q-card class="col-12 col-sm-10 col-md-8 col-lg-5">
      <q-card-section class="bg-primary text-white row items-center justify-between">
        <div class="text-h5">{{ $t('auth.register') }}</div>
        <LanguageSwitcher />
      </q-card-section>

      <q-card-section>
        <q-form
          ref="formRef"
          @submit.prevent="handleRegister"
          class="q-gutter-md"
        >
          <!-- Name Row - Responsive -->
          <div class="row q-col-gutter-md">
            <div class="col-12 col-sm-6">
              <q-input
                v-model="form.firstName"
                :label="$t('auth.firstName')"
                lazy-rules
                :rules="firstNameRules"
                :error="errorStore.hasFieldError('firstName')"
                :error-message="errorStore.getFieldError('firstName')"
                outlined
                aria-label="First name"
              />
            </div>
            <div class="col-12 col-sm-6">
              <q-input
                v-model="form.lastName"
                :label="$t('auth.lastName')"
                lazy-rules
                :rules="lastNameRules"
                :error="errorStore.hasFieldError('lastName')"
                :error-message="errorStore.getFieldError('lastName')"
                outlined
                aria-label="Last name"
              />
            </div>
          </div>

          <q-input
            v-model="form.login"
            :label="$t('auth.username')"
            lazy-rules
            :rules="usernameRules"
            :error="errorStore.hasFieldError('login')"
            :error-message="errorStore.getFieldError('login')"
            outlined
            aria-label="Username"
          >
            <template v-slot:prepend>
              <q-icon name="person" />
            </template>
          </q-input>

          <q-input
            v-model="form.email"
            :label="$t('auth.email')"
            type="email"
            lazy-rules
            :rules="emailRules"
            :error="errorStore.hasFieldError('email')"
            :error-message="errorStore.getFieldError('email')"
            outlined
            aria-label="Email address"
          >
            <template v-slot:prepend>
              <q-icon name="email" />
            </template>
          </q-input>

          <q-input
            v-model="form.password"
            :label="$t('auth.password')"
            :type="showPassword ? 'text' : 'password'"
            lazy-rules
            :rules="passwordRules"
            :error="errorStore.hasFieldError('password')"
            :error-message="errorStore.getFieldError('password')"
            outlined
            aria-label="Password"
          >
            <template v-slot:prepend>
              <q-icon name="lock" />
            </template>
            <template v-slot:append>
              <q-icon
                :name="showPassword ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                role="button"
                :aria-label="showPassword ? 'Hide password' : 'Show password'"
                @click="showPassword = !showPassword"
              />
            </template>
          </q-input>

          <q-input
            v-model="confirmPassword"
            :label="$t('auth.confirmPassword')"
            :type="showPassword ? 'text' : 'password'"
            lazy-rules
            :rules="confirmPasswordRules"
            outlined
            aria-label="Confirm password"
          >
            <template v-slot:prepend>
              <q-icon name="lock" />
            </template>
          </q-input>

          <q-input
            v-model="form.dob"
            :label="$t('auth.dateOfBirth')"
            type="date"
            lazy-rules
            :rules="dobRules"
            :error="errorStore.hasFieldError('dob')"
            :error-message="errorStore.getFieldError('dob')"
            outlined
            aria-label="Date of birth"
          >
            <template v-slot:prepend>
              <q-icon name="cake" />
            </template>
          </q-input>

          <q-select
            v-model="form.gender"
            :label="$t('auth.gender')"
            :options="genderOptions"
            emit-value
            map-options
            outlined
            aria-label="Gender"
          />

          <q-toggle
            v-model="form.otpEnabled"
            :label="$t('auth.enableTwoFactor')"
          />

          <ErrorAlert />

          <q-btn
            type="submit"
            :label="$t('auth.register')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
            aria-label="Create account"
          />

          <div class="text-center q-mt-md">
            {{ $t('auth.hasAccount') }}
            <router-link to="/login" class="text-primary">
              {{ $t('auth.signIn') }}
            </router-link>
          </div>
        </q-form>
      </q-card-section>
    </q-card>

    <!-- Success Dialog -->
    <q-dialog v-model="showSuccess" persistent>
      <q-card>
        <q-card-section class="row items-center">
          <q-avatar icon="check_circle" color="positive" text-color="white" />
          <span class="q-ml-sm text-h6">{{ $t('auth.registrationSuccess') }}</span>
        </q-card-section>
        <q-card-section>
          {{ $t('auth.registrationSuccessDesc') }}
        </q-card-section>
        <q-card-actions align="right">
          <q-btn
            flat
            :label="$t('auth.goToLogin')"
            color="primary"
            @click="goToLogin"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, reactive, computed, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useQuasar } from 'quasar';
import { useErrorStore } from 'stores/error';
import { accountApi } from 'src/api';
import ErrorAlert from 'components/common/ErrorAlert.vue';
import LanguageSwitcher from 'components/common/LanguageSwitcher.vue';

const router = useRouter();
const { t, locale } = useI18n();
const $q = useQuasar();
const errorStore = useErrorStore();

// Form ref
const formRef = ref(null);

// State
const loading = ref(false);
const showPassword = ref(false);
const showSuccess = ref(false);
const confirmPassword = ref('');

const form = reactive({
  login: '',
  password: '',
  firstName: '',
  lastName: '',
  email: '',
  dob: '',
  gender: null,
  otpEnabled: true,
  langKey: locale.value
});

// Gender options (computed for i18n)
const genderOptions = computed(() => [
  { label: t('gender.male'), value: 'MALE' },
  { label: t('gender.female'), value: 'FEMALE' },
  { label: t('gender.other'), value: 'OTHER' }
]);

// Validation rules
const firstNameRules = [
  val => !!val || t('validation.required'),
  val => val.length <= 50 || t('validation.maxLength', { max: 50 })
];

const lastNameRules = [
  val => !!val || t('validation.required'),
  val => val.length <= 50 || t('validation.maxLength', { max: 50 })
];

const usernameRules = [
  val => !!val || t('validation.required'),
  val => (val.length >= 1 && val.length <= 50) || t('validation.minLength', { min: 1 })
];

const emailRules = [
  val => !!val || t('validation.required'),
  val => isValidEmail(val) || t('validation.email')
];

const passwordRules = [
  val => !!val || t('validation.required'),
  val => val.length >= 5 || t('validation.minLength', { min: 5 })
];

const confirmPasswordRules = computed(() => [
  val => val === form.password || t('validation.passwordMatch')
]);

const dobRules = [
  val => !!val || t('validation.required'),
  val => isPastDate(val) || t('validation.pastDate')
];

// Cleanup
onUnmounted(() => {
  errorStore.clearError();
});

// Methods
function isValidEmail(email) {
  return /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i.test(email);
}

function isPastDate(date) {
  return new Date(date) < new Date();
}

async function handleRegister() {
  const valid = await formRef.value.validate();
  if (!valid) return;

  loading.value = true;
  errorStore.clearError();

  try {
    // Update langKey to current locale
    form.langKey = locale.value;
    await accountApi.register(form);
    showSuccess.value = true;
  } catch (err) {
    errorStore.setError(err);
  } finally {
    loading.value = false;
  }
}

function goToLogin() {
  router.push('/login');
}
</script>
```

### Password Reset Pages

```vue
<!-- src/pages/auth/ForgotPasswordPage.vue -->
<template>
  <q-page class="row justify-center items-center q-pa-md">
    <q-card class="col-12 col-sm-8 col-md-6 col-lg-4">
      <q-card-section class="bg-primary text-white row items-center justify-between">
        <div class="text-h5">{{ $t('auth.forgotPassword') }}</div>
        <LanguageSwitcher />
      </q-card-section>

      <q-card-section v-if="!emailSent">
        <div class="text-body1 q-mb-md">
          {{ $t('auth.forgotPasswordDesc') }}
        </div>

        <q-form
          ref="formRef"
          @submit.prevent="handleSubmit"
          class="q-gutter-md"
        >
          <q-input
            v-model="email"
            :label="$t('auth.email')"
            type="email"
            lazy-rules
            :rules="emailRules"
            outlined
            aria-label="Email address"
          >
            <template v-slot:prepend>
              <q-icon name="email" />
            </template>
          </q-input>

          <q-input
            v-model="dob"
            :label="$t('auth.dateOfBirth')"
            type="date"
            lazy-rules
            :rules="dobRules"
            outlined
            aria-label="Date of birth"
          >
            <template v-slot:prepend>
              <q-icon name="cake" />
            </template>
          </q-input>

          <ErrorAlert />

          <q-btn
            type="submit"
            :label="$t('auth.sendResetLink')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
            aria-label="Send reset link"
          />

          <div class="text-center">
            <router-link to="/login" class="text-primary">
              {{ $t('auth.backToLogin') }}
            </router-link>
          </div>
        </q-form>
      </q-card-section>

      <q-card-section v-else class="text-center">
        <q-icon name="mark_email_read" size="64px" color="positive" />
        <div class="text-h6 q-mt-md">{{ $t('auth.checkEmail') }}</div>
        <div class="text-body1 q-mt-sm">
          {{ $t('auth.checkEmailDesc', { email: email }) }}
        </div>
        <q-btn
          flat
          :label="$t('auth.backToLogin')"
          color="primary"
          class="q-mt-md"
          @click="$router.push('/login')"
        />
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { ref, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useErrorStore } from 'stores/error';
import { accountApi } from 'src/api';
import ErrorAlert from 'components/common/ErrorAlert.vue';
import LanguageSwitcher from 'components/common/LanguageSwitcher.vue';

const { t } = useI18n();
const errorStore = useErrorStore();

// Form ref
const formRef = ref(null);

// State
const email = ref('');
const dob = ref('');
const emailSent = ref(false);
const loading = ref(false);

// Validation rules
const emailRules = [
  val => !!val || t('validation.required'),
  val => isValidEmail(val) || t('validation.email')
];

const dobRules = [
  val => !!val || t('validation.required')
];

// Cleanup
onUnmounted(() => {
  errorStore.clearError();
});

// Methods
function isValidEmail(email) {
  return /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i.test(email);
}

async function handleSubmit() {
  const valid = await formRef.value.validate();
  if (!valid) return;

  loading.value = true;
  errorStore.clearError();

  try {
    await accountApi.requestPasswordReset(email.value, dob.value);
    emailSent.value = true;
  } catch (err) {
    // Always show success to prevent user enumeration
    emailSent.value = true;
  } finally {
    loading.value = false;
  }
}
</script>
```

```vue
<!-- src/pages/auth/ResetPasswordPage.vue -->
<template>
  <q-page class="row justify-center items-center q-pa-md">
    <q-card class="col-12 col-sm-8 col-md-6 col-lg-4">
      <q-card-section class="bg-primary text-white row items-center justify-between">
        <div class="text-h5">{{ $t('auth.resetPassword') }}</div>
        <LanguageSwitcher />
      </q-card-section>

      <q-card-section v-if="!resetComplete">
        <q-form
          ref="formRef"
          @submit.prevent="handleSubmit"
          class="q-gutter-md"
        >
          <q-input
            v-model="password"
            :label="$t('auth.newPassword')"
            :type="showPassword ? 'text' : 'password'"
            lazy-rules
            :rules="passwordRules"
            outlined
            aria-label="New password"
          >
            <template v-slot:prepend>
              <q-icon name="lock" />
            </template>
            <template v-slot:append>
              <q-icon
                :name="showPassword ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                role="button"
                :aria-label="showPassword ? 'Hide password' : 'Show password'"
                @click="showPassword = !showPassword"
              />
            </template>
          </q-input>

          <q-input
            v-model="confirmPassword"
            :label="$t('auth.confirmPassword')"
            :type="showPassword ? 'text' : 'password'"
            lazy-rules
            :rules="confirmPasswordRules"
            outlined
            aria-label="Confirm password"
          >
            <template v-slot:prepend>
              <q-icon name="lock" />
            </template>
          </q-input>

          <ErrorAlert />

          <q-btn
            type="submit"
            :label="$t('auth.resetPassword')"
            color="primary"
            class="full-width"
            :loading="loading"
            :disable="loading"
            aria-label="Reset password"
          />
        </q-form>
      </q-card-section>

      <q-card-section v-else class="text-center">
        <q-icon name="check_circle" size="64px" color="positive" />
        <div class="text-h6 q-mt-md">{{ $t('auth.passwordResetComplete') }}</div>
        <div class="text-body1 q-mt-sm">
          {{ $t('auth.passwordResetDesc') }}
        </div>
        <q-btn
          :label="$t('auth.goToLogin')"
          color="primary"
          class="q-mt-md"
          @click="$router.push('/login')"
        />
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useQuasar } from 'quasar';
import { useErrorStore } from 'stores/error';
import { accountApi } from 'src/api';
import ErrorAlert from 'components/common/ErrorAlert.vue';
import LanguageSwitcher from 'components/common/LanguageSwitcher.vue';

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const $q = useQuasar();
const errorStore = useErrorStore();

// Form ref
const formRef = ref(null);

// State
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const resetComplete = ref(false);
const resetKey = ref('');
const loading = ref(false);

// Validation rules
const passwordRules = [
  val => !!val || t('validation.required'),
  val => val.length >= 5 || t('validation.minLength', { min: 5 })
];

const confirmPasswordRules = computed(() => [
  val => val === password.value || t('validation.passwordMatch')
]);

// Lifecycle
onMounted(() => {
  resetKey.value = route.query.key;
  if (!resetKey.value) {
    router.push('/forgot-password');
  }
});

onUnmounted(() => {
  errorStore.clearError();
});

// Methods
async function handleSubmit() {
  const valid = await formRef.value.validate();
  if (!valid) return;

  loading.value = true;
  errorStore.clearError();

  try {
    await accountApi.resetPassword(resetKey.value, password.value);
    resetComplete.value = true;
    $q.notify({
      type: 'positive',
      message: t('auth.passwordResetComplete'),
      position: 'top'
    });
  } catch (err) {
    errorStore.setError(err);
  } finally {
    loading.value = false;
  }
}
</script>
```

### Account Activation Page

```vue
<!-- src/pages/auth/ActivatePage.vue -->
<template>
  <q-page class="row justify-center items-center q-pa-md">
    <q-card class="col-12 col-sm-8 col-md-6 col-lg-4">
      <!-- Loading State -->
      <q-card-section v-if="loading" class="text-center q-pa-xl">
        <q-spinner size="50px" color="primary" />
        <div class="text-body1 q-mt-md">{{ $t('auth.activating') }}</div>
      </q-card-section>

      <!-- Success State -->
      <q-card-section v-else-if="success" class="text-center q-pa-xl">
        <q-icon name="check_circle" size="64px" color="positive" />
        <div class="text-h6 q-mt-md">{{ $t('auth.accountActivated') }}</div>
        <div class="text-body1 q-mt-sm">
          {{ $t('auth.accountActivatedDesc') }}
        </div>
        <q-btn
          :label="$t('auth.goToLogin')"
          color="primary"
          class="q-mt-md"
          @click="$router.push('/login')"
        />
      </q-card-section>

      <!-- Error State -->
      <q-card-section v-else class="text-center q-pa-xl">
        <q-icon name="error" size="64px" color="negative" />
        <div class="text-h6 q-mt-md">{{ $t('auth.activationFailed') }}</div>
        <div class="text-body1 q-mt-sm">
          {{ errorMessage }}
        </div>
        <q-btn
          flat
          :label="$t('auth.backToLogin')"
          color="primary"
          class="q-mt-md"
          @click="$router.push('/login')"
        />
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useQuasar } from 'quasar';
import { accountApi } from 'src/api';

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const $q = useQuasar();

// State
const loading = ref(true);
const success = ref(false);
const errorMessage = ref('');

// Lifecycle
onMounted(async () => {
  const key = route.query.key;

  if (!key) {
    loading.value = false;
    errorMessage.value = t('errors.validation.badRequest');
    return;
  }

  try {
    await accountApi.activate(key);
    success.value = true;
    $q.notify({
      type: 'positive',
      message: t('auth.accountActivated'),
      position: 'top'
    });
  } catch (err) {
    const errorKey = err.response?.data?.errorMsg?.errorKey;
    errorMessage.value = errorKey
      ? t(`errors.${errorKey}`)
      : t('errors.generic.unknown');
  } finally {
    loading.value = false;
  }
});
</script>
```

---

## Router Configuration

```javascript
// src/router/routes.js
const routes = [
  {
    path: '/',
    component: () => import('layouts/MainLayout.vue'),
    children: [
      {
        path: '',
        redirect: '/dashboard'
      },
      {
        path: 'dashboard',
        component: () => import('pages/DashboardPage.vue'),
        meta: { requiresAuth: true }
      }
    ]
  },
  {
    path: '/login',
    component: () => import('layouts/AuthLayout.vue'),
    children: [
      {
        path: '',
        component: () => import('pages/LoginPage.vue')
      }
    ]
  },
  {
    path: '/register',
    component: () => import('layouts/AuthLayout.vue'),
    children: [
      {
        path: '',
        component: () => import('pages/RegisterPage.vue')
      }
    ]
  },
  {
    path: '/forgot-password',
    component: () => import('layouts/AuthLayout.vue'),
    children: [
      {
        path: '',
        component: () => import('pages/ForgotPasswordPage.vue')
      }
    ]
  },
  {
    path: '/reset-password',
    component: () => import('layouts/AuthLayout.vue'),
    children: [
      {
        path: '',
        component: () => import('pages/ResetPasswordPage.vue')
      }
    ]
  },
  {
    path: '/activate',
    component: () => import('layouts/AuthLayout.vue'),
    children: [
      {
        path: '',
        component: () => import('pages/ActivatePage.vue')
      }
    ]
  },
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/ErrorNotFound.vue')
  }
];

export default routes;
```

```javascript
// src/router/index.js
import { route } from 'quasar/wrappers';
import { createRouter, createWebHistory } from 'vue-router';
import routes from './routes';

export default route(function () {
  const Router = createRouter({
    history: createWebHistory(process.env.VUE_ROUTER_BASE),
    routes
  });

  // Navigation guard
  Router.beforeEach((to, from, next) => {
    const requiresAuth = to.matched.some(record => record.meta.requiresAuth);

    // Check user cookie
    const userCookie = document.cookie
      .split('; ')
      .find(row => row.startsWith('user='));

    const isAuthenticated = !!userCookie;

    if (requiresAuth && !isAuthenticated) {
      next({
        path: '/login',
        query: { redirect: to.fullPath }
      });
    } else {
      next();
    }
  });

  return Router;
});
```

---

## Summary

This guide provides everything needed to integrate Vue.js 3 + Quasar 2 with the Skillars security backend:

1. **Cookie Management** - Understanding which cookies are accessible
2. **CORS Configuration** - Proper setup for cross-origin requests
3. **Project Setup** - Quasar project initialization and dependencies
4. **Error Handling** - Centralized error processing with `ErrorDto` structure
5. **Internationalization** - English and French translations with vue-i18n
6. **Authentication Store** - Complete Pinia store with all auth methods
7. **Page Components** - Ready-to-use Login, Register, and Password Reset pages
8. **Router Configuration** - Protected routes with navigation guards

### Error Response Structure Quick Reference

```json
{
  "helpCode": "unique-support-code",
  "errorMsg": {
    "errorKey": "security.badCreds",
    "message": "Invalid email or password"
  },
  "fieldErrors": [
    {
      "objectName": "registrationDto",
      "field": "email",
      "errorMsg": {
        "errorKey": "invalid.email",
        "message": "Please enter a valid email"
      }
    }
  ]
}
```

### Supported Languages

| Code | Language | File |
|------|----------|------|
| `en` | English | `src/i18n/en-US/index.js` |
| `fr` | French | `src/i18n/fr-FR/index.js` |

### Related Documentation

- [Session Refresh Mechanism](./session-refresh-mechanism.md) - Session management and timeout warnings
- [Security API Endpoints](./security-api-endpoints.md) - Complete API reference
