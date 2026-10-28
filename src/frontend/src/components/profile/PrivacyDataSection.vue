<template>
  <div class="privacy-data-section">
    <!-- Export -->
    <div class="privacy-block">
      <div class="text-label">{{ t('privacy.exportTitle') }}</div>
      <div class="text-meta q-mt-xs">
        {{ isChild ? t('privacy.exportHintChild', { name: playerName }) : t('privacy.exportHint') }}
      </div>

      <div v-if="loadError" class="text-meta q-mt-sm" style="color: var(--accent-danger)">
        {{ t('privacy.loadError') }}
      </div>

      <div v-else-if="latestExport" class="q-mt-sm" data-testid="privacy-export-status">
        <template v-if="exportInFlight">
          <div class="row items-center q-gutter-sm">
            <q-spinner size="16px" />
            <span class="text-body2">{{ t('privacy.exportProcessing') }}</span>
          </div>
          <div v-if="pollingExhausted" class="text-meta q-mt-xs" data-testid="privacy-poll-hint">
            {{ t('privacy.exportStillProcessing') }}
          </div>
        </template>
        <template v-else-if="latestExport.status === 'COMPLETED' && latestExport.downloadUrl">
          <div class="text-body2">
            {{ t('privacy.exportReady', { date: formatDate(latestExport.expiresAt) }) }}
          </div>
          <q-btn
            unelevated
            dense
            no-caps
            color="primary"
            icon="download"
            class="q-mt-xs"
            data-testid="privacy-download-btn"
            :label="t('privacy.download')"
            :href="latestExport.downloadUrl"
            target="_blank"
            rel="noopener"
          />
        </template>
        <div
          v-else-if="latestExport.status === 'COMPLETED'"
          class="text-body2"
          data-testid="privacy-export-expired"
        >
          {{ t('privacy.exportExpired') }}
        </div>
        <div
          v-else-if="latestExport.status === 'FAILED'"
          class="text-body2"
          style="color: var(--accent-danger)"
          data-testid="privacy-export-failed"
        >
          {{ t('privacy.exportFailed') }}
        </div>
      </div>

      <div class="row q-gutter-sm q-mt-sm">
        <q-btn
          flat
          dense
          no-caps
          color="primary"
          icon="archive"
          data-testid="privacy-export-btn"
          :label="t('privacy.requestExport')"
          :loading="exportSubmitting"
          :disable="exportInFlight"
          @click="handleRequestExport"
        />
        <q-btn
          v-if="pollingExhausted || loadError"
          flat
          dense
          no-caps
          icon="refresh"
          data-testid="privacy-refresh-btn"
          :label="t('privacy.refresh')"
          @click="refresh"
        />
      </div>
    </div>

    <!-- Erasure -->
    <div class="privacy-block q-mt-lg">
      <div class="text-label">
        {{ isChild ? t('privacy.erasureTitleChild') : t('privacy.erasureTitle') }}
      </div>
      <div class="text-meta q-mt-xs">
        {{
          isChild ? t('privacy.erasureHintChild', { name: playerName }) : t('privacy.erasureHint')
        }}
      </div>
      <q-btn
        flat
        dense
        no-caps
        color="negative"
        icon="delete_forever"
        class="q-mt-sm"
        data-testid="privacy-erasure-open-btn"
        :label="isChild ? t('privacy.erasureActionChild') : t('privacy.erasureAction')"
        @click="openErasureDialog"
      />
    </div>

    <q-dialog v-model="erasureDialogOpen">
      <q-card
        data-testid="privacy-erasure-dialog"
        style="min-width: 320px; max-width: 520px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">
            {{
              isChild
                ? t('privacy.erasureDialogTitleChild', { name: playerName })
                : t('privacy.erasureDialogTitle')
            }}
          </div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <div class="text-body2 q-mb-sm">{{ t('privacy.erasureConsequencesIntro') }}</div>
          <ul class="q-pl-md q-my-none">
            <li v-for="key in consequenceKeys" :key="key" class="text-body2">{{ t(key) }}</li>
          </ul>
          <div class="text-body2 text-weight-bold q-mt-md">
            {{ t('privacy.confirmPrompt', { word: confirmWord }) }}
          </div>
          <q-input
            v-model="confirmText"
            data-testid="privacy-erasure-confirm-input"
            outlined
            dense
            class="q-mt-xs"
            autocomplete="off"
            :label="t('privacy.confirmLabel')"
          />
          <div
            v-if="erasureError"
            class="text-negative text-caption q-mt-sm"
            data-testid="privacy-erasure-error"
          >
            {{ erasureError }}
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            data-testid="privacy-erasure-submit-btn"
            :label="t('privacy.erasureConfirm')"
            :loading="erasureSubmitting"
            :disable="!confirmMatches"
            @click="handleErasure"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>

<script setup>
// FR-TSC-007 self-service export/erasure. Without playerId this acts on the signed-in user's own
// account; with playerId it acts for a parent-owned child profile (the parent is the only one who
// can exercise that child's rights — the child has no account). The backend is authoritative for
// every rule (ownership, active bookings, duplicate requests); this component only explains them.
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import {
  listMyGdprRequests,
  requestMyExport,
  requestMyErasure,
  listChildGdprRequests,
  requestChildExport,
  requestChildErasure,
} from 'src/api/gdpr.api'
import { usePlayerStore } from 'src/stores/playerStore'
import { useSession } from 'src/composables/useSession'

const POLL_INTERVAL_MS = 3000
const MAX_POLLS = 20

const props = defineProps({
  playerId: { type: [Number, String], default: null },
  playerName: { type: String, default: '' },
})
const emit = defineEmits(['erased'])

const { t } = useI18n()
const $q = useQuasar()
const playerStore = usePlayerStore()
const { handleLogout } = useSession()

const isChild = computed(() => props.playerId != null)

const requests = ref([])
const loadError = ref(false)
const exportSubmitting = ref(false)
const pollCount = ref(0)
const pollingExhausted = ref(false)
let pollTimer = null
let unmounted = false

const erasureDialogOpen = ref(false)
const confirmText = ref('')
const erasureSubmitting = ref(false)
const erasureError = ref('')

const latestExport = computed(() => requests.value.find((r) => r.requestType === 'EXPORT') ?? null)
const exportInFlight = computed(() =>
  ['PENDING', 'PROCESSING'].includes(latestExport.value?.status),
)

const confirmWord = computed(() => t('privacy.confirmWord'))
const confirmMatches = computed(
  () => confirmText.value.trim().toUpperCase() === confirmWord.value.toUpperCase(),
)

const consequenceKeys = computed(() =>
  isChild.value
    ? [
        'privacy.consequenceChildProfile',
        'privacy.consequenceChildDevelopment',
        'privacy.consequenceChildBookings',
        'privacy.consequenceChildBlocked',
        'privacy.consequenceIrreversible',
      ]
    : [
        'privacy.consequenceAccount',
        'privacy.consequenceMessages',
        'privacy.consequenceDevelopment',
        'privacy.consequenceCredits',
        'privacy.consequenceBlocked',
        'privacy.consequenceIrreversible',
      ],
)

function fetchRequests() {
  return isChild.value ? listChildGdprRequests(props.playerId) : listMyGdprRequests()
}

async function refresh() {
  stopPolling()
  pollCount.value = 0
  pollingExhausted.value = false
  await loadRequests()
}

async function loadRequests() {
  try {
    const data = await fetchRequests()
    if (unmounted) return
    requests.value = Array.isArray(data) ? data : []
    loadError.value = false
  } catch {
    if (unmounted) return
    loadError.value = true
  }
  schedulePoll()
}

function schedulePoll() {
  stopPolling()
  if (unmounted || !exportInFlight.value) return
  if (pollCount.value >= MAX_POLLS) {
    pollingExhausted.value = true
    return
  }
  pollTimer = setTimeout(async () => {
    pollTimer = null
    pollCount.value += 1
    await loadRequests()
  }, POLL_INTERVAL_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function errorMessageKey(err) {
  const message = err?.response?.data?.errorMsg?.message
  const errorKey = err?.response?.data?.errorMsg?.errorKey
  const map = {
    'gdpr.requestAlreadyPending': 'privacy.errors.alreadyPending',
    'gdpr.exportInProgress': 'privacy.errors.exportInProgress',
    'gdpr.activeBookingsExist': isChild.value
      ? 'privacy.errors.activeBookingsChild'
      : 'privacy.errors.activeBookings',
    'gdpr.playerAlreadyErased': 'privacy.errors.playerAlreadyErased',
  }
  if (map[message]) return map[message]
  if (errorKey === 'MISSING_RIGHTS') return 'privacy.errors.notAllowed'
  return 'privacy.errors.generic'
}

async function handleRequestExport() {
  exportSubmitting.value = true
  try {
    await (isChild.value ? requestChildExport(props.playerId) : requestMyExport())
    $q.notify({ type: 'positive', message: t('privacy.exportRequested') })
  } catch (err) {
    $q.notify({ type: 'negative', message: t(errorMessageKey(err)) })
  } finally {
    exportSubmitting.value = false
  }
  // Either way the list is the truth: a 409 means a request is already running — show it.
  await refresh()
}

function openErasureDialog() {
  confirmText.value = ''
  erasureError.value = ''
  erasureDialogOpen.value = true
}

async function handleErasure() {
  if (!confirmMatches.value) return
  erasureSubmitting.value = true
  erasureError.value = ''
  try {
    if (isChild.value) {
      await requestChildErasure(props.playerId)
      erasureDialogOpen.value = false
      $q.notify({ type: 'positive', message: t('privacy.erasureChildDone') })
      emit('erased', props.playerId)
      try {
        await playerStore.fetchPlayers()
        if (String(playerStore.activePlayerId) === String(props.playerId)) {
          playerStore.setActivePlayer(playerStore.players[0]?.id ?? null)
        }
      } catch {
        /* the hub refreshes on its next load */
      }
    } else {
      // The erasure runs synchronously: by the time this resolves the account is anonymised and
      // locked and its refresh tokens revoked, so the only sensible next step is signing out.
      await requestMyErasure()
      erasureDialogOpen.value = false
      $q.notify({ type: 'positive', message: t('privacy.erasureDone') })
      await handleLogout()
    }
  } catch (err) {
    erasureError.value = t(errorMessageKey(err))
    if (err?.response?.data?.errorMsg?.message === 'gdpr.requestAlreadyPending') {
      await refresh()
    }
  } finally {
    erasureSubmitting.value = false
  }
}

function formatDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}

onMounted(loadRequests)
onBeforeUnmount(() => {
  unmounted = true
  stopPolling()
})

defineExpose({ refresh })
</script>

<style lang="scss" scoped>
.privacy-block + .privacy-block {
  border-top: 1px solid var(--border-soft);
  padding-top: 16px;
}
</style>
