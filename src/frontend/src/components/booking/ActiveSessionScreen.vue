<template>
  <div class="active-session">
    <!-- Back button — only before 5 minutes have elapsed (coach tapped Start by accident) -->
    <q-btn
      v-if="!isPaused && elapsed < 300"
      flat
      dense
      round
      icon="arrow_back"
      class="active-session__back"
      @click="$emit('close')"
    />

    <!-- Drill plan stub -->
    <div class="active-session__drill-name">{{ t('booking.completion.noDrillPlan', 'No drill plan') }}</div>

    <!-- Timer -->
    <div class="active-session__timer">{{ formattedElapsed }}</div>

    <!-- Block progress pips -->
    <div class="active-session__pips">
      <div
        v-for="n in 4"
        :key="n"
        class="active-session__pip"
      />
    </div>

    <!-- Next drill stub -->
    <div class="active-session__next-drill" style="color: var(--text-secondary)">
      {{ t('booking.completion.nextDrill', 'Next drill: —') }}
    </div>

    <!-- Action error feedback -->
    <div v-if="actionError" class="active-session__error">{{ actionError }}</div>

    <!-- PAUSED indicator -->
    <div v-if="isPaused" class="active-session__paused-indicator">
      {{ t('booking.completion.paused') }}
    </div>

    <!-- Pause button: shown when not paused -->
    <q-btn
      v-if="!isPaused"
      flat
      class="active-session__pause-btn"
      :label="t('booking.completion.pause')"
      :loading="pausing"
      @click="handlePauseSession"
    />

    <!-- Resume button: shown when paused -->
    <q-btn
      v-if="isPaused"
      unelevated
      class="active-session__resume-btn"
      :label="t('booking.completion.resume')"
      :loading="resuming"
      @click="handleResumeSession"
    />

    <!-- End Session button: hidden while paused -->
    <q-btn
      v-if="!isPaused"
      unelevated
      class="active-session__end-btn"
      :label="endAllowed ? t('booking.completion.endSession') : t('booking.completion.endSessionDisabled')"
      :disable="!endAllowed"
      :loading="ending"
      @click="handleEndSession"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'

const props = defineProps({
  bookingId: { type: String, required: true },
  playerName: { type: String, required: true },
  sessionStartTime: { type: String, required: true },
  bookingStatus: { type: String, required: true },
})

const emit = defineEmits(['session-ended', 'close'])

const { t } = useI18n()
const bookingStore = useBookingStore()

const elapsed = ref(0)
const ending = ref(false)
const isPaused = ref(false)
const pausing = ref(false)
const resuming = ref(false)
const actionError = ref(null)
let timer = null

const endAllowed = computed(() => !isPaused.value && elapsed.value >= 300)

function startTimer() {
  if (timer) return
  timer = setInterval(() => { elapsed.value++ }, 1000)
}

function stopTimer() {
  clearInterval(timer)
  timer = null
}

const formattedElapsed = computed(() => {
  const h = Math.floor(elapsed.value / 3600)
  const m = Math.floor((elapsed.value % 3600) / 60)
  const s = elapsed.value % 60
  return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':')
})

async function handleEndSession() {
  ending.value = true
  actionError.value = null
  try {
    await bookingStore.handleEndSession(props.bookingId)
    emit('session-ended')
  } catch {
    actionError.value = t('booking.completion.actionError')
  } finally {
    ending.value = false
  }
}

async function handlePauseSession() {
  pausing.value = true
  actionError.value = null
  stopTimer()
  isPaused.value = true
  try {
    await bookingStore.handlePauseSession(props.bookingId)
  } catch {
    isPaused.value = false
    startTimer()
    actionError.value = t('booking.completion.actionError')
  } finally {
    pausing.value = false
  }
}

async function handleResumeSession() {
  resuming.value = true
  actionError.value = null
  try {
    await bookingStore.handleResumeSession(props.bookingId)
    isPaused.value = false
    startTimer()
  } catch {
    actionError.value = t('booking.completion.actionError')
  } finally {
    resuming.value = false
  }
}

// SSE-driven status sync: handles remote pause/resume (other device/tab)
watch(() => props.bookingStatus, (status) => {
  if (status === 'PAUSED' && !isPaused.value) {
    isPaused.value = true
    stopTimer()
  } else if (status === 'IN_PROGRESS' && isPaused.value) {
    isPaused.value = false
    startTimer()
  }
})

onMounted(() => {
  if (props.bookingStatus === 'PAUSED') {
    isPaused.value = true
    // Do NOT start the interval — session is already paused server-side
  } else {
    startTimer()
  }
})

onUnmounted(() => {
  stopTimer()
})
</script>

<style lang="scss" scoped>
.active-session {
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
  justify-content: center;
  padding: 24px;
}

.active-session__back {
  position: absolute;
  top: 16px;
  left: 16px;
}

.active-session__drill-name {
  font-size: 42px;
  font-weight: 800;
  text-align: center;
  margin-bottom: 24px;
}

.active-session__timer {
  font-size: 72px;
  font-weight: 700;
  background: linear-gradient(135deg, var(--accent-primary), var(--accent-secondary, var(--accent-primary)));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin-bottom: 32px;
  font-variant-numeric: tabular-nums;
}

.active-session__pips {
  display: flex;
  gap: 8px;
  margin-bottom: 24px;
}

.active-session__pip {
  width: 40px;
  height: 8px;
  border-radius: 4px;
  background: var(--border-subtle);
}

.active-session__next-drill {
  font-size: 16px;
  margin-bottom: 48px;
}

.active-session__error {
  color: var(--accent-error, #ef4444);
  font-size: 14px;
  margin-bottom: 12px;
  text-align: center;
}

.active-session__paused-indicator {
  font-size: 24px;
  font-weight: 700;
  color: var(--accent-warning);
  letter-spacing: 0.1em;
  margin-bottom: 24px;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.active-session__pause-btn {
  width: 100%;
  max-width: 400px;
  height: 48px;
  color: var(--text-secondary);
  margin-bottom: 12px;
}

.active-session__resume-btn {
  width: 100%;
  max-width: 400px;
  height: 56px;
  background: var(--accent-warning);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  border-radius: 12px;
  margin-bottom: 12px;
}

.active-session__end-btn {
  width: 100%;
  max-width: 400px;
  height: 56px;
  background: var(--accent-primary);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  border-radius: 12px;

  &:disabled,
  &[disabled] {
    opacity: 0.4;
  }
}
</style>
