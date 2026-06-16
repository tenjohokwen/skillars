<template>
  <div class="active-session">
    <!-- Back button — only before 5 minutes have elapsed (coach tapped Start by accident) -->
    <q-btn
      v-if="!endAllowed"
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

    <!-- End Session button -->
    <q-btn
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'

const props = defineProps({
  bookingId: { type: String, required: true },
  playerName: { type: String, required: true },
  sessionStartTime: { type: String, required: true },
})

const emit = defineEmits(['session-ended', 'close'])

const { t } = useI18n()
const bookingStore = useBookingStore()

const elapsed = ref(0)
const ending = ref(false)
let timer = null

const endAllowed = computed(() => elapsed.value >= 300)

const formattedElapsed = computed(() => {
  const h = Math.floor(elapsed.value / 3600)
  const m = Math.floor((elapsed.value % 3600) / 60)
  const s = elapsed.value % 60
  return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':')
})

async function handleEndSession() {
  ending.value = true
  try {
    await bookingStore.handleEndSession(props.bookingId)
    emit('session-ended')
  } finally {
    ending.value = false
  }
}

onMounted(() => {
  timer = setInterval(() => {
    elapsed.value++
  }, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
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
