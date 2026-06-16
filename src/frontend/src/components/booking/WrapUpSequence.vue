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
        @update:model-value="() => { attendanceTouched = true }"
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
          @update:model-value="(v) => { if (v === 5) currentRating = 'focus' }"
        />
        <q-btn
          v-if="effortRating > 0 && effortRating < 5"
          flat dense
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
          @update:model-value="(v) => { if (v === 5) currentRating = 'technique' }"
        />
        <q-btn
          v-if="focusRating > 0 && focusRating < 5"
          flat dense
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
          @update:model-value="(v) => { if (v === 5) setTimeout(() => { if (techniqueRating === 5) step = 3 }, 200) }"
        />
        <q-btn
          v-if="techniqueRating > 0 && techniqueRating < 5"
          flat dense
          :label="t('common.next')"
          class="q-mt-md"
          @click="step = 3"
        />
      </div>
    </div>

    <!-- Step 3: Voice Note -->
    <div v-else-if="step === 3" class="wrap-up__step">
      <div class="text-h6 q-mb-lg">{{ t('booking.wrapUp.step3Title') }}</div>

      <div v-if="!isRecording" class="wrap-up__voice-row">
        <q-btn
          unelevated
          round
          size="xl"
          icon="mic"
          color="primary"
          :label="t('booking.wrapUp.step3RecordBtn')"
          class="wrap-up__mic-btn"
          @click="startRecording"
        />
        <q-btn
          outline
          round
          size="xl"
          icon="skip_next"
          class="wrap-up__skip-btn"
          :label="t('booking.wrapUp.step3SkipBtn')"
          @click="step = 4"
        />
      </div>
      <div v-else class="wrap-up__voice-row">
        <q-btn
          unelevated
          round
          size="xl"
          icon="stop"
          color="negative"
          :label="t('booking.wrapUp.step3StopBtn')"
          @click="stopRecording"
        />
        <div class="text-body2 q-mt-sm" style="color: var(--color-error)">
          {{ t('booking.wrapUp.step3Recording') }}
        </div>
      </div>

      <!-- Text fallback — always visible when not recording -->
      <q-input
        v-if="!isRecording"
        v-model="voiceNoteText"
        type="textarea"
        :placeholder="t('booking.wrapUp.step3Placeholder')"
        class="q-mt-lg"
        outlined
        autogrow
      />

      <q-btn
        v-if="!isRecording"
        unelevated
        class="wrap-up__next-btn q-mt-md"
        :label="t('common.next')"
        @click="step = 4"
      />
    </div>

    <!-- Step 4: Homework -->
    <div v-else-if="step === 4" class="wrap-up__step">
      <div class="text-h6 q-mb-md">{{ t('booking.wrapUp.step4Title') }}</div>

      <div v-if="drillSuggestions.length === 0" class="text-body2 q-mb-lg" style="color: var(--text-secondary)">
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

      <q-btn
        unelevated
        class="wrap-up__next-btn"
        :label="t('booking.wrapUp.step4Done')"
        :loading="submitting"
        @click="handleSubmitWrapUp"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { getDrillSuggestions } from 'src/api/booking.api'
import { signUpload, confirmUpload } from 'src/api/marketplace.api'

const props = defineProps({
  bookingId: { type: String, required: true },
  playerId: { type: [Number, String], required: true },
  playerName: { type: String, required: true },
  isLiveMode: { type: Boolean, default: true },
})

const emit = defineEmits(['wrap-up-complete', 'cancelled'])

const { t } = useI18n()
const bookingStore = useBookingStore()

const step = ref(1)
const playerAttended = ref(true)
const attendanceTouched = ref(false)
const effortRating = ref(0)
const focusRating = ref(0)
const techniqueRating = ref(0)
const currentRating = ref('effort')
const voiceNoteText = ref('')
const isRecording = ref(false)
const recorder = ref(null)
const homeworkDrillIds = ref([])
const drillSuggestions = ref([])
const submitting = ref(false)

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

async function startRecording() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const chunks = []
    recorder.value = new MediaRecorder(stream)
    recorder.value.ondataavailable = (e) => chunks.push(e.data)
    recorder.value.onstop = async () => {
      const blob = new Blob(chunks, { type: 'audio/webm' })
      stream.getTracks().forEach((t) => t.stop())
      try {
        const signRes = await signUpload({
          entity: 'booking',
          entityId: props.bookingId,
          contentType: 'audio/webm',
          extension: 'webm',
          fileSizeBytes: blob.size,
          checksum: null,
          tags: {},
        })
        const { key, uploadUrl } = signRes.data
        await fetch(uploadUrl, { method: 'PUT', body: blob, headers: { 'Content-Type': 'audio/webm' } })
        await confirmUpload(key, {})
        voiceNoteText.value = '[Voice note recorded — transcription pending]'
      } catch (e) {
        console.warn('Voice note upload failed:', e)
        voiceNoteText.value = '[Voice note recorded — transcription pending]'
      }
    }
    recorder.value.start()
    isRecording.value = true
  } catch (e) {
    console.warn('MediaRecorder not available:', e)
  }
}

function stopRecording() {
  if (recorder.value) {
    recorder.value.stop()
    recorder.value = null
  }
  isRecording.value = false
}

async function fetchDrillSuggestions() {
  try {
    const res = await getDrillSuggestions(props.bookingId)
    drillSuggestions.value = res.data ?? []
  } catch {
    drillSuggestions.value = []
  }
}

async function handleSubmitWrapUp() {
  submitting.value = true
  try {
    await bookingStore.handleSubmitWrapUp(props.bookingId, {
      playerAttended: playerAttended.value,
      effortRating: effortRating.value || null,
      focusRating: focusRating.value || null,
      techniqueRating: techniqueRating.value || null,
      voiceNoteText: voiceNoteText.value || null,
      homeworkDrillIds: homeworkDrillIds.value,
      mode: props.isLiveMode ? 'LIVE' : 'QUICK',
    })
    emit('wrap-up-complete')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  fetchDrillSuggestions()
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

.wrap-up__rating-row {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.wrap-up__voice-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: center;
}

.wrap-up__mic-btn,
.wrap-up__skip-btn {
  flex: 1;
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
