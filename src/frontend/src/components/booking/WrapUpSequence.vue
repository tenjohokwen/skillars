<template>
  <div class="wrap-up">
    <!-- Step pip indicator -->
    <div class="wrap-up__pips">
      <div
        v-for="n in 4"
        :key="n"
        class="wrap-up__pip"
        :class="{ 'wrap-up__pip--active': n === step, 'wrap-up__pip--done': n < step }"
      />
    </div>

    <!-- Step 1: Attendance -->
    <div v-if="step === 1" class="wrap-up__step">
      <div class="text-h6 q-mb-lg">{{ t('booking.wrapUp.step1Title') }}</div>
      <q-checkbox
        v-model="playerAttended"
        :label="t('booking.wrapUp.step1Label') + ': ' + playerName"
        class="q-mb-xl"
        size="xl"
        @update:model-value="
          () => {
            attendanceTouched = true
          }
        "
      />
      <q-btn
        unelevated
        class="wrap-up__next-btn"
        :label="t('common.next')"
        :disable="!attendanceTouched"
        @click="step = 2"
      />
    </div>

    <!-- Step 2: Ratings -->
    <div v-else-if="step === 2" class="wrap-up__step">
      <div class="text-h6 q-mb-md">{{ t('booking.wrapUp.step2Title') }}</div>

      <div v-if="currentRating === 'effort'" class="wrap-up__rating-row">
        <div class="text-subtitle1 q-mb-sm">{{ t('booking.wrapUp.step2Effort') }}</div>
        <q-rating
          v-model="effortRating"
          size="48px"
          color="amber"
          icon="star_border"
          icon-selected="star"
          :max="5"
          @update:model-value="
            (v) => {
              if (v === 5) currentRating = 'focus'
            }
          "
        />
        <q-btn
          v-if="effortRating > 0 && effortRating < 5"
          flat
          dense
          :label="t('common.next')"
          class="q-mt-md"
          @click="currentRating = 'focus'"
        />
      </div>

      <div v-else-if="currentRating === 'focus'" class="wrap-up__rating-row">
        <div class="text-subtitle1 q-mb-sm">{{ t('booking.wrapUp.step2Focus') }}</div>
        <q-rating
          v-model="focusRating"
          size="48px"
          color="amber"
          icon="star_border"
          icon-selected="star"
          :max="5"
          @update:model-value="
            (v) => {
              if (v === 5) currentRating = 'technique'
            }
          "
        />
        <q-btn
          v-if="focusRating > 0 && focusRating < 5"
          flat
          dense
          :label="t('common.next')"
          class="q-mt-md"
          @click="currentRating = 'technique'"
        />
      </div>

      <div v-else-if="currentRating === 'technique'" class="wrap-up__rating-row">
        <div class="text-subtitle1 q-mb-sm">{{ t('booking.wrapUp.step2Technique') }}</div>
        <q-rating
          v-model="techniqueRating"
          size="48px"
          color="amber"
          icon="star_border"
          icon-selected="star"
          :max="5"
          @update:model-value="
            (v) => {
              if (v === 5)
                setTimeout(() => {
                  if (techniqueRating === 5) step = 3
                }, 200)
            }
          "
        />
        <q-btn
          v-if="techniqueRating > 0 && techniqueRating < 5"
          flat
          dense
          :label="t('common.next')"
          class="q-mt-md"
          @click="step = 3"
        />
      </div>
    </div>

    <!-- Step 3: Homework (i18n keys keep their historical step4* names) -->
    <div v-else-if="step === 3" class="wrap-up__step">
      <div class="text-h6 q-mb-md">{{ t('booking.wrapUp.step4Title') }}</div>

      <div class="wrap-up__dna-chart-area q-mb-md">
        <q-inner-loading :showing="dnaLoading" />
        <div v-if="dnaError" class="text-grey-6 text-center q-pa-md">{{ dnaError }}</div>
        <SessionDNAChart
          v-else
          :session-dna="
            sessionDnaData ?? {
              technical: 0,
              physical: 0,
              cognitive: 0,
              matchRealism: 0,
              weakFootFocus: 0,
            }
          "
          variant="full"
          :show-confirmation="true"
        />
      </div>

      <div
        v-if="drillSuggestions.length === 0"
        class="text-body2 q-mb-lg"
        style="color: var(--text-secondary)"
      >
        {{ t('booking.wrapUp.step4NoSuggestions') }}
      </div>
      <div v-else class="wrap-up__drills q-mb-lg">
        <q-card
          v-for="drill in drillSuggestions"
          :key="drill.id"
          class="wrap-up__drill-card q-mb-sm cursor-pointer"
          :class="{ 'wrap-up__drill-card--selected': homeworkDrillIds.includes(drill.id) }"
          @click="toggleDrill(drill.id)"
        >
          <q-card-section>{{ drill.name }}</q-card-section>
        </q-card>
      </div>

      <q-btn unelevated class="wrap-up__next-btn" :label="t('common.next')" @click="step = 4" />
    </div>

    <!-- Step 4: Session Recap (i18n keys keep their historical step5* names) -->
    <div v-else-if="step === 4" class="wrap-up__step">
      <div class="text-h6 q-mb-lg">{{ t('booking.wrapUp.step5Title') }}</div>

      <q-input
        v-model="recapWhatWeDid"
        type="textarea"
        outlined
        autogrow
        maxlength="500"
        counter
        class="q-mb-md full-width"
        :label="t('booking.wrapUp.step5WhatWeDidLabel')"
        :placeholder="t('booking.wrapUp.step5WhatWeDidPlaceholder')"
      />
      <q-input
        v-model="recapWhatsNext"
        type="textarea"
        outlined
        autogrow
        maxlength="500"
        counter
        class="q-mb-md full-width"
        :label="t('booking.wrapUp.step5WhatsNextLabel')"
        :placeholder="t('booking.wrapUp.step5WhatsNextPlaceholder')"
      />
      <q-input
        v-model="recapWhatWentWell"
        type="textarea"
        outlined
        autogrow
        maxlength="500"
        counter
        class="q-mb-md full-width"
        data-testid="recap-what-went-well"
        :label="t('booking.wrapUp.step5WhatWentWellLabel')"
        :placeholder="t('booking.wrapUp.step5WhatWentWellPlaceholder')"
      />

      <q-btn
        unelevated
        class="wrap-up__next-btn"
        :label="t('booking.wrapUp.step5Done')"
        :loading="submitting"
        :disable="
          !recapWhatWeDid.trim() ||
          !recapWhatsNext.trim() ||
          recapWhatWeDid.length > 500 ||
          recapWhatsNext.length > 500 ||
          recapWhatWentWell.length > 500
        "
        @click="handleSubmitWrapUp"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'
import { getDrillSuggestions } from 'src/api/booking.api'
import { sessionApi } from 'src/api/session.api'
import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'

const props = defineProps({
  bookingId: { type: String, required: true },
  playerId: { type: [Number, String], required: true },
  playerName: { type: String, required: true },
  isLiveMode: { type: Boolean, default: true },
})

const emit = defineEmits(['wrap-up-complete', 'cancelled'])

const { t } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()

const step = ref(1)
const playerAttended = ref(true)
const attendanceTouched = ref(false)
const effortRating = ref(0)
const focusRating = ref(0)
const techniqueRating = ref(0)
const currentRating = ref('effort')
const homeworkDrillIds = ref([])
const recapWhatWeDid = ref('')
const recapWhatsNext = ref('')
const recapWhatWentWell = ref('')
const drillSuggestions = ref([])
const submitting = ref(false)
const sessionDnaData = ref(null)
const dnaLoading = ref(false)
const dnaError = ref(null)

function toggleDrill(drillId) {
  const idx = homeworkDrillIds.value.indexOf(drillId)
  if (idx === -1) {
    if (homeworkDrillIds.value.length < 2) {
      homeworkDrillIds.value.push(drillId)
    }
  } else {
    homeworkDrillIds.value.splice(idx, 1)
  }
}

async function fetchDrillSuggestions() {
  try {
    const res = await getDrillSuggestions(props.bookingId)
    drillSuggestions.value = res ?? []
  } catch {
    drillSuggestions.value = []
  }
}

async function handleSubmitWrapUp() {
  submitting.value = true
  let submitSucceeded = false
  try {
    await bookingStore.handleSubmitWrapUp(props.bookingId, {
      playerAttended: playerAttended.value,
      effortRating: effortRating.value || null,
      focusRating: focusRating.value || null,
      techniqueRating: techniqueRating.value || null,
      homeworkDrillIds: homeworkDrillIds.value,
      mode: props.isLiveMode ? 'LIVE' : 'QUICK',
      recapWhatWeDid: recapWhatWeDid.value.trim(),
      recapWhatsNext: recapWhatsNext.value.trim(),
      recapWhatWentWell: recapWhatWentWell.value.trim() || null,
    })
    submitSucceeded = true
  } catch (err) {
    // skillars-12-2 code review (P3): branch on errorKey like the sibling handlers in
    // CoachCommandCenterPage.vue (handleStartSession/handleQuickComplete) — a bare catch would
    // collapse a real 403/409/5xx into a misleading "recap submission failed" message.
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.concurrentModification') {
      $q.notify({ type: 'negative', message: t('booking.errors.concurrentModification') })
    } else {
      $q.notify({ type: 'negative', message: t('booking.wrapUp.step5SubmitError') })
    }
  } finally {
    submitting.value = false
  }
  // Emitted outside the try/catch so a throwing parent listener on an already-successful submit
  // is never mistaken for (and reported as) a submit failure.
  if (submitSucceeded) {
    emit('wrap-up-complete')
  }
}

async function fetchSessionDna() {
  dnaLoading.value = true
  dnaError.value = null
  try {
    const res = await sessionApi.getSessionPlanByBooking(props.bookingId)
    sessionDnaData.value = res?.sessionDna ?? null
  } catch {
    sessionDnaData.value = null
    dnaError.value = t('booking.wrapUp.step4DnaError')
  } finally {
    dnaLoading.value = false
  }
}

onMounted(() => {
  fetchDrillSuggestions()
  fetchSessionDna()
})
</script>

<style lang="scss" scoped>
.wrap-up {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100dvh;
  z-index: 2000;
  background: var(--surface-page);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  padding: 32px 24px;
  overflow-y: auto;
}

.wrap-up__pips {
  display: flex;
  gap: 8px;
  margin-bottom: 40px;
}

.wrap-up__pip {
  width: 48px;
  height: 6px;
  border-radius: 3px;
  background: var(--border-subtle);
  transition: background 0.2s;

  &--active {
    background: var(--accent-primary);
  }

  &--done {
    background: var(--accent-primary);
    opacity: 0.4;
  }
}

.wrap-up__step {
  width: 100%;
  max-width: 480px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.wrap-up__dna-chart-area {
  position: relative;
  width: 100%;
  min-height: 80px;
}

.wrap-up__rating-row {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.wrap-up__next-btn {
  width: 100%;
  height: 56px;
  background: var(--accent-primary);
  color: #fff;
  border-radius: 12px;
  font-size: 18px;
  font-weight: 600;
  margin-top: 32px;
}

.wrap-up__drill-card {
  border: 2px solid var(--border-subtle);
  border-radius: 8px;
  transition: border-color 0.15s;

  &--selected {
    border-color: var(--accent-primary);
  }
}
</style>
