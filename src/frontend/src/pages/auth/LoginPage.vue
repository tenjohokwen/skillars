<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">
      <!-- Brand mark -->
      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">{{ $t('nav.tagline') }}</div>
      </div>

      <div class="glass-card--static auth-card">
        <div class="text-section-title q-mb-xs">{{ t('auth.login') }}</div>
        <div class="text-meta q-mb-lg">{{ $t('auth.loginWelcome') }}</div>

        <!-- Session expired banner -->
        <q-banner
          v-if="route.query.expired === 'true'"
          class="q-mb-md auth-banner auth-banner--warning"
          rounded
        >
          {{ t('session.expired') }}
        </q-banner>

        <!-- Email verified banner (skillars-deferred-138 AC2) -->
        <q-banner
          v-if="route.query.verified === 'true'"
          class="q-mb-md auth-banner auth-banner--success"
          rounded
        >
          {{ t('auth.emailVerifiedPleaseLogin') }}
        </q-banner>

        <!-- Account not verified banner -->
        <q-banner v-if="accountNotVerified" class="q-mb-md auth-banner auth-banner--error" rounded>
          {{ t('auth.accountNotVerified') }}
        </q-banner>

        <!-- Rate limit banner -->
        <q-banner v-if="rateLimited" class="q-mb-md auth-banner auth-banner--error" rounded>
          {{ t('auth.accountLocked') }}
        </q-banner>

        <q-form @submit.prevent="handleLogin" class="q-gutter-y-md">
          <q-input
            v-model="form.email"
            type="email"
            :label="t('auth.email')"
            outlined
            lazy-rules
            :rules="[required, validEmail]"
            :error="hasFieldError('id')"
            :error-message="getFieldError('id')"
          />

          <q-input
            v-model="form.password"
            :type="isPwd ? 'password' : 'text'"
            :label="t('auth.password')"
            outlined
            lazy-rules
            :rules="[required]"
            :error="hasFieldError('password')"
            :error-message="getFieldError('password')"
          >
            <template #append>
              <q-icon
                :name="isPwd ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                style="color: var(--text-muted)"
                @click="isPwd = !isPwd"
              />
            </template>
          </q-input>

          <q-banner
            v-if="hasError && !isValidationError"
            class="auth-banner auth-banner--error"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-btn
            type="submit"
            class="full-width btn-accent q-mt-sm"
            :loading="isSubmitting"
            :disable="isSubmitting"
            :label="t('auth.login')"
            unelevated
            size="md"
          />
        </q-form>

        <div class="text-center q-mt-md">
          <router-link to="/forgot-password" class="auth-link">
            {{ t('auth.forgotPassword') }}
          </router-link>
        </div>

        <div class="auth-divider">
          <span>{{ $t('common.or') }}</span>
        </div>

        <div class="text-center">
          <div class="text-meta q-mb-xs">{{ t('auth.noAccount') }}</div>
          <router-link to="/coach-register" class="auth-link">
            {{ t('auth.registerAsCoach') }}
          </router-link>
          <span class="text-meta q-mx-xs">&middot;</span>
          <router-link to="/parent-register" class="auth-link">
            {{ t('auth.registerAsParent') }}
          </router-link>
          <span class="text-meta q-mx-xs">&middot;</span>
          <router-link to="/player-register" class="auth-link">
            {{ t('auth.registerAsPlayer') }}
          </router-link>
        </div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi } from 'src/api/auth.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'
import { useAuthStore } from 'src/stores/auth.store'
import { useSession } from 'src/composables/useSession'
import { routeForRole } from 'src/router/roleRoutes'
import { isSafeRedirect } from 'src/router/safeRedirect'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const authStore = useAuthStore()
const { initSession } = useSession()

const {
  setError,
  clearError,
  hasError,
  errorMessage,
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError,
} = useErrorHandler()

const form = ref({ email: '', password: '' })
const isPwd = ref(true)
const isSubmitting = ref(false)
const accountNotVerified = ref(false)
const rateLimited = ref(false)

const required = (val) => !!val || t('validation.required')
const validEmail = (val) => /.+@.+\..+/.test(val) || t('validation.email')

async function handleLogin() {
  clearError()
  accountNotVerified.value = false
  rateLimited.value = false
  isSubmitting.value = true
  try {
    const response = await authApi.skillarsLogin(form.value.email, form.value.password)
    authStore.setUser(response)
    initSession()
    const redirect = route.query.redirect
    const safePath = isSafeRedirect(redirect, router) ? redirect : routeForRole(response.role)
    router.push(safePath)
  } catch (err) {
    const status = err?.response?.status
    if (status === 403) {
      accountNotVerified.value = true
    } else if (status === 429) {
      rateLimited.value = true
    } else {
      setError(err)
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.auth-brand {
  text-align: center;
}
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card {
  padding: 32px;
}
// skillars-deferred-141 AC5, corrected in code review: the four outer banners rendered flush
// against the form, and `q-mb-md` was NOT being out-specified — it applies cleanly (single
// unqualified `.q-mb-md { margin-bottom: 16px }` in quasar.css, no !important; the project's only
// .q-banner rule touches border-radius). The real suppressor is the gutter's own negative top
// margin: `.q-gutter-y-md, .q-gutter-md { margin-top: -16px }` applies to the <q-form> under BOTH
// classes, and .q-form is `position: relative` only — a block box — so the banner's +16px collapses
// against the form's -16px to a net 0px gap. Adding another 16px to the banner (the first attempt at
// this AC) therefore changed nothing: same value, same cancellation. 32px out-runs it: 32 - 16 = 16px.
// Measured in headless Chrome against quasar.css: 0px -> 16px, with the no-banner lead->form gap and
// the submit button's alignment both unchanged.
// Deliberately scoped to `> .auth-banner` (the four banners that are direct children of the card).
// The fifth .auth-banner lives INSIDE the form (the submit error banner) and carries no `q-mb-md`
// because the gutter already spaces it; giving it a bottom margin pushed the submit button 16px down
// (8px -> 24px, measured — q-btn is inline-flex, so no margin collapsing rescues it).
.auth-card > .auth-banner {
  margin-bottom: 32px;
}
.auth-link {
  color: var(--accent-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: opacity 0.15s ease;
  &:hover {
    opacity: 0.8;
  }
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--warning {
    background: rgba(255, 184, 77, 0.12) !important;
    color: var(--accent-warning) !important;
  }
  &--error {
    background: rgba(255, 95, 122, 0.12) !important;
    color: var(--accent-danger) !important;
  }
  &--success {
    background: rgba(0, 200, 83, 0.12) !important;
    color: var(--accent-success) !important;
  }
}
.auth-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0;
  &::before,
  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--border-soft);
  }
  span {
    font-size: 12px;
    color: var(--text-muted);
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
}
</style>
