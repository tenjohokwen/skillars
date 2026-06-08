<template>
  <q-layout view="lHh Lpr lFf">
    <q-header elevated class="bg-primary">
      <q-toolbar>
        <q-btn
          v-if="isAuthenticated"
          flat
          dense
          round
          icon="menu"
          aria-label="Menu"
          @click="toggleLeftDrawer"
        />

        <q-toolbar-title>
          <router-link to="/" class="text-white" style="text-decoration: none">
            Skillars
          </router-link>
        </q-toolbar-title>

        <q-space />

        <!-- Language Switcher -->
        <q-btn-dropdown
          flat
          no-caps
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
              <q-item-section>
                <q-item-label>{{ lang.label }}</q-item-label>
              </q-item-section>
              <q-item-section side v-if="locale === lang.value">
                <q-icon name="check" color="primary" />
              </q-item-section>
            </q-item>
          </q-list>
        </q-btn-dropdown>

        <!-- User Menu (when logged in) -->
        <template v-if="isAuthenticated">
          <q-btn-dropdown
            flat
            no-caps
            :label="username"
            icon="person"
          >
            <q-list>
              <q-item clickable v-close-popup @click="handleLogout">
                <q-item-section avatar>
                  <q-icon name="logout" />
                </q-item-section>
                <q-item-section>
                  <q-item-label>{{ t('auth.logout') }}</q-item-label>
                </q-item-section>
              </q-item>
            </q-list>
          </q-btn-dropdown>
        </template>

        <!-- Login button (when not logged in) -->
        <template v-else>
          <q-btn flat no-caps :label="t('auth.login')" to="/login" />
        </template>
      </q-toolbar>
    </q-header>

    <q-drawer
      v-if="isAuthenticated"
      v-model="leftDrawerOpen"
      show-if-above
      bordered
    >
      <q-list>
        <q-item-label header>{{ t('common.menu') }}</q-item-label>

        <q-item clickable to="/dashboard">
          <q-item-section avatar>
            <q-icon name="dashboard" />
          </q-item-section>
          <q-item-section>
            <q-item-label>Dashboard</q-item-label>
          </q-item-section>
        </q-item>

        <q-item clickable to="/profile">
          <q-item-section avatar>
            <q-icon name="person" />
          </q-item-section>
          <q-item-section>
            <q-item-label>{{ t('profile.title') }}</q-item-label>
          </q-item-section>
        </q-item>

        <q-separator spaced />
        <q-item-label header>Admin</q-item-label>

        <q-item clickable to="/admin/tenants">
          <q-item-section avatar>
            <q-icon name="group" />
          </q-item-section>
          <q-item-section>
            <q-item-label>Tenants</q-item-label>
          </q-item-section>
        </q-item>

        <q-item clickable to="/admin/health-dashboard">
          <q-item-section avatar>
            <q-icon name="monitor_heart" />
          </q-item-section>
          <q-item-section>
            <q-item-label>Health Dashboard</q-item-label>
          </q-item-section>
        </q-item>
      </q-list>
    </q-drawer>

    <q-page-container>
      <router-view />
    </q-page-container>
  </q-layout>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { authApi } from 'src/api/auth.api';
import { useSession } from 'src/composables/useSession';

const router = useRouter();
const { t, locale } = useI18n();
const { destroySession } = useSession();

const leftDrawerOpen = ref(false);

// Language options
const languages = [
  { label: 'English', value: 'en-US' },
  { label: 'Français', value: 'fr-FR' },
];

// Current language label
const currentLanguageLabel = computed(() => {
  const lang = languages.find(l => l.value === locale.value);
  return lang ? lang.label : 'English';
});

// Get username from cookie
function getUsernameFromCookie() {
  const match = document.cookie.match(/user=([^;]+)/);
  if (match) {
    try {
      // The cookie value might be URL encoded
      return decodeURIComponent(match[1]);
    } catch {
      return match[1];
    }
  }
  return '';
}

// Initialize username immediately for reactive auth state
const username = ref(getUsernameFromCookie());

// Check if user is authenticated (derived from username which is updated periodically)
const isAuthenticated = computed(() => {
  return !!username.value;
});

// Update username when component mounts and on cookie changes
function updateUsername() {
  username.value = getUsernameFromCookie();
}

// Change language
function changeLanguage(lang) {
  locale.value = lang;
  // Persist language preference
  localStorage.setItem('locale', lang);
}

// Load saved language preference
function loadLanguagePreference() {
  const savedLocale = localStorage.getItem('locale');
  if (savedLocale && languages.some(l => l.value === savedLocale)) {
    locale.value = savedLocale;
  }
}

// Delete user cookie
function deleteUserCookie() {
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
}

// Handle logout
async function handleLogout() {
  try {
    await authApi.logout();
  } catch {
    // Ignore logout errors (may already be logged out)
  }

  // Clean up session monitoring
  destroySession();

  // Clear username and cookie (updates isAuthenticated immediately)
  username.value = '';
  deleteUserCookie();

  // Redirect to login
  router.push('/login');
}

// Listen for session expired event
function onSessionExpired() {
  username.value = '';
  deleteUserCookie();
}

function toggleLeftDrawer() {
  leftDrawerOpen.value = !leftDrawerOpen.value;
}

onMounted(() => {
  updateUsername();
  loadLanguagePreference();

  // Listen for session expiry
  window.addEventListener('session:expired', onSessionExpired);

  // Update username periodically (in case of login state changes)
  const interval = setInterval(updateUsername, 1000);
  onUnmounted(() => {
    clearInterval(interval);
    window.removeEventListener('session:expired', onSessionExpired);
  });
});
</script>
