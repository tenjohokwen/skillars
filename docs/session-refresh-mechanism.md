# Session Refresh Mechanism for Frontend Integration

This document explains the session management and refresh mechanism for Vue.js 3 + Quasar 2 frontend integration. It covers how to keep sessions alive, detect session expiration, and implement session timeout warnings.

## Overview

The backend uses a **JWT-based authentication system** with a **two-tier token refresh mechanism**:

1. **JWT TTL Extension** - Token validity extended on each request (15 minutes)
2. **DB Refresh Token** - Database validation every 5 minutes

```
┌─────────────────────────────────────────────────────────────────┐
│                    Session Lifecycle                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Login ──► JWT Created (15 min TTL) ──► User makes requests    │
│                                              │                   │
│                                              ▼                   │
│                                    ┌─────────────────┐          │
│                                    │ Each Request:   │          │
│                                    │ - Extends JWT   │          │
│                                    │   by 15 min     │          │
│                                    │ - Every 5 min:  │          │
│                                    │   DB validation │          │
│                                    └─────────────────┘          │
│                                              │                   │
│                                              ▼                   │
│                              No activity for 15 min?             │
│                                     │          │                 │
│                                    Yes        No                 │
│                                     │          │                 │
│                                     ▼          └──► Continue     │
│                              Session Expires                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Key Timeouts

| Timer | Duration | Description |
|-------|----------|-------------|
| JWT TTL | 15 minutes | Token expires if no activity |
| DB Refresh | 5 minutes | Database validation interval |
| OTP/2FA | 30 minutes | One-time password validity |
| Password Reset | 24 hours | Reset link validity |
| Account Activation | 7 days | Activation link validity |

## Session Refresh Endpoint

### Endpoint Details

**Endpoint:** `GET /refresh` or `POST /refresh`

**Authentication:** Required (valid JWT)

**Purpose:** Keep the session alive without performing any other action

**Response:** `200 OK` (empty body)

**What happens:**
1. The `JWTAuthorizationFilter` intercepts the request
2. If JWT is valid, it extends the TTL by 15 minutes
3. If DB refresh token expired (>5 min), it validates against database
4. `SessionRefreshFilter` returns 200 OK

## The `rint` Cookie (Session Refresh Countdown)

The backend sets a special cookie called `rint` (Refresh Interval) that the frontend can read to implement session timeout warnings.

### Cookie Details

| Property | Value |
|----------|-------|
| Name | `rint` |
| HTTPOnly | No (readable by JavaScript) |
| TTL | Same as JWT (15 minutes) |
| Value | Timestamp or countdown indicator |

### How to Use for Session Warnings

The `rint` cookie is set when the user logs in and refreshed with each authenticated request. When this cookie is about to expire, you should warn the user.

## Frontend Implementation

### 1. Session Management Store (Pinia)

```javascript
// stores/session.js
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { api } from 'src/boot/axios';
import { useCookies } from '@vueuse/integrations/useCookies';

export const useSessionStore = defineStore('session', () => {
  // State
  const isAuthenticated = ref(false);
  const displayName = ref('');
  const isAdmin = ref(false);
  const sessionExpiresAt = ref(null);
  const showSessionWarning = ref(false);

  // Timers
  let sessionCheckInterval = null;
  let warningTimeout = null;
  let expirationTimeout = null;

  // Constants
  const SESSION_TTL_MS = 15 * 60 * 1000; // 15 minutes
  const WARNING_BEFORE_MS = 2 * 60 * 1000; // Warn 2 minutes before expiry
  const CHECK_INTERVAL_MS = 30 * 1000; // Check every 30 seconds

  // Computed
  const timeUntilExpiry = computed(() => {
    if (!sessionExpiresAt.value) return 0;
    return Math.max(0, sessionExpiresAt.value - Date.now());
  });

  const minutesUntilExpiry = computed(() => {
    return Math.ceil(timeUntilExpiry.value / 60000);
  });

  // Actions
  function initSession() {
    const cookies = useCookies();
    const userCookie = cookies.get('user');
    const adminCookie = cookies.get('admin');

    if (userCookie) {
      isAuthenticated.value = true;
      displayName.value = userCookie;
      isAdmin.value = adminCookie === 'true';
      resetSessionTimer();
      startSessionMonitoring();
    }
  }

  function resetSessionTimer() {
    // Set new expiration time
    sessionExpiresAt.value = Date.now() + SESSION_TTL_MS;
    showSessionWarning.value = false;

    // Clear existing timeouts
    if (warningTimeout) clearTimeout(warningTimeout);
    if (expirationTimeout) clearTimeout(expirationTimeout);

    // Set warning timeout (2 min before expiry)
    warningTimeout = setTimeout(() => {
      showSessionWarning.value = true;
    }, SESSION_TTL_MS - WARNING_BEFORE_MS);

    // Set expiration timeout
    expirationTimeout = setTimeout(() => {
      handleSessionExpired();
    }, SESSION_TTL_MS);
  }

  function startSessionMonitoring() {
    // Check session status periodically
    sessionCheckInterval = setInterval(() => {
      checkSessionStatus();
    }, CHECK_INTERVAL_MS);
  }

  function checkSessionStatus() {
    const cookies = useCookies();
    const userCookie = cookies.get('user');

    if (!userCookie && isAuthenticated.value) {
      // Session expired on server side
      handleSessionExpired();
    }
  }

  async function refreshSession() {
    try {
      await api.get('/refresh', { withCredentials: true });
      resetSessionTimer();
      return true;
    } catch (error) {
      if (error.response?.status === 401) {
        handleSessionExpired();
      }
      return false;
    }
  }

  function handleSessionExpired() {
    isAuthenticated.value = false;
    displayName.value = '';
    isAdmin.value = false;
    sessionExpiresAt.value = null;
    showSessionWarning.value = false;

    // Clear intervals and timeouts
    if (sessionCheckInterval) clearInterval(sessionCheckInterval);
    if (warningTimeout) clearTimeout(warningTimeout);
    if (expirationTimeout) clearTimeout(expirationTimeout);

    // Emit event or redirect
    window.location.href = '/login?expired=true';
  }

  async function logout() {
    try {
      await api.post('/api/logout', {}, { withCredentials: true });
    } finally {
      handleSessionExpired();
    }
  }

  function cleanup() {
    if (sessionCheckInterval) clearInterval(sessionCheckInterval);
    if (warningTimeout) clearTimeout(warningTimeout);
    if (expirationTimeout) clearTimeout(expirationTimeout);
  }

  return {
    // State
    isAuthenticated,
    displayName,
    isAdmin,
    showSessionWarning,
    timeUntilExpiry,
    minutesUntilExpiry,
    // Actions
    initSession,
    resetSessionTimer,
    refreshSession,
    logout,
    cleanup
  };
});
```

### 2. Session Warning Dialog Component

```vue
<!-- components/SessionWarningDialog.vue -->
<template>
  <q-dialog v-model="showDialog" persistent>
    <q-card style="min-width: 350px">
      <q-card-section class="row items-center">
        <q-avatar icon="access_time" color="warning" text-color="white" />
        <span class="q-ml-sm text-h6">Session Expiring</span>
      </q-card-section>

      <q-card-section>
        <p>Your session will expire in <strong>{{ minutesRemaining }}</strong> minute(s).</p>
        <p>Do you want to continue your session?</p>
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
          label="Logout"
          color="negative"
          @click="handleLogout"
          :loading="loading"
        />
        <q-btn
          label="Continue Session"
          color="primary"
          @click="handleRefresh"
          :loading="loading"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useSessionStore } from 'stores/session';
import { useQuasar } from 'quasar';

const $q = useQuasar();
const sessionStore = useSessionStore();

const loading = ref(false);
const showDialog = ref(false);

// Watch for session warning
watch(() => sessionStore.showSessionWarning, (show) => {
  showDialog.value = show;
});

const minutesRemaining = computed(() => sessionStore.minutesUntilExpiry);

const progressValue = computed(() => {
  // Progress from 1 to 0 over 2 minutes
  const twoMinutesMs = 2 * 60 * 1000;
  return Math.max(0, sessionStore.timeUntilExpiry / twoMinutesMs);
});

async function handleRefresh() {
  loading.value = true;
  try {
    const success = await sessionStore.refreshSession();
    if (success) {
      showDialog.value = false;
      $q.notify({
        type: 'positive',
        message: 'Session refreshed successfully',
        position: 'top'
      });
    }
  } catch (error) {
    $q.notify({
      type: 'negative',
      message: 'Failed to refresh session',
      position: 'top'
    });
  } finally {
    loading.value = false;
  }
}

async function handleLogout() {
  loading.value = true;
  await sessionStore.logout();
}
</script>
```

### 3. Axios Interceptor for Session Management

```javascript
// boot/axios.js
import { boot } from 'quasar/wrappers';
import axios from 'axios';
import { useSessionStore } from 'stores/session';

const api = axios.create({
  baseURL: process.env.API_URL || 'http://localhost:8080',
  withCredentials: true, // Always send cookies
  timeout: 30000
});

export default boot(({ app, router }) => {
  // Response interceptor
  api.interceptors.response.use(
    (response) => {
      // Reset session timer on successful authenticated requests
      const sessionStore = useSessionStore();
      if (sessionStore.isAuthenticated) {
        sessionStore.resetSessionTimer();
      }
      return response;
    },
    (error) => {
      const sessionStore = useSessionStore();

      if (error.response?.status === 401) {
        // Session expired
        sessionStore.logout();
        router.push('/login?expired=true');
      }

      return Promise.reject(error);
    }
  );

  // Request interceptor (optional - for adding headers)
  api.interceptors.request.use(
    (config) => {
      // Add any custom headers if needed
      return config;
    },
    (error) => Promise.reject(error)
  );

  app.config.globalProperties.$axios = axios;
  app.config.globalProperties.$api = api;
});

export { api };
```

### 4. Activity-Based Session Refresh

For a better user experience, refresh the session automatically when the user is active:

```javascript
// composables/useActivityTracking.js
import { onMounted, onUnmounted } from 'vue';
import { useSessionStore } from 'stores/session';
import { debounce } from 'quasar';

export function useActivityTracking() {
  const sessionStore = useSessionStore();

  // Debounced refresh - max once every 5 minutes
  const debouncedRefresh = debounce(() => {
    if (sessionStore.isAuthenticated) {
      sessionStore.refreshSession();
    }
  }, 5 * 60 * 1000, true); // 5 minutes, leading edge

  const activityEvents = ['mousedown', 'keydown', 'scroll', 'touchstart'];

  function handleActivity() {
    if (sessionStore.isAuthenticated && sessionStore.showSessionWarning) {
      // If warning is showing and user is active, refresh immediately
      sessionStore.refreshSession();
    } else {
      // Otherwise use debounced refresh
      debouncedRefresh();
    }
  }

  onMounted(() => {
    activityEvents.forEach(event => {
      document.addEventListener(event, handleActivity, { passive: true });
    });
  });

  onUnmounted(() => {
    activityEvents.forEach(event => {
      document.removeEventListener(event, handleActivity);
    });
  });
}
```

### 5. App Layout with Session Warning

```vue
<!-- layouts/MainLayout.vue -->
<template>
  <q-layout view="lHh Lpr lFf">
    <q-header elevated>
      <q-toolbar>
        <q-toolbar-title>My App</q-toolbar-title>

        <!-- Session indicator -->
        <div v-if="sessionStore.isAuthenticated" class="row items-center q-gutter-sm">
          <q-chip
            v-if="sessionStore.showSessionWarning"
            color="warning"
            text-color="white"
            icon="warning"
          >
            Session expiring in {{ sessionStore.minutesUntilExpiry }} min
          </q-chip>
          <span>{{ sessionStore.displayName }}</span>
          <q-btn flat icon="logout" @click="sessionStore.logout" />
        </div>
      </q-toolbar>
    </q-header>

    <q-page-container>
      <router-view />
    </q-page-container>

    <!-- Session Warning Dialog -->
    <SessionWarningDialog />
  </q-layout>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue';
import { useSessionStore } from 'stores/session';
import { useActivityTracking } from 'src/composables/useActivityTracking';
import SessionWarningDialog from 'components/SessionWarningDialog.vue';

const sessionStore = useSessionStore();

// Initialize session on mount
onMounted(() => {
  sessionStore.initSession();
});

// Cleanup on unmount
onUnmounted(() => {
  sessionStore.cleanup();
});

// Track user activity
useActivityTracking();
</script>
```

### 6. Route Guards for Protected Pages

```javascript
// router/index.js
import { route } from 'quasar/wrappers';
import { createRouter, createWebHistory } from 'vue-router';
import routes from './routes';

export default route(function () {
  const Router = createRouter({
    history: createWebHistory(),
    routes
  });

  Router.beforeEach((to, from, next) => {
    const requiresAuth = to.matched.some(record => record.meta.requiresAuth);

    // Check for user cookie (non-HTTPOnly, readable by JS)
    const userCookie = document.cookie
      .split('; ')
      .find(row => row.startsWith('user='));

    const isAuthenticated = !!userCookie;

    if (requiresAuth && !isAuthenticated) {
      next({ path: '/login', query: { redirect: to.fullPath } });
    } else if (to.path === '/login' && isAuthenticated) {
      next('/dashboard');
    } else {
      next();
    }
  });

  return Router;
});
```

## Best Practices

### 1. Always Use `withCredentials`

```javascript
// Ensure cookies are sent with every request
axios.defaults.withCredentials = true;
```

### 2. Handle 401 Responses Globally

```javascript
// In your axios interceptor
if (error.response?.status === 401) {
  sessionStore.logout();
  router.push('/login?expired=true');
}
```

### 3. Refresh on Tab Focus

```javascript
// Refresh session when user returns to the tab
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && sessionStore.isAuthenticated) {
    sessionStore.refreshSession();
  }
});
```

### 4. Don't Refresh Too Frequently

```javascript
// Debounce refreshes to avoid overwhelming the server
// Maximum once every 5 minutes for activity-based refresh
```

### 5. Show Warning Before Expiry

```javascript
// Show warning 2 minutes before session expires
// This gives users time to save their work
const WARNING_BEFORE_MS = 2 * 60 * 1000;
```

## Sequence Diagrams

### Normal Session Flow

```
User                    Frontend                   Backend
 │                         │                          │
 │──── Login ─────────────►│                          │
 │                         │──── POST /authenticate ──►│
 │                         │◄──── JWT + Cookies ───────│
 │◄──── Logged In ─────────│                          │
 │                         │                          │
 │──── Make Request ──────►│                          │
 │                         │──── GET /api/data ───────►│
 │                         │   (JWT in cookie)         │
 │                         │◄──── Data + Extended JWT ─│
 │◄──── Response ──────────│                          │
 │                         │                          │
 │     (13 min later)      │                          │
 │                         │◄── Warning Dialog ────────│
 │                         │                          │
 │──── Click Refresh ─────►│                          │
 │                         │──── GET /refresh ────────►│
 │                         │◄──── 200 OK + New JWT ────│
 │◄──── Session Extended ──│                          │
```

### Session Expiry Flow

```
User                    Frontend                   Backend
 │                         │                          │
 │     (15 min idle)       │                          │
 │                         │                          │
 │──── Make Request ──────►│                          │
 │                         │──── GET /api/data ───────►│
 │                         │   (Expired JWT)           │
 │                         │◄──── 401 Unauthorized ────│
 │                         │                          │
 │◄──── Redirect to Login ─│                          │
 │                         │                          │
```

## Troubleshooting

### Session Expires Unexpectedly

1. Check if `withCredentials: true` is set
2. Verify CORS configuration allows credentials
3. Check if cookies are being blocked by browser

### Warning Dialog Not Showing

1. Verify the `rint` cookie is being set
2. Check if the session timer is being reset properly
3. Ensure the warning component is mounted

### Refresh Not Working

1. Check network tab for 401 responses
2. Verify the `/refresh` endpoint is accessible
3. Check if the JWT cookie is being sent

## Summary

| Component | Purpose |
|-----------|---------|
| `/refresh` endpoint | Keep session alive |
| `rint` cookie | Frontend-readable session indicator |
| Session Store | Manage auth state and timers |
| Warning Dialog | Alert user before expiry |
| Activity Tracking | Auto-refresh on user activity |
| Axios Interceptor | Handle 401s and reset timers |

The key to a good session management implementation is:
1. **Proactively refresh** the session when the user is active
2. **Warn users** before their session expires
3. **Handle expiration gracefully** with clear messaging
4. **Reset timers** on every successful authenticated request
