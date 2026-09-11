<template>
  <q-layout view="lHh Lpr lFf">
    <!-- ── Header ──────────────────────────────────────────── -->
    <q-header>
      <q-toolbar class="header-toolbar">
        <!-- Hamburger (authenticated only) -->
        <q-btn
          v-if="authStore.isAuthenticated"
          flat
          round
          dense
          icon="menu"
          class="header-btn"
          :aria-label="$t('nav.menu')"
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
        <q-btn-dropdown
          flat
          no-caps
          :label="currentLanguageLabel"
          icon="language"
          class="header-btn"
        >
          <q-list class="dropdown-list">
            <q-item
              v-for="lang in languages"
              :key="lang.value"
              clickable
              v-close-popup
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
          flat
          round
          dense
          :icon="darkMode ? 'light_mode' : 'dark_mode'"
          class="header-btn"
          :aria-label="darkMode ? t('theme.toggle') : t('theme.toggle')"
          @click="onToggleTheme"
        >
          <q-tooltip>{{ darkMode ? t('theme.light') : t('theme.dark') }}</q-tooltip>
        </q-btn>

        <!-- Authenticated: user menu -->
        <template v-if="authStore.isAuthenticated">
          <q-btn-dropdown
            flat
            no-caps
            :label="authStore.displayName"
            icon="person"
            class="header-btn"
          >
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
                  <q-item-label style="color: var(--accent-danger)">{{
                    t('auth.logout')
                  }}</q-item-label>
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
      v-if="authStore.isAuthenticated"
      v-model="leftDrawerOpen"
      show-if-above
      :width="280"
      class="side-drawer"
    >
      <!-- Drawer header -->
      <div class="drawer-header">
        <div class="drawer-brand gradient-text">Skillars</div>
        <div class="text-meta">{{ $t('nav.tagline') }}</div>
      </div>

      <q-list padding class="drawer-nav">
        <!-- Main navigation -->
        <div class="text-label q-px-md q-mb-sm">{{ $t('nav.sectionMain') }}</div>

        <q-item clickable to="/dashboard" class="nav-item">
          <q-item-section avatar>
            <q-icon name="dashboard" class="nav-icon" />
          </q-item-section>
          <q-item-section>
            <q-item-label class="nav-label">{{ $t('nav.dashboard') }}</q-item-label>
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

        <!-- Player section (UAT.5: self-registered adult player) -->
        <template v-if="authStore.isPlayer">
          <div class="text-label q-px-md q-mt-lg q-mb-sm">{{ t('player.nav') }}</div>

          <q-item clickable to="/marketplace" class="nav-item">
            <q-item-section avatar>
              <q-icon name="search" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('marketplace.title') }}</q-item-label>
            </q-item-section>
          </q-item>

          <q-item clickable to="/parent/bookings" class="nav-item">
            <q-item-section avatar>
              <q-icon name="event" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('booking.requests.listTitle') }}</q-item-label>
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

          <q-item v-if="packsRoute" clickable :to="packsRoute" class="nav-item">
            <q-item-section avatar>
              <q-icon name="confirmation_number" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ t('booking.packs.dashboardTitle') }}</q-item-label>
            </q-item-section>
          </q-item>
        </template>

        <!-- Admin section -->
        <template v-if="authStore.isAdmin">
          <div class="text-label q-px-md q-mt-lg q-mb-sm">{{ $t('nav.admin') }}</div>

          <q-item clickable to="/admin/health-dashboard" class="nav-item">
            <q-item-section avatar>
              <q-icon name="monitor_heart" class="nav-icon" />
            </q-item-section>
            <q-item-section>
              <q-item-label class="nav-label">{{ $t('nav.healthDashboard') }}</q-item-label>
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useSession, LOGOUT_BACKEND_WAIT_MS } from 'src/composables/useSession'
import { toggleTheme as bootToggleTheme, isDarkMode } from 'src/boot/theme'
import ParentChildSwitcher from 'src/components/ParentChildSwitcher.vue'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'

const router = useRouter()
const { t, locale } = useI18n()
const { destroySession } = useSession()
const authStore = useAuthStore()
const playerStore = usePlayerStore()

const leftDrawerOpen = ref(false)
const darkMode = ref(isDarkMode())

// Deferred-82 AC3: resolved for a self-registered player caller so the pack-dashboard nav item
// can bind its route once resolved, rather than to an unresolved/undefined playerId.
const selfPlayerId = ref(null)
const packsRoute = computed(() =>
  selfPlayerId.value ? `/parent/players/${selfPlayerId.value}/packs` : null,
)

const languages = [
  { label: 'English', value: 'en-US' },
  { label: 'Français', value: 'fr-FR' },
  { label: 'Deutsch', value: 'de-DE' },
]

const currentLanguageLabel = computed(() => {
  const lang = languages.find((l) => l.value === locale.value)
  return lang ? lang.label : 'English'
})

function changeLanguage(lang) {
  locale.value = lang
  // skillars-deferred-109 AC3.2: localStorage.setItem throws in Safari private mode. Guard it
  // (sessionManager.js wraps its equivalent sessionStorage calls the same way) so the lang-cookie
  // clear below still runs — that is the line that unsticks a stale backend `lang` cookie.
  try {
    localStorage.setItem('locale', lang)
  } catch {
    // localStorage unavailable (Safari private mode / disabled) — the choice just won't persist.
  }
  // skillars-deferred-92 code review, chunk 3: a prior `?language=` visit can have left a `lang`
  // cookie (LocaleChangeInterceptor, MvcConfig) that outranks Accept-Language on every backend
  // request from then on, with no other way for the user to unstick it. Clear it so this switcher
  // is authoritative again for backend-resolved locale (error messages, emails).
  document.cookie = 'lang=; Max-Age=0; path=/'
}

function loadLanguagePreference() {
  let savedLocale = null
  // skillars-deferred-109 AC3.2: localStorage.getItem throws in Safari private mode; unguarded it
  // aborts onMounted before the storage listener registers and the self-player-id fetch runs.
  try {
    savedLocale = localStorage.getItem('locale')
  } catch {
    // localStorage unavailable — proceed with no saved preference.
  }
  if (savedLocale && languages.some((l) => l.value === savedLocale)) {
    locale.value = savedLocale
  }
}

function onToggleTheme() {
  // Take the state toggleTheme just applied — do NOT re-read the DOM (skillars-deferred-102 AC16).
  darkMode.value = bootToggleTheme()
}

function onStorageThemeChange(event) {
  if (event.key === 'skillars-theme') {
    darkMode.value = isDarkMode()
  }
}

function deleteUserCookie() {
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
}

async function handleLogout() {
  // skillars-deferred-109 AC3.1: carry useSession.handleLogout's deferred-91 AC14 guarantees so the
  // two logout sequences stop diverging on what matters for sibling-tab teardown — a BOUNDED wait
  // on the backend call, and BOTH 'rint' clears (pre- and post-race). Deliberately NOT unified:
  // useSession stops monitoring first; MainLayout keeps its
  // logout → resetSelfPlayerId → destroySession → deleteUserCookie → router.push('/login') order
  // (destroySession() below owns the monitoring teardown here).
  //
  // Pre-race clear: sibling tabs enter computeTimeUntilExpiry's fast-teardown branch immediately
  // rather than after the up-to-LOGOUT_BACKEND_WAIT_MS window (useSession.js:82).
  document.cookie = 'rint=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
  // Bounded: the axios instance sets no timeout, so a stalled POST /logout would otherwise strand
  // the user on the authenticated page (router.push is behind this await). authStore.logout()
  // swallows its own errors, so this races only against the hang.
  await Promise.race([
    authStore.logout(),
    new Promise((resolve) => setTimeout(resolve, LOGOUT_BACKEND_WAIT_MS)),
  ])
  // Post-race clear: any authenticated response in flight when the pre-race clear ran re-sets
  // 'rint' with path=/ via JwtManagerImpl, so a sibling tab would read a live 'rint' again
  // (useSession.js:104).
  document.cookie = 'rint=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
  playerStore.resetSelfPlayerId()
  destroySession()
  deleteUserCookie()
  router.push('/login')
}

function toggleLeftDrawer() {
  leftDrawerOpen.value = !leftDrawerOpen.value
}

onMounted(async () => {
  loadLanguagePreference()
  // Automatic session-expiry handling (cookie/state clearing + redirect) is owned
  // by App.vue, which is always mounted — avoids a race between two listeners.
  window.addEventListener('storage', onStorageThemeChange)

  if (authStore.isPlayer) {
    try {
      selfPlayerId.value = await playerStore.fetchSelfPlayerId()
    } catch (err) {
      // A 404 is the expected, silent case: verified but never finished the profile-builder
      // step (same precedent as CoachPublicProfilePage.vue). Anything else is surfaced.
      if (err.response?.status !== 404) {
        console.error('Failed to resolve self player id for nav', err)
      }
    }
  }
})

onUnmounted(() => {
  window.removeEventListener('storage', onStorageThemeChange)
})
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
  transition:
    background-color 0.15s ease,
    color 0.15s ease;

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
  transition:
    background-color 0.2s ease,
    color 0.2s ease;

  &:hover {
    background: var(--surface-glass-hover) !important;
    color: var(--text-primary);

    .nav-icon {
      color: var(--accent-primary);
    }
  }

  // Quasar active router-link class
  &.q-router-link--active {
    background: var(--nav-active-bg) !important;
    color: var(--nav-active-color);

    .nav-icon {
      color: var(--nav-active-color);
    }
    .nav-label {
      color: var(--nav-active-color);
      font-weight: 600;
    }
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

.nav-icon--danger {
  color: var(--accent-danger);
}
.nav-label--danger {
  color: var(--accent-danger);
  font-size: 14px;
  font-weight: 500;
}

.drawer-footer {
  padding: 8px;
  border-top: 1px solid var(--border-soft);
  margin-top: auto;

  .logout-item:hover {
    background: var(--surface-danger-hover) !important;
  }
}
</style>
