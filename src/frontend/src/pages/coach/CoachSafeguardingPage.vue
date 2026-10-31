<template>
  <q-page class="q-pa-md">
    <div class="safeguarding-page glass-card q-pa-lg">
      <div class="text-h6">{{ t('safeguarding.coach.title') }}</div>
      <div class="text-body2 q-mb-md" style="color: var(--text-secondary)">
        {{ t('safeguarding.coach.subtitle') }}
      </div>

      <q-banner v-if="loadError" class="q-mb-md" rounded data-testid="safeguarding-load-error">
        <template #avatar><q-icon name="mdi-alert" color="negative" /></template>
        {{ t('safeguarding.coach.loadError') }}
      </q-banner>

      <div v-if="loading" class="flex flex-center q-py-xl"><q-spinner size="48px" /></div>

      <template v-else-if="status">
        <div class="row items-center q-gutter-sm q-mb-sm">
          <span class="text-weight-medium">{{ t('safeguarding.coach.statusLabel') }}:</span>
          <q-chip
            dense
            :color="status.status === 'VERIFIED' ? 'positive' : 'grey-6'"
            text-color="white"
            data-testid="safeguarding-status"
            >{{ statusLabel }}</q-chip
          >
        </div>

        <div
          v-if="status.validUntil && status.status === 'VERIFIED'"
          data-testid="safeguarding-valid"
        >
          {{ t('safeguarding.coach.validUntil', { date: formatMonthYear(status.validUntil) }) }}
        </div>
        <div v-if="status.status === 'EXPIRED'" data-testid="safeguarding-expired">
          {{ t('safeguarding.coach.expiredNote') }}
        </div>
        <div v-if="status.status === 'REJECTED'" data-testid="safeguarding-rejected">
          {{ t('safeguarding.coach.rejectedNote') }}
        </div>

        <div v-if="pending" class="q-mt-sm" data-testid="safeguarding-pending">
          <div>
            {{ t('safeguarding.coach.requestedOn', { date: formatDate(status.requestedAt) }) }}
          </div>
          <div v-if="status.uploadOnFile">
            {{
              t('safeguarding.coach.uploadDeletesOn', { date: formatDate(status.uploadDeleteAt) })
            }}
          </div>
          <div v-else>{{ t('safeguarding.coach.noUploadNote') }}</div>
        </div>

        <q-card flat bordered class="q-mt-md q-pa-md">
          <div class="text-subtitle2">{{ t('safeguarding.coach.privacyTitle') }}</div>
          <ul class="q-mb-none q-pl-md">
            <li>{{ t('safeguarding.coach.privacy1') }}</li>
            <li>{{ t('safeguarding.coach.privacy2', { days: status.uploadRetentionDays }) }}</li>
            <li>{{ t('safeguarding.coach.privacy3') }}</li>
          </ul>
        </q-card>

        <div v-if="status.canRequest" class="column q-gutter-md q-mt-md">
          <q-file
            v-model="file"
            outlined
            dense
            clearable
            accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
            :max-file-size="MAX_BYTES"
            :label="t('safeguarding.coach.fileLabel')"
            data-testid="safeguarding-file"
            @rejected="onRejected"
          />
          <div v-if="message" class="text-negative" data-testid="safeguarding-message">
            {{ message }}
          </div>
          <div>
            <q-btn
              unelevated
              no-caps
              style="background: var(--color-accent-primary, #f5a623); color: white"
              :label="
                status.status === 'VERIFIED' || status.status === 'EXPIRED'
                  ? t('safeguarding.coach.renewBtn')
                  : t('safeguarding.coach.requestBtn')
              "
              :loading="busy"
              data-testid="safeguarding-request-btn"
              @click="submit"
            />
          </div>
        </div>
        <div v-else-if="message" class="text-negative q-mt-md" data-testid="safeguarding-message">
          {{ message }}
        </div>

        <div v-if="hasAnything" class="q-mt-lg">
          <q-btn
            flat
            no-caps
            color="negative"
            :label="t('safeguarding.coach.withdrawBtn')"
            :disable="busy"
            data-testid="safeguarding-withdraw-btn"
            @click="withdrawOpen = true"
          />
        </div>
      </template>
    </div>

    <q-dialog v-model="withdrawOpen">
      <q-card style="min-width: 320px; max-width: 480px" data-testid="safeguarding-withdraw-dialog">
        <q-card-section>
          <div class="text-h6">{{ t('safeguarding.coach.withdrawTitle') }}</div>
        </q-card-section>
        <q-card-section>{{ t('safeguarding.coach.withdrawBody') }}</q-card-section>
        <q-card-actions align="right">
          <q-btn flat no-caps :label="t('safeguarding.coach.cancel')" v-close-popup />
          <q-btn
            unelevated
            no-caps
            color="negative"
            :label="t('safeguarding.coach.confirm')"
            :loading="busy"
            data-testid="safeguarding-withdraw-confirm"
            @click="withdraw"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
// S-09: the coach's side of the optional safeguarding check. The coach can only ask staff to inspect their
// certificate (optionally attaching it) and withdraw; the badge itself is set by staff.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import {
  getSafeguardingStatus,
  requestSafeguardingCheck,
  withdrawSafeguardingCheck,
} from 'src/api/marketplace.api'
import { formatMonthYear } from 'src/utils/safeguarding'

// Mirrors CoachSafeguardingService.MAX_UPLOAD_BYTES; the server re-validates size and the real file type.
const MAX_BYTES = 5 * 1024 * 1024

const { t, locale } = useI18n()
const $q = useQuasar()

const status = ref(null)
const loading = ref(false)
const loadError = ref(false)
const busy = ref(false)
const file = ref(null)
const message = ref('')
const withdrawOpen = ref(false)

const pending = computed(() => status.value?.status === 'PENDING' || status.value?.renewalPending)
// Literal keys (not t(`...${status}`)): keeps every key statically checkable by i18nTemplateKeyResolutionSpec.
const statusLabel = computed(
  () =>
    ({
      NONE: t('safeguarding.coach.status.NONE'),
      PENDING: t('safeguarding.coach.status.PENDING'),
      VERIFIED: t('safeguarding.coach.status.VERIFIED'),
      EXPIRED: t('safeguarding.coach.status.EXPIRED'),
      REJECTED: t('safeguarding.coach.status.REJECTED'),
    })[status.value?.status] ?? '',
)
const hasAnything = computed(() => !!status.value && status.value.status !== 'NONE')

function formatDate(value) {
  if (!value) return ''
  return new Date(value).toLocaleDateString(locale.value)
}

// Backend MarketplaceException keys (CoachSafeguardingService) -> user-facing copy.
function errorMessage(e) {
  switch (e?.response?.data?.errorMsg?.errorKey) {
    case 'marketplace.safeguardingFileTooLarge':
      return t('safeguarding.coach.errors.fileTooLarge')
    case 'marketplace.safeguardingFileType':
      return t('safeguarding.coach.errors.fileType')
    case 'marketplace.safeguardingFileEmpty':
      return t('safeguarding.coach.errors.fileEmpty')
    case 'marketplace.safeguardingStillValid':
      return t('safeguarding.coach.errors.stillValid')
    default:
      return t('safeguarding.coach.errors.generic')
  }
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    status.value = await getSafeguardingStatus()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onRejected() {
  message.value = t('safeguarding.coach.fileRejected')
}

async function submit() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try {
    status.value = await requestSafeguardingCheck(file.value)
    file.value = null
    $q.notify({ message: t('safeguarding.coach.requestSuccess'), type: 'positive' })
  } catch (e) {
    message.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function withdraw() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try {
    await withdrawSafeguardingCheck()
    withdrawOpen.value = false
    file.value = null
    $q.notify({ message: t('safeguarding.coach.withdrawSuccess'), type: 'positive' })
    await load()
  } catch (e) {
    withdrawOpen.value = false
    message.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.safeguarding-page {
  max-width: 640px;
}
</style>
