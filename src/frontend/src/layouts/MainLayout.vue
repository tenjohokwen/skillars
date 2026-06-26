<template>
  <q-layout view="lHh Lpr lFf">

    <!-- ── Header ──────────────────────────────────────────── -->
    <q-header>
      <q-toolbar class="header-toolbar">

        <!-- Hamburger (authenticated only) -->
        <q-btn
          v-if="isAuthenticated"
          flat round dense
          icon="menu"
          class="header-btn"
          aria-label="Menu"
          @click="toggleLeftDrawer"
        />

        <!-- Logo / Brand -->
        <router-link to="/" class="brand-link">
          <span class="brand-name gradient-text">Skillars</span>
        </router-link>

        <q-space />

        <!-- Parent child switcher (self-hides when no players) -->
        <ParentChildSwitcher />

        <!-- Language switcher -->
        <q-btn-dropdown flat no-caps :label="currentLanguageLabel" icon="language" class="header-btn">
          <q-list class="dropdown-list">
            <q-item
              v-for="lang in languages"
              :key="lang.value"
              clickable v-close-popup
              @click="changeLanguage(lang.value)"
              class="dropdown-item"
            >
              <q-item-section>
                <q-item-label>{{ lang.label }}</q-item-label>
              </q-item-section>
              <q-item-section side v-if="locale === lang.value">
                <q-icon name="check" style="color: var(--accent-primary)" />
              </q-item-section>
            </q-item>
          </q-list>
        </q-btn-dropdown>

        <!-- Theme toggle -->
        <q-btn
          flat round dense
          :icon="darkMode ? 'light_mode' : 'dark_mode'"
          class="header-btn"
          :aria-label="darkMode ? t('theme.toggle') : t('theme.toggle')"
          @click="onToggleTheme"
        >
          <q-tooltip>{{ darkMode ? t('theme.light') : t('theme.dark') }}</q-tooltip>
        </q-btn>

        <!-- Authenticated: user menu -->
        <template v-if="isAuthenticated">
          <q-btn-dropdown flat no-caps :label="username" icon="person" class="header-btn">
            <q-list class="dropdown-list">
              <q-item clickable v-close-popup to="/profile" class="dropdown-item">
                <q-item-section avatar>
                  <q-icon name="person" style="color: var(--text-secondary)" />
                </q-item-section>
                <q-item-section>
                  <q-item-label>{{ t('profile.title') }}</q-item-label>
                </q-item-section>
              </q-item>
              <q-separator style="background: var(--border-soft)" />
              <q-item clickable v-close-popup @click="handleLogout" class="dropdown-item">
                <q-item-section avatar>
                  <q-icon name="logout" style="color: var(--accent-danger)" />
                </q-item-section>
                <q-item-section>
                  <q-item-label style="color: var(--accent-danger)">{{ t('auth.logout') }}</q-item-label>
                </q-item-section>
              </q-item>
            </q-list>
          </q-btn-dropdown>
        </template>

        <!-- Guest: login button -->
        <template v-else>
          <q-btn flat no-caps :label="t('auth.login')" to="/login" class="btn-accent q-px-md" />
        </template>

      </q-toolbar>
    </q-header>

    <!-- ── Left Drawer ─────────────────────────────────────── -->
    <q-drawer
      v-if="isAuthenticated"
      v-model="leftDrawerOpen"
      show-if-above
      :width="280"
      class="side-drawer"
    >
      <!-- Drawer header -->
      <div class="drawer-header">
        <div class="drawer-brand gradient-text">Skillars</div>
        <div class="text-meta">Analytics Platform</div>
      </div>

      <q-list padding class="drawer-nav">
        <!-- Main navigation -->
        <div class="text-label q-px-md q-mb-sm">Main</div>

        <q-item clickable to="/dashboard" class="nav-item">
          <q-item-section avatar>
            <q-icon name="dashboard" class="nav-icon" />
          </q-item-section>
          <q-item-section>
            <q-item-label class="nav-label">Dashboard</q-item-label>
          </q-item-section>
        </q-item>

        <q-item clickable to="/profile" class="nav-item">
          <q-item-section avatar>
            <q-icon name="person" class="nav-icon" />
          </q-item-section>
          <q-item-section>
            <q-item-label class="nav-label">{{ t('profile.title') }}</q-item-label>
          </q-item-section>
        </q-item>

        <!-- Coach section -->
        <template v-if="authStore.isCoach">
          <div class="text-label q-px-md q-mt-lg q-mb-sm">{{ t('coach.nav') }}</div>

          <q-item clickable to="/coach/revenue" class="nav-item">
            <q-item-section avatar>
              <q-icon name="bar_chart" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('revenue.pageTitle') }}</q-item-label>
            </q-item-section>
          </q-item>

          <q-item clickable to="/messaging" class="nav-item">
            <q-item-section avatar>
              <q-icon name="chat" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('messaging.pageTitle') }}</q-item-label>
            </q-item-section>
          </q-item>
        </template>

        <!-- Parent section -->
        <template v-if="authStore.isParent">
          <div class="text-label q-px-md q-mt-lg q-mb-sm">{{ t('parent.nav') }}</div>

          <q-item clickable to="/parent/credit-statement" class="nav-item">
            <q-item-section avatar>
              <q-icon name="receipt_long" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('creditStatement.pageTitle') }}</q-item-label>
            </q-item-section>
          </q-item>

          <q-item clickable to="/messaging" class="nav-item">
            <q-item-section avatar>
              <q-icon name="chat" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('messaging.pageTitle') }}</q-item-label>
            </q-item-section>
          </q-item>
        </template>

        <!-- Admin section -->
        <template v-if="authStore.isAdmin">
          <div class="text-label q-px-md q-mt-lg q-mb-sm">Admin</div>

          <q-item clickable to="/admin/tenants" class="nav-item">
            <q-item-section avatar>
              <q-icon name="group" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">Tenants</q-item-label>
            </q-item-section>
          </q-item>

          <q-item clickable to="/admin/health-dashboard" class="nav-item">
            <q-item-section avatar>
              <q-icon name="monitor_heart" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">Health Dashboard</q-item-label>
            </q-item-section>
          </q-item>
        </template>
      </q-list>

      <!-- Drawer footer -->
      <div class="drawer-footer">
        <q-item clickable class="nav-item logout-item" @click="handleLogout">
          <q-item-section avatar>
            <q-icon name="logout" class="nav-icon--danger" />
          </q-item-section>
          <q-item-section>
            <q-item-label class="nav-label--danger">{{ t('auth.logout') }}</q-item-label>
          </q-item-section>
        </q-item>
      </div>
    </q-drawer>

    <!-- ── Page content ────────────────────────────────────── -->
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
import { toggleTheme as bootToggleTheme, isDarkMode } from 'src/boot/theme';
import ParentChildSwitcher from 'src/components/ParentChildSwitcher.vue';
import { useAuthStore } from 'src/stores/auth.store';

const router = useRouter();
const { t, locale } = useI18n();
const { destroySession } = useSession();
const authStore = useAuthStore();

const leftDrawerOpen = ref(false);
const darkMode = ref(isDarkMode());

const languages = [
  { label: 'English', value: 'en-US' },
  { label: 'Français', value: 'fr-FR' },
];

const currentLanguageLabel = computed(() => {
  const lang = languages.find(l => l.value === locale.value);
  return lang ? lang.label : 'English';
});

function getUsernameFromCookie() {
  const match = document.cookie.match(/user=([^;]+)/);
  if (match) {
    try { return decodeURIComponent(match[1]); } catch { return match[1]; }
  }
  return '';
}

const username = ref(getUsernameFromCookie());
const isAuthenticated = computed(() => !!username.value);

function updateUsername() {
  username.value = getUsernameFromCookie();
}

function changeLanguage(lang) {
  locale.value = lang;
  localStorage.setItem('locale', lang);
}

function loadLanguagePreference() {
  const savedLocale = localStorage.getItem('locale');
  if (savedLocale && languages.some(l => l.value === savedLocale)) {
    locale.value = savedLocale;
  }
}

function onToggleTheme() {
  bootToggleTheme();
  darkMode.value = isDarkMode();
}

function onStorageThemeChange(event) {
  if (event.key === 'skillars-theme') {
    darkMode.value = isDarkMode();
  }
}

function deleteUserCookie() {
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
}

async function handleLogout() {
  try { await authApi.logout(); } catch { /* already logged out */ }
  destroySession();
  username.value = '';
  deleteUserCookie();
  router.push('/login');
}

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
  window.addEventListener('session:expired', onSessionExpired);
  window.addEventListener('storage', onStorageThemeChange);
});

onUnmounted(() => {
  window.removeEventListener('session:expired', onSessionExpired);
  window.removeEventListener('storage', onStorageThemeChange);
});
</script>

<style lang="scss" scoped>
// ── Header toolbar ────────────────────────────────────────
.header-toolbar {
  padding: 0 16px;
  min-height: 60px;
}

.brand-link {
  text-decoration: none;
  margin: 0 8px;
}

.brand-name {
  font-size: 20px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -0.5px;
}

.header-btn {
  color: var(--text-secondary) !important;
  font-family: 'Inter', sans-serif;
  font-weight: 500;
  font-size: 14px;
  border-radius: 10px !important;
  margin: 0 2px;

  &:hover {
    color: var(--text-primary) !important;
    background: var(--surface-glass-hover) !important;
  }
}

// ── Dropdowns ────────────────────────────────────────────
.dropdown-list {
  background: var(--bg-secondary);
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  padding: 8px;
  min-width: 160px;
}

.dropdown-item {
  border-radius: 10px;
  font-family: 'Inter', sans-serif;
  font-size: 14px;
  color: var(--text-secondary);
  transition: all 0.15s ease;

  &:hover {
    background: var(--surface-glass-hover) !important;
    color: var(--text-primary);
  }
}

// ── Drawer ───────────────────────────────────────────────
.side-drawer {
  display: flex;
  flex-direction: column;
}

.drawer-header {
  padding: 24px 20px 16px;
  border-bottom: 1px solid var(--border-soft);
}

.drawer-brand {
  font-size: 22px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -0.5px;
  margin-bottom: 4px;
}

.drawer-nav {
  flex: 1;
  padding-top: 16px;
}

.nav-item {
  border-radius: 12px;
  margin: 2px 8px;
  min-height: 44px;
  color: var(--text-secondary);
  font-family: 'Inter', sans-serif;
  transition: all 0.2s ease;

  &:hover {
    background: var(--surface-glass-hover) !important;
    color: var(--text-primary);

    .nav-icon { color: var(--accent-primary); }
  }

  // Quasar active router-link class
  &.q-router-link--active {
    background: var(--nav-active-bg) !important;
    color: var(--nav-active-color);

    .nav-icon { color: var(--nav-active-color); }
    .nav-label { color: var(--nav-active-color); font-weight: 600; }
  }
}

.nav-icon {
  color: var(--text-muted);
  transition: color 0.2s ease;
}

.nav-label {
  font-size: 14px;
  font-weight: 500;
  color: inherit;
}

.nav-icon--danger { color: var(--accent-danger); }
.nav-label--danger { color: var(--accent-danger); font-size: 14px; font-weight: 500; }

.drawer-footer {
  padding: 8px;
  border-top: 1px solid var(--border-soft);
  margin-top: auto;

  .logout-item:hover {
    background: var(--surface-danger-hover) !important;
  }
}
</style>
