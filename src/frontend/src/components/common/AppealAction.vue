<template>
  <span class="appeal-action">
    <q-chip
      v-if="existing"
      dense
      square
      size="sm"
      :color="chipColor"
      text-color="white"
      :icon="chipIcon"
      data-testid="appeal-status-chip"
    >
      {{ t(`appeals.status.${existing.status}`) }}
      <q-tooltip v-if="existing.decisionNote" max-width="320px">
        {{ existing.decisionNote }}
      </q-tooltip>
    </q-chip>
    <q-btn
      v-else-if="canAppeal"
      flat
      dense
      no-caps
      size="sm"
      color="primary"
      icon="gavel"
      data-testid="appeal-open-btn"
      :label="t('appeals.action')"
      @click="openDialog"
    />

    <q-dialog v-model="dialogOpen">
      <q-card data-testid="appeal-dialog" style="min-width: 320px; max-width: 520px; width: 100%">
        <q-card-section>
          <div class="text-h6">{{ t('appeals.dialogTitle') }}</div>
          <div class="text-caption q-mt-xs">{{ t(`appeals.subject.${subjectType}`) }}</div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <p class="text-body2">{{ t('appeals.dialogExplanation') }}</p>
          <q-input
            v-model="reason"
            data-testid="appeal-reason-input"
            type="textarea"
            outlined
            autogrow
            maxlength="2000"
            counter
            :label="t('appeals.reasonLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            data-testid="appeal-submit-btn"
            :label="t('appeals.submit')"
            :loading="submitting"
            :disable="!reason.trim()"
            @click="submitDialog"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </span>
</template>

<script setup>
// FR-ADM-004 / FR-TSC-003: one appeal per enforcement action, within 14 days of it; the platform's
// decision is final. The 14-day check here is UX only — AppealService.submit is authoritative.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useAppealStore } from 'src/stores/appeal.store'

const APPEAL_WINDOW_MS = 14 * 24 * 60 * 60 * 1000

const props = defineProps({
  subjectType: { type: String, required: true },
  subjectId: { type: [String, Number], required: true },
  // ISO timestamp of the enforcement; null/absent = unknown, leave the decision to the backend.
  enforcedAt: { type: String, default: null },
})

const { t } = useI18n()
const $q = useQuasar()
const appealStore = useAppealStore()

const dialogOpen = ref(false)
const reason = ref('')
const submitting = ref(false)

const existing = computed(() =>
  appealStore.findFor(props.subjectType, props.subjectId, props.enforcedAt),
)

const withinWindow = computed(() => {
  if (!props.enforcedAt) return true
  const enforced = new Date(props.enforcedAt).getTime()
  if (Number.isNaN(enforced)) return true
  return Date.now() <= enforced + APPEAL_WINDOW_MS
})

const canAppeal = computed(() => !existing.value && withinWindow.value)

const chipColor = computed(
  () => ({ OPEN: 'warning', UPHELD: 'negative', OVERTURNED: 'positive' })[existing.value?.status],
)
const chipIcon = computed(
  () =>
    ({ OPEN: 'hourglass_empty', UPHELD: 'block', OVERTURNED: 'check_circle' })[
      existing.value?.status
    ],
)

onMounted(async () => {
  try {
    await appealStore.load()
  } catch {
    // Non-fatal: without the list the button still renders, and a duplicate submit answers 409.
  }
})

function openDialog() {
  reason.value = ''
  dialogOpen.value = true
}

const ERROR_MESSAGES = {
  'appeals.alreadyAppealed': 'appeals.errors.alreadyAppealed',
  'appeals.windowExpired': 'appeals.errors.windowExpired',
  'appeals.notEnforced': 'appeals.errors.notEnforced',
  'appeals.notAppealable': 'appeals.errors.notAppealable',
  'appeals.subjectNotFound': 'appeals.errors.subjectNotFound',
}

async function submitDialog() {
  submitting.value = true
  try {
    await appealStore.submit({
      subjectType: props.subjectType,
      subjectId: String(props.subjectId),
      reason: reason.value.trim(),
    })
    dialogOpen.value = false
    $q.notify({ type: 'positive', message: t('appeals.submitSuccess') })
  } catch (err) {
    const key = err?.response?.data?.errorMsg?.message
    $q.notify({ type: 'negative', message: t(ERROR_MESSAGES[key] || 'appeals.errors.generic') })
    if (key === 'appeals.alreadyAppealed') {
      dialogOpen.value = false
      try {
        await appealStore.load({ force: true })
      } catch {
        /* the chip simply stays absent */
      }
    }
  } finally {
    submitting.value = false
  }
}
</script>
