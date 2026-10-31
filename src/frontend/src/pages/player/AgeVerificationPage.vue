<template>
  <q-page class="q-pa-md">
    <div class="glass-card q-pa-lg age-verification" data-test="age-verification-page">
      <div class="text-h6 q-mb-xs">{{ t('player.ageVerification.title') }}</div>

      <q-banner
        v-if="route.query.required && state !== 'VERIFIED'"
        class="q-mb-md"
        rounded
        data-test="age-verification-required"
      >
        {{ t('player.ageVerification.required') }}
      </q-banner>

      <q-inner-loading :showing="loading" />

      <template v-if="!loading && status">
        <!-- Verification switched off / Stripe Identity unconfigured: only the chat fallback applies. -->
        <div
          v-if="!status.enabled && state !== 'VERIFIED'"
          class="text-body2"
          data-test="age-unavailable"
        >
          {{ t('player.ageVerification.unavailable') }}
        </div>

        <template v-else>
          <div v-if="state === 'VERIFIED'" data-test="age-verified">
            <div class="row items-center q-gutter-sm q-mb-md">
              <q-icon name="verified" color="positive" size="28px" />
              <span class="text-body1">{{ t('player.ageVerification.verified') }}</span>
            </div>
            <q-btn
              v-if="nextPath"
              color="primary"
              unelevated
              no-caps
              :label="t('player.ageVerification.backToWhere')"
              data-test="age-continue"
              @click="continueToNext"
            />
          </div>

          <div v-else-if="state === 'BLOCKED'" data-test="age-blocked">
            <q-banner class="bg-negative text-white" rounded>
              {{ t('player.ageVerification.blocked') }}
            </q-banner>
          </div>

          <div v-else>
            <p class="text-body2">{{ t('player.ageVerification.intro') }}</p>
            <p class="text-body2">{{ t('player.ageVerification.how') }}</p>
            <p class="text-meta">{{ t('player.ageVerification.privacy') }}</p>

            <div
              v-if="state === 'PENDING'"
              class="row items-center q-gutter-sm q-my-md"
              data-test="age-pending"
            >
              <q-spinner-dots size="24px" />
              <span>{{ t('player.ageVerification.pending') }}</span>
            </div>

            <q-banner
              v-if="state === 'FAILED'"
              class="q-my-md bg-warning"
              rounded
              data-test="age-failed"
            >
              {{ t('player.ageVerification.failed') }}
            </q-banner>

            <div
              v-if="status.attemptsRemainingToday <= 0 && state !== 'PENDING'"
              class="text-negative q-my-md"
              data-test="age-exhausted"
            >
              {{ t('player.ageVerification.attemptsExhausted') }}
            </div>

            <div class="q-mt-md">
              <q-btn
                color="primary"
                unelevated
                no-caps
                :loading="starting"
                :disable="status.attemptsRemainingToday <= 0 && state !== 'PENDING'"
                :label="startLabel"
                data-test="age-start"
                @click="start"
              />
              <div class="text-meta q-mt-sm">
                {{
                  t('player.ageVerification.attemptsLeft', { count: status.attemptsRemainingToday })
                }}
              </div>
            </div>
          </div>
        </template>
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { ageVerificationApi } from 'src/api/ageVerification.api'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const $q = useQuasar()

const POLL_INTERVAL_MS = 4000
const MAX_POLLS = 45 // ~3 minutes; Stripe normally answers within a minute

const status = ref(null)
const loading = ref(true)
const starting = ref(false)
let pollTimer = null
let polls = 0

const state = computed(() => status.value?.state ?? 'NOT_STARTED')
const startLabel = computed(() => {
  if (state.value === 'FAILED') return t('player.ageVerification.retry')
  if (state.value === 'PENDING') return t('player.ageVerification.resume')
  return t('player.ageVerification.start')
})

// Only same-app paths may be followed after verification (the value comes from the query string).
const nextPath = computed(() => {
  const next = route.query.next
  return typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : null
})

function continueToNext() {
  router.push(nextPath.value)
}

async function load() {
  try {
    status.value = await ageVerificationApi.getStatus()
  } catch {
    status.value = null
  } finally {
    loading.value = false
  }
  schedulePoll()
}

// While Stripe is checking the document the webhook flips the state; poll until it does (bounded).
function schedulePoll() {
  stopPoll()
  if (state.value === 'PENDING' && polls < MAX_POLLS) {
    pollTimer = setTimeout(() => {
      polls += 1
      load()
    }, POLL_INTERVAL_MS)
  }
}

function stopPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function start() {
  starting.value = true
  try {
    const res = await ageVerificationApi.start()
    if (res?.url) {
      // Hosted Stripe Identity page; it returns the player to this route, where the poll picks up the outcome.
      window.location.href = res.url
      return
    }
    await load()
  } catch (err) {
    const key = err?.response?.data?.errorMsg?.errorKey
    $q.notify({
      type: 'negative',
      message:
        key === 'security.ageVerificationAttemptsExceeded'
          ? t('player.ageVerification.attemptsExhausted')
          : t('player.ageVerification.startError'),
    })
    await load()
  } finally {
    starting.value = false
  }
}

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<style lang="scss" scoped>
.age-verification {
  max-width: 640px;
  margin: 0 auto;
  position: relative;
}
</style>
