<template>
  <q-page class="auth-page">
    <div class="auth-card-container--wide fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">{{ t('auth.parent.registerSubtitle') }}</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.parent.registerTitle') }}</div>

        <q-banner
          v-if="hasError && !hasFieldErrors"
          class="q-mb-md auth-banner auth-banner--error"
          rounded
        >
          {{ emailInUse ? t('auth.parent.emailInUse') : errorMessage }}
          <template v-if="emailInUse">
            <span>&nbsp;</span>
            <router-link to="/login" class="auth-link">{{ t('auth.parent.signInInstead') }}</router-link>
          </template>
          <template v-else-if="helpCode">
            <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>

        <q-form @submit.prevent="handleRegister">
          <div class="row q-col-gutter-md">

            <div class="col-12 col-md-6">
              <div>
                <q-input
                  v-model="form.firstName"
                  :label="t('auth.firstName')"
                  outlined lazy-rules
                  :rules="[required, maxLen50]"
                  :error="hasFieldError('firstName')"
                  :error-message="getFieldError('firstName')"
                />
                <q-banner
                  v-if="firstNameHasContact"
                  class="contact-warning q-mt-xs"
                  rounded dense
                >
                  {{ t('auth.parent.contactDetailWarning') }}
                </q-banner>
              </div>
            </div>

            <div class="col-12 col-md-6">
              <div>
                <q-input
                  v-model="form.lastName"
                  :label="t('auth.lastName')"
                  outlined lazy-rules
                  :rules="[required, maxLen50]"
                  :error="hasFieldError('lastName')"
                  :error-message="getFieldError('lastName')"
                />
                <q-banner
                  v-if="lastNameHasContact"
                  class="contact-warning q-mt-xs"
                  rounded dense
                >
                  {{ t('auth.parent.contactDetailWarning') }}
                </q-banner>
              </div>
            </div>

            <div class="col-12 col-md-6">
              <q-input
                v-model="form.email"
                type="email"
                :label="t('auth.email')"
                outlined lazy-rules
                :rules="[required, validEmail]"
                :error="hasFieldError('email')"
                :error-message="getFieldError('email')"
              />
            </div>

            <div class="col-12 col-md-6">
              <q-input
                v-model="form.phone"
                type="tel"
                :label="t('auth.phone')"
                outlined lazy-rules
                :rules="[required]"
                :error="hasFieldError('phone')"
                :error-message="getFieldError('phone')"
              />
            </div>

            <div class="col-12 col-md-6">
              <q-input
                v-model="form.password"
                :type="isPwd ? 'password' : 'text'"
                :label="t('auth.password')"
                outlined lazy-rules
                :rules="[required, minLen8]"
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
            </div>

          </div>

          <div class="q-mt-lg q-gutter-y-md">

            <div>
              <div class="policy-scroll-box" @scroll="onScroll('tos', $event)">
                <p>{{ t('auth.parent.tosLabel') }} — Lorem ipsum dolor sit amet, consectetur adipiscing elit. By using Skillars, you agree to our terms and conditions. These terms govern your use of the platform, including booking sessions, payments, and data handling. You must be at least 18 years old to register as a parent. All accounts are subject to our acceptable use policy.</p>
              </div>
              <q-checkbox
                v-model="tosAccepted"
                :disable="!tosScrolled"
                :label="t('auth.parent.tosLabel')"
                color="primary"
              />
            </div>

            <div>
              <div class="policy-scroll-box" @scroll="onScroll('privacy', $event)">
                <p>{{ t('auth.parent.privacyLabel') }} — We collect personal data to provide our services. Your data is processed in accordance with GDPR and applicable data protection laws. We do not sell your personal data to third parties. You may request deletion of your data at any time. Session recordings and coaching data are retained for the period specified in our privacy policy.</p>
              </div>
              <q-checkbox
                v-model="privacyAccepted"
                :disable="!privacyScrolled"
                :label="t('auth.parent.privacyLabel')"
                color="primary"
              />
            </div>

            <div>
              <div class="policy-scroll-box" @scroll="onScroll('parentConsent', $event)">
                <p>{{ t('auth.parent.parentConsentLabel') }} — As the legal guardian of the player(s) you register, you consent to Skillars collecting and processing data about your child for coaching purposes. You confirm you are the legal parent or guardian. You are responsible for ensuring your child's participation is appropriate. You may withdraw consent at any time by contacting support.</p>
              </div>
              <q-checkbox
                v-model="parentConsentAccepted"
                :disable="!parentConsentScrolled"
                :label="t('auth.parent.parentConsentLabel')"
                color="primary"
              />
            </div>

          </div>

          <q-btn
            type="submit"
            class="full-width btn-accent q-mt-lg"
            :label="t('auth.createAccount')"
            :loading="isSubmitting"
            :disable="!tosAccepted || !privacyAccepted || !parentConsentAccepted || isSubmitting"
            unelevated size="md"
          />

          <div class="text-center q-mt-md">
            <span class="text-meta">{{ t('auth.haveAccount') }}</span>
            <router-link to="/login" class="auth-link q-ml-xs">
              {{ t('auth.login') }}
            </router-link>
          </div>
        </q-form>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { parentRegistrationApi } from 'src/api/parentRegistration.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'
import { useContactDetector } from 'src/composables/useContactDetector'

const router = useRouter()
const { t, locale } = useI18n()
const {
  setError, clearError, hasError, hasFieldErrors, errorMessage,
  helpCode, hasFieldError, getFieldError, errorKey,
} = useErrorHandler()

const form = ref({ firstName: '', lastName: '', email: '', password: '', phone: '' })
const isPwd = ref(true)
const isSubmitting = ref(false)
const tosAccepted = ref(false)
const privacyAccepted = ref(false)
const parentConsentAccepted = ref(false)
const tosScrolled = ref(false)
const privacyScrolled = ref(false)
const parentConsentScrolled = ref(false)

const scrollFlags = { tos: tosScrolled, privacy: privacyScrolled, parentConsent: parentConsentScrolled }

function onScroll(key, event) {
  const el = event.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 4) {
    scrollFlags[key].value = true
  }
}

const firstNameRef = computed(() => form.value.firstName)
const lastNameRef = computed(() => form.value.lastName)
const { hasContactDetail: firstNameHasContact } = useContactDetector(firstNameRef)
const { hasContactDetail: lastNameHasContact } = useContactDetector(lastNameRef)

const emailInUse = computed(() => errorKey.value === 'security.emailInUse')

const required = val => !!val || t('validation.required')
const validEmail = val => /.+@.+\..+/.test(val) || t('validation.email')
const minLen8 = val => val.length >= 8 || t('validation.minLength', { min: 8 })
const maxLen50 = val => !val || val.length <= 50 || t('validation.maxLength', { max: 50 })

async function handleRegister() {
  clearError()
  isSubmitting.value = true
  try {
    const langKey = locale.value.split('-')[0]
    await parentRegistrationApi.register({
      firstName: form.value.firstName,
      lastName: form.value.lastName,
      email: form.value.email,
      password: form.value.password,
      phone: form.value.phone,
      langKey,
    })
    sessionStorage.setItem('pendingParentEmail', form.value.email)
    router.push('/parent/email-pending')
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.auth-brand { text-align: center; }
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card { padding: 32px; }
.auth-link {
  color: var(--accent-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  &:hover { opacity: 0.8; }
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
}
.contact-warning {
  background: var(--surface-warning) !important;
  color: var(--accent-warning) !important;
  border-radius: 8px !important;
  font-size: 13px;
}
.policy-scroll-box {
  max-height: 120px;
  overflow-y: auto;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin-bottom: 8px;
  background: var(--surface-glass);
}
</style>
